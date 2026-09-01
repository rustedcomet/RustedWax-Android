package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityGrantHealthTest {

	@Test
	fun `a live grant is live whether or not it was seen before`() {
		assertEquals(GrantHealth.LIVE, AccessibilityGrantHealth.classify(live = true, everGranted = false))
		assertEquals(GrantHealth.LIVE, AccessibilityGrantHealth.classify(live = true, everGranted = true))
	}

	@Test
	fun `never granted is not reported as a failure`() {
		// A fresh install has nothing wrong with it, and saying "stopped on its
		// own" there would train the owner to ignore the message that matters.
		assertEquals(
			GrantHealth.NEVER_GRANTED,
			AccessibilityGrantHealth.classify(live = false, everGranted = false),
		)
	}

	@Test
	fun `a grant that was once live and is now off is a drop, not a choice`() {
		// The 2026-08-05 case: Android disables a crashed accessibility service,
		// which is byte-identical to the user revoking it. The app must not
		// claim the user did it, and must not stay silent either.
		assertEquals(
			GrantHealth.DROPPED,
			AccessibilityGrantHealth.classify(
				live = false,
				everGranted = true,
				notLiveForMillis = AccessibilityGrantHealth.SETTLE_MS,
			),
		)
	}

	@Test
	fun `the post-install window is not reported as a crash`() {
		// Measured immediately after `adb install -r` on 2026-08-05:
		// accessibility_enabled read 0 while both services were still named in
		// enabled_accessibility_services, and settled to 1 seconds later with
		// both reconnecting. Warning here would fire on every install — the
		// mirror image of the bug this class exists to catch, and the fastest
		// way to train the owner to ignore the warning that matters.
		assertEquals(
			GrantHealth.SETTLING,
			AccessibilityGrantHealth.classify(
				live = false,
				everGranted = true,
				notLiveForMillis = 3_000,
			),
		)
	}

	@Test
	fun `settling becomes a drop once it outlasts any install`() {
		val justBefore = AccessibilityGrantHealth.classify(
			live = false,
			everGranted = true,
			notLiveForMillis = AccessibilityGrantHealth.SETTLE_MS - 1,
		)
		val justAfter = AccessibilityGrantHealth.classify(
			live = false,
			everGranted = true,
			notLiveForMillis = AccessibilityGrantHealth.SETTLE_MS + 1,
		)
		assertEquals(GrantHealth.SETTLING, justBefore)
		assertEquals(GrantHealth.DROPPED, justAfter)
	}

	@Test
	fun `a never-granted service never settles into a false crash report`() {
		assertEquals(
			GrantHealth.NEVER_GRANTED,
			AccessibilityGrantHealth.classify(
				live = false,
				everGranted = false,
				notLiveForMillis = Long.MAX_VALUE,
			),
		)
	}
}
