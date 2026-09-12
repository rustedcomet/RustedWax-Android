package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An unproven same-title surface followed by a longer presentation.
 *
 * Releasing based on duration alone would also credit longer advertisements;
 * [NativeYouTubeSponsoredSameTitleSurfacesReplayTest] protects that boundary.
 * [NativeYouTubeProvisionalSurfaceAttributionReplayTest] verifies that the
 * provisional surface's own seconds cannot be credited to the video.
 */
class NativeYouTubeLongerPresentationReleaseReplayTest : ReplayScenarioTest() {

	private val videoId = "aaaaaaaaaa1"
	private val title = "Same Title Official Trailer"
	private val channel = "Trailer Channel"
	private val provisionalMs = 77_000L
	private val videoMs = 148_000L

	/** The resolver names the video only when asked about its own 148 s length. */
	private fun harness(): ReplayHarness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE).also { harness ->
		harness.env.facts.put(
			VideoFacts(
				videoId = videoId,
				title = title,
				author = channel,
				lengthSeconds = videoMs / 1000,
				category = "Film & Animation",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
		harness.env.identity.search = {
			val asked = harness.env.identity.searchRequests.lastOrNull()?.durationSec
			if (asked == videoMs / 1000) {
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = videoId,
						source = "search",
						title = title,
						channel = channel,
						lengthSeconds = videoMs / 1000,
						uniquelyResolved = true,
						presentationDurationCorroborated = true,
					),
				)
			} else {
				VideoResolutionAttempt(resolution = null)
			}
		}
	}

	private fun metadata(durationMs: Long) =
		PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = durationMs)

	/** Through the longer presentation's PLAYING from zero and one early PiP republish. */
	private fun abandonedProvisionalSurface(): List<PlaybackEvent> = listOf(
		metadata(provisionalMs),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 2_533),
		PlaybackEvent.Advance(8_708),
		PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 11_147),
		metadata(videoMs),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(19_184),
		metadata(videoMs),
	)

	@Test
	fun `a video abandoned before it outlasts the provisional surface is not credited from quarantine`() {
		// 60 s into the 148 s presentation it could still have been a 77 s surface,
		// so nothing it measured may be spent.
		val harness = harness().feed(
			abandonedProvisionalSurface() + listOf(
				PlaybackEvent.Advance(40_816),
				PlaybackEvent.IdleDeadlineReached,
				PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 60_000),
				PlaybackEvent.Finalized("session ended"),
			),
		)

		assertEquals(8_708L, harness.finalized.single().playedMs)
		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
	}
}
