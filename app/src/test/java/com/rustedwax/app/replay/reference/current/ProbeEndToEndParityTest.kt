package com.rustedwax.app.replay.reference.current

import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.hive.HiveRpc
import com.rustedwax.app.replay.ReplayEnvironment
import com.rustedwax.app.replay.ReplayScenarioTest
import com.rustedwax.app.replay.reference.phase01.ParityStep
import com.rustedwax.app.replay.reference.phase01.PlaybackState
import com.rustedwax.app.replay.reference.phase01.ReferenceRun
import com.rustedwax.app.scrobble.FinalizationObserver
import com.rustedwax.app.scrobble.FinalizationOutcome
import com.rustedwax.app.scrobble.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeEndToEndParityTest : ReplayScenarioTest() {

	private val native = YouTubeProbe.YOUTUBE_PACKAGE
	private val first = "aaaaaaaaaaa"
	private val second = "bbbbbbbbbbb"

	private fun facts() = arrayOf(
		VideoFacts(
			videoId = first,
			title = "Rick Astley - Never Gonna Give You Up",
			author = "RickAstleyVEVO",
			lengthSeconds = 213,
			category = "Music",
			watchPageResolved = true,
			isUnlisted = false,
		),
		VideoFacts(
			videoId = second,
			title = "PSY - GANGNAM STYLE",
			author = "officialpsy",
			lengthSeconds = 252,
			category = "Music",
			watchPageResolved = true,
			isUnlisted = false,
		),
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
		val duplicateAttempts: List<String>,
		val queued: List<String>,
		val queueSize: Int,
		val recent: List<String>,
		val skipped: List<String>,
		val log: List<String>,
	)

	/**
	 * Wall-clock instants and the frozen listen-start second differ between two
	 * runs that happen milliseconds apart. Normalising them is not hiding a
	 * difference: a listen's identity, title, artist, percentage, duration, url
	 * and kind are what the payload asserts about the world, and those are
	 * compared byte for byte.
	 */
	private fun normalise(text: String): String = text
		.replace(Regex("\"timestamp\"\\s*:\\s*\"[^\"]*\""), "\"timestamp\":\"<t>\"")
		.replace(Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}"), "<t>")
		.replace(Regex("start(ed)?=\\d+"), "start=<n>")
		.replace(Regex("\\b17\\d{8}\\b"), "<epoch>")
		.replace(Regex("\\b18\\d{8}\\b"), "<epoch>")
		// A History row's eventId is minted per row, so it differs between any two
		// runs by design — the same reason atEpochSec is erased above. Parity is
		// about what the row claims about the world, not which row object it was.
		.replace(Regex("eventId=[0-9a-fA-F-]{36}"), "eventId=<id>")

	private fun outcomeOf(outcome: FinalizationOutcome): String = when (outcome) {
		is FinalizationOutcome.Ignored -> "Ignored(${outcome.reason})"
		is FinalizationOutcome.Refused -> "Refused(${outcome.reason})"
		is FinalizationOutcome.Eligible -> "Eligible(${outcome.payloads.size})"
	}

	private fun runThroughEngine(
		snapshots: List<SessionSnapshot>,
		configure: (ReplayEnvironment) -> Unit = {},
	): EngineTrace {
		val env = ReplayEnvironment()
		env.facts.put(*facts())
		configure(env)
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
			duplicateAttempts = env.claims.duplicateAttempts.map { normalise(it) },
			queued = env.retryQueue.all.map { normalise(it.label) },
			queueSize = env.retryQueue.size(),
			recent = FinalizationRuntime.recent.value.map { normalise(it.toString()) },
			skipped = FinalizationRuntime.skipped.value.map { normalise(it.toString()) },
			log = EventLog.lines.value.map { normalise(it) },
		)
		EventLog.applyPolicy(false)
		EventLog.clear()
		return trace
	}

	private fun oldSide(script: List<ParityStep>, configure: (ReplayEnvironment) -> Unit = {}) =
		runThroughEngine(ReferenceRun.snapshots(script, native), configure)

	private fun newSide(script: List<ParityStep>, configure: (ReplayEnvironment) -> Unit = {}) =
		runThroughEngine(CurrentRun.snapshots(script, native), configure)

	/**
	 * Compare every recorded surface, and require the comparison to be about
	 * something.
	 */
	private fun assertEngineParity(
		name: String,
		script: List<ParityStep>,
		configure: (ReplayEnvironment) -> Unit = {},
	): Pair<EngineTrace, EngineTrace> {
		val old = oldSide(script, configure)
		val new = newSide(script, configure)

		assertTrue(
			"$name: the old side produced no terminal outcome — the comparison is vacuous",
			old.outcomes.isNotEmpty(),
		)
		assertEquals("$name: typed terminal outcomes differ", old.outcomes, new.outcomes)
		assertEquals("$name: payload bytes or order differ", old.payloads, new.payloads)
		assertEquals("$name: dedup claims differ", old.dedupClaims, new.dedupClaims)
		assertEquals(
			"$name: duplicate attempts differ",
			old.duplicateAttempts,
			new.duplicateAttempts,
		)
		assertEquals("$name: queue contents differ", old.queued, new.queued)
		assertEquals("$name: queue depth differs", old.queueSize, new.queueSize)
		assertEquals("$name: UI history differs", old.recent, new.recent)
		assertEquals("$name: Not-logged history differs", old.skipped, new.skipped)
		assertEquals("$name: engine decision log differs", old.log, new.log)
		return old to new
	}

	// ── the scripts ────────────────────────────────────────────────────────

	private fun playing(positionMs: Long, speed: Float = 1f) =
		ParityStep.Transport(PlaybackState.STATE_PLAYING, positionMs, speed)

	/** One listen, ended by the next track starting. */
	private fun oneListen(playedMs: Long) = listOf(
		ParityStep.Metadata(
			title = "Rick Astley - Never Gonna Give You Up",
			artist = "RickAstleyVEVO",
			durationMs = 213_000,
			mediaId = first,
		),
		playing(0),
		ParityStep.Advance(playedMs),
		ParityStep.Metadata(
			title = "PSY - GANGNAM STYLE",
			artist = "officialpsy",
			durationMs = 252_000,
			mediaId = second,
		),
	)

	/** The same track twice in a row, which is what the dedup ledger is for. */
	private fun repeatedListen() = listOf(
		ParityStep.Metadata(
			title = "Rick Astley - Never Gonna Give You Up",
			artist = "RickAstleyVEVO",
			durationMs = 213_000,
			mediaId = first,
		),
		playing(0),
		ParityStep.Advance(200_000),
		ParityStep.Metadata(
			title = "PSY - GANGNAM STYLE",
			artist = "officialpsy",
			durationMs = 252_000,
			mediaId = second,
		),
		playing(0),
		ParityStep.Advance(240_000),
		ParityStep.Metadata(
			title = "Rick Astley - Never Gonna Give You Up",
			artist = "RickAstleyVEVO",
			durationMs = 213_000,
			mediaId = first,
		),
		playing(0),
		ParityStep.Advance(200_000),
		ParityStep.Metadata("Something Else", durationMs = 100_000, mediaId = "ccccccccccc"),
	)

	// ── the comparisons ────────────────────────────────────────────────────

	@Test
	fun `an eligible listen produces the same payload, dedup and dispatch on both`() {
		val (old, _) = assertEngineParity("eligible", oneListen(200_000))
		assertTrue("expected an eligible listen, got ${old.outcomes}", old.outcomes.any {
			it.startsWith("Eligible")
		})
		assertTrue("nothing was dispatched", old.payloads.isNotEmpty())
		assertTrue(
			"the exact id from the native media id did not reach the payload",
			old.payloads.any { it.contains(first) },
		)
	}

	@Test
	fun `a refused listen refuses identically, down to the reason`() {
		// Two seconds of a 213-second video: below every threshold.
		val (old, _) = assertEngineParity("refused", oneListen(2_000))
		assertTrue(
			"expected a refusal, got ${old.outcomes}",
			old.outcomes.first().startsWith("Refused"),
		)
		assertEquals("a refused listen dispatched something", emptyList<String>(), old.payloads)
	}

	/**
	 * The same video finalized twice in one run, with a third in between.
	 *
	 * Everything the user can see agrees: three terminal outcomes, the same
	 * payload bytes in the same order, the same dedup claims, the same history.
	 * That is the gate.
	 *
	 * ## The one place they differ, and why it is the fix rather than the break
	 *
	 * `VerifiedPlaybackSequence` is keyed by `TrackInstanceId`, which reads
	 * `trackInstanceToken` and falls back to the frozen start *second* when none
	 * was stamped. All three listens here finalize inside one wall-clock second —
	 * the script's minutes are virtual, and `trackStartedAtEpochSec` is real
	 * `System.currentTimeMillis()` — so on the reference side, which stamps no
	 * token at all (see [AddedFields]), all three collapse into a single slot and
	 * each one overwrites the last. The current implementation keeps three.
	 *
	 * That collision is the entire reason `TrackInstanceId` exists, and this is
	 * the only script in the suite that reproduces it. Asserted in both
	 * directions rather than normalised away: if the current side ever collapsed
	 * them again, or the reference ever stopped collapsing them, this fails.
	 */
	@Test
	fun `a repeated listen reaches the same dedup verdict on both`() {
		val old = oldSide(repeatedListen())
		val new = newSide(repeatedListen())

		assertEquals("expected three terminal outcomes, got ${old.outcomes}", 3, old.outcomes.size)
		assertEquals("typed terminal outcomes differ", old.outcomes, new.outcomes)
		assertEquals("payload bytes or order differ", old.payloads, new.payloads)
		assertEquals("dedup claims differ", old.dedupClaims, new.dedupClaims)
		assertEquals("duplicate attempts differ", old.duplicateAttempts, new.duplicateAttempts)
		assertEquals("queue contents differ", old.queued, new.queued)
		assertEquals("UI history differs", old.recent, new.recent)
		assertEquals("Not-logged history differs", old.skipped, new.skipped)

		// Every log line that is not the sequence state agrees exactly.
		val sequence = { lines: List<String> -> lines.filter { it.contains("[sequence]") } }
		val rest = { lines: List<String> -> lines.filterNot { it.contains("[sequence]") } }
		assertTrue("the engine logged nothing", rest(old.log).isNotEmpty())
		assertEquals("engine decision log differs", rest(old.log), rest(new.log))

		// And the sequence state differs in exactly the declared way.
		assertTrue("no sequence state was recorded", sequence(old.log).isNotEmpty())
		assertNotEquals(
			"the same-second collision the instance token exists to fix has stopped " +
				"appearing — either the reference stamps a token now, or the current " +
				"implementation has stopped",
			sequence(old.log),
			sequence(new.log),
		)
		assertTrue(
			"the reference did not collapse three same-second listens into one slot: " +
				"${sequence(old.log)}",
			sequence(old.log).none { it.count { c -> c == ':' } > 1 },
		)
		assertTrue(
			"the current implementation no longer keeps three distinct listens: " +
				"${sequence(new.log)}",
			sequence(new.log).any { it.count { c -> c == ':' } >= 3 },
		)
	}

	/**
	 * Dispatch fails and the payload goes to the retry queue.
	 *
	 * A dispatch result is not a finalization outcome. The interesting comparison is that
	 * both sides still report `Eligible` while the queue depth, the entry label
	 * and the Not-logged history agree.
	 */
	@Test
	fun `a failed dispatch queues identically on both`() {
		val (old, _) = assertEngineParity("dispatch failure", oneListen(200_000)) { env ->
			env.broadcaster.defaultResult = HiveRpc.BroadcastResult.NetworkFailure("node unreachable")
		}
		assertTrue(
			"the failure did not reach the retry queue: ${old.queued}",
			old.queueSize > 0,
		)
	}

	/**
	 * The broadcaster throws rather than returning a failure.
	 *
	 * A different path through the same code: the guarded-fault case that used to
	 * end a finalization having reported nothing at all.
	 */
	@Test
	fun `a throwing broadcaster is handled identically on both`() {
		assertEngineParity("dispatch throws", oneListen(200_000)) { env ->
			env.broadcaster.throwOnBroadcast = IllegalStateException("socket closed")
		}
	}

	/**
	 * Persistence fails: the queue cannot store the entry it was handed.
	 *
	 * The listen still has to reach one terminal outcome, and both sides have to
	 * agree about what the user is told.
	 */
	@Test
	fun `an unwritable retry queue is handled identically on both`() {
		assertEngineParity("queue storage fails", oneListen(200_000)) { env ->
			env.broadcaster.defaultResult = HiveRpc.BroadcastResult.NetworkFailure("node unreachable")
			env.retryQueue.storageWorks = false
		}
	}

	/** Auto-scrobble off is a boundary meant to be invisible, on both sides. */
	@Test
	fun `a listen ignored by policy is ignored identically on both`() {
		val (old, _) = assertEngineParity("auto-scrobble off", oneListen(200_000)) { env ->
			env.policy.autoScrobble = false
		}
		assertTrue(
			"expected an Ignored outcome, got ${old.outcomes}",
			old.outcomes.all { it.startsWith("Ignored") },
		)
		assertEquals("an ignored listen dispatched something", emptyList<String>(), old.payloads)
	}

	/** A muted video is refused after processing, not ignored before it. */
	@Test
	fun `a muted video is refused identically on both`() {
		assertEngineParity("muted", oneListen(200_000)) { env ->
			env.mutes.mute(first, "not music")
		}
	}

	/**
	 * The video's page cannot be resolved.
	 *
	 * The identity chain's refusal reason is the thing most likely to drift, and
	 * it is the one the audit calls out: control flow that reads refusal *text*
	 * is how "try the next strategy" becomes "all resolution failed".
	 */
	@Test
	fun `an unresolvable video refuses identically on both`() {
		assertEngineParity("unresolvable", oneListen(200_000)) { env ->
			env.facts.failures += first
		}
	}

	// ── controls ───────────────────────────────────────────────────────────

	/**
	 * The control for every assertion in this file.
	 *
	 * They all pass when both sides are correct and when both are wrong in the
	 * same way. Feeding one side a different listen must break the comparison —
	 * if it does not, these tests compare nothing whatever they report.
	 */
	@Test
	fun `negative control - a different listen on one side breaks every surface`() {
		val old = oldSide(oneListen(200_000))
		val shorter = newSide(oneListen(2_000))

		assertNotEquals("outcomes did not notice", old.outcomes, shorter.outcomes)
		assertNotEquals("payloads did not notice", old.payloads, shorter.payloads)
		assertNotEquals("dedup claims did not notice", old.dedupClaims, shorter.dedupClaims)
		assertNotEquals("the log did not notice", old.log, shorter.log)
	}

	/**
	 * The control on the fault injectors themselves.
	 *
	 * A `configure` lambda that silently did nothing would make four of the
	 * comparisons above a re-run of the eligible case under a different name.
	 */
	@Test
	fun `negative control - the injected faults actually change the outcome`() {
		val clean = oldSide(oneListen(200_000))
		val failed = oldSide(oneListen(200_000)) { env ->
			env.broadcaster.defaultResult = HiveRpc.BroadcastResult.NetworkFailure("node unreachable")
		}
		val ignored = oldSide(oneListen(200_000)) { env -> env.policy.autoScrobble = false }

		assertTrue("the clean run did not dispatch", clean.payloads.isNotEmpty())
		assertNotEquals("the dispatch failure changed nothing", clean.queueSize, failed.queueSize)
		assertNotEquals("the policy switch changed nothing", clean.outcomes, ignored.outcomes)
	}
}
