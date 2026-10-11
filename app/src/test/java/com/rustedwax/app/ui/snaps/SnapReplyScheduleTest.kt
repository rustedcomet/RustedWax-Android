package com.rustedwax.app.ui.snaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #42 Stage 2: when the background reply check is scheduled, replaced,
 * kept or dropped, and when a finished run asks for a retry.
 *
 * The check itself is [SnapReplyCheckTest]'s; these are the decisions around
 * the platform scheduler, which a JVM test can make without one.
 */
class SnapReplyScheduleTest {

	private val spec = SnapReplySchedule.SPEC

	@Test
	fun `the job is periodic at Android's fifteen-minute floor, never more often`() {
		assertEquals(15 * 60 * 1000L, spec.periodMillis)
		assertEquals("the foreground's throttle is the period", SnapReplyCheck.MIN_INTERVAL_MILLIS, spec.periodMillis)
	}

	@Test
	fun `runs fall in the last five minutes of each period, and on-time runs are never too soon`() {
		// Android's minimum flex: a narrower window cannot be asked for.
		assertEquals(5 * 60 * 1000L, spec.flexMillis)
		// Two on-time runs are at least period - flex apart; the job accepts
		// exactly that. Found on the A36: 14m41s apart with a full-period flex.
		assertEquals(spec.periodMillis - spec.flexMillis, SnapReplySchedule.JOB_MIN_INTERVAL_MILLIS)
		assertTrue(14 * 60 * 1000L + 41_000L >= SnapReplySchedule.JOB_MIN_INTERVAL_MILLIS)
		assertTrue("still refuses a back-to-back catch-up run", 5_000L < SnapReplySchedule.JOB_MIN_INTERVAL_MILLIS)
	}

	@Test
	fun `the job needs a network and survives a reboot`() {
		assertTrue(spec.needsNetwork)
		assertTrue(spec.persisted)
	}

	@Test
	fun `backoff is conservative - longer than the period, and growing`() {
		assertTrue(spec.backoffMillis > spec.periodMillis)
		assertTrue(spec.exponentialBackoff)
	}

	@Test
	fun `signing in schedules it once, and opening the app again keeps it`() {
		assertEquals(SnapReplySchedule.Action.SCHEDULE, SnapReplySchedule.decide(signedIn = true, pending = null))
		assertEquals(
			"rescheduling an identical job would restart its period on every launch",
			SnapReplySchedule.Action.KEEP,
			SnapReplySchedule.decide(signedIn = true, pending = spec),
		)
	}

	@Test
	fun `a job lost to a force-stop or of another shape is replaced under the same id`() {
		// Force-stop and clearing data drop pending jobs; the next launch restores.
		assertEquals(SnapReplySchedule.Action.SCHEDULE, SnapReplySchedule.decide(true, null))
		listOf(
			spec.copy(persisted = false),
			spec.copy(needsNetwork = false),
			spec.copy(periodMillis = spec.periodMillis * 2),
			spec.copy(flexMillis = spec.periodMillis),
			spec.copy(backoffMillis = 30_000L),
			spec.copy(exponentialBackoff = false),
		).forEach {
			assertEquals(it.toString(), SnapReplySchedule.Action.SCHEDULE, SnapReplySchedule.decide(true, it))
		}
	}

	@Test
	fun `nobody signed in means no job`() {
		assertEquals(SnapReplySchedule.Action.CANCEL, SnapReplySchedule.decide(signedIn = false, pending = spec))
		assertEquals(SnapReplySchedule.Action.KEEP, SnapReplySchedule.decide(signedIn = false, pending = null))
	}

	@Test
	fun `only a run that reached no node, or found storage not ready, retries on the backoff`() {
		fun checked(read: Int, complete: Boolean) =
			SnapReplyCheck.Outcome.Checked("alice", read, emptyList(), emptyList(), complete)

		assertTrue("every node failed", SnapReplySchedule.retryAfter(checked(read = 0, complete = false)))
		assertTrue(SnapReplySchedule.retryAfter(SnapReplyCheck.Outcome.Skipped("storage not ready")))

		assertFalse("partial progress waits for the period", SnapReplySchedule.retryAfter(checked(3, false)))
		assertFalse(SnapReplySchedule.retryAfter(checked(10, true)))
		assertFalse("no Snaps at all is a complete run", SnapReplySchedule.retryAfter(checked(0, true)))
		listOf("no account", "too soon", "already running", "account changed", "failed: IllegalStateException")
			.forEach { assertFalse(it, SnapReplySchedule.retryAfter(SnapReplyCheck.Outcome.Skipped(it))) }
	}
}
