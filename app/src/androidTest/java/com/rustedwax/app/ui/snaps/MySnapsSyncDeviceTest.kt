package com.rustedwax.app.ui.snaps

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rustedwax.app.snaps.PendingSnap
import com.rustedwax.app.snaps.PendingSnapKind
import com.rustedwax.app.snaps.PendingSnapState
import com.rustedwax.app.snaps.SnapMedia
import com.rustedwax.app.snaps.SnapPayloadBuilder
import com.rustedwax.app.storage.db.DatabaseQuarantine
import com.rustedwax.app.storage.db.LocalDatabase
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 47B corrections on a real device and real SQLite, isolated: each test
 * opens its own uniquely named database, uses synthetic accounts and fake Hive
 * readers, and never touches the app's own database or the network.
 */
@RunWith(AndroidJUnit4::class)
class MySnapsSyncDeviceTest {

	private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private lateinit var name: String
	private lateinit var database: LocalDatabase
	private val t0 = 1_791_000_000L

	@Before
	fun setUp() {
		name = "rustedwax-47b-sync-${UUID.randomUUID()}.db"
		database = LocalDatabase(context, name)
	}

	@After
	fun tearDown() {
		runCatching { database.close() }
		val file = context.getDatabasePath(name)
		DatabaseQuarantine.existing(file).forEach { it.deleteRecursively() }
		(listOf("") + DatabaseQuarantine.COMPANION_SUFFIXES).forEach { File(file.path + it).delete() }
	}

	private fun root(permlink: String, created: Long, text: String, account: String = "alice") = JSONObject()
		.put("author", account).put("permlink", permlink)
		.put("parent_author", "peak.snaps").put("parent_permlink", "snap-container-1790947440")
		.put("depth", 1).put("json_metadata", JSONObject().put("app", "rustedwax/0.13.0"))
		.put("body", SnapPayloadBuilder.build(text, SnapMedia("dQw4w9WgXcQ")).body)
		.put("created", java.time.Instant.ofEpochSecond(created).toString().removeSuffix("Z"))

	private fun record(permlink: String, created: Long, text: String = "words", account: String = "alice") = PendingSnap(
		account = account, eventId = "e-$permlink", author = account, permlink = permlink,
		parentAuthor = "peak.snaps", parentPermlink = "snap-container-1790947440",
		body = SnapPayloadBuilder.build(text, SnapMedia("dQw4w9WgXcQ")).body, jsonMetadata = "{}",
		signedTransactionJson = "{}", txId = "tx", expirationEpochSec = 1L, state = PendingSnapState.CONFIRMED,
		createdAtEpochSec = created, updatedAtEpochSec = created, kind = PendingSnapKind.ROOT,
	)

	@Test
	fun aToBToANeverOverlapsOneAccountsPassesOnRealSqlite() {
		val rows = SqliteMySnapRows(database)
		var who = "alice"
		val aliceCalls = AtomicInteger()
		val firstEntered = CountDownLatch(1)
		val bobEntered = CountDownLatch(1)
		val releaseFirst = CountDownLatch(1)
		val releaseBob = CountDownLatch(1)
		val pool = Executors.newFixedThreadPool(3)
		val p = "rustedwax-snap-$t0-race01"
		val disc = MySnapsDiscovery(listOf("n1"), { _, account, _ ->
			if (account == "alice") {
				if (aliceCalls.incrementAndGet() == 1) {
					firstEntered.countDown()
					check(releaseFirst.await(10, TimeUnit.SECONDS))
					listOf(root(p, t0, "older answer"))
				} else listOf(root(p, t0 + 100, "newer answer"))
			} else {
				bobEntered.countDown()
				check(releaseBob.await(10, TimeUnit.SECONDS))
				emptyList()
			}
		})
		val main = Dispatchers.Main
		val c = MySnapsController(
			scope = CoroutineScope(main), account = { who }, catalog = { rows },
			confirmedRoots = { emptyList() }, io = pool.asCoroutineDispatcher(),
			clock = { (t0 + 1000) * 1000 }, discovery = disc,
		)
		fun onMain(block: () -> Unit) = runBlocking { withContext(main) { block() } }
		fun waitFor(what: () -> Boolean) {
			val until = System.currentTimeMillis() + 10_000
			while (runBlocking { withContext(main) { !what() } }) {
				check(System.currentTimeMillis() < until) { "condition never held" }
				Thread.sleep(10)
			}
		}
		try {
			onMain { c.open(emptyList()) }
			waitFor { c.ready }
			onMain { c.refresh(force = true) }
			assertTrue(firstEntered.await(10, TimeUnit.SECONDS))
			onMain { who = "bob"; c.refresh(force = true) }
			assertTrue(bobEntered.await(10, TimeUnit.SECONDS))
			onMain { who = "alice"; c.refresh(force = true) }
			Thread.sleep(200)
			assertEquals("the second Alice refresh was refused", 1, aliceCalls.get())
			releaseFirst.countDown()
			waitFor { !c.refreshing && c.rows.singleOrNull()?.userText == "older answer" }
			val first = CatalogCheckpoint.decode(rows.checkpoint("alice")).highWater!!
			onMain { c.refresh(force = true) }
			waitFor { !c.refreshing && c.rows.singleOrNull()?.userText == "newer answer" }
			val second = CatalogCheckpoint.decode(rows.checkpoint("alice")).highWater!!
			assertTrue(second >= first)
			assertEquals(t0 + 100, second)
			assertEquals("newer answer", rows.page("alice", 10, null).single().userText)
		} finally {
			releaseFirst.countDown(); releaseBob.countDown(); pool.shutdownNow()
		}
	}

	@Test
	fun persistentInSpanOmissionsCannotStarveOlderRowsOnRealSqlite() {
		val rows = SqliteMySnapRows(database)
		val listed = listOf(root("rustedwax-snap-${t0 + 900000}-top001", t0 + 900000, "top"), root("rustedwax-snap-$t0-bottom", t0, "bottom"))
		val recent = (1..12).map { i -> record("rustedwax-snap-${t0 + i * 10000}-r${i.toString().padStart(5, '0')}", t0 + i * 10000) }
		val old = (1..8).map { i -> record("rustedwax-snap-${t0 - i * 10000}-o${i.toString().padStart(5, '0')}", t0 - i * 10000) }
		val gone = "alice/${old[6].permlink}"
		val checks = mutableListOf<String>()
		val c = MySnapsController(
			scope = CoroutineScope(Dispatchers.Unconfined), account = { "alice" }, catalog = { rows },
			confirmedRoots = { recent + old }, io = Dispatchers.Unconfined, clock = { (t0 + 1000000) * 1000 },
			discovery = MySnapsDiscovery(listOf("n1"), { _, _, after -> if (after == null) listed else emptyList() }),
			provenAbsent = { a, p -> checks += "$a/$p"; "$a/$p" == gone },
		)
		c.open(emptyList())
		repeat(30) { c.refresh(force = true) }
		val oldReads = checks.count { id -> old.any { "alice/${it.permlink}" == id } }
		assertTrue("older rows read: $oldReads", oldReads >= old.size)
		assertEquals(listOf(old[6].permlink), rows.page("alice", 100, null).map { it.permlink }.let { live ->
			(recent + old).map { it.permlink }.filter { it !in live }
		})
		assertEquals("twelve unproven omissions kept", 12, rows.page("alice", 100, null).count { it.permlink.contains("-r0") })
	}

	// ── Final correction: a proven local edit during exact verification ──────

	@Test
	fun aLocalEditProvenDuringExactVerificationSurvivesDiscoveryAndRestart() {
		var rows = SqliteMySnapRows(database)
		val p = "rustedwax-snap-$t0-edit01"
		// The Snap as published, and one older catalog row the rotation will exact-read.
		var editRecord = record(p, t0, "before")
		val older = record("rustedwax-snap-${t0 - 50_000}-older1", t0 - 50_000)
		val inRead = CountDownLatch(1)
		val releaseRead = CountDownLatch(1)
		val pool = Executors.newFixedThreadPool(2)
		val main = Dispatchers.Main
		val clockMs = { System.currentTimeMillis() }
		val c = MySnapsController(
			scope = CoroutineScope(main), account = { "alice" }, catalog = { rows },
			confirmedRoots = { listOf(editRecord, older) }, io = pool.asCoroutineDispatcher(), clock = clockMs,
			// Hive still holds the words from before the edit.
			discovery = MySnapsDiscovery(listOf("n1"), { _, _, after -> if (after == null) listOf(root(p, t0, "before")) else emptyList() }),
			provenAbsent = { _, _ ->
				inRead.countDown()
				check(releaseRead.await(15, TimeUnit.SECONDS))
				false
			},
		)
		fun onMain(block: () -> Unit) = runBlocking { withContext(main) { block() } }
		fun waitFor(what: () -> Boolean) {
			val until = System.currentTimeMillis() + 10_000
			while (runBlocking { withContext(main) { !what() } }) {
				check(System.currentTimeMillis() < until) { "condition never held" }
				Thread.sleep(10)
			}
		}
		fun stored() = rows.page("alice", 10, null).single { it.permlink == p }
		try {
			// 1. Indexed with "before".
			onMain { c.open(emptyList()) }
			waitFor { c.ready && c.rows.size == 2 }
			assertEquals("before", stored().userText)
			val video = stored().videoId
			// 2–3. Discovery captures "before" from Hive and blocks inside the exact read.
			onMain { c.refresh(force = true) }
			assertTrue(inRead.await(10, TimeUnit.SECONDS))
			// 4–5. The proven edit, through the production callback path (since
			// Stage 47E mySnaps.applyEdit): the confirmed record now holds "after",
			// the edit is stamped and its words stored on the row.
			editRecord = record(p, t0, "after")
			onMain { c.applyEdit("alice", "alice/$p", "after") }
			waitFor { stored().userText == "after" }
			// 6. Release the blocked exact read; discovery then commits.
			releaseRead.countDown()
			waitFor { !c.refreshing }
			// 7–8. Discovery could not write "before" back.
			val row = stored()
			assertEquals("after", row.userText)
			assertEquals("alice", row.author)
			assertEquals(video, row.videoId)
			assertEquals("e-$p", row.eventId)
			assertEquals(1, rows.page("alice", 10, null).count { it.permlink == p })
			val cp = CatalogCheckpoint.decode(rows.checkpoint("alice"))
			assertTrue("checkpoint written", cp.lastSuccessMs != null && cp.deepDone)
			assertEquals(t0, cp.highWater)
			// 9. Restart: a new connection and a new controller still see "after".
			database.close()
			database = LocalDatabase(context, name)
			rows = SqliteMySnapRows(database)
			assertEquals("after", stored().userText)
			val restarted = MySnapsController(
				scope = CoroutineScope(main), account = { "alice" }, catalog = { rows },
				confirmedRoots = { listOf(editRecord, older) }, io = pool.asCoroutineDispatcher(), clock = clockMs,
			)
			onMain { restarted.open(emptyList()) }
			waitFor { restarted.ready && restarted.rows.size == 2 }
			assertEquals("after", restarted.rows.single { it.permlink == p }.userText)
		} finally {
			releaseRead.countDown(); pool.shutdownNow()
		}
	}

	// ── The original corrections, on real SQLite ─────────────────────────────

	@Test
	fun independentAccountsRefreshAndEachReleasesOnlyItsOwnClaim() {
		val rows = SqliteMySnapRows(database)
		var who = "alice"
		val aliceIn = CountDownLatch(1); val bobIn = CountDownLatch(1); val release = CountDownLatch(1)
		val pool = Executors.newFixedThreadPool(3)
		val main = Dispatchers.Main
		val disc = MySnapsDiscovery(listOf("n1"), { _, account, after ->
			if (after != null) return@MySnapsDiscovery emptyList()
			if (account == "alice") aliceIn.countDown() else bobIn.countDown()
			check(release.await(10, TimeUnit.SECONDS))
			listOf(root("rustedwax-snap-$t0-${account.take(5).padEnd(6, '0')}", t0, "from $account", account))
		})
		val c = MySnapsController(
			scope = CoroutineScope(main), account = { who }, catalog = { rows },
			confirmedRoots = { emptyList() }, io = pool.asCoroutineDispatcher(), clock = { (t0 + 10) * 1000 }, discovery = disc,
		)
		fun onMain(block: () -> Unit) = runBlocking { withContext(main) { block() } }
		try {
			onMain { c.refresh(force = true) }
			assertTrue(aliceIn.await(10, TimeUnit.SECONDS))
			onMain { who = "bob"; c.refresh(force = true) }
			assertTrue("Bob is not blocked by Alice", bobIn.await(10, TimeUnit.SECONDS))
			release.countDown()
			val until = System.currentTimeMillis() + 10_000
			while (rows.checkpoint("alice") == null || rows.checkpoint("bob") == null) {
				check(System.currentTimeMillis() < until); Thread.sleep(10)
			}
			Thread.sleep(100)
			assertEquals(false, runBlocking { withContext(main) { c.refreshing } })
			onMain { who = "alice" }
			assertEquals(false, runBlocking { withContext(main) { c.refreshing } })
			assertEquals(listOf("from alice"), rows.page("alice", 10, null).map { it.userText })
			assertEquals(listOf("from bob"), rows.page("bob", 10, null).map { it.userText })
		} finally {
			release.countDown(); pool.shutdownNow()
		}
	}

	@Test
	fun undecidedReadsHoldTheCursorAndOnlyProofTombstones() {
		val rows = SqliteMySnapRows(database)
		val old = (1..5).map { i -> record("rustedwax-snap-${t0 - i * 10_000}-old00$i", t0 - i * 10_000) }
		val listed = listOf(root("rustedwax-snap-${t0 + 900_000}-top001", t0 + 900_000, "top"))
		var answers: (String) -> Boolean? = { null }
		val asked = mutableListOf<String>()
		fun controller() = MySnapsController(
			scope = CoroutineScope(Dispatchers.Unconfined), account = { "alice" }, catalog = { rows },
			confirmedRoots = { old }, io = Dispatchers.Unconfined, clock = { (t0 + 1_000_000) * 1000 },
			discovery = MySnapsDiscovery(listOf("n1"), { _, _, after -> if (after == null) listed else emptyList() }),
			provenAbsent = { a, p -> asked += "$a/$p"; answers("$a/$p") },
		)
		// Every exact read undecided: nothing is tombstoned and the cursor stays put.
		controller().apply { open(emptyList()); refresh(force = true) }
		var cp = CatalogCheckpoint.decode(rows.checkpoint("alice"))
		assertEquals(null, cp.verifyPermlink)
		assertEquals(6, rows.page("alice", 10, null).size)
		// The second row is undecided: the cursor stops after the first.
		val second = "alice/${old[1].permlink}"
		answers = { id -> if (id == second) null else false }
		asked.clear()
		controller().apply { open(emptyList()); refresh(force = true) }
		cp = CatalogCheckpoint.decode(rows.checkpoint("alice"))
		assertEquals(old[0].permlink, cp.verifyPermlink)
		// Now the second is proven gone: only it is tombstoned, and only now.
		answers = { id -> id == second }
		asked.clear()
		controller().apply { open(emptyList()); refresh(force = true) }
		assertEquals("read first after the hold", second, asked.first())
		val live = rows.page("alice", 10, null).map { it.permlink }
		assertTrue(old[1].permlink !in live)
		assertEquals(5, live.size)
	}

	@Test
	fun aFailedPassKeepsTheCatalogAndCheckpointExactlyAsTheyWere() {
		val rows = SqliteMySnapRows(database)
		var down = false
		val listed = listOf(root("rustedwax-snap-$t0-keep01", t0, "kept words"))
		val c = MySnapsController(
			scope = CoroutineScope(Dispatchers.Unconfined), account = { "alice" }, catalog = { rows },
			confirmedRoots = { emptyList() }, io = Dispatchers.Unconfined, clock = { (t0 + 10) * 1000 },
			discovery = MySnapsDiscovery(listOf("n1", "n2"), { _, _, after -> if (down) error("offline"); if (after == null) listed else emptyList() }),
			provenAbsent = { _, _ -> true },
		)
		c.open(emptyList()); c.refresh(force = true)
		val before = rows.page("alice", 10, null)
		val cpBefore = rows.checkpoint("alice")
		down = true
		c.refresh(force = true)
		assertTrue(c.refreshFailed)
		assertEquals(before, rows.page("alice", 10, null))
		assertEquals(cpBefore, rows.checkpoint("alice"))
	}
}
