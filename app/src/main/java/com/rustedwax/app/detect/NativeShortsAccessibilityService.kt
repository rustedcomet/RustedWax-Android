package com.rustedwax.app.detect

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.rustedwax.app.storage.Settings as AppSettings
import android.view.accessibility.AccessibilityNodeInfo
import com.rustedwax.app.BuildConfig
import com.rustedwax.core.PlayerAdSurface
import com.rustedwax.core.SourceSessionId
import java.util.concurrent.atomic.AtomicBoolean

/** Separately disclosed, OS-scoped foreground native YouTube Shorts observer. */
class NativeShortsAccessibilityService : AccessibilityService() {

	/**
	 * The source this service captures for.
	 *
	 * Stateless and pure: it holds no tracker and touches no store, so keeping one
	 * here costs nothing and means the interpretation lives in one place rather
	 * than being duplicated between this gateway and the probe.
	 */
	private val shorts = NativeShortsAdapter()

	private val handler = Handler(Looper.getMainLooper())
	private val captureThread = HandlerThread("rustedwax-native-shorts-capture").apply { start() }
	private val captureHandler = Handler(captureThread.looper)
	private val captureInFlight = AtomicBoolean(false)
	@Volatile private var destroyed = false
	@Volatile private var lastCompleteProofAtMillis = 0L
	private var acquisitionRefreshUntilElapsed = 0L
	private var lastPlaylistPollElapsed = 0L

	/** Reporting-only detector for the §5.1 event-stream outage. */
	private val eventSilence = AccessibilityEventSilence()

	/** Public-API evidence that a surface-less Short is still playing. */
	private val pipProbe by lazy { PipPlaybackProbe(applicationContext) }
	private var pipInferenceEnabled = false
	private var loggedMissingUsageAccess = false
	private val refresh = object : Runnable {
		override fun run() {
			val now = System.currentTimeMillis()
			if (NativeShortsObserver.shouldRefresh() &&
				lastCompleteProofAtMillis > 0 &&
				now - lastCompleteProofAtMillis >= PROOF_FRESHNESS_MS
			) {
				// AccessibilityNodeInfo binder calls can stall while YouTube hides its
				// overlay. This watchdog stays on the service main thread, so a stalled
				// capture can never extend the no-credit proof grace.
				reportUnavailable(
					"fresh foreground Shorts proof expired while capture was unavailable",
					now,
					ShortsSurfaceUnavailableKind.PROOF_EXPIRED,
				)
			}
			// Deliberately last, and only asked when nothing else already brings
			// a capture: the outage worth catching is the one where no Short is
			// latched and a latched playlist has stopped the acquisition poll,
			// so nothing runs and the observer goes quiet without a trace.
			val silenceProbeDue = eligible() && eventSilence.probeDue(SystemClock.elapsedRealtime())
			if (NativeShortsObserver.shouldRefresh() ||
				SystemClock.elapsedRealtime() <= acquisitionRefreshUntilElapsed ||
				playlistAcquisitionDue() ||
				silenceProbeDue
			) {
				requestObservation("bounded foreground refresh")
			} else if (eligible() && NativeWatchAdObserver.watchPlaybackActive()) {
				// A full observation above already reads the watch player on its way
				// past. Otherwise a playing watch listen still needs the player looked
				// at every second: an ad draws its label for as long as it runs, and
				// YouTube emits no accessibility event while nothing on screen changes.
				requestWatchAdObservation()
			}
			handler.postDelayed(this, REFRESH_INTERVAL_MS)
		}
	}

	private fun playlistAcquisitionDue(): Boolean {
		if (nativePlaylistCurrent() != null) return false
		val now = SystemClock.elapsedRealtime()
		if (now - lastPlaylistPollElapsed < PLAYLIST_POLL_INTERVAL_MS) return false
		lastPlaylistPollElapsed = now
		return true
	}

	override fun onServiceConnected() {
		MonitorSwitch.init(applicationContext)
		NativeSourceSwitches.init(applicationContext)
		eventSilence.started(SystemClock.elapsedRealtime())
		shorts.readUnavailableSurface(
			ShortsSurfaceUnavailableFacts(
				kind = ShortsSurfaceUnavailableKind.LIFECYCLE_RESET,
				reason = "native Shorts observer connected",
				observedAtMillis = System.currentTimeMillis(),
				displayOff = false,
				pipEvidence = noPipEvidence(),
				foregroundSurfaceOwned = false,
			),
		)
		NativeShortsObserver.connected()
		EventLog.append(
			"native-shorts",
			"foreground Shorts observer connected; OS package scope is ${YouTubeProbe.YOUTUBE_PACKAGE}",
		)
		handler.removeCallbacks(refresh)
		handler.postDelayed(refresh, REFRESH_INTERVAL_MS)
	}

	override fun onAccessibilityEvent(event: AccessibilityEvent?) {
		if (!eligible()) return
		if (event?.packageName?.toString() != YouTubeProbe.YOUTUBE_PACKAGE) return
		eventSilence.eventReceived(SystemClock.elapsedRealtime())
			?.let { EventLog.append("native-shorts", it) }
		// YouTube can publish its final root/title/seekbar after its last content
		// callback. Retry only this exact foreground package for a short bounded
		// acquisition window; once proven, SessionProbe requests active refreshes.
		acquisitionRefreshUntilElapsed =
			SystemClock.elapsedRealtime() + ACQUISITION_REFRESH_MS
		if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
			NativeShortsObserver.shouldRefresh()
		) {
			reportUnavailable(
				"native Shorts scroll/seek event reset the position baseline",
				System.currentTimeMillis(),
				ShortsSurfaceUnavailableKind.NAVIGATION_RESET,
			)
		}
		requestObservation("accessibility callback")
	}

	override fun onInterrupt() = Unit

	override fun onDestroy() {
		destroyed = true
		handler.removeCallbacks(refresh)
		acquisitionRefreshUntilElapsed = 0
		lastCompleteProofAtMillis = 0
		captureThread.quitSafely()
		shorts.readUnavailableSurface(
			ShortsSurfaceUnavailableFacts(
				kind = ShortsSurfaceUnavailableKind.LIFECYCLE_RESET,
				reason = "native Shorts observer destroyed",
				observedAtMillis = System.currentTimeMillis(),
				displayOff = false,
				pipEvidence = noPipEvidence(),
				foregroundSurfaceOwned = false,
			),
		)
		NativeShortsObserver.disconnected("native Shorts accessibility service disconnected")
		clearNativePlaylist()
		NativeSourceSwitches.invalidatePackage(
			YouTubeProbe.YOUTUBE_PACKAGE,
			"native Shorts accessibility disconnected",
		)
		EventLog.append("native-shorts", "foreground Shorts observer disconnected and cleared")
		super.onDestroy()
	}

	private fun eligible(): Boolean = MonitorSwitch.isEnabled &&
		NativeSourceSwitches.acceptsPackage(YouTubeProbe.YOUTUBE_PACKAGE)

	private fun reportMissing(
		reason: String,
		observedAtMillis: Long,
		progressSurfaceLost: Boolean = false,
		inferredPlaying: Boolean = false,
		playbackRate: Double? = null,
		displayOff: Boolean? = null,
	) {
		NativeShortsObserver.missing(
			reason,
			observedAtMillis,
			progressSurfaceLost,
			inferredPlaying,
			playbackRate,
			displayOff = displayOff ?: !displayInteractive(),
		)
	}

	private fun noPipEvidence() = ShortsPipEvidence(
		inferenceEnabled = false,
		mediaAudioStarted = false,
		visiblePinnedWindow = false,
	)

	private fun reportUnavailable(
		reason: String,
		observedAtMillis: Long,
		kind: ShortsSurfaceUnavailableKind,
		pipEvidence: ShortsPipEvidence = noPipEvidence(),
	) {
		val decision = shorts.readUnavailableSurface(
			ShortsSurfaceUnavailableFacts(
				kind = kind,
				reason = reason,
				observedAtMillis = observedAtMillis,
				displayOff = !displayInteractive(),
				pipEvidence = pipEvidence,
				foregroundSurfaceOwned = NativeShortsObserver.shouldRefresh(),
			),
		)
		if (decision.preserveProofFreshness) lastCompleteProofAtMillis = observedAtMillis
		decision.readings.forEach { reading ->
			when (reading) {
				is ShortsSurfaceReading.Proven -> {
					lastCompleteProofAtMillis = observedAtMillis
					EventLog.append(
						"native-shorts",
						"fresh stabilizing foreground identity handed directly to picture-in-picture",
					)
					NativeShortsObserver.accepted(
						reading.result,
						observedAtMillis,
						reading.inferredPlaying,
					)
				}

				is ShortsSurfaceReading.Absent -> reportMissing(
					reading.reason,
					observedAtMillis,
					progressSurfaceLost = reading.progressSurfaceLost,
					inferredPlaying = reading.inferredPlaying,
					playbackRate = reading.playbackRate,
					displayOff = reading.displayOff,
				)
			}
		}
	}

	/**
	 * Whether the screen is on, as of right now.
	 *
	 * Read at report time rather than tracked from broadcasts: the value is only
	 * consulted when proof is already missing, and a listener would have to
	 * survive the service being torn down and rebuilt to be worth its wiring.
	 * Defaults to interactive when the service cannot be reached, so a failure
	 * here can only preserve the old behaviour, never extend a grace.
	 */
	private fun displayInteractive(): Boolean = runCatching {
		getSystemService(PowerManager::class.java)?.isInteractive ?: true
	}.getOrDefault(true)

	/**
	 * Preserve a complete first Short frame across an immediate Android PiP handoff.
	 *
	 * Once PiP is visible the active accessibility root belongs to RustedWax, the
	 * launcher, or no window at all. That is expected, not proof that playback
	 * ended. The paired audio + visible-pinned-window probe is independent of the
	 * missing tree. It may promote only the stabilizer's fresh, identity-bearing
	 * organic candidate; it can never invent identity from the empty native
	 * MediaSession or from an unnamed/ad frame.
	 */
	private fun reportSurfaceUnavailableDuringPossiblePip(
		reason: String,
		observedAtMillis: Long,
	) {
		reportUnavailable(
			reason,
			observedAtMillis,
			ShortsSurfaceUnavailableKind.SURFACE_GONE,
			pipEvidence(observedAtMillis),
		)
	}

	private fun requestObservation(reason: String) {
		if (destroyed || !captureInFlight.compareAndSet(false, true)) return
		captureHandler.post {
			try {
				observeForeground(reason)
			} finally {
				captureInFlight.set(false)
			}
		}
	}

	/**
	 * One capture outcome into the §5.1 detector.
	 *
	 * The screen check is read here rather than inside the detector so the
	 * decision stays pure and testable. `isInteractive` is the display state,
	 * which is what "the user could have been watching this" actually needs —
	 * playback with the screen off is unobservable by design, not by fault.
	 */
	private fun reportEventSilence(surface: ObservedSurface) {
		val screenOn = runCatching {
			getSystemService(PowerManager::class.java)?.isInteractive == true
		}.getOrDefault(false)
		eventSilence.observed(
			SystemClock.elapsedRealtime(),
			surface,
			screenOn,
			unmeasuredPlayback = ::unmeasuredPlayback,
		)?.let { EventLog.append("native-shorts", it) }
	}

	private fun unmeasuredPlayback(): Boolean? {

		if (nativeWatchSessionMeasuring()) return false
		if (!pipProbe.hasUsageAccess()) return null
		return runCatching {
			pipProbe.youTubePlayingWithoutSurface(System.currentTimeMillis())
		}.getOrNull()
	}

	/**
	 * Whether the MediaSession is already measuring native YouTube playback.
	 *
	 * A title is the discriminator, not the playing state: the session survives
	 * a pause, and a paused watch video is not an outage either.
	 */
	private fun nativeWatchSessionMeasuring(): Boolean = runCatching {
		ProbeHolder.current?.sessions?.value.orEmpty().any {
			it.packageName == YouTubeProbe.YOUTUBE_PACKAGE &&
				it.profile.packageProvesSource &&

				!it.isForegroundShort &&
				!it.title.isNullOrBlank()
		}
	}.getOrDefault(false)

	/**
	 * Whether a proven Short whose direct progress is absent or stalled is
	 * nonetheless still playing.
	 *
	 * Off unless the user opted in, and inert without Usage Access — without it
	 * the audio list cannot be attributed to YouTube at all, so the honest
	 * answer is "don't know", which credits nothing. Said once per service life
	 * rather than every second.
	 */
	/**
	 * The user's "count what cannot be measured" switch, without the PiP-specific
	 * evidence. A speed chip proves playback on its own; it still obeys the
	 * toggle, because the toggle is about crediting inference at all.
	 */
	private fun inferenceEnabled(): Boolean {
		pipInferenceEnabled = runCatching {
			AppSettings(applicationContext).pipInference
		}.getOrDefault(false)
		return pipInferenceEnabled
	}

	private fun pipEvidence(nowMillis: Long): ShortsPipEvidence {
		// Read here rather than in `eligible()`: only a proven organic Short can
		// spend this evidence, whereas `eligible()` runs on every accessibility event.
		// Reading per call also means toggling the switch takes effect on the
		// next poll instead of the next reconnect.
		pipInferenceEnabled = runCatching {
			AppSettings(applicationContext).pipInference
		}.getOrDefault(false)
		if (!pipInferenceEnabled) return noPipEvidence()
		if (!pipProbe.hasUsageAccess()) {
			if (!loggedMissingUsageAccess) {
				loggedMissingUsageAccess = true
				EventLog.append(
					"native-shorts",
					"picture-in-picture time is on, but Usage Access is not granted — " +
						"YouTube cannot be told apart from any other app playing audio, " +
						"so PiP will keep counting for nothing until it is granted",
				)
			}
			return noPipEvidence()
		}
		val evidence = pipProbe.evidence(nowMillis)
		return ShortsPipEvidence(
			inferenceEnabled = true,
			mediaAudioStarted = evidence.mediaAudioStarted,
			visiblePinnedWindow = evidence.visiblePinnedWindow,
		)
	}

	private fun observeForeground(reason: String) {
		val captureStartedElapsed = SystemClock.elapsedRealtime()
		val now = System.currentTimeMillis()
		if (!eligible()) {
			reportUnavailable(
				"observer idle: Monitoring and Native YouTube must both be on",
				now,
				ShortsSurfaceUnavailableKind.INELIGIBLE,
			)
			// Hold the silence clock at "now" while switched off, so the quiet of
			// a deliberate opt-out never accumulates into an outage that gets
			// reported the moment monitoring comes back on.
			eventSilence.started(SystemClock.elapsedRealtime())
			clearNativePlaylist()
			return
		}
		if (RustedWaxUiVisibility.isResumed) {
			// The active root is necessarily this app, not native YouTube. Asking
			// Android for it makes Compose synchronously materialize RustedWax's
			// semantics tree and cannot add source evidence. Preserve the existing
			// surface-unavailable/PiP path without paying for that useless tree.
			reportSurfaceUnavailableDuringPossiblePip(
				"RustedWax is foreground; native YouTube root not requested",
				now,
			)
			NativeWatchAdObserver.observed(
				NativeWatchAdParser.unobserved("RustedWax is foreground"),
			)
			observeNativePlaylist(
				NativePlaylistParser.Result.Unobservable(
					"RustedWax is foreground; native YouTube root not requested",
				),
				now,
			)
			return
		}
		val root = acquireFreshRoot()
		if (root == null) {
			reportSurfaceUnavailableDuringPossiblePip(
				"no fresh active native YouTube accessibility root",
				now,
			)
			NativeWatchAdObserver.observed(
				NativeWatchAdParser.unobserved("no fresh active native YouTube accessibility root"),
			)
			// With the screen off this is ordinary. With the screen on it means
			// the service cannot see any window at all, which is the most likely
			// shape of the §5.1 outage — hence reporting it rather than only
			// reporting captures that succeeded.
			reportEventSilence(ObservedSurface.NO_ROOT)
			// Backgrounded or screen off. The latch holds through this — playback
			// continues without any tree at all.
			observeNativePlaylist(
				NativePlaylistParser.Result.Unobservable(
					"no fresh active native YouTube accessibility root",
				),
				now,
			)
			return
		}
		val tree: NativeShortTree?
		val playlistCapture: NativePlaylistCapture
		val watchAdCapture: NativeWatchAdCapture
		try {
			reportShortSurface(root, now)
			tree = captureTargeted(root)
			playlistCapture = capturePlaylist(root)
			watchAdCapture = captureWatchAd(root)
		} finally {
			root.recycle()
		}
		publishWatchAd(watchAdCapture, captureStartedElapsed)
		// Independent of the Shorts result: the playlist bar belongs to the
		// ordinary watch screen, which is exactly where captureTargeted finds
		// nothing.
		observeNativePlaylist(NativePlaylistParser.parse(playlistCapture), now)
		if (tree == null) {
			reportSurfaceUnavailableDuringPossiblePip(
				"foreground root was hidden or not native YouTube",
				now,
			)
			reportEventSilence(ObservedSurface.OTHER_APP)
			return
		}
		// What a captured tree means is the source's, not this service's. The
		// gateway's remaining job is to supply the primitives — the tree, and the
		// paired usage/audio/window and settings facts — and to dispatch the
		// verdict. See `NativeShortsAdapter.readSurface`.
		val reading = shorts.readStableSurface(
			ShortsSurfaceCapture(
				tree = tree,
				pipEvidence = pipEvidence(now),
				inferenceEnabled = inferenceEnabled(),
			),
			observedAtMillis = now,
		)
		if (destroyed) return
		val absent = reading as? ShortsSurfaceReading.Absent
		val inferredPlaying = absent?.inferredPlaying ?: false
		// Reporting-only; nothing below branches on it. A parsed player is the
		// observer proving it can see, and is the liveness signal the event
		// callbacks are not: a latched Short is measured by the 1s poll while
		// YouTube emits no events at all.
		if (absent != null) {

			val crediting = inferredPlaying && NativeShortsObserver.shouldRefresh()
			if (!crediting) reportEventSilence(ObservedSurface.YOUTUBE_NO_PLAYER)
		} else {
			eventSilence.captureSucceeded(SystemClock.elapsedRealtime())
				?.let { EventLog.append("native-shorts", it) }
		}
		if (SystemClock.elapsedRealtime() - captureStartedElapsed > MAX_CAPTURE_AGE_MS) {
			reportUnavailable(
				"$reason: accessibility capture exceeded the freshness bound",
				System.currentTimeMillis(),
				ShortsSurfaceUnavailableKind.CAPTURE_STALE,
			)
			return
		}
		when (reading) {
			is ShortsSurfaceReading.Absent -> reportMissing(
				"$reason: ${reading.reason}",
				now,
				progressSurfaceLost = reading.progressSurfaceLost,
				inferredPlaying = reading.inferredPlaying,
				playbackRate = reading.playbackRate,
			)

			is ShortsSurfaceReading.Proven -> {
				lastCompleteProofAtMillis = now
				NativeShortsObserver.accepted(reading.result, now, reading.inferredPlaying)
			}
		}
	}

	private fun acquireFreshRoot(): AccessibilityNodeInfo? = AccessibilityRootFreshener.acquire(
		clearCache = {
			// API 33 added a service-wide cache invalidation. Older releases still
			// get the per-root refresh below.
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) clearCache()
		},
		obtain = { rootInActiveWindow },
		refresh = { candidate -> runCatching { candidate.refresh() }.getOrDefault(false) },
		recycle = AccessibilityNodeInfo::recycle,
	)

	/** A look at the watch player only, for a playing watch listen nothing else is observing. */
	private fun requestWatchAdObservation() {
		if (destroyed || !captureInFlight.compareAndSet(false, true)) return
		captureHandler.post {
			try {
				observeWatchAd()
			} finally {
				captureInFlight.set(false)
			}
		}
	}

	private fun observeWatchAd() {
		if (!eligible()) return
		val startedElapsed = SystemClock.elapsedRealtime()
		if (RustedWaxUiVisibility.isResumed) {
			NativeWatchAdObserver.observed(NativeWatchAdParser.unobserved("RustedWax is foreground"))
			return
		}
		val root = acquireFreshRoot() ?: run {
			NativeWatchAdObserver.observed(
				NativeWatchAdParser.unobserved("no fresh active native YouTube accessibility root"),
			)
			return
		}
		val capture = try {
			captureWatchAd(root)
		} finally {
			root.recycle()
		}
		publishWatchAd(capture, startedElapsed)
	}

	private fun publishWatchAd(capture: NativeWatchAdCapture, captureStartedElapsed: Long) {
		if (destroyed) return
		NativeWatchAdObserver.observed(
			if (SystemClock.elapsedRealtime() - captureStartedElapsed > MAX_CAPTURE_AGE_MS) {
				// What the player showed that long ago may already describe the next
				// presentation, and the reducer applies a look to what is installed now.
				NativeWatchAdParser.unobserved("watch-player capture exceeded the freshness bound")
			} else {
				NativeWatchAdParser.parse(capture)
			},
		)
	}

	/**
	 * The watch player's own ad controls, by exact view id.
	 *
	 * Ordered so the common cases cost least: a drawn label ends the look, and a
	 * player that is not on screen needs nothing else asked of it. Only the node
	 * itself is read — every control measured carries its label on that node.
	 */
	private fun captureWatchAd(root: AccessibilityNodeInfo): NativeWatchAdCapture {
		val notYouTube = NativeWatchAdCapture(
			packageName = null,
			watchPlayerVisible = false,
			otherPlayerSurfaceVisible = false,
			adControlNodes = emptyList(),
		)
		if (!root.isVisibleToUser || root.packageName?.toString() != YouTubeProbe.YOUTUBE_PACKAGE) {
			return notYouTube
		}
		fun visible(viewId: String): Boolean =
			runCatching { root.findAccessibilityNodeInfosByViewId(ID_PREFIX + viewId) }
				.getOrDefault(emptyList())
				.let { matches ->
					val any = matches.any { runCatching { it.isVisibleToUser }.getOrDefault(false) }
					matches.forEach(AccessibilityNodeInfo::recycle)
					any
				}
		val controls = mutableListOf<NativeShortNode>()
		NativeWatchAdParser.AD_CONTROL_VIEW_IDS.forEach { viewId ->
			val matches = runCatching { root.findAccessibilityNodeInfosByViewId(ID_PREFIX + viewId) }
				.getOrDefault(emptyList())
			try {
				matches.take(MAX_MATCHES_PER_ID).forEach { match ->
					controls += NativeShortNode(
						packageName = match.packageName?.toString(),
						resourceId = match.viewIdResourceName,
						text = match.text?.toString(),
						contentDescription = match.contentDescription?.toString(),
						className = match.className?.toString(),
						visible = match.isVisibleToUser,
					)
				}
			} finally {
				matches.forEach(AccessibilityNodeInfo::recycle)
			}
		}
		val capture = NativeWatchAdCapture(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			watchPlayerVisible = false,
			otherPlayerSurfaceVisible = false,
			adControlNodes = controls,
		)
		if (NativeWatchAdParser.parse(capture).surface == PlayerAdSurface.VISIBLE) return capture
		if (!visible(NativeWatchAdParser.WATCH_PLAYER_VIEW_ID)) return capture
		return capture.copy(
			watchPlayerVisible = true,
			otherPlayerSurfaceVisible = NativeWatchAdParser.OTHER_PLAYER_SURFACE_VIEW_IDS.any(::visible),
		)
	}

	private fun nativePlaylistSourceSession() = SourceSessionId(
		YouTubeProbe.YOUTUBE_PACKAGE,
		NativeSourceSwitches.epochFor(YouTubeProbe.YOUTUBE_PACKAGE),
	)

	private fun nativePlaylistCurrent(): NativePlaylistLatch.Held? =
		ProbeHolder.current?.evidenceCoordinatorForProducers()
			?.nativePlaylist(nativePlaylistSourceSession())

	private fun observeNativePlaylist(result: NativePlaylistParser.Result, nowMillis: Long) {
		ProbeHolder.current?.evidenceCoordinatorForProducers()
			?.observeNativePlaylist(nativePlaylistSourceSession(), result, nowMillis)
	}

	private fun clearNativePlaylist() {
		ProbeHolder.current?.evidenceCoordinatorForProducers()
			?.clearNativePlaylist(nativePlaylistSourceSession())
	}

	/** Debug-only counts from the same root the shipped observer is about to parse. */
	private fun reportShortSurface(root: AccessibilityNodeInfo, observedAtMillis: Long) {
		if (!BuildConfig.DEBUG) return
		fun count(viewId: String): Int = runCatching {
			root.findAccessibilityNodeInfosByViewId(viewId)
		}.getOrDefault(emptyList()).let { matches ->
			matches.size.also { matches.forEach(AccessibilityNodeInfo::recycle) }
		}
		val roots = count(ID_PREFIX + "reel_watch_fragment_root")
		val players = count(ID_PREFIX + "reel_watch_player")
		val progress = count(ID_PREFIX + "reel_time_bar")
		val minimized = count(ID_PREFIX + "modern_miniplayer") +
			count(ID_PREFIX + "floaty_bar_controls_view")
		Log.i(
			Phase3Telemetry.TAG,
			Phase3Telemetry.shortSurfaceLine(
				observedAtMillis,
				roots,
				players,
				progress,
				minimized,
			),
		)
	}

	private fun captureTargeted(root: AccessibilityNodeInfo): NativeShortTree? {
		if (!root.isVisibleToUser || root.packageName?.toString() != YouTubeProbe.YOUTUBE_PACKAGE) {
			return null
		}
		val budget = CaptureBudget()
		val children = mutableListOf<NativeShortNode>()
		TARGET_IDS.forEach { viewId ->
			val matches = runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }
				.getOrDefault(emptyList())
			if (matches.size > MAX_MATCHES_PER_ID) budget.exceeded = true
			matches.take(MAX_MATCHES_PER_ID).forEach { match ->
				try {
					captureNode(match, depth = 0, budget = budget)?.let(children::add)
				} finally {
					match.recycle()
				}
			}
			// Nodes outside the bounded retained prefix still belong to this call.
			matches.drop(MAX_MATCHES_PER_ID).forEach(AccessibilityNodeInfo::recycle)
		}
		return NativeShortTree(
			root = NativeShortNode(
				packageName = YouTubeProbe.YOUTUBE_PACKAGE,
				children = children,
			),
			exceededCaptureBudget = budget.exceeded,
		)
	}

	/**
	 * The playlist bar, captured as one container per anchor.
	 *
	 * The anchor is `yt:position`, which is present in both measured layouts —
	 * the collapsed bar under the player and the expanded queue-panel header.
	 * Its *parent* is captured rather than the node itself, because the name and
	 * owner are siblings. Capturing per-container is what stops a queue row's
	 * `yt:title` from being read as the playlist name.
	 */
	private fun capturePlaylist(root: AccessibilityNodeInfo): NativePlaylistCapture {
		val empty = NativePlaylistCapture(
			packageName = null,
			watchScreenPresent = false,
			containers = emptyList(),
		)
		if (!root.isVisibleToUser || root.packageName?.toString() != YouTubeProbe.YOUTUBE_PACKAGE) {
			return empty
		}
		// Absent in the miniplayer, present on every measured watch screen.
		val watchScreenPresent = runCatching {
			root.findAccessibilityNodeInfosByViewId(WATCH_SCREEN_ID)
		}.getOrDefault(emptyList()).let { matches ->
			val present = matches.isNotEmpty()
			matches.forEach(AccessibilityNodeInfo::recycle)
			present
		}
		val budget = CaptureBudget(remaining = MAX_PLAYLIST_NODES)
		val containers = mutableListOf<NativeShortNode>()
		PLAYLIST_ANCHOR_IDS.forEach { viewId ->
			val matches = runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }
				.getOrDefault(emptyList())
			matches.take(MAX_PLAYLIST_ANCHORS).forEach { match ->
				try {
					val parent = runCatching { match.parent }.getOrNull()
					try {
						val container = parent ?: match
						captureNode(container, depth = 0, budget = budget)?.let(containers::add)
					} finally {
						parent?.recycle()
					}
				} finally {
					match.recycle()
				}
			}
			matches.drop(MAX_PLAYLIST_ANCHORS).forEach(AccessibilityNodeInfo::recycle)
		}
		return NativePlaylistCapture(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			watchScreenPresent = watchScreenPresent,
			containers = containers,
			exceededCaptureBudget = budget.exceeded,
		)
	}

	private fun captureNode(
		node: AccessibilityNodeInfo,
		depth: Int,
		budget: CaptureBudget,
	): NativeShortNode? {
		if (depth > MAX_DEPTH || budget.remaining <= 0) {
			budget.exceeded = true
			return null
		}
		budget.remaining--
		val children = mutableListOf<NativeShortNode>()
		for (index in 0 until node.childCount) {
			if (budget.exceeded || budget.remaining <= 0) {
				budget.exceeded = true
				break
			}
			val child = node.getChild(index) ?: continue
			try {
				captureNode(child, depth + 1, budget)?.let(children::add)
			} finally {
				child.recycle()
			}
		}
		// Geometry is what lets the parser pick the title by *where it is* rather
		// than by being the last survivor of a blocklist. See
		// NativeShortParser.titleCandidate.
		val bounds = android.graphics.Rect()
		runCatching { node.getBoundsInScreen(bounds) }
		return NativeShortNode(
			packageName = node.packageName?.toString(),
			resourceId = node.viewIdResourceName,
			text = node.text?.toString(),
			contentDescription = node.contentDescription?.toString(),
			className = node.className?.toString(),
			visible = node.isVisibleToUser,
			clickable = node.isClickable,
			left = bounds.left,
			top = bounds.top,
			right = bounds.right,
			bottom = bounds.bottom,
			children = children,
		)
	}

	companion object {
		private const val MAX_DEPTH = 24
		private const val MAX_NODES = 500
		private const val MAX_MATCHES_PER_ID = 4
		private const val REFRESH_INTERVAL_MS = 1_000L
		private const val ACQUISITION_REFRESH_MS = 4_000L

		/** Slow enough to be free, fast enough to catch the bar between tracks. */
		private const val PLAYLIST_POLL_INTERVAL_MS = 5_000L
		private const val PROOF_FRESHNESS_MS = 2_500L
		private const val MAX_CAPTURE_AGE_MS = 2_000L
		private const val ID_PREFIX = "com.google.android.youtube:id/"
		private val TARGET_IDS = listOf(
			ID_PREFIX + "reel_watch_fragment_root",
			ID_PREFIX + "reel_time_bar",
			ID_PREFIX + "comments_panel",
			ID_PREFIX + "comment_sheet",
			ID_PREFIX + "engagement_panel",
		)

		/**
		 * Anchors for the ordinary watch screen's playlist bar. `position` is
		 * present in both measured layouts; `playlist_name` only in the
		 * collapsed one and is kept as a second anchor so the bar is still found
		 * if the position node is ever restructured.
		 */
		private val PLAYLIST_ANCHOR_IDS = listOf(
			ID_PREFIX + "position",
			ID_PREFIX + "playlist_name",
		)
		private const val MAX_PLAYLIST_ANCHORS = 4
		private const val MAX_PLAYLIST_NODES = 150

		private const val WATCH_SCREEN_ID = ID_PREFIX + "watch_panel"

		private data class CaptureBudget(var remaining: Int = MAX_NODES, var exceeded: Boolean = false)

		fun isEnabled(context: Context): Boolean {

			if (Settings.Secure.getInt(context.contentResolver, ACCESSIBILITY_ENABLED, 0) != 1) {
				return false
			}
			val enabled = Settings.Secure.getString(
				context.contentResolver,
				Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
			).orEmpty()
			val target = "${context.packageName}/${NativeShortsAccessibilityService::class.java.name}"
			return enabled.split(':').any { it.equals(target, ignoreCase = true) }
		}

		/** `Settings.Secure.ACCESSIBILITY_ENABLED`, which has no public constant. */
		private const val ACCESSIBILITY_ENABLED = "accessibility_enabled"
	}
}
