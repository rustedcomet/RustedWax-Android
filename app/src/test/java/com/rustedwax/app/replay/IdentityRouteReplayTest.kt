package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.youtube.identity.VideoResolutionFailure
import com.rustedwax.app.enrich.VerifiedIdentityCandidateCache
import com.rustedwax.app.replay.ReplayHarness.RefusalKind
import com.rustedwax.app.replay.ReplayIdentitySource.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityRouteReplayTest : ReplayScenarioTest() {

	private fun facts(videoId: String, title: String, author: String, lengthSeconds: Long) =
		VideoFacts(
			videoId = videoId,
			title = title,
			author = author,
			lengthSeconds = lengthSeconds,
			category = "Music",
			watchPageResolved = true,
			isUnlisted = false,
		)

	private fun resolution(
		videoId: String,
		title: String,
		channel: String,
		lengthSeconds: Long,
		source: String,
	) = VideoResolution(
		videoId = videoId,
		source = source,
		title = title,
		channel = channel,
		lengthSeconds = lengthSeconds,
		uniquelyResolved = true,
	)

	private fun playing(title: String, channel: String, durationMs: Long) = listOf(
		PlaybackEvent.SessionMetadata(title = title, artist = channel, durationMs = durationMs),
		PlaybackEvent.PlaybackStateChanged(playing = true),
		PlaybackEvent.Advance((durationMs * 0.9).toLong()),
		PlaybackEvent.Finalized(),
	)

	// ---- ordering -------------------------------------------------------------

	@Test
	fun `the playlist is asked before watch history and history before search`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(facts("playlistVid", "Track One", "A Channel", 200))
		harness.env.watchHistory.hasSession = true
		harness.env.identity.nativePlaylistId = { "PL_ordering" }
		harness.env.identity.fromPlaylist = { _, _ ->
			resolution("playlistVid", "Track One", "A Channel", 200, "playlist")
		}
		harness.env.watchHistory.evidence = {
			error("history must not be consulted once the playlist has answered")
		}

		harness.feed(
			listOf(PlaybackEvent.NativePlaylistObserved("Some List")) +
				playing("Track One", "A Channel", 200_000),
		)

		assertEquals("playlistVid", harness.broadcasts.single().videoId)
		assertEquals(listOf(Route.NATIVE_PLAYLIST_ID, Route.PLAYLIST), harness.identityRoutes)
	}

	@Test
	fun `watch history answers before search, which has been measured choosing wrong`() {
		// §10.1: search picked a duration-identical wrong upload. History names
		// what this account actually played, so it goes first.
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(facts("historyVidA", "Track Two", "A Channel", 200))
		harness.env.watchHistory.hasSession = true
		harness.env.watchHistory.evidence = {
			VideoResolutionAttempt(
				resolution = resolution("historyVidA", "Track Two", "A Channel", 200, "watch history"),
			)
		}
		harness.env.identity.search = { error("search must not run once history has answered") }

		harness.feed(playing("Track Two", "A Channel", 200_000))

		assertEquals("historyVidA", harness.broadcasts.single().videoId)
		assertFalse(Route.SEARCH in harness.identityRoutes)
	}

	@Test
	fun `search runs only once every bounded route has come up empty`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(facts("searchedVid", "Track Three", "A Channel", 200))
		harness.env.watchHistory.hasSession = true
		harness.env.identity.nativePlaylistId = { "PL_empty" }
		harness.env.identity.search = {
			VideoResolutionAttempt(resolution = resolution("searchedVid", "Track Three", "A Channel", 200, "search"))
		}

		harness.feed(
			listOf(PlaybackEvent.NativePlaylistObserved("Some List")) +
				playing("Track Three", "A Channel", 200_000),
		)

		assertEquals("searchedVid", harness.broadcasts.single().videoId)
		assertEquals(
			listOf(Route.NATIVE_PLAYLIST_ID, Route.PLAYLIST, Route.SEARCH),
			harness.identityRoutes,
		)
	}

	// ---- adjacency recovery ---------------------------------------------------

	@Test
	fun `two verified predecessors recover the playlist for a third unidentified track`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts("firstVideoA", "First", "A Channel", 200))
		harness.env.facts.put(facts("secondVideo", "Second", "A Channel", 200))
		harness.env.facts.put(facts("thirdVideoC", "Third", "A Channel", 200))
		harness.env.identity.fromPredecessors = { previous, title ->
			if (previous == "secondVideo" && title == "Third") {
				resolution("thirdVideoC", "Third", "A Channel", 200, "adjacent predecessors")
			} else {
				null
			}
		}

		listOf("firstVideoA" to "First", "secondVideo" to "Second").forEach { (id, title) ->
			harness.feed(
				listOf(
					PlaybackEvent.NotificationObserved(host = "youtube.com"),
					PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = id),
				) + playing(title, "A Channel", 200_000),
			)
		}

		// The browser is backgrounded; the bar never names the third video.
		harness.feed(
			listOf(PlaybackEvent.NotificationObserved(host = "youtube.com")) +
				playing("Third", "A Channel", 200_000),
		)

		assertEquals(
			listOf("firstVideoA", "secondVideo", "thirdVideoC"),
			harness.broadcasts.map { it.videoId },
		)
		assertTrue(Route.ADJACENT_PREDECESSORS in harness.identityRoutes)
	}

	@Test
	fun `Ethereum 2_0 is recovered after the exact 2Pac predecessor pair`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(
			facts("8RddqlctLnk", "2pac - When thugs Cry", "2Pac", 240),
			facts("FGjyRjd1_jQ", "2pac When We Ride On Our Enemies", "2Pac", 240),
			facts("4ZnHHd3i8I4", "Tupac heartz of men", "Ethereum 2.0", 284),
		)
		harness.env.identity.fromPredecessors = { previous, title ->
			if (previous == "FGjyRjd1_jQ" && title == "Tupac heartz of men") {
				resolution(
					"4ZnHHd3i8I4",
					"Tupac heartz of men",
					"Ethereum 2.0",
					284,
					"adjacent verified predecessors",
				)
			} else {
				null
			}
		}

		listOf(
			Triple("8RddqlctLnk", "2pac - When thugs Cry", "2Pac"),
			Triple("FGjyRjd1_jQ", "2pac When We Ride On Our Enemies", "2Pac"),
		).forEach { (id, title, channel) ->
			harness.feed(
				PlaybackEvent.NotificationObserved(host = "youtube.com"),
				PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = id),
				*playing(title, channel, 240_000).toTypedArray(),
			)
		}

		// Brave is off screen and the current id is absent from both the bar and
		// history/search. The ordered predecessor route is the recorded recovery.
		harness.feed(
			PlaybackEvent.NotificationObserved(host = "youtube.com"),
			*playing("Tupac heartz of men", "Ethereum 2.0", 284_000).toTypedArray(),
		)

		assertEquals(
			listOf("8RddqlctLnk", "FGjyRjd1_jQ", "4ZnHHd3i8I4"),
			harness.broadcasts.map { it.videoId },
		)
		assertEquals(emptyList<RefusalKind>(), harness.refusalKinds)
		assertTrue(Route.ADJACENT_PREDECESSORS in harness.identityRoutes)
	}

	@Test
	fun `an unresolved middle track breaks adjacency instead of letting two older ids stand in`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts("firstVideoA", "First", "A Channel", 200))
		harness.env.facts.put(facts("thirdVideoC", "Third", "A Channel", 200))
		harness.env.identity.fromPredecessors = { _, _ ->
			error("adjacency must not be offered a pair that skips an unresolved listen")
		}

		harness.feed(
			listOf(
				PlaybackEvent.NotificationObserved(host = "youtube.com"),
				PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = "firstVideoA"),
			) + playing("First", "A Channel", 200_000),
		)
		// The middle listen finalizes and never resolves. It is still a boundary.
		harness.feed(
			listOf(PlaybackEvent.NotificationObserved(host = "youtube.com")) +
				playing("Unidentifiable Middle", "A Channel", 200_000),
		)
		harness.feed(
			listOf(PlaybackEvent.NotificationObserved(host = "youtube.com")) +
				playing("Third", "A Channel", 200_000),
		)

		assertEquals(listOf("firstVideoA"), harness.broadcasts.map { it.videoId })
		assertEquals(
			listOf(RefusalKind.NO_VERIFIED_VIDEO_ID, RefusalKind.NO_VERIFIED_VIDEO_ID),
			harness.terminalRefusalKinds,
		)
		assertEquals(emptyList<ReplayHarness.Refusal>(), harness.refusals)
	}

	// ---- temporary failure ----------------------------------------------------

	@Test
	fun `ambiguous run-local candidates stop before playlist history and search`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		VerifiedIdentityCandidateCache.remember(
			packageName = "com.google.android.youtube",
			videoId = "candidateA1",
			title = "Ambiguous Local",
			channel = "A Channel",
			durationMs = 200_000,
			ownerHandle = null,
		)
		harness.env.identity.verifiedCandidates = {
			VideoResolutionAttempt(
				refusalReason = "ambiguous identity — two run-local candidates",
				failure = VideoResolutionFailure.AMBIGUOUS,
			)
		}
		harness.env.identity.search = { error("search must not run after ambiguity") }

		harness.feed(playing("Ambiguous Local", "A Channel", 200_000))

		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(listOf(Route.VERIFIED_CANDIDATES), harness.identityRoutes)
		assertTrue(harness.terminalRefusalReasons.single().contains("ambiguous identity"))
	}

	@Test
	fun `ambiguous observed playlist stops before history and search`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.watchHistory.hasSession = true
		harness.env.identity.nativePlaylistId = { "PL_ambiguous" }
		harness.env.identity.playlistAttempt = { _, _ ->
			VideoResolutionAttempt(
				refusalReason = "ambiguous playlist identity — two entries matched",
				failure = VideoResolutionFailure.AMBIGUOUS,
			)
		}
		harness.env.watchHistory.evidence = { error("history must not run after playlist ambiguity") }
		harness.env.identity.search = { error("search must not run after playlist ambiguity") }

		harness.feed(
			listOf(PlaybackEvent.NativePlaylistObserved("Ambiguous List")) +
				playing("Ambiguous Playlist Track", "A Channel", 200_000),
		)

		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(listOf(Route.NATIVE_PLAYLIST_ID, Route.PLAYLIST), harness.identityRoutes)
		assertTrue(harness.terminalRefusalReasons.single().contains("ambiguous playlist identity"))
	}

	@Test
	fun `ambiguous watch history stops before search`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.watchHistory.hasSession = true
		harness.env.watchHistory.evidence = {
			VideoResolutionAttempt(
				refusalReason = "ambiguous identity — two history rows matched",
				failure = VideoResolutionFailure.AMBIGUOUS,
			)
		}
		harness.env.identity.search = { error("search must not run after history ambiguity") }

		harness.feed(playing("Ambiguous History", "A Channel", 200_000))

		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertFalse(Route.SEARCH in harness.identityRoutes)
		assertTrue(harness.terminalRefusalReasons.single().contains("ambiguous identity"))
	}

	@Test
	fun `temporary watch-history failure falls through to ordinary search`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.watchHistory.hasSession = true
		harness.env.watchHistory.evidence = {
			VideoResolutionAttempt(
				refusalReason = "history network temporarily unavailable",
				failure = VideoResolutionFailure.TEMPORARY_FAILURE,
			)
		}
		harness.env.facts.put(facts("histTmpSrch", "History Temporary", "A Channel", 200))
		harness.env.identity.search = {
			VideoResolutionAttempt(
				resolution = resolution(
					"histTmpSrch", "History Temporary", "A Channel", 200, "search",
				),
			)
		}

		harness.feed(playing("History Temporary", "A Channel", 200_000))

		assertEquals(
			"terminal refusals=${harness.terminalRefusalReasons}; routes=${harness.identityRoutes}",
			"histTmpSrch",
			harness.broadcasts.singleOrNull()?.videoId,
		)
		assertTrue(Route.SEARCH in harness.identityRoutes)
	}

	@Test
	fun `a resolver that throws refuses this listen and does not poison the next`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(facts("recoveredId", "Track Five", "A Channel", 200))
		var firstAttempt = true
		harness.env.identity.search = { title ->
			if (firstAttempt) {
				firstAttempt = false
				throw java.io.IOException("the network went away")
			}
			VideoResolutionAttempt(resolution = resolution("recoveredId", title, "A Channel", 200, "search"))
		}

		harness.feed(playing("Track Five", "A Channel", 200_000))
		assertEquals(listOf(RefusalKind.NO_VERIFIED_VIDEO_ID), harness.terminalRefusalKinds)
		assertTrue(harness.terminalRefusalReasons.single().contains("resolver failed"))
		assertEquals(emptyList<ReplayHarness.Refusal>(), harness.refusals)

		harness.feed(playing("Track Five", "A Channel", 200_000))
		assertEquals(1, harness.broadcasts.size)
		assertEquals("recoveredId", harness.broadcasts.single().videoId)
	}

	@Test
	fun `enrichment failing after a proven id still scrobbles, because identity was not the question`() {
		// Decision D8: enrichment is advisory and non-blocking. A frozen exact id
		// is its own authority, so a watch page that timed out costs the payload
		// its category, not the listen.
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.failures += "provenIdAaa"

		harness.feed(
			listOf(
				PlaybackEvent.NotificationObserved(host = "youtube.com"),
				PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = "provenIdAaa"),
			) + playing("A Proven Track", "A Channel", 200_000),
		)

		assertEquals(1, harness.broadcasts.size)
		assertEquals("provenIdAaa", harness.broadcasts.single().videoId)
	}

	@Test
	fun `watch history disconnected mid-listen refuses the carry it can no longer re-verify`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.watchHistory.hasSession = false

		harness.feed(
			listOf(
				PlaybackEvent.SessionMetadata(
					title = "Track Six",
					artist = "A Channel",
					durationMs = 200_000,
				),
				PlaybackEvent.NativeIdentityPreResolved("carriedIdAa", PlaybackTrace.HISTORY_ROUTE),
			) + playing("Track Six", "A Channel", 200_000),
		)

		assertFalse(harness.broadcasts.any { it.videoId == "carriedIdAa" })
		assertEquals(listOf(RefusalKind.NO_VERIFIED_VIDEO_ID), harness.terminalRefusalKinds)
		assertEquals(emptyList<ReplayHarness.Refusal>(), harness.refusals)
	}

	// ---- the identity contract ------------------------------------------------

	@Test
	fun `a resolver id that replaces a frozen one is refused by the identity contract`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		// A frozen exact id makes the resolver irrelevant, so this asserts the
		// other half: a route that answers with a *different* id than the one the
		// bar proved never reaches the chain.
		harness.env.facts.put(facts("frozenIdAaa", "A Frozen Track", "A Channel", 200))
		harness.env.identity.search = {
			VideoResolutionAttempt(resolution = resolution("otherIdBbbb", "A Frozen Track", "A Channel", 200, "search"))
		}

		harness.feed(
			listOf(
				PlaybackEvent.NotificationObserved(host = "youtube.com"),
				PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = "frozenIdAaa"),
			) + playing("A Frozen Track", "A Channel", 200_000),
		)

		assertEquals(listOf("frozenIdAaa"), harness.broadcasts.map { it.videoId })
	}

	@Test
	fun `a candidate that is not uniquely resolved cannot establish identity`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(facts("ambiguousId", "Track Seven", "A Channel", 200))
		harness.env.identity.search = {
			VideoResolutionAttempt(
				resolution = VideoResolution(
					videoId = "ambiguousId",
					source = "search",
					title = "Track Seven",
					channel = "A Channel",
					lengthSeconds = 200,
					// Two uploads matched; the resolver declined to choose.
					uniquelyResolved = false,
				),
			)
		}

		harness.feed(playing("Track Seven", "A Channel", 200_000))

		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(listOf(RefusalKind.NO_VERIFIED_VIDEO_ID), harness.terminalRefusalKinds)
		assertTrue(
			harness.terminalRefusalReasons.single().contains("without a unique finalized-track match"),
		)
		assertEquals(emptyList<ReplayHarness.Refusal>(), harness.refusals)
	}

	@Test
	fun `a resolved candidate whose duration contradicts the session is refused`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(facts("wrongLength", "Track Eight", "A Channel", 900))
		harness.env.identity.search = {
			VideoResolutionAttempt(resolution = resolution("wrongLength", "Track Eight", "A Channel", 900, "search"))
		}

		harness.feed(playing("Track Eight", "A Channel", 200_000))

		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		assertEquals(listOf(RefusalKind.IDENTITY_CONTRADICTION), harness.terminalRefusalKinds)
		assertEquals(emptyList<ReplayHarness.Refusal>(), harness.refusals)
	}

	// ---- lookups turned off ---------------------------------------------------

	@Test
	fun `with lookups off a track the bar named still scrobbles and one it did not does not`() {
		val proven = ReplayHarness(
			ReplaySource.BRAVE,
			ReplayEnvironment(policy = ReplayPolicy(enrichment = false)),
		)
		proven.feed(
			listOf(
				PlaybackEvent.NotificationObserved(host = "youtube.com"),
				PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = "barNamedIdA"),
			) + playing("A Named Track", "A Channel", 200_000),
		)
		assertEquals(listOf("barNamedIdA"), proven.broadcasts.map { it.videoId })
		assertEquals(emptyList<String>(), proven.env.facts.resolved)

		val unproven = ReplayHarness(
			ReplaySource.BRAVE,
			ReplayEnvironment(policy = ReplayPolicy(enrichment = false)),
		)
		unproven.feed(
			listOf(PlaybackEvent.NotificationObserved(host = "youtube.com")) +
				playing("An Unnamed Track", "A Channel", 200_000),
		)
		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), unproven.broadcasts)
		assertTrue(unproven.terminalRefusalReasons.single().contains("video lookup is disabled"))
		assertEquals(emptyList<ReplayHarness.Refusal>(), unproven.refusals)
		assertEquals(emptyList<Route>(), unproven.identityRoutes)
	}
}
