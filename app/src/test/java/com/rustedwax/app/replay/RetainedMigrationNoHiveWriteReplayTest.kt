package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.ConnectivityRetryTrigger
import com.rustedwax.app.scrobble.DatabaseRetainedRecords
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.app.scrobble.InMemoryRetainedRecordRows
import com.rustedwax.app.scrobble.InMemorySharedPreferences
import com.rustedwax.app.scrobble.RetainedRecordCodec
import com.rustedwax.app.scrobble.SharedPreferencesRetainedRecords
import com.rustedwax.hive.HiveRpc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Issue #41, against the production engine with the replay ports installed.
 *
 *  - migrating and restoring History and Not Logged writes nothing to Hive,
 *    even for rows that say a listen is still queued;
 *  - a queue outcome that lands while the database is still opening is live
 *    state, and the database's older copy of that row cannot roll it back.
 */
class RetainedMigrationNoHiveWriteReplayTest : ReplayScenarioTest() {

	private val videoId = "_OBlgSz8sSM"
	private val title = "Synthetic Queued Song"
	private val channel = "SyntheticChannel"

	private fun row(eventId: String, queued: Boolean, op: String?) = FinalizationRuntime.ScrobbleRecord(
		title = "Synthetic $eventId",
		artist = null,
		percentPlayed = 95,
		atEpochSec = 1_700_000_000L,
		status = if (queued) "queued — offline" else "accepted — confirmation unavailable",
		txId = if (queued) null else "tx-$eventId",
		queued = queued,
		videoId = videoId,
		eventId = eventId,
		account = "rustedwax-replay",
		queueOperationId = op,
	)

	@Test
	fun `migration and restore broadcast, prepare and enqueue nothing`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		val prefs = InMemorySharedPreferences()
		SharedPreferencesRetainedRecords(prefs).writeHistory(
			RetainedRecordCodec.encodeHistory(
				listOf(row("queued-1", queued = true, op = "op-1"), row("sent-1", queued = false, op = null)),
			),
			0,
		)
		SharedPreferencesRetainedRecords(prefs).writeSkipped(
			"""[{"title":"declined","artist":null,"reason":"offline","at":1,"played":3,""" +
				""""duration":null,"videoId":null,"account":"rustedwax-replay"}]""",
			0,
		)
		val rows = InMemoryRetainedRecordRows()
		val queueBefore = harness.queuedForRetry

		repeat(2) {
			FinalizationRuntime.restoreRetained(SharedPreferencesRetainedRecords(prefs), { rows }, { it.run() })
		}

		assertEquals(setOf("queued-1", "sent-1"), FinalizationRuntime.recent.value.map { it.eventId }.toSet())
		assertEquals(1, FinalizationRuntime.skipped.value.size)
		assertEquals(2, rows.allHistory.size)
		assertTrue(harness.env.broadcaster.sent.isEmpty())
		assertTrue(harness.env.broadcaster.prepared.isEmpty())
		assertTrue(harness.broadcasts.isEmpty())
		assertEquals(queueBefore, harness.queuedForRetry)
		assertEquals(0, harness.env.retryQueue.all.size)
		assertEquals(0, FinalizationRuntime.queueSize.value)
	}

	@Test
	fun `a queue outcome during the database phase is not rolled back by the stored copy`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(
			VideoFacts(
				videoId = videoId,
				title = title,
				author = channel,
				lengthSeconds = 56,
				category = "Music",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
		// A listen queued offline: the row says "queued", the queue holds it.
		harness.env.broadcaster.preparationFailure = HiveRpc.BroadcastResult.NetworkFailure("Unable to resolve host")
		harness.feed(
			listOf(
				PlaybackEvent.NotificationObserved(host = "youtube.com", title = title),
				PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = videoId),
				PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = 56_000),
				PlaybackEvent.PlaybackStateChanged(playing = true),
				PlaybackEvent.Advance(54_000),
				PlaybackEvent.Finalized(),
			),
		)
		harness.env.broadcaster.preparationFailure = null
		val queued = FinalizationRuntime.recent.value.single()
		assertTrue(queued.queued)

		// Both stores hold that queued row, as the previous process left them.
		val prefs = InMemorySharedPreferences()
		val rows = InMemoryRetainedRecordRows()
		val blob = RetainedRecordCodec.encodeHistory(listOf(queued))
		SharedPreferencesRetainedRecords(prefs).writeHistory(blob, 1)
		DatabaseRetainedRecords.writeHistory(rows, listOf(queued), blob, 1, 1L)

		// Startup: the legacy restore is on screen, the database is still opening.
		val held = ArrayDeque<Runnable>()
		FinalizationRuntime.restoreRetained(SharedPreferencesRetainedRecords(prefs), { rows }, Executor { held.addLast(it) })

		// The network comes back inside that window and the queue settles the row.
		harness.env.clock.advance(25_000)
		ConnectivityRetryTrigger(
			queueDepth = { FinalizationRuntime.queueSize.value },
			flush = { FinalizationRuntime.flushQueue() },
			nowMs = { harness.env.clock.nowMillis() },
		).onUsableNetwork()
		val settled = FinalizationRuntime.recent.value.single()
		assertEquals(queued.eventId, settled.eventId)
		assertFalse(settled.queued)

		// Now the database answers, still holding the queued copy.
		while (held.isNotEmpty()) held.removeFirst().run()

		assertEquals("the live outcome was rolled back", listOf(settled), FinalizationRuntime.recent.value)
		assertEquals("the database was not brought forward", listOf(settled), rows.allHistory)
		assertEquals(1, harness.env.broadcaster.sent.size)
	}
}
