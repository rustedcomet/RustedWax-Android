package com.rustedwax.app.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signal that used to be missing.
 *
 * A scrobble finalized in airplane mode sat due in the queue until the user
 * opened the app: `flushQueue()` had three callers and all three were user or
 * system events. These cases are about the fourth — connectivity coming back —
 * and about it staying a *trigger* rather than becoming a retry policy of its
 * own.
 */
class ConnectivityRetryTriggerTest {

	private var depth = 0
	private var now = 0L
	private var drains = 0

	private fun trigger(quietPeriodMs: Long = 30_000L) = ConnectivityRetryTrigger(
		queueDepth = { depth },
		flush = { drains++ },
		nowMs = { now },
		quietPeriodMs = quietPeriodMs,
	)

	@Test
	fun `usable connectivity drains a queue that is waiting, with no UI event`() {
		depth = 1
		val trigger = trigger()

		assertTrue("connectivity returning did not drain the queue", trigger.onUsableNetwork())
		assertEquals(1, drains)
	}

	@Test
	fun `an empty queue costs nothing when the network returns`() {
		depth = 0
		val trigger = trigger()

		assertFalse(trigger.onUsableNetwork())
		assertEquals(0, drains)
	}

	@Test
	fun `one reconnect is one drain however many callbacks it delivers`() {
		depth = 2
		val trigger = trigger()

		// Wi-Fi and cellular validating within the same second, plus a flap.
		assertTrue(trigger.onUsableNetwork())
		now += 200
		assertFalse(trigger.onUsableNetwork())
		now += 1_000
		assertFalse(trigger.onUsableNetwork())

		assertEquals("a burst of callbacks became a burst of retries", 1, drains)
	}

	@Test
	fun `a later reconnect drains again once the quiet period has passed`() {
		depth = 1
		val trigger = trigger(quietPeriodMs = 30_000L)

		assertTrue(trigger.onUsableNetwork())
		now += 29_999
		assertFalse(trigger.onUsableNetwork())
		now += 1
		assertTrue(trigger.onUsableNetwork())

		assertEquals(2, drains)
	}

	/**
	 * The trigger decides *when to ask*, never *what is owed*.
	 *
	 * Nothing here inspects an entry, its attempt count or its backoff deadline:
	 * it calls the same `flushQueue()` the retry button calls and
	 * `BroadcastQueue.due()` answers. This case pins that seam — a queue that
	 * has nothing due drains nothing even though the trigger fired.
	 */
	@Test
	fun `the trigger asks the queue and does not decide for it`() {
		depth = 1
		var dueEntries = 0
		val trigger = ConnectivityRetryTrigger(
			queueDepth = { depth },
			flush = { drains += dueEntries },
			nowMs = { now },
		)

		assertTrue(trigger.onUsableNetwork())
		assertEquals("an entry still backing off was retried anyway", 0, drains)

		now += 60_000
		dueEntries = 1
		assertTrue(trigger.onUsableNetwork())
		assertEquals(1, drains)
	}
}
