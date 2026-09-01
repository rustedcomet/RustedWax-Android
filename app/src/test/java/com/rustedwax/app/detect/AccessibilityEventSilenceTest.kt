package com.rustedwax.app.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §5.1 outage detector.
 *
 * These pin the two ways instrumentation like this fails: staying quiet through
 * the event it exists to catch, and crying wolf often enough that the one line
 * that matters gets ignored. The first field day produced 111 reports in one
 * day and almost all of them were the second failure, so most of what is pinned
 * here is what must **not** be reported.
 */
class AccessibilityEventSilenceTest {

	private val threshold = AccessibilityEventSilence.SILENCE_THRESHOLD_MS
	private val repeat = AccessibilityEventSilence.REPEAT_INTERVAL_MS

	@Test
	fun `a healthy event stream is never reported`() {
		val silence = AccessibilityEventSilence()
		silence.started(0)
		var now = 0L
		repeat(20) {
			now += 5_000
			silence.eventReceived(now)
			assertNull(
				silence.observed(
					now,
					ObservedSurface.YOUTUBE_SHORTS_PLAYER,
					screenInteractive = true,
					unmeasuredPlayback = { true },
				),
			)
		}
	}

	/**
	 * Measured 2026-08-06 15:08:39: the detector reported a 45-second outage
	 * while, inside that window, the service measured the seekbar thirteen times
	 * and credited forty seconds of playback. A latched Short is read by the
	 * service's own 1s poll and YouTube emits no callbacks at all while it is —
	 * so events are not liveness, and successful captures are.
	 */
	@Test
	fun `a measuring observer is alive even with no events at all`() {
		val silence = AccessibilityEventSilence()
		silence.started(0)
		silence.eventReceived(0)
		var now = 0L
		repeat(60) {
			now += 1_000
			silence.captureSucceeded(now)
			assertNull(
				"a capture that parsed a player is the observer proving it can see",
				silence.observed(
					now,
					ObservedSurface.YOUTUBE_SHORTS_PLAYER,
					screenInteractive = true,
					unmeasuredPlayback = { true },
				),
			)
		}
	}

	@Test
	fun `something playing that cannot be seen names itself and its duration`() {
		val silence = AccessibilityEventSilence()
		silence.started(0)
		silence.eventReceived(1_000)

		assertNull(
			"a gap under the threshold is not an outage",
			silence.observed(
				1_000 + threshold - 1,
				ObservedSurface.YOUTUBE_NO_PLAYER,
				screenInteractive = true,
				unmeasuredPlayback = { true },
			),
		)

		val line = silence.observed(
			88_000,
			ObservedSurface.YOUTUBE_NO_PLAYER,
			screenInteractive = true,
			unmeasuredPlayback = { true },
		)
		assertNotNull(line)
		assertTrue(line!!.contains("87s"))
		assertTrue(line.contains("YouTube is playing audio"))
		assertTrue(line.contains("unobserved"))
	}

	/**
	 * Reproduced on demand 2026-08-06: press Back out of the Shorts player and
	 * YouTube keeps `reel_time_bar` in its hierarchy while the player root stops
	 * being visible, so every capture reads `found 0; captured 2 nodes`. 36% of
	 * the day's captures were that shape — the app open and idle, nothing lost.
	 */
	@Test
	fun `an idle YouTube with nothing playing is never an outage`() {
		val silence = AccessibilityEventSilence()
		silence.started(0)
		silence.eventReceived(0)
		assertNull(
			silence.observed(
				600_000,
				ObservedSurface.YOUTUBE_NO_PLAYER,
				screenInteractive = true,
				unmeasuredPlayback = { false },
			),
		)
		assertNull(
			"a paused Short drops out of the started-audio list too",
			silence.observed(
				600_000,
				ObservedSurface.YOUTUBE_SHORTS_PLAYER,
				screenInteractive = true,
				unmeasuredPlayback = { false },
			),
		)
	}

	@Test
	fun `a service that can see no window at all is reportable without Usage Access`() {
		// Unknown is not "nothing is playing". A service that cannot see any
		// window with the screen on is anomalous on its own evidence, so it is
		// the one state that still reports when the audio question cannot be
		// answered — and it says so rather than implying a listen was lost.
		val silence = AccessibilityEventSilence()
		silence.started(0)
		silence.eventReceived(0)
		val line = silence.observed(
			threshold,
			ObservedSurface.NO_ROOT,
			screenInteractive = true,
			unmeasuredPlayback = { null },
		)
		assertNotNull(line)
		assertTrue(line!!.contains("the service cannot see"))
		assertTrue(line.contains("unknown without Usage Access"))

		// Every other surface stays quiet on unknown, because without the audio
		// evidence there is nothing to distinguish it from an idle app.
		val quiet = AccessibilityEventSilence()
		quiet.started(0)
		quiet.eventReceived(0)
		assertNull(
			quiet.observed(
				threshold,
				ObservedSurface.YOUTUBE_NO_PLAYER,
				screenInteractive = true,
				unmeasuredPlayback = { null },
			),
		)
	}

	@Test
	fun `explained silence is never reported`() {
		// Another app foreground, or a dark screen, fully accounts for the quiet.
		// Reporting either would bury the case that matters.
		val other = AccessibilityEventSilence()
		other.started(0)
		other.eventReceived(0)
		assertNull(
			other.observed(
				600_000,
				ObservedSurface.OTHER_APP,
				screenInteractive = true,
				unmeasuredPlayback = { true },
			),
		)

		val dark = AccessibilityEventSilence()
		dark.started(0)
		dark.eventReceived(0)
		assertNull(
			dark.observed(
				600_000,
				ObservedSurface.NO_ROOT,
				screenInteractive = false,
				unmeasuredPlayback = { true },
			),
		)
	}

	@Test
	fun `an outage that ends reports the total it lasted`() {
		// A start with no end leaves the duration to be guessed from log gaps,
		// which is exactly the position §5.1 was written from.
		val silence = AccessibilityEventSilence()
		silence.started(0)
		silence.eventReceived(1_000)
		assertNotNull(
			silence.observed(
				50_000,
				ObservedSurface.YOUTUBE_NO_PLAYER,
				screenInteractive = true,
				unmeasuredPlayback = { true },
			),
		)

		val recovered = silence.captureSucceeded(88_000)
		assertNotNull(recovered)
		assertTrue(recovered!!.contains("recovered"))
		assertTrue(recovered.contains("87s"))
	}

	@Test
	fun `recovery is silent when no outage was ever reported`() {
		val silence = AccessibilityEventSilence()
		silence.started(0)
		assertNull(silence.eventReceived(10_000))
		assertNull(silence.captureSucceeded(20_000))
	}

	@Test
	fun `a long outage leaves a trail rather than a flood`() {
		val silence = AccessibilityEventSilence()
		silence.started(0)
		silence.eventReceived(0)
		var reports = 0
		// Ten minutes of continuous silence, probed every second.
		for (now in threshold..600_000 step 1_000) {
			val line = silence.observed(
				now,
				ObservedSurface.NO_ROOT,
				screenInteractive = true,
				unmeasuredPlayback = { true },
			)
			if (line != null) reports++
		}
		// One at the threshold, then one per repeat interval — not 555 lines.
		assertTrue("expected a handful of reports, got $reports", reports in 2..12)
	}

	@Test
	fun `probing is rate limited and stops once observation returns`() {
		val silence = AccessibilityEventSilence()
		silence.started(0)
		silence.eventReceived(0)

		assertFalse("healthy stream needs no probe", silence.probeDue(threshold - 1))
		assertTrue(silence.probeDue(threshold))
		assertFalse("asking again immediately is waste", silence.probeDue(threshold + 1))
		assertTrue(silence.probeDue(threshold + AccessibilityEventSilence.PROBE_INTERVAL_MS))

		silence.captureSucceeded(threshold + 30_000)
		assertFalse(
			"an observer that is seeing needs no probe",
			silence.probeDue(threshold + 30_000 + AccessibilityEventSilence.PROBE_INTERVAL_MS),
		)
	}

	@Test
	fun `a freshly connected service is not an outage`() {
		// The window before the first observation is legitimate, not a fault.
		val silence = AccessibilityEventSilence()
		silence.started(0)
		assertNull(
			silence.observed(
				threshold - 1,
				ObservedSurface.YOUTUBE_NO_PLAYER,
				screenInteractive = true,
				unmeasuredPlayback = { true },
			),
		)
		// Once past the threshold with audio playing and still nothing seen, it
		// is worth saying: a service that connects and never observes anything is
		// the 💯💯💯🔥😎 case, where the Short never appeared in the log at all.
		assertNotNull(
			silence.observed(
				threshold + repeat,
				ObservedSurface.YOUTUBE_NO_PLAYER,
				screenInteractive = true,
				unmeasuredPlayback = { true },
			),
		)
	}
}
