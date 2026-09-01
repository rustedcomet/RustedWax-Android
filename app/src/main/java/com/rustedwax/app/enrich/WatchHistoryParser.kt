package com.rustedwax.app.enrich

import org.json.JSONArray
import org.json.JSONObject

object WatchHistoryParser {

	data class Entry(
		val videoId: String,
		val title: String,
		val channel: String?,
		val lengthSeconds: Long?,

		val ownerHandle: String? = null,
	)

	data class ShortEntry(val videoId: String, val title: String)

	sealed interface Result {
		/** Entries as the page listed them, newest first. May be empty. */
		data class Feed(
			val entries: List<Entry>,

			val shorts: List<ShortEntry> = emptyList(),
		) : Result

		/** The feed cannot answer, and this is exactly why. */
		data class Unreadable(val reason: Reason, val detail: String) : Result
	}

	enum class Reason {
		/** No session, or one YouTube no longer accepts. Indistinguishable here. */
		SIGNED_OUT,

		/** The account is signed in but recording nothing. */
		HISTORY_PAUSED,

		/** Signed in, recording, and the page carried no watchable entry. */
		EMPTY,

		/** The document parsed but does not have the shape this reads. */
		MARKUP_CHANGED,
	}

	private val VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")

	fun parse(json: String): Result {
		val root = runCatching { JSONObject(json) }.getOrElse {
			return Result.Unreadable(Reason.MARKUP_CHANGED, "ytInitialData was not an object")
		}

		// Exact, and published by YouTube itself rather than inferred from the
		// absence of entries — an empty history and a dead session must not read
		// the same, because only one of them is the user's problem to fix.
		val loggedOut = root.optJSONObject("responseContext")
			?.optJSONObject("mainAppWebResponseContext")
			?.takeIf { it.has("loggedOut") }
			?.optBoolean("loggedOut")
		if (loggedOut == true) {
			return Result.Unreadable(
				Reason.SIGNED_OUT,
				"youtube.com answered as signed out (responseContext.loggedOut)",
			)
		}

		val sections = sectionList(root) ?: return Result.Unreadable(
			Reason.MARKUP_CHANGED,
			"no sectionListRenderer in the history feed",
		)

		val entries = mutableListOf<Entry>()
		val shorts = mutableListOf<ShortEntry>()
		val seen = mutableSetOf<String>()
		for (i in 0 until sections.length()) {
			val items = (sections.opt(i) as? JSONObject)
				?.optJSONObject("itemSectionRenderer")
				?.optJSONArray("contents")
				?: continue
			for (j in 0 until items.length()) {
				val item = items.opt(j) as? JSONObject ?: continue

				// A Shorts *shelf* holds many Shorts in one item, so it is
				// collected wholesale rather than as one row.
				collectShorts(item, shorts, seen)

				// Both ordinary shapes, searched inside this one item so array
				// order — the only ordering this parser is allowed to trust — is
				// preserved. A row may also arrive wrapped in a
				// `richItemRenderer`, hence a search rather than a direct child.
				val entry = find(item, "videoRenderer")?.let(::entryOf)
					?: find(item, "lockupViewModel")?.let(::lockupEntryOf)
					?: continue
				if (seen.add(entry.videoId)) entries += entry
			}
		}

		if (entries.isEmpty() && shorts.isEmpty()) {
			pausedNotice(root)?.let { return Result.Unreadable(Reason.HISTORY_PAUSED, it) }
			return Result.Unreadable(
				Reason.EMPTY,
				"the history feed carried no entries",
			)
		}
		return Result.Feed(entries, shorts)
	}

	fun shortsFilterToken(json: String): String? {
		val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
		return findShortsChipParams(root)
	}

	private fun findShortsChipParams(node: Any?): String? {
		when (node) {
			is JSONObject -> {
				node.optJSONObject("chipViewModel")?.let { chip ->
					if (chip.optString("text") == SHORTS_CHIP_LABEL) {
						val endpoint = find(chip, "browseEndpoint")
						val params = endpoint?.optString("params")?.takeIf { it.isNotBlank() }
						if (params != null && endpoint.optString("browseId") == HISTORY_BROWSE_ID) {
							return params
						}
					}
				}
				for (k in node.keys()) findShortsChipParams(node.opt(k))?.let { return it }
			}

			is JSONArray -> for (i in 0 until node.length()) {
				findShortsChipParams(node.opt(i))?.let { return it }
			}
		}
		return null
	}

	private const val SHORTS_CHIP_LABEL = "Shorts"
	private const val HISTORY_BROWSE_ID = "FEhistory"

	private fun collectShorts(
		node: Any?,
		out: MutableList<ShortEntry>,
		seen: MutableSet<String>,
	) {
		when (node) {
			is JSONObject -> {
				node.optJSONObject("shortsLockupViewModel")?.let { lockup ->
					val id = find(lockup, "reelWatchEndpoint")
						?.optString("videoId")
						?.takeIf { VIDEO_ID.matches(it) }
					val title = lockup.optJSONObject("overlayMetadata")
						?.optJSONObject("primaryText")
						?.optString("content")
						?.takeIf { it.isNotBlank() }
					if (id != null && title != null && seen.add(id)) {
						out += ShortEntry(id, title)
					}
				}
				for (k in node.keys()) collectShorts(node.opt(k), out, seen)
			}

			is JSONArray -> for (i in 0 until node.length()) collectShorts(node.opt(i), out, seen)
		}
	}

	private fun entryOf(renderer: JSONObject): Entry? {
		val id = renderer.optString("videoId").takeIf { VIDEO_ID.matches(it) } ?: return null
		val title = simpleOrRuns(renderer.optJSONObject("title"))
			?.takeIf { it.isNotBlank() } ?: return null
		val channel = simpleOrRuns(renderer.optJSONObject("longBylineText"))
			?: simpleOrRuns(renderer.optJSONObject("ownerText"))
			?: simpleOrRuns(renderer.optJSONObject("shortBylineText"))
		val length = simpleOrRuns(renderer.optJSONObject("lengthText"))
			?.let(SearchResultsParser::parseClock)
			?: overlayDuration(renderer)
		return Entry(
			videoId = id,
			title = title,
			channel = channel?.takeIf { it.isNotBlank() },
			lengthSeconds = length,
			ownerHandle = ownerHandleOf(renderer),
		)
	}

	private fun ownerHandleOf(renderer: JSONObject): String? {
		for (key in listOf("longBylineText", "ownerText", "shortBylineText")) {
			val runs = renderer.optJSONObject(key)?.optJSONArray("runs") ?: continue
			for (i in 0 until runs.length()) {
				val path = (runs.opt(i) as? JSONObject)
					?.optJSONObject("navigationEndpoint")
					?.optJSONObject("browseEndpoint")
					?.optString("canonicalBaseUrl")
					?.takeIf { it.isNotBlank() }
					?: continue
				val segment = path.trim().trimStart('/')
				if (segment.startsWith('@')) {
					OwnerHandle.canonical(segment)?.let { return it }
				}
			}
		}
		return null
	}

	/**
	 * The `lockupViewModel` shape.
	 *
	 * Playlist pages migrated to this and dropped `playlistVideoRenderer`
	 * entirely, which is exactly the kind of change that turns a working feed
	 * into a silent "your history is empty". The fields are the same ones
	 * [PlaylistPageParser] reads: id in `contentId`, title under
	 * `lockupMetadataViewModel`, channel as the first metadata row, duration in
	 * a thumbnail badge.
	 */
	private fun lockupEntryOf(lockup: JSONObject): Entry? {
		val id = lockup.optString("contentId").takeIf { VIDEO_ID.matches(it) } ?: return null
		val meta = find(lockup, "lockupMetadataViewModel") ?: return null
		val title = meta.optJSONObject("title")?.optString("content")
			?.takeIf { it.isNotBlank() } ?: return null

		val rows = mutableListOf<String>()
		collectStrings(meta.optJSONObject("metadata"), "content", rows)
		val badges = mutableListOf<String>()
		collectStrings(lockup, "text", badges, under = "thumbnailBadgeViewModel")

		return Entry(
			videoId = id,
			title = title,
			channel = rows.firstOrNull()?.takeIf { it.isNotBlank() },
			lengthSeconds = badges.firstNotNullOfOrNull(SearchResultsParser::parseClock),
		)
	}

	/** `thumbnailOverlayTimeStatusRenderer` when the row omits `lengthText`. */
	private fun overlayDuration(renderer: JSONObject): Long? {
		val overlays = renderer.optJSONArray("thumbnailOverlays") ?: return null
		for (i in 0 until overlays.length()) {
			val text = (overlays.opt(i) as? JSONObject)
				?.optJSONObject("thumbnailOverlayTimeStatusRenderer")
				?.optJSONObject("text")
				?.let(::simpleOrRuns)
				?: continue
			SearchResultsParser.parseClock(text)?.let { return it }
		}
		return null
	}

	/**
	 * The paused state, taken from the control the page offers.
	 *
	 * A recording account is offered "Pause watch history"; a paused one is
	 * offered to turn it back on, and says so in the body of the empty feed.
	 * Read only when there are no entries, so a stale phrase can never suppress
	 * a feed that is plainly working.
	 */
	private fun pausedNotice(root: JSONObject): String? {
		val strings = mutableListOf<String>()
		collectText(root, strings)
		return strings.firstOrNull { line ->
			PAUSED_PHRASES.any { line.contains(it, ignoreCase = true) }
		}?.let { "youtube.com says: \"${it.take(120)}\"" }
	}

	private val PAUSED_PHRASES = listOf(
		"turn on watch history",
		"watch history is paused",
		"watch history is off",
		"history is paused",
	)

	/** First `sectionListRenderer` reachable from the browse tabs. */
	private fun sectionList(root: JSONObject): JSONArray? {
		var found: JSONArray? = null
		walk(root) { node ->
			if (found == null) {
				node.optJSONObject("sectionListRenderer")?.optJSONArray("contents")?.let {
					found = it
				}
			}
		}
		return found
	}

	/** `{"simpleText":…}` or `{"runs":[{"text":…}]}`, the two shapes YouTube mixes. */
	private fun simpleOrRuns(node: JSONObject?): String? {
		if (node == null) return null
		node.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
		val runs = node.optJSONArray("runs") ?: return null
		val out = StringBuilder()
		for (i in 0 until runs.length()) {
			(runs.opt(i) as? JSONObject)?.optString("text")?.let(out::append)
		}
		return out.toString().takeIf { it.isNotBlank() }
	}

	/** First object stored under [key] anywhere in this subtree. */
	private fun find(node: Any?, key: String): JSONObject? {
		when (node) {
			is JSONObject -> {
				node.optJSONObject(key)?.let { return it }
				for (k in node.keys()) find(node.opt(k), key)?.let { return it }
			}

			is JSONArray -> for (i in 0 until node.length()) {
				find(node.opt(i), key)?.let { return it }
			}
		}
		return null
	}

	/**
	 * Every string stored under [key], optionally only inside objects reached
	 * through [under]. Used for metadata rows and duration badges, where the
	 * value is a bare string rather than a text renderer.
	 */
	private fun collectStrings(
		node: Any?,
		key: String,
		out: MutableList<String>,
		under: String? = null,
		inside: Boolean = under == null,
	) {
		when (node) {
			is JSONObject -> for (k in node.keys()) {
				val value = node.opt(k)
				val within = inside || k == under
				if (within && k == key && value is String) out += value
				else collectStrings(value, key, out, under, within)
			}

			is JSONArray -> for (i in 0 until node.length()) {
				collectStrings(node.opt(i), key, out, under, inside)
			}
		}
	}

	private fun collectText(node: Any?, out: MutableList<String>) {
		when (node) {
			is JSONObject -> {
				if (node.has("simpleText") || node.has("runs")) {
					simpleOrRuns(node)?.let(out::add)
				}
				for (k in node.keys()) collectText(node.opt(k), out)
			}

			is JSONArray -> for (i in 0 until node.length()) collectText(node.opt(i), out)
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
}
