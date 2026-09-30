package com.rustedwax.app.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production-wiring gate for the queue's one later wake-up (Issue #9 B1).
 *
 * `QueueWakeupReplayTest` proves the engine arms and drains correctly against a
 * scripted alarm; what a JVM test cannot show is that a phone ever schedules
 * anything, or that what it schedules is the narrow thing described.
 */
class QueueWakeupWiringTest {

	private fun text(path: String): String {
		val file = java.io.File(path)
		assertTrue("missing at ${file.absolutePath}", file.isFile)
		return file.readText()
	}

	private fun code(path: String): String = text("src/main/java/com/rustedwax/app/$path")
		.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
		.lines()
		.joinToString("\n") { it.substringBefore("//") }

	@Test
	fun `the device engine installs the alarm and the job`() {
		val runtime = code("scrobble/FinalizationRuntime.kt")
		assertTrue(
			"init does not install the device wake-up",
			"alarm = QueueRetryAlarmReceiver.Alarm(appContext)" in runtime &&
				"jobs = QueueRetryJobService.Jobs(appContext)" in runtime,
		)
	}

	/** Codex B1 finding: the run must end from the drain's completion, not the service's waiter. */
	@Test
	fun `the wake-up run ends from the runtime drain's own completion`() {
		val runtime = code("scrobble/FinalizationRuntime.kt")
		val flush = runtime.substringAfter("internal fun flushQueueForWakeup(")
			.substringBefore("internal fun onQueueWakeupAlarm")
		assertTrue("the run is not finished from the drain's completion", "drain.invokeOnCompletion" in flush)
		assertTrue("the run is finished without its token", "finishQueueWakeupRun(wakeup, run)" in flush)
		assertFalse("a caller can still end a run from outside", "internal fun queueWakeupFinished" in runtime)
	}

	@Test
	fun `both paths that write the queue reconsider the wake-up`() {
		val runtime = code("scrobble/FinalizationRuntime.kt")
		val drain = runtime.substringAfter("private fun retryQueuedPayloads()")
			.substringBefore("private fun prepareQueuedEntry")
		val send = runtime.substringAfter("private fun enqueueAndSend(")
			.substringBefore("private fun broadcastDirect(")
		assertTrue("the drain does not re-arm after writing", "reconsiderQueueWakeup()" in drain)
		assertTrue("the first send does not re-arm after writing", "reconsiderQueueWakeup()" in send)
	}

	/**
	 * The alarm is timing only. The minimum-latency job it replaced sat READY on
	 * the A36 for forty minutes; the fix must not drift back into asking the job
	 * scheduler for the delay.
	 */
	@Test
	fun `the alarm is one inexact elapsed-realtime alarm and does no network work`() {
		val receiver = code("scrobble/QueueRetryAlarmReceiver.kt")
		assertTrue("not an inexact idle-allowed alarm", "setAndAllowWhileIdle(" in receiver)
		assertTrue("not on elapsed realtime", "ELAPSED_REALTIME_WAKEUP" in receiver)
		assertTrue("the PendingIntent is mutable", "FLAG_IMMUTABLE" in receiver)
		assertTrue("the receiver does not hand over to the engine", "FinalizationRuntime.onQueueWakeupAlarm()" in receiver)
		listOf(
			"setExact", "setAlarmClock", "setRepeating", "setInexactRepeating",
			"flushQueue", "broadcast", "prepareJson", "HiveRpc", "goAsync",
		).forEach { assertFalse("the alarm receiver uses `$it`", it in receiver) }
	}

	@Test
	fun `the job is one expedited id with a network constraint and no latency`() {
		val job = code("scrobble/QueueRetryJobService.kt")
		assertTrue("the job has no network constraint", "setRequiredNetworkType(" in job)
		assertTrue("the job is never expedited", "setExpedited(true)" in job)
		assertTrue("a refused schedule is not detected", "RESULT_SUCCESS" in job)
		assertFalse("an expedited job cannot carry a latency", "setMinimumLatency(" in job)
		assertTrue("the job does not use the engine's drain", "FinalizationRuntime.flushQueueForWakeup(" in job)
		assertTrue("the job never finishes", "jobFinished(claim.params, false)" in job)
		assertTrue("jobFinished is not gated on the job still being ours", "if (claim.finish()) jobFinished(claim.params" in job)
		assertTrue("a start is not keyed by the scheduled job id", "jobs.begin(params.jobId, params)" in job)
		assertFalse("the service owns a waiter that its destruction can kill", "CoroutineScope(" in job || ".join()" in job)
		assertTrue("onStopJob does not give the job up by job id", "jobs.stop(params.jobId)" in job)
		assertFalse("onStopJob matches by parameters object identity", "jobs.stop(params)" in job)
		assertFalse("the job persists across boot, which needs a boot permission", "setPersisted(" in job)
		assertFalse("the job repeats on its own", "setPeriodic(" in job)
		assertFalse("the job owns retry policy", ".due(" in job || "recordFailure(" in job)
		assertFalse("the job re-finalizes a listen", "finalizeTrack" in job)
	}

	@Test
	fun `the manifest adds exactly one private receiver, the job service, and no permission`() {
		val manifest = text("src/main/AndroidManifest.xml")
		val service = manifest.substringAfter("android:name=\".scrobble.QueueRetryJobService\"")
			.substringBefore("/>")
		assertTrue("only the job scheduler may bind it", "android.permission.BIND_JOB_SERVICE" in service)
		assertTrue("the job service is exported", "android:exported=\"false\"" in service)

		val receivers = Regex("""<receiver[\s\S]*?(/>|</receiver>)""").findAll(manifest).map { it.value }.toList()
		assertTrue("not exactly one receiver", receivers.size == 1)
		val receiver = receivers.single()
		assertTrue("the receiver is not the queue alarm", ".scrobble.QueueRetryAlarmReceiver" in receiver)
		assertTrue("the receiver is exported", "android:exported=\"false\"" in receiver)
		assertFalse("the receiver listens for outside broadcasts", "intent-filter" in receiver)
		listOf(
			"RECEIVE_BOOT_COMPLETED",
			"SCHEDULE_EXACT_ALARM",
			"USE_EXACT_ALARM",
			"WAKE_LOCK",
			"FOREGROUND_SERVICE",
		).forEach { assertFalse("the wake-up needed no `$it`", manifest.contains(it)) }
	}
}
