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
import com.lambda.config.blocks.EatConfig
import com.lambda.config.blocks.EatConfig.Companion.reasonEating
import com.lambda.config.editSetting
import com.lambda.config.hideAllExcept
import com.lambda.config.withEdits
import com.lambda.event.events.TickEvent
import com.lambda.event.listener.SafeListener.Companion.listen
import com.lambda.module.Module
import com.lambda.module.tag.ModuleTag
import com.lambda.task.RootTask.run
import com.lambda.task.Task
import com.lambda.task.tasks.EatTask.Companion.eat
import com.lambda.task.wrappers.thenAction
import com.lambda.threading.runSafeAutomated

@Suppress("unused")
object AutoEat : Module(
    name = "AutoEat",
    description = "Eats food when you are hungry",
    tag = ModuleTag.PLAYER,
) {
    val remindMe by setting("Remind Me", true, "Reminds you when you start AutoMend or ElytraFly and this is off")

    private var eatTask: Task<*>? = null

    /** Eating until done, which can take several items in a row. */
    val isEating get() = eatTask?.isActive == true

    init {
		setDefaultAutomationConfig()
            .withEdits {
			    hideAllExcept(::eatConfig)
			    eatConfig.apply {
				    ::minFoodLevel.editSetting { defaultValue(8) }
				    ::saturated.editSetting { defaultValue(EatConfig.Saturation.EatUntilFull) }
			    }
		    }

        listen<TickEvent.Pre> {
            val reason = runSafeAutomated { reasonEating() }
            if (isEating || !reason.shouldEat()) return@listen

            eatTask = eat().thenAction { eatTask = null }.run()
        }

        onDisable {
            eatTask?.cancel()
            eatTask = null
        }
    }
}
