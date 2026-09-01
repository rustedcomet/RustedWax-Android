package com.rustedwax.app.enrich

import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.youtube.identity.VideoResolutionFailure

import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.detect.TitleParser
import com.rustedwax.app.detect.VideoTitleMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Recovers a video id by searching YouTube for what the session is playing.
 *
 * The fallback for when the address bar never names the video. Field logs of
 * 2026-07-25 showed why that is common rather than exceptional: across one
 * session the bar went silent for **15, 35 and 40-minute stretches**, because
 * a playlist advancing via the History API changes nothing on screen that
 * fires an accessibility event — the toolbar is scrolled away, or the browser
 * is backgrounded, or the screen is off. Fifteen of sixty scrobbles were
 * broadcast with no `url`, and four of six tracks in one album playlist.
 *
 * Unlike the address bar this needs no event, no foreground window and no
 * screen, so it covers exactly the case the bar cannot.
 *
 * ## It fails closed
 *
 * The id is *inferred*, so [SearchResultsParser.bestMatch] normally demands the title,
 * the channel and the duration all agree before anything is returned — the
 * measured search results for one track included a same-titled, same-length
 * cover by a different artist. Modern Shorts cards omit channel and duration,
 * so those candidates are completed from their individual watch pages and the
 * same three-field rule is applied there. When the finalized MediaSession itself
 * omitted duration, exact listing title plus the canonical page's exact channel
 * may identify a single id; every same-title candidate is re-fetched and two
 * survivors still refuse. Ambiguous or unverified results stay unresolved.
 */
class VideoIdResolver {

	/** Playlist contents, fetched once per playlist and reused for every track in it. */
	private val playlistCache = java.util.concurrent.ConcurrentHashMap<String, CachedPlaylist>()

	/** Mix queues are watch-page scoped, so the seed is part of their cache key. */
	private val mixQueueCache = java.util.concurrent.ConcurrentHashMap<String, CachedPlaylist>()

	/** Bounds re-reading a playlist whose entries did not contain the track. */
	private val playlistRefresh = PlaylistRefreshThrottle()

	private data class CachedPlaylist(
		val entries: List<SearchResultsParser.Candidate>,
		val fetchedAtMillis: Long,
	)

	/**
	 * Playlist name → id, for native sessions that have a name but no URL.
	 *
	 * Cached including the misses: a name that resolves to nothing must not
	 * re-search on every track of a playlist RustedWax cannot identify.
	 */
	private val nativePlaylistIdCache = java.util.concurrent.ConcurrentHashMap<String, String>()

	/**
	 * The id of the playlist the native player named, or null.
	 *
	 * Native YouTube publishes no video id and no URL, so the playlist bar's
	 * name is the only handle on the closed candidate set that makes native
	 * identity exact. One search per playlist, then every track in it is served
	 * from [resolveEvidenceFromPlaylist] for free — which is also cheaper than
	 * the per-track search it replaces (measured field logs spent 56–75
	 * candidates on a *single* track).
	 */
	suspend fun resolveNativePlaylistId(
		playlistName: String,
		ownerName: String? = null,
		total: Int? = null,
	): String? {
		val key = playlistName.trim()
		if (key.isEmpty()) return null
		nativePlaylistIdCache[key]?.let { return it.takeIf { id -> id != NO_PLAYLIST } }

		val html = fetchUrl(PLAYLIST_SEARCH_URL + URLEncoder.encode(key, "UTF-8")) ?: run {
			// Not cached as a miss: a network failure is not evidence about the name.
			EventLog.append("resolve", "native playlist search fetch failed for \"$key\"")
			return null
		}
		val blob = WatchPageParser.extractJson(html, INITIAL_DATA) ?: run {
			EventLog.append(
				"resolve",
				"EXTRACTION FAILED — $INITIAL_DATA not found in playlist search for \"$key\" " +
					"(${html.length} bytes). YouTube markup may have changed.",
			)
			return null
		}
		val candidates = runCatching { PlaylistSearchParser.candidates(blob) }.getOrElse {
			EventLog.append("resolve", "EXTRACTION FAILED — playlist search parse: ${it.message}")
			return null
		}
		val hit = PlaylistSearchParser.match(candidates, key, ownerName, total)
		if (hit == null) {
			EventLog.append(
				"resolve",
				"native playlist \"$key\" did not resolve to exactly one public playlist " +
					"among ${candidates.size} results — using search",
			)
			nativePlaylistIdCache[key] = NO_PLAYLIST
			return null
		}
		EventLog.append(
			"resolve",
			"native playlist \"$key\" → ${hit.playlistId} (${hit.videoCount ?: "?"} videos)",
		)
		nativePlaylistIdCache[key] = hit.playlistId
		return hit.playlistId
	}

	/**
	 * Recovers a playlist from two already-verified consecutive plays.
	 *
	 * This is the screen-independent bridge for playlist autoplay. A browser in
	 * the background and native YouTube with its watch UI gone can both omit the
	 * current URL/id, and the signed-in history feed may not record the play at
	 * all. Two exact predecessor ids form a much narrower public-playlist query;
	 * every returned public list is inspected for those ids next to each other
	 * in that order. Only the rows immediately after such pairs are candidates;
	 * the current row is accepted when the ordinary title+duration rule leaves
	 * exactly one video id across every qualifying list.
	 */
	suspend fun resolveEvidenceFromAdjacentPredecessors(
		firstVideoId: String,
		secondVideoId: String,
		firstTitle: String?,
		secondTitle: String?,
		title: String,
		channel: String?,
		durationSec: Long?,
	): VideoResolution? = resolveEvidenceFromAdjacentPredecessorsAttempt(
		firstVideoId,
		secondVideoId,
		firstTitle,
		secondTitle,
		title,
		channel,
		durationSec,
	).resolution

	suspend fun resolveEvidenceFromAdjacentPredecessorsAttempt(
		firstVideoId: String,
		secondVideoId: String,
		firstTitle: String?,
		secondTitle: String?,
		title: String,
		channel: String?,
		durationSec: Long?,
	): VideoResolutionAttempt {
		if (!VIDEO_ID.matches(firstVideoId) || !VIDEO_ID.matches(secondVideoId) ||
			firstVideoId == secondVideoId || title.isBlank() || durationSec == null
		) {
			return VideoResolutionAttempt(
				refusalReason = "adjacent predecessor route did not apply",
				failure = VideoResolutionFailure.NOT_APPLICABLE,
			)
		}

		// YouTube's own playlist index does not consistently index video ids.
		// The ids remain the *only* qualification authority; predecessor titles
		// are merely a second discovery query that finds the candidate lists to
		// inspect. A title match alone is never accepted.
		val titleQuery = listOfNotNull(
			firstTitle?.takeIf { it.isNotBlank() },
			secondTitle?.takeIf { it.isNotBlank() },
		).joinToString(" ").takeIf { it.isNotBlank() }
		val queries = listOfNotNull("$firstVideoId $secondVideoId", titleQuery).distinct()
		val candidateById = LinkedHashMap<String, PlaylistSearchParser.Candidate>()
		for (query in queries) {
			val html = fetchUrl(PLAYLIST_SEARCH_URL + URLEncoder.encode(query, "UTF-8"))
			if (html == null) {
				EventLog.append("resolve", "predecessor playlist search fetch failed for $query")
				continue
			}
			val blob = WatchPageParser.extractJson(html, INITIAL_DATA)
			if (blob == null) {
				EventLog.append(
					"resolve",
					"EXTRACTION FAILED — $INITIAL_DATA not found in predecessor playlist search " +
						"for $query (${html.length} bytes)",
				)
				continue
			}
			val parsed = runCatching { PlaylistSearchParser.candidates(blob) }.getOrElse {
				EventLog.append(
					"resolve",
					"EXTRACTION FAILED — predecessor playlist search parse: ${it.message}",
				)
				emptyList()
			}
			EventLog.append(
				"resolve",
				"predecessor playlist discovery query \"$query\" → ${parsed.size} lists",
			)
			parsed.forEach { candidateById.putIfAbsent(it.playlistId, it) }
		}
		val candidates = candidateById.values.toList()
		if (candidates.isEmpty()) {
			EventLog.append("resolve", "predecessor playlist discovery returned no public lists")
			return VideoResolutionAttempt(
				refusalReason = "predecessor playlist discovery returned no public lists",
			)
		}
		if (candidates.size > MAX_PREDECESSOR_PLAYLIST_CANDIDATES) {
			EventLog.append(
				"resolve",
				"predecessor playlist search returned ${candidates.size} lists, above the bounded " +
					"$MAX_PREDECESSOR_PLAYLIST_CANDIDATES-list verification budget; refusing inference",
			)
			return VideoResolutionAttempt(
				refusalReason = "predecessor playlist candidate set exceeded the bounded verification budget",
				failure = VideoResolutionFailure.AMBIGUOUS,
			)
		}

		// A playlist page temporarily holds the HTML, extracted JSON and parsed
		// candidates together. Fetching every discovery result at once drove the
		// measured app process to its 255 MiB heap ceiling and blocked Compose in
		// full GCs. The identity rule is unchanged; only two independent page
		// verifications may occupy memory together.
		val playlistPermits = Semaphore(MAX_PREDECESSOR_PLAYLIST_CONCURRENCY)
		val containing = coroutineScope {
			candidates.map { candidate ->
				async(Dispatchers.IO) {
					playlistPermits.withPermit {
						val playlist = playlistCache[candidate.playlistId]
							?: fetchPlaylist(candidate.playlistId)
						playlist?.let { candidate.playlistId to it }
					}
				}
			}.awaitAll().filterNotNull().filter { (_, playlist) ->
				PlaylistPageParser.containsAdjacent(
					playlist.entries, firstVideoId, secondVideoId,
				)
			}.distinctBy { it.first }
		}
		if (containing.isEmpty()) {
			EventLog.append(
				"resolve",
				"adjacent predecessors $firstVideoId → $secondVideoId identified " +
					"no qualifying public playlist",
			)
			return VideoResolutionAttempt(
				refusalReason = "adjacent predecessors identified no qualifying public playlist",
			)
		}

		val matchingFollowers = containing.flatMap { (_, playlist) ->
			val followers = PlaylistPageParser.followersAfterAdjacent(
				playlist.entries, firstVideoId, secondVideoId,
			)
			PlaylistPageParser.matches(followers, title, channel, durationSec)
		}.distinctBy(SearchResultsParser.Candidate::videoId)
		if (matchingFollowers.size != 1) {
			EventLog.append(
				"resolve",
				"adjacent predecessors $firstVideoId → $secondVideoId identified " +
					"${containing.size} public playlists, but the immediate next rows left " +
					"${matchingFollowers.size} ids matching \"$title\" (${durationSec}s); " +
					"${if (matchingFollowers.isEmpty()) "none qualify" else "ambiguous, refusing inference"}",
			)
			return VideoResolutionAttempt(
				refusalReason = if (matchingFollowers.isEmpty()) {
					"no immediate playlist follower matched the finalized track"
				} else {
					"ambiguous identity — ${matchingFollowers.size} immediate playlist followers matched"
				},
				failure = if (matchingFollowers.isEmpty()) {
					VideoResolutionFailure.NO_MATCH
				} else {
					VideoResolutionFailure.AMBIGUOUS
				},
			)
		}

		val hit = matchingFollowers.single()
		EventLog.append(
			"resolve",
			"adjacent verified predecessors $firstVideoId → $secondVideoId matched " +
				"${containing.size} public playlists; their immediate next rows uniquely " +
				"resolved current upload ${hit.videoId}",
		)
		return VideoResolutionAttempt(
			resolution = VideoResolution(
				videoId = hit.videoId,
				source = "adjacent verified predecessors",
				title = hit.title,
				channel = hit.channel,
				lengthSeconds = hit.lengthSeconds,
				uniquelyResolved = true,
				collaborativeChannel = hit.collaborativeChannel,
				playlistVerified = true,
			),
		)
	}

	/**
	 * The exact entry from the playlist being played, when there is one.
	 *
	 * Tried before [resolve] because it is *exact* where search is merely
	 * plausible: for the reported playlist, search resolves "Doomed" to
	 * `CZFTfYYql4k` while the playlist actually holds `5Oc0ja19_GU` — same
	 * song, same artist, same length, different upload. One fetch then serves
	 * every remaining track in that playlist for free.
	 */
	suspend fun resolveFromPlaylist(
		playlistId: String,
		title: String,
		channel: String?,
		durationSec: Long?,
		seedVideoId: String? = null,
	): String? = resolveEvidenceFromPlaylist(
		playlistId, title, channel, durationSec, seedVideoId,
	)?.videoId

	suspend fun resolveEvidenceFromPlaylist(
		playlistId: String,
		title: String,
		channel: String?,
		durationSec: Long?,
		seedVideoId: String? = null,
	): VideoResolution? = resolveEvidenceFromPlaylistAttempt(
		playlistId, title, channel, durationSec, seedVideoId,
	).resolution

	suspend fun resolveEvidenceFromPlaylistAttempt(
		playlistId: String,
		title: String,
		channel: String?,
		durationSec: Long?,
		seedVideoId: String? = null,
	): VideoResolutionAttempt {
		if (title.isBlank() || durationSec == null) {
			return VideoResolutionAttempt(
				refusalReason = "observed playlist route lacked finalized title or duration",
				failure = VideoResolutionFailure.NOT_APPLICABLE,
			)
		}

		// A Mix has no useful `/playlist` document, but its watch page embeds the
		// actual bounded queue. That queue proves both the exact upload and that
		// the finalized item was content rather than an interstitial ad, which is
		// the missing proof for a browser backgrounded for the whole track.
		if (playlistId.startsWith("RD", ignoreCase = true)) {
			return resolveEvidenceFromMixAttempt(
				playlistId, title, channel, durationSec, seedVideoId,
			)
		}

		// Liked and Watch Later are private and still have no anonymously
		// fetchable entry set. Search remains only a plausibility fallback.
		if (PRIVATE_PLAYLIST_PREFIXES.any { playlistId.startsWith(it, ignoreCase = true) }) {
			EventLog.append("resolve", "$playlistId is a private list — using search")
			return VideoResolutionAttempt(
				refusalReason = "$playlistId is a private playlist",
				failure = VideoResolutionFailure.NOT_APPLICABLE,
			)
		}

		var cached = playlistCache[playlistId] ?: fetchPlaylist(playlistId)
			?: return VideoResolutionAttempt(
				refusalReason = "playlist $playlistId could not be fetched or parsed",
				failure = VideoResolutionFailure.TEMPORARY_FAILURE,
			)
		var matches = PlaylistPageParser.matches(cached.entries, title, channel, durationSec)

		// The cached list can simply be out of date: a song added to the playlist
		// after the first fetch would otherwise never be found again for the life
		// of the process. Bounded by [PlaylistRefreshThrottle], because the
		// ordinary miss is a track that is genuinely not in this playlist —
		// autoplay past the last entry — and must not re-read the page per track.
		if (matches.isEmpty() &&
			playlistRefresh.claim(playlistId, cached.fetchedAtMillis, System.currentTimeMillis())
		) {
			EventLog.append(
				"resolve",
				"\"$title\" not among ${cached.entries.size} cached entries — " +
					"re-reading playlist $playlistId in case it changed",
			)
			fetchPlaylist(playlistId)?.let { refreshed ->
				cached = refreshed
				matches = PlaylistPageParser.matches(refreshed.entries, title, channel, durationSec)
			}
		}

		// Exactly one, never the first of several (§7.2 rule 8). A playlist that
		// holds the same song twice cannot say which upload was played, and a
		// coin flip between them would be an unfixable wrong link on-chain.
		if (matches.size > 1) {
			EventLog.append(
				"resolve",
				"ambiguous playlist identity — ${matches.size} entries of $playlistId match " +
					"\"$title\" (${durationSec}s): ${matches.joinToString { it.videoId }}; " +
					"refusing every id",
			)
			return VideoResolutionAttempt(
				refusalReason = "ambiguous playlist identity — ${matches.size} entries of " +
					"$playlistId matched; refusing every id",
				failure = VideoResolutionFailure.AMBIGUOUS,
			)
		}
		val hit = matches.singleOrNull()
		if (hit == null) {
			// The row's byline may simply be YouTube's other name for the same
			// uploader. Decided on that entry's own page before giving up.
			val disputed = resolveDisputedByline(
				candidates = cached.entries,
				title = title,
				channel = channel,
				durationSec = durationSec,
				source = "playlist $playlistId",
				listing = "playlist $playlistId",
			)
			disputed.resolution?.let {
				return VideoResolutionAttempt(resolution = it.copy(playlistVerified = true))
			}
			if (disputed.failure == VideoResolutionFailure.AMBIGUOUS ||
				disputed.failure == VideoResolutionFailure.CONTRADICTION
			) {
				return disputed
			}
			EventLog.append(
				"resolve",
				"\"$title\" not found among ${cached.entries.size} playlist entries — trying search",
			)
			return VideoResolutionAttempt(
				refusalReason = "\"$title\" was not found in observed playlist $playlistId",
			)
		}
		EventLog.append("resolve", "resolved \"$title\" → ${hit.videoId} from playlist $playlistId")
		return VideoResolutionAttempt(
			resolution = VideoResolution(
				videoId = hit.videoId,
				source = "playlist $playlistId",
				title = hit.title,
				channel = hit.channel,
				lengthSeconds = hit.lengthSeconds,
				uniquelyResolved = true,
				collaborativeChannel = hit.collaborativeChannel,
				playlistVerified = true,
			),
		)
	}

	private suspend fun resolveEvidenceFromMixAttempt(
		playlistId: String,
		title: String,
		channel: String?,
		durationSec: Long,
		observedVideoId: String?,
	): VideoResolutionAttempt {
		// Ordinary Mix ids are `RD` + their stable seed id. Prefer that over
		// the last address-bar id: once Brave is backgrounded the bar can remain
		// pinned to an unrelated earlier page for many tracks.
		val seed = playlistId.drop(2).takeIf(VIDEO_ID::matches)
			?: observedVideoId?.takeIf(VIDEO_ID::matches)
		if (seed == null) {
			EventLog.append(
				"resolve",
				"$playlistId is a Mix but no valid watch-page seed was available — using search",
			)
			return VideoResolutionAttempt(
				refusalReason = "$playlistId had no valid Mix watch-page seed",
				failure = VideoResolutionFailure.NOT_APPLICABLE,
			)
		}
		val cacheKey = "$playlistId|$seed"
		val cached = mixQueueCache[cacheKey] ?: fetchMixQueue(playlistId, seed)
			?: return VideoResolutionAttempt(
				refusalReason = "Mix queue $playlistId could not be fetched or parsed",
				failure = VideoResolutionFailure.TEMPORARY_FAILURE,
			)
		val matches = PlaylistPageParser.matches(cached.entries, title, channel, durationSec)
		if (matches.size > 1) {
			EventLog.append(
				"resolve",
				"ambiguous Mix queue identity — ${matches.size} entries of $playlistId match " +
					"\"$title\" (${durationSec}s): ${matches.joinToString { it.videoId }}; " +
					"refusing every id",
			)
			return VideoResolutionAttempt(
				refusalReason = "ambiguous Mix queue identity — ${matches.size} entries of " +
					"$playlistId matched; refusing every id",
				failure = VideoResolutionFailure.AMBIGUOUS,
			)
		}
		matches.singleOrNull()?.let { hit ->
			EventLog.append(
				"resolve",
				"resolved \"$title\" → ${hit.videoId} from Mix queue $playlistId",
			)
			return VideoResolutionAttempt(
				resolution = VideoResolution(
					videoId = hit.videoId,
					source = "Mix queue $playlistId",
					title = hit.title,
					channel = hit.channel,
					lengthSeconds = hit.lengthSeconds,
					uniquelyResolved = true,
					playlistVerified = true,
				),
			)
		}

		val disputed = resolveDisputedByline(
			candidates = cached.entries,
			title = title,
			channel = channel,
			durationSec = durationSec,
			source = "Mix queue $playlistId",
			listing = "Mix queue $playlistId",
		)
		disputed.resolution?.let {
			return VideoResolutionAttempt(resolution = it.copy(playlistVerified = true))
		}
		if (disputed.failure == VideoResolutionFailure.AMBIGUOUS ||
			disputed.failure == VideoResolutionFailure.CONTRADICTION
		) {
			return disputed
		}

		EventLog.append(
			"resolve",
			"\"$title\" not found among ${cached.entries.size} entries in Mix queue " +
				"$playlistId — trying search",
		)
		return VideoResolutionAttempt(
			refusalReason = "\"$title\" was not found in observed Mix queue $playlistId",
		)
	}

	private suspend fun fetchMixQueue(playlistId: String, seedVideoId: String): CachedPlaylist? {
		val cacheKey = "$playlistId|$seedVideoId"
		val html = fetchUrl("$WATCH_URL$seedVideoId&list=$playlistId") ?: run {
			EventLog.append("resolve", "Mix queue fetch failed for $playlistId")
			return null
		}
		val blob = WatchPageParser.extractJson(html, INITIAL_DATA) ?: run {
			EventLog.append(
				"resolve",
				"EXTRACTION FAILED — $INITIAL_DATA not found in Mix queue $playlistId " +
					"(${html.length} bytes). YouTube markup may have changed.",
			)
			return null
		}
		val entries = runCatching { MixQueueParser.entries(blob) }.getOrElse {
			EventLog.append("resolve", "EXTRACTION FAILED — Mix queue parse: ${it.message}")
			return null
		}
		if (entries.isEmpty()) {
			EventLog.append("resolve", "Mix queue $playlistId contained no video entries")
			return null
		}
		EventLog.append("resolve", "Mix queue $playlistId → ${entries.size} entries cached")
		return CachedPlaylist(entries, System.currentTimeMillis()).also {
			mixQueueCache[cacheKey] = it
		}
	}

	/** One page read, parsed and cached with the time it was read. */
	private suspend fun fetchPlaylist(playlistId: String): CachedPlaylist? {
		val html = fetchUrl(PLAYLIST_URL + playlistId) ?: run {
			EventLog.append("resolve", "playlist fetch failed for $playlistId")
			return null
		}
		val blob = WatchPageParser.extractJson(html, INITIAL_DATA)
		if (blob == null) {
			EventLog.append(
				"resolve",
				"EXTRACTION FAILED — $INITIAL_DATA not found in playlist $playlistId " +
					"(${html.length} bytes). YouTube markup may have changed.",
			)
			return null
		}
		val parsed = runCatching { PlaylistPageParser.entries(blob) }.getOrElse {
			EventLog.append("resolve", "EXTRACTION FAILED — playlist parse: ${it.message}")
			return null
		}
		EventLog.append("resolve", "playlist $playlistId → ${parsed.size} entries cached")
		return CachedPlaylist(parsed, System.currentTimeMillis())
			.also { playlistCache[playlistId] = it }
	}

	/** Null whenever the video cannot be identified beyond doubt. */
	suspend fun resolve(title: String, channel: String?, durationSec: Long?): String? =
		resolveEvidence(title, channel, durationSec)?.videoId

	suspend fun resolveEvidence(
		title: String,
		channel: String?,
		durationSec: Long?,
	): VideoResolution? = resolveEvidenceAttempt(title, channel, durationSec).resolution

	suspend fun resolveEvidenceAttempt(
		title: String,
		channel: String?,
		durationSec: Long?,
		ownerHandle: String? = null,
		allowStructuredNativeMusic: Boolean = false,
		allowYouTubeMusicCatalog: Boolean = false,
		/**
		 * The release the player published, when it published one.
		 *
		 * Only the YouTube Music catalog route reads it, and only to tell two art
		 * tracks of one recording apart. See [resolveYouTubeMusicCatalog].
		 */
		album: String? = null,
		/**
		 * The player's own length in milliseconds, unrounded.
		 *
		 * Only the duplicate-family selection reads it, and only because
		 * `durationSec` is truncated: `168925 ms` becomes 168 and names none of
		 * `Weh Dem A Do`'s three ingests, while rounding names exactly one.
		 */
		durationMs: Long? = null,
	): VideoResolutionAttempt {
		val normalizedHandle = ownerHandle?.let(OwnerHandle::normalize)
		if (ownerHandle != null && normalizedHandle == null) {
			return VideoResolutionAttempt(refusalReason = "the foreground owner handle was malformed")
		}
		if (title.isBlank() ||
			(normalizedHandle == null && channel.isNullOrBlank()) ||
			(normalizedHandle != null && durationSec == null)
		) {
			return VideoResolutionAttempt(
				refusalReason = "the finalized fields required by this lookup route were unavailable",
			)
		}

		val seen = LinkedHashMap<String, SearchResultsParser.Candidate>()
		val queryOwner = ownerHandle ?: channel.orEmpty()
		for ((queryNumber, query) in searchQueries(title, queryOwner).withIndex()) {
			val html = fetch(query)
			if (html == null) {
				EventLog.append(
					"resolve",
					"search fetch failed for query ${queryNumber + 1} of \"$title\"",
				)
				continue
			}

			val blob = WatchPageParser.extractJson(html, INITIAL_DATA)
			if (blob == null) {
				EventLog.append(
					"resolve",
					"EXTRACTION FAILED — $INITIAL_DATA not found in search results " +
						"(${html.length} bytes). YouTube markup may have changed.",
				)
				continue
			}

			val parsed = runCatching { SearchResultsParser.candidates(blob) }
			val candidates = parsed.getOrNull()
			if (candidates == null) {
				EventLog.append(
					"resolve",
					"EXTRACTION FAILED — search parse: ${parsed.exceptionOrNull()?.message}",
				)
				continue
			}
			candidates.forEach { seen.putIfAbsent(it.videoId, it) }
			EventLog.append(
				"resolve",
				"search ${queryNumber + 1} → ${candidates.size} video/Short candidates",
			)
		}

		val allCandidates = seen.values.toList()
		if (durationSec == null) {
			return resolveWithoutDuration(allCandidates, title, channel.orEmpty())
		}
		if (normalizedHandle != null) {
			return resolveByOwnerHandle(
				candidates = allCandidates,
				title = title,
				ownerHandle = ownerHandle,
				durationSec = durationSec,
			)
		}
		val requiredChannel = channel ?: return VideoResolutionAttempt(
			refusalReason = "the finalized channel was missing",
		)
		val plausible = allCandidates.filter {
			SearchResultsParser.hasNoIdentityContradiction(it, title, requiredChannel, durationSec)
		}
		val needsCompletion = plausible.any {
			it.channel == null || it.lengthSeconds == null
		}

		// Do not let one complete ordinary card hide an incomplete Shorts card
		// with the same identity title. That would accept the ordinary upload
		// without ever learning whether the Short is the item actually played.
		// An ambiguous ordinary-search result used to end the whole resolution.
		// For YouTube Music that was the single largest cause of a fully played
		// song being dropped: an art track's recording is routinely published as
		// several catalog uploads with the same work, the same artist and lengths
		// a second or two apart, so `title+channel+duration` cannot separate them
		// and every id was refused — `Raggy Road` and `More Prophet`, both played
		// to the end, in the 2026-08-21 log. The catalog route below *can*
		// separate them, because the songs-filtered response names each row's
		// album and the player published which album it was playing. So when that
		// route is available, ambiguity is held rather than returned, and is only
		// the answer if the catalog cannot do better.
		var deferredAmbiguity: VideoResolutionAttempt? = null
		fun defer(attempt: VideoResolutionAttempt): Boolean {
			if (attempt.failure != VideoResolutionFailure.AMBIGUOUS) return false
			if (deferredAmbiguity == null) deferredAmbiguity = attempt
			return true
		}

		if (needsCompletion) {
			val completed = verifyFromWatchPages(plausible, title, requiredChannel, durationSec)
			completed.resolution?.let { return completed }
			if (defer(completed) && !allowYouTubeMusicCatalog) {
				return completed
			}
		} else {
			// Do not return after the first query: a stripped-title query can reveal
			// a second indistinguishable upload. The immutable URL is accepted only
			// if the complete recovery set still has exactly one match.
			val matches = SearchResultsParser.identityMatches(
				plausible, title, requiredChannel, durationSec,
			)
			if (matches.size > 1) {
				val reason = "ambiguous identity — ${matches.size} uploads match " +
					"title+channel+duration (${matches.joinToString { it.videoId }}); refusing every id"
				EventLog.append("resolve", reason)
				val ambiguous = VideoResolutionAttempt(
					refusalReason = reason,
					failure = VideoResolutionFailure.AMBIGUOUS,
				)
				defer(ambiguous)
				if (!allowYouTubeMusicCatalog) return ambiguous
			}
			matches.singleOrNull()?.let { match ->
					EventLog.append(
						"resolve",
						"resolved \"$title\" → ${match.videoId} by title+channel+duration",
					)
					return VideoResolutionAttempt(
						resolution = VideoResolution(
							videoId = match.videoId,
							source = "title+channel+duration search",
							title = match.title,
							channel = match.channel,
							lengthSeconds = match.lengthSeconds,
							uniquelyResolved = true,
							collaborativeChannel = match.collaborativeChannel,
						),
					)
				}
		}

		val disputed = resolveDisputedByline(
			candidates = allCandidates,
			title = title,
			channel = requiredChannel,
			durationSec = durationSec,
			source = "byline-disputed search",
			listing = "search",
		)
		disputed.resolution?.let { return disputed }
		if (defer(disputed) && !allowYouTubeMusicCatalog) {
			return disputed
		}

		if (allowStructuredNativeMusic) {
			val structured = resolveStructuredNativeMusic(
				allCandidates, title, requiredChannel, durationSec,
			)
			structured.resolution?.let { resolution ->
				EventLog.append(
					"resolve",
					"resolved \"$title\" → ${resolution.videoId} by " +
						"structured native music title+artist+duration",
				)
				return structured
			}
			if (defer(structured) && !allowYouTubeMusicCatalog) {
				EventLog.append("resolve", structured.refusalReason.orEmpty())
				return structured
			}
			if (allowYouTubeMusicCatalog) {
				val catalog = resolveYouTubeMusicCatalog(
					title, requiredChannel, album, durationSec, durationMs,
				)
				catalog.resolution?.let { resolution ->
					EventLog.append(
						"resolve",
						"resolved \"$title\" → ${resolution.videoId} by ${resolution.source}",
					)
					return catalog
				}
				EventLog.append(
					"resolve",
					"YouTube Music catalog recovery refused \"$title\": ${catalog.refusalReason}",
				)
				// An earlier route that found the work but could not choose between
				// its uploads is the more informative refusal, and the one the
				// Not-logged row should carry.
				return deferredAmbiguity ?: catalog
			}
			EventLog.append(
				"resolve",
				"structured native music recovery refused \"$title\": " +
					structured.refusalReason,
			)
			return deferredAmbiguity ?: structured
		}
		deferredAmbiguity?.let { return it }

		val reason = "no verified id for \"$title\" / \"$channel\" (${durationSec}s) " +
			"among ${seen.size} unique search candidates"
		EventLog.append("resolve", reason)
		return VideoResolutionAttempt(refusalReason = reason)
	}

	/**
	 * Exact fallback for distributor art tracks absent from ordinary YouTube
	 * search. Search only creates a bounded candidate set; canonical watch pages
	 * still have to prove one title+artist+duration match, and ambiguity refuses
	 * every id.
	 */
	private suspend fun resolveYouTubeMusicCatalog(
		title: String,
		artist: String,
		album: String?,
		durationSec: Long,
		durationMs: Long? = null,
	): VideoResolutionAttempt {
		val shell = fetchUrl(YOUTUBE_MUSIC_HOME) ?: return VideoResolutionAttempt(
			refusalReason = "YouTube Music catalog bootstrap could not be fetched",
		)
		val config = YouTubeMusicCatalogSearchParser.config(shell)
			?: return VideoResolutionAttempt(
				refusalReason = "YouTube Music catalog bootstrap fields were unreadable",
			)
		val body = JSONObject()
			.put(
				"context",
				JSONObject().put(
					"client",
					JSONObject()
						.put("clientName", "WEB_REMIX")
						.put("clientVersion", config.clientVersion)
						.put("hl", "en")
						.put("gl", "US"),
				),
			)
			// The songs filter, which is what makes each row name its album and
			// its running time. Without it the response carries neither, and two
			// art tracks of one recording stay indistinguishable.
			.put("params", YouTubeMusicCatalogSearchParser.SONGS_FILTER_PARAMS)
			.put("query", "$title $artist")
			.toString()
		val response = postYouTubeMusic(config, "search", body)
			?: return VideoResolutionAttempt(
				refusalReason = "YouTube Music catalog search could not be fetched",
			)
		val parsed = runCatching {
			YouTubeMusicCatalogSearchParser.candidates(response)
		}.getOrElse {
			return VideoResolutionAttempt(
				refusalReason = "YouTube Music catalog search response was unreadable",
			)
		}
		EventLog.append("resolve", "YouTube Music catalog → ${parsed.size} exact-id rows")
		var plausible = parsed.filter { candidate ->
			YouTubeMusicCatalogSearchParser.matches(candidate, title, artist)
		}.distinctBy(YouTubeMusicCatalogSearchParser.Candidate::videoId)

		var narrowed = narrowYouTubeMusicCatalogCandidates(plausible, album, durationSec)

		// The Songs search can index a single release while the player is playing
		// the same recording from an album. Do not turn that search omission into a
		// contradiction. The response also carries exact album browse endpoints;
		// inspect only the endpoint whose published album equals the finalized one,
		// then run the unchanged work+complete-credit+duration predicate over that
		// closed track list. Measured 2026-08-21: broad search returned the 3:48
		// `Diamonds and Gold` single, while album `Safe` contained the played 3:40
		// row `c-qLLolHJLc` against the MediaSession's 3:39.
		if (narrowed.isEmpty() && !album.isNullOrBlank()) {
			val albumBrowseIds = parsed.filter { candidate ->
				candidate.albumBrowseId != null &&
					NativeStructuredMusicMatcher.albumsAgree(candidate.album, album)
			}.mapNotNull(YouTubeMusicCatalogSearchParser.Candidate::albumBrowseId)
				.distinct()
				.take(MAX_YOUTUBE_MUSIC_ALBUM_CANDIDATES + 1)
			if (albumBrowseIds.size <= MAX_YOUTUBE_MUSIC_ALBUM_CANDIDATES) {
				val albumRows = coroutineScope {
					albumBrowseIds.map { browseId ->
						async(Dispatchers.IO) {
							val albumBody = JSONObject()
								.put(
									"context",
									JSONObject().put(
										"client",
										JSONObject()
											.put("clientName", "WEB_REMIX")
											.put("clientVersion", config.clientVersion)
											.put("hl", "en")
											.put("gl", "US"),
									),
								)
								.put("browseId", browseId)
								.toString()
							postYouTubeMusic(config, "browse", albumBody)?.let { albumJson ->
								runCatching {
									YouTubeMusicCatalogSearchParser.albumCandidates(albumJson, album)
								}.getOrDefault(emptyList())
							}.orEmpty()
						}
					}.awaitAll().flatten()
				}.distinctBy(YouTubeMusicCatalogSearchParser.Candidate::videoId)
				val albumPlausible = albumRows.filter { candidate ->
					YouTubeMusicCatalogSearchParser.matches(candidate, title, artist)
				}.distinctBy(YouTubeMusicCatalogSearchParser.Candidate::videoId)
				val albumNarrowed = narrowYouTubeMusicCatalogCandidates(
					albumPlausible, album, durationSec,
				)
				if (albumNarrowed.isNotEmpty()) {
					plausible = (plausible + albumPlausible)
						.distinctBy(YouTubeMusicCatalogSearchParser.Candidate::videoId)
					narrowed = albumNarrowed
					EventLog.append(
						"resolve",
						"YouTube Music album \"$album\" supplied ${albumNarrowed.size} exact " +
							"work+artist+duration row(s) for \"$title\" " +
							"(${albumNarrowed.joinToString { it.videoId }})",
					)
				}
			} else {
				EventLog.append(
					"resolve",
					"YouTube Music album recovery exceeded the bounded " +
						"$MAX_YOUTUBE_MUSIC_ALBUM_CANDIDATES-album budget",
				)
			}
		}
		if (plausible.isEmpty()) {
			return VideoResolutionAttempt(
				refusalReason = "no YouTube Music catalog row had the exact work and artist credit",
			)
		}
		if (narrowed.size < plausible.size) {
			EventLog.append(
				"resolve",
				"YouTube Music catalog narrowed ${plausible.size} rows to ${narrowed.size} " +
					"for \"$title\" by published album/duration " +
					"(${narrowed.joinToString { it.videoId }})",
			)
		}
		// Preserve Claude's device-proven `.ifEmpty { plausible }` recovery for
		// canonical verification. A contradicted row is still ineligible for the
		// card-backed authority below; restoring it here only lets its own page
		// prove the identity if the search card was stale or indexed another release.
		val verificationCandidates = narrowed.ifEmpty { plausible }
		if (verificationCandidates.size > MAX_WATCH_PAGE_CANDIDATES) {
			return VideoResolutionAttempt(
				refusalReason = "YouTube Music catalog candidate set exceeded the bounded " +
					"$MAX_WATCH_PAGE_CANDIDATES-page verification budget; refusing every id",
			)
		}
		val fetched = coroutineScope {
			verificationCandidates.map { candidate ->
				async(Dispatchers.IO) {
					fetchCanonicalResolution(candidate.videoId, "YouTube Music catalog search")
				}
			}.awaitAll().filterNotNull()
		}
		val verified = NativeStructuredMusicMatcher.select(fetched, title, artist, durationSec)
		val verifiedResolution = verified.resolution
		verifiedResolution?.let { resolution ->
			// Carry the catalog's complete credit with the page-verified id, so
			// the finalization corroborator can reconcile a collaboration against
			// a page that only ever names one `- Topic` channel.
			val credits = verificationCandidates
				.firstOrNull { it.videoId == resolution.videoId }?.artists
			return if (credits.isNullOrEmpty()) {
				verified
			} else {
				VideoResolutionAttempt(resolution = resolution.copy(creditedArtists = credits))
			}
		}

		// One recording that YouTube's catalog ingested twice is not two candidate
		// works, and every field the player publishes has already agreed. See
		// [NativeStructuredMusicMatcher.sameRecording] for the measured pairs and
		// for why the duration test there is exact rather than tolerant.
		// `narrowed` empty means the published fields could not be applied at all
		// and `verificationCandidates` fell back to the unnarrowed set, so the
		// collapse must not run on it.
		//
		// It does **not** additionally require an album. Requiring one excluded
		// every single, which is most of this catalogue: measured 2026-08-23,
		// `Sort Dem Out`, `Hardball`, `Question` and `From Rags to Riches` were
		// each refused as ambiguous while their two candidates were one recording
		// on one `- Topic` channel —
		//
		// ```
		// Sort Dem Out          Demarco - Topic  173 s / 174 s
		// Hardball              Masicka - Topic  169 s / 169 s
		// Question              Jamal - Topic    164 s / 164 s
		// From Rags to Riches   Teejay - Topic   197 s / 197 s
		// ```
		//
		// — and all four published no `ALBUM`. Where both sides *do* publish an
		// album and disagree, `narrowYouTubeMusicCatalogCandidates` has already
		// removed the row before this point, so that veto is unchanged.
		if (verified.failure == VideoResolutionFailure.AMBIGUOUS &&
			narrowed.isNotEmpty()
		) {
			NativeStructuredMusicMatcher.sameRecordingAmong(
				fetched, title, artist, durationSec,
				playerSeconds = durationMs?.let { Math.round(it / 1000.0) },
			)?.let { representative ->
				EventLog.append(
					"resolve",
					"YouTube Music catalog rows for \"$title\" are duplicate uploads of one " +
						"recording on ${representative.channel} (${representative.lengthSeconds}s); " +
						"taking ${representative.videoId}",
				)
				val credits = verificationCandidates
					.firstOrNull { it.videoId == representative.videoId }?.artists
				return VideoResolutionAttempt(
					resolution = representative.copy(
						source = "duplicate YouTube Music catalog uploads of one recording",
						uniquelyResolved = true,
						structuredNativeMusic = true,
						creditedArtists = credits ?: representative.creditedArtists,
					),
				)
			}
		}

		// The canonical page could not finish the job. For an art track that is
		// ordinary rather than exceptional: `www.youtube.com/watch` is not where
		// these live, and the 2026-08-21 log shows the fetch failing outright on
		// them often enough to lose whole listens. Fall back to the catalog row
		// itself — but only when that row is *complete and unique*: exactly one
		// candidate survived, and its own card named the album and the running
		// time, and both agree with what the player published.
		//
		// This is not a relaxation of the canonical-hyperlink invariant. That
		// invariant requires a verified 11-character id and refuses search-card
		// evidence "when channel or duration is absent". Here neither is absent:
		// the artist credit arrives structurally, the running time is on the card,
		// and the album is a field an ordinary search card does not even carry.
		// A page that *was* read and describes a different work is a
		// contradiction, and no amount of card agreement may overrule it. A page
		// that could not be read at all is absence. Between those sits the case
		// this whole branch exists for: the page is fine and names one
		// `- Topic` channel — `Mr. Vegas - Topic` for a recording the player
		// credits to `Walshy Fire, Lizi & Mr. Vegas` — which is agreement with
		// part of a credit the catalog row has already proven whole.
		val sole = soleOrDuplicateFamilyRow(
			narrowed, playerSeconds = durationMs?.let { Math.round(it / 1000.0) },
		)
		if (sole != null && narrowed.size > 1) {
			EventLog.append(
				"resolve",
				"YouTube Music catalog rows for \"$title\" are duplicate catalog entries of one " +
					"recording (${narrowed.joinToString { it.videoId }}); taking ${sole.videoId}",
			)
		}
		val fetchedPage = sole?.let { candidate ->
			fetched.firstOrNull { it.videoId == candidate.videoId }
		}
		cardBackedYouTubeMusicResolution(
			sole, fetchedPage, title, album, durationSec,
		)?.let { cardResolution ->
			EventLog.append(
				"resolve",
				"YouTube Music catalog row ${cardResolution.videoId} is the unique exact " +
					"work+artist+album+duration match for \"$title\"; canonical page " +
					"did not corroborate (${verified.refusalReason})",
			)
			return VideoResolutionAttempt(resolution = cardResolution)
		}
		return verified
	}

	/**
	 * Recovery when Chromium publishes title/channel but no duration after a
	 * process or listener rebuild. The search listing establishes the full title;
	 * each candidate's canonical page establishes its uploader and binds those
	 * facts to the id. A shorter canonical title may be the same VEVO upload's
	 * page rendering, but a different work or uploader still contradicts.
	 */
	private suspend fun resolveWithoutDuration(
		candidates: List<SearchResultsParser.Candidate>,
		title: String,
		channel: String,
	): VideoResolutionAttempt {
		val plausible = durationlessTitleCandidates(candidates, title)
		if (plausible.isEmpty()) {
			return VideoResolutionAttempt(
				refusalReason = "no search candidate matched the finalized title without a duration",
			)
		}
		if (plausible.size > MAX_WATCH_PAGE_CANDIDATES) {
			return VideoResolutionAttempt(
				refusalReason = "duration-less candidate set exceeded the bounded " +
					"$MAX_WATCH_PAGE_CANDIDATES-page verification budget; refusing every id",
			)
		}

		val wantedChannel = SearchResultsParser.channelKey(channel)
			?: return VideoResolutionAttempt(refusalReason = "the finalized channel was missing")
		val matches = coroutineScope {
			plausible.map { listing ->
				async(Dispatchers.IO) {
					val canonical = fetchCanonicalResolution(
						listing.videoId,
						"title+channel search; finalized duration unavailable",
					) ?: return@async null
					if (SearchResultsParser.channelKey(canonical.channel) != wantedChannel) {
						return@async null
					}
					if (VideoTitleMatcher.compare(title, canonical.title.orEmpty()) ==
						VideoTitleMatcher.Evidence.CONTRADICTION
					) return@async null
					canonical.copy(
						title = listing.title,
						channel = canonical.channel,
					)
				}
			}.awaitAll().filterNotNull().distinctBy { it.videoId }
		}
		return when (matches.size) {
			1 -> VideoResolutionAttempt(
				resolution = matches.single().copy(uniquelyResolved = true),
			).also {
				EventLog.append(
					"resolve",
					"resolved \"$title\" → ${matches.single().videoId} by unique finalized " +
						"title + canonical channel; MediaSession duration was unavailable",
				)
			}
			0 -> VideoResolutionAttempt(
				refusalReason = "no same-title candidate's canonical channel matched \"$channel\"",
			)
			else -> VideoResolutionAttempt(
				refusalReason = "ambiguous identity — ${matches.size} uploads match finalized title " +
					"and canonical channel (${matches.joinToString { it.videoId }}); refusing every id",
				failure = VideoResolutionFailure.AMBIGUOUS,
			)
		}
	}


	private suspend fun resolveStructuredNativeMusic(
		candidates: List<SearchResultsParser.Candidate>,
		title: String,
		artist: String,
		durationSec: Long,
	): VideoResolutionAttempt {
		val plausible = structuredNativeCandidates(candidates, title, artist, durationSec)
		if (plausible.size > MAX_WATCH_PAGE_CANDIDATES) {
			return VideoResolutionAttempt(
				refusalReason = "structured native music candidate set exceeded the bounded " +
					"$MAX_WATCH_PAGE_CANDIDATES-page verification budget; refusing every id",
			)
		}
		if (plausible.isEmpty()) {
			return VideoResolutionAttempt(
				refusalReason = "no search candidate had the exact structured native work " +
					"and complete artist credit",
			)
		}
		val fetched = coroutineScope {
			plausible.map { candidate ->
				async(Dispatchers.IO) {
					fetchCanonicalResolution(candidate.videoId, "structured native music search")
				}
			}.awaitAll().filterNotNull()
		}
		return NativeStructuredMusicMatcher.select(fetched, title, artist, durationSec)
	}

	internal fun structuredNativeCandidates(
		candidates: List<SearchResultsParser.Candidate>,
		title: String,
		artist: String,
		durationSec: Long,
	): List<SearchResultsParser.Candidate> = candidates.filter { candidate ->
			candidate.lengthSeconds?.let {
				abs(it - durationSec) <= DURATION_TOLERANCE_SEC
			} != false && NativeStructuredMusicMatcher.couldDescribeTrack(
				candidate.title, candidate.channel, title, artist,
			)
		}.distinctBy(SearchResultsParser.Candidate::videoId)

	/**
	 * Re-fetch one id whose uniqueness was already established while the native
	 * track was playing, then repeat the structured predicate against the frozen
	 * final snapshot. The id is never accepted from memory alone.
	 */
	suspend fun revalidatePreResolvedNativeMusic(
		videoId: String,
		title: String,
		artist: String,
		durationSec: Long,
	): VideoResolutionAttempt {
		val fetched = fetchCanonicalResolution(videoId, "pre-resolved native music revalidation")
			?: return VideoResolutionAttempt(
				refusalReason = "pre-resolved native music page could not be re-fetched",
			)
		return NativeStructuredMusicMatcher.select(
			listOf(fetched), title, artist, durationSec,
		)
	}

	/** Re-fetch candidates from the run-local cache; the cache itself is never authority. */
	suspend fun resolveVerifiedCandidates(
		videoIds: List<String>,
		title: String?,
		channel: String?,
		durationSec: Long?,
		ownerHandle: String? = null,
	): VideoResolutionAttempt {
		if (videoIds.isEmpty() ||
			(ownerHandle == null && channel.isNullOrBlank()) ||
			// Without an owner handle the title is the only other discriminator,
			// so a missing one leaves nothing to verify against.
			(ownerHandle == null && title == null) ||
			// A length is required unless an owner handle carries the check —
			// YouTube stopped rendering the Shorts seekbar 2026-08-06, so a
			// foreground Short can arrive with a handle and neither a title nor
			// a length. Uniqueness on whatever *is* present then decides, and
			// [selectOwnerHandleMatch] tightens to a single match when the handle
			// is all there is.
			(durationSec == null && ownerHandle == null)
		) {
			return VideoResolutionAttempt(refusalReason = "no run-local verified candidate")
		}
		val fetched = coroutineScope {
			videoIds.distinct().take(MAX_CACHED_CANDIDATES).map { videoId ->
				async(Dispatchers.IO) { fetchCanonicalResolution(videoId, "run-local verified candidate") }
			}.awaitAll().filterNotNull()
		}
		if (ownerHandle != null) {
			return selectOwnerHandleMatch(fetched, title, ownerHandle, durationSec)
		}
		durationSec ?: return VideoResolutionAttempt(
			refusalReason = "no run-local verified candidate",
		)
		val matches = fetched.filter { candidate ->
			val candidateTitle = candidate.title ?: return@filter false
			val evidence = VideoTitleMatcher.compare(title!!, candidateTitle)
			(evidence == VideoTitleMatcher.Evidence.EXACT ||
				evidence == VideoTitleMatcher.Evidence.STRONG_CONTAINMENT) &&
				SearchResultsParser.channelKey(channel) ==
					SearchResultsParser.channelKey(candidate.channel) &&
				candidate.lengthSeconds?.let { abs(it - durationSec) <= DURATION_TOLERANCE_SEC } == true
		}.distinctBy { it.videoId }
		return when (matches.size) {
			1 -> VideoResolutionAttempt(
				resolution = matches.single().copy(
					source = "re-fetched run-local verified candidate",
					uniquelyResolved = true,
				),
			)
			0 -> VideoResolutionAttempt(
				refusalReason = "run-local candidate was re-fetched but did not fully corroborate",
			)
			else -> {
				val reason = "ambiguous identity — ${matches.size} re-fetched run-local candidates " +
					"match (${matches.joinToString { it.videoId }}); refusing every id"
				EventLog.append("resolve", reason)
				VideoResolutionAttempt(
					refusalReason = reason,
					failure = VideoResolutionFailure.AMBIGUOUS,
				)
			}
		}
	}

	private suspend fun resolveByOwnerHandle(
		candidates: List<SearchResultsParser.Candidate>,
		title: String,
		ownerHandle: String,
		durationSec: Long,
	): VideoResolutionAttempt {
		val plausible = candidates.filter { candidate ->
			SearchResultsParser.shortTitleMatches(title, candidate.title) &&
				candidate.lengthSeconds?.let {
					abs(it - durationSec) <= DURATION_TOLERANCE_SEC
				} != false
		}.distinctBy { it.videoId }
		if (plausible.size > MAX_WATCH_PAGE_CANDIDATES) {
			return VideoResolutionAttempt(
				refusalReason = "owner-handle candidate set exceeded the bounded " +
					"$MAX_WATCH_PAGE_CANDIDATES-page verification budget; refusing every id",
			)
		}
		val fetched = coroutineScope {
			plausible.map { candidate ->
				async(Dispatchers.IO) {
					fetchCanonicalResolution(candidate.videoId, "foreground owner-handle search")
				}
			}.awaitAll().filterNotNull()
		}
		return selectOwnerHandleMatch(fetched, title, ownerHandle, durationSec)
	}

	private suspend fun verifyFromWatchPages(
		candidates: List<SearchResultsParser.Candidate>,
		title: String,
		channel: String,
		durationSec: Long,
	): VideoResolutionAttempt {
		val plausible = candidates
			// A query already contains title + channel. Eight page checks is a
			// generous recovery budget while bounding data use and latency when a
			// heavily reposted Short has dozens of near-identical cards.
			.take(MAX_WATCH_PAGE_CANDIDATES)
		if (plausible.isEmpty()) {
			return VideoResolutionAttempt(refusalReason = "no plausible watch-page candidate")
		}

		val matches = coroutineScope {
			plausible.map { candidate ->
				async(Dispatchers.IO) {
					verifyWatchPage(candidate, title, channel, durationSec)
				}
			}.awaitAll().filterNotNull().distinctBy { it.videoId }
		}

		return when (matches.size) {
			0 -> VideoResolutionAttempt(refusalReason = "no watch-page candidate fully corroborated")
			1 -> matches.single().let {
				EventLog.append(
					"resolve",
					"resolved \"$title\" → ${it.videoId} after Shorts watch-page corroboration",
				)
				VideoResolutionAttempt(resolution = it.copy(uniquelyResolved = true))
			}
			else -> {
				val reason = "ambiguous identity — ${matches.size} uploads match " +
					"title+channel+duration (${matches.joinToString { it.videoId }}); refusing every id"
				EventLog.append("resolve", reason)
				VideoResolutionAttempt(
					refusalReason = reason,
					failure = VideoResolutionFailure.AMBIGUOUS,
				)
			}
		}
	}

	/**
	 * The listing named one uploader and the session named another.
	 *
	 * A search card and a playlist row carry a *display* byline. On every VEVO
	 * upload YouTube renders it as the Official Artist Channel — "Bring Me The
	 * Horizon" — while the media session and the video's own page both name the
	 * uploading channel, "BMTHOfficialVEVO". Measured 2026-08-10 in Brave:
	 * `GBRAnuT48qo` ("Happy Song", 3:57) was the *first* search result and entry
	 * four of the playlist being played, exact title, one second off the
	 * session's length, and both routes refused it. Four listens in forty
	 * minutes went unscrobbled that way, each after six searches and 79–136
	 * candidates.
	 *
	 * [SearchResultsParser.channelKey] cannot close this and never will: it
	 * strips ownership suffixes, so `BMTHOfficialVEVO` reduces to `bmth`, and no
	 * rule turns that into `bringmethehorizon`. The names are not spellings of
	 * each other — one is the label's channel, the other is the artist page
	 * YouTube redirects it to.
	 *
	 * The video's own watch page is the authority on who uploaded it, so when
	 * the byline is the **only** field in dispute the channel is decided there
	 * instead. This adds no candidate and drops no field: the title and the
	 * duration must already agree with the listing, [verifyWatchPage] then
	 * demands title *and* channel *and* duration against the page the id points
	 * at, and exactly one candidate may survive. A cover under the same name by
	 * a genuinely different channel — the case this class was built to refuse —
	 * fails there exactly as it failed on the card.
	 */
	/**
	 * Listing rows whose *only* disagreement with the session is the owner name.
	 *
	 * Title and duration have to agree with the row and the row has to name an
	 * owner at all — a row with no byline is already handled by the completion
	 * path and is not in dispute with anything. What is left is the single field
	 * a watch page can settle.
	 */
	internal fun disputedBylineCandidates(
		candidates: List<SearchResultsParser.Candidate>,
		title: String,
		channel: String?,
		durationSec: Long?,
	): List<SearchResultsParser.Candidate> {
		if (channel.isNullOrBlank() || durationSec == null || durationSec <= 0) return emptyList()
		val wantedTitle = SearchResultsParser.titleKey(title)
		if (wantedTitle.isEmpty()) return emptyList()
		return candidates.filter { candidate ->
			SearchResultsParser.titleKey(candidate.title) == wantedTitle &&
				candidate.lengthSeconds?.let {
					abs(it - durationSec) <= DURATION_TOLERANCE_SEC
				} == true &&
				SearchResultsParser.channelKey(candidate.channel) != null &&
				!SearchResultsParser.hasNoIdentityContradiction(
					candidate, title, channel, durationSec,
				)
		}.distinctBy(SearchResultsParser.Candidate::videoId)
	}

	private suspend fun resolveDisputedByline(
		candidates: List<SearchResultsParser.Candidate>,
		title: String,
		channel: String?,
		durationSec: Long?,
		source: String,
		listing: String,
	): VideoResolutionAttempt {
		val disputed = disputedBylineCandidates(candidates, title, channel, durationSec)
		// A non-empty dispute set is itself the proof that both were present:
		// [disputedBylineCandidates] returns nothing without them.
		val wantedChannel = channel ?: return VideoResolutionAttempt(
			refusalReason = "no byline to dispute",
		)
		val wantedDuration = durationSec ?: return VideoResolutionAttempt(
			refusalReason = "no byline to dispute",
		)
		if (disputed.isEmpty()) {
			return VideoResolutionAttempt(refusalReason = "no byline to dispute")
		}
		if (disputed.size > MAX_WATCH_PAGE_CANDIDATES) {
			val reason = "$listing byline-disputed candidate set exceeded the bounded " +
				"$MAX_WATCH_PAGE_CANDIDATES-page verification budget; refusing every id"
			EventLog.append("resolve", reason)
			return VideoResolutionAttempt(refusalReason = reason)
		}

		val matches = coroutineScope {
			disputed.map { candidate ->
				async(Dispatchers.IO) {
					verifyWatchPage(candidate, title, wantedChannel, wantedDuration, source)
				}
			}.awaitAll().filterNotNull().distinctBy { it.videoId }
		}
		return when (matches.size) {
			0 -> VideoResolutionAttempt(
				refusalReason = "no byline-disputed $listing candidate was confirmed by its watch page",
			)
			1 -> matches.single().let {
				EventLog.append(
					"resolve",
					"resolved \"$title\" → ${it.videoId}: $listing lists the owner as " +
						"\"${disputed.first { c -> c.videoId == it.videoId }.channel}\" " +
						"but its watch page names \"${it.channel}\", which is the session's own " +
						"channel; title and duration agree",
				)
				VideoResolutionAttempt(resolution = it.copy(uniquelyResolved = true))
			}
			else -> {
				val reason = "ambiguous identity — ${matches.size} byline-disputed uploads match " +
					"title+channel+duration (${matches.joinToString { it.videoId }}); refusing every id"
				EventLog.append("resolve", reason)
				VideoResolutionAttempt(
					refusalReason = reason,
					failure = VideoResolutionFailure.AMBIGUOUS,
				)
			}
		}
	}

	private suspend fun verifyWatchPage(
		candidate: SearchResultsParser.Candidate,
		title: String,
		channel: String,
		durationSec: Long,
		source: String = "Shorts watch-page completion",
	): VideoResolution? {
		val resolution = fetchCanonicalResolution(candidate.videoId, source)
			?: return null
		val completed = SearchResultsParser.Candidate(
			videoId = resolution.videoId,
			title = resolution.title ?: return null,
			channel = resolution.channel ?: return null,
			lengthSeconds = resolution.lengthSeconds ?: return null,
		)
		if (!SearchResultsParser.matchesIdentity(completed, title, channel, durationSec)) return null
		return resolution.copy(collaborativeChannel = candidate.collaborativeChannel)
	}

	private suspend fun fetchCanonicalResolution(videoId: String, source: String): VideoResolution? {
		val html = fetchUrl(WATCH_URL + videoId) ?: return null
		val player = WatchPageParser.extractJson(html, PLAYER_RESPONSE) ?: return null
		val reportedId = runCatching {
			JSONObject(player).optJSONObject("videoDetails")?.optString("videoId")
		}.getOrNull()
		if (reportedId != videoId) return null

		val facts = runCatching {
			WatchPageParser.parsePlayerResponse(videoId, player)
		}.getOrNull() ?: return null
		return VideoResolution(
			videoId = videoId,
			source = source,
			title = facts.title ?: return null,
			channel = facts.author ?: return null,
			ownerHandle = facts.ownerHandle,
			lengthSeconds = facts.lengthSeconds ?: return null,
			// The same page's displayed title, which is what a native observer
			// read off the screen whenever YouTube auto-translated it.
			localizedTitle = WatchPageParser.extractJson(html, INITIAL_DATA)
				?.let(WatchPageParser::localizedTitle),
		)
	}

	/**
	 * @param title the title read off the screen, or null when the footer did not
	 * yield one unambiguously. With a null title the gate is owner handle +
	 * duration + uniqueness, which is the join the watch-history route was always
	 * really relying on: the on-screen title was never authority, only a
	 * selector. Dropping it costs one of three agreeing fields and keeps the two
	 * that YouTube cannot restyle, and the single-match rule is untouched — two
	 * uploads by the same channel at the same length still refuse.
	 */
	internal fun selectOwnerHandleMatch(
		candidates: List<VideoResolution>,
		title: String?,
		ownerHandle: String,
		durationSec: Long?,
	): VideoResolutionAttempt {
		val normalizedHandle = OwnerHandle.normalize(ownerHandle) ?: return VideoResolutionAttempt(
			refusalReason = "the foreground owner handle was malformed",
		)
		val matches = candidates.filter { candidate ->
			val candidateTitle = candidate.title ?: return@filter false
			val candidateDuration = candidate.lengthSeconds ?: return@filter false
			// Either of the video's own two titles. YouTube auto-translates for
			// the viewer, so a foreground Short's title is read off the screen in
			// the *displayed* language while `videoDetails` keeps the original —
			// measured 2026-08-05, this refused every Spanish-origin Short on an
			// English-language device. Both titles come from the one page being
			// verified, so this admits no new candidate; the duration, the exact
			// owner handle and the single-match rule are all unchanged.
			val titleAgrees = title == null ||
				SearchResultsParser.shortTitleMatches(title, candidateTitle) ||
				candidate.localizedTitle?.let {
					SearchResultsParser.shortTitleMatches(title, it)
				} == true
			// A null length is not a failed comparison: YouTube stopped rendering
			// the Shorts seekbar, so there is nothing on screen to compare with.
			// The title and the exact handle still bind, and uniqueness still
			// decides — this drops a field, it does not weaken the survivors.
			val durationAgrees = durationSec == null ||
				abs(candidateDuration - durationSec) <= DURATION_TOLERANCE_SEC
			titleAgrees && durationAgrees &&
				OwnerHandle.normalize(candidate.ownerHandle) == normalizedHandle
		}.distinctBy { it.videoId }
		return when (matches.size) {
			1 -> VideoResolutionAttempt(
				resolution = matches.single().copy(uniquelyResolved = true),
			)
			0 -> VideoResolutionAttempt(
				refusalReason = if (title == null) {
					"no candidate matched exact duration+owner handle $ownerHandle"
				} else {
					"no candidate matched exact title+duration+owner handle $ownerHandle"
				},
			)
			// Neither a title nor a length: the handle is the only evidence there
			// is, so uniqueness has to carry the whole weight and recency may not
			// break the tie. Two Shorts by one creator watched back to back are
			// indistinguishable here, and a coin flip writes a wrong id to a
			// chain nothing can edit.
			else -> if (title == null && durationSec == null) {
				VideoResolutionAttempt(
					refusalReason = "ambiguous identity — ${matches.size} recent uploads by " +
						"$ownerHandle and neither a title nor a length to separate them " +
						"(${matches.joinToString { it.videoId }}); refusing every id",
					failure = VideoResolutionFailure.AMBIGUOUS,
				)
			} else if (title == null) {
				// With no readable title there are only two fields left, and a
				// creator who posts several Shorts of the same length ties them.
				// Measured 2026-08-06: a Short opened straight into
				// picture-in-picture counted to 100% and was then thrown away
				// because @its channel had two 57-second uploads.
				//
				// Recency is the third field, and it is not a coin flip. The
				// candidates arrive in watch-history order, newest first, and the
				// Short being identified is the one playing *now* — which is the
				// newest thing in that history. Handle and duration still both
				// have to agree; recency only says which of the survivors is the
				// current one. Uniqueness is restored, not relaxed.
				val newest = matches.first()
				VideoResolutionAttempt(
					resolution = newest.copy(
						uniquelyResolved = true,
						// Carried in the source rather than logged from here, so
						// this stays free of Android types and testable. The engine
						// already prints the source on every resolve.
						source = "${newest.source}; most recently watched of " +
							"${matches.size} same-length uploads by $ownerHandle, " +
							"no readable title",
					),
				)
			} else {
				VideoResolutionAttempt(
					refusalReason = "ambiguous identity — ${matches.size} uploads match exact " +
						"title+duration+owner handle (${matches.joinToString { it.videoId }}); " +
						"refusing every id",
					failure = VideoResolutionFailure.AMBIGUOUS,
				)
			}
		}
	}

	/**
	 * The title up to its first `@` mention, when enough of it survives.
	 *
	 * A mention is the one part of a title YouTube may hand us in a form that
	 * does not exist on the video. `aZaxQG3ggng` is titled
	 * `… Shot and edited by: @spitcamuniversity`, and on 2026-08-17 its
	 * MediaSession published the *display name* `@Maggie Rudisill` instead —
	 * words that appear nowhere in the upload. Search matched them literally, so
	 * all six queries returned a page the correct video was not on, and the play
	 * was lost. Truncated at the `@`, the same title returns it first.
	 *
	 * Null unless the title actually carries a mention, so a title without one
	 * generates exactly the queries it always did, in the same order. The
	 * remainder must still be [MIN_MENTION_FREE_TOKENS] words, because a query
	 * that is only an artist name would recall the whole channel — and this adds
	 * a *query*, never a match: every candidate it surfaces still has to clear
	 * the unchanged title+channel+duration gate, and two survivors are still
	 * refused as ambiguous.
	 */
	private fun mentionFreeTitle(title: String): String? {
		val at = title.indexOf('@')
		if (at <= 0) return null
		val head = title.take(at).trim()
		val words = SearchResultsParser.titleKey(head).split(' ').filter(String::isNotBlank)
		return head.takeIf { words.size >= MIN_MENTION_FREE_TOKENS }
	}

	internal fun searchQueries(title: String, channel: String): List<String> {
		val variants = linkedSetOf<String>()
		fun addVariant(value: String?) {
			value?.trim()?.takeIf(String::isNotBlank)?.let(variants::add)
		}
		addVariant(title)
		addVariant(mentionFreeTitle(title))
		addVariant(SearchResultsParser.searchTitle(title))
		addVariant(TitleParser.presentationCore(title))
		addVariant(TitleParser.parse(title, channel).track)

		val titleWords = SearchResultsParser.titleKey(title).split(' ').filter(String::isNotBlank)
		val channelWords = SearchResultsParser.titleKey(channel).split(' ').filter(String::isNotBlank)
		if (channelWords.isNotEmpty() && titleWords.size > channelWords.size &&
			titleWords.take(channelWords.size) == channelWords
		) {
			addVariant(titleWords.drop(channelWords.size).joinToString(" "))
		}

		return linkedSetOf<String>().apply {
			variants.forEach { variant ->
				add("$variant $channel")
				add(variant)
			}
		}.take(MAX_SEARCH_QUERIES)
	}

	private suspend fun fetch(query: String): String? =
		fetchUrl(SEARCH_URL + URLEncoder.encode(query, "UTF-8"))

	private suspend fun fetchUrl(target: String): String? {
		repeat(NETWORK_ATTEMPTS) { attempt ->
			val body = withContext(Dispatchers.IO) {
				runCatching {
					val url = URL(target)
					val connection = url.openConnection() as HttpURLConnection
					try {
						connection.apply {
							requestMethod = "GET"
							connectTimeout = TIMEOUT_MS
							readTimeout = TIMEOUT_MS
							instanceFollowRedirects = true
							// The desktop shell carries ytInitialData; the mobile one
							// serves a different document.
							setRequestProperty("User-Agent", USER_AGENT)
							setRequestProperty("Accept-Language", "en-US,en;q=0.9")
						}
						connection.inputStream.bufferedReader().readText()
					} finally {
						connection.disconnect()
					}
				}.getOrNull()
			}
			if (body != null) return body
			if (attempt + 1 < NETWORK_ATTEMPTS) delay(RETRY_DELAY_MS)
		}
		return null
	}

	private suspend fun postYouTubeMusic(
		config: YouTubeMusicCatalogSearchParser.Config,
		endpoint: String,
		body: String,
	): String? {
		val target = "$YOUTUBE_MUSIC_API/$endpoint?key=${URLEncoder.encode(config.apiKey, "UTF-8")}"
		repeat(NETWORK_ATTEMPTS) { attempt ->
			val response = withContext(Dispatchers.IO) {
				runCatching {
					val connection = URL(target).openConnection() as HttpURLConnection
					try {
						connection.apply {
							requestMethod = "POST"
							connectTimeout = TIMEOUT_MS
							readTimeout = TIMEOUT_MS
							doOutput = true
							setRequestProperty("User-Agent", USER_AGENT)
							setRequestProperty("Content-Type", "application/json")
							setRequestProperty("Origin", YOUTUBE_MUSIC_HOME.removeSuffix("/"))
							setRequestProperty("X-Youtube-Client-Name", config.clientNameHeader)
							setRequestProperty("X-Youtube-Client-Version", config.clientVersion)
						}
						connection.outputStream.bufferedWriter().use { it.write(body) }
						connection.inputStream.bufferedReader().readText()
					} finally {
						connection.disconnect()
					}
				}.getOrNull()
			}
			if (response != null) return response
			if (attempt + 1 < NETWORK_ATTEMPTS) delay(RETRY_DELAY_MS)
		}
		return null
	}

	internal companion object {
		/**
		 * The narrow authority available when a Music catalog row is complete but
		 * its ordinary watch page is absent or only names one proven Topic artist.
		 * A fetched page with a different work, credit, or duration is a veto.
		 */
		/**
		 * The one catalog row to treat as the played item, or null.
		 *
		 * Usually that is simply the only row left after narrowing. But a
		 * recording that YouTube ingested twice leaves **two** rows that agree on
		 * everything, and when their pages also fail to corroborate the credit —
		 * a `- Topic` channel naming one artist of a pair — nothing downstream
		 * fires: `select` reports zero matches rather than an ambiguity, so the
		 * duplicate collapse never runs, and card authority wants exactly one row.
		 * Measured 2026-08-23, both fully played and both lost:
		 *
		 * ```
		 * Question             FcYm_6kR3Eg / k0cAgBwJmD4  Jamal - Topic   164 s both
		 * From Rags to Riches  fYAeyUXqL10 / SMIK0e4m0Po  Teejay - Topic  197 s both
		 * ```
		 *
		 * So a family that agrees on the complete credit, work, release and Music
		 * presentation type, with card durations within [DUPLICATE_ROW_SPREAD_SEC]
		 * of each other, reduces to one row. The player's own length picks it when
		 * it names exactly one; failing that the lowest id does, deterministically.
		 * A different release or Song/Video type remains an ambiguity even when the
		 * other card fields happen to agree.
		 */
		internal fun soleOrDuplicateFamilyRow(
			narrowed: List<YouTubeMusicCatalogSearchParser.Candidate>,
			playerSeconds: Long?,
		): YouTubeMusicCatalogSearchParser.Candidate? {
			narrowed.singleOrNull()?.let { return it }
			if (narrowed.size < 2) return null
			val first = narrowed.first()
			val work = SearchResultsParser.titleKey(first.title).takeIf(String::isNotEmpty)
				?: return null
			val credit = first.artists.mapNotNull(SearchResultsParser::channelKey).toSet()
				.takeIf { it.isNotEmpty() } ?: return null
			val album = first.album?.let(SearchResultsParser::titleKey)
			val musicVideoType = first.musicVideoType
			val lengths = narrowed.mapNotNull(
				YouTubeMusicCatalogSearchParser.Candidate::durationSeconds,
			)
			if (lengths.size != narrowed.size) return null
			if ((lengths.max() - lengths.min()) > DUPLICATE_ROW_SPREAD_SEC) return null
			val family = narrowed.all { row ->
				SearchResultsParser.titleKey(row.title) == work &&
					row.artists.mapNotNull(SearchResultsParser::channelKey).toSet() == credit &&
					row.album?.let(SearchResultsParser::titleKey) == album &&
					row.musicVideoType == musicVideoType
			}
			if (!family) return null
			if (playerSeconds != null) {
				narrowed.filter { it.durationSeconds == playerSeconds }
					.singleOrNull()?.let { return it }
			}
			return narrowed.minByOrNull(YouTubeMusicCatalogSearchParser.Candidate::videoId)
		}

		internal fun cardBackedYouTubeMusicResolution(
			candidate: YouTubeMusicCatalogSearchParser.Candidate?,
			fetchedPage: VideoResolution?,
			nativeTitle: String,
			nativeAlbum: String?,
			durationSec: Long,
		): VideoResolution? {
			val row = candidate ?: return null
			val cardDuration = row.durationSeconds ?: return null
			if (abs(cardDuration - durationSec) > DURATION_TOLERANCE_SEC) return null
			// An album that both sides published and that disagrees is still a
			// veto. An album that either side simply does not have is **not** —
			// `albumsAgree` treats absence as agreement, and requiring one on both
			// sides is what silently lost every single. Measured 2026-08-23: over
			// one overnight run, twelve fully played tracks were refused with "no
			// fully fetched candidate matched" while their unique catalog row was
			// sitting right there, because YouTube Music leaves `ALBUM` unset on a
			// single. The album is a disambiguator, not the evidence — the
			// evidence is the exact work, the complete structural credit, the
			// running time and the uniqueness of the row.
			if (!NativeStructuredMusicMatcher.albumsAgree(row.album, nativeAlbum)) return null
			// The page may name the artist differently — see
			// [NativeStructuredMusicMatcher.corroboratesWorkAndLength]. Agreement
			// on the credit is better and is tried first; agreement on the work
			// and the length is enough.
			if (fetchedPage != null &&
				!NativeStructuredMusicMatcher.matchesOneCredit(
					fetchedPage, nativeTitle, row.artists, durationSec,
				) &&
				!NativeStructuredMusicMatcher.corroboratesWorkAndLength(
					fetchedPage, nativeTitle, durationSec,
				)
			) return null
			return VideoResolution(
				videoId = row.videoId,
				source = "exact YouTube Music catalog work+artist+album+duration",
				title = row.title,
				// Keep the complete catalog credit. A Topic page can only name
				// one contributor, but final corroboration still needs the whole set.
				channel = row.artists.joinToString(", "),
				lengthSeconds = cardDuration,
				uniquelyResolved = true,
				structuredNativeMusic = true,
				creditedArtists = row.artists,
			)
		}

		/**
		 * Apply only source-published album and duration evidence to exact-work rows.
		 * Missing fields are absence; present disagreement is a fail-closed conflict.
		 */
		internal fun narrowYouTubeMusicCatalogCandidates(
			candidates: List<YouTubeMusicCatalogSearchParser.Candidate>,
			nativeAlbum: String?,
			durationSec: Long,
		): List<YouTubeMusicCatalogSearchParser.Candidate> = candidates.filter { candidate ->
			NativeStructuredMusicMatcher.albumsAgree(candidate.album, nativeAlbum) &&
				candidate.durationSeconds.let {
					it == null || abs(it - durationSec) <= DURATION_TOLERANCE_SEC
				}
		}.distinctBy(YouTubeMusicCatalogSearchParser.Candidate::videoId)

		/** Pure bounded-selector seam for a duration-less finalized browser item. */
		internal fun durationlessTitleCandidates(
			candidates: List<SearchResultsParser.Candidate>,
			title: String,
		): List<SearchResultsParser.Candidate> {
			return candidates.filter {
				SearchResultsParser.shortTitleMatches(title, it.title)
			}.distinctBy { it.videoId }
		}

		const val SEARCH_URL = "https://www.youtube.com/results?search_query="
		const val YOUTUBE_MUSIC_HOME = "https://music.youtube.com/"
		const val YOUTUBE_MUSIC_API = "https://music.youtube.com/youtubei/v1"

		/** `sp=EgIQAw%3D%3D` is YouTube's "playlists only" search filter. */
		const val PLAYLIST_SEARCH_URL =
			"https://www.youtube.com/results?sp=EgIQAw%3D%3D&search_query="

		/**
		 * Sentinel for "this name resolved to nothing", so misses cache too.
		 *
		 * `U+FFFF` is a permanent noncharacter, so no playlist id can collide
		 * with it. It used to be a literal NUL, which made every `grep` in this
		 * repository treat the whole file as binary and skip it in silence —
		 * 2026-08-06, that cost a session an hour of concluding that
		 * `resolveVerifiedCandidates` did not exist.
		 */
		const val NO_PLAYLIST = "￿none"
		const val PLAYLIST_URL = "https://www.youtube.com/playlist?list="
		const val WATCH_URL = "https://www.youtube.com/watch?v="

		/** Private Liked / Watch Later lists. Mixes have a watch-page queue route. */
		val PRIVATE_PLAYLIST_PREFIXES = listOf("LL", "WL")
		val VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")
		const val INITIAL_DATA = "ytInitialData"
		const val PLAYER_RESPONSE = "ytInitialPlayerResponse"
		const val TIMEOUT_MS = 5_000
		const val NETWORK_ATTEMPTS = 2
		const val RETRY_DELAY_MS = 500L
		const val MAX_WATCH_PAGE_CANDIDATES = 8
		const val MAX_YOUTUBE_MUSIC_ALBUM_CANDIDATES = 4
		const val MAX_CACHED_CANDIDATES = 8
		const val MAX_SEARCH_QUERIES = 6

		/** A mention-free query shorter than this would recall a channel, not a work. */
		private const val MIN_MENTION_FREE_TOKENS = 3
		/**
		 * A fallback budget, not an identity relaxation. Above this, public search
		 * is too broad to justify loading every full playlist into a phone process,
		 * so the route fails closed and the ordinary exact routes continue.
		 */
		const val MAX_PREDECESSOR_PLAYLIST_CANDIDATES = 8
		const val MAX_PREDECESSOR_PLAYLIST_CONCURRENCY = 2
		const val DURATION_TOLERANCE_SEC = 5L

		/** Widest spread across one recording's duplicate catalog rows. */
		const val DUPLICATE_ROW_SPREAD_SEC = 5L
		const val USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
				"(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
	}
}
