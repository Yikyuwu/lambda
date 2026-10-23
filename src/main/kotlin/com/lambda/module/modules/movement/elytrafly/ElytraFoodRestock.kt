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

package com.lambda.module.modules.movement.elytrafly

import com.lambda.config.Config
import com.lambda.config.ConfigBlock
import com.lambda.config.blocks.HotbarConfig
import com.lambda.config.blocks.EatConfig.Companion.isHungerFood
import com.lambda.context.Automated
import com.lambda.context.SafeContext
import com.lambda.event.events.TickEvent
import com.lambda.interaction.handlers.FlightStop
import com.lambda.task.RootTask.run
import com.lambda.task.Task
import com.lambda.task.tasks.EnderChestRestockTask
import com.lambda.task.tasks.EnderChestRestockTask.Companion.MIN_ENDER_CHESTS
import com.lambda.task.tasks.EnderChestRestockTask.Companion.isEnderChest
import com.lambda.task.tasks.StationTask.Companion.stopSafely
import com.lambda.task.tasks.TakeFromShulkerTask
import com.lambda.util.CommunicationUtils.info
import com.lambda.util.CommunicationUtils.logError
import com.lambda.util.item.ItemStackUtils.shulkerBoxContents
import com.lambda.util.item.ItemUtils
import net.minecraft.item.ItemStack

/**
 * Lands to take food out of a carried shulker, or out of the ender chest when none has any, once it runs low, like
 * HighwayTools does. It lands like AutoArmor's mending with [FlightStop], at a safe spot with nobody around.
 */
class ElytraFoodRestock(override val c: Config, automated: Automated) : ConfigBlock, Automated by automated {
    private val enabled by c.setting("Restock Food", false, "Lands to take food out of a carried shulker or the ender chest once it runs low, at a safe spot with nobody around")
    private val minFood by c.setting("Min Food", 16, 1..64, 1, "Food left at which more is taken, up to a stack") { enabled }
    private val allowShulker by c.setting("Allow Shulker", true, "Take food out of a carried shulker") { enabled }
    private val allowEnderChest by c.setting("Allow Ender Chest", true, "Take food or a shulker with food from the ender chest when no carried shulker has any") { enabled }
    private val mobRadius by c.setting("Mob Free Radius", 32, 8..64, 4, "No hostile mob may be this close to land and restock", " blocks") { enabled }
    private val retryDelay by c.setting("Retry Delay", 60, 10..600, 10, "Wait after a restock that found too little food", " s") { enabled }

    /**
     * ElytraFly's settings, but swapping the hotbar at the usual stage only. ElytraFly swaps at every stage for its
     * elytras and fireworks, and placing the ender chest like that fights other hotbar users, flipping between the
     * pickaxe and the ender chest without ever placing it.
     */
    private val tasks: Automated = object : Automated by automated {
        override val hotbarConfig = object : HotbarConfig by automated.hotbarConfig {
            override val tickStageMask: Collection<TickEvent> = setOf(TickEvent.Input.Post)
        }
    }

    private var task: Task<*>? = null
    private var retryTicks = 0
    private var announced = false
    /** A restock failed, so it's not tried again until ElytraFly is turned on again. */
    private var failed = false

    fun SafeContext.tick() {
        if (retryTicks > 0) retryTicks--
        task?.let {
            // Picks its shulker or the ender chest back up, so nobody can take it.
            if (it.isActive && with(FlightStop) { hasDanger(mobRadius) }) it.stopSafely()
            if (it.isActive) return FlightStop.hold(this@ElytraFoodRestock)
            // Ended without its callbacks, e.g. cancelled.
            end()
        }
        val needed = enabled && !failed && retryTicks <= 0 && isLow() && (carriesFoodShulker() || canUseEnderChest())
        if (needed && !announced) info("Food runs low, looking for a safe spot to restock")
        announced = needed
        if (!needed) return FlightStop.release(this@ElytraFoodRestock)
        if (with(FlightStop) { land(this@ElytraFoodRestock, mobRadius) }) start()
    }

    fun reset() {
        failed = false
        retryTicks = 0
    }

    /** Stops a restock, which first picks its shulker or the ender chest back up. */
    fun stop() {
        task.stopSafely()
        if (task?.isActive != true) end()
    }

    private fun isFood(stack: ItemStack) = eatConfig.isHungerFood(stack.item)

    /** How much food [stack] is, or a shulker holds when those may be used. */
    private fun foodIn(stack: ItemStack) = when {
        isFood(stack) -> stack.count
        allowShulker && stack.item in ItemUtils.shulkerBoxes -> stack.shulkerBoxContents.sumOf { if (isFood(it)) it.count else 0 }
        else -> 0
    }

    /** Also the offhand, where food is often kept. */
    private fun SafeContext.carriedFood() = (player.inventory.mainStacks + player.offHandStack).sumOf { if (isFood(it)) it.count else 0 }

    private fun SafeContext.isLow() = carriedFood() <= minFood

    private fun SafeContext.carriesFoodShulker() =
        allowShulker && player.inventory.mainStacks.any { it.item in ItemUtils.shulkerBoxes && foodIn(it) > 0 }

    private fun SafeContext.canUseEnderChest() =
        allowEnderChest && player.inventory.mainStacks.sumOf { if (isEnderChest(it)) it.count else 0 } >= MIN_ENDER_CHESTS

    private fun SafeContext.start() {
        fun takeFromShulker() = TakeFromShulkerTask("food", ::isFood, FOOD_TARGET, SWAP_DELAY, tasks)
        val food = EnderChestRestockTask.Want(
            "food", 2, ::foodIn,
            isLow = { isLow() && !carriesFoodShulker() },
            isMet = { carriedFood() >= FOOD_TARGET || carriesFoodShulker() },
        )
        // A food shulker from the ender chest is emptied next to it before it's broken again.
        task = (if (carriesFoodShulker()) takeFromShulker()
        else EnderChestRestockTask(listOf(food), SWAP_DELAY, tasks, beforeBreaking = { if (carriesFoodShulker()) takeFromShulker() else null }))
            .onCompletion {
                end()
                if (isLow()) retryTicks = retryDelay * 20
            }.onFailure {
                logError("Restocking food failed: ${it.message}. Not trying again until ElytraFly is turned on again")
                failed = true
                end()
            }.run()
    }

    private fun end() {
        task = null
        FlightStop.release(this)
    }

    private companion object {
        const val FOOD_TARGET = 64
        const val SWAP_DELAY = 5
    }
}
