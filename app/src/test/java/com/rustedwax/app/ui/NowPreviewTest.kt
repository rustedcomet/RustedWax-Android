package com.rustedwax.app.ui

import com.rustedwax.app.detect.NativePreResolvedRoute
import com.rustedwax.app.detect.ResolverContext
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.hive.HiveScrobblePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The cache-only observation path rendered by the production Now card. */
class NowPreviewTest {

	@Test
	fun `an exact native pre-resolution refreshes the Now payload from cached facts`() {
		val videoId = "EGYmN-1UQzI"
		val requested = mutableListOf<String>()
		val preview = NowPreview.from(
			session(
				title = "Dear Jessie",
				artist = "Madonna",
				durationMs = 275_690,
				preResolvedVideoId = videoId,
			),
			cachedFacts = {
				requested += it
				VideoFacts(
					videoId = it,
					title = "Dear Jessie",
					author = "Madonna",
					category = "Music",
					lengthSeconds = 276,
					musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
				)
			},
			cachedMusicMatch = { _, _ -> null },
		)

		assertEquals(listOf(videoId), requested)
		assertEquals(videoId, preview.videoId)
		assertEquals(HiveScrobblePayload.KIND_SONG, preview.payload!!.kind)
		assertNull(preview.payload.album)
	}

	@Test
	fun `the real timer regression would remain video on the same preview path`() {
		val videoId = "cXbYjaEsQWg"
		val preview = NowPreview.from(
			session(
				title = "2 Minute Timer Bomb [COOKIE] 🍪",
				artist = "Cookie Bomb Timers",
				durationMs = 125_062,
				preResolvedVideoId = videoId,
			),
			cachedFacts = {
				VideoFacts(
					videoId = it,
					title = "2 Minute Timer Bomb [COOKIE] 🍪",
					author = "Cookie Bomb Timers",
					category = "Education",
					lengthSeconds = 125,
					musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
				)
			},
			cachedMusicMatch = { _, _ -> null },
		)

		assertEquals(HiveScrobblePayload.KIND_VIDEO, preview.payload!!.kind)
		assertEquals("YouTube Music identifies a podcast episode", preview.kindReason)
		assertNull(preview.payload.album)
	}

	private fun session(
		title: String,
		artist: String,
		durationMs: Long,
		preResolvedVideoId: String,
	) = SessionSnapshot(
		packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
		appLabel = "YouTube Music",
		isTarget = true,
		title = title,
		artist = artist,
		album = null,
		durationMs = durationMs,
		positionMs = 1_000,
		playedMs = 1_000,
		loopDetected = false,
		playbackState = "PLAYING",
		isPlaying = true,
		percentPlayed = 1_000.0 / durationMs,
		identity = YouTubeProbe.Identity.SiteOnly(
			host = "music.youtube.com",
			isMusic = true,
			source = "native package",
		),
		resolverContext = ResolverContext(
			preResolvedNativeVideoId = preResolvedVideoId,
			preResolvedNativeRoute = NativePreResolvedRoute.STRUCTURED_MUSIC,
		),
		notificationHint = null,
		metadataLines = emptyList(),
		trackStartedAtEpochSec = 1_700_000_000,
	)
}
