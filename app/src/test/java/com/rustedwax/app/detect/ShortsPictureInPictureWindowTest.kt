package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling a real picture-in-picture window from every other way a window can be
 * on screen at the same time as another app.
 *
 * This is the production classifier, not a stand-in: the service reduces each
 * `AccessibilityWindowInfo` to a [ShortsWindowFact] and the decision below is
 * the one it runs. What is faked here is Android, which is the part a JVM test
 * cannot have.
 *
 * The previous answer to this question was "YouTube is visible and some other
 * package resumed more recently", and Android's multi-resume made that wrong in
 * the ordinary way people use a phone. On a physical A36, split-screen and a
 * pop-up window over fullscreen YouTube both reported picture-in-picture while
 * `dumpsys` showed zero pinned tasks — so each of those shapes gets a test here.
 */
class ShortsPictureInPictureWindowTest {

	private val youTube = YouTubeProbe.YOUTUBE_PACKAGE
	private val adapter = NativeShortsAdapter()

	/** A window Android says is in picture-in-picture, owned by [owner]. */
	private fun pip(owner: String?) = ShortsWindowFact(
		inPictureInPictureMode = true,
		ownerPackage = { owner },
	)

	/** Any ordinary window — fullscreen, split-screen half, pop-up, anything. */
	private fun ordinary(owner: String?) = ShortsWindowFact(
		inPictureInPictureMode = false,
		ownerPackage = { owner },
	)

	// ---- 1. the case the whole signal exists for -----------------------------

	@Test
	fun `a YouTube picture-in-picture window is picture-in-picture`() {
		assertTrue(
			adapter.readPictureInPictureWindows(
				listOf(ordinary("com.sec.android.app.launcher"), pip(youTube)),
			),
		)
	}

	// ---- 2-4. the shapes that used to be mistaken for it ---------------------

	@Test
	fun `YouTube fullscreen is not picture-in-picture`() {
		assertFalse(
			"one visible YouTube window that Android does not call pinned is just YouTube",
			adapter.readPictureInPictureWindows(listOf(ordinary(youTube))),
		)
	}

	@Test
	fun `split-screen is not picture-in-picture`() {
		// Both halves visible, both resumed. This reported true before, because
		// the other half had resumed more recently than YouTube.
		assertFalse(
			adapter.readPictureInPictureWindows(
				listOf(ordinary(youTube), ordinary("com.rustedwax.app")),
			),
		)
	}

	@Test
	fun `a pop-up over fullscreen YouTube is not picture-in-picture`() {
		assertFalse(
			adapter.readPictureInPictureWindows(
				listOf(ordinary(youTube), ordinary("com.android.settings")),
			),
		)
	}

	// ---- 5-6. absence, and going away ----------------------------------------

	@Test
	fun `no windows at all is not picture-in-picture`() {
		assertFalse(adapter.readPictureInPictureWindows(emptyList()))
	}

	@Test
	fun `a dismissed picture-in-picture window is not picture-in-picture`() {
		// Dismissing it removes it from the list; what is left is the app the
		// viewer went back to, and possibly YouTube itself.
		assertFalse(
			adapter.readPictureInPictureWindows(
				listOf(ordinary("com.sec.android.app.launcher"), ordinary(youTube)),
			),
		)
	}

	// ---- failing closed -------------------------------------------------------

	@Test
	fun `another app's picture-in-picture window is not YouTube's`() {
		assertFalse(
			"a video call or a map in picture-in-picture is not a Short",
			adapter.readPictureInPictureWindows(
				listOf(pip("com.google.android.apps.meetings"), ordinary(youTube)),
			),
		)
	}

	@Test
	fun `a picture-in-picture window with no readable owner is refused`() {
		assertFalse(
			"an owner Android will not name is not an owner this may assume",
			adapter.readPictureInPictureWindows(listOf(pip(null))),
		)
	}

	@Test
	fun `a real YouTube window is still found behind other apps' picture-in-picture`() {
		assertTrue(
			adapter.readPictureInPictureWindows(
				listOf(pip("com.google.android.apps.meetings"), pip(youTube)),
			),
		)
	}

	// ---- the narrow read is part of the contract ------------------------------

	@Test
	fun `windows that are not in picture-in-picture are never asked who owns them`() {
		var asked = 0
		val counted = { pkg: String? ->
			ShortsWindowFact(inPictureInPictureMode = false, ownerPackage = { asked++; pkg })
		}

		adapter.readPictureInPictureWindows(
			listOf(counted("com.rustedwax.app"), counted("com.android.settings"), counted(youTube)),
		)

		assertEquals(
			"an ordinary window's owner is none of this app's business",
			0,
			asked,
		)
	}

	@Test
	fun `the search stops at the first YouTube picture-in-picture window`() {
		var asked = 0
		fun pipCounting(owner: String?) =
			ShortsWindowFact(inPictureInPictureMode = true, ownerPackage = { asked++; owner })

		assertTrue(
			adapter.readPictureInPictureWindows(
				listOf(pipCounting(youTube), pipCounting("com.android.settings")),
			),
		)
		assertEquals("no window is read after the question is answered", 1, asked)
	}
}
