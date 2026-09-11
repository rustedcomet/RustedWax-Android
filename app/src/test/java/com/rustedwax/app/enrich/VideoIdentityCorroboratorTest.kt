package com.rustedwax.app.enrich

import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.app.detect.ResolverContext
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.SourceProof
import com.rustedwax.app.detect.YouTubeProbe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoIdentityCorroboratorTest {

	@Test
	fun `foreground Short final corroboration requires exact handle from candidate and facts`() {
		val foreground = ended("Hackers' Skills...", "@Beredist", 139_000).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube Shorts (foreground)",
			sourceProof = SourceProof.NATIVE_FOREGROUND_SHORT,
			ownerHandle = "@Beredist",
		)
		val resolution = VideoResolution(
			videoId = "orsMh4bNeGE",
			source = "owner handle",
			title = "Hackers' Skills...",
			channel = "Beredits",
			lengthSeconds = 139,
			uniquelyResolved = true,
			ownerHandle = "@beredist",
		)
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = resolution.title,
			author = resolution.channel,
			ownerHandle = "@Beredist",
			lengthSeconds = 139,
			watchPageResolved = true,
		)
		assertNull(VideoIdentityCorroborator.contradiction(foreground, resolution, facts))
		assertTrue(VideoIdentityCorroborator.cacheable(foreground, resolution, facts))

		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				foreground,
				resolution.copy(ownerHandle = "@other_owner"),
				facts,
			),
		)

		assertNull(
			VideoIdentityCorroborator.contradiction(
				foreground,
				resolution,
				facts.copy(ownerHandle = null),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				foreground,
				resolution,
				facts.copy(ownerHandle = "@someone_else"),
			),
		)
		// Seeding the run-local cache still demands both, because a cached
		// candidate is re-used without the page in front of it.
		assertFalse(VideoIdentityCorroborator.cacheable(foreground, resolution, facts.copy(ownerHandle = null)))
	}

	@Test
	fun `a title-only rejection does not veto the same id a playlist later proves`() {
		val session = ended(
			"Ladii Rose ft Dej RoseGold Bounce (Official Video)",
			"Ladii Rose",
			137_000,
		).let {
			it.copy(
				resolverContext = it.resolverContext.copy(
					rejectedVideoIds = setOf("dE8D6WY6tQQ"),
					titleOnlyRejectedVideoIds = setOf("dE8D6WY6tQQ"),
				),
			)
		}
		val resolution = VideoResolution(
			videoId = "dE8D6WY6tQQ",
			source = "Mix queue RD2u5UTPEDGAw",
			title = "Ladii Rose ft Dej RoseGold Bounce (Official Video)",
			channel = "Ladii Rose",
			lengthSeconds = 137,
			uniquelyResolved = true,
			playlistVerified = true,
		)
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = resolution.title,
			author = resolution.channel,
			lengthSeconds = 137,
			watchPageResolved = true,
		)

		assertNull(
			"an independent playlist proof was vetoed by an earlier title-only rejection",
			VideoIdentityCorroborator.contradiction(session, resolution, facts),
		)
	}

	/**
	 * The narrowness, three ways. A rejection that was *not* title-only still
	 * vetoes; a route that is not an independent exact-id proof still vetoes; and
	 * an id that disagrees on the finalized fields is still refused by the
	 * ordinary gate underneath.
	 */
	@Test
	fun `the title-only exception admits nothing else`() {
		val base = ended("Some Track", "Some Channel", 137_000)
		val resolution = VideoResolution(
			videoId = "dE8D6WY6tQQ",
			source = "Mix queue RD2u5UTPEDGAw",
			title = "Some Track",
			channel = "Some Channel",
			lengthSeconds = 137,
			uniquelyResolved = true,
			playlistVerified = true,
		)
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = resolution.title,
			author = resolution.channel,
			lengthSeconds = 137,
			watchPageResolved = true,
		)

		// A duration disagreement is structural: that page is a different video.
		val durationRejected = base.copy(
			resolverContext = base.resolverContext.copy(
				rejectedVideoIds = setOf("dE8D6WY6tQQ"),
			),
		)
		assertNotNull(
			"a non-title rejection stopped vetoing",
			VideoIdentityCorroborator.contradiction(durationRejected, resolution, facts),
		)

		// Search is not an independent exact-id route and is not admitted.
		val titleOnly = base.copy(
			resolverContext = base.resolverContext.copy(
				rejectedVideoIds = setOf("dE8D6WY6tQQ"),
				titleOnlyRejectedVideoIds = setOf("dE8D6WY6tQQ"),
			),
		)
		assertNotNull(
			"a search result recovered a rejected id",
			VideoIdentityCorroborator.contradiction(
				titleOnly,
				resolution.copy(source = "search", playlistVerified = false),
				facts,
			),
		)

		// And the ordinary gate underneath still binds: a playlist entry whose
		// length contradicts the finalized listen is refused as before.
		assertNotNull(
			"the recovered id bypassed the finalized duration check",
			VideoIdentityCorroborator.contradiction(
				titleOnly,
				resolution.copy(lengthSeconds = 42),
				facts.copy(lengthSeconds = 42),
			),
		)
	}

	/**
	 * The row-backed route's own evidence has to survive its own finalization.
	 *
	 * [VideoIdResolver.cardBackedYouTubeMusicResolution] resolves an id from a
	 * YouTube Music row whose work *and complete credit* the player matched
	 * exactly, at the player's own length, requiring of the canonical page only
	 * that it be that length and name that work — deliberately, because such a
	 * page is often a distributor's upload that restates neither credit. Asking it
	 * to restate the credit here anyway threw those ids away at finalization and
	 * the listens they belonged to ended at zero.
	 *
	 * Nothing about the performer is decided by any of this. Artist-credit
	 * provenance is descriptive and cannot authorize or veto the verified id.
	 */
	@Test
	fun `a row-backed id survives on verified work and length regardless of page owner`() {
		val session = ended("Known Work", "Act One & Act Two", 219_000).copy(
			packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
			appLabel = "YouTube Music",
		)
		val credits = listOf("Act One", "Act Two")
		val resolution = VideoResolution(
			videoId = "rOwBaCkEd12",
			source = "exact YouTube Music video work+artist+duration",
			title = "Known Work",
			channel = credits.joinToString(", "),
			lengthSeconds = 219,
			uniquelyResolved = true,
			structuredNativeMusic = true,
			creditedArtists = credits,
			musicVideoRow = true,
			presentationDurationCorroborated = true,
		)
		// A distributor's upload: it publishes the work first, its own promo
		// suffix after, and an owner that is a company rather than an act.
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = "Known Work - Act One (Official Video) | Some Label",
			author = "Some Label Distribution",
			lengthSeconds = 219,
			watchPageResolved = true,
		)

		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))
		assertNotNull(
			"the catalog row must still name the finalized work",
			VideoIdentityCorroborator.contradiction(
				session, resolution.copy(title = "Another Work"), facts,
			),
		)
		assertNotNull(
			"the canonical page must still have the finalized presentation length",
			VideoIdentityCorroborator.contradiction(
				session, resolution, facts.copy(lengthSeconds = 400),
			),
		)
		assertNotNull(
			"the canonical page must still report the row's exact id",
			VideoIdentityCorroborator.contradiction(
				session, resolution, facts.copy(videoId = "oThErViDeO1"),
			),
		)
	}

	@Test
	fun `an owner YouTube binds to another act does not veto the verified id`() {
		val session = ended("Known Work", "Act One & Act Two", 219_000).copy(
			packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
			appLabel = "YouTube Music",
		)
		val credits = listOf("Act One", "Act Two")
		val resolution = VideoResolution(
			videoId = "rOwBaCkEd12",
			source = "exact YouTube Music video work+artist+duration",
			title = "Known Work",
			channel = credits.joinToString(", "),
			lengthSeconds = 219,
			uniquelyResolved = true,
			structuredNativeMusic = true,
			creditedArtists = credits,
		)
		val page = VideoFacts(
			videoId = resolution.videoId,
			title = "Known Work",
			lengthSeconds = 219,
			watchPageResolved = true,
		)

		// `- Topic` and `VEVO` are names YouTube generates for a rights holder, but a
		// name is still only who hosts the file. The work and the length verified this
		// id, and the uploader neither authorizes nor vetoes it.
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, page.copy(author = "Unrelated Artist - Topic"),
			),
		)
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, page.copy(author = "UnrelatedArtistVEVO"),
			),
		)
	}

	@Test
	fun `a page that names another work contradicts however its owner is spelled`() {
		val session = ended("Known Work", "Act One & Act Two", 219_000).copy(
			packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
			appLabel = "YouTube Music",
		)
		val credits = listOf("Act One", "Act Two")
		val resolution = VideoResolution(
			videoId = "rOwBaCkEd12",
			source = "exact YouTube Music video work+artist+duration",
			title = "Known Work",
			channel = credits.joinToString(", "),
			lengthSeconds = 219,
			uniquelyResolved = true,
			structuredNativeMusic = true,
			creditedArtists = credits,
		)
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = "Another Work - Act One (Official Video) | Some Label",
			author = "Some Label Distribution",
			lengthSeconds = 219,
			watchPageResolved = true,
		)

		assertNotNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))
		assertNotNull(
			"and the length still binds independently of the title",
			VideoIdentityCorroborator.contradiction(
				session,
				resolution,
				facts.copy(
					title = "Known Work - Act One (Official Video) | Some Label",
					lengthSeconds = 400,
				),
			),
		)
	}

	@Test
	fun `a route carrying no row credit is not offered the row-backed licence`() {
		val session = ended("Known Work", "Act One & Act Two", 219_000).copy(
			packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
			appLabel = "YouTube Music",
		)
		// The same page and the same length, but nothing proved a complete credit
		// for this id before it got here, so there is no row to be backed by.
		val resolution = VideoResolution(
			videoId = "rOwBaCkEd12",
			source = "structured native music title+artist+duration",
			title = "Known Work",
			channel = "Some Label Distribution",
			lengthSeconds = 219,
			uniquelyResolved = true,
			structuredNativeMusic = true,
		)
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = "Known Work - Act One (Official Video) | Some Label",
			author = "Some Label Distribution",
			lengthSeconds = 219,
			watchPageResolved = true,
		)

		assertNotNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))
	}

	private fun ended(
		title: String,
		channel: String,
		durationMs: Long,
		playlistId: String = "frozen-list",
	) = SessionSnapshot(
		packageName = "com.android.chrome",
		appLabel = "Chrome",
		isTarget = true,
		title = title,
		artist = channel,
		album = null,
		durationMs = durationMs,
		positionMs = durationMs,
		playedMs = durationMs,
		loopDetected = false,
		playbackState = "STOPPED",
		isPlaying = false,
		percentPlayed = 1.0,
		identity = YouTubeProbe.Identity.SiteOnly(
			host = "m.youtube.com",
			isMusic = false,
			source = "frozen notification",
		),
		resolverContext = ResolverContext(playlistId = playlistId),
		notificationHint = null,
		metadataLines = emptyList(),
		trackStartedAtEpochSec = 1_785_000_000,
	)

	@Test
	fun `a Topic channel alias does not veto a playlist-verified native id`() {
		val session = ended("Te Busco", "Cosculluela El Principe", 234_000).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube",
		)
		val resolution = VideoResolution(
			videoId = "7J6xA1_f8as",
			source = "playlist PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm",
			title = "Te Busco",
			channel = "Cosculluela El Principe",
			lengthSeconds = 234,
			uniquelyResolved = true,
			playlistVerified = true,
		)
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = "Te Busco",
			author = "Cosculluela - Topic",
			lengthSeconds = 234,
			watchPageResolved = true,
		)
		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))
	}

	/** The relaxation is for the channel alias only — a real mismatch still refuses. */
	@Test
	fun `a playlist-verified id is still refused when title or duration disagree`() {
		val session = ended("Te Busco", "Cosculluela El Principe", 234_000).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube",
		)
		val resolution = VideoResolution(
			videoId = "7J6xA1_f8as",
			source = "playlist PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm",
			title = "Te Busco",
			channel = "Cosculluela El Principe",
			lengthSeconds = 234,
			uniquelyResolved = true,
			playlistVerified = true,
		)
		val base = VideoFacts(
			videoId = resolution.videoId,
			title = "Te Busco",
			author = "Cosculluela - Topic",
			lengthSeconds = 234,
			watchPageResolved = true,
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, base.copy(title = "Un Verano Sin Ti"),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, base.copy(lengthSeconds = 95),
			),
		)
	}

	@Test
	fun `a VEVO channel alias does not veto a history-resolved native id`() {
		val session = ended(
			"Bring Me The Horizon - Kool-Aid (Official Video)",
			"Bring Me The Horizon",
			244_000,
		).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube",
		)
		val resolution = VideoResolution(
			videoId = "Jq4NhAnnD0Q",
			source = "watch history",
			title = "Bring Me The Horizon - Kool-Aid (Official Video)",
			channel = "Bring Me The Horizon",
			lengthSeconds = 244,
			uniquelyResolved = true,
			historyVerified = true,
		)
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = "Bring Me The Horizon - Kool-Aid (Official Video)",
			author = "BMTHOfficialVEVO",
			lengthSeconds = 244,
			watchPageResolved = true,
		)
		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))
		// The same relaxation for the route that resolved the other half of that
		// playlist, so neither the search nor the history path can regress alone.
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session,
				resolution.copy(
					source = "title+channel+duration search",
					historyVerified = false,
				),
				facts,
			),
		)
	}

	/**
	 * The id is what licenses the alias, so the guards that establish the id are
	 * exactly the ones that must survive it.
	 */
	@Test
	fun `a history id is bound by title and length and never by its uploader`() {
		val session = ended(
			"Bring Me The Horizon - Kool-Aid (Official Video)",
			"Bring Me The Horizon",
			244_000,
		).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube",
		)
		val resolution = VideoResolution(
			videoId = "Jq4NhAnnD0Q",
			source = "watch history",
			title = "Bring Me The Horizon - Kool-Aid (Official Video)",
			channel = "Bring Me The Horizon",
			lengthSeconds = 244,
			uniquelyResolved = true,
			historyVerified = true,
		)
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = "Bring Me The Horizon - Kool-Aid (Official Video)",
			author = "BMTHOfficialVEVO",
			lengthSeconds = 244,
			watchPageResolved = true,
		)
		// Whatever the route or the page says about the uploader, it is metadata:
		// a different or missing channel neither licenses nor refuses this id.
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution.copy(channel = "Some Other Uploader"), facts,
			),
		)
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution.copy(channel = null), facts,
			),
		)
		// Facts for a different video prove nothing about this one, and without an
		// owner veto they do not refuse it either.
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, facts.copy(videoId = "B9wvTuDC-H0"),
			),
		)
		// Title and duration still bind on the enriched pass.
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, facts.copy(title = "Bring Me The Horizon - Ludens"),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, facts.copy(lengthSeconds = 95),
			),
		)
	}

	@Test
	fun `a browser route id is bound by title and length and never by its uploader`() {
		val session = ended("Te Busco", "Cosculluela El Principe", 234_000)
		val resolution = VideoResolution(
			videoId = "7J6xA1_f8as",
			source = "playlist PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm",
			title = "Te Busco",
			channel = "Cosculluela El Principe",
			lengthSeconds = 234,
			uniquelyResolved = true,
			playlistVerified = true,
		)
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = "Te Busco",
			author = "Cosculluela - Topic",
			lengthSeconds = 234,
			watchPageResolved = true,
		)
		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))

		// The address bar named the id; the uploader the page reports is metadata
		// and does not refuse it.
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session,
				resolution.copy(
					source = "frozen address bar",
					uniquelyResolved = false,
					playlistVerified = false,
					channel = "Cosculluela - Topic",
				),
				facts,
			),
		)
		// Nor does a route whose own channel never agreed with the session artist.
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution.copy(channel = "Some Other Uploader"), facts,
			),
		)
		// Facts belonging to a different video corroborate nothing and veto nothing.
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, facts.copy(videoId = "B9wvTuDC-H0"),
			),
		)
		// Title and duration still bind on the enriched pass.
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, facts.copy(title = "Otra Cosa"),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, facts.copy(lengthSeconds = 95),
			),
		)
	}

	@Test
	fun `saGYMhApaH8 can never be completed by La Bebe 3mchJ-EW9rM`() {
		val session = ended("Me Porto Bonito", "Bad Bunny", 191_000)
		val next = VideoResolution(
			videoId = "3mchJ-EW9rM",
			source = "later foreground",
			title = "La Bebe (Remix)",
			channel = "Yng Lvcas",
			lengthSeconds = 191,
		)
		val mismatch = VideoIdentityCorroborator.contradiction(
			session,
			next,
			VideoFacts(
				videoId = "3mchJ-EW9rM",
				title = "La Bebe (Remix)",
				author = "Yng Lvcas",
				lengthSeconds = 191,
			),
		)
		assertNotNull(mismatch)
		assertTrue(mismatch!!.contains("contradicts ended"))
	}

	@Test
	fun `aZaxQG3ggng can never be completed by Coming Home 2QqyPy2itXw`() {
		val session = ended(
			"YCB Frenzy - Crazy ( Official Video ) Shot and edited by: @Maggie Rudisill",
			"YCB Frenzy",
			192_000,
		)
		val next = VideoResolution(
			videoId = "2QqyPy2itXw",
			source = "later foreground",
			title = "Coming Home SHOT BY: @SHONMAC071",
			channel = "E.K THE NKABK",
			lengthSeconds = 192,
		)
		assertNotNull(VideoIdentityCorroborator.contradiction(session, next, null))
	}

	@Test
	fun `a later foreground id without facts cannot replace observed ended identity`() {
		val session = ended("Me Porto Bonito", "Bad Bunny", 191_000).copy(
			identity = YouTubeProbe.Identity.Confirmed(
				videoId = "3mchJ-EW9rM",
				url = "https://www.youtube.com/watch?v=3mchJ-EW9rM",
				isMusic = false,
				source = "transition frame",
			),
			resolverContext = ResolverContext(observedVideoId = "saGYMhApaH8"),
		)
		val nextWithoutFacts = VideoResolution(
			videoId = "3mchJ-EW9rM",
			source = "frozen transition frame",
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(session, nextWithoutFacts, facts = null),
		)
	}

	@Test
	fun `corroborated frozen candidate remains eligible`() {
		val session = ended("Me Porto Bonito", "Bad Bunny", 191_000)
		val own = VideoResolution(
			videoId = "saGYMhApaH8",
			source = "frozen playlist",
			title = "BAD BUNNY x CHENCHO CORLEONE - ME PORTO BONITO",
			channel = "Bad Bunny",
			lengthSeconds = 192,
		)
		assertNull(VideoIdentityCorroborator.contradiction(session, own, null))
	}

	@Test
	fun `exact native media id accepts clean short metadata with duration corroboration`() {
		val id = "oG-4Uvhm4lI"
		val native = ended("Poker Face", "Lady Gaga", 237_000).copy(
			packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
			appLabel = "YouTube Music",
			identity = YouTubeProbe.Identity.Confirmed(
				videoId = id,
				url = "https://www.youtube.com/watch?v=$id",
				isMusic = true,
				exactIdRoute = "media id",
				source = "native media id",
			),
		)
		val resolution = VideoResolution(
			videoId = id,
			source = "native media id",
			title = "Lady Gaga - Poker Face (Official Music Video)",
			channel = "LadyGagaVEVO",
			lengthSeconds = 237,
		)

		assertNull(VideoIdentityCorroborator.contradiction(native, resolution, null))
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				native,
				resolution.copy(title = "A Different Song"),
				null,
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				native,
				resolution.copy(lengthSeconds = 600),
				null,
			),
		)
	}

	@Test
	fun `structured native music proof rechecks parsed facts and never applies to browser`() {
		val native = ended("No Quiere Enamorarse", "Ozuna", 213_000).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube",
		)
		val resolution = VideoResolution(
			videoId = "5YXxnHVYRDk",
			source = "structured native music title+artist+duration",
			title = "Ozuna - No Quiere Enamorarse (Official Lyric Video)",
			channel = "Ozunapr",
			lengthSeconds = 213,
			uniquelyResolved = true,
			structuredNativeMusic = true,
		)
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = resolution.title,
			author = resolution.channel,
			lengthSeconds = resolution.lengthSeconds,
			watchPageResolved = true,
		)

		assertNull(VideoIdentityCorroborator.contradiction(native, resolution, facts))
		assertFalse(VideoIdentityCorroborator.cacheable(native, resolution, facts))
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				native, resolution, facts.copy(title = "A different song"),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				ended("No Quiere Enamorarse", "Ozuna", 213_000), resolution, facts,
			),
		)
	}

	@Test
	fun `catalog collaboration is complete while its topic page may name one proven credit`() {
		val session = ended(
			"So High", "Walshy Fire, Lizi & Mr. Vegas", 168_000,
		).copy(
			packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
			appLabel = "YouTube Music",
		)
		val credits = listOf("Mr. Vegas", "Lizi", "Walshy Fire")
		val resolution = VideoResolution(
			videoId = "0INJOMzG0rM",
			source = "exact YouTube Music catalog work+artist+album+duration",
			title = "So High",
			channel = credits.joinToString(", "),
			lengthSeconds = 168,
			uniquelyResolved = true,
			structuredNativeMusic = true,
			creditedArtists = credits,
		)
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = "So High",
			author = "Mr. Vegas - Topic",
			lengthSeconds = 168,
			watchPageResolved = true,
		)

		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, facts.copy(author = "Unrelated Artist - Topic"),
			),
		)
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution.copy(creditedArtists = listOf("Unrelated Artist")), facts,
			),
		)
	}

	@Test
	fun `catalog authority survives same-id music-client-only corroboration`() {
		val session = ended(
			"Party", "Tommy Lee Sparta, DEMARCO & Dinesty King", 160_542,
		).copy(
			packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
			appLabel = "YouTube Music",
			album = "Party",
		)
		val credits = listOf("Tommy Lee Sparta", "DEMARCO", "Dinesty King")
		val resolution = VideoResolution(
			videoId = "oJUH2xBkzBM",
			source = "exact YouTube Music catalog work+artist+album+duration",
			title = "Party",
			channel = credits.joinToString(", "),
			lengthSeconds = 160,
			uniquelyResolved = true,
			structuredNativeMusic = true,
			creditedArtists = credits,
		)
		val musicClientOnly = VideoFacts(
			videoId = resolution.videoId,
			category = "Music",
			originalArtist = "Tommy Lee Sparta, DEMARCO, & Dinesty King",
			musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
			watchPageResolved = false,
		)

		assertNull(
			VideoIdentityCorroborator.contradiction(session, resolution, musicClientOnly),
		)
		assertNotNull(
			"music-client-only catalog corroboration leaked into native YouTube",
			VideoIdentityCorroborator.contradiction(
				session.copy(
					packageName = YouTubeProbe.YOUTUBE_PACKAGE,
					appLabel = "YouTube",
				),
				resolution,
				musicClientOnly,
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session,
				resolution,
				musicClientOnly.copy(originalArtist = "Unrelated Artist"),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session,
				resolution,
				musicClientOnly.copy(musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session,
				resolution,
				musicClientOnly.copy(
					title = "A different work",
					author = "Tommy Lee Sparta - Topic",
					lengthSeconds = 160,
					watchPageResolved = true,
				),
			),
		)
	}

	@Test
	fun `a topic page alias is corroborated by the music client credit for the same id`() {
		val session = ended("Who Dem", "Lexxus", 217_000).copy(
			packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
			appLabel = "YouTube Music",
		)
		val resolution = VideoResolution(
			videoId = "xhVZTtL44QI",
			source = "exact YouTube Music catalog work+artist+album+duration",
			title = "Who Dem",
			channel = "Lexxus",
			lengthSeconds = 218,
			uniquelyResolved = true,
			structuredNativeMusic = true,
			creditedArtists = listOf("Lexxus"),
		)
		// The watch page loaded and named a different alias; the music client for
		// the same id names the act the player published.
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = "Who Dem",
			author = "Mr. Lexx - Topic",
			lengthSeconds = 217,
			watchPageResolved = true,
			musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
			originalArtist = "Lexxus",
		)

		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))
		// The music client may stand in for the name and nothing else: a page that
		// describes a different work still vetoes.
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, facts.copy(title = "A different work"),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, facts.copy(lengthSeconds = 400),
			),
		)
		// The music-client credit is artist metadata like the page's owner: it may
		// corroborate the name, but a different one does not veto the verified id.
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, facts.copy(originalArtist = "Unrelated Artist"),
			),
		)
	}

	@Test
	fun `CJjvg7PbE4w observed Nunca Me Amo survives uploader versus credit roles`() {
		val id = "CJjvg7PbE4w"
		val session = ended("Nunca Me Amó", "Boy Wonder Chosen Few", 204_000).copy(
			resolverContext = ResolverContext(
				observedVideoId = id,
				urlGeneration = 41,
			),
		)
		val resolution = VideoResolution(
			videoId = id,
			source = "frozen current-generation URL",
			title = "Nunca Me Amó",
			channel = "Jon Z, Baby Rasta, & Boy Wonder CF",
			lengthSeconds = 204,
		)
		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, null))
	}

	@Test
	fun `channel agreement alone cannot rescue title or duration contradiction`() {
		val id = "CJjvg7PbE4w"
		val session = ended("Nunca Me Amó", "Boy Wonder Chosen Few", 204_000).copy(
			resolverContext = ResolverContext(observedVideoId = id, urlGeneration = 41),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session,
				VideoResolution(
					id, "same channel", "Adjacent Track", "Boy Wonder Chosen Few", 204,
				),
				null,
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session,
				VideoResolution(
					id, "same channel", "Nunca Me Amó", "Boy Wonder Chosen Few", 600,
				),
				null,
			),
		)
	}

	@Test
	fun `a different uploader of the same title and length is not refused by its channel`() {
		// Uniqueness is the resolving route's to enforce — every search and history
		// route already refuses an ambiguous set. The corroborator binds title and
		// length, and a different uploader name is metadata, not a contradiction.
		val session = ended("Nunca Me Amó", "Boy Wonder Chosen Few", 204_000)
		val otherUpload = VideoResolution(
			videoId = "otherUpload1",
			source = "search-only",
			title = "Nunca Me Amó",
			channel = "Different Uploader",
			lengthSeconds = 204,
		)
		assertNull(VideoIdentityCorroborator.contradiction(session, otherUpload, null))
		assertNotNull(
			"its length still binds",
			VideoIdentityCorroborator.contradiction(session, otherUpload.copy(lengthSeconds = 150), null),
		)
	}

	@Test
	fun `unique exact OMV collaborative byline accepts complete leading owner`() {
		val session = ended("CENTRAL CEE - BOOGA (MUSIC VIDEO)", "Central Cee", 110_000)
		val resolution = VideoResolution(
			videoId = "JmeUtPih4U8",
			source = "title+channel+duration search",
			title = "CENTRAL CEE - BOOGA (MUSIC VIDEO)",
			channel = "Central Cee and LIVE YOURS",
			lengthSeconds = 110,
			uniquelyResolved = true,
			collaborativeChannel = true,
		)
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = resolution.title,
			author = resolution.channel,
			category = "Music",
			lengthSeconds = 110,
			musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
		)
		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, facts.copy(author = "Central Cee"),
			),
		)
	}

	@Test
	fun `collaborative byline needs complete owner separator title duration and hard music`() {
		val session = ended("Owner - Work (Music Video)", "Owner", 110_000)
		val base = VideoResolution(
			videoId = "abcdefghijk",
			source = "unique search",
			title = "Owner - Work (Music Video)",
			channel = "Owner and Collaborator",
			lengthSeconds = 110,
			uniquelyResolved = true,
			collaborativeChannel = true,
		)
		fun facts(
			title: String? = base.title,
			author: String? = base.channel,
			length: Long? = 110,
			type: String? = "MUSIC_VIDEO_TYPE_OMV",
		) = VideoFacts(
			videoId = base.videoId,
			title = title,
			author = author,
			category = "Music",
			lengthSeconds = length,
			musicVideoType = type,
		)

		assertNull(VideoIdentityCorroborator.contradiction(session, base, facts(type = null)))
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(collaborativeChannel = false), facts(),
			),
		)
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(uniquelyResolved = false), facts(),
			),
		)
		// Title and duration still bind; the byline and the session artist are
		// metadata and neither rescue nor refuse the id below.
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(lengthSeconds = 45), facts(length = 45),
			),
		)
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(channel = "Owner Collaborator"),
				facts(author = "Owner Collaborator"),
			),
		)
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session.copy(artist = "Own"), base, facts(),
			),
		)
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(channel = "Owner Fan and Collaborator"),
				facts(author = "Owner Fan and Collaborator"),
			),
		)
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(channel = "Another Owner and Collaborator"),
				facts(author = "Another Owner and Collaborator"),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(title = "Wrong Work"), facts(),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(lengthSeconds = 140), facts(length = 140),
			),
		)
	}

	@Test
	fun `weak title cannot let a successor complete an ended track`() {
		val session = ended("J. Balvin - Ay Vamos (Official Video)", "jbalvinVEVO", 266_921).copy(
			resolverContext = ResolverContext(
				observedVideoId = "TapXs54Ah3E",
				urlGeneration = 52,
			),
		)
		val successor = VideoResolution(
			videoId = "at1axdFpcgI",
			source = "successor",
			title = "Ay Vamos",
			channel = "jbalvinVEVO",
			lengthSeconds = 266,
		)
		assertNotNull(VideoIdentityCorroborator.contradiction(session, successor, null))
	}

	@Test
	fun `finalized guard shares the presentation matcher`() {
		assertTrue(
			VideoIdentityCorroborator.titleEvidence(
				"BAD BUNNY - SOY PEOR (Video Oficial)",
				"BAD BUNNY - SOY PEOR (Official Video)",
			) != com.rustedwax.app.detect.VideoTitleMatcher.Evidence.CONTRADICTION,
		)
		assertTrue(
			VideoIdentityCorroborator.titleEvidence(
				"BAD BUNNY x JHAY CORTEZ - DÁKITI (Video Oficial)",
				"BAD BUNNY x JHAY CORTEZ - DÁKITI | EL ÚLTIMO TOUR DEL MUNDO " +
					"(Official Video)",
			) != com.rustedwax.app.detect.VideoTitleMatcher.Evidence.CONTRADICTION,
		)
		assertFalse(
			VideoIdentityCorroborator.titleEvidence(
				"BAD BUNNY x JHAY CORTEZ - DÁKITI (Video Oficial)",
				"FloyyMenor, Cris MJ - Gata Only (Video Oficial)",
			) != com.rustedwax.app.detect.VideoTitleMatcher.Evidence.CONTRADICTION,
		)
	}

	@Test
	fun `an auto-translated displayed title is not a contradiction`() {
		val onScreen = "The Day Karol G Experienced an Unexpected Moment During a Concert"
		val session = ended(onScreen, "@enefectoescine17", 60_000).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube Shorts (foreground)",
			sourceProof = SourceProof.NATIVE_FOREGROUND_SHORT,
			ownerHandle = "@enefectoescine17",
		)
		val resolution = VideoResolution(
			videoId = "QnRnooyKeZk",
			source = "watch history",
			title = "El D\u00eda que Karol G Vivi\u00f3 un Momento Inesperado en Pleno Concierto",
			channel = "EN EFECTO ES CINE",
			lengthSeconds = 60,
			uniquelyResolved = true,
			ownerHandle = "@enefectoescine17",
			localizedTitle = onScreen,
		)
		val facts = VideoFacts(
			videoId = "QnRnooyKeZk",
			title = "El D\u00eda que Karol G Vivi\u00f3 un Momento Inesperado en Pleno Concierto",
			author = "EN EFECTO ES CINE",
			ownerHandle = "@enefectoescine17",
			lengthSeconds = 60,
		)
		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))
	}

	/** A displayed title that is not the frozen one stays a contradiction. */
	@Test
	fun `an unrelated displayed title does not rescue a wrong candidate`() {
		val session = ended(
			"The Day Karol G Experienced an Unexpected Moment During a Concert",
			"@enefectoescine17",
			60_000,
		).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube Shorts (foreground)",
			sourceProof = SourceProof.NATIVE_FOREGROUND_SHORT,
			ownerHandle = "@enefectoescine17",
		)
		val resolution = VideoResolution(
			videoId = "QnRnooyKeZk",
			source = "watch history",
			title = "Something entirely different",
			channel = "EN EFECTO ES CINE",
			lengthSeconds = 60,
			uniquelyResolved = true,
			ownerHandle = "@enefectoescine17",
			localizedTitle = "Also entirely different",
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session,
				resolution,
				VideoFacts(
					videoId = "QnRnooyKeZk",
					title = "Something entirely different",
					author = "EN EFECTO ES CINE",
					ownerHandle = "@enefectoescine17",
					lengthSeconds = 60,
				),
			),
		)
	}

	@Test
	fun `either of a page's two titles may corroborate an all-hashtag Short`() {
		val onScreen = "#xbox​ #trendingnow​ #rap​ #musica​ #hiphop​ " +
			"#hiphopindiadancerealtyshowand​ #noticias​ #pubg​ #hindisong​"
		val uploaded = "#xbox #trendingnow #rap #musica #hiphop " +
			"#hiphopindiadancerealtyshowand #noticias #pubg #hindisong"
		val translated = "#xbox #trendingnow #rap #music #hiphop " +
			"#hiphopindiadancerealtyshowand #news #pubg #hindisong"
		val session = ended(onScreen, "@shortsvideo", 21_000).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube Shorts (foreground)",
			sourceProof = SourceProof.NATIVE_FOREGROUND_SHORT,
			ownerHandle = "@shortsvideo",
		)
		val resolution = VideoResolution(
			videoId = "RTQFqbCPUGg",
			source = "run-local verified candidate",
			title = uploaded,
			channel = "Shorts Video",
			lengthSeconds = 21,
			uniquelyResolved = true,
			ownerHandle = "@shortsvideo",
			localizedTitle = translated,
		)
		val facts = VideoFacts(
			videoId = "RTQFqbCPUGg",
			title = uploaded,
			author = "Shorts Video",
			ownerHandle = "@shortsvideo",
			lengthSeconds = 21,
		)
		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))

		// The translated rendering alone still corroborates: it is the name the
		// screen shows when the phone itself is the one being translated for.
		assertNull(
			VideoIdentityCorroborator.contradiction(
				session.copy(title = translated),
				resolution,
				facts,
			),
		)
	}

	/** Exact reproduction of the @emoções_videos PiP finalization refusal. */
	@Test
	fun `an exact emoji-only foreground title survives finalized corroboration`() {
		val title = "🥰❤️"
		val session = ended(title, "@emoções_videos", 60_000).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube Shorts (foreground)",
			sourceProof = SourceProof.NATIVE_FOREGROUND_SHORT,
			ownerHandle = "@emoções_videos",
		)
		val resolution = VideoResolution(
			videoId = "-8yHg3sb55I",
			source = "run-local verified candidate",
			title = title,
			channel = "Emoções Vídeos",
			lengthSeconds = 60,
			uniquelyResolved = true,
			ownerHandle = "@emoções_videos",
		)
		val facts = VideoFacts(
			videoId = resolution.videoId,
			title = title,
			author = resolution.channel,
			ownerHandle = resolution.ownerHandle,
			lengthSeconds = 60,
		)

		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))
	}

	/** Neither name agreeing is still a refusal, and the message names both. */
	@Test
	fun `a candidate whose two titles both disagree is still refused`() {
		val session = ended("Nicki Minaj - Barbie Tingz", "@NickiMinaj", 60_000).copy(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube Shorts (foreground)",
			sourceProof = SourceProof.NATIVE_FOREGROUND_SHORT,
			ownerHandle = "@NickiMinaj",
		)
		val resolution = VideoResolution(
			videoId = "IcrbM1l_BoI",
			source = "run-local verified candidate",
			title = "Some other upload",
			channel = "Nicki Minaj",
			lengthSeconds = 60,
			uniquelyResolved = true,
			ownerHandle = "@NickiMinaj",
			localizedTitle = "Otra subida distinta",
		)
		val refusal = VideoIdentityCorroborator.contradiction(
			session,
			resolution,
			VideoFacts(
				videoId = "IcrbM1l_BoI",
				title = "Some other upload",
				author = "Nicki Minaj",
				ownerHandle = "@NickiMinaj",
				lengthSeconds = 60,
			),
		)
		assertNotNull(refusal)
		assertTrue(refusal!!.contains("Some other upload"))
		assertTrue(refusal.contains("Otra subida distinta"))
	}

	@Test
	fun `the watch page's shorter name for an already-matched id is not weak evidence`() {
		val session = ended("Bring Me The Horizon - Avalanche (Official Video)", "BMTHOfficialVEVO", 275_000)
		val resolution = VideoResolution(
			videoId = "UNaYpBpRJOY",
			source = "playlist PLdp3KzF76wW-GiAbwN_TcL-0GNB5HOJ84",
			title = "Bring Me The Horizon - Avalanche (Official Video)",
			channel = "BMTHOfficialVEVO",
			lengthSeconds = 275,
			uniquelyResolved = true,
			playlistVerified = true,
		)
		val facts = VideoFacts(
			videoId = "UNaYpBpRJOY",
			title = "Avalanche (Official Video)",
			author = "BMTHOfficialVEVO",
			lengthSeconds = 275,
			watchPageResolved = true,
		)
		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))

		// The duration is what is left holding the id, so it still has to agree.
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session,
				resolution,
				facts.copy(lengthSeconds = 61),
			),
		)
		// And the escape is licensed by the route's own title match. A route
		// whose title did not agree gets nothing.
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session,
				resolution.copy(title = "Bring Me The Horizon - Throne", lengthSeconds = null),
				facts,
			),
		)
		// A page title that names a different work still refuses outright.
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session,
				resolution,
				facts.copy(title = "Sleepwalking (Official Video)"),
			),
		)
	}

	@Test
	fun `unique finalized title plus canonical channel survives missing session duration`() {
		val session = ended(
			"Bring Me The Horizon - Sleepwalking",
			"BMTHOfficialVEVO",
			236_000,
		).copy(durationMs = null, positionMs = null, percentPlayed = null)
		val resolution = VideoResolution(
			videoId = "lir3dzYIhz0",
			source = "watch history",
			title = "Bring Me The Horizon - Sleepwalking",
			channel = "Bring Me The Horizon",
			lengthSeconds = 237,
			uniquelyResolved = true,
			historyVerified = true,
		)
		val facts = VideoFacts(
			videoId = "lir3dzYIhz0",
			title = "Sleepwalking",
			author = "BMTHOfficialVEVO",
			lengthSeconds = 237,
			watchPageResolved = true,
		)

		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session,
				resolution,
				facts.copy(author = "A cover channel"),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session,
				resolution,
				facts.copy(title = "Throne"),
			),
		)
	}

	@Test
	fun `same-generation address bar id accepts its own shorter watch title`() {
		val videoId = "lir3dzYIhz0"
		val generation = 13L
		val session = ended(
			"Bring Me The Horizon - Sleepwalking", "BMTHOfficialVEVO", 236_741,
		).copy(
			identity = YouTubeProbe.Identity.Confirmed(
				videoId = videoId,
				url = "https://www.youtube.com/watch?v=$videoId",
				isMusic = false,
				urlGeneration = generation,
				source = "address bar + notification (latched)",
			),
			resolverContext = ResolverContext(
				urlGeneration = generation,
				observedVideoId = videoId,
				observedUrl = "m.youtube.com/watch?v=$videoId",
			),
		)
		val resolution = VideoResolution(
			videoId = videoId,
			source = "frozen address bar + notification",
			title = "Sleepwalking",
			channel = "BMTHOfficialVEVO",
			lengthSeconds = 237,
		)
		val facts = VideoFacts(
			videoId = videoId,
			title = "Sleepwalking",
			author = "Bring Me The Horizon",
			lengthSeconds = 237,
			watchPageResolved = true,
		)

		assertNull(VideoIdentityCorroborator.contradiction(session, resolution, facts))
	}

	// ── the uploader is metadata, not identity ───────────────────────────────

	private fun musicSession(title: String, artist: String, durationMs: Long) =
		ended(title, artist, durationMs).copy(
			packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
			appLabel = "YouTube Music",
		)

	@Test
	fun `a label distributor or fan uploader does not veto a verified title and length`() {
		val session = musicSession("El Preso", "Fruko & Wilson Saoko", 292_000)
		listOf("Discos Fuentes Edimusica", "salsa uploads 1985", "Fruko y Sus Tesos Oficial").forEach { owner ->
			val resolution = VideoResolution(
				videoId = "FN5oLBXiNvM",
				source = "search",
				title = "El Preso",
				channel = owner,
				lengthSeconds = 292,
				uniquelyResolved = true,
			)
			val facts = VideoFacts(
				videoId = resolution.videoId,
				title = "El Preso",
				author = owner,
				lengthSeconds = 292,
				watchPageResolved = true,
			)
			assertNull(owner, VideoIdentityCorroborator.contradiction(session, resolution, facts))
		}
	}

	@Test
	fun `an uploader named exactly like the artist cannot rescue a wrong title or length`() {
		val session = musicSession("El Preso", "Fruko & Wilson Saoko", 292_000)
		val sameOwner = "Fruko & Wilson Saoko"
		val wrongWork = VideoResolution(
			videoId = "wRoNgWoRk12",
			source = "search",
			title = "Los Charcos",
			channel = sameOwner,
			lengthSeconds = 292,
			uniquelyResolved = true,
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session,
				wrongWork,
				VideoFacts(
					videoId = wrongWork.videoId,
					title = "Los Charcos",
					author = sameOwner,
					lengthSeconds = 292,
					watchPageResolved = true,
				),
			),
		)
		val wrongLength = wrongWork.copy(videoId = "wRoNgLeN123", title = "El Preso", lengthSeconds = 331)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session,
				wrongLength,
				VideoFacts(
					videoId = wrongLength.videoId,
					title = "El Preso",
					author = sameOwner,
					lengthSeconds = 331,
					watchPageResolved = true,
				),
			),
		)
	}

	@Test
	fun `a distributor-hosted video row still refuses a page reporting another id or length`() {
		val session = musicSession("El Preso", "Fruko & Wilson Saoko", 292_000)
		val row = VideoResolution(
			videoId = "FN5oLBXiNvM",
			source = "exact YouTube Music video work+artist+duration",
			title = "El Preso",
			channel = "Fruko & Wilson Saoko",
			lengthSeconds = 292,
			uniquelyResolved = true,
			structuredNativeMusic = true,
			musicVideoRow = true,
			creditedArtists = listOf("Fruko & Wilson Saoko"),
		)
		val page = VideoFacts(
			videoId = row.videoId,
			title = "El Preso - Fruko y Sus Tesos (Video Oficial)",
			author = "Discos Fuentes Edimusica",
			lengthSeconds = 292,
			watchPageResolved = true,
		)

		assertNull(VideoIdentityCorroborator.contradiction(session, row, page))
		assertNotNull(
			"the page must still be the row's exact id",
			VideoIdentityCorroborator.contradiction(session, row, page.copy(videoId = "sOmEoThEr12")),
		)
		assertNotNull(
			"and the player's length",
			VideoIdentityCorroborator.contradiction(session, row, page.copy(lengthSeconds = 400)),
		)
	}
}
