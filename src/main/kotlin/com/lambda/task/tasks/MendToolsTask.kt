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
import com.lambda.context.Automated
import com.lambda.context.SafeContext
import com.lambda.event.events.PacketEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.interaction.handlers.BaritoneHandler
import com.lambda.interaction.managers.hotbar.HotbarRequest
import com.lambda.interaction.managers.rotating.IRotationRequest.Companion.rotationRequest
import com.lambda.interaction.managers.rotating.RotationSettle
import com.lambda.threading.runSafeAutomated
import com.lambda.util.CommunicationUtils.info
import com.lambda.util.EntityUtils.isInReach
import com.lambda.util.TickTimer
import com.lambda.util.combat.CombatUtils.attackEntity
import com.lambda.util.extension.containerSlots
import com.lambda.util.extension.playerSlots
import com.lambda.util.item.ItemStackUtils.durabilityFraction
import com.lambda.util.player.RotationUtils.lookAtEntity
import com.lambda.util.player.SlotUtils.hotbarAndInventorySlots
import com.lambda.util.player.SlotUtils.hotbarStacks
import com.lambda.util.world.closestEntity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.entity.mob.Monster
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.screen.ScreenHandler
import net.minecraft.util.math.BlockPos

/** Settings for [MendToolsTask], read every tick. */
interface MendConfig {
    val leaveInChest: Int
    val maxTools: Int
    val onlyWithMobs: Boolean
    val rotate: Boolean
    val minHitInterval: Int
    /** Seconds without mending progress before giving up. */
    val stallTimeout: Int
    val moveDelay: Int
    /** Waits while this is true, e.g. while eating. */
    val waiting: Boolean

    fun isTool(stack: ItemStack): Boolean
    fun canMend(stack: ItemStack): Boolean
    fun isMended(stack: ItemStack): Boolean
    /** Should have sweeping edge and no fire aspect. */
    fun isSword(stack: ItemStack): Boolean
}

/**
 * Mends tools at an XP farm where mobs are swept by hitting an armor stand, with the tool in the offhand.
 * Dumps mended tools into [repairedChest] and takes damaged ones from [damagedChest] until it runs out.
 * Hits the closest armor stand in reach.
 */
class MendToolsTask @Ta5kBuilder constructor(
    private val damagedChest: BlockPos,
    private val repairedChest: BlockPos,
    private val standPos: BlockPos,
    private val config: MendConfig,
    automated: Automated,
) : StationTask<MendToolsTask.Phase>("Mending tools", Phase.Opening, automated) {
    override val moveDelay get() = config.moveDelay

    enum class Phase(override val description: String, override val timeoutTicks: Int?) : StationPhase {
        Opening("opening chest", 200),
        Dumping("dumping mended tools", 200),
        Taking("taking damaged tools", 200),
        Mending("mending", null),
    }

    private var leftInChest = 0
    private var walkTicks = 0
    private var missingTicks = 0
    @Volatile private var lastPacket = System.currentTimeMillis()
    /** What was in the offhand before mending, e.g. a totem, to put back when done. */
    private var originalOffhand: Item? = null
    private var offhandRemembered = false
    private val settle = RotationSettle()
    private var lastDamage = -1
    private var mended = 0
    private val hitTimer = TickTimer()
    private val stallTimer = TickTimer()

    private fun isDamagedTool(stack: ItemStack) = config.isTool(stack) && config.canMend(stack) && !config.isMended(stack)
    private fun isMendedTool(stack: ItemStack) = config.isTool(stack) && config.isMended(stack)
    private fun shouldTake(stack: ItemStack) = isDamagedTool(stack) || isMendedTool(stack)

    init {
        listen<PacketEvent.Receive.Pre>(alwaysListen = true) { lastPacket = System.currentTimeMillis() }
        // Also when stopping early, so e.g. a totem isn't left in the inventory.
        onFailure { restoreOffhand() }
    }

    override fun SafeContext.onTick() {
        // Time spent waiting isn't the farm stalling.
        if (config.waiting) {
            stallTimer.reset()
            return
        }
        when (phase) {
            Phase.Dumping -> containerStep { dump(it) }
            Phase.Taking -> containerStep(onDone = { finishTaking() }) { take(it) }
            Phase.Mending -> mend()
            Phase.Opening -> {}
        }
    }

    override fun SafeContext.resume() {
        if (!offhandRemembered) {
            offhandRemembered = true
            originalOffhand = player.offHandStack.takeUnless { it.isEmpty || config.isTool(it) }?.item
        }
        val extraTicks = (player.hotbarAndInventorySlots.size + 1) * moveDelay
        when {
            carried(::isDamagedTool) > 0 -> startMending()
            carried(::isMendedTool) > 0 -> open(repairedChest, Phase.Dumping, extraTicks)
            else -> open(damagedChest, Phase.Taking, extraTicks)
        }
    }

    /** Puts one mended tool, offhand first, into the repaired chest. */
    private fun SafeContext.dump(handler: ScreenHandler): Boolean {
        val tool = handler.playerSlots.firstOrNull { isMendedTool(it.stack) }
        val free = handler.containerSlots.firstOrNull { it.stack.isEmpty }
        when {
            !isMendedTool(player.offHandStack) && tool == null -> return false
            free == null -> return failAction("The repaired tools chest is full")
            isMendedTool(player.offHandStack) -> submit { swap(free.id, OFFHAND_BUTTON) }
            tool != null -> submit { quickMove(tool.id) }
        }
        return true
    }

    /** Takes one tool, damaged ones first. Undamaged ones get sorted into the repaired chest too. */
    private fun SafeContext.take(handler: ScreenHandler): Boolean {
        val inChest = handler.containerSlots.filter { shouldTake(it.stack) }.sortedByDescending { isDamagedTool(it.stack) }
        val freeSlots = handler.playerSlots.count { it.stack.isEmpty }
        leftInChest = inChest.size
        if (minOf(inChest.size - config.leaveInChest, freeSlots, config.maxTools - carried(::shouldTake)) <= 0) return false
        submit { quickMove(inChest.first().id) }
        return true
    }

    private fun SafeContext.finishTaking() {
        if (carried(::isDamagedTool) > 0) return startMending()
        // Only undamaged ones this trip, they go straight to the repaired chest.
        if (carried(::isMendedTool) > 0) return resumeAfterClose()
        when {
            leftInChest > config.leaveInChest && player.hotbarAndInventorySlots.none { it.stack.isEmpty } ->
                return failure("No free inventory slot to take tools into")
            leftInChest > 0 -> info("Left $leftInChest tool(s) in the chest to keep the farm running")
            else -> info("No tools left to mend")
        }
        info("Mended $mended tool(s)")
        restoreOffhand()
        success()
    }

    /** Swaps what was in the offhand before back in, and a tool still there out. */
    private fun SafeContext.restoreOffhand() {
        val item = originalOffhand ?: return
        if (player.offHandStack.item == item) return
        val slot = player.hotbarAndInventorySlots.firstOrNull { it.stack.item == item } ?: return
        submit { swap(slot.id, OFFHAND_BUTTON) }
    }

    private fun SafeContext.startMending() {
        phaseDone()
        settle.reset()
        restartStallWatch()
        info("Mending ${carried(::isDamagedTool)} tool(s)")
        enter(Phase.Mending)
    }

    private fun SafeContext.mend() {
        hitTimer.tick()
        stallTimer.tick()
        // A rotation that settled before a pause isn't trusted after it.
        if (!aim()) settle.reset()
    }

    /** Works towards the next hit and returns whether it got as far as aiming at the armor stand. */
    private fun SafeContext.aim(): Boolean {
        if (player.currentScreenHandler != player.playerScreenHandler || !settled) return false

        // Such farms only run while you stand on their pressure plate.
        if (player.blockPos != standPos) {
            when {
                !standPos.isWithinDistance(player.pos, MAX_STAND_DISTANCE) ->
                    failure("The stand position ${standPos.toShortString()} is far away. Did the farm move?")
                ++walkTicks > STAND_TIMEOUT_TICKS -> failure("Couldn't get to the stand position ${standPos.toShortString()}")
                !BaritoneHandler.isActive -> BaritoneHandler.setGoalAndPath(GoalBlock(standPos))
            }
            stallTimer.reset()
            return false
        }
        walkTicks = 0
        if (BaritoneHandler.isActive) BaritoneHandler.cancel()

        val offhand = player.offHandStack
        if (!isDamagedTool(offhand)) {
            swapInNextTool(offhand)
            return false
        }

        if (offhand.damage != lastDamage) {
            lastDamage = offhand.damage
            stallTimer.reset()
        }
        if (stallTimer.hasSurpassed(config.stallTimeout * 20)) {
            failure("${offhand.name.string} wasn't mended for ${config.stallTimeout}s. Is the farm still running?")
            return false
        }

        val sword = player.hotbarStacks.indexOfFirst(config::isSword)
        if (sword == -1) {
            failure("No sweeping edge sword without fire aspect in the hotbar")
            return false
        }
        val swordStack = player.hotbarStacks[sword]
        if (swordStack.isDamageable && swordStack.durabilityFraction < MIN_SWORD_DURABILITY) {
            failure("${swordStack.name.string} is about to break")
            return false
        }
        if (!HotbarRequest(sword, this@MendToolsTask, nowOrNothing = false).submit().done) return false

        val reach = buildConfig.entityReach
        val target = closestEntity<ArmorStandEntity>(reach + ARMOR_STAND_SEARCH_EXTRA) {
            !it.isMarker && it.isInReach(player.eyePos, reach)
        }
        // Right after joining entities take a moment to show up.
        if (target == null) {
            if (++missingTicks > MISSING_TIMEOUT_TICKS)
                failure("No armor stand in reach")
            return false
        }
        missingTicks = 0
        if (config.onlyWithMobs && !hasMobsToSweep(target)) return false
        // Hits the server gets late land close together, and two within 5 of its ticks break the armor stand.
        if (System.currentTimeMillis() - lastPacket > LAG_MS) return false

        val hit = runSafeAutomated { lookAtEntity(target) } ?: return false
        if (config.rotate && !settle.ready(rotationRequest { rotation(hit.rotation) }.submit().done)) return true
        if (player.getAttackCooldownProgress(0.5f) < 1f || !hitTimer.hasSurpassed(config.minHitInterval)) return true

        attackEntity(target, interactConfig.swing)
        hitTimer.reset()
        return true
    }

    /** Swaps the next damaged tool into the offhand, or resumes once there is none. */
    private fun SafeContext.swapInNextTool(offhand: ItemStack) {
        val damaged = player.hotbarAndInventorySlots.firstOrNull { isDamagedTool(it.stack) }
        if (damaged != null && !submit { swap(damaged.id, OFFHAND_BUTTON) }) return
        if (isMendedTool(offhand)) mended++
        if (damaged == null) return resume()
        restartStallWatch()
    }

    private fun restartStallWatch() {
        lastDamage = -1
        stallTimer.reset()
    }

    /** The sweep hits mobs within a block of the armor stand and 3 blocks of the player. */
    private fun SafeContext.hasMobsToSweep(target: ArmorStandEntity) =
        world.getEntitiesByClass(LivingEntity::class.java, target.boundingBox.expand(1.0, 0.25, 1.0)) {
            it is Monster && it.isAlive && it.squaredDistanceTo(player) < SWEEP_RANGE_SQ
        }.isNotEmpty()

    companion object {
        private const val STAND_TIMEOUT_TICKS = 1200
        private const val MAX_STAND_DISTANCE = 16.0
        private const val MISSING_TIMEOUT_TICKS = 100
        private const val ARMOR_STAND_SEARCH_EXTRA = 2.0
        private const val LAG_MS = 500L
        private const val MIN_SWORD_DURABILITY = 0.05f
        private const val SWEEP_RANGE_SQ = 9.0
    }
}
