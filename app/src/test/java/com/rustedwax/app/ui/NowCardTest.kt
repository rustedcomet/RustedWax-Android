package com.rustedwax.app.ui

import com.rustedwax.app.detect.ResolverContext
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.hive.HiveScrobblePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the Now card is allowed to say.
 *
 * The card used to be the diagnostics screen: package name, source proof, video
 * id, resolver route, browser/observer coverage, MusicBrainz, the kind reason,
 * the payload preview and a monospace dump of the raw MediaSession metadata.
 * All of that is evidence about *this app*, and none of it answers the question
 * a person opening the app has, which is "what is playing and will it count".
 *
 * The model is asserted here rather than left inside the composable for the same
 * reason the settings order is: it is a product decision about what a person is
 * shown, and a decision nothing can assert is one that drifts back.
 */
class NowCardTest {

	@Test
	fun `the platform is named, not the package`() {
		assertEquals(NowCard.Platform.YOUTUBE, NowCard.platformFor("com.google.android.youtube"))
		assertEquals(
			NowCard.Platform.YOUTUBE_MUSIC,
			NowCard.platformFor("com.google.android.apps.youtube.music"),
		)
		assertEquals(NowCard.Platform.BRAVE, NowCard.platformFor("com.brave.browser"))
		assertEquals(NowCard.Platform.BRAVE, NowCard.platformFor("com.brave.browser_beta"))
		assertEquals(NowCard.Platform.CHROME, NowCard.platformFor("com.android.chrome"))
		assertEquals(NowCard.Platform.CHROME, NowCard.platformFor("com.chrome.beta"))
		assertEquals("YouTube Music", NowCard.Platform.YOUTUBE_MUSIC.label)
		assertEquals("Brave", NowCard.Platform.BRAVE.label)
	}

	/** Anything else is still named something a person can read. */
	@Test
	fun `an unknown package still gets a readable platform`() {
		assertEquals(NowCard.Platform.OTHER, NowCard.platformFor("com.example.player"))
	}

	@Test
	fun `the category is the payload kind in the words people use`() {
		assertEquals("Song", NowCard.categoryLabel(HiveScrobblePayload.KIND_SONG))
		assertEquals("Video", NowCard.categoryLabel(HiveScrobblePayload.KIND_VIDEO))
		assertEquals("Movie", NowCard.categoryLabel(HiveScrobblePayload.KIND_MOVIE))
		assertEquals("Episode", NowCard.categoryLabel(HiveScrobblePayload.KIND_EPISODE))
		assertEquals("Podcast", NowCard.categoryLabel(HiveScrobblePayload.KIND_PODCAST))
		assertNull("an unknown kind must not be invented", NowCard.categoryLabel(null))
		assertNull(NowCard.categoryLabel("something-new"))
	}

	@Test
	fun `a complete session reads as a card and nothing else`() {
		val card = NowCard.from(
			session(title = "Dear Jessie", artist = "Madonna", durationMs = 275_690, playedMs = 30_000),
			durationMs = 275_690,
			identified = true,
			kind = HiveScrobblePayload.KIND_SONG,
			thresholdPercent = 60,
			autoScrobble = true,
		)

		assertEquals(NowCard.Platform.YOUTUBE_MUSIC, card.platform)
		assertEquals("Madonna", card.channel)
		assertEquals("Dear Jessie", card.title)
		assertEquals("4:35", card.durationText)
		assertEquals("Song", card.category)
		assertEquals("11%", card.percentText)
		assertEquals("Scrobbles at 60% played", card.status)
	}

	@Test
	fun `progress is the fraction the threshold is judged on`() {
		val card = NowCard.from(
			session(title = "t", artist = "a", durationMs = 200_000, playedMs = 100_000),
			durationMs = 200_000,
			identified = true,
			kind = HiveScrobblePayload.KIND_VIDEO,
			thresholdPercent = 60,
			autoScrobble = true,
		)
		assertEquals(0.5f, card.progress!!, 0.001f)
		assertEquals("50%", card.percentText)
	}

	/** Past the bar, the card says so rather than repeating the bar. */
	@Test
	fun `past the threshold the status changes`() {
		val card = NowCard.from(
			session(title = "t", artist = "a", durationMs = 200_000, playedMs = 150_000),
			durationMs = 200_000,
			identified = true,
			kind = HiveScrobblePayload.KIND_SONG,
			thresholdPercent = 60,
			autoScrobble = true,
		)
		assertEquals("Threshold reached — final checks run when this ends", card.status)
	}

	@Test
	fun `crossing the progress threshold does not promise final eligibility`() {
		val card = NowCard.from(
			session(title = "t", artist = "a", durationMs = 20_000, playedMs = 20_000),
			durationMs = 20_000,
			identified = true,
			kind = HiveScrobblePayload.KIND_VIDEO,
			thresholdPercent = 60,
			autoScrobble = true,
		)

		assertEquals("Threshold reached — final checks run when this ends", card.status)
	}

	@Test
	fun `an unidentified video says so in one short line`() {
		val card = NowCard.from(
			session(title = "t", artist = "a", durationMs = 200_000, playedMs = 10_000),
			durationMs = 200_000,
			identified = false,
			kind = null,
			thresholdPercent = 60,
			autoScrobble = true,
		)
		assertEquals("Identifying video…", card.status)
		assertNull("no kind yet means no category", card.category)
	}

	@Test
	fun `an untitled session says it is still reading`() {
		val card = NowCard.from(
			session(title = null, artist = null, durationMs = 200_000, playedMs = 10_000),
			durationMs = 200_000,
			identified = true,
			kind = null,
			thresholdPercent = 60,
			autoScrobble = true,
		)
		assertEquals("Reading what's playing…", card.status)
	}

	@Test
	fun `an unknown length is stated rather than drawn as zero`() {
		val card = NowCard.from(
			session(title = "t", artist = "a", durationMs = null, playedMs = 10_000),
			durationMs = null,
			identified = true,
			kind = HiveScrobblePayload.KIND_VIDEO,
			thresholdPercent = 60,
			autoScrobble = true,
		)
		assertNull(card.durationText)
		assertNull(card.progress)
		assertNull(card.percentText)
		assertEquals("Waiting for the length…", card.status)
	}

	@Test
	fun `an advertisement is named before anything else`() {
		val card = NowCard.from(
			session(
				title = "t",
				artist = "a",
				durationMs = 200_000,
				playedMs = 150_000,
				adSignal = "Sponsored",
			),
			durationMs = 200_000,
			identified = true,
			kind = HiveScrobblePayload.KIND_VIDEO,
			thresholdPercent = 60,
			autoScrobble = true,
		)
		assertEquals("Advertisement — not counted", card.status)
	}

	@Test
	fun `with automatic scrobbling off the card does not promise a scrobble`() {
		val card = NowCard.from(
			session(title = "t", artist = "a", durationMs = 200_000, playedMs = 10_000),
			durationMs = 200_000,
			identified = true,
			kind = HiveScrobblePayload.KIND_SONG,
			thresholdPercent = 60,
			autoScrobble = false,
		)
		assertEquals("Automatic scrobbling is off", card.status)
	}

	/**
	 * The point of the whole change, pinned as a value: none of the diagnostic
	 * evidence can reach this model, so no amount of editing the composable can
	 * put it back on the card by accident.
	 */
	@Test
	fun `nothing diagnostic survives into the model`() {
		val card = NowCard.from(
			session(title = "Dear Jessie", artist = "Madonna", durationMs = 275_690, playedMs = 30_000),
			durationMs = 275_690,
			identified = true,
			kind = HiveScrobblePayload.KIND_SONG,
			thresholdPercent = 60,
			autoScrobble = true,
		)
		val rendered = listOfNotNull(
			card.platform.label,
			card.channel,
			card.title,
			card.durationText,
			card.percentText,
			card.category,
			card.status,
		).joinToString(" ")
		listOf(
			"com.google",
			"MediaSession",
			"music.youtube.com",
			"videoId",
			"http",
			"payload",
			"musicbrainz",
		).forEach {
			assertEquals("\"$it\" reached the card", false, rendered.contains(it, ignoreCase = true))
		}
	}

	@Test
	fun `duration is minutes and seconds, and an hour is an hour`() {
		assertEquals("0:09", NowCard.durationText(9_000))
		assertEquals("4:35", NowCard.durationText(275_690))
		assertEquals("1:02:03", NowCard.durationText(3_723_000))
		assertNull(NowCard.durationText(null))
	}

	private fun session(
		title: String?,
		artist: String?,
		durationMs: Long?,
		playedMs: Long,
		adSignal: String? = null,
	) = SessionSnapshot(
		packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
		appLabel = "YouTube Music",
		isTarget = true,
		title = title,
		artist = artist,
		album = null,
		durationMs = durationMs,
		positionMs = playedMs,
		playedMs = playedMs,
		loopDetected = false,
		explicitAdSignal = adSignal,
		playbackState = "PLAYING",
		isPlaying = true,
		percentPlayed = durationMs?.let { playedMs.toDouble() / it },
		identity = YouTubeProbe.Identity.SiteOnly(
			host = "music.youtube.com",
			isMusic = true,
			source = "native package",
		),
		resolverContext = ResolverContext(),
		notificationHint = null,
		metadataLines = emptyList(),
		trackStartedAtEpochSec = 1_700_000_000,
	)
}
