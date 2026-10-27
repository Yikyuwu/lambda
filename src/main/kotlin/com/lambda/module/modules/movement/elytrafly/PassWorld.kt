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

import net.minecraft.block.BlockState
import net.minecraft.entity.Entity
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.util.shape.VoxelShape

/**
 * Everything ElytraFly's obstacle passer reads of the world, see [ObstaclePassingMode.passWorld]: the live world each
 * tick, later a snapshot of the way ahead the route search can read off the game thread. Blocks, the world border,
 * loaded chunks and the entities in the way, as the planner sees them for you.
 */
interface PassWorld {
	/** The block state at [pos], for the debug log and solid floor. */
	fun blockState(pos: BlockPos): BlockState

	/** Whether the block at [pos] is a whole solid one, like [BlockState.isSolidBlock]. */
	fun isSolidBlock(pos: BlockPos): Boolean

	/** The collision shape at [pos] for you. */
	fun shapeAt(pos: BlockPos): VoxelShape

	/** The collision shape at [pos] for no one in particular, e.g. what a mob lands on. */
	fun bareShape(pos: BlockPos): VoxelShape

	/** The shape in your way at [pos]: its collision shape, a whole block where there's a hazard, see [hazardAt]. */
	fun wayShape(pos: BlockPos): VoxelShape

	/** Whether there's lava, fire, a cobweb or the like at [pos]: kept out of like a block, never stood on. */
	fun hazardAt(pos: BlockPos): Boolean

	/** Whether there's one of [hazardAt] in [box]. */
	fun hazardIn(box: Box): Boolean

	/** Whether the block at [pos] is magma that hurts you standing on it. */
	fun hotFloor(pos: BlockPos): Boolean

	/** Whether there's magma that hurts you under your box at [x], [y], [z]. */
	fun hotFloorUnder(x: Double, y: Double, z: Double): Boolean

	/** Whether no block, entity as solid as one or, with [border], the world border is in [box]. With [hazards], those as blocks. */
	fun spaceEmpty(box: Box, hazards: Boolean = true, border: Boolean = true): Boolean

	/** The boxes of the entities as solid as blocks to you around you, e.g. boats, as [spaceEmpty] counts them. */
	fun solidBoxes(): List<Box>

	/** Like the world's own isSpaceEmpty for [box], for you with [you], else for no one. */
	fun vanillaSpaceEmpty(box: Box, you: Boolean): Boolean

	/** Where a ray from [start] along [direction] first hits a block's way shape within [reach]. A block it [passes] is looked past. */
	fun blockRay(start: Vec3d, direction: Vec3d, reach: Double, passes: ((BlockPos, VoxelShape) -> Boolean)? = null): BlockHitResult?

	/** Where a ray from [start] along [direction] hits a block's collision shape within [reach], like the world's own raycast. */
	fun colliderRay(start: Vec3d, direction: Vec3d, reach: Double): BlockHitResult?

	/** How far along [direction] from [start] the world border is. Null from outside it, or running along it. */
	fun borderAhead(start: Vec3d, direction: Vec3d): Double?

	/** Whether [box] is inside the world border. */
	fun inBorder(box: Box): Boolean

	/** Whether [pos] is inside the world border. */
	fun inBorder(pos: BlockPos): Boolean

	/** Whether the chunk [pos] is in is loaded. */
	fun isLoaded(pos: Vec3d): Boolean

	/** The entities in [box] as solid as blocks to you, and with [mobs] the ones glided over. */
	fun obstacleEntities(box: Box, mobs: Boolean): List<Entity>

	/** The magma cubes and hoglins in [box], glided over like a block. */
	fun glidedOverMobs(box: Box): List<Entity>

	/** Where your gliding box mustn't be past [mob], one in the air landed on what's below it, else at [floorY]. */
	fun mobBox(mob: Entity, floorY: Double): Box
}
