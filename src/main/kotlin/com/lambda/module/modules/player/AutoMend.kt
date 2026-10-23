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
import com.lambda.context.SafeContext
import com.lambda.module.Module
import com.lambda.module.modules.combat.AutoTotem
import com.lambda.module.tag.ModuleTag
import com.lambda.task.RootTask.run
import com.lambda.task.Task
import com.lambda.task.tasks.MendConfig
import com.lambda.task.tasks.MendToolsTask
import com.lambda.util.BlockUtils.blockState
import com.lambda.util.ClickSetup
import com.lambda.util.CommunicationUtils.info
import com.lambda.util.CommunicationUtils.logError
import com.lambda.util.EnchantmentUtils.getEnchantment
import com.lambda.util.item.ItemStackUtils.durabilityFraction
import com.lambda.util.item.ItemUtils
import com.lambda.util.item.ToolFilter
import com.lambda.util.world.blockSearch
import net.minecraft.block.ChestBlock
import net.minecraft.enchantment.Enchantments
import net.minecraft.item.ItemStack
import net.minecraft.util.math.BlockPos

@Suppress("unused")
object AutoMend : Module(
    name = "AutoMend",
    description = "Mends tools in the offhand at an XP farm by sweeping its armor stand, fed from chests",
    tag = ModuleTag.PLAYER,
), MendConfig {
    private val setup by setting("Setup", { clickSetup.toggle() }, "Set the chests and where to stand by clicking them")
    private var damagedChest by setting("Damaged Chest", BlockPos.ORIGIN, "Chest to take tools from. At 0 0 0 it uses the upper of two stacked chests nearby")
    private var repairedChest by setting("Repaired Chest", BlockPos.ORIGIN, "Chest to put mended and undamaged tools into. At 0 0 0 it uses the lower of two stacked chests nearby")
    private var standPos by setting("Stand Pos", BlockPos.ORIGIN, "Where to stand while hitting. At 0 0 0 it uses where you are")
    private val tool by setting("Tool", ToolFilter.AnyPickaxe, "Tools to take and mend. Damaged ones without mending are left in the chest")
    private val mendTo by setting("Mend To", 100, 1..100, 1, "Durability to mend tools to", "%")
    override val leaveInChest by setting("Leave In Chest", 1, 0..27, 1, "Tools to leave, for farms that stop when the chest is empty")
    override val maxTools by setting("Max Tools", 27, 1..36, 1, "Most tools to take per trip")
    override val onlyWithMobs by setting("Only With Mobs", true, "Only hit when a mob can be swept")
    override val rotate by setting("Rotate", true, "Rotate towards the armor stand")
    override val minHitInterval by setting("Min Hit Interval", 12, 6..40, 1, "Least time between hits, the armor stand breaks when hit twice within 5 ticks", " ticks")
    override val stallTimeout by setting("Stall Timeout", 60, 10..600, 5, "Stop when the offhand tool isn't mended for this long", " s")
    override val moveDelay by setting("Move Delay", 10, 2..40, 1, "Ticks between inventory clicks", " ticks")
    private val waitForAutoEat by setting("Wait For AutoEat", true, "Pause while AutoEat is eating")

    private var mendTask: Task<*>? = null

    private val clickSetup = ClickSetup(
        this,
        listOf(
            ClickSetup.Step("Damaged Chest", "the chest with the damaged tools", ::damagedChest, ClickSetup.container),
            ClickSetup.Step("Repaired Chest", "the chest for the mended tools", ::repairedChest, ClickSetup.container),
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
            val (damaged, repaired) = resolveChests() ?: run {
                info("No stacked chests next to you, so set them up")
                disable()
                return@onEnable clickSetup.start()
            }
            val stand = standPos.orUnset ?: player.blockPos
            damagedChest = damaged
            repairedChest = repaired
            standPos = stand
            info("Taking from ${damaged.toShortString()}, putting into ${repaired.toShortString()}, standing at ${stand.toShortString()}")

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

    override fun isTool(stack: ItemStack) = tool.matches(stack.item) && stack.isDamageable

    override fun canMend(stack: ItemStack) = stack.getEnchantment(Enchantments.MENDING) > 0

    override fun isMended(stack: ItemStack) = stack.durabilityFraction >= mendTo / 100f

    override fun isSword(stack: ItemStack) = stack.item in ItemUtils.swords &&
        stack.getEnchantment(Enchantments.SWEEPING_EDGE) > 0 && stack.getEnchantment(Enchantments.FIRE_ASPECT) == 0

    /** The set chests, or else the closest two stacked ones, the upper for damaged tools. */
    private fun SafeContext.resolveChests(): Pair<BlockPos, BlockPos>? {
        val damaged = damagedChest.orUnset
        val repaired = repairedChest.orUnset
        if (damaged != null && repaired != null) return damaged to repaired

        val lower = blockSearch(SEARCH_RADIUS) { pos, state -> state.block is ChestBlock && blockState(pos.up()).block is ChestBlock }
            .keys.minByOrNull { it.getSquaredDistance(player.pos) }
            ?: return null
        return (damaged ?: lower.up()) to (repaired ?: lower)
    }

    private const val SEARCH_RADIUS = 4
}
