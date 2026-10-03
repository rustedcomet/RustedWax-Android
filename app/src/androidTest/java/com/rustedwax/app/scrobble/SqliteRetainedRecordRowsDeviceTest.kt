package com.rustedwax.app.scrobble

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rustedwax.app.storage.db.DatabaseQuarantine
import com.rustedwax.app.storage.db.LegacyImportState
import com.rustedwax.app.storage.db.LocalDatabase
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #41 Slice 2: the History and Not Logged adapter against real SQLite.
 *
 * The JVM suite proves the import and save rules against an in-memory
 * implementation; this proves SQLite gives that implementation's semantics —
 * conflicts ignored, updates in place, queue operations unique per owner,
 * newest-first order, per-owner pruning, and rollback.
 *
 * Every test opens its own uniquely named database and never touches
 * `rustedwax.db` or any SharedPreferences store.
 */
@RunWith(AndroidJUnit4::class)
class SqliteRetainedRecordRowsDeviceTest {

	private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private lateinit var name: String
	private lateinit var database: LocalDatabase
	private lateinit var rows: SqliteRetainedRecordRows

	@Before
	fun setUp() {
		name = "rustedwax-slice2-${UUID.randomUUID()}.db"
		database = LocalDatabase(context, name)
		rows = SqliteRetainedRecordRows(database)
	}

	@After
	fun tearDown() {
		runCatching { database.close() }
		val file = context.getDatabasePath(name)
		DatabaseQuarantine.existing(file).forEach { it.deleteRecursively() }
		(listOf("") + DatabaseQuarantine.COMPANION_SUFFIXES).forEach { File(file.path + it).delete() }
	}

	private fun reopen() {
		database.close()
		database = LocalDatabase(context, name)
		rows = SqliteRetainedRecordRows(database)
	}

	private class Blobs(var history: String? = null, var skipped: String? = null) : LegacyRetainedBlobs {
		override fun readHistory() = LegacyBlob(history, 0)
		override fun readSkipped() = LegacyBlob(skipped, 0)
		override fun writeHistory(blob: String, generation: Long) { history = blob }
		override fun writeSkipped(blob: String, generation: Long) { skipped = blob }
	}

	private fun history(account: String, eventId: String, at: Long = 1_700_000_000L, op: String? = null, status: String = "confirmed") =
		FinalizationRuntime.ScrobbleRecord(
			title = "Title $eventId",
			artist = "Artist",
			percentPlayed = 90,
			atEpochSec = at,
			status = status,
			txId = "tx-$eventId",
			queued = false,
			videoId = "dQw4w9WgXcQ",
			eventId = eventId,
			account = account,
			queueOperationId = op,
		)

	private fun skip(account: String?, id: String, at: Long = 1_700_000_000L) =
		FinalizationRuntime.SkipRecord("Skip $id", null, "below threshold", at, 5, null, null, account, rowId = id)

	@Test
	fun historyRoundTripsEveryField() {
		val row = history("alice", "e-1", op = "op-1", status = "queued — offline").copy(queued = true, txId = null, artist = null)
		rows.transaction { assertTrue(it.insertHistoryIfAbsent(row)) }
		reopen()
		assertEquals(row, rows.transaction { it.historyRow("e-1") })
	}

	@Test
	fun insertIfAbsentNeverOverwrites() {
		rows.transaction {
			assertTrue(it.insertHistoryIfAbsent(history("alice", "e-1", status = "newer")))
			assertFalse(it.insertHistoryIfAbsent(history("alice", "e-1", status = "older")))
			assertTrue(it.insertHistoryIfAbsent(history("alice", "q-1", op = "op")))
			assertFalse("same owner's queue operation", it.insertHistoryIfAbsent(history("alice", "q-2", op = "op")))
			assertTrue("another owner's operation", it.insertHistoryIfAbsent(history("bob", "q-3", op = "op")))
			assertFalse("History never stores the signed-out owner", it.insertHistoryIfAbsent(history("", "x")))
		}
		assertEquals("newer", rows.transaction { it.historyRow("e-1") }!!.status)
	}

	@Test
	fun writeUpdatesInPlaceAndReplacesAStaleRowForTheSameOperation() {
		rows.transaction {
			it.writeHistory(history("alice", "q-1", op = "op", status = "queued — offline"))
			it.writeHistory(history("alice", "q-1", op = "op", status = "sent from queue"))
		}
		assertEquals(listOf("sent from queue"), rows.transaction { it.history("alice", 50) }.map { it.status })

		rows.transaction { it.writeHistory(history("alice", "q-2", op = "op", status = "failed after 8 queued attempts")) }
		assertEquals(listOf("q-2"), rows.transaction { it.history("alice", 50) }.map { it.eventId })
	}

	@Test
	fun ownersAreStoredLowercaseAndSignedOutRefusalsComeBackAsNull() {
		rows.transaction {
			it.writeHistory(history("Alice", "e-1"))
			it.writeSkipped(skip(null, "s-1"))
			it.writeSkipped(skip("Alice", "s-2"))
		}
		reopen()
		rows.transaction { tx ->
			assertEquals(listOf("alice"), tx.historyOwners())
			assertEquals("alice", tx.historyRow("e-1")!!.account)
			assertEquals(setOf("-", "alice"), tx.skippedOwners().toSet())
			assertNull(tx.skippedRow("s-1")!!.account)
			assertEquals("alice", tx.skippedRow("s-2")!!.account)
		}
	}

	@Test
	fun newestFirstAndPrunedPerOwnerOnly() {
		rows.transaction { tx ->
			(1..10).forEach { tx.writeHistory(history("alice", "a-$it", at = it.toLong())) }
			(1..60).forEach { tx.writeHistory(history("bob", "b-$it", at = 100L + it)) }
			// Same second: the later write comes first.
			tx.writeHistory(history("carol", "c-1", at = 5))
			tx.writeHistory(history("carol", "c-2", at = 5))
			tx.pruneHistory("bob", 50)
		}
		rows.transaction { tx ->
			assertEquals((10 downTo 1).map { "a-$it" }, tx.history("alice", 50).map { it.eventId })
			assertEquals((60 downTo 11).map { "b-$it" }, tx.history("bob", 100).map { it.eventId })
			assertEquals(listOf("c-2", "c-1"), tx.history("carol", 50).map { it.eventId })
		}
	}

	@Test
	fun aFailedTransactionRollsEverythingBack() {
		try {
			rows.transaction { tx ->
				tx.writeHistory(history("alice", "e-1"))
				tx.writeSkipped(skip(null, "s-1"))
				error("interrupted")
			}
			fail("the failure should propagate")
		} catch (expected: IllegalStateException) {
		}
		rows.transaction { tx ->
			assertNull(tx.historyRow("e-1"))
			assertNull(tx.skippedRow("s-1"))
		}
	}

	@Test
	fun migrationIsVerifiedIdempotentAndUnionsChanges() {
		val blobs = Blobs(
			history = RetainedRecordCodec.encodeHistory(listOf(history("alice", "a-2", at = 2), history("alice", "a-1", at = 1))),
			skipped = """[{"title":"t","artist":null,"reason":"r","at":1,"played":3,"duration":null,""" +
				""""videoId":null,"account":null},{"title":"t","artist":null,"reason":"r","at":1,""" +
				""""played":3,"duration":null,"videoId":null,"account":null}]""",
		)

		val first = DatabaseRetainedRecords.open(rows, blobs, { it.run() }, { 1L })
		assertEquals(listOf("a-2", "a-1"), first.loadHistory().map { it.eventId })
		assertEquals("identical twins stay two rows", 2, first.loadSkipped().map { it.rowId }.toSet().size)
		rows.transaction { tx ->
			assertEquals(LegacyImportState.VERIFIED, tx.importRecord(RetainedRecordImport.HISTORY_SOURCE)!!.state)
			assertEquals(LegacyImportState.VERIFIED, tx.importRecord(RetainedRecordImport.SKIPPED_SOURCE)!!.state)
		}

		reopen()
		val again = DatabaseRetainedRecords.open(rows, blobs, { it.run() }, { 2L })
		assertEquals(first.loadHistory(), again.loadHistory())
		assertEquals(first.loadSkipped(), again.loadSkipped())

		blobs.history = RetainedRecordCodec.encodeHistory(listOf(history("alice", "a-3", at = 3)) + first.loadHistory())
		val unioned = DatabaseRetainedRecords.open(rows, blobs, { it.run() }, { 3L })
		assertEquals(listOf("a-3", "a-2", "a-1"), unioned.loadHistory().map { it.eventId })
	}

	@Test
	fun anUnreadableBlobIsRecordedAndLeftAlone() {
		val blobs = Blobs(history = "{damaged")
		DatabaseRetainedRecords.open(rows, blobs, { it.run() }, { 1L })
		rows.transaction { tx ->
			val record = tx.importRecord(RetainedRecordImport.HISTORY_SOURCE)!!
			assertEquals(LegacyImportState.UNREADABLE, record.state)
			assertEquals(RetainedRecordCodec.sha256("{damaged"), record.fingerprint)
			assertEquals(0L, record.generation)
		}
		assertEquals("{damaged", blobs.history)
	}
}
