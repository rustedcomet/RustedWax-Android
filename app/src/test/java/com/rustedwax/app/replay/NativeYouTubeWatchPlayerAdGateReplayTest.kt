package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.core.PlayerAdSurface
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native watch player's advertisement UI marks intervals excluded from playback.
 *
 * Synthetic identity fixtures model pre-rolls published under an upcoming video's
 * title and channel. Duration alone cannot identify those advertisements;
 * these scenarios supply literal player ad-state observations and require the
 * following video to be measured independently of the labelled intervals.
 */
class NativeYouTubeWatchPlayerAdGateReplayTest : ReplayScenarioTest() {

	private val videoId = "aaaaaaaaaa5"
	private val title = "Synthetic Official Trailer 2 (2026)"
	private val channel = "Synthetic Trailers"

	private val visible = PlaybackEvent.PlayerAdSurfaceObserved(PlayerAdSurface.VISIBLE)
	private val absent = PlaybackEvent.PlayerAdSurfaceObserved(PlayerAdSurface.ABSENT)
	private val unobserved = PlaybackEvent.PlayerAdSurfaceObserved(PlayerAdSurface.UNOBSERVED)

	private fun harness(lengthMs: Long): ReplayHarness =
		ReplayHarness(ReplaySource.NATIVE_YOUTUBE).also { harness ->
			harness.env.facts.put(
				VideoFacts(
					videoId = videoId,
					title = title,
					author = channel,
					lengthSeconds = lengthMs / 1000,
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
						lengthSeconds = lengthMs / 1000,
						uniquelyResolved = true,
					),
				)
			}
		}

	private fun metadata(durationMs: Long) =
		PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = durationMs)

	private fun nextVideo() = PlaybackEvent.SessionMetadata(
		title = "Next Synthetic Trailer (2026)",
		artist = channel,
		durationMs = 142_000,
	)

	private fun playing(positionMs: Long) =
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = positionMs)

	private fun stopped(positionMs: Long) =
		PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = positionMs)

	private fun paused(positionMs: Long) =
		PlaybackEvent.PlaybackStateChanged(playing = false, positionMs = positionMs)

	/** Watch [millis] of video with the player polled once a second, as the observer does. */
	private fun watchedWithAdLabelAbsent(millis: Long): List<PlaybackEvent> = buildList {
		var remaining = millis
		while (remaining > 0) {
			val step = minOf(1_000L, remaining)
			add(PlaybackEvent.Advance(step))
			add(absent)
			remaining -= step
		}
	}

	/** Two consecutive sponsored surfaces with explicit player ad labels. */
	private fun twoSponsoredSurfaces(): List<PlaybackEvent> = listOf(
		metadata(71_000),
		playing(3_117),
		// The first poll lands after the ad has already started.
		PlaybackEvent.Advance(800),
		visible,
		PlaybackEvent.Advance(66_995),
		visible,
		stopped(70_912),
		PlaybackEvent.Advance(5_160),
		paused(70_912),
		metadata(71_000),
		metadata(160_000),
		playing(70_912),
		playing(0),
		PlaybackEvent.Advance(1_000),
		visible,
		PlaybackEvent.Advance(70_988),
		PlaybackEvent.IdleDeadlineReached,
		PlaybackEvent.Advance(87_556),
		visible,
		stopped(159_544),
		PlaybackEvent.Advance(4_967),
		paused(159_544),
	)

	@Test
	fun `two sponsored surfaces credit nothing and the trailer is measured as itself`() {
		val trailerMs = 154_000L
		val harness = harness(trailerMs).feed(
			twoSponsoredSurfaces() + listOf(
				// The overlay is gone once the second ad has ended.
				absent,
				metadata(160_000),
				metadata(trailerMs),
				playing(159_544),
				playing(35),
			) + watchedWithAdLabelAbsent(154_308) + listOf(
				playing(154_343),
				stopped(154_343),
				nextVideo(),
			),
		)

		val trailer = harness.finalized.first()
		assertEquals(trailerMs, trailer.durationMs)
		assertTrue(
			"the trailer's own seconds must be credited, not quarantined: ${trailer.playedMs}ms",
			trailer.playedMs in 150_000L..154_343L,
		)
		assertEquals(listOf(videoId), harness.broadcasts.map { it.videoId })
		assertEquals(
			"the payload must carry the trailer's length, never an ad's",
			listOf("2:34"),
			harness.broadcasts.map { it.duration },
		)
	}

	@Test
	fun `an ad label still drawn when the trailer's bundle lands costs only that lag`() {
		val trailerMs = 154_000L
		val harness = harness(trailerMs).feed(
			twoSponsoredSurfaces() + listOf(
				metadata(160_000),
				metadata(trailerMs),
				playing(159_544),
				playing(35),
				PlaybackEvent.Advance(900),
				visible,
				PlaybackEvent.Advance(600),
			) + watchedWithAdLabelAbsent(152_808) + listOf(
				playing(154_343),
				stopped(154_343),
				nextVideo(),
			),
		)

		val trailer = harness.finalized.first()
		assertEquals(trailerMs, trailer.durationMs)
		assertTrue(
			"only the seconds the label was still drawn may be missing: ${trailer.playedMs}ms",
			trailer.playedMs in 148_000L..153_000L,
		)
		assertEquals(listOf("2:34"), harness.broadcasts.map { it.duration })
	}

	@Test
	fun `a skipped second ad hands the listen to the trailer rather than quarantining it`() {
		val trailerMs = 148_000L
		val harness = harness(trailerMs).feed(
			listOf(
				metadata(79_000),
				playing(0),
				PlaybackEvent.Advance(700),
				visible,
				PlaybackEvent.Advance(78_425),
				visible,
				stopped(79_125),
				PlaybackEvent.Advance(5_000),
				metadata(174_000),
				playing(0),
				PlaybackEvent.Advance(1_000),
				visible,
				PlaybackEvent.Advance(83_550),
				visible,
				// Skip ad.
				stopped(84_550),
				metadata(trailerMs),
				playing(0),
				PlaybackEvent.Advance(600),
				visible,
			) + watchedWithAdLabelAbsent(130_000) + listOf(
				paused(130_600),
				nextVideo(),
			),
		)

		val trailer = harness.finalized.first()
		assertEquals(trailerMs, trailer.durationMs)
		assertTrue(
			"the trailer's 130s, minus the label's lag, is what was watched: ${trailer.playedMs}ms",
			trailer.playedMs in 128_000L..130_600L,
		)
		assertEquals(listOf(videoId), harness.broadcasts.map { it.videoId })
		assertEquals(listOf("2:28"), harness.broadcasts.map { it.duration })
	}

	@Test
	fun `a labelled ad that took the anchor from a short surface is replaced by the video`() {
		val videoMs = 148_000L
		val harness = harness(videoMs).feed(
			listOf(
				metadata(6_000),
				playing(0),
				PlaybackEvent.Advance(3_000),
				// Ten times longer: the existing supersede makes this the anchor.
				metadata(211_000),
				playing(0),
				PlaybackEvent.Advance(900),
				visible,
				PlaybackEvent.Advance(9_100),
				visible,
				stopped(10_000),
				absent,
				metadata(videoMs),
				playing(0),
			) + watchedWithAdLabelAbsent(140_000) + listOf(
				stopped(140_000),
				nextVideo(),
			),
		)

		val video = harness.finalized.first()
		assertEquals(videoMs, video.durationMs)
		assertTrue(
			"the video must not be quarantined as a replacement of the ad: ${video.playedMs}ms",
			video.playedMs in 138_000L..140_000L,
		)
		assertEquals(listOf("2:28"), harness.broadcasts.map { it.duration })
	}

	@Test
	fun `a mid-roll keeps the organic seconds on both sides and credits none of the ad`() {
		val videoMs = 1_192_000L
		val harness = harness(videoMs).feed(
			listOf(
				metadata(videoMs),
				playing(0),
			) + watchedWithAdLabelAbsent(300_000) + listOf(
				metadata(259_000),
				playing(0),
				PlaybackEvent.Advance(800),
				visible,
				PlaybackEvent.Advance(29_200),
				visible,
				stopped(30_000),
				metadata(videoMs),
				playing(300_000),
				PlaybackEvent.Advance(700),
				visible,
			) + watchedWithAdLabelAbsent(500_000) + listOf(
				paused(800_700),
				nextVideo(),
			),
		)

		val video = harness.finalized.first()
		assertEquals(videoMs, video.durationMs)
		assertTrue(
			"300s before the ad and 500s after it, and none of the ad: ${video.playedMs}ms",
			video.playedMs in 797_000L..800_700L,
		)
	}

	@Test
	fun `an ad label drawn just before the mid-roll's bundle does not erase what was watched`() {
		val videoMs = 1_192_000L
		val harness = harness(videoMs).feed(
			listOf(
				metadata(videoMs),
				playing(0),
			) + watchedWithAdLabelAbsent(300_000) + listOf(
				visible,
				PlaybackEvent.Advance(400),
				metadata(259_000),
				playing(0),
				PlaybackEvent.Advance(30_000),
				visible,
				stopped(30_000),
				metadata(videoMs),
				playing(300_000),
				PlaybackEvent.Advance(700),
				absent,
			) + watchedWithAdLabelAbsent(100_000) + listOf(
				paused(400_700),
				nextVideo(),
			),
		)

		val video = harness.finalized.first()
		assertTrue(
			"the organic 300s before the label must survive it: ${video.playedMs}ms",
			video.playedMs in 398_000L..400_700L,
		)
	}

	@Test
	fun `an abandoned pre-roll finalizes with nothing credited`() {
		val harness = harness(154_000L).feed(
			listOf(
				metadata(71_000),
				playing(0),
				PlaybackEvent.Advance(1_500),
				visible,
				PlaybackEvent.Advance(58_500),
				visible,
				PlaybackEvent.SessionMetadata(
					title = "Something Else Entirely",
					artist = "Another Channel",
					durationMs = 200_000,
				),
			),
		)

		assertEquals(0L, harness.finalized.first().playedMs)
		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
	}

	@Test
	fun `a label that can no longer be seen keeps its ad uncredited`() {
		val trailerMs = 154_000L
		val harness = harness(trailerMs).feed(
			listOf(
				metadata(71_000),
				playing(0),
				PlaybackEvent.Advance(800),
				visible,
				PlaybackEvent.Advance(10_000),
				// The notification shade, say: the player is out of sight.
				unobserved,
				PlaybackEvent.Advance(40_200),
				unobserved,
				// Skipped before its end, so no spent-surface shape can release it.
				stopped(51_000),
				metadata(trailerMs),
				playing(0),
				PlaybackEvent.Advance(1_000),
				unobserved,
				PlaybackEvent.Advance(150_000),
				stopped(151_000),
				nextVideo(),
			),
		)

		val trailer = harness.finalized.first()
		assertEquals(trailerMs, trailer.durationMs)
		assertTrue(
			"no second of the unseen ad, all of the trailer: ${trailer.playedMs}ms",
			trailer.playedMs in 150_000L..151_000L,
		)
	}
}
