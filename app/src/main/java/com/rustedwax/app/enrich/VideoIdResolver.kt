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
