package com.rustedwax.app.enrich

import com.rustedwax.app.detect.ResolverContext
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.SourceProof
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.youtube.identity.VideoResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First play of a YouTube Music **video** rendering, which could not be
 * identified at all.
 *
	 * Regression: the session publishes a bare work, artist metadata and duration,
	 * while the matching upload prefixes the title with the artist and uses a
	 * shorter managed channel name. Exact string matching made every route refuse.
 *
 * Duration was never the blocker — the upload is 215 s and the session published
 * 215 s. Two things were:
 *
 *  1. the upload is titled `"Sizzla Kalonji - Solid As A Rock (Official Audio)"`,
 *     so [SearchResultsParser.titleKey] equality against the session's
 *     `"Solid As A Rock (Official Audio)"` failed; and
 *  2. its owner is `"Sizzla"` on the card and `"SizzlaVEVO"` on the watch page —
 *     both `"sizzla"` after [com.rustedwax.app.detect.TitleParser.cleanChannel] —
 *     against a published artist of `"Sizzla Kalonji"`, so exact channel-key
 *     agreement failed too.
 *
 * Loosening either one alone is not safe. At 215 s there are **two** uploads
 * owned by Sizzla — `XPLYvi4mh_w` (the video) and `YHsP10noEAA` (the song) — so
 * relaxing the channel rule on its own turns a refusal into an ambiguity. The
 * title is what separates them, and it is required to.
 *
 * Every fixture here is the real response, transcribed from
 * `https://www.youtube.com/results?search_query=Solid+As+A+Rock+(Official+Audio)+Sizzla+Kalonji`
 * — the resolver's own first query — and from both watch pages.
 */
class VideoModeArtistPrefixIdentityTest {

	private val videoId = "XPLYvi4mh_w"
	private val songId = "YHsP10noEAA"

	/** What the session published while the video was playing. */
	private val sessionTitle = "Solid As A Rock (Official Audio)"
	private val sessionArtist = "Sizzla Kalonji"
	private val sessionDurationSec = 215L

	private fun card(
		videoId: String,
		title: String,
		channel: String?,
		lengthSeconds: Long?,
	) = SearchResultsParser.Candidate(videoId, title, channel, lengthSeconds)

	/** The first query's result set, in the order YouTube returned it. */
	private val searchCards = listOf(
		card(videoId, "Sizzla Kalonji - Solid As A Rock (Official Audio)", "Sizzla", 215),
		card("oExfnnWzJDk", "sizzla - solid as a rock", "toems bundy", 220),
		card("L8vwjFK01M8", "Solid As A Rock", "Sizzla", 267),
		card("AdEqrgQdpws", "Solid As A Rock", "Sizzla", 99),
		card("4hUNaJq2_VA", "Sizzla-Solid As A Rock With Lyrics", "Music Lyrics", 220),
		card(songId, "Solid As A Rock", "Sizzla", 215),
		card(
			"1WTNJRr2wf8",
			"Sizzla Kalonji - Solid As A Rock - RMR Records Jamaica",
			"RMR Records Jamaica ",
			133,
		),
	)

	/** The same two 215 s uploads as their watch pages describe them. */
	private val watchPages = listOf(
		card(videoId, "Sizzla Kalonji - Solid As A Rock (Official Audio)", "SizzlaVEVO", 215),
		card(songId, "Solid As A Rock", "Sizzla - Topic", 215),
	)

	// ── A. the video resolves, and only it ───────────────────────────────────

	@Test
	fun `the video rendering is selected from its own title while the song is not`() {
		val matches = SearchResultsParser.identityMatches(
			searchCards, sessionTitle, sessionArtist, sessionDurationSec,
		)

		assertEquals(
			"exactly one upload satisfies title, owner and length together",
			listOf(videoId),
			matches.map { it.videoId },
		)
		assertEquals(
			videoId,
			SearchResultsParser.bestMatch(
				searchCards, sessionTitle, sessionArtist, sessionDurationSec,
			)?.videoId,
		)
	}

	@Test
	fun `the same holds once both candidates are completed from their watch pages`() {
		// `SizzlaVEVO` and `Sizzla - Topic` both clean to `Sizzla`, so the watch
		// pages are no easier than the cards — the title still has to do the work.
		assertEquals(
			listOf(videoId),
			SearchResultsParser.identityMatches(
				watchPages, sessionTitle, sessionArtist, sessionDurationSec,
			).map { it.videoId },
		)
	}

	@Test
	fun `the video survives the prefilter so its watch page is ever fetched`() {
		// `hasNoIdentityContradiction` decides which cards are worth completing.
		// While it rejected this one, no later route could see the upload at all.
		assertTrue(
			SearchResultsParser.hasNoIdentityContradiction(
				searchCards.first { it.videoId == videoId },
				sessionTitle,
				sessionArtist,
				sessionDurationSec,
			),
		)
		assertFalse(
			"and the plain-titled song is still contradicted by this session's title",
			SearchResultsParser.hasNoIdentityContradiction(
				searchCards.first { it.videoId == songId },
				sessionTitle,
				sessionArtist,
				sessionDurationSec,
			),
		)
	}

	@Test
	fun `a third party after the track name is not this session's title`() {
		// "Sizzla Kalonji - Solid As A Rock - RMR Records Jamaica" opens with the
		// right artist. Everything after the first separator must still be the
		// session's title, and here it names a label instead.
		assertFalse(
			SearchResultsParser.titleNamesArtistThenSession(
				sessionTitle,
				sessionArtist,
				"Sizzla Kalonji - Solid As A Rock - RMR Records Jamaica",
			),
		)
		assertFalse(
			SearchResultsParser.titleNamesArtistThenSession(
				"Solid As A Rock",
				sessionArtist,
				"Sizzla Kalonji - Solid As A Rock - RMR Records Jamaica",
			),
		)
	}

	// ── B. the plain song title must not select the video ────────────────────

	@Test
	fun `a plain session title selects the song and never the Official Audio upload`() {
		// The Song rendering publishes ARTIST = "Sizzla", TITLE = "Solid As A Rock".
		val matches = SearchResultsParser.identityMatches(
			searchCards, "Solid As A Rock", "Sizzla", sessionDurationSec,
		)

		assertEquals(
			"the song, by exact title and exact owner",
			listOf(songId),
			matches.map { it.videoId },
		)
		assertFalse(
			"the video's title is not this session's title, prefix rule or not",
			matches.any { it.videoId == videoId },
		)
	}

	@Test
	fun `the prefix rule needs the published artist, not merely some artist`() {
		assertFalse(
			"session artist \"Sizzla\" does not open \"Sizzla Kalonji - …\"",
			SearchResultsParser.titleNamesArtistThenSession(
				"Solid As A Rock (Official Audio)",
				"Sizzla",
				"Sizzla Kalonji - Solid As A Rock (Official Audio)",
			),
		)
	}

	// ── C. genuine ambiguity still refuses ───────────────────────────────────

	@Test
	fun `two uploads that both satisfy the evidence refuse rather than guess`() {
		val ambiguous = listOf(
			card(videoId, "Sizzla Kalonji - Solid As A Rock (Official Audio)", "Sizzla", 215),
			card("ZZZZZZZZZZZ", "Sizzla Kalonji - Solid As A Rock (Official Audio)", "SizzlaVEVO", 215),
		)

		assertEquals(
			"both are equally corroborated, so both are reported",
			2,
			SearchResultsParser.identityMatches(
				ambiguous, sessionTitle, sessionArtist, sessionDurationSec,
			).size,
		)
		assertNull(
			"and nothing is selected",
			SearchResultsParser.bestMatch(
				ambiguous, sessionTitle, sessionArtist, sessionDurationSec,
			),
		)
	}

	// ── D. a different owner is still refused ────────────────────────────────

	@Test
	fun `an upload titled for the artist but owned by someone else is refused`() {
		val reupload = listOf(
			card("RRRRRRRRRRR", "Sizzla Kalonji - Solid As A Rock (Official Audio)", "RMR Records Jamaica", 215),
		)

		assertEquals(
			"naming the artist in a title is not owning the upload",
			emptyList<String>(),
			SearchResultsParser.identityMatches(
				reupload, sessionTitle, sessionArtist, sessionDurationSec,
			).map { it.videoId },
		)
	}

	@Test
	fun `a vocal cover on a third-party channel is still refused`() {
		// The shape the strict rule has always existed for: the artist's name in
		// the title, someone else's channel, the same length.
		val cover = listOf(
			card("CCCCCCCCCCC", "BRING ME THE HORIZON - Doomed (AUDIO)", "Psychotic Vampire", 273),
		)

		assertEquals(
			emptyList<String>(),
			SearchResultsParser.identityMatches(
				cover, "Doomed (AUDIO)", "Bring Me The Horizon", 273,
			).map { it.videoId },
		)
	}

	@Test
	fun `owner agreement is by whole name, not by substring`() {
		assertTrue(
			SearchResultsParser.channelIsSameArtistNamedDifferently("Sizzla Kalonji", "Sizzla"),
		)
		assertTrue(
			"the marker suffixes are stripped before the comparison",
			SearchResultsParser.channelIsSameArtistNamedDifferently("Sizzla Kalonji", "SizzlaVEVO"),
		)
		assertTrue(
			SearchResultsParser.channelIsSameArtistNamedDifferently("Sizzla Kalonji", "Sizzla - Topic"),
		)
		assertFalse(
			"a longer word that merely starts the same is a different name",
			SearchResultsParser.channelIsSameArtistNamedDifferently("Sizzla Kalonji", "Sizzlagang"),
		)
		assertFalse(
			"an owner cannot establish identity by adding arbitrary tokens",
			SearchResultsParser.channelIsSameArtistNamedDifferently("Sizzla", "Sizzla Gang"),
		)
		assertTrue(
			"the observed bounded article variant remains accepted",
			SearchResultsParser.channelIsSameArtistNamedDifferently("India", "La India"),
		)
		assertFalse(
			SearchResultsParser.channelIsSameArtistNamedDifferently("Sizzla Kalonji", "RMR Records Jamaica"),
		)
		assertFalse(
			SearchResultsParser.channelIsSameArtistNamedDifferently(null, "Sizzla"),
		)
	}

	@Test
	fun `a candidate cannot be accepted solely through an owner with added tokens`() {
		val addedOwnerTokens = listOf(
			card(songId, "Sizzla - Solid As A Rock", "Sizzla Gang", 215),
		)

		assertEquals(
			emptyList<String>(),
			SearchResultsParser.identityMatches(
				addedOwnerTokens, "Solid As A Rock", "Sizzla", sessionDurationSec,
			).map { it.videoId },
		)
	}

	@Test
	fun `owner spelling cannot compensate for a non-prefixed title`() {
		// Same owner variation, ordinary title: the strict rule stands. This is
		// what keeps the relaxation out of every listen that did not earn it.
		val plainTitled = listOf(card(songId, "Solid As A Rock", "Sizzla", 215))

		assertEquals(
			emptyList<String>(),
			SearchResultsParser.identityMatches(
				plainTitled, "Solid As A Rock", "Sizzla Kalonji", sessionDurationSec,
			).map { it.videoId },
		)
	}

	// ── E. the first attempt, from the raw response, with nothing remembered ──

	@Test
	fun `the video resolves from the first search response alone`() {
		// Straight from `ytInitialData` — no cache, no run-local candidate, no
		// earlier play. This is the whole path a first attempt has.
		val parsed = SearchResultsParser.candidates(FIRST_QUERY_RESPONSE)
		assertTrue("the fixture must carry both 215s uploads", parsed.size >= 2)

		assertEquals(
			listOf(videoId),
			SearchResultsParser.identityMatches(
				parsed, sessionTitle, sessionArtist, sessionDurationSec,
			).map { it.videoId },
		)
	}

	@Test
	fun `the length the session published still has to agree`() {
		assertEquals(
			"a wrong length refuses the upload however well the title reads",
			emptyList<String>(),
			SearchResultsParser.identityMatches(
				searchCards, sessionTitle, sessionArtist, 180,
			).map { it.videoId },
		)
	}

	// ── the second gate: the corroborator must accept what the matcher named ─

	/** A finalized listen with the representative source metadata shape. */
	private fun endedVideoListen() = SessionSnapshot(
		packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
		appLabel = "YouTube Music",
		isTarget = true,
		title = sessionTitle,
		artist = sessionArtist,
		album = null,
		durationMs = 214_691,
		positionMs = 214_691,
		playedMs = 214_691,
		loopDetected = false,
		playbackState = "STOPPED",
		isPlaying = false,
		percentPlayed = 1.0,
		identity = YouTubeProbe.Identity.SiteOnly(
			host = "music.youtube.com",
			isMusic = true,
			source = "native package origin",
		),
		resolverContext = ResolverContext(presentationDurationMs = 214_691),
		notificationHint = null,
		metadataLines = emptyList(),
		trackStartedAtEpochSec = 1_785_000_000,
		sourceProof = SourceProof.MEDIA_SESSION,
	)

	private fun videoResolution(
		channel: String = "Sizzla",
		title: String = "Sizzla Kalonji - Solid As A Rock (Official Audio)",
	) = VideoResolution(
		videoId = videoId,
		source = "title+channel+duration search",
		title = title,
		channel = channel,
		lengthSeconds = 215,
		uniquelyResolved = true,
		presentationDurationCorroborated = true,
	)

	private fun videoFacts(author: String = "SizzlaVEVO") = VideoFacts(
		videoId = videoId,
		title = "Sizzla Kalonji - Solid As A Rock (Official Audio)",
		author = author,
		lengthSeconds = 215,
		category = "Music",
		watchPageResolved = true,
	)

	@Test
	fun `the corroborator accepts the upload the matcher named`() {
		// Regression: the resolver names the matching upload, but a later channel
		// comparison must not throw the identity away:
		// `candidate channel "Sizzla" contradicts ended channel "Sizzla Kalonji"`.
		assertNull(
			VideoIdentityCorroborator.contradiction(
				endedVideoListen(),
				videoResolution(),
				videoFacts(),
			),
		)
	}

	@Test
	fun `a label owner neither licenses nor refuses the corroborated upload`() {
		assertNull(
			"the uploader is metadata; the title and length already corroborate",
			VideoIdentityCorroborator.contradiction(
				endedVideoListen(),
				videoResolution(channel = "RMR Records Jamaica"),
				videoFacts(author = "RMR Records Jamaica"),
			),
		)
	}

	@Test
	fun `without an artist-prefixed title the title rank decides and the owner does not`() {
		// Same owner shortening, but the upload's title is not this session's title
		// behind this artist's name. The owner neither licenses nor refuses it; the
		// ordinary title rank and the length are what bind the id.
		assertNull(
			VideoIdentityCorroborator.contradiction(
				endedVideoListen(),
				videoResolution(title = "Solid As A Rock (Live In Kingston)"),
				videoFacts(author = "Sizzla").copy(
					title = "Solid As A Rock (Live In Kingston)",
				),
			),
		)
	}

	// ── the third gate: a weak title rank on an artist-prefixed upload ───────

	/**
	 * Regression: a bare work can match an artist-prefixed upload after the full
	 * presentation has played.
	 *
	 * The owner string is not an independent licence here — the session published
	 * `ARTIST = "Collie Buddz"` and the upload is owned by `Collie Buddz`, so the
	 * channel comparison was exact. What refused it was a *third* gate: the
	 * session's short title ranks as a weak canonical core of the candidate's
	 * `"Collie Buddz - Come Around"`, and
	 *
	 * ```
	 * skipped: video id could not be verified against the finalized snapshot —
	 * Shorts watch-page completion candidate title "Collie Buddz - Come Around"
	 * is only weak evidence
	 * ```
	 *
	 * The upload adds nothing but the artist's own name, which is the whole
	 * reason it is not weak evidence.
	 */
	private val comeAroundId = "Ry8_NdNKq-w"

	private fun endedComeAround() = SessionSnapshot(
		packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
		appLabel = "YouTube Music",
		isTarget = true,
		title = "Come Around",
		artist = "Collie Buddz",
		album = null,
		durationMs = 222_000,
		positionMs = 222_000,
		playedMs = 222_000,
		loopDetected = false,
		playbackState = "STOPPED",
		isPlaying = false,
		percentPlayed = 1.0,
		identity = YouTubeProbe.Identity.SiteOnly(
			host = "music.youtube.com",
			isMusic = true,
			source = "native package origin",
		),
		resolverContext = ResolverContext(presentationDurationMs = 222_000),
		notificationHint = null,
		metadataLines = emptyList(),
		trackStartedAtEpochSec = 1_785_000_000,
		sourceProof = SourceProof.MEDIA_SESSION,
	)

	@Test
	fun `an upload that adds only the artist's name is not weak evidence`() {
		val resolution = VideoResolution(
			videoId = comeAroundId,
			source = "Shorts watch-page completion",
			title = "Collie Buddz - Come Around",
			channel = "Collie Buddz",
			lengthSeconds = 223,
			uniquelyResolved = true,
			presentationDurationCorroborated = true,
		)
		val facts = VideoFacts(
			videoId = comeAroundId,
			title = "Collie Buddz - Come Around",
			author = "Collie Buddz",
			lengthSeconds = 223,
			category = "Music",
			watchPageResolved = true,
		)

		assertNull(
			VideoIdentityCorroborator.contradiction(endedComeAround(), resolution, facts),
		)
	}

	@Test
	fun `an upload that adds anything of its own is still weak evidence`() {
		// The same artist, the same owner, the same length — and a title that says
		// more than the session did. That is what the weak-title gate is for.
		val officialVideo = VideoResolution(
			videoId = "hadAFzwc8-o",
			source = "Shorts watch-page completion",
			title = "Collie Buddz - Come Around (Official Music Video)",
			channel = "Collie Buddz",
			lengthSeconds = 223,
			uniquelyResolved = true,
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				endedComeAround(),
				officialVideo,
				VideoFacts(
					videoId = "hadAFzwc8-o",
					title = "Collie Buddz - Come Around (Official Music Video)",
					author = "Collie Buddz",
					lengthSeconds = 223,
					category = "Music",
					watchPageResolved = true,
				),
			),
		)
	}

	@Test
	fun `a length that disagrees still refuses the artist-prefixed upload`() {
		assertNotNull(
			"the prefix licence never stands in for the duration",
			VideoIdentityCorroborator.contradiction(
				endedComeAround(),
				VideoResolution(
					videoId = comeAroundId,
					source = "Shorts watch-page completion",
					title = "Collie Buddz - Come Around",
					channel = "Collie Buddz",
					lengthSeconds = 305,
					uniquelyResolved = true,
				),
				VideoFacts(
					videoId = comeAroundId,
					title = "Collie Buddz - Come Around",
					author = "Collie Buddz",
					lengthSeconds = 305,
					category = "Music",
					watchPageResolved = true,
				),
			),
		)
	}

	@Test
	fun `the matcher picks the bare artist-prefixed upload out of the real result set`() {
		// The real first-query cards for "Come Around Collie Buddz". Only one of
		// them is this session's title behind this artist's name at this length.
		val cards = listOf(
			card(comeAroundId, "Collie Buddz - Come Around", "Collie Buddz", 223),
			card("rJotybvDam0", "Collie Buddz - [Collie Buddz ] Come Around HQ", "ident1tyx", 311),
			card(
				"hadAFzwc8-o",
				"Collie Buddz - Come Around (Official Music Video)",
				"Collie Buddz",
				223,
			),
			card("PaDX5k3oywQ", "Come Around", "Collie Buddz", 231),
		)

		assertEquals(
			listOf(comeAroundId),
			SearchResultsParser.identityMatches(cards, "Come Around", "Collie Buddz", 222)
				.map { it.videoId },
		)
	}

	// ── the session's own credit is the collaboration, not the owner's ───────

	/**
	 * Regression: a collaboration credit can appear on one member's channel while
	 * several similarly timed uploads remain possible.
	 *
	 * The session credited `ARTIST = "Little Lion Sound & Queen Omega"`; the
	 * upload sits on `Little Lion Sound`, where a collaboration normally is. The
	 * title matched **exactly** and 159 s vs 160 s is inside tolerance, so the
	 * owner comparison was the only thing refusing it.
	 *
	 * The channel's own dubplate series supplies the hard part of this test: four
	 * uploads on the same owner within five seconds of each other. Only the title
	 * separates them, and it is required to.
	 */
	private val dubplateId = "-EcbJdc5RPI"
	private val dubplateTitle = "Capleton - Dubplate - Little Lion Sound - Tour / Road to Zion"
	private val dubplateArtist = "Little Lion Sound & Queen Omega"

	private val dubplateCards = listOf(
		card(dubplateId, dubplateTitle, "Little Lion Sound", 160),
		card("NJs086E2qew", dubplateTitle, "Little Lion Sound", 215),
		card(
			"8xqrOnBd2xo",
			"Capleton - Dubplate - Little Lion Sound - Who Dem",
			"Little Lion Sound",
			158,
		),
		card(
			"LHgvTtszVh8",
			"Capleton - Dubplate - Little Lion Sound - Can't Sleep At Night",
			"Little Lion Sound",
			159,
		),
		card(
			"lHxetEVuYcY",
			"Queen Omega, Little Lion Sound - No Love Dubplate (Official Lyric Video)",
			"Queen Omega",
			155,
		),
	)

	@Test
	fun `the lead act of a credited collaboration owns the upload`() {
		assertEquals(
			"one title, one length — the sibling dubplates are all refused",
			listOf(dubplateId),
			SearchResultsParser.identityMatches(dubplateCards, dubplateTitle, dubplateArtist, 159)
				.map { it.videoId },
		)
	}

	@Test
	fun `the byline rule needs the owner to be the leader exactly`() {
		assertTrue(
			SearchResultsParser.channelLeadsSessionCollaboration(
				"Little Lion Sound & Queen Omega", "Little Lion Sound",
			),
		)
		assertFalse(
			"the second act is not the head of the credit",
			SearchResultsParser.channelLeadsSessionCollaboration(
				"Little Lion Sound & Queen Omega", "Queen Omega",
			),
		)
		assertFalse(
			"an owner that merely appears inside the credit is not its leader",
			SearchResultsParser.channelLeadsSessionCollaboration(
				"Little Lion Sound & Queen Omega", "Lion",
			),
		)
		assertFalse(
			"a session that credited one act has no byline to lead",
			SearchResultsParser.channelLeadsSessionCollaboration("Sizzla Kalonji", "Sizzla"),
		)
	}

	@Test
	fun `an unrelated owner is still refused for a credited collaboration`() {
		assertEquals(
			emptyList<String>(),
			SearchResultsParser.identityMatches(
				listOf(card("QQQQQQQQQQQ", dubplateTitle, "Reggae Uploads Daily", 160)),
				dubplateTitle,
				dubplateArtist,
				159,
			).map { it.videoId },
		)
	}

	@Test
	fun `the corroborator accepts the collaboration leader too`() {
		val session = SessionSnapshot(
			packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
			appLabel = "YouTube Music",
			isTarget = true,
			title = dubplateTitle,
			artist = dubplateArtist,
			album = null,
			durationMs = 159_000,
			positionMs = 159_000,
			playedMs = 159_000,
			loopDetected = false,
			playbackState = "STOPPED",
			isPlaying = false,
			percentPlayed = 1.0,
			identity = YouTubeProbe.Identity.SiteOnly(
				host = "music.youtube.com",
				isMusic = true,
				source = "native package origin",
			),
			resolverContext = ResolverContext(presentationDurationMs = 159_000),
			notificationHint = null,
			metadataLines = emptyList(),
			trackStartedAtEpochSec = 1_785_000_000,
			sourceProof = SourceProof.MEDIA_SESSION,
		)
		val resolution = VideoResolution(
			videoId = dubplateId,
			source = "title+channel+duration search",
			title = dubplateTitle,
			channel = "Little Lion Sound",
			lengthSeconds = 160,
			uniquelyResolved = true,
			presentationDurationCorroborated = true,
		)
		val facts = VideoFacts(
			videoId = dubplateId,
			title = dubplateTitle,
			author = "Little Lion Sound",
			lengthSeconds = 160,
			category = "Music",
			watchPageResolved = true,
		)

		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))
		assertNull(
			"and a stranger's channel does not refuse it either",
			VideoIdentityCorroborator.contradiction(
				session,
				resolution.copy(channel = "Reggae Uploads Daily"),
				facts.copy(author = "Reggae Uploads Daily"),
			),
		)
	}

	private companion object {
		/**
		 * The verbatim shape of the resolver's first query response, reduced to
		 * the fields [SearchResultsParser.candidates] reads.
		 */
		const val FIRST_QUERY_RESPONSE = """
			{"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
			{"videoRenderer":{"videoId":"XPLYvi4mh_w",
			"title":{"runs":[{"text":"Sizzla Kalonji - Solid As A Rock (Official Audio)"}]},
			"ownerText":{"runs":[{"text":"Sizzla"}]},"lengthText":{"simpleText":"3:35"}}},
			{"videoRenderer":{"videoId":"oExfnnWzJDk",
			"title":{"runs":[{"text":"sizzla - solid as a rock"}]},
			"ownerText":{"runs":[{"text":"toems bundy"}]},"lengthText":{"simpleText":"3:40"}}},
			{"videoRenderer":{"videoId":"L8vwjFK01M8","title":{"runs":[{"text":"Solid As A Rock"}]},
			"ownerText":{"runs":[{"text":"Sizzla"}]},"lengthText":{"simpleText":"4:27"}}},
			{"videoRenderer":{"videoId":"AdEqrgQdpws","title":{"runs":[{"text":"Solid As A Rock"}]},
			"ownerText":{"runs":[{"text":"Sizzla"}]},"lengthText":{"simpleText":"1:39"}}},
			{"videoRenderer":{"videoId":"YHsP10noEAA","title":{"runs":[{"text":"Solid As A Rock"}]},
			"ownerText":{"runs":[{"text":"Sizzla"}]},"lengthText":{"simpleText":"3:35"}}},
			{"videoRenderer":{"videoId":"1WTNJRr2wf8",
			"title":{"runs":[{"text":"Sizzla Kalonji - Solid As A Rock - RMR Records Jamaica"}]},
			"ownerText":{"runs":[{"text":"RMR Records Jamaica "}]},"lengthText":{"simpleText":"2:13"}}}
			]}}]}}}
		"""
	}
}
