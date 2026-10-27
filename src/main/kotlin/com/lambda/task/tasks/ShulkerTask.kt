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

import baritone.api.pathing.goals.GoalBlock
import com.lambda.context.Automated
import com.lambda.context.SafeContext
import com.lambda.interaction.handlers.BaritoneHandler
import com.lambda.task.tasks.BuildTask.Companion.breakAndCollectBlock
import com.lambda.task.tasks.PlaceContainerTask.Companion.isSafeContainerSpot
import com.lambda.util.BlockUtils.blockState
import com.lambda.util.CommunicationUtils.warn
import com.lambda.util.item.ItemUtils
import com.lambda.util.world.closestEntity
import net.minecraft.block.ShulkerBoxBlock
import net.minecraft.entity.ItemEntity
import net.minecraft.item.ItemStack
import net.minecraft.screen.slot.Slot
import net.minecraft.util.math.BlockPos

/**
 * A [StationTask] that places a carried shulker, works in it, then breaks it and picks it back up.
 * Shulkers only go where the dropped item can't fall into a hole, fluid or fire, and a placed one is never
 * left behind, so it's only broken once there's a free slot for it.
 */
abstract class ShulkerTask<P : StationPhase>(
    title: String,
    private val opening: P,
    automated: Automated,
    holdWhile: () -> Boolean,
    private val placing: P,
    private val makingRoom: P,
    private val breaking: P,
    private val pickingUp: P,
) : StationTask<P>(title, opening, automated, holdWhile) {
    protected var placedPos: BlockPos? = null
        private set
    private var shulkersNearby = emptySet<BlockPos>()
    private var shulkersCarried = 0
    protected var openAttempts = 0
    private var collectAttempts = 0

    private val collectPhases = setOf(makingRoom, breaking, pickingUp)

    /** Opens the shulker once it's placed. */
    protected abstract fun SafeContext.onPlaced()

    /** Runs every tick while no step is running and the shulker isn't being picked back up. */
    protected abstract fun SafeContext.tickWork()

    /** A phase of the work in the shulker failed. Placing and picking it back up recover by themselves. */
    protected abstract fun SafeContext.workFailed(reason: String?)

    /** Ends the task, e.g. saying what it did. */
    protected abstract fun SafeContext.finish()

    /** Goes on once the shulker is back in the inventory, or none was placed. */
    protected open fun SafeContext.shulkerBack() = finish()

    protected fun isShulker(stack: ItemStack) = stack.item in ItemUtils.shulkerBoxes

    final override fun SafeContext.onTick() {
        when (phase) {
            makingRoom -> makeRoom()
            pickingUp -> pickUp()
            placing, breaking -> {}
            else -> tickWork()
        }
    }

    override fun SafeContext.packUp() = when {
        phase == placing || phase in collectPhases -> true
        placedPos == null -> false
        else -> {
            cancelStep()
            collect()
            true
        }
    }

    protected fun SafeContext.placeShulker(shulker: Slot) {
        openAttempts = 0
        collectAttempts = 0
        shulkersCarried = inInventory(::isShulker)
        shulkersNearby = shulkersAround()
        enter(placing)
        runStep(PlaceContainerTask(shulker, this@ShulkerTask) { isSafeContainerSpot(it) }) { pos ->
            placedPos = pos
            onPlaced()
        }
    }

    /** Closes the shulker and starts picking it back up. */
    protected fun SafeContext.collect() {
        closeContainer()
        if (placedPos == null) return shulkerBack()
        enter(makingRoom)
    }

    /** Breaking the shulker with a full inventory would leave it on the ground, so free a slot first. */
    private fun SafeContext.makeRoom() {
        if (!settled || placedPos?.let { canBreakSafely(it) } == false) return
        if (hasFreeSlotOrThrow { "Left the shulker placed at ${placedPos?.toShortString()}" }) breakShulker()
    }

    private fun SafeContext.breakShulker() {
        val pos = placedPos ?: return shulkerBack()
        if (blockState(pos).block !is ShulkerBoxBlock) return enter(pickingUp)
        enter(breaking)
        runStep(breakAndCollectBlock(pos)) { enter(pickingUp) }
    }

    /** Waits for the shulker to be back in the inventory and walks to it if it is still on the ground. */
    private fun SafeContext.pickUp() {
        val pos = placedPos ?: return shulkerBack()
        if (inInventory(::isShulker) >= shulkersCarried) {
            BaritoneHandler.cancel()
            placedPos = null
            return shulkerBack()
        }
        if (blockState(pos).block is ShulkerBoxBlock) return phaseFailed("the shulker is still placed")
        if (!settled) return

        val drop = closestEntity<ItemEntity>(PICKUP_RADIUS, pos) { isShulker(it.stack) } ?: return
        if (!hasFreeSlotOrThrow { "The shulker is on the ground at ${drop.blockPos.toShortString()}" }) return
        if (!BaritoneHandler.isActive) BaritoneHandler.setGoalAndPath(GoalBlock(drop.blockPos))
    }

    /** Each phase recovers its own way, since a placed shulker must never be left behind. */
    final override fun SafeContext.stepFailed(reason: String?) {
        cancelStep()
        // A screen whose slots never loaded is still up, the retry would click the block with it open.
        if (phase == opening) closeContainer()

        when (phase) {
            placing -> {
                // It may have failed after the shulker was down. Only a new one counts, and only if one left the inventory.
                val shulkerLeft = inInventory(::isShulker) < shulkersCarried
                placedPos = if (shulkerLeft) (shulkersAround() - shulkersNearby).minByOrNull { it.getSquaredDistance(player.pos) } else null
                when {
                    placedPos != null -> onPlaced()
                    shulkerLeft -> failure("A shulker left the inventory but isn't placed anywhere nearby ($reason)")
                    else -> {
                        warn("Couldn't place the shulker ($reason). It needs a free, safe spot on the ground at your feet level within 4 blocks")
                        finish()
                    }
                }
            }
            in collectPhases ->
                if (++collectAttempts < MAX_COLLECT_ATTEMPTS) collect()
                else failure("Couldn't pick the shulker at ${placedPos?.toShortString()} back up (${phase.description}, $reason)")
            else -> workFailed(reason)
        }
    }

    companion object {
        const val MAX_OPEN_ATTEMPTS = 2
        private const val MAX_COLLECT_ATTEMPTS = 3
        private const val PICKUP_RADIUS = 8.0
    }
}
