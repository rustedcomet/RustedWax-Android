package com.rustedwax.app.scrobble

import com.rustedwax.app.storage.db.LegacyImportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Issue #41: importing the legacy History and Not Logged blobs.
 *
 * Every blob here is produced by, or written in the exact shape of, the
 * production codec, so these are the bytes a real install holds.
 */
class RetainedRecordImportTest {

	private val rows = InMemoryRetainedRecordRows()

	private fun history(account: String, eventId: String, at: Long = 1_700_000_000L, op: String? = null, status: String = "confirmed in block") =
		FinalizationRuntime.ScrobbleRecord(
			title = "Title $eventId",
			artist = "Artist",
			percentPlayed = 91,
			atEpochSec = at,
			status = status,
			txId = "tx-$eventId",
			queued = op != null,
			videoId = "dQw4w9WgXcQ",
			eventId = eventId,
			account = account,
			queueOperationId = op,
		)

	/** A Not Logged entry exactly as a pre-#41 build wrote it: no row id. */
	private fun legacySkipJson(title: String, account: String?, at: Long = 1_700_000_000L) =
		"""{"title":"$title","artist":null,"reason":"below threshold","at":$at,"played":12,""" +
			""""duration":100,"videoId":null,"account":${account?.let { "\"$it\"" } ?: "null"}}"""

	private fun legacySkips(vararg entries: String) = entries.joinToString(",", "[", "]")

	private fun importHistory(blob: String?, generation: Long = 0) =
		RetainedRecordImport.importHistory(rows, LegacyBlob(blob, generation), 1_000L)

	private fun importSkipped(blob: String?, generation: Long = 0) =
		RetainedRecordImport.importSkipped(rows, LegacyBlob(blob, generation), 1_000L)

	@Test
	fun `first import stores every row with its identity, owner, status and queue state`() {
		val legacy = listOf(
			history("alice", "a-2", at = 20, op = "op-1", status = "queued — offline"),
			history("alice", "a-1", at = 10),
			history("bob", "b-1", at = 15),
		)
		val blob = RetainedRecordCodec.encodeHistory(legacy)

		val result = importHistory(blob)

		assertEquals(RetainedRecordImport.Result.Imported(written = 3, verified = true), result)
		assertEquals(legacy.toSet(), rows.allHistory.toSet())
		val record = rows.import(RetainedRecordImport.HISTORY_SOURCE)!!
		assertEquals(LegacyImportState.VERIFIED, record.state)
		assertEquals(RetainedRecordCodec.sha256(blob), record.fingerprint)
		assertEquals(RetainedRecordImport.PARSER_VERSION, record.parserVersion)
		assertEquals(3, record.seen)
		assertEquals(3, record.imported)
		assertEquals(0, record.rejected)
	}

	@Test
	fun `on this build a History row's database id is its event id, kept in its own column`() {
		val row = history("alice", "event-7")
		importHistory(RetainedRecordCodec.encodeHistory(listOf(row)))

		assertEquals("event-7", RetainedOwners.rowId(row))
		assertEquals(row, rows.allHistory.single())
	}

	@Test
	fun `importing the same bytes again is a no-op`() {
		val blob = RetainedRecordCodec.encodeHistory(listOf(history("alice", "a-1"), history("alice", "a-2")))
		importHistory(blob)
		val before = rows.allHistory

		assertEquals(RetainedRecordImport.Result.Unchanged(LegacyImportState.VERIFIED), importHistory(blob))
		assertEquals(before, rows.allHistory)
	}

	@Test
	fun `a changed blob is unioned in without duplicating or overwriting stored rows`() {
		importHistory(
			RetainedRecordCodec.encodeHistory(
				listOf(
					history("alice", "a-1", op = "op", status = "sent from queue"),
					history("alice", "a-0", at = 1_699_999_000L, status = "confirmed in block"),
				),
			),
		)
		// An older blob still holds earlier states of the same rows; the stored
		// state wins, whether or not the row came from the queue.
		val changed = RetainedRecordCodec.encodeHistory(
			listOf(
				history("alice", "a-2", at = 1_700_000_100L),
				history("alice", "a-1", op = "op", status = "queued — offline"),
				history("alice", "a-0", at = 1_699_999_000L, status = "accepted — confirmation unavailable"),
			),
		)

		val result = importHistory(changed)

		assertEquals(RetainedRecordImport.Result.Imported(written = 1, verified = true), result)
		assertEquals(listOf("a-2", "a-1", "a-0"), rows.allHistory.map { it.eventId })
		assertEquals("sent from queue", rows.allHistory[1].status)
		assertEquals("confirmed in block", rows.allHistory[2].status)
	}

	@Test
	fun `an interrupted import leaves nothing behind and the next run completes`() {
		val blob = RetainedRecordCodec.encodeHistory((1..5).map { history("alice", "a-$it", at = it.toLong()) })
		rows.failAfterWrites = 3

		try {
			importHistory(blob)
			fail("the simulated interruption should propagate")
		} catch (expected: IllegalStateException) {
		}
		assertTrue(rows.allHistory.isEmpty())
		assertNull(rows.import(RetainedRecordImport.HISTORY_SOURCE))

		rows.failAfterWrites = null
		assertEquals(RetainedRecordImport.Result.Imported(written = 5, verified = true), importHistory(blob))
		assertEquals(5, rows.allHistory.size)
	}

	@Test
	fun `an unreadable blob is recorded once and not parsed again while unchanged`() {
		val garbage = "{not an array"

		assertEquals(RetainedRecordImport.Result.Unreadable, importHistory(garbage))
		val record = rows.import(RetainedRecordImport.HISTORY_SOURCE)!!
		assertEquals(LegacyImportState.UNREADABLE, record.state)

		assertEquals(RetainedRecordImport.Result.Unchanged(LegacyImportState.UNREADABLE), importHistory(garbage))
		assertEquals(record, rows.import(RetainedRecordImport.HISTORY_SOURCE))
		assertTrue(rows.allHistory.isEmpty())

		// Once the bytes change, they are read again.
		val valid = RetainedRecordCodec.encodeHistory(listOf(history("alice", "a-1")))
		assertEquals(RetainedRecordImport.Result.Imported(1, true), importHistory(valid))
	}

	@Test
	fun `valid rows survive damaged neighbours in the same blob`() {
		val good = RetainedRecordCodec.encodeHistory(listOf(history("alice", "a-1"), history("bob", "b-1")))
		val damaged = good.removeSuffix("]") + """,{"title":"no event id","account":"alice"},42,{"eventId":"x"}]"""

		val result = importHistory(damaged)

		assertEquals(RetainedRecordImport.Result.Imported(written = 2, verified = true), result)
		assertEquals(setOf("a-1", "b-1"), rows.allHistory.map { it.eventId }.toSet())
		assertEquals(3, rows.import(RetainedRecordImport.HISTORY_SOURCE)!!.rejected)
	}

	@Test
	fun `absent or blank legacy bytes record nothing`() {
		assertEquals(RetainedRecordImport.Result.Absent, importHistory(null))
		assertEquals(RetainedRecordImport.Result.Absent, importSkipped("  "))
		assertNull(rows.import(RetainedRecordImport.HISTORY_SOURCE))
		assertNull(rows.import(RetainedRecordImport.SKIPPED_SOURCE))
	}

	@Test
	fun `legacy not-logged rows get the same ids on every read`() {
		val blob = legacySkips(legacySkipJson("one", "alice"), legacySkipJson("two", null))

		val first = RetainedRecordCodec.decodeSkipped(blob).map { it.rowId }
		val second = RetainedRecordCodec.decodeSkipped(blob).map { it.rowId }

		assertEquals(first, second)
		assertEquals(2, first.toSet().size)
		first.forEach { assertTrue(it, it.startsWith("legacy-")) }
	}

	@Test
	fun `identical legacy twins stay two rows and repeated import adds nothing`() {
		val twin = legacySkipJson("same refusal", "alice")
		val blob = legacySkips(twin, twin, legacySkipJson("other", null))

		assertEquals(RetainedRecordImport.Result.Imported(written = 3, verified = true), importSkipped(blob))
		assertEquals(3, rows.allSkipped.size)
		assertEquals(2, rows.allSkipped.count { it.title == "same refusal" })

		// A different blob that still contains the twins (e.g. rewritten by a run
		// that fell back to the legacy store) re-derives the same ids.
		val grown = legacySkips(legacySkipJson("newer", "alice", at = 1_700_000_500L), twin, twin, legacySkipJson("other", null))
		val again = importSkipped(grown)
		assertEquals(RetainedRecordImport.Result.Imported(written = 1, verified = true), again)
		assertEquals(4, rows.allSkipped.size)
	}

	@Test
	fun `stored row ids are kept, and the signed-out owner survives import`() {
		val rowsIn = listOf(
			FinalizationRuntime.SkipRecord("t", null, "r", 5, 1, null, null, account = null, rowId = "minted-1"),
			FinalizationRuntime.SkipRecord("t", null, "r", 4, 1, null, null, account = "Alice", rowId = "minted-2"),
		)
		importSkipped(RetainedRecordCodec.encodeSkipped(rowsIn))

		val stored = rows.allSkipped.associateBy { it.rowId }
		assertNull("signed-out stays signed-out", stored.getValue("minted-1").account)
		assertEquals("alice", stored.getValue("minted-2").account)
	}

	@Test
	fun `import only reads the legacy bytes`() {
		val legacy = InMemoryLegacyBlobs(
			history = RetainedRecordCodec.encodeHistory(listOf(history("alice", "a-1"))),
			skipped = legacySkips(legacySkipJson("one", null)),
		)
		val before = legacy.history to legacy.skipped

		DatabaseRetainedRecords.open(rows, legacy, { it.run() }, { 1L })

		assertEquals(before, legacy.history to legacy.skipped)
		assertEquals(0, legacy.writes)
		assertFalse(rows.allHistory.isEmpty())
	}

	@Test
	fun `a blob newer than the database is written over the stored rows`() {
		// The database absorbed generation 3 with the row still queued.
		importHistory(RetainedRecordCodec.encodeHistory(listOf(history("alice", "a-1", op = "op", status = "queued — offline"))), generation = 3)
		// A run that could not open the database then settled it, at generation 4.
		val fallback = RetainedRecordCodec.encodeHistory(listOf(history("alice", "a-1", op = "op", status = "sent from queue")))

		val result = importHistory(fallback, generation = 4)

		assertEquals(RetainedRecordImport.Result.Imported(written = 1, verified = true, legacyNewer = true), result)
		assertEquals("sent from queue", rows.allHistory.single().status)
		assertEquals(4L, rows.import(RetainedRecordImport.HISTORY_SOURCE)!!.generation)
	}

	@Test
	fun `a blob older than the database cannot roll a row back`() {
		importHistory(RetainedRecordCodec.encodeHistory(listOf(history("alice", "a-1", status = "confirmed in block"))), generation = 5)
		val stale = RetainedRecordCodec.encodeHistory(listOf(history("alice", "a-1", status = "seen relaying in mempool")))

		val result = importHistory(stale, generation = 4)

		assertEquals(RetainedRecordImport.Result.Imported(written = 0, verified = true), result)
		assertEquals("confirmed in block", rows.allHistory.single().status)
		assertEquals("the absorbed generation never goes down", 5L, rows.import(RetainedRecordImport.HISTORY_SOURCE)!!.generation)
	}

	@Test
	fun `the same bytes at a newer generation are read again, not skipped`() {
		val blob = RetainedRecordCodec.encodeHistory(listOf(history("alice", "a-1")))
		importHistory(blob, generation = 1)

		val result = importHistory(blob, generation = 2)

		assertEquals(RetainedRecordImport.Result.Imported(written = 1, verified = true, legacyNewer = true), result)
		assertEquals(1, rows.allHistory.size)
		assertEquals(RetainedRecordImport.Result.Unchanged(LegacyImportState.VERIFIED), importHistory(blob, generation = 2))
	}
}
