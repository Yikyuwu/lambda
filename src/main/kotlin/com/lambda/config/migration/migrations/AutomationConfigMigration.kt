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

package com.lambda.config.migration.migrations

import com.lambda.Lambda.LOG
import com.lambda.config.categories.AutomationCategory
import com.lambda.config.migration.MigrationUtils
import com.lambda.config.migration.StepConfigMigration

@Suppress("unused")
object AutomationConfigMigration : StepConfigMigration() {
	override val category = AutomationCategory
	override val latestVersion = 4

	init {
		step(1, 2) { root ->
			var updateCount = 0
			root.propertyStream().forEach { configPair ->
				root.get(configPair.key).takeIf { it.isObject }?.asObject()?.let { config ->
					config.get("Limit Timeframe")?.let { limitTimeframe ->
						if (!limitTimeframe.isInt) return@forEach
						if (limitTimeframe.asInt() < 50) {
							config.put("Limit Timeframe", 310)
							updateCount++
						}
					}
				}
			}

			LOG.info("Migrated Automation config category schema v1 -> v2: $updateCount settings updated")
		}

		step(2, 3) { root ->
			val count = MigrationUtils.locateAndMoveMisplacedSettings(category, root)
			LOG.info("Migrated Automation config category schema v2 -> v3: $count settings moved")
		}

		// The defaults changed to ones that work well on 2b2t, existing setups keep the ones they had.
		step(3, 4) { root ->
			val pending = "Build/General/Max Pending Actions" to 59
			val breaks = "Break/Breaks Per Tick" to 59
			val airPlace = "Interact/Air Place" to "Standard"
			val interactions = "Interact/Interactions Per Tick" to 9
			val swapMode = "Hotbar/Swap Mode" to "Temporary"
			val pacing = arrayOf(pending, breaks, airPlace, interactions)
			val count = MigrationUtils.keepOldDefaults(root, mapOf(
				"Default" to mapOf(*pacing),
				"PlayerTrap Automation Config" to mapOf(*pacing),
				"Surround Automation Config" to mapOf(*pacing),
				"Nuker Automation Config" to mapOf(*pacing, swapMode),
				"Printer Automation Config" to mapOf(*pacing, swapMode),
				"FastBreak Automation Config" to mapOf(pending),
				"InventoryTweaks Automation Config" to mapOf(breaks, airPlace, interactions),
				"PacketMine Automation Config" to mapOf(pending, breaks),
				"AirPlace Automation Config" to mapOf(airPlace, interactions),
				"AutoPortal Automation Config" to mapOf(pending, breaks, airPlace),
				"AutoVillagerCycle Automation Config" to mapOf(pending, breaks, airPlace),
				"StashMover Automation Config" to mapOf(pending, breaks, airPlace),
				"Scaffold Automation Config" to mapOf(
					pending, airPlace, interactions,
					"Rotation/Instant Rotation" to true,
					"Rotation/Mean" to 40.0,
					"Rotation/Spread" to 10.0,
					"Hotbar/Keep Ticks" to 0,
				),
				"AutoEat Automation Config" to mapOf("Eat/Minimum Food Level" to 6, "Eat/Saturated" to "EatSmart"),
				"HighwayTools Automation Config" to mapOf(
					*pacing, swapMode,
					"Build/General/Spleef Entities" to false,
					"Build/Reach/Block Reach" to 4.5,
					"Break/Efficient Tools Only" to true,
					"Break/Suitable Tools Only" to true,
					"Inventory/Access/Access Shulker Boxes" to false,
					"Eat/Minimum Food Level" to 6,
					"Eat/Saturated" to "EatSmart",
					"Eat/Minimum Damage" to 10,
				),
			))
			LOG.info("Migrated Automation config category schema v3 -> v4: $count old defaults kept")
		}
	}
}