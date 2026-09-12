package com.rustedwax.app.detect

import com.rustedwax.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A running window cannot credit more content than the last transport position
 * left in the item. Further credit requires another transport callback.
 *
 * These synthetic-identity fixtures preserve callback intervals with an
 * arbitrary elapsed-time origin. Both overdue-timer and track-change ordering
 * must cap a frozen window while allowing position-proven additional playback.
 */
class FrozenProcessPlaybackGapTest {

	private val youTubeMusic = PlaybackSourceCapabilities(
		republishesShorterDurations = true,
		requiresExactIdToCarryProgress = true,
		usesStoppedReplacementGrace = true,
		supportsPictureInPictureInference = true,
		republishesAlternateMediaDurations = true,
	)

	private val songMs = 222_958L
	private val songId = "aaaaaaaaaa2"

	private fun song() = TrackIdentity("Synthetic Song", "Synthetic Artist", "Synthetic Album", songMs, null)

	private fun playing(elapsedRealtimeMs: Long, positionMs: Long, previousPositionMs: Long?) =
		PlaybackInput.TransportChanged(
			transport = TransportState.PLAYING,
			speed = 1.0,
			previousPositionMs = previousPositionMs,
			newPositionMs = positionMs,
			rawPositionMs = positionMs,
			durationMs = songMs,
			transportHasExactId = false,
			elapsedRealtimeMs = elapsedRealtimeMs,
			nowMillis = 1_700_000_000_000 + elapsedRealtimeMs,
			nextInstanceToken = 2,
		)

	/** Metadata started the listen while PLAYING; the fixture PLAYING callbacks followed. */
	private fun beforeTheFreeze(reducer: PlaybackReducer): ListenState {
		var state = ListenState(
			trackIdentity = song(),
			instanceToken = 1,
			instanceEstablishedAtMillis = 0,
			startedAtEpochSec = 1_700_000_000,
			everPublishedMetadata = true,
			transport = TransportState.PLAYING,
			playingSinceElapsedMs = 464_873,
			timelineEstablished = true,
		)
		state = reducer.reduce(state, playing(464_935, 0, null)).state
		state = reducer.reduce(state, playing(466_013, 0, 0)).state
		// The structured catalog route attributed the presentation three minutes in.
		state = reducer.reduce(state, PlaybackInput.ExactIdEstablished(songId)).state
		state = reducer.reduce(
			state,
			PlaybackInput.PresentationAttributionEstablished(songId, songMs),
		).state
		return state
	}

	@Test
	fun `the overdue idle deadline after a freeze credits the song once, not 489s`() {
		val reducer = PlaybackReducer(youTubeMusic)
		val frozen = beforeTheFreeze(reducer)

		// Thaw: the overdue timer runs first, 488.7 s after the last callback.
		val deadline = reducer.reduce(
			frozen,
			PlaybackInput.IdleDeadlineReached(durationMs = songMs, elapsedRealtimeMs = 954_734),
		)
		assertTrue(deadline.effects.any { it is PlaybackEffect.Finalize })
		val finalized = reducer.reduce(
			deadline.state,
			PlaybackInput.FinalizeRequested("playback ran out", 954_745),
		).state

		assertTrue(finalized.finalized)
		assertTrue(
			"a frozen interval became ${finalized.playedMs}ms of a ${songMs}ms song",
			finalized.playedMs in songMs..(songMs + 2_000),
		)
	}

	@Test
	fun `a track change delivered first after the freeze credits the song once too`() {
		val reducer = PlaybackReducer(youTubeMusic)
		val frozen = beforeTheFreeze(reducer)

		val next = reducer.reduce(
			frozen,
			PlaybackInput.MetadataPublished(
				identity = TrackIdentity("Next Song", "Synthetic Artist", null, 347_084, null),
				namesTabOnly = false,
				outgoingTransportHasExactId = false,
				hasPreResolvedNativeId = false,
				outgoingTitle = "Synthetic Song",
				nowMillis = 1_700_000_000_000 + 954_734,
				elapsedRealtimeMs = 954_734,
				nextInstanceToken = 3,
			),
		)
		assertTrue(next.before.any { it is PlaybackEffect.Finalize })
		val outgoing = reducer.reduce(
			frozen,
			PlaybackInput.FinalizeRequested("track change", 954_734),
		).state

		assertTrue(
			"a frozen interval became ${outgoing.playedMs}ms of a ${songMs}ms song",
			outgoing.playedMs in songMs..(songMs + 2_000),
		)
	}

	@Test
	fun `continuous playback with a live clock still accrues normally`() {
		val reducer = PlaybackReducer(youTubeMusic)
		val state = beforeTheFreeze(reducer)

		// 150 s after the last callback, well inside the song: every ms is credited.
		assertEquals(
			(466_013L - 464_873L) + 150_000L,
			state.playedMsAt(466_013 + 150_000),
		)
	}

	@Test
	fun `a position the transport publishes again extends what can be credited`() {
		val reducer = PlaybackReducer(youTubeMusic)
		var state = beforeTheFreeze(reducer)
		// 140 s in, the listener seeks back to the start and plays the song through.
		state = reducer.reduce(state, playing(606_013, 0, 140_000)).state
		val finalized = reducer.reduce(
			state,
			PlaybackInput.FinalizeRequested("stopped", 606_013 + songMs),
		).state

		assertEquals(
			(466_013L - 464_873L) + 140_000L + songMs,
			finalized.playedMs,
		)
		assertTrue(finalized.playedMs >= (songMs * 1.6).toLong())
	}
}
