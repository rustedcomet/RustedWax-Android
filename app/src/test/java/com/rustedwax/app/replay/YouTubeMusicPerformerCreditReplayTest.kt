package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.youtube.identity.PerformerCreditEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A verified YouTube Music listen scrobbles on the metadata the source published.
 *
 * The artist string is best-effort: whatever YouTube Music published for the id
 * this listen actually resolved. What has to be right is the media identity and
 * the playback — and those are asserted here and throughout the replay suite.
 * MusicBrainz enriches the entry; it neither authorizes nor vetoes it.
 */
class YouTubeMusicPerformerCreditReplayTest : ReplayScenarioTest() {

	private val videoId = "gEoRgInA123"
	private val title = "Georgina"
	private val artist = "Catalog Artist"

	private fun listen() = listOf(
		PlaybackEvent.SessionMetadata(
			title = title,
			artist = artist,
			durationMs = 180_000,
			mediaId = videoId,
		),
		PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
		PlaybackEvent.Advance(170_000),
		PlaybackEvent.Finalized("track change"),
	)

	@Test
	fun `a verified listen is written with the source's own credit`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC).also {
			it.env.facts.put(
				VideoFacts(
					videoId = videoId,
					title = title,
					author = artist,
					lengthSeconds = 180,
					category = "Music",
					watchPageResolved = true,
					isUnlisted = false,
					musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
				),
			)
		}.feed(*listen().toTypedArray())

		// A verified, sufficiently played listen is written using the source's
		// best-effort artist metadata; no separate performer proof is required.
		assertEquals(
			"a verified, sufficiently played listen belongs on the chain",
			listOf("song"),
			harness.broadcasts.map { it.kind },
		)
		assertEquals(
			"credited with what the source published for the id it resolved",
			listOf(artist),
			harness.broadcasts.map { it.artist },
		)
	}

	@Test
	fun `description metadata may enrich the artist without gating the write`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC).also {
			it.env.facts.put(
				VideoFacts(
					videoId = videoId,
					title = title,
					author = "Distributor Uploads",
					originalArtist = artist,
					watchPageArtistCredit = artist,
					lengthSeconds = 180,
					category = "Music",
					watchPageResolved = true,
					isUnlisted = false,
					musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
				),
			)
		}.feed(*listen().toTypedArray())

		assertEquals(listOf(videoId), harness.broadcasts.map { it.videoId })
		assertEquals(artist, harness.broadcasts.single().artist)
	}

	@Test
	fun `a revalidated structured route preserves the source artist metadata`() {
		val structured = VideoResolution(
			videoId = videoId,
			source = "structured native music title+artist+duration",
			title = "$artist - $title",
			channel = artist,
			lengthSeconds = 180,
			uniquelyResolved = true,
			structuredNativeMusic = true,
			presentationDurationCorroborated = true,
			performerCreditEvidence = PerformerCreditEvidence.CANONICAL_PAGE_COMPLETE_CREDIT,
		)
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC).also {
			it.env.facts.put(
				VideoFacts(
					videoId = videoId,
					title = structured.title,
					author = artist,
					lengthSeconds = 180,
					category = "Music",
					watchPageResolved = true,
					isUnlisted = false,
					musicVideoType = "MUSIC_VIDEO_TYPE_UGC",
				),
			)
			it.env.identity.search = { VideoResolutionAttempt(resolution = structured) }
			it.env.identity.structuredMusic = { VideoResolutionAttempt(resolution = structured) }
		}.feed(
			PlaybackEvent.SessionMetadata(
				title = title,
				artist = artist,
				durationMs = 180_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(170_000),
			PlaybackEvent.Finalized("track change"),
		)

		assertEquals(listOf(videoId), harness.broadcasts.map { it.videoId })
		assertEquals(artist, harness.broadcasts.single().artist)
		assertTrue(
			harness.env.identity.calls.contains(
				ReplayIdentitySource.Route.STRUCTURED_MUSIC_REVALIDATION,
			),
		)
	}

	@Test
	fun `an ordinary verified resolver route preserves the source artist metadata`() {
		val ordinary = VideoResolution(
			videoId = videoId,
			source = "title+channel+duration search",
			title = "$artist - $title",
			channel = artist,
			lengthSeconds = 180,
			uniquelyResolved = true,
			presentationDurationCorroborated = true,
			performerCreditEvidence = PerformerCreditEvidence.YOUTUBE_LISTING_COMPLETE_CREDIT,
		)
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC).also {
			it.env.facts.put(
				VideoFacts(
					videoId = videoId,
					title = ordinary.title,
					author = artist,
					lengthSeconds = 180,
					category = "Music",
					watchPageResolved = true,
					isUnlisted = false,
					musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
				),
			)
			it.env.identity.search = { VideoResolutionAttempt(resolution = ordinary) }
		}.feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = 180_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(170_000),
			PlaybackEvent.Finalized("track change"),
		)

		assertEquals(listOf(videoId), harness.broadcasts.map { it.videoId })
		assertEquals(artist, harness.broadcasts.single().artist)
		assertTrue(harness.env.identity.calls.contains(ReplayIdentitySource.Route.SEARCH))
	}

	@Test
	fun `a verified video-row collaboration remains valid on an artist-managed page`() {
		val collaborationId = "cOlLaBoRaT1"
		val collaborationTitle = "Shared Work"
		val collaborationArtist = "Act One & Act Two"
		val videoRow = VideoResolution(
			videoId = collaborationId,
			source = "exact YouTube Music video row over its own artist's channel",
			title = collaborationTitle,
			channel = "Act One Music",
			lengthSeconds = 219,
			uniquelyResolved = true,
			structuredNativeMusic = true,
			creditedArtists = listOf("Act One", "Act Two"),
			musicVideoRow = true,
			presentationDurationCorroborated = true,
			performerCreditEvidence =
				PerformerCreditEvidence.YOUTUBE_MUSIC_CATALOG_AND_CANONICAL_OWNER,
		)
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC).also {
			it.env.facts.put(
				VideoFacts(
					videoId = collaborationId,
					title = "Act One - Shared Work (Official Music Video)",
					author = "Act One Music",
					lengthSeconds = 219,
					category = "Music",
					watchPageResolved = true,
					isUnlisted = false,
					musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
				),
			)
			it.env.identity.search = { VideoResolutionAttempt(resolution = videoRow) }
			it.env.identity.structuredMusic = { VideoResolutionAttempt(resolution = videoRow) }
		}.feed(
			PlaybackEvent.SessionMetadata(
				title = collaborationTitle,
				artist = collaborationArtist,
				durationMs = 219_000,
			),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(187_000),
			PlaybackEvent.Finalized("track change"),
		)

		assertEquals(listOf(collaborationId), harness.broadcasts.map { it.videoId })
		assertEquals(collaborationArtist, harness.broadcasts.single().artist)
		assertTrue(
			harness.env.identity.calls.contains(
				ReplayIdentitySource.Route.MUSIC_VIDEO_ROW_REVALIDATION,
			),
		)
	}

	@Test
	fun `a verified video row on a distributor upload resolves and broadcasts`() {
		val distributorId = "dIsTrIbUt12"
		val videoRow = VideoResolution(
			videoId = distributorId,
			source = "exact YouTube Music video work+artist+duration",
			title = title,
			channel = "Distributor Uploads",
			lengthSeconds = 180,
			uniquelyResolved = true,
			structuredNativeMusic = true,
			creditedArtists = listOf(artist),
			musicVideoRow = true,
			presentationDurationCorroborated = true,
		)
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC).also {
			it.env.facts.put(
				VideoFacts(
					videoId = distributorId,
					title = "$artist - $title (Official Video)",
					author = "Distributor Uploads",
					lengthSeconds = 180,
					category = "Music",
					watchPageResolved = true,
					isUnlisted = false,
					musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
				),
			)
			it.env.identity.search = { VideoResolutionAttempt(resolution = videoRow) }
			it.env.identity.structuredMusic = { VideoResolutionAttempt(resolution = videoRow) }
		}.feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = 180_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(170_000),
			PlaybackEvent.Finalized("track change"),
		)

		assertEquals(listOf(distributorId), harness.broadcasts.map { it.videoId })
		assertEquals(artist, harness.broadcasts.single().artist)
		assertEquals(1, harness.broadcasts.size)
		assertTrue(
			harness.env.identity.calls.contains(
				ReplayIdentitySource.Route.MUSIC_VIDEO_ROW_REVALIDATION,
			),
		)
	}
}
