package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.ConnectivityRetryTrigger
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.hive.HiveRpc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who each History row belongs to, decided by the engine that makes it.
 *
 * `HistoryAccountScopeTest` pins what the selector does with the stamp. This
 * pins the stamp itself, through the real finalization path rather than a
 * hand-built record: a row that is filtered by an account it was never given
 * correctly is a boundary that only looks like one.
 *
 * The rule is that the stamp names the account the payload was **signed for**,
 * never whoever the vault happens to hold when the row is drawn. Those are the
 * same account almost always, and differ for exactly the rows that matter — one
 * finalized as a switch lands, or a queued entry retried long afterwards.
 */
class HistoryAccountStampReplayTest : ReplayScenarioTest() {

	private val videoId = "_OBlgSz8sSM"
	private val title = "Charlie bit my finger - again !"
	private val channel = "HDCYT"

	private fun facts(lengthSeconds: Long) = VideoFacts(
		videoId = videoId,
		title = title,
		author = channel,
		lengthSeconds = lengthSeconds,
		category = "Entertainment",
		watchPageResolved = true,
		isUnlisted = false,
	)

	private fun watching(durationMs: Long) = listOf(
		PlaybackEvent.NotificationObserved(host = "youtube.com", title = title),
		PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = videoId),
		PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = durationMs),
		PlaybackEvent.PlaybackStateChanged(playing = true),
	)

	/** A harness whose vault holds exactly one named account. */
	private fun harnessFor(account: String) = ReplayHarness(
		ReplaySource.BRAVE,
		ReplayEnvironment(posting = ReplayPostingIdentity(username = account)),
	)

	@Test
	fun `a sent listen is filed under the account that signed it`() {
		val harness = harnessFor("alice")
		harness.env.facts.put(facts(56))

		harness.feed(watching(56_000) + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized())

		val row = FinalizationRuntime.recent.value.single()
		assertEquals("alice", row.account)
		// Which is the same account the transaction went out as. The stamp and
		// the chain write agree, or the row is describing somebody else's listen.
		assertEquals("alice", harness.env.broadcaster.sent.single().username)
	}

	@Test
	fun `a queued row and the row that clears it are filed under the same account`() {
		val harness = harnessFor("alice")
		harness.env.facts.put(facts(56))
		// Airplane mode: signed, queued, and visible as queued while it waits.
		harness.env.broadcaster.willReturn(HiveRpc.BroadcastResult.NetworkFailure("no route to host"))

		harness.feed(watching(56_000) + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized())

		assertEquals("alice", FinalizationRuntime.recent.value.single().account)

		harness.env.clock.advance(25_000)
		ConnectivityRetryTrigger(
			queueDepth = { FinalizationRuntime.queueSize.value },
			flush = { FinalizationRuntime.flushQueue() },
			nowMs = { harness.env.clock.nowMillis() },
		).onUsableNetwork()

		// Two rows for one listen. The second is written by the queue drain,
		// minutes or days later, and takes its account from the entry it is
		// retrying rather than from the vault it happens to find.
		val rows = FinalizationRuntime.recent.value
		assertEquals(2, rows.size)
		assertTrue("the drained row was not stamped", rows.all { it.account == "alice" })
		assertTrue("the drained entry did not record a transaction", rows.first().txId != null)
	}

	@Test
	fun `a second account sees none of the first account's rows`() {
		// The reported failure, end to end: the list is process-global, so A's
		// rows are still in it after B signs in. What changes is what B is shown.
		val alice = harnessFor("alice")
		alice.env.facts.put(facts(56))
		alice.feed(watching(56_000) + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized())

		val afterAlice = FinalizationRuntime.recent.value
		assertEquals(1, afterAlice.size)
		assertEquals(
			emptyList<String>(),
			FinalizationRuntime.recentFor(afterAlice, "bob").map { it.eventId },
		)
		assertEquals(1, FinalizationRuntime.recentFor(afterAlice, "alice").size)
	}
}
