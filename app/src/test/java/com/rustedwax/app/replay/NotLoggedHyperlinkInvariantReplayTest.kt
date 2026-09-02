package com.rustedwax.app.replay

import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.app.scrobble.FinalizationOutcome
import com.rustedwax.app.scrobble.FinalizationRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a proven hyperlink is required, and where it is not.
 *
 * History is a hyperlink surface: nothing reaches the chain without a canonical
 * watch URL, so every entry can open the exact video it describes. Not logged
 * is a *record* surface — its job is to answer "why wasn't this scrobbled" —
 * and it used to enforce the same rule, which meant the commonest offline
 * refusal (no id resolved, because every id route needs the network) produced
 * no row at all. The rule here is now split: a Not-logged row shows a link when
 * one was proven and stands without one when it was not, and no id is ever
 * invented to manufacture the link.
 */
class NotLoggedHyperlinkInvariantReplayTest : ReplayScenarioTest() {

	private val videoId = "shortFloor1"
	private val title = "Nine-second Short"
	private val handle = "@shortowner"

	private fun configureResolvableShort(harness: ReplayHarness) {
		harness.env.facts.put(
			VideoFacts(
				videoId = videoId,
				title = title,
				author = "Short Owner",
				ownerHandle = handle,
				lengthSeconds = 9,
				category = "Entertainment",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
		harness.env.watchHistory.hasSession = true
		harness.env.watchHistory.shortIds = { listOf(videoId) }
		harness.env.identity.verifiedCandidates = { candidates ->
			if (candidates == listOf(videoId)) {
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = videoId,
						source = "watch history",
						title = title,
						channel = "Short Owner",
						ownerHandle = handle,
						lengthSeconds = 9,
						uniquelyResolved = true,
						historyVerified = true,
					),
				)
			} else {
				VideoResolutionAttempt(refusalReason = "no candidate corroborated")
			}
		}
	}

	@Test
	fun `a notable policy decline is resolved so its Not logged row is a link`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		configureResolvableShort(harness)

		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = title,
				ownerHandle = handle,
				durationMs = 9_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(5_000),
			PlaybackEvent.Finalized(),
		)

		val row = FinalizationRuntime.skipped.value.single()
		assertEquals(videoId, row.videoId)
		assertEquals(
			"https://www.youtube.com/watch?v=$videoId",
			YouTubeProbe.canonicalWatchUrl(row.videoId),
		)
		assertTrue(harness.outcomes.single().outcome is FinalizationOutcome.Refused)
	}

	@Test
	fun `an untitled Short uses its resolved title for the linked Not logged row`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		configureResolvableShort(harness)

		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = null,
				ownerHandle = handle,
				durationMs = 9_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(5_000),
			PlaybackEvent.Finalized(),
		)

		val row = FinalizationRuntime.skipped.value.single()
		assertEquals(title, row.title)
		assertEquals(videoId, row.videoId)
		assertEquals(
			"https://www.youtube.com/watch?v=$videoId",
			YouTubeProbe.canonicalWatchUrl(row.videoId),
		)
		assertTrue(harness.outcomes.single().outcome is FinalizationOutcome.Refused)
	}

	@Test
	fun `an unresolved refusal is recorded in Not logged without a link`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)

		harness.feed(
			PlaybackEvent.NotificationObserved(host = "youtube.com", title = "Unresolved video"),
			PlaybackEvent.SessionMetadata(
				title = "Unresolved video",
				artist = "Unknown channel",
				durationMs = 60_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(40_000),
			PlaybackEvent.Finalized(),
		)

		assertTrue(harness.outcomes.single().outcome is FinalizationOutcome.Refused)
		val row = FinalizationRuntime.skipped.value.single()
		assertEquals("Unresolved video", row.title)
		// Not a dead link and not a fabricated one: no id was proven, so the row
		// carries none and simply does not open.
		assertNull(row.videoId)
	}

	@Test
	fun `every row that claims an id has a canonical hyperlink`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		configureResolvableShort(harness)
		harness.feed(
			PlaybackEvent.ForegroundShortObserved(
				title = title,
				ownerHandle = handle,
				durationMs = 9_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(5_000),
			PlaybackEvent.Finalized(),
		)

		assertTrue(
			FinalizationRuntime.skipped.value.all {
				it.videoId == null || YouTubeProbe.canonicalWatchUrl(it.videoId) != null
			},
		)
		assertTrue(
			FinalizationRuntime.recent.value.all {
				YouTubeProbe.canonicalWatchUrl(it.videoId) != null
			},
		)
	}
}
