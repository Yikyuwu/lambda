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
 * Exact amounts are made with a few half-stack right-clicks rather than one click per item, see [planFill].
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
    /** How many of [fillItem] the grid is being filled to before it's crafted, 0 while it's not being filled. */
    private var fill = 0
    private var fillItem: ItemStack = ItemStack.EMPTY
    /** The inventory slot an exact amount is being split off from over several requests, -1 if none. */
    private var fillFrom = -1
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

        // Never crafted while filling, the grid may hold more than wanted halfway through.
        if (fill > 0) {
            if (grid.stack.count != fill) return fillGrid(grid)
            fill = 0
            fillFrom = -1
        }

        if (!grid.stack.isEmpty) {
            val result = output.stack
            if (result.isEmpty && ++outputWait < OUTPUT_WAIT_TICKS) return
            // Nothing to craft, e.g. something else was in it, so it goes back into the inventory.
            if (result.isEmpty || !(isPlank(result) || isButton(result))) return takeOut(grid)
            // A shift-click crafts all that's in the grid, so room is made for all of it while there's junk to throw.
            if (!hasRoomFor(result, result.count * grid.stack.count)) junkSlot()?.let { return throwJunk(it) }
            if (!hasRoomFor(result)) return craftFailed("No room for ${result.name.string} and nothing to throw away")
            act("crafting ${result.name.string} from ${grid.stack.count}x ${grid.stack.name.string}") { quickMove(OUTPUT_SLOT) }
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

    /** Starts filling the empty grid with [amount] of what's in [from]. */
    private fun SafeContext.putInGrid(from: Slot, amount: Int) {
        fill = amount
        fillItem = from.stack.copyWithCount(1)
        fillFrom = -1
        log("putting $amount ${from.stack.name.string} in the grid")
        fillGrid(player.playerScreenHandler.getSlot(GRID_SLOT))
    }

    /**
     * One request towards exactly [fill] in the grid: whole stacks that fit go in with two clicks, then the rest is split
     * off a bigger stack with the fewest requests [planFill] finds. With nothing left to add, crafts what's there.
     */
    private fun SafeContext.fillGrid(grid: Slot) {
        val have = grid.stack.count
        if (!grid.stack.isEmpty && !sameItem(grid.stack, fillItem)) return craftFailed("${grid.stack.name.string} is in the grid")
        val ownSlot = fillFrom.takeIf { it >= 0 }?.let(player.playerScreenHandler::getSlot)
            ?.takeIf { it.stack.isEmpty || sameItem(it.stack, fillItem) }
        val carried = player.hotbarAndInventorySlots.filter { sameItem(it.stack, fillItem) }
        if (ownSlot == null && have < fill) {
            fillFrom = -1
            carried.filter { it.stack.count <= fill - have }.maxByOrNull { it.stack.count }?.let { from ->
                return act {
                    pickup(from.id)
                    pickup(GRID_SLOT)
                }
            }
            if (carried.isEmpty()) {
                fill = have
                return
            }
        }
        // Too many in the grid only happens halfway through a split, unless that slot was filled with something else.
        val sources = ownSlot?.let { listOf(it) }
            ?: if (have < fill) carried else carried.filter { it.stack.count < it.stack.maxCount } + player.hotbarAndInventorySlots.filter { it.stack.isEmpty }
        val clicksPerRequest = buildConfig.inventoryLimit.coerceIn(2, MAX_CLICKS_PER_REQUEST)
        val (from, plan) = sources
            .mapNotNull { slot -> planFill(slot.stack.count, have, fill, fillItem.maxCount, clicksPerRequest)?.let { slot to it } }
            .minByOrNull { (_, plan) -> plan.requests * MAX_CLICKS_PER_REQUEST * 2 + plan.clicks }
            ?: run {
                // E.g. something took the split's slot, the carried stacks are looked at again.
                if (ownSlot != null) fillFrom = -1
                else craftFailed("Can't put exactly $fill ${fillItem.name.string} in the grid")
                return
            }
        fillFrom = from.id
        act {
            plan.first.forEach { pickup(if (it.grid) GRID_SLOT else from.id, if (it.right) 1 else 0) }
        }
    }

    /** Takes what's in a grid slot back into the inventory, making room first. */
    private fun SafeContext.takeOut(slot: Slot) {
        if (!hasRoomFor(slot.stack)) return makeRoomOrStop(slot.stack)
        act("taking ${slot.stack.count}x ${slot.stack.name.string} back out of the grid") { quickMove(slot.id) }
    }

    private fun SafeContext.filledGridSlot() = GRID_SLOTS.map(player.playerScreenHandler::getSlot).firstOrNull { !it.stack.isEmpty }

    private fun SafeContext.inGrid() = GRID_SLOTS.sumOf { player.playerScreenHandler.getSlot(it).stack.count }

    /** Whether [count] of [stack] fit into the inventory, where a shift-click puts them. */
    private fun SafeContext.hasRoomFor(stack: ItemStack, count: Int = stack.count) =
        player.hotbarAndInventorySlots.sumOf {
            when {
                it.stack.isEmpty -> stack.maxCount
                canMerge(it.stack, stack) -> it.stack.maxCount - it.stack.count
                else -> 0
            }
        } >= count

    /** Throws away a junk stack, else stops crafting and puts the logs back, since nothing else may go. */
    private fun SafeContext.makeRoomOrStop(stack: ItemStack) {
        val junk = junkSlot() ?: return craftFailed("No room for ${stack.count}x ${stack.name.string} and nothing to throw away")
        throwJunk(junk)
    }

    private fun throwJunk(junk: Slot) = act("throwing ${junk.stack.count}x ${junk.stack.name.string} to make room") { throwStack(junk.id) }

    /**
     * Submits [request], logging [message] once it's sent. A request the packet limit holds back is tried again next
     * tick, nothing of it was sent.
     */
    private fun act(message: String? = null, request: InvRequestBuilder.() -> Unit) {
        if (!submit(request)) return
        message?.let(::log)
        outputWait = 0
        progressed()
    }

    /** Takes what's in the grid back out once, then goes on putting the leftovers back. */
    private fun SafeContext.craftFailed(reason: String) {
        fill = 0
        fillFrom = -1
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
        /** Clicks in one request, at most the inventory packet limit, which is five by default. */
        private const val MAX_CLICKS_PER_REQUEST = 5
        /** The server shows the output right after the grid changes, this only ends a wait for a recipe that isn't one. */
        private const val OUTPUT_WAIT_TICKS = 100

        fun isLog(stack: ItemStack) = stack.isIn(ItemTags.LOGS)
        fun isPlank(stack: ItemStack) = stack.isIn(ItemTags.PLANKS)

        /** How many logs the shulker [stack] holds. */
        fun logsIn(stack: ItemStack) =
            if (stack.item in ItemUtils.shulkerBoxes) stack.shulkerBoxContents.sumOf { if (isLog(it)) it.count else 0 } else 0

        private fun canMerge(stack: ItemStack, other: ItemStack) =
            ItemStack.areItemsAndComponentsEqual(stack, other) && stack.count < stack.maxCount

        private fun sameItem(stack: ItemStack, other: ItemStack) = ItemStack.areItemsAndComponentsEqual(stack, other)

        /** A click in a [FillPlan]: left or right, on the source slot or the grid. */
        data class FillClick(val grid: Boolean, val right: Boolean)

        /** How to get the grid to an exact amount: [requests] requests of [clicks] clicks in all, [first] the next one. */
        class FillPlan(val requests: Int, val clicks: Int, val first: List<FillClick>)

        private val FILL_CLICKS = listOf(FillClick(false, false), FillClick(false, true), FillClick(true, false), FillClick(true, true))
        private val fillPlans = HashMap<List<Int>, FillPlan?>()

        /**
         * The fewest requests, then clicks, moving items between a source slot holding [source] and the grid holding [grid]
         * until the grid holds exactly [want], each request at most [maxClicks] clicks and ending with an empty cursor.
         * A right-click on a slot picks up its bigger half and one with items on the cursor puts one down, so e.g. 27 of
         * 64 take two requests rather than one click per item. The grid may hold more than [want] in between. Null if
         * it can't be done, e.g. fewer than [want] in both.
         */
        fun planFill(source: Int, grid: Int, want: Int, max: Int, maxClicks: Int): FillPlan? =
            fillPlans.getOrPut(listOf(source, grid, want, max, maxClicks)) { searchFill(source, grid, want, max, maxClicks) }

        /** Dijkstra over what's in the grid, the source holds the rest, see [planFill]. */
        private fun searchFill(source: Int, grid: Int, want: Int, max: Int, maxClicks: Int): FillPlan? {
            val total = source + grid
            if (want > total || want > max || source > max || grid > max) return null
            val cost = IntArray(max + 1) { Int.MAX_VALUE }
            val firstOf = arrayOfNulls<List<FillClick>>(max + 1)
            val requestsOf = IntArray(max + 1)
            val clicksOf = IntArray(max + 1)
            val done = BooleanArray(max + 1)
            cost[grid] = 0
            firstOf[grid] = emptyList()
            while (true) {
                val g = (0..max).filter { !done[it] && cost[it] != Int.MAX_VALUE }.minByOrNull { cost[it] } ?: return null
                if (g == want) return FillPlan(requestsOf[g], clicksOf[g], firstOf[g]!!)
                done[g] = true
                requestsFrom(total - g, g, max, maxClicks).forEach { (next, clicks) ->
                    val requests = requestsOf[g] + 1
                    val clicksSoFar = clicksOf[g] + clicks.size
                    // Fewer requests first, each waits the move delay, then fewer clicks.
                    val nextCost = requests * (maxClicks + 1) * 2 + clicksSoFar
                    if (done[next] || nextCost >= cost[next]) return@forEach
                    cost[next] = nextCost
                    requestsOf[next] = requests
                    clicksOf[next] = clicksSoFar
                    firstOf[next] = firstOf[g]!!.ifEmpty { clicks }
                }
            }
        }

        /** What the grid can hold after one request from [source] in the source slot and [grid] in the grid, the shortest clicks to each. */
        private fun requestsFrom(source: Int, grid: Int, max: Int, maxClicks: Int): Map<Int, List<FillClick>> {
            val best = HashMap<Int, List<FillClick>>()
            val path = ArrayList<FillClick>()
            fun search(source: Int, grid: Int, cursor: Int) {
                if (path.isNotEmpty() && cursor == 0) {
                    if (best[grid].let { it == null || it.size > path.size }) best[grid] = path.toList()
                    return
                }
                if (path.size == maxClicks) return
                for (click in FILL_CLICKS) {
                    val slot = if (click.grid) grid else source
                    // As vanilla: an empty cursor picks up all or the bigger half, else all or one is put down, as fits.
                    val moved = when {
                        cursor == 0 -> if (click.right) (slot + 1) / 2 else slot
                        else -> -minOf(if (click.right) 1 else cursor, max - slot)
                    }
                    if (moved == 0) continue
                    path += click
                    if (click.grid) search(source, grid - moved, cursor + moved) else search(source - moved, grid, cursor + moved)
                    path.removeAt(path.lastIndex)
                }
            }
            search(source, grid, 0)
            return best
        }
    }
}
