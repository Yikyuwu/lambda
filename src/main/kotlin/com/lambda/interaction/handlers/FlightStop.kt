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

import com.lambda.Lambda.LOG
import com.lambda.context.SafeContext
import com.lambda.core.Loadable
import com.lambda.event.events.MovementEvent
import com.lambda.event.events.WorldEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.event.listener.UnsafeListener.Companion.listenUnsafe
import com.lambda.task.tasks.PlaceContainerTask.Companion.isSafeContainerSpot
import com.lambda.util.BlockUtils.blockState
import com.lambda.util.BlockUtils.hasFluid
import com.lambda.util.BlockUtils.hasSolidGround
import com.lambda.util.BlockUtils.isPassable
import net.minecraft.entity.mob.EndermanEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.entity.mob.PiglinEntity
import net.minecraft.entity.mob.Monster
import net.minecraft.entity.mob.ZombifiedPiglinEntity
import net.minecraft.util.PlayerInput
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec2f

/**
 * Lands while flying to place a shulker or the ender chest, e.g. to mend or restock, for one owner at a time.
 * Without the aura there's no fighting, so only with no player in render distance, no hostile mob close and a safe spot
 * for the container below. Flight waits and the player stands still while an owner lands or uses what it placed.
 */
@Suppress("unused")
object FlightStop : Loadable {
    private const val STILL_SPEED_SQ = 1e-4
    /** How far below the ground to land on may be, a bounce doesn't go higher. */
    private const val MAX_LANDING_DROP = 4
    /** How far from where you land a spot for the container is looked for, like placing it does. */
    private const val SPOT_RANGE = 3

    private var owner: Any? = null
    /** Why it last couldn't land, written to the log once each time it changes. */
    private var waitReason: String? = null

    /** Someone lands or uses what it placed, so flying waits. */
    val pausesFlight get() = owner != null

    override fun load(): String {
        // Stands still meanwhile, unless Baritone walks to a drop.
        listen<MovementEvent.InputUpdate> {
            if (!pausesFlight || BaritoneHandler.isActive) return@listen
            it.input.playerInput = PlayerInput(false, false, false, false, false, it.input.playerInput.sneak, false)
            it.input.movementVector = Vec2f.ZERO
        }
        listenUnsafe<WorldEvent.Leave> { owner = null }
        return "Loaded Flight Stop"
    }

    /**
     * For [by], lands once it's safe here and nobody else does. True once it stands still and can start.
     * Until then it flies on, looking for a spot.
     */
    fun SafeContext.land(by: Any, mobRadius: Int): Boolean {
        if (owner != null && owner !== by) return false
        val reason = danger(mobRadius) ?: if (!hasSafeSpot()) "no safe spot below" else null
        if (reason != waitReason) {
            waitReason = reason
            if (reason != null) LOG.info("[FlightStop] Not landing yet: $reason")
        }
        if (reason != null) {
            release(by)
            return false
        }
        owner = by
        return !player.isGliding && player.isOnGround && player.velocity.horizontalLengthSquared() <= STILL_SPEED_SQ
    }

    /** [by] uses what it placed, flight keeps waiting. */
    fun hold(by: Any) {
        owner = by
    }

    fun release(by: Any) {
        if (owner === by) owner = null
    }

    /** Another player in render distance, or a hostile mob within [mobRadius] that would attack or take a drop. */
    fun SafeContext.hasDanger(mobRadius: Int) = danger(mobRadius) != null

    /** What [hasDanger] found, null for nothing. */
    private fun SafeContext.danger(mobRadius: Int): String? {
        world.players.firstOrNull { it !== player }?.let { return "${it.name.string} in render distance" }
        val mob = world.getEntitiesByClass(MobEntity::class.java, player.boundingBox.expand(mobRadius.toDouble())) { it.isAlive && it.isHostile }
            .minByOrNull { it.squaredDistanceTo(player) } ?: return null
        return "${mob.name.string} within $mobRadius blocks"
    }

    /**
     * Zombified piglins and endermen leave you alone until angered. Piglins only go for you once they notice you, idling
     * around the highway they'd keep it from landing for good.
     */
    private val MobEntity.isHostile get() = this is Monster && when (this) {
        is ZombifiedPiglinEntity, is PiglinEntity -> isAttacking
        is EndermanEntity -> isAngry
        else -> true
    }

    /** Ground below to land on and a spot next to it a container can go, without a hole, fire or lava, like a restock picks. */
    private fun SafeContext.hasSafeSpot(): Boolean {
        val feet = (0..MAX_LANDING_DROP).map { player.blockPos.down(it) }.firstOrNull { !isPassable(it.down()) } ?: return false
        if (!hasSolidGround(feet) || blockState(feet).hasFluid) return false
        return BlockPos.iterateOutwards(feet, SPOT_RANGE, 0, SPOT_RANGE).any { isPassable(it) && isSafeContainerSpot(it) }
    }
}
