package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.BroadcastQueue
import com.rustedwax.app.scrobble.ConnectivityRetryTrigger
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.app.scrobble.JobRunGate
import com.rustedwax.app.scrobble.QueueWakeup
import com.rustedwax.hive.HiveRpc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codex B1 finding: the wake-up run must end even when its job does not.
 *
 * The drain runs in the engine's application-lifetime scope; the job service
 * only waited for it. When the platform stopped the job and destroyed the
 * service, the waiter died with it, the drain still finished — and nothing
 * cleared "running", so every later wake-up was suppressed. These scenarios hold
 * the drain open on a [DeferredDispatcher], take the job away underneath it,
 * and then let it finish.
 */
class QueueWakeupLifecycleReplayTest : ReplayScenarioTest() {

	private val videoId = "hTWKbfoikeg"
	private val title = "Synthetic Lifecycle Song"
	private val channel = "SyntheticChannel"
	private val offline = HiveRpc.BroadcastResult.NetworkFailure("Unable to resolve host")

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

	private class ScriptedJobs : QueueWakeup.Jobs {
		var scheduled = false
		var expedited = 0
		override fun isScheduled() = scheduled
		override fun scheduleExpedited(): Boolean {
			expedited++
			scheduled = true
			return true
		}

		override fun scheduleOrdinary() = false
		override fun cancel() {
			scheduled = false
		}
	}

	private val alarm = ScriptedAlarm()
	private val jobs = ScriptedJobs()
	private val dispatcher = DeferredDispatcher()

	/** One service instance's bookkeeping, and every `jobFinished` it would send. */
	private val gate = JobRunGate<String>()

	/** The one fixed queue-retry job id; every execution below shares it, as on a phone. */
	private val jobId = 0x5157_0001
	private val jobFinished = mutableListOf<String>()

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

	private fun harness() = ReplayHarness(ReplaySource.BRAVE, dispatcher = dispatcher).also {
		FinalizationRuntime.installQueueWakeupForReplay(QueueWakeup(alarm, jobs))
		it.env.facts.put(facts())
	}

	/** Queued offline, retried offline once (attempt 1, one-minute backoff), alarm armed. */
	private fun queuedAndBackingOff(harness: ReplayHarness, inFlight: Boolean = false) {
		if (inFlight) {
			// Signed and sent; the answer was lost. The exact transaction is durable.
			harness.env.broadcaster.willReturn(offline, offline)
		} else {
			harness.env.broadcaster.preparationFailure = offline
		}
		harness.feed(watching() + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized())
		dispatcher.drain()
		assertEquals(1, harness.queuedForRetry)
		harness.env.clock.advance(18_000)
		FinalizationRuntime.flushQueue()
		dispatcher.drain()
		assertEquals(1, harness.env.retryQueue.all.single().attempts)
		assertEquals(60_000L, alarm.armedDelayMs)
		harness.env.broadcaster.preparationFailure = null
	}

	/** The receiver on the alarm; then the platform starts the job, whose drain is held open. */
	private fun alarmThenJobStarts(harness: ReplayHarness, key: String): kotlinx.coroutines.Job {
		harness.env.clock.advance(60_000)
		alarm.armedDelayMs = null
		assertEquals(QueueWakeup.Delivery.EXPEDITED, FinalizationRuntime.onQueueWakeupAlarm())
		return startJob(key)
	}

	/** What `onStartJob` does, with the service's gate standing in for `jobFinished`. */
	private fun startJob(key: String): kotlinx.coroutines.Job {
		jobs.scheduled = false
		// Each execution's parameters are a distinct object; only the id is shared.
		val claim = gate.begin(jobId, key)
		val drain = FinalizationRuntime.flushQueueForWakeup(onDrained = {
			if (claim.finish()) jobFinished += claim.params
		})
		assertNotNull(drain)
		return drain!!
	}

	@Test
	fun `normal completion finishes the job once and re-arms once`() {
		val harness = harness()
		queuedAndBackingOff(harness)
		val drain = alarmThenJobStarts(harness, "job-1")
		val disarmsBefore = alarm.disarms

		dispatcher.drain()

		assertTrue(drain.isCompleted)
		assertEquals(listOf("job-1"), jobFinished)
		assertEquals(1, harness.env.broadcaster.sent.size)
		assertEquals("the settled queue did not cancel exactly once", disarmsBefore + 1, alarm.disarms)
	}

	@Test
	fun `a job stopped and its service destroyed mid-drain still ends the run once, with no jobFinished`() {
		val harness = harness()
		queuedAndBackingOff(harness)
		val drain = alarmThenJobStarts(harness, "job-1")
		assertFalse("the drain was not held open", drain.isCompleted)

		// onStopJob only — no onDestroy to mask it: the stop itself, matched by
		// job id, must revoke the claim.
		assertTrue(gate.stop(jobId))
		// An alarm landing now sees the run still going.
		assertEquals(QueueWakeup.Delivery.RUNNING, FinalizationRuntime.onQueueWakeupAlarm())

		// The engine's drain carries on and finishes.
		dispatcher.drain()

		assertTrue(drain.isCompleted)
		assertEquals("jobFinished was sent for a job the platform had taken back", emptyList<String>(), jobFinished)
		assertEquals("the stopped drain did not send exactly once", 1, harness.env.broadcaster.sent.size)
		assertEquals(0, harness.queuedForRetry)
		// "running" was cleared: a later alarm is judged on the queue, not suppressed.
		assertEquals(QueueWakeup.Delivery.NOTHING_DUE, FinalizationRuntime.onQueueWakeupAlarm())
	}

	@Test
	fun `after a stopped run that failed again, the next alarm and job run and send once`() {
		val harness = harness()
		queuedAndBackingOff(harness)
		harness.env.broadcaster.preparationFailure = offline
		val drain = alarmThenJobStarts(harness, "job-1")
		gate.stop(jobId)
		gate.closeAll()
		val armsBefore = alarm.arms.size

		dispatcher.drain()

		assertTrue(drain.isCompleted)
		assertEquals(2, harness.env.retryQueue.all.single().attempts)
		assertEquals("the stopped run did not re-arm exactly once", armsBefore + 1, alarm.arms.size)
		assertEquals(120_000L, alarm.armedDelayMs)

		// A new service instance, a new job: the wake-up is not stuck.
		harness.env.broadcaster.preparationFailure = null
		harness.env.clock.advance(120_000)
		alarm.armedDelayMs = null
		assertEquals(QueueWakeup.Delivery.EXPEDITED, FinalizationRuntime.onQueueWakeupAlarm())
		val next = startJob("job-2")
		dispatcher.drain()
		assertTrue(next.isCompleted)
		assertEquals(listOf("job-2"), jobFinished)
		assertEquals(1, harness.env.broadcaster.sent.size)
		assertEquals(0, harness.queuedForRetry)
	}

	@Test
	fun `stop arriving after completion changes nothing — cleanup ran once`() {
		val harness = harness()
		queuedAndBackingOff(harness)
		alarmThenJobStarts(harness, "job-1")
		dispatcher.drain()
		val arms = alarm.arms.size
		val disarms = alarm.disarms

		assertFalse("stop after completion was treated as live", gate.stop(jobId))
		gate.closeAll()

		assertEquals(listOf("job-1"), jobFinished)
		assertEquals(arms, alarm.arms.size)
		assertEquals(disarms, alarm.disarms)
	}

	@Test
	fun `a stopped drain and its successor overlapping send once and both end`() {
		val harness = harness()
		queuedAndBackingOff(harness)
		val first = alarmThenJobStarts(harness, "job-1")
		gate.stop(jobId)
		// Before the stopped drain ran, the platform starts the job again.
		jobs.scheduled = true
		val second = startJob("job-2")

		dispatcher.drain()

		assertTrue(first.isCompleted && second.isCompleted)
		assertEquals("overlapping drains sent twice", 1, harness.env.broadcaster.sent.size)
		assertEquals(1, harness.transactionIds.size)
		assertEquals(listOf("job-2"), jobFinished)
		assertEquals(QueueWakeup.Delivery.NOTHING_DUE, FinalizationRuntime.onQueueWakeupAlarm())
	}

	@Test
	fun `a drain cancelled under a stopped job still ends the run`() {
		val harness = harness()
		queuedAndBackingOff(harness)
		val drain = alarmThenJobStarts(harness, "job-1")
		gate.stop(jobId)

		FinalizationRuntime.cancelInFlightForReplay()
		dispatcher.drain()

		assertTrue(drain.isCancelled)
		assertEquals(0, harness.env.broadcaster.sent.size)
		assertEquals(emptyList<String>(), jobFinished)
		// Still owed and due, and not stuck behind a phantom run: the next trigger sends it.
		assertEquals(1, harness.queuedForRetry)
		ConnectivityRetryTrigger(
			queueDepth = { FinalizationRuntime.queueSize.value },
			flush = { FinalizationRuntime.flushQueue() },
			nowMs = { harness.env.clock.nowMillis() },
		).onUsableNetwork()
		dispatcher.drain()
		assertEquals(1, harness.env.broadcaster.sent.size)
	}

	@Test
	fun `an ambiguous in-flight transaction under a stopped job stays fail-closed with no replacement`() {
		val harness = harness()
		queuedAndBackingOff(harness, inFlight = true)
		val entry = harness.env.retryQueue.all.single()
		assertEquals(BroadcastQueue.State.IN_FLIGHT, entry.state)
		val txId = entry.preparedTransactionId
		val sentBefore = harness.env.broadcaster.sent.size

		harness.env.broadcaster.defaultObservation = HiveRpc.TransactionEvidence.UNAVAILABLE
		val drain = alarmThenJobStarts(harness, "job-1")
		gate.stop(jobId)
		gate.closeAll()
		dispatcher.drain()

		assertTrue(drain.isCompleted)
		assertEquals("an unreconcilable transaction was broadcast", sentBefore, harness.env.broadcaster.sent.size)
		assertEquals("a replacement transaction was signed", 1, harness.env.broadcaster.prepared.size)
		assertEquals(BroadcastQueue.State.IN_FLIGHT, harness.env.retryQueue.all.single().state)
		assertNull("a fail-closed entry armed an immediate wake-up", alarm.armedDelayMs)
		assertEquals(emptyList<String>(), jobFinished)

		// When the chain answers "absent", only the exact same transaction goes out.
		harness.env.broadcaster.defaultObservation = HiveRpc.TransactionEvidence.ABSENT
		FinalizationRuntime.flushQueue()
		dispatcher.drain()
		assertEquals(1, harness.env.broadcaster.prepared.size)
		assertEquals(setOf(txId), harness.env.broadcaster.sent.map { it.txId }.toSet())
		assertEquals(0, harness.queuedForRetry)
	}
}
