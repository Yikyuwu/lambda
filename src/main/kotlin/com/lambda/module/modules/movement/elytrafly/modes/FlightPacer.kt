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

package com.lambda.module.modules.movement.elytrafly.modes

import com.lambda.context.SafeContext
import com.lambda.interaction.managers.rotating.RotationManager
import com.lambda.module.modules.movement.elytrafly.ElytraFly
import com.lambda.util.math.isLoaded
import net.minecraft.entity.Entity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import kotlin.math.max

/**
 * How fast Bounce may fly. While the server lags, with fewer chunks loaded ahead than usual, only so fast that the loaded
 * ones last [SAFE_TICKS] of flight, so the closer the unloaded ones the slower. Else it flies like always.
 * Missing chunks within stopping distance always land it.
 */
class FlightPacer {
    enum class Pace(val description: String) {
        Free("Flying on"),
        /** Limited but well below the safe speed, the bounces flatten out as it nears it. */
        Throttled("Flying on, easing into the safe speed"),
        /** A bit below the safe speed, keeping its height so the bounces don't push it on. */
        Coasting("Coasting, near the safe speed"),
        /** Faster than the safe speed, climbing to slow down. */
        Slowing("Slowing down"),
        /** Missing chunks within stopping distance, landed until enough more are loaded. */
        Waiting("Waiting for the chunks"),
    }

    var pace = Pace.Free
        private set
    /** How much of the bounce pitch to use while [Pace.Throttled], from 0 for level to 1 for all of it. */
    private var throttle = 1.0
    /** How far ahead the chunks are usually loaded, forgotten slowly. */
    private var usualLoaded = 0.0
    private var lagging = false
    /** How much longer it counts as lagging, see [LAG_HOLD_TICKS]. */
    private var lagTicks = 0
    private var landedTicks = 0
    /** A block within [LOW_ROOF_HEIGHT] above the floor, e.g. a tunnel or the nether's bedrock roof. */
    var lowRoof = false
        private set

    fun reset() {
        pace = Pace.Free
        usualLoaded = 0.0
        lagging = false
        lagTicks = 0
    }

    /** Decides the [pace] for this tick. [lookAhead] is how far chunks are looked for, null to not, [floorY] what you bounce on. */
    fun SafeContext.update(lookAhead: Int?, floorY: Double) {
        val ahead = Vec3d.fromPolar(0f, RotationManager.movementYaw ?: player.yaw)
        val missing = lookAhead?.let { range ->
            (CHECK_STEP..range step CHECK_STEP).firstOrNull { !player.pos.add(ahead.multiply(it.toDouble())).isLoaded }?.toDouble()
        }
        val speed = player.velocity.dotProduct(ahead).coerceAtLeast(0.0)

        // How far the chunks usually reach ahead, the server's view distance or the look-ahead. It lags until nearly as
        // many are loaded again for a while, else it flips between slowing down and flying on at the margin. Flying
        // faster than the server sends chunks, the distance loaded jumps by a chunk at each chunk border.
        val loaded = missing ?: ((lookAhead ?: 0) + CHECK_STEP).toDouble()
        usualLoaded = max(loaded, usualLoaded - USUAL_LOADED_DECAY)
        if (lookAhead != null && loaded < usualLoaded - if (lagging) LAG_END_MARGIN else LAG_MARGIN) lagTicks = LAG_HOLD_TICKS
        else if (lagTicks > 0) lagTicks--
        lagging = lagTicks > 0
        lowRoof = hasLowRoof(floorY)
        val safeSpeed = if (lagging) ((loaded - MIN_STOP_DISTANCE) / SAFE_TICKS).coerceAtLeast(0.0) else Double.MAX_VALUE
        val stopDistance = max(MIN_STOP_DISTANCE, speed * STOP_TICKS)

        // Each holds a bit longer than it starts, else it flips back and forth.
        if (pace != Pace.Waiting || !player.isOnGround) landedTicks = 0
        val next = when {
            // Down from a wait, the server ends the glide a moment later. Taking off again before that gets you set back.
            pace == Pace.Waiting && player.isOnGround && player.getFlag(Entity.GLIDING_FLAG_INDEX) && ++landedTicks < MAX_LANDED_TICKS -> Pace.Waiting
            missing != null && missing <= stopDistance + if (pace == Pace.Waiting) RESUME_MARGIN else 0.0 -> Pace.Waiting
            // Never slower than a glide holds, almost stalled the server sets you back.
            speed < MIN_SPEED -> if (lagging) Pace.Throttled else Pace.Free
            speed > safeSpeed * if (pace == Pace.Slowing) SLOW_UNTIL else 1.0 -> Pace.Slowing
            speed > safeSpeed * if (pace == Pace.Coasting) FLY_BELOW else COAST_ABOVE -> Pace.Coasting
            lagging -> Pace.Throttled
            else -> Pace.Free
        }
        // Nearing the safe speed, the bounces flatten out so it eases into it, not past it.
        throttle = (1 - speed / (safeSpeed * COAST_ABOVE)).coerceIn(0.0, 1.0)
        if (next != pace) ElytraFly.debug {
            "${next.description}: ${missing?.let { "%.0f".format(it) } ?: "all loaded"} ahead, usually %.0f, at %.2f a tick, safe %s".format(
                usualLoaded, speed, if (lagging) "%.2f%s".format(safeSpeed, if (lowRoof) " under a low roof" else "") else "any",
            )
        }
        pace = next
    }

    /**
     * [bounce] flattened towards [level] while [Pace.Throttled]. Looking down less you glide longer and bounce less
     * often, and each bounce, a sprint jump, pushes you on.
     */
    fun throttled(bounce: Double, level: Double) = if (pace == Pace.Throttled) level + (bounce - level) * throttle else bounce

    private fun SafeContext.hasLowRoof(floorY: Double): Boolean {
        val floor = BlockPos.ofFloored(player.x, floorY, player.z)
        return (1..LOW_ROOF_HEIGHT).any { !world.getBlockState(floor.up(it)).getCollisionShape(world, floor.up(it)).isEmpty }
    }

    private companion object {
        const val CHECK_STEP = 4
        /** Flight time the loaded chunks ahead should last while lagging, which sets the safe speed. */
        const val SAFE_TICKS = 60
        /** Flight time loaded ahead below which it lands, about how long landing and stopping takes. */
        const val STOP_TICKS = 30
        const val MIN_STOP_DISTANCE = 12.0
        /** How much more has to be loaded to fly on after waiting. */
        const val RESUME_MARGIN = 16.0
        /** Most ticks to wait for the server to end the glide after landing from a wait. */
        const val MAX_LANDED_TICKS = 10
        /** How much less than usually loaded ahead counts as lagging, and how much less still does once it does. */
        const val LAG_MARGIN = 16.0
        const val LAG_END_MARGIN = 8.0
        /** How long the chunks have to keep up before it flies on, each switch turns it around and risks a setback. */
        const val LAG_HOLD_TICKS = 40
        /** How fast the usual distance is forgotten, half a block a second, so a turn doesn't count as lag for long. */
        const val USUAL_LOADED_DECAY = 0.025
        const val LOW_ROOF_HEIGHT = 4
        /** Slowest it slows down to, below that a glide almost stalls. */
        const val MIN_SPEED = 0.5
        /** Slowing down until this share of the safe speed. */
        const val SLOW_UNTIL = 0.9
        /** Coasting from this share of the safe speed, and flying on again below the other. */
        const val COAST_ABOVE = 0.8
        const val FLY_BELOW = 0.7
    }
}
