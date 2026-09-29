package com.rustedwax.app.replay.reference.current

import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.app.replay.reference.phase01.ParityStep
import com.rustedwax.app.replay.reference.phase01.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #9: a Brave network stall must not be credited as playback.
 *
 * Driven through the shipping `SessionProbe` (the generated mirror), its
 * `MediaSessionDriver` and the real idle-finalization timer on virtual time —
 * the same path a device takes. The stall shape is the one the A36 published
 * for every second of two separate four-minute freezes: PLAYING, the position
 * frozen where the buffer ran out, and a rate of exactly 0.0. Nothing else.
 */
class BrowserStallCreditTest {

	private val brave = "com.brave.browser"
	private val native = YouTubeProbe.YOUTUBE_PACKAGE

	private fun playing(positionMs: Long, speed: Float = 1f) =
		ParityStep.Transport(PlaybackState.STATE_PLAYING, positionMs, speed)

	private fun paused(positionMs: Long) =
		ParityStep.Transport(PlaybackState.STATE_PAUSED, positionMs)

	private fun stalled(positionMs: Long) = playing(positionMs, speed = 0f)

	/** Ends whatever is in flight the way a system teardown does, so it is scored. */
	private val teardown = ParityStep.StopProbe(finalizeTracks = true)

	private class Run(val listens: List<SessionSnapshot>, val log: List<String>)

	private fun run(script: List<ParityStep>, packageName: String = brave): Run {
		EventLog.applyPolicy(true)
		EventLog.clear()
		try {
			val listens = CurrentRun.snapshots(script, packageName)
			return Run(listens, EventLog.lines.value.toList())
		} finally {
			EventLog.applyPolicy(false)
			EventLog.clear()
		}
	}

	/**
	 * The idle deadline ended the listen as used up. Matched on the reducer's own
	 * note: the `[finalize]` line names the title, and the privacy gate keeps it
	 * out of this log.
	 */
	private fun Run.ranOut() = log.any {
		it.contains("with no further transport update; treating the listen as ended")
	}

	@Test
	fun `ordinary Brave playback is measured as before`() {
		val run = run(
			listOf(
				ParityStep.Metadata("Browser Track", durationMs = 200_000),
				playing(0),
				ParityStep.Advance(60_000),
				paused(60_000),
				ParityStep.Advance(5_000),
				teardown,
			),
		)
		assertEquals(60_000L, run.listens.single().playedMs)
	}

	@Test
	fun `a stall after real progress keeps the progress and credits none of the freeze`() {
		val run = run(
			listOf(
				ParityStep.Metadata("Meteora (Full Album)", durationMs = 2_198_361),
				playing(0),
				ParityStep.Advance(107_976),
				stalled(107_976),
				ParityStep.Advance(243_690),
				teardown,
			),
		)
		assertEquals(
			"the 108 s played before the stall must be banked at its own rate, the freeze at zero",
			107_976L,
			run.listens.single().playedMs,
		)
	}

	/** The A36 case: an 8:02 song frozen at 0:54 was finalized as 100%. */
	@Test
	fun `a prolonged freeze never reaches the idle deadline's false completion`() {
		val run = run(
			listOf(
				ParityStep.Metadata("Stairway To Heaven", durationMs = 482_000),
				playing(0),
				ParityStep.Advance(55_021),
				stalled(55_021),
				// Far longer than the old deadline (remaining length + grace).
				ParityStep.Advance(30 * 60_000),
				teardown,
			),
		)
		assertFalse("the frozen listen was ended as a completed one", run.ranOut())
		val listen = run.listens.single()
		assertEquals(55_021L, listen.playedMs)
		assertTrue(
			"a 55 s listen of a 482 s item crossed the threshold",
			(listen.percentPlayed ?: 0.0) < 0.6,
		)
	}

	@Test
	fun `a positive-rate resume continues the same listen from the frozen position`() {
		val run = run(
			listOf(
				ParityStep.Metadata("Stairway To Heaven", durationMs = 482_000),
				playing(0),
				ParityStep.Advance(55_021),
				stalled(55_021),
				ParityStep.Advance(12 * 60_000),
				playing(55_250),
				ParityStep.Advance(60_000),
				paused(115_250),
				ParityStep.Advance(5_000),
				teardown,
			),
		)
		assertFalse(run.ranOut())
		val listen = run.listens.single()
		assertEquals("one listen, not a finalized one plus a second session", 1, run.listens.size)
		assertEquals(115_021L, listen.playedMs)
	}

	@Test
	fun `a stall cannot carry a listen across the scrobble threshold`() {
		val run = run(
			listOf(
				ParityStep.Metadata("Browser Track", durationMs = 200_000),
				playing(0),
				ParityStep.Advance(100_000),
				stalled(100_000),
				ParityStep.Advance(300_000),
				playing(100_000),
				ParityStep.Advance(10_000),
				paused(110_000),
				ParityStep.Advance(5_000),
				teardown,
			),
		)
		val listen = run.listens.single()
		assertEquals(110_000L, listen.playedMs)
		assertTrue((listen.percentPlayed ?: 0.0) < 0.6)
	}

	@Test
	fun `a Brave listen that really plays out is still ended by the idle deadline`() {
		val run = run(
			listOf(
				ParityStep.Metadata("Browser Track", durationMs = 200_000),
				playing(0),
				ParityStep.Advance(260_000),
			),
		)
		assertTrue("an organic run-out no longer finalizes", run.ranOut())
		assertEquals(200_000L, run.listens.single().playedMs)
	}

	@Test
	fun `an unknown position is measured as before, whatever the rate`() {
		val run = run(
			listOf(
				ParityStep.Metadata("Browser Track", durationMs = 200_000),
				playing(-1, speed = 0f),
				ParityStep.Advance(30_000),
				teardown,
			),
		)
		assertEquals(30_000L, run.listens.single().playedMs)
	}

	@Test
	fun `a native source still reads a zero rate as one`() {
		val run = run(
			listOf(
				ParityStep.Metadata("Trailer", durationMs = 300_000, mediaId = "aaaaaaaaaaa"),
				playing(0, speed = 0f),
				ParityStep.Advance(60_000),
				ParityStep.Metadata("Next", durationMs = 100_000, mediaId = "bbbbbbbbbbb"),
			),
			packageName = native,
		)
		assertEquals(60_000L, run.listens.first().playedMs)
	}
}
