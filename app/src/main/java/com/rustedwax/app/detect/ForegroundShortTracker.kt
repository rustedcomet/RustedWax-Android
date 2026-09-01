package com.rustedwax.app.detect

import com.rustedwax.core.*
import kotlin.math.abs
import kotlin.math.floor

/**
 * Pure foreground native-Short lifecycle.
 *
 * Progress is earned from accepted seekbar deltas. When YouTube leaves a proven
 * Short's seekbar absent or stalled, the user's opt-in paired visible-window +
 * active-audio evidence may instead credit bounded wall-clock as explicitly
 * inferred time. Missing proof freezes the baseline and finalizes after a short
 * no-credit grace.
 */
class ForegroundShortTracker(
	private val automaticWriteAuthorization: () -> AutomaticWriteAuthorization =
		{ AutomaticWriteAuthorization.LegacyEnabled },
	private val allocateTrackToken: () -> Long = MediaSessionAdEvidence::nextTrackToken,
) {

	data class OrganicObservation(
		val title: String?,
		val ownerHandle: String,
		val currentSeconds: Long,
		val totalSeconds: Long,
		val observedAtMillis: Long,
		val sourceEpoch: Long,
		/** Paired YouTube-visible-window + active-media-audio playback evidence. */
		val inferredPlaying: Boolean = false,
		/** Rate read from YouTube's own visible speed chip, when present. */
		val playbackRate: Double? = null,
	)

	/**
	 * A proven, named Short with no progress reading to advance from.
	 *
	 * @param playing the paired audio + visible-window evidence, which is the
	 * only thing that can move this Short's clock. False credits nothing.
	 */
	data class UnmeasuredObservation(
		val title: String?,
		val ownerHandle: String,
		val observedAtMillis: Long,
		val sourceEpoch: Long,
		val playing: Boolean,
		val playbackRate: Double? = null,
	)

	/**
	 * A measurable Shorts player whose footer is off screen.
	 *
	 * Measured 2026-08-08: the 2× hold hides the title and owner handle and
	 * leaves the seekbar readable. The handle is the identity, and it is already
	 * latched — re-proving it on every poll threw away the progress that was
	 * plainly there and killed the Short four seconds into every hold.
	 *
	 * Carries no identity on purpose. It continues the active Short and only when
	 * the length still matches; it can never acquire one.
	 */
	data class UnnamedObservation(
		val currentSeconds: Long,
		val totalSeconds: Long,
		val observedAtMillis: Long,
		val sourceEpoch: Long,
		val inferredPlaying: Boolean = false,
		val playbackRate: Double? = null,
	)

	data class AdObservation(
		val signal: String,
		val title: String?,
		val currentSeconds: Long,
		val totalSeconds: Long,
		val observedAtMillis: Long,
		val sourceEpoch: Long,
	)

	data class Update(
		val finalized: List<SessionSnapshot> = emptyList(),
		val active: SessionSnapshot? = null,
		val completeForegroundProof: Boolean = false,
		val diagnostic: String? = null,
	)

	private sealed class Active {
		abstract val currentSeconds: Long
		abstract val totalSeconds: Long
		abstract val observedAtMillis: Long
		abstract val sourceEpoch: Long
		abstract val startedAtEpochSec: Long

		/**
		 * Which listen this is, independent of the second it began in.
		 *
		 * Allocated once when the Short is acquired and carried through every
		 * `copy()` that advances it, so a Short that finalizes in the same second
		 * the next one starts stays a separate entry in the verified playback
		 * sequence. See `core/TrackInstanceId`.
		 */
		abstract val trackInstanceToken: Long
		abstract val automaticWriteAuthorization: AutomaticWriteAuthorization
		abstract val playedSeconds: Long
		abstract val loopDetected: Boolean
		abstract val frozenForMissingProof: Boolean

		/**
		 * The current loss of proof is specifically the measured PiP signature.
		 *
		 * Strictly narrower than [frozenForMissingProof], which is true for every
		 * refusal including the ordinary ones a swipe produces. Accumulates while
		 * frozen so an unrelated refusal landing between two PiP polls cannot
		 * erase it, and is cleared the moment a real observation lands.
		 */
		abstract val progressSurfaceLost: Boolean

		/**
		 * Wall-clock credited by [PipPlaybackInference] while the seekbar was
		 * gone. Kept apart from [playedSeconds] all the way to the snapshot so a
		 * listen can always say how much of it was measured and how much
		 * inferred.
		 *
		 * Banked *here* rather than read back off the live inference, which is
		 * discarded the moment the seekbar returns. Deriving it from that object
		 * zeroed the total on the next refusal, so a Short that spent time in PiP
		 * and then came back finalized as though it never had.
		 */
		abstract val inferredMillis: Long

		data class Organic(
			val title: String?,
			val ownerHandle: String,
			override val currentSeconds: Long,
			override val totalSeconds: Long,
			override val observedAtMillis: Long,
			override val sourceEpoch: Long,
			override val startedAtEpochSec: Long,
			override val trackInstanceToken: Long,
			override val automaticWriteAuthorization: AutomaticWriteAuthorization,
			override val playedSeconds: Long = 0,
			override val loopDetected: Boolean = false,
			override val frozenForMissingProof: Boolean = false,
			override val progressSurfaceLost: Boolean = false,
			override val inferredMillis: Long = 0,
			/** Inferred time already covering movement since [currentSeconds]. */
			val inferredSincePositionMillis: Long = 0,
			/**
			 * This viewing has already been banked as a complete listen.
			 *
			 * A Short only used to end when something took it away — a swipe, a
			 * track change, the player vanishing. Left alone it loops, so it
			 * accumulated forever and banked nothing: measured 2026-08-06, a 105s
			 * Short reached `measured total 461s` across four loops and never once
			 * finalized, so it never scrobbled at all.
			 *
			 * Reaching its own length *is* the end of a listen, so that is where
			 * it finalizes. One-way, so a Short left looping banks exactly one
			 * listen — which is all `ScrobbleRules.capForKind` would allow anyway.
			 */
			val bankedFullListen: Boolean = false,
			/**
			 * A terminal finalization has already been emitted for *this* logical
			 * listen — this start token and instance token.
			 *
			 * Deliberately not "this scrobbled". Whether the listen reached Hive is
			 * decided far downstream, after enrichment, identity and broadcast, any
			 * of which may fail and be retried. This says only that the lifecycle
			 * has already handed this listen over once, so handing the same one
			 * over again would be a duplicate rather than a recovery.
			 *
			 * Measured 2026-08-28 on `hDvSV9JfUEc`: a Short watched entirely in
			 * picture-in-picture reached its own length on inferred time, the
			 * proof grace ran and finalized it, and `remember` then carried it
			 * back as resumable with nothing recording that it had been finalized.
			 * The Short returning to the screen a second later restored it complete
			 * and unbanked, so it was finalized again — one viewing, two terminal
			 * outcomes, and a "Not logged: already scrobbled" row sitting beside
			 * its own History entry.
			 */
			val terminalFinalizationEmitted: Boolean = false,
		) : Active()

		data class Ad(
			val signal: String,
			val title: String?,
			override val currentSeconds: Long,
			override val totalSeconds: Long,
			override val observedAtMillis: Long,
			override val sourceEpoch: Long,
			override val startedAtEpochSec: Long,
			override val trackInstanceToken: Long,
			override val automaticWriteAuthorization: AutomaticWriteAuthorization,
			override val playedSeconds: Long = 0,
			override val loopDetected: Boolean = false,
			override val frozenForMissingProof: Boolean = false,
			override val progressSurfaceLost: Boolean = false,
			override val inferredMillis: Long = 0,
		) : Active()
	}

	private var active: Active? = null
	private var missingSinceMillis: Long? = null

	/**
	 * The last observation that still carried the title before an unmeasured
	 * footer blink began.
	 *
	 * This must not be derived from [Active.observedAtMillis]: unmeasured
	 * observations update that value every poll so inference can credit elapsed
	 * time. Using it as the blink anchor renewed an eight-second exception once a
	 * second and made the exception unbounded.
	 */
	private var unmeasuredTitleBlinkStartedAtMillis: Long? = null

	/**
	 * When the display was first seen non-interactive while proof was missing.
	 *
	 * Anchors the bounded [DISPLAY_OFF_SETTLE_MS] hold in [proofMissing]. Cleared
	 * whenever proof comes back, so a later screen-off gets its own window rather
	 * than inheriting an expired one.
	 */
	private var displayOffSinceMillis: Long? = null

	/**
	 * A forward jump that the rate ceiling just refused, waiting to be reported.
	 *
	 * Set by [advanceOrganic] and consumed by the next [Update]. It exists
	 * because the refusal used to be invisible: a Short played at 2× had every
	 * delta discarded and the log said nothing at all, so the only symptom was a
	 * listen that quietly never scrobbled. It took the owner isolating the
	 * variable by hand to find it (FIELD §19.1). A refusal that cannot say it
	 * happened is a refusal nobody can debug.
	 */
	private var refusedJump: String? = null

	/**
	 * This viewing has already been scored and must not be scored again.
	 *
	 * A Short that reaches its own length banks a complete listen while it is
	 * still on screen looping, and then something eventually takes it away —
	 * a swipe, a tab switch, the proof expiring — and every one of those paths
	 * used to finalize it a second time. Measured 2026-08-07 on
	 * `4x_q2gBomZI`: banked at `20:31:29`, finalized again at `20:31:31` when
	 * the next Short arrived, both resolved, both enriched, and the second
	 * broadcast stopped only by the dedup ledger — `skipped: already
	 * scrobbled`. The ledger is the last line of defence, not the design.
	 */
	/** Marks an Organic as having been handed over; an Ad carries no listen to repeat. */
	private fun Active.markTerminalFinalizationEmitted(): Active = when (this) {
		is Active.Organic -> copy(terminalFinalizationEmitted = true)
		is Active.Ad -> this
	}

	private val Active.alreadyFinalized: Boolean
		get() = (this as? Active.Organic)
			?.let { it.bankedFullListen || it.terminalFinalizationEmitted } == true

	/**
	 * What the Short that just ended had earned, in case it comes straight back.
	 *
	 * Measured 2026-08-07: the owner scrolled Shorts while switching between the
	 * Home and Shorts tabs, and **nothing scrobbled for 42 minutes**. Each switch
	 * takes the player away for longer than the 3-second grace, so the Short
	 * finalized; each switch back re-acquired the *same* Short and started it
	 * again from zero. One 32-second Short was watched across three switches and
	 * finalized at `0s`, `3s` and `5s` — never once reaching the threshold it had
	 * long since earned in total.
	 *
	 * The MediaSession path solved this years earlier with a continuation window.
	 * This is the same idea: a Short that returns with the same identity within
	 * [RESUME_WINDOW_MS] resumes what it had, rather than starting over. Merging
	 * two genuinely separate viewings of one Short is harmless — the dedup ledger
	 * already caps a video to one scrobble.
	 */
	private data class Interrupted(
		val title: String?,
		val ownerHandle: String,
		val totalSeconds: Long,
		val sourceEpoch: Long,
		/** Frozen identity of the continuous listen being resumed. */
		val startedAtEpochSec: Long,
		val trackInstanceToken: Long,
		val automaticWriteAuthorization: AutomaticWriteAuthorization,
		val playedSeconds: Long,
		val inferredMillis: Long,
		val loopDetected: Boolean,
		val bankedFullListen: Boolean,
		/** This listen was already handed over once; resuming it may not repeat that. */
		val terminalFinalizationEmitted: Boolean,
		val atMillis: Long,
		/** Where the seekbar stood when this Short was taken away. */
		val currentSeconds: Long,
		/**
		 * When [currentSeconds] was last published, which is not [atMillis].
		 *
		 * A Short that spent its last minute in picture-in-picture stopped
		 * publishing a position long before it was taken away, so the window the
		 * seekbar actually moved across starts here. Bounding a handback against
		 * [atMillis] instead would call every real PiP stretch a seek.
		 */
		val positionAtMillis: Long,
		/** The stretch that took it away was the measured PiP signature. */
		val progressSurfaceLost: Boolean,
		/** Inference already covering movement since [currentSeconds]. */
		val inferredSincePositionMillis: Long,
	)

	private var interrupted: Interrupted? = null

	/**
	 * Progress to resume for a Short that has just come back, or null.
	 *
	 * @param currentSeconds where the returning Short's seekbar stands. Past
	 * [RESUME_WINDOW_MS] this is what the resume rests on — see
	 * [RESUMED_WINDOW_MS].
	 */
	private fun resumeFor(
		title: String?,
		ownerHandle: String,
		totalSeconds: Long,
		sourceEpoch: Long,
		nowMillis: Long,
		currentSeconds: Long,
	): Interrupted? {
		val prior = interrupted ?: return null
		if (nowMillis < prior.atMillis) return null
		val away = nowMillis - prior.atMillis
		if (away > RESUMED_WINDOW_MS) return null
		if (prior.ownerHandle != ownerHandle || prior.sourceEpoch != sourceEpoch) return null
		if (prior.title != title) return null
		// A length learned since, or lost since, is still the same Short.
		if (prior.totalSeconds != 0L && totalSeconds != 0L && prior.totalSeconds != totalSeconds) {
			return null
		}
		if (away > RESUME_WINDOW_MS && !continuesFrom(prior, currentSeconds)) return null
		return prior
	}

	/**
	 * Whether the returning seekbar carries on from where this Short stopped.
	 *
	 * The 30-second window was measured against tab switches, which are quick.
	 * A user who minimizes YouTube and comes back minutes later is doing the same
	 * thing more slowly, and the Short returns exactly where they left it —
	 * measured 2026-08-09, a 107s Short taken away at `52s` and re-acquired at
	 * `52s of 107s` after 105 seconds away, which started over at zero and
	 * finished below threshold despite being watched end to end.
	 *
	 * So past the short window the seekbar has to agree: a Short genuinely being
	 * watched again from the top reports a position near zero and gets a fresh
	 * count, which is the outcome the time bound was reaching for.
	 */
	private fun continuesFrom(prior: Interrupted, currentSeconds: Long): Boolean {
		if (prior.currentSeconds < RESUME_MIN_POSITION_SECONDS) return false
		return abs(currentSeconds - prior.currentSeconds) <= RESUME_POSITION_TOLERANCE_SECONDS
	}

	/**
	 * Whether a footer title that went missing may continue the same Short.
	 *
	 * Only ever true when one side has no title at all. Two different titles are
	 * two different Shorts and always have been — this does not merge them, and
	 * it does not take the title out of the identity key.
	 */
	private fun titleTemporarilyMissing(active: String?, observed: String?): Boolean =
		(active == null) != (observed == null)

	/**
	 * Measured 2026-08-18 on `9s38_ONe2mE` (FIELD §21): holding a Short for 2×
	 * takes YouTube's footer off screen, and the footer comes back in two steps —
	 * owner handle first, title a frame or two later. The tracker saw
	 * `"This girl was crazy…" → null → "This girl was crazy…"`, all
	 * `@nyangear / 178s`, and finalized three times: 84s, 3s and 24s. Every piece
	 * was under the bar; the one continuous viewing that produced them was at 66%.
	 *
	 * A blink is admitted only where the Short is otherwise strongly continuous:
	 * the same owner, the same length, the same source epoch, a position that has
	 * not gone backwards and has not moved further than playback could carry it,
	 * and all of it inside a window far shorter than any real navigation.
	 */
	private fun isTitleBlink(same: Active.Organic, observation: OrganicObservation): Boolean {
		if (!titleTemporarilyMissing(same.title, observation.title)) return false
		if (same.ownerHandle != observation.ownerHandle) return false
		if (same.sourceEpoch != observation.sourceEpoch) return false
		// Identical to the ordinary key: a length learned mid-viewing is still
		// the same Short, a different one never is.
		if (same.totalSeconds != observation.totalSeconds && same.totalSeconds != 0L) {
			return false
		}
		val elapsed = observation.observedAtMillis - same.observedAtMillis
		if (elapsed < 0 || elapsed > TITLE_BLINK_WINDOW_MS) return false
		// Forward only. A genuinely new Short arrives near zero, which reads as a
		// backwards jump from anywhere into the current one and is refused here.
		if (observation.currentSeconds < same.currentSeconds) return false
		val advanced = observation.currentSeconds - same.currentSeconds
		return advanced <= maxTraversableSeconds(elapsed)
	}

	/**
	 * The same blink, on the surface that has no seekbar to corroborate it.
	 *
	 * Measured 2026-08-25: the owner reported a Short held at 2x producing a Not
	 * logged row saying it played a few seconds, followed by a second, full entry
	 * in History for the same viewing. YouTube intermittently renders no Shorts
	 * progress bar at all (v0.9.10), and a footer whose title has blinked out
	 * while that is true arrives as an [UnmeasuredObservation] carrying
	 * `title = null`. The identity key missed, the fragment already earned was
	 * finalized on its own, and the listen restarted from zero.
	 *
	 * `v0.11.0l` §1 left this path alone for want of evidence. This is the same
	 * rule minus the two clauses that need a position — there is none to read
	 * here — so it is deliberately the stricter of the two in every other
	 * respect: exactly one side without a title, the same owner, the same source
	 * epoch, inside the same short window.
	 */
	private fun isUnmeasuredTitleBlink(
		same: Active.Organic,
		observation: UnmeasuredObservation,
	): Boolean {
		if (!titleTemporarilyMissing(same.title, observation.title)) return false
		if (same.ownerHandle != observation.ownerHandle) return false
		if (same.sourceEpoch != observation.sourceEpoch) return false
		val startedAt = unmeasuredTitleBlinkStartedAtMillis ?: same.observedAtMillis
		val elapsed = observation.observedAtMillis - startedAt
		return elapsed >= 0 && elapsed <= TITLE_BLINK_WINDOW_MS
	}

	/** The most content playback itself could have carried in this much wall clock. */
	private fun maxTraversableSeconds(elapsedMillis: Long): Long =
		floor(elapsedMillis * MAX_PLAYBACK_RATE / 1000.0).toLong() + POSITION_JITTER_SECONDS

	/**
	 * Content the seekbar itself proves was played across a picture-in-picture
	 * stretch the tracker could not watch.
	 *
	 * Measured 2026-08-18 on `8Bh_XF6-48E` (FIELD §21): read to `8s of 59s`, sent
	 * to PiP, and handed back at `41s of 59s` still playing. Only witnessed
	 * deltas were ever added, so 33 seconds of content that YouTube's own bar
	 * accounted for became 23 seconds of wall-clock inference and the listen
	 * finalized at 54%.
	 *
	 * This is not general position credit. It runs only where the stretch just
	 * ended was the measured PiP signature, only forwards, only as far as
	 * playback could physically have carried the bar in the elapsed wall clock,
	 * and only for content the inference has not already been paid for. An
	 * implausible jump is a seek and earns nothing, exactly as before.
	 */
	private fun pipHandbackSeconds(
		fromSeconds: Long,
		toSeconds: Long,
		totalSeconds: Long,
		elapsedMillis: Long,
		coveredMillis: Long,
		measuredSeconds: Long,
		inferredMillis: Long,
	): Long {
		if (elapsedMillis < 0 || toSeconds <= fromSeconds) return 0
		val traversed = toSeconds - fromSeconds
		if (traversed > maxTraversableSeconds(elapsedMillis)) return 0
		val covered = (coveredMillis + 999) / 1_000
		val uncovered = (traversed - covered).coerceAtLeast(0)
		if (totalSeconds <= 0) return uncovered
		// A reconciliation may complete a listen; it may never claim more content
		// than the Short holds.
		val room = (totalSeconds - measuredSeconds - inferredMillis / 1_000).coerceAtLeast(0)
		return uncovered.coerceAtMost(room)
	}

	private fun remember(state: Active, nowMillis: Long) {
		val organic = state as? Active.Organic ?: return
		interrupted = Interrupted(
			title = organic.title,
			ownerHandle = organic.ownerHandle,
			totalSeconds = organic.totalSeconds,
			sourceEpoch = organic.sourceEpoch,
			startedAtEpochSec = organic.startedAtEpochSec,
			trackInstanceToken = organic.trackInstanceToken,
			automaticWriteAuthorization = organic.automaticWriteAuthorization,
			playedSeconds = organic.playedSeconds,
			inferredMillis = organic.inferredMillis,
			loopDetected = organic.loopDetected,
			bankedFullListen = organic.bankedFullListen,
			terminalFinalizationEmitted = organic.terminalFinalizationEmitted,
			atMillis = nowMillis,
			currentSeconds = organic.currentSeconds,
			positionAtMillis = organic.observedAtMillis,
			progressSurfaceLost = organic.progressSurfaceLost,
			inferredSincePositionMillis = organic.inferredSincePositionMillis,
		)
	}

	/**
	 * Live only while the current Short's progress surface is gone.
	 *
	 * Per-Short by construction: it is created on the first PiP observation and
	 * discarded whenever the seekbar returns, the track changes, or the Short
	 * ends. It must never outlive one Short — its duration cap is that Short's.
	 */
	private var inference: PipPlaybackInference? = null

	val hasActive: Boolean get() = active != null
	val hasCompleteProof: Boolean get() = active != null && missingSinceMillis == null

	/** Reduce the same neutral foreground-surface inputs emitted by source adapters. */
	fun reduce(input: PlaybackInput.ForegroundSurface): Update = when (input) {
		is PlaybackInput.ForegroundSurfaceConnected -> Update(
			active = snapshot(),
			completeForegroundProof = hasCompleteProof,
		)

		is PlaybackInput.ForegroundSurfaceUnavailable -> if (input.discard) {
			discard(input.reason)
		} else {
			proofMissing(
				nowMillis = input.nowMillis,
				reason = input.reason,
				progressSurfaceLost = input.progressSurfaceLost,
				inferredPlaying = input.playing,
				playbackRate = input.playbackRate,
				displayOff = input.displayOff,
			)
		}

		is PlaybackInput.ForegroundSurfaceObserved -> reduceObserved(input)
	}

	private fun reduceObserved(input: PlaybackInput.ForegroundSurfaceObserved): Update {
		val positionSeconds = input.positionMs?.div(1_000)
		val durationSeconds = input.durationMs?.div(1_000)
		val identity = input.identity
		val identityArtist = identity?.artist
		val adSignal = input.explicitAdSignal
		return when {
			adSignal != null && positionSeconds != null && durationSeconds != null -> observe(
				AdObservation(
					signal = adSignal,
					title = identity?.title,
					currentSeconds = positionSeconds,
					totalSeconds = durationSeconds,
					observedAtMillis = input.nowMillis,
					sourceEpoch = input.sourceEpoch,
				),
			)

			identity == null && positionSeconds != null && durationSeconds != null -> observe(
				UnnamedObservation(
					currentSeconds = positionSeconds,
					totalSeconds = durationSeconds,
					observedAtMillis = input.nowMillis,
					sourceEpoch = input.sourceEpoch,
					inferredPlaying = input.playing,
					playbackRate = input.playbackRate,
				),
			)

			identityArtist != null && positionSeconds != null && durationSeconds != null -> observe(
				OrganicObservation(
					title = identity.title,
					ownerHandle = identityArtist,
					currentSeconds = positionSeconds,
					totalSeconds = durationSeconds,
					observedAtMillis = input.nowMillis,
					sourceEpoch = input.sourceEpoch,
					inferredPlaying = input.playing,
					playbackRate = input.playbackRate,
				),
			)

			identityArtist != null && positionSeconds == null && durationSeconds == null -> observe(
				UnmeasuredObservation(
					title = identity.title,
					ownerHandle = identityArtist,
					observedAtMillis = input.nowMillis,
					sourceEpoch = input.sourceEpoch,
					playing = input.playing,
					playbackRate = input.playbackRate,
				),
			)

			else -> proofMissing(
				input.nowMillis,
				"foreground surface observation lacked a coherent identity/progress shape",
			)
		}
	}

	fun observe(observation: OrganicObservation): Update {
		// A measured observation has enough continuity evidence of its own. Any
		// seekbar-less title-blink window ends here rather than leaking into a later
		// unmeasured surface.
		unmeasuredTitleBlinkStartedAtMillis = null
		// Only ever describes this observation; a refusal is not carried forward.
		refusedJump = null
		val prior = active
		val same = prior as? Active.Organic
		val keyMatches = same != null &&
			same.title == observation.title &&
			same.ownerHandle == observation.ownerHandle &&
			// A Short that started without a seekbar has no length yet. The bar
			// appearing mid-viewing teaches it one; it does not make it a
			// different Short, and finalizing here would split one listen in two.
			(same.totalSeconds == observation.totalSeconds || same.totalSeconds == 0L) &&
			same.sourceEpoch == observation.sourceEpoch
		// The footer's title blinked out and back mid-viewing. See [isTitleBlink]:
		// same owner, same length, plausible forward progress, nothing else moved.
		val titleBlinked = same != null && !keyMatches && isTitleBlink(same, observation)
		val continues = keyMatches || titleBlinked
		val finalized = if (prior != null && !continues && !prior.alreadyFinalized) {
			listOf(snapshot(prior, finalized = true))
		} else {
			emptyList()
		}
		// A blink that reveals the title keeps it; one that hides it keeps the
		// title already proven. Either way the listen is the one already running.
		var handbackSeconds = 0L
		active = if (continues) {
			val carried = if (titleBlinked && same!!.title == null) {
				same.copy(title = observation.title)
			} else {
				same!!
			}
			advanceOrganic(carried, observation)
		} else {
			inference = null
			val resumed = resumeFor(
				observation.title,
				observation.ownerHandle,
				observation.totalSeconds,
				observation.sourceEpoch,
				observation.observedAtMillis,
				observation.currentSeconds,
			)
			// A Short handed back from picture-in-picture after the grace ran out
			// resumes what it earned *and* the content its own bar advanced past
			// while nothing could watch it.
			if (resumed != null && resumed.progressSurfaceLost) {
				handbackSeconds = pipHandbackSeconds(
					fromSeconds = resumed.currentSeconds,
					toSeconds = observation.currentSeconds,
					totalSeconds = observation.totalSeconds,
					elapsedMillis = observation.observedAtMillis - resumed.positionAtMillis,
					coveredMillis = resumed.inferredSincePositionMillis,
					measuredSeconds = resumed.playedSeconds,
					inferredMillis = resumed.inferredMillis,
				)
			}
			val acquired = Active.Organic(
				title = observation.title,
				ownerHandle = observation.ownerHandle,
				currentSeconds = observation.currentSeconds,
				totalSeconds = observation.totalSeconds,
				observedAtMillis = observation.observedAtMillis,
				sourceEpoch = observation.sourceEpoch,
				startedAtEpochSec = resumed?.startedAtEpochSec
					?: observation.observedAtMillis / 1000,
				trackInstanceToken = resumed?.trackInstanceToken
					?: allocateTrackToken(),
				automaticWriteAuthorization = resumed?.automaticWriteAuthorization
					?: automaticWriteAuthorization(),
				playedSeconds = (resumed?.playedSeconds ?: 0) + handbackSeconds,
				inferredMillis = resumed?.inferredMillis ?: 0,
				loopDetected = resumed?.loopDetected ?: false,
				bankedFullListen = resumed?.bankedFullListen ?: false,
				terminalFinalizationEmitted = resumed?.terminalFinalizationEmitted ?: false,
			)
			advanceOrganic(acquired, observation)
		}
		val currentOrganic = active as? Active.Organic
		val measuredPlayed = currentOrganic?.playedSeconds ?: 0
		val newlyEarned = if (continues) {
			(measuredPlayed - same!!.playedSeconds).coerceAtLeast(0)
		} else {
			0
		}
		val newlyInferred = if (continues) {
			((currentOrganic?.inferredMillis ?: 0) - same!!.inferredMillis).coerceAtLeast(0)
		} else {
			0
		}
		missingSinceMillis = null
		displayOffSinceMillis = null

		// A Short that has played its whole length has finished a listen, even
		// though it is still on screen looping. Bank it now rather than waiting
		// for something to take it away, which may never happen.
		val organic = active as? Active.Organic
		val completed = if (organic != null && !organic.alreadyFinalized &&
			organic.totalSeconds > 0 &&
			organic.playedSeconds + organic.inferredMillis / 1000 >= organic.totalSeconds
		) {
			active = organic.copy(bankedFullListen = true)
			listOf(snapshot(organic, finalized = true))
		} else {
			emptyList()
		}

		val note = when {
			titleBlinked -> "; the footer's title blinked out and back, so this " +
				"continues the same Short rather than starting one"
			handbackSeconds > 0 -> "; handed back from picture-in-picture with its own " +
				"seekbar ${handbackSeconds}s further on than the inference was paid for, " +
				"so that content is credited too"
			else -> ""
		}
		return Update(
			finalized = finalized + completed,
			active = active?.let { snapshot(it, finalized = false) },
			completeForegroundProof = true,
			diagnostic = when {
				completed.isNotEmpty() -> "foreground Short completed a full listen of " +
					"${organic?.totalSeconds}s while still on screen; banked it rather than " +
					"waiting for the loop to end"
				finalized.isNotEmpty() -> "foreground Short identity transitioned to " +
					"\"${observation.title}\" / ${observation.ownerHandle} / ${observation.totalSeconds}s"
				prior == null -> "foreground Short proof acquired: " +
					"\"${observation.title}\" / ${observation.ownerHandle} / " +
					"${observation.currentSeconds}s of ${observation.totalSeconds}s"
				newlyEarned > 0 -> "foreground Short seekbar advanced to " +
					"${observation.currentSeconds}s of ${observation.totalSeconds}s; " +
					"credited ${newlyEarned}s (measured total ${measuredPlayed}s)"
				newlyInferred > 0 -> "foreground Short seekbar remained at " +
					"${observation.currentSeconds}s of ${observation.totalSeconds}s; " +
					"credited ${newlyInferred}ms of inferred wall-clock" +
					(observation.playbackRate?.let {
						" at ${it}× off YouTube's own speed chip"
					} ?: " from paired YouTube-window + active-audio evidence") +
					" (inferred total " +
					"${(currentOrganic?.inferredMillis ?: 0) / 1000}s)"
				else -> refusedJump
			// A blink that lands on a frame with no new position and nothing
			// inferred has no base line to hang off, and `null?.plus(note)` threw
			// the note away — which is how a whole evening of device testing
			// measured "zero blinks" while the rule may well have been firing.
			}.let { base ->
				when {
					base != null -> base + note
					note.isNotEmpty() -> "foreground Short continues" + note
					else -> null
				}
			},
		)
	}

	/**
	 * Progress for the Short already being tracked, from a poll with no footer.
	 *
	 * Refuses unless the length still matches what was acquired, so a scroll that
	 * lands on another Short mid-hold cannot have its seconds credited here — and
	 * with nothing active at all this is simply a missing proof, exactly as
	 * before.
	 */
	fun observe(observation: UnnamedObservation): Update {
		unmeasuredTitleBlinkStartedAtMillis = null
		refusedJump = null
		val current = active as? Active.Organic
			?: return proofMissing(
				observation.observedAtMillis,
				"a Shorts player with no footer, and no acquired Short to credit it to",
			)
		if (current.sourceEpoch != observation.sourceEpoch ||
			(current.totalSeconds != 0L && current.totalSeconds != observation.totalSeconds)
		) {
			return proofMissing(
				observation.observedAtMillis,
				"a Shorts player with no footer whose length is not the one acquired " +
					"(${observation.totalSeconds}s, tracking ${current.totalSeconds}s)",
			)
		}
		val before = current.playedSeconds
		val inferredBefore = current.inferredMillis
		active = advanceOrganic(
			current,
			OrganicObservation(
				title = current.title,
				ownerHandle = current.ownerHandle,
				currentSeconds = observation.currentSeconds,
				totalSeconds = observation.totalSeconds,
				observedAtMillis = observation.observedAtMillis,
				sourceEpoch = observation.sourceEpoch,
				inferredPlaying = observation.inferredPlaying,
				playbackRate = observation.playbackRate,
			),
		)
		missingSinceMillis = null
		displayOffSinceMillis = null
		val organic = active as? Active.Organic
		val earned = (organic?.playedSeconds ?: 0) - before
		val inferred = ((organic?.inferredMillis ?: 0) - inferredBefore).coerceAtLeast(0)
		val completed = if (organic != null && !organic.alreadyFinalized &&
			organic.totalSeconds > 0 &&
			organic.playedSeconds + organic.inferredMillis / 1000 >= organic.totalSeconds
		) {
			active = organic.copy(bankedFullListen = true)
			listOf(snapshot(organic, finalized = true))
		} else {
			emptyList()
		}
		return Update(
			finalized = completed,
			active = active?.let { snapshot(it, finalized = false) },
			completeForegroundProof = true,
			diagnostic = when {
				completed.isNotEmpty() -> "foreground Short completed a full listen of " +
					"${organic?.totalSeconds}s with its footer off screen; banked it"
				earned > 0 -> "foreground Short seekbar advanced to " +
					"${observation.currentSeconds}s of ${observation.totalSeconds}s with the " +
					"footer off screen; credited ${earned}s (measured total " +
					"${organic?.playedSeconds}s)"
				inferred > 0 -> "foreground Short seekbar remained at " +
					"${observation.currentSeconds}s of ${observation.totalSeconds}s with the " +
					"footer off screen; credited ${inferred}ms of inferred wall-clock" +
					(observation.playbackRate?.let {
						" at ${it}× off YouTube's own speed chip"
					} ?: " from paired YouTube-window + active-audio evidence")
				else -> refusedJump
			},
		)
	}

	/**
	 * A Short that is proven and playing but publishes no progress at all.
	 *
	 * Measured 2026-08-06 late: YouTube stopped rendering the Shorts seekbar, so
	 * 47 of 71 Shorts in 85 minutes could never be *started* and therefore could
	 * never accrue anything. This starts them; the wall-clock inference then
	 * credits exactly as it does for picture-in-picture, on the same evidence,
	 * and every second of it is reported as inferred.
	 *
	 * The length is unknown here — it came from the seekbar — so nothing caps the
	 * accrual live. The cap is applied where the length is actually known: at
	 * finalize, against the duration the resolver read off the video's own page.
	 */
	fun observe(observation: UnmeasuredObservation): Update {
		val prior = active
		val same = prior as? Active.Organic
		val keyMatches = same != null &&
			same.title == observation.title &&
			same.ownerHandle == observation.ownerHandle &&
			same.sourceEpoch == observation.sourceEpoch
		// The footer's title blinked out and back while YouTube was rendering no
		// seekbar. See [isUnmeasuredTitleBlink].
		val titleBlinked = same != null && !keyMatches && isUnmeasuredTitleBlink(same, observation)
		unmeasuredTitleBlinkStartedAtMillis = if (
			titleBlinked && observation.title == null
		) {
			unmeasuredTitleBlinkStartedAtMillis ?: same!!.observedAtMillis
		} else {
			null
		}
		val continues = keyMatches || titleBlinked
		val finalized = if (prior != null && !continues && !prior.alreadyFinalized) {
			listOf(snapshot(prior, finalized = true))
		} else {
			emptyList()
		}
		val current = if (continues) {
			// A blink that reveals the title adopts it; one that hides it keeps
			// the title already proven.
			if (titleBlinked && same!!.title == null) {
				same.copy(title = observation.title)
			} else {
				same!!
			}
		} else {
			// A fresh Short — unless it is the one that just went away, in which
			// case it resumes what it had earned.
			inference = null
			val resumed = resumeFor(
				observation.title,
				observation.ownerHandle,
				totalSeconds = 0,
				sourceEpoch = observation.sourceEpoch,
				nowMillis = observation.observedAtMillis,
				// No seekbar to read at all, so this route can only ever resume
				// inside the short window.
				currentSeconds = 0,
			)
			Active.Organic(
				title = observation.title,
				ownerHandle = observation.ownerHandle,
				currentSeconds = 0,
				totalSeconds = resumed?.totalSeconds ?: 0,
				observedAtMillis = observation.observedAtMillis,
				sourceEpoch = observation.sourceEpoch,
				startedAtEpochSec = resumed?.startedAtEpochSec
					?: observation.observedAtMillis / 1000,
				trackInstanceToken = resumed?.trackInstanceToken
					?: allocateTrackToken(),
				automaticWriteAuthorization = resumed?.automaticWriteAuthorization
					?: automaticWriteAuthorization(),
				playedSeconds = resumed?.playedSeconds ?: 0,
				inferredMillis = resumed?.inferredMillis ?: 0,
				loopDetected = resumed?.loopDetected ?: false,
				bankedFullListen = resumed?.bankedFullListen ?: false,
				terminalFinalizationEmitted = resumed?.terminalFinalizationEmitted ?: false,
			)
		}
		var credited = 0L
		if (observation.playing) {
			val running = inference ?: PipPlaybackInference(
				// A cap of zero would credit nothing, which is the bug being
				// fixed; no cap at all would let a Short left looping invent
				// hours. Until the real length is known, the format's own maximum
				// is the honest ceiling — and the moment a seekbar appears, the
				// video's own length replaces it.
				durationMs = if (current.totalSeconds > 0) {
					current.totalSeconds * 1000
				} else {
					MAX_SHORT_DURATION_MS
				},
				measuredMs = current.playedSeconds * 1000 + current.inferredMillis,
			)
			val step = running.observe(
				observation.observedAtMillis,
				playing = true,
				rate = observation.playbackRate ?: 1.0,
			)
			inference = step.next
			credited = step.creditedMs
		} else {
			inference = inference?.observe(observation.observedAtMillis, playing = false)?.next
		}
		val inferredMillis = current.inferredMillis + credited
		active = current.copy(
			observedAtMillis = observation.observedAtMillis,
			frozenForMissingProof = false,
			progressSurfaceLost = true,
			inferredMillis = inferredMillis,
			inferredSincePositionMillis = current.inferredSincePositionMillis + credited,
		)
		missingSinceMillis = null
		displayOffSinceMillis = null

		// Nothing more can be earned, so the listen is over — bank it now rather
		// than waiting for something to take it away. Measured 2026-08-07: an
		// untitled, seekbar-less Short sat active for **seven minutes**, hit its
		// ceiling at three, and only finalized when the next Short replaced it.
		// By then the account had watched enough other Shorts that this one had
		// fallen out of the recent-history window identity needs, so a full
		// listen was measured and then could not be named.
		val organic = active as? Active.Organic
		val banked = if (organic != null && !organic.alreadyFinalized &&
			inference?.exhausted == true
		) {
			active = organic.copy(bankedFullListen = true)
			listOf(snapshot(organic, finalized = true))
		} else {
			emptyList()
		}

		return Update(
			finalized = finalized + banked,
			active = active?.let { snapshot(it, finalized = false) },
			completeForegroundProof = true,
			diagnostic = when {
				banked.isNotEmpty() -> "foreground Short with no seekbar reached the most that " +
					"can be inferred for one (${inferredMillis / 1000}s); banked it now rather " +
					"than holding it open until something replaces it"
				finalized.isNotEmpty() || prior == null ->
					"foreground Short proof acquired without a seekbar: " +
						"${observation.title?.let { "\"$it\"" } ?: "no readable title"} / " +
						"${observation.ownerHandle} — YouTube is not rendering the progress " +
						"bar, so time is inferred"
				titleBlinked -> "foreground Short has no seekbar and its footer's title blinked " +
					"out and back, so this continues the same Short rather than starting one" +
					(if (credited > 0) "; credited ${credited}ms of inferred wall-clock" else "")
				credited > 0 -> "foreground Short has no seekbar; credited ${credited}ms of " +
					"inferred wall-clock" +
					(observation.playbackRate?.let {
						" at ${it}× off YouTube's own speed chip"
					} ?: "") +
					" (inferred total ${inferredMillis / 1000}s)"
				else -> null
			},
		)
	}

	fun observe(observation: AdObservation): Update {
		unmeasuredTitleBlinkStartedAtMillis = null
		val prior = active
		val same = prior as? Active.Ad
		val keyMatches = same != null && same.signal == observation.signal &&
			same.totalSeconds == observation.totalSeconds && same.sourceEpoch == observation.sourceEpoch
		val finalized = if (prior != null && !keyMatches && !prior.alreadyFinalized) {
			listOf(snapshot(prior, finalized = true))
		} else {
			emptyList()
		}
		active = if (keyMatches) {
			advanceAd(same, observation)
		} else {
			Active.Ad(
				signal = observation.signal,
				title = observation.title,
				currentSeconds = observation.currentSeconds,
				totalSeconds = observation.totalSeconds,
				observedAtMillis = observation.observedAtMillis,
				sourceEpoch = observation.sourceEpoch,
				startedAtEpochSec = observation.observedAtMillis / 1000,
				trackInstanceToken = allocateTrackToken(),
				automaticWriteAuthorization = automaticWriteAuthorization(),
			)
		}
		missingSinceMillis = null
		displayOffSinceMillis = null
		// A real seekbar reading is in hand, so there is nothing left to infer.
		inference = null
		return Update(
			finalized = finalized,
			active = active?.let { snapshot(it, finalized = false) },
			completeForegroundProof = true,
			diagnostic = "literal native Short ad signal: ${observation.signal}",
		)
	}

	/**
	 * Freeze immediately; a same-key recovery resets the position baseline.
	 *
	 * @param progressSurfaceLost this refusal is the measured PiP signature
	 * ([NativeShortParser.Result.Invalid.progressSurfaceLost]), not merely a
	 * refusal. Only this distinguishes "playing but unmeasurable" from "no longer
	 * on screen", and only it may reach [SessionSnapshot.foregroundProgressLost].
	 */
	fun proofMissing(
		nowMillis: Long,
		reason: String,
		progressSurfaceLost: Boolean = false,
		inferredPlaying: Boolean = false,
		/**
		 * Rate from YouTube's own visible speed chip, when it is showing one.
		 *
		 * The 2× hold is exactly the state that makes a Short unmeasurable — the
		 * overlay, the footer and the seekbar all go — so the inference is the
		 * only thing crediting anything, and crediting it at 1× halves every such
		 * listen (FIELD §20).
		 */
		playbackRate: Double? = null,
		/**
		 * The display is not interactive as of this observation.
		 *
		 * Measured 2026-08-19 on the A36: turning the screen off while a Short
		 * played in picture-in-picture dropped the paired audio + visible-window
		 * evidence for 3.5 seconds and then restored it. The dropout is longer
		 * than [MISSING_PROOF_GRACE_MS], so a Short that never stopped playing
		 * finalized at 90s of 162s — 56%, four points under the threshold — and
		 * the evidence came back 1.8 seconds *after* it had already been scored.
		 *
		 * This does not credit anything. It only stops the grace from running out
		 * inside a transition that is known to be transient, and only for
		 * [DISPLAY_OFF_SETTLE_MS]. If the evidence returns the ordinary inference
		 * takes over and holds the grace open on its own terms; if it does not,
		 * the grace expires exactly as it did before.
		 */
		displayOff: Boolean = false,
	): Update {
		val current = active ?: return Update(diagnostic = reason)
		if (missingSinceMillis == null) missingSinceMillis = nowMillis
		if (!displayOff) {
			displayOffSinceMillis = null
		} else if (displayOffSinceMillis == null) {
			displayOffSinceMillis = nowMillis
		}
		val surfaceLost = current.progressSurfaceLost || progressSurfaceLost
		// Inference is admissible only for the exact PiP signature. A swipe, a
		// blown capture budget or a hidden root are not "playing, unmeasurable" —
		// they are "gone" — and crediting wall-clock through them would turn
		// every scroll into watch time.
		val inferring = surfaceLost && inferredPlaying
		var credited = 0L
		if (inferring) {
			val running = inference ?: PipPlaybackInference(
				durationMs = current.totalSeconds * 1000,
				// Everything already accounted for: the frozen seekbar reading
				// plus anything banked by an earlier PiP stretch of this same
				// Short. Keeps measured + inferred <= duration across a
				// PiP → fullscreen → PiP round trip.
				measuredMs = current.playedSeconds * 1000 + current.inferredMillis,
			)
			val step = running.observe(nowMillis, playing = true, rate = playbackRate ?: 1.0)
			inference = step.next
			credited = step.creditedMs
		} else if (progressSurfaceLost) {
			// This capture *is* the PiP signature and the evidence still said not
			// playing, so it is a genuine pause: drop the anchor and let the grace
			// run as usual.
			inference = inference?.observe(nowMillis, playing = false)?.next
		}
		// Any other refusal — the 1s freshness watchdog, a stale capture, a
		// scroll reset — carries no evidence about playback either way, so it
		// must leave the anchor alone. Treating those as pauses is what made the
		// first device run credit 0ms on every tick: the watchdog fires once a
		// second while the surface is gone, so the anchor was dropped and
		// re-taken between every pair of real observations and no interval ever
		// closed.
		val inferredMillis = current.inferredMillis + credited
		active = when (current) {
			is Active.Organic -> current.copy(
				frozenForMissingProof = true,
				progressSurfaceLost = surfaceLost,
				inferredMillis = inferredMillis,
				inferredSincePositionMillis =
					current.inferredSincePositionMillis + credited,
			)
			is Active.Ad -> current.copy(
				frozenForMissingProof = true,
				progressSurfaceLost = surfaceLost,
				inferredMillis = inferredMillis,
			)
		}
		// While the inference is live the Short has not gone anywhere, so the
		// no-credit grace must not run out underneath it. Without this the 3s
		// grace finalizes a Short two polls into a PiP session and the credit is
		// never able to accumulate at all.
		// Holding the grace open is only honest while there is still something to
		// earn. Once the cap is reached the listen is complete, so the ordinary
		// grace has to run and finalize it — otherwise a Short left in
		// picture-in-picture accrues to its full length and then never ends,
		// which is a scrobble lost rather than gained.
		if (inferring && inference?.exhausted != true) {
			missingSinceMillis = nowMillis
			return Update(
				active = snapshot(active ?: current, finalized = false),
				completeForegroundProof = false,
				diagnostic = "$reason; Short still playing in picture-in-picture — " +
					"credited ${credited}ms of inferred wall-clock" +
					(playbackRate?.let { " at ${it}\u00d7 off YouTube's own speed chip" } ?: "") +
					" (inferred total ${inferredMillis / 1000}s, measured ${current.playedSeconds}s)",
			)
		}
		// The screen going off drops the paired audio + visible-window evidence for
		// about three and a half seconds and then restores it — longer than the
		// grace, so without this a Short that never stopped playing is scored and
		// discarded inside the transition, and the evidence returns to a Short that
		// has already been finalized. Bounded by [DISPLAY_OFF_SETTLE_MS] so a screen
		// left off does not hold a Short open forever, and it credits nothing: if
		// playback really did stop, the grace expires a few seconds later than it
		// used to and the outcome is identical.
		val settlingAfterDisplayOff = displayOff &&
			displayOffSinceMillis?.let { nowMillis - it < DISPLAY_OFF_SETTLE_MS } == true
		if (settlingAfterDisplayOff) {
			missingSinceMillis = nowMillis
			return Update(
				active = snapshot(active ?: current, finalized = false),
				completeForegroundProof = false,
				diagnostic = "$reason; the display just went off and the paired " +
					"picture-in-picture evidence has not settled — holding the " +
					"end-of-track grace open, crediting nothing",
			)
		}
		// A Short on its way back from picture-in-picture publishes frames that are
		// neither the PiP signature nor a usable observation, so this grace can
		// still expire under a Short that is playing. That costs a stale Not logged
		// stub, not the listen: the credit it earned in PiP is reclaimed on the way
		// back by [pipHandbackSeconds]. An earlier revision widened the grace for
		// exactly this case and was withdrawn — a shared lifecycle timeout is not
		// worth changing to tidy up a duplicate row.
		return if (nowMillis - (missingSinceMillis ?: nowMillis) >= MISSING_PROOF_GRACE_MS) {
			val ending = active ?: current
			// A viewing banked at its own full length is already scored; the
			// player going away afterwards ends nothing that has not ended.
			val ended = if (ending.alreadyFinalized) {
				emptyList()
			} else {
				listOf(snapshot(ending, finalized = true))
			}
			// The commonest reason a Short's player goes away is that the user
			// switched tabs, and the commonest thing they do next is switch back.
			// What comes back has to remember that it was already handed over, or
			// returning inside the resume window scores the same viewing twice.
			remember(
				if (ended.isEmpty()) ending else ending.markTerminalFinalizationEmitted(),
				nowMillis,
			)
			active = null
			missingSinceMillis = null
			unmeasuredTitleBlinkStartedAtMillis = null
			displayOffSinceMillis = null
			inference = null
			Update(
				finalized = ended,
				diagnostic = "$reason; foreground proof grace expired at the last valid seekbar value",
			)
		} else {
			Update(
				active = snapshot(active ?: current, finalized = false),
				completeForegroundProof = false,
				diagnostic = "$reason; progress frozen during bounded refresh grace",
			)
		}
	}

	/** Stop, opt-out, disconnect and source-epoch changes discard rather than score. */
	fun discard(reason: String): Update {
		active = null
		missingSinceMillis = null
		unmeasuredTitleBlinkStartedAtMillis = null
		displayOffSinceMillis = null
		inference = null
		return Update(diagnostic = "$reason; in-flight foreground Short discarded")
	}

	fun snapshot(): SessionSnapshot? = active?.let { snapshot(it, finalized = false) }

	private fun advanceOrganic(
		current: Active.Organic,
		observation: OrganicObservation,
	): Active.Organic {
		if (observation.observedAtMillis < current.observedAtMillis) return current
		// The seekbar appearing on a Short that started without one supplies the
		// length for the first time. Adopt it before anything uses it, so the
		// completion cap and the percentage are computed against a real duration.
		if (current.totalSeconds == 0L && observation.totalSeconds > 0) {
			inference = null
			val adopted = current.copy(
				currentSeconds = observation.currentSeconds,
				totalSeconds = observation.totalSeconds,
				observedAtMillis = observation.observedAtMillis,
				frozenForMissingProof = false,
				progressSurfaceLost = false,
				inferredSincePositionMillis = 0,
			)
			return inferWhilePositionStalled(adopted, observation)
		}
		if (current.frozenForMissingProof) {
			// A readable seekbar is back, so whatever took it away is over. Field
			// §4.3: returning from PiP restores it within ~5s and it survives a
			// swipe to the next Short, so this really is recovery, not a blip.
			inference = null
			// Only where the stretch that just ended was the measured PiP
			// signature does the returning bar prove content was played. Every
			// other freeze — a swipe, a stale capture, the freshness watchdog —
			// still adopts the new position and credits nothing, as before.
			val handback = if (current.progressSurfaceLost) {
				pipHandbackSeconds(
					fromSeconds = current.currentSeconds,
					toSeconds = observation.currentSeconds,
					totalSeconds = current.totalSeconds,
					elapsedMillis = observation.observedAtMillis - current.observedAtMillis,
					coveredMillis = current.inferredSincePositionMillis,
					measuredSeconds = current.playedSeconds,
					inferredMillis = current.inferredMillis,
				)
			} else {
				0
			}
			val recovered = current.copy(
				currentSeconds = observation.currentSeconds,
				observedAtMillis = observation.observedAtMillis,
				playedSeconds = current.playedSeconds + handback,
				frozenForMissingProof = false,
				progressSurfaceLost = false,
				inferredSincePositionMillis = 0,
			)
			return inferWhilePositionStalled(recovered, observation)
		}
		// Samsung can expose the same cached integer seekbar value across many
		// successful polls, then publish several genuinely traversed seconds at
		// once. The Galaxy A36 field trace held one value for 63–104 seconds while
		// paired YouTube-window + active-audio evidence continuously said playback
		// was running. That is no longer treated as a pause: the opt-in inference
		// earns wall clock while the value is stalled, without moving the position
		// baseline a later real delta is checked against.
		if (observation.currentSeconds == current.currentSeconds) {
			return inferWhilePositionStalled(current, observation)
		}
		val earned = acceptedDelta(
			previous = current.currentSeconds,
			next = observation.currentSeconds,
			total = current.totalSeconds,
			elapsedMillis = observation.observedAtMillis - current.observedAtMillis,
		)
		if (earned.refusedSeconds > 0) {
			val elapsedSeconds =
				(observation.observedAtMillis - current.observedAtMillis) / 1000.0
			refusedJump = "foreground Short seekbar jumped ${earned.refusedSeconds}s in " +
				"${"%.1f".format(elapsedSeconds)}s of wall clock — faster than " +
				"${MAX_PLAYBACK_RATE}\u00d7 playback can account for, so it is a seek and " +
				"earns nothing"
		}
		// A delayed seekbar publication describes content already covered by the
		// stalled-position inference. Subtract that overlap so the later jump can
		// add only genuinely uncovered content, never double-count the interval.
		val inferredCoveredSeconds =
			(current.inferredSincePositionMillis + 999) / 1_000
		val uncoveredSeconds = (earned.seconds - inferredCoveredSeconds).coerceAtLeast(0)
		val advanced = current.copy(
			currentSeconds = observation.currentSeconds,
			observedAtMillis = observation.observedAtMillis,
			playedSeconds = current.playedSeconds + uncoveredSeconds,
			loopDetected = current.loopDetected || earned.wrap,
			inferredSincePositionMillis = 0,
		)
		inference = null
		return inferWhilePositionStalled(advanced, observation)
	}

	/**
	 * Credit a structurally proven Short whose published position is unchanged.
	 *
	 * The evidence is deliberately the same opt-in pair already accepted for a
	 * foreground Short with no seekbar and for PiP: YouTube owns a visible window
	 * and media audio is active. A title, exact owner handle, duration and Shorts
	 * player root are still present here, so this does not infer identity and can
	 * never acquire or continue a different Short.
	 */
	private fun inferWhilePositionStalled(
		current: Active.Organic,
		observation: OrganicObservation,
	): Active.Organic {
		if (!observation.inferredPlaying) {
			inference = inference?.observe(
				observation.observedAtMillis,
				playing = false,
			)?.next
			return current
		}
		val running = inference ?: PipPlaybackInference(
			durationMs = if (current.totalSeconds > 0) {
				current.totalSeconds * 1_000
			} else {
				MAX_SHORT_DURATION_MS
			},
			measuredMs = current.playedSeconds * 1_000 + current.inferredMillis,
		)
		val step = running.observe(
			observation.observedAtMillis,
			playing = true,
			rate = observation.playbackRate ?: 1.0,
		)
		inference = step.next
		return current.copy(
			inferredMillis = current.inferredMillis + step.creditedMs,
			inferredSincePositionMillis =
				current.inferredSincePositionMillis + step.creditedMs,
		)
	}

	private fun advanceAd(current: Active.Ad, observation: AdObservation): Active.Ad {
		if (observation.observedAtMillis < current.observedAtMillis) return current
		if (current.frozenForMissingProof) {
			return current.copy(
				currentSeconds = observation.currentSeconds,
				observedAtMillis = observation.observedAtMillis,
				frozenForMissingProof = false,
				progressSurfaceLost = false,
			)
		}
		if (observation.currentSeconds == current.currentSeconds) return current
		val earned = acceptedDelta(
			current.currentSeconds,
			observation.currentSeconds,
			current.totalSeconds,
			observation.observedAtMillis - current.observedAtMillis,
		)
		return current.copy(
			currentSeconds = observation.currentSeconds,
			observedAtMillis = observation.observedAtMillis,
			playedSeconds = current.playedSeconds + earned.seconds,
			loopDetected = current.loopDetected || earned.wrap,
		)
	}

	/**
	 * @param refusedSeconds a forward jump that exceeded the rate ceiling and so
	 * earned nothing. Carried out of here purely so the log can say it happened:
	 * this refusal was **silent** until v0.9.14, which is why a Short played at
	 * 2× looked to the viewer like a scrobbler that had simply stopped working.
	 */
	private data class Delta(
		val seconds: Long = 0,
		val wrap: Boolean = false,
		val refusedSeconds: Long = 0,
	)

	private fun acceptedDelta(
		previous: Long,
		next: Long,
		total: Long,
		elapsedMillis: Long,
	): Delta {
		if (elapsedMillis < 0 || next == previous) return Delta()
		// Content advances faster than the clock when the viewer speeds it up, and
		// until v0.9.14 this bound was wall-clock alone — so a Short held at 2×
		// had almost every delta refused as if it were a seek. Measured
		// 2026-08-08 on a 121-second Short played at 2× throughout:
		//
		//   12:01:23  seekbar advanced to 4s of 121s;  credited 2s (total 4s)
		//   12:01:53  seekbar advanced to 62s of 121s; credited 2s (total 6s)
		//   12:02:11  [finalize] played 6s of 121s → 5%, skipped
		//
		// 58 seconds of content traversed, 2 credited. The same Short at 1×
		// scrobbled at 98%. The MediaSession path has scaled by playback rate for
		// this exact reason since Phase 3 — "played" is content consumed, not
		// seconds elapsed — and this route simply never did.
		//
		// A Short publishes nothing to its MediaSession (FIELD §14.4), so there is
		// no rate to read here; the bound is the platform's fastest instead. It
		// stays a bound: nothing beyond twice wall-clock is admitted, so a seek is
		// still refused and no listen can be credited more content than could have
		// physically been played.
		val maxDelta = floor(elapsedMillis * MAX_PLAYBACK_RATE / 1000.0).toLong() +
			POSITION_JITTER_SECONDS
		if (next > previous) {
			val delta = next - previous
			return if (delta <= maxDelta) Delta(delta) else Delta(refusedSeconds = delta)
		}
		val wrapped = previous.toDouble() >= total * LOOP_END_FRACTION &&
			next.toDouble() <= total * LOOP_START_FRACTION &&
			previous - next >= total * LOOP_MIN_RESET_FRACTION
		if (!wrapped) return Delta()
		val traversed = (total - previous).coerceAtLeast(0) + next
		return if (traversed in 0..maxDelta) Delta(traversed, wrap = true) else Delta()
	}

	private fun snapshot(state: Active, finalized: Boolean): SessionSnapshot {
		// Nullable: an unreadable footer title no longer sinks the listen, it just
		// leaves identity to the watch-history route at finalize.
		val title: String?
		val handle: String?
		val signal: String?
		when (state) {
			is Active.Organic -> {
				title = state.title
				handle = state.ownerHandle
				signal = null
			}
			is Active.Ad -> {
				title = state.title ?: "Native YouTube Short advertisement"
				handle = null
				signal = state.signal
			}
		}
		val measuredMs = state.playedSeconds * 1000
		val inferredMs = state.inferredMillis
		val playedMs = measuredMs + inferredMs
		// Null, not zero, when YouTube rendered no seekbar to read a length from.
		// Absence of evidence has to stay absence all the way down: the rules
		// already know how to recover a length from the watch page, and the
		// corroborator must not read a zero as a contradicted duration.
		val durationMs = (state.totalSeconds * 1000).takeIf { it > 0 }
		val proofMissing = state.frozenForMissingProof
		// Deliberately *not* `proofMissing`. Every Short that ends is frozen for
		// missing proof first — scrolling to the next one included — so wiring
		// the marker to that put "(progress surface lost …)" on essentially every
		// finalize and made the honest PiP message meaningless (§3.1).
		val surfaceLost = state.progressSurfaceLost
		return SessionSnapshot(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube Shorts (foreground)",
			isTarget = true,
			title = title,
			artist = handle,
			album = null,
			durationMs = durationMs,
			positionMs = state.currentSeconds * 1000,
			playedMs = playedMs,
			loopDetected = state.loopDetected,
			explicitAdSignal = signal,
			playbackState = when {
				proofMissing -> "FOREGROUND_PROOF_MISSING"
				signal != null -> "FOREGROUND_SHORT_AD"
				else -> "FOREGROUND_SHORT"
			},
			isPlaying = !finalized && !proofMissing,
			foregroundProgressLost = surfaceLost,
			inferredPlayedMs = inferredMs,
			// Unknown until the resolver reads a length off the video's own page.
			percentPlayed = durationMs?.let { playedMs.toDouble() / it } ?: 0.0,
			identity = YouTubeProbe.Identity.SiteOnly(
				host = YouTubeProbe.YOUTUBE_PACKAGE,
				isMusic = false,
				source = "foreground native Shorts accessibility player; exact id pending",
			),
			notificationHint = null,
			metadataLines = listOfNotNull(
				"sourceProof = NATIVE_FOREGROUND_SHORT",
				"title = ${title ?: "<unread; resolved from watch history>"}",
				"ownerHandle = ${handle ?: "<ad observation>"}",
				"seekbar = ${state.currentSeconds}s of ${state.totalSeconds}s",
				"measuredPlayed = ${state.playedSeconds}s (position deltas only)",
				"foregroundProof = ${if (proofMissing) "missing/frozen" else "complete"}",
				"progressSurface = lost (seekbar container present, no readable time)"
					.takeIf { surfaceLost },
				("inferredPlayed = ${state.inferredMillis / 1000}s (content time inferred " +
					"while direct progress was missing or stalled; visible YouTube speed-chip " +
					"rate when present, otherwise paired window + audio at 1x)")
					.takeIf { state.inferredMillis > 0 },
			),
			// The seekbar the foreground route reads is a position, so this listen
			// is position-corroborated whenever it was ever readable.
			firstObservedPositionMs = (state.currentSeconds * 1000).takeIf { !surfaceLost },
			trackStartedAtEpochSec = state.startedAtEpochSec,
			trackInstanceToken = state.trackInstanceToken,
			sourceEpoch = state.sourceEpoch,
			sourceProof = SourceProof.NATIVE_FOREGROUND_SHORT,
			ownerHandle = handle,
			automaticWriteAuthorization = state.automaticWriteAuthorization,
		).also {
			Phase3Telemetry.snapshot(it, playbackRate = null, finalized = finalized)
		}
	}

	companion object {
		const val MISSING_PROOF_GRACE_MS = 3_000L

		/**
		 * How long the end-of-track grace is held open across a screen-off.
		 *
		 * Measured 2026-08-19 on the A36: the paired audio + visible-window
		 * evidence went false for 3.5s as the display turned off and then came
		 * back true while the Short was still playing. Eight seconds covers that
		 * transition with room for a slow one, and is short enough that a screen
		 * genuinely left off finalizes only a few seconds later than before.
		 *
		 * Deliberately not a change to [MISSING_PROOF_GRACE_MS]: the shared
		 * lifecycle timeout keeps its meaning everywhere else, and this window
		 * credits nothing on its own.
		 */
		const val DISPLAY_OFF_SETTLE_MS = 8_000L

		/**
		 * How long a footer title may be missing and still continue its Short.
		 *
		 * The measured blink is one to three seconds. This is deliberately far
		 * shorter than any navigation and longer than [MISSING_PROOF_GRACE_MS],
		 * so a blink spanning a brief freeze still continues.
		 */
		const val TITLE_BLINK_WINDOW_MS = 8_000L

		/**
		 * How long a Short that has just gone away may come back and resume.
		 *
		 * A tab switch away and back measured 7–14 seconds; thirty gives that
		 * room without letting an unrelated re-encounter half a minute later
		 * inherit someone else's seconds.
		 */
		const val RESUME_WINDOW_MS = 30_000L

		/**
		 * How long a Short may come back and resume when its **seekbar** says it
		 * is the same viewing.
		 *
		 * Thirty seconds covers a tab switch, which is what it was measured on. It
		 * does not cover minimizing YouTube and coming back, and that is the same
		 * interruption at human speed: measured 2026-08-09, a 107s Short left at
		 * `52s` and re-acquired at `52s of 107s` 105 seconds later started again
		 * from zero and finished under threshold having been watched right
		 * through. Matches [TrackProgressCarry.RESUMED_TTL_MS], because it is the
		 * same question about the same interruption.
		 */
		const val RESUMED_WINDOW_MS = 15 * 60_000L

		/**
		 * How far the returning seekbar may sit from where the Short stopped.
		 * Two polls of slack, the same jitter the live reader already tolerates.
		 */
		const val RESUME_POSITION_TOLERANCE_SECONDS = 5L

		/**
		 * How far in the Short must have been before its position may extend the
		 * window. Above the tolerance by design: a Short restarted from the top
		 * reports ~0 and must never fall inside the band around the carried value.
		 */
		const val RESUME_MIN_POSITION_SECONDS = 10L

		/**
		 * The longest a Short can be, and therefore the most that may be inferred
		 * for one whose length YouTube never rendered. Three minutes is the
		 * format's own published maximum.
		 */
		const val MAX_SHORT_DURATION_MS = 180_000L
		const val POSITION_JITTER_SECONDS = 2L

		/**
		 * The fastest YouTube will play anything: 2×, on the watch page's speed
		 * menu and on the Shorts press-and-hold gesture alike.
		 *
		 * Used only as the ceiling on how much content one wall-clock second may
		 * legitimately traverse. Raising it would start admitting seeks; leaving it
		 * at 1× refused a listen the viewer really did watch.
		 */
		const val MAX_PLAYBACK_RATE = 2.0
		private const val LOOP_END_FRACTION = 0.8
		private const val LOOP_START_FRACTION = 0.2
		private const val LOOP_MIN_RESET_FRACTION = 0.5
	}
}
