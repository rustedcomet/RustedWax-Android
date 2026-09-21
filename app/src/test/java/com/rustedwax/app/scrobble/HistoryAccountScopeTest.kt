package com.rustedwax.app.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account boundary History is drawn through.
 *
 * [FinalizationRuntime.recent] is one list for the whole process, held in
 * memory and never written to disk. Nothing clears it when the Hive account
 * changes — the vault is replaced, the Snap stores re-key themselves, and the
 * list carries on holding the rows the previous identity made. That is the
 * reported failure: sign in as B and A's listens are still on screen, counted
 * in the tab strip, and one tap from B's Snap composer.
 *
 * [FinalizationRuntime.recentFor] is the rule that stops it, and these are its
 * terms. The stamp it reads is asserted end to end, against the real engine, in
 * `HistoryAccountStampReplayTest`.
 */
class HistoryAccountScopeTest {

	@Test
	fun `a viewer sees only the rows stamped for them`() {
		val rows = listOf(row("alice", "a-1"), row("bob", "b-1"), row("alice", "a-2"))

		assertEquals(
			listOf("a-1", "a-2"),
			FinalizationRuntime.recentFor(rows, "alice").map { it.eventId },
		)
		assertEquals(
			listOf("b-1"),
			FinalizationRuntime.recentFor(rows, "bob").map { it.eventId },
		)
	}

	@Test
	fun `switching account replaces the visible list rather than adding to it`() {
		// The one the bug report describes. A is signed in and has History; B
		// takes over; the process-wide list is unchanged, and B must still see
		// none of it.
		val aRows = listOf(row("alice", "a-1"), row("alice", "a-2"))
		assertEquals(2, FinalizationRuntime.recentFor(aRows, "alice").size)
		assertEquals(emptyList<String>(), FinalizationRuntime.recentFor(aRows, "bob").map { it.eventId })

		// B then plays something of their own. A's rows are still in the list —
		// they are not deleted — and are still not B's.
		val both = listOf(row("bob", "b-1")) + aRows
		assertEquals(listOf("b-1"), FinalizationRuntime.recentFor(both, "bob").map { it.eventId })

		// And switching back restores exactly what A had, in the order it had.
		assertEquals(
			listOf("a-1", "a-2"),
			FinalizationRuntime.recentFor(both, "alice").map { it.eventId },
		)
	}

	@Test
	fun `signed out is not a viewer`() {
		val rows = listOf(row("alice", "a-1"))

		// Removing the key is an account change like any other: the rows it was
		// signed for stop being visible. Blank is the same answer as absent —
		// nothing legitimate is ever filed under an empty account name, so a
		// blank viewer matching a blank stamp would be two faults agreeing.
		assertEquals(emptyList<String>(), FinalizationRuntime.recentFor(rows, null).map { it.eventId })
		assertEquals(emptyList<String>(), FinalizationRuntime.recentFor(rows, "").map { it.eventId })
		assertEquals(emptyList<String>(), FinalizationRuntime.recentFor(rows, "   ").map { it.eventId })
	}

	@Test
	fun `the same account under a different casing is the same account`() {
		// `KeyVault.save` lowercases what it stores, so the two should already
		// agree. Compared case-insensitively anyway, exactly as every other
		// account check in this app is — a row that becomes invisible because of
		// a capital letter is a listen the user cannot see and cannot explain.
		val rows = listOf(row("alice", "a-1"))

		assertEquals(1, FinalizationRuntime.recentFor(rows, "Alice").size)
		assertEquals(1, FinalizationRuntime.recentFor(listOf(row("Alice", "a-1")), "alice").size)
	}

	@Test
	fun `the rows handed back are the originals, untouched`() {
		// The selector is a view, not a rewrite. Nothing downstream — the draft
		// key, the compose key, a Snap already published against this row — may
		// see a different `eventId` because the list was filtered.
		val mine = row("alice", "a-1")
		val visible = FinalizationRuntime.recentFor(listOf(mine, row("bob", "b-1")), "alice")

		assertEquals(1, visible.size)
		assertTrue("the selector rebuilt a row instead of passing it through", visible.single() === mine)
	}

	private fun row(account: String, eventId: String) = FinalizationRuntime.ScrobbleRecord(
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
}
