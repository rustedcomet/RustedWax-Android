package com.rustedwax.app.scrobble

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Putting the retained lists back, and what must not happen when it runs.
 *
 * Issue #31's other half. The codec proves the rows survive the disk; this
 * proves the runtime adopts them correctly — once, in the right order, without
 * duplicating anything this process already filed, and without a broken store
 * being able to take anything else down with it.
 *
 * [FinalizationRuntime] is a process singleton, so every test starts from
 * `resetForReplay`, which is also what clears the store between scenarios.
 */
class RetainedRecordsRestoreTest {

	@Before
	fun clean() = FinalizationRuntime.resetForReplay()

	@After
	fun tidy() = FinalizationRuntime.resetForReplay()

	@Test
	fun `a cold process adopts what the last one left`() {
		// The reported failure, in one assertion: the process is new, both lists
		// are empty, and the stored rows come back.
		assertTrue(FinalizationRuntime.recent.value.isEmpty())
		assertTrue(FinalizationRuntime.skipped.value.isEmpty())

		FinalizationRuntime.restoreFrom(
			FakeStore(
				history = listOf(historyRow("alice", "a-1"), historyRow("alice", "a-2")),
				skipped = listOf(skipRow("alice", "s-1"), skipRow(null, "s-2")),
			),
		)

		assertEquals(
			listOf("a-1", "a-2"),
			FinalizationRuntime.recent.value.map { it.eventId },
		)
		assertEquals(
			listOf("s-1", "s-2"),
			FinalizationRuntime.skipped.value.map { it.title },
		)
	}

	@Test
	fun `restoring twice does not double the lists`() {
		// `init` is called from MainActivity, the listener service and the
		// YouTube sign-in activity. On a cold start with the service already
		// bound, more than one of them can reach it; the second must be a no-op
		// rather than a second copy of every row.
		val store = FakeStore(
			history = listOf(historyRow("alice", "a-1")),
			skipped = listOf(skipRow("alice", "s-1")),
		)

		FinalizationRuntime.restoreFrom(store)
		FinalizationRuntime.restoreFrom(store)

		assertEquals(listOf("a-1"), FinalizationRuntime.recent.value.map { it.eventId })
		assertEquals(listOf("s-1"), FinalizationRuntime.skipped.value.map { it.title })
	}

	@Test
	fun `a row filed before the restore lands stays, and stays on top`() {
		// The race the merge exists for: a listener callback finalizes while
		// `init` is still on its way through. The new row is newer than anything
		// on disk, so it keeps the top of the list, and the stored rows follow
		// it — none of them lost, none of them repeated.
		val live = historyRow("alice", "live-1")
		val merged = FinalizationRuntime.mergeRestoredHistory(
			current = listOf(live),
			restored = listOf(historyRow("alice", "a-1"), historyRow("alice", "a-2")),
		)

		assertEquals(listOf("live-1", "a-1", "a-2"), merged.map { it.eventId })
	}

	@Test
	fun `a row filed while the store is read is retained and saved with the restored tail`() {
		val liveStore = FakeStore(
			history = listOf(historyRow("alice", "live-1")),
			skipped = listOf(skipRow("alice", "live-skip")),
		)
		val oldHistory = historyRow("alice", "old-1")
		val oldSkipped = skipRow("alice", "old-skip")
		var savedHistory = emptyList<FinalizationRuntime.ScrobbleRecord>()
		var savedSkipped = emptyList<FinalizationRuntime.SkipRecord>()
		val interleavingStore = object : RetainedRecordStore {
			override fun loadHistory(): List<FinalizationRuntime.ScrobbleRecord> =
				listOf(oldHistory)

			override fun loadSkipped(): List<FinalizationRuntime.SkipRecord> {
				FinalizationRuntime.restoreFrom(liveStore)
				return listOf(oldSkipped)
			}

			override fun saveHistory(rows: List<FinalizationRuntime.ScrobbleRecord>) {
				savedHistory = rows
			}

			override fun saveSkipped(rows: List<FinalizationRuntime.SkipRecord>) {
				savedSkipped = rows
			}
		}

		FinalizationRuntime.restoreFrom(interleavingStore)

		assertEquals(
			listOf("live-1", "old-1"),
			FinalizationRuntime.recent.value.map { it.eventId },
		)
		assertEquals(
			listOf("live-skip", "old-skip"),
			FinalizationRuntime.skipped.value.map { it.title },
		)
		assertEquals(listOf("live-1", "old-1"), savedHistory.map { it.eventId })
		assertEquals(listOf("live-skip", "old-skip"), savedSkipped.map { it.title })
	}

	@Test
	fun `a restored row this process already filed is not added again`() {
		// The same row on both sides — which is what a restore after a save of
		// the very same list looks like. Identity is `eventId`, minted once per
		// row, so the duplicate is recognised even though every other field also
		// matches.
		val shared = historyRow("alice", "a-1")
		val merged = FinalizationRuntime.mergeRestoredHistory(
			current = listOf(shared),
			restored = listOf(shared, historyRow("alice", "a-2")),
		)

		assertEquals(listOf("a-1", "a-2"), merged.map { it.eventId })
	}

	@Test
	fun `not-logged rows de-duplicate on the whole row`() {
		// Skip rows carry no minted id, so equality is the data class's own.
		val shared = skipRow("alice", "s-1")
		val merged = FinalizationRuntime.mergeRestoredSkipped(
			current = listOf(shared),
			restored = listOf(shared, skipRow(null, "s-2")),
		)

		assertEquals(listOf("s-1", "s-2"), merged.map { it.title })
	}

	@Test
	fun `the merge never exceeds the cap`() {
		val merged = FinalizationRuntime.mergeRestoredHistory(
			current = (1..30).map { historyRow("alice", "live-$it") },
			restored = (1..40).map { historyRow("alice", "old-$it") },
		)

		assertEquals(RETAINED_ROWS, merged.size)
		// Live rows are newer, so they are the ones that survive the trim.
		assertEquals("live-1", merged.first().eventId)
		assertEquals("old-20", merged.last().eventId)
	}

	@Test
	fun `the account boundary still selects over restored rows`() {
		FinalizationRuntime.restoreFrom(
			FakeStore(
				history = listOf(
					historyRow("alice", "a-1"),
					historyRow("bob", "b-1"),
					historyRow("alice", "a-2"),
				),
				skipped = listOf(
					skipRow("alice", "sa-1"),
					skipRow("bob", "sb-1"),
					skipRow(null, "sn-1"),
				),
			),
		)

		val allRecent = FinalizationRuntime.recent.value
		val allSkipped = FinalizationRuntime.skipped.value

		// A signs in: A's rows, and only A's.
		assertEquals(
			listOf("a-1", "a-2"),
			FinalizationRuntime.recentFor(allRecent, "alice").map { it.eventId },
		)
		assertEquals(
			listOf("sa-1"),
			FinalizationRuntime.skippedFor(allSkipped, "alice").map { it.title },
		)

		// B takes over: none of A's survives the switch on screen.
		assertEquals(
			listOf("b-1"),
			FinalizationRuntime.recentFor(allRecent, "bob").map { it.eventId },
		)
		assertEquals(
			listOf("sb-1"),
			FinalizationRuntime.skippedFor(allSkipped, "bob").map { it.title },
		)

		// And back to A, unchanged — the restore did not make the rows global
		// again, which is the regression this pairing has to rule out.
		assertEquals(
			listOf("a-1", "a-2"),
			FinalizationRuntime.recentFor(allRecent, "alice").map { it.eventId },
		)
	}

	@Test
	fun `signed out sees its own restored refusals and no account's history`() {
		FinalizationRuntime.restoreFrom(
			FakeStore(
				history = listOf(historyRow("alice", "a-1")),
				skipped = listOf(skipRow("alice", "sa-1"), skipRow(null, "sn-1")),
			),
		)

		assertEquals(
			emptyList<String>(),
			FinalizationRuntime.recentFor(FinalizationRuntime.recent.value, null).map { it.eventId },
		)
		assertEquals(
			listOf("sn-1"),
			FinalizationRuntime.skippedFor(FinalizationRuntime.skipped.value, null).map { it.title },
		)
	}

	@Test
	fun `a store that throws leaves the app running and the lists usable`() {
		// Persistence is a record of what the app did. Losing it must never be
		// able to stop the app doing it, so a store that cannot be read is not
		// allowed to propagate out of the restore.
		FinalizationRuntime.restoreFrom(ThrowingStore)

		assertTrue(FinalizationRuntime.recent.value.isEmpty())
		assertTrue(FinalizationRuntime.skipped.value.isEmpty())
	}

	@Test
	fun `a store that returns nothing is not an error`() {
		// First run after the update: the file does not exist yet.
		FinalizationRuntime.restoreFrom(FakeStore())

		assertTrue(FinalizationRuntime.recent.value.isEmpty())
		assertTrue(FinalizationRuntime.skipped.value.isEmpty())
	}

	private class FakeStore(
		private val history: List<FinalizationRuntime.ScrobbleRecord> = emptyList(),
		private val skipped: List<FinalizationRuntime.SkipRecord> = emptyList(),
	) : RetainedRecordStore {
		override fun loadHistory() = history
		override fun saveHistory(rows: List<FinalizationRuntime.ScrobbleRecord>) = Unit
		override fun loadSkipped() = skipped
		override fun saveSkipped(rows: List<FinalizationRuntime.SkipRecord>) = Unit
	}

	private object ThrowingStore : RetainedRecordStore {
		override fun loadHistory(): List<FinalizationRuntime.ScrobbleRecord> =
			throw IllegalStateException("preferences unreadable")

		override fun saveHistory(rows: List<FinalizationRuntime.ScrobbleRecord>) =
			throw IllegalStateException("preferences unwritable")

		override fun loadSkipped(): List<FinalizationRuntime.SkipRecord> =
			throw IllegalStateException("preferences unreadable")

		override fun saveSkipped(rows: List<FinalizationRuntime.SkipRecord>) =
			throw IllegalStateException("preferences unwritable")
	}

	private fun historyRow(account: String, eventId: String) =
		FinalizationRuntime.ScrobbleRecord(
			title = "Never Gonna Give You Up",
			artist = "Rick Astley",
			percentPlayed = 91,
			atEpochSec = 1_700_000_000L,
			status = "confirmed in block",
			txId = "tx-$eventId",
			videoId = "dQw4w9WgXcQ",
			eventId = eventId,
			account = account,
		)

	private fun skipRow(account: String?, title: String) =
		FinalizationRuntime.SkipRecord(
			title = title,
			artist = null,
			reason = "watched 12% — below your 70% threshold",
			atEpochSec = 1_700_000_000L,
			playedSeconds = 12,
			durationSeconds = 100,
			videoId = null,
			account = account,
		)
}
