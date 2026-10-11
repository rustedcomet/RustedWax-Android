package com.rustedwax.app.snaps

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rustedwax.hive.HiveCommentRead
import com.rustedwax.hive.HiveCommentState
import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.SnapContainerResolver
import com.rustedwax.hive.TxSerializer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Issue #56, 3C, on a real device: unsettled Edit and Delete attempts survive
 * a real kill of the app's Linux process, restored from the real preferences
 * file by the production [SnapWriteGuards.process].
 *
 * Three phases, each a separate instrumentation run in its own process and each
 * skipped unless selected with `-e issue56Phase <phase>`, so ordinary device runs
 * never execute them: `prepare` (creates the attempts, then waits to be killed),
 * `verify` (a new process), `clean` (a further new process). Synthetic
 * throughout: random names, dummy transactions, a fake port with no network,
 * no key. Only this test's own rows and manifest are ever removed.
 */
@RunWith(AndroidJUnit4::class)
class SnapAttemptProcessDeathDeviceTest {

	private val context: Context = ApplicationProvider.getApplicationContext()
	private val phase = InstrumentationRegistry.getArguments().getString("issue56Phase")
	private val manifest = File(context.filesDir, "issue56-3c-synthetic-manifest.json")
	private val tail = "\n\nhttps://youtu.be/rTKpYJ80OVQ\n\n#scrobblelife #scrobble #rustedwax"

	private class Names(val suffix: String) {
		val account = "rwsyn$suffix"
		val otherAccount = "rwsyo$suffix"
		val editTarget = SnapReplyTarget.of(account, "rustedwax-reply-1-$suffix")!!
		val deleteTarget = SnapReplyTarget.of(account, "rustedwax-snap-2-$suffix")!!
		val corruptTarget = SnapReplyTarget.of(account, "rustedwax-reply-3-$suffix")!!
		val freeTarget = SnapReplyTarget.of(account, "rustedwax-reply-4-$suffix")!!
		val all = listOf(editTarget, deleteTarget, corruptTarget, freeTarget)
		fun key(t: SnapReplyTarget, who: String = account) = "$who|${t.contentId}"
	}

	/** No network: signs dummy bytes, answers from memory. */
	private inner class FakeChain(n: Names) : SnapHivePort {
		val comments = ConcurrentHashMap<String, SnapChainComment>()
		val evidence = ConcurrentHashMap<String, HiveRpc.TransactionEvidence>()
		val signed: MutableList<PreparedHiveTransaction> = Collections.synchronizedList(mutableListOf())
		val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())
		private var count = 0

		init {
			comments[n.editTarget.contentId] = SnapChainComment(n.account, n.editTarget.permlink, n.account, n.deleteTarget.permlink, "", "reply words", "{}")
			comments[n.deleteTarget.contentId] = SnapChainComment(n.account, n.deleteTarget.permlink, "peak.snaps", "snap-container-1", "", "root words$tail", "{}")
			comments[n.corruptTarget.contentId] = SnapChainComment(n.account, n.corruptTarget.permlink, n.account, n.deleteTarget.permlink, "", "corrupt words", "{}")
			comments[n.freeTarget.contentId] = SnapChainComment(n.account, n.freeTarget.permlink, n.account, n.deleteTarget.permlink, "", "free words", "{}")
		}

		@Synchronized private fun next(prefix: String) = PreparedHiveTransaction(
			"""{"synthetic":"$prefix-${count + 1}-${UUID.randomUUID()}"}""", "synthetic-$prefix-${++count}-${UUID.randomUUID().toString().take(8)}", 2_000_000_060L,
		).also { signed += it }

		override fun resolveContainer(): SnapContainerResolver.Result = error("synthetic: never used")
		override fun prepareComment(operation: TxSerializer.CommentOp, author: String) = HivePreparationResult.Ready(next("edit"))
		override fun prepareDelete(operation: TxSerializer.DeleteCommentOp, author: String) = HivePreparationResult.Ready(next("del"))
		override fun broadcastPrepared(prepared: PreparedHiveTransaction, author: String): HiveRpc.BroadcastResult {
			sent += prepared.txId
			return HiveRpc.BroadcastResult.AcceptedUnconfirmed(prepared.txId, "fake", "synthetic")
		}
		override fun observeTransaction(txId: String, expirationEpochSec: Long) = evidence[txId] ?: HiveRpc.TransactionEvidence.UNAVAILABLE
		override fun contentExists(author: String, permlink: String): Boolean? = comments.containsKey("$author/$permlink")
		override fun readComment(author: String, permlink: String) = comments["$author/$permlink"]
		override fun readCommentState(author: String, permlink: String, limit: Int): List<HiveCommentRead> =
			(0 until minOf(limit, 2)).map { i ->
				comments["$author/$permlink"]?.let {
					HiveCommentRead.Present(HiveCommentState(it.author, it.permlink, it.parentAuthor, it.parentPermlink, 0, 0, 4_000_000_000L, 2_000_000_000L), "fake$i")
				} ?: HiveCommentRead.Absent("fake$i")
			}
	}

	private class Store : PendingSnapStore {
		val saved = ConcurrentHashMap<String, PendingSnap>()
		override fun read(account: String, eventId: String): PendingSnapRead =
			saved["$account|$eventId"]?.let(PendingSnapRead::Present) ?: PendingSnapRead.Absent
		override fun write(snap: PendingSnap): Boolean { saved["${snap.account}|${snap.eventId}"] = snap; return true }
		override fun clear(account: String, eventId: String) { saved.remove("$account|$eventId") }
		override fun all(account: String) = saved.filterKeys { it.startsWith("$account|") }.values.toList()
		override fun corruptEventIds(account: String) = emptySet<String>()
	}

	private val prefs get() = context.getSharedPreferences(SharedPreferencesSnapAttemptStore.FILE, Context.MODE_PRIVATE)
	private fun log(msg: String) = Log.i("Issue56Device3C", msg)

	private fun editor(chain: FakeChain, guards: SnapWriteGuards) = SnapEditor(chain, Store(), guards = guards)
	private fun deleter(chain: FakeChain, guards: SnapWriteGuards) =
		SnapDeleter(chain, Store(), sleep = {}, settleAttempts = 1, guards = guards)

	// ── phase 1: create, persist, then wait to be killed ───────────────

	@Test
	fun prepare() {
		assumeTrue(phase == "prepare")
		val n = Names(UUID.randomUUID().toString().filter { it in 'a'..'z' }.take(6).padEnd(6, 'q'))
		val guards = SnapWriteGuards.process(context)
		val chain = FakeChain(n)
		assertTrue(editor(chain, guards).edit(n.account, n.editTarget, SnapEditKind.REPLY, "synthetic edit") is SnapEditor.Outcome.Uncertain)
		assertTrue(deleter(chain, guards).delete(n.account, n.deleteTarget) is SnapDeleter.Outcome.Uncertain)
		// A row this test corrupts on purpose, so the next process must lock it.
		assertTrue(prefs.edit().putString(n.key(n.corruptTarget), "{synthetic-corrupt").commit())

		val edit = guards.current(n.account, n.editTarget) as SnapWriteGuards.EditAttempt
		val delete = guards.current(n.account, n.deleteTarget) as SnapWriteGuards.DeleteAttempt
		JSONObject()
			.put("suffix", n.suffix)
			.put("pid", Process.myPid())
			.put("editRow", prefs.getString(n.key(n.editTarget), null))
			.put("deleteRow", prefs.getString(n.key(n.deleteTarget), null))
			.put("editTx", edit.prepared.txId)
			.put("editSigned", edit.prepared.signedTransactionJson)
			.put("deleteTx", delete.prepared.txId)
			.put("deleteSigned", delete.prepared.signedTransactionJson)
			.toString()
			.let { manifest.writeText(it) }
		log("PREPARED pid=${Process.myPid()} edit=${edit.prepared.txId} delete=${delete.prepared.txId} rows=${prefs.all.keys.count { it.endsWith(n.suffix) }}")
		// Wait to be killed from outside. Finishing normally is a failed run.
		Thread.sleep(120_000)
		throw AssertionError("expected to be killed while the attempts were unresolved")
	}

	// ── phase 2: a new process restores from disk before anything signs ─

	@Test
	fun verify() {
		assumeTrue(phase == "verify")
		val m = JSONObject(manifest.readText())
		val n = Names(m.getString("suffix"))
		assertNotEquals("a different Linux process", m.getInt("pid"), Process.myPid())
		assertEquals("rows are still on disk, byte for byte", m.getString("editRow"), prefs.getString(n.key(n.editTarget), null))
		assertEquals(m.getString("deleteRow"), prefs.getString(n.key(n.deleteTarget), null))

		val guards = SnapWriteGuards.process(context) // first use in this process: restores from disk
		val edit = guards.current(n.account, n.editTarget) as SnapWriteGuards.EditAttempt
		val delete = guards.current(n.account, n.deleteTarget) as SnapWriteGuards.DeleteAttempt
		assertEquals(m.getString("editTx"), edit.prepared.txId)
		assertEquals(m.getString("editSigned"), edit.prepared.signedTransactionJson)
		assertEquals("synthetic edit", edit.text)
		assertEquals(m.getString("deleteTx"), delete.prepared.txId)
		assertEquals(m.getString("deleteSigned"), delete.prepared.signedTransactionJson)
		assertTrue("the corrupt row locks its comment", guards.current(n.account, n.corruptTarget) is SnapWriteGuards.Locked)
		log("RESTORED pid=${Process.myPid()} (was ${m.getInt("pid")}) edit=${edit.prepared.txId} delete=${delete.prepared.txId}")

		val chain = FakeChain(n)
		val editor = editor(chain, guards)
		val deleter = deleter(chain, guards)
		// Repeats and cross-operations: only the originals are settled, nothing is signed.
		assertTrue(editor.edit(n.account, n.editTarget, SnapEditKind.REPLY, "another edit") is SnapEditor.Outcome.Uncertain)
		assertEquals(SnapDeleter.Outcome.Blocked(SnapDeleter.EDIT_UNSETTLED), deleter.delete(n.account, n.editTarget))
		assertEquals(SnapEditor.Outcome.Failed(SnapEditor.DELETE_UNSETTLED), editor.edit(n.account, n.deleteTarget, SnapEditKind.ROOT, "x"))
		assertTrue(deleter.delete(n.account, n.deleteTarget) is SnapDeleter.Outcome.Uncertain)
		assertEquals(SnapEditor.Outcome.Failed(SnapEditor.LOCKED), editor.edit(n.account, n.corruptTarget, SnapEditKind.REPLY, "x"))
		assertEquals(SnapDeleter.Outcome.Blocked(SnapDeleter.LOCKED), deleter.delete(n.account, n.corruptTarget))
		assertTrue("no new signature", chain.signed.isEmpty())
		assertTrue("nothing sent", chain.sent.isEmpty())

		// Unrelated target and another account are free; the lock outlasts a switch and return.
		guards.reserve(n.account, n.freeTarget, SnapWriteGuards.Operation.EDIT)!!.let { guards.release(n.account, n.freeTarget, it.id) }
		guards.reserve(n.otherAccount, n.editTarget, SnapWriteGuards.Operation.DELETE)!!.let { guards.release(n.otherAccount, n.editTarget, it.id) }
		assertTrue(guards.current(n.account, n.editTarget) is SnapWriteGuards.EditAttempt)

		// Fake proof settles exactly the originals and removes exactly their rows.
		chain.evidence[edit.prepared.txId] = HiveRpc.TransactionEvidence.BLOCK
		assertTrue(editor.edit(n.account, n.editTarget, SnapEditKind.REPLY, "anything") is SnapEditor.Outcome.Edited)
		chain.evidence[delete.prepared.txId] = HiveRpc.TransactionEvidence.ABSENT
		assertTrue(deleter.delete(n.account, n.deleteTarget) is SnapDeleter.Outcome.Failed)
		assertNull(guards.current(n.account, n.editTarget))
		assertNull(guards.current(n.account, n.deleteTarget))
		assertNull("edit row removed", prefs.getString(n.key(n.editTarget), null))
		assertNull("delete row removed", prefs.getString(n.key(n.deleteTarget), null))
		assertTrue("still nothing signed or sent", chain.signed.isEmpty() && chain.sent.isEmpty())
		assertTrue("the corrupt row is never removed by the guard", prefs.contains(n.key(n.corruptTarget)))

		// This test's own corrupt row, removed by the test, not by the guard.
		assertTrue(prefs.edit().remove(n.key(n.corruptTarget)).commit())
		log("SETTLED pid=${Process.myPid()} rows-left=${prefs.all.keys.count { it.endsWith(n.suffix) }}")
	}

	// ── phase 3: a further new process finds nothing left ──────────────

	@Test
	fun clean() {
		assumeTrue(phase == "clean")
		val m = JSONObject(manifest.readText())
		val n = Names(m.getString("suffix"))
		assertNotEquals(m.getInt("pid"), Process.myPid())
		val guards = SnapWriteGuards.process(context)
		for (t in n.all) assertNull("no leftover lock on ${t.permlink}", guards.current(n.account, t))
		assertTrue("no rows of this test remain", prefs.all.keys.none { it.endsWith(n.suffix) })
		assertTrue(manifest.delete())
		log("CLEAN pid=${Process.myPid()}")
	}
}
