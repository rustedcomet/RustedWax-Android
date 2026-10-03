package com.rustedwax.app.scrobble

import com.rustedwax.app.storage.db.LegacyImportState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Issue #41: History and Not Logged through the production startup sequence
 * ([FinalizationRuntime.restoreRetained]) — the real SharedPreferences store
 * over in-memory preferences, an in-memory database, the writer run inline.
 */
class DatabaseRetainedRecordsTest {

	private val inline = Executor { it.run() }

	@Before
	fun clean() = FinalizationRuntime.resetForReplay()

	@After
	fun tidy() = FinalizationRuntime.resetForReplay()

	private fun history(account: String, eventId: String, at: Long = 1_700_000_000L, op: String? = null, status: String = "confirmed") =
		FinalizationRuntime.ScrobbleRecord(
			title = "Title $eventId",
			artist = null,
			percentPlayed = 90,
			atEpochSec = at,
			status = status,
			txId = null,
			queued = op != null && status.startsWith("queued"),
			videoId = "dQw4w9WgXcQ",
			eventId = eventId,
			account = account,
			queueOperationId = op,
		)

	private fun skip(account: String?, id: String, at: Long = 1_700_000_000L) =
		FinalizationRuntime.SkipRecord("Skip $id", null, "below threshold", at, 5, 100, null, account, rowId = id)

	/** The legacy store as a fresh process would build it over [prefs]. */
	private fun legacy(prefs: InMemorySharedPreferences) = SharedPreferencesRetainedRecords(prefs)

	private fun seed(prefs: InMemorySharedPreferences, history: List<FinalizationRuntime.ScrobbleRecord> = emptyList(), skipped: List<FinalizationRuntime.SkipRecord> = emptyList(), generation: Long = 0) {
		legacy(prefs).writeHistory(RetainedRecordCodec.encodeHistory(history), generation)
		legacy(prefs).writeSkipped(RetainedRecordCodec.encodeSkipped(skipped), generation)
	}

	/** One "process": exactly what `init` runs, with [openRows] as the database. */
	private fun boot(prefs: InMemorySharedPreferences, openRows: () -> RetainedRecordRows, writer: Executor = inline): SharedPreferencesRetainedRecords {
		FinalizationRuntime.resetForReplay()
		return legacy(prefs).also { FinalizationRuntime.restoreRetained(it, openRows, writer) }
	}

	private fun ids() = FinalizationRuntime.recent.value.map { it.eventId }

	// ---- migration and restore ------------------------------------------------

	@Test
	fun `first boot migrates legacy rows and restores each exactly once`() {
		val prefs = InMemorySharedPreferences()
		seed(prefs, listOf(history("alice", "a-2", at = 20), history("alice", "a-1", at = 10)), listOf(skip(null, "s-1")))
		val rows = InMemoryRetainedRecordRows()

		boot(prefs, { rows })

		assertEquals(listOf("a-2", "a-1"), ids())
		assertEquals(listOf("s-1"), FinalizationRuntime.skipped.value.map { it.rowId })
		assertEquals(listOf("a-2", "a-1"), rows.allHistory.map { it.eventId })
		assertEquals(listOf("s-1"), rows.allSkipped.map { it.rowId })
	}

	@Test
	fun `a process-style reopen restores every row exactly once`() {
		val prefs = InMemorySharedPreferences()
		seed(prefs, listOf(history("alice", "a-1"), history("bob", "b-1")), listOf(skip("alice", "s-1"), skip(null, "s-2")))
		val rows = InMemoryRetainedRecordRows()
		boot(prefs, { rows })
		val firstHistory = ids().sorted()
		val firstSkipped = FinalizationRuntime.skipped.value.map { it.rowId }.sorted()

		repeat(3) {
			boot(prefs, { rows })
			assertEquals(firstHistory, ids().sorted())
			assertEquals(firstSkipped, FinalizationRuntime.skipped.value.map { it.rowId }.sorted())
		}
		assertEquals(2, rows.allHistory.size)
		assertEquals(2, rows.allSkipped.size)
	}

	// ---- the database is authoritative once it opens ---------------------------

	@Test
	fun `a newer stored row replaces the older legacy copy, exactly once`() {
		val prefs = InMemorySharedPreferences()
		val rows = InMemoryRetainedRecordRows()
		val queued = history("alice", "a-1", op = "op-1", status = "queued — offline")
		val settled = queued.copy(status = "sent from queue", txId = "tx-1", queued = false)
		// The database committed the settled row at generation 2; the shadow
		// write that should have followed never reached disk, so the legacy
		// blob still holds generation 1 with the row queued.
		DatabaseRetainedRecords.writeHistory(rows, listOf(settled), RetainedRecordCodec.encodeHistory(listOf(settled)), 2, 1L)
		seed(prefs, listOf(queued), generation = 1)

		boot(prefs, { rows })

		assertEquals(listOf(settled), FinalizationRuntime.recent.value)
		assertEquals(listOf(settled), rows.allHistory)
		// And the shadow catches up, at a generation past the database's.
		assertEquals(listOf(settled), RetainedRecordCodec.decodeHistory(legacy(prefs).readHistory().text))
		assertTrue(legacy(prefs).readHistory().generation > 2)
	}

	@Test
	fun `the database replaces only untouched provisional rows`() {
		val stale = history("alice", "a-1", status = "seen relaying in mempool")
		val newer = stale.copy(status = "confirmed in block")
		val untouched = history("alice", "a-3", at = 1)
		val untouchedNewer = untouched.copy(status = "accepted — confirmation unavailable")
		val updatedLive = history("alice", "a-4", at = 2).copy(status = "sent from queue")
		val createdLive = history("alice", "a-5", at = 3)
		val storedOnly = history("bob", "b-1", at = 4)

		val result = FinalizationRuntime.reconcileAuthoritative(
			current = listOf(createdLive, updatedLive, stale, untouched),
			stored = listOf(newer, untouchedNewer, history("alice", "a-4", at = 2).copy(status = "queued — offline"), storedOnly),
			provisional = mapOf(
				"a-1" to stale,
				"a-3" to untouched,
				"a-4" to history("alice", "a-4", at = 2).copy(status = "queued — offline"),
			),
			id = FinalizationRuntime.ScrobbleRecord::eventId,
		)

		assertEquals(listOf(createdLive, updatedLive, newer, untouchedNewer, storedOnly), result)
	}

	@Test
	fun `a database that cannot open leaves the legacy store in charge as the fallback`() {
		val prefs = InMemorySharedPreferences()
		seed(prefs, listOf(history("alice", "a-1")), generation = 7)

		val store = boot(prefs, { throw IllegalStateException("database unavailable") })

		assertEquals(listOf("a-1"), ids())
		assertTrue("its writes now advance the generation", store.advancesGeneration)
		store.saveHistory(FinalizationRuntime.recent.value)
		assertEquals(8L, legacy(prefs).readHistory().generation)
	}

	@Test
	fun `a database that opens leaves the provisional legacy writes at their generation`() {
		val prefs = InMemorySharedPreferences()
		seed(prefs, listOf(history("alice", "a-1")), generation = 3)
		val held = ArrayDeque<Runnable>()

		val store = boot(prefs, { InMemoryRetainedRecordRows() }, writer = { held.addLast(it) })

		assertFalse(store.advancesGeneration)
		assertEquals("the provisional restore rewrote, but did not advance", 3L, legacy(prefs).readHistory().generation)
		while (held.isNotEmpty()) held.removeFirst().run()
	}

	@Test
	fun `an import that fails part-way falls back, and the next boot recovers`() {
		val prefs = InMemorySharedPreferences()
		seed(prefs, (1..4).map { history("alice", "a-$it", at = it.toLong()) })
		val rows = InMemoryRetainedRecordRows().apply { failAfterWrites = 2 }

		boot(prefs, { rows })
		assertTrue(rows.allHistory.isEmpty())
		assertEquals(4, ids().size)

		rows.failAfterWrites = null
		boot(prefs, { rows })
		assertEquals(4, rows.allHistory.size)
		assertEquals(4, ids().size)
	}

	@Test
	fun `a fallback run's changes win at the next successful boot`() {
		val prefs = InMemorySharedPreferences()
		val rows = InMemoryRetainedRecordRows()
		val queued = history("alice", "a-1", op = "op-1", status = "queued — offline")
		seed(prefs, listOf(queued))
		boot(prefs, { rows })

		// A run whose database fails settles the row through the legacy store.
		val fallback = boot(prefs, { throw IllegalStateException("unavailable") })
		fallback.saveHistory(listOf(queued.copy(status = "sent from queue", queued = false)))

		boot(prefs, { rows })
		assertEquals(listOf("sent from queue"), FinalizationRuntime.recent.value.map { it.status })
		assertEquals(listOf("sent from queue"), rows.allHistory.map { it.status })
	}

	// ---- saving ----------------------------------------------------------------

	@Test
	fun `every save writes the database first, then the shadow at a recorded generation`() {
		val prefs = InMemorySharedPreferences()
		val rows = InMemoryRetainedRecordRows()
		boot(prefs, { rows })
		val list = listOf(history("alice", "a-1"))
		val store = FinalizationRuntime.retainedStore()!!

		store.saveHistory(list)

		val shadow = legacy(prefs).readHistory()
		assertEquals(RetainedRecordCodec.encodeHistory(list), shadow.text)
		val record = rows.import(RetainedRecordImport.HISTORY_SOURCE)!!
		assertEquals(RetainedRecordCodec.sha256(shadow.text!!), record.fingerprint)
		assertEquals(shadow.generation, record.generation)
		assertEquals(
			RetainedRecordImport.Result.Unchanged(LegacyImportState.VERIFIED),
			RetainedRecordImport.importHistory(rows, shadow, 2L),
		)
	}

	@Test
	fun `a queue outcome updates the row it reports on rather than adding one`() {
		val rows = InMemoryRetainedRecordRows()
		boot(InMemorySharedPreferences(), { rows })
		val store = FinalizationRuntime.retainedStore()!!
		val queued = history("alice", "a-1", op = "op-1", status = "queued — offline")
		store.saveHistory(listOf(queued))

		store.saveHistory(listOf(queued.copy(status = "sent from queue", txId = "tx-9", queued = false)))

		val stored = rows.allHistory.single()
		assertEquals("a-1", stored.eventId)
		assertEquals("sent from queue", stored.status)
		assertEquals("tx-9", stored.txId)

		// A second row for the same owner's operation replaces the stale one.
		store.saveHistory(listOf(history("alice", "a-2", op = "op-1", status = "failed after 8 queued attempts")))
		assertEquals(listOf("a-2"), rows.allHistory.map { it.eventId })
	}

	@Test
	fun `a failed database write keeps the shadow, marked newer, and the next boot imports it`() {
		val prefs = InMemorySharedPreferences()
		val rows = InMemoryRetainedRecordRows()
		boot(prefs, { rows })
		rows.failAfterWrites = 0

		FinalizationRuntime.retainedStore()!!.saveHistory(listOf(history("alice", "a-1")))

		assertTrue(rows.allHistory.isEmpty())
		assertTrue(legacy(prefs).readHistory().generation > (rows.import(RetainedRecordImport.HISTORY_SOURCE)?.generation ?: 0))
		rows.failAfterWrites = null
		boot(prefs, { rows })
		assertEquals(listOf("a-1"), rows.allHistory.map { it.eventId })
		assertEquals(listOf("a-1"), ids())
	}

	// ---- per-owner retention ---------------------------------------------------

	@Test
	fun `fifty rows per owner in the database, and another account's rows never evict them`() {
		val prefs = InMemorySharedPreferences()
		val rows = InMemoryRetainedRecordRows()
		boot(prefs, { rows })
		val alice = (1..10).map { history("alice", "a-$it", at = it.toLong()) }
		val bob = (1..60).map { history("bob", "b-$it", at = 100L + it) }

		val inMemory = FinalizationRuntime.capHistory(bob.reversed() + alice.reversed())
		assertEquals(60, inMemory.size)
		FinalizationRuntime.retainedStore()!!.saveHistory(inMemory)

		assertEquals(10, rows.allHistory.count { it.account == "alice" })
		assertEquals(50, rows.allHistory.count { it.account == "bob" })
		assertEquals("b-60", rows.allHistory.first().eventId)

		// A → B → A after a restart.
		boot(prefs, { rows })
		val all = FinalizationRuntime.recent.value
		assertEquals(alice.map { it.eventId }.reversed(), FinalizationRuntime.recentFor(all, "alice").map { it.eventId })
		assertEquals(50, FinalizationRuntime.recentFor(all, "bob").size)
		assertEquals(10, FinalizationRuntime.recentFor(all, "ALICE").size)
	}

	@Test
	fun `the shadow keeps fifty History rows for each of two accounts`() {
		val prefs = InMemorySharedPreferences()
		val alice = (1..50).map { history("alice", "a-$it", at = it.toLong()) }
		val bob = (1..50).map { history("bob", "b-$it", at = 100L + it) }

		legacy(prefs).saveHistory(bob.reversed() + alice.reversed())

		val back = legacy(prefs).loadHistory()
		assertEquals(50, back.count { it.account == "alice" })
		assertEquals(50, back.count { it.account == "bob" })
	}

	@Test
	fun `when one account passes fifty in the shadow only that account is pruned`() {
		val prefs = InMemorySharedPreferences()
		val alice = (1..50).map { history("alice", "a-$it", at = it.toLong()) }
		val bob = (1..70).map { history("bob", "b-$it", at = 100L + it) }

		legacy(prefs).saveHistory(bob.reversed() + alice.reversed())

		val back = legacy(prefs).loadHistory()
		assertEquals(alice.reversed(), back.filter { it.account == "alice" })
		assertEquals((70 downTo 21).map { "b-$it" }, back.filter { it.account == "bob" }.map { it.eventId })
	}

	@Test
	fun `signed-out refusals in the shadow never evict signed-in ones`() {
		val prefs = InMemorySharedPreferences()
		val signedOut = (1..60).map { skip(null, "n-$it", at = 100L + it) }
		val alice = (1..50).map { skip("alice", "a-$it", at = it.toLong()) }

		legacy(prefs).saveSkipped(signedOut.reversed() + alice.reversed())

		val back = legacy(prefs).loadSkipped()
		assertEquals(50, back.count { it.account == null })
		assertEquals(50, back.count { it.account == "alice" })
		assertEquals("n-60", back.first().rowId)
	}

	@Test
	fun `a fallback run restores exactly the per-owner sets the shadow holds`() {
		val prefs = InMemorySharedPreferences()
		val history = (1..50).map { history("alice", "a-$it", at = it.toLong()) } +
			(1..50).map { history("bob", "b-$it", at = 100L + it) }
		val skipped = (1..50).map { skip(null, "n-$it") } + (1..50).map { skip("bob", "sb-$it") }
		legacy(prefs).saveHistory(history)
		legacy(prefs).saveSkipped(skipped)

		boot(prefs, { throw IllegalStateException("database unavailable") })

		val recent = FinalizationRuntime.recent.value
		assertEquals(legacy(prefs).loadHistory().toSet(), recent.toSet())
		assertEquals(50, FinalizationRuntime.recentFor(recent, "alice").size)
		assertEquals(50, FinalizationRuntime.recentFor(recent, "bob").size)
		val notLogged = FinalizationRuntime.skipped.value
		assertEquals(50, FinalizationRuntime.skippedFor(notLogged, null).size)
		assertEquals(50, FinalizationRuntime.skippedFor(notLogged, "bob").size)
	}

	@Test
	fun `the global cap is gone from the restore merge as well`() {
		val merged = FinalizationRuntime.mergeRestoredHistory(
			current = (1..50).map { history("bob", "b-$it") },
			restored = (1..20).map { history("alice", "a-$it") },
		)
		assertEquals(70, merged.size)
		val skippedMerged = FinalizationRuntime.mergeRestoredSkipped(
			current = (1..50).map { skip("bob", "sb-$it") },
			restored = (1..60).map { skip(null, "sn-$it") },
		)
		assertEquals(100, skippedMerged.size)
		assertEquals(50, skippedMerged.count { it.account == null })
	}

	@Test
	fun `signed-out refusals belong to the signed-out device only`() {
		val prefs = InMemorySharedPreferences()
		val rows = InMemoryRetainedRecordRows()
		boot(prefs, { rows })
		FinalizationRuntime.retainedStore()!!.saveSkipped(listOf(skip(null, "nobody"), skip("alice", "mine")))

		boot(prefs, { rows })
		val all = FinalizationRuntime.skipped.value

		assertEquals(listOf("nobody"), FinalizationRuntime.skippedFor(all, null).map { it.rowId })
		assertEquals(listOf("mine"), FinalizationRuntime.skippedFor(all, "alice").map { it.rowId })
		assertEquals(emptyList<String>(), FinalizationRuntime.skippedFor(all, "bob").map { it.rowId })
		assertNull(rows.allSkipped.single { it.rowId == "nobody" }.account)
	}
}
