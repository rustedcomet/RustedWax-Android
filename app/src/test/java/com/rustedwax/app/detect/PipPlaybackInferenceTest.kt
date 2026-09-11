package com.rustedwax.app.detect

import com.rustedwax.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caps are the whole safety story here.
 *
 * Credit is being handed out on evidence that cannot see the player, so what
 * matters is not that it counts, but that it cannot count more than the Short
 * could possibly contain.
 *
 * Every case threads the returned value rather than re-reading one object,
 * because that is now the only way credit advances — see the purity argument on
 * [PipPlaybackInference] itself.
 */
class PipPlaybackInferenceTest {

	private fun inference(durationSec: Long, measuredSec: Long) =
		PipPlaybackInference(durationMs = durationSec * 1000, measuredMs = measuredSec * 1000)

	/** Threads a run of observations, returning the final state. */
	private fun PipPlaybackInference.observing(
		vararg observations: Pair<Long, Boolean>,
		rate: Double = 1.0,
	): PipPlaybackInference {
		var state = this
		for ((now, playing) in observations) {
			state = state.observe(now, playing, rate).next
		}
		return state
	}

	@Test
	fun `nothing is credited for the first observation`() {
		// There is no interval yet. Crediting here would invent time from the
		// moment the surface went away, which nobody measured.
		val pip = inference(durationSec = 60, measuredSec = 10)
		val step = pip.observe(1_000, playing = true)
		assertEquals(0, step.creditedMs)
		assertEquals(0, step.next.credited)
	}

	@Test
	fun `elapsed time between two playing observations is credited`() {
		val anchored = inference(durationSec = 60, measuredSec = 10)
			.observe(1_000, playing = true).next
		val second = anchored.observe(2_000, playing = true)
		assertEquals(1_000, second.creditedMs)
		val third = second.next.observe(3_000, playing = true)
		assertEquals(1_000, third.creditedMs)
		assertEquals(2_000, third.next.credited)
	}

	@Test
	fun `a pause credits nothing and cannot be back-filled on resume`() {
		// The audio list drops paused players, so this is the ordinary "user
		// paused the PiP window" path. The paused stretch must not reappear as
		// watch time the moment playback resumes.
		val played = inference(durationSec = 60, measuredSec = 0)
			.observing(0L to true, 1_000L to true)
		assertEquals(1_000, played.credited)

		val paused = played.observe(2_000, playing = false)
		assertEquals(0, paused.creditedMs)
		val stillPaused = paused.next.observe(30_000, playing = false)
		assertEquals(0, stillPaused.creditedMs)
		// First observation after the pause re-anchors and credits nothing.
		val resumed = stillPaused.next.observe(31_000, playing = true)
		assertEquals(0, resumed.creditedMs)
		assertEquals(1_000, resumed.next.credited)
		val running = resumed.next.observe(32_000, playing = true)
		assertEquals(1_000, running.creditedMs)
		assertEquals(2_000, running.next.credited)
	}

	@Test
	fun `a long stall credits at most one step`() {
		// Doze, a frozen process or a stalled binder can put an arbitrary gap
		// between two polls. The first poll after the gap must not credit it all.
		val anchored = inference(durationSec = 600, measuredSec = 0)
			.observe(0, playing = true).next
		assertEquals(
			PipPlaybackInference.MAX_STEP_MS,
			anchored.observe(10 * 60 * 1000, playing = true).creditedMs,
		)
	}

	@Test
	fun `measured plus inferred can never exceed the Short's duration`() {
		// Shorts auto-loop. Without this cap a Short left playing in PiP would
		// accumulate credit forever and clear any threshold, which is exactly the
		// over-counting the whole design exists to prevent.
		var pip = inference(durationSec = 30, measuredSec = 12)
		var now = 0L
		repeat(1_000) {
			now += 1_000
			pip = pip.observe(now, playing = true).next
		}
		assertEquals(18_000, pip.credited)
		assertTrue(12_000 + pip.credited <= 30_000)
	}

	@Test
	fun `a Short already measured to the end earns no inferred time at all`() {
		val pip = inference(durationSec = 30, measuredSec = 30)
			.observing(0L to true, 1_000L to true)
		assertEquals(0, pip.credited)
	}

	@Test
	fun `out of order observations credit nothing`() {
		val pip = inference(durationSec = 60, measuredSec = 0)
			.observe(5_000, playing = true).next
		assertEquals(0, pip.observe(4_000, playing = true).creditedMs)
	}

	@Test
	fun `the observed speed chip scales what wall clock is worth`() {
		// The first observation only drops the anchor; the second is the first
		// interval that can be credited, and one second of it is worth two.
		val anchored = PipPlaybackInference(durationMs = 120_000, measuredMs = 0)
			.observe(0, playing = true, rate = 2.0).next
		val first = anchored.observe(1_000, playing = true, rate = 2.0)
		assertEquals(2_000, first.creditedMs)
		val second = first.next.observe(2_000, playing = true, rate = 2.0)
		assertEquals(2_000, second.creditedMs)
		assertEquals(4_000, second.next.credited)
	}

	@Test
	fun `no chip still means one times, and the duration cap still holds`() {
		val plain = PipPlaybackInference(durationMs = 120_000, measuredMs = 0)
			.observe(0, playing = true).next
		assertEquals(1_000, plain.observe(1_000, playing = true).creditedMs)

		// Rate applies after the wall-clock step bound, and never past the length.
		val capped = PipPlaybackInference(durationMs = 3_000, measuredMs = 0)
			.observe(0, playing = true, rate = 2.0).next
		val step = capped.observe(10_000, playing = true, rate = 2.0)
		assertEquals(3_000, step.creditedMs)
		assertTrue(step.next.exhausted)
	}

	/**
	 * The property `PlaybackReducer` depends on.
	 *
	 * When this was a mutable accumulator, a reducer that held one inside its
	 * state advanced that state by side effect — so replaying an event changed
	 * the answer. Observing must leave the receiver exactly as it was.
	 */
	@Test
	fun `observing never changes the value it was called on`() {
		val anchored = inference(durationSec = 60, measuredSec = 0)
			.observe(0, playing = true).next
		val first = anchored.observe(1_000, playing = true)
		assertEquals(0, anchored.credited)
		assertEquals(1_000, first.next.credited)
		assertNotSame(anchored, first.next)

		// And the same call twice on the same receiver gives the same answer.
		val again = anchored.observe(1_000, playing = true)
		assertEquals(first.creditedMs, again.creditedMs)
		assertEquals(first.next, again.next)
	}
	/**
	 * Why picture-in-picture may credit no additional time after a completed loop.
	 *
	 * Shorts auto-loop and [ForegroundShortTracker] keeps crediting each loop, so
	 * `playedSeconds` grows without bound. `proofMissing` builds this inference
	 * with `measuredMs = playedSeconds + inferredMillis`, so a Short that has been
	 * round once arrives with `measuredMs >= durationMs`, no room, and credits
	 * nothing. It is [exhausted] from its first observation.
	 *
	 * That is the intended cap, not a loss: the tracker banks a full listen the
	 * moment measured reaches the Short's length, so anything with
	 * `measuredMs >= durationMs` has already been scored and scrobbled. Both arms
	 * are represented by the synthetic continuity sequence below.
	 */
	@Test
	fun `a Short that already looped past its length arrives with nothing left to credit`() {
		val looped = PipPlaybackInference(durationMs = 14_000, measuredMs = 30_000)
		assertTrue("nothing is owed to a listen already banked", looped.exhausted)
		val anchored = looped.observe(1_000, playing = true)
		val step = anchored.next.observe(2_000, playing = true)
		assertEquals(0, step.creditedMs)
	}

	/** The other arm: entered before the Short ever looped, PiP carries the listen. */
	@Test
	fun `a Short sent to PiP before it loops can be credited to completion`() {
		var running = PipPlaybackInference(durationMs = 26_000, measuredMs = 0)
		var credited = 0L
		var at = 0L
		repeat(40) {
			at += 1_000
			val step = running.observe(at, playing = true)
			running = step.next
			credited += step.creditedMs
		}
		assertEquals("credit stops exactly at the Short's own length", 26_000, credited)
		assertTrue("and then says so, so the listen can end", running.exhausted)
	}

}
