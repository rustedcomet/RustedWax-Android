package com.rustedwax.app.enrich

import org.json.JSONArray
import org.json.JSONObject

object YouTubeMusicCatalogSearchParser {

	/**
	 * YouTube Music's "Songs" search filter.
	 *
	 * Opaque protobuf, sent verbatim the way the web client sends it. It is what
	 * switches the byline from `Song • Artist` to `Artist • Album • Duration`;
	 * without it neither [Candidate.album] nor [Candidate.durationSeconds] is
	 * present in the response at all.
	 */
	const val SONGS_FILTER_PARAMS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"

	/**
	 * YouTube Music's "Videos" search filter, the sibling of [SONGS_FILTER_PARAMS].
	 *
	 * The songs filter is what makes a row name its album and its running time, and
	 * it is also why a Video-mode presentation can never be in that response: the
	 * shelf it filters to holds art tracks, and the music video the player is
	 * actually rendering is a different row entirely. This filter asks for that
	 * shelf instead. Its rows carry no album — a music video belongs to no release
	 * — but they do carry the same hyperlinked artist byline and the same trailing
	 * `m:ss`, which is what the strict credit and length tests need.
	 */
	const val VIDEOS_FILTER_PARAMS = "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"

	data class Config(
		val apiKey: String,
		val clientVersion: String,
		val clientNameHeader: String,
	)

	data class Candidate(
		val videoId: String,
		val title: String,
		val artists: List<String>,
		val musicVideoType: String,
		/** The release this row belongs to; null on an unfiltered response. */
		val album: String? = null,
		/** Exact Music browse endpoint for [album], used only for bounded album recovery. */
		val albumBrowseId: String? = null,
		/** Catalog running time; null on an unfiltered response. */
		val durationSeconds: Long? = null,
	)

	private val VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")
	private val DURATION = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{2})$""")

	/** YouTube's byline field separator, as it renders it. */
	private val BYLINE_SEPARATOR = Regex("""\s*•\s*""")

	/** Read current public client coordinates from the Music shell. */
	fun config(shell: String): Config? {
		fun quoted(name: String): String? = Regex(
			""""${Regex.escape(name)}"\s*:\s*"([^"]+)"""",
		).find(shell)?.groupValues?.get(1)?.takeIf(String::isNotBlank)
		fun number(name: String): String? = Regex(
			""""${Regex.escape(name)}"\s*:\s*([0-9]+)""",
		).find(shell)?.groupValues?.get(1)?.takeIf(String::isNotBlank)

		return Config(
			apiKey = quoted("INNERTUBE_API_KEY") ?: return null,
			clientVersion = quoted("INNERTUBE_CLIENT_VERSION") ?: return null,
			clientNameHeader = number("INNERTUBE_CONTEXT_CLIENT_NAME") ?: return null,
		)
	}

	fun candidates(json: String): List<Candidate> {
		val out = LinkedHashMap<String, Candidate>()
		walk(JSONObject(json)) { node ->
			val renderer = node.optJSONObject("musicResponsiveListItemRenderer") ?: return@walk
			val videoId = renderer.optJSONObject("playlistItemData")
				?.optString("videoId")
				?.takeIf(VIDEO_ID::matches)
				?: return@walk
			val columns = renderer.optJSONArray("flexColumns") ?: return@walk
			val title = columnRuns(columns, 0)
				.firstOrNull()?.optString("text")?.takeIf(String::isNotBlank)
				?: return@walk
			val byline = columnRuns(columns, 1)
			val artists = bylineArtists(byline)
			if (artists.isEmpty()) return@walk
			// The album link, when the songs filter put one in the byline. Taken
			// by its own page type rather than by position: a row may carry two,
			// one or no separator runs before it depending on the artist count.
			val album = byline.firstOrNull { pageType(it) == "MUSIC_PAGE_TYPE_ALBUM" }
				?.optString("text")?.takeIf(String::isNotBlank)
			val albumBrowseId = byline.firstOrNull {
				pageType(it) == "MUSIC_PAGE_TYPE_ALBUM"
			}?.let(::browseId)
			// The trailing `m:ss` run. Recognised by shape, because it is the one
			// byline field YouTube renders as plain text with no endpoint on it.
			val durationSeconds = byline.asReversed().firstNotNullOfOrNull { run ->
				run.optString("text")?.let(::durationSeconds)
			}
			val musicVideoType = firstObject(renderer, "watchEndpointMusicConfig")
				?.optString("musicVideoType")?.takeIf(String::isNotBlank)
				?: return@walk
			out.putIfAbsent(
				videoId,
				Candidate(
					videoId, title, artists, musicVideoType, album, albumBrowseId, durationSeconds,
				),
			)
		}
		return out.values.toList()
	}

	/**
	 * Exact track rows from one already-selected album page.
	 *
	 * The broad Songs search can index a single release instead of the album
	 * release the player named. The album page is a closed catalog set and carries
	 * the missing id, title, structured artist credit and fixed-column duration.
	 * [album] comes from the exact browse endpoint selected by the caller; it is
	 * not guessed from row position.
	 */
	fun albumCandidates(json: String, album: String): List<Candidate> {
		if (album.isBlank()) return emptyList()
		val out = LinkedHashMap<String, Candidate>()
		walk(JSONObject(json)) { node ->
			val renderer = node.optJSONObject("musicResponsiveListItemRenderer") ?: return@walk
			val videoId = renderer.optJSONObject("playlistItemData")
				?.optString("videoId")?.takeIf(VIDEO_ID::matches) ?: return@walk
			val columns = renderer.optJSONArray("flexColumns") ?: return@walk
			val title = columnRuns(columns, 0)
				.firstOrNull()?.optString("text")?.takeIf(String::isNotBlank) ?: return@walk
			val artists = columnRuns(columns, 1)
				.filter { pageType(it) == "MUSIC_PAGE_TYPE_ARTIST" }
				.mapNotNull { it.optString("text").takeIf(String::isNotBlank) }
				.distinct()
			if (artists.isEmpty()) return@walk
			val duration = fixedColumnRuns(renderer.optJSONArray("fixedColumns"), 0)
				.asReversed().firstNotNullOfOrNull { durationSeconds(it.optString("text")) }
				?: return@walk
			val musicVideoType = firstObject(renderer, "watchEndpointMusicConfig")
				?.optString("musicVideoType")?.takeIf(String::isNotBlank) ?: return@walk
			out.putIfAbsent(
				videoId,
				Candidate(
					videoId = videoId,
					title = title,
					artists = artists,
					musicVideoType = musicVideoType,
					album = album,
					durationSeconds = duration,
				),
			)
		}
		return out.values.toList()
	}

	/** `3:37` / `1:02:11` → seconds. Null for anything that is not a running time. */
	fun durationSeconds(text: String?): Long? {
		val match = DURATION.matchEntire(text?.trim().orEmpty()) ?: return null
		val hours = match.groupValues[1].toLongOrNull() ?: 0L
		val minutes = match.groupValues[2].toLongOrNull() ?: return null
		val seconds = match.groupValues[3].toLongOrNull() ?: return null
		if (seconds >= 60 || (match.groupValues[1].isNotEmpty() && minutes >= 60)) return null
		return hours * 3600 + minutes * 60 + seconds
	}

	/**
	 * Exact catalog work and complete artist credit; never rank or substring.
	 *
	 * The artist test is a **set** comparison, and that is the correction rather
	 * than a loosening. It used to hand [NativeStructuredMusicMatcher] one catalog
	 * artist at a time against the player's whole credit string, so a
	 * collaboration could not match at any candidate: YouTube Music publishes
	 * `ARTIST = "Walshy Fire, Lizi & Mr. Vegas"` while the catalog row carries
	 * `["Mr. Vegas", "Lizi", "Walshy Fire"]`, and `"mrvegas"` is not
	 * `"walshyfirelizimrvegas"`. Comparing the complete credit on both sides is
	 * strictly *more* selective than the single-name test it replaces — a
	 * different collaboration on the same work still fails.
	 */
	fun matches(candidate: Candidate, nativeTitle: String, nativeArtist: String): Boolean =
		YouTubeMusicParser.isRecognisedMusicType(candidate.musicVideoType) &&
			NativeStructuredMusicMatcher.completeCreditsAgree(candidate.artists, nativeArtist) &&
			NativeStructuredMusicMatcher.worksAgree(candidate.title, nativeTitle)

	private fun bylineArtists(byline: List<JSONObject>): List<String> {
		val linked = byline.filter { pageType(it) == "MUSIC_PAGE_TYPE_ARTIST" }
			.mapNotNull { it.optString("text").takeIf(String::isNotBlank) }
			.distinct()
		if (linked.isNotEmpty()) return linked
		val segments = byline.joinToString("") { it.optString("text").orEmpty() }
			.split(BYLINE_SEPARATOR)
			.map(String::trim)
			.filter(String::isNotEmpty)
		if (segments.size < 2) return emptyList()
		val index = if (durationSeconds(segments.last()) != null) 0 else 1
		return listOfNotNull(segments.getOrNull(index)?.takeIf(String::isNotBlank))
	}

	private fun pageType(run: JSONObject): String? = run
		.optJSONObject("navigationEndpoint")
		?.optJSONObject("browseEndpoint")
		?.optJSONObject("browseEndpointContextSupportedConfigs")
		?.optJSONObject("browseEndpointContextMusicConfig")
		?.optString("pageType")
		?.takeIf(String::isNotBlank)

	private fun browseId(run: JSONObject): String? = run
		.optJSONObject("navigationEndpoint")
		?.optJSONObject("browseEndpoint")
		?.optString("browseId")
		?.takeIf(String::isNotBlank)

	private fun columnRuns(columns: JSONArray, index: Int): List<JSONObject> {
		val runs = columns.optJSONObject(index)
			?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
			?.optJSONObject("text")
			?.optJSONArray("runs")
			?: return emptyList()
		return buildList {
			for (i in 0 until runs.length()) runs.optJSONObject(i)?.let(::add)
		}
	}

	private fun fixedColumnRuns(columns: JSONArray?, index: Int): List<JSONObject> {
		val runs = columns?.optJSONObject(index)
			?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
			?.optJSONObject("text")
			?.optJSONArray("runs")
			?: return emptyList()
		return buildList {
			for (i in 0 until runs.length()) runs.optJSONObject(i)?.let(::add)
		}
	}

	private fun firstObject(node: Any?, key: String): JSONObject? {
		when (node) {
			is JSONObject -> {
				node.optJSONObject(key)?.let { return it }
				for (name in node.keys()) firstObject(node.opt(name), key)?.let { return it }
			}
			is JSONArray -> for (i in 0 until node.length()) {
				firstObject(node.opt(i), key)?.let { return it }
			}
		}
		return null
	}

	private fun walk(node: Any?, visit: (JSONObject) -> Unit) {
		when (node) {
			is JSONObject -> {
				visit(node)
				for (name in node.keys()) walk(node.opt(name), visit)
			}
			is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), visit)
		}
	}
}
