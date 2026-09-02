package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.replay.ReplayHarness.RefusalKind
import com.rustedwax.app.scrobble.ConnectivityRetryTrigger
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.hive.HiveRpc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What becomes of a listen the phone could not send when it ended.
 *
 * Two failures met here and hid each other. A listen finalized offline queued
 * correctly and then waited for the user to open the app, because nothing in
 * the process watched the network; and a listen whose id never resolved —
 * which offline is the *usual* outcome, since every id route needs a
 * connection — was refused into the event log and shown in no user-facing
 * surface at all. Between them, a video watched end to end on a plane could be
 * absent from History and from Not logged both.
 *
 * These scenarios pin the pair: a reconnect drains what is owed with no UI
 * event, an unresolved refusal is recorded and readable, and neither of those
 * lets anything reach the chain that could not prove which video it was.
 */
class OfflineRecoveryReplayTest : ReplayScenarioTest() {

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

	/**
	 * The trigger, wired to the same two seams production wires it to.
	 *
	 * Not the Android `NetworkCallback` — that is the platform's to deliver, and
	 * `ConnectivityRetryWiringTest` proves the service registers one. This is
	 * everything downstream of the callback firing: the engine's queue depth and
	 * the engine's own `flushQueue`.
	 */
	private fun connectivityReturns(harness: ReplayHarness): Boolean =
		ConnectivityRetryTrigger(
			queueDepth = { FinalizationRuntime.queueSize.value },
			flush = { FinalizationRuntime.flushQueue() },
			nowMs = { harness.env.clock.nowMillis() },
		).onUsableNetwork()

	// ---- 3a: the queue drains itself -----------------------------------------

	@Test
	fun `connectivity returning drains a queued scrobble with no UI event`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts(56))
		// Airplane mode: the payload is built and signed, and the node is gone.
		harness.env.broadcaster.willReturn(HiveRpc.BroadcastResult.NetworkFailure("no route to host"))

		harness.feed(
			watching(56_000) + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized(),
		)

		assertEquals("the finalized listen was not queued", 1, harness.queuedForRetry)
		assertEquals(emptyList<String>(), harness.transactionIds)

		// Airplane mode off. Nobody opens RustedWax, nobody presses Retry.
		harness.env.clock.advance(25_000)
		assertTrue("the reconnect signal was dropped", connectivityReturns(harness))

		assertEquals("the queue did not drain on reconnect", 0, harness.queuedForRetry)
		assertEquals(1, harness.transactionIds.size)
		assertEquals(0, FinalizationRuntime.queueSize.value)
		// Retry is transport-only: the same bytes went out a second time rather
		// than a second listen being finalized.
		assertEquals(2, harness.env.broadcaster.sent.size)
		assertEquals(
			harness.env.broadcaster.sent[0].json,
			harness.env.broadcaster.sent[1].json,
		)
		assertEquals(1, harness.outcomes.size)
	}

	@Test
	fun `a reconnect while the queue is still backing off sends nothing`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts(56))
		harness.env.broadcaster.willReturn(HiveRpc.BroadcastResult.NetworkFailure("no route to host"))

		harness.feed(
			watching(56_000) + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized(),
		)
		assertEquals(1, harness.queuedForRetry)

		// The link came back but is not usable yet: the retry fails again and the
		// entry is retained. Nothing here shortens or bypasses that.
		harness.env.broadcaster.willReturn(HiveRpc.BroadcastResult.NetworkFailure("still offline"))
		connectivityReturns(harness)

		assertEquals("a failing retry lost the listen", 1, harness.queuedForRetry)
		assertEquals(emptyList<String>(), harness.transactionIds)
		assertEquals(1, harness.env.retryQueue.all.single().attempts)
	}

	// ---- 3b: an outcome the user can read -------------------------------------

	@Test
	fun `a notable refusal with no resolvable id is still recorded in Not logged`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)

		// Offline: the notification proves the origin, and every id route — the
		// address bar, the watch page, watch history — comes back with nothing.
		harness.feed(
			PlaybackEvent.NotificationObserved(host = "youtube.com", title = "Unresolved video"),
			PlaybackEvent.SessionMetadata(
				title = "Unresolved video",
				artist = "Unknown channel",
				durationMs = 60_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(40_000),
			PlaybackEvent.Finalized(),
		)

		val refusal = harness.refusals.single()
		assertEquals(RefusalKind.NO_VERIFIED_VIDEO_ID, refusal.kind)
		assertEquals("Unresolved video", refusal.title)
		assertEquals(40, refusal.playedSeconds)
		// No id was proven, so none is shown — and none is invented.
		assertNull(refusal.videoId)
		assertTrue(harness.terminalRefusalReasons.single().isNotEmpty())
	}

	@Test
	fun `an unresolved listen is recorded but never broadcast`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)

		harness.feed(
			PlaybackEvent.NotificationObserved(host = "youtube.com", title = "Unresolved video"),
			PlaybackEvent.SessionMetadata(
				title = "Unresolved video",
				artist = "Unknown channel",
				durationMs = 60_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(40_000),
			PlaybackEvent.Finalized(),
		)

		// The record is a user-facing explanation, not a licence to publish.
		assertEquals(1, FinalizationRuntime.skipped.value.size)
		assertEquals(emptyList<RecordingBroadcaster.Sent>(), harness.env.broadcaster.sent)
		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(emptyList<String>(), harness.transactionIds)
		assertEquals(0, harness.queuedForRetry)
		assertEquals(emptyList<FinalizationRuntime.ScrobbleRecord>(), FinalizationRuntime.recent.value)
	}

	@Test
	fun `a refusal too brief to be notable is still kept out of Not logged`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)

		harness.feed(
			PlaybackEvent.NotificationObserved(host = "youtube.com", title = "Unresolved video"),
			PlaybackEvent.SessionMetadata(
				title = "Unresolved video",
				artist = "Unknown channel",
				durationMs = 60_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(1_500),
			PlaybackEvent.Finalized(),
		)

		// Visibility widened to unresolved refusals, not to every glance at a
		// tab. The played-time floor is doing the same job it always did.
		assertEquals(emptyList<ReplayHarness.Refusal>(), harness.refusals)
		assertTrue(harness.outcomes.single().outcome is com.rustedwax.app.scrobble.FinalizationOutcome.Refused)
	}

	// ---- the combined shape, and the control ---------------------------------

	@Test
	fun `a listen played offline ends up either sent or visible, never absent`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts(56))
		harness.env.broadcaster.willReturn(HiveRpc.BroadcastResult.NetworkFailure("no route to host"))

		harness.feed(
			watching(56_000) + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized(),
		)

		// Queued, and already visible as such while it waits.
		assertEquals(1, harness.queuedForRetry)
		assertTrue(FinalizationRuntime.recent.value.single().queued)

		harness.env.clock.advance(25_000)
		connectivityReturns(harness)

		// Two rows for one listen, oldest last: "queued — offline" while it
		// waited, then the send that cleared it. The list prepends.
		assertEquals(2, FinalizationRuntime.recent.value.size)
		val entry = FinalizationRuntime.recent.value.first()
		assertEquals(videoId, entry.videoId)
		assertTrue("the drained entry did not record a transaction", entry.txId != null)
		assertEquals(emptyList<ReplayHarness.Refusal>(), harness.refusals)
	}

	@Test
	fun `an ordinary online listen still scrobbles immediately and queues nothing`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts(56))

		harness.feed(
			watching(56_000) + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		assertEquals(1, harness.transactionIds.size)
		assertEquals(96, harness.broadcasts.single().percentPlayed)
		assertEquals(
			"https://www.youtube.com/watch?v=$videoId",
			harness.broadcasts.single().url,
		)
		assertEquals(0, harness.queuedForRetry)
		assertEquals(emptyList<ReplayHarness.Refusal>(), harness.refusals)
		// Nothing owed, so a reconnect is a no-op rather than a second send.
		assertEquals(false, connectivityReturns(harness))
		assertEquals(1, harness.env.broadcaster.sent.size)
	}
}
