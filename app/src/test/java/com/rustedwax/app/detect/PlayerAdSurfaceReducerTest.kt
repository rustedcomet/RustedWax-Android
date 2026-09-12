package com.rustedwax.app.detect

import com.rustedwax.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reducer's half of the native watch-player ad gate, one property at a time. */
class PlayerAdSurfaceReducerTest {

	private val nativeApp = PlaybackSourceCapabilities(
		republishesShorterDurations = true,
		requiresExactIdToCarryProgress = true,
		usesStoppedReplacementGrace = true,
		supportsPictureInPictureInference = true,
	)
	private val reducer = PlaybackReducer(nativeApp)

	private val initial = ListenState(
		trackIdentity = TrackIdentity("Trailer", "Channel", null, 154_000),
		instanceToken = 1,
		instanceEstablishedAtMillis = 0,
		startedAtEpochSec = 1_700_000_000,
		everPublishedMetadata = true,
	)

	private fun ListenState.then(input: PlaybackInput): ListenState = reducer.reduce(this, input).state

	private fun playing(at: Long, position: Long = 0) = PlaybackInput.TransportChanged(
		transport = TransportState.PLAYING,
		speed = 1.0,
		previousPositionMs = null,
		newPositionMs = position,
		rawPositionMs = position,
		durationMs = 154_000,
		transportHasExactId = false,
		elapsedRealtimeMs = at,
	)

	private fun ad(surface: PlayerAdSurface, at: Long) = PlaybackInput.PlayerAdSurfaceObserved(
		surface = surface,
		signal = if (surface == PlayerAdSurface.VISIBLE) "Sponsored" else null,
		elapsedRealtimeMs = at,
	)

	@Test
	fun `no second under the player's ad label is measured by any route`() {
		val state = initial
			.then(playing(at = 0))
			.then(ad(PlayerAdSurface.ABSENT, at = 1_000))
			.then(ad(PlayerAdSurface.ABSENT, at = 4_000))
			.then(ad(PlayerAdSurface.VISIBLE, at = 10_000))
			// A transport callback under the label must not restart the clock.
			.then(playing(at = 20_000, position = 20_000))
			.then(ad(PlayerAdSurface.ABSENT, at = 40_000))

		assertNull(state.playerAdSignal)
		assertEquals(10_000L, state.playedMsAt(40_000))
		assertEquals(15_000L, state.playedMsAt(45_000))
	}

	@Test
	fun `the lag before a new presentation's first label is the advertisement's`() {
		val state = initial
			.then(playing(at = 0))
			.then(ad(PlayerAdSurface.VISIBLE, at = 1_200))

		assertTrue(state.presentationShownAsAd)
		assertEquals(0L, state.playedMsAt(60_000))
	}

	@Test
	fun `a presentation seen organic keeps its seconds when a label covers it`() {
		val state = initial
			.then(playing(at = 0))
			.then(ad(PlayerAdSurface.ABSENT, at = 1_000))
			.then(ad(PlayerAdSurface.ABSENT, at = 5_000))
			.then(ad(PlayerAdSurface.VISIBLE, at = 9_000))

		assertEquals(9_000L, state.playedMsAt(30_000))
		assertEquals(false, state.presentationShownAsAd)
	}

	@Test
	fun `a first look too late to be lag takes nothing back`() {
		val state = initial
			.then(playing(at = 0))
			.then(ad(PlayerAdSurface.VISIBLE, at = PlaybackReducer.PLAYER_AD_ONSET_REFUSAL_LIMIT_MS + 1_000))

		assertEquals(PlaybackReducer.PLAYER_AD_ONSET_REFUSAL_LIMIT_MS + 1_000, state.playedMsAt(90_000))
	}

	@Test
	fun `a label that was read keeps holding the clock while the player is out of sight`() {
		val state = initial
			.then(playing(at = 0))
			.then(ad(PlayerAdSurface.VISIBLE, at = 500))
			.then(ad(PlayerAdSurface.UNOBSERVED, at = 5_000))
			.then(playing(at = 6_000, position = 6_000))

		assertEquals(0L, state.playedMsAt(30_000))
	}

	@Test
	fun `repeating a look that changes nothing is the same state`() {
		val labelled = initial.then(playing(at = 0)).then(ad(PlayerAdSurface.VISIBLE, at = 500))
		val again = reducer.reduce(labelled, ad(PlayerAdSurface.VISIBLE, at = 1_500))
		assertEquals(labelled, again.state)
		assertTrue(again.effects.isEmpty())
	}

	@Test
	fun `no idle deadline runs while an advertisement is in progress`() {
		val labelled = initial.then(playing(at = 0)).then(ad(PlayerAdSurface.VISIBLE, at = 500))
		assertNull(labelled.idleFinalizeDelayMs(1_000, 154_000))
		val transition = reducer.reduce(
			labelled,
			PlaybackInput.IdleDeadlineReached(durationMs = 154_000, elapsedRealtimeMs = 500_000),
		)
		assertTrue(transition.effects.none { it is PlaybackEffect.Finalize })
	}

	@Test
	fun `the foreground Shorts route is not described by the watch player`() {
		val suppressed = initial.then(
			PlaybackInput.ForegroundShortTookOver(shortDescribesSameItem = false, elapsedRealtimeMs = 0),
		)
		assertEquals(suppressed, suppressed.then(ad(PlayerAdSurface.VISIBLE, at = 100)))
	}

	@Test
	fun `a listen ending right after its label cleared credits none of the advertisement`() {
		val ended = initial
			.then(playing(at = 0))
			.then(ad(PlayerAdSurface.VISIBLE, at = 500))
			.then(ad(PlayerAdSurface.ABSENT, at = 30_000))
			.then(PlaybackInput.FinalizeRequested("track change", elapsedRealtimeMs = 31_500))

		assertTrue(ended.finalized)
		assertEquals(0L, ended.playedMs)
	}
}
