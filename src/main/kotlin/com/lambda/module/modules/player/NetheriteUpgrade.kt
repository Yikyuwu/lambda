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

package com.lambda.module.modules.player

import com.lambda.config.automation.AutomationConfig.Companion.setDefaultAutomationConfig
import com.lambda.config.hideAllExcept
import com.lambda.config.settings.complex.orUnset
import com.lambda.config.withEdits
import com.lambda.context.SafeContext
import com.lambda.module.Module
import com.lambda.module.tag.ModuleTag
import com.lambda.task.RootTask.run
import com.lambda.task.Task
import com.lambda.task.tasks.UpgradeConfig
import com.lambda.task.tasks.UpgradeToolsTask
import com.lambda.util.ClickSetup
import com.lambda.util.CommunicationUtils.info
import com.lambda.util.CommunicationUtils.logError
import com.lambda.util.Describable
import com.lambda.util.NamedEnum
import com.lambda.util.world.blockSearch
import net.minecraft.block.SmithingTableBlock
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.util.math.BlockPos

@Suppress("unused")
object NetheriteUpgrade : Module(
    name = "NetheriteUpgrade",
    description = "Upgrades diamond items to netherite at a smithing table, fed from chests",
    tag = ModuleTag.PLAYER,
), UpgradeConfig {
    private val setup by setting("Setup", { clickSetup.toggle() }, "Set the chests and the smithing table by clicking them")
    private val reset by setting("Reset", { clickSetup.reset() }, "Set all positions back to 0 0 0, so it only upgrades what you carry")
    private var diamondChest by setting("Diamond Chest", BlockPos.ORIGIN, "Chest to take diamond items from. At 0 0 0 only carried ones are upgraded")
    private var templateChest by setting("Template Chest", BlockPos.ORIGIN, "Chest to take smithing templates from. At 0 0 0 only carried ones are used")
    private var ingotChest by setting("Ingot Chest", BlockPos.ORIGIN, "Chest to take netherite ingots from. At 0 0 0 only carried ones are used")
    private var dumpChest by setting("Dump Chest", BlockPos.ORIGIN, "Chest to put upgraded items into. At 0 0 0 they stay in the inventory")
    private var smithingTable by setting("Smithing Table", BlockPos.ORIGIN, "Smithing table to use. At 0 0 0 it uses the closest one")
    private val upgrade by setting("Upgrade", Upgrade.SpecificItem, "Diamond items to upgrade")
    private val item by setting("Item", UpgradeItem.Pickaxe, "The one kind of diamond item to upgrade") { upgrade == Upgrade.SpecificItem }
    override val maxItems by setting("Max Items", 27, 1..34, 1, "Most diamond items to take per trip")
    override val moveDelay by setting("Move Delay", 10, 2..40, 1, "Ticks between inventory clicks", " ticks")
    private val dumpDamaged by setting("Dump Damaged", true, "Also dump damaged upgraded items instead of keeping them")
    override val returnLeftovers by setting("Return Leftovers", true, "Put leftover templates and ingots back when done")

    enum class Kind { Tool, Weapon, Armor }

    enum class Upgrade(
        override val displayName: String,
        override val description: String,
        val kinds: Set<Kind>,
    ) : NamedEnum, Describable {
        Tools("Tools", "Pickaxes, axes, shovels and hoes", setOf(Kind.Tool)),
        Weapons("Weapons", "Swords and spears", setOf(Kind.Weapon)),
        Armor("Armor", "Helmets, chestplates, leggings and boots", setOf(Kind.Armor)),
        ToolsAndWeapons("Tools And Weapons", "All tools and weapons", setOf(Kind.Tool, Kind.Weapon)),
        ToolsAndArmor("Tools And Armor", "All tools and armor", setOf(Kind.Tool, Kind.Armor)),
        WeaponsAndArmor("Weapons And Armor", "All weapons and armor", setOf(Kind.Weapon, Kind.Armor)),
        Everything("Everything", "All tools, weapons and armor", Kind.entries.toSet()),
        SpecificItem("Specific Item", "Only the kind of item picked below", emptySet()),
    }

    enum class UpgradeItem(override val displayName: String, val kind: Kind, val diamond: Item, val netherite: Item) : NamedEnum {
        Pickaxe("Pickaxe", Kind.Tool, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE),
        Axe("Axe", Kind.Tool, Items.DIAMOND_AXE, Items.NETHERITE_AXE),
        Shovel("Shovel", Kind.Tool, Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL),
        Hoe("Hoe", Kind.Tool, Items.DIAMOND_HOE, Items.NETHERITE_HOE),
        Sword("Sword", Kind.Weapon, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD),
        Spear("Spear", Kind.Weapon, Items.DIAMOND_SPEAR, Items.NETHERITE_SPEAR),
        Helmet("Helmet", Kind.Armor, Items.DIAMOND_HELMET, Items.NETHERITE_HELMET),
        Chestplate("Chestplate", Kind.Armor, Items.DIAMOND_CHESTPLATE, Items.NETHERITE_CHESTPLATE),
        Leggings("Leggings", Kind.Armor, Items.DIAMOND_LEGGINGS, Items.NETHERITE_LEGGINGS),
        Boots("Boots", Kind.Armor, Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS),
    }

    /** The kinds of items picked by [upgrade] and [item]. */
    private val upgradeItems
        get() = if (upgrade == Upgrade.SpecificItem) listOf(item) else UpgradeItem.entries.filter { it.kind in upgrade.kinds }

    private var upgradeTask: Task<*>? = null

    private val clickSetup = ClickSetup(
        this,
        listOf(
            ClickSetup.Step("Diamond Chest", "the chest with the diamond items", ::diamondChest, ClickSetup.container),
            ClickSetup.Step("Template Chest", "the chest with the smithing templates", ::templateChest, ClickSetup.container),
            ClickSetup.Step("Ingot Chest", "the chest with the netherite ingots", ::ingotChest, ClickSetup.container),
            ClickSetup.Step("Dump Chest", "the chest for the upgraded items", ::dumpChest, ClickSetup.container),
            ClickSetup.Step("Smithing Table", "the smithing table", ::smithingTable, ClickSetup.block<SmithingTableBlock>()),
        ),
    )

    init {
        setDefaultAutomationConfig()
            .withEdits {
                hideAllExcept(::rotationConfig, ::inventoryConfig, ::interactConfig, ::buildConfig)
            }

        onEnable {
            clickSetup.cancel()
            val table = smithingTable.orUnset ?: findSmithingTable() ?: run {
                logError("No smithing table set or within $SEARCH_RADIUS blocks. Press Setup")
                return@onEnable disable()
            }
            val diamond = diamondChest.orUnset
            val template = templateChest.orUnset
            val ingot = ingotChest.orUnset
            val dump = dumpChest.orUnset
            val chests = listOfNotNull(diamond, template, ingot, dump)
            if (chests.toSet().size < chests.size) {
                logError("Two of the chests are the same one. Press Setup or Reset")
                return@onEnable disable()
            }
            fun BlockPos?.short() = this?.toShortString() ?: "none"
            info(
                if (chests.isEmpty()) "No chests set, upgrading what you carry at ${table.toShortString()}"
                else "Diamond ${diamond.short()}, templates ${template.short()}, ingots ${ingot.short()}, dump ${dump.short()}, table ${table.toShortString()}"
            )

            upgradeTask = UpgradeToolsTask(
                diamondChest = diamond,
                templateChest = template,
                ingotChest = ingot,
                dumpChest = dump,
                smithingTable = table,
                config = this@NetheriteUpgrade,
                automated = this@NetheriteUpgrade,
            ).onFailure {
                logError("NetheriteUpgrade stopped: ${it.message}")
            }.onCompletion {
                upgradeTask = null
                disable()
            }.run()
        }

        onDisable {
            upgradeTask?.cancel()
            upgradeTask = null
        }
    }

    override fun isDiamond(stack: ItemStack) = upgradeItems.any { stack.isOf(it.diamond) }

    override fun isUpgraded(stack: ItemStack) = upgradeItems.any { stack.isOf(it.netherite) }

    override fun shouldDump(stack: ItemStack) = isUpgraded(stack) && (dumpDamaged || !stack.isDamaged)

    private fun SafeContext.findSmithingTable(): BlockPos? =
        blockSearch(SEARCH_RADIUS) { _, state -> state.block is SmithingTableBlock }
            .keys.minByOrNull { it.getSquaredDistance(player.pos) }

    private const val SEARCH_RADIUS = 6
}
