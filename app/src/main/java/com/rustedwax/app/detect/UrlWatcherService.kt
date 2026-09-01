package com.rustedwax.app.detect

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.rustedwax.core.SourceSessionId

/**
 * Reads the browser's address bar and explicit visible YouTube ad labels.
 *
 * Optional and off by default (decision D5). With it off nothing here runs and
 * identity falls back to notification hints, exactly as in Phase 3.
 *
 * ## Why an accessibility service
 *
 * Chromium tells the media session nothing about the page (PHASE0 Q3) and the
 * media notification only carries the origin. The address bar is the one place
 * on the device where the actual URL is legible. That buys two things nothing
 * else can:
 *
 *  - **Exact exclusivity.** "This tab is youtube.com" instead of "a
 *    notification from this browser said youtube.com".
 *  - **The video id**, which is the sole route to `url` in the payload and the
 *    precondition for enrichment.
 *
 * ## Scope
 *
 * `accessibility_service_config.xml` pins `packageNames` to the browsers in
 * [YouTubeProbe.TARGET_PACKAGES]. That is enforced by the OS, not by this
 * class: events from any other app are never delivered here at all. The class
	 * additionally re-checks the package. It reads the URL bar for identity and,
	 * whenever the visible host is YouTube, scans visible accessibility labels
	 * for exact ad UI such as "Sponsored" or "Skip ad". Shorts keep their exact
	 * id/generation path; ordinary playback is offered without a video id to the
	 * unique active MediaSession track.
 *
 * ## Known limits
 *
 * The address bar reflects the **foreground tab**, and Chromium on Android
 * often shows only the host rather than the full path. So this may yield a host
 * with no video id, which is still an upgrade on a notification hint. What it
 * actually returns on Brave is logged verbatim on first sight, because it's a
 * measurement this project hasn't made yet — see <redacted-private-path> Q7.
 */
class UrlWatcherService : AccessibilityService() {
	private val refreshHandler = Handler(Looper.getMainLooper())
	/**
	 * Stopped means stopped, and that now includes the YouTube master switch.
	 *
	 * This service exists only to prove *which YouTube video* is on screen, so
	 * with YouTube scrobbling off there is nothing here worth reading — and
	 * scanning a browser's address bar for evidence nobody will use is exactly
	 * the cost the switch is meant to remove.
	 */
	private fun watching(): Boolean =
		MonitorSwitch.isEnabled && NativeSourceSwitches.youTubeScrobblingEnabled

	private val refresh = object : Runnable {
		override fun run() {
			if (watching() && !RustedWaxUiVisibility.isResumed) {
				val successfulPackage = observeVisibleRoot(expectedPackage = null)
				val transitions = ProbeHolder.current?.evidenceCoordinatorForProducers()
					?.noteRefreshResult(successfulPackage).orEmpty()
				logFreshnessTransitions(
					transitions,
				)
			}
			refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
		}
	}

	override fun onServiceConnected() {
		// The system can bind this before the activity or the listener has run,
		// and the master switch has to be loaded before the first scan rather
		// than defaulting to on for whatever the first second brings.
		MonitorSwitch.init(applicationContext)
		NativeSourceSwitches.init(applicationContext)
		UrlEvidence.setConnected(true)
		EventLog.append("url", "address-bar watcher connected")
		refreshHandler.removeCallbacks(refresh)
		refreshHandler.postDelayed(refresh, REFRESH_INTERVAL_MS)
	}

	override fun onDestroy() {
		refreshHandler.removeCallbacks(refresh)
		super.onDestroy()
		UrlEvidence.setConnected(false)
		val coordinator = ProbeHolder.current?.evidenceCoordinatorForProducers()
		YouTubeProbe.TARGET_PACKAGES.forEach { packageName ->
			coordinator?.clearUrl(SourceSessionId(packageName, null))
		}
		EventLog.append("url", "address-bar watcher stopped")
	}

	override fun onInterrupt() = Unit

	override fun onAccessibilityEvent(event: AccessibilityEvent?) {
		// Same discipline as the notification listener: stopped means stopped.
		if (!watching() || RustedWaxUiVisibility.isResumed) return
		val pkg = event?.packageName?.toString() ?: return
		if (pkg !in YouTubeProbe.TARGET_PACKAGES) return
		val successfulPackage = observeVisibleRoot(expectedPackage = pkg)
		val transitions = ProbeHolder.current?.evidenceCoordinatorForProducers()
			?.noteRefreshResult(successfulPackage).orEmpty()
		logFreshnessTransitions(
			transitions,
		)
	}

	/** Shared bounded root observation for callbacks and the periodic refresh. */
	private fun observeVisibleRoot(expectedPackage: String?): String? {
		if (!watching() || RustedWaxUiVisibility.isResumed) return null
		val root = rootInActiveWindow ?: return null
		val observation = try {
			if (!root.isVisibleToUser) return null
			val rootPackage = root.packageName?.toString() ?: return null
			if (rootPackage !in YouTubeProbe.TARGET_PACKAGES ||
				(expectedPackage != null && expectedPackage != rootPackage)
			) return null
			val raw = readUrlBar(root, rootPackage)
			val host = raw?.let(::hostOf)
			if (!YouTubeAdDetector.shouldScanHost(host)) return null
			val parsedVideoId = raw?.let { VIDEO_ID.find(it)?.groupValues?.get(1) }
			val isShort = raw?.contains("/shorts/", ignoreCase = true) == true
			val adVideoId = YouTubeAdDetector.videoIdInSameShortSnapshot(host, raw)
			Observation(
				packageName = rootPackage,
				raw = raw,
				host = host,
				videoId = parsedVideoId,
				isShort = isShort,
				playlistId = raw?.let { PLAYLIST_ID.find(it)?.groupValues?.get(1) },
				shortAdVideoId = adVideoId,
				// Scan all visible YouTube hosts. Binding is deliberately deferred:
				// Shorts use shortAdVideoId, while ordinary playback is routed to
				// SessionProbe without the address-bar id.
				adSignal = if (YouTubeAdDetector.shouldScanHost(host)) {
					findAdSignal(root, depth = 0, budget = ScanBudget())
				} else {
					null
				},
			)
		} finally {
			root.recycle()
		}
		val raw = observation.raw?.takeIf { it.isNotBlank() } ?: return null

		val observedAt = System.currentTimeMillis()
		val coordinator = ProbeHolder.current?.evidenceCoordinatorForProducers() ?: return null
		val sourceSession = SourceSessionId(observation.packageName, null)
		val storedUrl = coordinator.putUrl(
			sourceSession,
			UrlEvidence.Evidence(
				host = observation.host,
				videoId = observation.videoId,
				isShort = observation.isShort,
				playlistId = observation.playlistId,
				raw = raw,
				atMillis = observedAt,
			),
		) ?: return null
		coordinator.emit(
			EvidenceCoordinator.Event.ScreenScanned(
				sourceSession = sourceSession,
				scan = MediaSessionAccessibilityEvidence.Scan(
				packageName = observation.packageName,
				host = observation.host,
				rootVisible = true,
				urlGeneration = storedUrl.generation,
				videoId = observation.videoId,
				isShort = observation.isShort,
				adSignal = observation.adSignal,
				atMillis = observedAt,
				),
			),
		)
		if (observation.adSignal != null && observation.shortAdVideoId != null &&
			observation.shortAdVideoId == storedUrl.videoId
		) {
			// A concrete Short remains exclusively on the existing id/generation
			// store. It is not ordinary watch-session evidence.
			coordinator.observeVisibleAd(
				sourceSession,
				AdEvidence.Evidence(
					packageName = observation.packageName,
					videoId = observation.shortAdVideoId,
					signal = observation.adSignal,
					urlGeneration = storedUrl.generation,
					atMillis = observedAt,
				),
			)
		} else {
			coordinator.visibleAdLabelAbsent(
				sourceSession, storedUrl.videoId, storedUrl.generation,
			)
		}
		return observation.packageName
	}

	private fun logFreshnessTransitions(
		transitions: List<MediaSessionAccessibilityEvidence.FreshnessTransition>,
	) {
		transitions.forEach { transition ->
			when (transition.freshness) {
				MediaSessionAccessibilityEvidence.Freshness.OUTAGE -> EventLog.append(
					"evidence",
					"${transition.packageName} → browser evidence outage: a target " +
						"MediaSession continues without successful visible YouTube-root scans; " +
						"the watcher remains connected and bounded retries continue",
				)
				MediaSessionAccessibilityEvidence.Freshness.RECOVERED -> EventLog.append(
					"evidence",
					"${transition.packageName} → browser evidence scans recovered",
				)
			}
		}
	}

	/**
	 * The omnibox, by view id — stable across Chromium forks because Brave
	 * inherits Chrome's layout. Falls back to the first focusable EditText,
	 * which is what the omnibox is when a fork has renamed it.
	 */
	private fun readUrlBar(root: AccessibilityNodeInfo, pkg: String): String? {
		root.findAccessibilityNodeInfosByViewId("$pkg:id/url_bar")?.let { nodes ->
			var text: String? = null
			nodes.forEach { node ->
				if (text.isNullOrBlank()) text = node.text?.toString()
				node.recycle()
			}
			if (!text.isNullOrBlank()) return text
		}
		return firstEditableText(root, depth = 0)
	}

	private fun firstEditableText(node: AccessibilityNodeInfo?, depth: Int): String? {
		if (node == null || depth > MAX_DEPTH) return null
		if (node.className == "android.widget.EditText") {
			node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
		}
		for (i in 0 until node.childCount) {
			val child = node.getChild(i) ?: continue
			val found = try {
				firstEditableText(child, depth + 1)
			} finally {
				child.recycle()
			}
			if (found != null) return found
		}
		return null
	}

	/**
	 * Search visible browser-page accessibility labels for YouTube's own ad UI.
	 *
	 * Both depth and node count are bounded because a long Shorts feed can
	 * expose a large virtual tree. [YouTubeAdDetector] performs the strict text
	 * matching; this method only walks and recycles nodes.
	 */
	private fun findAdSignal(
		node: AccessibilityNodeInfo?,
		depth: Int,
		budget: ScanBudget,
	): String? {
		if (node == null || depth > AD_SCAN_MAX_DEPTH || budget.remaining-- <= 0) return null
		if (node.isVisibleToUser) {
			YouTubeAdDetector.signalFor(node.text)?.let { return it }
			YouTubeAdDetector.signalFor(node.contentDescription)?.let { return it }
		}
		for (i in 0 until node.childCount) {
			val child = node.getChild(i) ?: continue
			val found = try {
				findAdSignal(child, depth + 1, budget)
			} finally {
				child.recycle()
			}
			if (found != null) return found
		}
		return null
	}

	/**
	 * The bar may hold a full URL, a bare host, or a search query. Accept the
	 * first two shapes, reject the rest — same fail-closed rule the rest of
	 * detection follows.
	 */
	private fun hostOf(value: String): String? {
		val v = value.trim()
		if (v.isEmpty() || v.contains(' ')) return null
		return HOST.find(v)?.groupValues?.get(1)?.removePrefix("www.")?.lowercase()
	}

	companion object {
		private const val MAX_DEPTH = 12
		private const val AD_SCAN_MAX_DEPTH = 24
		private const val AD_SCAN_MAX_NODES = 500
		private const val REFRESH_INTERVAL_MS = 5_000L

		private data class Observation(
			val packageName: String,
			val raw: String?,
			val host: String?,
			val videoId: String?,
			val isShort: Boolean,
			val playlistId: String?,
			val shortAdVideoId: String?,
			val adSignal: String?,
		)

		private data class ScanBudget(var remaining: Int = AD_SCAN_MAX_NODES)

		private val VIDEO_ID =
			Regex("""(?:[?&]v=|youtu\.be/|/shorts/)([A-Za-z0-9_-]{11})""")

		/** `list=` — the playlist is what still identifies tracks once the bar goes quiet. */
		private val PLAYLIST_ID = Regex("""[?&]list=([A-Za-z0-9_-]{2,})""")

		private val HOST =
			Regex("""(?:https?://)?((?:[a-z0-9-]+\.)+[a-z]{2,})""", RegexOption.IGNORE_CASE)

		/** Whether the user has enabled this service in system settings. */
		fun isEnabled(context: Context): Boolean {
			// Both halves, matching NativeShortsAccessibilityService.isEnabled.
			// The list alone keeps naming a service Android has stopped sending
			// events to, so it reports "On" for a watcher that is reading
			// nothing. This is the §2.3 defect, left alone at the time because
			// the file was fenced; the owner signed it off on 2026-08-05 after a
			// day in which the service had been dropped and nothing said so.
			if (Settings.Secure.getInt(context.contentResolver, ACCESSIBILITY_ENABLED, 0) != 1) {
				return false
			}
			val enabled = Settings.Secure.getString(
				context.contentResolver,
				Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
			).orEmpty()
			val target = "${context.packageName}/${UrlWatcherService::class.java.name}"
			return enabled.split(':').any { it.equals(target, ignoreCase = true) }
		}

		/** `Settings.Secure.ACCESSIBILITY_ENABLED`, which has no public constant. */
		private const val ACCESSIBILITY_ENABLED = "accessibility_enabled"
	}
}
