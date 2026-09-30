package com.rustedwax.app.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner, the one alarm and the one job, without an engine.
 *
 * `QueueWakeupReplayTest` runs the A36 sequence through the real engine; these
 * pin the rules that sequence depends on: what counts as a deadline worth
 * waking for, what an alarm delivery may schedule, and that one wake-up is
 * never two.
 */
class QueueWakeupTest {

	private class CountingAlarm : QueueWakeup.Alarm {
		val arms = mutableListOf<Long>()
		var disarms = 0
		var accepts = true
		override fun arm(delayMs: Long): Boolean {
			if (accepts) arms += delayMs
			return accepts
		}

		override fun disarm() {
			disarms++
		}
	}

	private class CountingJobs : QueueWakeup.Jobs {
		var scheduled = false
		var expeditedWorks = true
		var ordinaryWorks = true
		var expedited = 0
		var ordinary = 0
		var cancels = 0

		override fun isScheduled() = scheduled
		override fun scheduleExpedited(): Boolean {
			if (!expeditedWorks) return false
			expedited++
			scheduled = true
			return true
		}

		override fun scheduleOrdinary(): Boolean {
			if (!ordinaryWorks) return false
			ordinary++
			scheduled = true
			return true
		}

		override fun cancel() {
			cancels++
			scheduled = false
		}
	}

	private val alarm = CountingAlarm()
	private val jobs = CountingJobs()
	private val wakeup = QueueWakeup(alarm, jobs)

	private fun reconsider(now: Long, next: Long?, due: Boolean = false) =
		wakeup.reconsider(now, { next }) { due }

	private fun entry(
		username: String = "listener",
		nextAttemptAtMs: Long,
		state: BroadcastQueue.State = BroadcastQueue.State.QUEUED,
	) = BroadcastQueue.Entry(
		id = nextAttemptAtMs,
		operationId = "op-$nextAttemptAtMs-$username",
		username = username,
		json = "{}",
		label = "Synthetic",
		percentPlayed = 100,
		videoId = null,
		attempts = 1,
		nextAttemptAtMs = nextAttemptAtMs,
		lastError = "offline",
		state = state,
	)

	// ---- planner ---------------------------------------------------------------

	@Test
	fun `the earliest future deadline of the current account is the one armed for`() {
		val pending = listOf(
			entry(nextAttemptAtMs = 5_000),
			entry(nextAttemptAtMs = 2_000),
			entry(username = "LISTENER", nextAttemptAtMs = 3_000),
		)
		assertEquals(2_000L, QueueWakeup.nextDueAtMs(pending, "listener", nowMs = 1_000))
	}

	@Test
	fun `nothing is armed for an empty queue, a settled row, or no account`() {
		assertNull(QueueWakeup.nextDueAtMs(emptyList(), "listener", 0))
		assertNull(
			QueueWakeup.nextDueAtMs(
				listOf(entry(nextAttemptAtMs = 9_000, state = BroadcastQueue.State.SETTLED)),
				"listener",
				0,
			),
		)
		assertNull(QueueWakeup.nextDueAtMs(listOf(entry(nextAttemptAtMs = 9_000)), null, 0))
	}

	@Test
	fun `another account's rows are never a reason to wake or to run`() {
		val foreign = listOf(entry(username = "someone-else", nextAttemptAtMs = 9_000))
		assertNull(QueueWakeup.nextDueAtMs(foreign, "listener", 0))
		assertFalse(QueueWakeup.hasDueEntry(foreign, "listener", 10_000))
	}

	@Test
	fun `a row already due is left to the other triggers, never armed for now`() {
		val pending = listOf(
			entry(nextAttemptAtMs = 1_000, state = BroadcastQueue.State.IN_FLIGHT),
			entry(nextAttemptAtMs = 0),
		)
		assertNull(QueueWakeup.nextDueAtMs(pending, "listener", nowMs = 1_000))
		assertTrue(QueueWakeup.hasDueEntry(pending, "listener", nowMs = 1_000))
	}

	@Test
	fun `an in-flight row backing off is armed for — reconciliation, not re-signing, runs then`() {
		val pending = listOf(entry(nextAttemptAtMs = 61_000, state = BroadcastQueue.State.IN_FLIGHT))
		assertEquals(61_000L, QueueWakeup.nextDueAtMs(pending, "listener", nowMs = 1_000))
	}

	// ---- alarm arming ------------------------------------------------------------

	@Test
	fun `one deadline is one arm however often it is reconsidered`() {
		reconsider(1_000, 61_000)
		reconsider(2_000, 61_000)
		reconsider(30_000, 61_000)

		assertEquals(listOf(60_000L), alarm.arms)
		assertEquals(0, alarm.disarms)
	}

	@Test
	fun `a new deadline replaces the old one, and an empty queue cancels once`() {
		reconsider(0, 60_000)
		reconsider(60_000, 180_000)
		reconsider(180_000, null)
		reconsider(181_000, null)

		assertEquals(listOf(60_000L, 120_000L), alarm.arms)
		assertEquals(1, alarm.disarms)
	}

	@Test
	fun `a fresh process disarms and cancels a leftover run, since both outlive the process`() {
		reconsider(0, null)
		assertEquals("state unknown after restart: a leftover alarm must be cancelled", 1, alarm.disarms)
		assertEquals("a leftover job must be cancelled when nothing is due", 1, jobs.cancels)
	}

	@Test
	fun `a refused alarm is never cached as armed`() {
		alarm.accepts = false
		reconsider(0, 60_000)
		assertEquals(emptyList<Long>(), alarm.arms)

		// The next queue change tries again rather than trusting a false "armed".
		alarm.accepts = true
		reconsider(1_000, 60_000)
		assertEquals(listOf(59_000L), alarm.arms)
	}

	// ---- alarm delivery ----------------------------------------------------------

	@Test
	fun `an alarm with something due schedules exactly one expedited run`() {
		reconsider(0, 60_000)
		val delivery = wakeup.alarmFired(60_000, dueNow = { true }, nextDueAtMs = { null })

		assertEquals(QueueWakeup.Delivery.EXPEDITED, delivery)
		assertEquals(1, jobs.expedited)
		assertEquals(0, jobs.ordinary)
	}

	@Test
	fun `a duplicate delivery changes nothing`() {
		wakeup.alarmFired(60_000, dueNow = { true }, nextDueAtMs = { null })
		val second = wakeup.alarmFired(60_100, dueNow = { true }, nextDueAtMs = { null })

		assertEquals(QueueWakeup.Delivery.ALREADY_SCHEDULED, second)
		assertEquals(1, jobs.expedited)
	}

	@Test
	fun `a refused expedited run falls back to an ordinary one`() {
		jobs.expeditedWorks = false
		val delivery = wakeup.alarmFired(60_000, dueNow = { true }, nextDueAtMs = { null })

		assertEquals(QueueWakeup.Delivery.ORDINARY_FALLBACK, delivery)
		assertEquals(1, jobs.ordinary)
		assertTrue(jobs.scheduled)
	}

	@Test
	fun `when nothing can be scheduled it says so and claims nothing`() {
		jobs.expeditedWorks = false
		jobs.ordinaryWorks = false
		val delivery = wakeup.alarmFired(60_000, dueNow = { true }, nextDueAtMs = { null })

		assertEquals(QueueWakeup.Delivery.FAILED, delivery)
		assertFalse(jobs.scheduled)
		assertEquals("a failed schedule re-armed an alarm into a loop", emptyList<Long>(), alarm.arms)
	}

	@Test
	fun `a stale alarm schedules nothing and re-arms for the real deadline`() {
		reconsider(0, 60_000)
		// The entry was sent or backed off again before the alarm landed.
		val delivery = wakeup.alarmFired(60_000, dueNow = { false }, nextDueAtMs = { 180_000 })

		assertEquals(QueueWakeup.Delivery.NOTHING_DUE, delivery)
		assertEquals(0, jobs.expedited)
		assertEquals(listOf(60_000L, 120_000L), alarm.arms)
	}

	@Test
	fun `an alarm during a running drain schedules nothing — the run re-arms at its end`() {
		wakeup.alarmFired(60_000, dueNow = { true }, nextDueAtMs = { null })
		val run = wakeup.runStarted()
		val during = wakeup.alarmFired(60_500, dueNow = { true }, nextDueAtMs = { null })
		reconsider(60_600, 180_000)

		assertEquals(QueueWakeup.Delivery.RUNNING, during)
		assertEquals(1, jobs.expedited)
		assertEquals("the running job was cancelled from inside its own drain", 0, jobs.cancels)
		assertEquals(emptyList<Long>(), alarm.arms)

		assertTrue(wakeup.runFinished(run, 61_000, nextDueAtMs = { 181_000 }, dueNow = { false }))
		assertEquals(listOf(120_000L), alarm.arms)
	}

	// ---- run lifecycle -------------------------------------------------------------

	@Test
	fun `a run is finished exactly once, however many times its end is reported`() {
		val run = wakeup.runStarted()
		assertTrue(wakeup.runFinished(run, 61_000, nextDueAtMs = { 181_000 }, dueNow = { false }))
		assertFalse(
			"a second report re-armed",
			wakeup.runFinished(run, 61_500, nextDueAtMs = { 300_000 }, dueNow = { false }),
		)
		assertEquals(listOf(120_000L), alarm.arms)
	}

	@Test
	fun `a stopped run ending late does not clear a newer run`() {
		// The platform stopped job 1; its drain is still going when job 2 starts.
		val first = wakeup.runStarted()
		val second = wakeup.runStarted()

		wakeup.runFinished(first, 61_000, nextDueAtMs = { 181_000 }, dueNow = { false })
		assertEquals("re-armed while the second run was still going", emptyList<Long>(), alarm.arms)
		assertEquals(
			QueueWakeup.Delivery.RUNNING,
			wakeup.alarmFired(61_100, dueNow = { true }, nextDueAtMs = { null }),
		)

		wakeup.runFinished(second, 62_000, nextDueAtMs = { 182_000 }, dueNow = { false })
		assertEquals(listOf(120_000L), alarm.arms)
		assertEquals(
			"running was left set after both runs ended",
			QueueWakeup.Delivery.EXPEDITED,
			wakeup.alarmFired(182_000, dueNow = { true }, nextDueAtMs = { null }),
		)
	}

	@Test
	fun `an unknown token changes nothing`() {
		assertFalse(wakeup.runFinished(42, 0, nextDueAtMs = { 60_000 }, dueNow = { false }))
		assertEquals(emptyList<Long>(), alarm.arms)
	}

	// ---- the scheduled run ---------------------------------------------------------

	@Test
	fun `a scheduled run survives a queue change while something is still due`() {
		wakeup.alarmFired(60_000, dueNow = { true }, nextDueAtMs = { null })
		// A new listen's first send fails offline: it is due now, and so is the
		// old one the scheduled run is waiting on a network to retry.
		reconsider(61_000, null, due = true)

		assertEquals("a run still owed was cancelled", 0, jobs.cancels)
		assertTrue(jobs.scheduled)
	}

	@Test
	fun `a scheduled run is cancelled once nothing is due`() {
		wakeup.alarmFired(60_000, dueNow = { true }, nextDueAtMs = { null })
		// The app opened and sent it before the job ran.
		reconsider(61_000, null, due = false)

		assertEquals(1, jobs.cancels)
		assertFalse(jobs.scheduled)
	}
}
