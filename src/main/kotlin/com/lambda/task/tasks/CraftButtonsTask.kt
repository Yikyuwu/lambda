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
import com.lambda.interaction.managers.inventory.InventoryRequest.InvRequestBuilder
import com.lambda.util.CommunicationUtils.info
import com.lambda.util.CommunicationUtils.warn
import com.lambda.util.extension.containerSlots
import com.lambda.util.extension.playerSlots
import com.lambda.util.item.ItemStackUtils.shulkerBoxContents
import com.lambda.util.item.ItemUtils
import com.lambda.util.player.SlotUtils.hotbarAndInventorySlots
import net.minecraft.item.ItemStack
import net.minecraft.registry.tag.ItemTags
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.slot.Slot

/**
 * Crafts wooden buttons from a carried shulker of logs until [target] buttons are carried: place it, take the logs
 * needed, turn them into planks and the planks into buttons in the inventory's 2x2 grid, put the leftover logs and
 * planks back, then break the shulker and pick it back up. A log makes 4 planks and a plank one button, so at most 3
 * more than [target] are made. Only exact amounts go into the grid, so a shift-click on the output crafts no more.
 */
class CraftButtonsTask @Ta5kBuilder constructor(
    private val isButton: (ItemStack) -> Boolean,
    private val target: Int,
    private val swapDelay: Int,
    automated: Automated,
    holdWhile: () -> Boolean = { false },
) : ShulkerTask<CraftButtonsTask.Phase>(
    "Restocking buttons", Phase.Opening, automated, holdWhile,
    Phase.Placing, Phase.MakingRoom, Phase.Breaking, Phase.PickingUp,
) {
    override val moveDelay get() = swapDelay

    enum class Phase(override val description: String, override val timeoutTicks: Int) : StationPhase {
        Placing("placing the log shulker", 400),
        Opening("opening the log shulker", 200),
        Taking("taking logs", 200),
        // Each click counts as progress, so this is the time without one.
        Crafting("crafting buttons", 200),
        Returning("putting the leftover logs back", 200),
        MakingRoom("making room for the shulker", 100),
        Breaking("breaking the shulker", 300),
        PickingUp("picking up the shulker", 300),
    }

    private var buttonsAtStart = 0
    private var crafted = false
    /** How many of the item in the grid go in before it's crafted, 0 until something is put in. */
    private var fill = 0
    /**
     * Ticks waited with something in the grid but no output: the server shows what it crafts, and a shift-click shows
     * one craft until the server says it took all, which comes with the inventory it crafted into.
     */
    private var outputWait = 0
    /** Set when crafting failed once or the task is stopped, then it only takes what's in the grid back out. */
    private var clearing = false
    /** Set once the grid is emptied again after the leftovers went back, which may have made room for it. */
    private var gridRetried = false

    private fun SafeContext.buttons() = carriedCount(isButton)
    private fun SafeContext.missing() = target - buttons()

    override fun SafeContext.resume() {
        if (endIfStopping()) return
        val pos = placedPos ?: return place()
        if (crafted) open(pos, Phase.Returning, extraTicks = TICKS_PER_STACK * player.hotbarAndInventorySlots.size)
        else open(pos, Phase.Taking, extraTicks = TICKS_PER_STACK * logsNeeded().stacks)
    }

    override fun SafeContext.onPlaced() = resume()

    /** Mid-craft the grid is emptied first, then the shulker picked up, so nothing is left in it. */
    override fun SafeContext.packUp(): Boolean {
        if (phase != Phase.Crafting) return packUpShulker()
        clearing = true
        return true
    }

    override fun SafeContext.tickWork() {
        when (phase) {
            Phase.Taking -> containerStep(onDone = { startCrafting() }) { take(it) }
            Phase.Crafting -> craft()
            Phase.Returning -> containerStep(onDone = { returned() }) { putBack(it) }
            else -> {}
        }
    }

    private fun SafeContext.place() {
        buttonsAtStart = buttons()
        val needed = logsNeeded()
        val shulker = pickShulker(needed, ::logsIn) ?: run {
            warn("No shulker with logs left")
            return finish()
        }
        info("Crafting ${missing()} buttons from ${shulker.stack.name.string}")
        log("${buttons()} carried, target $target, $needed logs needed, ${logsIn(shulker.stack)} in ${shulker.stack.name.string}")
        placeShulker(shulker)
    }

    /** Logs to carry for [target]: a log makes 4 planks, a plank one button, and carried planks count. */
    private fun SafeContext.logsWanted() =
        Math.ceilDiv(missing() - carriedCount(::isPlank), PLANKS_PER_LOG).coerceAtLeast(0)

    /** Logs still to take, carried ones count. */
    private fun SafeContext.logsNeeded() = (logsWanted() - carriedCount(::isLog)).coerceAtLeast(0)

    /**
     * Takes about the logs needed, throwing away junk to make room. A stack twice as big as needed is halved into the
     * inventory, and half a carried stack goes back while what's left is still enough, so fewer than needed are left over.
     * Halves since a right-click takes half, picking up a stack and putting back all but a few logs takes a click per log.
     */
    private fun SafeContext.take(handler: ScreenHandler): Boolean {
        val needed = logsWanted() - carriedCount(::isLog)
        if (needed <= 0) return putBackHalf(handler, excess = -needed)
        val logs = handler.containerSlots.filter { isLog(it.stack) }
        // The smallest that's enough, so fewer leftovers go back.
        val from = logs.filter { it.stack.count >= needed }.minByOrNull { it.stack.count }
            ?: logs.maxByOrNull { it.stack.count }
            ?: return false
        throwJunkIfFull(handler, "logs")?.let { return it }
        val empty = handler.playerSlots.firstOrNull { it.stack.isEmpty }
        if (from.stack.count >= 2 * needed && empty != null) {
            log("taking half of ${from.stack.count}x ${from.stack.name.string}, $needed needed")
            submit {
                pickup(from.id, 1)
                pickup(empty.id)
            }
            return true
        }
        log("taking ${from.stack.count}x ${from.stack.name.string}, $needed needed")
        submit { quickMove(from.id) }
        return true
    }

    /** Puts half of a carried stack of logs back while [excess] more than needed are carried. False once none fits. */
    private fun SafeContext.putBackHalf(handler: ScreenHandler, excess: Int): Boolean {
        // A right-click picks up the bigger half.
        val from = handler.playerSlots
            .filter { isLog(it.stack) && Math.ceilDiv(it.stack.count, 2) <= excess }
            .maxByOrNull { it.stack.count }
            ?: return false
        val half = Math.ceilDiv(from.stack.count, 2)
        val to = handler.containerSlots.firstOrNull {
            ItemStack.areItemsAndComponentsEqual(it.stack, from.stack) && it.stack.maxCount - it.stack.count >= half
        } ?: handler.containerSlots.firstOrNull { it.stack.isEmpty } ?: return false
        log("putting back $half of ${from.stack.count}x ${from.stack.name.string}, $excess more than needed")
        submit {
            pickup(from.id, 1)
            pickup(to.id)
        }
        return true
    }

    /** The grid is the inventory's, which can't be reached with the shulker open. */
    private fun SafeContext.startCrafting() {
        closeContainer()
        enter(Phase.Crafting)
        fill = 0
    }

    /**
     * One step of crafting in the inventory's 2x2 grid, which works with no screen open. Exact amounts go into one grid
     * slot, then a shift-click on the output crafts them all. Logs become planks, planks become buttons, until enough.
     */
    private fun SafeContext.craft() {
        if (!settled) return
        val handler = player.playerScreenHandler
        if (player.currentScreenHandler !== handler) return phaseFailed("a screen is open")
        val grid = handler.getSlot(GRID_SLOT)
        val output = handler.getSlot(OUTPUT_SLOT)
        val cursor = handler.cursorStack

        if (!cursor.isEmpty) {
            val to = player.hotbarAndInventorySlots.firstOrNull { it.stack.isEmpty || canMerge(it.stack, cursor) }
                ?: return craftFailed("Nowhere to put ${cursor.name.string} on the cursor")
            act { pickup(to.id) }
            return
        }

        if (clearing) return filledGridSlot()?.let { takeOut(it) } ?: doneCrafting()
        // Anything in the rest of the grid would spoil the recipe.
        GRID_SLOTS.drop(1).map(handler::getSlot).firstOrNull { !it.stack.isEmpty }?.let { return takeOut(it) }

        if (!grid.stack.isEmpty && grid.stack.count < fill) return topUp(grid)

        if (!grid.stack.isEmpty) {
            val result = output.stack
            if (result.isEmpty && ++outputWait < OUTPUT_WAIT_TICKS) return
            // Nothing to craft, e.g. something else was in it, so it goes back into the inventory.
            if (result.isEmpty || !(isPlank(result) || isButton(result))) return takeOut(grid)
            if (!hasRoomFor(result)) return makeRoomOrStop(result)
            log("crafting ${result.name.string} from ${grid.stack.count}x ${grid.stack.name.string}")
            act { quickMove(OUTPUT_SLOT) }
            fill = 0
            return
        }

        val missing = missing()
        if (missing <= 0) return doneCrafting()
        val planks = smallest(::isPlank)
        val logs = smallest(::isLog)
        when {
            planks != null -> putInGrid(planks, missing.coerceAtMost(planks.stack.maxCount))
            logs != null -> putInGrid(logs, Math.ceilDiv(missing, PLANKS_PER_LOG).coerceAtMost(logs.stack.maxCount))
            else -> {
                warn("Out of logs with $missing buttons still missing")
                doneCrafting()
            }
        }
    }

    /** Puts [amount] of [from] into the empty grid. */
    private fun SafeContext.putInGrid(from: Slot, amount: Int) {
        fill = amount
        log("putting $amount ${from.stack.name.string} in the grid")
        moveToGrid(from, amount)
    }

    /** Adds the rest of [fill] from carried stacks of the same item. With none left, crafts what's there. */
    private fun SafeContext.topUp(grid: Slot) {
        val from = player.hotbarAndInventorySlots
            .filter { ItemStack.areItemsAndComponentsEqual(it.stack, grid.stack) }
            .minByOrNull { it.stack.count }
            ?: run {
                fill = grid.stack.count
                return
            }
        moveToGrid(from, fill - grid.stack.count)
    }

    /**
     * Moves [amount] of [from] into the grid: the whole stack in one go, else picked up, right-clicked in one by one and
     * the rest put back, so the cursor ends empty.
     */
    private fun moveToGrid(from: Slot, amount: Int) {
        if (amount >= from.stack.count) return act {
            pickup(from.id)
            pickup(GRID_SLOT)
        }
        act {
            pickup(from.id)
            repeat(amount.coerceAtMost(SINGLES_PER_REQUEST)) { pickup(GRID_SLOT, 1) }
            pickup(from.id)
        }
    }

    /** Takes what's in a grid slot back into the inventory, making room first. */
    private fun SafeContext.takeOut(slot: Slot) {
        if (!hasRoomFor(slot.stack)) return makeRoomOrStop(slot.stack)
        log("taking ${slot.stack.count}x ${slot.stack.name.string} back out of the grid")
        act { quickMove(slot.id) }
    }

    private fun SafeContext.filledGridSlot() = GRID_SLOTS.map(player.playerScreenHandler::getSlot).firstOrNull { !it.stack.isEmpty }

    private fun SafeContext.inGrid() = GRID_SLOTS.sumOf { player.playerScreenHandler.getSlot(it).stack.count }

    /** Whether all of [stack] fits into the inventory, where a shift-click puts it. */
    private fun SafeContext.hasRoomFor(stack: ItemStack) =
        player.hotbarAndInventorySlots.sumOf {
            when {
                it.stack.isEmpty -> stack.maxCount
                canMerge(it.stack, stack) -> it.stack.maxCount - it.stack.count
                else -> 0
            }
        } >= stack.count

    /** Throws away a junk stack, else stops crafting and puts the logs back, since nothing else may go. */
    private fun SafeContext.makeRoomOrStop(stack: ItemStack) {
        val junk = junkSlot() ?: return craftFailed("No room for ${stack.count}x ${stack.name.string} and nothing to throw away")
        log("throwing ${junk.stack.count}x ${junk.stack.name.string} to make room")
        act { throwStack(junk.id) }
    }

    private fun act(request: InvRequestBuilder.() -> Unit) {
        outputWait = 0
        if (submit(request)) progressed()
    }

    /** Takes what's in the grid back out once, then goes on putting the leftovers back. */
    private fun SafeContext.craftFailed(reason: String) {
        warn("$reason, stopped crafting buttons")
        if (clearing) return doneCrafting()
        clearing = true
    }

    private fun SafeContext.doneCrafting() {
        crafted = true
        log("crafted, ${buttons()} buttons carried, target $target, ${inGrid()} left in the grid")
        phaseDone()
        resume()
    }

    /** Puts the logs and planks left over back into the shulker. */
    private fun SafeContext.putBack(handler: ScreenHandler): Boolean {
        val leftover = handler.playerSlots.firstOrNull { isLog(it.stack) || isPlank(it.stack) } ?: return false
        if (handler.containerSlots.none { it.stack.isEmpty || canMerge(it.stack, leftover.stack) }) {
            warn("The shulker is full, keeping ${leftover.stack.count}x ${leftover.stack.name.string}")
            return false
        }
        log("putting back ${leftover.stack.count}x ${leftover.stack.name.string}")
        submit { quickMove(leftover.id) }
        return true
    }

    /** Picks the shulker back up. What's still in the grid, for want of room, is taken out once more first. */
    private fun SafeContext.returned() {
        if (filledGridSlot() == null || gridRetried) return collect()
        gridRetried = true
        clearing = true
        enter(Phase.Crafting)
    }

    override fun SafeContext.workFailed(reason: String?) {
        if (retryOpening(reason)) return
        when (phase) {
            Phase.Taking -> {
                warn("Failed while taking logs ($reason), crafting with what's carried")
                startCrafting()
            }
            Phase.Crafting -> if (!clearing) {
                warn("Failed while crafting buttons ($reason), taking what's in the grid back out")
                clearing = true
                enter(Phase.Crafting)
            } else doneCrafting()
            Phase.Returning -> {
                warn("Failed while ${phase.description} ($reason)")
                returned()
            }
            else -> {
                warn("Failed while ${phase.description} ($reason)")
                collect()
            }
        }
    }

    override fun SafeContext.finish() {
        val made = buttons() - buttonsAtStart
        val left = inGrid()
        if (made > 0) info("Crafted $made buttons, ${buttons()} carried")
        log("done, $made crafted, ${buttons()} carried, $left left in the grid")
        // Not lost, but unusable there, and closing the inventory with no room would drop them.
        if (left > 0 && !stopping) return failure("$left items are left in the crafting grid, the inventory has no room for them and nothing to throw away")
        if (left > 0) warn("$left items are left in the crafting grid, the inventory has no room for them")
        success()
    }

    private fun SafeContext.smallest(predicate: (ItemStack) -> Boolean) =
        player.hotbarAndInventorySlots.filter { predicate(it.stack) }.minByOrNull { it.stack.count }

    private val Int.stacks get() = Math.ceilDiv(this, STACK_SIZE)

    companion object {
        /** The player's crafting output and its first grid slot, where everything is crafted. */
        private const val OUTPUT_SLOT = 0
        private const val GRID_SLOT = 1
        private val GRID_SLOTS = 1..4
        const val PLANKS_PER_LOG = 4
        /** Three right-clicks between picking up and putting back stay under the default limit of five inventory packets. */
        private const val SINGLES_PER_REQUEST = 3
        private const val OUTPUT_WAIT_TICKS = 100

        fun isLog(stack: ItemStack) = stack.isIn(ItemTags.LOGS)
        fun isPlank(stack: ItemStack) = stack.isIn(ItemTags.PLANKS)

        /** How many logs the shulker [stack] holds. */
        fun logsIn(stack: ItemStack) =
            if (stack.item in ItemUtils.shulkerBoxes) stack.shulkerBoxContents.sumOf { if (isLog(it)) it.count else 0 } else 0

        private fun canMerge(stack: ItemStack, other: ItemStack) =
            ItemStack.areItemsAndComponentsEqual(stack, other) && stack.count < stack.maxCount
    }
}
