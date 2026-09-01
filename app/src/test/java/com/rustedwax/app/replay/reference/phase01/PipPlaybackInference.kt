package com.rustedwax.app.replay.reference.phase01

// GENERATED — do not edit. See <redacted-private-path>.


/**
 * Wall-clock credit for a Short whose seekbar has gone away, while evidence
 * still says it is playing.
 *
 * ## Why this is inference and not measurement
 *
 * In picture-in-picture YouTube publishes no progress at all: no `SeekBar` under
 * `reel_time_bar`, no time text in the window, and a MediaSession reporting
 * `STATE_NONE` with `position=0` (`<redacted-private-path>` §4.1). Every direct
 * source is gone, so the only honest options are to credit nothing or to credit
 * elapsed time on evidence that playback is continuing. This does the latter,
 * and says so in every line it produces.
 *
 * ## What the evidence is, and why it takes two signals
 *
 * `AudioManager.getActivePlaybackConfigurations()` is public and tells us
 * whether *some* app has `USAGE_MEDIA` audio started. It cannot say whose:
 * `AudioPlaybackConfiguration` exposes only `getAudioAttributes()` and
 * `getAudioDeviceInfo()` to a normal app, and the framework hands out anonymized
 * copies with the client uid stripped. On its own it would credit another app's
 * podcast to a YouTube Short.
 *
 * `UsageStatsManager` supplies the missing half. It names packages, and a PiP
 * window keeps YouTube's activity `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED` rather
 * than `ACTIVITY_STOPPED`, so "YouTube still has a visible window" is
 * attributable in a way the audio list is not.
 *
 * Neither alone is enough. Together they mean *YouTube is on screen and media
 * audio is playing*, which is as close to "this Short is playing" as the public
 * API allows. The residual false positive is stated plainly: another app playing
 * audio while a YouTube PiP window sits paused looks identical, and would be
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
 * Speed is 1× unless YouTube's own speed chip is on screen saying otherwise.
 * Measured 2026-08-08: holding a Short to play it at 2× strips the overlay — so
 * nothing can be measured — and leaves a visible `2x` label behind. Crediting
 * wall clock through that state under-counted every such listen by half, which
 * is a Short watched in full finalizing at ~50% and never scrobbling. Where no
 * chip is visible the assumption is still 1×, and still recorded rather than
 * hidden.
 */
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
	val exhausted: Boolean get() = durationMs - measuredMs - inferredMs <= 0

	/**
	 * One observation of the world while the progress surface is gone.
	 *
	 * @param playing YouTube has a visible window *and* media audio is started.
	 * @param rate content consumed per second of wall clock, from YouTube's own
	 * visible speed chip. 1.0 when nothing published one.
	 * @return millis newly credited by this observation, for logging.
	 */
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
