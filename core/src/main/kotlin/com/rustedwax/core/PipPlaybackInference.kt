package com.rustedwax.core

data class PipPlaybackInference(
	/** Total length of the Short, from the foreground Shorts player. */
	private val durationMs: Long,
	/** Progress already accounted for before this inference stretch. */
	private val measuredMs: Long,
	/** Inferred wall-clock credited so far. Never includes [measuredMs]. */
	val credited: Long = 0,
	/**
	 * When the last observation that said "playing" was taken, or null when the
	 * anchor has been dropped.
	 *
	 * Dropping it is how a pause is prevented from being back-filled, so it is
	 * part of the value rather than a detail: a caller that keeps the old value
	 * after a paused observation keeps the old anchor too, which is the bug.
	 */
	private val lastPlayingAtMillis: Long? = null,
) {

	/**
	 * The state after one observation, and what that observation was worth.
	 *
	 * Both halves are returned because both are used: the caller installs
	 * [next] and logs [creditedMs].
	 */
	data class Step(val next: PipPlaybackInference, val creditedMs: Long)

	val exhausted: Boolean get() = durationMs - measuredMs - credited <= 0

	fun observe(nowMillis: Long, playing: Boolean, rate: Double = 1.0): Step {
		if (!playing) {
			// Paused, or source's window is gone. Drop the anchor so the pause
			// cannot be back-filled when playback resumes.
			return Step(copy(lastPlayingAtMillis = null), 0)
		}
		val previous = lastPlayingAtMillis
		val anchored = copy(lastPlayingAtMillis = nowMillis)
		if (previous == null) return Step(anchored, 0)
		val elapsed = nowMillis - previous
		if (elapsed <= 0) return Step(anchored, 0)
		// The step is bounded in *wall clock* before the rate is applied, so a
		// stalled poll cannot be inflated twice over.
		val step = minOf(elapsed, MAX_STEP_MS)
		val consumed = (step * rate.coerceAtLeast(1.0)).toLong()
		val room = (durationMs - measuredMs - credited).coerceAtLeast(0)
		val credit = minOf(consumed, room)
		return Step(anchored.copy(credited = credited + credit), credit)
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
