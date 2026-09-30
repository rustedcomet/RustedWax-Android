package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.ConnectivityRetryTrigger
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.app.scrobble.RetainedRecordCodec
import com.rustedwax.app.scrobble.RetainedRecordStore
import com.rustedwax.app.ui.snaps.SnapDraftKey
import com.rustedwax.hive.HiveRpc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #9 B2 — one listen, one History row, however it leaves the queue.
 *
 * A listen finalized offline gets a "queued" row. Its later outcome used to
 * arrive as a second row with a fresh `eventId`, and the first kept saying
 * "not on-chain yet" for ever — across restarts, because the retained store
 * round-trips `queued`. The row now carries the queue operation id, and every
 * queued outcome updates that row in place.
 */
class QueuedHistoryReconciliationReplayTest : ReplayScenarioTest() {

	private val videoId = "_OBlgSz8sSM"
	private val title = "Synthetic Queued Song"
	private val channel = "SyntheticChannel"
	private val offline = HiveRpc.BroadcastResult.NetworkFailure("Unable to resolve host")

	/** The History header rule, as MainScreen.kt applies it. */
	private fun unsettled(rows: List<FinalizationRuntime.ScrobbleRecord>) =
		rows.count { it.queued || it.status.startsWith("rejected") || it.queueFailed || it.acceptedUnconfirmed }

	/** A store whose "disk" is the production codec's own bytes. */
	private class CodecStore(var historyBytes: String? = null) : RetainedRecordStore {
		override fun loadHistory() = RetainedRecordCodec.decodeHistory(historyBytes)
		override fun saveHistory(rows: List<FinalizationRuntime.ScrobbleRecord>) {
			historyBytes = RetainedRecordCodec.encodeHistory(rows)
		}
		override fun loadSkipped() = emptyList<FinalizationRuntime.SkipRecord>()
		override fun saveSkipped(rows: List<FinalizationRuntime.SkipRecord>) = Unit
	}

	private fun facts() = VideoFacts(
		videoId = videoId,
		title = title,
		author = channel,
		lengthSeconds = 56,
		category = "Music",
		watchPageResolved = true,
		isUnlisted = false,
	)

	private fun watching() = listOf(
		PlaybackEvent.NotificationObserved(host = "youtube.com", title = title),
		PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = videoId),
		PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 56_000),
		PlaybackEvent.PlaybackStateChanged(playing = true),
	)

	private fun reconnect(harness: ReplayHarness) = ConnectivityRetryTrigger(
		queueDepth = { FinalizationRuntime.queueSize.value },
		flush = { FinalizationRuntime.flushQueue() },
		nowMs = { harness.env.clock.nowMillis() },
	).onUsableNetwork()

	private fun rows() = FinalizationRuntime.recent.value

	/** One listen finalized with no node reachable: queued as plain QUEUED, one row. */
	private fun queuedOffline(harness: ReplayHarness, store: CodecStore = CodecStore()): FinalizationRuntime.ScrobbleRecord {
		FinalizationRuntime.restoreFrom(store)
		harness.env.facts.put(facts())
		harness.env.broadcaster.preparationFailure = offline
		harness.feed(watching() + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized())
		harness.env.broadcaster.preparationFailure = null

		val row = rows().single { it.account == "rustedwax-replay" }
		assertTrue(row.queued)
		assertEquals("queued — offline", row.status)
		assertNull(row.txId)
		assertEquals(
			"the queued row does not carry its queue operation",
			harness.env.retryQueue.all.single().operationId,
			row.queueOperationId,
		)
		assertEquals(1, unsettled(FinalizationRuntime.recentFor(rows(), "rustedwax-replay")))
		return row
	}

	/** The settled row is the queued row: one row, same identity, not queued. */
	private fun assertSettledInPlace(queuedRow: FinalizationRuntime.ScrobbleRecord): FinalizationRuntime.ScrobbleRecord {
		val row = rows().single()
		assertEquals("the outcome became a second identity", queuedRow.eventId, row.eventId)
		assertEquals(queuedRow.videoId, row.videoId)
		assertEquals(queuedRow.title, row.title)
		assertEquals(queuedRow.artist, row.artist)
		assertEquals(queuedRow.account, row.account)
		assertEquals("the listen time moved", queuedRow.atEpochSec, row.atEpochSec)
		assertEquals(queuedRow.queueOperationId, row.queueOperationId)
		assertFalse("the settled row still claims to be queued", row.queued)
		return row
	}

	@Test
	fun `queued then sent leaves one row with the same eventId`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		val queued = queuedOffline(harness)

		harness.env.clock.advance(25_000)
		reconnect(harness)

		assertEquals(0, harness.queuedForRetry)
		assertEquals(1, harness.env.broadcaster.sent.size)
		val row = assertSettledInPlace(queued)
		assertEquals("sent from queue", row.status)
		assertEquals(harness.transactionIds.single(), row.txId)
		assertEquals(0, unsettled(rows()))
	}

	@Test
	fun `accepted-unconfirmed from the queue updates the same row`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		val queued = queuedOffline(harness)
		harness.env.broadcaster.defaultResult =
			HiveRpc.BroadcastResult.AcceptedUnconfirmed("tx", "node", "confirmation unavailable")

		harness.env.clock.advance(25_000)
		reconnect(harness)

		val row = assertSettledInPlace(queued)
		assertEquals("accepted — confirmation unavailable", row.status)
		assertNotNull(row.txId)
		assertEquals(0, harness.queuedForRetry)
		assertTrue(row.acceptedUnconfirmed)
		assertEquals("accepted-unconfirmed was presented as confirmed", 1, unsettled(rows()))
	}

	@Test
	fun `a queued listen the chain rejects updates the same row and is not shown as on-chain`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		val queued = queuedOffline(harness)
		harness.env.broadcaster.preparationFailure = HiveRpc.BroadcastResult.Rejected("missing posting authority")

		harness.env.clock.advance(25_000)
		reconnect(harness)

		val row = assertSettledInPlace(queued)
		assertTrue(row.status.startsWith(FinalizationRuntime.ScrobbleRecord.QUEUE_FAILED_PERMANENTLY))
		assertTrue(row.queueFailed)
		assertNull(row.txId)
		assertEquals(0, harness.env.broadcaster.sent.size)
		assertEquals("a rejected queued send was presented as on-chain", 1, unsettled(rows()))
	}

	@Test
	fun `a queued listen that exhausts its attempts updates the same row and is not shown as on-chain`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		val queued = queuedOffline(harness)
		harness.env.broadcaster.preparationFailure = offline

		repeat(8) {
			harness.env.clock.advance(2 * 60 * 60_000L)
			FinalizationRuntime.flushQueue()
		}

		assertEquals(0, harness.queuedForRetry)
		val row = assertSettledInPlace(queued)
		assertTrue(row.status.startsWith(FinalizationRuntime.ScrobbleRecord.QUEUE_ATTEMPTS_EXHAUSTED))
		assertTrue(row.queueFailed)
		assertEquals(1, unsettled(rows()))
	}

	@Test
	fun `an ambiguous in-flight send reconciled on chain updates the same row`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		FinalizationRuntime.restoreFrom(CodecStore())
		harness.env.facts.put(facts())
		// Signed and sent; the answer was lost. The exact transaction is durable.
		harness.env.broadcaster.willReturn(offline)
		harness.feed(watching() + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized())
		val queued = rows().single()
		assertTrue(queued.queued)
		val txId = harness.env.retryQueue.all.single().preparedTransactionId!!

		// The chain has it after all.
		harness.env.broadcaster.willObserve(txId, HiveRpc.TransactionEvidence.BLOCK)
		harness.env.clock.advance(25_000)
		reconnect(harness)

		assertEquals(0, harness.queuedForRetry)
		assertEquals("reconciliation re-broadcast", 1, harness.env.broadcaster.sent.size)
		assertEquals(1, harness.env.broadcaster.prepared.size)
		val row = assertSettledInPlace(queued)
		assertTrue(row.status.startsWith("reconciled after ambiguous retry"))
		assertEquals(txId, row.txId)
		assertEquals(0, unsettled(rows()))
	}

	@Test
	fun `the operation id and the settled row survive a restart`() {
		val store = CodecStore()
		val harness = ReplayHarness(ReplaySource.BRAVE)
		val queued = queuedOffline(harness, store)
		assertTrue(
			"the queued row's operation id was not saved",
			store.historyBytes!!.contains(queued.queueOperationId!!),
		)

		// Restart while still queued: the id comes back and the send still finds the row.
		harness.feed(PlaybackEvent.ProcessRestarted)
		FinalizationRuntime.restoreFrom(store)
		assertEquals(queued, rows().single())

		harness.env.clock.advance(25_000)
		reconnect(harness)
		val settled = assertSettledInPlace(queued)

		harness.feed(PlaybackEvent.ProcessRestarted)
		FinalizationRuntime.restoreFrom(store)
		assertEquals(listOf(settled), rows())
		assertEquals(0, unsettled(rows()))
	}

	@Test
	fun `rows saved before the field existed still load, and stay untouched`() {
		// An old stale row exactly as v0.12.1 wrote it: no queueOperationId key.
		val legacy = """[{"title":"Old Song","artist":"Old Artist","percent":100,"at":1790000000,""" +
			""""status":"queued — offline","tx":null,"queued":true,"videoId":"$videoId",""" +
			""""eventId":"legacy-event","account":"rustedwax-replay"}]"""
		val restored = RetainedRecordCodec.decodeHistory(legacy).single()
		assertEquals("legacy-event", restored.eventId)
		assertNull(restored.queueOperationId)
		assertTrue(restored.queued)

		// A new queued listen of the same video settles its own row only; the
		// legacy row is never matched by video, account or time.
		val store = CodecStore(legacy)
		val harness = ReplayHarness(ReplaySource.BRAVE)
		FinalizationRuntime.restoreFrom(store)
		harness.env.facts.put(facts())
		harness.env.broadcaster.preparationFailure = offline
		harness.feed(watching() + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized())
		harness.env.broadcaster.preparationFailure = null
		harness.env.clock.advance(25_000)
		reconnect(harness)

		assertEquals(2, rows().size)
		assertEquals("sent from queue", rows()[0].status)
		assertEquals("the legacy row was altered", restored, rows().single { it.eventId == "legacy-event" })
		assertEquals("legacy stale rows are left to age out", 1, unsettled(rows()))
		// And a row without the key is written back without it.
		assertFalse(RetainedRecordCodec.encodeHistory(listOf(restored)).contains("queueOperationId"))
	}

	@Test
	fun `two queued listens of the same video settle independently`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		FinalizationRuntime.restoreFrom(CodecStore())
		harness.env.facts.put(facts())
		harness.env.broadcaster.preparationFailure = offline
		harness.feed(watching() + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized())
		harness.env.clock.advance(120_000)
		harness.feed(watching() + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized())
		harness.env.broadcaster.preparationFailure = null
		assertEquals("two listens were not queued", 2, harness.queuedForRetry)
		val (second, first) = rows()
		assertTrue(first.queued && second.queued)
		assertTrue(first.queueOperationId != second.queueOperationId)

		// One goes out, the other is still unreachable.
		harness.env.broadcaster.willReturn(
			HiveRpc.BroadcastResult.Success("tx", "node", HiveRpc.BroadcastResult.Evidence.BLOCK),
			offline,
		)
		harness.env.clock.advance(25_000)
		reconnect(harness)

		assertEquals(2, rows().size)
		val sentRow = rows().single { !it.queued }
		val waitingRow = rows().single { it.queued }
		assertEquals(setOf(first.eventId, second.eventId), setOf(sentRow.eventId, waitingRow.eventId))
		assertEquals("sent from queue", sentRow.status)
		assertEquals(1, unsettled(rows()))

		// The other one later.
		harness.env.clock.advance(2 * 60 * 60_000L)
		FinalizationRuntime.flushQueue()
		assertEquals(0, harness.queuedForRetry)
		assertEquals(2, rows().size)
		assertTrue(rows().none { it.queued })
		assertEquals(setOf(first.eventId, second.eventId), rows().map { it.eventId }.toSet())
		assertEquals(2, harness.env.broadcaster.sent.map { it.txId }.toSet().size)
		assertEquals(0, unsettled(rows()))
	}

	@Test
	fun `another account's row with the same operation id is never updated`() {
		val foreign = FinalizationRuntime.ScrobbleRecord(
			title = "Someone Else's Song",
			artist = null,
			percentPlayed = 100,
			atEpochSec = 1_790_000_000,
			status = "queued — offline",
			txId = null,
			queued = true,
			videoId = videoId,
			eventId = "foreign-event",
			account = "someone-else",
			// Deliberately the id the replay queue will mint for this account's listen.
			queueOperationId = "operation-1",
		)
		val store = CodecStore(RetainedRecordCodec.encodeHistory(listOf(foreign)))
		val harness = ReplayHarness(ReplaySource.BRAVE)
		val queued = queuedOffline(harness, store)
		assertEquals("operation-1", queued.queueOperationId)
		assertEquals(2, FinalizationRuntime.recent.value.size)

		harness.env.clock.advance(25_000)
		reconnect(harness)

		val all = FinalizationRuntime.recent.value
		assertEquals(2, all.size)
		assertEquals("another account's row was updated", foreign, all.single { it.eventId == "foreign-event" })
		val mine = all.single { it.eventId == queued.eventId }
		assertEquals("sent from queue", mine.status)
		assertFalse(mine.queued)
		// And each viewer sees only their own row.
		assertEquals(listOf(mine), FinalizationRuntime.recentFor(all, "rustedwax-replay"))
		assertEquals(listOf(foreign), FinalizationRuntime.recentFor(all, "someone-else"))
	}

	@Test
	fun `an outcome whose row has aged out is still recorded as a new row`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		queuedOffline(harness)

		// The process restarts with nothing retained: the queued row is gone,
		// the durable queue entry is not.
		harness.feed(PlaybackEvent.ProcessRestarted)
		FinalizationRuntime.restoreFrom(CodecStore())
		assertTrue(rows().isEmpty())
		assertEquals(1, harness.queuedForRetry)

		harness.env.clock.advance(25_000)
		reconnect(harness)

		val row = rows().single()
		assertEquals("sent from queue", row.status)
		assertFalse(row.queued)
		assertNotNull(row.txId)
		assertEquals(harness.env.retryQueue.all.size, 0)
	}

	@Test
	fun `a Snap draft keyed on the queued row stays attached after it settles`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		val queued = queuedOffline(harness)
		val draftKey = SnapDraftKey.of(queued.account, queued.eventId)

		harness.env.clock.advance(25_000)
		reconnect(harness)

		val settled = rows().single()
		assertEquals(
			"the Snap draft/post key moved to another row",
			draftKey,
			SnapDraftKey.of(settled.account, settled.eventId),
		)
	}

	/** An IN_FLIGHT entry whose History row is gone; the chain has the transaction. */
	private fun reconcileWithRowMissing(evidence: HiveRpc.TransactionEvidence) {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		FinalizationRuntime.restoreFrom(CodecStore())
		harness.env.facts.put(facts())
		// Signed and sent; the answer was lost. The exact transaction is durable.
		harness.env.broadcaster.willReturn(offline)
		harness.feed(watching() + PlaybackEvent.Advance(54_000) + PlaybackEvent.Finalized())
		val entry = harness.env.retryQueue.all.single()
		assertEquals(com.rustedwax.app.scrobble.BroadcastQueue.State.IN_FLIGHT, entry.state)
		val txId = entry.preparedTransactionId!!
		assertEquals(1, harness.env.broadcaster.sent.size)
		assertEquals(1, harness.env.broadcaster.prepared.size)

		// The queued row ages out: a new process with nothing retained. The
		// durable IN_FLIGHT entry is still there.
		harness.feed(PlaybackEvent.ProcessRestarted)
		FinalizationRuntime.restoreFrom(CodecStore())
		assertTrue(rows().isEmpty())
		assertEquals(1, harness.queuedForRetry)

		harness.env.broadcaster.willObserve(txId, evidence)
		harness.env.clock.advance(25_000)
		reconnect(harness)

		// Queue truth: settled. Chain truth: nothing new signed or broadcast.
		assertEquals(0, harness.queuedForRetry)
		assertEquals("reconciliation re-broadcast", 1, harness.env.broadcaster.sent.size)
		assertEquals("reconciliation signed a replacement", 1, harness.env.broadcaster.prepared.size)
		// History: exactly one outcome, for the transaction the chain has.
		val row = rows().single()
		assertTrue(row.status.startsWith("reconciled after ambiguous retry"))
		assertEquals(txId, row.txId)
		assertFalse(row.queued)
		assertEquals(entry.operationId, row.queueOperationId)
		assertEquals(videoId, row.videoId)
		assertEquals(0, unsettled(rows()))
	}

	@Test
	fun `an in-flight send reconciled in a block is recorded even when its queued row is gone`() {
		reconcileWithRowMissing(HiveRpc.TransactionEvidence.BLOCK)
	}

	@Test
	fun `an in-flight send reconciled in the mempool is recorded even when its queued row is gone`() {
		reconcileWithRowMissing(HiveRpc.TransactionEvidence.MEMPOOL)
	}
}
