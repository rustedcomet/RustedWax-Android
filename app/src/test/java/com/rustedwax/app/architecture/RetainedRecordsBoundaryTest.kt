package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #41: the History and Not Logged migration and restore path persists
 * rows and does nothing else.
 *
 * These files may name the two row types and the stores; they may not name a
 * broadcaster, the retry queue, a signer, the dispatcher, the runtime's row
 * writers or any Snap write path. So migrating or restoring rows cannot
 * create a playback event, enqueue a scrobble or reach Hive by construction.
 */
class RetainedRecordsBoundaryTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private val files = listOf(
		"RetainedRecords.kt",
		"RetainedRecordRows.kt",
		"RetainedRecordImport.kt",
		"DatabaseRetainedRecords.kt",
		"SqliteRetainedRecordRows.kt",
	).map { File(root, "app/src/main/java/com/rustedwax/app/scrobble/$it") }

	/** Code lines only: prose in KDoc may name what the code must not touch. */
	private fun violations(forbidden: Regex): List<String> = buildList {
		for (file in files) {
			check(file.isFile) { "missing ${file.name}" }
			file.readLines().forEachIndexed { index, line ->
				val code = line.trim()
				val comment = code.startsWith("*") || code.startsWith("/*") || code.startsWith("//")
				if (!comment && forbidden.containsMatchIn(code.substringBefore("//"))) {
					add("${file.name}:${index + 1}: $code")
				}
			}
		}
	}

	@Test
	fun `names no Hive, queue, signer, dispatcher or Snap write path`() {
		val forbidden = Regex(
			"\\b(com\\.rustedwax\\.hive|HiveRpc|HiveKey|Broadcaster|BroadcastQueue|RetryQueue|" +
				"ScrobbleDispatcher|KeyVault|PostingIdentity|QueueWakeup|DedupLedger|DedupClaims|" +
				"SnapPublisher|PendingSnap\\w*|HiveSnapPort|enqueue|broadcast\\w*)\\b",
		)
		assertEquals(emptyList<String>(), violations(forbidden))
	}

	@Test
	fun `reaches the runtime only for its two row types and its restore`() {
		// `FinalizationRuntime.ScrobbleRecord`/`SkipRecord` are data; any other
		// member — a writer, the queue, a flush — is out of bounds.
		val runtimeMember = Regex("FinalizationRuntime\\.(?!ScrobbleRecord\\b|SkipRecord\\b)\\w+")
		assertEquals(emptyList<String>(), violations(runtimeMember))
	}

	@Test
	fun `files no playback event`() {
		val writers = Regex("\\b(note|skip|finalize\\w*|flushQueue|drainQueue)\\s*\\(")
		assertEquals(emptyList<String>(), violations(writers))
	}
}
