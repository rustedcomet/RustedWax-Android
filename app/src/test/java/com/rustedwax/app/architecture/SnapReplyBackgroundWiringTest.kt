package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #42 Stage 2: the background reply check, asserted where a JVM test
 * cannot reach — what the manifest declares, what the job asks the scheduler
 * for, and what the job can name.
 *
 * The owner authorized one periodic job for this, and nothing more: no
 * receiver, no worker library, no foreground service, no wake lock, no exact
 * alarm, and no route from the job to anything that signs or sends.
 */
class SnapReplyBackgroundWiringTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private fun text(path: String): String = File(root, path).let {
		assertTrue("missing: $path", it.isFile)
		it.readText()
	}

	private fun code(path: String): String = text(path)
		.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
		.lines()
		.joinToString("\n") { it.substringBefore("//") }

	private val manifest get() = text("app/src/main/AndroidManifest.xml")
	private val job get() = code("app/src/main/java/com/rustedwax/app/ui/snaps/SnapReplyBackground.kt")
	private val activity get() = code("app/src/main/java/com/rustedwax/app/MainActivity.kt")

	@Test
	fun `the manifest adds one private job service and the boot permission, and nothing else`() {
		val service = manifest.substringAfter("android:name=\".ui.snaps.SnapReplyJobService\"").substringBefore("/>")
		assertTrue("only the job scheduler may bind it", "android.permission.BIND_JOB_SERVICE" in service)
		assertTrue("the job service is exported", "android:exported=\"false\"" in service)
		assertFalse("it listens for outside intents", "intent-filter" in service)
		assertTrue(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
		assertFalse("a boot receiver was added", Regex("BOOT_COMPLETED\"\\s*/>\\s*</intent-filter>").containsMatchIn(manifest))
		assertEquals(
			"the queue alarm stays the only receiver",
			1,
			Regex("<receiver").findAll(manifest).count(),
		)
		listOf("WAKE_LOCK", "FOREGROUND_SERVICE", "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM", "androidx.work")
			.forEach { assertFalse("background reply discovery needs no `$it`", it in manifest) }
	}

	@Test
	fun `one periodic, persisted, network-bound job under one id`() {
		assertTrue("not periodic with the narrow flex", "setPeriodic(SPEC.periodMillis, SPEC.flexMillis)" in job)
		assertTrue("no network constraint", "setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)" in job)
		assertTrue("not persisted across a reboot", "setPersisted(SPEC.persisted)" in job)
		assertTrue("no conservative backoff", "setBackoffCriteria(SPEC.backoffMillis, JobInfo.BACKOFF_POLICY_EXPONENTIAL)" in job)
		assertEquals("one schedule call", 1, Regex("""scheduler\?\.schedule\(""").findAll(job).count())
		assertTrue("unique: the pending job is compared before scheduling", "getPendingJob(JOB_ID)" in job)
		assertTrue("a refused schedule is not detected", "RESULT_SUCCESS" in job)
		listOf("setExpedited(", "setMinimumLatency(", "setOverrideDeadline(", "setImportantWhileForeground(")
			.forEach { assertFalse("the job uses `$it`", it in job) }
	}

	@Test
	fun `the job runs the Stage 1 check once and finishes, with no loop or wake lock`() {
		assertTrue(
			"the job does not use the shared check at its own interval",
			"SnapReplyChecks.background(app).run(minIntervalMillis = SnapReplySchedule.JOB_MIN_INTERVAL_MILLIS)" in job,
		)
		assertTrue("jobFinished is not gated on the job still being ours", "if (claim.finish()) jobFinished(claim.params" in job)
		assertTrue("a start is not keyed by the scheduled job id", "jobs.begin(params.jobId, params)" in job)
		assertTrue("onStopJob does not give the job up by job id", "jobs.stop(params.jobId)" in job)
		listOf("while (true)", "Thread.sleep", "WakeLock", "PowerManager", "AlarmManager", "Timer(", "delay(", "force = true")
			.forEach { assertFalse("the job uses `$it`", it in job) }
	}

	@Test
	fun `the job reads the account signed in at the moment it asks`() {
		assertTrue("the vault is not read late", "create(app) { runCatching { vault.account?.username }.getOrNull() }" in job)
	}

	@Test
	fun `nothing behind the job can sign or send`() {
		listOf(
			"SnapPublisher", "SnapHivePort", "HiveSnapPort", "HiveSnapLikePort", "SnapLikePort", "SnapEditor",
			"SnapDeleter", "HiveBroadcaster", "broadcast", "loadKey", "privacySecret", "BroadcastQueue",
			"FinalizationRuntime", "custom_json", "SnapPostController", "SnapThreadController", "SnapLikeController",
		).forEach { assertFalse("the background reply job names `$it`", it in job) }
	}

	@Test
	fun `foreground and background build the same check, and the screen follows both`() {
		assertFalse("the screen builds its own copy of the check", "SnapReplyCheck(" in activity)
		assertTrue("SnapReplyChecks.create(applicationContext) { account?.username }" in activity)
		assertTrue("a background result is not shown on an open screen", "SnapReplyResults.results.collect { onReplyCheck(it) }" in activity)
		assertTrue(
			"the schedule does not follow the signed-in account",
			activity.contains(
				Regex("""LaunchedEffect\(account\?\.username\) \{[\s\S]{0,900}?SnapReplySchedule\.Platform\(applicationContext\)\.ensure\(signedIn\)"""),
			),
		)
	}
}
