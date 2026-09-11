package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortsContinuityFieldTest {

	private val threshold = 0.60

	private fun tracker() = ForegroundShortTracker()

	// --- Defect 1: the footer title blinking during a 2x hold ---------------

	@Test
	fun `a title blinking out during a 2x hold stays one listen and clears the bar`() {
		val tracker = tracker()
		val title = "This girl was crazy…"
		val handle = "@nyangear"
		val total = 178L
		val finalized = mutableListOf<SessionSnapshot>()

		fun organic(t: String?, position: Long, at: Long) = finalized.addAll(
			tracker.observe(observation(t, handle, position, total, at)).finalized,
		)

		var at = 0L
		for (position in 2L..87L) {
			organic(title, position, at)
			at += 1_000
		}
		// One frame with the footer absent.
		finalized.addAll(tracker.proofMissing(at, "identity is stabilizing").finalized)
		at += 1_000
		// Next frame: the handle is back but the title is not.
		organic(null, 91, at)
		at += 1_000
		organic(null, 94, at)
		at += 3_000
		// Later, the hold is released and the title returns.
		for (position in 95L..119L) {
			organic(title, position, at)
			at += 1_000
		}
		repeat(8) {
			finalized.addAll(tracker.proofMissing(at, "capture unavailable").finalized)
			at += 1_000
		}

		val listen = finalized.singleOrNull()
		assertTrue(
			"one viewing, one listen — got ${finalized.map { "${it.playedMs / 1000}s" }}",
			listen != null,
		)
		assertEquals("and it keeps the title it was proven with", title, listen!!.title)
		assertEquals(handle, listen.artist)
		val played = listen.playedMs.toDouble() / (total * 1_000)
		assertTrue("and it clears the bar: ${(played * 100).toInt()}%", played >= threshold)
		// The bar itself only ever reached 119s of 178s. Continuity must not
		// invent content on top of that.
		assertTrue("without claiming more than the seekbar showed", listen.playedMs <= 118_000)
	}

	/** A blink may reveal a title. It may never swap one title for another. */
	@Test
	fun `a different title is a different Short even through a null frame`() {
		val tracker = tracker()
		val finalized = mutableListOf<SessionSnapshot>()
		finalized.addAll(tracker.observe(observation("A", "@same", 10, 60, 0)).finalized)
		finalized.addAll(tracker.observe(observation(null, "@same", 11, 60, 1_000)).finalized)
		assertTrue("the null frame continues A", finalized.isEmpty())

		val split = tracker.observe(observation("B", "@same", 12, 60, 2_000))
		assertEquals("B ends A rather than joining it", 1, split.finalized.size)
		assertEquals("A", split.finalized.single().title)
		assertEquals(0, split.active!!.playedMs)
	}

	/** Same shape, different owner: not the same Short, never merged. */
	@Test
	fun `a null title does not bridge two different owners`() {
		val tracker = tracker()
		tracker.observe(observation("A", "@first", 10, 60, 0))
		val split = tracker.observe(observation(null, "@second", 11, 60, 1_000))
		assertEquals(1, split.finalized.size)
	}

	/** Same shape, different length: not the same Short, never merged. */
	@Test
	fun `a null title does not bridge two different lengths`() {
		val tracker = tracker()
		tracker.observe(observation("A", "@same", 10, 60, 0))
		val split = tracker.observe(observation(null, "@same", 11, 90, 1_000))
		assertEquals(1, split.finalized.size)
	}

	/**
	 * The position guard is what stops the next Short from a creator's own feed
	 * being swallowed by a blink.
	 */
	@Test
	fun `progress playback could not have made does not merge`() {
		val tracker = tracker()
		tracker.observe(observation("A", "@same", 10, 60, 0))
		// 50 seconds of content in one second of wall clock is a seek, not a hold.
		val ahead = tracker.observe(observation(null, "@same", 60, 60, 1_000))
		assertEquals("a jump that far forward ends the listen", 1, ahead.finalized.size)

		val fresh = tracker()
		fresh.observe(observation("A", "@same", 40, 60, 0))
		// A new Short opens near zero, which reads as backwards from here.
		val behind = fresh.observe(observation(null, "@same", 1, 60, 1_000))
		assertEquals("and so does one that starts over", 1, behind.finalized.size)
	}

	/** A blink separated by a real absence is a return, not a continuation. */
	@Test
	fun `a title missing for longer than the blink window does not merge in place`() {
		val tracker = tracker()
		tracker.observe(observation("A", "@same", 10, 60, 0))
		val late = tracker.observe(
			observation(null, "@same", 11, 60, ForegroundShortTracker.TITLE_BLINK_WINDOW_MS + 1_000),
		)
		assertEquals(1, late.finalized.size)
	}

	// --- Defect 2: the picture-in-picture handback --------------------------

	@Test
	fun `a Short handed back from PiP in time is credited what its bar shows`() {
		val tracker = tracker()
		val handle = "@enika_dj"
		val total = 59L
		val finalized = mutableListOf<SessionSnapshot>()

		var at = 0L
		for (position in 2L..8L) {
			finalized.addAll(
				tracker.observe(observation(null, handle, position, total, at)).finalized,
			)
			at += 1_000
		}
		repeat(23) {
			finalized.addAll(
				tracker.proofMissing(
					at,
					"no owner handle and no readable seekbar time",
					progressSurfaceLost = true,
					inferredPlaying = true,
				).finalized,
			)
			at += 1_000
		}
		// Two frames of half-drawn footer, inside the grace.
		repeat(2) {
			finalized.addAll(tracker.proofMissing(at, "identity is stabilizing").finalized)
			at += 1_000
		}
		assertTrue("the listen is not interrupted", finalized.isEmpty())

		val back = tracker.observe(observation(null, handle, 41, total, at)).active!!
		val played = back.playedMs.toDouble() / (total * 1_000)
		assertTrue("its own bar proves 66%, got ${(played * 100).toInt()}%", played >= threshold)
		assertTrue(
			"and the inference is not double counted: ${back.playedMs}ms of 41s traversed",
			back.playedMs <= 41_000,
		)
	}

	/**
	 * The same handback, but the half-drawn frames outlast the no-credit grace.
	 *
	 * The grace is deliberately *not* widened for this case: a shared lifecycle
	 * timeout is not worth changing to tidy up a duplicate row. So the still-playing
	 * Short does finalize once — a stale Not logged stub — and what matters is that
	 * the listen itself loses nothing: it resumes what it earned and the bar's own
	 * evidence is reconciled on the way back.
	 */
	@Test
	fun `a handback after the grace keeps the listen even though a stub is emitted`() {
		val tracker = tracker()
		val handle = "@enika_dj"
		val total = 59L
		val finalized = mutableListOf<SessionSnapshot>()

		var at = 0L
		for (position in 2L..8L) {
			finalized.addAll(
				tracker.observe(observation(null, handle, position, total, at)).finalized,
			)
			at += 1_000
		}
		repeat(23) {
			finalized.addAll(
				tracker.proofMissing(
					at,
					"pip",
					progressSurfaceLost = true,
					inferredPlaying = true,
				).finalized,
			)
			at += 1_000
		}
		repeat(5) {
			finalized.addAll(tracker.proofMissing(at, "identity is stabilizing").finalized)
			at += 1_000
		}
		assertEquals("one stale stub, accepted", 1, finalized.size)
		val stub = finalized.single()

		val back = tracker.observe(observation(null, handle, 41, total, at)).active!!
		assertTrue(
			"the listen resumes rather than starting over: ${back.playedMs}ms vs stub ${stub.playedMs}ms",
			back.playedMs > stub.playedMs,
		)
		val played = back.playedMs.toDouble() / (total * 1_000)
		assertTrue("and clears the bar: ${(played * 100).toInt()}%", played >= threshold)
		assertTrue("without exceeding what the bar showed", back.playedMs <= 41_000)
	}

	/** A seek during PiP is still a seek and earns nothing. */
	@Test
	fun `a jump too far to have been played after PiP is not credited`() {
		val tracker = tracker()
		val handle = "@seeker"
		val total = 59L
		var at = 0L
		for (position in 2L..8L) {
			tracker.observe(observation(null, handle, position, total, at))
			at += 1_000
		}
		repeat(3) {
			tracker.proofMissing(
				at,
				"pip",
				progressSurfaceLost = true,
				inferredPlaying = true,
			)
			at += 1_000
		}
		// 8s → 55s while only ten seconds of wall clock has passed since the bar
		// last moved. Twice playback speed cannot carry it that far.
		val back = tracker.observe(observation(null, handle, 55, total, at)).active!!
		assertEquals(
			"6s measured plus the 2s inferred, and nothing for the jump",
			8_000,
			back.playedMs,
		)
	}

	/** Only the PiP signature earns a handback. An ordinary freeze does not. */
	@Test
	fun `a position that moved across an ordinary freeze is still not credited`() {
		val tracker = tracker()
		val handle = "@swiper"
		tracker.observe(observation(null, handle, 2, 59, 0))
		tracker.observe(observation(null, handle, 8, 59, 6_000))
		// A capture watchdog, not PiP: carries no evidence about playback.
		tracker.proofMissing(7_000, "fresh foreground Shorts proof expired")
		val back = tracker.observe(observation(null, handle, 20, 59, 9_000)).active!!
		assertEquals("the new baseline is adopted and nothing is earned", 6_000, back.playedMs)
	}

	/** A reconciliation may complete a listen; it may never overrun one. */
	@Test
	fun `a handback cannot credit more content than the Short holds`() {
		val tracker = tracker()
		val handle = "@capped"
		val total = 30L
		var at = 0L
		for (position in 2L..8L) {
			tracker.observe(observation(null, handle, position, total, at))
			at += 1_000
		}
		repeat(15) {
			tracker.proofMissing(at, "pip", progressSurfaceLost = true, inferredPlaying = true)
			at += 1_000
		}
		val back = tracker.observe(observation(null, handle, 29, total, at)).active!!
		assertTrue(
			"credited ${back.playedMs}ms of a ${total}s Short",
			back.playedMs <= total * 1_000,
		)
	}

	// --- Unchanged behaviour ------------------------------------------------

	/** A Short genuinely watched short of the bar is still short of the bar. */
	@Test
	fun `a blink does not lift a listen that was never near the threshold`() {
		val tracker = tracker()
		val handle = "@brief"
		val total = 178L
		val finalized = mutableListOf<SessionSnapshot>()
		var at = 0L
		for (position in 1L..20L) {
			finalized.addAll(
				tracker.observe(observation("Brief", handle, position, total, at)).finalized,
			)
			at += 1_000
		}
		finalized.addAll(tracker.observe(observation(null, handle, 21, total, at)).finalized)
		at += 1_000
		for (position in 22L..30L) {
			finalized.addAll(
				tracker.observe(observation("Brief", handle, position, total, at)).finalized,
			)
			at += 1_000
		}
		repeat(8) {
			finalized.addAll(tracker.proofMissing(at, "gone").finalized)
			at += 1_000
		}
		val listen = finalized.single()
		assertTrue(
			"29s of 178s stays 16%",
			listen.playedMs.toDouble() / (total * 1_000) < threshold,
		)
	}

	/** An ad observation carries its own identity and is untouched by any of this. */
	@Test
	fun `an ad is still an ad and still ends the Short before it`() {
		val tracker = tracker()
		tracker.observe(observation("Organic", "@creator", 10, 60, 0))
		val ad = tracker.observe(
			ForegroundShortTracker.AdObservation(
				signal = "Sponsored",
				title = "Advert",
				currentSeconds = 1,
				totalSeconds = 30,
				observedAtMillis = 1_000,
				sourceEpoch = 3,
			),
		)
		assertEquals(1, ad.finalized.size)
		assertEquals("Sponsored", ad.active!!.explicitAdSignal)
	}

	/** A source-epoch change is still a hard boundary. */
	@Test
	fun `a null title does not bridge two source epochs`() {
		val tracker = tracker()
		tracker.observe(observation("A", "@same", 10, 60, 0, epoch = 3))
		val split = tracker.observe(observation(null, "@same", 11, 60, 1_000, epoch = 4))
		assertEquals(1, split.finalized.size)
	}

	// --- Defect 3: the same blink while YouTube renders no seekbar -----------

	@Test
	fun `a title blinking out with no seekbar stays one listen`() {
		val tracker = tracker()
		val title = "Snoop Dogg\u2019s Funniest Roast Comebacks"
		val handle = "@tunehypeus"
		val finalized = mutableListOf<SessionSnapshot>()

		var at = 0L
		repeat(4) {
			finalized.addAll(tracker.observe(unmeasured(title, handle, at)).finalized)
			at += 1_000
		}
		// The footer's title goes off screen; the handle and the player do not.
		repeat(3) {
			finalized.addAll(tracker.observe(unmeasured(null, handle, at)).finalized)
			at += 1_000
		}
		// It comes back, on the same seekbar-less surface.
		repeat(4) {
			finalized.addAll(tracker.observe(unmeasured(title, handle, at)).finalized)
			at += 1_000
		}

		assertTrue(
			"one viewing, one listen \u2014 got ${finalized.map { "${it.playedMs / 1000}s" }}",
			finalized.isEmpty(),
		)
		val active = tracker.snapshot()
		assertEquals(title, active?.title)
		assertTrue(
			"the whole hold is credited to the one listen \u2014 got ${active?.inferredPlayedMs}ms",
			(active?.inferredPlayedMs ?: 0) >= 10_000,
		)
	}

	@Test
	fun `a seekbar-less blink still refuses a different owner or epoch`() {
		val byOwner = tracker()
		byOwner.observe(unmeasured("A", "@one", 0))
		assertEquals(1, byOwner.observe(unmeasured(null, "@two", 1_000)).finalized.size)

		val byEpoch = tracker()
		byEpoch.observe(unmeasured("A", "@same", 0, epoch = 3))
		assertEquals(1, byEpoch.observe(unmeasured(null, "@same", 1_000, epoch = 4)).finalized.size)

		val byTitle = tracker()
		byTitle.observe(unmeasured("A", "@same", 0))
		assertEquals(1, byTitle.observe(unmeasured("B", "@same", 1_000)).finalized.size)
	}

	@Test
	fun `a seekbar-less title missing past the blink window is a new Short`() {
		val tracker = tracker()
		tracker.observe(unmeasured("A", "@same", 0))
		val split = tracker.observe(
			unmeasured(null, "@same", ForegroundShortTracker.TITLE_BLINK_WINDOW_MS + 1_000),
		)
		assertEquals(1, split.finalized.size)
	}

	@Test
	fun `repeated missing-title observations cannot renew the blink window`() {
		val tracker = tracker()
		tracker.observe(unmeasured("A", "@same", 0))

		val finalized = mutableListOf<SessionSnapshot>()
		for (at in 1_000L..9_000L step 1_000L) {
			finalized += tracker.observe(unmeasured(null, "@same", at)).finalized
		}

		assertEquals(
			"each observation renewed the nominal 8-second blink into an unbounded carry",
			1,
			finalized.size,
		)
		assertEquals("A", finalized.single().title)
		assertEquals(null, tracker.snapshot()?.title)
	}

	private fun unmeasured(
		title: String?,
		handle: String,
		at: Long,
		epoch: Long = 3,
	) = ForegroundShortTracker.UnmeasuredObservation(
		title = title,
		ownerHandle = handle,
		observedAtMillis = at,
		sourceEpoch = epoch,
		playing = true,
	)

	private fun observation(
		title: String?,
		handle: String,
		position: Long,
		total: Long,
		at: Long,
		epoch: Long = 3,
	) = ForegroundShortTracker.OrganicObservation(
		title = title,
		ownerHandle = handle,
		currentSeconds = position,
		totalSeconds = total,
		observedAtMillis = at,
		sourceEpoch = epoch,
	)
}
