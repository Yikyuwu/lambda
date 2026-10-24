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
import com.lambda.event.events.MovementEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.module.Module
import com.lambda.module.modules.combat.AutoArmor
import com.lambda.module.modules.combat.AutoTotem
import com.lambda.module.tag.ModuleTag
import com.lambda.task.RootTask.run
import com.lambda.task.Task
import com.lambda.task.tasks.MendConfig
import com.lambda.task.tasks.MendToolsTask
import com.lambda.util.BlockUtils.blockState
import com.lambda.util.BlockUtils.fluidState
import com.lambda.util.BlockUtils.hasSolidGround
import com.lambda.util.BlockUtils.isPassable
import com.lambda.util.ClickSetup
import com.lambda.util.CommunicationUtils.info
import com.lambda.util.CommunicationUtils.logError
import com.lambda.util.Describable
import com.lambda.util.EnchantmentUtils.getEnchantment
import com.lambda.util.NamedEnum
import com.lambda.util.item.GearKind
import com.lambda.util.item.GearKindChoice
import com.lambda.util.item.ItemStackUtils.durabilityFraction
import com.lambda.util.item.ItemUtils
import com.lambda.util.player.MovementUtils.update
import com.lambda.util.player.SlotUtils.hotbarStacks
import net.minecraft.block.AbstractPressurePlateBlock
import net.minecraft.enchantment.Enchantments
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.cos
import kotlin.math.sin

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
    private val group by setting("Group", MendGroup.None, "What to mend. Armor and elytras are worn while mending, so several mend at once. At None the Kind below is used")
    private val kind by setting("Kind", GearKindChoice.Pickaxe, "One kind of item to mend, of any material. At None the Item below is used") { group == MendGroup.None }
    private val item by setting("Item", MendItem.None, "One diamond or netherite item to mend") { group == MendGroup.None && kind == GearKindChoice.None }
    private val mendTo by setting("Mend To", 100, 1..100, 1, "Durability to mend to", "%")
    override val leaveInChest by setting("Leave In Chest", 1, 0..27, 1, "Items to leave, for farms that stop when the chest is empty")
    override val maxTools by setting("Max Items", 27, 1..36, 1, "Most items to take per trip")
    override val onlyWithMobs by setting("Only With Mobs", true, "Only hit when a mob can be swept")
    override val rotate by setting("Rotate", true, "Rotate towards the armor stand")
    override val minHitInterval by setting("Min Hit Interval", 12, 6..40, 1, "Least time between hits, the armor stand breaks when hit twice within 5 ticks", " ticks")
    override val stallTimeout by setting("Stall Timeout", 60, 10..600, 5, "Stop when nothing gets mended for this long", " s")
    override val moveDelay by setting("Move Delay", 10, 2..40, 1, "Ticks between inventory clicks", " ticks")
    private val waitForAutoEat by setting("Wait For AutoEat", true, "Pause while AutoEat is eating")

    enum class MendGroup(
        override val displayName: String,
        override val description: String,
        val wearable: Boolean,
        val matches: (ItemStack) -> Boolean,
    ) : NamedEnum, Describable {
        None("None", "Mend the Kind or Item below instead", false, { false }),
        AnyTool("Any Tool", "Pickaxes, axes, shovels and hoes of any material", false, { it.isGear(GearKind.Category.Tool) }),
        AnyArmor("Any Armor", "Helmets, chestplates, leggings and boots, worn and in the offhand", true, { it.isGear(GearKind.Category.Armor) }),
        Elytras("Elytras", "Elytras, one worn and one in the offhand", true, { it.isOf(Items.ELYTRA) }),
        AnyWearables("Any Wearables", "Armor and elytras, worn and in the offhand", true, { it.isGear(GearKind.Category.Armor) || it.isOf(Items.ELYTRA) }),
    }

    enum class MendItem(override val displayName: String, val kind: GearKind?, netherite: Boolean = false) : NamedEnum {
        None("None", null),
        NetheritePickaxe("Netherite Pickaxe", GearKind.Pickaxe, true),
        DiamondPickaxe("Diamond Pickaxe", GearKind.Pickaxe),
        NetheriteAxe("Netherite Axe", GearKind.Axe, true),
        DiamondAxe("Diamond Axe", GearKind.Axe),
        NetheriteShovel("Netherite Shovel", GearKind.Shovel, true),
        DiamondShovel("Diamond Shovel", GearKind.Shovel),
        NetheriteHoe("Netherite Hoe", GearKind.Hoe, true),
        DiamondHoe("Diamond Hoe", GearKind.Hoe),
        NetheriteSword("Netherite Sword", GearKind.Sword, true),
        DiamondSword("Diamond Sword", GearKind.Sword),
        NetheriteSpear("Netherite Spear", GearKind.Spear, true),
        DiamondSpear("Diamond Spear", GearKind.Spear),
        NetheriteHelmet("Netherite Helmet", GearKind.Helmet, true),
        DiamondHelmet("Diamond Helmet", GearKind.Helmet),
        NetheriteChestplate("Netherite Chestplate", GearKind.Chestplate, true),
        DiamondChestplate("Diamond Chestplate", GearKind.Chestplate),
        NetheriteLeggings("Netherite Leggings", GearKind.Leggings, true),
        DiamondLeggings("Diamond Leggings", GearKind.Leggings),
        NetheriteBoots("Netherite Boots", GearKind.Boots, true),
        DiamondBoots("Diamond Boots", GearKind.Boots);

        val item = kind?.let { if (netherite) it.netherite else it.diamond }
    }

    /** What the settings pick, the first of Group, Kind and Item that isn't None. Null when all are. */
    private val picked: ((ItemStack) -> Boolean)?
        get() {
            if (group != MendGroup.None) return group.matches
            val kind = kind.kind ?: return item.item?.let { picked -> { it.isOf(picked) } }
            return kind::matches
        }

    private val picksWearables get() =
        if (group != MendGroup.None) group.wearable else (kind.kind ?: item.kind)?.category == GearKind.Category.Armor

    private var mendTask: Task<*>? = null
    /** The block beside the stand position it steps off to when done, see [stepOff]. */
    private var stepOffTarget: BlockPos? = null
    private var stepOffTicks = 0

    private val clickSetup = ClickSetup(
        this,
        listOf(
            ClickSetup.Step("Damaged Chest", "the chest with the damaged items", ::damagedChest, ClickSetup.container),
            ClickSetup.Step("Repaired Chest", "the chest for the mended items", ::repairedChest, ClickSetup.container),
            ClickSetup.Step("Stand Pos", "the top of the floor you hit from, e.g. the pressure plate", ::standPos, ClickSetup.standing),
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
            if (picked == null) {
                logError("Pick what to mend: a Group, a Kind or an Item")
                return@onEnable disable()
            }
            if (picksWearables && AutoArmor.isEnabled) {
                logError("Turn off AutoArmor first, it swaps the armor back. Your armor is put back when done")
                return@onEnable disable()
            }
            if (player.hotbarStacks.none(::isSword)) {
                logError("Put a sword with Sweeping Edge III and no Fire Aspect in your hotbar. Fire Aspect would burn the mobs and lose their XP")
                return@onEnable disable()
            }
            val damaged = damagedChest.orUnset
            val repaired = repairedChest.orUnset
            if ((damaged == null) != (repaired == null)) {
                logError("Set both chests, or neither to mend what you carry. Press Setup or Reset")
                return@onEnable disable()
            }
            val stand = standPos.orUnset ?: player.blockPos
            if (damaged != null) {
                // The farm's spot is kept for next time, mending what you carry happens wherever you are.
                standPos = stand
                info("Taking from ${damaged.toShortString()}, putting into ${repaired?.toShortString()}, standing at ${stand.toShortString()}")
            } else info("No chests set, mending what you carry, standing at ${stand.toShortString()}")

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
                if (damaged == null || !stepOff(stand)) disable()
            }.run()
        }

        // Only the movement keys, no pathfinding that could break or place blocks to get there.
        listen<MovementEvent.InputUpdate> {
            val target = stepOffTarget ?: return@listen
            val center = target.toBottomCenterPos()
            val dx = center.x - player.x
            val dz = center.z - player.z
            if (dx * dx + dz * dz < STEP_OFF_REACHED * STEP_OFF_REACHED && player.blockPos == target || ++stepOffTicks > STEP_OFF_TICKS) {
                if (player.blockPos != target) info("Couldn't step off to ${target.toShortString()}")
                it.input.update(forward = 0.0, strafe = 0.0, jump = false, sneak = false, sprint = false)
                stepOffTarget = null
                return@listen disable()
            }
            // From the target direction to the keys, relative to where the player looks.
            val yaw = Math.toRadians(player.yaw.toDouble())
            it.input.update(
                forward = -dx * sin(yaw) + dz * cos(yaw),
                strafe = dx * cos(yaw) + dz * sin(yaw),
                jump = target.y > player.blockPos.y && player.isOnGround,
                sneak = false,
                sprint = false,
            )
        }

        onDisable {
            stepOffTarget = null
            mendTask?.cancel()
            mendTask = null
        }
    }

    override val waiting get() = waitForAutoEat && AutoEat.isEating

    override fun isTool(stack: ItemStack) = picked?.invoke(stack) == true && stack.isDamageable

    override fun canMend(stack: ItemStack) = stack.getEnchantment(Enchantments.MENDING) > 0

    override fun isMended(stack: ItemStack) = stack.durabilityFraction >= mendTo / 100f

    override fun isSword(stack: ItemStack) = stack.item in ItemUtils.swords &&
        stack.getEnchantment(Enchantments.SWEEPING_EDGE) >= 3 && stack.getEnchantment(Enchantments.FIRE_ASPECT) == 0

    /**
     * Starts stepping off [stand] when done, so it doesn't keep the farm running while it stands there: to a free block
     * beside or diagonal to it, on the same level or one up. Whether it found one.
     */
    private fun SafeContext.stepOff(stand: BlockPos): Boolean {
        if (player.blockPos != stand) return false
        fun free(pos: BlockPos) = isPassable(pos) && isPassable(pos.up()) && fluidState(pos).isEmpty && fluidState(pos.up()).isEmpty
        fun canStand(pos: BlockPos) = free(pos) && hasSolidGround(pos) && blockState(pos).block !is AbstractPressurePlateBlock
        val sides = Direction.Type.HORIZONTAL.toList()
        val beside = sides.map { stand.offset(it) }
        // Diagonal only past a free side, else it gets stuck on the corners.
        val diagonal = sides.map { stand.offset(it).offset(it.rotateYClockwise()) }
            .filter { pos -> beside.any { free(it) && it.getManhattanDistance(pos) == 1 } }
        // One up only with room to jump.
        val up = if (isPassable(stand.up(2))) (beside + diagonal).map { it.up() } else emptyList()
        stepOffTarget = (beside + diagonal + up).firstOrNull(::canStand)
            ?: return false.also { info("No free block beside ${stand.toShortString()} to step off to") }
        stepOffTicks = 0
        return true
    }

    private const val STEP_OFF_TICKS = 60
    private const val STEP_OFF_REACHED = 0.3

    private fun ItemStack.isGear(category: GearKind.Category) = GearKind.entries.any { it.category == category && it.matches(this) }
}
