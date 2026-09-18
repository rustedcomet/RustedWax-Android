package com.rustedwax.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rustedwax.hive.KeyValidator
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.app.detect.AccessibilityGrantHealth
import com.rustedwax.app.detect.GrantHealth
import com.rustedwax.app.detect.MonitorSwitch
import com.rustedwax.app.detect.NativeShortsAccessibilityService
import com.rustedwax.app.detect.NativeShortsObserver
import com.rustedwax.app.detect.NativeSourceSwitches
import com.rustedwax.app.detect.PipPlaybackProbe
import com.rustedwax.app.detect.ProbeHolder
import com.rustedwax.app.detect.RustedWaxUiVisibility
import com.rustedwax.app.detect.SessionProbe
import com.rustedwax.app.detect.UrlWatcherService
import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.storage.KeyVault
import com.rustedwax.app.storage.Settings
import com.rustedwax.app.ui.AppTheme
import com.rustedwax.app.ui.LOADING_MINIMUM_MILLIS
import com.rustedwax.app.ui.LoadingScreen
import com.rustedwax.app.ui.MainScreen
import com.rustedwax.app.ui.RustedWaxWindow
import com.rustedwax.app.ui.ThemeChoice
import com.rustedwax.app.ui.snaps.SharedPreferencesSnapDraftStore
import com.rustedwax.app.snaps.HivePostedSnapReader
import com.rustedwax.app.snaps.HiveSnapPort
import com.rustedwax.app.snaps.PostedSnaps
import com.rustedwax.app.snaps.SharedPreferencesPendingSnapStore
import com.rustedwax.app.snaps.SnapPublisher
import com.rustedwax.app.ui.snaps.SnapComposerState
import com.rustedwax.app.ui.snaps.SnapPostController
import com.rustedwax.app.ui.Thumbnails
import com.rustedwax.app.ui.YouTubeSignInActivity

/**
 * The UI. Detection and scrobbling live in
 * [com.rustedwax.app.detect.RustedWaxListenerService] so they
 * keep running with this activity closed — this class only observes and
 * configures.
 */
class MainActivity : ComponentActivity() {

	private lateinit var vault: KeyVault
	private lateinit var settings: Settings
	private val validator = KeyValidator()

	override fun onResume() {
		super.onResume()
		RustedWaxUiVisibility.resumed()
	}

	override fun onPause() {
		// Publish before the next app can become active. Accessibility capture may
		// resume as soon as Android starts delivering that app's window events.
		RustedWaxUiVisibility.paused()
		super.onPause()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// Two cheap reads, deliberately kept on this thread. The log's policy
		// has to be in force before anything can write a line, and the theme has
		// to be known before the first frame or the app flashes the wrong one.
		settings = Settings(this)
		EventLog.init(this)

		setContent {
			// An unknown stored value follows the system rather than refusing
			// to draw: this is the app's appearance, not one of its rules.
			var themeChoice by remember { mutableStateOf(AppTheme.choice(settings.themeChoice)) }
			// The same wrapper the sign-in screen uses: one appearance for every
			// window the app owns, and the window and system bars painted to
			// match it rather than left as a white frame around a black app.
			RustedWaxWindow(window, themeChoice) {
				// The rest of startup opens the key vault, prunes the dedup
				// ledger and counts the send queue — files, on a phone, on a
				// cold start. It used to run here on the main thread with the
				// window empty behind it. Now it runs off it, and the record
				// turns until it is done. Nothing waits on the animation: the
				// screen is swapped the instant the work returns, so a warm
				// start may never show it at all.
				var startup by remember { mutableStateOf<KeyVault.Account?>(null) }
				var ready by remember { mutableStateOf(false) }
				LaunchedEffect(Unit) {
					val startedAt = System.currentTimeMillis()
					startup = withContext(Dispatchers.IO) { warmUp() }
					// Logged before any hold, because it is the only number that
					// says whether the loading screen is doing a job or standing
					// in the way. If this is ever large, the fix is the work
					// behind it.
					val elapsed = System.currentTimeMillis() - startedAt
					EventLog.append("app", "ready in ${elapsed}ms")
					// Zero unless someone is deliberately looking at the record
					// turn. See [LOADING_MINIMUM_MILLIS] — it is a delay to
					// startup and is not pretending otherwise.
					delay((LOADING_MINIMUM_MILLIS - elapsed).coerceAtLeast(0))
					ready = true
				}
				if (!ready) {
					LoadingScreen()
					return@RustedWaxWindow
				}
				Wired(startup, themeChoice) { themeChoice = it }
			}
		}
	}

	/**
	 * Everything that touches a file, done once, off the main thread.
	 *
	 * Returns the stored account rather than leaving the caller to read it: the
	 * read is the expensive half — it unlocks `EncryptedSharedPreferences`
	 * against the Android Keystore — and doing it here is the difference between
	 * warming the vault up and merely constructing it.
	 */
	private fun warmUp(): KeyVault.Account? {
		MonitorSwitch.init(this)
		NativeSourceSwitches.init(this)
		FinalizationRuntime.init(this)
		Thumbnails.init(this)
		vault = KeyVault(this)
		return vault.account
	}

	/**
	 * The app proper, once [warmUp] has finished.
	 *
	 * Its own function rather than more nesting inside `setContent`: [vault] is
	 * assigned by the warm-up, so everything that reads it has to be somewhere
	 * that cannot be composed before then.
	 */
	@Composable
	private fun Wired(
		startupAccount: KeyVault.Account?,
		themeChoice: ThemeChoice,
		onThemeChoice: (ThemeChoice) -> Unit,
	) {
		val probe by ProbeHolder.probe.collectAsStateWithLifecycle()
		val monitoring by MonitorSwitch.enabled.collectAsStateWithLifecycle()
		val nativeSources by NativeSourceSwitches.config.collectAsStateWithLifecycle()
		// Keep the stable Compose State holders. Delegating these with `by` reads
		// their rapidly changing values in Wired itself, so every log append and
		// Shorts-status emission invalidates this whole parent before a deferred
		// lambda can confine the read to its destination.
		val nativeShortsStatus = NativeShortsObserver.status.collectAsStateWithLifecycle()
		val logLines = EventLog.lines.collectAsStateWithLifecycle()
		val recent by FinalizationRuntime.recent.collectAsStateWithLifecycle()
		val skipped by FinalizationRuntime.skipped.collectAsStateWithLifecycle()
		val quietBar by FinalizationRuntime.tracksWithoutVideoId.collectAsStateWithLifecycle()
		val queued by FinalizationRuntime.queueSize.collectAsStateWithLifecycle()

		// Position changes once a second. Hold the list behind stable State so only
		// the Now page reads it; the tab strip gets a separate count that changes
		// only when a session appears or disappears.
		val sessions = remember {
			mutableStateOf(emptyList<com.rustedwax.app.detect.SessionSnapshot>())
		}
		var sessionCount by remember { mutableStateOf(0) }
		var hasAccess by remember { mutableStateOf(SessionProbe.hasNotificationAccess(this)) }
		var urlWatcher by remember { mutableStateOf(UrlWatcherService.isEnabled(this)) }
		var nativeShortsGranted by remember {
			mutableStateOf(NativeShortsAccessibilityService.isEnabled(this))
		}

		var urlWatcherDropped by remember { mutableStateOf(false) }
		var nativeShortsDropped by remember { mutableStateOf(false) }
		// When each grant last stopped being live. §2.3: the master flag
		// reads 0 for a few seconds after an install and then returns on
		// its own, so a drop is only a drop once it has outlasted that.
		var urlWatcherNotLiveSince by remember { mutableStateOf(0L) }
		var nativeShortsNotLiveSince by remember { mutableStateOf(0L) }
		var eventLogging by remember { mutableStateOf(settings.eventLogging) }
		// Persisted, so a trip to YouTube and back does not re-lock the tier
		// somebody just tapped seven times to open.
		var developerMode by remember { mutableStateOf(settings.developerMode) }
		// Re-read on every *tick*, which is what the comment always claimed —
		// but it was in the composable body, so it ran on every **recomposition**
		// instead, allocating a probe and making an AppOps binder call each time.
		// While a Short is on screen the observer's status changes about once a
		// second and the log writes several lines a second, so this was running
		// far more often than once per tick and on the thread that draws.
		// Usage access is granted in another app, so it still has to be noticed;
		// the one-second poll below is where noticing belongs.
		val pipProbe = remember { PipPlaybackProbe(applicationContext) }
		var usageAccessGranted by remember { mutableStateOf(pipProbe.hasUsageAccess()) }
		// Read once per composition rather than held in a flow: mutes change
		// only by the button below, so recomposing on that is enough.
		var mutedIds by remember { mutableStateOf(FinalizationRuntime.mutedVideos().keys) }
		// Handed in by the warm-up, which already paid for the Keystore unlock.
		// Reading it again here would repeat that on the main thread.
		var account by remember { mutableStateOf(startupAccount) }
		// History Snap drafts and the one open composer. Held here rather than in
		// History itself, which is destroyed and rebuilt on every tab change, and
		// keyed by account so one Hive user never sees another's unsent text.
		val snaps = remember {
			SnapComposerState(
				store = SharedPreferencesSnapDraftStore(applicationContext),
				account = { account?.username },
			)
		}
		// Root Snap publication. Deliberately assembled here and nowhere near the
		// scrobble engine: this path shares the signing primitives and nothing
		// else, and in particular never touches the broadcast queue.
		val posts = remember {
			// One store, shared by the two halves that need it: the publisher,
			// which writes it, and the posted-Snap view, which only ever reads it.
			val pendingSnaps = SharedPreferencesPendingSnapStore(applicationContext)
			val publisher = SnapPublisher(
				// The key is read inside the port at signing time, so it is never
				// held by the publisher and an account switch changes it.
				hive = HiveSnapPort(loadKey = { vault.loadKey() }),
				store = pendingSnaps,
			)
			SnapPostController(
				scope = lifecycleScope,
				publisher = { publisher },
				// Read late, at the moment of acting, so switching accounts with a
				// composer open cannot act under the previous account's draft key.
				account = { account?.username },
				// What a confirmed card shows. Built from a store read and a chain
				// *read* — there is no key and no broadcaster behind it, so no
				// posted card can ever cause a second publication.
				postedSnaps = {
					PostedSnaps(store = pendingSnaps, reader = HivePostedSnapReader())
				},
			)
		}
		// Anything that was still in flight when the process last died gets
		// settled against the chain on the way in. This reconciles; it never sends.
		LaunchedEffect(account?.username) { posts.resumePending() }
		var autoScrobble by remember { mutableStateOf(settings.autoScrobble) }
		// Re-read on every poll: the session is written by the sign-in
		// activity, and the refusal by the resolver on a background
		// thread, so neither can be cached in composition.
		var youTubeAccount by remember { mutableStateOf(FinalizationRuntime.youTubeSession()) }
		var watchHistory by remember { mutableStateOf(FinalizationRuntime.watchHistoryEnabled()) }
		var watchHistoryRefusal by remember {
			mutableStateOf(FinalizationRuntime.watchHistoryRefusal())
		}
		// The typed cause beside the prose. The warning card chooses its
		// recovery action from this; the prose is only ever displayed.
		var watchHistoryRefusalKind by remember {
			mutableStateOf(FinalizationRuntime.watchHistoryRefusalKind())
		}
			var busy by remember { mutableStateOf(false) }
			var status by remember { mutableStateOf<String?>(null) }
			var statusIsError by remember { mutableStateOf(false) }
			var probeError by remember { mutableStateOf<String?>(null) }

			// Observe errors as a flow. Reading StateFlow.value directly in
			// composition does not subscribe and can leave stale UI.
			LaunchedEffect(probe) {
				probeError = null
				probe?.error?.collect { probeError = it }
			}

		// The probe belongs to the service; poll it for live position
		// and re-check the grant, which can be revoked from Settings.
		LaunchedEffect(probe) {
			while (true) {
				hasAccess = SessionProbe.hasNotificationAccess(this@MainActivity)
				// Both grants are revocable from system settings while
				// we're in the background, so neither is cached.
				urlWatcher = UrlWatcherService.isEnabled(this@MainActivity)
				nativeShortsGranted =
					NativeShortsAccessibilityService.isEnabled(this@MainActivity)
				val nowMillis = System.currentTimeMillis()
				val browserGrant = noteGrant(
					live = urlWatcher,
					everGranted = settings.browserEvidenceEverGranted,
					remember = { settings.browserEvidenceEverGranted = it },
					alreadyReported = urlWatcherDropped,
					notLiveSince = urlWatcherNotLiveSince,
					nowMillis = nowMillis,
					label = "Browser evidence access",
				)
				urlWatcherDropped = browserGrant.dropped
				urlWatcherNotLiveSince = browserGrant.notLiveSince
				val shortsGrant = noteGrant(
					live = nativeShortsGranted,
					everGranted = settings.nativeShortsEverGranted,
					remember = { settings.nativeShortsEverGranted = it },
					alreadyReported = nativeShortsDropped,
					notLiveSince = nativeShortsNotLiveSince,
					nowMillis = nowMillis,
					label = "Foreground Shorts evidence",
				)
				nativeShortsDropped = shortsGrant.dropped
				nativeShortsNotLiveSince = shortsGrant.notLiveSince
				usageAccessGranted = pipProbe.hasUsageAccess()
				youTubeAccount = FinalizationRuntime.youTubeSession()
				watchHistory = FinalizationRuntime.watchHistoryEnabled()
				watchHistoryRefusal = FinalizationRuntime.watchHistoryRefusal()
				watchHistoryRefusalKind = FinalizationRuntime.watchHistoryRefusalKind()
				probe?.tick()
				val nextSessions = probe?.sessions?.value ?: emptyList()
				sessions.value = nextSessions
				sessionCount = nextSessions.size
				delay(1000)
			}
		}

		fun report(message: String, isError: Boolean) {
			status = message
			statusIsError = isError
			EventLog.append(if (isError) "error" else "hive", message)
		}

		MainScreen(
			sessions = { sessions.value },
			sessionCount = sessionCount,
			logLines = { logLines.value },
			hasAccess = hasAccess,
			serviceRunning = probe != null,
				error = probeError,
			account = account,
			accountBusy = busy,
			accountStatus = status,
			accountStatusIsError = statusIsError,
			monitoring = monitoring,
			autoScrobble = autoScrobble,
			youTubeScrobbling = nativeSources.youTubeScrobbling,
			nativeShortsGranted = nativeShortsGranted,
			nativeShortsDropped = nativeShortsDropped,
			nativeShortsStatus = { nativeShortsStatus.value },
			thresholdPercent = settings.thresholdPercent,
			urlWatcherEnabled = urlWatcher,
			urlWatcherDropped = urlWatcherDropped,
			usageAccessGranted = usageAccessGranted,
			recent = recent,
			skipped = skipped,
			mutedIds = mutedIds,
			snaps = snaps,
			posts = posts,
			tracksWithoutVideoId = quietBar,
			queuedCount = queued,
			youTubeAccount = youTubeAccount,
			watchHistory = watchHistory,
			watchHistoryRefusal = watchHistoryRefusal,
			watchHistoryRefusalKind = watchHistoryRefusalKind,
			eventLogEnabled = eventLogging,
			developerMode = developerMode,
			appVersion = BuildConfig.VERSION_NAME,
			themeChoice = themeChoice,
			onThemeChoice = { choice ->
				settings.themeChoice = choice.name
				onThemeChoice(choice)
			},
			onSetDeveloperMode = { enabled ->
				settings.developerMode = enabled
				developerMode = enabled
			},
			// Read off the main thread by the check that calls it, and only ever
			// the derived `STM…` **public** key: unlocking the vault is the
			// expensive half, and the private key has no reason to exist above
			// this line.
			postingPublicKey = { vault.loadKey()?.publicKeyString },
			onToggleEventLogging = { enabled ->
				settings.eventLogging = enabled
				eventLogging = enabled
				// Off erases what was already written, so the switch is true
				// about the past as well as the future. Applied here rather than
				// inside the row so the destination list and the log's policy
				// change in the same place.
				EventLog.applyPolicy(enabled)
			},
			onToggleWatchHistory = { enabled ->
				FinalizationRuntime.setWatchHistoryEnabled(enabled)
				watchHistory = enabled
				watchHistoryRefusal = FinalizationRuntime.watchHistoryRefusal()
			},
			onConnectYouTube = {
				startActivity(Intent(this@MainActivity, YouTubeSignInActivity::class.java))
			},
			onOpenYouTubeHistory = { openVideo(WATCH_HISTORY_URL) },
			onDisconnectYouTube = {
				FinalizationRuntime.disconnectYouTubeSession()
				youTubeAccount = null
				watchHistory = false
				watchHistoryRefusal = null
				watchHistoryRefusalKind = null
				report(
					"YouTube account disconnected and its session wiped from this device.",
					isError = false,
				)
			},
			onGrantAccess = ::openNotificationAccessSettings,
			onExportLog = ::exportLog,
			onClearLog = EventLog::clear,
			onToggleMonitoring = { enabled ->
				MonitorSwitch.set(this@MainActivity, enabled)
				report(
					if (enabled) {
						"Monitoring started."
					} else {
						"Monitoring stopped. Nothing is being read; " +
							"anything already queued still sends."
					},
					isError = false,
				)
			},
			onToggleYouTubeScrobbling = { enabled ->
				// One call, all five mechanisms. Both native epochs are bumped
				// inside it, so anything mid-finalization for either package is
				// invalidated rather than allowed to land after the user said
				// stop — turning YouTube off must never be what *causes* a
				// broadcast. The probe reacts through the config flow the
				// listener service collects.
				NativeSourceSwitches.setYouTubeScrobbling(this@MainActivity, enabled)
				report(
					if (enabled) {
						"YouTube scrobbling on — browser, the YouTube app and " +
							"YouTube Music are watched again."
					} else {
						"YouTube scrobbling off. Nothing YouTube is being read, and " +
							"anything part-played was discarded rather than logged."
					},
					isError = false,
				)
			},
			onOpenAccessibility = ::openAccessibilitySettings,
			onGrantUsageAccess = { openUsageAccessSettings() },
			onToggleAutoScrobble = { enabled ->
				if (enabled && account == null) {
					report("Add a Hive key first — nothing can be signed.", true)
				} else {
					FinalizationRuntime.setAutoScrobble(enabled)
					autoScrobble = enabled
				}
			},
			onOpenVideo = ::openVideo,
			onMute = { record ->
				val id = record.videoId ?: return@MainScreen
				val label = record.artist?.let { "$it — ${record.title}" } ?: record.title
				FinalizationRuntime.mute(id, label)
				mutedIds = FinalizationRuntime.mutedVideos().keys
				report(
					"\"$label\" won't scrobble again. The entry already " +
						"on-chain can't be removed — nothing can remove it.",
					isError = false,
				)
			},
			onRetryQueue = {
				FinalizationRuntime.flushQueue()
				report("Retrying queued scrobbles…", false)
			},
			onValidateAndSave = { username, wif ->
				busy = true
				status = "Checking @$username's posting authority on-chain…"
				statusIsError = false
				lifecycleScope.launch {
					when (val result = withContext(Dispatchers.IO) {
						validator.validate(username, wif)
					}) {
						is KeyValidator.Result.Valid -> {
							vault.save(result.username, wif, result.publicKey)
							account = vault.account
							report(
								"Key verified against @${result.username}'s posting " +
									"authority and saved.",
								isError = false,
							)
						}

						is KeyValidator.Result.Invalid ->
							report(result.reason, isError = true)
					}
					busy = false
				}
			},
			onForgetKey = {
				vault.forget()
				account = null
				FinalizationRuntime.setAutoScrobble(false)
				autoScrobble = false
				report("Key wiped from this device. Auto-scrobble off.", isError = false)
			},
		)
	}

	// The activity's own `broadcast(...)` helper is gone with the button that
	// was its only caller. It signed and dispatched a payload from the UI thread's
	// side of the app, in parallel with the engine's dispatcher, with no queue
	// behind it — a second transport path that existed to serve one test button.
	// Nothing in this class signs, dispatches or finalizes anything now; the
	// engine owns all three, which is what `UnifiedFinalizationWiringTest` and
	// `Phase9CompatibilityRemovalTest` assert.

	override fun onStart() {
		super.onStart()
		// Coming back to the app is a good moment to drain anything queued.
		FinalizationRuntime.flushQueue()
	}

	private fun openNotificationAccessSettings() {
		startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
	}

	/**
	 * There is no intent that jumps straight to one service's toggle, so this
	 * lands on the Accessibility list and the user picks RustedWax from it.
	 *
	 * On Android 13+ a sideloaded APK's accessibility toggle is greyed out
	 * until "Allow restricted settings" is granted from App info — the toggle
	 * looks broken rather than blocked, so the app says so up front.
	 */
	private fun openAccessibilitySettings() {
		EventLog.append(
			"url",
			"opening Accessibility settings — on Android 13+ a sideloaded build " +
				"needs 'Allow restricted settings' from App info first",
		)
		startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
	}

	/**
	 * Usage access, which is what makes PiP audio attributable to YouTube.
	 *
	 * Special access rather than a runtime permission, so there is no prompt to
	 * show — the only route is the Settings screen, and the user has to find
	 * RustedWax in the list themselves.
	 */
	private fun openUsageAccessSettings() {
		EventLog.append(
			"native-shorts",
			"opening Usage access settings — needed so picture-in-picture audio can " +
				"be attributed to YouTube rather than to any app that happens to be " +
				"playing",
		)
		startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS))
	}

	private fun openVideo(url: String) {
		val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
		runCatching { startActivity(view) }.onFailure {
			EventLog.append("ui", "nothing on this device can open a YouTube link")
		}
	}

	private companion object {
		/**
		 * Where a paused history is un-paused.
		 *
		 * The feed page itself, not a settings deep link: Android has no intent
		 * that lands on YouTube's history controls, and the feed is one tap from
		 * them and is the page whose emptiness the warning is about.
		 */
		const val WATCH_HISTORY_URL = "https://www.youtube.com/feed/history"
	}

	private fun exportLog() {
		val file = EventLog.logFile() ?: return
		if (!file.exists()) return
		val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
		val send = Intent(Intent.ACTION_SEND).apply {
			type = "text/plain"
			putExtra(Intent.EXTRA_STREAM, uri)
			putExtra(Intent.EXTRA_SUBJECT, "RustedWax log")
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		}
		startActivity(Intent.createChooser(send, "Export log"))
	}
}

private data class GrantReport(val dropped: Boolean, val notLiveSince: Long)

private fun noteGrant(
	live: Boolean,
	everGranted: Boolean,
	remember: (Boolean) -> Unit,
	alreadyReported: Boolean,
	notLiveSince: Long,
	nowMillis: Long,
	label: String,
): GrantReport {
	if (live) {
		if (!everGranted) remember(true)
		return GrantReport(dropped = false, notLiveSince = 0L)
	}
	val since = if (notLiveSince == 0L) nowMillis else notLiveSince
	val health = AccessibilityGrantHealth.classify(
		live = false,
		everGranted = everGranted,
		notLiveForMillis = nowMillis - since,
	)
	if (health == GrantHealth.DROPPED && !alreadyReported) {
		EventLog.append(
			"health",
			"$label was granted and is no longer enabled. Android disables an " +
				"accessibility service when it crashes, so this can happen without " +
				"anyone changing a setting. Nothing is being read through it.",
		)
	}
	return GrantReport(dropped = health == GrantHealth.DROPPED, notLiveSince = since)
}
