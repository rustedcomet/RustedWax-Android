package com.rustedwax.app.detect

import com.rustedwax.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
