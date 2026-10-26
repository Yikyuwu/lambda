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

package com.lambda.module.modules.movement.elytrafly.modes

import com.lambda.config.Config
import com.lambda.config.Group
import com.lambda.config.entries.Setting.Companion.onValueChange
import com.lambda.context.SafeContext
import com.lambda.event.events.ConnectionEvent
import com.lambda.event.events.MovementEvent
import com.lambda.event.events.PacketEvent
import com.lambda.event.events.TickEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.event.listener.UnsafeListener.Companion.listenUnsafe
import com.lambda.interaction.handlers.BaritoneHandler
import com.lambda.interaction.handlers.GlideHandler
import com.lambda.interaction.managers.rotating.IRotationRequest.Companion.rotationRequest
import com.lambda.interaction.managers.rotating.RotationManager
import com.lambda.module.hud.Speedometer
import com.lambda.module.modules.movement.elytrafly.ElytraFly
import com.lambda.module.modules.movement.elytrafly.ElytraFly.FlyMode
import com.lambda.module.modules.movement.elytrafly.ElytraFly.fakeFly
import com.lambda.module.modules.movement.elytrafly.ObstaclePassingMode
import com.lambda.module.modules.movement.elytrafly.PasserSettings
import com.lambda.threading.runSafe
import com.lambda.util.PacketUtils.handlePacketSilently
import com.lambda.util.PacketUtils.sendPacketSilently
import com.lambda.util.SpeedUnit
import com.lambda.util.TickTimer
import com.lambda.util.math.dist
import com.lambda.util.math.distSq
import com.lambda.util.math.minus
import com.lambda.util.player.PlayerUtils.canStartGliding
import com.lambda.util.player.PlayerUtils.canTakeoff
import net.minecraft.client.input.KeyboardInput.getMovementMultiplier
import net.minecraft.entity.Entity
import net.minecraft.network.packet.Packet
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket
import net.minecraft.util.PlayerInput
import net.minecraft.util.math.Vec2f
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class BounceElytraFly(
	override val c: Config
) : ObstaclePassingMode(c, FlyMode.Bounce) {
	companion object {
		private const val Y_MOTION_GROUP = "Y Motion"
		private const val BOUNCE_OBSTACLE_PASSER_GROUP = "Bounce Obstacle Passer"

		private const val MAX_FAKE_LAG_TICKS = 10
		private const val MAX_TAKEOFF_WAIT_TICKS = 10
		/** The server ignored starts up to 0.67 above the ground and took them from 0.83. */
		private const val MIN_TAKEOFF_HEIGHT = 0.75
		/** How far below the box a floor counts as stood on. */
		private const val FLOOR_CHECK = 0.01

		/** Level, so you keep bouncing but don't speed up. */
		private const val COAST_PITCH = 0.0
		/** Looking this slightly up while coasting keeps the height for a while, so it glides long instead of bouncing. */
		private const val COAST_PITCH_UP = -3.0
		/** Looking this far up the wings lift more than gravity pulls, so you climb and slow down. */
		private const val SLOW_PITCH = -10.0
		/** Most the pitch turns a tick when slowing down or coasting. */
		private const val MAX_PITCH_STEP = 15.0

		/** How far above the flight line flying level may carry you. */
		private const val MAX_COAST_RISE = 2.0
		/** How far above the flight line slowing down may climb, outside the nether. */
		private const val MAX_SLOW_RISE = 4.0
		/** Room kept below the roof when looking up to slow down. */
		private const val SLOW_ROOM = 0.5
		/** The lowest nether roof bedrock is at y 123, so flying level any higher bumps your head on it. */
		private const val MAX_NETHER_FLIGHT_Y = 121.5
		/** Up on a layer, as high as the gliding box stays clear of that roof by a bit. */
		private const val MAX_NETHER_LAYER_FLIGHT_Y = 122.2
		/** How far above the flight line gliding over an obstacle may go. From a floor at y 119 as high as from one at 120. */
		private const val MAX_HOP_RISE = 3.5
		/** Gliding over an obstacle, as high as the gliding box just stays under that roof. */
		private const val MAX_NETHER_HOP_Y = 122.35
		/** How far up a block roof over you is looked for. */
		private const val ROOF_SCAN_HEIGHT = 4.0
		private const val ROOF_SCAN_STEP = 0.25
		/** Room kept below a block roof. */
		private const val ROOF_MARGIN = 0.2
	}

	private val takeoff by c.setting("Takeoff", true, "Automatically jumps and initiates gliding")
	private val autoPitch by c.setting("Auto Pitch", true, "Automatically pitches the players rotation down to bounce at faster speeds")
	private val smartPitch by c.setting("Smart", true, "Calculates the best pitch for bounce") { autoPitch }
	private val pitch by c.setting("Pitch", 80.0, -90.0..90.0, 0.000001) { autoPitch && !smartPitch }
	private val jump by c.setting("Jump", true, "Automatically jumps")
	private val flagPause by c.setting("FlagPause Pause", 5, 0..100, 1, "How long to pause if the server flags you for a movement check", "ticks")
	private val minimizePackets by c.setting("Minimize Packets", true, "Shrinks the amount of start fly packets sent to the server as much as possible")
	private val fakeLag by c.setting("Fake Lag", true, "Emulates the player lagging to allow flying in 1x2 tunnels")
		.onValueChange { _, to -> if (!to) flushPackets() }
	private val waitForChunks by c.setting("Wait For Chunks", true, "Keeps a few seconds of flight of loaded chunks ahead, slowing down and landing when there are fewer. The server sets you back for flying into missing ones")
	private val chunkLookAhead by c.setting("Chunk Look-Ahead", 96, 16..160, 8, "How far ahead loaded chunks are looked for. Flying faster wants more of them loaded ahead", " blocks") { waitForChunks }

	@Group(Y_MOTION_GROUP) val yMotionSetting by c.setting("Y Motion", false, "Cancels the players y velocity to aid speed")
	@Group(Y_MOTION_GROUP) val onlyOnDiagonal: Boolean by c.setting("Only On Diagonal", true, "Only use y motion when the player is flying on a non-axial angle") { yMotionSetting }
	@Group(Y_MOTION_GROUP) val minDiagonalAngle by c.setting("Min Diagonal Angle", 15.0, 0.0..180.0, 0.1, "The minimum angle the player must be flying to use y motion") { yMotionSetting && onlyOnDiagonal }
	@Group(Y_MOTION_GROUP) val strictYMotionRange by c.setting("Strict Range", true, "provides an extra range check to sneak until within. Typically used for when you need to get within sub-block distances of walls for collision checks") { yMotionSetting }
	@Group(Y_MOTION_GROUP) val acceptableYMotionRange by c.setting("Acceptable Range", 0.1, 0.01..5.0, 0.01, "The acceptable distance, aside from forward distance, from the start position") { yMotionSetting && strictYMotionRange }
	@Group(Y_MOTION_GROUP) val yMotionStartSpeed by c.setting("Y Motion Start Speed", 30, 0..40, 1, unit = "bps") { yMotionSetting }
	@Group(Y_MOTION_GROUP) val speedLimit by c.setting("Speed Limit", 110, 10..400, 1, unit = "bps") { yMotionSetting }
	private val SafeContext.yMotion
		get() = yMotionSetting &&
				onYMotionAngle &&
				player.isOnGround &&
				player.isGliding &&
				Speedometer.calculateSpeed(true, SpeedUnit.BlocksPerSecond).let { speed ->
					speed > yMotionStartSpeed && speed < speedLimit
				}

	private val onYMotionAngle
		get() = !onlyOnDiagonal || diagonal

	@Group(BOUNCE_OBSTACLE_PASSER_GROUP) override val passerConfig by c.configBlock(PasserSettings(c))

	private var wantJump = false
	private var prevGliding: Boolean? = null
	private val pauseTimer = TickTimer()
	private val pingPackets = ConcurrentLinkedQueue<CommonPingS2CPacket>()
	/** Packets are sent from the network thread too, e.g. replies to the server. */
	private val sendPacketQueue = ConcurrentLinkedQueue<Packet<*>>()
	private var sneakLeft = false
	private var sneakRight = false
	private var interrupting = false
	private var groundY = 0.0
	private var queuedTicks = 0
	private var takeoffWait = 0
	private val pacer = FlightPacer()
	private val waitingForChunks get() = pacer.pace == FlightPacer.Pace.Waiting
	private val slowingForChunks get() = pacer.pace == FlightPacer.Pace.Slowing
	private val coasting get() = pacer.pace == FlightPacer.Pace.Coasting
	/** The pitch asked for last tick, to turn from it bit by bit. */
	private var lastPitch = 0.0
	private var hitRoof = false

	private val flightPaused
		get() = !pauseTimer.hasSurpassed(flagPause) || interrupting || BaritoneHandler.isActive || waitingForChunks || isBraking || isWaiting || noElytra

	private val SafeContext.queuePackets
		get() = fakeLag && player.isGliding && (!yMotionSetting || !onYMotionAngle) &&
				queuedTicks < MAX_FAKE_LAG_TICKS &&
				// Only bouncing. Held back after a setback or while landing, the server sees you stand still, sets you back
				// again and again. Nor falling off a block you bounced on. Also the tick before touching down, the server
				// often ended the glide when the landing came on its own.
				!flightPaused && bounceFloor.let { floor -> player.y >= floor && player.y + min(player.velocity.y, 0.0) - floor < if (passerConfig.passObstacles) passerConfig.minObstacleHeight + 0.1 else 0.163 }

	/**
	 * The floor you bounce on. The flight line moves up onto a layer before you first touch it, the floor you last
	 * stood on only after: that first touch the server would see and end the glide.
	 */
	private val SafeContext.bounceFloor get() = touchFloor() ?: if (passerConfig.passObstacles) startPos.y else groundY

	private val diagonal: Boolean
		get() {
			val normalised = abs(RotationManager.activeRotation.yaw % 90)
			return normalised > minDiagonalAngle && normalised < 90 - minDiagonalAngle
		}

	init {
		listen<TickEvent.Pre> {
			pauseTimer.tick()
			wantJump = false
			with(pacer) { update(chunkLookAhead.takeIf { waitForChunks }, groundY) }
			// The floor you're bouncing on, it may be higher or lower than where you started.
			if (player.isOnGround) groundY = player.y
			val roof = player.isGliding && player.verticalCollision && !player.isOnGround
			if (roof && !hitRoof) ElytraFly.debug { "Bumped the roof, %.2f above the floor, pitch %.1f".format(player.y - groundY, RotationManager.activeRotation.pitch) }
			hitRoof = roof
			// Held too long, the server would set you back for the burst.
			if (sendPacketQueue.isEmpty()) queuedTicks = 0
			// Also a tick without the movement they wait for, see below.
			else if (++queuedTicks >= MAX_FAKE_LAG_TICKS || !queuePackets) flushPackets()

			if (autoPitch) {
				val hold = hopPitch()
				val target = when {
					// Straight down the wings don't lift, so you land soonest and don't speed up on the way.
					// Also right before chunks that aren't loaded yet, gliding on would carry you into them.
					isBraking || waitingForChunks -> 90.0
					// Holding the height over an obstacle instead of bouncing into it.
					hold != null -> hold
					// Level for the jump off the floor. The server ended the glide there and moves the jump like one on
					// foot, looking down the glide would take the first 0.42 up as only 0.33 and get set back.
					player.isOnGround -> COAST_PITCH
					// Straight down the glide moves like a jump on foot, which the server does until it takes the start.
					// Slowing down or coasting, the whole way up: easing up into the brake while still rising, on 2b2t the
					// server set it back at the top of the bounce again and again, 120 times in a few hours.
					player.velocity.y > 0 && (player.y - groundY < MIN_TAKEOFF_HEIGHT || slowingForChunks || slowingToFit || coasting) -> 90.0
					// Under a low roof there's no room to climb far, and each bounce speeds it up again. Looking up it
					// brakes and glides long without touching the floor, as high as flying level goes.
					(slowingForChunks || slowingToFit) && pacer.lowRoof -> eased(holdHeightPitch(SLOW_PITCH, maxFlightY()))
					coasting && pacer.lowRoof -> eased(holdHeightPitch(COAST_PITCH_UP, maxFlightY()))
					// Looking up turns speed into climbing, and up there no bounce pushes you on.
					// Turned to bit by bit, the server sets you back for a sudden turn up while climbing.
					// Also to fit a lane past an obstacle ahead, see [slowingToFit].
					slowingForChunks || slowingToFit -> eased(holdHeightPitch(SLOW_PITCH))
					// Just enough up to keep the height, gliding long without bounces pushing you on.
					coasting -> eased(holdHeightPitch(COAST_PITCH_UP))
					// Up over a layer just now: down onto it to bounce on from its start, flying level it'd glide half across it
					// first. Not while still rising, a swing down then is set back.
					stepLanding && player.isGliding && player.velocity.y <= 0 -> pacer.throttled(90.0, coastPitch())
					// Level, so you keep bouncing but don't speed up. Starting again from a stop gets set back.
					isDodging -> coastPitch()
					!smartPitch -> pacer.throttled(pitch, coastPitch())
					player.velocity.y > -0.2 -> pacer.throttled(90.0, coastPitch())
					else -> 4.0
				}
				lastPitch = target
				rotationRequest { pitch(target) }.submit()
			}

			if (handlePassingObstacles()) return@listen

			if (yMotionSetting && strictYMotionRange && onYMotionAngle) run yMotionCorrection@ {
				val playerPos = player.pos
				val validDistanceFromStart = Vec3d(playerPos.x, startPos.y, playerPos.z) dist startPos > 0.1
				if (!validDistanceFromStart) return@yMotionCorrection

				val snappedDir = getSnappedDir()
				val closestLinePoint = playerPos.findClosestPointOnLine(snappedDir)
				val xz = Vec3d(player.x, closestLinePoint.y, player.z)
				if (xz distSq closestLinePoint > acceptableYMotionRange.pow(2)) {
					interrupting = true
					val offset = playerPos - startPos
					val cross = snappedDir.x * offset.z - snappedDir.z * offset.x
					val rotationRequest = rotationRequest {
						val yawAndPitch = snappedDir.yawAndPitch
						yaw(yawAndPitch.y)
					}.submit()
					if (!rotationRequest.done) return@listen
					sneakLeft = cross > 0
					sneakRight = !sneakLeft
					return@listen
				}
			}

			if (flightPaused) return@listen

			if (!player.isGliding) {
				if (takeoff && player.canTakeoff) {
					ElytraFly.trace(ElytraFly.TRACE_TICKS)
					if (player.canStartGliding) {
						// Right after the jump the server still takes you for on the ground and ignores the start,
						// so the client would glide alone and be set back. Unless a ceiling ends the jump early.
						if (player.y - groundY < MIN_TAKEOFF_HEIGHT && player.velocity.y > 0) return@listen
						// Still on the floor, though the client doesn't take you for on it for a tick, e.g. landing fast to
						// walk after a setback. The server ignores the start, then the jump after it is set back 3 times.
						if (!world.isSpaceEmpty(player, player.boundingBox.offset(0.0, -FLOOR_CHECK, 0.0))) return@listen
						GlideHandler.onGlide()
						wantJump = jump
					} else {
						val yawRad = Math.toRadians(player.yaw.toDouble())
						val rightX = -cos(yawRad)
						val rightZ = -sin(yawRad)
						val vx = player.velocity.x
						val vz = player.velocity.z
						val sidewaysSpeed = abs(vx * rightX + vz * rightZ)
						// Not forever, e.g. being pushed out of a block never stops.
						if (sidewaysSpeed >= 0.001 && ++takeoffWait < MAX_TAKEOFF_WAIT_TICKS) return@listen

						takeoffWait = 0
						wantJump = true
					}
				}
				return@listen
			}

			if (player.isOnGround) wantJump = jump
			if (minimizePackets && player.getFlag(Entity.GLIDING_FLAG_INDEX) && !fakeFly && !yMotion) return@listen
			if (player.isOnGround) return@listen

			flyOrFakeFly()
		}

		listen<TickEvent.Post>({ -100 }) {
			interrupting = false
		}

		listen<MovementEvent.InputUpdate> { event ->
			val input = event.input
			val playerInput = input.playerInput

			// Stands still right before chunks that aren't loaded yet, walking on would take you into them too.
			if (waitingForChunks && !BaritoneHandler.isActive) {
				input.playerInput = PlayerInput(false, false, false, false, false, playerInput.sneak, false)
				input.movementVector = Vec2f.ZERO
				return@listen
			}

			if (sneakLeft || sneakRight) {
				val sneak = player.velocity.horizontal.length() > 0.001 || !player.isSneaking
				input.playerInput = PlayerInput(
					playerInput.forward,
					playerInput.backward,
					sneakLeft,
					sneakRight,
					playerInput.jump,
					sneak,
					false
				)
				input.movementVector = Vec2f(
					getMovementMultiplier(sneakLeft, sneakRight),
					0f
				)
				sneakLeft = false
				sneakRight = false
				return@listen
			}
			if (!wantJump) return@listen
			if (flightPaused) return@listen
			input.jump()
		}

		listen<PacketEvent.Send.Pre>({ 1 }) { event ->
			// Sent from the network thread, e.g. keep-alive replies, they go out as they are. Only the game thread holds
			// packets back and sends them, else both take from the queue at once.
			if (!mc.isOnThread) return@listen
			// Starting the glide, e.g. taking off after walking, right away after what's held back. Held back with the
			// landing, the server sees you on the ground after it and ends it again.
			if (event.packet.let { it is ClientCommandC2SPacket && it.mode == ClientCommandC2SPacket.Mode.START_FALL_FLYING })
				return@listen flushPackets()
			// The server ignores your movement until you confirm its setback.
			if (queuePackets && event.packet !is TeleportConfirmC2SPacket) {
				sendPacketQueue.add(event.packet)
				ElytraFly.traceLine { "  held back ${ElytraFly.describe(event.packet)}" }
				event.cancel()
				return@listen
			}
			if (sendPacketQueue.isEmpty() || event.packet is TeleportConfirmC2SPacket) return@listen flushPackets()
			// The held back ones wait for the next movement and go out with it at once, so the server gets the landing
			// and the jump together. Handling the landing on its own, it ends the glide.
			sendPacketQueue.add(event.packet)
			event.cancel()
			if (event.packet is PlayerMoveC2SPacket) flushPackets()
		}
		listen<PacketEvent.Receive.Pre>({ 1 }) { event ->
			if (event.packet is CommonPingS2CPacket && queuePackets) {
				pingPackets.add(event.packet)
				event.cancel()
			}
		}

		// Held back for the server you left, the next one would get them.
		listenUnsafe<ConnectionEvent.Connect.Pre> {
			sendPacketQueue.clear()
			pingPackets.clear()
		}

		onFlag { pauseTimer.reset() }

		onEnable {
			pacer.reset()
			hitRoof = false
			// The floor below when started in the air, else packets would be held back up there.
			groundY = startPos.y
			queuedTicks = 0
			takeoffWait = 0
		}

		onDisable {
			wantJump = false
			prevGliding = false
			flushPackets()
			sneakLeft = false
			sneakRight = false
		}
	}

	/**
	 * Level, unless a bounce off the floor still carries you up. Flying level, gravity hardly pulls you down,
	 * so the bounce would take you up about 2 blocks, into a low roof. Pitched down the wings lift less and you fall
	 * like without them at 90, so the pitch is just enough down for the climb to top out at [ceilingY].
	 */
	private fun SafeContext.coastPitch(room: Double = ceilingY() - player.y, climb: Double = player.velocity.y): Double {
		if (room <= 0) return 90.0
		if (climb <= 0) return COAST_PITCH
		val gravity = player.finalGravity
		// Topping out after room blocks takes this much pull down, and gliding pulls gravity * (1 - 0.75 * cos² pitch).
		val needed = climb * climb / (2 * room)
		val cosSq = ((1 - needed / gravity) / 0.75).coerceIn(0.0, 1.0)
		return Math.toDegrees(acos(sqrt(cosSq))).coerceAtLeast(COAST_PITCH)
	}

	/**
	 * [up], climbing as high as there's room, the nether roof's bedrock or a block roof. Once the climb would get there,
	 * level or a bit down like [coastPitch], and up again once it sank, so it keeps gliding up there without bounces.
	 */
	private fun SafeContext.holdHeightPitch(
		up: Double,
		top: Double = if (world.registryKey == World.NETHER) MAX_NETHER_LAYER_FLIGHT_Y else startPos.y + MAX_SLOW_RISE,
	): Double {
		val room = ceilingY(top) - player.y
		val climb = player.velocity.y
		val rise = if (climb > 0) climb * climb / (2 * player.finalGravity) else 0.0
		return if (room - rise > SLOW_ROOM) up else coastPitch(room, climb)
	}

	/** Like the rotation request above, with the roof over you now. */
	override fun SafeContext.bouncePitch(): (y: Double, climb: Double) -> Double {
		val ceiling = ceilingY()
		return { y, climb ->
			val level = coastPitch(ceiling - y, climb)
			when {
				// Diving onto a layer just climbed up over, like the flight, see [stepLanding].
				stepLanding && climb <= 0 -> pacer.throttled(90.0, level)
				// Slowing down climbs at first, flying level is the lower one to plan with.
				slowingForChunks || slowingToFit || coasting || isDodging -> level
				!smartPitch -> pacer.throttled(pitch, level)
				climb > -0.2 -> pacer.throttled(90.0, level)
				else -> 4.0
			}
		}
	}

	/** Over an obstacle in the nether up to [MAX_NETHER_HOP_Y], over two blocks too. */
	override fun SafeContext.maxHopY() =
		if (world.registryKey == World.NETHER) max(maxFlightY(), min(MAX_NETHER_HOP_Y, startPos.y + MAX_HOP_RISE))
		else maxFlightY()

	override fun SafeContext.maxFlightY() =
		if (world.registryKey == World.NETHER) min(startPos.y + MAX_COAST_RISE, min(MAX_NETHER_LAYER_FLIGHT_Y, MAX_NETHER_FLIGHT_Y + layerHeight))
		else startPos.y + MAX_COAST_RISE

	/** The highest your feet may go while flying level: below a block roof, the nether roof, and not far above the flight line. */
	private fun SafeContext.ceilingY(ceiling: Double = maxFlightY()): Double {
		var rise = ROOF_SCAN_STEP
		while (rise <= ROOF_SCAN_HEIGHT && player.y + rise - ROOF_SCAN_STEP < ceiling) {
			if (!world.isSpaceEmpty(player, player.boundingBox.offset(0.0, rise, 0.0)))
				return min(ceiling, player.y + rise - ROOF_SCAN_STEP - ROOF_MARGIN)
			rise += ROOF_SCAN_STEP
		}
		return ceiling
	}

	/** At most [MAX_PITCH_STEP] from the last pitch towards [target]. */
	private fun eased(target: Double) = lastPitch + (target - lastPitch).coerceIn(-MAX_PITCH_STEP, MAX_PITCH_STEP)

	private fun SafeContext.flushPackets() {
		// Another thread may take the last one meanwhile.
		while (true) {
			val packet = sendPacketQueue.poll() ?: break
			ElytraFly.traceLine { "  sent held back ${ElytraFly.describe(packet)}" }
			// All in one write, flushed with the last.
			connection.sendPacketSilently(packet, flush = sendPacketQueue.isEmpty())
		}
		while (true) connection.handlePacketSilently(pingPackets.poll() ?: break)
	}

	fun getModifiedVelocity(original: Vec3d) =
		runSafe {
			if (!yMotion) return@runSafe original
			else Vec3d(original.x, 0.0, original.z)
		} ?: original

	override fun isGliding() =
		runSafe {
			val original = player.getFlag(Entity.GLIDING_FLAG_INDEX)
			if (prevGliding == true && !flightPaused) true
			else {
				prevGliding = original
				original
			}
		} == true
}
