package com.rustedwax.app.detect

import com.rustedwax.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A metadata bundle that omits DURATION says nothing about the length.
 *
 * Measured 2026-08-10 in Brave: YouTube's web player reported 236981 ms for
 * "Happy Song" throughout a 247-second watch, then republished the same title
 * and artist with DURATION unset half a second before the track changed. The
 * finalize read "played 247s of 0s", so there was no percentage to clear a
 * threshold with and no length to resolve an id by, and a complete listen was
 * skipped as "title, owner/channel and duration were not all available".
 */
class EstablishedDurationTest {

	private fun happySong(durationMs: Long?) = TrackIdentity(
		title = "Bring Me The Horizon - Happy Song (Official Audio)",
		artist = "BMTHOfficialVEVO",
		album = null,
		durationMs = durationMs,
	)

	@Test
	fun `dropping the duration does not end the track`() {
		// If it did, the listen would be cut in two instead of losing its length.
		assertTrue(happySong(236_981).sameTrackAs(happySong(null)))
	}

	@Test
	fun `the established length survives a bundle that omits it`() {
		// This is the value SessionProbe.snapshot falls back on, which is why it
		// is not inventing a length: the session already holds this one.
		assertEquals(
			236_981L,
			happySong(236_981).refinedWith(happySong(null)).durationMs,
		)
	}

	@Test
	fun `a length that arrives late still establishes itself`() {
		assertEquals(
			236_981L,
			happySong(null).refinedWith(happySong(236_981)).durationMs,
		)
	}

	@Test
	fun `a real track change carries nothing across`() {
		val next = TrackIdentity(
			title = "Bring Me The Horizon - Can You Feel My Heart",
			artist = "BMTHOfficialVEVO",
			album = null,
			durationMs = null,
		)
		assertEquals(null, happySong(236_981).refinedWith(next).durationMs)
	}
}
