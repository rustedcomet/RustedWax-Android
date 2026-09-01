package com.rustedwax.app.detect

import com.rustedwax.core.SourceSessionId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local bridge between the separately granted service and SessionProbe. */
object NativeShortsObserver {

	data class Status(
		val connected: Boolean = false,
		val completePlayerProof: Boolean = false,
		val activeTitle: String? = null,
		val activeHandle: String? = null,
		val currentSeconds: Long? = null,
		val totalSeconds: Long? = null,
		val lastObservationAtMillis: Long? = null,
		val exactReason: String = "native Shorts accessibility service is not connected",
		/**
		 * The Short is still playing, but with nothing readable to measure it by.
		 *
		 * Already carried on every event; published here so the UI can tell
		 * "nothing is playing" apart from "playing in picture-in-picture, where
		 * YouTube draws no title, no handle and no seekbar, and the time is being
		 * credited from wall-clock instead". Those are the same
		 * [completePlayerProof] `false`, and reporting them as one made a working
		 * PiP listen read as a detection failure.
		 */
		val inferredPlaying: Boolean = false,
	)

	sealed interface Event {
		data object Connected : Event
		data class Parsed(
			val result: NativeShortParser.Result,
			val observedAtMillis: Long,
			/** Paired audio + visible-window evidence for absent or stalled progress. */
			val inferredPlaying: Boolean = false,
		) : Event
		data class Missing(
			val reason: String,
			val observedAtMillis: Long,
			/** Carries [NativeShortParser.Result.Invalid.progressSurfaceLost] through. */
			val progressSurfaceLost: Boolean = false,
			/** Evidence the Short is still playing despite having no progress surface. */
			val inferredPlaying: Boolean = false,
			/** Rate off YouTube's own speed chip, when the overlay is showing one. */
			val playbackRate: Double? = null,
			/** The display was not interactive when this observation was taken. */
			val displayOff: Boolean = false,
		) : Event
		data class Disconnected(val reason: String) : Event
	}

	private val _status = MutableStateFlow(Status())
	val status: StateFlow<Status> = _status.asStateFlow()

	@Volatile
	private var refreshNeeded = false

	fun connected() {
		_status.value = _status.value.copy(
			connected = true,
			inferredPlaying = false,
			exactReason = "connected; waiting for a complete foreground Shorts player",
		)
		publish(Event.Connected)
	}

	/** Publish a result already parsed and stabilized by the production source adapter. */
	fun accepted(
		result: NativeShortParser.Result,
		observedAtMillis: Long,
		inferredPlaying: Boolean = false,
	) {
		_status.value = when (result) {
			is NativeShortParser.Result.Organic -> Status(
				connected = true,
				completePlayerProof = true,
				activeTitle = result.title,
				activeHandle = result.ownerHandle,
				currentSeconds = result.currentSeconds,
				totalSeconds = result.totalSeconds,
				lastObservationAtMillis = observedAtMillis,
				exactReason = "complete foreground Short proof",
				inferredPlaying = inferredPlaying,
			)
			is NativeShortParser.Result.OrganicUnmeasured -> Status(
				connected = true,
				completePlayerProof = true,
				activeTitle = result.title,
				activeHandle = result.ownerHandle,
				currentSeconds = null,
				totalSeconds = null,
				lastObservationAtMillis = observedAtMillis,
				exactReason = "foreground Short proof without a progress surface — " +
					"YouTube is not rendering the seekbar",
				inferredPlaying = inferredPlaying,
			)
			// Keeps whatever the last complete proof said; this observation adds a
			// progress reading, not an identity.
			is NativeShortParser.Result.OrganicUnnamed -> _status.value.copy(
				connected = true,
				completePlayerProof = true,
				currentSeconds = result.currentSeconds,
				totalSeconds = result.totalSeconds,
				lastObservationAtMillis = observedAtMillis,
				exactReason = "foreground Short player measurable but its footer is off screen",
				inferredPlaying = inferredPlaying,
			)
			is NativeShortParser.Result.Ad -> Status(
				connected = true,
				completePlayerProof = true,
				activeTitle = result.title,
				currentSeconds = result.currentSeconds,
				totalSeconds = result.totalSeconds,
				lastObservationAtMillis = observedAtMillis,
				exactReason = "complete foreground Short player with literal ad label \"${result.signal}\"",
				inferredPlaying = inferredPlaying,
			)
			is NativeShortParser.Result.Invalid -> _status.value.copy(
				connected = true,
				completePlayerProof = false,
				activeTitle = null,
				activeHandle = null,
				currentSeconds = null,
				totalSeconds = null,
				lastObservationAtMillis = observedAtMillis,
				exactReason = result.reason,
				inferredPlaying = inferredPlaying,
			)
		}
		publish(Event.Parsed(result, observedAtMillis, inferredPlaying))
	}

	fun missing(
		reason: String,
		observedAtMillis: Long,
		progressSurfaceLost: Boolean = false,
		inferredPlaying: Boolean = false,
		playbackRate: Double? = null,
		displayOff: Boolean = false,
	) {
		_status.value = _status.value.copy(
			connected = true,
			completePlayerProof = false,
			activeTitle = null,
			activeHandle = null,
			currentSeconds = null,
			totalSeconds = null,
			lastObservationAtMillis = observedAtMillis,
			exactReason = reason,
			inferredPlaying = inferredPlaying,
		)
		publish(
			Event.Missing(
				reason,
				observedAtMillis,
				progressSurfaceLost,
				inferredPlaying,
				playbackRate,
				displayOff,
			),
		)
	}

	fun disconnected(reason: String) {
		refreshNeeded = false
		_status.value = Status(exactReason = reason)
		publish(Event.Disconnected(reason))
	}

	fun setRefreshNeeded(value: Boolean) {
		refreshNeeded = value
	}

	fun shouldRefresh(): Boolean = refreshNeeded

	private fun publish(event: Event) {
		val coordinator = ProbeHolder.current?.evidenceCoordinatorForProducers() ?: return
		val sourceSession = SourceSessionId(
			YouTubeProbe.YOUTUBE_PACKAGE,
			NativeSourceSwitches.epochFor(YouTubeProbe.YOUTUBE_PACKAGE),
		)
		coordinator.emit(EvidenceCoordinator.Event.NativeShortObserved(sourceSession, event))
	}
}
