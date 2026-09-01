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
		// A handle the enrichment fetch did not carry is absence, not
		// contradiction: the candidate's own page proved it for this same id a
		// moment earlier. Measured 2026-08-07, the strict rule refused an
		// 83-second listen because the second read of one page came back without
		// the field. A handle that is present and *different* still refuses.
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

	// ---- 2026-08-16 Brave Mix regression: the title-only veto -----------------

	/**
	 * `dE8D6WY6tQQ`, the exact field shape.
	 *
	 * The address bar named the right video and the page/session titles were
	 * written differently, so the id was filed as rejected while the track was
	 * active. Eighty seconds later the Mix queue independently returned that same
	 * id, having matched the entry's own title, channel and duration — and the
	 * veto threw a complete 138-of-137-second listen away for the earlier,
	 * weaker disagreement.
	 */
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

	/**
	 * Measured 2026-08-04, native YouTube, playlist `Reggaeton 2016,17,18`.
	 *
	 * `7J6xA1_f8as` is entry #23 and is genuinely the track that played — "Te
	 * Busco", 234 s, 233 s of it watched. But YouTube spells its channel two
	 * ways: the playlist page and the MediaSession both say
	 * "Cosculluela El Principe" while the watch page says "Cosculluela - Topic".
	 * Stripping " - Topic" leaves "Cosculluela", still not the full stage name,
	 * so the enriched-watch-facts pass vetoed a correct id the playlist had
	 * already corroborated and the scrobble was silently lost.
	 */
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

	/**
	 * Measured 2026-08-09, native YouTube, a Bring Me The Horizon playlist left
	 * running while the phone installed apps in the background.
	 *
	 * Every VEVO upload has two names: the MediaSession publishes the artist
	 * ("Bring Me The Horizon") and the watch page publishes the label's channel
	 * ("BMTHOfficialVEVO"). The enriched pass called that a contradiction 46
	 * times in one log — for Doja Cat, Nicki Minaj, Doechii, FLO, Danna Paola
	 * and Los Enanitos Verdes as well — and none of them came from a playlist,
	 * so the `playlistVerified` form of the exception never fired.
	 *
	 * The cost was not only the lost scrobble. The same corroborator answers
	 * `resolveNativeCarryIdentity`, so the refusal left the track with no exact
	 * id, and when YouTube's MediaSession was torn down and recreated mid-video
	 * — constantly, on a device busy installing apps — `deferForContinuation`
	 * would not carry the progress across. "Kool-Aid" was watched start to
	 * finish and scored as 114s of 244s (47%) plus 130s of 244s (53%): two
	 * halves of one complete listen, both below the 60% threshold, nothing
	 * broadcast.
	 */
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
	fun `the channel alias still refuses without a matching route channel or id`() {
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
		// The route never matched a channel of its own: nothing has ever agreed
		// with the session artist, so the watch page is the only opinion there is
		// and it disagrees.
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution.copy(channel = "Some Other Uploader"), facts,
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution.copy(channel = null), facts,
			),
		)
		// Facts for a different video prove nothing about this one, so the two
		// names are no longer two spellings of one uploader.
		assertNotNull(
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

	/**
	 * What licenses the alias is the route, not the player.
	 *
	 * This is the 2026-08-04 case: the playlist page and the MediaSession both
	 * said "Cosculluela El Principe" while the watch page said
	 * "Cosculluela - Topic", and the watch page vetoed an id the playlist had
	 * already corroborated on all three fields. It stayed vetoed in a browser
	 * only because the 2026-08-09 repair was scoped to native sessions; the same
	 * shape then cost a 216-second "Happy Song" listen in Brave on 2026-08-10.
	 *
	 * The boundary that replaced `isNative` is below: an id a *route* uniquely
	 * resolved by matching a channel of its own against the session, versus an
	 * id the address bar merely named.
	 */
	@Test
	fun `a browser route that matched a channel of its own is trusted too`() {
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

		// The address bar named the id; no route matched a channel against the
		// session, so the watch page is the only opinion there is.
		assertNotNull(
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
		// Even a uniquely-resolved route gets nothing when its own channel never
		// agreed with the session artist.
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution.copy(channel = "Some Other Uploader"), facts,
			),
		)
		// Facts belonging to a different video are not a second name for this one.
		assertNotNull(
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
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution, facts.copy(author = "Unrelated Artist - Topic"),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, resolution.copy(creditedArtists = listOf("Unrelated Artist")), facts,
			),
		)
	}

	/**
	 * Fresh 2026-08-21 `Party` failure. The exact catalog row had already bound
	 * work, complete credit, album, duration and id. The final enrichment for
	 * that same id was music-client-only, so absent watch-page title/length must
	 * not be converted into a contradiction.
	 */
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

	/**
	 * Measured 2026-08-23. A `- Topic` channel carries a different alias of the
	 * same act than the catalogue does — `Mr. Lexx - Topic` for catalogue artist
	 * `Lexxus` — so the page cannot be the authority on the name. The music
	 * client, asked for that same id, returns `Lexxus`.
	 */
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
		// And the music-client credit itself must agree.
		assertNotNull(
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
	fun `same title different unobserved upload retains conservative channel guard`() {
		val session = ended("Nunca Me Amó", "Boy Wonder Chosen Few", 204_000)
		val otherUpload = VideoResolution(
			videoId = "otherUpload1",
			source = "search-only",
			title = "Nunca Me Amó",
			channel = "Different Uploader",
			lengthSeconds = 204,
		)
		assertNotNull(VideoIdentityCorroborator.contradiction(session, otherUpload, null))
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

		// A byline whose *leader* is the ended channel, with the title and the
		// duration also agreeing, is the same upload described at greater length
		// — not a contradiction. Measured 2026-08-05: 12 rejections in one
		// session on "La Melma Music and 2 more" against "La Melma Music" and
		// "Eladio Carrion and CAZZU" against "Eladio Carrion", every one a real
		// listen thrown away. The earlier rule additionally demanded a
		// YouTube-Music-recognised video and a uniquely-resolved candidate, which
		// the field showed is not how these arrive — history resolves them, so
		// those flags are false.
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
		// The three fields still all have to agree: a byline leader match cannot
		// rescue a contradicting duration.
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(lengthSeconds = 45), facts(length = 45),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(channel = "Owner Collaborator"),
				facts(author = "Owner Collaborator"),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session.copy(artist = "Own"), base, facts(),
			),
		)
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				session, base.copy(channel = "Owner Fan and Collaborator"),
				facts(author = "Owner Fan and Collaborator"),
			),
		)
		assertNotNull(
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
	fun `finalized guard shares the log 16 presentation matcher`() {
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

	/**
	 * Measured 2026-08-05. The resolver found `QnRnooyKeZk` correctly from the
	 * account's own watch history, and this guard then discarded it because
	 * YouTube's uploaded title is Spanish while the phone — and the foreground
	 * observer reading its screen — shows the auto-translated English one. One
	 * video, two names, both from its own page.
	 */
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

	/**
	 * Measured 2026-08-06 on `RTQFqbCPUGg`, and the mirror image of the Karol G
	 * case above: there the *uploaded* title was Spanish and the screen showed
	 * English, here the upload is Spanish and the screen shows it while the
	 * resolver's own `en-US` fetch of the same page renders the auto-translated
	 * English one.
	 *
	 * Verified by fetching the page twice on 2026-08-06:
	 *
	 * | `Accept-Language` | `videoDetails.title` | `videoPrimaryInfoRenderer` |
	 * | --- | --- | --- |
	 * | `en-US` | `#musica … #noticias` | `#music … #news` |
	 * | `es-419` | `#musica … #noticias` | `#musica … #noticias` |
	 *
	 * The old guard substituted the displayed title whenever its title *key*
	 * equalled the frozen one — and an all-hashtag title has an empty key, so
	 * every such title matched vacuously and the English rendering replaced the
	 * Spanish one the screen had actually shown. A page publishes two names for
	 * one id; agreement with either is agreement.
	 */
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

	/**
	 * Measured 2026-08-11 in Brave, browser minimized, playlist
	 * `Best of Bring Me The Horizon`.
	 *
	 * `UNaYpBpRJOY` is the entry that played — 277 s of its 275 s — and the
	 * playlist row matched the session on title, channel *and* duration. Its
	 * watch page then names it "Avalanche (Official Video)", which is one token
	 * once the presentation core is taken, so the weak rank fired and the whole
	 * listen was refused. Every VEVO upload has this shape.
	 */
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
}
