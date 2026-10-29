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
 * committing to it resets (what [PlanSnapshot] puts back), the memos and the caches of a pass (see [planning]), the
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
	var dodge: Vec3d? = null
	var dodgeOffset: Double = 0.0
	var dodgeSteer: Steer = Steer.GENTLE
	var dodgeLean: Int = 0
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
	 * A copy to search in apart, see [withCtx]: the plan, the line and where it looks from, the miss texts and the
	 * world and you it reads as they are, the memos and the caches empty.
	 */
	fun forSearch() = PlanCtx().also {
		it.hopRejection = hopRejection
		it.hopTooSlow = hopTooSlow
		it.laneMissText = laneMissText
		it.laneCarpetsText = laneCarpetsText
		it.lastHopRun = lastHopRun
		it.dipMiss = dipMiss
		it.touchMiss = touchMiss
		it.touchLaneText = touchLaneText
		it.openingMiss = openingMiss
		it.threadMiss = threadMiss
		it.planOwners += planOwners
		it.hop = hop
		it.dodge = dodge
		it.dodgeOffset = dodgeOffset
		it.dodgeSteer = dodgeSteer
		it.dodgeLean = dodgeLean
		it.dodgeEnd = dodgeEnd
		it.dodgeUpAt = dodgeUpAt
		it.thread = thread
		it.slowFor = slowFor
		it.slowAhead = slowAhead
		it.slowingAhead = slowingAhead
		it.sprintHeldAhead = sprintHeldAhead
		it.slowJumpsFor = slowJumpsFor
		it.dipAt = dipAt
		it.dipLaneAt = dipLaneAt
		it.brakeAt = brakeAt
		it.touchSide = touchSide
		it.touchHoleStart = touchHoleStart
		it.dodgeTicks = dodgeTicks
		it.dodgeYaw = dodgeYaw
		it.dodgeCarpets = dodgeCarpets
		it.onLane = onLane
		it.dodgeAfterMob = dodgeAfterMob
		it.fitDodge = fitDodge
		it.slowTo = slowTo
		it.landing = landing
		it.brakeCarpets = brakeCarpets
		it.brakeY = brakeY
		it.landedTicks = landedTicks
		it.levelHoldY = levelHoldY
		it.layerSpeedUp = layerSpeedUp
		it.startPos = startPos
		it.baseY = baseY
		it.lowerLevels += lowerLevels
		it.skipAt = skipAt
		it.skipHopAt = skipHopAt
		it.restartFor = restartFor
		it.restartedFor = restartedFor
		it.noLaneAt = noLaneAt
		it.noLaneDodge = noLaneDodge
		it.noGapAt = noGapAt
		it.layerHoleAgain = layerHoleAgain
		it.chosenHole = chosenHole
		it.chosenLayerAt = chosenLayerAt
		it.chosenLine = chosenLine
		it.chosenLane = chosenLane
		it.chosenBlockAt = chosenBlockAt
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
