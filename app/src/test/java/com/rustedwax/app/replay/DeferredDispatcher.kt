package com.rustedwax.app.replay

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * Holds the engine's finalization coroutine until a scenario says go.
 *
 * `FinalizationRuntime.onTrackFinalized` does its cheap rejections on the caller's
 * thread and launches the rest — resolution, enrichment, rules, dedup,
 * dispatch — onto its own scope. The first thing that launched block does is
 * re-check the source epoch, because on a device the user can turn a surface
 * off in the gap. Under an inline dispatcher there is no gap, so that guard is
 * unreachable and the branch would go untested precisely because it is the one
 * that only exists for a race.
 *
 * This makes the gap real without making it a race: work queues here, the
 * scenario changes whatever the guard is about, and [drain] then runs it.
 * Deterministic, no sleeps, no timeouts.
 */
class DeferredDispatcher : CoroutineDispatcher() {

	private val pending = ArrayDeque<Runnable>()

	override fun dispatch(context: CoroutineContext, block: Runnable) {
		pending.addLast(block)
	}

	val queued: Int get() = pending.size

	/** Run one queued boundary and leave work it schedules waiting. */
	fun runNext() {
		pending.removeFirst().run()
	}

	/** Run everything queued, including anything queued while draining. */
	fun drain() {
		while (pending.isNotEmpty()) pending.removeFirst().run()
	}
}
