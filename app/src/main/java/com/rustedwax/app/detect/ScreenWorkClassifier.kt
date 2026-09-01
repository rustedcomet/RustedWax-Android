package com.rustedwax.app.detect

import com.rustedwax.hive.HiveScrobblePayload

/**
 * Films and episodes, told apart from everything else — §6.2.
 *
 * ## What this is for, and what it deliberately is not
 *
 * The defect is films filed as songs. A trailer titled
 * `Fall 2: Deadpoint (2026) Official Trailer 2 - Harriet Slater, Arsema Thomas`
 * has a dash in it, a year, and a cast list, and it went on chain split into an
 * "artist" and a "track". `kind` is what fixes that, so `kind` is all this
 * produces.
 *
 * `imdb_id`, `wikipedia_url`, `series_*` and `poster_url` exist in the upstream
 * payload type and stay absent. The extension fills them from a page DOM and a
 * Wikidata lookup; this app has neither, and inventing them from a title would
 * put guesses on an unerasable ledger to make a record look tidier. The payload
 * already omits absent fields, so absent is a clean answer.
 *
 * ## Conservative on purpose
 *
 * Every rule here demands a *structural* signal — an explicit season/episode
 * marker, or a release-year parenthesis next to an explicit film word. Titles
 * merely *about* films stay ordinary videos. The cost of over-claiming is an
 * entry asserting someone watched a film they only watched a review of.
 */
object ScreenWorkClassifier {

	data class Result(
		val kind: String,
		val reason: String,
		/** The work's own name, once the marker that identified it is removed. */
		val title: String?,
		val year: Int?,
		val season: Int?,
		val episode: Int?,
	)

	/**
	 * `S02E05`, `Season 2 Episode 5`, `2x05` — the shapes that only ever mean an
	 * episode of something. `2x05` requires the season to be at most two digits
	 * so a resolution (`1920x1080`) cannot match.
	 */
	private val SEASON_EPISODE = listOf(
		Regex("""\bs(\d{1,2})\s*[.\-_ ]?\s*e(\d{1,3})\b""", RegexOption.IGNORE_CASE),
		Regex(
			"""\bseason\s*(\d{1,2})\b[^\d]{0,12}\bepisode\s*(\d{1,3})\b""",
			RegexOption.IGNORE_CASE,
		),
		Regex(
			"""\btemporada\s*(\d{1,2})\b[^\d]{0,12}\bcap[ií]tulo\s*(\d{1,3})\b""",
			RegexOption.IGNORE_CASE,
		),
		Regex("""(?<![\d×x])(\d{1,2})x(\d{2,3})(?![\dx×])""", RegexOption.IGNORE_CASE),
	)

	/** A four-digit release year in brackets, which is how films are titled. */
	private val BRACKETED_YEAR = Regex("""[(\[](19|20)(\d{2})[)\]]""")

	/**
	 * Words that name a *complete* screen work.
	 *
	 * `trailer`, `teaser` and `clip` are deliberately absent: a trailer is a
	 * promo for a film, not the film, and crediting someone with having watched
	 * `Dune` because they watched its trailer is exactly the kind of false claim
	 * this project refuses elsewhere. Those stay `video`.
	 */
	private val FILM_WORD = Regex(
		"""\b(full\s+movie|pel[ií]cula\s+completa|full\s+film|movie\s+in\s+full)\b""",
		RegexOption.IGNORE_CASE,
	)

	/** Categories YouTube itself assigns to screen works. */
	private const val CATEGORY_FILM = "film & animation"
	private const val CATEGORY_SHOWS = "shows"
	private const val CATEGORY_MOVIES = "movies"

	/**
	 * @param enrichedCategory YouTube's own category for the video, when known.
	 * @return null when this is not recognisably a film or an episode, which is
	 * the common case and not a failure.
	 */
	fun classify(
		rawTitle: String,
		durationMs: Long?,
		enrichedCategory: String? = null,
	): Result? {
		val title = rawTitle.trim()
		if (title.isEmpty()) return null
		val category = enrichedCategory?.trim()?.lowercase().orEmpty()
		val durationMinutes = durationMs?.let { it / 60_000 } ?: 0

		SEASON_EPISODE.firstNotNullOfOrNull { pattern ->
			pattern.find(title)?.let { match ->
				val season = match.groupValues[1].toIntOrNull()
				val episode = match.groupValues[2].toIntOrNull()
				if (season == null || episode == null) return@let null
				Result(
					kind = HiveScrobblePayload.KIND_EPISODE,
					reason = "title carries an explicit season/episode marker " +
						"(\"${match.value.trim()}\")",
					title = stripMarker(title, match.range),
					year = bracketedYear(title),
					season = season,
					episode = episode,
				)
			}
		}?.let { return it }

		// A film needs a structural claim *and* a plausible length. A
		// feature-length runtime alone is a lecture or a stream just as often, and
		// the phrase alone is how a two-minute "full movie in 2 minutes" recap
		// would slip through.
		val year = bracketedYear(title)
		val looksFeatureLength = durationMinutes >= FEATURE_MINUTES
		val saysFullFilm = FILM_WORD.containsMatchIn(title)
		val filmCategory = category == CATEGORY_FILM ||
			category == CATEGORY_MOVIES ||
			category == CATEGORY_SHOWS
		if (looksFeatureLength && (saysFullFilm || (filmCategory && year != null))) {
			return Result(
				kind = HiveScrobblePayload.KIND_MOVIE,
				reason = when {
					saysFullFilm -> "title says it is a complete film and it runs " +
						"${durationMinutes}m"
					else -> "YouTube category \"$enrichedCategory\", a release year, " +
						"and a ${durationMinutes}m runtime"
				},
				title = TitleParser.clean(BRACKETED_YEAR.replace(title, "")).ifEmpty { title },
				year = year,
				season = null,
				episode = null,
			)
		}
		return null
	}

	private fun bracketedYear(title: String): Int? =
		BRACKETED_YEAR.find(title)?.let { "${it.groupValues[1]}${it.groupValues[2]}".toIntOrNull() }

	private fun stripMarker(title: String, range: IntRange): String {
		val without = title.removeRange(range)
		return TitleParser.clean(BRACKETED_YEAR.replace(without, "")).ifEmpty { title }
	}

	/**
	 * Shorter than any feature, longer than the long tail of ordinary uploads.
	 * Set low enough to admit a short film, since the alternative is filing one
	 * as a music video.
	 */
	private const val FEATURE_MINUTES = 45
}
