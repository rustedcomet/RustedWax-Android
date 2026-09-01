package com.rustedwax.app.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Literal playback-position evidence for one continuous item looping.
 *
 * The Android callback that consumes this rule is not available to a pure JVM
 * test, so the boundary predicate is kept here with the exact v0.8.7 device
 * evidence.
 */
class PlaybackLoopTest {

	@Test
	fun `the timer reset across session recreation is a loop`() {
		assertTrue(
			SessionProbe.positionWrapped(
				previousPositionMs = 125_021,
				newPositionMs = 482,
				durationMs = 125_021,
			),
		)
	}

	@Test
	fun `a normal mid-video session restart is not a loop`() {
		assertFalse(SessionProbe.positionWrapped(47_039, 47_410, 196_000))
		assertFalse(SessionProbe.positionWrapped(70_769, 71_020, 196_000))
	}

	@Test
	fun `ordinary backward seeking does not satisfy both boundaries`() {
		assertFalse(SessionProbe.positionWrapped(60_000, 20_000, 120_000))
		assertFalse(SessionProbe.positionWrapped(110_000, 40_000, 120_000))
		assertFalse(SessionProbe.positionWrapped(70_000, 5_000, 120_000))
	}

	@Test
	fun `missing or invalid positions are not loop evidence`() {
		assertFalse(SessionProbe.positionWrapped(null, 0, 120_000))
		assertFalse(SessionProbe.positionWrapped(120_000, null, 120_000))
		assertFalse(SessionProbe.positionWrapped(120_000, 0, null))
		assertFalse(SessionProbe.positionWrapped(-1, 0, 120_000))
		assertFalse(SessionProbe.positionWrapped(120_000, -1, 120_000))
		assertFalse(SessionProbe.positionWrapped(120_000, 0, 0))
	}
}
