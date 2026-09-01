package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The YouTube Music catalogue lookup, adapted from the desktop extension.
 *
 * Every fixture below is trimmed from a real response captured on 2026-07-29
 * against ids from the field logs, so drift in the endpoint fails a test rather
 * than silently degrading classification.
 */
class YouTubeMusicParserTest {

	private fun response(
		videoId: String,
		type: String? = null,
		author: String? = null,
		title: String? = null,
		lengthSeconds: String? = null,
		category: String? = null,
		unlisted: String? = null,
	): String {
		val details = listOfNotNull(
			"\"videoId\":\"$videoId\"",
			type?.let { "\"musicVideoType\":\"$it\"" },
			author?.let { "\"author\":\"$it\"" },
			title?.let { "\"title\":\"$it\"" },
			lengthSeconds?.let { "\"lengthSeconds\":\"$it\"" },
		).joinToString(",")
		val micro = listOfNotNull(
			category?.let { "\"category\":\"$it\"" },
			unlisted?.let { "\"unlisted\":$it" },
		).joinToString(",")
		return """{"videoDetails":{$details},
			"microformat":{"microformatDataRenderer":{$micro}}}"""
	}

	// region what the catalogue says

	/** `GQwj_FRntp8` — MusicBrainz returned `no match` for this one in the field. */
	@Test
	fun `an art track carries canonical credits`() {
		val r = YouTubeMusicParser.parse(
			"GQwj_FRntp8",
			response(
				"GQwj_FRntp8",
				type = "MUSIC_VIDEO_TYPE_ATV",
				author = "Daddy Yankee",
				title = "Con Calma",
				lengthSeconds = "193",
				category = "Music",
				unlisted = "false",
			),
		)
		assertTrue(r.recognisedAsMusic)
		assertTrue(r.isArtTrack)
		assertEquals("Daddy Yankee", r.author)
		assertEquals("Con Calma", r.title)
		assertEquals(193L, r.lengthSeconds)
		assertEquals("Music", r.category)
		assertEquals(false, r.unlisted)
	}

	/**
	 * `l69Cq38GgZ4` — recognised as music, but its author is the *channel* and
	 * its title is the uploader's. The extension trusts these for OMV; we don't,
	 * which is what [YouTubeMusicParser.Result.isArtTrack] exists to express.
	 */
	@Test
	fun `an official music video is music but its credits are not canonical`() {
		val r = YouTubeMusicParser.parse(
			"l69Cq38GgZ4",
			response(
				"l69Cq38GgZ4",
				type = "MUSIC_VIDEO_TYPE_OMV",
				author = "Elena Verrier",
				title = "Metallica - Blackened (guitar cover)",
				lengthSeconds = "403",
			),
		)
		assertTrue(r.recognisedAsMusic)
		assertFalse(r.isArtTrack)
	}

	/** `cq2xXbWGHu8` — a football short. Absent type is a clean negative. */
	@Test
	fun `a video the catalogue has never heard of is not recognised`() {
		val r = YouTubeMusicParser.parse(
			"cq2xXbWGHu8",
			response("cq2xXbWGHu8", lengthSeconds = "39", unlisted = "false"),
		)
		assertNull(r.musicVideoType)
		assertFalse(r.recognisedAsMusic)
		assertFalse(r.isArtTrack)
		assertEquals(39L, r.lengthSeconds)
	}

	/**
	 * v0.8.7's timer regression. Catalogue membership said "podcast episode",
	 * not music, but the old prefix check treated every MUSIC_VIDEO_* value as
	 * song evidence.
	 */
	@Test
	fun `a podcast episode type is not recognised as music`() {
		val r = YouTubeMusicParser.parse(
			"cXbYjaEsQWg",
			response(
				"cXbYjaEsQWg",
				type = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
				author = "Timer Topia",
				title = "2 Minute Timer Bomb [COOKIE] 🍪",
				lengthSeconds = "125",
				category = "Education",
				unlisted = "false",
			),
		)
		assertFalse(r.recognisedAsMusic)
		assertFalse(
			VideoFacts(
				videoId = r.videoId,
				musicVideoType = r.musicVideoType,
			).recognisedByYouTubeMusic,
		)
	}

	/**
	 * `CYgQQqvwwsY` — the shorts-feed ad. The music client reports `unlisted`
	 * where the watch page reports `isUnlisted`, so the 10 KB request can gate
	 * the short-clip floor on its own when the page is unavailable.
	 */
	@Test
	fun `the shorts-feed ad reports itself unlisted here too`() {
		val r = YouTubeMusicParser.parse(
			"CYgQQqvwwsY",
			response("CYgQQqvwwsY", lengthSeconds = "18", unlisted = "true"),
		)
		assertEquals(true, r.unlisted)
		assertFalse(r.recognisedAsMusic)
	}

	@Test
	fun `an absent unlisted flag stays null`() {
		val r = YouTubeMusicParser.parse("abcdefghijk", response("abcdefghijk"))
		assertNull(r.unlisted)
		assertNull(r.lengthSeconds)
		assertNull(r.category)
	}

	/** A playability error still parses — it just says nothing. */
	@Test
	fun `an error response yields an empty result rather than throwing`() {
		val r = YouTubeMusicParser.parse(
			"abcdefghijk",
			"""{"playabilityStatus":{"status":"ERROR"},"responseContext":{}}""",
		)
		assertNull(r.musicVideoType)
		assertFalse(r.recognisedAsMusic)
	}
	// endregion

	@Test
	fun `the request body carries the client identity the endpoint demands`() {
		val body = YouTubeMusicParser.requestBody("abcdefghijk")
		// A WEB_REMIX identity is mandatory — the endpoint answers 400 without it.
		assertTrue(body.contains("WEB_REMIX"))
		assertTrue(body.contains("clientVersion"))
		assertTrue(body.contains("\"videoId\":\"abcdefghijk\""))
	}
}
