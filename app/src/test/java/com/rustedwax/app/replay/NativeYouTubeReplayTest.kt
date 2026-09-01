package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.app.replay.ReplayHarness.RefusalKind
import com.rustedwax.app.replay.ReplayIdentitySource.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native YouTube app and YouTube Music, replayed from the MediaSession.
 *
 * Native playback has no address bar, so identity comes from the MediaSession's
 * own id fields when it publishes one and from the resolver chain when it does
 * not. Every historical failure encoded here was a *correct* id being thrown
 * away by a corroboration rule — the VEVO byline, the auto-translated title, the
 * carry route that stopped revalidating — which is why the assertions are on
 * what was broadcast rather than on which route ran.
 */
class NativeYouTubeReplayTest : ReplayScenarioTest() {

	private fun facts(
		videoId: String,
		title: String,
		author: String,
		lengthSeconds: Long,
		category: String = "Music",
		ownerHandle: String? = null,
	) = VideoFacts(
		videoId = videoId,
		title = title,
		author = author,
		ownerHandle = ownerHandle,
		lengthSeconds = lengthSeconds,
		category = category,
		watchPageResolved = true,
		isUnlisted = false,
	)

	// ---- the exact-id path ---------------------------------------------------

	@Test
	fun `a native media id identifies the video with no lookup at all`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(facts("kJQP7kiw5Fk", "Luis Fonsi - Despacito ft. Daddy Yankee", "LuisFonsiVEVO", 282))

		harness.feed(
			PlaybackEvent.SessionMetadata(
				title = "Luis Fonsi - Despacito ft. Daddy Yankee",
				artist = "LuisFonsiVEVO",
				durationMs = 282_000,
				mediaId = "kJQP7kiw5Fk",
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(260_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		assertEquals("kJQP7kiw5Fk", harness.broadcasts.single().videoId)
		// The frozen exact id is its own authority; no resolver route may run.
		assertEquals(emptyList<Route>(), harness.identityRoutes)
	}

	@Test
	fun `a media URI identifies the video the same way a media id does`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(facts("kJQP7kiw5Fk", "Luis Fonsi - Despacito", "LuisFonsiVEVO", 282))

		harness.feed(
			PlaybackEvent.SessionMetadata(
				title = "Luis Fonsi - Despacito",
				artist = "LuisFonsiVEVO",
				durationMs = 282_000,
				mediaUri = "https://www.youtube.com/watch?v=kJQP7kiw5Fk",
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(260_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals("kJQP7kiw5Fk", harness.broadcasts.single().videoId)
	}

	@Test
	fun `ordinary native playback remains measurable when a PiP observation arrives`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(
			facts("kJQP7kiw5Fk", "Luis Fonsi - Despacito", "LuisFonsiVEVO", 282),
		)

		harness.feed(
			PlaybackEvent.SessionMetadata(
				title = "Luis Fonsi - Despacito",
				artist = "LuisFonsiVEVO",
				durationMs = 282_000,
				mediaId = "kJQP7kiw5Fk",
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(130_000),
			PlaybackEvent.SurfaceChanged(PlaybackEvent.Surface.PICTURE_IN_PICTURE),
			PlaybackEvent.Advance(130_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.finalized.size)
		assertEquals(listOf("kJQP7kiw5Fk"), harness.broadcasts.map { it.videoId })
		assertEquals(emptyList<RefusalKind>(), harness.refusalKinds)
	}

	// ---- the VEVO alias ------------------------------------------------------

	@Test
	fun `a VEVO channel alias does not refuse an id the route already proved`() {
		// Measured 2026-08-09: 46 correct ids refused in one day because the
		// MediaSession names the artist and the watch page names the label's
		// channel. No rule normalises "BMTHOfficialVEVO" into "Bring Me The
		// Horizon", and none should — the id is what licenses the alias.
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(facts("UNaYpBpRJOY", "Avalanche (Official Video)", "Bring Me The Horizon", 275))
		harness.env.identity.search = {
			VideoResolutionAttempt(
				resolution = VideoResolution(
					videoId = "UNaYpBpRJOY",
					source = "search",
					title = "Bring Me The Horizon - Avalanche (Official Video)",
					channel = "BMTHOfficialVEVO",
					lengthSeconds = 275,
					uniquelyResolved = true,
				),
			)
		}

		harness.feed(
			PlaybackEvent.SessionMetadata(
				title = "Bring Me The Horizon - Avalanche (Official Video)",
				artist = "BMTHOfficialVEVO",
				durationMs = 275_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(260_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		assertEquals("UNaYpBpRJOY", harness.broadcasts.single().videoId)
		assertEquals(emptyList<RefusalKind>(), harness.refusalKinds)
	}

	// ---- the auto-translated title -------------------------------------------

	@Test
	fun `a title YouTube auto-translated is the same video and not a second candidate`() {
		// Measured 2026-08-05: the resolver found the video correctly and the
		// corroborator threw it away because the screen showed the Spanish
		// rendering while `videoDetails` kept the uploaded English one.
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(
			facts("QnRnooyKeZk", "The Day Karol G Experienced Something New", "A Channel", 610),
		)
		harness.env.identity.search = {
			VideoResolutionAttempt(
				resolution = VideoResolution(
					videoId = "QnRnooyKeZk",
					source = "search",
					title = "The Day Karol G Experienced Something New",
					channel = "A Channel",
					lengthSeconds = 610,
					uniquelyResolved = true,
					localizedTitle = "El Día que Karol G Vivió Algo Nuevo",
				),
			)
		}

		harness.feed(
			PlaybackEvent.SessionMetadata(
				title = "El Día que Karol G Vivió Algo Nuevo",
				artist = "A Channel",
				durationMs = 610_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(500_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		assertEquals("QnRnooyKeZk", harness.broadcasts.single().videoId)
	}

	// ---- playlists and Mixes -------------------------------------------------

	@Test
	fun `a native playlist name is resolved to an id and then names the video`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(facts("7J6xA1_f8as", "Te Busco", "Cosculluela - Topic", 214))
		harness.env.identity.nativePlaylistId = { name ->
			if (name == "Reggaeton Classics") "PL_reggaeton" else null
		}
		harness.env.identity.fromPlaylist = { list, title ->
			if (list == "PL_reggaeton" && title == "Te Busco") {
				VideoResolution(
					videoId = "7J6xA1_f8as",
					source = "playlist",
					title = "Te Busco",
					channel = "Cosculluela El Principe",
					lengthSeconds = 214,
					uniquelyResolved = true,
					playlistVerified = true,
				)
			} else {
				null
			}
		}

		harness.feed(
			PlaybackEvent.NativePlaylistObserved("Reggaeton Classics", owner = "Someone", total = 40),
			PlaybackEvent.SessionMetadata(
				title = "Te Busco",
				artist = "Cosculluela El Principe",
				durationMs = 214_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(200_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals("7J6xA1_f8as", harness.broadcasts.single().videoId)
		assertEquals(listOf(Route.NATIVE_PLAYLIST_ID, Route.PLAYLIST), harness.identityRoutes)
	}

	@Test
	fun `a Mix queue resolves the same way a playlist does`() {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts("mIxVideoIdA", "Some Mix Track", "Mix Channel", 190))
		harness.env.identity.fromPlaylist = { list, _ ->
			if (list.startsWith("RD")) {
				VideoResolution(
					videoId = "mIxVideoIdA",
					source = "mix queue",
					title = "Some Mix Track",
					channel = "Mix Channel",
					lengthSeconds = 190,
					uniquelyResolved = true,
					playlistVerified = true,
				)
			} else {
				null
			}
		}

		harness.feed(
			PlaybackEvent.NotificationObserved(host = "youtube.com"),
			// A Mix that named its list but never named this individual video.
			PlaybackEvent.UrlObserved(host = "www.youtube.com", playlistId = "RDsomeMixSeed"),
			PlaybackEvent.SessionMetadata(
				title = "Some Mix Track",
				artist = "Mix Channel",
				durationMs = 190_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(180_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals("mIxVideoIdA", harness.broadcasts.single().videoId)
		assertTrue(Route.PLAYLIST in harness.identityRoutes)
	}

	// ---- the pre-resolved carry ----------------------------------------------

	@Test
	fun `a playlist carry that still revalidates keeps its id`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(facts("7J6xA1_f8as", "Te Busco", "Cosculluela - Topic", 214))
		harness.env.identity.nativePlaylistId = { "PL_reggaeton" }
		harness.env.identity.fromPlaylist = { _, _ ->
			VideoResolution(
				videoId = "7J6xA1_f8as",
				source = "playlist",
				title = "Te Busco",
				channel = "Cosculluela El Principe",
				lengthSeconds = 214,
				uniquelyResolved = true,
				playlistVerified = true,
			)
		}

		harness.feed(
			PlaybackEvent.NativePlaylistObserved("Reggaeton Classics"),
			PlaybackEvent.SessionMetadata(
				title = "Te Busco",
				artist = "Cosculluela El Principe",
				durationMs = 214_000,
			),
			PlaybackEvent.NativeIdentityPreResolved("7J6xA1_f8as", PlaybackTrace.PLAYLIST_ROUTE),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(200_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals("7J6xA1_f8as", harness.broadcasts.single().videoId)
	}

	@Test
	fun `a carry that no longer revalidates falls through instead of losing the listen`() {
		// Measured 2026-08-06: "Nicki Minaj - Barbie Tingz" had its id named by
		// watch history during playback; at finalize the carry route refused and
		// returned, so history was never asked again and a listen it could still
		// identify was thrown away.
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(facts("BarbieTing1", "Nicki Minaj - Barbie Tingz", "NickiMinajAtVEVO", 200))
		harness.env.watchHistory.hasSession = true
		harness.env.watchHistory.revalidation = {
			VideoResolutionAttempt(refusalReason = "watch history has since re-described this listen")
		}
		harness.env.watchHistory.evidence = {
			VideoResolutionAttempt(
				resolution = VideoResolution(
					videoId = "BarbieTing1",
					source = "watch history",
					title = "Nicki Minaj - Barbie Tingz",
					channel = "NickiMinajAtVEVO",
					lengthSeconds = 200,
					uniquelyResolved = true,
					historyVerified = true,
				),
			)
		}

		harness.feed(
			PlaybackEvent.SessionMetadata(
				title = "Nicki Minaj - Barbie Tingz",
				artist = "NickiMinajAtVEVO",
				durationMs = 200_000,
			),
			PlaybackEvent.NativeIdentityPreResolved("BarbieTing1", PlaybackTrace.HISTORY_ROUTE),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(190_000),
			PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		assertEquals("BarbieTing1", harness.broadcasts.single().videoId)
		assertEquals(emptyList<RefusalKind>(), harness.refusalKinds)
	}

	@Test
	fun `a playlist carry that now resolves to a different id refuses rather than guessing`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.identity.nativePlaylistId = { "PL_reggaeton" }
		harness.env.identity.fromPlaylist = { _, _ ->
			VideoResolution(
				videoId = "somethingels",
				source = "playlist",
				title = "Te Busco",
				channel = "Cosculluela El Principe",
				lengthSeconds = 214,
				uniquelyResolved = true,
				playlistVerified = true,
			)
		}

		harness.feed(
			PlaybackEvent.NativePlaylistObserved("Reggaeton Classics"),
			PlaybackEvent.SessionMetadata(
				title = "Te Busco",
				artist = "Cosculluela El Principe",
				durationMs = 214_000,
			),
			PlaybackEvent.NativeIdentityPreResolved("7J6xA1_f8as", PlaybackTrace.PLAYLIST_ROUTE),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(200_000),
			PlaybackEvent.Finalized(),
		)

		// It may fall through to the other routes, but it must not broadcast the
		// carried id on the strength of a playlist that now disagrees.
		assertFalse(harness.broadcasts.any { it.videoId == "7J6xA1_f8as" })
	}

	// ---- YouTube Music -------------------------------------------------------

	@Test
	fun `YouTube Music is trusted for the artist field where the YouTube app is not`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC)
		harness.env.facts.put(
			VideoFacts(
				videoId = "musicVideoA",
				title = "Bohemian Rhapsody",
				author = "Queen - Topic",
				lengthSeconds = 355,
				category = "Music",
				watchPageResolved = true,
				isUnlisted = false,
				musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
			),
		)

		harness.feed(
			PlaybackEvent.SessionMetadata(
				title = "Bohemian Rhapsody",
				artist = "Queen",
				album = "A Night At The Opera",
				durationMs = 355_000,
				mediaId = "musicVideoA",
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(340_000),
			PlaybackEvent.Finalized(),
		)

		val payload = harness.broadcasts.single()
		assertEquals("song", payload.kind)
		// The ARTIST field is preserved verbatim rather than parsed out of the
		// title, which is the one capability `SourceProfile` actually grants.
		assertEquals("Queen", payload.artist)
		assertEquals("Bohemian Rhapsody", payload.title)
		assertEquals("A Night At The Opera", payload.album)
	}

	// ---- the opt-out boundary ------------------------------------------------

	@Test
	fun `a native listen finalized after its opt-out boundary is discarded entirely`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(facts("kJQP7kiw5Fk", "Luis Fonsi - Despacito", "LuisFonsiVEVO", 282))

		harness.feed(
			PlaybackEvent.SessionMetadata(
				title = "Luis Fonsi - Despacito",
				artist = "LuisFonsiVEVO",
				durationMs = 282_000,
				mediaId = "kJQP7kiw5Fk",
			),
			PlaybackEvent.PlaybackStateChanged(playing = true),
			PlaybackEvent.Advance(260_000),
		)
		// The user turns YouTube scrobbling off while the track is still open.
		com.rustedwax.app.detect.NativeSourceSwitches.configureForReplay(
			com.rustedwax.app.detect.NativeSourceSwitches.Config(
				youTubeScrobbling = false,
				youtubeEnabled = false,
				youtubeMusicEnabled = false,
			),
		)
		harness.feed(PlaybackEvent.Finalized())

		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
		// Discarded, not refused: an opt-out must not become a way to *cause* a
		// Not-logged row about a listen the user asked us to forget.
		assertEquals(emptyList<RefusalKind>(), harness.refusalKinds)
	}
}
