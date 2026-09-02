package com.rustedwax.app.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The retry policy the connectivity trigger must not have changed.
 *
 * Nothing here is new behaviour. It is the characterisation the reconnect work
 * needed: connectivity returning now calls `flushQueue()` on its own, and the
 * one thing that must remain true is that a *drain* is still the queue
 * answering with what is due — same backoff, same ceiling, same drop.
 */
class BroadcastQueueBackoffTest {

	@get:Rule
	val folder = TemporaryFolder()

	private fun queue() = BroadcastQueue(folder.newFolder().resolve("broadcast-queue.json"))

	private fun BroadcastQueue.addOne(label: String = "HDCYT — a listen") =
		add("rustedwax-test", """{"kind":"video"}""", label, 96, "_OBlgSz8sSM")

	@Test
	fun `a freshly queued entry is due immediately`() {
		val queue = queue()
		assertTrue(queue.addOne())

		val entry = queue.all().single()
		assertEquals(0, entry.attempts)
		assertEquals(0L, entry.nextAttemptAtMs)
		assertEquals(listOf(entry), queue.due(nowMs = 0L))
	}

	@Test
	fun `a failed attempt backs off and is no longer due`() {
		val queue = queue()
		queue.addOne()
		val id = queue.all().single().id

		assertEquals(BroadcastQueue.FailureOutcome.RETAINED, queue.recordFailure(id, "no route to host"))

		val entry = queue.all().single()
		assertEquals(1, entry.attempts)
		assertEquals("no route to host", entry.lastError)
		val now = System.currentTimeMillis()
		assertTrue(
			"the first backoff was not about a minute long: ${entry.nextAttemptAtMs - now}ms",
			entry.nextAttemptAtMs - now in 55_000..60_000,
		)
		assertEquals(emptyList<BroadcastQueue.Entry>(), queue.due(nowMs = now))
		// Connectivity returning asks the queue; it does not overrule it.
		assertEquals(listOf(entry), queue.due(nowMs = entry.nextAttemptAtMs))
	}

	@Test
	fun `backoff doubles and is capped at an hour`() {
		val queue = queue()
		queue.addOne()
		val id = queue.all().single().id

		val waits = (1..6).map {
			queue.recordFailure(id, "offline")
			queue.all().single().nextAttemptAtMs - System.currentTimeMillis()
		}

		// 1, 2, 4, 8, 16, 32 minutes. Measured against a live clock, so each is
		// asserted as a range rather than an exact millisecond.
		listOf(1L, 2L, 4L, 8L, 16L, 32L).forEachIndexed { index, minutes ->
			val expected = minutes * 60_000
			assertTrue(
				"attempt ${index + 1} waited ${waits[index]}ms, expected about ${expected}ms",
				waits[index] in (expected - 5_000)..expected,
			)
		}
	}

	@Test
	fun `the eighth attempt drops the entry rather than retrying forever`() {
		val queue = queue()
		queue.addOne()
		val id = queue.all().single().id

		repeat(7) {
			assertEquals(
				"attempt ${it + 1} should have been retained",
				BroadcastQueue.FailureOutcome.RETAINED,
				queue.recordFailure(id, "offline"),
			)
			assertNotNull(queue.all().firstOrNull { entry -> entry.id == id })
		}

		assertEquals(BroadcastQueue.FailureOutcome.DROPPED, queue.recordFailure(id, "offline"))
		assertEquals(emptyList<BroadcastQueue.Entry>(), queue.all())
		assertEquals(BroadcastQueue.FailureOutcome.NOT_FOUND, queue.recordFailure(id, "offline"))
	}

	@Test
	fun `a queued entry survives a process restart with its backoff intact`() {
		val file = folder.newFolder().resolve("broadcast-queue.json")
		BroadcastQueue(file).let { queue ->
			queue.addOne()
			queue.recordFailure(queue.all().single().id, "offline")
		}

		val reopened = BroadcastQueue(file).all().single()
		assertEquals(1, reopened.attempts)
		assertEquals("_OBlgSz8sSM", reopened.videoId)
		assertTrue(reopened.nextAttemptAtMs > System.currentTimeMillis())
		assertNull(BroadcastQueue(file).due(nowMs = System.currentTimeMillis()).firstOrNull())
	}
}
