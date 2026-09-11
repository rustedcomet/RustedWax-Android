package com.rustedwax.app.replay

import com.rustedwax.app.detect.StoppedInterruption
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.replay.ReplayHarness.RefusalKind
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Screen off, and then back on — one viewing, replayed end to end.
 *
	 * Synthetic regression: a 252 s item plays to 71 s before screen-off. YouTube
	 * publishes `STATE_STOPPED` on screen-off exactly as it
 * publishes it when a person is finished with a video, the exact-ID-less
 * replacement grace ran out ten seconds later, and the listen was scored at
 * 28 % and refused. Four minutes on, the same video came back at 110 s as a
 * *new* listen with `played = 0`, and 97 s of remaining runtime could no longer
 * reach the 60 % threshold from there. One viewing of one video, two
 * sub-threshold refusals, nothing scrobbled.
 *
 * What these scenarios hold is the pair of properties that failure violated: an
 * interruption nobody asked for does not end a listen, and the interval nobody
 * watched is never credited to one.
 */
class InterruptedListenReplayTest : ReplayScenarioTest() {

	private val videoId = "9bZkp7q19f0"
	private val title = "PSY - GANGNAM STYLE(강남스타일) M/V"
	private val artist = "officialpsy"
	private val durationMs = 252_000L

	private fun harness(): ReplayHarness {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(
			VideoFacts(
				videoId = videoId,
				title = title,
				author = artist,
				ownerHandle = null,
				lengthSeconds = durationMs / 1000,
				category = "Music",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
		// The native app publishes no exact id of its own, so the id comes from
		// watch history and has to still be there when the listen finalizes.
		harness.env.watchHistory.hasSession = true
		val resolution = {
			VideoResolutionAttempt(
				resolution = VideoResolution(
					videoId = videoId,
					source = "watch history",
					title = title,
					channel = artist,
					lengthSeconds = durationMs / 1000,
					uniquelyResolved = true,
					historyVerified = true,
				),
			)
		}
		harness.env.watchHistory.evidence = { resolution() }
		harness.env.watchHistory.revalidation = { resolution() }
		return harness
	}

	/**
	 * The session publishes no exact id of its own, which is what puts this
	 * transport on the stopped-replacement grace at all; the id arrives from the
	 * resolver while the video is still playing, exactly as it did on the device.
	 */
	private fun playingAt(positionMs: Long) = listOf(
		PlaybackEvent.SessionMetadata(
			title = title,
			artist = artist,
			durationMs = durationMs,
		),
		PlaybackEvent.NativeIdentityPreResolved(videoId, PlaybackTrace.HISTORY_ROUTE),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = positionMs),
	)

	/** The reset/restore pair the native YouTube transport published on lock. */
	private fun surfaceDisappearsAt(positionMs: Long) = listOf(
		PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 0),
		PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = positionMs),
	)

	// ---- A: the interruption itself -------------------------------------------

	@Test
	fun `a screen-off stop does not end the listen it interrupted`() {
		val harness = harness()

		harness.feed(
			playingAt(0) + listOf(
				PlaybackEvent.Advance(71_000),
			) + surfaceDisappearsAt(71_065) + listOf(
				// The power key. YouTube's STOPPED, and the grace that follows it.
				PlaybackEvent.Advance(10_000),
				PlaybackEvent.StoppedGraceExpired(displayInteractive = false),
			),
		)

		assertTrue(
			"the display-off stop must be recognised as an interruption",
			harness.trace.stoppedInterruptionHeld,
		)
		assertEquals("nothing may be scored for an interruption", 0, harness.finalized.size)
		assertEquals(emptyList<RefusalKind>(), harness.refusalKinds)
	}

	@Test
	fun `the same video resuming after the lock continues one listen`() {
		val harness = harness()

		harness.feed(
			playingAt(0) + listOf(
				PlaybackEvent.Advance(71_000),
			) + surfaceDisappearsAt(71_065) + listOf(
				PlaybackEvent.Advance(10_000),
				PlaybackEvent.StoppedGraceExpired(displayInteractive = false),
				// YouTube's own session goes away under the lock, which is what
				// parks the listen in the carry; the replacement appears three
				// minutes later when the screen comes back. The position it comes
				// back at is 110 s, because the video kept playing for forty
				// seconds after the lock with nobody watching.
				PlaybackEvent.ProbeDisposed(finalize = true, allowContinuation = true),
				PlaybackEvent.Advance(234_000),
				PlaybackEvent.SessionRecreatedAtPosition(110_591),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 110_591),
				PlaybackEvent.Advance(1_000),
			),
		)

		assertEquals("still nothing finalized mid-viewing", 0, harness.finalized.size)
		assertEquals(
			"the 71s earned before the lock must survive it",
			72_000L,
			harness.trace.currentPlayedMs,
		)
	}

	// ---- B: what the resumed listen is worth ----------------------------------

	@Test
	fun `a listen interrupted by the lock still scrobbles on genuine watched time`() {
		val harness = harness()

		harness.feed(
			playingAt(0) + listOf(
				PlaybackEvent.Advance(71_000),
			) + surfaceDisappearsAt(71_065) + listOf(
				PlaybackEvent.Advance(10_000),
				PlaybackEvent.StoppedGraceExpired(displayInteractive = false),
				PlaybackEvent.ProbeDisposed(finalize = true, allowContinuation = true),
				PlaybackEvent.Advance(234_000),
				PlaybackEvent.SessionRecreatedAtPosition(110_591),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 110_591),
				// Watched to the end from there: 141 s more of the 252 s item.
				PlaybackEvent.Advance(141_000),
				PlaybackEvent.Finalized("track ended"),
			),
		)

		assertEquals("one viewing, one listen", 1, harness.finalized.size)
		val listen = harness.finalized.single()
		// 71s before the lock plus 141s after it. The forty seconds that played to
		// nobody are *not* in this number, which is why it is 212 and not 252.
		assertEquals(212_000L, listen.playedMs)
		assertEquals(durationMs, listen.durationMs)
		assertEquals(
			"the eligible listen the split used to make impossible",
			listOf(videoId),
			harness.broadcasts.map { it.videoId },
		)
		assertEquals(emptyList<RefusalKind>(), harness.refusalKinds)
		harness.assertOneOutcomePerFinalization(1)
	}

	/**
	 * The failure the fix has to beat, stated as arithmetic: from a fresh listen
	 * at 110 s, the 141 s of runtime left cannot reach 60 % of 252 s, so the
	 * viewer who watched essentially the whole video got nothing. Same events,
	 * except that the grace is allowed to end the listen.
	 */
	@Test
	fun `finalizing the interruption is what used to make the video unscrobblable`() {
		val harness = harness()

		harness.feed(
			playingAt(0) + listOf(
				PlaybackEvent.Advance(71_000),
				PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 71_065),
				PlaybackEvent.Advance(10_000),
				// The screen was on: the old behaviour, on the path that keeps it.
				PlaybackEvent.StoppedGraceExpired(displayInteractive = true),
			),
		)
		harness.feed(
			playingAt(110_591) + listOf(
				PlaybackEvent.Advance(141_000),
				PlaybackEvent.Finalized("track ended"),
			),
		)

		assertFalse(harness.trace.stoppedInterruptionHeld)
		assertEquals("two listens, which is the defect", 2, harness.finalized.size)
		assertEquals(listOf(71_000L, 141_000L), harness.finalized.map { it.playedMs })
		assertEquals(
			listOf(RefusalKind.BELOW_THRESHOLD, RefusalKind.BELOW_THRESHOLD),
			harness.refusalKinds,
		)
		assertEquals(emptyList<String?>(), harness.broadcasts.map { it.videoId })
	}

	// ---- C: a stop the user actually made -------------------------------------

	@Test
	fun `a stop made with the screen on finalizes and is never resurrected`() {
		val harness = harness()

		harness.feed(
			playingAt(0) + listOf(
				PlaybackEvent.Advance(200_000),
				PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 200_000),
				PlaybackEvent.Advance(10_000),
				PlaybackEvent.StoppedGraceExpired(displayInteractive = true),
			),
		)

		assertFalse(harness.trace.stoppedInterruptionHeld)
		assertEquals(1, harness.finalized.size)
		assertEquals(200_000L, harness.finalized.single().playedMs)

		// Minutes later the same video is played again. It is a second viewing and
		// starts from nothing; the first one's time may not follow it.
		harness.feed(
			listOf(PlaybackEvent.Advance(240_000)) + playingAt(0) + listOf(
				PlaybackEvent.Advance(30_000),
			),
		)
		assertEquals(30_000L, harness.trace.currentPlayedMs)
	}

	@Test
	fun `a genuine stop with the display off still finalizes`() {
		val harness = harness()

		harness.feed(
			playingAt(0) + listOf(
				PlaybackEvent.Advance(90_000),
				PlaybackEvent.PlaybackStateChanged(
					playing = false,
					stopped = true,
					positionMs = 90_000,
				),
				PlaybackEvent.Advance(10_000),
				PlaybackEvent.StoppedGraceExpired(displayInteractive = false),
			),
		)

		assertFalse(harness.trace.stoppedInterruptionHeld)
		assertEquals(1, harness.finalized.size)
		assertEquals(90_000L, harness.finalized.single().playedMs)
	}

	@Test
	fun `a media end with the display off still finalizes`() {
		val harness = harness()

		harness.feed(
			playingAt(0) + listOf(
				PlaybackEvent.Advance(durationMs),
			) + surfaceDisappearsAt(durationMs) + listOf(
				PlaybackEvent.Advance(10_000),
				PlaybackEvent.StoppedGraceExpired(displayInteractive = false),
			),
		)

		assertFalse(harness.trace.stoppedInterruptionHeld)
		assertEquals(1, harness.finalized.size)
		assertEquals(durationMs, harness.finalized.single().playedMs)
	}

	// ---- E: what the position is not allowed to buy ---------------------------

	@Test
	fun `reopening the video far ahead of the interruption starts a new listen`() {
		val harness = harness()

		harness.feed(
			playingAt(0) + listOf(
				PlaybackEvent.Advance(71_000),
			) + surfaceDisappearsAt(71_065) + listOf(
				PlaybackEvent.Advance(10_000),
				PlaybackEvent.StoppedGraceExpired(displayInteractive = false),
				PlaybackEvent.ProbeDisposed(finalize = true, allowContinuation = true),
				// Back eighty seconds later, but reopened at 240 s. Eighty seconds
				// of wall clock cannot account for a hundred and seventy of item,
				// so this is not the continuation of that viewing.
				PlaybackEvent.Advance(80_000),
				PlaybackEvent.SessionRecreatedAtPosition(240_000),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 240_000),
				PlaybackEvent.Advance(12_000),
				PlaybackEvent.Finalized("track ended"),
			),
		)

		assertEquals(
			"the unwatched lead-in is credited to nobody",
			12_000L,
			harness.finalized.last().playedMs,
		)
		// The 71 s fragment is still held for its own owner to finalize rather
		// than having been swallowed by the reopened listen. (This harness has no
		// continuation timer, so it is asserted where it lives.)
		assertTrue(
			"the interrupted fragment must not be merged into the reopened one",
			harness.finalized.none { it.playedMs > 12_000L },
		)
	}

	// ---- F: teardown and recreation -------------------------------------------

	@Test
	fun `a session rebuilt in place during the interruption carries only what it proved`() {
		val harness = harness()

		harness.feed(
			playingAt(0) + listOf(
				PlaybackEvent.Advance(71_000),
			) + surfaceDisappearsAt(71_065) + listOf(
				PlaybackEvent.Advance(10_000),
				PlaybackEvent.StoppedGraceExpired(displayInteractive = false),
				PlaybackEvent.Advance(60_000),
				PlaybackEvent.SessionRecreatedAtPosition(75_000),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 75_000),
			),
		)

		assertEquals(
			"the measured time carries; the four seconds of position do not",
			71_000L,
			harness.trace.currentPlayedMs,
		)
		assertEquals(0, harness.finalized.size)
	}

	@Test
	fun `teardown near the interruption deadline cannot start another carry window`() {
		val harness = harness()

		harness.feed(
			playingAt(0) + listOf(
				PlaybackEvent.Advance(71_000),
			) + surfaceDisappearsAt(71_065) + listOf(
				PlaybackEvent.Advance(10_000),
				PlaybackEvent.StoppedGraceExpired(displayInteractive = false),
				// The transport leaves with twenty seconds of the original
				// interruption lifetime remaining.
				PlaybackEvent.Advance(StoppedInterruption.SCREEN_OFF_HOLD_CAP_MS - 30_000),
				PlaybackEvent.ProbeDisposed(finalize = true, allowContinuation = true),
				// One millisecond beyond the deadline that began at STOPPED.
				PlaybackEvent.Advance(20_001),
				PlaybackEvent.SessionRecreatedAtPosition(110_591),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 110_591),
				PlaybackEvent.Advance(1_000),
			),
		)

		assertEquals(
			"the expired fragment must not cross the original interruption deadline",
			1_000L,
			harness.trace.currentPlayedMs,
		)
	}

	@Test
	fun `teardown near the interruption deadline preserves progress just inside it`() {
		val harness = harness()

		harness.feed(
			playingAt(0) + listOf(
				PlaybackEvent.Advance(71_000),
			) + surfaceDisappearsAt(71_065) + listOf(
				PlaybackEvent.Advance(10_000),
				PlaybackEvent.StoppedGraceExpired(displayInteractive = false),
				PlaybackEvent.Advance(StoppedInterruption.SCREEN_OFF_HOLD_CAP_MS - 30_000),
				PlaybackEvent.ProbeDisposed(finalize = true, allowContinuation = true),
				// One millisecond before the original STOPPED deadline.
				PlaybackEvent.Advance(19_999),
				PlaybackEvent.SessionRecreatedAtPosition(110_591),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 110_591),
			),
		)

		assertEquals(71_000L, harness.trace.currentPlayedMs)
		assertEquals(0, harness.finalized.size)
	}

	/** The hold is bounded by the same budget the continuation it protects has. */
	@Test
	fun `an interruption nobody returns from still ends`() {
		assertFalse(
			StoppedInterruption.holdsListenOpen(
				displayInteractive = false,
				stoppedForMs = StoppedInterruption.SCREEN_OFF_HOLD_CAP_MS,
				surfaceDisappearanceConfirmed = true,
				stoppedAtMs = 71_065,
				durationMs = durationMs,
			),
		)

		val harness = harness()
		harness.feed(
			playingAt(0) + listOf(
				PlaybackEvent.Advance(71_000),
			) + surfaceDisappearsAt(71_065) + listOf(
				PlaybackEvent.Advance(StoppedInterruption.SCREEN_OFF_HOLD_CAP_MS),
				PlaybackEvent.StoppedGraceExpired(displayInteractive = false),
			),
		)

		assertFalse(harness.trace.stoppedInterruptionHeld)
		assertEquals(1, harness.finalized.size)
		assertEquals(71_000L, harness.finalized.single().playedMs)
	}
}
