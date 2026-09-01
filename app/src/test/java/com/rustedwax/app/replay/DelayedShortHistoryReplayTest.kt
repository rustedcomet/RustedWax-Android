package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.app.scrobble.FinalizationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for YouTube's eventually-consistent Shorts history feed. */
class DelayedShortHistoryReplayTest : ReplayScenarioTest() {

	private data class FieldShort(
		val videoId: String,
		val title: String,
		val channel: String,
		val handle: String,
		val durationSeconds: Long,
		val playedSeconds: Long,
	)

	private val first = FieldShort(
		videoId = "Pf_Tbo9-onQ",
		title = "Women's Basketball Went Absolutely Crazy.",
		channel = "Supalak",
		handle = "@supalak-u6f111",
		durationSeconds = 58,
		playedSeconds = 52,
	)
	private val second = FieldShort(
		videoId = "hb45czcI1bA",
		title = "Ronaldo respects Referees",
		channel = "Michelle Weeks",
		handle = "@michelleweeks-h9u",
		durationSeconds = 59,
		playedSeconds = 45,
	)
	private val third = FieldShort(
		videoId = "hknhmzbJl24",
		title = "smooth movement",
		channel = "FC Adamrose",
		handle = "@fcadamrose173",
		durationSeconds = 58,
		playedSeconds = 52,
	)

	private fun facts(short: FieldShort) = VideoFacts(
		videoId = short.videoId,
		title = short.title,
		author = short.channel,
		ownerHandle = short.handle,
		lengthSeconds = short.durationSeconds,
		category = "Entertainment",
		watchPageResolved = true,
		isUnlisted = false,
	)

	private fun resolution(short: FieldShort) = VideoResolutionAttempt(
		resolution = VideoResolution(
			videoId = short.videoId,
			source = "watch history",
			title = short.title,
			channel = short.channel,
			lengthSeconds = short.durationSeconds,
			uniquelyResolved = true,
			ownerHandle = short.handle,
			historyVerified = true,
		),
	)

	private fun events(short: FieldShort) = listOf(
		PlaybackEvent.ForegroundShortObserved(
			title = null,
			ownerHandle = short.handle,
			durationMs = short.durationSeconds * 1_000,
		),
		PlaybackEvent.SessionMetadata(artist = short.channel),
		PlaybackEvent.PlaybackStateChanged(playing = true),
		PlaybackEvent.Advance(short.playedSeconds * 1_000),
		PlaybackEvent.Finalized(),
	)

	@Test
	fun `the three field Shorts all scrobble when history exposes the first two late`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		listOf(first, second, third).forEach { harness.env.facts.put(facts(it)) }
		harness.env.watchHistory.hasSession = true

		var historyRead = 0
		harness.env.watchHistory.shortIds = {
			historyRead++
			when {
				historyRead <= 5 -> listOf("olderSh0rt1")
				historyRead == 6 -> listOf(first.videoId, "olderSh0rt1")
				historyRead <= 8 -> listOf(first.videoId, "olderSh0rt1")
				historyRead == 9 -> listOf(second.videoId, first.videoId, "olderSh0rt1")
				else -> listOf(third.videoId, second.videoId, first.videoId, "olderSh0rt1")
			}
		}

		var verification = 0
		harness.env.identity.verifiedCandidates = {
			verification++
			when (verification) {
				1, 3 -> VideoResolutionAttempt(
					refusalReason = "no candidate matched exact duration+owner handle",
				)
				2 -> resolution(first)
				4 -> resolution(second)
				5 -> resolution(third)
				else -> error("unexpected duplicate candidate verification $verification")
			}
		}

		harness.feed(events(first) + events(second) + events(third))

		assertEquals(
			listOf(first.videoId, second.videoId, third.videoId),
			harness.broadcasts.map { it.videoId },
		)
		assertEquals(3, harness.transactionIds.distinct().size)
		assertTrue(harness.outcomes.all { it.outcome is FinalizationOutcome.Eligible })
		assertEquals(emptyList<ReplayHarness.Refusal>(), harness.refusals)
		// Six reads for the first delayed row, then three for the second and one
		// for the already-visible third. Only changed candidate sets fetch pages.
		assertEquals(10, harness.env.watchHistory.shortIdQueries.size)
		assertEquals(5, verification)
		assertEquals(
			listOf(5_000L, 15_000L, 40_000L, 90_000L, 5_000L),
			harness.env.historyRetryDelay.waits,
		)
		assertEquals(false, harness.env.watchHistory.shortIdForceRefreshes[0])
		assertTrue(harness.env.watchHistory.shortIdForceRefreshes.drop(1).take(5).all { it })
	}

	@Test
	fun `a Short absent for the whole propagation window refuses once and stops`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.watchHistory.hasSession = true
		harness.env.watchHistory.shortIds = { listOf("olderSh0rt1") }
		harness.env.identity.verifiedCandidates = {
			VideoResolutionAttempt(
				refusalReason = "no candidate matched exact duration+owner handle",
			)
		}

		harness.feed(events(first))

		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(1, harness.outcomes.size)
		assertTrue(harness.outcomes.single().outcome is FinalizationOutcome.Refused)
		assertEquals(6, harness.env.watchHistory.shortIdQueries.size)
		assertEquals(
			listOf(false, true, true, true, true, true),
			harness.env.watchHistory.shortIdForceRefreshes,
		)
		assertEquals(
			listOf(5_000L, 15_000L, 40_000L, 90_000L),
			harness.env.historyRetryDelay.waits,
		)
		assertEquals(
			1,
			harness.identityRoutes.count { it == ReplayIdentitySource.Route.VERIFIED_CANDIDATES },
		)
	}
}
