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

package com.lambda.interaction.material.container.containers

import com.lambda.context.AutomatedSafeContext
import com.lambda.context.SafeContext
import com.lambda.interaction.handlers.ContainerHandler
import com.lambda.interaction.material.container.ExternalContainer
import com.lambda.interaction.material.container.MaterialContainer
import com.lambda.task.tasks.BuildTask.Companion.breakAndCollectBlock
import com.lambda.task.tasks.EnderChestRestockTask.Companion.MIN_ENDER_CHESTS
import com.lambda.task.tasks.EnderChestRestockTask.Companion.hasSilkTouchPickaxe
import com.lambda.task.tasks.EnderChestRestockTask.Companion.isEnderChest
import com.lambda.task.tasks.EnderChestRestockTask.Companion.withSilkTouch
import com.lambda.task.tasks.OpenContainerTask
import com.lambda.task.tasks.PlaceContainerTask
import com.lambda.task.tasks.PlaceContainerTask.Companion.isSafeContainerSpot
import com.lambda.task.wrappers.TaskSupplier
import com.lambda.task.wrappers.thenAction
import com.lambda.task.wrappers.thenOrNull
import com.lambda.util.extension.containerSlots
import com.lambda.util.player.SlotUtils.hotbarAndInventorySlots
import com.lambda.util.text.buildText
import com.lambda.util.text.literal
import net.minecraft.block.entity.EnderChestBlockEntity
import net.minecraft.item.ItemStack

object EnderChestContainer : MaterialContainer(Rank.EnderChest), ExternalContainer {
	context(safeContext: SafeContext)
	override val slots
		get() =
			if (ContainerHandler.lastInteractedBlockEntity is EnderChestBlockEntity)
				safeContext.player.currentScreenHandler.containerSlots
			else emptyList()
	override var stacks = emptyList<ItemStack>()

	override val description = buildText { literal("Ender Chest") }

	/** Never with the last ender chest, without one the ender chest can't be reached any more. Breaks it with Silk Touch if there is some. */
	context(automatedSafeContext: AutomatedSafeContext)
	override fun accessThen(exitAfter: Boolean, taskSupplier: TaskSupplier<Unit, Unit>) = with(automatedSafeContext) {
		val chests = player.hotbarAndInventorySlots.filter { isEnderChest(it.stack) }
		if (chests.sumOf { it.stack.count } < MIN_ENDER_CHESTS) return@with null
		val breaker = if (hasSilkTouchPickaxe()) withSilkTouch() else automatedSafeContext
		PlaceContainerTask(chests.first(), automatedSafeContext) { isSafeContainerSpot(it) }.thenAction { pos ->
			OpenContainerTask(pos, automatedSafeContext).thenAction {
				taskSupplier.invoke(automatedSafeContext, Unit).thenOrNull {
					if (exitAfter) breaker.breakAndCollectBlock(pos, lifeMaintenance = false)
					else null
				}
			}
		}
	}
}
