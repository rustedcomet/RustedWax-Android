package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeYouTubeAppsTest {

	@Test
	fun `browser packages ride the master switch, not the native opt-ins`() {
		for (packageName in YouTubeProbe.TARGET_PACKAGES) {
			assertTrue(
				YouTubeProbe.acceptsPackage(
					packageName = packageName,
					youTubeScrobbling = true,
					nativeYouTubeEnabled = false,
					nativeYouTubeMusicEnabled = false,
				),
			)
		}
	}

	/**
	 * The hole the unified switch closes: before v0.11.0 there was no setting
	 * anywhere that stopped browser YouTube. Turning both native sources off left
	 * Brave and Chrome scrobbling, which is the one thing a person who switched
	 * everything off would not expect.
	 */
	@Test
	fun `the master switch off stops every YouTube surface including the browser`() {
		for (packageName in YouTubeProbe.TARGET_PACKAGES + YouTubeProbe.YOUTUBE_APP_PACKAGES) {
			assertFalse(
				packageName,
				YouTubeProbe.acceptsPackage(
					packageName = packageName,
					youTubeScrobbling = false,
					nativeYouTubeEnabled = true,
					nativeYouTubeMusicEnabled = true,
				),
			)
		}
	}

	@Test
	fun `each native package keeps its own opt-in boundary under the master`() {
		assertFalse(YouTubeProbe.acceptsPackage(YouTubeProbe.YOUTUBE_PACKAGE, true, false, false))
		assertFalse(
			YouTubeProbe.acceptsPackage(YouTubeProbe.YOUTUBE_MUSIC_PACKAGE, true, false, false),
		)
		assertTrue(YouTubeProbe.acceptsPackage(YouTubeProbe.YOUTUBE_PACKAGE, true, true, false))
		assertFalse(
			YouTubeProbe.acceptsPackage(YouTubeProbe.YOUTUBE_MUSIC_PACKAGE, true, true, false),
		)
		assertFalse(YouTubeProbe.acceptsPackage(YouTubeProbe.YOUTUBE_PACKAGE, true, false, true))
		assertTrue(
			YouTubeProbe.acceptsPackage(YouTubeProbe.YOUTUBE_MUSIC_PACKAGE, true, false, true),
		)
		assertFalse(YouTubeProbe.acceptsPackage("com.example.player", true, true, true))
	}

	@Test
	fun `a canonical link is built only from a proven eleven-character id`() {
		assertEquals(
			"https://www.youtube.com/watch?v=dQw4w9WgXcQ",
			YouTubeProbe.canonicalWatchUrl("dQw4w9WgXcQ"),
		)
		// Everything a History or Not-logged row could actually be holding when
		// identity never got there. None of these may become a link.
		assertNull(YouTubeProbe.canonicalWatchUrl(null))
		assertNull(YouTubeProbe.canonicalWatchUrl(""))
		assertNull(YouTubeProbe.canonicalWatchUrl("dQw4w9WgXc"))
		assertNull(YouTubeProbe.canonicalWatchUrl("dQw4w9WgXcQ2"))
		assertNull(YouTubeProbe.canonicalWatchUrl("Rick Astley — Never"))
		assertNull(YouTubeProbe.canonicalWatchUrl("dQw4w9WgXc "))
		assertNull(YouTubeProbe.canonicalWatchUrl("https://youtu.be/dQw4w9WgXcQ"))
	}

	@Test
	fun `native media id is exact and canonicalized`() {
		val identity = YouTubeProbe.identifyNative(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			fields = YouTubeProbe.NativeMetadataFields(mediaId = "dQw4w9WgXcQ"),
		) as YouTubeProbe.Identity.Confirmed

		assertEquals("dQw4w9WgXcQ", identity.videoId)
		assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", identity.url)
		assertEquals("media id", identity.exactIdRoute)
		assertFalse(identity.isMusic)
	}

	@Test
	fun `native exact id routes prefer media id then media URI then artwork`() {
		val identity = YouTubeProbe.identifyNative(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			fields = YouTubeProbe.NativeMetadataFields(
				mediaId = "dQw4w9WgXcQ",
				mediaUri = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
				artUri = "https://i.ytimg.com/vi/9bZkp7q19f0/hqdefault.jpg",
			),
		) as YouTubeProbe.Identity.Confirmed

		assertEquals("dQw4w9WgXcQ", identity.videoId)
		assertEquals("media id", identity.exactIdRoute)
	}

	@Test
	fun `native media URI is accepted only from a canonical YouTube route`() {
		val identity = YouTubeProbe.identifyNative(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			fields = YouTubeProbe.NativeMetadataFields(
				mediaUri = "https://music.youtube.com/watch?list=RDAMVM&v=aqz-KE-bpKQ",
			),
		) as YouTubeProbe.Identity.Confirmed

		assertEquals("aqz-KE-bpKQ", identity.videoId)
		assertEquals("https://www.youtube.com/watch?v=aqz-KE-bpKQ", identity.url)
		assertEquals("media URI", identity.exactIdRoute)
	}

	@Test
	fun `a native shorts media URI retains short path evidence`() {
		val identity = YouTubeProbe.identifyNative(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			fields = YouTubeProbe.NativeMetadataFields(
				mediaUri = "https://www.youtube.com/shorts/aqz-KE-bpKQ",
			),
		) as YouTubeProbe.Identity.Confirmed

		assertTrue(identity.isShort)
		assertEquals("https://www.youtube.com/watch?v=aqz-KE-bpKQ", identity.url)
	}

	@Test
	fun `native artwork URI can prove the exact video id`() {
		val identity = YouTubeProbe.identifyNative(
			packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
			fields = YouTubeProbe.NativeMetadataFields(
				albumArtUri = "https://i.ytimg.com/vi_webp/9bZkp7q19f0/maxresdefault.webp",
			),
		) as YouTubeProbe.Identity.Confirmed

		assertEquals("9bZkp7q19f0", identity.videoId)
		assertEquals("https://www.youtube.com/watch?v=9bZkp7q19f0", identity.url)
		assertEquals("artwork URI", identity.exactIdRoute)
		assertTrue(identity.isMusic)
	}

	@Test
	fun `invalid or non YouTube ids stay site-only and off-chain`() {
		val identity = YouTubeProbe.identifyNative(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			fields = YouTubeProbe.NativeMetadataFields(
				mediaId = "not-an-id",
				mediaUri = "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
				artUri = "https://example.com/vi/9bZkp7q19f0/cover.jpg",
			),
		)

		assertTrue(identity is YouTubeProbe.Identity.SiteOnly)
		assertEquals(null, (identity as? YouTubeProbe.Identity.Confirmed)?.videoId)
	}

	@Test
	fun `conflicting or malformed media URI ids fail closed`() {
		val conflicting = YouTubeProbe.identifyNative(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			fields = YouTubeProbe.NativeMetadataFields(
				mediaUri = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&v=aqz-KE-bpKQ",
			),
		)
		val malformed = YouTubeProbe.identifyNative(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			fields = YouTubeProbe.NativeMetadataFields(
				mediaUri = "https://www.youtube.com/watch?v=%ZZ",
			),
		)

		assertTrue(conflicting is YouTubeProbe.Identity.SiteOnly)
		assertTrue(malformed is YouTubeProbe.Identity.SiteOnly)
	}

	@Test
	fun `package origin alone never invents a video id`() {
		val identity = YouTubeProbe.identifyNative(
			packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
			fields = YouTubeProbe.NativeMetadataFields(),
		)

		assertTrue(identity is YouTubeProbe.Identity.SiteOnly)
		assertTrue((identity as YouTubeProbe.Identity.SiteOnly).isMusic)
		assertTrue(identity.source.contains(YouTubeProbe.YOUTUBE_MUSIC_PACKAGE))
	}
}
