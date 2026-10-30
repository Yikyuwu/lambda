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
import com.lambda.task.tasks.StationTask.Companion.nearbyGrabber
import com.lambda.task.tasks.StationTask.Companion.stopSafely
import com.lambda.task.tasks.StationTask.Companion.waitsForPlayer
import com.lambda.util.CommunicationUtils.logError
import com.lambda.util.CommunicationUtils.warn
import com.lambda.util.item.ItemStackUtils.shulkerBoxContents
import com.lambda.util.item.ItemUtils
import net.minecraft.item.ItemStack
import net.minecraft.registry.tag.ItemTags

/**
 * Places buttons on the highway's floor where magma cubes could spawn. At [restockAt] stacks left,
 * crafts back up to [stacks] stacks from a carried shulker of logs, or one the ender chest trip takes and puts back
 * afterwards. Early, so a restock waits for a safe moment, e.g. a mob gone, instead
 * of the highway going on without buttons.
 */
class HighwayButtons(override val c: HighwayTools) : ConfigBlock, Automated by c {
    val enabled by c.setting("Place Buttons", false, "Places a button on every floor block a magma cube could spawn on, where 3 x 3 blocks around it and 3 up are open. Narrower highways need none")
    private val stoneButtons by c.setting("Stone Buttons", true, "Also places stone and polished blackstone buttons when carried. Only wooden ones are crafted") { enabled }
    private val restock by c.setting("Restock Buttons", true, "At Restock At stacks left, crafts back up to Button Stacks from a shulker of logs, taken from the ender chest and put back if none is carried") { enabled }
    private val stacks by c.setting("Button Stacks", 4, 1..16, 1, "Stacks of buttons a restock crafts up to, as far as the inventory has room") { enabled && restock }
    private val restockAt by c.setting("Restock At", 1, 1..12, 1, "Stacks of buttons left at which a restock starts, at most a stack below Button Stacks", " stacks") { enabled && restock }

    private var task: Task<*>? = null
    private var retryTicks = 0
    /** A restock failed, maybe leaving a shulker placed, so none until the highway is started again. */
    var failed = false
    private var warnedOut = false
    private var warnedMob = false
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

    /** What a restock crafts up to. */
    private val target get() = stacks * STACK_SIZE

    /**
     * At [restockAt] stacks, so a restock happens while there are still some to place. At most a stack below [target],
     * half of it with one stack set, so each restock gains some and can't loop.
     */
    private val lowAt get() = minOf(restockAt * STACK_SIZE, maxOf(target - STACK_SIZE, target / 2))

    private fun SafeContext.isLow() = carried() <= lowAt

    /**
     * Whether a restock that's due waits for a mob to be killed or a mob that would pick up the shulker's drop to go,
     * saying so once. Started early, see [restockAt], so this has time. Out of buttons it starts anyway: a fight pauses
     * it until the mob is dead, and the shulker is only broken once no mob would pick it up.
     */
    private fun SafeContext.waitsForMob(): Boolean {
        val mob = when {
            carried() == 0 -> null
            c.isFighting -> "mob being fought"
            else -> nearbyGrabber()?.name?.string
        }
        if (mob == null) {
            warnedMob = false
            return false
        }
        if (!warnedMob) LOG.info("[HighwayButtons] restock waits for the $mob, ${carried()} buttons left")
        warnedMob = true
        return true
    }

    private fun SafeContext.carriesLogShulker() = player.inventory.mainStacks.any(::isLogShulker)

    fun SafeContext.onStart() {
        failed = false
        retryTicks = 0
        warnedOut = false
        warnedMob = false
        returnLogShulker = false
        craftedThisTrip = false
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
        if (!restock || failed || retryTicks > 0 || c.isUsingContainer || waitsForMob()) return
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
            if (!warnedOut) warn("${carried()} buttons left, but the inventory has no room to craft more and too little junk to throw away, going on without a restock")
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

    /** A shulker of logs from the ender chest once buttons are low with none carried. */
    fun SafeContext.enderChestWant(): Want? {
        if (!enabled || !restock || failed) return null
        return Want(
            "a shulker of logs", 1, CraftButtonsTask::logsIn,
            isLow = { isLow() && !carriesLogShulker() && !waitsForMob() },
            isMet = { !isLow() || carriesLogShulker() },
        )
    }

    /** Crafting for the ender chest trip to run before it's broken, with the shulker of logs it just took. Null when not needed. */
    fun SafeContext.craftNow(): Task<*>? {
        if (!enabled || !restock || failed || craftedThisTrip || !isLow() || !carriesLogShulker()) return null
        craftedThisTrip = true
        // Like a restock on its own. The shulker is kept and crafted from once there's room.
        if (!hasRoom()) {
            warn("${carried()} buttons left, but the inventory has no room to craft more and too little junk to throw away, keeping the shulker of logs for later")
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
     * Buttons a restock crafts up to: [target], or fewer when the inventory has no room for all of it. A slot per new
     * stack of buttons, which the planks take before, and [ROOM_MARGIN] for the leftover logs, a grid slot's worth and
     * the shulker. Junk counts, it's thrown away.
     */
    private fun SafeContext.craftTarget(): Int {
        val free = player.inventory.mainStacks.count { it.isEmpty || it.item in inventoryConfig.disposables }
        return minOf(target, carried() + (free - ROOM_MARGIN).coerceAtLeast(0) * STACK_SIZE)
    }

    /** Whether a restock gets above [lowAt], see [isLow]. */
    private fun SafeContext.hasRoom() = craftTarget() > lowAt

    private fun SafeContext.enderChests() = carriedCount(EnderChestRestockTask::isEnderChest)

    /** The task says in chat what it crafts. */
    private fun SafeContext.newTask(): Task<*> =
        CraftButtonsTask(
            isButton = ::isButton,
            target = craftTarget(),
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
