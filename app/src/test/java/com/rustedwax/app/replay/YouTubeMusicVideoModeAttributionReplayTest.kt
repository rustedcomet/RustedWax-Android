package com.rustedwax.app.replay

import com.rustedwax.app.detect.listenNeedsPresentationProof
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YouTube Music playing an album in Video presentation, where no catalog row
 * has the length being published.
 *
	 * Regression: a Video-mode presentation can resolve its exact id through
	 * watch-page title, artist metadata and duration corroboration even when no
	 * catalog row has the published length. That proof must attribute the measured
	 * presentation rather than finalizing it at zero.
 *
 * The cause was a gate that asked *which listing named the work* instead of
 * *whether the proof checked the length*. A Video-mode presentation's length is
 * the upload's, not the catalog song's, so the structured routes miss it and the
 * resolver falls through to `verifyFromWatchPages` — which verifies title,
 * channel **and** the published duration against the candidate's own watch page,
 * and was being treated as though it had verified nothing.
 *
 * These scenarios therefore hand the harness a proof that corroborated the
 * duration while carrying none of the structured-music markers. If attribution
 * ever goes back to reading the route bucket, every test here fails.
 */
class YouTubeMusicVideoModeAttributionReplayTest : ReplayScenarioTest() {

	private val videoId = "F1Ww3Xgjm3c"
	private val title = "Can You Feel My Heart"
	private val artist = "Bring Me The Horizon"

	/** The video upload's length — deliberately not any catalog row's. */
	private val videoMs = 228_000L

	/**
	 * A harness whose resolver answers the way `verifyFromWatchPages` does: it
	 * names the id only while the published length matches the candidate's own
	 * page, and it is **not** a structured-music proof.
	 */
	private fun harness(
		durationMs: Long = videoMs,
		videoId: String = this.videoId,
		title: String = this.title,
		corroboratesDuration: Boolean = true,
	): ReplayHarness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC).also { harness ->
		harness.env.facts.put(
			VideoFacts(
				videoId = videoId,
				title = title,
				author = artist,
				originalArtist = artist,
				watchPageArtistCredit = artist,
				lengthSeconds = durationMs / 1000,
				category = "Music",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
		harness.env.identity.search = {
			val askedDurationSec = harness.env.identity.searchRequests.lastOrNull()?.durationSec
			if (askedDurationSec != null && askedDurationSec == durationMs / 1000) {
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = videoId,
						source = "Shorts watch-page completion",
						title = title,
						channel = artist,
						lengthSeconds = durationMs / 1000,
						uniquelyResolved = true,
						// The point of the fixture: no structured-music marker, so
						// the route lands in RAW_TITLE_CHANNEL, and yet the length
						// was genuinely checked.
						structuredNativeMusic = false,
						presentationDurationCorroborated = corroboratesDuration,
					),
				)
			} else {
				VideoResolutionAttempt(refusalReason = "no watch-page candidate fully corroborated")
			}
		}
	}

	private fun ReplayHarness.play(
		title: String,
		durationMs: Long,
		playedMs: Long,
	): ReplayHarness = feed(
		PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = durationMs),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(playedMs),
	)

	// ── A. the reproduced failure ─────────────────────────────────────────────

	@Test
	fun `a video-mode track proven by its own watch page keeps every second it played`() {
		val harness = harness().play(title, videoMs, playedMs = videoMs)

		assertTrue(
			"the scenario must run production's own carry-authority request",
			harness.trace.carryAuthorityResolutions > 0,
		)
		assertFalse(
			"a watch-page proof that pinned the length attributes the surface, " +
				"even though its route is not the structured one",
			harness.trace.presentationAttributionAmbiguous,
		)

		harness.feed(PlaybackEvent.Finalized("track change"))
		assertEquals(
			"228s played end to end is not zero progress",
			videoMs,
			harness.finalized.single().playedMs,
		)
		assertEquals(1, harness.broadcasts.size)
	}

	/**
	 * The first attempt is the only attempt a first play gets.
	 *
	 * A video rendering that resolves on a *second* play, or only once a run-local
	 * candidate from an earlier failed play is available, has not been fixed —
	 * the listen that was lost is the first one.
	 */
	@Test
	fun `a video-mode first play resolves on its first and only attempt`() {
		val harness = harness().play(title, videoMs, playedMs = videoMs)

		assertEquals(
			"one presentation, one bounded lookup: no retry and no second play",
			1,
			harness.trace.carryAuthorityRequests,
		)
		assertEquals(
			"and that one lookup is what answers",
			1,
			harness.trace.carryAuthorityResolutions,
		)
		assertFalse(harness.trace.presentationAttributionAmbiguous)

		harness.feed(PlaybackEvent.Finalized("track change"))
		assertEquals(videoMs, harness.finalized.single().playedMs)
		assertEquals(listOf(videoId), harness.broadcasts.map { it.videoId })
	}

	// ── re-entry: a second listen of the same work needs its own proof ────────

	/**
	 * Regression: after a Video-mode item is attributed and later re-entered from
	 * STOPPED, the new generation can carry the previous listen's
	 * `trackIdentity` — id included — into a state whose attribution had been
	 * reset, and the lookup guard read the id alone and skipped. The second listen
	 * measured its whole length and could credit none of it.
	 */
	@Test
	fun `a re-entered listen holds the id but not the proof, and must ask again`() {
		val harness = harness().play(title, videoMs, playedMs = 120_000)
		assertFalse("the first listen is attributed", harness.trace.presentationAttributionAmbiguous)
		assertTrue("and holds its id", harness.trace.currentHasExactSourceItemId)

		// STOPPED then PLAYING on the same presentation: the first listen is
		// finalized and a second generation begins.
		harness.feed(
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 120_000),
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = videoMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(140_000),
		)

		assertEquals("the first listen ended", 1, harness.finalized.size)
		// The exact combination the old guard mishandled: the new generation
		// inherited the id, but attribution was reset with the listen.
		assertTrue(
			"identity survives the generation boundary",
			harness.trace.currentHasExactSourceItemId,
		)
		assertTrue(
			"attribution does not, so this listen holds no proof",
			harness.trace.presentationAttributionAmbiguous,
		)
		assertTrue(
			"and a listen in that state must be allowed to ask for one — reading the " +
				"id alone is what lost the re-entered 228s listen",
			listenNeedsPresentationProof(
				hasExactSourceItemId = true,
				presentationAttributionAmbiguous = true,
			),
		)
	}

	@Test
	fun `the proof rule keeps identity and attribution on their own lifetimes`() {
		assertFalse(
			"an attributed listen holding its id asks for nothing",
			listenNeedsPresentationProof(
				hasExactSourceItemId = true,
				presentationAttributionAmbiguous = false,
			),
		)
		assertTrue(
			"a listen with an id but no proof still has to ask",
			listenNeedsPresentationProof(
				hasExactSourceItemId = true,
				presentationAttributionAmbiguous = true,
			),
		)
		assertTrue(
			"and a listen with no id always did",
			listenNeedsPresentationProof(
				hasExactSourceItemId = false,
				presentationAttributionAmbiguous = false,
			),
		)
	}

	@Test
	fun `one listen still spends only one lookup however often metadata repeats`() {
		val harness = harness().feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = videoMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(20_000),
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = videoMs),
			PlaybackEvent.Advance(20_000),
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = videoMs),
			PlaybackEvent.Advance(20_000),
		)

		assertEquals(
			"the signature check still collapses repeats inside one listen",
			1,
			harness.trace.carryAuthorityRequests,
		)
	}

	// ── B. the same route without the duration check attributes nothing ───────

	@Test
	fun `the same route attributes nothing when the proof did not check the length`() {
		val harness = harness(corroboratesDuration = false).play(title, videoMs, playedMs = videoMs)

		assertTrue(
			"the id must still resolve, or this proves nothing",
			harness.trace.carryAuthorityResolutions > 0,
		)
		assertTrue(
			"a proof that never pinned the length may not attribute the surface",
			harness.trace.presentationAttributionAmbiguous,
		)
	}

	@Test
	fun `an unattributed listen still explains itself instead of disappearing`() {
		val harness = harness(corroboratesDuration = false)
			.play(title, videoMs, playedMs = 162_000)
			.feed(PlaybackEvent.Finalized("track change"))

		assertEquals(
			"the refusal stands: none of it is credited",
			0L,
			harness.finalized.single().playedMs,
		)
		assertEquals(
			"and nothing is broadcast",
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)

		val row = FinalizationRuntime.skipped.value.single()
		assertEquals(title, row.title)
		assertEquals(
			"the row may not claim the track was played",
			0L,
			row.playedSeconds,
		)
		assertTrue(
			"but it must say what was seen and why none of it counted: ${row.reason}",
			row.reason.contains("162s played here") &&
				row.reason.contains("never established which presentation was the named work"),
		)
		assertFalse(
			"and must not repeat the arithmetic-true, evening-false 'played 0%'",
			row.reason.contains("played 0%"),
		)
	}

	@Test
	fun `a listen too brief to be notable is still silent`() {
		val harness = harness(corroboratesDuration = false)
			.play(title, videoMs, playedMs = 2_000)
			.feed(PlaybackEvent.Finalized("track change"))

		assertEquals(
			"the three-second floor still keeps metadata churn out of the list",
			emptyList<FinalizationRuntime.SkipRecord>(),
			FinalizationRuntime.skipped.value,
		)
	}

	// ── C. identity alone is still never attribution ──────────────────────────

	@Test
	fun `an exact id with no duration proof leaves a video-mode surface ambiguous`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC).also {
			it.env.identity.search = {
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = videoId,
						source = "title+channel search; finalized duration unavailable",
						title = title,
						channel = artist,
						uniquelyResolved = true,
					),
				)
			}
		}.play(title, videoMs, playedMs = 60_000)

		assertTrue(harness.trace.carryAuthorityResolutions > 0)
		assertTrue(
			"knowing which work is playing says nothing about which surface is",
			harness.trace.presentationAttributionAmbiguous,
		)
	}

	// ── D. ads are still excluded ─────────────────────────────────────────────

	@Test
	fun `a pre-roll before a video-mode track is refused attribution and adds nothing`() {
		val harness = harness().feed(
			// 30 s interstitial under the song's own title and artist. No catalog
			// row and no watch page has the work at this length, so nothing names it.
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = 30_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(30_000),
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = videoMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(150_000),
			PlaybackEvent.Finalized("song ended"),
		)

		assertEquals("the interstitial may not finalize the song", 1, harness.finalized.size)
		assertEquals(
			"only the video's own 150s is credited; the 30s pre-roll is not",
			150_000L,
			harness.finalized.single().playedMs,
		)
	}

	// ── E. an abandoned pre-roll still writes nothing ─────────────────────────

	@Test
	fun `a pre-roll abandoned before a video-mode track writes nothing`() {
		val harness = harness().feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = 30_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(29_000),
		)

		assertTrue(
			"its own length matches nothing, so the surface stays unattributed",
			harness.trace.presentationAttributionAmbiguous,
		)

		harness.feed(
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 29_000),
			PlaybackEvent.Finalized("stopped"),
		)

		assertEquals(
			"no interstitial second may be reported as the song's",
			emptyList<Long>(),
			harness.finalized.map { it.playedMs }.filter { it > 0 },
		)
		assertEquals(
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)
		assertEquals(
			"and none may be written down as a listen that happened",
			emptyList<String>(),
			FinalizationRuntime.skipped.value
				.filter { it.playedSeconds > 0 }
				.map { "${it.title} played ${it.playedSeconds}s" },
		)
	}

	// ── F/G. Music ↔ Video is one listen, and the gap is not a listen ─────────

	/**
	 * The switch republishes the same work at the other rendering's length, and
	 * the player's position jumps with it. What the listener heard does not change
	 * because the app started describing it differently, so the earned time is
	 * kept — and the jump itself is not time anyone spent listening, so it is not
	 * added.
	 */
	private fun assertSwitchKeepsProgress(
		firstMs: Long,
		secondMs: Long,
		playedBefore: Long,
		playedAfter: Long,
	) {
		val harness = harness(durationMs = firstMs).play(title, firstMs, playedMs = playedBefore)
		assertFalse(
			"the first rendering is attributed before the switch",
			harness.trace.presentationAttributionAmbiguous,
		)

		harness.feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = secondMs),
			PlaybackEvent.Advance(playedAfter),
			PlaybackEvent.Finalized("track ended"),
		)

		assertEquals(
			"one work rendered two ways is one listen",
			1,
			harness.finalized.size,
		)
		assertEquals(
			"the ${playedBefore / 1000}s already earned survives the switch, and the " +
				"gap the switch created is not credited",
			playedBefore + playedAfter,
			harness.finalized.single().playedMs,
		)
	}

	@Test
	fun `Music to Video keeps the seconds already earned and credits no gap`() {
		assertSwitchKeepsProgress(
			firstMs = 45_000,
			secondMs = 120_000,
			playedBefore = 40_000,
			playedAfter = 30_000,
		)
	}

	@Test
	fun `Video to Music keeps the seconds already earned and credits no gap`() {
		assertSwitchKeepsProgress(
			firstMs = 120_000,
			secondMs = 45_000,
			playedBefore = 40_000,
			playedAfter = 20_000,
		)
	}

	// ── H. a sub-threshold video-mode track explains itself truthfully ────────

	@Test
	fun `a sub-threshold video-mode track earns a Not-logged row with its real time`() {
		val harness = harness().play(title, videoMs, playedMs = 54_000)
			.feed(PlaybackEvent.Finalized("track change"))

		assertEquals(54_000L, harness.finalized.single().playedMs)
		assertEquals(
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)

		val row = FinalizationRuntime.skipped.value.single()
		assertEquals(title, row.title)
		assertEquals("the row states what was measured", 54L, row.playedSeconds)
		assertTrue(
			"and explains itself truthfully: ${row.reason}",
			row.reason.contains("24%") && row.reason.contains("below 60% threshold"),
		)
	}

	@Test
	fun `no finalized video-mode listen is absent from both History and Not logged`() {
		val harness = harness().play(title, videoMs, playedMs = 54_000)
			.feed(PlaybackEvent.Finalized("track change"))

		assertEquals(
			"every finalized listen owes the user a scrobble or a visible refusal",
			harness.finalized.size,
			harness.broadcasts.size + FinalizationRuntime.skipped.value.size,
		)
	}
}
