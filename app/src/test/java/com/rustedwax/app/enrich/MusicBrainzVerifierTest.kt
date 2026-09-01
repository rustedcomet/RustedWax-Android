package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matching rules are the safety of the whole MusicBrainz layer: a false
 * "found" turns some clip into a song and canonicalizes its fields to a
 * recording it isn't. These pin the strictness.
 */
class MusicBrainzVerifierTest {

	/** Shaped like a real /ws/2/recording search response, trimmed. */
	private fun response(vararg recordings: String) =
		"""{"created":"2026-07-24","count":${recordings.size},"offset":0,""" +
			""""recordings":[${recordings.joinToString(",")}]}"""

	private fun recording(score: Int, title: String, artist: String) =
		"""{"id":"x","score":$score,"title":"$title",""" +
			""""artist-credit":[{"name":"$artist","artist":{"id":"y","name":"$artist"}}]}"""

	@Test
	fun `matches when artist and title both agree`() {
		val body = response(recording(100, "Doomed", "Bring Me the Horizon"))
		val match = MusicBrainzVerifier.matchFrom(body, "bring me the horizon", "doomed")
		assertTrue(match.found)
		// Canonical casing comes back from the database, not from the parse.
		assertEquals("Bring Me the Horizon", match.artist)
		assertEquals("Doomed", match.title)
	}

	/**
	 * The dangerous direction: the title exists as somebody's recording, but
	 * not this uploader's. Title-only matching would claim every clip named
	 * like some song — "BlueBird", "Film", "Doomed" are all real recordings.
	 */
	@Test
	fun `a title match with the wrong artist is not a match`() {
		val body = response(recording(100, "BlueBird", "Some Real Band"))
		assertFalse(MusicBrainzVerifier.matchFrom(body, "The Future Is Wild", "BlueBird").found)
	}

	@Test
	fun `a low search score is not a match`() {
		val body = response(recording(45, "Doomed", "Bring Me the Horizon"))
		assertFalse(MusicBrainzVerifier.matchFrom(body, "Bring Me the Horizon", "Doomed").found)
	}

	@Test
	fun `normalization forgives case whitespace and quotes`() {
		val body = response(recording(97, "For Whom the Bell Tolls", "Metallica"))
		assertTrue(
			MusicBrainzVerifier.matchFrom(body, "METALLICA", "“For Whom the  Bell Tolls”").found,
		)
	}

	@Test
	fun `an empty result set is a clean negative`() {
		assertFalse(MusicBrainzVerifier.matchFrom(response(), "Anyone", "Anything").found)
	}

	@Test
	fun `picks the right recording out of several`() {
		val body = response(
			recording(100, "Doomed", "A Tribute Band"),
			recording(95, "Doomed", "Bring Me the Horizon"),
		)
		val match = MusicBrainzVerifier.matchFrom(body, "Bring Me the Horizon", "Doomed")
		assertTrue(match.found)
		assertEquals("Bring Me the Horizon", match.artist)
	}
}
