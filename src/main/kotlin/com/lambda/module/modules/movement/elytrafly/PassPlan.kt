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

import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.Vec3d
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// What the obstacle passer plans with, see [ObstaclePassingMode].

/**
 * Through an opening in what's glided over, the wall [wallAt] along the line. Each tick moves along one axis and then
 * the other, diagonally the box swings out across the way, e.g. between the two blocks beside a single one missing,
 * which only touch its corners: centred it gets through only at some ticks. With the heading held a little to [lean]
 * across, which axis goes first is known, 0 when it doesn't matter there. Gone through from [centre] across the
 * line, the middle of the widest spot that gets through at every tick, see [passage], at [maxSpeed] at most. The way
 * through is looked at from [from] to [to] along the line from [origin], the box between [minY] and [maxY]. The heading
 * is held so until [passEnd] along the line, as far as the way through was looked at: through a field of floating
 * blocks that's all of the glide, a block beside the way past the opening is passed the same way. Steered over to as
 * sharply as [steer].
 */
internal class Opening(
	val origin: Vec3d, val dir: Vec3d, val from: Double, val to: Double, val minY: Double, val maxY: Double,
	val centre: Double, val width: Double, val lean: Int, val maxSpeed: Double, val wallAt: Double, val hopStart: Double,
	val passEnd: Double, val steer: Steer,
) {
	/** Whether how it went through was logged. */
	var logged = false
	/** Held for it too fast, flapping until holding the height gets there slow enough, see [shedPitch]. */
	var shedding = false
	/** Where across it goes through at each speed, by [THREAD_SPEED_STEP]s, null where none is wide enough. */
	val centres = HashMap<Int, Double?>()
}

/** The widest spot across the way that gets through at every tick, its middle [centre], with the heading held to [lean]. */
internal class Passage(val centre: Double, val width: Double, val lean: Int)

/** In the way [distance] ahead. A [mob] is dodged, gliding over or under it the glide only sees blocks. */
internal class Obstacle(val distance: Double, val mob: Boolean = false, describe: () -> String) {
	/** Only put together for the debug log, there's a hit for each ray. */
	val what by lazy(describe)
}

/**
 * Gliding over an obstacle along the line from [start] to [end], the feet between [feetY] and [topY] meanwhile,
 * held at [targetY]. The obstacle itself starts at [obstacle]. A [step] up onto a layer of blocks to bounce on, or
 * gliding [down] off the end of one, [stepHeight] above the floor. On the [lane] that far across the line, dodging.
 * Something [low], carpets or a hole, bounced on over unless a bounce would come down on it.
 */
internal class Hop(
	val start: Double, val end: Double, val feetY: Double, val topY: Double, val obstacle: Double, val step: Boolean,
	val down: Boolean = false, lane: Vec3d = Vec3d.ZERO, val stepHeight: Double = STEP_HEIGHT, val low: Boolean = false,
	/** Under something hanging over the way, held in the middle of the room below it, off the floor so it doesn't bounce. */
	val under: Boolean = false,
	/** Over a hole, held high, with room to sink as it slows down so it gets farther. */
	val hole: Boolean = false,
	/** Over a hole with no floor in sight yet, held as far as it's seen and then looked at again. */
	val open: Boolean = false,
	/** Over a magma cube, held at the last moment. */
	val cube: Boolean = false,
	/** Not making it, climbing as high as it gets anyway, bumping into it lower is no worse. Held right away. */
	val anyway: Boolean = false,
	/** Held at this height instead, e.g. higher over a magma cube where there's room. */
	val aim: Double? = null,
	/** Over a magma cube, held this high instead when fast enough once it's held, see [holdingFor]. */
	val highAim: Double? = null,
	/** Stretches along the line, e.g. blocks in a hole, the feet have to be higher than [feetY] over, and how high. */
	val raised: List<Pair<ClosedFloatingPointRange<Double>, Double>> = emptyList(),
	/** A top on the way, e.g. a pillar, touched down on and bounced off instead of glided over. */
	val touch: Touch? = null,
	/** Out to a lane beside what's on the line and back, held over what's low on the way, see [Swerve]. */
	val swerve: Swerve? = null,
) {
	val lane = laneVec(lane)
	/** Across the line at [along] it, [across] being the way across: [lane], or where [swerve] is there. */
	fun laneAt(along: Double, across: Vec3d): Vec3d = if (swerve == null) lane else laneVec(across.multiply(pathOffset(swerve.path, along)))
	val targetY = aim ?: when {
		under -> (feetY + topY) / 2
		hole -> topY - min(HOP_MARGIN, (topY - feetY) / 2)
		else -> feetY + min(HOP_MARGIN, (topY - feetY) / 2)
	}
	/** Changed so, how far it is glided over it kept, e.g. steering over to a lane mid-glide. */
	fun with(
		down: Boolean = this.down, lane: Vec3d = this.lane, stepHeight: Double = this.stepHeight, touch: Touch? = this.touch,
		topY: Double = this.topY,
	) = Hop(start, end, feetY, topY, obstacle, step, down, lane, stepHeight, low, under, hole, open, cube, anyway, aim, highAim, raised, touch, swerve).also {
			copyStateTo(it)
			it.touchedAt = touchedAt
			it.intoDip = intoDip
			it.fromLayer = fromLayer
		}
	/**
	 * A copy with how it went so far, for a route search on the route thread: the flight goes on changing this one, see
	 * [held] and the like. Its [touch] is the same, the flight only marks it looked at, see [Touch.steerChecked].
	 */
	fun copied() = with().also {
		it.thenMisses = thenMisses
		it.overTop = overTop
		it.skipped = skipped
	}
	/** [other], this one planned again, e.g. a swerve from where you are, going on from how this one went so far. */
	fun goneOnAs(other: Hop) = other.also { copyStateTo(it) }
	/** How it went so far onto [other]: waiting, held, topped, rose, chosen and what follows it. */
	private fun copyStateTo(other: Hop) {
		other.waiting = waiting
		other.held = held
		other.heldCheckedAt = heldCheckedAt
		other.topped = topped
		other.rose = rose
		other.chosen = chosen
		other.then = then
	}
	/** Held from now on, at [tick], see [held]. */
	fun holdFrom(tick: Int) {
		held = true
		heldCheckedAt = tick
	}
	/** Over carpets on the floor, not onto or off a layer of them, see [planHop]. */
	val overCarpets get() = low && !hole && !step && !down
	/** How many looks in a row found nothing past it from where the glide really ends, see [thenStillFollows]. */
	var thenMisses = 0
	/** Not making it yet, logged once. */
	var waiting = false
	/** Holding the height for it, not letting go again as the speed drops a little. */
	var held = false
	/** The tick [held] was last looked at, it takes a few simulations of the glide. */
	var heldCheckedAt = -1
	/**
	 * Over a hole, up at [targetY] once, or as high as it got: rising while held, then not. From then on it keeps the
	 * height it has, sinking as it slows, see [holdY].
	 */
	var topped = false
	/** Rising while held, see [topped]. */
	var rose = false
	/** Over [touch] once and diving onto it, it doesn't stop halfway, see [hopPitch]. */
	var overTop = false
	/** Past where the box comes most inside [touch]'s edges without coming far enough in, glided on past it, see [hopPitch]. */
	var skipped = false
	/** Gliding [down] into a dip a block deep to bounce on down there, see [dipLane]. */
	var intoDip = false
	/** Over the hole right after the end of a layer, planned from up on it and kept to the hole's end, see [layerHole]. */
	var fromLayer = false
	/** Picked by the chooser over a hole, looked at again for a much faster way until it's held, see [chooseAtHole]. */
	var chosen = false
	/** What it was judged with past it, flown right after it, see [continuation] and [flyThen]. */
	var then: Continuation? = null
	/** The tick it touched down on [touch], -1 before. */
	var touchedAt = -1
	val touched get() = touchedAt >= 0
	/**
	 * The height to hold from [y]: [targetY], over a hole once [topped] only as high as it is. Pulling back up there
	 * costs the speed that carries it to the floor after the hole, it sinks all the more after.
	 */
	fun holdY(
		y: Double, along: Double, topped: Boolean = this.topped, touched: Boolean = this.touched, dive: Boolean, overTop: Boolean,
	) = when {
		// Just above the top to touch down on, skimming down to just above it once it's coming ([dive]), then down onto
		// it once [overTop]. Below its height only with the box over it already: a move goes down first, then across,
		// beside it that runs into its side. Skimming only coming down onto it from above: still climbing up to it,
		// easing off below its height runs into its side.
		touch != null && !touched -> when {
			!dive -> touch.y + TOUCH_ABOVE
			overTop && y >= touch.y -> touch.y - TOUCH_DIVE
			y < touch.y + TOUCH_SKIM -> touch.y + TOUCH_ABOVE
			else -> touch.y + TOUCH_SKIM
		}
		hole && topped -> min(targetY, max(y, needFrom(along) + HOP_MARGIN))
		else -> targetY
	}
	/** How fast it may sink: down onto the top to touch down on fast once over it, see [holdY]. */
	fun sinkAt(dive: Boolean) = if (dive) TOUCH_SINK else HOLD_SINK
	/**
	 * When the glide simulation has the top to touch down on coming: like the flight, from [TOUCH_SKIM_TICKS] before
	 * the box comes over it at [speed], see [canHop] and [hopPitch]. From only [TOUCH_DIVE_TICKS] before its middle the
	 * skim down to just above it hadn't started yet where the flight's had, a short top, e.g. a block beside a pillar,
	 * was glided past a cm too high in the simulation and touched down on in the flight.
	 */
	fun diving(along: Double, speed: Double, touched: Boolean = this.touched) =
		touch != null && !touched && along >= touch.range.start - speed * TOUCH_SKIM_TICKS && along <= touch.range.endInclusive
	/** How high the feet have to be at [along] the line. Over the top to touch down on, down on it. */
	fun feetAt(along: Double) = if (touch != null && along in touch.range) touch.y
		else raised.filter { along in it.first }.maxOfOrNull { it.second }?.let { max(feetY, it) } ?: feetY
	/** How high the feet have to stay from [along] on until past the last of [raised]. */
	fun needFrom(along: Double) = raised.filter { it.first.endInclusive >= along }.maxOfOrNull { it.second }?.let { max(feetY, it) } ?: feetY
}

/**
 * A top at [y] to touch down on and bounce off, under the box from where to where along the line in [range], on the
 * [lane] that far across the line, [middle] halfway along it.
 */
internal class Touch(val range: ClosedFloatingPointRange<Double>, val y: Double, lane: Vec3d = Vec3d.ZERO) {
	val lane = laneVec(lane)
	val middle = (range.start + range.endInclusive) / 2
	/** Over a hole, steered toward [lane] only from here along the line on, past what's in the way on it before. */
	var switchAt = Double.NEGATIVE_INFINITY
	/** Whether the flight looked at getting over it from where it really is at [switchAt] yet, see [steerInMisses]. */
	var steerChecked = false
	/** Stretches along the line flown tilted away from the line, the box swinging out only that way, see [leanYaw]. */
	var leans: List<ClosedFloatingPointRange<Double>> = emptyList()
	/**
	 * Which way across the line the heading is held on the way to it, the box swinging out between the axes only that
	 * way, 0 when it may swing either way, see [topHops] and [heldLean].
	 */
	var lean = 0
	/**
	 * Where across the line it is on the way to it, steered over to [lane]: along the line and across it, a tick apart,
	 * see [topHops]. Empty when on [lane] already.
	 */
	var path: List<Pair<Double, Double>> = emptyList()
}

/**
 * Past what's on the line, out to [offset] across it from [outAt] along it, back from [backAt], held over what's low on
 * the way from the hop's start to its end, e.g. a railing beside a pillar in the middle of the highway. Where across
 * the line it is along the way, along and across it a tick apart, [path], steered like the flight does, see [steerYaw],
 * out and back as sharply as [steer].
 */
internal class Swerve(
	val offset: Double, val outAt: Double, val backAt: Double, val path: List<Pair<Double, Double>>, val blockAt: Double = backAt,
	val steer: Steer = Steer.GENTLE,
) {
	/** Where across the line to steer to at [along] it. */
	fun targetAt(along: Double) = if (along >= outAt && along < backAt) offset else 0.0
}

/** Lets something through right away for a new [key], for the same again only every [ticks] times it's asked. */
internal class Limiter(val ticks: Int) {
	var key = ""
	var wait = 0
	fun allows(key: String): Boolean {
		if (!onGameThread) return true
		if (key == this.key && --wait > 0) return false
		this.key = key
		wait = ticks
		return true
	}
}

/**
 * Where across the line to steer to get past an obstacle, tilting the heading [lean] across it on the way past. With
 * [carpets] that many on it ahead, and whether it [stays] on the lane you're on. [offset] across the line it is, the
 * target short of it by the drift of the tilt. Steered over to only from [steerAt] along the line on when given, through
 * what's passed first on the lane flown now, see [steerStart]. As sharply as [steer], judged and flown alike.
 */
internal class Lane(
	target: Vec3d, val lean: Int, val offset: Double, val carpets: Int = 0, val stays: Boolean = false, val steerAt: Double? = null,
	val steer: Steer = Steer.GENTLE,
) {
	val target = laneVec(target)
}

/**
 * How sharply a lane is steered over to: at where the drift across the line takes you, [ahead] along it, the heading
 * aimed at most [maxDegrees] off the line, any with none. The flight turns a tenth of the way to where you look each
 * tick, the drift counted in keeps it from overshooting, see [STEER_DAMPING_TICKS]. Gently, sharper only when no lane
 * gets past gently, for the ones it isn't on in time, see [steered]: gently it takes 25 to 40 along the line to get two
 * lanes over at any speed, the heading hardly 5° off it (a wall with a single block missing on the line 30 before
 * another with its gap two lanes over: steered over only once through the first, no lane past the second fit at any
 * speed, at [SHARP] it fits at 1.64). A sharp turn at speed gets set back: the aim is held to [maxDegrees], as far as
 * gently over to a lane 2 to 3 blocks off at the start of the swing, turning to it sheds at most 0.2% of the speed a
 * tick. Told in the debug log as [text].
 */
internal enum class Steer(val ahead: Double, val maxDegrees: Double?, val text: String) {
	GENTLE(DODGE_STEER_AHEAD, null, ""),
	SHARP(6.0, 10.0, ", steered over sharper"),
	SHARPEST(4.0, 12.0, ", steered over sharpest");

	/** The heading off the line to aim at [across] short of the lane across it, drifting [drift] farther across meanwhile. */
	fun aim(across: Double, drift: Double) = held(atan2(across - drift, ahead))

	/** [aim] off the line held to [maxDegrees]. */
	fun held(aim: Double) = maxDegrees?.let { Math.toRadians(it) }?.let { aim.coerceIn(-it, it) } ?: aim
}

/** Bouncing on until [until] along the [line] for what's [at], from the [tick], see [commitRoute]. */
internal class BounceOn(val until: Double, val line: Vec3d, val tick: Int, val at: Double)

internal class SteerMiss(val startPos: Vec3d, val middle: Double, val y: Double, val at: Int)

/** What [laneHit] depends on besides the range, its key in [PlanCtx.planningLaneHits]. */
internal data class LaneHitKey(val point: Vec3d, val dir: Vec3d, val y: Double, val maxFlightY: Double, val sprintOff: Boolean, val world: PassWorld)

/**
 * Something next after the way flown now, [at] along the line, that fits only as fast as [speed], see [slowForNext].
 * Not slowed down for before [waitUntil] along the line: the glide flown now only gets past faster, see [canGetOver].
 * [dodged] past it on a lane or along a gap, not through an opening in it.
 */
internal class SlowAhead(val at: Double, val speed: Double, val waitUntil: Double?, val what: String, val dodged: Boolean) {
	/** Slowing down for it once, logged then. */
	var started = false
}

/**
 * Where a glide simulated by [canHop] is right past the hop: [at] along the line, the feet at [y] climbing at [climb],
 * at [speed], [ticks] after it started.
 */
internal class HopEnd(val speed: Double, val at: Double, val y: Double, val climb: Double, val ticks: Int = 0)

/**
 * What follows a way past from where it ends: [exit], how fast past what's next on its lane too, and how, a [hop] or a
 * [lane] to dodge to past [at] along the line, neither when there's nothing to get past within reach or it's braked for.
 * Nothing within reach with some of it not loaded yet is [unseen]: a chunk not sent yet is air to the rays.
 */
internal class Continuation(val exit: Double, val hop: Hop?, val lane: Lane?, val at: Double, val text: String, val unseen: Boolean = false) {
	/**
	 * Kept with the way it follows: flown on to once past it, see [flyThen], and looked at again on the way, see
	 * [thenStillFollows], [unseen] too, once loaded (right after a far teleport a glide over steps judged with nothing
	 * after it, the steps 7 past its end in a chunk not sent yet, were never looked at again, it glided on over both
	 * from 1.3 down to 0.38 and sank into the second).
	 */
	val kept get() = hop != null || lane != null || unseen
}

/**
 * What [continuation] found from where in which frame, not what it's simulated from, while the chooser looks, see
 * [withHopMemo], with tops of [anyHeightTops] or not.
 */
internal data class ContinuationKey(val lane: Vec3d, val at: Long, val y: Long, val climb: Long, val speed: Long, val frame: SimFrame, val anyHeight: Boolean)

/** Which hole and lane [speedHopPlan] is planned for and when, see [planSpeed]. */
internal data class SpeedHopKey(val start: Double, val end: Double, val lane: Double, val floor: Double)

/** What [clearInside] found: where how high, how high it may go, where to hold it, and a block that can't be glided over. */
internal data class Clearance(
	val raised: List<Pair<ClosedFloatingPointRange<Double>, Double>>, val topY: Double, val aim: Double?, val blocked: Double?,
)

/** What [raiseOnStep] found, and what looking at it set [hopTooSlow] and [hopRejection] to, null when it didn't. */
internal class StepRaise(val at: Double?, val tooSlow: Boolean, val rejection: String?)

/**
 * How a glide [canHop] simulated went: whether it [made] it, why not ([miss], [short], where along the line the box
 * would [runInto] a block), where and how it's past it ([exit] speed, [exitAt], [exitY], [exitClimb], after [ticks],
 * [touched] down on its top on the way), and the speed at the obstacle ([wall]). What a run doesn't find, e.g. the
 * exit of one that misses, is the run's before it.
 */
internal data class HopRun(
	val made: Boolean, val missText: Lazy<String>, val short: Double, val exit: Double, val exitAt: Double, val exitY: Double,
	val exitClimb: Double, val ticks: Int, val wall: Double, val touched: Boolean, val runInto: Double = Double.NaN,
) {
	/** Put together only once read, most never are. */
	val miss get() = missText.value

	companion object {
		/** Before any. */
		val NONE = HopRun(false, lazyOf(""), Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0, 0.0, 0, 0.0, false)
	}
}

/** A glide simulated by [canHop]: the [hop] itself, not one alike, from where with what in which frame, and how far it's flown already. */
internal data class HopRunKey(
	val hop: Hop, val progress: Double, val holdFrom: Int?, val from: Double, val margin: Double, val gain: Double, val turn: Boolean,
	val held: Boolean, val topped: Boolean, val rose: Boolean, val touchedAt: Int, val frame: SimFrame,
)

/** A state to simulate from instead of yours: the feet at [y] climbing at [climb], at [speed] a tick, see [SimFrame.start]. */
internal data class ChainStart(val y: Double, val climb: Double, val speed: Double)

/**
 * What the glides are simulated in, all they read of the flight's state, so they can be simulated from another than
 * the flight's own, see [inFrame]: the line from [origin], its y the level bounced on, see [startPos], with the level
 * below it at [baseY], see [onLayer]. [threadAt] is where the hop through an opening that only fits slower starts,
 * see [threaded], [gain] how much a bounce speeds you up, see [bounceGain], [start] the state simulated from, null
 * for yours.
 */
internal data class SimFrame(val origin: Vec3d, val baseY: Double, val threadAt: Double?, val gain: Double, val start: ChainStart? = null) {
	val level get() = origin.y
	val onLayer get() = layerAbove(level, baseY)
	val onCarpets get() = carpetsAbove(level, baseY)

	/** Whether [hop] is the one through the opening, see [Opening]. */
	fun threaded(hop: Hop) = threadAt == hop.start

	/** This frame, simulated from [start]. */
	fun from(start: ChainStart) = copy(start = start)
}

/** Coming down below the top of the layer bounced on over a gap in it, not onto it, see [intoLayerSide]. */
internal class GapFall {
	var sinking = false
}

/** Where [steerPath] has you [along] the line ahead, [offset] across it, flying [heading] off it at [speed]. */
internal class SteerState(val along: Double, val offset: Double, val heading: Double, val speed: Double)

/**
 * Whether this is the game thread, not the route thread: what the flight remembers between ticks outside [PlanCtx], e.g.
 * the spots with no way yet, is the game thread's alone, the route thread neither reads nor writes it and looks at
 * everything each time, see [ObstaclePassingMode.searchOnThread].
 */
internal val onGameThread get() = MinecraftClient.getInstance().isOnThread
