package com.rustedwax.app.enrich

import com.rustedwax.app.detect.TitleParser
import com.rustedwax.app.detect.VideoTitleMatcher
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

object SearchResultsParser {

	data class Candidate(
		val videoId: String,
		val title: String,
		val channel: String?,
		val lengthSeconds: Long?,
		/** The byline opens YouTube's explicit multi-owner/collaborator dialog. */
		val collaborativeChannel: Boolean = false,
	)

	/** Search's displayed length is rounded — the real video was 274 s, listed as 4:35. */
	private const val DURATION_TOLERANCE_SEC = 5L

	private val VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")

	/**
	 * Pulls every video-like node out of `ytInitialData`.
	 *
	 * Walks the tree looking for objects carrying a `videoId` and a `title`
	 * rather than following a fixed path like
	 * `contents.twoColumnSearchResultsRenderer.…`. The renderer nesting differs
	 * between the desktop and mobile shells and is reorganised freely; the
	 * shape of a result item is the stable part.
	 */
	fun candidates(json: String): List<Candidate> {
		val out = LinkedHashMap<String, Candidate>()
		walk(JSONObject(json)) { node ->
			// YouTube moved Shorts search cards away from `videoRenderer`.
			// `shortsLockupViewModel` carries the id below `reelWatchEndpoint`
			// and the title as plain `content`; it deliberately carries neither
			// channel nor duration. The resolver corroborates those two fields
			// against each candidate's watch page before accepting the id.
			node.optJSONObject("shortsLockupViewModel")?.let { lockup ->
				val id = firstObject(lockup, "reelWatchEndpoint")
					?.optString("videoId")
					?.takeIf { VIDEO_ID.matches(it) }
					?: return@let
				val title = lockup.optJSONObject("overlayMetadata")
					?.optJSONObject("primaryText")
					?.optString("content")
					?.takeIf { it.isNotBlank() }
					?: return@let
				out.putIfAbsent(id, Candidate(id, title, channel = null, lengthSeconds = null))
			}

			// The modern ordinary-video card. Playlist cards use the same name,
			// but their `contentId` is not an eleven-character video id and is
			// therefore ignored.
			node.optJSONObject("lockupViewModel")?.let { lockup ->
				val id = lockup.optString("contentId")
					.takeIf { VIDEO_ID.matches(it) } ?: return@let
				val metadata = firstObject(
					lockup.optJSONObject("metadata"),
					"lockupMetadataViewModel",
				)
				val title = metadata?.optJSONObject("title")
					?.optString("content")
					?.takeIf { it.isNotBlank() } ?: return@let
				val rows = mutableListOf<String>()
				collectStrings(metadata.optJSONObject("metadata"), "content", rows)
				val clocks = mutableListOf<String>()
				collectStrings(lockup, "text", clocks)
				out.putIfAbsent(
					id,
					Candidate(
						videoId = id,
						title = title,
						channel = rows.firstOrNull()?.takeIf { it.isNotBlank() },
						lengthSeconds = clocks.firstNotNullOfOrNull(::parseClock),
						collaborativeChannel = firstObject(metadata, "showDialogCommand") != null,
					),
				)
			}

			val id = node.optString("videoId").takeIf { VIDEO_ID.matches(it) } ?: return@walk
			val title = text(node.opt("title"))
				?: text(node.opt("headline"))
				?: return@walk
			if (out.containsKey(id)) return@walk
			val ownerText = node.opt("ownerText")
			val longBylineText = node.opt("longBylineText")
			val shortBylineText = node.opt("shortBylineText")
			val byline = when {
				text(ownerText) != null -> ownerText
				text(longBylineText) != null -> longBylineText
				text(shortBylineText) != null -> shortBylineText
				else -> null
			}
			out[id] = Candidate(
				videoId = id,
				title = title,
				channel = text(byline),
				lengthSeconds = text(node.opt("lengthText"))?.let(::parseClock),
				collaborativeChannel = firstObject(byline, "showDialogCommand") != null,
			)
		}
		return out.values.toList()
	}

	fun bestMatch(
		candidates: List<Candidate>,
		title: String,
		channel: String?,
		durationSec: Long?,
	): Candidate? = identityMatches(candidates, title, channel, durationSec).singleOrNull()

	/** Every strict three-field match, retained so ambiguity can be explained. */
	fun identityMatches(
		candidates: List<Candidate>,
		title: String,
		channel: String?,
		durationSec: Long?,
	): List<Candidate> {
		if (durationSec == null || durationSec <= 0) return emptyList()
		val wantChannel = channelKey(channel)
		if (wantChannel == null) return emptyList()

		return candidates.filter { c ->
			val len = c.lengthSeconds ?: return@filter false
			titleAgrees(title, c.title) &&
				channelMatches(c, wantChannel) &&
				kotlin.math.abs(len - durationSec) <= DURATION_TOLERANCE_SEC
		}
	}

	private fun titleAgrees(sessionTitle: String, candidateTitle: String): Boolean =
		shortTitleMatches(sessionTitle, candidateTitle) ||
			VideoTitleMatcher.mentionSpellingOnly(sessionTitle, candidateTitle)

	/**
	 * Whether a fully-populated candidate proves the same media-session item.
	 * Used again after a Shorts card has been completed from its watch page, so
	 * the ordinary and Shorts paths cannot drift into different identity rules.
	 */
	fun matchesIdentity(
		candidate: Candidate,
		title: String,
		channel: String?,
		durationSec: Long?,
	): Boolean = bestMatch(listOf(candidate), title, channel, durationSec) != null

	/**
	 * A search card worth completing from its watch page. Missing card fields are
	 * allowed; a field that is present and contradicts the session is not.
	 */
	fun hasNoIdentityContradiction(
		candidate: Candidate,
		title: String,
		channel: String?,
		durationSec: Long?,
	): Boolean {
		if (!titleAgrees(title, candidate.title)) return false
		val wantChannel = channelKey(channel) ?: return false
		val candidateChannel = channelKey(candidate.channel)
		if (candidateChannel != null && !channelMatches(candidate, wantChannel)) return false
		val candidateDuration = candidate.lengthSeconds
		if (
			candidateDuration != null && durationSec != null &&
			kotlin.math.abs(candidateDuration - durationSec) > DURATION_TOLERANCE_SEC
		) return false
		return true
	}

	/**
	 * Exact owner agreement, with one bounded exception for YouTube's own
	 * collaborator byline. The exception is available only when the parsed card
	 * carried the explicit collaborator-dialog command; arbitrary channel names
	 * containing "and" are never split. Title and duration remain independently
	 * mandatory, and the complete result set must still contain exactly one id.
	 */
	private fun channelMatches(candidate: Candidate, wantChannel: String): Boolean {
		if (channelKey(candidate.channel) == wantChannel) return true
		if (!candidate.collaborativeChannel) return false
		val leader = COLLABORATOR_LEADER.find(candidate.channel.orEmpty())
			?.groupValues?.get(1) ?: return false
		return channelKey(leader) == wantChannel
	}

	/**
	 * Identity title key. Hashtags, emoji and punctuation are presentation noise
	 * in Shorts MediaSession titles and search cards, but words and numbers are
	 * retained. Channel and duration still have to match independently.
	 */
	fun titleKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
		.lowercase(Locale.ROOT)
		.replace(Regex("""\p{M}+"""), "")
		.replace(HASHTAG, " ")
		.replace(SYMBOL, " ")
		.replace(Regex("""[^\p{L}\p{N}]+"""), " ")
		.replace(Regex("""\s+"""), " ")
		.trim()

	/**
	 * Title agreement for Shorts, whose complete visible name may be a hashtag
	 * or emoji. A non-empty semantic key retains the established rule that
	 * presentation hashtags are noise beside real words. If that key is empty,
	 * only exact non-empty presentation equality may agree: `#hoyoverse` must
	 * never select `#unrelated`, and `🥰❤️` must never select `😂❤️`.
	 */
	fun shortTitleMatches(first: String, second: String): Boolean {
		val firstSemantic = titleKey(first)
		return if (firstSemantic.isNotEmpty()) {
			firstSemantic == titleKey(second)
		} else {
			VideoTitleMatcher.samePresentation(first, second)
		}
	}

	/** A shorter second search query for titles whose hashtag tail dominates the URL. */
	fun searchTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
		.replace(HASHTAG, " ")
		.replace(SYMBOL, " ")
		.replace(Regex("""\s+"""), " ")
		.trim()

	fun channelKey(channel: String?): String? = channel
		?.let { TitleParser.cleanChannel(it) }
		?.let { Normalizer.normalize(it, Normalizer.Form.NFKD) }
		?.lowercase(Locale.ROOT)
		?.replace(Regex("""\p{M}+"""), "")
		?.replace(Regex("""[^\p{L}\p{N}]+"""), "")
		?.takeIf { it.isNotEmpty() }

	/**
	 * The uploading channel at the head of a collaborative byline.
	 *
	 * `"La Melma Music and 2 more"` -> `"La Melma Music"`. Null when the name is
	 * not a byline at all, so a caller can tell "no collaborators" apart from
	 * "leader did not match".
	 */
	fun collaboratorLeader(channel: String?): String? = channel
		?.trim()
		?.let { COLLABORATOR_LEADER.matchEntire(it) }
		?.groupValues?.get(1)
		?.trim()
		?.takeIf { it.isNotEmpty() }

	private val COLLABORATOR_LEADER = Regex(
		"""^(.+?)\s+(?:and|&|x|×)\s+(?:\d+\s+more|.+)$""",
		RegexOption.IGNORE_CASE,
	)

	/** `4:35` → 275, `1:02:33` → 3753. */
	fun parseClock(value: String): Long? {
		val parts = value.trim().split(':')
		if (parts.size !in 2..3) return null
		var total = 0L
		for (p in parts) {
			val n = p.trim().toLongOrNull() ?: return null
			total = total * 60 + n
		}
		return total
	}

	/** Renderer text is either `{simpleText}` or `{runs:[{text}]}`. */
	private fun text(node: Any?): String? {
		val o = node as? JSONObject ?: return null
		o.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
		val runs = o.optJSONArray("runs") ?: return null
		val sb = StringBuilder()
		for (i in 0 until runs.length()) {
			sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
		}
		return sb.toString().takeIf { it.isNotEmpty() }
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
				for (child in node.keys()) firstObject(node.opt(child), key)?.let { return it }
			}

			is JSONArray -> for (i in 0 until node.length()) {
				firstObject(node.opt(i), key)?.let { return it }
			}
		}
		return null
	}

	private fun collectStrings(node: Any?, key: String, out: MutableList<String>) {
		when (node) {
			is JSONObject -> for (child in node.keys()) {
				val value = node.opt(child)
				if (child == key && value is String) out += value
				else collectStrings(value, key, out)
			}

			is JSONArray -> for (i in 0 until node.length()) {
				collectStrings(node.opt(i), key, out)
			}
		}
	}

	private val HASHTAG = Regex("""#[\p{L}\p{M}\p{N}_-]+""")
	private val SYMBOL = Regex("""[\p{So}\u200D\uFE0E\uFE0F]""")
}
