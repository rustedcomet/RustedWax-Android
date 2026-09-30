package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.BroadcastQueue
import com.rustedwax.app.scrobble.ConnectivityRetryTrigger
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.app.scrobble.QueueWakeup
import com.rustedwax.hive.HiveRpc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #9 B1 — connectivity returned before the backoff, and nothing asked again.
 *
 * The A36 sequence, replayed through the real engine: a listen finalizes
 * offline and queues; the app is opened while still offline, so the retry
 * fails and backs off a minute; the network validates 45 s later and the
 * connectivity trigger correctly finds nothing due. Before this change that was
 * the last question anybody asked. These scenarios drive the wake-up the engine
 * arms exactly as the job scheduler would, and count calls at the broadcaster
 * seam because that is where an irreversible write crosses.
 */
class QueueWakeupReplayTest : ReplayScenarioTest() {

	private val videoId = "d8ekz_CSBVg"
	private val title = "Three Days Grace - Synthetic Test Song"
	private val channel = "SyntheticChannel"

	/** The platform alarm, scripted: one slot, replaced on every arm. */
	private class ScriptedAlarm : QueueWakeup.Alarm {
		var armedDelayMs: Long? = null
		val arms = mutableListOf<Long>()
		var disarms = 0

		override fun arm(delayMs: Long): Boolean {
			armedDelayMs = delayMs
			arms += delayMs
			return true
		}

		override fun disarm() {
			armedDelayMs = null
			disarms++
		}
	}

	/** The platform job, scripted: one id, and a switch for the quota answer. */
	private class ScriptedJobs : QueueWakeup.Jobs {
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

	private val alarm = ScriptedAlarm()
	private val jobs = ScriptedJobs()

	private fun facts() = VideoFacts(
		videoId = videoId,
		title = title,
		author = channel,
		lengthSeconds = 56,
		category = "Music",
		watchPageResolved = true,
		isUnlisted = false,
	)

	private fun watching() = listOf(
		PlaybackEvent.NotificationObserved(host = "youtube.com", title = title),
		PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = videoId),
		PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 56_000),
		PlaybackEvent.PlaybackStateChanged(playing = true),
	)

	private val offline = HiveRpc.BroadcastResult.NetworkFailure("Unable to resolve host")

	private fun installWakeup() {
		FinalizationRuntime.installQueueWakeupForReplay(QueueWakeup(alarm, jobs))
	}

	private fun reconnect(harness: ReplayHarness): Boolean =
		ConnectivityRetryTrigger(
			queueDepth = { FinalizationRuntime.queueSize.value },
			flush = { FinalizationRuntime.flushQueue() },
			nowMs = { harness.env.clock.nowMillis() },
		).onUsableNetwork()

	/** The receiver: the platform delivers the alarm. No network, no Hive. */
	private fun alarmFires(harness: ReplayHarness): QueueWakeup.Delivery? {
		alarm.armedDelayMs = null
		val prepared = harness.env.broadcaster.prepared.size
		val sent = harness.env.broadcaster.sent.size
		val observed = harness.env.broadcaster.observed.size
		val delivery = FinalizationRuntime.onQueueWakeupAlarm()
		assertEquals("the alarm receiver signed something", prepared, harness.env.broadcaster.prepared.size)
		assertEquals("the alarm receiver broadcast something", sent, harness.env.broadcaster.sent.size)
		assertEquals("the alarm receiver asked the chain", observed, harness.env.broadcaster.observed.size)
		return delivery
	}

	/**
	 * What the scheduled job does: drain; at the drain's end the job is finished
	 * first, then the wake-up re-arms from what the queue now owes.
	 */
	private fun jobRuns() {
		assertTrue("the job ran without being scheduled", jobs.scheduled)
		jobs.scheduled = false
		val armsBefore = alarm.arms.size
		val cancelsBefore = jobs.cancels
		var finished = 0
		var armsAtFinish = -1
		var cancelsAtFinish = -1
		val drain = FinalizationRuntime.flushQueueForWakeup(onDrained = {
			finished++
			armsAtFinish = alarm.arms.size
			cancelsAtFinish = jobs.cancels
		})
		assertNotNull("the engine refused the wake-up's drain", drain)
		assertTrue("the replay drain did not complete synchronously", drain!!.isCompleted)
		assertEquals("the job was not finished exactly once", 1, finished)
		assertEquals("the drain re-armed before its job was finished", armsBefore, armsAtFinish)
		assertEquals(
			"the drain cancelled its own running job, which the platform would stop",
			cancelsBefore,
			cancelsAtFinish,
		)
	}

	/** The whole autonomous path: alarm → receiver → expedited job → drain. */
	private fun wakeupFires(harness: ReplayHarness) {
		assertNotNull("the alarm fired without being armed", alarm.armedDelayMs)
		assertEquals(QueueWakeup.Delivery.EXPEDITED, alarmFires(harness))
		jobRuns()
	}

	/**
	 * The A36 shape up to the moment the bug began: queued offline, the app
	 * opened offline (attempt 1, backoff one minute), connectivity back 45 s
	 * early and finding nothing due.
	 */
	private fun reachReconnectBeforeDue(harness: ReplayHarness) {
		installWakeup()
		harness.env.facts.put(facts())
		// Offline, signing needs the chain head, so preparation itself fails and
		// the entry stays plain QUEUED — no transaction exists yet.
		harness.env.broadcaster.preparationFailure = offline
		harness.feed(watching() + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized())
		assertEquals("the listen was not queued", 1, harness.queuedForRetry)
		assertNull("an entry due now armed a wake-up", alarm.armedDelayMs)

		// App opened while still offline: attempt 1, backoff one minute.
		harness.env.clock.advance(18_000)
		FinalizationRuntime.flushQueue()
		val entry = harness.env.retryQueue.all.single()
		assertEquals(1, entry.attempts)
		assertEquals(harness.env.clock.nowMillis() + 60_000, entry.nextAttemptAtMs)
		assertEquals("the backoff did not arm exactly one wake-up", listOf(60_000L), alarm.arms)

		// Validated connectivity 15 s later — 45 s before the entry is due.
		harness.env.clock.advance(15_000)
		harness.env.broadcaster.preparationFailure = null
		assertTrue("the connectivity trigger did not ask", reconnect(harness))
		assertEquals("connectivity returning bypassed the backoff", 0, harness.env.broadcaster.sent.size)
		assertEquals(1, harness.queuedForRetry)
		assertEquals("an unchanged deadline was armed a second time", 1, alarm.arms.size)
		assertEquals(60_000L, alarm.armedDelayMs)
	}

	@Test
	fun `reconnect before due sends nothing early, then the wake-up sends it once without the app`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		reachReconnectBeforeDue(harness)

		// Nothing opens the app. The job fires when the backoff elapses.
		harness.env.clock.advance(45_000)
		val disarmsBefore = alarm.disarms
		wakeupFires(harness)

		assertEquals("the due listen was not sent by the wake-up", 1, harness.env.broadcaster.sent.size)
		assertEquals(0, harness.queuedForRetry)
		assertEquals(1, harness.transactionIds.size)
		assertNull("an empty queue left a wake-up armed", alarm.armedDelayMs)
		assertEquals("the settled queue did not cancel", disarmsBefore + 1, alarm.disarms)

		// Every later drain on an empty queue: no arm, no second send.
		reconnect(harness)
		FinalizationRuntime.flushQueue()
		assertEquals(1, harness.env.broadcaster.sent.size)
		assertEquals(1, alarm.arms.size)
		assertEquals("an already-cancelled wake-up was cancelled again", disarmsBefore + 1, alarm.disarms)
	}

	@Test
	fun `another failure at the wake-up backs off two minutes and re-arms exactly once`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		reachReconnectBeforeDue(harness)

		harness.env.clock.advance(45_000)
		// The node is unreachable again at the moment it fires.
		harness.env.broadcaster.preparationFailure = offline
		val firedAt = harness.env.clock.nowMillis()
		wakeupFires(harness)

		val entry = harness.env.retryQueue.all.single()
		assertEquals(BroadcastQueue.State.QUEUED, entry.state)
		assertEquals("backoff did not double", 2, entry.attempts)
		assertEquals(firedAt + 120_000, entry.nextAttemptAtMs)
		assertEquals("not exactly one re-arm, at the new backoff", listOf(60_000L, 120_000L), alarm.arms)
		assertEquals(0, harness.env.broadcaster.sent.size)

		// Fires again, online: exactly one broadcast.
		harness.env.broadcaster.preparationFailure = null
		harness.env.clock.advance(120_000)
		wakeupFires(harness)
		assertEquals(1, harness.env.broadcaster.sent.size)
		assertEquals(0, harness.queuedForRetry)
		assertNull(alarm.armedDelayMs)
	}

	@Test
	fun `an alarm the app beat to it schedules nothing and sends nothing`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		reachReconnectBeforeDue(harness)

		// The user opens the app just after the deadline: sent by onStart.
		harness.env.clock.advance(46_000)
		FinalizationRuntime.flushQueue()
		assertEquals(1, harness.env.broadcaster.sent.size)
		assertNull("a settled queue left the alarm armed", alarm.armedDelayMs)

		// An alarm the platform delivered before the cancel landed.
		assertEquals(QueueWakeup.Delivery.NOTHING_DUE, alarmFires(harness))
		assertEquals("a stale alarm scheduled a run", 0, jobs.expedited)
		assertEquals("a stale alarm sent the listen twice", 1, harness.env.broadcaster.sent.size)
		assertEquals(1, harness.transactionIds.size)
	}

	@Test
	fun `a run the app beat to it is cancelled, and if it runs anyway it sends nothing`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		reachReconnectBeforeDue(harness)
		harness.env.clock.advance(45_000)
		assertEquals(QueueWakeup.Delivery.EXPEDITED, alarmFires(harness))

		// The app opens before the platform starts the job.
		FinalizationRuntime.flushQueue()
		assertEquals(1, harness.env.broadcaster.sent.size)
		assertFalse("a run nothing is owed for was left scheduled", jobs.scheduled)

		// The platform started it before the cancel landed.
		jobs.scheduled = true
		jobRuns()
		assertEquals("a stale run sent the listen twice", 1, harness.env.broadcaster.sent.size)
		assertEquals(1, harness.transactionIds.size)
	}

	@Test
	fun `a duplicate alarm delivery schedules one run and sends once`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		reachReconnectBeforeDue(harness)
		harness.env.clock.advance(45_000)

		assertEquals(QueueWakeup.Delivery.EXPEDITED, alarmFires(harness))
		assertEquals(QueueWakeup.Delivery.ALREADY_SCHEDULED, alarmFires(harness))
		assertEquals(1, jobs.expedited)
		jobRuns()
		assertEquals(1, harness.env.broadcaster.sent.size)
		assertEquals(1, harness.transactionIds.size)
	}

	@Test
	fun `an alarm that lands offline spends no attempt — the run waits for a network`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		reachReconnectBeforeDue(harness)

		// The network drops again before the deadline.
		harness.env.clock.advance(45_000)
		harness.env.broadcaster.preparationFailure = offline
		assertEquals(QueueWakeup.Delivery.EXPEDITED, alarmFires(harness))
		assertEquals("the alarm spent an attempt", 1, harness.env.retryQueue.all.single().attempts)

		// The job's network constraint holds it; a validated network releases it.
		harness.env.clock.advance(600_000)
		harness.env.broadcaster.preparationFailure = null
		jobRuns()
		assertEquals(1, harness.env.broadcaster.sent.size)
		assertEquals(0, harness.queuedForRetry)
	}

	@Test
	fun `a refused expedited run falls back to an ordinary one that still sends once`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		reachReconnectBeforeDue(harness)
		harness.env.clock.advance(45_000)
		jobs.expeditedWorks = false

		assertEquals(QueueWakeup.Delivery.ORDINARY_FALLBACK, alarmFires(harness))
		jobRuns()
		assertEquals(1, harness.env.broadcaster.sent.size)
		assertEquals(0, harness.queuedForRetry)
	}

	@Test
	fun `when no run can be scheduled nothing is claimed and connectivity still sends it`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		reachReconnectBeforeDue(harness)
		harness.env.clock.advance(45_000)
		jobs.expeditedWorks = false
		jobs.ordinaryWorks = false

		assertEquals(QueueWakeup.Delivery.FAILED, alarmFires(harness))
		assertFalse(jobs.scheduled)
		assertEquals(0, harness.env.broadcaster.sent.size)
		assertEquals(1, harness.queuedForRetry)

		// The pre-existing triggers are untouched by the failure.
		assertTrue(reconnect(harness))
		assertEquals(1, harness.env.broadcaster.sent.size)
	}

	@Test
	fun `a process restart re-arms the same deadline from the durable queue`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		reachReconnectBeforeDue(harness)

		// Process death loses the in-memory wake-up; the queue file remains.
		harness.feed(PlaybackEvent.ProcessRestarted)
		installWakeup()
		harness.env.clock.advance(5_000)
		// The listener connecting drains on start: nothing due yet.
		FinalizationRuntime.flushQueue()

		assertEquals(0, harness.env.broadcaster.sent.size)
		assertEquals("the restored wake-up does not target the same deadline", 40_000L, alarm.armedDelayMs)

		harness.env.clock.advance(40_000)
		wakeupFires(harness)
		assertEquals(1, harness.env.broadcaster.sent.size)
		assertEquals(0, harness.queuedForRetry)
	}

	@Test
	fun `an entry for another account never arms a wake-up and is left untouched`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		installWakeup()
		val foreign = harness.env.retryQueue.enqueue(
			"someone-else",
			"""{"synthetic":true}""",
			"Other account — Synthetic",
			100,
			null,
		)!!
		harness.env.retryQueue.recordFailure(foreign, "offline")
		assertTrue(harness.env.retryQueue.all.single().nextAttemptAtMs > harness.env.clock.nowMillis())

		FinalizationRuntime.flushQueue()
		assertNull("a wake-up was armed for a row this key can never send", alarm.armedDelayMs)
		assertEquals(0, alarm.arms.size)

		// Even a (stale) alarm delivered at its deadline schedules nothing for it.
		harness.env.clock.advance(120_000)
		assertEquals(QueueWakeup.Delivery.NOTHING_DUE, alarmFires(harness))
		assertEquals(0, jobs.expedited + jobs.ordinary)

		harness.env.clock.advance(3_600_000)
		FinalizationRuntime.flushQueue()
		assertEquals("another account's row was sent", 0, harness.env.broadcaster.sent.size)
		assertEquals(0, harness.env.broadcaster.prepared.size)
		assertEquals("someone-else", harness.env.retryQueue.all.single().username)
		assertEquals(0, alarm.arms.size)
	}

	@Test
	fun `an ambiguous in-flight transaction is reconciled, never re-signed, and never hot-loops`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		installWakeup()
		harness.env.facts.put(facts())
		// Signed and sent; the answer is lost. The exact transaction is durable.
		harness.env.broadcaster.willReturn(offline)
		harness.feed(watching() + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized())
		assertEquals(BroadcastQueue.State.IN_FLIGHT, harness.env.retryQueue.all.single().state)
		val txId = harness.env.retryQueue.all.single().preparedTransactionId

		// A queued rebroadcast of the exact same transaction fails again: backoff.
		harness.env.broadcaster.willReturn(offline)
		FinalizationRuntime.flushQueue()
		assertEquals(listOf(60_000L), alarm.arms)
		assertEquals(2, harness.env.broadcaster.sent.size)

		// At the wake-up, nothing can say whether the chain has it.
		harness.env.clock.advance(60_000)
		harness.env.broadcaster.defaultObservation = HiveRpc.TransactionEvidence.UNAVAILABLE
		wakeupFires(harness)

		assertEquals("an unreconcilable transaction was broadcast", 2, harness.env.broadcaster.sent.size)
		assertEquals("a replacement transaction was signed", 1, harness.env.broadcaster.prepared.size)
		assertEquals(setOf(txId), harness.env.broadcaster.sent.map { it.txId }.toSet())
		assertEquals(BroadcastQueue.State.IN_FLIGHT, harness.env.retryQueue.all.single().state)
		// Still due, so the next wake-up would ask the same unanswerable question
		// immediately: that is left to connectivity and the app, not a loop.
		assertNull("a fail-closed entry armed an immediate wake-up", alarm.armedDelayMs)
		assertEquals(1, alarm.arms.size)
	}
}
