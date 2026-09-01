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
