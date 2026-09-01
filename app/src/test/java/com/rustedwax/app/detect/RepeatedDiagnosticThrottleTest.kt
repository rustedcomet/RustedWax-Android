package com.rustedwax.app.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatedDiagnosticThrottleTest {

	@Test
	fun `identical diagnostics are bounded while changed safety state emits immediately`() {
		val throttle = RepeatedDiagnosticThrottle(30_000)
		assertTrue(throttle.shouldEmit("ordinary player has no Shorts root", 1_000))
		assertFalse(throttle.shouldEmit("ordinary player has no Shorts root", 1_100))
		assertFalse(throttle.shouldEmit("ordinary player has no Shorts root", 30_999))
		assertTrue(throttle.shouldEmit("foreground proof grace expired", 2_000))
		assertTrue(throttle.shouldEmit("ordinary player has no Shorts root", 2_100))
		assertTrue(throttle.shouldEmit("ordinary player has no Shorts root", 32_100))
	}

	@Test
	fun `clock reset and explicit reset never suppress the next diagnostic`() {
		val throttle = RepeatedDiagnosticThrottle(30_000)
		assertTrue(throttle.shouldEmit("missing", 50_000))
		assertTrue(throttle.shouldEmit("missing", 1_000))
		throttle.reset()
		assertTrue(throttle.shouldEmit("missing", 1_100))
	}

	@Test
	fun `alternating capture triggers and node counts share one ordinary-player key`() {
		val callback = "accessibility callback: expected exactly one visible Shorts player root; " +
			"found 0; captured 51 nodes; structural ids=[reel_time_bar]"
		val refresh = "bounded foreground refresh: expected exactly one visible Shorts player root; " +
			"found 0; captured 72 nodes; structural ids=[reel_time_bar]"
		assertTrue(NativeShortDiagnosticKey.of(callback) == NativeShortDiagnosticKey.of(refresh))

		val throttle = RepeatedDiagnosticThrottle(30_000)
		assertTrue(throttle.shouldEmit(NativeShortDiagnosticKey.of(callback), 1_000))
		assertFalse(throttle.shouldEmit(NativeShortDiagnosticKey.of(refresh), 2_000))
		val frozen = "$refresh; progress frozen during bounded refresh grace"
		assertTrue(NativeShortDiagnosticKey.of(frozen) != NativeShortDiagnosticKey.of(refresh))
		assertTrue(throttle.shouldEmit(NativeShortDiagnosticKey.of(frozen), 2_100))
	}
}
