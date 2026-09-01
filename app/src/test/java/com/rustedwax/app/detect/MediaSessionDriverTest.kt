package com.rustedwax.app.detect

import com.rustedwax.core.ListenState
import com.rustedwax.core.PlaybackEffect
import com.rustedwax.core.PlaybackInput
import com.rustedwax.core.PlaybackReducer
import com.rustedwax.core.PlaybackSourceCapabilities
import com.rustedwax.core.TrackIdentity
import com.rustedwax.core.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production driver coverage for callback inputs, effects, and terminal re-entrancy. */
class MediaSessionDriverTest {
	private val capabilities = PlaybackSourceCapabilities(
		republishesShorterDurations = false,
		requiresExactIdToCarryProgress = false,
		usesStoppedReplacementGrace = false,
		supportsPictureInPictureInference = false,
	)

	private fun initial() = ListenState(
		trackIdentity = TrackIdentity("Driver Track", "Driver Artist", null, 60_000),
		instanceToken = 1,
		instanceEstablishedAtMillis = 1,
		startedAtEpochSec = 1_800_000_000,
		everPublishedMetadata = true,
	)

	@Test
	fun `metadata transport and destroy inputs traverse the production driver`() {
		val effects = mutableListOf<PlaybackEffect>()
		var outermost = 0
		val driver = MediaSessionDriver(
			PlaybackReducer(capabilities),
			initial(),
			perform = { effect, _ -> effects += effect },
			afterOutermostDispatch = { outermost++ },
		)
		driver.dispatch(
			PlaybackInput.MetadataPublished(
				identity = TrackIdentity("Refined Driver Track", "Driver Artist", null, 60_000),
				namesTabOnly = false,
				outgoingTransportHasExactId = false,
				hasPreResolvedNativeId = false,
				outgoingTitle = "Driver Track",
				nowMillis = 2,
				elapsedRealtimeMs = 2,
				nextInstanceToken = 2,
			),
		)
		driver.dispatch(
			PlaybackInput.TransportChanged(
				transport = TransportState.PLAYING,
				speed = 1.0,
				previousPositionMs = null,
				newPositionMs = 0,
				rawPositionMs = 0,
				durationMs = 60_000,
				transportHasExactId = false,
				elapsedRealtimeMs = 3,
			),
		)
		driver.dispatch(PlaybackInput.SessionDestroyed(30_003, continuationOpen = false))

		assertEquals("Refined Driver Track", driver.state.trackIdentity.title)
		assertEquals(TransportState.PLAYING, driver.state.transport)
		assertTrue(effects.any { it is PlaybackEffect.OpenContinuation })
		assertEquals(3, outermost)
	}

	@Test
	fun `nested TrackFrozen terminal transition cannot be rolled back`() {
		lateinit var driver: MediaSessionDriver
		var freezeEffects = 0
		var outermost = 0
		driver = MediaSessionDriver(
			PlaybackReducer(capabilities),
			initial(),
			perform = { effect, _ ->
				if (effect is PlaybackEffect.FreezeAndReport) {
					freezeEffects++
					driver.dispatch(PlaybackInput.TrackFrozen)
				}
			},
			afterOutermostDispatch = { outermost++ },
		)
		driver.dispatch(PlaybackInput.FinalizeRequested("driver terminal", 60_000))

		assertTrue(driver.state.finalized)
		assertEquals(1, freezeEffects)
		assertEquals(1, outermost)
	}
}
