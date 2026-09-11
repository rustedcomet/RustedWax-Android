package com.rustedwax.app.detect

import com.rustedwax.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundShortTrackerTest {

	@Test
	fun `neutral foreground inputs drive the production tracker`() {
		val tracker = ForegroundShortTracker()
		fun observed(positionMs: Long, nowMillis: Long) =
			PlaybackInput.ForegroundSurfaceObserved(
				identity = TrackIdentity("Neutral Short", "@owner", null, 20_000),
				positionMs = positionMs,
				durationMs = 20_000,
				nowMillis = nowMillis,
				sourceEpoch = 7,
				playing = true,
			)

		tracker.reduce(observed(positionMs = 0, nowMillis = 0))
		val advanced = tracker.reduce(observed(positionMs = 5_000, nowMillis = 5_000))

		assertEquals("Neutral Short", advanced.active!!.title)
		assertEquals("@owner", advanced.active!!.artist)
		assertEquals(5_000, advanced.active!!.playedMs)
	}

	@Test
	fun `a Short with no seekbar still starts and accrues inferred time`() {
		val tracker = ForegroundShortTracker()
		val first = tracker.observe(unmeasured(at = 0))
		assertTrue(tracker.hasActive)
		assertEquals(0, first.active!!.playedMs)
		// Nothing is measured, so the length is unknown and quoted as such.
		assertNull(first.active!!.durationMs)

		var update = first
		for (second in 1..10) {
			update = tracker.observe(unmeasured(at = second * 1_000L))
		}
		val played = update.active!!.playedMs
		assertTrue("expected wall-clock to accrue, got ${played}ms", played >= 9_000)
		assertEquals("every second of it is inferred", played, update.active!!.inferredPlayedMs)
	}

	@Test
	fun `a Short with no seekbar credits nothing while the evidence says paused`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(unmeasured(at = 0, playing = false))
		var update = tracker.observe(unmeasured(at = 5_000, playing = false))
		update = tracker.observe(unmeasured(at = 10_000, playing = false))
		assertEquals(0, update.active!!.playedMs)
	}

	/** The seekbar coming back mid-viewing must not restart or double-count it. */
	@Test
	fun `a returning seekbar continues the same Short`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(unmeasured(at = 0))
		val inferred = tracker.observe(unmeasured(at = 6_000)).active!!.playedMs
		assertTrue(inferred > 0)
		val measured = tracker.observe(organic(position = 6, at = 7_000, total = 60))
		assertTrue("the same Short continues", measured.finalized.isEmpty())
		assertEquals(60_000L, measured.active!!.durationMs)
	}

	@Test
	fun `a Short interrupted by a tab switch resumes what it earned`() {
		val tracker = ForegroundShortTracker()
		val original = tracker.observe(organic(position = 0, total = 32, at = 0)).active!!
		tracker.observe(organic(position = 12, total = 32, at = 12_000))

		// The player goes away; the grace expires and it finalizes at 12s.
		tracker.proofMissing(13_000, "player root gone")
		val ended = tracker.proofMissing(
			13_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"player root gone",
		)
		assertEquals(12_000, ended.finalized.single().playedMs)
		assertFalse(tracker.hasActive)

		// Back within the window, same Short: it continues rather than restarting.
		val back = tracker.observe(organic(position = 12, total = 32, at = 22_000))
		assertEquals(12_000, back.active!!.playedMs)
		assertEquals(original.trackInstanceToken, back.active!!.trackInstanceToken)
		assertEquals(original.trackStartedAtEpochSec, back.active!!.trackStartedAtEpochSec)
		assertEquals(20_000, tracker.observe(organic(position = 20, total = 32, at = 30_000)).active!!.playedMs)
	}

	@Test
	fun `seekbarless tab resume keeps one listen identity and cannot claim twice`() {
		val tracker = ForegroundShortTracker()
		val original = tracker.observe(
			unmeasured(title = "Best Girl", handle = "@lfgbae", at = 1_000),
		).active!!
		tracker.observe(
			unmeasured(title = "Best Girl", handle = "@lfgbae", at = 66_000),
		)
		tracker.proofMissing(67_000, "Home transition")
		val ended = tracker.proofMissing(
			67_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"Home transition",
		).finalized.single()

		val resumed = tracker.observe(
			unmeasured(title = "Best Girl", handle = "@lfgbae", at = 71_000),
		).active!!

		assertEquals(original.trackInstanceToken, ended.trackInstanceToken)
		assertEquals(ended.trackInstanceToken, resumed.trackInstanceToken)
		assertEquals(original.trackStartedAtEpochSec, ended.trackStartedAtEpochSec)
		assertEquals(ended.trackStartedAtEpochSec, resumed.trackStartedAtEpochSec)
		assertTrue(resumed.playedMs >= ended.playedMs)
	}

	@Test
	fun `a different Short does not inherit the interrupted one's seconds`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(title = "A", handle = "@a", position = 0, total = 32, at = 0))
		tracker.observe(organic(title = "A", handle = "@a", position = 20, total = 32, at = 20_000))
		tracker.proofMissing(21_000, "gone")
		tracker.proofMissing(21_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS, "gone")

		val other = tracker.observe(
			organic(title = "B", handle = "@b", position = 0, total = 32, at = 26_000),
		)
		assertEquals(0, other.active!!.playedMs)
	}

	@Test
	fun `coming back long after the interruption starts over`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 32, at = 0))
		tracker.observe(organic(position = 20, total = 32, at = 20_000))
		tracker.proofMissing(21_000, "gone")
		tracker.proofMissing(21_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS, "gone")

		val late = tracker.observe(
			organic(position = 0, total = 32, at = 25_000 + ForegroundShortTracker.RESUME_WINDOW_MS),
		)
		assertEquals(0, late.active!!.playedMs)
	}

	@Test
	fun `a Short resumed minutes later continues where its seekbar left off`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 107, at = 0))
		tracker.observe(organic(position = 52, total = 107, at = 52_000))
		tracker.proofMissing(53_000, "minimized")
		tracker.proofMissing(53_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS, "minimized")

		val back = tracker.observe(organic(position = 52, total = 107, at = 158_000))
		assertEquals(52_000, back.active!!.playedMs)
		assertEquals(
			60_000,
			tracker.observe(organic(position = 60, total = 107, at = 166_000)).active!!.playedMs,
		)
	}

	/** Watching it again from the top is a fresh count, however soon it happens. */
	@Test
	fun `a Short restarted from the beginning does not inherit its own seconds`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 107, at = 0))
		tracker.observe(organic(position = 52, total = 107, at = 52_000))
		tracker.proofMissing(53_000, "minimized")
		tracker.proofMissing(53_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS, "minimized")

		val replay = tracker.observe(organic(position = 0, total = 107, at = 158_000))
		assertEquals(0, replay.active!!.playedMs)
	}

	/** Nothing resumes forever, and a replay can never sit inside the band. */
	@Test
	fun `the Short resume window is bounded and its floor clears the tolerance`() {
		assertTrue(
			ForegroundShortTracker.RESUME_MIN_POSITION_SECONDS >
				ForegroundShortTracker.RESUME_POSITION_TOLERANCE_SECONDS,
		)
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 107, at = 0))
		tracker.observe(organic(position = 52, total = 107, at = 52_000))
		tracker.proofMissing(53_000, "minimized")
		tracker.proofMissing(53_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS, "minimized")

		// Measured from the finalize that remembered it, not from the first
		// missing-proof tick three seconds earlier.
		val rememberedAt = 53_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS
		val tooLate = tracker.observe(
			organic(
				position = 52,
				total = 107,
				at = rememberedAt + ForegroundShortTracker.RESUMED_WINDOW_MS + 1,
			),
		)
		assertEquals(0, tooLate.active!!.playedMs)
	}

	@Test
	fun `only sequential seekbar deltas earn progress`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, at = 0))
		assertEquals(4_000, tracker.observe(organic(position = 4, at = 4_000)).active!!.playedMs)
		assertEquals(4_000, tracker.observe(organic(position = 4, at = 14_000)).active!!.playedMs)
		assertEquals(4_000, tracker.observe(organic(position = 30, at = 15_000)).active!!.playedMs)
		assertEquals(4_000, tracker.observe(organic(position = 8, at = 16_000)).active!!.playedMs)
		assertEquals(6_000, tracker.observe(organic(position = 10, at = 18_000)).active!!.playedMs)
	}

	/**
	 * Reduced test-device field shape: the player, title, exact handle,
	 * duration and seekbar all remain visible, but the seekbar value is cached for
	 * more than a minute while audio and the YouTube window remain active.
	 */
	@Test
	fun `a proven Short with a stalled seekbar accrues paired wall clock without double count`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(
			organic(position = 1, total = 59, at = 0, inferredPlaying = true),
		)
		var update = tracker.snapshot()!!
		for (second in 1..40) {
			update = tracker.observe(
				organic(
					position = 1,
					total = 59,
					at = second * 1_000L,
					inferredPlaying = true,
				),
			).active!!
		}
		assertEquals(40_000, update.playedMs)
		assertEquals(40_000, update.inferredPlayedMs)

		// YouTube finally publishes where the seekbar really is. Those 40 seconds
		// are already paid for by inference, so the delayed jump adds nothing.
		val caughtUp = tracker.observe(
			organic(position = 41, total = 59, at = 41_000, inferredPlaying = true),
		)
		assertEquals(40_000, caughtUp.active!!.playedMs)

		tracker.proofMissing(42_000, "tab switched")
		val ended = tracker.proofMissing(
			42_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"tab switched",
		)
		assertEquals(40_000, ended.finalized.single().playedMs)
		assertTrue("the field listen must clear 60%", 40.0 / 59.0 > 0.60)
	}

	@Test
	fun `a stalled seekbar earns nothing without paired playback evidence`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 1, total = 59, at = 0))
		for (second in 1..40) {
			tracker.observe(organic(position = 1, total = 59, at = second * 1_000L))
		}
		assertEquals(0, tracker.snapshot()!!.playedMs)
	}

	/**
	 * Regression for the exact field route: Home Short, Shorts-tab Short, then a
	 * Home Short finished in PiP. All three seekbars stayed cached while the
	 * paired evidence remained true; each listen must end once and independently.
	 */
	@Test
	fun `Home then Shorts tab then Home to PiP finalizes three stalled Shorts once`() {
		val tracker = ForegroundShortTracker()
		val finalized = mutableListOf<SessionSnapshot>()
		var now = 0L

		fun plateau(title: String, handle: String, duration: Long, seconds: Int) {
			finalized += tracker.observe(
				organic(
					title = title,
					handle = handle,
					position = 1,
					total = duration,
					at = now,
					inferredPlaying = true,
				),
			).finalized
			repeat(seconds) {
				now += 1_000
				finalized += tracker.observe(
					organic(
						title = title,
						handle = handle,
						position = 1,
						total = duration,
						at = now,
						inferredPlaying = true,
					),
				).finalized
			}
		}

		plateau("Home Short A", "@home-a", duration = 59, seconds = 40)
		now += 1_000
		plateau("Shorts tab B", "@shorts-b", duration = 97, seconds = 60)
		now += 1_000
		plateau("Home Short C", "@home-c", duration = 124, seconds = 70)

		// The third Short leaves fullscreen for PiP. Direct progress disappears,
		// but the same paired evidence continues the already-proven identity.
		repeat(10) {
			now += 1_000
			finalized += tracker.proofMissing(
				now,
				"picture-in-picture progress surface",
				progressSurfaceLost = true,
				inferredPlaying = true,
			).finalized
		}
		now += 1_000
		tracker.proofMissing(
			now,
			"PiP closed",
			progressSurfaceLost = true,
			inferredPlaying = false,
		)
		finalized += tracker.proofMissing(
			now + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"PiP closed",
			progressSurfaceLost = true,
			inferredPlaying = false,
		).finalized

		assertEquals(listOf("Home Short A", "Shorts tab B", "Home Short C"), finalized.map { it.title })
		assertTrue(finalized.all { it.percentPlayed!! > 0.60 })
		assertEquals(3, finalized.map { it.trackInstanceToken }.toSet().size)
		assertEquals(3, finalized.size)
	}

	@Test
	fun `a Short played at double speed earns the content it traversed`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 121, at = 0))
		// Ten wall-clock seconds per poll, twenty seconds of content each time.
		var played = 0L
		listOf(20L, 40L, 60L, 80L, 100L).forEachIndexed { index, position ->
			played = tracker.observe(
				organic(position = position, total = 121, at = (index + 1) * 10_000L),
			).active!!.playedMs
		}
		assertEquals(100_000, played)
	}

	@Test
	fun `nothing faster than the platform's own maximum is admitted`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 121, at = 0))
		// Four times wall-clock is not playback at any speed YouTube offers, so
		// it stays what it has always been treated as: a seek, earning nothing.
		val refused = tracker.observe(organic(position = 40, total = 121, at = 10_000))
		assertEquals(0, refused.active!!.playedMs)
		// And it says so. This refusal was silent until v0.9.14, which is the
		// only reason 2× playback presented as "the scrobbler stopped working".
		assertTrue(refused.diagnostic!!, "jumped 40s" in refused.diagnostic!!)
		assertTrue(refused.diagnostic!!, "earns nothing" in refused.diagnostic!!)
	}

	@Test
	fun `a refusal describes only the observation it belongs to`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 121, at = 0))
		assertNotNull(tracker.observe(organic(position = 40, total = 121, at = 10_000)).diagnostic)
		// An ordinary credited advance immediately afterwards must not inherit it.
		val credited = tracker.observe(organic(position = 45, total = 121, at = 15_000))
		assertEquals(5_000, credited.active!!.playedMs)
		assertTrue(credited.diagnostic!!, "earns nothing" !in credited.diagnostic!!)
	}

	@Test
	fun `unchanged accessibility polls do not erase the sparse delta time bound`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, at = 0))
		tracker.observe(organic(position = 0, at = 2_000))
		tracker.observe(organic(position = 0, at = 4_000))
		assertEquals(5_000, tracker.observe(organic(position = 5, at = 5_000)).active!!.playedMs)
	}

	@Test
	fun `strict end to start wrap earns only traversed seconds and marks loop`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 18, total = 20, at = 0))
		val snapshot = tracker.observe(organic(position = 1, total = 20, at = 3_000)).active!!
		assertEquals(3_000, snapshot.playedMs)
		assertTrue(snapshot.loopDetected)
	}

	@Test
	fun `ordinary rewind and implausible wrap earn nothing`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 15, total = 20, at = 0))
		assertEquals(0, tracker.observe(organic(position = 5, total = 20, at = 10_000)).active!!.playedMs)
		val implausible = ForegroundShortTracker().apply {
			observe(organic(position = 19, total = 20, at = 0))
		}.observe(organic(position = 3, total = 20, at = 100)).active!!
		assertEquals(0, implausible.playedMs)
		assertFalse(implausible.loopDetected)
	}

	@Test
	fun `organic ad organic transitions isolate progress and ad evidence`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(title = "A", handle = "@a_owner", position = 0, at = 0))
		tracker.observe(organic(title = "A", handle = "@a_owner", position = 5, at = 5_000))
		val toAd = tracker.observe(ad(position = 0, at = 6_000))
		assertEquals("A", toAd.finalized.single().title)
		assertEquals(5_000, toAd.finalized.single().playedMs)
		assertEquals("Sponsored", toAd.active!!.explicitAdSignal)

		val toB = tracker.observe(organic(title = "B", handle = "@b_owner", position = 0, at = 7_000))
		assertEquals("Sponsored", toB.finalized.single().explicitAdSignal)
		assertNull(toB.active!!.explicitAdSignal)
		assertEquals("B", toB.active!!.title)
		assertEquals(0, toB.active!!.playedMs)
	}

	@Test
	fun `rapid organic swipes finalize each exact key at its own value`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(title = "A", handle = "@a_owner", position = 3, at = 0))
		val b = tracker.observe(organic(title = "B", handle = "@b_owner", position = 8, at = 100))
		val c = tracker.observe(organic(title = "C", handle = "@c_owner", position = 1, at = 200))
		assertEquals("A", b.finalized.single().title)
		assertEquals(0, b.finalized.single().playedMs)
		assertEquals("B", c.finalized.single().title)
		assertEquals(0, c.finalized.single().playedMs)
	}

	@Test
	fun `temporary proof loss freezes and recovery resets the baseline`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 2, at = 0))
		tracker.observe(organic(position = 7, at = 5_000))
		val missing = tracker.proofMissing(6_000, "tree missing")
		assertEquals(5_000, missing.active!!.playedMs)
		assertFalse(missing.completeForegroundProof)
		assertEquals(5_000, tracker.observe(organic(position = 15, at = 7_000)).active!!.playedMs)
		assertEquals(7_000, tracker.observe(organic(position = 17, at = 9_000)).active!!.playedMs)
	}

	@Test
	fun `PiP proof disappearance finalizes after grace with no gap credit`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, at = 0))
		tracker.observe(organic(position = 12, at = 12_000))
		tracker.proofMissing(13_000, "PiP removed structural fields")
		val ended = tracker.proofMissing(
			13_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"PiP removed structural fields",
		)
		assertEquals(12_000, ended.finalized.single().playedMs)
		assertNull(ended.active)
		assertFalse(tracker.hasActive)
	}

	@Test
	fun `only the lost-surface signature marks a finalize as unmeasurable`() {

		val ordinary = ForegroundShortTracker()
		ordinary.observe(organic(position = 0, at = 0))
		ordinary.observe(organic(position = 12, at = 12_000))
		ordinary.proofMissing(13_000, "swiped to the next Short")
		val swipedAway = ordinary.proofMissing(
			13_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"swiped to the next Short",
		)
		assertFalse(swipedAway.finalized.single().foregroundProgressLost)

		val pip = ForegroundShortTracker()
		pip.observe(organic(position = 0, at = 0))
		pip.observe(organic(position = 12, at = 12_000))
		pip.proofMissing(13_000, "one seekbar container, no readable time", progressSurfaceLost = true)
		val wentToPip = pip.proofMissing(
			13_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"one seekbar container, no readable time",
			progressSurfaceLost = true,
		)
		val ended = wentToPip.finalized.single()
		assertTrue(ended.foregroundProgressLost)
		// Still a refusal to claim time, not a claim of zero: the last measured
		// value stands and the gap earns nothing.
		assertEquals(12_000, ended.playedMs)
	}

	@Test
	fun `an unrelated refusal between two PiP polls does not erase the marker`() {
		// The capture watchdog and the scroll/seek reset both raise Missing with
		// their own reasons. One landing mid-PiP must not turn an unmeasurable
		// finalize back into an apparent 0%.
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, at = 0))
		tracker.observe(organic(position = 9, at = 9_000))
		tracker.proofMissing(10_000, "no readable time", progressSurfaceLost = true)
		tracker.proofMissing(10_500, "fresh foreground Shorts proof expired")
		val ended = tracker.proofMissing(
			10_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"fresh foreground Shorts proof expired",
		)
		assertTrue(ended.finalized.single().foregroundProgressLost)
	}

	@Test
	fun `returning from PiP clears the marker so the finalize is honest again`() {

		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, at = 0))
		tracker.observe(organic(position = 9, at = 9_000))
		tracker.proofMissing(10_000, "no readable time", progressSurfaceLost = true)
		val recovered = tracker.observe(organic(position = 11, at = 11_000)).active!!
		assertFalse(recovered.foregroundProgressLost)
		val ended = tracker.observe(organic(title = "Next", position = 1, at = 13_000))
		assertFalse(ended.finalized.single().foregroundProgressLost)
	}

	@Test
	fun `a Short still playing in PiP accrues inferred time instead of finalizing`() {
		// Before this, the 3s no-credit grace finalized a Short two polls into a
		// PiP session, so PiP was always worth exactly nothing.
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
			assertTrue("must not finalize while still playing", update.finalized.isEmpty())
		}
		val active = tracker.snapshot()!!
		// 20s measured from the seekbar + ~19s of inferred wall-clock.
		assertEquals(19_000, active.inferredPlayedMs)
		assertEquals(39_000, active.playedMs)
		assertTrue(active.foregroundProgressLost)
	}

	@Test
	fun `the freshness watchdog interleaving does not stop inferred credit`() {
		// While the surface is gone the 1s freshness
		// watchdog raises its own Missing with no playback evidence, so real
		// observations arrive interleaved with generic ones. Treating a generic
		// refusal as a pause dropped the anchor between every pair of real ticks
		// and credited 0ms forever.
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 179, at = 0))
		tracker.observe(organic(position = 44, total = 179, at = 44_000))
		var now = 44_000L
		repeat(10) {
			now += 500
			tracker.proofMissing(now, "fresh foreground Shorts proof expired")
			now += 500
			tracker.proofMissing(
				now,
				"one seekbar container, no readable time",
				progressSurfaceLost = true,
				inferredPlaying = true,
			)
		}
		val active = tracker.snapshot()!!
		assertEquals(9_000, active.inferredPlayedMs)
		assertEquals(53_000, active.playedMs)
	}

	@Test
	fun `a fully credited PiP Short finalizes instead of hanging forever`() {

		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 20, at = 0))
		tracker.observe(organic(position = 1, total = 20, at = 1_000))
		var now = 1_000L
		var finalized: SessionSnapshot? = null
		repeat(120) {
			now += 1_000
			val update = tracker.proofMissing(
				now,
				"one seekbar container, no readable time",
				progressSurfaceLost = true,
				inferredPlaying = true,
			)
			update.finalized.firstOrNull()?.let { if (finalized == null) finalized = it }
		}
		val ended = requireNotNull(finalized) { "a capped PiP Short must finalize" }
		// The whole Short: 1s from the seekbar plus 19s inferred, never more.
		assertEquals(20_000, ended.playedMs)
		assertEquals(19_000, ended.inferredPlayedMs)
		assertFalse(tracker.hasActive)
	}

	@Test
	fun `inferred time stops the moment the evidence stops`() {
		// Audio paused, or YouTube's window gone: the grace resumes and the
		// Short finalizes with only what it had earned.
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 60, at = 0))
		tracker.observe(organic(position = 10, total = 60, at = 10_000))
		tracker.proofMissing(11_000, "pip", progressSurfaceLost = true, inferredPlaying = true)
		tracker.proofMissing(12_000, "pip", progressSurfaceLost = true, inferredPlaying = true)

		// Evidence stops. Grace runs from here and expires as it always did.
		tracker.proofMissing(13_000, "pip", progressSurfaceLost = true, inferredPlaying = false)
		val ended = tracker.proofMissing(
			13_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"pip",
			progressSurfaceLost = true,
			inferredPlaying = false,
		).finalized.single()
		assertEquals(1_000, ended.inferredPlayedMs)
		assertEquals(11_000, ended.playedMs)
	}

	@Test
	fun `only the PiP signature may accrue inferred time`() {
		// A swipe, a blown budget or a hidden root mean the Short is gone, not
		// unmeasurable. Crediting through those would turn scrolling into watch
		// time — the single most dangerous way this could go wrong.
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 60, at = 0))
		tracker.observe(organic(position = 10, total = 60, at = 10_000))
		tracker.proofMissing(11_000, "swiped away", progressSurfaceLost = false, inferredPlaying = true)
		val ended = tracker.proofMissing(
			11_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"swiped away",
			progressSurfaceLost = false,
			inferredPlaying = true,
		).finalized.single()
		assertEquals(0, ended.inferredPlayedMs)
		assertEquals(10_000, ended.playedMs)
	}

	@Test
	fun `returning from PiP keeps the inferred time and resumes measuring`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 60, at = 0))
		tracker.observe(organic(position = 10, total = 60, at = 10_000))
		tracker.proofMissing(11_000, "pip", progressSurfaceLost = true, inferredPlaying = true)
		tracker.proofMissing(12_000, "pip", progressSurfaceLost = true, inferredPlaying = true)
		tracker.proofMissing(13_000, "pip", progressSurfaceLost = true, inferredPlaying = true)

		// Back to fullscreen: the seekbar is readable again (§4.3).
		val back = tracker.observe(organic(position = 13, total = 60, at = 13_500)).active!!
		assertFalse(back.foregroundProgressLost)
		// The 2s already inferred is banked, not discarded and not double counted.
		assertEquals(2_000, back.inferredPlayedMs)

		assertEquals(13_000, back.playedMs)
		// Measuring resumes from the new baseline.
		val advanced = tracker.observe(organic(position = 16, total = 60, at = 16_500)).active!!
		// 14s measured (10 before PiP, 1 reconciled across it, 3 since) + 2s inferred.
		assertEquals(16_000, advanced.playedMs)
	}

	@Test
	fun `a looping Short in PiP cannot be credited past its own length`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 20, at = 0))
		tracker.observe(organic(position = 5, total = 20, at = 5_000))
		var now = 5_000L
		var ended: SessionSnapshot? = null
		repeat(300) {
			now += 1_000
			val update = tracker.proofMissing(
				now, "pip", progressSurfaceLost = true, inferredPlaying = true,
			)
			update.finalized.firstOrNull()?.let { if (ended == null) ended = it }
		}
		// Capped at the Short's own length, and then *ended* — a capped Short has
		// nothing left to earn, so continuing to hold it open would lose the
		// listen entirely rather than protect it.
		val finalized = requireNotNull(ended) { "a capped PiP Short must finalize" }
		assertEquals(20_000, finalized.playedMs)
		assertEquals(15_000, finalized.inferredPlayedMs)
		assertFalse(tracker.hasActive)
	}

	@Test
	fun `a Short left looping banks its listen instead of counting forever`() {

		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 20, at = 0))
		var banked: SessionSnapshot? = null
		var now = 0L
		var position = 0L
		repeat(60) {
			now += 1_000
			position = (position + 1) % 21
			val update = tracker.observe(organic(position = position, total = 20, at = now))
			update.finalized.firstOrNull()?.let { if (banked == null) banked = it }
		}
		val listen = requireNotNull(banked) { "a Short played to its full length must bank" }
		assertTrue(listen.playedMs >= 20_000)
		// Still on screen and still tracked — banking the listen does not end the
		// viewing, it just stops the count being lost.
		assertTrue(tracker.hasActive)
	}

	@Test
	fun `a full listen is banked once, not once per loop`() {
		// capForKind allows a Short exactly one transaction, so banking per loop
		// would only produce refusals — and a second bank would re-open the same
		// question the dedup ledger already answers.
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 10, at = 0))
		var banks = 0
		var now = 0L
		var position = 0L
		repeat(120) {
			now += 1_000
			position = (position + 1) % 11
            banks += tracker.observe(organic(position = position, total = 10, at = now))
				.finalized.size
		}
		assertEquals(1, banks)
	}

	@Test
	fun `a banked listen is not finalized again when the Short goes away`() {

		listOf<(ForegroundShortTracker, Long) -> ForegroundShortTracker.Update>(
			{ tracker, now -> tracker.observe(organic(title = "Next", position = 0, at = now)) },
			{ tracker, now -> tracker.proofMissing(now, "player went away") },
		).forEach { end ->
			val tracker = ForegroundShortTracker()
			tracker.observe(organic(position = 0, total = 10, at = 0))
			var now = 0L
			var banks = 0
			repeat(12) {
				now += 1_000
				banks += tracker.observe(organic(position = it + 1L, total = 10, at = now))
					.finalized.size
			}
			assertEquals(1, banks)
			// The proof-expiry path needs its grace to elapse before it decides.
			end(tracker, now + 1_000)
			assertEquals(0, end(tracker, now + 60_000).finalized.size)
		}
	}

	@Test
	fun `one times then two times then one times again keeps every second`() {
		val tracker = ForegroundShortTracker()
		// 6 seconds watched normally, from the seekbar.
		tracker.observe(organic(position = 0, total = 60, at = 0))
		tracker.observe(organic(position = 6, total = 60, at = 6_000))
		assertEquals(6_000, tracker.snapshot()!!.playedMs)

		// The hold: overlay stripped, chip says 2x. Ten seconds of wall clock.
		var now = 6_000L
		repeat(10) {
			now += 1_000
			tracker.proofMissing(
				now,
				"owner handle gone",
				progressSurfaceLost = true,
				inferredPlaying = true,
				playbackRate = 2.0,
			)
		}
		// The first poll only drops the anchor, so nine intervals are credited.
		val afterHold = tracker.snapshot()!!.playedMs
		assertEquals("6s measured + 2x across nine held seconds", 24_000, afterHold)

		// Released: the bar is back and the position has run on with it.
		val resumed = tracker.observe(organic(position = 24, total = 60, at = now + 1_000))
		assertEquals("the jump is already paid for, not re-credited", 24_000, resumed.active!!.playedMs)

		// And normal speed continues to earn from here.
		val later = tracker.observe(organic(position = 32, total = 60, at = now + 9_000))
		assertEquals(32_000, later.active!!.playedMs)
	}

	@Test
	fun `a footerless poll still measures the Short already acquired`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 60, at = 0))
		tracker.observe(organic(position = 6, total = 60, at = 6_000))

		// The hold: no footer, bar still readable, running at 2x.
		val held = tracker.observe(unnamed(position = 18, total = 60, at = 12_000))
		assertEquals("6s at 1x plus 12s of content in 6s held", 18_000, held.active!!.playedMs)
		assertTrue(held.diagnostic!!, "footer off screen" in held.diagnostic!!)

		// Released: the footer is back and it simply carries on.
		assertEquals(
			24_000,
			tracker.observe(organic(position = 24, total = 60, at = 18_000)).active!!.playedMs,
		)
	}

	/**
	 * Exact field shape for AhlkQPqStO8 (55s): a readable footerless seekbar
	 * plateaued at 2s while the visible speed chip said 2x. Twenty-eight seconds
	 * of wall clock is enough for a full listen and must not remain 28/55.
	 */
	@Test
	fun `Ahlk footerless 2x plateau banks a full 55 second listen`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(
			organic(
				title = "Best Girl Guitarist 🎸 Bae",
				handle = "@lfgbae",
				position = 0,
				total = 55,
				at = 0,
			),
		)

		var finalized = 0
		for (second in 1L..28L) {
			finalized += tracker.observe(
				unnamed(
					position = 2,
					total = 55,
					at = second * 1_000,
					inferredPlaying = true,
					playbackRate = 2.0,
				),
			).finalized.size
		}

		assertEquals("the listen was not banked exactly once", 1, finalized)
		assertEquals("2x inference did not reach the duration cap", 55_000, tracker.snapshot()!!.playedMs)
	}

	/**
	 * Exact field shape for YX1m91vXNNE (108s): the title was unreadable but the
	 * owner/duration were proven, and the cached seekbar stayed near zero for 47
	 * wall seconds. At 2x that is 94 content seconds and comfortably scrobblable.
	 */
	@Test
	fun `YX named 2x plateau credits 94 of 108 seconds`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(
			organic(
				title = null,
				handle = "@barefoot_surf",
				position = 0,
				total = 108,
				at = 0,
				inferredPlaying = true,
				playbackRate = 2.0,
			),
		)
		for (second in 1L..47L) {
			tracker.observe(
				organic(
					title = null,
					handle = "@barefoot_surf",
					position = 0,
					total = 108,
					at = second * 1_000,
					inferredPlaying = true,
					playbackRate = 2.0,
				),
			)
		}

		assertEquals(94_000, tracker.snapshot()!!.playedMs)
		assertTrue(tracker.snapshot()!!.playedMs * 100 / 108_000 >= 60)
	}

	@Test
	fun `a plateau without a speed chip remains one times`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 108, at = 0, inferredPlaying = true))
		for (second in 1L..47L) {
			tracker.observe(
				organic(position = 0, total = 108, at = second * 1_000, inferredPlaying = true),
			)
		}

		assertEquals(47_000, tracker.snapshot()!!.playedMs)
		assertTrue(tracker.snapshot()!!.playedMs * 100 / 108_000 < 60)
	}

	@Test
	fun `a delayed seekbar jump does not double count a 2x plateau`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(
			organic(
				position = 0,
				total = 60,
				at = 0,
				inferredPlaying = true,
				playbackRate = 2.0,
			),
		)
		for (second in 1L..10L) {
			tracker.observe(
				organic(
					position = 0,
					total = 60,
					at = second * 1_000,
					inferredPlaying = true,
					playbackRate = 2.0,
				),
			)
		}
		assertEquals(20_000, tracker.snapshot()!!.playedMs)

		val published = tracker.observe(
			organic(
				position = 20,
				total = 60,
				at = 11_000,
				inferredPlaying = true,
				playbackRate = 2.0,
			),
		)
		assertEquals("the delayed 20s jump was credited twice", 20_000, published.active!!.playedMs)
	}

	@Test
	fun `a footerless poll cannot start a Short or credit a different one`() {
		val fresh = ForegroundShortTracker()
		val nothing = fresh.observe(unnamed(position = 10, total = 60, at = 0))
		assertFalse("nothing may be acquired without a handle", fresh.hasActive)
		assertNull(nothing.active)

		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 60, at = 0))
		tracker.observe(organic(position = 6, total = 60, at = 6_000))
		// A different length is a different Short; its seconds are not ours.
		tracker.observe(unnamed(position = 30, total = 95, at = 8_000))
		assertEquals(6_000, tracker.snapshot()!!.playedMs)
	}

	@Test
	fun `Stop disconnect opt-out and source epoch transition discard`() {
		listOf("Stop", "accessibility disconnected", "opt-out").forEach { reason ->
			val tracker = ForegroundShortTracker()
			tracker.observe(organic(position = 9, at = 0))
			val update = tracker.discard(reason)
			assertTrue(update.finalized.isEmpty())
			assertFalse(tracker.hasActive)
		}
		val epoch = ForegroundShortTracker()
		epoch.observe(organic(title = "same", position = 1, at = 0, epoch = 3))
		val transitioned = epoch.observe(organic(title = "same", position = 2, at = 1_000, epoch = 4))
		assertEquals(1, transitioned.finalized.size)
		assertEquals(0, transitioned.active!!.playedMs)
	}

	@Test
	fun `a screen-off evidence dropout does not finalize a Short that is still playing`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 162, at = 0))
		tracker.observe(organic(position = 40, total = 162, at = 40_000))

		// The dropout: no PiP signature, nothing playing, display off.
		var update = ForegroundShortTracker.Update()
		for (step in 1..4) {
			update = tracker.proofMissing(
				nowMillis = 40_000 + step * 1_000L,
				reason = "no fresh active native YouTube accessibility root",
				displayOff = true,
			)
		}

		assertTrue(
			"the Short was still playing; it must not be finalized inside the dropout",
			update.finalized.isEmpty(),
		)
		assertTrue(tracker.hasActive)
	}

	/** The hold credits nothing — it only defers the decision. */
	@Test
	fun `holding the grace open across a screen-off credits no time at all`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 162, at = 0))
		val measured = tracker.observe(organic(position = 40, total = 162, at = 40_000))
		val before = measured.active!!.playedMs

		var update = ForegroundShortTracker.Update()
		for (step in 1..4) {
			update = tracker.proofMissing(
				nowMillis = 40_000 + step * 1_000L,
				reason = "no fresh active native YouTube accessibility root",
				displayOff = true,
			)
		}

		assertEquals("no wall-clock may be credited by the hold", before, update.active!!.playedMs)
	}

	/**
	 * Negative guard: the hold is bounded. A screen genuinely left off must still
	 * finalize, only [ForegroundShortTracker.DISPLAY_OFF_SETTLE_MS] later than it
	 * used to — never "a Short left open forever".
	 */
	@Test
	fun `a screen left off finalizes once the settle window passes`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 162, at = 0))
		tracker.observe(organic(position = 40, total = 162, at = 40_000))

		val finalized = mutableListOf<SessionSnapshot>()
		var now = 40_000L
		repeat(20) {
			now += 1_000
			finalized += tracker.proofMissing(
				nowMillis = now,
				reason = "no fresh active native YouTube accessibility root",
				displayOff = true,
			).finalized
		}

		assertEquals("the bounded hold must still end in a finalize", 1, finalized.size)
		assertFalse(tracker.hasActive)
	}

	/**
	 * Negative guard: with the screen on, nothing changes. The shared lifecycle
	 * timeout keeps its old meaning everywhere the screen-off transition is not
	 * the cause.
	 */
	@Test
	fun `with the display on a missing surface still finalizes at the ordinary grace`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 162, at = 0))
		tracker.observe(organic(position = 40, total = 162, at = 40_000))

		val finalized = mutableListOf<SessionSnapshot>()
		var now = 40_000L
		repeat(5) {
			now += 1_000
			finalized += tracker.proofMissing(
				nowMillis = now,
				reason = "foreground root was hidden or not native YouTube",
				displayOff = false,
			).finalized
		}

		assertEquals("screen-on behaviour must be unchanged", 1, finalized.size)
	}

	/**
	 * The window is anchored per screen-off, not once per Short: a Short that
	 * survives one dropout and later meets another gets the full settle window
	 * again rather than inheriting an expired anchor.
	 */
	@Test
	fun `a second screen-off gets its own settle window`() {
		val tracker = ForegroundShortTracker()
		tracker.observe(organic(position = 0, total = 162, at = 0))
		tracker.observe(organic(position = 20, total = 162, at = 20_000))
		// First dropout, then proof returns.
		tracker.proofMissing(nowMillis = 21_000, reason = "dropout", displayOff = true)
		tracker.observe(organic(position = 40, total = 162, at = 40_000))

		// Second dropout, far beyond the first anchor.
		var update = ForegroundShortTracker.Update()
		for (step in 1..4) {
			update = tracker.proofMissing(
				nowMillis = 40_000 + step * 1_000L,
				reason = "dropout",
				displayOff = true,
			)
		}

		assertTrue(
			"the second screen-off must hold on its own anchor",
			update.finalized.isEmpty(),
		)
	}

	private fun organic(
		title: String? = "Organic",
		handle: String = "@creator",
		position: Long,
		total: Long = 60,
		at: Long,
		epoch: Long = 3,
		inferredPlaying: Boolean = false,
		playbackRate: Double? = null,
	) = ForegroundShortTracker.OrganicObservation(
		title,
		handle,
		position,
		total,
		at,
		epoch,
		inferredPlaying,
		playbackRate,
	)

	private fun unnamed(
		position: Long,
		total: Long,
		at: Long,
		epoch: Long = 3,
		inferredPlaying: Boolean = false,
		playbackRate: Double? = null,
	) = ForegroundShortTracker.UnnamedObservation(
		position,
		total,
		at,
		epoch,
		inferredPlaying,
		playbackRate,
	)

	private fun unmeasured(
		title: String? = "Organic",
		handle: String = "@creator",
		at: Long,
		playing: Boolean = true,
		epoch: Long = 3,
		playbackRate: Double? = null,
	) = ForegroundShortTracker.UnmeasuredObservation(
		title,
		handle,
		at,
		epoch,
		playing,
		playbackRate,
	)

	private fun ad(position: Long, at: Long) = ForegroundShortTracker.AdObservation(
		signal = "Sponsored",
		title = "Advert",
		currentSeconds = position,
		totalSeconds = 18,
		observedAtMillis = at,
		sourceEpoch = 3,
	)

	@Test
	fun `a Short scored by the picture-in-picture grace is not scored again on return`() {
		val tracker = ForegroundShortTracker()
		val total = 179L
		tracker.observe(organic(title = "Repro", position = 0, total = total, at = 0))

		var now = 1_000L
		var graceFinalized = 0
		for (tick in 0 until 400) {
			val update = tracker.proofMissing(
				nowMillis = now,
				reason = "picture-in-picture",
				progressSurfaceLost = true,
				inferredPlaying = true,
			)
			now += 1_000
			if (update.finalized.isNotEmpty()) {
				graceFinalized += update.finalized.size
				break
			}
		}
		assertEquals("the PiP grace scored the completed listen", 1, graceFinalized)

		// The Short is still on screen when the player root comes back.
		val returned = tracker.observe(
			organic(title = "Repro", position = total, total = total, at = now),
		)

		assertEquals(
			"the same viewing was scored twice — the second becomes a duplicate " +
				"Not logged row beside its own scrobble",
			emptyList<Long>(),
			returned.finalized.map { it.playedMs },
		)
	}

	/** Drives a Short to its full length on inferred PiP time and returns the grace's output. */
	private fun pipToCompletion(
		tracker: ForegroundShortTracker,
		total: Long,
		title: String = "Repro",
		startAt: Long = 1_000L,
	): Pair<Int, Long> {
		tracker.observe(organic(title = title, position = 0, total = total, at = 0))
		var now = startAt
		var finalizedCount = 0
		for (tick in 0 until 400) {
			val update = tracker.proofMissing(
				nowMillis = now,
				reason = "picture-in-picture",
				progressSurfaceLost = true,
				inferredPlaying = true,
			)
			now += 1_000
			if (update.finalized.isNotEmpty()) {
				finalizedCount += update.finalized.size
				break
			}
		}
		return finalizedCount to now
	}

	/** Walks the seekbar a second at a time; a jump is refused as a seek, not credited. */
	private fun walkSeekbar(
		tracker: ForegroundShortTracker,
		title: String,
		handle: String = "@creator",
		total: Long,
		fromSeconds: Long,
		toSeconds: Long,
		startAt: Long,
	): ForegroundShortTracker.Update {
		var update: ForegroundShortTracker.Update? = null
		for (second in fromSeconds..toSeconds) {
			update = tracker.observe(
				organic(
					title = title,
					handle = handle,
					position = second,
					total = total,
					at = startAt + (second - fromSeconds) * 1_000,
				),
			)
			if (update.finalized.isNotEmpty()) return update
		}
		return update!!
	}

	/** 3 — the opposite ordering: banked on screen first, grace afterwards. */
	@Test
	fun `a Short banked on screen is not finalized again by a later grace expiry`() {
		val tracker = ForegroundShortTracker()
		val total = 30L
		val banked = walkSeekbar(tracker, "Banked", total = total, fromSeconds = 0, toSeconds = total, startAt = 0)
		assertEquals("the completion bank scored it", 1, banked.finalized.size)

		tracker.proofMissing(31_000, "gone")
		val grace = tracker.proofMissing(
			31_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"gone",
		)

		assertEquals("the grace may not score it again", emptyList<Long>(), grace.finalized.map { it.playedMs })
	}

	/** 4 — restored before any terminal output: continues, and finalizes exactly once. */
	@Test
	fun `a Short restored before completion still finalizes exactly once`() {
		val tracker = ForegroundShortTracker()
		val total = 60L
		tracker.observe(organic(title = "Partial", position = 0, total = total, at = 0))
		tracker.observe(organic(title = "Partial", position = 20, total = total, at = 20_000))

		// Taken away and brought back inside the resume window, before anything
		// terminal has been emitted for it.
		tracker.proofMissing(21_000, "player root gone")
		val grace = tracker.proofMissing(
			21_000 + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
			"player root gone",
		)
		assertEquals("the grace ended the interrupted listen", 1, grace.finalized.size)

		val returned = tracker.observe(organic(title = "Partial", position = 22, total = total, at = 26_000))
		assertEquals(
			"the restored listen may not repeat the terminal output",
			emptyList<Long>(),
			returned.finalized.map { it.playedMs },
		)
	}

	/** 6 — a genuine replay past the resume window is its own listen and may finalize. */
	@Test
	fun `a genuine replay after the resume window finalizes on its own`() {
		val tracker = ForegroundShortTracker()
		val total = 30L
		val (graceCount, endedAt) = pipToCompletion(tracker, total)
		assertEquals(1, graceCount)

		// Far outside RESUME_WINDOW_MS, and starting from zero: nothing to resume.
		val replayAt = endedAt + ForegroundShortTracker.RESUMED_WINDOW_MS + 60_000
		val replayed = walkSeekbar(
			tracker, "Repro", total = total, fromSeconds = 0, toSeconds = total, startAt = replayAt,
		)

		assertEquals("a genuine replay is a new listen and must score", 1, replayed.finalized.size)
	}

	/** 7 — a different Short after the restore is untouched by the flag. */
	@Test
	fun `a different Short after a finalized one scores normally`() {
		val tracker = ForegroundShortTracker()
		val (graceCount, endedAt) = pipToCompletion(tracker, total = 30L)
		assertEquals(1, graceCount)

		val other = walkSeekbar(
			tracker, "Another", handle = "@other", total = 20,
			fromSeconds = 0, toSeconds = 20, startAt = endedAt,
		)

		assertEquals("an unrelated Short still scores", 1, other.finalized.size)
	}
}
