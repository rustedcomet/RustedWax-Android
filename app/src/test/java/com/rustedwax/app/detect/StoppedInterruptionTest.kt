package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The transport evidence that separates an interrupted surface from an end. */
class StoppedInterruptionTest {

	private fun holds(
		displayInteractive: Boolean,
		stoppedForMs: Long,
		surfaceDisappearanceConfirmed: Boolean = true,
		stoppedAtMs: Long = 90_000,
		durationMs: Long = 240_000,
	): Boolean = StoppedInterruption.holdsListenOpen(
		displayInteractive = displayInteractive,
		stoppedForMs = stoppedForMs,
		surfaceDisappearanceConfirmed = surfaceDisappearanceConfirmed,
		stoppedAtMs = stoppedAtMs,
		durationMs = durationMs,
	)

	@Test
	fun `a screen-on stop ends the listen`() {
		assertFalse(holds(displayInteractive = true, stoppedForMs = 0))
		assertFalse(holds(displayInteractive = true, stoppedForMs = 10_000))
		assertFalse(
			holds(
				displayInteractive = true,
				stoppedForMs = StoppedInterruption.SCREEN_OFF_HOLD_CAP_MS * 4,
			),
		)
	}

	@Test
	fun `a proven surface disappearance with the display off holds the listen`() {
		assertTrue(holds(displayInteractive = false, stoppedForMs = 10_000))
		assertTrue(
			holds(
				displayInteractive = false,
				stoppedForMs = StoppedInterruption.SCREEN_OFF_HOLD_CAP_MS - 1,
			),
		)
	}

	@Test
	fun `display off without a surface disappearance is a genuine stop`() {
		assertFalse(
			holds(
				displayInteractive = false,
				stoppedForMs = 10_000,
				surfaceDisappearanceConfirmed = false,
			),
		)
	}

	@Test
	fun `a media boundary is an end even after the surface disappeared`() {
		assertFalse(
			holds(
				displayInteractive = false,
				stoppedForMs = 10_000,
				stoppedAtMs = 240_000,
				durationMs = 240_000,
			),
		)
	}

	@Test
	fun `the lock reset and restore prove the playback surface disappeared`() {
		val resetFrom = StoppedInterruption.resetCandidate(
			previousPositionMs = 71_065,
			stoppedPositionMs = 0,
		)
		assertEquals(71_065L, resetFrom)
		assertTrue(StoppedInterruption.confirmsSurfaceDisappearance(resetFrom, 71_065))
		assertFalse(StoppedInterruption.confirmsSurfaceDisappearance(resetFrom, 0))
		assertFalse(StoppedInterruption.confirmsSurfaceDisappearance(null, 71_065))
	}

	@Test
	fun `the hold gives up at the cap`() {
		assertFalse(
			holds(
				displayInteractive = false,
				stoppedForMs = StoppedInterruption.SCREEN_OFF_HOLD_CAP_MS,
			),
		)
		assertFalse(
			holds(
				displayInteractive = false,
				stoppedForMs = StoppedInterruption.SCREEN_OFF_HOLD_CAP_MS + 1,
			),
		)
	}

	@Test
	fun `the hold and the continuation give up together`() {
		assertEquals(
			TrackProgressCarry.RESUMED_TTL_MS,
			StoppedInterruption.SCREEN_OFF_HOLD_CAP_MS,
		)
	}
}
