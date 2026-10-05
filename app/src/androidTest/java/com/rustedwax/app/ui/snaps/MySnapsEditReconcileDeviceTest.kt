package com.rustedwax.app.ui.snaps

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rustedwax.app.snaps.SnapMedia
import com.rustedwax.app.snaps.SnapPayloadBuilder
import com.rustedwax.app.storage.db.DatabaseQuarantine
import com.rustedwax.app.storage.db.LocalDatabase
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #47 Stage 47E on a real device and real SQLite, isolated: a Snap
 * discovered on Hive — no local publication record — takes its proven edited
 * words on the same catalog row, and a known one edited in another frontend is
 * reconciled without loosening what discovery imports. Each test opens its own
 * database; accounts are synthetic and every Hive answer is a fake.
 */
@RunWith(AndroidJUnit4::class)
class MySnapsEditReconcileDeviceTest {

	private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private lateinit var name: String
	private lateinit var database: LocalDatabase
	private val t0 = 1_791_000_000L
	private val p = "rustedwax-snap-$t0-other1"

	@Before
	fun setUp() {
		name = "rustedwax-47e-${UUID.randomUUID()}.db"
		database = LocalDatabase(context, name)
	}

	@After
	fun tearDown() {
		runCatching { database.close() }
		val file = context.getDatabasePath(name)
		DatabaseQuarantine.existing(file).forEach { it.deleteRecursively() }
		(listOf("") + DatabaseQuarantine.COMPANION_SUFFIXES).forEach { File(file.path + it).delete() }
	}

	private fun root(text: String, app: String = "rustedwax/0.13.0", permlink: String = p, video: String = "dQw4w9WgXcQ") =
		JSONObject()
			.put("author", "alice").put("permlink", permlink)
			.put("parent_author", "peak.snaps").put("parent_permlink", "snap-container-1790947440")
			.put("depth", 1).put("json_metadata", JSONObject().put("app", app))
			.put("body", SnapPayloadBuilder.build(text, SnapMedia(video)).body)
			.put("created", java.time.Instant.ofEpochSecond(t0).toString().removeSuffix("Z"))

	private fun peakd(text: String) = root(text, app = "peakd/2026.7.5")

	private fun stored(rows: SqliteMySnapRows = SqliteMySnapRows(database)) =
		rows.page("alice", 10, null).singleOrNull { it.permlink == p }

	/** Serves [answer] for every page; null makes the node fail. */
	private class Node(var answer: List<JSONObject>?) {
		fun discovery() = MySnapsDiscovery(listOf("n1"), { _, _, after ->
			if (after != null) emptyList() else answer ?: error("offline")
		})
	}

	private fun unconfined(
		rows: SqliteMySnapRows,
		node: Node,
		account: () -> String? = { "alice" },
		read: (String, String) -> JSONObject? = { _, _ -> null },
	) = MySnapsController(
		scope = CoroutineScope(Dispatchers.Unconfined), account = account, catalog = { rows },
		confirmedRoots = { emptyList() }, io = Dispatchers.Unconfined, clock = { (t0 + 1000) * 1000 },
		discovery = node.discovery(), readRoot = read,
	)

	@Test
	fun provenEditOfDiscoveredRootPersistsAcrossRestartAndOffline() {
		val node = Node(listOf(root("before")))
		val c = unconfined(SqliteMySnapRows(database), node)
		c.open(emptyList()); c.refresh(force = true)
		val was = stored()!!
		assertEquals("before", was.userText)
		assertNull("discovered: no local record", was.eventId)

		c.applyEdit("alice", "alice/$p", "after")
		assertEquals("after", c.rows.single().userText)
		assertEquals(was.copy(userText = "after"), stored())

		// Restart: a fresh connection and controller, Hive unreachable.
		database.close()
		database = LocalDatabase(context, name)
		node.answer = null
		val rows = SqliteMySnapRows(database)
		val restarted = unconfined(rows, node)
		restarted.open(emptyList())
		restarted.refresh(force = true)
		assertTrue(restarted.refreshFailed)
		assertEquals("after", restarted.rows.single().userText)
		assertEquals(1, rows.page("alice", 10, null).size)
	}

	@Test
	fun knownRootEditedInPeakdRefreshesByPass() {
		val node = Node(listOf(root("before")))
		val c = unconfined(SqliteMySnapRows(database), node)
		c.open(emptyList()); c.refresh(force = true)

		node.answer = listOf(peakd("after pass"))
		c.refresh(force = true)
		assertEquals("after pass", stored()!!.userText)
		assertEquals("after pass", c.rows.single().userText)
	}

	@Test
	fun reconcileNeverImportsSparesTombstonedAndOtherVideoRowsAndKeepsLocalRecords() {
		val rows = SqliteMySnapRows(database)
		val node = Node(listOf(root("before"), root("unknown", app = "peakd/2026.7.5", permlink = "rustedwax-snap-$t0-newone")))
		val c = unconfined(rows, node)
		c.open(emptyList()); c.refresh(force = true)
		assertEquals("unknown edited object not imported", listOf(p), rows.page("alice", 10, null).map { it.permlink })

		// Another video in the frozen tail: not this Snap's words.
		rows.reconcile("alice", listOf(stored()!!.copy(userText = "x", videoId = "aaaaaaaaaaa")), 1L)
		assertEquals("before", stored()!!.userText)

		// A row with a local record takes accepted words and keeps its record's
		// identity and History context; reopening with older record words keeps them.
		val local = "rustedwax-snap-$t0-local1"
		rows.upsert(listOf(MySnapRow("alice", "alice", local, t0, "e1", "dQw4w9WgXcQ", "Song", null, null, "mine")), 1L)
		assertEquals(listOf("alice/$local"), rows.reconcile("alice", listOf(MySnapRow("alice", "alice", local, t0, null, null, null, null, null, "x")), 1L))
		assertEquals("same words are not a change", emptyList<String>(), rows.reconcile("alice", listOf(MySnapRow("alice", "alice", local, t0, null, null, null, null, null, "x")), 2L))
		rows.upsert(listOf(MySnapRow("alice", "alice", local, t0, "e1", "dQw4w9WgXcQ", null, null, null, "mine")), 3L, keepKnownText = true)
		val kept = rows.page("alice", 10, null).single { it.permlink == local }
		assertEquals("x", kept.userText)
		assertEquals("e1", kept.eventId)
		assertEquals("Song", kept.title)

		// Tombstoned: nothing brings it back.
		assertTrue(c.recordProvenDeletion("alice", "alice/$p", "tx"))
		c.applyDelete("alice", "alice/$p")
		node.answer = listOf(peakd("after"))
		c.applyEdit("alice", "alice/$p", "after")
		c.refresh(force = true)
		assertNull(stored())
	}

	@Test
	fun realDispatchersEditDuringPassIsNotReversed() {
		val rows = SqliteMySnapRows(database)
		val pool = Executors.newFixedThreadPool(3)
		val main = Dispatchers.Main
		val who: String? = "alice"
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		var hold = false
		val disc = MySnapsDiscovery(listOf("n1"), { _, _, after ->
			if (after != null) return@MySnapsDiscovery emptyList()
			if (!hold) return@MySnapsDiscovery listOf(root("before"))
			entered.countDown()
			check(release.await(10, TimeUnit.SECONDS))
			listOf(peakd("older"))
		})
		var clock = (t0 + 1000) * 1000
		val c = MySnapsController(
			scope = CoroutineScope(main), account = { who }, catalog = { rows },
			confirmedRoots = { emptyList() }, io = pool.asCoroutineDispatcher(), clock = { clock }, discovery = disc,
		)
		fun onMain(block: () -> Unit) = runBlocking { withContext(main) { block() } }
		fun waitFor(what: () -> Boolean) {
			val until = System.currentTimeMillis() + 10_000
			while (!what()) {
				check(System.currentTimeMillis() < until) { "condition never held" }
				Thread.sleep(10)
			}
		}
		onMain { c.open(emptyList()); c.refresh(force = true) }
		waitFor { stored()?.userText == "before" && runBlocking { withContext(main) { !c.refreshing } } }

		// A pass reads "older" words while the editor proves "after".
		hold = true
		onMain { c.refresh(force = true) }
		check(entered.await(10, TimeUnit.SECONDS))
		clock += 1
		onMain { c.applyEdit("alice", "alice/$p", "after") }
		waitFor { stored()?.userText == "after" }
		release.countDown()
		waitFor { runBlocking { withContext(main) { !c.refreshing } } }
		Thread.sleep(200)
		assertEquals("the older pass did not reverse the proven edit", "after", stored()?.userText)
		pool.shutdown()
	}

	// ── Stage 47E follow-up: manual refresh reaches older known roots ─────

	private val day = 24 * 60 * 60L

	private fun filler(i: Int, created: Long) = JSONObject()
		.put("author", "alice").put("permlink", "re-someone-$i")
		.put("parent_author", "someone").put("parent_permlink", "post").put("depth", 2)
		.put("json_metadata", JSONObject().put("app", "peakd/2026.7.5"))
		.put("body", "a reply").put("created", java.time.Instant.ofEpochSecond(created).toString().removeSuffix("Z"))

	private fun oldRoot(permlink: String, created: Long, text: String, app: String) = JSONObject()
		.put("author", "alice").put("permlink", permlink)
		.put("parent_author", "peak.snaps").put("parent_permlink", "snap-container-1790947440")
		.put("depth", 1).put("json_metadata", JSONObject().put("app", app))
		.put("body", SnapPayloadBuilder.build(text, SnapMedia("dQw4w9WgXcQ")).body)
		.put("created", java.time.Instant.ofEpochSecond(created).toString().removeSuffix("Z"))

	@Test
	fun manualRefreshReconcilesOldKnownRootsAcrossRestartOnRealSqlite() {
		val olds = (1..45).map { "rustedwax-snap-${t0 - 11 * day - it}-o${"%05d".format(it)}" }
		val chain = mutableListOf<JSONObject>().apply {
			(0 until 20).forEach { add(filler(it, t0 + it)) }
			(20 until 40).forEach { add(filler(it, t0 - day - it)) }
			olds.forEachIndexed { i, p -> add(oldRoot(p, t0 - 11 * day - i - 1, "before", "rustedwax/0.13.0")) }
		}
		val disc = MySnapsDiscovery(listOf("n1"), { _, _, after ->
			val start = after?.let { a -> chain.indexOfFirst { it.getString("permlink") == a.permlink } } ?: 0
			chain.drop(start).take(MySnapsDiscovery.PAGE_SIZE)
		})
		val reads = mutableListOf<String>()
		fun controller(rows: SqliteMySnapRows) = MySnapsController(
			scope = CoroutineScope(Dispatchers.Unconfined), account = { "alice" }, catalog = { rows },
			confirmedRoots = { emptyList() }, io = Dispatchers.Unconfined, clock = { (t0 + 1000) * 1000 },
			discovery = disc,
			readRoot = { _, p -> reads += p; oldRoot(p, t0 - 11 * day - olds.indexOf(p) - 1, "after", "peakd/2026.7.5") },
		)
		val c = controller(SqliteMySnapRows(database))
		c.open(emptyList()); c.refresh(force = true)
		assertEquals(45, SqliteMySnapRows(database).page("alice", 100, null).count { it.userText == "before" })

		c.refresh(force = true)
		assertEquals("an ordinary forced pass reads no content", 0, reads.size)

		c.refresh(force = true, manual = true)
		assertEquals(MySnapsController.CONTENT_CHECKS, reads.size)
		assertTrue(c.contentPending)

		// Restart mid-cycle: the cursor is in the real checkpoint row.
		database.close()
		database = LocalDatabase(context, name)
		val restarted = controller(SqliteMySnapRows(database))
		restarted.open(emptyList())
		restarted.refresh(force = true, manual = true)
		assertEquals(45, reads.size)
		assertEquals("each row read once", 45, reads.toSet().size)
		assertTrue(!restarted.contentPending)
		val rows = SqliteMySnapRows(database).page("alice", 100, null)
		assertEquals(45, rows.size)
		assertTrue(rows.all { it.userText == "after" && it.eventId == null && it.videoId == "dQw4w9WgXcQ" })
	}

	@Test
	fun oldLocalRootManualPullSurvivesBackfillAndRestartOnRealSqlite() {
		val local = "rustedwax-snap-${t0 - 11 * day}-local1"
		val chain = mutableListOf<JSONObject>().apply {
			(0 until 20).forEach { add(filler(it, t0 + it)) }
			(20 until 40).forEach { add(filler(it, t0 - day - it)) }
			add(oldRoot(local, t0 - 11 * day, "before", "rustedwax/0.13.0"))
		}
		val disc = MySnapsDiscovery(listOf("n1"), { _, _, after ->
			val start = after?.let { a -> chain.indexOfFirst { it.getString("permlink") == a.permlink } } ?: 0
			chain.drop(start).take(MySnapsDiscovery.PAGE_SIZE)
		})
		val record = com.rustedwax.app.snaps.PendingSnap(
			account = "alice", eventId = "e-local", author = "alice", permlink = local,
			parentAuthor = "peak.snaps", parentPermlink = "snap-container-1790947440",
			body = SnapPayloadBuilder.build("before", SnapMedia("dQw4w9WgXcQ")).body, jsonMetadata = "{}",
			signedTransactionJson = "{}", txId = "tx", expirationEpochSec = 1L,
			state = com.rustedwax.app.snaps.PendingSnapState.CONFIRMED,
			createdAtEpochSec = t0 - 11 * day, updatedAtEpochSec = t0 - 11 * day,
			kind = com.rustedwax.app.snaps.PendingSnapKind.ROOT,
		)
		var answer: JSONObject? = null
		val told = mutableListOf<String>()
		fun controller(rows: SqliteMySnapRows) = MySnapsController(
			scope = CoroutineScope(Dispatchers.Unconfined), account = { "alice" }, catalog = { rows },
			confirmedRoots = { listOf(record) }, latestText = { "before" }, io = Dispatchers.Unconfined,
			clock = { (t0 + 1000) * 1000 }, discovery = disc, readRoot = { _, _ -> answer },
			onReconciled = { _, ids -> told += ids },
		)
		val c = controller(SqliteMySnapRows(database))
		c.open(emptyList()); c.refresh(force = true)
		assertEquals("e-local", stored(SqliteMySnapRows(database)).let { SqliteMySnapRows(database).page("alice", 10, null).single().eventId })

		answer = oldRoot(local, t0 - 11 * day, "after", "peakd/2026.7.5")
		c.refresh(force = true, manual = true)
		c.open(emptyList())
		assertEquals(listOf("alice/$local"), told)

		database.close()
		database = LocalDatabase(context, name)
		val restarted = controller(SqliteMySnapRows(database))
		restarted.open(emptyList())
		val row = SqliteMySnapRows(database).page("alice", 10, null).single()
		assertEquals("after", row.userText)
		assertEquals("e-local", row.eventId)
		assertEquals("after", restarted.rows.single().userText)
	}
}
