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

import baritone.api.pathing.goals.GoalBlock
import com.lambda.config.Config
import com.lambda.config.ConfigBlock
import com.lambda.context.SafeContext
import com.lambda.event.events.TickEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.interaction.handlers.BaritoneHandler
import com.lambda.interaction.managers.rotating.IRotationRequest.Companion.rotationRequest
import com.lambda.interaction.managers.rotating.RotationManager
import com.lambda.module.hud.Speedometer
import com.lambda.module.modules.movement.elytrafly.ElytraFly.FlyMode
import com.lambda.threading.runGameScheduled
import com.lambda.threading.runSafe
import com.lambda.util.BlockUtils.blockState
import com.lambda.util.CommunicationUtils.logError
import com.lambda.util.CommunicationUtils.warn
import com.lambda.util.SpeedUnit
import com.lambda.util.math.dist
import com.lambda.util.math.distSq
import com.lambda.util.math.flooredBlockPos
import com.lambda.util.math.isLoaded
import com.lambda.util.world.raycast.InteractionMask
import com.lambda.util.world.raycast.RayCastUtils.blockResult
import com.lambda.util.world.raycast.RayCastUtils.rayCast
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.block.ShapeContext
import net.minecraft.entity.Entity
import net.minecraft.entity.mob.HoglinEntity
import net.minecraft.entity.mob.MagmaCubeEntity
import net.minecraft.registry.Registries
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Box
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.Vec3d
import net.minecraft.util.shape.VoxelShape
import net.minecraft.util.shape.VoxelShapes
import net.minecraft.world.BlockView
import net.minecraft.world.RaycastContext
import net.minecraft.world.World
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.sin

abstract class ObstaclePassingMode(
	override val c: Config,
	flyMode: FlyMode
) : ElytraFlyMode(flyMode) {
	companion object {
		private const val MAX_PASS_STEPS = 4
		private const val MAX_FAILED_PASSES = 2
		/** The same spot to walk to picked this often in a row gets nowhere. */
		private const val MAX_SAME_GOALS = 3
		/** How far past a spot to walk to has to be free, to fly off from it. */
		private const val WALK_GOAL_CLEAR = 3.0
		/** How long to stand still after finding no way past, before looking again. */
		private const val PASS_COOLDOWN_TICKS = 100
		/** Ahead of RotationLock, which would otherwise keep the yaw on the line. */
		private const val DODGE_PRIORITY = 101
		/** How far ahead on the lane to steer at, shorter turns sharper. A sharp turn at speed gets set back. */
		private const val DODGE_STEER_AHEAD = 12.0
		/** Close enough to the line to fly straight on again. */
		private const val DODGE_BACK_ON_LINE = 0.3
		/** Close enough to the lane dodged to, to bounce on along it. */
		private const val ON_LANE = 0.3
		private const val MAX_DODGE_TICKS = 200
		/** How far past the obstacle the lane has to be free, and the line before swinging back. */
		private const val DODGE_MARGIN = 3.0
		/** Closer than this there's no time left to steer, walking around it is safer. */
		private const val MIN_DODGE_DISTANCE = 4.0
		/**
		 * After landing from a brake, how long until you stand: the server ends the glide only once it sees you
		 * down, about a ping, and till then you slide on at full speed. Then walking friction stops you in a few.
		 */
		private const val LANDING_TICKS = 8
		/** Room left before the obstacle once stopped. */
		private const val BRAKE_MARGIN = 3.0
		/** How long to wait on the ground for the server to end the glide, about a ping and a half on a bad day. */
		private const val MAX_LANDING_TICKS = 40
		/** How close to a lane the swing over to it counts as on it. */
		private const val LANE_REACHED = 0.3
		/** How far below the floor's height you count as having fallen into a hole. */
		private const val FELL_IN = 0.1
		/** Room kept around a magma cube on every side, it hops about meanwhile. */
		private const val MAGMA_CUBE_MARGIN = 0.3
		/** A magma cube is glided over like a block this high at least. */
		private const val CUBE_HEIGHT = 2.0
		/**
		 * How far across a mob attacks you, a magma cube just by touching: your box within this of its box sideways, at
		 * its height, not above or below it. The game's sqrt(2.04) - 0.6.
		 */
		private const val CUBE_REACH = 0.83
		/** How far up and down from you magma cubes are looked for, the biggest is about two blocks. */
		private const val CUBE_SEARCH_HEIGHT = 4.0
		/** How far ahead the line has to be free to swing back onto it, the swing takes about [DODGE_STEER_AHEAD]. */
		private const val RETURN_CLEAR = DODGE_STEER_AHEAD + DODGE_MARGIN
		/** Less than the player's width taken for its rays, so a block only grazed isn't counted. */
		private const val WIDTH_MARGIN = 0.01
		/**
		 * How far ahead in ticks the drift across the line is counted in when steering. The flight direction lags the yaw
		 * by a few ticks, so steering at where you are alone overshoots the lane, a third of a block on a one block swing.
		 */
		private const val STEER_DAMPING_TICKS = 5.0
		/** How much further off [steerPath] may be, it came out 0.04 short of a logged dodge as the yaw applies a tick late. */
		private const val STEER_MARGIN = 0.1
		/** How tall and wide you are gliding. */
		private const val GLIDE_SIZE = 0.6
		/** How far above what's needed to clear an obstacle to glide over it, for room to sink a little. */
		private const val HOP_MARGIN = 0.15
		private const val HOP_SCAN_STEP = 0.25
		/** How far apart a lane is looked at for holes, less than one is long where the box drops in. */
		private const val HOLE_SCAN_STEP = 0.4
		private const val HOP_HEIGHT_STEP = 0.05
		/** A gap shorter than this between obstacles is glided over too, there's no time to land in it. */
		private const val HOP_GAP = 4.0
		/** Longer obstacles are dodged, holding the height costs speed. */
		private const val MAX_HOP_LENGTH = 16.0
		/** Longest hole, or holes too close to bounce between, or obstacle, glided over in one, as long as the height holds. */
		private const val MAX_HOLE_LENGTH = 48.0
		/** A layer of blocks this high on the floor is bounced on like the floor, anything higher is in the way. */
		private const val STEP_HEIGHT = 1.0
		/** How far onto a layer to bounce on the glide up onto it goes, and how far on it must be free up there. */
		private const val STEP_ON = 1.0
		private const val STEP_CLEAR = 4.0
		/** A gap in a layer you bounce on up to this long is glided across, a longer one you drop down into. */
		private const val MAX_GAP = 8.0
		/** A shorter gap is crossed bouncing on, there's no time to fall into it. */
		private const val MIN_GAP = 1.5
		/**
		 * How far before a layer's end to stop bouncing and glide off it. Bounced off its very edge, the server takes
		 * you for off it already and sets you back.
		 */
		private const val DROP_BEFORE = 2.0
		private const val HOP_SIM_TICKS = 80
		/** Long enough to bounce up to speed from a take-off and over a hop as far as it's looked for. */
		private const val CAN_HOP_SIM_TICKS = 200
		/** How many ticks after the next bounce starts holding from it is looked at, see [holdingFor]. */
		private const val NEXT_BOUNCE_MARGIN = 2
		/** How much higher than needed holding from the next bounce has to get you, see [fromNextBounce]. */
		private const val NEXT_BOUNCE_HEIGHT_MARGIN = 0.2
		/** Missing by at most this little, too close to stop it's gone for anyway, the flight often beats the simulation. */
		private const val ANYWAY_SHORT = 0.15
		/** Steeper up the wings brake hard. Down to 90, like without wings, to top out a bounce in time. */
		private const val MAX_HOP_PITCH_UP = 30
		/** How many degrees apart [holdPitch] looks first. */
		private const val HOLD_PITCH_STEP = 6
		/** Taking longer than this a tick, it's logged. The game has 50 for all. */
		private const val SLOW_PASSING_NANOS = 5_000_000L
		/**
		 * How many ticks before the obstacle to start holding the height, bouncing on until then, besides the ticks to
		 * climb up there. Holding it all the way loses speed.
		 */
		private const val HOP_LEAD_TICKS = 10
		/** How many ticks more it may glide in low before gliding under something, off the bounces. */
		private const val UNDER_LEAD_TICKS = 30
		/** Gliding in low under something only once starting this many ticks later wouldn't make it anymore. */
		private const val UNDER_HOLD_DELAY = 3
		/**
		 * The speed a tick gliding over a magma cube high in the room above it has to leave you with, else it's glided
		 * over low. Gliding over it costs speed, too slow after it another cube right behind can't be got over.
		 */
		private const val HIGH_CUBE_EXIT_SPEED = 1.0
		/** How many ticks after gliding over a hole it comes down on the floor, and how much floor more it needs to bounce. */
		private const val HOLE_TOUCHDOWN_TICKS = 3
		private const val HOLE_TOUCHDOWN_MARGIN = 1.0
		/** How fast a lane past an obstacle is looked for once none fits at the speed you have, see [slowingToFit]. */
		private const val FIT_SPEED = 0.85
		/** How far above what blocks in a hole need a glide over it is held, when they need more than it'd rather go, see [clearInside]. */
		private const val RAISED_HOLD = 0.5
		/** How far above a top to touch down on the feet are held until over it, see [Touch]. */
		private const val TOUCH_ABOVE = 0.2
		/** How far below a top to touch down on the hold aims once over it, down onto it. */
		private const val TOUCH_DIVE = 1.0
		/** How many ticks before the middle of a top to touch down on the dive onto it starts, sinking takes the glide that long. */
		private const val TOUCH_DIVE_TICKS = 2
		/** How fast it may sink holding the height, smoothly, and down onto a top to touch down on, see [holdPitch]. */
		private const val HOLD_SINK = 0.1
		private const val TOUCH_SINK = 0.6
		/**
		 * Room above a top to touch down on and bounce off. Held back with the jump, the server doesn't see the landing
		 * and you glide on to it, low like in a tunnel two high. Should it end the glide after all, crouching fits.
		 */
		private const val TOUCH_ROOM = 1.9
		/** How long the box has to have a top under it to touch down on it. */
		private const val MIN_TOUCH = 0.6
		/**
		 * How far before and after the top to touch down on its landing is held back, see [touchFloor]: coming down on its
		 * edge the same tick may carry you on past it.
		 */
		private const val TOUCH_HELD_AROUND = 2.0
		/** How far along the box crossing a top on the lane flown along is enough, else a lane through its middle is looked for. */
		private const val GOOD_TOUCH = 1.0
		/** How much shorter a stretch over a top a lane that much closer may have and still be taken, see [laneTouch]. */
		private const val TOUCH_AWAY_COST = 0.2
		/** How far before a top on another lane it's steered over to it, see [laneTouch]. */
		private const val TOUCH_APPROACH = 8.0
		/** How long after touching down the glide may still be ended, until the bounce off the top takes off again. */
		private const val TOUCH_GRACE_TICKS = 5
		/** How far ahead, in ticks at the speed you have, floor beside a hole is looked for to land on, see [landBeside]. */
		private const val LAND_AHEAD_TICKS = 12
		/** How often an obstacle still far with no way past yet is looked at again, see [lookedLately]. */
		private const val RELOOK_TICKS = 4
		/** How close below its height over a hole counts as up there, see [Hop.topped]. */
		private const val TOPPED_MARGIN = 0.1
		/** How often the holes ahead are looked at, see [planSpeed]. */
		private const val AHEAD_SCAN_TICKS = 4
		/** The fastest looked at for what a hole needs, a tick, for the debug log. Bouncing doesn't get you much faster. */
		private const val MAX_PLAN_SPEED = 2.2
		/** How far before braking would be needed to stop waiting to get fast enough to glide over, and dodge instead. */
		private const val HOP_RETRY_MARGIN = 24.0
		/** The fastest [holdPitch] climbs to the height held. Slower, a block up takes longer than it's held ahead. */
		private const val MAX_HOLD_CLIMB = 0.25
		private const val JUMP_VELOCITY = 0.42
		/**
		 * The speed a bounce adds, the jump sprinting: 0.17 to 0.19 a tick seen. Left out, a glide simulated past a bounce
		 * comes out too slow, e.g. over the rest of a hole after touching down on a pillar in it.
		 */
		private const val BOUNCE_SPEED_GAIN = 0.17
		/** How high a bounce takes your feet off the floor where nothing else caps it. */
		private const val MAX_BOUNCE_RISE = 2.0
		/**
		 * How far out of line a diagonal heading is tilted while passing an obstacle. The box swings out to the side the
		 * heading tilts to, so tilted away from the obstacle it never swings into it. Drifts a block over 115 ticks.
		 */
		private const val LEAN_DEGREES = 0.5
		/** How much of [LEAN_DEGREES] the flight has to have taken on, it turns a tenth of the way each tick. */
		private const val MIN_LEAN_DEGREES = 0.2
		/**
		 * How far the heading stays tilted once on a lane past carpets. Enough for the box to swing out only to that side,
		 * the larger of the two moves goes first, drifting a block over 300 ticks.
		 */
		private const val HOLD_LEAN_DEGREES = 0.25
		/** How long before the obstacle to tilt, for the flight to take it on. */
		private const val LEAN_TICKS = 20
		/** How far apart the rays across the way are, close enough that no block corner slips between them. */
		private const val RAY_SPACING = 0.25
		/** How far above two blocks over the floor the top of a bounce goes, bumping a roof there. */
		private const val ROOF_BUMP = 0.1
		/** How far around you entities as solid as blocks, e.g. boats, are looked for, as far as the planner looks. */
		private const val SOLID_ENTITY_RANGE = 128.0
		/** Faster than now a bit, for speeding up on the way. */
		private const val SWEEP_SPEED_MARGIN = 0.1
		/** How much faster than now a lane is picked for, bouncing over to it speeds you up, diagonally by a block a tick or so. */
		private const val LANE_SPEED_MARGIN = 0.5
		/** How far across the highway to look for its walls, to fly down its middle. */
		private const val MAX_CENTER_LANES = 8
		/** How many spots ahead the walls are looked for at, and how far apart, a block lying in the way at one isn't the wall. */
		private const val CENTER_SPOTS = 8
		private const val CENTER_SPOT_STEP = 2.0
		/** The line starts this far behind you, so its direction from the start stays the one you fly in once it's moved over. */
		private const val CENTER_BACKOFF = 16.0
		/**
		 * How far above the floor carpets are looked for, below their 0.0625. Bounced on like the floor, but coming down
		 * right before one its edge stops you dead, and the server sees that sooner than the client. So they're dodged.
		 */
		private const val CARPET_RAY = 0.01
		/**
		 * How long a stretch of carpets with no lane past it is glided over, holding the height. Just above them that
		 * costs little speed, walking a lot more.
		 */
		private const val MAX_CARPET_HOP_LENGTH = 48.0
		/**
		 * How far above the floor your feet glide over carpets at least, like over a block: not skimming them for long,
		 * only up over them from a bounce and down again.
		 */
		private const val CARPET_GLIDE_FEET = 0.2
		/**
		 * Bouncing on over carpets, how many ticks later holding the height would still get over them. Once it wouldn't
		 * it's held, only the bounce that would come down on them is stretched, not glided along low over them.
		 */
		private const val CARPET_HOLD_DELAY = 2
		/**
		 * How many ticks before carpets at most the height is held for them, about a bounce. Farther the glide to them is
		 * too long to tell, held from there it's glided along low all the way, slowing down.
		 */
		private const val MAX_CARPET_HOLD_TICKS = 16
		/** Never holding the height, for [canHop] bouncing on. */
		private const val NO_HOLD = Int.MAX_VALUE
		/**
		 * How far ahead carpets on the way are planned to be glided over, about a bounce, still in time to hold the height
		 * for them. Farther the bounces may well not come down on them.
		 */
		private const val CARPET_HOP_AHEAD = 24.0
		/** Carpets closer together than this many ticks of flight are glided over as one. */
		private const val CARPET_GAP_TICKS = 4
		/** How often the lane with the fewest carpets is looked for again for the same carpet ahead. */
		private const val CARPET_LANE_TICKS = 10
		/** How high carpets are, and so a layer of them to bounce on. */
		private const val CARPET_HEIGHT = 1.0 / 16
		/** How far on carpets have to lie with no gap and nothing on top, to bounce on like a layer. */
		private const val CARPET_LAYER_MIN = 8.0
		/** How far ahead of a spot to take off from there may be no carpets, for room to get up to speed. */
		private const val CARPET_TAKEOFF_CLEAR = 24.0
		/** How far on through carpets a spot to take off from is looked for, they may cover a long way. */
		private const val CARPET_WALK_REACH = 96.0
		/** How far past carpets a lane dodging them has to be free of anything else in the way. */
		private const val CARPET_CLEAR = 6.0
		/** How far past where you bumped into something to look at what it is. */
		private const val WALL_PROBE = 0.1
		/** How far below you a floor is looked for when starting in the air. */
		private const val MAX_FLOOR_DROP = 4.0
	}

	/** In the way [distance] ahead. A [mob] is dodged, gliding over or under it the glide only sees blocks. */
	private class Obstacle(val distance: Double, val mob: Boolean = false, describe: () -> String) {
		/** Only put together for the debug log, there's a hit for each ray. */
		val what by lazy(describe)
	}

	/**
	 * Gliding over an obstacle along the line from [start] to [end], the feet between [feetY] and [topY] meanwhile,
	 * held at [targetY]. The obstacle itself starts at [obstacle]. A [step] up onto a layer of blocks to bounce on, or
	 * gliding [down] off the end of one, [stepHeight] above the floor. On the [lane] that far across the line, dodging.
	 * Something [low], carpets or a hole, bounced on over unless a bounce would come down on it.
	 */
	private class Hop(
		val start: Double, val end: Double, val feetY: Double, val topY: Double, val obstacle: Double, val step: Boolean,
		val down: Boolean = false, val lane: Vec3d = Vec3d.ZERO, val stepHeight: Double = STEP_HEIGHT, val low: Boolean = false,
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
	) {
		val targetY = aim ?: when {
			under -> (feetY + topY) / 2
			hole -> topY - min(HOP_MARGIN, (topY - feetY) / 2)
			else -> feetY + min(HOP_MARGIN, (topY - feetY) / 2)
		}
		/** Changed so, how far it is glided over it kept, e.g. steering over to a lane mid-glide. */
		fun with(
			down: Boolean = this.down, lane: Vec3d = this.lane, stepHeight: Double = this.stepHeight, touch: Touch? = this.touch,
			topY: Double = this.topY,
		) = Hop(start, end, feetY, topY, obstacle, step, down, lane, stepHeight, low, under, hole, open, cube, anyway, aim, highAim, raised, touch).also {
				it.waiting = waiting
				it.held = held
				it.heldCheckedAt = heldCheckedAt
				it.topped = topped
				it.rose = rose
				it.touchedAt = touchedAt
			}
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
		/** Over [touch] once, logged once. */
		var overTop = false
		/** The tick it touched down on [touch], -1 before. */
		var touchedAt = -1
		val touched get() = touchedAt >= 0
		/**
		 * The height to hold from [y]: [targetY], over a hole once [topped] only as high as it is. Pulling back up there
		 * costs the speed that carries it to the floor after the hole, it sinks all the more after.
		 */
		fun holdY(
			y: Double, along: Double, speed: Double, topped: Boolean = this.topped, touched: Boolean = this.touched,
			dive: Boolean = diving(along, speed, touched),
		) = when {
			// Just above the top to touch down on until a tick before its middle, then down onto it.
			touch != null && !touched -> if (dive) touch.y - TOUCH_DIVE else touch.y + TOUCH_ABOVE
			hole && topped -> min(targetY, max(y, needFrom(along) + HOP_MARGIN))
			else -> targetY
		}
		/** How fast it may sink at [along] the line: over the top to touch down on fast, down onto it while over it. */
		fun sinkAt(along: Double, speed: Double, touched: Boolean = this.touched, dive: Boolean = diving(along, speed, touched)) =
			if (dive) TOUCH_SINK else HOLD_SINK
		/**
		 * Diving onto the top to touch down on, from [TOUCH_DIVE_TICKS] before its middle at [speed]: the glide takes that
		 * long to sink, and it comes down on the middle. On its edge it may slide off it in the same tick.
		 */
		fun diving(along: Double, speed: Double, touched: Boolean = this.touched) =
			touch != null && !touched && along >= touch.middle - speed * TOUCH_DIVE_TICKS && along <= touch.range.endInclusive
		/** How high the feet have to be at [along] the line. Over the top to touch down on, down on it. */
		fun feetAt(along: Double) = if (touch != null && along in touch.range) touch.y
			else raised.filter { along in it.first }.maxOfOrNull { it.second }?.let { max(feetY, it) } ?: feetY
		/** How high the feet have to stay from [along] on until past the last of [raised]. */
		fun needFrom(along: Double) = raised.filter { it.first.endInclusive >= along }.maxOfOrNull { it.second }?.let { max(feetY, it) } ?: feetY
	}

	/**
	 * A top at [y] to touch down on and bounce off, under the box from where to where along the line in [range], on the
	 * [lane] that far across the line. Aimed at its [middle], see [Hop.diving].
	 */
	private class Touch(val range: ClosedFloatingPointRange<Double>, val y: Double, val lane: Vec3d = Vec3d.ZERO) {
		val middle = (range.start + range.endInclusive) / 2
	}

	abstract val passerConfig: PasserSettings

	var startPos: Vec3d = Vec3d.ZERO
	/** The floor you started on. The line goes up onto a layer of blocks [STEP_HEIGHT] high on it, or of carpets, and back down. */
	private var baseY = 0.0
	var passingToPos: Vec3d? = null
	private var failedPasses = 0
	private var passCooldown = 0

	/** Across the flight line, as far as the free lane is that's glided on past a blocked spot. Zero when swinging back. */
	private var dodge: Vec3d? = null
	/** How far along the line the obstacle ends, as far as known. */
	private var dodgeEnd = 0.0
	/** Where along the line an obstacle is whose dodge got set back, which isn't dodged again. */
	private var skipAt: Double? = null
	private var dodgeTicks = 0
	private var dodgeYaw: Double? = null
	/** Which way across the line to tilt the heading passing the obstacle, 0 for none. */
	private var dodgeLean = 0
	/** Dodging carpets, back onto the line only once it's free of them too. */
	private var dodgeCarpets = false
	/** How far across the line the lane is that's dodged to, [dodge] short of it by the drift of the tilt. */
	private var dodgeOffset = 0.0
	/** Looking for the lane with the fewest carpets, at all lanes, now and then only for the same one. */
	private val fewestCarpetsLane = Limiter(CARPET_LANE_TICKS)
	/** Looking for a lane free of carpets when they can't be glided over, likewise. */
	private val freeOfCarpetsLane = Limiter(CARPET_LANE_TICKS)

	/** Lets something through right away for a new [key], for the same again only every [ticks] times it's asked. */
	private class Limiter(val ticks: Int) {
		private var key = ""
		private var wait = 0
		fun allows(key: String): Boolean {
			if (key == this.key && --wait > 0) return false
			this.key = key
			wait = ticks
			return true
		}
	}

	/**
	 * Where across the line to steer to get past an obstacle, tilting the heading [lean] across it on the way past. With
	 * [carpets] that many on it ahead, and whether it [stays] on the lane you're on. [offset] across the line it is, the
	 * target short of it by the drift of the tilt.
	 */
	private class Lane(val target: Vec3d, val lean: Int, val offset: Double, val carpets: Int = 0, val stays: Boolean = false)

	/** Where along the line an obstacle is that no lane gets past, landing to walk around it. */
	private var brakeAt: Double? = null
	/** Landing for carpets, walking on to where there are none ahead, to take off clear of them. */
	private var brakeCarpets = false
	/** The height bounced on when it started landing, landed up on a layer it may bounce on there instead. */
	private var brakeY = 0.0

	private var hop: Hop? = null
	/** Down from a brake, the glide over, walking may start. */
	private var landed = false
	/** Walking stopped for eating, to go on with once done. */
	private var pausedWalk = false
	/** The spot last walked to, and how often in a row it was picked. */
	private var lastGoal: BlockPos? = null
	private var sameGoals = 0
	private var hopRejection = ""
	/** Why [freeLane] turned down each lane, for the debug log. */
	private var laneMiss = ""
	/** How many carpets [freeLane] counted on each lane it took, for the debug log. */
	private var laneCarpets = ""
	/** Whether a [planHop] since [startDodge] began found a way over but you're too slow to make it yet. */
	private var hopTooSlow = false
	/** Where [canHop] found the glide falls short, for the debug log. */
	private var hopMiss = ""
	/** Why the last [withTouch] doesn't touch down on a top, for the debug log. */
	private var touchMiss = ""
	/** How far below what it needs the last simulation came out, infinite missing it otherwise. */
	private var hopShort = Double.POSITIVE_INFINITY
	/** The speed a tick [canHop] found the glide is past the hop at, when it makes it. */
	private var hopExitSpeed = 0.0
	/** How long you've been down after braking, waiting for the server to end the glide. */
	private var landedTicks = 0
	/** Where along the line an obstacle is that a lane gets past only slower, see [slowingToFit]. */
	private var slowFor: Double? = null
	/**
	 * Slowing down to [FIT_SPEED] for a lane past an obstacle ahead: diagonally the box swings out across the way the
	 * farther the faster, at speed it may not fit between it and the wall beside it.
	 */
	protected val slowingToFit get() = slowFor != null
	/** Dodging on a lane that only fits slower, see [slowingToFit], until past what's dodged. */
	private var fitDodge = false
	/** On it at [FIT_SPEED] already, flying level so it doesn't get faster, slower it bounces on. */
	private var atFitSpeed = false
	/** Over a hole, steering over to floor beside it to land there, see [landBeside]. */
	private var landing = false
	/** Why [throughOpening] found no opening, for the log. */
	private var openingMiss = ""
	/** Where along the line an obstacle is that no lane gets past yet, for logging it once. */
	private var noLaneAt: Double? = null
	/** Where along the line obstacles and holes with no way past yet are, and the [passTicks] each was looked at. */
	private val noWayYet = HashMap<Long, Int>()
	/** Ticks obstacles were passed in, a clock for [lookedLately]. */
	private var passTicks = 0
	/** Where along the line an obstacle is that got too close to get over, which is dodged instead. */
	private var skipHopAt: Double? = null
	/** Where along the line the layer you bounce on ends with no gliding across to more of it, not to look again. */
	private var noGapAt: Double? = null

	/**
	 * Steering past a blocked spot, flying level meanwhile so it doesn't speed into it. Not once over on the lane, it
	 * bounces on there to get back the speed the swing over cost, e.g. for a hole right after, the lane is looked at
	 * again every tick at the speed you have. Not past carpets, there's nothing to speed into and it's planned for
	 * bouncing on. Not on the way to gliding over something, it's planned for bouncing on up to it.
	 */
	protected val isDodging get() = dodge != null && !dodgeCarpets && hop == null && (atFitSpeed || !onLane && !speedingUp)
	/** Over on the lane dodged to and not tilting past what's dodged yet, see [steerDodge]. */
	private var onLane = false
	/** Ticks since what's ahead was last looked at, see [planSpeed]. */
	private var scanTicks = 0
	/** Too slow to glide over the next hole on the way, see [planSpeed]. */
	private var wantsSpeed = false
	/** Where that hole starts, for logging each once. */
	private var speedNeedAt = Double.NaN
	/** Bouncing on while swinging over to a lane too, too slow for the hole ahead, see [planSpeed]. */
	private var speedingUp = false
	/** Dodging what's right after a mob glided over, the lane doesn't keep clear of the mob, see [pastMobAndNext]. */
	private var dodgeAfterMob = false
	/** Landing before an obstacle no lane gets past, to walk around it. */
	protected val isBraking get() = brakeAt != null
	/** Found no way past, standing still a while before looking again. */
	protected val isWaiting get() = passCooldown > 0

	/** Bouncing on a layer of blocks on the floor you started on. */
	protected val onLayer get() = startPos.y > baseY + 0.01
	/** How high the layer you bounce on is, 0 down on the floor. */
	protected val layerHeight get() = startPos.y - baseY
	/** Bouncing on a layer of carpets. */
	private val onCarpets get() = onLayer && layerHeight < CARPET_HEIGHT + 0.01
	/** Whether carpets are in the way at all, with obstacles counted from as low as them they're like any other block. */
	private val carpetsCount get() = passerConfig.minObstacleHeight > CARPET_RAY

	/** The highest your feet may go gliding, a roof above the obstacle aside. */
	protected open fun SafeContext.maxFlightY() = Double.POSITIVE_INFINITY

	/** The highest your feet may go gliding over an obstacle, a short while only. */
	protected open fun SafeContext.maxHopY() = maxFlightY()

	/** The pitch bouncing on is flown at, not holding the height, from a y climbing that much. Like smart pitch by default. */
	protected open fun SafeContext.bouncePitch(): (y: Double, climb: Double) -> Double = { _, climb -> if (climb > -0.2) 90.0 else 4.0 }

	/** The pitch holding you above the obstacle you glide over instead of bouncing, null when there's none. */
	protected fun SafeContext.hopPitch(): Double? = timed {
		val hop = hop ?: return@timed null
		// Down on the top to touch down on, the bounce jumps off it level like off the floor.
		hop.touch?.let { touch ->
			if (player.isOnGround && abs(player.y - touch.y) < 0.01) {
				if (!hop.touched) ElytraFly.debug { "Touched down on it at y %.2f, bouncing off it".format(touch.y) }
				hop.touchedAt = player.age
				hop.topped = true
				return@timed null
			}
		}
		if (!holdingFor(hop, progress(player.pos))) return@timed null
		// Along the line it's time to dive, only with the top really under the box soon: beside it, e.g. steered over not
		// quite far enough, diving sinks you next to it into the hole.
		val dive = hop.touch?.takeIf { !hop.touched }?.let { touch ->
			hop.diving(progress(player.pos), player.velocity.horizontalLength()) && topUnderSoon(touch.y)
		} == true
		if (dive && !hop.overTop) {
			hop.overTop = true
			ElytraFly.debug { "Over the top to touch down on at y %.2f climbing %.2f, diving onto it at y %.2f".format(player.y, player.velocity.y, hop.touch.y) }
		}
		// Down on the floor the bounce jumps this tick too. Pulling up on top of that throws you up into the roof.
		val climb = if (player.isOnGround) JUMP_VELOCITY else player.velocity.y
		// Switched to gliding over it higher just now, see [holdingFor].
		val held = this@ObstaclePassingMode.hop ?: hop
		if (held.hole && (player.y >= held.targetY - TOPPED_MARGIN || held.rose && climb <= 0)) held.topped = true
		if (climb > 0) held.rose = true
		val progress = progress(player.pos)
		val speed = player.velocity.horizontalLength()
		holdPitch(player.y, climb, speed, held.holdY(player.y, progress, speed, dive = dive), player.finalGravity, held.sinkAt(progress, speed, dive = dive))
	}

	/**
	 * Whether the box comes over a top at [y] the next tick or the one after: a block there ending at [y] with air above
	 * it, not a block reaching higher, e.g. a pillar on the way it'd dive into.
	 */
	private fun SafeContext.topUnderSoon(y: Double) = (1..2).any { ticks ->
		val at = player.pos.add(player.velocity.x * ticks, 0.0, player.velocity.z * ticks)
		listOf(-1, 1).any { dx -> listOf(-1, 1).any { dz ->
			val x = at.x + dx * (GLIDE_SIZE / 2 - 0.01)
			val z = at.z + dz * (GLIDE_SIZE / 2 - 0.01)
			shapeAt(BlockPos.ofFloored(x, y - 0.5, z)) === VoxelShapes.fullCube() && shapeAt(BlockPos.ofFloored(x, y + 0.5, z)).isEmpty
		} }
	}

	/** How long obstacle passing took this tick so far. */
	private var passingNanos = 0L

	private inline fun <T> timed(block: () -> T): T {
		val started = System.nanoTime()
		try {
			return block()
		} finally {
			passingNanos += System.nanoTime() - started
		}
	}

	init {
		onEnable {
			startPos = floorBelow()
			baseY = startPos.y
			if (passerConfig.passObstacles) centerLine()
			failedPasses = 0
			passCooldown = 0
			skipAt = null
			skipHopAt = null
			noGapAt = null
			noLaneAt = null
			landedTicks = 0
			lastGoal = null
			sameGoals = 0
			scanTicks = 0
			noWayYet.clear()
			slowFor = null
			forgetSpeedNeed()
		}
		onDisable {
			passingToPos = null
			pausedWalk = false
			if (passerConfig.passObstacles) BaritoneHandler.cancel()
			stopDodge()
			brakeAt = null
			hop = null
			dropShapes()
		}
		listen<TickEvent.Pre>({ DODGE_PRIORITY }) {
			if (passingNanos > SLOW_PASSING_NANOS) ElytraFly.debug { "Obstacle passing took %.1f ms last tick".format(passingNanos / 1e6) }
			passingNanos = 0
			val yaw = dodgeYaw?.takeIf { player.isGliding && passerConfig.passObstacles && passerConfig.dodge } ?: return@listen
			rotationRequest { yaw(yaw) }.submit()
		}
		onStuck {
			passingToPos = null
			pausedWalk = false
			brakeAt = null
			lastGoal = null
			sameGoals = 0
			BaritoneHandler.cancel()
		}
		// Setbacks come in on the network thread, what's planned is changed on the game thread only.
		onFlag { runGameScheduled { flagged() } }
	}

	/** Set back, what it was at isn't tried again, and with [PasserSettings.walkWhenFlagged] it lands to walk on. */
	private fun SafeContext.flagged() {
		hop?.let {
			ElytraFly.debug { "Set back while gliding over an obstacle" }
			skipHopAt = it.start
			hop = null
		}
		if (dodge != null) {
			ElytraFly.debug { "Set back while dodging" }
			skipAt = dodgeEnd
			stopDodge()
		}
		if (!passerConfig.passObstacles || !passerConfig.walkWhenFlagged || passCooldown > 0) return
		// Landing first like for an obstacle, walking off still gliding gets set back again and again.
		brake(progress(player.pos) + passerConfig.obstacleLookAhead + 1, "Flagged")
	}

	fun SafeContext.handlePassingObstacles() = timed { passObstacles() }

	private fun SafeContext.passObstacles(): Boolean {
		passTicks++
		// Steered anew each tick below, a yaw left over would hold you on an old heading.
		dodgeYaw = null
		if (slowFor?.let { progress(player.pos) > it } == true || !player.isGliding) slowFor = null
		// Past it, or on a lane or landing for it.
		if (noLaneAt?.let { progress(player.pos) > it } == true || dodge != null || brakeAt != null || !player.isGliding) noLaneAt = null
		if (!BaritoneHandler.baritoneAvailable) {
			logError("Obstacle passing requires baritone to be installed!")
			ElytraFly.disable()
			return true
		}

		// Eating slows walking to a crawl and stops the sprint, walking on meanwhile the server sets you back every step.
		passingToPos?.let { passingTo ->
			if (player.isUsingItem) {
				if (BaritoneHandler.isActive) {
					ElytraFly.debug { "Eating, waiting to walk on" }
					BaritoneHandler.cancel()
				}
				pausedWalk = true
				return true
			}
			if (pausedWalk) {
				pausedWalk = false
				BaritoneHandler.setGoalAndPath(GoalBlock(passingTo.flooredBlockPos))
				return true
			}
		}

		if (!BaritoneHandler.isActive) passingToPos?.let { passingTo ->
			passingToPos = null
			// Baritone stopped short of it, e.g. it found no path. Not when still moving fast enough to overshoot it.
			val reached = passingTo.flooredBlockPos.getSquaredDistance(player.blockPos) <= 2 ||
				passingTo.subtract(player.pos).dotProduct(getSnappedDir()) <= 0
			if (!reached) {
				ElytraFly.debug { "Baritone stopped short of ${passingTo.flooredBlockPos.toShortString()}" }
				passFailed()
			} else failedPasses = 0
		}

		if (!passerConfig.passObstacles) return false
		if (passCooldown > 0) {
			passCooldown--
			return false
		}

		val playerPos = player.pos
		val validDistanceFromStart = Vec3d(playerPos.x, startPos.y, playerPos.z) dist startPos > 0.1
		if (!validDistanceFromStart) return false

		// Down on the floor or up on a layer of blocks on it, the line follows to where you bounce. Not while walking,
		// the way there was picked on the line as it was.
		if (player.isOnGround && passingToPos == null && hop == null)
			// Up onto a layer only bouncing, standing on one after walking you take off from the floor.
			listOf(baseY, baseY + STEP_HEIGHT, baseY + CARPET_HEIGHT).firstOrNull { abs(playerPos.y - it) < 0.01 && abs(startPos.y - it) > 0.01 && (it == baseY || player.isGliding) }?.let { level ->
				ElytraFly.debug { if (level > startPos.y) "Up on the blocks, bouncing on at y %.2f".format(level) else "Back down, bouncing on at y %.2f".format(level) }
				startPos = Vec3d(startPos.x, level, startPos.z)
			}

		val snappedDir = getSnappedDir()
		var closestLinePoint = playerPos.findClosestPointOnLine(snappedDir)

		passingToPos?.let { passingTo ->
			if (passingTo distSq startPos < playerPos distSq startPos) {
				val atClosestPointBlockPos = closestLinePoint.flooredBlockPos == player.blockPos
				if (!atClosestPointBlockPos) pathToValidPoint(closestLinePoint, snappedDir, false)
				else {
					BaritoneHandler.cancel()
					passingToPos = null
					return@let
				}
			}
			// Judged the way it was picked, a spot to stand with a few free after it.
			if (passingTo.isObstructed(snappedDir, WALK_GOAL_CLEAR) || !passingTo.canStand()) {
				ElytraFly.debug { "Goal ${passingTo.flooredBlockPos.toShortString()} is ${passingTo.obstruction(snappedDir, WALK_GOAL_CLEAR)}" }
				return pathToValidPoint(passingTo, snappedDir)
			}
			return true
		}

		if (passerConfig.dodge && player.isGliding && !onLayer) planSpeed(closestLinePoint, snappedDir) else forgetSpeedNeed()

		// Over the floor beside the hole now, down onto it.
		// Gliding over it after all, e.g. once off the layer a tick later, it's not landed beside.
		if (hop != null) landing = false
		if (landing && player.isGliding && !player.isOnGround && floorBelowYou()) {
			landing = false
			brake(progress(playerPos) + 1, "Over the floor beside the hole")
		}

		brakeAt?.let { at ->
			if (!player.isOnGround) return false
			// The client keeps gliding until the server ends it on seeing you down. Walking before that, you slide on
			// with the elytra, faster than walking, and get set back.
			if (player.getFlag(Entity.GLIDING_FLAG_INDEX) && ++landedTicks < MAX_LANDING_TICKS) return false
			if (landedTicks >= MAX_LANDING_TICKS) ElytraFly.debug { "Still gliding after landing, walking anyway" }
			landedTicks = 0
			brakeAt = null
			// Came down on top of a layer, e.g. one right after a hole, with the way on up there free: bouncing on it.
			if (startPos.y > brakeY + 0.01 && !Vec3d(playerPos.x, closestLinePoint.y, playerPos.z).isObstructed(snappedDir)) {
				ElytraFly.debug { "Landed up on the blocks, the way on is free up there, bouncing on" }
				return false
			}
			// From right before the obstacle, as that may still be a way ahead.
			val beforeIt = closestLinePoint.add(snappedDir.multiply(at - progress(closestLinePoint) - 1))
			landed = true
			return pathToValidPoint(beforeIt, snappedDir, carpets = brakeCarpets).also { landed = false }
		}

		// Bumped into something after all, the hit turns the flight sideways into the next wall. Walking on is safer.
		// Not a carpet's edge coming down onto the floor right before it, it's bounced on like the floor.
		if (passerConfig.dodge && player.isGliding && player.horizontalCollision && !bumpedLowEdge(snappedDir))
			return brake(progress(playerPos) + 1, "Hit a wall")
		hop?.let { hop ->
			val progress = progress(playerPos)
			// Planned slow, right after taking off, the box swings out farther now at speed, into blocks beside the line.
			// Not the obstacle itself: its edge runs askew diagonally, the swing reaches its corner up to that much sooner.
			val before = hop.obstacle - progress - halfWidth(snappedDir) - sweep(snappedDir) - HOP_SCAN_STEP
			// On the lane dodged to, looked at like the dodge does: from the lane itself, past what's dodged, tilted past it
			// the box swings out only that side. From where you are, on a narrow lane its edges would be in the way.
			val onDodgeLane = dodge.let { it != null && it != Vec3d.ZERO && hop.lane == it }
			val tilt = if (onDodgeLane && leaning(progress)) dodgeLean else 0
			val lookFrom = if (onDodgeLane) dodgeLanePoint(closestLinePoint, snappedDir)
				else Vec3d(playerPos.x, closestLinePoint.y, playerPos.z)
			val skip = if (onDodgeLane) (dodgeEnd + DODGE_MARGIN - progress).coerceAtLeast(0.0) else 0.0
			val inTheWay = if (before > skip) lookFrom.add(snappedDir.multiply(skip)).obstacleHit(snappedDir, before - skip, lean = tilt) else null
			// A simulation of the glide, once a tick is enough.
			// No floor in sight, it glides on as far as it can anyway.
			// Over a magma cube once held it climbs on anyway, missing it by a bit it bumps into it higher up at worst.
			val makesIt by lazy { hop.open || hop.anyway || hop.cube && hop.held || canGetOver(hop, progress) }
			when {
				// Touching down on a top ends the glide until the bounce off it takes off again.
				!player.isGliding && !(hop.touched && player.age - hop.touchedAt <= TOUCH_GRACE_TICKS) -> this@ObstaclePassingMode.hop = null
				inTheWay != null -> {
					ElytraFly.debug { "In the way %.1f ahead before the obstacle at this speed (${inTheWay.what}), not gliding over it".format(inTheWay.distance + skip) }
					this@ObstaclePassingMode.hop = null
				}
				progress > hop.end -> {
					// Above the layer now, from the line down on the floor it'd be in the way right where you are.
					when {
						hop.step -> {
							ElytraFly.debug { "${if (onLayer) "Across the gap" else "Up on the blocks"}, bouncing on at y %.2f".format(baseY + hop.stepHeight) }
							startPos = Vec3d(startPos.x, baseY + hop.stepHeight, startPos.z)
						}
						// Past the edge, from the line up there the end of the layer would be ahead again and again.
						hop.down -> offTheLayer()
						else -> ElytraFly.debug { "Over it" }
					}
					this@ObstaclePassingMode.hop = null
				}
				// Still far, not holding yet: a few bounces on, faster, it may make it after all, e.g. right after taking off.
				!holdingFor(hop, progress) && hop.start - progress > brakeDistance() && !makesIt -> {
					if (!hop.waiting) ElytraFly.debug { "Can't get over the obstacle at %.1f yet ($hopMiss), bouncing on to look again".format(hop.start - progress) }
					hop.waiting = true
					dodgeYaw = hopYaw(closestLinePoint, hop, snappedDir, progress)
					return false
				}
				// A magma cube isn't dodged, it's looked at again from the next bounce on until it's close.
				!makesIt && hop.cube && hop.start - progress > player.velocity.horizontalLength() * HOP_LEAD_TICKS -> {
					noLaneYet(hop.obstacle, "Can't get over the mob at %.1f from here ($hopMiss), bouncing on to look again".format(hop.start - progress))
					this@ObstaclePassingMode.hop = null
					// The lane past what's after it doesn't keep clear of it, see [pastMobAndNext].
					if (dodgeAfterMob) stopDodge()
				}
				// A hole this close there's no landing before, and dropping the glide leaves you lost over it. Missing a
				// block by a hair, braking this close runs into it all the same. As high as it goes, the simulation misses
				// by a little at times where the flight doesn't.
				!makesIt && (hop.hole || !hop.under && !hop.low && hopShort <= ANYWAY_SHORT) -> {
					if (!hop.held) ElytraFly.debug { "Can't quite get over it at %.1f ($hopMiss), holding as high as it goes anyway".format(hop.start - progress) }
					hop.held = true
					hop.heldCheckedAt = player.age
					dodgeYaw = hopYaw(closestLinePoint, laneFromGlide(hop, closestLinePoint, snappedDir, progress) ?: hop, snappedDir, progress)
					return false
				}
				!makesIt -> {
					ElytraFly.debug { "Can't get over the obstacle at %.1f in time ($hopMiss), dodging instead".format(hop.start - progress) }
					skipHopAt = hop.start
					this@ObstaclePassingMode.hop = null
					if (dodgeAfterMob) stopDodge()
				}
				else -> {
					val gliding = laneFromGlide(hop, closestLinePoint, snappedDir, progress) ?: hop
					dodgeYaw = hopYaw(closestLinePoint, gliding, snappedDir, progress)
					return false
				}
			}
		}
		// Up onto a layer or off it just now, the line is at its new height.
		closestLinePoint = playerPos.findClosestPointOnLine(snappedDir)
		// Dodging up on a layer, its end on the lane is glided off like on the line, or landed before for a hole after it
		// that's too long.
		dodge?.let { lane -> if (onLayer && planGap(closestLinePoint, snappedDir, lane)) return false }
		if (dodge != null && steerDodge(closestLinePoint, snappedDir)) return false
		if (isBraking) return false
		// Far ahead while gliding, for time to steer. Close by on the ground below, for walking around it.
		if (passerConfig.dodge && player.isGliding) {
			if (onLayer && dodge == null && planGap(closestLinePoint, snappedDir)) return false
			// Off the end of the layer just now, from the floor. Up there nothing's under a lane beside it.
			closestLinePoint = playerPos.findClosestPointOnLine(snappedDir)
			val here = Vec3d(playerPos.x, closestLinePoint.y, playerPos.z)
			// Carpets too, and on its own what else is in the way, looked for at once.
			val (low, block) = here.obstacleHits(snappedDir, passerConfig.dodgeLookAhead.toDouble(), carpets = carpetsCount)
			val ahead = block?.distance
			// A hole before anything in the way, carpets too. Up on a layer its end is looked for instead.
			if (!onLayer && glideOverHole(here, closestLinePoint, snappedDir, low?.distance)) return false
			ahead?.let { distance ->
				if (startDodge(here, closestLinePoint, snappedDir, distance, block.mob)) return false
			}
			// A hole right ahead was left to dodging what's after it, which didn't happen, e.g. no lane yet: over the
			// hole anyway rather than into it, what's after it is looked at again from there.
			if (!onLayer && dodge == null && hop == null && brakeAt == null &&
				glideOverHole(here, closestLinePoint, snappedDir, low?.distance, within = brakeDistance() + DODGE_STEER_AHEAD)) return false
			if (ahead == null && low != null && dodgeCarpets(here, closestLinePoint, snappedDir, low)) return false
			// Holding the yaw alone drifts off the line, half a block over a couple hundred, into blocks beside it.
			dodgeYaw = steerYaw(closestLinePoint, Vec3d.ZERO, snappedDir)
		}

		if (!player.isOnGround) return false

		val notProgressing = Speedometer.calculateSpeed(true, SpeedUnit.BlocksPerSecond) < 0.01
		if (player.isGliding && notProgressing) {
			ElytraFly.debug { "Not moving while gliding" }
			return pathToValidPoint(closestLinePoint, snappedDir)
		}

		val xy = Vec3d(playerPos.x, closestLinePoint.y, playerPos.z)

		// We only want to account for horizontal and below the line rather than total
		// distance as jumping from bounce might cause false positives
		val distanceToLine = xy
			.dist(closestLinePoint) + (playerPos.y - closestLinePoint.y)
			.coerceAtMost(0.0)
		if (distanceToLine > passerConfig.acceptableOffsetRange) {
			ElytraFly.debug { "%.2f blocks off the flight line from ${startPos.flooredBlockPos.toShortString()}".format(distanceToLine) }
			return pathToValidPoint(closestLinePoint, snappedDir, false)
		}

		if (xy.isObstructed(snappedDir)) {
			ElytraFly.debug { "Way ahead is ${xy.obstruction(snappedDir)}, line y %.2f".format(startPos.y) }
			// From where it's in the way on, before it the first spot to stand is right here.
			val blocked = xy.obstacleDistance(snappedDir, passerConfig.obstacleLookAhead.toDouble()) ?: 0.0
			return pathToValidPoint(closestLinePoint.add(snappedDir.multiply(blocked)), snappedDir)
		}

		if (BaritoneHandler.isActive) return true

		return false
	}

	/** Glides over to a free lane past the obstacle [distance] ahead, or lands to walk around it when there's none. */
	private fun SafeContext.startDodge(from: Vec3d, linePoint: Vec3d, dir: Vec3d, distance: Double, mob: Boolean = false): Boolean {
		val at = progress(from) + distance
		if (lookedLately(at, distance)) return false
		val block = from.obstacleBlock(dir, distance + 1)
		if (mob) return passCube(from, linePoint, dir, distance, at, block)
		hopRejection = "it got too close to get over before"
		hopTooSlow = false
		if (!skipHopAt.near(at) && isStepUp(from, dir, distance)) planHop(linePoint, dir, distance, true)?.let {
			if (dodgePastNext(from, linePoint, dir, distance, block, it)) return true
			ElytraFly.debug { "Obstacle %.1f ahead ($block), a layer to bounce on, gliding up onto it at y %.2f".format(distance, it.feetY) }
			hop = it
			return true
		}
		if (!skipHopAt.near(at)) planHop(linePoint, dir, distance)?.let { planned ->
			val over = if (planned.under) planned else withHoleAfter(planned, linePoint, dir) ?: withTouch(planned, linePoint, dir)
			if (dodgePastNext(from, linePoint, dir, distance, block, over)) return true
			ElytraFly.debug {
				val how = over.touch?.let { "touching down on it at y %.2f".format(it.y) }
					?: "gliding ${if (over.under) "under" else "over"} it at y %.2f${if (over.under || touchMiss.isEmpty()) "" else " ($touchMiss)"}".format(over.targetY)
				"Obstacle %.1f ahead ($block), $how${if (over.hole) " and the hole after it" else ""}, %.1f long".format(distance, over.end - over.start)
			}
			hop = over
			return true
		}
		if (distance < MIN_DODGE_DISTANCE || skipAt.near(at)) return false
		// Slow still, e.g. right after taking off. Faster a few bounces on it may get over after all, a lane is
		// left for close enough to still steer over to it.
		if (hopTooSlow && distance > brakeDistance() + HOP_RETRY_MARGIN)
			return noLaneYet(at, "Obstacle %.1f ahead ($block), can't get over it yet ($hopMiss), bouncing on to look again".format(distance))
		// Steered past over a hole before it, from the line.
		if (!onLayer && distance > brakeDistance() + DODGE_STEER_AHEAD && dodgeOverHole(linePoint, dir, distance))
			return noLaneYet(at, "Obstacle %.1f ahead ($block), not gliding over it ($hopRejection), steering past it over the hole before it".format(distance))
		val lane = freeLane(from, linePoint, dir, distance, Vec3d.ZERO)
			?: run {
				// Looking for an opening glides over it on each lane, that'd replace why not on the line.
				val notOver = hopRejection
				val noLane = laneMiss
				if (throughOpening(from, linePoint, dir, distance, block)) return true
				// One fits slower, it slows down first.
				if (distance > brakeDistance() + DODGE_STEER_AHEAD && player.velocity.horizontalLength() > FIT_SPEED &&
					freeLane(from, linePoint, dir, distance, Vec3d.ZERO, speed = FIT_SPEED) != null) {
					slowFor = at
					return noLaneYet(at, "Obstacle %.1f ahead ($block), not gliding over it ($notOver), no free lane at this speed ($noLane), slowing down to %.2f to fit one".format(distance, FIT_SPEED))
				}
				return if (distance > brakeDistance()) noLaneYet(at, "Obstacle %.1f ahead ($block), not gliding over it ($notOver), no free lane to dodge to yet ($noLane), no opening in it ($openingMiss)".format(distance))
				else brake(at, "Obstacle %.1f ahead ($block), not gliding over it ($notOver), %.2f off the line, no free lane to dodge to ($noLane), no opening in it ($openingMiss)".format(distance, from.distanceTo(linePoint)))
			}
		ElytraFly.debug { "Obstacle %.1f ahead ($block), not gliding over it ($hopRejection), dodging ${lane.target.describe(dir)}${lane.leanText()}".format(distance) }
		return dodgeTo(lane, at)
	}

	/**
	 * Over the magma cube [distance] ahead, glided over on the line at full speed like a block two high, see [cubeBoxes].
	 * Up on the railing over the way, glided under like a block hanging there, low over the floor. Never dodged, it
	 * jumps at you, nor walked around, it'd come at you over and over. Not getting past it, flown on through it,
	 * bumping into it in the air hurts a little at worst.
	 */
	private fun SafeContext.passCube(from: Vec3d, linePoint: Vec3d, dir: Vec3d, distance: Double, at: Double, block: String): Boolean {
		if (skipHopAt.near(at)) return false
		hopTooSlow = false
		fun plan(anyway: Boolean = false) = planHop(linePoint, dir, distance, avoid = cubeBoxes(from, distance + MAX_HOLE_LENGTH), anyway = anyway)
		if (pastMobAndNext(from, linePoint, dir, distance, block)) return true
		plan()?.let { over ->
			// With room above, e.g. a highway without pavement under its ceiling, held in the middle of it when fast enough.
			val highAim = ((over.feetY + over.topY) / 2).takeIf { !over.under && it > over.targetY }
			ElytraFly.debug {
				"Mob %.1f ahead ($block), gliding ${if (over.under) "under" else "over"} it at y %.2f${highAim?.let { ", %.2f if fast enough".format(it) } ?: ""}, %.1f long"
					.format(distance, over.targetY, over.end - over.start)
			}
			hop = Hop(over.start, over.end, over.feetY, over.topY, over.obstacle, over.step, under = over.under, cube = true, highAim = highAim)
			return true
		}
		// Slow still, e.g. right after taking off. Faster a few bounces on, or from the next bounce, it may get over after
		// all. Or it's moved, it hops around. There's nothing to brake for, it's looked at again until it's close.
		if (distance > player.velocity.horizontalLength() * HOP_LEAD_TICKS)
			return noLaneYet(at, "Mob %.1f ahead ($block), can't get over it yet (${if (hopTooSlow) hopMiss else hopRejection}), bouncing on to look again".format(distance))
		skipHopAt = at
		// Too slow only, it gets as close to the height as it can anyway. Maybe just past it, else bumping into it less.
		if (hopTooSlow) plan(anyway = true)?.let { over ->
			ElytraFly.debug { "Mob %.1f ahead ($block), can't get ${if (over.under) "under" else "over"} it in time ($hopRejection), going for it anyway".format(distance) }
			hop = Hop(over.start, over.end, over.feetY, over.topY, over.obstacle, over.step, under = over.under, cube = true, anyway = true)
			return true
		}
		ElytraFly.debug { "Mob %.1f ahead ($block), not gliding over it ($hopRejection), flying on through it".format(distance) }
		return false
	}

	/**
	 * A block in the way right after the mob [distance] ahead, once over the mob too close to steer past, e.g. at the end
	 * of a hole there's no landing in. Glided over with the mob it'd be one obstacle too high to get over. Past both on a
	 * lane that keeps out of the mob's reach, else over the mob on a lane past the block, gliding over it there where the
	 * block isn't in the way, or on the line while steering over. Whether it's on its way past them.
	 */
	private fun SafeContext.pastMobAndNext(from: Vec3d, linePoint: Vec3d, dir: Vec3d, distance: Double, block: String): Boolean {
		val next = from.add(dir.multiply(distance)).obstacleHit(dir, brakeDistance() + DODGE_STEER_AHEAD, mobs = false) ?: return false
		val end = distance + next.distance
		val at = progress(from)
		freeLane(from, linePoint, dir, end, Vec3d.ZERO)?.let { lane ->
			ElytraFly.debug { "Mob %.1f ahead ($block), ${next.what} %.1f after it, dodging past both ${lane.target.describe(dir)}${lane.leanText()}".format(distance, next.distance) }
			return dodgeTo(lane, at + end)
		}
		val lane = freeLane(from, linePoint, dir, end, Vec3d.ZERO, mobs = false) ?: run {
			ElytraFly.debug { "Mob %.1f ahead ($block), ${next.what} %.1f after it, no free lane past it ($laneMiss)".format(distance, next.distance) }
			return false
		}
		val avoid = cubeBoxes(from, distance + MAX_HOLE_LENGTH)
		val across = across(dir).multiply(lane.offset)
		val over = planHop(linePoint.add(across), dir, distance, avoid = avoid) ?: planHop(linePoint, dir, distance, avoid = avoid) ?: return false
		ElytraFly.debug { "Mob %.1f ahead ($block), ${next.what} %.1f after it, over to ${lane.target.describe(dir)}${lane.leanText()} gliding over the mob there at y %.2f".format(distance, next.distance, over.targetY) }
		dodgeTo(lane, at + end)
		dodgeAfterMob = true
		hop = Hop(over.start, over.end, over.feetY, over.topY, over.obstacle, over.step, lane = lane.target, under = over.under, cube = true)
		return true
	}

	/**
	 * Another obstacle right after [over], once over it it'd be too close to steer past. Steers onto a lane past both
	 * instead when there's one, whether it did. Up on a layer, from its height on, it's on top of that.
	 */
	private fun SafeContext.dodgePastNext(from: Vec3d, linePoint: Vec3d, dir: Vec3d, distance: Double, block: String, over: Hop): Boolean {
		val after = over.end - progress(from)
		val next = from.add(dir.multiply(after)).add(0.0, if (over.step) over.stepHeight else 0.0, 0.0)
			.obstacleHit(dir, brakeDistance() + DODGE_STEER_AHEAD) ?: return false
		val lane = freeLane(from, linePoint, dir, after + next.distance, Vec3d.ZERO) ?: return false
		ElytraFly.debug { "Obstacle %.1f ahead ($block), another %.1f after it (${next.what}), dodging past both ${lane.target.describe(dir)}${lane.leanText()}".format(distance, next.distance) }
		return dodgeTo(lane, progress(from) + after + next.distance)
	}

	/** Steers over to [lane], past the obstacle ending [at] along the line, or the [carpets]. Always true. */
	private fun dodgeTo(lane: Lane, at: Double, carpets: Boolean = false): Boolean {
		dodge = lane.target
		dodgeLean = lane.lean
		dodgeOffset = lane.offset
		dodgeEnd = at
		dodgeTicks = 0
		dodgeCarpets = carpets
		onLane = false
		dodgeAfterMob = false
		fitDodge = slowFor != null
		slowFor = null
		return true
	}

	/**
	 * Past the [carpet] ahead of [from]: bounces on them like a layer when they lie with no gaps, else steers onto the lane
	 * with the fewest of them, else glides over them about a bounce ahead, or lands before them to walk past, bumping into
	 * one hurts. Whether it's past them in the air.
	 */
	private fun SafeContext.dodgeCarpets(from: Vec3d, linePoint: Vec3d, dir: Vec3d, carpet: Obstacle): Boolean {
		if (!carpetsCount) return false
		val at = progress(from) + carpet.distance
		// No gaps and nothing on top for a while, bounced on like a layer of blocks.
		if (!skipHopAt.near(at) && isCarpetLayer(from, dir, carpet.distance))
			planHop(linePoint, dir, carpet.distance, true, carpets = true)?.let { up ->
				ElytraFly.debug { "Low ${carpet.what} %.1f ahead, a layer of them to bounce on, gliding up onto it at y %.2f".format(carpet.distance, up.feetY) }
				// More of them right after its end, the lane past them picked now. Up onto it and on it there's room to
				// steer over, it's on all lanes.
				val layer = linePoint.add(0.0, CARPET_HEIGHT, 0.0)
				val end = generateSequence(carpet.distance + STEP_ON) { it + HOP_SCAN_STEP }.takeWhile { it <= passerConfig.dodgeLookAhead * 2 }
					.firstOrNull { !layer.add(dir.multiply(it)).boxHasFloor() }
				val after = end?.let { laneAfterCarpets(layer, dir, it) }
				hop = up.with(stepHeight = CARPET_HEIGHT, lane = after?.first?.target ?: Vec3d.ZERO)
				after?.let { (lane, at) -> dodgeTo(lane, at, carpets = true) }
				return true
			}
		// Only staying on the line, it simulates the glide.
		laneCarpets = ""
		val over by lazy { planHop(linePoint, dir, carpet.distance, carpets = true) }
		fun glideOver(over: Hop): Boolean {
			ElytraFly.debug { "Low ${carpet.what} %.1f ahead, gliding over it at y %.2f, %.1f long (carpets on the lanes: $laneCarpets; turned down: $laneMiss)".format(carpet.distance, over.feetY, over.end - over.start) }
			hop = over
			return true
		}
		// Right before it there's no time to steer, still up high from gliding over one before it may get over it too.
		if (carpet.distance < MIN_DODGE_DISTANCE) return over?.let { glideOver(it) } ?: false
		// Over to the lane with the fewest of them, seen from afar, the line on a tie. One here and there the bounces
		// mostly don't come down on, else only that one is glided over.
		if (fewestCarpetsLane.allows(carpet.what)) freeLane(from, linePoint, dir, carpet.distance, Vec3d.ZERO, past = CARPET_CLEAR, fewestCarpets = true)
			?.takeUnless { it.stays }?.let { lane ->
			ElytraFly.debug { "Low ${carpet.what} %.1f ahead, dodging ${lane.target.describe(dir)}${lane.leanText()}, ${lane.carpets} carpets on it (carpets on the lanes: $laneCarpets)".format(carpet.distance) }
			return dodgeTo(lane, at, carpets = true)
		}
		// Planned about a bounce ahead, the ones before may well not come down on it.
		if (carpet.distance > CARPET_HOP_AHEAD) return false
		over?.let { return glideOver(it) }
		return if (carpet.distance > brakeDistance()) noLaneYet(at, "Low ${carpet.what} %.1f ahead, not gliding over it ($hopRejection), no lane free of them yet ($laneMiss)".format(carpet.distance))
			else brake(at, "Low ${carpet.what} %.1f ahead, not gliding over it ($hopRejection), no lane free of them ($laneMiss)".format(carpet.distance), carpets = true)
	}

	/**
	 * The closest lane to [current] that's free until [distance] ahead, where the obstacle is, and a bit past it,
	 * with floor there, up on a layer or the floor below it, and with nothing in the way of gliding over to it. The line
	 * itself first. With [carpets] free of them too, for [past] after it. Looked at from [lead] ahead on, with that much
	 * more to steer over to it first. With [fewestCarpets] the one with the fewest of them ahead instead, the one you're on
	 * and then the line on a tie. Without [mobs] magma cubes and hoglins aren't in the way, they're glided over. At
	 * [speed] a tick, the box swings out farther the faster.
	 */
	private fun SafeContext.freeLane(
		from: Vec3d, linePoint: Vec3d, dir: Vec3d, distance: Double, current: Vec3d, carpets: Boolean = false, past: Double = DODGE_MARGIN,
		lead: Double = 0.0, fewestCarpets: Boolean = false, mobs: Boolean = true, speed: Double = player.velocity.horizontalLength(),
	): Lane? {
		val across = across(dir)
		val step = laneStep(dir)
		val here = from.subtract(linePoint).dotProduct(across)
		val leans = sweep(dir, speed) > 0.01
		// The room past an obstacle can be narrower than a lane: diagonally, or straight with the line off the middle of the
		// blocks, a lane a block over grazes the ones on both sides. A quarter of one finds it.
		val laneStep = step / 4
		// The lane dodged to, not its target, that's short of it by the tilt and would round to the one next to it.
		val currentOffset = ((if (dodge != null && current == dodge) dodgeOffset else current.dotProduct(across)) / laneStep).roundToInt()
		val maxOffset = (passerConfig.maxDodge / laneStep + 1e-6).toInt()
		val leanDistance = speed * LEAN_TICKS
		// The path slows down a little each tick, bouncing speeds you up meanwhile. The box swings out that much farther.
		val laneSpeed = speed + LANE_SPEED_MARGIN
		val misses = mutableListOf<Pair<Double, String>>()
		val taken = mutableListOf<Pair<Double, Int>>()
		val lanes = (-maxOffset..maxOffset)
			.filter { fewestCarpets || it != currentOffset }
			.sortedWith(compareBy({ it != currentOffset }, { it != 0 }, { abs(it - currentOffset) }, { -it }))
		// Up on a layer, one staying on it first. Off it you drop down beside it, where the layer is in the way.
		return (if (onLayer) listOf(true, false) else listOf(true)).firstNotNullOfOrNull { sameFloor -> misses.clear(); taken.clear(); lanes.asSequence().mapNotNull { offset ->
				val laneOffset = offset * laneStep
				// Tilted away from where you come from, so on past the obstacle if it's on that side.
				// The lane you're on keeps its tilt, it's leaning already.
				val lean = if (offset == currentOffset && dodge != null) dodgeLean
					else if (leans && abs(laneOffset - here) > 0.1) sign(laneOffset - here).toInt() else 0
				// Short of the lane by the drift of the tilt, so it's on the lane while passing. Past carpets lying along the way
				// right on it, the tilt drifts you on away from them.
				val target = if (fewestCarpets) laneOffset else laneOffset - lean * leanDrift(leanDistance)
				val path = steerPath(here, dir, target, lean, lead + distance - leanDistance, lead + distance + DODGE_MARGIN)
				// From [lead] on all of the way over, it may not be over there yet. Else past the obstacle.
				val passing = path.filter { it.along >= if (lead > 0) lead else distance - 1 }.ifEmpty { listOf(path.last()) }
				val low = passing.minOf { it.offset }
				val high = passing.maxOf { it.offset }
				// Only when it's tilted the whole way past it, the box doesn't swing in.
				val tilted = lean != 0 && passing.all { Math.toDegrees(it.heading) * lean >= MIN_LEAN_DEGREES }
				val middle = linePoint.add(across.multiply((low + high) / 2))
				// How far along the swing gets onto the lane, at most up to the obstacle.
				val reached = (path.firstOrNull { abs(it.offset - target) < LANE_REACHED }?.along ?: distance)
					.coerceIn(0.0, max(0.0, distance - 1))
				val lane = linePoint.add(across.multiply(laneOffset))
				val miss = when {
					// Ahead, you're not there yet to glide over from.
					lead == 0.0 && from.sidewaysObstructed(lane.subtract(from)) -> "in the way sideways"
					// From where the swing gets onto it, before that you're between the lanes, see the lanes crossed below.
					else -> middle.add(dir.multiply(reached)).obstacleHit(dir, distance + past - reached, (high - low) / 2 + STEER_MARGIN, if (tilted) lean else 0, laneSpeed, carpets, mobs)
						?.let { "${it.what} ahead" }
						?: lane.add(dir.multiply(distance)).let { if (sameFloor) it else it.add(0.0, baseY - it.y, 0.0) }.let { spot ->
							// A hole there with floor again within reach is glided over on the lane.
							if (spot.boxHasFloor() || !onLayer && spot.floorWithin(dir, MAX_HOLE_LENGTH)) null
							else "no floor at %.2f %.2f %.2f (${spot.blocksAround()})".format(spot.x, spot.y, spot.z)
						}
						// Down on the floor, a hole on the way there it'd fall into. A short one is glided over on the lane.
						?: if (onLayer) null else generateSequence(HOP_SCAN_STEP) { it + HOLE_SCAN_STEP }.takeWhile { it < distance }
							.firstOrNull { !lane.add(dir.multiply(it)).let { spot -> !spot.isLoaded || spot.boxHasFloor() } }
							?.takeUnless { hole -> lane.add(dir.multiply(hole)).floorWithin(dir, MAX_HOLE_LENGTH) }
							?.let { "a hole %.1f ahead".format(it) }
						// The lanes crossed on the way over, during the swing. Before [lead], where nothing's in the way.
						?: if (lead > 0) null else (minOf(offset, currentOffset) + 1 until maxOf(offset, currentOffset)).firstNotNullOfOrNull {
							linePoint.add(across.multiply(it * laneStep)).obstacleHit(dir, DODGE_STEER_AHEAD, mobs = mobs)
								?.let { hit -> "${hit.what} ahead crossing over" }
						}
				}
				// The first of them on it no closer than the one on the way now, else over there it's on you sooner.
				val (count, first) = if (miss == null && fewestCarpets) lane.carpetsAhead(dir, laneSpeed, lean) else 0 to null
				val closer = first?.takeIf { offset != currentOffset && it < distance - 1 }?.let { "carpets %.1f ahead, closer".format(it) }
				(miss ?: closer)?.let { misses += (laneOffset - here) to it }
				if (miss != null || closer != null) null
				// Past carpets tilted once on the lane, so the box swings out only the side they were counted on.
				else Lane(across.multiply(target), if (tilted || fewestCarpets) lean else 0, laneOffset, count, offset == currentOffset)
					.also { if (fewestCarpets) taken += (laneOffset - here) to it.carpets }
			}.let { found -> if (fewestCarpets) found.minByOrNull { it.carpets } else found.firstOrNull() } }
			.also {
				laneMiss = misses.sortedBy { it.first }.missText()
				laneCarpets = taken.sortedBy { it.first }.joinToString(", ") { (offset, count) -> "%+.2f %d".format(offset, count) }
			}
	}

	/**
	 * How many carpets lie ahead of [from] along [dir] as far as it looks while gliding, across your width and swing at [speed],
	 * only to the side of [lean] when tilted, and how far
	 * ahead the first is. Anything else that low in the way counts as one too, a layer of them to bounce on as none.
	 */
	context(safeContext: SafeContext)
	private fun Vec3d.carpetsAhead(dir: Vec3d, speed: Double, lean: Int): Pair<Int, Double?> {
		val across = across(dir)
		// As far as the box swings out touching down, like the look-ahead on the lane later. Tilted, only to that side.
		val sweep = with(safeContext) { sweep(dir, speed) }
		val low = -halfWidth(dir) - if (lean > 0) 0.0 else sweep
		val high = halfWidth(dir) + if (lean < 0) 0.0 else sweep
		val count = ceil((high - low) / RAY_SPACING).toInt()
		val rays = (0..count).map { add(across.multiply(low + (high - low) * it / count)).add(0.0, CARPET_RAY, 0.0) }
		// Past one, diagonally its corner reaches farther along.
		val pastOne = 1 / max(abs(dir.x), abs(dir.z))
		val reach = passerConfig.dodgeLookAhead.toDouble()
		var carpets = 0
		var first: Double? = null
		var along = 0.0
		while (along < reach) {
			val next = rays.mapNotNull { ray ->
				val start = ray.add(dir.multiply(along))
				safeContext.blockRay(start, dir, reach - along)?.pos?.subtract(start)?.dotProduct(dir)
			}.minOrNull() ?: break
			val at = add(dir.multiply(along))
			val top = at.add(0.0, CARPET_HEIGHT, 0.0)
			// Floor on top of it right after, else it's no layer. That's quick to see, the whole of it isn't.
			if (top.add(dir.multiply(next + STEP_ON)).boxHasFloor() && with(safeContext) { isCarpetLayer(at, dir, next) }) {
				// On past its end, where there's no floor up there.
				along += generateSequence(next + STEP_ON) { it + HOP_SCAN_STEP }.takeWhile { along + it < reach }
					.firstOrNull { !top.add(dir.multiply(it)).boxHasFloor() } ?: break
			} else {
				if (first == null) first = along + next
				along += next + pastOne
				carpets++
			}
		}
		return carpets to first
	}

	/** The lanes beside you, how far across from you, + to the right, grouped where they're turned down alike, for the debug log. */
	private fun List<Pair<Double, String>>.missText() =
		fold(mutableListOf<Triple<Double, Double, String>>()) { runs, (offset, miss) ->
			val last = runs.lastOrNull()
			if (last?.third == miss) runs[runs.lastIndex] = Triple(last.first, offset, miss) else runs += Triple(offset, offset, miss)
			runs
		}.joinToString("; ") { (from, to, miss) ->
			(if (from == to) "%+.2f".format(from) else "%+.2f to %+.2f".format(from, to)) + " $miss"
		}

	/** How far across the line the tilt drifts you over [distance]. */
	private fun leanDrift(distance: Double) = distance * Math.toRadians(LEAN_DEGREES)

	/** The yaw along the line tilted [degrees] to [lean] across it. */
	private fun leanYaw(dir: Vec3d, lean: Int, degrees: Double = LEAN_DEGREES): Double {
		val across = across(dir)
		val heading = dir.normalize().add(across.multiply(lean * Math.tan(Math.toRadians(degrees))))
		return Math.toDegrees(atan2(-heading.x, heading.z))
	}

	private fun Lane.leanText() = if (lean == 0) "" else ", tilted ${if (lean > 0) "right" else "left"} past it"

	/** Across the way [dir] goes, to the right, a block long. */
	private fun across(dir: Vec3d) = Vec3d(-dir.z, 0.0, dir.x).normalize()

	/** Where on the lane dodged to [linePoint] is across from. */
	private fun dodgeLanePoint(linePoint: Vec3d, dir: Vec3d) = linePoint.add(across(dir).multiply(dodgeOffset))

	/**
	 * How far apart the lanes are across [dir]. A block on the way spans the width of two lanes diagonally, so they're a
	 * block apart straight along an axis, but 0.71 diagonally, a block over on either axis.
	 */
	private fun laneStep(dir: Vec3d) = dir.horizontalLength() / (abs(dir.x) + abs(dir.z))

	/** How far across [direction] the player's box reaches from its middle, farther diagonally as its corners stick out. */
	private fun halfWidth(direction: Vec3d) = 0.3 * (abs(direction.x) + abs(direction.z)) / direction.horizontalLength() - WIDTH_MARGIN

	/**
	 * How far the box swings out to either side of [direction] within a tick. A move goes along one axis and then the
	 * other, so diagonally the box first goes half a tick's way off to the side, nothing straight along an axis.
	 */
	private fun SafeContext.sweep(direction: Vec3d, speed: Double = player.velocity.horizontalLength()): Double {
		val dir = direction.normalize()
		return (speed + SWEEP_SPEED_MARGIN) * abs(dir.x * dir.z)
	}

	/**
	 * Where you are, down on the floor below when started in the air. The line at your feet mid-bounce would have no
	 * floor under it, ahead it all looks like a drop to walk around.
	 */
	private fun SafeContext.floorBelow(): Vec3d {
		val floorY = if (player.isOnGround) player.y
			else rayCast(player.pos, Vec3d(0.0, -1.0, 0.0), MAX_FLOOR_DROP, InteractionMask.Block, RaycastContext.ShapeType.COLLIDER)
				?.blockResult?.pos?.y ?: return player.pos
		// On a carpet, the block under it. Bounced on like the floor, at its height the floor anywhere else is too low for one.
		val above = floorY - floor(floorY)
		return Vec3d(player.x, if (above > 0 && above <= passerConfig.minObstacleHeight + 1e-3) floor(floorY) else floorY, player.z)
	}

	/**
	 * Moves the line over to the middle of the highway you start in, for the most room to either side, as the box
	 * swings out across it at speed. Between the nearest walls at your feet across the way you face.
	 */
	private fun SafeContext.centerLine() {
		val dir = lockYawToStep(Vec3d.fromPolar(0f, RotationManager.movementYaw ?: player.yaw))
		val across = across(dir)
		val step = laneStep(dir)
		fun free(at: Vec3d) = world.isSpaceEmpty(Box(at.x - GLIDE_SIZE / 2, at.y + passerConfig.minObstacleHeight, at.z - GLIDE_SIZE / 2, at.x + GLIDE_SIZE / 2, at.y + GLIDE_SIZE, at.z + GLIDE_SIZE / 2))
		fun free(along: Double, lane: Int) = free(startPos.add(dir.multiply(along)).add(across.multiply(lane * step)))
		if (!free(0.0, 0)) return
		// The walls at a few spots ahead, the middle one found each side. A block lying beside you at one of them isn't
		// the wall, it'd move the line next to the real one across. Not where there's a wall on that side at most of them.
		val spots = (0 until CENTER_SPOTS).map { it * CENTER_SPOT_STEP }.filter { startPos.add(dir.multiply(it)).isLoaded && free(it, 0) }
		fun wall(side: Int) = spots.mapNotNull { along -> (1..MAX_CENTER_LANES).firstOrNull { !free(along, side * it) } }
			.takeIf { it.size * 2 > spots.size }?.sorted()?.let { it[it.size / 2] }
		val right = wall(1)
		val left = wall(-1)
		val middle = if (right == null || left == null) 0.0 else (right - left) / 2.0 * step
		val range = passerConfig.acceptableOffsetRange - 0.1
		// Straight along an axis, down the middle of a row of blocks. On the edge between two the box is over both, and
		// carpets in either stop you at their edge.
		val straight = abs(dir.x) < 1e-6 || abs(dir.z) < 1e-6
		val shift = if (!straight) middle.coerceIn(-range, range) else {
			// Which way across points along its axis, and where across it the middle is.
			val axis = across.x + across.z
			val at = startPos.add(across.multiply(middle)).let { if (abs(across.x) > 0.5) it.x else it.z }
			val toRow = middle + (floor(at) + 0.5 - at) * axis
			// Too far off where you started, the row next to it back that way.
			if (abs(toRow) > range) toRow - sign(toRow) else toRow
		}
		if (abs(shift) < if (straight) 0.01 else 0.1) return
		val center = startPos.add(across.multiply(shift))
		if (!center.hasFloor() || !free(center)) return
		startPos = center.subtract(dir.multiply(CENTER_BACKOFF))
		ElytraFly.debug { "Moved the line ${across.multiply(shift).describe(dir)}, to the middle of the highway" }
	}

	/** The yaw to [lane] beside the line, steering at where the drift across it takes you in a few ticks. */
	private fun SafeContext.steerYaw(linePoint: Vec3d, lane: Vec3d, dir: Vec3d): Double {
		val across = across(dir)
		val drift = across.multiply(player.velocity.dotProduct(across) * STEER_DAMPING_TICKS)
		val target = linePoint.add(lane).add(dir.multiply(DODGE_STEER_AHEAD)).subtract(drift)
		return Math.toDegrees(atan2(-(target.x - player.x), target.z - player.z))
	}

	/**
	 * Steers onto the lane, and back onto the line once past the obstacle and the line is free for the swing back.
	 * When the lane ahead is blocked too, moves over to another free one, or lands to walk around it if there's none.
	 * Ends when back on the line. Whether it's still dodging.
	 */
	private fun SafeContext.steerDodge(linePoint: Vec3d, dir: Vec3d): Boolean {
		val lane = dodge ?: return false
		val onLine = lane == Vec3d.ZERO
		val offset = Vec3d(player.x - linePoint.x, 0.0, player.z - linePoint.z)
		val here = Vec3d(player.x, linePoint.y, player.z)
		val progress = progress(player.pos)
		if (!player.isGliding) return stopDodge()
		// Lane after lane for that long, back to the line rather than staying off it, touching down that far off it walks.
		if (++dodgeTicks > MAX_DODGE_TICKS) {
			if (onLine) return stopDodge()
			ElytraFly.debug { "Dodging for $MAX_DODGE_TICKS ticks, back to the line" }
			swingBack()
			dodgeTicks = 0
		}
		// A short hole on the lane, glided over like on the line, staying on the lane.
		if (!onLine && !onLayer && glideOverHole(here, linePoint, dir, null, dodging = true)) {
			hop = hop?.with(lane = lane)
			return true
		}
		// Up on a layer of carpets the ones down on the floor after it aren't seen, nor the layer itself from down there.
		// Back onto the line only once down off it.
		val low = if (dodgeCarpets && onCarpets) Vec3d(0.0, baseY - linePoint.y, 0.0) else Vec3d.ZERO
		// An obstacle longer than seen from afar, the line next to you is still blocked.
		if (!onLine && progress >= dodgeEnd && linePoint.add(low).obstacleDistance(dir, DODGE_MARGIN, carpets = dodgeCarpets) != null) dodgeEnd = progress
		when {
			onLine && offset.length() < DODGE_BACK_ON_LINE -> {
				ElytraFly.debug { "Back on the line" }
				return stopDodge()
			}
			!onLine && progress > dodgeEnd && linePoint.add(low).obstacleDistance(dir, RETURN_CLEAR, carpets = dodgeCarpets) == null &&
				// Still on the lane for a while on the swing back.
				here.add(low).obstacleDistance(dir, RETURN_CLEAR, carpets = dodgeCarpets) == null && !here.sidewaysObstructed(offset.negate()) -> {
				ElytraFly.debug { "Past it, back to the line" }
				swingBack()
			}
			// Past what the lane was picked for, that's known free. Not of carpets, picked with the fewest of them.
			else -> {
				val skip = if (dodgeCarpets) 0.0 else (dodgeEnd + DODGE_MARGIN - progress).coerceAtLeast(0.0)
				// Along the lane itself like when it was picked, the target is short of it by the tilt.
				val onLane = dodgeLanePoint(linePoint, dir)
				// Tilted, the box swings out only that side. On the lane from where you are, drifting across.
				val tilt = if (leaning(progress)) dodgeLean else 0
				val from = if (tilt != 0 && onDodgeLane(linePoint, dir)) here else onLane
				val ahead = from.add(dir.multiply(skip))
				// Dodging carpets, more of them on the lane too.
				// A magma cube or hoglin on the lane is glided over there like on the line, see passCube. Not getting over it,
				// it's dodged like a block.
				val (low, other) = ahead.obstacleHits(dir, passerConfig.dodgeLookAhead - skip, lean = tilt, carpets = dodgeCarpets)
				if (low?.mob == true) {
					val distance = low.distance + skip
					planHop(from, dir, distance, lean = tilt, avoid = cubeBoxes(here, distance + MAX_HOLE_LENGTH))?.let { over ->
						ElytraFly.debug { "Mob %.1f ahead on the lane (${low.what}), gliding ${if (over.under) "under" else "over"} it at y %.2f".format(distance, over.targetY) }
						hop = Hop(over.start, over.end, over.feetY, over.topY, over.obstacle, over.step, lane = lane, under = over.under, cube = true)
						dodgeYaw = steerYaw(linePoint, lane, dir)
						return true
					}
				}
				low?.let { hit ->
					val distance = hit.distance + skip
					val block = hit.what
					// Only carpets on the lane, glided over right there instead of landing.
					val carpetsOnly = dodgeCarpets && (other == null || other.distance > hit.distance + 1) &&
						!skipHopAt.near(progress + distance)
					// A layer of them on the lane, bounced onto from there like from the line.
					if (carpetsOnly && !onLine && distance >= MIN_DODGE_DISTANCE && isCarpetLayer(onLane, dir, distance))
						planHop(onLane, dir, distance, true, carpets = true, lean = tilt)?.let { up ->
							ElytraFly.debug { "Low $block %.1f ahead on the lane, a layer of them to bounce on, gliding up onto it at y %.2f".format(distance, up.feetY) }
							hop = up.with(stepHeight = CARPET_HEIGHT, lane = lane)
							dodgeYaw = steerYaw(linePoint, lane, dir)
							return true
						}
					// A layer of blocks on the lane, bounced onto from there like from the line, e.g. right after a hole glided
					// over high. Close too, the glide sees whether it gets up there. Not with a hole before it, gliding over that.
					// Onto the line when it's up there too, off to the side of a narrow one it'd soon drop off its edge.
					if (!dodgeCarpets && !onLine && !skipHopAt.near(progress + distance) &&
						!holeBefore(onLane, dir, progress, distance) && isStepUp(onLane, dir, distance)) {
						val lineUp = linePoint.obstacleDistance(dir, distance + STEP_CLEAR)?.takeIf { isStepUp(linePoint, dir, it) }
							?.let { planHop(linePoint, dir, it, true) }
						(lineUp ?: planHop(from, dir, distance, true, lean = tilt))?.let { up ->
							ElytraFly.debug { "Obstacle %.1f ahead on the lane ($block), a layer to bounce on, gliding up onto it ${if (lineUp != null) "on the line " else ""}at y %.2f".format(distance, up.feetY) }
							if (lineUp != null) swingBack()
							hop = up.with(lane = dodge ?: Vec3d.ZERO)
							dodgeYaw = steerYaw(linePoint, dodge ?: Vec3d.ZERO, dir)
							return true
						}
					}
					// Still passing what's dodged, something farther on the lane is looked at once past it. Over to another
					// lane now would cross the line right where what's dodged is.
					if (!onLine && progress < dodgeEnd && progress + distance > dodgeEnd + DODGE_STEER_AHEAD) return@let
					// In a hole or after one, gliding over the hole or touching down on it decides, see [glideOverHole].
					if (!onLine && !carpetsOnly && holeBefore(onLane, dir, progress, distance)) return@let
					val next = when {
						distance < MIN_DODGE_DISTANCE -> null
						// Over to another only with fewer of them on it.
						carpetsOnly && !onLine -> if (!fewestCarpetsLane.allows(block)) null
							else freeLane(here, linePoint, dir, distance, lane, past = CARPET_CLEAR, fewestCarpets = true)?.takeUnless { it.stays }
						else -> freeLane(here, linePoint, dir, distance, lane, dodgeCarpets, if (dodgeCarpets) CARPET_CLEAR else DODGE_MARGIN)
					}
					val over = if (carpetsOnly && distance <= CARPET_HOP_AHEAD) planHop(from, dir, distance, carpets = true, lean = tilt)
						?.with(lane = lane) else null
					// Still dodging, from the next tick on holding the height over them on the lane.
					fun glideOver(over: Hop) {
						ElytraFly.debug { "Low $block %.1f ahead on the lane, gliding over it at y %.2f, %.1f long".format(distance, over.feetY, over.end - over.start) }
						hop = over
					}
					when {
						// Swinging back, on the line it may be a layer to bounce on or something to glide over. Too close for
						// that, landing as before.
						next == null && onLine && distance >= MIN_DODGE_DISTANCE -> return stopDodge()
						next != null -> {
							ElytraFly.debug { "Obstacle %.1f ahead on the lane ($block), over to ${next.target.describe(dir)}${next.leanText()}${if (carpetsOnly) ", ${next.carpets} carpets on it (carpets on the lanes: $laneCarpets)" else ""}".format(distance) }
							dodgeTo(next, if (next.target != Vec3d.ZERO) progress + distance else dodgeEnd, dodgeCarpets)
						}
						// Staying on the lane, the fewest of them on it. Planned about a bounce ahead.
						carpetsOnly && !onLine && distance > CARPET_HOP_AHEAD -> {}
						over != null -> glideOver(over)
						// Not getting over them on the lane, over to one free of them after all. Once for them, not lane after lane.
						carpetsOnly && !onLine && distance >= MIN_DODGE_DISTANCE && freeOfCarpetsLane.allows(block) &&
							freeLane(here, linePoint, dir, distance, lane, carpets = true, past = CARPET_CLEAR)?.let { free ->
								ElytraFly.debug { "Low $block %.1f ahead on the lane, not gliding over it ($hopRejection), over to ${free.target.describe(dir)}${free.leanText()}, free of them".format(distance) }
								dodgeTo(free, progress + distance, carpets = true)
							} == true -> {}
						// No other lane, something low enough to glide over on this one, glided over right there like on the line.
						// In a hole the glide over the hole goes over it too, see [glideOverHole].
						!carpetsOnly && !onLine && distance >= MIN_DODGE_DISTANCE &&
							!holeBefore(onLane, dir, progress, distance) &&
							planHop(from, dir, distance, lean = tilt)?.takeUnless { it.under }?.let { glideOver(it.with(lane = lane)) } != null -> {}
						// Keeping to the lane meanwhile, e.g. the line frees up once past the obstacle beside it.
						distance > brakeDistance() -> noLaneYet(progress + distance, "Obstacle %.1f ahead on the lane ($block), ${if (carpetsOnly) "not gliding over it ($hopRejection), " else ""}no other free lane yet ($laneMiss)".format(distance))
						else -> return brake(progress + distance, "Obstacle %.1f ahead on the lane ($block), ${if (carpetsOnly) "not gliding over it ($hopRejection), " else ""}no other free lane ($laneMiss)".format(distance), dodgeCarpets)
					}
				}
			}
		}
		val leaning = leaning(progress)
		atFitSpeed = fitDodge && player.velocity.horizontalLength() >= FIT_SPEED
		val wasOnLane = onLane
		onLane = dodge.let { it != null && it != Vec3d.ZERO } && !leaning &&
			abs(offset.dotProduct(across(dir)) - dodgeOffset) < ON_LANE
		if (onLane && !wasOnLane) ElytraFly.debug { "Over on the lane, bouncing on at %.2f".format(player.velocity.horizontalLength()) }
		dodgeYaw = if (leaning) dodgeLeanYaw(linePoint, dir) else steerYaw(linePoint, dodge ?: Vec3d.ZERO, dir)
		ElytraFly.debug {
			val v = player.velocity
			"  dodge: %.2f off the line, to ${(dodge ?: Vec3d.ZERO).describe(dir)}${if (leaning) ", tilted" else ""}, steering yaw %.1f, moving yaw %.1f at %.2f, sent yaw %.1f"
				.format(offset.dotProduct(across(dir)), dodgeYaw, Math.toDegrees(atan2(-v.x, v.z)), v.horizontalLength(), RotationManager.activeRotation.yaw)
		}
		return true
	}

	/** Whether a hole on [lane] starts before what's [distance] ahead, at [progress] along the line, and isn't behind you yet. */
	private fun SafeContext.holeBefore(lane: Vec3d, dir: Vec3d, progress: Double, distance: Double) =
		holesAhead(lane, dir).any { it.start < progress + distance && it.endInclusive > progress }

	/**
	 * The holes in the floor ahead of [lane] as far as [PasserSettings.dodgeLookAhead], from where to where along the line,
	 * the last open when there's no floor in sight after it. Holes with too little floor between them to bounce on are
	 * one, like [glideOverHole] glides over them.
	 */
	private fun SafeContext.holesAhead(lane: Vec3d, dir: Vec3d): List<ClosedFloatingPointRange<Double>> {
		val at = progress(lane)
		val reach = passerConfig.dodgeLookAhead.toDouble()
		val bounceRoom = player.velocity.horizontalLength() * HOLE_TOUCHDOWN_TICKS + HOLE_TOUCHDOWN_MARGIN
		val holes = mutableListOf<ClosedFloatingPointRange<Double>>()
		var holeStart: Double? = null
		var along = HOLE_SCAN_STEP
		while (along <= reach) {
			val spot = lane.add(dir.multiply(along))
			if (!spot.isLoaded) break
			val floor = spot.boxHasFloor()
			if (!floor && holeStart == null) holeStart = holes.lastOrNull()?.takeIf { along - (it.endInclusive - at) < bounceRoom }
				?.let { holes.removeAt(holes.lastIndex).start - at } ?: along
			if (floor && holeStart != null) {
				holes += at + holeStart..at + along
				holeStart = null
			}
			along += HOLE_SCAN_STEP
		}
		holeStart?.let { holes += at + it..at + along }
		return holes
	}

	/**
	 * How fast to be for what's ahead: too slow to glide over the next hole on the lane you're dodging to, else on the
	 * line, like [glideOverHole] would, it bounces on to speed up instead of flying level, also swinging over to a lane
	 * while what's dodged is still far, so it gets over the hole after it instead of landing before it. Looked at with
	 * each scan, the glide over it is simulated.
	 */
	private fun SafeContext.planSpeed(linePoint: Vec3d, dir: Vec3d) {
		if (++scanTicks < AHEAD_SCAN_TICKS) return
		scanTicks = 0
		val progress = progress(player.pos)
		// Only the lane you're on or dodging to, beside it it may look shorter, e.g. what's dodged stands in it on the line.
		val lane = if (dodge.let { it != null && it != Vec3d.ZERO }) dodgeLanePoint(linePoint, dir) else linePoint
		val hole = holesAhead(lane, dir).firstOrNull { it.start > progress } ?: return forgetSpeedNeed()
		// Like [glideOverHole] glides over it, over the blocks in it too.
		val over = holeHop(lane, dir, hole).first
		fun makesIt(speed: Double) = canHop(over, progress, NO_HOLD, speed) || canHop(over, progress, 0, speed)
		val speed = player.velocity.horizontalLength()
		val wants = !makesIt(speed)
		// Not tilting past what's dodged yet, nor close to it, bouncing on into it there.
		speedingUp = wants && dodge != null && !leaning(progress) && dodgeEnd - progress > brakeDistance() + DODGE_STEER_AHEAD
		if (abs(hole.start - speedNeedAt) > 1 || wants != wantsSpeed) ElytraFly.debug {
			// The slowest that makes it, a dozen more simulations, only for the log.
			var low = speed
			var high = MAX_PLAN_SPEED
			val need = if (!wants) "" else if (!makesIt(high)) ", too long to glide over" else {
				repeat(8) {
					val mid = (low + high) / 2
					if (makesIt(mid)) high = mid else low = mid
				}
				", needs %.2f a tick to glide over, bouncing on to speed up".format(high)
			}
			"Hole %.1f ahead, %.1f long, at %.2f$need".format(hole.start - progress, hole.endInclusive - hole.start, speed)
		}
		speedNeedAt = hole.start
		wantsSpeed = wants
	}

	private fun forgetSpeedNeed() {
		wantsSpeed = false
		speedNeedAt = Double.NaN
		speedingUp = false
	}

	/**
	 * Glides over a hole in the floor ahead of [from], up to [MAX_HOLE_LENGTH] long and before [before] where something's
	 * in the way, like over a block: bouncing on unless you'd come down in it. Lands before it to walk when it can't get
	 * over in time. Whether it's past it in the air. With [within] only one starting that close, whatever's after it.
	 */
	private fun SafeContext.glideOverHole(
		from: Vec3d, linePoint: Vec3d, dir: Vec3d, before: Double?, dodging: Boolean = false, within: Double? = null,
	): Boolean {
		val along = generateSequence(HOP_SCAN_STEP) { it + HOP_SCAN_STEP }
			.takeWhile { it <= (before ?: passerConfig.dodgeLookAhead.toDouble()) }
			.map { it to from.add(dir.multiply(it)) }
			.takeWhile { (_, point) -> point.isLoaded }
		val start = along.firstOrNull { (_, point) -> !point.boxHasFloor() }?.first ?: return false
		if (within != null && start > within) return false
		val at = progress(from)
		if (lookedLately(at + start, start)) return false
		if (skipHopAt.near(at + start)) return false
		// Long ones too, holding the height as long as it gets over them. Longer, e.g. a step down to a lower floor, it's
		// left as before.
		fun floorFrom(at: Double) = generateSequence(at) { it + HOP_SCAN_STEP }.takeWhile { it <= start + MAX_HOLE_LENGTH }
			.firstOrNull { from.add(dir.multiply(it)).boxHasFloor() }
		// Enough floor between two holes to come down on and bounce, which gets back speed and height. Less, the bounce
		// would come down in the next one: glided over as one.
		val bounceRoom = player.velocity.horizontalLength() * HOLE_TOUCHDOWN_TICKS + HOLE_TOUCHDOWN_MARGIN
		fun holeFrom(at: Double) = generateSequence(at) { it + HOP_SCAN_STEP }.takeWhile { it < at + bounceRoom }
			.firstOrNull { !from.add(dir.multiply(it)).boxHasFloor() }
		val floorEnd = floorFrom(start)
		if (floorEnd == null) return glideOverOpenHole(from, dir, at, start)
		var end: Double = floorEnd
		while (true) end = holeFrom(end)?.let(::floorFrom) ?: break
		// Something in it or soon after it, once over the hole too close to steer past. Dodging, what's dodged is right
		// beside the lane: the hole's glided over, the dodge goes on past the rest.
		val next = if (dodging || within != null) null
			else from.add(dir.multiply(start)).obstacleHit(dir, end - start + brakeDistance() + DODGE_STEER_AHEAD)?.let { it.distance + start }
		// Right after it both are glided over as one, from before the hole on at its height.
		if (next != null && next >= end && next - end <= HOP_GAP) {
			// Under it would mean coming down low over the hole, it's dodged instead.
			val over = planHop(linePoint, dir, next)?.takeUnless { it.under }
				?.let { Hop(min(it.start, at + start - HOP_SCAN_STEP), it.end, it.feetY, it.topY, at + start, false) }
				?.takeIf { canGetOver(it, at) } ?: return false
			ElytraFly.debug { "Hole %.1f ahead, %.1f long, something right after it, gliding over both at y %.2f".format(start, end - start, over.feetY) }
			hop = over
			return true
		}
		// Farther, or in it: glided over from the line, bouncing on up to it faster than on a lane, and steered over to a
		// lane past it meanwhile. With none, that's what's dodged or glided over first.
		// In it, glided over or touched down on with the hole, a lane only for one that can't be, see below.
		val nextLane = next?.let { freeLane(from, linePoint, dir, it, Vec3d.ZERO) ?: if (it < end) null else return false }
		// High enough for the blocks in it that can be glided over. Dodging, on the lane flown along, not where you are
		// steering over to it, from there what's dodged looks in the way. Steering over to one past what's after it, on
		// that lane, e.g. touching down on a block on the way over there.
		val glideLine = when {
			dodging && dodge != null -> dodgeLanePoint(linePoint, dir)
			nextLane != null -> linePoint.add(nextLane.target)
			else -> from
		}
		val (hole, blocked) = holeHop(glideLine, dir, at + start..at + end)
		// One in it that can't be, e.g. on the lane dodged to, only with a lane past it to steer over to meanwhile. Else
		// it's in the way like a wall in the hole, waited for or landed before while there's room to. Right at its edge
		// braking drops you in, it's glided over and steered past once there's a lane, see [laneFromGlide]. What's
		// dodged the lane already gets past.
		val pastBlocked = if (nextLane != null || blocked == null || start <= brakeDistance() || dodging && at + blocked <= dodgeEnd + DODGE_MARGIN) null
			else freeLane(from, linePoint, dir, blocked, dodge ?: Vec3d.ZERO)?.takeIf { it.target != (dodge ?: Vec3d.ZERO) } ?: run {
				val reason = "Hole %.1f ahead, %.1f long, ${from.obstacleBlock(dir, blocked + 1)} %.1f ahead in it can't be glided over, no lane past it ($laneMiss)"
					.format(start, end - start, blocked)
				return if (start > brakeDistance() + DODGE_STEER_AHEAD) noLaneYet(at + start, reason) else brake(at + start, reason)
			}
		val lane = nextLane ?: pastBlocked
		val past = if (nextLane != null) next else blocked
		if (!canGetOver(hole, at)) {
			val reason = "Hole %.1f ahead, %.1f long, can't get over it ($hopMiss${if (touchMiss.isEmpty()) "" else "; $touchMiss"})".format(start, end - start)
			return if (start > brakeDistance()) noLaneYet(at + start, reason) else brake(at + start, reason)
		}
		if (lane == null || past == null) {
			ElytraFly.debug {
				val touching = hole.touch?.let { ", touching down on the top at y %.2f %.1f ahead".format(it.y, it.range.start - at) }
					?: if (touchMiss.isEmpty()) "" else " ($touchMiss)"
				"Hole %.1f ahead, %.1f long, gliding over it$touching".format(start, end - start)
			}
			hop = hole
			return true
		}
		ElytraFly.debug {
			val touching = hole.touch?.let { ", touching down on the top at y %.2f %.1f ahead".format(it.y, it.range.start - at) }
				?: if (touchMiss.isEmpty()) "" else " ($touchMiss)"
			"Hole %.1f ahead, %.1f long, gliding over it$touching and over to ${lane.target.describe(dir)}${lane.leanText()} past what's %.1f ahead".format(start, end - start, past)
		}
		dodgeTo(lane, at + past)
		hop = hole.with(lane = lane.target)
		return true
	}

	/**
	 * No lane past the obstacle [distance] ahead at bounce height and nothing to glide over on the line: an opening in it
	 * beside the line, e.g. a wall with a hole high up on one side. On that lane the obstacle is the first thing, it's
	 * steered over to before it, and a glide fits through it there, at the opening's height. Whether it's on its way.
	 */
	private fun SafeContext.throughOpening(from: Vec3d, linePoint: Vec3d, dir: Vec3d, distance: Double, block: String): Boolean {
		if (distance < MIN_DODGE_DISTANCE) return false
		val across = across(dir)
		// Like [freeLane], a quarter lane at a time, the opening may be narrower than a lane.
		val step = laneStep(dir) / 4
		val sides = (passerConfig.maxDodge / step + 1e-6).toInt()
		val here = from.subtract(linePoint).dotProduct(across)
		// The closest lane to an opening that's too narrow, for the log. Diagonally each tick moves along one axis and
		// then the other, the box swings out across the way, so a single block missing is too narrow at speed.
		var closest: String? = null
		val (offset, over) = (-sides..sides).sortedBy { abs(it) }.asSequence().mapNotNull { side ->
			val offset = side * step
			val lanePoint = linePoint.add(across.multiply(offset))
			val wall = lanePoint.obstacleHit(dir, distance + DODGE_MARGIN, mobs = false)?.distance ?: return@mapNotNull null
			if (wall < distance - 1) return@mapNotNull null
			val reached = steerPath(here, dir, offset, 0, wall, wall).firstOrNull { abs(it.offset - offset) < LANE_REACHED }?.along
			if (reached == null || reached > wall - 1) return@mapNotNull null
			planHop(lanePoint, dir, wall)?.let { offset to it }
				?: null.also { if (closest == null) closest = "%+.2f across: $hopRejection".format(offset) }
		}.firstOrNull() ?: run {
			openingMiss = closest ?: "none"
			return false
		}
		ElytraFly.debug { "Obstacle %.1f ahead ($block), no free lane, through the opening in it %+.2f across, gliding at y %.2f".format(distance, offset, over.targetY) }
		val lane = across.multiply(offset)
		dodgeTo(Lane(lane, 0, offset), over.end)
		hop = over.with(lane = lane)
		return true
	}

	/**
	 * [over], a glide over a block, with a hole starting right after it, too close to bounce in between: both glided over
	 * as one, as high as over a hole from the start, not low over the block and pulling up over the hole after, and over
	 * the blocks in it. Null when there's none or that doesn't get over it.
	 */
	private fun SafeContext.withHoleAfter(over: Hop, linePoint: Vec3d, dir: Vec3d): Hop? {
		val bounceRoom = player.velocity.horizontalLength() * HOLE_TOUCHDOWN_TICKS + HOLE_TOUCHDOWN_MARGIN
		val hole = holesAhead(linePoint, dir).firstOrNull { it.endInclusive > over.end && it.start <= over.end + bounceRoom } ?: return null
		val at = progress(linePoint)
		// Higher over the block it starts with and the ones in it, over the rest just above the floor.
		val (raised, topY, aim, blocked) = clearInside(linePoint, dir, over.end - at, hole.endInclusive - at, min(maxFlightY(), linePoint.y + MAX_BOUNCE_RISE), over)
		// A block in it that can't be glided over, the hole's looked at on its own once over this one.
		if (blocked != null || topY - raised.maxOf { it.second } < HOP_MARGIN) return null
		val both = Hop(over.start, hole.endInclusive, linePoint.y + passerConfig.minObstacleHeight, topY, over.obstacle, false,
			low = true, hole = true, aim = aim, raised = raised)
		return both.takeIf { canGetOver(it, at) }
	}

	/**
	 * How high a glide along [linePoint] from [from] to [to] ahead has to be to clear the blocks there that can be glided
	 * over, e.g. in a hole: where along the line the feet have to be how high, how high they may go, and where to hold
	 * them. [topY] is only how high it'd rather go, for blocks needing more it goes as high as there's room over them,
	 * held a bit above what they need. Null where to hold them with none. One that can't be glided over ends the look,
	 * where it is is [Clearance.blocked], a lane past it is needed. With the glide over a block [before] it, that one too.
	 */
	private fun SafeContext.clearInside(
		linePoint: Vec3d, dir: Vec3d, from: Double, to: Double, topY: Double, before: Hop? = null,
	): Clearance {
		val at = progress(linePoint)
		val raised = mutableListOf<Pair<ClosedFloatingPointRange<Double>, Double>>()
		var room = maxHopY()
		before?.let {
			raised += it.start..it.end to it.feetY
			room = min(room, it.topY)
		}
		var along = from
		var blocked: Double? = null
		while (along < to) {
			val hit = linePoint.add(dir.multiply(along)).obstacleHit(dir, to - along, mobs = false) ?: break
			// Only how high, whether it gets up there in time is up to the glide over all of it.
			val inner = planHop(linePoint, dir, along + hit.distance, anyway = true)
			if (inner == null) {
				blocked = along + hit.distance
				break
			}
			raised += inner.start..inner.end to inner.feetY
			room = min(room, inner.topY)
			along = max(inner.end - at, along + hit.distance + HOP_SCAN_STEP)
		}
		val highest = raised.maxOfOrNull { it.second } ?: return Clearance(raised, topY, null, blocked)
		if (highest + HOP_MARGIN <= min(topY, room)) return Clearance(raised, min(topY, room), null, blocked)
		return Clearance(raised, room, min(room - HOP_MARGIN, highest + RAISED_HOLD), blocked)
	}

	/**
	 * Why the hole right after the end of the layer [edge] ahead of [linePoint] can't be glided over from up there, null
	 * when it can or there's none.
	 */
	private fun SafeContext.holeAfterEdgeMiss(linePoint: Vec3d, dir: Vec3d, edge: Double): String? {
		val floorLine = linePoint.add(0.0, baseY - linePoint.y, 0.0)
		val at = progress(linePoint)
		val bounceRoom = player.velocity.horizontalLength() * HOLE_TOUCHDOWN_TICKS + HOLE_TOUCHDOWN_MARGIN
		val hole = holesAhead(floorLine, dir).firstOrNull { it.endInclusive > at + edge && it.start <= at + edge + bounceRoom } ?: return null
		return if (canGetOver(holeHop(floorLine, dir, hole).first, at)) null else hopMiss
	}

	/**
	 * The glide along [line] over [hole], from where to where along the line, just above the floor there and higher over
	 * the blocks in it, see [clearInside]. Partly over its edge the box stands, the rays found where it doesn't. With
	 * where a block in it is that can't be glided over.
	 */
	private fun SafeContext.holeHop(line: Vec3d, dir: Vec3d, hole: ClosedFloatingPointRange<Double>): Pair<Hop, Double?> {
		val at = progress(line)
		// Not lower than you already are, e.g. right off a layer above it.
		val (raised, topY, aim, blocked) = clearInside(line, dir, hole.start - at, hole.endInclusive - at, min(maxFlightY(), max(line.y + MAX_BOUNCE_RISE, player.y)))
		val over = Hop(hole.start - HOP_SCAN_STEP, hole.endInclusive, line.y + passerConfig.minObstacleHeight, topY, hole.start, false,
			low = true, hole = true, aim = aim, raised = raised)
		// Also with none in it on this lane, beside it there may be one.
		return withTouch(over, line, dir) to blocked
	}

	/**
	 * [hop] touching down on the first top on its way that it can, e.g. a pillar or a block in a hole, bouncing off it
	 * resets the glide and speeds it up. As it was when there's none, or the glide doesn't get there.
	 */
	private fun SafeContext.withTouch(hop: Hop, line: Vec3d, dir: Vec3d): Hop {
		if (hop.under || hop.low && !hop.hole || hop.cube || hop.step || hop.down) return hop
		// On the lane flown along when it crosses the top well, else the lane crossing it the longest, through its middle.
		val own = touchSpot(line, dir, hop.start, hop.end)
		val touch = own?.takeIf { it.range.endInclusive - it.range.start >= GOOD_TOUCH }
			?: listOfNotNull(own, laneTouch(hop, line, dir)).maxByOrNull { it.range.endInclusive - it.range.start }
			?: return hop.also { touchMiss = "no top to touch down on" }
		// As high as there's room on the way to it, above what the glide would rather keep under: the bounce before it
		// may take you higher than the top, sinking to just above it after.
		val touching = hop.with(touch = touch, topY = max(hop.topY, maxHopY()))
		if (canGetOver(touching, progress(player.pos))) return touching.also { touchMiss = "" }
		touchMiss = "touching down on the top at y %.2f %.1f ahead misses too, $hopMiss".format(touch.y, touch.range.start - progress(player.pos))
		return hop
	}

	/**
	 * The first top along [line] from [from] to [to] along the line to touch down on: one or two blocks above its floor,
	 * under the box for [MIN_TOUCH] at least, with [TOUCH_ROOM] above it. Null with none.
	 */
	private fun SafeContext.touchSpot(line: Vec3d, dir: Vec3d, from: Double, to: Double): Touch? {
		val at = progress(line)
		val across = across(dir)
		val lane = across.multiply(line.subtract(startPos).dotProduct(across))
		var start: Double? = null
		var height = 0.0
		var along = from
		while (along <= to) {
			val point = line.add(dir.multiply(along - at))
			val top = (1..2).map { line.y + it }.firstOrNull { y ->
				y <= maxHopY() && Vec3d(point.x, y, point.z).boxHasFloor() &&
					spaceEmpty(Box(point.x - GLIDE_SIZE / 2, y, point.z - GLIDE_SIZE / 2, point.x + GLIDE_SIZE / 2, y + TOUCH_ROOM, point.z + GLIDE_SIZE / 2))
			}
			when {
				top != null && start == null -> {
					start = along
					height = top
				}
				start != null && top != height -> {
					if (along - start >= MIN_TOUCH) return Touch(start..along - HOP_SCAN_STEP, height, lane)
					start = if (top != null) along else null
					top?.let { height = it }
				}
			}
			along += HOP_SCAN_STEP
		}
		return start?.takeIf { to - it >= MIN_TOUCH }?.let { Touch(it..to, height, lane) }
	}

	/**
	 * A top to touch down on beside [line] for [hop], the closest lane with one, e.g. a block in a hole beside the lane
	 * past what's after it: steered over to it for the bounce, then on to the lane. Only with room at the height it's
	 * held at for the last [TOUCH_APPROACH] before it on that lane, steered over to it there. Before that it's on its own
	 * lane, e.g. past a pillar at the start of a hole on the line the top is on.
	 */
	private fun SafeContext.laneTouch(hop: Hop, line: Vec3d, dir: Vec3d): Touch? {
		val across = across(dir)
		// Lanes across the line itself, no farther out than a dodge goes.
		val here = line.subtract(startPos).dotProduct(across)
		val middle = line.subtract(across.multiply(here))
		// Half a lane apart, a top a block wide isn't missed between them, and it's looked for every few ticks.
		val step = laneStep(dir) / 2
		val sides = (passerConfig.maxDodge / step + 1e-6).toInt()
		val progress = progress(player.pos)
		val at = progress(line)
		val swing = sweep(dir)
		// The one crossing a top the longest, through its middle, a bit shorter closer by.
		return (-sides..sides).map { it * step }.filter { abs(it - here) > step / 2 }.mapNotNull { offset ->
			val lane = middle.add(across.multiply(offset))
			touchSpot(lane, dir, hop.start, hop.end)?.takeIf { touch ->
				// Room for the box swinging out on the way there, steering over to a lane by a wall it'd swing into it.
				generateSequence(max(progress, touch.range.start - TOUCH_APPROACH)) { it + HOP_SCAN_STEP }.takeWhile { it < touch.range.start }.all { along ->
					val point = lane.add(dir.multiply(along - at))
					spaceEmpty(Box(point.x - GLIDE_SIZE / 2, touch.y + TOUCH_ABOVE, point.z - GLIDE_SIZE / 2,
						point.x + GLIDE_SIZE / 2, touch.y + TOUCH_ABOVE + GLIDE_SIZE, point.z + GLIDE_SIZE / 2).expand(swing, 0.0, swing))
				}
			}?.let { touch -> touch to abs(offset - here) }
		}.maxByOrNull { (touch, away) -> touch.range.endInclusive - touch.range.start - away * TOUCH_AWAY_COST }?.first
	}

	/**
	 * The top being touched down on while over it, until the jump off it, else null: bounced off like the floor, the
	 * landing is held back with the jump. Handled on its own, the server ends the glide and the jump off it right under
	 * a roof, e.g. a pillar two high, takes you into it standing.
	 */
	protected fun SafeContext.touchFloor(): Double? {
		val hop = hop ?: return null
		val touch = hop.touch ?: return null
		val progress = progress(player.pos)
		return touch.y.takeIf {
			if (hop.touched) player.age - hop.touchedAt <= 1
			else progress >= touch.range.start - TOUCH_HELD_AROUND && progress <= touch.range.endInclusive + TOUCH_HELD_AROUND
		}
	}

	/** What [clearInside] found: where how high, how high it may go, where to hold it, and a block that can't be glided over. */
	private data class Clearance(
		val raised: List<Pair<ClosedFloatingPointRange<Double>, Double>>, val topY: Double, val aim: Double?, val blocked: Double?,
	)

	/**
	 * Gliding over a hole or a block on the line or the lane dodged to, something ahead on it that can't be glided over:
	 * over to a lane past it once there's one, still gliding, looked for every [RELOOK_TICKS] as far as
	 * [PasserSettings.dodgeLookAhead]. Seen from farther than a lane past it was free, or past the look ahead before.
	 * Once over it, too close to steer past. The glide steered over to it, null while none.
	 */
	private fun SafeContext.laneFromGlide(hop: Hop, linePoint: Vec3d, dir: Vec3d, progress: Double): Hop? {
		// On the line, or on the lane dodged to, swinging back to the line too.
		val current = dodge ?: Vec3d.ZERO
		if (hop.lane != current || hop.cube || hop.step || hop.down || hop.under || hop.low && !hop.hole || passTicks % RELOOK_TICKS != 0) return null
		val onLane = current != Vec3d.ZERO
		val here = Vec3d(player.x, linePoint.y, player.z)
		val lanePoint = if (onLane) dodgeLanePoint(linePoint, dir) else linePoint
		val reach = passerConfig.dodgeLookAhead.toDouble()
		// What's in a hole counts, over a block from where the glide over it ends. On a lane what's dodged beside it doesn't,
		// like the dodge looks at it. Things to glide over on the way are left to that, the first one that can't be is what
		// a lane gets past.
		var from = if (hop.hole) 0.0 else (hop.end - progress).coerceAtLeast(0.0)
		if (onLane) from = max(from, dodgeEnd + DODGE_MARGIN - progress)
		var next: Pair<Double, String>? = null
		while (from < reach && next == null) {
			val hit = lanePoint.add(dir.multiply(from)).obstacleHit(dir, reach - from, mobs = false) ?: return null
			val at = from + hit.distance
			if (planHop(lanePoint, dir, at) == null) next = at to hit.what
			else from = at + HOP_GAP
		}
		val (distance, what) = next ?: return null
		val lane = freeLane(here, linePoint, dir, distance, current)?.takeIf { it.target != current } ?: return null
		ElytraFly.debug { "Gliding over it, $what %.1f ahead, over to ${lane.target.describe(dir)}${lane.leanText()} meanwhile".format(distance) }
		dodgeTo(lane, progress + distance)
		return hop.with(lane = lane.target).also { this@ObstaclePassingMode.hop = it }
	}

	/**
	 * Whether the obstacle [distance] ahead of [linePoint] is better dodged over a hole on the line before it: bouncing
	 * on the line up to the hole is faster than on a lane, more speed to get over the hole with, and over it there's
	 * room to steer past, see [glideOverHole]. Only with a lane past it free from where the hole starts.
	 */
	private fun SafeContext.dodgeOverHole(linePoint: Vec3d, dir: Vec3d, distance: Double): Boolean {
		val start = generateSequence(HOLE_SCAN_STEP) { it + HOLE_SCAN_STEP }.takeWhile { it < distance - MIN_DODGE_DISTANCE }
			.firstOrNull { linePoint.add(dir.multiply(it)).let { spot -> spot.isLoaded && !spot.boxHasFloor() } } ?: return false
		val holeStart = linePoint.add(dir.multiply(start))
		return freeLane(holeStart, holeStart, dir, distance - start, Vec3d.ZERO) != null
	}

	/**
	 * Glides on at the height over a hole [start] ahead of [from] with no floor in sight, there may be some farther on.
	 * As far as [MAX_HOP_LENGTH] at a time, then it's looked at again, also for anything in the way.
	 */
	private fun SafeContext.glideOverOpenHole(from: Vec3d, dir: Vec3d, at: Double, start: Double): Boolean {
		val end = generateSequence(start) { it + HOP_SCAN_STEP }
			.takeWhile { it <= start + MAX_HOP_LENGTH && from.add(dir.multiply(it)).isLoaded }.last()
		// Something in the way over it, dodged or glided over like anywhere else.
		if (from.obstacleHit(dir, end) != null) return false
		ElytraFly.debug { "Hole %.1f ahead, no floor in sight, gliding on over it at its height".format(start) }
		hop = Hop(at + start - HOP_SCAN_STEP, at + end, from.y + passerConfig.minObstacleHeight, min(maxFlightY(), from.y + MAX_BOUNCE_RISE),
			at + start, false, hole = true, open = true)
		return true
	}

	/**
	 * Up on a layer of blocks, where it ends ahead of [line]. The floor below the rays don't see, so with more of
	 * the layer soon after, glides across the gap at its height instead of dropping into it in front of the next. On the
	 * [lane] that far across the line, dodging.
	 */
	private fun SafeContext.planGap(line: Vec3d, dir: Vec3d, lane: Vec3d = Vec3d.ZERO): Boolean {
		val linePoint = line.add(lane)
		val along = generateSequence(HOP_SCAN_STEP) { it + HOP_SCAN_STEP }
			.takeWhile { it <= passerConfig.dodgeLookAhead }
			.map { it to linePoint.add(dir.multiply(it)) }
			.takeWhile { (_, point) -> point.isLoaded }
		val edge = along.firstOrNull { (_, point) -> !point.boxHasFloor() }?.first ?: return false
		// Off its end already, e.g. a glide off it that got cut short. A lane beside a layer of blocks is off it from the
		// start, while the line still is on it you're still up there.
		if (edge <= HOP_SCAN_STEP) {
			if (lane != Vec3d.ZERO && !onCarpets && line.boxHasFloor()) return false
			offTheLayer()
			return false
		}
		val at = progress(linePoint) + edge
		if (noGapAt.near(at) || skipHopAt.near(at)) return false
		noGapAt = at
		// Deeper than the floor you started on, that's for walking around.
		if (!linePoint.add(dir.multiply(edge)).add(0.0, baseY - linePoint.y, 0.0).boxHasFloor()) return false
		// Where there's floor under you again up here, more of the layer.
		val resume = along.firstOrNull { (distance, point) -> distance > edge && distance <= edge + MAX_GAP && point.boxHasFloor() }?.first
		// Carpets, even a short gap is glided across: down in it you're stopped at the edge of the next one.
		if (resume != null && resume - edge < MIN_GAP && !onCarpets) return false
		if (resume == null) {
			// Carpets right after a layer of them, down there too close to steer past. Over to a lane free of them
			// still up here, the layer leaves room for it.
			if (onCarpets && lane == Vec3d.ZERO) laneAfterCarpets(linePoint, dir, edge)?.let { (lane, at) -> return dodgeTo(lane, at, carpets = true) }
			// A hole right after it down there, too fast to stop once off the edge. Too slow to glide over it, e.g. slowed
			// down gliding up onto the layer, landing up here before the edge to walk around it.
			if (edge > brakeDistance()) holeAfterEdgeMiss(linePoint, dir, edge)?.let { miss ->
				return brake(progress(linePoint) + edge, "The blocks end %.1f ahead, a hole right after it, can't get over it ($miss)".format(edge))
			}
			// Gliding off the edge at the layer's height, the next bounce is down on the floor.
			val off = planHop(linePoint, dir, (edge - DROP_BEFORE).coerceAtLeast(HOP_SCAN_STEP), true, DROP_BEFORE + STEP_ON, carpets = onCarpets) ?: run {
				ElytraFly.debug { "The blocks end %.1f ahead, bouncing on down at y %.0f after, not gliding off ($hopRejection)".format(edge, baseY) }
				return false
			}
			// Carpets right after it down on the floor, coming down off the edge there's no time to get up over them. Glided
			// over along with the edge, as one.
			val floorLine = linePoint.add(0.0, baseY - linePoint.y, 0.0)
			val after = floorLine.add(dir.multiply(edge)).obstacleHit(dir, max(HOP_GAP, player.velocity.horizontalLength() * CARPET_GAP_TICKS), carpets = true)
			val alongWith = after?.let { planHop(floorLine, dir, edge + it.distance, carpets = true) }?.let { over ->
				Hop(off.start, max(off.end, over.end), max(off.feetY, over.feetY), min(off.topY, over.topY), off.obstacle, false, down = true, lane = lane, low = true)
					.takeIf { it.topY - it.feetY >= HOP_MARGIN && canGetOver(it, progress(linePoint)) }
			}
			ElytraFly.debug {
				if (alongWith != null) "The blocks end %.1f ahead, ${after.what} right after, gliding off them and over it at y %.2f, bouncing on down at y %.0f after".format(edge, alongWith.feetY, baseY)
				else "The blocks end %.1f ahead, gliding off them at y %.2f, bouncing on down at y %.0f after".format(edge, off.feetY, baseY)
			}
			// Not up onto anything after it, the line goes down once you're down.
			hop = alongWith ?: Hop(off.start, off.end, off.feetY, off.topY, off.obstacle, false, down = true, lane = lane, low = off.low)
			return true
		}
		val across = planHop(linePoint, dir, edge, true, resume - edge + STEP_ON, carpets = onCarpets) ?: run {
			ElytraFly.debug { "The blocks end %.1f ahead, more %.1f after, not gliding across ($hopRejection)".format(edge, resume - edge) }
			return false
		}
		ElytraFly.debug { "The blocks end %.1f ahead, more %.1f after, gliding across at y %.2f".format(edge, resume - edge, across.feetY) }
		hop = across.with(stepHeight = layerHeight, lane = lane)
		return true
	}

	/**
	 * The lane with the fewest of the carpets right after the end of the layer of them [edge] ahead of [linePoint], down on
	 * the floor, to steer onto while still up on it, and where along the line those carpets are. Null when that's the line,
	 * or they're far enough after it to steer past from down there.
	 */
	private fun SafeContext.laneAfterCarpets(linePoint: Vec3d, dir: Vec3d, edge: Double): Pair<Lane, Double>? {
		val across = across(dir)
		val offset = across.multiply(Vec3d(player.x - linePoint.x, 0.0, player.z - linePoint.z).dotProduct(across))
		val edgeLine = linePoint.add(dir.multiply(edge)).add(0.0, baseY - linePoint.y, 0.0)
		val next = edgeLine.obstacleHit(dir, passerConfig.dodgeLookAhead.toDouble(), carpets = true) ?: return null
		// Far enough after it, there's time to steer from down there.
		if (next.distance > brakeDistance() + DODGE_STEER_AHEAD) return null
		val lane = freeLane(edgeLine.add(offset), edgeLine, dir, next.distance, Vec3d.ZERO, past = CARPET_CLEAR, lead = edge, fewestCarpets = true)
			?.takeUnless { it.stays } ?: run {
				ElytraFly.debug { "The blocks end %.1f ahead, ${next.what} %.1f after, the line has the fewest of them (carpets on the lanes: $laneCarpets; turned down: $laneMiss)".format(edge, next.distance) }
				return null
			}
		ElytraFly.debug { "The blocks end %.1f ahead, ${next.what} %.1f after, dodging ${lane.target.describe(dir)} up there, ${lane.carpets} carpets on it".format(edge, next.distance) }
		return lane to progress(edgeLine) + next.distance
	}

	/** Past the end of a layer, the line goes back down to the floor you started on. */
	private fun offTheLayer() {
		ElytraFly.debug { "Off the blocks, bouncing on down at y %.2f".format(baseY) }
		startPos = Vec3d(startPos.x, baseY, startPos.z)
	}

	/**
	 * Whether the carpets [distance] ahead of [from] lie on with no gap for [CARPET_LAYER_MIN], nothing on top, to bounce
	 * on like a layer. Only from down on the floor. A gap's edge stops you dead like a carpet's.
	 */
	private fun SafeContext.isCarpetLayer(from: Vec3d, dir: Vec3d, distance: Double): Boolean {
		if (onLayer) return false
		val up = from.add(0.0, CARPET_HEIGHT, 0.0)
		// The floor up there first, a gap is quick to find. The rays for what's on top take long.
		return generateSequence(distance + STEP_ON) { it + HOP_SCAN_STEP }.takeWhile { it <= distance + CARPET_LAYER_MIN }
			.all { up.add(dir.multiply(it)).boxHasFloor() } &&
			up.obstacleDistance(dir, distance + CARPET_LAYER_MIN, carpets = true) == null
	}

	/** Whether this spot along the line, e.g. [skipHopAt], is about at [at], the same obstacle seen again. */
	private fun Double?.near(at: Double) = this != null && abs(this - at) < 1.5

	/**
	 * Whether the obstacle [distance] ahead of [from] is a layer of blocks [STEP_HEIGHT] high to bounce on, with room
	 * to bounce up there for a bit. Only from down on the floor you started on, one more on top is in the way.
	 */
	private fun SafeContext.isStepUp(from: Vec3d, dir: Vec3d, distance: Double): Boolean {
		if (onLayer) return false
		val up = from.add(0.0, STEP_HEIGHT, 0.0)
		// Floor on up there for a while too: a single block lying askew has its own top a block on.
		return up.obstacleDistance(dir, distance + STEP_CLEAR) == null && up.add(dir.multiply(distance + STEP_ON)).boxHasFloor() &&
			up.add(dir.multiply(distance + STEP_CLEAR)).boxHasFloor()
	}

	/**
	 * How to glide over the obstacle [distance] ahead of [linePoint], as low as the gliding box gets over it all and the
	 * ones right behind it, with room above under [maxFlightY]. Null when it's too high or too long, or you can't get up
	 * there in time. With [carpets] over the carpets ahead.
	 */
	private fun SafeContext.planHop(
		linePoint: Vec3d, dir: Vec3d, distance: Double, step: Boolean = false, length: Double = STEP_ON, carpets: Boolean = false, lean: Int = 0,
		avoid: List<Box> = emptyList(), anyway: Boolean = false,
	): Hop? {
		// Blocks, and the [avoid] boxes, e.g. magma cubes the world's collisions don't count.
		fun free(box: Box) = spaceEmpty(box) && avoid.none { it.intersects(box) }
		val lineY = linePoint.y
		// As far out along each axis as the box swings across the way, it's in the way where the swing hits it. Tilted
		// [lean], only to that side: half as far each way, moved over that way by as much. Diagonally the box reaches as far
		// across only right at each spot looked at, a bit more covers in between them, as far as the rays look.
		val between = HOP_SCAN_STEP / 2 * 2 * abs(dir.x * dir.z) / dir.horizontalLengthSquared()
		val swing = sweep(dir) / (abs(dir.x) + abs(dir.z)) * dir.horizontalLength() / (if (lean == 0) 1 else 2) + between
		val shift = across(dir).multiply(lean * sweep(dir) / 2)
		fun box(along: Double, feetY: Double, height: Double = GLIDE_SIZE) = linePoint.add(dir.multiply(along)).add(shift).let {
			Box(it.x - GLIDE_SIZE / 2, feetY, it.z - GLIDE_SIZE / 2, it.x + GLIDE_SIZE / 2, feetY + height, it.z + GLIDE_SIZE / 2)
		}
		val maxY = maxFlightY()
		// Where it's in the way from, with carpets down to them.
		val floorY = lineY + if (carpets) CARPET_RAY else passerConfig.minObstacleHeight
		// As high as a bounce takes the box.
		val bounceHeight = (if (maxY.isFinite()) maxY else lineY + MAX_BOUNCE_RISE) + GLIDE_SIZE - floorY
		var end = distance
		var gap = 0.0
		var along = distance
		var onFloor = step
		// Carpets a few ticks apart are one: gliding over one ends low, coming down, too late to get up over the next.
		val maxGap = if (carpets) max(HOP_GAP, player.velocity.horizontalLength() * CARPET_GAP_TICKS) else HOP_GAP
		// Onto a layer to bounce on, only up to a bit onto it.
		if (step) end = distance + length
		else while (gap < maxGap) {
			along += HOP_SCAN_STEP
			val maxLength = if (carpets) MAX_CARPET_HOP_LENGTH else MAX_HOLE_LENGTH
			if (along > distance + maxLength) return noHop("longer than ${maxLength.toInt()}")
			if (free(box(along, floorY, bounceHeight).expand(swing, 0.0, swing))) gap += HOP_SCAN_STEP
			else {
				gap = 0.0
				end = along
				if (!free(box(along, floorY).expand(swing, 0.0, swing))) onFloor = true
			}
		}
		// Carpets the rays saw only at the edge of the swing, the box swinging out diagonally reaches as far only in its
		// middle. They're on the floor, glided over for as long as one is.
		if (!onFloor && carpets) {
			onFloor = true
			end = max(end, distance + 1 / max(abs(dir.x), abs(dir.z)))
		}
		// From where the box first touches it until it's past it, on the same spots as above.
		val reach = ceil(GLIDE_SIZE / HOP_SCAN_STEP) * HOP_SCAN_STEP
		val samples = generateSequence(distance - reach) { it + HOP_SCAN_STEP }.takeWhile { it <= end + reach }.toList()
		val start = distance - reach
		val stop = end + reach
		fun fits(y: Double) = samples.all { free(box(it, y).expand(swing, 0.0, swing)) }
		// Over carpets a bit higher than just clearing them, onto a layer of them only that.
		val maxHopY = maxHopY()
		val heights = generateSequence(lineY + if (carpets && !step) CARPET_GLIDE_FEET else passerConfig.minObstacleHeight) { it + HOP_HEIGHT_STEP }
			.takeWhile { it <= maxHopY }
		// Only overhead, glided under without touching the floor, a bounce would throw you up into it. Only if the box fits
		// right above the floor all the way, else something's in the way down there too that the rays above missed.
		val under = !onFloor && fits(heights.first())
		val feetY = heights.firstOrNull { fits(it) } ?: run {
			// What's in the way up there, for the log.
			val blocked = samples.firstOrNull { !free(box(it, maxHopY).expand(swing, 0.0, swing)) }
			val what = blocked?.let { along ->
				val box = box(along, maxHopY).expand(swing, 0.0, swing)
				if (!spaceEmpty(box)) "a block %.1f ahead".format(along) else "a mob's reach %.1f ahead".format(along)
			} ?: "nothing"
			return noHop("too high to get over under y %.2f, $what, %.1f long".format(maxHopY, end - distance))
		}
		// As high as there's room over it all, a roof or a block hanging above.
		val topY = heights.dropWhile { it < feetY }.takeWhile { fits(it) }.last()
		if (topY - feetY < HOP_MARGIN) return noHop("only %.2f room between y %.2f and %.2f".format(topY - feetY, feetY, topY))
		// Under it only with floor right after to come down on, else it's dodged and the height kept.
		if (under && !linePoint.add(dir.multiply(stop + HOP_SCAN_STEP)).boxHasFloor()) return noHop("no floor right after it to come down on")
		val at = progress(linePoint)
		return Hop(at + start, at + stop, feetY, topY, at + distance, step, low = carpets, under = under).takeIf { anyway || canGetOver(it, at) }
			?: noHop("can't get up there in time, $hopMiss").also { hopTooSlow = true }
	}

	/**
	 * Whether you get over [hop] from [progress] on. Over carpets bouncing on may well, else holding the height from now
	 * on, at the last moment later. Else holding it from close by, or from the next bounce like [holdingFor] may.
	 */
	private fun SafeContext.canGetOver(hop: Hop, progress: Double) = when {
		hop.held -> canHop(hop, progress)
		hop.low -> canHop(hop, progress, NO_HOLD) || canHop(hop, progress, 0)
		// The one from close by last, its miss is the one told and gone by, see [hopShort].
		else -> !hop.hole && !hop.under && fromNextBounce(hop, progress) || canHop(hop, progress)
	}

	/**
	 * Whether holding the height with [holdPitch] gets you over [hop] from [progress] on, bouncing off the floor
	 * on the way if need be. From [holdFrom] ticks on, by default once close enough like [holdingFor]. Simulates the glide
	 * tick by tick, at [from] a tick for now.
	 */
	private fun SafeContext.canHop(
		hop: Hop, progress: Double, holdFrom: Int? = null, from: Double = player.velocity.horizontalLength(), margin: Double = 0.0,
	): Boolean {
		val dir = getSnappedDir()
		val gravity = player.finalGravity
		var topped = hop.topped
		var rose = hop.rose
		val bounce = bouncePitch()
		var y = player.y
		var climb = player.velocity.y
		var speed = from
		var along = progress
		// Held already like hopPitch does, it doesn't let go again as the speed drops a little.
		var holding = hop.held
		// From now on, not only once holding: slow right after taking off, it's held from close by with no time to rise.
		var touched = hop.touched
		repeat(CAN_HOP_SIM_TICKS) { tick ->
			val touch = hop.touch
			// Down on the top to touch down on, bounced off it like the floor. From then on it keeps the height it has.
			if (touch != null && !touched && along in touch.range && y <= touch.y && climb <= 0) {
				y = touch.y
				climb = JUMP_VELOCITY
				speed += BOUNCE_SPEED_GAIN
				touched = true
				topped = true
			}
			// Only off floor that's there, e.g. not in a hole on the way.
			else if (y <= startPos.y && climb <= 0) {
				val floor = floorAt(dir, along, hop.lane) ?: run {
					hopMiss = "from y %.2f climbing %.2f at %.2f, it'd come down %.1f ahead with no floor to bounce off"
						.format(player.y, player.velocity.y, player.velocity.horizontalLength(), along - progress)
					hopShort = Double.POSITIVE_INFINITY
					return false
				}
				if (y <= floor) {
					y = floor
					climb = JUMP_VELOCITY
					speed += BOUNCE_SPEED_GAIN
				}
			}
			// Bouncing on until then.
			holding = holding || if (holdFrom != null) tick >= holdFrom else hop.start - along <= speed * leadTicks(hop)
			if (holding && hop.hole && (y >= hop.targetY - TOPPED_MARGIN || rose && climb <= 0)) topped = true
			if (holding && climb > 0) rose = true
			val pitch = if (holding) holdPitch(y, climb, speed, hop.holdY(y, along, speed, topped, touched), gravity, hop.sinkAt(along, speed, touched)) else bounce(y, climb)
			glide(climb, speed, pitch, gravity).let { (c, s) ->
				climb = c
				speed = s
			}
			y += climb
			along += speed
			// Over the top to touch down on coming down onto it, it stops the fall, from the next tick on it's bounced off.
			val onTop = hop.touch?.range?.contains(along) == true
			val low = if (onTop && !touched) Double.NEGATIVE_INFINITY else hop.feetAt(along) + if (onTop) 0.0 else margin
			if (along >= hop.start && (y < low || y > hop.topY)) {
				hopMiss = "from y %.2f climbing %.2f at %.2f, it'd be at y %.2f %.1f ahead, needs %.2f to %.2f"
					.format(player.y, player.velocity.y, player.velocity.horizontalLength(), y, along - progress, hop.feetAt(along), hop.topY)
				hopShort = if (y > hop.topY) Double.POSITIVE_INFINITY else hop.feetAt(along) - y
				return false
			}
			if (along > hop.end) {
				hopExitSpeed = speed
				return true
			}
		}
		hopMiss = "not over it within $CAN_HOP_SIM_TICKS ticks"
		hopShort = Double.POSITIVE_INFINITY
		return false
	}

	/**
	 * Where along the line, [along] it on [lane], there's floor to bounce off: the one bounced on, else the one below up
	 * on a layer. Null over a hole.
	 */
	private fun SafeContext.floorAt(dir: Vec3d, along: Double, lane: Vec3d): Double? {
		val spot = startPos.add(dir.multiply(along)).add(lane)
		return when {
			spot.boxHasFloor() -> startPos.y
			onLayer && spot.add(0.0, baseY - startPos.y, 0.0).boxHasFloor() -> baseY
			else -> null
		}
	}

	/**
	 * The way across the line you take for [distance] along it, steering with [steerYaw] from [offset] across it over
	 * to [target], then from [leanFrom] on tilted [lean] across it. The flight turns a tenth of the way to where you
	 * look each tick, so it lags behind.
	 */
	private fun SafeContext.steerPath(offset: Double, dir: Vec3d, target: Double, lean: Int, leanFrom: Double, distance: Double): List<SteerState> {
		val across = across(dir)
		val velocity = player.velocity
		var speed = velocity.horizontalLength()
		var heading = atan2(velocity.dotProduct(across), velocity.dotProduct(dir))
		var off = offset
		var along = 0.0
		val path = mutableListOf(SteerState(along, off, heading, speed))
		repeat(HOP_SIM_TICKS) {
			if (along >= distance) return path
			val drift = speed * sin(heading) * STEER_DAMPING_TICKS
			val aim = if (lean != 0 && along >= leanFrom) Math.toRadians(LEAN_DEGREES) * lean
				else atan2(target - off - drift, DODGE_STEER_AHEAD)
			heading += (aim - heading) * 0.1
			off += speed * sin(heading)
			along += speed * cos(heading)
			speed *= 0.99
			path.add(SteerState(along, off, heading, speed))
		}
		return path
	}

	/** Where [steerPath] has you [along] the line ahead, [offset] across it, flying [heading] off it at [speed]. */
	private class SteerState(val along: Double, val offset: Double, val heading: Double, val speed: Double)

	/**
	 * Magma cubes and hoglins are glided over like a block two high, never dodged or walked around: a cube
	 * jumps at you, a hoglin charges, flown past fast and high they don't get to.
	 */
	private fun Entity.glidedOver() = isAlive && (this is MagmaCubeEntity || this is HoglinEntity)

	/**
	 * The boxes of the magma cubes and hoglins up to [maxDistance] around [from], see [mobBox].
	 */
	private fun SafeContext.cubeBoxes(from: Vec3d, maxDistance: Double) =
		world.getOtherEntities(player, Box(from, from).expand(maxDistance, CUBE_SEARCH_HEIGHT, maxDistance)) { it.glidedOver() }
			.map { mobBox(it) }

	/**
	 * Where your gliding box mustn't be past [mob]: glided over like a block two high, the biggest just as high, and
	 * as far across as it attacks you, [CUBE_REACH], with room around that. Above or below it, it can't. One mid-jump
	 * is taken as down on what's below it again, the floor or the railing, it'll have landed by the time you're there.
	 */
	private fun SafeContext.mobBox(mob: Entity): Box {
		val body = mob.boundingBox.let { it.offset(0.0, (if (mob.isOnGround) it.minY else landingY(mob)) - it.minY, 0.0) }
		return Box(body.minX, body.minY, body.minZ, body.maxX, body.minY + max(CUBE_HEIGHT, body.lengthY), body.maxZ)
			.expand(CUBE_REACH + MAGMA_CUBE_MARGIN, 0.0, CUBE_REACH + MAGMA_CUBE_MARGIN)
	}

	/** Where [entity] in the air comes down, on the top of the first block below it, else on the line's floor. */
	private fun SafeContext.landingY(entity: Entity): Double {
		var pos = entity.blockPos
		repeat(CUBE_SEARCH_HEIGHT.toInt()) {
			val below = pos.down()
			val shape = world.getBlockState(below).getCollisionShape(world, below)
			if (!shape.isEmpty) return min(entity.y, below.y + shape.getMax(Direction.Axis.Y))
			pos = below
		}
		return min(entity.y, startPos.y)
	}

	/**
	 * Whether to hold the height for [hop] at [progress] along the line, once it's close enough. Over carpets only
	 * once bouncing on would come down on them and holding any later wouldn't make it, so the bounces before go on as
	 * they would, and within [MAX_CARPET_HOLD_TICKS]. Over a magma cube once holding any later wouldn't make it. Once
	 * held it stays held.
	 */
	private fun SafeContext.holdingFor(hop: Hop, progress: Double): Boolean {
		if (hop.held || hop.heldCheckedAt == player.age) return hop.held
		hop.heldCheckedAt = player.age
		val speed = player.velocity.horizontalLength()
		hop.held = when {
			// Over a hole from the last bounce before it, rising: up there from the jump, not pulling up later. With blocks
			// in it to get up over, from an earlier one when held from the next one it'd no longer get over them. Looked
			// at a bit later than that one, a near miss in the simulation doesn't let both slip by. Held from much
			// earlier it glides long and slows down a lot. At its edge there's no bouncing on, held anyway.
			hop.hole -> progress >= hop.start - speed || (lastBounceBefore(hop, progress) || hop.raised.isNotEmpty() &&
				player.velocity.y > 0 && !player.isOnGround && !fromNextBounce(hop, progress)) && canHop(hop, progress, 0)
			hop.low -> hop.start - progress <= speed * MAX_CARPET_HOLD_TICKS &&
				!canHop(hop, progress, NO_HOLD) && !canHop(hop, progress, CARPET_HOLD_DELAY) && canHop(hop, progress, 0)
			hop.anyway -> true
			// Not only from close by: slow, e.g. right after taking off, it climbs up there from farther. Under one up on
			// the railing too, gliding low loses speed fast.
			hop.cube -> holdCube(hop, progress)
			// Gliding low loses speed, so it bounces on as long as coming down a bit later still gets it under.
			hop.under -> hop.start - progress <= speed * leadTicks(hop) && !canHop(hop, progress, UNDER_HOLD_DELAY)
			// Over a block or a wall, or through an opening in one: one more bounce while from it it still gets over,
			// taking off from the floor up there faster than pulling up gliding.
			else -> hop.start - progress <= speed * leadTicks(hop) && !fromNextBounce(hop, progress)
		}
		if (hop.held && hop.cube) ElytraFly.debug { "Gliding ${if (hop.under) "down under" else "up over"} the mob %.1f ahead at %.2f".format(hop.start - progress, speed) }
		return hop.held
	}

	/**
	 * Whether holding the height for [hop] from the next bounce on still gets over it, a bit later than that bounce
	 * rises and with a bit to spare: a bounce ahead the simulation is off by a little, a near miss would let that one and
	 * this one both slip by.
	 */
	private fun SafeContext.fromNextBounce(hop: Hop, progress: Double) =
		nextRise(progress, hop.lane)?.let { canHop(hop, progress, it + NEXT_BOUNCE_MARGIN, margin = NEXT_BOUNCE_HEIGHT_MARGIN) } == true

	/**
	 * In how many ticks the next bounce off the floor rises, bouncing on from here at [progress] along the line on [lane].
	 * Null when it'd come down where there's no floor, or not within the simulation.
	 */
	private fun SafeContext.nextRise(progress: Double, lane: Vec3d): Int? {
		val dir = getSnappedDir()
		val gravity = player.finalGravity
		val bounce = bouncePitch()
		var y = player.y
		var climb = player.velocity.y
		var speed = player.velocity.horizontalLength()
		var along = progress
		repeat(CAN_HOP_SIM_TICKS) { tick ->
			glide(climb, speed, bounce(y, climb), gravity).let { (c, s) ->
				climb = c
				speed = s
			}
			y += climb
			along += speed
			// Coming down where there's no floor there's no next bounce.
			if (y <= startPos.y) floorAt(dir, along, lane)?.let { if (y <= it) return tick + 1 } ?: return null
		}
		return null
	}

	/**
	 * Whether you're rising from the last bounce before the hole [hop] glides over: bouncing on, you'd come down at its
	 * edge or past it, too close to bounce once more.
	 */
	private fun SafeContext.lastBounceBefore(hop: Hop, progress: Double): Boolean {
		if (player.velocity.y <= 0 || player.isOnGround) return false
		val dir = getSnappedDir()
		val gravity = player.finalGravity
		val bounce = bouncePitch()
		var y = player.y
		var climb = player.velocity.y
		var speed = player.velocity.horizontalLength()
		var along = progress
		repeat(CAN_HOP_SIM_TICKS) {
			glide(climb, speed, bounce(y, climb), gravity).let { (c, s) ->
				climb = c
				speed = s
			}
			y += climb
			along += speed
			if (y <= startPos.y) {
				// Down in it, or on its edge or past it, too close to bounce once more.
				val floor = floorAt(dir, along, hop.lane) ?: return true
				if (y <= floor) return along >= hop.start - HOLE_TOUCHDOWN_MARGIN
			}
		}
		return true
	}

	/**
	 * Whether to hold the height over the magma cube of [hop] now, once from the next bounce it wouldn't make it. Gliding
	 * over it late and short keeps the speed, and it has less time to notice you and jump. Fast enough to still
	 * be after it, see [HIGH_CUBE_EXIT_SPEED], at its [Hop.highAim] instead: farther off the cube, more time to see
	 * what's next. That one's held a bit sooner, it's higher up. Decided now, not when planned far off and slower.
	 */
	private fun SafeContext.holdCube(hop: Hop, progress: Double): Boolean {
		val high = hop.highAim?.let { Hop(hop.start, hop.end, hop.feetY, hop.topY, hop.obstacle, hop.step, lane = hop.lane, cube = true, aim = it) }
			?.takeIf { canHop(it, progress, 0) && hopExitSpeed >= HIGH_CUBE_EXIT_SPEED }
			?: return !fromNextBounce(hop, progress)
		if (fromNextBounce(high, progress)) return false
		high.held = true
		high.heldCheckedAt = player.age
		this@ObstaclePassingMode.hop = high
		ElytraFly.debug { "Fast enough to glide over the mob high, at y %.2f, %.2f after it".format(high.targetY, hopExitSpeed) }
		return true
	}

	/**
	 * How many ticks before [hop] to start holding the height, more the higher it is above the floor. From down on the
	 * floor, not bouncing up yet, a block up doesn't make it in [HOP_LEAD_TICKS] alone. Under something it comes down
	 * from the bounces long before, the room below is narrow.
	 */
	private fun SafeContext.leadTicks(hop: Hop) =
		HOP_LEAD_TICKS + (hop.targetY - startPos.y).coerceAtLeast(0.0) / MAX_HOLD_CLIMB + if (hop.under) UNDER_LEAD_TICKS else 0

	/**
	 * The pitch that gets [climb] closest to closing in on [target] smoothly, from [y] at [speed], sinking at most [sink]
	 * a tick.
	 */
	private fun holdPitch(y: Double, climb: Double, speed: Double, target: Double, gravity: Double, sink: Double = HOLD_SINK): Double {
		val wanted = ((target - y) * 0.3).coerceIn(-sink, MAX_HOLD_CLIMB)
		fun miss(pitch: Int) = abs(glide(climb, speed, pitch.toDouble(), gravity).first - wanted)
		// Every few degrees and then each one around the best, simulated a few hundred times a tick.
		val coarse = (-MAX_HOP_PITCH_UP..90 step HOLD_PITCH_STEP).minBy(::miss)
		return (max(-MAX_HOP_PITCH_UP, coarse - HOLD_PITCH_STEP + 1)..min(90, coarse + HOLD_PITCH_STEP - 1)).minBy(::miss).toDouble()
	}

	/** Vanilla's elytra glide, the climb and speed after a tick at [pitch] facing where you fly. */
	private fun glide(climb: Double, speed: Double, pitch: Double, gravity: Double): Pair<Double, Double> {
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
		return y * 0.98 to horizontal * 0.99
	}

	/**
	 * How far you get from braking now until you stand: coming down straight, with no lift, then sliding on until
	 * the server ends the glide. The speed hardly drops on the way. Farther than this an obstacle may wait.
	 */
	private fun SafeContext.brakeDistance(): Double {
		val speed = player.velocity.horizontalLength()
		val now = speed * (fallTicks(player.y, player.velocity.y) + LANDING_TICKS) + BRAKE_MARGIN
		// Down now, braking takes least, but a tick later the bounce has you up again for the longest way down.
		return if (!player.isOnGround) now else max(now, speed * (1 + fallTicks(startPos.y, JUMP_VELOCITY) + LANDING_TICKS) + BRAKE_MARGIN)
	}

	/** How many ticks it takes from [y] climbing at [climb] to come down on the line, falling straight. */
	private fun SafeContext.fallTicks(y: Double, climb: Double): Int {
		var height = y
		var velocity = climb
		var ticks = 0
		do {
			velocity = (velocity - player.finalGravity) * 0.98
			height += velocity
			ticks++
		} while (height > startPos.y && ticks < HOP_SIM_TICKS)
		return ticks
	}

	/** Why the last [planHop] found no way over, for the debug log. Always null. */
	private fun noHop(reason: String): Hop? {
		hopRejection = reason
		return null
	}

	/** Logs once that the obstacle at [at] along the line has no free lane past it yet, looked at again next tick. Always false. */
	private fun noLaneYet(at: Double, reason: String): Boolean {
		if (noLaneAt?.let { abs(it - at) < 1.5 } != true) ElytraFly.debug { reason }
		noLaneAt = at
		noWayYet.values.removeIf { passTicks - it >= RELOOK_TICKS }
		noWayYet[at.roundToLong()] = passTicks
		return false
	}

	/**
	 * Whether the obstacle or hole at [at] along the line, [distance] ahead, found no way past a moment ago and is still
	 * far. Looking again only every [RELOOK_TICKS] then, every lane and glide over it is simulated each time. Close by
	 * each tick.
	 */
	private fun SafeContext.lookedLately(at: Double, distance: Double): Boolean {
		noWayYet.values.removeIf { passTicks - it >= RELOOK_TICKS }
		return distance > brakeDistance() + DODGE_STEER_AHEAD && (-1..1).any { at.roundToLong() + it in noWayYet }
	}

	/** Stops dodging and lands, to walk around the obstacle at [at] along the line, or past the [carpets]. Always false. */
	private fun brake(at: Double, reason: String, carpets: Boolean = false): Boolean {
		// Over a hole there's nothing to land on: over to floor beside it, else it glides on and looks again, there may be
		// floor farther on.
		if (runSafe { overHole() } == true) {
			if (runSafe { landBeside(at, reason) } == true) return false
			return noLaneYet(at, "$reason, over a hole, gliding on")
		}
		ElytraFly.debug { "$reason, landing to walk around it" }
		landedTicks = 0
		stopDodge()
		hop = null
		brakeAt = at
		brakeCarpets = carpets
		brakeY = startPos.y
		return false
	}

	/**
	 * Over a hole with no way past what's at [at]: over to a lane beside it with floor a bit ahead, or a layer a block up
	 * to land on top of, steered to like a dodge. Lands once over it, see [landing]. Whether there's one.
	 */
	private fun SafeContext.landBeside(at: Double, reason: String): Boolean {
		val dir = getSnappedDir()
		val linePoint = player.pos.findClosestPointOnLine(dir)
		val across = across(dir)
		val here = Vec3d(player.x - linePoint.x, 0.0, player.z - linePoint.z).dotProduct(across)
		val ahead = linePoint.add(dir.multiply(player.velocity.horizontalLength() * LAND_AHEAD_TICKS))
		val step = laneStep(dir) / 4
		val sides = (passerConfig.maxDodge / step + 1e-6).toInt()
		// Room at your height on the way over there too, beside the hole may be right at the wall. The box swings out at speed.
		val swing = sweep(dir)
		fun roomAt(spot: Vec3d) = spaceEmpty(Box(spot.x - GLIDE_SIZE / 2 - swing, player.y, spot.z - GLIDE_SIZE / 2 - swing,
			spot.x + GLIDE_SIZE / 2 + swing, player.y + GLIDE_SIZE, spot.z + GLIDE_SIZE / 2 + swing))
		fun fits(offset: Double) = ahead.add(across.multiply(offset)).let { spot ->
			spot.isLoaded && (spot.boxHasFloor() || spot.add(0.0, 1.0, 0.0).let { up -> up.boxHasFloor() && up.canStand() }) &&
				roomAt(spot) && roomAt(linePoint.add(dir.multiply(player.velocity.horizontalLength() * LAND_AHEAD_TICKS / 2)).add(across.multiply((here + offset) / 2)))
		}
		// The one picked before while it still fits, picking again each tick it'd swing from side to side.
		val offset = dodgeOffset.takeIf { landing && dodge != null && fits(it) }
			?: (-sides..sides).map { it * step }.sortedBy { abs(it - here) }.firstOrNull(::fits) ?: return false
		if (!landing || offset != dodgeOffset) ElytraFly.debug { "$reason, over a hole, landing beside it %+.2f across".format(offset) }
		dodgeTo(Lane(across.multiply(offset), 0, offset), at)
		landing = true
		return true
	}

	/** Floor under you, or a layer a block up, to land on, see [landBeside]. */
	private fun SafeContext.floorBelowYou() = Vec3d(player.x, startPos.y, player.z).let { feet ->
		feet.boxHasFloor() || feet.add(0.0, 1.0, 0.0).let { up -> player.y >= up.y && up.boxHasFloor() }
	}

	/** Gliding above the floor's height over a hole. Fallen into it, it walks out. */
	private fun SafeContext.overHole() =
		player.isGliding && !player.isOnGround && player.y >= startPos.y - FELL_IN &&
			Vec3d(player.x, startPos.y, player.z).isLoaded && !floorBelowYou()

	private fun Vec3d.describe(dir: Vec3d): String {
		val offset = dotProduct(across(dir))
		return if (abs(offset) < 0.01) "the line" else "%.2f to the ${if (offset > 0) "right" else "left"}".format(abs(offset))
	}

	/** How far along the flight line [pos] is. */
	private fun SafeContext.progress(pos: Vec3d) = pos.subtract(startPos).dotProduct(getSnappedDir())

	/**
	 * How far ahead along [direction] the first block is that would hit you, low or, with head hitters, at head
	 * height, across your width. Null when none is within [maxDistance].
	 */
	context(safeContext: SafeContext)
	private fun Vec3d.obstacleDistance(direction: Vec3d, maxDistance: Double, extra: Double = 0.0, lean: Int = 0, speed: Double? = null, carpets: Boolean = false) =
		obstacleHit(direction, maxDistance, extra, lean, speed, carpets)?.distance

	/**
	 * How far ahead [obstacleDistance] is, and the block there. [extra] wider to either side, and with the heading
	 * tilted [lean] across the way, the box only swings out that side. Swinging as far as at [speed], by default yours.
	 */
	context(safeContext: SafeContext)
	private fun Vec3d.obstacleHit(direction: Vec3d, maxDistance: Double, extra: Double = 0.0, lean: Int = 0, speed: Double? = null, carpets: Boolean = false, mobs: Boolean = true) =
		obstacleHits(direction, maxDistance, extra, lean, speed, carpets, mobs).first

	/**
	 * Like [obstacleHit], the first in the way with [carpets] and, on its own, the first that's not one of them. Looked
	 * for at once, each costs a few dozen rays as far ahead as it looks.
	 */
	context(safeContext: SafeContext)
	private fun Vec3d.obstacleHits(direction: Vec3d, maxDistance: Double, extra: Double = 0.0, lean: Int = 0, speed: Double? = null, carpets: Boolean = false, mobs: Boolean = true): Pair<Obstacle?, Obstacle?> {
		val across = across(direction)
		val halfWidth = halfWidth(direction) + extra
		val sweep = with(safeContext) { speed?.let { sweep(direction, it) } ?: sweep(direction) }
		val low = -halfWidth - if (lean > 0) 0.0 else sweep
		val high = halfWidth + if (lean < 0) 0.0 else sweep
		val rays = ceil((high - low) / RAY_SPACING).toInt()
		val offsets = (0..rays).map { across.multiply(low + (high - low) * it / rays) }
		// Up to the top of the gliding box, as high as it may fly, see [roofInTheWay].
		val glideTop = with(safeContext) { maxFlightY() } + GLIDE_SIZE - y - WIDTH_MARGIN
		val heights = if (passerConfig.headHitters) listOf(passerConfig.minObstacleHeight, 1.01, 1.99) else listOf(passerConfig.minObstacleHeight)
		val roofStarts = if (passerConfig.headHitters && glideTop > 1.99 && glideTop.isFinite()) offsets.map { add(it).add(0.0, glideTop, 0.0) } else emptyList()
		// Carpets as far as the box swings out too: touching down, you move down first and then along each axis, along
		// the floor, and diagonally into one beside the way.
		val carpetStarts = if (!carpets) emptyList() else offsets.map { add(it).add(0.0, CARPET_RAY, 0.0) }
		val blockStarts = heights.flatMap { height -> offsets.map { add(it).add(0.0, height, 0.0) } }
		val starts = carpetStarts + blockStarts + roofStarts
		// Boats, minecarts and shulkers are as solid as blocks to you, the rays only see blocks. Magma cubes too, of any
		// size, their box grows with it: gliding into one hurts and throws you off. The rays are close enough for the
		// smallest.
		val reach = direction.normalize().multiply(maxDistance)
		val around = starts.fold(Box(starts.first(), starts.first())) { box, start -> box.union(Box(start, start)) }
		val solid = safeContext.world.getOtherEntities(safeContext.player, around.union(around.offset(reach)).expand(CUBE_REACH + MAGMA_CUBE_MARGIN)) {
			safeContext.player.collidesWith(it) || mobs && it.glidedOver()
		}.map { it to if (it.glidedOver()) with(safeContext) { mobBox(it) } else it.boundingBox }
		// Past one already hit no ray needs to look, that's most of the way for most of them.
		fun nearest(starts: List<Vec3d>, within: Double): Obstacle? {
			var nearest: Obstacle? = null
			for (start in starts) {
				// Not back the other way, from inside one it's right there.
				val range = nearest?.distance ?: within
				if (range > 0) safeContext.blockRay(start, direction, range)?.let { hit ->
						val distance = hit.pos.subtract(start).dotProduct(direction)
						if (distance < (nearest?.distance ?: Double.POSITIVE_INFINITY))
							nearest = Obstacle(distance) { "${Registries.BLOCK.getId(safeContext.blockState(hit.blockPos).block).path} ${hit.blockPos.toShortString()}" }
					}
				solid.forEach { (entity, box) ->
					val glidedOver = entity.glidedOver()
					box.raycast(start, start.add(reach)).orElse(null)?.let {
						val distance = it.subtract(start).dotProduct(direction)
						if (distance < (nearest?.distance ?: Double.POSITIVE_INFINITY))
							nearest = Obstacle(distance, glidedOver) { "${Registries.ENTITY_TYPE.getId(entity.type).path}${if (entity is MagmaCubeEntity) " size ${entity.size}" else ""} ${entity.blockPos.toShortString()}" }
					}
				}
			}
			return nearest
		}
		val below = nearest(blockStarts, maxDistance)
		val block = (if (roofInTheWay()) nearest(roofStarts, below?.distance ?: maxDistance) else null) ?: below
		// Carpets only before it, past it they don't matter.
		val carpet = nearest(carpetStarts, block?.distance ?: maxDistance)
		return (if (carpet != null && (block == null || carpet.distance < block.distance)) carpet else block) to block
	}

	/** Each block's collision shape this tick. The planner looks at the same blocks dozens of times a tick. */
	private val shapes = Long2ObjectOpenHashMap<VoxelShape>()
	/** Entities as solid as blocks around you this tick, e.g. boats, like [World.isSpaceEmpty] counts them. */
	private var solidEntities: List<Box> = emptyList()
	private var shapeContext: ShapeContext = ShapeContext.absent()
	private var shapesAge = -1
	/** Which world they're of, by identity only: holding on to it would keep a world left behind in memory. */
	private var shapesWorld = 0

	/** Starts the caches over each tick, blocks change between ticks. */
	private fun SafeContext.freshShapes() {
		if (shapesAge == player.age && shapesWorld == System.identityHashCode(world)) return
		shapesAge = player.age
		shapesWorld = System.identityHashCode(world)
		shapes.clear()
		shapeContext = ShapeContext.of(player)
		solidEntities = world.getOtherEntities(player, player.boundingBox.expand(SOLID_ENTITY_RANGE)) { player.collidesWith(it) }.map { it.boundingBox }
	}

	/** Lets go of the caches, the shape context holds on to you and through you the world. */
	private fun dropShapes() {
		shapes.clear()
		shapes.trim()
		solidEntities = emptyList()
		shapeContext = ShapeContext.absent()
		shapesAge = -1
	}

	private fun SafeContext.shapeAt(pos: BlockPos): VoxelShape {
		freshShapes()
		val key = pos.asLong()
		return shapes.get(key) ?: world.getBlockState(pos).getCollisionShape(world, pos, shapeContext).also { shapes.put(key, it) }
	}

	/**
	 * Like [World.isSpaceEmpty] for you: no block, entity as solid as one or the world border in [box]. From one below,
	 * a fence reaches up into it.
	 */
	private fun SafeContext.spaceEmpty(box: Box): Boolean {
		freshShapes()
		if (!world.worldBorder.contains(box) || solidEntities.any { it.intersects(box) }) return false
		val pos = BlockPos.Mutable()
		for (x in floor(box.minX).toInt()..floor(box.maxX).toInt())
			for (z in floor(box.minZ).toInt()..floor(box.maxZ).toInt())
				for (y in floor(box.minY).toInt() - 1..floor(box.maxY).toInt()) {
					val shape = shapeAt(pos.set(x, y, z))
					if (shape.isEmpty) continue
					val hit = if (shape === VoxelShapes.fullCube()) box.intersects(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)
						else shape.boundingBoxes.any { it.offset(x.toDouble(), y.toDouble(), z.toDouble()).intersects(box) }
					if (hit) return false
				}
		return true
	}

	/** Where a ray from [start] along [direction] first hits a block's collision shape within [reach], like [rayCast]. */
	private fun SafeContext.blockRay(start: Vec3d, direction: Vec3d, reach: Double): BlockHitResult? {
		val end = start.add(direction.multiply(reach))
		return BlockView.raycast<BlockHitResult?, Unit>(start, end, Unit, { _, pos -> shapeAt(pos).raycast(start, end, pos) }, { null })
	}

	/**
	 * Whether a roof only the highest ray hits is in the way, two blocks or more above the floor of the line at [Vec3d.y]:
	 * bouncing on under it, e.g. in a tunnel two high, the bounces bump it at most. Only flying higher than that, e.g.
	 * coasting high to slow down, it's in the way.
	 */
	context(safeContext: SafeContext)
	private fun Vec3d.roofInTheWay() = safeContext.player.y + GLIDE_SIZE > y + 2 + ROOF_BUMP

	/** What [obstacleDistance] ran into, for the debug log. */
	context(safeContext: SafeContext)
	private fun Vec3d.obstacleBlock(direction: Vec3d, maxDistance: Double) = obstacleHit(direction, maxDistance)?.what ?: "nothing"

	context(safeContext: SafeContext)
	private fun Vec3d.hasFloor() = flooredBlockPos.down().let { safeContext.blockState(it).isSolidBlock(safeContext.world, it) }

	/** Whether there's floor again within [length] ahead of this along [dir]. */
	context(safeContext: SafeContext)
	private fun Vec3d.floorWithin(dir: Vec3d, length: Double) =
		generateSequence(HOP_SCAN_STEP) { it + HOP_SCAN_STEP }.takeWhile { it <= length }.any { add(dir.multiply(it)).boxHasFloor() }

	/** Whether anything under your box here holds you up. A block's corner does, as where a layer's edge runs askew. */
	context(safeContext: SafeContext)
	private fun Vec3d.boxHasFloor() =
		!safeContext.spaceEmpty(Box(x - 0.29, y - 0.05, z - 0.29, x + 0.29, y, z + 0.29))

	/** The blocks a block below to one above under your box here, for the debug log. */
	context(safeContext: SafeContext)
	private fun Vec3d.blocksAround() =
		BlockPos.iterate(BlockPos.ofFloored(x - 0.29, y - 1, z - 0.29), BlockPos.ofFloored(x + 0.29, y, z + 0.29)).joinToString(", ") {
			"${Registries.BLOCK.getId(safeContext.blockState(it).block).path} ${it.toShortString()}"
		}

	/**
	 * The yaw over [hop], onto its lane. Tilted the way the dodge is, the box swinging out touching down only to that
	 * side, away from what's dodged.
	 */
	private fun SafeContext.hopYaw(linePoint: Vec3d, hop: Hop, dir: Vec3d, progress: Double) = when {
		// Over to the lane with the top to touch down on from shortly before it until bounced off it, see [laneTouch].
		hop.touch != null && !hop.touched && progress >= hop.touch.range.start - TOUCH_APPROACH -> steerYaw(linePoint, hop.touch.lane, dir)
		dodge != null && hop.lane == dodge && leaning(progress) -> dodgeLeanYaw(linePoint, dir)
		else -> steerYaw(linePoint, hop.lane, dir)
	}

	/**
	 * The tilted yaw past what's dodged. Past carpets steering on over to the lane first, heading that way the box swings
	 * out to that side already, and once on it only just tilted enough for that: they may lie along it for long, tilted
	 * all the way you'd drift on into the wall.
	 */
	private fun SafeContext.dodgeLeanYaw(linePoint: Vec3d, dir: Vec3d): Double {
		if (!dodgeCarpets) return leanYaw(dir, dodgeLean)
		return if (onDodgeLane(linePoint, dir)) leanYaw(dir, dodgeLean, HOLD_LEAN_DEGREES) else steerYaw(linePoint, dodge ?: Vec3d.ZERO, dir)
	}

	/** Whether you're over on the lane dodged to, or past it the way the heading tilts. */
	private fun SafeContext.onDodgeLane(linePoint: Vec3d, dir: Vec3d) =
		(Vec3d(player.x - linePoint.x, 0.0, player.z - linePoint.z).dotProduct(across(dir)) - dodgeOffset) * dodgeLean >= 0

	/** Whether the heading is tilted past what's dodged at [progress] along the line, from shortly before it on. */
	private fun SafeContext.leaning(progress: Double) =
		dodgeLean != 0 && progress >= dodgeEnd - player.velocity.horizontalLength() * LEAN_TICKS

	/** Back onto the line, still dodging until on it. */
	private fun swingBack() {
		dodge = Vec3d.ZERO
		onLane = false
		fitDodge = false
		atFitSpeed = false
		dodgeLean = 0
		dodgeOffset = 0.0
	}

	/** Always false, for returning it from [steerDodge]. */
	private fun stopDodge(): Boolean {
		dodge = null
		onLane = false
		dodgeAfterMob = false
		slowFor = null
		fitDodge = false
		atFitSpeed = false
		landing = false
		dodgeLean = 0
		dodgeOffset = 0.0
		dodgeCarpets = false
		dodgeYaw = null
		return false
	}

	protected fun SafeContext.getSnappedDir(): Vec3d {
		// Across only, the line may be on the floor below you or a layer up, which would shorten it.
		val travelDiff = Vec3d(player.x - startPos.x, 0.0, player.z - startPos.z).normalize()
		return lockYawToStep(travelDiff)
	}

	private fun lockYawToStep(vector: Vec3d): Vec3d {
		// No step, no snapping. Dividing by it would round NaN, which throws.
		if (passerConfig.directionStep <= 0.0) return vector
		val yaw = atan2(vector.z, vector.x)
		val yawDegrees = Math.toDegrees(yaw)

		val normalizedYaw = (yawDegrees % 360.0 + 360.0) % 360.0

		val steps = normalizedYaw / passerConfig.directionStep
		val roundedSteps = steps.roundToInt()
		val lockedYawDegrees = roundedSteps * passerConfig.directionStep

		val normalizedLockedYawDegrees = (lockedYawDegrees % 360.0 + 360.0) % 360.0
		val lockedYaw = Math.toRadians(normalizedLockedYawDegrees)

		val horizontalLength = hypot(vector.x, vector.z)
		val x = cos(lockedYaw) * horizontalLength
		val z = sin(lockedYaw) * horizontalLength

		return Vec3d(x, vector.y, z)
	}

	/**
	 * Walks to the first clear point a few look-aheads along [dir]. False when there's none in reach,
	 * e.g. behind the world border or in unloaded chunks, Baritone can't get there. With [carpets] none of them ahead
	 * of it either.
	 */
	private fun SafeContext.pathToValidPoint(startSearchPos: Vec3d, dir: Vec3d, guaranteeOneSkip: Boolean = true, carpets: Boolean = false): Boolean {
		// Touching down between bounces you still glide. Walking off like that, packets are held back and the sprint is
		// kept, the server takes your input and speed wrong and sets back every step. So land first, like for a brake.
		if (!landed && player.getFlag(Entity.GLIDING_FLAG_INDEX))
			return brake(progress(startSearchPos) + if (guaranteeOneSkip) 1.0 else 0.0, "Still gliding", carpets)
		// Block by block. Best a spot with room to take off again after it, walking through all that's in the way at
		// once. Else, among obstacles close together, the first spot to stand with a few free after it.
		val first = if (guaranteeOneSkip) 1.0 else 0.0
		// On the floor you started on, also from up on a layer. Layers are only bounced on, taking off is from the floor.
		val spots = generateSequence(first) { it + 1 }
			.takeWhile { it <= if (carpets) CARPET_WALK_REACH else MAX_PASS_STEPS * passerConfig.obstacleLookAhead.toDouble() }
			.map { Vec3d(startSearchPos.x, baseY, startSearchPos.z).add(dir.multiply(it)) }
			.takeWhile { it.isLoaded && world.worldBorder.contains(it.flooredBlockPos) }
			// Where you stand already Baritone is done at once, and it's picked again and again.
			.filter { it.flooredBlockPos != player.blockPos && it.canStand() }
			.toList()
		val goal = if (carpets)
			// Room after it to take off and get up to speed short of more of them, else as far on through them as you can.
			spots.firstOrNull { !it.isObstructed(dir, CARPET_TAKEOFF_CLEAR, true) } ?: spots.lastOrNull { !it.isObstructed(dir, WALK_GOAL_CLEAR) }
		else spots.firstOrNull { !it.isObstructed(dir) } ?: spots.firstOrNull { !it.isObstructed(dir, WALK_GOAL_CLEAR) }
		if (goal == null) return passFailed()
		return passTo(goal)
	}

	/**
	 * Whether what you bumped into along [dir] is no higher above your feet than [PasserSettings.minObstacleHeight],
	 * like a carpet's edge, so a little higher you'd be past it.
	 */
	private fun SafeContext.bumpedLowEdge(dir: Vec3d) =
		world.isSpaceEmpty(player, player.boundingBox.offset(dir.normalize().multiply(WALL_PROBE)).offset(0.0, passerConfig.minObstacleHeight, 0.0))

	/** Whether you fit standing at this spot. */
	context(safeContext: SafeContext)
	private fun Vec3d.canStand() = safeContext.world.isSpaceEmpty(safeContext.player, Box(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3))

	private fun SafeContext.passTo(pos: Vec3d): Boolean {
		// The same goal again would restart Baritone's path every tick.
		if (BaritoneHandler.isActive && passingToPos?.flooredBlockPos == pos.flooredBlockPos) return true
		// Picked again after getting there, walking there got nowhere.
		if (pos.flooredBlockPos == lastGoal) {
			if (++sameGoals >= MAX_SAME_GOALS) {
				sameGoals = 0
				return passFailed()
			}
		} else sameGoals = 0
		lastGoal = pos.flooredBlockPos
		passingToPos = pos
		ElytraFly.debug { "Walking to ${pos.flooredBlockPos.toShortString()}" }
		// Bounce sprints while gliding, a sprint the server doesn't take. Walking on with it, every step is faster than
		// the server allows and set back, until sprinting starts over. So it starts over here.
		player.isSprinting = false
		// Onto it, getting next to it Baritone takes for done without a step.
		BaritoneHandler.setGoalAndPath(GoalBlock(pos.flooredBlockPos))
		return true
	}

	/**
	 * After a few passes that went nowhere, stands still for [PASS_COOLDOWN_TICKS] and looks again, e.g. once the chunks
	 * around are loaded or the way is cleared. It never stops for it. Always false.
	 */
	private fun SafeContext.passFailed(): Boolean {
		ElytraFly.debug { "No way past found" }
		if (++failedPasses < MAX_FAILED_PASSES) return false
		failedPasses = 0
		passingToPos = null
		BaritoneHandler.cancel()
		// Flying on into it only bumps into it again and again, each bump hurts.
		warn("Can't get past the obstacle at ${player.blockPos.toShortString()}, looking again in ${PASS_COOLDOWN_TICKS / 20} seconds")
		passCooldown = PASS_COOLDOWN_TICKS
		return false
	}

	protected fun Vec3d.findClosestPointOnLine(snappedDirection: Vec3d): Vec3d {
		val startToCurrent = subtract(startPos)
		val t = startToCurrent.dotProduct(snappedDirection) / snappedDirection.dotProduct(snappedDirection)
		return startPos.add(snappedDirection.multiply(t))
	}

	context(safeContext: SafeContext)
	private fun Vec3d.isObstructed(direction: Vec3d, distance: Double = passerConfig.obstacleLookAhead.toDouble(), carpets: Boolean = false) =
		if (!isLoaded) false
		else {
			!boxHasFloor() || blockedLow(direction, distance) || passerConfig.headHitters && blockedHigh(direction, distance) ||
				carpets && alongWidth(direction, CARPET_RAY, distance)
		}

	/**
	 * Rays along both edges of your width as well, a block beside the middle one still hits you.
	 */
	context(safeContext: SafeContext)
	private fun Vec3d.alongWidth(direction: Vec3d, height: Double, distance: Double): Boolean {
		val edge = across(direction).multiply(halfWidth(direction))
		val start = add(0.0, height, 0.0)
		return start.rayCastObstructed(direction, distance) || start.add(edge).rayCastObstructed(direction, distance) ||
			start.subtract(edge).rayCastObstructed(direction, distance)
	}

	context(safeContext: SafeContext)
	private fun Vec3d.blockedLow(direction: Vec3d, distance: Double = passerConfig.obstacleLookAhead.toDouble()) =
		alongWidth(direction, passerConfig.minObstacleHeight, distance)

	context(safeContext: SafeContext)
	private fun Vec3d.blockedHigh(direction: Vec3d, distance: Double = passerConfig.obstacleLookAhead.toDouble()) =
		alongWidth(direction, 1.01, distance) || alongWidth(direction, 1.99, distance)

	/** Which check of [isObstructed] failed, for the debug log. */
	context(safeContext: SafeContext)
	private fun Vec3d.obstruction(direction: Vec3d, distance: Double = passerConfig.obstacleLookAhead.toDouble()) = when {
		!boxHasFloor() -> "not above solid ground"
		blockedLow(direction, distance) -> "blocked low"
		passerConfig.headHitters && blockedHigh(direction, distance) -> "blocked at head height"
		else -> "no room to stand"
	}

	/**
	 * Whether something is in the way of gliding [side] over from here, low or, with head hitters, at head height.
	 * Not carpets, bouncing you're over them.
	 */
	context(safeContext: SafeContext)
	private fun Vec3d.sidewaysObstructed(side: Vec3d): Boolean {
		val direction = side.normalize()
		val distance = side.length() + 0.3
		return add(0.0, passerConfig.minObstacleHeight, 0.0).rayCastObstructed(direction, distance) ||
			passerConfig.headHitters && (add(0.0, 1.01, 0.0).rayCastObstructed(direction, distance) ||
				add(0.0, 1.99, 0.0).rayCastObstructed(direction, distance))
	}

	context(safeContext: SafeContext)
	private fun Vec3d.rayCastObstructed(direction: Vec3d, distance: Double = passerConfig.obstacleLookAhead.toDouble()) =
		safeContext.rayCast(
			this,
			direction,
			distance,
			InteractionMask.Block,
			RaycastContext.ShapeType.COLLIDER
		)?.blockResult != null
}

class PasserSettings(override val c: Config) : ConfigBlock {
	val passObstacles by c.setting("Pass Obstacles", true, "Automatically paths around obstacles using baritone")
	val walkWhenFlagged by c.setting("Walk When Flagged", false, "Triggers obstacle passer when the server forces your position (typically getting flagged by the anticheat)") { passObstacles }
	val minObstacleHeight by c.setting("Min Obstacle Height", 0.063, 0.0..1.0, 0.0001, "The minimum height an obstacle must be above the ground to trigger obstacle passer") { passObstacles }
	val headHitters by c.setting("Head Hitters", true, "Flags obstacles above the y level you started flying at") { passObstacles }
	val acceptableOffsetRange by c.setting("Acceptable Offset Range", 2.0, 0.1..5.0, 0.01, "Acceptable offset from the original flight line to allow when starting to fly again after passing obstacles") { passObstacles }
	val obstacleLookAhead by c.setting("Obstacle Look-Ahead", 8, 0..50, 1, "Looks ahead of the player to see if obstacles are in the way") { passObstacles }
	val dodge by c.setting("Dodge In Flight", true, "Glides past a blocked spot on a free lane next to it, before landing to walk around it") { passObstacles }
	val maxDodge by c.setting("Max Dodge", 2, 1..3, 1, "How far to the side to look for a free lane", " blocks") { passObstacles && dodge }
	val dodgeLookAhead by c.setting("Dodge Look-Ahead", 64, 10..96, 1, "How far ahead to look for obstacles while gliding, for time to steer past them", " blocks") { passObstacles && dodge }
	val directionStep by c.setting("Direction Step", 45.0, 0.0..180.0, 0.1, "The step size to use when locking the flight direction") { passObstacles }
}