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
import com.lambda.config.categories.ModuleCategory
import com.lambda.config.migration.MigrationUtils
import com.lambda.config.migration.StepConfigMigration

@Suppress("unused")
object ModuleConfigMigration : StepConfigMigration() {
	override val category = ModuleCategory
	override val latestVersion = 4

	init {
		step(1, 2) { root ->
			val count = MigrationUtils.locateAndMoveMisplacedSettings(category, root)
			LOG.info("Migrated Module config category schema v1 -> v2: $count settings moved")
		}

		step(2, 3) { root ->
			val autoUpdater = root.get("AutoUpdater") ?: return@step
			if (!autoUpdater.isObject) return@step
			val settings = autoUpdater.get("Settings") ?: return@step
			val loaderPromptHandled = settings.get("Loader Prompt Handled") ?: return@step
			if (!loaderPromptHandled.isBoolean) return@step
			val properties = autoUpdater.asObject().objectOrCreate("Properties")
			properties.put("loaderPromptHandled", loaderPromptHandled.asString())
			LOG.info("Module config category schema v2 -> v3")
		}

		step(2, 3) { root ->
			val autoUpdater = root.get("AutoUpdater") ?: return@step
			if (!autoUpdater.isObject) return@step
			val settings = autoUpdater.get("Settings") ?: return@step
			if (!settings.isObject) return@step
			val loaderBranch = settings.get("Loader Branch") ?: return@step
			if (!loaderBranch.isString) return@step
			if (loaderBranch.asString() != "Snapshot") return@step
			settings.asObject().put("Loader Branch", "Dev")
		}

		// The defaults changed to ones that work well on 2b2t, existing setups keep the ones they had.
		step(3, 4) { root ->
			val count = MigrationUtils.keepOldDefaults(root, mapOf(
				"FancyChat" to mapOf("uwu" to false, ">" to true),
				"AutoArmor" to mapOf("Elytra Priority" to true, "Min Durability" to 5, "Preferred Leg Protection" to "BlastProtection"),
				"AutoDisconnect" to mapOf(
					"Triggers/Health Settings/Min Health" to 10,
					"Triggers/Totem Settings/Min Totems" to 2,
					"Triggers/Players" to false,
					"Triggers/Players Settings/Player Distance" to 64,
				),
				"CrystalAura" to mapOf("General/Update Mode" to "Ticked"),
				"SafeWalk" to mapOf("Step Height" to 0.5),
				"ElytraFly" to mapOf(
					"Rocket Speed" to 1.0,
					"Bounce/Pitch" to 72.0,
					"Bounce/Y Motion/Speed Limit" to 120,
					"Bounce/Bounce Obstacle Passer/Walk When Flagged" to true,
					"Bounce/Bounce Obstacle Passer/Direction Step" to 22.5,
				),
				"ESP" to mapOf("General/Outline/Screen Width" to 20),
				"ToolSaver" to mapOf("Min Durability" to 5, "Max Shulkers" to 2),
				"HWTAura" to mapOf("Weapon" to "NetheriteSword", "Any Weapon" to true),
				"HighwayTools" to mapOf(
					"Width" to 6,
					"Pavement" to "Block",
					"Floor" to "None",
					"Left Wall" to "None",
					"Ceiling" to "None",
					"Seal Border" to false,
					"Slice Size" to 3,
				),
			))
			LOG.info("Migrated Module config category schema v3 -> v4: $count old defaults kept")
		}
	}
}