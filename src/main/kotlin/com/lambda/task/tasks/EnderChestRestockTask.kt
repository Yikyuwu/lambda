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

package com.lambda.task.tasks

import baritone.api.pathing.goals.GoalBlock
import com.lambda.Lambda.LOG
import com.lambda.config.blocks.BreakConfig
import com.lambda.context.Automated
import com.lambda.context.SafeContext
import com.lambda.interaction.handlers.BaritoneHandler
import com.lambda.interaction.material.container.containers.EnderChestContainer
import com.lambda.module.modules.player.ToolSaver
import com.lambda.task.Task
import com.lambda.task.tasks.BuildTask.Companion.breakAndCollectBlock
import com.lambda.task.tasks.PlaceContainerTask.Companion.isSafeContainerSpot
import com.lambda.task.tasks.StationTask.Companion.moveIntoHotbar
import com.lambda.task.tasks.StationTask.Companion.stopSafely
import com.lambda.util.BlockUtils.blockState
import com.lambda.util.CommunicationUtils.info
import com.lambda.util.CommunicationUtils.warn
import com.lambda.util.EnchantmentUtils.getEnchantment
import com.lambda.util.extension.containerSlots
import com.lambda.util.extension.containerStacks
import com.lambda.util.extension.playerSlots
import com.lambda.util.item.ItemStackUtils.durabilityFraction
import com.lambda.util.player.SlotUtils.hotbarAndInventorySlots
import com.lambda.util.player.SlotUtils.hotbarSlots
import com.lambda.util.world.closestEntity
import net.minecraft.block.EnderChestBlock
import net.minecraft.enchantment.Enchantments
import net.minecraft.entity.ItemEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.tag.ItemTags
import net.minecraft.screen.ScreenHandler
import net.minecraft.util.math.BlockPos

/**
 * Restocks from the ender chest: places a carried one, takes what [wants] asks for and ender chests while fewer
 * than [MIN_ENDER_CHESTS] would be left, then breaks it again. With a Silk Touch pickaxe it comes back, else it drops obsidian.
 * It never places the last ender chest, since without one the ender chest can't be reached anymore.
 */
class EnderChestRestockTask @Ta5kBuilder constructor(
    private val wants: List<Want>,
    private val swapDelay: Int,
    automated: Automated,
    holdWhile: () -> Boolean = { false },
    /** Runs before the ender chest is broken, e.g. swapping worn pickaxes from a shulker just taken, since breaking it needs one. */
    private val beforeBreaking: SafeContext.() -> Task<*>? = { null },
) : StationTask<EnderChestRestockTask.Phase>("Restocking from the ender chest", Phase.Opening, automated, holdWhile) {
    override val moveDelay get() = swapDelay

    /**
     * Takes the stacks with the most [amount], e.g. the fullest shulker, at most [maxStacks], until [isMet].
     * [isLow] is what makes it worth a trip.
     */
    class Want(
        val name: String,
        val maxStacks: Int,
        val amount: (ItemStack) -> Int,
        val isLow: SafeContext.() -> Boolean,
        val isMet: SafeContext.() -> Boolean,
    ) {
        fun matches(stack: ItemStack) = amount(stack) > 0
    }

    enum class Phase(override val description: String, override val timeoutTicks: Int?) : StationPhase {
        Placing("placing the ender chest", 400),
        Opening("opening the ender chest", 200),
        Taking("taking items", 200),
        // What runs then, e.g. a tool restock or mending, times out by itself.
        BeforeBreaking("using what was taken", null),
        MakingRoom("making room for the ender chest", 100),
        Breaking("breaking the ender chest", 400),
        PickingUp("picking up the ender chest", 300),
    }

    private var placedPos: BlockPos? = null
    private var chestsNearby = emptySet<BlockPos>()
    private var chestsCarried = 0
    private var chestsBeforeBreak = 0
    private var silkTouch = false
    private var openAttempts = 0
    private var collectAttempts = 0
    private var ranBeforeBreaking = false
    private var hotbarCooldown = 0
    /** Why [beforeBreaking] failed. The ender chest is still broken first, so it isn't left behind. */
    private var beforeBreakingFailure: String? = null
    private val taken = mutableMapOf<Want, Int>()

    /** Taken first, so the trip that uses up an ender chest also brings new ones. */
    private val enderChests = Want(
        "ender chests", 1, { if (isEnderChest(it)) it.count else 0 },
        isLow = { false },
        isMet = { carriedCount(::isEnderChest) + (if (silkTouch) 1 else 0) >= MIN_ENDER_CHESTS },
    )
    private val allWants = listOf(enderChests) + wants

    override fun SafeContext.resume() {
        if (stopping) return if (placedPos != null) collect() else finish()
        val pos = placedPos ?: return place()
        open(pos, Phase.Taking, extraTicks = TICKS_PER_STACK * allWants.sumOf { it.maxStacks })
    }

    override fun SafeContext.onTick() {
        when (phase) {
            Phase.Taking -> containerStep(onDone = { collect() }) { take(it) }
            Phase.MakingRoom -> makeRoom()
            Phase.PickingUp -> pickUp()
            Phase.Placing, Phase.Opening, Phase.BeforeBreaking, Phase.Breaking -> {}
        }
    }


    override fun SafeContext.packUp() = when {
        phase == Phase.Placing || phase in COLLECT_PHASES -> true
        // What runs then, e.g. a tool restock or mending, picks its shulker back up first, then the ender chest is broken.
        phase == Phase.BeforeBreaking -> {
            currentStep.stopSafely()
            // Nothing of it was placed, so it just ended.
            if (currentStep?.isActive != true) {
                cancelStep()
                collect()
            }
            true
        }
        placedPos == null -> false
        else -> {
            cancelStep()
            collect()
            true
        }
    }

    private fun SafeContext.place() {
        chestsCarried = carriedCount(::isEnderChest)
        if (chestsCarried < MIN_ENDER_CHESTS) return failure("Only $chestsCarried ender chest(s) left, the last one stays")
        val chest = player.hotbarAndInventorySlots.first { isEnderChest(it.stack) }
        silkTouch = hasSilkTouchPickaxe()
        chestsNearby = blocksAround { it is EnderChestBlock }
        info("Restocking ${wants.filter { it.isLow(this) }.joinToString { it.name }} from the ender chest")
        LOG.info("[EnderChestRestock] $chestsCarried ender chests, silk touch $silkTouch, " +
            allWants.joinToString { "${it.name}: low ${it.isLow(this)} met ${it.isMet(this)}" })
        enter(Phase.Placing)
        runStep(PlaceContainerTask(chest, this@EnderChestRestockTask) { isSafeContainerSpot(it) }) { pos ->
            placedPos = pos
            resume()
        }
    }

    /** Takes a stack for the first want that isn't met yet, throwing away junk to make room for it. */
    private fun SafeContext.take(handler: ScreenHandler): Boolean {
        // What's in it decides whether the next trip is worth it.
        EnderChestContainer.update(handler.containerStacks.map { it.copy() })
        val open = allWants.filter { (taken[it] ?: 0) < it.maxStacks && !it.isMet(this) }
        val (want, slot) = open.firstNotNullOfOrNull { want ->
            handler.containerSlots.filter { want.matches(it.stack) }.maxByOrNull { want.amount(it.stack) }?.let { want to it }
        } ?: return false

        if (handler.playerSlots.none { it.stack.isEmpty }) {
            val junk = handler.playerSlots.firstOrNull { slot ->
                slot.stack.item in inventoryConfig.disposables && open.none { it.matches(slot.stack) }
            }
            if (junk == null) {
                warn("Inventory is full and has nothing to throw away, can't take ${want.name}")
                return false
            }
            submit { throwStack(junk.id) }
            return true
        }
        val stack = slot.stack.copy()
        if (submit { quickMove(slot.id) }) {
            taken[want] = (taken[want] ?: 0) + 1
            LOG.info("[EnderChestRestock] took ${stack.count}x ${stack.name.string} (${want.amount(stack)}) for ${want.name}")
        }
        return true
    }

    private fun SafeContext.collect() {
        closeContainer()
        if (!ranBeforeBreaking && !stopping) {
            ranBeforeBreaking = true
            beforeBreaking()?.let { task ->
                enter(Phase.BeforeBreaking)
                return runStep(task) { enter(Phase.MakingRoom) }
            }
        }
        enter(Phase.MakingRoom)
    }

    /** With Silk Touch the ender chest comes back, which needs a free slot. Else it only drops obsidian. */
    private fun SafeContext.makeRoom() {
        if (!settled || placedPos?.let { canBreakSafely(it) } == false) return
        // The tool restock may have swapped a worn Silk Touch pickaxe away.
        silkTouch = hasSilkTouchPickaxe()
        // The break only uses what's in the hotbar, and with Silk Touch nothing else would do.
        if (hotbarCooldown > 0) {
            hotbarCooldown--
            return
        }
        if (silkTouch && player.hotbarSlots.none { isUsableSilkTouchPickaxe(it.stack) }) {
            if (moveIntoHotbar(this@EnderChestRestockTask, ::isUsableSilkTouchPickaxe)) {
                hotbarCooldown = HOTBAR_COOLDOWN_TICKS
                return
            }
            // E.g. a hotbar full of tools, breaking it with Silk Touch would wait forever.
            warn("No room in the hotbar for the Silk Touch pickaxe, breaking the ender chest without it")
            silkTouch = false
        }
        if (!silkTouch || hasFreeSlotOrThrow { "Left the ender chest placed at ${placedPos?.toShortString()}" }) breakChest()
    }

    private fun SafeContext.breakChest() {
        val pos = placedPos ?: return finish()
        chestsBeforeBreak = carriedCount(::isEnderChest)
        if (blockState(pos).block !is EnderChestBlock) return enter(Phase.PickingUp)
        enter(Phase.Breaking)
        LOG.info("[EnderChestRestock] breaking it, silk touch $silkTouch, hotbar " +
            player.hotbarSlots.filter { it.stack.isIn(ItemTags.PICKAXES) }.joinToString { "${it.stack.name.string} ${(it.stack.durabilityFraction * 100).toInt()}%" })
        val breaker = if (silkTouch) withSilkTouch() else this@EnderChestRestockTask
        runStep(breaker.breakAndCollectBlock(pos)) { enter(Phase.PickingUp) }
    }

    /** Waits for the ender chest to be back in the inventory and walks to it if it is still on the ground. */
    private fun SafeContext.pickUp() {
        val pos = placedPos ?: return finish()
        if (!silkTouch || carriedCount(::isEnderChest) > chestsBeforeBreak) return done()
        if (blockState(pos).block is EnderChestBlock) return phaseFailed("the ender chest is still placed")
        if (!settled) return

        // None means it didn't drop itself, e.g. something else broke it.
        val drop = closestEntity<ItemEntity>(PICKUP_RADIUS, pos) { isEnderChest(it.stack) } ?: return done()
        if (!hasFreeSlotOrThrow { "The ender chest is on the ground at ${drop.blockPos.toShortString()}" }) return
        if (!BaritoneHandler.isActive) BaritoneHandler.setGoalAndPath(GoalBlock(drop.blockPos))
    }

    private fun SafeContext.done() {
        LOG.info("[EnderChestRestock] ender chests $chestsBeforeBreak before breaking, ${carriedCount(::isEnderChest)} now")
        BaritoneHandler.cancel()
        placedPos = null
        finish()
    }

    override fun SafeContext.stepFailed(reason: String?) {
        cancelStep()

        when (phase) {
            Phase.Placing -> {
                // It may have failed after the ender chest was down. Only a new one counts, and only if one left the inventory.
                val chestLeft = carriedCount(::isEnderChest) < chestsCarried
                placedPos = if (chestLeft) (blocksAround { it is EnderChestBlock } - chestsNearby).minByOrNull { it.getSquaredDistance(player.pos) } else null
                when {
                    placedPos != null -> resume()
                    chestLeft -> failure("An ender chest left the inventory but isn't placed anywhere nearby ($reason)")
                    else -> {
                        warn("Couldn't place the ender chest ($reason). It needs a free, safe spot on the ground at your feet level within 4 blocks")
                        finish()
                    }
                }
            }
            Phase.Opening -> if (++openAttempts < MAX_OPEN_ATTEMPTS) resume() else {
                warn("Couldn't open the ender chest ($reason), breaking it")
                collect()
            }
            Phase.Taking -> {
                warn("Failed while taking items ($reason)")
                collect()
            }
            Phase.BeforeBreaking -> {
                beforeBreakingFailure = reason
                enter(Phase.MakingRoom)
            }
            Phase.MakingRoom, Phase.Breaking, Phase.PickingUp ->
                if (++collectAttempts < MAX_COLLECT_ATTEMPTS) collect()
                else failure("Couldn't break the ender chest at ${placedPos?.toShortString()} and pick it back up (${phase.description}, $reason)")
        }
    }

    private fun SafeContext.finish() {
        beforeBreakingFailure?.let { return failure("Failed while using what was taken from the ender chest ($it)") }
        val took = allWants.mapNotNull { want -> taken[want]?.let { "$it stack(s) for ${want.name}" } }
        if (took.isEmpty()) warn("Found nothing to take in the ender chest")
        else info("Took ${took.joinToString()} from the ender chest")
        success()
    }

    companion object {
        /** Ender chests always kept, so the last one is never placed. */
        const val MIN_ENDER_CHESTS = 2

        fun isEnderChest(stack: ItemStack) = stack.item == Items.ENDER_CHEST

        fun SafeContext.hasSilkTouchPickaxe() = player.hotbarAndInventorySlots.any { isUsableSilkTouchPickaxe(it.stack) }

        /**
         * One ToolSaver lets into the hotbar, else breaking with Silk Touch would wait for it forever.
         * Never one about to break, also without ToolSaver.
         */
        fun isUsableSilkTouchPickaxe(stack: ItemStack) =
            stack.isIn(ItemTags.PICKAXES) && stack.getEnchantment(Enchantments.SILK_TOUCH) > 0 &&
                stack.durabilityFraction >= MIN_SILK_DURABILITY && !ToolSaver.keepsOut(stack)

        /**
         * These settings, but breaking only with Silk Touch so an ender chest drops itself. It holds the Silk Touch pickaxe
         * the whole break, a swap to it only at the end like the default mode does could still mine it with another pickaxe.
         */
        fun Automated.withSilkTouch(): Automated {
            val base = this
            return object : Automated by base {
                override val breakConfig = object : BreakConfig by base.breakConfig {
                    override val forceSilkTouch = true
                    override val swapMode = BreakConfig.SwapMode.Constant
                }
            }
        }
        private const val TICKS_PER_STACK = 10
        private const val MIN_SILK_DURABILITY = 0.01f
        /** Until the swap shows in the inventory, so it isn't swapped back out. */
        private const val HOTBAR_COOLDOWN_TICKS = 10
        private const val PICKUP_RADIUS = 8.0
        private const val MAX_OPEN_ATTEMPTS = 2
        private const val MAX_COLLECT_ATTEMPTS = 3
        private val COLLECT_PHASES = setOf(Phase.MakingRoom, Phase.Breaking, Phase.PickingUp)
    }
}
