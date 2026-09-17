package com.rustedwax.app.snaps

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural proof that Snap writes and scrobble writes stay apart.
 *
 * Asserted against the source rather than behaviour because the property is an
 * *absence*: no test can observe a Snap failing to enter the retry queue, but a
 * test can observe that the code which would put it there does not exist. A
 * scrobble may be rebuilt and resent at will; a Snap rebuilt and resent is a
 * duplicate permanent comment, so the two must never share a path.
 */
class SnapQueueIsolationTest {

	private val root: File = generateSequence(
		File(checkNotNull(System.getProperty("user.dir"))).absoluteFile,
	) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

	private fun text(path: String) = File(root, path).readText()

	/**
	 * Source with comments removed.
	 *
	 * The bans below are on what the code *does*, not on what it is allowed to
	 * explain. The prose in these files names the scrobble queue precisely
	 * because staying out of it is the point, and a check that could not tell
	 * those apart would push the explanation out of the code to stay green.
	 */
	private fun code(source: String): String = source
		.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
		.lines().joinToString("\n") { it.substringBefore("//") }

	private val snapSources: List<Pair<String, String>> =
		File(root, "app/src/main/java/com/rustedwax/app/snaps").walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.map { it.name to it.readText() }
			.toList()

	@Test
	fun `the snap package exists and was scanned`() {
		assertTrue(snapSources.map { it.first }.containsAll(
			listOf("SnapPayload.kt", "SnapPublisher.kt", "PendingSnap.kt"),
		))
	}

	/** Nothing in the Snap path may reach the automatic scrobble machinery. */
	@Test
	fun `snap publication never touches the scrobble queue`() {
		val forbidden = listOf(
			"BroadcastQueue",
			"PayloadBroadcaster",
			"FinalizationRuntime",
			"broadcastJson",
			"prepareJson",
			"HiveScrobblePayload.CUSTOM_JSON_ID",
		)
		snapSources.forEach { (name, src) ->
			forbidden.forEach { symbol ->
				assertTrue(
					"$name must not reference $symbol",
					!code(src).contains(symbol),
				)
			}
		}
	}

	/**
	 * Production posting goes prepare → persist → broadcastPrepared.
	 * `broadcastComment` cannot persist anything before it sends, so it must not
	 * appear on this path at all.
	 */
	@Test
	fun `production posting does not use the one-shot broadcast helper`() {
		val publisher = text("app/src/main/java/com/rustedwax/app/snaps/SnapPublisher.kt")
		assertTrue("must use prepareComment", publisher.contains("prepareComment"))
		assertTrue("must use broadcastPrepared", publisher.contains("broadcastPrepared"))
		assertTrue("must not use broadcastComment", !code(publisher).contains("broadcastComment("))
	}

	/** The Stage 2A doc wording that pointed at the wrong call is corrected. */
	@Test
	fun `the broadcaster documents the persisted path correctly`() {
		val broadcaster = text("hive/src/main/kotlin/com/rustedwax/hive/HiveBroadcaster.kt")
		val doc = broadcaster.substringAfter("fun prepareComment").let {
			broadcaster.substringBefore("fun prepareComment")
		}
		assertTrue(
			"prepareComment's contract must name broadcastPrepared",
			doc.contains("[broadcastPrepared], so an ambiguous result"),
		)
	}

	/** Conversely, the scrobble engine must not have grown a Snap dependency. */
	@Test
	fun `the scrobble engine knows nothing about Snaps`() {
		listOf(
			"app/src/main/java/com/rustedwax/app/scrobble/FinalizationRuntime.kt",
			"app/src/main/java/com/rustedwax/app/scrobble/EnginePorts.kt",
			"app/src/main/java/com/rustedwax/app/scrobble/BroadcastQueue.kt",
		).forEach { path ->
			val src = text(path)
			listOf("SnapPublisher", "PendingSnap", "SnapPayload", "CommentOp", "VoteOp")
				.forEach { symbol ->
					assertTrue("$path must not reference $symbol", !code(src).contains(symbol))
				}
		}
	}

	/** No private key material may be written into a pending record. */
	@Test
	fun `the pending record stores no key material`() {
		val src = text("app/src/main/java/com/rustedwax/app/snaps/PendingSnap.kt")
		listOf("wif", "WIF", "privateKey", "loadKey", "HiveKey").forEach {
			assertTrue("PendingSnap must not mention $it", !code(src).contains(it))
		}
	}

	/** The container is resolved live; no fixture permlink is baked in. */
	@Test
	fun `no snap container permlink is hardcoded anywhere`() {
		val all = (
			snapSources.map { it.second } +
				File(root, "hive/src/main/kotlin").walkTopDown()
					.filter { it.isFile && it.extension == "kt" }.map { it.readText() }
			)
		val offenders = all.filter { Regex("""snap-container-\d""").containsMatchIn(it) }
		assertEquals("a fixture container leaked into production code", emptyList<String>(), offenders)
	}

	// ── fixture sanitation ─────────────────────────────────────────────

	/**
	 * No complete WIF-shaped private key may appear in anything Stage 2B adds.
	 *
	 * A 51-character `5…` literal is indistinguishable at a glance from a real
	 * posting key. Tests that need to sign build their key at runtime from a
	 * published scalar instead, so nothing in the candidate can be mistaken for,
	 * or lifted as, an account credential.
	 */
	@Test
	fun `stage 2B sources contain no WIF-shaped private key`() {
		val wif = Regex("""["']5[HJK][1-9A-HJ-NP-Za-km-z]{20,}""")
		val candidate = listOf(
			"app/src/main/java/com/rustedwax/app/snaps",
			"app/src/test/java/com/rustedwax/app/snaps",
			"app/src/test/java/com/rustedwax/app/ui/snaps",
			"app/src/main/java/com/rustedwax/app/ui/snaps",
			"hive/src/main/kotlin/com/rustedwax/hive/SnapContainer.kt",
			"hive/src/test/kotlin/com/rustedwax/hive/HiveSnapVectorsTest.kt",
			"hive/src/test/kotlin/com/rustedwax/hive/SnapTestKey.kt",
			"hive/src/test/kotlin/com/rustedwax/hive/SnapContainerResolverTest.kt",
		)
		val offenders = candidate.flatMap { path ->
			val file = File(root, path)
			val files = if (file.isDirectory) {
				file.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
			} else {
				listOf(file).filter { it.isFile }
			}
			files.filter { wif.containsMatchIn(it.readText()) }.map { it.name }
		}
		assertEquals("WIF-shaped literal in the Stage 2B candidate", emptyList<String>(), offenders)
	}

	/** The signing key for the vectors is derived, not written down. */
	@Test
	fun `the vector test derives its key at runtime`() {
		val helper = text("hive/src/test/kotlin/com/rustedwax/hive/SnapTestKey.kt")
		assertTrue(helper.contains("fromScalar"))
		assertTrue(
			"the vector test must use the derived key",
			text("hive/src/test/kotlin/com/rustedwax/hive/HiveSnapVectorsTest.kt")
				.contains("SnapTestKey.key"),
		)
	}

	// ── the cancelled association work is gone ─────────────────────────

	/**
	 * History membership is the eligibility gate. Nothing on the Snap path may
	 * re-derive artist, title or media kind, or refuse a row for lacking them.
	 */
	@Test
	fun `the snap path re-derives no media classification`() {
		snapSources.forEach { (name, src) ->
			listOf("zingit", "timed_comment", "artist", "isrc").forEach { symbol ->
				assertTrue(
					"$name must not reference $symbol",
					!code(src).contains(symbol),
				)
			}
		}
		val screen = text("app/src/main/java/com/rustedwax/app/ui/MainScreen.kt")
		assertTrue(
			"the card must pass only the video id",
			code(screen).contains("SnapMedia(videoId = record.videoId)"),
		)
	}
}
