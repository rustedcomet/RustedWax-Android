package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling active picture-in-picture inference apart from a picture-in-picture
 * pause, in the value the Now card reads.
 *
 * A Short playing in picture-in-picture publishes no position at all, so the
 * source's own transport cannot say "playing" and `isPlaying` is false for it —
 * exactly as it is for a Short the viewer paused. Both also freeze the seekbar
 * and both set the missing-proof freeze, so before #12 the two were one value
 * and the card called a Short that was accruing a second per second `Paused`.
 *
 * What separates them here is the live authorization behind the current tick,
 * never how much inferred time has piled up: a pause keeps every millisecond it
 * earned, so anything read off a total would call a pause playback for as long
 * as it lasted. These tests pin that distinction at both ends — the tracker's
 * own state, and every exit that has to clear it.
 *
 * The credit itself is not retested here; that is
 * [ShortsPipPauseLifecycleTest]'s, and it is deliberately untouched.
 */
class ShortsPipInferredPresentationTest {

	// ---- 1 and 2. the two states that used to be one -------------------------

	@Test
	fun `a Short accruing in picture-in-picture is not presented as paused`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 120, at = 0))
		tracker.observe(organic(position = 20, total = 120, at = 20_000))

		creditInPip(tracker, from = 20_000, seconds = 5)

		val active = tracker.snapshot()!!
		assertFalse(
			"no seekbar, so the transport still cannot claim playing",
			active.isPlaying,
		)
		assertTrue(
			"but inference is authorized right now, which is not a pause",
			active.pipInferredPlaying,
		)
	}

	@Test
	fun `a Short paused in picture-in-picture is presented as paused`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 120, at = 0))
		tracker.observe(organic(position = 20, total = 120, at = 20_000))
		var now = creditInPip(tracker, from = 20_000, seconds = 5)

		repeat(10) {
			now += 1_000
			pausedInPip(tracker, now)
		}

		val paused = tracker.snapshot()!!
		assertFalse(paused.isPlaying)
		assertFalse(
			"the authorization was withdrawn, so this reads as the pause it is",
			paused.pipInferredPlaying,
		)
		assertTrue(
			"even though the time earned before the pause is still carried",
			paused.inferredPlayedMs > 0,
		)
	}

	// ---- 3 and 4. the whole cycle, and what resume may not invent -------------

	@Test
	fun `the flag follows fullscreen, picture-in-picture, pause and resume`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 180, at = 0))
		tracker.observe(organic(position = 20, total = 180, at = 20_000))
		assertFalse(
			"a readable seekbar infers nothing",
			tracker.snapshot()!!.pipInferredPlaying,
		)

		var now = creditInPip(tracker, from = 20_000, seconds = 5)
		assertTrue("entering PiP", tracker.snapshot()!!.pipInferredPlaying)

		repeat(8) {
			now += 1_000
			pausedInPip(tracker, now)
		}
		assertFalse("pausing there", tracker.snapshot()!!.pipInferredPlaying)

		now = creditInPip(tracker, from = now, seconds = 5)
		assertTrue("resuming there", tracker.snapshot()!!.pipInferredPlaying)

		// The seekbar coming back is the handback to direct proof.
		tracker.observe(organic(position = 40, total = 180, at = now + 1_000))
		val fullscreen = tracker.snapshot()!!
		assertTrue("direct proof is back", fullscreen.isPlaying)
		assertFalse(
			"and nothing is being inferred any more",
			fullscreen.pipInferredPlaying,
		)
	}

	@Test
	fun `resuming keeps one listen and back-fills none of the pause`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 180, at = 0))
		tracker.observe(organic(position = 20, total = 180, at = 20_000))
		var now = creditInPip(tracker, from = 20_000, seconds = 5)
		val before = tracker.snapshot()!!

		repeat(20) {
			now += 1_000
			pausedInPip(tracker, now)
		}
		val held = tracker.snapshot()!!
		assertEquals(
			"20s of pause is worth nothing",
			before.inferredPlayedMs,
			held.inferredPlayedMs,
		)

		now = creditInPip(tracker, from = now, seconds = 5)
		val after = tracker.snapshot()!!

		assertEquals(
			"the same listen throughout",
			before.trackInstanceToken,
			after.trackInstanceToken,
		)
		assertEquals(
			"only the playing seconds after the resume — the first tick re-takes " +
				"the anchor and is worth nothing, which is what stops the pause " +
				"itself being back-filled",
			4_000,
			after.inferredPlayedMs - before.inferredPlayedMs,
		)
		assertTrue(after.pipInferredPlaying)
	}

	// ---- 5. every exit clears it ---------------------------------------------

	@Test
	fun `a listen banked at its cap is not presented as still inferring`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 10, at = 0))
		tracker.observe(organic(position = 2, total = 10, at = 2_000))

		// Well past the 10s cap, so the inference exhausts and the listen banks.
		var now = 2_000L
		val banked = buildList {
			repeat(20) {
				now += 1_000
				addAll(
					tracker.proofMissing(
						now,
						"one seekbar container, no readable time",
						progressSurfaceLost = true,
						inferredPlaying = true,
					).finalized,
				)
			}
		}

		assertTrue("the cap has to produce a finalize", banked.isNotEmpty())
		banked.forEach {
			assertFalse(
				"a finalized listen is not inferring anything",
				it.pipInferredPlaying,
			)
			assertFalse(it.isPlaying)
		}
	}

	@Test
	fun `the window going away finalizes without claiming inference`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 120, at = 0))
		tracker.observe(organic(position = 20, total = 120, at = 20_000))
		var now = creditInPip(tracker, from = 20_000, seconds = 5)

		// No window, no audio, no PiP signature: the ordinary grace runs.
		val ended = buildList {
			repeat(10) {
				now += 1_000
				addAll(tracker.proofMissing(now, "no root while the app is in front").finalized)
			}
		}

		assertTrue("the grace still finalizes exactly as it did", ended.isNotEmpty())
		ended.forEach { assertFalse(it.pipInferredPlaying) }
	}

	@Test
	fun `a discarded Short leaves nothing presented`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 120, at = 0))
		tracker.observe(organic(position = 20, total = 120, at = 20_000))
		creditInPip(tracker, from = 20_000, seconds = 5)

		tracker.discard("Native YouTube is off")

		assertFalse(tracker.hasActive)
	}

	// ---- the anchor rule, which is why this is not a flicker -----------------

	@Test
	fun `a refusal carrying no playback evidence does not flip the presentation`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 120, at = 0))
		tracker.observe(organic(position = 20, total = 120, at = 20_000))
		var now = creditInPip(tracker, from = 20_000, seconds = 5)

		// The once-a-second freshness watchdog fires between two real PiP polls.
		// It says nothing about playback either way, so it must leave this alone
		// exactly as it leaves the inference anchor alone — otherwise the card
		// alternates between playing and paused every second.
		now += 1_000
		tracker.proofMissing(now, "capture older than the freshness window")

		assertTrue(
			"a refusal that carries no evidence may not manufacture a pause",
			tracker.snapshot()!!.pipInferredPlaying,
		)
	}

	// ---- 7. nothing else acquired a new presentation state -------------------

	@Test
	fun `a source that never infers reports nothing`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 60, at = 0))
		tracker.observe(organic(position = 5, total = 60, at = 5_000))

		val active = tracker.snapshot()!!
		assertTrue(active.isPlaying)
		assertFalse(active.pipInferredPlaying)
	}

	// ---- helpers -------------------------------------------------------------

	/** One second per tick of the exact picture-in-picture playing signature. */
	private fun creditInPip(
		tracker: ForegroundShortTracker,
		from: Long,
		seconds: Int,
	): Long {
		var now = from
		repeat(seconds) {
			now += 1_000
			tracker.proofMissing(
				now,
				"RustedWax is foreground; native YouTube root not requested",
				progressSurfaceLost = true,
				inferredPlaying = true,
			)
		}
		return now
	}

	/** One tick of "the window is still there and the audio stopped". */
	private fun pausedInPip(
		tracker: ForegroundShortTracker,
		nowMillis: Long,
	): ForegroundShortTracker.Update = tracker.proofMissing(
		nowMillis,
		"RustedWax is foreground; native YouTube root not requested",
		progressSurfaceLost = false,
		inferredPlaying = false,
		pipWindowPresent = true,
	)

	private fun organic(
		title: String? = "Organic",
		handle: String = "@creator",
		position: Long,
		total: Long = 60,
		at: Long,
		epoch: Long = 3,
	) = ForegroundShortTracker.OrganicObservation(
		title,
		handle,
		position,
		total,
		at,
		epoch,
	)
}
