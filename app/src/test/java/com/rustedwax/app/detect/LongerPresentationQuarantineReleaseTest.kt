package com.rustedwax.app.detect

import com.rustedwax.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A shorter same-title surface followed by a longer presentation starting over.
 *
 * Duration alone cannot distinguish the longer video from another advertisement.
 * Synthetic identity and an arbitrary elapsed-time origin retain the regression
 * intervals. Idle deadlines must not release a quarantine, and unproven longer
 * or shorter replacements must not spend quarantined playback time.
 */
class LongerPresentationQuarantineReleaseTest {

	private val nativeYouTube = PlaybackSourceCapabilities(
		republishesShorterDurations = true,
		requiresExactIdToCarryProgress = true,
		usesStoppedReplacementGrace = true,
		supportsPictureInPictureInference = true,
	)

	private val title = "Same Title Official Trailer"
	private val artist = "Trailer Channel"

	private fun identity(durationMs: Long) = TrackIdentity(title, artist, null, durationMs, null)

	private fun metadata(durationMs: Long, elapsedRealtimeMs: Long) =
		PlaybackInput.MetadataPublished(
			identity = identity(durationMs),
			namesTabOnly = false,
			outgoingTransportHasExactId = false,
			hasPreResolvedNativeId = false,
			outgoingTitle = title,
			nowMillis = 1_700_000_000_000 + elapsedRealtimeMs,
			elapsedRealtimeMs = elapsedRealtimeMs,
			nextInstanceToken = 2,
		)

	private fun transport(
		state: TransportState,
		elapsedRealtimeMs: Long,
		previousPositionMs: Long?,
		positionMs: Long,
		durationMs: Long,
	) = PlaybackInput.TransportChanged(
		transport = state,
		speed = 1.0,
		previousPositionMs = previousPositionMs,
		newPositionMs = positionMs,
		rawPositionMs = positionMs,
		durationMs = durationMs,
		transportHasExactId = false,
		elapsedRealtimeMs = elapsedRealtimeMs,
		nowMillis = 1_700_000_000_000 + elapsedRealtimeMs,
		nextInstanceToken = 2,
	)

	/** Production dispatches the extrapolated position immediately before every bundle. */
	private fun republish(
		reducer: PlaybackReducer,
		state: ListenState,
		positionMs: Long,
		durationMs: Long,
		elapsedRealtimeMs: Long,
	): PlaybackReducer.Transition {
		val positioned = reducer.reduce(
			state,
			PlaybackInput.PositionSeen(positionMs, establishFirst = false),
		).state
		return reducer.reduce(positioned, metadata(durationMs, elapsedRealtimeMs))
	}

	private fun notes(transition: PlaybackReducer.Transition): String =
		(transition.before + transition.effects)
			.filterIsInstance<PlaybackEffect.Note>()
			.joinToString(" | ") { it.message }

	/** The fixture callbacks up to the longer presentation's PLAYING from zero. */
	private fun throughLongerPresentationStart(reducer: PlaybackReducer): ListenState {
		var state = ListenState(
			trackIdentity = identity(77_000),
			instanceToken = 1,
			instanceEstablishedAtMillis = 0,
			startedAtEpochSec = 1_700_000_000,
			everPublishedMetadata = true,
		)
		state = reducer.reduce(
			state,
			transport(TransportState.PLAYING, 432_750, null, 2_533, 77_000),
		).state
		state = republish(reducer, state, 11_203, 77_000, 441_395).state
		state = reducer.reduce(
			state,
			transport(TransportState.STOPPED, 441_458, 11_266, 11_147, 77_000),
		).state
		state = reducer.reduce(
			state,
			transport(TransportState.OTHER, 441_468, 11_147, 11_147, 77_000),
		).state
		val replaced = republish(reducer, state, 11_147, 148_000, 441_470)
		assertTrue(notes(replaced), notes(replaced).contains("quarantining replacement measurement"))
		state = replaced.state
		state = reducer.reduce(
			state,
			transport(TransportState.OTHER, 441_472, 11_147, 0, 148_000),
		).state
		state = republish(reducer, state, 0, 148_000, 441_481).state
		state = reducer.reduce(
			state,
			transport(TransportState.PLAYING, 441_569, 0, 0, 148_000),
		).state
		// A picture-in-picture toggle republishes the bundle while the video is at 19 s.
		state = republish(reducer, state, 19_184, 148_000, 460_753).state
		assertEquals(
			"the provisional surface's 8.7s is the only banked time before release",
			8_708L,
			state.playedMs,
		)
		return state
	}

	@Test
	fun `the idle deadline during the quarantine changes nothing`() {
		val reducer = PlaybackReducer(nativeYouTube)
		val state = throughLongerPresentationStart(reducer)

		// 60 s into the 148 s presentation: it could still be a 77 s surface.
		val early = reducer.reduce(
			state,
			PlaybackInput.IdleDeadlineReached(durationMs = 77_000, elapsedRealtimeMs = 501_569),
		)

		assertEquals(state, early.state)
		assertEquals(emptyList<PlaybackEffect>(), early.before + early.effects)
		assertEquals(8_708L, early.state.playedMsAt(501_569))
	}

	@Test
	fun `a longer surface that never outlasts an established presentation stays quarantined`() {
		// The mid-roll direction that must not move: a 1192 s presentation earned
		// 325 s, then a 2163 s surface under the same title ran 47 s from zero.
		val reducer = PlaybackReducer(nativeYouTube)
		var state = ListenState(
			trackIdentity = identity(1_192_000),
			instanceToken = 1,
			instanceEstablishedAtMillis = 0,
			startedAtEpochSec = 1_700_000_000,
			everPublishedMetadata = true,
		)
		state = reducer.reduce(state, transport(TransportState.PLAYING, 0, null, 0, 1_192_000)).state
		state = republish(reducer, state, 325_000, 2_163_000, 325_000).state
		state = reducer.reduce(
			state,
			transport(TransportState.PLAYING, 326_000, 325_000, 40, 2_163_000),
		).state
		val deadline = reducer.reduce(
			state,
			PlaybackInput.IdleDeadlineReached(durationMs = 1_192_000, elapsedRealtimeMs = 373_000),
		)

		assertEquals(2_163_000L, deadline.state.durationReplacementMs)
		assertEquals(325_000L, deadline.state.playedMsAt(373_000))
		assertFalse((deadline.before + deadline.effects).any { it is PlaybackEffect.Finalize })
	}

	@Test
	fun `a shorter mid-roll surface is never released by its own progress`() {
		val reducer = PlaybackReducer(nativeYouTube)
		var state = ListenState(
			trackIdentity = identity(952_000),
			instanceToken = 1,
			instanceEstablishedAtMillis = 0,
			startedAtEpochSec = 1_700_000_000,
			everPublishedMetadata = true,
		)
		state = reducer.reduce(state, transport(TransportState.PLAYING, 0, null, 0, 952_000)).state
		state = republish(reducer, state, 300_000, 77_000, 300_000).state
		state = reducer.reduce(
			state,
			transport(TransportState.PLAYING, 300_100, 300_000, 0, 77_000),
		).state

		assertEquals(null, state.idleFinalizeDelayMs(300_100, durationMs = 952_000)?.takeIf { it < 600_000 })
		val deadline = reducer.reduce(
			state,
			PlaybackInput.IdleDeadlineReached(durationMs = 952_000, elapsedRealtimeMs = 1_300_000),
		)

		assertEquals(77_000L, deadline.state.durationReplacementMs)
		assertEquals(300_000L, deadline.state.playedMsAt(1_300_000))
	}
}
