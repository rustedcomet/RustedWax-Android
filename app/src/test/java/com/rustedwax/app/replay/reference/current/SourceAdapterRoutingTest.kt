package com.rustedwax.app.replay.reference.current

import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.app.replay.reference.phase01.ParityStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The production probe really routes each source to its own adapter.
 *
 * ## What this adds that the parity gate cannot
 *
 * `ProbeParityTest` proves the migration changed no behaviour, by running the
 * recorded pre-migration `Watch` and the current one over one script. That is
 * the right question for *parity* and the wrong one for *routing*: a probe that
 * quietly kept interpreting sources itself would pass it perfectly.
 *
 * So this drives the shipping host — [SessionProbe] here is the byte-identical
 * mirror of `detect/SessionProbe.kt`, re-checked by
 * [CurrentMirrorProvenanceTest] on every run — over **one identical script under
 * two packages**, and asserts the finalized production snapshots differ in
 * exactly the ways the two adapters declare and nowhere else.
 *
 * Everything asserted is a `SessionSnapshot` the probe actually emitted to
 * `onTrackFinalized`. Nothing here inspects a class, a name or a source string.
 */
class SourceAdapterRoutingTest {

	private val browserPackage = "com.brave.browser"
	private val nativePackage = YouTubeProbe.YOUTUBE_PACKAGE

	/**
	 * `"<something> - YouTube"` is Chromium's document title and a real track
	 * title in the app, and one reader for both was wrong for one of them.
	 *
	 * [com.rustedwax.app.detect.BrowserTabMetadata] records what that cost when
	 * the browser half was missing: one such bundle collected 102 seconds of the
	 * *next* video's playback, another accrued 22,425 seconds overnight, and
	 * twelve correct ids were refused in a day for contradicting a channel of
	 * `m.youtube.com`.
	 */
	private val script = listOf(
		ParityStep.Metadata(title = "Sleepwalking", artist = "BMTH", durationMs = 200_000),
		ParityStep.Transport(state = PLAYING, positionMs = 0),
		ParityStep.Advance(60_000),
		ParityStep.Metadata(title = "Sleepwalking - YouTube"),
		ParityStep.Advance(30_000),
		ParityStep.RemoveSession,
	)

	@Test
	fun `a document title ends a browser listen and is a track title in the app`() {
		val browser = CurrentRun.snapshots(script, packageName = browserPackage)
		val native = CurrentRun.snapshots(script, packageName = nativePackage)

		assertEquals(
			"the browser adapter's container-title rule did not reach production: " +
				browser.describe(),
			listOf("Sleepwalking"),
			browser.map { it.title },
		)
		assertEquals(
			"a browser listen measured time while the tab named only itself",
			listOf(60_000L),
			browser.map { it.playedMs },
		)

		assertEquals(
			"the native adapter inherited a browser rule and lost a real track: " +
				native.describe(),
			listOf("Sleepwalking", "Sleepwalking - YouTube"),
			native.map { it.title },
		)
		assertEquals(
			"the native app's second listen was not measured",
			listOf(60_000L, 30_000L),
			native.map { it.playedMs },
		)
	}

	/**
	 * Only a native session can name its own item, and only from the id keys.
	 *
	 * `MEDIA_ID` is not one of the URI keys a browser identity is allowed to read
	 * — Chromium leaves every URI key unset (`<redacted-private-path>` Q3) and a media id it
	 * happened to publish would not be a YouTube video id. The two adapters
	 * therefore reach opposite verdicts from the identical bundle.
	 */
	@Test
	fun `an exact media id identifies the native listen and not the browser one`() {
		val withMediaId = listOf(
			ParityStep.Metadata(
				title = "Sleepwalking",
				artist = "BMTH",
				durationMs = 200_000,
				mediaId = "dQw4w9WgXcQ",
			),
			ParityStep.Transport(state = PLAYING, positionMs = 0),
			ParityStep.Advance(120_000),
			// STOPPED rather than a vanished session: a native listen that *can*
			// name its item is allowed to park its progress for a replacement
			// transport, so removing the session would open a continuation instead
			// of ending the listen. The stopped-replacement grace does not apply
			// either, precisely because the exact id is present.
			ParityStep.Transport(state = STOPPED, positionMs = 120_000),
		)

		val native = CurrentRun.snapshots(withMediaId, packageName = nativePackage)
		val browser = CurrentRun.snapshots(withMediaId, packageName = browserPackage)

		assertEquals(
			"the native adapter did not publish the item its session named: " +
				native.describe(),
			listOf("dQw4w9WgXcQ"),
			native.map { it.confirmed?.videoId },
		)
		assertEquals(1, browser.size)
		assertNull(
			"a browser listen manufactured an exact item id from a media id: " +
				browser.describe(),
			browser.single().confirmed?.videoId,
		)
		// Both still measured the same listen: the split is about identity, not
		// about the shared accumulator.
		assertEquals(listOf(120_000L), native.map { it.playedMs })
		assertEquals(listOf(120_000L), browser.map { it.playedMs })
	}

	/**
	 * The §4.1 record gate follows the source, not the state machine.
	 *
	 * A native package proves itself; a browser session has to earn the right to
	 * be named at all, because Chromium publishes a MediaSession for any video on
	 * any page and the log is exportable.
	 */
	@Test
	fun `only the package-proven source describes itself without proving the site`() {
		val native = CurrentRun.snapshots(script, packageName = nativePackage)
		val browser = CurrentRun.snapshots(script, packageName = browserPackage)

		assertEquals(
			"the native listen was not source-proven by its package",
			listOf(true, true),
			native.map { it.isYouTube },
		)
		assertEquals(
			"an unproven browser session was treated as proven YouTube",
			listOf(false),
			browser.map { it.isYouTube },
		)
	}

	private fun List<SessionSnapshot>.describe(): String =
		joinToString("; ") { "${it.packageName} \"${it.title}\" played=${it.playedMs}" }

	private companion object {
		/** `PlaybackState.STATE_PLAYING`, by value, as the stand-in publishes it. */
		const val PLAYING = 3

		/** `PlaybackState.STATE_STOPPED`. */
		const val STOPPED = 1
	}
}
