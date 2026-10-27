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
import com.lambda.Lambda.mc
import com.lambda.context.Automated
import com.lambda.context.SafeContext
import com.lambda.event.events.TickEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.interaction.handlers.BaritoneHandler
import com.lambda.interaction.managers.inventory.InventoryRequest.Companion.inventoryRequest
import com.lambda.interaction.managers.inventory.InventoryRequest.InvRequestBuilder
import com.lambda.task.Task
import com.lambda.threading.runSafe
import com.lambda.util.CommunicationUtils.warn
import com.lambda.util.TickTimer
import com.lambda.util.extension.containerSlots
import com.lambda.util.extension.playerSlots
import com.lambda.util.item.ItemUtils
import com.lambda.util.player.SlotUtils.hotbarAndInventorySlots
import com.lambda.util.player.SlotUtils.hotbarSlots
import com.lambda.util.player.SlotUtils.inventorySlots
import com.lambda.util.world.blockSearch
import com.lambda.util.world.closestEntity
import net.minecraft.block.Block
import net.minecraft.block.ShulkerBoxBlock
import net.minecraft.client.gui.hud.ChatHud
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.client.gui.screen.GameMenuScreen
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.entity.Entity
import net.minecraft.entity.mob.AbstractSkeletonEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.entity.mob.PiglinEntity
import net.minecraft.entity.mob.ZombieEntity
import net.minecraft.item.ItemStack
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.slot.Slot
import net.minecraft.util.math.BlockPos

/** A phase of a [StationTask]. It fails after [timeoutTicks], or never when null. */
interface StationPhase {
    val description: String
    val timeoutTicks: Int?
}

/**
 * Base for tasks that work a station of containers, one step and one click at a time,
 * since servers like 2b2t kick for clicking too fast.
 * Failed or timed out phases are retried a few times.
 *
 * @param opening The phase while a container is being opened.
 */
abstract class StationTask<P : StationPhase>(
    private val title: String,
    private val opening: P,
    automated: Automated,
    /**
     * Waits while this is true, e.g. while the aura fights a piglin that saw the container opened. An open container is
     * closed and a running step paused for it, afterwards the container is opened again and the task goes on.
     */
    private val holdWhile: () -> Boolean = { false },
) : Task<Unit>(), Automated by automated {
    override val name get() = "$title (${phase.description})"

    /** Ticks to wait after each inventory action. Read every tick, so it can change while running. */
    protected abstract val moveDelay: Int

    /** Decides what to do next. */
    protected abstract fun SafeContext.resume()

    /** Runs every tick while no step is running. */
    protected abstract fun SafeContext.onTick()

    /**
     * Starts breaking and picking up what's placed, for [stop]. Its callbacks see [stopping] and end the task once it's
     * back instead of going on. False when nothing is placed, so the task can just be cancelled, e.g. at a fixed station.
     */
    protected open fun SafeContext.packUp() = false

    /** Set by [stop], so the task ends once what's placed is back instead of going on. */
    protected var stopping = false
        private set

    /** The running step, e.g. to stop a nested station task too. */
    protected val currentStep get() = step

    private var warnedGrabber = false
    /** Steps paused for [holdWhile], to go on with once it's over. */
    private val heldSteps = mutableListOf<Task<*>>()
    /** A container was closed for [holdWhile], so it's opened again afterwards. */
    private var reopenAfterHold = false

    protected var phase = opening
        private set
    private var clicks = 0

    private var step: Task<*>? = null
    private var attempts = 0
    /** Unlike [clicks] never reset. */
    private var totalClicks = 0
    private var maxClicks = 0
    private var resumeInTicks = -1
    private var phaseTimeout = Int.MAX_VALUE
    private var resynced = false
    private val phaseTimer = TickTimer()
    private val settleTimer = TickTimer()
    /** The chat or game menu open before a container, shown again once it's closed. */
    private var previousScreen: Screen? = null
    /** The screen [open] opened, so a containerStep never clicks in one the server opened instead. */
    private var openedHandler: ScreenHandler? = null

    /** Whether the last inventory action had time to settle. */
    protected val settled get() = settleTimer.hasSurpassed(moveDelay)


    override fun SafeContext.onStart() {
        BaritoneHandler.cancel()
        resume()
    }

    init {
        // Also while paused for a step, so steps time out too.
        listen<TickEvent.Pre>(alwaysListen = true) {
            if (!isActive) return@listen
            phaseTimer.tick()
            settleTimer.tick()

            val holding = holdWhile()
            if (holding) hold() else release()
            // Waiting doesn't count towards a phase's timeout.
            if (holding) phaseTimer.reset()
            if (phaseTimer.hasSurpassed(phaseTimeout)) return@listen phaseFailed("timed out")
            if (state != State.Running || holding) return@listen
            if (resumeInTicks >= 0) {
                if (--resumeInTicks < 0) resume()
                return@listen
            }
            onTick()
        }

        // Failing doesn't cancel, so nothing else would close the container or stop walking.
        onFailure {
            closeContainer()
            BaritoneHandler.cancel()
        }
    }

    /**
     * Ends the task without leaving anything behind: a placed shulker or ender chest is first broken and picked back up.
     * Slower than [cancel], but nothing is lost. Ends right away when nothing is placed.
     */
    fun stop() {
        if (!isActive || stopping) return
        stopping = true
        // A pending resume or a reopen after a fight would go on with what was being done.
        resumeInTicks = -1
        reopenAfterHold = false
        if (runSafe { packUp() } != true) cancel()
    }

    /** Makes way for [holdWhile]: closes an open container and pauses what the running step does. */
    private fun SafeContext.hold() {
        val nested = step as? StationTask<*>
        if (nested != null && player.currentScreenHandler != player.playerScreenHandler) {
            // The container is the nested task's. Cancelling it would leave its placed shulker behind, so it closes
            // the container itself when it holds too, else it's only closed and the nested task's retries open it again.
            if (!nested.holdWhile()) closeContainer()
        } else if (player.currentScreenHandler != player.playerScreenHandler) {
            // A step that opened it would go on as if it were still open.
            cancelStep()
            closeContainer()
            reopenAfterHold = true
        }
        // Again every tick, since a step that finished part of its work wakes up its parent.
        step?.let { pauseRunning(it) }
    }

    private fun pauseRunning(task: Task<*>) {
        task.subTasks.toList().forEach(::pauseRunning)
        if (task.state != State.Running) return
        task.pause()
        heldSteps += task
    }

    private fun release() {
        heldSteps.forEach { it.activate() }
        heldSteps.clear()
        if (!reopenAfterHold) return
        reopenAfterHold = false
        resumeAfterClose()
    }

    /** Resumes after a short wait, so the inventory the server sends on close is in, e.g. what a smithing table gave back. */
    protected fun resumeAfterClose() {
        resumeInTicks = maxOf(moveDelay, RESUME_DELAY_TICKS)
        // The failed phase is over, so it can't time out again while waiting.
        phaseTimeout = Int.MAX_VALUE
    }

    protected fun enter(next: P, extraTicks: Int = 0) {
        // Which step it got stuck in, should it.
        LOG.info("[$title] ${next.description}")
        phase = next
        phaseTimeout = next.timeoutTicks?.plus(extraTicks) ?: Int.MAX_VALUE
        phaseTimer.reset()
        settleTimer.reset()
    }

    /** The phase made progress, so its timeout and click limit start over. */
    protected fun progressed() {
        phaseTimer.reset()
        clicks = 0
    }

    /** The phase worked, so later failures get their retries again. */
    protected fun phaseDone() {
        attempts = 0
    }

    /**
     * Opens the container at [pos], then enters [next] with [extraTicks] more time.
     * At most [maxClicks] requests are made in it, by default enough for every slot twice.
     */
    protected fun SafeContext.open(pos: BlockPos, next: P, extraTicks: Int, maxClicks: Int = player.hotbarAndInventorySlots.size * 2 + 10) {
        // Saved positions stay when a station moves, so don't walk off to one far away.
        if (!pos.isWithinDistance(player.pos, MAX_DISTANCE))
            return failure("${pos.toShortString()} is ${pos.toCenterPos().distanceTo(player.pos).toInt()} blocks away. Did the station move?")
        enter(opening)
        previousScreen = mc.currentScreen.takeIf { it !is HandledScreen<*> }
        runStep(OpenContainerTask(pos, this@StationTask)) { handler ->
            openedHandler = handler
            clicks = 0
            this@StationTask.maxClicks = maxClicks
            resynced = false
            enter(next, extraTicks)
        }
    }

    /**
     * Runs one inventory action in the open container. [action] returns false once there's nothing left to do,
     * then a resync catches clicks the server didn't take, and once it shows nothing left [onDone] runs.
     */
    protected fun SafeContext.containerStep(
        onDone: SafeContext.() -> Unit = { resumeAfterClose() },
        action: SafeContext.(ScreenHandler) -> Boolean,
    ) {
        val handler = player.currentScreenHandler.takeIf { it != player.playerScreenHandler }
            ?: return phaseFailed("the container closed")
        if (handler !== openedHandler) return phaseFailed("another screen opened")
        if (!settled) return
        // An item left on the cursor gets a few more clicks, closing would drop it with a full inventory.
        val cursorFull = !handler.cursorStack.isEmpty
        if (clicks >= maxClicks + if (cursorFull) CURSOR_EXTRA_CLICKS else 0) return phaseFailed("gave up after $clicks attempts")
        if (cursorFull) return putCursorAway(handler)
        val clicksBefore = totalClicks
        if (action(handler)) {
            if (totalClicks > clicksBefore) resynced = false
            return
        }
        if (!resynced) {
            resynced = submit { resyncInventory() }
            return
        }
        closeContainer()
        phaseDone()
        onDone()
    }

    private fun SafeContext.putCursorAway(handler: ScreenHandler) {
        val target = handler.playerSlots.firstOrNull { it.stack.isEmpty }
            ?: handler.containerSlots.firstOrNull { it.stack.isEmpty }
            ?: return failure("Nowhere to put the item on the cursor")
        submit { pickup(target.id) }
    }

    /** Fails the task from inside a [containerStep] action, which then counts as having acted. */
    protected fun failAction(message: String): Boolean {
        failure(message)
        return true
    }

    /** Submits an all-or-nothing click request and waits [moveDelay] before the next one. */
    protected fun submit(request: InvRequestBuilder.() -> Unit): Boolean {
        val done = inventoryRequest(builder = request).submit().done
        if (done) {
            clicks++
            totalClicks++
            settleTimer.reset()
        }
        return done
    }

    /** Closes the open container and shows the screen from before it again. */
    protected fun SafeContext.closeContainer() {
        if (player.currentScreenHandler == player.playerScreenHandler) return
        player.closeHandledScreen()
        // A new copy, since showing the old one again skips its init and breaks the chat.
        when (previousScreen) {
            is ChatScreen -> mc.openChatScreen(ChatHud.ChatMethod.MESSAGE)
            is GameMenuScreen -> mc.openGameMenu(false)
            else -> {}
        }
        previousScreen = null
    }

    /** Stacks matching [predicate] in the inventory, and in the offhand unless [offhand] is false. */
    protected fun SafeContext.carried(predicate: (ItemStack) -> Boolean, offhand: Boolean = true) =
        player.hotbarAndInventorySlots.count { predicate(it.stack) } + if (offhand && predicate(player.offHandStack)) 1 else 0

    /** Stacks matching [predicate] in the inventory, which is all a container reaches. */
    protected fun SafeContext.inInventory(predicate: (ItemStack) -> Boolean) = carried(predicate, offhand = false)

    /** Items matching [predicate] in the inventory. */
    protected fun SafeContext.carriedCount(predicate: (ItemStack) -> Boolean) =
        player.hotbarAndInventorySlots.sumOf { if (predicate(it.stack)) it.stack.count else 0 }

    /** Whether there's a free slot. Otherwise throws away a disposable stack, or fails if there's none. */
    protected fun SafeContext.hasFreeSlotOrThrow(whereIsShulker: () -> String): Boolean {
        if (player.hotbarAndInventorySlots.any { it.stack.isEmpty }) return true
        val junk = player.hotbarAndInventorySlots.firstOrNull { it.stack.item in inventoryConfig.disposables }
        if (junk == null) failure("Inventory is full and has nothing to throw away. ${whereIsShulker()}")
        else LOG.info("[$title] throwing ${junk.stack.count}x ${junk.stack.name.string} to make room, sent ${submit { throwStack(junk.id) }}")
        return false
    }


    /**
     * Whether the container at [pos] can be broken now. A mob nearby that would pick up its drop is waited out,
     * which doesn't count towards the phase's timeout, while e.g. the aura kills it.
     */
    protected fun SafeContext.canBreakSafely(pos: BlockPos): Boolean {
        val grabber = closestEntity<MobEntity>(GRABBER_RANGE, pos) { isItemGrabber(it) } ?: run {
            warnedGrabber = false
            return true
        }
        if (!warnedGrabber) warn("Waiting for the ${grabber.name.string} to go away before breaking it, it would pick up the drop")
        warnedGrabber = true
        progressed()
        return false
    }

    protected fun SafeContext.shulkersAround() = blocksAround { it is ShulkerBoxBlock }

    /** Where blocks like a placed container are nearby, to find one that was placed after a failed try. */
    protected fun SafeContext.blocksAround(predicate: (Block) -> Boolean): Set<BlockPos> =
        blockSearch(CONTAINER_SEARCH_RADIUS) { _, state -> predicate(state.block) }.keys

    protected fun cancelStep() {
        step?.cancel()
        step = null
    }

    /** Logs why the phase failed, to find where it got stuck, then lets [stepFailed] recover. */
    protected fun SafeContext.phaseFailed(reason: String?) {
        LOG.info("[$title] ${phase.description} failed: $reason")
        stepFailed(reason)
    }

    /** The phase failed or timed out. By default the container is closed and [resume] tries again a few times. */
    protected open fun SafeContext.stepFailed(reason: String?) {
        cancelStep()
        closeContainer()

        if (++attempts >= MAX_ATTEMPTS) return failure("Failed while ${phase.description} ($reason)")
        warn("Failed while ${phase.description} ($reason), retrying")
        resumeAfterClose()
    }

    /** Runs [task] as the current step and [onDone] with its result. A step that fails calls [stepFailed]. */
    protected fun <R> SafeContext.runStep(task: Task<R>, onDone: SafeContext.(R) -> Unit) {
        step = task
        task.onSuccess { result ->
            if (step !== task) return@onSuccess
            step = null
            onDone(result)
        }.execute(this@StationTask)
    }

    override fun onSubTaskFailure(subTask: Task<*>, cause: Throwable) {
        if (subTask === step) runSafe { phaseFailed(cause.message) }
        else super.onSubTaskFailure(subTask, cause)
    }

    // After a failed step a new one may already run, which keeps this task paused.
    override fun onSubTaskCompletion(subTask: Task<*>) {
        if (step == null || step === subTask) super.onSubTaskCompletion(subTask)
    }

    override fun SafeContext.onCancel() {
        closeContainer()
        BaritoneHandler.cancel()
        warn("Stopped ${title.replaceFirstChar { it.lowercase() }} while ${phase.description}")
    }

    companion object {
        /** Swap button that targets the offhand instead of a hotbar slot. */
        const val OFFHAND_BUTTON = 40
        private const val MAX_ATTEMPTS = 3
        private const val RESUME_DELAY_TICKS = 10
        private const val CURSOR_EXTRA_CLICKS = 4
        private const val MAX_DISTANCE = 16.0
        private const val CONTAINER_SEARCH_RADIUS = 6

        /** How close another player holds off restocks, so they can't take a placed container or its drop. */
        private const val PLAYER_RANGE = 5.0
        /** Who [waitsForPlayer] last warned about, so it's said once. */
        private var warnedPlayer: String? = null
        /** How close to a placed container a mob that would pick up its drop holds off breaking it. */
        private const val GRABBER_RANGE = 6.0

        /** Another player close enough to see a placed container or take its drop, friends too since they'd pick it up as well. */
        fun SafeContext.nearbyPlayer() =
            world.players.firstOrNull { it !== player && it.squaredDistanceTo(player) < PLAYER_RANGE * PLAYER_RANGE }

        /** Whether a restock that's needed or running waits for another player nearby to go, saying so once. */
        fun SafeContext.waitsForPlayer(): Boolean {
            val name = nearbyPlayer()?.name?.string ?: run {
                warnedPlayer = null
                return false
            }
            if (warnedPlayer != name) warn("$name is nearby, restocking waits until they're gone")
            warnedPlayer = name
            return true
        }

        /**
         * A mob that picks up a dropped shulker or ender chest: one that can pick up loot with nothing in its hand,
         * like a baby piglin. Armed ones like zombified piglins keep their weapon over it, and piglin brutes only take axes.
         */
        fun isItemGrabber(entity: Entity) = entity is MobEntity && entity.isAlive && entity.mainHandStack.isEmpty &&
            (entity is PiglinEntity || entity is ZombieEntity || entity is AbstractSkeletonEntity)

        /** [stop] for a station task, else [cancel]. Null and finished tasks are left alone. */
        fun Task<*>?.stopSafely() {
            if (this?.isActive != true) return
            if (this is StationTask<*>) stop() else cancel()
        }

        /** Where a tool moved into the hotbar is in the way the least: empty, junk, blocks or food, then anything but a tool or shulker. */
        fun Automated.leastNeededHotbarSlot(hotbar: List<Slot>): Slot? =
            hotbar.firstOrNull { it.stack.isEmpty }
                ?: hotbar.firstOrNull { it.stack.item in inventoryConfig.disposables }
                ?: hotbar.firstOrNull { it.stack.isStackable && it.stack.item !in ItemUtils.shulkerBoxes }
                ?: hotbar.firstOrNull { !it.stack.isDamageable && it.stack.item !in ItemUtils.shulkerBoxes }

        /**
         * Swaps the stack matching [isWanted] that [rank] likes most from the inventory into the hotbar when none is there,
         * e.g. a fresh pickaxe after ToolSaver moved the worn ones out. Returns whether it swapped.
         */
        fun SafeContext.moveIntoHotbar(automated: Automated, isWanted: (ItemStack) -> Boolean, rank: (ItemStack) -> Float = { 0f }): Boolean {
            if (player.currentScreenHandler != player.playerScreenHandler) return false
            val hotbar = player.hotbarSlots
            if (hotbar.any { isWanted(it.stack) }) return false
            val from = player.inventorySlots.filter { isWanted(it.stack) }.maxByOrNull { rank(it.stack) } ?: return false
            val target = automated.leastNeededHotbarSlot(hotbar) ?: return false
            with(automated) { inventoryRequest { swap(from.id, hotbar.indexOf(target)) }.submit() }
            return true
        }
    }
}
