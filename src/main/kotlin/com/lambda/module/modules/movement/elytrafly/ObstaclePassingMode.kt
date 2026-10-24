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
import net.minecraft.entity.Entity
import net.minecraft.registry.Registries
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
		/** Longest hole, or holes too close to bounce between, glided over in one. */
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
		/** How many ticks after gliding over a hole it comes down on the floor, and how much floor more it needs to bounce. */
		private const val HOLE_TOUCHDOWN_TICKS = 3
		private const val HOLE_TOUCHDOWN_MARGIN = 1.0
		/** How far before braking would be needed to stop waiting to get fast enough to glide over, and dodge instead. */
		private const val HOP_RETRY_MARGIN = 24.0
		/** The fastest [holdPitch] climbs to the height held. Slower, a block up takes longer than it's held ahead. */
		private const val MAX_HOLD_CLIMB = 0.25
		private const val JUMP_VELOCITY = 0.42
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

	/** What's in the way [distance] ahead, [what] it is for the debug log. */
	private class Obstacle(val distance: Double, describe: () -> String) {
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
	) {
		val targetY = when {
			under -> (feetY + topY) / 2
			hole -> topY - min(HOP_MARGIN, (topY - feetY) / 2)
			else -> feetY + min(HOP_MARGIN, (topY - feetY) / 2)
		}
		fun with(down: Boolean = this.down, lane: Vec3d = this.lane, stepHeight: Double = this.stepHeight) =
			Hop(start, end, feetY, topY, obstacle, step, down, lane, stepHeight, low, under, hole, open)
		/** Not making it yet, logged once. */
		var waiting = false
		/** Holding the height for it, not letting go again as the speed drops a little. */
		var held = false
		/** The tick [held] was last looked at, it takes a few simulations of the glide. */
		var heldCheckedAt = -1
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
	/** How long you've been down after braking, waiting for the server to end the glide. */
	private var landedTicks = 0
	/** Where along the line an obstacle is that no lane gets past yet, for logging it once. */
	private var noLaneAt: Double? = null
	/** Where along the line an obstacle is that got too close to get over, which is dodged instead. */
	private var skipHopAt: Double? = null
	/** Where along the line the layer you bounce on ends with no gliding across to more of it, not to look again. */
	private var noGapAt: Double? = null

	/**
	 * Steering past a blocked spot, flying level meanwhile so it doesn't speed into it. Not past carpets, there's nothing
	 * to speed into and it's planned for bouncing on.
	 */
	protected val isDodging get() = dodge != null && !dodgeCarpets
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
		if (!holdingFor(hop, progress(player.pos))) return@timed null
		// Down on the floor the bounce jumps this tick too. Pulling up on top of that throws you up into the roof.
		val climb = if (player.isOnGround) JUMP_VELOCITY else player.velocity.y
		holdPitch(player.y, climb, player.velocity.horizontalLength(), hop.targetY, player.finalGravity)
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
		}
		onDisable {
			passingToPos = null
			pausedWalk = false
			if (passerConfig.passObstacles) BaritoneHandler.cancel()
			stopDodge()
			brakeAt = null
			hop = null
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
		// Steered anew each tick below, a yaw left over would hold you on an old heading.
		dodgeYaw = null
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

		brakeAt?.let { at ->
			if (!player.isOnGround) return false
			// The client keeps gliding until the server ends it on seeing you down. Walking before that, you slide on
			// with the elytra, faster than walking, and get set back.
			if (player.getFlag(Entity.GLIDING_FLAG_INDEX) && ++landedTicks < MAX_LANDING_TICKS) return false
			if (landedTicks >= MAX_LANDING_TICKS) ElytraFly.debug { "Still gliding after landing, walking anyway" }
			landedTicks = 0
			brakeAt = null
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
			val inTheWay = if (before > 0) Vec3d(playerPos.x, closestLinePoint.y, playerPos.z).obstacleHit(snappedDir, before) else null
			// A simulation of the glide, once a tick is enough.
			// No floor in sight, it glides on as far as it can anyway.
			val makesIt by lazy { hop.open || canGetOver(hop, progress) }
			when {
				!player.isGliding -> this@ObstaclePassingMode.hop = null
				inTheWay != null -> {
					ElytraFly.debug { "In the way %.1f ahead before the obstacle at this speed (${inTheWay.what}), not gliding over it".format(inTheWay.distance) }
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
				!makesIt -> {
					ElytraFly.debug { "Can't get over the obstacle at %.1f in time ($hopMiss), dodging instead".format(hop.start - progress) }
					skipHopAt = hop.start
					this@ObstaclePassingMode.hop = null
				}
				else -> {
					dodgeYaw = hopYaw(closestLinePoint, hop, snappedDir, progress)
					return false
				}
			}
		}
		// Up onto a layer or off it just now, the line is at its new height.
		closestLinePoint = playerPos.findClosestPointOnLine(snappedDir)
		// Dodging up on a layer of carpets, its end on the lane is glided off like on the line.
		dodge?.let { lane -> if (onCarpets && planGap(closestLinePoint, snappedDir, lane)) return false }
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
				if (startDodge(here, closestLinePoint, snappedDir, distance)) return false
			}
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
	private fun SafeContext.startDodge(from: Vec3d, linePoint: Vec3d, dir: Vec3d, distance: Double): Boolean {
		val at = progress(from) + distance
		val block = from.obstacleBlock(dir, distance + 1)
		hopRejection = "it got too close to get over before"
		hopTooSlow = false
		if (skipHopAt?.let { abs(it - at) < 1.5 } != true && isStepUp(from, dir, distance)) planHop(linePoint, dir, distance, true)?.let {
			if (dodgePastNext(from, linePoint, dir, distance, block, it)) return true
			ElytraFly.debug { "Obstacle %.1f ahead ($block), a layer to bounce on, gliding up onto it at y %.2f".format(distance, it.feetY) }
			hop = it
			return true
		}
		if (skipHopAt?.let { abs(it - at) < 1.5 } != true) planHop(linePoint, dir, distance)?.let { over ->
			if (dodgePastNext(from, linePoint, dir, distance, block, over)) return true
			ElytraFly.debug { "Obstacle %.1f ahead ($block), gliding ${if (over.under) "under" else "over"} it at y %.2f, %.1f long".format(distance, over.targetY, over.end - over.start) }
			hop = over
			return true
		}
		if (distance < MIN_DODGE_DISTANCE || skipAt?.let { abs(it - at) < 1.5 } == true) return false
		// Slow still, e.g. right after taking off. Faster a few bounces on it may get over after all, a lane is
		// left for close enough to still steer over to it.
		if (hopTooSlow && distance > brakeDistance() + HOP_RETRY_MARGIN)
			return noLaneYet(at, "Obstacle %.1f ahead ($block), can't get over it yet ($hopMiss), bouncing on to look again".format(distance))
		val lane = freeLane(from, linePoint, dir, distance, Vec3d.ZERO)
			?: return if (distance > brakeDistance()) noLaneYet(at, "Obstacle %.1f ahead ($block), not gliding over it ($hopRejection), no free lane to dodge to yet ($laneMiss)".format(distance))
			else brake(at, "Obstacle %.1f ahead ($block), not gliding over it ($hopRejection), %.2f off the line, no free lane to dodge to ($laneMiss)".format(distance, from.distanceTo(linePoint)))
		ElytraFly.debug { "Obstacle %.1f ahead ($block), not gliding over it ($hopRejection), dodging ${lane.target.describe(dir)}${lane.leanText()}".format(distance) }
		return dodgeTo(lane, at)
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
		if (skipHopAt?.let { abs(it - at) < 1.5 } != true && isCarpetLayer(from, dir, carpet.distance))
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
	 * and then the line on a tie.
	 */
	private fun SafeContext.freeLane(
		from: Vec3d, linePoint: Vec3d, dir: Vec3d, distance: Double, current: Vec3d, carpets: Boolean = false, past: Double = DODGE_MARGIN,
		lead: Double = 0.0, fewestCarpets: Boolean = false,
	): Lane? {
		val across = Vec3d(-dir.z, 0.0, dir.x).normalize()
		val step = laneStep(dir)
		val here = from.subtract(linePoint).dotProduct(across)
		val leans = sweep(dir) > 0.01
		// The room past an obstacle can be narrower than a lane: diagonally, or straight with the line off the middle of the
		// blocks, a lane a block over grazes the ones on both sides. A quarter of one finds it.
		val laneStep = step / 4
		// The lane dodged to, not its target, that's short of it by the tilt and would round to the one next to it.
		val currentOffset = ((if (dodge != null && current == dodge) dodgeOffset else current.dotProduct(across)) / laneStep).roundToInt()
		val maxOffset = (passerConfig.maxDodge / laneStep + 1e-6).toInt()
		val leanDistance = player.velocity.horizontalLength() * LEAN_TICKS
		// The path slows down a little each tick, bouncing speeds you up meanwhile. The box swings out that much farther.
		val laneSpeed = player.velocity.horizontalLength() + LANE_SPEED_MARGIN
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
				val lane = linePoint.add(across.multiply(laneOffset))
				val miss = when {
					// Ahead, you're not there yet to glide over from.
					lead == 0.0 && from.sidewaysObstructed(lane.subtract(from)) -> "in the way sideways"
					else -> middle.obstacleHit(dir, distance + past, (high - low) / 2 + STEER_MARGIN, if (tilted) lean else 0, laneSpeed, carpets)
						?.let { "${it.what} ahead" }
						?: lane.add(dir.multiply(distance)).let { if (sameFloor) it else it.add(0.0, baseY - it.y, 0.0) }.let { spot ->
							if (spot.boxHasFloor()) null
							else "no floor at %.2f %.2f %.2f (${spot.blocksAround()})".format(spot.x, spot.y, spot.z)
						}
						// Down on the floor, a hole on the way there it'd fall into. A short one is glided over on the lane.
						?: if (onLayer) null else generateSequence(HOP_SCAN_STEP) { it + HOLE_SCAN_STEP }.takeWhile { it < distance }
							.firstOrNull { !lane.add(dir.multiply(it)).let { spot -> !spot.isLoaded || spot.boxHasFloor() } }
							?.takeUnless { hole -> generateSequence(hole) { it + HOP_SCAN_STEP }.takeWhile { it <= hole + MAX_HOLE_LENGTH }
								.any { lane.add(dir.multiply(it)).boxHasFloor() } }
							?.let { "a hole %.1f ahead".format(it) }
						// The lanes crossed on the way over, during the swing. Before [lead], where nothing's in the way.
						?: if (lead > 0) null else (minOf(offset, currentOffset) + 1 until maxOf(offset, currentOffset)).firstNotNullOfOrNull {
							linePoint.add(across.multiply(it * laneStep)).obstacleHit(dir, DODGE_STEER_AHEAD)
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
		val across = Vec3d(-dir.z, 0.0, dir.x).normalize()
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
				safeContext.rayCast(start, dir, reach - along, InteractionMask.Block, RaycastContext.ShapeType.COLLIDER)
					?.blockResult?.pos?.subtract(start)?.dotProduct(dir)
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
		val across = Vec3d(-dir.z, 0.0, dir.x).normalize()
		val heading = dir.normalize().add(across.multiply(lean * Math.tan(Math.toRadians(degrees))))
		return Math.toDegrees(atan2(-heading.x, heading.z))
	}

	private fun Lane.leanText() = if (lean == 0) "" else ", tilted ${if (lean > 0) "right" else "left"} past it"

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
		val across = Vec3d(-dir.z, 0.0, dir.x).normalize()
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
		val across = Vec3d(-dir.z, 0.0, dir.x).normalize()
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
		if (!onLine && !onLayer && glideOverHole(here, linePoint, dir, null)) {
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
				val onLane = linePoint.add(Vec3d(-dir.z, 0.0, dir.x).normalize().multiply(dodgeOffset))
				// Tilted, the box swings out only that side. On the lane from where you are, drifting across.
				val tilt = if (leaning(progress)) dodgeLean else 0
				val from = if (tilt != 0 && onDodgeLane(linePoint, dir)) here else onLane
				val ahead = from.add(dir.multiply(skip))
				// Dodging carpets, more of them on the lane too.
				val (low, other) = ahead.obstacleHits(dir, passerConfig.dodgeLookAhead - skip, lean = tilt, carpets = dodgeCarpets)
				low?.let { hit ->
					val distance = hit.distance + skip
					val block = hit.what
					// Only carpets on the lane, glided over right there instead of landing.
					val carpetsOnly = dodgeCarpets && (other == null || other.distance > hit.distance + 1) &&
						skipHopAt?.let { abs(it - (progress + distance)) < 1.5 } != true
					// A layer of them on the lane, bounced onto from there like from the line.
					if (carpetsOnly && !onLine && distance >= MIN_DODGE_DISTANCE && isCarpetLayer(onLane, dir, distance))
						planHop(onLane, dir, distance, true, carpets = true, lean = tilt)?.let { up ->
							ElytraFly.debug { "Low $block %.1f ahead on the lane, a layer of them to bounce on, gliding up onto it at y %.2f".format(distance, up.feetY) }
							hop = up.with(stepHeight = CARPET_HEIGHT, lane = lane)
							dodgeYaw = steerYaw(linePoint, lane, dir)
							return true
						}
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
						// Keeping to the lane meanwhile, e.g. the line frees up once past the obstacle beside it.
						distance > brakeDistance() -> noLaneYet(progress + distance, "Obstacle %.1f ahead on the lane ($block), ${if (carpetsOnly) "not gliding over it ($hopRejection), " else ""}no other free lane yet ($laneMiss)".format(distance))
						else -> return brake(progress + distance, "Obstacle %.1f ahead on the lane ($block), ${if (carpetsOnly) "not gliding over it ($hopRejection), " else ""}no other free lane ($laneMiss)".format(distance), dodgeCarpets)
					}
				}
			}
		}
		val leaning = leaning(progress)
		dodgeYaw = if (leaning) dodgeLeanYaw(linePoint, dir) else steerYaw(linePoint, dodge ?: Vec3d.ZERO, dir)
		ElytraFly.debug {
			val v = player.velocity
			"  dodge: %.2f off the line, to ${(dodge ?: Vec3d.ZERO).describe(dir)}${if (leaning) ", tilted" else ""}, steering yaw %.1f, moving yaw %.1f at %.2f, sent yaw %.1f"
				.format(offset.dotProduct(Vec3d(-dir.z, 0.0, dir.x).normalize()), dodgeYaw, Math.toDegrees(atan2(-v.x, v.z)), v.horizontalLength(), RotationManager.activeRotation.yaw)
		}
		return true
	}

	/**
	 * Glides over a hole in the floor ahead of [from], up to [MAX_HOLE_LENGTH] long and before [before] where something's
	 * in the way, like over a block: bouncing on unless you'd come down in it. Lands before it to walk when it can't get
	 * over in time. Whether it's past it in the air.
	 */
	private fun SafeContext.glideOverHole(from: Vec3d, linePoint: Vec3d, dir: Vec3d, before: Double?): Boolean {
		val along = generateSequence(HOP_SCAN_STEP) { it + HOP_SCAN_STEP }
			.takeWhile { it <= (before ?: passerConfig.dodgeLookAhead.toDouble()) }
			.map { it to from.add(dir.multiply(it)) }
			.takeWhile { (_, point) -> point.isLoaded }
		val start = along.firstOrNull { (_, point) -> !point.boxHasFloor() }?.first ?: return false
		val at = progress(from)
		if (skipHopAt?.let { abs(it - (at + start)) < 1.5 } == true) return false
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
		// Something soon after it, once over the hole too close to steer past. Right after it both are glided over as one,
		// from before the hole on at its height. Farther, that's what's dodged or glided over, no lane past it has a hole.
		from.add(dir.multiply(end)).obstacleHit(dir, brakeDistance() + DODGE_STEER_AHEAD)?.let { next ->
			if (next.distance > HOP_GAP) return false
			// Under it would mean coming down low over the hole, it's dodged instead.
			val over = planHop(linePoint, dir, end + next.distance)?.takeUnless { it.under }
				?.let { Hop(min(it.start, at + start - HOP_SCAN_STEP), it.end, it.feetY, it.topY, at + start, false) }
				?.takeIf { canGetOver(it, at) } ?: return false
			ElytraFly.debug { "Hole %.1f ahead, %.1f long, ${next.what} right after it, gliding over both at y %.2f".format(start, end - start, over.feetY) }
			hop = over
			return true
		}
		// Partly over its edge the box stands, the rays found where it doesn't.
		val hole = Hop(at + start - HOP_SCAN_STEP, at + end, from.y + passerConfig.minObstacleHeight, min(maxFlightY(), from.y + MAX_BOUNCE_RISE),
			at + start, false, low = true, hole = true)
		if (!canGetOver(hole, at)) {
			val reason = "Hole %.1f ahead, %.1f long, can't get over it ($hopMiss)".format(start, end - start)
			return if (start > brakeDistance()) noLaneYet(at + start, reason) else brake(at + start, reason)
		}
		ElytraFly.debug { "Hole %.1f ahead, %.1f long, gliding over it".format(start, end - start) }
		hop = hole
		return true
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
		// Off its end already, e.g. a glide off it that got cut short.
		if (edge <= HOP_SCAN_STEP) {
			offTheLayer()
			return false
		}
		val at = progress(linePoint) + edge
		if (noGapAt?.let { abs(it - at) < 1.5 } == true || skipHopAt?.let { abs(it - at) < 1.5 } == true) return false
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
		val across = Vec3d(-dir.z, 0.0, dir.x).normalize()
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
	): Hop? {
		val lineY = linePoint.y
		// As far out along each axis as the box swings across the way, it's in the way where the swing hits it. Tilted
		// [lean], only to that side: half as far each way, moved over that way by as much. Diagonally the box reaches as far
		// across only right at each spot looked at, a bit more covers in between them, as far as the rays look.
		val between = HOP_SCAN_STEP / 2 * 2 * abs(dir.x * dir.z) / dir.horizontalLengthSquared()
		val swing = sweep(dir) / (abs(dir.x) + abs(dir.z)) * dir.horizontalLength() / (if (lean == 0) 1 else 2) + between
		val shift = Vec3d(-dir.z, 0.0, dir.x).normalize().multiply(lean * sweep(dir) / 2)
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
			val maxLength = if (carpets) MAX_CARPET_HOP_LENGTH else MAX_HOP_LENGTH
			if (along > distance + maxLength) return noHop("longer than ${maxLength.toInt()}")
			if (world.isSpaceEmpty(player, box(along, floorY, bounceHeight).expand(swing, 0.0, swing))) gap += HOP_SCAN_STEP
			else {
				gap = 0.0
				end = along
				if (!world.isSpaceEmpty(player, box(along, floorY).expand(swing, 0.0, swing))) onFloor = true
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
		fun fits(y: Double) = samples.all { world.isSpaceEmpty(player, box(it, y).expand(swing, 0.0, swing)) }
		// Over carpets a bit higher than just clearing them, onto a layer of them only that.
		val maxHopY = maxHopY()
		val heights = generateSequence(lineY + if (carpets && !step) CARPET_GLIDE_FEET else passerConfig.minObstacleHeight) { it + HOP_HEIGHT_STEP }
			.takeWhile { it <= maxHopY }
		// Only overhead, glided under without touching the floor, a bounce would throw you up into it. Only if the box fits
		// right above the floor all the way, else something's in the way down there too that the rays above missed.
		val under = !onFloor && fits(heights.first())
		val feetY = heights.firstOrNull { fits(it) } ?: return noHop("too high to get over under y %.2f".format(maxHopY))
		// As high as there's room over it all, a roof or a block hanging above.
		val topY = heights.dropWhile { it < feetY }.takeWhile { fits(it) }.last()
		if (topY - feetY < HOP_MARGIN) return noHop("only %.2f room between y %.2f and %.2f".format(topY - feetY, feetY, topY))
		// Under it only with floor right after to come down on, else it's dodged and the height kept.
		if (under && !linePoint.add(dir.multiply(stop + HOP_SCAN_STEP)).boxHasFloor()) return noHop("no floor right after it to come down on")
		val at = progress(linePoint)
		return Hop(at + start, at + stop, feetY, topY, at + distance, step, low = carpets, under = under).takeIf { canGetOver(it, at) }
			?: noHop("can't get up there in time, $hopMiss").also { hopTooSlow = true }
	}

	/**
	 * Whether you get over [hop] from [progress] on. Over carpets bouncing on may well, else holding the height from now
	 * on, at the last moment later. Else holding it once [holdingFor] does.
	 */
	private fun SafeContext.canGetOver(hop: Hop, progress: Double) =
		if (!hop.low || hop.held) canHop(hop, progress)
		else canHop(hop, progress, NO_HOLD) || canHop(hop, progress, 0)

	/**
	 * Whether holding the height with [holdPitch] gets you over [hop] from [progress] on, bouncing off the floor
	 * on the way if need be. From [holdFrom] ticks on, by default once close enough like [holdingFor]. Simulates the glide
	 * tick by tick.
	 */
	private fun SafeContext.canHop(hop: Hop, progress: Double, holdFrom: Int? = null): Boolean {
		val floor = startPos.y
		val gravity = player.finalGravity
		val target = hop.targetY
		val bounce = bouncePitch()
		var y = player.y
		var climb = player.velocity.y
		var speed = player.velocity.horizontalLength()
		var along = progress
		// Held already like hopPitch does, it doesn't let go again as the speed drops a little.
		var holding = hop.held
		// From now on, not only once holding: slow right after taking off, it's held from close by with no time to rise.
		repeat(CAN_HOP_SIM_TICKS) { tick ->
			if (y <= floor && climb <= 0) {
				y = floor
				climb = JUMP_VELOCITY
			}
			// Bouncing on until then.
			holding = holding || if (holdFrom != null) tick >= holdFrom else hop.start - along <= speed * leadTicks(hop)
			val pitch = if (holding) holdPitch(y, climb, speed, target, gravity) else bounce(y, climb)
			glide(climb, speed, pitch, gravity).let { (c, s) ->
				climb = c
				speed = s
			}
			y += climb
			along += speed
			if (along >= hop.start && (y < hop.feetY || y > hop.topY)) {
				hopMiss = "from y %.2f climbing %.2f at %.2f, it'd be at y %.2f %.1f ahead, needs %.2f to %.2f"
					.format(player.y, player.velocity.y, player.velocity.horizontalLength(), y, along - progress, hop.feetY, hop.topY)
				return false
			}
			if (along > hop.end) return true
		}
		hopMiss = "not over it within $CAN_HOP_SIM_TICKS ticks"
		return false
	}

	/**
	 * The way across the line you take for [distance] along it, steering with [steerYaw] from [offset] across it over
	 * to [target], then from [leanFrom] on tilted [lean] across it. The flight turns a tenth of the way to where you
	 * look each tick, so it lags behind.
	 */
	private fun SafeContext.steerPath(offset: Double, dir: Vec3d, target: Double, lean: Int, leanFrom: Double, distance: Double): List<SteerState> {
		val across = Vec3d(-dir.z, 0.0, dir.x).normalize()
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
	 * Whether to hold the height for [hop] at [progress] along the line, once it's close enough. Over carpets only
	 * once bouncing on would come down on them and holding any later wouldn't make it, so the bounces before go on as
	 * they would, and within [MAX_CARPET_HOLD_TICKS]. Once held it stays held.
	 */
	private fun SafeContext.holdingFor(hop: Hop, progress: Double): Boolean {
		if (hop.held || hop.heldCheckedAt == player.age) return hop.held
		hop.heldCheckedAt = player.age
		val speed = player.velocity.horizontalLength()
		hop.held = when {
			hop.low -> hop.start - progress <= speed * MAX_CARPET_HOLD_TICKS &&
				!canHop(hop, progress, NO_HOLD) && !canHop(hop, progress, CARPET_HOLD_DELAY) && canHop(hop, progress, 0)
			// Gliding low loses speed, so it bounces on as long as coming down a bit later still gets it under.
			hop.under -> hop.start - progress <= speed * leadTicks(hop) && !canHop(hop, progress, UNDER_HOLD_DELAY)
			else -> hop.start - progress <= speed * leadTicks(hop)
		}
		return hop.held
	}

	/**
	 * How many ticks before [hop] to start holding the height, more the higher it is above the floor. From down on the
	 * floor, not bouncing up yet, a block up doesn't make it in [HOP_LEAD_TICKS] alone. Under something it comes down
	 * from the bounces long before, the room below is narrow.
	 */
	private fun SafeContext.leadTicks(hop: Hop) =
		HOP_LEAD_TICKS + (hop.targetY - startPos.y).coerceAtLeast(0.0) / MAX_HOLD_CLIMB + if (hop.under) UNDER_LEAD_TICKS else 0

	/** The pitch that gets [climb] closest to closing in on [target] smoothly, from [y] at [speed]. */
	private fun holdPitch(y: Double, climb: Double, speed: Double, target: Double, gravity: Double): Double {
		val wanted = ((target - y) * 0.3).coerceIn(-0.1, MAX_HOLD_CLIMB)
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
		return false
	}

	/** Stops dodging and lands, to walk around the obstacle at [at] along the line, or past the [carpets]. Always false. */
	private fun brake(at: Double, reason: String, carpets: Boolean = false): Boolean {
		// Over a hole there's nothing to land on, it glides on and looks again, there may be floor farther on.
		if (runSafe { overHole() } == true) return noLaneYet(at, "$reason, over a hole, gliding on")
		ElytraFly.debug { "$reason, landing to walk around it" }
		landedTicks = 0
		stopDodge()
		hop = null
		brakeAt = at
		brakeCarpets = carpets
		return false
	}

	private fun SafeContext.overHole() =
		player.isGliding && !player.isOnGround && Vec3d(player.x, startPos.y, player.z).let { it.isLoaded && !it.boxHasFloor() }

	private fun Vec3d.describe(dir: Vec3d): String {
		val offset = dotProduct(Vec3d(-dir.z, 0.0, dir.x).normalize())
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
	private fun Vec3d.obstacleHit(direction: Vec3d, maxDistance: Double, extra: Double = 0.0, lean: Int = 0, speed: Double? = null, carpets: Boolean = false) =
		obstacleHits(direction, maxDistance, extra, lean, speed, carpets).first

	/**
	 * Like [obstacleHit], the first in the way with [carpets] and, on its own, the first that's not one of them. Looked
	 * for at once, each costs a few dozen rays as far ahead as it looks.
	 */
	context(safeContext: SafeContext)
	private fun Vec3d.obstacleHits(direction: Vec3d, maxDistance: Double, extra: Double = 0.0, lean: Int = 0, speed: Double? = null, carpets: Boolean = false): Pair<Obstacle?, Obstacle?> {
		val across = Vec3d(-direction.z, 0.0, direction.x).normalize()
		val halfWidth = halfWidth(direction) + extra
		val sweep = with(safeContext) { speed?.let { sweep(direction, it) } ?: sweep(direction) }
		val low = -halfWidth - if (lean > 0) 0.0 else sweep
		val high = halfWidth + if (lean < 0) 0.0 else sweep
		val rays = ceil((high - low) / RAY_SPACING).toInt()
		val offsets = (0..rays).map { across.multiply(low + (high - low) * it / rays) }
		// Up to the top of the gliding box, as high as it bounces.
		val glideTop = with(safeContext) { maxFlightY() } + GLIDE_SIZE - y - WIDTH_MARGIN
		val heights = if (passerConfig.headHitters) listOf(passerConfig.minObstacleHeight, 1.01, 1.99) + listOf(glideTop).filter { it > 1.99 && it.isFinite() }
			else listOf(passerConfig.minObstacleHeight)
		// Carpets as far as the box swings out too: touching down, you move down first and then along each axis, along
		// the floor, and diagonally into one beside the way.
		val carpetStarts = if (!carpets) emptyList() else offsets.map { add(it).add(0.0, CARPET_RAY, 0.0) }
		val blockStarts = heights.flatMap { height -> offsets.map { add(it).add(0.0, height, 0.0) } }
		val starts = carpetStarts + blockStarts
		// Boats, minecarts and shulkers are as solid as blocks to you, the rays only see blocks.
		val reach = direction.normalize().multiply(maxDistance)
		val around = starts.fold(Box(starts.first(), starts.first())) { box, start -> box.union(Box(start, start)) }
		val solid = safeContext.world.getOtherEntities(safeContext.player, around.union(around.offset(reach))) {
			safeContext.player.collidesWith(it)
		}
		// Past one already hit no ray needs to look, that's most of the way for most of them.
		fun nearest(starts: List<Vec3d>, within: Double): Obstacle? {
			var nearest: Obstacle? = null
			for (start in starts) {
				// Not back the other way, from inside one it's right there.
				val range = nearest?.distance ?: within
				if (range > 0) safeContext.rayCast(start, direction, range, InteractionMask.Block, RaycastContext.ShapeType.COLLIDER)
					?.blockResult?.let { hit ->
						val distance = hit.pos.subtract(start).dotProduct(direction)
						if (distance < (nearest?.distance ?: Double.POSITIVE_INFINITY))
							nearest = Obstacle(distance) { "${Registries.BLOCK.getId(safeContext.blockState(hit.blockPos).block).path} ${hit.blockPos.toShortString()}" }
					}
				solid.forEach { entity ->
					entity.boundingBox.raycast(start, start.add(reach)).orElse(null)?.let {
						val distance = it.subtract(start).dotProduct(direction)
						if (distance < (nearest?.distance ?: Double.POSITIVE_INFINITY))
							nearest = Obstacle(distance) { "${Registries.ENTITY_TYPE.getId(entity.type).path} ${entity.blockPos.toShortString()}" }
					}
				}
			}
			return nearest
		}
		val block = nearest(blockStarts, maxDistance)
		// Carpets only before it, past it they don't matter.
		val carpet = nearest(carpetStarts, block?.distance ?: maxDistance)
		return (if (carpet != null && (block == null || carpet.distance < block.distance)) carpet else block) to block
	}

	/** What [obstacleDistance] ran into, for the debug log. */
	context(safeContext: SafeContext)
	private fun Vec3d.obstacleBlock(direction: Vec3d, maxDistance: Double) = obstacleHit(direction, maxDistance)?.what ?: "nothing"

	context(safeContext: SafeContext)
	private fun Vec3d.hasFloor() = flooredBlockPos.down().let { safeContext.blockState(it).isSolidBlock(safeContext.world, it) }

	/** Whether anything under your box here holds you up. A block's corner does, as where a layer's edge runs askew. */
	context(safeContext: SafeContext)
	private fun Vec3d.boxHasFloor() =
		!safeContext.world.isSpaceEmpty(safeContext.player, Box(x - 0.29, y - 0.05, z - 0.29, x + 0.29, y, z + 0.29))

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
	private fun SafeContext.hopYaw(linePoint: Vec3d, hop: Hop, dir: Vec3d, progress: Double) =
		if (dodge != null && hop.lane == dodge && leaning(progress)) dodgeLeanYaw(linePoint, dir) else steerYaw(linePoint, hop.lane, dir)

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
		(Vec3d(player.x - linePoint.x, 0.0, player.z - linePoint.z).dotProduct(Vec3d(-dir.z, 0.0, dir.x).normalize()) - dodgeOffset) * dodgeLean >= 0

	/** Whether the heading is tilted past what's dodged at [progress] along the line, from shortly before it on. */
	private fun SafeContext.leaning(progress: Double) =
		dodgeLean != 0 && progress >= dodgeEnd - player.velocity.horizontalLength() * LEAN_TICKS

	/** Back onto the line, still dodging until on it. */
	private fun swingBack() {
		dodge = Vec3d.ZERO
		dodgeLean = 0
		dodgeOffset = 0.0
	}

	/** Always false, for returning it from [steerDodge]. */
	private fun stopDodge(): Boolean {
		dodge = null
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
		val edge = Vec3d(-direction.z, 0.0, direction.x).normalize().multiply(halfWidth(direction))
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