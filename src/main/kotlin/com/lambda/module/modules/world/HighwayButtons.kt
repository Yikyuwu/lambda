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

import com.lambda.Lambda.LOG
import com.lambda.config.ConfigBlock
import com.lambda.context.Automated
import com.lambda.context.SafeContext
import com.lambda.interaction.construction.verify.TargetState
import com.lambda.task.RootTask.run
import com.lambda.task.Task
import com.lambda.task.tasks.CraftButtonsTask
import com.lambda.task.tasks.EnderChestRestockTask
import com.lambda.task.tasks.EnderChestRestockTask.Companion.MIN_ENDER_CHESTS
import com.lambda.task.tasks.EnderChestRestockTask.Want
import com.lambda.task.tasks.StationTask.Companion.stopSafely
import com.lambda.task.tasks.StationTask.Companion.waitsForPlayer
import com.lambda.util.CommunicationUtils.info
import com.lambda.util.CommunicationUtils.logError
import com.lambda.util.CommunicationUtils.warn
import net.minecraft.item.ItemStack
import net.minecraft.registry.tag.ItemTags

/**
 * Places buttons on the highway's floor so magma cubes, which need 3x3 blocks of floor, can't spawn on it. Once none are
 * left, crafts as many as were carried at the start from a carried shulker of logs, or one the ender chest trip takes
 * and puts back afterwards.
 */
class HighwayButtons(override val c: HighwayTools) : ConfigBlock, Automated by c {
    val enabled by c.setting("Place Buttons", false, "Places a button on every second floor block both ways, so no 3x3 of floor is left for a magma cube to spawn on")
    private val stoneButtons by c.setting("Stone Buttons", true, "Also places stone and polished blackstone buttons when carried. Only wooden ones are crafted") { enabled }
    private val restock by c.setting("Restock Buttons", true, "Once none are left, crafts as many as were carried at the start from a shulker of logs, taken from the ender chest and put back if none is carried") { enabled }

    private var task: Task<*>? = null
    /** Buttons carried at the start, which a restock crafts back up to. */
    private var target = 0
    private var retryTicks = 0
    /** A restock failed, maybe leaving a shulker placed, so none until the highway is started again. */
    var failed = false
    private var warnedOut = false
    /** Set once a shulker of logs taken on this ender chest trip was crafted from, so the trip puts it back. */
    private var returnLogShulker = false

    /** Whether a shulker is out to craft buttons. */
    val isRestocking get() = task?.isActive == true

    fun isButton(stack: ItemStack) = stack.isIn(ItemTags.WOODEN_BUTTONS) || stoneButtons && stack.isIn(ItemTags.BUTTONS)

    private fun isLogShulker(stack: ItemStack) = CraftButtonsTask.logsIn(stack) > 0

    /** Also the offhand, like the other restocks count. */
    private fun SafeContext.carried() = (player.inventory.mainStacks + player.offHandStack).sumOf { if (isButton(it)) it.count else 0 }

    private fun SafeContext.carriesLogShulker() = player.inventory.mainStacks.any(::isLogShulker)

    fun SafeContext.onStart() {
        failed = false
        retryTicks = 0
        warnedOut = false
        returnLogShulker = false
        target = carried().takeIf { it > 0 } ?: DEFAULT_TARGET
        if (enabled && restock) LOG.info("[Restocking buttons] restocks up to $target buttons")
    }

    /** What goes on the floor where buttons belong, null while off. With none carried, nothing is placed or broken there. */
    fun SafeContext.floorButton(): TargetState? =
        if (enabled) TargetState.FloorButton(::isButton, placing = carried() > 0) else null

    fun SafeContext.tick() {
        if (retryTicks > 0) retryTicks--
        task?.let {
            if (it.isActive) return
            // Ended without its callbacks, e.g. cancelled.
            end()
        }
        if (!enabled || carried() > 0) {
            warnedOut = false
            return
        }
        if (!restock || failed || retryTicks > 0 || c.isBusy) return
        val logShulker = carriesLogShulker()
        // Without either nothing can be crafted, so the highway goes on without buttons.
        if (!logShulker && !(inventoryConfig.accessEnderChest && enderChests() >= MIN_ENDER_CHESTS)) {
            if (!warnedOut) warn("Out of buttons and no shulker of logs to craft more, going on without them")
            warnedOut = true
            return
        }
        if (!hasRoom()) {
            logError("Out of buttons, but the inventory has no room to craft ${target - carried()} and too little junk to throw away. Stopped HighwayTools, make room and start it again")
            failed = true
            return c.disable()
        }
        // The ender chest trip brings one, see enderChestWant.
        if (!logShulker || waitsForPlayer() || !c.pauseFor(this@HighwayButtons)) return
        task = newTask().onCompletion {
            end()
            if (carried() == 0) retryTicks = RETRY_TICKS
        }.onFailure {
            logError("Restocking buttons failed: ${it.message}. Stopped HighwayTools so nothing gets left behind")
            failed = true
            c.disable()
        }.run()
    }

    /** A shulker of logs from the ender chest once out of buttons with none carried. */
    fun SafeContext.enderChestWant(): Want? {
        if (!enabled || !restock || failed) return null
        return Want(
            "a shulker of logs", 1, CraftButtonsTask::logsIn,
            isLow = { carried() == 0 && !carriesLogShulker() },
            isMet = { carried() > 0 || carriesLogShulker() },
        )
    }

    /** Crafting for the ender chest trip to run before it's broken, with the shulker of logs it just took. Null when not needed. */
    fun SafeContext.craftNow(): Task<*>? {
        if (!enabled || !restock || failed || carried() > 0 || !carriesLogShulker()) return null
        return newTask().onSuccess { returnLogShulker = true }.also { task = it }
    }

    /** Whether the ender chest trip puts [stack] back: the shulker of logs it took, once crafted from. */
    fun putsBack(stack: ItemStack) = returnLogShulker && isLogShulker(stack)

    fun tripEnded() {
        returnLogShulker = false
    }

    /** Stops a restock, which first picks its shulker back up. */
    fun stop() {
        task.stopSafely()
        if (task?.isActive != true) end()
    }

    /**
     * Whether there's room to craft back up to [target]: a slot per stack of buttons, which the planks take before,
     * and [ROOM_MARGIN] for the leftover logs, a grid slot's worth and the shulker. Junk counts, it's thrown away.
     */
    private fun SafeContext.hasRoom(): Boolean {
        val needed = (target - carried() + STACK - 1) / STACK + ROOM_MARGIN
        val free = player.inventory.mainStacks.count { it.isEmpty || it.item in inventoryConfig.disposables }
        return free >= needed
    }

    private fun SafeContext.enderChests() = player.inventory.mainStacks.sumOf { if (EnderChestRestockTask.isEnderChest(it)) it.count else 0 }

    private fun SafeContext.newTask(): Task<*> {
        info("Out of buttons, crafting $target from a shulker of logs")
        return CraftButtonsTask(
            isButton = ::isButton,
            target = target,
            swapDelay = SWAP_DELAY,
            automated = this@HighwayButtons,
            // The aura goes first, the restock carries on once the mob is dead.
            holdWhile = { c.isFighting },
        )
    }

    private fun end() {
        task = null
        c.resume(this)
    }

    private companion object {
        /** When none were carried at the start. */
        const val DEFAULT_TARGET = 64
        const val STACK = 64
        const val ROOM_MARGIN = 3
        const val SWAP_DELAY = 5
        const val RETRY_TICKS = 1200
    }
}
