package com.rustedwax.app.detect

import com.rustedwax.hive.HiveScrobblePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** §6.2. Classification only — the fields the extension fills from a DOM stay absent. */
class ScreenWorkClassifierTest {

	private val feature = 100 * 60_000L

	@Test
	fun `an explicit season and episode marker names an episode`() {
		val result = ScreenWorkClassifier.classify("Some Show S02E05 - The Reckoning", 45 * 60_000L)
		assertEquals(HiveScrobblePayload.KIND_EPISODE, result!!.kind)
		assertEquals(2, result.season)
		assertEquals(5, result.episode)
	}

	@Test
	fun `the spelled-out and numeric forms are recognised too`() {
		assertEquals(
			3 to 12,
			ScreenWorkClassifier.classify("Show Name Season 3 Episode 12", null)
				?.let { it.season to it.episode },
		)
		assertEquals(
			1 to 4,
			ScreenWorkClassifier.classify("Serie 1x04 completo", null)
				?.let { it.season to it.episode },
		)
	}

	/**
	 * `1920x1080` is a resolution, and it appears in ad slate names and upload
	 * titles constantly. Reading it as season 1920 episode 1080 would file half
	 * the ad creatives in `debug/` as television.
	 */
	@Test
	fun `a resolution is not a season and episode`() {
		assertNull(ScreenWorkClassifier.classify("indrive 15s v16 1920x1080 panama subs", null))
		assertNull(ScreenWorkClassifier.classify("MotionGraphic 16x9 None Brand English", null))
	}

	@Test
	fun `a feature-length film with a year and YouTube's own category is a movie`() {
		val result = ScreenWorkClassifier.classify(
			"The Quiet Hour (2019)",
			feature,
			enrichedCategory = "Film & Animation",
		)
		assertEquals(HiveScrobblePayload.KIND_MOVIE, result!!.kind)
		assertEquals(2019, result.year)
		assertEquals("The Quiet Hour", result.title)
	}

	/**
	 * The case that started this: a trailer is a promo for a film, not the film.
	 * Crediting someone with having watched `Fall 2` because they watched two
	 * minutes of its trailer is the false claim §6.2 is meant to stop, not one
	 * to introduce from the other direction.
	 */
	@Test
	fun `a trailer stays an ordinary video`() {
		assertNull(
			ScreenWorkClassifier.classify(
				"Fall 2: Deadpoint (2026) Official Trailer 2 - Harriet Slater, Arsema Thomas",
				150_000,
				enrichedCategory = "Film & Animation",
			),
		)
	}

	/** A structural claim without the runtime to back it is not a film. */
	@Test
	fun `a short recap is not a full movie`() {
		assertNull(ScreenWorkClassifier.classify("Full Movie in 2 Minutes!", 120_000))
	}

	/** Music must be untouched: this runs ahead of the music/video split. */
	@Test
	fun `ordinary music is not a screen work`() {
		assertNull(ScreenWorkClassifier.classify("Korn - Trash (Official Audio)", 240_000))
		assertNull(
			ScreenWorkClassifier.classify(
				"Bring Me The Horizon - Kool-Aid (Official Video)",
				244_000,
				enrichedCategory = "Music",
			),
		)
	}
}
