package com.rustedwax.app.replay

import com.rustedwax.app.detect.NativeSourceSwitches
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.hive.HiveScrobblePayload
import com.rustedwax.app.scrobble.FinalizationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalOutcomeReplayTest : ReplayScenarioTest() {

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
		// A readable position, because a real transport publishes one. It matters
		// for the repeat-listen scenario below: since the `Cry Baby` fix, a second
		// transaction requires progress that a position corroborated, and a
		// fixture that never reported one would be describing an abandoned tab
		// rather than a listen.
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
	)

	private val song = Triple("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO")

	private fun ReplayHarness.playSong(playedMs: Long = 200_000) = feed(
		watching(song.first, song.second, song.third, 213_000)
			+ PlaybackEvent.Advance(playedMs)
			+ PlaybackEvent.Finalized(),
	)

	private fun outcomeOf(harness: ReplayHarness): FinalizationOutcome =
		harness.outcomes.single().outcome

	// ---- Ignored: the deliberately silent boundaries -------------------------

	@Test
	fun `monitoring stopped is Ignored and leaves no row and no broadcast`() {
		val harness = ReplayHarness(
			ReplaySource.BRAVE,
			ReplayEnvironment(policy = ReplayPolicy(monitoringEnabled = false)),
		)
		harness.env.facts.put(facts(song.first, song.second, song.third, 213))
		harness.playSong()

		assertEquals(FinalizationOutcome.Ignored("monitoring stopped"), outcomeOf(harness))
		assertEquals(emptyList<ReplayHarness.RefusalKind>(), harness.refusalKinds)
		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
	}

	@Test
	fun `auto-scrobble off is Ignored and leaves no row and no broadcast`() {
		val harness = ReplayHarness(
			ReplaySource.BRAVE,
			ReplayEnvironment(policy = ReplayPolicy(autoScrobble = false)),
		)
		harness.env.facts.put(facts(song.first, song.second, song.third, 213))
		harness.playSong()

		assertEquals(FinalizationOutcome.Ignored("auto-scrobble off"), outcomeOf(harness))
		assertEquals(emptyList<ReplayHarness.RefusalKind>(), harness.refusalKinds)
		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
	}

	@Test
	fun `a source that is not watched is Ignored rather than refused`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(facts(song.first, song.second, song.third, 213))
		// The user has the native surface switched off. The snapshot is frozen as
		// a non-target and must not reach any policy at all.
		NativeSourceSwitches.configureForReplay(
			NativeSourceSwitches.Config(
				youTubeScrobbling = true,
				youtubeEnabled = false,
				youtubeMusicEnabled = false,
			),
		)
		harness.feed(
			PlaybackEvent.SessionMetadata(
				title = song.second,
				artist = song.third,
				durationMs = 213_000,
				mediaId = song.first,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(200_000),
			PlaybackEvent.Finalized(),
		)

		assertTrue(outcomeOf(harness) is FinalizationOutcome.Ignored)
		assertEquals(emptyList<ReplayHarness.RefusalKind>(), harness.refusalKinds)
	}

	@Test
	fun `a listen whose source epoch moved since it froze is Ignored, not refused`() {
		// A listener reconnect or a Stop bumps the package's epoch without
		// necessarily disabling the surface. The snapshot froze under the old
		// generation, so it must be discarded rather than refused: an opt-out is
		// not allowed to *cause* a Not-logged row about a listen the user asked
		// the app to forget.
		//
		// Presented via the frozen snapshot rather than by flipping the switch
		// mid-trace, because flipping it before the freeze changes what the
		// snapshot *is* — it would be frozen as a non-target, which is the
		// different boundary tested above.
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(facts(song.first, song.second, song.third, 213))
		harness.feed(
			PlaybackEvent.SessionMetadata(
				title = song.second,
				artist = song.third,
				durationMs = 213_000,
				mediaId = song.first,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(200_000),
			PlaybackEvent.Finalized(),
		)
		assertTrue(outcomeOf(harness) is FinalizationOutcome.Eligible)

		// Same surface, still opted in, new generation.
		NativeSourceSwitches.configureForReplay(
			NativeSourceSwitches.Config(
				youTubeScrobbling = true,
				youtubeEnabled = true,
				youtubeMusicEnabled = true,
				youtubeEpoch = 2,
				youtubeMusicEpoch = 2,
			),
		)
		harness.refinalizeLastListen()

		assertEquals(
			FinalizationOutcome.Ignored("finalized after its opt-in/lifecycle boundary"),
			harness.outcomes.last().outcome,
		)
		// Discarded before dedup, so it never even attempts a claim.
		assertEquals(emptyList<String>(), harness.duplicateAttempts)
		assertEquals(1, harness.env.broadcaster.sent.size)
	}

	@Test
	fun `a source switched off while resolution was still queued is Ignored`() {
		// The one guard that only exists for a race: the launched block re-checks
		// the epoch because the user can turn a surface off in the gap between the
		// synchronous prefilter and the asynchronous remainder. Under an inline
		// dispatcher there is no gap, so the branch would be untestable exactly
		// because it is about timing. `DeferredDispatcher` makes the gap real
		// without making it a race.
		val dispatcher = DeferredDispatcher()
		val harness = ReplayHarness(
			ReplaySource.NATIVE_YOUTUBE,
			dispatcher = dispatcher,
		)
		harness.env.facts.put(facts(song.first, song.second, song.third, 213))

		harness.feed(
			PlaybackEvent.SessionMetadata(
				title = song.second,
				artist = song.third,
				durationMs = 213_000,
				mediaId = song.first,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(200_000),
			PlaybackEvent.Finalized(),
		)

		// The synchronous half passed and the rest is waiting.
		assertEquals(0, harness.outcomes.size)
		assertTrue(dispatcher.queued > 0)

		NativeSourceSwitches.configureForReplay(
			NativeSourceSwitches.Config(
				youTubeScrobbling = false,
				youtubeEnabled = false,
				youtubeMusicEnabled = false,
			),
		)
		dispatcher.drain()

		assertEquals(
			FinalizationOutcome.Ignored("source generation changed before resolution"),
			outcomeOf(harness),
		)
		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
	}

	@Test
	fun `a listen with no posting key saved is Ignored and nothing is queued`() {
		val harness = ReplayHarness(
			ReplaySource.BRAVE,
			ReplayEnvironment(posting = ReplayPostingIdentity(wif = null)),
		)
		harness.env.facts.put(facts(song.first, song.second, song.third, 213))
		harness.playSong()

		assertEquals(FinalizationOutcome.Ignored("no posting key saved"), outcomeOf(harness))
		assertEquals(emptyList<RecordingBroadcaster.Sent>(), harness.env.broadcaster.sent)
		assertEquals(0, harness.queuedForRetry)
	}

	// ---- Refused: processed and declined -------------------------------------

	@Test
	fun `a below-threshold listen is Refused exactly once`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.playSong(playedMs = 60_000)

		val outcome = outcomeOf(harness)
		assertTrue(outcome is FinalizationOutcome.Refused)
		assertTrue((outcome as FinalizationOutcome.Refused).reason.contains("below 60% threshold"))
		assertEquals(listOf(ReplayHarness.RefusalKind.BELOW_THRESHOLD), harness.refusalKinds)
	}

	@Test
	fun `a non-YouTube source is Refused even though it is never listed`() {
		// The outcome and the disclosure are different questions. §4.1 keeps a
		// page watched somewhere else out of Not logged; the engine still refused
		// it, and the audit gate is about the decision rather than the row.
		val harness = ReplayHarness(ReplaySource.CHROME)
		harness.feed(
			PlaybackEvent.NotificationObserved(host = "soundcloud.com", title = "Some Mix"),
			PlaybackEvent.SessionMetadata(title = "Some Mix", artist = "A DJ", durationMs = 600_000),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(500_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(
			FinalizationOutcome.Refused("source not proven YouTube"),
			outcomeOf(harness),
		)
		assertEquals(emptyList<ReplayHarness.RefusalKind>(), harness.refusalKinds)
	}

	@Test
	fun `a sub-three-second transition is Refused even though it is too small to list`() {
		// `MIN_NOTABLE_PLAYED_MS` suppresses the row so a page load swapping a
		// placeholder title does not bury the entries that matter. It must not
		// also suppress the outcome, or those finalizations would decide nothing.
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.playSong(playedMs = 2_000)

		assertTrue(outcomeOf(harness) is FinalizationOutcome.Refused)
		assertEquals(emptyList<ReplayHarness.RefusalKind>(), harness.refusalKinds)
	}

	@Test
	fun `a duplicate finalization is Refused while the first stays Eligible`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts(song.first, song.second, song.third, 213))
		harness.playSong()
		harness.refinalizeLastListen()

		assertEquals(listOf("Eligible", "Refused"), harness.outcomeKinds)
		assertEquals(1, harness.env.broadcaster.sent.size)
		assertEquals(1, harness.duplicateAttempts.size)
	}

	@Test
	fun `a malformed resolved id that cannot build a payload is Refused exactly once`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		val malformedId = "not-an-id"
		harness.env.identity.search = {
			com.rustedwax.youtube.identity.VideoResolutionAttempt(
				resolution = com.rustedwax.youtube.identity.VideoResolution(
					videoId = malformedId,
					source = "scripted malformed resolver output",
					title = song.second,
					channel = song.third,
					lengthSeconds = 213,
					uniquelyResolved = true,
				),
			)
		}
		harness.env.facts.put(facts(malformedId, song.second, song.third, 213))
		harness.feed(
			PlaybackEvent.NotificationObserved(host = "youtube.com", title = song.second),
			PlaybackEvent.SessionMetadata(
				title = song.second,
				artist = song.third,
				durationMs = 213_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(200_000),
			PlaybackEvent.Finalized(),
		)

		assertTrue(outcomeOf(harness) is FinalizationOutcome.Refused)
		assertEquals(
			listOf(ReplayHarness.RefusalKind.PAYLOAD_NOT_BUILDABLE),
			harness.terminalRefusalKinds,
		)
		assertEquals(
			listOf(ReplayHarness.RefusalKind.PAYLOAD_NOT_BUILDABLE),
			harness.unlinkedRefusalKinds,
		)
		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
	}

	// ---- Eligible: the payload list ------------------------------------------

	@Test
	fun `an ordinary listen is Eligible with exactly one payload carrying the hyperlink`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts(song.first, song.second, song.third, 213))
		harness.playSong()

		val outcome = outcomeOf(harness)
		assertTrue(outcome is FinalizationOutcome.Eligible)
		val payloads = (outcome as FinalizationOutcome.Eligible).payloads
		assertEquals(1, payloads.size)
		assertEquals("https://www.youtube.com/watch?v=${song.first}", payloads.single().url)
		// The outcome's payload list is the list that reached the broadcaster.
		assertEquals(
			payloads.map { it.percentPlayed },
			harness.broadcasts.map { it.percentPlayed },
		)
	}

	@Test
	fun `a genuine double listen is one Eligible outcome carrying both payloads`() {
		// The repeat-listen rule: one tx at >=60%, a second at >=160%, songs only,
		// no loop evidence, not a Short. This is the case the original "exactly one
		// payload" wording would have outlawed.
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts(song.first, song.second, song.third, 213))
		// Genuine because the position proves it: 147 s in, a seek back to the start
		// and the song through again. Silence past the song's end proves nothing.
		harness.feed(
			watching(song.first, song.second, song.third, 213_000)
				+ PlaybackEvent.Advance(147_000)
				+ PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0)
				+ PlaybackEvent.Advance(213_000)
				+ PlaybackEvent.Finalized(),
		)

		val outcome = outcomeOf(harness)
		assertTrue(outcome is FinalizationOutcome.Eligible)
		val payloads = (outcome as FinalizationOutcome.Eligible).payloads
		assertEquals(2, payloads.size)
		assertEquals(HiveScrobblePayload.KIND_SONG, payloads.first().kind)
		// One outcome, two payloads, two transactions — not two outcomes.
		assertEquals(1, harness.outcomes.size)
		assertEquals(2, harness.env.broadcaster.sent.size)
		assertEquals(2, harness.transactionIds.size)
	}

	@Test
	fun `a listen the chain refused permanently is still Eligible`() {
		// Dispatch is not a finalization outcome. The engine found this listen
		// eligible and built its payload; what the network did afterwards is
		// recorded by the broadcaster and the retry queue, and folding it back in
		// would make a rejected send look like a policy refusal.
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts(song.first, song.second, song.third, 213))
		harness.env.broadcaster.willReturn(
			com.rustedwax.hive.HiveRpc.BroadcastResult.Rejected("missing posting authority"),
		)
		harness.playSong()

		assertTrue(outcomeOf(harness) is FinalizationOutcome.Eligible)
		assertEquals(1, harness.env.broadcaster.sent.size)
		assertEquals(emptyList<String>(), harness.transactionIds)
		assertEquals(0, harness.queuedForRetry)
	}

	@Test
	fun `an offline listen is Eligible and owed`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts(song.first, song.second, song.third, 213))
		harness.env.broadcaster.willReturn(
			com.rustedwax.hive.HiveRpc.BroadcastResult.NetworkFailure("no route to host"),
		)
		harness.playSong()

		assertTrue(outcomeOf(harness) is FinalizationOutcome.Eligible)
		assertEquals(1, harness.queuedForRetry)
	}

	// ---- the rule itself ------------------------------------------------------

	@Test
	fun `a long mixed run files one outcome per finalization and never two`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts(song.first, song.second, song.third, 213))

		// Eligible, Refused (threshold), Refused (dedup), Eligible again.
		harness.playSong()
		harness.playSong(playedMs = 30_000)
		harness.refinalizeLastListen()
		harness.playSong()

		assertEquals(4, harness.presentedToEngine)
		assertEquals(4, harness.outcomes.size)
		harness.assertOneOutcomePerFinalization()
		assertEquals(
			listOf("Eligible", "Refused", "Refused", "Eligible"),
			harness.outcomeKinds,
		)
	}
}
