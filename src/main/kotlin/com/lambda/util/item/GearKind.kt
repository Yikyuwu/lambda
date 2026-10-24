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

package com.lambda.util.item

import com.lambda.util.NamedEnum
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.tag.ItemTags
import net.minecraft.registry.tag.TagKey

/** A kind of tool, weapon or armor piece, of any material, with its diamond and netherite versions. */
enum class GearKind(val category: Category, val tag: TagKey<Item>, val diamond: Item, val netherite: Item) {
    Pickaxe(Category.Tool, ItemTags.PICKAXES, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE),
    Axe(Category.Tool, ItemTags.AXES, Items.DIAMOND_AXE, Items.NETHERITE_AXE),
    Shovel(Category.Tool, ItemTags.SHOVELS, Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL),
    Hoe(Category.Tool, ItemTags.HOES, Items.DIAMOND_HOE, Items.NETHERITE_HOE),
    Sword(Category.Weapon, ItemTags.SWORDS, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD),
    Spear(Category.Weapon, ItemTags.SPEARS, Items.DIAMOND_SPEAR, Items.NETHERITE_SPEAR),
    Helmet(Category.Armor, ItemTags.HEAD_ARMOR, Items.DIAMOND_HELMET, Items.NETHERITE_HELMET),
    Chestplate(Category.Armor, ItemTags.CHEST_ARMOR, Items.DIAMOND_CHESTPLATE, Items.NETHERITE_CHESTPLATE),
    Leggings(Category.Armor, ItemTags.LEG_ARMOR, Items.DIAMOND_LEGGINGS, Items.NETHERITE_LEGGINGS),
    Boots(Category.Armor, ItemTags.FOOT_ARMOR, Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS);

    enum class Category { Tool, Weapon, Armor }

    fun matches(stack: ItemStack) = stack.isIn(tag)
}

/** A [GearKind] or none, for a setting that picks one. */
enum class GearKindChoice(override val displayName: String, val kind: GearKind?) : NamedEnum {
    None("None", null),
    Pickaxe("Any Pickaxe", GearKind.Pickaxe),
    Axe("Any Axe", GearKind.Axe),
    Shovel("Any Shovel", GearKind.Shovel),
    Hoe("Any Hoe", GearKind.Hoe),
    Sword("Any Sword", GearKind.Sword),
    Spear("Any Spear", GearKind.Spear),
    Helmet("Any Helmet", GearKind.Helmet),
    Chestplate("Any Chestplate", GearKind.Chestplate),
    Leggings("Any Leggings", GearKind.Leggings),
    Boots("Any Boots", GearKind.Boots),
}
