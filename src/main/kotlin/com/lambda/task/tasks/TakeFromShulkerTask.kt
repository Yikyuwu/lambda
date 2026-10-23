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
import com.lambda.util.CommunicationUtils.info
import com.lambda.util.CommunicationUtils.warn
import com.lambda.util.extension.containerSlots
import com.lambda.util.extension.playerSlots
import com.lambda.util.item.ItemStackUtils.shulkerBoxContents
import com.lambda.util.player.SlotUtils.hotbarAndInventorySlots
import net.minecraft.item.ItemStack
import net.minecraft.screen.ScreenHandler

/**
 * Takes items like food out of a carried shulker until [target] of them are carried: place it, open it, take,
 * break it and pick it back up. The shulker with the fewest that still has enough goes first, so they're used up one
 * after another, else the fullest.
 */
class TakeFromShulkerTask @Ta5kBuilder constructor(
    private val what: String,
    private val isWanted: (ItemStack) -> Boolean,
    private val target: Int,
    private val swapDelay: Int,
    automated: Automated,
    holdWhile: () -> Boolean = { false },
) : ShulkerTask<TakeFromShulkerTask.Phase>(
    "Taking $what from a shulker", Phase.Opening, automated, holdWhile,
    Phase.Placing, Phase.MakingRoom, Phase.Breaking, Phase.PickingUp,
) {
    override val moveDelay get() = swapDelay

    enum class Phase(override val description: String, override val timeoutTicks: Int) : StationPhase {
        Placing("placing shulker", 400),
        Opening("opening shulker", 200),
        Taking("taking items", 200),
        MakingRoom("making room for the shulker", 100),
        Breaking("breaking shulker", 300),
        PickingUp("picking up shulker", 300),
    }

    private var carriedAtStart = 0

    /** Also the offhand, which is where food is often kept, like the module that asked for it counts. */
    private fun SafeContext.carried() = carriedCount(isWanted) + player.offHandStack.let { if (isWanted(it)) it.count else 0 }
    private fun inShulker(stack: ItemStack) =
        if (isShulker(stack)) stack.shulkerBoxContents.sumOf { if (isWanted(it)) it.count else 0 } else 0

    override fun SafeContext.resume() {
        if (stopping) return if (placedPos != null) collect() else finish()
        val pos = placedPos ?: return place()
        open(pos, Phase.Taking, extraTicks = TICKS_PER_STACK * player.hotbarAndInventorySlots.size)
    }

    override fun SafeContext.onPlaced() = resume()

    override fun SafeContext.tickWork() {
        if (phase == Phase.Taking) containerStep(onDone = { collect() }) { take(it) }
    }

    private fun SafeContext.place() {
        carriedAtStart = carried()
        val needed = target - carriedAtStart
        val shulkers = player.hotbarAndInventorySlots.filter { inShulker(it.stack) > 0 }
        val shulker = shulkers.filter { inShulker(it.stack) >= needed }.minByOrNull { inShulker(it.stack) }
            ?: shulkers.maxByOrNull { inShulker(it.stack) }
            ?: run {
                warn("No shulker with $what left")
                return finish()
            }

        info("Taking $what from ${shulker.stack.name.string}")
        placeShulker(shulker)
    }

    /**
     * Fills up a carried stack with just what's missing and puts the rest back, so e.g. 63 carrots take one.
     * Without one to fill up it takes the biggest stack, throwing away junk to make room for it.
     */
    private fun SafeContext.take(handler: ScreenHandler): Boolean {
        if (carried() >= target) return false
        val stacks = handler.containerSlots.filter { isWanted(it.stack) }
        handler.playerSlots.firstNotNullOfOrNull { carried ->
            if (!isWanted(carried.stack) || carried.stack.count >= carried.stack.maxCount) return@firstNotNullOfOrNull null
            stacks.filter { ItemStack.areItemsAndComponentsEqual(it.stack, carried.stack) }.maxByOrNull { it.stack.count }?.let { carried to it }
        }?.let { (carried, from) ->
            // Three clicks stay under the default limit of five inventory packets.
            submit {
                pickup(from.id)
                pickup(carried.id)
                pickup(from.id)
            }
            return true
        }
        val stack = stacks.maxByOrNull { it.stack.count } ?: return false
        // A digger's inventory is usually full, and a quick move into it would silently do nothing.
        if (handler.playerSlots.none { it.stack.isEmpty }) {
            val junk = handler.playerSlots.firstOrNull { it.stack.item in inventoryConfig.disposables && !isWanted(it.stack) }
            if (junk == null) {
                warn("Inventory is full and has nothing to throw away, can't take $what")
                return false
            }
            submit { throwStack(junk.id) }
            return true
        }
        submit { quickMove(stack.id) }
        return true
    }

    override fun SafeContext.workFailed(reason: String?) {
        when (phase) {
            Phase.Opening -> if (++openAttempts < MAX_OPEN_ATTEMPTS) resume() else {
                warn("Couldn't open the shulker ($reason), picking it back up")
                collect()
            }
            else -> {
                warn("Failed while taking $what ($reason)")
                collect()
            }
        }
    }

    override fun SafeContext.finish() {
        val took = carried() - carriedAtStart
        if (took > 0) info("Took $took $what")
        success()
    }

    private companion object {
        const val TICKS_PER_STACK = 10
    }
}
