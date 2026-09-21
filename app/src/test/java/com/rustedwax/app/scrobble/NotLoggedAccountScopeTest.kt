package com.rustedwax.app.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account boundary Not logged is drawn through.
 *
 * [FinalizationRuntime.skipped] has the same shape as the History list that
 * #22 fixed — one list for the whole process, in memory, never written to disk
 * and never cleared when the Hive account changes — and it had the same leak.
 * Sign in as B and A's declined listens were still on screen, still counted in
 * the tab strip, and still one tap from opening the video they name.
 *
 * [FinalizationRuntime.skippedFor] is the rule that stops it, and these are its
 * terms. The stamp it reads is asserted end to end, against the real engine, in
 * `NotLoggedAccountStampReplayTest`.
 *
 * The one place this rule is not a copy of `recentFor` is signed out, and the
 * tests below spend most of their length on it: a row here can honestly have no
 * owner, and what happens to those rows when somebody signs in is the question
 * the issue asked to be answered explicitly.
 */
class NotLoggedAccountScopeTest {

	@Test
	fun `a viewer sees only the rows stamped for them`() {
		val rows = listOf(row("alice", "a-1"), row("bob", "b-1"), row("alice", "a-2"))

		assertEquals(
			listOf("a-1", "a-2"),
			FinalizationRuntime.skippedFor(rows, "alice").map { it.title },
		)
		assertEquals(
			listOf("b-1"),
			FinalizationRuntime.skippedFor(rows, "bob").map { it.title },
		)
	}

	@Test
	fun `switching account replaces the visible list rather than adding to it`() {
		// The reported failure. A has declined listens; B takes over without a
		// restart; the process-wide list is untouched, and B must see none of it.
		val afterA = listOf(row("alice", "a-1"))

		assertEquals(emptyList<String>(), FinalizationRuntime.skippedFor(afterA, "bob").map { it.title })

		val afterB = listOf(row("bob", "b-1")) + afterA
		assertEquals(listOf("b-1"), FinalizationRuntime.skippedFor(afterB, "bob").map { it.title })
	}

	@Test
	fun `switching back restores the first account's rows`() {
		// A to B to A. The rows were filtered out of B's view, not deleted, so
		// coming back shows them again — the same retention History has, and the
		// reason the fix is a selector rather than a clear-on-switch.
		val rows = listOf(row("bob", "b-1"), row("alice", "a-1"))

		assertEquals(listOf("a-1"), FinalizationRuntime.skippedFor(rows, "alice").map { it.title })
		assertEquals(listOf("b-1"), FinalizationRuntime.skippedFor(rows, "bob").map { it.title })
		assertEquals(listOf("a-1"), FinalizationRuntime.skippedFor(rows, "alice").map { it.title })
	}

	@Test
	fun `a signed-out device sees the rows it made while signed out`() {
		// Not logged answers "why wasn't this scrobbled", and a device with no
		// key needs that answer more than anyone. Gates above the posting-key
		// check still file rows, so signed out is a real owner here — unlike in
		// History, where it is not a viewer at all.
		val rows = listOf(row(null, "anon-1"), row("alice", "a-1"), row(null, "anon-2"))

		assertEquals(
			listOf("anon-1", "anon-2"),
			FinalizationRuntime.skippedFor(rows, null).map { it.title },
		)
	}

	@Test
	fun `signing in does not hand the signed-out rows to the account that arrives`() {
		// The explicit instruction in the issue: activity nobody was logged in
		// for is never retroactively attributed. A null stamp is matched, never
		// treated as a wildcard, so it stays with the signed-out device.
		val rows = listOf(row(null, "anon-1"), row("alice", "a-1"))

		assertEquals(listOf("a-1"), FinalizationRuntime.skippedFor(rows, "alice").map { it.title })
		assertEquals(listOf("a-1"), FinalizationRuntime.skippedFor(rows, "Alice").map { it.title })
		assertTrue(
			"an anonymous row reached a signed-in viewer",
			FinalizationRuntime.skippedFor(rows, "alice").none { it.account == null },
		)
	}

	@Test
	fun `signing out does not expose the rows an account left behind`() {
		// The other direction of the same rule. Handing A's declined listens to
		// whoever picks the phone up after a sign-out is the leak in a different
		// costume.
		val rows = listOf(row("alice", "a-1"), row("bob", "b-1"))

		assertEquals(emptyList<String>(), FinalizationRuntime.skippedFor(rows, null).map { it.title })
	}

	@Test
	fun `a blank viewer is signed out, not a wildcard`() {
		// A username that survived as whitespace must not become "show me
		// everything"; it is the absence of an account, and reads the anonymous
		// rows exactly as null does.
		val rows = listOf(row("alice", "a-1"), row(null, "anon-1"))

		assertEquals(listOf("anon-1"), FinalizationRuntime.skippedFor(rows, "  ").map { it.title })
	}

	@Test
	fun `the stamp is matched the way Hive names compare`() {
		// The vault lowercases on save, but the row outlives any one spelling and
		// a viewer name can arrive from elsewhere. Same comparison `recentFor`
		// makes, so the two boundaries cannot disagree about who is who.
		val rows = listOf(row("Alice", "a-1"))

		assertEquals(listOf("a-1"), FinalizationRuntime.skippedFor(rows, "alice").map { it.title })
	}

	@Test
	fun `History keeps its own stricter signed-out rule`() {
		// Guarding the seam rather than the behaviour: these two selectors look
		// alike and are deliberately not the same. Every History row was signed
		// by somebody, so signed out sees nothing there — collapsing them into
		// one rule would regress #22 the moment Not logged's looser case was
		// applied to listens that actually reached the chain.
		assertEquals(
			emptyList<FinalizationRuntime.ScrobbleRecord>(),
			FinalizationRuntime.recentFor(
				listOf(
					FinalizationRuntime.ScrobbleRecord(
						title = "Never Gonna Give You Up",
						artist = "Rick Astley",
						percentPlayed = 91,
						atEpochSec = 1_700_000_000L,
						status = "confirmed in block",
						txId = "tx-a-1",
						videoId = "dQw4w9WgXcQ",
						eventId = "event-1",
						account = "alice",
					),
				),
				null,
			),
		)
	}

	private fun row(account: String?, title: String) = FinalizationRuntime.SkipRecord(
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
