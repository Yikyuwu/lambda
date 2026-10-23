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

package com.lambda.interaction.managers.rotating

/**
 * Waits for a rotation to reach the server before acting on it, e.g. an attack.
 * A request is done once its rotation is about to be sent, so the server has it one tick later.
 */
class RotationSettle {
    private var doneLastTick = false

    /** Call every tick with whether the request is done. True once the server has the rotation. */
    fun ready(done: Boolean): Boolean {
        val ready = done && doneLastTick
        doneLastTick = done
        return ready
    }

    fun reset() {
        doneLastTick = false
    }
}
