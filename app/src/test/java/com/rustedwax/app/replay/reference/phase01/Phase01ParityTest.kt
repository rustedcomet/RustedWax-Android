package com.rustedwax.app.replay.reference.phase01

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Old and new, on identical input.
 *
 * Each script runs through the authentic pre-migration `SessionProbe` and
 * through `PlaybackReducer`, and the finalized listens are compared field by
 * field. See `Phase01Parity.kt` for exactly what is and is not under comparison
 * — in particular, the production Android adapter is not, and is reported as an
 * open gate rather than covered here.
 */
class Phase01ParityTest {

	private fun assertParity(script: List<ParityStep>) {
		val old = ReferenceRun.play(script)
		val new = ReducerRun().play(script)
		// Non-vacuity. `0 == 0` is a passing comparison of nothing, and a script
		// that finalizes on neither side would sail through every assertion below
		// while proving that neither implementation exists.
		assertTrue(
			"the script finalized nothing on the old side — this comparison is vacuous",
			old.isNotEmpty(),
		)
		assertEquals("finalized listen count differs", old.size, new.size)
		old.zip(new).forEachIndexed { index, (o, n) ->
			assertEquals("listen $index: played ms", o.playedMs, n.playedMs)
			assertEquals("listen $index: duration", o.durationMs, n.durationMs)
			assertEquals("listen $index: loop", o.loopDetected, n.loopDetected)
			assertEquals("listen $index: title", o.title, n.title)
		}
	}

	private fun playing(positionMs: Long, speed: Float = 1f) =
		ParityStep.Transport(PlaybackState.STATE_PLAYING, positionMs, speed)

	private fun paused(positionMs: Long) =
		ParityStep.Transport(PlaybackState.STATE_PAUSED, positionMs)

	@Test
	fun `a plain listen measures the same on both implementations`() {
		assertParity(
			listOf(
				ParityStep.Metadata("Sleepwalking", "Bring Me The Horizon", 200_000),
				playing(0),
				ParityStep.Advance(60_000),
				ParityStep.DestroySession,
			),
		)
	}

	@Test
	fun `the paused clock stops on both implementations`() {
		assertParity(
			listOf(
				ParityStep.Metadata("Sleepwalking", durationMs = 200_000),
				playing(0),
				ParityStep.Advance(30_000),
				paused(30_000),
				ParityStep.Advance(120_000),
				playing(30_000),
				ParityStep.Advance(30_000),
				ParityStep.DestroySession,
			),
		)
	}

	/**
	 * Speed scaling is the rule with the most field history behind it — a video
	 * watched in full at 2× used to read 50% and never scrobble — so it is the
	 * one most worth proving the replacement kept.
	 */
	@Test
	fun `accelerated playback scales content time identically`() {
		assertParity(
			listOf(
				ParityStep.Metadata("Ethereum 2.0", durationMs = 600_000),
				playing(0, speed = 2f),
				ParityStep.Advance(60_000),
				ParityStep.DestroySession,
			),
		)
	}

	@Test
	fun `a metadata change ends one listen and starts the next on both`() {
		assertParity(
			listOf(
				ParityStep.Metadata("First", durationMs = 100_000, mediaId = "aaaaaaaaaaa"),
				playing(0),
				ParityStep.Advance(40_000),
				ParityStep.Metadata("Second", durationMs = 150_000, mediaId = "bbbbbbbbbbb"),
				playing(0),
				ParityStep.Advance(50_000),
				ParityStep.DestroySession,
			),
		)
	}

	/**
	 * The control that makes the rest of this file mean something.
	 *
	 * Every assertion above passes when both sides are correct *and* when both
	 * sides are wrong in the same way — and the second case is the one a
	 * same-implementation replay cannot tell apart. So one side is deliberately
	 * broken here, in the specific way the migration was most likely to break it,
	 * and the comparison is required to notice.
	 *
	 * If this test ever passes, the parity assertions above are decorative and
	 * the gate is unproven regardless of what they report.
	 */
	@Test
	fun `negative control - a reducer measuring at the wrong rate fails parity`() {
		val script = listOf(
			ParityStep.Metadata("Sleepwalking", durationMs = 200_000),
			playing(0),
			ParityStep.Advance(60_000),
			ParityStep.DestroySession,
		)

		val old = ReferenceRun.play(script)
		val sabotaged = ReducerRun(speedScale = 1.25).play(script)

		assertTrue("the script must finalize something to compare", old.isNotEmpty())
		assertEquals(old.size, sabotaged.size)
		assertNotEquals(
			"a 1.25x measurement error went unnoticed — the comparison is not comparing",
			old.first().playedMs,
			sabotaged.first().playedMs,
		)
	}

	/**
	 * A second control, on the other side of the comparison.
	 *
	 * The first control breaks the new implementation. This one proves the
	 * comparison is not simply insensitive to the *old* one by checking that the
	 * reference genuinely measures — a reference stuck at zero would agree with a
	 * broken reducer as readily as with a correct one.
	 */
	@Test
	fun `negative control - the reference genuinely measures playback`() {
		val idle = ReferenceRun.play(
			listOf(
				ParityStep.Metadata("Sleepwalking", durationMs = 200_000),
				paused(0),
				ParityStep.Advance(60_000),
				ParityStep.DestroySession,
			),
		)
		val played = ReferenceRun.play(
			listOf(
				ParityStep.Metadata("Sleepwalking", durationMs = 200_000),
				playing(0),
				ParityStep.Advance(60_000),
				ParityStep.DestroySession,
			),
		)

		assertTrue("the reference finalized nothing at all", played.isNotEmpty())
		assertNotEquals(
			"the reference reports the same time paused as playing",
			idle.firstOrNull()?.playedMs ?: -1L,
			played.first().playedMs,
		)
	}
}
