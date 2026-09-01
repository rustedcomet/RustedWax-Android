package com.rustedwax.app.detect

import com.rustedwax.core.*
/**
 * Foreground native Shorts.
 *
 * ## What this owns
 *
 * `<redacted-private-path>`: accessibility-tree observations; parsing and
 * stabilization; foreground Short identity and progress; handover to
 * picture-in-picture; progress-loss inference; miniplayer exclusion.
 *
 * ## Why it is shaped differently from the other three
 *
 * The other adapters speak for one `MediaController`. This one speaks for a
 * surface that has **no controller at all**: a foreground Short is observed
 * through the accessibility tree, and its MediaSession — when there is one —
 * describes the same player from a second, weaker angle. `<redacted-private-path>`
 * §1 names the consequence: Shorts "require a handover between accessibility and
 * MediaSession state", and that handover lived inside `SessionProbe` as a 140-
 * line method whose every branch tested one package constant.
 *
 * So this is a host-scoped translator rather than a per-watch adapter: one
 * observation in, neutral [PlaybackInput] values out, and the host performs
 * them.
 *
 * It owns only the short-lived accessibility identity stabilizer. It holds no
 * playback tracker, touches no store, measures nothing, and cannot return a
 * `SessionSnapshot` — so it cannot finalize, even by accident. The host owns the
 * foreground tracker, suppression, log and engine callback, because
 * `<redacted-private-path>` puts finalization extraction in Phase 7.
 *
 * ## The separations that must survive
 *
 * - **Miniplayer exclusion.** A retained in-app mini-player is not a foreground
 *   Short. That is enforced by [NativeShortParser] on the tree's own structure;
 *   nothing here relaxes it.
 * - **No double credit.** Picture-in-picture inference is offered to the
 *   ordinary MediaSession watch *only* when the foreground route holds nothing —
 *   [pictureInPictureCredit] returns null otherwise — so a Short being tracked
 *   properly can never also be credited as inferred time.
 */
class NativeShortsAdapter {
	private val stabilizer = NativeShortStabilizer()

	/** The app whose foreground surface this reads. The one package constant here. */
	val packageName: String = YouTubeProbe.YOUTUBE_PACKAGE

	/** Whether this source is currently opted in at all. */
	fun isWatched(): Boolean = NativeSourceSwitches.acceptsPackage(packageName)

	/**
	 * Whether a foreground listen still belongs to the current generation.
	 *
	 * A Short acquired before an opt-out must not survive it.
	 */
	fun isCurrent(sourceEpoch: Long?): Boolean =
		NativeSourceSwitches.isSnapshotCurrent(packageName, sourceEpoch)

	/**
	 * One observation of the foreground surface, as this source understands it.
	 *
	 * Pure: no store is touched, nothing is measured, and — the point of this
	 * boundary — nothing that could carry a finalized listen is returned. What
	 * comes back is expressed in the same neutral [PlaybackInput] vocabulary as
	 * every controller-backed source. The host owns the tracker, the
	 * suppression, the log and the engine callback, exactly as `Architecture_Audit`
	 * Phase 7 requires finalization to stay put until it is extracted.
	 */
	fun read(
		event: NativeShortsObserver.Event,
		foregroundSurfaceOwned: Boolean,
		hostNowMillis: Long,
	): List<PlaybackInput> = when (event) {
		NativeShortsObserver.Event.Connected -> listOf(
			PlaybackInput.ForegroundSurfaceConnected(hostNowMillis),
		)

		is NativeShortsObserver.Event.Disconnected -> listOf(
			PlaybackInput.ForegroundSurfaceUnavailable(
				reason = event.reason,
				nowMillis = hostNowMillis,
				discard = true,
			),
		)

		is NativeShortsObserver.Event.Missing -> buildList {
			add(
				PlaybackInput.ForegroundSurfaceUnavailable(
					reason = event.reason,
					nowMillis = event.observedAtMillis,
					progressSurfaceLost = event.progressSurfaceLost,
					playing = event.inferredPlaying,
					playbackRate = event.playbackRate,
					displayOff = event.displayOff,
				),
			)
			// A Short opened and sent straight to PiP may never give the
			// foreground route enough proof to acquire it. Only then may the same
			// evidence be offered to the controller route; a latched Short consumes
			// it in ForegroundShortTracker and must never be credited twice.
			if (!foregroundSurfaceOwned && event.progressSurfaceLost) {
				add(
					PlaybackInput.PictureInPictureObserved(
						nowMillis = event.observedAtMillis,
						playing = event.inferredPlaying,
						durationMs = null,
					),
				)
			}
		}

		is NativeShortsObserver.Event.Parsed -> {
			// A parse that arrives after this source was switched off belongs to a
			// generation that no longer exists.
			val epoch = NativeSourceSwitches.epochFor(packageName)
			if (epoch == null) emptyList() else listOf(parsed(event, epoch))
		}
	}

	/**
	 * Interpret one captured accessibility tree.
	 *
	 * This is the boundary the Phase 4 table asks for: parsing, the structural
	 * miniplayer refusal it performs, and the decision about whether unmeasurable
	 * playback may be inferred at all, are **this source's**, not the Android
	 * service's. The service captures framework nodes and primitive facts and
	 * dispatches the verdict; it no longer decides what a Shorts tree means.
	 *
	 * Pure and Android-free: [NativeShortTree] is already a value, and the
	 * usage/audio/window and settings facts arrive as booleans.
	 */
	fun readSurface(capture: ShortsSurfaceCapture): ShortsSurfaceReading {
		val result = NativeShortParser.parse(capture.tree)
		if (result !is NativeShortParser.Result.Invalid) {
			val speedChip = when (result) {
				is NativeShortParser.Result.Organic -> result.playbackRate
				is NativeShortParser.Result.OrganicUnnamed -> result.playbackRate
				is NativeShortParser.Result.OrganicUnmeasured -> result.playbackRate
				is NativeShortParser.Result.Ad -> null
				is NativeShortParser.Result.Invalid -> null
			}
			return ShortsSurfaceReading.Proven(
				result = result,
				// This pair is consulted for every proven organic Short, but it earns
				// time only when the tracker sees that direct progress is absent or
				// stalled. Measured 2026-08-15 on a Galaxy A36: YouTube kept a visible,
				// parseable seekbar at one cached value for 63–104 seconds while the
				// Short continued playing. Restricting the pair to a missing seekbar
				// turned those complete watches into zero seconds.
				inferredPlaying = when (result) {
					is NativeShortParser.Result.Organic,
					is NativeShortParser.Result.OrganicUnnamed,
					is NativeShortParser.Result.OrganicUnmeasured,
					-> if (speedChip != null) capture.inferenceEnabled else capture.pipEvidence.playing
					is NativeShortParser.Result.Ad -> false
					is NativeShortParser.Result.Invalid -> false
				},
			)
		}
		// Only the picture-in-picture signature may accrue. Every other refusal
		// means the Short is gone, not unmeasurable, and must accrue nothing.
		//
		// A visible speed chip is the exception, and a stronger signal than the
		// pair PiP has to settle for. Measured 2026-08-08: holding a Short to play
		// it at 2x strips the overlay, so nothing is measurable, and the
		// audio/usage evidence PiP relies on did not answer for it either — so the
		// Short was dropped three seconds into every hold and finalized at whatever
		// it had earned. YouTube drawing `2x` over the player is that player saying
		// it is playing, and at what rate; it is drawn only while the gesture is
		// actually speeding playback up.
		val speedChip = result.playbackRate
		return ShortsSurfaceReading.Absent(
			reason = result.reason,
			progressSurfaceLost = result.progressSurfaceLost,
			inferredPlaying = result.progressSurfaceLost &&
				if (speedChip != null) capture.inferenceEnabled else capture.pipEvidence.playing,
			playbackRate = speedChip,
		)
	}

	/**
	 * Parse and stabilize one production accessibility capture at this boundary.
	 *
	 * [readSurface] remains the single-frame interpretation used by parser tests;
	 * the separately granted Android service uses this stateful entry point so
	 * transient outgoing-title/incoming-progress trees never leave the adapter.
	 */
	fun readStableSurface(
		capture: ShortsSurfaceCapture,
		observedAtMillis: Long,
	): ShortsSurfaceReading = when (val reading = readSurface(capture)) {
		is ShortsSurfaceReading.Absent -> {
			// Immediate PiP can retain the YouTube root and its player/time-bar
			// containers while stripping every readable identity/progress child. If
			// that is the first frame after a complete candidate, promote before the
			// ordinary Absent path resets it. The paired window+audio fact is read
			// directly here so a speed chip alone cannot weaken the identity gate.
			val pending = if (reading.progressSurfaceLost && capture.pipEvidence.playing) {
				stabilizer.promotePendingOrganicForImmediatePip(observedAtMillis)
			} else {
				null
			}
			if (pending != null) {
				ShortsSurfaceReading.Proven(pending, inferredPlaying = true)
			} else {
				stabilizer.reset()
				reading
			}
		}

		is ShortsSurfaceReading.Proven -> when (
			val decision = stabilizer.observe(reading.result, observedAtMillis)
		) {
			is NativeShortStabilizer.Decision.Accepted -> reading.copy(result = decision.result)
			is NativeShortStabilizer.Decision.Waiting -> ShortsSurfaceReading.Absent(
				reason = decision.reason,
				progressSurfaceLost = false,
				inferredPlaying = false,
				playbackRate = null,
				stabilityPending = true,
			)
		}
	}

	/**
	 * Interpret a missing/unavailable Android surface from framework primitives.
	 *
	 * This is deliberately stateful: reset, pending-candidate promotion and
	 * preservation all belong to the source stabilizer. The Android service may
	 * capture these facts and dispatch the ordered readings, but it cannot decide
	 * what a missing root means.
	 */
	fun readUnavailableSurface(
		facts: ShortsSurfaceUnavailableFacts,
	): ShortsUnavailableDecision {
		if (facts.kind != ShortsSurfaceUnavailableKind.SURFACE_GONE) {
			stabilizer.reset()
			return ShortsUnavailableDecision(
				readings = if (facts.kind == ShortsSurfaceUnavailableKind.LIFECYCLE_RESET) {
					emptyList()
				} else {
					listOf(facts.absent(progressLost = false, inferredPlaying = false))
				},
				preserveProofFreshness = false,
			)
		}

		val playingInPip = facts.pipEvidence.playing
		val promoted = if (playingInPip) {
			stabilizer.promotePendingOrganicForImmediatePip(facts.observedAtMillis)
		} else {
			null
		}
		if (!playingInPip) stabilizer.reset()
		return ShortsUnavailableDecision(
			readings = buildList {
				promoted?.let { add(ShortsSurfaceReading.Proven(it, inferredPlaying = true)) }
				add(facts.absent(progressLost = playingInPip, inferredPlaying = playingInPip))
			},
			preserveProofFreshness = playingInPip &&
				(promoted != null || facts.foregroundSurfaceOwned),
		)
	}

	private fun ShortsSurfaceUnavailableFacts.absent(
		progressLost: Boolean,
		inferredPlaying: Boolean,
	) = ShortsSurfaceReading.Absent(
		reason = reason,
		progressSurfaceLost = progressLost,
		inferredPlaying = inferredPlaying,
		playbackRate = null,
		displayOff = displayOff,
	)

	private fun parsed(
		event: NativeShortsObserver.Event.Parsed,
		epoch: Long,
	): PlaybackInput.ForegroundSurface = when (val parsed = event.result) {
		is NativeShortParser.Result.Organic -> PlaybackInput.ForegroundSurfaceObserved(
			identity = TrackIdentity(
				title = parsed.title,
				artist = parsed.ownerHandle,
				album = null,
				durationMs = parsed.totalSeconds * 1_000,
			),
			positionMs = parsed.currentSeconds * 1_000,
			durationMs = parsed.totalSeconds * 1_000,
			nowMillis = event.observedAtMillis,
			sourceEpoch = epoch,
			playing = event.inferredPlaying,
			playbackRate = parsed.playbackRate,
		)

		is NativeShortParser.Result.Ad -> PlaybackInput.ForegroundSurfaceObserved(
			identity = TrackIdentity(
				title = parsed.title,
				artist = null,
				album = null,
				durationMs = parsed.totalSeconds * 1_000,
			),
			positionMs = parsed.currentSeconds * 1_000,
			durationMs = parsed.totalSeconds * 1_000,
			nowMillis = event.observedAtMillis,
			sourceEpoch = epoch,
			playing = true,
			explicitAdSignal = parsed.signal,
		)

		is NativeShortParser.Result.OrganicUnmeasured ->
			PlaybackInput.ForegroundSurfaceObserved(
				identity = TrackIdentity(
					title = parsed.title,
					artist = parsed.ownerHandle,
					album = null,
					durationMs = null,
				),
				positionMs = null,
				durationMs = null,
				nowMillis = event.observedAtMillis,
				sourceEpoch = epoch,
				playing = event.inferredPlaying,
				playbackRate = parsed.playbackRate,
			)

		is NativeShortParser.Result.OrganicUnnamed ->
			PlaybackInput.ForegroundSurfaceObserved(
				identity = null,
				positionMs = parsed.currentSeconds * 1_000,
				durationMs = parsed.totalSeconds * 1_000,
				nowMillis = event.observedAtMillis,
				sourceEpoch = epoch,
				playing = event.inferredPlaying,
				playbackRate = parsed.playbackRate,
			)

		is NativeShortParser.Result.Invalid ->
			PlaybackInput.ForegroundSurfaceUnavailable(
				reason = parsed.reason,
				nowMillis = event.observedAtMillis,
			)
	}
}

/**
 * One accessibility capture, as primitives.
 *
 * The Android service's whole remaining job: read framework nodes and the
 * usage/audio/window and settings facts, and hand them over. Nothing here is an
 * Android type.
 */
data class ShortsSurfaceCapture(
	val tree: NativeShortTree,
	/** Independent public framework facts; only their pair can prove PiP playback. */
	val pipEvidence: ShortsPipEvidence,
	/** The owner has allowed inference from elapsed time where it is permitted. */
	val inferenceEnabled: Boolean,
) {
	/** Compatibility constructor for single-frame parser tests. */
	constructor(
		tree: NativeShortTree,
		pipPlaying: Boolean,
		inferenceEnabled: Boolean,
	) : this(
		tree,
		ShortsPipEvidence(
			// This legacy argument is already the fully gated pair. The separate
			// `inferenceEnabled` parameter below is the speed-chip permission.
			inferenceEnabled = pipPlaying,
			mediaAudioStarted = pipPlaying,
			visiblePinnedWindow = pipPlaying,
		),
		inferenceEnabled,
	)
}

/** Primitive public evidence used for surface-less playback. */
data class ShortsPipEvidence(
	val inferenceEnabled: Boolean,
	val mediaAudioStarted: Boolean,
	val visiblePinnedWindow: Boolean,
) {
	val playing: Boolean
		get() = inferenceEnabled && mediaAudioStarted && visiblePinnedWindow
}

enum class ShortsSurfaceUnavailableKind {
	SURFACE_GONE,
	NAVIGATION_RESET,
	PROOF_EXPIRED,
	CAPTURE_STALE,
	INELIGIBLE,
	LIFECYCLE_RESET,
}

data class ShortsSurfaceUnavailableFacts(
	val kind: ShortsSurfaceUnavailableKind,
	val reason: String,
	val observedAtMillis: Long,
	val displayOff: Boolean,
	val pipEvidence: ShortsPipEvidence,
	val foregroundSurfaceOwned: Boolean,
)

data class ShortsUnavailableDecision(
	val readings: List<ShortsSurfaceReading>,
	val preserveProofFreshness: Boolean,
)

/** What one captured tree means to this source. */
sealed interface ShortsSurfaceReading {

	/** Structural proof stands. */
	data class Proven(
		val result: NativeShortParser.Result,
		val inferredPlaying: Boolean,
	) : ShortsSurfaceReading

	/** No provable foreground Short in this capture. */
	data class Absent(
		val reason: String,
		val progressSurfaceLost: Boolean,
		val inferredPlaying: Boolean,
		val playbackRate: Double?,
		val displayOff: Boolean = false,
		val stabilityPending: Boolean = false,
	) : ShortsSurfaceReading
}
