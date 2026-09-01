package com.rustedwax.app.scrobble

import com.rustedwax.app.detect.NativeSourceSwitches
import com.rustedwax.app.detect.ResolverContext
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.app.replay.ReplayEnvironment
import com.rustedwax.app.replay.ReplayScenarioTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicPresentationDurationTest : ReplayScenarioTest() {
	private val audioId = "nrHp1qUcuv0"

	private fun installEngine(env: ReplayEnvironment) {
		env.identity.search = {
			VideoResolutionAttempt(
				resolution = VideoResolution(
					videoId = audioId,
					source = "exact YouTube Music catalog work+artist+duration",
					title = "Buss It Open",
					channel = "Mr. Vegas",
					lengthSeconds = 196,
					uniquelyResolved = true,
					structuredNativeMusic = true,
				),
			)
		}
		env.facts.put(
			VideoFacts(
				videoId = audioId,
				title = "Buss It Open",
				author = "Mr. Vegas",
				lengthSeconds = 196,
				category = "Music",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
		FinalizationRuntime.installPortsForReplay(
			env.ports(),
			CoroutineScope(Dispatchers.Unconfined),
		)
	}

	private fun audioSession() = SessionSnapshot(
		packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
		appLabel = "YouTube Music",
		isTarget = true,
		title = "Buss It Open",
		artist = "Mr. Vegas",
		album = "Buss It Open",
		durationMs = 208_979,
		positionMs = 43_580,
		playedMs = 40_000,
		loopDetected = false,
		playbackState = "PLAYING",
		isPlaying = true,
		percentPlayed = 40_000.0 / 208_979.0,
		identity = YouTubeProbe.Identity.SiteOnly(
			host = "music.youtube.com",
			isMusic = true,
			source = "native YouTube Music",
		),
		resolverContext = ResolverContext(presentationDurationMs = 196_533),
		notificationHint = null,
		metadataLines = emptyList(),
		trackStartedAtEpochSec = 1_787_279_000,
		trackInstanceToken = 41,
		sourceEpoch = 1,
	)

	@Test
	fun `audio carry lookup uses current item duration not longest logical duration`() {
		NativeSourceSwitches.configureForReplay(
			NativeSourceSwitches.Config(
				youTubeScrobbling = true,
				youtubeEnabled = true,
				youtubeMusicEnabled = true,
			),
		)
		val env = ReplayEnvironment()
		installEngine(env)
		val session = audioSession()
		var carried: com.rustedwax.app.detect.SessionProbe.NativeResolvedIdentity? = null

		FinalizationRuntime.resolveNativeCarryIdentity(session) { carried = it }

		val request = env.identity.searchRequests.single()
		assertEquals(196L, request.durationSec)
		assertTrue(request.allowStructuredNativeMusic)
		assertTrue(request.allowYouTubeMusicCatalog)
		assertEquals("Buss It Open", request.album)
		assertEquals(audioId, carried?.videoId)
	}

	@Test
	fun `ordinary YouTube native carry cannot enter the Music catalog route`() {
		NativeSourceSwitches.configureForReplay(
			NativeSourceSwitches.Config(
				youTubeScrobbling = true,
				youtubeEnabled = true,
				youtubeMusicEnabled = true,
			),
		)
		val env = ReplayEnvironment()
		installEngine(env)
		val ordinaryYouTube = audioSession().copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube",
			album = null,
			identity = YouTubeProbe.Identity.SiteOnly(
				host = "www.youtube.com",
				isMusic = false,
				source = "native YouTube",
			),
		)

		FinalizationRuntime.resolveNativeCarryIdentity(ordinaryYouTube) {}

		val request = env.identity.searchRequests.single()
		assertTrue(request.allowStructuredNativeMusic)
		assertEquals(false, request.allowYouTubeMusicCatalog)
		assertEquals(null, request.album)
	}

	@Test
	fun `final corroboration uses current item duration without weakening threshold`() {
		NativeSourceSwitches.configureForReplay(
			NativeSourceSwitches.Config(
				youTubeScrobbling = true,
				youtubeEnabled = true,
				youtubeMusicEnabled = true,
			),
		)
		val env = ReplayEnvironment()
		installEngine(env)
		val outcomes = mutableListOf<FinalizationOutcome>()
		FinalizationRuntime.finalizationObserver = FinalizationObserver { _, outcome ->
			outcomes += outcome
		}

		FinalizationRuntime.executeAutomatic(
			audioSession().copy(
				positionMs = 140_000,
				playedMs = 140_000,
				playbackState = "STOPPED",
				isPlaying = false,
				percentPlayed = 140_000.0 / 208_979.0,
			),
		)

		assertTrue(outcomes.single() is FinalizationOutcome.Eligible)
		assertEquals(1, env.broadcaster.sent.size)
	}
}
