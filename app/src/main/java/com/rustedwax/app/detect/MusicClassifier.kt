package com.rustedwax.app.detect

import com.rustedwax.hive.HiveScrobblePayload

object MusicClassifier {

	data class Result(val kind: String, val reason: String) {
		val isMusic: Boolean get() = kind == HiveScrobblePayload.KIND_SONG
	}

	private fun song(reason: String) = Result(HiveScrobblePayload.KIND_SONG, reason)
	private fun video(reason: String) = Result(HiveScrobblePayload.KIND_VIDEO, reason)

	/** A term kept next to its word-boundary regex, so reasons stay readable. */
	private class Term(val text: String, val regex: Regex) {
		fun matches(haystack: String) = regex.containsMatchIn(haystack)
	}

	/** YouTube's own category, when enrichment resolved one. Decisive. */
	private const val MUSIC_CATEGORY = "music"

	private val DECISIVE_NON_MUSIC_CATEGORIES = setOf(
		"film & animation", "gaming", "news & politics", "sports", "education",
		"howto & style", "science & technology", "autos & vehicles",
		"travel & events", "pets & animals", "nonprofits & activism",
	)

	/**
	 * Phrases in the *title* that mean this isn't music, whatever else it looks
	 * like. Deliberately conservative — each has to be something that
	 * essentially never appears in a music upload's title.
	 *
	 * Matched on word boundaries via [wordish], not as raw substrings, so
	 * "review" no longer fires on "preview" and "cover" no longer fires on
	 * "discover". The `cover …` / `… cover` entries are the fix for the
	 * magazine-cover false positive: a photo cover, a cover story and an album
	 * cover are decisively *not* music, and blocklist beats the positive rules.
	 */
	private val NON_MUSIC_TITLE = listOf(
		"podcast", "how to", "how-to",
		"walkthrough", "gameplay", "let's play", "lets play", "speedrun",
		"unboxing", "review", "vlog", "documentary",
		"explained", "q&a", "press conference", "full match",
		"teaser", "tier list", "first look", "hands on", "devlog",
		"sermon", "keynote", "webinar", "recipe", "asmr",
		"breaking news", "town hall", "testimony",

		"movie scene", "movie scenes", "top movie", "movie clip", "movie clips",
		"full movie", "short film", "showreel", "behind the scenes",
		// Reaction: the bare word is a real song ("Chain Reaction"), so require
		// the video-format phrasing instead.
		"reaction video", "reacts to",
		// Trailer: bare "trailer" blocks "Trailer Trash", "Trailer Park"; the
		// structural rule below handles the word in film context.
		"official trailer", "movie trailer", "game trailer",
		// Hearing: bare word blocks "Hearing Damage"; keep only the venues.
		"senate hearing", "court hearing", "congressional hearing",
		// Non-musical "cover" contexts.
		"cover photo", "cover story", "cover art", "album cover",
		"magazine cover", "book cover", "cover reveal", "cover letter",
		"undercover", "cover up", "cover-up",
	).map { Term(it, wordish(it)) }

	/**
	 * "Trailer" recognized structurally instead of by enumerated phrase —
	 * "Official *Final* Trailer" slipped past the exact phrases in the field
	 * sample. A film context word within two words before "trailer", or
	 * "Trailer 2" / "Trailer (2026)". Song titles that merely *contain* the
	 * word ("Trailer Trash (Official Video)") match neither shape.
	 */
	private val TRAILER_STRUCTURAL = Regex(
		"""\b(?:official|final|teasers?|movie|film|imax|4k)\s+(?:\w+\s+){0,2}trailers?\b""" +
			"""|\btrailers?\s*(?:#?\d|\(\d{4}\))""",
		RegexOption.IGNORE_CASE,
	)

	/**
	 * Music *news* headlines. "Marilyn Manson Releases Heavy New Single…" is a
	 * 47-second news clip that YouTube categorizes as **Music** — the uploader
	 * talks about music, so the category is honest and still wrong for us.
	 * Headline verbs next to release nouns essentially never occur in a song's
	 * own title, so this may safely outrank the category.
	 */
	private val NEWS_STRUCTURAL = Regex(
		"""\b(?:releases?|announces?|drops|unveils|teases|debuts|confirms)\b""" +
			"""[^.!?\n]{0,60}?\b(?:singles?|albums?|ep|tours?|records?|tracklist)\b""",
		RegexOption.IGNORE_CASE,
	)

	private val EPISODE_STRUCTURAL = Regex(
		"""\bs(?:eason)?\s*\d{1,2}\s*[,.\- ]*\s*ep(?:isode)?s?\.?\s*\d{1,3}\b""" +
			"""|\bs\d{1,2}\s?e\d{1,3}\b""",
		RegexOption.IGNORE_CASE,
	)

	private val EPISODE_NUMBER_X = Regex("""\b\d{1,2}x\d{1,3}\b""", RegexOption.IGNORE_CASE)

	/**
	 * `NxNN` shapes that aren't episodes.
	 *
	 * Video resolutions can't reach this rule — `1920x1080` and `640x480` have too
	 * many digits on the left for [EPISODE_NUMBER_X] to find a word boundary — but
	 * aspect ratios have exactly the same shape as a season and episode, so they
	 * are named.
	 */
	private val NOT_EPISODE_NUMBERS = setOf("16x9", "9x16", "4x3", "3x4", "21x9", "1x1")

	/**
	 * True when a `NxNN` in the title is episode numbering rather than a ratio.
	 *
	 * A false positive here costs one misfiled entry; a false negative writes a TV
	 * clip into the music index permanently. So where the shape is genuinely
	 * ambiguous — a title like `4x4` — this resolves toward `video`, matching the
	 * asymmetry the rest of this class is built on.
	 */
	private fun looksLikeEpisodeNumber(title: String): Boolean =
		EPISODE_NUMBER_X.findAll(title).any { it.value.lowercase() !in NOT_EPISODE_NUMBERS }

	/**
	 * Words that usually mean commentary but are also real song titles.
	 *
	 * These sit *below* auto-generated provenance, the Music category and
	 * MusicBrainz, so a verified recording passes while the video format they
	 * usually describe is still caught.
	 *
	 * The list grew in v0.7.0 on evidence from the scrobble.life maintainer's
	 * database: game soundtracks routinely ship tracks called **Tutorial**,
	 * **Tutorial (Puzzle)**, **Trailer 2** and **Trailer 3** (Calum Bowen's
	 * *Poinpy* and *Pikuniku*), and Future has a real recording titled **Fukk A
	 * Interview**. Every one of those was hitting the hard blocklist and being
	 * demoted to `video` even when it came from a Topic channel with a
	 * MusicBrainz match, because the blocklist outranked both. Game-OST
	 * vocabulary was a systematic blind spot.
	 *
	 * `official trailer` / `movie trailer` / `game trailer` stay in the hard
	 * list — those name a format outright and never title a recording.
	 */
	private val CONTEXTUAL_NON_MUSIC = listOf(
		"reaction", "tutorial", "interview", "episode", "highlights",
		"lecture", "debate",
	).map { Term(it, wordish(it)) }

	/**
	 * A title split into three or more segments reads as a clip caption, not
	 * as `Artist - Track`.
	 *
	 * From the field: `Blade II | Sewers of the Damned | ClipZone: Heroes &
	 * Villains` was scrobbled as a song because the first `|` made the weakest
	 * rule of all — "the title names an artist" — fire, yielding artist
	 * "Blade II". Two separators is a strong sign the title is describing a
	 * clip within a series.
	 */
	private val MULTI_SEGMENT =
		Regex("""\s[|｜–—-]\s[^|｜–—]*\s[|｜–—-]\s""")

	/** `Artist - Topic` is auto-generated by YouTube from a distributor feed. */
	private fun isTopicChannel(channel: String) = channel.endsWith("- topic")

	private val NON_MUSIC_CHANNEL = listOf(
		"news", "gaming", "podcast", "esports", "sports", "tutorials",
		"movie", "movies", "movieclips", "film", "films", "filmes", "cinema",
		"trailers", "pictures",
	).map { Term(it, wordish(it)) }

	/** Instruments/styles that qualify a "cover" or "playthrough" as musical. */
	private const val INSTRUMENTS =
		"""guitar|electric|acoustic|bass|drums?|piano|keyboard|synth|""" +
			"""vocal|vocals|metal|violin|cello|sax|saxophone|ukulele|orchestral|""" +
			"""8-?bit|lo-?fi|band|male|female|duet|choir|acapella|a cappella"""

	private val COVER_MUSIC = Regex(
		"""\b(?:$INSTRUMENTS)\s+covers?\b""" +
			"""|\bcovers?\s+(?:of|songs?|versions?|by)\b""" +
			"""|[(\[【][^)\]】]*\bcovers?\b[^)\]】]*[)\]】]""",
		RegexOption.IGNORE_CASE,
	)

	/**
	 * A "playthrough" that means music, never the bare word.
	 *
	 * A guitar or drum playthrough is music; a game playthrough is not, and
	 * `"Elden Ring Playthrough"` carries no blocklist word to catch it. So this
	 * requires the same instrument qualifier a cover does.
	 */
	private val PLAYTHROUGH_MUSIC = Regex(
		"""\b(?:$INSTRUMENTS)\s+play\s?through\b""" +
			"""|[(\[【][^)\]】]*\bplay\s?through\b[^)\]】]*[)\]】]""",
		RegexOption.IGNORE_CASE,
	)

	/** Any "playthrough" — musical or not. */
	private val PLAYTHROUGH_ANY = Regex("""\bplay\s?through\b""", RegexOption.IGNORE_CASE)

	/** Positive music evidence in the title, other than a cover or playthrough. */
	private val MUSIC_TITLE = listOf(
		"instrumental", "karaoke", "backing track",
		"lyrics", "lyric video", "official audio", "official video",
		"official music video", "live at", "live performance", "live session",
		"live in concert", "acoustic", "unplugged", "remix", "remaster",
		"remastered", "full album", "feat.", "ft.", "prod.", "concert",
		"mashup", "medley", "soundtrack", "theme song", "guitar solo",
	).map { Term(it, wordish(it)) }

	/** Channel suffixes that only artists and labels use. */
	private val MUSIC_CHANNEL_SUFFIXES = listOf(
		"vevo", "- topic", "records", "music", "recordings",
	)

	/**
	 * A term as a word-boundary regex. Interior spaces match any run of
	 * whitespace so "how to" also catches "how  to"; `.` (as in "feat.") is
	 * escaped. Boundaries are only required where the term ends in a word
	 * character, so "q&a" and "feat." still match.
	 */
	private fun wordish(term: String): Regex {
		// \Q…\E quotes the term literally; interior spaces become \s+ so
		// "how to" also matches "how  to".
		val body = "\\Q${term.lowercase()}\\E".replace(" ", "\\E\\s+\\Q")
		val lead = if (term.first().isLetterOrDigit()) "\\b" else ""
		val tail = if (term.last().isLetterOrDigit()) "\\b" else ""
		return Regex("$lead$body$tail", RegexOption.IGNORE_CASE)
	}

	/**
	 * Below this, weak evidence isn't enough. Almost no real song is under 90
	 * seconds, but a huge share of shorts and clips are — and their titles are
	 * full of dashes and pipes that fool the artist heuristic.
	 */
	private const val SHORT_FORM_MS = 90L * 1000

	/**
	 * Above these, weak evidence isn't enough either — the same bar short-form is
	 * held to, at the other end.
	 *
	 * Both thresholds are the desktop extension's (`MEDIUM_FORM_THRESHOLD_SEC`
	 * and `LONG_FORM_THRESHOLD_SEC`), adopted rather than guessed. 8 minutes
	 * catches talk-show and late-night clips whose titles parse as `Artist -
	 * Track`; 15 minutes catches podcasts, streams and vlogs.
	 *
	 * Genuine music above these lengths — a DJ set, a full album upload, an
	 * extended remix — essentially always carries a real signal: a Music
	 * category, `full album` / `live at` / `mix` vocabulary, an artist channel,
	 * or now the YouTube Music catalogue. All of those are checked before this.
	 */
	private const val MEDIUM_FORM_MS = 8L * 60 * 1000
	private const val LONG_FORM_MS = 15L * 60 * 1000

	private val SHORTS_TAG = Regex("""#shorts?\b""", RegexOption.IGNORE_CASE)

	fun classify(
		rawTitle: String,
		channel: String?,
		durationMs: Long?,
		siteSaysMusic: Boolean,
		enrichedCategory: String? = null,
		isShort: Boolean = false,
		musicbrainzMatch: Boolean = false,
		autoGenerated: Boolean = false,
		recognisedByYouTubeMusic: Boolean = false,
		nativeYouTubeMusic: Boolean = false,
		youtubeMusicPodcastEpisode: Boolean = false,
		nativeGenre: String? = null,
	): Result {
		val title = rawTitle.lowercase()
		val ch = channel?.trim()?.lowercase().orEmpty()
		val category = enrichedCategory?.trim()?.lowercase()?.ifEmpty { null }
		val nativeGenreKey = nativeGenre?.trim()?.lowercase().orEmpty()

		// A package is strong context, not permission to erase a literal
		// structured disposition published for this item.
		if (nativeYouTubeMusic && youtubeMusicPodcastEpisode) {
			return video("YouTube Music identifies a podcast episode")
		}
		if (nativeYouTubeMusic && NATIVE_HARD_NON_MUSIC_GENRE.containsMatchIn(nativeGenreKey)) {
			return video("native MediaMetadata genre is explicitly non-music: $nativeGenre")
		}

		// 0. Auto-generated provenance — trusted above every title heuristic.
		//
		//    The blocklist's unstated premise is that a human wrote a
		//    descriptive title ("Guitar Tutorial for Beginners"). A Topic
		//    channel breaks that premise: YouTube generates it from a
		//    distributor's feed, so the title *is* catalogue metadata — a track
		//    name. Running human-title heuristics over distributor metadata is
		//    a category error, and it was demoting real soundtrack tracks
		//    called "Tutorial" and "Trailer 2" to `video`.
		//
		//    This does not reopen the case that put format evidence above the
		//    *category* in v0.5.1: there an uploader had chosen "Music" for a
		//    tutorial and a news bulletin. The distinction is who set the
		//    metadata — a human can be wrong or chase reach; an auto-generated
		//    feed cannot.
		if (siteSaysMusic) return song("music.youtube.com")
		if (autoGenerated && !nativeYouTubeMusic) {
			return song("auto-generated Art Track (distributor feed)")
		}
		if (isTopicChannel(ch) && !nativeYouTubeMusic) {
			return song("YouTube Topic channel (distributor feed)")
		}

		if (TitleParser.isHashtagOnly(rawTitle)) {
			return video("title is only hashtags — names no track")
		}

		NON_MUSIC_TITLE.firstOrNull { it.matches(title) }?.let {
			return video("title has non-music \"${it.text}\"")
		}
		if (NEWS_STRUCTURAL.containsMatchIn(title)) {
			return video("title reads as a music-news headline")
		}
		if (EPISODE_STRUCTURAL.containsMatchIn(title) || looksLikeEpisodeNumber(title)) {
			return video("title reads as a TV episode")
		}
		if (nativeYouTubeMusic && NATIVE_NUMBERED_EPISODE.containsMatchIn(title)) {
			return video("native title explicitly identifies a numbered episode")
		}
		// A "playthrough" with no instrument is a game playthrough. The
		// instrument-qualified case is let through to become a song below;
		// unlike a cover, a bare playthrough defaults to gaming, not music.
		if (PLAYTHROUGH_ANY.containsMatchIn(title) && !PLAYTHROUGH_MUSIC.containsMatchIn(title)) {
			return video("game playthrough (no instrument)")
		}

		// Native YouTube Music is strong music context, deliberately below all
		// explicit podcast/episode/tutorial/news/trailer/game-format evidence.
		if (nativeYouTubeMusic) return song("native YouTube Music package")

		if (recognisedByYouTubeMusic) return song("YouTube Music catalogue")

		// 2b. MusicBrainz is hard recording provenance. It remains below the
		//     established tutorial/news/episode format rules, but above the new
		//     narrative movie/edit marker and the uploader's bare category.
		if (musicbrainzMatch) return song("MusicBrainz confirms artist and recording")

		// 2c. Generic channel-name vocabulary is useful negative context, but it
		//     is not stronger than id-bound catalogue or recording provenance.
		//     `Flow La Movie` is a real music owner; the word "movie" must not
		//     demote its YouTube Music OMV. Without hard provenance the existing
		//     channel guard still beats a bare uploader category.
		NON_MUSIC_CHANNEL.firstOrNull { it.matches(ch) }?.let {
			return video("channel has non-music \"${it.text}\"")
		}

		// 2d. A paired, explicit movie/edit hashtag structure is strong narrative
		//     format evidence. A bare word "movie" is intentionally insufficient.
		//     Distributor, Topic, Art Track, YouTube Music and confirmed-recording
		//     provenance above still win; this is content kind, never ad evidence.
		if (STRONG_NARRATIVE_MOVIE_EDIT.containsMatchIn(title)) {
			return video("title explicitly marks a narrative movie edit")
		}

		// 3. YouTube says Music, and nothing above disagreed.
		if (category == MUSIC_CATEGORY) return song("YouTube category: Music")

		// 4. Commentary words that are also song titles — below provenance,
		//    the Music category and MusicBrainz on purpose, so real recordings
		//    ("Chain Reaction", a soundtrack's "Tutorial") are rescued by the
		//    layers above rather than sacrificed to the word.
		CONTEXTUAL_NON_MUSIC.firstOrNull { it.matches(title) }?.let {
			return video("title has \"${it.text}\" and no stronger music evidence")
		}
		if (TRAILER_STRUCTURAL.containsMatchIn(title)) {
			return video("title reads as a film trailer and nothing stronger says music")
		}

		// 5. Cover and playthrough vocabulary. Deliberately *below* the
		//    contextual words rather than beside MusicBrainz: these are still
		//    words in a human-written title, so "Bass Cover Tutorial" is a
		//    tutorial about a cover, not a cover. Kept above the category so an
		//    instrument cover filed under Education is still that song played.
		if (COVER_MUSIC.containsMatchIn(title)) return song("title names a cover")
		if (PLAYTHROUGH_MUSIC.containsMatchIn(title)) return song("title names a playthrough")

		// 6. The uploader's own non-music categorization.
		if (category in DECISIVE_NON_MUSIC_CATEGORIES) {
			return video("YouTube category: $enrichedCategory")
		}

		// 6. Music vocabulary and artist-owned channels.
		MUSIC_CHANNEL_SUFFIXES.firstOrNull { ch.endsWith(it) }?.let {
			return song("channel ends with \"$it\"")
		}
		MUSIC_TITLE.firstOrNull { it.matches(title) }?.let {
			return song("title has music \"${it.text}\"")
		}

		// 7. From here down only weak evidence remains — and two situations
		//    where weak evidence is not accepted.
		//
		//    A known non-music category: the uploader had "Music" available and
		//    chose something else; a `|`-separated title doesn't outrank them.
		if (category != null) {
			return video("category \"$enrichedCategory\" and no explicit music signal")
		}
		//    Short-form: shorts are browsed by the dozen, titles are dash-heavy
		//    clip captions, and a real sub-90-second song is rare.
		if (isShort || SHORTS_TAG.containsMatchIn(title) ||
			(durationMs != null && durationMs < SHORT_FORM_MS)
		) {
			return video("short-form with no explicit music signal")
		}
		//    Long-form, the mirror of the rule above and adapted from the desktop
		//    extension, which gates at the same two lengths. This was a real gap:
		//    RustedWax had a *lower* bound and no upper one, so a 45-minute
		//    podcast titled "Host - Guest Name" with no category reached the weak
		//    rule below and became a `song`. A dash in the title is not evidence
		//    that three quarters of an hour is a recording.
		if (durationMs != null && durationMs >= MEDIUM_FORM_MS) {
			val howLong = if (durationMs >= LONG_FORM_MS) "long-form" else "medium-form"
			return video("$howLong (${durationMs / 60_000} min) with no explicit music signal")
		}

		// 8. Weak evidence, acceptable only for ordinary watch-page videos of
		//    unknown category — and not for clip captions, which a three-part
		//    title almost always is.
		if (TitleParser.looksLikeMusic(rawTitle, channel) &&
			!MULTI_SEGMENT.containsMatchIn(rawTitle)
		) {
			return song("title names an artist")
		}

		// 9. Nothing said music at all. D4 originally defaulted this to song;
		//    regression evidence showed the default-song false positives were news
		//    clip, a movie scene or a vlog, while real music essentially always
		//    carries at least one signal above — a category, a MusicBrainz
		//    match, cover/lyrics vocabulary, an artist channel, or an
		//    `Artist - Track` title. Default is video since v0.5.1;
		//    MusicBrainz is the safety net for untagged uploads of real songs.
		return video("no music evidence")
	}

	private val STRONG_NARRATIVE_MOVIE_EDIT = Regex(
		"""(?=.*(?:^|\s)#movie\b)(?=.*(?:^|\s)#edit\b)""",
		RegexOption.IGNORE_CASE,
	)

	private val NATIVE_HARD_NON_MUSIC_GENRE = Regex(
		"""\b(?:podcasts?|episodes?|audiobooks?|spoken\s+word)\b""",
		RegexOption.IGNORE_CASE,
	)

	private val NATIVE_NUMBERED_EPISODE = Regex(
		"""\bepisodes?\s*(?:[:#-]\s*)?\d{1,4}\b""",
		RegexOption.IGNORE_CASE,
	)
}
