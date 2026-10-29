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
import net.minecraft.block.Blocks
import net.minecraft.util.function.BooleanBiFunction
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.ChunkSectionPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraft.util.shape.VoxelShape
import net.minecraft.util.shape.VoxelShapes
import net.minecraft.world.BlockView
import net.minecraft.world.border.WorldBorder
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor
import kotlin.math.max

/**
 * What a block is to the planner, see [CorridorWorld]: its [state], its collision shape for you ([shape]) and for no
 * one ([bare]), whether it's a [hazard] or [magma], a whole [solid] block, and what the world's own collision check
 * asks of it at the edge of a box, [exceedsCube] and [movingPiston].
 */
class CorridorCell(
	val state: BlockState, val shape: VoxelShape, val bare: VoxelShape, val hazard: Boolean, val magma: Boolean, val solid: Boolean,
) {
	val exceedsCube = state.exceedsCube()
	val movingPiston = state.isOf(Blocks.MOVING_PISTON)

	companion object {
		/** Outside the corridor: like a chunk not loaded, nothing there, see [CorridorWorld]. */
		val OUTSIDE = CorridorCell(Blocks.VOID_AIR.defaultState, VoxelShapes.empty(), VoxelShapes.empty(), false, false, false)
	}
}

/**
 * The way ahead as the planner reads it, taken on the game thread at once, see [ObstaclePassingMode.captureCorridor]:
 * an immutable [PassWorld] a route search can read apart from the live world, e.g. off the game thread later on.
 *
 * The blocks of a box, [minX], [minY], [minZ] and [sizeX] by [sizeY] by [sizeZ] large, each an index in [cells] into the
 * small [palette] of what they are, see [CorridorCell]. Which chunks were loaded, by [minChunkX], [minChunkZ] and
 * [chunksZ], the world border as it was, whether you had Fire Resistance, and the entities: [entities] in the way or
 * glided over, the boxes of those as solid as blocks to you around you ([solidBoxes], [youBoxes]) and to no one in
 * particular ([collidableBoxes]), where you were for the world border's collision ([flyerX], [flyerZ]).
 *
 * Outside it, it's like a chunk not loaded: void air with no shape, no hazard, not loaded, see [CorridorCell.OUTSIDE],
 * the way the live world reads past what's loaded. Each read there is counted, [outsideReads]. Entities outside it
 * aren't there.
 */
class CorridorWorld(
	val minX: Int, val minY: Int, val minZ: Int, val sizeX: Int, val sizeY: Int, val sizeZ: Int,
	private val cells: ShortArray, val palette: Array<CorridorCell>,
	private val minChunkX: Int, private val minChunkZ: Int, private val chunksX: Int, private val chunksZ: Int, private val loaded: BooleanArray,
	private val border: WorldBorder, private val flyerX: Double, private val flyerZ: Double, private val fireResistant: Boolean,
	private val entities: List<PassEntity>, private val solidBoxes: List<Box>, private val youBoxes: List<Box>, private val collidableBoxes: List<Box>,
) : PassWorld {
	/** How many reads fell outside, for the log: each one read as a chunk not loaded. */
	val outsideReads = AtomicInteger()

	/** About how many bytes it takes, for the log. */
	val bytes get() = cells.size * 2L + loaded.size + palette.size * 64L + (entities.size + solidBoxes.size + youBoxes.size + collidableBoxes.size) * 64L

	private fun cell(x: Int, y: Int, z: Int): CorridorCell {
		val dx = x - minX
		val dy = y - minY
		val dz = z - minZ
		if (dx !in 0..<sizeX || dy !in 0..<sizeY || dz !in 0..<sizeZ) {
			outsideReads.incrementAndGet()
			return CorridorCell.OUTSIDE
		}
		return palette[cells[(dx * sizeZ + dz) * sizeY + dy].toInt()]
	}

	private fun cell(pos: BlockPos) = cell(pos.x, pos.y, pos.z)

	/** The boxes of the shapes in [palette] and of a whole block, by identity, see [boxesOf]. Only read after. */
	private val paletteBoxes = IdentityHashMap<VoxelShape, List<Box>>().apply {
		put(VoxelShapes.fullCube(), VoxelShapes.fullCube().boundingBoxes)
		for (cell in palette) {
			if (cell.shape !in this) put(cell.shape, cell.shape.boundingBoxes)
			if (cell.bare !in this) put(cell.bare, cell.bare.boundingBoxes)
		}
	}

	/** The boxes around the shapes in [palette], like [paletteBoxes]. */
	private val paletteBounds = IdentityHashMap<VoxelShape, Box>().apply {
		for (shape in paletteBoxes.keys) if (!shape.isEmpty) put(shape, shape.boundingBox)
	}

	override fun boxesOf(shape: VoxelShape) = paletteBoxes[shape] ?: shape.boundingBoxes

	override fun boundsOf(shape: VoxelShape) = paletteBounds[shape] ?: shape.boundingBox

	override fun blockState(pos: BlockPos) = cell(pos).state

	override fun isSolidBlock(pos: BlockPos) = cell(pos).solid

	override fun shapeAt(pos: BlockPos) = cell(pos).shape

	override fun bareShape(pos: BlockPos) = cell(pos).bare

	override fun wayShape(pos: BlockPos): VoxelShape = cell(pos).let { if (it.hazard) VoxelShapes.fullCube() else it.shape }

	override fun hazardAt(pos: BlockPos) = cell(pos).hazard

	override fun hotFloor(pos: BlockPos) = cell(pos).magma && !fireResistant

	override fun solidBoxes() = solidBoxes

	/**
	 * Like the world's own isSpaceEmpty: the blocks the way its collision check looks at them, those at the edge of the
	 * box only when they reach past their own block, the entities as solid as blocks, and for [you] the world border
	 * when you're close enough to it to bump it.
	 */
	override fun vanillaSpaceEmpty(box: Box, you: Boolean): Boolean {
		val loX = floor(box.minX - 1e-7).toInt() - 1
		val hiX = floor(box.maxX + 1e-7).toInt() + 1
		val loY = floor(box.minY - 1e-7).toInt() - 1
		val hiY = floor(box.maxY + 1e-7).toInt() + 1
		val loZ = floor(box.minZ - 1e-7).toInt() - 1
		val hiZ = floor(box.maxZ + 1e-7).toInt() + 1
		val boxShape = VoxelShapes.cuboid(box)
		for (x in loX..hiX) for (z in loZ..hiZ) for (y in loY..hiY) {
			val edges = (if (x == loX || x == hiX) 1 else 0) + (if (y == loY || y == hiY) 1 else 0) + (if (z == loZ || z == hiZ) 1 else 0)
			if (edges == 3) continue
			val cell = cell(x, y, z)
			if (edges == 1 && !cell.exceedsCube || edges == 2 && !cell.movingPiston) continue
			val shape = if (you) cell.shape else cell.bare
			if (shape.isEmpty) continue
			if (shape === VoxelShapes.fullCube()) {
				if (box.intersects(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)) return false
			} else if (VoxelShapes.matchesAnywhere(shape.offset(x.toDouble(), y.toDouble(), z.toDouble()), boxShape, BooleanBiFunction.AND)) return false
		}
		if (box.averageSideLength >= 1e-7) {
			val grown = box.expand(1e-7)
			if ((if (you) youBoxes else collidableBoxes).any { it.intersects(grown) }) return false
		}
		if (!you) return true
		// The world border's own collision, see [WorldBorder.canCollide], from where you were.
		val reach = max(MathHelper.absMax(box.lengthX, box.lengthZ), 1.0)
		if (border.getDistanceInsideBorder(flyerX, flyerZ) < reach * 2 && border.contains(flyerX, flyerZ, reach))
			return !VoxelShapes.matchesAnywhere(border.asVoxelShape(), boxShape, BooleanBiFunction.AND)
		return true
	}

	/** Like the world's own raycast against the collision shapes. Which face it hits may differ for a block raycast at another shape. */
	override fun colliderRay(start: Vec3d, direction: Vec3d, reach: Double): BlockHitResult? {
		val end = start.add(direction.multiply(reach))
		return BlockView.raycast<BlockHitResult?, Unit>(start, end, Unit, { _, pos -> shapeAt(pos).raycast(start, end, pos) }, { null })
	}

	override fun worldBorder() = border

	override fun isLoaded(pos: Vec3d): Boolean {
		val cx = ChunkSectionPos.getSectionCoord(floor(pos.x).toInt()) - minChunkX
		val cz = ChunkSectionPos.getSectionCoord(floor(pos.z).toInt()) - minChunkZ
		if (cx !in 0..<chunksX || cz !in 0..<chunksZ) {
			outsideReads.incrementAndGet()
			return false
		}
		return loaded[cx * chunksZ + cz]
	}

	override fun obstacleEntities(box: Box, mobs: Boolean) = entities.filter { it.box.intersects(box) && (it.solid || mobs && it.glidedOver) }

	override fun glidedOverMobs(box: Box) = entities.filter { it.glidedOver && it.box.intersects(box) }

	companion object {
		/** A copy of [border] as it is now, only read after. */
		fun copyOf(border: WorldBorder) = WorldBorder().apply {
			maxRadius = border.maxRadius
			setCenter(border.centerX, border.centerZ)
			setSize(border.size)
		}
	}
}
