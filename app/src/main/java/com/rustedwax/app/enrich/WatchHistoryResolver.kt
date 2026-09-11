package com.rustedwax.app.enrich

import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.youtube.identity.VideoResolutionFailure
import com.rustedwax.youtube.identity.PerformerCreditEvidence

import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.storage.YouTubeSessionVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class WatchHistoryResolver private constructor(
	private val vault: YouTubeSessionVault,
	private val health: WatchHistoryHealth,
	private val commitVaultMutations: Boolean,
) {
	constructor(vault: YouTubeSessionVault) : this(
		vault = vault,
		health = WatchHistoryHealth(),
		commitVaultMutations = true,
	)

	/**
	 * A read-equivalent resolver whose health/cache/session effects are detached.
	 *
	 * The cached feed is immutable and copied by reference. Breaker evolution and
	 * later cache replacements stay in the copy, while response cookie rotations
	 * and account-label updates are suppressed at the vault boundary.
	 */
	internal fun isolatedCopy(): WatchHistoryResolver =
		WatchHistoryResolver(
			vault = vault,
			health = health.isolatedCopy(),
			commitVaultMutations = false,
		).also { it.cached = cached }

	private data class CachedFeed(
		val entries: List<WatchHistoryParser.Entry>,
		val shorts: List<WatchHistoryParser.ShortEntry>,
		val fetchedAtMillis: Long,
	)

	@Volatile
	private var cached: CachedFeed? = null

	/** The exact reason the route is not running, or null when it is. */
	val refusedBecause: String? get() = health.refusedBecause

	/** The typed cause behind [refusedBecause]; see [WatchHistoryHealth.Refusal]. */
	val refusedAs: WatchHistoryHealth.Refusal? get() = health.refusedAs

	val hasSession: Boolean get() = vault.hasSession

	/** Sign-in, sign-out and monitoring boundaries all start the state over. */
	fun reset() {
		health.reset()
		cached = null
	}

	/**
	 * One candidate id for the track described by the frozen tuple, or the exact
	 * reason there is none. Never a verdict — the engine still runs it through
	 * [VideoIdentityCorroborator] like every other route.
	 */
	suspend fun resolveEvidence(
		title: String,
		channel: String?,
		durationSec: Long?,
		ownerHandle: String? = null,
		countsAsAccountEvidence: Boolean = true,
	): VideoResolutionAttempt = attempt(
		title,
		channel,
		durationSec,
		carriedVideoId = null,
		ownerHandle = ownerHandle,
		countsAsAccountEvidence = countsAsAccountEvidence,
	)

	fun recordShortCorroborated() = health.recordHit()

	/**
	 * Re-derive a history-routed carry authority at finalization. The id is
	 * never trusted from memory; the feed has to still name the same video.
	 */
	suspend fun revalidate(
		videoId: String,
		title: String,
		channel: String?,
		durationSec: Long?,
		countsAsAccountEvidence: Boolean = true,
	): VideoResolutionAttempt = attempt(
		title,
		channel,
		durationSec,
		carriedVideoId = videoId,
		countsAsAccountEvidence = countsAsAccountEvidence,
	)

	private suspend fun attempt(
		title: String,
		channel: String?,
		durationSec: Long?,
		carriedVideoId: String?,
		ownerHandle: String? = null,

		countsAsAccountEvidence: Boolean = true,
	): VideoResolutionAttempt {
		if (!vault.hasSession) {
			return VideoResolutionAttempt(
				refusalReason = "no YouTube account is connected for watch-history lookups",
			)
		}
		val now = System.currentTimeMillis()
		if (!health.mayRun(now, accountEvidence = countsAsAccountEvidence)) {
			return VideoResolutionAttempt(
				refusalReason = "watch history is not being used: ${health.refusedBecause}",
			)
		}

		fun match(entries: List<WatchHistoryParser.Entry>) = if (carriedVideoId == null) {
			WatchHistoryMatcher.candidate(entries, title, channel, durationSec)
		} else {
			WatchHistoryMatcher.revalidate(entries, carriedVideoId, title, channel, durationSec)
		}

		var feed = when (
			val first = recentEntries(
				now,
				allowCache = true,
				accountEvidence = countsAsAccountEvidence,
			)
		) {
			is Feed.Entries -> first
			is Feed.Unavailable -> return VideoResolutionAttempt(
				refusalReason = first.reason,
				failure = VideoResolutionFailure.TEMPORARY_FAILURE,
			)
		}
		var verdict = match(feed.entries)

		if (feed.fromCache && WatchHistoryMatcher.isAbsence(verdict)) {
			val fresh = recentEntries(
				now,
				allowCache = false,
				accountEvidence = countsAsAccountEvidence,
			)
			if (fresh is Feed.Entries) {
				feed = fresh
				verdict = match(fresh.entries)
			}
		}
		val entries = feed.entries

		return when (verdict) {
			is WatchHistoryMatcher.Verdict.Candidate -> {
				// A hit disproves the diagnosis only if it was evidence for it. A
				// browser video landing in this account's history says nothing about
				// which account the *native YouTube app* is signed into, so it may
				// not clear a pause that native playback earned — nor be counted
				// against it.
				if (countsAsAccountEvidence) health.recordHit()
				val entry = verdict.entry
				EventLog.append(
					"history",
					"resolved \"$title\" → ${entry.videoId} from watch history " +
						"(entry ${verdict.positionFromNewest} of ${entries.size}, " +
						"0 = newest)",
				)
				VideoResolutionAttempt(
					resolution = NativeStructuredMusicMatcher.withPerformerCreditEvidence(
						candidate = VideoResolution(
							videoId = entry.videoId,
							source = "watch history",
							title = entry.title,
							channel = entry.channel,
							lengthSeconds = entry.lengthSeconds,
							uniquelyResolved = true,
							historyVerified = true,
						),
						nativeTitle = title ?: entry.title.orEmpty(),
						nativeArtist = channel.orEmpty(),
						durationSec = durationSec,
						evidence = PerformerCreditEvidence.YOUTUBE_LISTING_COMPLETE_CREDIT,
					),
				)
			}

			is WatchHistoryMatcher.Verdict.Refused -> {
				// Only a plain absence from a *freshly read* feed is evidence about
				// the account. An ambiguous feed is evidence about the uploads, and
				// a stale cache is evidence about nothing at all — counting either
				// toward the "your app is on another account" diagnosis would
				// eventually stand the route down over a timing artefact.
				if (countsAsAccountEvidence && !feed.fromCache &&
					WatchHistoryMatcher.isAbsence(verdict)
				) {
					health.recordMiss(now, missKey(title, channel, durationSec))
				}
				EventLog.append("history", "refused \"$title\": ${verdict.reason}")

				if (ownerHandle != null && WatchHistoryMatcher.isAbsence(verdict)) {
					EventLog.append("history", rowReport(feed.entries, title, ownerHandle))
				}
				VideoResolutionAttempt(
					refusalReason = verdict.reason,
					failure = when (verdict.kind) {
						WatchHistoryMatcher.Verdict.RefusalKind.AMBIGUOUS ->
							VideoResolutionFailure.AMBIGUOUS
						WatchHistoryMatcher.Verdict.RefusalKind.CONTRADICTION ->
							VideoResolutionFailure.CONTRADICTION
						WatchHistoryMatcher.Verdict.RefusalKind.ABSENT,
						WatchHistoryMatcher.Verdict.RefusalKind.NO_MATCH,
						-> VideoResolutionFailure.NO_MATCH
					},
				)
			}
		}
	}

	/** What a freshly stored session actually turned out to be able to read. */
	sealed interface Probe {
		data class Working(
			val entries: Int,
			val newestTitle: String?,
			val accountLabel: String?,
		) : Probe

		data class Faulted(
			val reason: WatchHistoryParser.Reason?,
			val message: String,
		) : Probe
	}

	/**
	 * One-off check run straight after sign-in, so the user is told there and
	 * then whether the session can read anything — rather than finding out days
	 * later from an empty History tab.
	 *
	 * Deliberately bypasses the cache and the health gate: this is the question
	 * "does this session work", not "what is playing".
	 */
	suspend fun probe(): Probe {
		val page = fetchHistory()
			?: return Probe.Faulted(null, "youtube.com could not be reached")
		if (page.signedOutRedirect) {
			return Probe.Faulted(
				WatchHistoryParser.Reason.SIGNED_OUT,
				"youtube.com redirected the request to a sign-in page",
			)
		}
		val blob = WatchPageParser.extractJson(page.body, INITIAL_DATA)
			?: return Probe.Faulted(
				WatchHistoryParser.Reason.MARKUP_CHANGED,
				"the history page did not contain $INITIAL_DATA",
			)
		val label = accountLabel(page.body)
		if (commitVaultMutations) vault.rememberAccountLabel(label)
		return when (val parsed = WatchHistoryParser.parse(blob)) {
			is WatchHistoryParser.Result.Feed -> {
				cached = CachedFeed(parsed.entries, parsed.shorts, System.currentTimeMillis())
				Probe.Working(
					entries = parsed.entries.size + parsed.shorts.size,
					newestTitle = parsed.entries.firstOrNull()?.title
						?: parsed.shorts.firstOrNull()?.title,
					accountLabel = label,
				)
			}

			is WatchHistoryParser.Result.Unreadable -> {
				if (parsed.reason == WatchHistoryParser.Reason.EMPTY) {
					EventLog.append("history", shapeReport(page.body))
				}
				Probe.Faulted(parsed.reason, parsed.detail)
			}
		}
	}

	/**
	 * A display name for the connected account, best effort.
	 *
	 * Only ever used to show the user *which* account RustedWax reads, so that
	 * "the YouTube app is on a different one" is something they can see rather
	 * than deduce. Absence is fine and is not an error.
	 */
	internal fun accountLabel(html: String): String? =
		ACCOUNT_LABEL_PATTERNS.firstNotNullOfOrNull { pattern ->
			pattern.find(html)?.groupValues?.getOrNull(1)
				?.takeIf { it.isNotBlank() && it.length <= 80 }
		}

	/** Identifies the track a miss is about, so replaying one cannot count twice. */
	private fun missKey(title: String, channel: String?, durationSec: Long?): String =
		listOf(
			SearchResultsParser.titleKey(title),
			channel?.let(SearchResultsParser::channelKey).orEmpty(),
			durationSec?.toString().orEmpty(),
		).joinToString("|")

	private sealed interface Feed {
		data class Entries(
			val entries: List<WatchHistoryParser.Entry>,
			val shorts: List<WatchHistoryParser.ShortEntry>,
			/** Absence in a cached feed proves nothing; absence in a fresh one does. */
			val fromCache: Boolean,
		) : Feed

		data class Unavailable(val reason: String) : Feed
	}

	suspend fun recentShortIds(
		title: String?,
		limit: Int = MAX_SHORT_CANDIDATES,
		forceRefresh: Boolean = false,
		/**
		 * Whether this read is native playback, which the account diagnosis is
		 * about and which a corroborated outcome can disprove.
		 *
		 * A browser read is neither. It must not spend the one probe a stood-down
		 * route allows, because a browser Short can say nothing about which
		 * account the *native YouTube app* is signed into — and spending the probe
		 * would postpone the only chance native playback has to clear the pause.
		 */
		countsAsAccountEvidence: Boolean = true,
	): List<String> {
		if (!vault.hasSession) return emptyList()
		val now = System.currentTimeMillis()

		if (!health.mayRun(now, accountEvidence = countsAsAccountEvidence)) {
			EventLog.append(
				"history",
				"not offering Shorts candidates: ${health.refusedBecause}",
			)
			return emptyList()
		}
		val feed = recentEntries(
			now,
			allowCache = !forceRefresh,
			accountEvidence = countsAsAccountEvidence,
		)
		val shorts = (feed as? Feed.Entries)?.shorts ?: return emptyList()
		if (shorts.isEmpty()) return emptyList()

		if (title == null) {

			val recent = shorts.map(WatchHistoryParser.ShortEntry::videoId)
				.distinct()
				.take(MAX_UNTITLED_SHORT_CANDIDATES)
			EventLog.append(
				"history",
				"no on-screen title for this Short; offering the ${recent.size} most recent " +
					"of ${shorts.size} feed Shorts for owner-handle + duration verification",
			)
			return recent
		}
		val wanted = SearchResultsParser.titleKey(title)
		val byTitle = shortIdsMatchingTitle(shorts, title)
		if (byTitle.isNotEmpty()) {
			EventLog.append(
				"history",
				"${byTitle.size} of ${shorts.size} Shorts in the feed match the title " +
					"— offering them for owner-handle verification",
			)
			return byTitle.take(limit)
		}

		EventLog.append(
			"history",
			"no Short in the feed matched the title \"$title\"; offering the " +
				"${minOf(shorts.size, limit)} most recent of ${shorts.size} instead. " +
				"Session key=[$wanted]. Feed keys: " +
				shorts.take(SHORT_ID_DIAGNOSTIC_LIMIT).joinToString(" ") {
					"${it.videoId}=[${SearchResultsParser.titleKey(it.title)}]"
				},
		)
		return shorts.take(limit).map(WatchHistoryParser.ShortEntry::videoId)
	}

	private suspend fun recentEntries(
		nowMillis: Long,
		allowCache: Boolean,
		/**
		 * Whether the consumer that asked is health-eligible native playback. An
		 * empty feed seen by a browser lookup is not evidence about the native
		 * app's account; a *parsed* feed seen by either proves the route works.
		 */
		accountEvidence: Boolean = true,
	): Feed {
		if (allowCache) {
			cached?.takeIf { nowMillis - it.fetchedAtMillis < CACHE_MS }?.let {
				return Feed.Entries(it.entries, it.shorts, fromCache = true)
			}
		}

		val page = fetchHistory() ?: run {
			health.recordFetchFailure()
			return Feed.Unavailable("the watch-history page could not be fetched")
		}
		if (page.signedOutRedirect) {
			health.recordUnavailable(
				WatchHistoryParser.Reason.SIGNED_OUT,
				"youtube.com redirected the request to a sign-in page",
				nowMillis,
				accountEvidence = accountEvidence,
			)
			return Feed.Unavailable("watch history is not being used: ${health.refusedBecause}")
		}

		val blob = WatchPageParser.extractJson(page.body, INITIAL_DATA) ?: run {
			health.recordUnavailable(
				WatchHistoryParser.Reason.MARKUP_CHANGED,
				"$INITIAL_DATA not found in ${page.body.length} bytes",
				nowMillis,
				accountEvidence = accountEvidence,
			)
			EventLog.append(
				"history",
				"EXTRACTION FAILED — $INITIAL_DATA not found in the watch-history page " +
					"(${page.body.length} bytes). YouTube markup may have changed.",
			)
			return Feed.Unavailable("watch history is not being used: ${health.refusedBecause}")
		}

		return when (val parsed = WatchHistoryParser.parse(blob)) {
			is WatchHistoryParser.Result.Feed -> {
				// A response that fetched and parsed is the route working, whoever
				// asked for it. That is exactly what ends a declared route fault —
				// and it is deliberately not a native hit, so a browser recovering
				// the route cannot clear native mismatch evidence with it.
				health.recordRouteHealthy()
				val shorts = parsed.shorts.ifEmpty { shortsFromFilteredFeed(blob) }
				cached = CachedFeed(parsed.entries, shorts, System.currentTimeMillis())
				EventLog.append(
					"history",
					"read ${parsed.entries.size} watch-history entries and " +
						"${shorts.size} Shorts",
				)
				Feed.Entries(parsed.entries, shorts, fromCache = false)
			}

			is WatchHistoryParser.Result.Unreadable -> {
				health.recordUnavailable(
					parsed.reason,
					parsed.detail,
					nowMillis,
					accountEvidence = accountEvidence,
				)
				val reason = health.refusedBecause
					?: "watch history had no usable entries (${parsed.detail})"
				EventLog.append("history", "unusable: $reason")
				if (parsed.reason == WatchHistoryParser.Reason.EMPTY) {
					EventLog.append("history", shapeReport(page.body))
				}
				Feed.Unavailable(reason)
			}
		}
	}

	/**
	 * Counts of the renderer *names* the page contains — never its content.
	 *
	 * "Your history is empty" has two completely different causes: an account
	 * that really has watched nothing, and a page whose markup this parser no
	 * longer recognises. Playlist pages have already made exactly that move
	 * once, dropping `playlistVideoRenderer` for `lockupViewModel`, and the
	 * symptom was indistinguishable. These are key names and integers only —
	 * no title, channel, id or anything else about what was watched goes into
	 * the log.
	 */
	private fun shapeReport(html: String): String {
		fun count(needle: String) = html.split(needle).size - 1
		return "empty-feed shape check: videoRenderer=${count("\"videoRenderer\"")} " +
			"lockupViewModel=${count("\"lockupViewModel\"")} " +
			"richItemRenderer=${count("\"richItemRenderer\"")} " +
			"sectionListRenderer=${count("\"sectionListRenderer\"")} " +
			"itemSectionRenderer=${count("\"itemSectionRenderer\"")} " +
			"bytes=${html.length}. Non-zero renderer counts here mean the feed has " +
			"entries this parser did not read; all-zero means the account really " +
			"has nothing recent."
	}

	/**
	 * Which *fields* of the recent rows are present and which agree — never what
	 * they contain.
	 *
	 * Written for one open question: why a Short that has just finished playing
	 * is not matched by a feed that matches ordinary videos in under three
	 * seconds. Each of the four gates is reported separately, so the answer is
	 * read off the log rather than inferred. No title, channel or handle text is
	 * logged; video ids are, because they are public and already appear on a
	 * successful resolve.
	 */
	private fun rowReport(
		entries: List<WatchHistoryParser.Entry>,
		title: String,
		ownerHandle: String,
	): String {
		val wantTitle = SearchResultsParser.titleKey(title)
		val wantChannel = SearchResultsParser.channelKey(ownerHandle)
		val wantHandle = OwnerHandle.normalize(ownerHandle)
		val rows = entries.take(WatchHistoryMatcher.RECENT_WINDOW).mapIndexed { index, entry ->
			val flags = listOf(
				"dur=" + (entry.lengthSeconds?.toString() ?: "absent"),
				"handle=" + (entry.ownerHandle?.let {
					if (OwnerHandle.normalize(it) == wantHandle) "same" else "different"
				} ?: "absent"),
				"title=" + if (SearchResultsParser.titleKey(entry.title) == wantTitle) {
					"same"
				} else {
					"different"
				},
				"channel=" + (entry.channel?.let {
					if (SearchResultsParser.channelKey(it) == wantChannel) "same" else "different"
				} ?: "absent"),
			)
			"$index:${entry.videoId}[${flags.joinToString(" ")}]"
		}
		return "Short did not match; recent rows — ${rows.joinToString(" ")}. " +
			"The session tuple carried duration and handle $ownerHandle."
	}

	private suspend fun shortsFromFilteredFeed(
		blob: String,
	): List<WatchHistoryParser.ShortEntry> {
		val token = WatchHistoryParser.shortsFilterToken(blob) ?: return emptyList()
		val page = fetchHistory(filterToken = token) ?: return emptyList()
		if (page.signedOutRedirect) return emptyList()
		val filtered = WatchPageParser.extractJson(page.body, INITIAL_DATA) ?: return emptyList()
		val shorts = when (val parsed = WatchHistoryParser.parse(filtered)) {
			is WatchHistoryParser.Result.Feed -> parsed.shorts
			is WatchHistoryParser.Result.Unreadable -> emptyList()
		}
		// The token is YouTube's own opaque filter value and carries no account
		// data; the count is what makes this route diagnosable.
		EventLog.append("history", "Shorts filter returned ${shorts.size} Shorts")
		return shorts
	}

	private data class Page(val body: String, val signedOutRedirect: Boolean)

	/**
	 * The single place the session cookie is used.
	 *
	 * [HISTORY_URL] is a compile-time constant on youtube.com and redirects are
	 * disabled, so there is no code path on which the credential reaches another
	 * host. The host is asserted anyway — a future edit to the constant must
	 * fail closed rather than leak.
	 */
	private suspend fun fetchHistory(filterToken: String? = null): Page? {
		val cookie = vault.secretCookieHeader() ?: return null
		// `bp` is the same filter the page's own chip carries; it selects a feed,
		// never a host. Encoded because the token is base64 and may contain `=`
		// and `+`, and appended only after the host assertion below still reads
		// the constant's host.
		val url = URL(
			if (filterToken == null) {
				HISTORY_URL
			} else {
				HISTORY_URL + "?bp=" + URLEncoder.encode(filterToken, "UTF-8")
			},
		)
		if (!url.host.endsWith(".youtube.com") && url.host != "youtube.com") {
			EventLog.append("history", "refusing to attach the session to ${url.host}")
			return null
		}
		return withContext(Dispatchers.IO) {
			runCatching {
				val connection = url.openConnection() as HttpURLConnection
				try {
					connection.apply {
						requestMethod = "GET"
						connectTimeout = TIMEOUT_MS
						readTimeout = TIMEOUT_MS
						instanceFollowRedirects = false
						setRequestProperty("Cookie", cookie)
						// The desktop shell carries ytInitialData; the mobile one
						// serves a different document.
						setRequestProperty("User-Agent", VideoIdResolver.USER_AGENT)
						// Keeps YouTube's own "signed out" / "history is paused"
						// wording in the language the parser recognises.
						setRequestProperty("Accept-Language", "en-US,en;q=0.9")
					}
					val code = connection.responseCode
					if (code in 300..399) {
						return@runCatching Page("", signedOutRedirect = true)
					}
					if (commitVaultMutations) {
						rotatedCookies(connection)?.let(vault::mergeRotatedCookies)
					}
					if (code != 200) return@runCatching null
					Page(
						connection.inputStream.bufferedReader().readText(),
						signedOutRedirect = false,
					)
				} finally {
					connection.disconnect()
				}
			}.getOrNull()
		}
	}

	/** `Set-Cookie` values, name → value, ignoring attributes and deletions. */
	private fun rotatedCookies(connection: HttpURLConnection): Map<String, String>? {
		val headers = connection.headerFields["Set-Cookie"] ?: return null
		val out = LinkedHashMap<String, String>()
		for (header in headers) {
			val pair = header.substringBefore(';')
			val eq = pair.indexOf('=')
			if (eq <= 0) continue
			val name = pair.substring(0, eq).trim()
			val value = pair.substring(eq + 1).trim()
			// An expiry-in-the-past deletion carries an empty value; keeping the
			// old one would be wrong, but so would writing a blank over it, so
			// the whole session is left for the next real answer to correct.
			if (value.isEmpty() || value == "EXPIRED") continue
			out[name] = value
		}
		return out.takeIf { it.isNotEmpty() }
	}

	internal companion object {
		/** Pure title-selection seam for production-shaped history regressions. */
		internal fun shortIdsMatchingTitle(
			shorts: List<WatchHistoryParser.ShortEntry>,
			title: String,
		): List<String> {
			return shorts
				.filter { SearchResultsParser.shortTitleMatches(title, it.title) }
				.map(WatchHistoryParser.ShortEntry::videoId)
				.distinct()
		}

		/**
		 * The account's own name as the page happens to spell it. Tried in order
		 * of how specific each one is; every one of them is optional.
		 */
		private val ACCOUNT_LABEL_PATTERNS = listOf(
			Regex(""""accountName":\{"simpleText":"([^"]{1,80})""""),
			Regex(""""channelHandle":\{"simpleText":"(@[^"]{1,60})""""),
			Regex(""""CHANNEL_HANDLE":"(@[^"]{1,60})""""),
		)

		const val HISTORY_URL = "https://www.youtube.com/feed/history"
		const val INITIAL_DATA = "ytInitialData"
		const val TIMEOUT_MS = 6_000

		/**
		 * Long enough that the pre-resolution and the finalization of one track
		 * do not both pay for a page, short enough that a track which lands in
		 * the feed a few seconds after it starts is still seen.
		 */
		const val CACHE_MS = 15_000L

		/**
		 * Bounded because each candidate costs one watch-page fetch. Title
		 * selection normally leaves one or two, so this is the ceiling for the
		 * fallback rather than the usual cost; `resolveVerifiedCandidates`
		 * applies its own eight-page budget on top.
		 */
		const val MAX_SHORT_CANDIDATES = 5

		/**
		 * The window for a Short with no readable title, where recency is the
		 * only selector there is. Eight, to match the resolver's own page budget
		 * — widening past what it will fetch would only refuse later.
		 */
		const val MAX_UNTITLED_SHORT_CANDIDATES = 8

		/** Diagnostic only; one bounded log line. */
		const val SHORT_ID_DIAGNOSTIC_LIMIT = 6
	}
}
