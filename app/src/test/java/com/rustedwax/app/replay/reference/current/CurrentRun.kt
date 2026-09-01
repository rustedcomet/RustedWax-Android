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
import com.rustedwax.app.replay.reference.phase01.resetSharedState

/**
 * The same script, through the implementation that ships today.
 *
 * ## Why this exists
 *
 * `Phase01Parity.ReducerRun` compares the old state machine against
 * `PlaybackReducer` by translating each script step into a `PlaybackInput` by
 * hand. That translation is harness code: it proves the two *reducers* agree
 * given equivalent input, and says nothing about the host that produces the
 * input in production — which is where continuation, carry, the stopped grace,
 * the idle deadline, session replacement and listener lifecycle actually live.
 * Most of `<redacted-private-path>` Phase 3's list is in that half.
 *
 * So this side runs the real one. [SessionProbe] in this package is the shipping
 * `detect/SessionProbe.kt`, body byte-identical, made executable on a JVM by the
 * same header transform that makes the pre-migration reference executable —
 * `tools/phase03/generate-current-mirror.sh`, re-checked against the live
 * production file by [CurrentMirrorProvenanceTest] on every run. The comparison
 * is therefore
 *
 * ```text
 * recorded pre-migration Watch   vs   current Watch host + PlaybackReducer
 * ```
 *
 * with one script driving both through identical `MediaController` callbacks on
 * one virtual clock, and every production singleton downstream shared.
 *
 * ## What it still is not
 *
 * A mirror is not the shipped class file, and `Android.kt` is not Android. What
 * this cannot prove is that the real platform delivers callbacks in the order
 * and shape the stand-ins do; that is the instrumented gate in
 * `app/src/androidTest`, and it is reported separately rather than folded in
 * here.
 */
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
	): List<SessionSnapshot> {
		resetSharedState()
		val stage = ParityStage(packageName)
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
		LegacyEvidenceCallbacks.nativeShortEvent = null
		stage.time.drain()
		SystemClock.current = VirtualTime()
		return finalized.toList()
	}
}
