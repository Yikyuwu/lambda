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

package com.lambda.module.modules.world

import baritone.api.pathing.goals.GoalBlock
import com.lambda.Lambda.LOG
import com.lambda.config.automation.AutomationConfig.Companion.grimPacing
import com.lambda.config.automation.AutomationConfig.Companion.setDefaultAutomationConfig
import com.lambda.config.blocks.EatConfig
import com.lambda.config.blocks.EatConfig.Companion.isHungerFood
import com.lambda.config.blocks.HotbarConfig
import com.lambda.config.Group
import com.lambda.config.editSetting
import com.lambda.config.editTypedSettings
import com.lambda.config.withEdits
import com.lambda.context.SafeContext
import com.lambda.event.events.MovementEvent
import com.lambda.event.events.TickEvent
import com.lambda.event.events.WorldEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.event.listener.UnsafeListener.Companion.listenUnsafe
import com.lambda.interaction.construction.blueprint.Blueprint.Companion.emptyStructure
import com.lambda.interaction.construction.blueprint.PropagatingBlueprint
import com.lambda.interaction.construction.blueprint.PropagatingBlueprint.Companion.propagatingBlueprint
import com.lambda.interaction.construction.verify.TargetState
import com.lambda.interaction.handlers.BaritoneHandler
import com.lambda.interaction.handlers.SpleefGuard
import com.lambda.interaction.handlers.breaking.BrokenBlockHandler
import com.lambda.interaction.managers.inventory.InventoryRequest.Companion.inventoryRequest
import com.lambda.interaction.managers.rotating.IRotationRequest.Companion.rotationRequest
import com.lambda.interaction.managers.rotating.Rotation
import com.lambda.interaction.managers.rotating.Rotation.Companion.dist
import com.lambda.interaction.managers.rotating.RotationManager
import com.lambda.interaction.material.container.containers.EnderChestContainer
import com.lambda.module.Module
import com.lambda.module.modules.combat.AutoArmor
import com.lambda.module.modules.combat.AutoTotem
import com.lambda.module.modules.combat.autodisconnect.AutoDisconnect
import com.lambda.module.modules.player.AutoEat
import com.lambda.module.tag.ModuleTag
import com.lambda.task.RootTask.run
import com.lambda.task.Task
import com.lambda.task.tasks.BuildTask
import com.lambda.task.tasks.BuildTask.Companion.build
import com.lambda.task.tasks.EnderChestRestockTask
import com.lambda.task.tasks.EnderChestRestockTask.Companion.MIN_ENDER_CHESTS
import com.lambda.task.tasks.EnderChestRestockTask.Want
import com.lambda.task.tasks.StationTask.Companion.carriedCount
import com.lambda.task.tasks.StationTask.Companion.waitsForPlayer
import com.lambda.task.tasks.StationTask.Companion.stopSafely
import com.lambda.task.tasks.TakeFromShulkerTask
import com.lambda.util.BlockUtils.blockState
import com.lambda.util.BlockUtils.hasFluid
import com.lambda.util.BlockUtils.hasSolidGround
import com.lambda.util.BlockUtils.isPassable
import com.lambda.util.BlockUtils.offset
import com.lambda.util.item.ItemStackUtils.shulkerBoxContents
import com.lambda.util.item.ItemUtils
import com.lambda.util.CommunicationUtils.info
import com.lambda.util.CommunicationUtils.logError
import com.lambda.util.CommunicationUtils.warn
import com.lambda.util.Describable
import com.lambda.util.NamedEnum
import com.lambda.util.TickTimer
import com.lambda.util.extension.Structure
import com.lambda.util.extension.move
import com.lambda.util.extension.moveY
import com.lambda.util.extension.playerSlots
import com.lambda.util.math.MathUtils.floorToInt
import com.lambda.util.math.rotateClockwise
import com.lambda.util.player.MovementUtils.octant
import com.lambda.util.player.MovementUtils.sneaking
import com.lambda.util.player.RotationUtils.lookAt
import com.lambda.util.world.StructureUtils.generateDirectionalTube
import net.minecraft.block.Block
import net.minecraft.block.Blocks
import net.minecraft.entity.mob.EndermanEntity
import net.minecraft.item.BlockItem
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.tag.BlockTags
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.EightWayDirection
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.atan2

object HighwayTools : Module(
    name = "HighwayTools",
    description = "Auto highway builder",
    tag = ModuleTag.WORLD,
) {
    private val height by setting("Height", 4, 2..10, 1)
    private val width by setting("Width", 2, 1..30, 1)
    private val pavement by setting("Pavement", Material.None, "Material for the pavement")
    private val rimHeight by setting("Pavement Rim Height", 1, 0..6, 1) { pavement != Material.None }
    private val rim by setting("Rim Height", 0, 0..6, 1, "Rims of solid blocks like netherrack on both sides of the hole without a pavement, kept where they are and placed where missing, like walls. Only with a width of 3 or more, so a lane is left between them") { pavement == Material.None }
    /** Rims on the hole's own edge columns, which leave no lane to walk in narrower than 3. */
    private val hasRims get() = pavement == Material.None && rim > 0 && width >= 3
    private val cornerBlock by setting("Corner", Corner.None, "Include corner blocks in the highway") { pavement != Material.None }
    private val pavementMaterial by setting("Pavement Material", Blocks.OBSIDIAN, "Material to build the highway with") { pavement == Material.Block }
    private val floor by setting("Floor", Material.Solid, "Material for the floor")
    private val floorMaterial by setting("Floor Material", Blocks.NETHERRACK, "Material to build the floor with") { floor == Material.Block }
    private val rightWall by setting("Right Wall", Material.None, "Build the right wall")
    private val leftWall by setting("Left Wall", Material.Solid, "Build the left wall")
    private val wallMaterial by setting("Wall Material", Blocks.NETHERRACK, "Material to build the walls with") { rightWall == Material.Block || leftWall == Material.Block }
    private val ceiling by setting("Ceiling", Material.Solid, "Material for the ceiling")
    private val ceilingMaterial by setting("Ceiling Material", Blocks.OBSIDIAN, "Material to build the ceiling with") { ceiling == Material.Block }
    private val replaceableSolids by setting("Replaceable Solids", setOf(Blocks.MAGMA_BLOCK, Blocks.SOUL_SAND))
    private val sealBorder by setting("Seal Border", true, "Replace lava behind the world border next to the highway with junk blocks like netherrack, so it can't flow in")
    private val sealGap by setting("Seal Gap", 2, 0..3, 1, "How many blocks the highway may be away from the border. The gaps in between are filled first to place against", " blocks") { sealBorder }
    private val distance by setting("Distance", -1, -1..1000000, 1, "Distance to build the highway/tunnel (negative for infinite)")
    private val sliceSize by setting("Slice Size", 5, 1..5, 1, "Number of slices to build at once")
    private val keepMoving by setting("Keep Moving", true, "Walk on while the slices ahead are done instead of stopping after each one. Needs Pathing")
    private val unstuckStep by setting("Unstuck Step", true, "Step forward crouched when the build makes no progress, e.g. after being knocked out of reach")
    private val unstuckDelay by setting("Unstuck Delay", 5, 2..60, 1, "Time without progress before stepping", " s") { unstuckStep }
    private val stepDistance by setting("Step Distance", 2, 1..3, 1, "How far to step, as far as it's safe to stand", " blocks") { unstuckStep }
    private val throwJunk by setting("Throw Junk", true, "Throw away picked up items you don't need, back along the highway so they're not walked into again")
    private val throwOut by setting(
        "Throw Out",
        setOf(
            Items.GLOWSTONE, Items.GLOWSTONE_DUST, Items.BONE, Items.NETHER_WART_BLOCK, Items.WARPED_WART_BLOCK,
            Items.WEEPING_VINES, Items.TWISTING_VINES, Items.SOUL_SAND, Items.SOUL_SOIL, Items.MAGMA_BLOCK,
        ),
        description = "The items to throw away",
    ) { throwJunk }
    private val provokeEndermen by setting("Provoke Endermen", false, "Anger endermen in the way, then back off so they teleport away. Off while Kill Mobs is on")
    private val backOffDistance by setting("Back Off Distance", 3, 1..6, 1, "How far to back off from a provoked enderman", " blocks") { provokeEndermen }
    @Group(ENDER_CHEST_GROUP) private val restockPickaxes by setting("Restock Pickaxe Shulkers", true, "Takes a shulker with fresh tools from the ender chest once no carried one has any. Needs Restock Tools") { inventoryConfig.accessEnderChest }
    @Group(FOOD_GROUP) private val restockFood by setting("Restock Food", true, "Takes food out of a carried shulker once it runs low, or from the ender chest if no shulker has any")
    @Group(FOOD_GROUP) private val minFood by setting("Min Food", 16, 1..64, 1, "Food left at which more is taken, up to a stack") { restockFood }
    @Group(ENDER_CHEST_GROUP) private val restockBlocks by setting("Restock Building Blocks", true, "Takes the blocks the highway is built with from the ender chest once they run low") { inventoryConfig.accessEnderChest }
    @Group(ENDER_CHEST_GROUP) private val minBlocks by setting("Min Blocks", 16, 1..64, 1, "Blocks left at which more are taken, up to two stacks") { inventoryConfig.accessEnderChest && restockBlocks }
    @Group(ENDER_CHEST_GROUP) private val restockRetryDelay by setting("Restock Retry Delay", 60, 10..600, 10, "Wait after a trip to the ender chest before the next one", " s") { inventoryConfig.accessEnderChest }
    @Group(TOOL_RESTOCK_GROUP) private val toolRestock by configBlock(HighwayToolRestock(this))
    @Group(AURA_GROUP) private val aura by configBlock(HighwayAura(this))
    @Group(BUTTONS_GROUP) private val buttons by configBlock(HighwayButtons(this))
    private val safetyWarning by setting("Safety Warning", true, "Warns on enable when AutoArmor, AutoDisconnect or AutoTotem is off")
    private val debugLog by setting("Debug Log", false, "Writes the build's progress and what it waits on to the game log every few seconds")

    private var octant = EightWayDirection.NORTH
    private var startPos = BlockPos.ORIGIN
    private var runningTask: Task<*>? = null
    private var blueprint: PropagatingBlueprint? = null
    /** The slices being built, by their index along the highway. */
    private val slices = ArrayDeque<Int>()
    private var nextSlice = 0
    /** Set while only the done front slices are swapped for new ones, not all of them after the build finished them. */
    private var rolling = false
    /** Whether the blueprint places buttons, null without them, to build it anew once that changes, see [rollSlices]. */
    private var blueprintPlacing: Boolean? = null
    /** Who paused the build with [pauseFor]. Only they resume it. */
    private var pausedBy: Any? = null

    private var lastActions = 0
    private var lastPos = Vec3d.ZERO
    private val stuckTimer = TickTimer()
    private var warnedCantStep = false
    private var mode: Mode = Mode.Idle
    private var cruiseTarget: BlockPos? = null
    private var waitTicks = 0
    private var throwCooldown = 0
    private var cruiseGoalTicks = 0
    private var cruiseRepaths = 0
    private var cruiseCooldown = 0
    private var debugTicks = 0
    /** Endermen provoked lately and when, so one that stays isn't stared at over and over. */
    private val provoked = WeakHashMap<EndermanEntity, Long>()
    private var dimension: RegistryKey<World>? = null

    /** Holds the pause while the player is in another dimension than the highway. */
    private object OtherDimension
    private var enderChestTask: Task<*>? = null
    private var foodTask: Task<*>? = null
    private var foodRetryTicks = 0
    /** Turned on in the queue, so the highway starts once on the server. */
    private var waitingForServer = false
    private var ticksInWorld = 0
    /** Shortly after joining, the inventory may not be there yet and restocks would think everything is missing. */
    private val inventoryLoaded get() = ticksInWorld >= JOIN_GRACE_TICKS
    private var restockRetryTicks = 0
    /** Whether a trip since the start saw what's in the ender chest. Before that it may have been changed by hand. */
    private var sawEnderChest = false
    /** What's needed but wasn't in the ender chest, warned about once. */
    private val warnedMissing = mutableSetOf<String>()
    /** A trip to the ender chest failed, so none until the highway is started again. */
    private var restockFailed = false

    /** What the module does with the player besides building. The build is paused while it's not [Idle]. */
    private sealed interface Mode {
        data object Idle : Mode
        class Walking(val target: BlockPos, var ticksLeft: Int) : Mode
        class Staring(val enderman: EndermanEntity, var ticksLeft: Int) : Mode
    }

    private const val STARE_TIMEOUT_TICKS = 100
    private const val PROVOKE_RANGE = 16.0
    private const val WALK_START_TICKS = 40
    private const val TICKS_PER_BLOCK = 20
    private const val CRUISE_LOOKAHEAD = 6
    private const val RETARGET_DISTANCE = 1.5
    private const val MAX_WAIT_FACTOR = 3
    private const val CRUISE_STALL_TICKS = 20
    private const val MAX_CRUISE_REPATHS = 3
    private const val CRUISE_COOLDOWN_TICKS = 100
    private const val PROVOKE_COOLDOWN_MS = 30_000L
    private const val THROW_PITCH = -20.0
    private const val THROW_COOLDOWN_TICKS = 5
    private const val DEBUG_INTERVAL_TICKS = 100
    private const val SHULKER_SWAP_DELAY = 5
    private const val ENDER_CHEST_GROUP = "Ender Chest"
    private const val FOOD_GROUP = "Food"
    private const val TOOL_RESTOCK_GROUP = "Tool Restock"
    private const val AURA_GROUP = "Aura"
    private const val BUTTONS_GROUP = "Buttons"
    private const val FOOD_TARGET = 64
    private const val FOOD_RETRY_TICKS = 600
    private const val JOIN_GRACE_TICKS = 40
    private const val BLOCKS_TARGET = 128
    private const val THROW_LOOK_TOLERANCE = 5.0
    private val UNSAFE_PASSABLE = listOf(Blocks.COBWEB, Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW)

    enum class Material(
        override val displayName: String,
        override val description: String
    ): NamedEnum, Describable {
        None("None", "Wont pave the highway"),
        Solid("Solid", "Paves the highway with solid blocks. Will use any full block available. Useful if you only want to make sure that the highway is encased."),
        Block("Block", "Paves the highway with a specific block. Will use the block you specified in the settings"),
    }

	@Suppress("unused")
    enum class Corner(
        override val displayName: String,
        override val description: String
    ): NamedEnum, Describable {
        None("None", "Wont fill the corner block of the highway pavement below the rims."),
        Solid("Solid", "Fills the corner block of the highway pavement below the rims with solid blocks."),
    }

    init {
		setDefaultAutomationConfig()
			.withEdits {
				buildConfig.apply {
					editTypedSettings(::pathing, ::spleefEntities) { defaultValue(true) }
					::blockReach.editSetting { defaultValue(3.99) }
				}
				grimPacing(breaksPerTick = 8, interactionsPerTick = 2)
				breakConfig.apply {
					editTypedSettings(::efficientOnly, ::suitableToolsOnly) { defaultValue(false) }
				}
				inventoryConfig::accessShulkerBoxes.editSetting { defaultValue(true) }
				hotbarConfig::swapMode.editSetting { defaultValue(HotbarConfig.SwapMode.Permanent) }
				eatConfig.apply {
					::minFoodLevel.editSetting { defaultValue(8) }
					::saturated.editSetting { defaultValue(EatConfig.Saturation.EatUntilFull) }
					::minDamage.editSetting { defaultValue(12) }
				}
            }

        onEnable {
            restockFailed = false
            restockRetryTicks = 0
            foodRetryTicks = 0
            sawEnderChest = false
            warnedMissing.clear()
            toolRestock.failed = false
            buttons.failed = false
            // The tool restock is what takes the pickaxes out of the shulker, so without it the shulker isn't fetched.
            if (inventoryConfig.accessEnderChest && restockPickaxes && !toolRestock.enabled)
                warn("Restock Tools is off, so no pickaxe shulkers get taken from the ender chest")
            // Digging AFK, these keep you alive or log you out before you die.
            val off = listOf(AutoArmor, AutoDisconnect, AutoTotem).filterNot { it.isEnabled }
            if (safetyWarning && off.isNotEmpty())
                warn("${off.joinToString { it.name }} ${if (off.size == 1) "is" else "are"} off, turn ${if (off.size == 1) "it" else "them"} on to stay safe while digging AFK")
            // In the queue the inventory isn't there yet, so restocks would think everything is missing.
            waitingForServer = player.isSpectator
            // Already in the world, so the inventory is there.
            ticksInWorld = if (waitingForServer) 0 else JOIN_GRACE_TICKS
            if (waitingForServer) info("In the queue, HighwayTools starts once you're on the server")
            else start()
        }
        onDisable {
            aura.stopFighting()
            stopRestocks()
            if (!isRestockingFood) endFoodRestock()
            if (!isRestockingFromEnderChest) enderChestTask = null
            runningTask?.cancel()
            runningTask = null
            blueprint = null
            slices.clear()
            pausedBy = null
            mode = Mode.Idle
            cruiseTarget = null
            provoked.clear()
            BaritoneHandler.cancel()
        }

        // Also while the build is paused, which they do themselves.
        listen<TickEvent.Pre> {
            if (player.isSpectator) ticksInWorld = 0 else if (ticksInWorld < JOIN_GRACE_TICKS) ticksInWorld++
            if (waitingForServer && ticksInWorld >= JOIN_GRACE_TICKS) {
                waitingForServer = false
                start()
            }
            watchForPlayers()
            with(aura) { tick() }
            // Not e.g. in the queue, where a shulker can't be placed.
            if (isBuilding && world.registryKey == dimension && inventoryLoaded) {
                with(toolRestock) { tick() }
                with(buttons) { tick() }
            }
        }

        listen<TickEvent.Pre> {
            val task = runningTask as? BuildTask ?: return@listen
            if (debugLog && ++debugTicks >= DEBUG_INTERVAL_TICKS) {
                debugTicks = 0
                logStatus(task)
            }
            // E.g. in the queue or after dying, wait to be back in the highway's world.
            if (world.registryKey != dimension) {
                // A walk or stare can't finish here, and the enderman would keep the old world in memory.
                if (mode != Mode.Idle) {
                    BaritoneHandler.cancel()
                    stopAndResume()
                }
                stopCruising()
                pauseFor(OtherDimension)
                return@listen
            }
            resume(OtherDimension)
            // Ended without its callbacks, e.g. cancelled.
            if (enderChestTask?.isActive == false) endEnderChestTrip()
            if (foodTask?.isActive == false) endFoodRestock()
            when (val current = mode) {
                is Mode.Walking -> return@listen walk(current)
                is Mode.Staring -> return@listen stare(current)
                Mode.Idle -> {}
            }

            val running = task.state == Task.State.Running
            if (running) rollSlices(task)
            if (running && throwJunk) throwOutJunk()
            if (running && provokeEndermen && !aura.enabled) blockingEnderman()?.let { return@listen provoke(it) }
            if (running && keepMoving && buildConfig.pathing) cruise() else stopCruising()
            if (running && inventoryLoaded && restockFood) restockFoodFromShulker()
            if (running && inventoryLoaded && inventoryConfig.accessEnderChest) restockFromEnderChest()
            // Not once a missing ender chest stopped it.
            if (running && inventoryLoaded && isEnabled) stopIfOutOfFood()

            // Time paused by e.g. a tool restock doesn't count.
            val progressed = task.actions != lastActions || player.pos.squaredDistanceTo(lastPos) > 0.01
            if (!unstuckStep || !running || progressed) {
                lastActions = task.actions
                lastPos = player.pos
                stuckTimer.reset()
                waitTicks = 0
                warnedCantStep = false
                return@listen
            }
            // Pending actions or a mob in the way get some time, but not forever.
            if ((task.isWorking || SpleefGuard.recentBlockers.isNotEmpty()) && ++waitTicks < unstuckDelay * 20 * MAX_WAIT_FACTOR) {
                stuckTimer.reset()
                return@listen
            }

            stuckTimer.tick()
            if (stuckTimer.hasSurpassed(unstuckDelay * 20)) stepForward()
        }

        listen<MovementEvent.InputUpdate> {
            if (mode is Mode.Walking) it.input.sneaking = true
            aura.crouch(it.input)
        }

        // A walk or stare that paused the build can't finish any more.
        listenUnsafe<WorldEvent.Leave> {
            resume(this@HighwayTools)
            mode = Mode.Idle
            cruiseTarget = null
            provoked.clear()
            aura.clear()
            ticksInWorld = 0
        }
    }

    val isBuilding get() = runningTask?.isActive == true

    /** Starts the highway where the player is. */
    private fun SafeContext.start() {
        dimension = world.registryKey
        octant = player.octant
        startPos = player.blockPos
        with(buttons) { onStart() }
        keepInsideBorder()
        buildHighway()
        // One still picking up its shulker or the ender chest from the last stop holds the new build until it's done.
        when {
            isRestockingTools -> pauseFor(toolRestock)
            isRestockingButtons -> pauseFor(buttons)
            isRestockingFood -> pauseFor(FoodRestock)
            isRestockingFromEnderChest -> pauseFor(EnderChestTrip)
        }
    }

    /** Whether a mob is being fought, so others wait instead of rotating and swapping against it. */
    val isFighting get() = aura.isFighting
    /** Whether the build should leave calm endermen in the way to [HighwayAura] instead of spleefing them. */
    val killsCalmEndermen get() = isEnabled && aura.killsCalmEndermen

    /** Whether a shulker is out to restock tools. */
    val isRestockingTools get() = toolRestock.isRestocking

    /** Whether a shulker of logs is out to craft buttons. */
    val isRestockingButtons get() = buttons.isRestocking

    /** Whether the ender chest is out to restock. */
    val isRestockingFromEnderChest get() = enderChestTask?.isActive == true

    /** Whether a shulker is out to take food from. */
    val isRestockingFood get() = foodTask?.isActive == true

    /** Whether a shulker or the ender chest is out, or about to be, for a restock. */
    val isUsingContainer get() = isRestockingTools || isRestockingButtons || isRestockingFood || isRestockingFromEnderChest

    /** Whether a container is out or a mob is being fought, so nothing else starts meanwhile. */
    val isBusy get() = isUsingContainer || isFighting

    /** A restock that's out while another player comes near picks its shulker or the ender chest back up. */
    private fun SafeContext.watchForPlayers() {
        if (!isUsingContainer || !waitsForPlayer()) return
        stopRestocks()
    }

    /** A restock first picks its shulker or the ender chest back up, so stopping never leaves one behind. */
    private fun stopRestocks() {
        toolRestock.stop()
        buttons.stop()
        foodTask.stopSafely()
        enderChestTask.stopSafely()
    }

    /** Takes food out of a carried shulker before it runs out, so there's some once hungry. */
    private fun SafeContext.restockFoodFromShulker() {
        if (foodRetryTicks > 0 && --foodRetryTicks > 0) return
        if (isBusy || AutoEat.isEating) return
        if (carriedCount(::isFood, offhand = true) > minFood || !carriesFoodShulker()) return
        if (waitsForPlayer() || !pauseFor(FoodRestock)) return

        val before = carriedCount(::isFood, offhand = true)
        foodTask = TakeFromShulkerTask(
            what = "food",
            isWanted = ::isFood,
            target = FOOD_TARGET,
            swapDelay = SHULKER_SWAP_DELAY,
            automated = this@HighwayTools,
            // The aura goes first, the restock carries on once the mob is dead.
            holdWhile = { isFighting },
        ).onCompletion {
            endFoodRestock()
            // E.g. the shulker couldn't be placed anywhere, so not right again.
            if (carriedCount(::isFood, offhand = true) <= before) foodRetryTicks = FOOD_RETRY_TICKS
        }.onFailure {
            logError("Taking food from a shulker failed: ${it.message}. Stopped HighwayTools so nothing gets left behind")
            endFoodRestock()
            disable()
        }.run()
    }

    /** With no food carried, in a shulker or in the ender chest, the digger would starve, so the build stops. */
    private fun SafeContext.stopIfOutOfFood() {
        // Stopping would cancel what has a shulker or the ender chest out, leaving it placed.
        if (!eatConfig.eatOnHunger || isUsingContainer) return
        if (carriedCount(::isFood, offhand = true) > 0 || carriesFoodShulker()) return
        // Unknown until a trip saw it, and that trip comes first.
        val enderChestMayHave = restockFood && inventoryConfig.accessEnderChest && !restockFailed &&
            carriedCount(EnderChestRestockTask::isEnderChest, offhand = true) >= MIN_ENDER_CHESTS &&
            (!sawEnderChest || EnderChestContainer.stacks.any { foodIn(it) > 0 })
        if (enderChestMayHave) return
        logError("No food left in the inventory, a shulker or the ender chest. Stopped HighwayTools, add food and start it again")
        disable()
    }

    /**
     * Whether [stack] is a shulker with nothing left to use, only worn tools or nothing, put back in the ender chest on a
     * trip. Any food, experience bottles, fresh tools or other items left in it and it's kept.
     */
    private fun isUsedUpShulker(stack: ItemStack) =
        stack.item in ItemUtils.shulkerBoxes && stack.shulkerBoxContents.all { it.isEmpty || toolRestock.isWornTool(it) }

    private fun SafeContext.carriesFoodShulker() = player.inventory.mainStacks.any { it.item in ItemUtils.shulkerBoxes && foodIn(it) > 0 }

    /** How much food [stack] is, or a shulker holds. */
    private fun foodIn(stack: ItemStack) = when {
        isFood(stack) -> stack.count
        stack.item in ItemUtils.shulkerBoxes -> stack.shulkerBoxContents.sumOf { if (isFood(it)) it.count else 0 }
        else -> 0
    }

    private fun endFoodRestock() {
        foodTask = null
        resume(FoodRestock)
    }

    private object FoodRestock

    /** Food that's eaten when hungry, by this module's eat settings, so not e.g. golden apples. */
    private fun isFood(stack: ItemStack) = eatConfig.isHungerFood(stack.item)

    /** Takes what runs low from the ender chest. The last ender chest is never placed, the build stops instead. */
    private fun SafeContext.restockFromEnderChest() {
        if (restockRetryTicks > 0 && --restockRetryTicks > 0) return
        if (restockFailed || isBusy || AutoEat.isEating) return

        val wants = restockWants()
        // Unknown until the ender chest was opened once, so the first trip finds out.
        val stored = EnderChestContainer.stacks
        val (low, missing) = wants.filter { it.isLow(this) }
            .partition { want -> !sawEnderChest || stored.isEmpty() || stored.any(want::matches) }
        missing.filter { warnedMissing.add(it.name) }.forEach {
            warn("Need ${it.name}, but the ender chest had none last time. Start HighwayTools again after putting some in")
        }
        warnedMissing.retainAll(missing.map { it.name }.toSet())
        if (low.isEmpty() || waitsForPlayer()) return

        val chests = carriedCount(EnderChestRestockTask::isEnderChest, offhand = true)
        if (chests < MIN_ENDER_CHESTS) {
            logError("Need ${low.joinToString { it.name }} from the ender chest, but only $chests ender chest(s) are left and the last one stays. Add ender chests and start HighwayTools again")
            return disable()
        }
        if (!pauseFor(EnderChestTrip)) return

        enderChestTask = EnderChestRestockTask(
            wants = wants,
            swapDelay = SHULKER_SWAP_DELAY,
            automated = this@HighwayTools,
            // The aura goes first, the trip carries on once the mob is dead.
            holdWhile = { isFighting },
            // The pickaxes first, breaking the ender chest needs one. A shulker of logs just taken is crafted from, then put back.
            beforeBreaking = { with(toolRestock) { restockNow() } ?: with(buttons) { craftNow() } },
            putBack = { isUsedUpShulker(it) || buttons.putsBack(it) },
        ).onCompletion {
            endEnderChestTrip()
            restockRetryTicks = restockRetryDelay * 20
            sawEnderChest = true
        }.onFailure {
            logError("Restocking from the ender chest failed: ${it.message}. Stopped HighwayTools so nothing gets left behind")
            restockFailed = true
            endEnderChestTrip()
            disable()
        }.run()
    }

    private fun SafeContext.restockWants() = buildList {
        if (restockPickaxes && toolRestock.enabled) add(Want(
            "a pickaxe shulker", 1, toolRestock::freshTools,
            isLow = { carriedCount(toolRestock::isToolShulker, offhand = true) == 0 },
            isMet = { carriedCount(toolRestock::isToolShulker, offhand = true) > 0 },
        ))
        // Food in a carried shulker is taken from there first, so a shulker with food is enough.
        if (restockFood) add(Want(
            "food", 2, ::foodIn,
            isLow = { carriedCount(::isFood, offhand = true) <= minFood && !carriesFoodShulker() },
            isMet = { carriedCount(::isFood, offhand = true) >= FOOD_TARGET || carriesFoodShulker() },
        ))
        if (restockBlocks) add(Want(
            "building blocks", 2, { if (isBuildMaterial(it)) it.count else 0 },
            isLow = { carriedCount(::isBuildMaterial, offhand = true) <= minBlocks },
            isMet = { carriedCount(::isBuildMaterial, offhand = true) >= BLOCKS_TARGET },
        ))
        with(buttons) { enderChestWant() }?.let(::add)
    }

    private fun endEnderChestTrip() {
        enderChestTask = null
        buttons.tripEnded()
        resume(EnderChestTrip)
    }

    private object EnderChestTrip

    /** Whether [stack] is something the highway gets built with: a chosen block, or the junk blocks of solid parts. */
    fun isBuildMaterial(stack: ItemStack): Boolean {
        val block = (stack.item as? BlockItem)?.block ?: return false
        val junk = stack.item in inventoryConfig.disposables
        val parts = listOf(pavement to pavementMaterial, floor to floorMaterial, rightWall to wallMaterial, leftWall to wallMaterial, ceiling to ceilingMaterial)
        return (sealBorder && junk) || parts.any { (material, chosen) ->
            when (material) {
                Material.None -> false
                Material.Solid -> junk && block !in replaceableSolids
                Material.Block -> block == chosen
            }
        }
    }

    /** Whether [pauseFor] would work for [owner], without pausing yet. */
    fun canPauseFor(owner: Any) = pausedBy === owner || pausedBy == null && runningTask?.state == Task.State.Running

    /** Pauses the build for [owner]. False when it isn't running, e.g. someone else paused it. */
    fun pauseFor(owner: Any): Boolean {
        if (pausedBy === owner) return true
        val task = runningTask?.takeIf { pausedBy == null && it.state == Task.State.Running } ?: return false
        task.pause()
        pausedBy = owner
        // Also a path the build set, so nothing walks while paused.
        cruiseTarget = null
        BaritoneHandler.cancel()
        return true
    }

    /** Resumes the build if [owner] paused it. */
    fun resume(owner: Any) {
        if (pausedBy !== owner) return
        pausedBy = null
        runningTask?.activate()
    }

    /**
     * Walks on to the furthest done slice of the next few while the one you're on is done too.
     * The build breaks blocks ahead as they come in reach, which moves the target on.
     */
    private fun SafeContext.cruise() {
        if (cruiseCooldown > 0) {
            cruiseCooldown--
            return stopCruising()
        }
        val target = nextCruiseTarget()
        val current = cruiseTarget
        // Further on the way: keep walking to the current one until it's close, so Baritone doesn't restart for every slice.
        if (target != null && current != null && BaritoneHandler.isActive &&
            highwayIndex(target) > highwayIndex(current) &&
            player.pos.squaredDistanceTo(Vec3d.ofBottomCenter(current)) > RETARGET_DISTANCE * RETARGET_DISTANCE
        ) return
        if (target != cruiseTarget) {
            // Not walking on to a target that isn't done any more, e.g. after gravel fell in.
            if (target != null) BaritoneHandler.setGoalAndPath(GoalBlock(target))
            else if (player.blockPos != cruiseTarget) BaritoneHandler.cancel()
            cruiseTarget = target
            cruiseGoalTicks = 0
            cruiseRepaths = 0
            return
        }
        // Baritone stopped short, retry a few times, then let the build path.
        if (target == null || player.blockPos == target || BaritoneHandler.isActive || ++cruiseGoalTicks < CRUISE_STALL_TICKS) return
        cruiseGoalTicks = 0
        if (++cruiseRepaths > MAX_CRUISE_REPATHS) {
            cruiseCooldown = CRUISE_COOLDOWN_TICKS
            return stopCruising()
        }
        BaritoneHandler.setGoalAndPath(GoalBlock(target))
    }

    private fun SafeContext.nextCruiseTarget(): BlockPos? {
        val slice = generateSlice()
        val button = with(buttons) { floorButton() }
        val index = highwayIndex(player.blockPos)
        fun done(i: Int) = (distance < 0 || i < distance) && sliceDone(slice, i, button)

        if (!done(index)) return null
        return safeSteps(forward = true, CRUISE_LOOKAHEAD)
            .withIndex()
            .takeWhile { (i, _) -> done(index + i + 1) }
            .lastOrNull()?.value
    }

    private fun stopCruising() {
        if (cruiseTarget == null) return
        cruiseTarget = null
        BaritoneHandler.cancel()
    }

    private fun highwayPos(index: Int): BlockPos = startPos.offset(octant, index)

    private fun highwayIndex(pos: BlockPos): Int {
        val offset = pos.subtract(startPos)
        return Math.floorDiv(
            offset.x * octant.offsetX + offset.z * octant.offsetZ,
            octant.offsetX * octant.offsetX + octant.offsetZ * octant.offsetZ,
        )
    }

    /** Throws out a stack of [throwOut], after looking back along the highway. Not while a container is open. */
    private fun SafeContext.throwOutJunk() {
        if (throwCooldown > 0 && --throwCooldown > 0) return
        if (player.currentScreenHandler != player.playerScreenHandler) return
        val junk = player.playerScreenHandler.playerSlots.firstOrNull { !it.stack.isEmpty && it.stack.item in throwOut } ?: return
        val back = octant.rotateClockwise(4)
        val look = Rotation(Math.toDegrees(atan2(-back.offsetX.toDouble(), back.offsetZ.toDouble())), THROW_PITCH)
        rotationRequest { rotation(look) }.submit()
        if (RotationManager.serverRotation dist look > THROW_LOOK_TOLERANCE) return
        inventoryRequest { throwStack(junk.id) }.submit()
        throwCooldown = THROW_COOLDOWN_TICKS
    }

    private fun SafeContext.logStatus(task: BuildTask) {
        val paused = pausedBy?.let { it::class.simpleName } ?: "nothing"
        LOG.info(
            "[HighwayTools] at ${player.blockPos.toShortString()}, slice ${highwayIndex(player.blockPos)}, " +
                "building ${slices.firstOrNull()}..${slices.lastOrNull()}, ${task.actions} actions, " +
                "${if (task.isWorking) "waiting on the server" else "nothing pending"}, ${task.state}, " +
                "${BrokenBlockHandler.failedBreaks} breaks failed, " +
                "mode ${mode::class.simpleName}, paused by $paused, cruising ${cruiseTarget != null}, " +
                "hp ${player.health.toInt()} food ${player.hungerManager.foodLevel}, carried food " +
                (player.inventory.mainStacks + player.offHandStack).filter(::isFood).groupBy { it.item.name.string }
                    .map { (name, stacks) -> "${stacks.sumOf { it.count }} $name" } +
                ", food shulker ${carriesFoodShulker()}, next ${task.nextResult?.name ?: "none"}"
        )
    }

    /** What the build is stuck on, for the chat. */
    private fun waitingOn() = (runningTask as? BuildTask)?.nextResult?.let { " (waiting on ${it.name})" } ?: ""

    private fun SafeContext.stepForward() {
        stuckTimer.reset()
        val steps = safeSteps(forward = true, stepDistance)
        if (steps.isEmpty()) {
            if (!warnedCantStep) warn("No progress for ${unstuckDelay}s${waitingOn()} and can't step forward")
            warnedCantStep = true
            return
        }

        info("No progress for ${unstuckDelay}s${waitingOn()}, stepping ${steps.size} block(s) forward")
        walkAlong(steps)
    }

    /** The closest enderman in the way that staring can anger: in sight, not angry yet and not provoked lately. */
    private fun SafeContext.blockingEnderman(): EndermanEntity? {
        val now = System.currentTimeMillis()
        provoked.entries.removeIf { now - it.value > PROVOKE_COOLDOWN_MS }
        return SpleefGuard.recentBlockers.asSequence()
            .filterIsInstance<EndermanEntity>()
            .filter { it.isAlive && !it.isAngry && it !in provoked && inProvokeRange(it) && player.canSee(it) }
            .minByOrNull { it.squaredDistanceTo(player) }
    }

    private fun SafeContext.inProvokeRange(enderman: EndermanEntity) =
        enderman.squaredDistanceTo(player) <= PROVOKE_RANGE * PROVOKE_RANGE

    private fun SafeContext.provoke(enderman: EndermanEntity) {
        if (!pauseFor(this@HighwayTools)) return
        info("Provoking ${enderman.name.string}, then backing off so it teleports away")
        provoked[enderman] = System.currentTimeMillis()
        val staring = Mode.Staring(enderman, STARE_TIMEOUT_TICKS)
        mode = staring
        stare(staring)
    }

    /** An angry enderman teleports away once it's more than 4 blocks off and not looked at. */
    private fun SafeContext.stare(staring: Mode.Staring) {
        val enderman = staring.enderman
        when {
            !enderman.isAlive || !inProvokeRange(enderman) -> stopAndResume()
            enderman.isAngry -> backOff(enderman)
            --staring.ticksLeft <= 0 -> {
                warn("${enderman.name.string} didn't get angry")
                stopAndResume()
            }
            else -> rotationRequest { rotation(lookAt(enderman.eyePos)) }.submit()
        }
    }

    /** Walks along the highway away from [enderman]. */
    private fun SafeContext.backOff(enderman: EndermanEntity) {
        val toEnderman = enderman.pos.subtract(player.pos)
        val ahead = toEnderman.x * octant.offsetX + toEnderman.z * octant.offsetZ > 0
        val steps = safeSteps(forward = !ahead, backOffDistance)
        if (steps.isEmpty()) {
            warn("Can't back off from the enderman, nowhere safe to go")
            return stopAndResume()
        }
        walkAlong(steps)
    }

    /** The blocks up to [distance] ahead or behind, until the first one that isn't safe to stand on. */
    private fun SafeContext.safeSteps(forward: Boolean, distance: Int): List<BlockPos> {
        val sign = if (forward) 1 else -1
        return (1..distance)
            .map { player.blockPos.offset(octant, it * sign) }
            .takeWhile { canStandAt(it) }
    }

    private fun walkAlong(steps: List<BlockPos>) = walkTo(steps.last(), WALK_START_TICKS + steps.size * TICKS_PER_BLOCK)

    /** Pauses the build and walks to [target]. [walk] resumes it once there. */
    private fun walkTo(target: BlockPos, timeoutTicks: Int) {
        if (!pauseFor(this)) return
        mode = Mode.Walking(target, timeoutTicks)
        BaritoneHandler.setGoalAndPath(GoalBlock(target))
    }

    private fun SafeContext.walk(walking: Mode.Walking) {
        if (player.blockPos != walking.target && --walking.ticksLeft > 0) return
        BaritoneHandler.cancel()
        stopAndResume()
    }

    private fun stopAndResume() {
        mode = Mode.Idle
        resume(this)
    }

    /** Room for the player, solid ground below and nothing to step into that hurts or holds you. */
    private fun SafeContext.canStandAt(pos: BlockPos): Boolean {
        fun empty(at: BlockPos) = isPassable(at) && blockState(at).let { state ->
            !state.hasFluid && !state.isIn(BlockTags.FIRE) && UNSAFE_PASSABLE.none { state.isOf(it) }
        }
        return empty(pos) && empty(pos.up()) && hasSolidGround(pos) && !blockState(pos.down()).isOf(Blocks.MAGMA_BLOCK)
    }

    private fun buildHighway() {
        slices.clear()
        nextSlice = 0
        val blueprint = propagatingBlueprint {
            // The build finished all of them, or [rollSlices] dropped the done ones.
            if (!rolling) slices.clear()
            while (slices.size < sliceSize && (distance < 0 || nextSlice < distance)) slices.addLast(nextSlice++)
            if (slices.isEmpty()) {
                this@HighwayTools.info("Highway built")
                disable()
                return@propagatingBlueprint emptyStructure()
            }
            val slice = generateSlice()
            val button = with(buttons) { floorButton() }
            blueprintPlacing = button?.placing
            withButtons(fitToBorder(slices.fold(emptyStructure()) { structure, i -> structure + slice.move(highwayPos(i)) }), button)
        }
        this.blueprint = blueprint
        runningTask = blueprint.build(collectDrops = buildConfig.collectDrops, lifeMaintenance = true)
            .apply { allowPathing = { cruiseTarget == null } }
            .run()
    }

    /**
     * Swaps the done slices at the front for new ones ahead, so the build keeps going instead of waiting for all of them.
     * Only once the server confirmed them, a block it puts back would be left behind otherwise. Once buttons run out or
     * are restocked the slices are built anew too, else the blueprint and [sliceDone] disagree and none counts as done.
     */
    private fun SafeContext.rollSlices(task: BuildTask) {
        val slice = generateSlice()
        val button = with(buttons) { floorButton() }
        var dropped = false
        while (slices.isNotEmpty() && sliceDone(slice, slices.first(), button) { task.isPending(it) }) {
            slices.removeFirst()
            dropped = true
        }
        if (!dropped && button?.placing == blueprintPlacing) return
        rolling = true
        blueprint?.next()
        rolling = false
    }

    private fun SafeContext.sliceDone(slice: Structure, index: Int, button: TargetState.FloorButton?, pending: (BlockPos) -> Boolean = { false }): Boolean {
        val structure = fitToBorder(slice.move(highwayPos(index)))
        return structure.all { (pos, state) ->
            val target = if (button != null && isButtonSpot(structure, pos, state)) button else state
            target.matches(blockState(pos), pos) && !pending(pos)
        }
    }

    /** Moves the highway sideways so nothing is to be dug behind the world border, e.g. when started right next to it. */
    private fun SafeContext.keepInsideBorder() {
        val slice = generateSlice()
        val orthogonal = octant.rotateClockwise(2)
        fun digsOutside(start: BlockPos) =
            slice.move(start).any { (pos, state) -> state.isEmpty() && !world.worldBorder.contains(pos) }
        if (!digsOutside(startPos)) return
        val shift = (1..width).flatMap { listOf(it, -it) }.firstOrNull { !digsOutside(startPos.offset(orthogonal, it)) } ?: return
        startPos = startPos.offset(orthogonal, shift)
        info("Moved the highway ${abs(shift)} block(s) away from the world border, nothing can be dug behind it")
    }

    /**
     * The [structure] without what's to be dug behind the world border, it can't be broken there.
     * With [sealBorder] lava there is replaced instead, and so is lava right behind the border next to it,
     * also when that's up to [sealGap] blocks away, then the gaps in between are filled to place against.
     * Each is placed against the block before it, so it waits while that one is missing.
     */
    private fun SafeContext.fitToBorder(structure: Structure): Structure {
        val border = world.worldBorder
        val (behind, fitted) = structure.entries
            .partition { (pos, state) -> state.isEmpty() && !border.contains(pos) }
            .let { (behind, rest) -> behind.map { it.key } to rest.associate { it.toPair() } }
        if (!sealBorder) return fitted
        val seal = behind.filterTo(mutableSetOf()) { blockState(it).hasFluid }
        fitted.keys.filter { border.contains(it) }.forEach { edge ->
            Direction.Type.HORIZONTAL.forEach { side ->
                val path = (1..sealGap + 1).map { edge.offset(side, it) }
                val outside = path.indexOfFirst { !border.contains(it) }
                val gap = if (outside < 0) return@forEach else path.take(outside + 1)
                if (gap.none { it in fitted } && blockState(gap.last()).hasFluid) seal += gap.filter { blockState(it).isReplaceable }
            }
        }
        return fitted + seal.associateWith { TargetState.Solid(emptySet()) }
    }

    /**
     * Buttons on the floor down the highway's middle line, every second block along it (diagonally every second
     * diagonal step), counted from the world grid so a restart keeps the same spots. Only where the slice has a floor
     * or pavement below.
     */
    private fun withButtons(structure: Structure, button: TargetState.FloorButton?): Structure =
        if (button == null) structure
        else structure.mapValues { (pos, state) -> if (isButtonSpot(structure, pos, state)) button else state }

    private fun isButtonSpot(structure: Structure, pos: BlockPos, state: TargetState) =
        state == TargetState.Air && pos.y == startPos.y && onMiddleLine(pos) &&
            Math.floorMod(if (octant.offsetX != 0) pos.x else pos.z, 2) == 0 && structure[pos.down()]?.isEmpty() == false

    /** Whether [pos] is on the line [startPos] goes along, a whole number of steps of the octant from it. */
    private fun onMiddleLine(pos: BlockPos) =
        (pos.x - startPos.x) * octant.offsetZ == (pos.z - startPos.z) * octant.offsetX

    private fun generateSlice(): Structure {
        val structure = mutableMapOf<BlockPos, TargetState>()
        val orthogonal = octant.rotateClockwise(2)
        val center = (width / 2.0).floorToInt()

        // Hole
        structure += generateDirectionalTube(
	        orthogonal,
	        width,
	        height,
	        -center,
	        0,
        ).associateWith { TargetState.Air }

        if (pavement != Material.None) {
            structure += generateDirectionalTube(
	            orthogonal,
	            width,
	            1,
	            -center,
	            0,
            ).associateWith { target(pavement, pavementMaterial) }

            // Left rim
            structure += generateDirectionalTube(
	            orthogonal,
	            1,
	            rimHeight,
	            -center + width - 1,
	            1,
            ).associateWith { target(pavement, pavementMaterial) }

            // Right rim
            structure += generateDirectionalTube(
	            orthogonal,
	            1,
	            rimHeight,
	            -center,
	            1,
            ).associateWith { target(pavement, pavementMaterial) }

            if (cornerBlock == Corner.None && rimHeight > 0) {
                // Support for the left rim
                structure += generateDirectionalTube(
	                orthogonal,
	                1,
	                1,
	                -center + width - 1,
	                0,
                ).associateWith { TargetState.Support(Direction.UP) }

                // Support for the right rim
                structure += generateDirectionalTube(
	                orthogonal,
	                1,
	                1,
	                -center,
	                0,
                ).associateWith { TargetState.Support(Direction.UP) }
            }
        }

        // Without a pavement the rims stand on the floor, the hole's sides up to their height.
        if (hasRims) listOf(-center + width - 1, -center).forEach { side ->
            structure += generateDirectionalTube(orthogonal, 1, rim, side, 0).associateWith { target(Material.Solid, wallMaterial) }
        }

        if (ceiling != Material.None) {
            structure += generateDirectionalTube(
	            orthogonal,
	            width,
	            1,
	            -center,
	            height,
            ).associateWith { target(ceiling, ceilingMaterial) }
        }

        // From the rim's top up, right beside it, the way it's always been built. Written as `rimHeight else 0 + 1` it
        // read as if a block more was meant, which would leave a gap beside the rim all along the way. Without a
        // pavement nothing moves down a block, so the rim's top is a block lower.
        val wallElevation = if (pavement != Material.None) rimHeight else if (hasRims) rim - 1 else 0
        if (rightWall != Material.None) {
            structure += wall(orthogonal, center, 1, wallElevation).associateWith { target(rightWall, wallMaterial) }
        }

        if (leftWall != Material.None) {
            structure += wall(orthogonal, center, -1, wallElevation).associateWith { target(leftWall, wallMaterial) }
        }

        if (floor != Material.None) {
            structure += generateDirectionalTube(
	            orthogonal,
	            width,
	            1,
	            -center,
	            -1,
            ).associateWith { target(floor, floorMaterial) }
        }

        val transformed = when {
            pavement != Material.None -> structure.moveY(-1)
            else -> structure
        }

        return transformed
    }

    /**
     * The blocks right next to the hole on its right ([side] 1) or left (-1), from [elevation] up to the ceiling.
     * Along a diagonal the hole's sides are staircases, and a tube a block further out left a gap all along them.
     */
    private fun wall(orthogonal: EightWayDirection, center: Int, side: Int, elevation: Int): Set<BlockPos> {
        val hole = generateDirectionalTube(orthogonal, width, 1, -center, 0)
        fun across(pos: BlockPos) = pos.x * orthogonal.offsetX + pos.z * orthogonal.offsetZ
        val edge = if (side < 0) hole.minOf(::across) else hole.maxOf(::across)
        return hole
            .flatMap { cell -> Direction.Type.HORIZONTAL.map { cell.offset(it) } }
            .filter { across(it) == edge + side }
            .flatMapTo(mutableSetOf()) { cell -> (elevation until height).map { cell.up(it) } }
    }

    private fun target(target: Material, material: Block) =
        when (target) {
            Material.Solid -> TargetState.Solid(replaceableSolids)
            Material.Block -> TargetState.Block(material)
            else -> throw IllegalStateException("Invalid material")
        }
}