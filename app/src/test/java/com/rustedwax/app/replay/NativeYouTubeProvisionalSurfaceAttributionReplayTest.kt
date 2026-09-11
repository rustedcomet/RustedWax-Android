package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A borrowed presentation's seconds, carried across the churn that replaced it.
 *
	 * Regression: a pre-roll pod can run under the upcoming video's title with a
	 * duration belonging to neither the ad nor the video. If later presentations
	 * replace that unidentified anchor, the pod's interval must remain refused
	 * rather than becoming credited playback for the named video.
 *
 * Nothing was written, but only by accident: the anchor stayed at the pod's 127 s,
 * so every identity lookup asked about a length no upload had and failed closed.
 * The seconds themselves were never refused. A source that does not need a lookup
 * — a browser, which reads its id from the address bar — reaches the same boundary
 * already holding a verified exact id, and there the retained interval is written.
 *
 * The rule this pins: a presentation that never proved which item it was may not
 * spend its seconds on the presentation that replaced it. It is the same rule
 * [YouTubeMusicPodAnchorInflationReplayTest] pins for Music, reached by the other
 * route — Music discards the provisional interval outright, and regular YouTube
 * banked it.
 */
class NativeYouTubeProvisionalSurfaceAttributionReplayTest : ReplayScenarioTest() {

	private val videoId = "dQw4w9WgXcQ"
	private val title = "Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)"
	private val artist = "Rick Astley"

	/** Synthetic boundary values: a 127 s pod anchor and a 213 s video. */
	private val podMs = 127_000L
	private val videoMs = 213_000L
	private val podPlayedMs = 124_963L
	private val podLeadInMs = 2_254L
	private val podEndPositionMs = 127_182L

	/**
	 * A resolver that can name the video once it is asked about the video's own
		 * length, and cannot name anything at the pod's 127 s.
	 */
	private fun harness(source: ReplaySource = ReplaySource.NATIVE_YOUTUBE): ReplayHarness =
		ReplayHarness(source).also { harness ->
			harness.env.facts.put(
				VideoFacts(
					videoId = videoId,
					title = title,
					author = artist,
					originalArtist = artist,
					watchPageArtistCredit = artist,
					lengthSeconds = videoMs / 1000,
					category = "Music",
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
							channel = artist,
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
		PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = durationMs)

	/**
	 * The synthetic pod: the upcoming video's title over a 127 s surface,
	 * played very nearly through and never identified as anything.
	 */
	private fun ReplayHarness.playTheBorrowedPod(): ReplayHarness = feed(
		metadata(podMs),
		// The fixture joins the surface after a short lead-in.
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = podLeadInMs),
		PlaybackEvent.Advance(podPlayedMs),
		// …and ran it past its own end: `position=127182` of a 127000 ms surface.
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = podEndPositionMs),
	)

	// ── the carry regression ─────────────────────────────────────────────────

	@Test
	fun `a borrowed pod surface adds nothing to the video that replaced it`() {
		val harness = harness().playTheBorrowedPod().feed(
			// Churn to the video's genuine length.
			metadata(videoMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(130_000),
			PlaybackEvent.Finalized("video ended"),
		)

		assertEquals(
			"only the 130s heard on the video's own presentation — 254963 was the pod's " +
				"124963 folded in",
			130_000L,
			harness.finalized.single().playedMs,
		)
	}

	@Test
	fun `the synthetic pod chain cannot reach a full-play percentage`() {
		val harness = harness().playTheBorrowedPod().feed(
			metadata(videoMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(130_000),
			PlaybackEvent.Finalized("video ended"),
		)

		assertEquals(
			"130s of a 213s video is 61%, not the 100% the pod's seconds would buy",
			listOf(61),
			harness.broadcasts.map { it.percentPlayed },
		)
	}

	@Test
	fun `pod seconds cannot push a sub-threshold video over the line`() {
		// 124963 of pod plus 90s of a 213s video is 100% only if the pod counts.
		// The video alone is 42%.
		val harness = harness().playTheBorrowedPod().feed(
			metadata(videoMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(90_000),
			PlaybackEvent.Finalized("video ended"),
		)

		assertEquals(90_000L, harness.finalized.single().playedMs)
		assertEquals(
			"a video not yet half watched is not a scrobble",
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)
	}

	// ── the write-reachable configuration ────────────────────────────────────

	/**
	 * The same boundary where the id never had to be looked up.
	 *
	 * A browser reads `dQw4w9WgXcQ` out of the address bar, so it is holding a
	 * verified exact id while the pod is still on screen — the accident that saved
	 * the native capture is simply absent, and the retained interval is written.
	 */
	@Test
	fun `a browser holding the exact id does not write the pod's seconds`() {
		val harness = harness(ReplaySource.BRAVE)
		harness.feed(
			PlaybackEvent.NotificationObserved(host = "youtube.com", title = title),
			PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = videoId),
			metadata(podMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = podLeadInMs),
			PlaybackEvent.Advance(podPlayedMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = podEndPositionMs),
			metadata(videoMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(60_000),
			PlaybackEvent.Finalized("video ended"),
		)

		val written = harness.broadcasts.singleOrNull()
		assertEquals(
			"the pod's 124963 must not be spent on the id the address bar proved",
			60_000L,
			harness.finalized.last().playedMs,
		)
		if (written != null) {
			assertTrue(
				"60s of a 213s video is 28%, not the 87% the pod's seconds would buy",
				(written.percentPlayed ?: 0) < 50,
			)
		}
	}

	// ── what must not change ─────────────────────────────────────────────────

	@Test
	fun `a video that proved its own length keeps every second across a later churn`() {
		// The other order, and the reason the rule asks which presentation proved
		// itself. The video establishes its own corroborated presentation first, so
		// a later surface replacing it may not cost it the seconds it earned.
		val harness = harness().feed(
			metadata(videoMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(150_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 150_000),
			// A mid-roll surface arrives under the same title.
			metadata(33_000),
			PlaybackEvent.Finalized("stopped"),
		)

		assertEquals(
			"the video's own 150s survives the surface that interrupted it",
			150_000L,
			harness.finalized.single().playedMs,
		)
	}
}
