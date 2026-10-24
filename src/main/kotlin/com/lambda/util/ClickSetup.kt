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

package com.lambda.util

import com.lambda.config.settings.complex.orUnset
import com.lambda.context.SafeContext
import com.lambda.event.callback.ICancellable
import com.lambda.event.events.PlayerEvent
import com.lambda.event.events.WorldEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.event.listener.UnsafeListener.Companion.listenUnsafe
import com.lambda.module.Module
import com.lambda.util.BlockUtils.blockEntity
import com.lambda.util.BlockUtils.blockState
import com.lambda.util.CommunicationUtils.info
import com.lambda.util.CommunicationUtils.warn
import com.lambda.util.world.raycast.RayCastUtils.blockResult
import net.minecraft.block.Block
import net.minecraft.inventory.Inventory
import net.minecraft.util.Hand
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import kotlin.reflect.KMutableProperty0

/**
 * Sets a module's positions by asking in chat to right-click them one after another.
 * Sneak-clicking sets one to 0 0 0, so the module finds it itself. Clicks don't go through while it runs.
 */
class ClickSetup(
    private val module: Module,
    private val steps: List<Step>,
) {
    /** Sets [property], shown as [name], to the position [pick] makes of a click on [what], null when it's the wrong thing. */
    class Step(
        val name: String,
        val what: String,
        val property: KMutableProperty0<BlockPos>,
        val pick: SafeContext.(HitResult) -> BlockPos?,
    )

    private var index = -1
    val isActive get() = index >= 0

    init {
        listen<PlayerEvent.Interact.Block> { click(it.hand, it.blockHitResult, it) }
        listenUnsafe<WorldEvent.Leave> { index = -1 }
    }

    fun start() {
        index = 0
        module.info("Right-click what's asked for. Sneak-click to let it find one itself, press Setup again to cancel")
        ask()
    }

    fun cancel() {
        if (!isActive) return
        index = -1
        module.info("Setup cancelled")
    }

    /** Sets every position back to 0 0 0, so the module finds them itself again. */
    fun reset() {
        cancel()
        steps.forEach { it.property.set(BlockPos.ORIGIN) }
        module.info("Positions reset to 0 0 0")
    }

    /** Cancels a running setup, or turns the module off and starts one. */
    fun toggle() {
        if (isActive) return cancel()
        module.disable()
        start()
    }

    private fun SafeContext.click(hand: Hand, hit: HitResult, event: ICancellable) {
        if (!isActive) return
        event.cancel()
        // The off hand is tried after the main hand, it would set the next step to the same click.
        if (hand != Hand.MAIN_HAND) return
        val step = steps[index]
        val pos = if (player.isSneaking) BlockPos.ORIGIN else step.pick(this, hit) ?: return module.warn("That's not ${step.what}")
        step.property.set(pos)
        module.info("${step.name} set to ${pos.orUnset?.toShortString() ?: "find itself"}")
        if (++index < steps.size) return ask()
        index = -1
        module.info("Setup done, turn ${module.name} on to start")
    }

    private fun ask() = module.info("Right-click ${steps[index].what} (${index + 1}/${steps.size})")

    companion object {
        val container: SafeContext.(HitResult) -> BlockPos? = { hit ->
            hit.blockResult?.blockPos?.takeIf { blockEntity(it) is Inventory }
        }

        inline fun <reified T : Block> block(): SafeContext.(HitResult) -> BlockPos? = { hit ->
            hit.blockResult?.blockPos?.takeIf { blockState(it).block is T }
        }
    }
}
