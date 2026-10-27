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

package com.lambda.module.modules.movement.elytrafly

import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d

/**
 * You as ElytraFly's obstacle passer plans from, see [ObstaclePassingMode.flyer]: the live player each tick, later a
 * snapshot of it the route search can read off the game thread. Only read, flying acts on the real player.
 */
interface Flyer {
	/** Where your feet are. */
	val pos: Vec3d
	val x: Double
	val y: Double
	val z: Double
	val blockPos: BlockPos
	val velocity: Vec3d
	/** How fast you go across the ground, a tick's way. */
	val horizontalSpeed: Double
	/** How much gravity pulls you down a tick. */
	val finalGravity: Double
	val isOnGround: Boolean
	val isGliding: Boolean
}

/** The live player as [Flyer], read as it is each time. */
class LiveFlyer : Flyer {
	/** Whose state is read, set each time it's looked up. Let go of in [drop], it holds on to the world. */
	private var player: ClientPlayerEntity? = null
	private val you get() = player!!

	fun of(player: ClientPlayerEntity) = also { this.player = player }

	fun drop() {
		player = null
	}

	override val pos: Vec3d get() = you.pos
	override val x get() = you.x
	override val y get() = you.y
	override val z get() = you.z
	override val blockPos: BlockPos get() = you.blockPos
	override val velocity: Vec3d get() = you.velocity
	override val horizontalSpeed get() = you.velocity.horizontalLength()
	override val finalGravity get() = you.finalGravity
	override val isOnGround get() = you.isOnGround
	override val isGliding get() = you.isGliding
}
