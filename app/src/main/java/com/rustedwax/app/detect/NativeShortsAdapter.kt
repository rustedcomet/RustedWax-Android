package com.rustedwax.app.detect

import com.rustedwax.core.*

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
	 * every controller-backed source. The host owns the tracker, suppression,
	 * logging, and engine callback; finalization remains at the shared application
	 * boundary.
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
					pipWindowPresent = event.pipWindowPresent,
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
	 * This source boundary owns parsing, the structural
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

		val speedChip = result.playbackRate
		return ShortsSurfaceReading.Absent(
			reason = result.reason,
			progressSurfaceLost = result.progressSurfaceLost,
			inferredPlaying = result.progressSurfaceLost &&
				if (speedChip != null) capture.inferenceEnabled else capture.pipEvidence.playing,
			playbackRate = speedChip,
			pipWindowPresent = capture.pipEvidence.pausedButPresent,
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
	 * Whether native YouTube genuinely owns a picture-in-picture window.
	 *
	 * The whole of the picture-in-picture question, and deliberately nothing
	 * more. It was once answered by asking which package had resumed most
	 * recently; Android's multi-resume meant split-screen and a pop-up over
	 * fullscreen YouTube both answered "picture-in-picture" on a physical A36
	 * while the platform reported zero pinned tasks. Only Android's own window
	 * list can settle it, so this reads that and draws no inferences of its own.
	 *
	 * Fails closed in every direction: an empty list, a window whose owner cannot
	 * be established, and a picture-in-picture window belonging to another app
	 * are all "no". Saying no costs the ordinary missing-proof grace, which is
	 * what happened before this signal existed at all.
	 */
	fun readPictureInPictureWindows(windows: List<ShortsWindowFact>): Boolean =
		windows.any { it.inPictureInPictureMode && it.ownerPackage() == packageName }

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
		pipWindowPresent = pipEvidence.pausedButPresent,
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
			pinnedWindowPresent = pipPlaying,
		),
		inferenceEnabled,
	)
}

/**
 * One open window, reduced to the only facts picture-in-picture identification
 * needs.
 *
 * No bounds, no title, no layer, no contents — there is no reason to carry them
 * and every reason not to.
 */
data class ShortsWindowFact(
	val inPictureInPictureMode: Boolean,
	/**
	 * The window's owner package, or null where Android does not let this
	 * service read it.
	 *
	 * A function rather than a value so a window that is not in
	 * picture-in-picture is never asked who owns it: the narrow read is part of
	 * the contract, not an implementation detail of the caller.
	 */
	val ownerPackage: () -> String?,
)

/** Primitive public evidence used for surface-less playback. */
data class ShortsPipEvidence(
	val inferenceEnabled: Boolean,
	val mediaAudioStarted: Boolean,
	val visiblePinnedWindow: Boolean,
	/** Android reports a picture-in-picture window whose root package is YouTube. */
	val pinnedWindowPresent: Boolean = false,
) {
	val playing: Boolean
		get() = inferenceEnabled && mediaAudioStarted && visiblePinnedWindow

	/**
	 * The picture-in-picture window is still there and its audio has stopped.
	 *
	 * This is a paused Short, not a Short that went away, and the difference is
	 * the whole of §PiP-pause: it earns nothing, and it must not be finalized
	 * while the window it is playing in is still on screen.
	 */
	val pausedButPresent: Boolean
		get() = inferenceEnabled && pinnedWindowPresent && !mediaAudioStarted
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
		/** A paused-but-present picture-in-picture window; see [ShortsPipEvidence]. */
		val pipWindowPresent: Boolean = false,
	) : ShortsSurfaceReading
}
