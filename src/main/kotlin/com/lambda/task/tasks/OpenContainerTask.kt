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

import baritone.api.pathing.goals.GoalNear
import com.lambda.context.Automated
import com.lambda.context.SafeContext
import com.lambda.event.events.InventoryEvent
import com.lambda.event.events.TickEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.interaction.handlers.BaritoneHandler
import com.lambda.interaction.managers.rotating.IRotationRequest.Companion.rotationRequest
import com.lambda.task.Task
import com.lambda.threading.runSafeAutomated
import com.lambda.util.TickTimer
import com.lambda.util.player.RotationUtils.lookAtBlock
import com.lambda.util.world.raycast.RayCastUtils.blockResult
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.screen.ScreenHandler
import net.minecraft.util.Hand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

class OpenContainerTask @Ta5kBuilder constructor(
    private val blockPos: BlockPos,
    private val automated: Automated,
    private val waitForSlotLoad: Boolean = true,
    private val sides: Set<Direction> = Direction.entries.toSet()
) : Task<ScreenHandler>(), Automated by automated {
    override val name get() = "${containerState.description()} at ${blockPos.toShortString()}"

    private var screenHandler: ScreenHandler? = null
    private var containerState = State.Scoping
    private var pathed = false

    private val retryTimer = TickTimer()

    enum class State {
        Pathing,
        Scoping,
        Opening,
        SlotLoading;

        fun description() = when (this) {
            Pathing -> "Pathing closer"
            Scoping -> "Waiting for scope"
            Opening -> "Opening container"
            SlotLoading -> "Waiting for slots to load"
        }
    }

    override fun SafeContext.onStart() {
        opening += this@OpenContainerTask
    }

    override fun SafeContext.onCancel() = stopPathing()

    init {
        onSuccess { stopPathing() }

        listen<InventoryEvent.Open> {
            if (containerState != State.Opening) return@listen

            screenHandler = it.screenHandler
            opened = WeakReference(it.screenHandler)
            containerState = State.SlotLoading

            if (!waitForSlotLoad) success(it.screenHandler)
        }

        listen<InventoryEvent.Close> {
            if (screenHandler != it.screenHandler) return@listen

            containerState = State.Scoping
            screenHandler = null
        }

        listen<InventoryEvent.FullUpdate> {
            if (containerState != State.SlotLoading) return@listen

            screenHandler?.let {
                success(it)
            }
        }

        listen<TickEvent.Pre> {
            if (containerState == State.Opening) {
                retryTimer.tick()
                if (retryTimer.hasSurpassed(10)) {
                    retryTimer.reset()
                    containerState = State.Scoping
                }
                return@listen
            }

            if (containerState != State.Scoping && containerState != State.Pathing) return@listen

            val checkedHit = runSafeAutomated { lookAtBlock(blockPos, sides) }
                ?: run {
                    containerState = State.Pathing
                    pathed = true
                    if (!BaritoneHandler.isActive) BaritoneHandler.setGoalAndPath(GoalNear(blockPos, 3))
                    return@listen
                }
            stopPathing()
            if (interactConfig.rotate && !rotationRequest { rotation(checkedHit.rotation) }.submit().done) return@listen
            // Sneaking with an item in hand would use it on the container instead of opening it.
            if (player.isSneaking) return@listen

            interaction.interactBlock(player, Hand.MAIN_HAND, checkedHit.hit.blockResult ?: return@listen)
            player.swingHand(Hand.MAIN_HAND)

            containerState = State.Opening
        }
    }

    private fun stopPathing() {
        if (!pathed) return
        pathed = false
        BaritoneHandler.cancel()
    }

    companion object {
        private var opened = WeakReference<ScreenHandler>(null)
        private val opening: MutableSet<OpenContainerTask> = Collections.newSetFromMap(WeakHashMap())

        /** Whether a task is opening a container, so nothing else should make the player sneak or turn it away, see RotationLock. */
        val isOpening: Boolean
            get() {
                opening.removeIf { !it.isActive }
                return opening.isNotEmpty()
            }

        /** Whether [screen] is a container a task opened, not the player. */
        fun isAutomated(screen: Screen?) = screen is HandledScreen<*> && screen.screenHandler === opened.get()
    }
}
