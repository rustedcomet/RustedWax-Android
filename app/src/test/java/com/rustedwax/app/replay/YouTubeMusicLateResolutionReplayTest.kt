package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The proof that lands a few seconds after the listen ended.
 *
	 * Regression: pre-resolution can refuse while the track plays, then the same
	 * resolver can establish the exact media identity and pinned presentation at
	 * finalization. The listen must be evaluated with that late proof instead of
	 * remaining at zero because the reducer had already spent its refusal. The
	 * pre-resolution is one
 * bounded lookup against live listings and it does not always land while the
 * track is playing; nothing about whether 331 s were really heard depends on
 * that.
 *
 * These scenarios drive the production path with a resolver that refuses while
	 * the track plays and answers at finalization.
 * Every safety leg is asserted in the negative alongside: an answer that never
 * checked the length, an answer about a different length, and an interstitial's
 * banked seconds are all still refused.
 */
class YouTubeMusicLateResolutionReplayTest : ReplayScenarioTest() {

	private val videoId = "97iYm3Y4eKM"
	private val title = "Oiga, mire, vea"
	private val artist = "Guayacán Orquesta"
	private val songMs = 331_000L

	/**
	 * A resolver that is silent until [answering] is set, then gives the answer
	 * finalization gives.
	 *
	 * The flag is flipped by the scenario between the last playback event and the
	 * finalize, so the refusal the reducer acts on and the proof the finalization
	 * acts on are the same two events, in the same order, as on the device.
	 */
	private class LateResolver {
		var answering = false
		var presentationDurationCorroborated = true
		var resolvedLengthSec: Long? = null
	}

	private fun harness(
		durationMs: Long = songMs,
		resolver: LateResolver = LateResolver(),
	): Pair<ReplayHarness, LateResolver> {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC).also { harness ->
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
				if (!resolver.answering) {
					VideoResolutionAttempt(
						refusalReason = "no fully fetched candidate matched structured native " +
							"music title+artist+duration",
					)
				} else {
					VideoResolutionAttempt(
						resolution = VideoResolution(
							videoId = videoId,
							source = "structured native music title+artist+duration",
							title = title,
							channel = artist,
							lengthSeconds = resolver.resolvedLengthSec ?: (durationMs / 1000),
							uniquelyResolved = true,
							structuredNativeMusic = true,
							presentationDurationCorroborated =
								resolver.presentationDurationCorroborated,
						),
					)
				}
			}
		}
		return harness to resolver
	}

	private fun ReplayHarness.playThenAnswer(
		resolver: LateResolver,
		durationMs: Long,
		playedMs: Long,
	): ReplayHarness {
		feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = durationMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(playedMs),
		)
		assertTrue(
			"the scenario must exercise production's own carry-authority request",
			trace.carryAuthorityRequests > 0,
		)
		assertTrue(
			"and that request must have refused, or nothing here is being tested",
			trace.presentationAttributionAmbiguous,
		)
		resolver.answering = true
		return feed(PlaybackEvent.Finalized("track change"))
	}

	private fun skipRow() = FinalizationRuntime.skipped.value.single()

	// ── A. late identity for the listen ───────────────────────────────────────

	@Test
	fun `a song proven at finalization keeps the seconds the reducer had set aside`() {
		val (harness, resolver) = harness()
		harness.playThenAnswer(resolver, songMs, playedMs = songMs)

		assertEquals(
			"331s of real listening, proven by this listen's own finalization",
			1,
			harness.broadcasts.size,
		)
		assertEquals(
			"an eligible listen leaves no refusal row",
			emptyList<FinalizationRuntime.SkipRecord>(),
			FinalizationRuntime.skipped.value,
		)
	}

	@Test
	fun `a sub-threshold listen proven late states the seconds it really played`() {
		val (harness, resolver) = harness()
		harness.playThenAnswer(resolver, songMs, playedMs = 100_000)

		assertEquals(
			"below threshold is a refusal, not a broadcast",
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)
		val row = skipRow()
		assertEquals(
			"the row must state the time that was measured, not the zero the order produced",
			100L,
			row.playedSeconds,
		)
		assertTrue(
			"and must complain about the threshold, not about attribution: ${row.reason}",
			row.reason.contains("below 60% threshold"),
		)
		assertFalse(
			"the presentation was proven, late or not: ${row.reason}",
			row.reason.contains("never established which presentation"),
		)
	}

	// ── B. every leg of the proof is still required ───────────────────────────

	@Test
	fun `an answer that never checked the length credits nothing`() {
		val (harness, resolver) = harness()
		resolver.presentationDurationCorroborated = false
		harness.playThenAnswer(resolver, songMs, playedMs = songMs)

		assertEquals(
			"identity is not attribution, whenever it arrives",
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)
		assertEquals(0L, skipRow().playedSeconds)
	}

	@Test
	fun `an answer about another length credits nothing`() {
		val (harness, resolver) = harness(durationMs = 30_000)
		// The interstitial's 30s surface, and an answer describing the 331s work.
		// The lengths disagree, so the seconds measured on that surface stay where
		// the reducer put them.
		resolver.resolvedLengthSec = songMs / 1000
		harness.playThenAnswer(resolver, durationMs = 30_000, playedMs = 30_000)

		assertEquals(
			"a pre-roll may not collect the song's proof",
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)
		assertEquals(
			emptyList<Long>(),
			FinalizationRuntime.skipped.value.map { it.playedSeconds }.filter { it > 0 },
		)
	}

	@Test
	fun `only the presentation that was proven is credited, never the banked total`() {
		val (harness, resolver) = harness()
		harness.feed(
			// An interstitial published under the song's title and artist, with its
			// own 26s length, abandoned unattributed.
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = 26_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(26_000),
			// The song itself, also unattributed while it plays.
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(190_000),
		)
		resolver.answering = true
		harness.feed(PlaybackEvent.Finalized("track change"))

		val row = skipRow()
		assertEquals(
			"the song's own 190s, not the 216s the two surfaces measured between them",
			190L,
			row.playedSeconds,
		)
		assertEquals(
			"190s of 331s is 57%, and 216s would have crossed the threshold and written",
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)
	}
}
