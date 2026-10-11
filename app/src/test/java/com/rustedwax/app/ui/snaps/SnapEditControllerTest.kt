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
import kotlinx.coroutines.asCoroutineDispatcher
import com.rustedwax.app.ui.snaps.SnapEditImages
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

	/**
	 * The composer's image store, as an Edit sees it. Each key holds local
	 * image names; an upload hosts the ones not yet hosted, in order.
	 */
	class Images : SnapEditImages {
		val waiting = mutableMapOf<String, MutableList<String>>()
		val hosted = mutableMapOf<String, String>()
		val uploads = mutableListOf<String>()
		val failing = mutableSetOf<String>()
		var duringUpload: (() -> Unit)? = null
		fun add(key: String, vararg names: String) { waiting.getOrPut(key) { mutableListOf() } += names }
		fun urlFor(name: String) = "https://images.hive.blog/DQm" +
			name.padEnd(44, 'z').take(44).map { if (it.isLetterOrDigit() && it != '0' && it != 'O' && it != 'I' && it != 'l') it else 'x' }
				.joinToString("") + "/image.jpg"
		override fun count(key: String) = waiting[key]?.size ?: 0
		override fun upload(key: String, onReady: (List<String>) -> Unit, onFailed: (String) -> Unit) {
			duringUpload?.invoke()
			for (name in waiting[key].orEmpty()) {
				if (name in hosted) continue
				if (name in failing) return onFailed("Host down.")
				uploads += name
				hosted[name] = urlFor(name)
			}
			onReady(waiting[key].orEmpty().map { hosted.getValue(it) })
		}
		override fun clear(key: String) { waiting.remove(key) }
	}

	private class Harness(
		var who: String? = "alice",
		io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
		/** A real image store instead of [images], when a test wires one in. */
		var port: SnapEditImages? = null,
	) {
		val chain = Chain()
		val reader = Reader(emptyList())
		val images = Images()
		val rootPending = mutableListOf<Pair<String, String?>>()
		val rootOpened = mutableListOf<String>()
		val rootEdits = mutableListOf<Triple<String, String, String>>()
		val store = Store()
		private val retainedEditor = SnapEditor(chain)
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
			editor = { retainedEditor },
			onRootEdited = { a, id, text -> rootEdits += Triple(a, id, text) },
			onRootOpened = { _, id -> rootOpened += id },
			editImagesPort = { port ?: images },
			onRootPending = { _, id, text -> rootPending += id to text },
			io = io,
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

		assertEquals("the comment goes back to what Hive holds", "old words", bodies(h)[mine.contentId])
		// Issue 40D: the editor never waits on Hive. The edit is kept on the
		// comment instead, so nothing is retyped.
		assertNull("the bar closed on the tap", h.threads.editing)
		val pending = h.threads.pendingEdit(SnapReplyTarget.of(mine)!!)!!
		assertEquals(SnapThreadController.Phase.FAILED, pending.phase)
		assertEquals("new words", pending.words)
		assertNotNull("the author is told", h.threads.editFailure)
		h.threads.startEdit(root, SnapReplyTarget.of(mine)!!, SnapEditKind.REPLY, mine.body)
		assertEquals("Edit reopens on the failed words", "new words", h.threads.editText)
	}

	@Test
	fun `an unconfirmed save is reported as uncertain and changes nothing on screen`() {
		val h = opened()
		h.chain.result = HiveRpc.BroadcastResult.NetworkFailure("lost")

		h.threads.startEdit(root, SnapReplyTarget.of(mine)!!, SnapEditKind.REPLY, mine.body)
		h.threads.editDraft("new words")
		h.threads.saveEdit()

		assertEquals("old words", bodies(h)[mine.contentId])
		assertEquals(
			SnapThreadController.Phase.UNCERTAIN,
			h.threads.pendingEdit(SnapReplyTarget.of(mine)!!)!!.phase,
		)
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
		assertNull("Bob sees no edit of Alice's", h.threads.pendingEdit(SnapReplyTarget.of(mine)!!))
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

	// ── Issue 40D: attached images through an edit ─────────────────────

	private val img1 = "https://images.hive.blog/DQm" + "a".repeat(44) + "/image.jpg"
	private val img2 = "https://images.hive.blog/DQm" + "b".repeat(44) + "/image.gif"

	@Test
	fun `editing a reply with images edits only the words and keeps the images`() {
		val h = Harness()
		val withImages = mine.copy(body = "old words\n\n![]($img1)\n![]($img2)")
		h.chain.put(onChain(withImages))
		h.reader.replies = listOf(withImages, theirs)
		h.threads.open(root, null)

		h.threads.startEdit(root, SnapReplyTarget.of(withImages)!!, SnapEditKind.REPLY, withImages.body)

		assertEquals("the box holds the words only", "old words", h.threads.editText)
		assertEquals(listOf(img1, img2), h.threads.editing!!.images)
		h.threads.editDraft("x".repeat(280))
		h.threads.saveEdit()

		val op = h.chain.prepared.single()
		assertEquals("x".repeat(280) + "\n\n![]($img1)\n![]($img2)", op.body)
		assertEquals(withImages.author to withImages.permlink, op.author to op.permlink)
		assertNull(h.threads.editing)
	}

	@Test
	fun `an unchanged edit of a reply with images is not a write`() {
		val h = Harness()
		val withImages = mine.copy(body = "old words\n\n![]($img1)")
		h.chain.put(onChain(withImages))
		h.reader.replies = listOf(withImages)
		h.threads.open(root, null)

		h.threads.startEdit(root, SnapReplyTarget.of(withImages)!!, SnapEditKind.REPLY, withImages.body)
		h.threads.saveEdit()

		assertEquals(0, h.chain.broadcasts)
	}

	@Test
	fun `editing a root Snap with images keeps the images and the frozen tail`() {
		val h = Harness()
		val authored = "old words\n\n![]($img1)"
		h.chain.put(
			SnapChainComment(
				"alice", root.permlink, "peak.snaps", "snap-container-1789648560", "",
				"$authored$tail", """{"app":"rustedwax/0.12.2","tags":["scrobblelife","scrobble","rustedwax"]}""",
			),
		)
		h.threads.open(root, PostedSnap("alice", root.permlink, authored, 1_000L, fromChain = true))

		h.threads.startEdit(root, root, SnapEditKind.ROOT, authored)
		assertEquals("old words", h.threads.editText)
		h.threads.editDraft("new words")
		h.threads.saveEdit()

		val op = h.chain.prepared.single()
		assertEquals("new words\n\n![]($img1)$tail", op.body)
		assertEquals("new words\n\n![]($img1)", h.threads.openRootSnap!!.userText)
	}

	// ── Issue 40D follow-up: removing attached images during Edit ──────

	private val img3 = "https://images.hive.blog/DQm" + "c".repeat(44) + "/image.png"
	private val img4 = "https://images.hive.blog/DQm" + "d".repeat(44) + "/image.webp"

	private fun block(vararg urls: String) = urls.joinToString("\n") { "![]($it)" }

	/** Alice's reply carrying [urls] after [words], open in the edit bar. */
	private fun editingReply(words: String, vararg urls: String): Pair<Harness, SnapReply> {
		val h = Harness()
		val r = mine.copy(body = if (urls.isEmpty()) words else "$words\n\n${block(*urls)}")
		h.chain.put(onChain(r))
		h.reader.replies = listOf(r, theirs)
		h.threads.open(root, null)
		h.threads.startEdit(root, SnapReplyTarget.of(r)!!, SnapEditKind.REPLY, r.body)
		return h to r
	}

	@Test
	fun `every identified image is offered for removal in order, the words alone are in the box`() {
		val (h, _) = editingReply("words", img1, img2, img3, img4)
		assertEquals("words", h.threads.editText)
		assertEquals(listOf(img1, img2, img3, img4), h.threads.editImages)
	}

	@Test
	fun `removing the first, middle or last image keeps the words and every other image in order`() {
		for ((index, expected) in listOf(
			0 to listOf(img2, img3, img4),
			1 to listOf(img1, img3, img4),
			3 to listOf(img1, img2, img3),
		)) {
			val (h, r) = editingReply("words", img1, img2, img3, img4)
			h.threads.removeEditImage(index)
			h.threads.saveEdit()

			val op = h.chain.prepared.single()
			assertEquals("words\n\n${block(*expected.toTypedArray())}", op.body)
			assertEquals("same object", r.author to r.permlink, op.author to op.permlink)
			assertEquals("same parent", r.parentAuthor to r.parentPermlink, op.parentAuthor to op.parentPermlink)
			assertEquals("one write", 1, h.chain.broadcasts)
			assertNull(h.threads.editing)
		}
	}

	@Test
	fun `removing several images while also changing the words is one edit`() {
		val (h, _) = editingReply("old words", img1, img2, img3, img4)
		h.threads.removeEditImage(3)
		h.threads.removeEditImage(1)
		h.threads.editDraft("new words")
		h.threads.saveEdit()
		assertEquals("new words\n\n${block(img1, img3)}", h.chain.prepared.single().body)
		assertEquals(1, h.chain.broadcasts)
	}

	@Test
	fun `removing every image leaves an ordinary text-only body`() {
		val (h, _) = editingReply("just words", img1, img2)
		h.threads.removeEditImage(0)
		h.threads.removeEditImage(0)
		assertTrue(h.threads.editImages.isEmpty())
		h.threads.saveEdit()
		assertEquals("just words", h.chain.prepared.single().body)
	}

	@Test
	fun `an image-only reply cannot lose its last image without gaining words`() {
		val h = Harness()
		val r = mine.copy(body = block(img1))
		h.chain.put(onChain(r))
		h.reader.replies = listOf(r)
		h.threads.open(root, null)
		h.threads.startEdit(root, SnapReplyTarget.of(r)!!, SnapEditKind.REPLY, r.body)
		h.threads.removeEditImage(0)
		assertNotNull("an empty comment is not a valid edit", SnapEditor.textProblem(h.threads.editedText()))
		h.threads.saveEdit()
		assertEquals(0, h.chain.broadcasts)

		h.threads.editDraft("now words")
		h.threads.saveEdit()
		assertEquals("now words", h.chain.prepared.single().body)
	}

	@Test
	fun `cancelling after removing images changes nothing anywhere`() {
		val (h, r) = editingReply("words", img1, img2)
		val before = bodies(h)
		h.threads.removeEditImage(0)
		h.threads.cancelEdit()

		assertNull(h.threads.editing)
		assertTrue(h.threads.editImages.isEmpty())
		assertEquals(0, h.chain.broadcasts)
		assertEquals(before, bodies(h))
		assertEquals(r.body, h.chain.objects[r.contentId]!!.body)

		// A fresh Edit starts from the published images again.
		h.threads.startEdit(root, SnapReplyTarget.of(r)!!, SnapEditKind.REPLY, r.body)
		assertEquals(listOf(img1, img2), h.threads.editImages)
	}

	@Test
	fun `a failed save keeps the words and the intended removals for retry`() {
		val (h, r) = editingReply("words", img1, img2, img3)
		h.chain.result = HiveRpc.BroadcastResult.Rejected("no")
		h.chain.evidence = HiveRpc.TransactionEvidence.ABSENT
		h.threads.removeEditImage(1)
		h.threads.editDraft("new words")
		h.threads.saveEdit()

		val pending = h.threads.pendingEdit(SnapReplyTarget.of(r)!!)!!
		assertEquals(SnapThreadController.Phase.FAILED, pending.phase)
		assertEquals("new words", pending.words)
		assertEquals(listOf(img1, img3), pending.kept)
		assertEquals("the chain still has every image", r.body, h.chain.objects[r.contentId]!!.body)
		assertEquals("and so does the screen", r.body, bodies(h)[r.contentId])

		h.chain.result = HiveRpc.BroadcastResult.Success("tx", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)
		h.threads.retryEdit(SnapReplyTarget.of(r)!!)
		assertEquals("new words\n\n${block(img1, img3)}", h.chain.objects[r.contentId]!!.body)
		assertNull(h.threads.editing)
	}

	@Test
	fun `nothing can be removed while a save is running`() {
		val (h, _) = editingReply("words", img1, img2)
		h.chain.duringBroadcast = { h.threads.removeEditImage(0) }
		h.threads.editDraft("changed")
		h.threads.saveEdit()
		assertEquals("changed\n\n${block(img1, img2)}", h.chain.prepared.single().body)
	}

	@Test
	fun `a root keeps its canonical YouTube link and tags when an image is removed`() {
		val h = Harness()
		val authored = "old words\n\n${block(img1, img2, img3)}"
		h.chain.put(
			SnapChainComment(
				"alice", root.permlink, "peak.snaps", "snap-container-1789648560", "",
				"$authored$tail", """{"app":"rustedwax/0.12.2","tags":["scrobblelife","scrobble","rustedwax"]}""",
			),
		)
		h.threads.open(root, PostedSnap("alice", root.permlink, authored, 1_000L, fromChain = true))
		h.threads.startEdit(root, root, SnapEditKind.ROOT, authored)
		h.threads.removeEditImage(1)
		h.threads.saveEdit()

		val op = h.chain.prepared.single()
		assertEquals("old words\n\n${block(img1, img3)}$tail", op.body)
		assertEquals(root.author to root.permlink, op.author to op.permlink)
		assertEquals("peak.snaps" to "snap-container-1789648560", op.parentAuthor to op.parentPermlink)
		assertEquals("old words\n\n${block(img1, img3)}", h.threads.openRootSnap!!.userText)
		assertEquals(1, h.chain.broadcasts)
	}

	@Test
	fun `280 characters with the remaining images is still a valid edit`() {
		val (h, _) = editingReply("w", img1, img2, img3, img4)
		h.threads.removeEditImage(2)
		h.threads.editDraft("x".repeat(280))
		h.threads.saveEdit()
		assertEquals("x".repeat(280) + "\n\n${block(img1, img2, img4)}", h.chain.prepared.single().body)

		val (h2, _) = editingReply("w", img1)
		h2.threads.removeEditImage(0)
		h2.threads.editDraft("x".repeat(281))
		h2.threads.saveEdit()
		assertEquals("281 is refused whatever the images", 0, h2.chain.broadcasts)
	}

	@Test
	fun `images RustedWax cannot identify as its own block stay in the words and are never removable`() {
		val foreign = "https://files.peakd.com/file/peakd-hive/alice/x.jpg"
		listOf(
			// Another frontend's image, in the text.
			"look ![]($foreign) here",
			// One of ours, but inside the words rather than the trailing block.
			"before ![]($img1) after",
			// Our image with alt text: not the exact block form.
			"words\n\n![alt]($img1)",
			// Not set off by a blank line.
			"words\n![]($img1)",
			// Five lines: more than the block can ever hold.
			"words\n\n${block(img1, img2, img3, img4, img1)}",
		).forEach { body ->
			val h = Harness()
			val r = mine.copy(body = body)
			h.chain.put(onChain(r))
			h.reader.replies = listOf(r)
			h.threads.open(root, null)
			h.threads.startEdit(root, SnapReplyTarget.of(r)!!, SnapEditKind.REPLY, r.body)

			assertTrue("nothing offered for removal: $body", h.threads.editImages.isEmpty())
			assertEquals("the whole body stays editable text", body, h.threads.editText)
			h.threads.removeEditImage(0)
			assertEquals(body, h.threads.editedText())
		}
	}

	@Test
	fun `a foreign image in the words survives removing our own block images`() {
		val foreign = "https://files.peakd.com/file/peakd-hive/alice/x.jpg"
		val (h, _) = editingReply("see ![]($foreign)", img1, img2)
		assertEquals("see ![]($foreign)", h.threads.editText)
		h.threads.removeEditImage(0)
		h.threads.saveEdit()
		assertEquals("see ![]($foreign)\n\n${block(img2)}", h.chain.prepared.single().body)
	}

	@Test
	fun `a duplicated image is removed by position, not by address`() {
		val (h, _) = editingReply("words", img1, img2, img1)
		h.threads.removeEditImage(2)
		h.threads.saveEdit()
		assertEquals("words\n\n${block(img1, img2)}", h.chain.prepared.single().body)
	}

	@Test
	fun `the edited body renders only the remaining images, in order, everywhere 40C draws it`() {
		val (h, r) = editingReply("caption", img2, img1, img3)
		h.threads.removeEditImage(1)
		h.threads.saveEdit()
		val body = h.chain.objects[r.contentId]!!.body

		// Re-read from the chain, as a refresh or a reopen does.
		h.reader.replies = listOf(r.copy(body = body), theirs)
		h.threads.load(root, force = true)
		assertEquals(body, bodies(h)[r.contentId])

		val shown = SnapMediaText.display(body)
		assertEquals("caption", shown.text)
		assertEquals(listOf(img2, img3), shown.media.map { it.source })
		assertTrue("the GIF stays first and animated", (shown.media.first() as SnapMediaRef.Image).animated)

		// History: one still thumbnail, the first remaining image.
		val history = SnapMediaText.history(body)
		assertEquals(img2, history.thumbnail!!.source)
		assertFalse(history.thumbnailStill!!.animated)
		assertFalse(history.showsReplyPreview)

		// And with every image gone, History is back to plain words.
		val (h2, r2) = editingReply("plain", img1)
		h2.threads.removeEditImage(0)
		h2.threads.saveEdit()
		val plain = SnapMediaText.history(h2.chain.objects[r2.contentId]!!.body)
		assertNull(plain.thumbnail)
		assertTrue(plain.showsReplyPreview)
	}

	// ── Issue 40D: Save never waits, and Edit can add images ───────────

	private fun until(what: () -> Boolean) {
		val end = System.currentTimeMillis() + 5_000
		while (!what()) {
			check(System.currentTimeMillis() < end) { "timed out" }
			Thread.sleep(5)
		}
	}

	private fun editKey(h: Harness, r: SnapReply) = h.threads.editImagesKey(SnapReplyTarget.of(r)!!)

	@Test
	fun `Save hands the screen back before Hive answers, showing the edit at once`() {
		val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
		try {
			val h = Harness(io = pool.asCoroutineDispatcher())
			h.chain.put(onChain(mine))
			h.chain.put(onChain(theirs))
			h.reader.replies = listOf(mine, theirs)
			h.threads.open(root, null)
			until { h.threads.thread(root) != null }
			val node = java.util.concurrent.CountDownLatch(1)
			h.chain.duringBroadcast = { node.await() }

			h.threads.startEdit(root, SnapReplyTarget.of(mine)!!, SnapEditKind.REPLY, mine.body)
			h.threads.editDraft("new words")
			val started = System.nanoTime()
			h.threads.saveEdit()
			val tookMs = (System.nanoTime() - started) / 1_000_000

			// Hive has not answered — the broadcast is parked — and yet:
			assertTrue("Save returned in ${tookMs}ms", tookMs < 500)
			assertNull("the editor is already closed", h.threads.editing)
			assertTrue(h.threads.isEditSettling(SnapReplyTarget.of(mine)!!))
			assertEquals("the edit shows straight away", "new words", bodies(h)[mine.contentId])

			// A 40C refresh arriving meanwhile, still carrying the old words,
			// does not take the intended edit off the screen.
			h.threads.load(root, force = true)
			until { h.reader.replies != null && bodies(h)[mine.contentId] != null }
			assertEquals("new words", bodies(h)[mine.contentId])

			node.countDown()
			until { h.threads.pendingEdit(SnapReplyTarget.of(mine)!!) == null }
			assertEquals(1, h.chain.broadcasts)
			assertEquals("new words", h.chain.objects[mine.contentId]!!.body)
		} finally {
			pool.shutdownNow()
		}
	}

	@Test
	fun `a removal-only edit also closes at once and shows only the kept images`() {
		val (h, r) = editingReply("words", img1, img2)
		val gate = java.util.concurrent.atomic.AtomicReference<String?>(null)
		h.chain.duringBroadcast = { gate.set(bodies(h)[r.contentId]) }
		h.threads.removeEditImage(0)
		h.threads.saveEdit()
		assertEquals("shown while broadcasting", "words\n\n${block(img2)}", gate.get())
		assertNull(h.threads.editing)
		assertEquals(1, h.chain.broadcasts)
	}

	@Test
	fun `adding images during Edit uploads only the new ones, then edits once`() {
		val (h, r) = editingReply("words", img1, img2)
		val key = editKey(h, r)
		h.images.add(key, "newA", "newB")
		assertEquals(2, h.threads.editAddedImages())
		assertTrue(h.threads.canSaveEdit())

		h.threads.saveEdit()

		assertEquals("only the new images upload", listOf("newA", "newB"), h.images.uploads)
		val op = h.chain.prepared.single()
		assertEquals(
			"words\n\n${block(img1, img2, h.images.urlFor("newA"), h.images.urlFor("newB"))}",
			op.body,
		)
		assertEquals(r.author to r.permlink, op.author to op.permlink)
		assertEquals(r.parentAuthor to r.parentPermlink, op.parentAuthor to op.parentPermlink)
		assertEquals(1, h.chain.broadcasts)
		assertNull(h.threads.pendingEdit(SnapReplyTarget.of(r)!!))
		assertEquals("the new images leave the store once used", 0, h.images.count(key))
	}

	@Test
	fun `remove one and add a replacement keeps four, in remove-then-add order`() {
		val (h, r) = editingReply("words", img1, img2, img3, img4)
		h.threads.removeEditImage(1)
		h.images.add(editKey(h, r), "replacement")
		h.threads.saveEdit()
		assertEquals(
			"words\n\n${block(img1, img3, img4, h.images.urlFor("replacement"))}",
			h.chain.prepared.single().body,
		)
	}

	@Test
	fun `remove every image and add new ones, or add up to four from none`() {
		val (h, r) = editingReply("words", img1, img2)
		h.threads.removeEditImage(0)
		h.threads.removeEditImage(0)
		h.images.add(editKey(h, r), "n1", "n2", "n3", "n4")
		h.threads.saveEdit()
		assertEquals(
			"words\n\n${block(*listOf("n1", "n2", "n3", "n4").map(h.images::urlFor).toTypedArray())}",
			h.chain.prepared.single().body,
		)

		val (h2, r2) = editingReply("only words")
		h2.images.add(editKey(h2, r2), "solo")
		h2.threads.saveEdit()
		assertEquals("only words\n\n${block(h2.images.urlFor("solo"))}", h2.chain.prepared.single().body)
	}

	@Test
	fun `more than four images in all can never be saved`() {
		val (h, r) = editingReply("words", img1, img2, img3, img4)
		h.images.add(editKey(h, r), "fifth")
		assertFalse(h.threads.canSaveEdit())
		h.threads.saveEdit()
		assertEquals(0, h.chain.broadcasts)
		assertTrue(h.images.uploads.isEmpty())
		assertNotNull("nothing is lost: still editing", h.threads.editing)
		assertEquals(listOf(img1, img2, img3, img4), h.threads.editImages)
	}

	@Test
	fun `cancelling an edit with added images uploads nothing and drops them`() {
		val (h, r) = editingReply("words", img1)
		h.images.add(editKey(h, r), "never")
		h.threads.cancelEdit()
		assertTrue(h.images.uploads.isEmpty())
		assertEquals(0, h.images.count(editKey(h, r)))
		assertEquals(0, h.chain.broadcasts)
		assertEquals("words\n\n${block(img1)}", bodies(h)[r.contentId])
	}

	@Test
	fun `a failed upload shows Hive's state again, tells the author, and retry uploads only what failed`() {
		val (h, r) = editingReply("old words", img1)
		h.images.add(editKey(h, r), "ok1", "bad", "ok2")
		h.images.failing += "bad"
		h.threads.editDraft("new words")
		h.threads.saveEdit()

		val target = SnapReplyTarget.of(r)!!
		assertEquals(SnapThreadController.Phase.FAILED, h.threads.pendingEdit(target)!!.phase)
		assertEquals("rolled back", r.body, bodies(h)[r.contentId])
		assertNotNull(h.threads.editFailure)
		assertEquals(0, h.chain.broadcasts)
		assertEquals(listOf("ok1"), h.images.uploads)

		h.images.failing.clear()
		h.threads.retryEdit(target)

		assertEquals("ok1 is never uploaded twice", listOf("ok1", "bad", "ok2"), h.images.uploads)
		assertEquals(1, h.chain.broadcasts)
		assertEquals(
			"new words\n\n${block(img1, h.images.urlFor("ok1"), h.images.urlFor("bad"), h.images.urlFor("ok2"))}",
			h.chain.objects[r.contentId]!!.body,
		)
		assertNull(h.threads.pendingEdit(target))
	}

	@Test
	fun `a failed broadcast after the uploads retries without uploading again`() {
		val (h, r) = editingReply("words", img1)
		h.images.add(editKey(h, r), "fresh")
		h.chain.result = HiveRpc.BroadcastResult.Rejected("no")
		h.chain.evidence = HiveRpc.TransactionEvidence.ABSENT
		h.threads.saveEdit()
		val target = SnapReplyTarget.of(r)!!
		assertEquals(SnapThreadController.Phase.FAILED, h.threads.pendingEdit(target)!!.phase)
		assertEquals(listOf("fresh"), h.images.uploads)
		assertEquals(1, h.chain.broadcasts)

		h.chain.result = HiveRpc.BroadcastResult.Success("tx", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)
		h.threads.retryEdit(target)
		assertEquals("no second upload", listOf("fresh"), h.images.uploads)
		assertEquals(2, h.chain.broadcasts)
		assertNull(h.threads.pendingEdit(target))
	}

	@Test
	fun `an edit that actually landed is not broadcast again by retry`() {
		val (h, r) = editingReply("words", img1)
		// The broadcast reaches the chain, but the answer is lost on the way back.
		h.chain.result = HiveRpc.BroadcastResult.NetworkFailure("lost")
		h.chain.duringBroadcast = {
			val op = h.chain.prepared.last()
			h.chain.put(h.chain.objects.getValue(r.contentId).copy(body = op.body))
		}
		h.threads.editDraft("new words")
		h.threads.saveEdit()
		val target = SnapReplyTarget.of(r)!!
		val afterFirst = h.chain.broadcasts
		val pending = h.threads.pendingEdit(target)
		if (pending != null) {
			h.chain.duringBroadcast = null
			h.threads.retryEdit(target)
		}
		assertEquals("never a second broadcast of the same edit", afterFirst, h.chain.broadcasts)
		// The words being there is not this transaction's proof; its inclusion is.
		h.chain.evidence = HiveRpc.TransactionEvidence.BLOCK
		h.threads.retryEdit(target)
		assertEquals(afterFirst, h.chain.broadcasts)
		assertNull(h.threads.pendingEdit(target))
	}

	@Test
	fun `an uncertain edit stays locked and repeated retry only checks the original transaction`() {
		val (h, r) = editingReply("words", img1)
		val target = SnapReplyTarget.of(r)!!
		h.images.add(editKey(h, r), "fresh")
		h.chain.result = HiveRpc.BroadcastResult.NetworkFailure("lost")
		h.threads.editDraft("new words")
		h.threads.saveEdit()
		assertEquals(SnapThreadController.Phase.UNCERTAIN, h.threads.pendingEdit(target)!!.phase)
		assertTrue(h.threads.isEditSettling(target))
		h.threads.startEdit(root, target, SnapEditKind.REPLY, r.body)
		assertNull(h.threads.editing)
		h.threads.requestDelete(root, target, SnapEditKind.REPLY, r.body)
		assertNull(h.threads.deleting)
		h.threads.discardEdit(target)
		assertNotNull(h.threads.pendingEdit(target))
		repeat(2) { h.threads.retryEdit(target) }
		assertEquals(1, h.chain.broadcasts)
		assertEquals(1, h.chain.prepared.size)
		assertEquals(listOf("fresh"), h.images.uploads)
		// Inclusion may become visible before the object read catches up.
		h.chain.evidence = HiveRpc.TransactionEvidence.BLOCK
		h.threads.retryEdit(target)
		assertNull(h.threads.pendingEdit(target))
		assertEquals(1, h.chain.broadcasts)
		assertTrue(bodies(h)[r.contentId]!!.startsWith("new words"))
	}

	@Test
	fun `a discarded failed edit leaves the comment as Hive holds it and drops its images`() {
		val (h, r) = editingReply("words", img1)
		h.images.add(editKey(h, r), "x1")
		h.images.failing += "x1"
		h.threads.saveEdit()
		val target = SnapReplyTarget.of(r)!!
		h.threads.discardEdit(target)
		assertNull(h.threads.pendingEdit(target))
		assertEquals(0, h.images.count(editKey(h, r)))
		assertEquals(r.body, bodies(h)[r.contentId])
	}

	@Test
	fun `Edit and Delete are refused while an edit is settling`() {
		val (h, r) = editingReply("words", img1)
		val target = SnapReplyTarget.of(r)!!
		var during: Pair<Boolean, Boolean>? = null
		h.chain.duringBroadcast = {
			h.threads.startEdit(root, target, SnapEditKind.REPLY, r.body)
			h.threads.requestDelete(root, target, SnapEditKind.REPLY, r.body)
			during = (h.threads.editing != null) to (h.threads.deleting != null)
		}
		h.threads.editDraft("changed")
		h.threads.saveEdit()
		assertEquals(false to false, during)
	}

	@Test
	fun `a late upload after an account switch signs nothing and keeps the edit for its account`() {
		val (h, r) = editingReply("words", img1)
		h.images.add(editKey(h, r), "late")
		h.images.duringUpload = { h.who = "bob" }
		h.threads.saveEdit()
		assertEquals(0, h.chain.broadcasts)
		assertNull("Bob sees nothing of it", h.threads.pendingEdit(SnapReplyTarget.of(r)!!))
		h.who = "alice"
		assertEquals(
			SnapThreadController.Phase.FAILED,
			h.threads.pendingEdit(SnapReplyTarget.of(r)!!)!!.phase,
		)
	}

	@Test
	fun `a root shows its edit at once, and a failure takes it back and re-reads the root`() {
		val h = Harness()
		val authored = "old words\n\n${block(img1)}"
		h.chain.put(
			SnapChainComment(
				"alice", root.permlink, "peak.snaps", "snap-container-1789648560", "",
				"$authored$tail", """{"app":"rustedwax/0.12.2","tags":["scrobblelife","scrobble","rustedwax"]}""",
			),
		)
		h.threads.open(root, PostedSnap("alice", root.permlink, authored, 1_000L, fromChain = true))
		h.chain.result = HiveRpc.BroadcastResult.Rejected("no")
		h.chain.evidence = HiveRpc.TransactionEvidence.ABSENT
		var shownDuring: String? = null
		h.chain.duringBroadcast = { shownDuring = h.threads.openRootSnap!!.userText }

		h.threads.startEdit(root, root, SnapEditKind.ROOT, authored)
		h.threads.editDraft("new words")
		h.threads.saveEdit()

		assertEquals("new words\n\n${block(img1)}", shownDuring)
		assertEquals(
			listOf(root.contentId to "new words\n\n${block(img1)}", root.contentId to null),
			h.rootPending,
		)
		assertEquals("rolled back", authored, h.threads.openRootSnap!!.userText)
		assertTrue("nothing claimed as confirmed", h.rootEdits.isEmpty())
		assertTrue("the root is re-read", root.contentId in h.rootOpened)
	}

	@Test
	fun `a root edit adding an image keeps the canonical tail`() {
		val h = Harness()
		val authored = "old words\n\n${block(img1)}"
		h.chain.put(
			SnapChainComment(
				"alice", root.permlink, "peak.snaps", "snap-container-1789648560", "",
				"$authored$tail", """{"app":"rustedwax/0.12.2","tags":["scrobblelife","scrobble","rustedwax"]}""",
			),
		)
		h.threads.open(root, PostedSnap("alice", root.permlink, authored, 1_000L, fromChain = true))
		h.threads.startEdit(root, root, SnapEditKind.ROOT, authored)
		h.images.add(h.threads.editImagesKey(root), "added")
		h.threads.editDraft("x".repeat(280))
		h.threads.saveEdit()
		val op = h.chain.prepared.single()
		assertEquals("x".repeat(280) + "\n\n${block(img1, h.images.urlFor("added"))}$tail", op.body)
		assertEquals(root.author to root.permlink, op.author to op.permlink)
		assertEquals(1, h.rootEdits.size)
	}

	@Test
	fun `a failed edit reopens with its words, kept images and waiting new images`() {
		val (h, r) = editingReply("words", img1, img2)
		val key = editKey(h, r)
		h.images.add(key, "pending")
		h.images.failing += "pending"
		h.threads.removeEditImage(0)
		h.threads.editDraft("changed")
		h.threads.saveEdit()

		h.threads.startEdit(root, SnapReplyTarget.of(r)!!, SnapEditKind.REPLY, r.body)
		assertEquals("changed", h.threads.editText)
		assertEquals(listOf(img2), h.threads.editImages)
		assertEquals("the new image is still waiting", 1, h.images.count(key))
	}

	@Test
	fun `an image hosted while another account was signed in is reused, once, by the original edit`() {
		val (h, r) = editingReply("words", img1)
		val target = SnapReplyTarget.of(r)!!
		val uploads = mutableListOf<String>()
		var switchOnUpload = true
		// The composer's real image store, with only the platform faked.
		val store = SnapAttachmentController(
			scope = CoroutineScope(Dispatchers.Unconfined),
			intake = object : SnapImageIntake {
				override fun take(source: SnapImageSource) = SnapImageIntake.Taken.Accepted(
					java.io.File(source.uri.substringAfterLast('/')),
					com.rustedwax.app.snaps.SnapImageFormat.JPEG,
					10,
				)
				override fun discard(file: java.io.File) = Unit
			},
			uploader = {
				SnapImageUploader { account, attachment, _ ->
					uploads += "$account:${attachment.file.name}"
					// The host answers — successfully — after the switch.
					if (switchOnUpload) h.who = "bob"
					com.rustedwax.hive.ImageHoster.Result.Uploaded(h.images.urlFor("shared"))
				}
			},
			account = { h.who },
			io = Dispatchers.Unconfined,
		)
		h.port = store
		val key = editKey(h, r)
		store.add(key, listOf(SnapImageSource("content://test/shared", SnapImageOrigin.PICKER)), reserved = 1)
		h.threads.editDraft("new words")

		h.threads.saveEdit()

		assertEquals("uploaded once, as alice", listOf("alice:shared"), uploads)
		assertEquals("nothing is signed for bob", 0, h.chain.broadcasts)
		assertNull("bob sees nothing of alice's edit", h.threads.pendingEdit(target))

		h.who = "alice"
		switchOnUpload = false
		assertEquals(SnapThreadController.Phase.FAILED, h.threads.pendingEdit(target)!!.phase)
		h.threads.retryEdit(target)
		h.threads.retryEdit(target)

		assertEquals("the hosted image is reused, never uploaded again", listOf("alice:shared"), uploads)
		assertEquals("exactly one edit", 1, h.chain.broadcasts)
		assertEquals("words\n\n${block(img1, h.images.urlFor("shared"))}".replaceFirst("words", "new words"), h.chain.prepared.single().body)
		assertNull(h.threads.pendingEdit(target))
	}
}
