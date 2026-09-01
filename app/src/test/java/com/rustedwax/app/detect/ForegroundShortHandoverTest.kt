package com.rustedwax.app.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the MediaSession is allowed to throw away when the foreground Shorts
 * route takes the player.
 *
 * Measured 2026-08-07: a 104-second trailer reached 85 seconds, the viewer
 * opened the Shorts tab, and the whole listen was deleted with no finalize.
 * Discarding is only correct when the Short taking over is the same item the
 * session was already describing.
 */
class ForegroundShortHandoverTest {

	@Test fun `a trailer is not the Short that takes over from it`() {
		assertFalse(
			ForegroundShortHandover.describesSameItem(
				sessionTitle = "THE RUN — Official Trailer (2026)",
				sessionDurationMs = 104_000,
				shortTitle = "These singers crossed the line and flirted with their own fans on stage",
				shortDurationMs = 54_000,
			),
		)
	}

	@Test fun `the same Short on both surfaces is one item`() {
		assertTrue(
			ForegroundShortHandover.describesSameItem(
				sessionTitle = "These singers crossed the line",
				sessionDurationMs = 54_000,
				shortTitle = "These singers crossed the line",
				shortDurationMs = 54_000,
			),
		)
	}

	@Test fun `title decides even when the two surfaces round the length apart`() {
		assertTrue(
			ForegroundShortHandover.describesSameItem(
				sessionTitle = "  Some Short  ",
				sessionDurationMs = 54_400,
				shortTitle = "Some short",
				shortDurationMs = 54_000,
			),
		)
	}

	@Test fun `a Short with no readable footer title is recognised by its length`() {
		assertTrue(
			ForegroundShortHandover.describesSameItem(
				sessionTitle = "Some Short",
				sessionDurationMs = 15_000,
				shortTitle = null,
				shortDurationMs = 15_000,
			),
		)
	}

	@Test fun `a titleless Short of a different length is a different item`() {
		assertFalse(
			ForegroundShortHandover.describesSameItem(
				sessionTitle = "IDENTITEAZE | Official Trailer",
				sessionDurationMs = 84_000,
				shortTitle = null,
				shortDurationMs = 15_000,
			),
		)
	}

	@Test fun `a seekbar-less Short proves nothing about the outgoing track`() {
		assertFalse(
			ForegroundShortHandover.describesSameItem(
				sessionTitle = "Lanterns | Official Trailer | HBO Max",
				sessionDurationMs = 188_000,
				shortTitle = null,
				shortDurationMs = null,
			),
		)
	}

	@Test fun `no foreground snapshot at all proves nothing`() {
		assertFalse(
			ForegroundShortHandover.describesSameItem(
				sessionTitle = "Clean Bandit - Symphony (feat. Zara Larsson) [Official Video]",
				sessionDurationMs = 227_000,
				shortTitle = null,
				shortDurationMs = 0,
			),
		)
	}
}
