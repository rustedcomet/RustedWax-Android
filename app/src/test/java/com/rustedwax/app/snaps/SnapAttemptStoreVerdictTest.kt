package com.rustedwax.app.snaps

import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.SnapContainerResolver
import com.rustedwax.hive.TxSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1: Android's SharedPreferences loader answers a malformed file with an empty
 * map and only logs the parse failure. The attempt store must not mistake that
 * for an empty store. Byte layouts below are Android's own preferences format.
 */
class SnapAttemptStoreVerdictTest {

	private fun verdict(loaded: Map<String, *>, file: String?) =
		SharedPreferencesSnapAttemptStore.verdict(loaded, file?.toByteArray(Charsets.UTF_8))

	private val header = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
	private val key = "rwsynabcdef|rwsynabcdef/rustedwax-reply-1-abcdef"
	private val row = """{"v":1,"op":"edit","txId":"synthetic-1"}"""
	private val rowXml = row.replace("\"", "&quot;")

	// ── stores that are legitimately usable ────────────────────────────

	@Test
	fun `no file at all is a new, empty store`() {
		assertEquals(emptyMap<String, String>(), verdict(emptyMap<String, Any>(), null))
	}

	@Test
	fun `a valid empty map is an empty store`() {
		assertEquals(emptyMap<String, String>(), verdict(emptyMap<String, Any>(), "$header<map />\n"))
		assertEquals(emptyMap<String, String>(), verdict(emptyMap<String, Any>(), "$header<map></map>\n"))
	}

	@Test
	fun `valid stored attempts are returned exactly`() {
		val other = "rwsynabcdef|rwsynabcdef/rustedwax-snap-2-abcdef"
		val file = "$header<map>\n    <string name=\"$key\">$rowXml</string>\n    <string name=\"$other\">x</string>\n</map>\n"
		assertEquals(mapOf(key to row, other to "x"), verdict(mapOf(key to row, other to "x"), file))
	}

	@Test
	fun `what Android recovered from a valid backup is trusted, because the file now agrees with it`() {
		// Android renames <file>.bak over the main file before parsing; by the time
		// the store reads the file, it is the recovered one.
		val recovered = "$header<map>\n    <string name=\"$key\">$rowXml</string>\n</map>\n"
		assertEquals(mapOf(key to row), verdict(mapOf(key to row), recovered))
	}

	@Test
	fun `a non-string value still comes back as an unreadable row, which locks its comment`() {
		val file = "$header<map>\n    <int name=\"$key\" value=\"7\" />\n</map>\n"
		assertEquals(mapOf(key to ""), verdict(mapOf(key to 7), file))
	}

	// ── stores that must read as unreadable ────────────────────────────

	@Test
	fun `the physically reproduced malformed file is unreadable, not empty`() {
		assertNull(verdict(emptyMap<String, Any>(), "<map><rw-b1-broken"))
	}

	@Test
	fun `a truncated attempt file Android dropped to empty is unreadable`() {
		val truncated = "$header<map>\n    <string name=\"$key\">$rowXml"
		assertNull(verdict(emptyMap<String, Any>(), truncated))
	}

	@Test
	fun `garbage, an empty file, another root or a doctype are unreadable`() {
		listOf("not xml at all", "", "$header<root />", "$header<!DOCTYPE map [ ]><map />", "$header<map>").forEach {
			assertNull("read '$it'", verdict(emptyMap<String, Any>(), it))
		}
	}

	@Test
	fun `a file whose entries disagree with what Android loaded is unreadable`() {
		val file = "$header<map>\n    <string name=\"$key\">$rowXml</string>\n</map>\n"
		assertNull("entries on disk that Android did not return", verdict(emptyMap<String, Any>(), file))
		assertNull("different names", verdict(mapOf("someone|else/p" to row), file))
	}

	// ── the guard over a malformed file ────────────────────────────────

	@Test
	fun `a guard restored from a malformed file locks every comment and nothing is signed`() {
		val malformed = object : SnapAttemptStore {
			override fun load() = verdict(emptyMap<String, Any>(), "<map><rw-b1-broken")
			override fun save(key: String, value: String) = error("nothing may be saved over the evidence")
			override fun remove(key: String) = error("nothing may be removed")
		}
		var signatures = 0
		val port = object : SnapHivePort {
			override fun resolveContainer(): SnapContainerResolver.Result = error("unused")
			override fun prepareComment(operation: TxSerializer.CommentOp, author: String): HivePreparationResult {
				signatures++
				return HivePreparationResult.Ready(PreparedHiveTransaction("{}", "replacement", 1L))
			}
			override fun prepareDelete(operation: TxSerializer.DeleteCommentOp, author: String): HivePreparationResult {
				signatures++
				return HivePreparationResult.Ready(PreparedHiveTransaction("{}", "replacement", 1L))
			}
			override fun broadcastPrepared(prepared: PreparedHiveTransaction, author: String): HiveRpc.BroadcastResult =
				error("nothing may be sent")
			override fun observeTransaction(txId: String, expirationEpochSec: Long) = HiveRpc.TransactionEvidence.UNAVAILABLE
			override fun contentExists(author: String, permlink: String): Boolean? = true
			override fun readComment(author: String, permlink: String) =
				SnapChainComment(author, permlink, author, "rustedwax-snap-2-abcdef", "", "words", "{}")
		}
		val guards = SnapWriteGuards(malformed)
		val target = SnapReplyTarget.of("rwsynabcdef", "rustedwax-reply-1-abcdef")!!

		assertTrue(guards.current("rwsynabcdef", target) is SnapWriteGuards.Locked)
		assertEquals(
			SnapEditor.Outcome.Failed(SnapEditor.LOCKED),
			SnapEditor(port, guards = guards).edit("rwsynabcdef", target, SnapEditKind.REPLY, "replacement words"),
		)
		assertEquals(
			SnapDeleter.Outcome.Blocked(SnapDeleter.LOCKED),
			SnapDeleter(port, sleep = {}, guards = guards).delete("rwsynabcdef", target),
		)
		assertEquals("zero replacement signatures", 0, signatures)
	}
}
