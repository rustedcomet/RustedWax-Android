package com.rustedwax.app.detect

import com.rustedwax.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pausing a Short inside picture-in-picture, and resuming it there.
 *
 * The lifecycle this covers was a deterministic loss: the pinned window was
 * still on screen, only its audio had stopped, and the tracker read the silence
 * as the player going away — so the ordinary missing-proof grace finalized a
 * Short the viewer had merely paused. Resuming could not reacquire it, because
 * inference extends a live Short and cannot name a new one, so one continuous
 * viewing became a premature `Not logged` stub plus whatever fullscreen
 * expansion started afterwards.
 *
 * What holds the listen open here is the window itself, not a longer timeout:
 * every one of these tests ends the hold by taking the window away, never by
 * waiting.
 */
class ShortsPipPauseLifecycleTest {

	/** Long enough that the old 3s grace would have expired several times over. */
	private val wellPastGrace = ForegroundShortTracker.MISSING_PROOF_GRACE_MS * 5

	// ---- the evidence itself -------------------------------------------------

	@Test
	fun `a stopped audio track with its window still up is a pause, not a departure`() {
		val paused = ShortsPipEvidence(
			inferenceEnabled = true,
			mediaAudioStarted = false,
			visiblePinnedWindow = true,
			pinnedWindowPresent = true,
		)

		assertFalse("silence can never be credited as playback", paused.playing)
		assertTrue("but the window still standing is a paused Short", paused.pausedButPresent)
	}

	@Test
	fun `a window that went away is a departure whatever else is true`() {
		val gone = ShortsPipEvidence(
			inferenceEnabled = true,
			mediaAudioStarted = false,
			visiblePinnedWindow = false,
			pinnedWindowPresent = false,
		)

		assertFalse(gone.playing)
		assertFalse("nothing to hold open once the window is gone", gone.pausedButPresent)
	}

	@Test
	fun `playing in picture-in-picture is never also reported as paused`() {
		val playing = ShortsPipEvidence(
			inferenceEnabled = true,
			mediaAudioStarted = true,
			visiblePinnedWindow = true,
			pinnedWindowPresent = true,
		)

		assertTrue(playing.playing)
		assertFalse("audio is running, so this is not the paused case", playing.pausedButPresent)
	}

	@Test
	fun `with picture-in-picture time switched off nothing is held`() {
		val disabled = ShortsPipEvidence(
			inferenceEnabled = false,
			mediaAudioStarted = false,
			visiblePinnedWindow = true,
			pinnedWindowPresent = true,
		)

		assertFalse(disabled.playing)
		assertFalse("the owner's switch governs the hold as well", disabled.pausedButPresent)
	}

	@Test
	fun `a vanished surface carries the paused-window fact to the reducer`() {
		val adapter = NativeShortsAdapter()

		val decision = adapter.readUnavailableSurface(
			ShortsSurfaceUnavailableFacts(
				kind = ShortsSurfaceUnavailableKind.SURFACE_GONE,
				reason = "no root while the app is in front",
				observedAtMillis = 1_000,
				displayOff = false,
				pipEvidence = ShortsPipEvidence(
					inferenceEnabled = true,
					mediaAudioStarted = false,
					visiblePinnedWindow = true,
					pinnedWindowPresent = true,
				),
				foregroundSurfaceOwned = true,
			),
		)

		val absent = decision.readings.filterIsInstance<ShortsSurfaceReading.Absent>().single()
		assertTrue("the paused window has to reach the tracker", absent.pipWindowPresent)
		assertFalse("and it is still not playing", absent.inferredPlaying)
	}

	// ---- 1. picture-in-picture playback is untouched --------------------------

	@Test
	fun `a Short playing in picture-in-picture still accrues inferred time`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 60, at = 0))
		tracker.observe(organic(position = 20, total = 60, at = 20_000))

		var now = 20_000L
		repeat(20) {
			now += 1_000
			val update = tracker.proofMissing(
				now,
				"one seekbar container, no readable time",
				progressSurfaceLost = true,
				inferredPlaying = true,
			)
			assertTrue("playing must not finalize", update.finalized.isEmpty())
		}

		val active = tracker.snapshot()!!
		assertEquals("unchanged by the pause work", 19_000, active.inferredPlayedMs)
		assertEquals(39_000, active.playedMs)
	}

	// ---- 2 and 3. a pause holds the listen and earns nothing ------------------

	@Test
	fun `a pause in picture-in-picture does not finalize however long it lasts`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 120, at = 0))
		tracker.observe(organic(position = 20, total = 120, at = 20_000))
		val playing = creditInPip(tracker, from = 20_000, seconds = 5)

		var now = playing
		repeat((wellPastGrace / 1_000).toInt()) {
			now += 1_000
			val update = pausedInPip(tracker, now)
			assertTrue(
				"the window is still on screen, so nothing may finalize",
				update.finalized.isEmpty(),
			)
			assertNotNull("and the listen stays on Now", update.active)
		}

		assertTrue("the same listen is still live", tracker.hasActive)
	}

	@Test
	fun `a paused interval earns no time at all`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 120, at = 0))
		tracker.observe(organic(position = 20, total = 120, at = 20_000))
		val playing = creditInPip(tracker, from = 20_000, seconds = 5)
		val bankedAtPause = tracker.snapshot()!!

		var now = playing
		repeat(30) {
			now += 1_000
			pausedInPip(tracker, now)
		}

		val afterPause = tracker.snapshot()!!
		assertEquals(
			"a pause is not watch time",
			bankedAtPause.playedMs,
			afterPause.playedMs,
		)
		assertEquals(bankedAtPause.inferredPlayedMs, afterPause.inferredPlayedMs)
	}

	// ---- 4. resuming there continues the same listen --------------------------

	@Test
	fun `resuming in picture-in-picture continues the same logical listen`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 120, at = 0))
		tracker.observe(organic(position = 20, total = 120, at = 20_000))
		val playing = creditInPip(tracker, from = 20_000, seconds = 5)
		val before = tracker.snapshot()!!

		var now = playing
		repeat(20) {
			now += 1_000
			pausedInPip(tracker, now)
		}
		val resumedFrom = now
		now = creditInPip(tracker, from = now, seconds = 5)

		val after = tracker.snapshot()!!
		assertEquals(
			"the same listen, not a replacement",
			before.trackInstanceToken,
			after.trackInstanceToken,
		)
		assertEquals(
			"and the same start second",
			before.trackStartedAtEpochSec,
			after.trackStartedAtEpochSec,
		)
		assertTrue(
			"crediting picks up where it left off — got ${after.inferredPlayedMs}ms",
			after.inferredPlayedMs > before.inferredPlayedMs,
		)
		assertEquals(
			"and only the playing seconds after the resume were added — the first " +
				"tick re-takes the anchor and is worth nothing, which is exactly what " +
				"stops the pause itself being back-filled",
			4_000,
			after.inferredPlayedMs - before.inferredPlayedMs,
		)
		assertTrue(resumedFrom < now)
	}

	@Test
	fun `many pause and resume cycles stay one listen`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 180, at = 0))
		tracker.observe(organic(position = 10, total = 180, at = 10_000))
		val token = tracker.snapshot()!!.trackInstanceToken

		var now = 10_000L
		repeat(3) {
			now = creditInPip(tracker, from = now, seconds = 4)
			repeat(6) {
				now += 1_000
				pausedInPip(tracker, now)
			}
		}

		val active = tracker.snapshot()!!
		assertEquals("one listen across every cycle", token, active.trackInstanceToken)
		assertEquals(
			"only the three playing stretches were credited, each one an anchor tick " +
				"plus three paid seconds",
			9_000,
			active.inferredPlayedMs,
		)
	}

	// ---- 5. losing the window finalizes exactly as before ---------------------

	@Test
	fun `the window going away still finalizes the paused Short`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 120, at = 0))
		tracker.observe(organic(position = 20, total = 120, at = 20_000))
		var now = creditInPip(tracker, from = 20_000, seconds = 5)

		repeat(10) {
			now += 1_000
			pausedInPip(tracker, now)
		}
		assertTrue("held while the window stood", tracker.hasActive)

		val finalized = mutableListOf<SessionSnapshot>()
		repeat(5) {
			now += 1_000
			// The window is gone now: no pipWindowPresent, nothing playing.
			finalized += tracker.proofMissing(now, "picture-in-picture dismissed").finalized
		}

		assertEquals("one ending, as always", 1, finalized.size)
		assertFalse("and nothing is left hanging", tracker.hasActive)
		assertEquals(
			"the pause added nothing to what was scored",
			24_000,
			finalized.single().playedMs,
		)
	}

	// ---- 6. handing back to a readable seekbar --------------------------------

	@Test
	fun `expanding back to fullscreen keeps one listen and does not reset it`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 120, at = 0))
		tracker.observe(organic(position = 20, total = 120, at = 20_000))
		var now = creditInPip(tracker, from = 20_000, seconds = 5)
		val before = tracker.snapshot()!!

		repeat(8) {
			now += 1_000
			pausedInPip(tracker, now)
		}

		now += 1_000
		val back = tracker.observe(organic(position = 25, total = 120, at = now))

		assertTrue("expanding is not an ending", back.finalized.isEmpty())
		val active = back.active!!
		assertEquals(
			"the same listen came back",
			before.trackInstanceToken,
			active.trackInstanceToken,
		)
		assertTrue(
			"and it kept what it had earned — got ${active.playedMs}ms",
			active.playedMs >= before.playedMs,
		)
		assertFalse("the surface is readable again", active.foregroundProgressLost)
	}

	// ---- 7. a different Short inherits nothing --------------------------------

	@Test
	fun `a different Short after a held pause starts from zero`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(title = "First", handle = "@one", position = 0, total = 120, at = 0))
		tracker.observe(
			organic(title = "First", handle = "@one", position = 20, total = 120, at = 20_000),
		)
		var now = creditInPip(tracker, from = 20_000, seconds = 5)
		repeat(8) {
			now += 1_000
			pausedInPip(tracker, now)
		}

		now += 1_000
		val next = tracker.observe(
			organic(title = "Second", handle = "@two", position = 0, total = 45, at = now),
		)

		val active = next.active!!
		assertEquals("Second", active.title)
		assertEquals("@two", active.artist)
		assertEquals("nothing carried over", 0, active.playedMs)
		assertEquals(0, active.inferredPlayedMs)
		assertEquals(
			"the first Short ended on its own measurements",
			1,
			next.finalized.size,
		)
		assertEquals("First", next.finalized.single().title)
	}

	// ---- 8. the surrounding rules still apply --------------------------------

	@Test
	fun `a Short already banked at its full length is not held open`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 20, at = 0))
		tracker.observe(organic(position = 1, total = 20, at = 1_000))

		var now = 1_000L
		var banked: SessionSnapshot? = null
		repeat(40) {
			now += 1_000
			val update = tracker.proofMissing(
				now,
				"one seekbar container, no readable time",
				progressSurfaceLost = true,
				inferredPlaying = true,
			)
			update.finalized.firstOrNull()?.let { if (banked == null) banked = it }
		}
		assertNotNull("the cap still banks the listen", banked)

		// Paused afterwards, with the window still up: a scored viewing must not
		// be resurrected and held on screen forever.
		val finalized = mutableListOf<SessionSnapshot>()
		repeat(10) {
			now += 1_000
			finalized += pausedInPip(tracker, now).finalized
		}

		assertFalse("the banked listen still ends", tracker.hasActive)
		assertEquals("and is not scored twice", 0, finalized.size)
	}

	@Test
	fun `a Short that looped to its own full length is scored once and not held`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 30, at = 0))
		tracker.observe(organic(position = 29, total = 30, at = 29_000))
		val wrapped = tracker.observe(organic(position = 1, total = 30, at = 31_000))

		// Playing to the end and round again is a complete viewing: it is banked
		// there and then, and it says so.
		val scored = wrapped.finalized.single()
		assertTrue("the wrap was seen", scored.loopDetected)
		assertEquals("29s to the end plus the 2s it played round again", 31_000, scored.playedMs)

		// Pausing that in picture-in-picture afterwards must not resurrect it,
		// hold it on Now forever, or score it a second time.
		var now = 31_000L
		val finalized = mutableListOf<SessionSnapshot>()
		repeat(8) {
			now += 1_000
			finalized += pausedInPip(tracker, now).finalized
		}

		assertEquals("no second scoring of one viewing", 0, finalized.size)
		assertFalse("and nothing left hanging", tracker.hasActive)
		assertNull(tracker.snapshot())
	}

	@Test
	fun `a refusal carrying no window evidence still runs the ordinary grace`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 120, at = 0))
		tracker.observe(organic(position = 20, total = 120, at = 20_000))
		creditInPip(tracker, from = 20_000, seconds = 5)

		// A swipe, a stale capture or the freshness watchdog says nothing about a
		// window. Those must finalize on the same 3s as they always did.
		var now = 25_000L
		val finalized = mutableListOf<SessionSnapshot>()
		repeat(5) {
			now += 1_000
			finalized += tracker.proofMissing(now, "accessibility capture exceeded the bound").finalized
		}

		assertEquals(1, finalized.size)
		assertFalse(tracker.hasActive)
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
