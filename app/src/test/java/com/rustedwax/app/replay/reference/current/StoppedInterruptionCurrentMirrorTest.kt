package com.rustedwax.app.replay.reference.current

import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.NativePreResolvedRoute
import com.rustedwax.app.detect.StoppedInterruption
import com.rustedwax.app.replay.reference.phase01.Context
import com.rustedwax.app.replay.reference.phase01.MediaController
import com.rustedwax.app.replay.reference.phase01.MediaMetadata
import com.rustedwax.app.replay.reference.phase01.MediaSessionManager
import com.rustedwax.app.replay.reference.phase01.PlaybackState
import com.rustedwax.app.replay.reference.phase01.SystemClock
import com.rustedwax.app.replay.reference.phase01.VirtualTime
import com.rustedwax.app.replay.reference.phase01.VirtualSystem
import com.rustedwax.app.replay.reference.phase01.resetSharedState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shipping SessionProbe callback/timer coverage for the display-off STOPPED gate. */
class StoppedInterruptionCurrentMirrorTest {

	private val durationMs = 240_000L

	private data class Run(
		val time: VirtualTime,
		val manager: MediaSessionManager,
		val controller: MediaController,
		val probe: SessionProbe,
		val finalized: MutableList<SessionSnapshot>,
	)

	private fun run(): Run {
		resetSharedState()
		TrackProgressCarry.clear()
		val time = VirtualTime()
		SystemClock.current = time
		val manager = MediaSessionManager()
		val controller = MediaController("com.google.android.youtube").apply {
			seed(
				MediaMetadata(
					mapOf(
						MediaMetadata.METADATA_KEY_TITLE to "Interrupted item",
						MediaMetadata.METADATA_KEY_ARTIST to "Channel",
						MediaMetadata.METADATA_KEY_DURATION to durationMs,
					),
				),
				PlaybackState(
					PlaybackState.STATE_PLAYING,
					position = 71_065,
					lastPositionUpdateTime = time.elapsedRealtime(),
				),
			)
		}
		manager.publish(listOf(controller))
		val finalized = mutableListOf<SessionSnapshot>()
		val probe = SessionProbe(
			Context(manager),
			acceptsPackage = { true },
			displayInteractive = { false },
		).also { it.onTrackFinalized = { snapshot -> finalized += snapshot } }
		probe.start()
		time.drain()
		return Run(time, manager, controller, probe, finalized)
	}

	/**
	 * The reproduced native shape, including resolver-owned exact carry authority.
	 * The transport metadata remains exact-ID-less so STOPPED still takes the
	 * replacement grace instead of the reducer's immediate exact-ID branch.
	 */
	private fun deadlineRun(): Run {
		resetSharedState()
		TrackProgressCarry.clear()
		val time = VirtualTime()
		SystemClock.current = time
		VirtualSystem.use(time)
		val manager = MediaSessionManager()
		val controller = MediaController("com.google.android.youtube").apply {
			seed(
				MediaMetadata(
					mapOf(
						MediaMetadata.METADATA_KEY_TITLE to "Interrupted item",
						MediaMetadata.METADATA_KEY_ARTIST to "Channel",
						MediaMetadata.METADATA_KEY_DURATION to durationMs,
					),
				),
				PlaybackState(
					PlaybackState.STATE_PLAYING,
					position = 71_065,
					lastPositionUpdateTime = time.elapsedRealtime(),
				),
			)
		}
		manager.publish(listOf(controller))
		val finalized = mutableListOf<SessionSnapshot>()
		val probe = SessionProbe(
			Context(manager),
			acceptsPackage = { true },
			displayInteractive = { false },
		).also { candidate ->
			candidate.onTrackFinalized = { snapshot -> finalized += snapshot }
			candidate.onNativeIdentityRequested = { _, completion ->
				completion(
					SessionProbe.NativeResolvedIdentity(
						videoId = "9bZkp7q19f0",
						route = NativePreResolvedRoute.HISTORY,
					),
				)
			}
		}
		probe.start()
		// The binding is constructed from an already-playing controller. One real
		// callback asks the production adapter/reducer path for carry authority.
		controller.publishPlaybackState(
			PlaybackState(
				PlaybackState.STATE_PLAYING,
				position = 71_065,
				lastPositionUpdateTime = time.elapsedRealtime(),
			),
		)
		time.drain()
		// Bank a non-zero amount whose survival can be observed on the replacement.
		time.advance(1_000)
		return Run(time, manager, controller, probe, finalized)
	}

	private fun Run.openContinuationWithTwentySecondsLeft(): Long {
		controller.publishPlaybackState(
			PlaybackState(PlaybackState.STATE_STOPPED, position = 0),
		)
		controller.publishPlaybackState(
			PlaybackState(PlaybackState.STATE_STOPPED, position = 72_065),
		)
		val carriedPlayedMs = probe.sessions.value.single().playedMs
		time.advance(StoppedInterruption.SCREEN_OFF_HOLD_CAP_MS - 20_000)
		// This is the shipping active-session-loss path: syncControllers disposes
		// the real binding and its reducer opens the real TrackProgressCarry entry.
		manager.publish(emptyList())
		time.drain()
		assertTrue(
			"the shipping binding did not hand a continuation to TrackProgressCarry",
			TrackProgressCarry.hasPackage("com.google.android.youtube"),
		)
		return carriedPlayedMs
	}

	private fun Run.replaceAndReadPlayedMs(): Long {
		val replacement = MediaController("com.google.android.youtube").apply {
			seed(
				MediaMetadata(
					mapOf(
						MediaMetadata.METADATA_KEY_TITLE to "Interrupted item",
						MediaMetadata.METADATA_KEY_ARTIST to "Channel",
						MediaMetadata.METADATA_KEY_DURATION to durationMs,
					),
				),
				PlaybackState(
					PlaybackState.STATE_PLAYING,
					position = 72_065,
					lastPositionUpdateTime = time.elapsedRealtime(),
				),
			)
		}
		manager.publish(listOf(replacement))
		// Resolve the same exact id through the replacement binding's production
		// RequestCarryAuthority -> restoreCarriedProgress path.
		replacement.publishPlaybackState(
			PlaybackState(
				PlaybackState.STATE_PLAYING,
				position = 72_065,
				lastPositionUpdateTime = time.elapsedRealtime(),
			),
		)
		time.drain()
		return probe.sessions.value.single().playedMs
	}

	@After
	fun tearDown() {
		TrackProgressCarry.clear()
		VirtualSystem.useRealTime()
		resetSharedState()
		SystemClock.current = VirtualTime()
	}

	@Test
	fun `lock reset and restore holds the shipping watch past the old grace`() {
		val run = run()
		run.controller.publishPlaybackState(
			PlaybackState(PlaybackState.STATE_STOPPED, position = 0),
		)
		run.controller.publishPlaybackState(
			PlaybackState(PlaybackState.STATE_STOPPED, position = 71_065),
		)

		run.time.advance(SessionProbe.NATIVE_STOPPED_FINALIZE_GRACE_MS)

		assertEquals(0, run.finalized.size)
		run.probe.stop(finalizeTracks = false)
	}

	@Test
	fun `display-off genuine stop finalizes through the shipping timer`() {
		val run = run()
		run.controller.publishPlaybackState(
			PlaybackState(PlaybackState.STATE_STOPPED, position = 71_065),
		)

		run.time.advance(SessionProbe.NATIVE_STOPPED_FINALIZE_GRACE_MS)

		assertEquals(1, run.finalized.size)
		run.probe.stop(finalizeTracks = false)
	}

	@Test
	fun `display-off media end finalizes even after a surface reset`() {
		val run = run()
		run.controller.publishPlaybackState(
			PlaybackState(PlaybackState.STATE_STOPPED, position = 0),
		)
		run.controller.publishPlaybackState(
			PlaybackState(PlaybackState.STATE_STOPPED, position = durationMs),
		)

		run.time.advance(SessionProbe.NATIVE_STOPPED_FINALIZE_GRACE_MS)

		assertEquals(1, run.finalized.size)
		run.probe.stop(finalizeTracks = false)
	}

	@Test
	fun `shipping handoff remains claimable one millisecond before its original deadline`() {
		val run = deadlineRun()
		val carriedPlayedMs = run.openContinuationWithTwentySecondsLeft()

		run.time.advance(19_999)

		assertEquals(carriedPlayedMs, run.replaceAndReadPlayedMs())
		assertTrue(carriedPlayedMs > 0)
		run.probe.stop(finalizeTracks = false)
	}

	@Test
	fun `shipping handoff rejects a claim at its original deadline`() {
		val run = deadlineRun()
		run.openContinuationWithTwentySecondsLeft()

		run.time.advance(20_000)

		assertEquals(0, run.replaceAndReadPlayedMs())
		run.probe.stop(finalizeTracks = false)
	}

	@Test
	fun `shipping handoff rejects a claim after its original deadline`() {
		val run = deadlineRun()
		run.openContinuationWithTwentySecondsLeft()

		run.time.advance(20_001)

		assertEquals(0, run.replaceAndReadPlayedMs())
		run.probe.stop(finalizeTracks = false)
	}
}
