package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.PendingSnap
import com.rustedwax.app.snaps.PendingSnapKind
import com.rustedwax.app.snaps.PendingSnapState
import com.rustedwax.app.snaps.SnapMedia
import com.rustedwax.app.snaps.SnapPayloadBuilder
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #47 Stage 47A: which records enter the My Snaps catalog, what each row
 * says, and how the page keeps one account's catalog away from another's.
 */
class MySnapsTest {

	private fun record(
		eventId: String,
		permlink: String = "rustedwax-snap-1700000000-$eventId",
		account: String = "alice",
		author: String = account,
		state: PendingSnapState = PendingSnapState.CONFIRMED,
		kind: PendingSnapKind = PendingSnapKind.ROOT,
		videoId: String = "dQw4w9WgXcQ",
		text: String = "words for $eventId",
		created: Long = 1_700_000_000L,
		body: String = SnapPayloadBuilder.build(text, SnapMedia(videoId)).body,
	) = PendingSnap(
		account = account,
		eventId = eventId,
		author = author,
		permlink = permlink,
		parentAuthor = "peak.snaps",
		parentPermlink = "snaps-container",
		body = body,
		jsonMetadata = "{}",
		signedTransactionJson = if (state == PendingSnapState.INTENT) "" else "{}",
		txId = "tx",
		expirationEpochSec = if (state == PendingSnapState.INTENT) 0L else 1L,
		state = state,
		createdAtEpochSec = created,
		updatedAtEpochSec = created,
		kind = kind,
	)

	// ── Backfill ──────────────────────────────────────────────────────────────

	@Test
	fun `only confirmed root records of this account enter the catalog`() {
		val records = listOf(
			record("ok"),
			record("reply", kind = PendingSnapKind.REPLY),
			*PendingSnapState.entries.filter { it != PendingSnapState.CONFIRMED }
				.map { record("s-${it.name}", state = it) }.toTypedArray(),
			record("bob", author = "bob"),
			record("other", account = "bob"),
		)
		val rows = MySnapsBackfill.rows("alice", records, media = { null })
		assertEquals(listOf("ok"), rows.map { it.eventId })
		assertEquals("alice", rows.single().owner)
		assertEquals("alice", rows.single().author)
	}

	@Test
	fun `a record whose identity is invalid is refused`() {
		val rows = MySnapsBackfill.rows(
			"alice",
			listOf(record("caps", account = "Alice", author = "Alice")),
			media = { null },
		)
		assertTrue(rows.isEmpty())
	}

	@Test
	fun `no account means no rows`() {
		assertTrue(MySnapsBackfill.rows("", listOf(record("a")), media = { null }).isEmpty())
		assertTrue(MySnapsBackfill.rows("-", listOf(record("a")), media = { null }).isEmpty())
	}

	@Test
	fun `the video id comes from the frozen body and History adds title and artist`() {
		val history = MySnapMedia("e1", "dQw4w9WgXcQ", "Song", "Band")
		val row = MySnapsBackfill.rows("alice", listOf(record("e1")), media = { if (it == "e1") history else null }).single()
		assertEquals("dQw4w9WgXcQ", row.videoId)
		assertEquals("Song", row.title)
		assertEquals("Band", row.artist)
		assertEquals("words for e1", row.userText)
		assertEquals(1_700_000_000L, row.createdAtEpochSec)
	}

	@Test
	fun `without its History row a Snap keeps the provable video id and nothing guessed`() {
		val row = MySnapsBackfill.rows("alice", listOf(record("gone")), media = { null }).single()
		assertEquals("dQw4w9WgXcQ", row.videoId)
		assertNull(row.title)
		assertNull(row.artist)
		assertNull(row.service)
	}

	@Test
	fun `History context naming a different video is not attached`() {
		val wrong = MySnapMedia("e1", "aaaaaaaaaaa", "Other", "Someone")
		val row = MySnapsBackfill.rows("alice", listOf(record("e1")), media = { wrong }).single()
		assertEquals("dQw4w9WgXcQ", row.videoId)
		assertNull(row.title)
	}

	@Test
	fun `a body that is not a v1 Snap body is not listed`() {
		val rows = MySnapsBackfill.rows("alice", listOf(record("raw", body = "just words")), media = { null })
		assertTrue(rows.isEmpty())
	}

	@Test
	fun `two references to one Hive object are one row`() {
		val rows = MySnapsBackfill.rows(
			"alice",
			listOf(record("a", permlink = "same"), record("b", permlink = "same")),
			media = { null },
		)
		assertEquals(1, rows.size)
	}

	@Test
	fun `the newest known words win over the record's own`() {
		val row = MySnapsBackfill.rows("alice", listOf(record("e")), media = { null }, latestText = { "edited" }).single()
		assertEquals("edited", row.userText)
	}

	// ── The page ──────────────────────────────────────────────────────────────

	/** The table's semantics in memory: coalescing upsert, newest-first keyset pages. */
	private class Rows : MySnapRows {
		val stored = linkedMapOf<Triple<String, String, String>, MySnapRow>()
		var fail = false
		val removed = mutableListOf<String>()
		val tombstones = mutableSetOf<Triple<String, String, String>>()
		var failTombstone = false

		override fun upsert(rows: List<MySnapRow>, nowMillis: Long, keepKnownText: Boolean) {
			if (fail) error("disk")
			rows.forEach { r ->
				val key = Triple(r.owner, r.author, r.permlink)
				if (key in tombstones) return@forEach
				val old = stored[key]
				stored[key] = if (old == null) r else r.copy(
					eventId = r.eventId ?: old.eventId,
					videoId = r.videoId ?: old.videoId,
					title = r.title ?: old.title,
					artist = r.artist ?: old.artist,
					service = r.service ?: old.service,
					userText = if (keepKnownText) old.userText ?: r.userText else r.userText ?: old.userText,
				)
			}
		}

		/** One transaction: on failure neither the tombstone nor the removal lands. */
		override fun tombstone(owner: String, author: String, permlink: String, txId: String?, nowMillis: Long) {
			if (failTombstone) error("disk")
			val key = Triple(owner, author, permlink)
			tombstones += key
			if (stored.remove(key) != null) removed += "$author/$permlink"
		}

		val checkpoints = mutableMapOf<String, String>()
		var failDiscovery = false

		override fun checkpoint(owner: String): String? = checkpoints[owner]

		/** Fails only the words part of the discovery transaction (Stage 47E). */
		var failWords = false

		/** One transaction: rows, words and checkpoint all land, or none of them. */
		override fun applyDiscovery(
			owner: String,
			rows: List<MySnapRow>,
			checkpoint: String,
			nowMillis: Long,
			reconciled: List<MySnapRow>,
		): List<String> {
			if (failDiscovery) error("disk")
			if (failWords && reconciled.isNotEmpty()) error("disk")
			rows.filter { it.owner == owner }.forEach { r ->
				val key = Triple(r.owner, r.author, r.permlink)
				if (key in tombstones) return@forEach
				val old = stored[key]
				stored[key] = if (old == null) r else old.copy(userText = r.userText ?: old.userText, videoId = old.videoId ?: r.videoId)
			}
			checkpoints[owner] = checkpoint
			return reconcile(owner, reconciled, nowMillis)
		}

		/** Update only, like the SQL: never inserts, skips tombstones and a different known video. */
		override fun reconcile(owner: String, rows: List<MySnapRow>, nowMillis: Long): List<String> {
			if (fail) error("disk")
			val changed = mutableListOf<String>()
			rows.filter { it.owner == owner && it.userText != null }.forEach { r ->
				val key = Triple(r.owner, r.author, r.permlink)
				if (key in tombstones) return@forEach
				val old = stored[key] ?: return@forEach
				if (old.videoId != null && r.videoId != null && old.videoId != r.videoId) return@forEach
				if (old.userText == r.userText) return@forEach
				stored[key] = old.copy(userText = r.userText)
				changed += r.contentId
			}
			return changed
		}

		override fun createdBetween(owner: String, from: Long, to: Long): List<MySnapRow> =
			stored.values.filter {
				it.owner == owner && it.createdAtEpochSec in from..to && Triple(it.owner, it.author, it.permlink) !in tombstones
			}

		override fun page(owner: String, limit: Int, after: MySnapRow?): List<MySnapRow> {
			if (fail) error("disk")
			return stored.values.filter { it.owner == owner && Triple(it.owner, it.author, it.permlink) !in tombstones }
				.sortedWith(compareByDescending<MySnapRow> { it.createdAtEpochSec }.thenByDescending { it.permlink })
				.filter {
					after == null || it.createdAtEpochSec < after.createdAtEpochSec ||
						(it.createdAtEpochSec == after.createdAtEpochSec && it.permlink < after.permlink)
				}
				.take(limit)
		}
	}

	private class Fixture(pageSize: Int = 30) {
		var account: String? = "alice"
		val rows = Rows()
		val records = mutableMapOf<String, List<PendingSnap>>()
		val controller = MySnapsController(
			scope = CoroutineScope(Dispatchers.Unconfined),
			account = { account },
			catalog = { rows },
			confirmedRoots = { records[it].orEmpty() },
			io = Dispatchers.Unconfined,
			clock = { 1L },
			pageSize = pageSize,
		)
	}

	@Test
	fun `opening backfills and lists newest first`() {
		val f = Fixture()
		f.records["alice"] = listOf(record("old", created = 10), record("new", created = 30), record("mid", created = 20))
		f.controller.open(emptyList())
		assertTrue(f.controller.ready)
		assertEquals(listOf("new", "mid", "old"), f.controller.rows.map { it.eventId })
	}

	@Test
	fun `a Snap stays listed after its History row and its record are both gone`() {
		val f = Fixture()
		f.records["alice"] = listOf(record("e1"))
		f.controller.open(listOf(MySnapMedia("e1", "dQw4w9WgXcQ", "Song", "Band")))
		f.records["alice"] = emptyList()
		f.controller.open(emptyList())
		val row = f.controller.rows.single()
		assertEquals("Song", row.title)
		assertEquals("Band", row.artist)
	}

	@Test
	fun `rereading a proven edit updates the same catalog row and preserves media`() {
		val f = Fixture()
		f.records["alice"] = listOf(record("e1"))
		f.controller.open(listOf(MySnapMedia("e1", "dQw4w9WgXcQ", "Song", "Band")))
		val identity = f.controller.rows.single().contentId
		// Since 47E a proven edit reaches the row through applyEdit; reopening
		// with the record's words no longer rewrites a known row.
		f.records["alice"] = listOf(record("e1", text = "edited words"))
		f.controller.applyEdit("alice", identity, "edited words")
		f.controller.open(emptyList())
		val row = f.controller.rows.single()
		assertEquals(identity, row.contentId)
		assertEquals("edited words", row.userText)
		assertEquals("Song", row.title)
		assertEquals("Band", row.artist)
		assertEquals(row, f.rows.stored.values.single())
	}

	@Test
	fun `reopening does not duplicate rows`() {
		val f = Fixture()
		f.records["alice"] = listOf(record("e1"), record("e2"))
		repeat(3) { f.controller.open(emptyList()) }
		assertEquals(2, f.controller.rows.size)
		assertEquals(2, f.rows.stored.size)
	}

	@Test
	fun `pages are loaded on demand`() {
		val f = Fixture(pageSize = 2)
		f.records["alice"] = (1..5).map { record("e$it", created = it.toLong()) }
		f.controller.open(emptyList())
		assertEquals(listOf("e5", "e4"), f.controller.rows.map { it.eventId })
		assertTrue(f.controller.hasMore)
		f.controller.loadMore()
		f.controller.loadMore()
		assertEquals(listOf("e5", "e4", "e3", "e2", "e1"), f.controller.rows.map { it.eventId })
		assertFalse(f.controller.hasMore)
	}

	@Test
	fun `switching accounts shows only the signed-in account's rows and keeps the other's`() {
		val f = Fixture()
		f.records["alice"] = listOf(record("a1"))
		f.records["bob"] = listOf(record("b1", account = "bob"))
		f.controller.open(emptyList())
		assertEquals(listOf("a1"), f.controller.rows.map { it.eventId })

		f.account = "bob"
		// Before Bob's page has even been opened, Alice's rows are not shown.
		assertTrue(f.controller.rows.isEmpty())
		f.controller.open(emptyList())
		assertEquals(listOf("b1"), f.controller.rows.map { it.eventId })

		f.account = "alice"
		f.records["alice"] = emptyList()
		f.controller.open(emptyList())
		assertEquals("A's catalog survived B", listOf("a1"), f.controller.rows.map { it.eventId })
	}

	@Test
	fun `signed out shows nothing`() {
		val f = Fixture()
		f.records["alice"] = listOf(record("a1"))
		f.controller.open(emptyList())
		f.account = null
		f.controller.open(emptyList())
		assertTrue(f.controller.rows.isEmpty())
		assertFalse(f.controller.ready)
	}

	@Test
	fun `a failed catalog read is not an empty catalog`() {
		val f = Fixture()
		f.records["alice"] = listOf(record("a1"))
		f.controller.open(emptyList())
		f.rows.fail = true
		f.controller.open(emptyList())
		assertTrue(f.controller.failed)
		assertEquals("rows on screen were kept", listOf("a1"), f.controller.rows.map { it.eventId })
	}

	@Test
	fun `a proven deletion removes exactly that Snap, and only for its own account`() {
		val f = Fixture()
		f.records["alice"] = listOf(record("a1", permlink = "p1"), record("a2", permlink = "p2"))
		f.controller.open(emptyList())

		f.controller.applyDelete("bob", "alice/p1")
		assertEquals(2, f.controller.rows.size)

		f.controller.applyDelete("alice", "alice/p1")
		assertEquals(listOf("a2"), f.controller.rows.map { it.eventId })
		assertEquals(listOf("alice/p1"), f.rows.removed)
	}

	/** Holds I/O answers until the UI explicitly accepts them. */
	private class QueuedDispatcher : CoroutineDispatcher() {
		val tasks = java.util.ArrayDeque<Runnable>()
		override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
		fun first() { tasks.removeFirst().run() }
		fun last() { tasks.removeLast().run() }
	}

	@Test
	fun `a page captured before proven deletion cannot restore the deleted row`() {
		assertLateReadCannotRestoreDeletedRow(loadMore = true)
	}

	@Test
	fun `a backfill answer captured before proven deletion cannot restore the deleted row`() {
		assertLateReadCannotRestoreDeletedRow(loadMore = false)
	}

	private fun assertLateReadCannotRestoreDeletedRow(loadMore: Boolean) {
		val main = QueuedDispatcher()
		val io = QueuedDispatcher()
		val rows = Rows()
		val records = mutableListOf(record("new", created = 3), record("old", created = 2))
		val controller = MySnapsController(
			scope = CoroutineScope(main), account = { "alice" }, catalog = { rows },
			confirmedRoots = { records.toList() }, io = io, pageSize = if (loadMore) 1 else 2,
		)
		controller.open(emptyList())
		while (main.tasks.isNotEmpty() || io.tasks.isNotEmpty()) {
			if (main.tasks.isNotEmpty()) main.first()
			if (io.tasks.isNotEmpty()) io.first()
		}
		if (loadMore) {
			controller.loadMore()
		} else {
			controller.open(emptyList())
			main.first()
			io.first()
		}
		main.first()
		io.first() // Captures the older row; its UI continuation remains queued.
		val deleted = records.removeAt(1)
		controller.applyDelete("alice", "${deleted.author}/${deleted.permlink}")
		main.last()
		io.first()
		main.last() // The proven deletion has removed the catalog row.
		main.first() // Now deliver the older page's captured answer.
		assertEquals(listOf("new"), controller.rows.map { it.eventId })
		assertEquals(listOf("new"), rows.stored.values.map { it.eventId })
	}


	// ── A proven deletion survives restart and storage failure ────────────

	/** A controller as a fresh process builds it: no memory of earlier deletions. */
	private fun process(rows: Rows, account: () -> String? = { "alice" }, records: () -> List<PendingSnap>) =
		MySnapsController(
			scope = CoroutineScope(Dispatchers.Unconfined), account = account, catalog = { rows },
			confirmedRoots = { records() }, io = Dispatchers.Unconfined, clock = { 1L },
		)

	private fun indexed(): Pair<Rows, MutableList<PendingSnap>> {
		val rows = Rows()
		val records = mutableListOf(
			record("keep", permlink = "p-keep", created = 2),
			record("gone", permlink = "p-gone", created = 1),
		)
		process(rows) { records.toList() }.open(emptyList())
		assertEquals(2, rows.stored.size)
		return rows to records
	}

	@Test
	fun `the process dying before the old asynchronous removal no longer resurrects the Snap`() {
		val (rows, records) = indexed()
		val main = QueuedDispatcher()
		val io = QueuedDispatcher()
		val dying = MySnapsController(
			scope = CoroutineScope(main), account = { "alice" }, catalog = { rows },
			confirmedRoots = { records.toList() }, io = io, clock = { 1L },
		)
		// The deletion path: durable step, then retirement, then the callback,
		// whose own queued work never runs because the process dies.
		assertTrue(dying.recordProvenDeletion("alice", "alice/p-gone", "tx"))
		records.removeAll { it.permlink == "p-gone" }
		dying.applyDelete("alice", "alice/p-gone")

		val after = process(rows) { records.toList() }
		after.open(emptyList())
		assertEquals(listOf("keep"), after.rows.map { it.eventId })
	}

	@Test
	fun `dying after the tombstone but before retirement does not restore the Snap from its old record`() {
		val (rows, records) = indexed()
		assertTrue(process(rows) { records.toList() }.recordProvenDeletion("alice", "alice/p-gone", "tx"))
		// Process death: the confirmed record was never retired.

		val after = process(rows) { records.toList() }
		after.open(emptyList())
		assertEquals(listOf("keep"), after.rows.map { it.eventId })
		assertEquals("the old record is still there, and still ignored", 2, records.size)
	}

	@Test
	fun `a failed tombstone transaction reports failure and changes nothing`() {
		val (rows, records) = indexed()
		rows.failTombstone = true
		assertFalse(process(rows) { records.toList() }.recordProvenDeletion("alice", "alice/p-gone", "tx"))
		assertTrue(rows.tombstones.isEmpty())
		assertEquals(2, rows.stored.size)
	}

	@Test
	fun `after a failed tombstone the kept record lists the Snap until the read-only retry records it`() {
		val (rows, records) = indexed()
		rows.failTombstone = true
		val first = process(rows) { records.toList() }
		first.open(emptyList())
		assertFalse(first.recordProvenDeletion("alice", "alice/p-gone", "tx"))
		// The deleter keeps the record; this session hides the row anyway.
		first.applyDelete("alice", "alice/p-gone")
		assertEquals(listOf("keep"), first.rows.map { it.eventId })

		// Restart with storage still failing: the record is the only evidence left.
		val restarted = process(rows) { records.toList() }
		restarted.open(emptyList())
		assertEquals(listOf("keep", "gone"), restarted.rows.map { it.eventId })

		// Storage recovers; asking to delete again proves absence by reading and
		// lands the tombstone before the record goes.
		rows.failTombstone = false
		assertTrue(restarted.recordProvenDeletion("alice", "alice/p-gone", null))
		records.removeAll { it.permlink == "p-gone" }
		val healed = process(rows) { records.toList() }
		healed.open(emptyList())
		assertEquals(listOf("keep"), healed.rows.map { it.eventId })
	}

	@Test
	fun `the in-session callback lands the tombstone itself when the deletion path's write failed`() {
		val (rows, records) = indexed()
		rows.failTombstone = true
		val session = process(rows) { records.toList() }
		assertFalse(session.recordProvenDeletion("alice", "alice/p-gone", "tx"))
		rows.failTombstone = false
		session.applyDelete("alice", "alice/p-gone")

		val after = process(rows) { records.toList() }
		after.open(emptyList())
		assertEquals(listOf("keep"), after.rows.map { it.eventId })
	}

	@Test
	fun `a tombstone is per account, per exact identity, and leaves other rows alone`() {
		val rows = Rows()
		val alice = mutableListOf(record("a1", permlink = "same"), record("a2", permlink = "other"))
		val bob = mutableListOf(record("b1", permlink = "same", account = "bob"))
		var who = "alice"
		val c = process(rows, { who }) { if (who == "alice") alice.toList() else bob.toList() }
		c.open(emptyList())
		who = "bob"; c.open(emptyList())

		assertFalse("another account's Snap is refused", c.recordProvenDeletion("bob", "alice/same", null))
		assertFalse("a malformed id is refused", c.recordProvenDeletion("alice", "alice", null))
		assertTrue(c.recordProvenDeletion("alice", "alice/same", null))

		who = "alice"; c.open(emptyList())
		assertEquals(listOf("a2"), c.rows.map { it.eventId })
		who = "bob"; c.open(emptyList())
		assertEquals(listOf("b1"), c.rows.map { it.eventId })
	}

	@Test
	fun `nothing but a proven deletion tombstones — a missing record never does`() {
		val (rows, records) = indexed()
		records.clear()
		val after = process(rows) { records.toList() }
		after.open(emptyList())
		assertEquals(listOf("keep", "gone"), after.rows.map { it.eventId })
		assertTrue(rows.tombstones.isEmpty())
	}

	// ── Stage 47B: discovery from Hive ─────────────────────────────────────

	private fun chainRoot(permlink: String, created: Long, text: String = "chain words", account: String = "alice") =
		org.json.JSONObject()
			.put("author", account).put("permlink", permlink)
			.put("parent_author", "peak.snaps").put("parent_permlink", "snap-container-1790947440")
			.put("depth", 1).put("json_metadata", org.json.JSONObject().put("app", "rustedwax/0.13.0"))
			.put("body", SnapPayloadBuilder.build(text, SnapMedia("dQw4w9WgXcQ")).body)
			.put("created", java.time.Instant.ofEpochSecond(created).toString().removeSuffix("Z"))

	private fun chainOther(permlink: String, created: Long) = chainRoot(permlink, created)
		.put("json_metadata", org.json.JSONObject().put("app", "peakd/2026.7.5"))

	/** One fake node serving [chain]'s comments for whichever account asks. */
	private class Chain {
		val comments = mutableMapOf<String, MutableList<org.json.JSONObject>>()
		var down = false
		var passes = 0
		val absent = mutableSetOf<String>()
		val absenceChecks = mutableListOf<String>()
		fun discovery() = MySnapsDiscovery(listOf("n1"), { _, account, after ->
			if (down) error("offline")
			if (after == null) passes++
			val list = comments[account].orEmpty().sortedByDescending { it.getString("created") }
			val start = after?.let { c -> list.indexOfFirst { it.getString("permlink") == c.permlink } } ?: 0
			list.drop(start).take(MySnapsDiscovery.PAGE_SIZE)
		})
	}

	private val t0 = 1_791_000_000L

	private fun discovering(
		rows: Rows,
		chain: Chain,
		records: () -> List<PendingSnap> = { emptyList() },
		account: () -> String? = { "alice" },
		clock: () -> Long = { 10_000_000L },
		history: (PendingSnap) -> String? = { null },
		read: (String, String) -> org.json.JSONObject? = { _, _ -> null },
	) = MySnapsController(
		scope = CoroutineScope(Dispatchers.Unconfined), account = account, catalog = { rows },
		confirmedRoots = { records() }, latestText = history, io = Dispatchers.Unconfined, clock = clock,
		discovery = chain.discovery(),
		provenAbsent = { a, p -> chain.absenceChecks += "$a/$p"; chain.down.not() && "$a/$p" in chain.absent },
		readRoot = read,
	)

	@Test
	fun `a Snap published on another installation is discovered and listed`() {
		val rows = Rows()
		val chain = Chain().apply { comments["alice"] = mutableListOf(chainRoot("rustedwax-snap-${t0}-other1", t0)) }
		val c = discovering(rows, chain)
		c.open(emptyList())
		assertTrue(c.rows.isEmpty())
		c.refresh(force = true)
		val row = c.rows.single()
		assertEquals("alice/rustedwax-snap-${t0}-other1", row.contentId)
		assertEquals("chain words", row.userText)
		assertNull("nothing guessed", row.title)
		assertNull(row.eventId)
	}

	@Test
	fun `other frontends' Snaps and RustedWax Snaps edited elsewhere are not imported`() {
		val rows = Rows()
		val chain = Chain().apply {
			comments["alice"] = mutableListOf(
				chainOther("rustedwax-snap-${t0}-peakd1", t0),
				chainOther("re-peaksnaps-abc", t0 - 10),
			)
		}
		val c = discovering(rows, chain)
		c.open(emptyList())
		c.refresh(force = true)
		assertTrue(c.rows.isEmpty())
	}

	@Test
	fun `a discovered Snap this device also knows stays one row and keeps its local context`() {
		val rows = Rows()
		val local = record("e1", permlink = "rustedwax-snap-${t0}-known1", created = t0)
		val chain = Chain().apply { comments["alice"] = mutableListOf(chainRoot("rustedwax-snap-${t0}-known1", t0 + 3, text = "edited on chain")) }
		val c = discovering(rows, chain, records = { listOf(local) })
		c.open(listOf(MySnapMedia("e1", "dQw4w9WgXcQ", "Song", "Band")))
		c.refresh(force = true)
		val row = c.rows.single()
		assertEquals("e1", row.eventId)
		assertEquals("Song", row.title)
		assertEquals("Band", row.artist)
		assertEquals("the record's creation time is kept", t0, row.createdAtEpochSec)
		assertEquals("edited on chain", row.userText)
	}

	@Test
	fun `a tombstoned Snap is never brought back by discovery`() {
		val rows = Rows()
		val chain = Chain().apply { comments["alice"] = mutableListOf(chainRoot("rustedwax-snap-${t0}-gone01", t0)) }
		val c = discovering(rows, chain)
		c.open(emptyList())
		assertTrue(c.recordProvenDeletion("alice", "alice/rustedwax-snap-${t0}-gone01", "tx"))
		c.refresh(force = true)
		assertTrue(c.rows.isEmpty())
		assertTrue(rows.stored.isEmpty())
	}

	@Test
	fun `a failed refresh keeps every row and says so, and a later success clears it`() {
		val rows = Rows()
		val chain = Chain().apply { comments["alice"] = mutableListOf(chainRoot("rustedwax-snap-${t0}-keep01", t0)) }
		val c = discovering(rows, chain)
		c.open(emptyList())
		c.refresh(force = true)
		assertEquals(1, c.rows.size)
		chain.down = true
		c.refresh(force = true)
		assertTrue(c.refreshFailed)
		assertEquals(1, c.rows.size)
		assertTrue(rows.tombstones.isEmpty())
		chain.down = false
		c.refresh(force = true)
		assertFalse(c.refreshFailed)
	}

	@Test
	fun `a storage failure while applying a pass keeps the old checkpoint and rows`() {
		val rows = Rows()
		val chain = Chain().apply { comments["alice"] = mutableListOf(chainRoot("rustedwax-snap-${t0}-new001", t0)) }
		val c = discovering(rows, chain)
		c.open(emptyList())
		rows.failDiscovery = true
		c.refresh(force = true)
		assertTrue(c.refreshFailed)
		assertTrue(rows.checkpoints.isEmpty())
		assertTrue(rows.stored.isEmpty())
	}

	@Test
	fun `opening refreshes only when stale, and pull-to-refresh always does`() {
		val rows = Rows()
		val chain = Chain().apply { comments["alice"] = mutableListOf(chainRoot("rustedwax-snap-${t0}-aaaaaa", t0)) }
		var now = 10_000_000L
		val c = discovering(rows, chain, clock = { now })
		c.open(emptyList())
		c.refresh(force = false)
		assertEquals("never refreshed: stale", 1, chain.passes)
		now += MySnapsController.STALE_AFTER_MILLIS - 1
		c.refresh(force = false)
		assertEquals("fresh: skipped", 1, chain.passes)
		c.refresh(force = true)
		assertEquals("pull-to-refresh: always", 2, chain.passes)
		now += MySnapsController.STALE_AFTER_MILLIS
		c.refresh(force = false)
		assertEquals(3, chain.passes)
	}

	@Test
	fun `the catalog has its own checkpoint, apart from History's refresh stamp`() {
		val rows = Rows()
		val chain = Chain().apply { comments["alice"] = mutableListOf(chainRoot("rustedwax-snap-${t0}-aaaaaa", t0)) }
		val c = discovering(rows, chain)
		c.open(emptyList())
		c.refresh(force = true)
		val cp = CatalogCheckpoint.decode(rows.checkpoints["alice"])
		assertEquals(10_000_000L, cp.lastSuccessMs)
		assertTrue(cp.deepDone)
		assertEquals(MySnapsController.STALE_AFTER_MILLIS, SnapPostController.STALE_AFTER_MILLIS)
	}

	@Test
	fun `a known Snap Hive stopped listing is tombstoned only when current nodes prove it gone`() {
		val rows = Rows()
		val chain = Chain().apply {
			comments["alice"] = mutableListOf(
				chainRoot("rustedwax-snap-${t0 + 30_000}-top001", t0 + 30_000),
				chainRoot("rustedwax-snap-${t0}-bottom", t0),
			)
		}
		val records = listOf(
			record("gone", permlink = "rustedwax-snap-${t0 + 20_000}-gone01", created = t0 + 20_000),
			record("kept", permlink = "rustedwax-snap-${t0 + 10_000}-kept01", created = t0 + 10_000),
		)
		val c = discovering(rows, chain, records = { records })
		c.open(emptyList())
		assertEquals(2, rows.stored.size)
		// Hive no longer lists either; only one is proven absent.
		chain.absent += "alice/rustedwax-snap-${t0 + 20_000}-gone01"
		c.refresh(force = true)
		assertEquals(
			listOf("rustedwax-snap-${t0 + 30_000}-top001", "rustedwax-snap-${t0 + 10_000}-kept01", "rustedwax-snap-${t0}-bottom"),
			c.rows.map { it.permlink },
		)
		assertEquals(setOf(Triple("alice", "alice", "rustedwax-snap-${t0 + 20_000}-gone01")), rows.tombstones)

		// And restart: the proven deletion holds, the unproven one is still listed.
		val after = discovering(rows, Chain(), records = { records })
		after.open(emptyList())
		assertFalse(after.rows.any { it.permlink.endsWith("gone01") })
		assertTrue(after.rows.any { it.permlink.endsWith("kept01") })
	}

	@Test
	fun `a deleted newest Snap is caught, and a present older one is kept`() {
		val rows = Rows()
		val chain = Chain().apply { comments["alice"] = mutableListOf(chainRoot("rustedwax-snap-${t0}-only01", t0)) }
		val outside = record("old", permlink = "rustedwax-snap-${t0 - 100_000}-old001", created = t0 - 100_000)
		// Newer than anything Hive lists: deleted, or not indexed yet.
		val deletedNewest = record("gone", permlink = "rustedwax-snap-${t0 + 100_000}-gone01", created = t0 + 100_000)
		val notIndexedYet = record("new", permlink = "rustedwax-snap-${t0 + 200_000}-new001", created = t0 + 200_000)
		val c = discovering(rows, chain, records = { listOf(outside, deletedNewest, notIndexedYet) }, clock = { (t0 + 300_000) * 1000 })
		chain.absent += listOf("alice/${deletedNewest.permlink}")
		c.open(emptyList())
		c.refresh(force = true)
		assertEquals(setOf(Triple("alice", "alice", deletedNewest.permlink)), rows.tombstones)
		assertTrue("present on Hive: kept", c.rows.any { it.permlink == notIndexedYet.permlink })
		assertTrue(c.rows.any { it.permlink == outside.permlink })
	}

	@Test
	fun `an offline proof read never tombstones`() {
		val rows = Rows()
		val chain = Chain().apply {
			comments["alice"] = mutableListOf(
				chainRoot("rustedwax-snap-${t0 + 30_000}-top001", t0 + 30_000),
				chainRoot("rustedwax-snap-${t0}-bottom", t0),
			)
		}
		val missing = record("m", permlink = "rustedwax-snap-${t0 + 20_000}-miss01", created = t0 + 20_000)
		val c = MySnapsController(
			scope = CoroutineScope(Dispatchers.Unconfined), account = { "alice" }, catalog = { rows },
			confirmedRoots = { listOf(missing) }, io = Dispatchers.Unconfined, clock = { 1L },
			discovery = chain.discovery(), provenAbsent = { _, _ -> error("timeout") },
		)
		c.open(emptyList())
		c.refresh(force = true)
		assertTrue(rows.tombstones.isEmpty())
		assertTrue(c.rows.any { it.permlink.endsWith("miss01") })
	}

	@Test
	fun `a pass that began before a proven local edit cannot write the older words`() {
		val rows = Rows()
		val chain = Chain().apply { comments["alice"] = mutableListOf(chainRoot("rustedwax-snap-${t0}-edit01", t0, text = "before")) }
		var now = 1_000L
		lateinit var c: MySnapsController
		val discovery = chain.discovery()
		val slow = MySnapsDiscovery(listOf("n1"), { node, account, after ->
			// The edit lands while this pass is reading Hive.
			c.noteEdit("alice", "alice/rustedwax-snap-${t0}-edit01")
			chain.comments.getValue(account).sortedByDescending { it.getString("created") }.take(20)
		})
		rows.upsert(listOf(MySnapRow("alice", "alice", "rustedwax-snap-${t0}-edit01", t0, null, "dQw4w9WgXcQ", null, null, null, "after")), 1L)
		c = MySnapsController(
			scope = CoroutineScope(Dispatchers.Unconfined), account = { "alice" }, catalog = { rows },
			confirmedRoots = { emptyList() }, io = Dispatchers.Unconfined, clock = { now },
			discovery = slow,
		)
		c.open(emptyList())
		c.refresh(force = true)
		assertEquals("after", c.rows.single().userText)
	}

	@Test
	fun `a pass finishing after an account switch shows nothing of it to the new account`() {
		val rows = Rows()
		var who = "alice"
		val chain = Chain().apply {
			comments["alice"] = mutableListOf(chainRoot("rustedwax-snap-${t0}-alice1", t0, account = "alice"))
			comments["bob"] = mutableListOf(chainRoot("rustedwax-snap-${t0}-bob001", t0, account = "bob"))
		}
		val main = QueuedDispatcher()
		val c = MySnapsController(
			scope = CoroutineScope(main), account = { who }, catalog = { rows },
			confirmedRoots = { emptyList() }, io = Dispatchers.Unconfined, clock = { 1L },
			discovery = chain.discovery(),
		)
		c.open(emptyList())
		while (main.tasks.isNotEmpty()) main.first()
		c.refresh(force = true)
		who = "bob"
		c.open(emptyList())
		while (main.tasks.isNotEmpty()) main.first()
		assertEquals("Bob sees only his own catalog", emptyList<String>(), c.rows.map { it.permlink })
		assertEquals("Alice's pass still filed her own Snap under her", 1, rows.stored.keys.count { it.first == "alice" })
		assertTrue(rows.stored.keys.none { it.first == "bob" })

		c.refresh(force = true)
		while (main.tasks.isNotEmpty()) main.first()
		assertEquals(listOf("rustedwax-snap-${t0}-bob001"), c.rows.map { it.permlink })
		who = "alice"
		c.open(emptyList())
		while (main.tasks.isNotEmpty()) main.first()
		assertEquals(listOf("rustedwax-snap-${t0}-alice1"), c.rows.map { it.permlink })
	}

	@Test
	fun `the rotating check finds an old Snap deleted outside the scanned span, across passes`() {
		val rows = Rows()
		val chain = Chain().apply { comments["alice"] = mutableListOf(chainRoot("rustedwax-snap-${t0 + 900_000}-top001", t0 + 900_000)) }
		// Eight old rows Hive will not list (the scan never reaches them); one is gone.
		val old = (1..8).map { i -> record("o$i", permlink = "rustedwax-snap-${t0 - i * 10_000}-old00$i", created = t0 - i * 10_000) }
		val gone = old[6]
		chain.absent += "alice/${gone.permlink}"
		var now = 10_000_000L
		val c = discovering(rows, chain, records = { old }, clock = { now })
		c.open(emptyList())
		val checkedPerPass = mutableListOf<Int>()
		repeat(4) {
			val before = chain.absenceChecks.size
			now += MySnapsController.STALE_AFTER_MILLIS
			c.refresh(force = true)
			checkedPerPass += chain.absenceChecks.size - before
		}
		assertTrue("bounded per pass: $checkedPerPass", checkedPerPass.all { it <= MySnapsController.ROTATION_CHECKS })
		assertEquals(setOf(Triple("alice", "alice", gone.permlink)), rows.tombstones)
		assertEquals(7, c.rows.count { it.permlink.contains("-old00") })
		assertFalse("rows the pass saw on Hive are never exact-read", "alice/rustedwax-snap-${t0 + 900_000}-top001" in chain.absenceChecks)
	}

	@Test
	fun `the rotation cursor survives restarts and wraps round the catalog`() {
		val rows = Rows()
		val chain = Chain().apply { comments["alice"] = mutableListOf(chainRoot("rustedwax-snap-${t0 + 900_000}-top001", t0 + 900_000)) }
		val old = (1..5).map { i -> record("o$i", permlink = "rustedwax-snap-${t0 - i * 10_000}-old00$i", created = t0 - i * 10_000) }
		var now = 10_000_000L
		fun pass() {
			now += MySnapsController.STALE_AFTER_MILLIS
			// A fresh controller each time: only the stored checkpoint carries the cursor.
			discovering(rows, chain, records = { old }, clock = { now }).apply { open(emptyList()); refresh(force = true) }
		}
		pass()
		assertEquals(old.take(3).map { "alice/${it.permlink}" }, chain.absenceChecks)
		assertEquals(old[2].permlink, CatalogCheckpoint.decode(rows.checkpoints["alice"]).verifyPermlink)
		pass()
		assertEquals(old.drop(3).map { "alice/${it.permlink}" }, chain.absenceChecks.drop(3))
		assertNull("the end of the catalog wraps", CatalogCheckpoint.decode(rows.checkpoints["alice"]).verifyPermlink)
		pass()
		assertEquals(old.take(3).map { "alice/${it.permlink}" }, chain.absenceChecks.drop(5))
		assertTrue("all present: nothing tombstoned", rows.tombstones.isEmpty())
	}

	// ── 47B corrections: one pass per account; reserved rotation ───────────

	/** Codex's A→B→A reproduction: the first Alice pass is held while Bob and Alice refresh again. */
	@Test
	fun `A to B to A never runs two passes for one account, so an older pass cannot overwrite a newer one`() {
		val rows = Rows()
		var who = "alice"
		val aliceCalls = java.util.concurrent.atomic.AtomicInteger()
		val firstEntered = java.util.concurrent.CountDownLatch(1)
		val bobEntered = java.util.concurrent.CountDownLatch(1)
		val releaseFirst = java.util.concurrent.CountDownLatch(1)
		val releaseBob = java.util.concurrent.CountDownLatch(1)
		val main = QueuedDispatcher()
		val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
		val io = pool.asDispatcher()
		val p = "rustedwax-snap-${t0}-race01"
		val disc = MySnapsDiscovery(listOf("n1"), { _, account, _ ->
			if (account == "alice") {
				if (aliceCalls.incrementAndGet() == 1) {
					firstEntered.countDown()
					check(releaseFirst.await(5, java.util.concurrent.TimeUnit.SECONDS))
					listOf(chainRoot(p, t0, "older answer"))
				} else listOf(chainRoot(p, t0 + 100, "newer answer"))
			} else {
				bobEntered.countDown()
				check(releaseBob.await(5, java.util.concurrent.TimeUnit.SECONDS))
				emptyList()
			}
		})
		val c = MySnapsController(
			scope = CoroutineScope(main), account = { who }, catalog = { rows },
			confirmedRoots = { emptyList() }, io = io,
			clock = { (t0 + 1000) * 1000 }, discovery = disc,
		)
		fun pumpUntil(predicate: () -> Boolean) {
			val deadline = System.nanoTime() + 3_000_000_000L
			while (!predicate() && System.nanoTime() < deadline) {
				if (main.tasks.isNotEmpty()) main.first() else Thread.sleep(2)
			}
			check(predicate())
		}
		fun pumpIdle(ms: Long) {
			val until = System.nanoTime() + ms * 1_000_000
			while (System.nanoTime() < until) if (main.tasks.isNotEmpty()) main.first() else Thread.sleep(2)
		}
		try {
			c.open(emptyList()); pumpUntil { c.ready }
			pumpIdle(30)
			c.refresh(force = true); pumpIdle(30)
			check(firstEntered.await(3, java.util.concurrent.TimeUnit.SECONDS))
			assertTrue(c.refreshing)
			who = "bob"
			assertFalse("Alice's pass is not Bob's", c.refreshing)
			c.refresh(force = true); pumpIdle(30)
			check(bobEntered.await(3, java.util.concurrent.TimeUnit.SECONDS))
			who = "alice"
			assertTrue("Alice's claim survived the switch", c.refreshing)
			c.refresh(force = true) // refused: Alice already has a pass in flight
			pumpIdle(50)
			assertEquals(1, aliceCalls.get())

			releaseFirst.countDown()
			pumpUntil { !c.refreshing && c.rows.singleOrNull()?.userText == "older answer" }
			val firstHighWater = CatalogCheckpoint.decode(rows.checkpoints["alice"]).highWater!!

			// The claim is gone, so the next refresh runs — and only now is the newer answer read.
			c.refresh(force = true)
			pumpUntil { !c.refreshing && c.rows.singleOrNull()?.userText == "newer answer" }
			assertEquals(2, aliceCalls.get())
			val secondHighWater = CatalogCheckpoint.decode(rows.checkpoints["alice"]).highWater!!
			assertTrue("the checkpoint never moves backwards", secondHighWater >= firstHighWater)
			assertEquals(t0 + 100, secondHighWater)
		} finally {
			releaseFirst.countDown(); releaseBob.countDown(); pool.shutdownNow()
		}
	}

	private fun java.util.concurrent.ExecutorService.asDispatcher(): kotlinx.coroutines.CoroutineDispatcher =
		object : kotlinx.coroutines.CoroutineDispatcher() {
			override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { execute(block) }
		}

	@Test
	fun `two accounts refresh independently and each releases only its own claim`() {
		val rows = Rows()
		var who = "alice"
		val main = QueuedDispatcher()
		val io = QueuedDispatcher()
		val chain = Chain().apply {
			comments["alice"] = mutableListOf(chainRoot("rustedwax-snap-${t0}-alice1", t0, account = "alice"))
			comments["bob"] = mutableListOf(chainRoot("rustedwax-snap-${t0}-bob001", t0, account = "bob"))
		}
		val c = MySnapsController(
			scope = CoroutineScope(main), account = { who }, catalog = { rows },
			confirmedRoots = { emptyList() }, io = io, clock = { 1L }, discovery = chain.discovery(),
		)
		c.refresh(force = true); main.first() // Alice's pass is now waiting on io
		who = "bob"
		c.refresh(force = true); main.first() // Bob's too: a different account is not blocked
		assertEquals(2, io.tasks.size)
		assertTrue(c.refreshing)
		while (io.tasks.isNotEmpty() || main.tasks.isNotEmpty()) { if (io.tasks.isNotEmpty()) io.first(); if (main.tasks.isNotEmpty()) main.first() }
		assertFalse(c.refreshing)
		who = "alice"; assertFalse(c.refreshing)
		assertEquals(2, chain.passes)
		assertEquals(setOf("alice", "bob"), rows.checkpoints.keys)
	}

	@Test
	fun `a failed, thrown or cancelled pass releases its claim`() {
		val rows = Rows()
		val chain = Chain().apply { comments["alice"] = mutableListOf(chainRoot("rustedwax-snap-${t0}-aaaaaa", t0)) }
		// Failure: every node down.
		val c = discovering(rows, chain)
		chain.down = true
		c.refresh(force = true)
		assertFalse(c.refreshing)
		assertTrue(c.refreshFailed)
		// A catalog that throws.
		rows.fail = true
		c.refresh(force = true)
		assertFalse(c.refreshing)
		rows.fail = false
		// Cancellation: the scope dies while the pass waits.
		val main = QueuedDispatcher()
		val io = QueuedDispatcher()
		val scope = CoroutineScope(main + kotlinx.coroutines.SupervisorJob())
		val d = MySnapsController(
			scope = scope, account = { "alice" }, catalog = { rows },
			confirmedRoots = { emptyList() }, io = io, clock = { 1L }, discovery = chain.discovery(),
		)
		d.refresh(force = true); main.first()
		assertTrue(d.refreshing)
		scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
		while (main.tasks.isNotEmpty() || io.tasks.isNotEmpty()) { if (io.tasks.isNotEmpty()) io.first(); if (main.tasks.isNotEmpty()) main.first() }
		assertFalse("a cancelled pass's claim is released", d.refreshing)
		// And a later controller pass runs normally.
		chain.down = false
		c.refresh(force = true)
		assertFalse(c.refreshFailed)
	}

	/** Codex's saturation reproduction: twelve persistent in-span omissions, eight older rows, one gone. */
	@Test
	fun `persistent in-span omissions cannot starve the rotation of older rows`() {
		val rows = Rows()
		val chain = Chain().apply { comments["alice"] = mutableListOf(
			chainRoot("rustedwax-snap-${t0 + 900000}-top001", t0 + 900000),
			chainRoot("rustedwax-snap-${t0}-bottom", t0),
		) }
		val recent = (1..12).map { i -> record("r$i", permlink = "rustedwax-snap-${t0 + i * 10000}-r${i.toString().padStart(5, '0')}", created = t0 + i * 10000) }
		val old = (1..8).map { i -> record("o$i", permlink = "rustedwax-snap-${t0 - i * 10000}-o${i.toString().padStart(5, '0')}", created = t0 - i * 10000) }
		chain.absent += "alice/${old[6].permlink}"
		val c = discovering(rows, chain, records = { recent + old }, clock = { (t0 + 1000000) * 1000 })
		c.open(emptyList())
		val perPass = mutableListOf<Int>()
		repeat(30) {
			val before = chain.absenceChecks.size
			c.refresh(force = true)
			perPass += chain.absenceChecks.size - before
		}
		assertTrue("bounded: $perPass", perPass.all { it <= MySnapsController.MAX_ABSENCE_CHECKS })
		val oldReads = chain.absenceChecks.count { id -> old.any { "alice/${it.permlink}" == id } }
		assertTrue("older rows were read: $oldReads", oldReads >= old.size)
		assertEquals(setOf(Triple("alice", "alice", old[6].permlink)), rows.tombstones)
		assertEquals("the twelve unproven omissions are all kept", 12, c.rows.count { it.permlink.contains("-r0") })
		// Every in-span omission gets read too, not just the first seven.
		recent.forEach { r -> assertTrue(r.permlink, "alice/${r.permlink}" in chain.absenceChecks) }
	}

	@Test
	fun `an undecided exact read holds the rotation cursor, and that row is read first next time`() {
		val rows = Rows()
		val chain = Chain().apply { comments["alice"] = mutableListOf(chainRoot("rustedwax-snap-${t0 + 900_000}-top001", t0 + 900_000)) }
		val old = (1..5).map { i -> record("o$i", permlink = "rustedwax-snap-${t0 - i * 10_000}-old00$i", created = t0 - i * 10_000) }
		val undecided = mutableSetOf("alice/${old[1].permlink}")
		val asked = mutableListOf<String>()
		var now = 10_000_000L
		fun controller() = MySnapsController(
			scope = CoroutineScope(Dispatchers.Unconfined), account = { "alice" }, catalog = { rows },
			confirmedRoots = { old }, io = Dispatchers.Unconfined, clock = { now }, discovery = chain.discovery(),
			provenAbsent = { a, p -> asked += "$a/$p"; if ("$a/$p" in undecided) null else false },
		)
		controller().apply { open(emptyList()); refresh(force = true) }
		assertEquals(old.take(3).map { "alice/${it.permlink}" }, asked)
		assertEquals("held after the last decided row", old[0].permlink, CatalogCheckpoint.decode(rows.checkpoints["alice"]).verifyPermlink)
		// After a restart, with the node answering again.
		undecided.clear(); asked.clear(); now += 1
		controller().apply { open(emptyList()); refresh(force = true) }
		assertEquals(old.drop(1).take(3).map { "alice/${it.permlink}" }, asked)
		assertTrue(rows.tombstones.isEmpty())
	}

	@Test
	fun `a first row left undecided keeps the cursor exactly where it was`() {
		val rows = Rows()
		val chain = Chain().apply { comments["alice"] = mutableListOf(chainRoot("rustedwax-snap-${t0 + 900_000}-top001", t0 + 900_000)) }
		val old = (1..5).map { i -> record("o$i", permlink = "rustedwax-snap-${t0 - i * 10_000}-old00$i", created = t0 - i * 10_000) }
		val c = MySnapsController(
			scope = CoroutineScope(Dispatchers.Unconfined), account = { "alice" }, catalog = { rows },
			confirmedRoots = { old }, io = Dispatchers.Unconfined, clock = { 1L }, discovery = chain.discovery(),
			provenAbsent = { _, _ -> error("timeout") },
		)
		c.open(emptyList())
		c.refresh(force = true)
		val cp = CatalogCheckpoint.decode(rows.checkpoints["alice"])
		assertNull(cp.verifyAt)
		assertNull(cp.verifyPermlink)
		assertTrue(rows.tombstones.isEmpty())
	}

	@Test
	fun `rotation is per account and never reads or tombstones another account's rows`() {
		val rows = Rows()
		var who = "alice"
		val chain = Chain().apply {
			comments["alice"] = mutableListOf(chainRoot("rustedwax-snap-${t0 + 900_000}-top001", t0 + 900_000, account = "alice"))
			comments["bob"] = mutableListOf(chainRoot("rustedwax-snap-${t0 + 900_000}-top002", t0 + 900_000, account = "bob"))
		}
		val aliceOld = record("a1", permlink = "rustedwax-snap-${t0 - 10_000}-aold01", created = t0 - 10_000)
		val bobOld = record("b1", permlink = "rustedwax-snap-${t0 - 10_000}-bold01", created = t0 - 10_000, account = "bob")
		chain.absent += listOf("alice/${aliceOld.permlink}", "bob/${bobOld.permlink}")
		val c = discovering(rows, chain, records = { if (who == "alice") listOf(aliceOld) else listOf(bobOld) }, account = { who })
		c.open(emptyList()); c.refresh(force = true)
		assertEquals(listOf("alice/${aliceOld.permlink}"), chain.absenceChecks)
		assertEquals(setOf(Triple("alice", "alice", aliceOld.permlink)), rows.tombstones)
		who = "bob"; c.open(emptyList()); c.refresh(force = true)
		assertEquals(listOf("alice/${aliceOld.permlink}", "bob/${bobOld.permlink}"), chain.absenceChecks)
		assertEquals(2, rows.tombstones.size)
		assertTrue(rows.tombstones.all { it.first == it.second })
	}

	@Test
	fun `edit proven during rotation reads must not be overwritten by earlier discovery words`() {
		val rows = Rows()
		val p = "rustedwax-snap-${t0}-edit01"
		val old = record("old", permlink = "rustedwax-snap-${t0 - 10000}-old001", created = t0 - 10000)
		val edited = record("edit", permlink = p, created = t0)
		var text = "before"
		var now = 1000L
		lateinit var c: MySnapsController
		c = MySnapsController(
			scope = CoroutineScope(Dispatchers.Unconfined), account = { "alice" }, catalog = { rows },
			confirmedRoots = { listOf(edited, old) }, latestText = { text }, io = Dispatchers.Unconfined,
			clock = { now }, discovery = MySnapsDiscovery(listOf("n1"), { _, _, _ -> listOf(chainRoot(p, t0, "before")) }),
			provenAbsent = { _, _ ->
				// A successful editor callback arrives during the newly moved exact-read phase.
				now += 1; text = "after"
				c.noteEdit("alice", "alice/$p")
				rows.upsert(MySnapsBackfill.rows("alice", listOf(edited), { null }, { text }), now)
				assertEquals("edit backfill committed before discovery", "after", rows.stored[Triple("alice", "alice", p)]!!.userText)
				false
			},
		)
		c.open(emptyList()); c.refresh(force = true)
		assertEquals("after", rows.stored[Triple("alice", "alice", p)]!!.userText)
	}

	// ── Stage 47E: discovered roots' words after an edit ──────────────────

	private val ep = "rustedwax-snap-${t0}-other1"
	private val epKey = Triple("alice", "alice", ep)

	/** A discovered root with "before", no local record, as Stage 47B imports it. */
	private fun discoveredBefore(rows: Rows, chain: Chain, read: (String, String) -> org.json.JSONObject? = { _, _ -> null }): MySnapsController {
		chain.comments["alice"] = mutableListOf(chainRoot(ep, t0, "before"))
		val c = discovering(rows, chain, read = read)
		c.open(emptyList()); c.refresh(force = true)
		assertEquals("before", c.rows.single().userText)
		assertNull(c.rows.single().eventId)
		return c
	}

	private fun peakdEdited(text: String) = chainRoot(ep, t0, text)
		.put("json_metadata", org.json.JSONObject().put("app", "peakd/2026.7.5"))

	@Test
	fun `a proven edit changes a discovered card from before to after, on the same row`() {
		val rows = Rows()
		val c = discoveredBefore(rows, Chain())
		val was = rows.stored[epKey]!!

		c.applyEdit("alice", "alice/$ep", "after")

		assertEquals("after", c.rows.single().userText)
		assertEquals(1, rows.stored.size)
		assertEquals("same identity and media, new words only", was.copy(userText = "after"), rows.stored[epKey])
	}

	@Test
	fun `a proven edit survives a restart and an offline refresh`() {
		val rows = Rows()
		val chain = Chain()
		discoveredBefore(rows, chain).applyEdit("alice", "alice/$ep", "after")

		chain.down = true
		val restarted = discovering(rows, chain)
		restarted.open(emptyList())
		restarted.refresh(force = true)

		assertTrue(restarted.refreshFailed)
		assertEquals("after", restarted.rows.single().userText)
	}

	@Test
	fun `a known discovered root edited in another frontend refreshes on a forced pass`() {
		val rows = Rows()
		val chain = Chain()
		val c = discoveredBefore(rows, chain)

		chain.comments["alice"] = mutableListOf(peakdEdited("after"))
		c.refresh(force = true)

		assertEquals("after", c.rows.single().userText)
		assertEquals(1, rows.stored.size)
	}

	@Test
	fun `an unknown object edited by another frontend is still never imported`() {
		val rows = Rows()
		val chain = Chain().apply { comments["alice"] = mutableListOf(chainOther("rustedwax-snap-${t0}-newone", t0)) }
		val c = discovering(rows, chain) { _, _ -> chainOther("rustedwax-snap-${t0}-newone", t0) }
		c.open(emptyList()); c.refresh(force = true)
		c.refresh(force = true, manual = true)

		assertTrue(c.rows.isEmpty())
		assertTrue(rows.stored.isEmpty())
	}

	@Test
	fun `a proven edit landing during a discovery pass is not reversed by that pass`() {
		val rows = Rows()
		lateinit var c: MySnapsController
		var editDuringPass = false
		val disc = MySnapsDiscovery(listOf("n1"), { _, _, after ->
			if (after != null) return@MySnapsDiscovery emptyList()
			if (editDuringPass) {
				// The editor's proof lands while this pass is reading, and its
				// catalog write commits before the pass persists — as
				// applyEdit's own write would on a real dispatcher.
				c.noteEdit("alice", "alice/$ep")
				rows.reconcile("alice", listOf(rows.stored[epKey]!!.copy(userText = "after")), 1L)
			}
			listOf(if (editDuringPass) peakdEdited("older") else chainRoot(ep, t0, "before"))
		})
		c = MySnapsController(
			scope = CoroutineScope(Dispatchers.Unconfined), account = { "alice" }, catalog = { rows },
			confirmedRoots = { emptyList() }, io = Dispatchers.Unconfined, clock = { 10_000_000L }, discovery = disc,
		)
		c.open(emptyList()); c.refresh(force = true)
		assertEquals("before", c.rows.single().userText)

		editDuringPass = true
		c.refresh(force = true)

		assertEquals("after", c.rows.single().userText)
		assertEquals("after", rows.stored[epKey]!!.userText)
	}

	@Test
	fun `a tombstoned root is not brought back by an edit, a refresh or an open`() {
		val rows = Rows()
		val chain = Chain()
		val c = discoveredBefore(rows, chain) { _, _ -> peakdEdited("after") }

		assertTrue(c.recordProvenDeletion("alice", "alice/$ep", "tx"))
		c.applyDelete("alice", "alice/$ep")
		chain.comments["alice"] = mutableListOf(peakdEdited("after"))
		c.applyEdit("alice", "alice/$ep", "after")
		c.refresh(force = true, manual = true)
		c.open(emptyList())

		assertTrue(c.rows.isEmpty())
		assertTrue(rows.stored.isEmpty())
	}

	@Test
	fun `a locally confirmed root's proven edit lands on its row and keeps its record and History context`() {
		val rows = Rows()
		val local = record("e1", permlink = ep, created = t0, text = "before")
		val chain = Chain().apply { comments["alice"] = mutableListOf(chainRoot(ep, t0, "before")) }
		val c = discovering(rows, chain, records = { listOf(local) }, history = { "before" })
		c.open(listOf(MySnapMedia("e1", "dQw4w9WgXcQ", "Song", "Band")))
		c.refresh(force = true)
		val was = rows.stored[epKey]!!
		assertEquals("e1", was.eventId)

		c.applyEdit("alice", "alice/$ep", "after")
		assertEquals(was.copy(userText = "after"), rows.stored[epKey])
		// The record and its cache still say "before"; reopening keeps the proven words.
		c.open(emptyList())
		assertEquals(was.copy(userText = "after"), rows.stored[epKey])
		assertEquals(1, c.rows.size)
	}

	// ── Stage 47E follow-up: manual refresh reconciles older known roots ──

	private val day = 24 * 60 * 60L
	private val old = "rustedwax-snap-${t0 - 11 * day}-old11d"
	private val oldKey = Triple("alice", "alice", old)

	/** A comment of the account's that is not a root Snap: seen, never imported. */
	private fun filler(i: Int, created: Long) = org.json.JSONObject()
		.put("author", "alice").put("permlink", "re-someone-$i")
		.put("parent_author", "someone").put("parent_permlink", "post").put("depth", 2)
		.put("json_metadata", org.json.JSONObject().put("app", "peakd/2026.7.5"))
		.put("body", "a reply").put("created", java.time.Instant.ofEpochSecond(created).toString().removeSuffix("Z"))

	/**
	 * 20 recent comments, 20 from a day earlier, then [old] 11 days back: after
	 * the first full scan, a pass stops on the second page and never reaches it —
	 * the shape of the real account on 2026-10-05.
	 */
	private fun deepAccount(rootText: String) = mutableListOf<org.json.JSONObject>().apply {
		(0 until 20).forEach { add(filler(it, t0 + it)) }
		(20 until 40).forEach { add(filler(it, t0 - day - it)) }
		add(chainRoot(old, t0 - 11 * day, rootText))
	}

	private fun peakdOld(permlink: String, created: Long, text: String) = chainRoot(permlink, created, text)
		.put("json_metadata", org.json.JSONObject().put("app", "peakd/2026.7.5"))

	/** Imports [deepAccount], then rewrites [old] on chain as PeakD would; exact reads answer from [exact]. */
	private class Old(val rows: Rows, val chain: Chain, val c: MySnapsController, val reads: MutableList<String>, val exact: MutableMap<String, org.json.JSONObject?>)

	private fun oldSetup(
		account: () -> String? = { "alice" },
		extraOld: Int = 0,
		read: ((String) -> org.json.JSONObject?)? = null,
	): Old {
		val rows = Rows()
		val chain = Chain()
		val olds = (1..extraOld).map { "rustedwax-snap-${t0 - 11 * day - it}-o${"%05d".format(it)}" }
		chain.comments["alice"] = deepAccount("before").apply {
			olds.forEachIndexed { i, p -> add(chainRoot(p, t0 - 11 * day - i - 1, "before")) }
		}
		val reads = mutableListOf<String>()
		val exact = mutableMapOf<String, org.json.JSONObject?>()
		val c = discovering(rows, chain, account = account) { _, p -> reads += p; read?.invoke(p) ?: exact[p] }
		c.open(emptyList()); c.refresh(force = true)
		assertEquals("before", rows.stored[oldKey]!!.userText)
		reads.clear()
		val edited = peakdOld(old, t0 - 11 * day, "after")
		chain.comments["alice"]!![40] = edited
		exact[old] = edited
		return Old(rows, chain, c, reads, exact)
	}

	@Test
	fun `a manual refresh reconciles an old known root edited elsewhere, without opening it`() {
		val o = oldSetup()
		o.c.refresh(force = true, manual = true)

		assertEquals("after", o.rows.stored[oldKey]!!.userText)
		assertEquals("after", o.c.rows.single { it.permlink == old }.userText)
		assertFalse(o.c.contentPending)
		assertEquals("one exact read, for the one row the pass missed", listOf(old), o.reads)
		assertNull("nothing was opened", o.rows.stored[oldKey]!!.eventId)
	}

	@Test
	fun `automatic and deep-continuing passes make no content reads`() {
		val o = oldSetup()
		o.c.refresh(force = false)
		o.c.refresh(force = true)
		assertEquals(emptyList<String>(), o.reads)
		assertEquals("before", o.rows.stored[oldKey]!!.userText)
		assertFalse(o.c.contentPending)
	}

	@Test
	fun `a recent root the pass sees is reconciled by the pass, not read again`() {
		val rows = Rows()
		val chain = Chain().apply { comments["alice"] = mutableListOf(chainRoot(ep, t0, "before")) }
		val reads = mutableListOf<String>()
		val c = discovering(rows, chain) { _, p -> reads += p; null }
		c.open(emptyList()); c.refresh(force = true)
		chain.comments["alice"] = mutableListOf(peakdEdited("after"))
		c.refresh(force = true, manual = true)
		assertEquals("after", rows.stored[epKey]!!.userText)
		assertEquals(emptyList<String>(), reads)
	}

	@Test
	fun `only catalog rows are read, and an unknown edited object is never imported`() {
		val o = oldSetup()
		val stranger = "rustedwax-snap-${t0 - 12 * day}-strang"
		o.chain.comments["alice"]!!.add(peakdOld(stranger, t0 - 12 * day, "not mine to import"))
		o.exact[stranger] = peakdOld(stranger, t0 - 12 * day, "not mine to import")
		o.c.refresh(force = true, manual = true)
		assertFalse(stranger in o.reads)
		assertNull(o.rows.stored[Triple("alice", "alice", stranger)])
	}

	@Test
	fun `a failed read holds the cursor and says so, and the next pull finishes`() {
		var down = true
		lateinit var o: Old
		o = oldSetup { p -> if (down) error("offline") else o.exact[p] }
		o.c.refresh(force = true, manual = true)
		assertEquals("before", o.rows.stored[oldKey]!!.userText)
		assertTrue(o.c.contentPending)

		down = false
		o.c.refresh(force = true, manual = true)
		assertEquals("after", o.rows.stored[oldKey]!!.userText)
		assertFalse(o.c.contentPending)
	}

	@Test
	fun `foreign, malformed and absent answers keep the words and are not retried as failures`() {
		listOf(
			null,
			peakdOld(old, t0 - 11 * day, "x").put("author", "bob"),
			peakdOld(old, t0 - 11 * day, "x").put("body", "frozen tail gone"),
			peakdOld(old, t0 - 11 * day, "x").put("depth", 2),
			peakdOld(old, t0 - 11 * day, "x").put("parent_author", "someone"),
			peakdOld(old, t0 - 11 * day, "x").put("permlink", "rustedwax-snap-${t0}-zzzzzz"),
			peakdOld(old, t0 - 11 * day, "x").put("body", SnapPayloadBuilder.build("x", SnapMedia("aaaaaaaaaaa")).body),
		).forEach { answer ->
			val o = oldSetup()
			o.exact[old] = answer
			o.c.refresh(force = true, manual = true)
			assertEquals("before", o.rows.stored[oldKey]!!.userText)
			assertFalse(o.c.contentPending)
		}
	}

	@Test
	fun `an account switch during the reads shows the new account nothing of them`() {
		var who: String? = "alice"
		lateinit var o: Old
		o = oldSetup(account = { who }) { p -> who = "bob"; o.exact[p] }
		o.c.refresh(force = true, manual = true)
		assertTrue(o.c.rows.isEmpty())
		assertFalse(o.c.contentPending)
		assertTrue(o.rows.stored.keys.none { it.first == "bob" })
		// Alice's own row holds Alice's chain answer, written under her owner key only.
		assertEquals("after", o.rows.stored[oldKey]!!.userText)
	}

	@Test
	fun `a proven edit landing during the reads is not reversed`() {
		lateinit var o: Old
		o = oldSetup { p ->
			if (p == old) {
				o.c.noteEdit("alice", "alice/$old")
				o.rows.reconcile("alice", listOf(o.rows.stored[oldKey]!!.copy(userText = "proven")), 1L)
			}
			o.exact[p]
		}
		o.c.refresh(force = true, manual = true)
		assertEquals("proven", o.rows.stored[oldKey]!!.userText)
	}

	@Test
	fun `a large catalog is checked 40 at a time across pulls and restarts, each row once`() {
		val o = oldSetup(extraOld = 99)
		val all = o.rows.stored.values.filter { it.eventId == null && it.createdAtEpochSec < t0 - 2 * day }.map { it.permlink }
		assertEquals(100, all.size)

		o.c.refresh(force = true, manual = true)
		assertEquals(MySnapsController.CONTENT_CHECKS, o.reads.size)
		assertTrue(o.c.contentPending)

		// Restart: a new controller over the same catalog continues from the stored cursor.
		val reads2 = mutableListOf<String>()
		val again = discovering(o.rows, o.chain) { _, p -> reads2 += p; o.exact[p] }
		again.open(emptyList())
		again.refresh(force = true, manual = true)
		assertEquals(MySnapsController.CONTENT_CHECKS, reads2.size)
		assertTrue(again.contentPending)
		again.refresh(force = true, manual = true)
		assertEquals(100, reads2.size + o.reads.size)
		assertFalse(again.contentPending)

		assertEquals("each row read exactly once in the cycle", all.toSet(), (o.reads + reads2).toSet())
		assertEquals(100, (o.reads + reads2).size)
		assertEquals("after", o.rows.stored[oldKey]!!.userText)
	}

	@Test
	fun `tombstoned rows are never read or resurrected`() {
		val o = oldSetup()
		assertTrue(o.c.recordProvenDeletion("alice", "alice/$old", "tx"))
		o.c.applyDelete("alice", "alice/$old")

		o.c.refresh(force = true, manual = true)

		assertEquals(emptyList<String>(), o.reads)
		assertNull(o.rows.stored[oldKey])
	}

	// ── Stage 47E final corrections (Codex F1/F3) ─────────────────────────

	private val localOld = "rustedwax-snap-${t0 - 11 * day}-local1"
	private val localKey = Triple("alice", "alice", localOld)

	@Test
	fun `F1 an old locally confirmed root edited elsewhere updates on manual pulls and survives reopen and restart`() {
		val rows = Rows()
		val chain = Chain().apply {
			comments["alice"] = deepAccount("before").apply { set(size - 1, chainRoot(localOld, t0 - 11 * day, "before")) }
		}
		// A confirmed record, no History row; its record and cache still say "before".
		val local = record("e1", permlink = localOld, created = t0 - 11 * day, text = "before")
		var answer: org.json.JSONObject? = null
		val reconciled = mutableListOf<String>()
		fun controller() = MySnapsController(
			scope = CoroutineScope(Dispatchers.Unconfined), account = { "alice" }, catalog = { rows },
			confirmedRoots = { listOf(local) }, latestText = { "before" }, io = Dispatchers.Unconfined,
			clock = { 10_000_000L }, discovery = chain.discovery(), readRoot = { _, _ -> answer },
			onReconciled = { _, ids -> reconciled += ids },
		)
		val c = controller()
		c.open(emptyList()); c.refresh(force = true)
		assertEquals("e1", rows.stored[localKey]!!.eventId)

		answer = peakdOld(localOld, t0 - 11 * day, "after")
		repeat(3) {
			c.refresh(force = true, manual = true)
			c.open(emptyList())
		}
		assertEquals("after", rows.stored[localKey]!!.userText)
		assertEquals("record identity kept", "e1", rows.stored[localKey]!!.eventId)
		assertEquals("History's copy is told once", listOf("alice/$localOld"), reconciled)

		val restarted = controller()
		restarted.open(emptyList())
		assertEquals("after", restarted.rows.single { it.permlink == localOld }.userText)
	}

	@Test
	fun `F3 a failed words write stores no cursor, reports failure, and the retry finishes`() {
		val o = oldSetup(extraOld = 44)
		val before = o.rows.checkpoints["alice"]
		o.rows.failWords = true
		o.c.refresh(force = true, manual = true)

		assertEquals("checkpoint and content cursor untouched", before, o.rows.checkpoints["alice"])
		assertTrue(o.c.refreshFailed)
		assertEquals("before", o.rows.stored[oldKey]!!.userText)

		o.rows.failWords = false
		o.reads.clear()
		o.c.refresh(force = true, manual = true)
		assertFalse(o.c.refreshFailed)
		assertTrue(o.c.contentPending)
		assertEquals(MySnapsController.CONTENT_CHECKS, o.reads.size)
		o.c.refresh(force = true, manual = true)
		assertFalse(o.c.contentPending)
		assertTrue(o.rows.stored.values.filter { it.createdAtEpochSec < t0 - 2 * day }.all { it.userText == "after" || it.permlink != old })
		assertEquals("after", o.rows.stored[oldKey]!!.userText)
	}

	@Test
	fun `F3 a single failed words write never reports completion`() {
		val o = oldSetup()
		o.rows.failWords = true
		o.c.refresh(force = true, manual = true)
		assertTrue(o.c.refreshFailed)
		assertFalse("completion is not claimed", !o.c.contentPending && !o.c.refreshFailed)
		assertEquals("before", o.rows.stored[oldKey]!!.userText)
	}

	@Test
	fun `the content cursor survives encoding, and an older checkpoint still decodes`() {
		val cp = CatalogCheckpoint(highWater = 5, contentAt = 7, contentPermlink = "p")
		assertEquals(cp, CatalogCheckpoint.decode(cp.encode()))
		val older = org.json.JSONObject(cp.encode()).apply { remove("contentAt"); remove("contentPermlink") }.toString()
		assertEquals(cp.copy(contentAt = null, contentPermlink = null), CatalogCheckpoint.decode(older))
		val half = org.json.JSONObject(cp.encode()).put("contentPermlink", org.json.JSONObject.NULL).toString()
		assertNull("half a cursor restarts from the top", CatalogCheckpoint.decode(half).contentAt)
	}
}
