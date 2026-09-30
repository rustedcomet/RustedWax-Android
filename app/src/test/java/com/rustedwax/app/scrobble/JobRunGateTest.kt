package com.rustedwax.app.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The service's half of the Codex B1 findings: `jobFinished` for an execution
 * at most once, never after the platform stopped it or destroyed the service —
 * and the stop is matched by job id, because the platform may hand `onStopJob`
 * a different parameters object than `onStartJob` received.
 */
class JobRunGateTest {

	/**
	 * Stands in for `JobParameters`: identity equality only, like the platform
	 * class, so a test that passes proves nothing leans on value equality.
	 */
	private class Params(val jobId: Int)

	private val gate = JobRunGate<Params>()
	private val jobId = 0x5157_0001

	private fun begin(params: Params) = gate.begin(params.jobId, params)

	@Test
	fun `a job that ends normally is finished exactly once, with its start parameters`() {
		val start = Params(jobId)
		val claim = begin(start)
		assertTrue(claim.finish())
		assertSame("jobFinished would be sent for the wrong execution", start, claim.params)
		assertFalse("finished twice", claim.finish())
	}

	@Test
	fun `a stop carrying a different parameters object for the same id revokes the claim`() {
		val atStart = Params(jobId)
		val atStop = Params(jobId)
		assertNotEquals("the stand-in must not have value equality", atStart, atStop)
		val claim = begin(atStart)

		assertTrue("onStopJob(B) missed the claim onStartJob(A) created", gate.stop(atStop.jobId))
		assertFalse("jobFinished after onStopJob", claim.finish())
	}

	@Test
	fun `a stop for a different job id cannot revoke another claim`() {
		val claim = begin(Params(jobId))
		assertFalse(gate.stop(jobId + 1))
		assertTrue("an unrelated stop revoked this job", claim.finish())
	}

	@Test
	fun `a new start for the same id revokes the claim it replaces`() {
		val old = begin(Params(jobId))
		val next = begin(Params(jobId))

		assertFalse("a replaced execution can still be finished", old.finish())
		assertTrue(next.finish())
	}

	@Test
	fun `a stop after a replacement revokes only the current execution`() {
		val old = begin(Params(jobId))
		val next = begin(Params(jobId))
		assertTrue(gate.stop(jobId))
		assertFalse(old.finish())
		assertFalse(next.finish())
	}

	@Test
	fun `completion after stop never finishes`() {
		val claim = begin(Params(jobId))
		gate.stop(jobId)
		repeat(3) { assertFalse(claim.finish()) }
	}

	@Test
	fun `stopping a job that already finished reports nothing to give up`() {
		val claim = begin(Params(jobId))
		assertTrue(claim.finish())
		assertFalse(gate.stop(jobId))
	}

	@Test
	fun `destroy finishes nothing it had started and is idempotent`() {
		val claim = begin(Params(jobId))
		gate.closeAll()
		gate.closeAll()
		assertFalse(claim.finish())
		assertFalse(gate.stop(jobId))
	}

	@Test
	fun `a successor on the same fixed id starts and finishes normally after a stop`() {
		val stopped = begin(Params(jobId))
		gate.stop(Params(jobId).jobId)
		val successor = begin(Params(jobId))

		assertFalse(stopped.finish())
		assertTrue("the successor execution could not be finished", successor.finish())
		assertFalse(successor.finish())
	}

	@Test
	fun `a stopped execution finishing late cannot unregister its successor`() {
		val stopped = begin(Params(jobId))
		gate.stop(jobId)
		val successor = begin(Params(jobId))
		stopped.finish()
		// The successor's claim is still registered: a stop now reaches it.
		assertTrue(gate.stop(jobId))
		assertFalse(successor.finish())
	}

	@Test
	fun `stop racing completion finishes at most once and never after a won stop`() {
		val pool = Executors.newFixedThreadPool(2)
		try {
			repeat(2_000) { i ->
				val claim = begin(Params(jobId))
				val stopWith = Params(jobId)
				val go = CountDownLatch(1)
				val finished = AtomicInteger()
				val stopped = AtomicInteger()
				val done = CountDownLatch(2)
				pool.execute { go.await(); if (claim.finish()) finished.incrementAndGet(); done.countDown() }
				pool.execute { go.await(); if (gate.stop(stopWith.jobId)) stopped.incrementAndGet(); done.countDown() }
				go.countDown()
				assertTrue(done.await(5, TimeUnit.SECONDS))
				assertEquals("round $i: exactly one of finish/stop must win", 1, finished.get() + stopped.get())
			}
		} finally {
			pool.shutdownNow()
		}
	}
}
