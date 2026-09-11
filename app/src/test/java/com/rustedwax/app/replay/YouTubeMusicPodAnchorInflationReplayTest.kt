package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pod surface that was never identified, and the anchor it took for free.
 *
	 * Regression: a pod can run under the song's title while one unidentified
	 * surface supersedes another. The song that follows must not inherit the last
	 * pod surface's seconds.
 *
 * Nothing in that chain had been identified as the song. The 19 s surface was
 * discarded correctly; the 45 s surface that replaced it was handed the organic
 * anchor by the supersede rule itself, and the next boundary read that anchor
 * back as `outgoingWasProven` and carried its 45 s into the song.
 *
 * Two very different things write that anchor, and telling them apart is the
 * whole of the fix:
 *
 *  - a **live exact-item proof** — the transport's own exact id, or a resolver
 *    answer whose length had to be this presentation's — says which item is
 *    playing, and may carry its seconds across a later boundary;
 *  - the **spent-surface supersede** says only that something short ended and
 *    something longer began under the same title. That is enough to start
 *    measuring the new surface, which is Bug 5's entire purpose, and it is not
 *    enough to spend those seconds on whatever replaces it.
 *
 * Bug 5's own discard, the Song↔Video continuation and ordinary ad exclusion are
 * all unchanged; only the carry is now asked which kind of anchor it is spending.
 */
class YouTubeMusicPodAnchorInflationReplayTest : ReplayScenarioTest() {

	private val songId = "YHsP10noEAA"
	private val title = "Solid As A Rock"
	private val artist = "Sizzla"
	private val songMs = 214_000L

	/**
	 * A resolver that names the work but never corroborates a length, which is
	 * what the device had while the pod was on screen: identity is available and
	 * attribution is not.
	 */
	private fun harness(
		corroborates: Boolean = false,
		/** Corroborates whatever length is asked — a pod that really is some upload. */
		corroboratesEveryLength: Boolean = false,
	): ReplayHarness =
		ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC).also { harness ->
			harness.env.facts.put(
				VideoFacts(
					videoId = songId,
					title = title,
					author = artist,
					originalArtist = artist,
					watchPageArtistCredit = artist,
					lengthSeconds = songMs / 1000,
					category = "Music",
					watchPageResolved = true,
					isUnlisted = false,
				),
			)
			harness.env.identity.search = {
				val asked = harness.env.identity.searchRequests.lastOrNull()?.durationSec
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = songId,
						source = "search",
						title = title,
						channel = artist,
						lengthSeconds = songMs / 1000,
						uniquelyResolved = true,
						presentationDurationCorroborated = corroboratesEveryLength ||
							(corroborates && asked == songMs / 1000),
					),
				)
			}
		}

	private fun metadata(durationMs: Long) =
		PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = durationMs)

	/** Synthetic pod: 19 s spent, superseded by 45 s, itself spent. */
	private fun ReplayHarness.playThePod(): ReplayHarness = feed(
		metadata(19_000),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(19_000),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 19_000),
		// Spent and replaced by a longer surface: Bug 5 discards the 19 s and
		// anchors the 45 s one.
		metadata(45_000),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(45_000),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 45_000),
	)

	// ── A/B. anchor-inflation regression ─────────────────────────────────────

	@Test
	fun `a pod surface holding only a superseded anchor adds nothing to the song`() {
		val harness = harness().playThePod()

		assertFalse(
			"the supersede rule does make the pod surface measurable — that is Bug 5",
			harness.trace.presentationAttributionAmbiguous,
		)

		harness.feed(
			metadata(songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(130_000),
			PlaybackEvent.Finalized("song ended"),
		)

		val song = harness.finalized.single()
		assertEquals(
			"only the 130s heard on the song's own presentation — 175s was the pod's " +
				"45s folded in",
			130_000L,
			song.playedMs,
		)
		assertEquals(
			"the pod's 45s is recorded as measured-and-refused so the outcome can " +
				"explain itself, and is never added to progress",
			45_000L,
			song.unattributedMeasuredMs,
		)
	}

	@Test
	fun `the chain that inflated the device no longer reaches 100 percent`() {
		val harness = harness().playThePod().feed(
			metadata(songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(130_000),
			PlaybackEvent.Finalized("song ended"),
		)

		assertEquals(1, harness.broadcasts.size)
		assertEquals(
			"130s of a 214s song is 61%, not 100%",
			listOf(61),
			harness.broadcasts.map { it.percentPlayed },
		)
	}

	// ── C. pod time may not carry a song over the threshold ──────────────────

	@Test
	fun `pod seconds cannot push a sub-threshold song over the line`() {
		// 45 s of pod plus 110 s of a 214 s song is 72 % only if the pod counts.
		// The song alone is 51 %.
		val harness = harness().playThePod().feed(
			metadata(songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(110_000),
			PlaybackEvent.Finalized("song ended"),
		)

		assertEquals(110_000L, harness.finalized.single().playedMs)
		assertEquals(
			"a song half heard is not a scrobble",
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)
	}

	// ── D. a real proof still carries across the boundary ────────────────────

	@Test
	fun `a presentation proven by an exact item keeps its progress across the switch`() {
		// The Song↔Video contract. The first presentation earns a live
		// duration-corroborated proof, so its seconds are the work's own and
		// survive the rendering change exactly as before.
		val harness = harness(corroborates = true).feed(
			metadata(songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(90_000),
		)
		assertFalse(
			"the scenario must genuinely attribute, or it proves nothing",
			harness.trace.presentationAttributionAmbiguous,
		)
		assertTrue(harness.trace.carryAuthorityResolutions > 0)

		harness.feed(
			// The other rendering of the same work, mid-item.
			metadata(240_000),
			PlaybackEvent.Advance(60_000),
			PlaybackEvent.Finalized("track ended"),
		)

		assertEquals("one work rendered two ways is one listen", 1, harness.finalized.size)
		assertEquals(
			"the 90s already earned survives the switch",
			150_000L,
			harness.finalized.single().playedMs,
		)
	}

	// ── A (Path 2). a pod that genuinely earns an exact-item proof ───────────

	/**
	 * The pod may hold a *genuine* exact-item proof for a short upload, then be
	 * followed by a different presentation under the same work metadata. Proof of being some
	 * item is not proof of being the item that follows.
	 */
	/**
	 * A resolver with a real short upload as well as the song, each corroborating
	 * its own length — which is exactly what YouTube hosts: `Ets811a2uyQ` is a
	 * genuine 57 s "Solid As A Rock".
	 */
	private fun podAndSongHarness(): ReplayHarness =
		ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC).also { harness ->
			listOf(songId to songMs / 1000, "Ets811a2uyQ" to 57L).forEach { (id, seconds) ->
				harness.env.facts.put(
					VideoFacts(
						videoId = id,
						title = title,
						author = artist,
						originalArtist = artist,
						watchPageArtistCredit = artist,
						lengthSeconds = seconds,
						category = "Music",
						watchPageResolved = true,
						isUnlisted = false,
					),
				)
			}
			harness.env.identity.search = {
				val asked = harness.env.identity.searchRequests.lastOrNull()?.durationSec
				val id = if (asked != null && asked <= 60) "Ets811a2uyQ" else songId
				val seconds = if (id == songId) songMs / 1000 else 57L
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = id,
						source = "structured native music title+artist+duration",
						title = title,
						channel = artist,
						lengthSeconds = seconds,
						uniquelyResolved = true,
						structuredNativeMusic = true,
						presentationDurationCorroborated = true,
					),
				)
			}
		}

	@Test
	fun `a pod proven by an exact item still contributes nothing to the song`() {
		val harness = podAndSongHarness().feed(
			metadata(57_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(57_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 57_000),
		)
		assertFalse(
			"the pod really is attributed here, or this proves nothing",
			harness.trace.presentationAttributionAmbiguous,
		)

		harness.feed(
			// Played to its own end and handed over end-to-start.
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			metadata(songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(130_000),
			PlaybackEvent.Finalized("song ended"),
		)

		assertEquals(
			"the proven pod's 57s is not the song's",
			130_000L,
			harness.finalized.single().playedMs,
		)
	}

	@Test
	fun `a proven song replaced by a shorter surface keeps every second it earned`() {
		// The other order, and the reason the rule asks which presentation is
		// longer. A 216s listen that had merely finished before a pod was destroyed
		// by an earlier attempt at this rule; it must keep its progress.
		val harness = harness(corroborates = true).feed(
			metadata(songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = songMs),
			// Ends, wraps, and a shorter pod follows.
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			metadata(57_000),
			PlaybackEvent.Finalized("stopped"),
		)

		assertEquals(
			"the song played whole, so its own seconds survive the handover",
			songMs,
			harness.finalized.single().playedMs,
		)
	}

	// ── E. an ordinary unproven ad is still excluded ─────────────────────────

	@Test
	fun `an ordinary pre-roll that never supersedes is still excluded`() {
		// Left before its own end, so Bug 5's discard cannot reach it and the
		// false-progress rule is what refuses it. The song earns its own proof
		// here, exactly as it does on the device. Unchanged by this fix.
		val harness = harness(corroborates = true).feed(
			metadata(30_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(20_000),
			metadata(songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(140_000),
			PlaybackEvent.Finalized("song ended"),
		)

		assertEquals(
			"only the song's own 140s",
			140_000L,
			harness.finalized.single().playedMs,
		)
	}

	@Test
	fun `a pod abandoned before any song writes nothing at all`() {
		val harness = harness().playThePod().feed(
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 45_000),
			PlaybackEvent.Finalized("stopped"),
		)

		assertEquals(
			"no pod second may be broadcast as a listen",
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)
	}
}
