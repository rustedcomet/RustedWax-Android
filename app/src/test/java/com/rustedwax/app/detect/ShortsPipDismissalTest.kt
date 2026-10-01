package com.rustedwax.app.detect

import com.rustedwax.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Closing a Short's picture-in-picture window ends its listen for good (#15).
 *
 * On the A36 the closed Short stays loaded in YouTube, and YouTube plays it
 * again by itself the next time the app is opened. Before this, that replay
 * either resumed the finalized listen (inside the 30 s resume window) or started
 * a second one (after it), so one viewing produced two outcomes.
 */
class ShortsPipDismissalTest {

	private val pip = PlaybackInput.SourceWindowEvidence.PICTURE_IN_PICTURE
	private val offScreen = PlaybackInput.SourceWindowEvidence.OFF_SCREEN
	private val foreground = PlaybackInput.SourceWindowEvidence.FOREGROUND

	// ---- the boundary ----------------------------------------------------------

	@Test
	fun `closing a paused picture-in-picture window finalizes once and suppresses the same Short`() {
		val tracker = ForegroundShortTracker()
		val outcomes = Outcomes()
		val token = acquire(tracker, outcomes)
		var now = 10_000L
		repeat(10) { now += 1_000; outcomes += pausedInPip(tracker, now) }
		assertEquals("pausing in picture-in-picture ends nothing", 0, outcomes.count)

		now = closeWindow(tracker, outcomes, now)

		assertEquals("the closed Short finalizes exactly once", 1, outcomes.count)
		assertEquals(token, outcomes.all.single().trackInstanceToken)
		assertFalse(tracker.hasActive)

		// Reopened 10 s later — inside the old resume window — and playing again.
		outcomes += replaySameShort(tracker, from = now + 10_000, startSecond = 20)
		assertFalse("the closed Short must not be resumed", tracker.hasActive)

		// And again well after it.
		outcomes += replaySameShort(tracker, from = now + 120_000, startSecond = 20)
		assertFalse("nor started again later", tracker.hasActive)
		assertEquals("one viewing, one outcome", 1, outcomes.count)
	}

	@Test
	fun `closing a playing picture-in-picture window behaves the same`() {
		val tracker = ForegroundShortTracker()
		val outcomes = Outcomes()
		acquire(tracker, outcomes)
		var now = 10_000L
		repeat(8) { now += 1_000; outcomes += playingInPip(tracker, now) }

		now = closeWindow(tracker, outcomes, now)
		assertEquals(1, outcomes.count)
		assertTrue("the PiP time is part of that one outcome", outcomes.all.single().inferredPlayedMs > 0)

		outcomes += replaySameShort(tracker, from = now + 5_000, startSecond = 18)
		outcomes += replaySameShort(tracker, from = now + 45_000, startSecond = 18)
		assertFalse(tracker.hasActive)
		assertEquals(1, outcomes.count)
	}

	@Test
	fun `the suppressed Short going back into picture-in-picture earns nothing`() {
		val tracker = ForegroundShortTracker()
		val outcomes = Outcomes()
		acquire(tracker, outcomes)
		var now = 10_000L
		repeat(3) { now += 1_000; outcomes += pausedInPip(tracker, now) }
		now = closeWindow(tracker, outcomes, now)

		outcomes += replaySameShort(tracker, from = now + 40_000, startSecond = 10)
		now += 50_000
		repeat(20) {
			now += 1_000
			val update = playingInPip(tracker, now)
			outcomes += update
			assertNull("no listen to credit", update.active)
		}
		now = closeWindow(tracker, outcomes, now)
		assertFalse(tracker.hasActive)
		assertEquals(1, outcomes.count)
	}

	// ---- what clears it --------------------------------------------------------

	@Test
	fun `an unreadable or ambiguous frame does not clear the suppression`() {
		val tracker = ForegroundShortTracker()
		val outcomes = Outcomes()
		acquire(tracker, outcomes)
		var now = dismissFromPip(tracker, outcomes)

		// Footer title missing, same owner and length: could be the same Short.
		outcomes += tracker.observe(organic(title = null, position = 5, at = now + 1_000))
		// No footer at all.
		outcomes += tracker.observe(
			ForegroundShortTracker.UnnamedObservation(6, 60, now + 2_000, EPOCH),
		)
		// No seekbar and no title.
		outcomes += tracker.observe(
			ForegroundShortTracker.UnmeasuredObservation(null, HANDLE, now + 3_000, EPOCH, true),
		)
		// A generic refusal.
		outcomes += tracker.proofMissing(now + 4_000, "capture stale")
		assertFalse(tracker.hasActive)

		outcomes += replaySameShort(tracker, from = now + 5_000, startSecond = 7)
		assertFalse("still the closed Short", tracker.hasActive)
		assertEquals(1, outcomes.count)
	}

	@Test
	fun `a different Short clears it, and the old Short is then a normal new listen`() {
		val tracker = ForegroundShortTracker()
		val outcomes = Outcomes()
		val original = acquire(tracker, outcomes)
		var now = dismissFromPip(tracker, outcomes)

		var other: ForegroundShortTracker.Update? = null
		for (second in 0L..5L) {
			other = tracker.observe(
				organic(title = "Other", handle = "@other", position = second, total = 30, at = now + second * 1_000),
			)
			outcomes += other
		}
		assertNotNull("a different Short is acquired normally", other!!.active)
		assertEquals(5_000, other.active!!.playedMs)
		now += 6_000

		// Back to the closed Short: a fresh listen, not a resumption.
		var back: ForegroundShortTracker.Update? = null
		for (second in 0L..4L) {
			back = tracker.observe(organic(position = 20 + second, at = now + second * 1_000))
			outcomes += back
		}
		assertNotNull(back!!.active)
		assertNotEquals(original, back.active!!.trackInstanceToken)
		assertEquals(4_000, back.active!!.playedMs)
		assertEquals("the other Short finalized when this one replaced it", 2, outcomes.count)
	}

	@Test
	fun `the same owner with a different title is a different Short`() {
		val tracker = ForegroundShortTracker()
		val outcomes = Outcomes()
		acquire(tracker, outcomes)
		val now = dismissFromPip(tracker, outcomes)

		val next = tracker.observe(organic(title = "Next upload", position = 0, at = now + 1_000))
		assertNotNull(next.active)
	}

	@Test
	fun `a source reset clears the suppression`() {
		val tracker = ForegroundShortTracker()
		val outcomes = Outcomes()
		acquire(tracker, outcomes)
		val now = dismissFromPip(tracker, outcomes)

		tracker.discard("Native YouTube is off")
		val after = tracker.observe(organic(position = 20, at = now + 5_000))
		assertNotNull(after.active)
	}

	// ---- what must not count as closing the window ------------------------------

	@Test
	fun `leaving without a picture-in-picture window keeps the ordinary resume`() {
		val tracker = ForegroundShortTracker()
		val outcomes = Outcomes()
		val token = acquire(tracker, outcomes)
		var now = 10_000L
		// Off screen, but no window was ever listed: an app switch, not a close.
		while (outcomes.count == 0) {
			now += 1_000
			outcomes += tracker.proofMissing(now, "foreground root was hidden", sourceWindow = offScreen)
		}

		val back = tracker.observe(organic(position = 10, at = now + 10_000))
		assertEquals("the app-switch resume is untouched", token, back.active!!.trackInstanceToken)
		assertEquals(1, outcomes.count)
	}

	@Test
	fun `expanding picture-in-picture back to fullscreen keeps the same listen`() {
		val tracker = ForegroundShortTracker()
		val outcomes = Outcomes()
		val token = acquire(tracker, outcomes)
		var now = 10_000L
		repeat(5) { now += 1_000; outcomes += playingInPip(tracker, now) }

		now += 1_000
		val back = tracker.observe(organic(position = 16, at = now))
		outcomes += back
		assertEquals(token, back.active!!.trackInstanceToken)
		assertEquals(0, outcomes.count)
	}

	@Test
	fun `an expansion whose frames outlast the grace is not mistaken for a close`() {
		val tracker = ForegroundShortTracker()
		val outcomes = Outcomes()
		val token = acquire(tracker, outcomes)
		var now = 10_000L
		repeat(5) { now += 1_000; outcomes += playingInPip(tracker, now) }

		// One frame of launcher with the window already gone, then YouTube's own
		// root with nothing readable yet, long enough for the grace to run out.
		now += 500
		outcomes += tracker.proofMissing(now, "foreground root was hidden", sourceWindow = offScreen)
		while (outcomes.count == 0) {
			now += 1_000
			outcomes += tracker.proofMissing(now, "Shorts footer unreadable", sourceWindow = foreground)
		}

		val back = tracker.observe(organic(position = 18, at = now + 1_000))
		outcomes += back
		assertEquals("the same listen continues", token, back.active!!.trackInstanceToken)
		assertEquals("and is not scored again", 1, outcomes.count)
	}

	@Test
	fun `a refusal that read no window facts does not decide anything`() {
		val tracker = ForegroundShortTracker()
		val outcomes = Outcomes()
		val token = acquire(tracker, outcomes)
		var now = 10_000L
		repeat(3) { now += 1_000; outcomes += pausedInPip(tracker, now) }
		// Window list unreadable from here on (Usage Access withdrawn, screen off…).
		while (outcomes.count == 0) {
			now += 1_000
			outcomes += tracker.proofMissing(now, "proof expired")
		}

		val back = tracker.observe(organic(position = 10, at = now + 5_000))
		assertEquals(token, back.active!!.trackInstanceToken)
	}

	@Test
	fun `a capped Short left in its window is unchanged, and closing it later suppresses nothing`() {
		val tracker = ForegroundShortTracker()
		val outcomes = Outcomes()
		acquire(tracker, outcomes, total = 20)
		var now = 10_000L
		while (outcomes.count == 0) {
			now += 1_000
			outcomes += playingInPip(tracker, now)
		}
		assertEquals("the cap banks it once", 1, outcomes.count)
		repeat(5) { now += 1_000; outcomes += playingInPip(tracker, now) }
		now = closeWindow(tracker, outcomes, now)
		assertEquals(1, outcomes.count)

		// The pre-#15 behaviour for this shape: a genuine replay later is its own listen.
		val replay = tracker.observe(organic(position = 0, total = 20, at = now + 20 * 60_000))
		assertNotNull(replay.active)
	}

	@Test
	fun `pause and resume while the window stays up remain one listen`() {
		val tracker = ForegroundShortTracker()
		val outcomes = Outcomes()
		val token = acquire(tracker, outcomes)
		var now = 10_000L
		repeat(3) { now += 1_000; outcomes += playingInPip(tracker, now) }
		val inferredBefore = tracker.snapshot()!!.inferredPlayedMs
		repeat(20) { now += 1_000; outcomes += pausedInPip(tracker, now) }
		assertEquals("a pause earns nothing", inferredBefore, tracker.snapshot()!!.inferredPlayedMs)
		repeat(3) { now += 1_000; outcomes += playingInPip(tracker, now) }

		assertEquals(token, tracker.snapshot()!!.trackInstanceToken)
		assertTrue(tracker.snapshot()!!.inferredPlayedMs > inferredBefore)
		assertEquals(0, outcomes.count)
	}


	// ---- Codex blocker 1: an unreadable window list is not a closed window ------

	@Test
	fun `only a readable list with every picture-in-picture window identified proves absence`() {
		val adapter = NativeShortsAdapter()
		val youTube = YouTubeProbe.YOUTUBE_PACKAGE
		fun window(pip: Boolean, owner: String?) =
			ShortsWindowFact(inPictureInPictureMode = pip, ownerPackage = { owner })

		assertEquals(ShortsPipWindowRead.UNKNOWN, adapter.readPictureInPictureWindowState(null))
		assertEquals(ShortsPipWindowRead.UNKNOWN, adapter.readPictureInPictureWindowState(emptyList()))
		assertEquals(
			"an unidentifiable picture-in-picture window could be YouTube's",
			ShortsPipWindowRead.UNKNOWN,
			adapter.readPictureInPictureWindowState(
				listOf(window(false, "com.sec.android.app.launcher"), window(true, null)),
			),
		)
		assertEquals(
			ShortsPipWindowRead.ABSENT,
			adapter.readPictureInPictureWindowState(
				listOf(window(false, "com.sec.android.app.launcher"), window(true, "com.other.app")),
			),
		)
		assertEquals(
			ShortsPipWindowRead.ABSENT,
			adapter.readPictureInPictureWindowState(listOf(window(false, "com.sec.android.app.launcher"))),
		)
		assertEquals(
			"YouTube's window wins over an unidentifiable one",
			ShortsPipWindowRead.PRESENT,
			adapter.readPictureInPictureWindowState(listOf(window(true, null), window(true, youTube))),
		)
	}

	@Test
	fun `an unreadable window list after picture-in-picture never arms a dismissal`() {
		val adapter = NativeShortsAdapter()
		val tracker = ForegroundShortTracker()
		val outcomes = Outcomes()
		val token = acquire(tracker, outcomes)
		var now = 10_000L
		repeat(3) {
			now += 1_000
			outcomes += throughAdapter(adapter, tracker, now, ShortsPipWindowRead.PRESENT, audio = false)
		}
		// The window list fails, comes back empty or meets an unidentifiable
		// window from here on — every one of those is UNKNOWN.
		while (outcomes.count == 0) {
			now += 1_000
			outcomes += throughAdapter(adapter, tracker, now, ShortsPipWindowRead.UNKNOWN, audio = false)
		}

		val back = tracker.observe(organic(position = 10, at = now + 40_000))
		assertNotNull("not suppressed", back.active)
		// Past the 30 s window this is the ordinary fresh replay, as before #15.
		assertEquals(1, outcomes.count)
		assertTrue(token > 0)
	}

	@Test
	fun `a proven-absent window list still arms the dismissal`() {
		val adapter = NativeShortsAdapter()
		val tracker = ForegroundShortTracker()
		val outcomes = Outcomes()
		acquire(tracker, outcomes)
		var now = 10_000L
		repeat(3) {
			now += 1_000
			outcomes += throughAdapter(adapter, tracker, now, ShortsPipWindowRead.PRESENT, audio = false)
		}
		while (outcomes.count == 0) {
			now += 1_000
			outcomes += throughAdapter(adapter, tracker, now, ShortsPipWindowRead.ABSENT, audio = false)
		}

		outcomes += replaySameShort(tracker, from = now + 5_000, startSecond = 10)
		outcomes += replaySameShort(tracker, from = now + 60_000, startSecond = 10)
		assertFalse(tracker.hasActive)
		assertEquals(1, outcomes.count)
	}

	// ---- Codex blocker 2: handing back to fullscreen disarms the PiP stretch ----

	@Test
	fun `YouTube owning the screen again clears everything the PiP stretch armed`() {
		val tracker = ForegroundShortTracker()
		val outcomes = Outcomes()
		val token = acquire(tracker, outcomes)
		var now = 10_000L
		repeat(3) { now += 1_000; outcomes += playingInPip(tracker, now) }
		// Expanded: YouTube's own root, nothing readable yet.
		now += 500
		outcomes += tracker.proofMissing(now, "Shorts footer unreadable", sourceWindow = foreground)
		// Straight to another app; Android correctly lists no YouTube window.
		while (outcomes.count == 0) {
			now += 1_000
			outcomes += tracker.proofMissing(now, "foreground root was hidden", sourceWindow = offScreen)
		}

		val back = tracker.observe(organic(position = 14, at = now + 10_000))
		assertEquals("an ordinary app switch resumes", token, back.active!!.trackInstanceToken)
		assertEquals(1, outcomes.count)
	}

	@Test
	fun `expand, unreadable proof, immediate app switch stays resumable and is never suppressed`() {
		val tracker = ForegroundShortTracker()
		val outcomes = Outcomes()
		val token = acquire(tracker, outcomes)
		var now = 10_000L
		repeat(3) { now += 1_000; outcomes += pausedInPip(tracker, now) }
		repeat(2) {
			now += 400
			outcomes += tracker.proofMissing(now, "Shorts identity stabilizing", sourceWindow = foreground)
		}
		while (outcomes.count == 0) {
			now += 1_000
			outcomes += tracker.proofMissing(now - 500, "proof expired")
			outcomes += tracker.proofMissing(now, "foreground root was hidden", sourceWindow = offScreen)
		}
		assertEquals(1, outcomes.count)

		// Inside the resume window: the same listen.
		val back = tracker.observe(organic(position = 13, at = now + 8_000))
		assertEquals(token, back.active!!.trackInstanceToken)
		// Leave again the ordinary way and return well after it: still not suppressed.
		var later = now + 9_000
		while (tracker.hasActive) {
			later += 1_000
			outcomes += tracker.proofMissing(later, "foreground root was hidden", sourceWindow = offScreen)
		}
		assertNotNull(tracker.observe(organic(position = 0, at = later + 120_000)).active)
	}

	// ---- the adapter's evidence ---------------------------------------------------

	@Test
	fun `the adapter reports a closed window only from a read window list with the screen on`() {
		fun windowFor(
			kind: ShortsSurfaceUnavailableKind = ShortsSurfaceUnavailableKind.SURFACE_GONE,
			inference: Boolean = true,
			pinned: Boolean = false,
			absent: Boolean = !pinned,
			displayOff: Boolean = false,
		) = NativeShortsAdapter().readUnavailableSurface(
			ShortsSurfaceUnavailableFacts(
				kind = kind,
				reason = "foreground root was hidden or not native YouTube",
				observedAtMillis = 1_000,
				displayOff = displayOff,
				pipEvidence = ShortsPipEvidence(
					inferenceEnabled = inference,
					mediaAudioStarted = false,
					visiblePinnedWindow = pinned,
					pinnedWindowPresent = pinned,
					pinnedWindowAbsent = absent,
				),
				foregroundSurfaceOwned = true,
			),
		).readings.filterIsInstance<ShortsSurfaceReading.Absent>().single().sourceWindow

		assertEquals(offScreen, windowFor())
		assertEquals(pip, windowFor(pinned = true))
		assertEquals(
			"a window list that proved nothing is not a closed window",
			PlaybackInput.SourceWindowEvidence.UNKNOWN,
			windowFor(absent = false),
		)
		assertEquals(PlaybackInput.SourceWindowEvidence.UNKNOWN, windowFor(inference = false))
		assertEquals(PlaybackInput.SourceWindowEvidence.UNKNOWN, windowFor(displayOff = true))
		assertEquals(
			PlaybackInput.SourceWindowEvidence.UNKNOWN,
			windowFor(kind = ShortsSurfaceUnavailableKind.PROOF_EXPIRED),
		)
	}

	@Test
	fun `the adapter carries the window fact into the reducer input`() {
		val input = NativeShortsAdapter().read(
			NativeShortsObserver.Event.Missing(
				reason = "foreground root was hidden or not native YouTube",
				observedAtMillis = 1_000,
				sourceWindow = offScreen,
			),
			foregroundSurfaceOwned = true,
			hostNowMillis = 1_000,
		).filterIsInstance<PlaybackInput.ForegroundSurfaceUnavailable>().single()

		assertEquals(offScreen, input.sourceWindow)
	}

	// ---- helpers ------------------------------------------------------------------

	private class Outcomes {
		val all = mutableListOf<SessionSnapshot>()
		val count: Int get() = all.size
		operator fun plusAssign(update: ForegroundShortTracker.Update) {
			all += update.finalized
		}
		operator fun plusAssign(updates: List<ForegroundShortTracker.Update>) {
			updates.forEach { plusAssign(it) }
		}
	}

	/** Ten seconds of ordinary fullscreen viewing; returns the listen's token. */
	private fun acquire(tracker: ForegroundShortTracker, outcomes: Outcomes, total: Long = 60): Long {
		var update: ForegroundShortTracker.Update? = null
		for (second in 0L..10L) {
			update = tracker.observe(organic(position = second, total = total, at = second * 1_000))
			outcomes += update
		}
		return update!!.active!!.trackInstanceToken!!
	}

	private fun pausedInPip(tracker: ForegroundShortTracker, now: Long) = tracker.proofMissing(
		now,
		"foreground root was hidden or not native YouTube",
		pipWindowPresent = true,
		sourceWindow = pip,
	)

	private fun playingInPip(tracker: ForegroundShortTracker, now: Long) = tracker.proofMissing(
		now,
		"foreground root was hidden or not native YouTube",
		progressSurfaceLost = true,
		inferredPlaying = true,
		sourceWindow = pip,
	)

	/**
	 * One window-only observation the way production delivers it: the adapter
	 * reads the facts, the observer carries the reading, the adapter turns the
	 * event into the reducer's input, and the tracker reduces it.
	 */
	private fun throughAdapter(
		adapter: NativeShortsAdapter,
		tracker: ForegroundShortTracker,
		now: Long,
		window: ShortsPipWindowRead,
		audio: Boolean,
	): List<ForegroundShortTracker.Update> {
		val present = window == ShortsPipWindowRead.PRESENT
		val reading = adapter.readUnavailableSurface(
			ShortsSurfaceUnavailableFacts(
				kind = ShortsSurfaceUnavailableKind.SURFACE_GONE,
				reason = "foreground root was hidden or not native YouTube",
				observedAtMillis = now,
				displayOff = false,
				pipEvidence = ShortsPipEvidence(
					inferenceEnabled = true,
					mediaAudioStarted = audio,
					visiblePinnedWindow = present,
					pinnedWindowPresent = present,
					pinnedWindowAbsent = window == ShortsPipWindowRead.ABSENT,
				),
				foregroundSurfaceOwned = tracker.hasActive,
			),
		).readings.filterIsInstance<ShortsSurfaceReading.Absent>().single()
		val event = NativeShortsObserver.Event.Missing(
			reason = reading.reason,
			observedAtMillis = now,
			progressSurfaceLost = reading.progressSurfaceLost,
			inferredPlaying = reading.inferredPlaying,
			playbackRate = reading.playbackRate,
			displayOff = reading.displayOff,
			pipWindowPresent = reading.pipWindowPresent,
			sourceWindow = reading.sourceWindow,
		)
		return adapter.read(event, foregroundSurfaceOwned = tracker.hasActive, hostNowMillis = now)
			.filterIsInstance<PlaybackInput.ForegroundSurface>()
			.map { tracker.reduce(it) }
	}

	/** A few seconds paused in picture-in-picture, then the window is closed. */
	private fun dismissFromPip(tracker: ForegroundShortTracker, outcomes: Outcomes): Long {
		var now = 10_000L
		repeat(3) { now += 1_000; outcomes += pausedInPip(tracker, now) }
		return closeWindow(tracker, outcomes, now)
	}

	/** The window goes; frames say so until the grace expires. Returns the time it did. */
	private fun closeWindow(tracker: ForegroundShortTracker, outcomes: Outcomes, from: Long): Long {
		var now = from
		val before = outcomes.count
		repeat(10) {
			now += 1_000
			// The watchdog's own refusals interleave and carry no window facts.
			outcomes += tracker.proofMissing(now - 500, "proof expired")
			outcomes += tracker.proofMissing(now, "foreground root was hidden", sourceWindow = offScreen)
			if (outcomes.count > before || !tracker.hasActive) return now
		}
		return now
	}

	/** YouTube plays the retained Short again on its own; twenty seconds of it. */
	private fun replaySameShort(
		tracker: ForegroundShortTracker,
		from: Long,
		startSecond: Long,
	): List<ForegroundShortTracker.Update> = (0L until 20L).map { second ->
		tracker.observe(organic(position = startSecond + second, at = from + second * 1_000)).also {
			assertNull("the closed Short must not be acquired", it.active)
		}
	}

	private fun organic(
		title: String? = "Organic",
		handle: String = HANDLE,
		position: Long,
		total: Long = 60,
		at: Long,
	) = ForegroundShortTracker.OrganicObservation(title, handle, position, total, at, EPOCH)

	private companion object {
		const val HANDLE = "@creator"
		const val EPOCH = 3L
	}
}
