package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #42 Stage 1: the reply check reads and announces, and can do nothing
 * else.
 *
 * It is handed a thread reader, the notice store and the notifier. It may not
 * name a key, a signer or publisher, a Hive client, the scrobble queue, or any
 * scheduling mechanism — so running it can never end in a Hive transaction or
 * start background work on its own. And it is the existing rule that decides
 * who is notified, not a copy of it.
 */
class SnapReplyCheckBoundaryTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private fun text(path: String) = File(root, path).readText()

	private val file get() = File(root, "app/src/main/java/com/rustedwax/app/ui/snaps/SnapReplyCheck.kt")

	private fun violations(forbidden: Regex): List<String> = buildList {
		file.readLines().forEachIndexed { index, line ->
			val code = line.trim()
			val comment = code.startsWith("*") || code.startsWith("/*") || code.startsWith("//")
			if (!comment && forbidden.containsMatchIn(code.substringBefore("//"))) add("${index + 1}: $code")
		}
	}

	@Test
	fun `names no Hive client, key, publisher, queue or scheduler`() {
		val forbidden = Regex(
			"\\b(com\\.rustedwax\\.hive|HiveRpc|HiveKey|KeyVault|loadKey|SnapPublisher|PendingSnap\\w*|HiveSnapPort|" +
				"HiveSnapLikePort|SnapEditor|SnapDeleter|BroadcastQueue|FinalizationRuntime|broadcast\\w*|" +
				"WorkManager|AlarmManager|JobScheduler|JobService|Handler|Timer)\\b",
		)
		assertEquals(emptyList<String>(), violations(forbidden))
	}

	@Test
	fun `who is notified is the existing scan, and what is new is the existing store`() {
		val source = file.readText()
		assertTrue(source.contains("SnapNoticeScan.incoming(who, root, replies)"))
		assertTrue(source.contains("store.record(who, root.contentId, found,"))
		assertEquals("one place that announces", 1, Regex("""notifier\.post\(""").findAll(source).count())
	}

	@Test
	fun `the one conversation sheet is what marks a thread visible`() {
		val screen = text("app/src/main/java/com/rustedwax/app/ui/MainScreen.kt")
		assertEquals(1, Regex("""SnapThreadVisibility\.opened\(""").findAll(screen).count())
		assertEquals(1, Regex("""SnapThreadVisibility\.closed\(""").findAll(screen).count())
	}
}
