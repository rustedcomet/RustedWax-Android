package com.rustedwax.app.replay.reference.current

import com.rustedwax.core.*
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.core.SourceSessionId
import com.rustedwax.app.detect.EvidenceCoordinator
import com.rustedwax.app.detect.NativeShortsObserver
import com.rustedwax.app.detect.LegacyEvidenceCallbacks
import com.rustedwax.app.detect.NativeSourceSwitches
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.app.replay.reference.phase01.PARITY_PACKAGE
import com.rustedwax.app.replay.reference.phase01.ParityStage
import com.rustedwax.app.replay.reference.phase01.ParityStep
import com.rustedwax.app.replay.reference.phase01.ProbeControl
import com.rustedwax.app.replay.reference.phase01.SystemClock
import com.rustedwax.app.replay.reference.phase01.UrlWatcherService
import com.rustedwax.app.replay.reference.phase01.VirtualTime
import com.rustedwax.app.replay.reference.phase01.VirtualSystem
import com.rustedwax.app.replay.reference.phase01.resetSharedState

object CurrentRun {

	/**
	 * The frozen listens the current probe emits for a script.
	 *
	 * Deliberately the same shape as `ReferenceRun.snapshots`, including the
	 * shared-state reset and the disabled address-bar watcher, because anything
	 * this function does differently is a difference the comparison would report
	 * as the migration's.
	 */
	fun snapshots(
		script: List<ParityStep>,
		packageName: String = PARITY_PACKAGE,
		virtualWallClock: Boolean = false,
	): List<SessionSnapshot> {
		resetSharedState()
		TrackProgressCarry.clear()
		val stage = ParityStage(packageName)
		if (virtualWallClock) VirtualSystem.use(stage.time) else VirtualSystem.useRealTime()
		UrlWatcherService.enabled = false

		val probe = SessionProbe(stage.context)
		LegacyEvidenceCallbacks.nativeShortEvent = { event ->
			probe.acceptEvidenceEventForHarness(
				EvidenceCoordinator.Event.NativeShortObserved(
					SourceSessionId(
						YouTubeProbe.YOUTUBE_PACKAGE,
						NativeSourceSwitches.epochFor(YouTubeProbe.YOUTUBE_PACKAGE),
					),
					event,
				),
			)
		}
		val finalized = mutableListOf<SessionSnapshot>()
		probe.onTrackFinalized = { finalized += it }

		val control = object : ProbeControl {
			override fun start() = probe.start()
			override fun stop(finalizeTracks: Boolean) = probe.stop(finalizeTracks)
		}

		stage.begin(control)
		script.forEach { stage.perform(it, control) }

		probe.stop()
		TrackProgressCarry.clear()
		VirtualSystem.useRealTime()
		LegacyEvidenceCallbacks.nativeShortEvent = null
		stage.time.drain()
		SystemClock.current = VirtualTime()
		return finalized.toList()
	}
}
