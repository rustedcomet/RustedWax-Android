package com.rustedwax.app.detect

import com.rustedwax.core.*
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.rustedwax.core.SourceSessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Freeze the identity this track established before Chromium's teardown
 * bundle replaces real fields with site chrome or omits them entirely.
 */
internal fun finalizedPresentation(
	established: TrackIdentity,
	currentTitle: String?,
	currentArtist: String?,
	currentAlbum: String?,
): TrackIdentity = established.copy(
	title = established.title ?: currentTitle,
	artist = established.artist ?: currentArtist,
	album = established.album ?: currentAlbum,
)

/**
 * Whether this listen still has to ask for a presentation proof.
 *
 * An id the listen already holds is a reason not to look again *only while the
 * listen is also already attributed*. The two facts have different lifetimes: a
 * playback generation beginning after STOPPED on the same presentation carries
 * the previous listen's `trackIdentity` — id included — into a state whose
 * attribution has been reset. Reading the id alone therefore skipped the lookup
 * for a listen that held no proof, and a re-entered 228 s video measured its
 * whole length and could credit none of it.
 *
 * Identity outlives a listen; attribution does not. For a source that never
 * needs attribution the ambiguity flag is always false, so this is exactly the
 * old rule there. Duplicate lookups *within* one listen are still prevented by
 * the request signature, which this does not replace.
 */
internal fun listenNeedsPresentationProof(
	hasExactSourceItemId: Boolean,
	presentationAttributionAmbiguous: Boolean,
): Boolean = !hasExactSourceItemId || presentationAttributionAmbiguous

internal fun resolverContextWithObservedUrl(
	context: ResolverContext,
	url: UrlEvidence.Evidence,
	rejectedVideoIds: Set<String>,
): ResolverContext {
	val observedWasRejected = context.observedVideoId != null &&
		context.observedVideoId in rejectedVideoIds
	val acceptConcreteId = url.videoId != null &&
		(context.observedVideoId == null || observedWasRejected)
	return context.copy(
		// A concrete successor URL replaces the rejected outgoing track as one
		// atomic observation. Keeping the old list while accepting the new video
		// id sends the resolver into the previous playlist and defeats the exact
		// bounded lookup that the new URL was meant to restore.
		playlistId = if (acceptConcreteId) {
			url.playlistId ?: context.playlistId
		} else {
			context.playlistId ?: url.playlistId
		},
		urlGeneration = if (acceptConcreteId) {
			url.generation.takeIf { it > 0 }
		} else {
			context.urlGeneration
		},
		observedVideoId = if (acceptConcreteId) url.videoId else context.observedVideoId,
		observedUrl = if (acceptConcreteId) url.raw else context.observedUrl,
	)
}

/**
 * Watches every active media session and tracks playback progress.
 *
 * This is the detection half of RustedWax: it observes, measures and reports
 * when a track ends. It deliberately decides nothing — thresholds, dedup and
 * payloads all live in `scrobble/`.
 *
 * Browser sessions remain the v0.8.15 path. v0.9 additionally watches the two
 * native YouTube packages only when their independent persisted opt-ins are on.
 *
 * Position handling extrapolates from the last published PlaybackState:
 * reports a position sampled at `lastPositionUpdateTime`, so live position is
 * `position + (now - sampledAt) * speed`. We also accumulate *played
 * milliseconds* across play/pause, which is what the 60% rule consumes and is
 * computable from this data alone.
 *
 * "Played" means **content consumed**, not seconds elapsed: the same `speed`
 * factor scales the accumulator, because the threshold compares against
 * `duration`. Watching at 1.25× used to report 67% of a trailer that had been
 * watched to 79%, and at 2× a video watched in full read 50% and never
 * scrobbled. [MediaSessionDriver] carries the reducer state that owns this accounting.
 */
class SessionProbe(
	context: Context,
	/**
	 * Which packages this probe watches.
	 *
	 * Production passes nothing and gets [NativeSourceSwitches.acceptsPackage],
	 * which is the shipped answer and the only answer any shipped caller can get:
	 * the parameter is immutable, per-instance, and has no setter, so there is no
	 * runtime state a later call could widen. That is the difference from the
	 * global allowance this replaces — `internal` is module visibility, not
	 * test-only isolation, and a mutable global compiled into the app is a
	 * standing widening of which packages get read.
	 *
	 * The instrumented suite passes its own predicate because a test cannot create
	 * a `MediaSession` under Brave's package name, and without a watchable package
	 * the real callback path is unreachable from the device.
	 *
	 * **What overriding this cannot prove.** It changes which packages are
	 * *watched*; it changes nothing about what a package *is*. A session created by
	 * this app is still not a native YouTube session, so
	 * `SourceProfile.playbackCapabilitiesFor` still answers browser — no exact-ID
	 * carry requirement, no stopped-replacement grace, no picture-in-picture
	 * inference. Coverage obtained this way is Android callback translation under
	 * browser capabilities and nothing more; native and PiP behaviour needs the
	 * real installed source, and is reported separately rather than inferred here.
	 */
	private val acceptsPackage: (String) -> Boolean = NativeSourceSwitches::acceptsPackage,
	private val evidenceCoordinator: EvidenceCoordinator =
		EvidenceCoordinator().also { it.start() },
	private val finalizedPlaybackTombstones: FinalizedPlaybackTombstones =
		FinalizedPlaybackTombstones.None,
	private val automaticWriteAuthorization: () -> AutomaticWriteAuthorization =
		{ AutomaticWriteAuthorization.LegacyEnabled },
	/**
	 * Whether the display is on right now.
	 *
	 * Injected rather than read from [context] because this file is also compiled
	 * against the JVM stand-in platform the old/new parity gate runs on, where
	 * there is no `PowerManager` to ask. The default answers "on", which is the
	 * behaviour every path had before [StoppedInterruption] existed, so a caller
	 * that does not wire it can only get the old finalization, never a longer one.
	 * `RustedWaxListenerService` wires the real display state.
	 */
	private val displayInteractive: () -> Boolean = { true },
) {

	private val appContext = context.applicationContext
	private val packageManager = appContext.packageManager
	private val sessionManager =
		appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
	private val listenerComponent =
		ComponentName(appContext, RustedWaxListenerService::class.java)
	private val handler = Handler(Looper.getMainLooper())

	private val watches = mutableMapOf<String, AndroidSessionBinding>()

	/**
	 * Listens whose MediaSession has gone while their continuation is still open.
	 *
	 * Frozen [SessionSnapshot]s, not live objects: the AndroidSessionBinding that
	 * produced one has already been disposed and its callbacks unregistered, so
	 * nothing here is a session being kept alive for the UI, and nothing here can
	 * accrue play time. The listen itself is still owned by [TrackProgressCarry]
	 * and still finalized by the continuation timer; this map only lets Now say
	 * that the app is waiting, instead of showing nothing at all.
	 */
	private val pendingContinuations = LinkedHashMap<String, PendingContinuation>()

	/**
	 * One waiting row, and which listen owns it.
	 *
	 * The key is the carry's own key — package and semantic track — so two
	 * separate viewings of the same item share it, and a delayed callback
	 * belonging to the first would otherwise collect the second one's row. The
	 * owner travels with the snapshot so removal can ask "is this still *my*
	 * row", the same question [TrackProgressCarry] answers with its token,
	 * rather than "is there a row under this key".
	 */
	private data class PendingContinuation(
		/** The continuation whose wait this row is showing. */
		val continuationToken: Long,
		val snapshot: SessionSnapshot,
	)

	/** One pending row per package and semantic track, matching the carry's own key. */
	private fun continuationRowKey(packageName: String, semanticKey: String) =
		"$packageName\u0000$semanticKey"

	/**
	 * Drop this continuation's own waiting row, if it is still the one on Now.
	 *
	 * Returns whether anything was removed, so a caller can publish only when the
	 * row set actually changed.
	 */
	private fun forgetPendingContinuation(
		packageName: String,
		trackIdentity: TrackIdentity,
		continuationToken: Long,
	): Boolean = forgetPendingContinuation(packageName, trackIdentity) {
		it.continuationToken == continuationToken
	}

	/**
	 * Drop the waiting row for the listen a replacement has just claimed back.
	 *
	 * A claim names no continuation token — it consumes the carry and returns
	 * only the progress — so ownership is proven here by the listen instance both
	 * the carry and the frozen row were stamped with. A carry with no instance of
	 * its own proves nothing either way, and removal falls back to the key, which
	 * is what every caller did before ownership was checked at all.
	 */
	private fun forgetPendingContinuation(
		packageName: String,
		trackIdentity: TrackIdentity,
		claimed: TrackProgressCarry.Progress,
	): Boolean = forgetPendingContinuation(packageName, trackIdentity) { pending ->
		claimed.trackInstanceToken?.let { it == pending.snapshot.trackInstanceToken } ?: true
	}

	private fun forgetPendingContinuation(
		packageName: String,
		trackIdentity: TrackIdentity,
		owns: (PendingContinuation) -> Boolean,
	): Boolean {
		val key = continuationRowKey(packageName, trackIdentity.semanticKey)
		val pending = pendingContinuations[key] ?: return false
		if (!owns(pending)) return false
		pendingContinuations.remove(key)
		return true
	}

	private val destroyedTokens = object : LinkedHashMap<String, Unit>(16, 0.75f, false) {
		override fun removeEldestEntry(eldest: Map.Entry<String, Unit>): Boolean =
			size > MAX_REMEMBERED_DESTROYED_TOKENS
	}

	/** A session the platform told us was destroyed is never watched again. */
	private fun noteSessionDestroyed(token: String) {
		destroyedTokens[token] = Unit
	}
	private fun allocateTrackToken(sourceSession: SourceSessionId): Long =
		requireNotNull(evidenceCoordinator.allocateTrackInstance(sourceSession)) {
			"track tokens may only be allocated inside an active evidence run"
		}.instanceToken

	private val foregroundShortTracker = ForegroundShortTracker(
		automaticWriteAuthorization = automaticWriteAuthorization,
		allocateTrackToken = {
			allocateTrackToken(
				SourceSessionId(
					YouTubeProbe.YOUTUBE_PACKAGE,
					NativeSourceSwitches.epochFor(YouTubeProbe.YOUTUBE_PACKAGE),
				),
			)
		},
	)
	/** Exact Brave Short playback seen before Chromium names the new track. */
	private val browserForegroundLeadIn = BrowserForegroundLeadIn()

	/**
	 * The foreground-Shorts source, which has no `MediaController` of its own.
	 *
	 * Every observation from the accessibility tree is translated by this adapter
	 * and performed here. What used to live in this class was the translation
	 * *and* the decisions, in one method whose every branch tested one package
	 * constant.
	 */
	private val nativeShorts = NativeShortsAdapter()
	private val nativeShortDiagnosticThrottle = RepeatedDiagnosticThrottle()
	private var foregroundShortSnapshot: SessionSnapshot? = null

	/** Packages already logged as ignored, so the log records each one once. */
	private val ignoredPackages = mutableSetOf<String>()

	/** §4.1: everything a session we do not scrobble is allowed to leave behind. */
	private val unprovenSessions = UnprovenSessionCounter()

	private val _sessions = MutableStateFlow<List<SessionSnapshot>>(emptyList())
	val sessions: StateFlow<List<SessionSnapshot>> = _sessions.asStateFlow()

	private val _error = MutableStateFlow<String?>(null)
	val error: StateFlow<String?> = _error.asStateFlow()

	/**
	 * Called when a track ends — the moment the scrobble decision is made.
	 *
	 * The snapshot is built *here*, not at track start, because identity
	 * depends on the notification hint that arrives ~300 ms late (PHASE0 run 2).
	 * By finalize time it has always landed.
	 */
	var onTrackFinalized: ((SessionSnapshot) -> Unit)? = null

	/**
	 * Cheap scoring-layer scheduling answer for a session that vanished.
	 *
	 * Detection supplies only measured content and duration; it never reads an
	 * automatic-scrobble switch or threshold. `true` keeps the ordinary
	 * replacement grace but declines the long resumable-position window because
	 * the listen has already earned prompt disposition.
	 */
	var shouldFinalizeContinuationPromptly:
		((playedMs: Long, durationMs: Long?) -> Boolean)? = null

	/**
	 * Fires when a session's video id becomes known — the earliest moment
	 * enrichment can start. Wired to the runtime's prefetch, which dedupes, so
	 * being called on every identity re-check is fine.
	 */
	var onVideoConfirmed:
		((videoId: String, completion: (available: Boolean) -> Unit) -> Unit)? = null

	/** Clears resolver candidates when a native package is disabled or torn down. */
	var onPackageTornDown: ((packageName: String) -> Unit)? = null

	/** Minimal immutable proof returned by the runtime's off-main-thread native lookup. */
	data class NativeResolvedIdentity(
		val videoId: String,
		val route: NativePreResolvedRoute,
		/**
		 * The proof that named this id also required the length the player is
		 * publishing to be this work's own.
		 *
		 * Carried as a fact from the resolver rather than inferred from [route]:
		 * the route buckets describe *which listing* named the work, and more than
		 * one of them contains both duration-checked and duration-blind paths. A
		 * Video-mode presentation, whose length matches no catalog row, is
		 * corroborated by its own watch page and belongs here even though its
		 * route is not the structured one.
		 */
		val presentationDurationCorroborated: Boolean = false,
	)

	/**
	 * Resolve a stable exact-ID-less native track while it is playing. The probe
	 * spends this only on controller continuity; payload authority remains in the
	 * runtime and is re-fetched at finalization.
	 */
	var onNativeIdentityRequested:
		((SessionSnapshot, (NativeResolvedIdentity?) -> Unit) -> Unit)? = null

	/**
	 * What a resolved page says about a video id — the two facts that can
	 * disprove a latch. Deliberately narrow rather than passing `VideoFacts`
	 * around: the probe corroborates identity, it doesn't consume metadata.
	 */
	data class KnownVideo(
		val title: String?,
		val channel: String?,
		val lengthSeconds: Long?,
	)

	/**
	 * Cached facts for a video id, or null when nothing is known yet. Used to
	 * corroborate a latched video id against what the session is actually
	 * playing. Cache-only — must never touch the network.
	 */
	var knownVideoFor: ((videoId: String) -> KnownVideo?)? = null

	private var started = false
	private var browserEvidenceEnabledForRun = false
	private var evidenceEventsJob: Job? = null

	/** Android-created sibling services publish only into the service-owned run. */
	internal fun evidenceCoordinatorForProducers(): EvidenceCoordinator = evidenceCoordinator

	/** Deterministic JVM mirror seam; production producers use [EvidenceCoordinator.events]. */
	internal fun acceptEvidenceEventForHarness(event: EvidenceCoordinator.Event) {
		handler.post { handleEvidenceEvent(event) }
	}

	private val activeSessionsListener =
		MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
			syncControllers(controllers ?: emptyList())
		}

	fun start() {
		if (started) return
		browserEvidenceEnabledForRun = UrlWatcherService.isEnabled(appContext)
		evidenceEventsJob = CoroutineScope(SupervisorJob()).launch(
			start = CoroutineStart.UNDISPATCHED,
		) {
			evidenceCoordinator.events.collect { event ->
				handler.post { handleEvidenceEvent(event) }
			}
		}
		try {
			sessionManager.addOnActiveSessionsChangedListener(
				activeSessionsListener,
				listenerComponent,
				handler,
			)
			syncControllers(sessionManager.getActiveSessions(listenerComponent))
			started = true
			_error.value = null
			EventLog.append("probe", "started")
		} catch (e: SecurityException) {
			evidenceEventsJob?.cancel()
			evidenceEventsJob = null
			// Notification Access not granted (or revoked while running).
			_error.value = "Notification Access not granted — enable it to read media sessions."
			EventLog.append("probe", "start failed: ${e.message}")
		}
	}

	private fun handleEvidenceEvent(event: EvidenceCoordinator.Event) {
		if (!started) return
		val packageName = event.sourceSession.packageName
		when (event) {
			is EvidenceCoordinator.Event.NotificationChanged -> {
				watches.values.filter { it.packageName == packageName && it.acceptsLiveEvidence }
					.forEach { it.reidentify("notification") }
			}
			is EvidenceCoordinator.Event.UrlChanged -> {
				watches.values.filter { it.packageName == packageName && it.acceptsLiveEvidence }
					.forEach { it.reidentify("address bar") }
			}
			is EvidenceCoordinator.Event.VisibleAdAccepted -> {
				watches.values.filter {
					it.observesHostScreenEvidence && it.playbackEligible &&
						it.packageName == packageName
				}.forEach { it.noteAdEvidence(event.evidence) }
			}
			is EvidenceCoordinator.Event.HostAdObserved -> handleHostAdObservation(event.observation)
			is EvidenceCoordinator.Event.ScreenScanned -> handleScreenScan(event.scan)
			is EvidenceCoordinator.Event.NativeShortObserved -> handleNativeShortEvent(event.event)
			is EvidenceCoordinator.Event.NativeWatchAdObserved -> handleNativeWatchAd(event)
		}
		publish()
	}

	/**
	 * The watch player's own ad state, to the listens it can describe.
	 *
	 * Which interval that state marks is the reducer's decision. The host's part is
	 * only that the look was taken for this source and this switch generation.
	 */
	private fun handleNativeWatchAd(event: EvidenceCoordinator.Event.NativeWatchAdObserved) {
		watches.values
			.filter {
				it.adapter.evidenceCapabilities.presentsWatchPlayerAdSurface &&
					it.packageName == event.sourceSession.packageName &&
					it.sourceEpoch == event.sourceSession.sourceEpoch
			}
			.forEach { it.notePlayerAdSurface(event.reading) }
	}

	private fun handleHostAdObservation(observation: MediaSessionAdEvidence.Observation) {
		if (observation.signal == null) {
			evidenceCoordinator.clearTrackAdProvisional(
				SourceSessionId(observation.packageName, null),
			)
			return
		}
		when (val selection = bindHostObservation(observation.packageName, namedItemId = null)) {
			is SourceBinding.Bound ->
				watchByInstanceToken(observation.packageName, selection.key)
					?.noteMediaSessionAdEvidence(observation)
			is SourceBinding.Refused -> {
				evidenceCoordinator.clearTrackAdProvisional(
					SourceSessionId(observation.packageName, null),
				)
				EventLog.append(
					"ad",
					"${observation.packageName} → ordinary watch ad label refused: ${selection.reason}",
				)
			}
		}
	}

	private fun handleScreenScan(scan: MediaSessionAccessibilityEvidence.Scan) {
		browserForegroundLeadIn.observe(
			BrowserForegroundLeadIn.Observation(
				packageName = scan.packageName,
				videoId = scan.videoId,
				urlGeneration = scan.urlGeneration,
				isShort = scan.isShort,
				atMillis = scan.atMillis,
			),
			watches.values.filter { it.packageName == scan.packageName }.map { watch ->
				BrowserForegroundLeadIn.Candidate(
					key = watch.sessionKey,
					live = watch.acceptsLiveEvidence,
					playing = watch.playbackEligible,
					describesNamedVideo = scan.videoId?.let(watch::isMeasuringVideo) == true,
				)
			},
		)
		when (val selection = bindHostObservation(scan.packageName, scan.videoId)) {
			is SourceBinding.Bound ->
				watchByInstanceToken(scan.packageName, selection.key)?.noteAccessibilityScan(
					scan,
					namedThisInstance = selection.namedThisInstance,
				)
			is SourceBinding.Refused -> EventLog.append(
				"evidence",
				"${scan.packageName} → successful YouTube scan not bound: ${selection.reason}",
			)
		}
	}

	/**
	 * Ask the source which of its live listens an observation belongs to.
	 *
	 * The host's whole part is here: collect immutable facts about the listens it
	 * holds, and perform whatever comes back. Which listen a browser observation
	 * describes is browser policy — several live tabs, named versus eliminated,
	 * paused versus ended — and it lives behind [BrowserYouTubeAdapter]. A
	 * first-party source refuses the same request outright, so nothing here has to
	 * ask what kind of source it is holding.
	 */
	private fun bindHostObservation(
		packageName: String,
		namedItemId: String?,
	): SourceBinding = SourceRegistry.forWatch(packageName, packageName, evidenceCoordinator)
		.selectForHostObservation(
			SourceHostObservationRequest(
				namedItemId = namedItemId,
				candidates = watches.values
					.filter { it.packageName == packageName }
					.map { watch ->
						SourceObservationCandidate(
							key = watch.bindingKey,
							describesNamedItem =
								namedItemId != null && watch.describesVideo(namedItemId),
							finalized = watch.listenFinalized,
							playing = watch.listenPlaying,
						)
					},
			),
		)

	/** The watch [BrowserScanBinding] chose, or null if it has since gone. */
	private fun watchByInstanceToken(packageName: String, key: Long): AndroidSessionBinding? =
		watches.values.singleOrNull { it.packageName == packageName && it.bindingKey == key }

	fun stop(finalizeTracks: Boolean = true) {
		if (!started) return
		evidenceEventsJob?.cancel()
		evidenceEventsJob = null
		foregroundShortTracker.discard(
			if (finalizeTracks) "listener/probe lifecycle boundary" else "user Stop",
		)
		foregroundShortSnapshot = null
		NativeShortsObserver.setRefreshNeeded(false)
		runCatching { sessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
		// Probe shutdown cannot wait for a replacement session: either score the
		// last aggregate now (system teardown) or discard it (user Stop).
		watches.values.forEach { watch ->
			// Whether a listener rebuild is a pause or a boundary is the source's own
			// answer: a browser keeps its identity in a bar that survives the rebuild,
			// and a native listen's evidence is stamped with a generation that does not.
			val discards =
				watch.adapter.teardownPolicy == SourceTeardownPolicy.DISCARD_AND_RESET
			watch.dispose(
				finalize = finalizeTracks && !discards,
				allowContinuation = false,
			)
			if (discards) clearPackageState(watch.packageName)
		}
		watches.clear()
		pendingContinuations.clear()
		// A vanished epoch-scoped controller may still have a pending continuation
		// even though no AndroidSessionBinding remains in the map. Reconnect/Stop clears it too.
		SourceRegistry.epochScopedPackages.forEach(::clearPackageState)

		if (!finalizeTracks) {
			TrackProgressCarry.clear()
		}
		started = false
		EventLog.append(
			"probe",
			if (finalizeTracks) "stopped" else "stopped — in-flight tracks discarded",
		)
		publish()
	}

	/**
	 * Apply a source-switch change to already-active MediaSessions immediately.
	 *
	 * Since v0.11.0 the browser packages are swept too. They carry no epoch of
	 * their own, so before the YouTube master switch existed there was nothing
	 * that could un-accept them and nothing to sweep. Now there is — and a
	 * browser listen that had been deferred for continuation would otherwise sit
	 * in [TrackProgressCarry] across the opt-out and become finalizable again
	 * the moment the switch came back on. Turning a source off has to be a
	 * boundary, not a pause.
	 */
	fun refreshTargets() {
		if (!started) return
		if (!nativeShorts.isWatched() ||
			!nativeShorts.isCurrent(foregroundShortSnapshot?.sourceEpoch)
		) {
			foregroundShortTracker.discard("Native YouTube opt-out/source epoch changed")
			foregroundShortSnapshot = null
			NativeShortsObserver.setRefreshNeeded(false)
		}
		SourceRegistry.watchedPackages
			.filterNot(acceptsPackage)
			.forEach { packageName ->
				// Said out loud only when there was something to lose. The
				// sweep runs on every config change, and a line per package per
				// change is how the two previous diagnoses got buried.
				if (TrackProgressCarry.hasPackage(packageName)) {
					EventLog.append(
						"native",
						"$packageName is no longer a source — deferred play time dropped",
					)
				}
				clearPackageState(packageName)
			}
		runCatching { sessionManager.getActiveSessions(listenerComponent) }
			.onSuccess(::syncControllers)
			.onFailure { EventLog.append("probe", "target refresh failed: ${it.message}") }
	}

	/** Recomputes snapshots so the UI shows live extrapolated position. */
	fun tick() = publish()

	// ── internals ──────────────────────────────────────────────────────

	private fun syncControllers(controllers: List<MediaController>) {
		val byKey = controllers.associateBy { it.sessionToken.toString() }

		// Gone, or explicitly disabled while still active.
		for (key in watches.keys.toList()) {
			val watch = watches[key] ?: continue
			val controller = byKey[key]
			when {
				watch.adapter.evidenceCapabilities.scopedBySourceEpoch &&
					!NativeSourceSwitches.isSnapshotCurrent(
						watch.packageName,
						watch.sourceEpoch,
					) -> {
					watches.remove(key)
					EventLog.append("native", "${watch.packageName} source epoch changed — track discarded")
					watch.dispose(finalize = false, allowContinuation = false)
					clearPackageState(watch.packageName)
				}
				// Browsers reach this too since v0.11.0: the YouTube master
				// switch un-accepts them, and a part-played track must be
				// dropped rather than finished. Turning a source off is not a
				// way to make it broadcast.
				!acceptsPackage(watch.packageName) -> {
					watches.remove(key)
					EventLog.append(
						"native",
						"${watch.packageName} source switched off — in-flight track discarded",
					)
					watch.dispose(finalize = false, allowContinuation = false)
					clearPackageState(watch.packageName)
				}
				controller == null -> {
					watches.remove(key)
					watch.logDeparture("− ${watch.packageName} (session ended)")
					watch.dispose(finalize = true, allowContinuation = true)
				}
			}
		}

		// New.
		for ((key, controller) in byKey) {
			if (watches.containsKey(key)) continue

			// A session this probe has already seen destroyed. Android may keep
			// listing it for a moment; watching it again would put two watches on
			// one logical listen. See [destroyedTokens].
			if (key in destroyedTokens) continue

			if (!acceptsPackage(controller.packageName)) {
				noteIgnored(
					controller.packageName,
					SourceRegistry.ignoredReason(controller.packageName),
				)
				continue
			}

			val watch = AndroidSessionBinding(controller, labelFor(controller.packageName))
			watches[key] = watch
			val restoredEndedTransport = watch.suppressRestoredEndedTransport()
			if (!restoredEndedTransport) {
				if (watch.adapter.evidenceCapabilities.presentsForegroundShorts &&
					foregroundShortTracker.hasCompleteProof
				) {
					watch.suppressForForegroundShort(foregroundShortSnapshot)
				}
			}
			// The arrival line is deliberately not written here. At this moment
			// nothing has proven what site this is, and "+ com.android.chrome" on
			// a timestamp is the same browsing record §4.1 removes elsewhere. The
			// session announces itself once it is proven YouTube; until then the
			// aggregate is the only trace.
			if (!restoredEndedTransport) {
				watch.logMetadata(controller.metadata, "initial")
				watch.logPlaybackState(controller.playbackState, "initial")
			}
		}

		// Two MediaSessions from one browser package are deliberately ambiguous:
		// accessibility exposes no session token with which to choose between them.
		watches.values.filter { it.observesHostScreenEvidence && it.acceptsLiveEvidence }
			.groupBy { it.packageName }.forEach { (packageName, packageWatches) ->
			if (packageWatches.size > 1) {
				evidenceCoordinator.clearTrackAdProvisional(SourceSessionId(packageName, null))
			}
		}

		// A native session may already be PLAYING when the listener attaches, so
		// the same ownership decision used by callbacks also runs after discovery.
		watches.values.toList().forEach(::arbitrateExclusivePlayback)

		publish()
	}

	/**
	 * End a proven positionless browser clock when first-party YouTube publishes
	 * real progress. On this device YouTube requested exclusive media focus while
	 * Brave retained a stale PLAYING/-1 transport; counting both turned 51 real
	 * seconds of `#hoyoverse` into a false 165-second listen.
	 */
	private fun arbitrateExclusivePlayback(takeover: AndroidSessionBinding) {
		val current = watches.values.toList()
		val targets = ExclusivePlaybackArbitration.staleHostWatchesToFinalize(
			takeover = takeover.concurrentPlaybackCandidate(),
			candidates = current.map(AndroidSessionBinding::concurrentPlaybackCandidate),
		)
		current.filter { it.sessionKey in targets }.forEach {
			it.finalizeForExclusiveTakeover(takeover.packageName)
		}
	}

	/**
	 * One line per package, ever — enough to answer "why isn't my music
	 * showing up", without the package name reappearing on every session
	 * change. No metadata is read from it.
	 */
	private fun noteIgnored(packageName: String, reason: String) {
		// §4.1: a package this app does not scrobble is still an app the user has
		// open, and naming it in a timestamped file is the same record the title
		// leak was. The aggregate says the probe is alive without saying where
		// anyone went.
		if (ignoredPackages.add("$packageName|$reason")) unprovenSessions.note()
	}

	/**
	 * True when the browser has exactly one media session, which is what lets
	 * [NotificationHints.bestFor] fall back to the newest hint: with one session
	 * there is no other tab the notification could belong to.
	 */
	private val soleHostSession: Boolean
		get() = watches.values.count {
			it.observesHostScreenEvidence && it.acceptsLiveEvidence
		} == 1

	private fun clearPackageState(packageName: String) {
		TrackProgressCarry.clearPackage(packageName)
		pendingContinuations.keys.removeAll { it.startsWith("$packageName\u0000") }
		// Which package-scoped observation state a reset clears belongs to the source
		// that owns it: the native watch screen's playlist bar is the YouTube app's,
		// and YouTube Music must neither read it nor discard it.
		SourceRegistry.forPackage(packageName, packageName, evidenceCoordinator)?.onPackageStateReset()
		evidenceCoordinator.resetPackage(packageName)
		onPackageTornDown?.invoke(packageName)
	}

	private fun publish() {
		val mediaSessions = watches.values
			.filter {
				!it.suppressedByForegroundShort && it.acceptsLiveEvidence &&
					it.hasPresentableIdentity
			}
			.map { it.snapshot() }
		// A package that is presenting something live has answered the question a
		// pending row exists to ask, and the answer does not expire.
		//
		// Dropped rather than hidden, and keyed on the listen rather than the
		// package, so the two cases stay apart. A live row carrying the *same*
		// instance token is the waiting listen itself, come back and claimed —
		// that row is kept out of the list below so one listen is not shown twice,
		// but it is still owed its place if this session goes again. A live row
		// carrying a *different* token is a different listen, and the waiting one
		// has stopped being what this app is about: it is removed here, once, so
		// that when the newer listen later ends — leaving no live session, and
		// whether or not it left a continuation of its own — the older one cannot
		// walk back onto Now with nobody having resumed it.
		//
		// Presentation only, in both directions. The removed listen keeps its
		// carry entry, its timer and its finalization exactly as before.
		//
		// The foreground Short is presentation in exactly the same sense, and is
		// counted here as one: it is a native listen this app is showing right
		// now, under the same package as the watch screen a waiting row came
		// from. Leaving it out let a Short play beside an older native-watch row
		// that nobody had resumed. Nothing about how a Short is measured,
		// finalized or scrobbled is touched by being counted as a presented row.
		val shortRow = foregroundShortSnapshot
		val presenting = (listOfNotNull(shortRow) + mediaSessions).groupBy { it.packageName }
		pendingContinuations.values.removeAll { pending ->
			val live = presenting[pending.snapshot.packageName]
			live != null && live.none { it.trackInstance == pending.snapshot.trackInstance }
		}
		val waiting = pendingContinuations.values
			.filter { it.snapshot.packageName !in presenting.keys }
			.map { it.snapshot }
		_sessions.value = listOfNotNull(shortRow) + mediaSessions + waiting
	}

	private fun handleNativeShortEvent(event: NativeShortsObserver.Event) {
		if (!started) return
		if (!nativeShorts.isWatched()) {
			foregroundShortTracker.discard("Native YouTube is off")
			foregroundShortSnapshot = null
			NativeShortsObserver.setRefreshNeeded(false)
			publish()
			return
		}
		// The source has already interpreted the Android observation. The host only
		// routes neutral playback inputs to the reducer that owns their lifecycle.
		val inputs = nativeShorts.read(
			event = event,
			foregroundSurfaceOwned = foregroundShortTracker.hasActive,
			hostNowMillis = System.currentTimeMillis(),
		)
		var update: ForegroundShortTracker.Update? = null
		var observationAtMillis = System.currentTimeMillis()
		inputs.forEach { input ->
			when (input) {
				is PlaybackInput.ForegroundSurfaceConnected -> {
					nativeShortDiagnosticThrottle.reset()
					observationAtMillis = input.nowMillis
					update = foregroundShortTracker.reduce(input)
				}

				is PlaybackInput.ForegroundSurface -> {
					observationAtMillis = when (input) {
						is PlaybackInput.ForegroundSurfaceConnected -> input.nowMillis
						is PlaybackInput.ForegroundSurfaceObserved -> input.nowMillis
						is PlaybackInput.ForegroundSurfaceUnavailable -> input.nowMillis
					}
					update = foregroundShortTracker.reduce(input)
				}

				is PlaybackInput.PictureInPictureObserved -> watches.values
					.filter { it.adapter.evidenceCapabilities.presentsForegroundShorts }
					.forEach { it.creditPipInference(input.nowMillis, input.playing) }

				else -> error("native Shorts adapter emitted an unsupported playback input: $input")
			}
		}
		update?.let { performForegroundShort(it, observationAtMillis) }
	}

	/**
	 * Perform what one foreground-Shorts observation produced, in order.
	 *
	 * The host half: the other watches, the diagnostic throttle, the log, and the
	 * finalization callback. The adapter can reach none of them, which is what stops a
	 * source from finalizing anything itself.
	 */
	private fun performForegroundShort(
		update: ForegroundShortTracker.Update,
		observedAtMillis: Long,
	) {
		val ownsPlayer = foregroundShortTracker.hasActive
		foregroundShortSnapshot = update.active
		NativeShortsObserver.setRefreshNeeded(ownsPlayer)
		watches.values
			.filter { it.adapter.evidenceCapabilities.presentsForegroundShorts }
			.forEach { watch ->
				if (ownsPlayer) {
					watch.suppressForForegroundShort(foregroundShortSnapshot)
				} else {
					watch.releaseForegroundShortSuppression()
				}
			}
		update.diagnostic?.takeIf {
			nativeShortDiagnosticThrottle.shouldEmit(
				NativeShortDiagnosticKey.of(it), observedAtMillis,
			)
		}?.let { EventLog.append("native-shorts", it) }
		update.finalized.forEach { ended ->
			EventLog.append(
				"finalize",
				"${ended.packageName} [foreground Short lifecycle] ${ended.title} — " +
					"played ${ended.playedMs / 1000}s of " +
					// A Short whose seekbar YouTube never drew has no length to
					// quote here at all. "of 0s" read like a measurement fault;
					// the length is simply not known until identity resolves.
					(ended.durationMs?.let { "${it / 1000}s" } ?: "an unknown length") +
					if (ended.inferredPlayedMs > 0) {
						" (${(ended.playedMs - ended.inferredPlayedMs) / 1000}s measured " +
							"from the seekbar + ${ended.inferredPlayedMs / 1000}s inferred " +
							// Never "in picture-in-picture": the same inference now
							// also carries a fullscreen Short whose progress bar
							// YouTube declined to render, and saying PiP there sent
							// a field investigation looking for a PiP session that
							// never happened.
							"from wall-clock while direct progress was unavailable)"
					} else if (ended.foregroundProgressLost) {
						" (progress surface lost — the seekbar container was still " +
							"there but published no readable time, so the remainder " +
							"is unmeasured, not zero)"
					} else {
						""
					},
			)
			onTrackFinalized?.invoke(ended)
		}
		publish()
	}

	private fun labelFor(packageName: String): String = runCatching {
		packageManager
			.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
			.toString()
	}.getOrDefault(packageName)

	private fun browserEvidenceEnabledForThisRun(): Boolean {
		if (!browserEvidenceEnabledForRun && UrlWatcherService.isEnabled(appContext)) {
			browserEvidenceEnabledForRun = true
		}
		return browserEvidenceEnabledForRun
	}

	/** Per-controller state + callbacks. */
	private inner class AndroidSessionBinding(
		private val controller: MediaController,
		val appLabel: String,
	) {
		val packageName: String = controller.packageName
		// Capture once. Adapter and reducer must never observe different epochs.
		val sourceEpoch: Long? = NativeSourceSwitches.epochFor(packageName)
		private val evidenceSourceSession = SourceSessionId(packageName, sourceEpoch)

		/** The key this watch is filed under, and the identity a tombstone names. */
		val sessionKey: String = controller.sessionToken.toString()

		val adapter: SourceAdapter =
			SourceRegistry.forWatch(
				packageName, appLabel, evidenceCoordinator, evidenceSourceSession,
			)

		val observesHostScreenEvidence: Boolean
			get() = adapter.evidenceCapabilities.publishesHostScreenEvidence
		// Capture at AndroidSessionBinding construction. Reading the current epoch while finalizing
		// would let a callback racing an opt-out stamp itself with the new epoch.
		/**
		 * True while the structurally proven foreground Shorts route owns this
		 * player. Owned by the reducer; read by the probe and the UI.
		 */
		val suppressedByForegroundShort: Boolean get() = listen.suppressedByForegroundShort

		/** A finished track stays tombstoned in [watches], but owns no live surface. */
		val acceptsLiveEvidence: Boolean get() = !listen.finalized

		/** Empty native Shorts controllers are transport noise, not a Now item. */
		val hasPresentableIdentity: Boolean get() = trackIdentity.isUsable

		/** Identity alone is insufficient for screen evidence; transport must play. */
		val playbackEligible: Boolean
			get() = acceptsLiveEvidence && listen.transport == TransportState.PLAYING

		/**
		 * Always true for observed sessions because unsupported packages are not
		 * watched. Kept because the payload and the UI still read it,
		 * and because a future per-app allowlist would put it back to work.
		 */
		val isTarget: Boolean = acceptsPackage(packageName)

		/**
		 * Set when evidence positively names a non-YouTube site, and cleared
		 * only on a track change.
		 *
		 * One-way on purpose. Identity is re-checked whenever a notification
		 * lands, so without this a track proven to be some other site could be
		 * rehabilitated by a YouTube hint arriving from a different tab
		 * moments later — and then scrobbled as YouTube.
		 */
		private var taintedReason: String? = null

		private var latchedVideo: YouTubeProbe.Identity.Confirmed? = null

		/**
		 * Last identity selected while this AndroidSessionBinding was still active.
		 *
		 * A continuation expiry may run a minute after this AndroidSessionBinding was removed
		 * from [watches], when the live address bar already describes several
		 * later Shorts. It must spend this value, never re-read that later tab.
		 */
		private var lastStableIdentity: YouTubeProbe.Identity? = null

		/** Identity frozen when disappearance opened a continuation window. */
		private var continuationIdentity: YouTubeProbe.Identity? = null
		private var continuationAccessibilityCoverage:
			MediaSessionAccessibilityEvidence.Coverage? = null

		/** Exact visible YouTube ad label bound to this track, if one appeared. */
		private var explicitAdSignal: String? = null
		private var accessibilityCoverage: MediaSessionAccessibilityEvidence.Coverage? = null

		/**
		 * Video ids disproven for this track by the watch page's own title not
		 * matching the session's. Never re-latched; a rejected id also stops
		 * qualifying as live URL evidence for this track.
		 */
		private val rejectedVideoIds = mutableSetOf<String>()

	/**
	 * The subset of [rejectedVideoIds] whose only disagreement was the title.
	 *
	 * Still rejected for live latching — a title that disagrees may never confirm
	 * an id. Carried separately so finalization can tell "this page named a
	 * different video" from "this page wrote the same video's name differently".
	 */
	private val titleOnlyRejectedVideoIds = mutableSetOf<String>()

		/**
		 * Ids the page could not yet *confirm*, for logging only.
		 *
		 * An id dropped for insufficient evidence is deliberately not rejected,
		 * so nothing stops the next callback from trying it again — and the bar
		 * is re-read on every UI event, which put 114 identical unlatch lines in
		 * 40 seconds of one measured run. The retry is wanted; saying so 114
		 * times is not. This is never consulted for identity.
		 */
		private val unconfirmedVideoIds = mutableSetOf<String>()

		private var metadata: MediaMetadata? = controller.metadata
		private var state: PlaybackState? = controller.playbackState

		private val driver = MediaSessionDriver(
			reducer = PlaybackReducer(
				adapter.playbackCapabilities,
				evidenceCoordinator,
				evidenceSourceSession,
			),
			initialState = ListenState(
				trackIdentity = adapter.trackIdentity(metadata?.asFields()),
				instanceToken = allocateTrackToken(evidenceSourceSession),
				instanceEstablishedAtMillis = System.currentTimeMillis(),
				startedAtEpochSec = System.currentTimeMillis() / 1000,
				transport = transportStateOf(state),
				speed = speedOf(state),
				playingSinceElapsedMs = SystemClock.elapsedRealtime().takeIf { isPlaying(state) },
				lastObservedPositionMs = extrapolatedPosition(state),
				describingTabOnly = adapter.namesContainerOnly(metadata?.asFields()),
				everPublishedMetadata = metadata != null,
				metadataAuthoritativeForCurrentPlayback = metadata != null,
				metadataObservedSincePlaybackBoundary = metadata != null,
			),
			perform = ::perform,
			afterOutermostDispatch = ::rearmIdleFinalization,
		)

		private val listen: ListenState get() = driver.state
		/** Authority of the logical listen currently owned by [listen]. */
		private var trackAutomaticWriteAuthorization = automaticWriteAuthorization()

		/** Raw Android metadata, only while callback order authorizes this generation. */
		private val currentMetadata: MediaMetadata?
			get() = metadata.takeIf { listen.metadataAuthoritativeForCurrentPlayback }

		// Read-only views, so the identity, logging and snapshot code below reads
		// exactly as it did before the extraction. Every write goes through
		// [dispatch]; there is no second path that can change a measurement.
		private val trackIdentity: TrackIdentity get() = listen.trackIdentity
		private val trackInstanceToken: Long get() = listen.instanceToken
		private val trackInstanceEstablishedAtMillis: Long get() = listen.instanceEstablishedAtMillis
		private val trackStartedAtEpochSec: Long get() = listen.startedAtEpochSec
		private val playedMs: Long get() = listen.playedMs
		private val fastestSpeedSeen: Double get() = listen.fastestSpeedSeen
		private val firstSeenPositionMs: Long? get() = listen.firstSeenPositionMs
		private val loopDetected: Boolean get() = listen.loopDetected
		private val finalized: Boolean get() = listen.finalized
		private val describingTabOnly: Boolean get() = listen.describingTabOnly
		private val pipInferredMs: Long get() = listen.pipInferredMs

		/**
		 * Feed one observation to the reducer and perform what it asks for.
		 *
		 * The three-step contract [PlaybackReducer.Transition] describes, made
		 * literal. The one subtlety is re-entrancy: a `Finalize` effect calls
		 * [finalizeCurrent], which reduces again, so by the time the outer
		 * transition installs its state the inner one has already moved it. When
		 * the outer reducer asked for no change at all — a disposal that finalizes,
		 * for instance — the inner result is the one that must survive, or the
		 * `finalized` latch would be rolled back and the same track could score
		 * twice.
		 */
		private fun dispatch(input: PlaybackInput, incoming: MediaMetadata? = null): Unit {
			driver.dispatch(input, incoming)
		}

		/**
		 * The deadline that ends a listen whose source has gone silent.
		 *
		 * The timer and its supersession live in [IdleFinalization], which is testable;
		 * whether an expiry means anything is [PlaybackReducer]'s, which is where the
		 * measurement is.
		 */
		private val idleFinalization = IdleFinalization(
			scheduler = { delayMs, action -> handler.postDelayed(action, delayMs) },
			listenState = { listen },
			elapsedRealtimeMs = { SystemClock.elapsedRealtime() },
			durationMs = { idleDeadlineDurationMs() },
			onDeadline = { durationMs, elapsedRealtimeMs ->
				dispatch(
					PlaybackInput.IdleDeadlineReached(
						durationMs = durationMs,
						elapsedRealtimeMs = elapsedRealtimeMs,
					),
				)
				publish()
			},
		)

		/**
		 * The best length available for this listen, for the idle deadline only.
		 *
		 * The session's own `DURATION` first — but a browser publishing none at all
		 * is ordinary rather than exceptional, and it is exactly the source that
		 * needs bounding, so the latched video's own cached page is consulted next.
		 * That is the same length the rules already recover at finalization; using
		 * it here just means the deadline can be precise instead of a blunt ceiling.
		 *
		 * Cache-only, like every other read through [knownVideoFor]: this must never
		 * touch the network.
		 */
		private fun idleDeadlineDurationMs(): Long? {
			establishedDurationMs(currentMetadata)?.let { return it }
			val videoId = latchedVideo?.videoId
				?: (lastStableIdentity as? YouTubeProbe.Identity.Confirmed)?.videoId
				?: return null
			return knownVideoFor?.invoke(videoId)
				?.lengthSeconds
				?.takeIf { it > 0 }
				?.times(1000)
		}

		private fun rearmIdleFinalization() = idleFinalization.rearm()

		private var videoFactsRequestGeneration = 0L
		private var videoFactsRequestSignature: Pair<Long, String>? = null

		/**
		 * Fetch completion belongs to this exact AndroidSessionBinding and track generation.
		 *
		 * The runtime may coalesce duplicate requests, but the callback comes back
		 * through the main handler and is spent only if this AndroidSessionBinding is still registered,
		 * still live, still on the same instance token and still describes the id.
		 */
		private fun requestVideoFacts(videoId: String) {
			val requester = onVideoConfirmed ?: return
			val signature = trackInstanceToken to videoId
			if (videoFactsRequestSignature == signature) return
			videoFactsRequestSignature = signature
			val generation = ++videoFactsRequestGeneration
			var completionSpent = false
			requester(videoId) { available ->
				handler.post {
					if (completionSpent) return@post
					completionSpent = true
					if (!available || generation != videoFactsRequestGeneration ||
						videoFactsRequestSignature != signature ||
						watches[sessionKey] !== this || !acceptsLiveEvidence ||
						trackInstanceToken != signature.first || !describesVideo(videoId)
					) return@post
					rearmIdleFinalization()
					// Corroboration can spend a foreground lead-in only after this page
					// exists. Re-run the same identity path immediately instead of waiting
					// for the UI's next one-second snapshot.
					reidentify("video facts")
					publish()
				}
			}
		}

		private fun invalidateVideoFactsRequest() {
			videoFactsRequestGeneration++
			videoFactsRequestSignature = null
		}

		private fun perform(effect: PlaybackEffect, incoming: MediaMetadata?) {
			when (effect) {
				is PlaybackEffect.Finalize -> finalizeCurrent(
					effect.reason,
					effect.persistRestartTombstone,
				)
				is PlaybackEffect.FreezeAndReport -> freezeAndReport(
					effect.reason,
					effect.persistRestartTombstone,
				)
				PlaybackEffect.InstallMetadata -> metadata = incoming
				PlaybackEffect.ClearTrackScopedEvidence -> clearTrackScopedEvidence()
				PlaybackEffect.RestoreCarriedProgress -> restoreCarriedProgress()
				PlaybackEffect.CancelContinuation -> cancelContinuation()
				is PlaybackEffect.OpenContinuation -> openContinuation(effect.reason)
				PlaybackEffect.CancelStoppedFinalizationGrace -> cancelNativeStoppedFinalization()
				PlaybackEffect.ScheduleStoppedFinalizationGrace -> scheduleNativeStoppedFinalization()
				PlaybackEffect.InvalidateInFlightIdentityRequest -> invalidateNativeResolution()
				PlaybackEffect.ClearPreResolvedNativeIdentity -> {
					resolverContext = resolverContext.copy(
						preResolvedNativeVideoId = null,
						preResolvedNativeRoute = null,
					)
				}
				PlaybackEffect.RequestCarryAuthority -> requestNativeCarryAuthority()
				PlaybackEffect.RebindTrackInstanceEvidence -> adapter.bindTrackInstance(
					mediaSessionAdInstance(), trackInstanceEstablishedAtMillis,
				)
				// The reducer knows no package name — that is the boundary rule made
				// structural rather than aspirational — so the prefix is added here,
				// where the name legitimately lives.
				is PlaybackEffect.Note -> EventLog.append(
					effect.tag,
					"$packageName ${effect.message.replace("transport session", adapter.transportDiagnosticName)}",
				)
				is PlaybackEffect.LogMetadata -> logMetadata(metadata, effect.reason)
				is PlaybackEffect.LogTransportState -> logPlaybackState(state, effect.reason)
			}
		}

		/** Tokenized grace for exact-ID-less native STOPPED → metadata replacement. */
		private var nativeStoppedFinalizeToken: Long = 0
		/** One line per display-off hold, not one per poll. */
		private var nativeStoppedScreenOffNoted = false
		/** Epoch boundary shared with a continuation if this STOPPED loses its session. */
		private var nativeStoppedInterruptionStartedAtMillis: Long? = null
		private var nativeStoppedInterruptionDeadlineMillis: Long? = null
		/** Position reset/restore signature proving the native playback surface disappeared. */
		private var nativeStoppedResetFromPositionMs: Long? = null
		private var nativeStoppedSurfaceDisappearanceConfirmed = false
		/** Generation/signature for one bounded in-flight native carry lookup. */
		private var nativeResolutionGeneration: Long = 0
		private var nativeResolutionSignature: String? = null
		/** Transport stamp of a persisted finalization suppressing this AndroidSessionBinding. */
		private var restartableEndedTransportStamp: Long? = null

		/**
		 * Tombstone a Chromium controller restored unchanged after this process died.
		 * The store compares only a hashed semantic identity and monotonic stamps;
		 * this AndroidSessionBinding owns the live callback needed to recognize a later genuine replay.
		 */
		fun suppressRestoredEndedTransport(): Boolean {
			if (!observesHostScreenEvidence || !trackIdentity.isUsable ||
				transportStateOf(state) != TransportState.PLAYING
			) return false
			val nowEpochMs = System.currentTimeMillis()
			val nowElapsedMs = SystemClock.elapsedRealtime()
			val match = finalizedPlaybackTombstones.findStaleTransport(
				packageName = packageName,
				semanticKey = trackIdentity.semanticKey,
				nowEpochMs = nowEpochMs,
				nowElapsedMs = nowElapsedMs,
				transportUpdatedAtElapsedMs = state?.lastPositionUpdateTime ?: 0L,
				positionMs = extrapolatedPosition(state),
			) ?: return false
			restartableEndedTransportStamp = match.transportUpdatedAtElapsedMs
			dispatch(PlaybackInput.TrackFrozen)
			invalidateVideoFactsRequest()
			adapter.releaseTrackInstance(mediaSessionAdInstance())
			EventLog.append(
				"playback",
				"$packageName restored an unchanged transport already finalized before restart; " +
					"keeping it out of live playback",
			)
			return true
		}

		/**
		 * Token for progress waiting to see whether Chrome creates a replacement
		 * MediaSession. While present, disappearance is not a track ending.
		 */
		private var continuationToken: Long? = null
		private var continuationTrackIdentity: TrackIdentity? = null

		/** Resolver inputs accumulated while this track was the active AndroidSessionBinding. */
		private var resolverContext: ResolverContext = ResolverContext()

		private val callback = object : MediaController.Callback() {
			override fun onMetadataChanged(md: MediaMetadata?) {
				val outgoingInstanceToken = trackInstanceToken
				dispatch(
					PlaybackInput.PositionSeen(
						positionMs = extrapolatedPosition(state),
						establishFirst = false,
					),
				)
				dispatch(
					PlaybackInput.MetadataPublished(
						identity = adapter.trackIdentity(md?.asFields()),
						// The browser is describing its *tab* now, not a track — the
						// document title, with the origin where the channel was. Read
						// here rather than in the reducer because it is the one question
						// that needs the raw bundle. See [BrowserTabMetadata].
						namesTabOnly = adapter.namesContainerOnly(md?.asFields()),
						outgoingTransportHasExactId =
							adapter.trackIdentity(currentMetadata?.asFields()).hasExactSourceItemId,
						hasPreResolvedNativeId = resolverContext.preResolvedNativeVideoId != null,
						outgoingTitle = titleOf(currentMetadata),
						nowMillis = System.currentTimeMillis(),
						elapsedRealtimeMs = SystemClock.elapsedRealtime(),
						nextInstanceToken = allocateTrackToken(evidenceSourceSession),
					),
					incoming = md,
				)
				if (trackInstanceToken != outgoingInstanceToken &&
					!listen.describingTabOnly && trackIdentity.isUsable
				) {
					browserForegroundLeadIn.noteMetadataBoundary(
						packageName = packageName,
						key = sessionKey,
						instanceToken = trackInstanceToken,
						atMillis = trackInstanceEstablishedAtMillis,
					)
				}
				arbitrateExclusivePlayback(this@AndroidSessionBinding)
				publish()
			}

			override fun onPlaybackStateChanged(ps: PlaybackState?) {
				// Read before the transport is replaced, so wrap detection compares
				// where the player was against where it now is.
				val previousPosition = extrapolatedPosition(state)
				var restartedFromTombstone = false
				val endedStamp = restartableEndedTransportStamp
				if (endedStamp != null && transportStateOf(ps) == TransportState.PLAYING &&
					(ps?.lastPositionUpdateTime ?: 0L) > endedStamp
				) {
					restartableEndedTransportStamp = null
					restartedFromTombstone = true
					dispatch(
						PlaybackInput.FreshPlaybackAfterRestartTombstone(
							nowMillis = System.currentTimeMillis(),
							elapsedRealtimeMs = SystemClock.elapsedRealtime(),
							nextInstanceToken = allocateTrackToken(evidenceSourceSession),
						),
					)
				}
				if (transportStateOf(ps) != TransportState.PLAYING) {
					browserForegroundLeadIn.invalidate(packageName, sessionKey)
				}
				state = ps
				dispatch(
					PlaybackInput.TransportChanged(
						transport = transportStateOf(ps),
						speed = speedOf(ps),
						previousPositionMs = previousPosition.takeUnless { restartedFromTombstone },
						newPositionMs = extrapolatedPosition(ps),
						rawPositionMs = ps?.position,
						durationMs = durationOf(currentMetadata),
						transportHasExactId =
							adapter.trackIdentity(currentMetadata?.asFields()).hasExactSourceItemId,
						elapsedRealtimeMs = SystemClock.elapsedRealtime(),
						nowMillis = System.currentTimeMillis(),
						nextInstanceToken = allocateTrackToken(evidenceSourceSession),
					),
				)
				noteNativeStoppedInterruptionEvidence(
					previousPositionMs = previousPosition.takeUnless { restartedFromTombstone },
					newPositionMs = extrapolatedPosition(ps),
					transport = transportStateOf(ps),
				)
				arbitrateExclusivePlayback(this@AndroidSessionBinding)
				publish()
			}

			override fun onSessionDestroyed() {
				browserForegroundLeadIn.invalidate(packageName, sessionKey)
				logDeparture("× $packageName destroyed")
				// Before anything else: this token is spent, whatever the active
				// session list says next.
				noteSessionDestroyed(sessionKey)
				dispatch(
					PlaybackInput.SessionDestroyed(
						elapsedRealtimeMs = SystemClock.elapsedRealtime(),
						continuationOpen = continuationToken != null,
					),
				)
				publish()
			}
		}

		init {
			adapter.bindTrackInstance(mediaSessionAdInstance(), trackInstanceEstablishedAtMillis)
			controller.registerCallback(callback, handler)
			// A session that appears already knowing its track is usually a
			// replacement for one Chrome just tore down.
			restoreCarriedProgress()
		}

		fun dispose(finalize: Boolean = true, allowContinuation: Boolean = true) {
			browserForegroundLeadIn.invalidate(packageName, sessionKey)
			dispatch(
				PlaybackInput.Disposed(
					finalize = finalize,
					allowContinuation = allowContinuation,
					elapsedRealtimeMs = SystemClock.elapsedRealtime(),
					continuationOpen = continuationToken != null,
				),
			)
			// A disposed watch may not keep a deadline. It has left [watches], its
			// callbacks are about to be unregistered, and nothing can supersede a
			// pending expiry any more — but the posted block still holds this
			// object, and [dispatch] has just re-armed it, because a disposal that
			// deliberately does not finalize leaves a PLAYING listen behind.
			//
			// Measured by the old/new parity gate: a user Stop on a 200 s track at
			// 150 s scored it 80 s later, from a timer the Stop was supposed to have
			// ended. `ProbeParityTest.user Stop discards what was in flight` is the
			// old implementation refusing to do that, and it refuses because it had
			// no such timer at all — the deadline arrived with this migration, and
			// so did the leak.
			idleFinalization.cancel()
			invalidateVideoFactsRequest()
			adapter.releaseTrackInstance(mediaSessionAdInstance())
			runCatching { controller.unregisterCallback(callback) }
		}

		/**
		 * Park this track's progress for a replacement MediaSession.
		 *
		 * Whether a continuation may be opened at all is the reducer's decision;
		 * this is the half that needs Android — the carry store, the frozen
		 * evidence, the departure line and the timer.
		 *
		 * If no matching replacement claims the progress during
		 * [TrackProgressCarry.TTL_MS], the delayed callback finalizes the aggregate
		 * exactly once. A token keeps an older callback from consuming a newer
		 * continuation with the same key.
		 */
		private fun openContinuation(reason: String) {
			val now = System.currentTimeMillis()
			val identityKey = trackIdentity
			val interruptedStoppedTransport =
				state?.state == PlaybackState.STATE_STOPPED &&
					nativeStoppedSurfaceDisappearanceConfirmed
			val interruptionStartedAt = nativeStoppedInterruptionStartedAtMillis
				.takeIf { interruptedStoppedTransport }
			val interruptionDeadline = nativeStoppedInterruptionDeadlineMillis
				.takeIf { interruptedStoppedTransport }
			val frozenCoverage = currentAccessibilityCoverage(now)
			// This AndroidSessionBinding leaves the active map immediately after disappearance.
			// Freeze what it knew while active; the expiry callback must not
			// acquire whatever id the foreground tab names a minute later.
			val frozenIdentity = lastStableIdentity ?: YouTubeProbe.Identity.Unconfirmed(
				"identity was not established before the session ended",
			)
			val progress = TrackProgressCarry.Progress(
				playedMs = playedMs,
				trackStartedAtEpochSec = trackStartedAtEpochSec,
				fastestSpeedSeen = fastestSpeedSeen,
				atMillis = now,
				lastPositionMs = extrapolatedPosition(state),
				loopDetected = loopDetected,
				identity = frozenIdentity,
				explicitAdSignal = explicitAdSignal,
				accessibilityCoverage = frozenCoverage,
				trackInstanceToken = trackInstanceToken,
				promptFinalization =
					shouldFinalizeContinuationPromptly?.invoke(
						playedMs,
						identityKey.durationMs,
					) == true,
				automaticWriteAuthorization = trackAutomaticWriteAuthorization,
				interruptionStartedAtMillis = interruptionStartedAt,
				interruptionDeadlineMillis = interruptionDeadline,
			)
			val token = TrackProgressCarry.remember(
				packageName = packageName,
				trackIdentity = identityKey,
				progress = progress,
			)
			if (token == null) {
				val originalInterruptionExpired = interruptionDeadline?.let { now >= it } == true
				clearNativeStoppedInterruption()
				if (originalInterruptionExpired) {
					finalizeCurrent("screen-off interruption expired before session continuation")
				}
				return
			}
			continuationIdentity = frozenIdentity
			continuationAccessibilityCoverage = frozenCoverage
			continuationToken = token
			continuationTrackIdentity = identityKey
			// Frozen here, once, while this AndroidSessionBinding still knows what it was
			// playing. Taken as a copy so the row cannot count time nobody observed.
			if (hasPresentableIdentity && !suppressedByForegroundShort) {
				// One waiting row per app. A second listen reaching this point means
				// the app moved on from the first, so the first is no longer what
				// Now is about — its carry entry, its timer and its finalization are
				// untouched, and only the row stops being shown.
				pendingContinuations.keys.removeAll { it.startsWith("$packageName\u0000") }
				pendingContinuations[continuationRowKey(packageName, identityKey.semanticKey)] =
					PendingContinuation(
						continuationToken = token,
						snapshot = snapshot().copy(isPlaying = false, awaitingContinuation = true),
					)
			}
			// Whether position will be allowed to speak for this one, which is the
			// same predicate the claim will apply — asked here against the carry's
			// own stopping point so the log states the wait it will actually keep.
			val resumable = TrackProgressCarry.holdsResumeWindow(identityKey, progress)
			val continuationDeadline =
				TrackProgressCarry.continuationDeadlineMillis(identityKey, progress)
			val remainingMs = (continuationDeadline - now).coerceAtLeast(0)

			logDeparture(
				"$packageName [$reason] waiting " +
					if (resumable) {
						(if (interruptionDeadline != null) {
							"until the original screen-off interruption deadline " +
								"(${remainingMs / 1000}s remain)"
						} else {
							"up to ${TrackProgressCarry.RESUMED_TTL_MS / 60_000}m"
						}) + " for this listen to " +
							"resume near ${(progress.lastPositionMs ?: 0L) / 1000}s before finalizing"
					} else {
						"${TrackProgressCarry.TTL_MS / 1000}s for a replacement session " +
							"before finalizing"
					},
			)
			clearNativeStoppedInterruption()
			// Polled rather than fired once: the wait can now end three ways — a
			// claim, a newer continuation displacing this one, or the deadline —
			// and only asking can tell "still waiting" from "someone else settled
			// it". Every tick either collects this fragment or confirms it is
			// still owed a continuation.
			val tick = object : Runnable {
				override fun run() {
					val expired = TrackProgressCarry.expire(packageName, identityKey, token)
					if (expired != null) {
						forgetPendingContinuation(packageName, identityKey, token)
						publish()
						if (continuationToken == token && !finalized) {
							continuationToken = null
							continuationTrackIdentity = null
							finalizeCurrent("session continuation expired")
							if (adapter.teardownPolicy == SourceTeardownPolicy.DISCARD_AND_RESET &&
								watches.values.none { it.packageName == packageName }
							) {
								clearPackageState(packageName)
							}
						}
						return
					}
					// Nothing to collect and nothing still waiting: this
					// continuation is gone — claimed by a replacement, cancelled,
					// or pruned by someone else's `remember` before this poll
					// reached it. The row it was showing outlives it either way,
					// and a row that no continuation stands behind is a listen
					// Now claims is waiting to resume when nothing is. Only this
					// continuation's own row goes; a newer one under the same key
					// is another listen's, still owed its wait.
					if (!TrackProgressCarry.isPending(packageName, identityKey, token)) {
						if (forgetPendingContinuation(packageName, identityKey, token)) publish()
						return
					}
					if (continuationToken == token && !finalized) {
						handler.postDelayed(this, TrackProgressCarry.TTL_MS)
					}
				}
			}
			val firstPollDelayMs = minOf(TrackProgressCarry.TTL_MS, remainingMs) +
				CONTINUATION_TIMER_SLOP_MS
			handler.postDelayed(tick, firstPollDelayMs)
		}

		private fun cancelContinuation() {
			val token = continuationToken ?: return
			val identityKey = continuationTrackIdentity ?: trackIdentity
			TrackProgressCarry.cancel(packageName, identityKey, token)
			forgetPendingContinuation(packageName, identityKey, token)
			continuationToken = null
			continuationTrackIdentity = null
			continuationAccessibilityCoverage = null
		}

		/**
		 * Take back play time from a session that vanished mid-track.
		 *
		 * `trackStartedAtEpochSec` is restored along with the clock, so the
		 * on-chain `timestamp` names when the listen actually began rather than
		 * when Chrome happened to rebuild its session — and so the dedup key stays
		 * stable across the restart.
		 */
		private fun restoreCarriedProgress() {
			if (suppressedByForegroundShort) return
			// Where this session picked the item up. Inside the metadata window it
			// goes unused; past that window it is the whole evidence that this is
			// the same viewing resuming rather than a later one starting.
			val carried = TrackProgressCarry.claim(
				packageName = packageName,
				trackIdentity = trackIdentity,
				resumePositionMs = extrapolatedPosition(state) ?: firstSeenPositionMs,
				// A browser keeps its exact id in the address-bar latch rather than
				// on the identity, and past the metadata window the claim has to be
				// able to name the video either way.
				resumeVideoId = (lastStableIdentity as? YouTubeProbe.Identity.Confirmed)?.videoId
					?: latchedVideo?.videoId,
			) ?: return
			// This listen is live again on this AndroidSessionBinding; the waiting row was it.
			forgetPendingContinuation(packageName, trackIdentity, carried)
			trackAutomaticWriteAuthorization = carried.automaticWriteAuthorization
			// Deactivated on the *outgoing* instance and re-activated on the one the
			// carry establishes, so the order around the token change is load-bearing
			// and is kept here rather than folded into the reduction.
			adapter.unbindTrackInstance(mediaSessionAdInstance())
			dispatch(
				PlaybackInput.ProgressCarried(
					playedMs = carried.playedMs,
					startedAtEpochSec = carried.trackStartedAtEpochSec,
					fastestSpeedSeen = carried.fastestSpeedSeen,
					loopDetected = carried.loopDetected,
					instanceToken = carried.trackInstanceToken,
					lastPositionMs = carried.lastPositionMs,
					currentPositionMs = extrapolatedPosition(state),
					durationMs = durationOf(currentMetadata),
					nowMillis = System.currentTimeMillis(),
				),
			)
			adapter.bindTrackInstance(mediaSessionAdInstance(), trackInstanceEstablishedAtMillis)
			carried.identity?.let { identity ->
				lastStableIdentity = when (identity) {
					is YouTubeProbe.Identity.Confirmed -> identity.copy(
						source = "${identity.source} (carried across session restart)",
					).also {
						latchedVideo = it
						requestVideoFacts(it.videoId)
					}
					else -> identity
				}
			}
			explicitAdSignal = carried.explicitAdSignal
			// Which of these a source accepts back is its own: a browser re-establishes
			// the ad label and the screen coverage against the new instance, and a native
			// listen has neither to re-establish.
			adapter.restoreCarriedEvidence(
				SourceCarryRestoreRequest(
					instance = mediaSessionAdInstance(),
					establishedAtMillis = trackInstanceEstablishedAtMillis,
					explicitAdSignal = carried.explicitAdSignal,
					accessibilityCoverage = carried.accessibilityCoverage,
					atMillis = carried.atMillis,
				),
			).accessibilityCoverage?.let { accessibilityCoverage = it }
			EventLog.append(
				"session",
				"$packageName resumed \"${trackIdentity.semanticKey}\" after a session restart — " +
					"carrying ${carried.playedMs / 1000}s of play time forward" +
					when {
						loopDetected && explicitAdSignal != null ->
							", a detected loop, and explicit ad evidence"
						loopDetected -> " and a detected loop"
						explicitAdSignal != null -> " and explicit ad evidence"
						else -> ""
					},
			)
		}

		/**
		 * Request finalization. Guarded so the several paths
		 * that can end a track (metadata change, stop, session destroyed,
		 * disposal — several of which fire together) only report once.
		 */
		fun finalizeCurrent(reason: String, persistRestartTombstone: Boolean = false) {
			dispatch(
				PlaybackInput.FinalizeRequested(
					reason = reason,
					elapsedRealtimeMs = SystemClock.elapsedRealtime(),
					persistRestartTombstone = persistRestartTombstone,
				),
			)
		}

		/** Immutable arbitration view; no selector may reach live Android objects. */
		fun concurrentPlaybackCandidate(): ConcurrentPlaybackCandidate =
			ConcurrentPlaybackCandidate(
				key = sessionKey,
				packageName = packageName,
				live = acceptsLiveEvidence,
				sourceProven = adapter.evidenceCapabilities.packageProvesSource ||
					lastStableIdentity?.isSourceProven == true,
				packageProvesSource = adapter.evidenceCapabilities.packageProvesSource,
				publishesHostScreenEvidence = observesHostScreenEvidence,
				transport = listen.transport,
				rawPositionMs = state?.position?.takeIf { it >= 0 },
				metadataUsable = trackIdentity.isUsable,
			)

		/**
		 * Freeze the host listen at the exact ownership boundary. A strictly newer
		 * PLAYING sample may start a genuine replay through the same restart path as
		 * a persisted idle-finalization tombstone; the unchanged stale transport may
		 * never resume itself.
		 */
		fun finalizeForExclusiveTakeover(takeoverPackage: String) {
			if (!acceptsLiveEvidence) return
			restartableEndedTransportStamp = state?.lastPositionUpdateTime ?: return
			EventLog.append(
				"playback",
				"$packageName kept PLAYING without a readable position after " +
					"$takeoverPackage published first-party progress; ending the host listen " +
					"at the playback-ownership boundary",
			)
			finalizeCurrent(
				"first-party YouTube playback took over",
				persistRestartTombstone = true,
			)
		}

		/**
		 * Build the immutable snapshot and hand it to the finalization callback.
		 *
		 * The reducer has already decided this track is over and latched it, so
		 * there is no guard here: reaching this method *is* the decision.
		 */
		private fun freezeAndReport(reason: String, persistRestartTombstone: Boolean) {
			val snapshot = snapshot(finalizedTrack = true)
			clearNativeStoppedInterruption()
			// Persist only the terminal condition this store can identify safely on a
			// later process: Chromium kept PLAYING after the item ran out. A service
			// teardown also finalizes in-flight work, but the identical transport may
			// still be genuinely playing; tombstoning that would hide a live listen.
			if (observesHostScreenEvidence && persistRestartTombstone) {
				val nowEpochMs = System.currentTimeMillis()
				finalizedPlaybackTombstones.record(
					packageName = packageName,
					semanticKey = trackIdentity.semanticKey,
					finalizedAtEpochMs = nowEpochMs,
					finalizedAtElapsedMs = SystemClock.elapsedRealtime(),
					transportUpdatedAtElapsedMs = state?.lastPositionUpdateTime ?: 0L,
					durationMs = snapshot.durationMs,
				)
			}

			if (mayRecordIdentifyingDetail()) {
				EventLog.append(
					"finalize",
					"$packageName [$reason] ${snapshot.title ?: "<untitled>"} — " +
						"played ${snapshot.playedMs / 1000}s of " +
						"${(snapshot.durationMs ?: 0) / 1000}s" +
						// Named only when it applies, so the ordinary line is unchanged
						// and a sped-up listen is obvious rather than looking like a
						// mis-measured one.
						(if (fastestSpeedSeen > 1.0) " (up to ${fastestSpeedSeen}× speed)" else "") +
						unobservedLeadInNote(snapshot),
				)
			}
			onTrackFinalized?.invoke(snapshot)
			invalidateVideoFactsRequest()
			adapter.notes(SourceMoment.FINALIZED).forEach { EventLog.append(it.tag, it.message) }
			adapter.releaseTrackInstance(mediaSessionAdInstance())
		}

		/**
		 * Clear everything a new track must not inherit from the last one.
		 *
		 * The evidence half of what used to be `resetForNewTrack`; the measurement
		 * half is `ListenState.startNewTrack` in [PlaybackReducer], and the two are
		 * always performed together because the reducer emits this effect on exactly
		 * the transitions that reset the state.
		 */
		private fun clearTrackScopedEvidence() {
			// This effect is performed only after the reducer has installed the new
			// logical track. Freeze the current opt-in interval at that same boundary.
			trackAutomaticWriteAuthorization = automaticWriteAuthorization()
			clearNativeStoppedInterruption()
			invalidateNativeResolution()
			taintedReason = null
			latchedVideo = null
			lastStableIdentity = null
			continuationIdentity = null
			continuationAccessibilityCoverage = null
			explicitAdSignal = null
			accessibilityCoverage = null
			rejectedVideoIds.clear()
			titleOnlyRejectedVideoIds.clear()
			unconfirmedVideoIds.clear()
			invalidateVideoFactsRequest()
			resolverContext = ResolverContext()
		}

		/**
		 * Ask once per source-eligible native presentation for a unique immutable id.
		 * Foreground Shorts have their own exact owner-handle route and never enter
		 * here. This establishes carry identity, not ownership of measured playback.
		 */
		private fun requestNativeCarryAuthority() {
			if (suppressedByForegroundShort || finalized || !isPlaying(state)) return
			if (!listenNeedsPresentationProof(
					hasExactSourceItemId = trackIdentity.hasExactSourceItemId,
					presentationAttributionAmbiguous = listen.presentationUnprovenForNamedWork,
				)
			) return
			// Whether this presentation is worth one bounded lookup is the source's
			// question: a browser's exact id is already in the address bar.
			if (!adapter.mayPreResolveExactItemId(
					SourceExactIdRequest(
						fields = currentMetadata?.asFields(),
						durationMs = durationOf(currentMetadata),
					),
				)
			) return
			// Both are guaranteed by the answer above; read here for the log line and
			// for the signature the in-flight request is keyed by.
			val title = titleOf(currentMetadata) ?: return
			val duration = durationOf(currentMetadata) ?: return
			val requester = onNativeIdentityRequested ?: return
			val signature = "${trackIdentity.semanticKey}|$duration"
			if (nativeResolutionSignature == signature) return
			nativeResolutionSignature = signature
			val generation = ++nativeResolutionGeneration
			val requestSnapshot = snapshot()
			// The exact length the resolver is about to corroborate against real
			// catalog rows. Read from the request, not from the callback: only this
			// number was actually proven, and the reducer refuses the attribution if
			// the presentation has moved on by the time the answer lands.
			val attributedPresentationMs = requestSnapshot.resolverContext.presentationDurationMs
				?: requestSnapshot.durationMs
			EventLog.append(
				"native-carry",
				"$packageName pre-resolving stable exact-ID-less track \"$title\" for controller continuity",
			)
			requester(requestSnapshot) { proof ->
				handler.post {
					if (generation != nativeResolutionGeneration || finalized ||
						suppressedByForegroundShort ||
						!NativeSourceSwitches.isSnapshotCurrent(packageName, sourceEpoch)
					) return@post
					val currentDuration = durationOf(currentMetadata) ?: return@post
					val currentSignature = "${trackIdentity.semanticKey}|$currentDuration"
					if (currentSignature != signature || proof == null) return@post
					dispatch(PlaybackInput.ExactIdEstablished(proof.videoId))
					// The id alone proves the work, never the surface — an interstitial
					// borrows the song's title and artist field-for-field, so a proof
					// built only from those may have named the right song while the
					// wrong thing was playing. Only a proof that also required the
					// *currently published* length to be this work's own can say the
					// interval being measured belongs to it, and a 15/30/45s pre-roll
					// cannot satisfy that.
					//
					// Asked of the proof, not of the route: the route buckets say which
					// listing named the work, and a Video-mode presentation — whose
					// length matches no catalog row — is corroborated by its own watch
					// page while landing outside the structured bucket entirely. Reading
					// the bucket instead lost every such listen.
					if (proof.presentationDurationCorroborated &&
						attributedPresentationMs != null
					) {
						dispatch(
							PlaybackInput.PresentationAttributionEstablished(
								sourceItemId = proof.videoId,
								presentationDurationMs = attributedPresentationMs,
							),
						)
						EventLog.append(
							"native-identity",
							"$packageName attributed the ${attributedPresentationMs / 1000}s " +
								"presentation to ${proof.videoId} (${proof.route}); its measured " +
								"time is the work's own",
						)
					}
					resolverContext = resolverContext.copy(
						preResolvedNativeVideoId = proof.videoId,
						preResolvedNativeRoute = proof.route,
					)
					EventLog.append(
						"native-carry",
						"$packageName established immutable carry authority ${proof.videoId} for \"$title\"",
					)
					// A replacement AndroidSessionBinding initially had no exact id, so its construction
					// could not claim anything. Only this independently resolved id may try.
					restoreCarriedProgress()
					// Whatever this id did not claim, it has now outlived: a different
					// track is playing in this app, so nothing is coming back for it.
					TrackProgressCarry.abandon(packageName, trackIdentity)
					publish()
				}
			}
		}

		private fun invalidateNativeResolution() {
			nativeResolutionGeneration++
			nativeResolutionSignature = null
		}

		private fun scheduleNativeStoppedFinalization() {
			val token = ++nativeStoppedFinalizeToken
			nativeStoppedScreenOffNoted = false
			val nowMillis = System.currentTimeMillis()
			nativeStoppedInterruptionStartedAtMillis = nowMillis
			nativeStoppedInterruptionDeadlineMillis =
				nowMillis + StoppedInterruption.SCREEN_OFF_HOLD_CAP_MS
			nativeStoppedResetFromPositionMs = null
			nativeStoppedSurfaceDisappearanceConfirmed = false
			EventLog.append(
				"native-identity",
				"$packageName exact-ID-less STOPPED state waiting " +
					"${NATIVE_STOPPED_FINALIZE_GRACE_MS / 1000}s for a metadata replacement",
			)
			postNativeStoppedFinalizeCheck(token, SystemClock.elapsedRealtime())
		}

		/**
		 * Ask again, one grace period from now, whether this STOPPED ends the listen.
		 *
		 * Polled rather than fired once because the answer can change while the
		 * grace runs: [StoppedInterruption] combines the current display state with
		 * the transport's reset-and-restore surface-disappearance evidence. Every
		 * check that decides the listen is still merely interrupted re-posts itself
		 * against the same [stoppedSinceElapsedMs], so the cap is measured from the
		 * transport's STOPPED transition rather than being renewed by each poll.
		 *
		 * A held listen is not a measuring one — a STOPPED transport banks nothing
		 * and arms no idle deadline — so this changes what the same viewing is
		 * allowed to continue into, not what it is credited.
		 */
		private fun postNativeStoppedFinalizeCheck(token: Long, stoppedSinceElapsedMs: Long) {
			handler.postDelayed(
				{
					if (token != nativeStoppedFinalizeToken || finalized) return@postDelayed
					if (state?.state != PlaybackState.STATE_STOPPED) return@postDelayed
					val stoppedForMs = SystemClock.elapsedRealtime() - stoppedSinceElapsedMs
					if (StoppedInterruption.holdsListenOpen(
							displayInteractive = displayInteractive(),
							stoppedForMs = stoppedForMs,
							surfaceDisappearanceConfirmed =
								nativeStoppedSurfaceDisappearanceConfirmed,
							stoppedAtMs = extrapolatedPosition(state),
							durationMs = durationOf(currentMetadata),
						)
					) {
						if (!nativeStoppedScreenOffNoted) {
							nativeStoppedScreenOffNoted = true
							EventLog.append(
								"native-identity",
								"$packageName proved a display-off surface interruption — holding " +
									"this listen open for the same item to continue into rather " +
									"than finalizing an interruption nobody asked for",
							)
						}
						postNativeStoppedFinalizeCheck(token, stoppedSinceElapsedMs)
						return@postDelayed
					}
					finalizeCurrent("stopped replacement grace expired")
				},
				NATIVE_STOPPED_FINALIZE_GRACE_MS,
			)
		}

		private fun cancelNativeStoppedFinalization() {
			nativeStoppedFinalizeToken++
			nativeStoppedScreenOffNoted = false
		}

		private fun noteNativeStoppedInterruptionEvidence(
			previousPositionMs: Long?,
			newPositionMs: Long?,
			transport: TransportState,
		) {
			if (transport != TransportState.STOPPED) {
				clearNativeStoppedInterruption()
				return
			}
			if (nativeStoppedInterruptionStartedAtMillis == null) return
			if (nativeStoppedResetFromPositionMs == null) {
				nativeStoppedResetFromPositionMs = StoppedInterruption.resetCandidate(
					previousPositionMs,
					newPositionMs,
				)
			}
			if (StoppedInterruption.confirmsSurfaceDisappearance(
					nativeStoppedResetFromPositionMs,
					newPositionMs,
				)
			) {
				nativeStoppedSurfaceDisappearanceConfirmed = true
			}
		}

		private fun clearNativeStoppedInterruption() {
			cancelNativeStoppedFinalization()
			nativeStoppedInterruptionStartedAtMillis = null
			nativeStoppedInterruptionDeadlineMillis = null
			nativeStoppedResetFromPositionMs = null
			nativeStoppedSurfaceDisappearanceConfirmed = false
		}

		/**
		 * Hand ownership to the structural foreground-Short lifecycle.
		 *
		 * Whether the outgoing listen is scored or discarded is the reducer's
		 * decision; what belongs here is the one question it cannot answer without
		 * the session's own bundle — whether the Short being acquired *is* the item
		 * this MediaSession was already describing. [ForegroundShortHandover] decides
		 * that on published evidence alone.
		 */
		fun suppressForForegroundShort(incomingShort: SessionSnapshot?) {
			dispatch(
				PlaybackInput.ForegroundShortTookOver(
					shortDescribesSameItem = ForegroundShortHandover.describesSameItem(
						sessionTitle = titleOf(currentMetadata),
						sessionDurationMs = durationOf(currentMetadata),
						shortTitle = incomingShort?.title,
						shortDurationMs = incomingShort?.durationMs,
					),
					elapsedRealtimeMs = SystemClock.elapsedRealtime(),
				),
			)
		}

		/** Resume conservative MediaSession observation from a fresh zero baseline. */
		fun releaseForegroundShortSuppression() {
			dispatch(
				PlaybackInput.ForegroundShortReleased(
					identity = adapter.trackIdentity(metadata?.asFields()),
					nowMillis = System.currentTimeMillis(),
					elapsedRealtimeMs = SystemClock.elapsedRealtime(),
					nextInstanceToken = allocateTrackToken(evidenceSourceSession),
				),
			)
		}

		/**
		 * Identity for the given metadata, using the hint bound to *this*
		 * session rather than whatever landed last for the package, applying
		 * the taint rule, and latching the video id for the track's lifetime.
		 */
		private fun identityOf(md: MediaMetadata?): YouTubeProbe.Identity {
			// A AndroidSessionBinding waiting outside the active map has ended. Its identity was
			// frozen at disappearance; consulting live evidence here is the exact
			// race that paired Karol G's progress with the next Short's payload.
			continuationIdentity?.let { return it }

			val sessionTitle = titleOf(md)
			// Every question about what *this source's* evidence says is the adapter's.
			// The address bar, the media notification, the native watch screen's
			// playlist bar and the metadata reading itself all differ per source, and
			// they used to differ inside this method as four source-kind ternaries.
			val reading = adapter.readIdentity(
				SourceIdentityRequest(
					fields = md?.asFields(),
					rejectedItemIds = rejectedVideoIds,
					resolverContext = resolverContext,
					soleSession = soleHostSession,
				),
			)
			resolverContext = reading.resolverContext
			// This Android registry observes the YouTube family, so its live identity
			// is intentionally YouTube-typed. Source-neutral expansion enters
			// downstream as `FinalizedTrack`, as the foreign-source fixture proves.
			val live = reading.identity as? YouTubeProbe.Identity
				?: error(
					"the YouTube MediaSession registry cannot carry " +
						"a ${reading.identity.source} identity",
				)
			var rejectedThisPass = false

			if (live is YouTubeProbe.Identity.Unconfirmed && live.provenOtherSite) {
				// §4.1. The reason names the site — "notification says
				// w3schools.com, not YouTube" — which is precisely the record this
				// section removes, so it is kept in memory for the skip path and
				// never written. That a session was proven to be somewhere else is
				// exactly the fact that must leave no trace.
				taintedReason = live.reason
			}

			// Latch the first confirmation. Later confirmations with a
			// different id are the address bar moving on, not this track
			// changing — a track change resets the latch.
			if (taintedReason == null && latchedVideo == null &&
				live is YouTubeProbe.Identity.Confirmed &&
				live.videoId !in rejectedVideoIds
			) {
				latchedVideo = live.copy(source = "${live.source} (latched)")
				EventLog.append("identity", "$packageName latched video ${live.videoId} for this track")
				// The page named what it is playing, which a feed tile never does.
				// See `PlaybackReducer.withEstablishedTimeline`.
				dispatch(PlaybackInput.PageNamedItem)
				requestVideoFacts(live.videoId)
			}

			var titleCorroboratedObservedId = false
			latchedVideo?.let { l ->
				val known = knownVideoFor?.invoke(l.videoId)
				val titleEvidence = if (known?.title != null && sessionTitle != null) {
					VideoTitleMatcher.compare(known.title, sessionTitle)
				} else {
					null
				}
				val weakEvidenceAllowed = titleEvidence?.let {
					titleEvidenceMayRetainObservedId(
						evidence = it,
						candidateVideoId = l.videoId,
						candidateGeneration = l.urlGeneration,
						observedVideoId = resolverContext.observedVideoId,
						observedGeneration = resolverContext.urlGeneration,
						sessionDurationMs = establishedDurationMs(md),
						pageDurationSeconds = known?.lengthSeconds,
					)
				} == true
				val disagreement = known?.let {
					latchDisagreement(
						titleEvidence = titleEvidence,
						weakEvidenceAllowed = weakEvidenceAllowed,
						pageTitle = it.title,
						sessionTitle = sessionTitle,
						sessionDurationMs = establishedDurationMs(md),
						pageDurationSeconds = it.lengthSeconds,
					)
				}
				if (disagreement == null && weakEvidenceAllowed) {
					titleCorroboratedObservedId = true
				}
				if (disagreement != null) {
					val worthSaying = disagreement.contradicts ||
						unconfirmedVideoIds.add(l.videoId)
					if (worthSaying) {
						EventLog.append(
							"identity",
							"$packageName unlatched ${l.videoId}: ${disagreement.reason}",
						)
					}
					if (disagreement.contradicts) {
						rejectedVideoIds += l.videoId
						if (disagreement.titleOnly) titleOnlyRejectedVideoIds += l.videoId
						else titleOnlyRejectedVideoIds -= l.videoId
					}
					latchedVideo = null
					rejectedThisPass = true
				}
			}

			val selected = taintedReason?.let {
				YouTubeProbe.Identity.Unconfirmed("another site was proven earlier: $it", true)
			} ?: identityAfterCorroboration(
				latched = latchedVideo,
				live = live,
				rejectedVideoIds = rejectedVideoIds,
				rejectedThisPass = rejectedThisPass,
			)
			if (titleCorroboratedObservedId && selected is YouTubeProbe.Identity.Confirmed) {
				val livePackageWatches = watches.values.filter {
					it.packageName == packageName && it.acceptsLiveEvidence
				}
				browserForegroundLeadIn.claim(
					packageName = packageName,
					videoId = selected.videoId,
					urlGeneration = selected.urlGeneration,
					key = sessionKey,
					instanceToken = trackInstanceToken,
					throughMillis = trackInstanceEstablishedAtMillis,
					nowMillis = System.currentTimeMillis(),
					currentlySoleAndPlaying = livePackageWatches.singleOrNull() === this &&
						playbackEligible,
				)?.let { credit ->
					dispatch(
						PlaybackInput.VerifiedLeadIn(
							playedMs = credit.playedMs,
							startedAtEpochSec = credit.startedAtEpochSec,
							elapsedRealtimeMs = SystemClock.elapsedRealtime(),
						),
					)
					EventLog.append(
						"playback",
						"$packageName credited ${credit.playedMs / 1000}s seen on the exact " +
							"foreground Short before Chromium published its corroborating track metadata",
					)
				}
			}
			val identityJustConfirmed = selected is YouTubeProbe.Identity.Confirmed &&
				lastStableIdentity !is YouTubeProbe.Identity.Confirmed
			lastStableIdentity = selected
			// What follows from the answer is the source's too, and it is the half that
			// cannot be pure: a browser retries a parked carry the moment its bar
			// finally names the item, and looks for visible ad evidence filed against
			// exactly that id. The adapter decides; this performs, in order.
			adapter.onIdentitySelected(
				SourceIdentitySelected(identity = selected, justConfirmed = identityJustConfirmed),
			).forEach { effect ->
				when (effect) {
					SourceEffect.RetryCarriedProgressClaim -> restoreCarriedProgress()
					is SourceEffect.BindVisibleAdEvidence -> noteAdEvidence(effect.evidence)
				}
			}
			return selected
		}

		/**
		 * Persist explicit ad evidence only when it names this exact Short.
		 *
		 * The id binding is mandatory. A visible watch-page pre-roll label sits
		 * over the real video's URL; treating the package alone as sufficient
		 * would veto the content the user actually chose.
		 */
		fun noteAdEvidence(evidence: AdEvidence.Evidence) = adopt(
			if (!playbackEligible) SourceAdVerdict.NONE else adapter.bindVisibleAdEvidence(
				SourceVisibleAdRequest(
					evidence = evidence,
					identity = latchedVideo
						?: (lastStableIdentity as? YouTubeProbe.Identity.Confirmed),
					currentSignal = explicitAdSignal,
				),
			),
		)

		/**
		 * Take the source's verdict about an ad observation, and say what it said.
		 *
		 * A verdict with no signal is not "no ad": it is this source declining to
		 * attribute *this* observation to *this* listen, which is the whole of what
		 * the id binding and the instance token exist to decide.
		 */
		private fun adopt(verdict: SourceAdVerdict) {
			verdict.signal?.let { explicitAdSignal = it }
			verdict.notes.forEach { EventLog.append(it.tag, it.message) }
		}

		/** The listen existed, and was playing, before the observation was made. */
		private fun instanceEstablishedBefore(atMillis: Long): Boolean =
			trackIdentity.isUsable && isPlaying(state) &&
				trackInstanceEstablishedAtMillis <= atMillis

		private fun noteAdvertisementMetadata(md: MediaMetadata?) {
			val metadata = md ?: return
			if (explicitAdSignal != null) return
			val flagged = runCatching {
				metadata.getLong(METADATA_KEY_ADVERTISEMENT) != 0L
			}.getOrDefault(false)
			if (!flagged) return
			explicitAdSignal = ADVERTISEMENT_METADATA_SIGNAL
			EventLog.append(
				"ad",
				"$packageName published METADATA_KEY_ADVERTISEMENT — the player says " +
					"this is an ad, so the listen is vetoed",
			)
		}

		/** Bind an id-less ordinary-watch label only to this exact track token. */
		fun noteMediaSessionAdEvidence(observation: MediaSessionAdEvidence.Observation) = adopt(
			if (!playbackEligible) SourceAdVerdict.NONE else adapter.bindHostAdLabel(
				SourceHostAdLabelRequest(
					observation = observation,
					instance = mediaSessionAdInstance(),
					instanceEstablishedBeforeObservation =
						instanceEstablishedBefore(observation.atMillis),
					currentSignal = explicitAdSignal,
				),
			),
		)

		/** This listen's own handle, for [BrowserScanBinding]. */
		val bindingKey: Long get() = listen.instanceToken

		val listenFinalized: Boolean get() = listen.finalized

		/** Transport state, for the refusal line only — see [BrowserScanBinding]. */
		val listenPlaying: Boolean get() = listen.transport == TransportState.PLAYING

		/**
		 * Whether this listen is the one playing [videoId].
		 *
		 * Reads the latch first because that is what survives a background tab: the
		 * address bar describes whichever tab is in front, so a listen that proved
		 * its id and then went to the background keeps it here and nowhere else.
		 */
		fun describesVideo(videoId: String): Boolean =
			latchedVideo?.videoId == videoId ||
				(lastStableIdentity as? YouTubeProbe.Identity.Confirmed)?.videoId == videoId

		/** The ordinary MediaSession clock already owns this exact item. */
		fun isMeasuringVideo(videoId: String): Boolean =
			playbackEligible && !listen.describingTabOnly && trackIdentity.isUsable &&
				describesVideo(videoId)

		/** One successful root scan supplies coverage and, independently, ad input. */
		fun noteAccessibilityScan(
			scan: MediaSessionAccessibilityEvidence.Scan,
			namedThisInstance: Boolean = false,
		) {
			if (!playbackEligible) return
			val verdict = adapter.bindScreenScan(
				SourceScreenScanRequest(
					scan = scan,
					instance = mediaSessionAdInstance(),
					unambiguous = watches.values.count {
						it.packageName == packageName && it.acceptsLiveEvidence
					} == 1,
					namedThisInstance = namedThisInstance,
					instanceEstablishedBeforeObservation = instanceEstablishedBefore(scan.atMillis),
					currentSignal = explicitAdSignal,
				),
			)
			verdict.coverage?.let { accessibilityCoverage = it }
			adopt(verdict.ad)
		}

		private fun mediaSessionAdInstance() = MediaSessionAdEvidence.TrackInstance(
			packageName = packageName,
			token = trackInstanceToken,
			signature = trackIdentity,
		)

		private fun currentAccessibilityCoverage(
			now: Long = System.currentTimeMillis(),
		): MediaSessionAccessibilityEvidence.Coverage? = adapter.coverageFor(
			SourceCoverageRequest(
				instance = mediaSessionAdInstance(),
				expectedUrlGeneration = resolverContext.urlGeneration,
				localCoverage = accessibilityCoverage,
				nowMillis = now,
			),
		)?.also { accessibilityCoverage = it }

		/** The hint the probe is currently bound to, for the diagnostics card. */
		private fun boundHint(md: MediaMetadata?): NotificationHints.Hint? =
			adapter.hostNotificationHint(md?.asFields(), soleHostSession)

		fun notePlayerAdSurface(reading: NativeWatchAdParser.Reading) {
			dispatch(
				PlaybackInput.PlayerAdSurfaceObserved(
					surface = reading.surface,
					signal = reading.signal,
					elapsedRealtimeMs = SystemClock.elapsedRealtime(),
				),
			)
		}

		fun creditPipInference(nowMillis: Long, playing: Boolean) {
			if (!acceptsLiveEvidence) return
			dispatch(
				PlaybackInput.PictureInPictureObserved(
					nowMillis = nowMillis,
					playing = playing,
					durationMs = durationOf(currentMetadata),
				),
			)
		}

		private fun playedMsNow(): Long = listen.playedMsAt(SystemClock.elapsedRealtime())

		private fun speedOf(ps: PlaybackState?): Double = speedFactor(ps?.playbackSpeed)

		private fun unobservedLeadInNote(snapshot: SessionSnapshot): String {
			val unobserved = snapshot.unobservedLeadInMs
			if (unobserved <= 0) return ""
			return ", first seen ${unobserved / 1000}s in — " +
				"anything played before that was never published to RustedWax"
		}

		fun logMetadata(md: MediaMetadata?, reason: String) {
			if (!acceptsLiveEvidence) return
			noteAdvertisementMetadata(md)
			// Identity is settled *before* anything is written, because whether we
			// may write is exactly what identity decides — §4.1. The metadata is
			// still read and still drives detection; only the record of it waits.
			logIdentity(md, reason)
			if (mayRecordIdentifyingDetail()) {
				EventLog.appendBlock(
					"metadata",
					"$packageName / ${adapter.originName} ($reason)",
					MetadataDump.dump(md?.asFields()),
				)
			}
			requestNativeCarryAuthority()
		}

		/** Re-run identity after a late-arriving notification hint. */
		fun reidentify(trigger: String) {
			if (acceptsLiveEvidence) logIdentity(metadata, "re-check after $trigger")
		}

		/**
		 * Whether this session may be described in the log at all.
		 *
		 * Chrome publishes a MediaSession for *any* video site, and RustedWax
		 * built a `AndroidSessionBinding` and wrote its page title before anything had proven the
		 * site was YouTube. The result was an accumulating record of what the user
		 * watched everywhere else — timing, title and package — in a file the app
		 * offers to export. `UrlWatcherService` was never the leak; this was.
		 *
		 * Native packages are proven by the package itself. A browser session has
		 * to earn it, and until it does the only thing recorded is that some
		 * number of sessions were ignored.
		 */
		private fun mayRecordIdentifyingDetail(): Boolean =
			adapter.evidenceCapabilities.packageProvesSource ||
			lastStableIdentity is YouTubeProbe.Identity.Confirmed ||
			lastStableIdentity is YouTubeProbe.Identity.SiteOnly

		/** Whether this session has ever been named in the log. */
		private var announced = false

		/**
		 * Write the arrival line, once, at the moment the session earns one.
		 *
		 * Held back from session creation because at creation nothing has proven
		 * the site. A YouTube session reads exactly as it always did, one line
		 * later; a session that never proves itself is never named.
		 */
		private fun announceIfProven() {
			if (announced || !mayRecordIdentifyingDetail()) return
			announced = true
			EventLog.append(
				"session",
				"+ $packageName ($appLabel) ← ${adapter.originName}",
			)
			adapter.notes(SourceMoment.ANNOUNCED).forEach { EventLog.append(it.tag, it.message) }
		}

		/** Only a session that was named may be reported as leaving. */
		fun logDeparture(message: String) {
			if (announced) EventLog.append("session", message)
		}

		private fun logIdentity(md: MediaMetadata?, reason: String) {
			val resolved = identityOf(md)
			// Before the verdict is written, so a proven session reads in the same
			// order it always did: arrival, then what it turned out to be.
			announceIfProven()
			when (val id = resolved) {
				is YouTubeProbe.Identity.Confirmed -> EventLog.append(
					"identity",
					"$packageName / ${adapter.originName} → YouTube ${id.videoId} " +
						"(${if (id.isMusic) "music" else "video"}) via ${id.source}",
				)

				is YouTubeProbe.Identity.SiteOnly -> EventLog.append(
					"identity",
					"$packageName → YouTube (site only, no video id) via ${id.source}",
				)

				// Deliberately silent, and deliberately not "silent except for the
				// package name": a package plus a timestamp is still a record of
				// what someone opened and when. The aggregate is the whole signal
				// this case is allowed to produce.
				is YouTubeProbe.Identity.Unconfirmed -> unprovenSessions.note()
			}
		}

		fun logPlaybackState(ps: PlaybackState?, reason: String) {
			if (!acceptsLiveEvidence) return
			// Position tracking still runs for an unproven session — it is how a
			// browser tab that later turns out to be YouTube keeps its progress —
			// but nothing about it is written until it is proven. §4.1.
			if (ps == null) {
				if (mayRecordIdentifyingDetail()) {
					EventLog.append("playback", "$packageName ($reason) <null state>")
				}
				return
			}
			dispatch(PlaybackInput.PositionSeen(ps.position))
			if (!mayRecordIdentifyingDetail()) return
			EventLog.append(
				"playback",
				"$packageName ($reason) state=${stateName(ps.state)} " +
					"pos=${ps.position}ms speed=${ps.playbackSpeed} " +
					"updatedAt=${ps.lastPositionUpdateTime} played=${playedMsNow()}ms",
			)
			if (adapter.evidenceCapabilities.publishesStructuredTransportDump) {
				EventLog.appendBlock(
					"native-state",
					"$packageName / ${adapter.originName} ($reason)",
					PlaybackStateDump.dump(ps),
				)
			}
		}

		/**
		 * The presentation fields, as **this** source reads them.
		 *
		 * Which metadata keys are readable, and whether a value is a track's or the
		 * host container's, is source knowledge: `DISPLAY_TITLE` is a tab in a browser
		 * and a track in the native app. See [SourceAdapter.presentedTitle].
		 */
		private fun titleOf(md: MediaMetadata?): String? = adapter.presentedTitle(md?.asFields())

		private fun artistOf(md: MediaMetadata?): String? = adapter.presentedArtist(md?.asFields())

		private fun durationOf(md: MediaMetadata?): Long? =
			MetadataDump.longOrNull(md?.asFields(), MediaMetadata.METADATA_KEY_DURATION)

		private fun establishedDurationMs(md: MediaMetadata?): Long? =
			listen.establishedDurationMs(durationOf(md))

		fun snapshot(finalizedTrack: Boolean = false): SessionSnapshot {
			val md = currentMetadata
			val ps = state
			val duration = establishedDurationMs(md)
			val position = extrapolatedPosition(ps)
			val played = playedMsNow()
			// A finalized snapshot may not consult the later foreground URL. Live
			// diagnostics still re-identify so the Now card remains current.
			val identity = if (finalizedTrack) {
				continuationIdentity ?: lastStableIdentity
					?: YouTubeProbe.Identity.Unconfirmed(
						"identity was not established before the track ended",
					)
			} else {
				identityOf(md)
			}
			val known = (identity as? YouTubeProbe.Identity.Confirmed)
				?.let { knownVideoFor?.invoke(it.videoId) }
			val frozenResolver = resolverContext.copy(
				presentationDurationMs = trackIdentity.durationMs.takeIf {
					adapter.playbackCapabilities.republishesAlternateMediaDurations
				},
				knownTitle = known?.title,
				knownChannel = known?.channel,
				knownDurationSeconds = known?.lengthSeconds,
				rejectedVideoIds = rejectedVideoIds.toSet(),
				titleOnlyRejectedVideoIds = titleOnlyRejectedVideoIds.toSet(),
			)
			val frozenCoverage = if (finalizedTrack) {
				continuationAccessibilityCoverage ?: currentAccessibilityCoverage()
			} else {
				currentAccessibilityCoverage()
			}
			val presentation = finalizedPresentation(
				established = trackIdentity,
				currentTitle = titleOf(md),
				currentArtist = artistOf(md),
				currentAlbum = MetadataDump.textOrNull(md?.asFields(), MediaMetadata.METADATA_KEY_ALBUM),
			)
			return SessionSnapshot(
				packageName = packageName,
				appLabel = appLabel,
				isTarget = isTarget,
				// The last bundle is often a teardown bundle: Brave keeps the title,
				// replaces the real channel with `m.youtube.com`, and drops the
				// duration immediately before announcing the next track. Silence in
				// that bundle must not erase fields this same track established while
				// it was playing. [trackIdentity] is reset on every real track change
				// and [TrackIdentity.refinedWith] already preserves exactly those
				// established values, so it is the finalized presentation authority.
				title = presentation.title,
				artist = presentation.artist,
				album = presentation.album,
				durationMs = duration,
				positionMs = position,
				playedMs = played,
				unattributedMeasuredMs = listen.unattributedMeasuredMs,
				refusedFinalPresentationMs = listen.refusedFinalPresentationMs,
				loopDetected = loopDetected,
				explicitAdSignal = explicitAdSignal,
				browserEvidenceEnabled = !adapter.evidenceCapabilities.packageProvesSource &&
					browserEvidenceEnabledForThisRun(),
				accessibilityCoverage = frozenCoverage,
				playbackState = stateName(ps?.state ?: PlaybackState.STATE_NONE),
				isPlaying = !suppressedByForegroundShort && isPlaying(ps),
				// Percent-of-duration using content played — the input the
				// 60% / 160% rule in ScrobbleRules consumes. Both sides of this
				// division are content milliseconds, which is why the accumulator
				// has to be speed-scaled.
				percentPlayed = duration?.takeIf { it > 0 }?.let { played.toDouble() / it },
				inferredPlayedMs = pipInferredMs,
				identity = identity,
				resolverContext = frozenResolver,
				notificationHint = boundHint(md),

				metadataLines = if (finalizedTrack) MetadataDump.dump(md?.asFields()) else emptyList(),
				firstObservedPositionMs = firstSeenPositionMs,
				trackStartedAtEpochSec = trackStartedAtEpochSec,
				// The same token the ad and accessibility evidence bind to, so one
				// listen has one instance identity everywhere it is asked for —
				// including across the session recreation that restores it above.
				trackInstanceToken = trackInstanceToken,
				genre = MetadataDump.textOrNull(md?.asFields(), MediaMetadata.METADATA_KEY_GENRE),
				sourceEpoch = sourceEpoch,
				automaticWriteAuthorization = trackAutomaticWriteAuthorization,
			).also {
				Phase3Telemetry.snapshot(it, speedOf(ps), finalizedTrack)
			}
		}

		/**
		 * Bound from the moment the watcher exists.
		 *
		 * A `AndroidSessionBinding` is routinely built around a session that is **already playing** —
		 * every listener-service rebuild, every process start with a video going, and
		 * every controller that appears mid-track. Re-arming used to hang off the
		 * reduce path only, and construction reduces nothing, so exactly those listens
		 * were the ones that never got a deadline at all: the case the deadline exists
		 * for was the case it did not cover.
		 */
		init {
			rearmIdleFinalization()
		}
	}

	private fun extrapolatedPosition(ps: PlaybackState?): Long? {
		if (ps == null) return null
		if (ps.position < 0) return null
		if (ps.state != PlaybackState.STATE_PLAYING) return ps.position
		val drift = SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime
		return ps.position + (drift * ps.playbackSpeed).toLong()
	}

	private fun isPlaying(ps: PlaybackState?): Boolean =
		ps?.state == PlaybackState.STATE_PLAYING

	/**
	 * The Android transport constant as the source-neutral value the reducer
	 * consumes.
	 *
	 * Everything that is neither playing, paused nor stopped collapses to
	 * [TransportState.OTHER] deliberately: buffering, connecting, error and none
	 * are all "not measuring and not over", and the state machine has never
	 * distinguished them.
	 */
	private fun transportStateOf(ps: PlaybackState?): TransportState = when (ps?.state) {
		PlaybackState.STATE_PLAYING -> TransportState.PLAYING
		PlaybackState.STATE_PAUSED -> TransportState.PAUSED
		PlaybackState.STATE_STOPPED -> TransportState.STOPPED
		else -> TransportState.OTHER
	}

	private fun stateName(state: Int): String = when (state) {
		PlaybackState.STATE_NONE -> "NONE"
		PlaybackState.STATE_STOPPED -> "STOPPED"
		PlaybackState.STATE_PAUSED -> "PAUSED"
		PlaybackState.STATE_PLAYING -> "PLAYING"
		PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
		PlaybackState.STATE_REWINDING -> "REWINDING"
		PlaybackState.STATE_BUFFERING -> "BUFFERING"
		PlaybackState.STATE_ERROR -> "ERROR"
		PlaybackState.STATE_CONNECTING -> "CONNECTING"
		PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
		PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
		PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
		else -> "UNKNOWN($state)"
	}

	companion object {
		/** Handler and wall clocks can differ by a few milliseconds. */
		private const val CONTINUATION_TIMER_SLOP_MS = 250L

		/**
		 * How many destroyed session tokens are remembered.
		 *
		 * Only needs to outlive the window in which Android may still re-list a
		 * released controller, which is under a second. Generous by three orders of
		 * magnitude, and bounded so a day-long monitoring run cannot grow it.
		 */
		private const val MAX_REMEMBERED_DESTROYED_TOKENS = 256

		/**
		 * How an OS-declared ad names itself in a skip reason. Phrased as evidence
		 * rather than as a key name, because this string is shown to the user in
		 * the Not-logged tab.
		 */
		const val ADVERTISEMENT_METADATA_SIGNAL = "the player marked this track as an advertisement"

		/**
		 * `MediaMetadataCompat.METADATA_KEY_ADVERTISEMENT`, by its literal name.
		 *
		 * The framework's `android.media.MediaMetadata` does not declare this
		 * constant — it belongs to the media-compat library, which RustedWax does
		 * not depend on. The key is written into the same metadata bundle either
		 * way, so reading it by name is the whole of the integration and avoids
		 * taking a dependency for one string.
		 */
		private const val METADATA_KEY_ADVERTISEMENT = "android.media.metadata.ADVERTISEMENT"
		/** Grace covering a short STOPPED gap between same-title duration phases. */
		const val NATIVE_STOPPED_FINALIZE_GRACE_MS = 10_000L
		const val MIN_NATIVE_PRE_RESOLVE_DURATION_MS = 60_000L

		/**
		 * How far into a track the player may already be before its lead-in is
		 * worth naming. Ten seconds — below that it is ordinary startup jitter,
		 * above it something played that RustedWax was never shown.
		 */

		/**
		 * Select an identity after latch corroboration without resurrecting a
		 * value that the same pass just disproved.
		 *
		 * v0.8.8 cleared `latchedVideo` and then returned
		 * `latchedVideo ?: live`. When `live` was the value just rejected, the
		 * clear was undone in the return expression and the wrong id reached
		 * enrichment. A later active callback may still latch a different,
		 * corroborated id; only this contradictory pass fails closed.
		 */

		data class LatchDisagreement(
			val reason: String,
			val contradicts: Boolean,
			/**
			 * The disagreement was the two *titles*, not the two durations.
			 *
			 * Kept apart because the two are not the same kind of evidence. A page
			 * whose length differs from the one being played is structurally a
			 * different video. A page whose title differs may simply be YouTube
			 * writing one video's name two ways — an auto-translation, a channel
			 * mention spelled as a handle on one surface and a display name on the
			 * other, a canonical short title against a long presentation. Every
			 * such shape has cost a correct id in the field, so a title-only
			 * disagreement drops the latch here but does not get to outrank an
			 * independent exact-id route later. See [VideoIdentityCorroborator].
			 */
			val titleOnly: Boolean = false,
		)

		fun latchDisagreement(
			titleEvidence: VideoTitleMatcher.Evidence?,
			weakEvidenceAllowed: Boolean,
			pageTitle: String?,
			sessionTitle: String?,
			sessionDurationMs: Long?,
			pageDurationSeconds: Long?,
		): LatchDisagreement? {
			if (titleEvidence == VideoTitleMatcher.Evidence.CONTRADICTION) {
				return LatchDisagreement(
					"page title \"$pageTitle\" ≠ session title \"$sessionTitle\"",
					contradicts = true,
					titleOnly = true,
				)
			}
			if (durationsDisagree(sessionDurationMs, pageDurationSeconds)) {
				return LatchDisagreement(
					"page is ${pageDurationSeconds}s but the session is playing " +
						"${(sessionDurationMs ?: 0) / 1000}s",
					contradicts = true,
				)
			}
			if (titleEvidence == VideoTitleMatcher.Evidence.WEAK_SHORT_CANONICAL_CORE &&
				!weakEvidenceAllowed
			) {
				return LatchDisagreement(
					"page title \"$pageTitle\" is only weak short-title evidence without " +
						"same-generation duration corroboration; not confirming the id, " +
						"and not counting this against it",
					contradicts = false,
				)
			}
			return null
		}

		fun identityAfterCorroboration(
			latched: YouTubeProbe.Identity.Confirmed?,
			live: YouTubeProbe.Identity,
			rejectedVideoIds: Set<String>,
			rejectedThisPass: Boolean,
		): YouTubeProbe.Identity {
			if (rejectedThisPass) {
				return YouTubeProbe.Identity.Unconfirmed(
					"the latched video id was disproven for this track",
				)
			}
			latched?.let { return it }
			if (live is YouTubeProbe.Identity.Confirmed &&
				live.videoId in rejectedVideoIds
			) {
				return YouTubeProbe.Identity.Unconfirmed(
					"video id ${live.videoId} was disproven for this track",
				)
			}
			return live
		}

		/**
		 * Explicit ad UI only belongs to the current Short whose id was read in
		 * the same browser window. Package-only binding would turn a watch-page
		 * pre-roll into a veto on the real content behind it.
		 */
		fun adEvidenceMatches(
			identity: YouTubeProbe.Identity.Confirmed?,
			evidence: AdEvidence.Evidence,
		): Boolean =
			identity?.isShort == true &&
				identity.videoId == evidence.videoId &&
				(identity.urlGeneration == null || identity.urlGeneration == evidence.urlGeneration)

		/** Shared presentation-aware evidence for page title versus MediaSession. */
		fun titleEvidence(a: String, b: String): VideoTitleMatcher.Evidence =
			VideoTitleMatcher.compare(a, b)

		/** True only when both durations exist and do not materially disagree. */
		fun durationsCorroborate(sessionMs: Long?, pageSeconds: Long?): Boolean =
			sessionMs != null && sessionMs > 0 && pageSeconds != null && pageSeconds > 0 &&
				!durationsDisagree(sessionMs, pageSeconds)

		/** Policy boundary for the weak rank; strong ranks do not need this escape hatch. */
		fun titleEvidenceMayRetainObservedId(
			evidence: VideoTitleMatcher.Evidence,
			candidateVideoId: String,
			candidateGeneration: Long?,
			observedVideoId: String?,
			observedGeneration: Long?,
			sessionDurationMs: Long?,
			pageDurationSeconds: Long?,
		): Boolean = when (evidence) {
			VideoTitleMatcher.Evidence.EXACT,
			VideoTitleMatcher.Evidence.STRONG_CONTAINMENT -> true
			VideoTitleMatcher.Evidence.WEAK_SHORT_CANONICAL_CORE ->
				candidateVideoId == observedVideoId &&
					candidateGeneration != null && candidateGeneration > 0 &&
					candidateGeneration == observedGeneration &&
					durationsCorroborate(sessionDurationMs, pageDurationSeconds)
			VideoTitleMatcher.Evidence.CONTRADICTION -> false
		}

		fun durationsDisagree(sessionMs: Long?, pageSeconds: Long?): Boolean {
			if (sessionMs == null || sessionMs <= 0 || pageSeconds == null || pageSeconds <= 0) {
				return false
			}
			val pageMs = pageSeconds * 1000
			val diff = kotlin.math.abs(sessionMs - pageMs)
			val longer = maxOf(sessionMs, pageMs)
			return diff > DURATION_TOLERANCE_MS &&
				diff > longer * DURATION_TOLERANCE_FRACTION
		}

		/**
		 * Strict evidence that the same media item restarted at its beginning.
		 *
		 * Both boundary checks and the minimum jump are required. A person may
		 * seek backward anywhere in a long video; only a transition from the
		 * final 20% to the first 20%, covering at least half the duration,
		 * counts as the continuous loop signal used by the scrobble cap.
		 *
		 * The rule itself lives in [PlaybackReducer], which is where it is now
		 * applied. This stays as the name the loop regressions were written
		 * against — one implementation, two doors.
		 */
		fun positionWrapped(
			previousPositionMs: Long?,
			newPositionMs: Long?,
			durationMs: Long?,
		): Boolean = PlaybackReducer.positionWrapped(previousPositionMs, newPositionMs, durationMs)

		/**
		 * Ceiling on the rate the played-time clock will scale by.
		 *
		 * YouTube's own maximum is 2×; this leaves headroom for players that
		 * offer more while refusing to let one absurd `playbackSpeed` sample turn
		 * ten seconds of play into a full listen.
		 */
		const val MAX_PLAYBACK_SPEED = PlaybackReducer.MAX_PLAYBACK_SPEED

		/** Rounding slack between `lengthSeconds` and the session's `DURATION`. */
		const val DURATION_TOLERANCE_MS = 5_000L

		/** Also required, so long videos aren't held to the flat 5 s. */
		const val DURATION_TOLERANCE_FRACTION = 0.05

		const val LOOP_END_FRACTION = PlaybackReducer.LOOP_END_FRACTION
		const val LOOP_START_FRACTION = PlaybackReducer.LOOP_START_FRACTION
		const val LOOP_MIN_RESET_FRACTION = PlaybackReducer.LOOP_MIN_RESET_FRACTION

		/**
		 * The rate to score a play window at, given what `PlaybackState` reported.
		 *
		 * Non-positive — paused, or simply unreported — is **not** an instruction
		 * to count zero. The played clock already decides whether a window counts
		 * at all; a `speed=0.0` sample landing mid-window would otherwise erase
		 * time genuinely spent playing. A missing value means "assume normal
		 * speed", which is the pre-v0.8.1 behaviour.
		 *
		 * Implemented in [PlaybackReducer] alongside the accumulation it feeds.
		 */
		fun speedFactor(reported: Float?): Double = PlaybackReducer.speedFactor(reported)

		/** True if the app's notification listener is currently enabled. */
		fun hasNotificationAccess(context: Context): Boolean {
			val enabled = android.provider.Settings.Secure.getString(
				context.contentResolver,
				"enabled_notification_listeners",
			).orEmpty()
			val pkg = context.packageName
			return enabled.split(':').any { it.startsWith("$pkg/") }
		}
	}

}
