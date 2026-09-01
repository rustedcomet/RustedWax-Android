package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.FinalizationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalizationFaultReplayTest : ReplayScenarioTest() {

	private val videoId = "dQw4w9WgXcQ"

	private fun harness(): ReplayHarness {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(
			VideoFacts(
				videoId = videoId,
				title = "Rick Astley - Never Gonna Give You Up",
				author = "Rick Astley",
				lengthSeconds = 213,
				category = "Music",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
		return harness
	}

	/** A complete, ordinary, eligible browser listen. */
	private fun watched(withUrl: Boolean = true) = listOfNotNull(
		PlaybackEvent.NotificationObserved(host = "youtube.com"),
		PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = videoId).takeIf { withUrl },
		PlaybackEvent.SessionMetadata(
			title = "Rick Astley - Never Gonna Give You Up",
			artist = "Rick Astley",
			durationMs = 213_000,
		),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(200_000),
		PlaybackEvent.Finalized("track change"),
	)

	@Test
	fun `a resolver route that throws is refused by the chain's own boundary`() {
		val harness = harness()
		harness.env.identity.search = { error("markup changed under the search route") }

		harness.feed(watched(withUrl = false))

		harness.assertOneOutcomePerFinalization()
		val outcome = harness.outcomes.single().outcome
		assertTrue("a fault is a refusal, not a silence", outcome is FinalizationOutcome.Refused)
		assertTrue(
			(outcome as FinalizationOutcome.Refused).reason
				.contains("resolver failed: markup changed under the search route"),
		)
		assertEquals("nothing may reach the chain", 0, harness.broadcasts.size)
		assertEquals(0, harness.env.claims.claimed.size)
	}

	/**
	 * The gap the outer boundary closes.
	 *
	 * Past identity, past the contract, into the half that builds the payload —
	 * where nothing was wrapped. The mute check is an ordinary disk read and
	 * stands here for every unguarded step after it: the payload factory, the
	 * verified-candidate cache, the sequence, the key vault. Before the fix this
	 * finalization filed no outcome and the exception went to the coroutine's
	 * default handler, which on Android ends the process.
	 */
	@Test
	fun `an unguarded step that throws after the id resolves still produces exactly one outcome`() {
		val harness = harness()
		harness.env.mutes.throwOnQuery = IllegalStateException("simulated disk fault")

		harness.feed(watched())

		harness.assertOneOutcomePerFinalization()
		val outcome = harness.outcomes.single().outcome
		assertTrue(outcome is FinalizationOutcome.Refused)
		assertEquals(
			"finalization failed unexpectedly — IllegalStateException: simulated disk fault",
			(outcome as FinalizationOutcome.Refused).reason,
		)
		assertEquals(0, harness.broadcasts.size)
		assertEquals("and nothing was claimed against a listen nobody scored", 0, harness.env.claims.claimed.size)
	}

	@Test
	fun `an Error rather than an Exception is caught too`() {

		val harness = harness()
		harness.env.mutes.throwOnQuery = OutOfMemoryError("simulated allocation failure")

		harness.feed(watched())

		harness.assertOneOutcomePerFinalization()
		assertTrue(
			(harness.outcomes.single().outcome as FinalizationOutcome.Refused).reason
				.contains("OutOfMemoryError"),
		)
	}

	/**
	 * The case the failure handler must **not** rewrite.
	 *
	 * Dispatch is not a finalization outcome — the audit amendment says so
	 * explicitly, because folding it back in would make a two-payload listen
	 * whose second send failed report as a refusal. A throw during dispatch
	 * therefore has to leave the already-filed `Eligible` alone: the listen did
	 * earn its entry, and what happened to the entry afterwards is the
	 * broadcaster's and the retry queue's story to tell.
	 */
	@Test
	fun `a fault during dispatch leaves an eligible listen eligible`() {
		val harness = harness()
		harness.env.broadcaster.throwOnBroadcast = IllegalStateException("signing failed")

		harness.feed(watched())

		harness.assertOneOutcomePerFinalization()
		val outcome = harness.outcomes.single().outcome
		assertTrue(
			"the listen was ruled eligible before anything threw",
			outcome is FinalizationOutcome.Eligible,
		)
		assertEquals(1, (outcome as FinalizationOutcome.Eligible).payloads.size)
		assertEquals("and nothing reached the chain", 0, harness.transactionIds.size)
	}

	@Test
	fun `a fault does not stop the next listen from being decided`() {
		// The engine is a singleton with one long-lived scope. A fault that killed
		// the scope — or left a latch set — would show up here as a second
		// finalization with no outcome, which is the failure mode a `SupervisorJob`
		// is supposed to prevent and which nothing was checking.
		val harness = harness()
		harness.env.mutes.throwOnQuery = IllegalStateException("simulated disk fault")
		harness.feed(watched())

		harness.env.mutes.throwOnQuery = null
		harness.feed(watched())

		harness.assertOneOutcomePerFinalization(expected = 2)
		assertEquals(listOf("Refused", "Eligible"), harness.outcomeKinds)
		assertEquals(1, harness.broadcasts.size)
	}
}
