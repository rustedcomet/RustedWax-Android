package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported length must belong to the verified presentation.
 *
 * Synthetic identity fixtures model a longer pre-roll published under a song's
 * metadata. Once the song's installed presentation is attributed to its exact
 * item, its own duration must govern the replay payload and percentage.
 */
class YouTubeMusicPreRollDurationReplayTest : ReplayScenarioTest() {

	private val videoId = "aaaaaaaaaa3"
	private val title = "Synthetic Bolero"
	private val artist = "Synthetic Orquesta"
	private val preRollMs = 296_193L
	private val songMs = 259_622L

	private fun harness(): ReplayHarness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC).also { harness ->
		harness.env.facts.put(
			VideoFacts(
				videoId = videoId,
				title = title,
				author = artist,
				originalArtist = artist,
				watchPageArtistCredit = artist,
				lengthSeconds = 260,
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
					lengthSeconds = 260,
					uniquelyResolved = true,
					presentationDurationCorroborated = true,
				),
			)
		}
	}

	private fun metadata(durationMs: Long) =
		PlaybackEvent.SessionMetadata(title = title, artist = artist, album = "Synthetic Album", durationMs = durationMs)

	private fun nextSong() = PlaybackEvent.SessionMetadata(
		title = "Next Synthetic Song",
		artist = artist,
		durationMs = 261_410,
	)

	/** The pre-roll under the song's metadata, paused by the lock screen. */
	private fun lockedPreRoll(): List<PlaybackEvent> = listOf(
		metadata(preRollMs),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(36_952),
		PlaybackEvent.PlaybackStateChanged(playing = false, positionMs = 36_566),
		PlaybackEvent.Advance(315_000),
	)

	/** The skip: the song's own presentation, attributed and played through. */
	private fun song(): List<PlaybackEvent> = listOf(
		metadata(songMs),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(5_000),
		PlaybackEvent.PresentationAttributionEstablished(videoId, songMs),
		PlaybackEvent.Advance(songMs - 5_000),
		nextSong(),
	)

	@Test
	fun `a pre-roll's longer length is not written for the song that replaced it`() {
		val harness = harness().feed(lockedPreRoll() + song())

		assertEquals(songMs, harness.finalized.first().playedMs)
		assertEquals(songMs, harness.finalized.first().durationMs)
		assertEquals(listOf(videoId), harness.broadcasts.map { it.videoId })
		assertEquals(listOf("4:19"), harness.broadcasts.map { it.duration })
		assertEquals(listOf(100), harness.broadcasts.map { it.percentPlayed })
		assertEquals(1, harness.transactionIds.size)
	}

	@Test
	fun `no length from a pod of fillers replaces the proven presentation's`() {
		val harness = harness().feed(
			listOf(
				metadata(30_093),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
				PlaybackEvent.Advance(30_000),
				metadata(preRollMs),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
				PlaybackEvent.Advance(40_000),
			) + song(),
		)

		assertEquals(listOf("4:19"), harness.broadcasts.map { it.duration })
		assertEquals(listOf(100), harness.broadcasts.map { it.percentPlayed })
		assertTrue(harness.finalized.first().playedMs <= songMs)
	}

	@Test
	fun `a song with no pre-roll still writes its own length`() {
		val harness = harness().feed(song().let { listOf(it.first()) + it.drop(1) })

		assertEquals(listOf("4:19"), harness.broadcasts.map { it.duration })
		assertEquals(listOf(100), harness.broadcasts.map { it.percentPlayed })
	}
}
