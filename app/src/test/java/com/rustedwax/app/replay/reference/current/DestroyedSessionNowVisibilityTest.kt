package com.rustedwax.app.replay.reference.current

import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.replay.reference.phase01.ParityStage
import com.rustedwax.app.replay.reference.phase01.ParityStep
import com.rustedwax.app.replay.reference.phase01.PlaybackState
import com.rustedwax.app.replay.reference.phase01.ProbeControl
import com.rustedwax.app.replay.reference.phase01.SystemClock
import com.rustedwax.app.replay.reference.phase01.UrlWatcherService
import com.rustedwax.app.replay.reference.phase01.VirtualSystem
import com.rustedwax.app.replay.reference.phase01.VirtualTime
import com.rustedwax.app.replay.reference.phase01.resetSharedState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Now shows once a listen's MediaSession is gone.
 *
 * A source whose session has been destroyed is no longer a playback session this
 * app can present, so it leaves Now at once — verified on a physical Galaxy A36,
 * where a closed YouTube/YouTube Music/Brave loses its session while a merely
 * backgrounded one keeps it. The continuation behind it is untouched: these
 * tests pin that the credit is still there to be taken back by the same item,
 * and still refused to a different one.
 */
class DestroyedSessionNowVisibilityTest {

	private val brave = "com.brave.browser"

	private class Harness(packageName: String) {
		val stage = ParityStage(packageName)
		val probe = SessionProbe(
			stage.context,
			acceptsPackage = { it == packageName },
		)
		private val control = object : ProbeControl {
			override fun start() = probe.start()
			override fun stop(finalizeTracks: Boolean) = probe.stop(finalizeTracks)
		}

		fun begin() = stage.begin(control)
		fun perform(vararg steps: ParityStep) = steps.forEach { stage.perform(it, control) }
		fun now(): List<SessionSnapshot> = probe.sessions.value
		fun stop() = probe.stop(finalizeTracks = false)
	}

	private fun harness(): Harness {
		resetSharedState()
		TrackProgressCarry.clear()
		UrlWatcherService.enabled = false
		val harness = Harness(brave)
		// One clock for the timer and the carry's deadline, so a continuation
		// expires when this test says it does rather than when the wall clock
		// happens to agree.
		VirtualSystem.use(harness.stage.time)
		harness.begin()
		return harness
	}

	@After
	fun tearDown() {
		VirtualSystem.useRealTime()
		TrackProgressCarry.clear()
		SystemClock.current = VirtualTime()
	}

	/** The item, played to 90s of 240s — real progress, well under any threshold. */
	private fun Harness.playToNinetySeconds(mediaId: String? = null) = perform(
		ParityStep.Metadata("Song One", artist = "Band One", durationMs = 240_000, mediaId = mediaId),
		ParityStep.Transport(PlaybackState.STATE_PLAYING, 0),
		ParityStep.Advance(90_000),
	)

	/** Chromium's shape: pause, then the session goes. */
	private fun Harness.pauseAndLoseTheSession() = perform(
		ParityStep.Transport(PlaybackState.STATE_PAUSED, 90_000),
		ParityStep.DestroySession,
		ParityStep.RemoveSession,
	)

	@Test
	fun `a destroyed session leaves Now at once`() {
		val h = harness()
		h.playToNinetySeconds()
		h.perform(ParityStep.Transport(PlaybackState.STATE_PAUSED, 90_000))
		assertEquals("the paused listen was not on Now to begin with", 1, h.now().size)

		h.perform(ParityStep.DestroySession, ParityStep.RemoveSession)

		assertEquals(
			"a listen whose session is gone is still presented as playback",
			emptyList<SessionSnapshot>(),
			h.now(),
		)
		h.stop()
	}

	@Test
	fun `a live paused session keeps its row`() {
		val h = harness()
		h.playToNinetySeconds()

		h.perform(ParityStep.Transport(PlaybackState.STATE_PAUSED, 90_000))

		val row = h.now().single()
		assertFalse("a paused listen is still reported as playing", row.isPlaying)
		assertEquals("the paused listen lost its title", "Song One", row.title)
		h.stop()
	}

	@Test
	fun `the same item resuming still takes its credit back`() {
		val h = harness()
		h.playToNinetySeconds()
		h.perform(ParityStep.Transport(PlaybackState.STATE_PAUSED, 90_000))
		val carried = h.now().single().playedMs
		h.perform(ParityStep.DestroySession, ParityStep.RemoveSession)

		h.perform(
			ParityStep.ReplaceSession,
			ParityStep.Metadata("Song One", artist = "Band One", durationMs = 240_000),
			ParityStep.Transport(PlaybackState.STATE_PLAYING, 90_000),
		)

		assertEquals("resuming produced a second Now row", 1, h.now().size)
		val row = h.now().single()
		assertTrue("the resumed row is not playing", row.isPlaying)
		assertTrue(
			"the resumed listen did not keep its measured progress: ${row.playedMs} < $carried",
			row.playedMs >= carried,
		)
		h.stop()
	}

	@Test
	fun `a different item inherits nothing from the destroyed session`() {
		val h = harness()
		h.playToNinetySeconds()
		h.perform(ParityStep.Transport(PlaybackState.STATE_PAUSED, 90_000))
		val carried = h.now().single().playedMs
		h.perform(ParityStep.DestroySession, ParityStep.RemoveSession)

		h.perform(
			ParityStep.ReplaceSession,
			ParityStep.Metadata("Song Two", artist = "Band Two", durationMs = 240_000),
			ParityStep.Transport(PlaybackState.STATE_PLAYING, 0),
			ParityStep.Advance(5_000),
		)

		val row = h.now().single()
		assertEquals("Song Two", row.title)
		assertTrue(
			"progress leaked into a different item: ${row.playedMs} against a carried $carried",
			row.playedMs < carried,
		)
		assertNull(
			"the destroyed session's listen walked back onto Now",
			h.now().firstOrNull { it.title == "Song One" },
		)
		h.stop()
	}

	@Test
	fun `an expired continuation leaves Now empty`() {
		val h = harness()
		h.playToNinetySeconds()
		h.perform(
			ParityStep.Transport(PlaybackState.STATE_PAUSED, 90_000),
			ParityStep.DestroySession,
			ParityStep.RemoveSession,
		)

		h.perform(ParityStep.Advance(TrackProgressCarry.TTL_MS + 5_000))

		assertEquals("an expired continuation put something on Now", emptyList<SessionSnapshot>(), h.now())
		h.stop()
	}

	/**
	 * Android orders the destroyed callback and the active-session-list callback
	 * independently, so the row has to go on whichever arrives first.
	 *
	 * Driving only `onSessionDestroyed` is the shape the combined
	 * DestroySession + RemoveSession step used to mask: with the list callback
	 * still pending the binding is alive in `watches`, un-finalized, and would
	 * otherwise keep publishing a session that no longer exists.
	 */
	@Test
	fun `a destroyed session leaves Now before the active-session list catches up`() {
		val h = harness()
		h.playToNinetySeconds()
		h.perform(ParityStep.Transport(PlaybackState.STATE_PAUSED, 90_000))
		assertEquals("the paused listen was not on Now to begin with", 1, h.now().size)

		h.perform(ParityStep.DestroySession)

		assertEquals(
			"a destroyed session is still presented while the list callback is pending",
			emptyList<SessionSnapshot>(),
			h.now(),
		)
		h.stop()
	}

	/**
	 * Removing the row on the destroyed callback is presentation only: the listen
	 * behind it keeps its carry and a matching replacement still reclaims it.
	 */
	@Test
	fun `credit survives a destroyed callback the list has not caught up with`() {
		val h = harness()
		h.playToNinetySeconds()
		h.perform(ParityStep.Transport(PlaybackState.STATE_PAUSED, 90_000))
		val carried = h.now().single().playedMs

		h.perform(ParityStep.DestroySession)
		assertEquals("the destroyed session is still on Now", emptyList<SessionSnapshot>(), h.now())

		h.perform(
			ParityStep.ReplaceSession,
			ParityStep.Metadata("Song One", artist = "Band One", durationMs = 240_000),
			ParityStep.Transport(PlaybackState.STATE_PLAYING, 90_000),
		)

		val row = h.now().single()
		assertTrue("the resumed row is not playing", row.isPlaying)
		assertTrue(
			"the listen lost its measured progress across the destroyed callback: " +
				"${row.playedMs} < $carried",
			row.playedMs >= carried,
		)
		h.stop()
	}
}
