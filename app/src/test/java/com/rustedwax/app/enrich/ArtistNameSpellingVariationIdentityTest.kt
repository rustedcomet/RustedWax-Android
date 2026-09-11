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
 * One artist's name spelled two ways.
 *
	 * Regression: source metadata can spell an artist one character differently
	 * from an otherwise matching managed upload:
 *
 * | field | session | `ztyOtM7sero` |
 * |---|---|---|
 * | title | `Bad Man Nuh Flee` | `Bad Man Nuh Flee` — exact |
 * | length | 195 s | 195 s — exact |
 * | owner | `Bennie Man & Mr Vegas` | `Beenie Man - Topic` |
 *
 * `bennieman` against `beenieman`: one substitution. The artist is Beenie Man;
 * YouTube Music's own metadata spells it Bennie Man. The track played whole and
 * wrote nothing.
 *
 * The licence added for it is deliberately not a fuzzy matcher. It needs, all at
 * once: the title exact after existing normalization, the length inside the
 * existing tolerance, an owner YouTube itself binds to the artist (`- Topic` or
 * `VEVO`), exactly one changed character over names long enough that one
 * character cannot reach a different act, and — through the caller's own rule —
 * exactly one surviving candidate.
 *
 * The other half of the reported variation needs no licence at all:
 * [SearchResultsParser.channelKey] already erases punctuation, so `"Mr Vegas"`
 * and `"Mr. Vegas"` are the same key and agree exactly. That is pinned below so
 * it cannot silently stop being true.
 */
class ArtistNameSpellingVariationIdentityTest {

	private val correctId = "ztyOtM7sero"
	private val title = "Bad Man Nuh Flee"
	private val sessionArtist = "Bennie Man & Mr Vegas"
	private val sessionDurationSec = 195L

	private fun card(
		videoId: String,
		title: String,
		channel: String?,
		lengthSeconds: Long?,
	) = SearchResultsParser.Candidate(videoId, title, channel, lengthSeconds)

	/** The real listing, with the owners the watch pages report. */
	private val completedPages = listOf(
		card(correctId, title, "Beenie Man - Topic", 195),
		card("NX6bLAyGros", "Beenie Man Ft Mr Vegas - Bad Man Nuh Flee", "Tee Rombley", 197),
		card("71mM4Z5jW9I", "Beenie Man & Mr. Vegas - Bad Man Nuh Flee (Video con letra oficial)", "Jet Star Music", 195),
		card("OdS9X5-quDk", "Beenie Man Ft. Mr. Vegas - Bad Man Nuh Flee", "DJ KELL’S", 198),
		card("6Fkk5n2LjEA", "Beenie Man Ft Mr Vegas - Bad Man Nuh Flee", "brian boss", 200),
	)

	// ── A. the confirmed case ────────────────────────────────────────────────

	@Test
	fun `the Topic upload resolves despite the one-character artist spelling`() {
		assertEquals(
			"exactly one upload survives title, length, ownership and spelling together",
			listOf(correctId),
			SearchResultsParser.identityMatches(
				completedPages, title, sessionArtist, sessionDurationSec,
			).map { it.videoId },
		)
	}

	@Test
	fun `the listing card is sent to its watch page rather than accepted or dropped`() {
		// The card byline reads `Beenie Man`; only the page reads `Beenie Man -
		// Topic`. Without the fetch the licence could never see the marker it
		// requires, and the card would have been filtered out before anything
		// looked at it.
		val listingCard = card(correctId, title, "Beenie Man", 196)

		assertTrue(
			"it survives the prefilter",
			SearchResultsParser.hasNoIdentityContradiction(
				listingCard, title, sessionArtist, sessionDurationSec,
			),
		)
		assertTrue(
			"and is marked as needing its page",
			SearchResultsParser.requiresWatchPageForArtistSpelling(
				listingCard, title, sessionArtist,
			),
		)
		assertEquals(
			"but the listing alone never resolves it — the owner is unproven there",
			emptyList<String>(),
			SearchResultsParser.identityMatches(
				listOf(listingCard), title, sessionArtist, sessionDurationSec,
			).map { it.videoId },
		)
	}

	@Test
	fun `the corroborator accepts the upload the matcher named`() {
		assertNull(
			VideoIdentityCorroborator.contradiction(
				endedListen(),
				resolution(),
				facts(),
			),
		)
	}

	// ── B. a wrong title refuses ─────────────────────────────────────────────

	@Test
	fun `a near artist spelling over a different title is refused`() {
		assertEquals(
			emptyList<String>(),
			SearchResultsParser.identityMatches(
				listOf(card(correctId, "Bad Man Nuh Flee (Live)", "Beenie Man - Topic", 195)),
				title,
				sessionArtist,
				sessionDurationSec,
			).map { it.videoId },
		)
	}

	@Test
	fun `the spelling licence never leans on the artist-prefix title shape`() {
		// "Beenie Man - Bad Man Nuh Flee" would satisfy the *prefix* title rule for
		// a session artist spelled that way. It must not also buy the spelling
		// licence: two licences may not corroborate each other.
		assertEquals(
			emptyList<String>(),
			SearchResultsParser.identityMatches(
				listOf(card(correctId, "Bennie Man - Bad Man Nuh Flee", "Beenie Man - Topic", 195)),
				title,
				sessionArtist,
				sessionDurationSec,
			).map { it.videoId },
		)
	}

	// ── C. a wrong duration refuses ──────────────────────────────────────────

	@Test
	fun `a near artist spelling at a different length is refused`() {
		assertEquals(
			emptyList<String>(),
			SearchResultsParser.identityMatches(
				listOf(card(correctId, title, "Beenie Man - Topic", 240)),
				title,
				sessionArtist,
				sessionDurationSec,
			).map { it.videoId },
		)
	}

	@Test
	fun `the corroborator still refuses a near spelling at a different length`() {
		assertNotNull(
			VideoIdentityCorroborator.contradiction(
				endedListen(),
				resolution(lengthSeconds = 240),
				facts(lengthSeconds = 240),
			),
		)
	}

	// ── D. two survivors are still ambiguous ─────────────────────────────────

	@Test
	fun `two Topic uploads one spelling away leave an ambiguity, not a guess`() {
		val two = listOf(
			card(correctId, title, "Beenie Man - Topic", 195),
			card("QQQQQQQQQQQ", title, "Bennie Mann - Topic", 195),
		)
		assertEquals(
			2,
			SearchResultsParser.identityMatches(two, title, sessionArtist, sessionDurationSec).size,
		)
		assertNull(
			"nothing is selected when two survive",
			SearchResultsParser.bestMatch(two, title, sessionArtist, sessionDurationSec),
		)
	}

	// ── E. a different artist refuses, however it is written ─────────────────

	@Test
	fun `a different act on a Topic channel is refused`() {
		assertEquals(
			emptyList<String>(),
			SearchResultsParser.identityMatches(
				listOf(card("RRRRRRRRRRR", title, "Bounty Killer - Topic", 195)),
				title,
				sessionArtist,
				sessionDurationSec,
			).map { it.videoId },
		)
	}

	@Test
	fun `more than one changed character is not a spelling of the same name`() {
		assertFalse(
			SearchResultsParser.artistNameIsOneSpellingApart("Bennie Man", "Beenie Man Jr"),
		)
		assertFalse(
			SearchResultsParser.artistNameIsOneSpellingApart("Bennie Man", "Bonnie Mon"),
		)
		assertTrue(
			"one substitution is the whole of what it allows",
			SearchResultsParser.artistNameIsOneSpellingApart("Bennie Man", "Beenie Man"),
		)
		assertTrue(
			"and one inserted or deleted character",
			SearchResultsParser.artistNameIsOneSpellingApart("Beenie Man", "Beeni Man"),
		)
	}

	@Test
	fun `a short name is never allowed to change a character`() {
		assertFalse(
			"`sean` to `sian` is a different act, not a spelling",
			SearchResultsParser.artistNameIsOneSpellingApart("Sean", "Sian - Topic"),
		)
	}

	@Test
	fun `an ordinary channel is never authoritative enough to spell a name loosely`() {
		assertFalse(
			SearchResultsParser.ownerIsAuthoritativeArtistChannel("Tee Rombley"),
		)
		assertFalse(
			"a label's own channel is not the artist's",
			SearchResultsParser.ownerIsAuthoritativeArtistChannel("Jet Star Music"),
		)
		assertTrue(SearchResultsParser.ownerIsAuthoritativeArtistChannel("Beenie Man - Topic"))
		assertTrue(SearchResultsParser.ownerIsAuthoritativeArtistChannel("capletonVEVO"))

		assertEquals(
			"so a reposter one spelling away resolves nothing",
			emptyList<String>(),
			SearchResultsParser.identityMatches(
				listOf(card("SSSSSSSSSSS", title, "Beenie Man", 195)),
				title,
				sessionArtist,
				sessionDurationSec,
			).map { it.videoId },
		)
	}

	// ── F. the punctuation half needs no licence ─────────────────────────────

	@Test
	fun `Mr Vegas and Mr Vegas with a full stop are already the same owner`() {
		assertEquals(
			"existing normalization erases punctuation, so these agree exactly",
			SearchResultsParser.channelKey("Mr Vegas"),
			SearchResultsParser.channelKey("Mr. Vegas"),
		)
		assertFalse(
			"and never reach the spelling licence at all",
			SearchResultsParser.artistNameIsOneSpellingApart("Mr Vegas", "Mr. Vegas"),
		)
	}

	@Test
	fun `a punctuation-only owner difference resolves uniquely on the exact path`() {
		assertEquals(
			listOf("PPPPPPPPPPP"),
			SearchResultsParser.identityMatches(
				listOf(card("PPPPPPPPPPP", "Hot Wuk", "Mr. Vegas", 195)),
				"Hot Wuk",
				"Mr Vegas",
				sessionDurationSec,
			).map { it.videoId },
		)
	}

	// ── fixtures ─────────────────────────────────────────────────────────────

	private fun endedListen() = SessionSnapshot(
		packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
		appLabel = "YouTube Music",
		isTarget = true,
		title = title,
		artist = sessionArtist,
		album = null,
		durationMs = 195_047,
		positionMs = 195_047,
		playedMs = 195_047,
		loopDetected = false,
		playbackState = "STOPPED",
		isPlaying = false,
		percentPlayed = 1.0,
		identity = YouTubeProbe.Identity.SiteOnly(
			host = "music.youtube.com",
			isMusic = true,
			source = "native package origin",
		),
		resolverContext = ResolverContext(presentationDurationMs = 195_047),
		notificationHint = null,
		metadataLines = emptyList(),
		trackStartedAtEpochSec = 1_785_000_000,
		sourceProof = SourceProof.MEDIA_SESSION,
	)

	private fun resolution(lengthSeconds: Long = 195) = VideoResolution(
		videoId = correctId,
		source = "Shorts watch-page completion",
		title = title,
		channel = "Beenie Man - Topic",
		lengthSeconds = lengthSeconds,
		uniquelyResolved = true,
		presentationDurationCorroborated = true,
	)

	private fun facts(lengthSeconds: Long = 195) = VideoFacts(
		videoId = correctId,
		title = title,
		author = "Beenie Man - Topic",
		lengthSeconds = lengthSeconds,
		category = "Music",
		watchPageResolved = true,
	)
}
