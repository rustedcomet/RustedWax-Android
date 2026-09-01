package com.rustedwax.app.enrich

import org.json.JSONArray
import org.json.JSONObject

/**
 * The chapter list from a watch page — §6.3.
 *
 * ## Why this exists now, when nothing consumes it yet
 *
 * The video still scrobbles exactly once, as it always did. Nothing on chain
 * can be amended, so a chapter list recorded after the fact cannot retroactively
 * split an entry, and the near-term value is diagnostic only.
 *
 * The reason to build it anyway is that it is the *reusable* piece. Per-chapter
 * scrobbling later needs four things: chapter parsing, per-chapter play
 * measurement from seekbar position, per-chapter dedup keys, and N transactions
 * from one video. This is the first of those and the only one that touches
 * nothing delicate — the other three all reach into the measurement and dedup
 * paths that three field rounds have been spent hardening.
 *
 * ## Where chapters actually live
 *
 * `MediaSession` does not carry them, which is what made them look
 * platform-blocked. They are in the watch page the resolver already fetches, in
 * `playerOverlays.….decoratedPlayerBarRenderer`, as `chapterRenderer` entries
 * with a title and a start offset in milliseconds.
 *
 * A single "chapter" spanning the whole video is YouTube's own placeholder and
 * is not a chapter list; a real one has at least two.
 */
object ChapterParser {

	data class Chapter(val title: String, val startMs: Long)

	/** Fewer than this is a placeholder, not a chaptered video. */
	private const val MIN_CHAPTERS = 2

	/**
	 * @param initialPlayerJson the `ytInitialData` blob from the watch page.
	 * @return the chapters in start order, or an empty list. Never null — an
	 * unchaptered video is the ordinary case, not an error worth distinguishing.
	 */
	fun parse(initialPlayerJson: String?): List<Chapter> {
		val root = runCatching { JSONObject(initialPlayerJson.orEmpty()) }.getOrNull()
			?: return emptyList()
		val markers = findMarkersMap(root) ?: return emptyList()
		val chapters = mutableListOf<Chapter>()
		for (index in 0 until markers.length()) {
			val renderer = markers.optJSONObject(index)
				?.optJSONObject("chapterRenderer") ?: continue
			val title = simpleText(renderer.optJSONObject("title"))
				?.trim()?.takeIf(String::isNotEmpty) ?: continue
			// `optLong` would turn a missing offset into chapter zero, which would
			// silently invent a chapter boundary at the start of the video.
			if (!renderer.has("timeRangeStartMillis")) continue
			chapters += Chapter(title, renderer.optLong("timeRangeStartMillis"))
		}
		if (chapters.size < MIN_CHAPTERS) return emptyList()
		return chapters.sortedBy(Chapter::startMs)
	}

	/**
	 * Walks to the marker list rather than indexing a fixed path.
	 *
	 * YouTube reshuffles the containers around `decoratedPlayerBarRenderer`
	 * regularly, and a hardcoded path is the thing that breaks silently and looks
	 * like "this video has no chapters". Searching for the distinctive key is the
	 * same tactic `WatchPageParser` already uses for `videoDetails`.
	 */
	private fun findMarkersMap(node: Any?): JSONArray? = when (node) {
		is JSONObject -> {
			node.optJSONArray("markers")
				?.takeIf { hasChapterRenderer(it) }
				?: node.keys().asSequence()
					.mapNotNull { key -> findMarkersMap(node.opt(key)) }
					.firstOrNull()
		}

		is JSONArray -> (0 until node.length()).asSequence()
			.mapNotNull { findMarkersMap(node.opt(it)) }
			.firstOrNull()

		else -> null
	}

	/**
	 * YouTube writes a display string either as `simpleText` or as a list of
	 * `runs` to be concatenated. Both shapes appear on chapter titles.
	 */
	private fun simpleText(node: JSONObject?): String? {
		if (node == null) return null
		node.optString("simpleText").takeIf(String::isNotEmpty)?.let { return it }
		val runs = node.optJSONArray("runs") ?: return null
		val text = (0 until runs.length())
			.mapNotNull { runs.optJSONObject(it)?.optString("text") }
			.joinToString("")
		return text.takeIf(String::isNotEmpty)
	}

	private fun hasChapterRenderer(markers: JSONArray): Boolean =
		(0 until markers.length()).any {
			markers.optJSONObject(it)?.has("chapterRenderer") == true
		}
}
