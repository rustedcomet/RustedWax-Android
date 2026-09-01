package com.rustedwax.app.scrobble

import com.rustedwax.hive.HiveScrobblePayload
import com.rustedwax.core.ListenPolicyDefaults
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * When a finished track becomes one or more scrobbles.
 *
 * Ported from `hive-scrobbler.ts#finalize` in the extension, with one
 * deliberate difference: upstream's music branch has no threshold check of its
 * own, because web-scrobbler's controller only calls `finalize()` for tracks it
 * already decided were scrobbleable. We have no controller, so the
 * `scrobblePercent` gate lives here.
 *
 * Upstream behaviour that is preserved exactly:
 *   - 1 tx at ≥60%, a 2nd at ≥160% (a genuine double-listen), capped at 2.
 *   - `percent_played` for tx *i* is `min(100, round((progress - i) * 100))`.
 *
 * ## Two-stage evaluation
 *
 * [prefilter] runs on the caller's thread and rejects what no amount of
 * enrichment could rescue. [decide] runs after enrichment, once the effective
 * duration and the watch-page proof are known. The split exists to keep the
 * network out of the common path: a shorts feed finalizes a track every few
 * seconds, and most of those finalizations are metadata transitions with
 * a second or two of play time.
 */
object ScrobbleRules {

	/** The extension's `scrobblePercent` default. */
	const val DEFAULT_THRESHOLD = ListenPolicyDefaults.THRESHOLD

	const val MIN_DURATION_SECONDS = 30L

	const val SHORT_MIN_DURATION_SECONDS = 10L

	/**
	 * Progress above this is strong evidence that a short auto-looped.
	 *
	 * This is diagnostic evidence, not a rejection rule. A qualifying first
	 * viewing remains earned however long the browser leaves the same short
	 * active, and [capForKind] already limits every video to one transaction.
	 * Regression evidence puts ordinary end-of-track timing overrun below 125%,
	 * so 125% leaves a small margin before naming a probable loop.
	 */
	const val SHORT_LOOP_INFERENCE_PROGRESS = 1.25

	data class Decision(
		/** One entry per transaction to broadcast, each with its own percent. */
		val percentages: List<Int>,
		/** Why nothing is being broadcast, for the log. Null when scrobbling. */
		val skippedBecause: String? = null,
		/** High progress says this short probably auto-looped; never a rejection. */
		val probableLoop: Boolean = false,
	) {
		val shouldScrobble: Boolean get() = percentages.isNotEmpty()
	}

	/**
	 * Why a short `played` against a long duration is not a lost measurement.
	 *
	 * A video YouTube resumed part-way through, or one the viewer skipped into,
	 * can only ever be measured from the point RustedWax was first shown it. The
	 * percentage is then correct and looks broken, which is exactly the report
	 * this exists to answer: "it says 10% and that is not true."
	 */
	private fun resumedLeadInNote(unobservedLeadInMs: Long): String {
		if (unobservedLeadInMs <= 0) return ""
		val totalSeconds = unobservedLeadInMs / 1000
		// Clock time, not seconds: this line is read by whoever is asking why a
		// video they watched says 10%, and "17:47 in" is the number they can check
		// against the player. The diagnostic log keeps seconds.
		val stamp = "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
		return " — this session began $stamp into it, and anything played before " +
			"that was never published to RustedWax"
	}

	fun prefilter(
		playedMs: Long,
		durationMs: Long?,
		threshold: Double = DEFAULT_THRESHOLD,
		explicitAdSignal: String? = null,
		progressSurfaceLost: Boolean = false,
		inferredMs: Long = 0,
		unobservedLeadInMs: Long = 0,
	): String? {
		explicitAdSignal?.let { return explicitAdReason(it) }
		if (durationMs == null || durationMs <= 0) {
			// The watch page can still supply a length. Worth the fetch only if
			// enough was played that *some* admissible duration could clear the
			// threshold — below that, no recoverable duration helps.
			val floor = (SHORT_MIN_DURATION_SECONDS * 1000 * threshold).toLong()
			return if (playedMs < floor) {
				"no duration, and only ${playedMs / 1000}s played — " +
					"too little for any length to clear the threshold"
			} else {
				null
			}
		}
		if (durationMs < SHORT_MIN_DURATION_SECONDS * 1000) {
			return "track is ${durationMs / 1000}s, under the " +
				"${SHORT_MIN_DURATION_SECONDS}s hard floor — too short to count as a listen"
		}
		val progress = playedMs.toDouble() / durationMs
		if (progress < threshold) {
			if (progressSurfaceLost) return progressSurfaceLostReason(playedMs, inferredMs)
			return "played ${(progress * 100).roundToInt()}%, below " +
				"${(threshold * 100).roundToInt()}% threshold" +
				resumedLeadInNote(unobservedLeadInMs)
		}
		return null
	}

	fun decide(
		playedMs: Long,
		durationMs: Long?,
		threshold: Double = DEFAULT_THRESHOLD,
		isShort: Boolean = false,
		videoResolved: Boolean = false,
		videoUnlisted: Boolean? = null,
		explicitAdSignal: String? = null,
		progressSurfaceLost: Boolean = false,
		inferredMs: Long = 0,
		unobservedLeadInMs: Long = 0,
	): Decision {

		explicitAdSignal?.let {
			return Decision(emptyList(), explicitAdReason(it))
		}

		// Explicit ad evidence is a veto, not merely a reason to withhold the
		// lowered 10-second floor. v0.8.7 let a 42-second unlisted Shorts
		// creative clear the ordinary 30-second floor and reach the chain.
		// Either enrichment source can supply the same unlisted fact, so this
		// intentionally does not depend on watch-page provenance.
		if (isShort && videoUnlisted == true) {
			return Decision(
				emptyList(),
				"a short and the video is unlisted — almost certainly a feed ad",
			)
		}

		if (durationMs == null || durationMs <= 0) {
			return Decision(emptyList(), "no duration — can't measure progress")
		}

		// A proven Short is admitted or refused; it is never quietly held to the
		// ordinary floor. Falling back to 30 seconds was the v0.8.7 leak in a
		// different shape: it let anything unproven through on length alone, and
		// a 42-second creative is longer than 30 seconds. There is no third
		// state — proven public, or refused.
		val provenPublic = videoResolved && videoUnlisted == false
		if (isShort && !provenPublic) {
			return Decision(emptyList(), unprovenShort(videoResolved))
		}

		val floorSeconds =
			if (isShort) SHORT_MIN_DURATION_SECONDS else MIN_DURATION_SECONDS

		if (durationMs < floorSeconds * 1000) {
			return Decision(emptyList(), tooShort(durationMs, floorSeconds, isShort))
		}

		val progress = playedMs.toDouble() / durationMs
		if (progress < threshold) {
			// See [progressSurfaceLostReason]. In practice a sub-threshold Short
			// is rejected by [prefilter] and never arrives here; this stays
			// because `decide` is also reachable directly and the two stages must
			// not disagree about what a lost surface means.
			if (progressSurfaceLost) {
				return Decision(emptyList(), progressSurfaceLostReason(playedMs, inferredMs))
			}
			return Decision(
				emptyList(),
				"played ${(progress * 100).roundToInt()}%, below " +
					"${(threshold * 100).roundToInt()}% threshold" +
					resumedLeadInNote(unobservedLeadInMs),
			)
		}

		// Upstream: min(2, 1 + floor(max(0, progress - 0.6)))
		val txCount = min(2.0, 1 + floor(maxOf(0.0, progress - threshold))).toInt()
		val percentages = (0 until txCount).map { i ->
			min(100, ((progress - i) * 100).roundToInt())
		}
		return Decision(
			percentages = percentages,
			// Only a Short reaches here as a Short, and only a proven public one
			// does, so the loop inference needs no second condition.
			probableLoop = isShort && progress > SHORT_LOOP_INFERENCE_PROGRESS,
		)
	}

	private fun explicitAdReason(signal: String): String =
		"YouTube's visible UI marked this track as an ad (\"$signal\")"

	private fun progressSurfaceLostReason(playedMs: Long, inferredMs: Long): String = if (
		inferredMs > 0
	) {
		// Inference ran and still did not reach the bar. Say so with both
		// numbers, so a short PiP session reads differently from one where the
		// evidence never supported crediting anything at all.
		"the Short played on with no progress surface — picture-in-picture, or a " +
			"player YouTube drew no seekbar for: ${(playedMs - inferredMs) / 1000}s " +
			"measured from the seekbar plus ${inferredMs / 1000}s inferred from " +
			"wall-clock still falls short of the threshold."
	} else {
		"the Short's progress surface went away while it was still playing: the " +
			"seekbar container is still there but publishes no readable time, and " +
			"YouTube's MediaSession publishes no position either, so playback " +
			"cannot be measured. The measured cause is picture-in-picture or the " +
			"background. Only ${playedMs / 1000}s was measured before it went; " +
			"nothing is assumed about the rest."
	}

	/**
	 * Says which floor was applied *and why that one*, because "under the 30s
	 * minimum" on a 12-second short is the log line that sends you reading code
	 * to find out whether the exception fired.
	 */
	private fun tooShort(durationMs: Long, floorSeconds: Long, isShort: Boolean): String {
		val seconds = durationMs / 1000
		val because = if (isShort) "verified short" else "not a verified short"
		return "track is ${seconds}s, under the ${floorSeconds}s minimum ($because)"
	}

	/**
	 * A `/shorts/` path that could not be shown to be a real, public video.
	 *
	 * Both branches refuse. They stay distinguishable because they need
	 * different responses: a failed fetch is transient and the same clip earns
	 * its entry next time, while a page that resolves and declines to say
	 * whether the video is listed is YouTube having changed something and is
	 * worth reading the log over.
	 */
	private fun unprovenShort(videoResolved: Boolean): String = if (!videoResolved) {
		"a short, but its watch page didn't resolve — nothing separates a clip " +
			"scrolled past in the feed from a shorts-feed ad, so it is refused"
	} else {
		"a short, but the page never said whether it's listed — being public is " +
			"the only thing that tells a real short from an ad creative, so it is refused"
	}

	fun capForKind(
		percentages: List<Int>,
		kind: String,
		isShort: Boolean = false,
		loopDetected: Boolean = false,
		positionCorroborated: Boolean = true,
	): List<Int> =
		if (!loopDetected && !isShort && positionCorroborated &&
			kind == HiveScrobblePayload.KIND_SONG
		) {
			percentages
		} else {
			percentages.take(1)
		}
}
