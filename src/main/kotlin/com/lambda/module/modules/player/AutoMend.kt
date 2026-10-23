/*
 * Copyright 2026 Lambda
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.lambda.module.modules.player

import com.lambda.config.automation.AutomationConfig.Companion.setDefaultAutomationConfig
import com.lambda.config.hideAllExcept
import com.lambda.config.settings.complex.orUnset
import com.lambda.config.withEdits
import com.lambda.module.Module
import com.lambda.module.modules.combat.AutoArmor
import com.lambda.module.modules.combat.AutoTotem
import com.lambda.module.tag.ModuleTag
import com.lambda.task.RootTask.run
import com.lambda.task.Task
import com.lambda.task.tasks.MendConfig
import com.lambda.task.tasks.MendToolsTask
import com.lambda.util.ClickSetup
import com.lambda.util.CommunicationUtils.info
import com.lambda.util.CommunicationUtils.logError
import com.lambda.util.Describable
import com.lambda.util.EnchantmentUtils.getEnchantment
import com.lambda.util.NamedEnum
import com.lambda.util.item.ItemStackUtils.durabilityFraction
import com.lambda.util.item.ItemUtils
import net.minecraft.component.DataComponentTypes
import net.minecraft.enchantment.Enchantments
import net.minecraft.entity.EquipmentSlot
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.util.math.BlockPos

@Suppress("unused")
object AutoMend : Module(
    name = "AutoMend",
    description = "Mends tools, armor or elytras at an XP farm by sweeping its armor stand, worn and in the offhand",
    tag = ModuleTag.PLAYER,
), MendConfig {
    private val setup by setting("Setup", { clickSetup.toggle() }, "Set the chests and where to stand by clicking them")
    private val reset by setting("Reset", { clickSetup.reset() }, "Set all positions back to 0 0 0, so it mends what you carry where you stand")
    private var damagedChest by setting("Damaged Chest", BlockPos.ORIGIN, "Chest to take damaged items from. Without both chests it mends what you carry and stops")
    private var repairedChest by setting("Repaired Chest", BlockPos.ORIGIN, "Chest to put mended and undamaged items into. Without both chests it mends what you carry and stops")
    private var standPos by setting("Stand Pos", BlockPos.ORIGIN, "Where to stand while hitting. At 0 0 0 it uses where you are")
    private val items by setting("Items", MendItems.AnyPickaxe, "What to mend. Armor and elytras are worn while mending, so several mend at once")
    private val mendTo by setting("Mend To", 100, 1..100, 1, "Durability to mend to", "%")
    override val leaveInChest by setting("Leave In Chest", 1, 0..27, 1, "Items to leave, for farms that stop when the chest is empty")
    override val maxTools by setting("Max Items", 27, 1..36, 1, "Most items to take per trip")
    override val onlyWithMobs by setting("Only With Mobs", true, "Only hit when a mob can be swept")
    override val rotate by setting("Rotate", true, "Rotate towards the armor stand")
    override val minHitInterval by setting("Min Hit Interval", 12, 6..40, 1, "Least time between hits, the armor stand breaks when hit twice within 5 ticks", " ticks")
    override val stallTimeout by setting("Stall Timeout", 60, 10..600, 5, "Stop when nothing gets mended for this long", " s")
    override val moveDelay by setting("Move Delay", 10, 2..40, 1, "Ticks between inventory clicks", " ticks")
    private val waitForAutoEat by setting("Wait For AutoEat", true, "Pause while AutoEat is eating")

    enum class MendItems(
        override val displayName: String,
        override val description: String,
        val wearable: Boolean,
        val matches: (ItemStack) -> Boolean,
    ) : NamedEnum, Describable {
        NetheritePickaxe("Netherite Pickaxe", "Only netherite pickaxes", false, { it.isOf(Items.NETHERITE_PICKAXE) }),
        DiamondPickaxe("Diamond Pickaxe", "Only diamond pickaxes", false, { it.isOf(Items.DIAMOND_PICKAXE) }),
        AnyPickaxe("Any Pickaxe", "Pickaxes of any material", false, { it.item in ItemUtils.pickaxes }),
        AnyTool("Any Tool", "Any tool except swords, so the one you hit with stays", false, { it.item in ItemUtils.tools && it.item !in ItemUtils.swords }),
        Elytra("Elytra", "Elytras, one worn and one in the offhand", true, { it.isOf(Items.ELYTRA) }),
        Armor("Armor", "Armor pieces, worn and in the offhand", true, { it.isArmor && !it.isOf(Items.ELYTRA) }),
        AnyWearables("Any Wearables", "Armor pieces and elytras, worn and in the offhand", true, { it.isArmor }),
    }

    private var mendTask: Task<*>? = null

    private val clickSetup = ClickSetup(
        this,
        listOf(
            ClickSetup.Step("Damaged Chest", "the chest with the damaged items", ::damagedChest, ClickSetup.container),
            ClickSetup.Step("Repaired Chest", "the chest for the mended items", ::repairedChest, ClickSetup.container),
            ClickSetup.Step("Stand Pos", "any block while standing where you hit from", ::standPos, ClickSetup.standing),
        ),
    )

    init {
        setDefaultAutomationConfig()
            .withEdits {
                hideAllExcept(::rotationConfig, ::inventoryConfig, ::interactConfig, ::hotbarConfig, ::buildConfig)
            }

        onEnable {
            clickSetup.cancel()
            if (AutoTotem.isEnabled) {
                logError("Turn off AutoTotem first, it swaps the offhand back. Your offhand item is put back when done")
                return@onEnable disable()
            }
            if (items.wearable && AutoArmor.isEnabled) {
                logError("Turn off AutoArmor first, it swaps the armor back. Your armor is put back when done")
                return@onEnable disable()
            }
            val damaged = damagedChest.orUnset
            val repaired = repairedChest.orUnset
            if ((damaged == null) != (repaired == null)) {
                logError("Set both chests, or neither to mend what you carry. Press Setup or Reset")
                return@onEnable disable()
            }
            val stand = standPos.orUnset ?: player.blockPos
            standPos = stand
            if (damaged != null) info("Taking from ${damaged.toShortString()}, putting into ${repaired?.toShortString()}, standing at ${stand.toShortString()}")
            else info("No chests set, mending what you carry, standing at ${stand.toShortString()}")

            mendTask = MendToolsTask(
                damagedChest = damaged,
                repairedChest = repaired,
                standPos = stand,
                config = this@AutoMend,
                automated = this@AutoMend,
            ).onFailure {
                logError("AutoMend stopped: ${it.message}")
            }.onCompletion {
                mendTask = null
                disable()
            }.run()
        }

        onDisable {
            mendTask?.cancel()
            mendTask = null
        }
    }

    override val waiting get() = waitForAutoEat && AutoEat.isEating

    override fun isTool(stack: ItemStack) = items.matches(stack) && stack.isDamageable

    override fun canMend(stack: ItemStack) = stack.getEnchantment(Enchantments.MENDING) > 0

    override fun isMended(stack: ItemStack) = stack.durabilityFraction >= mendTo / 100f

    override fun isSword(stack: ItemStack) = stack.item in ItemUtils.swords &&
        stack.getEnchantment(Enchantments.SWEEPING_EDGE) > 0 && stack.getEnchantment(Enchantments.FIRE_ASPECT) == 0

    /** Worn in an armor slot, which elytras are too. */
    private val ItemStack.isArmor get() =
        get(DataComponentTypes.EQUIPPABLE)?.slot()?.type == EquipmentSlot.Type.HUMANOID_ARMOR
}
