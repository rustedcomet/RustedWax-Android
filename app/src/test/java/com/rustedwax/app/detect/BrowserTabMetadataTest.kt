package com.rustedwax.app.detect

import com.rustedwax.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fields Brave publishes when the page has stopped describing a track.
 *
 * Every string here is a literal from the 2026-08-11 log, including the ones
 * that must survive: "Bring Me The Horizon - Youtopia" contains the site's name
 * and is a real song, and the suffix test has to know the difference.
 */
class BrowserTabMetadataTest {

	private val brave = "com.brave.browser"
	private val nativeYouTube = YouTubeProbe.YOUTUBE_PACKAGE

	@Test
	fun `the bare site name is the tab, not a track`() {
		assertTrue(BrowserTabMetadata.isTabTitle(brave, "YouTube"))
		assertTrue(BrowserTabMetadata.isTabTitle(brave, "  youtube  "))
		assertTrue(BrowserTabMetadata.isTabTitle(brave, "YouTube Music"))
		assertTrue(BrowserTabMetadata.isTabTitle(brave, "m.youtube.com"))
		assertNull(BrowserTabMetadata.titleOrNull(brave, "YouTube"))
	}

	@Test
	fun `a document title carries the site as a suffix`() {
		assertTrue(
			BrowserTabMetadata.isTabTitle(
				brave,
				"Bring Me The Horizon - Sleepwalking - YouTube",
			),
		)
		assertTrue(BrowserTabMetadata.isTabTitle(brave, "2Pac - Street Fame - YouTube"))
		assertTrue(BrowserTabMetadata.isTabTitle(brave, "lofi hip hop radio live - YouTube"))
	}

	@Test
	fun `a real title that merely mentions the site survives`() {
		val titles = listOf(
			"Bring Me The Horizon - Youtopia",
			"Bring Me The Horizon - YOUtopia (Lyric Video)",
			"How YouTube Changed Music",
			"YouTube Rewind 2018",
			"Metallica - Sad But True",
		)
		titles.forEach {
			assertFalse(it, BrowserTabMetadata.isTabTitle(brave, it))
			assertEquals(it, BrowserTabMetadata.titleOrNull(brave, it))
		}
	}

	@Test
	fun `the origin in the artist field is absence`() {
		assertNull(BrowserTabMetadata.artistOrNull(brave, "m.youtube.com"))
		assertNull(BrowserTabMetadata.artistOrNull(brave, "www.youtube.com"))
		assertNull(BrowserTabMetadata.artistOrNull(brave, " music.youtube.com "))
		assertEquals(
			"BMTHOfficialVEVO",
			BrowserTabMetadata.artistOrNull(brave, "BMTHOfficialVEVO"),
		)
		// The channel actually called YouTube keeps its name; only a host goes.
		assertEquals("YouTube", BrowserTabMetadata.artistOrNull(brave, "YouTube"))
	}

	@Test
	fun `the native app has no tab and no document title`() {
		assertFalse(BrowserTabMetadata.isTabTitle(nativeYouTube, "YouTube"))
		assertEquals(
			"YouTube",
			BrowserTabMetadata.titleOrNull(nativeYouTube, "YouTube"),
		)
		assertEquals(
			"m.youtube.com",
			BrowserTabMetadata.artistOrNull(nativeYouTube, "m.youtube.com"),
		)
	}

	@Test
	fun `nothing published stays nothing`() {
		assertFalse(BrowserTabMetadata.isTabTitle(brave, null))
		assertFalse(BrowserTabMetadata.isTabTitle(brave, "   "))
		assertNull(BrowserTabMetadata.titleOrNull(brave, null))
		assertNull(BrowserTabMetadata.artistOrNull(brave, null))
	}

	/**
	 * The identity the probe builds from those fields, which is where the damage
	 * was done: "Sleepwalking"/"BMTHOfficialVEVO" and the torn-down bundle that
	 * followed it have to be one track, not two.
	 */
	@Test
	fun `a torn down bundle does not end the track it followed`() {
		val playing = TrackIdentity(
			title = "Bring Me The Horizon - Sleepwalking",
			artist = "BMTHOfficialVEVO",
			album = null,
			durationMs = 236_741,
		)
		val originAsArtist = TrackIdentity(
			title = "Bring Me The Horizon - Sleepwalking",
			artist = BrowserTabMetadata.artistOrNull("com.brave.browser", "m.youtube.com"),
			album = null,
			durationMs = null,
		)
		assertTrue(playing.sameTrackAs(originAsArtist))
		assertEquals("BMTHOfficialVEVO", playing.refinedWith(originAsArtist).artist)
		// And the reverse: the channel arriving late names the same track.
		assertTrue(originAsArtist.sameTrackAs(playing))
		assertEquals("BMTHOfficialVEVO", originAsArtist.refinedWith(playing).artist)
	}

	@Test
	fun `finalization reads the channel established before the teardown bundle`() {
		// Reproduced 2026-08-11 on three consecutive background Brave tracks:
		// the final bundle kept the real title, replaced the channel with the
		// origin, and dropped the duration. The track identity retained all three,
		// but snapshot() used to ignore it and handed the resolver a blank owner.
		val established = TrackIdentity(
			title = "Krazy",
			artist = "2Pac - Topic",
			album = null,
			durationMs = 315_881,
		).refinedWith(TrackIdentity("Krazy", null, null, null))
		val presentation = finalizedPresentation(
			established = established,
			currentTitle = BrowserTabMetadata.titleOrNull(brave, "Krazy"),
			currentArtist = BrowserTabMetadata.artistOrNull(brave, "m.youtube.com"),
			currentAlbum = null,
		)
		assertEquals("Krazy", presentation.title)
		assertEquals("2Pac - Topic", presentation.artist)
		assertEquals(315_881L, presentation.durationMs)
	}

	@Test
	fun `two published channels that differ are still two tracks`() {
		val flo = TrackIdentity("Fly Girl", "FLO", null, 180_000)
		val doja = TrackIdentity("Fly Girl", "Doja Cat", null, 180_000)
		assertFalse(flo.sameTrackAs(doja))
	}
}
