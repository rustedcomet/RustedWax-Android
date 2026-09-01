package com.rustedwax.app.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rustedwax.app.storage.KeyVault

/**
 * The Hive account, as a settings row: enter a username and posting key,
 * validate it against the account's on-chain posting authority, store it
 * encrypted — and afterwards, inspect the connected identity or forget it.
 *
 * ## Why this is a settings row rather than a destination
 *
 * It was a whole tab, and for anyone who had already saved a key that tab was a
 * username, a public key and two buttons nobody presses twice — a sixth of the
 * strip's width, permanently, for a screen visited once. It is a *setting*, it
 * is read alongside the YouTube account setting, and it now sits directly beside
 * it. The move preserved validate-and-connect, the identity it shows,
 * forgetting the key, the posting-key disclosure, the busy spinner and the
 * error line. The synthetic test broadcast was removed later and replaced by
 * the read-only connection check in developer mode.
 *
 * ## Key secrecy is unchanged
 *
 * The key field is masked, the entered key goes straight to the vault, and the
 * stored key is never displayed again — the only value ever shown is the derived
 * `STM…` **public** posting key, which is public by definition.
 *
 * Collapsed by default once a key is saved, because at that point there is
 * nothing to do here; open by default when there is none, because then there is.
 */
@Composable
fun HiveAccountSection(
	account: KeyVault.Account?,
	busy: Boolean,
	status: String?,
	statusIsError: Boolean,
	onValidateAndSave: (username: String, wif: String) -> Unit,
	onForget: () -> Unit,
) {
	var expanded by remember { mutableStateOf(account == null) }
	SettingRow(
		icon = WaxIcons.Key,
		title = "Hive account",
		body = if (account == null) {
			"Not connected — nothing can be signed, so nothing can be broadcast. " +
				"Automatic scrobbling stays unavailable until a posting key is saved."
		} else {
			"Connected as @${account.username}. Scrobbles are signed on this device " +
				"and only the signed transaction is sent to a Hive node."
		},
		below = if (!expanded) {
			null
		} else {
			{
				Column(Modifier.padding(top = 12.dp)) {
					if (account != null) {
						SavedAccount(account, busy, onForget)
					} else {
						KeyEntry(busy, onValidateAndSave)
					}

					status?.let {
						Spacer(Modifier.height(12.dp))
						Text(
							it,
							style = MaterialTheme.typography.bodySmall,
							color = if (statusIsError) {
								MaterialTheme.colorScheme.error
							} else {
								MaterialTheme.colorScheme.onSurface
							},
						)
					}

					Spacer(Modifier.height(16.dp))
					SecurityNote()
				}
			}
		},
	) {
		WaxOutlinedButton(onClick = { expanded = !expanded }) {
			Text(
				when {
					expanded -> "Hide"
					account == null -> "Connect"
					else -> "Manage"
				},
			)
		}
	}
}

@Composable
private fun KeyEntry(busy: Boolean, onValidateAndSave: (String, String) -> Unit) {
	var username by remember { mutableStateOf("") }
	var wif by remember { mutableStateOf("") }

	Column {
		Text(
			"Brave on Android can't run Hive Keychain, so this app signs " +
				"scrobbles itself. The key is checked against your account's " +
				"on-chain posting authority before it's accepted.",
			style = MaterialTheme.typography.bodySmall,
		)
		Spacer(Modifier.height(16.dp))

		OutlinedTextField(
			value = username,
			onValueChange = { username = it },
			label = { Text("Hive username") },
			placeholder = { Text("without the @") },
			singleLine = true,
			enabled = !busy,
			shape = MaterialTheme.shapes.small,
			colors = waxFieldColors(),
			modifier = Modifier.fillMaxWidth(),
		)
		Spacer(Modifier.height(8.dp))
		OutlinedTextField(
			value = wif,
			onValueChange = { wif = it },
			label = { Text("Posting key") },
			placeholder = { Text("5…") },
			singleLine = true,
			enabled = !busy,
			visualTransformation = PasswordVisualTransformation(),
			shape = MaterialTheme.shapes.small,
			colors = waxFieldColors(),
			modifier = Modifier.fillMaxWidth(),
		)
		Spacer(Modifier.height(12.dp))
		Row {
			WaxButton(
				onClick = { onValidateAndSave(username, wif) },
				enabled = !busy && username.isNotBlank() && wif.isNotBlank(),
				icon = WaxIcons.Key,
			) {
				Text("Validate & save")
			}
			if (busy) {
				Spacer(Modifier.height(8.dp))
				CircularProgressIndicator(Modifier.padding(start = 12.dp).height(24.dp))
			}
		}
	}
}

/** Field borders answer in the accent, the same as everything else you touch. */
@Composable
private fun waxFieldColors() = OutlinedTextFieldDefaults.colors(
	focusedBorderColor = MaterialTheme.colorScheme.primary,
	unfocusedBorderColor = MaterialTheme.colorScheme.outline,
	focusedLabelColor = MaterialTheme.colorScheme.primary,
	cursorColor = MaterialTheme.colorScheme.primary,
)

@Composable
private fun SavedAccount(
	account: KeyVault.Account,
	busy: Boolean,
	onForget: () -> Unit,
) {
	Column {
		Card(
			modifier = Modifier.fillMaxWidth(),
			colors = CardDefaults.cardColors(
				containerColor = MaterialTheme.colorScheme.surface,
			),
			elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
			border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
		) {
			Column(Modifier.padding(12.dp)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					WaxRowIcon(WaxIcons.Key)
					Spacer(Modifier.width(10.dp))
					Column {
						Text("@${account.username}", style = MaterialTheme.typography.titleSmall)
						// §4.4. The value below is the derived `STM…` *public*
						// posting key, which is public by definition; the private
						// WIF is in EncryptedSharedPreferences and is never shown.
						// The old label read "posting key in use:", which invited
						// exactly the wrong reading of the string underneath it.
						Text(
							"Posting public key:",
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
				Spacer(Modifier.height(6.dp))
				Text(
					account.publicKey,
					fontFamily = FontFamily.Monospace,
					fontSize = 10.sp,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		Spacer(Modifier.height(12.dp))
		// The test broadcast that used to sit here is gone. It answered "does
		// this work?" by writing a listen that never happened onto a ledger that
		// cannot remove it, and it could only ever say *something* failed. The
		// replacement asks the same question in four read-only parts, and lives
		// in developer mode with the other diagnostics.
		WaxButtonRow {
			WaxOutlinedButton(onClick = onForget, enabled = !busy, icon = WaxIcons.Trash) {
				Text("Forget key")
			}
		}
		if (busy) {
			Spacer(Modifier.height(12.dp))
			CircularProgressIndicator(Modifier.height(24.dp))
		}
	}
}

@Composable
private fun SecurityNote() {
	Card(
		modifier = Modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceVariant,
		),
	) {
		Column(Modifier.padding(12.dp)) {
			Text("About your posting key", style = MaterialTheme.typography.titleSmall)
			Spacer(Modifier.height(4.dp))
			Text(
				"It's stored encrypted under the Android Keystore and never leaves " +
					"the device — only signed transactions are sent to a Hive node.\n\n" +
					"But a posting key can post, comment and vote as you, with no " +
					"per-action prompt. Consider adding a dedicated key to your posting " +
					"authority from desktop Keychain and using that one here, so you can " +
					"revoke it without rotating the key you use everywhere else.\n\n" +
					"This app never asks for your owner, active or memo key.",
				style = MaterialTheme.typography.bodySmall,
			)
		}
	}
}
