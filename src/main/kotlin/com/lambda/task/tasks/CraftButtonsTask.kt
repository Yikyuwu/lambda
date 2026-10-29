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

import com.lambda.Lambda.LOG
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
    /** Set when crafting failed once, then it only takes what's in the grid back out. */
    private var clearing = false

    /** Also the offhand, like the module that asked for it counts. */
    private fun SafeContext.buttons() = carriedCount(isButton) + player.offHandStack.let { if (isButton(it)) it.count else 0 }
    private fun SafeContext.missing() = target - buttons()

    override fun SafeContext.resume() {
        if (stopping) return if (placedPos != null) collect() else finish()
        val pos = placedPos ?: return place()
        if (crafted) open(pos, Phase.Returning, extraTicks = TICKS_PER_STACK * player.hotbarAndInventorySlots.size)
        else open(pos, Phase.Taking, extraTicks = TICKS_PER_STACK * logsNeeded().stacks)
    }

    override fun SafeContext.onPlaced() = resume()

    override fun SafeContext.tickWork() {
        when (phase) {
            Phase.Taking -> containerStep(onDone = { startCrafting() }) { take(it) }
            Phase.Crafting -> craft()
            Phase.Returning -> containerStep(onDone = { collect() }) { putBack(it) }
            else -> {}
        }
    }

    private fun SafeContext.place() {
        buttonsAtStart = buttons()
        val needed = logsNeeded()
        val shulkers = player.hotbarAndInventorySlots.filter { logsIn(it.stack) > 0 }
        // The one with the fewest that still has enough, so they're used up one after another.
        val shulker = shulkers.filter { logsIn(it.stack) >= needed }.minByOrNull { logsIn(it.stack) }
            ?: shulkers.maxByOrNull { logsIn(it.stack) }
            ?: run {
                warn("No shulker with logs left")
                return finish()
            }
        info("Crafting ${missing()} buttons from ${shulker.stack.name.string}")
        LOG.info("[Restocking buttons] ${buttons()} carried, target $target, $needed logs needed, ${logsIn(shulker.stack)} in ${shulker.stack.name.string}")
        placeShulker(shulker)
    }

    /** Logs still to take: a log makes 4 planks, a plank one button, and carried planks and logs count. */
    private fun SafeContext.logsNeeded(): Int {
        val planksNeeded = missing() - carriedCount(::isPlank)
        return (ceilDiv(planksNeeded, PLANKS_PER_LOG) - carriedCount(::isLog)).coerceAtLeast(0)
    }

    /** Takes whole stacks of logs, throwing away junk to make room. */
    private fun SafeContext.take(handler: ScreenHandler): Boolean {
        if (logsNeeded() <= 0) return false
        val logs = handler.containerSlots.filter { isLog(it.stack) }
        // The smallest that's enough, so fewer leftovers go back.
        val from = logs.filter { it.stack.count >= logsNeeded() }.minByOrNull { it.stack.count }
            ?: logs.maxByOrNull { it.stack.count }
            ?: return false
        if (handler.playerSlots.none { it.stack.isEmpty }) {
            val junk = handler.playerSlots.firstOrNull { it.stack.item in inventoryConfig.disposables } ?: run {
                warn("Inventory is full and has nothing to throw away, can't take logs")
                return false
            }
            submit { throwStack(junk.id) }
            return true
        }
        LOG.info("[Restocking buttons] taking ${from.stack.count}x ${from.stack.name.string}")
        submit { quickMove(from.id) }
        return true
    }

    private fun startCrafting() {
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

        if (!grid.stack.isEmpty && !clearing && grid.stack.count < fill) return topUp(grid)

        if (!grid.stack.isEmpty) {
            val result = output.stack
            if (result.isEmpty && !clearing && ++outputWait < OUTPUT_WAIT_TICKS) return
            if (clearing || result.isEmpty || !(isPlank(result) || isButton(result))) {
                // Nothing to craft, e.g. something else was in it, so it goes back into the inventory.
                if (!hasRoomFor(grid.stack)) return makeRoomOrStop(grid.stack)
                LOG.info("[Restocking buttons] taking ${grid.stack.count}x ${grid.stack.name.string} back out of the grid")
                act { quickMove(GRID_SLOT) }
                return
            }
            if (!hasRoomFor(result)) return makeRoomOrStop(result)
            LOG.info("[Restocking buttons] crafting ${result.name.string} from ${grid.stack.count}x ${grid.stack.name.string}")
            act { quickMove(OUTPUT_SLOT) }
            fill = 0
            return
        }

        val missing = missing()
        if (clearing || missing <= 0) return doneCrafting()
        val planks = smallest(::isPlank)
        val logs = smallest(::isLog)
        when {
            planks != null -> putInGrid(planks, missing.coerceAtMost(planks.stack.maxCount))
            logs != null -> putInGrid(logs, ceilDiv(missing, PLANKS_PER_LOG).coerceAtMost(logs.stack.maxCount))
            else -> {
                warn("Out of logs with $missing buttons still missing")
                doneCrafting()
            }
        }
    }

    /** Puts [amount] of [from] into the empty grid: the whole stack in one go, else one at a time. */
    private fun SafeContext.putInGrid(from: Slot, amount: Int) {
        fill = amount
        LOG.info("[Restocking buttons] putting $amount ${from.stack.name.string} in the grid")
        if (amount >= from.stack.count) act {
            pickup(from.id)
            pickup(GRID_SLOT)
        } else placeSingles(from, amount)
    }

    /** Adds the rest of [fill] from carried stacks of the same item. With none left, crafts what's there. */
    private fun SafeContext.topUp(grid: Slot) {
        val wanted = fill - grid.stack.count
        val from = player.hotbarAndInventorySlots
            .filter { ItemStack.areItemsAndComponentsEqual(it.stack, grid.stack) }
            .minByOrNull { it.stack.count }
            ?: run {
                fill = grid.stack.count
                return
            }
        if (wanted >= from.stack.count) act {
            pickup(from.id)
            pickup(GRID_SLOT)
        } else placeSingles(from, wanted)
    }

    /** Picks up [from], right-clicks some of it into the grid one by one and puts the rest back, so the cursor ends empty. */
    private fun SafeContext.placeSingles(from: Slot, amount: Int) {
        val now = amount.coerceAtMost(SINGLES_PER_REQUEST)
        act {
            pickup(from.id)
            repeat(now) { pickup(GRID_SLOT, 1) }
            pickup(from.id)
        }
    }

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
        val junk = player.hotbarAndInventorySlots.firstOrNull { it.stack.item in inventoryConfig.disposables }
            ?: return craftFailed("No room for ${stack.count}x ${stack.name.string} and nothing to throw away")
        LOG.info("[Restocking buttons] throwing ${junk.stack.count}x ${junk.stack.name.string} to make room")
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
        LOG.info("[Restocking buttons] crafted, ${buttons()} buttons carried, target $target")
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
        LOG.info("[Restocking buttons] putting back ${leftover.stack.count}x ${leftover.stack.name.string}")
        submit { quickMove(leftover.id) }
        return true
    }

    override fun SafeContext.workFailed(reason: String?) {
        when (phase) {
            Phase.Opening -> if (++openAttempts < MAX_OPEN_ATTEMPTS) resume() else {
                warn("Couldn't open the shulker ($reason), picking it back up")
                collect()
            }
            Phase.Taking -> {
                warn("Failed while taking logs ($reason), crafting with what's carried")
                startCrafting()
            }
            Phase.Crafting -> if (!clearing) {
                warn("Failed while crafting buttons ($reason), taking what's in the grid back out")
                clearing = true
                enter(Phase.Crafting)
            } else {
                crafted = true
                resume()
            }
            else -> {
                warn("Failed while ${phase.description} ($reason)")
                collect()
            }
        }
    }

    override fun SafeContext.finish() {
        val made = buttons() - buttonsAtStart
        if (made > 0) info("Crafted $made buttons, ${buttons()} carried")
        LOG.info("[Restocking buttons] done, $made crafted, ${buttons()} carried")
        success()
    }

    private fun SafeContext.smallest(predicate: (ItemStack) -> Boolean) =
        player.hotbarAndInventorySlots.filter { predicate(it.stack) }.minByOrNull { it.stack.count }

    private val Int.stacks get() = ceilDiv(this, MAX_STACK)

    companion object {
        /** The player's crafting output and its first grid slot, where everything is crafted. */
        private const val OUTPUT_SLOT = 0
        private const val GRID_SLOT = 1
        const val PLANKS_PER_LOG = 4
        private const val MAX_STACK = 64
        private const val TICKS_PER_STACK = 10
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

        private fun ceilDiv(a: Int, b: Int) = if (a <= 0) 0 else (a + b - 1) / b
    }
}
