package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.BroadcastQueue
import com.rustedwax.app.scrobble.ConnectivityRetryTrigger
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.hive.HiveRpc
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A queued listen reaches the chain exactly once, whatever storage does next.
 *
 * The queue's own removal is a file rewrite and it can fail. When it did, the
 * entry stayed in the file with the `nextAttemptAtMs = 0` an offline-queued
 * listen carries — due immediately — and the engine recorded the words "do not
 * retry" in a History row. Nothing enforced them. The next drain, from any of the
 * four triggers, would sign the same listen again against a fresh chain head, so
 * not even the transaction id would collide with the first.
 *
 * These scenarios run the real engine, the real dispatch path and the real
 * `broadcastLock`. They count calls at the broadcaster seam, because that is the
 * boundary an irreversible write crosses.
 */
class QueuedSendDurabilityReplayTest : ReplayScenarioTest() {

	private val videoId = "_OBlgSz8sSM"
	private val title = "Charlie bit my finger - again !"
	private val channel = "HDCYT"

	private fun facts() = VideoFacts(
		videoId = videoId,
		title = title,
		author = channel,
		lengthSeconds = 56,
		category = "Entertainment",
		watchPageResolved = true,
		isUnlisted = false,
	)

	private fun watching() = listOf(
		PlaybackEvent.NotificationObserved(host = "youtube.com", title = title),
		PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = videoId),
		PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 56_000),
		PlaybackEvent.PlaybackStateChanged(playing = true),
	)

	/** Play past threshold with no node reachable, so the listen is queued. */
	private fun queueOneListenOffline(harness: ReplayHarness) {
		harness.env.facts.put(facts())
		harness.env.broadcaster.willReturn(HiveRpc.BroadcastResult.NetworkFailure("no route to host"))
		harness.feed(watching() + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized())
		assertEquals("the listen was not queued", 1, harness.queuedForRetry)
		assertEquals("the offline attempt did not reach the broadcaster", 1, harness.env.broadcaster.sent.size)
	}

	/** Every production trigger shape ends in the same call. */
	private fun reconnect(harness: ReplayHarness) {
		ConnectivityRetryTrigger(
			queueDepth = { FinalizationRuntime.queueSize.value },
			flush = { FinalizationRuntime.flushQueue() },
			nowMs = { harness.env.clock.nowMillis() },
		).onUsableNetwork()
	}

	// ---- accepted, then the queue cannot be rewritten -------------------------

	@Test
	fun `a sent scrobble whose removal fails is never broadcast a second time`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		queueOneListenOffline(harness)

		// The node takes it; the file that would retire the entry cannot be written.
		harness.env.broadcaster.defaultResult =
			HiveRpc.BroadcastResult.Success("tx", "node", HiveRpc.BroadcastResult.Evidence.BLOCK)
		harness.env.retryQueue.removalWorks = false

		harness.env.clock.advance(60_000)
		reconnect(harness)

		assertEquals("the drain did not send", 2, harness.env.broadcaster.sent.size)
		// The entry is still in the file — nothing could rewrite it — and it is no
		// longer owed. That distinction is the fix.
		assertEquals(1, harness.env.retryQueue.all.size)
		assertEquals(emptyList<BroadcastQueue.Entry>(), harness.env.retryQueue.due())
		assertEquals(0, harness.env.retryQueue.size())

		// Every later trigger: reconnect, the activity's onStart, the manual
		// button, a listener reconnect. All four are this one call.
		harness.env.clock.advance(3_600_000)
		reconnect(harness)
		FinalizationRuntime.flushQueue()
		FinalizationRuntime.flushQueue()

		assertEquals(
			"the accepted listen was broadcast again after a failed removal",
			2,
			harness.env.broadcaster.sent.size,
		)
		assertEquals(1, harness.transactionIds.size)
	}

	@Test
	fun `an accepted-unconfirmed scrobble whose removal fails is never re-broadcast`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		queueOneListenOffline(harness)

		// The accepting node has it but cannot confirm inclusion. Retrying would
		// build a different transaction and can permanently duplicate the listen.
		harness.env.broadcaster.defaultResult =
			HiveRpc.BroadcastResult.AcceptedUnconfirmed("tx-accepted", "node", "confirmation unavailable")
		harness.env.retryQueue.removalWorks = false

		harness.env.clock.advance(60_000)
		reconnect(harness)
		assertEquals(2, harness.env.broadcaster.sent.size)

		harness.env.clock.advance(3_600_000)
		reconnect(harness)
		FinalizationRuntime.flushQueue()

		assertEquals(
			"an accepted-unconfirmed listen was broadcast again",
			2,
			harness.env.broadcaster.sent.size,
		)
		assertEquals(emptyList<BroadcastQueue.Entry>(), harness.env.retryQueue.due())
	}

	@Test
	fun `a durably recorded acceptance survives a process restart`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		queueOneListenOffline(harness)
		harness.env.broadcaster.defaultResult =
			HiveRpc.BroadcastResult.Success("tx", "node", HiveRpc.BroadcastResult.Evidence.BLOCK)
		harness.env.retryQueue.removalWorks = false

		harness.env.clock.advance(60_000)
		reconnect(harness)
		assertEquals(2, harness.env.broadcaster.sent.size)

		// A restart keeps the queue file, including SETTLED state.
		harness.feed(PlaybackEvent.ProcessRestarted)
		FinalizationRuntime.flushQueue()

		assertEquals(
			"a restart resurrected a send the disk already knew about",
			2,
			harness.env.broadcaster.sent.size,
		)
	}

	@Test
	fun `pre-send in-flight persistence failure calls the broadcaster zero times`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts())
		harness.env.retryQueue.preparationWorks = false

		harness.feed(watching() + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized())

		assertEquals("broadcast crossed a failed write-ahead boundary", 0, harness.env.broadcaster.sent.size)
		assertEquals(1, harness.env.retryQueue.all.size)
		assertEquals(BroadcastQueue.State.QUEUED, harness.env.retryQueue.all.single().state)
		assertEquals(emptyList<String>(), harness.transactionIds)
	}

	@Test
	fun `success plus settlement failure plus restart creates no second transaction`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		queueOneListenOffline(harness)
		harness.env.broadcaster.defaultResult =
			HiveRpc.BroadcastResult.Success("tx", "node", HiveRpc.BroadcastResult.Evidence.BLOCK)
		harness.env.retryQueue.settlementWorks = false

		harness.env.clock.advance(60_000)
		reconnect(harness)
		assertEquals(2, harness.env.broadcaster.sent.size)
		assertEquals(BroadcastQueue.State.IN_FLIGHT, harness.env.retryQueue.all.single().state)

		harness.feed(PlaybackEvent.ProcessRestarted)
		FinalizationRuntime.flushQueue()
		Thread.sleep(100)

		assertEquals("restart re-broadcast an already confirmed transaction", 2, harness.env.broadcaster.sent.size)
		assertEquals(
			"restart created a replacement transaction id",
			1,
			harness.env.broadcaster.sent.map { it.txId }.toSet().size,
		)
	}

	@Test
	fun `accepted unconfirmed plus settlement failure plus restart creates no second transaction`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		queueOneListenOffline(harness)
		harness.env.broadcaster.defaultResult =
			HiveRpc.BroadcastResult.AcceptedUnconfirmed("tx", "node", "confirmation unavailable")
		harness.env.retryQueue.settlementWorks = false

		harness.env.clock.advance(60_000)
		reconnect(harness)
		assertEquals(2, harness.env.broadcaster.sent.size)
		harness.env.broadcaster.defaultObservation = HiveRpc.TransactionEvidence.UNAVAILABLE

		harness.feed(PlaybackEvent.ProcessRestarted)
		FinalizationRuntime.flushQueue()
		Thread.sleep(100)

		assertEquals("restart re-broadcast an ambiguously accepted transaction", 2, harness.env.broadcaster.sent.size)
		assertEquals(BroadcastQueue.State.IN_FLIGHT, harness.env.retryQueue.all.single().state)
	}

	@Test
	fun `ambiguous in-flight restart remains fail closed`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		queueOneListenOffline(harness)
		val exactPrepared = requireNotNull(harness.env.retryQueue.all.single().preparedTransaction())
		harness.env.broadcaster.defaultObservation = HiveRpc.TransactionEvidence.UNAVAILABLE

		harness.feed(PlaybackEvent.ProcessRestarted)
		harness.env.clock.advance(3_600_000)
		FinalizationRuntime.flushQueue()
		Thread.sleep(100)

		assertEquals("ambiguous restart blindly re-broadcast", 1, harness.env.broadcaster.sent.size)
		val retained = harness.env.retryQueue.all.single()
		assertEquals(BroadcastQueue.State.IN_FLIGHT, retained.state)
		assertEquals("ambiguous evidence changed exact durable state", exactPrepared, retained.preparedTransaction())
		assertEquals(
			RecordingBroadcaster.Observation(exactPrepared.txId, exactPrepared.expirationEpochSec),
			harness.env.broadcaster.observed.single(),
		)
	}

	@Test
	fun `authoritative absence after expiration uses the existing replacement path`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		queueOneListenOffline(harness)
		val original = requireNotNull(harness.env.retryQueue.all.single().preparedTransaction())
		harness.env.broadcaster.defaultObservation = HiveRpc.TransactionEvidence.ABSENT

		val afterExpirationMs = original.expirationEpochSec * 1_000L + 1
		harness.env.clock.advance(afterExpirationMs - harness.env.clock.nowMillis())
		FinalizationRuntime.flushQueue()

		assertEquals(
			RecordingBroadcaster.Observation(original.txId, original.expirationEpochSec),
			harness.env.broadcaster.observed.single(),
		)
		assertEquals("replacement path did not prepare a new transaction", 2, harness.env.broadcaster.prepared.size)
		assertEquals("replacement path did not broadcast", 2, harness.env.broadcaster.sent.size)
		assertEquals(2, harness.env.broadcaster.sent.map { it.txId }.distinct().size)
		assertEquals(emptyList<BroadcastQueue.Entry>(), harness.env.retryQueue.all)
	}

	// ---- concurrency ---------------------------------------------------------

	/**
	 * Reconnect, the activity's `onStart` and a listener reconnect at once.
	 *
	 * Driven from real threads, with the first broadcast held open until all three
	 * drains are in flight, so the drains genuinely overlap rather than being
	 * serialized by the test harness. The engine's `broadcastLock` makes the
	 * losers wait, and by the time they run `due()` the entry is settled.
	 */
	@Test
	fun `three simultaneous drains send one due entry exactly once`() {
		val harness = ReplayHarness(ReplaySource.BRAVE, dispatcher = Dispatchers.IO)
		harness.env.facts.put(facts())
		harness.env.broadcaster.willReturn(HiveRpc.BroadcastResult.NetworkFailure("no route to host"))
		harness.feed(watching() + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized())
		awaitUntil("the listen was not queued") { harness.queuedForRetry == 1 }

		harness.env.broadcaster.defaultResult =
			HiveRpc.BroadcastResult.Success("tx", "node", HiveRpc.BroadcastResult.Evidence.BLOCK)

		val insideFirstBroadcast = CountDownLatch(1)
		val releaseFirstBroadcast = CountDownLatch(1)
		harness.env.broadcaster.onBroadcast = { call ->
			if (call == 2) {
				insideFirstBroadcast.countDown()
				check(releaseFirstBroadcast.await(10, TimeUnit.SECONDS)) { "the drain was never released" }
			}
		}

		reconnect(harness)
		assertTrue(
			"the first drain never reached the broadcaster",
			insideFirstBroadcast.await(10, TimeUnit.SECONDS),
		)
		// Two more triggers while the first send is still open.
		FinalizationRuntime.flushQueue()
		FinalizationRuntime.flushQueue()
		releaseFirstBroadcast.countDown()

		awaitUntil("the queue never drained") { harness.env.retryQueue.size() == 0 }
		// Give the two losers room to do the wrong thing if the lock does not hold.
		Thread.sleep(200)

		assertEquals(
			"one due entry produced more than one broadcast",
			2,
			harness.env.broadcaster.sent.size,
		)
		assertEquals(1, harness.transactionIds.size)
	}

	/**
	 * The same race with the removal failing underneath it.
	 *
	 * Strictly harder than the case above: there, the losers find the queue empty
	 * because the winner's removal succeeded, so only the lock is under test. Here
	 * the entry is still in the file when they look, and the only thing between
	 * them and a second irreversible write is the settled record.
	 */
	@Test
	fun `simultaneous drains send once even when the winner cannot retire the entry`() {
		val harness = ReplayHarness(ReplaySource.BRAVE, dispatcher = Dispatchers.IO)
		harness.env.facts.put(facts())
		harness.env.broadcaster.willReturn(HiveRpc.BroadcastResult.NetworkFailure("no route to host"))
		harness.feed(watching() + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized())
		awaitUntil("the listen was not queued") { harness.queuedForRetry == 1 }

		harness.env.broadcaster.defaultResult =
			HiveRpc.BroadcastResult.Success("tx", "node", HiveRpc.BroadcastResult.Evidence.BLOCK)
		harness.env.retryQueue.removalWorks = false

		val insideFirstBroadcast = CountDownLatch(1)
		val releaseFirstBroadcast = CountDownLatch(1)
		harness.env.broadcaster.onBroadcast = { call ->
			if (call == 2) {
				insideFirstBroadcast.countDown()
				check(releaseFirstBroadcast.await(10, TimeUnit.SECONDS)) { "the drain was never released" }
			}
		}

		reconnect(harness)
		assertTrue(
			"the first drain never reached the broadcaster",
			insideFirstBroadcast.await(10, TimeUnit.SECONDS),
		)
		FinalizationRuntime.flushQueue()
		FinalizationRuntime.flushQueue()
		releaseFirstBroadcast.countDown()

		awaitUntil("the entry was never settled") { harness.env.retryQueue.size() == 0 }
		Thread.sleep(200)

		assertEquals(
			"a losing drain re-sent an entry the winner could not remove",
			2,
			harness.env.broadcaster.sent.size,
		)
		assertEquals(1, harness.env.retryQueue.all.size)
		assertEquals(emptyList<BroadcastQueue.Entry>(), harness.env.retryQueue.due())
	}

	// ---- the unchanged failure path ------------------------------------------

	@Test
	fun `a genuine failure still retries with the existing backoff and ceiling`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		queueOneListenOffline(harness)
		harness.env.broadcaster.defaultResult = HiveRpc.BroadcastResult.NetworkFailure("still offline")

		// Seven more failures: retained, attempts climbing, never suppressed.
		repeat(7) { round ->
			harness.env.clock.advance(3_600_000)
			reconnect(harness)
			assertEquals(
				"attempt ${round + 1} did not reach the broadcaster",
				2 + round,
				harness.env.broadcaster.sent.size,
			)
			assertEquals(1, harness.env.retryQueue.all.size)
			assertEquals(round + 1, harness.env.retryQueue.all.single().attempts)
			assertEquals("a failed send was treated as settled", 1, harness.env.retryQueue.size())
		}

		// The eighth failure exhausts the ceiling and drops the entry.
		harness.env.clock.advance(3_600_000)
		reconnect(harness)

		assertEquals(9, harness.env.broadcaster.sent.size)
		assertEquals(emptyList<BroadcastQueue.Entry>(), harness.env.retryQueue.all)
		assertEquals(emptyList<String>(), harness.transactionIds)
		assertTrue(
			FinalizationRuntime.recent.value.any { "failed after 8 queued attempts" in it.status },
		)
	}

	@Test
	fun `a deferred retry keeps the existing one minute backoff`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		queueOneListenOffline(harness)
		harness.env.broadcaster.defaultResult = HiveRpc.BroadcastResult.Deferred("node rotation")

		reconnect(harness)
		assertEquals(2, harness.env.broadcaster.sent.size)
		assertEquals(1, harness.env.retryQueue.all.single().attempts)

		harness.env.clock.advance(59_999)
		FinalizationRuntime.flushQueue()
		assertEquals("backoff was shortened", 2, harness.env.broadcaster.sent.size)

		harness.env.broadcaster.defaultResult =
			HiveRpc.BroadcastResult.Success("tx", "node", HiveRpc.BroadcastResult.Evidence.BLOCK)
		harness.env.clock.advance(1)
		FinalizationRuntime.flushQueue()

		assertEquals("deferred operation did not become due after one minute", 3, harness.env.broadcaster.sent.size)
		assertEquals(0, harness.queuedForRetry)
		assertEquals(1, harness.env.broadcaster.sent.map { it.txId }.toSet().size)
	}

	private fun awaitUntil(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
		val deadline = System.currentTimeMillis() + timeoutMs
		while (System.currentTimeMillis() < deadline) {
			if (condition()) return
			Thread.sleep(10)
		}
		throw AssertionError(message)
	}
}
