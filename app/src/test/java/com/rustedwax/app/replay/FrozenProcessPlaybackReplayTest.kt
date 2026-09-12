package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A frozen process must not turn one playback window into multiple operations.
 *
 * Synthetic identity fixtures cover overdue-timer and track-change ordering.
 * Corroborated background playback and position-proven second passes retain
 * their earned credit. Broadcast assertions use the replay harness only.
 */
class FrozenProcessPlaybackReplayTest : ReplayScenarioTest() {

	private val videoId = "aaaaaaaaaa2"
	private val title = "Synthetic Song"
	private val artist = "Synthetic Artist"
	private val songMs = 222_958L

	private fun harness(): ReplayHarness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC).also { harness ->
		harness.env.facts.put(
			VideoFacts(
				videoId = videoId,
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
			VideoResolutionAttempt(
				resolution = VideoResolution(
					videoId = videoId,
					source = "search",
					title = title,
					channel = artist,
					lengthSeconds = songMs / 1000,
					uniquelyResolved = true,
					presentationDurationCorroborated = true,
				),
			)
		}
	}

	private fun songStarts(): List<PlaybackEvent> = listOf(
		PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = songMs),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(181_000),
		PlaybackEvent.PresentationAttributionEstablished(videoId, songMs),
	)

	private fun nextSong() = PlaybackEvent.SessionMetadata(
		title = "Next Synthetic Song",
		artist = artist,
		durationMs = 347_084,
	)

	@Test
	fun `a five-minute freeze after the last callback writes the song once`() {
		val harness = harness().feed(
			songStarts() + listOf(
				// Frozen: the song ends and the next begins with nothing delivered.
				PlaybackEvent.Advance(308_000),
				// Thaw: the overdue idle timer runs before the queued callbacks.
				PlaybackEvent.IdleDeadlineReached,
				nextSong(),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			),
		)

		val played = harness.finalized.first().playedMs
		assertTrue("the freeze was credited: ${played}ms of ${songMs}ms", played in songMs..(songMs + 2_000))
		assertEquals(listOf(videoId), harness.broadcasts.map { it.videoId })
		assertEquals(listOf(100), harness.broadcasts.map { it.percentPlayed })
		assertEquals(1, harness.transactionIds.size)
		assertEquals(emptyList<String>(), harness.duplicateAttempts)
	}

	@Test
	fun `the same freeze thawing into the track change first writes the song once`() {
		val harness = harness().feed(
			songStarts() + listOf(
				PlaybackEvent.Advance(308_000),
				nextSong(),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			),
		)

		val played = harness.finalized.first().playedMs
		assertTrue("the freeze was credited: ${played}ms of ${songMs}ms", played in songMs..(songMs + 2_000))
		assertEquals(listOf(videoId), harness.broadcasts.map { it.videoId })
		assertEquals(1, harness.transactionIds.size)
	}

	@Test
	fun `continuous playback the transport corroborates is credited in full`() {
		val harness = harness().feed(
			songStarts() + listOf(
				PlaybackEvent.Advance(30_000),
				PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 211_000),
				PlaybackEvent.Finalized("stopped"),
			),
		)

		assertEquals(211_000L, harness.finalized.single().playedMs)
		assertEquals(listOf(95), harness.broadcasts.map { it.percentPlayed })
	}

	@Test
	fun `background playback with a live clock still counts`() {
		val harness = harness().feed(
			listOf(
				PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = songMs),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
				PlaybackEvent.SurfaceChanged(PlaybackEvent.Surface.BACKGROUND),
				PlaybackEvent.PresentationAttributionEstablished(videoId, songMs),
				PlaybackEvent.Advance(songMs),
				nextSong(),
			),
		)

		assertEquals(songMs, harness.finalized.first().playedMs)
		assertEquals(listOf(videoId), harness.broadcasts.map { it.videoId })
	}

	@Test
	fun `a second pass the position proves still earns a second transaction`() {
		val harness = harness().feed(
			listOf(
				PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = songMs),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
				PlaybackEvent.PresentationAttributionEstablished(videoId, songMs),
				PlaybackEvent.Advance(140_000),
				// A seek back to the start 140 s in: not a wrap, a real replay.
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
				PlaybackEvent.Advance(songMs),
				PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = songMs),
				PlaybackEvent.Finalized("stopped"),
			),
		)

		assertEquals(140_000L + songMs, harness.finalized.single().playedMs)
		assertEquals(listOf(100, 63), harness.broadcasts.map { it.percentPlayed })
		assertEquals(2, harness.transactionIds.size)
	}
}
