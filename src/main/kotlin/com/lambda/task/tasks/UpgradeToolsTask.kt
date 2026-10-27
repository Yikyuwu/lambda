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
import com.lambda.util.CommunicationUtils.info
import com.lambda.util.extension.containerSlots
import com.lambda.util.extension.hasRoomFor
import com.lambda.util.extension.playerSlots
import com.lambda.util.player.SlotUtils.hotbarAndInventorySlots
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.SmithingScreenHandler
import net.minecraft.util.math.BlockPos

/** Settings for [UpgradeToolsTask], read every tick. */
interface UpgradeConfig {
    /** Most diamond items to take per trip. */
    val maxItems: Int
    val moveDelay: Int
    val returnLeftovers: Boolean

    fun isDiamond(stack: ItemStack): Boolean
    fun isUpgraded(stack: ItemStack): Boolean
    /** Upgraded items to put into the dump chest. The others stay in the inventory. */
    fun shouldDump(stack: ItemStack): Boolean
}

/**
 * Upgrades diamond items from [diamondChest] to netherite at [smithingTable], with templates and ingots
 * from their chests, and dumps them into [dumpChest]. Stops once the diamond chest is empty or the dump chest is full.
 * A chest that isn't set is left out: without the diamond chest only carried items are upgraded, without the template
 * or ingot chest only carried ones are used, and without the dump chest upgraded items stay in the inventory.
 */
class UpgradeToolsTask @Ta5kBuilder constructor(
    private val diamondChest: BlockPos?,
    private val templateChest: BlockPos?,
    private val ingotChest: BlockPos?,
    private val dumpChest: BlockPos?,
    private val smithingTable: BlockPos,
    private val config: UpgradeConfig,
    automated: Automated,
) : StationTask<UpgradeToolsTask.Phase>("Upgrading to netherite", Phase.Opening, automated) {
    override val moveDelay get() = config.moveDelay

    enum class Phase(override val description: String) : StationPhase {
        Opening("opening a container"),
        Dumping("dumping upgraded items"),
        TakingDiamond("taking diamond items"),
        TakingTemplates("taking smithing templates"),
        TakingIngots("taking netherite ingots"),
        Upgrading("upgrading"),
        ReturningDiamonds("returning leftover diamond items"),
        ReturningTemplates("returning leftover templates"),
        ReturningIngots("returning leftover ingots");

        override val timeoutTicks = 200
    }

    private var diamondChestEmpty = false
    /** Free slots in the dump chest the last time it was open. */
    private var dumpFree = Int.MAX_VALUE
    private var upgraded = 0
    /** Clicks on the result slot since the diamond item went in. */
    private var resultTakes = 0
    private var resultResynced = false
    /** Upgraded items carried before the last result take, to tell whether it really arrived. */
    private var upgradedBefore = 0
    /** The template or ingot chest ran out, so only what's carried gets used. */
    private var materialsOut = false

    private fun isTemplate(stack: ItemStack) = stack.item == Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE
    private fun isIngot(stack: ItemStack) = stack.item == Items.NETHERITE_INGOT


    override fun SafeContext.onTick() {
        when (phase) {
            Phase.Dumping -> containerStep { dump(it) }
            Phase.TakingDiamond -> containerStep { takeDiamond(it) }
            Phase.TakingTemplates -> containerStep { takeFor(it, ::isTemplate, "smithing templates") }
            Phase.TakingIngots -> containerStep { takeFor(it, ::isIngot, "netherite ingots") }
            Phase.Upgrading -> containerStep { upgrade(it) }
            Phase.ReturningDiamonds -> containerStep { putAway(it, config::isDiamond, "diamond") }
            Phase.ReturningTemplates -> containerStep { putAway(it, ::isTemplate, "template") }
            Phase.ReturningIngots -> containerStep { putAway(it, ::isIngot, "ingot") }
            Phase.Opening -> {}
        }
    }

    override fun SafeContext.resume() {
        val diamond = inInventory(config::isDiamond)
        // A failed visit gave back what was in the table, so nothing taken from it before counts.
        resultTakes = 0
        resultResynced = false
        val templates = carriedCount(::isTemplate)
        val ingots = carriedCount(::isIngot)
        val templateSource = templateChest?.takeIf { !materialsOut }
        val ingotSource = ingotChest?.takeIf { !materialsOut }
        val dump = dumpChest?.takeIf { dumpFree != 0 }
        val dumpFull = dumpChest != null && dump == null
        val outOfMaterials = templates == 0 && templateSource == null || ingots == 0 && ingotSource == null
        val canUpgrade = !dumpFull && diamond > 0 && !outOfMaterials
        when {
            dump != null && (diamond == 0 || outOfMaterials) && inInventory(config::shouldDump) > 0 -> open(dump, Phase.Dumping)
            canUpgrade && templateSource != null && templates < diamond -> open(templateSource, Phase.TakingTemplates)
            canUpgrade && ingotSource != null && ingots < diamond -> open(ingotSource, Phase.TakingIngots)
            // The timeout restarts for every item, see upgrade().
            canUpgrade -> open(smithingTable, Phase.Upgrading, moveDelay * CLICKS_PER_UPGRADE)
            diamondChest != null && !dumpFull && !materialsOut && !diamondChestEmpty -> open(diamondChest, Phase.TakingDiamond)
            config.returnLeftovers && diamondChest != null && !canUpgrade && diamond > 0 -> open(diamondChest, Phase.ReturningDiamonds)
            config.returnLeftovers && templateChest != null && inInventory(::isTemplate) > 0 -> open(templateChest, Phase.ReturningTemplates)
            config.returnLeftovers && ingotChest != null && inInventory(::isIngot) > 0 -> open(ingotChest, Phase.ReturningIngots)
            else -> finish(when {
                dumpFull -> "Dump chest is full"
                outOfMaterials && diamond > 0 -> "Out of smithing templates or netherite ingots"
                diamondChestEmpty -> "Diamond chest is empty"
                else -> null
            })
        }
    }

    private fun SafeContext.open(pos: BlockPos, next: Phase) =
        open(pos, next, (player.hotbarAndInventorySlots.size + 1) * moveDelay * 2)

    private fun SafeContext.finish(reason: String?) {
        reason?.let { info(it) }
        info("Upgraded $upgraded item(s)")
        keptMessage()?.let { info(it) }
        success()
    }

    private fun SafeContext.dump(handler: ScreenHandler): Boolean {
        dumpFree = handler.containerSlots.count { it.stack.isEmpty }
        val item = handler.playerSlots.firstOrNull { config.shouldDump(it.stack) } ?: return false
        // Full, so resume() ends the run and the rest stays in the inventory.
        if (dumpFree == 0) return false
        submit { quickMove(item.id) }
        return true
    }

    private fun SafeContext.putAway(handler: ScreenHandler, predicate: (ItemStack) -> Boolean, chest: String): Boolean {
        val item = handler.playerSlots.firstOrNull { predicate(it.stack) } ?: return false
        if (!handler.containerSlots.hasRoomFor(item.stack)) return failAction("The $chest chest is full")
        submit { quickMove(item.id) }
        return true
    }

    /** Takes one diamond item, keeping two slots free for the template and ingot stacks. */
    private fun SafeContext.takeDiamond(handler: ScreenHandler): Boolean {
        val inChest = handler.containerSlots.filter { config.isDiamond(it.stack) }
        val reserved = (if (inInventory(::isTemplate) == 0) 1 else 0) + (if (inInventory(::isIngot) == 0) 1 else 0)
        val freeSlots = handler.playerSlots.count { it.stack.isEmpty } - reserved
        val diamonds = inInventory(config::isDiamond)
        diamondChestEmpty = inChest.isEmpty()
        if (inChest.isEmpty() || diamonds >= minOf(config.maxItems, dumpFree)) return false
        if (freeSlots <= 0) {
            if (diamonds == 0) return failAction("No free inventory slots for diamond items" + (keptMessage()?.let { ". $it" } ?: ""))
            return false
        }
        // Taking the last one empties the chest, so there's no need to come back and look.
        if (submit { quickMove(inChest.first().id) } && inChest.size == 1) diamondChestEmpty = true
        return true
    }

    /** Takes stacks matching [predicate] until there's one for every carried diamond item, or the chest runs out. */
    private fun SafeContext.takeFor(handler: ScreenHandler, predicate: (ItemStack) -> Boolean, what: String): Boolean {
        if (carriedCount(predicate) >= inInventory(config::isDiamond)) return false
        val stack = handler.containerSlots.firstOrNull { predicate(it.stack) } ?: run {
            materialsOut = true
            return false
        }
        if (!handler.playerSlots.hasRoomFor(stack.stack)) return failAction("No free inventory slot for $what")
        submit { quickMove(stack.id) }
        return true
    }

    /**
     * Takes out the result, or fills the next slot of the table. Once something runs out, closing the table
     * gives back what's left in it, so leftovers are only taken out when there's no room for that.
     */
    private fun SafeContext.upgrade(handler: ScreenHandler): Boolean {
        val smithing = handler as? SmithingScreenHandler ?: return failAction("That's not a smithing table")
        val template = smithing.getSlot(TEMPLATE_SLOT)
        val diamond = smithing.getSlot(EQUIPMENT_SLOT)
        val ingot = smithing.getSlot(MATERIAL_SLOT)

        // The last take worked once the diamond item is gone from the table and the netherite one arrived.
        if (resultTakes > 0 && !diamond.hasStack()) {
            if (inInventory(config::isUpgraded) > upgradedBefore) {
                upgraded++
                progressed()
            }
            resultTakes = 0
            resultResynced = false
        }
        if (smithing.getSlot(RESULT_SLOT).hasStack()) return takeResult()

        // The template, ingot and diamond item for the slots that are still empty.
        val inputs = listOf(TEMPLATE_SLOT to ::isTemplate, MATERIAL_SLOT to ::isIngot, EQUIPMENT_SLOT to config::isDiamond)
            .filter { (slot, _) -> !smithing.getSlot(slot).hasStack() }
            .map { (_, predicate) -> smithing.playerSlots.firstOrNull { predicate(it.stack) } }

        if (null in inputs) {
            val leftovers = listOf(diamond, ingot, template).filter { it.hasStack() }
            if (smithing.playerSlots.count { it.stack.isEmpty } >= leftovers.size) return false
            submit { quickMove(leftovers.first().id) }
            return true
        }
        val input = inputs.firstOrNull()
        when {
            input != null -> submit { quickMove(input.id) }
            // The client often doesn't show the result, but the server made it when the last item went in.
            resultTakes < MAX_RESULT_TAKES -> takeResult()
            !resultResynced -> resultResynced = submit { resyncInventory() }
            // Maybe just a slow server, closing and coming back gives it time.
            else -> phaseFailed("no result for ${diamond.stack.name.string}")
        }
        return true
    }

    private fun SafeContext.takeResult(): Boolean {
        if (resultTakes == 0) upgradedBefore = inInventory(config::isUpgraded)
        if (submit { quickMove(RESULT_SLOT) }) resultTakes++
        return true
    }

    private fun SafeContext.keptMessage() =
        inInventory { config.isUpgraded(it) && !config.shouldDump(it) }
            .takeIf { it > 0 }
            ?.let { "Holding $it damaged netherite item(s) that weren't dumped" }

    companion object {
        private const val MAX_RESULT_TAKES = 3
        /** Enough for filling the table and retrying a missing result a few times. */
        private const val CLICKS_PER_UPGRADE = 20
        private const val TEMPLATE_SLOT = 0
        private const val EQUIPMENT_SLOT = 1
        private const val MATERIAL_SLOT = 2
        private const val RESULT_SLOT = 3
    }
}
