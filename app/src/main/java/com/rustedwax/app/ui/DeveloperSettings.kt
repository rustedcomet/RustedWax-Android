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
