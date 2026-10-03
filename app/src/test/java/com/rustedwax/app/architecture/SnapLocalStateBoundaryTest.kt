package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #41 Slice 3: migrating and restoring Snap local state reads and
 * writes local entries and nothing else.
 *
 * These files may name the display stores they replace; they may not name a
 * signer, a publisher, a pending-write store, a reply intent, a chain reader,
 * the broadcast queue or the notice store. So no migration, restore or shadow
 * write can fetch from or write to Hive, and the Hive-write safety stores are
 * not reachable from here at all.
 */
class SnapLocalStateBoundaryTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private val files = listOf(
		"storage/LocalMirror.kt",
		"storage/LocalMirrorAdapters.kt",
		"ui/snaps/SnapLocalState.kt",
	).map { File(root, "app/src/main/java/com/rustedwax/app/$it") }

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
	fun `names no Hive, signer, publisher, pending store, chain reader, queue or notice`() {
		val forbidden = Regex(
			"\\b(com\\.rustedwax\\.hive|HiveRpc|HiveKey|KeyVault|Broadcaster|BroadcastQueue|RetryQueue|" +
				"SnapPublisher|PendingSnap\\w*|SnapReplyIntent|SnapReplyDraft\\w*|HiveSnapPort|" +
				"HivePostedSnapReader|HiveSnapThreadReader|SnapEditor|SnapDeleter|SnapNotice\\w*|" +
				"FinalizationRuntime|enqueue|broadcast\\w*)\\b",
		)
		assertEquals(emptyList<String>(), violations(forbidden))
	}

	@Test
	fun `names no legacy file but the display stores it replaces`() {
		val files = Regex("\"rustedwax_[a-z_]+\"").let { pattern ->
			this.files.flatMap { f -> pattern.findAll(f.readText()).map { it.value }.toList() }.toSet()
		}
		assertEquals(setOf("\"rustedwax_snap_drafts\"", "\"rustedwax_posted_snap_cache\""), files)
	}
}
