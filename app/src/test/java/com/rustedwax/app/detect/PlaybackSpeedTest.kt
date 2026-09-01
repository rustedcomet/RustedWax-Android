package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rate the play-time accumulator scores a window at.
 *
 * "Played" has to mean *content consumed*, because the 60% rule compares it
 * against `duration`. Measuring wall-clock seconds instead under-reports every
 * sped-up listen and loses some outright: a 2026-07-29 field session watched a
 * 76 s trailer at 1.25× to position 59.9 s — 79% of the video — and it went
 * on-chain as 67%. At 2× the same arithmetic puts a fully-watched video at 50%,
 * below the threshold, and it never scrobbles.
 *
 * Only the arithmetic is covered here; the accumulator itself lives on an inner
 * class holding an Android `MediaController`.
 */
class PlaybackSpeedTest {

	private fun scale(elapsedMs: Long, reported: Float?): Long =
		(elapsedMs * SessionProbe.speedFactor(reported)).toLong()

	@Test
	fun `normal speed is unchanged`() {
		assertEquals(1.0, SessionProbe.speedFactor(1.0f), 0.0)
		assertEquals(50_000L, scale(50_000, 1.0f))
	}

	/** The field case: 50 s of wall-clock at 1.25× is 62.5 s of content. */
	@Test
	fun `faster playback counts more content than elapsed time`() {
		assertEquals(62_500L, scale(50_000, 1.25f))
	}

	/**
	 * The one that changes an outcome rather than a number. A 60 s video watched
	 * fully at 2× takes 30 s of wall-clock: 30/60 is 50% and fails the 60% gate,
	 * while 60/60 is the 100% that actually happened.
	 */
	@Test
	fun `a video watched fully at double speed reaches a hundred percent`() {
		val durationMs = 60_000L
		val wallClock = 30_000L
		assertEquals(0.5, wallClock.toDouble() / durationMs, 0.001)
		assertEquals(1.0, scale(wallClock, 2.0f).toDouble() / durationMs, 0.001)
	}

	/** Slower playback is content too — half speed for 60 s is 30 s of video. */
	@Test
	fun `slower playback counts less content`() {
		assertEquals(30_000L, scale(60_000, 0.5f))
	}

	/**
	 * Paused or unreported must not erase time. `playingSince` already decides
	 * whether a window counts; a `speed=0.0` sample landing mid-window would
	 * otherwise throw away play time that really happened. The field log had 36
	 * such samples.
	 */
	@Test
	fun `zero and missing speeds fall back to normal`() {
		assertEquals(1.0, SessionProbe.speedFactor(0.0f), 0.0)
		assertEquals(1.0, SessionProbe.speedFactor(null), 0.0)
		assertEquals(1.0, SessionProbe.speedFactor(-2.0f), 0.0)
		assertEquals(10_000L, scale(10_000, 0.0f))
	}

	/** One absurd sample must not turn ten seconds into a full listen. */
	@Test
	fun `an implausibly high speed is clamped`() {
		assertEquals(SessionProbe.MAX_PLAYBACK_SPEED, SessionProbe.speedFactor(500f), 0.0)
		assertEquals(SessionProbe.MAX_PLAYBACK_SPEED, SessionProbe.speedFactor(Float.MAX_VALUE), 0.0)
		assertEquals(40_000L, scale(10_000, 500f))
	}

	/**
	 * A non-finite reading is garbage, not "very fast", so it falls back to
	 * normal rather than to the ceiling. Clamping NaN or infinity to 4× would
	 * quadruple a track's play time on the strength of a nonsense sample.
	 */
	@Test
	fun `a non-finite speed falls back to normal, not to the ceiling`() {
		assertEquals(1.0, SessionProbe.speedFactor(Float.NaN), 0.0)
		assertEquals(1.0, SessionProbe.speedFactor(Float.POSITIVE_INFINITY), 0.0)
		assertEquals(1.0, SessionProbe.speedFactor(Float.NEGATIVE_INFINITY), 0.0)
	}
}
