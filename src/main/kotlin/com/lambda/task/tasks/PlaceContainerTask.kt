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

package com.lambda.task.tasks

import com.lambda.context.Automated
import com.lambda.context.SafeContext
import com.lambda.event.events.TickEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.interaction.construction.blueprint.Blueprint.Companion.toStructure
import com.lambda.interaction.construction.blueprint.StaticBlueprint.Companion.toBlueprint
import com.lambda.interaction.construction.simulation.result.results.GenericResult
import com.lambda.interaction.construction.simulation.result.results.InteractResult
import com.lambda.interaction.construction.simulation.sim
import com.lambda.interaction.construction.verify.TargetState
import com.lambda.interaction.handlers.breaking.RebreakHandler
import com.lambda.interaction.managers.ManagerUtils
import com.lambda.interaction.managers.breaking.BreakManager
import com.lambda.task.Task
import com.lambda.task.tasks.BuildTask.Companion.build
import com.lambda.task.wrappers.thenAction
import com.lambda.threading.runSafe
import com.lambda.threading.runSafeAutomated
import com.lambda.util.BlockUtils.blockPos
import com.lambda.util.BlockUtils.blockState
import com.lambda.util.BlockUtils.hasFluid
import com.lambda.util.BlockUtils.hasSolidGround
import com.lambda.util.BlockUtils.isPassable
import com.lambda.util.BlockUtils.offset
import com.lambda.util.item.ItemUtils.shulkerBoxes
import com.lambda.util.math.distSq
import com.lambda.util.player.SlotUtils.hotbarAndInventorySlots
import net.minecraft.block.Block
import net.minecraft.block.ChestBlock
import net.minecraft.entity.mob.ShulkerEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.tag.BlockTags
import net.minecraft.screen.slot.Slot
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.EightWayDirection

/**
 * Places the container in [slot] at the closest openable spot at foot level, trying a few spots and scans.
 * Shulkers face up, their default state, so they go on top of the block below.
 * Once it left the inventory no other spot is tried, so a slow server can't get two placed.
 * It waits for breaking to stop and the player to stand first. The server may still finish a break the client gave up on,
 * breaking a container placed where that block was, and in the air the spots at foot level have no ground.
 */
class PlaceContainerTask @Ta5kBuilder constructor(
    slot: Slot,
    automated: Automated,
    private val positionFilter: SafeContext.(BlockPos) -> Boolean = { true },
) : Task<BlockPos>(), Automated by automated {
    /** The container as it was chosen. The slot holds something else once it moved to the hotbar. */
    private val stack = slot.stack.copy()
    override val name: String get() = "Placing container ${stack.name.string}"

    private val container = Block.getBlockFromItem(stack.item)
    private var spot: BlockPos? = null
    private var build: Task<*>? = null
    private val triedSpots = mutableSetOf<BlockPos>()
    private var carriedAtStart = 0
    private var attempts = 0
    private var ticks = 0
    private var quietTicks = 0
    private var waitedTicks = 0
    /** Why the last scan found no spot. */
    private var lastScan = ""

    private val settled get() = quietTicks >= QUIET_TICKS || waitedTicks >= MAX_QUIET_WAIT_TICKS

    override fun SafeContext.onStart() {
        carriedAtStart = carried()
    }

    init {
        // Also while paused for the build, so a spot that doesn't work times out.
        listen<TickEvent.Pre>(alwaysListen = true) {
            if (!isActive) return@listen
            // Held by a station task, e.g. for a fight, so a spot doesn't time out meanwhile.
            if (state == State.Paused && build?.state != State.Running) return@listen
            // Already down, maybe at a spot that was given up on, the build just didn't see it yet.
            triedSpots.firstOrNull { blockState(it).block == container }?.let {
                build?.cancel()
                return@listen success(it)
            }
            if (spot == null) {
                if (!settled) {
                    waitedTicks++
                    quietTicks = if (BreakManager.blockedPositions.isEmpty() && player.isOnGround) quietTicks + 1 else 0
                } else if (--ticks <= 0) scan()
                return@listen
            }
            if (++ticks >= PLACE_TIMEOUT_TICKS) skipSpot()
        }
    }

    override fun onSubTaskFailure(subTask: Task<*>, cause: Throwable) {
        if (subTask === build) runSafe { skipSpot() } else super.onSubTaskFailure(subTask, cause)
    }

    private fun SafeContext.scan() {
        val containerPosition = findPosition() ?: run {
            if (++attempts >= MAX_ATTEMPTS) return failure("Couldn't find a valid container placement position for ${stack.name.string} ($lastScan)")
            ticks = SCAN_INTERVAL_TICKS
            return
        }

        spot = containerPosition
        triedSpots += containerPosition
        ticks = 0
        build = containerPosition
            .toStructure(TargetState.Stack(stack))
            .toBlueprint()
            .build(finishOnDone = true, collectDrops = false)
            .thenAction { success(containerPosition) }
            .execute(this@PlaceContainerTask)
    }

    private fun SafeContext.skipSpot() {
        // It left the inventory, so it's on its way to one of the tried spots. Wait for it instead of placing another.
        if (carried() < carriedAtStart) {
            if (ticks < LEFT_INVENTORY_TIMEOUT_TICKS) return
            return failure("${stack.name.string} left the inventory but didn't show up")
        }
        build?.cancel()
        build = null
        spot = null
        ticks = 0
        if (++attempts >= MAX_ATTEMPTS) failure("Couldn't place ${stack.name.string} at any spot")
    }

    private fun SafeContext.carried() = player.hotbarAndInventorySlots.count { ItemStack.areItemsAndComponentsEqual(it.stack, stack) }

    private fun SafeContext.findPosition(): BlockPos? {
        // ToDo: Check based on if we can move the player close enough rather than y level once the custom pathfinder is merged
        // On a partial block like soul sand the feet are inside it.
        val feet = player.blockPos.let { if (isPassable(it)) it else it.up() }
        val spots = BlockPos.iterateOutwards(feet, 4, 0, 4).map { it.blockPos }.filter { it !in triedSpots }
        // A rebreak there would break the container right away.
        val rebreakPos = RebreakHandler.reBreak?.context?.blockPos
        val free = spots.filter { !ManagerUtils.isPosBlocked(it) && it != rebreakPos }
        val allowed = free.filter { positionFilter(it) }
        val results = runSafeAutomated { allowed.flatMap { it.toStructure(TargetState.Stack(stack)).sim() } }

        // Holding something else, the sim stops at selecting the container, so the side it'll face is assumed.
        val openable = results.filter {
            it is InteractResult.Interact && canBeOpened(stack, it.pos, it.context.hitResult.side) ||
                it is GenericResult.WrongItemSelection && canBeOpened(stack, it.pos, Direction.UP)
        }
        lastScan = "${spots.size} spots around ${feet.toShortString()}: ${spots.size - free.size} being broken, " +
            "${free.size - allowed.size} not allowed, ${allowed.size - openable.distinctBy { it.pos }.size} can't be placed or opened"
        return openable.minByOrNull { it.pos distSq player.pos }?.pos
    }

    private fun SafeContext.canBeOpened(
        itemStack: ItemStack,
        blockPos: BlockPos,
        direction: Direction,
    ) = when (itemStack.item) {
        Items.ENDER_CHEST -> {
            !ChestBlock.isChestBlocked(world, blockPos)
        }
        in shulkerBoxes -> {
            // The space its lid opens into, as ShulkerBoxBlock checks it. The box is already at the block.
            val box = ShulkerEntity
                .calculateBoundingBox(1.0f, direction, 0.0f, 0.5f, blockPos.toBottomCenterPos())
                .contract(1.0E-6)

            world.isSpaceEmpty(box)
        }
        else -> false
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
        private const val SCAN_INTERVAL_TICKS = 20
        private const val PLACE_TIMEOUT_TICKS = 100
        private const val LEFT_INVENTORY_TIMEOUT_TICKS = 300
        private const val QUIET_TICKS = 10
        private const val MAX_QUIET_WAIT_TICKS = 100

        /**
         * On full solid ground, so it can't be air placed or sit on a fence or carpet, and with nothing next to it
         * its drop could fall into or burn in. Any block with a collision box next to it stops the item, also e.g. glass, slabs or a wall over lava.
         */
        fun SafeContext.isSafeContainerSpot(pos: BlockPos): Boolean {
            fun hazardous(at: BlockPos) = blockState(at).let { it.hasFluid || it.isIn(BlockTags.FIRE) }

            return hasSolidGround(pos) && EightWayDirection.entries.all { direction ->
                val side = pos.offset(direction, 1)
                when {
                    !isPassable(side) -> true
                    hazardous(side) -> false
                    else -> !hazardous(side.down()) && !isPassable(side.down())
                }
            }
        }
    }
}
