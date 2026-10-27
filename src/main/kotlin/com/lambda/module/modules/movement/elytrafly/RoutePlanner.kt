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

import com.lambda.Lambda.LOG
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The route thread of one of ElytraFly's modes, see [ObstaclePassingMode]: a single daemon thread a bit below normal
 * priority, made the first time a job is submitted, running at most one job at a time. Each job has a generation, see
 * [submit]: [cancel] and the next [submit] move it on, the job sees it's not [isCurrent] any more and stops, what it
 * publishes after is let go of by the game thread. A job's exception is logged, never thrown at the game.
 */
class RoutePlanner(private val name: String) {
	private val generation = AtomicLong()
	private val pending = AtomicReference<Future<*>?>()
	private val executor: ExecutorService by lazy {
		Executors.newSingleThreadExecutor { runnable ->
			Thread(runnable, name).apply {
				isDaemon = true
				priority = Thread.NORM_PRIORITY - 1
			}
		}
	}

	/** Whether [gen] is the generation of the job going on, nothing cancelled or submitted since. */
	fun isCurrent(gen: Long) = generation.get() == gen

	/** Whether a job is queued or running. */
	val busy get() = pending.get()?.isDone == false

	/** Stops the job going on, if any: the one running stops at its next look at [isCurrent], one queued doesn't start. */
	fun cancel() {
		generation.incrementAndGet()
		pending.getAndSet(null)?.cancel(false)
	}

	/** Cancels the job going on and runs [job] with its new generation on the route thread, returns that generation. */
	fun submit(job: (gen: Long) -> Unit): Long {
		val gen = generation.incrementAndGet()
		pending.getAndSet(null)?.cancel(false)
		pending.set(executor.submit {
			if (!isCurrent(gen)) return@submit
			try {
				job(gen)
			} catch (e: Throwable) {
				LOG.error("[ElytraFly] $name: the route search failed", e)
			}
		})
		return gen
	}
}
