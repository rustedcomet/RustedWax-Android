package com.rustedwax.core

/**
 * Wall-clock credit for a Short whose seekbar is absent or stalled, while
 * evidence still says it is playing.
 *
 * ## Why this is inference and not measurement
 *
 * In picture-in-picture source publishes no progress at all: no `SeekBar` under
 * `reel_time_bar`, no time text in the window, and a transport session reporting
 * `STATE_NONE` with `position=0` (`<redacted-private-path>` §4.1). Every direct
 * source is gone, so the only honest options are to credit nothing or to credit
 * elapsed time on evidence that playback is continuing. On the Galaxy A36,
 * source also left a structurally valid seekbar frozen for 63–104 seconds while
 * the Short visibly continued. Both cases require the latter, and every line it
 * produces says the time was inferred.
 *
 * ## What the evidence is, and why it takes two signals
 *
 * `AudioManager.getActivePlaybackConfigurations()` is public and tells us
 * whether *some* app has `USAGE_MEDIA` audio started. It cannot say whose:
 * `AudioPlaybackConfiguration` exposes only `getAudioAttributes()` and
 * `getAudioDeviceInfo()` to a normal app, and the framework hands out anonymized
 * copies with the client uid stripped. On its own it would credit another app's
 * podcast to a source Short.
 *
 * `UsageStatsManager` supplies the missing half. It names packages, and a PiP
 * window keeps source's activity `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED` rather
 * than `ACTIVITY_STOPPED`, so "source still has a visible window" is
 * attributable in a way the audio list is not.
 *
 * Neither alone is enough. Together they mean *source is on screen and media
 * audio is playing*, which is as close to "this Short is playing" as the public
 * API allows. The residual false positive is stated plainly: another app playing
 * audio while a source PiP window sits paused looks identical, and would be
 * credited. That is the accepted cost of counting PiP at all.
 *
 * ## The caps, which are not optional
 *
 * - Credit only advances between two consecutive observations that *both* say
 *   playing, so a gap in polling cannot be back-filled.
 * - A single step is capped at [MAX_STEP_MS]. A process frozen for ten minutes
 *   and then resumed must not wake up and credit ten minutes.
 * - Total credit is capped so measured + inferred can never exceed the Short's
 *   own duration. Shorts auto-loop, and without this a Short left in PiP would
 *   accumulate credit forever and reach any threshold.
 *
 * Speed is 1× unless source's own speed chip is on screen saying otherwise.
 * Measured 2026-08-08: holding a Short to play it at 2× strips the overlay — so
 * nothing can be measured — and leaves a visible `2x` label behind. Crediting
 * wall clock through that state under-counted every such listen by half, which
 * is a Short watched in full finalizing at ~50% and never scrobbling. Where no
 * chip is visible the assumption is still 1×, and still recorded rather than
 * hidden.
 *
 * ## Why it is a value and not an accumulator
 *
 * This used to be a mutable object, and `PlaybackReducer` carried one inside
 * `ListenState` — which meant `reduce(state, input)` mutated the state it was
 * handed. A reducer whose input changes underneath it is not deterministic in
 * the sense the audit's Phase 3 asks for: replaying the same event twice gave
 * two different answers, and the second one was the wrong one. Every transition
 * is now a new value, so the anchor and the running credit can only advance by
 * being written into the next state.
 */
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

	/**
	 * True once there is nothing left to credit — measured plus inferred already
	 * fills the Short's own length.
	 *
	 * Load-bearing for *ending* the track, not just for capping it. While the
	 * inference is live the caller holds the end-of-track grace open, because the
	 * Short demonstrably has not gone anywhere. Once the cap is reached that stops
	 * being true: the listen is complete, nothing further can be earned, and
	 * continuing to hold the grace open means the Short never finalizes and never
	 * scrobbles at all. Measured 2026-08-06 — a Short left playing in
	 * picture-in-picture accrued to its cap and then hung there indefinitely.
	 */
	val exhausted: Boolean get() = durationMs - measuredMs - credited <= 0

	/**
	 * One observation of the world while direct progress is absent or stalled.
	 *
	 * @param playing source has a visible window *and* media audio is started.
	 * @param rate content consumed per second of wall clock, from source's own
	 * visible speed chip. 1.0 when nothing published one.
	 * @return the state after this observation and what it credited.
	 */
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
