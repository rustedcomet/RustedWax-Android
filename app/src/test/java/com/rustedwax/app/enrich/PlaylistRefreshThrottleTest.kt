package com.rustedwax.app.enrich

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bound on re-reading a playlist after a track failed to match it.
 *
 * Two failure modes are being held apart, and both were real before v0.9.6:
 * a playlist that never refreshes cannot find a song added to it after the
 * first fetch, and a playlist that refreshes on every miss re-downloads the
 * page once per track for every autoplay track outside it.
 */
class PlaylistRefreshThrottleTest {

	private val minute = 60_000L

	@Test
	fun `a freshly fetched playlist is never re-read on the next track`() {
		val throttle = PlaylistRefreshThrottle()
		assertFalse(throttle.claim("PL1", cachedAtMillis = 0, nowMillis = 30_000))
		assertFalse(throttle.claim("PL1", cachedAtMillis = 0, nowMillis = 9 * minute))
	}

	@Test
	fun `a stale playlist may be re-read once, then not again for the interval`() {
		val throttle = PlaylistRefreshThrottle()
		val cachedAt = 0L
		assertTrue(throttle.claim("PL1", cachedAt, nowMillis = 11 * minute))
		// The next track misses seconds later. The page was just re-read; the
		// answer has not changed and must not be paid for again.
		assertFalse(throttle.claim("PL1", cachedAt, nowMillis = 11 * minute + 5_000))
		assertTrue(throttle.claim("PL1", cachedAt, nowMillis = 22 * minute))
	}

	@Test
	fun `a playlist RustedWax is not playing costs a bounded number of page reads`() {
		val throttle = PlaylistRefreshThrottle()
		var granted = 0
		// One miss per track, all evening, all of them genuinely outside the
		// latched playlist. Ask far more often than the interval allows.
		for (track in 1..200) {
			if (throttle.claim("PL1", cachedAtMillis = 0, nowMillis = track * 11 * minute)) {
				granted++
			}
		}
		assertTrue(granted == PlaylistRefreshThrottle.MAX_REFRESHES)
	}

	@Test
	fun `each playlist has its own budget`() {
		val throttle = PlaylistRefreshThrottle()
		assertTrue(throttle.claim("PL1", cachedAtMillis = 0, nowMillis = 11 * minute))
		assertTrue(throttle.claim("PL2", cachedAtMillis = 0, nowMillis = 11 * minute))
	}

	@Test
	fun `a reset restores the budget`() {
		val throttle = PlaylistRefreshThrottle(minIntervalMs = minute, maxRefreshes = 1)
		assertTrue(throttle.claim("PL1", cachedAtMillis = 0, nowMillis = 2 * minute))
		assertFalse(throttle.claim("PL1", cachedAtMillis = 0, nowMillis = 20 * minute))
		throttle.reset()
		assertTrue(throttle.claim("PL1", cachedAtMillis = 0, nowMillis = 21 * minute))
	}
}
