package com.rustedwax.app.replay.reference.phase01

// Legacy replay reference; edit only with the corresponding parity tests.

class PipPlaybackInference(
	/** Total length of the Short, from the last foreground seekbar reading. */
	private val durationMs: Long,
	/** Progress genuinely measured from the seekbar before the surface went. */
	private val measuredMs: Long,
) {

	private var inferredMs: Long = 0
	private var lastPlayingAtMillis: Long? = null

	/** Inferred wall-clock credited so far. Never includes [measuredMs]. */
	val credited: Long get() = inferredMs

	val exhausted: Boolean get() = durationMs - measuredMs - inferredMs <= 0

	fun observe(nowMillis: Long, playing: Boolean, rate: Double = 1.0): Long {
		if (!playing) {
			// Paused, or YouTube's window is gone. Drop the anchor so the pause
			// cannot be back-filled when playback resumes.
			lastPlayingAtMillis = null
			return 0
		}
		val previous = lastPlayingAtMillis
		lastPlayingAtMillis = nowMillis
		if (previous == null) return 0
		val elapsed = nowMillis - previous
		if (elapsed <= 0) return 0
		// The step is bounded in *wall clock* before the rate is applied, so a
		// stalled poll cannot be inflated twice over.
		val step = minOf(elapsed, MAX_STEP_MS)
		val consumed = (step * rate.coerceAtLeast(1.0)).toLong()
		val room = (durationMs - measuredMs - inferredMs).coerceAtLeast(0)
		val credit = minOf(consumed, room)
		inferredMs += credit
		return credit
	}

	companion object {
		/**
		 * The largest single step any one observation may credit.
		 *
		 * The poll runs about once a second. Doze, a frozen process or a stalled
		 * binder can put an arbitrary gap between two observations, and without
		 * this the first poll after the gap would credit all of it as playback.
		 * Generous enough to absorb ordinary scheduling jitter, small enough that
		 * a long stall credits nothing meaningful.
		 */
		const val MAX_STEP_MS = 3_000L
	}
}
