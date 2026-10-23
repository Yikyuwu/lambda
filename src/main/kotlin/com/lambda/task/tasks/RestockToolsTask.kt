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
import com.lambda.task.tasks.StationTask.Companion.leastNeededHotbarSlot
import com.lambda.util.CommunicationUtils.info
import com.lambda.util.CommunicationUtils.warn
import com.lambda.util.extension.containerSlots
import com.lambda.util.extension.playerSlots
import com.lambda.util.item.ItemStackUtils.durabilityFraction
import com.lambda.util.item.ItemStackUtils.shulkerBoxContents
import com.lambda.util.player.SlotUtils.hotbarAndInventorySlots
import net.minecraft.item.ItemStack
import net.minecraft.screen.ScreenHandler

/**
 * Swaps worn tools in the inventory for fresh ones from carried shulker boxes:
 * place one, open it, swap, break it and pick it back up, one shulker at a time.
 */
class RestockToolsTask @Ta5kBuilder constructor(
    private val isTool: (ItemStack) -> Boolean,
    private val isWorn: (ItemStack) -> Boolean,
    private val maxShulkers: Int,
    private val swapDelay: Int,
    automated: Automated,
    holdWhile: () -> Boolean = { false },
) : ShulkerTask<RestockToolsTask.Phase>(
    "Restocking tools", Phase.Opening, automated, holdWhile,
    Phase.Placing, Phase.MakingRoom, Phase.Breaking, Phase.PickingUp,
) {
    override val moveDelay get() = swapDelay

    enum class Phase(override val description: String, override val timeoutTicks: Int) : StationPhase {
        Placing("placing shulker", 400),
        Opening("opening shulker", 200),
        Swapping("swapping tools", 200),
        MakingRoom("making room for the shulker", 100),
        Breaking("breaking shulker", 300),
        PickingUp("picking up shulker", 300),
    }

    private var shulkersUsed = 0
    private var freshAtStart = -1

    private fun isWornTool(stack: ItemStack) = isTool(stack) && isWorn(stack)
    private fun isFreshTool(stack: ItemStack) = isTool(stack) && !isWorn(stack)

    // A placed one is opened again, e.g. after it was closed for a fight.
    override fun SafeContext.resume() = placedPos?.let { open() } ?: nextShulker()

    override fun SafeContext.onPlaced() = open()

    override fun SafeContext.tickWork() {
        if (phase == Phase.Swapping) containerStep(onDone = { collect() }) { swap(it) }
    }

    override fun SafeContext.shulkerBack() = nextShulker()

    private fun SafeContext.nextShulker() {
        if (stopping) return finish()
        if (freshAtStart < 0) freshAtStart = inInventory(::isFreshTool)
        val worn = inInventory(::isWornTool)
        if (worn == 0) return finish()
        if (shulkersUsed >= maxShulkers) {
            warn("Used $shulkersUsed shulker(s), $worn worn tool(s) left")
            return finish()
        }

        val shulker = nextShulkerSlot() ?: run {
            warn("No shulker with fresh tools left")
            return finish()
        }

        shulkersUsed++
        info("Swapping $worn worn tool(s) using ${shulker.stack.name.string}")
        placeShulker(shulker)
    }

    /** The shulker with the fewest fresh tools that still has one, so shulkers are used up one after another. */
    private fun SafeContext.nextShulkerSlot() =
        player.hotbarAndInventorySlots
            .filter { isShulker(it.stack) && it.stack.shulkerBoxContents.any(::isFreshTool) }
            .minByOrNull { slot -> slot.stack.shulkerBoxContents.count(::isFreshTool) }

    private fun SafeContext.open() {
        if (stopping) return collect()
        val pos = placedPos ?: return nextShulker()
        val worn = inInventory(::isWornTool)
        // Each swap is one request, plus room for retrying ghost items.
        open(pos, Phase.Swapping, extraTicks = worn * TICKS_PER_SWAP, maxClicks = worn * 2 + 10)
    }

    /** Swaps one worn tool for the freshest one. Three clicks stay under the default limit of five inventory packets. */
    private fun swap(handler: ScreenHandler): Boolean {
        val worn = handler.playerSlots.firstOrNull { isWornTool(it.stack) } ?: return toHotbar(handler)
        val fresh = handler.containerSlots.filter { isFreshTool(it.stack) }.maxByOrNull { it.stack.durabilityFraction } ?: return false
        submit {
            pickup(fresh.id)
            pickup(worn.id)
            pickup(fresh.id)
        }
        return true
    }

    /**
     * Moves a fresh tool into the hotbar when none is there, e.g. after ToolSaver moved the worn ones out,
     * so the build can use it. It goes where it's in the way the least.
     */
    private fun toHotbar(handler: ScreenHandler): Boolean {
        val hotbar = handler.playerSlots.takeLast(HOTBAR_SIZE)
        if (hotbar.any { isFreshTool(it.stack) }) return false
        val fresh = handler.playerSlots.dropLast(HOTBAR_SIZE).filter { isFreshTool(it.stack) }.maxByOrNull { it.stack.durabilityFraction } ?: return false
        val target = leastNeededHotbarSlot(hotbar) ?: return false
        submit { swap(fresh.id, hotbar.indexOf(target)) }
        return true
    }

    override fun SafeContext.workFailed(reason: String?) {
        when (phase) {
            Phase.Opening -> if (++openAttempts < MAX_OPEN_ATTEMPTS) open() else {
                warn("Couldn't open the shulker ($reason), picking it back up")
                // So no other shulker is tried after picking this one up.
                shulkersUsed = maxShulkers
                collect()
            }
            else -> {
                warn("Failed while swapping tools ($reason), picking the shulker back up")
                collect()
            }
        }
    }

    override fun SafeContext.finish() {
        val restocked = inInventory(::isFreshTool) - freshAtStart
        if (restocked > 0) info("Restocked $restocked tool(s)")
        success()
    }

    companion object {
        private const val TICKS_PER_SWAP = 20
        private const val HOTBAR_SIZE = 9
    }
}
