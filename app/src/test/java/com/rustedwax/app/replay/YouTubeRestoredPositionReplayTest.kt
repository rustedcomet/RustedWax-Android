package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A video the source resumes where the viewer left off.
 *
	 * The native YouTube app can restore a saved position when an earlier viewing
	 * is opened again while publishing a near-zero **placeholder first**. The
	 * synthetic traces cover that placeholder followed by a materially later real
	 * position.
 *
 * The measured time was right in both — 170 s and 94 s are what was newly
 * watched, and neither listen credited the 42 s it had joined at. What was wrong
 * is where each listen recorded itself as having *begun*: the placeholder
 * arrived first, so `firstSeenPositionMs` latched at ~0 and the real position
 * could never replace it.
 *
 * Two things read that value. The outcome loses its explanation — a third video
 * the same evening, which happened to be acquired cleanly at 17 s, said
 * *"first seen 17s in — anything played before that was never published"*, while
 * these two said nothing at all. And `restoreCarriedProgress` falls back to it
 * when the transport publishes no position, so a claim could be made against a
 * position nothing had played to.
 *
 * The rule that fixes it is narrow: a forward jump too large to be playback,
 * while the listen has banked nothing, re-establishes the start. A seek *after*
 * real playback banks time first and never reaches it, so seek protection is
 * unchanged, and the measured clock is not touched either way.
 */
class YouTubeRestoredPositionReplayTest : ReplayScenarioTest() {

	private val videoId = "XPLYvi4mh_w"
	private val title = "Sizzla Kalonji - Solid As A Rock (Official Audio)"
	private val author = "Sizzla"
	private val durationMs = 215_000L

	private fun harness(): ReplayHarness =
		ReplayHarness(ReplaySource.NATIVE_YOUTUBE).also { harness ->
			harness.env.facts.put(
				VideoFacts(
					videoId = videoId,
					title = title,
					author = author,
					lengthSeconds = durationMs / 1000,
					category = "Music",
					watchPageResolved = true,
					isUnlisted = false,
				),
			)
		}

	private fun open(durationMs: Long = this.durationMs) = PlaybackEvent.SessionMetadata(
		title = title,
		artist = author,
		durationMs = durationMs,
		mediaId = videoId,
	)

	// ── A. a fresh video starting at zero is unchanged ───────────────────────

	@Test
	fun `a video that genuinely starts at zero records a zero start`() {
		val harness = harness().feed(
			open(),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(60_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 60_000),
			PlaybackEvent.Finalized("track change"),
		)

		val listen = harness.finalized.single()
		assertEquals(0L, listen.firstObservedPositionMs)
		assertEquals(60_000L, listen.playedMs)
		assertEquals(
			"nothing was skipped, so there is no lead-in to explain",
			0L,
			listen.unobservedLeadInMs,
		)
	}

	// ── B. the restored position becomes the baseline ────────────────────────

	@Test
	fun `a placeholder before the restored position does not become the start`() {
		val harness = harness().feed(
			open(),
			// A near-zero placeholder followed by the restored reading.
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 11),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 43_126),
			PlaybackEvent.Advance(170_000),
			PlaybackEvent.Finalized("track change"),
		)

		val listen = harness.finalized.single()
		assertEquals(
			"the listen began where the source resumed it, not at the placeholder",
			43_126L,
			listen.firstObservedPositionMs,
		)
	}

	@Test
	fun `a short listen that joined mid-video can explain itself`() {
		// `unobservedLeadInMs` reports the skipped stretch only while the listen has
		// not already out-played it — the same rule a carried listen relies on — so
		// this is the shape that has something to explain.
		val harness = harness().feed(
			open(),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 11),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 43_126),
			PlaybackEvent.Advance(14_000),
			PlaybackEvent.Finalized("track change"),
		)

		val listen = harness.finalized.single()
		assertEquals(43_126L, listen.firstObservedPositionMs)
		assertEquals(
			"and the outcome can now say how far in it joined",
			43_126L,
			listen.unobservedLeadInMs,
		)
	}

	@Test
	fun `the restored position is never added to the measured time`() {
		val harness = harness().feed(
			open(),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 11),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 43_126),
			PlaybackEvent.Advance(170_000),
			PlaybackEvent.Finalized("track change"),
		)

		assertEquals(
			"170s was watched; the 43s joined at was watched earlier and is not this listen's",
			170_000L,
			harness.finalized.single().playedMs,
		)
	}

	// ── C. playback after the resume is credited normally ────────────────────

	@Test
	fun `only the seconds after the resume are credited, and all of them are`() {
		val harness = harness().feed(
			open(),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 86_000),
			PlaybackEvent.Advance(30_000),
			PlaybackEvent.Finalized("track change"),
		)

		val listen = harness.finalized.single()
		assertEquals(86_000L, listen.firstObservedPositionMs)
		assertEquals("the 30s watched after re-entry", 30_000L, listen.playedMs)
		assertEquals(
			"13% of the video, not 54%",
			13,
			((listen.playedMs * 100) / durationMs).toInt(),
		)
	}

	// ── D. a real seek after real playback is untouched ──────────────────────

	@Test
	fun `a forward seek after real playback does not move the recorded start`() {
		// Seek protection unchanged: this listen has banked a minute, so the jump
		// cannot be a restoring source's placeholder and the start stays at zero.
		val harness = harness().feed(
			open(),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(60_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 60_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 150_000),
			PlaybackEvent.Advance(20_000),
			PlaybackEvent.Finalized("track change"),
		)

		val listen = harness.finalized.single()
		assertEquals(
			"a seek is not a resume; the listen still began at zero",
			0L,
			listen.firstObservedPositionMs,
		)
		assertEquals(
			"and the skipped stretch is not credited",
			80_000L,
			listen.playedMs,
		)
	}

	@Test
	fun `a small forward step is ordinary playback and never re-establishes`() {
		val harness = harness().feed(
			open(),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 500),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 5_500),
			PlaybackEvent.Advance(40_000),
			PlaybackEvent.Finalized("track change"),
		)

		assertEquals(500L, harness.finalized.single().firstObservedPositionMs)
	}

	// ── E. the same id across a new generation stays identity-safe ───────────

	@Test
	fun `a resumed re-entry of the same id still resolves to that id`() {
		val harness = harness().feed(
			open(),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(140_000),
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 140_000),
			PlaybackEvent.Finalized("stopped"),
		)
		assertEquals(listOf(videoId), harness.broadcasts.map { it.videoId })

		// Re-entered later, resumed by the source at 140s.
		harness.feed(
			open(),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 140_000),
			PlaybackEvent.Advance(60_000),
			PlaybackEvent.Finalized("track change"),
		)

		val second = harness.finalized.last()
		assertEquals(
			"the re-entry records where the source put it",
			140_000L,
			second.firstObservedPositionMs,
		)
		assertEquals(
			"and credits only what was newly watched",
			60_000L,
			second.playedMs,
		)
	}

	@Test
	fun `a credible restored position retries and consumes exact-id native carry once`() {
		val harness = harness().feed(
			open(),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(80_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 80_000),
			// The replacement constructor sees zero and correctly refuses the 80s
			// carry as contradictory. Its initial log then records that placeholder.
			PlaybackEvent.SessionRecreatedAtPosition(0),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			// Android publishes the real restored position later. Replacing the
			// placeholder retries the real TrackProgressCarry claim.
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 80_000),
			// A later ordinary position must not claim the stored fragment again.
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 81_000),
			PlaybackEvent.Advance(50_000),
			PlaybackEvent.Finalized("track change"),
		)

		val listen = harness.finalized.single()
		assertEquals(130_000L, listen.playedMs)
		assertEquals(80_000L, listen.firstObservedPositionMs)
		assertEquals(
			"the combined viewing crosses 60%; the replacement fragment alone does not",
			listOf(videoId),
			harness.broadcasts.map { it.videoId },
		)
	}

	@Test
	fun `a restored-looking jump that remains materially behind still refuses carry`() {
		val harness = harness().feed(
			open(),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(80_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 80_000),
			PlaybackEvent.SessionRecreatedAtPosition(0),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			// This replaces the placeholder baseline, but is still 60s behind the
			// parked stop position. The existing contradiction rule must win.
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 20_000),
			PlaybackEvent.Advance(50_000),
			PlaybackEvent.Finalized("track change"),
		)

		assertEquals(50_000L, harness.finalized.single().playedMs)
		assertTrue(harness.broadcasts.isEmpty())
	}
}
