package com.rustedwax.app.detect

import com.rustedwax.core.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YouTube playing in one tab while the user browses another.
 *
 * Brave and Chrome both allow it, and it is ordinary use rather than an edge
 * case. Before this, every evidence route that needed to know *which* tab a
 * screen observation belonged to gave up as soon as a second tab existed: the
 * accessibility scan refused to bind, so the playing listen lost its coverage
 * and its ad evidence for as long as any other tab stayed open — and since a
 * browser tab whose video has finished keeps a nominally `PLAYING` session
 * indefinitely, "as long as any other tab stayed open" could mean forever.
 *
 * Refusing was the *safe* answer and it stays the fallback, because binding a
 * scan of the visible tab to a listen playing in a different one would attribute
 * an ad label, or a coverage proof, to the wrong video. What changed is that a
 * scan which **names the video it saw** no longer needs elimination to be sure:
 * it belongs to whichever session is playing that video.
 */
class MultipleBrowserTabsTest {

	private val pkg = "com.brave.browser"

	private fun instance(token: Long, title: String) = MediaSessionAdEvidence.TrackInstance(
		packageName = pkg,
		token = token,
		signature = TrackIdentity(title, "channel", null, 213_000),
	)

	private fun scan(videoId: String?) = MediaSessionAccessibilityEvidence.Scan(
		packageName = pkg,
		host = "www.youtube.com",
		rootVisible = true,
		urlGeneration = 3,
		videoId = videoId,
		atMillis = 2_000,
	)

	@After
	fun tearDown() {
		MediaSessionAccessibilityEvidence.clearAll()
		MediaSessionAdEvidence.clearAll()
	}

	@Test
	fun `one tab still binds by elimination, exactly as before`() {
		val only = instance(1, "Sleepwalking")
		MediaSessionAccessibilityEvidence.activate(only, establishedAtMillis = 1_000)

		val coverage = MediaSessionAccessibilityEvidence.observe(
			scan = scan(videoId = null),
			instance = only,
			unambiguous = true,
		)

		assertNotNull("a sole session needs no video id to be sure", coverage)
	}

	@Test
	fun `a second tab no longer starves the playing listen of coverage`() {
		// Two live sessions. The scan names the video the first one is playing, so
		// it belongs to that one and nothing else can claim it.
		val playing = instance(1, "Sleepwalking")
		val other = instance(2, "Some other tab")
		MediaSessionAccessibilityEvidence.activate(playing, establishedAtMillis = 1_000)
		MediaSessionAccessibilityEvidence.activate(other, establishedAtMillis = 1_000)

		val coverage = MediaSessionAccessibilityEvidence.observe(
			scan = scan(videoId = "dQw4w9WgXcQ"),
			instance = playing,
			unambiguous = false,
			namedThisInstance = true,
		)

		assertNotNull("the scan named this instance's own video", coverage)
		assertEquals("dQw4w9WgXcQ", coverage?.videoId)
	}

	@Test
	fun `a scan of a different visible tab never becomes the playing listen's coverage`() {
		val playing = instance(1, "Sleepwalking")
		MediaSessionAccessibilityEvidence.activate(playing, establishedAtMillis = 1_000)

		val selection = BrowserScanBinding.select(
			namedVideoId = "7i_2TJv96Wk",
			candidates = listOf(
				// The only live session, and it is playing something else.
				BrowserScanBinding.Candidate(key = 1, describesNamedVideo = false),
			),
		)

		assertTrue(selection is BrowserScanBinding.Selection.Refused)
		assertNull(
			"nothing was bound, so nothing may be recorded",
			MediaSessionAccessibilityEvidence.current(
				instance = playing,
				expectedUrlGeneration = null,
				now = 2_000,
			),
		)
	}

	@Test
	fun `a scan that names no video still refuses when several tabs are live`() {
		// The safety property that has to survive: with nothing identifying the
		// observation, binding it to either session would be a guess, and a wrong
		// guess attributes an ad label to the video the user actually chose.
		val playing = instance(1, "Sleepwalking")
		val other = instance(2, "Some other tab")
		MediaSessionAccessibilityEvidence.activate(playing, establishedAtMillis = 1_000)
		MediaSessionAccessibilityEvidence.activate(other, establishedAtMillis = 1_000)

		val coverage = MediaSessionAccessibilityEvidence.observe(
			scan = scan(videoId = null),
			instance = playing,
			unambiguous = false,
			namedThisInstance = false,
		)

		assertNull(coverage)
	}
}
