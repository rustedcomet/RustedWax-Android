package com.rustedwax.app.detect

import com.rustedwax.core.*

class IdleFinalization(
	private val scheduler: Scheduler,
	/** The listen as it stands right now. */
	private val listenState: () -> ListenState,
	private val elapsedRealtimeMs: () -> Long,
	/**
	 * The best length known for this listen, or null when none is.
	 *
	 * Re-read at arming *and* at expiry, because the whole point of the notify
	 * path is that this answer changes while a deadline is pending.
	 */
	private val durationMs: () -> Long?,
	/** Hand the expiry to the reducer, which decides whether it means anything. */
	private val onDeadline: (durationMs: Long?, elapsedRealtimeMs: Long) -> Unit,
) {

	/**
	 * Somewhere to run something later.
	 *
	 * Deliberately without a cancel: cancellation here is by token, so a
	 * scheduler only has to be able to run a block after a delay. A `Handler`
	 * satisfies it, and so does a list in a test.
	 */
	fun interface Scheduler {
		fun postDelayed(delayMs: Long, action: () -> Unit)
	}

	/**
	 * Which pending expiry is still the current one.
	 *
	 * Bumped by every arm, so an expiry scheduled by an earlier observation finds
	 * a token that no longer matches and does nothing. This is why [Scheduler]
	 * needs no cancel — and why a stale timer is a no-op rather than a race.
	 */
	private var token: Long = 0

	/** Whether a deadline is currently pending. Diagnostics and tests only. */
	var armed: Boolean = false
		private set

	/**
	 * Re-derive the deadline from the listen as it stands.
	 *
	 * Called after every observation, so any transport or metadata event pushes
	 * the deadline out; at construction, so a session that was already playing is
	 * bounded from the start; and when the video's page arrives, so a length
	 * learned late replaces the ceiling with a precise deadline.
	 *
	 * A null delay means there is nothing to wait for — the listen is over, the
	 * transport is not playing, another surface owns it — and any pending expiry
	 * is dropped along with it.
	 */
	fun rearm() {
		token++
		val pending = token
		armed = false
		val delay = listenState().idleFinalizeDelayMs(
			elapsedRealtimeMs = elapsedRealtimeMs(),
			durationMs = durationMs(),
		) ?: return
		armed = true
		scheduler.postDelayed(delay) {
			if (pending != token) return@postDelayed
			armed = false
			onDeadline(durationMs(), elapsedRealtimeMs())
		}
	}

	/** Drop any pending deadline without arming a new one. */
	fun cancel() {
		token++
		armed = false
	}
}
