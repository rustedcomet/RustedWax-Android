package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which callbacks [SessionProbe.transportSpeed] takes at their word.
 *
 * Exactly one shape is: a Chromium host, PLAYING, a known position, a rate of
 * exactly zero — what Brave published for the whole of a network stall on the
 * A36. Everything else keeps [SessionProbe.speedFactor]'s answer.
 */
class TransportSpeedTest {

	private fun speed(
		playing: Boolean = true,
		reported: Float? = 0f,
		positionMs: Long? = 55_021,
		chromiumHost: Boolean = true,
	) = SessionProbe.transportSpeed(playing, reported, positionMs, chromiumHost)

	@Test
	fun `a stalled Chromium transport at a known position runs at zero`() {
		assertEquals(0.0, speed(), 0.0)
		assertEquals(0.0, speed(positionMs = 0), 0.0)
	}

	@Test
	fun `an unknown position keeps the old reading`() {
		assertEquals(1.0, speed(positionMs = -1), 0.0)
		assertEquals(1.0, speed(positionMs = null), 0.0)
	}

	@Test
	fun `a native source keeps the old reading`() {
		assertEquals(1.0, speed(chromiumHost = false), 0.0)
	}

	@Test
	fun `a transport that is not playing keeps the old reading`() {
		assertEquals(1.0, speed(playing = false), 0.0)
	}

	/** IEEE zero only: `-0.0` is that zero, so it stalls too. */
	@Test
	fun `only a zero rate qualifies`() {
		assertEquals(0.0, speed(reported = -0f), 0.0)
		assertEquals(1.0, speed(reported = -2f), 0.0)
		assertEquals(1.0, speed(reported = Float.NaN), 0.0)
		assertEquals(1.0, speed(reported = null), 0.0)
	}

	@Test
	fun `positive rates are unchanged, including the ceiling`() {
		assertEquals(1.0, speed(reported = 1f), 0.0)
		assertEquals(1.5, speed(reported = 1.5f), 0.0)
		assertEquals(SessionProbe.MAX_PLAYBACK_SPEED, speed(reported = 500f), 0.0)
	}
}
