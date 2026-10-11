package com.rustedwax.app.snaps

import android.os.Process
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rustedwax.app.MainActivity
import com.rustedwax.hive.HiveCommentRead
import com.rustedwax.hive.HiveCommentState
import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.SnapContainerResolver
import com.rustedwax.hive.TxSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Issue #56, 3A, on a real device: an unsettled Edit or Delete survives a real
 * `MainActivity` recreation in the same process.
 *
 * Synthetic throughout. The port below is a fake: it signs nothing, sends
 * nothing and reaches no network, and the account and comments are random
 * names no key exists for. What is real is the Android lifecycle — the
 * Activity is destroyed and rebuilt by the platform — and the production
 * [SnapWriteGuards.process], [SnapEditor] and [SnapDeleter] that the rebuilt
 * Activity's controllers are constructed with.
 *
 * Only slots this test created are cleared afterwards.
 */
@RunWith(AndroidJUnit4::class)
class SnapWriteGuardsLifecycleDeviceTest {

	private val suffix = UUID.randomUUID().toString().filter { it in 'a'..'z' }.take(6).padEnd(6, 'q')
	private val account = "rwsyn$suffix"
	private val otherAccount = "rwsyo$suffix"
	private val tail = "\n\nhttps://youtu.be/rTKpYJ80OVQ\n\n#scrobblelife #scrobble #rustedwax"

	private val rootA = SnapReplyTarget.of(account, "rustedwax-snap-1-$suffix")!!
	private val replyB = SnapReplyTarget.of(account, "rustedwax-reply-2-$suffix")!!
	private val replyC = SnapReplyTarget.of(account, "rustedwax-reply-3-$suffix")!!
	private val created = listOf(rootA, replyB, replyC)

	private val context: android.content.Context = ApplicationProvider.getApplicationContext()
	private val guards = SnapWriteGuards.process(context)

	/** A chain with no network: every transaction stays includable until a test says otherwise. */
	private inner class FakeChain : SnapHivePort {
		val comments = ConcurrentHashMap<String, SnapChainComment>()
		val evidence = ConcurrentHashMap<String, HiveRpc.TransactionEvidence>()
		val signed: MutableList<String> = Collections.synchronizedList(mutableListOf())
		@Volatile var result: HiveRpc.BroadcastResult = HiveRpc.BroadcastResult.AcceptedUnconfirmed("x", "fake", "synthetic")
		@Volatile var pauseOn: String? = null
		val paused = CountDownLatch(1)
		val resume = CountDownLatch(1)
		private var n = 0

		fun put(target: SnapReplyTarget, body: String, root: Boolean) {
			comments[target.contentId] = SnapChainComment(
				target.author, target.permlink,
				if (root) "peak.snaps" else account,
				if (root) "snap-container-1789648560" else rootA.permlink,
				"", body, "{}",
			)
		}

		@Synchronized private fun next(prefix: String) = "synthetic-$prefix-${++n}".also { signed += it }

		override fun resolveContainer(): SnapContainerResolver.Result = error("synthetic: never used")
		override fun prepareComment(operation: TxSerializer.CommentOp, author: String) =
			HivePreparationResult.Ready(PreparedHiveTransaction("{\"synthetic\":true}", next("edit"), 2_000_000_060L))
		override fun prepareDelete(operation: TxSerializer.DeleteCommentOp, author: String) =
			HivePreparationResult.Ready(PreparedHiveTransaction("{\"synthetic\":true}", next("del"), 2_000_000_060L))
		override fun broadcastPrepared(prepared: PreparedHiveTransaction, author: String) = result
		override fun observeTransaction(txId: String, expirationEpochSec: Long): HiveRpc.TransactionEvidence {
			if (txId == pauseOn && Thread.currentThread().name == "old-activity") {
				paused.countDown()
				resume.await(10, TimeUnit.SECONDS)
			}
			return evidence[txId] ?: HiveRpc.TransactionEvidence.UNAVAILABLE
		}
		override fun contentExists(author: String, permlink: String): Boolean? = comments.containsKey("$author/$permlink")
		override fun readComment(author: String, permlink: String) = comments["$author/$permlink"]
		override fun readCommentState(author: String, permlink: String, limit: Int): List<HiveCommentRead> =
			(0 until minOf(limit, 2)).map { i ->
				comments["$author/$permlink"]?.let {
					HiveCommentRead.Present(
						HiveCommentState(it.author, it.permlink, it.parentAuthor, it.parentPermlink, 0, 0, 4_000_000_000L, 2_000_000_000L),
						"fake$i",
					)
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

	private fun confirmed(target: SnapReplyTarget, body: String) = PendingSnap(
		account = account, eventId = "synthetic-event", author = target.author, permlink = target.permlink,
		parentAuthor = "peak.snaps", parentPermlink = "snap-container-1789648560", body = body,
		jsonMetadata = "{}", signedTransactionJson = "{}", txId = "synthetic-original", expirationEpochSec = 1L,
		state = PendingSnapState.CONFIRMED, createdAtEpochSec = 1L, updatedAtEpochSec = 1L, kind = PendingSnapKind.ROOT,
	)

	/** What MainActivity builds: production editor and deleter bound to the process holder. */
	private fun editor(chain: FakeChain, store: Store) = SnapEditor(chain, store, guards = SnapWriteGuards.process(context))
	private fun deleter(chain: FakeChain, store: Store) =
		SnapDeleter(chain, store, sleep = {}, settleAttempts = 1, guards = SnapWriteGuards.process(context))

	private fun log(msg: String) = Log.i("Issue56Device", msg)

	@After
	fun clearOnlyWhatThisTestCreated() {
		for (who in listOf(account, otherAccount)) for (t in created) {
			guards.current(who, t)?.let { guards.release(who, t, it.id) }
		}
	}

	@Test
	fun unsettledAttemptsSurviveARealActivityRecreationInTheSameProcess() {
		val chain = FakeChain().apply {
			put(rootA, "root words$tail", root = true)
			put(replyB, "reply words", root = false)
			put(replyC, "other words", root = false)
		}
		val store = Store()

		ActivityScenario.launch(MainActivity::class.java).use { scenario ->
			var first: MainActivity? = null
			scenario.onActivity { first = it }
			val pid = Process.myPid()
			val holder = SnapWriteGuards.process(context)

			// While the first Activity lives: an uncertain Delete of the root, an uncertain Edit of a reply.
			val oldDeleter = deleter(chain, store)
			val oldEditor = editor(chain, store)
			assertTrue(oldDeleter.delete(account, rootA) is SnapDeleter.Outcome.Uncertain)
			assertTrue(oldEditor.edit(account, replyB, SnapEditKind.REPLY, "first edit") is SnapEditor.Outcome.Uncertain)
			val originalDelete = (guards.current(account, rootA) as SnapWriteGuards.DeleteAttempt).prepared.txId
			val originalEdit = (guards.current(account, replyB) as SnapWriteGuards.EditAttempt).prepared.txId
			assertEquals(listOf(originalDelete, originalEdit), chain.signed)

			// The platform destroys and rebuilds the Activity.
			scenario.recreate()
			var second: MainActivity? = null
			scenario.onActivity { second = it }
			assertNotSame("a new Activity instance", first, second)
			assertTrue("the old Activity is destroyed", first!!.isDestroyed)
			assertEquals("the same Linux process", pid, Process.myPid())
			assertSame("the same process holder", holder, SnapWriteGuards.process(context))
			assertEquals(Lifecycle.State.RESUMED, scenario.state)
			log("recreated: activity ${System.identityHashCode(first)} -> ${System.identityHashCode(second)}, pid $pid, holder ${System.identityHashCode(holder)}")

			// Controllers the new Activity builds see both originals and sign nothing new.
			val newDeleter = deleter(chain, store)
			val newEditor = editor(chain, store)
			assertEquals(SnapEditor.Outcome.Failed(SnapEditor.DELETE_UNSETTLED), newEditor.edit(account, rootA, SnapEditKind.ROOT, "x"))
			assertTrue(newDeleter.delete(account, rootA) is SnapDeleter.Outcome.Uncertain) // settles the original, no new tx
			assertEquals(SnapDeleter.Outcome.Blocked(SnapDeleter.EDIT_UNSETTLED), newDeleter.delete(account, replyB))
			assertTrue(newEditor.edit(account, replyB, SnapEditKind.REPLY, "second edit") is SnapEditor.Outcome.Uncertain)
			assertEquals("only the originals were ever signed", listOf(originalDelete, originalEdit), chain.signed)
			assertEquals(originalDelete, (guards.current(account, rootA) as SnapWriteGuards.DeleteAttempt).prepared.txId)
			assertEquals(originalEdit, (guards.current(account, replyB) as SnapWriteGuards.EditAttempt).prepared.txId)

			// Other targets and accounts stay free.
			guards.reserve(account, replyC, SnapWriteGuards.Operation.EDIT)!!.let { guards.release(account, replyC, it.id) }
			guards.reserve(otherAccount, rootA, SnapWriteGuards.Operation.DELETE)!!.let { guards.release(otherAccount, rootA, it.id) }

			// Evidence releases only the rightful attempt; then the intended action proceeds.
			chain.evidence[originalEdit] = HiveRpc.TransactionEvidence.BLOCK
			assertTrue(newEditor.edit(account, replyB, SnapEditKind.REPLY, "anything") is SnapEditor.Outcome.Edited)
			assertTrue("the root delete is untouched", guards.current(account, rootA) is SnapWriteGuards.DeleteAttempt)
			chain.evidence[originalDelete] = HiveRpc.TransactionEvidence.ABSENT
			assertTrue(newDeleter.delete(account, rootA) is SnapDeleter.Outcome.Failed)
			chain.result = HiveRpc.BroadcastResult.Success("x", "fake", HiveRpc.BroadcastResult.Evidence.BLOCK)
			assertTrue(newEditor.edit(account, rootA, SnapEditKind.ROOT, "new root words") is SnapEditor.Outcome.Edited)
			assertEquals(3, chain.signed.size)
			log("released by evidence; signed ${chain.signed}")
		}
	}

	@Test
	fun aStaleCompletionFromTheDestroyedActivityCannotOverwriteTheNewerBody() {
		val chain = FakeChain().apply { put(rootA, "first words$tail", root = true) }
		val store = Store().apply { write(confirmed(rootA, "first words$tail")) }

		ActivityScenario.launch(MainActivity::class.java).use { scenario ->
			val pid = Process.myPid()
			val oldEditor = editor(chain, store)
			assertTrue(oldEditor.edit(account, rootA, SnapEditKind.ROOT, "older words") is SnapEditor.Outcome.Uncertain)
			val tx1 = chain.signed.single()

			// The old Activity's re-check is mid-read when the platform rebuilds the Activity.
			chain.pauseOn = tx1
			val oldThread = Thread { oldEditor.edit(account, rootA, SnapEditKind.ROOT, "older words") }
				.apply { name = "old-activity"; start() }
			assertTrue(chain.paused.await(10, TimeUnit.SECONDS))
			scenario.recreate()
			assertEquals(pid, Process.myPid())

			val newEditor = editor(chain, store)
			chain.evidence[tx1] = HiveRpc.TransactionEvidence.BLOCK
			chain.put(rootA, "older words$tail", root = true)
			assertTrue(newEditor.edit(account, rootA, SnapEditKind.ROOT, "older words") is SnapEditor.Outcome.Edited)
			chain.result = HiveRpc.BroadcastResult.Success("x", "fake", HiveRpc.BroadcastResult.Evidence.BLOCK)
			assertTrue(newEditor.edit(account, rootA, SnapEditKind.ROOT, "newest words") is SnapEditor.Outcome.Edited)

			chain.resume.countDown()
			oldThread.join(10_000)

			val body = (store.read(account, "synthetic-event") as PendingSnapRead.Present).snap.body
			assertEquals("the newer confirmed body stands", "newest words$tail", body)
			assertEquals(2, chain.signed.size)
			log("stale completion ignored; stored body ok; signed ${chain.signed}")
		}
	}
}
