package com.rustedwax.app.detect

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.rustedwax.core.SourceSessionId
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.app.scrobble.FinalizationTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Notification listener — and, since Phase 3, the host for detection itself.
 *
 * Three jobs:
 *
 *  1. Hold the Notification Access grant. [android.media.session.MediaSessionManager.getActiveSessions]
 *     requires the caller to name an *enabled* listener component; that grant is
 *     Android's gate on reading other apps' media sessions.
 *
 *  2. Harvest the page origin from browser media notifications. Phase 0 found
 *     Chromium publishes no URI metadata on the media session (<redacted-private-path>, Q3),
 *     so the notification's sub-text — which Chromium sets to the origin, e.g.
 *     "youtube.com" — is our remaining way to know which site is playing.
 *
	 *  3. **Run the probe and the finalization runtime.** The plan called for a
 *     foreground service, but that turned out to be the wrong tool: a
 *     NotificationListenerService is already bound and kept alive by the system
 *     for as long as the grant is held. Hosting detection here means no
 *     persistent notification, no `foregroundServiceType` wrangling, and none
 *     of Android 15's data-sync runtime caps — and it's how established
 *     scrobblers do it. The trade is that lifetime is the system's call, so
 *     [onListenerConnected] must be able to (re)build everything from scratch.
 *
 * Scope discipline: notification contents remain limited to target browsers.
 * Native opt-ins read MediaSession metadata/state through Notification Access,
 * but never inspect the native apps' notification contents.
 */
class RustedWaxListenerService : NotificationListenerService() {

	private var probe: SessionProbe? = null
	private var evidenceCoordinator: EvidenceCoordinator? = null

	/** Lives exactly as long as the listener binding does. */
	private var scope: CoroutineScope? = null

	override fun onListenerConnected() {
		Log.i(TAG, "Notification listener connected — media sessions readable")
		EventLog.init(applicationContext)
		EventLog.append("listener", "connected")

		MonitorSwitch.init(applicationContext)
		NativeSourceSwitches.init(applicationContext)
		FinalizationRuntime.init(applicationContext)

		// A reconnect without an intervening disconnect is allowed, and the probe
		// it left behind holds dead controllers. Drop it before rebuilding —
		// finalizing, because this is the system recycling us mid-playback, not
		// the user asking us to stop.
		NativeSourceSwitches.invalidateAll("listener connected/rebuilt")
		scope?.cancel()
		probe?.stop(finalizeTracks = true)
		evidenceCoordinator?.stop()
		evidenceCoordinator = null
		probe = null
		ProbeHolder.set(null)

		// NotificationListenerService does not promise to call
		// onNotificationPosted again for notifications that were already active
		// when this process/binding came back. Rebuild the browser-origin evidence
		// before SessionProbe enumerates media sessions, otherwise a Brave track
		// that stayed playing through our restart is incorrectly treated as an
		// unproven source. Clear first so a listener reconnect cannot retain a
		// notification that is no longer active. The replay uses the exact same
		// target-package and media-style privacy filters as the live callback.
		// The probe follows the switch rather than the binding: the system can
		// reconnect this service at any time, and it must not resurrect
		// monitoring the user turned off. Collecting a StateFlow delivers the
		// current value immediately, so this also handles the initial state.
		scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
			s.launch {
				MonitorSwitch.enabled.collect { on ->
					if (on) startProbe() else stopProbe()
				}
			}
			s.launch {
				NativeSourceSwitches.config.collect {
					probe?.refreshTargets()
				}
			}
		}

		// Anything stranded by an earlier offline spell gets another go. Runs
		// even while stopped: those scrobbles were earned before Stop, and
		// holding them hostage only drifts their timestamps further.
		FinalizationRuntime.flushQueue()
	}

	override fun onListenerDisconnected() {
		Log.w(TAG, "Notification listener disconnected")
		EventLog.append("listener", "disconnected")
		NativeSourceSwitches.invalidateAll("listener disconnected")
		scope?.cancel()
		scope = null
		// System teardown, not a user Stop — a track in flight really is ending,
		// so it gets its last chance to score.
		probe?.stop(finalizeTracks = true)
		evidenceCoordinator?.stop()
		evidenceCoordinator = null
		probe = null
		ProbeHolder.set(null)
	}

	/**
	 * Rebuilt from scratch every time: the system can tear this service down
	 * and bring it back at will, and a stale probe would hold dead controllers.
	 */
	private fun startProbe() {
		if (probe != null) return
		val coordinator = EvidenceCoordinator().also { it.start() }
		evidenceCoordinator = coordinator
		// Start is also a rebuild boundary. A notification that was already active
		// while monitoring was off will not necessarily be posted again.
		replayActiveBrowserNotifications()
		// A new/rebuilt monitor is a new recovery run. Cached identities never
		// cross this boundary even though the singleton process may survive it.
		FinalizationRuntime.clearVerifiedIdentityCandidates()
		probe = SessionProbe(
			applicationContext,
			evidenceCoordinator = coordinator,
			finalizedPlaybackTombstones =
				SharedPreferencesFinalizedPlaybackTombstones(applicationContext),
			automaticWriteAuthorization =
				FinalizationRuntime::automaticWriteAuthorization,
		).also { p ->
			p.onTrackFinalized = { snapshot ->
				if (FinalizationRuntime.isReady) {
					FinalizationRuntime.finalizeTrack.execute(
						FinalizedTrack.from(snapshot),
						FinalizationTrigger.AUTOMATIC,
					)
				}
			}
			p.shouldFinalizeContinuationPromptly =
				FinalizationRuntime::shouldFinalizeContinuationPromptly
			p.onVideoConfirmed = { videoId, completion ->
				FinalizationRuntime.prefetch(videoId, completion)
			}
			p.knownVideoFor = { videoId ->
				FinalizationRuntime.knownVideo(videoId)
			}
			p.onPackageTornDown = FinalizationRuntime::clearVerifiedIdentityCandidates
			p.onNativeIdentityRequested = FinalizationRuntime::resolveNativeCarryIdentity
			p.start()
			ProbeHolder.set(p)
		}
	}

	/** The user pressed Stop. Nothing in flight is scrobbled on the way out. */
	private fun stopProbe() {
		val wasRunning = probe != null
		NativeSourceSwitches.invalidateAll("monitoring Stop/reset")
		probe?.stop(finalizeTracks = false)
		evidenceCoordinator?.stop()
		evidenceCoordinator = null
		probe = null
		ProbeHolder.set(null)
		// The coordinator owns and drops all run-scoped evidence on stop. Progress
		// parked outside it is cleared explicitly because a user Stop must not let
		// an old listen seed the next monitoring run.
		TrackProgressCarry.clear()
		FinalizationRuntime.clearVerifiedIdentityCandidates()
		EventLog.append(
			"monitor",
			if (wasRunning) {
				"monitoring stopped — nothing is being read"
			} else {
				// Reached on every reconnect while stopped. Worth a line: it's
				// the answer to "why is the app not seeing anything".
				"monitoring is off — probe not started"
			},
		)
	}

	override fun onNotificationPosted(sbn: StatusBarNotification?) {
		if (!MonitorSwitch.isEnabled) return
		recordBrowserNotification(sbn)
	}

	private fun replayActiveBrowserNotifications() {
		val notifications = try {
			activeNotifications
		} catch (error: SecurityException) {
			Log.w(TAG, "Could not replay active browser notifications", error)
			emptyArray()
		}
		notifications?.forEach(::recordBrowserNotification)
	}

	private fun recordBrowserNotification(sbn: StatusBarNotification?) {
		val pkg = sbn?.packageName ?: return
		if (pkg !in YouTubeProbe.TARGET_PACKAGES) return

		val extras = sbn.notification?.extras ?: return
		// Only a media-style notification describes playback. Anything else
		// from the browser (downloads, tab reminders) is none of our business.
		if (!extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return

		val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
		val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
		val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

		val hint = NotificationHints.Hint(
			host = hostOf(subText) ?: hostOf(text),
			subText = subText,
			title = title,
			text = text,
		)
		evidenceCoordinator?.putNotification(SourceSessionId(pkg, null), hint)
		// §4.1. The hint is still *kept* — it is how a browser session is proven
		// to be YouTube at all — but it is only ever *written down* once it says
		// YouTube. Measured 2026-08-10: playing an unrelated video in Chrome put
		// `host=w3schools.com … title="w3schools.com/html/mov_bbb.mp4"` in the
		// exportable log, which is the page title leak this section removes,
		// arriving by a second route after the first was closed.
		if (YouTubeAdDetector.shouldScanHost(hint.host)) {
			EventLog.append("notification", "$pkg → ${hint.describe()}")
		}
	}

	override fun onNotificationRemoved(sbn: StatusBarNotification?) {
		// Removal callbacks carry notification extras too. Stopped means returning
		// before even reading the package or title, just like the posted path.
		if (!MonitorSwitch.isEnabled) return
		val pkg = sbn?.packageName ?: return
		if (pkg !in YouTubeProbe.TARGET_PACKAGES) return
		// Remove only the hint this notification produced. Clearing the whole
		// package here used to erase a second tab's evidence along with it, so
		// closing one tab silently killed the other tab's scrobble.
		val title = sbn.notification?.extras
			?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
		evidenceCoordinator?.removeNotification(SourceSessionId(pkg, null), title)
	}

	/**
	 * Pull a hostname out of whatever Chromium put in the field. It may be a
	 * bare origin ("youtube.com"), a full URL, or free text — accept the first
	 * two, reject the rest rather than guessing.
	 */
	private fun hostOf(value: String?): String? {
		val v = value?.trim().orEmpty()
		if (v.isEmpty()) return null
		HOST.find(v)?.let { return it.groupValues[1].removePrefix("www.").lowercase() }
		return null
	}

	companion object {
		private const val TAG = "RustedWaxListener"

		/** Matches a bare domain or the host part of a URL. */
		private val HOST =
			Regex("""(?:https?://)?((?:[a-z0-9-]+\.)+[a-z]{2,})""", RegexOption.IGNORE_CASE)
	}
}
