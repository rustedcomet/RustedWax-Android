package com.rustedwax.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rustedwax.app.storage.Settings
import com.rustedwax.hive.HiveConnectionCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Taps on the version that unlock [SettingsRow.DEVELOPER_MODE]. */
const val DEVELOPER_TAPS = 7

/**
 * The version, and the way in to the developer tier.
 *
 * Deliberately one line. "About" screens accumulate — a licence, a link, a
 * credit, a build hash — and this one exists to answer "which build is this?"
 * and to be tapped.
 *
 * The unlock is unannounced on purpose: the tier behind it holds a plain-text
 * recording of what somebody watched and a switch that decides what reaches an
 * unerasable ledger, and neither is something to invite an ordinary install into
 * by putting a `Show` button next to it. Someone who needs it has been told how.
 *
 * @param onUnlock called once, on the seventh consecutive tap. The counter is
 * not reset by anything else on the screen; there is nothing here worth
 * protecting from a determined tapper, only from an accidental one.
 */
@Composable
fun AboutRow(version: String, unlocked: Boolean, onUnlock: () -> Unit) {
	var taps by remember { mutableIntStateOf(0) }
	SettingCard(
		modifier = Modifier.clickable {
			if (unlocked) return@clickable
			taps += 1
			if (taps >= DEVELOPER_TAPS) onUnlock()
		},
	) {
		Text("RustedWax version $version", style = MaterialTheme.typography.bodySmall)
	}
}

/**
 * Everything that used to be behind `Advanced`, and now is not offered at all
 * until somebody asks for it seven times.
 *
 * ## What happened to Advanced
 *
 * `Advanced` was a `Show`/`Hide` disclosure holding four privacy switches, the
 * Shorts rule and the event log. It was a second settings screen anybody could
 * open, and its contents were not *advanced* so much as *unasked* — nobody
 * arrives at a scrobbler wanting to configure per-kind envelope encryption.
 *
 * The privacy switches are **not deleted**: `Settings.privacyMusic` and its
 * three siblings, `PrivacyCipher`, `PrivateScrobble` and the broadcast path that
 * consults them are all untouched, and every stored answer is preserved. They
 * are off the menu because there is currently no case for them, which is a
 * decision about a menu and is meant to be cheap to reverse.
 *
 * What is here is what someone diagnosing this app actually reaches for: the
 * log, the Shorts rule that decides what a feed can put on-chain, and a
 * connection check that answers "can this device scrobble at all" without
 * broadcasting anything to find out.
 *
 * Reads and writes [Settings] directly, as its predecessor did — nothing else on
 * the screen depends on the Shorts switch, so hoisting it would add coupling to
 * gain nothing. `Event log` is the exception and is passed in: whether it is on
 * decides whether the `Log` destination exists at all, so the screen above has
 * to know the answer and two copies of it would be two answers.
 *
 * @param onLock puts the tier away again. The settings behind it keep their
 * values — locking changes what is *shown*, never what is recorded.
 * @param postingPublicKey derives the `STM…` **public** posting key from the
 * vault. A function rather than a value so the read happens off the main thread
 * inside the check, and so the private key never crosses into the UI at all.
 */
@Composable
fun DeveloperModeSection(
	eventLogging: Boolean,
	hiveUsername: String?,
	postingPublicKey: () -> String?,
	onToggleEventLogging: (Boolean) -> Unit,
	onLock: () -> Unit,
) {
	val context = LocalContext.current
	val settings = remember { Settings(context) }
	val scope = rememberCoroutineScope()

	var disableShorts by remember { mutableStateOf(settings.disableShorts) }
	var checking by remember { mutableStateOf(false) }
	var report by remember { mutableStateOf<HiveConnectionCheck.Report?>(null) }

	DeveloperCard {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier.fillMaxWidth(),
		) {
			Column(Modifier.weight(1f)) {
				Text("Developer mode", style = MaterialTheme.typography.titleSmall)
				Text(
					"The event log, the Shorts rule and a connection check.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			WaxOutlinedButton(onClick = onLock) { Text("Turn off") }
		}
	}

	// ── Logging ───────────────────────────────────────────────────────────
	//
	// One switch, and only one. There used to be a second, `Diagnostic logging`,
	// which wrote the same file on a 15-day timer and overrode this one while it
	// ran — so "is RustedWax logging?" had two answers and the wrong one was
	// visible. It was removed in v0.11.0 rather than reconciled: this control
	// already did everything it did, permanently and legibly.
	DeveloperToggle(
		title = "Event log",
		body = if (eventLogging) {
			"On — the app records what it observed, which is what makes a " +
				"problem diagnosable and what the Export button sends. The last " +
				"12 hours are kept, up to 512 KiB; older lines are discarded."
		} else {
			// Not "erased when you turned this off": on a fresh install this is
			// the default and nobody turned anything off. Erasure is stated as
			// what the switch does rather than as something that happened.
			"Off (default) — nothing is written anywhere, and the Log tab and " +
				"Export button are not shown. Turning this off also erases " +
				"whatever had already been recorded."
		},
		checked = eventLogging,
		onChange = onToggleEventLogging,
	)

	// ── Shorts ────────────────────────────────────────────────────────────
	//
	// One switch, and only one. It used to have a neighbour called `Short clips`
	// that decided whether a *verified public* Short could use the 10-second
	// floor, worded against this one on purpose and sitting right beside it —
	// and someone who turned the wrong one off believed they had stopped Shorts
	// while Shorts kept landing on a ledger that cannot be edited. The floor is
	// no longer a preference: a Short is proven public and gets 10 seconds, or
	// it is refused. So there is one question left, and it is the one people
	// actually have an opinion about.
	DeveloperToggle(
		title = "Disable Shorts",
		body = if (disableShorts) {
			"On (default) — nothing from a /shorts/ path is scrobbled, at any length."
		} else {
			"Off — a Short scrobbles once its watch page proves it is a real, " +
				"publicly listed video, from 10 seconds. One that cannot be proven " +
				"is refused rather than held to the ordinary 30 seconds, because " +
				"length alone does not tell a clip from a shorts-feed ad."
		},
		checked = disableShorts,
	) { value ->
		disableShorts = value
		settings.disableShorts = value
	}

	// ── Connection ────────────────────────────────────────────────────────
	//
	// The replacement for `Broadcast a test scrobble`, which answered the same
	// question by writing a listen that never happened onto a ledger that cannot
	// edit it. Four read-only questions instead, so a failure says *which* thing
	// is wrong rather than only that something is.
	DeveloperCard {
		Text("Test Hive connection", style = MaterialTheme.typography.titleSmall)
		Spacer(Modifier.height(2.dp))
		Text(
			"Checks node access, the account's posting authority and whether the " +
				"saved key can sign. Reads only — nothing is broadcast.",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Spacer(Modifier.height(8.dp))
		WaxButtonRow {
			WaxButton(
				onClick = {
					checking = true
					report = null
					scope.launch {
						val result = withContext(Dispatchers.IO) {
							val publicKey = runCatching { postingPublicKey() }.getOrNull()
							HiveConnectionCheck().run(hiveUsername, publicKey)
						}
						report = result
						checking = false
					}
				},
				enabled = !checking,
				icon = WaxIcons.Key,
			) {
				Text(if (checking) "Checking…" else "Run check")
			}
		}
		if (checking) {
			Spacer(Modifier.height(12.dp))
			CircularProgressIndicator(Modifier.height(24.dp))
		}
		report?.let { result ->
			Spacer(Modifier.height(12.dp))
			result.steps.forEach { step ->
				Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
					WaxRowIcon(
						if (step.ok) WaxIcons.CheckCircle else WaxIcons.Alert,
						alert = !step.ok,
					)
					Spacer(Modifier.width(10.dp))
					Column(Modifier.weight(1f)) {
						Text(step.name, style = MaterialTheme.typography.labelMedium)
						Text(
							step.detail,
							style = MaterialTheme.typography.bodySmall,
							color = if (step.ok) {
								MaterialTheme.colorScheme.onSurfaceVariant
							} else {
								MaterialTheme.colorScheme.error
							},
						)
					}
				}
			}
			Spacer(Modifier.height(6.dp))
			Text(
				result.summary,
				style = MaterialTheme.typography.labelMedium,
				color = if (result.ok) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.error
				},
			)
		}
	}
}

@Composable
private fun DeveloperToggle(
	title: String,
	body: String,
	checked: Boolean,
	enabled: Boolean = true,
	onChange: (Boolean) -> Unit,
) {
	DeveloperCard {
		Row(verticalAlignment = Alignment.Top) {
			Column(Modifier.weight(1f)) {
				Text(title, style = MaterialTheme.typography.titleSmall)
				Spacer(Modifier.height(2.dp))
				Text(
					body,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Spacer(Modifier.width(8.dp))
			Switch(
				checked = checked,
				onCheckedChange = onChange,
				enabled = enabled,
				colors = waxSwitchColors(),
			)
		}
	}
}

/** Matches the flat, hairline card the rest of the settings list uses. */
@Composable
private fun DeveloperCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
	Card(
		modifier = Modifier.fillMaxWidth(),
		shape = MaterialTheme.shapes.medium,
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
		elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
		border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
	) {
		Column(Modifier.padding(12.dp), content = content)
	}
}
