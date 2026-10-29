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

import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The obstacle passer's geometry and glide physics, touching no state, see [ObstaclePassingMode].

/**
 * A lane across the way, the line itself exactly [Vec3d.ZERO]: along an axis the way's direction isn't exact, its
 * lane 0 would come out -0.0 or a hair off, not equal to it, e.g. never back on the line after a dodge.
 */
internal fun laneVec(lane: Vec3d) = if (lane.horizontalLengthSquared() < 1e-9) Vec3d.ZERO else lane

/** Across the line at [along] it on [path] (along and across it a tick apart), the last spot past its end. */
internal fun pathOffset(path: List<Pair<Double, Double>>, along: Double): Double {
	if (along <= path.first().first) return path.first().second
	for (i in 1 until path.size) {
		val (a0, o0) = path[i - 1]
		val (a1, o1) = path[i]
		if (along <= a1) return if (a1 > a0) o0 + (o1 - o0) * (along - a0) / (a1 - a0) else o1
	}
	return path.last().second
}

/** Across the way [dir] goes, to the right, a block long. */
internal fun across(dir: Vec3d): Vec3d = Vec3d(-dir.z, 0.0, dir.x).normalize()

/** Whether bouncing on [level] is up on a layer on [base], see [onLayer]. */
internal fun layerAbove(level: Double, base: Double) = level > base + 0.01
/** Whether that layer is one of carpets, see [onCarpets]. */
internal fun carpetsAbove(level: Double, base: Double) = layerAbove(level, base) && level - base < CARPET_HEIGHT + 0.01

/** The gliding box at [x], [z] from [bottom] to [top]. */
internal fun glideBox(x: Double, z: Double, bottom: Double, top: Double) =
	Box(x - GLIDE_SIZE / 2, bottom, z - GLIDE_SIZE / 2, x + GLIDE_SIZE / 2, top, z + GLIDE_SIZE / 2)

/** The gliding box at [at] from [bottom] to [top], see [glideBox]. */
internal fun glideBox(at: Vec3d, bottom: Double, top: Double) = glideBox(at.x, at.z, bottom, top)

/** The spots a scan looks at along the line, [HOP_SCAN_STEP] apart from [start] on. */
internal fun scanFrom(start: Double = HOP_SCAN_STEP) = generateSequence(start) { it + HOP_SCAN_STEP }

/** Along [dir] level, a block long. */
internal fun forward(dir: Vec3d): Vec3d = dir.horizontal.normalize()

/** Why a lane was turned down, put together only once read for the debug log. */
internal fun missText(text: () -> String) = text

/** The yaw heading along [v]. */
internal fun yawOf(v: Vec3d) = Math.toDegrees(atan2(-v.x, v.z))

/** How far across the line the tilt drifts you over [distance]. */
internal fun leanDrift(distance: Double) = distance * Math.toRadians(LEAN_DEGREES)

/** The yaw along the line tilted [degrees] to [lean] across it. */
internal fun leanYaw(dir: Vec3d, lean: Int, degrees: Double = LEAN_DEGREES): Double {
	val across = across(dir)
	val heading = dir.normalize().add(across.multiply(lean * Math.tan(Math.toRadians(degrees))))
	return yawOf(heading)
}

/**
 * How far apart the lanes are across [dir]. A block on the way spans the width of two lanes diagonally, so they're a
 * block apart straight along an axis, but 0.71 diagonally, a block over on either axis.
 */
internal fun laneStep(dir: Vec3d) = dir.horizontalLength() / (abs(dir.x) + abs(dir.z))

/** How far across [direction] the player's box reaches from its middle, farther diagonally as its corners stick out. */
internal fun halfWidth(direction: Vec3d) = 0.3 * (abs(direction.x) + abs(direction.z)) / direction.horizontalLength() - WIDTH_MARGIN

/** The heading along [d] tilted [THREAD_TILT] to [lean] across it, [across] it to the right. */
internal fun tilted(d: Vec3d, across: Vec3d, lean: Int) = d.add(across.multiply(lean * TAN_THREAD_TILT))

/** How far across a block along the heading tilted [THREAD_TILT] and [THREAD_MAX_TILT] goes, see [tilted] and [swingSide]. */
private val TAN_THREAD_TILT = Math.tan(Math.toRadians(THREAD_TILT))
private val TAN_THREAD_MAX_TILT = Math.tan(Math.toRadians(THREAD_MAX_TILT))

/**
 * The side across [dir] the box swings out to with the heading tilted [lean] across it, 1 to the right, -1 to the left,
 * 0 to either. Each tick's move goes along the axis the heading goes farther along first, see [swingBlocked], and that
 * leg takes the box off the line to one side. Diagonally the tilt picks the axis, so it's the side tilted to; off the
 * diagonal the line's own larger axis goes first whichever way it's tilted, and the box swings out to that side, maybe
 * the one tilted away from. Either when tilted as little as [THREAD_TILT] and as much as [THREAD_MAX_TILT] don't agree
 * on it. Along an axis it doesn't swing out, [lean] as it is.
 */
internal fun swingSide(dir: Vec3d, lean: Int): Int {
	val d = forward(dir)
	if (lean == 0 || abs(d.x * d.z) < 1e-3) return lean
	val across = across(d)
	// The first leg's move across the way: along x it's d.x · across.x = -d.x · d.z, along z d.z · across.z, the other way.
	val xSide = if (-d.x * d.z > 0) 1 else -1
	fun side(tan: Double): Int {
		val x = d.x + across.x * (lean * tan)
		val z = d.z + across.z * (lean * tan)
		return if (abs(x) >= abs(z)) xSide else -xSide
	}
	val least = side(TAN_THREAD_TILT)
	return if (side(TAN_THREAD_MAX_TILT) == least) least else 0
}

/**
 * The spots across the way, offsets from the line along [dir] through [origin], that gliding along it at [speed] from
 * anywhere between [from] and [to] along it a tick's move runs into one of [boxes] from, with the first move along x
 * when [xFirst], see [passage]. Each an interval, a block, grown by half the box, is in the way of the spots across
 * where one of the move's parallelograms meets it, found by the separating axes x, z and across the way.
 */
internal fun swingBlocked(origin: Vec3d, dir: Vec3d, from: Double, to: Double, speed: Double, boxes: List<Box>, xFirst: Boolean): List<Pair<Double, Double>> {
	val d = forward(dir)
	val across = across(dir)
	val half = GLIDE_SIZE / 2
	val moveX = d.x * speed
	val moveZ = d.z * speed
	// Where the way starts and ends right on the line.
	val ax = origin.x + d.x * from
	val az = origin.z + d.z * from
	val bx = origin.x + d.x * to
	val bz = origin.z + d.z * to
	// The separating axes x, z and across the way, and how far a step across moves along each.
	val nx = doubleArrayOf(1.0, 0.0, across.x)
	val nz = doubleArrayOf(0.0, 1.0, across.z)
	val k = DoubleArray(3) { across.x * nx[it] + across.z * nz[it] }
	val out = ArrayList<Pair<Double, Double>>()
	// Each box's corners grown by half the box, filled anew for each, see below.
	val qx = DoubleArray(4)
	val qz = DoubleArray(4)
	var shiftX = 0.0
	var shiftZ = 0.0
	for (leg in 0..1) {
		val alongX = (leg == 0) == xFirst
		val legX = if (alongX) moveX else 0.0
		val legZ = if (alongX) 0.0 else moveZ
		val px = doubleArrayOf(ax + shiftX, bx + shiftX, ax + shiftX + legX, bx + shiftX + legX)
		val pz = doubleArrayOf(az + shiftZ, bz + shiftZ, az + shiftZ + legZ, bz + shiftZ + legZ)
		shiftX += legX
		shiftZ += legZ
		// The leg's parallelogram on each axis, once for all the boxes.
		val pMin = DoubleArray(3)
		val pMax = DoubleArray(3)
		for (axis in 0..2) {
			var lo = Double.POSITIVE_INFINITY
			var hi = Double.NEGATIVE_INFINITY
			for (i in 0..3) {
				val p = px[i] * nx[axis] + pz[i] * nz[axis]
				lo = min(lo, p)
				hi = max(hi, p)
			}
			pMin[axis] = lo
			pMax[axis] = hi
		}
		for (box in boxes) {
			qx[0] = box.minX - half; qx[1] = box.maxX + half; qx[2] = box.minX - half; qx[3] = box.maxX + half
			qz[0] = box.minZ - half; qz[1] = box.minZ - half; qz[2] = box.maxZ + half; qz[3] = box.maxZ + half
			var low = Double.NEGATIVE_INFINITY
			var high = Double.POSITIVE_INFINITY
			for (axis in 0..2) {
				var qMin = Double.POSITIVE_INFINITY
				var qMax = Double.NEGATIVE_INFINITY
				for (i in 0..3) {
					val q = qx[i] * nx[axis] + qz[i] * nz[axis]
					qMin = min(qMin, q)
					qMax = max(qMax, q)
				}
				// Only touching it isn't hitting it, like the game's collisions.
				if (abs(k[axis]) < 1e-9) {
					if (pMin[axis] >= qMax - 1e-7 || pMax[axis] <= qMin + 1e-7) high = low
				} else {
					val first = (qMax - 1e-7 - pMin[axis]) / k[axis]
					val second = (qMin + 1e-7 - pMax[axis]) / k[axis]
					low = max(low, min(first, second))
					high = min(high, max(first, second))
				}
				if (low >= high) break
			}
			if (low < high) out += low to high
		}
	}
	return out
}

/**
 * Whether gliding along [dir] at [speed] from anywhere between [from] and [to] along the line through [origin], at
 * any spot from [low] to [high] across it, no tick's move runs into one of [boxes]. With the heading tilted [lean]
 * across the way which axis goes first is known, else it may be either, see [passage].
 */
internal fun swingClear(origin: Vec3d, dir: Vec3d, from: Double, to: Double, speed: Double, boxes: List<Box>, low: Double, high: Double, lean: Int = 0): Boolean {
	val d = forward(dir)
	val heading = tilted(d, across(dir), lean)
	val orders = if (abs(d.x * d.z) < 1e-3) listOf(abs(d.x) >= abs(d.z)) else if (lean == 0) listOf(true, false)
		else listOf(abs(heading.x) >= abs(heading.z))
	return orders.all { xFirst -> swingBlocked(origin, dir, from, to, speed, boxes, xFirst).none { (l, h) -> l < high && h > low } }
}

/** How far out from the way [passage] looks at [speed], up to [window] to either side of [around]. */
internal fun passageSpread(speed: Double, around: Double, window: Double) = abs(around) + window + GLIDE_SIZE / 2 + speed + 1

/**
 * How far across the way the box swings diagonally between two spots half a scan step apart along [dir], see
 * [HOP_SCAN_STEP]: none along an axis.
 */
internal fun halfStepSwing(dir: Vec3d) = HOP_SCAN_STEP / 2 * 2 * abs(dir.x * dir.z) / dir.horizontalLengthSquared()

/** Where [state] of a steered path along the line [dir] from [linePoint] is, [across] it. */
internal fun steerPoint(linePoint: Vec3d, dir: Vec3d, across: Vec3d, state: SteerState): Vec3d =
	linePoint.add(dir.multiply(state.along)).add(across.multiply(state.offset))

/** [glide]'s climb alone at a whole [pitch] from -[MAX_HOP_PITCH_UP] to 90, from [PITCH_COS_SQ] and [PITCH_NEG_SIN]. */
internal fun glideClimb(climb: Double, speed: Double, pitch: Int, gravity: Double): Double {
	val cosSq = PITCH_COS_SQ[pitch + MAX_HOP_PITCH_UP]
	var y = climb + gravity * (-1 + cosSq * 0.75)
	if (y < 0) y += y * -0.1 * cosSq
	if (pitch < 0) y += speed * PITCH_NEG_SIN[pitch + MAX_HOP_PITCH_UP] * 0.04 * 3.2
	return y * 0.98
}

/**
 * Vanilla's elytra glide, the climb and speed after a tick at [pitch] facing where you fly. Vanilla then turns the
 * velocity a tenth of the way back to the look at the speed it had, flying where you look that takes a tenth off
 * each change of speed, with [turn] that too: without it a hold of 60 blocks got there 0.03 slower than flown
 * (0.79 instead of 0.82). Only for openings, see [Opening], so far: the margins of the glides over holes were found
 * without it, with it some that only just make it go for it and miss.
 */
internal fun glide(climb: Double, speed: Double, pitch: Double, gravity: Double, turn: Boolean = false): Pair<Double, Double> {
	val rad = Math.toRadians(pitch)
	val cosSq = cos(rad) * cos(rad)
	var y = climb + gravity * (-1 + cosSq * 0.75)
	var horizontal = speed
	if (y < 0) {
		val lift = y * -0.1 * cosSq
		y += lift
		horizontal += lift
	}
	if (rad < 0) {
		val lift = speed * -sin(rad) * 0.04
		y += lift * 3.2
		horizontal -= lift
	}
	if (turn) horizontal += (speed - horizontal) * 0.1
	return y * 0.98 to horizontal * 0.99
}
