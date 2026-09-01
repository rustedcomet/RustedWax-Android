package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.hive.HiveRpc
import com.rustedwax.app.replay.ReplayHarness.RefusalKind
import com.rustedwax.app.scrobble.FinalizationRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleReplayTest : ReplayScenarioTest() {

	private fun facts(videoId: String, title: String, author: String, lengthSeconds: Long) =
		VideoFacts(
			videoId = videoId,
			title = title,
			author = author,
			lengthSeconds = lengthSeconds,
			category = "Music",
			watchPageResolved = true,
			isUnlisted = false,
		)

	private fun watching(videoId: String, title: String, channel: String, durationMs: Long) = listOf(
		PlaybackEvent.NotificationObserved(host = "youtube.com", title = title),
		PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = videoId),
		PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = durationMs),
		PlaybackEvent.PlaybackStateChanged(playing = true),
	)

	// ---- MediaSession recreation ---------------------------------------------

	@Test
	fun `a media session rebuilt mid-listen produces one transaction and not two`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213))

		harness.feed(
			watching("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213_000)
				+ PlaybackEvent.Advance(100_000)
				+ PlaybackEvent.SessionRecreated
				+ PlaybackEvent.PlaybackStateChanged(playing = true)
				+ PlaybackEvent.Advance(100_000)
				+ PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		assertEquals(1, harness.transactionIds.size)
		// The measurement survived the rebuild: 200 s of 213 s, not 100 s.
		assertEquals(94, harness.broadcasts.single().percentPlayed)
	}

	@Test
	fun `the same listen finalized twice is claimed once and refused the second time`() {
		// The shape a rebuild produces when both halves finalize: the ledger is
		// the only thing standing between that and a duplicate on a chain
		// nothing can edit.
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213))

		harness.feed(
			watching("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213_000)
				+ PlaybackEvent.Advance(200_000)
				+ PlaybackEvent.Finalized(),
		)
		assertEquals(1, harness.transactionIds.size)

		harness.refinalizeLastListen()

		assertEquals(1, harness.transactionIds.size)
		assertEquals(1, harness.env.broadcaster.sent.size)
		assertEquals(1, harness.duplicateAttempts.size)
		assertTrue(RefusalKind.ALREADY_SCROBBLED in harness.refusalKinds)
	}

	@Test
	fun `playing the same video again later is a new listen and not a duplicate`() {
		// The other side of the same coin. The dedup key is content plus the
		// frozen start, so a genuine second sitting earns its own entry — which
		// is what makes the key safe to be as strict as it is.
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213))

		repeat(2) {
			harness.feed(
				watching("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213_000)
					+ PlaybackEvent.Advance(200_000)
					+ PlaybackEvent.Finalized(),
			)
		}

		assertEquals(2, harness.transactionIds.size)
		assertEquals(2, harness.transactionIds.toSet().size)
		assertEquals(emptyList<String>(), harness.duplicateAttempts)
	}

	// ---- process restart ------------------------------------------------------

	@Test
	fun `a process restart forgets memory evidence but keeps the durable ledger`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213))

		harness.feed(
			watching("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213_000)
				+ PlaybackEvent.Advance(200_000)
				+ PlaybackEvent.Finalized(),
		)
		val claimsBefore = harness.env.claims.claimed
		assertEquals(1, harness.broadcasts.size)

		// Android kills and rebuilds the process; the URL and notification stores
		// are memory and go with it. The ledger is on disk and does not.
		harness.feed(PlaybackEvent.ProcessRestarted)
		harness.feed(
			watching("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213_000)
				+ PlaybackEvent.Advance(200_000)
				+ PlaybackEvent.Finalized(),
		)

		// The second listen has its own frozen start, so it is a new listen and a
		// new claim — a replay minutes later is not a duplicate.
		assertEquals(2, harness.broadcasts.size)
		assertNotEquals(claimsBefore, harness.env.claims.claimed)
		assertEquals(emptyList<String>(), harness.duplicateAttempts)
	}

	@Test
	fun `a process restart clears run-local predecessor identity`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(
			facts("8RddqlctLnk", "First", "A Channel", 120),
			facts("FGjyRjd1_jQ", "Second", "A Channel", 120),
		)
		harness.env.identity.fromPredecessors = { _, _ ->
			error("a predecessor pair from the dead process must not be offered")
		}

		listOf("8RddqlctLnk" to "First", "FGjyRjd1_jQ" to "Second").forEach { (id, title) ->
			harness.feed(
				watching(id, title, "A Channel", 120_000) +
					PlaybackEvent.Advance(110_000) +
					PlaybackEvent.Finalized(),
			)
		}

		harness.feed(PlaybackEvent.ProcessRestarted)
		harness.feed(
			PlaybackEvent.NotificationObserved(host = "youtube.com"),
			PlaybackEvent.SessionMetadata(
				title = "Third",
				artist = "A Channel",
				durationMs = 120_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(110_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(2, harness.broadcasts.size)
		assertEquals(listOf(RefusalKind.NO_VERIFIED_VIDEO_ID), harness.terminalRefusalKinds)
		assertEquals(emptyList<ReplayHarness.Refusal>(), harness.refusals)
	}

	@Test
	fun `a process restart clears the old track URL notification and latched identity`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(
			facts("dQw4w9WgXcQ", "Old Track", "Old Channel", 120),
		)

		// Establish all run-local browser evidence, then kill the process before the
		// listen finalizes. None of it may identify the first session seen afterward.
		harness.feed(
			PlaybackEvent.NotificationObserved(host = "youtube.com", title = "Old Track"),
			PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = "dQw4w9WgXcQ"),
			PlaybackEvent.SessionMetadata(
				title = "Old Track",
				artist = "Old Channel",
				durationMs = 120_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(90_000),
			PlaybackEvent.ProcessRestarted,
			PlaybackEvent.SessionMetadata(
				title = "New Track",
				artist = "New Channel",
				durationMs = 120_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(90_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.finalized.size)
		assertEquals("New Track", harness.finalized.single().title)
		assertEquals(null, harness.finalized.single().confirmed)
		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(listOf("Refused"), harness.outcomeKinds)
	}

	@Test
	fun `a clearly synthetic 189-track one-engine soak gives every finalization one outcome`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)

		repeat(189) { index ->
			val videoId = index.toString().padStart(11, '0')
			val title = "Synthetic soak item $index"
			harness.env.facts.put(facts(videoId, title, "Synthetic Channel", 30))
			harness.feed(
				watching(videoId, title, "Synthetic Channel", 30_000) +
					PlaybackEvent.Advance(30_000) +
					PlaybackEvent.Finalized(),
			)
		}

		assertEquals(189, harness.finalized.size)
		assertEquals(189, harness.broadcasts.size)
		assertEquals(189, harness.env.broadcaster.sent.size)
		// The user-facing history is intentionally bounded under the same load.
		assertEquals(50, harness.transactionIds.size)
		assertEquals(emptyList<RefusalKind>(), harness.refusalKinds)
		assertEquals(emptyList<String>(), harness.duplicateAttempts)
	}

	// ---- multiple browser tabs ------------------------------------------------

	@Test
	fun `closing one tab does not erase the evidence the other tab is playing on`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts("aBcDeFgHiJk", "Playing Tab Song", "A Channel", 200))

		harness.feed(
			watching("aBcDeFgHiJk", "Playing Tab Song", "A Channel", 200_000)
				+ PlaybackEvent.Advance(60_000)
				// A second tab is opened and then closed. The bar describes it
				// while it is in front; the notification for *this* session is
				// untouched.
				+ PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = "otherTabVid")
				+ PlaybackEvent.Advance(10_000)
				+ PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = "aBcDeFgHiJk")
				+ PlaybackEvent.Advance(130_000)
				+ PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		assertEquals("aBcDeFgHiJk", harness.broadcasts.single().videoId)
	}

	@Test
	fun `an id disproved while the track was active is never broadcast for it`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts("wrongIdAaaa", "A Completely Different Video", "Other", 600))

		harness.feed(
			watching("wrongIdAaaa", "Playing Tab Song", "A Channel", 200_000)
				+ PlaybackEvent.Advance(60_000)
				+ PlaybackEvent.VideoIdRejected("wrongIdAaaa")
				+ PlaybackEvent.Advance(130_000)
				+ PlaybackEvent.Finalized(),
		)

		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		// It is refused *and explained* in the terminal outcome. The disproved id
		// cannot authorize a hyperlink, so no dead Not-logged row is created.
		assertEquals(listOf(RefusalKind.NO_VERIFIED_VIDEO_ID), harness.terminalRefusalKinds)
		assertEquals(emptyList<ReplayHarness.Refusal>(), harness.refusals)
	}

	// ---- dispatch outcomes ----------------------------------------------------

	@Test
	fun `an offline broadcast is queued rather than lost`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213))
		harness.env.broadcaster.willReturn(HiveRpc.BroadcastResult.NetworkFailure("no route to host"))

		harness.feed(
			watching("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213_000)
				+ PlaybackEvent.Advance(200_000)
				+ PlaybackEvent.Finalized(),
		)

		// It reached the broadcaster seam — the payload exists — and it is owed.
		assertEquals(1, harness.env.broadcaster.sent.size)
		assertEquals(1, harness.queuedForRetry)
		assertEquals(emptyList<String>(), harness.transactionIds)
	}

	@Test
	fun `a rejected broadcast is dropped rather than retried forever`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213))
		harness.env.broadcaster.willReturn(HiveRpc.BroadcastResult.Rejected("missing posting authority"))

		harness.feed(
			watching("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213_000)
				+ PlaybackEvent.Advance(200_000)
				+ PlaybackEvent.Finalized(),
		)

		assertEquals(0, harness.queuedForRetry)
		assertEquals(emptyList<String>(), harness.transactionIds)
	}

	@Test
	fun `with no key saved nothing reaches the broadcaster and nothing is queued`() {
		val harness = ReplayHarness(
			ReplaySource.BRAVE,
			ReplayEnvironment(posting = ReplayPostingIdentity(wif = null)),
		)
		harness.env.facts.put(facts("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213))

		harness.feed(
			watching("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213_000)
				+ PlaybackEvent.Advance(200_000)
				+ PlaybackEvent.Finalized(),
		)

		assertEquals(emptyList<RecordingBroadcaster.Sent>(), harness.env.broadcaster.sent)
		assertEquals(0, harness.queuedForRetry)
	}

	// ---- the global switches --------------------------------------------------

	@Test
	fun `earned auto scrobbles request prompt continuation disposition`() {
		ReplayHarness(
			ReplaySource.NATIVE_YOUTUBE_MUSIC,
			ReplayEnvironment(policy = ReplayPolicy(scrobbleThreshold = 0.6)),
		)

		assertFalse(
			FinalizationRuntime.shouldFinalizeContinuationPromptly(
				playedMs = 179_540, // one millisecond below the exact 60% boundary
				durationMs = 299_235,
			),
		)
		assertTrue(
			FinalizationRuntime.shouldFinalizeContinuationPromptly(
				playedMs = 179_541,
				durationMs = 299_235,
			),
		)
		assertTrue(
			FinalizationRuntime.shouldFinalizeContinuationPromptly(
				playedMs = 199_165,
				durationMs = 299_235,
			),
		)
	}

	@Test
	fun `auto scrobble off never shortens the continuation window`() {
		ReplayHarness(
			ReplaySource.NATIVE_YOUTUBE_MUSIC,
			ReplayEnvironment(policy = ReplayPolicy(autoScrobble = false)),
		)

		assertFalse(
			FinalizationRuntime.shouldFinalizeContinuationPromptly(
				playedMs = 199_165,
				durationMs = 299_235,
			),
		)
	}

	@Test
	fun `auto-scrobble off records nothing at all, not even a refusal`() {
		val harness = ReplayHarness(
			ReplaySource.BRAVE,
			ReplayEnvironment(policy = ReplayPolicy(autoScrobble = false)),
		)
		harness.env.facts.put(facts("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213))

		harness.feed(
			watching("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213_000)
				+ PlaybackEvent.Advance(200_000)
				+ PlaybackEvent.Finalized(),
		)

		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		// A hundred "auto-scrobble off" rows explain nothing the switch is not
		// already saying.
		assertEquals(emptyList<RefusalKind>(), harness.refusalKinds)
	}

	@Test
	fun `monitoring stopped loses a finalize that races the Stop`() {
		val harness = ReplayHarness(
			ReplaySource.BRAVE,
			ReplayEnvironment(policy = ReplayPolicy(monitoringEnabled = false)),
		)
		harness.env.facts.put(facts("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213))

		harness.feed(
			watching("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213_000)
				+ PlaybackEvent.Advance(200_000)
				+ PlaybackEvent.Finalized(),
		)

		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(emptyList<RefusalKind>(), harness.refusalKinds)
	}

	// ---- the mute list --------------------------------------------------------

	@Test
	fun `a muted video is refused after identity and before any signing`() {
		val harness = ReplayHarness(
			ReplaySource.BRAVE,
			ReplayEnvironment(mutes = ReplayMuteList(mapOf("dQw4w9WgXcQ" to "Never Gonna Give You Up"))),
		)
		harness.env.facts.put(facts("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213))

		harness.feed(
			watching("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213_000)
				+ PlaybackEvent.Advance(200_000)
				+ PlaybackEvent.Finalized(),
		)

		assertEquals(emptyList<RecordingBroadcaster.Sent>(), harness.env.broadcaster.sent)
		assertEquals(listOf(RefusalKind.MUTED), harness.refusalKinds)
		// The row can still be a link and still show a thumbnail — v0.11.0 §7.
		assertEquals("dQw4w9WgXcQ", harness.refusals.single().videoId)
	}
}
