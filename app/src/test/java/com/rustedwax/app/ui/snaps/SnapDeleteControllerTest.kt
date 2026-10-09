package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.PendingSnap
import com.rustedwax.app.snaps.PendingSnapKind
import com.rustedwax.app.snaps.PendingSnapRead
import com.rustedwax.app.snaps.PendingSnapState
import com.rustedwax.app.snaps.PendingSnapStore
import com.rustedwax.app.snaps.SnapChainComment
import com.rustedwax.app.snaps.SnapDeleter
import com.rustedwax.app.snaps.SnapEditKind
import com.rustedwax.app.snaps.SnapEditor
import com.rustedwax.app.snaps.SnapHivePort
import com.rustedwax.app.snaps.SnapPublisher
import com.rustedwax.app.snaps.SnapReply
import com.rustedwax.app.snaps.SnapReplyTarget
import com.rustedwax.app.snaps.SnapSend
import com.rustedwax.app.snaps.SnapThreadPreview
import com.rustedwax.app.snaps.SnapThreadReader
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
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delete, from the sheet's side: who is offered it, that the chain is read
 * before the confirmation can be accepted, that Cancel sends nothing, that a
 * failure leaves the comment on screen, and that a proven deletion takes out
 * exactly one comment even while hivemind still returns it.
 *
 * Runs unconfined so `launch` completes inline, like the other controller tests.
 */
class SnapDeleteControllerTest {

	private val root = SnapReplyTarget.of("alice", "rustedwax-snap-1000-aaaaaa")!!
	private val mine = SnapReply("alice", "rustedwax-reply-1000-mine", "alice", root.permlink, "old words", 1_000L)
	private val theirs = SnapReply("bob", "re-alice-1001", "alice", root.permlink, "bob says", 1_001L)
	private val mine2 = SnapReply("alice", "rustedwax-reply-1002-mine", "alice", root.permlink, "more words", 1_002L)

	private class Chain : SnapHivePort {
		val states = mutableMapOf<String, HiveCommentState>()
		val comments = mutableMapOf<String, SnapChainComment>()
		var result: HiveRpc.BroadcastResult =
			HiveRpc.BroadcastResult.Success("tx", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)
		val deletes = mutableListOf<TxSerializer.DeleteCommentOp>()
		val edits = mutableListOf<TxSerializer.CommentOp>()
		var broadcasts = 0
		var reads = 0
		var onRead: (() -> Unit)? = null

		fun put(r: SnapReply, children: Long = 0, net: Long = 0) {
			val id = "${r.author}/${r.permlink}"
			states[id] = HiveCommentState(
				r.author, r.permlink, r.parentAuthor, r.parentPermlink, children, net,
				4_000_000_000L, head,
			)
			comments[id] = SnapChainComment(r.author, r.permlink, r.parentAuthor, r.parentPermlink, "", r.body, "{}")
		}

		override fun resolveContainer() = SnapContainerResolver.Result.Resolved(
			SnapContainer("peak.snaps", "snap-container-1789648560", "2026-09-17T12:36:00"),
		)
		override fun prepareComment(operation: TxSerializer.CommentOp, author: String): HivePreparationResult {
			edits += operation
			return HivePreparationResult.Ready(PreparedHiveTransaction("{}", "edit-${edits.size}", 2_000_000_000L))
		}
		override fun prepareDelete(operation: TxSerializer.DeleteCommentOp, author: String): HivePreparationResult {
			deletes += operation
			return HivePreparationResult.Ready(PreparedHiveTransaction("{}", "del-${deletes.size}", 2_000_000_000L))
		}
		override fun broadcastPrepared(prepared: PreparedHiveTransaction, author: String): HiveRpc.BroadcastResult {
			broadcasts++
			if (prepared.txId.startsWith("del-") && result is HiveRpc.BroadcastResult.Success) {
				val op = deletes.last()
				states.remove("${op.author}/${op.permlink}")
				comments.remove("${op.author}/${op.permlink}")
			}
			if (prepared.txId.startsWith("edit-") && result is HiveRpc.BroadcastResult.Success) {
				val op = edits.last()
				comments["${op.author}/${op.permlink}"] = comments["${op.author}/${op.permlink}"]!!.copy(body = op.body)
			}
			return result
		}
		var evidence = HiveRpc.TransactionEvidence.UNAVAILABLE
		/** Head time of the node answering presence reads, as of the next [put]. */
		var head = 2_000_000_000L
		/** The irreversible block time proven past an expiration, or null. */
		var finality: Long? = null
		override fun irreversiblyPast(expirationEpochSec: Long): Long? =
			finality?.takeIf { it > expirationEpochSec }
		/** Set to stand in for the real port's own guard refusing before the network. */
		var refuseBeforeSending: String? = null
		override fun sendPrepared(prepared: PreparedHiveTransaction, author: String): SnapSend =
			refuseBeforeSending?.let { SnapSend.NotSent(it) } ?: SnapSend.Sent(broadcastPrepared(prepared, author))
		override fun observeTransaction(txId: String, expirationEpochSec: Long) = evidence
		override fun contentExists(author: String, permlink: String): Boolean? =
			comments.containsKey("$author/$permlink")
		override fun readComment(author: String, permlink: String) = comments["$author/$permlink"]
		override fun readCommentState(author: String, permlink: String, limit: Int): List<HiveCommentRead> {
			reads++
			onRead?.invoke()
			return (0 until minOf(limit, 2)).map { n ->
				states["$author/$permlink"]?.let { HiveCommentRead.Present(it, "n$n") }
					?: HiveCommentRead.Absent("n$n")
			}
		}
	}

	private class Reader(var replies: List<SnapReply>?) : SnapThreadReader {
		override fun read(rootAuthor: String, rootPermlink: String, viewer: String?) = replies
	}

	private class Store : PendingSnapStore {
		val saved = mutableMapOf<String, PendingSnap>()
		override fun read(account: String, eventId: String): PendingSnapRead =
			saved["$account|$eventId"]?.let(PendingSnapRead::Present) ?: PendingSnapRead.Absent
		override fun write(snap: PendingSnap): Boolean {
			saved["${snap.account}|${snap.eventId}"] = snap; return true
		}
		override fun clear(account: String, eventId: String) { saved.remove("$account|$eventId") }
		override fun all(account: String) =
			saved.filterKeys { it.startsWith("$account|") }.values.toList()
		override fun corruptEventIds(account: String) = emptySet<String>()
	}

	private class Drafts : SnapReplyDraftStore {
		val saved = mutableMapOf<String, String>()
		override fun read(key: String): SnapReplyDraftRead {
			val raw = saved[key] ?: return SnapReplyDraftRead.Absent
			return SnapReplyDraft.fromJson(raw)?.let(SnapReplyDraftRead::Present)
				?: SnapReplyDraftRead.Corrupt("unreadable")
		}
		private fun current(key: String) =
			(read(key) as? SnapReplyDraftRead.Present)?.draft ?: SnapReplyDraft.NONE
		override fun writeText(key: String, text: String) {
			val next = current(key).copy(text = text)
			if (next.isEmpty) saved.remove(key) else saved[key] = next.toJson()
		}
		override fun beginIntent(key: String, intentId: String): Boolean {
			saved[key] = current(key).copy(intentId = intentId).toJson(); return true
		}
		override fun removeIf(key: String, expected: SnapReplyDraft?): Boolean {
			val raw = saved[key] ?: return false
			if (SnapReplyDraft.fromJson(raw) != expected) return false
			saved.remove(key); return true
		}
		override fun settle(key: String, published: SnapReplyDraft): SnapReplySettlement {
			val now = (read(key) as? SnapReplyDraftRead.Present)?.draft
				?: return SnapReplySettlement.Untouched
			if (now.intentId != published.intentId) return SnapReplySettlement.Untouched
			saved.remove(key)
			return SnapReplySettlement.Retired
		}
	}

	private class Previews : SnapThreadPreviewStore {
		override fun read(key: String): SnapThreadPreview.Preview? = null
		override fun write(key: String, preview: SnapThreadPreview.Preview) = Unit
	}

	private class Harness(var who: String? = "alice") {
		val chain = Chain()
		val reader = Reader(emptyList())
		val store = Store()
		val rootDeletes = mutableListOf<Pair<String, String>>()
		/** One instance, as production holds one per kind. */
		val deleter = SnapDeleter(chain, store, sleep = {}, settleAttempts = 2)
		val threads = SnapThreadController(
			scope = CoroutineScope(Dispatchers.Unconfined),
			reader = { reader },
			publisher = { SnapPublisher(chain, store, nowEpochSec = { 1_000L }) },
			account = { who },
			drafts = Drafts(),
			previewStore = Previews(),
			editor = { SnapEditor(chain) },
			deleter = { deleter },
			onRootDeleted = { a, id -> rootDeletes += a to id },
			io = Dispatchers.Unconfined,
		)
	}

	private fun opened(): Harness {
		val h = Harness()
		h.chain.put(mine)
		h.chain.put(theirs)
		h.reader.replies = listOf(mine, theirs)
		h.threads.open(root, null)
		return h
	}

	private fun ids(h: Harness) = h.threads.thread(root)!!.rows.map { it.reply.contentId }.toSet()

	private val mineTarget get() = SnapReplyTarget.of(mine)!!

	// ── who gets Delete ────────────────────────────────────────────────

	@Test
	fun `only the signed-in author is offered Delete`() {
		val h = opened()
		assertTrue(h.threads.canDelete("alice"))
		assertTrue(h.threads.canDelete("ALICE"))
		assertFalse(h.threads.canDelete("bob"))
		h.who = null
		assertFalse(h.threads.canDelete("alice"))
	}

	@Test
	fun `another author's reply cannot even reach the confirmation`() {
		val h = opened()
		h.threads.requestDelete(root, SnapReplyTarget.of(theirs)!!, SnapEditKind.REPLY, theirs.body)
		assertNull(h.threads.deleting)
		h.threads.confirmDelete()
		assertEquals(0, h.chain.reads)
		assertEquals(0, h.chain.broadcasts)
	}

	// ── confirmation and cancel ────────────────────────────────────────

	@Test
	fun `the confirmation opens only after the chain says yes, naming the exact object`() {
		val h = opened()
		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)

		val d = h.threads.deleting!!
		assertEquals(SnapThreadController.DeletePhase.CONFIRM, d.phase)
		assertEquals(mineTarget, d.target)
		assertEquals("old words", d.body)
		assertEquals(1, h.chain.reads)
		assertEquals(0, h.chain.broadcasts)
	}

	@Test
	fun `cancel sends nothing and leaves the reply`() {
		val h = opened()
		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.threads.cancelDelete()

		assertNull(h.threads.deleting)
		h.threads.confirmDelete()
		assertTrue(h.chain.deletes.isEmpty())
		assertEquals(0, h.chain.broadcasts)
		assertTrue(mine.contentId in ids(h))
	}

	@Test
	fun `a blocked reply shows Hive's reason and never reaches a confirmation`() {
		val h = opened()
		h.chain.put(mine, children = 1)

		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)

		assertNull(h.threads.deleting)
		assertEquals(SnapPostStatus.Failed(SnapDeleter.HAS_REPLIES), h.threads.deleteNotice(mineTarget))
		assertEquals(0, h.chain.broadcasts)
	}

	// ── outcomes ───────────────────────────────────────────────────────

	@Test
	fun `a proven deletion removes exactly that reply, even while hivemind still returns it`() {
		val h = opened()
		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.threads.confirmDelete()

		assertEquals(1, h.chain.broadcasts)
		assertEquals(TxSerializer.DeleteCommentOp("alice", mine.permlink), h.chain.deletes.single())
		assertNull(h.threads.deleting)
		// The reader (hivemind) has not caught up and still returns it.
		assertEquals(setOf(theirs.contentId), ids(h))

		h.threads.load(root, force = true)
		assertEquals(setOf(theirs.contentId), ids(h))

		// hivemind catches up: still exactly one reply, the unrelated one.
		h.reader.replies = listOf(theirs)
		h.threads.load(root, force = true)
		assertEquals(setOf(theirs.contentId), ids(h))
	}

	@Test
	fun `a later stale refresh cannot resurrect a reply after an absent refresh`() {
		val h = opened()
		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.threads.confirmDelete()
		h.reader.replies = listOf(theirs)
		h.threads.load(root, force = true)
		assertEquals(setOf(theirs.contentId), ids(h))

		// A different hivemind node has not caught up yet.
		h.reader.replies = listOf(mine, theirs)
		h.threads.load(root, force = true)
		assertEquals(setOf(theirs.contentId), ids(h))
		assertEquals(1, h.chain.broadcasts)
	}

	@Test
	fun `a refusal that a deletion made stale is cleared`() {
		val h = opened()
		h.chain.put(SnapReply("alice", root.permlink, "peak.snaps", "c", "snap", 900L), children = 1)
		h.threads.requestDelete(root, root, SnapEditKind.ROOT, "snap")
		assertEquals(SnapPostStatus.Failed(SnapDeleter.HAS_REPLIES), h.threads.deleteNotice(root))

		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.threads.confirmDelete()

		assertNull(h.threads.deleteNotice(root))
	}

	@Test
	fun `eligibility changing after confirmation keeps the reply and says why`() {
		val h = opened()
		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.chain.put(mine, net = 10)
		h.threads.confirmDelete()

		assertEquals(0, h.chain.broadcasts)
		assertEquals(SnapPostStatus.Failed(SnapDeleter.HAS_VOTES), h.threads.deleteNotice(mineTarget))
		assertTrue(mine.contentId in ids(h))
	}

	@Test
	fun `a rejected broadcast keeps the reply on screen`() {
		val h = opened()
		h.chain.result = HiveRpc.BroadcastResult.Rejected("missing required posting authority")
		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.threads.confirmDelete()

		// A rejection from one node does not prove another never took it.
		assertTrue(h.threads.deleteNotice(mineTarget) is SnapPostStatus.Uncertain)
		assertTrue(mine.contentId in ids(h))
	}

	@Test
	fun `an unproven deletion is checked again by reading, without a second delete`() {
		val h = opened()
		h.chain.result = HiveRpc.BroadcastResult.AcceptedUnconfirmed("del-1", "n", "no confirmation")
		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.threads.confirmDelete()

		assertTrue(h.threads.deleteNotice(mineTarget) is SnapPostStatus.Uncertain)
		assertTrue(mine.contentId in ids(h))

		// It lands; "Check deletion" goes straight to settling.
		h.chain.states.remove(mine.contentId)
		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)

		assertEquals(1, h.chain.deletes.size)
		assertEquals(1, h.chain.broadcasts)
		assertEquals(setOf(theirs.contentId), ids(h))
	}

	@Test
	fun `a reply already gone from Hive is reconciled without sending anything`() {
		val h = opened()
		h.chain.states.remove(mine.contentId)

		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)

		assertEquals(0, h.chain.broadcasts)
		assertTrue(h.chain.deletes.isEmpty())
		assertEquals(setOf(theirs.contentId), ids(h))
	}

	@Test
	fun `a deleted root Snap leaves the History card and closes the sheet`() {
		val h = Harness()
		h.chain.put(SnapReply("alice", root.permlink, "peak.snaps", "snap-container-1789648560", "snap", 900L))
		h.reader.replies = emptyList()
		h.threads.open(root, null)

		h.threads.requestDelete(root, root, SnapEditKind.ROOT, "snap")
		h.threads.confirmDelete()

		assertEquals(listOf("alice" to root.contentId), h.rootDeletes)
		assertNull(h.threads.openThread)
	}

	/**
	 * Issue 40C: a thread read that left before the root was deleted, and
	 * answers after, must not put the deleted root's conversation back.
	 */
	@Test
	fun `a thread read in flight across a root deletion cannot repopulate it`() {
		val h = Harness()
		h.chain.put(SnapReply("alice", root.permlink, "peak.snaps", "snap-container-1789648560", "snap", 900L))
		val pool = java.util.concurrent.Executors.newCachedThreadPool()
		val io = pool.asCoroutineDispatcher()
		val inRead = java.util.concurrent.CountDownLatch(1)
		val release = java.util.concurrent.CountDownLatch(1)
		val reads = java.util.concurrent.atomic.AtomicInteger()
		val deletes = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
		val reader = object : SnapThreadReader {
			override fun read(rootAuthor: String, rootPermlink: String, viewer: String?): List<SnapReply> {
				if (reads.incrementAndGet() == 2) {
					inRead.countDown()
					release.await(5, java.util.concurrent.TimeUnit.SECONDS)
				}
				// Pre-deletion answer: the root with a reply under it.
				return listOf(theirs)
			}
		}
		val threads = SnapThreadController(
			scope = CoroutineScope(Dispatchers.Unconfined),
			reader = { reader },
			publisher = { SnapPublisher(h.chain, h.store, nowEpochSec = { 1_000L }) },
			account = { "alice" },
			drafts = Drafts(),
			previewStore = Previews(),
			deleter = { h.deleter },
			onRootDeleted = { a, id -> deletes += a to id },
			io = io,
		)
		fun until(what: () -> Boolean) {
			val end = System.currentTimeMillis() + 5_000
			while (!what()) { check(System.currentTimeMillis() < end); Thread.sleep(5) }
		}
		try {
			threads.open(root, null)
			until { threads.thread(root) != null }
			threads.load(root, force = true)
			check(inRead.await(5, java.util.concurrent.TimeUnit.SECONDS))

			// The deletion is proven while that read is still out.
			threads.requestDelete(root, root, SnapEditKind.ROOT, "snap")
			until { threads.deleting?.phase == SnapThreadController.DeletePhase.CONFIRM }
			threads.confirmDelete()
			until { deletes.isNotEmpty() && threads.deleting == null }

			release.countDown()
			Thread.sleep(200)
			assertEquals(listOf("alice" to root.contentId), deletes.toList())
			assertNull("deleted root's conversation came back", threads.state(root))
			assertNull(threads.preview(root))
			assertNull(threads.rootOf(SnapReplyTarget.of(theirs)!!))
		} finally {
			release.countDown()
			pool.shutdown()
		}
	}

	// ── isolation and exclusivity ──────────────────────────────────────

	@Test
	fun `a check that finishes under another account is discarded`() {
		val h = opened()
		// Bob signs in while Alice's read is being answered.
		h.chain.onRead = { h.who = "bob" }
		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.chain.onRead = null
		assertNull(h.threads.deleting)
		h.threads.confirmDelete()

		// Back to Alice: no stale confirmation waiting to be accepted.
		h.who = "alice"
		assertNull(h.threads.deleting)
		h.threads.confirmDelete()
		assertEquals(0, h.chain.broadcasts)
	}

	@Test
	fun `Edit is refused while a deletion is being considered`() {
		val h = opened()
		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.threads.startEdit(root, mineTarget, SnapEditKind.REPLY, mine.body)
		assertNull(h.threads.editing)

		h.threads.cancelDelete()
		h.threads.startEdit(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.threads.editDraft("new words")
		h.threads.saveEdit()
		assertEquals(1, h.chain.edits.size)
		assertTrue(h.chain.deletes.isEmpty())
	}

	// ── an unsettled delete keeps Edit away (Issue #56 F03a) ───────────

	private fun unsettledDelete(result: HiveRpc.BroadcastResult): Harness {
		val h = opened()
		h.chain.result = result
		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.threads.confirmDelete()
		assertTrue(h.threads.deleteNotice(mineTarget) is SnapPostStatus.Uncertain)
		assertNull("the delete UI has finished", h.threads.deleting)
		assertTrue(h.deleter.hasUnsettled("alice", mineTarget))
		return h
	}

	@Test
	fun `Edit stays unavailable while the comment's delete is unsettled`() {
		val h = unsettledDelete(HiveRpc.BroadcastResult.AcceptedUnconfirmed("del-1", "n", "no confirmation"))

		h.threads.startEdit(root, mineTarget, SnapEditKind.REPLY, mine.body)
		assertNull(h.threads.editing)
		h.threads.saveEdit()
		assertTrue("nothing was signed as an edit", h.chain.edits.isEmpty())
		assertEquals(1, h.chain.broadcasts)
	}

	@Test
	fun `a rejected delete keeps Edit away until reading proves it landed`() {
		val h = unsettledDelete(HiveRpc.BroadcastResult.Rejected("Duplicate transaction check failed"))
		h.threads.startEdit(root, mineTarget, SnapEditKind.REPLY, mine.body)
		assertNull(h.threads.editing)

		// The original lands. Asking again settles it and sends nothing more.
		h.chain.states.remove(mine.contentId)
		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)

		assertEquals(1, h.chain.deletes.size)
		assertEquals(1, h.chain.broadcasts)
		assertTrue(h.chain.edits.isEmpty())
		assertEquals(setOf(theirs.contentId), ids(h))
	}

	@Test
	fun `a delete proven never included gives Edit back`() {
		val h = unsettledDelete(HiveRpc.BroadcastResult.Rejected("Duplicate transaction check failed"))
		h.chain.evidence = HiveRpc.TransactionEvidence.ABSENT
		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)
		assertTrue(h.threads.deleteNotice(mineTarget) is SnapPostStatus.Failed)
		assertFalse(h.deleter.hasUnsettled("alice", mineTarget))

		h.chain.result = HiveRpc.BroadcastResult.Success("tx", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)
		h.threads.startEdit(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.threads.editDraft("new words")
		h.threads.saveEdit()
		assertEquals(1, h.chain.edits.size)
		assertEquals(1, h.chain.deletes.size)
	}

	@Test
	fun `another comment can still be edited while one delete is unsettled`() {
		val h = opened()
		h.chain.put(mine2)
		h.reader.replies = listOf(mine, theirs, mine2)
		h.threads.open(root, null)
		h.chain.result = HiveRpc.BroadcastResult.AcceptedUnconfirmed("del-1", "n", "no confirmation")
		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.threads.confirmDelete()

		h.chain.result = HiveRpc.BroadcastResult.Success("tx", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)
		val other = SnapReplyTarget.of(mine2)!!
		h.threads.startEdit(root, other, SnapEditKind.REPLY, mine2.body)
		h.threads.editDraft("edited elsewhere")
		h.threads.saveEdit()

		assertEquals(listOf(other.permlink), h.chain.edits.map { it.permlink })
		assertTrue(h.deleter.hasUnsettled("alice", mineTarget))
	}

	@Test
	fun `a failed edit cannot be retried while the comment's delete is unsettled`() {
		val h = opened()
		// An edit that is proven never to have landed: FAILED, kept for retry.
		h.chain.result = HiveRpc.BroadcastResult.NetworkFailure("down")
		h.chain.evidence = HiveRpc.TransactionEvidence.ABSENT
		h.threads.startEdit(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.threads.editDraft("new words")
		h.threads.saveEdit()
		assertEquals(1, h.chain.edits.size)

		// Then a delete that cannot be settled.
		h.chain.result = HiveRpc.BroadcastResult.AcceptedUnconfirmed("del-1", "n", "no confirmation")
		h.chain.evidence = HiveRpc.TransactionEvidence.UNAVAILABLE
		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.threads.confirmDelete()
		assertTrue(h.deleter.hasUnsettled("alice", mineTarget))

		h.chain.result = HiveRpc.BroadcastResult.Success("tx", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)
		h.threads.retryEdit(mineTarget)
		h.threads.startEdit(root, mineTarget, SnapEditKind.REPLY, mine.body)

		assertEquals("the failed edit was not signed again", 1, h.chain.edits.size)
		assertNull(h.threads.editing)
	}

	@Test
	fun `the guard survives an account switch and back`() {
		val h = unsettledDelete(HiveRpc.BroadcastResult.AcceptedUnconfirmed("del-1", "n", "no confirmation"))
		h.who = "bob"
		h.threads.startEdit(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.who = "alice"
		h.threads.startEdit(root, mineTarget, SnapEditKind.REPLY, mine.body)

		assertNull(h.threads.editing)
		assertTrue(h.chain.edits.isEmpty())
	}

	@Test
	fun `a delete refused on this device before sending leaves Edit available`() {
		val h = opened()
		h.chain.refuseBeforeSending = "You've switched Hive accounts — this Snap belonged to a different one."
		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.threads.confirmDelete()

		assertTrue(h.threads.deleteNotice(mineTarget) is SnapPostStatus.Failed)
		assertEquals("nothing reached the network", 0, h.chain.broadcasts)
		assertFalse(h.deleter.hasUnsettled("alice", mineTarget))

		h.threads.startEdit(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.threads.editDraft("new words")
		h.threads.saveEdit()
		assertEquals(1, h.chain.edits.size)
	}

	@Test
	fun `a delete proven unable to land, with the reply still there, gives Edit back`() {
		val h = unsettledDelete(HiveRpc.BroadcastResult.AcceptedUnconfirmed("del-1", "n", "no confirmation"))
		h.threads.startEdit(root, mineTarget, SnapEditKind.REPLY, mine.body)
		assertNull(h.threads.editing)

		h.chain.finality = 2_000_000_003L
		h.chain.head = 2_000_000_003L
		h.chain.put(mine)
		h.threads.requestDelete(root, mineTarget, SnapEditKind.REPLY, mine.body)

		assertTrue(h.threads.deleteNotice(mineTarget) is SnapPostStatus.Failed)
		assertFalse(h.deleter.hasUnsettled("alice", mineTarget))
		assertEquals("settling sent nothing", 1, h.chain.broadcasts)
		assertTrue(mine.contentId in ids(h))

		h.chain.result = HiveRpc.BroadcastResult.Success("tx", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)
		h.threads.startEdit(root, mineTarget, SnapEditKind.REPLY, mine.body)
		h.threads.editDraft("new words")
		h.threads.saveEdit()
		assertEquals(1, h.chain.edits.size)
	}
}
