package com.rustedwax.app.scrobble

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.rustedwax.core.Clock
import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.HiveScrobblePayload
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.PrivateScrobble
import com.rustedwax.app.detect.ScrobbleBuilder
import com.rustedwax.app.detect.FinalizedTrack
import com.rustedwax.app.detect.SessionProbe
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.TitleParser
import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.detect.NativeSourceSwitches
import com.rustedwax.app.detect.NativePreResolvedRoute
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.app.enrich.WatchHistoryHealth
import com.rustedwax.app.enrich.FactsCache
import com.rustedwax.app.enrich.MetadataResolver
import com.rustedwax.app.enrich.MusicBrainzVerifier
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.enrich.VideoIdentityCorroborator
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.youtube.identity.VideoResolutionFailure
import com.rustedwax.identity.api.IdentityOutcomeKind
import com.rustedwax.identity.api.IdentityStrategyOutcome
import com.rustedwax.youtube.identity.ProductionIdentityContext
import com.rustedwax.youtube.identity.ProductionIdentityStrategies
import com.rustedwax.youtube.identity.VideoIdentityRepositoryAttempt
import com.rustedwax.youtube.identity.YouTubeIdentityContext
import com.rustedwax.youtube.identity.YouTubeIdentityStrategyRepository
import com.rustedwax.app.enrich.VerifiedIdentityCandidateCache
import com.rustedwax.app.enrich.WatchHistoryResolver
import com.rustedwax.app.enrich.YouTubePageResolver
import com.rustedwax.app.storage.YouTubeSessionVault
import java.util.UUID

/**
 * Turns finished tracks into on-chain scrobbles.
 *
 * The pipeline, mirroring what `hive-scrobbler.ts` does across its finalize and
 * broadcast paths:
 *
 *   finalize → identity → prefilter ┊ enrich → rules → dedup → sign → broadcast
 *                                   ┊                                ↘ queue on failure
 *
 * The `┊` is the thread boundary. Everything left of it is cheap and
 * synchronous; everything right of it may touch the network. The rules run on
 * the far side because two of their inputs — the recovered duration and the
 * watch-page proof behind the short-clip floor — don't exist until enrichment
 * has answered. [ScrobbleRules.prefilter] is what keeps that from meaning "one
 * fetch per finalize".
 *
 * A singleton because the detection host ([com.rustedwax.app.detect.RustedWaxListenerService])
 * and the UI are separate processes-in-spirit that must share one ledger and
 * one queue. Initialised once from application context.
 */
object FinalizationRuntime {

	/**
	 * Every device, disk and network this object touches — see [EnginePorts].
	 *
	 * Held as one field rather than eleven so that "which wiring is installed"
	 * is a single readable fact. [init] installs the real one;
	 * [installPortsForReplay] installs a scripted one for the replay harness,
	 * and nothing between here and the chain can tell the difference. That is
	 * the point: the harness has to exercise this code, not a copy of it.
	 */
	private lateinit var ports: EnginePorts

	private val vault: PostingIdentity get() = ports.identity
	private val ledger: DedupClaims get() = ports.claims
	private val queue: RetryQueue get() = ports.retryQueue
	private val settings: ScrobblePolicy get() = ports.policy
	private val resolver: MetadataResolver get() = ports.metadata
	private val factsCache: FactsStore get() = ports.facts
	private val musicBrainz: MusicVerifier get() = ports.music
	private val muted: MuteList get() = ports.mutes
	private val youtubeSession: AccountSessionStore get() = ports.accountSession
	private val history: WatchHistorySource get() = ports.watchHistory
	private val idResolver: VideoIdentitySource get() = ports.videoIdentity
	private val broadcaster: PayloadBroadcaster get() = ports.broadcaster
	private val clock: Clock get() = ports.clock
	private val verifiedPlaybackSequence = VerifiedPlaybackSequence()

	/**
	 * Video ids a prefetch has already been launched for this session. Never
	 * cleared on failure: a video that couldn't be resolved once (offline,
	 * markup drift) shouldn't be re-fetched every second by the UI tick that
	 * triggers identity checks.
	 */
	private val prefetches = PrefetchCompletionRegistry()

	/**
	 * Where finalization runs. Application-lifetime IO in production.
	 *
	 * Injectable for one reason: a replay needs the launched work to have
	 * finished by the time it asserts, and waiting on a real dispatcher is how
	 * a suite acquires the flaky test that eventually gets deleted along with
	 * the coverage.
	 */
	private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val broadcastLock = Mutex()
	internal lateinit var finalizeTrack: FinalizeTrackUseCase
	private lateinit var dispatcher: ScrobbleDispatcher

	/**
	 * Told what became of each finalized track — see [FinalizationOutcome].
	 *
	 * [FinalizationObserver.None] in production, which is the whole point: the
	 * seam costs one virtual call per finalize and changes nothing a user can
	 * see. The replay harness installs a recorder so it can assert on the branch
	 * the engine actually took rather than reconstructing it from the skip list
	 * and the broadcast list afterwards.
	 */
	@Volatile
	internal var finalizationObserver: FinalizationObserver = FinalizationObserver.None

	@Volatile
	private var initialised = false

	/**
	 * Where [recent] and [skipped] are kept between processes, once [init] has
	 * supplied one.
	 *
	 * Null until then, and null for the whole of a replay run. A replay asserts
	 * on what one scenario produced, so it starts from two empty lists and must
	 * not read or write anything a previous scenario left behind; leaving this
	 * unset is what guarantees that, rather than a flag each scenario remembers
	 * to pass.
	 */
	@Volatile
	private var retained: RetainedRecordStore? = null

	data class ScrobbleRecord(
		val title: String,
		val artist: String?,
		val percentPlayed: Int,
		val atEpochSec: Long,
		val status: String,
		val txId: String? = null,
		val queued: Boolean = false,
		/**
		 * The exact video this entry came from. User-facing history is a hyperlink
		 * surface, so an entry that cannot prove this value is not a history row.
		 */
		val videoId: String,
		/**
		 * This row's own identity, opaque and minted once when the row is made.
		 *
		 * Nothing else here identifies a row. Video plus second collides: two
		 * queued attempts on one video inside the same second produce rows that
		 * are equal in every field, and `txId` is null for exactly those rows.
		 * A UI keyed on a colliding value hands one row's state — an open
		 * "Discard Snap?", a typed draft — to a different row.
		 *
		 * Deliberately has no default. A default would mint identity anywhere a
		 * record is built, which is how a missing one stops being noticed.
		 */
		val eventId: String,
		/**
		 * The Hive account this row belongs to, stamped when the row is made.
		 *
		 * History is per-account state: a row describes a listen that one
		 * identity broadcast, and the Snap, thread, Like and notification state
		 * hanging off it is stored under that same account. Without this the
		 * list was process-global, so switching accounts left the previous
		 * one's rows on screen for the new one to act on.
		 *
		 * Never read from the vault at display time. The vault says who is
		 * signed in *now*; this says who the row was made for, and those differ
		 * for exactly the rows that matter — one finalized as the switch lands,
		 * or a queued entry retried later. Taken from the account the payload
		 * was signed for, or from the queue entry's own `username`.
		 *
		 * No default, for the same reason [eventId] has none.
		 */
		val account: String,
	)

	/**
	 * A track that finished and did *not* become an entry, with the reason.
	 *
	 * The reasons were always computed — they went to the event log and nowhere
	 * else. From the user's side that made "watched 20 shorts, got 6 entries"
	 * indistinguishable from a broken app: there was no artifact anywhere in the
	 * UI saying the other 14 were seen and why each was declined. A gap you can
	 * explain is a policy; a silent one reads as a bug every time.
	 */
	data class SkipRecord(
		val title: String,
		val artist: String?,
		val reason: String,
		val atEpochSec: Long,
		val playedSeconds: Long,
		val durationSeconds: Long?,
		/**
		 * The exact video this row opens, when one was proven.
		 *
		 * Null when identity never resolved — offline, or a catalog lookup that
		 * corroborated nothing. A hyperlink is a requirement of *broadcasting*:
		 * nothing reaches the chain without a canonical watch URL, and that gate
		 * is untouched. It is not a requirement of telling the user their listen
		 * was seen and declined. Refusing the row as well made the commonest
		 * offline refusal invisible in every surface at once — the listen was
		 * measured, explained in the event log, and then shown nowhere. A row
		 * that does not open is honest; an absent row reads as a lost listen.
		 * Never fabricated: an unproven id stays null rather than becoming a
		 * guess that opens somebody else's re-upload.
		 */
		val videoId: String?,
		/**
		 * The Hive account this row belongs to, or null if nobody was signed in.
		 *
		 * Not-logged rows are per-account state for the same reason History rows
		 * are: the row names a title somebody watched, and which titles you
		 * watched is not the next identity's business. Without this the list was
		 * process-global, so switching accounts left the previous one's declined
		 * listens on screen, counted in the tab strip and one tap from opening.
		 *
		 * Nullable, where [ScrobbleRecord.account] is not, and the difference is
		 * the whole reason this field could not simply copy that one. A scrobble
		 * cannot exist without an account to sign it; a refusal can. Three of the
		 * gates that file a row — a source never proven to be YouTube, Shorts
		 * turned off, the prefilter — run before the posting key is ever
		 * consulted, so a signed-out install produces rows with no owner. Null
		 * says that, and says it durably: it is a real owner, "the signed-out
		 * device", and never a placeholder waiting to be filled in by whoever
		 * signs in next. See [skippedFor].
		 *
		 * No default, for the same reason [ScrobbleRecord.eventId] has none.
		 */
		val account: String?,
	)

	private const val MIN_NOTABLE_PLAYED_MS = 3_000L

	private val SHORT_HISTORY_RETRY_DELAYS_MS = longArrayOf(
		5_000L,
		15_000L,
		40_000L,
		90_000L,
	)

	private val _recent = MutableStateFlow<List<ScrobbleRecord>>(emptyList())
	val recent: StateFlow<List<ScrobbleRecord>> = _recent.asStateFlow()

	/**
	 * The rows [account] is allowed to see, and only those.
	 *
	 * [recent] is the whole process's list, kept in memory and never written to
	 * disk, so it outlives an account change the way any singleton does. This is
	 * the boundary the UI reads through: rows stamped for anybody else are not
	 * shown, not counted and not reachable by a tap, so no History action can
	 * land on a listen belonging to an account that is not signed in.
	 *
	 * A blank or absent [account] sees nothing. Signed out is not a viewer.
	 *
	 * Takes the list rather than reading [_recent] so the caller can hand in the
	 * value it is already observing, and so this rule is testable without the
	 * runtime being initialised.
	 */
	fun recentFor(
		rows: List<ScrobbleRecord>,
		account: String?,
	): List<ScrobbleRecord> {
		val viewer = account?.takeIf { it.isNotBlank() } ?: return emptyList()
		return rows.filter { it.account.equals(viewer, ignoreCase = true) }
	}

	private val _skipped = MutableStateFlow<List<SkipRecord>>(emptyList())
	val skipped: StateFlow<List<SkipRecord>> = _skipped.asStateFlow()

	/**
	 * The declined listens [account] is allowed to see, and only those.
	 *
	 * The same boundary [recentFor] draws around History, over a list with one
	 * more kind of owner in it. Rows carry [SkipRecord.account], stamped when
	 * the row was filed, and this is the only way the UI reads them: another
	 * account's refusals are not shown, not counted in the tab strip, and not
	 * reachable by a tap into the video they name.
	 *
	 * Where it parts company with [recentFor] is the signed-out case. There,
	 * signed out is not a viewer and sees nothing, because every row in that
	 * list was signed by somebody. Here a signed-out device files rows of its
	 * own — the gates above the posting-key check still run — and they are the
	 * only answer it has to "why wasn't this scrobbled", which is the entire
	 * job of this tab. So signed out sees exactly the rows made while signed
	 * out: the null stamp is matched, not treated as a wildcard.
	 *
	 * That cuts both ways, deliberately. Signing in does not hand those rows to
	 * the account that arrives — activity nobody was logged in for is never
	 * retroactively attributed to whoever logs in later — and signing out does
	 * not expose the rows an account left behind. Each set waits for its own
	 * owner to come back, for as long as the process lives.
	 *
	 * Takes the list rather than reading [_skipped] for the reasons [recentFor]
	 * does: the caller hands in the value it is already observing, and the rule
	 * is testable without the runtime being initialised.
	 */
	fun skippedFor(
		rows: List<SkipRecord>,
		account: String?,
	): List<SkipRecord> {
		val viewer = account?.takeIf { it.isNotBlank() }
			?: return rows.filter { it.account == null }
		return rows.filter { it.account != null && it.account.equals(viewer, ignoreCase = true) }
	}

	/**
	 * Restored rows placed under whatever this process has already filed.
	 *
	 * Restoration is not an assignment, because it is not guaranteed to be the
	 * first thing that happens. [init] runs from three entry points and a
	 * listener callback can finalize a track while one of them is still on its
	 * way through; a plain overwrite would throw that row away, and a plain
	 * concatenation would show it twice once the next write re-saved the merged
	 * list.
	 *
	 * So: [current] first, because anything this process filed is newer than
	 * anything a previous one left; then the stored rows that are not already
	 * here; then the same cap the writers apply. Identity is [ScrobbleRecord
	 * .eventId], which is the only field on a History row that cannot repeat.
	 *
	 * Idempotent by construction — running it twice restores nothing the second
	 * time — which is what makes it safe for [init] to be called again by a
	 * second activity or by the service.
	 */
	internal fun mergeRestoredHistory(
		current: List<ScrobbleRecord>,
		restored: List<ScrobbleRecord>,
	): List<ScrobbleRecord> {
		if (restored.isEmpty()) return current
		val known = current.mapTo(HashSet()) { it.eventId }
		return (current + restored.filterNot { it.eventId in known }).take(RETAINED_ROWS)
	}

	/**
	 * The same merge for Not Logged, over rows that have no minted identity.
	 *
	 * A skip row carries no `eventId` — nothing downstream keys UI state on one,
	 * so there was never a reason to mint it. Whole-row equality stands in: the
	 * data class compares every field, including the second it was filed at and
	 * the account that owns it, so a restored row is dropped exactly when this
	 * process has already filed one describing the same refusal.
	 *
	 * Two genuinely distinct refusals that agree on every field are
	 * indistinguishable here and one of them is dropped. That is accepted: they
	 * would draw as two identical rows, and a duplicate across a restart reads
	 * as a bug where a missing identical twin does not.
	 */
	internal fun mergeRestoredSkipped(
		current: List<SkipRecord>,
		restored: List<SkipRecord>,
	): List<SkipRecord> {
		if (restored.isEmpty()) return current
		val known = current.toHashSet()
		return (current + restored.filterNot { it in known }).take(RETAINED_ROWS)
	}

	private val _tracksWithoutVideoId = MutableStateFlow(0)
	val tracksWithoutVideoId: StateFlow<Int> = _tracksWithoutVideoId.asStateFlow()

	/** Consecutive misses before the UI says so. Three is a pattern, one is a page load. */
	const val QUIET_BAR_THRESHOLD = 3

	private val _queueSize = MutableStateFlow(0)
	val queueSize: StateFlow<Int> = _queueSize.asStateFlow()

	@Synchronized
	fun init(context: Context) {
		if (initialised) return
		val appContext = context.applicationContext
		// The one construction order that is not the adapter's business: the
		// page resolver writes through the same cache the Now card reads, so
		// both ports have to be built from a single [FactsCache] instance.
		val factsCache = FactsCache(appContext)
		install(
			EnginePorts.forDevice(
				context = appContext,
				factsCache = factsCache,
				metadata = YouTubePageResolver(factsCache),
			),
			CoroutineScope(SupervisorJob() + Dispatchers.IO),
		)
		// After `install`, so a restored list cannot be cleared by the wiring,
		// and inside the same `@Synchronized` call, so the lists are populated
		// before this method returns to the activity or service that asked. The
		// UI collects both as state, so a restore that lands a moment later
		// still draws — but nothing has to rely on that.
		restoreFrom(SharedPreferencesRetainedRecords(appContext))
	}

	/**
	 * Put back what the last process left, and keep writing there from now on.
	 *
	 * Separate from [init] so the restore rule can be exercised against a store
	 * that is not Android's. Guarded end to end: a store that throws on the way
	 * in leaves both lists exactly as they were and leaves [retained] unset, so
	 * a failure here costs the retained view and nothing else. Scrobbling does
	 * not read these lists and is not reached from this path.
	 */
	@Synchronized
	internal fun restoreFrom(store: RetainedRecordStore) {
		val restored = runCatching {
			store.loadHistory() to store.loadSkipped()
		}.getOrElse {
			EventLog.append(
				"retained",
				"could not read the retained History and Not Logged lists " +
					"(${it.javaClass.simpleName}); both start empty this run. " +
					"Scrobbling is unaffected.",
			)
			return
		}
		retained = store
		_recent.update { mergeRestoredHistory(it, restored.first) }
		_skipped.update { mergeRestoredSkipped(it, restored.second) }
		// A row can be filed while the store is being read. Persist the merged
		// value so that row and the restored tail both survive another process
		// death even if no later row is filed in this process.
		persistHistory()
		persistSkipped()
	}

	/**
	 * Write the retained lists after restoration or after a row has been filed.
	 *
	 * Called from restore and the two row writers. Failure is swallowed on
	 * purpose: the row is already on screen and the scrobble has already happened,
	 * so the only thing a thrown write could still do is take down the caller —
	 * which for [skip] is the media-session callback.
	 */
	private fun persistHistory() {
		runCatching { retained?.saveHistory(_recent.value) }
	}

	private fun persistSkipped() {
		runCatching { retained?.saveSkipped(_skipped.value) }
	}

	@Synchronized
	private fun install(installed: EnginePorts, installedScope: CoroutineScope) {
		ports = installed
		scope = installedScope
		wireFinalization()
		initialised = true
		ledger.prune()
		_queueSize.value = queue.size()
	}

	/** Production finalization composition root, rebuilt with every device or replay port set. */
	private fun wireFinalization() {
		dispatcher = object : ScrobbleDispatcher {
			override val hasPostingAccount: Boolean get() = vault.account != null

			override fun claim(
				claims: DedupClaims,
				payload: HiveScrobblePayload,
				startedAtEpochSec: Long,
			): Boolean {
				val key = DedupLedger.keyFor(payload.title, payload.artist, startedAtEpochSec)
				return claims.claim(key)
			}

			override fun release(
				claims: DedupClaims,
				payload: HiveScrobblePayload,
				startedAtEpochSec: Long,
			) = claims.release(DedupLedger.keyFor(payload.title, payload.artist, startedAtEpochSec))

			override fun dispatch(
				payload: HiveScrobblePayload,
				sourceItemId: String,
				sourcePackage: String,
				sourceEpoch: Long?,
				automaticTransportCommitted: Boolean,
				trigger: FinalizationTrigger,
				onFeedback: ((String, Boolean) -> Unit)?,
			) = enqueueAndSend(
				payload = payload,
				videoId = sourceItemId,
				sourcePackage = sourcePackage,
				sourceEpoch = sourceEpoch,
				automaticTransportCommitted = automaticTransportCommitted,
				trigger = trigger,
				onFeedback = onFeedback,
			)

			override fun retryDue() = retryQueuedPayloads()
		}
		finalizeTrack = FinalizeTrackUseCase(ProductionFinalizationOrchestrator(
			scope = scope,
			settings = settings,
			sequence = verifiedPlaybackSequence,
			claims = ledger,
			history = history,
			identity = IdentityService { session, sequence, runHistory ->
				when (ports.identityResolverMode) {
					IdentityResolverMode.TYPED -> resolveVideoId(session, sequence, runHistory)
					IdentityResolverMode.LEGACY -> session.confirmed?.let { confirmed ->
						VideoResolutionAttempt(resolution = frozenResolution(session, confirmed))
					} ?: resolveVideoIdLegacy(session, sequence, runHistory)
				}
			},
			enrichment = object : EnrichmentService {
				override suspend fun facts(videoId: String): VideoFacts? = enrich(videoId)
				override suspend fun music(
					session: SessionSnapshot,
					facts: VideoFacts?,
				): MusicBrainzVerifier.Match? = verifyMusic(session, facts)
			},
			classification = object : ClassificationService {
				override fun contradiction(
					session: SessionSnapshot,
					resolution: VideoResolution,
					facts: VideoFacts?,
				): String? = VideoIdentityCorroborator.contradiction(session, resolution, facts)

				override fun rememberVerified(
					session: SessionSnapshot,
					resolution: VideoResolution,
					facts: VideoFacts?,
					shadow: Boolean,
				) {
					if (!shadow && VideoIdentityCorroborator.cacheable(session, resolution, facts)) {
						VerifiedIdentityCandidateCache.remember(
							packageName = session.packageName,
							videoId = resolution.videoId,
							title = session.title,
							channel = session.artist,
							durationMs = session.durationMs,
							ownerHandle = session.ownerHandle,
						)
						EventLog.append(
							"resolve",
							"remembered ${resolution.videoId} as a bounded run-local verified candidate",
						)
					}
				}

				override fun isMuted(sourceItemId: String): Boolean = muted.isMuted(sourceItemId)
			},
			eligibility = object : EligibilityPolicy {
				override fun prefilter(session: SessionSnapshot): String? = ScrobbleRules.prefilter(
					playedMs = session.playedMs,
					durationMs = session.durationMs,
					threshold = settings.scrobbleThreshold,
					explicitAdSignal = session.explicitAdSignal,
					progressSurfaceLost = session.foregroundProgressLost,
					inferredMs = session.inferredPlayedMs,
					unobservedLeadInMs = session.unobservedLeadInMs,
				)

				override fun decide(
					session: SessionSnapshot,
					facts: VideoFacts?,
					durationMs: Long?,
				): ScrobbleRules.Decision = ScrobbleRules.decide(
					playedMs = session.playedMs,
					durationMs = durationMs,
					threshold = settings.scrobbleThreshold,
					isShort = session.hasShortSourceProof,
					videoResolved = facts?.resolvedOnWatchPage == true,
					videoUnlisted = facts?.isUnlisted,
					explicitAdSignal = session.explicitAdSignal,
					progressSurfaceLost = session.foregroundProgressLost,
					inferredMs = session.inferredPlayedMs,
					unobservedLeadInMs = session.unobservedLeadInMs,
				)

				override fun prefilter(track: FinalizedTrack): String? = ScrobbleRules.prefilter(
					playedMs = track.measurement.playedMs,
					durationMs = track.measurement.durationMs,
					threshold = settings.scrobbleThreshold,
					explicitAdSignal = track.evidence.explicitAdSignal,
					progressSurfaceLost = track.measurement.progressSurfaceLost,
					inferredMs = track.measurement.inferredPlayedMs,
				)

				override fun decide(
					track: FinalizedTrack,
					durationMs: Long?,
				): ScrobbleRules.Decision = ScrobbleRules.decide(
					playedMs = track.measurement.playedMs,
					durationMs = durationMs,
					threshold = settings.scrobbleThreshold,
					isShort = false,
					videoResolved = false,
					videoUnlisted = null,
					explicitAdSignal = track.evidence.explicitAdSignal,
					progressSurfaceLost = track.measurement.progressSurfaceLost,
					inferredMs = track.measurement.inferredPlayedMs,
				)
			},
			payloads = object : PayloadFactory {
				override fun effectiveDurationMs(
					session: SessionSnapshot,
					facts: VideoFacts?,
				): Long? = ScrobbleBuilder.effectiveDurationMs(session, facts)

				override fun build(
					session: SessionSnapshot,
					facts: VideoFacts?,
					music: MusicBrainzVerifier.Match?,
					identity: VideoResolution,
					durationMs: Long?,
				): HiveScrobblePayload? = ScrobbleBuilder.from(
					session,
					facts,
					music,
					identity.videoId,
					durationMs,
					resolvedTitle = identity.title,
					identityEvidence = identity,
				)

				override fun cap(
					decision: ScrobbleRules.Decision,
					payload: HiveScrobblePayload,
					session: SessionSnapshot,
				): List<Int> = ScrobbleRules.capForKind(
					decision.percentages,
					payload.kind,
					isShort = session.hasShortSourceProof,
					loopDetected = session.loopDetected,
					positionCorroborated = session.firstObservedPositionMs != null,
				)

				override fun buildSourceNeutral(
					track: FinalizedTrack,
					durationMs: Long?,
				): HiveScrobblePayload? {
					val title = track.metadata.title?.trim()?.takeIf(String::isNotEmpty) ?: return null
					val duration = durationMs?.takeIf { it > 0 } ?: return null
					return HiveScrobblePayload(
						title = title,
						artist = track.metadata.artist?.trim()?.takeIf(String::isNotEmpty),
						album = track.metadata.album?.trim()?.takeIf(String::isNotEmpty),
						timestamp = HiveScrobblePayload.isoTimestamp(track.measurement.startedAtEpochSec),
						duration = HiveScrobblePayload.formatDuration(duration / 1000),
						percentPlayed = ((track.measurement.playedMs.toDouble() / duration) * 100)
							.toInt().coerceIn(0, 100),
						platform = track.source.originName.lowercase(),
						url = track.evidence.canonicalLink,
					)
				}

				override fun cap(
					decision: ScrobbleRules.Decision,
					payload: HiveScrobblePayload,
					track: FinalizedTrack,
				): List<Int> = ScrobbleRules.capForKind(
					decision.percentages,
					payload.kind,
					isShort = false,
					loopDetected = track.measurement.loopDetected,
					positionCorroborated = track.measurement.firstObservedPositionMs != null,
				)
			},
			dispatcher = dispatcher,
			effects = object : FinalizationEffects {
				override fun skip(
					report: FinalizationReport,
					session: SessionSnapshot,
					reason: String,
					durationMs: Long?,
					log: Boolean,
					videoId: String?,
					resolvedTitle: String?,
				) = this@FinalizationRuntime.skip(
					report, session, reason, durationMs, log, videoId, resolvedTitle,
				)

				override fun couldBecomeUserFacingSkip(session: SessionSnapshot): Boolean =
					this@FinalizationRuntime.couldBecomeUserFacingSkip(session)

				override fun noteIdentity(
					session: SessionSnapshot,
					sourceItemId: String?,
					shadow: Boolean,
				) = noteVideoIdOutcome(session, sourceItemId, shadow)
			},
			observer = { finalizationObserver },
		))
	}

	internal fun installPortsForReplay(replayPorts: EnginePorts, replayScope: CoroutineScope) {
		synchronized(this) { initialised = false }
		prefetches.reset()
		verifiedPlaybackSequence.clear()
		// Replay never persists and never restores. Dropping the store here is
		// what keeps one scenario's rows out of the next one's assertions.
		retained = null
		_recent.value = emptyList()
		_skipped.value = emptyList()
		_tracksWithoutVideoId.value = 0
		install(replayPorts, replayScope)
	}

	/**
	 * Cancel work already launched, as a teardown would.
	 *
	 * The runtime's real scope is cancelled by the process or by the listener
	 * service going away; neither is reachable from a JVM replay. This cancels
	 * the children of the installed scope and nothing else, so a scenario can put
	 * a finalization in flight and then take the scope away underneath it — which
	 * is the race that used to end a finalization with no terminal outcome at
	 * all. Replay only; no production caller.
	 */
	internal fun cancelInFlightForReplay() {
		scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
	}

	/** Put the singleton back to "never initialised", between replay scenarios. */
	internal fun resetForReplay() {
		synchronized(this) { initialised = false }
		finalizationObserver = FinalizationObserver.None
		prefetches.reset()
		verifiedPlaybackSequence.clear()
		retained = null
		_recent.value = emptyList()
		_skipped.value = emptyList()
		_tracksWithoutVideoId.value = 0
		_queueSize.value = 0
	}

	val isReady: Boolean get() = initialised

	fun autoScrobbleEnabled(): Boolean = initialised && settings.autoScrobble

	/** Stamp a newly established logical listen with the current opt-in interval. */
	internal fun automaticWriteAuthorization(): com.rustedwax.core.AutomaticWriteAuthorization =
		if (initialised) {
			settings.automaticWriteAuthorization
		} else {
			com.rustedwax.core.AutomaticWriteAuthorization(0, enabledAtStart = false)
		}

	fun setAutoScrobble(enabled: Boolean) {
		settings.autoScrobble = enabled
		EventLog.append("engine", "auto-scrobble ${if (enabled) "on" else "off"}")
	}

	/**
	 * Whether a vanished MediaSession has already earned prompt automatic
	 * disposition.
	 *
	 * `SessionProbe` still decides no threshold: it supplies measurement and uses
	 * this boolean only to select the existing one-minute replacement window or
	 * the long resumable-position window. Full eligibility remains in
	 * [ScrobbleRules] at finalization, where identity, ads, duration floors,
	 * enrichment, mute and dedup evidence are available.
	 */
	internal fun shouldFinalizeContinuationPromptly(
		playedMs: Long,
		durationMs: Long?,
	): Boolean {
		if (!initialised || !settings.monitoringEnabled || !settings.autoScrobble) return false
		val duration = durationMs?.takeIf { it > 0 } ?: return false
		return playedMs.toDouble() / duration >= settings.scrobbleThreshold
	}

	fun resolveNativeCarryIdentity(
		session: SessionSnapshot,
		callback: (SessionProbe.NativeResolvedIdentity?) -> Unit,
	) {
		if (!initialised || !settings.monitoringEnabled || !settings.enrichment ||
			!session.profile.packageProvesSource || session.isForegroundShort ||
			!NativeSourceSwitches.isSnapshotCurrent(session.packageName, session.sourceEpoch)
		) {
			callback(null)
			return
		}
		scope.launch {
			val attempt = resolveVideoId(session, verifiedPlaybackSequence, history)
			val resolution = attempt.resolution
			if (resolution == null) {
				EventLog.append(
					"native-carry",
					"${session.packageName} pre-resolution refused \"${session.title}\": " +
						(attempt.refusalReason ?: "no unique candidate corroborated"),
				)
				callback(null)
				return@launch
			}
			val facts = enrich(resolution.videoId)
			val contradiction = VideoIdentityCorroborator.contradiction(session, resolution, facts)
			if (contradiction != null ||
				!NativeSourceSwitches.isSnapshotCurrent(session.packageName, session.sourceEpoch)
			) {
				EventLog.append(
					"native-carry",
					"${session.packageName} pre-resolution refused \"${session.title}\": " +
						(contradiction ?: "native source generation changed"),
				)
				callback(null)
				return@launch
			}
			callback(
				SessionProbe.NativeResolvedIdentity(
					videoId = resolution.videoId,
					route = when {
						resolution.playlistVerified -> NativePreResolvedRoute.PLAYLIST
						resolution.historyVerified -> NativePreResolvedRoute.HISTORY
						resolution.musicVideoRow -> NativePreResolvedRoute.MUSIC_VIDEO_ROW
						resolution.structuredNativeMusic ->
							NativePreResolvedRoute.STRUCTURED_MUSIC

						else -> NativePreResolvedRoute.RAW_TITLE_CHANNEL
					},
					// Passed through from the proof, never re-derived from the route
					// above. The route says which listing named the work; only the
					// matcher knows whether it also pinned the length.
					presentationDurationCorroborated = resolution.presentationDurationCorroborated,
				),
			)
		}
	}

	private fun mbCandidates(credits: ScrobbleBuilder.Parsed): List<Pair<String, String>> {
		val artist = credits.artist?.takeIf { it.isNotBlank() } ?: return emptyList()
		val out = mutableListOf(artist to credits.track)
		if (!credits.track.equals(artist, ignoreCase = true)) {
			out += credits.track to artist
		}
		return out
	}

	/**
	 * Best-effort MusicBrainz confirmation of the artist/track the payload
	 * would carry. Null when disabled, unparseable, or the network couldn't
	 * answer — every path degrades to the pre-MusicBrainz behaviour.
	 */
	private suspend fun verifyMusic(
		session: SessionSnapshot,
		facts: VideoFacts?,
	): MusicBrainzVerifier.Match? {
		if (!settings.enrichment) return null
		val credits = ScrobbleBuilder.creditsOf(session, facts) ?: return null
		var last: MusicBrainzVerifier.Match? = null
		for ((artist, track) in mbCandidates(credits)) {
			val match = runCatching { musicBrainz.verify(artist, track) }.getOrElse {
				EventLog.append("musicbrainz", "verifier threw: ${it.message}")
				null
			}
			if (match?.found == true) return match
			last = match ?: last
		}
		return last
	}

	/** Cache-only MusicBrainz verdict for the Now-tab preview. */
	fun cachedMusicMatch(
		session: SessionSnapshot,
		facts: VideoFacts?,
	): MusicBrainzVerifier.Match? {
		if (!initialised || !settings.enrichment) return null
		val credits = ScrobbleBuilder.creditsOf(session, facts) ?: return null
		var last: MusicBrainzVerifier.Match? = null
		for ((artist, track) in mbCandidates(credits)) {
			val match = musicBrainz.cached(artist, track)
			if (match?.found == true) return match
			last = match ?: last
		}
		return last
	}

	fun prefetch(videoId: String, onComplete: ((available: Boolean) -> Unit)? = null) {
		if (!initialised || !settings.enrichment) {
			onComplete?.invoke(false)
			return
		}
		if (cachedFacts(videoId) != null) {
			onComplete?.invoke(true)
			return
		}
		val registration = prefetches.register(videoId, onComplete)
		registration.completed?.let { completed ->
			onComplete?.invoke(completed)
			return
		}
		val launch = registration.launch ?: return
		scope.launch {
			val facts = runCatching { resolver.resolve(videoId) }
				.onFailure { EventLog.append("enrich", "prefetch failed: ${it.message}") }
				.getOrNull()
			// The completion says only whether the shared cache now has the page. The
			// The Android binding re-reads that cache on its main-thread handoff; facts never cross
			// the ownership boundary and a stale lifecycle cannot consume them.
			val available = facts != null && cachedFacts(videoId) != null
			prefetches.complete(launch, available).forEach { callback ->
				runCatching { callback(available) }
					.onFailure { EventLog.append("enrich", "prefetch completion failed: ${it.message}") }
			}
			if (facts == null) return@launch
			// Chain the MusicBrainz check so the Now card's verdict is warm by
			// the time anyone looks. Same credits derivation as the payload.
			val rawTitle = facts.title ?: return@launch
			val parsed = TitleParser.parse(rawTitle, facts.author)
			val credits = ScrobbleBuilder.Parsed(
				artist = facts.originalArtist ?: parsed.artist,
				track = facts.originalTitle?.let { TitleParser.clean(it) } ?: parsed.track,
			)
			for ((artist, track) in mbCandidates(credits)) {
				val match = runCatching { musicBrainz.verify(artist, track) }.getOrNull()
				if (match?.found == true) break
			}
		}
	}

	/**
	 * Never scrobble this video again.
	 *
	 * The user's escape hatch for promoted content the rules can't see — see
	 * [MutedVideos]. It cannot unwrite what is already on-chain; it stops the
	 * same video counting again when the feed brings it back.
	 */
	fun mute(videoId: String, label: String) {
		if (!initialised) return
		muted.mute(videoId, label)
		EventLog.append("engine", "muted $videoId — \"$label\" will not scrobble again")
	}

	fun unmute(videoId: String) {
		if (!initialised) return
		muted.unmute(videoId)
		EventLog.append("engine", "unmuted $videoId")
	}

	fun isMuted(videoId: String): Boolean = initialised && muted.isMuted(videoId)

	/** Muted ids with the labels they were muted under, for the UI list. */
	fun mutedVideos(): Map<String, String> = if (initialised) muted.all() else emptyMap()

	/** Already-resolved facts, memory/disk only — never the network. */
	fun cachedFacts(videoId: String): VideoFacts? =
		if (initialised) factsCache.get(videoId) else null

	/**
	 * The two facts the probe uses to disprove a latched video id, cache-only.
	 *
	 * Kept here rather than in the probe so the probe never learns about
	 * `VideoFacts`: it corroborates identity, it doesn't consume metadata.
	 */
	fun knownVideo(videoId: String): SessionProbe.KnownVideo? =
		cachedFacts(videoId)?.let {
			SessionProbe.KnownVideo(
				title = it.title,
				channel = it.author,
				lengthSeconds = it.lengthSeconds,
			)
		}

	/**
	 * Best-effort lookup of the video's own metadata.
	 *
	 * Returns null on every metadata failure path — disabled, network down, or
	 * markup changed. Video identity is a separate mandatory gate that has
	 * already passed before this method runs.
	 */
	private suspend fun enrich(videoId: String): VideoFacts? {
		if (!settings.enrichment) return null
		return runCatching { resolver.resolve(videoId) }.getOrElse {
			EventLog.append("enrich", "resolver threw: ${it.message}")
			null
		}
	}

	/**
	 * Search-based recovery of a video id the address bar never supplied.
	 *
	 * Gated behind the same "Look videos up" switch — it is off-device traffic
	 * — and fails closed. If the match is not certain, finalization records the
	 * item in Not logged and never constructs an on-chain entry.
	 */
	/**
	 * The playlist entry this session is playing, or null.
	 *
	 * Browsers supply the playlist id from the address bar. Native YouTube
	 * supplies only the playlist *name*, read off the watch screen, so it is
	 * resolved to an id here — once per playlist, cached including the misses.
	 * After the first fetch every remaining track in that playlist is free.
	 */
	private suspend fun nativePlaylistResolution(
		session: SessionSnapshot,
		title: String,
		durationSec: Long?,
	): VideoResolution? {
		val list = session.resolverContext.playlistId
			?: session.resolverContext.nativePlaylistName?.let { name ->
				idResolver.resolveNativePlaylistId(
					playlistName = name,
					ownerName = session.resolverContext.nativePlaylistOwner,
					total = session.resolverContext.nativePlaylistTotal,
				)
			}
			?: return null
		return idResolver.resolveEvidenceFromPlaylist(
			playlistId = list,
			title = title,
			channel = session.artist,
			durationSec = durationSec,
			seedVideoId = session.resolverContext.observedVideoId,
		)
	}

	private suspend fun watchHistoryResolution(
		session: SessionSnapshot,
		title: String?,
		durationSec: Long?,
		history: WatchHistorySource,
	): VideoResolutionAttempt? {
		if (!settings.watchHistory || !history.hasSession) return null
		val nativeYouTube = session.profile.packageProvesSource &&
			session.packageName == YouTubeProbe.YOUTUBE_PACKAGE
		// A browser session must be proven YouTube before its own account's
		// history is consulted about it; anything else is a different site.
		val browserYouTube = !session.profile.packageProvesSource && session.isYouTube
		if (!nativeYouTube && !browserYouTube) return null

		val establishedNativeVideo = durationSec != null && durationSec > 0 &&
			session.playedMs >= (durationSec * 1_000.0 * settings.scrobbleThreshold)
		val nativeAccountEvidence = nativeYouTube &&
			session.explicitAdSignal == null &&
			!session.hasShortSourceProof &&
			establishedNativeVideo

		val handle = session.ownerHandle
		if (handle != null) {
			var previousCandidates: List<String>? = null
			var lastAttempt: VideoResolutionAttempt? = null

			suspend fun attemptFromShortHistory(forceRefresh: Boolean): VideoResolutionAttempt? {
				val candidates = history.recentShortIds(
					title,
					forceRefresh,
					countsAsAccountEvidence = nativeYouTube,
				)
				if (candidates.isEmpty()) {
					previousCandidates = emptyList()
					return lastAttempt
				}
				// A history refresh is cheap; re-fetching the same eight candidate
				// watch pages is not. An unchanged candidate list cannot produce a new
				// corroboration result, so wait for the feed itself to change.
				if (candidates == previousCandidates) {
					EventLog.append(
						"history",
						"fresh Shorts feed still offers the same ${candidates.size} candidates; " +
							"skipping duplicate watch-page verification",
					)
					return lastAttempt
				}
				previousCandidates = candidates
				val attempt = idResolver.resolveVerifiedCandidates(
					videoIds = candidates,
					title = title,
					channel = session.artist,
					durationSec = durationSec,
					ownerHandle = handle,
				)
				lastAttempt = attempt
				// The account's own feed named this id and its own watch page then
				// agreed on title, owner and duration. That is the same fact an
				// ordinary-entry match establishes, and until now only the ordinary
				// list could report it — so a phone watching nothing but Shorts could
				// never clear a stand-down its ads had caused.
				// Native playback only. A browser Short landing in this account's feed
				// is not evidence about the native YouTube app's account, so it may
				// not clear a pause that native playback earned.
				if (attempt.resolution != null && nativeYouTube) history.recordShortCorroborated()
				EventLog.append(
					"history",
					attempt.resolution?.let {
						"resolved Short ${title?.let { t -> "\"$t\"" } ?: "with no readable title"} " +
							"→ ${it.videoId} from watch history, corroborated on its own watch page"
					} ?: "watch-history Short candidates did not corroborate " +
						"${title?.let { t -> "\"$t\"" } ?: "this untitled Short"}: " +
						attempt.refusalReason,
				)
				return attempt
			}

			val initial = attemptFromShortHistory(forceRefresh = false)
			if (initial?.resolution != null) return initial
			// Only a proven foreground Short receives the propagation window. The
			// route never delays an ordinary video or guesses that a browser page is
			// a Short. First bypass a possibly stale 15-second cache immediately.
			if (!nativeYouTube || !session.isForegroundShort) return initial
			var current = attemptFromShortHistory(forceRefresh = true)
			if (current?.resolution != null) return current
			for (waitMillis in SHORT_HISTORY_RETRY_DELAYS_MS) {
				EventLog.append(
					"history",
					"Short identity is not in the current feed; retrying a fresh history read " +
						"after ${waitMillis / 1000}s",
				)
				ports.historyRetryDelay.wait(waitMillis)
				if (!NativeSourceSwitches.isSnapshotCurrent(
						session.packageName,
						session.sourceEpoch,
					)
				) {
					return VideoResolutionAttempt(
						refusalReason = "source generation changed while watch history was catching up",
					)
				}
				current = attemptFromShortHistory(forceRefresh = true)
				if (current?.resolution != null) return current
			}
			return current ?: lastAttempt
		}

		// Every non-Short route still needs a title; only the handle path above
		// can stand without one.
		if (title == null) return null

		if (!session.profile.packageProvesSource) {
			// Browser-scoped: this read may not spend the probe a stood-down route
			// allows native playback, and its outcome is not evidence either way.
			// Deliberately *not* used to decide whether this track is a Short:
			// `recentShortIds` falls back to the most recent Shorts when no title
			// matches, so a non-empty list can be the feed's fallback rather than a
			// match, and an ordinary browser video would be classified as a Short by
			// nothing more than the feed having Shorts in it.
			val shortCandidates = history.recentShortIds(
				title,
				forceRefresh = false,
				countsAsAccountEvidence = false,
			)
			if (shortCandidates.isNotEmpty()) {
				val attempt = idResolver.resolveVerifiedCandidates(
					videoIds = shortCandidates,
					title = title,
					channel = session.artist,
					durationSec = durationSec,
				)
				EventLog.append(
					"history",
					attempt.resolution?.let {
						"resolved browser Short \"$title\" → ${it.videoId} from watch history, " +
							"corroborated on its own watch page"
					} ?: "watch-history Short candidates did not corroborate \"$title\": " +
						attempt.refusalReason,
				)
				if (attempt.resolution != null) return attempt
			}
		}
		return history.resolveEvidence(
			title = title,
			channel = session.artist,
			durationSec = durationSec,
			ownerHandle = null,
			// Four things reach here whose absence says nothing about which account
			// the *native YouTube app* is signed into:
			//
			// - an ad, which is not a watch-history row in any account;
			// - a Short, which the parser keeps in its own list and out of the
			//   ordinary entries, so its absence from that window is a property of
			//   the parser;
			// - anything played in a browser, which is not the native app at all;
			// - a video of unknown length, or one previewed below the threshold,
			//   which the account may simply not have recorded yet.
			countsAsAccountEvidence = nativeAccountEvidence,
		)
	}

	private fun frozenResolution(
		session: SessionSnapshot,
		confirmed: YouTubeProbe.Identity.Confirmed,
	): VideoResolution = VideoResolution(
		videoId = confirmed.videoId,
		source = "frozen ${confirmed.source}",
		title = session.resolverContext.knownTitle,
		channel = session.resolverContext.knownChannel,
		ownerHandle = session.ownerHandle,
		lengthSeconds = session.resolverContext.knownDurationSeconds,
	)

	private fun VideoResolutionAttempt.asRepositoryAttempt(): VideoIdentityRepositoryAttempt {
		val typedKind = when {
			resolution != null -> IdentityOutcomeKind.RESOLVED
			failure == VideoResolutionFailure.NOT_APPLICABLE -> IdentityOutcomeKind.NOT_APPLICABLE
			failure == VideoResolutionFailure.AMBIGUOUS -> IdentityOutcomeKind.AMBIGUOUS
			failure == VideoResolutionFailure.CONTRADICTION -> IdentityOutcomeKind.CONTRADICTION
			failure == VideoResolutionFailure.TEMPORARY_FAILURE ->
				IdentityOutcomeKind.TEMPORARY_FAILURE
			else -> IdentityOutcomeKind.NO_MATCH
		}
		return VideoIdentityRepositoryAttempt(
			resolution = resolution,
			kind = typedKind,
			diagnostic = refusalReason ?: resolution?.source ?: "identity route returned no diagnostic",
		)
	}

	private fun IdentityStrategyOutcome.asVideoAttempt(): VideoResolutionAttempt = when (this) {
		is IdentityStrategyOutcome.Resolved -> {
			val video = identity as? VideoResolution
			if (video != null) {
				VideoResolutionAttempt(resolution = video)
			} else {
				VideoResolutionAttempt(
					refusalReason = "resolved source identity was not a YouTube video",
					failure = VideoResolutionFailure.CONTRADICTION,
				)
			}
		}
		is IdentityStrategyOutcome.NotApplicable -> VideoResolutionAttempt(
			refusalReason = diagnostic.message,
			failure = VideoResolutionFailure.NOT_APPLICABLE,
		)
		is IdentityStrategyOutcome.NoMatch -> VideoResolutionAttempt(
			refusalReason = diagnostic.message,
			failure = VideoResolutionFailure.NO_MATCH,
		)
		is IdentityStrategyOutcome.Ambiguous -> VideoResolutionAttempt(
			refusalReason = diagnostic.message,
			failure = VideoResolutionFailure.AMBIGUOUS,
		)
		is IdentityStrategyOutcome.Contradiction -> VideoResolutionAttempt(
			refusalReason = diagnostic.message,
			failure = VideoResolutionFailure.CONTRADICTION,
		)
		is IdentityStrategyOutcome.TemporaryFailure -> VideoResolutionAttempt(
			refusalReason = diagnostic.message,
			failure = VideoResolutionFailure.TEMPORARY_FAILURE,
		)
	}

	private suspend fun typedFrozenExactAttempt(
		session: SessionSnapshot,
		durationSec: Long?,
		history: WatchHistorySource,
	): VideoResolutionAttempt {
		session.confirmed?.let {
			return VideoResolutionAttempt(resolution = frozenResolution(session, it))
		}
		val preResolvedNativeId = session.resolverContext.preResolvedNativeVideoId
			?: return VideoResolutionAttempt(
				refusalReason = "no frozen exact id",
				failure = VideoResolutionFailure.NOT_APPLICABLE,
			)
		if (!settings.enrichment) {
			return VideoResolutionAttempt(refusalReason = "video lookup is disabled")
		}
		val title = session.title
			?: return VideoResolutionAttempt(refusalReason = "the finalized title is missing")
		val artist = session.artist
			?: return VideoResolutionAttempt(refusalReason = "the finalized native artist is missing")
		val duration = durationSec
			?: return VideoResolutionAttempt(refusalReason = "the finalized native duration is missing")
		val preResolvedRoute = session.resolverContext.preResolvedNativeRoute
			?: return VideoResolutionAttempt(
				refusalReason = "pre-resolved native authority omitted its resolver route",
			)
		EventLog.append(
			"resolve",
			"re-fetching pre-resolved native carry authority $preResolvedNativeId " +
				"($preResolvedRoute) for \"$title\"",
		)
		val attempt = runCatching {
			when (preResolvedRoute) {
				NativePreResolvedRoute.STRUCTURED_MUSIC ->
					idResolver.revalidatePreResolvedNativeMusic(
						preResolvedNativeId, title, artist, duration,
					)
				NativePreResolvedRoute.MUSIC_VIDEO_ROW ->
					idResolver.revalidateMusicVideoRow(
						preResolvedNativeId, title, artist, duration,
					)
				NativePreResolvedRoute.RAW_TITLE_CHANNEL ->
					idResolver.resolveVerifiedCandidates(
						listOf(preResolvedNativeId), title, artist, duration,
					)
				NativePreResolvedRoute.HISTORY -> if (settings.watchHistory && history.hasSession) {
					history.revalidate(
						preResolvedNativeId,
						title,
						artist,
						duration,
						countsAsAccountEvidence = session.profile.packageProvesSource &&
							session.packageName == YouTubeProbe.YOUTUBE_PACKAGE &&
							session.explicitAdSignal == null &&
							!session.hasShortSourceProof &&
							session.playedMs >=
							(duration * 1_000.0 * settings.scrobbleThreshold),
					)
				} else {
					VideoResolutionAttempt(
						refusalReason = "watch history was disconnected while \"$title\" was playing, " +
							"so the id it supplied cannot be re-verified",
					)
				}
				NativePreResolvedRoute.PLAYLIST -> {
					val current = observedPlaylistAttempt(session, title, duration)
					val currentResolution = current.resolution
					when {
						currentResolution == null -> VideoResolutionAttempt(
							refusalReason = "pre-resolved playlist id $preResolvedNativeId " +
								"no longer matches any entry of the playlist being played",
						)
						currentResolution.videoId != preResolvedNativeId -> VideoResolutionAttempt(
							refusalReason = "playlist now resolves \"$title\" to " +
								"${currentResolution.videoId}, not the carried $preResolvedNativeId",
						)
						else -> current
					}
				}
			}
		}.getOrElse {
			VideoResolutionAttempt(
				refusalReason = "pre-resolved native revalidation failed: ${it.message}",
				failure = VideoResolutionFailure.TEMPORARY_FAILURE,
			)
		}
		if (attempt.resolution == null) {
			EventLog.append(
				"resolve",
				"carried $preResolvedNativeId ($preResolvedRoute) no longer revalidates " +
					"for \"$title\" — ${attempt.refusalReason}; asking the ordinary routes " +
					"rather than refusing the listen",
			)
			// A stale carry is one route losing authority, not a contradiction that
			// may veto independently corroborating lower-priority evidence.
			return attempt.copy(failure = VideoResolutionFailure.NO_MATCH)
		}
		return attempt
	}

	private suspend fun observedPlaylistAttempt(
		session: SessionSnapshot,
		title: String,
		durationSec: Long?,
	): VideoResolutionAttempt {
		val explicitList = session.resolverContext.playlistId
		val nativeName = session.resolverContext.nativePlaylistName
		if (explicitList == null && nativeName == null) {
			return VideoResolutionAttempt(
				refusalReason = "no observed playlist context",
				failure = VideoResolutionFailure.NOT_APPLICABLE,
			)
		}
		val list = explicitList ?: idResolver.resolveNativePlaylistId(
			playlistName = checkNotNull(nativeName),
			ownerName = session.resolverContext.nativePlaylistOwner,
			total = session.resolverContext.nativePlaylistTotal,
		) ?: return VideoResolutionAttempt(
			refusalReason = "the observed native playlist name did not resolve",
		)
		return idResolver.resolveEvidenceFromPlaylistAttempt(
			playlistId = list,
			title = title,
			channel = session.artist,
			durationSec = durationSec,
			seedVideoId = session.resolverContext.observedVideoId,
		)
	}

	private suspend fun resolveVideoId(
		session: SessionSnapshot,
		sequence: VerifiedPlaybackSequence,
		history: WatchHistorySource,
	): VideoResolutionAttempt {
		val durationSec = (
			session.resolverContext.presentationDurationMs ?: session.durationMs
		)?.div(1000)
		val shortHistoryOnly = session.isForegroundShort &&
			session.ownerHandle != null &&
			(session.title == null || durationSec == null)
		var searchPipeline: VideoResolutionAttempt? = null

		suspend fun searchAttempt(): VideoResolutionAttempt {
			searchPipeline?.let { return it }
			val computed = when {
				!settings.enrichment ->
					VideoResolutionAttempt(refusalReason = "video lookup is disabled")
				shortHistoryOnly -> VideoResolutionAttempt(
					refusalReason = "this Short's ${if (session.title == null) "title" else "length"} " +
						"could not be read and watch history could not identify it from what was left; " +
						(history.refusedBecause?.let { "the route is not running: $it" }
							?: "sign-in and the watch-history switch are what make these resolvable"),
				)
				session.title == null ->
					VideoResolutionAttempt(refusalReason = "the finalized title is missing")
				else -> runCatching {
					idResolver.resolveEvidenceAttempt(
						session.title,
						session.artist,
						durationSec,
						session.ownerHandle,
						allowStructuredNativeMusic = session.profile.packageProvesSource &&
							!session.isForegroundShort,
						allowYouTubeMusicCatalog =
							session.packageName == YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
						album = session.album,
						durationMs = session.resolverContext.presentationDurationMs
							?: session.durationMs,
					)
				}.getOrElse {
					EventLog.append("resolve", "resolver threw: ${it.message}")
					VideoResolutionAttempt(
						refusalReason = "resolver failed: ${it.message ?: "unknown error"}",
						failure = VideoResolutionFailure.TEMPORARY_FAILURE,
					)
				}
			}
			searchPipeline = computed
			return computed
		}

		val repository = object : YouTubeIdentityStrategyRepository {
			override suspend fun frozenExactId(): VideoIdentityRepositoryAttempt =
				typedFrozenExactAttempt(session, durationSec, history).asRepositoryAttempt()

			override suspend fun runLocalCandidate(): VideoIdentityRepositoryAttempt {
				if (!settings.enrichment || shortHistoryOnly || session.title == null) {
					return VideoIdentityRepositoryAttempt(
						kind = IdentityOutcomeKind.NOT_APPLICABLE,
						diagnostic = if (!settings.enrichment) {
							"video lookup is disabled"
						} else {
							"run-local candidate route did not apply"
						},
					)
				}
				val cachedIds = VerifiedIdentityCandidateCache.candidates(
					packageName = session.packageName,
					title = session.title,
					channel = session.artist,
					durationMs = session.durationMs,
					ownerHandle = session.ownerHandle,
				)
				if (cachedIds.isEmpty()) {
					return VideoIdentityRepositoryAttempt(
						kind = IdentityOutcomeKind.NO_MATCH,
						diagnostic = "no run-local verified candidate",
					)
				}
				EventLog.append(
					"resolve",
					"${cachedIds.size} run-local candidate(s) for \"${session.title}\" — re-fetching",
				)
				return runCatching {
					idResolver.resolveVerifiedCandidates(
						cachedIds,
						session.title,
						session.artist,
						durationSec,
						session.ownerHandle,
					)
				}.getOrElse {
					VideoResolutionAttempt(
						refusalReason = "run-local candidate re-fetch failed: ${it.message}",
						failure = VideoResolutionFailure.TEMPORARY_FAILURE,
					)
				}.asRepositoryAttempt()
			}

			override suspend fun consecutivePlaylist(): VideoIdentityRepositoryAttempt {
				if (!settings.enrichment || shortHistoryOnly || session.title == null) {
					return VideoIdentityRepositoryAttempt(
						kind = IdentityOutcomeKind.NOT_APPLICABLE,
						diagnostic = "consecutive playlist route did not apply",
					)
				}
				val predecessors = sequence.predecessors(session.trackInstance)
					?: return VideoIdentityRepositoryAttempt(
						kind = IdentityOutcomeKind.NOT_APPLICABLE,
						diagnostic = "two verified adjacent predecessors were unavailable",
					)
				return idResolver.resolveEvidenceFromAdjacentPredecessorsAttempt(
					firstVideoId = predecessors.first.videoId,
					secondVideoId = predecessors.second.videoId,
					firstTitle = predecessors.first.title,
					secondTitle = predecessors.second.title,
					title = session.title,
					channel = session.artist,
					durationSec = durationSec,
				).asRepositoryAttempt()
			}

			override suspend fun observedPlaylist(): VideoIdentityRepositoryAttempt {
				if (!settings.enrichment || shortHistoryOnly || session.title == null) {
					return VideoIdentityRepositoryAttempt(
						kind = IdentityOutcomeKind.NOT_APPLICABLE,
						diagnostic = "observed playlist route did not apply",
					)
				}
				return observedPlaylistAttempt(session, session.title, durationSec)
					.asRepositoryAttempt()
			}

			override suspend fun watchHistory(): VideoIdentityRepositoryAttempt {
				if (!settings.enrichment) {
					return VideoIdentityRepositoryAttempt(
						kind = IdentityOutcomeKind.NOT_APPLICABLE,
						diagnostic = "video lookup is disabled",
					)
				}
				val attempt = watchHistoryResolution(
					session, session.title, durationSec, history,
				)
				if (attempt != null) return attempt.asRepositoryAttempt()
				return VideoIdentityRepositoryAttempt(
					kind = IdentityOutcomeKind.NOT_APPLICABLE,
					diagnostic = "watch history was disconnected or not applicable",
				)
			}

			override suspend fun structuredNativeMusic(): VideoIdentityRepositoryAttempt {
				if (!settings.enrichment || shortHistoryOnly ||
					!session.profile.packageProvesSource || session.isForegroundShort
				) {
					return VideoIdentityRepositoryAttempt(
						kind = IdentityOutcomeKind.NOT_APPLICABLE,
						diagnostic = "structured native music route did not apply",
					)
				}
				val attempt = searchAttempt()
				return if (attempt.resolution?.structuredNativeMusic == true) {
					attempt.asRepositoryAttempt()
				} else {
					VideoIdentityRepositoryAttempt(
						kind = IdentityOutcomeKind.NOT_APPLICABLE,
						diagnostic = "the legacy search repository selected no structured native result",
					)
				}
			}

			override suspend fun search(): VideoIdentityRepositoryAttempt =
				searchAttempt().asRepositoryAttempt()
		}

		val result = ProductionIdentityStrategies.chain().resolve(
			ProductionIdentityContext(
				publishedIdentity = session.identity,
				youTube = YouTubeIdentityContext,
				repository = repository,
			),
		)
		return result.outcome.asVideoAttempt()
	}

	private suspend fun resolveVideoIdLegacy(
		session: SessionSnapshot,
		sequence: VerifiedPlaybackSequence,
		history: WatchHistorySource,
	): VideoResolutionAttempt {
		if (!settings.enrichment) {
			return VideoResolutionAttempt(refusalReason = "video lookup is disabled")
		}
		// Threshold measurement deliberately keeps the longest duration established
		// for one logical listen. Identity must instead corroborate the exact item
		// currently presented by the source (for example YouTube Music's shorter
		// Song item after its longer Video item), or the resolver reacquires the
		// wrong presentation's id.
		val durationSec = (
			session.resolverContext.presentationDurationMs ?: session.durationMs
			)?.div(1000)
		// A foreground Short may legitimately arrive with no title: the footer
		// lost its resource ids and YouTube keeps restyling it, so the title is
		// the least reliable thing on screen. Its owner handle and its seekbar
		// duration are not, and the watch-history route resolves on exactly those.
		// Every other source still requires a title, because nothing else has a
		// second discriminator to fall back on.
		// A foreground Short now reaches here in three shapes: with a title and a
		// length, with a length but no title (the footer restyle), and — since
		// YouTube stopped rendering the Shorts seekbar — with a title but no
		// length at all. Watch history is the only route that can answer any of
		// them, because it is the only one that knows what this account played.
		if (session.isForegroundShort &&
			session.ownerHandle != null &&
			(session.title == null || durationSec == null)
		) {
			// Straight to watch history: the search and playlist routes below all
			// need a title *and* a length to query with, and the pre-resolved
			// carry can only exist for a Short that already had both. History
			// needs neither — it names what this account played, and whichever of
			// the two fields survived still has to match, uniquely, on the
			// candidate's own watch page.
			val missing = if (session.title == null) "title" else "length"
			return watchHistoryResolution(session, session.title, durationSec, history)
				?: VideoResolutionAttempt(
					refusalReason = "this Short's $missing could not be read and watch history " +
						"could not identify it from what was left; " +

						(
							history.refusedBecause?.let { "the route is not running: $it" }
								?: "sign-in and the watch-history switch are what make these resolvable"
							),
				)
		}
		val title = session.title
			?: return VideoResolutionAttempt(refusalReason = "the finalized title is missing")
		val preResolvedNativeId = session.resolverContext.preResolvedNativeVideoId
		if (preResolvedNativeId != null) {
			val artist = session.artist ?: return VideoResolutionAttempt(
				refusalReason = "the finalized native artist is missing",
			)
			val duration = durationSec ?: return VideoResolutionAttempt(
				refusalReason = "the finalized native duration is missing",
			)
			val preResolvedRoute = session.resolverContext.preResolvedNativeRoute
				?: return VideoResolutionAttempt(
					refusalReason = "pre-resolved native authority omitted its resolver route",
				)
			EventLog.append(
				"resolve",
				"re-fetching pre-resolved native carry authority $preResolvedNativeId " +
					"($preResolvedRoute) for \"$title\"",
			)
			val carryAttempt = runCatching {
				when (preResolvedRoute) {
					NativePreResolvedRoute.STRUCTURED_MUSIC ->
						idResolver.revalidatePreResolvedNativeMusic(
							preResolvedNativeId, title, artist, duration,
						)
					NativePreResolvedRoute.MUSIC_VIDEO_ROW ->
						idResolver.revalidateMusicVideoRow(
							preResolvedNativeId, title, artist, duration,
						)
					NativePreResolvedRoute.RAW_TITLE_CHANNEL ->
						idResolver.resolveVerifiedCandidates(
							listOf(preResolvedNativeId), title, artist, duration,
						)

					// Re-ask the feed that produced it. History is a live list,
					// so a listen it has since re-described must refuse rather
					// than carry a stale answer onto a chain nothing can edit.
					NativePreResolvedRoute.HISTORY -> {
						val revalidated = if (settings.watchHistory && history.hasSession) {
							history.revalidate(
								preResolvedNativeId,
								title,
								artist,
								duration,

								countsAsAccountEvidence = session.profile.packageProvesSource &&
									session.packageName == YouTubeProbe.YOUTUBE_PACKAGE &&
									session.explicitAdSignal == null &&
									!session.hasShortSourceProof &&
									session.playedMs >=
									(duration * 1_000.0 * settings.scrobbleThreshold),
							)
						} else {
							VideoResolutionAttempt(
								refusalReason = "watch history was disconnected while " +
									"\"$title\" was playing, so the id it supplied " +
									"cannot be re-verified",
							)
						}
						revalidated
					}

					// Re-verify against the authority that produced it. The
					// playlist entry list is already cached, so this is a lookup,
					// and requiring the same id back preserves the immutability
					// the carry depends on.
					NativePreResolvedRoute.PLAYLIST -> {
						val revalidated = nativePlaylistResolution(session, title, duration)
						when {
							revalidated == null -> VideoResolutionAttempt(
								refusalReason = "pre-resolved playlist id $preResolvedNativeId " +
									"no longer matches any entry of the playlist being played",
							)

							revalidated.videoId != preResolvedNativeId -> VideoResolutionAttempt(
								refusalReason = "playlist now resolves \"$title\" to " +
									"${revalidated.videoId}, not the carried $preResolvedNativeId",
							)

							else -> VideoResolutionAttempt(resolution = revalidated)
						}
					}
				}
			}.getOrElse {
				VideoResolutionAttempt(
					refusalReason = "pre-resolved native revalidation failed: ${it.message}",
				)
			}
			if (carryAttempt.resolution != null) return carryAttempt

			EventLog.append(
				"resolve",
				"carried $preResolvedNativeId ($preResolvedRoute) no longer revalidates " +
					"for \"$title\" — ${carryAttempt.refusalReason}; asking the ordinary " +
					"routes rather than refusing the listen",
			)
		}
		return runCatching {
			val cachedIds = VerifiedIdentityCandidateCache.candidates(
				packageName = session.packageName,
				title = session.title,
				channel = session.artist,
				durationMs = session.durationMs,
				ownerHandle = session.ownerHandle,
			)
			if (cachedIds.isNotEmpty()) {
				EventLog.append(
					"resolve",
					"${cachedIds.size} run-local candidate(s) for \"$title\" — re-fetching",
				)
				val cachedAttempt = idResolver.resolveVerifiedCandidates(
					cachedIds, title, session.artist, durationSec, session.ownerHandle,
				)
				if (cachedAttempt.resolution != null ||
					cachedAttempt.failure == VideoResolutionFailure.AMBIGUOUS
				) {
					return@runCatching cachedAttempt
				}
				EventLog.append(
					"resolve",
					"run-local recovery did not corroborate — continuing to playlist/search",
				)
			}
			// When neither source can publish the current id, recover the public
			// playlist from the immediately preceding two verified uploads. This is
			// shared by Brave and the native YouTube app and needs no visible UI.
			// It deliberately precedes the carried playlist context: if playback
			// moved to a new list while the browser was backgrounded, that carried
			// id is the stale list from the exact gap this route repairs.
			val predecessors = sequence.predecessors(session.trackInstance)
			EventLog.append(
				"sequence",
				"${session.packageName} predecessor lookup for start=${session.trackStartedAtEpochSec}: " +
					"${predecessors ?: "unavailable"}; " +
					sequence.describe(session.packageName),
			)
			predecessors?.let { (first, second) ->
				idResolver.resolveEvidenceFromAdjacentPredecessors(
					firstVideoId = first.videoId,
					secondVideoId = second.videoId,
					firstTitle = first.title,
					secondTitle = second.title,
					title = title,
					channel = session.artist,
					durationSec = durationSec,
				)?.let { recovered ->
					return@runCatching VideoResolutionAttempt(resolution = recovered)
				}
			}

			// The playlist is exact where search is only plausible, and after
			// the first fetch it costs nothing for the rest of the playlist.
			val playlistResolution = nativePlaylistResolution(session, title, durationSec)
			if (playlistResolution != null) {
				return@runCatching VideoResolutionAttempt(resolution = playlistResolution)
			}

			// Then the account's own watch history, which is the only route that
			// names an exact id for native playback with no playlist around it —
			// a single video, or anything played with the screen off. Ahead of
			// search because search has been measured choosing a
			// duration-identical wrong upload (§10.1); behind the playlist
			// because the playlist needs no credentials and is already proven.
			val historyAttempt = watchHistoryResolution(session, title, durationSec, history)
			if (historyAttempt?.resolution != null) {
				return@runCatching historyAttempt
			}

			idResolver.resolveEvidenceAttempt(
				title,
				session.artist,
				durationSec,
				session.ownerHandle,
				allowStructuredNativeMusic = session.profile.packageProvesSource &&
					!session.isForegroundShort,
				allowYouTubeMusicCatalog =
					session.packageName == YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
				// Only YouTube Music publishes a real album, and only its catalog
				// route reads this. The YouTube app puts the channel in the artist
				// slot and nothing in the album slot, so nothing here changes for
				// it or for the browser.
				album = session.album,

				durationMs = session.resolverContext.presentationDurationMs
					?: session.durationMs,
			)
		}.getOrElse {
			EventLog.append("resolve", "resolver threw: ${it.message}")
			VideoResolutionAttempt(refusalReason = "resolver failed: ${it.message ?: "unknown error"}")
		}
	}

	/** Monitoring/package reset boundary for the memory-only identity index. */
	fun clearVerifiedIdentityCandidates(packageName: String? = null) {
		if (packageName == null) VerifiedIdentityCandidateCache.clearAll()
		else VerifiedIdentityCandidateCache.clear(packageName)
		verifiedPlaybackSequence.clear(packageName)
		// The history route's "your app is on another account" diagnosis is a
		// run of consecutive misses; a monitoring or package boundary makes the
		// old run meaningless, so it starts over rather than carrying a verdict
		// across a state change the user may have made to fix it.
		if (initialised && packageName == null) history.reset()
	}

	// ---- watch history, the user-facing surface --------------------------------

	/** Whether an account is connected for watch-history lookups. */
	fun youTubeSession(): YouTubeSessionVault.Session? =
		if (initialised) youtubeSession.session else null

	fun watchHistoryEnabled(): Boolean = initialised && settings.watchHistory

	fun setWatchHistoryEnabled(enabled: Boolean) {
		if (!initialised) return
		settings.watchHistory = enabled
		history.reset()
		EventLog.append(
			"history",
			"watch-history lookups ${if (enabled) "on" else "off"}",
		)
	}

	/** The exact reason the route is standing down, or null when it is running. */
	fun watchHistoryRefusal(): String? = if (initialised) history.refusedBecause else null

	/**
	 * The same refusal as a typed cause, for a surface that has to offer the
	 * right recovery. [watchHistoryRefusal] is prose for a person to read and is
	 * never branched on.
	 */
	fun watchHistoryRefusalKind(): WatchHistoryHealth.Refusal? =
		if (initialised) history.refusedAs else null

	/**
	 * Store a session the user signed in for. The cookie never passes through a
	 * log, a state flow or the UI — only this call, and only into the vault.
	 */
	fun connectYouTubeSession(cookieHeader: String, accountLabel: String?) {
		if (!initialised) return
		youtubeSession.save(cookieHeader, accountLabel)
		settings.watchHistory = true
		history.reset()
		EventLog.append(
			"history",
			"connected a YouTube account for watch-history lookups" +
				(accountLabel?.let { " (@$it)" } ?: "") + "; the session is stored encrypted " +
				"and is never logged or sent anywhere but youtube.com",
		)
	}

	/** Prove a freshly connected session can actually read history. */
	suspend fun probeWatchHistory(): WatchHistoryResolver.Probe =
		if (initialised) {
			history.probe()
		} else {
			WatchHistoryResolver.Probe.Faulted(null, "the engine is not initialised")
		}

	fun disconnectYouTubeSession() {
		if (!initialised) return
		youtubeSession.forget()
		settings.watchHistory = false
		history.reset()
		EventLog.append("history", "YouTube account disconnected and its session wiped")
	}

	/** Retry anything waiting in the queue. Safe to call often. */
	fun flushQueue() {
		if (!initialised) return
		dispatcher.retryDue()
	}

	/** Transport-only queue retry. No finalized listen can enter this method. */
	private fun retryQueuedPayloads() {
		scope.launch {
			broadcastLock.withLock {
				val account = vault.account ?: return@withLock
				val key = vault.loadKey() ?: return@withLock
				for (entry in queue.due()) {
					if (!entry.username.equals(account.username, ignoreCase = true)) {
						EventLog.append(
							"queue",
							"waiting for @${entry.username}: current key belongs to " +
								"@${account.username}; entry left untouched",
						)
						continue
					}

					when (entry.state) {
						BroadcastQueue.State.QUEUED -> prepareQueuedEntry(entry, key)
						BroadcastQueue.State.IN_FLIGHT -> reconcileInFlight(entry, key)
						BroadcastQueue.State.SETTLED -> Unit
					}
				}
				_queueSize.value = queue.size()
				if (account.username.isNotEmpty()) ledger.prune()
			}
		}
	}

	private fun prepareQueuedEntry(entry: BroadcastQueue.Entry, key: com.rustedwax.hive.HiveKey) {
		when (val preparation = broadcaster.prepareJson(entry.username, key, entry.json)) {
			is HivePreparationResult.Failed -> when (val result = preparation.result) {
				is HiveRpc.BroadcastResult.NetworkFailure -> handleQueuedFailure(entry, result.message)
				is HiveRpc.BroadcastResult.Deferred -> handleQueuedFailure(entry, result.message)
				is HiveRpc.BroadcastResult.Rejected -> retireRejected(entry, result.message)
				is HiveRpc.BroadcastResult.Success,
				is HiveRpc.BroadcastResult.AcceptedUnconfirmed,
				-> EventLog.append("queue", "invalid preparation result; queued operation left untouched")
			}

			is HivePreparationResult.Ready -> {
				if (!persistBeforeBroadcast(entry, preparation.transaction)) return
				broadcastPrepared(entry, preparation.transaction, initialAttempt = false)
			}
		}
	}

	/** An IN_FLIGHT row never authorizes a new transaction without reconciliation. */
	private fun reconcileInFlight(entry: BroadcastQueue.Entry, key: com.rustedwax.hive.HiveKey) {
		val prepared = entry.preparedTransaction()
		if (prepared == null) {
			EventLog.append(
				"queue",
				"CORRUPT IN_FLIGHT state for ${entry.label}; automatic broadcast blocked",
			)
			return
		}

		val evidence = runCatching {
			broadcaster.observeTransaction(prepared.txId, prepared.expirationEpochSec)
		}
			.getOrDefault(HiveRpc.TransactionEvidence.UNAVAILABLE)
		when (evidence) {
			HiveRpc.TransactionEvidence.BLOCK,
			HiveRpc.TransactionEvidence.MEMPOOL,
			-> {
				val settled = queue.settle(entry)
				EventLog.append(
					"queue",
					"reconciled prepared transaction ${prepared.txId} as ${evidence.name.lowercase()}",
				)
				logSettlement(settled, entry.label)
			}

			HiveRpc.TransactionEvidence.UNAVAILABLE -> EventLog.append(
				"queue",
				"prepared transaction ${prepared.txId} could not be reconciled; left fail-closed",
			)

			HiveRpc.TransactionEvidence.ABSENT -> {
				if (clock.nowEpochSeconds() < prepared.expirationEpochSec) {
					// Exact same signed transaction and id; never a replacement.
					broadcastPrepared(entry, prepared, initialAttempt = false)
				} else {
					// Independent nodes established expired-irreversible absence using
					// the saved expiration. Only now may a replacement be persisted.
					prepareQueuedEntry(entry.copy(state = BroadcastQueue.State.QUEUED), key)
				}
			}
		}
	}

	private fun persistBeforeBroadcast(
		entry: BroadcastQueue.Entry,
		prepared: PreparedHiveTransaction,
	): Boolean = when (queue.markInFlight(entry, prepared)) {
		BroadcastQueue.PrepareOutcome.STORED -> true
		BroadcastQueue.PrepareOutcome.NOT_FOUND -> {
			EventLog.append("queue", "queued operation disappeared before preparation; nothing sent")
			false
		}
		BroadcastQueue.PrepareOutcome.STORAGE_ERROR -> {
			EventLog.append(
				"queue",
				"STORAGE ERROR persisting PREPARED/IN_FLIGHT state for ${entry.label}; nothing sent",
			)
			false
		}
	}

	private fun broadcastPrepared(
		entry: BroadcastQueue.Entry,
		prepared: PreparedHiveTransaction,
		initialAttempt: Boolean,
		onFeedback: ((String, Boolean) -> Unit)? = null,
	) {
		val result = runCatching { broadcaster.broadcastPrepared(prepared) }
			.getOrElse { HiveRpc.BroadcastResult.NetworkFailure(it.message ?: "error") }
		when (result) {
			is HiveRpc.BroadcastResult.Success -> {
				val settled = queue.settle(entry)
				val evidence = result.evidence.name.lowercase()
				EventLog.append(
					"engine",
					if (initialAttempt) {
						"scrobbled ($evidence): ${entry.label} — tx ${result.txId}"
					} else {
						"queued scrobble sent ($evidence): ${entry.label} — tx ${result.txId}"
					},
				)
				logSettlement(settled, entry.label)
				note(
					entry.username,
					entry.label,
					if (initialAttempt) {
						if (result.evidence == HiveRpc.BroadcastResult.Evidence.BLOCK) {
							"confirmed in block" + settlementSuffix(settled)
						} else {
							"seen relaying in mempool" + settlementSuffix(settled)
						}
					} else {
						"sent from queue" + settlementSuffix(settled)
					},
					result.txId,
					entry.percentPlayed,
					videoId = entry.videoId,
				)
				onFeedback?.invoke("Confirmed in a block — tx ${result.txId}", false)
			}

			is HiveRpc.BroadcastResult.AcceptedUnconfirmed -> {
				val settled = queue.settle(entry)
				EventLog.append(
					"engine",
					if (initialAttempt) {
						"accepted but confirmation unavailable: ${entry.label} — tx ${result.txId}"
					} else {
						"queued scrobble accepted but confirmation unavailable: " +
							"${entry.label} — tx ${result.txId}"
					},
				)
				logSettlement(settled, entry.label)
				note(
					entry.username,
					entry.label,
					if (initialAttempt) {
						"accepted — confirmation unavailable; not retried" + settlementSuffix(settled)
					} else {
						"accepted — confirmation unavailable" + settlementSuffix(settled)
					},
					result.txId,
					entry.percentPlayed,
					videoId = entry.videoId,
				)
				onFeedback?.invoke(
					"Accepted, but confirmation was unavailable — do not retry" +
						(result.txId?.let { " — tx $it" } ?: ""),
					false,
				)
			}

			is HiveRpc.BroadcastResult.Rejected -> {
				retireRejected(entry, result.message, initialAttempt)
				onFeedback?.invoke("Chain rejected it: ${result.message}", true)
			}

			is HiveRpc.BroadcastResult.Deferred -> {
				if (initialAttempt) {
					recordInitialQueueFailure(entry, "waiting to retry — ${result.message}")
				} else {
					handleQueuedFailure(entry, result.message)
				}
			}

			is HiveRpc.BroadcastResult.NetworkFailure -> {
				if (initialAttempt) {
					recordInitialQueueFailure(entry, "queued — offline")
				} else {
					handleQueuedFailure(entry, result.message)
				}
			}
		}
	}

	private fun recordInitialQueueFailure(entry: BroadcastQueue.Entry, status: String) {
		EventLog.append("engine", "$status: ${entry.label}")
		note(
			entry.username,
			entry.label,
			status,
			null,
			entry.percentPlayed,
			queued = true,
			videoId = entry.videoId,
		)
	}

	private fun retireRejected(
		entry: BroadcastQueue.Entry,
		message: String,
		initialAttempt: Boolean = false,
	) {
		val settled = queue.settle(entry)
		EventLog.append(
			"engine",
			if (initialAttempt) "rejected: $message" else "queued scrobble dropped (rejected): $message",
		)
		logSettlement(settled, entry.label)
		note(
			entry.username,
			entry.label,
			(if (initialAttempt) "rejected: $message" else "queue failed permanently: $message") +
				settlementSuffix(settled),
			null,
			entry.percentPlayed,
			videoId = entry.videoId,
		)
	}

	/**
	 * Say which durable fail-closed state remains when settled cleanup cannot
	 * complete. Both outcomes survive restart; neither relies on a diagnostic.
	 */
	private fun logSettlement(outcome: BroadcastQueue.SettleOutcome, label: String) {
		when (outcome) {
			BroadcastQueue.SettleOutcome.RETIRED -> Unit

			BroadcastQueue.SettleOutcome.SETTLED_DURABLY -> EventLog.append(
				"queue",
				"STORAGE ERROR removing settled retry state: $label. " +
					"SETTLED remains durable and cannot broadcast.",
			)

			BroadcastQueue.SettleOutcome.IN_FLIGHT_RETAINED -> EventLog.append(
				"queue",
				"STORAGE FAILURE settling a sent scrobble: $label. The exact durable " +
					"IN_FLIGHT transaction remains fail-closed for reconciliation.",
			)
		}
	}

	private fun settlementSuffix(outcome: BroadcastQueue.SettleOutcome): String = when (outcome) {
		BroadcastQueue.SettleOutcome.RETIRED -> ""
		BroadcastQueue.SettleOutcome.SETTLED_DURABLY -> " — settled cleanup remains pending"
		BroadcastQueue.SettleOutcome.IN_FLIGHT_RETAINED ->
			" — exact in-flight transaction retained for reconciliation"
	}

	private fun handleQueuedFailure(entry: BroadcastQueue.Entry, message: String) {
		when (queue.recordFailure(entry, message)) {
			BroadcastQueue.FailureOutcome.RETAINED ->
				EventLog.append("engine", "queued scrobble still waiting: $message")

			BroadcastQueue.FailureOutcome.DROPPED -> {
				EventLog.append(
					"engine",
					"queued scrobble exhausted retry limit and was removed: ${entry.label}",
				)
				note(
					entry.username,
					entry.label,
					"failed after 8 queued attempts: $message",
					null,
					entry.percentPlayed,
					videoId = entry.videoId,
				)
			}

			BroadcastQueue.FailureOutcome.AWAITING_RECONCILIATION ->
				resolveExhaustedInFlight(entry, message)

			BroadcastQueue.FailureOutcome.NOT_FOUND ->
				EventLog.append("queue", "retry entry disappeared before failure could be recorded")

			BroadcastQueue.FailureOutcome.STORAGE_ERROR -> {
				EventLog.append(
					"queue",
					"STORAGE ERROR recording retry failure for ${entry.label}",
				)
				note(
					entry.username,
					entry.label,
					"retry state could not be persisted — export the log",
					null,
					entry.percentPlayed,
					videoId = entry.videoId,
				)
			}
		}
	}

	/**
	 * Preserve the eight-attempt ceiling without discarding an ambiguous send.
	 * Proven absence closes it as a genuine failure; positive evidence settles it;
	 * unavailable evidence leaves the exact transaction fail-closed.
	 */
	private fun resolveExhaustedInFlight(entry: BroadcastQueue.Entry, message: String) {
		val prepared = entry.preparedTransaction()
		if (prepared == null) {
			EventLog.append("queue", "retry ceiling reached with corrupt IN_FLIGHT state; left fail-closed")
			return
		}
		when (runCatching {
			broadcaster.observeTransaction(prepared.txId, prepared.expirationEpochSec)
		}
			.getOrDefault(HiveRpc.TransactionEvidence.UNAVAILABLE)) {
			HiveRpc.TransactionEvidence.BLOCK,
			HiveRpc.TransactionEvidence.MEMPOOL,
			-> {
				val settled = queue.settle(entry)
				logSettlement(settled, entry.label)
				note(
					entry.username,
					entry.label,
					"reconciled after ambiguous retry" + settlementSuffix(settled),
					prepared.txId,
					entry.percentPlayed,
					videoId = entry.videoId,
				)
			}

			HiveRpc.TransactionEvidence.ABSENT -> {
				val settled = queue.settle(entry)
				logSettlement(settled, entry.label)
				EventLog.append(
					"engine",
					"queued scrobble exhausted retry limit and was absent: ${entry.label}",
				)
				note(
					entry.username,
					entry.label,
					"failed after 8 queued attempts: $message" + settlementSuffix(settled),
					null,
					entry.percentPlayed,
					videoId = entry.videoId,
				)
			}

			HiveRpc.TransactionEvidence.UNAVAILABLE -> EventLog.append(
				"queue",
				"retry ceiling reached for ${entry.label}, but transaction status is unavailable; " +
					"exact IN_FLIGHT state retained",
			)
		}
	}

	private fun enqueueAndSend(
		payload: HiveScrobblePayload,
		videoId: String?,
		sourcePackage: String?,
		sourceEpoch: Long?,
		automaticTransportCommitted: Boolean,
		trigger: FinalizationTrigger,
		onFeedback: ((String, Boolean) -> Unit)?,
	) {
		// Automatic dispatch is transport-only after the use case's linearizable
		// commit. Refuse a miswired caller before account access, privacy-secret
		// derivation, key loading, queueing or the broadcaster seam.
		if (trigger == FinalizationTrigger.AUTOMATIC && !automaticTransportCommitted) {
			EventLog.append(
				"engine",
				"automatic transport reached dispatch without a committed authorization — broadcast cancelled",
			)
			onFeedback?.invoke("Automatic transport was not authorized. Nothing sent.", true)
			return
		}
		val account = vault.account
		if (account == null) {
			EventLog.append("engine", "no key saved — scrobble dropped")
			onFeedback?.invoke("No key saved — add one on the Account tab first.", true)
			return
		}
		val label = "${payload.artist?.plus(" — ") ?: ""}${payload.title}"
		// §6.1. When privacy is on for this kind, what goes on chain is the
		// envelope, not the payload — and if the envelope cannot be built the
		// listen is dropped rather than published in the clear. A ledger has no
		// undo, so "we could not encrypt it, so we sent it anyway" is the one
		// outcome this feature must never produce.
		val category = PrivateScrobble.categoryFor(payload.kind)
		val json = if (settings.privacyEnabledFor(category)) {
			val envelope = PrivateScrobble.envelope(payload, vault.privacySecret())
			if (envelope == null) {
				EventLog.append(
					"privacy",
					"private ${payload.kind} scrobble held back: the privacy key could not be " +
						"derived, and a private listen is never broadcast in the clear",
				)
				onFeedback?.invoke("Private scrobble could not be encrypted. Nothing sent.", true)
				return
			}
			EventLog.append("privacy", "encrypted a ${payload.kind} scrobble before broadcast")
			envelope
		} else {
			payload.toJson()
		}

		scope.launch {
			broadcastLock.withLock {
				if (sourcePackage != null &&
					!NativeSourceSwitches.isSnapshotCurrent(sourcePackage, sourceEpoch)
				) {
					EventLog.append(
						"native",
						"$sourcePackage opt-in/lifecycle changed before signing — broadcast cancelled",
					)
					onFeedback?.invoke("That source was disabled or reset. Nothing sent.", true)
					return@withLock
				}
				val key = vault.loadKey()
				if (key == null) {
					EventLog.append("engine", "key unreadable — scrobble dropped")
					onFeedback?.invoke("The posting key could not be read. Nothing sent.", true)
					return@withLock
				}
				EventLog.append("engine", "broadcasting: $json")
				if (trigger == FinalizationTrigger.MANUAL) {
					broadcastDirect(account.username, key, json, label, payload, videoId, onFeedback)
					return@withLock
				}

				// Automatic transport is write-ahead: even an ordinary online send
				// becomes a queue operation before signing/broadcast can cross the
				// irreversible boundary. A failed queue write means nothing is sent.
				val entry = queue.enqueue(
					account.username,
					json,
					label,
					payload.percentPlayed,
					videoId,
				)
				if (entry == null) {
					EventLog.append("queue", "QUEUE STORAGE FAILURE before broadcast: $label; nothing sent")
					note(
						account.username,
						label,
						"failed to persist pre-send state — nothing sent",
						null,
						payload.percentPlayed,
						videoId = videoId,
					)
					onFeedback?.invoke("Retry state could not be persisted. Nothing sent.", true)
					return@withLock
				}

				when (val preparation = broadcaster.prepareJson(account.username, key, json)) {
					is HivePreparationResult.Ready -> {
						if (persistBeforeBroadcast(entry, preparation.transaction)) {
							broadcastPrepared(
								entry,
								preparation.transaction,
								initialAttempt = true,
								onFeedback = onFeedback,
							)
						}
					}

					is HivePreparationResult.Failed -> when (val failure = preparation.result) {
						is HiveRpc.BroadcastResult.NetworkFailure ->
							recordInitialQueueFailure(entry, "queued — offline")
						is HiveRpc.BroadcastResult.Deferred ->
							recordInitialQueueFailure(entry, "waiting to retry — ${failure.message}")
						is HiveRpc.BroadcastResult.Rejected -> retireRejected(entry, failure.message, true)
						is HiveRpc.BroadcastResult.Success,
						is HiveRpc.BroadcastResult.AcceptedUnconfirmed,
						-> EventLog.append("queue", "invalid preparation result; operation left queued")
					}
				}
				_queueSize.value = queue.size()
			}
		}
	}

	/** Manual transport keeps its established no-queue behavior. */
	private fun broadcastDirect(
		username: String,
		key: com.rustedwax.hive.HiveKey,
		json: String,
		label: String,
		payload: HiveScrobblePayload,
		videoId: String?,
		onFeedback: ((String, Boolean) -> Unit)?,
	) {
		val result = runCatching { broadcaster.broadcastJson(username, key, json) }
			.getOrElse { HiveRpc.BroadcastResult.NetworkFailure(it.message ?: "error") }
		when (result) {
			is HiveRpc.BroadcastResult.Success -> {
				val evidence = result.evidence.name.lowercase()
				EventLog.append("engine", "scrobbled ($evidence): $label — tx ${result.txId}")
				note(
					username,
					label,
					if (result.evidence == HiveRpc.BroadcastResult.Evidence.BLOCK) {
						"confirmed in block"
					} else {
						"seen relaying in mempool"
					},
					result.txId,
					payload.percentPlayed,
					videoId = videoId,
				)
				onFeedback?.invoke("Confirmed in a block — tx ${result.txId}", false)
			}
			is HiveRpc.BroadcastResult.AcceptedUnconfirmed -> {
				EventLog.append("engine", "accepted but confirmation unavailable: $label — tx ${result.txId}")
				note(
					username,
					label,
					"accepted — confirmation unavailable; not retried",
					result.txId,
					payload.percentPlayed,
					videoId = videoId,
				)
				onFeedback?.invoke(
					"Accepted, but confirmation was unavailable — do not retry" +
						(result.txId?.let { " — tx $it" } ?: ""),
					false,
				)
			}
			is HiveRpc.BroadcastResult.Rejected -> {
				EventLog.append("engine", "rejected: ${result.message}")
				note(
					username,
					label,
					"rejected: ${result.message}",
					null,
					payload.percentPlayed,
					videoId = videoId,
				)
				onFeedback?.invoke("Chain rejected it: ${result.message}", true)
			}
			is HiveRpc.BroadcastResult.Deferred -> onFeedback?.invoke(
				"Not on-chain yet — ${result.message}. Try again in a moment.",
				true,
			)
			is HiveRpc.BroadcastResult.NetworkFailure ->
				onFeedback?.invoke("Couldn't reach a node: ${result.message}", true)
		}
	}

	private fun skip(
		report: FinalizationReport,
		session: SessionSnapshot,
		reason: String,
		durationMs: Long? = session.durationMs,
		log: Boolean = true,
		videoId: String? = session.confirmed?.videoId,
		resolvedTitle: String? = null,
	) {
		// Filed first, and unconditionally. Every early return below is about
		// whether the *user* is shown this row; none of them are about whether the
		// track was refused. Reporting after those guards is how a refusal that is
		// deliberately invisible — a session never proven to be YouTube, a
		// sub-three-second metadata transition — would have become a finalization
		// with no outcome at all.
		report.refused(reason)
		if (log) EventLog.append("engine", "skipped: $reason")
		// A shadow run has decided; what it must not do is write the decision
		// down. The Not-logged list is a durable, user-visible record.
		if (report.shadow) return
		// A listen the reducer refused to attribute arrives here with `playedMs`
		// cleared, and that zero is the same zero as "nothing was measured". The
		// floor exists for the second one — a metadata swap inside three seconds.
		// Applying it to the first is how a 228s song played end to end left no
		// trace in History *or* Not logged: the refusal was correct, the silence
		// was not. The measured interval is used to decide the row is worth
		// showing and is never scored, which is the one thing it may not become.
		val notableMs = maxOf(session.playedMs, session.unattributedMeasuredMs)
		if (notableMs < MIN_NOTABLE_PLAYED_MS) return
		// §4.1. The Not-logged tab is a durable record too, and a session that was
		// never proven to be YouTube is a page the user watched somewhere else —
		// listing its title there is the same disclosure the event log stopped
		// making. It is also useless as an explanation: "why wasn't this
		// scrobbled" does not need answering about a site this app never scrobbles.
		if (!session.isYouTube) return
		val title = session.title?.takeIf { it.isNotBlank() }
			?: resolvedTitle?.takeIf { it.isNotBlank() }
			?: return
		// Not a guard. An id that cannot authorize a canonical watch URL is
		// dropped from the *row*, not the row from the list: the hyperlink is
		// owed to the broadcast, which still refuses to publish anything without
		// one, while the user is owed the record of a listen this app measured
		// and declined. Nothing is invented to fill the gap.
		val linkedVideoId = videoId?.takeIf { YouTubeProbe.canonicalWatchUrl(it) != null }
		// The prefilter reason for an unattributed listen is "played 0%", which is
		// arithmetically true of the cleared counter and false about the evening:
		// the app measured this time and declined to credit it. Say that instead.
		// Only the row is reworded — the outcome and the log keep the engine's own
		// reason, so nothing that classifies refusals changes meaning.
		val rowReason = if (session.playedMs < MIN_NOTABLE_PLAYED_MS &&
			session.unattributedMeasuredMs >= MIN_NOTABLE_PLAYED_MS
		) {
			"${session.unattributedMeasuredMs / 1000}s played here, but the source never " +
				"established which presentation was the named work, so none of it could be " +
				"credited to this track"
		} else {
			reason
		}
		val record = SkipRecord(
			title = title,
			artist = session.artist,
			reason = rowReason,
			atEpochSec = clock.nowEpochSeconds(),
			playedSeconds = session.playedMs / 1000,
			durationSeconds = durationMs?.takeIf { it > 0 }?.div(1000),
			videoId = linkedVideoId,
			// Read here, as the row is filed, and not at display time. This is
			// the one moment the answer is authoritative: a refusal is decided
			// and written in one go, with no queue to outlive it and no later
			// retry to be re-filed by, so whoever the vault holds now is exactly
			// whose listen this was. Null when nobody is signed in, which is a
			// stamp like any other. See [SkipRecord.account].
			account = vault.account?.username,
		)
		// `update` rather than a plain assignment: unlike every other list here,
		// this one is written from two threads — the prefilter rejects on the
		// media-session callback while the post-enrichment rules reject on the IO
		// scope, and a shorts feed can have both in flight.
		_skipped.update { (listOf(record) + it).take(RETAINED_ROWS) }
		persistSkipped()
	}

	/**
	 * Whether a prefiltered refusal is substantial enough to resolve for the UI.
	 *
	 * Reads the same measure [skip] does, including the interval that was refused
	 * attribution. The two must agree: this decides whether a title is worth
	 * resolving for a row, and a listen [skip] will now write would otherwise
	 * reach it without one.
	 */
	private fun couldBecomeUserFacingSkip(session: SessionSnapshot): Boolean =
		maxOf(session.playedMs, session.unattributedMeasuredMs) >= MIN_NOTABLE_PLAYED_MS && (
			!session.title.isNullOrBlank() ||
				(session.hasShortSourceProof && !session.ownerHandle.isNullOrBlank())
		)

	private fun noteVideoIdOutcome(session: SessionSnapshot, videoId: String?, shadow: Boolean) {
		if (shadow) return
		if (session.origin != YouTubeProbe.Origin.BROWSER) return
		if (videoId != null) {
			_tracksWithoutVideoId.value = 0
			return
		}
		val barNamedThisTrack = session.confirmed != null ||
			session.resolverContext.observedVideoId != null ||
			session.resolverContext.urlGeneration != null
		if (barNamedThisTrack) {
			// A bar that named this video is not a quiet bar, whatever happened to
			// the id afterwards. Clearing rather than merely not counting is what
			// makes the card dismissable by the one thing that disproves it.
			_tracksWithoutVideoId.value = 0
			return
		}
		val misses = _tracksWithoutVideoId.updateAndGet { it + 1 }
		if (misses == QUIET_BAR_THRESHOLD) {
			EventLog.append(
				"url",
				"the address bar has named no video for $misses tracks in a row — " +
					"unresolved tracks will not be broadcast. Check Accessibility is still " +
					"granted, or tap the toolbar once to expand it.",
			)
		}
	}

	private fun note(
		/** Who this row belongs to. See [ScrobbleRecord.account]. */
		account: String,
		label: String,
		status: String,
		txId: String?,
		percent: Int? = null,
		queued: Boolean = false,
		videoId: String? = null,
	) {
		val linkedVideoId = videoId
			?.takeIf { YouTubeProbe.canonicalWatchUrl(it) != null }
			?: return
		val artist = label.substringBefore(" — ", "").ifEmpty { null }
		val title = label.substringAfter(" — ", label)
		_recent.update {
			(listOf(
				ScrobbleRecord(
					title = title,
					artist = artist,
					percentPlayed = percent ?: 0,
					atEpochSec = clock.nowEpochSeconds(),
					status = status,
					txId = txId,
					queued = queued,
					videoId = linkedVideoId,
					// Once, here, for this row. Every other field is shared with
					// the listen and can repeat; this one cannot.
					eventId = UUID.randomUUID().toString(),
					account = account,
				),
			) + it).take(RETAINED_ROWS)
		}
		persistHistory()
	}
}
