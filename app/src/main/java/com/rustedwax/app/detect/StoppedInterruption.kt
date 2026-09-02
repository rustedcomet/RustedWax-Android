package com.rustedwax.app.detect

import kotlin.math.abs

/**
 * Whether a native `STATE_STOPPED` that has outlived its replacement grace ends
 * the listen, or merely interrupts it.
 *
 * The transport itself cannot say. YouTube publishes the same `STOPPED` when the
 * user finishes with a video and when the screen goes off underneath one, so the
 * grace that waits for a metadata replacement finalizes both. Finalizing the
 * second kind splits one viewing into two sub-threshold fragments: the part
 * before the interruption is scored at whatever it had reached, and the resume
 * that follows starts from zero with no continuation left for it to claim.
 *
 * Display state alone cannot separate them: a genuine stop or media end may also
 * arrive while the display is off. The interruption path therefore requires the
 * additional reset-and-restore transport signature observed when the native
 * surface disappeared. Ambiguous stops fail closed and finalize normally.
 *
 * Holding the listen open costs nothing while it waits. A `STOPPED` transport
 * accrues no play time, arms no idle deadline and writes nothing; what it keeps
 * is the ability for the same item to continue into this listen rather than
 * against a fresh one. The hold is bounded so an interruption nobody returns
 * from still ends.
 */
object StoppedInterruption {

	/**
	 * How long a display-off `STOPPED` may hold a listen open.
	 *
	 * Deliberately the same fifteen minutes as
	 * [TrackProgressCarry.RESUMED_TTL_MS]: both are the same patience budget for
	 * the same event — a human stepped away from a listen that may still resume —
	 * and the continuation this hold exists to make reachable expires on that
	 * number anyway. A second, different constant would only decide which half of
	 * one mechanism gave up first.
	 */
	const val SCREEN_OFF_HOLD_CAP_MS = TrackProgressCarry.RESUMED_TTL_MS

	/**
	 * Whether this `STOPPED` should keep the listen open instead of ending it.
	 *
	 * [stoppedForMs] is measured from the transport's own `STOPPED` transition,
	 * not from the last check, so a long hold cannot outlive the cap by polling.
	 */
	fun holdsListenOpen(
		displayInteractive: Boolean,
		stoppedForMs: Long,
		surfaceDisappearanceConfirmed: Boolean,
		stoppedAtMs: Long?,
		durationMs: Long?,
	): Boolean =
		!displayInteractive &&
			surfaceDisappearanceConfirmed &&
			!finishedItem(stoppedAtMs, durationMs) &&
			stoppedForMs < SCREEN_OFF_HOLD_CAP_MS

	/**
	 * The transport signature observed on the reproduced lock: the surface first
	 * disappeared to position zero, then the same STOPPED transport restored the
	 * position it had immediately before that disappearance.
	 *
	 * The bounds are the carry bounds, not a new timing or distance heuristic. A
	 * lone STOPPED, a reset that never returns, or an unrelated later position is
	 * ambiguous and deliberately does not satisfy this evidence gate.
	 */
	fun resetCandidate(previousPositionMs: Long?, stoppedPositionMs: Long?): Long? {
		val previous = previousPositionMs ?: return null
		val stopped = stoppedPositionMs ?: return null
		if (previous < TrackProgressCarry.RESUME_MIN_POSITION_MS) return null
		if (stopped !in 0..TrackProgressCarry.RESUME_WINDOW_MS) return null
		return previous.takeIf { previous - stopped > TrackProgressCarry.RESUME_WINDOW_MS }
	}

	fun confirmsSurfaceDisappearance(resetFromPositionMs: Long?, stoppedPositionMs: Long?): Boolean {
		val resetFrom = resetFromPositionMs ?: return false
		val stopped = stoppedPositionMs ?: return false
		return abs(stopped - resetFrom) <= TrackProgressCarry.RESUME_WINDOW_MS
	}

	/** A position at the item's own boundary is an end, never an interruption. */
	private fun finishedItem(stoppedAtMs: Long?, durationMs: Long?): Boolean {
		val stoppedAt = stoppedAtMs ?: return false
		val duration = durationMs?.takeIf { it > 0 } ?: return false
		return stoppedAt >= duration - TrackProgressCarry.RESUME_WINDOW_MS
	}
}
