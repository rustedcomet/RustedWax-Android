package com.rustedwax.app.detect

import com.rustedwax.app.enrich.MusicBrainzVerifier
import com.rustedwax.app.enrich.FactsCache
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.hive.HiveScrobblePayload
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.PerformerCreditEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The payload's own two Phase-4b fixes: credits that depend on the kind, and a
 * duration that can come from the watch page.
 *
 * No Android types are constructed — [SessionSnapshot] is a plain data class and
 * the identity routes it needs are built directly.
 */
class ScrobbleBuilderTest {

	@Test
	fun `resolver-only native foreground proof activates Short rules classifier and cap`() {
		val nativeShort = session(
			title = "Epic Moment - Best Scene Ever",
			channel = "@clip_owner",
			durationMs = 20_000,
			playedMs = 20_000,
			videoId = null,
		).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube Shorts (foreground)",
			sourceProof = SourceProof.NATIVE_FOREGROUND_SHORT,
			ownerHandle = "@clip_owner",
		)
		val facts = VideoFacts(
			videoId = "abcdefghijk",
			title = nativeShort.title,
			author = "Different display author",
			ownerHandle = nativeShort.ownerHandle,
			category = "People & Blogs",
			lengthSeconds = 20,
			isUnlisted = false,
			isCrawlable = true,
			watchPageResolved = true,
		)
		assertTrue(nativeShort.hasShortSourceProof)
		assertTrue(
			com.rustedwax.app.scrobble.ScrobbleRules.decide(
				playedMs = nativeShort.playedMs,
				durationMs = nativeShort.durationMs,
				isShort = nativeShort.hasShortSourceProof,
				videoResolved = true,
				videoUnlisted = false,
			).shouldScrobble,
		)
		val payload = ScrobbleBuilder.from(nativeShort, facts, videoId = facts.videoId)
		assertEquals(HiveScrobblePayload.KIND_VIDEO, payload!!.kind)
		assertEquals(
			listOf(100),
			com.rustedwax.app.scrobble.ScrobbleRules.capForKind(
				listOf(100, 100),
				payload.kind,
				isShort = nativeShort.hasShortSourceProof,
			),
		)
	}

	/**
	 * `percentPlayed` is derived exactly as `SessionProbe` derives it — null
	 * when the session has no duration. Hardcoding it made an earlier version of
	 * this fixture unable to see the bug the percent test below pins.
	 */
	private fun session(
		title: String?,
		channel: String?,
		durationMs: Long? = 4 * 60 * 1000L,
		playedMs: Long = durationMs ?: 0,
		videoId: String? = "abcdefghijk",
		isShort: Boolean = false,
	) = SessionSnapshot(
		packageName = "com.brave.browser",
		appLabel = "Brave",
		isTarget = true,
		title = title,
		artist = channel,
		album = null,
		durationMs = durationMs,
		positionMs = 0,
		playedMs = playedMs,
		loopDetected = false,
		playbackState = "PLAYING",
		isPlaying = true,
		percentPlayed = durationMs?.takeIf { it > 0 }?.let { playedMs.toDouble() / it },
		identity = videoId?.let {
			YouTubeProbe.Identity.Confirmed(
				videoId = it,
				url = "https://www.youtube.com/watch?v=$it",
				isMusic = false,
				isShort = isShort,
				source = "test",
			)
		} ?: YouTubeProbe.Identity.SiteOnly(
			host = "m.youtube.com",
			isMusic = false,
			source = "test",
		),
		notificationHint = null,
		metadataLines = emptyList(),
		trackStartedAtEpochSec = 1_700_000_000,
	)

	@Test
	fun `the YouTube app's channel is not taken as the artist`() {
		val payload = ScrobbleBuilder.from(
			session("Snoop Doggy Dogg - Intro", "King Of Rap").copy(
				packageName = YouTubeProbe.YOUTUBE_PACKAGE,
				appLabel = "YouTube",
			),
			VideoFacts(
				videoId = "abcdefghijk",
				title = "Snoop Doggy Dogg - Intro",
				author = "King Of Rap",
				category = "Music",
				lengthSeconds = 240,
			),
		)
		assertEquals(HiveScrobblePayload.KIND_SONG, payload!!.kind)
		assertEquals("Snoop Doggy Dogg", payload.artist)
		assertEquals("Intro", payload.title)
	}

	/** Source-published YouTube Music metadata is preserved rather than reparsed. */
	@Test
	fun `YouTube Music preserves its source-published metadata`() {
		val payload = ScrobbleBuilder.from(
			nativeMusicSession(
				title = "Clean Song (Official Audio)",
				artist = "Clean Artist",
				album = "Clean Album",
			),
			VideoFacts(
				videoId = "abcdefghijk",
				title = "Clean Song (Official Audio)",
				author = "Distributor Uploads",
				originalArtist = "Clean Artist",
				watchPageArtistCredit = "Clean Artist",
				category = "Music",
				lengthSeconds = 240,
			),
		)
		assertEquals(HiveScrobblePayload.KIND_SONG, payload!!.kind)
		assertEquals("Clean Artist", payload.artist)
		assertEquals("Clean Song (Official Audio)", payload.title)
		assertEquals("Clean Album", payload.album)
	}

	/**
	 * §3.2 step 4, and the reason restoring the ladder loses no scrobbles: the
	 * listen is kept, and only the claim about it narrows to what is provable.
	 */
	@Test
	fun `an unsplittable generic music title remains a video entry`() {
		val payload = ScrobbleBuilder.from(
			session("Full Album Mix Nonstop", "Some Uploader").copy(
				packageName = YouTubeProbe.YOUTUBE_PACKAGE,
				appLabel = "YouTube",
			),
			VideoFacts(
				videoId = "abcdefghijk",
				title = "Full Album Mix Nonstop",
				author = "Some Uploader",
				category = "Music",
				lengthSeconds = 240,
			),
		)
		assertNotNull(payload)
		assertEquals(HiveScrobblePayload.KIND_VIDEO, payload!!.kind)
		assertEquals("Some Uploader", payload.artist)
	}

	// ── source metadata remains best effort after identity verification ───────
	// ── the product contract: a verified listen scrobbles on source metadata ──

	/*
	 * RustedWax is a playback scrobbler, not a musicological authority. What has
	 * to be right is the *media identity* and the *playback*: a verified video id,
	 * a title, real measured time, and a reasonable song/video classification.
	 * The artist string is best-effort — whatever YouTube or YouTube Music
	 * published for that id — and an imperfect one is acceptable where a wrong
	 * video or invented playback is not.
	 *
	 * MusicBrainz is enrichment: it supplies canonical spelling and feeds
	 * classification. It may not authorize a write and it may not veto one.
	 */

	@Test
	fun `a verified music listen scrobbles on source metadata with no MusicBrainz match`() {
		val payload = ScrobbleBuilder.from(
			nativeMusicSession("El Preso", "Fruko & Wilson Saoko", null),
			VideoFacts(
				videoId = "abcdefghijk",
				title = "El Preso - Fruko y Sus Tesos (Video Oficial) | Some Label",
				author = "Some Label Distribution",
				category = "Music",
				lengthSeconds = 240,
				watchPageResolved = true,
				musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
			),
			MusicBrainzVerifier.Match(found = false),
		)

		assertEquals(HiveScrobblePayload.KIND_SONG, payload?.kind)
		assertEquals("Fruko & Wilson Saoko", payload?.artist)
		assertEquals("El Preso", payload?.title)
	}

	@Test
	fun `an unreachable MusicBrainz cannot veto a verified listen`() {
		val session = nativeMusicSession("Known Work", "Known Artist", null)
		val facts = VideoFacts(
			videoId = "abcdefghijk",
			title = "Known Work",
			author = "Some Label Distribution",
			category = "Music",
			lengthSeconds = 240,
			watchPageResolved = true,
			musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
		)

		// `null` is what a timeout, a DNS failure and an HTTP 503 all produce.
		for (mb in listOf(null, MusicBrainzVerifier.Match(found = false))) {
			val payload = ScrobbleBuilder.from(session, facts, mb)
			assertEquals(HiveScrobblePayload.KIND_SONG, payload?.kind)
			assertEquals("Known Artist", payload?.artist)
		}
	}

	@Test
	fun `an ordinary YouTube video may be credited to the channel that published it`() {
		val payload = ScrobbleBuilder.from(
			session("Some Talk About Things", "A Channel", 600_000),
			VideoFacts(
				videoId = "abcdefghijk",
				title = "Some Talk About Things",
				author = "A Channel",
				category = "Entertainment",
				lengthSeconds = 600,
				watchPageResolved = true,
			),
		)

		assertNotNull("a verified listen is still a listen", payload)
		assertEquals("A Channel", payload?.artist)
	}

	@Test
	fun `an unverified video id is still refused`() {
		val session = nativeMusicSession("Known Work", "Known Artist", null)
		val facts = VideoFacts(
			videoId = "abcdefghijk",
			title = "Known Work",
			author = "Known Artist",
			category = "Music",
			lengthSeconds = 240,
			watchPageResolved = true,
			musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
		)

		assertNull(
			"no id, no hyperlink, no entry",
			ScrobbleBuilder.from(session, facts, null, videoId = null),
		)
		assertNull(
			"a malformed id is not a YouTube video",
			ScrobbleBuilder.from(session, facts, null, videoId = "not-an-id"),
		)
	}

	private fun nativeMusicSession(
		title: String,
		artist: String,
		album: String?,
		genre: String? = null,
	) = SessionSnapshot(
		packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
		appLabel = "YouTube Music",
		isTarget = true,
		title = title,
		artist = artist,
		album = album,
		durationMs = 240_000,
		positionMs = 240_000,
		playedMs = 240_000,
		loopDetected = false,
		playbackState = "PLAYING",
		isPlaying = true,
		percentPlayed = 1.0,
		identity = YouTubeProbe.Identity.Confirmed(
			videoId = "abcdefghijk",
			url = "https://www.youtube.com/watch?v=abcdefghijk",
			isMusic = true,
			source = "native media id",
		),
		notificationHint = null,
		metadataLines = emptyList(),
		trackStartedAtEpochSec = 1_700_000_000,
		genre = genre,
	)

	@Test
	fun `native YouTube Music preserves separated title artist and album`() {
		val payload = ScrobbleBuilder.from(
			nativeMusicSession(
				title = "Song - Not a Browser Credit Shape (Live)",
				artist = "Native Artist",
				album = "Native Album",
			),
			VideoFacts(
				videoId = "abcdefghijk",
				title = "Different Browser-Shaped Artist - Different Title",
				author = "Different Channel",
				originalArtist = "Native Artist",
				watchPageArtistCredit = "Native Artist",
				category = "Music",
				lengthSeconds = 240,
				album = "Lookup Album",
			),
		)

		assertEquals(HiveScrobblePayload.KIND_SONG, payload!!.kind)
		assertEquals("Native Artist", payload.artist)
		assertEquals("Song - Not a Browser Credit Shape (Live)", payload.title)
		assertEquals("Native Album", payload.album)
	}

	@Test
	fun `native YouTube Music preserves its artist when the page has a managed owner`() {
		val payload = ScrobbleBuilder.from(
			nativeMusicSession("Bohemian Rhapsody", "Queen", "A Night At The Opera"),
			VideoFacts(
				videoId = "abcdefghijk",
				title = "Bohemian Rhapsody",
				author = "Queen - Topic",
				category = "Music",
				lengthSeconds = 240,
				watchPageResolved = true,
				musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
			),
		)

		assertEquals("Queen", payload!!.artist)
	}

	@Test
	fun `MusicBrainz may enrich a verified native YouTube Music credit`() {
		val payload = ScrobbleBuilder.from(
			nativeMusicSession("Known Work", "Known Artist", null),
			VideoFacts(
				videoId = "abcdefghijk",
				title = "Known Work",
				author = "Distributor Uploads",
				category = "Music",
				lengthSeconds = 240,
			),
			MusicBrainzVerifier.Match(
				found = true,
				artist = "Known Artist",
				title = "Known Work",
			),
		)

		assertEquals("Known Artist", payload!!.artist)
	}

	@Test
	fun `a revalidated structured identity preserves source credit metadata`() {
		val session = nativeMusicSession("Known Solo Work", "Known Solo Artist", null)
		val identity = VideoResolution(
			videoId = "abcdefghijk",
			source = "structured native music title+artist+duration",
			title = "Known Solo Artist - Known Solo Work",
			channel = "Known Solo Artist",
			lengthSeconds = 240,
			uniquelyResolved = true,
			structuredNativeMusic = true,
			presentationDurationCorroborated = true,
			performerCreditEvidence = PerformerCreditEvidence.CANONICAL_PAGE_COMPLETE_CREDIT,
		)
		val payload = ScrobbleBuilder.from(
			session = session,
			facts = VideoFacts(
				videoId = identity.videoId,
				title = identity.title,
				author = identity.channel,
				lengthSeconds = 240,
				category = "Music",
			),
			identityEvidence = identity,
		)

		assertEquals("Known Solo Artist", payload!!.artist)
	}

	@Test
	fun `resolver metadata does not override the source-published credit`() {
		val session = nativeMusicSession("Follow You (Official Video)", "Bring Me The Horizon", null)
		val raw = VideoResolution(
			videoId = "abcdefghijk",
			source = "title+channel+duration search",
			title = "Bring Me The Horizon - Follow You (Official Video)",
			channel = "Bring Me The Horizon",
			lengthSeconds = 240,
			uniquelyResolved = true,
			presentationDurationCorroborated = true,
			performerCreditEvidence = PerformerCreditEvidence.YOUTUBE_LISTING_COMPLETE_CREDIT,
		)

		assertEquals(
			"Bring Me The Horizon",
			ScrobbleBuilder.from(
				session = session,
				facts = VideoFacts(
					videoId = raw.videoId,
					title = raw.title,
					author = raw.channel,
					lengthSeconds = 240,
					category = "Music",
				),
				identityEvidence = raw,
			)!!.artist,
		)
	}

	@Test
	fun `legacy facts remain usable with a fresh verified identity`() {
		val session = nativeMusicSession("Legacy Work", "Legacy Artist", null)
		val facts = FactsCache.decode(
			"abcdefghijk",
			"""{"title":"Legacy Artist - Legacy Work","author":"Legacy Artist","lengthSeconds":240,"category":"Music","watchPageResolved":true,"ownerHandle":null}""",
		)
		val identity = VideoResolution(
			videoId = "abcdefghijk",
			source = "structured native music title+artist+duration",
			title = "Legacy Artist - Legacy Work",
			channel = "Legacy Artist",
			lengthSeconds = 240,
			uniquelyResolved = true,
			structuredNativeMusic = true,
			presentationDurationCorroborated = true,
			performerCreditEvidence = PerformerCreditEvidence.CANONICAL_PAGE_COMPLETE_CREDIT,
		)

		assertNull(facts?.watchPageArtistCredit)
		assertEquals(
			"Legacy Artist",
			ScrobbleBuilder.from(
				session = session,
				facts = facts,
				identityEvidence = identity,
			)!!.artist,
		)
	}

	@Test
	fun `native YouTube Music podcast stays video`() {
		val payload = ScrobbleBuilder.from(
			nativeMusicSession("A Clean Episode", "A Podcast", "Season 1", genre = "Podcast"),
			VideoFacts(
				videoId = "abcdefghijk",
				title = "A Clean Episode",
				author = "A Podcast",
				lengthSeconds = 240,
				musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
			),
		)

		assertEquals(HiveScrobblePayload.KIND_VIDEO, payload!!.kind)
		assertEquals("A Podcast", payload.artist)
		assertEquals("A Clean Episode", payload.title)
		assertNull(payload.album)
	}

	@Test
	fun `native YouTube still uses the evidence-ranked song and video classifier`() {
		val nativeSong = nativeMusicSession(
			title = "Clean Song (Official Audio)",
			artist = "Clean Artist",
			album = "Clean Album",
		).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube",
			identity = YouTubeProbe.Identity.Confirmed(
				videoId = "abcdefghijk",
				url = "https://www.youtube.com/watch?v=abcdefghijk",
				isMusic = false,
				source = "native media id",
			),
		)
		val nativeVideo = nativeSong.copy(
			title = "How to repair a bicycle",
			artist = "Workshop Channel",
			album = "Should not be broadcast",
		)

		val songPayload = ScrobbleBuilder.from(nativeSong)
		val videoPayload = ScrobbleBuilder.from(nativeVideo)
		// The YouTube app publishes a channel in the ARTIST slot. With no
		// `Artist - Track` structure in the generic title, classification keeps the
		// intact title and channel as a video entry; the verified listen is not lost.
		assertEquals(HiveScrobblePayload.KIND_VIDEO, songPayload!!.kind)
		assertEquals("Clean Artist", songPayload.artist)
		assertEquals("Clean Song", songPayload.title)
		// `album` is release metadata and belongs only to a song.
		assertNull(songPayload.album)
		assertEquals(HiveScrobblePayload.KIND_VIDEO, videoPayload!!.kind)
		assertEquals("Workshop Channel", videoPayload.artist)
		assertEquals("How to repair a bicycle", videoPayload.title)
		assertNull(videoPayload.album)
	}

	// region credits depend on the kind

	@Test
	fun `a film trailer keeps its channel as the artist and its whole title`() {
		val rawTitle =
			"Fall 2: Deadpoint (2026) Official Trailer 2 - Harriet Slater, Arsema Thomas"
		val payload = ScrobbleBuilder.from(
			session(rawTitle, "Lionsgate Movies", durationMs = 101_000),
			VideoFacts(
				videoId = "abcdefghijk",
				title = rawTitle,
				author = "Lionsgate Movies",
				category = "Film & Animation",
				lengthSeconds = 101,
			),
		)
		assertNotNull(payload)
		assertEquals(HiveScrobblePayload.KIND_VIDEO, payload!!.kind)
		assertEquals("Lionsgate Movies", payload.artist)
		assertEquals(rawTitle, payload.title)
	}

	/**
	 * The same failure in reverse order. `Track - Artist` is common in
	 * Spanish-language uploads, and MusicBrainz can only arbitrate it for real
	 * recordings — never for a news clip.
	 */
	@Test
	fun `a news clip is not split into artist and track`() {
		val rawTitle =
			"Iran threatens to attack UK bases used by US forces - Risking wider war | BBC News"
		val payload = ScrobbleBuilder.from(
			session(rawTitle, "BBC News", durationMs = 180_000),
			VideoFacts(videoId = "abcdefghijk", title = rawTitle, author = "BBC News", lengthSeconds = 180),
		)
		assertEquals(HiveScrobblePayload.KIND_VIDEO, payload!!.kind)
		assertEquals("BBC News", payload.artist)
		assertEquals(rawTitle, payload.title)
	}

	@Test
	fun `a YouTube Music podcast type does not turn the timer into a song`() {
		val payload = ScrobbleBuilder.from(
			session(
				title = "2 Minute Timer Bomb [COOKIE] 🍪",
				channel = "Timer Topia",
				durationMs = 125_021,
			),
			VideoFacts(
				videoId = "cXbYjaEsQWg",
				title = "2 Minute Timer Bomb [COOKIE] 🍪",
				author = "Timer Topia",
				category = "Education",
				lengthSeconds = 125,
				musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
			),
		)
		assertEquals(HiveScrobblePayload.KIND_VIDEO, payload!!.kind)
		assertEquals("Timer Topia", payload.artist)
	}

	/** Songs keep splitting — that is the behaviour the parser exists for. */
	@Test
	fun `a song is still split into artist and track`() {
		val payload = ScrobbleBuilder.from(
			session("Korn - Trash (Official Audio)", "KornVEVO"),
			VideoFacts(
				videoId = "abcdefghijk",
				title = "Korn - Trash (Official Audio)",
				author = "KornVEVO",
				category = "Music",
				lengthSeconds = 240,
			),
		)
		assertEquals(HiveScrobblePayload.KIND_SONG, payload!!.kind)
		assertEquals("Korn", payload.artist)
		assertEquals("Trash", payload.title)
	}

	/** Hashtags come off the title of a video too, even though nothing is split. */
	@Test
	fun `a video title loses its trailing hashtags`() {
		val payload = ScrobbleBuilder.from(
			session(
				"The Baddest Dragon in Westeros #houseofthedragon #daemontargaryen #got",
				"SineCema",
				durationMs = 47_000,
			),
			VideoFacts(
				videoId = "abcdefghijk",
				title = "The Baddest Dragon in Westeros #houseofthedragon #daemontargaryen #got",
				author = "SineCema",
				category = "Film & Animation",
				lengthSeconds = 47,
			),
		)
		assertEquals("The Baddest Dragon in Westeros", payload!!.title)
		assertEquals("SineCema", payload.artist)
	}

	/**
	 * A hashtag-only title survives into the payload rather than being emptied —
	 * but as a `video`, where the channel names it and the raw title is honest.
	 */
	@Test
	fun `a hashtag-only title stays intact and stays a video`() {
		val rawTitle = "#guitar #dubstep #djdubstep #fnaf #fivenightsatfreddy"
		val payload = ScrobbleBuilder.from(
			session(rawTitle, "katter", durationMs = 30_000),
			VideoFacts(
				videoId = "abcdefghijk",
				title = rawTitle,
				author = "katter",
				category = "Music",
				lengthSeconds = 30,
			),
		)
		assertEquals(HiveScrobblePayload.KIND_VIDEO, payload!!.kind)
		assertEquals("katter", payload.artist)
		assertEquals(rawTitle, payload.title)
	}
	// endregion

	// region album

	@Test
	fun `a song carries its album`() {
		val payload = ScrobbleBuilder.from(
			session("Korn - Trash", "KornVEVO"),
			VideoFacts(
				videoId = "abcdefghijk",
				title = "Korn - Trash",
				author = "KornVEVO",
				category = "Music",
				lengthSeconds = 240,
				album = "Untouchables",
			),
		)
		assertEquals(HiveScrobblePayload.KIND_SONG, payload!!.kind)
		assertEquals("Untouchables", payload.album)
	}

	/** Release metadata on a trailer would be noise at best. */
	@Test
	fun `a video carries no album`() {
		val payload = ScrobbleBuilder.from(
			session("Some Trailer", "A Studio", durationMs = 120_000),
			VideoFacts(
				videoId = "abcdefghijk",
				title = "Some Trailer",
				author = "A Studio",
				category = "Film & Animation",
				lengthSeconds = 120,
				album = "Should Not Appear",
			),
		)
		assertEquals(HiveScrobblePayload.KIND_VIDEO, payload!!.kind)
		assertNull(payload.album)
	}
	// endregion

	// region YouTube Music credits

	@Test
	fun `art track credits reach the payload`() {
		val payload = ScrobbleBuilder.from(
			session("Con Calma", "Daddy Yankee - Topic", durationMs = 193_000),
			VideoFacts(
				videoId = "abcdefghijk",
				title = "Con Calma",
				author = "Daddy Yankee - Topic",
				lengthSeconds = 193,
				musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
				originalArtist = "Daddy Yankee",
				originalTitle = "Con Calma",
			),
		)
		assertEquals(HiveScrobblePayload.KIND_SONG, payload!!.kind)
		assertEquals("Daddy Yankee", payload.artist)
		assertEquals("Con Calma", payload.title)
	}

	/**
	 * The catalogue makes a cover a `song`, but its OMV "author" is the channel
	 * and its "title" is the uploader's — so the resolver deliberately doesn't
	 * pass those through, and the existing parse stands.
	 */
	@Test
	fun `an official music video is classified by the catalogue but parsed locally`() {
		val payload = ScrobbleBuilder.from(
			session("Metallica - Blackened (guitar cover)", "Elena Verrier", durationMs = 403_000),
			VideoFacts(
				videoId = "abcdefghijk",
				title = "Metallica - Blackened (guitar cover)",
				author = "Elena Verrier",
				lengthSeconds = 403,
				musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
			),
		)
		assertEquals(HiveScrobblePayload.KIND_SONG, payload!!.kind)
		assertEquals("Metallica", payload.artist)
		assertEquals("Blackened", payload.title)
	}
	// endregion

	// region duration recovery

	@Test
	fun `the watch page supplies a duration the session lacked`() {
		val facts = VideoFacts(
			videoId = "abcdefghijk",
			title = "Some Short",
			author = "A Channel",
			lengthSeconds = 22,
		)
		val s = session("Some Short", "A Channel", durationMs = null)
		assertEquals(22_000L, ScrobbleBuilder.effectiveDurationMs(s, facts))
		assertEquals("0:22", ScrobbleBuilder.from(s, facts)!!.duration)
	}

	/**
	 * `percent_played` has to be recomputed against the effective duration.
	 * Reading `SessionSnapshot.percentPlayed` divides by the *session's*
	 * duration, which is null in exactly the case a watch-page length rescues —
	 * so a recovered payload would have gone out with no percent at all.
	 */
	@Test
	fun `a recovered duration still yields a percent played`() {
		val s = session("Some Short", "A Channel", durationMs = null, playedMs = 11_000)
		val facts = VideoFacts(videoId = "abcdefghijk", title = "Some Short", lengthSeconds = 22)
		assertNull(s.percentPlayed)
		assertEquals(50, ScrobbleBuilder.from(s, facts)!!.percentPlayed)
	}

	/** Overrun is clamped, not wrapped — `played 12s of 10s` is 100%, not 120%. */
	@Test
	fun `percent played is clamped to a hundred`() {
		val s = session("Clip", "A Channel", durationMs = 10_000, playedMs = 12_000)
		assertEquals(100, ScrobbleBuilder.from(s, null)!!.percentPlayed)
	}

	/** The session's own duration is authoritative when it has one. */
	@Test
	fun `the session duration wins over the watch page`() {
		val s = session("Track", "Channel", durationMs = 240_000)
		val facts = VideoFacts(videoId = "abcdefghijk", lengthSeconds = 999)
		assertEquals(240_000L, ScrobbleBuilder.effectiveDurationMs(s, facts))
	}

	@Test
	fun `no duration anywhere is still unbroadcastable`() {
		val s = session("Track", "Channel", durationMs = null)
		assertNull(ScrobbleBuilder.effectiveDurationMs(s, null))
		assertNull(ScrobbleBuilder.from(s, null))
	}

	@Test
	fun `a YouTube session without a verified video id cannot build a payload`() {
		val unresolved = session(
			title = "How Unai Simón Denied Messi’s Clearest Goal Scoring Chance",
			channel = "Mind-boggling Football",
			durationMs = 60_000,
			videoId = null,
		)
		assertNull(ScrobbleBuilder.from(unresolved))
	}
	// endregion
}
