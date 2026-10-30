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

package com.lambda.interaction.construction.simulation.result

import com.lambda.graphics.mc.RenderBuilder
import net.minecraft.util.math.BlockPos
import java.awt.Color

/**
 * Represents a [BuildResult] that can be rendered in-game.
 */
interface Drawable {
    fun RenderBuilder.render()
}

/** A block the build still has to break, in red, or place a block at, in blue, drawn whole. */
class PlannedBlock(private val pos: BlockPos, private val breaking: Boolean) : Drawable {
    override fun RenderBuilder.render() {
        box(pos) {
            if (breaking) colors(BREAK_FILL, BREAK_OUTLINE) else colors(PLACE_FILL, PLACE_OUTLINE)
        }
    }

    companion object {
        val BREAK_FILL = Color(222, 0, 0, 25)
        val BREAK_OUTLINE = Color(222, 0, 0, 100)
        val PLACE_FILL = Color(35, 188, 254, 50)
        val PLACE_OUTLINE = Color(35, 188, 254, 100)
    }
}
