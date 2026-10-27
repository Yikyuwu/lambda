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

import com.lambda.context.Automated
import com.lambda.context.SafeContext
import com.lambda.interaction.managers.hotbar.HotbarRequest
import com.lambda.interaction.managers.rotating.IRotationRequest.Companion.rotationRequest
import com.lambda.interaction.managers.rotating.RotationManager
import com.lambda.interaction.material.StackSelection.Companion.selectStack
import com.lambda.interaction.material.container.containers.HotbarContainer
import com.lambda.interaction.material.container.containers.InventoryContainer
import com.lambda.threading.runSafeAutomated
import com.lambda.util.BlockUtils.blockState
import com.lambda.util.CommunicationUtils.info
import com.lambda.util.CommunicationUtils.warn
import com.lambda.util.EnchantmentUtils.getEnchantment
import com.lambda.util.extension.containerSlots
import com.lambda.util.extension.playerSlots
import com.lambda.util.item.ItemStackUtils.durabilityFraction
import com.lambda.util.item.ItemStackUtils.shulkerBoxContents
import com.lambda.util.player.SlotUtils.armorSlots
import com.lambda.util.player.SlotUtils.hotbarAndInventorySlots
import com.lambda.util.world.closestEntity
import net.minecraft.block.ShulkerBoxBlock
import net.minecraft.enchantment.Enchantments
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.ExperienceOrbEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket
import net.minecraft.screen.ScreenHandler
import net.minecraft.util.Hand

/**
 * Mends worn armor with Mending by throwing experience bottles at your feet. The bottles come from the inventory,
 * or else from a carried shulker box: place it, take a stack, throw it, take another while the armor isn't mended,
 * put the rest back, then break the shulker and pick it back up.
 * With [items] only those are mended, and carried ones are worn or held in the offhand in turn, e.g. elytras.
 */
class MendArmorTask @Ta5kBuilder constructor(
    /** Durability fraction to mend to. */
    private val mendTo: Float,
    private val throwDelay: Int,
    private val swapDelay: Int,
    automated: Automated,
    holdWhile: () -> Boolean = { false },
    /** Mends only these, also carried ones by wearing and holding them in turn. Null mends the worn armor. */
    private val items: ((ItemStack) -> Boolean)? = null,
) : ShulkerTask<MendArmorTask.Phase>(
    "Mending armor", Phase.Opening, automated, holdWhile,
    Phase.Placing, Phase.MakingRoom, Phase.Breaking, Phase.PickingUp,
) {
    override val moveDelay get() = swapDelay

    enum class Phase(override val description: String, override val timeoutTicks: Int) : StationPhase {
        Placing("placing shulker", 400),
        Opening("opening shulker", 200),
        Taking("taking experience bottles", 200),
        Throwing("throwing experience bottles", 200),
        Returning("putting experience bottles back", 200),
        MakingRoom("making room for the shulker", 100),
        Breaking("breaking shulker", 300),
        PickingUp("picking up shulker", 300),
    }

    /** What the shulker is being opened for, to open it again after a failed try. */
    private var openingFor = Phase.Taking
    private var throwCooldown = 0
    private var orbWaitTicks = 0
    private var thrown = 0
    /** Bottles thrown since the last stack was taken. None means taking one didn't work, so no more are taken. */
    private var thrownSinceTake = 0
    private var shulkerEmpty = false
    /** Something broke the open shulker, so it's placed again once it's picked up. */
    private var placeAgain = false
    private var placements = 0
    /** What was in the offhand before an item to mend went there, to put back when done. */
    private var originalOffhand: Item? = null

    private val bottles = selectStack { isItem(Items.EXPERIENCE_BOTTLE) }

    private fun isBottle(stack: ItemStack) = stack.item == Items.EXPERIENCE_BOTTLE

    override fun SafeContext.resume() {
        if (stopping) return if (placedPos != null) collect() else finish()
        if (placedPos != null) return openShulker(openingFor)
        when {
            worn().isEmpty() -> finish()
            carriedCount(::isBottle) > 0 -> startThrowing()
            else -> placeShulker()
        }
    }

    override fun SafeContext.onPlaced() {
        shulkerEmpty = false
        openShulker(Phase.Taking)
    }

    override fun SafeContext.tickWork() {
        when (phase) {
            Phase.Taking -> containerStep(onDone = { startThrowing() }) { take(it) }
            Phase.Throwing -> throwBottles()
            Phase.Returning -> containerStep(onDone = { collect() }) { putBack(it) }
            else -> {}
        }
    }

    override fun SafeContext.shulkerBack() {
        if (placeAgain && placements < MAX_PLACEMENTS) {
            placeAgain = false
            return resume()
        }
        finish()
    }

    private fun needsMend(stack: ItemStack) = items?.invoke(stack) != false &&
        stack.isDamageable && stack.getEnchantment(Enchantments.MENDING) > 0 && stack.durabilityFraction < mendTo

    /** What's left to mend: worn armor with Mending below [mendTo], with [items] also what's held or carried. */
    private fun SafeContext.worn(): List<ItemStack> {
        val carried = if (items == null) emptyList() else player.inventory.mainStacks + player.offHandStack
        return (ARMOR_SLOTS.map { player.getEquippedStack(it) } + carried).filter(::needsMend)
    }

    /** Wears or holds the next carried item to mend in place of a mended one, one click at a time. Whether it clicked. */
    private fun SafeContext.equipNext(): Boolean {
        val next = player.hotbarAndInventorySlots.firstOrNull { needsMend(it.stack) } ?: return false
        if (!settled) return true
        // Curse of Binding can't be taken off.
        player.armorSlots.firstOrNull {
            it.canInsert(next.stack) && !needsMend(it.stack) && it.stack.getEnchantment(Enchantments.BINDING_CURSE) == 0
        }?.let { armor ->
            submit {
                pickup(next.id)
                pickup(armor.id)
                pickup(next.id)
            }
            return true
        }
        val offhand = player.offHandStack
        // Nor a totem, AutoTotem would put it back at once and mending would never get to throwing.
        if (needsMend(offhand) || isBottle(offhand) || offhand.isOf(Items.TOTEM_OF_UNDYING)) return false
        if (originalOffhand == null && !offhand.isEmpty && items?.invoke(offhand) != true) originalOffhand = offhand.item
        submit { swap(next.id, OFFHAND_BUTTON) }
        return true
    }

    private fun SafeContext.placeShulker() {
        if (stopping) return finish()
        // The shulker with the fewest bottles that still has some, so shulkers are used up one after another.
        val shulker = player.hotbarAndInventorySlots
            .filter { isShulker(it.stack) && it.stack.shulkerBoxContents.any(::isBottle) }
            .minByOrNull { slot -> slot.stack.shulkerBoxContents.filter(::isBottle).sumOf { it.count } }
        if (shulker == null) {
            warn("No experience bottles left to mend the armor with")
            return finish()
        }

        info("Mending ${worn().size} item(s) with experience bottles from ${shulker.stack.name.string}")
        placements++
        placeShulker(shulker)
    }

    private fun SafeContext.openShulker(next: Phase) {
        if (stopping) return collect()
        val pos = placedPos ?: return resume()
        openingFor = next
        open(pos, next, extraTicks = TICKS_PER_STACK * player.hotbarAndInventorySlots.size)
    }

    /** Takes one stack of bottles, the smallest so leftovers get used up first, throwing away junk to make room for it. */
    private fun SafeContext.take(handler: ScreenHandler): Boolean {
        if (carriedCount(::isBottle) > 0) return false
        val bottle = handler.containerSlots.filter { isBottle(it.stack) }.minByOrNull { it.stack.count }
        if (bottle == null) {
            shulkerEmpty = true
            return false
        }
        // A digger's inventory is usually full, and a quick move into it would silently do nothing.
        if (handler.playerSlots.none { it.stack.isEmpty }) {
            val junk = handler.playerSlots.firstOrNull { it.stack.item in inventoryConfig.disposables }
            if (junk == null) {
                warn("Inventory is full and has nothing to throw away, can't take experience bottles")
                return false
            }
            submit { throwStack(junk.id) }
            return true
        }
        submit { quickMove(bottle.id) }
        return true
    }

    private fun SafeContext.startThrowing() {
        closeContainer()
        throwCooldown = 0
        orbWaitTicks = 0
        thrownSinceTake = 0
        enter(Phase.Throwing)
    }

    /**
     * Throws a bottle at your feet every [throwDelay] ticks while armor is worn,
     * then waits for the orbs that are left to be picked up.
     */
    private fun SafeContext.throwBottles() {
        if (worn().isEmpty() || carriedCount(::isBottle) == 0) {
            val orbsLeft = closestEntity<ExperienceOrbEntity>(ORB_RADIUS) != null
            if (orbsLeft && ++orbWaitTicks < ORB_WAIT_TICKS) return
            return doneThrowing()
        }
        if (items != null && equipNext()) return
        rotationRequest { pitch(THROW_PITCH) }.submit()

        val hotbarSlot = bottles.filterSlots(HotbarContainer.slots).firstOrNull()
        if (hotbarSlot == null) {
            if (settled) runSafeAutomated { InventoryContainer.transfer(bottles, HotbarContainer) }
            return
        }
        if (!HotbarRequest(hotbarSlot.index, this@MendArmorTask, nowOrNothing = false).submit().done) return
        val rotation = RotationManager.serverRotation
        if (rotation.pitch < THROW_PITCH - THROW_PITCH_TOLERANCE) return
        if (throwCooldown > 0 && --throwCooldown > 0) return

        // The packet carries the rotation the server throws with, which is the camera's once the rotation request ended.
        interaction.syncSelectedSlot()
        interaction.sendSequencedPacket(world) { sequence ->
            PlayerInteractItemC2SPacket(Hand.MAIN_HAND, sequence, rotation.yawF, rotation.pitchF)
        }
        player.swingHand(Hand.MAIN_HAND)
        throwCooldown = throwDelay
        thrown++
        thrownSinceTake++
        progressed()
    }

    /** Out of bottles while the armor is still worn takes another stack, once it's mended the rest goes back. */
    private fun SafeContext.doneThrowing() {
        val bottlesLeft = carriedCount(::isBottle) > 0
        val needsMore = worn().isNotEmpty() && !bottlesLeft
        when {
            placedPos == null -> if (needsMore) placeShulker() else finish()
            needsMore && !shulkerEmpty && thrownSinceTake > 0 -> openShulker(Phase.Taking)
            bottlesLeft -> openShulker(Phase.Returning)
            else -> collect()
        }
    }

    /** Puts the bottles that are left back into the shulker while it has room. */
    private fun SafeContext.putBack(handler: ScreenHandler): Boolean {
        val bottle = handler.playerSlots.firstOrNull { isBottle(it.stack) } ?: return false
        val room = handler.containerSlots.any { it.stack.isEmpty || (isBottle(it.stack) && it.stack.count < it.stack.maxCount) }
        if (!room) return false
        submit { quickMove(bottle.id) }
        return true
    }

    override fun SafeContext.workFailed(reason: String?) {
        // E.g. the server finished an old break where it was placed.
        if (phase in OPEN_PHASES && placedPos?.let { blockState(it).block !is ShulkerBoxBlock } == true) {
            warn("Something broke the shulker, picking it up to place it again")
            closeContainer()
            placeAgain = true
            return enter(Phase.PickingUp)
        }

        when (phase) {
            Phase.Opening -> if (++openAttempts < MAX_OPEN_ATTEMPTS) openShulker(openingFor) else {
                warn("Couldn't open the shulker ($reason), picking it back up")
                collect()
            }
            // Throw what's there, the rest stays in the shulker.
            Phase.Taking -> {
                warn("Failed while taking experience bottles ($reason)")
                startThrowing()
            }
            // E.g. the bottles don't reach the armor. The rest goes back.
            Phase.Throwing -> {
                warn("Stopped throwing experience bottles ($reason)")
                doneThrowing()
            }
            Phase.Returning -> {
                warn("Failed while putting experience bottles back ($reason), keeping them")
                collect()
            }
            else -> {}
        }
    }

    override fun SafeContext.finish() {
        val worn = worn().size
        when {
            worn > 0 -> warn("Threw $thrown experience bottle(s), $worn item(s) still worn")
            thrown > 0 -> info("Mended with $thrown experience bottle(s)")
        }
        originalOffhand?.takeIf { player.offHandStack.item != it }?.let { item ->
            player.hotbarAndInventorySlots.firstOrNull { it.stack.item == item }?.let { slot -> submit { swap(slot.id, OFFHAND_BUTTON) } }
        }
        success()
    }

    companion object {
        private val ARMOR_SLOTS = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
        private const val THROW_PITCH = 90.0
        private const val THROW_PITCH_TOLERANCE = 5.0
        private const val ORB_RADIUS = 4.0
        private const val ORB_WAIT_TICKS = 60
        private const val TICKS_PER_STACK = 10
        private const val MAX_PLACEMENTS = 3
        private val OPEN_PHASES = setOf(Phase.Opening, Phase.Taking, Phase.Returning)
    }
}
