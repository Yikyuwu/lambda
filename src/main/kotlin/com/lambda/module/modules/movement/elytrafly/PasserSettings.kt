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

import com.lambda.config.Config
import com.lambda.config.ConfigBlock
import com.lambda.util.NamedEnum
import net.minecraft.util.math.Direction
import java.awt.Color

/**
 * The settings the route search reads, see [PasserSettings]: the live ones on the game thread, taken as they are for a
 * search on the route thread, see [PasserValues.Taken].
 */
interface PasserValues {
	val minObstacleHeight: Double
	val headHitters: Boolean
	val obstacleLookAhead: Int
	val maxDodge: Int
	val dodgeLookAhead: Int
	val directionStep: Double

	/** [PasserValues] as they were when taken, only values. */
	data class Taken(
		override val minObstacleHeight: Double, override val headHitters: Boolean, override val obstacleLookAhead: Int,
		override val maxDodge: Int, override val dodgeLookAhead: Int, override val directionStep: Double,
	) : PasserValues

	/** These as they are now. */
	fun taken() = Taken(minObstacleHeight, headHitters, obstacleLookAhead, maxDodge, dodgeLookAhead, directionStep)
}

class PasserSettings(override val c: Config) : ConfigBlock, PasserValues {
	val passObstacles by c.setting("Pass Obstacles", true, "Automatically paths around obstacles using baritone")
	val walkWhenFlagged by c.setting("Walk When Flagged", false, "Triggers obstacle passer when the server forces your position (typically getting flagged by the anticheat)") { passObstacles }
	override val minObstacleHeight by c.setting("Min Obstacle Height", 0.063, 0.0..1.0, 0.0001, "The minimum height an obstacle must be above the ground to trigger obstacle passer") { passObstacles }
	override val headHitters by c.setting("Head Hitters", true, "Flags obstacles above the y level you started flying at") { passObstacles }
	val acceptableOffsetRange by c.setting("Acceptable Offset Range", 2.0, 0.1..5.0, 0.01, "Acceptable offset from the original flight line to allow when starting to fly again after passing obstacles") { passObstacles }
	override val obstacleLookAhead by c.setting("Obstacle Look-Ahead", 8, 0..50, 1, "Looks ahead of the player to see if obstacles are in the way") { passObstacles }
	val dodge by c.setting("Dodge In Flight", true, "Glides past a blocked spot on a free lane next to it, before landing to walk around it") { passObstacles }
	override val maxDodge by c.setting("Max Dodge", 2, 1..3, 1, "How far to the side to look for a free lane", " blocks") { passObstacles && dodge }
	override val dodgeLookAhead by c.setting("Dodge Look-Ahead", 64, 10..96, 1, "How far ahead to look for obstacles while gliding, for time to steer past them", " blocks") { passObstacles && dodge }
	val chooserDecides by c.setting("Chooser Decides", false, "At holes, layer ends and blocks picks the way past with the fastest exit of those simulated to make it from every start: a dodge, a glide over or under, a touch-down on a top of any height, a stepping stone, an opening, a side-step") { passObstacles }
	override val directionStep by c.setting("Direction Step", 45.0, 0.0..180.0, 0.1, "The step size to use when locking the flight direction") { passObstacles }
	val showPath by c.setting("Show Path", false, "Draws the planned path in the world: the glide over what's ahead, the dodge lane, the top to touch down on, the opening threaded through and the flight line. Only drawn, the flight is the same") { passObstacles }
	val glideColor by c.setting("Glide Color", Color(0, 220, 255, 255), "The glide simulated over what's ahead, and a swerve's way out and back") { passObstacles && showPath }
	val laneColor by c.setting("Lane Color", Color(255, 200, 0, 255), "The steering over to the dodge lane and the lane until past what's dodged") { passObstacles && showPath }
	val touchColor by c.setting("Touch Color", Color(60, 255, 60, 255), "The top touched down on") { passObstacles && showPath }
	val openingColor by c.setting("Opening Color", Color(255, 60, 255, 255), "The opening threaded through") { passObstacles && showPath }
	val lineColor by c.setting("Line Color", Color(255, 255, 255, 70), "The flight line") { passObstacles && showPath }
	val routePlanner by c.setting("Route Planner", RoutePlanner.Off, "Plans a route of several ways past what's ahead within 64 blocks. Shadow only logs it (with Debug Log) and draws it (with Show Path), the flight is the same. Rescue also flies it where the bot would otherwise land to walk, when a route gets past without landing. Decide flies the route's way whenever it's clearly faster than the deciders' plan, with the deciders as safety checks") { passObstacles }

	val routeThread by c.setting("Route Thread", true, "Plans the route on a thread of its own, over the corridor ahead taken each time it starts, so the game's tick doesn't wait for it and it has more time. Off, it's planned on the game's tick a few ms at a time like before. The flight is the same") { passObstacles && routePlanner != RoutePlanner.Off }

	val routeSnapshotCheck by c.setting("Route Snapshot Check", false, "Debug: every few route searches, takes the corridor ahead and runs the search through twice at once, over the live world and over what was taken, and logs (with Debug Log) where the routes differ and now and then what taking it cost. A frame's hitch each time, the flight is the same") { passObstacles && routePlanner != RoutePlanner.Off }

	enum class RoutePlanner(override val displayName: String) : NamedEnum {
		Off("Off"),
		Shadow("Shadow"),
		Rescue("Rescue"),
		Decide("Decide"),
	}
}
