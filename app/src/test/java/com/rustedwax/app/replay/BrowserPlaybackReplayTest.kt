package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.app.replay.ReplayHarness.RefusalKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPlaybackReplayTest : ReplayScenarioTest() {

	// ---- fixtures ------------------------------------------------------------

	private fun watching(
		videoId: String,
		title: String,
		channel: String,
		durationMs: Long,
		playlistId: String? = null,
	) = listOf(
		PlaybackEvent.NotificationObserved(host = "youtube.com", title = title),
		PlaybackEvent.UrlObserved(
			host = "www.youtube.com",
			videoId = videoId,
			playlistId = playlistId,
		),
		PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = durationMs),
		PlaybackEvent.PlaybackStateChanged(playing = true),
	)

	private fun facts(
		videoId: String,
		title: String,
		author: String,
		lengthSeconds: Long,
		category: String = "Music",
	) = VideoFacts(
		videoId = videoId,
		title = title,
		author = author,
		lengthSeconds = lengthSeconds,
		category = category,
		watchPageResolved = true,
		isUnlisted = false,
	)

	// ---- foreground, the ordinary case --------------------------------------

	@Test
	fun `brave foreground watch page scrobbles once with the canonical hyperlink`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(
			facts("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213),
		)

		harness.feed(
			watching("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213_000)
				+ PlaybackEvent.Advance(180_000)
				+ PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		val payload = harness.broadcasts.single()
		assertEquals("dQw4w9WgXcQ", payload.videoId)
		assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", payload.url)
		assertEquals(85, payload.percentPlayed)
		assertEquals(emptyList<RefusalKind>(), harness.refusalKinds)
		assertEquals(1, harness.transactionIds.size)
	}

	@Test
	fun `a listen below the threshold is refused before any network call`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)

		harness.feed(
			watching("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213_000)
				+ PlaybackEvent.Advance(60_000)
				+ PlaybackEvent.Finalized(),
		)

		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(listOf(RefusalKind.BELOW_THRESHOLD), harness.refusalKinds)
		// The prefilter is the whole point: nothing was fetched for a track that
		// no lookup could have rescued.
		assertEquals(emptyList<String>(), harness.env.facts.resolved)
		assertEquals(emptyList<ReplayIdentitySource.Route>(), harness.identityRoutes)
	}

	// ---- background and minimised --------------------------------------------

	@Test
	fun `a background tab keeps the id the bar named before the user left it`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts("aBcDeFgHiJk", "Bring Me The Horizon - Avalanche", "BMTHOfficialVEVO", 275))

		harness.feed(
			watching("aBcDeFgHiJk", "Bring Me The Horizon - Avalanche", "BMTHOfficialVEVO", 275_000)
				+ PlaybackEvent.Advance(30_000)
				// The user switches to another tab; the bar now describes that one.
				// A non-YouTube URL must never taint a track that is already proven.
				+ PlaybackEvent.UrlObserved(host = "en.wikipedia.org", raw = "https://en.wikipedia.org/wiki/Avalanche")
				+ PlaybackEvent.Advance(220_000)
				+ PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		assertEquals("aBcDeFgHiJk", harness.broadcasts.single().videoId)
	}

	@Test
	fun `Sleepwalking replaces the rejected outgoing URL and keeps the corrected generation`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(
			facts("QuQW1vkDA1c", "Bring Me The Horizon - Visions", "BMTHOfficialVEVO", 245),
			facts("lir3dzYIhz0", "Sleepwalking", "BMTHOfficialVEVO", 237),
		)

		harness.feed(
			PlaybackEvent.NotificationObserved(host = "youtube.com"),
			// Chromium's first callback still describes the outgoing track.
			PlaybackEvent.UrlObserved(
				host = "m.youtube.com",
				videoId = "QuQW1vkDA1c",
				playlistId = "PLoldPlaylist000000",
			),
			PlaybackEvent.SessionMetadata(
				title = "Bring Me The Horizon - Sleepwalking",
				artist = "BMTHOfficialVEVO",
				durationMs = 237_000,
			),
			// The outgoing id is now disproved; the later bar reading must replace
			// both it and its stale playlist as one observation.
			PlaybackEvent.UrlObserved(
				host = "m.youtube.com",
				videoId = "lir3dzYIhz0",
				playlistId = "PLnewPlaylist000000",
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(220_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(listOf("lir3dzYIhz0"), harness.broadcasts.map { it.videoId })
		assertEquals(emptyList<RefusalKind>(), harness.refusalKinds)
		assertEquals("lir3dzYIhz0", harness.finalized.single().resolverContext.observedVideoId)
		assertEquals("PLnewPlaylist000000", harness.finalized.single().resolverContext.playlistId)
	}

	@Test
	fun `a minimised browser whose bar went quiet still resolves through search`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts("mNoPqRsTuVw", "Doja Cat - Paint The Town Red", "dojacatVEVO", 231))
		harness.env.identity.search = {
			VideoResolutionAttempt(
				resolution = VideoResolution(
					videoId = "mNoPqRsTuVw",
					source = "search",
					title = "Doja Cat - Paint The Town Red",
					channel = "dojacatVEVO",
					lengthSeconds = 231,
					uniquelyResolved = true,
				),
			)
		}

		harness.feed(
			// Only the notification proves the site; the bar never spoke.
			PlaybackEvent.NotificationObserved(host = "youtube.com"),
			PlaybackEvent.SessionMetadata(
				title = "Doja Cat - Paint The Town Red",
				artist = "dojacatVEVO",
				durationMs = 231_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(200_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		assertEquals("mNoPqRsTuVw", harness.broadcasts.single().videoId)
		assertTrue(ReplayIdentitySource.Route.SEARCH in harness.identityRoutes)
	}

	@Test
	fun `an ordinary Brave video keeps its frozen id in picture in picture`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(
			facts("aBcDeFgHiJk", "Bring Me The Horizon - Avalanche", "BMTHOfficialVEVO", 275),
		)

		harness.feed(
			watching(
				"aBcDeFgHiJk",
				"Bring Me The Horizon - Avalanche",
				"BMTHOfficialVEVO",
				275_000,
			) + PlaybackEvent.Advance(30_000) +
				PlaybackEvent.SurfaceChanged(PlaybackEvent.Surface.PICTURE_IN_PICTURE) +
				PlaybackEvent.Advance(220_000) +
				PlaybackEvent.Finalized(),
		)

		assertEquals(listOf("aBcDeFgHiJk"), harness.broadcasts.map { it.videoId })
		assertEquals(emptyList<RefusalKind>(), harness.refusalKinds)
	}

	// ---- the quiet-bar counter -----------------------------------------------

	@Test
	fun `three unresolvable browser tracks in a row raise the quiet-bar counter`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)

		repeat(3) { index ->
			harness.feed(
				PlaybackEvent.NotificationObserved(host = "youtube.com"),
				PlaybackEvent.SessionMetadata(
					title = "Untitled clip $index",
					artist = "Some Channel",
					durationMs = 120_000,
				),
				PlaybackEvent.PlaybackStateChanged(playing = true),
				PlaybackEvent.Advance(100_000),
				PlaybackEvent.Finalized(),
			)
		}

		assertEquals(3, harness.consecutiveTracksWithoutVideoId)
		assertEquals(
			List(3) { RefusalKind.NO_VERIFIED_VIDEO_ID },
			harness.terminalRefusalKinds,
		)
		assertEquals(List(3) { RefusalKind.NO_VERIFIED_VIDEO_ID }, harness.unlinkedRefusalKinds)
		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
	}

	@Test
	fun `a bar that named the video does not count toward the quiet-bar warning`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)

		harness.env.facts.put(
			facts("fctnSdDjxiY", "A completely different video", "Another Channel", 600),
		)

		harness.feed(
			watching("fctnSdDjxiY", "Sleepwalking", "Bring Me The Horizon", 271_000)
				+ PlaybackEvent.Advance(240_000)
				+ PlaybackEvent.Finalized(),
		)

		// The point of the scenario: the counter is the evidence behind a card
		// that tells the user to re-grant Accessibility, so a bar that spoke must
		// clear it however the id fared afterwards.
		assertEquals(0, harness.consecutiveTracksWithoutVideoId)
		// The refusal itself lands one stage earlier than the finalized
		// corroborator: the latch is checked against the video's own cached page
		// while the track is still playing, so a contradicting page disproves the
		// id there and finalization simply never gets one.
		assertEquals(listOf(RefusalKind.NO_VERIFIED_VIDEO_ID), harness.terminalRefusalKinds)
		assertEquals(listOf(RefusalKind.NO_VERIFIED_VIDEO_ID), harness.unlinkedRefusalKinds)
		// And the row still knows the bar named something, which is what keeps
		// this diagnosable rather than merely refused.
		assertEquals("fctnSdDjxiY", harness.finalized.single().resolverContext.observedVideoId)
	}

	// ---- ads ------------------------------------------------------------------

	@Test
	fun `a visible ad label vetoes the listen whatever the measurement says`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213))

		harness.feed(
			watching("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213_000)
				+ PlaybackEvent.AdLabelObserved("Sponsored · 1 of 2")
				+ PlaybackEvent.Advance(200_000)
				+ PlaybackEvent.Finalized(),
		)

		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(listOf(RefusalKind.EXPLICIT_AD), harness.refusalKinds)
		assertTrue(harness.refusals.single().reason.contains("Sponsored · 1 of 2"))
	}

	// ---- a site that is not YouTube ------------------------------------------

	@Test
	fun `a proven non-YouTube site is never scrobbled and never listed`() {
		val harness = ReplayHarness(ReplaySource.CHROME)

		harness.feed(
			PlaybackEvent.NotificationObserved(host = "soundcloud.com", title = "Some Mix"),
			PlaybackEvent.SessionMetadata(title = "Some Mix", artist = "A DJ", durationMs = 3_600_000),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(3_000_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		// §4.1: not merely unscrobbled — not disclosed in the Not-logged list
		// either, because it is a page this app does not scrobble at all.
		assertEquals(emptyList<RefusalKind>(), harness.refusalKinds)
	}

	// ---- consecutive playback -------------------------------------------------

	@Test
	fun `three consecutive playlist tracks each earn their own transaction`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		val tracks = listOf(
			Triple("aaaaaaaaaaa", "First Song", "A Channel"),
			Triple("bbbbbbbbbbb", "Second Song", "A Channel"),
			Triple("ccccccccccc", "Third Song", "A Channel"),
		)
		tracks.forEach { (id, title, channel) ->
			harness.env.facts.put(facts(id, title, channel, 200))
		}

		tracks.forEach { (id, title, channel) ->
			harness.feed(
				watching(id, title, channel, 200_000, playlistId = "PLtest")
					+ PlaybackEvent.Advance(190_000)
					+ PlaybackEvent.Finalized(),
			)
		}

		assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), harness.broadcasts.map { it.videoId })
		assertEquals(3, harness.transactionIds.toSet().size)
		assertEquals(emptyList<String>(), harness.duplicateAttempts)
	}

	@Test
	fun `each consecutive listen carries its own frozen start timestamp`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts("aaaaaaaaaaa", "First Song", "A Channel", 200))
		harness.env.facts.put(facts("bbbbbbbbbbb", "Second Song", "A Channel", 200))

		harness.feed(
			watching("aaaaaaaaaaa", "First Song", "A Channel", 200_000)
				+ PlaybackEvent.Advance(190_000)
				+ PlaybackEvent.Finalized(),
		)
		harness.feed(
			watching("bbbbbbbbbbb", "Second Song", "A Channel", 200_000)
				+ PlaybackEvent.Advance(190_000)
				+ PlaybackEvent.Finalized(),
		)

		val timestamps = harness.broadcasts.mapNotNull { it.timestamp }
		assertEquals(2, timestamps.size)
		assertEquals(2, timestamps.toSet().size)
		// Two different listens must produce two different dedup claims, or the
		// second one silently disappears.
		assertEquals(2, harness.env.claims.claimed.size)
		assertEquals(emptyList<String>(), harness.duplicateAttempts)
	}

	// ---- accelerated playback -------------------------------------------------

	@Test
	fun `playback at two times speed measures content consumed and not seconds elapsed`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213))

		harness.feed(
			PlaybackEvent.NotificationObserved(host = "youtube.com"),
			PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = "dQw4w9WgXcQ"),
			PlaybackEvent.SessionMetadata(
				title = "Rick Astley - Never Gonna Give You Up",
				artist = "RickAstleyVEVO",
				durationMs = 213_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true, speed = 2.0),
			// Half the wall clock, all of the content.
			PlaybackEvent.Advance(107_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		assertEquals(100, harness.broadcasts.single().percentPlayed)
	}

	// ---- the clock stops in the gap -------------------------------------------

	@Test
	fun `time while paused is not counted as watched`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)

		harness.feed(
			watching("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "RickAstleyVEVO", 213_000)
				+ PlaybackEvent.Advance(60_000)
				+ PlaybackEvent.PlaybackStateChanged(playing = false)
				+ PlaybackEvent.Advance(3_600_000)
				+ PlaybackEvent.PlaybackStateChanged(playing = true)
				+ PlaybackEvent.Advance(20_000)
				+ PlaybackEvent.Finalized(),
		)

		// 80 s of a 213 s video is 38%, whatever the wall clock did in between.
		assertEquals(listOf(RefusalKind.BELOW_THRESHOLD), harness.refusalKinds)
		assertEquals(80L, harness.refusals.single().playedSeconds)
		assertNull(harness.broadcasts.firstOrNull())
	}
}
