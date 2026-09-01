package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortsScreenOffEndToEndReplayTest : ReplayScenarioTest() {
	private val videoId = "screenOff01"
	private val title = "Screen-off continuity field Short"
	private val handle = "@fieldcreator"
	private val durationMs = 60_000L

	private fun harness(): ReplayHarness {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.watchHistory.hasSession = true
		harness.env.watchHistory.nowMillis = { harness.env.clock.nowMillis() }
		harness.env.watchHistory.shortIds = { listOf(videoId) }
		harness.env.identity.verifiedCandidates = { ids ->
			if (ids == listOf(videoId)) {
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = videoId,
						source = "watch history",
						title = title,
						channel = "Field Creator",
						ownerHandle = handle,
						lengthSeconds = durationMs / 1_000,
						uniquelyResolved = true,
						historyVerified = true,
					),
				)
			} else {
				VideoResolutionAttempt(refusalReason = "unexpected Shorts candidates")
			}
		}
		harness.env.facts.put(
			VideoFacts(
				videoId = videoId,
				title = title,
				author = "Field Creator",
				ownerHandle = handle,
				lengthSeconds = durationMs / 1_000,
				category = "Entertainment",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
		return harness
	}

	private fun acquireAndMeasure(harness: ReplayHarness) {
		harness.feed(
			PlaybackEvent.ForegroundShortObserved(title, handle, durationMs),
			PlaybackEvent.Advance(35_000),
		)
	}

	@Test
	fun `screen-off dropout recovers as one listen and the settle interval credits zero`() {
		val harness = harness()
		acquireAndMeasure(harness)

		harness.feed(
			PlaybackEvent.ProgressSurfaceLost(
				inferredMs = 3_500,
				playing = false,
				displayOff = true,
			),
			PlaybackEvent.ForegroundShortObserved(
				title = title,
				ownerHandle = handle,
				durationMs = durationMs,
				positionMs = 35_000,
			),
			PlaybackEvent.Advance(5_000),
			PlaybackEvent.Finalized("field Short ended after screen-off recovery"),
			PlaybackEvent.Advance(35_000),
		)

		assertEquals("the dropout split one listen", 1, harness.finalized.size)
		assertEquals("the no-credit settle interval inflated playback", 40_000, harness.finalized.single().playedMs)
		assertEquals(0, harness.finalized.single().inferredPlayedMs)
		assertEquals(listOf("Eligible"), harness.outcomeKinds)
		assertEquals(videoId, harness.broadcasts.single().videoId)
		assertEquals(title, harness.broadcasts.single().title)
		assertEquals(1, harness.env.broadcaster.sent.size)
		assertEquals(emptyList<String>(), harness.env.claims.duplicateAttempts)
	}

	@Test
	fun `display left off beyond the bounded settle finalizes exactly once`() {
		val harness = harness()
		acquireAndMeasure(harness)

		harness.feed(
			PlaybackEvent.ProgressSurfaceLost(
				inferredMs = 11_000,
				playing = false,
				displayOff = true,
			),
			PlaybackEvent.Finalized("display remained off"),
			PlaybackEvent.Advance(35_000),
		)

		assertEquals(1, harness.finalized.size)
		assertEquals(35_000, harness.finalized.single().playedMs)
		assertEquals(0, harness.finalized.single().inferredPlayedMs)
		assertEquals(listOf("Refused"), harness.outcomeKinds)
		assertEquals(listOf(ReplayHarness.RefusalKind.PROGRESS_SURFACE_LOST), harness.refusalKinds)
		assertEquals(0, harness.env.broadcaster.sent.size)
	}

	@Test
	fun `screen-on evidence loss keeps the ordinary grace`() {
		val harness = harness()
		acquireAndMeasure(harness)

		harness.feed(
			PlaybackEvent.ProgressSurfaceLost(
				inferredMs = 3_500,
				playing = false,
				displayOff = false,
			),
			PlaybackEvent.Advance(35_000),
		)

		assertEquals(1, harness.finalized.size)
		assertEquals(35_000, harness.finalized.single().playedMs)
		assertEquals(listOf("Refused"), harness.outcomeKinds)
		assertEquals(0, harness.env.broadcaster.sent.size)
	}
}
