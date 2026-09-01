package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAdDetectorTest {

	@Test
	fun `youtube ad labels are recognised in English and Spanish`() {
		assertEquals("Sponsored", YouTubeAdDetector.signalFor("Sponsored"))
		assertEquals("Skip ad", YouTubeAdDetector.signalFor("Skip ad"))
		assertEquals("Ad 1 of 2 · 0:15", YouTubeAdDetector.signalFor("Ad 1 of 2 · 0:15"))
		assertEquals("Patrocinado", YouTubeAdDetector.signalFor("Patrocinado"))
		assertEquals("Omitir anuncio", YouTubeAdDetector.signalFor("Omitir anuncio"))
		assertEquals("Anuncio 1 de 2", YouTubeAdDetector.signalFor("Anuncio 1 de 2"))
	}

	@Test
	fun `brand and ordinary title text are not ad evidence`() {
		assertNull(YouTubeAdDetector.signalFor("PONDS CAM"))
		assertNull(YouTubeAdDetector.signalFor("Bad and Boujee"))
		assertNull(YouTubeAdDetector.signalFor("This video includes paid promotion"))
		assertNull(YouTubeAdDetector.signalFor("How advertising changed football"))
		assertNull(YouTubeAdDetector.signalFor("Add to queue"))
		assertNull(YouTubeAdDetector.signalFor("Adidas"))
	}

	@Test
	fun `exact label scanning is enabled for every visible youtube host shape`() {
		assertTrue(YouTubeAdDetector.shouldScanHost("youtube.com"))
		assertTrue(YouTubeAdDetector.shouldScanHost("m.youtube.com"))
		assertTrue(YouTubeAdDetector.shouldScanHost("music.youtube.com"))
		assertFalse(YouTubeAdDetector.shouldScanHost("example.com"))
		assertFalse(YouTubeAdDetector.shouldScanHost(null))
	}

	@Test
	fun `ad binding requires a concrete shorts id in the same snapshot`() {
		assertEquals(
			"grNk0DpiaEE",
			YouTubeAdDetector.videoIdInSameShortSnapshot(
				host = "youtube.com",
				rawUrlBar = "https://youtube.com/shorts/grNk0DpiaEE?si=test",
			),
		)
		assertNull(
			YouTubeAdDetector.videoIdInSameShortSnapshot(
				host = "youtube.com",
				rawUrlBar = "youtube.com",
			),
		)
		assertNull(
			YouTubeAdDetector.videoIdInSameShortSnapshot(
				host = "youtube.com",
				rawUrlBar = "https://youtube.com/watch?v=grNk0DpiaEE",
			),
		)
		assertNull(
			YouTubeAdDetector.videoIdInSameShortSnapshot(
				host = "example.com",
				rawUrlBar = "https://example.com/shorts/grNk0DpiaEE",
			),
		)
	}

	@Test
	fun `ad evidence binds only to the same shorts id`() {
		val short = YouTubeProbe.Identity.Confirmed(
			videoId = "abcdefghijk",
			url = "https://www.youtube.com/watch?v=abcdefghijk",
			isMusic = false,
			isShort = true,
			source = "test",
		)
		val evidence = AdEvidence.Evidence(
			packageName = "com.android.chrome",
			videoId = "abcdefghijk",
			signal = "Sponsored",
		)
		assertTrue(SessionProbe.adEvidenceMatches(short, evidence))
		assertFalse(
			SessionProbe.adEvidenceMatches(
				short.copy(videoId = "lmnopqrstuv"),
				evidence,
			),
		)
		assertFalse(SessionProbe.adEvidenceMatches(short.copy(isShort = false), evidence))
	}

	@Test
	fun `measured multi-line Shorts ad card is recognised by its own line`() {
		// Captured on the device 2026-08-05. The whole ad card arrives as one
		// content description, with the label on its own line rather than in its
		// own node. Whole-literal matching missed every one of these, so the ad
		// refused on the owner-handle gate — ads have no channel — and was logged
		// as an identity failure instead of an ad.
		assertEquals(
			"Ad",
			YouTubeAdDetector.signalFor("Dola: Smart AI Assistant\nAd\n4.4 stars\nFREE"),
		)
		assertEquals(
			"Ad",
			YouTubeAdDetector.signalFor("TikTok\nAd\n4.4 stars\nFREE"),
		)
	}

	@Test
	fun `a line has to be nothing but the label`() {
		// The guarantee that keeps brand and channel names safe: "ad" occurring
		// inside a line never counts, however many lines there are.
		assertNull(YouTubeAdDetector.signalFor("Adele Live\n2 million views"))
		assertNull(YouTubeAdDetector.signalFor("Read this now\nbefore it goes"))
		assertNull(YouTubeAdDetector.signalFor("How to add spice\nto anything"))
		assertNull(YouTubeAdDetector.signalFor("Bad Bunny\nnew single"))
	}
}
