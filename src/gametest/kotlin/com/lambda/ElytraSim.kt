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

package com.lambda

import com.lambda.module.modules.movement.elytrafly.ElytraFly
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.Perspective
import org.lwjgl.glfw.GLFW
import java.io.File

/**
 * Flies ElytraFly over the scenarios in elytra-sim/scenarios.txt in the run directory, in the save named there, one
 * line each: `name x y z yaw ticks distance`, ending that far from the start (where the highway ends), the first line
 * `world <save>`, lines starting with / are commands run once. Writes each tick to elytra-sim/out/<name>.csv.
 * Does nothing without the file.
 */
@Suppress("UnstableApiUsage")
object ElytraSim : FabricClientGameTest {
	override fun runTest(context: ClientGameTestContext) {
		val dir = File("elytra-sim")
		val file = File(dir, "scenarios.txt").takeIf { it.exists() } ?: return
		val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
		val save = lines.first { it.startsWith("world ") }.removePrefix("world ").trim()
		val out = File(dir, "out").apply { mkdirs() }

		// The test sets 5, ElytraFly waits for the chunks ahead to load before flying on into them. 10 is as far as it looks
		// ahead and more, 16 in four windows at once dropped one to 13 frames a second, ticks bunching up then.
		context.runOnClient<RuntimeException> {
			placeWindow(it)
			it.options.viewDistance.value = 10
			it.options.simulationDistance.value = 10
			it.createIntegratedServerLoader().start(save) {}
		}
		context.waitFor({ it.world != null && it.player != null && it.server != null }, 20 * 120)
		// Watched from behind, the box and what's around it in view.
		context.runOnClient<RuntimeException> { it.options.perspective = Perspective.THIRD_PERSON_BACK }
		context.waitTicks(40)
		waitForOthers(context, "ready")
		val prep = lines.filter { it.startsWith("/") }
		prep.forEach { if (it.startsWith("/wait ")) context.waitTicks(it.removePrefix("/wait ").trim().toInt()) else command(context, it) }
		// Only building: saved, to be copied and flown later.
		if (prep.isNotEmpty() && lines.none { !it.startsWith("world ") && !it.startsWith("/") }) {
			context.waitTicks(100)
			context.runOnClient<RuntimeException> { c ->
				val server = c.server!!
				server.execute { server.saveAll(false, true, true) }
			}
			context.waitTicks(400)
		}

		val flights = lines.filter { !it.startsWith("world ") && !it.startsWith("/") }
		// The first flight flown once unscored first: the first two or three flew at 20 to 60 frames a second, the planner's
		// code not compiled yet, then above 100.
		val warmup = flights.firstOrNull()?.split(Regex("\\s+"))?.toMutableList()?.apply {
			this[0] = WARMUP
			this[5] = "$WARMUP_TICKS"
		}?.joinToString(" ")
		for ((round, line) in (listOfNotNull(warmup) + flights).withIndex()) {
			// Teleported together with the others' next flights, not while they fly: loading a new spot drops their frames.
			if (round > 0) waitForOthers(context, "${round - 1}.done")
			val parts = line.split(Regex("\\s+"))
			val name = parts[0]
			val (x, y, z, yaw) = parts.subList(1, 5).map { it.toDouble() }
			val ticks = parts[5].toInt()
			val distance = parts.getOrNull(6)?.toDouble() ?: Double.MAX_VALUE
			context.runOnClient<RuntimeException> { ElytraFly.disable() }
			context.waitTicks(5)
			command(context, "/execute in minecraft:the_nether run tp @a ${x.plain()} ${y.plain()} ${z.plain()} $yaw 0")
			command(context, "/gamemode survival @a")
			command(context, "/item replace entity @a armor.chest with minecraft:elytra")
			command(context, "/effect give @a minecraft:resistance infinite 4 true")
			command(context, "/effect give @a minecraft:saturation infinite 4 true")
			command(context, "/effect give @a minecraft:fire_resistance infinite 0 true")
			command(context, "/effect give @a minecraft:instant_health 1 10 true")
			context.waitTicks(60)
			// Started in the air, a little above the floor: the takeoff from standing doesn't start the glide in a test.
			command(context, "/execute in minecraft:the_nether run tp @a ${x.plain()} ${(y + 1.2).plain()} ${z.plain()} $yaw 0")
			context.runOnClient<RuntimeException> {
				it.player!!.yaw = yaw.toFloat()
				it.player!!.pitch = 0f
				Lambda.LOG.info("[ElytraSim] start $name")
				ElytraFly.enable()
			}
			var stuckX = Double.NaN
			var stuckZ = Double.NaN
			var groundTicks = 0
			val rows = StringBuilder("tick,x,y,z,vx,vy,vz,ground,gliding,hcol,vcol,fps,enabled\n")
			// The fewest frames a second while flying: under 20 ticks bunch up in a frame, the route planner's answers come a
			// tick later then and the same flight may go another way.
			var minFps = Int.MAX_VALUE
			for (tick in 0 until ticks) {
				context.waitTick()
				// Decimals with a point whatever the system's language, the fps on its own.
				val (row, fps) = context.computeOnClient<Pair<String, Int>, RuntimeException> { c ->
					val p = c.player!!
					val v = p.velocity
					val fps = c.currentFps
					val flags = "${p.isOnGround},${p.isGliding},${p.horizontalCollision},${p.verticalCollision},$fps,${ElytraFly.isEnabled}"
					String.format(java.util.Locale.ROOT, "$tick,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,$flags", p.x, p.y, p.z, v.x, v.y, v.z) to fps
				}
				rows.append(row).append('\n')
				if (tick >= 20) minFps = minOf(minFps, fps)
				if (tick < 30) context.runOnClient<RuntimeException> { probe(it) }
				if (row.endsWith(",false")) break
				val cols = row.split(",")
				// Bumped or landed to walk: the flight failed there, off its way the rest of the track says nothing more.
				groundTicks = if (cols[7] == "true") groundTicks + 1 else 0
				if (cols[9] == "true" || groundTicks > WALK_STOP_TICKS) break
				// Stuck, e.g. walking into a wall or bouncing in place: on to the next.
				val (px, pz) = cols[1].toDouble() to cols[3].toDouble()
				if (Math.hypot(px - x, pz - z) >= distance) break
				if (tick % STUCK_TICKS == 0) {
					if (tick > 0 && Math.hypot(px - stuckX, pz - stuckZ) < 3) break
					stuckX = px
					stuckZ = pz
				}
			}
			context.runOnClient<RuntimeException> {
				Lambda.LOG.info("[ElytraSim] end $name, fps at least $minFps")
				ElytraFly.disable()
			}
			if (name != WARMUP) File(out, "$name.csv").writeText(rows.toString())
		}
		// Done with all its flights: the others don't wait for this one any more.
		waitForOthers(context, "finished", wait = false)
		// Leaving the world waits for the server, which waits for the test: the copy of the save isn't needed again.
		org.apache.logging.log4j.LogManager.shutdown()
		Runtime.getRuntime().halt(0)
	}

	private const val STUCK_TICKS = 300

	/** Logs why the bounce mode may hold still: each part of its flightPaused and the takeoff checks. */
	private fun probe(client: MinecraftClient) {
		val mode = ElytraFly.mode.elytraFly
		fun get(name: String): Any? = generateSequence<Class<*>>(mode.javaClass) { it.superclass }.firstNotNullOfOrNull { c ->
			runCatching { c.getDeclaredMethod(name).apply { isAccessible = true }.invoke(mode) }.getOrNull()
				?: runCatching { c.getDeclaredField(name.removePrefix("get").replaceFirstChar { it.lowercase() }).apply { isAccessible = true }.get(mode) }.getOrNull()
		}
		val p = client.player!!
		Lambda.LOG.info("[ElytraSim] probe mode ${mode.javaClass.simpleName} paused ${get("getFlightPaused")} interrupting ${get("interrupting")} " +
			"waitingChunks ${get("getWaitingForChunks")} braking ${get("isBraking")} waiting ${get("isWaiting")} noElytra ${get("getNoElytra")} " +
			"groundY ${get("groundY")} y ${p.y} vy ${p.velocity.y} chest ${p.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).item}")
	}

	private fun command(context: ClientGameTestContext, command: String) {
		context.runOnClient<RuntimeException> { c: MinecraftClient ->
			val server = c.server!!
			// The server waits for the client each tick in a game test, it runs it on its next tick.
			server.execute { server.commandManager.parseAndExecute(server.commandSource, command.removePrefix("/")) }
		}
		context.waitTicks(2)
	}

	/**
	 * The window filling the bottom right quarter of the screen's work area, like a window snapped into that corner, the
	 * top right one with -Delytrasim.corner=top, the top left one with topleft, the bottom left one with bottomleft, e.g.
	 * for more runs at once.
	 */
	private fun placeWindow(client: MinecraftClient) {
		val handle = client.window.handle
		val monitor = GLFW.glfwGetPrimaryMonitor().takeIf { it != 0L } ?: return
		val x = IntArray(1); val y = IntArray(1); val w = IntArray(1); val h = IntArray(1)
		GLFW.glfwGetMonitorWorkarea(monitor, x, y, w, h)
		val left = IntArray(1); val top = IntArray(1); val right = IntArray(1); val bottom = IntArray(1)
		GLFW.glfwGetWindowFrameSize(handle, left, top, right, bottom)
		val width = w[0] / 2 - left[0] - right[0]
		val height = h[0] / 2 - top[0] - bottom[0]
		GLFW.glfwSetWindowSize(handle, width, height)
		val corner = System.getProperty("elytrasim.corner") ?: "bottom"
		val leftHalf = corner == "topleft" || corner == "bottomleft"
		val upper = corner == "top" || corner == "topleft"
		GLFW.glfwSetWindowPos(handle, x[0] + (if (leftHalf) 0 else w[0] / 2) + left[0], y[0] + (if (upper) 0 else h[0] / 2) + top[0])
	}

	/** Written out in full for a command, never like 2.9999983E7: the ring road at the 30M edge didn't parse. */
	private fun Double.plain() = "%.4f".format(java.util.Locale.ROOT, this)

	/**
	 * With -Delytrasim.barrier=<folder> and -Delytrasim.count=<n>, several runs at once keep in step: they start flying
	 * together once all n have loaded their world ([step] "ready"), and after each flight wait for the others' flight of
	 * that number ("<round>.done") before teleporting on, at most [BARRIER_TICKS] each time; one that's done with all of
	 * its flights ("finished") isn't waited for. One still loading or teleporting takes the others' CPU, the route
	 * planner's thread answers later then and the same flight goes another way. Without [wait] it only says so.
	 */
	private fun waitForOthers(context: ClientGameTestContext, step: String, wait: Boolean = true) {
		val folder = System.getProperty("elytrasim.barrier")?.let(::File) ?: return
		val count = System.getProperty("elytrasim.count")?.toIntOrNull() ?: return
		folder.mkdirs()
		File(folder, "${ProcessHandle.current().pid()}.$step").createNewFile()
		if (!wait) return
		fun arrived() = folder.listFiles()?.count { it.name.endsWith(".$step") || it.name.endsWith(".finished") } ?: 0
		var waited = 0
		while (arrived() < count && waited < BARRIER_TICKS) {
			context.waitTicks(20)
			waited += 20
		}
	}

	private const val BARRIER_TICKS = 20 * 180
	private const val WARMUP = "warmup"
	private const val WARMUP_TICKS = 600
	/** Ticks on the ground in a row that are a walk, not a bounce (one or two), ending the flight. */
	private const val WALK_STOP_TICKS = 20
}
