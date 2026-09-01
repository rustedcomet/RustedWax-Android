package com.rustedwax.app.scrobble

import android.content.Context
import com.rustedwax.app.enrich.WatchHistoryHealth
import com.rustedwax.app.enrich.MusicBrainzVerifier
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.enrich.VideoIdResolver
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.youtube.identity.VideoResolutionFailure
import com.rustedwax.app.enrich.WatchHistoryResolver
import com.rustedwax.hive.HiveBroadcaster
import com.rustedwax.hive.HiveKey
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PrivateScrobble
import com.rustedwax.app.storage.KeyVault
import com.rustedwax.app.storage.MutedVideos
import com.rustedwax.app.storage.Settings
import com.rustedwax.app.storage.YouTubeSessionVault
import com.rustedwax.core.AutomaticWriteAuthorization
import com.rustedwax.core.Clock
import com.rustedwax.core.SystemClock
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay

/** The user's switches, as the finalization path reads them. */
internal interface ScrobblePolicy {
	val monitoringEnabled: Boolean
	var autoScrobble: Boolean
	/** The current continuous automatic-write opt-in interval. */
	val automaticWriteAuthorization: AutomaticWriteAuthorization

	/** True only for the same uninterrupted ON interval in which the target began. */
	fun authorizesAutomaticWrite(stamp: AutomaticWriteAuthorization): Boolean =
		autoScrobble && stamp.enabledAtStart && stamp == automaticWriteAuthorization

	/**
	 * Linearize an automatic target's handoff to irreversible transport.
	 *
	 * A successful return commits the complete ordered payload batch as
	 * already-authorized transport work. Implementations must serialize this
	 * decision with real [autoScrobble] mutation, without holding that critical
	 * section across key, network, signing, broadcast or queue operations.
	 */
	fun tryCommitAutomaticWrite(stamp: AutomaticWriteAuthorization): Boolean
	val enrichment: Boolean
	val scrobbleThreshold: Double
	val disableShorts: Boolean
	var watchHistory: Boolean
	fun privacyEnabledFor(category: PrivateScrobble.Category): Boolean
}

/** "Has this exact listen already been claimed?" — see [DedupLedger]. */
internal interface DedupClaims {
	fun claim(key: String): Boolean

	/**
	 * Whether the key is already held, without taking it.
	 *
	 * Exists so a shadow run can reach the same duplicate verdict a live one
	 * would without touching the ledger. Asking by claiming — the previous
	 * arrangement — takes a real claim for a run that is not happening, and a
	 * concurrent live finalization of the same listen is refused as a duplicate
	 * of nothing. Releasing it afterwards narrows that window; it does not close
	 * it.
	 */
	fun holds(key: String): Boolean
	fun release(key: String)
	fun prune()
}

/**
 * Dedup for a run that must decide but must not record.
 *
 * Reads through to the real ledger, so a listen it already holds is refused in
 * shadow exactly as it would be live — that verdict is one of the things a
 * parity comparison is for. Writes go to a set that is discarded with the run,
 * so a second finalization *within* the same shadow run still sees the first
 * one's claim and the ledger never learns either happened.
 */
internal class ShadowClaims(private val live: DedupClaims) : DedupClaims {

	private val claimed = mutableSetOf<String>()

	override fun claim(key: String): Boolean =
		!live.holds(key) && claimed.add(key)

	override fun holds(key: String): Boolean = live.holds(key) || key in claimed

	override fun release(key: String) {
		claimed -= key
	}

	/** Pruning is durable maintenance. A run that changes nothing does not do it. */
	override fun prune() = Unit
}

/** Durable retry storage for listens the chain could not take yet. */
internal interface RetryQueue {
	fun size(): Int
	fun due(): List<BroadcastQueue.Entry>
	fun add(
		username: String,
		json: String,
		label: String,
		percentPlayed: Int?,
		videoId: String?,
	): Boolean

	fun remove(id: Long): Boolean
	fun recordFailure(id: Long, error: String): BroadcastQueue.FailureOutcome
}

/** The posting account and the keys derived from it. Never logs, never leaks. */
internal interface PostingIdentity {
	val account: KeyVault.Account?
	fun loadKey(): HiveKey?
	fun privacySecret(): ByteArray?
}

/**
 * The one place a scrobble leaves the device.
 *
 * Named as a port mostly so that a replay's fake can *assert it was never
 * called*. Shadow-mode parity work in later migration phases has exactly one
 * safety property — the shadow path must not broadcast — and a test can only
 * check that against a seam like this one.
 */
internal interface PayloadBroadcaster {
	fun broadcastJson(username: String, key: HiveKey, payloadJson: String): HiveRpc.BroadcastResult
}

/** "Never scrobble this video again." */
internal interface MuteList {
	fun isMuted(videoId: String): Boolean
	fun mute(videoId: String, label: String)
	fun unmute(videoId: String)
	fun all(): Map<String, String>
}

/** Already-fetched watch-page facts. Memory/disk only — never the network. */
internal fun interface FactsStore {
	fun get(videoId: String): VideoFacts?
}

/** Best-effort canonical artist/track confirmation. */
internal interface MusicVerifier {
	fun cached(artist: String, track: String): MusicBrainzVerifier.Match?
	suspend fun verify(artist: String, track: String): MusicBrainzVerifier.Match?
}

/**
 * The resolver routes, as the engine calls them.
 *
 * Every method returns the resolver's own evidence types unchanged. The engine
 * does its own corroboration on whatever comes back — that is the property
 * `Documentation/Product/IDENTITY.md` depends on, and a port that started interpreting results
 * would be the beginning of losing it.
 */
internal interface VideoIdentitySource {
	// The defaults mirror `VideoIdResolver`'s own, so introducing the port left
	// every call site in the engine unchanged. An override may not restate them,
	// which is the language making sure there is only one set to keep true.
	suspend fun resolveNativePlaylistId(
		playlistName: String,
		ownerName: String? = null,
		total: Int? = null,
	): String?

	suspend fun resolveEvidenceFromPlaylist(
		playlistId: String,
		title: String,
		channel: String?,
		durationSec: Long?,
		seedVideoId: String? = null,
	): VideoResolution?

	suspend fun resolveEvidenceFromPlaylistAttempt(
		playlistId: String,
		title: String,
		channel: String?,
		durationSec: Long?,
		seedVideoId: String? = null,
	): VideoResolutionAttempt = resolveEvidenceFromPlaylist(
		playlistId, title, channel, durationSec, seedVideoId,
	)?.let { VideoResolutionAttempt(resolution = it) } ?: VideoResolutionAttempt(
		refusalReason = "observed playlist did not resolve",
		failure = VideoResolutionFailure.NO_MATCH,
	)

	suspend fun resolveEvidenceFromAdjacentPredecessors(
		firstVideoId: String,
		secondVideoId: String,
		firstTitle: String?,
		secondTitle: String?,
		title: String,
		channel: String?,
		durationSec: Long?,
	): VideoResolution?

	suspend fun resolveEvidenceFromAdjacentPredecessorsAttempt(
		firstVideoId: String,
		secondVideoId: String,
		firstTitle: String?,
		secondTitle: String?,
		title: String,
		channel: String?,
		durationSec: Long?,
	): VideoResolutionAttempt = resolveEvidenceFromAdjacentPredecessors(
		firstVideoId, secondVideoId, firstTitle, secondTitle, title, channel, durationSec,
	)?.let { VideoResolutionAttempt(resolution = it) } ?: VideoResolutionAttempt(
		refusalReason = "consecutive playlist did not resolve",
		failure = VideoResolutionFailure.NO_MATCH,
	)

	suspend fun resolveEvidenceAttempt(
		title: String,
		channel: String?,
		durationSec: Long?,
		ownerHandle: String? = null,
		allowStructuredNativeMusic: Boolean = false,
		allowYouTubeMusicCatalog: Boolean = false,
		/** The release the player published; only the Music catalog route reads it. */
		album: String? = null,
		/** The player's unrounded length; only duplicate-family selection reads it. */
		durationMs: Long? = null,
	): VideoResolutionAttempt

	suspend fun resolveVerifiedCandidates(
		videoIds: List<String>,
		title: String?,
		channel: String?,
		durationSec: Long?,
		ownerHandle: String? = null,
	): VideoResolutionAttempt

	suspend fun revalidatePreResolvedNativeMusic(
		videoId: String,
		title: String,
		artist: String,
		durationSec: Long,
	): VideoResolutionAttempt
}

/** The signed-in account's own watch history. */
internal interface WatchHistorySource {
	val hasSession: Boolean
	val refusedBecause: String?

	/**
	 * The same refusal as a typed cause.
	 *
	 * [refusedBecause] is prose written for a person; production behaviour may
	 * not branch on it. The UI needs to know *which* condition is in force to
	 * offer the right recovery, so the cause travels beside the sentence.
	 */
	val refusedAs: WatchHistoryHealth.Refusal?
	/** Read the same pre-run evidence while committing no health/cache/vault/query state. */
	fun isolatedCopy(): WatchHistorySource
	fun reset()
	suspend fun recentShortIds(
		title: String?,
		forceRefresh: Boolean = false,
		/**
		 * False for a browser read. A browser Short is not evidence about the
		 * native YouTube app's account, so asking must not spend the one probe a
		 * stood-down route allows native playback.
		 */
		countsAsAccountEvidence: Boolean = true,
	): List<String>
	suspend fun resolveEvidence(
		title: String,
		channel: String?,
		durationSec: Long?,
		ownerHandle: String? = null,
		/**
		 * False for a track that could never be an ordinary history row — an ad, or
		 * a Short, which the feed keeps in a separate list. Its absence is then not
		 * evidence that this phone is playing into a different account.
		 */
		countsAsAccountEvidence: Boolean = true,
	): VideoResolutionAttempt

	/**
	 * An id from this account's own Shorts feed was corroborated on its own watch
	 * page. Proof the account is recording this phone's playback, by the feed's
	 * other list.
	 */
	fun recordShortCorroborated()

	suspend fun revalidate(
		videoId: String,
		title: String,
		channel: String?,
		durationSec: Long?,
		/** False when the listen was too brief to be evidence about the account. */
		countsAsAccountEvidence: Boolean = true,
	): VideoResolutionAttempt

	suspend fun probe(): WatchHistoryResolver.Probe
}

/** A cancellable wait between bounded watch-history propagation retries. */
internal fun interface HistoryRetryDelay {
	suspend fun wait(millis: Long)
}

/** Production timing; replay supplies an immediate, recording implementation. */
internal object CoroutineHistoryRetryDelay : HistoryRetryDelay {
	override suspend fun wait(millis: Long) = delay(millis)
}

/**
 * The stored YouTube session.
 *
 * The cookie itself is write-only through this port on purpose: [save] takes
 * one and nothing here ever gives one back. The engine has no legitimate reason
 * to read a session cookie, and a port that could hand one out would be the
 * shortest route from a diagnostic to a credential in an exported log.
 */
internal interface AccountSessionStore {
	val session: YouTubeSessionVault.Session?
	fun save(cookieHeader: String, accountLabel: String?)
	fun forget()
}

// ---- production adapters ----------------------------------------------------
//
// Each one forwards. There is no logic here on purpose: an adapter that decided
// anything would be behaviour that exists only on the device, which is the
// exact thing the ports were introduced to remove.

internal class SettingsPolicy(private val settings: Settings) : ScrobblePolicy {
	private val automaticAuthorization = AtomicReference(
		AutomaticWriteAuthorization(0, settings.autoScrobble),
	)
	override val monitoringEnabled: Boolean get() = settings.monitoringEnabled
	override var autoScrobble: Boolean
		get() = settings.autoScrobble
		set(value) {
			synchronized(this) {
				automaticAuthorization.updateAndGet { current ->
					if (current.enabledAtStart == value) current else AutomaticWriteAuthorization(
						generation = current.generation + 1,
						enabledAtStart = value,
					)
				}
				settings.autoScrobble = value
			}
		}
	override val automaticWriteAuthorization: AutomaticWriteAuthorization
		get() = automaticAuthorization.get()
	override fun tryCommitAutomaticWrite(stamp: AutomaticWriteAuthorization): Boolean =
		synchronized(this) {
			settings.autoScrobble && stamp.enabledAtStart && stamp == automaticAuthorization.get()
		}
	override val enrichment: Boolean get() = settings.enrichment
	override val scrobbleThreshold: Double get() = settings.scrobbleThreshold
	override val disableShorts: Boolean get() = settings.disableShorts
	override var watchHistory: Boolean
		get() = settings.watchHistory
		set(value) {
			settings.watchHistory = value
		}

	override fun privacyEnabledFor(category: PrivateScrobble.Category): Boolean =
		settings.privacyEnabledFor(category)
}

internal class LedgerClaims(private val ledger: DedupLedger) : DedupClaims {
	override fun claim(key: String): Boolean = ledger.claim(key)
	override fun holds(key: String): Boolean = ledger.contains(key)
	override fun release(key: String) = ledger.release(key)
	override fun prune() = ledger.prune()
}

internal class StoredRetryQueue(private val queue: BroadcastQueue) : RetryQueue {
	override fun size(): Int = queue.size()
	override fun due(): List<BroadcastQueue.Entry> = queue.due()
	override fun add(
		username: String,
		json: String,
		label: String,
		percentPlayed: Int?,
		videoId: String?,
	): Boolean = queue.add(username, json, label, percentPlayed, videoId)

	override fun remove(id: Long): Boolean = queue.remove(id)
	override fun recordFailure(id: Long, error: String): BroadcastQueue.FailureOutcome =
		queue.recordFailure(id, error)
}

internal class VaultPostingIdentity(private val vault: KeyVault) : PostingIdentity {
	override val account: KeyVault.Account? get() = vault.account
	override fun loadKey(): HiveKey? = vault.loadKey()
	override fun privacySecret(): ByteArray? = vault.privacySecret()
}

internal class HivePayloadBroadcaster(
	private val broadcaster: HiveBroadcaster = HiveBroadcaster(),
) : PayloadBroadcaster {
	override fun broadcastJson(
		username: String,
		key: HiveKey,
		payloadJson: String,
	): HiveRpc.BroadcastResult = broadcaster.broadcastJson(username, key, payloadJson)
}

internal class StoredMuteList(private val muted: MutedVideos) : MuteList {
	override fun isMuted(videoId: String): Boolean = muted.isMuted(videoId)
	override fun mute(videoId: String, label: String) = muted.mute(videoId, label)
	override fun unmute(videoId: String) = muted.unmute(videoId)
	override fun all(): Map<String, String> = muted.all()
}

internal class MusicBrainzVerification(
	private val verifier: MusicBrainzVerifier,
) : MusicVerifier {
	override fun cached(artist: String, track: String): MusicBrainzVerifier.Match? =
		verifier.cached(artist, track)

	override suspend fun verify(artist: String, track: String): MusicBrainzVerifier.Match? =
		verifier.verify(artist, track)
}

internal class ResolverIdentitySource(
	private val resolver: VideoIdResolver = VideoIdResolver(),
) : VideoIdentitySource {
	override suspend fun resolveNativePlaylistId(
		playlistName: String,
		ownerName: String?,
		total: Int?,
	): String? = resolver.resolveNativePlaylistId(playlistName, ownerName, total)

	override suspend fun resolveEvidenceFromPlaylist(
		playlistId: String,
		title: String,
		channel: String?,
		durationSec: Long?,
		seedVideoId: String?,
	): VideoResolution? =
		resolver.resolveEvidenceFromPlaylist(playlistId, title, channel, durationSec, seedVideoId)

	override suspend fun resolveEvidenceFromPlaylistAttempt(
		playlistId: String,
		title: String,
		channel: String?,
		durationSec: Long?,
		seedVideoId: String?,
	): VideoResolutionAttempt = resolver.resolveEvidenceFromPlaylistAttempt(
		playlistId, title, channel, durationSec, seedVideoId,
	)

	override suspend fun resolveEvidenceFromAdjacentPredecessors(
		firstVideoId: String,
		secondVideoId: String,
		firstTitle: String?,
		secondTitle: String?,
		title: String,
		channel: String?,
		durationSec: Long?,
	): VideoResolution? = resolver.resolveEvidenceFromAdjacentPredecessors(
		firstVideoId, secondVideoId, firstTitle, secondTitle, title, channel, durationSec,
	)

	override suspend fun resolveEvidenceFromAdjacentPredecessorsAttempt(
		firstVideoId: String,
		secondVideoId: String,
		firstTitle: String?,
		secondTitle: String?,
		title: String,
		channel: String?,
		durationSec: Long?,
	): VideoResolutionAttempt = resolver.resolveEvidenceFromAdjacentPredecessorsAttempt(
		firstVideoId, secondVideoId, firstTitle, secondTitle, title, channel, durationSec,
	)

	override suspend fun resolveEvidenceAttempt(
		title: String,
		channel: String?,
		durationSec: Long?,
		ownerHandle: String?,
		allowStructuredNativeMusic: Boolean,
		allowYouTubeMusicCatalog: Boolean,
		album: String?,
		durationMs: Long?,
	): VideoResolutionAttempt = resolver.resolveEvidenceAttempt(
		title,
		channel,
		durationSec,
		ownerHandle,
		allowStructuredNativeMusic,
		allowYouTubeMusicCatalog,
		album,
		durationMs,
	)

	override suspend fun resolveVerifiedCandidates(
		videoIds: List<String>,
		title: String?,
		channel: String?,
		durationSec: Long?,
		ownerHandle: String?,
	): VideoResolutionAttempt =
		resolver.resolveVerifiedCandidates(videoIds, title, channel, durationSec, ownerHandle)

	override suspend fun revalidatePreResolvedNativeMusic(
		videoId: String,
		title: String,
		artist: String,
		durationSec: Long,
	): VideoResolutionAttempt =
		resolver.revalidatePreResolvedNativeMusic(videoId, title, artist, durationSec)
}

internal class AccountWatchHistory(
	private val history: WatchHistoryResolver,
) : WatchHistorySource {
	override val hasSession: Boolean get() = history.hasSession
	override val refusedBecause: String? get() = history.refusedBecause
	override val refusedAs: WatchHistoryHealth.Refusal? get() = history.refusedAs
	override fun isolatedCopy(): WatchHistorySource =
		AccountWatchHistory(history.isolatedCopy())
	override fun reset() = history.reset()
	override suspend fun recentShortIds(
		title: String?,
		forceRefresh: Boolean,
		countsAsAccountEvidence: Boolean,
	): List<String> = history.recentShortIds(
		title,
		forceRefresh = forceRefresh,
		countsAsAccountEvidence = countsAsAccountEvidence,
	)

	override suspend fun resolveEvidence(
		title: String,
		channel: String?,
		durationSec: Long?,
		ownerHandle: String?,
		countsAsAccountEvidence: Boolean,
	): VideoResolutionAttempt = history.resolveEvidence(
		title,
		channel,
		durationSec,
		ownerHandle,
		countsAsAccountEvidence = countsAsAccountEvidence,
	)

	override fun recordShortCorroborated() = history.recordShortCorroborated()

	override suspend fun revalidate(
		videoId: String,
		title: String,
		channel: String?,
		durationSec: Long?,
		countsAsAccountEvidence: Boolean,
	): VideoResolutionAttempt = history.revalidate(
		videoId,
		title,
		channel,
		durationSec,
		countsAsAccountEvidence = countsAsAccountEvidence,
	)

	override suspend fun probe(): WatchHistoryResolver.Probe = history.probe()
}

internal class VaultAccountSession(
	private val vault: YouTubeSessionVault,
) : AccountSessionStore {
	override val session: YouTubeSessionVault.Session? get() = vault.session
	override fun save(cookieHeader: String, accountLabel: String?) =
		vault.save(cookieHeader, accountLabel)

	override fun forget() = vault.forget()
}

/**
 * Everything [FinalizationRuntime] runs on, in one bundle.
 *
 * A bundle rather than eleven `init` parameters because the set is what has to
 * stay consistent: a replay that swapped the broadcaster but kept the device's
 * real key vault would be a live-fire test wearing a fake's name.
 */
internal class EnginePorts(
	val policy: ScrobblePolicy,
	val identity: PostingIdentity,
	val claims: DedupClaims,
	val retryQueue: RetryQueue,
	val broadcaster: PayloadBroadcaster,
	val mutes: MuteList,
	val facts: FactsStore,
	val metadata: com.rustedwax.app.enrich.MetadataResolver,
	val music: MusicVerifier,
	val videoIdentity: VideoIdentitySource,
	val watchHistory: WatchHistorySource,
	val accountSession: AccountSessionStore,
	val clock: Clock = SystemClock,
	val historyRetryDelay: HistoryRetryDelay = CoroutineHistoryRetryDelay,
	val identityResolverMode: IdentityResolverMode = IdentityResolverMode.TYPED,
) {
	companion object {
		/** The wiring the app itself uses. Every port is the real thing. */
		fun forDevice(
			context: Context,
			factsCache: com.rustedwax.app.enrich.FactsCache,
			metadata: com.rustedwax.app.enrich.MetadataResolver,
		): EnginePorts {
			val settings = Settings(context)
			val youtubeSession = YouTubeSessionVault(context)
			return EnginePorts(
				policy = SettingsPolicy(settings),
				identity = VaultPostingIdentity(KeyVault(context)),
				claims = LedgerClaims(DedupLedger(context)),
				retryQueue = StoredRetryQueue(BroadcastQueue(context)),
				broadcaster = HivePayloadBroadcaster(),
				mutes = StoredMuteList(MutedVideos(context)),
				facts = FactsStore(factsCache::get),
				metadata = metadata,
				music = MusicBrainzVerification(MusicBrainzVerifier(context)),
				videoIdentity = ResolverIdentitySource(),
				watchHistory = AccountWatchHistory(WatchHistoryResolver(youtubeSession)),
				accountSession = VaultAccountSession(youtubeSession),
				clock = SystemClock,
				historyRetryDelay = CoroutineHistoryRetryDelay,
			)
		}
	}
}

/** The retained old path is replay-only parity evidence; production uses typed strategies. */
enum class IdentityResolverMode {
	TYPED,
	LEGACY,
}
