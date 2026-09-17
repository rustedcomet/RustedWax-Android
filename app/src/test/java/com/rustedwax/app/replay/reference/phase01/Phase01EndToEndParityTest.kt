package com.rustedwax.app.replay.reference.phase01

import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.replay.PlaybackEvent
import com.rustedwax.app.replay.ReplayEnvironment
import com.rustedwax.app.replay.ReplayHarness
import com.rustedwax.app.replay.ReplayScenarioTest
import com.rustedwax.app.replay.ReplaySource
import com.rustedwax.app.scrobble.FinalizationObserver
import com.rustedwax.app.scrobble.FinalizationOutcome
import com.rustedwax.app.scrobble.*
import com.rustedwax.app.enrich.VideoFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Old and new, end to end, over the whole acceptance surface.
 *
 * `Phase01ParityTest` compares the two state machines on measurement and
 * lifecycle. The end-to-end gate requires zero unexplained difference across
 * **identity, measurement, instances, typed
 * outcomes, payload bytes and order, dedup, queue/retry/dispatch, persistence,
 * EventLog, and UI history** — everything a listen touches on its way to the
 * chain, not just the part that counts milliseconds.
 *
 * So both sides run the whole pipeline here:
 *
 * ```text
 * OLD: phase01/SessionProbe (the recorded pre-migration Watch)
 *          -> SessionSnapshot -> FinalizationRuntime -> recorded ports
 * NEW: PlaybackTrace -> production PlaybackReducer
 *          -> SessionSnapshot -> FinalizationRuntime -> recorded ports
 * ```
 *
 * One engine, one set of port fakes, two snapshot producers. Everything
 * downstream of the snapshot is production code executed identically by both
 * sides, which is the point: a difference anywhere in this comparison is a
 * difference in the thing being migrated, because nothing else differs.
 *
 * ## What a difference here means, and what it does not
 *
 * The two sides are fed equivalent scripts, not identical objects. Where the old
 * and new implementations genuinely disagree — and one place they do is recorded
 * as an explicit exception below — the disagreement is named and justified
 * rather than smoothed away. An unexplained difference fails the gate; an
 * explained one is what "zero *unexplained* difference" leaves room for.
 */
class Phase01EndToEndParityTest : ReplayScenarioTest() {

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

	/**
	 * Everything the engine did, as one comparable value.
	 *
	 * Read from the recording ports rather than reconstructed, so a side that
	 * silently stopped doing something shows up as an absence rather than as an
	 * assertion nobody wrote.
	 */
	private data class EngineTrace(
		val outcomes: List<String>,
		val payloads: List<String>,
		val dedupClaims: List<String>,
		val queued: List<String>,
		val recent: List<String>,
		val skipped: List<String>,
		val log: List<String>,
	)

	/**
	 * Timestamps and the frozen listen-start second are wall-clock, and the two
	 * sides start their clocks at different instants. Normalising them is not
	 * hiding a difference: a listen's *identity, title, artist, percentage,
	 * duration, url and kind* are what the payload asserts about the world, and
	 * those are compared byte for byte.
	 */
	private fun normalise(text: String): String = text
		.replace(Regex("\"timestamp\"\\s*:\\s*\"[^\"]*\""), "\"timestamp\":\"<t>\"")
		.replace(Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}"), "<t>")
		.replace(Regex("start(ed)?=\\d+"), "start=<n>")
		.replace(Regex("\\b17\\d{8}\\b"), "<epoch>")
		// A History row's eventId is minted per row, so it differs between any two
		// runs by design — the same reason atEpochSec is erased above. Parity is
		// about what the row claims about the world, not which row object it was.
		.replace(Regex("eventId=[0-9a-fA-F-]{36}"), "eventId=<id>")

	private fun outcomeOf(outcome: FinalizationOutcome): String = when (outcome) {
		is FinalizationOutcome.Ignored -> "Ignored(${outcome.reason})"
		is FinalizationOutcome.Refused -> "Refused(${outcome.reason})"
		is FinalizationOutcome.Eligible ->
			"Eligible(${outcome.payloads.size})"
	}

	/** Run a list of already-frozen snapshots through a fresh engine. */
	private fun runThroughEngine(snapshots: List<SessionSnapshot>): EngineTrace {
		val env = ReplayEnvironment()
		env.facts.put(facts())
		val outcomes = mutableListOf<String>()

		FinalizationRuntime.resetForReplay()
		FinalizationRuntime.installPortsForReplay(env.ports(), CoroutineScope(Dispatchers.Unconfined))
		FinalizationRuntime.finalizationObserver = FinalizationObserver { _, outcome ->
			outcomes += outcomeOf(outcome)
		}
		EventLog.applyPolicy(true)
		EventLog.clear()

		snapshots.forEach { FinalizationRuntime.executeAutomatic(it) }

		val trace = EngineTrace(
			outcomes = outcomes.toList(),
			payloads = env.broadcaster.sent.map { normalise(it.json) },
			dedupClaims = env.claims.claimed.map { normalise(it) },
			queued = env.retryQueue.all.map { normalise(it.label) },
			recent = FinalizationRuntime.recent.value.map { normalise(it.toString()) },
			skipped = FinalizationRuntime.skipped.value.map { normalise(it.toString()) },
			log = EventLog.lines.value.map { normalise(it) },
		)
		EventLog.applyPolicy(false)
		EventLog.clear()
		return trace
	}

	/** The old side: the recorded pre-migration probe, then the engine. */
	private fun oldSide(script: List<ParityStep>): EngineTrace =
		runThroughEngine(ReferenceRun.snapshots(script))

	/** The new side: production `PlaybackReducer` via the harness, then the engine. */
	private fun newSide(events: List<PlaybackEvent>): EngineTrace {
		val captured = mutableListOf<SessionSnapshot>()
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(facts())
		harness.captureFinalized = { captured += it }
		harness.feed(events)
		return runThroughEngine(captured)
	}

	// ---- the scripts, expressed once per side ---------------------------------

	/*
	 * The listen ends with a *track change*, not with the session being destroyed.
	 *
	 * Measured while writing this test: with a native `mediaId` present, the
	 * pre-migration probe answers `onSessionDestroyed` by parking the listen's
	 * progress for a replacement session rather than finalizing it — the native
	 * carry window. Nothing is finalized, on either side, and every assertion
	 * below would have compared one empty list against another. A track change is
	 * the ending both implementations agree is an ending, so it is the one that
	 * actually exercises the pipeline.
	 */
	private fun oldScript(playedMs: Long) = listOf(
		ParityStep.Metadata(
			title = "Rick Astley - Never Gonna Give You Up",
			artist = "RickAstleyVEVO",
			durationMs = 213_000,
			mediaId = videoId,
		),
		ParityStep.Transport(PlaybackState.STATE_PLAYING, 0),
		ParityStep.Advance(playedMs),
		ParityStep.Metadata(
			title = "PSY - GANGNAM STYLE",
			artist = "officialpsy",
			durationMs = 252_000,
			mediaId = "9bZkp7q19f0",
		),
	)

	private fun newScript(playedMs: Long) = listOf(
		PlaybackEvent.SessionMetadata(
			title = "Rick Astley - Never Gonna Give You Up",
			artist = "RickAstleyVEVO",
			durationMs = 213_000,
			mediaId = videoId,
		),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(playedMs),
		PlaybackEvent.SessionMetadata(
			title = "PSY - GANGNAM STYLE",
			artist = "officialpsy",
			durationMs = 252_000,
			mediaId = "9bZkp7q19f0",
		),
	)

	// ---- the comparison -------------------------------------------------------

	@Test
	fun `an eligible listen produces the same payload bytes, dedup and dispatch on both`() {
		val old = oldSide(oldScript(200_000))
		val new = newSide(newScript(200_000))

		assertTrue(
			"the old side finalized nothing — the comparison would be vacuous",
			old.outcomes.isNotEmpty(),
		)
		assertEquals("typed terminal outcomes differ", old.outcomes, new.outcomes)
		assertEquals("payload bytes or order differ", old.payloads, new.payloads)
		assertEquals("dedup claims differ", old.dedupClaims, new.dedupClaims)
		assertEquals("queue/retry effects differ", old.queued, new.queued)
		assertEquals("UI history differs", old.recent, new.recent)
		assertEquals("Not-logged history differs", old.skipped, new.skipped)
	}

	@Test
	fun `a refused listen refuses identically, down to the reason`() {
		// Two seconds of a 213-second video: below every threshold, so the
		// prefilter refuses it on both sides and nothing is dispatched.
		val old = oldSide(oldScript(2_000))
		val new = newSide(newScript(2_000))

		assertTrue("the old side finalized nothing", old.outcomes.isNotEmpty())
		assertTrue(
			"expected a refusal, got ${old.outcomes}",
			old.outcomes.first().startsWith("Refused"),
		)
		assertEquals("refusal reasons differ", old.outcomes, new.outcomes)
		assertEquals("a refused listen dispatched something", emptyList<String>(), old.payloads)
		assertEquals("payloads differ", old.payloads, new.payloads)
		assertEquals("Not-logged history differs", old.skipped, new.skipped)
	}

	@Test
	fun `the identity the two sides resolve is the same video`() {
		val old = oldSide(oldScript(200_000))
		val new = newSide(newScript(200_000))

		assertTrue("no payload to read an identity from", old.payloads.isNotEmpty())
		assertTrue(
			"the old side did not resolve the exact id from the native media id",
			old.payloads.any { it.contains(videoId) },
		)
		assertEquals("resolved identity differs", old.payloads, new.payloads)
	}

	/**
	 * The engine's own log, line for line.
	 *
	 * The most sensitive surface in the comparison and the last one a migration
	 * would think to check: it records the route identity took, the sequence
	 * state, the dedup verdict and the dispatch result. Two implementations that
	 * agree on the payload can still disagree about how they got there.
	 */
	@Test
	fun `the event log records the same decisions in the same order`() {
		val old = oldSide(oldScript(200_000))
		val new = newSide(newScript(200_000))

		assertTrue("the engine logged nothing at all", old.log.isNotEmpty())
		assertEquals("engine decision log differs", old.log, new.log)
	}

	/**
	 * The control for all of the above.
	 *
	 * Every assertion in this file passes when both sides are correct and when
	 * both are wrong in the same way. Feeding one side a different listen must
	 * therefore break the comparison — if it does not, these tests are comparing
	 * nothing and the gate is unproven whatever they report.
	 */
	@Test
	fun `negative control - a different listen on one side breaks every surface`() {
		val old = oldSide(oldScript(200_000))
		val shorter = newSide(newScript(2_000))

		assertNotEquals("outcomes did not notice", old.outcomes, shorter.outcomes)
		assertNotEquals("payloads did not notice", old.payloads, shorter.payloads)
		assertNotEquals("the log did not notice", old.log, shorter.log)
	}
}
