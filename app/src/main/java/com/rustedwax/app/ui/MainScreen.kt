package com.rustedwax.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rustedwax.app.R
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.app.ui.snaps.DiscardSnapDialog
import com.rustedwax.app.ui.snaps.SnapComposer
import com.rustedwax.app.snaps.SnapMedia
import com.rustedwax.app.ui.snaps.SnapComposerState
import com.rustedwax.app.ui.snaps.SnapPostController
import com.rustedwax.app.ui.snaps.SnapPostStatus
import com.rustedwax.app.ui.snaps.SnapText
import com.rustedwax.app.detect.ScrobbleBuilder
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.NativeShortsObserver
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.enrich.WatchHistoryHealth
import com.rustedwax.app.storage.KeyVault
import com.rustedwax.app.storage.YouTubeSessionVault
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The whole UI: what's playing now, scrobble history, the settings that own
 * every switch and both accounts, and the raw event log explaining each
 * decision.
 *
 * Utilitarian by design — this is a tool for one person, and the log being
 * readable matters more than the chrome around it.
 *
 * Navigation is by [Destination], never by tab number: which destinations exist
 * depends on whether the event log is switched on, and an index cannot survive
 * that list changing under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
	/**
	 * Deferred so the once-per-second playback-position update is read only by
	 * [SessionList]. Passing the list by value makes every destination and the
	 * full semantics tree recompose while a track plays.
	 */
	sessions: () -> List<SessionSnapshot>,
	/** Stable tab-label input; unlike position, this changes only on add/remove. */
	sessionCount: Int,
	/**
	 * Deferred, not passed by value.
	 *
	 * The log is written several times a second while something is playing, and a
	 * `List<String>` is an unstable Compose parameter — so passing the value made
	 * every logged line recompose this entire screen, and with RustedWax's own
	 * accessibility services enabled each recomposition drags Compose's whole
	 * semantics-tree rebuild with it. Reading it inside the one page that draws it
	 * confines the invalidation to that page.
	 */
	logLines: () -> List<String>,
	hasAccess: Boolean,
	serviceRunning: Boolean,
	error: String?,
	account: KeyVault.Account?,
	accountBusy: Boolean,
	accountStatus: String?,
	accountStatusIsError: Boolean,
	monitoring: Boolean,
	autoScrobble: Boolean,
	youTubeScrobbling: Boolean,
	nativeShortsGranted: Boolean,
	nativeShortsDropped: Boolean,
	/** Deferred for the same reason as [logLines]: it turns over about once a
	 * second while a Short is on screen. */
	nativeShortsStatus: () -> NativeShortsObserver.Status,
	thresholdPercent: Int,
	urlWatcherEnabled: Boolean,
	urlWatcherDropped: Boolean,
	usageAccessGranted: Boolean,
	recent: List<FinalizationRuntime.ScrobbleRecord>,
	skipped: List<FinalizationRuntime.SkipRecord>,
	mutedIds: Set<String>,
	/**
	 * Open-composer and draft state for History Snaps.
	 *
	 * Owned above this screen because History is torn down and rebuilt every time
	 * the tab changes, and a half-typed Snap must survive that.
	 */
	snaps: SnapComposerState,
	posts: SnapPostController,
	tracksWithoutVideoId: Int,
	queuedCount: Int,
	youTubeAccount: YouTubeSessionVault.Session?,
	watchHistory: Boolean,
	watchHistoryRefusal: String?,
	watchHistoryRefusalKind: WatchHistoryHealth.Refusal?,
	eventLogEnabled: Boolean,
	developerMode: Boolean,
	appVersion: String,
	themeChoice: ThemeChoice,
	onThemeChoice: (ThemeChoice) -> Unit,
	onSetDeveloperMode: (Boolean) -> Unit,
	/**
	 * Derives the `STM…` **public** posting key from the vault, for the
	 * developer-mode connection check. A function so the encrypted read happens
	 * off the main thread and the private key never enters the UI.
	 */
	postingPublicKey: () -> String?,
	onToggleEventLogging: (Boolean) -> Unit,
	onToggleWatchHistory: (Boolean) -> Unit,
	onConnectYouTube: () -> Unit,
	onDisconnectYouTube: () -> Unit,
	onOpenYouTubeHistory: () -> Unit,
	onGrantAccess: () -> Unit,
	onExportLog: () -> Unit,
	onClearLog: () -> Unit,
	onToggleMonitoring: (Boolean) -> Unit,
	onToggleYouTubeScrobbling: (Boolean) -> Unit,
	onOpenAccessibility: () -> Unit,
	onGrantUsageAccess: () -> Unit,
	onToggleAutoScrobble: (Boolean) -> Unit,
	onOpenVideo: (String) -> Unit,
	onMute: (FinalizationRuntime.ScrobbleRecord) -> Unit,
	onRetryQueue: () -> Unit,
	onValidateAndSave: (String, String) -> Unit,
	onForgetKey: () -> Unit,
) {
	val destinations = AppNavigation.destinations(eventLogEnabled)
	var chosen by remember { mutableStateOf(Destination.NOW) }
	// Resolved every composition rather than repaired by an effect: a selection
	// that stopped existing has to be handled before anything draws with it, and
	// there is no frame in which the screen should be showing a destination that
	// is not in the list beside it.
	val selected = AppNavigation.resolve(chosen, destinations)

	// Repair a selection whose conditional destination disappeared. The screen
	// owns only the named destination; there is deliberately no second numeric
	// pager state to reconcile with it.
	LaunchedEffect(destinations) {
		chosen = AppNavigation.resolve(chosen, destinations)
	}
	val swipeThreshold = with(LocalDensity.current) { 48.dp.toPx() }

	Scaffold(
		containerColor = MaterialTheme.colorScheme.background,
		topBar = {
			TopAppBar(
				// The mark, then the name — the same pairing as the launcher,
				// so the app you tapped and the app you're looking at agree.
				title = {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Image(
							painter = painterResource(R.drawable.rustedwax_mark),
							contentDescription = null,
							modifier = Modifier
								.size(32.dp)
								.clip(RoundedCornerShape(percent = 50)),
						)
						Spacer(Modifier.width(10.dp))
						Text("RustedWax", style = MaterialTheme.typography.titleLarge)
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
					titleContentColor = MaterialTheme.colorScheme.onBackground,
				),
			)
		},
	) { padding ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(padding)
				.padding(horizontal = 12.dp),
		) {
			if (!hasAccess) {
				AccessBanner(onGrantAccess)
			} else if (monitoring && !serviceRunning) {
				// Grant held, monitoring wanted, but the system hasn't (re)bound
				// the listener. Only an error while monitoring is *on* — a user
				// Stop produces the same null probe and is not a fault.
				Text(
					"Waiting for Android to start the listener service — toggle " +
						"Notification Access off and on if this persists.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.error,
					modifier = Modifier.padding(top = 8.dp),
				)
			}

			// One line, not the whole settings card. Everything below this point
			// gets the rest of a 720×1600 screen, which the card used to spend
			// on itself — and would have spent more of with every feature added.
			// The switches now live on their own scrolling destination.
			StatusStrip(
				monitoring = monitoring,
				autoScrobble = autoScrobble,
				hasKey = account != null,
				thresholdPercent = thresholdPercent,
				queuedCount = queuedCount,
				onToggleMonitoring = onToggleMonitoring,
			)
			error?.let {
				Text(
					it,
					color = MaterialTheme.colorScheme.error,
					style = MaterialTheme.typography.bodySmall,
					modifier = Modifier.padding(vertical = 4.dp),
				)
			}

			// Above the destinations, so it is on screen wherever you are. A
			// native video that cannot be named is silently not logged, and the
			// only previous hint was a sentence inside a settings row.
			WatchHistoryWarning(
				state = YouTubeConnectionWarning.evaluate(
					connected = youTubeAccount != null,
					refusal = watchHistoryRefusalKind,
				),
				detail = watchHistoryRefusal,
				onSignIn = onConnectYouTube,
				onOpenYouTubeHistory = onOpenYouTubeHistory,
			)

			if (urlWatcherEnabled && tracksWithoutVideoId >= FinalizationRuntime.QUIET_BAR_THRESHOLD) {
				Card(
					modifier = Modifier
						.fillMaxWidth()
						.padding(top = 8.dp),
					colors = CardDefaults.cardColors(
						containerColor = MaterialTheme.colorScheme.errorContainer,
					),
				) {
					Column(Modifier.padding(10.dp)) {
						Text(
							"The address bar has gone quiet",
							style = MaterialTheme.typography.titleSmall,
						)
						Text(
							"No video has been identified for the last " +
								"$tracksWithoutVideoId tracks. They were kept off-chain " +
								"because every entry requires a verified hyperlink.\n\n" +
								"Check Accessibility is still granted, or tap the " +
								"browser toolbar once to expand it.",
							style = MaterialTheme.typography.bodySmall,
						)
						Spacer(Modifier.height(6.dp))
						WaxOutlinedButton(onClick = onOpenAccessibility) { Text("Accessibility") }
					}
				}
			}

			// The strip is wider than a phone, so it scrolls rather than silently
			// clipping whichever destination is last. The selected one is drawn
			// rather than prefixed — the "▸" was standing in for a colour the app
			// didn't have.
			TabStrip(
				destinations = destinations,
				selected = selected,
				onSelect = { destination -> chosen = destination },
				label = { destination ->
					AppNavigation.label(
						destination,
						sessions = sessionCount,
						history = recent.size,
						notLogged = skipped.size,
					)
				},
			)

			Box(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth()
					.pointerInput(destinations, selected, swipeThreshold) {
						var dragged = 0f
						detectHorizontalDragGestures(
							onDragStart = { dragged = 0f },
							onHorizontalDrag = { change, amount ->
								dragged += amount
								change.consume()
							},
							onDragCancel = { dragged = 0f },
							onDragEnd = {
								val delta = when {
									dragged <= -swipeThreshold -> 1
									dragged >= swipeThreshold -> -1
									else -> 0
								}
								if (delta != 0) {
									chosen = AppNavigation.swipe(selected, destinations, delta)
								}
								dragged = 0f
							},
						)
					},
			) {
				when (selected) {
					Destination.HISTORY ->
						// Thumbnails ride the YouTube switch: they are a request
						// to i.ytimg.com keyed to an already-identified video,
						// which is part of the same pipeline that switch governs.
						HistoryList(recent, mutedIds, youTubeScrobbling, snaps, posts, onOpenVideo, onMute)

					Destination.NOT_LOGGED -> SkippedList(skipped, youTubeScrobbling, onOpenVideo)

					Destination.LOG -> LogList(logLines, onExportLog, onClearLog)

					// Its own scroll, so a switch added next month pushes nothing
					// off the bottom of a small screen.
					Destination.SETTINGS -> Column(
						Modifier
							.fillMaxSize()
							.verticalScroll(rememberScrollState()),
					) {
						ScrobbleControls(
							monitoring = monitoring,
							autoScrobble = autoScrobble,
							youTubeScrobbling = youTubeScrobbling,
							nativeShortsGranted = nativeShortsGranted,
							nativeShortsDropped = nativeShortsDropped,
							nativeShortsStatus = nativeShortsStatus,
							thresholdPercent = thresholdPercent,
							account = account,
							accountBusy = accountBusy,
							accountStatus = accountStatus,
							accountStatusIsError = accountStatusIsError,
							queuedCount = queuedCount,
							urlWatcherEnabled = urlWatcherEnabled,
							urlWatcherDropped = urlWatcherDropped,
							usageAccessGranted = usageAccessGranted,
							youTubeAccount = youTubeAccount,
							watchHistory = watchHistory,
							watchHistoryRefusal = watchHistoryRefusal,
							eventLogEnabled = eventLogEnabled,
							developerMode = developerMode,
							appVersion = appVersion,
							postingPublicKey = postingPublicKey,
							themeChoice = themeChoice,
							onThemeChoice = onThemeChoice,
							onSetDeveloperMode = onSetDeveloperMode,
							onToggleEventLogging = onToggleEventLogging,
							onToggleYouTubeScrobbling = onToggleYouTubeScrobbling,
							onOpenAccessibility = onOpenAccessibility,
							onGrantUsageAccess = onGrantUsageAccess,
							onToggleWatchHistory = onToggleWatchHistory,
							onConnectYouTube = onConnectYouTube,
							onDisconnectYouTube = onDisconnectYouTube,
							onToggle = onToggleAutoScrobble,
							onRetryQueue = onRetryQueue,
							onValidateAndSave = onValidateAndSave,
							onForgetKey = onForgetKey,
						)
						Spacer(Modifier.height(16.dp))
					}

					else -> SessionList(
						sessions = sessions,
						monitoring = monitoring,
						thresholdPercent = thresholdPercent,
						autoScrobble = autoScrobble && account != null,
					)
				}
			}
		}
	}
}

@Composable
private fun WatchHistoryWarning(
	state: YouTubeConnectionWarning.State?,
	detail: String?,
	onSignIn: () -> Unit,
	onOpenYouTubeHistory: () -> Unit,
) {
	if (state == null) return
	Card(
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 8.dp),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.errorContainer,
		),
	) {
		Column(Modifier.padding(10.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				WaxRowIcon(WaxIcons.History, alert = true)
				Spacer(Modifier.width(10.dp))
				Text(state.title, style = MaterialTheme.typography.titleSmall)
			}
			Spacer(Modifier.height(4.dp))
			Text(state.body, style = MaterialTheme.typography.bodySmall)
			// The engine's own sentence, when it has one, under the explanation
			// rather than instead of it.
			if (detail != null && state.action != YouTubeConnectionWarning.Action.SIGN_IN) {
				Spacer(Modifier.height(4.dp))
				Text(
					detail,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onErrorContainer,
				)
			}
			when (state.action) {
				YouTubeConnectionWarning.Action.NONE -> Unit
				YouTubeConnectionWarning.Action.SIGN_IN -> {
					Spacer(Modifier.height(6.dp))
					WaxOutlinedButton(onClick = onSignIn) { Text(state.actionLabel.orEmpty()) }
				}

				YouTubeConnectionWarning.Action.OPEN_YOUTUBE_HISTORY -> {
					Spacer(Modifier.height(6.dp))
					WaxOutlinedButton(onClick = onOpenYouTubeHistory) {
						Text(state.actionLabel.orEmpty())
					}
				}
			}
		}
	}
}

/**
 * The horizontal strip, which scrolls the destination you picked fully into view.
 *
 * The strip is wider than a phone, so at rest some entry is always half off the
 * edge — and tapping a half-visible one used to leave it half visible, which
 * reads as the app having ignored the tap. Selecting one now brings all of it on
 * screen, plus [EDGE_MARGIN] so it doesn't sit flush against the bezel and so
 * the next one peeks out and says the strip continues.
 *
 * One already fully visible is left alone. Scrolling on selection is there to
 * fix a clipped entry, not to re-centre the strip every time it is touched.
 *
 * Positions come from layout rather than from measuring text: the labels carry
 * live counts, so their widths change as sessions come and go. They are keyed by
 * [Destination] rather than by position, so a destination appearing or
 * disappearing cannot leave another one's bounds attributed to it.
 */
@Composable
private fun TabStrip(
	destinations: List<Destination>,
	selected: Destination,
	onSelect: (Destination) -> Unit,
	label: (Destination) -> String,
) {
	val scroll = rememberScrollState()
	// Offset and width of each entry within the scrolling content, filled in by
	// layout rather than measured here. Layout writing into state that
	// composition reads settles because the value stops changing: the second
	// pass places every entry where the first one did.
	val bounds = remember { mutableStateMapOf<Destination, IntRange>() }
	var viewportWidth by remember { mutableIntStateOf(0) }
	val margin = with(LocalDensity.current) { EDGE_MARGIN.roundToPx() }

	LaunchedEffect(selected, viewportWidth, bounds[selected]) {
		val tab = bounds[selected] ?: return@LaunchedEffect
		if (viewportWidth == 0) return@LaunchedEffect
		val visible = scroll.value..(scroll.value + viewportWidth)
		val target = when {
			// Off the left edge, or clipped by it.
			tab.first - margin < visible.first -> tab.first - margin
			// Off the right edge. Never scroll so far that the entry's own start
			// leaves the viewport: one wider than the strip should show its
			// beginning, which is where the label starts.
			tab.last + margin > visible.last ->
				minOf(tab.last + margin - viewportWidth, tab.first - margin)
			// Already fully visible: leave it exactly where it is.
			else -> return@LaunchedEffect
		}
		scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue))
	}

	Row(
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier
			.padding(vertical = 8.dp)
			.onSizeChanged { viewportWidth = it.width }
			.horizontalScroll(scroll),
	) {
		destinations.forEach { destination ->
			WaxOutlinedButton(
				onClick = { onSelect(destination) },
				selected = selected == destination,
				modifier = Modifier.onPlaced { coords ->
					val start = coords.positionInParent().x.roundToInt()
					bounds[destination] = start..(start + coords.size.width)
				},
			) {
				Text(label(destination))
			}
		}
	}
}

/** How much clear space a freshly selected tab gets against the screen edge. */
private val EDGE_MARGIN = 12.dp

/**
 * The one line that has to be visible from every tab.
 *
 * The switch that decides whether anything is read at all lives here, so "is it
 * on, and can I stop it right now" is never behind a tab. Nothing else
 * qualifies: the rest are set once and left, which is exactly why they were
 * costing a third of the screen on a 720×1600 phone.
 *
 * While it is running there is **no headline** — only the line that states the
 * rule, `Scrobbling at 60% played`. The word above it used to be `Monitoring`,
 * which described the app watching rather than what it would do, and reads as
 * surveillance for no benefit to the person reading it. `Stopped` keeps its
 * headline, because that is the state worth noticing.
 */
@Composable
private fun StatusStrip(
	monitoring: Boolean,
	autoScrobble: Boolean,
	hasKey: Boolean,
	thresholdPercent: Int,
	queuedCount: Int,
	onToggleMonitoring: (Boolean) -> Unit,
) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 8.dp),
	) {
		Column(Modifier.weight(1f)) {
			MonitoringStatus.headline(monitoring)?.let { headline ->
				Text(
					headline,
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.error,
				)
			}
			Text(
				MonitoringStatus.detail(
					monitoring = monitoring,
					hasKey = hasKey,
					autoScrobble = autoScrobble,
					thresholdPercent = thresholdPercent,
					queuedCount = queuedCount,
				),
				style = MaterialTheme.typography.bodySmall,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		// Outlined rather than filled, and pink either way: this is the one
		// control that is on screen at all times, and a solid block of colour
		// permanently in the corner would out-shout whatever tab is below it.
		WaxOutlinedButton(
			onClick = { onToggleMonitoring(!monitoring) },
			selected = true,
			icon = if (monitoring) WaxIcons.StopSquare else WaxIcons.PlayTriangle,
		) {
			Text(if (monitoring) "Stop" else "Start")
		}
	}
}

/**
 * The settings list, walked in [SettingsOutline]'s order.
 *
 * ## Two nested switches, and the nesting is the point
 *
 * **Monitoring** is the outer one: off means the probe is gone and nothing is
 * read at all. It is not in this list — it is the one control that has to be
 * reachable from every destination, so it lives in the always-visible strip
 * above rather than twice. **Automatic scrobbling** is the inner one, and only
 * gates the final broadcast; it still leaves the app watching, which is what
 * makes the Now destination useful for diagnosing why something parsed the way
 * it did. It is now first, because it is the switch people open Settings for.
 *
 * ## Why the order lives somewhere else
 *
 * A `Column` of cards puts the order in whichever line each card happens to be
 * declared on, where nothing can assert it and adding a row silently changes it.
 * The order is a product decision, so it is stated in [SettingsOutline], tested
 * there, and rendered here.
 */
@Composable
private fun ScrobbleControls(
	monitoring: Boolean,
	autoScrobble: Boolean,
	youTubeScrobbling: Boolean,
	nativeShortsGranted: Boolean,
	nativeShortsDropped: Boolean,
	nativeShortsStatus: () -> NativeShortsObserver.Status,
	thresholdPercent: Int,
	account: KeyVault.Account?,
	accountBusy: Boolean,
	accountStatus: String?,
	accountStatusIsError: Boolean,
	queuedCount: Int,
	urlWatcherEnabled: Boolean,
	urlWatcherDropped: Boolean,
	usageAccessGranted: Boolean,
	youTubeAccount: YouTubeSessionVault.Session?,
	watchHistory: Boolean,
	watchHistoryRefusal: String?,
	eventLogEnabled: Boolean,
	developerMode: Boolean,
	appVersion: String,
	postingPublicKey: () -> String?,
	themeChoice: ThemeChoice,
	onThemeChoice: (ThemeChoice) -> Unit,
	onSetDeveloperMode: (Boolean) -> Unit,
	onToggleEventLogging: (Boolean) -> Unit,
	onToggleYouTubeScrobbling: (Boolean) -> Unit,
	onOpenAccessibility: () -> Unit,
	onGrantUsageAccess: () -> Unit,
	onToggleWatchHistory: (Boolean) -> Unit,
	onConnectYouTube: () -> Unit,
	onDisconnectYouTube: () -> Unit,
	onToggle: (Boolean) -> Unit,
	onRetryQueue: () -> Unit,
	onValidateAndSave: (String, String) -> Unit,
	onForgetKey: () -> Unit,
) {
	val hasKey = account != null
	// One card per setting, as the mockup draws them. A row is easier to find
	// when its edges say where it ends.
	Column(
		verticalArrangement = Arrangement.spacedBy(8.dp),
		modifier = Modifier.padding(top = 8.dp),
	) {
		SettingsOutline.rows(usageAccessGranted, queuedCount, developerMode).forEach { row ->
			when (row) {
				SettingsRow.AUTOMATIC_SCROBBLING -> SettingRow(
					icon = WaxIcons.Record,
					title = SettingsOutline.title(row),
					body = when {
						!monitoring -> "Paused while monitoring is stopped"
						!hasKey -> "Add a Hive key to enable"
						autoScrobble -> "On — scrobbles at $thresholdPercent% played"
						else -> "Off — nothing is broadcast automatically"
					},
				) {
					Switch(
						checked = autoScrobble && monitoring,
						onCheckedChange = onToggle,
						enabled = hasKey && monitoring,
						colors = waxSwitchColors(),
					)
				}

				// One switch where there were four. Native YouTube, native
				// YouTube Music, video lookups and picture-in-picture counting
				// were never policies anybody holds — they are this app's own
				// machinery asking to be configured. They all still exist and
				// all still run; they just answer to this now.
				SettingsRow.YOUTUBE_SCROBBLING -> SettingRow(
					icon = WaxIcons.PlayBox,
					title = SettingsOutline.title(row),
					// The copy lives in the outline, where it is asserted. It
					// names the two apps the switch is for and the two things it
					// does that deserve disclosing — a metadata lookup, and
					// counting time in a floating window. It is a description of
					// the feature, not an inventory of every surface it gates.
					body = SettingsOutline.youTubeScrobblingBody(on = youTubeScrobbling),
				) {
					Switch(
						checked = youTubeScrobbling,
						onCheckedChange = onToggleYouTubeScrobbling,
						colors = waxSwitchColors(),
					)
				}

				SettingsRow.FOREGROUND_SHORTS -> SettingRow(
					icon = WaxIcons.Bolt,
					title = SettingsOutline.title(row),
					body = when {
						nativeShortsDropped ->
							"Stopped on its own — this was granted and Android has since " +
								"disabled it, which is what happens when the service crashes. " +
								"No foreground Short has been read since. Re-enable RustedWax — " +
								"Native Shorts in Accessibility."
						!nativeShortsGranted ->
							"Experimental grant off — enable RustedWax — Native Shorts in " +
								"Accessibility. It is OS-scoped only to com.google.android.youtube."
						!youTubeScrobbling ->
							"Granted but idle — turn on YouTube scrobbling to use it."
						// "PiP and background time are not counted" was carried
						// here from before picture-in-picture inference existed,
						// and it is simply untrue: a Short watched in PiP is
						// credited from elapsed time and reaches the chain.
						else ->
							"Granted — reads the YouTube app's own Shorts player. Screen-off " +
								"and lock-screen time are never counted. Picture-in-picture " +
								"has no player to read, so it is credited from elapsed time " +
								"where Usage access allows, and marked inferred."
					},
					// Three states, not two. "No readable player" and "nothing
					// playing" are the same `completePlayerProof == false`, and
					// collapsing them said "No Shorts player on screen" over a
					// Short that was playing in picture-in-picture and being
					// credited second by second — a working listen reported as a
					// failure. YouTube draws no title, no handle and no seekbar in
					// PiP, so unreadable is the expected state there, not a fault.
					liveNote = if (
						!nativeShortsGranted || nativeShortsDropped || !youTubeScrobbling
					) {
						null
					} else {
						// Read here, inside the row that draws it, so a status
						// change invalidates this note rather than the screen.
						val status = nativeShortsStatus()
						settledText(
							when {
								status.completePlayerProof -> "Reading a Short now"
								status.inferredPlaying ->
									"Playing in picture-in-picture — counting elapsed time"
								else -> "No Shorts player on screen"
							},
						)
					},
					alert = nativeShortsDropped,
				) {
					WaxOutlinedButton(onClick = onOpenAccessibility) {
						Text(if (nativeShortsGranted) "Manage" else "Enable")
					}
				}

				SettingsRow.BROWSER_EVIDENCE -> SettingRow(
					icon = WaxIcons.Search,
					title = SettingsOutline.title(row),
					body = when {
						urlWatcherDropped ->
							"Stopped on its own — this was granted and Android has since " +
								"disabled it, which is what happens when the service crashes. " +
								"Nothing watched in Chrome or Brave has been recorded since."
						urlWatcherEnabled ->
							"On — exact site/video link and visible YouTube ad labels"
						else -> "Off — no video link or visible ad-label detection"
					},
					alert = urlWatcherDropped,
				) {
					WaxOutlinedButton(onClick = onOpenAccessibility) {
						Text(if (urlWatcherEnabled) "Manage" else "Enable")
					}
				}

				// The only route that names an exact id for native playback
				// outside a playlist. Two independent things: whether an account
				// is connected, and whether the route is allowed to use it.
				SettingsRow.WATCH_HISTORY -> SettingRow(
					icon = WaxIcons.History,
					title = SettingsOutline.title(row),
					body = when {
						youTubeAccount == null ->
							"Not connected — native videos outside a playlist can only be " +
								"identified by search, which sometimes cannot tell two " +
								"uploads apart and then logs nothing."
						!youTubeScrobbling ->
							"Connected but idle — turn on YouTube scrobbling to use it."
						watchHistoryRefusal != null -> "Standing down: $watchHistoryRefusal"
						watchHistory ->
							"On — reads only youtube.com/feed/history, as " +
								(youTubeAccount.accountLabel ?: "the connected account") +
								", to name the video that just played."
						else -> "Off — the stored session is kept but not used."
					},
					below = if (youTubeAccount == null) {
						null
					} else {
						{
							WaxButtonRow(Modifier.padding(top = 8.dp)) {
								WaxOutlinedButton(onClick = onDisconnectYouTube) {
									Text("Disconnect account")
								}
							}
						}
					},
				) {
					if (youTubeAccount == null) {
						WaxOutlinedButton(onClick = onConnectYouTube) { Text("Sign in") }
					} else {
						Switch(
							checked = watchHistory && youTubeScrobbling,
							onCheckedChange = onToggleWatchHistory,
							enabled = youTubeScrobbling,
							colors = waxSwitchColors(),
						)
					}
				}

				// The two accounts, side by side. The Hive key used to have a
				// whole destination of its own — one that was, for anyone who had
				// already saved a key, a name, a public key and two buttons
				// nobody presses twice. It is a setting, it is read alongside the
				// YouTube one, and it belongs where the other one is.
				SettingsRow.HIVE_ACCOUNT -> HiveAccountSection(
					account = account,
					busy = accountBusy,
					status = accountStatus,
					statusIsError = accountStatusIsError,
					onValidateAndSave = onValidateAndSave,
					onForget = onForgetKey,
				)

				// Usage access is a grant, not a preference, so it stays on the
				// simple screen even though the switch it used to sit beside is
				// gone: the app cannot ask for it in a dialog and the user has to
				// find RustedWax in a system list themselves. Shown only while it
				// is missing — once granted there is nothing here to do.
				SettingsRow.PICTURE_IN_PICTURE -> SettingRow(
					icon = WaxIcons.PictureInPicture,
					title = SettingsOutline.title(row),
					body = "Not counted — a Short that keeps playing in a floating " +
						"window publishes no progress of any kind, and without Usage " +
						"access YouTube can't be told apart from any other app making " +
						"sound. Grant it and PiP time is credited automatically, " +
						"marked inferred rather than measured.",
					below = {
						WaxButtonRow(Modifier.padding(top = 8.dp)) {
							WaxOutlinedButton(onClick = onGrantUsageAccess) {
								Text("Grant usage access")
							}
						}
					},
				) {}

				// The old §5.4 "Advanced" tier, which anybody could open and
				// almost nobody wanted. What is left of it that is still offered
				// is here, behind seven taps on the version below.
				SettingsRow.DEVELOPER_MODE -> DeveloperModeSection(
					eventLogging = eventLogEnabled,
					hiveUsername = account?.username,
					postingPublicKey = postingPublicKey,
					onToggleEventLogging = onToggleEventLogging,
					onLock = { onSetDeveloperMode(false) },
				)

				// Last, always, and the only way into the tier above it.
				SettingsRow.ABOUT -> AboutRow(
					version = appVersion,
					unlocked = developerMode,
					onUnlock = { onSetDeveloperMode(true) },
				)

				// Appearance. Purely how the app looks — no detection, no
				// traffic, no rule changes — so it sits at the bottom, after
				// everything that does.
				SettingsRow.APPEARANCE -> AppearanceRow(themeChoice, onThemeChoice)

				SettingsRow.QUEUE -> SettingCard {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(
							"$queuedCount waiting to send",
							style = MaterialTheme.typography.bodySmall,
							modifier = Modifier.weight(1f),
						)
						WaxOutlinedButton(onClick = onRetryQueue) { Text("Retry now") }
					}
				}
			}
		}
	}
}

/**
 * The card every settings row is drawn in: surface, hairline, no shadow.
 *
 * Elevation is deliberately flat. In dark mode Material tints an elevated
 * surface toward the primary, which on this palette would put a pink haze
 * behind every row on the screen.
 */
@Composable
internal fun SettingCard(
	modifier: Modifier = Modifier,
	content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
	Card(
		modifier = modifier.fillMaxWidth(),
		shape = MaterialTheme.shapes.medium,
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
		elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
		border = androidx.compose.foundation.BorderStroke(
			1.dp,
			MaterialTheme.colorScheme.outlineVariant,
		),
	) {
		Column(Modifier.padding(12.dp), content = content)
	}
}

@Composable
internal fun SettingRow(
	icon: ImageVector,
	title: String,
	body: String,
	alert: Boolean = false,
	liveNote: String? = null,
	below: (@Composable () -> Unit)? = null,
	control: @Composable () -> Unit,
) {
	SettingCard {
		Row(verticalAlignment = Alignment.Top) {
			WaxRowIcon(icon, alert = alert)
			Spacer(Modifier.width(10.dp))
			Column(Modifier.weight(1f)) {
				Text(title, style = MaterialTheme.typography.titleSmall)
				Spacer(Modifier.height(2.dp))
				Text(
					body,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				if (liveNote != null) {
					Spacer(Modifier.height(4.dp))
					Text(
						liveNote,
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.primary,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
			Spacer(Modifier.width(8.dp))
			control()
		}
		below?.invoke()
	}
}

/**
 * A value that has stopped changing, for text a person is trying to read.
 *
 * The foreground-Shorts observer flips between "proof" and "no proof" several
 * times a second as YouTube redraws its player, and a label doing that is not
 * status — it is a strobe. This holds the last value until a new one has
 * survived [SETTLE_MILLIS], so the line only moves when the thing it describes
 * actually settles.
 *
 * Not a throttle: the first value appears immediately, and a value that flaps
 * and returns never redraws at all.
 */
@Composable
private fun settledText(value: String): String {
	var settled by remember { mutableStateOf(value) }
	LaunchedEffect(value) {
		delay(SETTLE_MILLIS)
		settled = value
	}
	return settled
}

/**
 * Long enough that YouTube's player redraw doesn't reach the screen.
 *
 * Measured against the real cadence: in picture-in-picture the observer
 * alternates between crediting inferred time and a bounded refresh grace at
 * roughly one second, so a settle shorter than that lets the label flip anyway.
 */
private const val SETTLE_MILLIS = 1_500L

/**
 * Light, dark, or follow the phone.
 *
 * Three states rather than a switch: "follow the system" is a real answer and
 * the one most people want, and a two-position switch can't hold it — it would
 * silently pick one the first time it was touched and never give the choice
 * back.
 */
@Composable
private fun AppearanceRow(choice: ThemeChoice, onChoice: (ThemeChoice) -> Unit) {
	SettingCard {
		Row(verticalAlignment = Alignment.Top) {
			WaxRowIcon(WaxIcons.Contrast)
			Spacer(Modifier.width(10.dp))
			Column(Modifier.weight(1f)) {
				Text("Appearance", style = MaterialTheme.typography.titleSmall)
				Spacer(Modifier.height(2.dp))
				Text(
					when (choice) {
						ThemeChoice.SYSTEM -> "Following the system's light/dark setting"
						ThemeChoice.LIGHT -> "Light — ignores the system setting"
						ThemeChoice.DARK -> "Dark — ignores the system setting"
					},
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		WaxButtonRow(Modifier.padding(top = 8.dp)) {
			WaxOutlinedButton(
				onClick = { onChoice(ThemeChoice.SYSTEM) },
				selected = choice == ThemeChoice.SYSTEM,
			) { Text("System") }
			WaxOutlinedButton(
				onClick = { onChoice(ThemeChoice.LIGHT) },
				selected = choice == ThemeChoice.LIGHT,
			) { Text("Light") }
			WaxOutlinedButton(
				onClick = { onChoice(ThemeChoice.DARK) },
				selected = choice == ThemeChoice.DARK,
			) { Text("Dark") }
		}
	}
}

/**
 * Thumbnail and title as a link to the video, when — and only when — the exact
 * one is known.
 *
 * [YouTubeProbe.canonicalWatchUrl] refuses anything that is not a proven video
 * id, so a row without one simply isn't tappable. That is the point rather than
 * a shortfall: the alternative is a YouTube search for the title, which for the
 * duplicate uploads and re-uploads this app spends most of its effort telling
 * apart would open the wrong video and look authoritative doing it. A row that
 * does nothing is honest; a row that opens someone else's re-upload is not.
 *
 * The tap target is the thumbnail and the title, and nothing else in the row.
 */
@Composable
private fun VideoLink(
	/**
	 * Null when identity never resolved. The row still draws — a Not-logged
	 * entry explaining a refusal is worth reading whether or not it can open the
	 * video — it simply is not tappable, exactly as an id that fails
	 * [YouTubeProbe.canonicalWatchUrl] already was.
	 */
	videoId: String?,
	thumbnails: Boolean,
	onOpenVideo: (String) -> Unit,
	/**
	 * The rest of the row. The modifier handed in belongs on the **title alone**
	 * — it is what carries the tap, and it is empty when there is no proven id.
	 * Everything else in the row stays inert.
	 */
	details: @Composable ColumnScope.(titleModifier: Modifier) -> Unit,
) {
	val url = remember(videoId) { YouTubeProbe.canonicalWatchUrl(videoId) }
	val tap = url?.let { link ->
		Modifier.clickable(onClickLabel = "Open on YouTube") { onOpenVideo(link) }
	} ?: Modifier
	Row(verticalAlignment = Alignment.Top) {
		VideoThumbnail(videoId, enabled = thumbnails, modifier = tap)
		Spacer(Modifier.width(10.dp))
		Column(Modifier.weight(1f)) { details(tap) }
	}
}

@Composable
private fun HistoryList(
	recent: List<FinalizationRuntime.ScrobbleRecord>,
	mutedIds: Set<String>,
	thumbnails: Boolean,
	snaps: SnapComposerState,
	posts: SnapPostController,
	onOpenVideo: (String) -> Unit,
	onMute: (FinalizationRuntime.ScrobbleRecord) -> Unit,
) {
	// An open composer belongs to History being on screen, not to the session.
	//
	// The draft and the *choice of which card is open* are held above the tab
	// switch on purpose — that is what stops a half-typed Snap dying when you
	// look at Settings. But the expansion is a view state, and nothing was
	// telling it that History had gone away, so coming back re-drew the
	// composer still open on top of a card you left minutes ago.
	//
	// Collapsing here ties it to the one thing it should follow: this list
	// being composed. `collapse` flushes before it clears, so the draft is
	// saved by leaving rather than lost by it, and the card comes back with its
	// Draft mark, waiting for Snap. Above the empty-list return so that leaving
	// an empty History collapses too.
	DisposableEffect(Unit) {
		onDispose { snaps.collapse() }
	}
	if (recent.isEmpty()) {
		Text(
			"No scrobbles yet.\n\nWith automatic scrobbling on, a track is " +
				"broadcast once it passes the threshold and then ends.",
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.padding(top = 24.dp),
		)
		return
	}
	val dark = LocalWaxDark.current
	// The header only gets to say "on-chain" when every row is. A queued or
	// rejected row makes that a claim about entries that aren't there, and the
	// whole point of this pair of tabs is that the app doesn't do that.
	val unsettled = recent.count { it.queued || it.status.startsWith("rejected") }
	Column {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier.padding(bottom = 8.dp),
		) {
			Icon(
				if (unsettled == 0) WaxIcons.CheckCircle else WaxIcons.Alert,
				contentDescription = null,
				tint = if (unsettled == 0) {
					if (dark) Wax.SuccessGreenLight else Wax.SuccessGreen
				} else {
					if (dark) Wax.AmberLight else Wax.Amber
				},
				modifier = Modifier.size(16.dp),
			)
			Spacer(Modifier.width(6.dp))
			Text(
				if (unsettled == 0) {
					"Confirmed on Hive"
				} else {
					"$unsettled not on-chain yet"
				},
				style = MaterialTheme.typography.labelMedium,
				color = if (unsettled == 0) {
					if (dark) Wax.SuccessGreenLight else Wax.SuccessGreen
				} else {
					if (dark) Wax.AmberLight else Wax.Amber
				},
				modifier = Modifier.weight(1f),
			)
			Text(
				"${recent.size} items",
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
			// Keyed by the row's own identity, because rows are *prepended*: a
			// new scrobble lands at index 0 and shifts every existing row down
			// one. Identified by position, Compose hands the composition that
			// was showing row N — and whatever it remembers — to the different
			// record that now sits at N. An open "Discard Snap?" would move to
			// another card that way, and answering it would delete a draft the
			// user never opened.
			//
			// `eventId` rather than video-plus-second: that pair can repeat
			// across two queued attempts on one video inside a single second,
			// and a duplicate key here is the same bug wearing a better name.
			items(recent, key = { it.eventId }) { r ->
				SettingCard {
					VideoLink(r.videoId, thumbnails, onOpenVideo) { titleModifier ->
						Text(
							r.artist?.let { "$it — ${r.title}" } ?: r.title,
							style = MaterialTheme.typography.titleSmall,
							modifier = titleModifier,
						)
						Text(
							"${r.percentPlayed}% · ${r.status}",
							style = MaterialTheme.typography.bodySmall,
							color = when {
								r.status.startsWith("rejected") ->
									MaterialTheme.colorScheme.error
								r.queued -> if (dark) Wax.AmberLight else Wax.Amber
								else -> if (dark) Wax.SuccessGreenLight else Wax.SuccessGreen
							},
						)
						r.txId?.let {
							Text(
								"tx $it",
								fontFamily = FontFamily.Monospace,
								fontSize = 9.sp,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
						}
					}
					// The escape hatch for promoted content no rule can identify.
					// Only offered where there's an id to key it on, and worded so
					// it's clear this can't undo the entry above it — nothing can.
					val id = r.videoId
					Spacer(Modifier.height(6.dp))
					SnapActionRow(
						record = r,
						muted = id in mutedIds,
						snaps = snaps,
						posts = posts,
						onMute = { onMute(r) },
					)
				}
			}
		}
	}
}

/**
 * The bottom of a History card: the composer when it is open, then the actions.
 *
 * Both halves of the row are additions *around* the existing mute control, not a
 * change to it. Don't scrobble is the same one-way call on the same record with
 * the same wording once taken — only its label and its icon are new, because the
 * mockup needs room for a second action beside it.
 *
 * Nothing in here can post. Stage 1 draws the Post state and stops.
 */
/** A short line under the composer explaining the last attempt. */
@Composable
private fun SnapNotice(message: String) {
	Text(
		message,
		style = MaterialTheme.typography.bodySmall,
		color = if (LocalWaxDark.current) Wax.AmberLight else Wax.Amber,
		modifier = Modifier.padding(top = 6.dp),
	)
}

@Composable
private fun SnapActionRow(
	record: FinalizationRuntime.ScrobbleRecord,
	muted: Boolean,
	snaps: SnapComposerState,
	posts: SnapPostController,
	onMute: () -> Unit,
) {
	val key = snaps.key(record.eventId)
	val open = snaps.isExpanded(key)
	val draft = snaps.draft(key)
	// Bound to the draft it is asking about, the same as [postNotice] below.
	// The list key above already stops a composition being handed to another
	// record, but this dialog is the one control that destroys typed text, so
	// it does not rely on that alone: tie it to the key and a reused slot
	// cannot carry a live "Discard Snap?" onto a different event's draft.
	var confirmDiscard by remember(key) { mutableStateOf(false) }
	val status = posts.status(key)
	// History membership is the eligibility gate. The Snap carries the row's
	// verified video id and nothing else about the media — no title, no artist,
	// no kind, and no re-judging of a row the engine already finalized.
	val media = SnapMedia(videoId = record.videoId)

	// Expands from the Snap area, downward, with a short fade — and entirely
	// inside the card, which is why it is a child of the card's own column.
	AnimatedVisibility(
		visible = open,
		enter = fadeIn(tween(140)) + expandVertically(tween(180)),
		exit = fadeOut(tween(110)) + shrinkVertically(tween(150)),
	) {
		Column {
			SnapComposer(
				text = draft,
				onTextChange = { snaps.edit(key, it) },
				onClose = {
					// Empty closes at once; anything typed gets asked about.
					if (draft.isEmpty()) snaps.collapse() else confirmDiscard = true
				},
			)
			// Whatever the last attempt said. Failure and ambiguity read
			// differently on purpose: one invites another go, the other explicitly
			// does not.
			when (val st = status) {
				is SnapPostStatus.Failed -> SnapNotice(st.message)
				is SnapPostStatus.Uncertain -> SnapNotice(st.message)
				else -> Unit
			}
			Spacer(Modifier.height(8.dp))
		}
	}

	// Posted. Per the spec this shows *only* what the user typed — the YouTube
	// link and the hashtags RustedWax appends are real on chain, and deliberately
	// not shown back here.
	(status as? SnapPostStatus.Posted)?.let { posted ->
		Column(modifier = Modifier.padding(bottom = 6.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Icon(
					WaxIcons.SpeechBubble,
					contentDescription = null,
					tint = if (LocalWaxDark.current) Wax.AmberLight else Wax.Amber,
					modifier = Modifier.size(13.dp),
				)
				Spacer(Modifier.width(5.dp))
				Text(
					"Snapped to Hive",
					style = MaterialTheme.typography.labelMedium,
					color = if (LocalWaxDark.current) Wax.AmberLight else Wax.Amber,
				)
			}
			Text(
				posted.contentId,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}

	// The subtle mark on a collapsed card that still holds typed text. Deliberately
	// quiet: it is a reminder, not a call to action.
	if (!open && draft.isNotEmpty()) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier.padding(bottom = 6.dp),
		) {
			Icon(
				WaxIcons.SpeechBubble,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(13.dp),
			)
			Spacer(Modifier.width(5.dp))
			Text(
				"Draft",
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}

	Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		when {
			// One root Snap per History event. Once it is on chain this card
			// stops offering to make another.
			status is SnapPostStatus.Posted -> WaxOutlinedButton(
				onClick = {},
				enabled = false,
				icon = WaxIcons.SpeechBubble,
				modifier = Modifier.weight(1f),
			) {
				Text("Snapped")
			}

			// Unknown outcome. The only thing offered is another *read* — posting
			// again could duplicate a comment that is already live.
			status is SnapPostStatus.Uncertain -> WaxOutlinedButton(
				onClick = { posts.recheck(key, record.eventId) },
				icon = WaxIcons.Send,
				modifier = Modifier.weight(1f),
			) {
				Text("Check again")
			}

			open -> WaxOutlinedButton(
				onClick = {
					posts.post(key, record.eventId, media, draft) {
						// The draft is only destroyed once Hive has confirmed the
						// Snap — never optimistically.
						snaps.discard(key)
					}
				},
				// Locked while in flight, so a second tap cannot start a second
				// broadcast.
				enabled = SnapText.isValid(draft) && !posts.isBusy(key),
				icon = WaxIcons.Send,
				modifier = Modifier.weight(1f),
			) {
				Text(if (posts.isBusy(key)) "Posting…" else "Post")
			}

			else -> WaxOutlinedButton(
				onClick = { snaps.open(key) },
				selected = true,
				icon = WaxIcons.SpeechBubble,
				modifier = Modifier.weight(1f),
			) {
				Text("Snap")
			}
		}

		if (muted) {
			// Unchanged from before Snaps: the same sentence, in the same place
			// in the flow, once the video has been muted.
			Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
				Text(
					"Muted — this video won't scrobble again",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.primary,
				)
			}
		} else {
			WaxOutlinedButton(
				onClick = onMute,
				icon = WaxIcons.Blocked,
				iconTint = MaterialTheme.colorScheme.primary,
				modifier = Modifier.weight(1f),
			) {
				Text("Don't scrobble")
			}
		}
	}

	if (confirmDiscard) {
		DiscardSnapDialog(
			onKeepEditing = { confirmDiscard = false },
			onDiscard = {
				confirmDiscard = false
				snaps.discard(key)
			},
		)
	}
}

/**
 * Tracks that finished and didn't become entries.
 *
 * The counterpart to History, and the reason it exists: without it, a strict
 * rule and a broken app look identical from the outside. Every row carries the
 * reason the engine already computed, so "where are my entries?" is answerable
 * without pulling the log off the device.
 */
@Composable
private fun SkippedList(
	skipped: List<FinalizationRuntime.SkipRecord>,
	thumbnails: Boolean,
	onOpenVideo: (String) -> Unit,
) {
	if (skipped.isEmpty()) {
		Text(
			"Nothing skipped yet.\n\nTracks that finish without being scrobbled " +
				"show up here with the reason — too short, not watched far " +
				"enough, or already logged.",
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.padding(top = 24.dp),
		)
		return
	}
	Column {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier.padding(bottom = 8.dp),
		) {
			Text(
				"Seen and declined",
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.primary,
				modifier = Modifier.weight(1f),
			)
			Text(
				"${skipped.size} items",
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
			items(skipped) { s ->
				SettingCard {
					// A Not-logged row is still playback history. An unresolved
					// refusal draws here too, without a link: the tab exists to
					// answer "why wasn't this scrobbled", and the answer is owed
					// whether or not identity ever resolved.
					VideoLink(s.videoId, thumbnails, onOpenVideo) { titleModifier ->
						Text(
							s.artist?.let { "$it — ${s.title}" } ?: s.title,
							style = MaterialTheme.typography.titleSmall,
							maxLines = 2,
							overflow = TextOverflow.Ellipsis,
							modifier = titleModifier,
						)
						// Pink, not brown: this is the reason the row exists,
						// and the design gives the accent to what you came to
						// read rather than to decoration.
						Text(
							s.reason,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.primary,
						)
						Text(
							"played ${s.playedSeconds}s" +
								(s.durationSeconds?.let { " of ${it}s" } ?: " · length unknown"),
							style = MaterialTheme.typography.bodySmall,
							fontSize = 10.sp,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			}
		}
	}
}

@Composable
private fun AccessBanner(onGrantAccess: () -> Unit) {
	Card(
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 8.dp),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.errorContainer,
		),
	) {
		Column(Modifier.padding(12.dp)) {
				Text("Notification Access required", style = MaterialTheme.typography.titleSmall)
				Text(
					"Android gates media-session access behind this grant. " +
						"RustedWax reads media-notification title, text, and site labels " +
						"from Brave and Chrome only. If you opt into a native YouTube app, " +
						"it reads that package's MediaSession metadata/state, not its " +
						"notification contents. Every other app is ignored.",
				style = MaterialTheme.typography.bodySmall,
			)
			Spacer(Modifier.height(8.dp))
			WaxOutlinedButton(onClick = onGrantAccess) { Text("Open settings") }
		}
	}
}

@Composable
private fun SessionList(
	sessions: () -> List<SessionSnapshot>,
	monitoring: Boolean,
	thresholdPercent: Int,
	autoScrobble: Boolean,
) {
	// This is the ownership boundary for the live read. When Now is not composed,
	// session progress cannot invalidate Settings, History, Not logged or Log.
	val liveSessions = sessions()

	if (!monitoring) {
		Text(
			"Monitoring is stopped.\n\nNo media sessions are being watched and no " +
				"notifications are being read. Press Start above to resume.",
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier
				.fillMaxSize()
				.padding(top = 24.dp),
		)
		return
	}
	if (liveSessions.isEmpty()) {
		Text(
			"No active media session. Play something in the YouTube app or YouTube Music.",
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier
				.fillMaxSize()
				.padding(top = 24.dp),
		)
		return
	}
	LazyColumn(
		verticalArrangement = Arrangement.spacedBy(8.dp),
		modifier = Modifier.fillMaxSize(),
	) {
		items(liveSessions, key = { it.packageName + it.title }) { s ->
			SessionCard(s, thresholdPercent, autoScrobble)
		}
	}
}

@Composable
private fun SessionCard(
	s: SessionSnapshot,
	thresholdPercent: Int,
	autoScrobble: Boolean,
) {
	SettingCard {
		// Cache-only: the UI never starts network work. An independently
		// pre-resolved native id is observation authority only — finalization
		// re-verifies it before anything is dispatched.
		val preview = NowPreview.from(
			session = s,
			cachedFacts = FinalizationRuntime::cachedFacts,
			cachedMusicMatch = FinalizationRuntime::cachedMusicMatch,
		)
		val card = NowCard.from(
			session = s,
			durationMs = ScrobbleBuilder.effectiveDurationMs(s, preview.facts),
			identified = preview.videoId != null,
			kind = preview.payload?.kind,
			thresholdPercent = thresholdPercent,
			autoScrobble = autoScrobble,
		)

		Row(verticalAlignment = Alignment.CenterVertically) {
			WaxRowIcon(platformIcon(card.platform))
			Spacer(Modifier.width(10.dp))
			Text(
				card.platform.label,
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.weight(1f),
			)
			card.category?.let {
				Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
			}
		}

		Spacer(Modifier.height(8.dp))
		Text(
			card.title ?: "—",
			style = MaterialTheme.typography.titleSmall,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
		card.channel?.let {
			Text(
				it,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}

		// The bar is drawn only where there is a length to draw it against. A
		// progress bar pinned at zero because nothing knows the duration is a
		// claim that nothing has been played, which is a different thing.
		card.progress?.let { progress ->
			Spacer(Modifier.height(10.dp))
			LinearProgressIndicator(
				progress = { progress },
				modifier = Modifier.fillMaxWidth(),
			)
			Spacer(Modifier.height(4.dp))
			Row(Modifier.fillMaxWidth()) {
				Text(
					card.percentText.orEmpty(),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.weight(1f),
				)
				card.durationText?.let {
					Text(
						it,
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}

		card.status?.let {
			Spacer(Modifier.height(6.dp))
			Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
		}
	}
}

/** The drawn mark for each platform, from the set the settings rows use. */
private fun platformIcon(platform: NowCard.Platform): ImageVector = when (platform) {
	NowCard.Platform.YOUTUBE_MUSIC -> WaxIcons.MusicNote
	NowCard.Platform.BRAVE, NowCard.Platform.CHROME -> WaxIcons.Search
	else -> WaxIcons.PlayBox
}

@Composable
private fun LogList(lines: () -> List<String>, onExport: () -> Unit, onClear: () -> Unit) {
	Column {
		// `Export` used to be a conditional trailing button on the tab strip,
		// which needed a rule deciding when it was allowed to exist. It belongs
		// beside the other thing you do to a log, and the guarantee that it can
		// never attach an empty file now holds structurally: this page exists
		// only while a log does. The line count went with it — a number nobody
		// acts on, occupying the row the two buttons wanted.
		WaxButtonRow {
			WaxOutlinedButton(onClick = onClear, icon = WaxIcons.Trash) {
				Text("Clear log")
			}
			WaxOutlinedButton(onClick = onExport, icon = WaxIcons.Export) {
				Text("Export")
			}
		}
		Spacer(Modifier.height(6.dp))
		LazyColumn(
			modifier = Modifier
				.fillMaxSize()
				.clip(MaterialTheme.shapes.medium)
				.background(MaterialTheme.colorScheme.surfaceContainerHigh)
				.padding(vertical = 4.dp),
		) {
			items(lines().asReversed()) { line ->
				Text(
					line,
					fontFamily = FontFamily.Monospace,
					fontSize = 10.sp,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier
						.fillMaxWidth()
						.horizontalScroll(rememberScrollState())
						.padding(horizontal = 6.dp, vertical = 1.dp),
					maxLines = 1,
				)
			}
		}
	}
}
