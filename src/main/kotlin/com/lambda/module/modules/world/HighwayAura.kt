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

import com.lambda.config.ConfigBlock
import com.lambda.context.Automated
import com.lambda.context.SafeContext
import com.lambda.interaction.handlers.SpleefGuard
import com.lambda.interaction.managers.hotbar.HotbarRequest
import com.lambda.interaction.managers.rotating.IRotationRequest.Companion.rotationRequest
import com.lambda.interaction.managers.rotating.RotationSettle
import com.lambda.interaction.material.StackSelection.Companion.select
import com.lambda.task.tasks.OpenContainerTask
import com.lambda.task.tasks.StationTask.Companion.isItemGrabber
import com.lambda.threading.runSafeAutomated
import com.lambda.util.CommunicationUtils.warn
import com.lambda.util.Describable
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
    val isFighting get() = target != null || waiting
    private val settle = RotationSettle()
    private var warnedNoWeapon = false
    /** Mobs given up on since they couldn't be hit, e.g. behind a wall, and when. */
    private val unhittable = WeakHashMap<Entity, Long>()
    private var missTicks = 0
    private var heldSlot = -1
    private var heldTicks = 0

    fun SafeContext.tick() {
        if (!enabled) return stopFighting()
        var next = findTarget() ?: return stopFighting()
        val pauses = pauseBuild && c.isBuilding
        // Busy with something else, e.g. a restock, so only fight what attacks you or would take a drop.
        if (pauses && !c.canPauseFor(this@HighwayAura)) next = findTarget(threatsOnly = true) ?: return stopFighting()
        // A restock closes its container for the fight and carries on once the mob is dead. Eating finishes first.
        if (player.isUsingItem || player.currentScreenHandler != player.playerScreenHandler) {
            stopFighting()
            waiting = true
            return
        }
        if (pauses) c.pauseFor(this@HighwayAura)
        if (next !== target) {
            settle.reset()
            missTicks = 0
        }
        target = next
        fight(next)
    }

    fun crouch(input: Input) {
        if (crouch && target != null && !OpenContainerTask.isOpening) input.sneaking = true
    }

    fun clear() {
        target = null
        unhittable.clear()
    }

    /** The mob being fought while it's in reach, so one knocked out of the way doesn't come back. Else the closest. */
    private fun SafeContext.findTarget(threatsOnly: Boolean = false): LivingEntity? {
        if (!angryEndermen && !calmEndermen && !ghasts && !striders && !magmaCubes && !skeletons && !hoglins && !angryPiglins && !killGrabbers) return null
        val now = System.currentTimeMillis()
        unhittable.entries.removeIf { now - it.value > UNHITTABLE_MS }
        val reach = buildConfig.entityReach
        // Reach goes to the hitbox, which for a ghast is far from where it stands.
        val inReach = entitySearch<LivingEntity>(reach + SEARCH_EXTRA) {
            it.isAlive && it !in unhittable && (!threatsOnly || it.isThreat || it.isGrabberTarget) && it.isInReach(player.eyePos, reach) && when {
                it.isGrabberTarget || it.isAngryPiglin -> true
                it is EndermanEntity -> if (it.isAngry) angryEndermen else calmEndermen
                it is GhastEntity -> ghasts
                it is StriderEntity -> striders
                it is MagmaCubeEntity -> magmaCubes
                it is AbstractSkeletonEntity -> skeletons
                it is HoglinEntity -> hoglins
                else -> false
            }
        }
        inReach.firstOrNull { it === target }?.let { return it }

        return inReach
            .filter {
                val allowed = !onlyInTheWay || it in SpleefGuard.recentBlockers
                when {
                    it.isGrabberTarget || it.isAngryPiglin -> true
                    // Too wide to spleef, so the build would wait for one in the way forever.
                    it is HoglinEntity -> it in SpleefGuard.recentBlockers || it.squaredDistanceTo(player) <= hoglinRange * hoglinRange
                    it is AbstractSkeletonEntity -> it.squaredDistanceTo(player) <= skeletonRange * skeletonRange
                    // An angry enderman is coming for you.
                    it is EndermanEntity -> it.isAngry || allowed
                    else -> allowed
                }
            }
            .minByOrNull { it.squaredDistanceTo(player) }
    }

    private fun SafeContext.fight(target: LivingEntity) {
        val weapon = weaponSlot()
        if (weapon != null && !HotbarRequest(weapon, this@HighwayAura, nowOrNothing = false).submit().done) {
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
            if (++missTicks > MAX_MISS_TICKS) {
                unhittable[target] = System.currentTimeMillis()
                this@HighwayAura.target = null
            }
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
        target = null
        waiting = false
        heldTicks = 0
        settle.reset()
        c.resume(this)
    }

    private companion object {
        const val SEARCH_EXTRA = 3.0
        const val MAX_MISS_TICKS = 20
        const val UNHITTABLE_MS = 5_000L
    }
}
