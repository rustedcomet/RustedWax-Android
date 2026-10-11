package com.rustedwax.app.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rustedwax.hive.HiveKey
import com.rustedwax.hive.PrivacySecret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Stores the Hive username and posting key.
 *
 * The key sits in `EncryptedSharedPreferences` under an Android Keystore master
 * key, so it is encrypted at rest and the raw bytes never appear in a plain
 * preferences file. It is read only to sign, and never logged, never sent
 * anywhere, and never included in the exportable log.
 *
 * There is no biometric or device-credential prompt on each read. Anyone with
 * control of an unlocked phone may therefore cause the app to sign while
 * automatic scrobbling is enabled. Use only a posting-authority key, preferably
 * a dedicated key that can be revoked independently.
 */
class KeyVault(context: Context) {

	private val prefs: SharedPreferences by lazy {
		val masterKey = MasterKey.Builder(context)
			.setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
			.build()
		EncryptedSharedPreferences.create(
			context,
			FILE_NAME,
			masterKey,
			EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
			EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
		)
	}

	data class Account(
		val username: String,
		/** The `STM…` form, safe to display — it's public by definition. */
		val publicKey: String,
	)

	val account: Account?
		get() {
			val username = prefs.getString(KEY_USERNAME, null) ?: return null
			val publicKey = prefs.getString(KEY_PUBLIC, null) ?: return null
			return Account(username, publicKey)
		}

	val hasKey: Boolean get() = account != null

	fun save(username: String, wif: String, publicKey: String) {
		prefs.edit()
			.putString(KEY_USERNAME, username.lowercase().trim())
			.putString(KEY_WIF, wif.trim())
			.putString(KEY_PUBLIC, publicKey)
			.apply()
		HiveAccountChanges.changed()
	}

	/** Parse the stored key for signing. Returns null if nothing is stored. */
	fun loadKey(): HiveKey? = prefs.getString(KEY_WIF, null)?.let { HiveKey.fromWif(it) }

	/**
	 * The AES secret used to encrypt private scrobbles.
	 *
	 * Derived here rather than cached anywhere, because the derivation is a
	 * deterministic signature over a fixed challenge: recomputing it costs one
	 * ECDSA operation and avoids keeping a second long-lived secret on disk. The
	 * extension caches only because it cannot sign without prompting the user.
	 *
	 * The WIF never leaves this class.
	 */
	fun privacySecret(): ByteArray? =
		prefs.getString(KEY_WIF, null)?.let(PrivacySecret::derive)

	fun forget() {
		prefs.edit().clear().apply()
		HiveAccountChanges.changed()
	}

	private companion object {
		const val FILE_NAME = "rustedwax_keys"
		const val KEY_USERNAME = "username"
		const val KEY_WIF = "posting_wif"
		const val KEY_PUBLIC = "posting_pub"
	}
}

/**
 * Says only that the stored Hive account may have changed — never which.
 *
 * Bumped after [KeyVault.save] and [KeyVault.forget] have applied, so a read
 * made after hearing it sees the new value. Exists for code that caches who
 * is signed in outside the activity — the status card (#4) — and must stop
 * trusting that cache the moment a key is saved or forgotten, rather than on
 * some later, unrelated event. It carries no account and changes no data.
 */
object HiveAccountChanges {
	private val _generation = MutableStateFlow(0L)
	val generation: StateFlow<Long> = _generation.asStateFlow()

	fun changed() {
		_generation.update { it + 1 }
	}
}
