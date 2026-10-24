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

import baritone.api.pathing.goals.GoalXZ
import com.lambda.Lambda.LOG
import com.lambda.context.SafeContext
import com.lambda.core.Loadable
import com.lambda.event.events.MovementEvent
import com.lambda.event.events.WorldEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.event.listener.UnsafeListener.Companion.listenUnsafe
import com.lambda.interaction.managers.rotating.RotationManager
import com.lambda.task.tasks.PlaceContainerTask.Companion.isSafeContainerSpot
import com.lambda.util.BlockUtils.blockState
import com.lambda.util.BlockUtils.hasFluid
import com.lambda.util.BlockUtils.hasSolidGround
import com.lambda.util.BlockUtils.isPassable
import com.lambda.util.CommunicationUtils.warn
import net.minecraft.entity.boss.WitherEntity
import net.minecraft.entity.mob.EndermanEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.entity.mob.PiglinEntity
import net.minecraft.entity.mob.Monster
import net.minecraft.entity.mob.WardenEntity
import net.minecraft.entity.mob.ZombifiedPiglinEntity
import net.minecraft.util.PlayerInput
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec2f
import net.minecraft.util.math.Vec3d

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
    private const val WALK_AHEAD = 16
    private const val MAX_STUCK_WALKS = 5

    private var owner: Any? = null
    /** Why it last couldn't land, written to the log once each time it changes. */
    private var waitReason: String? = null
    /** Who it walks on for without a usable elytra, looking for a spot, see [land]. */
    private var walkingFor: Any? = null
    /** Where it walks, the way it was flying when it started. */
    private var walkDirection = Vec3d.ZERO
    /** Where the last walk started, and how many in a row got nowhere. */
    private var walkStart: Vec3d? = null
    private var stuckWalks = 0

    /** Someone lands or uses what it placed, so flying waits. */
    val pausesFlight get() = owner != null

    override fun load(): String {
        // Stands still meanwhile, unless Baritone walks to a drop.
        listen<MovementEvent.InputUpdate> {
            if (!pausesFlight || BaritoneHandler.isActive) return@listen
            it.input.playerInput = PlayerInput(false, false, false, false, false, it.input.playerInput.sneak, false)
            it.input.movementVector = Vec2f.ZERO
        }
        listenUnsafe<WorldEvent.Leave> {
            owner = null
            walkingFor = null
        }
        return "Loaded Flight Stop"
    }

    /**
     * For [by], lands once it's safe here and nobody else does. True once it stands still and can start.
     * Until then it flies on, looking for a spot, or with [walkOn] and no usable elytra walks on the way it flew.
     */
    fun SafeContext.land(by: Any, mobRadius: Int, walkOn: Boolean = false): Boolean {
        if (owner != null && owner !== by) return false
        val reason = danger(mobRadius) ?: if (!hasSafeSpot()) "no safe spot below" else null
        if (reason != waitReason) {
            waitReason = reason
            if (reason != null) LOG.info("[FlightStop] Not landing yet: $reason")
        }
        if (reason != null) {
            if (owner === by) owner = null
            if (walkOn && !GlideHandler.canGlide() && player.isOnGround) walkOn(by)
            return false
        }
        stopWalking(by)
        owner = by
        return !player.isGliding && player.isOnGround && player.velocity.horizontalLengthSquared() <= STILL_SPEED_SQ
    }

    /** [by] uses what it placed, flight keeps waiting. */
    fun hold(by: Any) {
        owner = by
    }

    fun release(by: Any) {
        if (owner === by) owner = null
        stopWalking(by)
    }

    /** Walks [WALK_AHEAD] blocks at a time the way it was flying, a spot further on may be safe. */
    private fun SafeContext.walkOn(by: Any) {
        if (walkingFor !== by) {
            walkingFor = by
            walkDirection = Vec3d.fromPolar(0f, RotationManager.movementYaw ?: player.yaw)
            stuckWalks = 0
            walkStart = null
            LOG.info("[FlightStop] No usable elytra, walking on to find a spot")
        }
        if (BaritoneHandler.isActive || stuckWalks >= MAX_STUCK_WALKS) return
        // Baritone gives up at once when it finds no way, it isn't asked again and again.
        if (walkStart?.let { it.squaredDistanceTo(player.pos) < 1.0 } == true) {
            if (++stuckWalks >= MAX_STUCK_WALKS) return warn("Can't walk on to find a spot to land, waiting here")
        } else stuckWalks = 0
        walkStart = player.pos
        val goal = player.pos.add(walkDirection.multiply(WALK_AHEAD.toDouble()))
        BaritoneHandler.setGoalAndPath(GoalXZ(goal.x.toInt(), goal.z.toInt()))
    }

    private fun stopWalking(by: Any) {
        if (walkingFor !== by) return
        walkingFor = null
        BaritoneHandler.cancel()
    }

    /**
     * Another player, a warden or a wither in render distance, or a hostile mob within [mobRadius] that would attack or
     * take a drop. Null for none.
     */
    fun SafeContext.danger(mobRadius: Int): String? {
        world.players.firstOrNull { it !== player }?.let { return "${it.name.string} in render distance" }
        world.entities.firstOrNull { (it is WardenEntity || it is WitherEntity) && it.isAlive }?.let { return "${it.name.string} in render distance" }
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
