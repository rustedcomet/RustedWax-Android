package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.PendingSnap
import com.rustedwax.app.snaps.PendingSnapRead
import com.rustedwax.app.snaps.PendingSnapStore
import com.rustedwax.app.snaps.PostedSnap
import com.rustedwax.app.snaps.SnapChainComment
import com.rustedwax.app.snaps.SnapEditKind
import com.rustedwax.app.snaps.SnapEditor
import com.rustedwax.app.snaps.SnapHivePort
import com.rustedwax.app.snaps.SnapPublisher
import com.rustedwax.app.snaps.SnapReply
import com.rustedwax.app.snaps.SnapReplyTarget
import com.rustedwax.app.snaps.SnapThreadPreview
import com.rustedwax.app.snaps.SnapThreadReader
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edit, from the sheet's side: who gets it, what the conversation shows after
 * a save, and that a save which did not land leaves the old words in place.
 *
 * Runs unconfined so `launch` completes inline, like the other controller
 * tests.
 */
class SnapEditControllerTest {

	private val root = SnapReplyTarget.of("alice", "rustedwax-snap-1000-aaaaaa")!!
	private val tail = "\n\nhttps://youtu.be/rTKpYJ80OVQ\n\n#scrobblelife #scrobble #rustedwax"

	/** Alice's own reply, and Bob's, both directly under the root. */
	private val mine = SnapReply("alice", "rustedwax-reply-1000-mine", "alice", root.permlink, "old words", 1_000L)
	private val theirs = SnapReply("bob", "re-alice-1001", "alice", root.permlink, "bob says", 1_001L)

	private class Chain : SnapHivePort {
		val objects = mutableMapOf<String, SnapChainComment>()
		var result: HiveRpc.BroadcastResult =
			HiveRpc.BroadcastResult.Success("tx", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)
		var evidence = HiveRpc.TransactionEvidence.UNAVAILABLE
		val prepared = mutableListOf<TxSerializer.CommentOp>()
		var broadcasts = 0
		var duringBroadcast: (() -> Unit)? = null

		fun put(c: SnapChainComment) { objects["${c.author}/${c.permlink}"] = c }

		override fun resolveContainer() = SnapContainerResolver.Result.Resolved(
			SnapContainer("peak.snaps", "snap-container-1789648560", "2026-09-17T12:36:00"),
		)
		override fun prepareComment(operation: TxSerializer.CommentOp, author: String): HivePreparationResult {
			prepared += operation
			return HivePreparationResult.Ready(
				PreparedHiveTransaction("{}", "tx-${prepared.size}", 2_000_000_000L),
			)
		}
		override fun broadcastPrepared(prepared: PreparedHiveTransaction, author: String): HiveRpc.BroadcastResult {
			broadcasts++
			duringBroadcast?.invoke()
			val op = this.prepared.last()
			val r = result
			if (r is HiveRpc.BroadcastResult.Success) {
				val id = "${op.author}/${op.permlink}"
				objects[id] = (objects[id] ?: SnapChainComment(
					op.author, op.permlink, op.parentAuthor, op.parentPermlink, op.title, "", op.jsonMetadata,
				)).copy(body = op.body)
			}
			return r
		}
		override fun observeTransaction(txId: String, expirationEpochSec: Long) = evidence
		override fun contentExists(author: String, permlink: String): Boolean? =
			objects.containsKey("$author/$permlink")
		override fun readComment(author: String, permlink: String) = objects["$author/$permlink"]
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
		val rootEdits = mutableListOf<Triple<String, String, String>>()
		val store = Store()
		private var minted = 0
		val threads = SnapThreadController(
			scope = CoroutineScope(Dispatchers.Unconfined),
			reader = { reader },
			publisher = {
				SnapPublisher(
					chain,
					store,
					nowEpochSec = { 1_000L },
					newReplyPermlink = { "rustedwax-reply-1000-new${minted++}" },
				)
			},
			account = { who },
			drafts = Drafts(),
			previewStore = Previews(),
			editor = { SnapEditor(chain) },
			onRootEdited = { a, id, text -> rootEdits += Triple(a, id, text) },
			io = Dispatchers.Unconfined,
		)
	}

	private fun onChain(reply: SnapReply) = SnapChainComment(
		author = reply.author,
		permlink = reply.permlink,
		parentAuthor = reply.parentAuthor,
		parentPermlink = reply.parentPermlink,
		title = "",
		body = reply.body,
		jsonMetadata = """{"app":"rustedwax/0.12.2"}""",
	)

	private fun bodies(h: Harness) =
		h.threads.thread(root)!!.rows.associate { it.reply.contentId to it.reply.body }

	/** Alice's thread, open, with her reply and Bob's on screen and on chain. */
	private fun opened(): Harness {
		val h = Harness()
		h.chain.put(onChain(mine))
		h.chain.put(onChain(theirs))
		h.reader.replies = listOf(mine, theirs)
		h.threads.open(root, null)
		return h
	}

	// ── who gets Edit ──────────────────────────────────────────────────

	@Test
	fun `only the signed-in author is offered Edit`() {
		val h = opened()
		assertTrue(h.threads.canEdit("alice"))
		assertTrue("account names are compared like the signer does", h.threads.canEdit("ALICE"))
		assertFalse(h.threads.canEdit("bob"))

		h.who = null
		assertFalse("nobody signed in edits nothing", h.threads.canEdit("alice"))
	}

	@Test
	fun `a non-author's comment cannot be put into the edit bar`() {
		val h = opened()

		h.threads.startEdit(root, SnapReplyTarget.of(theirs)!!, SnapEditKind.REPLY, theirs.body)

		assertNull(h.threads.editing)
		h.threads.saveEdit()
		assertEquals(0, h.chain.broadcasts)
	}

	// ── a reply edit ───────────────────────────────────────────────────

	@Test
	fun `a saved reply edit shows the new words in place, once, under the same identity`() {
		val h = opened()
		val before = h.threads.thread(root)!!.total

		h.threads.startEdit(root, SnapReplyTarget.of(mine)!!, SnapEditKind.REPLY, mine.body)
		h.threads.editDraft("new words")
		h.threads.saveEdit()

		assertNull("the bar closes on success", h.threads.editing)
		assertEquals(1, h.chain.broadcasts)
		val op = h.chain.prepared.single()
		assertEquals(mine.author to mine.permlink, op.author to op.permlink)
		assertEquals(mine.parentAuthor to mine.parentPermlink, op.parentAuthor to op.parentPermlink)
		assertEquals("no duplicate row", before, h.threads.thread(root)!!.total)
		assertEquals("new words", bodies(h)[mine.contentId])
		assertEquals("someone else's reply is untouched", "bob says", bodies(h)[theirs.contentId])
	}

	@Test
	fun `a lagging re-read cannot put the old words back`() {
		val h = opened()
		h.threads.startEdit(root, SnapReplyTarget.of(mine)!!, SnapEditKind.REPLY, mine.body)
		h.threads.editDraft("new words")
		h.threads.saveEdit()

		// hivemind still serves the pre-edit body.
		h.reader.replies = listOf(mine, theirs)
		h.threads.load(root, force = true)
		assertEquals("new words", bodies(h)[mine.contentId])

		// Caught up: the chain's own copy is drawn, and still says the same.
		h.reader.replies = listOf(mine.copy(body = "new words"), theirs)
		h.threads.load(root, force = true)
		assertEquals("new words", bodies(h)[mine.contentId])
		assertEquals(2, h.threads.thread(root)!!.total)
	}

	@Test
	fun `a save that did not land keeps the old words and the typed ones`() {
		val h = opened()
		h.chain.result = HiveRpc.BroadcastResult.Rejected("node said no")
		h.chain.evidence = HiveRpc.TransactionEvidence.ABSENT

		h.threads.startEdit(root, SnapReplyTarget.of(mine)!!, SnapEditKind.REPLY, mine.body)
		h.threads.editDraft("new words")
		h.threads.saveEdit()

		assertEquals("old words", bodies(h)[mine.contentId])
		assertNotNull("the bar stays open so nothing is retyped", h.threads.editing)
		assertEquals("new words", h.threads.editText)
		assertTrue(h.threads.editStatus is SnapPostStatus.Failed)
		assertFalse(h.threads.isSavingEdit)
	}

	@Test
	fun `an unconfirmed save is reported as uncertain and changes nothing on screen`() {
		val h = opened()
		h.chain.result = HiveRpc.BroadcastResult.NetworkFailure("lost")

		h.threads.startEdit(root, SnapReplyTarget.of(mine)!!, SnapEditKind.REPLY, mine.body)
		h.threads.editDraft("new words")
		h.threads.saveEdit()

		assertEquals("old words", bodies(h)[mine.contentId])
		assertTrue(h.threads.editStatus is SnapPostStatus.Uncertain)
	}

	@Test
	fun `281 characters cannot be saved and 280 can`() {
		val h = opened()
		h.threads.startEdit(root, SnapReplyTarget.of(mine)!!, SnapEditKind.REPLY, mine.body)

		h.threads.editDraft("a".repeat(281))
		h.threads.saveEdit()
		assertEquals(0, h.chain.broadcasts)
		assertNotNull(h.threads.editing)

		h.threads.editDraft("a".repeat(280))
		h.threads.saveEdit()
		assertEquals(1, h.chain.broadcasts)
		assertEquals("a".repeat(280), bodies(h)[mine.contentId])
	}

	@Test
	fun `saving the words already there is not a write`() {
		val h = opened()
		h.threads.startEdit(root, SnapReplyTarget.of(mine)!!, SnapEditKind.REPLY, mine.body)

		h.threads.saveEdit()

		assertEquals(0, h.chain.broadcasts)
		assertNull(h.threads.editing)
	}

	@Test
	fun `a second save tap while one is running sends nothing more`() {
		val h = opened()
		h.chain.duringBroadcast = { h.threads.saveEdit() }
		h.threads.startEdit(root, SnapReplyTarget.of(mine)!!, SnapEditKind.REPLY, mine.body)
		h.threads.editDraft("new words")

		h.threads.saveEdit()

		assertEquals(1, h.chain.broadcasts)
	}

	@Test
	fun `an account switch mid-save leaves the new account's screen alone`() {
		val h = opened()
		h.chain.duringBroadcast = { h.who = "bob" }
		h.threads.startEdit(root, SnapReplyTarget.of(mine)!!, SnapEditKind.REPLY, mine.body)
		h.threads.editDraft("new words")

		h.threads.saveEdit()

		assertNull("Bob sees no edit bar of Alice's", h.threads.editing)
		assertNull(h.threads.editStatus)
		assertFalse(h.threads.isSavingEdit)
	}

	@Test
	fun `opening a thread as another account clears the previous unsaved edit`() {
		val h = opened()
		h.threads.startEdit(root, SnapReplyTarget.of(mine)!!, SnapEditKind.REPLY, mine.body)
		h.threads.editDraft("Alice's unsaved words")

		h.who = "bob"
		h.threads.open(root, null)

		assertNull(h.threads.editing)
		assertEquals("", h.threads.editText)
		h.threads.saveEdit()
		assertEquals(0, h.chain.broadcasts)
	}

	@Test
	fun `opening a root composer as another account clears the previous unsaved edit`() {
		val h = opened()
		h.threads.startEdit(root, SnapReplyTarget.of(mine)!!, SnapEditKind.REPLY, mine.body)
		h.threads.editDraft("Alice's unsaved words")

		h.who = "bob"
		h.threads.openComposer("another-event")

		assertNull(h.threads.editing)
		assertEquals("", h.threads.editText)
		assertEquals(0, h.chain.broadcasts)
	}

	// ── a root edit ────────────────────────────────────────────────────

	@Test
	fun `a saved root edit updates the sheet and tells the History card`() {
		val h = Harness()
		h.chain.put(
			SnapChainComment(
				"alice", root.permlink, "peak.snaps", "snap-container-1789648560", "",
				"old words$tail", """{"app":"rustedwax/0.11.4","tags":["scrobblelife","scrobble","rustedwax"]}""",
			),
		)
		h.threads.open(root, PostedSnap("alice", root.permlink, "old words", 1_000L, fromChain = true))

		h.threads.startEdit(root, root, SnapEditKind.ROOT, "old words")
		h.threads.editDraft("new words")
		h.threads.saveEdit()

		assertEquals("new words", h.threads.openRootSnap!!.userText)
		assertEquals(listOf(Triple("alice", root.contentId, "new words")), h.rootEdits)
		val op = h.chain.prepared.single()
		assertEquals("new words$tail", op.body)
		assertEquals("peak.snaps", op.parentAuthor)
	}

	@Test
	fun `a failed root edit tells nobody and keeps the old words`() {
		val h = Harness()
		h.chain.result = HiveRpc.BroadcastResult.Rejected("no")
		h.chain.evidence = HiveRpc.TransactionEvidence.ABSENT
		h.chain.put(
			SnapChainComment(
				"alice", root.permlink, "peak.snaps", "snap-container-1789648560", "",
				"old words$tail", "{}",
			),
		)
		h.threads.open(root, PostedSnap("alice", root.permlink, "old words", 1_000L, fromChain = true))

		h.threads.startEdit(root, root, SnapEditKind.ROOT, "old words")
		h.threads.editDraft("new words")
		h.threads.saveEdit()

		assertEquals("old words", h.threads.openRootSnap!!.userText)
		assertTrue(h.rootEdits.isEmpty())
	}

	// ── nothing else moved ─────────────────────────────────────────────

	@Test
	fun `replying still works after an edit and mints its own permlink`() {
		val h = opened()
		h.threads.startEdit(root, SnapReplyTarget.of(mine)!!, SnapEditKind.REPLY, mine.body)
		h.threads.editDraft("new words")
		h.threads.saveEdit()

		val key = h.threads.replyKey(root)
		h.threads.edit(key, "a fresh reply")
		h.threads.send(root, root)

		assertEquals(2, h.chain.broadcasts)
		val sent = h.chain.prepared.last()
		assertTrue(sent.permlink.startsWith("rustedwax-reply-1000-new"))
		assertEquals(root.author to root.permlink, sent.parentAuthor to sent.parentPermlink)
		assertEquals("the edit's identity was not reused", 2, h.chain.prepared.map { it.permlink }.toSet().size)
	}

	@Test
	fun `closing the sheet abandons an edit without writing`() {
		val h = opened()
		h.threads.startEdit(root, SnapReplyTarget.of(mine)!!, SnapEditKind.REPLY, mine.body)
		h.threads.editDraft("never saved")

		h.threads.close()

		assertNull(h.threads.editing)
		assertEquals(0, h.chain.broadcasts)
	}
}
