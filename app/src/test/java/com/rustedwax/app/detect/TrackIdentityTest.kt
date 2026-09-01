package com.rustedwax.app.detect

import com.rustedwax.core.*
import com.rustedwax.app.scrobble.ScrobbleRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrackIdentityTest {

	private fun cardi(durationMs: Long?) = TrackIdentity(
		title = "Cardi B - Trump",
		artist = "Cardi B",
		album = null,
		durationMs = durationMs,
	)

	@Test
	fun `log 14 227125 to 227124 is one qualifying track`() {
		val first = cardi(227_125)
		val refined = cardi(227_124)
		assertTrue(first.sameTrackAs(refined))

		// The two log fragments were 48% and 53%. They must reach one rule
		// decision as combined progress, not two independent threshold failures.
		val combinedPlayedMs = 109_260L + 120_390L
		val decision = ScrobbleRules.decide(
			playedMs = combinedPlayedMs,
			durationMs = refined.durationMs,
		)
		assertTrue(decision.shouldScrobble)
		assertEquals(listOf(100), decision.percentages)
	}

	@Test
	fun `missing duration and two-second drift are refinements`() {
		assertTrue(cardi(null).sameTrackAs(cardi(227_125)))
		assertTrue(cardi(227_125).sameTrackAs(cardi(229_125)))
		assertFalse(cardi(227_125).sameTrackAs(cardi(229_126)))
	}

	@Test
	fun `changed metadata is a real track change`() {
		assertFalse(cardi(227_125).sameTrackAs(cardi(227_125).copy(title = "Enough")))
		assertFalse(cardi(227_125).sameTrackAs(cardi(227_125).copy(artist = "Another Artist")))
		assertFalse(cardi(227_125).sameTrackAs(cardi(227_125).copy(album = "Another Album")))
	}

	@Test
	fun `different exact native video ids are a real track change`() {
		val first = cardi(227_125).copy(sourceItemId = "abcdefghijk")
		val second = cardi(227_125).copy(sourceItemId = "lmnopqrstuv")
		assertFalse(first.sameTrackAs(second))
		assertFalse(first.sameTrackAs(cardi(227_125).copy(sourceItemId = "Abcdefghijk")))
		assertTrue(first.sameTrackAs(cardi(227_125).copy(sourceItemId = null)))
	}

	@Test
	fun `exact id less same metadata material duration replacement is discard evidence`() {
		val sevenSeconds = cardi(7_000)
		val fullSong = cardi(257_000)
		assertTrue(sevenSeconds.isExactIdlessMaterialDurationReplacement(fullSong))
		assertTrue(fullSong.isExactIdlessMaterialDurationReplacement(sevenSeconds))
		assertFalse(cardi(227_125).isExactIdlessMaterialDurationReplacement(cardi(227_124)))
		assertFalse(
			sevenSeconds.isExactIdlessMaterialDurationReplacement(
				fullSong.copy(title = "Another song"),
			),
		)
		assertFalse(
			sevenSeconds.copy(sourceItemId = "abcdefghijk")
				.isExactIdlessMaterialDurationReplacement(fullSong),
		)
		// SessionProbe deliberately removes memory-only resolver authority before
		// evaluating a new exact-ID-less MediaSession duration callback.
		assertTrue(
			sevenSeconds.copy(sourceItemId = "abcdefghijk").copy(sourceItemId = null)
				.isExactIdlessMaterialDurationReplacement(fullSong),
		)
	}

	@Test
	fun `a missing artist is silence during an exact id less duration replacement`() {
		val titleOnly = cardi(952_000).copy(artist = null)
		val lateArtistAndShortDuration = cardi(7_000)

		assertTrue(titleOnly.isExactIdlessMaterialDurationReplacement(lateArtistAndShortDuration))
		assertTrue(lateArtistAndShortDuration.isExactIdlessMaterialDurationReplacement(titleOnly))
		assertFalse(
			cardi(952_000).isExactIdlessMaterialDurationReplacement(
				lateArtistAndShortDuration.copy(artist = "Contradictory channel"),
			),
		)
	}

	@Before fun setUp() = TrackProgressCarry.clear()
	@After fun tearDown() = TrackProgressCarry.clear()

	@Test
	fun `duration refinement claims carried progress but material conflict cannot`() {
		val progress = TrackProgressCarry.Progress(
			playedMs = 109_260,
			trackStartedAtEpochSec = 1_785_000_000,
			fastestSpeedSeen = 1.0,
			atMillis = 1_000_000,
		)
		TrackProgressCarry.remember("com.android.chrome", cardi(227_125), progress)
		assertNotNull(
			TrackProgressCarry.claim("com.android.chrome", cardi(227_124), now = 1_010_000),
		)

		TrackProgressCarry.remember("com.android.chrome", cardi(227_125), progress)
		assertNull(
			TrackProgressCarry.claim("com.android.chrome", cardi(230_000), now = 1_010_000),
		)
		assertEquals(1, TrackProgressCarry.size())
	}
}
