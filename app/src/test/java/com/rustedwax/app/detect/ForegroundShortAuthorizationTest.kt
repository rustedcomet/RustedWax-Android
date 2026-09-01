package com.rustedwax.app.detect

import com.rustedwax.core.AutomaticWriteAuthorization
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundShortAuthorizationTest {
	@Test
	fun `a resumed Short keeps its original authorization and a new Short gets the current one`() {
		var current = AutomaticWriteAuthorization(4, enabledAtStart = false)
		var token = 100L
		val tracker = ForegroundShortTracker(
			automaticWriteAuthorization = { current },
			allocateTrackToken = { token++ },
		)
		fun observation(title: String, position: Long, at: Long) =
			ForegroundShortTracker.OrganicObservation(
				title = title,
				ownerHandle = "@owner",
				currentSeconds = position,
				totalSeconds = 100,
				observedAtMillis = at,
				sourceEpoch = 1,
			)

		val first = tracker.observe(observation("First", 1, 1_000)).active!!
		current = AutomaticWriteAuthorization(6, enabledAtStart = true)
		val same = tracker.observe(observation("First", 2, 2_000)).active!!
		val changed = tracker.observe(observation("Second", 1, 3_000))

		assertEquals(first.automaticWriteAuthorization, same.automaticWriteAuthorization)
		assertEquals(AutomaticWriteAuthorization(4, false), changed.finalized.single().automaticWriteAuthorization)
		assertEquals(current, changed.active!!.automaticWriteAuthorization)
	}
}
