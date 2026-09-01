package com.rustedwax.app.enrich

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads `youtube.com/feed/history` into its entries. Pure — no network, no
 * Android, no credentials.
 *
 * ## Why this page exists in the resolver at all
 *
 * Native YouTube publishes no video id on any surface a third-party app can
 * reach — MediaSession metadata, queue, session activity, notification,
 * accessibility tree and the exported `MainAppMediaBrowserService` were all
 * measured empty (`<redacted-private-path>` §1). Inside a playlist the
 * bounded entry list makes identity exact. Outside one — a single video, or
 * anything played with the screen off — the resolver was back to open-world
 * search, which was measured selecting a duration-identical *wrong* upload three
 * separate times (§10.1). The account's own watch history is the only known
 * surface that names the exact id for arbitrary native playback.
 *
 * ## What it is allowed to conclude
 *
 * Nothing on its own. This returns *candidates in the order the page listed
 * them*, newest first. The id is corroborated against the frozen MediaSession
 * tuple exactly like every other route, so a stale entry — or one written by
 * another device on the same account — refuses instead of mis-linking.
 *
 * ## Ordering
 *
 * "Most recent" is the whole point, so order is taken only from JSON *arrays*,
 * which are ordered. `JSONObject.keys()` is not, which is the same trap that
 * made [PlaylistPageParser] read a channel as a title; the section list and its
 * item arrays are descended by index and nothing else is trusted for sequence.
 */
object WatchHistoryParser {

	data class Entry(
		val videoId: String,
		val title: String,
		val channel: String?,
		val lengthSeconds: Long?,
		/**
		 * The uploader's exact `@handle`, from the byline's own canonical link.
		 *
		 * A foreground Short identifies its owner by handle rather than by
		 * display name (`ForegroundShortTracker` sets the session artist to the
		 * handle), so without this the two sides of a Shorts comparison are
		 * describing the same channel in two different vocabularies.
		 */
		val ownerHandle: String? = null,
	)

	/**
	 * A Short the account watched: an id and a title, and nothing else.
	 *
	 * This is a *candidate*, and less of one than an ordinary entry — with no
	 * channel and no duration it cannot corroborate itself at all. It is
	 * therefore never accepted here; the id is handed to the existing
	 * owner-handle verification, which re-fetches that video's own watch page
	 * and requires exact title, duration and `@handle` agreement before
	 * anything is resolved.
	 */
	data class ShortEntry(val videoId: String, val title: String)

	sealed interface Result {
		/** Entries as the page listed them, newest first. May be empty. */
		data class Feed(
			val entries: List<Entry>,
			/**
			 * Shorts, kept apart from [entries] rather than mixed into them.
			 *
			 * A Shorts row is a `shortsLockupViewModel`: it carries an id and a
			 * title and **deliberately no channel and no duration**, so it can
			 * never satisfy the three-field gate that admits an ordinary entry.
			 * Mixing the two lists would therefore add rows that can only fail,
			 * and — worse — those rows would occupy slots in the recent window
			 * and push the ordinary entry that *can* match out of reach.
			 *
			 * Measured 2026-08-05: a Shorts-only viewing session left the
			 * ordinary list byte-identical across three refusals minutes apart,
			 * because every Short had been rendered in a shape this parser was
			 * not reading at all.
			 */
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

	/**
	 * @param json the `ytInitialData` object from the history page
	 */
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

	/**
	 * The history page's own Shorts filter token, or null.
	 *
	 * Measured 2026-08-17: the default history feed stopped carrying Shorts
	 * inline. The page now renders one `chipViewModel` per filter — All, Videos,
	 * Shorts, Podcasts, Music — each a `browseId: FEhistory` differing only by
	 * `params`, and the Shorts cards exist **only** in the filtered response.
	 * The unfiltered page that [WatchHistoryResolver] already downloads returned
	 * 200 ordinary entries and zero `shortsLockupViewModel`, while the same
	 * account's filtered response returned 200 Shorts.
	 *
	 * Read from the page rather than hardcoded, because the token is YouTube's
	 * to change and a stale constant would silently select the wrong filter. The
	 * chip is matched on its own `browseEndpoint`, not on position, and the label
	 * comparison is exact — a missing or renamed chip yields null and the caller
	 * keeps the unfiltered result rather than guessing.
	 */
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

	/**
	 * Every `shortsLockupViewModel` inside one feed item, in array order.
	 *
	 * The id lives under `reelWatchEndpoint` and the title is plain `content` —
	 * the same shape [SearchResultsParser] reads from a search page, because it
	 * is the same card. Confirmed 2026-08-05: a public search page served 26
	 * `shortsLockupViewModel` cards and zero `videoRenderer`, so this is the
	 * only shape a Short arrives in.
	 */
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

	/**
	 * The `@handle` YouTube links the byline to, when it links to one.
	 *
	 * Taken from `canonicalBaseUrl`, which is the channel's own canonical path,
	 * so a display name that happens to look like a handle can never be mistaken
	 * for one. A channel that has no handle, or a row that omits the endpoint,
	 * yields null rather than a guess.
	 */
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
