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
 * Free YouTube Music pre-rolls, published under the song's own title and artist.
 *
	 * The synthetic traces represent free-tier behavior: the transport
 * carried the interstitial's duration and position while the metadata already
 * named the song that had not started yet.
 *
 * The abandonment cases deliberately give the engine everything it needs to
 * identify and broadcast the *song* — cached facts and a uniquely resolving
 * search — so that a false write is prevented by the measurement being refused,
 * not by an identity lookup happening to fail on one fixture.
 */
class YouTubeMusicAdProgressReplayTest : ReplayScenarioTest() {

	private val videoId = "dQw4w9WgXcQ"
	private val title = "Never Gonna Give You Up"
	private val artist = "Rick Astley"
	private val songMs = 213_000L

	private fun harness(
		durationMs: Long = songMs,
		videoId: String = this.videoId,
		title: String = this.title,
		artist: String = this.artist,
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
			VideoResolutionAttempt(
				resolution = VideoResolution(
					videoId = videoId,
					source = "search",
					title = title,
					channel = artist,
					lengthSeconds = durationMs / 1000,
					uniquelyResolved = true,
				),
			)
		}
	}

	private fun facts(
		videoId: String,
		title: String,
		durationMs: Long,
	) = VideoFacts(
		videoId = videoId,
		title = title,
		author = artist,
		lengthSeconds = durationMs / 1000,
		category = "Music",
		watchPageResolved = true,
		isUnlisted = false,
	)

	private fun establishedPresentation(
		videoId: String,
		durationMs: Long,
	) = PlaybackEvent.PresentationAttributionEstablished(
		videoId = videoId,
		durationMs = durationMs,
	)

	/**
	 * Reproduction A's pod, stopping short of the song: two 30 s interstitials
	 * under the song's metadata, the second reached by an end-to-start handover.
	 */
	private fun pod(): List<PlaybackEvent> = listOf(
		PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = 30_093),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(30_000),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = 30_058),
		PlaybackEvent.Advance(30_000),
	)

	/** A single presentation abandoned before any wrap or replacement. */
	private fun singlePreRoll(): List<PlaybackEvent> = listOf(
		PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = 30_000),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(29_000),
	)

	private fun assertNothingWasCreditedToTheSong(harness: ReplayHarness) {
		assertEquals(
			"no interstitial time may be reported as the song's",
			emptyList<Long>(),
			harness.finalized.map { it.playedMs }.filter { it > 0 },
		)
		assertEquals(
			"an abandoned pre-roll may never be broadcast",
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)
		assertEquals(
			"and may never leave a durable row claiming the song was played",
			emptyList<String>(),
			FinalizationRuntime.skipped.value
				.filter { it.playedSeconds > 0 }
				.map { "${it.title} played ${it.playedSeconds}s" },
		)
	}

	/** 1. abandoned pre-roll via STOPPED. */
	@Test
	fun `a pre-roll abandoned by STOPPED writes nothing and records no song progress`() {
		val harness = harness().feed(
			pod() + listOf(
				PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 29_900),
				PlaybackEvent.Finalized("stopped"),
			),
		)

		assertNothingWasCreditedToTheSong(harness)
		harness.assertOneOutcomePerFinalization()
	}

	@Test
	fun `a single pre-roll abandoned by STOPPED is refused despite otherwise valid identity`() {
		val harness = harness(durationMs = 30_000).feed(
			singlePreRoll() + listOf(
				PlaybackEvent.PlaybackStateChanged(
					playing = false,
					stopped = true,
					positionMs = 29_000,
				),
				PlaybackEvent.Finalized("stopped"),
			),
		)

		assertTrue("the replay must execute production carry authority", harness.trace.carryAuthorityRequests > 0)
		assertTrue("the otherwise-valid carry lookup must resolve", harness.trace.carryAuthorityResolutions > 0)
		assertTrue(
			"the resolved callback must execute production's carry-restore retry",
			harness.trace.carryRestoreAttemptsAfterResolution > 0,
		)
		assertNothingWasCreditedToTheSong(harness)
		harness.assertOneOutcomePerFinalization()
	}

	/** 2. abandoned pre-roll via session teardown. */
	@Test
	fun `a pre-roll abandoned by teardown writes nothing and records no song progress`() {
		val harness = harness().feed(
			pod() + listOf(
				PlaybackEvent.SessionRecreated,
				PlaybackEvent.Finalized("session ended"),
			),
		)

		assertNothingWasCreditedToTheSong(harness)
	}

	@Test
	fun `a single pre-roll abandoned by teardown is refused despite otherwise valid identity`() {
		val harness = harness(durationMs = 30_000).feed(
			singlePreRoll() + listOf(
				PlaybackEvent.ProbeDisposed(),
			),
		)

		assertTrue("the replay must execute production carry authority", harness.trace.carryAuthorityRequests > 0)
		assertTrue("the otherwise-valid carry lookup must resolve", harness.trace.carryAuthorityResolutions > 0)
		assertTrue(
			"the resolved callback must execute production's carry-restore retry",
			harness.trace.carryRestoreAttemptsAfterResolution > 0,
		)
		assertNothingWasCreditedToTheSong(harness)
	}

	/** 3. the abandoned state cannot become an ordinary song outcome. */
	@Test
	fun `an abandoned pre-roll cannot reach the threshold`() {
		val harness = harness().feed(pod() + PlaybackEvent.Finalized("track ended"))

		val song = harness.finalized.singleOrNull()
		assertTrue("the listen still ends exactly once", harness.finalized.size <= 1)
		if (song != null) {
			assertEquals("60s of interstitial is not song progress", 0L, song.playedMs)
			val duration = song.durationMs ?: 0
			assertTrue(
				"played ${song.playedMs} of $duration must not reach 60%",
				duration == 0L || song.playedMs * 100 / duration < 60,
			)
		}
		assertNothingWasCreditedToTheSong(harness)
	}

	/** An idle expiry during a pre-roll ends nothing at all. */
	@Test
	fun `an idle deadline during a pre-roll ends nothing`() {
		val harness = harness().feed(pod() + PlaybackEvent.IdleDeadlineReached)

		assertEquals(
			"60s of interstitial is not a 30s song played twice",
			emptyList<Long>(),
			harness.finalized.map { it.playedMs },
		)
		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
	}

	/** 7/8. two pre-rolls then the song played past threshold. */
	@Test
	fun `two pre-rolls credit the song nothing and never finalize it`() {
		val harness = harness().feed(
			pod() + listOf(
				PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = songMs),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
				PlaybackEvent.Advance(140_000),
				PlaybackEvent.Finalized("song ended"),
			),
		)

		assertEquals("the interstitials may not finalize the song", 1, harness.finalized.size)
		val song = harness.finalized.single()
		assertEquals("only the song's own time is credited", 140_000L, song.playedMs)
		assertEquals(songMs, song.durationMs)
		assertFalse("consecutive interstitials are not a repeat", song.loopDetected)
		assertTrue(
			"carry authority still resolves after playback evidence establishes the song",
			harness.trace.carryAuthorityResolutions > 0,
		)
		harness.assertOneOutcomePerFinalization(1)
	}

	@Test
	fun `organic carry authority reclaims pending progress after native session replacement`() {
		val harness = harness()
		harness.feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			establishedPresentation(videoId, songMs),
			PlaybackEvent.Advance(40_000),
			PlaybackEvent.NativeSessionRecreatedAwaitingCarryAuthority,
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 40_000),
		)

		assertEquals(
			"the replacement must request and resolve carry authority twice; " +
				"requests=${harness.trace.carryAuthorityRequests}",
			2,
			harness.trace.carryAuthorityResolutions,
		)
		assertEquals(
			"the post-resolution retry must reclaim the real pending fragment",
			1,
			harness.trace.carryProgressRestorationsAfterResolution,
		)
		assertEquals(40_000L, harness.trace.carriedPlayedMsAfterResolution)
		assertEquals(40_000L, harness.trace.currentPlayedMs)
		assertTrue(
			"resolved identity and restored progress still do not attribute the replacement surface",
			harness.trace.presentationAttributionAmbiguous,
		)

		harness.feed(
			establishedPresentation(videoId, songMs),
			PlaybackEvent.Advance(100_000),
			PlaybackEvent.Finalized("track ended"),
		)
		assertEquals(
			"the restored fragment and replacement playback remain one organic listen",
			140_000L,
			harness.finalized.single().playedMs,
		)
		assertEquals(1, harness.broadcasts.size)
	}

	@Test
	fun `post-resolution carry retry cannot make a provisional fragment song progress`() {
		val harness = harness(durationMs = 30_000)
		harness.feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = 30_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(15_000),
			PlaybackEvent.NativeSessionRecreatedAwaitingCarryAuthority,
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 15_000),
			PlaybackEvent.Advance(14_000),
		)

		assertEquals(
			"the provisional replacement must request and resolve carry authority twice; " +
				"requests=${harness.trace.carryAuthorityRequests}",
			2,
			harness.trace.carryAuthorityResolutions,
		)
		assertEquals(
			"production reclaims the fragment only into the still-ambiguous listen",
			1,
			harness.trace.carryProgressRestorationsAfterResolution,
		)
		assertEquals(15_000L, harness.trace.carriedPlayedMsAfterResolution)
		assertEquals("the full provisional interval remains quarantined", 29_000L, harness.trace.currentPlayedMs)
		assertTrue(
			"carry identity and a real carry claim cannot attribute interstitial time",
			harness.trace.presentationAttributionAmbiguous,
		)

		harness.feed(
			PlaybackEvent.PlaybackStateChanged(
				playing = false,
				stopped = true,
				positionMs = 29_000,
			),
			PlaybackEvent.Finalized("stopped"),
		)
		assertNothingWasCreditedToTheSong(harness)
	}

	/** 7. a 15 s pre-roll, then the 215 s song: no ad lead in the scored progress. */
	@Test
	fun `a pre-roll's lead is not scored against the song`() {
		val songMs = 215_713L
		val title = "Somebody's Watching Me (Official Music Video)"
		val artist = "Rockwell"
		val harness = harness(
			durationMs = songMs,
			videoId = "KFS9c852M_k",
			title = title,
			artist = artist,
		).feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = 15_082),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(15_082),
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(130_000),
			PlaybackEvent.Finalized("song ended"),
		)

		val song = harness.finalized.single()
		assertEquals("the 15s pre-roll is not song progress", 130_000L, song.playedMs)
	}

	/** 6. a legitimate short track is measured and scored on its own terms. */
	@Test
	fun `a legitimate 30s track is not treated as an interstitial`() {
		val trackMs = 30_000L
		val harness = harness(durationMs = trackMs, videoId = "aaaaaaaaaaa", title = "Skit").feed(
			PlaybackEvent.SessionMetadata(title = "Skit", artist = artist, durationMs = trackMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			establishedPresentation("aaaaaaaaaaa", trackMs),
			PlaybackEvent.Advance(29_000),
			PlaybackEvent.Finalized("track ended"),
		)

		val track = harness.finalized.single()
		assertEquals("a short track keeps every second it played", 29_000L, track.playedMs)
		assertEquals(trackMs, track.durationMs)
	}

	/** 4. a genuine short Song replaced by its longer Video keeps its progress. */
	@Test
	fun `a 45s song switched to its 120s video keeps its progress`() {
		val initialVideoId = "ddddddddddd"
		val harness = harness(
			durationMs = 120_000,
			videoId = "bbbbbbbbbbb",
			title = "Interlude",
		).also {
			it.env.facts.put(facts(initialVideoId, "Interlude", 45_000))
		}.feed(
			PlaybackEvent.SessionMetadata(title = "Interlude", artist = artist, durationMs = 45_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			establishedPresentation(initialVideoId, 45_000),
			PlaybackEvent.Advance(20_000),
			PlaybackEvent.SessionMetadata(title = "Interlude", artist = artist, durationMs = 120_000),
			PlaybackEvent.Advance(40_000),
			PlaybackEvent.Finalized("track ended"),
		)

		assertEquals("one listen across the presentation boundary", 1, harness.finalized.size)
		assertEquals(
			"crossing 60s is not evidence of anything",
			60_000L,
			harness.finalized.single().playedMs,
		)
	}

	@Test
	fun `a proven presentation at natural end retains progress across a legitimate update`() {
		val initialVideoId = "eeeeeeeeeee"
		val harness = harness(
			durationMs = 215_000,
			videoId = "fffffffffff",
			title = "Interlude",
		).also {
			it.env.facts.put(facts(initialVideoId, "Interlude", 15_000))
		}.feed(
			PlaybackEvent.SessionMetadata(title = "Interlude", artist = artist, durationMs = 15_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			establishedPresentation(initialVideoId, 15_000),
			PlaybackEvent.Advance(15_000),
			PlaybackEvent.SessionMetadata(title = "Interlude", artist = artist, durationMs = 215_000),
			PlaybackEvent.Advance(20_000),
			PlaybackEvent.Finalized("track ended"),
		)

		assertEquals(1, harness.finalized.size)
		assertEquals(
			"a spent but proven presentation is not discarded as a pre-roll",
			35_000L,
			harness.finalized.single().playedMs,
		)
	}

	/** 5. an ordinary 61 s → 58 s correction keeps its progress. */
	@Test
	fun `a 61s to 58s correction keeps its progress`() {
		val initialVideoId = "ggggggggggg"
		val harness = harness(
			durationMs = 58_000,
			videoId = "ccccccccccc",
			title = "Short Track",
		).also {
			it.env.facts.put(facts(initialVideoId, "Short Track", 61_000))
		}.feed(
			PlaybackEvent.SessionMetadata(title = "Short Track", artist = artist, durationMs = 61_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			establishedPresentation(initialVideoId, 61_000),
			PlaybackEvent.Advance(30_000),
			PlaybackEvent.SessionMetadata(title = "Short Track", artist = artist, durationMs = 58_000),
			PlaybackEvent.Advance(20_000),
			PlaybackEvent.Finalized("track ended"),
		)

		assertEquals(1, harness.finalized.size)
		assertEquals(
			"a correction is not a replacement to quarantine",
			50_000L,
			harness.finalized.single().playedMs,
		)
	}

	/** E. an ordinary song with no interstitial is measured exactly as before. */
	@Test
	fun `a song with no interstitial is unchanged`() {
		val harness = harness().feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			establishedPresentation(videoId, songMs),
			PlaybackEvent.Advance(140_000),
			PlaybackEvent.Finalized("song ended"),
		)

		val song = harness.finalized.single()
		assertEquals(140_000L, song.playedMs)
		assertEquals(songMs, song.durationMs)
		assertFalse(song.loopDetected)
	}
}
