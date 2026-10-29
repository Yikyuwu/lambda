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

import net.minecraft.entity.Entity
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d

/**
 * The planner's mutable state, everything a search writes or reads as it goes besides the world and you: where it
 * looks from ([lookState], [simFrame], the line, see [startPos], [baseY] and [lowerLevels]), the plan and what
 * committing to it resets (what [snapshot] takes), the memos and the caches of a pass (see [planning]), the
 * miss texts for the log and the world and you it reads (see [passWorldOverride] and [flyerOverride]). The fields of
 * the mode read and write the one in [ctx]: the game thread's own, or one swapped in for a search, see [withCtx].
 */
internal class PlanCtx {
	var hopRejection: String = ""
	var hopTooSlow: Boolean = false
	var laneMissText: Lazy<String> = lazyOf("")
	var laneCarpetsText: Lazy<String> = lazyOf("")
	var lastHopRun: HopRun = HopRun.NONE
	var dipMiss: String = ""
	var touchMiss: String = ""
	var touchLaneText: String = ""
	var openingMiss: String = ""
	var threadMiss: String = ""
	val planOwners = LinkedHashSet<String>()
	var hop: Hop? = null
	/** The lane committed to, see [commitLane], one to [Vec3d.ZERO] swinging back onto the line, null none. */
	var committedLane: Lane? = null
	val dodge get() = committedLane?.target
	val dodgeOffset get() = committedLane?.offset ?: 0.0
	val dodgeSteer get() = committedLane?.steer ?: Steer.GENTLE
	val dodgeLean get() = committedLane?.lean ?: 0
	var dodgeEnd: Double = 0.0
	var dodgeUpAt: Double? = null
	var thread: Opening? = null
	var slowFor: Double? = null
	var slowAhead: SlowAhead? = null
	var slowingAhead: Boolean = false
	var sprintHeldAhead: Boolean = false
	var slowJumpsFor: Double? = null
	var dipAt: ClosedFloatingPointRange<Double>? = null
	var dipLaneAt: Vec3d = Vec3d.ZERO
	var brakeAt: Double? = null
	var touchSide: Pair<Vec3d, Double>? = null
	var touchHoleStart: Double = 0.0
	var dodgeTicks: Int = 0
	var dodgeYaw: Double? = null
	var dodgeCarpets: Boolean = false
	var onLane: Boolean = false
	var dodgeAfterMob: Boolean = false
	var fitDodge: Boolean = false
	var slowTo: Double? = null
	var landing: Boolean = false
	var brakeCarpets: Boolean = false
	var brakeY: Double = 0.0
	var landedTicks: Int = 0
	var levelHoldY: Double? = null
	var layerSpeedUp: Boolean = false
	var startPos: Vec3d = Vec3d.ZERO
	/** The line's direction, fixed for the flight, see [ObstaclePassingMode.getSnappedDir], null following where you went. */
	var lineDir: Vec3d? = null
	var baseY: Double = 0.0
	val lowerLevels = ArrayDeque<Double>()
	var skipAt: Double? = null
	var skipHopAt: Double? = null
	var restartFor: Double? = null
	var restartedFor: Double? = null
	var rescuedFor: Double? = null
	/** How many [sandboxed] trials deep the planning is, see [routeRescue]. */
	var sandboxes = 0
	var noLaneAt: Double? = null
	var noLaneDodge: Vec3d? = null
	var noGapAt: Double? = null
	var layerHoleAgain: Int? = null
	var chosenHole: ClosedFloatingPointRange<Double>? = null
	var chosenLayerAt: Double? = null
	var chosenLine: Double? = null
	var chosenLane: Vec3d = Vec3d.ZERO
	var chosenBlockAt: Double? = null
	var simFrame: SimFrame? = null
	var lookState: RouteState? = null
	var hopRunMemo: HashMap<HopRunKey, HopRun>? = null
	var continuationMemo: HashMap<ContinuationKey, Continuation?>? = null
	val continuationsThisTick = HashMap<ContinuationKey, Continuation?>()
	var continuationsTick: Int = -1
	var anyHeightTops: Boolean = false
	var lowTopsSeen: Boolean = false
	var glideLaneRefused: String = ""
	var planning: Boolean = false
	var planningEntities: List<Entity>? = null
	var planningEntitiesBox: Box? = null
	val planningCeilings = HashMap<Pair<Double, Double>, Double>()
	val planningRises = HashMap<List<Any?>, Int?>()
	val planningRaises = HashMap<List<Any?>, Boolean>()
	val planningLaneHits = HashMap<LaneHitKey, Pair<Double, Obstacle?>>()
	val planningStepRaises = HashMap<List<Any?>, StepRaise>()
	var passWorldOverride: PassWorld? = null
	var flyerOverride: Flyer? = null
	var glideTrace: MutableList<Pair<Double, Double>>? = null
	var routeMemo = HashMap<RouteKey, RouteWays?>()
	var routeMemoLine: Vec3d? = null
	var routeMemoDir: Vec3d? = null
	var routeMemoTick: Int = 0
	var touchNearMiss: Hop? = null
	var steerMiss: SteerMiss? = null
	var swingFitSpeed: Double = Double.POSITIVE_INFINITY
	var swingFitTick: Int = Int.MIN_VALUE / 2
	var laneFitTick: Int = Int.MIN_VALUE / 2
	var speedHop: Pair<SpeedHopKey, Int>? = null
	var speedHopPlan: Hop? = null
	var flightTicks: Int = 0
	var passTicks: Int = 0
	var swingCapped: Boolean = false
	var stepLanding: Boolean = false
	var speedingUp: Boolean = false
	var lastRoute: Route? = null
	val routeWorkNanos = LongArray(RouteWork.entries.size)
	val routeWorkCalls = IntArray(RouteWork.entries.size)
	var routeMemoHits: Int = 0
	var routeMemoLooks: Int = 0
	/** The settings taken for a search on the route thread, see [searchConfig], null reading the live ones. */
	var config: PasserValues? = null
	/** The line's direction taken for a search on the route thread, see [getSnappedDir], null working it out from you. */
	var searchDir: Vec3d? = null
	/** What the mode's own hooks read, taken for a search on the route thread, see [captureModeInputs]. */
	var modeInputs: Any? = null

	/**
	 * A copy of the plan and what goes with it, what [restore] puts back after a trial, see [ObstaclePassingMode.sandboxed]:
	 * the plan ([hop], the lane, the opening, slowing down, the dip, landing) and the state committing to it resets with
	 * it, e.g. [dodgeTicks] and [onLane], what the deciders remember about what's ahead (skipped, landed for, no lane yet,
	 * the chooser's picks to look at again), the line's height, and the miss texts and last simulation's results for the
	 * log. Not what's kept across trials on purpose: the memos and caches, the clocks and timings, slowing down for what's
	 * next, see [slowAhead], the relook throttles and log dedupes.
	 */
	fun snapshot() = PlanCtx().also {
		it.takeAround(this)
		it.takePlan(this)
	}

	/**
	 * Puts back what [snapshot] took. The plan itself only when it changed, compared field by field: true then, for
	 * logging. One that's only equal is kept as it is.
	 */
	fun restore(snapshot: PlanCtx): Boolean {
		takeAround(snapshot)
		if (!planDiffers(snapshot)) return false
		takePlan(snapshot)
		return true
	}

	/** The plan itself, see [snapshot]. */
	private fun takePlan(from: PlanCtx) {
		hop = from.hop
		committedLane = from.committedLane
		dodgeEnd = from.dodgeEnd
		dodgeUpAt = from.dodgeUpAt
		thread = from.thread
		slowFor = from.slowFor
		dipAt = from.dipAt
		dipLaneAt = from.dipLaneAt
		brakeAt = from.brakeAt
	}

	private fun planDiffers(from: PlanCtx) =
		hop !== from.hop || dodge != from.dodge || dodgeOffset != from.dodgeOffset || dodgeSteer != from.dodgeSteer || dodgeLean != from.dodgeLean ||
			dodgeEnd != from.dodgeEnd || dodgeUpAt != from.dodgeUpAt || thread !== from.thread || slowFor != from.slowFor ||
			dipAt != from.dipAt || dipLaneAt != from.dipLaneAt || brakeAt != from.brakeAt

	/** What a [snapshot] carries besides the plan itself. */
	private fun takeAround(from: PlanCtx) {
		// Why not and how the last simulation went, for the log.
		hopRejection = from.hopRejection
		hopTooSlow = from.hopTooSlow
		laneMissText = from.laneMissText
		laneCarpetsText = from.laneCarpetsText
		lastHopRun = from.lastHopRun
		dipMiss = from.dipMiss
		touchMiss = from.touchMiss
		touchLaneText = from.touchLaneText
		openingMiss = from.openingMiss
		threadMiss = from.threadMiss
		// Who committed to the plan, and what goes with it without being compared.
		planOwners.clear()
		planOwners += from.planOwners
		slowJumpsFor = from.slowJumpsFor
		touchSide = from.touchSide
		touchHoleStart = from.touchHoleStart
		// What committing to it resets with it, see [commitLane], [commitNoLane], [commitSwingBack] and [brake].
		dodgeTicks = from.dodgeTicks
		dodgeYaw = from.dodgeYaw
		dodgeCarpets = from.dodgeCarpets
		onLane = from.onLane
		dodgeAfterMob = from.dodgeAfterMob
		fitDodge = from.fitDodge
		slowTo = from.slowTo
		landing = from.landing
		brakeCarpets = from.brakeCarpets
		brakeY = from.brakeY
		landedTicks = from.landedTicks
		levelHoldY = from.levelHoldY
		layerSpeedUp = from.layerSpeedUp
		startPos = from.startPos
		lineDir = from.lineDir
		baseY = from.baseY
		lowerLevels.clear()
		lowerLevels += from.lowerLevels
		// What the deciders remember about what's ahead.
		skipAt = from.skipAt
		skipHopAt = from.skipHopAt
		restartFor = from.restartFor
		restartedFor = from.restartedFor
		noLaneAt = from.noLaneAt
		noLaneDodge = from.noLaneDodge
		noGapAt = from.noGapAt
		layerHoleAgain = from.layerHoleAgain
		chosenHole = from.chosenHole
		chosenLayerAt = from.chosenLayerAt
		chosenLine = from.chosenLine
		chosenLane = from.chosenLane
		chosenBlockAt = from.chosenBlockAt
	}

	/**
	 * A copy to search in apart, see [withCtx]: what a [snapshot] carries, and the line and where it looks from, slowing
	 * down for what's next, the near misses, the clocks, the world and you it reads as they are, the memos and the caches
	 * empty.
	 */
	fun forSearch() = snapshot().also {
		it.slowAhead = slowAhead
		it.slowingAhead = slowingAhead
		it.sprintHeldAhead = sprintHeldAhead
		it.simFrame = simFrame
		it.lookState = lookState
		it.anyHeightTops = anyHeightTops
		it.glideLaneRefused = glideLaneRefused
		it.planning = planning
		it.passWorldOverride = passWorldOverride
		it.flyerOverride = flyerOverride
		it.touchNearMiss = touchNearMiss
		it.steerMiss = steerMiss
		it.flightTicks = flightTicks
		it.passTicks = passTicks
		it.swingCapped = swingCapped
		it.stepLanding = stepLanding
		it.speedingUp = speedingUp
		it.lastRoute = lastRoute
		it.config = config
		it.searchDir = searchDir
		it.modeInputs = modeInputs
	}
}
