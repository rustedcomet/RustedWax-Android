package com.rustedwax.app.enrich

import com.rustedwax.app.detect.TitleParser
import org.json.JSONArray
import org.json.JSONObject

object PlaylistPageParser {

	/** Within a known playlist the set is bounded, so title + duration suffices. */
	private const val DURATION_TOLERANCE_SEC = 5L

	private val VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")

	/** True only when the two verified uploads occupy consecutive playlist rows. */
	fun containsAdjacent(
		entries: List<SearchResultsParser.Candidate>,
		firstVideoId: String,
		secondVideoId: String,
	): Boolean = followersAfterAdjacent(entries, firstVideoId, secondVideoId).isNotEmpty() ||
		entries.takeLast(2).let { pair ->
			pair.size == 2 && pair[0].videoId == firstVideoId && pair[1].videoId == secondVideoId
		}

	/** Every row immediately following the exact ordered predecessor pair. */
	fun followersAfterAdjacent(
		entries: List<SearchResultsParser.Candidate>,
		firstVideoId: String,
		secondVideoId: String,
	): List<SearchResultsParser.Candidate> = entries.windowed(size = 3).mapNotNull { rows ->
		rows[2].takeIf {
			rows[0].videoId == firstVideoId && rows[1].videoId == secondVideoId
		}
	}

	fun entries(json: String): List<SearchResultsParser.Candidate> {
		val out = LinkedHashMap<String, SearchResultsParser.Candidate>()
		walk(JSONObject(json)) { node ->
			val lockup = node.optJSONObject("lockupViewModel") ?: return@walk
			val id = lockup.optString("contentId").takeIf { VIDEO_ID.matches(it) } ?: return@walk
			if (out.containsKey(id)) return@walk

			// Read the title by its own key rather than by position among the
			// metadata strings: JSONObject.keys() has no defined order, so
			// "first string found" returned the channel about as often as the
			// title. Only `metadataRows` is a JSON array, and arrays *are*
			// ordered — hence the channel is safe to take as its first row.
			val meta = firstObject(lockup.optJSONObject("metadata"), "lockupMetadataViewModel")
			val title = meta?.optJSONObject("title")?.optString("content")
				?.takeIf { it.isNotBlank() } ?: return@walk

			val rows = mutableListOf<String>()
			collect(meta.optJSONObject("metadata"), "content", rows)

			val badges = mutableListOf<String>()
			collectBadges(lockup, badges)

			out[id] = SearchResultsParser.Candidate(
				videoId = id,
				title = title,
				channel = rows.firstOrNull()?.takeIf { it.isNotBlank() },
				lengthSeconds = badges.firstNotNullOfOrNull(SearchResultsParser::parseClock),
			)
		}
		return out.values.toList()
	}

	/**
	 * Every entry of this playlist the session could be playing.
	 *
	 * Channel is checked only when the page supplies one — inside a bounded
	 * playlist a title plus a matching duration is already decisive, and
	 * demanding a channel would drop entries whose row layout differs.
	 */
	fun matches(
		entries: List<SearchResultsParser.Candidate>,
		title: String,
		channel: String?,
		durationSec: Long?,
	): List<SearchResultsParser.Candidate> {
		if (durationSec == null || durationSec <= 0) return emptyList()
		val wantTitle = normalize(title)
		if (wantTitle.isEmpty()) return emptyList()
		// Whitespace-insensitive for the same reason search is: VEVO channels
		// are one word ("systemofadownVEVO") where listings show three
		// ("System Of A Down").
		val wantChannel = SearchResultsParser.channelKey(channel)

		return entries.filter { e ->
			val len = e.lengthSeconds ?: return@filter false
			if (normalize(e.title) != wantTitle) return@filter false
			if (kotlin.math.abs(len - durationSec) > DURATION_TOLERANCE_SEC) return@filter false
			val entryChannel = SearchResultsParser.channelKey(e.channel)
			entryChannel == null || wantChannel == null || entryChannel == wantChannel
		}.distinctBy(SearchResultsParser.Candidate::videoId)
	}

	fun match(
		entries: List<SearchResultsParser.Candidate>,
		title: String,
		channel: String?,
		durationSec: Long?,
	): SearchResultsParser.Candidate? =
		matches(entries, title, channel, durationSec).singleOrNull()

	/** First object stored under [key] anywhere in the subtree. */
	private fun firstObject(node: Any?, key: String): JSONObject? {
		when (node) {
			is JSONObject -> {
				node.optJSONObject(key)?.let { return it }
				for (k in node.keys()) firstObject(node.opt(k), key)?.let { return it }
			}

			is JSONArray -> for (i in 0 until node.length()) {
				firstObject(node.opt(i), key)?.let { return it }
			}
		}
		return null
	}

	private fun collect(node: Any?, key: String, out: MutableList<String>) {
		when (node) {
			is JSONObject -> for (k in node.keys()) {
				val v = node.opt(k)
				if (k == key && v is String) out += v else collect(v, key, out)
			}

			is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
		}
	}

	private fun collectBadges(node: Any?, out: MutableList<String>) {
		when (node) {
			is JSONObject -> {
				node.optJSONObject("thumbnailBadgeViewModel")
					?.optString("text")
					?.takeIf { it.isNotEmpty() }
					?.let { out += it }
				for (k in node.keys()) collectBadges(node.opt(k), out)
			}

			is JSONArray -> for (i in 0 until node.length()) collectBadges(node.opt(i), out)
		}
	}

	private fun walk(node: Any?, visit: (JSONObject) -> Unit) {
		when (node) {
			is JSONObject -> {
				visit(node)
				for (k in node.keys()) walk(node.opt(k), visit)
			}

			is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), visit)
		}
	}

	private fun normalize(value: String): String =
		value.lowercase()
			.replace(Regex("""["'’‘“”]"""), "")
			.replace(Regex("""\s+"""), " ")
			.trim()
}
