package com.rustedwax.core

/**
 * How much of a track was consumed, and how well that is known.
 *
 * ## Content, not seconds
 *
 * [playedMs] is *content consumed* — time in a playing state scaled by the
 * playback rate — because the threshold divides it by [durationMs], and both
 * sides of that division have to be content milliseconds. A 2026-07-29 field
 * session watched a 76 s trailer at 1.25× to 79% of the video and went on-chain
 * as 67%, because wall-clock had been measured instead. At 2× the same
 * arithmetic puts a fully watched video at 50% and it never scrobbles at all.
 *
 * ## Measured and inferred are kept apart
 *
 * [inferredPlayedMs] is the part of [playedMs] that was credited from
 * wall-clock rather than read off a progressing seekbar — either the
 * picture-in-picture case where the tree loses the seekbar, or a proven
 * foreground Short whose published seekbar value remains cached while paired
 * visible-window + active-audio evidence says playback continues. A scrobble
 * built on it is still a claim about a real listen; it is a weaker one, and
 * every layer downstream has to be able to say so rather than discovering the
 * difference too late.
 *
 * [progressSurfaceLost] is the separate statement that *nothing further can be
 * measured*. It is not "0% was played", and reporting it as such is what made a
 * PiP session indistinguishable from a parser bug for most of a day.
 */
data class PlaybackMeasurement(
	/** Content milliseconds consumed, speed-scaled. Includes [inferredPlayedMs]. */
	val playedMs: Long,
	/** The length this track established; null when the source never published one. */
	val durationMs: Long?,
	/** Last extrapolated transport position, when the source publishes one. */
	val positionMs: Long?,
	/**
	 * The first readable position seen for this listen, or null when the source
	 * never published one.
	 *
	 * Null means the whole measurement rests on wall clock, which keeps running
	 * whether or not anyone is still watching. Policy treats that as uncorroborated
	 * rather than as zero — see `ScrobbleRules.capForKind`.
	 */
	val firstObservedPositionMs: Long? = null,
	/** The part of [playedMs] that was inferred rather than read. */
	val inferredPlayedMs: Long = 0,
	/** Playback ran off the end of the item and back to its start. */
	val loopDetected: Boolean = false,
	/** Playing continued with no progress source of any kind left to read. */
	val progressSurfaceLost: Boolean = false,
	val isPlaying: Boolean = false,
	/** The transport state's own name, for the log and the diagnostics card. */
	val transportState: String,
	/** When this listen began, frozen once and carried across session restarts. */
	val startedAtEpochSec: Long,
	/**
	 * `playedMs / durationMs`, as the snapshot reported it.
	 *
	 * Carried rather than recomputed. The foreground-Shorts path deliberately
	 * publishes `0.0` where the length is unknown while another transport path
	 * publishes `null`, and that difference reaches `ScrobbleRules`. Deriving it
	 * here would quietly erase one of the two and change a decision.
	 */
	val percentPlayed: Double?,
) {
	init {
		require(playedMs >= 0) { "played content cannot be negative: $playedMs" }
		require(inferredPlayedMs >= 0) { "inferred content cannot be negative: $inferredPlayedMs" }
		// The whole point of keeping the two apart is that a caller can ask how
		// much of a listen was *read* rather than credited. If the inferred part
		// could exceed the total, that subtraction would go negative and the answer
		// would be a clamp rather than a measurement.
		require(inferredPlayedMs <= playedMs) {
			"inferred ${inferredPlayedMs}ms exceeds the ${playedMs}ms played"
		}
		require(durationMs == null || durationMs >= 0) { "a negative length: $durationMs" }
		require(percentPlayed == null || percentPlayed >= 0) {
			"a negative fraction played: $percentPlayed"
		}
		require(startedAtEpochSec >= 0) { "a listen cannot start before the epoch" }
		// The transport's own name reaches the log, the diagnostics card and
		// `ScrobbleRules`, which compares it. An empty one is a decision made on a
		// string nobody wrote.
		require(transportState.isNotBlank()) { "a listen must name its transport state" }
	}

	/** Content milliseconds that were actually read off a progress surface. */
	val measuredPlayedMs: Long get() = playedMs - inferredPlayedMs

	/** Progress-only threshold check; final eligibility still belongs to the rules. */
	fun reachedThreshold(threshold: Double): Boolean = (percentPlayed ?: 0.0) >= threshold
}
