package com.rustedwax.app.detect

import com.rustedwax.core.SourceSessionId

/**
 * Process-local bridge carrying the watch player's ad state from the separately
 * granted native service to [SessionProbe].
 *
 * Every look is published, not only changes: an unlabelled presentation is
 * confirmed organic by being seen that way more than once, and the reducer, not
 * this bridge, decides what a repeated reading means.
 */
object NativeWatchAdObserver {

	fun observed(reading: NativeWatchAdParser.Reading) {
		val coordinator = ProbeHolder.current?.evidenceCoordinatorForProducers() ?: return
		coordinator.emit(
			EvidenceCoordinator.Event.NativeWatchAdObserved(
				sourceSession = SourceSessionId(
					YouTubeProbe.YOUTUBE_PACKAGE,
					NativeSourceSwitches.epochFor(YouTubeProbe.YOUTUBE_PACKAGE),
				),
				reading = reading,
			),
		)
	}

	/**
	 * Whether a native watch listen is playing, so the player is worth a look.
	 *
	 * Read from the published snapshots, exactly as the Shorts service's own
	 * outage detector reads them, so the service holds no probe state of its own.
	 */
	fun watchPlaybackActive(): Boolean = runCatching {
		ProbeHolder.current?.sessions?.value.orEmpty().any {
			it.packageName == YouTubeProbe.YOUTUBE_PACKAGE && !it.isForegroundShort && it.isPlaying
		}
	}.getOrDefault(false)
}
