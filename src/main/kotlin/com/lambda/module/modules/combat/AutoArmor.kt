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

package com.lambda.module.modules.combat

import com.lambda.config.automation.AutomationConfig.Companion.setDefaultAutomationConfig
import com.lambda.config.hideAllExcept
import com.lambda.config.settings.complex.Bind
import com.lambda.config.settings.complex.KeybindSetting.Companion.onPress
import com.lambda.config.withEdits
import com.lambda.context.SafeContext
import com.lambda.event.events.TickEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.interaction.managers.inventory.InventoryRequest.Companion.inventoryRequest
import com.lambda.module.Module
import com.lambda.module.modules.world.HighwayTools
import com.lambda.module.tag.ModuleTag
import com.lambda.util.EnchantmentUtils.getEnchantment
import com.lambda.util.item.ItemUtils.armorSlot
import com.lambda.util.player.SlotUtils.armorSlots
import com.lambda.util.player.SlotUtils.hotbarAndInventorySlots
import net.minecraft.component.DataComponentTypes
import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.Enchantments
import net.minecraft.entity.attribute.EntityAttribute
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.item.Items
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.registry.tag.ItemTags
import net.minecraft.screen.slot.Slot

@Suppress("unused")
object AutoArmor : Module(
	name = "AutoArmor",
	description = "Automatically equips armor",
	tag = ModuleTag.COMBAT
) {
	var elytraPriority by setting("Elytra Priority", false, "Prioritizes elytra's over other armor pieces in the chest slot")
	private val toggleElytraPriority by setting("Toggle Elytra Priority", Bind.EMPTY)
		.onPress { elytraPriority = !elytraPriority }
	private val minDurabilityPercentage by setting("Min Durability", 1, 0..100, 1, "Minimum durability percentage before being swapped for a new piece", "%")
	private val headProtection by setting("Preferred Head Protection", Protection.Protection)
	private val chestProtection by setting("Preferred Chest Protection", Protection.Protection)
	private val legProtection by setting("Preferred Leg Protection", Protection.Protection)
	private val feetProtection by setting("Preferred Feet Protection", Protection.Protection)
	private val ignoreBinding by setting("Ignore Binding", true, "Ignores curse of binding armor pieces")

	private var tickedThisTick = false

	var overriddenElytraPriority: Boolean? = null

	/** The better piece first: one that's not about to break, then elytra priority, armor, toughness, enchantments, the hotbar. */
	val SORTER = Comparator<Slot> { a, b -> compareScores(score(b), score(a)) }

	/** What [SORTER] compares, best first, one after the other. */
	private fun score(slot: Slot): DoubleArray {
		val stack = slot.stack
		fun attribute(attribute: RegistryEntry<EntityAttribute>) = stack.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, null)
			?.modifiers
			?.find { modifier -> modifier.attribute == attribute }
			?.modifier?.value
			?: 0.0
		return doubleArrayOf(
			if (stack.isDamageable && 1 - (stack.damage.toFloat() / stack.maxDamage) < minDurabilityPercentage.toFloat() / 100) -Double.MAX_VALUE else 0.0,
			if ((overriddenElytraPriority ?: elytraPriority) && stack.item == Items.ELYTRA) 1.0 else 0.0,
			attribute(EntityAttributes.ARMOR),
			attribute(EntityAttributes.ARMOR_TOUGHNESS),
			when {
				stack.isIn(ItemTags.FOOT_ARMOR) -> stack.getEnchantment(feetProtection.enchant)
				stack.isIn(ItemTags.LEG_ARMOR) -> stack.getEnchantment(legProtection.enchant)
				stack.isIn(ItemTags.CHEST_ARMOR) -> stack.getEnchantment(chestProtection.enchant)
				else -> stack.getEnchantment(headProtection.enchant)
			}.toDouble(),
			Protection.entries.sumOf { stack.getEnchantment(it.enchant) }.toDouble(),
			(stack.getEnchantment(Enchantments.UNBREAKING) + stack.getEnchantment(Enchantments.MENDING)).toDouble(),
			if (slot.index in 0..8) 1.0 else 0.0,
		)
	}

	private fun compareScores(a: DoubleArray, b: DoubleArray): Int {
		for (i in a.indices) a[i].compareTo(b[i]).let { if (it != 0) return it }
		return 0
	}

	init {
		setDefaultAutomationConfig()
			.withEdits {
				hideAllExcept(::inventoryConfig)
			}

		listen<TickEvent.Pre> {
			if (!tickedThisTick) tick()
		}

		listen<TickEvent.Post>({ Int.MIN_VALUE }) {
			tickedThisTick = false
		}
	}

	fun SafeContext.tick() {
		tickedThisTick = true
		// Its clicks would go to the wrong slots while a shulker or the ender chest is open.
		if (HighwayTools.isUsingContainer) return
		val armorSlots = player.armorSlots
		// Each slot scored once, sorting compares them again and again. The same order as SORTER.
		val scores = HashMap<Slot, DoubleArray>()
		val sorter = Comparator<Slot> { a, b -> compareScores(scores.getOrPut(b) { score(b) }, scores.getOrPut(a) { score(a) }) }

		val swappable = player.hotbarAndInventorySlots
			.filter { slot ->
				!slot.stack.isEmpty && armorSlots.any { it.canInsert(slot.stack) } &&
					(!ignoreBinding || slot.stack.getEnchantment(Enchantments.BINDING_CURSE) <= 0)
			}
			.sortedWith(sorter)
			.distinctBy { it.stack.armorSlot }

		val swaps = mutableListOf<Pair<Slot, Slot>>()
		armorSlots.forEach { equipped ->
			val new = swappable.find { new ->
				equipped.canInsert(new.stack) && sorter.compare(equipped, new) > 0
			} ?: return@forEach

			swaps.add(Pair(new, equipped))
		}

		if (swaps.isEmpty()) return
		inventoryRequest {
			swaps.forEach {
				moveSlot(it.first.id, it.second.id)
				if (!it.second.stack.isEmpty) pickup(it.first.id)
			}
		}.submit()
	}

	private enum class Protection(val enchant: RegistryKey<Enchantment>) {
		Protection(Enchantments.PROTECTION),
		BlastProtection(Enchantments.BLAST_PROTECTION),
		ProjectileProtection(Enchantments.PROJECTILE_PROTECTION),
		FireProtection(Enchantments.FIRE_PROTECTION);
	}
}