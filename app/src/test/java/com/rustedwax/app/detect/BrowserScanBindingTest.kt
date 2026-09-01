package com.rustedwax.app.detect

import com.rustedwax.app.detect.BrowserScanBinding.Candidate
import com.rustedwax.app.detect.BrowserScanBinding.Selection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which tab a browser screen observation belongs to.
 *
 * The rule this pins is the one the previous selection got backwards: it tried
 * elimination *first*, so a scan that named a video was handed to the sole
 * session even when that session was playing something else. Every case below
 * is a shape that occurred, or could occur, on a phone with two YouTube tabs
 * open.
 */
class BrowserScanBindingTest {

	private val playingVideo = "dQw4w9WgXcQ"
	private val visibleTabVideo = "7i_2TJv96Wk"

	private fun refusal(selection: Selection): String {
		assertTrue("expected a refusal, got $selection", selection is Selection.Refused)
		return (selection as Selection.Refused).reason
	}

	// ── a scan that names a video ──────────────────────────────────────────

	@Test
	fun `a named scan binds to the listen playing that video`() {
		val selection = BrowserScanBinding.select(
			namedVideoId = playingVideo,
			candidates = listOf(
				Candidate(key = 1, describesNamedVideo = true),
				Candidate(key = 2, describesNamedVideo = false),
			),
		)

		assertEquals(Selection.Bound(1, namedThisInstance = true), selection)
	}

	/**
	 * The defect, stated as the case that produced it.
	 *
	 * One MediaSession is playing video A. The user has a second YouTube tab in
	 * front showing video B — a tab that has published no session of its own, or
	 * whose session has already gone. The scan describes B. Under the old
	 * `candidates.singleOrNull() ?: named` it bound to A, so A's coverage proof
	 * and A's ad label came from a page A was not playing.
	 */
	@Test
	fun `a named scan does not bind to the only listen when that listen plays something else`() {
		val selection = BrowserScanBinding.select(
			namedVideoId = visibleTabVideo,
			candidates = listOf(Candidate(key = 1, describesNamedVideo = false)),
		)

		assertTrue(refusal(selection).contains("none is playing $visibleTabVideo"))
	}

	@Test
	fun `two listens claiming the same video bind to neither`() {
		// A latch that has not caught up with a tab change looks exactly like this.
		// Picking either would be a coin toss with an ad label riding on it.
		val selection = BrowserScanBinding.select(
			namedVideoId = playingVideo,
			candidates = listOf(
				Candidate(key = 1, describesNamedVideo = true),
				Candidate(key = 2, describesNamedVideo = true),
			),
		)

		assertTrue(refusal(selection).contains("2 of 2 active MediaSession tracks"))
		assertTrue(refusal(selection).contains("claim $playingVideo"))
	}

	@Test
	fun `no match refuses even when nothing else is live`() {
		val selection = BrowserScanBinding.select(namedVideoId = playingVideo, candidates = emptyList())

		assertTrue(refusal(selection).contains("0 active MediaSession tracks"))
	}

	// ── a scan that names nothing ──────────────────────────────────────────

	@Test
	fun `an unnamed scan binds by elimination when one listen is live`() {
		val selection = BrowserScanBinding.select(
			namedVideoId = null,
			candidates = listOf(Candidate(key = 4)),
		)

		assertEquals(Selection.Bound(4, namedThisInstance = false), selection)
	}

	@Test
	fun `an unnamed scan refuses as soon as a second listen exists`() {
		val selection = BrowserScanBinding.select(
			namedVideoId = null,
			candidates = listOf(Candidate(key = 1), Candidate(key = 2)),
		)

		assertTrue(refusal(selection).contains("the observation named no video"))
	}

	@Test
	fun `an unnamed scan refuses when nothing is live`() {
		val selection = BrowserScanBinding.select(namedVideoId = null, candidates = emptyList())

		assertTrue(refusal(selection).contains("0 active MediaSession tracks"))
	}

	// ── stale listens ──────────────────────────────────────────────────────

	@Test
	fun `a finalized listen is not a candidate and does not block elimination`() {
		// The track it described has ended. A scan taken now cannot be about it,
		// and counting it would starve the live listen exactly the way the count
		// check used to.
		val selection = BrowserScanBinding.select(
			namedVideoId = null,
			candidates = listOf(
				Candidate(key = 1, finalized = true),
				Candidate(key = 2),
			),
		)

		assertEquals(Selection.Bound(2, namedThisInstance = false), selection)
	}

	@Test
	fun `a finalized listen cannot claim a scan that names its video`() {
		val selection = BrowserScanBinding.select(
			namedVideoId = playingVideo,
			candidates = listOf(Candidate(key = 1, describesNamedVideo = true, finalized = true)),
		)

		val reason = refusal(selection)
		assertTrue(reason.contains("none is playing $playingVideo"))
		assertTrue("the refusal should say one was stale: $reason", reason.contains("1 already finalized"))
	}

	// ── paused listens ─────────────────────────────────────────────────────

	@Test
	fun `a latched id on a paused listen is identity not playback proof`() {
		// The latch says what the tab described; it does not say that this session
		// is the one producing audio now. Screen evidence may not become playback
		// evidence merely because the two ids agree.
		val selection = BrowserScanBinding.select(
			namedVideoId = playingVideo,
			candidates = listOf(
				Candidate(key = 1, describesNamedVideo = true, playing = false),
				Candidate(key = 2, describesNamedVideo = false, playing = true),
			),
		)

		val reason = refusal(selection)
		assertTrue("the refusal should identify the ineligible match: $reason", reason.contains("paused"))
	}

	@Test
	fun `a paused listen is not eliminated away in favour of the playing one`() {
		// Treating pause as absence would hand the paused tab's scan to the playing
		// listen — the same misattribution, from the other direction.
		val selection = BrowserScanBinding.select(
			namedVideoId = null,
			candidates = listOf(
				Candidate(key = 1, playing = true),
				Candidate(key = 2, playing = false),
			),
		)

		val reason = refusal(selection)
		assertTrue(reason.contains("2 active MediaSession tracks"))
		assertTrue("the refusal should say what it was made of: $reason", reason.contains("1 paused"))
	}

	@Test
	fun `a sole paused listen cannot receive an unnamed observation`() {
		val selection = BrowserScanBinding.select(
			namedVideoId = null,
			candidates = listOf(Candidate(key = 1, playing = false)),
		)

		val reason = refusal(selection)
		assertTrue(reason.contains("0 playback-eligible"))
		assertTrue(reason.contains("1 paused"))
	}

	@Test
	fun `a tab switch binds the newly playing matching session`() {
		val selection = BrowserScanBinding.select(
			namedVideoId = visibleTabVideo,
			candidates = listOf(
				Candidate(key = 1, describesNamedVideo = false, playing = false),
				Candidate(key = 2, describesNamedVideo = true, playing = true),
			),
		)

		assertEquals(Selection.Bound(2, namedThisInstance = true), selection)
	}

	@Test
	fun `a replacement binds while the finalized predecessor is ignored`() {
		val selection = BrowserScanBinding.select(
			namedVideoId = playingVideo,
			candidates = listOf(
				Candidate(
					key = 1,
					describesNamedVideo = true,
					finalized = true,
				),
				Candidate(key = 2, describesNamedVideo = true, playing = true),
			),
		)

		assertEquals(Selection.Bound(2, namedThisInstance = true), selection)
	}
}
