package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.PendingSnap
import com.rustedwax.app.snaps.PendingSnapRead
import com.rustedwax.app.snaps.PendingSnapStore
import com.rustedwax.app.snaps.SnapChainComment
import com.rustedwax.app.snaps.SnapDeleter
import com.rustedwax.app.snaps.SnapEditKind
import com.rustedwax.app.snaps.SnapEditor
import com.rustedwax.app.snaps.SnapHivePort
import com.rustedwax.app.snaps.SnapPublisher
import com.rustedwax.app.snaps.SnapReply
import com.rustedwax.app.snaps.SnapReplyTarget
import com.rustedwax.app.snaps.SnapThreadPreview
import com.rustedwax.app.snaps.SnapThreadReader
import com.rustedwax.app.snaps.SnapWriteGuards
import com.rustedwax.hive.HiveCommentRead
import com.rustedwax.hive.HiveCommentState
import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.SnapContainer
import com.rustedwax.hive.SnapContainerResolver
import com.rustedwax.hive.TxSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Issue #56, 3A: an unsettled Edit or Delete survives Activity recreation.
 *
 * Each [Activity] is what `MainActivity` builds afresh — a new thread
 * controller, a new editor and a new deleter — but over the same chain, the
 * same stores and the same process-lifetime [SnapWriteGuards]. Broadcasts never
 * apply by themselves here: every signed transaction stays includable until a
 * test says otherwise. Every assertion counts what was actually signed.
 */
class SnapWriteGuardsRecreationTest {

	private val root = SnapReplyTarget.of("alice", "rustedwax-snap-1000-aaaaaa")!!
	private val mine = SnapReply("alice", "rustedwax-reply-1000-mine", "alice", root.permlink, "old words", 1_000L)
	private val mine2 = SnapReply("alice", "rustedwax-reply-1001-mine", "alice", root.permlink, "more words", 1_001L)
	private val bobs = SnapReply("bob", "rustedwax-reply-1002-bobs", "alice", root.permlink, "bob words", 1_002L)
	private val target get() = SnapReplyTarget.of(mine)!!

	private val tail = "\n\nhttps://youtu.be/rTKpYJ80OVQ\n\n#scrobblelife #scrobble #rustedwax"

	private class Chain : SnapHivePort {
		val states = Collections.synchronizedMap(mutableMapOf<String, HiveCommentState>())
		val comments = Collections.synchronizedMap(mutableMapOf<String, SnapChainComment>())
		@Volatile var result: HiveRpc.BroadcastResult = HiveRpc.BroadcastResult.AcceptedUnconfirmed("x", "n", "no confirmation")
		/** Evidence per transaction id; UNAVAILABLE unless a test says otherwise. */
		val evidence = Collections.synchronizedMap(mutableMapOf<String, HiveRpc.TransactionEvidence>())
		val signed: MutableList<String> = Collections.synchronizedList(mutableListOf())
		@Volatile var duringRead: (() -> Unit)? = null
		@Volatile var duringStateRead: (() -> Unit)? = null
		private var n = 0

		fun put(r: SnapReply, body: String = r.body) {
			val id = "${r.author}/${r.permlink}"
			states[id] = HiveCommentState(r.author, r.permlink, r.parentAuthor, r.parentPermlink, 0, 0, 4_000_000_000L, 2_000_000_000L)
			comments[id] = SnapChainComment(r.author, r.permlink, r.parentAuthor, r.parentPermlink, "", body, "{}")
		}

		@Synchronized private fun next(prefix: String) = "$prefix-${++n}".also { signed += it }

		override fun resolveContainer() = SnapContainerResolver.Result.Resolved(
			SnapContainer("peak.snaps", "snap-container-1789648560", "2026-09-17T12:36:00"),
		)
		override fun prepareComment(operation: TxSerializer.CommentOp, author: String): HivePreparationResult =
			HivePreparationResult.Ready(PreparedHiveTransaction("{}", next("edit"), 2_000_000_060L))
		override fun prepareDelete(operation: TxSerializer.DeleteCommentOp, author: String): HivePreparationResult =
			HivePreparationResult.Ready(PreparedHiveTransaction("{}", next("del"), 2_000_000_060L))
		override fun broadcastPrepared(prepared: PreparedHiveTransaction, author: String) = result
		override fun observeTransaction(txId: String, expirationEpochSec: Long) =
			evidence[txId] ?: HiveRpc.TransactionEvidence.UNAVAILABLE
		override fun contentExists(author: String, permlink: String): Boolean? = comments.containsKey("$author/$permlink")
		override fun readComment(author: String, permlink: String): SnapChainComment? {
			duringRead?.invoke()
			return comments["$author/$permlink"]
		}
		override fun readCommentState(author: String, permlink: String, limit: Int): List<HiveCommentRead> {
			duringStateRead?.invoke()
			return (0 until minOf(limit, 2)).map { i ->
				states["$author/$permlink"]?.let { HiveCommentRead.Present(it, "n$i") } ?: HiveCommentRead.Absent("n$i")
			}
		}
	}

	private class Reader(var replies: List<SnapReply>?) : SnapThreadReader {
		override fun read(rootAuthor: String, rootPermlink: String, viewer: String?) = replies
	}

	private class Store : PendingSnapStore {
		val saved = Collections.synchronizedMap(mutableMapOf<String, PendingSnap>())
		override fun read(account: String, eventId: String): PendingSnapRead =
			saved["$account|$eventId"]?.let(PendingSnapRead::Present) ?: PendingSnapRead.Absent
		override fun write(snap: PendingSnap): Boolean { saved["${snap.account}|${snap.eventId}"] = snap; return true }
		override fun clear(account: String, eventId: String) { saved.remove("$account|$eventId") }
		override fun all(account: String) = saved.filterKeys { it.startsWith("$account|") }.values.toList()
		override fun corruptEventIds(account: String) = emptySet<String>()
	}

	private class Drafts : SnapReplyDraftStore {
		override fun read(key: String): SnapReplyDraftRead = SnapReplyDraftRead.Absent
		override fun writeText(key: String, text: String) = Unit
		override fun beginIntent(key: String, intentId: String) = true
		override fun removeIf(key: String, expected: SnapReplyDraft?) = false
		override fun settle(key: String, published: SnapReplyDraft) = SnapReplySettlement.Untouched
	}

	private class Previews : SnapThreadPreviewStore {
		override fun read(key: String): SnapThreadPreview.Preview? = null
		override fun write(key: String, preview: SnapThreadPreview.Preview) = Unit
	}

	/** The process: one chain, one set of stores, one guard holder. */
	private inner class Process {
		val chain = Chain().apply {
			put(mine)
			put(mine2)
			put(bobs)
		}
		val store = Store()
		val guards = SnapWriteGuards()

		fun editor() = SnapEditor(chain, store, guards = guards)
		fun deleter() = SnapDeleter(chain, store, sleep = {}, settleAttempts = 2, guards = guards)
	}

	/** One Activity's worth of wiring, as `MainActivity.Wired` builds it again after recreation. */
	private inner class Activity(val p: Process, var who: String? = "alice") {
		val deleter = p.deleter()
		val editor = p.editor()
		val threads = SnapThreadController(
			scope = CoroutineScope(Dispatchers.Unconfined),
			reader = { Reader(listOf(mine, mine2, bobs)) },
			publisher = { SnapPublisher(p.chain, p.store, nowEpochSec = { 1_000L }) },
			account = { who },
			drafts = Drafts(),
			previewStore = Previews(),
			editor = { editor },
			deleter = { deleter },
			io = Dispatchers.Unconfined,
		)
		init { threads.open(root, null) }

		fun tryEdit(r: SnapReply, words: String) {
			val t = SnapReplyTarget.of(r)!!
			threads.startEdit(root, t, SnapEditKind.REPLY, r.body)
			if (threads.editing == null) return
			threads.editDraft(words)
			threads.saveEdit()
		}

		fun tryDelete(r: SnapReply) {
			val t = SnapReplyTarget.of(r)!!
			threads.requestDelete(root, t, SnapEditKind.REPLY, r.body)
			threads.confirmDelete()
		}
	}

	// ── 1. uncertain Delete → recreation → Edit and a second Delete ────

	@Test
	fun `an uncertain Delete still blocks Edit and a second Delete after recreation`() {
		val p = Process()
		Activity(p).tryDelete(mine)
		assertEquals(listOf("del-1"), p.chain.signed)

		val b = Activity(p)
		b.tryEdit(mine, "new words")
		b.tryDelete(mine)
		b.tryDelete(mine)

		assertEquals("only the original was ever signed", listOf("del-1"), p.chain.signed)
		assertTrue(b.deleter.hasUnsettled("alice", target))
	}

	// ── 2. uncertain Edit → recreation → Delete and a second Edit ──────

	@Test
	fun `an uncertain Edit still blocks Delete and a second Edit after recreation`() {
		val p = Process()
		Activity(p).tryEdit(mine, "first edit")
		assertEquals(listOf("edit-1"), p.chain.signed)

		val b = Activity(p)
		b.threads.requestDelete(root, target, SnapEditKind.REPLY, mine.body)
		assertEquals("no confirmation is even offered", null, b.threads.deleting)
		b.tryDelete(mine)
		b.tryEdit(mine, "second edit")

		assertEquals("only the original was ever signed", listOf("edit-1"), p.chain.signed)
		assertTrue("the Delete was refused, not silently dropped", b.threads.deleteNotice(target) is SnapPostStatus.Failed)
	}

	// ── 3. account switch and return; other targets and accounts free ──

	@Test
	fun `the lock survives switching account and back, and leaves other targets and accounts free`() {
		val p = Process()
		Activity(p).tryDelete(mine)

		// Recreated while signed in as bob: bob's own reply is free.
		Activity(p, who = "bob").tryEdit(bobs, "bob edits")
		// Back as alice, recreated again: her locked reply stays locked, her other one is free.
		val c = Activity(p)
		c.tryEdit(mine, "new words")
		c.tryEdit(mine2, "other words")

		assertEquals(listOf("del-1", "edit-2", "edit-3"), p.chain.signed)
		assertTrue(c.deleter.hasUnsettled("alice", target))
		assertFalse(c.deleter.hasUnsettled("bob", target))
	}

	// ── 4. proof resolves the original, then the intended action proceeds ─

	@Test
	fun `a recreated Activity settles the original Delete by proof, then Edit may proceed`() {
		val p = Process()
		Activity(p).tryDelete(mine)
		val b = Activity(p)

		p.chain.evidence["del-1"] = HiveRpc.TransactionEvidence.ABSENT
		b.tryDelete(mine) // settles del-1 by reading; signs nothing
		assertEquals(listOf("del-1"), p.chain.signed)
		assertFalse(b.deleter.hasUnsettled("alice", target))

		p.chain.result = HiveRpc.BroadcastResult.Success("x", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)
		b.tryEdit(mine, "new words")
		assertEquals(listOf("del-1", "edit-2"), p.chain.signed)
	}

	@Test
	fun `a recreated Activity settles the original Edit by inclusion, then Delete may proceed`() {
		val p = Process()
		Activity(p).tryEdit(mine, "first edit")
		val b = Activity(p)

		p.chain.evidence["edit-1"] = HiveRpc.TransactionEvidence.BLOCK
		b.tryDelete(mine)
		assertEquals("Delete waits for the edit to be settled", listOf("edit-1"), p.chain.signed)

		b.tryEdit(mine, "anything") // settles edit-1 by its own inclusion; signs nothing
		assertEquals(listOf("edit-1"), p.chain.signed)

		b.tryDelete(mine)
		assertEquals(listOf("edit-1", "del-2"), p.chain.signed)
	}

	// ── 5. simultaneous Edit and Delete never both sign ────────────────

	@Test
	fun `an Edit and a Delete started together on two instances sign once between them`() {
		val p = Process()
		val inRead = CountDownLatch(1)
		val go = CountDownLatch(1)
		p.chain.duringRead = {
			p.chain.duringRead = null
			inRead.countDown()
			go.await(5, TimeUnit.SECONDS)
		}
		val editor = p.editor()
		val deleter = p.deleter()
		var edit: SnapEditor.Outcome? = null
		val editing = Thread { edit = editor.edit("alice", target, SnapEditKind.REPLY, "new words") }.apply { start() }
		assertTrue(inRead.await(5, TimeUnit.SECONDS))

		// The edit holds the slot while it reads; the delete must not sign.
		val delete = deleter.delete("alice", target)
		go.countDown()
		editing.join(5_000)

		assertTrue("got $delete", delete is SnapDeleter.Outcome.Blocked)
		assertNotNull(edit)
		assertEquals(listOf("edit-1"), p.chain.signed)
	}

	@Test
	fun `a Delete and an Edit started together the other way round also sign once`() {
		val p = Process()
		val inRead = CountDownLatch(1)
		val go = CountDownLatch(1)
		p.chain.duringStateRead = {
			p.chain.duringStateRead = null
			inRead.countDown()
			go.await(5, TimeUnit.SECONDS)
		}
		val editor = p.editor()
		val deleter = p.deleter()
		val deleting = Thread { deleter.delete("alice", target) }.apply { start() }
		assertTrue(inRead.await(5, TimeUnit.SECONDS))

		val edit = editor.edit("alice", target, SnapEditKind.REPLY, "new words")
		go.countDown()
		deleting.join(5_000)

		assertTrue("got $edit", edit !is SnapEditor.Outcome.Edited)
		assertEquals(listOf("del-1"), p.chain.signed)
	}

	// ── 6. a late completion from the old Activity cannot free a newer attempt ─

	@Test
	fun `a stale settle from the old Activity cannot release a newer attempt`() {
		val p = Process()
		val old = p.deleter()
		old.delete("alice", target) // del-1, uncertain

		// The old Activity's second check is mid-read when the new Activity takes over.
		val paused = CountDownLatch(1)
		val resume = CountDownLatch(1)
		val oldThread = Thread {
			p.chain.duringStateRead = {
				if (Thread.currentThread().name == "old") {
					paused.countDown()
					resume.await(5, TimeUnit.SECONDS)
				}
			}
			old.delete("alice", target)
		}.apply { name = "old"; start() }
		assertTrue(paused.await(5, TimeUnit.SECONDS))

		val fresh = p.deleter()
		p.chain.evidence["del-1"] = HiveRpc.TransactionEvidence.ABSENT
		assertTrue(fresh.delete("alice", target) is SnapDeleter.Outcome.Failed) // del-1 settled, slot freed
		assertTrue(fresh.delete("alice", target) is SnapDeleter.Outcome.Uncertain) // del-2, a new attempt
		assertEquals(listOf("del-1", "del-2"), p.chain.signed)

		// The old check now finishes with del-1's ABSENT verdict.
		resume.countDown()
		oldThread.join(5_000)

		assertTrue("del-2's lock is untouched", fresh.hasUnsettled("alice", target))
		fresh.delete("alice", target)
		assertEquals("still settling del-2, never signing a third", listOf("del-1", "del-2"), p.chain.signed)
	}

	@Test
	fun `a recreated deleter refuses plainly while another instance's Edit is unsettled`() {
		val p = Process()
		assertTrue(p.editor().edit("alice", target, SnapEditKind.REPLY, "first edit") is SnapEditor.Outcome.Uncertain)

		val deleter = p.deleter()
		assertEquals(SnapDeleter.Outcome.Blocked(SnapDeleter.EDIT_UNSETTLED), deleter.delete("alice", target))
		assertEquals(com.rustedwax.app.snaps.SnapDeleteCheck.Blocked(SnapDeleter.EDIT_UNSETTLED), deleter.check("alice", target))
		assertEquals(listOf("edit-1"), p.chain.signed)
	}

	// ── 7. root and reply targets are separate ─────────────────────────

	@Test
	fun `a root Snap's unsettled Delete blocks only that root, never a reply`() {
		val p = Process()
		val rootSnap = SnapReply("alice", root.permlink, "peak.snaps", "snap-container-1789648560", "root words$tail", 900L)
		p.chain.put(rootSnap)

		p.deleter().delete("alice", root) // root's del-1, uncertain
		val editor = p.editor()
		assertEquals(
			SnapEditor.Outcome.Failed(SnapEditor.DELETE_UNSETTLED),
			editor.edit("alice", root, SnapEditKind.ROOT, "new root words"),
		)
		assertEquals(listOf("del-1"), p.chain.signed)

		p.chain.result = HiveRpc.BroadcastResult.Success("x", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)
		assertTrue(editor.edit("alice", target, SnapEditKind.REPLY, "reply words") is SnapEditor.Outcome.Edited)
		assertEquals(listOf("del-1", "edit-2"), p.chain.signed)
	}
}
