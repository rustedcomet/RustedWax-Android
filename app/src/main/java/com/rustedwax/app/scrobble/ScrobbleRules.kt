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

	/**
	 * The ordinary minimum. Applies to the `/watch` path.
	 *
	 * It no longer applies to an unproven Short. A clip on a `/shorts/` path that
	 * cannot be shown to be a real public video is refused outright rather than
	 * held to this floor — see [SHORT_MIN_DURATION_SECONDS].
	 *
	 * It was added because YouTube pre-roll ads publish their own media session
	 * with the *video's* title and a ~6 s duration (observed in PHASE0 run 1),
	 * which would otherwise scrobble the song on every ad.
	 */
	const val MIN_DURATION_SECONDS = 30L

	/**
	 * The minimum for a **verified** YouTube short.
	 *
	 * ### Why shorts get their own floor
	 *
	 * The 2026-07-29 session sorted every 30 s-floor rejection by URL path:
	 * all 24 were `/shorts/`, none were `/watch`. The floor was doing nothing
	 * on the watch path and blocking a third of the shorts feed — 22 of 64
	 * unique shorts produced no logbook entry, which is exactly the "where are
	 * my entries?" failure this floor was never meant to cause.
	 *
	 * Ten seconds rather than fifteen because the blocked durations clustered
	 * hard at the bottom: `7, 10, 10, 10, 10, 10, 11, 12, 12, 15, 16, …`. A 10 s
	 * floor recovers 23 of the 24; 15 s recovers only 15.
	 *
	 * ### Why it is scoped by *path*, not by kind
	 *
	 * The ad-guard rationale above is specifically about pre-roll, which is a
	 * `/watch` phenomenon. Scoping the exception to a proven `/shorts/` URL
	 * leaves the case the floor was written for completely untouched.
	 *
	 * ### Why "verified" is load-bearing, and what it actually means
	 *
	 * Length is a terrible ad guard — an ad and a real 12-second clip are the
	 * same length. v0.8.0 therefore gated the exception on the video resolving
	 * on its watch page, on the reasoning that an ad creative has no public
	 * watch page.
	 *
	 * **That was wrong, and a Chrome session proved it.** YouTube serves shorts
	 * ads at genuine `/shorts/` URLs backed by genuine watch pages, so an 18 s ad
	 * cleared both halves and reached the chain. The gate is now
	 * [com.rustedwax.app.enrich.VideoFacts.provenPublicVideo] — resolved *and*
	 * publicly listed — because an ad creative is unlisted by construction while
	 * a short reached by scrolling the feed is public by construction.
	 *
	 * ### Why an unproven Short is refused rather than held to 30 seconds
	 *
	 * Falling back to [MIN_DURATION_SECONDS] looked conservative and was not. It
	 * is the v0.8.7 leak in another shape: it admitted anything unproven purely
	 * on length, and a 42-second shorts creative is longer than 30 seconds. So a
	 * proven `/shorts/` path is now either proven public — 10-second floor — or
	 * refused. There is no third state in which something unproven scrobbles
	 * because it happened to run long.
	 *
	 * The cost, stated plainly: enrichment failed on ~12% of ids in field
	 * testing, and decision D8 requires it stay non-blocking. So roughly one in
	 * eight legitimate short clips is now refused rather than merely held to a
	 * higher floor. That is the right direction to fail — a missed entry can be
	 * earned again by watching, a false entry is permanent — and it applies only
	 * to Shorts, which are off by default anyway.
	 */
	const val SHORT_MIN_DURATION_SECONDS = 10L

	/**
	 * Progress above this is strong evidence that a short auto-looped.
	 *
	 * This is diagnostic evidence, not a rejection rule. A qualifying first
	 * viewing remains earned however long the browser leaves the same short
	 * active, and [capForKind] already limits every video to one transaction.
	 * Field data put ordinary end-of-track timing overrun at no more than 120%,
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
	 * The cheap rejection, before any network call.
	 *
	 * @param progressSurfaceLost the Short's progress surface went away while it
	 * was still playing, so no percentage can honestly be quoted. This has to be
	 * handled *here* as well as in [decide]: a sub-threshold Short is rejected on
	 * this path and never reaches [decide] at all, so the honest wording added
	 * there alone was unreachable on the common path and the log still printed
	 * `played N%` for something that was never measured (`<redacted-private-path>`
	 * §3.2).
	 * @return a reason to skip now, or null when the track deserves enrichment
	 * and a full [decide].
	 */
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

	/**
	 * The full decision, once enrichment has had its say.
	 *
	 * @param durationMs the *effective* duration — the media session's, or the
	 * watch page's `lengthSeconds` when the session published none
	 * @param isShort the address bar proved a `/shorts/` path
	 * @param videoResolved the watch page returned a well-formed `videoDetails`
	 * @param videoUnlisted the page said `isUnlisted`, or didn't say — null when
	 * the field was absent, which fails the gate the same way `true` does
	 */
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
		// The optional accessibility service observed YouTube's own visible ad
		// UI and bound it to this exact track instance. This is the mobile analogue of
		// the desktop connector refusing while `.ad-showing` is present.
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

	/**
	 * Shared by both stages so a Short refused early and a Short refused late
	 * read identically in the log.
	 *
	 * A percentage here would be a claim we cannot support. When the progress
	 * surface is gone there is no measurement at all, not a measurement of zero,
	 * and the two must not read alike: on 2026-08-05 a picture-in-picture session
	 * reported "played 0%, below 60% threshold" and was indistinguishable from a
	 * parser bug for most of a day. It refuses either way — the difference is
	 * only whether the log tells the truth about why.
	 */
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

	/**
	 * The 160% double-listen only applies to songs with no loop evidence.
	 *
	 * A deliberate deviation from upstream, which doubles every kind. The rule
		 * exists to record a genuine second listen — but YouTube shorts auto-loop,
		 * so any short watched to 1.6× its 40 seconds produced two transactions for
		 * one sitting (observed on-chain 2026-07-24: the same clip broadcast twice
		 * in one block at 100% and 76%). The `/shorts/` source stays capped even
		 * when its content is correctly classified as a song. A strict
		 * end-to-start position reset applies the same cap to a watch-path song;
		 * a separate later session earns its own new scrobble.
	 *
	 * ## The second transaction needs more than elapsed time
	 *
	 * [positionCorroborated] is false when the session never published a readable
	 * position for this listen — the transport said `pos=-1` from the first
	 * callback to the last. Everything then rests on wall clock, and wall clock
	 * keeps running whether or not anyone is still watching.
	 *
	 * Measured 2026-08-12: a 3:06 song finalized at **6,421 s of 186 s — 3,452%**
	 * after the browser was left sitting on the finished video, and this rule
	 * minted two on-chain transactions for one sitting (`ae7ad561…` and
	 * `27c2171c…`, identical frozen start, both 100%). Brave never publishes
	 * `STATE_STOPPED` — zero of 835 finalizations in the retained field log — so
	 * nothing ended the track; and with no position there was no wrap to detect,
	 * so the loop cap above could not apply either.
	 *
	 * A second scrobble is a claim that a second listen happened. With no position
	 * that moved, there is no evidence for one, and one transaction is the honest
	 * answer. A genuine replay on a session that reports position is unaffected.
	 *
	 * Deliberately the *complement* of [loopDetected] rather than the same test: a
	 * detected wrap means the item auto-looped and caps for that reason, while
	 * this covers the case where nothing could be detected at all.
	 */
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
