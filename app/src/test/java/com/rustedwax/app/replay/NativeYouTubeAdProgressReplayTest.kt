package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.FinalizationOutcome
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field-shaped native YouTube duration/transport churn through the production engine. */
class NativeYouTubeAdProgressReplayTest : ReplayScenarioTest() {

	private val videoId = "UeBeg2C5ftE"
	private val title = "Nobody Is Buying Jordans Anymore... Here’s What They’re Wearing Instead! 🤯"
	private val channel = "Ballinonabudget"
	private val durationMs = 952_000L

	private fun harness(): ReplayHarness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE).also { harness ->
		harness.env.facts.put(
			VideoFacts(
				videoId = videoId,
				title = title,
				author = channel,
				lengthSeconds = durationMs / 1000,
				category = "Entertainment",
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
					lengthSeconds = durationMs / 1000,
					uniquelyResolved = true,
				),
			)
		}
	}

	private fun interruptedListen(beforeMs: Long, afterMs: Long): List<PlaybackEvent> = listOf(
		PlaybackEvent.SessionMetadata(title = title, durationMs = durationMs),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(beforeMs),
		PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 7_000),
		PlaybackEvent.Advance(7_000),
		PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 7_000),
		PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 77_000),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 122),
		PlaybackEvent.Advance(77_000),
		PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 77_000),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = beforeMs),
		PlaybackEvent.Advance(afterMs),
		PlaybackEvent.Finalized("organic video ended"),
	)

	@Test
	fun `organic progress across duration replacements scrobbles once without replacement time`() {
		val harness = harness().feed(interruptedListen(beforeMs = 300_000, afterMs = 280_000))

		assertEquals("only the organic video may finalize", 1, harness.finalized.size)
		assertEquals("replacement time is not organic progress", 580_000, harness.finalized.single().playedMs)
		assertEquals(
			harness.outcomes.joinToString { it.outcome.toString() },
			listOf(videoId),
			harness.broadcasts.map { it.videoId },
		)
		assertEquals(61, harness.broadcasts.single().percentPlayed)
		assertEquals(emptyList<FinalizationRuntime.SkipRecord>(), FinalizationRuntime.skipped.value)
		harness.assertOneOutcomePerFinalization(1)
	}

	@Test
	fun `below threshold interrupted organic video creates one linked Not Logged outcome`() {
		val harness = harness().feed(interruptedListen(beforeMs = 200_000, afterMs = 300_000))

		assertEquals("only the organic video may finalize", 1, harness.finalized.size)
		assertEquals(500_000, harness.finalized.single().playedMs)
		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(1, harness.outcomes.size)
		assertTrue(harness.outcomes.single().outcome is FinalizationOutcome.Refused)
		assertEquals(videoId, FinalizationRuntime.skipped.value.single().videoId)
		harness.assertOneOutcomePerFinalization(1)
	}

	@Test
	fun `remaining-duration rebases and consecutive interstitials remain one organic listen`() {
		val fieldDurationMs = 2_183_000L
		val remainingSurfaceMs = 1_192_000L
		val harness = harness().also {
			it.env.facts.put(
				VideoFacts(
					videoId = videoId,
					title = title,
					author = channel,
					lengthSeconds = fieldDurationMs / 1000,
					category = "Entertainment",
					watchPageResolved = true,
					isUnlisted = false,
				),
			)
			it.env.identity.search = {
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = videoId,
						source = "search",
						title = title,
						channel = channel,
						lengthSeconds = fieldDurationMs / 1000,
						uniquelyResolved = true,
					),
				)
			}
		}.feed(
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = fieldDurationMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(1_049_000),
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 1_049_000),
			PlaybackEvent.PlaybackStateChanged(playing = false, positionMs = 0),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = remainingSurfaceMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(77_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 27_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(27_000),
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 27_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 44_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(44_000),
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 44_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = remainingSurfaceMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 76_000),
			PlaybackEvent.Advance(300_000),
			PlaybackEvent.Finalized("organic video ended"),
		)

		assertEquals(1, harness.finalized.size)
		assertEquals(1_426_000L, harness.finalized.single().playedMs)
		assertEquals(fieldDurationMs, harness.finalized.single().durationMs)
		assertEquals(listOf(videoId), harness.broadcasts.map { it.videoId })
		assertEquals(65, harness.broadcasts.single().percentPlayed)
		harness.assertOneOutcomePerFinalization(1)
	}

	@Test
	fun `a replay from zero after a replacement starts a fresh listen without inherited time`() {
		val harness = harness().feed(
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = durationMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(300_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 77_000),
			PlaybackEvent.Advance(77_000),
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 77_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = durationMs),
			PlaybackEvent.Advance(600_000),
			PlaybackEvent.Finalized("replay ended"),
		)

		assertEquals(listOf(300_000L), harness.finalized.map { it.playedMs })
		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(1, harness.outcomes.size)
		assertTrue(harness.outcomes.single().outcome is FinalizationOutcome.Refused)
		assertEquals(videoId, FinalizationRuntime.skipped.value.single().videoId)
		harness.assertOneOutcomePerFinalization(1)
	}

	@Test
	fun `organic metadata may lead its returning position without fragmenting the listen`() {
		val primaryVideoId = "kObfOT4gwlM"
		val primaryTitle = "¡Si no lo Hubieran Grabado NADIE lo Hubiera CREÍDO! Si te Ríes Pierdes 2026"
		val primaryChannel = "#Mente Sorprendente"
		val remainingSurfaceMs = 1_192_000L
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE).also {
			it.env.facts.put(
				VideoFacts(
					videoId = primaryVideoId,
					title = primaryTitle,
					author = primaryChannel,
					lengthSeconds = remainingSurfaceMs / 1000,
					category = "Entertainment",
					watchPageResolved = true,
					isUnlisted = false,
				),
			)
			it.env.identity.search = {
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = primaryVideoId,
						source = "search",
						title = primaryTitle,
						channel = primaryChannel,
						lengthSeconds = remainingSurfaceMs / 1000,
						uniquelyResolved = true,
					),
				)
			}
		}.feed(
			PlaybackEvent.SessionMetadata(
				title = primaryTitle,
				artist = primaryChannel,
				durationMs = remainingSurfaceMs,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 1_021_583),
			PlaybackEvent.Advance(47_000),
			PlaybackEvent.SessionMetadata(title = primaryTitle, artist = primaryChannel, durationMs = 25_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(25_000),
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 25_000),
			PlaybackEvent.SessionMetadata(title = primaryTitle, artist = primaryChannel, durationMs = 20_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(20_000),
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 20_000),
			// Synthetic callback order: long metadata first sees the ad position;
			// PLAYING repeats it, then publishes the organic position 7 ms later.
			PlaybackEvent.SessionMetadata(
				title = primaryTitle,
				artist = primaryChannel,
				durationMs = remainingSurfaceMs,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 20_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 1_067_441),
			PlaybackEvent.Advance(100_000),
			PlaybackEvent.Finalized("organic video ended"),
		)

		assertEquals("the callback-order edge must remain one listen", 1, harness.finalized.size)
		assertEquals("neither replacement interval may count", 147_000L, harness.finalized.single().playedMs)
		assertEquals(remainingSurfaceMs, harness.finalized.single().durationMs)
		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(1, harness.outcomes.size)
		assertTrue(harness.outcomes.single().outcome is FinalizationOutcome.Refused)
		assertEquals(primaryVideoId, FinalizationRuntime.skipped.value.single().videoId)
		assertFalse(harness.trace.stoppedGracePending)
		harness.assertOneOutcomePerFinalization(1)
	}

	@Test
	fun `a longer sponsored presentation neither fragments nor earns progress`() {
		val organicDurationMs = 1_192_000L
		val sponsoredSurfaceMs = 2_163_000L
		val harness = harness().also {
			it.env.facts.put(
				VideoFacts(
					videoId = videoId,
					title = title,
					author = channel,
					lengthSeconds = organicDurationMs / 1000,
					category = "Entertainment",
					watchPageResolved = true,
					isUnlisted = false,
				),
			)
			it.env.identity.search = {
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = videoId,
						source = "search",
						title = title,
						channel = channel,
						lengthSeconds = organicDurationMs / 1000,
						uniquelyResolved = true,
					),
				)
			}
		}.feed(
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = organicDurationMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(325_000),
			PlaybackEvent.PlaybackStateChanged(playing = false, positionMs = 40),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = sponsoredSurfaceMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 40),
			PlaybackEvent.Advance(47_000),
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 47_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = organicDurationMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 47_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 325_500),
			PlaybackEvent.Advance(400_000),
			PlaybackEvent.Finalized("organic video ended"),
		)

		assertEquals("the sponsored surface may not create a terminal outcome", 1, harness.finalized.size)
		assertEquals("the 47 sponsored seconds may not count", 725_000L, harness.finalized.single().playedMs)
		assertEquals(organicDurationMs, harness.finalized.single().durationMs)
		assertEquals(listOf(videoId), harness.broadcasts.map { it.videoId })
		assertEquals(61, harness.broadcasts.single().percentPlayed)
		assertEquals(emptyList<FinalizationRuntime.SkipRecord>(), FinalizationRuntime.skipped.value)
		harness.assertOneOutcomePerFinalization(1)
	}

	@Test
	fun `a different video after a replacement cannot inherit organic progress`() {
		val nextVideoId = "Different01"
		val nextTitle = "A Different Organic Video"
		val harness = harness().also {
			it.env.facts.put(
				VideoFacts(
					videoId = nextVideoId,
					title = nextTitle,
					author = "Another channel",
					lengthSeconds = 600,
					category = "Entertainment",
					watchPageResolved = true,
					isUnlisted = false,
				),
			)
		}.feed(
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = durationMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(300_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 77_000),
			PlaybackEvent.Advance(77_000),
			PlaybackEvent.SessionMetadata(
				title = nextTitle,
				artist = "Another channel",
				durationMs = 600_000,
				mediaId = nextVideoId,
			),
			PlaybackEvent.Advance(400_000),
			PlaybackEvent.Finalized("different video ended"),
		)

		assertEquals(listOf(300_000L, 400_000L), harness.finalized.map { it.playedMs })
		assertEquals(
			harness.outcomes.joinToString { it.outcome.toString() },
			listOf(nextVideoId),
			harness.broadcasts.map { it.videoId },
		)
		assertEquals(67, harness.broadcasts.single().percentPlayed)
		harness.assertOneOutcomePerFinalization(2)
	}

	private fun preRollThenOrganic(organicMs: Long, watchedMs: Long): List<PlaybackEvent> = listOf(
		PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 54_000),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 2_577),
		PlaybackEvent.Advance(3_257),
		PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 5_839),
		PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = organicMs),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(watchedMs),
		PlaybackEvent.Finalized("organic video ended"),
	)

	private fun organicHarness(organicMs: Long): ReplayHarness = harness().also {
		it.env.facts.put(
			VideoFacts(
				videoId = videoId,
				title = title,
				author = channel,
				lengthSeconds = organicMs / 1000,
				category = "Entertainment",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
		it.env.identity.search = {
			VideoResolutionAttempt(
				resolution = VideoResolution(
					videoId = videoId,
					source = "search",
					title = title,
					channel = channel,
					lengthSeconds = organicMs / 1000,
					uniquelyResolved = true,
				),
			)
		}
	}

	@Test
	fun `a pre-roll interstitial may not anchor the organic presentation`() {
		val organicMs = 1_192_000L
		val harness = organicHarness(organicMs).feed(preRollThenOrganic(organicMs, watchedMs = 750_000))

		assertEquals("only the organic video may finalize", 1, harness.finalized.size)
		assertEquals(
			"organic progress after a pre-roll interstitial must be measured",
			750_000L,
			harness.finalized.single().playedMs,
		)
		assertEquals(organicMs, harness.finalized.single().durationMs)
		assertEquals(listOf(videoId), harness.broadcasts.map { it.videoId })
		assertEquals(63, harness.broadcasts.single().percentPlayed)
		assertEquals(emptyList<FinalizationRuntime.SkipRecord>(), FinalizationRuntime.skipped.value)
		harness.assertOneOutcomePerFinalization(1)
	}

	@Test
	fun `a below threshold organic video after a pre-roll creates one linked Not Logged outcome`() {
		val organicMs = 1_192_000L
		val harness = organicHarness(organicMs).feed(preRollThenOrganic(organicMs, watchedMs = 300_000))

		assertEquals("only the organic video may finalize", 1, harness.finalized.size)
		assertEquals(300_000L, harness.finalized.single().playedMs)
		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(1, harness.outcomes.size)
		assertTrue(harness.outcomes.single().outcome is FinalizationOutcome.Refused)
		assertEquals(videoId, FinalizationRuntime.skipped.value.single().videoId)
		harness.assertOneOutcomePerFinalization(1)
	}

	@Test
	fun `a presentation within the scale gap keeps its listen and quarantines instead`() {
		// 400s against 1,192s is 2.98x, inside the gap, so this is the established
		// case the both-direction quarantine owns: the replacement earns nothing
		// and the original listen keeps every second it measured.
		val establishedMs = 400_000L
		val harness = organicHarness(establishedMs).feed(
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = establishedMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(90_000),
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 90_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 1_192_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(60_000),
			PlaybackEvent.Finalized("session ended"),
		)

		assertEquals("the established presentation keeps its listen", 1, harness.finalized.size)
		assertEquals(
			"a presentation inside the scale gap may not be discarded",
			90_000L,
			harness.finalized.single().playedMs,
		)
		harness.assertOneOutcomePerFinalization(1)
	}

	@Test
	fun `a pre-roll pod longer than its own anchor may not hold the organic listen`() {
		val organicMs = 1_192_000L
		val harness = organicHarness(organicMs).feed(
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 29_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 2_938),
			PlaybackEvent.Advance(26_295),
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 29_226),
			PlaybackEvent.PlaybackStateChanged(playing = false, positionMs = 29_226),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 33_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 29_226),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(33_400),
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 33_320),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 33_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = organicMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(750_000),
			PlaybackEvent.Finalized("organic video ended"),
		)

		assertEquals("only the organic video may finalize", 1, harness.finalized.size)
		assertEquals(
			"the pre-roll pod may not keep the organic video from being measured",
			750_000L,
			harness.finalized.single().playedMs,
		)
		assertEquals(organicMs, harness.finalized.single().durationMs)
		assertEquals(listOf(videoId), harness.broadcasts.map { it.videoId })
		assertEquals(63, harness.broadcasts.single().percentPlayed)
		harness.assertOneOutcomePerFinalization(1)
	}

	@Test
	fun `a provisional anchor that looked like a rebase still yields to the work`() {
		val organicMs = 1_192_000L
		val harness = organicHarness(organicMs).feed(
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 27_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 2_049),
			PlaybackEvent.Advance(25_385),
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 27_349),
			PlaybackEvent.PlaybackStateChanged(playing = false, positionMs = 27_349),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 27_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 12_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 27_349),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(12_230),
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 12_117),
			PlaybackEvent.PlaybackStateChanged(playing = false, positionMs = 12_117),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 12_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = organicMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(750_000),
			PlaybackEvent.Finalized("organic video ended"),
		)

		assertEquals("only the organic video may finalize", 1, harness.finalized.size)
		assertEquals(
			"a coincidental rebase may not let the pod keep the listen",
			750_000L,
			harness.finalized.single().playedMs,
		)
		assertEquals(organicMs, harness.finalized.single().durationMs)
		assertEquals(63, harness.broadcasts.single().percentPlayed)
		harness.assertOneOutcomePerFinalization(1)
	}

	@Test
	fun `an interstitial watched to its own end still yields to the work`() {
		val organicMs = 1_192_000L
		val harness = organicHarness(organicMs).feed(
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 13_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 2_000),
			PlaybackEvent.Advance(10_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 58_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 13_376),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(58_196),
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 58_112),
			PlaybackEvent.PlaybackStateChanged(playing = false, positionMs = 58_112),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 58_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = organicMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(750_000),
			PlaybackEvent.Finalized("organic video ended"),
		)

		assertEquals("only the organic video may finalize", 1, harness.finalized.size)
		assertEquals(
			"a fully watched interstitial may not keep the listen",
			750_000L,
			harness.finalized.single().playedMs,
		)
		assertEquals(organicMs, harness.finalized.single().durationMs)
		assertEquals(63, harness.broadcasts.single().percentPlayed)
		harness.assertOneOutcomePerFinalization(1)
	}

	@Test
	fun `a resumed video measures only what was published to it`() {
		val organicMs = 1_192_000L
		val harness = organicHarness(organicMs).feed(
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 27_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 2_372),
			PlaybackEvent.Advance(25_069),
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 27_349),
			PlaybackEvent.PlaybackStateChanged(playing = false, positionMs = 27_349),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 27_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 6_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 27_349),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(6_300),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 6_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = organicMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 6_058),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 1_068_016),
			PlaybackEvent.Advance(124_568),
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 1_192_367),
			PlaybackEvent.Finalized("stopped replacement grace expired"),
		)

		assertEquals("only the organic video may finalize", 1, harness.finalized.size)
		val finalized = harness.finalized.single()
		assertEquals(
			"the measured 124s is an accurate account of what was published",
			124_568L,
			finalized.playedMs,
		)
		assertEquals(
			"the resume position, not the discarded interstitial's trailing 6,058 ms",
			1_068_016L,
			finalized.firstObservedPositionMs,
		)
		assertEquals(
			"the player ended at the video's end having measured only the last 124s",
			1_192_367L - 124_568L,
			(finalized.positionMs ?: 0) - finalized.playedMs,
		)
		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		val refusal = FinalizationRuntime.skipped.value.single().reason
		assertTrue(
			"the Not logged row has to say the video was resumed, or 10% reads as a " +
				"lost measurement: $refusal",
			refusal.contains("began 17:48 into it") &&
				refusal.contains("never published to RustedWax"),
		)
	}

	@Test
	fun `a quarantined surface may not define the finalized duration`() {
		val organicMs = 1_192_000L
		val harness = organicHarness(organicMs).feed(
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = organicMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(1_072_291),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 1_817_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 25),
			PlaybackEvent.Advance(30_000),
			PlaybackEvent.Finalized("session continuation expired"),
		)

		assertEquals("only the organic video may finalize", 1, harness.finalized.size)
		val finalized = harness.finalized.single()
		assertEquals(
			"the ad-inclusive surface may not become the listen's length",
			organicMs,
			finalized.durationMs,
		)
		assertEquals(
			"the quarantined interval earns nothing",
			1_072_291L,
			finalized.playedMs,
		)
		assertEquals(listOf(videoId), harness.broadcasts.map { it.videoId })
		assertEquals(90, harness.broadcasts.single().percentPlayed)
	}

	@Test
	fun `a sponsored surface open at finalize may not define the duration`() {
		val organicMs = 1_192_000L
		val harness = organicHarness(organicMs).feed(
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = organicMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(800_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 2_163_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 40),
			PlaybackEvent.Advance(47_000),
			PlaybackEvent.Finalized("session ended"),
		)

		assertEquals(1, harness.finalized.size)
		assertEquals(organicMs, harness.finalized.single().durationMs)
		assertEquals("the sponsored interval earns nothing", 800_000L, harness.finalized.single().playedMs)
		assertEquals(67, harness.broadcasts.single().percentPlayed)
	}

	/**
	 * The original protection, restated in the new model: a *shorter* interstitial
	 * open at finalize may not shrink the denominator either. This is what
	 * `longestDurationMs` was written for, and it must survive the change.
	 */
	@Test
	fun `a short interstitial open at finalize may not shrink the duration`() {
		val organicMs = 1_192_000L
		val harness = organicHarness(organicMs).feed(
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = organicMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(800_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 259_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 40),
			PlaybackEvent.Advance(20_000),
			PlaybackEvent.Finalized("session ended"),
		)

		assertEquals(1, harness.finalized.size)
		assertEquals(organicMs, harness.finalized.single().durationMs)
		assertEquals(800_000L, harness.finalized.single().playedMs)
		assertEquals(67, harness.broadcasts.single().percentPlayed)
	}

	/**
	 * The dwarf rule's hazard case, pinned deliberately.
	 *
	 * A real 400-second work, genuinely watched, followed by the same bogus
	 * 2,163-second ad-inclusive surface the field produced. That is 5.4x — the
	 * highest ratio the opposite shape reaches — and at the old 4x factor it was
	 * superseded, discarding 350 seconds of real measurement. It must not be.
	 *
	 * Duration magnitude is not track-change evidence. Where it cannot separate
	 * the two shapes, measured progress is preserved and the length stays with the
	 * presentation that earned it; identity resolution downstream remains free to
	 * refuse.
	 */
	@Test
	fun `a genuine short work is not superseded by a much longer bogus surface`() {
		val workMs = 400_000L
		val harness = organicHarness(workMs).feed(
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = workMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(350_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 2_163_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 40),
			PlaybackEvent.Advance(47_000),
			PlaybackEvent.Finalized("session ended"),
		)

		assertEquals("the work keeps its own listen", 1, harness.finalized.size)
		assertEquals(
			"350s of real measurement may not be discarded by a duration jump",
			350_000L,
			harness.finalized.single().playedMs,
		)
		assertEquals(
			"the bogus surface may not become the work's length",
			workMs,
			harness.finalized.single().durationMs,
		)
		assertEquals(88, harness.broadcasts.single().percentPlayed)
	}

	/**
	 * The other side of the same boundary: the lowest interstitial-to-work ratio
	 * the field produced (58s → 1,192s, 20.6x) must still supersede, or the
	 * pre-roll defect returns.
	 */
	@Test
	fun `the lowest observed interstitial ratio still yields to the work`() {
		val organicMs = 1_192_000L
		val harness = organicHarness(organicMs).feed(
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 58_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(58_000),
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 58_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = organicMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(750_000),
			PlaybackEvent.Finalized("organic video ended"),
		)

		assertEquals(1, harness.finalized.size)
		assertEquals(
			"the interstitial's 58s may not be credited to the work",
			750_000L,
			harness.finalized.single().playedMs,
		)
		assertEquals(organicMs, harness.finalized.single().durationMs)
		assertEquals(63, harness.broadcasts.single().percentPlayed)
	}
}
