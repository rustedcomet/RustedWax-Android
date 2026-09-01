package com.rustedwax.app.replay

import com.rustedwax.core.Clock
import com.rustedwax.core.AutomaticWriteAuthorization
import com.rustedwax.app.enrich.MetadataResolver
import com.rustedwax.app.enrich.MusicBrainzVerifier
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.app.enrich.WatchHistoryHealth
import com.rustedwax.app.enrich.WatchHistoryParser
import com.rustedwax.app.enrich.WatchHistoryResolver
import com.rustedwax.hive.HiveKey
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PrivateScrobble
import com.rustedwax.app.scrobble.AccountSessionStore
import com.rustedwax.app.scrobble.BroadcastQueue
import com.rustedwax.app.scrobble.DedupClaims
import com.rustedwax.app.scrobble.EnginePorts
import com.rustedwax.app.scrobble.FactsStore
import com.rustedwax.app.scrobble.MuteList
import com.rustedwax.app.scrobble.MusicVerifier
import com.rustedwax.app.scrobble.PayloadBroadcaster
import com.rustedwax.app.scrobble.PostingIdentity
import com.rustedwax.app.scrobble.RetryQueue
import com.rustedwax.app.scrobble.ScrobblePolicy
import com.rustedwax.app.scrobble.VideoIdentitySource
import com.rustedwax.app.scrobble.WatchHistorySource
import com.rustedwax.app.scrobble.HistoryRetryDelay
import com.rustedwax.app.scrobble.IdentityResolverMode
import com.rustedwax.app.storage.KeyVault
import com.rustedwax.app.storage.YouTubeSessionVault
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The scripted side of the replay harness — one fake per [EnginePorts] port.
 *
 * Every fake here answers from a script and records what it was asked. None of
 * them decide anything: a fake that applied a rule would be a second
 * implementation of that rule, and the whole value of replaying against the
 * real `FinalizationRuntime` is that there is only one.
 *
 * ## The one property worth stating twice
 *
 * [RecordingBroadcaster] is the *only* way a scrobble can leave this harness,
 * and it never touches a network. `<redacted-private-path>`'s acceptance gates
 * require that shadow-mode comparison never broadcasts; that is checkable here
 * and nowhere else, because this is the seam where "would have sent" and "sent"
 * stop being the same thing.
 */

// ---- clock ------------------------------------------------------------------

/**
 * Time the scenario advances by hand.
 *
 * Wall-clock reads inside the engine land in `atEpochSec` on the records the UI
 * shows, so a replay that could not pin them would either assert nothing about
 * them or assert something that fails at midnight.
 */
class ReplayClock(startMillis: Long = DEFAULT_START_MILLIS) : Clock {
	var nowMillis: Long = startMillis
		private set

	override fun nowMillis(): Long = nowMillis

	fun advance(millis: Long) {
		require(millis >= 0) { "a replay cannot run time backwards" }
		nowMillis += millis
	}

	/**
	 * Restore an exact scenario start when two passes must represent the same
	 * frozen listen, not merely the same event shapes at different times.
	 *
	 * Shadow/live non-suppression is specifically a dedup assertion, and the
	 * production dedup key includes the frozen start. Reusing an already-advanced
	 * clock would give the live pass a different key and let the test pass even if
	 * the shadow path had retained the first one.
	 */
	fun restore(millis: Long = DEFAULT_START_MILLIS) {
		nowMillis = millis
	}

	companion object {
		/** 2026-08-12T00:00:00Z — the audit date, so traces read in context. */
		const val DEFAULT_START_MILLIS = 1_786_233_600_000L
	}
}

// ---- policy -----------------------------------------------------------------

/** The user's switches, all set explicitly so no scenario depends on a default. */
class ReplayPolicy(
	override val monitoringEnabled: Boolean = true,
	autoScrobble: Boolean = true,
	override val enrichment: Boolean = true,
	override val scrobbleThreshold: Double = 0.6,
	override val disableShorts: Boolean = false,
	override var watchHistory: Boolean = true,
	private val privateCategories: Set<PrivateScrobble.Category> = emptySet(),
) : ScrobblePolicy {
	/**
	 * Deterministic concurrency seams around the production transport commit.
	 *
	 * Production has no hooks; these are the replay equivalent of stopping a
	 * thread immediately before or after the one linearization point.
	 */
	var beforeAutomaticTransportCommit: (() -> Unit)? = null
	var afterAutomaticTransportCommit: ((Boolean) -> Unit)? = null
	private val transportCommitAttempts = AtomicInteger(0)
	val automaticTransportCommitAttempts: Int get() = transportCommitAttempts.get()

	private val automaticAuthorization = AtomicReference(
		AutomaticWriteAuthorization(0, autoScrobble),
	)
	override var autoScrobble: Boolean = autoScrobble
		set(value) {
			synchronized(this) {
				automaticAuthorization.updateAndGet { current ->
					if (current.enabledAtStart == value) current else AutomaticWriteAuthorization(
						generation = current.generation + 1,
						enabledAtStart = value,
					)
				}
				field = value
			}
		}
	override val automaticWriteAuthorization: AutomaticWriteAuthorization
		get() = automaticAuthorization.get()
	override fun tryCommitAutomaticWrite(stamp: AutomaticWriteAuthorization): Boolean {
		transportCommitAttempts.incrementAndGet()
		beforeAutomaticTransportCommit?.invoke()
		val committed = synchronized(this) {
			autoScrobble && stamp.enabledAtStart && stamp == automaticAuthorization.get()
		}
		afterAutomaticTransportCommit?.invoke(committed)
		return committed
	}
	override fun privacyEnabledFor(category: PrivateScrobble.Category): Boolean =
		category in privateCategories
}

// ---- dedup ------------------------------------------------------------------

/**
 * The ledger, in memory, remembering the attempts as well as the claims.
 *
 * [duplicateAttempts] is the interesting half. A duplicate scrobble is not
 * visible in the broadcast list — by definition the second one never gets
 * there — so a replay that only watched the broadcaster could not tell "the
 * ledger stopped it" from "it never happened". After a MediaSession
 * recreation those are exactly the two answers that need separating.
 */
class ReplayDedupClaims : DedupClaims {
	private val held = linkedSetOf<String>()
	val claimed: List<String> get() = held.toList()
	val duplicateAttempts = mutableListOf<String>()
	var pruned = 0
		private set

	override fun claim(key: String): Boolean {
		if (!held.add(key)) {
			duplicateAttempts += key
			return false
		}
		return true
	}

	/**
	 * Asked, not taken.
	 *
	 * Deliberately not recorded in [duplicateAttempts]: that list is "a claim was
	 * refused", which is a live-path event. A shadow run asking whether the key is
	 * held has not attempted anything, and counting it would make the ledger's own
	 * assertions report shadow activity as real.
	 */
	override fun holds(key: String): Boolean = key in held

	override fun release(key: String) {
		held.remove(key)
	}

	override fun prune() {
		pruned++
	}
}


// ---- retry queue ------------------------------------------------------------

/** [BroadcastQueue] without a file behind it. Same entry type, same outcomes. */
class ReplayRetryQueue(private val clock: Clock) : RetryQueue {
	private val entries = mutableListOf<BroadcastQueue.Entry>()
	private var nextId = 1L

	/** Set to make persistence fail, for the "queue storage failure" branch. */
	var storageWorks: Boolean = true

	val all: List<BroadcastQueue.Entry> get() = entries.toList()

	override fun size(): Int = entries.size

	override fun due(): List<BroadcastQueue.Entry> =
		entries.filter { it.nextAttemptAtMs <= clock.nowMillis() }

	override fun add(
		username: String,
		json: String,
		label: String,
		percentPlayed: Int?,
		videoId: String?,
	): Boolean {
		if (!storageWorks) return false
		entries += BroadcastQueue.Entry(
			id = nextId++,
			username = username,
			json = json,
			label = label,
			percentPlayed = percentPlayed,
			videoId = videoId,
			attempts = 0,
			lastError = null,
			nextAttemptAtMs = clock.nowMillis(),
		)
		return true
	}

	override fun remove(id: Long): Boolean = entries.removeAll { it.id == id }

	override fun recordFailure(id: Long, error: String): BroadcastQueue.FailureOutcome {
		if (!storageWorks) return BroadcastQueue.FailureOutcome.STORAGE_ERROR
		val index = entries.indexOfFirst { it.id == id }
		if (index < 0) return BroadcastQueue.FailureOutcome.NOT_FOUND
		val entry = entries[index]
		val attempts = entry.attempts + 1
		if (attempts >= MAX_ATTEMPTS) {
			entries.removeAt(index)
			return BroadcastQueue.FailureOutcome.DROPPED
		}
		entries[index] = entry.copy(
			attempts = attempts,
			lastError = error,
			nextAttemptAtMs = clock.nowMillis(),
		)
		return BroadcastQueue.FailureOutcome.RETAINED
	}

	private companion object {
		/** Matches `BroadcastQueue`'s own limit; the engine's message quotes 8. */
		const val MAX_ATTEMPTS = 8
	}
}

// ---- posting identity -------------------------------------------------------

/**
 * A real key, from the vector suite's own WIF, held only in memory.
 *
 * Real rather than a stub so the replay crosses the same key-loading boundary
 * as production. Transaction construction and signing occur inside the real
 * `HiveBroadcaster`, behind this harness's broadcaster seam; their golden
 * coverage remains in the Hive vector tests.
 */
class ReplayPostingIdentity(
	username: String = "rustedwax-replay",
	private val wif: String? = TEST_WIF,
) : PostingIdentity {
	var loadKeyCalls: Int = 0
		private set

	override val account: KeyVault.Account? =
		if (wif == null) null else KeyVault.Account(username, "STM-replay")

	override fun loadKey(): HiveKey? {
		loadKeyCalls++
		return wif?.let(HiveKey::fromWif)
	}

	override fun privacySecret(): ByteArray? = ByteArray(32) { it.toByte() }

	companion object {
		/** The same WIF `HiveVectorsTest` signs its golden vectors with. */
		const val TEST_WIF = "5JEofkGSyRCqNe298aiQqiLwHgXYaPBKXe1oeaepituuwofqipA"
	}
}

// ---- broadcaster ------------------------------------------------------------

/**
 * Records what would have gone on chain, and answers with a scripted result.
 *
 * The recorded value is the serialized JSON rather than the payload object on
 * purpose: that string is what gets signed, so asserting on it is asserting on
 * the artifact itself instead of on an intention that a later step could still
 * mangle.
 */
class RecordingBroadcaster(
	private val results: MutableList<HiveRpc.BroadcastResult> = mutableListOf(),
) : PayloadBroadcaster {

	data class Sent(val username: String, val json: String)

	val sent = mutableListOf<Sent>()

	/** Result for every call once [results] is exhausted. */
	var defaultResult: HiveRpc.BroadcastResult =
		HiveRpc.BroadcastResult.Success("tx-replay", "replay-node", HiveRpc.BroadcastResult.Evidence.BLOCK)

	/**
	 * Throw instead of answering.
	 *
	 * A dispatch fault is a real shape — a signing library, a JSON encoder, an
	 * OOM — and it happens *after* the engine has already ruled the listen
	 * eligible. What that must not do is retroactively turn the listen into a
	 * refusal, so the one-outcome gate needs a way to produce it.
	 */
	var throwOnBroadcast: Throwable? = null

	fun willReturn(vararg scripted: HiveRpc.BroadcastResult) {
		results += scripted
	}

	override fun broadcastJson(
		username: String,
		key: HiveKey,
		payloadJson: String,
	): HiveRpc.BroadcastResult {
		throwOnBroadcast?.let { throw it }
		sent += Sent(username, payloadJson)
		val next = if (results.isEmpty()) defaultResult else results.removeAt(0)
		return when (next) {
			// Give each transaction its own id so a duplicate is visible as two
			// ids rather than hidden behind one repeated constant.
			is HiveRpc.BroadcastResult.Success -> next.copy(txId = "${next.txId}-${sent.size}")
			else -> next
		}
	}
}

// ---- mutes ------------------------------------------------------------------

class ReplayMuteList(muted: Map<String, String> = emptyMap()) : MuteList {
	private val entries = muted.toMutableMap()

	/**
	 * Throw instead of answering.
	 *
	 * The mute check is a disk read inside the asynchronous half, past identity
	 * and past the contract, and nothing wraps it. It stands here for the whole
	 * class of unguarded faults that used to end a finalization having reported
	 * no outcome at all.
	 */
	var throwOnQuery: Throwable? = null

	override fun isMuted(videoId: String): Boolean {
		throwOnQuery?.let { throw it }
		return entries.containsKey(videoId)
	}
	override fun mute(videoId: String, label: String) {
		entries[videoId] = label
	}

	override fun unmute(videoId: String) {
		entries.remove(videoId)
	}

	override fun all(): Map<String, String> = entries.toMap()
}

// ---- enrichment -------------------------------------------------------------

/**
 * Watch-page facts, from a script keyed by video id.
 *
 * [failures] is the temporary-lookup-failure lever: an id listed there throws,
 * which is what a timeout looks like from the engine's side. That distinction
 * matters — `ScrobbleRules` treats "the page didn't resolve" as a reason to
 * hold a Short to the full 30-second floor, and the audit's matrix asks for
 * exactly that case.
 */
class ReplayFacts(
	private val facts: MutableMap<String, VideoFacts> = mutableMapOf(),
) : MetadataResolver, FactsStore {

	val failures = mutableSetOf<String>()
	val resolved = mutableListOf<String>()

	fun put(vararg entries: VideoFacts) {
		entries.forEach { facts[it.videoId] = it }
	}

	override suspend fun resolve(videoId: String): VideoFacts? {
		resolved += videoId
		if (videoId in failures) throw java.io.IOException("replayed lookup failure for $videoId")
		return facts[videoId]
	}

	override fun get(videoId: String): VideoFacts? = facts[videoId]
}

/** MusicBrainz, answering from a script. Never found unless a scenario says so. */
class ReplayMusicVerifier(
	private val matches: MutableMap<Pair<String, String>, MusicBrainzVerifier.Match> = mutableMapOf(),
) : MusicVerifier {
	val asked = mutableListOf<Pair<String, String>>()

	fun found(artist: String, track: String, canonicalArtist: String, canonicalTitle: String) {
		matches[artist to track] =
			MusicBrainzVerifier.Match(found = true, artist = canonicalArtist, title = canonicalTitle)
	}

	override fun cached(artist: String, track: String): MusicBrainzVerifier.Match? =
		matches[artist to track]

	/** Throw instead of answering — see [RecordingBroadcaster.throwOnBroadcast]. */
	var throwOnVerify: Throwable? = null

	override suspend fun verify(artist: String, track: String): MusicBrainzVerifier.Match? {
		asked += artist to track
		throwOnVerify?.let { throw it }
		return matches[artist to track]
	}
}

// ---- identity routes --------------------------------------------------------

/**
 * The resolver chain, scripted per route.
 *
 * Each route is a lambda so a scenario can express "this route declines and the
 * next one answers", which is the shape `<redacted-private-path>` §3 says the
 * production types cannot currently express. Recording [calls] is how a replay
 * asserts *which* route answered — the audit's "identity route" output — rather
 * than only that some route did.
 */
class ReplayIdentitySource : VideoIdentitySource {
	data class SearchRequest(
		val title: String,
		val channel: String?,
		val durationSec: Long?,
		val allowStructuredNativeMusic: Boolean,
		val allowYouTubeMusicCatalog: Boolean,
		/** The release the engine handed the resolver, or null when it had none. */
		val album: String? = null,
	)

	enum class Route {
		NATIVE_PLAYLIST_ID,
		PLAYLIST,
		ADJACENT_PREDECESSORS,
		SEARCH,
		VERIFIED_CANDIDATES,
		STRUCTURED_MUSIC_REVALIDATION,
	}

	val calls = mutableListOf<Route>()
	val searchRequests = mutableListOf<SearchRequest>()

	var nativePlaylistId: (String) -> String? = { null }
	var fromPlaylist: (String, String) -> VideoResolution? = { _, _ -> null }
	var fromPredecessors: (String, String) -> VideoResolution? = { _, _ -> null }
	var playlistAttempt: ((String, String) -> VideoResolutionAttempt)? = null
	var predecessorAttempt: ((String, String) -> VideoResolutionAttempt)? = null
	var search: (String) -> VideoResolutionAttempt =
		{ VideoResolutionAttempt(refusalReason = "no candidate corroborated \"$it\"") }
	var verifiedCandidates: (List<String>) -> VideoResolutionAttempt =
		{ VideoResolutionAttempt(refusalReason = "candidates did not corroborate") }
	var structuredMusic: (String) -> VideoResolutionAttempt =
		{ VideoResolutionAttempt(refusalReason = "structured music revalidation declined") }

	override suspend fun resolveNativePlaylistId(
		playlistName: String,
		ownerName: String?,
		total: Int?,
	): String? {
		calls += Route.NATIVE_PLAYLIST_ID
		return nativePlaylistId(playlistName)
	}

	override suspend fun resolveEvidenceFromPlaylist(
		playlistId: String,
		title: String,
		channel: String?,
		durationSec: Long?,
		seedVideoId: String?,
	): VideoResolution? {
		calls += Route.PLAYLIST
		return fromPlaylist(playlistId, title)
	}

	override suspend fun resolveEvidenceFromPlaylistAttempt(
		playlistId: String,
		title: String,
		channel: String?,
		durationSec: Long?,
		seedVideoId: String?,
	): VideoResolutionAttempt {
		calls += Route.PLAYLIST
		return playlistAttempt?.invoke(playlistId, title)
			?: fromPlaylist(playlistId, title)?.let { VideoResolutionAttempt(resolution = it) }
			?: VideoResolutionAttempt(refusalReason = "playlist did not resolve")
	}

	override suspend fun resolveEvidenceFromAdjacentPredecessors(
		firstVideoId: String,
		secondVideoId: String,
		firstTitle: String?,
		secondTitle: String?,
		title: String,
		channel: String?,
		durationSec: Long?,
	): VideoResolution? {
		calls += Route.ADJACENT_PREDECESSORS
		return fromPredecessors(secondVideoId, title)
	}

	override suspend fun resolveEvidenceFromAdjacentPredecessorsAttempt(
		firstVideoId: String,
		secondVideoId: String,
		firstTitle: String?,
		secondTitle: String?,
		title: String,
		channel: String?,
		durationSec: Long?,
	): VideoResolutionAttempt {
		calls += Route.ADJACENT_PREDECESSORS
		return predecessorAttempt?.invoke(secondVideoId, title)
			?: fromPredecessors(secondVideoId, title)?.let { VideoResolutionAttempt(resolution = it) }
			?: VideoResolutionAttempt(refusalReason = "consecutive playlist did not resolve")
	}

	override suspend fun resolveEvidenceAttempt(
		title: String,
		channel: String?,
		durationSec: Long?,
		ownerHandle: String?,
		allowStructuredNativeMusic: Boolean,
		allowYouTubeMusicCatalog: Boolean,
		album: String?,
		durationMs: Long?,
	): VideoResolutionAttempt {
		calls += Route.SEARCH
		searchRequests += SearchRequest(
			title,
			channel,
			durationSec,
			allowStructuredNativeMusic,
			allowYouTubeMusicCatalog,
			album,
		)
		return search(title)
	}

	override suspend fun resolveVerifiedCandidates(
		videoIds: List<String>,
		title: String?,
		channel: String?,
		durationSec: Long?,
		ownerHandle: String?,
	): VideoResolutionAttempt {
		calls += Route.VERIFIED_CANDIDATES
		return verifiedCandidates(videoIds)
	}

	override suspend fun revalidatePreResolvedNativeMusic(
		videoId: String,
		title: String,
		artist: String,
		durationSec: Long,
	): VideoResolutionAttempt {
		calls += Route.STRUCTURED_MUSIC_REVALIDATION
		return structuredMusic(videoId)
	}
}

/** Watch history, scripted — including the "not connected at all" state. */
class ReplayWatchHistory(
	override var hasSession: Boolean = false,
	private val healthState: WatchHistoryHealth = WatchHistoryHealth(),
) : WatchHistorySource {

	var resets = 0
		private set

	/**
	 * The real [WatchHistoryHealth], so a replay proves the production diagnosis
	 * rather than a model of it: what the engine routes here is what counts.
	 */
	val health = healthState

	override val refusedBecause: String? get() = health.refusedBecause
	override val refusedAs: com.rustedwax.app.enrich.WatchHistoryHealth.Refusal?
		get() = health.refusedAs

	/** Wall clock for the health gate; a scenario can advance it to reach a probe. */
	var nowMillis: () -> Long = { 0L }

	/** Every ordinary-entry lookup, and whether it was offered as account evidence. */
	val evidenceQueries = mutableListOf<Pair<String, Boolean>>()
	var shortCorroborations = 0
		private set
	val shortIdQueries = mutableListOf<String?>()
	val shortIdForceRefreshes = mutableListOf<Boolean>()

	/** Whether each Shorts read was offered as native-account evidence. */
	val shortIdScopes = mutableListOf<Boolean>()
	/** Fresh-feed reads and cache replacements, for shadow-isolation fingerprints. */
	var feedReads = 0
		private set
	var cacheWrites = 0
		private set
	var shortIds: (String?) -> List<String> = { emptyList() }
	var evidence: (String) -> VideoResolutionAttempt =
		{ VideoResolutionAttempt(refusalReason = "watch history named no video for \"$it\"") }
	var revalidation: (String) -> VideoResolutionAttempt =
		{ VideoResolutionAttempt(refusalReason = "watch history no longer names $it") }

	override fun isolatedCopy(): ReplayWatchHistory =
		ReplayWatchHistory(hasSession, health.isolatedCopy()).also { copy ->
			copy.resets = resets
			copy.nowMillis = nowMillis
			copy.evidenceQueries.addAll(evidenceQueries)
			copy.shortCorroborations = shortCorroborations
			copy.shortIdQueries.addAll(shortIdQueries)
			copy.shortIdForceRefreshes.addAll(shortIdForceRefreshes)
			copy.shortIdScopes.addAll(shortIdScopes)
			copy.feedReads = feedReads
			copy.cacheWrites = cacheWrites
			copy.shortIds = shortIds
			copy.evidence = evidence
			copy.revalidation = revalidation
			copy.feedHealthy = feedHealthy
			copy.feedFault = feedFault
		}

	override fun reset() {
		resets++
		health.reset()
	}

	override suspend fun recentShortIds(
		title: String?,
		forceRefresh: Boolean,
		countsAsAccountEvidence: Boolean,
	): List<String> {
		shortIdQueries += title
		shortIdForceRefreshes += forceRefresh
		shortIdScopes += countsAsAccountEvidence
		// The production gate: a stood-down route offers nothing to native
		// playback outside its probe, and a browser read neither spends the probe
		// nor is blocked by a diagnosis it is not evidence for.
		if (!health.mayRun(nowMillis(), accountEvidence = countsAsAccountEvidence)) {
			return emptyList()
		}
		if (!reachFeed()) return emptyList()
		return shortIds(title)
	}

	override fun recordShortCorroborated() {
		shortCorroborations++
		// Exactly what the production resolver does with this call.
		health.recordHit()
	}

	override suspend fun resolveEvidence(
		title: String,
		channel: String?,
		durationSec: Long?,
		ownerHandle: String?,
		countsAsAccountEvidence: Boolean,
	): VideoResolutionAttempt {
		evidenceQueries += title to countsAsAccountEvidence
		if (!health.mayRun(nowMillis(), accountEvidence = countsAsAccountEvidence)) {
			return VideoResolutionAttempt(
				refusalReason = "watch history is not being used: ${health.refusedBecause}",
			)
		}
		if (!reachFeed()) {
			return VideoResolutionAttempt(
				refusalReason = "watch history is not being used: ${health.refusedBecause}",
			)
		}
		val attempt = evidence(title)
		if (attempt.resolution != null) {
			// The production rule: a hit clears the diagnosis only when it was
			// evidence for it.
			if (countsAsAccountEvidence) health.recordHit()
		} else if (countsAsAccountEvidence) {
			// The production rule: a plain absence from a freshly read feed, keyed
			// by the track, is the only thing that counts.
			health.recordMiss(nowMillis(), "$title|${channel.orEmpty()}|${durationSec ?: ""}")
		}
		return attempt
	}

	override suspend fun revalidate(
		videoId: String,
		title: String,
		channel: String?,
		durationSec: Long?,
		countsAsAccountEvidence: Boolean,
	): VideoResolutionAttempt {
		evidenceQueries += title to countsAsAccountEvidence
		return revalidation(videoId)
	}

	/** A fetched-and-parsed feed, as `recentEntries` reports it in production. */
	fun feedParsedHealthy() = health.recordRouteHealthy()

	/** YouTube's own declared fault about the stored session. */
	fun declareFault(
		reason: WatchHistoryParser.Reason,
		atMillis: Long = nowMillis(),
	) = health.recordUnavailable(reason, "replay", atMillis)

	/**
	 * Whether a lookup that gets past the gate reaches a feed that fetches and
	 * parses.
	 *
	 * True is production's ordinary case and is what clears a declared route
	 * fault. False models a recovery probe that found the same fault again.
	 */
	var feedHealthy: Boolean = true
	var feedFault: WatchHistoryParser.Reason = WatchHistoryParser.Reason.SIGNED_OUT

	/** What `recentEntries` does to health once the gate has let a caller in. */
	private fun reachFeed(): Boolean {
		feedReads++
		if (feedHealthy) {
			health.recordRouteHealthy()
			cacheWrites++
			return true
		}
		health.recordUnavailable(feedFault, "replay", nowMillis())
		return false
	}

	override suspend fun probe(): WatchHistoryResolver.Probe =
		WatchHistoryResolver.Probe.Faulted(null, "the replay harness does not fetch history")
}

/** Immediate in replay, while retaining the exact production delay schedule for assertions. */
class ReplayHistoryRetryDelay : HistoryRetryDelay {
	val waits = mutableListOf<Long>()

	override suspend fun wait(millis: Long) {
		waits += millis
	}
}

/** The connected-account record, with no cookie anywhere near it. */
class ReplayAccountSession(
	override var session: YouTubeSessionVault.Session? = null,
) : AccountSessionStore {
	var saves = 0
		private set
	var forgets = 0
		private set

	override fun save(cookieHeader: String, accountLabel: String?) {
		saves++
		session = YouTubeSessionVault.Session(accountLabel, 0, 0)
	}

	override fun forget() {
		forgets++
		session = null
	}
}

// ---- the bundle -------------------------------------------------------------

/** Every fake, assembled, with the pieces a scenario wants to reach kept public. */
class ReplayEnvironment(
	val clock: ReplayClock = ReplayClock(),
	val policy: ReplayPolicy = ReplayPolicy(),
	val claims: ReplayDedupClaims = ReplayDedupClaims(),
	val broadcaster: RecordingBroadcaster = RecordingBroadcaster(),
	val mutes: ReplayMuteList = ReplayMuteList(),
	val facts: ReplayFacts = ReplayFacts(),
	val music: ReplayMusicVerifier = ReplayMusicVerifier(),
	val identity: ReplayIdentitySource = ReplayIdentitySource(),
	val watchHistory: ReplayWatchHistory = ReplayWatchHistory(),
	val account: ReplayAccountSession = ReplayAccountSession(),
	val posting: ReplayPostingIdentity = ReplayPostingIdentity(),
	val historyRetryDelay: ReplayHistoryRetryDelay = ReplayHistoryRetryDelay(),
) {
	val retryQueue = ReplayRetryQueue(clock)

	internal fun ports(
		identityResolverMode: IdentityResolverMode = IdentityResolverMode.TYPED,
	): EnginePorts = EnginePorts(
		policy = policy,
		identity = posting,
		claims = claims,
		retryQueue = retryQueue,
		broadcaster = broadcaster,
		mutes = mutes,
		facts = facts,
		metadata = facts,
		music = music,
		videoIdentity = identity,
		watchHistory = watchHistory,
		accountSession = account,
		clock = clock,
		historyRetryDelay = historyRetryDelay,
		identityResolverMode = identityResolverMode,
	)
}
