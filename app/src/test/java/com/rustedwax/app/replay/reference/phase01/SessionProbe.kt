package com.rustedwax.app.replay.reference.phase01

// GENERATED — do not edit. See <redacted-private-path>.
// Body below is byte-identical to the recorded pre-migration original.

import com.rustedwax.app.detect.*
import com.rustedwax.core.TrackIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


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
 * Position handling here is the same extrapolation Phase 3 needs: PlaybackState
 * reports a position sampled at `lastPositionUpdateTime`, so live position is
 * `position + (now - sampledAt) * speed`. We also accumulate *played
 * milliseconds* across play/pause, which is what the 60% rule consumes and is
 * computable from this data alone.
 *
 * "Played" means **content consumed**, not seconds elapsed: the same `speed`
 * factor scales the accumulator, because the threshold compares against
 * `duration`. Watching at 1.25× used to report 67% of a trailer that had been
 * watched to 79%, and at 2× a video watched in full read 50% and never
 * scrobbled. See [Watch.accumulate].
 */
class SessionProbe(context: Context) {

	private val appContext = context.applicationContext
	private val packageManager = appContext.packageManager
	private val sessionManager =
		appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
	private val listenerComponent =
		ComponentName(appContext, RustedWaxListenerService::class.java)
	private val handler = Handler(Looper.getMainLooper())

	private val watches = mutableMapOf<String, Watch>()
	private val foregroundShortTracker = ForegroundShortTracker()
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
	 * Fires when a session's video id becomes known — the earliest moment
	 * enrichment can start. Wired to the engine's prefetch, which dedupes, so
	 * being called on every identity re-check is fine.
	 */
	var onVideoConfirmed: ((videoId: String) -> Unit)? = null

	/** Clears resolver candidates when a native package is disabled or torn down. */
	var onPackageTornDown: ((packageName: String) -> Unit)? = null

	/** Minimal immutable proof returned by the engine's off-main-thread native lookup. */
	data class NativeResolvedIdentity(
		val videoId: String,
		val route: NativePreResolvedRoute,
	)

	/**
	 * Resolve a stable exact-ID-less native track while it is playing. The probe
	 * spends this only on controller continuity; payload authority remains in the
	 * engine and is re-fetched at finalization.
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

	private val activeSessionsListener =
		MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
			syncControllers(controllers ?: emptyList())
		}

	fun start() {
		if (started) return
		browserEvidenceEnabledForRun = UrlWatcherService.isEnabled(appContext)
		// A notification hint often arrives *after* we've already judged the
		// track (measured: ~300 ms later), so re-run identity when one lands.
		NotificationHints.onHint = { pkg ->
			handler.post {
				watches.values
					.filter { it.packageName == pkg }
					.forEach { it.reidentify("notification") }
				publish()
			}
		}
		// The address bar lands late for the same reason, and until v0.5.3
		// nothing re-ran identity when it did — so a video id that arrived a
		// moment after track start was only ever picked up by the UI's tick,
		// making `url` depend on the app being open. Same wiring as hints.
		UrlEvidence.onEvidence = { pkg ->
			handler.post {
				watches.values
					.filter { it.packageName == pkg }
					.forEach { it.reidentify("address bar") }
				publish()
			}
		}
		// The accessibility callback sees YouTube's visible ad UI in the same
		// window snapshot that supplied the `/shorts/` id. Bind that literal
		// evidence to the matching active track; never infer from its channel.
		AdEvidence.onEvidence = { evidence ->
			handler.post {
				watches.values
					.filter { !it.isNative && it.packageName == evidence.packageName }
					.forEach { it.noteAdEvidence(evidence) }
				publish()
			}
		}
		// Ordinary watch labels carry no video id: the address bar names the
		// organic content behind an in-stream ad. Resolve the observation only
		// against one active Watch from the same browser package.
		MediaSessionAdEvidence.onObservation = { observation ->
			handler.post {
				val candidates = watches.values.filter {
					!it.isNative && it.packageName == observation.packageName
				}
				when {
					observation.signal == null ->
						MediaSessionAdEvidence.labelAbsent(observation.packageName)
					candidates.size != 1 -> {
						MediaSessionAdEvidence.conflict(observation.packageName)
						EventLog.append(
							"ad",
							"${observation.packageName} → ordinary watch ad label refused: " +
								"${candidates.size} active MediaSession tracks",
						)
					}
					else -> candidates.single().noteMediaSessionAdEvidence(observation)
				}
				publish()
			}
		}
		MediaSessionAccessibilityEvidence.onScan = { scan ->
			handler.post {
				val candidates = watches.values.filter {
					!it.isNative && it.packageName == scan.packageName
				}
				if (candidates.size == 1) {
					candidates.single().noteAccessibilityScan(scan)
				} else {
					EventLog.append(
						"evidence",
						"${scan.packageName} → successful YouTube scan not bound: " +
							"${candidates.size} active MediaSession tracks",
					)
				}
				publish()
			}
		}
		NativeShortsObserver.onEvent = { event ->
			handler.post { handleNativeShortEvent(event) }
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
			NativeShortsObserver.onEvent = null
			// Notification Access not granted (or revoked while running).
			_error.value = "Notification Access not granted — enable it to read media sessions."
			EventLog.append("probe", "start failed: ${e.message}")
		}
	}

	/**
	 * Tear the probe down.
	 *
	 * @param finalizeTracks whether tracks still in flight get one last chance
	 * to score. True when the *system* ends things (session gone, listener
	 * disconnected) — the track really did end, and dropping it would lose a
	 * legitimate scrobble. **False when the user presses Stop**: a Stop button
	 * that writes to an immutable chain on its way out is a bad Stop button, and
	 * without this flag `dispose()` would do exactly that for any track already
	 * past the threshold.
	 */
	fun stop(finalizeTracks: Boolean = true) {
		if (!started) return
		NotificationHints.onHint = null
		UrlEvidence.onEvidence = null
		AdEvidence.onEvidence = null
		MediaSessionAdEvidence.onObservation = null
		MediaSessionAccessibilityEvidence.onScan = null
		NativeShortsObserver.onEvent = null
		foregroundShortTracker.discard(
			if (finalizeTracks) "listener/probe lifecycle boundary" else "user Stop",
		)
		foregroundShortSnapshot = null
		NativeShortsObserver.setRefreshNeeded(false)
		runCatching { sessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
		// Probe shutdown cannot wait for a replacement session: either score the
		// last aggregate now (system teardown) or discard it (user Stop).
		watches.values.forEach { watch ->
			// Listener lifecycle rebuilds preserve the browser contract, but native
			// state is a hard reset boundary and is discarded conservatively.
			watch.dispose(
				finalize = finalizeTracks && !watch.isNative,
				allowContinuation = false,
			)
			if (watch.isNative) clearPackageState(watch.packageName)
		}
		watches.clear()
		// A vanished native controller may still have a pending continuation even
		// though no Watch remains in the map. Reconnect/Stop clears it too.
		YouTubeProbe.YOUTUBE_APP_PACKAGES.forEach(::clearPackageState)
		// Nothing observed before a Stop may survive it — including play time
		// waiting to be handed to a session that no longer exists.
		if (!finalizeTracks) {
			TrackProgressCarry.clear()
			AdEvidence.clearAll()
		}
		MediaSessionAdEvidence.clearAll()
		MediaSessionAccessibilityEvidence.clearAll()
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
		val foreground = foregroundShortSnapshot
		if (!NativeSourceSwitches.acceptsPackage(YouTubeProbe.YOUTUBE_PACKAGE) ||
			(foreground != null && !NativeSourceSwitches.isSnapshotCurrent(
				YouTubeProbe.YOUTUBE_PACKAGE,
				foreground.sourceEpoch,
			))
		) {
			foregroundShortTracker.discard("Native YouTube opt-out/source epoch changed")
			foregroundShortSnapshot = null
			NativeShortsObserver.setRefreshNeeded(false)
		}
		(YouTubeProbe.YOUTUBE_APP_PACKAGES + YouTubeProbe.TARGET_PACKAGES)
			.filterNot(NativeSourceSwitches::acceptsPackage)
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
				watch.isNative && !NativeSourceSwitches.isSnapshotCurrent(
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
				!NativeSourceSwitches.acceptsPackage(watch.packageName) -> {
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

			if (!NativeSourceSwitches.acceptsPackage(controller.packageName)) {
				noteIgnored(
					controller.packageName,
					if (YouTubeProbe.isNativePackage(controller.packageName)) {
						"native source toggle is off"
					} else {
						"not a supported source package"
					},
				)
				continue
			}

			val watch = Watch(controller, labelFor(controller.packageName))
			watches[key] = watch
			if (watch.packageName == YouTubeProbe.YOUTUBE_PACKAGE &&
				foregroundShortTracker.hasCompleteProof
			) {
				watch.suppressForForegroundShort(foregroundShortSnapshot)
			}
			// The arrival line is deliberately not written here. At this moment
			// nothing has proven what site this is, and "+ com.android.chrome" on
			// a timestamp is the same browsing record §4.1 removes elsewhere. The
			// session announces itself once it is proven YouTube; until then the
			// aggregate is the only trace.
			watch.logMetadata(controller.metadata, "initial")
			watch.logPlaybackState(controller.playbackState, "initial")
		}

		// Two MediaSessions from one browser package are deliberately ambiguous:
		// accessibility exposes no session token with which to choose between them.
		watches.values.filterNot { it.isNative }
			.groupBy { it.packageName }.forEach { (packageName, packageWatches) ->
			if (packageWatches.size > 1) MediaSessionAdEvidence.conflict(packageName)
		}

		publish()
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
	private val soleBrowserSession: Boolean
		get() = watches.values.count { it.origin == YouTubeProbe.Origin.BROWSER } == 1

	private fun clearPackageState(packageName: String) {
		TrackProgressCarry.clearPackage(packageName)
		if (packageName == YouTubeProbe.YOUTUBE_PACKAGE) {
			NativePlaylistObserver.clear("$packageName state reset")
		}
		onPackageTornDown?.invoke(packageName)
	}

	private fun publish() {
		val mediaSessions = watches.values
			.filterNot(Watch::suppressedByForegroundShort)
			.map { it.snapshot() }
		_sessions.value = listOfNotNull(foregroundShortSnapshot) + mediaSessions
	}

	private fun handleNativeShortEvent(event: NativeShortsObserver.Event) {
		if (!started) return
		if (!NativeSourceSwitches.acceptsPackage(YouTubeProbe.YOUTUBE_PACKAGE)) {
			foregroundShortTracker.discard("Native YouTube is off")
			foregroundShortSnapshot = null
			NativeShortsObserver.setRefreshNeeded(false)
			publish()
			return
		}
		val update = when (event) {
			NativeShortsObserver.Event.Connected -> {
				nativeShortDiagnosticThrottle.reset()
				publish()
				return
			}
			is NativeShortsObserver.Event.Disconnected ->
				foregroundShortTracker.discard(event.reason)
			is NativeShortsObserver.Event.Missing ->
				foregroundShortTracker.proofMissing(
					event.observedAtMillis,
					event.reason,
					progressSurfaceLost = event.progressSurfaceLost,
					inferredPlaying = event.inferredPlaying,
					playbackRate = event.playbackRate,
				)
			is NativeShortsObserver.Event.Parsed -> {
				val epoch = NativeSourceSwitches.epochFor(YouTubeProbe.YOUTUBE_PACKAGE)
					?: return
				when (val parsed = event.result) {
					is NativeShortParser.Result.Organic -> foregroundShortTracker.observe(
						ForegroundShortTracker.OrganicObservation(
							title = parsed.title,
							ownerHandle = parsed.ownerHandle,
							currentSeconds = parsed.currentSeconds,
							totalSeconds = parsed.totalSeconds,
							observedAtMillis = event.observedAtMillis,
							sourceEpoch = epoch,
						),
					)
					is NativeShortParser.Result.Ad -> foregroundShortTracker.observe(
						ForegroundShortTracker.AdObservation(
							signal = parsed.signal,
							title = parsed.title,
							currentSeconds = parsed.currentSeconds,
							totalSeconds = parsed.totalSeconds,
							observedAtMillis = event.observedAtMillis,
							sourceEpoch = epoch,
						),
					)
					is NativeShortParser.Result.OrganicUnmeasured ->
						foregroundShortTracker.observe(
							ForegroundShortTracker.UnmeasuredObservation(
								title = parsed.title,
								ownerHandle = parsed.ownerHandle,
								observedAtMillis = event.observedAtMillis,
								sourceEpoch = epoch,
								playing = event.inferredPlaying,
							),
						)
					is NativeShortParser.Result.OrganicUnnamed ->
						foregroundShortTracker.observe(
							ForegroundShortTracker.UnnamedObservation(
								currentSeconds = parsed.currentSeconds,
								totalSeconds = parsed.totalSeconds,
								observedAtMillis = event.observedAtMillis,
								sourceEpoch = epoch,
							),
						)
					is NativeShortParser.Result.Invalid -> foregroundShortTracker.proofMissing(
						event.observedAtMillis,
						parsed.reason,
					)
				}
			}
		}
		// A Short opened and sent straight to picture-in-picture never gives the
		// accessibility tree a stably readable seekbar, so the foreground route
		// acquires nothing and there is no active Short to credit. Its
		// MediaSession still carries title and duration and still publishes
		// STATE_NONE, so the same evidence is fed to the media-session watch
		// instead. Only reached when nothing is latched, so a Short being tracked
		// properly can never be credited twice.
		if (!foregroundShortTracker.hasActive) {
			val pipEvent = event as? NativeShortsObserver.Event.Missing
			if (pipEvent != null && pipEvent.progressSurfaceLost) {
				watches.values
					.filter { it.packageName == YouTubeProbe.YOUTUBE_PACKAGE }
					.forEach { it.creditPipInference(pipEvent.observedAtMillis, pipEvent.inferredPlaying) }
			}
		}
		foregroundShortSnapshot = update.active
		NativeShortsObserver.setRefreshNeeded(foregroundShortTracker.hasActive)
		watches.values
			.filter { it.packageName == YouTubeProbe.YOUTUBE_PACKAGE }
			.forEach { watch ->
				if (foregroundShortTracker.hasActive) {
					watch.suppressForForegroundShort(foregroundShortSnapshot)
				} else {
					watch.releaseForegroundShortSuppression()
				}
			}
		val diagnosticAt = when (event) {
			NativeShortsObserver.Event.Connected -> System.currentTimeMillis()
			is NativeShortsObserver.Event.Disconnected -> System.currentTimeMillis()
			is NativeShortsObserver.Event.Missing -> event.observedAtMillis
			is NativeShortsObserver.Event.Parsed -> event.observedAtMillis
		}
		update.diagnostic?.takeIf {
			nativeShortDiagnosticThrottle.shouldEmit(
				NativeShortDiagnosticKey.of(it), diagnosticAt,
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
							"from wall-clock while no progress surface existed)"
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
	private inner class Watch(
		private val controller: MediaController,
		val appLabel: String,
	) {
		val packageName: String = controller.packageName
		val origin: YouTubeProbe.Origin = YouTubeProbe.originForPackage(packageName)
		val isNative: Boolean = YouTubeProbe.isNativePackage(packageName)
		// Capture at Watch construction. Reading the current epoch while finalizing
		// would let a callback racing an opt-out stamp itself with the new epoch.
		val sourceEpoch: Long? = NativeSourceSwitches.epochFor(packageName)

		/** True while the structurally proven foreground Shorts route owns this player. */
		var suppressedByForegroundShort: Boolean = false
			private set

		/**
		 * The browser is publishing its tab's own title instead of a track's.
		 *
		 * Nothing is measured in this state. Elapsed time between one video's
		 * metadata being torn down and the next one's arriving cannot be credited
		 * to either of them — the outgoing track has already been finalized with
		 * what it earned, and the incoming one has not started. See
		 * [BrowserTabMetadata].
		 *
		 * Initialised from the metadata this Watch was *built* on, not only
		 * entered through [onMetadataChanged]. Chromium recreates its
		 * MediaSession constantly, so a replacement can be constructed while the
		 * tab bundle is already installed and would otherwise start measuring
		 * against a track nobody has named. Measured 2026-08-11: the page was
		 * muted, Brave published `TITLE = "YouTube"` and then said nothing for
		 * seven minutes while a real video played, and all seven minutes landed
		 * on it.
		 */
		private var describingTabOnly: Boolean =
			BrowserTabMetadata.isTabTitle(controller.packageName, rawTitleOf(controller.metadata))

		/**
		 * Always true since Phase 4 — non-browser sessions are no longer
		 * watched at all. Kept because the payload and the UI still read it,
		 * and because a future per-app allowlist would put it back to work.
		 */
		val isTarget: Boolean = NativeSourceSwitches.acceptsPackage(packageName)

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

		/**
		 * The Confirmed identity captured while this track was actually
		 * playing, kept for the track's lifetime.
		 *
		 * PHASE0's "resolve identity at finalize" rule is right for
		 * notification hints (they arrive late) and exactly wrong for
		 * address-bar evidence, which is right at track *start* and stale at
		 * track *end*. Resolving from live evidence at finalize produced two
		 * on-chain failures on 2026-07-24: a track that lost its video id
		 * because the user had already scrolled to the next short (payload got
		 * no url, no category, wrong kind), and two different songs broadcast
		 * with the *same* url because one finalized while the bar showed the
		 * other. So: latch on first confirmation, spend at finalize.
		 */
		private var latchedVideo: YouTubeProbe.Identity.Confirmed? = null

		/**
		 * Last identity selected while this Watch was still active.
		 *
		 * A continuation expiry may run a minute after this Watch was removed
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

		/**
		 * Milliseconds of *content* consumed in the current track — elapsed time
		 * in STATE_PLAYING, scaled by the playback rate. See [accumulate].
		 */
		private var playedMs: Long = 0

		/** Highest rate scored for this track, for the finalize line only. */
		private var fastestSpeedSeen: Double = 1.0

		/**
		 * Where the player already was when this track was first seen.
		 *
		 * Measured 2026-08-06: `_zR6ROjoOX0` (Iggy Azalea, "Work") published no
		 * MediaSession at all for the eleven minutes before RustedWax saw it,
		 * then appeared 94 seconds into a 227-second video and was destroyed
		 * seven seconds later. The finalize line read "played 12s of 227s" and
		 * looked like a measurement fault; it was an accurate account of the only
		 * playback that was ever published. Recorded so the line can say so.
		 */
		private var firstSeenPositionMs: Long? = null
		/** End-to-start playback reset observed during this continuous viewing. */
		private var loopDetected: Boolean = false
		private var playingSince: Long = if (isPlaying(state)) SystemClock.elapsedRealtime() else 0
		private var trackIdentity: TrackIdentity = trackIdentityOf(metadata, packageName)
		private var trackInstanceToken: Long = MediaSessionAdEvidence.nextTrackToken()
		private var trackInstanceEstablishedAtMillis: Long = System.currentTimeMillis()
		private var trackStartedAtEpochSec: Long = System.currentTimeMillis() / 1000

		/** One finalize per track, however many callbacks announce the end. */
		private var finalized: Boolean = false
		/** Tokenized grace for exact-ID-less native STOPPED → metadata replacement. */
		private var nativeStoppedFinalizeToken: Long = 0
		/** Generation/signature for one bounded in-flight native carry lookup. */
		private var nativeResolutionGeneration: Long = 0
		private var nativeResolutionSignature: String? = null

		/**
		 * Token for progress waiting to see whether Chrome creates a replacement
		 * MediaSession. While present, disappearance is not a track ending.
		 */
		private var continuationToken: Long? = null
		private var continuationTrackIdentity: TrackIdentity? = null

		/** Resolver inputs accumulated while this track was the active Watch. */
		private var resolverContext: ResolverContext = ResolverContext()

		private val callback = object : MediaController.Callback() {
			override fun onMetadataChanged(md: MediaMetadata?) {
				val newIdentity = trackIdentityOf(md, packageName)
				if (suppressedByForegroundShort) {
					metadata = md
					trackIdentity = newIdentity
					logMetadata(md, "changed while foreground Short proof owned playback")
					publish()
					return
				}
				// The browser is describing its *tab* now, not a track — the
				// document title, with the origin where the channel was. See
				// [BrowserTabMetadata] for what that cost.
				//
				// It ends the current track, because the page has stopped saying
				// what is playing and the seconds after that belong to nobody we
				// can name. What it must never do is become a track of its own:
				// that is how "2Pac - Street Fame - YouTube" accumulated 22,425
				// seconds overnight and then finalized against the next video's
				// id. Nothing accumulates until the browser names something again.
				if (BrowserTabMetadata.isTabTitle(packageName, rawTitleOf(md))) {
					enterTabTitleOnly(md)
					return
				}
				if (describingTabOnly) {
					if (!newIdentity.isUsable) {
						metadata = md
						logMetadata(md, "changed while the browser named only its tab")
						publish()
						return
					}
					// The next track, from zero. Explicitly rather than through the
					// freshly-created-session branch below, because the address bar
					// may have named two videos during the gap and this track must
					// not inherit a latch from the first of them.
					describingTabOnly = false
					resetForNewTrack()
					trackIdentity = newIdentity
					trackInstanceToken = MediaSessionAdEvidence.nextTrackToken()
					trackInstanceEstablishedAtMillis = System.currentTimeMillis()
					metadata = md
					restoreCarriedProgress()
					logMetadata(md, "the browser named a track again")
					publish()
					return
				}
				// YouTube re-creates its MediaSession on every tab switch, and the
				// first metadata it publishes is empty — no title, no duration —
				// with the real values arriving a fraction of a second later.
				// Measured 2026-08-07: a viewer moved between the Home and Shorts
				// tabs while a 155-second trailer played, and each return produced
				//
				//   [track] track change after 0s played
				//   [finalize] <untitled> — played 0s of 0s
				//   [metadata] TITLE = "Algo terrible está a punto de suceder…"
				//
				// so the trailer was finalized against a placeholder, over and
				// over, and finished the session having accumulated 18 of the
				// 155 seconds actually watched. Nothing scrobbled.
				//
				// An empty announcement is the session clearing its throat, not a
				// different track. Hold the current one and wait for the real
				// metadata; a genuinely ended track still ends by STOPPED, by
				// session destruction, or by the replacement that follows.
				if (!newIdentity.isUsable && newIdentity.durationMs == null &&
					trackIdentity.isUsable
				) {
					EventLog.append(
						"native-identity",
						"$packageName published empty metadata while " +
							"\"${titleOf(metadata)}\" was playing; waiting for the real " +
							"values rather than ending it on a placeholder",
					)
					publish()
					return
				}
				val oldMediaSessionHasExactId = trackIdentityOf(metadata, packageName)
					.hasExactSourceItemId
				val presentationIdentity = if (!oldMediaSessionHasExactId &&
					resolverContext.preResolvedNativeVideoId != null
				) {
					trackIdentity.copy(sourceItemId = null)
				} else {
					trackIdentity
				}
				val discardNativeDurationFragment = isNative &&
					presentationIdentity.isExactIdlessMaterialDurationReplacement(newIdentity)
				if (discardNativeDurationFragment) {
					cancelNativeStoppedFinalization()
					cancelContinuation()
					// A downward replacement that lands on a fragment which has
					// already earned a listen is not an ad fragment — it is the
					// real track, with an interstitial's length published over it
					// at the very end. Measured 2026-08-06: "Nicki Minaj - Barbie
					// Dreams" (301s) and "Red Ruby Da Sleeze" (207s) both had a
					// ~13s duration swapped in near the end, and discarding threw
					// the whole listen away — the log then read
					// "played 18s of 13s" and every route refused, because they
					// all require the duration to agree.
					//
					// Finalizing instead freezes the duration that was in force
					// while it was playing, which is the only one it was ever
					// measured against. The discard still happens for everything
					// that had not earned a listen, which is the ad case the
					// branch was written for.
					val priorDuration = maxOf(
						trackIdentity.durationMs ?: 0,
						longestDurationMs ?: 0,
					).takeIf { it > 0 }
					val replacementIsDownward = priorDuration != null &&
						(newIdentity.durationMs ?: 0) < priorDuration
					if (replacementIsDownward) {
						// Same title, shorter number, still playing: this is
						// YouTube churning the length, not a different item. Hold
						// the longest length and keep accumulating, so the watch
						// stays one listen instead of becoming a pile of scraps.
						longestDurationMs = priorDuration
						EventLog.append(
							"native-identity",
							"$packageName reported a shorter length " +
								"(${priorDuration / 1000}s → " +
								"${(newIdentity.durationMs ?: 0) / 1000}s) for an unchanged " +
								"title after ${playedMsNow() / 1000}s played; keeping the " +
								"longer length and continuing the same listen",
						)
						metadata = md
						logMetadata(md, "shorter length reported for an unchanged title")
						publish()
						return
					}
					EventLog.append(
						"native-identity",
						"$packageName exact-ID-less same-metadata duration changed from " +
							"${trackIdentity.durationMs?.div(1000)}s to " +
							"${newIdentity.durationMs?.div(1000)}s; discarded the prior " +
							"fragment with zero carry and made no ad inference",
					)
					resetForNewTrack()
					trackIdentity = newIdentity
					trackInstanceToken = MediaSessionAdEvidence.nextTrackToken()
					trackInstanceEstablishedAtMillis = System.currentTimeMillis()
					metadata = md
					logMetadata(md, "changed after exact-ID-less duration replacement")
					publish()
					return
				}
				// A session that was created holding nothing — no title, no
				// duration — has not been playing a track that can now "end". It
				// was YouTube preparing a controller, and this is the real
				// metadata arriving. Announcing a track change here finalized a
				// phantom `<untitled> — played 0s of 0s` on every tab switch,
				// which is noise at best and, when it lands between a teardown
				// and its carry, throws the real track's progress away.
				val outgoingWasPlaceholder = !trackIdentity.isUsable &&
					trackIdentity.durationMs == null &&
					playedMsNow() == 0L
				val trackChanged = !trackIdentity.sameTrackAs(newIdentity) &&
					!outgoingWasPlaceholder
				if (outgoingWasPlaceholder && newIdentity.isUsable) {
					trackIdentity = newIdentity
					trackInstanceToken = MediaSessionAdEvidence.nextTrackToken()
					trackInstanceEstablishedAtMillis = System.currentTimeMillis()
					metadata = md
					restoreCarriedProgress()
					logMetadata(md, "first real metadata for a freshly created session")
					publish()
					return
				}
				if (trackChanged) {
					cancelNativeStoppedFinalization()
					EventLog.append(
						"track",
						"$packageName track change after ${playedMsNow() / 1000}s played",
					)
					// A real metadata change outranks the continuation grace
					// period: the old track has now demonstrably ended.
					cancelContinuation()
					finalizeCurrent("track change")
					resetForNewTrack()
					trackIdentity = newIdentity
					trackInstanceToken = MediaSessionAdEvidence.nextTrackToken()
					trackInstanceEstablishedAtMillis = System.currentTimeMillis()
					if (!isNative) {
						MediaSessionAccessibilityEvidence.activate(
							mediaSessionAdInstance(), trackInstanceEstablishedAtMillis,
						)
					}
				} else {
					trackIdentity = trackIdentity.refinedWith(newIdentity)
				}
				metadata = md
				if (trackChanged) {
					// The real title often arrives here rather than at
					// construction — Chromium publishes a placeholder first — so
					// this is where a resumed track usually gets its time back.
					// Assign metadata first so cross-session loop detection
					// compares against the replacement's real duration.
					restoreCarriedProgress()
				}
				logMetadata(md, "changed")
				publish()
			}

			override fun onPlaybackStateChanged(ps: PlaybackState?) {
				if (suppressedByForegroundShort) {
					state = ps
					playingSince = 0
					logPlaybackState(ps, "changed while foreground Short proof owned playback")
					publish()
					return
				}
				val previousPosition = extrapolatedPosition(state)
				accumulate()
				val wasPlaying = isPlaying(state)
				state = ps
				notePositionWrap(
					previousPositionMs = previousPosition,
					newPositionMs = extrapolatedPosition(ps),
					acrossSessionRestart = false,
				)
				if (isPlaying(ps) && playingSince == 0L) {
					cancelNativeStoppedFinalization()
					playingSince = SystemClock.elapsedRealtime()
				}
				if (isPlaying(ps)) requestNativeCarryAuthority()
				// STOPPED means the track is over; PAUSED does not — a paused
				// track is often resumed, and finalizing it would scrobble a
				// half-listen and then dedup-block the real one.
				if (wasPlaying && ps?.state == PlaybackState.STATE_STOPPED) {
					cancelContinuation()
					// Resolver authority may authorize a controller handoff, but the
					// MediaSession is still exact-ID-less and retains replacement grace.
					val mediaSessionHasExactId = trackIdentityOf(metadata, packageName)
						.hasExactSourceItemId
					if (isNative && !mediaSessionHasExactId) {
						scheduleNativeStoppedFinalization()
					} else {
						finalizeCurrent("stopped")
					}
				}
				logPlaybackState(ps, "changed")
				publish()
			}

			override fun onSessionDestroyed() {
				logDeparture("× $packageName destroyed")
				if (suppressedByForegroundShort) {
					finalized = true
					playingSince = 0
					EventLog.append(
						"native-shorts",
						"suppressed native MediaSession destroyed; no carry or finalization",
					)
					publish()
					return
				}
				// Chrome tears sessions down around ad breaks and playlist
				// transitions. Disappearance starts a continuation window; it is
				// not itself a track ending.
				deferForContinuation("session destroyed")
				publish()
			}
		}

		init {
			if (!isNative) {
				MediaSessionAccessibilityEvidence.activate(
					mediaSessionAdInstance(), trackInstanceEstablishedAtMillis,
				)
			}
			controller.registerCallback(callback, handler)
			// A session that appears already knowing its track is usually a
			// replacement for one Chrome just tore down.
			restoreCarriedProgress()
		}

		fun dispose(finalize: Boolean = true, allowContinuation: Boolean = true) {
			cancelNativeStoppedFinalization()
			invalidateNativeResolution()
			when {
				suppressedByForegroundShort -> {
					finalized = true
					playingSince = 0
					cancelContinuation()
				}
				!finalize -> cancelContinuation()
				allowContinuation -> deferForContinuation("session ended")
				else -> {
					// System teardown cannot observe a replacement. Score the
					// aggregate now; user Stop passes finalize=false above.
					cancelContinuation()
					finalizeCurrent("probe ended")
				}
			}
			if (!isNative) {
				MediaSessionAdEvidence.clearInstance(mediaSessionAdInstance())
				MediaSessionAccessibilityEvidence.deactivate(mediaSessionAdInstance())
			}
			runCatching { controller.unregisterCallback(callback) }
		}

		/**
		 * Hold this track for a replacement MediaSession without scoring the
		 * fragment on its own.
		 *
		 * If no matching replacement claims the progress during [TrackProgressCarry.TTL_MS],
		 * the delayed callback finalizes the aggregate exactly once. A token keeps
		 * an older callback from consuming a newer continuation with the same key.
		 */
		private fun deferForContinuation(reason: String) {
			if (finalized || continuationToken != null || metadata == null) return
			cancelNativeStoppedFinalization()
			if (suppressedByForegroundShort) {
				finalized = true
				playingSince = 0
				EventLog.append("native-shorts", "$packageName [$reason] suppressed MediaSession dropped")
				return
			}
			accumulate()
			if (isNative && !trackIdentity.hasExactSourceItemId) {
				EventLog.append(
					"native-carry",
					"$packageName [$reason] exact-id-less MediaSession continuation refused; " +
						"resolver/site metadata cannot carry progress across controllers",
				)
				finalizeCurrent("$reason; exact-id-less native continuation refused")
				return
			}
			val now = System.currentTimeMillis()
			val identityKey = trackIdentity
			val frozenCoverage = currentAccessibilityCoverage(now)
			// This Watch leaves the active map immediately after disappearance.
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
			)
			val token = TrackProgressCarry.remember(
				packageName = packageName,
				trackIdentity = identityKey,
				progress = progress,
			) ?: return
			continuationIdentity = frozenIdentity
			continuationAccessibilityCoverage = frozenCoverage
			continuationToken = token
			continuationTrackIdentity = identityKey
			// Whether position will be allowed to speak for this one, which is the
			// same predicate the claim will apply — asked here against the carry's
			// own stopping point so the log states the wait it will actually keep.
			val resumable = TrackProgressCarry.holdsResumeWindow(identityKey, progress)
			// §4.1: a session that was never named must not name itself on the way
			// out either. Measured 2026-08-10 — this was the last line an
			// unrelated Chrome video still produced after the other routes closed.
			logDeparture(
				"$packageName [$reason] waiting " +
					if (resumable) {
						"up to ${TrackProgressCarry.RESUMED_TTL_MS / 60_000}m for this listen to " +
							"resume near ${(progress.lastPositionMs ?: 0L) / 1000}s before finalizing"
					} else {
						"${TrackProgressCarry.TTL_MS / 1000}s for a replacement session " +
							"before finalizing"
					},
			)
			// Polled rather than fired once: the wait can now end three ways — a
			// claim, a newer continuation displacing this one, or the deadline —
			// and only asking can tell "still waiting" from "someone else settled
			// it". Every tick either collects this fragment or confirms it is
			// still owed a continuation.
			val tick = object : Runnable {
				override fun run() {
					val expired = TrackProgressCarry.expire(packageName, identityKey, token)
					if (expired != null) {
						if (continuationToken == token && !finalized) {
							continuationToken = null
							continuationTrackIdentity = null
							finalizeCurrent("session continuation expired")
							if (isNative && watches.values.none { it.packageName == packageName }) {
								clearPackageState(packageName)
							}
						}
						return
					}
					if (continuationToken == token && !finalized &&
						TrackProgressCarry.isPending(packageName, identityKey, token)
					) {
						handler.postDelayed(this, TrackProgressCarry.TTL_MS)
					}
				}
			}
			handler.postDelayed(tick, TrackProgressCarry.TTL_MS + CONTINUATION_TIMER_SLOP_MS)
		}

		private fun cancelContinuation() {
			val token = continuationToken ?: return
			val identityKey = continuationTrackIdentity ?: trackIdentity
			TrackProgressCarry.cancel(packageName, identityKey, token)
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
			if (!isNative) MediaSessionAccessibilityEvidence.deactivate(mediaSessionAdInstance())
			trackInstanceToken = carried.trackInstanceToken ?: trackInstanceToken
			trackInstanceEstablishedAtMillis = System.currentTimeMillis()
			if (!isNative) {
				MediaSessionAccessibilityEvidence.activate(
					mediaSessionAdInstance(), trackInstanceEstablishedAtMillis,
				)
			}
			// Added to, not assigned over. The claim can land seconds after the
			// replacement started measuring — the native resolver and the browser's
			// address bar both take a moment to name the video — and those seconds
			// are the same listen, so overwriting them threw away real play time.
			//
			// Banked time only: the running clock is deliberately left alone.
			// Folding it in here with `accumulate()` also *stops* it, and nothing
			// on this path starts it again — measured 2026-08-09, a Chrome session
			// that was already PLAYING when it claimed reached `pos=220412ms` of a
			// 220421 ms video having measured 58s, because the claim silenced the
			// clock at the moment it handed the time back and no further state
			// change ever arrived to restart it.
			playedMs += carried.playedMs
			trackStartedAtEpochSec = carried.trackStartedAtEpochSec
			fastestSpeedSeen = carried.fastestSpeedSeen
			loopDetected = carried.loopDetected
			carried.identity?.let { identity ->
				lastStableIdentity = when (identity) {
					is YouTubeProbe.Identity.Confirmed -> identity.copy(
						source = "${identity.source} (carried across session restart)",
					).also {
						latchedVideo = it
						onVideoConfirmed?.invoke(it.videoId)
					}
					else -> identity
				}
			}
			explicitAdSignal = carried.explicitAdSignal
			carried.explicitAdSignal?.takeUnless { isNative }?.let { signal ->
				MediaSessionAdEvidence.restoreAccepted(
					mediaSessionAdInstance(),
					signal,
					carried.atMillis,
				)
			}
			carried.accessibilityCoverage?.takeUnless { isNative }?.let { coverage ->
				if (MediaSessionAccessibilityEvidence.restore(
						mediaSessionAdInstance(), coverage, trackInstanceEstablishedAtMillis,
					)
				) {
					accessibilityCoverage = coverage.copy(instance = mediaSessionAdInstance())
				}
			}
			notePositionWrap(
				previousPositionMs = carried.lastPositionMs,
				newPositionMs = extrapolatedPosition(state),
				acrossSessionRestart = true,
			)
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
		 * Record a strict end-to-start position reset.
		 *
		 * MediaSession exposes no "automatic loop" bit. The position boundary
		 * is the literal evidence available, and [positionWrapped] deliberately
		 * requires both ends of the item so ordinary backward seeking does not
		 * qualify.
		 */
		private fun notePositionWrap(
			previousPositionMs: Long?,
			newPositionMs: Long?,
			acrossSessionRestart: Boolean,
		) {
			if (loopDetected || !positionWrapped(previousPositionMs, newPositionMs, durationOf(metadata))) {
				return
			}
			loopDetected = true
			EventLog.append(
				"playback",
				"$packageName playback position wrapped from end to start" +
					if (acrossSessionRestart) {
						" across a session restart — loop detected"
					} else {
						" — loop detected"
					},
			)
		}

		/**
		 * Hand the finished track to the engine. Guarded so the several paths
		 * that can end a track (metadata change, stop, session destroyed,
		 * disposal — several of which fire together) only report once.
		 */
		fun finalizeCurrent(reason: String) {
			if (finalized) return
			if (metadata == null) return
			cancelNativeStoppedFinalization()
			if (suppressedByForegroundShort) {
				finalized = true
				playingSince = 0
				cancelContinuation()
				EventLog.append(
					"native-shorts",
					"$packageName [$reason] stale MediaSession finalization suppressed",
				)
				return
			}
			finalized = true
			accumulate()
			val snapshot = snapshot(finalizedTrack = true)
			// §4.1. The finalize still happens — the engine needs the snapshot to
			// decide, and it will refuse this one for not being YouTube — but the
			// *line* names the track, and for an unproven session that track is a
			// page the user watched somewhere else. Measured 2026-08-10, after the
			// metadata, identity, playback and arrival routes were closed, this one
			// still wrote `w3schools.com/html/mov_bbb.mp4` into the exportable log.
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
						unobservedLeadInNote(snapshot.playedMs),
				)
			}
			onTrackFinalized?.invoke(snapshot)
			if (isNative) {
				EventLog.append(
					"native",
					"$packageName finalized with exact MediaSession metadata/state captured; " +
						"native ad outcome remains unproven unless the dump contains a literal " +
						"structured signal",
				)
			}
			if (!isNative) {
				MediaSessionAdEvidence.clearInstance(mediaSessionAdInstance())
				MediaSessionAccessibilityEvidence.deactivate(mediaSessionAdInstance())
			}
		}

		/**
		 * End the track the page has stopped describing, and start nothing.
		 *
		 * The finalize happens first and reads the *outgoing* metadata, so the
		 * entry keeps the title, channel and length the page published while it
		 * was playing. Only after that does the session become nameless.
		 */
		private fun enterTabTitleOnly(md: MediaMetadata?) {
			if (describingTabOnly) {
				metadata = md
				logMetadata(md, "the browser is still naming only its tab")
				publish()
				return
			}
			cancelNativeStoppedFinalization()
			cancelContinuation()
			if (trackIdentity.isUsable) {
				EventLog.append(
					"track",
					"$packageName replaced the track with its tab's own title after " +
						"${playedMsNow() / 1000}s played",
				)
				finalizeCurrent("the browser named its tab instead of a track")
			}
			resetForNewTrack()
			describingTabOnly = true
			// [resetForNewTrack] restarts the clock for a track that is about to
			// begin. None is. Leaving it running would hand every second of the
			// gap to whichever track claims the session next, the moment the flag
			// clears — which is the seven-minute credit measured on 2026-08-11.
			playingSince = 0
			trackIdentity = TrackIdentity(null, null, null, null)
			trackInstanceToken = MediaSessionAdEvidence.nextTrackToken()
			trackInstanceEstablishedAtMillis = System.currentTimeMillis()
			metadata = md
			logMetadata(md, "the browser named its tab, not a track")
			publish()
		}

		private fun resetForNewTrack() {
			cancelNativeStoppedFinalization()
			invalidateNativeResolution()
			describingTabOnly = false
			playedMs = 0
			longestDurationMs = null
			// Per-track, exactly like playedMs: its duration cap belongs to the
			// track that is ending, never to the next one.
			resetPipInference()
			fastestSpeedSeen = 1.0
			firstSeenPositionMs = null
			loopDetected = false
			playingSince = if (isPlaying(state)) SystemClock.elapsedRealtime() else 0
			trackStartedAtEpochSec = System.currentTimeMillis() / 1000
			finalized = false
			taintedReason = null
			latchedVideo = null
			lastStableIdentity = null
			continuationIdentity = null
			continuationAccessibilityCoverage = null
			explicitAdSignal = null
			accessibilityCoverage = null
			rejectedVideoIds.clear()
			unconfirmedVideoIds.clear()
			resolverContext = ResolverContext()
		}

		/**
		 * Ask once per stable native presentation for a unique immutable id. Short
		 * transition phases are excluded by duration; foreground Shorts have their
		 * own exact owner-handle route and never enter here.
		 */
		private fun requestNativeCarryAuthority() {
			if (!isNative || suppressedByForegroundShort || finalized ||
				trackIdentity.hasExactSourceItemId || !isPlaying(state)
			) return
			val title = titleOf(metadata)?.takeIf(String::isNotBlank) ?: return
			artistOf(metadata)?.takeIf(String::isNotBlank) ?: return
			val duration = durationOf(metadata)?.takeIf {
				it >= MIN_NATIVE_PRE_RESOLVE_DURATION_MS
			} ?: return
			val requester = onNativeIdentityRequested ?: return
			val signature = "${trackIdentity.semanticKey}|$duration"
			if (nativeResolutionSignature == signature) return
			nativeResolutionSignature = signature
			val generation = ++nativeResolutionGeneration
			val requestSnapshot = snapshot()
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
					val currentDuration = durationOf(metadata) ?: return@post
					val currentSignature = "${trackIdentity.semanticKey}|$currentDuration"
					if (currentSignature != signature || proof == null) return@post
					trackIdentity = trackIdentity.copy(sourceItemId = proof.videoId)
					resolverContext = resolverContext.copy(
						preResolvedNativeVideoId = proof.videoId,
						preResolvedNativeRoute = proof.route,
					)
					EventLog.append(
						"native-carry",
						"$packageName established immutable carry authority ${proof.videoId} for \"$title\"",
					)
					// A replacement Watch initially had no exact id, so its construction
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
			EventLog.append(
				"native-identity",
				"$packageName exact-ID-less STOPPED state waiting " +
					"${NATIVE_STOPPED_FINALIZE_GRACE_MS / 1000}s for a metadata replacement",
			)
			handler.postDelayed(
				{
					if (token == nativeStoppedFinalizeToken && !finalized &&
						state?.state == PlaybackState.STATE_STOPPED
					) {
						finalizeCurrent("stopped replacement grace expired")
					}
				},
				NATIVE_STOPPED_FINALIZE_GRACE_MS,
			)
		}

		private fun cancelNativeStoppedFinalization() {
			nativeStoppedFinalizeToken++
		}

		/** Hand ownership to the structural foreground-Short lifecycle. */
		fun suppressForForegroundShort(incomingShort: SessionSnapshot?) {
			if (suppressedByForegroundShort) return
			cancelNativeStoppedFinalization()
			accumulate()
			bankProgressBeforeHandover(incomingShort)
			suppressedByForegroundShort = true
			playedMs = 0
			playingSince = 0
			loopDetected = false
			finalized = false
			EventLog.append(
				"native-shorts",
				"$packageName MediaSession hidden while complete foreground Shorts proof is active",
			)
		}

		/**
		 * Score what this MediaSession was playing before the foreground Shorts
		 * route took the player.
		 *
		 * Suppression exists so the same seconds are not counted on both
		 * surfaces, and it starts the MediaSession over at zero for exactly that
		 * reason. What it must not do is delete a listen on the way past.
		 * Measured 2026-08-07: "THE RUN — Official Trailer" reached 85s of its
		 * 104s, the viewer opened the Shorts tab, and the trailer was erased
		 * without a finalize line — 82% watched, nothing scrobbled, no record it
		 * had ever played. That log holds ten of these, up to 168 seconds each.
		 *
		 * The one case where discarding is right is the Short taking over being
		 * the very item this session was describing, which
		 * [ForegroundShortHandover] decides on published evidence alone.
		 */
		private fun bankProgressBeforeHandover(incomingShort: SessionSnapshot?) {
			// A pending continuation cannot survive the hand-off: its expiry
			// callback would land on a suppressed Watch, where finalization is a
			// no-op. Whatever it was holding is decided here instead.
			cancelContinuation()
			if (playedMs == 0L || !trackIdentity.isUsable) return
			if (ForegroundShortHandover.describesSameItem(
					sessionTitle = titleOf(metadata),
					sessionDurationMs = durationOf(metadata),
					shortTitle = incomingShort?.title,
					shortDurationMs = incomingShort?.durationMs,
				)
			) {
				EventLog.append(
					"native-shorts",
					"$packageName MediaSession was already describing the Short the " +
						"foreground route just acquired; its ${playedMs / 1000}s belongs to " +
						"that route and is not scored twice",
				)
				return
			}
			finalizeCurrent("foreground Short proof took over playback")
		}

		/** Resume conservative MediaSession observation from a fresh zero baseline. */
		fun releaseForegroundShortSuppression() {
			if (!suppressedByForegroundShort) return
			suppressedByForegroundShort = false
			trackIdentity = trackIdentityOf(metadata, packageName)
			resetForNewTrack()
			trackInstanceToken = MediaSessionAdEvidence.nextTrackToken()
			trackInstanceEstablishedAtMillis = System.currentTimeMillis()
			EventLog.append(
				"native-shorts",
				"$packageName foreground proof ended; MediaSession resumed at a fresh zero baseline",
			)
		}

		/**
		 * Identity for the given metadata, using the hint bound to *this*
		 * session rather than whatever landed last for the package, applying
		 * the taint rule, and latching the video id for the track's lifetime.
		 */
		private fun identityOf(md: MediaMetadata?): YouTubeProbe.Identity {
			// A Watch waiting outside the active map has ended. Its identity was
			// frozen at disappearance; consulting live evidence here is the exact
			// race that paired Karol G's progress with the next Short's payload.
			continuationIdentity?.let { return it }

			val sessionTitle = titleOf(md)
			val hint = if (isNative) {
				null
			} else {
				NotificationHints.bestFor(
					packageName = packageName,
					title = sessionTitle,
					artist = artistOf(md),
					soleSession = soleBrowserSession,
				)
			}
			// Evidence whose id was disproven for this track is not evidence.
			val url = if (isNative) {
				null
			} else {
				UrlEvidence.get(packageName)
					?.takeUnless { it.videoId != null && it.videoId in rejectedVideoIds }
			}
			// A playlist is context, not a position. [UrlEvidence.playlistByPackage]
			// exists to say so — it keeps the last `list=` for three hours rather
			// than the five minutes a video id gets, because the bar stops naming
			// individual videos within seconds while the playlist keeps advancing.
			//
			// It had no reader. The only path that set `resolverContext.playlistId`
			// was the branch below, off the five-minute reading, so the playlist
			// route — the *exact* one, which beats search — went unavailable five
			// minutes after the browser was last visible. Measured 2026-08-11: a
			// 180-entry 2Pac playlist named in the bar at 23:44 was gone by 00:00,
			// and a track that is entry 41 of it went to search instead.
			if (!isNative && resolverContext.playlistId == null) {
				UrlEvidence.playlistId(packageName)?.let { remembered ->
					resolverContext = resolverContext.copy(playlistId = remembered)
				}
			}
			if (url != null) {
				val firstConcreteId = resolverContext.observedVideoId == null && url.videoId != null
				resolverContext = resolverContext.copy(
					playlistId = resolverContext.playlistId ?: url.playlistId,
					urlGeneration = if (firstConcreteId) {
						url.generation.takeIf { it > 0 }
					} else {
						resolverContext.urlGeneration
					},
					observedVideoId = if (firstConcreteId) url.videoId else resolverContext.observedVideoId,
					observedUrl = if (firstConcreteId) url.raw else resolverContext.observedUrl,
				)
			}
			// Native YouTube has no address bar. The watch screen's playlist bar
			// is the equivalent evidence and the only route that makes native
			// identity exact rather than plausible
			// (<redacted-private-path>). Deliberately kept out of
			// `playlistId`, which stays proven-URL evidence only.
			if (isNative && packageName == YouTubeProbe.YOUTUBE_PACKAGE) {
				NativePlaylistObserver.current()?.let { playlist ->
					resolverContext = resolverContext.copy(
						nativePlaylistName = playlist.playlistName,
						nativePlaylistOwner = playlist.ownerName,
						nativePlaylistTotal = playlist.total,
					)
				}
			}
			val live = if (isNative) {
				YouTubeProbe.identifyNative(packageName, md)
			} else {
				YouTubeProbe.identify(md, hint, url, soleBrowserSession)
			}
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
				onVideoConfirmed?.invoke(live.videoId)
			}

			// Corroborate the latch once a page is known: what it says must match
			// what the session is playing. A clear mismatch means the bar was
			// already showing some other video when we latched — drop the id
			// rather than broadcast a wrong url.
			//
			// Two independent checks, because either can be unavailable. The
			// title is the stronger signal but absent whenever the fetch failed;
			// the duration survives that, and it is what the 2026-07-29
			// wrong-url case turned on. See [durationsDisagree].
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
				if (disagreement != null) {
					val worthSaying = disagreement.contradicts ||
						unconfirmedVideoIds.add(l.videoId)
					if (worthSaying) {
						EventLog.append(
							"identity",
							"$packageName unlatched ${l.videoId}: ${disagreement.reason}",
						)
					}
					if (disagreement.contradicts) rejectedVideoIds += l.videoId
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
			val identityJustConfirmed = selected is YouTubeProbe.Identity.Confirmed &&
				lastStableIdentity !is YouTubeProbe.Identity.Confirmed
			lastStableIdentity = selected
			// The browser mirror of the native resolver's retry: a replacement
			// session exists before the address bar has named what it is playing,
			// so its first attempt to reclaim a continuation could not identify
			// itself. This is the moment it can.
			if (!isNative && identityJustConfirmed) restoreCarriedProgress()
			if (!isNative && selected is YouTubeProbe.Identity.Confirmed && selected.isShort) {
				selected.urlGeneration?.let { generation ->
					AdEvidence.markSessionEstablished(packageName, selected.videoId, generation)
				}
				AdEvidence.get(
					packageName,
					selected.videoId,
					selected.urlGeneration,
				)?.let(::noteAdEvidence)
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
		fun noteAdEvidence(evidence: AdEvidence.Evidence) {
			if (isNative) return
			val identity =
				latchedVideo ?: (lastStableIdentity as? YouTubeProbe.Identity.Confirmed)
			if (!adEvidenceMatches(identity, evidence)) return
			if (explicitAdSignal == evidence.signal) return
			explicitAdSignal = evidence.signal
			EventLog.append(
				"ad",
				"$packageName bound explicit ad evidence to ${evidence.videoId}: " +
					"\"${evidence.signal}\"",
			)
		}

		/**
		 * The ad flag Android itself publishes — `<redacted-private-path>` §3.4.
		 *
		 * `METADATA_KEY_ADVERTISEMENT` is set by the player on a session that is
		 * playing an ad. RustedWax read every other ad signal and not this one,
		 * which is the cheapest and least ambiguous of them: an ad the OS has
		 * labelled is not weaker evidence than one inferred from a screen scrape,
		 * so it is the same hard veto rather than a hint.
		 *
		 * Unlike the visible-UI and notification routes this deliberately **does**
		 * apply to native sessions. Those routes skip native because they read a
		 * browser's chrome, which says nothing about what the YouTube app is
		 * doing; this reads the session's own metadata, which says exactly that.
		 * The corroborating evidence is in `debug/`: finalized titles like
		 * `094 GP EN 16x9 21s 11` are ad creative slate names that reached
		 * finalization as if they were content.
		 *
		 * Only ever set, never cleared: the flag going away means the ad ended and
		 * the *next* track is content, and that next track gets a fresh Watch or a
		 * `resetForNewTrack`. Clearing it here would let the last frame of an ad
		 * launder the listen it interrupted.
		 */
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
		fun noteMediaSessionAdEvidence(observation: MediaSessionAdEvidence.Observation) {
			if (isNative) return
			val accepted = MediaSessionAdEvidence.observe(
				observation = observation,
				instance = mediaSessionAdInstance(),
				instanceEstablishedBeforeObservation =
					trackIdentity.isUsable && isPlaying(state) &&
						trackInstanceEstablishedAtMillis <= observation.atMillis,
				unambiguous = true,
			) ?: return
			if (explicitAdSignal == accepted.signal) return
			explicitAdSignal = accepted.signal
			EventLog.append(
				"ad",
				"$packageName bound explicit ad evidence to MediaSession track " +
					"${accepted.instance.token}: \"${accepted.signal}\"",
			)
		}

		/** One successful root scan supplies coverage and, independently, ad input. */
		fun noteAccessibilityScan(scan: MediaSessionAccessibilityEvidence.Scan) {
			if (isNative) return
			val coverage = MediaSessionAccessibilityEvidence.observe(
				scan = scan,
				instance = mediaSessionAdInstance(),
				unambiguous = watches.values.count { it.packageName == packageName } == 1,
			) ?: return
			accessibilityCoverage = coverage
			if (scan.isShort) {
				if (scan.adSignal == null) MediaSessionAdEvidence.labelAbsent(packageName)
				return
			}
			noteMediaSessionAdEvidence(
				MediaSessionAdEvidence.Observation(
					packageName = packageName,
					signal = scan.adSignal,
					atMillis = scan.atMillis,
				),
			)
		}

		private fun mediaSessionAdInstance() = MediaSessionAdEvidence.TrackInstance(
			packageName = packageName,
			token = trackInstanceToken,
			signature = trackIdentity,
		)

		private fun currentAccessibilityCoverage(
			now: Long = System.currentTimeMillis(),
		): MediaSessionAccessibilityEvidence.Coverage? {
			if (isNative) return null
			MediaSessionAccessibilityEvidence.current(
				instance = mediaSessionAdInstance(),
				expectedUrlGeneration = resolverContext.urlGeneration,
				now = now,
			)?.let {
				accessibilityCoverage = it
				return it
			}
			val local = accessibilityCoverage ?: return null
			val sameInstance = local.instance.packageName == packageName &&
				local.instance.token == trackInstanceToken &&
				local.instance.signature.sameTrackAs(trackIdentity)
			val lifecycleCurrent =
				MediaSessionAccessibilityEvidence.isLifecycleCurrent(local)
			val fresh = now >= local.atMillis &&
				now - local.atMillis <= MediaSessionAccessibilityEvidence.COVERAGE_FRESH_MS
			val sameGeneration = resolverContext.urlGeneration == null ||
				local.urlGeneration == null || local.urlGeneration == resolverContext.urlGeneration
			return local.takeIf { sameInstance && lifecycleCurrent && fresh && sameGeneration }
		}

		/** The hint the probe is currently bound to, for the diagnostics card. */
		private fun boundHint(md: MediaMetadata?): NotificationHints.Hint? =
			if (isNative) null else NotificationHints.bestFor(
				packageName = packageName,
				title = titleOf(md),
				artist = artistOf(md),
				soleSession = soleBrowserSession,
			)

		/**
		 * Folds the elapsed window into [playedMs] and restarts the clock.
		 *
		 * Scaled by the playback rate, which is the whole point: the threshold
		 * compares against `duration`, so what has to be measured is *content
		 * consumed*, not seconds elapsed. A 2026-07-29 field session watched a
		 * 76 s trailer at 1.25× to position 59.9 s — 79% of the video — and it
		 * went on-chain as 67%, because 50 s of wall-clock had passed. At 2× the
		 * same arithmetic puts a fully-watched video at 50% and it never
		 * scrobbles at all.
		 *
		 * Read from `state` deliberately *before* the caller assigns the new one:
		 * the window that just ended was played at the rate that was in effect
		 * during it, not at the rate being switched to.
		 */
		/**
		 * Wall-clock credited while this session was unmeasurable in PiP.
		 *
		 * The foreground-Short route cannot help here: opening a Short and going
		 * straight to picture-in-picture never gives the accessibility tree a
		 * stably readable seekbar, so nothing is ever acquired. Measured
		 * 2026-08-05 on "BECKY G, MAYORES" — the MediaSession carried
		 * `TITLE` and `DURATION = 50000` and then reported `state=NONE`,
		 * `pos=0`, `isActive=false` for the whole session. Title and duration are
		 * there; only progress is missing, which is exactly what this supplies.
		 */
		/**
		 * The longest length this unchanged title has ever claimed.
		 *
		 * YouTube republishes a *shorter* length for the same material — measured
		 * 2026-08-06, a 415s live set reported 415s, then 11s, then 6s while it
		 * was still playing the same thing. Taking each new number at face value
		 * chopped one seven-minute watch into scraps of 6 and 11 seconds, none of
		 * which could clear any threshold.
		 *
		 * Keeping the longest is strictly safer than taking the newest: the case
		 * the shorter number would create is a song looking complete because an
		 * interstitial's length was written over it, which is the exact failure
		 * the old pre-roll guard existed to prevent.
		 */
		private var longestDurationMs: Long? = null

		private var pipInference: PipPlaybackInference? = null
		private var pipInferredMs: Long = 0

		/**
		 * One tick of PiP evidence for a session the MediaSession cannot measure.
		 *
		 * Only ever credits when the session publishes no usable progress of its
		 * own: a regular video in PiP keeps reporting position and is measured
		 * normally, and must not be double-counted.
		 */
		fun creditPipInference(nowMillis: Long, playing: Boolean) {
			if (suppressedByForegroundShort || !isNative) return
			val duration = durationOf(metadata) ?: return
			if (duration <= 0 || isPlaying(state)) {
				pipInference?.observe(nowMillis, playing = false)
				return
			}
			val running = pipInference ?: PipPlaybackInference(
				durationMs = duration,
				measuredMs = playedMs,
			).also { pipInference = it }
			running.observe(nowMillis, playing)
			pipInferredMs = running.credited
		}

		private fun resetPipInference() {
			pipInference = null
			pipInferredMs = 0
		}

		private fun accumulate() {
			if (suppressedByForegroundShort || describingTabOnly) {
				playingSince = 0
				return
			}
			if (playingSince != 0L) {
				val speed = speedOf(state)
				val elapsed = SystemClock.elapsedRealtime() - playingSince
				playedMs += (elapsed * speed).toLong()
				if (speed > fastestSpeedSeen) fastestSpeedSeen = speed
				playingSince = 0
			}
		}

		private fun playedMsNow(): Long = if (suppressedByForegroundShort || describingTabOnly) {
			0
		} else {
			playedMs + pipInferredMs + if (playingSince != 0L) {
				((SystemClock.elapsedRealtime() - playingSince) * speedOf(state)).toLong()
			} else {
				0
			}
		}

		private fun speedOf(ps: PlaybackState?): Double = speedFactor(ps?.playbackSpeed)

		/** The first position seen for a track, whatever announced it. */
		private fun noteFirstSeenPosition(ps: PlaybackState?) {
			if (firstSeenPositionMs != null) return
			firstSeenPositionMs = ps?.position?.takeIf { it >= 0 }
		}

		/**
		 * "…, first seen 94s in", when the player was already well into the track
		 * when RustedWax first saw it. Everything before that point was never
		 * published to us and can never have been measured, so a short `played`
		 * against a long duration is an accurate report rather than a fault.
		 * Silent for the ordinary case of a track seen from its start.
		 */
		private fun unobservedLeadInNote(playedMs: Long): String {
			val firstSeen = firstSeenPositionMs ?: return ""
			if (firstSeen < UNOBSERVED_LEAD_IN_MS) return ""
			// A track whose progress was carried across a replacement session has
			// already accounted for its lead-in; only an unexplained one is news.
			if (playedMs >= firstSeen) return ""
			return ", first seen ${firstSeen / 1000}s in — " +
				"anything played before that was never published to RustedWax"
		}

		fun logMetadata(md: MediaMetadata?, reason: String) {
			noteAdvertisementMetadata(md)
			// Identity is settled *before* anything is written, because whether we
			// may write is exactly what identity decides — §4.1. The metadata is
			// still read and still drives detection; only the record of it waits.
			logIdentity(md, reason)
			if (mayRecordIdentifyingDetail()) {
				EventLog.appendBlock(
					"metadata",
					"$packageName / ${origin.displayName} ($reason)",
					MetadataDump.dump(md),
				)
			}
			requestNativeCarryAuthority()
		}

		/** Re-run identity after a late-arriving notification hint. */
		fun reidentify(trigger: String) = logIdentity(metadata, "re-check after $trigger")

		/**
		 * Whether this session may be described in the log at all.
		 *
		 * Chrome publishes a MediaSession for *any* video site, and RustedWax
		 * built a `Watch` and wrote its page title before anything had proven the
		 * site was YouTube. The result was an accumulating record of what the user
		 * watched everywhere else — timing, title and package — in a file the app
		 * offers to export. `UrlWatcherService` was never the leak; this was.
		 *
		 * Native packages are proven by the package itself. A browser session has
		 * to earn it, and until it does the only thing recorded is that some
		 * number of sessions were ignored.
		 */
		private fun mayRecordIdentifyingDetail(): Boolean = isNative ||
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
				"+ $packageName ($appLabel) ← ${origin.displayName}",
			)
			if (isNative) {
				EventLog.append(
					"native",
					"$packageName MediaSession instrumentation active; browser visible-ad " +
						"protection does not cover native apps and no generic native ad flag is assumed",
				)
			}
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
					"$packageName / ${origin.displayName} → YouTube ${id.videoId} " +
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
			// Position tracking still runs for an unproven session — it is how a
			// browser tab that later turns out to be YouTube keeps its progress —
			// but nothing about it is written until it is proven. §4.1.
			if (ps == null) {
				if (mayRecordIdentifyingDetail()) {
					EventLog.append("playback", "$packageName ($reason) <null state>")
				}
				return
			}
			noteFirstSeenPosition(ps)
			if (!mayRecordIdentifyingDetail()) return
			EventLog.append(
				"playback",
				"$packageName ($reason) state=${stateName(ps.state)} " +
					"pos=${ps.position}ms speed=${ps.playbackSpeed} " +
					"updatedAt=${ps.lastPositionUpdateTime} played=${playedMsNow()}ms",
			)
			if (isNative) {
				EventLog.appendBlock(
					"native-state",
					"$packageName / ${origin.displayName} ($reason)",
					PlaybackStateDump.dump(ps),
				)
			}
		}

		/** Exactly what the session published, before [BrowserTabMetadata] reads it. */
		private fun rawTitleOf(md: MediaMetadata?): String? =
			MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_TITLE)
				?: MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)

		private fun titleOf(md: MediaMetadata?): String? =
			BrowserTabMetadata.titleOrNull(packageName, rawTitleOf(md))

		private fun artistOf(md: MediaMetadata?): String? = BrowserTabMetadata.artistOrNull(
			packageName,
			MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_ARTIST)
				?: MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
		)

		private fun durationOf(md: MediaMetadata?): Long? =
			MetadataDump.longOrNull(md, MediaMetadata.METADATA_KEY_DURATION)

		/**
		 * The length this track has established, which a bundle that omits
		 * DURATION does not erase.
		 *
		 * A bundle without DURATION is silence about the length, not a statement
		 * that the track has none. YouTube's web player publishes exactly that as
		 * a video ends, and republishes it intermittently while one plays —
		 * measured 2026-08-10, "Happy Song" reported 236981 ms for its whole
		 * 247-second watch and then dropped it half a second before the track
		 * changed:
		 *
		 *   21:13:51  DURATION = 236981   pos=236974
		 *   21:13:52  unset: … DURATION …
		 *   21:14:02  [finalize] Happy Song — played 247s of 0s
		 *
		 * With no length there is no percentage to clear a threshold with and no
		 * duration to resolve an id by, so a complete listen produced nothing:
		 * "title, owner/channel and duration were not all available for lookup".
		 * The same silence also makes the latch's own corroboration unavailable,
		 * so a page whose title is a short form of the session's kept failing to
		 * corroborate and the track's identity was wiped pass after pass, ending
		 * in "source not proven YouTube".
		 *
		 * [TrackIdentity.refinedWith] has kept the established length all along —
		 * it is what decides these updates describe the same track — so this
		 * reads a value the session already holds rather than inventing one. A
		 * published length always wins; this is consulted only when the current
		 * bundle says nothing.
		 */
		private fun establishedDurationMs(md: MediaMetadata?): Long? =
			durationOf(md)?.let { published -> maxOf(published, longestDurationMs ?: 0) }
				?: longestDurationMs
				?: trackIdentity.durationMs?.takeIf { it > 0 }

		fun snapshot(finalizedTrack: Boolean = false): SessionSnapshot {
			val md = metadata
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
				knownTitle = known?.title,
				knownChannel = known?.channel,
				knownDurationSeconds = known?.lengthSeconds,
				rejectedVideoIds = rejectedVideoIds.toSet(),
			)
			val frozenCoverage = if (finalizedTrack) {
				continuationAccessibilityCoverage ?: currentAccessibilityCoverage()
			} else {
				currentAccessibilityCoverage()
			}
			return SessionSnapshot(
				packageName = packageName,
				appLabel = appLabel,
				isTarget = isTarget,
				title = titleOf(md),
				artist = artistOf(md),
				album = MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_ALBUM),
				durationMs = duration,
				positionMs = position,
				playedMs = played,
				loopDetected = loopDetected,
				explicitAdSignal = explicitAdSignal,
				browserEvidenceEnabled = !isNative && browserEvidenceEnabledForThisRun(),
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
				metadataLines = MetadataDump.dump(md),
				trackStartedAtEpochSec = trackStartedAtEpochSec,
				genre = MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_GENRE),
				sourceEpoch = sourceEpoch,
			)
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

	private fun trackIdentityOf(md: MediaMetadata?, packageName: String): TrackIdentity = TrackIdentity(
		// A tab's own title and a page's origin are not this track's identity —
		// see [BrowserTabMetadata]. Filtered here as well as at the snapshot, so
		// the browser cannot end one track and start another by announcing the
		// window it is showing.
		title = BrowserTabMetadata.titleOrNull(
			packageName,
			MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_TITLE)
				?: if (YouTubeProbe.isNativePackage(packageName)) {
					MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
				} else null,
		),
		artist = BrowserTabMetadata.artistOrNull(
			packageName,
			MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_ARTIST)
				?: if (YouTubeProbe.isNativePackage(packageName)) {
					MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
				} else null,
		),
		album = MetadataDump.textOrNull(md, MediaMetadata.METADATA_KEY_ALBUM),
		durationMs = MetadataDump.longOrNull(md, MediaMetadata.METADATA_KEY_DURATION),
		sourceItemId = if (YouTubeProbe.isNativePackage(packageName)) {
			(YouTubeProbe.identifyNative(packageName, md) as? YouTubeProbe.Identity.Confirmed)?.videoId
		} else null,
	)

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
		/** Measured 8 s STOPPED gap between same-title native duration phases. */
		const val NATIVE_STOPPED_FINALIZE_GRACE_MS = 10_000L
		const val MIN_NATIVE_PRE_RESOLVE_DURATION_MS = 60_000L

		/**
		 * How far into a track the player may already be before its lead-in is
		 * worth naming. Ten seconds — below that it is ordinary startup jitter,
		 * above it something played that RustedWax was never shown.
		 */
		private const val UNOBSERVED_LEAD_IN_MS = 10_000L

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
		/**
		 * Why a latched id was dropped, and whether that is evidence *against* it.
		 *
		 * @param contradicts the page named a different video, so the id follows
		 * the track as rejected. False when the page simply could not establish
		 * the id — the latch is still dropped, but nothing is held against it.
		 */
		data class LatchDisagreement(val reason: String, val contradicts: Boolean)

		/**
		 * Not confirming an id is not the same as disproving it.
		 *
		 * Measured 2026-08-10: tapping "Happy Song" in a playlist read the address
		 * bar before the session had published a duration, so the page's short
		 * title was only weak evidence and `GBRAnuT48qo` was filed as rejected.
		 * Four minutes later the resolver proved that same id by title + duration
		 * + the watch page's own channel — a strictly stronger join than this one
		 * — and a 225-of-236-second listen was thrown away for "video id
		 * GBRAnuT48qo was rejected while the track was active".
		 *
		 * The latch is still dropped in both cases: a weak title may never confirm
		 * an id. What changes is that insufficiency no longer outranks the
		 * stronger evidence that arrives afterwards.
		 */
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

		/**
		 * Second, independent corroboration of a latched id: does the page's length
		 * match what the session says it's playing?
		 *
		 * Added because the title check **fails open**. On 2026-07-29 the address
		 * bar was 7 seconds late advancing a playlist, so a Danger Man track latched
		 * the *previous* entry's id — and the page fetch for that id had already
		 * timed out, so there was no title to compare and the stale id survived onto
		 * the chain with a `url` pointing at Daddy Yankee's "Con Calma". The
		 * durations were 226 s against 193 s: the mismatch was sitting right there.
		 *
		 * Tolerance is both absolute *and* proportional, and it has to be both.
		 * `lengthSeconds` and the session's `DURATION` routinely differ by a second
		 * of rounding, so a flat threshold alone is too noisy; a percentage alone
		 * would let a 30-second disagreement pass on a two-hour video.
		 */
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
		 */
		fun positionWrapped(
			previousPositionMs: Long?,
			newPositionMs: Long?,
			durationMs: Long?,
		): Boolean {
			if (previousPositionMs == null || newPositionMs == null ||
				durationMs == null || durationMs <= 0 ||
				previousPositionMs < 0 || newPositionMs < 0
			) {
				return false
			}
			val nearEnd = previousPositionMs.toDouble() >= durationMs * LOOP_END_FRACTION
			val nearStart = newPositionMs.toDouble() <= durationMs * LOOP_START_FRACTION
			val largeReset =
				previousPositionMs - newPositionMs >= durationMs * LOOP_MIN_RESET_FRACTION
			return nearEnd && nearStart && largeReset
		}

		/**
		 * Ceiling on the rate [Watch.accumulate] will scale by.
		 *
		 * YouTube's own maximum is 2×; this leaves headroom for players that
		 * offer more while refusing to let one absurd `playbackSpeed` sample turn
		 * ten seconds of play into a full listen.
		 */
		const val MAX_PLAYBACK_SPEED = 4.0

		/** Rounding slack between `lengthSeconds` and the session's `DURATION`. */
		const val DURATION_TOLERANCE_MS = 5_000L

		/** Also required, so long videos aren't held to the flat 5 s. */
		const val DURATION_TOLERANCE_FRACTION = 0.05

		const val LOOP_END_FRACTION = 0.8
		const val LOOP_START_FRACTION = 0.2
		const val LOOP_MIN_RESET_FRACTION = 0.5

		/**
		 * The rate to score a play window at, given what `PlaybackState`
		 * reported.
		 *
		 * Non-positive — paused, or simply unreported — is **not** an instruction
		 * to count zero. `playingSince` already decides whether a window counts
		 * at all; a `speed=0.0` sample landing mid-window would otherwise erase
		 * time genuinely spent playing. A missing value means "assume normal
		 * speed", which is the pre-v0.8.1 behaviour.
		 *
		 * Clamped above so one absurd sample can't turn ten seconds into a full
		 * listen. Extracted here, away from the Android types, so those rules are
		 * unit-testable.
		 */
		fun speedFactor(reported: Float?): Double {
			val raw = reported?.toDouble() ?: return 1.0
			if (!raw.isFinite() || raw <= 0.0) return 1.0
			return raw.coerceAtMost(MAX_PLAYBACK_SPEED)
		}

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
