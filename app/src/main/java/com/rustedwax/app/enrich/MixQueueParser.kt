package com.rustedwax.app.enrich

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the bounded queue YouTube embeds in an `RD…` Mix watch page.
 *
 * A Mix has no useful `/playlist` page, but its watch page carries the actual
 * queue as `playlistPanelVideoRenderer` rows. Those rows are stronger than
 * search: an interstitial ad is never a queue entry, and the row names the
 * exact upload rather than merely a plausible copy of the same song.
 */
object MixQueueParser {

	private val VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")

	fun entries(json: String): List<SearchResultsParser.Candidate> {
		val out = LinkedHashMap<String, SearchResultsParser.Candidate>()
		walk(JSONObject(json)) { node ->
			val row = node.optJSONObject("playlistPanelVideoRenderer") ?: return@walk
			val id = row.optString("videoId").takeIf(VIDEO_ID::matches) ?: return@walk
			val title = text(row.opt("title")) ?: return@walk
			val channel = text(row.opt("longBylineText"))
				?: text(row.opt("shortBylineText"))
			val duration = text(row.opt("lengthText"))
				?.let(SearchResultsParser::parseClock)
			out.putIfAbsent(
				id,
				SearchResultsParser.Candidate(id, title, channel, duration),
			)
		}
		return out.values.toList()
	}

	private fun text(node: Any?): String? {
		val value = node as? JSONObject ?: return null
		value.optString("simpleText").takeIf(String::isNotBlank)?.let { return it }
		val runs = value.optJSONArray("runs") ?: return null
		return buildString {
			for (i in 0 until runs.length()) {
				append(runs.optJSONObject(i)?.optString("text").orEmpty())
			}
		}.takeIf(String::isNotBlank)
	}

	private fun walk(node: Any?, visit: (JSONObject) -> Unit) {
		when (node) {
			is JSONObject -> {
				visit(node)
				for (key in node.keys()) walk(node.opt(key), visit)
			}

			is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), visit)
		}
	}
}
