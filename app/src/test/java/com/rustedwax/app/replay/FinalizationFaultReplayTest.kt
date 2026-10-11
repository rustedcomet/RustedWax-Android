package com.rustedwax.app.replay

import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.DispatchLink
import com.rustedwax.app.scrobble.FinalizationObserver
import com.rustedwax.app.scrobble.FinalizationOutcome
import com.rustedwax.app.scrobble.FinalizationRuntime
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

	// ── #4: a presentation observer must never steer the engine ──────────

	/**
	 * Records every callback and throws from the ones named. Installed over the
	 * harness's own recorder, exactly where the status card installs itself.
	 */
	private class FailingObserver(
		private val onOutcome: (FinalizationOutcome) -> Boolean = { false },
		/** The harness's own recorder, told first so its exactly-one-outcome check still holds. */
		var inner: FinalizationObserver = FinalizationObserver.None,
		private val onCreated: Boolean = false,
		private val onEnded: Boolean = false,
	) : FinalizationObserver {
		val outcomes = mutableListOf<FinalizationOutcome>()
		val created = mutableListOf<String>()
		val ended = mutableListOf<String>()

		override fun onFinalizationOutcome(session: SessionSnapshot, outcome: FinalizationOutcome) {
			inner.onFinalizationOutcome(session, outcome)
			outcomes += outcome
			if (onOutcome(outcome)) throw IllegalStateException("status card failed")
		}

		override fun onOperationCreated(link: DispatchLink, operationId: String, account: String) {
			created += operationId
			if (onCreated) throw IllegalStateException("status card failed")
		}

		override fun onDispatchEnded(link: DispatchLink, reason: String) {
			ended += reason
			if (onEnded) throw IllegalStateException("status card failed")
		}
	}

	private fun ReplayHarness.observedBy(observer: FailingObserver): ReplayHarness = also {
		observer.inner = FinalizationRuntime.finalizationObserver
		FinalizationRuntime.finalizationObserver = observer
	}

	@Test
	fun `a quiet presentation observer sees one eligible listen sent once`() {
		val observer = FailingObserver()
		val harness = harness().observedBy(observer)

		harness.feed(watched())

		assertEquals(listOf("Eligible"), observer.outcomes.map { it::class.simpleName })
		assertEquals(1, harness.broadcasts.size)
		assertEquals(1, harness.env.claims.claimed.size)
		assertEquals(1, observer.created.size)
		assertEquals(1, harness.env.policy.automaticTransportCommitAttempts)
	}

	/**
	 * Codex's reproduction. The verdict is filed before dispatch; a status card
	 * that threw while being told used to unwind the eligible path, so nothing
	 * was sent and the dedup claim stayed held against a listen that had
	 * earned its entry.
	 */
	@Test
	fun `a presentation observer that throws on Eligible cannot stop the send`() {
		val observer = FailingObserver(onOutcome = { it is FinalizationOutcome.Eligible })
		val harness = harness().observedBy(observer)

		harness.feed(watched())

		assertEquals("told exactly once", 1, observer.outcomes.size)
		assertTrue(observer.outcomes.single() is FinalizationOutcome.Eligible)
		assertEquals("the eligible listen is still sent, once", 1, harness.broadcasts.size)
		assertEquals(1, harness.transactionIds.size)
		assertEquals("one claim, as without the fault", 1, harness.env.claims.claimed.size)
		assertEquals(emptyList<String>(), harness.duplicateAttempts)
		assertEquals("authorization committed once, as without the fault", 1, harness.env.policy.automaticTransportCommitAttempts)
		assertEquals(1, observer.created.size)
	}

	@Test
	fun `a throwing observer leaves a refusal a refusal`() {
		val observer = FailingObserver(onOutcome = { true })
		val harness = harness().observedBy(observer)
		harness.env.identity.search = { error("markup changed under the search route") }

		harness.feed(watched(withUrl = false))

		assertEquals(1, observer.outcomes.size)
		val outcome = observer.outcomes.single()
		assertTrue(outcome is FinalizationOutcome.Refused)
		assertTrue((outcome as FinalizationOutcome.Refused).reason.contains("resolver failed"))
		assertEquals(0, harness.broadcasts.size)
		assertEquals(0, harness.env.claims.claimed.size)
	}

	@Test
	fun `a throwing observer leaves an ignored listen ignored`() {
		val observer = FailingObserver(onOutcome = { true })
		val harness = harness().observedBy(observer)
		harness.env.policy.autoScrobble = false

		harness.feed(watched())

		assertEquals(1, observer.outcomes.size)
		assertTrue(observer.outcomes.single() is FinalizationOutcome.Ignored)
		assertEquals(0, harness.broadcasts.size)
		assertEquals(0, harness.env.claims.claimed.size)
	}

	@Test
	fun `a throwing observer is told once and the next listen is still decided`() {
		val observer = FailingObserver(onOutcome = { true })
		val harness = harness().observedBy(observer)

		harness.feed(watched())
		harness.feed(watched())

		assertEquals("one verdict per finalization", 2, observer.outcomes.size)
		assertEquals(2, harness.broadcasts.size)
	}

	@Test
	fun `a throwing operation-created callback is contained`() {
		val observer = FailingObserver(onCreated = true)
		val harness = harness().observedBy(observer)

		harness.feed(watched())

		assertEquals(1, observer.created.size)
		assertEquals(1, harness.broadcasts.size)
		assertEquals(1, harness.env.claims.claimed.size)
	}

	@Test
	fun `a throwing dispatch-ended callback is contained`() {
		val observer = FailingObserver(onEnded = true)
		val harness = harness().observedBy(observer)
		harness.env.retryQueue.storageWorks = false

		harness.feed(watched())

		assertEquals(listOf("queue storage failure"), observer.ended)
		assertTrue(observer.outcomes.single() is FinalizationOutcome.Eligible)
		assertEquals("nothing could be persisted, so nothing was sent", 0, harness.broadcasts.size)
	}

	// ── #4: the diagnostic written for a failing observer is best-effort too ─

	/**
	 * Logging on, through EventLog's own attach seam, with a clock that throws
	 * exactly once when [arm]ed. The observer arms it as it throws, so the very
	 * next append — the diagnostic in `FinalizationReport.file()` — is the one
	 * that fails; every engine line after it logs normally.
	 */
	private class FaultyLogClock {
		@Volatile
		var armed = false

		fun now(): Long {
			if (armed) {
				armed = false
				throw IllegalStateException("diagnostic logging failed")
			}
			return System.currentTimeMillis()
		}
	}

	private fun <T> withLogging(clock: FaultyLogClock, block: () -> T): T {
		EventLog.attach(null, enabled = true, now = clock::now)
		return try {
			block()
		} finally {
			EventLog.attach(null, enabled = false)
		}
	}

	private fun armingObserver(clock: FaultyLogClock, failLogger: Boolean, throwsOn: (FinalizationOutcome) -> Boolean) =
		FailingObserver(
			onOutcome = { outcome ->
				val fault = throwsOn(outcome)
				if (fault && failLogger) clock.armed = true
				fault
			},
		)

	/** Codex's reproduction: the observer's exception was caught, the logger's was not. */
	@Test
	fun `a diagnostic that throws while reporting a failed observer cannot stop the send`() {
		val clock = FaultyLogClock()
		val observer = armingObserver(clock, failLogger = true) { it is FinalizationOutcome.Eligible }
		val harness = harness().observedBy(observer)

		withLogging(clock) { harness.feed(watched()) }

		assertEquals("the injected logger fault was actually reached", false, clock.armed)
		assertEquals(1, observer.outcomes.size)
		assertTrue(observer.outcomes.single() is FinalizationOutcome.Eligible)
		assertEquals("the eligible listen is still sent, once", 1, harness.broadcasts.size)
		assertEquals(1, harness.transactionIds.size)
		assertEquals(1, harness.env.claims.claimed.size)
		assertEquals(emptyList<String>(), harness.duplicateAttempts)
		assertEquals(1, harness.env.policy.automaticTransportCommitAttempts)
		assertEquals(1, observer.created.size)
	}

	@Test
	fun `a failed observer with a working logger is sent once`() {
		val clock = FaultyLogClock()
		val observer = armingObserver(clock, failLogger = false) { it is FinalizationOutcome.Eligible }
		val harness = harness().observedBy(observer)

		withLogging(clock) { harness.feed(watched()) }

		assertEquals(1, harness.broadcasts.size)
		assertEquals(1, harness.env.claims.claimed.size)
		assertEquals(1, harness.env.policy.automaticTransportCommitAttempts)
	}

	@Test
	fun `a healthy observer and logger are unchanged`() {
		val clock = FaultyLogClock()
		val observer = FailingObserver()
		val harness = harness().observedBy(observer)

		withLogging(clock) { harness.feed(watched()) }

		assertTrue(observer.outcomes.single() is FinalizationOutcome.Eligible)
		assertEquals(1, harness.broadcasts.size)
		assertEquals(1, harness.env.claims.claimed.size)
		assertEquals(1, harness.env.policy.automaticTransportCommitAttempts)
		assertEquals(1, observer.created.size)
	}

	@Test
	fun `an ignored listen stays ignored when observer and logger both fail`() {
		val clock = FaultyLogClock()
		val observer = armingObserver(clock, failLogger = true) { true }
		val harness = harness().observedBy(observer)
		harness.env.policy.autoScrobble = false

		withLogging(clock) { harness.feed(watched()) }

		assertEquals(false, clock.armed)
		assertTrue(observer.outcomes.single() is FinalizationOutcome.Ignored)
		assertEquals(0, harness.broadcasts.size)
		assertEquals(0, harness.env.claims.claimed.size)
	}

	@Test
	fun `a refusal stays a refusal when observer and logger both fail`() {
		val clock = FaultyLogClock()
		val observer = armingObserver(clock, failLogger = true) { true }
		val harness = harness().observedBy(observer)
		harness.env.identity.search = { error("markup changed under the search route") }

		withLogging(clock) { harness.feed(watched(withUrl = false)) }

		assertEquals(false, clock.armed)
		val outcome = observer.outcomes.single()
		assertTrue(outcome is FinalizationOutcome.Refused)
		assertTrue((outcome as FinalizationOutcome.Refused).reason.contains("resolver failed"))
		assertEquals(0, harness.broadcasts.size)
		assertEquals(0, harness.env.claims.claimed.size)
	}
}
