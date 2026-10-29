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

import net.minecraft.block.Blocks
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

// The obstacle passer's tuning, see [ObstaclePassingMode].

/** How often the path shown is put together again, see [PasserSettings.showPath]: the glide is simulated anew each time. */
internal const val PATH_VIEW_TICKS = 4
/** How far behind you along the flight line it's drawn, see [PasserSettings.showPath]. */
internal const val PATH_LINE_BEHIND = 8.0
/** The width of the lines of the path shown, as wide on screen at any distance. */
internal const val PATH_LINE_WIDTH = -0.001f

/** How many routes the route planner keeps looking on from at each step, see [ObstaclePassingMode.RouteSearch]. */
internal const val ROUTE_BEAM = 4
/** How many ways past in a row a route has at most. */
internal const val ROUTE_DEPTH = 4
/** How far ahead along the line routes are compared at, see [ObstaclePassingMode.routeScore]. */
internal const val ROUTE_HORIZON = 64.0
/** How long planning a route may take each tick, it goes on the next, see [ObstaclePassingMode.RouteSearch]. */
internal const val ROUTE_BUDGET_NANOS = 3_000_000L
/** How many ticks a route bounces on for at most before looking again, about a bounce, see [ObstaclePassingMode.bounceOnWay]. */
internal const val ROUTE_BOUNCE_TICKS = 12.0
/** How far a route bounces on at least before looking again, see [ObstaclePassingMode.bounceOnWay]. */
internal const val ROUTE_BOUNCE_MIN = 3.0
/** How far before what's ahead a route's bounce on ends at least, closer there's no getting past it after, see [ObstaclePassingMode.bounceOnWay]. */
internal const val ROUTE_BOUNCE_ROOM = 2.0
/** The kind of way bouncing on along the lane, see [ObstaclePassingMode.bounceOnWay]. */
internal const val BOUNCE_ON = "bounce"
/** How much faster a route has to be than the deciders' plan for Decide to fly it, see [ObstaclePassingMode.routeTakeover]. */
internal const val ROUTE_SWITCH = 0.15
/** How close across the line a route's way is to the plan's to be the same way, see [ObstaclePassingMode.routeTakeover]. */
internal const val ROUTE_SAME_LANE = 0.5
/** How long Rescue may search for a route instead of landing, on the game thread, see [ObstaclePassingMode.routeRescue]. */
internal const val ROUTE_RESCUE_NANOS = 30_000_000L
/** How many ticks a route search goes on for at most, then the routes not looked on from yet are scored as they are. */
internal const val ROUTE_MAX_TICKS = 16
/** How long a route search on the route thread may take, see [RoutePlanner]: wider and deeper searches while there's time. */
internal const val ROUTE_THREAD_NANOS = 35_000_000L
/** The beams the route thread searches with in turn while there's time, each one's best route published, see [ROUTE_THREAD_NANOS]. */
internal val ROUTE_THREAD_BEAMS = listOf(ROUTE_BEAM, 8, 16)
/** How many ways past in a row a route the route thread searches for has at most, the last beam's. */
internal const val ROUTE_THREAD_DEPTH = 6
/** How many ticks old a route from the route thread may be when it's taken, older it's let go of. */
internal const val ROUTE_THREAD_STALE_TICKS = 10
/** How far along the line from where a route from the route thread expects you you may be when it's taken. */
internal const val ROUTE_ROOT_ALONG = 2.0
/** How far across the line from where it started you may be when it's taken. */
internal const val ROUTE_ROOT_OFFSET = 0.75
/** How far up or down from where it started you may be when it's taken, the bounces go up and down. */
internal const val ROUTE_ROOT_Y = 3.0
/** How long what the route planner found from a spot is kept, the world may change, see [ObstaclePassingMode.routeWays]. */
internal const val ROUTE_MEMO_TICKS = 40
/** After how many route searches where their time went is logged. */
internal const val ROUTE_TIMING_SEARCHES = 20
/** How many levels down off the end of one the route planner looks for floor, see [ObstaclePassingMode.downWay]. */
internal const val ROUTE_DROP_LEVELS = 2
/** Every how many route searches started [ObstaclePassingMode.checkRouteSnapshot] runs, see [PasserSettings.routeSnapshotCheck]. */
internal const val ROUTE_CHECK_EVERY = 4
/** After how many snapshot checks the corridor's size and what it cost is logged, see [ObstaclePassingMode.checkRouteSnapshot]. */
internal const val ROUTE_CHECK_LOG_EVERY = 10
/** How far behind you along the line the corridor goes, see [ObstaclePassingMode.captureCorridor]. */
internal const val CORRIDOR_BEHIND = 8.0
/** How far ahead along the line the corridor goes at least, as far as a route search looks past that. */
internal const val CORRIDOR_AHEAD = 80.0
/** How far across the line to either side the corridor goes, besides the farthest lane dodged to. */
internal const val CORRIDOR_SIDE = 4.0
/** How far a box swings out across the way past that, diagonally, see [ObstaclePassingMode.swingClears]. */
internal const val CORRIDOR_SWING = 2.0
/** How far above the highest flown the corridor goes with no highest, see [ObstaclePassingMode.maxFlightY]. */
internal const val CORRIDOR_OPEN_TOP = 8.0
/** The text of the way that's the deciders' plan, see [ObstaclePassingMode.planWay]. */
internal const val PLAN_TEXT = "plan"
/** The colours of a route's steps drawn, first to last, see [PasserSettings.showPath]. */
internal val ROUTE_COLORS = listOf(Color(255, 70, 70), Color(255, 150, 0), Color(190, 90, 255), Color(70, 140, 255))

internal const val MAX_PASS_STEPS = 4
internal const val MAX_FAILED_PASSES = 2
/** The same spot to walk to picked this often in a row gets nowhere. */
internal const val MAX_SAME_GOALS = 3
/** How far past a spot to walk to has to be free, to fly off from it. */
internal const val WALK_GOAL_CLEAR = 3.0
/** How long to stand still after finding no way past, before looking again. */
internal const val PASS_COOLDOWN_TICKS = 100
/** Ahead of RotationLock, which would otherwise keep the yaw on the line. */
internal const val DODGE_PRIORITY = 101
/** Ahead of everything else, the clock and a new world are seen first, see [flightTicks]. */
internal const val CLOCK_PRIORITY = 1000
/** How far ahead on the lane to steer at, shorter turns sharper. A sharp turn at speed gets set back. */
internal const val DODGE_STEER_AHEAD = 12.0
/** Close enough to the line to fly straight on again. */
internal const val DODGE_BACK_ON_LINE = 0.3
/** Close enough to the lane dodged to, to bounce on along it. */
internal const val ON_LANE = 0.3
internal const val MAX_DODGE_TICKS = 200
/** How far past the obstacle the lane has to be free, and the line before swinging back. */
internal const val DODGE_MARGIN = 3.0
/** Closer than this there's no time left to steer, walking around it is safer. */
internal const val MIN_DODGE_DISTANCE = 4.0
/** How far along an obstacle is looked at for room over it as high as it may fly, a block askew diagonally. */
internal const val TALL_SCAN = 1.5
/** How many ticks before the wall the heading is held to one side of the diagonal, see [threadYaw]. */
internal const val THREAD_TICKS = 30
/** How far across short of where it goes through it steers before that, coming in from the side it leans from. */
internal const val THREAD_PRE = 0.04
/** How many degrees at least the heading leans off the diagonal then, enough for which axis goes first, hardly drifting. */
internal const val THREAD_TILT = 0.05
/** How many degrees at most the heading leans off the diagonal for the flight to have turned to [THREAD_TILT] by the wall, see [heldLean]. */
internal const val THREAD_MAX_TILT = 2.0
/**
 * How wide across the spot an opening is gone through from has to be, at every tick of the way through it. A single
 * block missing diagonally is 0.07 wide up to 1.0 a tick, none from 1.1 on, see [passage].
 */
internal const val THREAD_MIN_WIDTH = 0.06
/** How far to either side of a lane an opening is looked for across, the spot gone through from may be off it. */
internal const val THREAD_WINDOW = 1.0
/** How far to either side of the spot planned it's looked for again at the speed you have, see [centreAt]. */
internal const val THREAD_RETARGET = 0.4
/** The speeds a spot is planned for, a step apart, see [centreAt]. */
internal const val THREAD_SPEED_STEP = 0.02
/** The fastest that fits through an opening is looked for this much at a time, from a bit above the speed you have. */
internal const val THREAD_SPEED_GRID = 0.05
/** Fitting through an opening this fast, it fits at any speed bouncing gets to, see [threadPlan]. */
internal const val THREAD_TOP_SPEED = 2.0
/** Slower than this no opening is planned for, holding the height it'd stall. */
internal const val THREAD_MIN_SPEED = 0.5
/** How much faster than you are it's looked for, the bounces before it speed you up. */
internal const val THREAD_SPEED_ABOVE = 0.4
/**
 * How much slower than the fastest that fits it's held to arrive. The glide through one is simulated like the
 * game's, see [glide], the speed logged going through is a tick before it, about 0.01 faster.
 */
internal const val THREAD_SPEED_MARGIN = 0.05
/** How far above and below the height held it flaps to shed speed for an opening, see [flapPitch]. */
internal const val SHED_BAND = 0.2
/**
 * How many ticks before the opening's held over it stops flapping at the latest, the last ones are steered onto the
 * spot it gets through with the speed it has then, see [threadYaw].
 */
internal const val SHED_CLEAR_TICKS = 10
/** Rising off the floor less than this high, it's held plainly instead of flapping, see [shedPitch]. */
internal const val SHED_RISE = 0.75
/** How close two obstacles along the line are the same one landed for to take off again slower, see [restartedFor]. */
internal const val RESTART_SAME = 3.0
/** How far before an opening it has to land at least to take off again and come in slower, see [restartFor]. */
internal const val RESTART_ROOM = 15.0
/** How long the glide over a hole [planSpeed] looks at is kept for it, the speed needed hardly changes meanwhile. */
internal const val SPEED_HOP_TICKS = 20
/** As wide as this at the spot either axis may go first, the heading isn't held to one side, see [Thread.lean]. */
internal const val THREAD_FREE_WIDTH = 0.25
/** How far above and below the height held the box is kept clear going through, holding it wavers a bit. */
internal const val THREAD_Y_MARGIN = 0.05
/** How far before and past what's glided over the way through it is looked at. */
internal const val THREAD_RUN = 1.0
/** How often what's past the start of a layer is looked at while gliding up onto it, see [dodgePastOnLayer]. */
internal const val LAYER_LOOK_TICKS = 5
/** How often something past a layer's start with no way past it yet is looked at again while far, see [dodgePastOnLayer]. */
internal const val LAYER_RELOOK_TICKS = 10
/**
 * After landing from a brake, how long until you stand: the server ends the glide only once it sees you
 * down, about a ping, and till then you slide on at full speed. Then walking friction stops you in a few.
 */
internal const val LANDING_TICKS = 8
/** Room left before the obstacle once stopped. */
internal const val BRAKE_MARGIN = 3.0
/** How long to wait on the ground for the server to end the glide, about a ping and a half on a bad day. */
internal const val MAX_LANDING_TICKS = 40
/** How close to a lane the swing over to it counts as on it. */
internal const val LANE_REACHED = 0.3
/** What's planned past a glide is looked at again from where you are only this fast, and dropped after this many misses. */
internal const val THEN_RECHECK_SPEED = 0.6
internal const val THEN_RECHECK_MISSES = 2
/** Half a chunk: looked at this often along the way, no chunk it crosses is stepped over, see [loadedAlong]. */
internal const val LOADED_STEP = 8.0
/**
 * A dodge lane with something on it within this past what's dodged has the other side looked at too, taken when
 * that's free this much farther, see [startDodge].
 */
internal const val LANE_FREE_AFTER = 24.0
internal const val LANE_FREE_BETTER = 6.0
/** Swerving past a block, on the lane this far before it and steered back this far after it, see [Swerve]. */
internal const val SWERVE_LEAD = 1.0
internal const val SWERVE_PAST = 1.0
/** Back on the line this close, it bounces on, see [swervePlan]. */
internal const val SWERVE_BACK_ON = 0.15
/**
 * Room kept beside the box swerving, and how high above what's low it's held. In the simulator the box came within
 * 0.1 of a pillar beside the way and sank down onto the railing held only 0.15 above it.
 */
internal const val SWERVE_SIDE_MARGIN = 0.25
/** Less once committed to it, so the next bounce a little off doesn't drop it, see [reswerve]. */
internal const val SWERVE_HELD_MARGIN = 0.12
internal const val SWERVE_ABOVE = 0.3
/** Room kept above the box held swerving, under the nether's roof at y 123 held at 122.31 there's 0.09. */
internal const val SWERVE_HEAD_ROOM = 0.05
/** Above the floor the box looked at for what's in the way starts, and how high bouncing takes it, 1.11 up and the box. */
internal const val SWERVE_FLOOR_GAP = 0.01
internal const val SWERVE_BOUNCE_BAND = 1.8
/** How finely the height held over what's low is looked for. */
internal const val SWERVE_HEIGHT_STEP = 0.05
/** Longest block along the line swerved past, and the farthest steering over to the lane may take. */
internal const val SWERVE_MAX_BLOCK = 16.0
internal const val SWERVE_MAX_REACH = 60.0
/** How often the swerve flown is planned again from where you are, see [reswerve]. */
internal const val SWERVE_RELOOK_TICKS = 4
/** Steering out this much earlier at a time when planned again, see [reswerve]. */
internal const val SWERVE_EARLIER_TICKS = 5
internal const val SWERVE_EARLIER_TRIES = 4
/** How many ticks of flight past the end of what's low the glide ends, see [swervePlan]. */
internal const val SWERVE_END_TICKS = 4
/** Why [swervePlan] has none when there's nothing to hold over on the way. */
internal const val SWERVE_FREE = "free bouncing, a dodge"
/** How far below the floor's height you count as having fallen into a hole. */
internal const val FELL_IN = 0.1
/** Room kept around a magma cube on every side, it hops about meanwhile. */
internal const val MAGMA_CUBE_MARGIN = 0.3
/** A magma cube is glided over like a block this high at least. */
internal const val CUBE_HEIGHT = 2.0
/**
 * How far across a mob attacks you, a magma cube just by touching: your box within this of its box sideways, at
 * its height, not above or below it. The game's sqrt(2.04) - 0.6.
 */
internal const val CUBE_REACH = 0.83
/**
 * How much farther than the climb up over (or the sink down under) a magma cube takes the height is held before
 * its reach, room for it jumping too, see [holdCube].
 */
internal const val CUBE_HOLD_MARGIN = 3.0
/**
 * The ticks before climbing up over (or sinking down under) a magma cube the height is held at most, besides the
 * climb itself, see [holdCube]: the simulation of the glide decides when, this is only a backstop. With the bounces
 * simulated like the flight the next bounce's landing is known to a few hundredths, more holds too early. Under
 * [CUBE_HIGH_RISE] up 2 more, aimed lower its last bounces come later.
 */
internal const val CUBE_LEAD_TICKS = 4
/** How high up over a magma cube is high, see [CUBE_LEAD_TICKS]. */
internal const val CUBE_HIGH_RISE = 2.5
/** How many ticks later than the next bounce holding over a magma cube from then has to make it still, see [holdCube]. */
internal const val CUBE_BOUNCE_MARGIN = 3
/** How much higher than its reach holding over a magma cube from the next bounce has to make it still, see [holdCube]. */
internal const val CUBE_BOUNCE_HEIGHT_MARGIN = 0.3
/** How far up and down from you magma cubes are looked for, the biggest is about two blocks. */
internal const val CUBE_SEARCH_HEIGHT = 4.0
/** How far ahead the line has to be free to swing back onto it, the swing takes about [DODGE_STEER_AHEAD]. */
internal const val RETURN_CLEAR = DODGE_STEER_AHEAD + DODGE_MARGIN
/** Less than the player's width taken for its rays, so a block only grazed isn't counted. */
internal const val WIDTH_MARGIN = 0.01
/**
 * How far ahead in ticks the drift across the line is counted in when steering. The flight direction lags the yaw
 * by a few ticks, so steering at where you are alone overshoots the lane, a third of a block on a one block swing.
 */
internal const val STEER_DAMPING_TICKS = 5.0
/** How much further off [steerPath] may be, it came out 0.04 short of a logged dodge as the yaw applies a tick late. */
internal const val STEER_MARGIN = 0.1
/** How tall and wide you are gliding. */
internal const val GLIDE_SIZE = 0.6
/** How far above what's needed to clear an obstacle to glide over it, for room to sink a little. */
internal const val HOP_MARGIN = 0.15
internal const val HOP_SCAN_STEP = 0.25
/** How far apart a lane is looked at for holes, less than one is long where the box drops in. */
internal const val HOLE_SCAN_STEP = 0.4
internal const val HOP_HEIGHT_STEP = 0.05
/** A gap shorter than this between obstacles is glided over too, there's no time to land in it. */
internal const val HOP_GAP = 4.0
/** Longer obstacles are dodged, holding the height costs speed. */
internal const val MAX_HOP_LENGTH = 16.0
/** Longest hole, or holes too close to bounce between, or obstacle, glided over in one, as long as the height holds. */
internal const val MAX_HOLE_LENGTH = 48.0
/** A layer of blocks this high on the floor is bounced on like the floor, anything higher is in the way. */
internal const val STEP_HEIGHT = 1.0
/** How far onto a layer to bounce on the glide up onto it goes, and how far on it must be free up there. */
internal const val STEP_ON = 1.0
internal const val STEP_CLEAR = 4.0
/** How many ticks up on a layer it's down on it after gliding up and a bounce got it a block higher, see [isStepUp]. */
internal const val STEP_RAISE_TICKS = 8.0
/**
 * How many ticks onto a layer past where its glide up ends a raise on it is looked for to glide straight up onto, when
 * that's faster than up onto the layer first, see [raiseOnStep]: two bounces up there, farther it's bounced on first.
 */
internal const val RAISE_LOOK_TICKS = 24.0
/** How far before a layer a step up the next bounce still comes down at least, closer it's walked onto, see [passObstacles]. */
internal const val STEP_BOUNCE_ROOM = 2.0
/** Room above a level to bounce on it, e.g. on a layer in a tunnel two blocks high: as high as you stand. */
internal const val LEVEL_ROOM = 1.8
/** A gap in a layer you bounce on up to this long is glided across, a longer one you drop down into. */
internal const val MAX_GAP = 8.0
/**
 * A shorter gap is crossed bouncing on, there's no time to fall into it, unless a bounce comes down right in it:
 * sinking a cm below the top there, the next of its blocks is in the way, see [intoLayerSide].
 */
internal const val MIN_GAP = 1.5
/**
 * A hole this short on a lane beside a hole is glided over in a bounce, the lane is still bounced on past it, e.g.
 * where the stair-stepped rim of a diagonal dip misses a block, see [floorLane].
 */
internal const val SIDE_STEP_GAP = 2.5
/** How far the box may stick out over the floor around a gap in a layer and still come down in it, see [intoLayerSide]. */
internal const val GAP_FALL_MARGIN = 0.1
/**
 * How far before a layer's end to stop bouncing and glide off it. Bounced off its very edge, the server takes
 * you for off it already and sets you back.
 */
internal const val DROP_BEFORE = 2.0
internal const val HOP_SIM_TICKS = 80
/** How long the way over to a lane is followed at most, as far as it looks ahead even at a third of a block a tick. */
internal const val STEER_SIM_TICKS = 400
/** Long enough to bounce up to speed from a take-off and over a hop as far as it's looked for. */
internal const val CAN_HOP_SIM_TICKS = 200
/** How long bouncing on after a glide is followed at most coming back down, see [clearComingDown]. */
internal const val COMING_DOWN_TICKS = 40
/** How far the box moves at most between the spots its way is looked at, see [boxMove]: under a block. */
internal const val BOX_WAY_STEP = 1.0
/** How much smaller the box is looked at along its way, see [boxMove]: touching a block isn't running into it. */
internal const val BOX_WAY_INSET = 1e-3
/**
 * How much narrower the box is looked at along its way, see [boxMove]: it's simulated on the lane, flown steering
 * on to it, a corner it only just clips is the lane's to keep clear of, see [freeLane].
 */
internal const val BOX_WAY_SIDE = 0.05
/** How many ticks after the next bounce starts holding from it is looked at, see [holdingFor]. */
internal const val NEXT_BOUNCE_MARGIN = 2
/** How much higher than needed holding from the next bounce has to get you, see [fromNextBounce]. */
internal const val NEXT_BOUNCE_HEIGHT_MARGIN = 0.2
/** Missing by at most this little, too close to stop it's gone for anyway, the flight often beats the simulation. */
internal const val ANYWAY_SHORT = 0.15
/** Steeper up the wings brake hard. Down to 90, like without wings, to top out a bounce in time. */
internal const val MAX_HOP_PITCH_UP = 30
/** How many degrees apart [holdPitch] looks first. */
internal const val HOLD_PITCH_STEP = 6
/** cos² of each whole pitch from -[MAX_HOP_PITCH_UP] to 90, the same as [glide] works out, see [glideClimb]. */
internal val PITCH_COS_SQ = DoubleArray(MAX_HOP_PITCH_UP + 91) { cos(Math.toRadians((it - MAX_HOP_PITCH_UP).toDouble())).let { c -> c * c } }
/** -sin of each whole pitch from -[MAX_HOP_PITCH_UP] to 90, see [glideClimb]. */
internal val PITCH_NEG_SIN = DoubleArray(MAX_HOP_PITCH_UP + 91) { -sin(Math.toRadians((it - MAX_HOP_PITCH_UP).toDouble())) }
/** Taking longer than this a tick, it's logged. The game has 50 for all. */
internal const val SLOW_PASSING_NANOS = 5_000_000L
/**
 * How many ticks before the obstacle to start holding the height, bouncing on until then, besides the ticks to
 * climb up there. Holding it all the way loses speed.
 */
internal const val HOP_LEAD_TICKS = 10
/** How many ticks more it may glide in low before gliding under something, off the bounces. */
internal const val UNDER_LEAD_TICKS = 20
/** Gliding in low under something only once starting this many ticks later wouldn't make it anymore. */
internal const val UNDER_HOLD_DELAY = 1
/**
 * How much lower than the room under something allows the glide in low is simulated with when deciding to hold it:
 * held a bit early, not at the very last tick that just scrapes under it.
 */
internal const val UNDER_TOP_MARGIN = 0.15
/** Up to how many ticks from now holding the height to glide under something is looked at, and how far apart. */
internal const val UNDER_HOLD_SEARCH_TICKS = 120
internal const val UNDER_HOLD_SEARCH_STEP = 6
/**
 * The first this many ticks of that looked at each: close by the window to start holding in may be a tick or two,
 * between coming down low enough and bouncing up off the floor into it, e.g. at 0.9 a tick from y 120.16 3.8 before a
 * floating block 4.7 on, only holding from 3 ticks on gets under it, from 0 or 6 doesn't: 6 apart it was judged
 * not made at all, and the miss told was the unheld run's, scraping the floor right before it.
 */
internal const val UNDER_HOLD_FINE_TICKS = 18
/**
 * How far gliding in low under something gets a tick of speed, before it's down to [UNDER_HOLD_END_SPEED] and on
 * the floor: in a 1-high gap about 38 blocks from 1.0 a tick, 23 from 0.8, 53 from 1.2.
 */
internal const val UNDER_HOLD_BLOCKS_PER_SPEED = 62.0
internal const val UNDER_HOLD_END_SPEED = 0.4
/**
 * The speed a tick gliding over a magma cube high in the room above it has to leave you with, else it's glided
 * over low. Gliding over it costs speed, too slow after it another cube right behind can't be got over.
 */
internal const val HIGH_CUBE_EXIT_SPEED = 1.0
/** How many ticks after gliding over a hole it comes down on the floor, and how much floor more it needs to bounce. */
internal const val HOLE_TOUCHDOWN_TICKS = 3
internal const val HOLE_TOUCHDOWN_MARGIN = 1.0
/** Ticks of room left before what's ahead to still wait a floor tick for the chooser's look, see [waitForRescue]. */
internal const val RESCUE_WAIT_TICKS = 2
/** How fast a lane past an obstacle is looked for once none fits at the speed you have, see [slowingToFit]. */
internal const val FIT_SPEED = 0.85
/**
 * The speeds slowed down to for a lane that only fits slower, the fastest first: diagonally past a pillar between
 * staircase walls the box fits at 0.57 at most, see [swingFit].
 */
internal val FIT_SPEEDS = listOf(FIT_SPEED, 0.6, 0.45)
/**
 * How much speed slowing down sheds a block at least, looking up while bouncing on: how early to start slowing down
 * for what fits only slower ahead, see [slowForNext]. Flown, it's 0.01 to 0.02.
 */
internal const val SLOW_SHED_PER_BLOCK = 0.01
/**
 * How far before what fits only slower the speed it fits is to be down to, for the swing over to its lane, see
 * [slowForNext].
 */
internal const val SLOW_AHEAD_LEAD = 12.0
/**
 * How far before what's next after the way flown now it's judged from at most, see [slowForNext]: about as far as
 * steering over to a lane past it takes, the line before that is free.
 */
internal const val NEXT_JUDGE_LEAD = 24.0
/** How often the same thing next after the way flown now is judged again, see [slowForNext]. */
internal const val NEXT_REJUDGE_TICKS = 24
/** How far above what blocks in a hole need a glide over it is held, when they need more than it'd rather go, see [clearInside]. */
internal const val RAISED_HOLD = 0.5
/** How far on past a block in a hole that can't be glided over the rest of it is looked at from, about a block, see [clearInside]. */
internal const val PAST_BLOCKED = 1.0
/** How far above a top to touch down on the feet are held until over it, see [Touch]. */
internal const val TOUCH_ABOVE = 0.2
/** How far below a top to touch down on the hold aims once over it, down onto it. */
internal const val TOUCH_DIVE = 1.0
/** How many ticks before the middle of a top to touch down on the dive onto it starts, sinking takes the glide that long. */
internal const val TOUCH_DIVE_TICKS = 2
/** How fast it may sink holding the height, smoothly, and down onto a top to touch down on, see [holdPitch]. */
internal const val HOLD_SINK = 0.1
internal const val TOUCH_SINK = 0.6
/** How high above the top to touch down on it skims, diving, until the box is over it, see [Hop.holdY]. */
internal const val TOUCH_SKIM = 0.05
/**
 * How many ticks in a row down gliding without moving is stuck, walking on: taken off standing still the first
 * touch down has no speed yet, its bounce pushes you on. Walked again there, it took off and stopped over and over.
 */
internal const val STILL_TICKS = 3
/**
 * How much faster after it gliding over a top has to be than touching down on it to glide instead, see [glidingFaster].
 * Bounced off, the bounces after it speed you up on, the glide only coasts: at 0.02 floating blocks were glided over
 * and come to slower.
 */
internal const val TOUCH_MIN_GAIN = 0.08
/** How much higher than needed gliding over has to get over to be done instead of touching down, see [glidingFaster]. */
internal const val PLAIN_GLIDE_MARGIN = 0.2
/** How many bounces of a layer before its end with a hole after it it's landed on as late as before, see [stepLanding]. */
internal const val STEP_LANDING_BOUNCES = 2
/** How far before the end of a hole with a layer right after it the feet are above that layer already, see [holeHop]. */
internal const val LAYER_AFTER_HOLE = 1.5
/** How many ticks of a dip a block deep there has to be at least to bounce on down there, about a bounce, see [dipLane]. */
internal const val DIP_BOUNCE_TICKS = 12
/** How far below the floor you started on you're down in a dip, see [intoDip]. */
internal const val DIP_DOWN_MARGIN = 0.05
/** How far in from a dip's ends its sides are looked at for the swing, see [dipSwingClear]. */
internal const val DIP_SWING_ENDS = 1.0
/** How far before a dip bounced on down into its start may be seen from another spot, the same dip, see [dipAt]. */
internal const val DIP_AGAIN = 1.5
/** Blocks besides lava that hurt or hold you without stopping you, see [PassWorld.hazardAt]. */
internal val HAZARDS = setOf(Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.COBWEB, Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE, Blocks.POWDER_SNOW, Blocks.CACTUS)
/** How many ticks before the box comes over the top it skims down to just above it, see [Hop.holdY]. */
internal const val TOUCH_SKIM_TICKS = 4
/** How far before and past where the lane planned crosses the top it may come over it, see [hopPitch]. */
internal const val TOUCH_NEAR = 1.5
/** How long after the last tick diving onto a top or touching down on it the landing is still held back, see [touchFloor]. */
internal const val TOUCH_DIVE_HOLD_TICKS = 2
/**
 * How much of the box, in square blocks, has to be over the top to come down on it: a sixth, not a corner sliver.
 * Passing beside its middle, e.g. on a lane past a pillar at a hole's start, no more of it may come over it, it's
 * come down on in the middle of the way across it instead, see [hopPitch].
 */
internal const val TOUCH_MIN_OVERLAP = GLIDE_SIZE * GLIDE_SIZE / 6
/**
 * How far inside a top's edges, along x and z, the middle of the box has to be to come down on it: well over it, not
 * on its edge. Not in a hole, see [touchLane]. Nearer its edge the move across that tick may carry it off the top,
 * it's never come down on there, glided on past it instead, see [topMargin]. The tick it comes over it decides how far
 * in it is: simulated, 0.15 in and more a single block was hardly ever come down on at a tick's speed, the parkour
 * field's stepping stones half as often. At 0.08 a single pillar on a diagonal was glided over instead of bounced
 * on, so only the middle of the box has to be over it, not off its edge.
 */
internal const val TOUCH_EDGE = 0.01
/** How far to either side of the lane through a top's middle the way there is looked at, see [topHops]. */
internal const val TOUCH_AIM_WINDOW = 0.15
/** How far across from the lane a top was found on the lane through its middle is looked for, see [topHops]. */
internal const val TOUCH_AIM_REACH = 0.75
/** The steps the lanes and spots along them are looked at in for the one through a top's middle, see [topHops]. */
internal const val TOUCH_AIM_STEP = 0.1
/** How many of the ways onto a top, the lane most inside its edges first, are simulated at most, see [topHops]. */
internal const val TOUCH_AIM_TRIES = 4
/** The ways along x and z a top's edges are looked for, see [topMargin]. */
internal val TOP_SIDES_X = intArrayOf(1, -1, 0, 0)
internal val TOP_SIDES_Z = intArrayOf(0, 0, 1, -1)
/** A move's axes this close in length, either may go first, see [swingPathHit]. */
internal const val SWING_EVEN = 1e-4
/**
 * How far the box has to reach over the top each way to dive onto it, a sliver of its corner is enough to come
 * down on, see [topUnderSoon].
 */
internal const val TOUCH_OVERLAP = 0.05
/**
 * Room above a top to touch down on and bounce off. Held back with the jump, the server doesn't see the landing
 * and you glide on to it, low like in a tunnel two high. Should it end the glide after all, crouching fits.
 */
internal const val TOUCH_ROOM = 1.9
/** How long the box has to have a top under it to touch down on it. */
internal const val MIN_TOUCH = 0.6
/**
 * How far below a top the block it's the top of is looked for, see [topAt]: a whole one's is the block below it, a
 * carpet's or a slab's the block it's in.
 */
internal const val TOP_BELOW = 1e-3
/**
 * How far before and after the top to touch down on its landing is held back, see [touchFloor]: coming down on its
 * edge the same tick may carry you on past it.
 */
internal const val TOUCH_HELD_AROUND = 2.0
/** How far before a top on another lane it's steered over to it, see [laneTouch]. */
internal const val TOUCH_APPROACH = 8.0
/** How far past a hole's end the lane to touch down on in it has to be free, e.g. past a pillar there. */
internal const val TOUCH_PAST = 3.0
/** The slowest steering on past a top is simulated at, see [pastTopHit], slower it hardly gets anywhere along the line. */
internal const val PAST_TOP_MIN_SPEED = 0.3
/** How far above the height glided at the box is looked at past a top, see [pastTopHit]: down on one it's at its height. */
internal const val PAST_TOP_LIFT = 0.01
/** The speed steering over to the lane of a top in a hole is simulated at, at least, see [touchLane]. */
internal const val TOUCH_STEER_SPEED = 1.6
/** How many ways to touch down on a top in a hole are simulated at most, best first, see [touchLane]. */
internal const val TOUCH_PLANS_TRIED = 4
/** How far off the lane to touch down on in a hole from it may still be when it gets there, see [touchLane]. */
internal const val TOUCH_LANE_OFF = 0.15
/** How much later steering in toward the lane over a top in a hole is tried next, see [touchLane]. */
internal const val TOUCH_SWITCH_STEP = 0.5
/** How long a top steered in toward too fast to get over is left out of touching down, see [steerInMisses]. */
internal const val STEER_MISS_TICKS = 100
/** How long until a hole after a layer not gotten over yet from far is looked at again, see [layerHole]. */
internal const val LAYER_HOLE_AGAIN_TICKS = 5
/**
 * How long before what's beside the lane through a hole it's tilted past, see [touchLane]. Long enough for the
 * flight to take on [MIN_LEAN_DEGREES], short so it doesn't drift off the lane, it can't steer in meanwhile.
 */
internal const val TOUCH_LEAN_TICKS = 8
/**
 * How much more room than the box a lane to touch down on in a hole needs on the way, see [touchLane], and the way
 * over to a lane, see [steerPathHit].
 */
internal const val LANE_CLEAR_MARGIN = 0.05
/** How long after touching down the glide may still be ended, until the bounce off the top takes off again. */
internal const val TOUCH_GRACE_TICKS = 5
/** How far ahead, in ticks at the speed you have, floor beside a hole is looked for to land on, see [landBeside]. */
internal const val LAND_AHEAD_TICKS = 12
/** How many ticks ahead the lane picked to land beside a hole on is still taken to fit, see [landBeside]. */
internal val LAND_KEEP_TICKS = listOf(LAND_AHEAD_TICKS, LAND_AHEAD_TICKS - 2, LAND_AHEAD_TICKS + 2, LAND_AHEAD_TICKS - 4, LAND_AHEAD_TICKS + 4)
/** How often an obstacle still far with no way past yet is looked at again, see [lookedLately]. */
internal const val RELOOK_TICKS = 4
/** A multiple of every period looked again in and longer than any tick stamp is kept, see [onEnable]. */
internal const val PHASE_TICKS = 240
/** What a way past that doesn't get past costs the chooser, see [shadowChoose]. */
internal const val CHOOSER_FAIL_COST = 1000.0
/** What landing to walk costs the chooser. */
internal const val CHOOSER_BRAKE_COST = 300.0
/** What touching down costs the chooser on top, it risks a setback on 2b2t. */
internal const val CHOOSER_LAND_PRIOR = 3.0
/** How many ticks of the way on after the checkpoint the exit speed is valued over, in blocks. */
internal const val CHOOSER_EXIT_VALUE = 40.0
/** How far past what's ahead the chooser compares the ways past at, a block and a hole. */
internal const val CHOOSER_PAST_BLOCK = 25.0
internal const val CHOOSER_PAST_HOLE = 15.0
/**
 * The starts each way past is simulated from: off by this much speed a tick, and this far along the line. About
 * as far off as the simulation is from real flights (~0.03 a tick), farther every touch-down missed by cm.
 */
internal val CHOOSER_STARTS = listOf(0.0 to 0.0, -0.03 to -0.3, 0.03 to 0.3)
/** From how many of [CHOOSER_STARTS] Chooser Decides needs a way to get past, the first among them, see [pickWay]. */
internal const val CHOOSER_MIN_STARTS = 2
/** How long the chooser may take a run on average. */
internal const val CHOOSER_BUDGET_NANOS = 2_000_000L
/** Ways whose exit from the first start is this much below the best one getting past from every start aren't simulated from the others. */
internal const val CHOOSER_CLEAR = 0.02
/** The ways past what's ahead put together are only simulated again once this much slower or faster, else on something new or a change of the plan. */
internal const val CHOOSER_SPEED_DRIFT = 0.1
/** The ways past what's ahead are put together again after this many ticks, where you are decides which lanes are reached. */
internal const val CHOOSER_REBUILD_TICKS = 20
/** Deciding, a way replaces the plan committed to only exiting this many times as fast, see [chooseAtHole]. */
internal const val CHOOSER_SWITCH = 1.15
/**
 * How many of the 25 points of the box at least are over a top steered in toward where it gets across, see
 * [touchLane]: fewer, it's on its edge, a bit short across it misses and the rest of the hole is glided.
 */
internal const val TOUCH_STEER_MIN_COVER = 15
/** How many of the ways to touch down on a top in a hole the chooser looks at, best covered first, see [touchLane]. */
internal const val CHOOSER_TOUCH_PLANS = 6
/** How often a way over a hole the chooser picked is looked at again before it's held, for a much faster one. */
internal const val CHOOSER_RELOOK_TICKS = 8
/** No way past found, not looked for again for this many ticks unless the lane, speed band or floor changes, see [chooserMissed]. */
internal const val CHOOSER_MISS_TICKS = 10
/** Likewise with no way to look at at all, e.g. a wall too long to glide over with no lane past it. */
internal const val CHOOSER_NONE_TICKS = 20
/** How wide a speed band is that a miss is remembered for, see [chooserMissed]. */
internal const val CHOOSER_MISS_BAND = 0.05
/** How far past the block it'd run into a top to bounce on is looked for, its own top too, see [topWay]. */
internal const val TOP_WAY_PAST = 1.5
/** Skimming just above a top already, how many ticks ahead of it is enough to drop onto it, see [topWay]. */
internal const val TOP_WAY_SKIM_TICKS = 1.5
/**
 * How far below what it needs the glide over what follows a way may come out and still count, see [continuation]:
 * it's planned again from where it really is then, e.g. a bounce off a top a bit higher than simulated.
 */
internal const val CHAIN_SHORT = 0.1
/** The exit counted for a way followed by braking for what's next, a way past at a crawl, see [continuation]. */
internal const val CHAIN_BRAKE_EXIT = 0.05
/** How far apart across the line two ways through an opening the chooser looks at are at least, see [blockWays]. */
internal const val THREAD_CENTRES_APART = 0.15
/** The pitch braking is flown at, see [brakeLandingHit] and the mode's [isBraking]. */
internal const val BRAKE_PITCH = 90.0
/** How tall you stand once the server ends the glide after landing. */
internal const val STANDING_HEIGHT = 1.8
/** How many blocks above its floor a top is looked for at most, see [laneSurfaces]. */
internal const val MAX_SURFACE_RISE = 4
/** How high a bounce off the floor or a top takes the feet, about, see [roomOffTop]. */
internal const val BOUNCE_PEAK = 1.12
/** How many ticks a bounce takes, about. */
internal const val BOUNCE_TICKS = 12
/** How many ticks of the rise off a top the room above it is looked at all the way up from it, see [roomOffTop]. */
internal const val TOUCH_RISE_TICKS = 2
/** How much room above a bounce off a top is kept clear, see [roomOffTop]. */
internal const val TOUCH_CLEARANCE = 0.2
/** How many touch-downs on a lane the chooser looks at at a block, first first, see [chooseAtBlock]. */
internal const val CHOOSER_LANE_TOPS = 2
/** How close below its height over a hole counts as up there, see [Hop.topped]. */
internal const val TOPPED_MARGIN = 0.1
/** How often the holes ahead are looked at, see [planSpeed]. */
internal const val AHEAD_SCAN_TICKS = 4
/** The fastest looked at for what a hole needs, a tick, for the debug log. Bouncing doesn't get you much faster. */
internal const val MAX_PLAN_SPEED = 2.2
/** How far before braking would be needed to stop waiting to get fast enough to glide over, and dodge instead. */
internal const val HOP_RETRY_MARGIN = 24.0
/** The fastest [holdPitch] climbs to the height held. Slower, a block up takes longer than it's held ahead. */
internal const val MAX_HOLD_CLIMB = 0.25
internal const val JUMP_VELOCITY = 0.42
/**
 * The speed a bounce adds, the sprint jump's push along the way, before that tick's glide at [JUMP_PITCH]. Left
 * out, a glide simulated past a bounce comes out too slow, e.g. over the rest of a hole after touching down on a
 * pillar in it. Like vanilla, the logged bounces match it to a hundredth of a block.
 */
internal const val BOUNCE_SPEED_GAIN = 0.2
/**
 * How many of the next bounces [threadHold] looks at holding from: the next one may be too far off still, gliding
 * that far it'd stall, while one after it gets there.
 */
internal const val THREAD_HOLD_RISES = 3
/**
 * About how much speed holding the height sheds a block, see [threadHold]: an opening that only fits slower is held
 * from as far before it as that takes, e.g. 62 before one fitting up to 0.95 at 1.75.
 */
internal const val THREAD_HOLD_SLOWDOWN = 0.013
/** The pitch the jump tick of a bounce is flown at, level, see BounceElytraFly. */
internal const val JUMP_PITCH = 0.0
/** How high a bounce takes your feet off the floor where nothing else caps it. */
internal const val MAX_BOUNCE_RISE = 2.0
/**
 * How far out of line a diagonal heading is tilted while passing an obstacle. The box swings out to the side the
 * heading tilts to, so tilted away from the obstacle it never swings into it. Drifts a block over 115 ticks.
 */
internal const val LEAN_DEGREES = 0.5
/** How much of [LEAN_DEGREES] the flight has to have taken on, it turns a tenth of the way each tick. */
internal const val MIN_LEAN_DEGREES = 0.2
/**
 * How far the heading stays tilted once on a lane past carpets. Enough for the box to swing out only to that side,
 * the larger of the two moves goes first, drifting a block over 300 ticks.
 */
internal const val HOLD_LEAN_DEGREES = 0.25
/** How long before the obstacle to tilt, for the flight to take it on. */
internal const val LEAN_TICKS = 20
/** How far apart the rays across the way are, close enough that no block corner slips between them. */
internal const val RAY_SPACING = 0.25
/** How long after a knockback a bump into a wall is taken for the knockback's, not walked on from, see [knockedBack]. */
internal const val KNOCKBACK_GRACE_TICKS = 5
/** How much the server has to change your speed for it to be a knockback, not just your speed told back. */
internal const val KNOCKBACK_MIN_CHANGE = 0.1
/** Slower than this after a bump, you're stopped against the wall, knocked back or not. */
internal const val STOPPED_SPEED = 0.05
/** How far above two blocks over the floor the top of a bounce goes, bumping a roof there. */
internal const val ROOF_BUMP = 0.1
/** How far around you entities as solid as blocks, e.g. boats, are looked for, as far as the planner looks. */
internal const val SOLID_ENTITY_RANGE = 128.0
/** Faster than now a bit, for speeding up on the way. */
internal const val SWEEP_SPEED_MARGIN = 0.1
/** Faster than now a bit not sprinting, for what a tick's glide adds, see [swingClears]. Sprinting, a bounce adds [BOUNCE_SPEED_GAIN]. */
internal const val SWING_SPEED_MARGIN = 0.02
/** How far to either side of the lane the box may be off it and still clear what's beside the way, see [swingFit]. */
internal const val SWING_FIT_MARGIN = 0.03
/** How much faster than the next sprint jump takes you [swingFit] looks, faster still counts as fitting any speed. */
internal const val SWING_FIT_ABOVE = 0.4
/** Slower than this no tick fits, it's in the way, not beside it, see [swingFit]. */
internal const val SWING_FIT_MIN = 0.5
/** How much slower than the box fits along the way to an opening it's held to, see [threadPlan]. */
internal const val THREAD_APPROACH_MARGIN = 0.05
/** How often [swingFit] is looked at again, in ticks. */
internal const val SWING_FIT_TICKS = 2
/** How much faster than now a lane is picked for, bouncing over to it speeds you up, diagonally by a block a tick or so. */
internal const val LANE_SPEED_MARGIN = 0.5
/** How far across the highway to look for its walls, to fly down its middle. */
internal const val MAX_CENTER_LANES = 8
/** How many spots ahead the walls are looked for at, and how far apart, a block lying in the way at one isn't the wall. */
internal const val CENTER_SPOTS = 8
internal const val CENTER_SPOT_STEP = 2.0
/** The line starts this far behind you, so its direction from the start stays the one you fly in once it's moved over. */
internal const val CENTER_BACKOFF = 16.0
/** How often the line's looked at for drifting off the highway's middle, in ticks, and how far off it moves it back. */
internal const val CENTER_DRIFT_TICKS = 40
internal const val CENTER_DRIFT = 0.5
/** Two directions closer than this (1 - their dot) are the same line, steps are at least a tenth of a degree apart. */
internal const val SAME_LINE_DIR = 1e-9
/** How far ahead the highway's way is followed as you start, and how far apart its middle is looked for. */
internal const val LINE_FIND_DISTANCE = 144.0
internal const val LINE_FIND_STEP = 2.0
/** How far to either side its walls are looked for, farther it's in the open. */
internal const val LINE_FIND_HALF = 8.0
/** How many spots in a row without its middle, e.g. a layer across it, blocked or open, it's followed on past. */
internal const val LINE_FIND_MISSES = 12
/** How many middles back the way on is followed from, a block in the way at one only turns it a little. */
internal const val LINE_FIND_TRACK = 6
/** How long it must be followed for, and how far apart the middles a slope is taken between. */
internal const val LINE_FIND_MIN_LENGTH = 48.0
internal const val LINE_FIND_PAIR_GAP = 8.0
/** How far off the line fitted a middle may be and still be on it, and how many must be. */
internal const val LINE_FIND_FIT = 0.75
internal const val LINE_FIND_INLIERS = 0.6
/** How close in degrees a step or the ray from 0,0 must be to the way measured to fly it instead. */
internal const val LINE_FIND_DEGREES = 1.0
/** How far from 0,0 the ray from it through you is taken, closer its way is too rough. */
internal const val LINE_FIND_ORIGIN_MIN = 256.0
/** How close to a step the ray from 0,0 through you is taken for that step, the highway dug along it, see [findLineDir]. */
internal const val LINE_FIND_ORIGIN_ON_STEP = 0.05
/**
 * How far above the floor carpets are looked for, below their 0.0625. Bounced on like the floor, but coming down
 * right before one its edge stops you dead, and the server sees that sooner than the client. So they're dodged.
 */
internal const val CARPET_RAY = 0.01
/**
 * How long a stretch of carpets with no lane past it is glided over, holding the height. Just above them that
 * costs little speed, walking a lot more.
 */
internal const val MAX_CARPET_HOP_LENGTH = 48.0
/**
 * How far above the floor your feet glide over carpets at least, like over a block: not skimming them for long,
 * only up over them from a bounce and down again.
 */
internal const val CARPET_GLIDE_FEET = 0.2
/**
 * Bouncing on over carpets, how many ticks later holding the height would still get over them. Once it wouldn't
 * it's held, only the bounce that would come down on them is stretched, not glided along low over them.
 */
internal const val CARPET_HOLD_DELAY = 2
/**
 * How many ticks before carpets at most the height is held for them, about a bounce. Farther the glide to them is
 * too long to tell, held from there it's glided along low all the way, slowing down.
 */
internal const val MAX_CARPET_HOLD_TICKS = 16
/** Never holding the height, for [canHop] bouncing on. */
internal const val NO_HOLD = Int.MAX_VALUE
/**
 * How far ahead carpets on the way are planned to be glided over, about a bounce, still in time to hold the height
 * for them. Farther the bounces may well not come down on them.
 */
internal const val CARPET_HOP_AHEAD = 24.0
/** Carpets closer together than this many ticks of flight are glided over as one. */
internal const val CARPET_GAP_TICKS = 4
/** How often the lane with the fewest carpets is looked for again for the same carpet ahead. */
internal const val CARPET_LANE_TICKS = 10
/** How high carpets are, and so a layer of them to bounce on. */
internal const val CARPET_HEIGHT = 1.0 / 16
/** How far on carpets have to lie with no gap and nothing on top, to bounce on like a layer. */
internal const val CARPET_LAYER_MIN = 8.0
/** How far ahead of a spot to take off from there may be no carpets, for room to get up to speed. */
internal const val CARPET_TAKEOFF_CLEAR = 24.0
/** How far on through carpets a spot to take off from is looked for, they may cover a long way. */
internal const val CARPET_WALK_REACH = 96.0
/** How far past carpets a lane dodging them has to be free of anything else in the way. */
internal const val CARPET_CLEAR = 6.0
/** How far past where you bumped into something to look at what it is. */
internal const val WALL_PROBE = 0.1
/** How far below you a floor is looked for when starting in the air. */
internal const val MAX_FLOOR_DROP = 4.0
