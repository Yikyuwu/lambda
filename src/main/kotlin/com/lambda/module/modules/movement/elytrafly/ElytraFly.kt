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

import com.lambda.Lambda.LOG
import com.lambda.config.Tab
import com.lambda.config.automation.AutomationConfig.Companion.setDefaultAutomationConfig
import com.lambda.config.editSetting
import com.lambda.config.entries.Setting.Companion.onValueChange
import com.lambda.config.forEachSetting
import com.lambda.config.hideAllExcept
import com.lambda.config.withEdits
import com.lambda.context.Automated
import com.lambda.context.AutomatedSafeContext
import com.lambda.context.SafeContext
import com.lambda.event.events.ClientEvent
import com.lambda.event.events.MovementEvent
import com.lambda.event.events.PacketEvent
import com.lambda.event.events.TickEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.interaction.handlers.BaritoneHandler
import com.lambda.interaction.managers.rotating.RotationManager
import com.lambda.interaction.managers.rotating.RotationRequest
import com.lambda.module.Module
import com.lambda.module.modules.combat.AutoArmor
import com.lambda.module.modules.combat.AutoTotem
import com.lambda.module.modules.combat.autodisconnect.AutoDisconnect
import com.lambda.module.modules.movement.elytrafly.modes.BounceElytraFly
import com.lambda.module.modules.movement.elytrafly.modes.GeneralElytraFly
import com.lambda.module.modules.movement.elytrafly.modes.GrimControlElytraFly
import com.lambda.module.tag.ModuleTag
import com.lambda.task.Task
import com.lambda.threading.runGameScheduled
import com.lambda.util.CommunicationUtils.warn
import com.lambda.threading.runSafe
import com.lambda.util.NamedEnum
import com.lambda.util.Timer
import com.lambda.util.extension.isElytraFlying
import com.lambda.util.extension.prevPos
import com.lambda.util.player.MovementUtils.addSpeed
import com.lambda.util.player.PlayerUtils.hasFirework
import net.minecraft.component.DataComponentTypes
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.MovementType
import net.minecraft.item.Items
import net.minecraft.network.packet.Packet
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

object ElytraFly : Module(
    name = "ElytraFly",
    description = "Modifies elytra functionality to allow for more control",
    tag = ModuleTag.MOVEMENT,
) {
    @JvmStatic val mode by setting("Fly Mode", FlyMode.Bounce)
        .onValueChange { from, to ->
            from.elytraFly.onDisableListeners.forEach { it() }
            to.elytraFly.onEnableListeners.forEach { it() }
        }

    enum class RocketBoostMode(override val displayName: String): NamedEnum {
        Standard("Standard"),
        Grim("Grim")
    }

    private val boostSpeed by setting("Boost", 0.0, 0.0..0.5, 0.005, description = "Speed to add when flying")
    @JvmStatic val rocketBoostMode by setting("Rocket Boost Mode", RocketBoostMode.Grim)
    private val rocketSpeed by setting("Rocket Speed", 0.0, 0.0..2.0, 0.01, description = "Speed multiplier that the rocket gives you") { rocketBoostMode == RocketBoostMode.Standard }
    private val maxGrimBoost by setting("Max Grim Boost", 3.0, 0.0..3.0, 0.01, description = "Maximum additional speed the firework boost can add on top of the base rocket speed") { rocketBoostMode == RocketBoostMode.Grim }
    private val safetyMargin by setting("Safety Margin", 0.2, 0.0..2.0, 0.01, "The time (in seconds) to shorten the firework use delay to account for ping variation", "s")
    private val mute by setting("Mute Elytra", false, "Mutes the elytra sound when gliding")
    @JvmStatic val fakeFly by setting("Fake Fly", false, "Rapidly swaps the chestplate and elytra to give the appearance the player is flying without an elytra. May also reduce durability loss")
    private val safetyWarning by setting("Safety Warning", true, "Warns on enable when AutoArmor, AutoDisconnect or AutoTotem is off")
    private val debugLog by setting("Debug Log", false, "Writes setbacks and why the obstacle passer walks to the game log, to find out why flying stops")

    private const val BOUNCE_TAB = "Bounce"
//    private const val CONTROL_TAB = "Control"
    private const val GRIM_CONTROL_TAB = "Grim Control"
//    private const val PACKET_TAB = "Packet"
    private const val GENERAL_TAB = "None"

    private const val GRIM_ROCKET_BOOST_RESCALE = 1.65
    const val TRACE_TICKS = 10
    /** This many setbacks within [SETBACK_WINDOW_MS] stop it, it's stuck. */
    private const val MAX_SETBACKS = 5
    private const val SETBACK_WINDOW_MS = 2000L
    /** Stuck this often within [STUCK_WINDOW_MS] around the same spot even after starting over, it says so. */
    private const val MAX_STUCK = 3
    private const val STUCK_WINDOW_MS = 30_000L
    private const val STUCK_RADIUS = 3.0
    /** About the last 2 seconds of packets and ticks. */
    private const val RECENT_LINES = 300

    @Tab(GENERAL_TAB) @JvmStatic val generalMode by configBlock(GeneralElytraFly(this))
        .withEdits { forEachSetting { visibility { old -> { old() && mode == FlyMode.General } } } }
    @Tab(BOUNCE_TAB) @JvmStatic val bounceMode by configBlock(BounceElytraFly(this))
        .withEdits { forEachSetting { visibility { old -> { old() && mode == FlyMode.Bounce } } } }
    @Tab(GRIM_CONTROL_TAB) @JvmStatic val grimControlMode by configBlock(GrimControlElytraFly(this))
        .withEdits { forEachSetting { visibility { old -> { old() && mode == FlyMode.GrimControl } } } }
//    @Tab(CONTROL_TAB) @JvmStatic val controlMode by configBlock(ControlElytraFly(this))
//    @Tab(PACKET_TAB) @JvmStatic val packetMode by configBlock(PacketElytraFly(this))

    // Last sent movement packet state, used to rebuild Grim's firework prediction box.
    private var lastMovementIncludedPosition = true
    private var prevVelocity = Vec3d.ZERO
    private var targetVelocity: Vec3d? = null

    // Shared firework tracking. Vanilla can briefly drop the firework entity for a tick,
    // so once a firework is used, we hold this true until its flight duration runs out.
    private val fireworkTimer = Timer()
    private var lastFireworkDuration = -1.0
    @JvmStatic var hasFirework = false

    init {
        setDefaultAutomationConfig()
            .withEdits {
                hotbarConfig::tickStageMask.editSetting { defaultValue(TickEvent.ALL_STAGES.toMutableList()) }
	            hideAllExcept(::inventoryConfig, ::rotationConfig)
            }

        onEnable {
            mode.elytraFly.onEnableListeners.forEach { it() }
            // Flying AFK, these keep you alive or log you out before you die.
            val off = listOf(AutoArmor, AutoDisconnect, AutoTotem).filterNot { it.isEnabled }
            if (safetyWarning && off.isNotEmpty())
                warn("${off.joinToString { it.name }} ${if (off.size == 1) "is" else "are"} off, turn ${if (off.size == 1) "it" else "them"} on to stay safe while flying AFK")
            synchronized(recentSetbacks) { recentSetbacks.clear() }
            stuckAt.clear()
            prevVelocity = Vec3d.ZERO
            targetVelocity = null
            hasFirework = false
            fireworkTimer.reset()
            lastFireworkDuration = -1.0
        }
        onDisable {
            mode.elytraFly.onDisableListeners.forEach { it() }
            synchronized(recent) { recent.clear() }
        }

        listen<TickEvent.Pre>(priority = { 2 }) {
            if (!player.isGliding) {
                hasFirework = false
                return@listen
            }
            if (!withinFireworkTimeframe()) hasFirework = false
            hasFirework = hasFirework || player.hasFirework
        }

        listen<PacketEvent.Send.Pre> { event ->
            val packet = event.packet
            if (packet !is PlayerInteractItemC2SPacket) return@listen
            val stack = player.getStackInHand(packet.hand)
            if (stack.item != Items.FIREWORK_ROCKET) return@listen
            fireworkTimer.reset()
            lastFireworkDuration = (stack.get(DataComponentTypes.FIREWORKS)?.flightDuration ?: 1) * 0.5 + 0.5
        }

        listen<TickEvent.Pre> {
            if (rocketBoostMode != RocketBoostMode.Grim) return@listen
            if (!player.isGliding || !hasFirework || mode.elytraFly.pausingMovement()) return@listen

            val aiming = Vec3d.fromPolar(
                RotationManager.movementPitch ?: player.pitch,
                RotationManager.movementYaw ?: player.yaw,
            )
            val bounds =
                GrimFireworkBox.getFireworkBounds(
                    prevVelocity,
                    aiming,
                    lastMovementIncludedPosition,
                    GRIM_ROCKET_BOOST_RESCALE
                ) ?: return@listen
            val claimed = farthestPointInBox(bounds, aiming)?.let { limitSpeed(it) } ?: return@listen

            targetVelocity = claimed
            player.velocity = claimed
        }

        listen<PacketEvent.Send.Post> { event ->
            val packet = event.packet as? PlayerMoveC2SPacket ?: return@listen
            if (packet.changesPosition()) {
                val prevPos = player.prevPos
                val next = Vec3d(packet.getX(prevPos.x), packet.getY(prevPos.y), packet.getZ(prevPos.z))
                val delta = next.subtract(prevPos)
                prevVelocity = delta
            }
            lastMovementIncludedPosition = packet.changesPosition()
        }

        listen<MovementEvent.Entity.Pre> { event ->
            if (event.entity != player || !player.isGliding) return@listen
            val velocity = targetVelocity ?: return@listen
            targetVelocity = null
            player.velocity = velocity
            player.move(MovementType.SELF, velocity)
            event.cancel()
        }

        listen<PacketEvent.Receive.Pre> { event ->
            val packet = event.packet as? PlayerPositionLookS2CPacket ?: return@listen
            if (debugLog) synchronized(recent) {
                if (recent.isNotEmpty()) {
                    LOG.info("[ElytraFly] The ${recent.size} lines before the setback:")
                    recent.forEach { LOG.info("[ElytraFly] $it") }
                    recent.clear()
                }
            }
            debug { "Setback to ${packet.change.position.short} ${packet.relatives}, velocity ${packet.change.deltaMovement.short} (was ${player.velocity.short}), gliding ${player.isGliding}, on ground ${player.isOnGround}" }
            trace(TRACE_TICKS)
            val to = packet.change.position
            // On the game thread, the world and its entities change there meanwhile.
            if (debugLog) runGameScheduled {
                if (lastSurroundings?.let { it.squaredDistanceTo(to) < 1.0 } != true) {
                    lastSurroundings = to
                    debug { "Around the setback: ${surroundings(to)}" }
                }
            }
            // Set back over and over, e.g. by a block only the server has, walking or flying on never gets anywhere.
            val now = System.currentTimeMillis()
            val stuck = synchronized(recentSetbacks) {
                recentSetbacks.removeAll { now - it > SETBACK_WINDOW_MS }
                recentSetbacks.add(now)
                (recentSetbacks.size >= MAX_SETBACKS).also { if (it) recentSetbacks.clear() }
            }
            if (stuck) {
                // Dropping what it was at and planning anew gets it out. It never stops for it, when that doesn't it
                // only says so and starts over again.
                runGameScheduled {
                    stuckAt.removeAll { (time, pos) -> now - time > STUCK_WINDOW_MS || pos.squaredDistanceTo(to) > STUCK_RADIUS * STUCK_RADIUS }
                    stuckAt.add(now to to)
                    if (stuckAt.size >= MAX_STUCK) {
                        stuckAt.clear()
                        warn("Set back over and over at ${to.short} even after starting over $MAX_STUCK times. The server may have a block there the client doesn't, starting over again")
                    } else debug { "Set back $MAX_SETBACKS times within ${SETBACK_WINDOW_MS / 1000} seconds, starting over" }
                    mode.elytraFly.onStuckListeners.forEach { it() }
                }
                return@listen
            }
            mode.elytraFly.onFlagListeners.forEach { it() }
        }

        listen<PacketEvent.Send.Post> { event ->
            if (debugLog) traceLine("  sent ${describe(event.packet)}")
        }

        listen<PacketEvent.Receive.Pre> { event ->
            if (!debugLog) return@listen
            // What the server says about you, e.g. that it ended your glide.
            when (val packet = event.packet) {
                is EntityTrackerUpdateS2CPacket -> if (packet.id == player.id)
                    traceLine("  received data ${packet.trackedValues.joinToString { "${it.id}=${it.value}" }}")
                is EntityVelocityUpdateS2CPacket -> if (packet.entityId == player.id) traceLine("  received velocity")
                else -> {}
            }
        }

        listen<TickEvent.Post> {
            val wall = player.isGliding && player.horizontalCollision
            if (wall && !hitWall) {
                debug { "Hit a wall gliding at %.2f a tick, yaw %.1f, around: ${surroundings(player.pos)}".format(lastHorizontalSpeed, player.yaw) }
                trace(TRACE_TICKS)
            }
            hitWall = wall
            lastHorizontalSpeed = player.velocity.horizontalLength()
        }

        listen<TickEvent.Post> {
            if (!debugLog) return@listen
            val v = player.velocity
            traceLine(
                "  tick: vel %.3f %.3f %.3f, pose ${player.pose}, gliding flag ${player.getFlag(Entity.GLIDING_FLAG_INDEX)}, on ground ${player.isOnGround}, sprinting ${player.isSprinting}, yaw %.1f pitch %.1f, sent rot ${RotationManager.activeRotation} for ${requesters()}, input ${player.input.playerInput}, baritone ${BaritoneHandler.isActive}"
                    .format(v.x, v.y, v.z, player.yaw, player.pitch)
            )
            if (traceTicks > 0) traceTicks--
        }

        listen<MovementEvent.Player.Pre> {
            if (player.isElytraFlying && !player.isUsingItem) {
                addSpeed(boostSpeed)
            }
        }

        listen<ClientEvent.Sound> { event ->
            if (!mute) return@listen
            if (event.sound.id != SoundEvents.ITEM_ELYTRA_FLYING.id) return@listen
            event.cancel()
        }
    }

    /** Writes [message] to the game log with [debugLog]. */
    fun debug(message: () -> String) {
        if (debugLog) runSafe { LOG.info("[ElytraFly] at ${player.pos.short}: ${message()}") }
    }

    private var traceTicks = 0
    private val recentSetbacks = ArrayList<Long>()
    private val stuckAt = ArrayList<Pair<Long, Vec3d>>()
    /** What [traceLine] kept back lately, written out at a setback to see what led up to it. */
    private val recent = ArrayDeque<String>()

    /** [traceLine] for the modes, [line] only built with [debugLog]. */
    fun traceLine(line: () -> String) {
        if (debugLog) runSafe { traceLine(line()) }
    }

    /** Logs [line] while tracing, else keeps it back for [RECENT_LINES] lines in case a setback follows. */
    private fun SafeContext.traceLine(line: String) {
        if (traceTicks > 0) {
            LOG.info("[ElytraFly] at ${player.pos.short}: $line")
            return
        }
        // Packets come in on the network thread.
        synchronized(recent) {
            recent.addLast("at ${player.pos.short}: $line")
            if (recent.size > RECENT_LINES) recent.removeFirst()
        }
    }
    private var lastSurroundings: Vec3d? = null
    private var hitWall = false
    private var lastHorizontalSpeed = 0.0

    /** What the client has in the way around [pos], to tell what only the server has from what both have. */
    private fun SafeContext.surroundings(pos: Vec3d): String {
        val box = player.boundingBox.offset(pos.subtract(player.pos)).expand(0.6, 0.0, 0.6)
        val blocks = BlockPos.stream(box).map { it.toImmutable() }.toList().mapNotNull { blockPos ->
            val state = world.getBlockState(blockPos)
            val shape = state.getCollisionShape(world, blockPos)
            // Also ones without collision, like cobwebs, that still slow you down.
            if (state.isAir) null
            else "${Registries.BLOCK.getId(state.block).path} ${blockPos.toShortString()} ${if (shape.isEmpty) "no collision" else shape.boundingBox.offset(blockPos).short}"
        }
        val entities = world.getOtherEntities(player, box.expand(2.0)).map {
            "${EntityType.getId(it.type).path} ${it.boundingBox.short}"
        }
        // What it stands on, a floor only the client has would let it walk where the server makes it fall.
        val floor = BlockPos.stream(box.offset(0.0, -1.0, 0.0).withMaxY(box.minY - 0.5)).map { it.toImmutable() }.toList().map { blockPos ->
            "${Registries.BLOCK.getId(world.getBlockState(blockPos).block).path} ${blockPos.toShortString()}"
        }
        return "blocks [${blocks.joinToString()}], floor [${floor.joinToString()}], entities [${entities.joinToString()}]"
    }

    private val Box.short get() = "%.2f %.2f %.2f..%.2f %.2f %.2f".format(minX, minY, minZ, maxX, maxY, maxZ)

    /** Logs the packets sent and the player's state for the next [ticks] ticks with [debugLog], to see what the server set back. */
    fun trace(ticks: Int) {
        if (debugLog) traceTicks = max(traceTicks, ticks)
    }

    /** Who holds the yaw and the pitch, and who was turned down since the last trace line. */
    private fun requesters(): String {
        fun who(by: Automated?): String = when (by) {
            null -> "nobody"
            is Module -> by.name
            is Task<*> -> by.name
            is AutomatedSafeContext -> who(by.automated)
            else -> by::class.simpleName ?: by.javaClass.name
        }
        fun name(request: Any?) = who((request as? RotationRequest)?.requester)
        val rejected = RotationManager.lastRejected?.let { ", turned down ${name(it)}" } ?: ""
        RotationManager.lastRejected = null
        return "yaw ${name(RotationManager.yawRequest)}, pitch ${name(RotationManager.pitchRequest)}$rejected"
    }

    fun describe(packet: Packet<*>) = when (packet) {
        is PlayerMoveC2SPacket -> "${packet.javaClass.simpleName} " +
            (if (packet.changesPosition()) "pos %.3f %.3f %.3f ".format(packet.getX(0.0), packet.getY(0.0), packet.getZ(0.0)) else "") +
            (if (packet.changesLook()) "rot %.1f %.1f ".format(packet.getYaw(0f), packet.getPitch(0f)) else "") +
            "on ground ${packet.isOnGround} collision ${packet.horizontalCollision()}"
        is ClientCommandC2SPacket -> "ClientCommand ${packet.mode}"
        is PlayerInputC2SPacket -> "Input ${packet.input()}"
        is TeleportConfirmC2SPacket -> "TeleportConfirm ${packet.teleportId}"
        else -> packet.javaClass.simpleName
    }

    private val Vec3d.short get() = "%.2f %.2f %.2f".format(x, y, z)

    @JvmStatic
    fun getFireworkTargetVelocity(rotPitch: Float, rotYaw: Float): Vec3d {
        val vec = Vec3d.fromPolar(rotPitch, rotYaw)
        val d = 1.5 * rocketSpeed
        val e = 0.1 * rocketSpeed
        return vec.multiply(d + e * 2)
    }

    @JvmStatic
    fun boostRocket() =
        runSafe {
	        val vec = player.rotationVector
	        val velocity = player.velocity

	        val d = 1.5 * rocketSpeed
	        val e = 0.1 * rocketSpeed

	        player.velocity = velocity.add(
	    	    vec.x * e + (vec.x * d - velocity.x) * 0.5,
	    	    vec.y * e + (vec.y * d - velocity.y) * 0.5,
	    	    vec.z * e + (vec.z * d - velocity.z) * 0.5
	        )
        }

    fun withinFireworkTimeframe() = !fireworkTimer.timePassed((lastFireworkDuration - safetyMargin).seconds)

    private fun farthestPointInBox(bounds: DoubleArray, aim: Vec3d): Vec3d? {
        val center = Vec3d(
            (bounds[0] + bounds[3]) / 2.0,
            (bounds[1] + bounds[4]) / 2.0,
            (bounds[2] + bounds[5]) / 2.0,
        )
        val direction = aim.normalize()
        var near = Double.NEGATIVE_INFINITY
        var far = Double.POSITIVE_INFINITY
	    repeat(3) { axis ->
		    val origin = if (axis == 0) center.x else if (axis == 1) center.y else center.z
		    val min = bounds[axis]
		    val max = bounds[axis + 3]
		    val d = if (axis == 0) direction.x else if (axis == 1) direction.y else direction.z
		    if (abs(d) < 1e-12) {
			    if (origin !in min..max) return null
			    return@repeat
		    }
		    var entry = (min - origin) / d
		    var exit = (max - origin) / d
		    if (entry > exit) {
			    val tmp = entry
			    entry = exit
			    exit = tmp
		    }
		    near = max(near, entry)
		    far = min(far, exit)
	    }
        if (near > far) return null
        return center.add(direction.multiply(far))
    }

    private fun limitSpeed(velocity: Vec3d): Vec3d {
        val maxSpeed = 1.7 * rocketSpeed + maxGrimBoost
        val length = velocity.length()
        if (length <= maxSpeed) return velocity
        return velocity.multiply(maxSpeed / length)
    }

    enum class FlyMode(private val elytraFlyGetter: () -> ElytraFlyMode) {
        Bounce({ bounceMode }),
//        Control({ controlMode }),
        GrimControl({ grimControlMode }),
//        Packet({ packetMode }),
        General({ generalMode });

        val elytraFly get() = elytraFlyGetter()
    }
}