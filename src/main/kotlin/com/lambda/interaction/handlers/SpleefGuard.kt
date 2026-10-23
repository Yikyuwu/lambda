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

package com.lambda.interaction.handlers

import com.lambda.config.blocks.BuildConfig
import com.lambda.context.SafeContext
import com.lambda.core.Loadable
import com.lambda.event.events.MovementEvent
import com.lambda.event.events.TickEvent
import com.lambda.event.events.WorldEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.event.listener.UnsafeListener.Companion.listenUnsafe
import com.lambda.task.RootTask
import com.lambda.task.tasks.OpenContainerTask
import com.lambda.util.BlockUtils.isPassable
import com.lambda.util.CommunicationUtils.warn
import com.lambda.util.player.MovementUtils.jumping
import com.lambda.util.player.MovementUtils.sneaking
import net.minecraft.entity.Entity
import net.minecraft.entity.Flutterer
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.mob.BlazeEntity
import net.minecraft.entity.mob.EndermanEntity
import net.minecraft.entity.mob.SlimeEntity
import net.minecraft.entity.passive.BatEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps spleefing from hurting the player: decides which entities can be spleefed, and after a spleef or near
 * an open spleef hole keeps the player crouched, on the ledge and from jumping while a task runs.
 * Also remembers entities in the way that can't be spleefed, see [recentBlockers].
 */
@Suppress("unused")
object SpleefGuard : Loadable {
    private const val HOLE_RANGE = 6.0
    private const val FORGET_RANGE = 16.0
    private const val SIZE_EPSILON = 1e-3
    private const val BLOCKER_MEMORY_MS = 1000L

    /** Spleefed positions and until when they're guarded. Written from simulation threads. */
    private val holes = ConcurrentHashMap<BlockPos, Long>()
    @Volatile private var guardUntil = 0L
    /** Entities in the way that can't be spleefed, and when they were last reported. Written from simulation threads. */
    private val blockers = ConcurrentHashMap<Entity, Long>()
    private val warnedBlockers = mutableSetOf<Entity>()
    private var guarding = false

    override fun load(): String {
        listen<TickEvent.Pre> {
            val now = System.currentTimeMillis()
            if (!RootTask.isBusy) {
                holes.clear()
                guardUntil = 0L
            }
            var holeNearby = false
            holes.entries.removeIf { (pos, until) ->
                // One the player is in can't be fallen into any more, and guarding it would keep them from climbing out.
                if (!isNear(pos, FORGET_RANGE) || player.boundingBox.intersects(Box(pos))) return@removeIf true
                val exposed = isExposed(pos)
                if (exposed && isNear(pos, HOLE_RANGE)) holeNearby = true
                !exposed && now > until
            }
            // In water or lava crouching would keep the player from swimming up.
            guarding = (now < guardUntil || holeNearby) && !player.isTouchingWater && !player.isInLava

            blockers.entries.removeIf { (entity, time) -> !entity.isAlive || now - time > BLOCKER_MEMORY_MS }
            // Warned once while it's around, not again every time it blocks after a pause.
            warnedBlockers.removeIf { !it.isAlive || it.world !== world || !isNear(it.blockPos, FORGET_RANGE) }
            blockers.keys.forEach {
                if (warnedBlockers.add(it)) warn("Can't spleef ${it.name.string}. Waiting until it's out of the way")
            }
        }

        listenUnsafe<WorldEvent.Leave> {
            holes.clear()
            guardUntil = 0L
            blockers.clear()
            warnedBlockers.clear()
        }

        listen<MovementEvent.ClipAtLedge> {
            if (guarding) it.clip = true
        }

        // The ledge clip only holds on the ground.
        listen<MovementEvent.InputUpdate> {
            if (!guarding) return@listen
            if (!OpenContainerTask.isOpening) it.input.sneaking = true
            it.input.jumping = false
        }

        listen<MovementEvent.Jump> {
            if (guarding) it.cancel()
        }

        return "Loaded Spleef Guard"
    }

    /** A mob small enough to fall through a hole that can't hop, fly or climb back out. Endermen would need a hole 4 deep. */
    fun BuildConfig.canSpleef(entity: Entity) =
        entity is LivingEntity && entity !is PlayerEntity && entity !is SlimeEntity && entity !is BlazeEntity &&
            entity !is EndermanEntity && entity !is Flutterer && entity !is BatEntity && !entity.hasNoGravity() &&
            entity.width <= spleefMaxWidth + SIZE_EPSILON && entity.height <= spleefMaxHeight + SIZE_EPSILON

    /** Whether breaking [pos] would dig out the ground under the player or right next to their feet. */
    fun SafeContext.isUnderPlayer(pos: BlockPos): Boolean {
        val box = player.boundingBox.expand(0.3, 0.0, 0.3)
        return pos.y <= player.blockPos.y && box.intersects(Box(pos).withMinY(box.minY).withMaxY(box.maxY))
    }

    fun reportUnspleefable(entity: Entity) {
        blockers[entity] = System.currentTimeMillis()
    }

    /** Entities that can't be spleefed and blocked a placement within the last second. */
    val recentBlockers: Set<Entity> get() = blockers.keys

    fun BuildConfig.onSpleef(positions: Collection<BlockPos>) {
        guardUntil = System.currentTimeMillis() + spleefEdgeGuard * 50L
        positions.forEach { holes[it] = guardUntil }
    }

    /** Open up to the player's feet, so not covered by a floor placed again above it. */
    private fun SafeContext.isExposed(pos: BlockPos) =
        (pos.y..maxOf(pos.y, player.blockPos.y - 1)).all { isPassable(BlockPos(pos.x, it, pos.z)) }

    private fun SafeContext.isNear(pos: BlockPos, range: Double) = pos.getSquaredDistance(player.pos) <= range * range
}
