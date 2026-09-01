package com.rustedwax.app.enrich

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * Finds a playlist id from the name the native YouTube player put on screen.
 *
 * Native YouTube names the playlist but never its id, so the id is recovered by
 * one playlist-filtered search. That is only safe because playlist names are far
 * more distinctive than song titles. Measured 2026-08-04 against the live
 * playlist-filtered search page:
 *
 * | query | playlist results | exact-title matches |
 * | --- | ---: | ---: |
 * | `Reggaeton 2016,17,18` | 20 | **1** |
 * | `Reggaeton` | 20 | 0 |
 * | `Workout` | 18 | 0 |
 *
 * So requiring an *exact* normalized title either yields exactly one playlist or
 * yields none — a generic name fails closed by construction rather than by a
 * heuristic. Anything other than a single survivor resolves to nothing and the
 * caller falls back to the existing search path.
 */
object PlaylistSearchParser {

	data class Candidate(
		val playlistId: String,
		val title: String,
		val owner: String?,
		val videoCount: Int?,
	)

	/** Mixes (`RD…`) are excluded here as well as downstream: they have no fetchable page. */
	private val PLAYLIST_ID = Regex("""^(?:PL|UU|OL|FL)[A-Za-z0-9_-]{10,}$""")

	private val VIDEO_COUNT = Regex("""(\d[\d,.\s]*)\s+videos?""", RegexOption.IGNORE_CASE)

	fun candidates(json: String): List<Candidate> {
		val out = LinkedHashMap<String, Candidate>()
		walk(JSONObject(json)) { node ->
			val lockup = node.optJSONObject("lockupViewModel") ?: return@walk
			val id = lockup.optString("contentId").takeIf { PLAYLIST_ID.matches(it) } ?: return@walk
			if (out.containsKey(id)) return@walk

			val meta = firstObject(lockup.optJSONObject("metadata"), "lockupMetadataViewModel")
			val title = meta?.optJSONObject("title")?.optString("content")
				?.takeIf { it.isNotBlank() } ?: return@walk

			val rows = mutableListOf<String>()
			collectStrings(meta.optJSONObject("metadata"), "content", rows)

			// The "N videos" badge is not in the metadata rows on every layout,
			// so it is read from anywhere inside this lockup.
			val everything = mutableListOf<String>()
			collectAllStrings(lockup, everything)
			val count = everything.firstNotNullOfOrNull { value ->
				VIDEO_COUNT.find(value)?.groupValues?.get(1)
					?.replace(Regex("""[^\d]"""), "")
					?.toIntOrNull()
			}

			out[id] = Candidate(
				playlistId = id,
				title = title,
				owner = rows.firstOrNull()?.takeIf { it.isNotBlank() },
				videoCount = count,
			)
		}
		return out.values.toList()
	}

	/**
	 * The single playlist matching what the player showed, or null.
	 *
	 * [owner] and [total] are corroboration only — they are used to *reject*, never
	 * to select, because the collapsed playlist bar does not always publish an
	 * owner and the on-screen total counts entries the page cannot render
	 * (the measured playlist claimed 120 with 19 unavailable).
	 */
	fun match(
		candidates: List<Candidate>,
		name: String,
		owner: String? = null,
		total: Int? = null,
	): Candidate? {
		val wanted = normalize(name)
		if (wanted.isEmpty()) return null

		val exact = candidates.filter { normalize(it.title) == wanted }
		val byOwner = if (owner.isNullOrBlank()) {
			exact
		} else {
			val wantedOwner = normalize(owner)
			// Keep candidates that agree, and those that published no owner at all.
			exact.filter { it.owner == null || normalize(it.owner) == wantedOwner }
		}
		val byCount = if (total == null) {
			byOwner
		} else {
			byOwner.filter { it.videoCount == null || it.videoCount == total }
		}
		return byCount.singleOrNull()
	}

	private fun normalize(value: String): String {
		val stripped = Normalizer.normalize(value, Normalizer.Form.NFKD)
			.replace(Regex("""\p{Mn}+"""), "")
		return stripped.lowercase(Locale.ROOT)
			.replace(Regex("""[^\p{L}\p{N}]+"""), " ")
			.trim()
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

	private fun collectStrings(node: Any?, key: String, out: MutableList<String>) {
		when (node) {
			is JSONObject -> for (k in node.keys()) {
				val v = node.opt(k)
				if (k == key && v is String) out += v else collectStrings(v, key, out)
			}

			is JSONArray -> for (i in 0 until node.length()) collectStrings(node.opt(i), key, out)
		}
	}

	private fun collectAllStrings(node: Any?, out: MutableList<String>) {
		if (out.size > MAX_STRINGS) return
		when (node) {
			is JSONObject -> for (k in node.keys()) {
				val v = node.opt(k)
				if (v is String) out += v else collectAllStrings(v, out)
			}

			is JSONArray -> for (i in 0 until node.length()) collectAllStrings(node.opt(i), out)
		}
	}

	private const val MAX_STRINGS = 400
}
