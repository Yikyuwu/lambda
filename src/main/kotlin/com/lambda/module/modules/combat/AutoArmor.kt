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
import com.lambda.interaction.handlers.FlightStop
import com.lambda.task.tasks.EnderChestRestockTask
import com.lambda.task.tasks.EnderChestRestockTask.Companion.MIN_ENDER_CHESTS
import com.lambda.task.tasks.EnderChestRestockTask.Companion.isEnderChest
import com.lambda.util.CommunicationUtils.info
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.interaction.managers.inventory.InventoryRequest.Companion.inventoryRequest
import com.lambda.module.Module
import com.lambda.module.modules.world.HighwayTools
import com.lambda.module.tag.ModuleTag
import com.lambda.task.RootTask.run
import com.lambda.task.Task
import com.lambda.task.tasks.MendArmorTask
import com.lambda.task.tasks.StationTask.Companion.waitsForPlayer
import com.lambda.task.tasks.StationTask.Companion.stopSafely
import com.lambda.util.CommunicationUtils.logError
import com.lambda.util.EnchantmentUtils.getEnchantment
import com.lambda.util.item.ItemStackUtils.durabilityFraction
import com.lambda.util.item.ItemStackUtils.shulkerBoxContents
import com.lambda.util.item.ItemUtils
import com.lambda.util.item.ItemUtils.armorSlot
import com.lambda.util.player.SlotUtils.armorSlots
import com.lambda.util.player.SlotUtils.hotbarAndInventorySlots
import net.minecraft.component.DataComponentTypes
import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.Enchantments
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.attribute.EntityAttribute
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.registry.tag.ItemTags
import net.minecraft.screen.slot.Slot

@Suppress("unused")
object AutoArmor : Module(
	name = "AutoArmor",
	description = "Automatically equips armor, and mends it with experience bottles",
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
	private val mendArmor by setting("Mend Armor", false, "Mends armor with Mending by throwing experience bottles at your feet. With Elytra Priority, for ElytraFly, elytras too and it lands away from players and mobs for it. Without, for HighwayTools, the build pauses meanwhile")
	private val allowShulker by setting("Allow Shulker", true, "Use the experience bottles you carry, loose or in a shulker") { mendArmor }
	private val allowEnderChest by setting("Allow Ender Chest", true, "With Elytra Priority, take a shulker of experience bottles from the ender chest when none are carried. HighwayTools restocks them itself") { mendArmor && allowShulker }
	private val mendAt by setting("Mend At", 5, 1..99, 1, "Durability at which the last carried piece of a kind gets mended, e.g. the last pair of leggings", "%") { mendArmor }
	private val mendTo by setting("Mend To", 100, 2..100, 1, "Durability to mend to", "%") { mendArmor }
	private val mobRadius by setting("Mob Free Radius", 32, 8..64, 4, "With Elytra Priority, no hostile mob may be this close to land and mend", " blocks") { mendArmor && elytraPriority }
	private val throwDelay by setting("Throw Delay", 4, 1..20, 1, "Ticks between experience bottles", " ticks") { mendArmor }
	private val mendSwapDelay by setting("Mend Swap Delay", 5, 1..20, 1, "Ticks between inventory clicks while mending", " ticks") { mendArmor }
	private val mendRetryDelay by setting("Mend Retry Delay", 30, 5..300, 5, "Wait after mending that left armor worn", " s") { mendArmor }

	private var tickedThisTick = false

	private var mendTask: Task<*>? = null
	private var mendRetryTicks = 0
	/** Mending failed, maybe leaving a shulker placed, so it's not tried again until AutoArmor or HighwayTools is started again. */
	private var mendFailed = false
	private var wasBuilding = false
	private var announced = false

	/** Whether experience bottles are being thrown, or a shulker or the ender chest is out for them. */
	val isMending get() = mendTask?.isActive == true

	/** Whether worn armor gets mended, which needs [hasBottles]. */
	val mendsArmor get() = isEnabled && mendArmor

	/** Whether [stack] is experience bottles, or a shulker with some. */
	fun hasBottles(stack: ItemStack) = bottles(stack) > 0

	/** How many experience bottles [stack] is, or a shulker holds. */
	fun bottles(stack: ItemStack) = when {
		stack.item == Items.EXPERIENCE_BOTTLE -> stack.count
		stack.item in ItemUtils.shulkerBoxes -> stack.shulkerBoxContents.sumOf { if (it.item == Items.EXPERIENCE_BOTTLE) it.count else 0 }
		else -> 0
	}

	var overriddenElytraPriority: Boolean? = null

	/** The better piece first: one that's not about to break, then elytra priority, armor, toughness, enchantments, worn or in the hotbar. */
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
			// Worn too, else an equal piece in the hotbar is put on and the worn one, now in the hotbar, back again and again.
			if (slot.index !in 9..35) 1.0 else 0.0,
		)
	}

	private fun compareScores(a: DoubleArray, b: DoubleArray): Int {
		for (i in a.indices) a[i].compareTo(b[i]).let { if (it != 0) return it }
		return 0
	}

	init {
		setDefaultAutomationConfig()
			.withEdits {
				hideAllExcept(::inventoryConfig, ::breakConfig, ::interactConfig, ::hotbarConfig, ::rotationConfig, ::buildConfig)
			}

		onEnable { mendFailed = false }

		onDisable {
			FlightStop.release(this@AutoArmor)
			// Mending first picks its shulker back up, so stopping never leaves one behind.
			mendTask.stopSafely()
			if (!isMending) endMend()
		}

		listen<TickEvent.Pre> {
			if (!tickedThisTick) tick()
			if (mendRetryTicks > 0) mendRetryTicks--
			// Starting the highway again is a fresh start for mending too.
			if (HighwayTools.isBuilding && !wasBuilding) mendFailed = false
			wasBuilding = HighwayTools.isBuilding
			mendTask?.let {
				// Picks its shulker or the ender chest back up, so nobody can take it.
				if (it.isActive && hasDanger()) it.stopSafely()
				if (it.isActive) return@listen FlightStop.hold(this@AutoArmor)
				// Ended without its callbacks, e.g. cancelled.
				endMend()
			}
			val kinds = wornKinds()
			val needed = kinds.isNotEmpty() && (carriesBottles() || canUseEnderChest())
			if (needed && elytraPriority && !announced) info("Worn ${if (kinds.contains(ELYTRA)) "elytras" else "armor"}, looking for a safe spot to mend")
			announced = needed
			if (!needed) return@listen FlightStop.release(this@AutoArmor)
			if (!elytraPriority) {
				// Not while a restock has a container out or the aura fights.
				if (!HighwayTools.isBusy && !waitsForPlayer()) startMend(kinds)
				return@listen
			}
			// Flies on until it's safe here, then stands still to place a shulker. With the elytras broken it walks on.
			if (with(FlightStop) { land(this@AutoArmor, mobRadius, walkOn = true) }) startMend(kinds)
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

	/**
	 * The kinds whose last carried piece is worn and can be mended, e.g. every carried pair of leggings.
	 * With Elytra Priority elytras, helmets, leggings and boots, since the chest slot is for the elytra. Else all armor.
	 */
	private fun SafeContext.wornKinds(): List<(ItemStack) -> Boolean> {
		if (!mendArmor || mendRetryTicks > 0 || mendFailed) return emptyList()
		val kinds = if (elytraPriority) listOf(ELYTRA, HEAD, LEGS, FEET) else listOf(HEAD, CHEST, LEGS, FEET)
		return kinds.filter { allWorn(it) }
	}

	private fun SafeContext.allWorn(kind: (ItemStack) -> Boolean): Boolean {
		val items = (player.inventory.mainStacks + player.offHandStack + MEND_SLOTS.map { player.getEquippedStack(it) })
			.filter { kind(it) && it.isDamageable }
		return items.none { it.durabilityFraction >= mendAt / 100f } && items.any { it.getEnchantment(Enchantments.MENDING) > 0 && it.durabilityFraction < mendTo / 100f }
	}

	private fun SafeContext.carriesBottles() = allowShulker && player.inventory.mainStacks.any(::hasBottles)

	private fun SafeContext.canUseEnderChest() = elytraPriority && allowShulker && allowEnderChest &&
		player.inventory.mainStacks.sumOf { if (isEnderChest(it)) it.count else 0 } >= MIN_ENDER_CHESTS

	/** For ElytraFly another player in render distance or a hostile mob close, for HighwayTools a player close enough to take a placed shulker. */
	private fun SafeContext.hasDanger() =
		if (elytraPriority) with(FlightStop) { hasDanger(mobRadius) } else waitsForPlayer()

	private fun SafeContext.startMend(kinds: List<(ItemStack) -> Boolean>) {
		// A build that can't be paused now is busy with something else.
		if (HighwayTools.isBuilding && !HighwayTools.pauseFor(this@AutoArmor)) return

		fun mend() = MendArmorTask(
			mendTo = mendTo / 100f,
			throwDelay = throwDelay,
			swapDelay = mendSwapDelay,
			automated = this@AutoArmor,
			// The aura goes first, the mend carries on once the mob is dead.
			holdWhile = { HighwayTools.isFighting },
			items = { stack -> kinds.any { it(stack) } },
		)
		val bottles = EnderChestRestockTask.Want(
			"experience bottles", 1, ::bottles,
			isLow = { !carriesBottles() },
			isMet = { carriesBottles() },
		)
		// Mended next to the ender chest before it's broken again.
		val task = if (carriesBottles()) mend() else EnderChestRestockTask(listOf(bottles), mendSwapDelay, this@AutoArmor, beforeBreaking = { mend() })
		mendTask = task.onCompletion {
			endMend()
			if (kinds.any { allWorn(it) }) mendRetryTicks = mendRetryDelay * 20
		}.onFailure {
			logError("Mending failed: ${it.message}. Not trying again until AutoArmor or HighwayTools is started again, HighwayTools is stopped")
			mendFailed = true
			HighwayTools.disable()
		}.run()
	}

	private fun endMend() {
		mendTask = null
		FlightStop.release(this)
		HighwayTools.resume(this)
	}

	private val MEND_SLOTS = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
	private val ELYTRA: (ItemStack) -> Boolean = { it.isOf(Items.ELYTRA) }
	private val HEAD: (ItemStack) -> Boolean = { it.isIn(ItemTags.HEAD_ARMOR) }
	private val CHEST: (ItemStack) -> Boolean = { it.isIn(ItemTags.CHEST_ARMOR) }
	private val LEGS: (ItemStack) -> Boolean = { it.isIn(ItemTags.LEG_ARMOR) }
	private val FEET: (ItemStack) -> Boolean = { it.isIn(ItemTags.FOOT_ARMOR) }

	private enum class Protection(val enchant: RegistryKey<Enchantment>) {
		Protection(Enchantments.PROTECTION),
		BlastProtection(Enchantments.BLAST_PROTECTION),
		ProjectileProtection(Enchantments.PROJECTILE_PROTECTION),
		FireProtection(Enchantments.FIRE_PROTECTION);
	}
}