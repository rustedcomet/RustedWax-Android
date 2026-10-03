package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #41: the local database persists decided state and decides nothing.
 *
 * Its package may use the platform's SQLite, `java.io` and Kotlin, and nothing
 * else of this app's. In particular it cannot reach Hive signing or
 * publishing, the broadcast queue, playback or finalization, or the Snap
 * write paths — so no migration, restore or adapter built on it can cause a
 * Hive write by construction, not by convention.
 */
class LocalDatabaseBoundaryTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private val dbPackage = "com.rustedwax.app.storage.db"

	private val sources: List<File> by lazy {
		File(root, "app/src/main/java/" + dbPackage.replace('.', '/'))
			.walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.toList()
			.also { assertTrue("the local database package has no sources", it.isNotEmpty()) }
	}

	/** Code lines only: prose in KDoc may name what the code must not touch. */
	private fun violations(forbidden: Regex): List<String> = buildList {
		for (file in sources) {
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
	fun `imports only the platform database, java io and Kotlin`() {
		val allowed = Regex(
			"^import (android\\.database\\.|android\\.content\\.Context$|android\\.content\\.ContentValues$|android\\.util\\.Log$|" +
				"androidx\\.annotation\\.|java\\.io\\.|kotlin\\.)",
		)
		val bad = sources.flatMap { file ->
			file.readLines()
				.map { it.trim() }
				.filter { it.startsWith("import ") && !allowed.containsMatchIn(it) }
				.map { "${file.name}: $it" }
		}
		assertEquals(emptyList<String>(), bad)
	}

	@Test
	fun `names nothing else in this app or in the Hive module`() {
		val elsewhere = Regex("com\\.rustedwax\\.(?!app\\.storage\\.db\\b)")
		assertEquals(emptyList<String>(), violations(elsewhere))
	}

	@Test
	fun `mentions no writer, queue, signer or finalization type`() {
		val writers = Regex(
			"\\b(SnapPublisher|PendingSnap\\w*|SnapReplyIntent|HiveSnapPort|BroadcastQueue|" +
				"DedupLedger|FinalizationRuntime|ScrobbleDispatcher|KeyVault|HiveKey|" +
				"QueueWakeup|SnapEditor|SnapDeleter)\\b",
		)
		assertEquals(emptyList<String>(), violations(writers))
	}

	@Test
	fun `never deletes a database or reads a legacy store`() {
		val destructive = Regex(
			"(deleteDatabase|deleteRecursively|\\.deleteOnExit|DROP\\s+TABLE|" +
				"getSharedPreferences|SharedPreferences|filesDir)",
		)
		assertEquals(emptyList<String>(), violations(destructive))
		// The one `.delete()` allowed is removing the empty quarantine directory
		// this layer has just created and not filled.
		val deletes = violations(Regex("\\.delete\\(\\)"))
		assertTrue(deletes.toString(), deletes.all { "directory.delete()" in it })
	}
}
