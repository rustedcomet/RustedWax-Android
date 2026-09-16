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

	data class UnmeasuredObservation(
		val title: String?,
		val ownerHandle: String,
		val observedAtMillis: Long,
		val sourceEpoch: Long,
		val playing: Boolean,
		val playbackRate: Double? = null,
	)

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
		 * Bounded picture-in-picture inference is crediting this Short *now*.
		 *
		 * Exactly the `surfaceLost && inferredPlaying` predicate that authorizes
		 * the credit, kept rather than recomputed, so presentation can tell an
		 * actively inferred PiP stretch from a real pause. Both freeze the
		 * seekbar and both set [frozenForMissingProof], which is why the two
		 * collapsed into one paused-looking value before (#12).
		 *
		 * Presentation only — nothing here is scored, and it must never be
		 * derived from [inferredMillis], which says inference *happened*, not
		 * that it is happening. It follows the inference anchor: set when a
		 * step is credited, cleared by the same evidence that drops the anchor,
		 * and left alone by a refusal that carries no playback evidence either
		 * way.
		 */
		abstract val pipInferenceActive: Boolean

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
			override val pipInferenceActive: Boolean = false,
			override val inferredMillis: Long = 0,
			/** Inferred time already covering movement since [currentSeconds]. */
			val inferredSincePositionMillis: Long = 0,

			val bankedFullListen: Boolean = false,

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
			override val pipInferenceActive: Boolean = false,
			override val inferredMillis: Long = 0,
		) : Active()
	}

	private var active: Active? = null
	private var missingSinceMillis: Long? = null

	private var unmeasuredTitleBlinkStartedAtMillis: Long? = null

	/**
	 * When the display was first seen non-interactive while proof was missing.
	 *
	 * Anchors the bounded [DISPLAY_OFF_SETTLE_MS] hold in [proofMissing]. Cleared
	 * whenever proof comes back, so a later screen-off gets its own window rather
	 * than inheriting an expired one.
	 */
	private var displayOffSinceMillis: Long? = null

	private var refusedJump: String? = null

	/** Marks an Organic as having been handed over; an Ad carries no listen to repeat. */
	private fun Active.markTerminalFinalizationEmitted(): Active = when (this) {
		is Active.Organic -> copy(terminalFinalizationEmitted = true)
		is Active.Ad -> this
	}

	private val Active.alreadyFinalized: Boolean
		get() = (this as? Active.Organic)
			?.let { it.bankedFullListen || it.terminalFinalizationEmitted } == true

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
				pipWindowPresent = input.pipWindowPresent,
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
			pipInferenceActive = false,
			progressSurfaceLost = true,
			inferredMillis = inferredMillis,
			inferredSincePositionMillis = current.inferredSincePositionMillis + credited,
		)
		missingSinceMillis = null
		displayOffSinceMillis = null

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

	fun proofMissing(
		nowMillis: Long,
		reason: String,
		progressSurfaceLost: Boolean = false,
		inferredPlaying: Boolean = false,

		playbackRate: Double? = null,

		displayOff: Boolean = false,

		/**
		 * The source's picture-in-picture window is still on screen and its audio
		 * has stopped: the viewer paused, they did not leave.
		 */
		pipWindowPresent: Boolean = false,
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
		// Follows the anchor, for the same reasons and in the same branches. Set
		// where a step is credited; cleared where the anchor is dropped, which is
		// the evidence that actually says "not playing"; and left alone by a
		// refusal that says nothing either way, so the once-a-second watchdog
		// cannot flicker the card between playing and paused between two real
		// PiP observations.
		var pipInferenceActive = current.pipInferenceActive
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
			pipInferenceActive = true
		} else if (progressSurfaceLost || pipWindowPresent) {
			// This capture is the PiP signature, or the window standing there with
			// its audio stopped, and either way the evidence said not playing. That
			// is a genuine pause: drop the anchor so the paused interval cannot be
			// back-filled as playback when it resumes.
			inference = inference?.observe(nowMillis, playing = false)?.next
			pipInferenceActive = false
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
				pipInferenceActive = pipInferenceActive,
				inferredMillis = inferredMillis,
				inferredSincePositionMillis =
					current.inferredSincePositionMillis + credited,
			)
			is Active.Ad -> current.copy(
				frozenForMissingProof = true,
				progressSurfaceLost = surfaceLost,
				pipInferenceActive = pipInferenceActive,
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
		// A Short paused in picture-in-picture has not gone anywhere: its own
		// window is still on screen and only the audio stopped. Hold the same
		// listen open — crediting nothing, keeping its token, its measured
		// seconds and its inference ledger — for exactly as long as that window
		// lasts, so resuming continues this listen instead of needing fullscreen
		// to rebuild one from scratch.
		//
		// No timeout is invented here and the generic grace is untouched: the
		// moment the window goes, this is false again and the ordinary grace runs
		// and finalizes exactly as it always did. The anchor was already dropped
		// above, so the paused interval cannot be back-filled when it resumes.
		//
		// Deliberately not also requiring the surface-lost signature: a Short
		// paused in the very first moments of picture-in-picture never earned one,
		// and that is the case this exists for. The window evidence is specific
		// enough on its own — it is false whenever YouTube is the app in front, so
		// a swipe, a scroll or a trip back to the feed still finalizes as before.
		//
		// Excludes a listen already banked at its full length: that one is scored,
		// and holding it open would keep a finished viewing on screen forever.
		if (pipWindowPresent && !inferring && !current.alreadyFinalized) {
			missingSinceMillis = nowMillis
			return Update(
				active = snapshot(active ?: current, finalized = false),
				completeForegroundProof = false,
				diagnostic = "$reason; Short paused in picture-in-picture with its window " +
					"still on screen — holding the same listen and crediting nothing " +
					"(inferred total ${inferredMillis / 1000}s, measured ${current.playedSeconds}s)",
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
				pipInferenceActive = false,
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
				pipInferenceActive = false,
				progressSurfaceLost = false,
				inferredSincePositionMillis = 0,
			)
			return inferWhilePositionStalled(recovered, observation)
		}

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
				pipInferenceActive = false,
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
			// Gated on the freeze that produced it as well as on the flag, so a
			// value left over from an earlier PiP stretch cannot outlive the
			// stretch: the moment a readable seekbar or a finalize returns, this
			// is false whatever the carried field still says.
			pipInferredPlaying = !finalized && proofMissing && state.pipInferenceActive,
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

		const val DISPLAY_OFF_SETTLE_MS = 8_000L

		/**
		 * How long a footer title may be missing and still continue its Short.
		 *
		 * A brief blink lasts only a few seconds. This is deliberately far
		 * shorter than any navigation and longer than [MISSING_PROOF_GRACE_MS],
		 * so a blink spanning a brief freeze still continues.
		 */
		const val TITLE_BLINK_WINDOW_MS = 8_000L

		/**
		 * How long a Short that has just gone away may come back and resume.
		 *
		 * Thirty seconds accommodates a brief tab switch without letting
		 * an unrelated re-encounter half a minute later
		 * inherit someone else's seconds.
		 */
		const val RESUME_WINDOW_MS = 30_000L

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
