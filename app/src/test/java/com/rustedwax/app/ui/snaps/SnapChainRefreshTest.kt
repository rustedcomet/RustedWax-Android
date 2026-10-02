package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.HivePostedSnapReader
import com.rustedwax.app.snaps.InMemoryPostedSnapCache
import com.rustedwax.app.snaps.PendingSnap
import com.rustedwax.app.snaps.PendingSnapKind
import com.rustedwax.app.snaps.PendingSnapRead
import com.rustedwax.app.snaps.PendingSnapState
import com.rustedwax.app.snaps.PendingSnapStore
import com.rustedwax.app.snaps.PostedSnapContent
import com.rustedwax.app.snaps.PostedSnapReader
import com.rustedwax.app.snaps.PostedSnaps
import com.rustedwax.app.snaps.SnapChainComment
import com.rustedwax.app.snaps.SnapEditor
import com.rustedwax.app.snaps.SnapEditKind
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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue 40C, October 2 contract: the chain is the source of truth for what a
 * posted Snap says, refreshes are event-driven (pull, open, foreground at five
 * minutes, after a write, retry), and nothing late, foreign, failed or older
 * may roll what is on screen backwards.
 */
class SnapChainRefreshTest {

	private val vid = "7LnBvuzjpr4"
	private val tags = "#scrobblelife #scrobble #rustedwax"
	private fun v1(text: String) = "$text\n\nhttps://youtu.be/$vid\n\n$tags"

	private val pool = Executors.newCachedThreadPool()
	private val io = pool.asCoroutineDispatcher()
	private val latches = mutableListOf<CountDownLatch>()

	@After
	fun tearDown() {
		latches.forEach { it.countDown() }
		pool.shutdown()
		check(pool.awaitTermination(10, TimeUnit.SECONDS))
	}

	// ── fakes ──────────────────────────────────────────────────────────

	private class Store : PendingSnapStore {
		val saved = ConcurrentHashMap<String, PendingSnap>()
		val writes = AtomicInteger()
		override fun read(account: String, eventId: String): PendingSnapRead =
			saved["$account|$eventId"]?.let(PendingSnapRead::Present) ?: PendingSnapRead.Absent
		override fun write(snap: PendingSnap): Boolean {
			writes.incrementAndGet()
			saved["${snap.account}|${snap.eventId}"] = snap
			return true
		}
		override fun clear(account: String, eventId: String) {
			saved.remove("$account|$eventId")
		}
		override fun all(account: String) =
			saved.filterKeys { it.startsWith("$account|") }.values.sortedBy { it.eventId }
		override fun corruptEventIds(account: String) = emptySet<String>()
	}

	/** Answers with [respond] for the n-th call (1-based), per `author/permlink`. */
	private class Reader : PostedSnapReader {
		val calls = AtomicInteger()
		val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
		@Volatile var respond: (Int, String) -> PostedSnapContent? = { _, _ -> null }
		override fun read(author: String, permlink: String): PostedSnapContent? {
			val n = calls.incrementAndGet()
			asked += "$author/$permlink"
			return respond(n, "$author/$permlink")
		}
	}

	/** A Hive port that must never be used: every write path counts. */
	private class NoWrites : SnapHivePort {
		val writes = AtomicInteger()
		override fun resolveContainer() = SnapContainerResolver.Result.Resolved(
			SnapContainer("peak.snaps", "snap-container-1", "2026-09-17T12:36:00"),
		)
		override fun prepareComment(operation: TxSerializer.CommentOp, author: String): HivePreparationResult {
			writes.incrementAndGet()
			return HivePreparationResult.Ready(PreparedHiveTransaction("{}", "tx", 2_000_000_000L))
		}
		override fun broadcastPrepared(prepared: PreparedHiveTransaction, author: String): HiveRpc.BroadcastResult {
			writes.incrementAndGet()
			return HiveRpc.BroadcastResult.NetworkFailure("no")
		}
		override fun observeTransaction(txId: String, expirationEpochSec: Long) =
			HiveRpc.TransactionEvidence.UNAVAILABLE
		override fun contentExists(author: String, permlink: String): Boolean? = null
	}

	private fun record(account: String, eventId: String, body: String) = PendingSnap(
		account = account,
		eventId = eventId,
		author = account,
		permlink = "rustedwax-snap-1000-$eventId",
		parentAuthor = "peak.snaps",
		parentPermlink = "snap-container-1",
		body = body,
		jsonMetadata = """{"app":"rustedwax/test"}""",
		signedTransactionJson = """{"op":"x"}""",
		txId = "tx-$eventId",
		expirationEpochSec = 2_000_000_000L,
		state = PendingSnapState.CONFIRMED,
		createdAtEpochSec = 900L,
		updatedAtEpochSec = 900L,
		kind = PendingSnapKind.ROOT,
	)

	private inner class Posts(var who: String? = "alice") {
		val store = Store()
		val reader = Reader()
		val cache = InMemoryPostedSnapCache()
		val hive = NoWrites()
		@Volatile var now = 10_000_000L
		val controller = SnapPostController(
			scope = CoroutineScope(Dispatchers.Unconfined),
			publisher = { SnapPublisher(hive, store, nowEpochSec = { 1_000L }) },
			account = { who },
			io = io,
			postedSnaps = { PostedSnaps(store, reader, cache) },
			clock = { now },
		)

		fun add(account: String, eventId: String, text: String) {
			store.saved["$account|$eventId"] = record(account, eventId, v1(text))
		}

		fun key(eventId: String) = SnapDraftKey.of(who!!, eventId)
		fun text(eventId: String) = controller.posted(key(eventId))?.userText
		fun id(account: String, eventId: String) = "$account/rustedwax-snap-1000-$eventId"

		/** Start-up restore, with the chain answering [startup] for every row. */
		fun restored(startup: (String) -> String?): Posts {
			reader.respond = { _, id -> startup(id)?.let { PostedSnapContent(it, 1_000L) } }
			controller.resumePending()
			eventually { !controller.isRefreshing && reader.calls.get() >= store.all(who!!).size }
			eventually { store.all(who!!).all { controller.posted(SnapDraftKey.of(who!!, it.eventId))?.fromChain == true } }
			reader.calls.set(0)
			reader.asked.clear()
			return this
		}
	}

	private fun eventually(what: () -> Boolean) {
		val until = System.currentTimeMillis() + 5_000
		while (!what()) {
			check(System.currentTimeMillis() < until) { "condition never held" }
			Thread.sleep(5)
		}
	}

	private fun latch() = CountDownLatch(1).also { latches += it }

	// ── 1. pull-to-refresh ─────────────────────────────────────────────

	@Test
	fun `pull-to-refresh reads the chain and replaces stale text while keeping the card up`() {
		val p = Posts().apply { add("alice", "e1", "old words") }.restored { v1("old words") }
		val gate = latch()
		p.reader.respond = { _, _ -> gate.await(); PostedSnapContent("edited elsewhere $tags", 2_000L) }

		p.controller.refresh(listOf("e1"))
		eventually { p.reader.calls.get() == 1 }
		// In flight: the card is still drawn, with the last good words.
		assertTrue(p.controller.isRefreshing)
		assertEquals("old words", p.text("e1"))

		gate.countDown()
		eventually { !p.controller.isRefreshing }
		assertEquals("edited elsewhere $tags", p.text("e1"))
		assertEquals(0, p.hive.writes.get())
		assertEquals(0, p.store.writes.get())
	}

	@Test
	fun `pull-to-refresh is bounded to the rows it is given`() {
		val p = Posts().apply {
			add("alice", "e1", "one")
			add("alice", "e2", "two")
		}.restored { v1(if (it.endsWith("e1")) "one" else "two") }
		p.reader.respond = { _, _ -> PostedSnapContent(v1("x"), 1L) }
		p.controller.refresh(listOf("e2", "not-posted"))
		eventually { !p.controller.isRefreshing }
		assertEquals(listOf(p.id("alice", "e2")), p.reader.asked.toList())
	}

	// ── 3. foreground, at five minutes ─────────────────────────────────

	@Test
	fun `staleness boundary is five minutes inclusive`() {
		val five = SnapPostController.STALE_AFTER_MILLIS
		assertEquals(300_000L, five)
		assertTrue(SnapPostController.isStale(null, 0L))
		assertFalse(SnapPostController.isStale(1_000L, 1_000L + five - 1))
		assertTrue(SnapPostController.isStale(1_000L, 1_000L + five))
		assertTrue(SnapPostController.isStale(1_000L, 1_000L + five + 60_000))
	}

	@Test
	fun `foreground return under five minutes does not refresh, at five minutes it does`() {
		val p = Posts().apply { add("alice", "e1", "a") }.restored { v1("a") }
		val stamp = p.cache.lastRefreshed("alice")
		assertEquals("a successful start-up read is a successful refresh", p.now, stamp)

		var fired = 0
		p.now = stamp!! + SnapPostController.STALE_AFTER_MILLIS - 1
		p.controller.refreshIfStale { fired++ }
		Thread.sleep(100)
		assertEquals(0, fired)

		p.now = stamp + SnapPostController.STALE_AFTER_MILLIS
		p.controller.refreshIfStale { fired++ }
		eventually { fired == 1 }
	}

	@Test
	fun `foreground refresh uses the signed-in account's own stamp`() {
		val p = Posts().apply { add("alice", "e1", "a") }.restored { v1("a") }
		p.cache.markRefreshed("bob", p.now + 10 * 60_000)
		p.now += SnapPostController.STALE_AFTER_MILLIS
		var fired = 0
		p.controller.refreshIfStale { fired++ }
		eventually { fired == 1 }
	}

	// ── cache and timestamp ───────────────────────────────────────────

	@Test
	fun `a successful refresh updates the cache and stamp, and a reopen starts from it`() {
		val p = Posts().apply { add("alice", "e1", "mine") }.restored { v1("mine") }
		p.reader.respond = { _, _ -> PostedSnapContent("reshaped $tags", 2_000L) }
		p.now += 1_000
		p.controller.refresh(listOf("e1"))
		eventually { !p.controller.isRefreshing }
		assertEquals(p.now, p.cache.lastRefreshed("alice"))

		// A new process: same store and cache, chain unreachable.
		val reopened = PostedSnaps(p.store, object : PostedSnapReader {
			override fun read(author: String, permlink: String): PostedSnapContent? = null
		}, p.cache)
		assertEquals("reshaped $tags", reopened.local("alice", "e1")?.userText)
		assertEquals("reshaped $tags", reopened.refreshed("alice", "e1")?.userText)
	}

	@Test
	fun `a cache entry made against an older record body is not used`() {
		val p = Posts().apply { add("alice", "e1", "mine") }.restored { "elsewhere $tags" }
		// RustedWax edits the Snap: the stored record body moves.
		p.store.saved["alice|e1"] = p.store.saved["alice|e1"]!!.copy(body = v1("edited here"))
		assertEquals("edited here", PostedSnaps(p.store, p.reader, p.cache).local("alice", "e1")?.userText)
	}

	// ── failure keeps the last good state ──────────────────────────────

	@Test
	fun `offline, timeout, error and missing object keep the last chain state and the stamp`() {
		val p = Posts().apply { add("alice", "e1", "local words") }.restored { "chain words $tags" }
		val stamp = p.cache.lastRefreshed("alice")
		listOf<(Int, String) -> PostedSnapContent?>(
			{ _, _ -> null }, // offline / no such object (deleted reads as null)
			{ _, _ -> throw java.net.SocketTimeoutException("timeout") },
			{ _, _ -> throw IllegalStateException("node error") },
			{ _, _ -> PostedSnapContent("   ", 1L) }, // blank body
		).forEach { failure ->
			p.reader.respond = failure
			p.now += SnapPostController.STALE_AFTER_MILLIS
			p.controller.refresh(listOf("e1"))
			eventually { !p.controller.isRefreshing }
			assertEquals("never the stale local text", "chain words $tags", p.text("e1"))
			assertNotNull("never treated as deletion", p.controller.posted(p.key("e1")))
			assertTrue(p.controller.refreshFailed)
			assertEquals(stamp, p.cache.lastRefreshed("alice"))
		}
	}

	// ── 5. retry ───────────────────────────────────────────────────────

	@Test
	fun `retry after a failed refresh reads the chain again`() {
		val p = Posts().apply { add("alice", "e1", "a") }.restored { v1("a") }
		p.reader.respond = { _, _ -> null }
		p.controller.refresh(listOf("e1"))
		eventually { !p.controller.isRefreshing }
		assertTrue(p.controller.refreshFailed)
		assertEquals(1, p.reader.calls.get())

		p.reader.respond = { _, _ -> PostedSnapContent("fixed $tags", 3L) }
		p.controller.refresh(listOf("e1"))
		eventually { !p.controller.isRefreshing }
		assertEquals(2, p.reader.calls.get())
		assertFalse(p.controller.refreshFailed)
		assertEquals("fixed $tags", p.text("e1"))
	}

	// ── ordering and identity ──────────────────────────────────────────

	@Test
	fun `an older response cannot overwrite a newer one`() {
		val p = Posts().apply { add("alice", "e1", "a") }.restored { v1("a") }
		val slow = latch()
		p.reader.respond = { n, _ ->
			if (n == 1) {
				slow.await()
				PostedSnapContent("older $tags", 1L)
			} else {
				PostedSnapContent("newer $tags", 2L)
			}
		}
		p.controller.refreshContent("alice", p.id("alice", "e1"))
		eventually { p.reader.calls.get() == 1 }
		p.controller.refreshContent("alice", p.id("alice", "e1"))
		eventually { p.text("e1") == "newer $tags" }
		slow.countDown()
		Thread.sleep(100)
		assertEquals("newer $tags", p.text("e1"))
		assertEquals("newer $tags", PostedSnaps(p.store, p.reader, p.cache).local("alice", "e1")?.userText)
	}

	@Test
	fun `a refresh that started before a proven edit cannot undo it, and the edit is re-fetched`() {
		val p = Posts().apply { add("alice", "e1", "before") }.restored { v1("before") }
		val slow = latch()
		p.reader.respond = { n, _ ->
			if (n == 1) {
				slow.await()
				PostedSnapContent(v1("before"), 1L)
			} else {
				PostedSnapContent(v1("after"), 2L)
			}
		}
		p.controller.refreshContent("alice", p.id("alice", "e1"))
		eventually { p.reader.calls.get() == 1 }

		// Trigger 4: the edit lands, and Hive is asked again.
		p.store.saved["alice|e1"] = p.store.saved["alice|e1"]!!.copy(body = v1("after"))
		p.controller.applyEdit("alice", p.id("alice", "e1"), "after")
		eventually { p.reader.calls.get() == 2 }
		assertEquals("after", p.text("e1"))
		slow.countDown()
		Thread.sleep(100)
		assertEquals("after", p.text("e1"))
		assertEquals("after", PostedSnaps(p.store, p.reader, p.cache).local("alice", "e1")?.userText)
		assertEquals(v1("after"), p.cache.read("alice", p.id("alice", "e1"))?.chainBody)
	}


	@Test
	fun `a read started before deletion cannot restore the deleted root or its cache`() {
		val p = Posts().apply { add("alice", "e1", "before") }.restored { v1("before") }
		val slow = latch()
		val answered = latch()
		p.reader.respond = { _, _ ->
			slow.await()
			answered.countDown()
			PostedSnapContent("late $tags", 1L)
		}
		p.controller.refreshContent("alice", p.id("alice", "e1"))
		eventually { p.reader.calls.get() == 1 }
		p.store.clear("alice", "e1")
		p.controller.applyDelete("alice", p.id("alice", "e1"))
		slow.countDown()
		assertTrue(answered.await(5, TimeUnit.SECONDS))
		Thread.sleep(100)
		assertNull(p.controller.posted(p.key("e1")))
		assertEquals(SnapPostStatus.Idle, p.controller.status(p.key("e1")))
		assertEquals(v1("before"), p.cache.read("alice", p.id("alice", "e1"))?.chainBody)
	}

	@Test
	fun `another account's late answer lands nowhere`() {
		val p = Posts().apply {
			add("alice", "e1", "alice words")
			add("bob", "e1", "bob words")
		}.restored { v1("alice words") }
		val slow = latch()
		p.reader.respond = { _, _ -> slow.await(); PostedSnapContent("late $tags", 1L) }
		p.controller.refresh(listOf("e1"))
		eventually { p.reader.calls.get() == 1 }
		p.who = "bob"
		slow.countDown()
		Thread.sleep(100)
		assertNull("bob sees none of alice's cards", p.controller.postedFor(p.id("alice", "e1")))
		p.who = "alice"
		assertEquals("alice words", p.text("e1"))
		assertNull(p.cache.read("bob", p.id("alice", "e1")))
		assertEquals(v1("alice words"), p.cache.read("alice", p.id("alice", "e1"))?.chainBody)
	}

	@Test
	fun `refreshContent for another account does nothing`() {
		val p = Posts().apply { add("alice", "e1", "a") }.restored { v1("a") }
		p.controller.refreshContent("bob", p.id("alice", "e1"))
		Thread.sleep(50)
		assertEquals(0, p.reader.calls.get())
	}

	@Test
	fun `the reader only accepts the exact object it asked for`() {
		fun json(author: String, permlink: String) = JSONObject()
			.put("author", author).put("permlink", permlink).put("body", "b").put("created", "2026-10-02T00:00:00")
		assertNotNull(HivePostedSnapReader.contentOf("alice", "p1", json("alice", "p1")))
		assertNull(HivePostedSnapReader.contentOf("alice", "p1", json("bob", "p1")))
		assertNull(HivePostedSnapReader.contentOf("alice", "p1", json("alice", "p2")))
		assertNull(HivePostedSnapReader.contentOf("alice", "p1", null))
	}

	@Test
	fun `a reshaped external body replaces stale display and the media move follows it`() {
		val photo = "https://files.peakd.com/file/x/photo.png"
		val p = Posts().apply { add("alice", "e1", "look $photo") }.restored { v1("look $photo") }
		assertEquals(photo, SnapMediaText.history(p.text("e1")!!).thumbnail?.source)

		// Another frontend moved the image below the tags and changed the words.
		p.reader.respond = { _, _ ->
			PostedSnapContent("new words\n\nhttps://youtu.be/$vid\n\n$tags\n$photo", 2L)
		}
		p.controller.refresh(listOf("e1"))
		eventually { !p.controller.isRefreshing }
		val text = p.text("e1")!!
		assertEquals("new words\n\n$tags\n$photo", text)
		val shown = SnapMediaText.history(text)
		assertEquals(photo, shown.thumbnail?.source)
		assertFalse(shown.showsReplyPreview)
		assertFalse(shown.text.contains(photo))
	}

	// ── 2, 4, 5 and the editor, on the thread side ─────────────────────

	private val root = SnapReplyTarget.of("alice", "rustedwax-snap-1000-e1")!!

	private object NoDrafts : SnapReplyDraftStore {
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

	private class ThreadReader : SnapThreadReader {
		val calls = AtomicInteger()
		@Volatile var respond: (Int) -> List<SnapReply>? = { emptyList() }
		override fun read(rootAuthor: String, rootPermlink: String, viewer: String?): List<SnapReply>? =
			respond(calls.incrementAndGet())
	}

	private inner class Threads(var who: String? = "alice", sync: Boolean = false) {
		val reader = ThreadReader()
		val opened = mutableListOf<Pair<String, String>>()
		val controller = SnapThreadController(
			scope = CoroutineScope(Dispatchers.Unconfined),
			reader = { reader },
			publisher = { null },
			account = { who },
			drafts = NoDrafts,
			previewStore = Previews(),
			editor = { com.rustedwax.app.snaps.SnapEditor(NoWrites()) },
			onRootOpened = { a, id -> opened += a to id },
			io = if (sync) Dispatchers.Unconfined else io,
		)
	}

	private fun reply(body: String) = SnapReply("bob", "re-1", root.author, root.permlink, body, 1_000L)

	@Test
	fun `opening and reopening Comments fetches root and replies each time`() {
		val t = Threads(sync = true)
		t.controller.open(root, null)
		t.controller.close()
		t.controller.open(root, null)
		assertEquals(2, t.reader.calls.get())
		assertEquals(listOf("alice" to root.contentId, "alice" to root.contentId), t.opened)
	}

	@Test
	fun `opening Comments re-reads the root Snap through the posting controller`() {
		val p = Posts().apply { add("alice", "e1", "a") }.restored { v1("a") }
		p.reader.respond = { _, _ -> PostedSnapContent("reshaped root $tags", 2L) }
		val threads = SnapThreadController(
			scope = CoroutineScope(Dispatchers.Unconfined),
			reader = { ThreadReader() },
			publisher = { null },
			account = { p.who },
			drafts = NoDrafts,
			previewStore = Previews(),
			onRootOpened = p.controller::refreshContent,
			io = Dispatchers.Unconfined,
		)
		val target = SnapReplyTarget.of("alice", "rustedwax-snap-1000-e1")!!
		threads.open(target, p.controller.posted(p.key("e1")))
		eventually { p.controller.postedFor(target.contentId)?.userText == "reshaped root $tags" }
	}

	@Test
	fun `a failed thread read keeps the conversation, and Retry really reads again`() {
		val t = Threads(sync = true)
		t.reader.respond = { listOf(reply("first")) }
		t.controller.open(root, null)
		t.reader.respond = { null }
		t.controller.load(root, force = true)
		assertEquals("first", t.controller.thread(root)!!.rows.single().reply.body)

		t.reader.respond = { listOf(reply("second")) }
		t.controller.load(root, force = true) // the sheet's Retry
		assertEquals(3, t.reader.calls.get())
		assertEquals("second", t.controller.thread(root)!!.rows.single().reply.body)
	}

	@Test
	fun `a post-write re-read asked for while a read is in flight runs after it`() {
		val t = Threads()
		val slow = latch()
		t.reader.respond = { n ->
			if (n == 1) {
				slow.await()
				listOf(reply("before the write"))
			} else {
				listOf(reply("after the write"))
			}
		}
		t.controller.open(root, null)
		eventually { t.reader.calls.get() == 1 }
		// A repeated open joins the read in flight...
		t.controller.load(root, force = true)
		// ...but trigger 4 — a write just landed — is owed a read taken after it.
		t.controller.reloadAfterWrite(root)
		t.controller.reloadAfterWrite(root)
		slow.countDown()
		eventually { t.controller.thread(root)?.rows?.singleOrNull()?.reply?.body == "after the write" }
		Thread.sleep(50)
		assertEquals("queued once, not once per request", 2, t.reader.calls.get())
	}

	@Test
	fun `an unforced load during a read does not queue another`() {
		val t = Threads()
		val slow = latch()
		t.reader.respond = { slow.await(); emptyList() }
		t.controller.load(root)
		eventually { t.reader.calls.get() == 1 }
		t.controller.load(root)
		slow.countDown()
		eventually { t.controller.thread(root) != null }
		Thread.sleep(50)
		assertEquals(1, t.reader.calls.get())
	}

	@Test
	fun `a refresh arriving during Edit never touches the draft`() {
		val t = Threads(sync = true)
		val mine = SnapReply("alice", "rustedwax-reply-1000-x", root.author, root.permlink, "old", 1_000L)
		t.reader.respond = { listOf(mine) }
		t.controller.open(root, null)
		val target = SnapReplyTarget.of(mine)!!
		t.controller.startEdit(root, target, SnapEditKind.REPLY, "old")
		t.controller.editDraft("my half-typed edit")

		t.reader.respond = { listOf(mine.copy(body = "changed on another frontend")) }
		t.controller.load(root, force = true)

		assertEquals("my half-typed edit", t.controller.editText)
		assertEquals(target, t.controller.editing?.target)
		// The fresh chain state is kept separately, on the thread.
		assertEquals("changed on another frontend", t.controller.thread(root)!!.rows.single().reply.body)
	}


	@Test
	fun `saving a root edit re-fetches the root and thread and persists the accepted body`() {
		val p = Posts().apply { add("alice", "e1", "before") }.restored { v1("before") }
		val target = SnapReplyTarget.of("alice", "rustedwax-snap-1000-e1")!!
		var chain = SnapChainComment("alice", target.permlink, "peak.snaps", "snap-container-1", "", v1("before"), "{}")
		var operation: TxSerializer.CommentOp? = null
		var writes = 0
		val port = object : SnapHivePort by NoWrites() {
			override fun readComment(author: String, permlink: String) = chain
			override fun prepareComment(op: TxSerializer.CommentOp, author: String): HivePreparationResult {
				operation = op
				return HivePreparationResult.Ready(PreparedHiveTransaction("{}", "edit-tx", 2_000_000_000L))
			}
			override fun broadcastPrepared(prepared: PreparedHiveTransaction, author: String): HiveRpc.BroadcastResult {
				writes++
				chain = chain.copy(body = operation!!.body)
				return HiveRpc.BroadcastResult.Success("edit-tx", "node", HiveRpc.BroadcastResult.Evidence.BLOCK)
			}
		}
		p.reader.respond = { _, _ -> PostedSnapContent(chain.body, 1_000L) }
		val reader = ThreadReader()
		val threads = SnapThreadController(
			scope = CoroutineScope(Dispatchers.Unconfined), reader = { reader }, publisher = { null },
			account = { p.who }, drafts = NoDrafts, previewStore = Previews(),
			editor = { SnapEditor(port, p.store) }, onRootEdited = p.controller::applyEdit,
			io = Dispatchers.Unconfined,
		)
		threads.open(target, p.controller.posted(p.key("e1")))
		threads.startEdit(target, target, SnapEditKind.ROOT, "before")
		threads.editDraft("after")
		threads.saveEdit()
		// applyEdit draws the proven words at once; the re-fetch lands after.
		eventually {
			p.reader.calls.get() == 1 && p.text("e1") == "after" &&
				p.cache.read("alice", target.contentId)?.chainBody == v1("after")
		}
		assertEquals(1, writes)
		assertEquals(1, p.reader.calls.get())
		assertEquals(2, reader.calls.get())
		assertNull(threads.editing)
		assertEquals(v1("after"), p.store.saved["alice|e1"]?.body)
		assertEquals(v1("after"), p.cache.read("alice", target.contentId)?.chainBody)
		assertEquals("after", PostedSnaps(p.store, p.reader, p.cache).local("alice", "e1")?.userText)
	}

	@Test
	fun `a locally changed record rejects an earlier answer before the edit callback arrives`() {
		val p = Posts().apply { add("alice", "e1", "before") }.restored { v1("before") }
		val slow = latch()
		p.reader.respond = { _, _ -> slow.await(); PostedSnapContent(v1("before"), 1L) }
		p.controller.refresh(listOf("e1"))
		eventually { p.reader.calls.get() == 1 }
		p.store.saved["alice|e1"] = p.store.saved["alice|e1"]!!.copy(body = v1("after"))
		slow.countDown()
		eventually { !p.controller.isRefreshing }
		assertEquals("after", PostedSnaps(p.store, p.reader, p.cache).local("alice", "e1")?.userText)
		assertTrue(p.controller.refreshFailed)
	}

	@Test
	fun `a root refresh during Edit leaves the editor alone`() {
		val p = Posts().apply { add("alice", "e1", "a") }.restored { v1("a") }
		val t = Threads(sync = true)
		t.controller.open(root, p.controller.posted(p.key("e1")))
		t.controller.startEdit(root, root, SnapEditKind.ROOT, "a")
		t.controller.editDraft("typing")
		p.reader.respond = { _, _ -> PostedSnapContent("elsewhere $tags", 2L) }
		p.controller.refresh(listOf("e1"))
		eventually { !p.controller.isRefreshing }
		assertEquals("elsewhere $tags", p.text("e1"))
		assertEquals("typing", t.controller.editText)
		assertEquals(root, t.controller.editing?.target)
	}
}
