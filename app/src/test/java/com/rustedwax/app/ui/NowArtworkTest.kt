package com.rustedwax.app.ui

import com.rustedwax.app.detect.ResolverContext
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.YouTubeProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #62: what Now may draw before, and without, a proven video id. */
class NowArtworkTest {

	@Test
	fun `a browser listen with no id has no thumbnail id and may use its own player frame`() {
		val s = brave(title = "Migos - Slippery feat. Gucci Mane [Official Video]")
		val preview = NowPreview.from(s, cachedFacts = { null }, cachedMusicMatch = { _, _ -> null })

		assertNull(preview.videoId)
		assertTrue(playerArtworkMatches(s, "Migos - Slippery feat. Gucci Mane [Official Video]"))
	}

	@Test
	fun `a frame from a bundle naming another title is not drawn`() {
		val s = brave(title = "Migos - Slippery feat. Gucci Mane [Official Video]")
		assertFalse(playerArtworkMatches(s, "Quavo - Quavo – Honey Bun (Official Video)"))
		// Chromium's teardown bundle names the site, not the video.
		assertFalse(playerArtworkMatches(s, "YouTube"))
	}

	@Test
	fun `a session with no title draws no player frame`() {
		assertFalse(playerArtworkMatches(brave(title = null), "Anything"))
	}

	@Test
	fun `provisional artwork never becomes the preview's identity`() {
		// The observed address-bar id of the *previous* video stays out of Now;
		// only confirmed or pre-resolved ids drive the thumbnail and payload.
		val s = brave(
			title = "Slippery",
			resolver = ResolverContext(observedVideoId = "Xvca8wQvfjc"),
		)
		val preview = NowPreview.from(s, cachedFacts = { null }, cachedMusicMatch = { _, _ -> null })
		assertNull(preview.videoId)
		assertNull(preview.payload?.url)
	}

	@Test
	fun `transient thumbnail misses are retried on a bounded backoff`() {
		assertEquals(
			listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 120_000L, 120_000L, 120_000L),
			(0..7).map(ThumbnailFetch::retryDelayMs),
		)
		assertEquals(120_000L, ThumbnailFetch.retryDelayMs(Int.MAX_VALUE))
	}

	private fun brave(title: String?, resolver: ResolverContext = ResolverContext()) = SessionSnapshot(
		packageName = "com.brave.browser",
		appLabel = "Brave",
		isTarget = true,
		title = title,
		artist = "Migos ATL",
		album = null,
		durationMs = 306_621,
		positionMs = 1_000,
		playedMs = 1_000,
		loopDetected = false,
		playbackState = "PLAYING",
		isPlaying = true,
		percentPlayed = 1_000.0 / 306_621,
		identity = YouTubeProbe.Identity.SiteOnly(
			host = "m.youtube.com",
			isMusic = false,
			source = "notification sub-text",
		),
		resolverContext = resolver,
		notificationHint = null,
		metadataLines = emptyList(),
		trackStartedAtEpochSec = 1_700_000_000,
		trackInstanceToken = 4,
	)
}
