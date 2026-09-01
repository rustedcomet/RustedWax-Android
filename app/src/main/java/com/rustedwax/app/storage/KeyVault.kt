package com.rustedwax.app.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rustedwax.hive.HiveKey
import com.rustedwax.hive.PrivacySecret

/**
 * Stores the Hive username and posting key.
 *
 * The key sits in `EncryptedSharedPreferences` under an Android Keystore master
 * key, so it is encrypted at rest and the raw bytes never appear in a plain
 * preferences file. It is read only to sign, and never logged, never sent
 * anywhere, and never included in the exportable log.
 *
 * Not yet done (tracked in DEVELOPMENT_PLAN §7): a biometric/device-credential
 * gate on read. Right now anyone holding the unlocked phone can make the app
 * sign, which is the same exposure as an unlocked Keychain on desktop but
 * without the per-operation prompt. Do not put a key here that controls
 * anything you'd mind losing — prefer a dedicated posting key you can revoke.
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
	}

	/** Parse the stored key for signing. Returns null if nothing is stored. */
	fun loadKey(): HiveKey? = prefs.getString(KEY_WIF, null)?.let { HiveKey.fromWif(it) }

	/**
	 * The AES secret private scrobbles are encrypted under — §6.1.
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
	}

	private companion object {
		const val FILE_NAME = "rustedwax_keys"
		const val KEY_USERNAME = "username"
		const val KEY_WIF = "posting_wif"
		const val KEY_PUBLIC = "posting_pub"
	}
}
