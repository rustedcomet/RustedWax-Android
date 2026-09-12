package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two sponsored surfaces under a synthetic trailer's title, then the trailer.
 *
 * Outlasting a shorter same-title surface does not prove a longer presentation
 * is organic. These replay cases prevent advertisement time from qualifying
 * the trailer and preserve credit for a separately established trailer timeline.
 */
class NativeYouTubeSponsoredSameTitleSurfacesReplayTest : ReplayScenarioTest() {

	private val videoId = "aaaaaaaaaa4"
	private val title = "Synthetic Official Trailer 2 (2026)"
	private val channel = "Synthetic Trailers"
	private val trailerMs = 154_000L

	private fun harness(): ReplayHarness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE).also { harness ->
		harness.env.facts.put(
			VideoFacts(
				videoId = videoId,
				title = title,
				author = channel,
				lengthSeconds = trailerMs / 1000,
				category = "Film & Animation",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
		harness.env.identity.search = {
			VideoResolutionAttempt(
				resolution = VideoResolution(
					videoId = videoId,
					source = "search",
					title = title,
					channel = channel,
					lengthSeconds = trailerMs / 1000,
					uniquelyResolved = true,
				),
			)
		}
	}

	private fun metadata(durationMs: Long) =
		PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = durationMs)

	private fun nextTrailer() = PlaybackEvent.SessionMetadata(
		title = "Next Synthetic Trailer (2026)",
		artist = channel,
		durationMs = 142_000,
	)

	/** The two sponsored surfaces, in the fixture, up to the moment the second one stops. */
	private fun sponsoredSurfaces(): List<PlaybackEvent> = listOf(
		metadata(71_000),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 3_117),
		PlaybackEvent.Advance(67_795),
		PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 70_912),
		PlaybackEvent.Advance(5_160),
		PlaybackEvent.PlaybackStateChanged(playing = false, positionMs = 70_912),
		metadata(71_000),
		metadata(160_000),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 70_912),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		// The idle timer at the longer surface outlasts the first: the 160 s
		// surface has now played longer than the whole 71 s one.
		PlaybackEvent.Advance(71_988),
		PlaybackEvent.IdleDeadlineReached,
		PlaybackEvent.Advance(87_556),
		PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 159_544),
		PlaybackEvent.Advance(4_967),
		PlaybackEvent.PlaybackStateChanged(playing = false, positionMs = 159_544),
	)

	@Test
	fun `the modeled sequence credits no sponsored time and writes nothing on it`() {
		val harness = harness().feed(
			sponsoredSurfaces() + listOf(
				// In this fixture: the 160 s bundle is republished just before the trailer's.
				metadata(160_000),
				metadata(trailerMs),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 159_544),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 35),
				PlaybackEvent.Advance(154_308),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 154_343),
				PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 154_343),
				nextTrailer(),
			),
		)

		val played = harness.finalized.first().playedMs
		assertTrue(
			"sponsored time was credited as trailer playback: ${played}ms",
			played < 67_795,
		)
		assertEquals(
			"nothing may be written on the sponsored surfaces' time",
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)
	}

	@Test
	fun `nothing is written when the trailer is left right after the sponsored surfaces`() {
		val harness = harness().feed(
			sponsoredSurfaces() + listOf(
				metadata(trailerMs),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 35),
				PlaybackEvent.Advance(5_000),
				nextTrailer(),
			),
		)

		assertTrue(harness.finalized.first().playedMs <= 5_000)
		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
	}

	@Test
	fun `a trailer that follows the spent sponsored surface directly is measured on its own`() {
		val harness = harness().feed(
			sponsoredSurfaces() + listOf(
				metadata(trailerMs),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 35),
				PlaybackEvent.Advance(150_000),
				PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 150_035),
				nextTrailer(),
			),
		)

		assertEquals(150_000L, harness.finalized.first().playedMs)
		assertEquals(listOf(videoId), harness.broadcasts.map { it.videoId })
		assertEquals(listOf("2:34"), harness.broadcasts.map { it.duration })
		assertEquals(1, harness.transactionIds.size)
	}
}
