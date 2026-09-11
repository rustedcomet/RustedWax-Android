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
			if (kotlin.math.abs(len - durationSec) > DURATION_TOLERANCE_SEC) return@filter false
			val artistPrefixed = titleNamesArtistThenSession(title, channel, c.title)
			val titleExact = titleAgrees(title, c.title)
			(titleExact || artistPrefixed) &&
				channelAgrees(c, wantChannel, channel, artistPrefixed, titleExact)
		}
	}

	private fun titleAgrees(sessionTitle: String, candidateTitle: String): Boolean =
		shortTitleMatches(sessionTitle, candidateTitle) ||
			VideoTitleMatcher.mentionSpellingOnly(sessionTitle, candidateTitle)

	/** Separators YouTube puts between an owning artist and the track. */
	private val ARTIST_TITLE_SEPARATORS = charArrayOf('-', '–', '—', '|')

	/**
	 * The candidate's own title is this session's title behind an
	 * `"<artist> - "` prefix.
	 *
	 * The ordinary shape of an artist-owned upload, and the reason a legitimate
	 * first play could not be identified at all. YouTube Music publishes
	 * `TITLE = "Solid As A Rock (Official Audio)"` with
	 * `ARTIST = "Sizzla Kalonji"`, while the upload it is playing is titled
	 * `"Sizzla Kalonji - Solid As A Rock (Official Audio)"`. Those are the same
	 * item described twice, and [titleKey] equality alone calls them different.
	 *
	 * Deliberately not a fuzzy match. Both halves must be *exact* keys: the part
	 * before the first separator must be the artist the session published, and
	 * everything after it must be the session's title. `"Sizzla Kalonji - Solid
	 * As A Rock - RMR Records Jamaica"` is refused here, because its remainder
	 * names a third party and is not the session's title — which is also why the
	 * *first* separator is the one that counts.
	 *
	 * This never widens which titles agree on its own: it is one extra shape, and
	 * a candidate still has to satisfy channel and duration independently.
	 */
	internal fun titleNamesArtistThenSession(
		sessionTitle: String,
		sessionArtist: String?,
		candidateTitle: String,
	): Boolean {
		val artist = sessionArtist?.let(::titleKey)?.takeIf { it.isNotEmpty() } ?: return false
		val wanted = titleKey(sessionTitle).takeIf { it.isNotEmpty() } ?: return false
		val cut = candidateTitle.indexOfFirst { it in ARTIST_TITLE_SEPARATORS }
		if (cut <= 0) return false
		return titleKey(candidateTitle.take(cut)) == artist &&
			titleKey(candidateTitle.substring(cut + 1)) == wanted
	}

	/**
	 * The candidate title opens with the work the player named.
	 *
	 * The exact counterpart of [titleNamesArtistThenSession], for the order
	 * YouTube publishes just as often. Both of these are real canonical titles
	 * over rows a YouTube Music shelf matched on work *and* complete credit:
	 *
	 * ```
	 * El Preso - Fruko y Sus Tesos (Video Oficial) | Discos Fuentes
	 * Gotas De Lluvia, Grupo Niche - Video Letra
	 * ```
	 *
	 * [com.rustedwax.app.detect.TitleParser.parse] cannot orient either one — the
	 * first has two separators and a channel that agrees with neither side, so it
	 * conservatively keeps the whole string as the track; the second is read as a
	 * conventional `Artist - Track` and yields `Video Letra`. That conservatism is
	 * correct for building a payload and wrong for asking "does this page name the
	 * work", because the answer is plainly yes in both.
	 *
	 * Deliberately not a substring search. The work must be the title's opening
	 * run, whole, up to a separator or a comma — so every boundary is tried rather
	 * than only the first, which is what lets a work that contains a comma of its
	 * own (`Oiga, Mire, Vea - …`) be compared entire instead of cut at its second
	 * word. A title that opens with a *different* work, or with the artist, does
	 * not match.
	 *
	 * Says nothing whatever about who performed the work. Its callers grade the
	 * performer separately and a distributor's upload still earns no credit.
	 */
	internal fun titleLeadsWithSessionWork(
		candidateTitle: String,
		sessionWork: String,
	): Boolean {
		val wanted = titleKey(sessionWork).takeIf(String::isNotEmpty) ?: return false
		val title = candidateTitle.trim()
		title.forEachIndexed { index, character ->
			if (index > 0 && (character in ARTIST_TITLE_SEPARATORS || character == ',')) {
				if (titleKey(title.take(index)) == wanted) return true
			}
		}
		return false
	}

	/**
	 * The owner names the same artist under a shorter form of the name.
	 *
	 * `"Sizzla"` owning `"Sizzla Kalonji"`'s upload, once `- Topic` and `VEVO`
	 * have been stripped by [TitleParser.cleanChannel]. Compared as whole name
	 * tokens rather than as substrings: `"Sizzla"` may own `"Sizzla Kalonji"`'s
	 * upload, but an owner cannot establish identity by adding arbitrary tokens,
	 * so `"Sizzla Gang"` may not own `"Sizzla"`'s. Owner-side additions are
	 * limited to the exact observed aliases below.
	 *
	 * **Never consulted on its own.** [channelAgrees] reaches it only where the
	 * candidate's own title already named this exact artist, which is independent
	 * evidence that the upload belongs to them. Without that licence the strict
	 * rule stands, which is what keeps a reupload under a different owner out.
	 */
	internal fun channelIsSameArtistNamedDifferently(
		sessionArtist: String?,
		candidateChannel: String?,
	): Boolean {
		val artist = artistNameTokens(sessionArtist)
		val owner = artistNameTokens(candidateChannel)
		if (artist.isEmpty() || owner.isEmpty()) return false
		return artist.containsAll(owner) || (artist to owner) in BOUNDED_OWNER_TOKEN_ALIASES
	}

	private val BOUNDED_OWNER_TOKEN_ALIASES = setOf(
		listOf("india") to listOf("la", "india"),
		listOf("fruko") to listOf("fruko", "y", "sus", "tesos"),
	)

	private fun artistNameTokens(value: String?): List<String> = value
		?.let { TitleParser.cleanChannel(it) }
		?.let(::titleKey)
		?.split(' ')
		?.filter(String::isNotBlank)
		.orEmpty()

	/**
	 * The session credited a collaboration and the upload is owned by the act at
	 * the head of that credit.
	 *
	 * `ARTIST = "Little Lion Sound & Queen Omega"` while the upload sits on
	 * `Little Lion Sound`, which is where a collaboration is normally published.
	 * [channelMatches] already reads a byline this way in the other direction — a
	 * *candidate* whose owner is a multi-act byline led by the session's artist —
	 * and this is the same reading of the same evidence, on the side the session
	 * happens to have published it.
	 *
	 * Requires the leader to be the owner *exactly*, so it recognises a byline
	 * rather than accepting any owner whose name appears somewhere in the credit.
	 * Both call sites reach it only after the title has already agreed, and the
	 * length still binds independently.
	 */
	internal fun channelLeadsSessionCollaboration(
		sessionArtist: String?,
		candidateChannel: String?,
	): Boolean {
		val leader = collaboratorLeader(sessionArtist)?.let(::channelKey) ?: return false
		val owner = channelKey(candidateChannel) ?: return false
		return owner == leader
	}

	/**
	 * An owner YouTube itself binds to the artist.
	 *
	 * `"Beenie Man - Topic"` is generated by YouTube for the rights holder's art
	 * tracks and `"capletonVEVO"` by the label's official programme; in both the
	 * channel name *is* the artist's name by construction, which is what makes
	 * either a statement about who recorded the work rather than about who
	 * uploaded a copy of it.
	 *
	 * The other markers [com.rustedwax.app.detect.TitleParser.cleanChannel]
	 * strips — `Official`, `Music`, `TV`, `Records` — are deliberately excluded:
	 * a label or a fan channel can carry them, so they say nothing about the
	 * artist. Read from the raw name, because cleaning is what removes the marker.
	 */
	internal fun ownerIsAuthoritativeArtistChannel(channel: String?): Boolean {
		val raw = channel?.trim()?.takeIf(String::isNotBlank) ?: return false
		return raw.endsWith(" - Topic", ignoreCase = true) ||
			raw.endsWith("VEVO", ignoreCase = true)
	}

	/**
	 * Two spellings of one artist's name, differing by a single character.
	 *
	 * YouTube Music published `ARTIST = "Bennie Man & Mr Vegas"` over an upload
	 * whose owner is `Beenie Man - Topic`: `bennieman` against `beenieman`, one
	 * substitution, and `Bad Man Nuh Flee` was unscrobblable for it while its
	 * title and its length both agreed exactly.
	 *
	 * Deliberately **not** a fuzzy matcher. Exactly one edit — one substitution,
	 * insertion or deletion — over keys of at least
	 * [MIN_ARTIST_SPELLING_KEY_LENGTH] characters, so a short name cannot hop to
	 * a different artist. Punctuation and spacing differences never reach it at
	 * all: [channelKey] already erases them, so `"Mr Vegas"` and `"Mr. Vegas"`
	 * are the same key and agree exactly.
	 *
	 * The session's whole credit and the lead act of a collaboration it credited
	 * are both tried, because the published byline may name several acts while
	 * the upload sits on one of their channels.
	 *
	 * **Never consulted on its own.** [channelAgrees] reaches it only where the
	 * title already matched exactly *and* the owner is authoritative, and the
	 * caller's own uniqueness rule still has to leave one survivor.
	 */
	internal fun artistNameIsOneSpellingApart(
		sessionArtist: String?,
		candidateChannel: String?,
	): Boolean {
		val owner = channelKey(candidateChannel) ?: return false
		if (owner.length < MIN_ARTIST_SPELLING_KEY_LENGTH) return false
		return listOfNotNull(
			channelKey(sessionArtist),
			collaboratorLeader(sessionArtist)?.let(::channelKey),
		).any {
			it.length >= MIN_ARTIST_SPELLING_KEY_LENGTH && differsByOneCharacter(it, owner)
		}
	}

	/** Long enough that one changed character cannot reach a different act. */
	private const val MIN_ARTIST_SPELLING_KEY_LENGTH = 6

	private fun differsByOneCharacter(first: String, second: String): Boolean = when {
		first == second -> false
		first.length == second.length -> first.indices.count { first[it] != second[it] } == 1
		first.length == second.length + 1 -> isOneDeletionOf(first, second)
		second.length == first.length + 1 -> isOneDeletionOf(second, first)
		else -> false
	}

	private fun isOneDeletionOf(longer: String, shorter: String): Boolean {
		var i = 0
		while (i < shorter.length && longer[i] == shorter[i]) i++
		return longer.removeRange(i, i + 1) == shorter
	}

	/**
	 * A card the listing cannot settle, because only its watch page names the
	 * owner authoritatively.
	 *
	 * A search byline reads `Beenie Man`; the page reads `Beenie Man - Topic`.
	 * The spelling licence requires the marker, so a near-spelling card is worth
	 * completing and is never acceptable from the listing alone — which is also
	 * the extra evidence that makes the licence safe.
	 */
	internal fun requiresWatchPageForArtistSpelling(
		candidate: Candidate,
		title: String,
		channel: String?,
	): Boolean {
		val wantChannel = channelKey(channel) ?: return false
		if (!shortTitleMatches(title, candidate.title)) return false
		if (channelMatches(candidate, wantChannel)) return false
		if (ownerIsAuthoritativeArtistChannel(candidate.channel)) return false
		return artistNameIsOneSpellingApart(channel, candidate.channel)
	}

	/**
	 * Exact owner agreement, or one of three named variations the session's own
	 * fields license: the same artist under a shorter name on an upload whose
	 * title says so ([artistPrefixed]); the lead act of a collaboration the
	 * session credited to several; or one artist name spelled two ways, which
	 * needs an exact title *and* an owner YouTube binds to the artist.
	 */
	private fun channelAgrees(
		candidate: Candidate,
		wantChannel: String,
		sessionArtist: String?,
		artistPrefixed: Boolean,
		titleExact: Boolean,
	): Boolean = channelMatches(candidate, wantChannel) ||
		(artistPrefixed && channelIsSameArtistNamedDifferently(sessionArtist, candidate.channel)) ||
		channelLeadsSessionCollaboration(sessionArtist, candidate.channel) ||
		(
			titleExact &&
				ownerIsAuthoritativeArtistChannel(candidate.channel) &&
				artistNameIsOneSpellingApart(sessionArtist, candidate.channel)
			)

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
		val artistPrefixed = titleNamesArtistThenSession(title, channel, candidate.title)
		val titleExact = titleAgrees(title, candidate.title)
		if (!titleExact && !artistPrefixed) return false
		val wantChannel = channelKey(channel) ?: return false
		val candidateChannel = channelKey(candidate.channel)
		// A near-spelling card is admitted here without the authoritative marker
		// the listing cannot show, so that its watch page — which can — is fetched.
		// Acceptance still happens in [identityMatches], against that page.
		if (candidateChannel != null &&
			!channelAgrees(candidate, wantChannel, channel, artistPrefixed, titleExact) &&
			!requiresWatchPageForArtistSpelling(candidate, title, channel)
		) return false
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
