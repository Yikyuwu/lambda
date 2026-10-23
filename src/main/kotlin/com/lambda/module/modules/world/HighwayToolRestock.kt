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

package com.lambda.module.modules.world

import com.lambda.config.ConfigBlock
import com.lambda.context.Automated
import com.lambda.context.SafeContext
import com.lambda.module.modules.player.ToolSaver
import com.lambda.task.RootTask.run
import com.lambda.task.Task
import com.lambda.task.tasks.RestockToolsTask
import com.lambda.task.tasks.StationTask.Companion.moveIntoHotbar
import com.lambda.task.tasks.StationTask.Companion.waitsForPlayer
import com.lambda.task.tasks.StationTask.Companion.stopSafely
import com.lambda.util.CommunicationUtils.logError
import com.lambda.util.item.ItemStackUtils.durabilityFraction
import com.lambda.util.item.ItemStackUtils.shulkerBoxContents
import com.lambda.util.item.ItemUtils
import com.lambda.util.item.ToolFilter
import com.lambda.util.player.SlotUtils.hotbarAndInventorySlots
import net.minecraft.item.ItemStack

/** Swaps worn tools for fresh ones from carried shulkers while the highway is built. */
class HighwayToolRestock(override val c: HighwayTools) : ConfigBlock, Automated by c {
    val enabled by c.setting("Restock Tools", true, "Swaps worn tools for fresh ones from carried shulkers once all of them are worn")
    private val tool by c.setting("Restock Tool", ToolFilter.NetheritePickaxe, "Tools to restock") { enabled }
    private val wornAt by c.setting("Worn At", 1, 0..100, 1, "Durability left at which a tool counts as worn", "%") { enabled }
    private val maxShulkers by c.setting("Max Shulkers", 5, 1..5, 1, "Most shulkers to open per restock") { enabled }
    private val swapDelay by c.setting("Swap Delay", 5, 1..20, 1, "Ticks between swaps", " ticks") { enabled }
    private val retryDelay by c.setting("Retry Delay", 30, 5..300, 5, "Wait after a restock that found no fresh tool", " s") { enabled }

    private var task: Task<*>? = null
    private var retryTicks = 0
    private var hotbarCooldown = 0
    /** A restock failed, maybe leaving a shulker placed, so none until the highway is started again. */
    var failed = false

    /** Whether a shulker is out to restock tools. */
    val isRestocking get() = task?.isActive == true

    /** Whether [stack] is a shulker with a fresh tool to restock from. */
    fun isToolShulker(stack: ItemStack) = freshTools(stack) > 0

    /** How many fresh tools to restock from the shulker [stack] holds. */
    fun freshTools(stack: ItemStack) =
        if (stack.item in ItemUtils.shulkerBoxes) stack.shulkerBoxContents.count { it.isFresh } else 0

    fun SafeContext.tick() {
        if (retryTicks > 0) retryTicks--
        if (hotbarCooldown > 0) hotbarCooldown--
        task?.let {
            if (it.isActive) return
            // Ended without its callbacks, e.g. cancelled.
            end()
        }
        if (!enabled) return
        if (needsRestock()) return start()
        // E.g. after ToolSaver moved the worn ones out, the build only uses what's in the hotbar.
        // Not while something else uses the hotbar, like placing the ender chest.
        if (hotbarCooldown > 0 || c.isBusy) return
        if (moveIntoHotbar(this@HighwayToolRestock, { it.isFresh }, { it.durabilityFraction })) hotbarCooldown = HOTBAR_COOLDOWN_TICKS
    }

    /** Stops a restock, which first picks its shulker back up. */
    fun stop() {
        task.stopSafely()
        if (task?.isActive != true) end()
    }

    /**
     * A restock for another task to run now, e.g. the ender chest trip with a pickaxe shulker it just took,
     * since breaking the ender chest needs a pickaxe. Null when none is needed.
     */
    fun SafeContext.restockNow(): Task<*>? {
        if (!enabled || failed || !hasOnlyWornTools()) return null
        return newTask().also { task = it }
    }

    private fun SafeContext.needsRestock() = retryTicks <= 0 && !failed && !c.isBusy && hasOnlyWornTools() && !waitsForPlayer()

    /** Whether every carried restock tool is worn and a carried shulker still has a fresh one. */
    private fun SafeContext.hasOnlyWornTools(): Boolean {
        val stacks = player.hotbarAndInventorySlots.map { it.stack }
        if (stacks.none { it.isTool } || stacks.any { it.isFresh }) return false
        return stacks.any(::isToolShulker)
    }

    private fun newTask() = RestockToolsTask(
        isTool = { it.isTool },
        isWorn = { it.isWorn },
        maxShulkers = maxShulkers,
        swapDelay = swapDelay,
        automated = this,
        // The aura goes first, the restock carries on once the mob is dead.
        holdWhile = { c.isFighting },
    )

    private fun SafeContext.start() {
        // A build that can't be paused now is busy with something else.
        if (!c.pauseFor(this@HighwayToolRestock)) return

        task = newTask().onCompletion {
            end()
            if (player.hotbarAndInventorySlots.none { it.stack.isFresh }) retryTicks = retryDelay * 20
        }.onFailure {
            logError("Restocking tools failed: ${it.message}. Stopped HighwayTools so nothing gets left behind")
            failed = true
            c.disable()
        }.run()
    }

    private fun end() {
        task = null
        c.resume(this)
    }

    private val ItemStack.isTool get() = tool.matches(item)

    /** Also what ToolSaver keeps out of the hotbar, which the build can't use. */
    private val ItemStack.isWorn get() = isDamageable && durabilityFraction < wornAt / 100f || ToolSaver.keepsOut(this)

    private val ItemStack.isFresh get() = isTool && !isWorn

    private companion object {
        /** Until the swap shows in the inventory, so it isn't swapped back out. */
        const val HOTBAR_COOLDOWN_TICKS = 10
    }
}
