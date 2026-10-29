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
import net.minecraft.registry.Registries
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.util.shape.VoxelShape
import net.minecraft.util.shape.VoxelShapes
import net.minecraft.world.BlockView
import net.minecraft.world.border.WorldBorder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

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
	fun wayShape(pos: BlockPos): VoxelShape = if (hazardAt(pos)) VoxelShapes.fullCube() else shapeAt(pos)

	/** Whether there's lava, fire, a cobweb or the like at [pos]: kept out of like a block, never stood on. */
	fun hazardAt(pos: BlockPos): Boolean

	/** Whether there's one of [hazardAt] in [box]. */
	fun hazardIn(box: Box): Boolean {
		val pos = BlockPos.Mutable()
		for (x in floor(box.minX).toInt()..floor(box.maxX - 1e-7).toInt())
			for (z in floor(box.minZ).toInt()..floor(box.maxZ - 1e-7).toInt())
				for (y in floor(box.minY).toInt()..floor(box.maxY - 1e-7).toInt())
					if (hazardAt(pos.set(x, y, z))) return true
		return false
	}

	/** Whether the block at [pos] is magma that hurts you standing on it. */
	fun hotFloor(pos: BlockPos): Boolean

	/**
	 * Whether there's magma under your box at [y], see [hotFloor]: each bounce on it touches it a tick, it hurts more
	 * often than that from a bounce to the next.
	 */
	fun hotFloorUnder(x: Double, y: Double, z: Double): Boolean {
		val below = floor(y - 0.05).toInt()
		val pos = BlockPos.Mutable()
		for (bx in floor(x - 0.29).toInt()..floor(x + 0.29).toInt())
			for (bz in floor(z - 0.29).toInt()..floor(z + 0.29).toInt())
				if (hotFloor(pos.set(bx, below, bz))) return true
		return false
	}

	/**
	 * Whether no block, entity as solid as one or, with [border], the world border is in [box], like the world's own
	 * isSpaceEmpty for you. With [hazards], those as blocks. From one below, a fence reaches up into [box].
	 */
	fun spaceEmpty(box: Box, hazards: Boolean = true, border: Boolean = true): Boolean {
		if (border && !inBorder(box) || solidBoxes().any { it.intersects(box) }) return false
		val pos = BlockPos.Mutable()
		for (x in floor(box.minX).toInt()..floor(box.maxX).toInt())
			for (z in floor(box.minZ).toInt()..floor(box.maxZ).toInt())
				for (y in floor(box.minY).toInt() - 1..floor(box.maxY).toInt()) {
					val shape = if (hazards) wayShape(pos.set(x, y, z)) else shapeAt(pos.set(x, y, z))
					if (shape.isEmpty) continue
					val hit = if (shape === VoxelShapes.fullCube()) box.intersects(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)
						// Each part as offset to the block and intersected, without a box made for it.
						else boxesOf(shape).any { p ->
							p.minX + x < box.maxX && p.maxX + x > box.minX && p.minY + y < box.maxY && p.maxY + y > box.minY &&
								p.minZ + z < box.maxZ && p.maxZ + z > box.minZ
						}
					if (hit) return false
				}
		return true
	}

	/** The boxes [shape] is made of, its [VoxelShape.getBoundingBoxes], kept for the shapes read often. */
	fun boxesOf(shape: VoxelShape): List<Box> = shape.boundingBoxes

	/** The box around all of [shape], its [VoxelShape.getBoundingBox], kept like [boxesOf]. */
	fun boundsOf(shape: VoxelShape): Box = shape.boundingBox

	/** The boxes of the entities as solid as blocks to you around you, e.g. boats, as [spaceEmpty] counts them. */
	fun solidBoxes(): List<Box>

	/** Like the world's own isSpaceEmpty for [box], for you with [you], else for no one. */
	fun vanillaSpaceEmpty(box: Box, you: Boolean): Boolean

	/**
	 * Where a ray from [start] along [direction] first hits a block's [wayShape] within [reach], like the world's own
	 * raycast. A block it [passes] is looked past. One narrower than a whole block across the way, e.g. a post, [POST_MARGIN]
	 * wider to either side across it: the rays are twice that apart, a post that thin would fit between two of them.
	 */
	fun blockRay(start: Vec3d, direction: Vec3d, reach: Double, passes: ((BlockPos, VoxelShape) -> Boolean)? = null): BlockHitResult? {
		val end = start.add(direction.multiply(reach))
		val across = across(direction)
		val wideX = abs(across.x) * POST_MARGIN
		val wideZ = abs(across.z) * POST_MARGIN
		return BlockView.raycast<BlockHitResult?, Unit>(start, end, Unit, { _, pos ->
			val shape = wayShape(pos)
			// Looked past, the ray goes on to the next block.
			if (passes != null && !shape.isEmpty && passes(pos, shape)) return@raycast null
			if (shape.isEmpty || shape === VoxelShapes.fullCube()) return@raycast shape.raycast(start, end, pos)
			val bounds = boundsOf(shape)
			val growX = if (bounds.lengthX < 1 - 1e-6) wideX else 0.0
			val growZ = if (bounds.lengthZ < 1 - 1e-6) wideZ else 0.0
			if (growX < 1e-6 && growZ < 1e-6) shape.raycast(start, end, pos)
			else bounds.expand(growX, 0.0, growZ).offset(pos).raycast(start, end).orElse(null)
				?.let { BlockHitResult(it, Direction.getFacing(direction.x, direction.y, direction.z).opposite, pos.toImmutable(), false) }
		}, { null })
	}

	/** Where a ray from [start] along [direction] hits a block's collision shape within [reach], like the world's own raycast. */
	fun colliderRay(start: Vec3d, direction: Vec3d, reach: Double): BlockHitResult?

	/** The world border, only read. */
	fun worldBorder(): WorldBorder

	/**
	 * How far along [direction] from [start] the world border is, a wall to you. Null from outside it, or running along
	 * it: there it's [spaceEmpty] that keeps you in.
	 */
	fun borderAhead(start: Vec3d, direction: Vec3d): Double? {
		val border = worldBorder()
		if (!border.contains(start.x, start.z)) return null
		val dir = direction.normalize()
		val toX = when {
			dir.x > 1e-6 -> (border.boundEast - start.x) / dir.x
			dir.x < -1e-6 -> (border.boundWest - start.x) / dir.x
			else -> Double.POSITIVE_INFINITY
		}
		val toZ = when {
			dir.z > 1e-6 -> (border.boundSouth - start.z) / dir.z
			dir.z < -1e-6 -> (border.boundNorth - start.z) / dir.z
			else -> Double.POSITIVE_INFINITY
		}
		return min(toX, toZ).takeIf { it.isFinite() }
	}

	/** Whether [box] is inside the world border. */
	fun inBorder(box: Box) = worldBorder().contains(box)

	/** Whether [pos] is inside the world border. */
	fun inBorder(pos: BlockPos) = worldBorder().contains(pos)

	/** Whether the chunk [pos] is in is loaded. */
	fun isLoaded(pos: Vec3d): Boolean

	/** The entities in [box] as solid as blocks to you, and with [mobs] the ones glided over. */
	fun obstacleEntities(box: Box, mobs: Boolean): List<PassEntity>

	/** The magma cubes and hoglins in [box], glided over like a block. */
	fun glidedOverMobs(box: Box): List<PassEntity>
}

/** The block's id without its namespace, e.g. for the debug log. */
internal fun BlockState.idPath(): String = Registries.BLOCK.getId(block).path

/**
 * How far wider across the way a block narrower than a whole one is to [PassWorld.blockRay], e.g. a glass pane, iron
 * bars, a chain or bamboo standing alone: half the rays' spacing across the way, a post that thin fits between two.
 */
internal const val POST_MARGIN = 0.125

/**
 * An entity in the way as the planner sees it, see [PassWorld.obstacleEntities]: its [box], as solid as a block to you
 * ([solid]) or a mob [glidedOver], down on the ground or, in the air, landing on a block's top at [landsOn] below it,
 * null with none that near. Named for the log by [label], put together only once read.
 */
class PassEntity(val box: Box, val solid: Boolean, val glidedOver: Boolean, val onGround: Boolean, val landsOn: Double?, label: () -> String) {
	val label by lazy(LazyThreadSafetyMode.PUBLICATION, label)
}
