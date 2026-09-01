package com.rustedwax.app.detect

import com.rustedwax.core.*
/**
 * The deadline that ends a listen whose source has stopped saying anything.
 *
 * ## What it is for
 *
 * Nothing ends a browser listen except a track change, a navigation or a closed
 * tab. Brave publishes `STATE_STOPPED` zero times in the entire retained field
 * log and its position reads `-1`, so a video that finishes while nobody is
 * watching keeps a `PLAYING` transport and an accruing clock indefinitely.
 * Measured 2026-08-12: one 3:06 song accrued 1h47m that way and went on-chain
 * twice.
 *
 * The *decision* — whether an expiry means the listen has ended — belongs to
 * [PlaybackReducer.reduce] and stays there, because only it knows how much of
 * the item has actually been consumed. What lives here is the timer: when to arm
 * one, what to do when a newer observation supersedes it, and how an expiry that
 * has been overtaken is discarded.
 *
 * ## Why it is its own class
 *
 * It was three fields and a `handler.postDelayed` inside the legacy `Watch`,
 * which is a private inner class over `MediaController` — so none of it could be
 * tested, and two arming defects survived precisely because of that:
 *
 * 1. **A listen that was already playing when the watcher was built never armed
 *    one at all.** Re-arming hung off the reduce path, and construction reduces
 *    nothing. Attaching to a session mid-playback — every listener-service
 *    rebuild, every process start with a video already going — produced exactly
 *    the unbounded listen this exists to prevent.
 * 2. **A length that arrived late never armed one either.** The precise deadline
 *    needs the item's length, and where the session publishes none it comes from
 *    the video's own cached page — which is fetched *asynchronously*. Nothing
 *    told the watcher when that landed, so a listen that could have had a
 *    precise deadline sat on the blunt silence ceiling, or on nothing at all.
 *
 * Both are now arming events like any other, and both have tests, because
 * [Scheduler] is a seam rather than a `Handler`.
 */
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
