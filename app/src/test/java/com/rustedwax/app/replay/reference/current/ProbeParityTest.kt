package com.rustedwax.app.replay.reference.current

import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.app.replay.reference.phase01.ParityStep
import com.rustedwax.app.replay.reference.phase01.PlaybackState
import com.rustedwax.app.replay.reference.phase01.ReferenceRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeParityTest {

	private val native = YouTubeProbe.YOUTUBE_PACKAGE
	private val browser = "com.brave.browser"

	private fun playing(positionMs: Long, speed: Float = 1f) =
		ParityStep.Transport(PlaybackState.STATE_PLAYING, positionMs, speed)

	private fun paused(positionMs: Long) =
		ParityStep.Transport(PlaybackState.STATE_PAUSED, positionMs)

	private fun stopped(positionMs: Long) =
		ParityStep.Transport(PlaybackState.STATE_STOPPED, positionMs)

	/**
	 * Run one script through both implementations and require agreement.
	 *
	 * The non-vacuity check is not decoration. A script that finalizes nothing on
	 * either side passes every equality below while proving that neither
	 * implementation did useful work. This guard prevents that vacuous pass.
	 */
	/** What one implementation did with a script: the listens and the log. */
	private data class ProbeRun(
		val snapshots: List<SessionSnapshot>,
		val log: List<String>,
	)

	/**
	 * Run one side with the diagnostic log switched on and captured.
	 *
	 * The log has to be enabled around the run and cleared afterwards, or the
	 * next scenario inherits this one's lines and the comparison becomes a
	 * comparison of whatever ran first.
	 */
	private fun capture(block: () -> List<SessionSnapshot>): ProbeRun {
		EventLog.applyPolicy(true)
		EventLog.clear()
		// `finally`, because the whole JVM suite shares one `EventLog`: a scenario
		// that threw while logging was on would leave it on, and the next class to
		// assert "a shadow run writes nothing" would fail somewhere else entirely.
		try {
			val snapshots = block()
			return ProbeRun(snapshots, EventLog.lines.value.toList())
		} finally {
			EventLog.applyPolicy(false)
			EventLog.clear()
		}
	}

	private fun oldRun(script: List<ParityStep>, packageName: String) =
		capture { ReferenceRun.snapshots(script, packageName) }

	private fun newRun(script: List<ParityStep>, packageName: String) =
		capture { CurrentRun.snapshots(script, packageName) }

	private fun assertParity(name: String, script: List<ParityStep>, packageName: String = native) {
		val oldSide = oldRun(script, packageName)
		val newSide = newRun(script, packageName)
		val old = oldSide.snapshots.asListens()
		val new = newSide.snapshots.asListens()

		assertTrue(
			"$name: the old side finalized nothing — this comparison is vacuous",
			old.isNotEmpty(),
		)
		assertEquals("$name: finalized listen count differs", old.size, new.size)
		old.zip(new).forEachIndexed { index, (o, n) ->
			assertEquals("$name: listen $index differs", o, n)
		}
		assertDeclaredDifference(
			name,
			oldSide.snapshots.addedFields(),
			newSide.snapshots.addedFields(),
		)
		assertTrue("$name: the run logged nothing at all", oldSide.log.isNotEmpty())
		assertEquals(
			"$name: the diagnostic log differs",
			oldSide.log.normaliseLog(),
			newSide.log.normaliseLog(),
		)
	}

	/**
	 * The one difference this gate expects, asserted rather than ignored.
	 *
	 * See [AddedFields]: two snapshot fields did not exist at the reference
	 * commit, so the old side is structurally silent on both and the new side
	 * must populate them. Checking only the first half would pass a migration
	 * that had quietly stopped stamping the instance token — which is the field
	 * that keeps two listens inside one second apart.
	 */
	private fun assertDeclaredDifference(
		name: String,
		old: List<AddedFields>,
		new: List<AddedFields>,
	) {
		old.forEachIndexed { index, fields ->
			assertEquals(
				"$name: listen $index — the reference commit had no firstObservedPositionMs",
				null,
				fields.firstObservedPositionMs,
			)
			assertEquals(
				"$name: listen $index — the reference commit had no trackInstanceToken",
				null,
				fields.instanceOrdinal,
			)
		}
		new.forEachIndexed { index, fields ->
			assertEquals(
				"$name: listen $index — the current implementation stopped stamping " +
					"the track instance token",
				index,
				fields.instanceOrdinal,
			)
		}
	}

	/**
	 * A script both sides agree finalizes nothing.
	 *
	 * Some lifecycle boundaries deliberately end with no listen — a user Stop, a
	 * suppressed session — and "neither side scored anything" is the behaviour
	 * under test rather than a vacuous comparison. Kept separate from
	 * [assertParity] so silence can never be mistaken for agreement there.
	 */
	private fun assertBothSilent(
		name: String,
		script: List<ParityStep>,
		packageName: String = native,
	) {
		val old = ReferenceRun.snapshots(script, packageName).asListens()
		val new = CurrentRun.snapshots(script, packageName).asListens()
		assertEquals("$name: the old side scored something", emptyList<Listen>(), old)
		assertEquals("$name: the new side scored something", emptyList<Listen>(), new)
	}

	// ── 1. progress and speed accounting ───────────────────────────────────

	@Test
	fun `a plain listen`() = assertParity(
		"plain",
		listOf(
			ParityStep.Metadata("Sleepwalking", "Bring Me The Horizon", 200_000, "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(60_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	@Test
	fun `pause and resume stops and restarts the clock`() = assertParity(
		"pause/resume",
		listOf(
			ParityStep.Metadata("Sleepwalking", durationMs = 200_000, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(30_000),
			paused(30_000),
			ParityStep.Advance(120_000),
			playing(30_000),
			ParityStep.Advance(30_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	@Test
	fun `accelerated playback scales content time`() = assertParity(
		"2x",
		listOf(
			ParityStep.Metadata("Ethereum 2.0", durationMs = 600_000, mediaId = "aaaaaaaaaaa"),
			playing(0, speed = 2f),
			ParityStep.Advance(60_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	/**
	 * The rate that was in force during a window is the rate that window is
	 * scored at — not the one being switched to. Getting that backwards is the
	 * defect the extraction was most likely to introduce, because the reducer
	 * receives both rates on one input.
	 */
	@Test
	fun `a rate change mid-listen scores each window at its own rate`() = assertParity(
		"rate change",
		listOf(
			ParityStep.Metadata("Trailer", durationMs = 300_000, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(40_000),
			playing(40_000, speed = 1.5f),
			ParityStep.Advance(40_000),
			playing(100_000, speed = 0.5f),
			ParityStep.Advance(40_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	@Test
	fun `an absurd rate is clamped rather than believed`() = assertParity(
		"rate clamp",
		listOf(
			ParityStep.Metadata("Trailer", durationMs = 300_000, mediaId = "aaaaaaaaaaa"),
			playing(0, speed = 64f),
			ParityStep.Advance(60_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	@Test
	fun `a zero rate reads as one rather than stopping the clock twice`() = assertParity(
		"zero rate",
		listOf(
			ParityStep.Metadata("Trailer", durationMs = 300_000, mediaId = "aaaaaaaaaaa"),
			playing(0, speed = 0f),
			ParityStep.Advance(60_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	@Test
	fun `seeking forward and backward does not itself credit or remove time`() = assertParity(
		"seek",
		listOf(
			ParityStep.Metadata("Long", durationMs = 600_000, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(20_000),
			playing(400_000),
			ParityStep.Advance(20_000),
			playing(100_000),
			ParityStep.Advance(20_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	@Test
	fun `a session first seen mid-video records where it was`() = assertParity(
		"lead-in",
		listOf(
			ParityStep.Metadata("Late", durationMs = 227_000, mediaId = "aaaaaaaaaaa"),
			playing(94_000),
			ParityStep.Advance(12_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	// ── loop and wrap ──────────────────────────────────────────────────────

	@Test
	fun `an end-to-start wrap is a loop`() = assertParity(
		"loop",
		listOf(
			ParityStep.Metadata("Looper", durationMs = 100_000, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(95_000),
			playing(95_000),
			playing(500),
			ParityStep.Advance(30_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	@Test
	fun `an ordinary backward seek is not a loop`() = assertParity(
		"seek not loop",
		listOf(
			ParityStep.Metadata("Looper", durationMs = 100_000, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(60_000),
			playing(60_000),
			playing(40_000),
			ParityStep.Advance(20_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	// ── 2. metadata refinement and track transitions ───────────────────────

	@Test
	fun `a later bundle refines the same track rather than replacing it`() = assertParity(
		"refinement",
		listOf(
			ParityStep.Metadata("Happy Song", durationMs = 236_981, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(60_000),
			ParityStep.Metadata("Happy Song", artist = "Bring Me The Horizon", mediaId = "aaaaaaaaaaa"),
			ParityStep.Advance(60_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	@Test
	fun `a bundle that drops the duration does not erase it`() = assertParity(
		"duration dropped",
		listOf(
			ParityStep.Metadata("Happy Song", durationMs = 236_981, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(240_000),
			ParityStep.Metadata("Happy Song", artist = "BMTH", mediaId = "aaaaaaaaaaa"),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	/** A session clearing its throat, not a different track. */
	@Test
	fun `an empty bundle mid-track is held rather than ending the listen`() = assertParity(
		"empty bundle",
		listOf(
			ParityStep.Metadata("Trailer", durationMs = 155_000, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(60_000),
			ParityStep.Metadata(null),
			ParityStep.Advance(1_000),
			ParityStep.Metadata("Trailer", durationMs = 155_000, mediaId = "aaaaaaaaaaa"),
			ParityStep.Advance(60_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	@Test
	fun `first real metadata on a freshly created session starts no phantom`() = assertParity(
		"placeholder handover",
		listOf(
			ParityStep.Metadata(null),
			playing(0),
			ParityStep.Metadata("Real", durationMs = 120_000, mediaId = "aaaaaaaaaaa"),
			ParityStep.Advance(60_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	/**
	 * Two listens ending inside the same wall-clock second.
	 *
	 * The frozen start second cannot tell them apart, which is why the instance
	 * token exists; if either implementation stopped stamping one, the ordinals
	 * in [Listen] collapse and this fails.
	 */
	@Test
	fun `two listens inside one second stay two instances`() = assertParity(
		"same-second instances",
		listOf(
			ParityStep.Metadata("First", durationMs = 100_000, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(100),
			ParityStep.Metadata("Second", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
			playing(0),
			ParityStep.Advance(100),
			ParityStep.Metadata("Third", durationMs = 100_000, mediaId = "ccccccccccc"),
			playing(0),
			ParityStep.Advance(100),
			ParityStep.Metadata("Fourth", durationMs = 100_000, mediaId = "ddddddddddd"),
		),
	)

	@Test
	fun `declared divergence - a shorter replacement no longer earns organic time`() {
		val script = listOf(
			ParityStep.Metadata("Barbie Dreams", durationMs = 301_000),
			playing(0),
			ParityStep.Advance(280_000),
			ParityStep.Metadata("Barbie Dreams", durationMs = 13_000),
			ParityStep.Advance(5_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		)
		val oldSide = oldRun(script, native)
		val newSide = newRun(script, native)
		val old = oldSide.snapshots.asListens()
		val new = newSide.snapshots.asListens()

		assertEquals(1, old.size)
		assertEquals(1, new.size)
		assertEquals(285_000L, old.single().playedMs)
		assertEquals(280_000L, new.single().playedMs)
		assertEquals(
			"the repair changed more than replacement-interval measurement",
			old.single().copy(
				playedMs = new.single().playedMs,
				percentPlayed = new.single().percentPlayed,
			),
			new.single(),
		)
		assertTrue(
			"the current run did not record duration-replacement quarantine",
			newSide.log.any { it.contains("quarantining replacement measurement") },
		)
		assertNotEquals(
			"the diagnostic log hid the intentional measurement change",
			oldSide.log.normaliseLog(),
			newSide.log.normaliseLog(),
		)
	}

	@Test
	fun `an upward exact-ID-less duration supersedes an anchor it dwarfs`() {
		val script = listOf(
			ParityStep.Metadata("Same Title", durationMs = 30_000),
			playing(0),
			ParityStep.Advance(20_000),
			ParityStep.Metadata("Same Title", durationMs = 300_000),
			playing(0),
			ParityStep.Advance(200_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		)
		val oldSide = oldRun(script, native)
		val newSide = newRun(script, native)
		val old = oldSide.snapshots.asListens()
		val new = newSide.snapshots.asListens()

		assertEquals(1, old.size)
		assertEquals(1, new.size)
		assertEquals(
			"the 20s provisional surface may not be credited to the 300s presentation",
			200_000L,
			new.single().playedMs,
		)
		assertEquals(
			"the repair changed more than which presentation anchors the listen",
			old.single().copy(
				playedMs = new.single().playedMs,
				percentPlayed = new.single().percentPlayed,
			),
			new.single(),
		)
		assertTrue(
			"the current run did not record the superseded provisional anchor",
			newSide.log.any { it.contains("never established an organic anchor") },
		)
		assertTrue(
			"a superseded provisional anchor must not also be quarantined",
			newSide.log.none { it.contains("quarantining replacement measurement") },
		)
	}

	@Test
	fun `an upward exact-ID-less duration inside the scale gap is quarantined`() {
		val script = listOf(
			ParityStep.Metadata("Same Title", durationMs = 1_192_000),
			playing(0),
			ParityStep.Advance(40_000),
			ParityStep.Metadata("Same Title", durationMs = 2_163_000),
			playing(0),
			ParityStep.Advance(47_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		)
		val oldSide = oldRun(script, native)
		val newSide = newRun(script, native)
		val old = oldSide.snapshots.asListens()
		val new = newSide.snapshots.asListens()

		assertEquals(1, old.size)
		assertEquals(1, new.size)
		// The old reference restarted its clock on the longer surface and kept only
		// the sponsored interval; the current run keeps the established listen.
		assertEquals(47_000L, old.single().playedMs)
		assertEquals(
			"the 47s longer surface may not be credited once the anchor was earned",
			40_000L,
			new.single().playedMs,
		)
		// Declared divergence, intentional: the reference adopts the quarantined
		// 2,163s surface as the listen's length because `establishedDurationMs`
		// took the largest number anyone published. The current run keeps the
		// 1,192s presentation it actually measured — length authority follows
		// measurement — so the *duration* differs as well as the played time.
		assertEquals(
			"the reference no longer adopts the quarantined surface as the length",
			2_163_000L,
			old.single().durationMs,
		)
		assertEquals(
			"a presentation the listen refused to measure defined its length",
			1_192_000L,
			new.single().durationMs,
		)
		assertEquals(
			"the repair changed more than replacement-interval measurement and length",
			old.single().copy(
				playedMs = new.single().playedMs,
				percentPlayed = new.single().percentPlayed,
				durationMs = new.single().durationMs,
			),
			new.single(),
		)
		assertTrue(
			"the current run did not record bidirectional duration quarantine",
			newSide.log.any { it.contains("quarantining replacement measurement") },
		)
		assertNotEquals(
			"the diagnostic log hid the intentional measurement change",
			oldSide.log.normaliseLog(),
			newSide.log.normaliseLog(),
		)
	}

	@Test
	fun `a track with no length at all still finalizes`() = assertParity(
		"no duration",
		listOf(
			ParityStep.Metadata("Live Stream", mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(120_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	// ── 3. destruction, replacement and continuation ───────────────────────

	/**
	 * The carry window: a native session with an exact id parks its progress and
	 * the replacement claims it, so one listen keeps one instance across two
	 * controllers.
	 */
	@Test
	fun `progress carries across a replaced session`() = assertParity(
		"replacement carry",
		listOf(
			ParityStep.Metadata("Carried", durationMs = 240_000, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(120_000),
			ParityStep.DestroySession,
			ParityStep.ReplaceSession,
			ParityStep.Metadata("Carried", durationMs = 240_000, mediaId = "aaaaaaaaaaa"),
			playing(120_000),
			ParityStep.Advance(60_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	@Test
	fun `a wrap across a session restart is a loop`() = assertParity(
		"restart wrap",
		listOf(
			ParityStep.Metadata("Looper", durationMs = 100_000, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(95_000),
			playing(95_000),
			ParityStep.DestroySession,
			ParityStep.ReplaceSession,
			ParityStep.Metadata("Looper", durationMs = 100_000, mediaId = "aaaaaaaaaaa"),
			playing(500),
			ParityStep.Advance(30_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	/** No exact id: a native continuation is refused rather than carried. */
	@Test
	fun `an exact-ID-less native session refuses to carry`() = assertParity(
		"carry refused",
		listOf(
			ParityStep.Metadata("Nameless", durationMs = 240_000),
			playing(0),
			ParityStep.Advance(120_000),
			ParityStep.DestroySession,
			ParityStep.ReplaceSession,
			ParityStep.Metadata("Nameless", durationMs = 240_000),
			playing(120_000),
			ParityStep.Advance(60_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	/**
	 * A parked listen the next track proves will never resume.
	 *
	 * The continuation is opened by the teardown, then a *different* exactly
	 * identified track starts in the same app — which is proof the first one is
	 * not coming back. The carry is displaced, and the owner's polling tick
	 * collects the fragment and finalizes it exactly once.
	 *
	 * This is the reachable half of continuation expiry; the deadline half is
	 * wall-clock bound on both sides — see
	 * [known limitation - the continuation deadline is wall-clock on both sides].
	 */
	@Test
	fun `a displaced continuation is finalized by its owner`() = assertParity(
		"continuation displaced",
		listOf(
			ParityStep.Metadata("Abandoned", durationMs = 1_800_000, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(150_000),
			ParityStep.DestroySession,
			ParityStep.ReplaceSession,
			ParityStep.Metadata("A Different Video", durationMs = 200_000, mediaId = "bbbbbbbbbbb"),
			playing(0),
			ParityStep.Advance(90_000),
			ParityStep.Metadata("A Third", durationMs = 200_000, mediaId = "ccccccccccc"),
		),
	)

	/**
	 * Departure by the active-session list rather than by `onSessionDestroyed`.
	 *
	 * A different production entry point — `syncControllers` disposing a watch
	 * whose controller has gone — reaching the same decision. Exact-ID-less so the
	 * native carry is refused and the listen is scored on the spot, which is what
	 * makes this observable at all rather than parked behind a wall-clock TTL.
	 */
	@Test
	fun `a session that leaves the active list ends its listen`() = assertParity(
		"removed from active",
		listOf(
			ParityStep.Metadata("Removed", durationMs = 240_000),
			playing(0),
			ParityStep.Advance(150_000),
			ParityStep.RemoveSession,
		),
	)

	/**
	 * **A known limitation, asserted rather than claimed.**
	 *
	 * `TrackProgressCarry.expire` compares `System.currentTimeMillis()` against
	 * the entry's own `atMillis`; neither implementation routes that through the
	 * injectable clock, and the reference body is frozen, so no amount of virtual
	 * time expires a parked continuation on either side. The unclaimed-deadline
	 * path is therefore **not covered by this JVM gate** — it is the instrumented
	 * suite's, where the timer and the clock are the real ones.
	 *
	 * Written as a test because a limitation recorded only in prose stops being
	 * true without anyone noticing. This fails the moment either side starts
	 * honouring virtual time here, which is the change that would make the gap
	 * closeable.
	 */
	@Test
	fun `known limitation - the continuation deadline is wall-clock on both sides`() {
		val script = listOf(
			ParityStep.Metadata("Abandoned", durationMs = 1_800_000, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(150_000),
			ParityStep.DestroySession,
			ParityStep.Advance(30 * 60_000),
		)
		val old = oldRun(script, native)
		val new = newRun(script, native)

		assertEquals(
			"the reference expired a wall-clock TTL on virtual time",
			0,
			old.snapshots.size,
		)
		assertEquals(
			"the current side expired a wall-clock TTL on virtual time",
			0,
			new.snapshots.size,
		)

		// Non-vacuity: both sides really did park the listen rather than simply
		// dropping it, which is what makes "neither expired it" a statement about
		// the deadline instead of about the carry never having happened.
		assertTrue(
			"the reference never opened a continuation: ${old.log}",
			old.log.any { it.contains("before finalizing") },
		)
		assertTrue(
			"the current side never opened a continuation: ${new.log}",
			new.log.any { it.contains("before finalizing") },
		)
		assertEquals(
			"the two sides describe the wait differently",
			old.log.normaliseLog(),
			new.log.normaliseLog(),
		)
	}

	// ── 4. finalization causes ─────────────────────────────────────────────

	@Test
	fun `stopped ends a browser listen immediately`() = assertParity(
		"browser stopped",
		listOf(
			ParityStep.Metadata("Browser Track", durationMs = 200_000),
			playing(0),
			ParityStep.Advance(150_000),
			stopped(150_000),
			ParityStep.Advance(60_000),
		),
		packageName = browser,
	)

	/**
	 * Native `STOPPED` with no exact id waits for the replacement bundle instead
	 * of splitting one transition into an unidentifiable fragment.
	 */
	@Test
	fun `native stopped waits out its grace before finalizing`() = assertParity(
		"native stopped grace",
		listOf(
			ParityStep.Metadata("Nameless", durationMs = 200_000),
			playing(0),
			ParityStep.Advance(150_000),
			stopped(150_000),
			ParityStep.Advance(120_000),
		),
	)

	@Test
	fun `declared divergence - STOPPED then fresh metadata starts a new non-loop generation`() {
		val script = listOf(
			ParityStep.Metadata("Nameless", durationMs = 200_000),
			playing(0),
			ParityStep.Advance(150_000),
			stopped(150_000),
			ParityStep.Advance(500),
			ParityStep.Metadata("The Next One", durationMs = 100_000),
			playing(0),
			ParityStep.Advance(80_000),
			ParityStep.Metadata("Third", durationMs = 100_000),
		)
		val oldSide = oldRun(script, native)
		val newSide = newRun(script, native)
		val old = oldSide.snapshots.asListens()
		val new = newSide.snapshots.asListens()

		assertEquals(2, old.size)
		assertEquals(2, new.size)
		assertTrue("the recorded reference no longer misclassifies the boundary as a loop", old.last().loopDetected)
		assertTrue("the current playback generation still inherited the old wrap", !new.last().loopDetected)
		assertEquals(
			"metadata freshness changed more than the false loop attribution",
			old.map { it.copy(loopDetected = false) },
			new,
		)
		assertTrue(
			"the current run did not record the lifecycle authority it used",
			newSide.log.any {
				it.contains("PLAYING began after STOPPED with metadata published after the boundary")
			},
		)
	}

	@Test
	fun `system teardown scores what was in flight`() = assertParity(
		"system teardown",
		listOf(
			ParityStep.Metadata("In Flight", durationMs = 200_000, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(150_000),
			ParityStep.StopProbe(finalizeTracks = true),
		),
		packageName = browser,
	)

	/** A Stop button that writes to an immutable chain on its way out is a bad one. */
	@Test
	fun `user Stop discards what was in flight`() = assertBothSilent(
		"user stop",
		listOf(
			ParityStep.Metadata("In Flight", durationMs = 200_000, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(150_000),
			ParityStep.StopProbe(finalizeTracks = false),
			ParityStep.Advance(120_000),
		),
	)

	@Test
	fun `the listener coming back re-attaches to what is still playing`() = assertParity(
		"listener recreation",
		listOf(
			ParityStep.Metadata("Across A Rebuild", durationMs = 400_000, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(100_000),
			ParityStep.StopProbe(finalizeTracks = true),
			ParityStep.Advance(2_000),
			ParityStep.StartProbe,
			ParityStep.Metadata("Across A Rebuild", durationMs = 400_000, mediaId = "aaaaaaaaaaa"),
			playing(100_000),
			ParityStep.Advance(100_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	@Test
	fun `declared divergence - only the current implementation reports a resumed lead-in`() {
		val script = listOf(
			ParityStep.Metadata("Resumed", durationMs = 27_000),
			playing(2_372),
			ParityStep.Advance(25_069),
			stopped(27_349),
			paused(27_349),
			ParityStep.Metadata("Resumed", durationMs = 6_000),
			playing(0),
			ParityStep.Advance(6_300),
			ParityStep.Metadata("Resumed", durationMs = 1_192_000),
			playing(6_058),
			playing(1_068_016),
			ParityStep.Advance(124_568),
			stopped(1_192_367),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		)
		val oldSide = oldRun(script, native)
		val newSide = newRun(script, native)
		val old = oldSide.snapshots.asListens()
		val new = newSide.snapshots.asListens()

		assertEquals(
			"the divergence is in what the listen says, not what it counted",
			old.map { it.playedMs },
			new.map { it.playedMs },
		)
		assertTrue(
			"the reference already reported the lead-in — this is no longer a divergence",
			oldSide.log.none { it.contains("first seen") },
		)
		assertTrue(
			"the current run did not report the unobserved lead-in: " +
				newSide.log.filter { it.contains("[finalize]") },
			newSide.log.any { it.contains("first seen 1068s in") },
		)
	}

	@Test
	fun `declared divergence - only the current implementation bounds a silent listen`() {
		val script = listOf(
			ParityStep.Metadata("Runs Out", durationMs = 186_000),
			playing(0),
			ParityStep.Advance(3_600_000),
		)
		val old = ReferenceRun.snapshots(script, browser).asListens()
		val new = CurrentRun.snapshots(script, browser).asListens()

		assertEquals("the reference finalized more than one listen", 1, old.size)
		assertEquals("the current implementation finalized more than one listen", 1, new.size)
		assertEquals(
			"the reference no longer accrues the unbounded hour this bounds",
			3_600_000L,
			old.first().playedMs,
		)
		assertEquals(
			"the idle deadline no longer stops at the item's own length plus grace",
			186_000L + 30_000L,
			new.first().playedMs,
		)
		// Everything else about the listen still has to agree: the divergence is a
		// bound on played time, not a different listen.
		assertEquals(old.first().title, new.first().title)
		assertEquals(old.first().durationMs, new.first().durationMs)
		assertEquals(old.first().identity, new.first().identity)
		assertEquals(old.first().metadataLines, new.first().metadataLines)
	}

	/**
	 * The Stop button, and the regression this gate found.
	 *
	 * A user Stop must not write to an immutable chain on its way out. The old
	 * implementation cannot: it has no deadline to leave behind. The current one
	 * acquired one with `IdleFinalization`, and until `AndroidSessionBinding.dispose`
	 * cancelled it, a Stop at 150 s of a 200 s track scored that track 80 s later
	 * from a timer the Stop was supposed to have ended.
	 *
	 * Kept as a named scenario rather than folded into [assertBothSilent] above so
	 * the failure it guards is legible from the test name.
	 */
	@Test
	fun `a stopped probe leaves no timer that can finalize afterwards`() {
		val script = listOf(
			ParityStep.Metadata("In Flight", durationMs = 200_000, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(150_000),
			ParityStep.StopProbe(finalizeTracks = false),
			// Well past the deadline the listen had armed: (200s - 0s) + 30s grace.
			ParityStep.Advance(600_000),
		)
		assertEquals(
			"a discarded listen was scored by a timer that outlived the Stop",
			emptyList<Listen>(),
			CurrentRun.snapshots(script, native).asListens(),
		)
		assertEquals(
			"the reference scored something after a Stop",
			emptyList<Listen>(),
			ReferenceRun.snapshots(script, native).asListens(),
		)
	}

	// ── the browser tab-title state ────────────────────────────────────────

	@Test
	fun `a browser naming only its tab ends the track and credits the gap to nobody`() =
		assertParity(
			"tab title",
			listOf(
				ParityStep.Metadata("Real Track", "Some Channel", 200_000),
				playing(0),
				ParityStep.Advance(120_000),
				ParityStep.Metadata("YouTube", "youtube.com"),
				ParityStep.Advance(420_000),
				ParityStep.Metadata("The Next Track", "Another Channel", 200_000),
				playing(0),
				ParityStep.Advance(150_000),
				ParityStep.Metadata("A Third", durationMs = 200_000),
			),
			packageName = browser,
		)

	// ── 5. picture-in-picture measurement ──────────────────────────────────

	/**
	 * A native session with no readable progress, credited from the
	 * picture-in-picture inference.
	 *
	 * Restoring the old `Watch` silently breaks value-typed PiP accumulation,
	 * because the old
	 * code advanced a mutable accumulator by side effect and the replacement
	 * returns its successor. Two implementations with different mutation
	 * semantics have to credit the same milliseconds here or the change was not
	 * behaviour-preserving.
	 */
	@Test
	fun `picture-in-picture credits an unmeasurable session identically`() = assertParity(
		"pip",
		listOf(
			ParityStep.Metadata("PiP Short", durationMs = 60_000, mediaId = "aaaaaaaaaaa"),
			ParityStep.Transport(PlaybackState.STATE_NONE, 0),
			ParityStep.PictureInPicture(playing = true, atMillis = 1_000_000),
			ParityStep.Advance(5_000),
			ParityStep.PictureInPicture(playing = true, atMillis = 1_005_000),
			ParityStep.Advance(5_000),
			ParityStep.PictureInPicture(playing = true, atMillis = 1_010_000),
			ParityStep.Advance(5_000),
			ParityStep.PictureInPicture(playing = true, atMillis = 1_015_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	/** A session that publishes its own progress must never be credited twice. */
	@Test
	fun `picture-in-picture never double-counts a measurable session`() = assertParity(
		"pip refused",
		listOf(
			ParityStep.Metadata("Measurable", durationMs = 240_000, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.PictureInPicture(playing = true, atMillis = 1_000_000),
			ParityStep.Advance(60_000),
			ParityStep.PictureInPicture(playing = true, atMillis = 1_060_000),
			ParityStep.Advance(60_000),
			ParityStep.PictureInPicture(playing = true, atMillis = 1_120_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	@Test
	fun `a paused picture-in-picture observation credits nothing`() = assertParity(
		"pip paused",
		listOf(
			ParityStep.Metadata("PiP Short", durationMs = 60_000, mediaId = "aaaaaaaaaaa"),
			ParityStep.Transport(PlaybackState.STATE_NONE, 0),
			ParityStep.PictureInPicture(playing = true, atMillis = 1_000_000),
			ParityStep.Advance(5_000),
			ParityStep.PictureInPicture(playing = false, atMillis = 1_005_000),
			ParityStep.Advance(30_000),
			ParityStep.PictureInPicture(playing = false, atMillis = 1_035_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		),
	)

	// ── controls ───────────────────────────────────────────────────────────

	/**
	 * Every assertion above passes when both sides are correct *and* when both are
	 * wrong in the same way. These prove the comparison can tell the difference.
	 *
	 * Perturbing the *script* on one side is the sensitivity test available once
	 * both sides are real implementations — there is no sabotage knob to turn on a
	 * byte-identical mirror, and adding one would be adding a third implementation.
	 */
	@Test
	fun `negative control - one extra second on one side is noticed`() {
		val script = listOf(
			ParityStep.Metadata("Sleepwalking", durationMs = 200_000, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(60_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		)
		val longer = listOf(
			ParityStep.Metadata("Sleepwalking", durationMs = 200_000, mediaId = "aaaaaaaaaaa"),
			playing(0),
			ParityStep.Advance(61_000),
			ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
		)
		val oldSide = oldRun(script, native)
		val newSide = newRun(longer, native)
		val old = oldSide.snapshots.asListens()
		val new = newSide.snapshots.asListens()

		assertTrue("nothing to compare", old.isNotEmpty() && new.isNotEmpty())
		assertNotEquals("a one-second difference went unnoticed", old.first(), new.first())
		// The log comparison has to be sensitive too, or it is 300 lines of
		// agreement that would agree with anything.
		assertNotEquals(
			"the diagnostic log did not notice a one-second difference",
			oldSide.log.normaliseLog(),
			newSide.log.normaliseLog(),
		)
	}

	/**
	 * The other side of the same question: the *reference* must genuinely execute
	 * the behaviour, not sit at a default that agrees with anything.
	 */
	@Test
	fun `negative control - the reference genuinely measures, carries and loops`() {
		val idle = ReferenceRun.snapshots(
			listOf(
				ParityStep.Metadata("Sleepwalking", durationMs = 200_000, mediaId = "aaaaaaaaaaa"),
				paused(0),
				ParityStep.Advance(60_000),
				ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
			),
			native,
		).asListens()
		val played = ReferenceRun.snapshots(
			listOf(
				ParityStep.Metadata("Sleepwalking", durationMs = 200_000, mediaId = "aaaaaaaaaaa"),
				playing(0),
				ParityStep.Advance(60_000),
				ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
			),
			native,
		).asListens()

		assertTrue("the reference finalized nothing at all", played.isNotEmpty())
		assertEquals("a paused reference measured something", 0L, idle.first().playedMs)
		assertNotEquals(
			"the reference reports the same time paused as playing",
			idle.first().playedMs,
			played.first().playedMs,
		)
	}

	/**
	 * The carry comparison would be vacuous if neither side carried anything, and
	 * "both refused" looks identical to "both succeeded" in a field-by-field
	 * equality. This asserts the behaviour is present before parity claims it
	 * agrees.
	 */
	@Test
	fun `non-vacuity - the replacement carry script really does carry`() {
		val carried = ReferenceRun.snapshots(
			listOf(
				ParityStep.Metadata("Carried", durationMs = 240_000, mediaId = "aaaaaaaaaaa"),
				playing(0),
				ParityStep.Advance(120_000),
				ParityStep.DestroySession,
				ParityStep.ReplaceSession,
				ParityStep.Metadata("Carried", durationMs = 240_000, mediaId = "aaaaaaaaaaa"),
				playing(120_000),
				ParityStep.Advance(60_000),
				ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
			),
			native,
		).asListens()

		assertTrue("nothing finalized", carried.isNotEmpty())
		assertTrue(
			"the replacement session did not reclaim the first 120s: " +
				"played ${carried.first().playedMs}ms",
			carried.first().playedMs >= 180_000,
		)
	}

	/** Likewise for picture-in-picture: agreement on zero would prove nothing. */
	@Test
	fun `non-vacuity - the picture-in-picture script really does infer time`() {
		val inferred = ReferenceRun.snapshots(
			listOf(
				ParityStep.Metadata("PiP Short", durationMs = 60_000, mediaId = "aaaaaaaaaaa"),
				ParityStep.Transport(PlaybackState.STATE_NONE, 0),
				ParityStep.PictureInPicture(playing = true, atMillis = 1_000_000),
				ParityStep.Advance(5_000),
				ParityStep.PictureInPicture(playing = true, atMillis = 1_005_000),
				ParityStep.Advance(5_000),
				ParityStep.PictureInPicture(playing = true, atMillis = 1_010_000),
				ParityStep.Advance(5_000),
				ParityStep.PictureInPicture(playing = true, atMillis = 1_015_000),
				ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
			),
			native,
		).asListens()

		assertTrue("nothing finalized", inferred.isNotEmpty())
		assertTrue(
			"the reference inferred no picture-in-picture time at all",
			inferred.first().inferredPlayedMs > 0,
		)
	}
}
