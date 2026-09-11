package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A feed preview is playing, in the only sense the transport knows.
 *
	 * Regression: an untouched inline preview can publish PLAYING, title and artist
	 * while position remains unavailable and duration is absent. Later history and
	 * watch-page recovery can supply identity and duration, but must not convert the
	 * preview's wall-clock interval into credited playback.
	 *
	 * Deliberate playback on the same page may also begin with one unavailable
	 * position callback, then promptly publish a real position and duration.
 *
 * So the distinction is not the title, the length, the percentage, the URL or the
 * audio — it is whether the transport ever says *where in the item it is*. A real
 * playback says so within about a second. A preview never does.
 */
class BrowserFeedPreviewTimelineReplayTest : ReplayScenarioTest() {

	private val videoId = "8D1RUlTyFpM"
	private val title = "Kabaka Pyramid - Faded Away ft. Buju Banton (Official Music Video)"
	private val channel = "Kabaka Pyramid Music"
	private val durationMs = 219_000L

	private fun harness(): ReplayHarness =
		ReplayHarness(ReplaySource.BRAVE).also { harness ->
			harness.env.facts.put(
				VideoFacts(
					videoId = videoId,
					title = title,
					author = channel,
					lengthSeconds = durationMs / 1000,
					category = "Music",
					watchPageResolved = true,
					isUnlisted = false,
				),
			)
		}

	/** The feed's own publication: a name, and no timeline at all. */
	private fun previewMetadata(title: String = this.title) = PlaybackEvent.SessionMetadata(
		title = title,
		artist = channel,
		durationMs = null,
	)

	// ── A. the preview earns nothing ─────────────────────────────────────────

	@Test
	fun `a preview that never publishes a position banks no time`() {
		val harness = harness().feed(
			previewMetadata(),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = -1),
			PlaybackEvent.Advance(79_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = -1),
			PlaybackEvent.Finalized("track change"),
		)

		assertEquals(
			"79s of wall clock while the viewer scrolled past it",
			0L,
			harness.finalized.sumOf { it.playedMs },
		)
		assertTrue(
			"and nothing reaches the engine, so nothing can be scrobbled",
			harness.broadcasts.isEmpty(),
		)
	}

	// ── B. no Not-logged row for something that was never a listen ───────────

	@Test
	fun `a preview leaves no row behind when the next preview replaces it`() {
		val harness = harness().feed(
			previewMetadata(),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = -1),
			PlaybackEvent.Advance(9_000),
			// The device republishes -1 on every callback for a preview's whole
			// life; the replay clock would otherwise extrapolate one for it.
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = -1),
			previewMetadata("Bring Me The Horizon - \"Visions\""),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = -1),
			PlaybackEvent.Advance(19_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = -1),
			PlaybackEvent.Finalized("track change"),
		)

		assertTrue(
			"three previews in a row used to leave three Not-logged rows",
			harness.finalized.isEmpty(),
		)
	}

	// ── E. the recovery routes never get their chance ────────────────────────

	@Test
	fun `watch-page duration cannot rescue a listen that was never measured`() {
		// The facts are in the harness: this id, this length, ready to be recovered.
		// It changes nothing, because the listen is never reported at all.
		val harness = harness().feed(
			previewMetadata(),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = -1),
			PlaybackEvent.Advance(140_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = -1),
			PlaybackEvent.Finalized("track change"),
		)

		assertTrue(harness.finalized.isEmpty())
		assertTrue(harness.broadcasts.isEmpty())
	}

	// ── C / F. a real playback establishes its timeline and is measured ──────

	@Test
	fun `the first unknown callback does not cost a genuine playback its listen`() {
		// A normal opening sequence: one unavailable position, then a real position.
		val harness = harness().feed(
			previewMetadata(),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = -1),
			PlaybackEvent.Advance(1_000),
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = durationMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 55),
			PlaybackEvent.Advance(150_000),
			PlaybackEvent.Finalized("track change"),
		)

		val listen = harness.finalized.single()
		assertEquals(
			"only the time after the timeline was established",
			150_000L,
			listen.playedMs,
		)
	}

	@Test
	fun `a position alone establishes the timeline`() {
		val harness = harness().feed(
			previewMetadata(),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = -1),
			PlaybackEvent.Advance(5_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(60_000),
			PlaybackEvent.Finalized("track change"),
		)

		assertEquals(60_000L, harness.finalized.single().playedMs)
	}

	@Test
	fun `a duration alone establishes the timeline`() {
		// Duration-first must not be held back waiting for a position: both are
		// statements about a real item.
		val harness = harness().feed(
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = durationMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = -1),
			PlaybackEvent.Advance(60_000),
			PlaybackEvent.Finalized("track change"),
		)

		assertEquals(60_000L, harness.finalized.single().playedMs)
	}

	@Test
	fun `an unknown position mid-playback is an ordinary gap, not a reset`() {
		val harness = harness().feed(
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = durationMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(40_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = -1),
			PlaybackEvent.Advance(40_000),
			PlaybackEvent.Finalized("track change"),
		)

		assertEquals(80_000L, harness.finalized.single().playedMs)
	}

	// ── D. a real playback still scrobbles ───────────────────────────────────

	@Test
	fun `a genuine browser video still reaches the threshold and is broadcast`() {
		val harness = harness().feed(
			PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = durationMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(200_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 200_000),
			PlaybackEvent.Finalized("track change"),
		)

		val listen = harness.finalized.single()
		assertEquals(200_000L, listen.playedMs)
		assertTrue(
			"200s of 219s is 91%",
			listen.playedMs.toDouble() / durationMs > 0.60,
		)
	}
}
