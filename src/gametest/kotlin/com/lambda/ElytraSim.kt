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

		// The test sets 5, ElytraFly waits for the chunks ahead to load before flying on into them.
		context.runOnClient<RuntimeException> {
			it.options.viewDistance.value = 16
			it.options.simulationDistance.value = 16
			it.createIntegratedServerLoader().start(save) {}
		}
		context.waitFor({ it.world != null && it.player != null && it.server != null }, 20 * 120)
		context.waitTicks(40)
		val prep = lines.filter { it.startsWith("/") }
		prep.forEach { command(context, it) }

		for (line in lines.filter { !it.startsWith("world ") && !it.startsWith("/") }) {
			val parts = line.split(Regex("\\s+"))
			val name = parts[0]
			val (x, y, z, yaw) = parts.subList(1, 5).map { it.toDouble() }
			val ticks = parts[5].toInt()
			val distance = parts.getOrNull(6)?.toDouble() ?: Double.MAX_VALUE
			context.runOnClient<RuntimeException> { ElytraFly.disable() }
			context.waitTicks(5)
			command(context, "/execute in minecraft:the_nether run tp @a $x $y $z $yaw 0")
			command(context, "/gamemode survival @a")
			command(context, "/item replace entity @a armor.chest with minecraft:elytra")
			command(context, "/effect give @a minecraft:resistance infinite 4 true")
			command(context, "/effect give @a minecraft:saturation infinite 4 true")
			command(context, "/effect give @a minecraft:fire_resistance infinite 0 true")
			command(context, "/effect give @a minecraft:instant_health 1 10 true")
			context.waitTicks(60)
			// Started in the air, a little above the floor: the takeoff from standing doesn't start the glide in a test.
			command(context, "/execute in minecraft:the_nether run tp @a $x ${y + 1.2} $z $yaw 0")
			context.runOnClient<RuntimeException> {
				it.player!!.yaw = yaw.toFloat()
				it.player!!.pitch = 0f
				Lambda.LOG.info("[ElytraSim] start $name")
				ElytraFly.enable()
			}
			var stuckX = Double.NaN
			var stuckZ = Double.NaN
			val rows = StringBuilder("tick,x,y,z,vx,vy,vz,ground,gliding,hcol,vcol,enabled\n")
			for (tick in 0 until ticks) {
				context.waitTick()
				val row = context.computeOnClient<String, RuntimeException> { c ->
					val p = c.player!!
					val v = p.velocity
					"$tick,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,${p.isOnGround},${p.isGliding},${p.horizontalCollision},${p.verticalCollision},${ElytraFly.isEnabled}"
						.format(p.x, p.y, p.z, v.x, v.y, v.z)
				}
				rows.append(row).append('\n')
				if (tick < 30) context.runOnClient<RuntimeException> { probe(it) }
				if (row.endsWith(",false")) break
				// Stuck, e.g. walking into a wall or bouncing in place: on to the next.
				val (px, pz) = row.split(",").let { it[1].toDouble() to it[3].toDouble() }
				if (Math.hypot(px - x, pz - z) >= distance) break
				if (tick % STUCK_TICKS == 0) {
					if (tick > 0 && Math.hypot(px - stuckX, pz - stuckZ) < 3) break
					stuckX = px
					stuckZ = pz
				}
			}
			context.runOnClient<RuntimeException> {
				Lambda.LOG.info("[ElytraSim] end $name")
				ElytraFly.disable()
			}
			File(out, "$name.csv").writeText(rows.toString())
		}
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
}
