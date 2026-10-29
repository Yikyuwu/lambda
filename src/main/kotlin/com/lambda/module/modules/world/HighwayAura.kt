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
import com.lambda.config.ConfigBlock
import com.lambda.context.Automated
import com.lambda.context.SafeContext
import com.lambda.interaction.handlers.BaritoneHandler
import com.lambda.interaction.handlers.SpleefGuard
import com.lambda.interaction.managers.hotbar.HotbarRequest
import com.lambda.interaction.managers.rotating.IRotationRequest.Companion.rotationRequest
import com.lambda.interaction.managers.rotating.RotationSettle
import com.lambda.interaction.material.StackSelection.Companion.select
import com.lambda.task.tasks.OpenContainerTask
import com.lambda.task.tasks.StationTask.Companion.isItemGrabber
import com.lambda.threading.runSafeAutomated
import com.lambda.util.CommunicationUtils.info
import com.lambda.util.CommunicationUtils.warn
import com.lambda.util.Describable
import com.lambda.util.EntityUtils.getClosestPointTo
import com.lambda.util.EntityUtils.isInReach
import com.lambda.util.NamedEnum
import com.lambda.util.combat.CombatUtils.attackEntity
import com.lambda.util.item.ItemStackUtils.attackDamage
import com.lambda.util.item.ItemStackUtils.attackSpeed
import com.lambda.util.item.ItemUtils
import com.lambda.util.player.MovementUtils.sneaking
import com.lambda.util.player.RotationUtils.lookAtEntity
import com.lambda.util.player.SlotUtils.hotbarStacks
import com.lambda.util.world.entitySearch
import net.minecraft.client.input.Input
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.mob.AbstractPiglinEntity
import net.minecraft.entity.mob.AbstractSkeletonEntity
import net.minecraft.entity.mob.EndermanEntity
import net.minecraft.entity.mob.GhastEntity
import net.minecraft.entity.mob.HoglinEntity
import net.minecraft.entity.mob.MagmaCubeEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.entity.mob.ZombifiedPiglinEntity
import net.minecraft.entity.passive.StriderEntity
import net.minecraft.item.Item
import net.minecraft.item.Items
import net.minecraft.util.math.BlockPos
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.ceil

/** Kills mobs the highway can't spleef out of the way, and hoglins and skeletons that come close. */
class HighwayAura(override val c: HighwayTools) : ConfigBlock, Automated by c {
    val enabled by c.setting("Kill Mobs", true, "Kill mobs the build can't spleef out of the way, and hoglins and skeletons that come close")
    private val angryEndermen by c.setting("Angry Endermen", true, "Kill endermen that attack you") { enabled }
    private val calmEndermen by c.setting("Calm Endermen", false, "Kill calm endermen in the way instead of spleefing them") { enabled }
    private val ghasts by c.setting("Ghasts", true, "Kill ghasts in the way") { enabled }
    private val striders by c.setting("Striders", true, "Kill striders in the way") { enabled }
    private val magmaCubes by c.setting("Magma Cubes", true, "Kill magma cubes in the way") { enabled }
    private val skeletons by c.setting("Skeletons", true, "Kill skeletons and wither skeletons that come close, they shoot or hit you anyway") { enabled }
    private val skeletonRange by c.setting("Skeleton Range", 2.0, 1.0..3.0, 0.25, "How close a skeleton has to come. Below reach, so one that backs off can't keep you chasing it", " blocks") { enabled && skeletons }
    private val hoglins by c.setting("Hoglins", true, "Kill hoglins that come close or are in the way") { enabled }
    private val angryPiglins by c.setting("Angry Piglins", true, "Kill piglins, piglin brutes and zombified piglins that attack you, e.g. after seeing a shulker or ender chest opened") { enabled }
    private val hoglinRange by c.setting("Hoglin Range", 3.0, 1.0..4.0, 0.25, "How close a hoglin has to come", " blocks") { enabled && hoglins }
    private val killGrabbers by c.setting("Kill Item Grabbers", true, "Kill mobs like baby piglins near a placed shulker or ender chest, they'd pick up its drop. Zombified piglins are waited out instead, since hitting one angers the others") { enabled }
    private val onlyInTheWay by c.setting("Only In The Way", true, "Only attack calm endermen, ghasts, striders and magma cubes blocking the build") { enabled }
    private val weapon by c.setting("Weapon", Weapon.NetheriteAxe, "Weapon in the hotbar to fight with") { enabled }
    private val anyWeapon by c.setting("Any Weapon", false, "Use the strongest sword or axe when that weapon isn't there") { enabled }
    private val rotate by c.setting("Rotate", true, "Rotate towards the mob") { enabled }
    private val crouch by c.setting("Crouch", true, "Crouch while fighting, so knockback can't push you off an edge") { enabled }
    private val pauseBuild by c.setting("Pause Build", true, "Pause the build while fighting") { enabled }

    enum class Weapon(
        override val displayName: String,
        override val description: String,
        val item: Item,
    ) : NamedEnum, Describable {
        NetheriteSword("Netherite Sword", "Fight with a netherite sword", Items.NETHERITE_SWORD),
        NetheriteAxe("Netherite Axe", "Fight with a netherite axe", Items.NETHERITE_AXE),
        DiamondSword("Diamond Sword", "Fight with a diamond sword", Items.DIAMOND_SWORD),
        DiamondAxe("Diamond Axe", "Fight with a diamond axe", Items.DIAMOND_AXE),
    }

    private var target: Entity? = null
    /** A mob to fight is there, but a container is open or an item in use first. */
    private var waiting = false
    /** Calm endermen in the way are left to the fight, so the build doesn't spleef them. */
    val killsCalmEndermen get() = enabled && calmEndermen
    /** Whether a mob is being fought or about to be, so others close their container and wait instead of acting against it. */
    val isFighting get() = target != null || waiting || approaching != null
    private val settle = RotationSettle()
    private var warnedNoWeapon = false
    /** Mobs given up on since they couldn't be hit, e.g. behind a wall, and when. */
    private val unhittable = WeakHashMap<Entity, Long>()
    private var missTicks = 0
    /** Ticks a mob waited on a container someone else keeps open. Only reset once no container is open anymore. */
    private var screenWaitTicks = 0
    private var heldSlot = -1
    private var heldTicks = 0
    /** A mob in the way out of reach being walked up to, see [approach]. */
    private var approaching: LivingEntity? = null
    private var approachGoal: BlockPos? = null
    private var approachTicks = 0
    private var approachStallTicks = 0
    private var approachClosest = Double.MAX_VALUE
    /** Mobs a walk up to failed for, and when, so the build's own unstuck gets its turn. */
    private val unapproachable = WeakHashMap<Entity, Long>()
    /** Mobs told about in the chat, once each. */
    private val announced: MutableSet<Entity> = Collections.newSetFromMap(WeakHashMap())

    fun SafeContext.tick() {
        val screenOpen = player.currentScreenHandler != player.playerScreenHandler
        if (!screenOpen) screenWaitTicks = 0
        if (!enabled) return stopFighting()
        var next = findTarget() ?: return approach(screenOpen)
        val pauses = pauseBuild && c.isBuilding
        // Busy with something else, e.g. a restock, so only fight what attacks you or would take a drop.
        if (pauses && !c.canPauseFor(this@HighwayAura)) next = findTarget(threatsOnly = true) ?: return stopFighting()
        // A restock closes its container for the fight and carries on once the mob is dead. Eating finishes first.
        if (player.isUsingItem || screenOpen) {
            stopFighting()
            // A container nobody closes for the fight, e.g. one opened by hand or by another module, would keep the
            // build paused for as long as the mob stays. It's left open and the fight given up until it's closed.
            if (screenOpen && ++screenWaitTicks > MAX_SCREEN_WAIT_TICKS && !player.isUsingItem) return
            waiting = true
            return
        }
        if (pauses) c.pauseFor(this@HighwayAura)
        // In reach now, the fight takes over.
        stopApproaching()
        if (next !== target) {
            settle.reset()
            missTicks = 0
        }
        target = next
        fight(next)
    }

    fun crouch(input: Input) {
        // Walking up always crouches, like the build's own walks, so it can't step off an edge.
        if ((crouch && target != null || approaching != null) && !OpenContainerTask.isOpening) input.sneaking = true
    }

    fun clear() {
        target = null
        stopApproaching()
        unhittable.clear()
        unapproachable.clear()
        announced.clear()
    }

    /** The mob being fought while it's in reach, so one knocked out of the way doesn't come back. Else the closest. */
    private fun SafeContext.findTarget(threatsOnly: Boolean = false): LivingEntity? {
        if (!angryEndermen && !calmEndermen && !ghasts && !striders && !magmaCubes && !skeletons && !hoglins && !angryPiglins && !killGrabbers) return null
        val now = System.currentTimeMillis()
        unhittable.entries.removeIf { now - it.value > UNHITTABLE_MS }
        val reach = buildConfig.entityReach
        // Reach goes to the hitbox, which for a ghast is far from where it stands.
        val inReach = entitySearch<LivingEntity>(reach + SEARCH_EXTRA) {
            it.isAlive && it !in unhittable && (!threatsOnly || it.isThreat || it.isGrabberTarget) && it.isInReach(player.eyePos, reach) && it.isKilled
        }
        inReach.firstOrNull { it === target }?.let { return it }

        return inReach
            .filter {
                // One walked up to isn't reported any more while the build is paused.
                val inTheWay = it in SpleefGuard.recentBlockers || it === approaching
                val allowed = !onlyInTheWay || inTheWay
                when {
                    it.isGrabberTarget || it.isAngryPiglin -> true
                    // Too wide to spleef, so the build would wait for one in the way forever.
                    it is HoglinEntity -> inTheWay || it.squaredDistanceTo(player) <= hoglinRange * hoglinRange
                    it is AbstractSkeletonEntity -> it.squaredDistanceTo(player) <= skeletonRange * skeletonRange
                    // An angry enderman is coming for you.
                    it is EndermanEntity -> it.isAngry || allowed
                    else -> allowed
                }
            }
            .minByOrNull { it.squaredDistanceTo(player) }
    }

    /** A kind of mob the settings have killed, wherever it is. */
    private val LivingEntity.isKilled get() = when {
        isGrabberTarget || isAngryPiglin -> true
        this is EndermanEntity -> if (isAngry) angryEndermen else calmEndermen
        this is GhastEntity -> ghasts
        this is StriderEntity -> striders
        this is MagmaCubeEntity -> magmaCubes
        this is AbstractSkeletonEntity -> skeletons
        this is HoglinEntity -> hoglins
        else -> false
    }

    /**
     * Blocks are placed further than mobs are hit, so one in the way can be out of reach, and a magma cube or strider
     * on a narrow highway may never get out of it. The build is paused and the player walks along the highway, crouched,
     * up to the closest spot it's in reach from. Once it is, [findTarget] has it and the fight takes over.
     * Given up on for a while when no spot on the highway reaches it or the walk stops getting closer.
     */
    private fun SafeContext.approach(screenOpen: Boolean) {
        val now = System.currentTimeMillis()
        unapproachable.entries.removeIf { now - it.value > UNAPPROACHABLE_MS }
        val mob = approachTarget() ?: return stopFighting()
        // Others first: eating, a container, or a walk, stare or restock of the build. Fought once it comes in reach.
        if (player.isUsingItem || screenOpen || !c.isBuilding || !c.canPauseFor(this@HighwayAura)) return stopFighting()
        val spot = with(c) { approachSpot(mob, buildConfig.entityReach - APPROACH_MARGIN) }
            ?: return giveUpApproach(mob, "no spot on the highway within ${HighwayTools.APPROACH_STEPS} blocks reaches it", chat = false)
        if (!c.pauseFor(this@HighwayAura)) return stopFighting()
        target = null
        waiting = false
        val distance = mob.boundingBox.getClosestPointTo(player.eyePos).distanceTo(player.eyePos)
        if (mob !== approaching) {
            approaching = mob
            approachGoal = null
            approachTicks = 0
            approachStallTicks = 0
            approachClosest = distance
            LOG.info("[HighwayAura] walking up to ${mob.name.string} at ${mob.blockPos.toShortString()}, ${"%.1f".format(distance)} away, from ${player.blockPos.toShortString()} to ${spot.toShortString()}")
            if (announced.add(mob)) c.info("Walking up to the ${mob.name.string} in the way to kill it")
        }
        if (distance < approachClosest - APPROACH_PROGRESS) {
            approachClosest = distance
            approachStallTicks = 0
        } else if (++approachStallTicks > APPROACH_STALL_TICKS) return giveUpApproach(mob, "got no closer for ${APPROACH_STALL_TICKS / 20}s")
        if (++approachTicks > APPROACH_TIMEOUT_TICKS) return giveUpApproach(mob, "not in reach after ${APPROACH_TIMEOUT_TICKS / 20}s")
        // The mob moving, e.g. a magma cube hopping, can move the spot on.
        if (spot != approachGoal) {
            approachGoal = spot
            BaritoneHandler.setGoalAndPath(GoalBlock(spot))
        }
    }

    /** The mob being walked up to, else the closest killable one in the way out of reach. */
    private fun SafeContext.approachTarget(): LivingEntity? {
        // The build is paused on the way, so it doesn't report the mob as in the way any more.
        approaching?.takeIf { it.isAlive && it.world === world && it !in unhittable && it !in unapproachable && it.isKilled }
            ?.let { return it }
        val range = HighwayTools.APPROACH_STEPS + buildConfig.entityReach + 1
        return SpleefGuard.recentBlockers.asSequence()
            .filterIsInstance<LivingEntity>()
            .filter {
                // Skeletons and threats come to you, and a mob only close by isn't in the way.
                it.isAlive && it.world === world && it !in unhittable && it !in unapproachable && it.isKilled &&
                    it !is AbstractSkeletonEntity && it.squaredDistanceTo(player) <= range * range
            }
            .minByOrNull { it.squaredDistanceTo(player) }
    }

    private fun SafeContext.giveUpApproach(mob: LivingEntity, why: String, chat: Boolean = true) {
        LOG.info("[HighwayAura] gave up walking up to ${mob.name.string} at ${mob.blockPos.toShortString()} from ${player.blockPos.toShortString()}: $why")
        if (chat) c.warn("Can't get to the ${mob.name.string} in the way, $why")
        unapproachable[mob] = System.currentTimeMillis()
        stopFighting()
    }

    /** Ends a walk up to a mob, if there is one. Baritone only stops for one, else it may be the build's own path. */
    private fun stopApproaching() {
        if (approaching == null) return
        approaching = null
        approachGoal = null
        BaritoneHandler.cancel()
    }

    private fun SafeContext.fight(target: LivingEntity) {
        val weapon = weaponSlot()
        if (weapon != null && !HotbarRequest(weapon, this@HighwayAura, nowOrNothing = false).submit().done) {
            // A swap that never goes through would keep the build paused for as long as the mob stays.
            if (++missTicks > MAX_MISS_TICKS) giveUp(target)
            settle.reset()
            return
        }
        // The server starts the swing over when the held item changes, so a switch waits for the weapon's full swing.
        val slot = weapon ?: player.inventory.selectedSlot
        if (slot != heldSlot) {
            heldSlot = slot
            heldTicks = 0
        } else heldTicks++

        val hit = runSafeAutomated { lookAtEntity(target) } ?: run {
            if (++missTicks > MAX_MISS_TICKS) giveUp(target)
            settle.reset()
            return
        }
        missTicks = 0
        if (rotate && !settle.ready(rotationRequest { rotation(hit.rotation) }.submit().done)) return

        val swingTicks = ceil(20 / player.attackSpeed(player.hotbarStacks[slot])).toInt()
        if (player.getAttackCooldownProgress(0.5f) < 1f || heldTicks < swingTicks) return
        attackEntity(target, interactConfig.swing)
        heldTicks = 0
    }

    /** Leaves a mob that can't be fought alone for a while, so the build goes on. */
    private fun giveUp(target: LivingEntity) {
        unhittable[target] = System.currentTimeMillis()
        this.target = null
    }

    /** The hotbar slot of the chosen weapon, or of the strongest other one. Null fights with whatever is held. */
    private fun SafeContext.weaponSlot(): Int? {
        val hotbar = player.hotbarStacks
        val stack = listOf(weapon.item).select().bestItemMatch(hotbar)
            ?: if (anyWeapon) (ItemUtils.swords + ItemUtils.axes).select()
                .sortByDescending { player.attackDamage(stack = it) }
                .bestItemMatch(hotbar) else null
        if (stack == null && !warnedNoWeapon) c.warn("No ${weapon.displayName} in your hotbar, fighting with what you hold")
        warnedNoWeapon = stack == null
        return stack?.let { hotbar.indexOf(it) }
    }

    /** One that would pick up the drop of a placed shulker or ender chest. Zombified piglins are waited out instead. */
    private val LivingEntity.isGrabberTarget get() =
        killGrabbers && c.isUsingContainer && isItemGrabber(this) && this !is ZombifiedPiglinEntity

    /** Piglins attack when they see a guarded block like a shulker or ender chest opened, zombified ones when hit. */
    private val LivingEntity.isAngryPiglin get() =
        angryPiglins && (this is AbstractPiglinEntity || this is ZombifiedPiglinEntity) && (this as MobEntity).isAttacking

    private val LivingEntity.isThreat get() =
        this is HoglinEntity || this is AbstractSkeletonEntity || (this is EndermanEntity && isAngry) || isAngryPiglin

    fun stopFighting() {
        stopApproaching()
        target = null
        waiting = false
        heldTicks = 0
        settle.reset()
        c.resume(this)
    }

    private companion object {
        const val SEARCH_EXTRA = 3.0
        const val MAX_MISS_TICKS = 20
        /**
         * The build's own restocks close their container the tick after the fight starts, so this only runs out on a
         * container nobody closes. Long enough for one that was still opening on a laggy server to arrive and be closed.
         */
        const val MAX_SCREEN_WAIT_TICKS = 100
        const val UNHITTABLE_MS = 5_000L
        /** Long enough for the build to wait, step forward or unstuck before the next walk up to the same mob. */
        const val UNAPPROACHABLE_MS = 30_000L
        /** Less than reach, so standing a bit off the spot's middle still reaches. */
        const val APPROACH_MARGIN = 0.4
        const val APPROACH_PROGRESS = 0.1
        const val APPROACH_STALL_TICKS = 60
        const val APPROACH_TIMEOUT_TICKS = 300
    }
}
