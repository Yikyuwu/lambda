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

import com.lambda.util.Describable
import com.lambda.util.NamedEnum
import net.minecraft.item.Item
import net.minecraft.item.Items

/** Which tools a feature works on, for a setting. */
enum class ToolFilter(
    override val displayName: String,
    override val description: String,
    val matches: (Item) -> Boolean,
) : NamedEnum, Describable {
    NetheritePickaxe("Netherite Pickaxe", "Only netherite pickaxes", { it == Items.NETHERITE_PICKAXE }),
    DiamondPickaxe("Diamond Pickaxe", "Only diamond pickaxes", { it == Items.DIAMOND_PICKAXE }),
    AnyPickaxe("Any Pickaxe", "Pickaxes of any material", { it in ItemUtils.pickaxes }),
    AnyTool("Any Tool", "Any tool except swords, so the one you hit with stays", { it in ItemUtils.tools && it !in ItemUtils.swords }),
}
