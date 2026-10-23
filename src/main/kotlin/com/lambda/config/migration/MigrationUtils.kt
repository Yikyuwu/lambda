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

package com.lambda.config.migration

import com.lambda.config.Config
import com.lambda.config.ConfigCategory
import com.lambda.config.EntryLayer
import com.lambda.config.entries.Setting
import com.lambda.util.CommunicationUtils.logError
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

object MigrationUtils {
	/**
	 * Writes the [old] defaults into an existing file where they aren't set, so a changed default doesn't change it.
	 * Only what differs from the default is saved, so an unsaved setting would take the new one.
	 * Configs map to setting paths below their "Settings", separated by "/", and the old value. Returns how many were written.
	 */
	fun keepOldDefaults(rootNode: ObjectNode, old: Map<String, Map<String, Any>>): Int {
		var written = 0
		old.forEach { (configName, settings) ->
			val settingsNode = rootNode.childObject(configName)?.childObject("Settings") ?: return@forEach
			settings.forEach settings@{ (path, value) ->
				val keys = path.split("/")
				val parent = keys.dropLast(1).fold(settingsNode as ObjectNode?) { node, key -> node?.childObject(key) } ?: return@settings
				if (parent.has(keys.last())) return@settings
				when (value) {
					is Boolean -> parent.put(keys.last(), value)
					is Int -> parent.put(keys.last(), value)
					is Double -> parent.put(keys.last(), value)
					else -> parent.put(keys.last(), value.toString())
				}
				written++
			}
		}
		return written
	}

	/**
	 * Moves the HWTAura module's settings into HighwayTools' "Aura" group and ToolSaver's shulker restock into its "Tool Restock" group.
	 * Whether those modules were on isn't kept, they shared a keybind and got toggled by accident. Returns how many were moved.
	 */
	fun moveIntoHighwayTools(rootNode: ObjectNode): Int {
		var moved = 0
		fun move(from: ObjectNode, to: () -> ObjectNode?, key: String, newKey: String = key) {
			val value = from.remove(key) ?: return
			to()?.set(newKey, value)
			moved++
		}
		fun group(name: String) = rootNode.childObject("HighwayTools")?.childObject("Settings")?.childObject(name)

		(rootNode.remove("HWTAura")?.get("Settings") as? ObjectNode)?.let { aura ->
			aura.propertyStream().map { it.key }.toList()
				.filter { it != "Enabled" && it != "Keybind" }
				.forEach { move(aura, { group("Aura") }, it) }
		}

		(rootNode.get("ToolSaver")?.get("Settings") as? ObjectNode)?.let { toolSaver ->
			move(toolSaver, { group("Tool Restock") }, "Shulker Restock", "Restock Tools")
			listOf("Restock Tool", "Max Shulkers", "Swap Delay", "Retry Delay").forEach { move(toolSaver, { group("Tool Restock") }, it) }
		}
		return moved
	}

	/** The object at [key], created when missing. Null when something else is there. */
	private fun ObjectNode.childObject(key: String): ObjectNode? = when (val node = get(key)) {
		null -> putObject(key)
		is ObjectNode -> node
		else -> null
	}

	fun locateAndMoveMisplacedSettings(category: ConfigCategory, rootNode: ObjectNode): Int {
		var settingsMoved = 0
		category.configs.forEach { config ->
			val configNode = rootNode.get(config.name) ?: return@forEach
			if (!configNode.isObject) return@forEach
			val objNode = configNode.asObject()
			moveContentsIntoSettings(objNode)
			settingsMoved += locateAndMoveMisplacedSettings(config, objNode.get("Settings") as? ObjectNode ?: return@forEach)
		}
		return settingsMoved
	}

	fun moveContentsIntoSettings(configNode: ObjectNode) {
		val settingsNode = configNode.objectNode()
		configNode.propertyStream().forEach { (key, value) ->
			settingsNode.set(key, value)
		}

		configNode.removeAll()
		configNode.set("Settings", settingsNode)
	}

	private fun locateAndMoveMisplacedSettings(config: Config, rootNode: ObjectNode): Int {
		var settingsMoved = 0
		fun walk(multiple: EntryLayer.Multiple<Setting<*>>, obj: ObjectNode) {
			multiple.forEachEntry(
				false,
				{ _, childMultiple ->
					val existing = obj.get(childMultiple.name)
					val nestedObj: ObjectNode = when {
						existing != null && existing.isObject -> existing.asObject()
						existing == null || existing.isNull -> obj.putObject(childMultiple.name)
						else -> return@forEachEntry
					}
					try {
						walk(childMultiple, nestedObj)
					} catch (e: Throwable) {
						logError("Failed to relocate settings in ${childMultiple.multipleType.toString().lowercase()} '${childMultiple.name}' in '${config.name}'", e)
					}
				}
			) { path, single ->
				if (obj.has(single.name)) return@forEachEntry

				val fallbackValue = locateAndRemove(single.name, path, rootNode) ?: return@forEachEntry
				try {
					obj.set(single.name, fallbackValue)
					settingsMoved++
				} catch (e: Throwable) {
					logError("Failed to relocate setting '${single.name}' in '${config.name}'", e)
				}
			}
		}

		walk(config.settingLayers, rootNode)
		return settingsMoved
	}

	private fun locateAndRemove(
		settingName: String,
		parentLayers: List<EntryLayer.Multiple<Setting<*>>>,
		rootObj: ObjectNode
	): JsonNode? {
		var bestMatch: JsonNode? = null
		var bestMatchKey: String? = null
		var bestMatchParent: ObjectNode? = null
		var bestMatchScore = 0

		fun search(currentObj: ObjectNode, jsonPath: List<String>) {
			currentObj.propertyStream().forEach { (key, element) ->
				if (key.endsWith(settingName, ignoreCase = true)) {
					var score = 0
					if (key.equals(settingName, ignoreCase = true)) {
						score += 10
					}

					val fullPathString = (jsonPath + key).joinToString("")
					parentLayers.forEach { layer ->
						if (fullPathString.contains(layer.name, ignoreCase = true)) {
							score += 5
						}
					}

					if (score > bestMatchScore) {
						bestMatchScore = score
						bestMatch = element
						bestMatchKey = key
						bestMatchParent = currentObj
					}
				}

				if (element.isObject) {
					search(element.asObject(), jsonPath + key)
				}
			}
		}

		search(rootObj, emptyList())

		if (bestMatch != null && bestMatchParent != null && bestMatchKey != null) {
			bestMatchParent.remove(bestMatchKey)
		}

		return bestMatch
	}
}