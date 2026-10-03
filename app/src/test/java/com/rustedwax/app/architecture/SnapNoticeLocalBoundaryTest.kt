package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #41 Slice 4: the bell's durable state is storage and nothing else.
 *
 * The notice store file may name the notice model and codec it persists; it
 * may not name Hive, a signer or publisher, the queue, the notifier, Android's
 * notification APIs, or any scheduling mechanism. Migrating, restoring or
 * writing notice state therefore cannot deliver a notification, schedule
 * background work or reach Hive — delivery stays in `SnapNoticeController`.
 */
class SnapNoticeLocalBoundaryTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private val file get() = File(root, "app/src/main/java/com/rustedwax/app/ui/snaps/SnapNoticeLocal.kt")

	private fun violations(forbidden: Regex): List<String> = buildList {
		file.readLines().forEachIndexed { index, line ->
			val code = line.trim()
			val comment = code.startsWith("*") || code.startsWith("/*") || code.startsWith("//")
			if (!comment && forbidden.containsMatchIn(code.substringBefore("//"))) add("${index + 1}: $code")
		}
	}

	@Test
	fun `names no Hive, publisher, queue, notifier, notification API or scheduler`() {
		val forbidden = Regex(
			"\\b(com\\.rustedwax\\.hive|HiveRpc|HiveKey|KeyVault|SnapPublisher|PendingSnap\\w*|HiveSnapPort|" +
				"HiveSnapThreadReader|BroadcastQueue|SnapNoticeNotifier|AndroidSnapNoticeNotifier|SnapNoticeController|" +
				"NotificationManager\\w*|NotificationCompat|WorkManager|AlarmManager|JobScheduler|" +
				"FinalizationRuntime|post\\(|cancel\\(|broadcast\\w*)",
		)
		assertEquals(emptyList<String>(), violations(forbidden))
	}
}
