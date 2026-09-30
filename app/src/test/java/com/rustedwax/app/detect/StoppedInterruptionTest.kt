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
		rustedWaxForeground: Boolean = false,
		foregroundSurfaceDisappearanceConfirmed: Boolean = false,
	): Boolean = StoppedInterruption.holdsListenOpen(
		displayInteractive = displayInteractive,
		stoppedForMs = stoppedForMs,
		surfaceDisappearanceConfirmed = surfaceDisappearanceConfirmed,
		stoppedAtMs = stoppedAtMs,
		durationMs = durationMs,
		rustedWaxForeground = rustedWaxForeground,
		foregroundSurfaceDisappearanceConfirmed = foregroundSurfaceDisappearanceConfirmed,
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

	@Test
	fun `RustedWax in front holds a proven surface disappearance with the display on`() {
		assertTrue(holds(displayInteractive = true, stoppedForMs = 10_000, rustedWaxForeground = true))
		assertTrue(
			holds(
				displayInteractive = true,
				stoppedForMs = StoppedInterruption.SCREEN_OFF_HOLD_CAP_MS - 1,
				rustedWaxForeground = true,
			),
		)
	}

	@Test
	fun `RustedWax in front keeps every other condition of the hold`() {
		assertFalse(
			holds(
				displayInteractive = true,
				stoppedForMs = 10_000,
				surfaceDisappearanceConfirmed = false,
				rustedWaxForeground = true,
			),
		)
		assertFalse(
			holds(
				displayInteractive = true,
				stoppedForMs = 10_000,
				stoppedAtMs = 240_000,
				rustedWaxForeground = true,
			),
		)
		assertFalse(
			holds(
				displayInteractive = true,
				stoppedForMs = StoppedInterruption.SCREEN_OFF_HOLD_CAP_MS,
				rustedWaxForeground = true,
			),
		)
	}

	@Test
	fun `the early-position signature is a reset to zero then a positive restore`() {
		assertEquals(12_000L, StoppedInterruption.foregroundResetCandidate(12_000, 0))
		assertEquals(null, StoppedInterruption.foregroundResetCandidate(12_000, 500))
		assertEquals(null, StoppedInterruption.foregroundResetCandidate(0, 0))
		assertEquals(null, StoppedInterruption.foregroundResetCandidate(null, 0))
		assertTrue(StoppedInterruption.confirmsForegroundSurfaceDisappearance(12_000, 12_065))
		assertFalse("the reset sample itself is not a restore", StoppedInterruption.confirmsForegroundSurfaceDisappearance(12_000, 0))
		assertFalse(StoppedInterruption.confirmsForegroundSurfaceDisappearance(12_000, 40_000))
		// The full signature cannot see it this early, which is why this one exists.
		assertEquals(null, StoppedInterruption.resetCandidate(12_000, 0))
	}

	@Test
	fun `only RustedWax in front may hold on the early-position signature`() {
		assertTrue(
			holds(
				displayInteractive = true,
				stoppedForMs = 10_000,
				surfaceDisappearanceConfirmed = false,
				stoppedAtMs = 12_065,
				rustedWaxForeground = true,
				foregroundSurfaceDisappearanceConfirmed = true,
			),
		)
		assertFalse(
			holds(
				displayInteractive = true,
				stoppedForMs = 10_000,
				surfaceDisappearanceConfirmed = false,
				stoppedAtMs = 12_065,
				foregroundSurfaceDisappearanceConfirmed = true,
			),
		)
		assertFalse(
			"a lock keeps needing the full signature",
			holds(
				displayInteractive = false,
				stoppedForMs = 10_000,
				surfaceDisappearanceConfirmed = false,
				stoppedAtMs = 12_065,
				foregroundSurfaceDisappearanceConfirmed = true,
			),
		)
	}
}
