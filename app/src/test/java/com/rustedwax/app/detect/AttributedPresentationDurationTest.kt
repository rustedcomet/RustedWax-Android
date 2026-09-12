package com.rustedwax.app.detect

import com.rustedwax.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Which length a listen reports once a presentation has been attributed.
 *
 * The longest length a title has claimed stays the answer wherever the installed
 * presentation is unproven — that is what stops an interstitial's shorter number
 * making a song look complete. An exact-item attribution of the installed
 * presentation is the one fact that outranks it.
 */
class AttributedPresentationDurationTest {

	private val youTubeMusic = PlaybackSourceCapabilities(
		republishesShorterDurations = true,
		requiresExactIdToCarryProgress = true,
		usesStoppedReplacementGrace = true,
		supportsPictureInPictureInference = true,
		republishesAlternateMediaDurations = true,
	)

	private val nativeYouTube = youTubeMusic.copy(republishesAlternateMediaDurations = false)

	private val songId = "aaaaaaaaaa3"

	private fun identity(durationMs: Long) =
		TrackIdentity("Synthetic Bolero", "Synthetic Orquesta", "Synthetic Album", durationMs, null)

	private fun metadata(durationMs: Long, elapsedRealtimeMs: Long) = PlaybackInput.MetadataPublished(
		identity = identity(durationMs),
		namesTabOnly = false,
		outgoingTransportHasExactId = false,
		hasPreResolvedNativeId = false,
		outgoingTitle = "Synthetic Bolero",
		nowMillis = 1_700_000_000_000 + elapsedRealtimeMs,
		elapsedRealtimeMs = elapsedRealtimeMs,
		nextInstanceToken = 2,
	)

	private fun playing(elapsedRealtimeMs: Long, durationMs: Long) = PlaybackInput.TransportChanged(
		transport = TransportState.PLAYING,
		speed = 1.0,
		previousPositionMs = null,
		newPositionMs = 0,
		rawPositionMs = 0,
		durationMs = durationMs,
		transportHasExactId = false,
		elapsedRealtimeMs = elapsedRealtimeMs,
	)

	private fun preRollThenSong(reducer: PlaybackReducer): ListenState {
		var state = ListenState(
			trackIdentity = identity(296_193),
			instanceToken = 1,
			instanceEstablishedAtMillis = 0,
			startedAtEpochSec = 1_700_000_000,
			everPublishedMetadata = true,
		)
		state = reducer.reduce(state, playing(0, 296_193)).state
		state = reducer.reduce(state, metadata(259_622, 350_000)).state
		return reducer.reduce(state, playing(350_100, 259_622)).state
	}

	@Test
	fun `the unattributed song presentation still reports the longest length`() {
		val state = preRollThenSong(PlaybackReducer(youTubeMusic))

		assertEquals(296_193L, state.establishedDurationMs(259_622))
	}

	@Test
	fun `an attributed presentation reports its own length over a longer pre-roll`() {
		val reducer = PlaybackReducer(youTubeMusic)
		var state = preRollThenSong(reducer)
		state = reducer.reduce(state, PlaybackInput.ExactIdEstablished(songId)).state
		state = reducer.reduce(state, PlaybackInput.PresentationAttributionEstablished(songId, 259_622)).state

		assertEquals(259_622L, state.establishedDurationMs(259_622))
		assertEquals(259_622L, state.establishedDurationMs(null))
	}

	@Test
	fun `a later unproven presentation does not inherit the attributed length`() {
		val reducer = PlaybackReducer(youTubeMusic)
		var state = preRollThenSong(reducer)
		state = reducer.reduce(state, PlaybackInput.ExactIdEstablished(songId)).state
		state = reducer.reduce(state, PlaybackInput.PresentationAttributionEstablished(songId, 259_622)).state
		// A 30 s surface under the same title replaces the proven song.
		state = reducer.reduce(state, metadata(30_000, 620_000)).state

		assertNotEquals(259_622L, state.establishedDurationMs(30_000))
	}

	@Test
	fun `native YouTube keeps the longest length across a shorter republish`() {
		val reducer = PlaybackReducer(nativeYouTube)
		var state = ListenState(
			trackIdentity = identity(415_000),
			instanceToken = 1,
			instanceEstablishedAtMillis = 0,
			startedAtEpochSec = 1_700_000_000,
			everPublishedMetadata = true,
		)
		state = reducer.reduce(state, playing(0, 415_000)).state
		state = reducer.reduce(state, metadata(11_000, 200_000)).state

		assertEquals(415_000L, state.establishedDurationMs(11_000))
	}
}
