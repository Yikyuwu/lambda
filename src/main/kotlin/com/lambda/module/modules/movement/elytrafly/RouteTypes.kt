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

import com.lambda.context.SafeContext
import net.minecraft.block.BlockState
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.util.shape.VoxelShape
import kotlin.math.floor
import kotlin.math.max

// What the route planner and the maneuver chooser work with, see [ObstaclePassingMode].

/** The kinds of ways, see [HoleWay.kind] and [Maneuver.kind]: the ones told apart. */
internal const val KIND_BRAKE = "brake"
internal const val KIND_STEP = "step"
internal const val KIND_TOUCH = "touch"
internal const val KIND_PLAN = "plan"
/** Bouncing on, no plan past what's ahead, see [RouteSearch.planLands]. */
internal const val KIND_ON = "on"

/**
 * The path shown, see [PasserSettings.showPath], put together on the game thread at [at] for [hop] and drawn as it is
 * on the render thread: nothing in it changes once made. Points in the world, the feet's height for the glide.
 */
internal class PathView(
	val at: Int, val hop: Hop?, val line: List<Vec3d>, val glide: List<Vec3d>, val swerve: List<Vec3d>, val steer: List<Vec3d>,
	val lane: List<Vec3d>, val touch: Box?, val opening: Box?,
)

/**
 * A way past what's ahead looked at by [shadowChoose]: a [kind] on the [lane] across the line, and how it went from
 * each start, see [CHOOSER_STARTS]. With [fixedCost] not simulated, e.g. landing.
 */
internal class Maneuver(val kind: String, val lane: Double, val note: String = "", val fixedCost: Double? = null) {
	var runs = 0
	var made = 0
	var ticks = 0.0
	var exits = 0.0
	/** Why the first start that didn't get past didn't. */
	var why = ""
	/** Simulates it from the start with that index in [CHOOSER_STARTS], see [made] and [missed]. None with [fixedCost]. */
	var sim: (SafeContext.(start: Int) -> Unit)? = null
	val success get() = if (fixedCost != null) 1.0 else if (runs == 0) 0.0 else made.toDouble() / runs
	/** How fast it is past what's ahead, on average over the starts that get past. */
	val exit get() = if (made == 0) 0.0 else exits / made
	/** Getting past from every start. */
	val sure get() = fixedCost == null && runs == CHOOSER_STARTS.size && made == runs

	fun reset() {
		runs = 0
		made = 0
		ticks = 0.0
		exits = 0.0
		why = ""
	}

	fun made(ticks: Double, exit: Double) {
		runs++
		made++
		this.ticks += ticks
		exits += exit
	}

	fun missed(why: String) {
		runs++
		if (this.why.isEmpty()) this.why = why
	}

	/** The expected cost: the ticks to the checkpoint and how slow after it when it gets past, the cost of not else. */
	val cost: Double get() {
		fixedCost?.let { return it }
		if (made == 0) return CHOOSER_FAIL_COST
		val prior = if (kind == KIND_TOUCH) CHOOSER_LAND_PRIOR else 0.0
		return success * (ticks / made + CHOOSER_EXIT_VALUE / max(exit, 0.1) + prior) + (1 - success) * CHOOSER_FAIL_COST
	}

	override fun toString() = buildString {
		append(kind)
		if (fixedCost == null) append(" %+.2f".format(lane))
		append(note)
		append(" (score %.1f".format(cost))
		if (fixedCost == null) append(", exit %.2f, %d/%d".format(exit, made, runs))
		if (made < runs && why.isNotEmpty()) append(", $why")
		append(")")
	}
}

/**
 * Where a route is at: [along] the line and [offset] across it, the feet at [y] climbing at [climb], at [speed] a
 * tick, bouncing on the [level] up on a layer or the floor, see [SimFrame.level].
 */
internal class RouteState(val along: Double, val offset: Double, val y: Double, val climb: Double, val speed: Double, val level: Double)

/**
 * A way past to fly from where a route is, along its lane until [laneUntil] when it's a lane, see [routeStep]: past
 * the block at [blockAt] along the line, or over the [hole] with a layer starting at [layerAt] after it, to commit it
 * like the chooser does, see [routeRescue].
 */
internal class RouteWay(
	val way: HoleWay, val laneUntil: Double, val blockAt: Double? = null, val hole: ClosedFloatingPointRange<Double>? = null, val layerAt: Double? = null,
)

/** One way past on a route, [way], ending at [end] [ticks] later, drawn through [points]. */
internal class RouteStep(val way: RouteWay, val end: RouteState, val ticks: Int, val points: List<Vec3d>) {
	val kind get() = way.way.kind
	/** The deciders' plan, see [planWay]. */
	val plan get() = way.way.text == PLAN_TEXT

	override fun toString() = if (kind == KIND_BRAKE) "brake" else "%s%s %+.2f to %.0f".format(kind, if (plan) "*" else "", end.offset, end.along)
}

/**
 * The ways past in a row, [steps], ending at [end] after [ticks], [score]d at the route's horizon, lower is better,
 * see [routeScore].
 */
internal class Route(val steps: List<RouteStep>, val end: RouteState, val ticks: Int, val score: Double) {
	/** The same ways on the same lanes, wherever they end exactly, for logging it only once it changes. */
	val key get() = steps.joinToString(" ") { "%s%+.1f".format(it.kind, it.end.offset) }

	override fun toString() = steps.joinToString(" -> ")
}

/** The route drawn, see [PasserSettings.showPath]: each step's points, and coasting on from its end to the horizon. */
internal class RouteView(val steps: List<List<Vec3d>>, val coast: List<Vec3d>)

/**
 * What [waysFrom] found from a route's end: the [ways] past what's next, and whether swerving past it is looked for
 * too when none of them gets past, see [swerveWays], then bouncing on toward what's next [bounceAt], see [bounceOnWay].
 */
internal class RouteWays(val ways: List<RouteWay>, val swerves: Boolean, val bounceAt: Double? = null)

/**
 * A beam search for the route past what's ahead, spread over ticks, see [routeShadow]: from [root], on the line [dir]
 * from [lineX], [lineZ], scored at the [horizon] along it. Each tick it goes on where it left off, a way at a time:
 * the routes in the [beam] of this [depth], the one at [index] looked on from, its ways left to fly in [todo]. The
 * deciders' plan when it started, [did], [didKind], to compare with.
 */
internal class RouteSearch(
	val dir: Vec3d, val lineX: Double, val lineZ: Double, val root: RouteState, val horizon: Double, rootScore: Double,
	val rootWays: List<RouteWay>, val did: String, val didKind: String, val width: Int = ROUTE_BEAM, val maxDepth: Int = ROUTE_DEPTH,
	/** The glides simulated, kept from tick to tick of the search, see [hopRunMemo], and from search to search on the route thread. */
	val hops: HashMap<HopRunKey, HopRun> = HashMap(),
) {
	var beam = listOf(Route(emptyList(), root, 0, rootScore))
	var depth = 0
	var index = 0
	/** The routes one way on from the beam so far, the next beam's to pick from. */
	val next = mutableListOf<Route>()
	/** The routes that end, with nothing ahead in reach or braking. */
	val done = mutableListOf<Route>()
	/** The ways from the route at [index] not flown yet, null before they're looked for. */
	var todo: ArrayDeque<RouteWay>? = null
	var made = 0
	var swerves = false
	var swerved = false
	/** What's next that bouncing on toward is looked at for once nothing else gets past it, see [bounceOnWay]. */
	var bounceAt: Double? = null
	var expanded = 0
	var nanos = 0L
	var ticks = 0
	val finished get() = depth >= maxDepth || beam.isEmpty()

	/**
	 * The best route there is so far: ended, one way on, or not looked on from yet, not those looked on from already.
	 * One not ended that's still down below the level it started on, e.g. off a layer into a hole's bottom, hasn't
	 * shown a way back up out of there: as if it lands, see [CHOOSER_BRAKE_COST].
	 */
	fun best() = (done + next + beam.drop(if (todo == null) index else index + 1)).minByOrNull { judged(it) }

	/** [best] with the score it's judged by, see [judged]. */
	fun bestJudged() = best()?.let { Route(it.steps, it.end, it.ticks, judged(it)) }

	/** [route]'s score as [best] judges it. */
	fun judged(route: Route) =
		if (route !in done && route.end.along < horizon && route.end.level < root.level - STEP_HEIGHT / 2) route.score + CHOOSER_BRAKE_COST else route.score

	/** The routes left that fly the deciders' plan first. */
	private fun planRoutes() = (done + next + beam).filter { it.steps.firstOrNull()?.plan == true }

	/** The best score of a route flying the deciders' plan first, see [judged], infinite with none. */
	fun planScore() = planRoutes().minOfOrNull { judged(it) } ?: Double.POSITIVE_INFINITY

	/**
	 * Whether the deciders' plan lands: none of the routes left that fly it first gets past without landing. With none
	 * at all, only bouncing on lands, it runs into something within a bounce; a lane or glide of theirs the route
	 * doesn't fly as they will, e.g. one they're still planning, isn't known to.
	 */
	fun planLands(): Boolean {
		val plans = planRoutes()
		return if (plans.isEmpty()) didKind == KIND_ON else plans.all { judged(it) >= CHOOSER_BRAKE_COST }
	}
}

/** What the route planner spends its time on, see [routeTimed]. */
internal enum class RouteWork { Scan, HoleWays, DownWay, BlockWays, SwerveWays, Glide, Lane, Score }

/** A route state rounded, what [waysFrom] found from it is found from all close to it, see [routeMemo]. */
internal data class RouteKey(val along: Long, val offset: Long, val y: Long, val climb: Long, val speed: Long, val level: Long, val swerving: Boolean)

/**
 * What a job on the route thread found, see [searchOnThread]: for the job [gen] started at [tick] (see [flightTicks])
 * from [root], the best [route] so far, drawn as [view], after [searches] searches, the last with the beam [width]
 * [depth] deep, [expanded] routes looked on from in all in [nanos]. The deciders' plan when it started, [didKind]
 * and [did]. With the [last] one of the job, where its time went, see [RouteWork].
 */
internal class RoutePub(
	val gen: Long, val tick: Int, val root: RouteState, val route: Route?, val view: RouteView?, val searches: Int, val width: Int, val depth: Int,
	val expanded: Int, val nanos: Long, val finished: Boolean, val did: String, val didKind: String, val last: Boolean,
	val work: LongArray, val calls: IntArray, val memoHits: Int, val memoLooks: Int, val horizon: Double, val planLands: Boolean,
	val planScore: Double,
)

/** The job on the route thread as the game thread knows it: its generation, the line it searches on and the plan when it started. */
internal class RouteJob(val gen: Long, val dir: Vec3d, val line: Vec3d, val plan: List<Any?>)

/** What tells a block apart in the corridor, see [CorridorCell]: the shapes by identity, most blocks share theirs. */
internal data class CellKey(val state: BlockState, val shape: VoxelShape, val bare: VoxelShape, val hazard: Boolean, val solid: Boolean)

/**
 * A way over a hole Chooser Decides can commit to: gliding over it on the [hop]'s lane, touching down on its top, or
 * side-stepping it on the floor on the [lane]. How fast it is after it on average over the starts, see [chooseAtHole].
 */
internal class HoleWay(val kind: String, val hop: Hop?, val lane: Lane?, val text: String, val thread: Opening? = null, val toLevel: Double? = null) {
	var exit = 0.0
	/** What follows it from where it ends, from the first start, flown once it's past, see [continuation]. */
	var then: Continuation? = null
	/** From how many of [CHOOSER_STARTS] it got past. */
	var made = 0
	override fun toString() = "$kind $text (exit %.2f, %d/%d)".format(exit, made, CHOOSER_STARTS.size)
}

/**
 * Where Chooser Decides found no way past from enough starts a moment ago, see [chooserMissed]: a hole by its end or a
 * block, along the line, on the lane that far across, at that speed band, from a floor that high, until that tick.
 */
internal class ChooserMiss(val kind: String, val at: Double, val lane: Double, val band: Int, val floorY: Double, val until: Int)
