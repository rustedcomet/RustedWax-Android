package com.rustedwax.app.replay.reference.current

import com.rustedwax.app.detect.EvidenceCoordinator
import com.rustedwax.app.detect.NativeShortParser
import com.rustedwax.app.detect.NativeShortsObserver
import com.rustedwax.app.detect.NativeSourceSwitches
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.core.SourceSessionId
import com.rustedwax.core.TrackIdentity
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Now shows while a listen's MediaSession is gone but its continuation is not.
 *
 * Chromium tears its session down about a minute after a pause, so between that
 * teardown and the continuation deadline RustedWax intends to take the listen
 * back and used to show nothing at all. These tests pin the row's presence, its
 * frozen measurement, and — just as load-bearing — every case where it must
 * *not* appear.
 */
class PendingContinuationNowVisibilityTest {

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
		fun row(): SessionSnapshot = now().single()
		fun stop() = probe.stop(finalizeTracks = false)

		/**
		 * One foreground-Shorts observation, through the production evidence event.
		 *
		 * The same `EvidenceCoordinator.Event` the accessibility service publishes
		 * in production, handed to the probe directly because `NativeShortsObserver`
		 * routes through `ProbeHolder`, which holds the shipping probe rather than
		 * this mirror. Everything downstream — the source adapter, the tracker, the
		 * snapshot — is production code.
		 */
		fun observeShort(event: NativeShortsObserver.Event) {
			probe.acceptEvidenceEventForHarness(
				EvidenceCoordinator.Event.NativeShortObserved(
					SourceSessionId(
						YouTubeProbe.YOUTUBE_PACKAGE,
						NativeSourceSwitches.epochFor(YouTubeProbe.YOUTUBE_PACKAGE),
					),
					event,
				),
			)
			stage.time.drain()
		}
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

	/** The same stage, under the native package, where an exact id is required. */
	private fun nativeHarness(): Harness {
		resetSharedState()
		TrackProgressCarry.clear()
		UrlWatcherService.enabled = false
		val harness = Harness(YouTubeProbe.YOUTUBE_PACKAGE)
		VirtualSystem.use(harness.stage.time)
		harness.begin()
		return harness
	}

	/** One complete foreground-Shorts player reading. */
	private fun shortObserved(
		title: String,
		ownerHandle: String,
		currentSeconds: Long,
		totalSeconds: Long = 30,
	) = NativeShortsObserver.Event.Parsed(
		result = NativeShortParser.Result.Organic(
			title = title,
			ownerHandle = ownerHandle,
			currentSeconds = currentSeconds,
			totalSeconds = totalSeconds,
		),
		observedAtMillis = VirtualSystem.currentTimeMillis(),
	)

	/**
	 * Another source parks a listen, which prunes every expired continuation.
	 *
	 * The production path — `TrackProgressCarry.remember` prunes on the way in —
	 * reached from a package this probe never watches, so what it collects is the
	 * continuation under test and nothing this probe did.
	 */
	private fun rememberUnrelatedListen() {
		val now = VirtualSystem.currentTimeMillis()
		TrackProgressCarry.remember(
			packageName = "com.example.other",
			trackIdentity = TrackIdentity(
				title = "Someone Else",
				artist = "Another Band",
				album = null,
				durationMs = 240_000,
			),
			progress = TrackProgressCarry.Progress(
				playedMs = 1_000,
				trackStartedAtEpochSec = now / 1000,
				fastestSpeedSeen = 1.0,
				atMillis = now,
			),
		)
	}

	private fun songOne(h: Harness): SessionSnapshot? =
		h.now().firstOrNull { it.title == "Song One" }

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
	fun `a playing listen is shown as playing`() {
		val h = harness()
		h.playToNinetySeconds()

		val row = h.row()
		assertTrue("a playing listen is not marked playing", row.isPlaying)
		assertFalse("a live session is not awaiting anything", row.awaitingContinuation)
		assertEquals("Song One", row.title)
		h.stop()
	}

	@Test
	fun `a paused listen keeps its row and stops claiming to play`() {
		val h = harness()
		h.playToNinetySeconds()
		h.perform(ParityStep.Transport(PlaybackState.STATE_PAUSED, 90_000))

		val row = h.row()
		assertFalse("a paused listen still says it is playing", row.isPlaying)
		assertFalse(
			"a paused listen with a live session is not waiting on a continuation",
			row.awaitingContinuation,
		)
		h.stop()
	}

	@Test
	fun `the row survives the session teardown and says it is waiting`() {
		val h = harness()
		h.playToNinetySeconds()
		val playedWhilePaused = run {
			h.perform(ParityStep.Transport(PlaybackState.STATE_PAUSED, 90_000))
			h.row().playedMs
		}

		h.perform(ParityStep.DestroySession, ParityStep.RemoveSession)

		val row = h.row()
		assertTrue("the listen vanished from Now when its session went", row.awaitingContinuation)
		assertFalse(row.isPlaying)
		assertEquals("the waiting row lost its title", "Song One", row.title)
		assertEquals("the waiting row lost its artist", "Band One", row.artist)
		assertEquals("the waiting row lost its source", brave, row.packageName)
		assertEquals("the waiting row lost its length", 240_000L, row.durationMs)
		assertEquals("the waiting row lost its measurement", playedWhilePaused, row.playedMs)
		h.stop()
	}

	@Test
	fun `a waiting row does not gain play time while nobody is watching`() {
		val h = harness()
		h.playToNinetySeconds()
		h.pauseAndLoseTheSession()

		val atTeardown = h.row().playedMs
		val percentAtTeardown = h.row().percentPlayed
		h.perform(ParityStep.Advance(30_000))

		assertEquals("a waiting row counted time no source reported", atTeardown, h.row().playedMs)
		assertEquals(percentAtTeardown, h.row().percentPlayed)
		h.stop()
	}

	@Test
	fun `the same item resuming returns to one playing row`() {
		val h = harness()
		h.playToNinetySeconds()
		h.pauseAndLoseTheSession()
		val carried = h.row().playedMs

		h.perform(
			ParityStep.ReplaceSession,
			ParityStep.Metadata("Song One", artist = "Band One", durationMs = 240_000),
			ParityStep.Transport(PlaybackState.STATE_PLAYING, 90_000),
		)

		assertEquals("resuming produced a second Now row", 1, h.now().size)
		val row = h.row()
		assertTrue("the resumed row is not playing", row.isPlaying)
		assertFalse("the resumed row is still marked as waiting", row.awaitingContinuation)
		assertTrue(
			"the resumed listen did not keep its measured progress: ${row.playedMs} < $carried",
			row.playedMs >= carried,
		)
		h.stop()
	}

	@Test
	fun `a different item replaces the waiting row and inherits nothing`() {
		val h = harness()
		h.playToNinetySeconds()
		h.pauseAndLoseTheSession()
		val carried = h.row().playedMs

		h.perform(
			ParityStep.ReplaceSession,
			ParityStep.Metadata("Song Two", artist = "Band Two", durationMs = 240_000),
			ParityStep.Transport(PlaybackState.STATE_PLAYING, 0),
			ParityStep.Advance(5_000),
		)

		assertEquals("the abandoned listen is still presented as Now", 1, h.now().size)
		val row = h.row()
		assertEquals("Song Two", row.title)
		assertFalse(row.awaitingContinuation)
		assertTrue(
			"progress leaked into a different item: ${row.playedMs} against a carried $carried",
			row.playedMs < carried,
		)
		assertNull(
			"the waiting row is still on Now beside the new one",
			h.now().firstOrNull { it.title == "Song One" },
		)
		h.stop()
	}

	@Test
	fun `an expired continuation leaves Now`() {
		val h = harness()
		h.playToNinetySeconds()
		h.pauseAndLoseTheSession()
		assertNotNull("nothing was waiting to expire", h.now().firstOrNull())

		// Past the ordinary replacement window an exact-id-less browser listen gets.
		h.perform(ParityStep.Advance(TrackProgressCarry.TTL_MS + 5_000))

		assertEquals("an expired continuation is still on Now", emptyList<SessionSnapshot>(), h.now())
		h.stop()
	}

	/**
	 * Two abandoned listens in one app do not stack up on Now.
	 *
	 * Both are still owed a finalization and both keep their carry entry and
	 * timer; what the second one ends is the first one's claim to be what this
	 * app is currently about.
	 */
	@Test
	fun `only the most recent waiting listen in an app is shown`() {
		val h = harness()
		h.playToNinetySeconds()
		h.pauseAndLoseTheSession()

		h.perform(
			ParityStep.ReplaceSession,
			ParityStep.Metadata("Song Two", artist = "Band Two", durationMs = 240_000),
			ParityStep.Transport(PlaybackState.STATE_PLAYING, 0),
			ParityStep.Advance(40_000),
			ParityStep.Transport(PlaybackState.STATE_PAUSED, 40_000),
			ParityStep.DestroySession,
			ParityStep.RemoveSession,
		)

		assertEquals("two waiting rows piled up for one app", 1, h.now().size)
		val row = h.row()
		assertEquals("Song Two", row.title)
		assertTrue(row.awaitingContinuation)
		h.stop()
	}

	/**
	 * Supersession is permanent, whatever happens to the item that caused it.
	 *
	 * The newer item need not leave a continuation of its own behind — it can
	 * simply end — and when it does there is again no live session in the app.
	 * The older listen's carry may still be perfectly valid at that moment, and
	 * it must still not walk back onto Now: nobody resumed it.
	 */
	@Test
	fun `a superseded listen does not reappear when the newer one ends`() {
		val h = harness()
		h.playToNinetySeconds()
		h.pauseAndLoseTheSession()
		assertTrue("nothing was waiting to begin with", h.row().awaitingContinuation)

		// Song Two takes over, runs past its own length, and its session goes.
		h.perform(
			ParityStep.ReplaceSession,
			ParityStep.Metadata("Song Two", artist = "Band Two", durationMs = 60_000),
			ParityStep.Transport(PlaybackState.STATE_PLAYING, 0),
			ParityStep.Advance(70_000),
		)
		assertEquals("the superseding item is not the only Now row", 1, h.now().size)
		assertEquals("Song Two", h.row().title)

		h.perform(ParityStep.DestroySession, ParityStep.RemoveSession, ParityStep.Advance(2_000))

		assertNull(
			"the superseded listen came back to Now without anybody resuming it",
			h.now().firstOrNull { it.title == "Song One" },
		)
		h.stop()
	}

	/**
	 * The superseded listen stays gone once the newer one's own wait runs out.
	 *
	 * This is the whole question asked out loud. The first listen is removed from
	 * the row set while its own window is still open — proved by the test above,
	 * which sees only the newer row at a point where two waiting entries would
	 * otherwise both be shown — and removal is not a hiding rule that a later
	 * empty screen can undo.
	 */
	@Test
	fun `a superseded listen never returns once the newer one expires`() {
		val h = harness()
		h.playToNinetySeconds()
		h.pauseAndLoseTheSession()
		val carried = h.row().playedMs

		h.perform(
			ParityStep.ReplaceSession,
			ParityStep.Metadata("Song Two", artist = "Band Two", durationMs = 240_000),
			ParityStep.Transport(PlaybackState.STATE_PLAYING, 0),
			ParityStep.Advance(45_000),
		)
		assertNull("Song One is still shown beside a live Song Two", songOne(h))
		assertTrue(
			"Song Two inherited Song One's play time: ${h.row().playedMs} against $carried",
			h.row().playedMs < carried,
		)

		// Song Two is paused and loses its session too, so nothing is live.
		h.perform(
			ParityStep.Transport(PlaybackState.STATE_PAUSED, 45_000),
			ParityStep.DestroySession,
			ParityStep.RemoveSession,
		)
		assertEquals("two waiting rows", 1, h.now().size)
		assertEquals("Song Two", h.row().title)

		// Song Two's wait runs out, and then some. Now empties and stays empty.
		h.perform(ParityStep.Advance(TrackProgressCarry.TTL_MS + 5_000))
		assertEquals(emptyList<SessionSnapshot>(), h.now())
		h.perform(ParityStep.Advance(TrackProgressCarry.TTL_MS))
		assertNull("the superseded listen resurrected itself", songOne(h))
		assertEquals(emptyList<SessionSnapshot>(), h.now())
		h.stop()
	}

	/**
	 * Being superseded is not a ban. The one thing that may bring the older item
	 * back is the only thing that should: it actually plays again.
	 */
	@Test
	fun `a superseded listen may return when it genuinely resumes`() {
		val h = harness()
		h.playToNinetySeconds()
		h.pauseAndLoseTheSession()

		h.perform(
			ParityStep.ReplaceSession,
			ParityStep.Metadata("Song Two", artist = "Band Two", durationMs = 240_000),
			ParityStep.Transport(PlaybackState.STATE_PLAYING, 0),
			ParityStep.Advance(10_000),
		)
		assertNull("Song One was not superseded", songOne(h))

		// Song One is opened again and plays.
        h.perform(
			ParityStep.ReplaceSession,
			ParityStep.Metadata("Song One", artist = "Band One", durationMs = 240_000),
			ParityStep.Transport(PlaybackState.STATE_PLAYING, 90_000),
		)

		assertEquals("resuming produced more than one row", 1, h.now().size)
		val row = h.row()
		assertEquals("Song One", row.title)
		assertTrue("the returned listen is not live", row.isPlaying)
		assertFalse("the returned listen still reads as waiting", row.awaitingContinuation)
		h.stop()
	}

	/**
	 * The one-way rule cannot lean on the newer listen leaving a continuation.
	 *
	 * A browser always opens one, so for Brave the older row is collected the
	 * moment the newer listen's own wait begins. A native source does not: when
	 * it cannot name the exact item it is playing, `PlaybackReducer` finalizes
	 * and declines the continuation outright, and nothing in that path would
	 * collect the older row. Supersession has to survive that, or the older
	 * listen reappears the moment the newer one's session goes.
	 */
	@Test
	fun `supersession holds when the newer listen leaves no continuation`() {
		resetSharedState()
		TrackProgressCarry.clear()
		UrlWatcherService.enabled = false
		val h = Harness(YouTubeProbe.YOUTUBE_PACKAGE)
		VirtualSystem.use(h.stage.time)
		h.begin()

		// An exactly identified native listen, paused, and its session goes.
		h.perform(
			ParityStep.Metadata(
				"Song One",
				artist = "Band One",
				durationMs = 240_000,
				mediaId = "aaaaaaaaaaa",
			),
			ParityStep.Transport(PlaybackState.STATE_PLAYING, 0),
			ParityStep.Advance(90_000),
			ParityStep.Transport(PlaybackState.STATE_PAUSED, 90_000),
			ParityStep.DestroySession,
			ParityStep.RemoveSession,
		)
		assertTrue("the native listen never became a waiting row", h.row().awaitingContinuation)

		// A newer native listen with no exact id of its own: it can be shown, and
		// it can never be carried, so its teardown opens nothing.
		h.perform(
			ParityStep.ReplaceSession,
			ParityStep.Metadata("Song Two", artist = "Band Two", durationMs = 240_000),
			ParityStep.Transport(PlaybackState.STATE_PLAYING, 0),
			ParityStep.Advance(20_000),
		)
		assertEquals("the newer listen did not take over Now", 1, h.now().size)
		assertEquals("Song Two", h.row().title)

		h.perform(ParityStep.DestroySession, ParityStep.RemoveSession, ParityStep.Advance(2_000))

		assertNull(
			"the superseded listen reappeared once the uncarryable newer one went",
			h.now().firstOrNull { it.title == "Song One" },
		)
		h.stop()
	}

	/**
	 * The row's life is the continuation's life, not a guess either side of it.
	 *
	 * The harness cannot give a browser listen an exact id — [BrowserYouTubeAdapter]
	 * publishes `sourceItemId = null` by construction, because a browser's exact id
	 * comes from the address bar and this replay disables that watcher. So the window
	 * under test here is the ordinary one, and what is pinned is the boundary: present
	 * while the continuation is owed, gone once it is not.
	 */
	@Test
	fun `the row lasts exactly as long as the continuation does`() {
		val h = harness()
		h.playToNinetySeconds()
		h.pauseAndLoseTheSession()

		h.perform(ParityStep.Advance(TrackProgressCarry.TTL_MS - 5_000))
		assertTrue(
			"the row was dropped before its continuation was given up on",
			h.row().awaitingContinuation,
		)

		h.perform(ParityStep.Advance(10_000))
		assertEquals(
			"the row outlived the continuation it was showing",
			emptyList<SessionSnapshot>(),
			h.now(),
		)
		h.stop()
	}

	/**
	 * An older listen's timer may collect its own row and nobody else's.
	 *
	 * Two viewings of the *same* item share one carry key and one row key, so the
	 * first viewing's delayed callback comes due while the second one's row is the
	 * one being shown. The first is owed its finalization — it gets it here, from
	 * the carry it was displaced into — but the row it takes with it must be the
	 * row it left, not the one a later listen put there.
	 */
	@Test
	fun `an older listen's expiry does not collect a newer listen's waiting row`() {
		val h = harness()
		h.playToNinetySeconds()
		h.pauseAndLoseTheSession()
		val firstWait = h.row().playedMs

		// The same item opened again, from the beginning: a separate viewing,
		// which is why its first position refuses the first viewing's carry
		// instead of continuing it.
		h.perform(
			ParityStep.ReplaceSession,
			ParityStep.Transport(PlaybackState.STATE_PLAYING, 0),
			ParityStep.Metadata("Song One", artist = "Band One", durationMs = 240_000),
			ParityStep.Advance(20_000),
			ParityStep.Transport(PlaybackState.STATE_PAUSED, 20_000),
			ParityStep.DestroySession,
			ParityStep.RemoveSession,
		)
		val secondWait = h.row().playedMs
		assertTrue("the second viewing never became a waiting row", h.row().awaitingContinuation)
		assertTrue(
			"the row shown is the first viewing's, not the second's: " +
				"$secondWait against $firstWait",
			secondWait < firstWait,
		)

		// The first viewing's wait runs out while the second one's is still open.
		h.perform(ParityStep.Advance(45_000))

		assertEquals(
			"the older listen's timer emptied Now while a newer listen was waiting",
			1,
			h.now().size,
		)
		val row = h.row()
		assertTrue("the surviving row stopped saying it is waiting", row.awaitingContinuation)
		assertEquals(
			"the surviving row is not the newer listen's",
			secondWait,
			row.playedMs,
		)
		h.stop()
	}

	/**
	 * A foreground Short is a presented listen like any other.
	 *
	 * Native YouTube publishes Shorts through the accessibility route rather than
	 * a MediaSession, so a waiting watch-screen row and an active Short both
	 * belong to one package while only one of them is what the app is doing. The
	 * Short answers the question the waiting row exists to ask, and the answer
	 * does not expire when the Short ends.
	 *
	 * Presentation only: nothing here changes how a Short is measured or
	 * finalized, and the watch listen keeps its carry, its timer and its
	 * finalization exactly as before.
	 */
	@Test
	fun `a foreground Short supersedes an older waiting native watch row`() {
		val h = nativeHarness()
		h.perform(
			ParityStep.Metadata(
				"Song One",
				artist = "Band One",
				durationMs = 240_000,
				mediaId = "aaaaaaaaaaa",
			),
			ParityStep.Transport(PlaybackState.STATE_PLAYING, 0),
			ParityStep.Advance(90_000),
			ParityStep.Transport(PlaybackState.STATE_PAUSED, 90_000),
			ParityStep.DestroySession,
			ParityStep.RemoveSession,
		)
		assertTrue("the native watch listen never became a waiting row", h.row().awaitingContinuation)

		h.observeShort(NativeShortsObserver.Event.Connected)
		h.observeShort(shortObserved("Short One", "@channel", currentSeconds = 2))

		assertEquals(
			"the waiting watch row is still shown beside the Short: " +
				h.now().map { it.title },
			1,
			h.now().size,
		)
		val short = h.row()
		assertEquals("Short One", short.title)
		assertFalse("the Short is presented as a waiting row", short.awaitingContinuation)
		assertNull("the superseded watch listen is still on Now", songOne(h))

		// The Short goes: nothing is presented, and the older row may not return.
		h.observeShort(NativeShortsObserver.Event.Disconnected("accessibility service went"))
		h.perform(ParityStep.Advance(2_000))

		assertNull(
			"the superseded watch listen came back once the Short ended",
			songOne(h),
		)
		h.stop()
	}

	/**
	 * A row outlives the continuation it was showing only until its own timer.
	 *
	 * Any source's `remember` prunes every expired continuation, this one's
	 * included, and it can reach it before that continuation's own poll does.
	 * The poll then finds nothing to collect and nothing pending — and used to
	 * return on that, leaving Now saying a listen was waiting to resume when
	 * nothing was waiting for anything.
	 */
	@Test
	fun `a row whose continuation was pruned away leaves Now`() {
		val h = harness()
		h.playToNinetySeconds()
		h.pauseAndLoseTheSession()
		assertTrue("nothing was waiting to go stale", h.row().awaitingContinuation)

		// Past the deadline but before this continuation's own poll, another
		// source parks a listen — and prunes the expired one on its way in.
		h.perform(ParityStep.Advance(TrackProgressCarry.TTL_MS + 100))
		rememberUnrelatedListen()
		assertTrue(
			"the continuation under test was not pruned away",
			h.now().none { !it.awaitingContinuation },
		)

		h.perform(ParityStep.Advance(1_000))

		assertEquals(
			"a row stayed on Now with no continuation behind it",
			emptyList<SessionSnapshot>(),
			h.now(),
		)
		h.stop()
	}

	/**
	 * The stale-row cleanup above is owned, like every other removal here.
	 *
	 * The pruned continuation's poll comes due after a newer listen has taken the
	 * row key for itself. That newer listen is still waiting, and a callback
	 * belonging to a continuation that no longer exists may not speak for it.
	 */
	@Test
	fun `a pruned continuation's timer leaves a newer waiting row alone`() {
		val h = harness()
		h.playToNinetySeconds()
		h.pauseAndLoseTheSession()

		h.perform(ParityStep.Advance(TrackProgressCarry.TTL_MS + 100))
		rememberUnrelatedListen()

		// The same item plays again and leaves a continuation of its own, so the
		// row key is now the second viewing's.
		h.perform(
			ParityStep.ReplaceSession,
			ParityStep.Transport(PlaybackState.STATE_PLAYING, 0),
			ParityStep.Metadata("Song One", artist = "Band One", durationMs = 240_000),
			ParityStep.Advance(20_000),
			ParityStep.Transport(PlaybackState.STATE_PAUSED, 20_000),
			ParityStep.DestroySession,
			ParityStep.RemoveSession,
		)
		val secondWait = h.row().playedMs

		h.perform(ParityStep.Advance(1_000))

		assertEquals("the newer listen's row was collected by a dead timer", 1, h.now().size)
		assertTrue(h.row().awaitingContinuation)
		assertEquals(secondWait, h.row().playedMs)
		h.stop()
	}
}
