package com.rustedwax.app.replay.reference.current

import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.YouTubeProbe
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One expiring continuation must not take its siblings with it.
 *
 * A native package resets its observation state when its last listen ends, and
 * that reset used to run from *any* continuation's expiry tick. With two
 * continuations outstanding for one package — ordinary as soon as two videos are
 * each left mid-play — the first to expire wiped the second's carry through
 * `TrackProgressCarry.clearPackage`. The second was never finalized, so it
 * reached neither History nor Not logged and disappeared without a line in the
 * log. Reproduced end to end on a real native YouTube session before this gate
 * existed.
 *
 * The reset itself is not in question: these tests pin that it still happens,
 * once, when the package really is done.
 */
class SiblingNativeContinuationTest {

	private val youtube = YouTubeProbe.YOUTUBE_PACKAGE
	private val brave = "com.brave.browser"
	private val videoA = "AAAAAAAAAA1"
	private val videoB = "BBBBBBBBBB2"
	private val videoC = "CCCCCCCCCC3"

	private class Harness(packageName: String) {
		val stage = ParityStage(packageName)
		val finalized = mutableListOf<SessionSnapshot>()
		val tornDown = mutableListOf<String>()
		val probe = SessionProbe(stage.context, acceptsPackage = { it == packageName })
		private val control = object : ProbeControl {
			override fun start() = probe.start()
			override fun stop(finalizeTracks: Boolean) = probe.stop(finalizeTracks)
		}

		init {
			probe.onTrackFinalized = { finalized += it }
			probe.onPackageTornDown = { tornDown += it }
		}

		fun begin() = stage.begin(control)
		fun perform(vararg steps: ParityStep) = steps.forEach { stage.perform(it, control) }

		/** Video ids of every listen this probe has finalized, in order. */
		fun finalizedIds(): List<String> = finalized.mapNotNull {
			(it.identity as? YouTubeProbe.Identity.Confirmed)?.videoId
		}
	}

	private fun harness(packageName: String): Harness {
		resetSharedState()
		TrackProgressCarry.clear()
		UrlWatcherService.enabled = false
		val harness = Harness(packageName)
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

	/** Play [videoId] on the current session for [playedMs], then let it vanish. */
	private fun Harness.parkListen(videoId: String, playedMs: Long, replaceFirst: Boolean) {
		if (replaceFirst) perform(ParityStep.ReplaceSession)
		perform(
			ParityStep.Metadata(title = "Track $videoId", mediaId = videoId, durationMs = 300_000),
			ParityStep.Transport(PlaybackState.STATE_PLAYING, 0),
			ParityStep.Advance(playedMs),
			ParityStep.RemoveSession,
		)
	}

	/** Two listens park, the first expires, and the second is still owed its wait. */
	@Test
	fun expiringContinuationLeavesItsSiblingIntact() {
		val h = harness(youtube)

		h.parkListen(videoB, playedMs = 15_000, replaceFirst = false)
		h.perform(ParityStep.Advance(5_000))
		h.parkListen(videoA, playedMs = 20_000, replaceFirst = true)

		// Both are outstanding: two listens, one package, neither finalized.
		assertTrue("both continuations should be held", TrackProgressCarry.hasPackage(youtube))
		assertEquals(2, TrackProgressCarry.size())
		assertEquals(emptyList<String>(), h.finalizedIds())

		// B reaches its own deadline first.
		h.perform(ParityStep.Advance(45_000))
		assertEquals("B should have finalized on its own deadline", listOf(videoB), h.finalizedIds())

		// The regression: A is still inside its window, so its carry must survive
		// and the package must not have been torn down yet.
		assertTrue("A's carry was destroyed by B's expiry", TrackProgressCarry.hasPackage(youtube))
		assertEquals(1, TrackProgressCarry.size())
		assertEquals("package was torn down while A was still pending", emptyList<String>(), h.tornDown)

		// A then finalizes on its own deadline, reaching the finalization path
		// exactly as a lone continuation would.
		h.perform(ParityStep.Advance(45_000))
		assertEquals(listOf(videoB, videoA), h.finalizedIds())
		assertFalse("carry should be empty once both are collected", TrackProgressCarry.hasPackage(youtube))
	}

	/** The deferred reset still runs, once, when the package really is done. */
	@Test
	fun lastContinuationToExpireStillResetsThePackage() {
		val h = harness(youtube)

		h.parkListen(videoB, playedMs = 15_000, replaceFirst = false)
		h.perform(ParityStep.Advance(5_000))
		h.parkListen(videoA, playedMs = 20_000, replaceFirst = true)

		h.perform(ParityStep.Advance(45_000))
		assertEquals("reset must wait for the last listen", emptyList<String>(), h.tornDown)

		h.perform(ParityStep.Advance(45_000))
		assertEquals("the last expiry owes the package its reset", listOf(youtube), h.tornDown)
	}

	/** A lone native continuation resets the package exactly as it always did. */
	@Test
	fun loneNativeContinuationStillResetsThePackage() {
		val h = harness(youtube)

		h.parkListen(videoA, playedMs = 20_000, replaceFirst = false)
		h.perform(ParityStep.Advance(70_000))

		assertEquals(listOf(videoA), h.finalizedIds())
		assertEquals(listOf(youtube), h.tornDown)
		assertFalse(TrackProgressCarry.hasPackage(youtube))
	}

	/** A live listen in the package suppresses the reset, as it did before. */
	@Test
	fun liveListenInThePackageStillSuppressesTheReset() {
		val h = harness(youtube)

		h.parkListen(videoB, playedMs = 15_000, replaceFirst = false)
		h.perform(ParityStep.Advance(5_000))
		h.parkListen(videoA, playedMs = 20_000, replaceFirst = true)

		// A third listen is playing, and stays playing, across B's deadline.
		h.perform(
			ParityStep.ReplaceSession,
			ParityStep.Metadata(title = "Track $videoC", mediaId = videoC, durationMs = 300_000),
			ParityStep.Transport(PlaybackState.STATE_PLAYING, 0),
			ParityStep.Advance(45_000),
		)

		assertEquals(listOf(videoB), h.finalizedIds())
		assertEquals("a live listen must not trigger a package reset", emptyList<String>(), h.tornDown)
		assertTrue("A's carry must survive a live sibling too", TrackProgressCarry.hasPackage(youtube))
	}

	/**
	 * Browsers never reset a package here, and still must not lose a listen.
	 *
	 * Their teardown policy is not `DISCARD_AND_RESET`, so this path was never
	 * reachable for them. Pinned so the gate above cannot quietly change what a
	 * browser does with two outstanding continuations.
	 */
	@Test
	fun browserContinuationsAreUnaffected() {
		val h = harness(brave)

		h.parkListen(videoB, playedMs = 15_000, replaceFirst = false)
		h.perform(ParityStep.Advance(5_000))
		h.parkListen(videoA, playedMs = 20_000, replaceFirst = true)

		h.perform(ParityStep.Advance(45_000))
		h.perform(ParityStep.Advance(45_000))

		assertEquals(2, h.finalized.size)
		assertEquals("a browser package is never reset from this path", emptyList<String>(), h.tornDown)
	}
}
