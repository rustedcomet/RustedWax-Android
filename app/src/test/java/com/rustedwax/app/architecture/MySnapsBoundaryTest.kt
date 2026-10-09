package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #47 Stage 47A: the My Snaps catalog is presentation state.
 *
 * Its file may *read* root publication records — that is how it learns which
 * Snaps are proven on chain — but it may not name a signer, a publisher, an
 * editor, a deleter, a chain reader or the broadcast queue, and it may not
 * hold a pending store or call anything that writes one. So no row in the
 * catalog can authorize, retry or block a Hive operation, and opening My
 * Snaps cannot reach the network.
 */
class MySnapsBoundaryTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private val file get() = File(root, "app/src/main/java/com/rustedwax/app/ui/snaps/MySnaps.kt")

	private fun code(): List<String> = file.readLines().mapIndexedNotNull { index, line ->
		val code = line.trim()
		val comment = code.startsWith("*") || code.startsWith("/*") || code.startsWith("//")
		if (comment) null else "${index + 1}: ${code.substringBefore("//")}"
	}

	@Test
	fun `names no Hive, signer, writer, chain reader or queue`() {
		val forbidden = Regex(
			"\\b(com\\.rustedwax\\.hive|HiveRpc|HiveKey|KeyVault|Broadcaster|BroadcastQueue|RetryQueue|" +
				"SnapPublisher|PendingSnapStore|SharedPreferencesPendingSnapStore|SnapReplyIntent|" +
				"HiveSnapPort|HivePostedSnapReader|HiveSnapThreadReader|PostedSnaps|SnapEditor|SnapDeleter|" +
				"SnapLikeController|SnapThreadController|SnapPostController|FinalizationRuntime|" +
				"enqueue|broadcast\\w*)\\b",
		)
		assertEquals(emptyList<String>(), code().filter { forbidden.containsMatchIn(it) })
	}

	@Test
	fun `reads records through a function and never writes or clears one`() {
		assertTrue(
			"the catalog no longer takes its records read-only",
			file.readText().contains("private val confirmedRoots: (account: String) -> List<PendingSnap>"),
		)
		val writes = Regex("\\.(write|clear|commit|apply)\\(")
		// The only permitted ones: the catalog's own transaction and the page's own list.
		val allowed = listOf("database.write", "loaded.clear()")
		assertEquals(emptyList<String>(), code().filter { line -> writes.containsMatchIn(line) && allowed.none { it in line } })
	}

	@Test
	fun `the activity hands the catalog only a read of the root store`() {
		val activity = File(root, "app/src/main/java/com/rustedwax/app/MainActivity.kt").readText()
		assertTrue(activity.contains("confirmedRoots = { who -> pendingSnaps.all(who) }"))
		assertTrue(
			"a proven root deletion does not reach My Snaps",
			Regex("onRootDeleted = \\{[^}]*mySnaps\\.applyDelete\\(", RegexOption.DOT_MATCHES_ALL)
				.containsMatchIn(activity),
		)
	}

	@Test
	fun `only the root deleter records a tombstone, and replies are deleted exactly as before`() {
		val activity = File(root, "app/src/main/java/com/rustedwax/app/MainActivity.kt").readText()
		val rootWiring = activity.substringAfter("val rootDeleter = SnapDeleter(").substringBefore("val replyDeleter")
		assertTrue(
			"the root deleter does not reach the tombstone before retirement",
			"beforeRetire = {" in rootWiring && "mySnaps.recordProvenDeletion(" in rootWiring,
		)
		assertTrue(
			"the reply deleter changed",
			// Issue #56 3A adds only the process-wide write guard every editor and deleter shares.
			activity.contains("val replyDeleter = SnapDeleter(hive = editPort, store = pendingReplies, guards = writeGuards)"),
		)
		assertEquals(1, Regex("beforeRetire =").findAll(activity).count())
	}

	// ── Stage 47B: discovery reads and nothing else ────────────────────────

	private val discoveryFile get() = File(root, "app/src/main/java/com/rustedwax/app/ui/snaps/MySnapsDiscovery.kt")

	@Test
	fun `discovery names no signer, broadcaster, writer or key`() {
		val forbidden = Regex(
			"\\b(HiveKey|KeyVault|Broadcaster|BroadcastQueue|RetryQueue|SnapPublisher|PendingSnap\\w*|" +
				"HiveSnapPort|SnapEditor|SnapDeleter|SnapLike\\w*|HiveSnapLikePort|TxSerializer|" +
				"PreparedHiveTransaction|broadcast\\w*|prepare\\w*|sign\\w*|FinalizationRuntime)\\b",
		)
		val bad = discoveryFile.readLines().mapIndexedNotNull { i, line ->
			val code = line.trim()
			val comment = code.startsWith("*") || code.startsWith("/*") || code.startsWith("//")
			if (!comment && forbidden.containsMatchIn(code.substringBefore("//"))) "${i + 1}: $code" else null
		}
		assertEquals(emptyList<String>(), bad)
	}

	@Test
	fun `the activity feeds discovery only Hive reads`() {
		val activity = File(root, "app/src/main/java/com/rustedwax/app/MainActivity.kt").readText()
		val wiring = activity.substringAfter("discovery = MySnapsDiscovery(").substringBefore("\n\t\t\t)\n\t\t}")
		assertTrue(wiring.contains(".getAccountPosts("))
		assertTrue(wiring.contains("MySnapsIdentity.absence(HiveRpc().readCommentState("))
		listOf("broadcast", "loadKey", "vault", "prepare", "Publisher").forEach {
			assertFalse("discovery wiring reaches $it", wiring.contains(it))
		}
	}
}
