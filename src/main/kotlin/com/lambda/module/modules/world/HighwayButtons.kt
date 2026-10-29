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
import com.lambda.task.tasks.StationTask.Companion.STACK_SIZE
import com.lambda.task.tasks.StationTask.Companion.carriedCount
import com.lambda.task.tasks.StationTask.Companion.stopSafely
import com.lambda.task.tasks.StationTask.Companion.waitsForPlayer
import com.lambda.util.CommunicationUtils.logError
import com.lambda.util.CommunicationUtils.warn
import com.lambda.util.item.ItemStackUtils.shulkerBoxContents
import com.lambda.util.item.ItemUtils
import net.minecraft.item.ItemStack
import net.minecraft.registry.tag.ItemTags

/**
 * Places buttons on the highway's floor where magma cubes could spawn. Once on the last stack,
 * crafts back up to as many as were carried at the start, at least two stacks, from a carried shulker of logs, or one the
 * ender chest trip takes and puts back afterwards. Early, so a restock waits for a safe moment, e.g. a mob gone, instead
 * of the highway going on without buttons.
 */
class HighwayButtons(override val c: HighwayTools) : ConfigBlock, Automated by c {
    val enabled by c.setting("Place Buttons", false, "Places a button on every floor block a magma cube could spawn on, where 3 x 3 blocks around it and 3 up are open. Narrower highways need none")
    private val stoneButtons by c.setting("Stone Buttons", true, "Also places stone and polished blackstone buttons when carried. Only wooden ones are crafted") { enabled }
    private val restock by c.setting("Restock Buttons", true, "Once on the last stack, crafts back up to as many as were carried at the start, at least two stacks, from a shulker of logs, taken from the ender chest and put back if none is carried") { enabled }

    private var task: Task<*>? = null
    /** Buttons carried at the start, at least two stacks, which a restock crafts back up to. */
    private var target = 0
    private var retryTicks = 0
    /** A restock failed, maybe leaving a shulker placed, so none until the highway is started again. */
    var failed = false
    private var warnedOut = false
    /** Set once a shulker of logs taken on this ender chest trip was crafted from, so the trip puts it back. */
    private var returnLogShulker = false
    /** Crafting runs once per ender chest trip, one that made nothing isn't run again and again. */
    private var craftedThisTrip = false

    /** Whether a shulker is out to craft buttons. */
    val isRestocking get() = task?.isActive == true

    fun isButton(stack: ItemStack) = stack.isIn(ItemTags.WOODEN_BUTTONS) || stoneButtons && stack.isIn(ItemTags.BUTTONS)

    private fun isLogShulker(stack: ItemStack) = CraftButtonsTask.logsIn(stack) > 0

    /** Not the offhand: the build only places what [TargetState.FloorButton] finds in the inventory. */
    private fun SafeContext.carried() = carriedCount(::isButton)

    /** On the last stack, so a restock happens while there are still some to place. */
    private fun SafeContext.isLow() = carried() <= STACK_SIZE

    private fun SafeContext.carriesLogShulker() = player.inventory.mainStacks.any(::isLogShulker)

    fun SafeContext.onStart() {
        failed = false
        retryTicks = 0
        warnedOut = false
        returnLogShulker = false
        craftedThisTrip = false
        // Above the last stack a restock starts on, so each one gains at least a stack and can't loop.
        target = maxOf(carried(), 2 * STACK_SIZE)
        if (enabled && restock) LOG.info("[HighwayButtons] restocks up to $target buttons")
    }

    /** What goes on the floor where buttons belong, null while off. With none carried, nothing is placed or broken there. */
    fun SafeContext.floorButton(): TargetState.FloorButton? =
        if (enabled) TargetState.FloorButton(::isButton, placing = carried() > 0) else null

    fun SafeContext.tick() {
        if (retryTicks > 0) retryTicks--
        task?.let {
            if (it.isActive) return
            // Ended without its callbacks, e.g. cancelled.
            end()
        }
        if (!enabled || !isLow()) {
            warnedOut = false
            return
        }
        if (!restock || failed || retryTicks > 0 || c.isBusy) return
        val logShulker = carriesLogShulker()
        // Without either nothing can be crafted, so the highway goes on without buttons.
        val noEnderChest = when {
            !inventoryConfig.accessEnderChest -> "Access Ender Chest is off"
            enderChests() < MIN_ENDER_CHESTS -> "fewer than $MIN_ENDER_CHESTS ender chests are carried"
            else -> null
        }
        if (!logShulker && noEnderChest != null) {
            if (!warnedOut) warn("${carried()} buttons left and no shulker of logs carried to craft more, and $noEnderChest, going on without a restock")
            warnedOut = true
            return
        }
        // Buttons are a bonus, the highway goes on without them until there's room.
        if (!hasRoom()) {
            if (!warnedOut) warn("${carried()} buttons left, but the inventory has no room to craft $target and too little junk to throw away, going on without a restock")
            warnedOut = true
            retryTicks = RETRY_TICKS
            return
        }
        // The ender chest trip brings one, see enderChestWant.
        if (!logShulker || waitsForPlayer() || !c.pauseFor(this@HighwayButtons)) return
        task = newTask().onCompletion {
            end()
            if (isLow()) retryTicks = RETRY_TICKS
        }.onFailure {
            logError("Restocking buttons failed: ${it.message}. Stopped HighwayTools so nothing gets left behind")
            failed = true
            c.disable()
        }.run()
    }

    /** A shulker of logs from the ender chest once on the last stack of buttons with none carried. */
    fun SafeContext.enderChestWant(): Want? {
        if (!enabled || !restock || failed) return null
        return Want(
            "a shulker of logs", 1, CraftButtonsTask::logsIn,
            isLow = { isLow() && !carriesLogShulker() },
            isMet = { !isLow() || carriesLogShulker() },
        )
    }

    /** Crafting for the ender chest trip to run before it's broken, with the shulker of logs it just took. Null when not needed. */
    fun SafeContext.craftNow(): Task<*>? {
        if (!enabled || !restock || failed || craftedThisTrip || !isLow() || !carriesLogShulker()) return null
        craftedThisTrip = true
        // Like a restock on its own. The shulker is kept and crafted from once there's room.
        if (!hasRoom()) {
            warn("${carried()} buttons left, but the inventory has no room to craft $target and too little junk to throw away, keeping the shulker of logs for later")
            return null
        }
        return newTask().onSuccess { returnLogShulker = true }.also { task = it }
    }

    /**
     * Whether the ender chest trip puts [stack] back: a shulker of only logs and planks, the one it took once crafted
     * from, and any with no logs left, which would be carried forever otherwise.
     */
    fun putsBack(stack: ItemStack): Boolean {
        if (!enabled || !restock || stack.item !in ItemUtils.shulkerBoxes) return false
        val contents = stack.shulkerBoxContents
        if (contents.any { !it.isEmpty && !CraftButtonsTask.isLog(it) && !CraftButtonsTask.isPlank(it) }) return false
        return returnLogShulker || contents.none(CraftButtonsTask::isLog)
    }

    fun tripEnded() {
        returnLogShulker = false
        craftedThisTrip = false
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
        val needed = Math.ceilDiv(target - carried(), STACK_SIZE) + ROOM_MARGIN
        val free = player.inventory.mainStacks.count { it.isEmpty || it.item in inventoryConfig.disposables }
        return free >= needed
    }

    private fun SafeContext.enderChests() = carriedCount(EnderChestRestockTask::isEnderChest)

    /** The task says in chat what it crafts. */
    private fun newTask(): Task<*> =
        CraftButtonsTask(
            isButton = ::isButton,
            target = target,
            swapDelay = SWAP_DELAY,
            automated = this@HighwayButtons,
            // The aura goes first, the restock carries on once the mob is dead.
            holdWhile = { c.isFighting },
        )

    private fun end() {
        task = null
        c.resume(this)
    }

    private companion object {
        const val ROOM_MARGIN = 3
        const val SWAP_DELAY = 5
        const val RETRY_TICKS = 1200
    }
}
