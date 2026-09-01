package com.rustedwax.app.replay

import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.detect.MediaSessionAccessibilityEvidence
import com.rustedwax.app.detect.MediaSessionAdEvidence
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.TrackProgressCarry
import com.rustedwax.app.detect.UrlEvidence
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.FinalizationOutcome
import com.rustedwax.app.scrobble.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shadow boundary, and what it is allowed to leave behind.
 *
 * ## What was wrong with the previous arrangement
 *
 * Shadow mode used to be a `PayloadBroadcaster` that recorded the payload and
 * returned `Rejected`. That is not a boundary — it is the **live** pipeline run
 * to completion, with one seam declining at the very end. Everything upstream of
 * the broadcaster happened for real: the dedup claim was taken, the retry queue
 * was consulted, the Now and Not-logged lists were written, and the event log
 * recorded a run that never occurred. The arrangement tested how the engine
 * handles a rejection, and it needed a *second* fake (`ShadowDedupClaims`) to
 * undo one of the effects it had already allowed — which is the shape of a
 * design that is holding something back rather than not doing it.
 *
 * `FinalizationRuntime.finalizeInShadow` is the replacement, and it is defined by
 * what it cannot reach: dispatch is never entered, so there is nothing for a
 * broadcaster to decline. Every case below is one of the effects the audit's
 * gate names.
 *
 * ## What is compared
 *
 * `<redacted-private-path>`'s parity list, in full: finalized identity,
 * measurement, track and source instance, the ordered terminal outcomes, the
 * serialized payload bytes, dedup decisions, queue/retry/dispatch effects,
 * shared evidence, the event log, and the recent/skipped UI history.
 */
class ShadowBoundaryReplayTest : ReplayScenarioTest() {

	private val videoId = "dQw4w9WgXcQ"

	private fun facts() = VideoFacts(
		videoId = videoId,
		title = "Rick Astley - Never Gonna Give You Up",
		author = "RickAstleyVEVO",
		lengthSeconds = 213,
		category = "Music",
		watchPageResolved = true,
		isUnlisted = false,
	)

	private fun harness(shadow: Boolean, env: ReplayEnvironment = ReplayEnvironment()) =
		ReplayHarness(ReplaySource.BRAVE, env).also {
			it.shadow = shadow
			it.env.facts.put(facts())
		}

	/** An eligible listen, and a refused one, so both branches are covered. */
	private fun watched(playedMs: Long = 200_000) = listOf(
		PlaybackEvent.NotificationObserved(host = "youtube.com"),
		PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = videoId),
		PlaybackEvent.SessionMetadata(
			title = "Rick Astley - Never Gonna Give You Up",
			artist = "RickAstleyVEVO",
			durationMs = 213_000,
		),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(playedMs),
		PlaybackEvent.Finalized("track change"),
	)

	// ---- what a shadow run cannot do ------------------------------------------

	@Test
	fun `a shadow run reaches no broadcaster, no queue and no retry`() {
		val harness = harness(shadow = true)

		harness.feed(watched())

		assertEquals(listOf("Eligible"), harness.outcomeKinds)
		assertEquals("nothing was sent", 0, harness.env.broadcaster.sent.size)
		assertEquals("and nothing is owed", 0, harness.env.retryQueue.size())
		// The distinction that matters: this is not a broadcaster that declined —
		// it is a broadcaster that was never asked. A `Rejected` result would have
		// meant the whole dispatch path ran.
		assertEquals(emptyList<String>(), harness.transactionIds)
	}

	@Test
	fun `a shadow run retains no dedup claim, against a live ledger`() {
		// Against the *live* ledger deliberately. A shadow mode that needed a
		// special no-op ledger to be safe would be one whose safety came from the
		// fake rather than from the boundary.
		val harness = harness(shadow = true)

		harness.feed(watched())

		assertEquals(emptyList<String>(), harness.env.claims.claimed)
		assertEquals(emptyList<String>(), harness.env.claims.duplicateAttempts)
	}

	@Test
	fun `a shadow run cannot suppress the live run that follows it`() {
		// The property the whole boundary exists for, over one environment and one
		// restored frozen start — with two environments the live half would start
		// from an empty ledger and pass regardless.
		val env = ReplayEnvironment()
		harness(shadow = true, env = env).feed(watched())
		env.clock.restore()

		val live = harness(shadow = false, env = env)
		live.feed(watched())

		assertEquals(listOf("Eligible"), live.outcomeKinds)
		assertEquals(1, live.env.broadcaster.sent.size)
		assertEquals(1, live.env.claims.claimed.size)
		assertEquals(emptyList<String>(), live.env.claims.duplicateAttempts)
	}

	@Test
	fun `a shadow run writes nothing to the Now or Not-logged lists`() {
		val eligible = harness(shadow = true)
		eligible.feed(watched())
		// Below the threshold, so this one is refused rather than eligible: the
		// Not-logged list is written from `skip`, which is a different branch.
		val refused = harness(shadow = true)
		refused.feed(watched(playedMs = 20_000))

		assertEquals(listOf("Eligible"), eligible.outcomeKinds)
		assertEquals(listOf("Refused"), refused.outcomeKinds)
		assertEquals(emptyList<FinalizationRuntime.ScrobbleRecord>(), FinalizationRuntime.recent.value)
		assertEquals(emptyList<FinalizationRuntime.SkipRecord>(), FinalizationRuntime.skipped.value)
	}

	@Test
	fun `a shadow run writes nothing to the event log`() {
		// The log is a durable, exportable record of what the app did. A shadow
		// line in it would be indistinguishable from a real one to whoever read it.
		EventLog.applyPolicy(true)
		try {
			// The trace's own observations — the address bar, the notification — are
			// not finalization and do write, in shadow as in live: they are things
			// that genuinely happened on the device. What is under test is the
			// finalization, so the log is cleared immediately before it.
			val shadow = harness(shadow = true)
			shadow.feed(watched().dropLast(1))
			EventLog.clear()
			shadow.feed(listOf(PlaybackEvent.Finalized("track change")))
			assertEquals(emptyList<String>(), EventLog.lines.value)

			// And the identical finalization live does write, so the assertion above
			// is testing suppression rather than a log nobody switched on.
			val live = harness(shadow = false)
			live.feed(watched().dropLast(1))
			EventLog.clear()
			live.feed(listOf(PlaybackEvent.Finalized("track change")))
			assertTrue(EventLog.lines.value.isNotEmpty())
		} finally {
			EventLog.applyPolicy(false)
			EventLog.clear()
		}
	}

	@Test
	fun `a shadow run leaves the shared evidence stores exactly as it found them`() {
		// Finalization does not write these at all, which is the point: the claim
		// is checked rather than assumed, because `<redacted-private-path>` §2 is
		// precisely about evidence with no single owner.
		val harness = harness(shadow = true)
		harness.feed(watched().dropLast(1))
		val before = evidenceFingerprint()

		harness.feed(listOf(PlaybackEvent.Finalized("track change")))

		assertEquals(before, evidenceFingerprint())
	}

	/**
	 * The observable state of every shared store finalization could touch.
	 *
	 * Read through the stores' own accessors rather than through a snapshot the
	 * test builds, so a store that gained state would show up here rather than in
	 * a field the fingerprint forgot to include.
	 */
	private fun evidenceFingerprint(): String = listOf(
		"url=${UrlEvidence.get("com.brave.browser", Long.MAX_VALUE)}",
		"playlist=${UrlEvidence.playlistId("com.brave.browser", Long.MAX_VALUE)}",
		"adPackages=${MediaSessionAdEvidence.trackedPackages()}",
		"coverage=${MediaSessionAccessibilityEvidence.trackedInstances()}",
		"carry=${TrackProgressCarry.size()}",
		"carryBrave=${TrackProgressCarry.hasPackage("com.brave.browser")}",
	).joinToString("|")

	// ---- and that it decides the same things ----------------------------------

	/**
	 * The parity comparison the audit's gate asks for, over every dimension it
	 * names.
	 *
	 * Two runs of the same trace over the same reducer: one shadow, one live.
	 * Everything the finalization *decided* must be identical; everything it was
	 * allowed to *do* must differ in exactly one direction.
	 */
	@Test
	fun `shadow and live agree on every finalized decision`() {
		val shadowEnv = ReplayEnvironment()
		val shadow = harness(shadow = true, env = shadowEnv)
		shadow.feed(watched())
		val shadowOutcomes = shadow.outcomes.toList()

		// The *same* frozen listens, presented again to a live engine. Replaying
		// the trace a second time would allocate new track instance tokens from the
		// process-wide counter and re-stamp the frozen start, so the two sides
		// would differ for reasons that have nothing to do with the boundary.
		// Presenting one immutable snapshot to both is what makes "the same
		// decision about the same listen" a literal statement.
		val liveEnv = ReplayEnvironment()
		val live = harness(shadow = false, env = liveEnv)
		shadow.finalized.forEach { live.finalizeDirectly(it) }
		val liveOutcomes = live.outcomes.toList()

		// Finalized identity, measurement, track instance and source instance —
		// the whole snapshot, field for field, as each side actually received it.
		assertEquals(
			shadowOutcomes.map { it.session.parityView() },
			liveOutcomes.map { it.session.parityView() },
		)
		// The ordered terminal outcomes.
		assertEquals(shadow.outcomeKinds, live.outcomeKinds)
		// The serialized payload bytes, not a parsed view of them.
		assertEquals(
			shadowOutcomes.eligibleJson(),
			liveOutcomes.eligibleJson(),
		)
		assertTrue("a parity run with no payloads proves nothing", shadowOutcomes.eligibleJson().isNotEmpty())

		// Dedup decisions: the same key would have been claimed, and only the live
		// run actually holds it.
		assertEquals(emptyList<String>(), shadowEnv.claims.claimed)
		assertEquals(1, liveEnv.claims.claimed.size)
		// Queue, retry and dispatch effects.
		assertEquals(0, shadowEnv.retryQueue.size())
		assertEquals(0, liveEnv.retryQueue.size())
		assertEquals(0, shadowEnv.broadcaster.sent.size)
		assertEquals(1, liveEnv.broadcaster.sent.size)
	}

	/**
	 * The negative control.
	 *
	 * A comparison that cannot fail is a green light wired to nothing. This feeds
	 * the shadow side a deliberately different listen and requires the comparison
	 * to notice.
	 */
	@Test
	fun `the comparison notices when the two sides disagree`() {
		val shadow = harness(shadow = true)
		shadow.feed(watched(playedMs = 200_000))

		// The live side is handed a *different* listen — 20 s of a 3:33 song — so
		// the comparison has something to catch.
		val live = harness(shadow = false)
		live.feed(watched(playedMs = 20_000))

		assertNotEquals(shadow.outcomeKinds, live.outcomeKinds)
		assertNotEquals(
			shadow.outcomes.map { it.session.playedMs },
			live.outcomes.map { it.session.playedMs },
		)
	}

	private fun List<ReplayHarness.RecordedOutcome>.eligibleJson(): List<String> =
		mapNotNull { it.outcome as? FinalizationOutcome.Eligible }
			.flatMap { eligible -> eligible.payloads.map { it.toJson() } }

	/**
	 * Everything about a finalized listen that a comparison must hold constant.
	 *
	 * The snapshot's own `toString`, minus nothing: comparing a hand-picked set of
	 * fields is how a parity gate stops noticing the field nobody picked.
	 */
	private fun SessionSnapshot.parityView(): String = listOf(
		toString(),
		"sourceSession=$sourceSession",
		"trackInstance=$trackInstance",
	).joinToString("\n")
}
