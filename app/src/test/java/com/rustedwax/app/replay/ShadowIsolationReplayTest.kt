package com.rustedwax.app.replay

import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.enrich.VerifiedIdentityCandidateCache
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.FinalizationOutcome
import com.rustedwax.app.scrobble.FinalizationRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three shadow effects the Phase 2/3 audit found still leaking.
 *
 * `ShadowBoundaryReplayTest` already covers broadcast, queue, retry, the UI
 * lists and the evidence stores. It missed three, and each was missed for the
 * same reason: the assertion ran on the caller's thread, where the interesting
 * half had not happened yet.
 *
 *  1. **Log suppression stopped at the dispatcher.** `finalizeInShadow` wrapped
 *     the synchronous call in `withWritesSuppressed`; the wrapper returned as
 *     soon as `launch` did. Every line the asynchronous half wrote reached the
 *     real log. The old tests dispatched inline, so the wrapper happened to
 *     still be on the stack — the bug was invisible *because* the harness was
 *     convenient.
 *  2. **The dedup claim was real.** Shadow claimed the key and released it, so
 *     an assertion made afterwards saw an empty ledger and a live finalization
 *     racing the window saw a duplicate.
 *  3. **The verified playback sequence and the identity-candidate cache were
 *     shared.** `begin` records every finalized target, so a shadow run left an
 *     entry behind and the next real listen's predecessor lookup was wrong.
 *
 * Every test here therefore uses a [DeferredDispatcher] and asserts across the
 * drain, not before it.
 */
class ShadowIsolationReplayTest : ReplayScenarioTest() {

	private val videoId = "dQw4w9WgXcQ"
	private val otherId = "9bZkp7q19f0"

	private fun facts(id: String = videoId) = VideoFacts(
		videoId = id,
		title = "Rick Astley - Never Gonna Give You Up",
		author = "RickAstleyVEVO",
		lengthSeconds = 213,
		category = "Music",
		watchPageResolved = true,
		isUnlisted = false,
	)

	private fun watched(
		id: String = videoId,
		playedMs: Long = 200_000,
		title: String = "Rick Astley - Never Gonna Give You Up",
	) = listOf(
		PlaybackEvent.NotificationObserved(host = "youtube.com"),
		PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = id),
		PlaybackEvent.SessionMetadata(
			title = title,
			artist = "RickAstleyVEVO",
			durationMs = 213_000,
		),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(playedMs),
		PlaybackEvent.Finalized("track change"),
	)

	/**
	 * The log stays silent for the whole shadow run, not just its first half.
	 *
	 * The dispatcher holds the asynchronous remainder until [DeferredDispatcher.drain],
	 * which is well after `finalizeInShadow` returned. Under the old global
	 * suppression counter this is exactly when suppression had already ended.
	 */
	@Test
	fun `a shadow run writes nothing to the log even after the dispatcher hop`() {
		val dispatcher = DeferredDispatcher()
		val harness = ReplayHarness(ReplaySource.BRAVE, dispatcher = dispatcher)
		harness.env.facts.put(facts())
		harness.shadow = true

		try {
			EventLog.applyPolicy(true)
			EventLog.clear()

			// Everything up to the finalize. These events write evidence lines —
			// `url`, `notification` — from the harness, not from the shadow run, so
			// they are the baseline rather than a violation.
			harness.feed(watched().dropLast(1))
			val baseline = EventLog.lines.value.size

			harness.feed(listOf(PlaybackEvent.Finalized("track change")))
			assertTrue("the async half should still be queued", dispatcher.queued > 0)
			assertEquals(
				"the synchronous half of a shadow run reached the real log",
				baseline,
				EventLog.lines.value.size,
			)

			dispatcher.drain()

			assertEquals(
				"the asynchronous half of a shadow run reached the real log",
				baseline,
				EventLog.lines.value.size,
			)
		} finally {
			EventLog.applyPolicy(false)
			EventLog.clear()
		}
	}

	/**
	 * A live run *does* write, so the test above is about suppression rather than
	 * about a log nobody turned on.
	 */
	@Test
	fun `a live run over the same trace does write to the log`() {
		val dispatcher = DeferredDispatcher()
		val harness = ReplayHarness(ReplaySource.BRAVE, dispatcher = dispatcher)
		harness.env.facts.put(facts())

		try {
			EventLog.applyPolicy(true)
			EventLog.clear()

			harness.feed(watched().dropLast(1))
			val baseline = EventLog.lines.value.size
			harness.feed(listOf(PlaybackEvent.Finalized("track change")))
			dispatcher.drain()

			assertNotEquals(
				"a live finalization wrote nothing — the suppression test proves nothing",
				baseline,
				EventLog.lines.value.size,
			)
		} finally {
			EventLog.applyPolicy(false)
			EventLog.clear()
		}
	}

	/**
	 * Shadow never holds a claim, at any instant — not "holds one briefly".
	 *
	 * The deferred dispatcher makes "briefly" observable. The previous
	 * implementation claimed and released inside the launched block, so this
	 * assertion, taken mid-flight, would have seen the key.
	 */
	@Test
	fun `a shadow run never holds a real dedup claim, mid-flight or after`() {
		val dispatcher = DeferredDispatcher()
		val harness = ReplayHarness(ReplaySource.BRAVE, dispatcher = dispatcher)
		harness.env.facts.put(facts())
		harness.shadow = true

		harness.feed(watched())
		assertEquals(
			"a claim was taken before the run even reached dispatch",
			emptyList<String>(),
			harness.env.claims.claimed,
		)

		dispatcher.drain()

		assertEquals(
			"a shadow run left, or transiently took, a real dedup claim",
			emptyList<String>(),
			harness.env.claims.claimed,
		)
		assertEquals(
			"a shadow run must not register duplicate attempts against the live ledger",
			emptyList<String>(),
			harness.env.claims.duplicateAttempts,
		)
	}

	/**
	 * A shadow run still *reaches* the duplicate verdict a live run would.
	 *
	 * Isolation must not be implemented by skipping the check: "does the ledger
	 * already hold this listen" is one of the decisions the parity comparison
	 * exists to compare. Asserted on `ShadowClaims` directly rather than through
	 * two harnesses, because two harnesses freeze two different listen-start
	 * seconds and therefore produce two different dedup keys — the runs would
	 * agree for a reason that has nothing to do with shadow isolation.
	 */
	@Test
	fun `shadow dedup reads the live ledger and writes only to itself`() {
		val live = ReplayDedupClaims()
		assertTrue("setup: the live ledger takes the claim", live.claim("already-sent"))

		val shadow = com.rustedwax.app.scrobble.ShadowClaims(live)

		assertEquals(
			"a listen the ledger already holds must be refused in shadow too",
			false,
			shadow.claim("already-sent"),
		)
		assertEquals(
			"a fresh listen must be claimable in shadow",
			true,
			shadow.claim("fresh"),
		)
		assertEquals(
			"the second shadow claim of one key must be refused, as it would be live",
			false,
			shadow.claim("fresh"),
		)
		assertEquals(
			"the shadow run wrote its claim through to the live ledger",
			listOf("already-sent"),
			live.claimed,
		)
		assertEquals(
			"asking the live ledger must not register as a duplicate attempt",
			emptyList<String>(),
			live.duplicateAttempts,
		)
	}

	/**
	 * A shadow run leaves no entry in the verified playback sequence.
	 *
	 * The sequence is private to the engine, so the property is asserted where it
	 * is observable: the engine logs the whole sequence on every finalization. A
	 * live finalization following a shadow one must describe a sequence holding
	 * only its own listen. `begin` records every finalized target including
	 * unresolved ones, so a leaked shadow entry would appear here — and would
	 * change the adjacency the next real resolution reads.
	 */
	@Test
	fun `a shadow run leaves no entry in the sequence a later live run reads`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts())
		// A genuinely different track, not the same one under a different id: the
		// two listens must have different metadata or the second feed continues the
		// first rather than starting a listen of its own.
		harness.env.facts.put(facts(otherId).copy(title = "PSY - GANGNAM STYLE"))

		try {
			harness.shadow = true
			harness.feed(watched(otherId, title = "PSY - GANGNAM STYLE"))

			EventLog.applyPolicy(true)
			EventLog.clear()
			harness.shadow = false
			harness.feed(watched())

			val sequenceLines = EventLog.lines.value.filter { it.contains("[sequence]") }
			assertTrue("the live run logged no sequence line at all", sequenceLines.isNotEmpty())
			assertTrue(
				"the shadow listen $otherId survived into the live run's sequence: " +
					sequenceLines.joinToString("\n"),
				sequenceLines.none { it.contains(otherId) },
			)
		} finally {
			EventLog.applyPolicy(false)
			EventLog.clear()
		}
	}

	/**
	 * A shadow run does not seed the run-local verified-candidate cache.
	 *
	 * The cache is process-wide and outlives the finalization, so a shadow write
	 * hands a later real resolution a candidate no real listen established.
	 */
	@Test
	fun `a shadow run writes nothing to the verified identity candidate cache`() {
		VerifiedIdentityCandidateCache.clearAll()
		val dispatcher = DeferredDispatcher()
		val harness = ReplayHarness(ReplaySource.BRAVE, dispatcher = dispatcher)
		harness.env.facts.put(facts())
		harness.shadow = true

		harness.feed(watched())
		dispatcher.drain()

		assertEquals(
			"a shadow run seeded the run-local candidate cache",
			emptyList<String>(),
			VerifiedIdentityCandidateCache.candidates(
				packageName = "com.brave.browser",
				title = "Rick Astley - Never Gonna Give You Up",
				channel = "RickAstleyVEVO",
				durationMs = 213_000,
				ownerHandle = null,
			),
		)
	}

	/**
	 * A shadow run does not move the quiet-address-bar counter.
	 *
	 * That counter drives a card the user sees. A shadow run advancing it invents
	 * a warning; a shadow run resetting it hides one the live path earned. Both
	 * are user-visible state, which is the category shadow is defined not to
	 * touch.
	 */
	@Test
	fun `a shadow run leaves the quiet address bar counter alone`() {
		val dispatcher = DeferredDispatcher()
		val harness = ReplayHarness(ReplaySource.BRAVE, dispatcher = dispatcher)
		harness.shadow = true

		// No facts and no URL evidence: the resolution fails, which is the branch
		// that advances the counter.
		val before = FinalizationRuntime.tracksWithoutVideoId.value
		harness.feed(
			listOf(
				PlaybackEvent.NotificationObserved(host = "youtube.com"),
				PlaybackEvent.SessionMetadata(
					title = "Rick Astley - Never Gonna Give You Up",
					artist = "RickAstleyVEVO",
					durationMs = 213_000,
				),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
				PlaybackEvent.Advance(200_000),
				PlaybackEvent.Finalized("track change"),
			),
		)
		dispatcher.drain()

		assertEquals(
			"a shadow run moved the user-visible quiet-bar counter",
			before,
			FinalizationRuntime.tracksWithoutVideoId.value,
		)
	}

	@Test
	fun `a cancelled finalization files exactly one Ignored outcome`() {
		val dispatcher = DeferredDispatcher()
		val harness = ReplayHarness(ReplaySource.BRAVE, dispatcher = dispatcher)
		harness.env.facts.put(facts())

		harness.feed(watched())
		assertTrue("the async half should still be queued", dispatcher.queued > 0)

		FinalizationRuntime.cancelInFlightForReplay()
		dispatcher.drain()

		assertEquals(
			"a cancelled finalization filed no outcome, or filed more than one",
			1,
			harness.outcomes.size,
		)
		assertTrue(
			"cancellation should be Ignored, not Refused: ${harness.outcomes.single().outcome}",
			harness.outcomes.single().outcome is FinalizationOutcome.Ignored,
		)
	}
}
