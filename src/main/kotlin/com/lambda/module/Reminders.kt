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

package com.lambda.module

import com.lambda.util.CommunicationUtils.warn

/**
 * Warns about the [modules] that are off whose "Remind Me" setting is on, e.g. AutoTotem when HighwayTools starts.
 * Each is the module and that setting.
 */
fun Module.remindIfOff(doing: String, vararg modules: Pair<Module, Boolean>) {
	val off = modules.filter { (module, remind) -> remind && !module.isEnabled }.map { it.first }
	if (off.isEmpty()) return
	val one = off.size == 1
	warn("${off.joinToString { it.name }} ${if (one) "is" else "are"} off, turn ${if (one) "it" else "them"} on $doing")
}
