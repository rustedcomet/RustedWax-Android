package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.FinalizationOutcome
import com.rustedwax.app.scrobble.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The harness's own guarantees, checked rather than assumed.
 *
 * Everything else in this package uses the replay harness to make claims about
 * `FinalizationRuntime`. This file makes claims about the harness — that a shadow run
 * cannot broadcast, that two identical traces produce identical outcomes, and
 * that one scenario cannot leak into the next. Without these the rest of the
 * corpus is a set of assertions with nothing underneath them.
 */
class ShadowSafetyReplayTest : ReplayScenarioTest() {

	private fun facts(videoId: String) = VideoFacts(
		videoId = videoId,
		title = "Rick Astley - Never Gonna Give You Up",
		author = "RickAstleyVEVO",
		lengthSeconds = 213,
		category = "Music",
		watchPageResolved = true,
		isUnlisted = false,
	)

	private val scrobbleableListen = listOf(
		PlaybackEvent.NotificationObserved(host = "youtube.com"),
		PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = "dQw4w9WgXcQ"),
		PlaybackEvent.SessionMetadata(
			title = "Rick Astley - Never Gonna Give You Up",
			artist = "RickAstleyVEVO",
			durationMs = 213_000,
		),
		PlaybackEvent.PlaybackStateChanged(playing = true),
		PlaybackEvent.Advance(200_000),
		PlaybackEvent.Finalized(),
	)

	// ---- shadow mode ----------------------------------------------------------

	/** The payloads the engine ruled eligible, as bytes. */
	private fun ReplayHarness.eligibleJson(): List<String> = outcomes
		.mapNotNull { it.outcome as? FinalizationOutcome.Eligible }
		.flatMap { eligible -> eligible.payloads.map { it.toJson() } }

	@Test
	fun `a shadow run builds the payload and produces no transaction and no queue`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.shadow = true
		harness.env.facts.put(facts("dQw4w9WgXcQ"))

		harness.feed(scrobbleableListen)

		// The decision was made and the payload was built — that is the output a
		// parity comparison needs. Read from the terminal outcome, because a shadow
		// run reaches no broadcaster to record it.
		assertEquals(1, harness.eligibleJson().size)
		assertTrue(harness.eligibleJson().single().contains("dQw4w9WgXcQ"))

		// And nothing exists anywhere that could later become a real scrobble.
		assertEquals(emptyList<String>(), harness.transactionIds)
		assertEquals(0, harness.queuedForRetry)
		assertEquals(emptyList<RecordingBroadcaster.Sent>(), harness.env.broadcaster.sent)
		assertEquals(emptyList<String>(), harness.env.claims.claimed)
	}

	@Test
	fun `a shadow run of the same trace agrees with the live run's payload`() {
		// The comparison the strangler migration is built on, exercised end to
		// end while both sides are still the same implementation. When Phase 2
		// introduces a second pipeline this is the assertion that changes shape
		// and nothing else does.
		val live = ReplayHarness(ReplaySource.BRAVE)
		live.env.facts.put(facts("dQw4w9WgXcQ"))
		live.feed(scrobbleableListen)

		val shadowed = ReplayHarness(ReplaySource.BRAVE)
		shadowed.shadow = true
		shadowed.env.facts.put(facts("dQw4w9WgXcQ"))
		shadowed.feed(scrobbleableListen)

		assertEquals(
			live.env.broadcaster.sent.map { it.json },
			shadowed.eligibleJson(),
		)
		// One payload for this representative finalized track on both sides. This
		// validates the comparator, not the global one-outcome invariant: the
		// existing engine has intentional silent early returns and repeat-listen
		// cases that can emit two payloads.
		assertEquals(1, live.finalized.size)
		assertEquals(1, live.env.broadcaster.sent.size)
		assertEquals(1, shadowed.eligibleJson().size)
	}

	@Test
	fun `a shadow run leaves no dedup claim that could block the live path`() {
		// Stated as its own scenario because it is the failure that would be
		// invisible: a shadow that claimed the ledger would silently suppress the
		// real scrobble, and the symptom would be a missing entry with no reason
		// anywhere.
		val shadowed = ReplayHarness(ReplaySource.BRAVE)
		shadowed.shadow = true
		shadowed.env.facts.put(facts("dQw4w9WgXcQ"))
		shadowed.feed(scrobbleableListen)
		assertEquals(emptyList<String>(), shadowed.env.claims.claimed)
		assertEquals(emptyList<String>(), shadowed.env.claims.duplicateAttempts)
	}

	@Test
	fun `a shadow run cannot suppress a later live run over the same durable state`() {
		// The test that actually bites. Running the live half against a fresh
		// environment proves nothing: it would pass even if the shadow had
		// claimed every key in the ledger, because the live half would not be
		// looking at that ledger. One environment, shadow first, live second.
		val env = ReplayEnvironment()
		val shadowed = ReplayHarness(ReplaySource.BRAVE, env)
		shadowed.shadow = true
		shadowed.env.facts.put(facts("dQw4w9WgXcQ"))
		shadowed.feed(scrobbleableListen)
		val shadowKey = shadowed.eligibleJson()

		// Nothing durable moved: no claim retained, nothing owed, nothing sent.
		assertEquals(emptyList<String>(), env.claims.claimed)
		assertEquals(0, env.retryQueue.size())
		assertEquals(emptyList<RecordingBroadcaster.Sent>(), env.broadcaster.sent)

		// Same environment, same ledger, same queue, and the same frozen listen
		// start — now for real. The start matters because it is part of the dedup
		// key; merely replaying the event shapes 200 seconds later would not test
		// whether the shadow claim could suppress this exact live finalization.
		env.clock.restore()
		val live = ReplayHarness(ReplaySource.BRAVE, env)
		live.feed(scrobbleableListen)

		assertEquals(1, live.transactionIds.size)
		assertEquals(emptyList<String>(), live.duplicateAttempts)
		assertEquals(1, env.claims.claimed.size)
		// The same listen on both sides, so the shadow half really was describing
		// the finalization the live half then performed.
		assertEquals(shadowKey, live.env.broadcaster.sent.map { it.json })
	}

	// ---- determinism ----------------------------------------------------------

	@Test
	fun `the same trace replayed twice produces byte-identical payloads`() {
		fun run(): List<String> {
			val harness = ReplayHarness(ReplaySource.BRAVE)
			harness.env.facts.put(facts("dQw4w9WgXcQ"))
			harness.feed(scrobbleableListen)
			return harness.env.broadcaster.sent.map { it.json }
		}

		val first = run()
		val second = run()

		assertEquals(1, first.size)
		// Includes the ISO timestamp, which is the field a wall-clock read would
		// make different every run — the reason the clock is a port at all.
		assertEquals(first, second)
	}

	@Test
	fun `advancing the replay clock changes the frozen listen start and nothing else`() {
		fun run(offsetMillis: Long): String {
			val env = ReplayEnvironment(clock = ReplayClock(ReplayClock.DEFAULT_START_MILLIS + offsetMillis))
			val harness = ReplayHarness(ReplaySource.BRAVE, env)
			harness.env.facts.put(facts("dQw4w9WgXcQ"))
			harness.feed(scrobbleableListen)
			return harness.env.broadcaster.sent.single().json
		}

		val base = run(0)
		val later = run(3_600_000)

		assertNotEquals(base, later)
		// Same everything except the moment it happened.
		assertEquals(
			base.replace(Regex("\"timestamp\":\"[^\"]+\""), ""),
			later.replace(Regex("\"timestamp\":\"[^\"]+\""), ""),
		)
	}

	// ---- isolation ------------------------------------------------------------

	@Test
	fun `a new harness starts with no history from the previous scenario`() {
		val first = ReplayHarness(ReplaySource.BRAVE)
		first.env.facts.put(facts("dQw4w9WgXcQ"))
		first.feed(scrobbleableListen)
		assertEquals(1, first.transactionIds.size)

		val second = ReplayHarness(ReplaySource.BRAVE)

		assertEquals(emptyList<String>(), second.transactionIds)
		assertEquals(emptyList<ReplayHarness.RefusalKind>(), second.refusalKinds)
		assertEquals(0, second.consecutiveTracksWithoutVideoId)
		assertEquals(0, second.queuedForRetry)
		assertEquals(emptyList<String>(), second.duplicateAttempts)
	}

	@Test
	fun `the engine reports itself ready only once replay ports are installed`() {
		FinalizationRuntime.resetForReplay()
		assertEquals(false, FinalizationRuntime.isReady)

		ReplayHarness(ReplaySource.BRAVE)

		assertEquals(true, FinalizationRuntime.isReady)
	}

	@Test
	fun `a finalize arriving before any ports are installed is dropped rather than throwing`() {
		// `onTrackFinalized` is called from a media-session callback on the main
		// thread and must never throw into it. The uninitialised case is the one
		// that would, and it is reachable for real: the listener service can be
		// rebuilt by the system at any moment.
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts("dQw4w9WgXcQ"))
		harness.feed(scrobbleableListen)
		val snapshot = harness.finalized.single()

		FinalizationRuntime.resetForReplay()
		FinalizationRuntime.executeAutomatic(snapshot)

		assertEquals(false, FinalizationRuntime.isReady)
	}
}
