package com.rustedwax.app.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the YouTube web session the user signed in with, and nothing else.
 *
 * ## Why this is the most sensitive thing the app holds
 *
 * A Google session cookie is a bearer credential for a whole account. It is
 * strictly more dangerous than the Hive posting key next to it in [KeyVault],
 * which authorises one operation on one chain. So it gets at least the same
 * treatment and a few extra rules on top:
 *
 * - **Encrypted at rest.** `EncryptedSharedPreferences` under an Android
 *   Keystore master key, same as the posting key.
 * - **Never logged.** No method here returns the cookie to anything that logs.
 *   [session] deliberately exposes only a display label and a timestamp, so the
 *   UI and the event log cannot accidentally be handed the credential; the one
 *   caller that needs the real value asks for it by a name that says so.
 * - **Never sent anywhere but youtube.com.** Enforced at the one call site that
 *   attaches it, not by convention — see `WatchHistoryFetcher`.
 * - **Clearable.** [forget] wipes it, and the UI offers that unconditionally.
 *
 * The app never sees the password: the user signs in to Google themselves in a
 * WebView, and only the resulting cookie jar is harvested.
 */
class YouTubeSessionVault internal constructor(store: () -> SharedPreferences) {

	constructor(context: Context) : this({
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
	})

	/**
	 * Lazy so the Keystore is not unlocked until something actually asks, and
	 * injectable so the read-count rule below is decidable without an Android
	 * Keystore — the same seam `HiveConnectionCheck` uses for its RPCs.
	 */
	private val prefs: SharedPreferences by lazy(store)

	@Volatile
	private var cached: CachedSession? = null

	/** A held answer, including a held "there is no session". */
	private class CachedSession(val value: Session?)

	private fun invalidate() {
		cached = null
	}

	/** Everything about the session that is safe to display or log. */
	data class Session(
		/** The account YouTube named for this session, when the page gave one. */
		val accountLabel: String?,
		val signedInAtEpochSec: Long,
		val lastRefreshedAtEpochSec: Long,
	)

	val session: Session?
		get() {
			cached?.let { return it.value }
			val read = if (prefs.getString(KEY_COOKIES, null).isNullOrBlank()) {
				null
			} else {
				Session(
					accountLabel = prefs.getString(KEY_ACCOUNT, null),
					signedInAtEpochSec = prefs.getLong(KEY_SIGNED_IN_AT, 0),
					lastRefreshedAtEpochSec = prefs.getLong(KEY_REFRESHED_AT, 0),
				)
			}
			cached = CachedSession(read)
			return read
		}

	val hasSession: Boolean get() = session != null

	/**
	 * The credential itself. Named to make an accidental log line read wrong.
	 * Only the youtube.com fetcher may call this.
	 */
	fun secretCookieHeader(): String? =
		prefs.getString(KEY_COOKIES, null)?.takeIf { it.isNotBlank() }

	fun save(cookieHeader: String, accountLabel: String?) {
		val now = System.currentTimeMillis() / 1000
		prefs.edit()
			.putString(KEY_COOKIES, cookieHeader.trim())
			.putString(KEY_ACCOUNT, accountLabel)
			.putLong(KEY_SIGNED_IN_AT, now)
			.putLong(KEY_REFRESHED_AT, now)
			.apply()
		invalidate()
	}

	/**
	 * Fold `Set-Cookie` rotations back in, so a session that YouTube keeps alive
	 * on its own does not expire here for want of writing the new value down.
	 * Unknown names are added; known ones are replaced; nothing is removed.
	 */
	fun mergeRotatedCookies(updates: Map<String, String>) {
		if (updates.isEmpty()) return
		val current = secretCookieHeader() ?: return
		val merged = LinkedHashMap<String, String>()
		parseCookieHeader(current).forEach { (name, value) -> merged[name] = value }
		updates.forEach { (name, value) -> merged[name] = value }
		prefs.edit()
			.putString(KEY_COOKIES, merged.entries.joinToString("; ") { "${it.key}=${it.value}" })
			.putLong(KEY_REFRESHED_AT, System.currentTimeMillis() / 1000)
			.apply()
		invalidate()
	}

	fun rememberAccountLabel(label: String?) {
		if (label.isNullOrBlank()) return
		prefs.edit().putString(KEY_ACCOUNT, label).apply()
		invalidate()
	}

	fun forget() {
		prefs.edit().clear().apply()
		invalidate()
	}

	companion object {
		/** `a=1; b=2` → `{a=1, b=2}`. Values are opaque and never inspected. */
		fun parseCookieHeader(header: String): Map<String, String> =
			header.split(';').mapNotNull { part ->
				val trimmed = part.trim()
				val eq = trimmed.indexOf('=')
				if (eq <= 0) null else trimmed.substring(0, eq) to trimmed.substring(eq + 1)
			}.toMap()

		/**
		 * Cookie names that make a jar an actual signed-in session.
		 *
		 * Harvesting anything less than one of these would store a cookie jar
		 * that cannot read history and then report success.
		 */
		val SESSION_COOKIES = listOf("__Secure-3PSID", "__Secure-1PSID", "SID", "LOGIN_INFO")

		fun looksSignedIn(cookieHeader: String?): Boolean {
			val names = parseCookieHeader(cookieHeader ?: return false).keys
			return SESSION_COOKIES.any { it in names }
		}

		private const val FILE_NAME = "rustedwax_youtube_session"
		private const val KEY_COOKIES = "youtube_cookie_header"
		private const val KEY_ACCOUNT = "youtube_account_label"
		private const val KEY_SIGNED_IN_AT = "youtube_signed_in_at"
		private const val KEY_REFRESHED_AT = "youtube_refreshed_at"
	}
}
