package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.PendingSnap
import com.rustedwax.app.snaps.PendingSnapKind
import com.rustedwax.app.snaps.PendingSnapRead
import com.rustedwax.app.snaps.PendingSnapState
import com.rustedwax.app.snaps.PendingSnapStore
import com.rustedwax.app.snaps.SnapHivePort
import com.rustedwax.app.snaps.SnapPublisher
import com.rustedwax.app.snaps.SnapReply
import com.rustedwax.app.snaps.SnapReplyKey
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
 * Screen-level safety for replies: the crash window, duplicate taps, the
 * character rule, and the one thing account isolation actually has to mean —
 * that switching Hive accounts never shows, or acts on, the previous account's
 * state.
 *
 * Runs unconfined so `launch` completes inline; no coroutine-test dependency is
 * added for this.
 */
class SnapThreadControllerTest {

	private val root = SnapReplyTarget.of("alice", "rustedwax-snap-1000-aaaaaa")!!

	// ── fakes ──────────────────────────────────────────────────────────

	/**
	 * A reply-draft store that survives a "restart".
	 *
	 * Backed by a map handed in from the test, so a second controller can be
	 * built over the same durable bytes — which is how process death is
	 * expressed here.
	 */
	private class Drafts(
		val saved: MutableMap<String, String> = mutableMapOf(),
	) : SnapReplyDraftStore {
		/** Flip to simulate a disk that will not accept the intent record. */
		var writable = true
		/** Flip to simulate a `commit()` that will not stick on removal. */
		var removable = true
		var intentWrites = 0
		var removeAttempts = 0

		override fun read(key: String): SnapReplyDraftRead {
			val raw = saved[key] ?: return SnapReplyDraftRead.Absent
			return SnapReplyDraft.fromJson(raw)?.let(SnapReplyDraftRead::Present)
				?: SnapReplyDraftRead.Corrupt("unreadable")
		}

		private fun draftOrNone(key: String) =
			(read(key) as? SnapReplyDraftRead.Present)?.draft ?: SnapReplyDraft.NONE

		override fun writeText(key: String, text: String) {
			if (read(key) is SnapReplyDraftRead.Corrupt) return
			val next = draftOrNone(key).copy(text = text)
			if (next.isEmpty) saved.remove(key) else saved[key] = next.toJson()
		}

		override fun beginIntent(key: String, intentId: String): Boolean {
			if (read(key) is SnapReplyDraftRead.Corrupt) return false
			intentWrites++
			if (!writable) return false
			saved[key] = draftOrNone(key).copy(intentId = intentId).toJson()
			return true
		}

		override fun removeIf(key: String, expected: SnapReplyDraft?): Boolean {
			removeAttempts++
			val raw = saved[key] ?: return false
			if (SnapReplyDraft.fromJson(raw) != expected) return false
			if (!removable) return false
			saved.remove(key)
			return true
		}

		override fun settle(key: String, published: SnapReplyDraft): SnapReplySettlement {
			val raw = saved[key] ?: return SnapReplySettlement.Untouched
			val current = SnapReplyDraft.fromJson(raw) ?: return SnapReplySettlement.Untouched
			if (current.intentId == null || current.intentId != published.intentId) {
				return SnapReplySettlement.Untouched
			}
			if (!removable) return SnapReplySettlement.Failed
			if (current == published) {
				saved.remove(key)
				return SnapReplySettlement.Retired
			}
			val released = current.copy(intentId = null)
			saved[key] = released.toJson()
			return SnapReplySettlement.Released(released)
		}
	}

	/** Survives a restart the same way: the map is the disk. */
	private class Store(val saved: MutableMap<String, String> = mutableMapOf()) : PendingSnapStore {
		/** Flip to simulate a disk that will not hold the reply record. */
		var writable = true
		override fun read(account: String, eventId: String) =
			saved["$account|$eventId"]?.let { PendingSnap.fromJson(it) }
				?.let(PendingSnapRead::Present) ?: PendingSnapRead.Absent
		override fun write(snap: PendingSnap): Boolean {
			if (!writable) return false
			saved["${snap.account}|${snap.eventId}"] = snap.toJson(); return true
		}
		override fun clear(account: String, eventId: String) { saved.remove("$account|$eventId") }
		override fun all(account: String) = saved.entries
			.filter { it.key.startsWith("$account|") }.mapNotNull { PendingSnap.fromJson(it.value) }
		override fun corruptEventIds(account: String) = emptySet<String>()
	}

	private class Hive(var result: HiveRpc.BroadcastResult) : SnapHivePort {
		var broadcasts = 0
		var content: Boolean? = false
		/** Runs while the "network call" is in flight, to race the cleanup. */
		var duringBroadcast: (() -> Unit)? = null
		/**
		 * Runs while the transaction is being built — the first moment the
		 * network is involved at all. What the screen looks like *here* is what
		 * the user would be staring at on a slow connection.
		 */
		var duringPrepare: (() -> Unit)? = null
		/** Flip to make preparation fail, leaving the intent unfinished. */
		var preparable = true
		val preparedOps = mutableListOf<TxSerializer.CommentOp>()
		override fun resolveContainer() = SnapContainerResolver.Result.Resolved(
			SnapContainer("peak.snaps", "snap-container-1789648560", "2026-09-17T12:36:00"),
		)
		override fun prepareComment(operation: TxSerializer.CommentOp, author: String): HivePreparationResult {
			duringPrepare?.invoke()
			if (!preparable) {
				return HivePreparationResult.Failed(
					HiveRpc.BroadcastResult.NetworkFailure("no chain head"),
				)
			}
			preparedOps += operation
			return HivePreparationResult.Ready(
				PreparedHiveTransaction(
					"""{"op":"${operation.permlink}"}""",
					"tx-${operation.permlink}",
					2_000_000_000L,
				),
			)
		}
		override fun broadcastPrepared(prepared: PreparedHiveTransaction, author: String): HiveRpc.BroadcastResult {
			broadcasts++
			duringBroadcast?.invoke()
			return result
		}
		override fun observeTransaction(txId: String, expirationEpochSec: Long) =
			HiveRpc.TransactionEvidence.UNAVAILABLE
		override fun contentExists(author: String, permlink: String): Boolean? = content
	}

	private class Reader(var replies: List<SnapReply>?) : SnapThreadReader {
		var reads = 0
		/** Runs while a read is in flight — used to race `open` against itself. */
		var duringRead: (() -> Unit)? = null
		/** The viewer each read was made for, so account scoping can be asserted. */
		var lastViewer: String? = null
		override fun read(
			rootAuthor: String,
			rootPermlink: String,
			viewer: String?,
		): List<SnapReply>? {
			reads++
			lastViewer = viewer
			duringRead?.invoke()
			return replies
		}
	}

	/**
	 * A card-summary store that survives a "restart".
	 *
	 * Backed by a map handed in from the test, so a second controller can be
	 * built over the same bytes — which is how Activity recreation is expressed.
	 */
	private class Previews(
		val saved: MutableMap<String, SnapThreadPreview.Preview> = mutableMapOf(),
	) : SnapThreadPreviewStore {
		var reads = 0
		var writes = 0
		/** Entries the real store would refuse to decode. */
		val corrupt = mutableSetOf<String>()

		override fun read(key: String): SnapThreadPreview.Preview? {
			reads++
			if (key in corrupt) return null
			return saved[key]
		}

		override fun write(key: String, preview: SnapThreadPreview.Preview) {
			writes++
			saved[key] = preview
		}
	}

	/** Permlinks from a shared pool, so a "restart" cannot reuse one by luck. */
	private class Permlinks {
		private var next = 0
		fun mint(): String = "rustedwax-reply-1000-x${next++}"
	}

	private fun controller(
		hive: Hive = Hive(inBlock()),
		reader: Reader = Reader(emptyList()),
		drafts: Drafts = Drafts(),
		store: Store = Store(),
		permlinks: Permlinks = Permlinks(),
		previews: Previews = Previews(),
		account: () -> String?,
	): SnapThreadController {
		val publisher = SnapPublisher(
			hive,
			store,
			nowEpochSec = { 1_000L },
			newReplyPermlink = { permlinks.mint() },
		)
		return SnapThreadController(
			scope = CoroutineScope(Dispatchers.Unconfined),
			reader = { reader },
			publisher = { publisher },
			account = account,
			drafts = drafts,
			previewStore = previews,
			io = Dispatchers.Unconfined,
		)
	}

	private fun inBlock() =
		HiveRpc.BroadcastResult.Success("tx", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)

	private fun reply(author: String, permlink: String, parent: SnapReplyTarget) =
		SnapReply(author, permlink, parent.author, parent.permlink, "hi", 1_000L)

	/** The decoded draft at one key, or null when there is none. */
	private fun storedDraft(drafts: Drafts, key: String): SnapReplyDraft? =
		(drafts.read(key) as? SnapReplyDraftRead.Present)?.draft

	/** The intent id of the one attempt recorded in the pending store. */
	private fun soleIntent(store: Store): String =
		SnapReplyKey.intentOf(store.saved.keys.single().substringAfter('|'))!!

	// ── reading ────────────────────────────────────────────────────────

	@Test
	fun `a loaded thread is built and not re-fetched`() {
		val reader = Reader(listOf(reply("bob", "r1", root)))
		val threads = controller(reader = reader) { "alice" }

		threads.load(root)
		threads.load(root)

		assertEquals(1, reader.reads)
		assertEquals(1, threads.thread(root)!!.total)
	}

	/**
	 * The reachability path end to end: what the reader returned is what the
	 * screen gets, all of it.
	 *
	 * `bridge.get_discussion` answers with the complete subtree in one response,
	 * so there is no second request to make and nothing is held back — the
	 * dedicated thread renders the whole list incrementally through its
	 * `LazyColumn`.
	 */
	@Test
	fun `a conversation far past the old node cap reaches the screen whole`() {
		val many = (0 until 1_500).map {
			SnapReply("bob", "r$it", root.author, root.permlink, "reply $it", 1_000L + it)
		}
		val threads = controller(reader = Reader(many)) { "alice" }

		threads.load(root)

		val thread = threads.thread(root)!!
		assertEquals(1_500, thread.total)
		assertEquals(1_500, thread.rows.size)
		assertEquals(
			many.map { it.contentId }.toSet(),
			thread.rows.map { it.reply.contentId }.toSet(),
		)
		// And the card it feeds is still a card.
		assertEquals(2, threads.preview(root)!!.items.size)
		assertEquals(1_500, threads.preview(root)!!.total)
	}

	/** Scale and long bodies together: neither cap comes back. */
	@Test
	fun `a large conversation of long replies keeps every body whole`() {
		val body = "long ".repeat(15_000)
		val many = (0 until 600).map {
			SnapReply("bob", "r$it", root.author, root.permlink, body, 1_000L + it)
		}
		val threads = controller(reader = Reader(many)) { "alice" }

		threads.load(root)

		val rows = threads.thread(root)!!.rows
		assertEquals(600, rows.size)
		assertTrue(rows.all { it.reply.body == body })
		// The card clamps its two-line summary, and only the card.
		assertTrue(
			threads.preview(root)!!.items.all {
				it.truncated && it.text.length <= 140
			},
		)
	}

	@Test
	fun `a chain that could not be read is not an empty thread`() {
		val threads = controller(reader = Reader(null)) { "alice" }

		threads.load(root)

		assertTrue(threads.state(root) is SnapThreadLoad.Unavailable)
		assertNull(threads.preview(root))
	}

	@Test
	fun `a reader that throws does not crash the screen`() {
		val throwing = object : SnapThreadReader {
			override fun read(
				rootAuthor: String,
				rootPermlink: String,
				viewer: String?,
			): List<SnapReply>? =
				throw IllegalStateException("boom")
		}
		val threads = SnapThreadController(
			scope = CoroutineScope(Dispatchers.Unconfined),
			reader = { throwing },
			publisher = { null },
			account = { "alice" },
			drafts = Drafts(),
			previewStore = Previews(),
			io = Dispatchers.Unconfined,
		)

		threads.load(root)

		assertTrue(threads.state(root) is SnapThreadLoad.Unavailable)
	}

	// ── the reopen cache: seed before the network ─────────────────────

	/**
	 * Closing the app from Recents destroys the Activity — and with it these
	 * controllers — while the process lives on. A reopened card must draw what
	 * it last knew instead of regressing to nothing until Hive answers.
	 */
	@Test
	fun `a fresh controller seeds the card from the persisted summary`() {
		val previews = Previews()
		val reader = Reader(listOf(reply("bob", "r1", root), reply("carol", "r2", root)))
		val first = controller(reader = reader, previews = previews) { "alice" }
		first.load(root)
		assertEquals(2, first.preview(root)!!.total)
		assertEquals(1, previews.writes)

		// The Activity is recreated: brand-new controller, same durable store,
		// and a reader that has not answered yet.
		val slow = Reader(null)
		val restarted = controller(reader = slow, previews = previews) { "alice" }
		restarted.load(root)

		val seeded = restarted.preview(root)!!
		assertEquals("the count survives", 2, seeded.total)
		assertEquals(2, seeded.items.size)
	}

	/** Seeding must not require the chain to answer at all. */
	@Test
	fun `the seeded summary is available with zero successful Hive reads`() {
		val previews = Previews()
		previews.saved[SnapDraftKey.of("alice", root.contentId)] = SnapThreadPreview.Preview(
			items = listOf(SnapThreadPreview.Item("bob", "r1", "hello", truncated = false)),
			total = 7,
		)
		val reader = Reader(null)
		val threads = controller(reader = reader, previews = previews) { "alice" }

		threads.load(root)

		val p = threads.preview(root)!!
		assertEquals(7, p.total)
		assertEquals("bob", p.items.single().author)
		assertTrue("7 total against 1 shown must offer a way through", p.hasMore)
		assertTrue("the thread itself is still unknown", threads.thread(root) == null)
	}

	@Test
	fun `a successful refresh replaces the persisted summary`() {
		val previews = Previews()
		val key = SnapDraftKey.of("alice", root.contentId)
		previews.saved[key] = SnapThreadPreview.Preview(
			items = listOf(SnapThreadPreview.Item("bob", "r1", "stale", truncated = false)),
			total = 1,
		)
		val reader = Reader((1..4).map { reply("someone$it", "re-$it", root) })
		val threads = controller(reader = reader, previews = previews) { "alice" }

		threads.open(root, null)

		assertEquals(4, threads.preview(root)!!.total)
		assertEquals("the store moved forward", 4, previews.saved[key]!!.total)
	}

	@Test
	fun `a failed refresh preserves the persisted summary`() {
		val previews = Previews()
		val key = SnapDraftKey.of("alice", root.contentId)
		val good = SnapThreadPreview.Preview(
			items = listOf(SnapThreadPreview.Item("bob", "r1", "good", truncated = false)),
			total = 3,
		)
		previews.saved[key] = good
		val threads = controller(reader = Reader(null), previews = previews) { "alice" }

		threads.open(root, null)

		assertEquals("nothing may overwrite it", good, previews.saved[key])
		assertEquals(3, threads.preview(root)!!.total)
	}

	@Test
	fun `a corrupt persisted summary fails safe to no preview`() {
		val previews = Previews()
		val key = SnapDraftKey.of("alice", root.contentId)
		previews.saved[key] = SnapThreadPreview.Preview(emptyList(), 0)
		previews.corrupt += key
		val threads = controller(reader = Reader(null), previews = previews) { "alice" }

		threads.load(root)

		assertNull("a card with no summary waits; it never shows guessed content",
			threads.preview(root))
	}

	@Test
	fun `one account never sees another account's persisted summary`() {
		val previews = Previews()
		previews.saved[SnapDraftKey.of("alice", root.contentId)] = SnapThreadPreview.Preview(
			items = listOf(SnapThreadPreview.Item("bob", "r1", "alice's view", truncated = false)),
			total = 9,
		)
		var signedIn = "bob"
		val threads = controller(reader = Reader(null), previews = previews) { signedIn }

		threads.load(root)

		assertNull("bob has no summary of his own", threads.preview(root))
		signedIn = "alice"
		threads.load(root)
		assertEquals(9, threads.preview(root)!!.total)
	}

	/** The seed happens once; recomposition must not re-read the disk. */
	@Test
	fun `the summary is read from the store once, not on every load`() {
		val previews = Previews()
		previews.saved[SnapDraftKey.of("alice", root.contentId)] = SnapThreadPreview.Preview(
			items = emptyList(), total = 2,
		)
		val threads = controller(reader = Reader(null), previews = previews) { "alice" }

		threads.load(root)
		repeat(20) { threads.load(root) }

		assertEquals(1, previews.reads)
	}

	// ── opening the thread refreshes it ───────────────────────────────

	/**
	 * The A12 bug, reproduced and fixed.
	 *
	 * A thread was read once and then cached for the life of the process, so
	 * replies posted by anybody else afterwards were never seen: opening the
	 * sheet called an *unforced* load, which returns early on a cached key.
	 * Opening is now the deliberate act that re-reads.
	 */
	@Test
	fun `opening the thread re-reads it and discovers external replies`() {
		val reader = Reader(listOf(reply("skiptvads.vidz", "rustedwax-reply-1", root)))
		val threads = controller(reader = reader) { "alice" }

		// First look: what History's composition fetched.
		threads.load(root)
		assertEquals(1, reader.reads)
		assertEquals(1, threads.thread(root)!!.total)
		assertEquals(1, threads.preview(root)!!.total)

		// Two other people reply on Hive while the app sits there. Timestamps
		// mirror the real fixture: the RustedWax reply is the oldest, the
		// third-party reply to the root came next, the nested one last.
		reader.replies = listOf(
			SnapReply(
				"skiptvads.vidz", "rustedwax-reply-1",
				root.author, root.permlink, "testing reply stage4", 1_000L,
			),
			SnapReply(
				"palomap3", "re-external-root",
				root.author, root.permlink, "a third-party reply to the root", 2_000L,
			),
			SnapReply(
				"skiptvads", "re-external-nested",
				"skiptvads.vidz", "rustedwax-reply-1", "a 200 character external reply", 3_000L,
			),
		)

		threads.open(root, null)

		assertEquals("opening must re-read", 2, reader.reads)
		val thread = threads.thread(root)!!
		assertEquals(3, thread.total)
		// Depth-first reading order: the RustedWax reply, then what hangs off
		// it, then the later top-level reply.
		assertEquals(
			listOf(
				"skiptvads.vidz/rustedwax-reply-1",
				"skiptvads/re-external-nested",
				"palomap3/re-external-root",
			),
			thread.rows.map { it.reply.contentId },
		)
		assertEquals(listOf(0, 1, 0), thread.rows.map { it.depth })
	}

	/** The refreshed tree keeps the real parent/child shape, at both depths. */
	@Test
	fun `refreshed external replies keep their nesting`() {
		val reader = Reader(listOf(reply("skiptvads.vidz", "rustedwax-reply-1", root)))
		val threads = controller(reader = reader) { "alice" }
		threads.load(root)
		reader.replies = listOf(
			reply("skiptvads.vidz", "rustedwax-reply-1", root),
			reply("palomap3", "re-external-root", root),
			SnapReply(
				"skiptvads", "re-external-nested",
				"skiptvads.vidz", "rustedwax-reply-1", "nested under the RustedWax reply", 3_000L,
			),
		)

		threads.open(root, null)

		val thread = threads.thread(root)!!
		// Two direct children of the root; the third hangs off the first.
		assertEquals(2, thread.children.size)
		val rustedwax = thread.children.first { it.reply.permlink == "rustedwax-reply-1" }
		assertEquals(0, rustedwax.depth)
		assertEquals(1, rustedwax.children.size)
		assertEquals("skiptvads/re-external-nested", rustedwax.children.single().reply.contentId)
		assertEquals(1, rustedwax.children.single().depth)
		val external = thread.children.first { it.reply.permlink == "re-external-root" }
		assertEquals(0, external.depth)
		assertTrue(external.children.isEmpty())
	}

	/** The History card follows the refreshed data, count included. */
	@Test
	fun `the History preview and count update from the refreshed read`() {
		val reader = Reader(listOf(reply("skiptvads.vidz", "rustedwax-reply-1", root)))
		val threads = controller(reader = reader) { "alice" }
		threads.load(root)
		assertEquals(1, threads.preview(root)!!.total)
		assertFalse(threads.preview(root)!!.hasMore)

		reader.replies = (1..5).map { reply("someone$it", "re-$it", root) }
		threads.open(root, null)

		val preview = threads.preview(root)!!
		assertEquals("View replies (N) must use the refreshed count", 5, preview.total)
		assertEquals(2, preview.items.size)
		assertTrue(preview.hasMore)
	}

	/**
	 * Composition must stay cheap. History recomposes about once a second, and
	 * its unforced load is what keeps that from becoming a request per frame.
	 */
	@Test
	fun `recomposition without a Thread open does not re-read`() {
		val reader = Reader(listOf(reply("bob", "r1", root)))
		val threads = controller(reader = reader) { "alice" }

		threads.load(root)
		repeat(50) { threads.load(root) }

		assertEquals(1, reader.reads)
	}

	/**
	 * A deliberate action can still arrive twice — a double tap, or a
	 * recomposition landing on the same handler. The second must join the first.
	 */
	@Test
	fun `a repeated open while a read is in flight does not duplicate the read`() {
		val reader = Reader(listOf(reply("bob", "r1", root)))
		val threads = controller(reader = reader) { "alice" }
		// Re-enter `open` from inside the read itself: the first read has not
		// finished, so the guard is genuinely exercised.
		reader.duringRead = {
			threads.open(root, null)
			threads.open(root, null)
			threads.load(root, force = true)
		}

		threads.open(root, null)

		assertEquals("one RPC for one conversation", 1, reader.reads)
		assertEquals(1, threads.thread(root)!!.total)
	}

	@Test
	fun `a later open after a read finished does re-read`() {
		val reader = Reader(listOf(reply("bob", "r1", root)))
		val threads = controller(reader = reader) { "alice" }

		threads.open(root, null)
		threads.close()
		threads.open(root, null)

		assertEquals(2, reader.reads)
	}

	/** A refresh that fails must not cost the conversation already on screen. */
	@Test
	fun `a failed refresh keeps the previously good thread`() {
		val reader = Reader(listOf(reply("bob", "r1", root), reply("carol", "r2", root)))
		val threads = controller(reader = reader) { "alice" }
		threads.load(root)
		assertEquals(2, threads.thread(root)!!.total)

		reader.replies = null
		threads.open(root, null)

		assertEquals(2, reader.reads)
		val state = threads.state(root)
		assertTrue("must stay Ready, not Unavailable", state is SnapThreadLoad.Ready)
		assertEquals(2, threads.thread(root)!!.total)
		assertEquals("the card keeps its preview too", 2, threads.preview(root)!!.total)
	}

	/** With nothing cached, a failed read still reports honestly. */
	@Test
	fun `a first read that fails is still reported as unavailable`() {
		val threads = controller(reader = Reader(null)) { "alice" }

		threads.open(root, null)

		assertTrue(threads.state(root) is SnapThreadLoad.Unavailable)
		assertNull(threads.preview(root))
	}

	/** And it recovers once the chain answers again. */
	@Test
	fun `a retry after a failed first read recovers`() {
		val reader = Reader(null)
		val threads = controller(reader = reader) { "alice" }
		threads.open(root, null)
		assertTrue(threads.state(root) is SnapThreadLoad.Unavailable)

		reader.replies = listOf(reply("bob", "r1", root))
		threads.load(root, force = true)

		assertTrue(threads.state(root) is SnapThreadLoad.Ready)
		assertEquals(1, threads.thread(root)!!.total)
	}

	/** Account isolation still holds across a refresh that lands after a switch. */
	@Test
	fun `a refresh completing after an account switch writes nothing`() {
		val reader = Reader(listOf(reply("bob", "r1", root)))
		var signedIn = "alice"
		val threads = controller(reader = reader) { signedIn }
		threads.load(root)
		val aliceThread = threads.thread(root)!!
		assertEquals(1, aliceThread.total)

		// The switch happens while the refresh is in flight.
		reader.replies = listOf(reply("bob", "r1", root), reply("carol", "r2", root))
		reader.duringRead = { signedIn = "bob" }
		threads.open(root, null)

		// Nothing landed under bob's key...
		assertNull(threads.state(root))
		assertNull(threads.preview(root))
		// ...and alice's cached conversation is untouched by bob's session.
		signedIn = "alice"
		assertEquals(1, threads.thread(root)!!.total)
	}

	/** The in-flight guard is released even when the account changed mid-read. */
	@Test
	fun `a refresh abandoned by an account switch does not wedge later reads`() {
		val reader = Reader(listOf(reply("bob", "r1", root)))
		var signedIn = "alice"
		val threads = controller(reader = reader) { signedIn }
		threads.load(root)
		reader.duringRead = { signedIn = "bob" }
		threads.open(root, null)
		assertEquals(2, reader.reads)

		signedIn = "alice"
		reader.duringRead = null
		reader.replies = listOf(reply("bob", "r1", root), reply("carol", "r2", root))
		threads.open(root, null)

		assertEquals("a later open must not be refused by a stale guard", 3, reader.reads)
		assertEquals(2, threads.thread(root)!!.total)
	}

	// ── the crash window ───────────────────────────────────────────────

	/**
	 * The Codex failure, end to end.
	 *
	 * A reply confirms and its record is persisted; the process dies before the
	 * draft is cleared. On restart the draft and its intent are both still
	 * there, so a Send finds the confirmed record for *that intent* and answers
	 * with the comment that already exists: no permlink, no signature, no
	 * broadcast. The draft is then retired, exactly as the uninterrupted path
	 * would have retired it.
	 */
	@Test
	fun `a crash after CONFIRMED and before the draft is cleared cannot duplicate`() {
		val hive = Hive(inBlock())
		val drafts = Drafts()
		val store = Store()
		val permlinks = Permlinks()

		val dying = controller(hive, drafts = drafts, store = store, permlinks = permlinks) {
			"alice"
		}
		val key = dying.replyKey(root)
		dying.edit(key, "nice one")
		dying.send(root, root)
		assertEquals(1, hive.broadcasts)

		// The process died before the draft was cleared. Put it back exactly as
		// disk would have held it.
		drafts.saved[key] = SnapReplyDraft("nice one", soleIntent(store)).toJson()

		val restarted = controller(hive, drafts = drafts, store = store, permlinks = permlinks) {
			"alice"
		}
		restarted.send(root, root)

		assertEquals("no second transaction", 1, hive.broadcasts)
		assertEquals("no second permlink", 1, hive.preparedOps.size)
		assertTrue(restarted.status(key) is SnapPostStatus.Posted)
		assertEquals("the draft is retired", "", restarted.draft(key))
		assertFalse(drafts.saved.containsKey(key))
	}

	/** Restart settles it without anybody tapping anything. */
	@Test
	fun `resuming after that crash retires the draft without broadcasting`() {
		val hive = Hive(inBlock())
		val drafts = Drafts()
		val store = Store()
		val first = controller(hive, drafts = drafts, store = store) { "alice" }
		val key = first.replyKey(root)
		first.edit(key, "nice one")
		first.send(root, root)
		drafts.saved[key] = SnapReplyDraft("nice one", soleIntent(store)).toJson()

		val restarted = controller(hive, drafts = drafts, store = store) { "alice" }
		restarted.resumePending()

		assertEquals(1, hive.broadcasts)
		assertTrue(restarted.status(key) is SnapPostStatus.Posted)
		assertEquals("", restarted.draft(key))
	}

	/**
	 * The rule that stops the fix becoming a different bug: a draft the user
	 * started *after* an earlier reply has no intent of its own yet, so nothing
	 * about that earlier confirmed reply may touch it.
	 */
	@Test
	fun `resuming does not destroy a new unsent draft to the same parent`() {
		val hive = Hive(inBlock())
		val drafts = Drafts()
		val store = Store()
		val first = controller(hive, drafts = drafts, store = store) { "alice" }
		val key = first.replyKey(root)
		first.edit(key, "first reply")
		first.send(root, root)
		assertEquals("", first.draft(key))

		// The user starts something new to the same person.
		first.edit(key, "and another thing")

		val restarted = controller(hive, drafts = drafts, store = store) { "alice" }
		restarted.resumePending()

		assertEquals("and another thing", restarted.draft(key))
		assertNull(storedDraft(drafts, key)!!.intentId)
	}

	/** And that new draft still publishes, under an intent of its own. */
	@Test
	fun `a genuinely later reply to the same parent still publishes`() {
		val hive = Hive(inBlock())
		val drafts = Drafts()
		val store = Store()
		val threads = controller(hive, drafts = drafts, store = store) { "alice" }
		val key = threads.replyKey(root)

		threads.edit(key, "first reply")
		threads.send(root, root)
		threads.edit(key, "and another thing")
		threads.send(root, root)

		assertEquals(2, hive.broadcasts)
		assertEquals(2, hive.preparedOps.size)
		assertTrue(hive.preparedOps[0].permlink != hive.preparedOps[1].permlink)
		assertEquals(listOf("first reply", "and another thing"), hive.preparedOps.map { it.body })
	}

	/** An ambiguous attempt that survives a restart may only read. */
	@Test
	fun `an ambiguous reply cannot be resent after a restart`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val drafts = Drafts()
		val store = Store()
		val first = controller(hive, drafts = drafts, store = store) { "alice" }
		val key = first.replyKey(root)
		first.edit(key, "nice one")
		first.send(root, root)
		assertEquals(1, hive.broadcasts)

		val restarted = controller(hive, drafts = drafts, store = store) { "alice" }
		restarted.send(root, root)

		assertEquals(1, hive.broadcasts)
		assertEquals(1, hive.preparedOps.size)
		assertTrue(restarted.status(key) is SnapPostStatus.Uncertain)
		assertEquals("the draft survives", "nice one", restarted.draft(key))
	}

	/** An intent that could not be stored means nothing was sent. */
	@Test
	fun `a reply whose intent cannot be saved is never broadcast`() {
		val hive = Hive(inBlock())
		val drafts = Drafts().apply { writable = false }
		val threads = controller(hive, drafts = drafts) { "alice" }
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")

		threads.send(root, root)

		assertEquals(0, hive.broadcasts)
		assertTrue(threads.status(key) is SnapPostStatus.Failed)
		assertEquals("nice one", threads.draft(key))
	}

	/** The intent is minted once and reused, not re-minted on every attempt. */
	@Test
	fun `a second attempt reuses the stored intent`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val drafts = Drafts()
		val threads = controller(hive, drafts = drafts) { "alice" }
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")

		threads.send(root, root)
		val intent = storedDraft(drafts, key)!!.intentId
		threads.send(root, root)

		assertNotNull(intent)
		assertEquals(intent, storedDraft(drafts, key)!!.intentId)
		assertEquals("the intent is committed once", 1, drafts.intentWrites)
	}

	// ── a corrupt draft fails closed ───────────────────────────────────

	/**
	 * The draft was sent and confirmed under intent X; its stored entry has
	 * since become unreadable, so X is lost with it.
	 *
	 * Nothing may guess. An earlier revision derived a replacement intent from
	 * the key — stable, and still a *different* record, a different permlink and
	 * a second public comment saying the same thing. The slot is locked instead.
	 */
	@Test
	fun `a corrupt draft whose reply already published can never broadcast again`() {
		val hive = Hive(inBlock())
		val drafts = Drafts()
		val store = Store()
		val threads = controller(hive, drafts = drafts, store = store) { "alice" }
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")
		threads.send(root, root)
		assertEquals(1, hive.broadcasts)
		val confirmed = soleIntent(store)
		val intentsBefore = drafts.intentWrites

		// The entry survives the crash, but not intact.
		drafts.saved[key] = "{not json at all"

		val restarted = controller(hive, drafts = drafts, store = store) { "alice" }
		restarted.open(root, null)
		restarted.startReply(key)
		restarted.edit(key, "nice one")
		restarted.send(root, root)

		assertEquals("no second transaction", 1, hive.broadcasts)
		assertEquals("no second permlink", 1, hive.preparedOps.size)
		assertEquals("no new intent may be committed", intentsBefore, drafts.intentWrites)
		assertNotNull("the slot is surfaced as locked", restarted.corruptReason(key))
		assertEquals("the corrupt entry is left exactly as found", "{not json at all", drafts.saved[key])
		// The confirmed record is untouched and still the only reply on chain.
		assertEquals(1, store.saved.size)
		assertEquals(confirmed, soleIntent(store))
	}

	/** Reopening and restarting must not wear the refusal down. */
	@Test
	fun `a corrupt draft stays non-sendable across repeated opens and restarts`() {
		val hive = Hive(inBlock())
		val drafts = Drafts(mutableMapOf<String, String>())
		val store = Store()
		val key = controller(hive, drafts = drafts, store = store) { "alice" }.replyKey(root)
		drafts.saved[key] = "}}broken{{"

		repeat(5) {
			val threads = controller(hive, drafts = drafts, store = store) { "alice" }
			threads.resumePending()
			threads.open(root, null)
			threads.startReply(key)
			threads.edit(key, "let me in")
			threads.send(root, root)
			threads.recheck(root, root)

			assertNotNull(threads.corruptReason(key))
			assertEquals("", threads.draft(key))
		}

		assertEquals(0, hive.broadcasts)
		assertEquals(0, hive.preparedOps.size)
		assertEquals(0, drafts.intentWrites)
		assertEquals("}}broken{{", drafts.saved[key])
	}

	/** Typing into a locked slot must not quietly repair it. */
	@Test
	fun `a corrupt draft is never overwritten by editing`() {
		val drafts = Drafts()
		val threads = controller(drafts = drafts) { "alice" }
		val key = threads.replyKey(root)
		drafts.saved[key] = "not json"
		threads.open(root, null)
		threads.startReply(key)

		threads.edit(key, "typed over the top")

		assertEquals("not json", drafts.saved[key])
		assertEquals("", threads.draft(key))
	}

	/** Explicit, destructive, and only this one. */
	@Test
	fun `discarding a corrupt draft removes only that draft`() {
		val drafts = Drafts()
		val threads = controller(drafts = drafts) { "alice" }
		val other = SnapReplyTarget.of("bob", "re-one")!!
		val key = threads.replyKey(root)
		val otherKey = threads.replyKey(other)
		drafts.saved[key] = "not json"
		threads.edit(otherKey, "keep me")
		threads.open(root, null)
		threads.startReply(key)
		assertNotNull(threads.corruptReason(key))

		threads.discard(root)

		assertFalse(drafts.saved.containsKey(key))
		assertNull(threads.corruptReason(key))
		assertEquals("keep me", threads.draft(otherKey))
	}

	/** And the slot works normally again afterwards. */
	@Test
	fun `a slot is usable again once its corrupt draft is discarded`() {
		val hive = Hive(inBlock())
		val drafts = Drafts()
		val threads = controller(hive, drafts = drafts) { "alice" }
		val key = threads.replyKey(root)
		drafts.saved[key] = "not json"
		threads.open(root, null)
		threads.startReply(key)
		threads.discard(root)

		threads.startReply(key)
		threads.edit(key, "starting over")
		threads.send(root, root)

		assertNull(threads.corruptReason(key))
		assertEquals(1, hive.broadcasts)
		assertEquals("starting over", hive.preparedOps.single().body)
	}

	// ── cleanup is conditional on what is actually stored ──────────────

	/**
	 * The user types something new while the send is in flight.
	 *
	 * The reply that landed was written from a different draft, so retiring
	 * *this* entry would delete words the confirmation knows nothing about. The
	 * comparison happens inside the store, against what is really there — and
	 * the newer words are handed back as an ordinary unsent draft rather than
	 * left attached to an intent that has already published.
	 */
	@Test
	fun `a newer draft written during the send survives the cleanup`() {
		val hive = Hive(inBlock())
		val drafts = Drafts()
		val threads = controller(hive, drafts = drafts) { "alice" }
		val key = threads.replyKey(root)
		threads.edit(key, "first")
		hive.duringBroadcast = { threads.edit(key, "second thoughts") }

		threads.send(root, root)

		assertEquals("first", hive.preparedOps.single().body)
		assertTrue(threads.status(key) is SnapPostStatus.Posted)
		assertEquals("the newer draft is untouched", "second thoughts", threads.draft(key))
		assertTrue(drafts.saved.containsKey(key))
	}

	// ── a confirmed intent releases newer text ─────────────────────────

	/**
	 * The bug this replaced: `oldText/X` publishes, `newText/X` is what is
	 * stored, and an exact-match removal correctly refuses — leaving the new
	 * words tied to an intent that is already confirmed. The next Send then
	 * resolved X as "already posted" and *deleted* them instead of publishing
	 * them.
	 *
	 * Settlement now releases the intent and keeps the words, so the entry is a
	 * fresh unsent draft again.
	 */
	@Test
	fun `a confirmed intent releases the newer text it was replaced by`() {
		val hive = Hive(inBlock())
		val drafts = Drafts()
		val threads = controller(hive, drafts = drafts) { "alice" }
		val key = threads.replyKey(root)
		threads.edit(key, "oldText")
		hive.duringBroadcast = { threads.edit(key, "newText") }

		threads.send(root, root)

		val stored = storedDraft(drafts, key)!!
		assertEquals("newText", stored.text)
		assertNull("the confirmed intent is released, not kept", stored.intentId)
		assertEquals("newText", threads.draft(key))
	}

	/** And that released draft publishes normally, once, on the next Send. */
	@Test
	fun `the released text becomes a new intent only when Send is pressed again`() {
		val hive = Hive(inBlock())
		val drafts = Drafts()
		val threads = controller(hive, drafts = drafts) { "alice" }
		val key = threads.replyKey(root)
		threads.edit(key, "oldText")
		hive.duringBroadcast = { threads.edit(key, "newText") }
		threads.send(root, root)
		hive.duringBroadcast = null
		assertEquals("settlement must not publish anything itself", 1, hive.broadcasts)

		threads.send(root, root)

		assertEquals("exactly one further broadcast", 2, hive.broadcasts)
		assertEquals(listOf("oldText", "newText"), hive.preparedOps.map { it.body })
		assertTrue(
			"a new permlink, not the confirmed one",
			hive.preparedOps[0].permlink != hive.preparedOps[1].permlink,
		)
		assertEquals("", threads.draft(key))
	}

	/** The original attempt is still confirmed and still cannot be resent. */
	@Test
	fun `the original intent cannot broadcast again after releasing newer text`() {
		val hive = Hive(inBlock())
		val drafts = Drafts()
		val store = Store()
		val threads = controller(hive, drafts = drafts, store = store) { "alice" }
		val key = threads.replyKey(root)
		threads.edit(key, "oldText")
		hive.duringBroadcast = { threads.edit(key, "newText") }
		threads.send(root, root)
		val originalIntent = soleIntent(store)
		hive.duringBroadcast = null

		// Put the released draft back under the original intent, as a damaged
		// store or an older build might, and try again.
		drafts.saved[key] = SnapReplyDraft("newText", originalIntent).toJson()
		threads.send(root, root)

		assertEquals(1, hive.broadcasts)
		assertEquals(1, hive.preparedOps.size)
	}

	/** A slot that has moved on to a different attempt is not this one's to settle. */
	@Test
	fun `a different newer intent is left untouched by an older confirmation`() {
		val hive = Hive(inBlock())
		val drafts = Drafts()
		val threads = controller(hive, drafts = drafts) { "alice" }
		val key = threads.replyKey(root)
		val otherIntent = "ffffffffffffffff"
		threads.edit(key, "oldText")
		hive.duringBroadcast = {
			drafts.saved[key] = SnapReplyDraft("someone else's attempt", otherIntent).toJson()
		}

		threads.send(root, root)

		val stored = storedDraft(drafts, key)!!
		assertEquals("someone else's attempt", stored.text)
		assertEquals("the other intent is not released", otherIntent, stored.intentId)
	}

	/** A slot that became undecodable stays locked; settlement does not unlock it. */
	@Test
	fun `a corrupt replacement is left untouched by settlement`() {
		val hive = Hive(inBlock())
		val drafts = Drafts()
		val threads = controller(hive, drafts = drafts) { "alice" }
		val key = threads.replyKey(root)
		threads.edit(key, "oldText")
		hive.duringBroadcast = { drafts.saved[key] = "}}damaged{{" }

		threads.send(root, root)

		assertEquals("}}damaged{{", drafts.saved[key])
		assertEquals("", threads.draft(key))
	}

	/** A settlement that cannot be written is not a settlement. */
	@Test
	fun `a settlement commit failure preserves the draft and claims nothing`() {
		val hive = Hive(inBlock())
		val drafts = Drafts()
		val threads = controller(hive, drafts = drafts) { "alice" }
		val key = threads.replyKey(root)
		threads.edit(key, "oldText")
		hive.duringBroadcast = {
			threads.edit(key, "newText")
			drafts.removable = false
		}

		threads.send(root, root)

		val stored = storedDraft(drafts, key)!!
		assertEquals("no text may be lost", "newText", stored.text)
		assertEquals("newText", threads.draft(key))
		assertTrue(drafts.saved.containsKey(key))
	}

	/** Restarting after a settlement finds an ordinary unsent draft. */
	@Test
	fun `a restart after settlement keeps the released text unsent`() {
		val hive = Hive(inBlock())
		val drafts = Drafts()
		val store = Store()
		val threads = controller(hive, drafts = drafts, store = store) { "alice" }
		val key = threads.replyKey(root)
		threads.edit(key, "oldText")
		hive.duringBroadcast = { threads.edit(key, "newText") }
		threads.send(root, root)
		hive.duringBroadcast = null

		val restarted = controller(hive, drafts = drafts, store = store) { "alice" }
		restarted.resumePending()

		assertEquals("newText", restarted.draft(key))
		assertNull(storedDraft(drafts, key)!!.intentId)
		assertEquals(1, hive.broadcasts)
	}

	/**
	 * A draft whose intent key was stripped in storage is corrupt, not new.
	 *
	 * `{"text":"nice one"}` used to parse as an unsent draft, which would have
	 * minted a second intent for a reply that is already on chain.
	 */
	@Test
	fun `a post-send draft damaged by losing its intent can never broadcast again`() {
		val hive = Hive(inBlock())
		val drafts = Drafts()
		val store = Store()
		val threads = controller(hive, drafts = drafts, store = store) { "alice" }
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")
		threads.send(root, root)
		val intentsBefore = drafts.intentWrites

		// The intent key is gone; the text survives.
		drafts.saved[key] = """{"text":"nice one"}"""

		val restarted = controller(hive, drafts = drafts, store = store) { "alice" }
		restarted.resumePending()
		restarted.open(root, null)
		restarted.startReply(key)
		restarted.edit(key, "nice one")
		restarted.send(root, root)

		assertEquals("no second transaction", 1, hive.broadcasts)
		assertEquals("no second permlink", 1, hive.preparedOps.size)
		assertEquals("no new intent", intentsBefore, drafts.intentWrites)
		assertNotNull("the slot is locked", restarted.corruptReason(key))
		assertEquals("""{"text":"nice one"}""", drafts.saved[key])
	}

	/** A confirmed older intent has no authority over a newer draft. */
	@Test
	fun `an older confirmed intent cannot clear a draft carrying a newer one`() {
		val drafts = Drafts()
		val threads = controller(drafts = drafts) { "alice" }
		val key = threads.replyKey(root)
		drafts.saved[key] = SnapReplyDraft("newer words", "bbbbbbbbbbbbbbbb").toJson()

		val removed = drafts.removeIf(key, SnapReplyDraft("older words", "aaaaaaaaaaaaaaaa"))

		assertFalse(removed)
		assertEquals("newer words", threads.draft(key))
	}

	@Test
	fun `compare-and-clear refuses when only the text differs`() {
		val drafts = Drafts()
		val key = "alice|reply|alice/snap-1"
		drafts.saved[key] = SnapReplyDraft("edited", "aaaaaaaaaaaaaaaa").toJson()

		assertFalse(drafts.removeIf(key, SnapReplyDraft("original", "aaaaaaaaaaaaaaaa")))
		assertTrue(drafts.saved.containsKey(key))
	}

	@Test
	fun `compare-and-clear removes the entry when it still matches`() {
		val drafts = Drafts()
		val key = "alice|reply|alice/snap-1"
		val draft = SnapReplyDraft("exactly this", "aaaaaaaaaaaaaaaa")
		drafts.saved[key] = draft.toJson()

		assertTrue(drafts.removeIf(key, draft))
		assertFalse(drafts.saved.containsKey(key))
	}

	/** `null` expects a still-unreadable entry, and can never take a valid one. */
	@Test
	fun `compare-and-clear with no expected draft only removes an unreadable entry`() {
		val drafts = Drafts()
		val corruptKey = "alice|reply|alice/snap-1"
		val validKey = "alice|reply|alice/snap-2"
		drafts.saved[corruptKey] = "not json"
		drafts.saved[validKey] = SnapReplyDraft("real words", null).toJson()

		assertFalse("a valid draft is not an unreadable one", drafts.removeIf(validKey, null))
		assertTrue(drafts.saved.containsKey(validKey))
		assertTrue(drafts.removeIf(corruptKey, null))
		assertFalse(drafts.saved.containsKey(corruptKey))
	}

	/**
	 * A commit that does not stick is not a cleared draft, and must not be
	 * reported as one.
	 */
	@Test
	fun `a failed removal is not reported as a cleared draft`() {
		val hive = Hive(inBlock())
		val drafts = Drafts().apply { removable = false }
		val threads = controller(hive, drafts = drafts) { "alice" }
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")

		threads.send(root, root)

		assertEquals(1, hive.broadcasts)
		assertTrue(threads.status(key) is SnapPostStatus.Posted)
		assertEquals("the draft is still here, and says so", "nice one", threads.draft(key))
		assertTrue(drafts.saved.containsKey(key))
	}

	/** And a later Send on that still-present draft still cannot duplicate. */
	@Test
	fun `a draft left behind by a failed removal still cannot duplicate`() {
		val hive = Hive(inBlock())
		val drafts = Drafts().apply { removable = false }
		val threads = controller(hive, drafts = drafts) { "alice" }
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")
		threads.send(root, root)

		threads.send(root, root)

		assertEquals(1, hive.broadcasts)
		assertEquals(1, hive.preparedOps.size)
	}

	/** Discard must not take a draft that replaced the one it was asked about. */
	@Test
	fun `discard does not delete a draft that replaced the one it was given`() {
		val drafts = Drafts()
		val threads = controller(drafts = drafts) { "alice" }
		val key = threads.replyKey(root)
		drafts.saved[key] = SnapReplyDraft("something else entirely", null).toJson()

		// The record the user was looking at is not what is stored now.
		assertFalse(drafts.removeIf(key, SnapReplyDraft("what they saw", null)))
		assertEquals("something else entirely", threads.draft(key))
	}

	// ── the character rule ─────────────────────────────────────────────

	/** Exactly 200 clusters is inside the limit; 201 is not. */
	@Test
	fun `the 200 cluster boundary is where a reply stops being sendable`() {
		val hive = Hive(inBlock())
		val threads = controller(hive) { "alice" }
		val key = threads.replyKey(root)

		threads.edit(key, "a".repeat(201))
		threads.send(root, root)
		assertEquals("201 must not send", 0, hive.broadcasts)

		threads.edit(key, "a".repeat(200))
		threads.send(root, root)
		assertEquals("200 must send", 1, hive.broadcasts)
	}

	/** One family emoji is one character, not eleven UTF-16 units. */
	@Test
	fun `a reply of 200 family emoji is inside the limit`() {
		val hive = Hive(inBlock())
		val threads = controller(hive) { "alice" }
		val key = threads.replyKey(root)
		val family = "👨‍👩‍👧‍👦"

		threads.edit(key, family.repeat(200))
		threads.send(root, root)

		assertEquals(1, hive.broadcasts)
		assertTrue(hive.preparedOps.single().body.length > 2_000)
	}

	@Test
	fun `a reply that is one emoji is sendable`() {
		val hive = Hive(inBlock())
		val threads = controller(hive) { "alice" }
		val key = threads.replyKey(root)

		threads.edit(key, "🔥")
		threads.send(root, root)

		assertEquals(1, hive.broadcasts)
		assertEquals("🔥", hive.preparedOps.single().body)
	}

	@Test
	fun `a reply of whitespace and invisible joiners is not sendable`() {
		val hive = Hive(inBlock())
		val threads = controller(hive) { "alice" }
		val key = threads.replyKey(root)

		threads.edit(key, "  \n‍️ ")
		threads.send(root, root)

		assertEquals(0, hive.broadcasts)
	}

	// ── one explicit send ──────────────────────────────────────────────

	@Test
	fun `a proven reply clears its draft and refreshes the thread`() {
		val hive = Hive(inBlock())
		val reader = Reader(emptyList())
		val drafts = Drafts()
		val threads = controller(hive, reader, drafts) { "alice" }
		val key = threads.replyKey(root)
		threads.open(root, null)
		threads.startReply(key)
		threads.edit(key, "nice one")
		val before = reader.reads

		threads.send(root, root)

		assertTrue(threads.status(key) is SnapPostStatus.Posted)
		assertEquals("", threads.draft(key))
		assertFalse(drafts.saved.containsKey(key))
		assertNull(threads.replyingTo)
		assertEquals("the thread is read back after a reply lands", before + 1, reader.reads)
	}

	/** Ambiguity keeps the draft and offers no resend. */
	@Test
	fun `an uncertain reply keeps its draft`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val threads = controller(hive) { "alice" }
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")

		threads.send(root, root)

		assertTrue(threads.status(key) is SnapPostStatus.Uncertain)
		assertEquals("nice one", threads.draft(key))
	}

	/**
	 * The draft is destroyed only once Hive has confirmed, and a repeated tap
	 * after that has nothing left to send.
	 */
	@Test
	fun `a repeated tap after a reply lands does not send again`() {
		val hive = Hive(inBlock())
		val threads = controller(hive) { "alice" }
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")

		threads.send(root, root)
		threads.send(root, root)

		assertEquals(1, hive.broadcasts)
	}

	// ── close, and the explicit discard ────────────────────────────────

	@Test
	fun `closing the thread keeps every draft`() {
		val drafts = Drafts()
		val threads = controller(drafts = drafts) { "alice" }
		val key = threads.replyKey(root)
		threads.open(root, null)
		threads.startReply(key)
		threads.edit(key, "half typed")

		threads.close()

		assertNull(threads.openThread)
		assertNull(threads.replyingTo)
		assertEquals("half typed", threads.draft(key))
	}

	@Test
	fun `cancelling the composer keeps the draft`() {
		val threads = controller { "alice" }
		val key = threads.replyKey(root)
		threads.open(root, null)
		threads.startReply(key)
		threads.edit(key, "half typed")

		threads.cancelReply()

		assertNull(threads.replyingTo)
		assertEquals("half typed", threads.draft(key))
	}

	@Test
	fun `discard removes this draft and only this one`() {
		val drafts = Drafts()
		val threads = controller(drafts = drafts) { "alice" }
		val other = SnapReplyTarget.of("bob", "re-one")!!
		val key = threads.replyKey(root)
		val otherKey = threads.replyKey(other)
		threads.open(root, null)
		threads.startReply(key)
		threads.edit(key, "throw me away")
		threads.edit(otherKey, "keep me")

		threads.discard(root)

		assertEquals("", threads.draft(key))
		assertFalse(drafts.saved.containsKey(key))
		assertEquals("keep me", threads.draft(otherKey))
		assertNull(threads.replyingTo)
	}

	/** Discard is per account: the same comment under Bob is untouched. */
	@Test
	fun `discard does not reach another account's draft for the same comment`() {
		val drafts = Drafts()
		var signedIn = "alice"
		val threads = controller(drafts = drafts) { signedIn }
		val aliceKey = threads.replyKey(root)
		threads.edit(aliceKey, "alice's words")
		signedIn = "bob"
		val bobKey = threads.replyKey(root)
		threads.edit(bobKey, "bob's words")

		threads.discard(root)

		assertEquals("", threads.draft(bobKey))
		signedIn = "alice"
		assertEquals("alice's words", threads.draft(aliceKey))
	}

	/**
	 * Discarding a draft whose attempt is undecided would orphan the pending
	 * record and let the next Send mint a fresh permlink for words that may
	 * already be live. Refused, and explained.
	 */
	@Test
	fun `discard is refused while the reply's outcome is unknown`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val drafts = Drafts()
		val threads = controller(hive, drafts = drafts) { "alice" }
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")
		threads.send(root, root)

		threads.discard(root)

		assertEquals("nice one", threads.draft(key))
		assertTrue(drafts.saved.containsKey(key))
		assertTrue(threads.status(key) is SnapPostStatus.Uncertain)
	}

	/** Once the chain has settled it, the same draft may be discarded. */
	@Test
	fun `discard is allowed once the attempt has been decided`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val drafts = Drafts()
		val store = Store()
		val threads = controller(hive, drafts = drafts, store = store) { "alice" }
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")
		threads.send(root, root)
		// The chain proves the comment is there, so the attempt is decided and
		// the draft is retired by the recheck itself.
		hive.content = true
		threads.recheck(root, root)

		threads.discard(root)

		assertEquals("", threads.draft(key))
		assertFalse(drafts.saved.containsKey(key))
	}

	// ── account isolation: keyed data ──────────────────────────────────

	@Test
	fun `drafts, threads and statuses are keyed by account`() {
		var signedIn = "alice"
		val reader = Reader(listOf(reply("bob", "r1", root)))
		val threads = controller(reader = reader) { signedIn }
		val aliceKey = threads.replyKey(root)
		threads.edit(aliceKey, "alice's words")
		threads.load(root)
		assertEquals(1, threads.thread(root)!!.total)

		signedIn = "bob"

		assertEquals("bob must not see alice's thread", null, threads.state(root))
		assertNull(threads.preview(root))
		assertEquals("bob must not see alice's draft", "", threads.draft(threads.replyKey(root)))
		assertTrue(aliceKey != threads.replyKey(root))
	}

	@Test
	fun `after a switch, Send finds nothing of the previous account to send`() {
		val hive = Hive(inBlock())
		var signedIn = "alice"
		val threads = controller(hive) { signedIn }
		val aliceKey = threads.replyKey(root)
		threads.edit(aliceKey, "alice's words")

		signedIn = "bob"
		threads.send(root, root)

		assertEquals(0, hive.broadcasts)
		assertEquals("alice's draft is untouched", "alice's words", threads.draft(aliceKey))
		assertEquals(SnapPostStatus.Idle, threads.status(aliceKey))
	}

	@Test
	fun `signing out leaves no account's state reachable`() {
		var signedIn: String? = "alice"
		val threads = controller(reader = Reader(listOf(reply("bob", "r1", root)))) { signedIn }
		threads.edit(threads.replyKey(root), "alice's words")
		threads.load(root)
		threads.open(root, null)

		signedIn = null

		assertNull(threads.state(root))
		assertNull(threads.openThread)
		assertEquals("", threads.draft(threads.replyKey(root)))
	}

	// ── account isolation: active UI state ─────────────────────────────

	@Test
	fun `an open thread belongs to the account that opened it`() {
		var signedIn = "alice"
		val threads = controller { signedIn }
		threads.open(root, null)
		assertNotNull(threads.openThread)

		signedIn = "bob"

		assertNull("bob must not inherit alice's open thread", threads.openThread)
		assertNull(threads.openRootSnap)

		signedIn = "alice"
		assertNotNull("and alice's own sheet is still hers", threads.openThread)
	}

	@Test
	fun `an open reply composer belongs to the account that opened it`() {
		var signedIn = "alice"
		val threads = controller { signedIn }
		val key = threads.replyKey(root)
		threads.open(root, null)
		threads.startReply(key)
		assertTrue(threads.isReplying(key))

		signedIn = "bob"

		assertNull(threads.replyingTo)
		assertFalse(threads.isReplying(key))
	}

	/** Bob cannot steer a sheet he cannot see. */
	@Test
	fun `startReply is refused while the sheet belongs to another account`() {
		var signedIn = "alice"
		val threads = controller { signedIn }
		threads.open(root, null)

		signedIn = "bob"
		threads.startReply(threads.replyKey(root))

		assertNull(threads.replyingTo)
		signedIn = "alice"
		assertNull("and alice's composer was not opened either", threads.replyingTo)
	}

	// ── account isolation: async completions ───────────────────────────

	/**
	 * Answers alice until the switch point and bob after it, which makes "the
	 * account changed while the work was in flight" deterministic rather than a
	 * race the test has to win.
	 */
	private class Switching(private val switchAfter: Int) {
		var reads = 0
		operator fun invoke(): String = if (reads++ >= switchAfter) "bob" else "alice"
	}

	@Test
	fun `a load that completes after a switch writes nothing`() {
		val reader = Reader(listOf(reply("bob", "r1", root)))
		// Reads: threadKey and `who` before the launch, then the completion check.
		val who = Switching(switchAfter = 2)
		val threads = controller(reader = reader) { who() }

		threads.load(root)

		assertEquals(1, reader.reads)
		// Nothing was written under either account's key.
		assertNull(threads.thread(root))
		assertNull(threads.preview(root))
	}

	@Test
	fun `a send that completes after a switch writes no status and clears no draft`() {
		val hive = Hive(inBlock())
		val drafts = Drafts()
		val setup = controller(hive, drafts = drafts) { "alice" }
		val aliceKey = setup.replyKey(root)
		setup.edit(aliceKey, "alice's words")

		// Enough reads for the key, `who` and the in-coroutine ownership check to
		// see alice; the completion check then sees bob.
		val who = Switching(switchAfter = 3)
		val threads = controller(hive, drafts = drafts) { who() }
		threads.send(root, root)

		assertEquals("alice's draft survives", "alice's words", threads.draft(aliceKey))
		assertTrue(drafts.saved.containsKey(aliceKey))
		assertFalse(
			"no result may be written under either account",
			threads.status(aliceKey) is SnapPostStatus.Posted,
		)
	}

	@Test
	fun `a recheck that completes after a switch writes no status`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val drafts = Drafts()
		val store = Store()
		val setup = controller(hive, drafts = drafts, store = store) { "alice" }
		val aliceKey = setup.replyKey(root)
		setup.edit(aliceKey, "alice's words")
		setup.send(root, root)
		assertTrue(setup.status(aliceKey) is SnapPostStatus.Uncertain)

		val who = Switching(switchAfter = 3)
		val threads = controller(hive, drafts = drafts, store = store) { who() }
		hive.content = true
		threads.recheck(root, root)

		assertFalse(
			"alice's card must not be moved to Posted by bob's session",
			threads.status(aliceKey) is SnapPostStatus.Posted,
		)
		assertEquals("alice's draft survives", "alice's words", threads.draft(aliceKey))
	}

	@Test
	fun `resumePending under a signed-out account does nothing`() {
		val hive = Hive(inBlock())
		val drafts = Drafts()
		val store = Store()
		val seeded = controller(hive, drafts = drafts, store = store) { "alice" }
		seeded.edit(seeded.replyKey(root), "nice one")
		seeded.send(root, root)

		val threads = controller(hive, drafts = drafts, store = store) { null }
		threads.resumePending()

		assertEquals(1, hive.broadcasts)
	}

	@Test
	fun `only one reply composer is open at a time`() {
		val threads = controller { "alice" }
		val other = SnapReplyTarget.of("bob", "re-one")!!
		threads.open(root, null)

		threads.startReply(threads.replyKey(root))
		threads.startReply(threads.replyKey(other))

		assertTrue(threads.isReplying(threads.replyKey(other)))
		assertFalse(threads.isReplying(threads.replyKey(root)))
	}

	// ── local-first: the reply is the user's before Hive hears about it ──
	//
	// The invariant these pin, in one line: **nothing the network does is on
	// the visible path.** Tapping Reply commits one local record, and from that
	// instant the composer is gone and the reply is in the thread. Everything
	// after — building the transaction, signing it, broadcasting it, settling
	// it — happens behind a screen that has already moved on.
	//
	// The strongest of these assert *from inside the network call*: whatever
	// they can see while a node is being asked is what a user on a slow
	// connection sits looking at.

	/** Reply, and the box is already gone by the time Hive is asked anything. */
	@Test
	fun `the reply composer closes on the durable intent, before any network call`() {
		val hive = Hive(inBlock())
		val threads = controller(hive) { "alice" }
		threads.open(root, null)
		val key = threads.replyKey(root)
		threads.startReply(key)
		threads.edit(key, "nice one")
		var openDuringPrepare: Boolean? = null
		hive.duringPrepare = { openDuringPrepare = threads.isReplying(key) }

		threads.send(root, root)

		assertEquals("the composer was still open while a node was asked", false, openDuringPrepare)
		assertFalse(threads.isReplying(key))
	}

	/** And the reply itself is on screen just as early, in its right place. */
	@Test
	fun `the reply is in the thread before the network is asked`() {
		val hive = Hive(inBlock())
		// Older than the clock the controller stages under, so the two order
		// the way the conversation actually happened.
		val bob = SnapReply("bob", "r1", root.author, root.permlink, "hi", 900L)
		val threads = controller(hive, reader = Reader(listOf(bob))) { "alice" }
		threads.open(root, null)
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")
		var bodiesDuringPrepare: List<String>? = null
		hive.duringPrepare = {
			bodiesDuringPrepare = threads.thread(root)?.rows?.map { it.reply.body }
		}

		threads.send(root, root)

		assertEquals(listOf("hi", "nice one"), bodiesDuringPrepare)
		// Under the comment it answers, not floating at the top.
		val mine = threads.thread(root)!!.rows.single { it.reply.body == "nice one" }
		assertEquals("alice", mine.reply.author)
		assertEquals(root.author, mine.reply.parentAuthor)
		assertEquals(root.permlink, mine.reply.parentPermlink)
	}

	/** A reply to a reply lands under *that* comment, not under the root. */
	@Test
	fun `an optimistic reply to a nested comment keeps its position`() {
		val bob = reply("bob", "r1", root)
		val target = SnapReplyTarget.of("bob", "r1")!!
		val threads = controller(reader = Reader(listOf(bob))) { "alice" }
		threads.open(root, null)
		val key = threads.replyKey(target)
		threads.edit(key, "agreed")

		threads.send(root, target)

		val mine = threads.thread(root)!!.rows.single { it.reply.body == "agreed" }
		assertEquals("bob", mine.reply.parentAuthor)
		assertEquals("r1", mine.reply.parentPermlink)
		assertEquals("one level in", 1, mine.depth)
	}

	/**
	 * The durable boundary, from the other side.
	 *
	 * A record that could not be committed is a reply with no identity and no
	 * permlink — nothing a crash could recover and nothing a retry could reuse.
	 * Drawing it would be the app asserting a Snap it cannot prove it owns, so
	 * nothing is drawn, nothing is sent, and the words stay in the composer.
	 */
	@Test
	fun `a reply whose record cannot be stored is never drawn and never sent`() {
		val hive = Hive(inBlock())
		val store = Store().apply { writable = false }
		val threads = controller(hive, store = store) { "alice" }
		threads.open(root, null)
		val key = threads.replyKey(root)
		threads.startReply(key)
		threads.edit(key, "nice one")

		threads.send(root, root)

		assertEquals("nothing reached a node", 0, hive.broadcasts)
		assertEquals(emptyList<Any>(), threads.thread(root)!!.rows)
		assertTrue(threads.status(key) is SnapPostStatus.Failed)
		assertTrue("the composer stays open on the words", threads.isReplying(key))
		assertEquals("nice one", threads.draft(key))
	}

	/**
	 * Two taps, one comment.
	 *
	 * The second arrives while the first is still on the wire — which is now
	 * the *normal* case, because the composer closed long before the broadcast
	 * finished. The claim is what turns it away; the status cannot, because by
	 * then the row reads as an ordinary reply.
	 */
	@Test
	fun `a second tap during the broadcast cannot duplicate the reply`() {
		val hive = Hive(inBlock())
		val threads = controller(hive) { "alice" }
		threads.open(root, null)
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")
		hive.duringBroadcast = { threads.send(root, root) }

		threads.send(root, root)

		assertEquals("exactly one comment", 1, hive.broadcasts)
		assertEquals(1, hive.preparedOps.size)
		assertEquals(1, threads.thread(root)!!.rows.size)
	}

	// ── an unfinished reply: frozen, visible, and nobody's to finish but
	// the user's ──────────────────────────────────────────────────────

	/**
	 * Preparation failed, so the reply exists here and nowhere else.
	 *
	 * It keeps its place in the thread — the words are the user's and the
	 * record is durable — and it is not reported as posted. What it must never
	 * become is a ghost: a comment sitting in a conversation that Hive has
	 * never heard of, with nothing on screen saying so.
	 */
	@Test
	fun `an unbuilt reply stays in the thread and says it is unfinished`() {
		val hive = Hive(inBlock()).apply { preparable = false }
		val threads = controller(hive) { "alice" }
		threads.open(root, null)
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")

		threads.send(root, root)

		assertEquals("nothing was sent", 0, hive.broadcasts)
		assertTrue(threads.status(key) is SnapPostStatus.Interrupted)
		assertEquals(
			listOf("nice one"),
			threads.thread(root)!!.rows.map { it.reply.body },
		)
		assertEquals("the words survive", "nice one", threads.draft(key))
	}

	/** It survives a restart in exactly that state, and writes nothing. */
	@Test
	fun `restarting with an unfinished reply broadcasts nothing and still shows it`() {
		val hive = Hive(inBlock()).apply { preparable = false }
		val drafts = Drafts()
		val store = Store()
		val first = controller(hive, drafts = drafts, store = store) { "alice" }
		first.open(root, null)
		val key = first.replyKey(root)
		first.edit(key, "nice one")
		first.send(root, root)
		assertTrue(first.status(key) is SnapPostStatus.Interrupted)

		hive.preparable = true
		val restarted = controller(hive, drafts = drafts, store = store) { "alice" }
		restarted.resumePending()
		restarted.open(root, null)

		assertEquals("a restart writes nothing to Hive", 0, hive.broadcasts)
		assertEquals(0, hive.preparedOps.size)
		assertTrue(restarted.status(key) is SnapPostStatus.Interrupted)
		assertEquals(listOf("nice one"), restarted.thread(root)!!.rows.map { it.reply.body })
	}

	/**
	 * Finishing it is one explicit tap, and it publishes the **frozen** reply.
	 *
	 * The draft is edited in between on purpose. What gets signed is what the
	 * user has been looking at in the thread since they tapped Reply, under the
	 * permlink minted then — not whatever the composer happens to hold now.
	 */
	@Test
	fun `finishing an interrupted reply reuses the frozen identity and words`() {
		val hive = Hive(inBlock()).apply { preparable = false }
		val drafts = Drafts()
		val store = Store()
		val threads = controller(hive, drafts = drafts, store = store) { "alice" }
		threads.open(root, null)
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")
		threads.send(root, root)
		val frozen = store.saved.values.single().let { PendingSnap.fromJson(it)!! }

		hive.preparable = true
		threads.edit(key, "completely different words")
		threads.send(root, root)

		assertEquals(1, hive.preparedOps.size)
		assertEquals("the frozen body", "nice one", hive.preparedOps.single().body)
		assertEquals("the frozen permlink", frozen.permlink, hive.preparedOps.single().permlink)
		assertEquals("one comment", 1, hive.broadcasts)
		assertEquals("and one record", 1, store.saved.size)
	}

	/** An ambiguous reply is read, never resent — and stays on screen. */
	@Test
	fun `an ambiguous reply keeps its place and is never re-broadcast`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val threads = controller(hive) { "alice" }
		threads.open(root, null)
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")
		threads.send(root, root)

		threads.send(root, root)
		threads.recheck(root, root)

		assertEquals("one broadcast, ever", 1, hive.broadcasts)
		assertTrue(threads.status(key) is SnapPostStatus.Uncertain)
		assertEquals(listOf("nice one"), threads.thread(root)!!.rows.map { it.reply.body })
	}

	/** A confirmed reply is not drawn twice once the chain returns it. */
	@Test
	fun `a reply the chain has caught up with is not shown twice`() {
		val hive = Hive(inBlock())
		val store = Store()
		val reader = Reader(emptyList())
		val threads = controller(hive, reader = reader, store = store) { "alice" }
		threads.open(root, null)
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")
		threads.send(root, root)
		val published = PendingSnap.fromJson(store.saved.values.single())!!

		// hivemind catches up, and the thread is re-read.
		reader.replies = listOf(
			SnapReply("alice", published.permlink, root.author, root.permlink, "nice one", 1_000L),
		)
		threads.load(root, force = true)

		assertEquals(listOf("nice one"), threads.thread(root)!!.rows.map { it.reply.body })
	}

	// ── the Stage 6 seam, on the reply side ────────────────────────────

	/** An unfinished reply is surfaced as a reply, not as a Snap. */
	@Test
	fun `needsAttention reports an unfinished reply under its own kind`() {
		val hive = Hive(inBlock()).apply { preparable = false }
		val threads = controller(hive) { "alice" }
		threads.open(root, null)
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")
		threads.send(root, root)

		val attention = threads.needsAttention()

		assertEquals(1, attention.size)
		assertEquals(key, attention.single().key)
		assertEquals(SnapAttention.Kind.REPLY, attention.single().kind)
		assertTrue(attention.single().status is SnapPostStatus.Interrupted)
		assertEquals("reading it sends nothing", 0, hive.broadcasts)
	}

	/** And only for the account signed in now. */
	@Test
	fun `another account's unfinished reply is not surfaced`() {
		val hive = Hive(inBlock()).apply { preparable = false }
		var who: String? = "alice"
		val threads = controller(hive) { who }
		threads.open(root, null)
		threads.edit(threads.replyKey(root), "nice one")
		threads.send(root, root)
		assertEquals(1, threads.needsAttention().size)

		who = "bob"

		assertEquals(emptyList<Any>(), threads.needsAttention())
	}

	// ── discarding an unfinished reply really discards it ──────────────

	/**
	 * A reply the user throws away leaves the thread with it.
	 *
	 * The row is drawn from the durable record, so removing the draft alone
	 * would leave a comment in the conversation that exists nowhere else and
	 * that nothing on screen could finish or remove. Safe to delete for one
	 * reason only: an intent was never built, so it reached no node.
	 */
	@Test
	fun `discarding an unfinished reply removes it from the thread`() {
		val hive = Hive(inBlock()).apply { preparable = false }
		val store = Store()
		val threads = controller(hive, store = store) { "alice" }
		threads.open(root, null)
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")
		threads.send(root, root)
		assertEquals(listOf("nice one"), threads.thread(root)!!.rows.map { it.reply.body })

		threads.discard(root)

		assertEquals(emptyList<Any>(), threads.thread(root)!!.rows)
		assertEquals("the record is gone too", 0, store.saved.size)
		assertEquals("", threads.draft(key))
		assertEquals(SnapPostStatus.Idle, threads.status(key))
	}

	/** A reply that may be on chain is not deletable, and says so. */
	@Test
	fun `discarding refuses while the reply may already exist`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val store = Store()
		val threads = controller(hive, store = store) { "alice" }
		threads.open(root, null)
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")
		threads.send(root, root)

		threads.discard(root)

		assertEquals("the record stays", 1, store.saved.size)
		assertEquals("and so do the words", "nice one", threads.draft(key))
		assertTrue(threads.status(key) is SnapPostStatus.Uncertain)
	}

	/** A confirmed reply's draft still discards, and the comment stays. */
	@Test
	fun `discarding after a confirmed reply leaves the published comment alone`() {
		val hive = Hive(inBlock())
		val store = Store()
		val threads = controller(hive, store = store) { "alice" }
		threads.open(root, null)
		val key = threads.replyKey(root)
		threads.edit(key, "nice one")
		threads.send(root, root)
		// The settlement retires the draft; typing again makes one to discard.
		threads.edit(key, "second thoughts")

		threads.discard(root)

		assertEquals("the published record is untouched", 1, store.saved.size)
		assertEquals(
			PendingSnapState.CONFIRMED,
			PendingSnap.fromJson(store.saved.values.single())!!.state,
		)
		assertEquals("", threads.draft(key))
		assertEquals(listOf("nice one"), threads.thread(root)!!.rows.map { it.reply.body })
	}

	/**
	 * A reply record naming another author is not drawn in the thread.
	 *
	 * The optimistic row exists because the record proves the reply is this
	 * user's. A record whose author disagrees with the account it is filed
	 * under proves the opposite of that, so it draws nothing — otherwise the
	 * conversation would show a comment under one name that would be signed
	 * under another.
	 */
	@Test
	fun `a reply record written by another author is never drawn`() {
		val hive = Hive(inBlock())
		val store = Store()
		val target = root
		val slot = SnapReplyKey.of(target, "a1b2c3d4e5f60718")
		store.saved["alice|$slot"] = PendingSnap(
			account = "alice",
			eventId = slot,
			author = "bob",
			permlink = "rustedwax-reply-1000-frozen",
			parentAuthor = target.author,
			parentPermlink = target.permlink,
			body = "not mine",
			jsonMetadata = """{"app":"rustedwax/test"}""",
			signedTransactionJson = "",
			txId = "",
			expirationEpochSec = 0L,
			state = PendingSnapState.INTENT,
			createdAtEpochSec = 900L,
			updatedAtEpochSec = 900L,
			kind = PendingSnapKind.REPLY,
		).toJson()
		val before = store.saved.toMap()
		val threads = controller(hive, store = store) { "alice" }

		threads.open(root, null)
		threads.resumePending()

		assertEquals(emptyList<Any>(), threads.thread(root)!!.rows)
		assertEquals("nothing sent", 0, hive.broadcasts)
		assertEquals("nothing written", before, store.saved)
	}
}
