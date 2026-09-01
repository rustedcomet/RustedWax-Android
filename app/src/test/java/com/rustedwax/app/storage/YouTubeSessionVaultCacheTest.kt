package com.rustedwax.app.storage

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSessionVaultCacheTest {

	/** A `SharedPreferences` that counts reads and actually stores writes. */
	private class CountingPrefs : SharedPreferences {

		private val values = mutableMapOf<String, Any?>()
		var reads = 0
			private set

		fun resetCounts() {
			reads = 0
		}

		override fun getAll(): MutableMap<String, *> = values

		override fun getString(key: String?, defValue: String?): String? {
			reads++
			return values[key] as? String ?: defValue
		}

		override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues

		override fun getInt(key: String?, defValue: Int): Int {
			reads++
			return values[key] as? Int ?: defValue
		}

		override fun getLong(key: String?, defValue: Long): Long {
			reads++
			return values[key] as? Long ?: defValue
		}

		override fun getFloat(key: String?, defValue: Float): Float {
			reads++
			return values[key] as? Float ?: defValue
		}

		override fun getBoolean(key: String?, defValue: Boolean): Boolean {
			reads++
			return values[key] as? Boolean ?: defValue
		}

		override fun contains(key: String?): Boolean = values.containsKey(key)

		override fun registerOnSharedPreferenceChangeListener(
			listener: SharedPreferences.OnSharedPreferenceChangeListener?,
		) = Unit

		override fun unregisterOnSharedPreferenceChangeListener(
			listener: SharedPreferences.OnSharedPreferenceChangeListener?,
		) = Unit

		override fun edit(): SharedPreferences.Editor = Edit(values)

		private class Edit(
			private val target: MutableMap<String, Any?>,
		) : SharedPreferences.Editor {
			private val staged = mutableMapOf<String, Any?>()
			private var clear = false

			override fun putString(key: String, value: String?) = apply { staged[key] = value }
			override fun putStringSet(key: String, values: MutableSet<String>?) = apply {
				staged[key] = values
			}

			override fun putInt(key: String, value: Int) = apply { staged[key] = value }
			override fun putLong(key: String, value: Long) = apply { staged[key] = value }
			override fun putFloat(key: String, value: Float) = apply { staged[key] = value }
			override fun putBoolean(key: String, value: Boolean) = apply { staged[key] = value }
			override fun remove(key: String) = apply { staged[key] = null }
			override fun clear() = apply { clear = true }

			override fun commit(): Boolean {
				apply()
				return true
			}

			override fun apply() {
				if (clear) target.clear()
				staged.forEach { (key, value) ->
					if (value == null) target.remove(key) else target[key] = value
				}
			}
		}
	}

	private fun vaultWithSession(): Pair<YouTubeSessionVault, CountingPrefs> {
		val prefs = CountingPrefs()
		val vault = YouTubeSessionVault { prefs }
		vault.save("__Secure-3PSID=abc; SID=def", "someone")
		prefs.resetCounts()
		return vault to prefs
	}

	/** One second of polling per read is the shape this exists to stop. */
	private val ONE_MINUTE_OF_POLLING = 60

	@Test
	fun `sixty reads of the session decrypt once`() {
		val (vault, prefs) = vaultWithSession()

		repeat(ONE_MINUTE_OF_POLLING) { assertNotNull(vault.session) }

		assertEquals(
			"the status poll decrypted the session ${prefs.reads / 4} times for " +
				"$ONE_MINUTE_OF_POLLING reads; on the device that is an Android " +
				"Keystore unlock every second on the main thread",
			4,
			prefs.reads,
		)
	}

	/** An absent session is an answer, and holding it must not re-ask either. */
	@Test
	fun `sixty reads of an absent session read once`() {
		val prefs = CountingPrefs()
		val vault = YouTubeSessionVault { prefs }

		repeat(ONE_MINUTE_OF_POLLING) { assertNull(vault.session) }

		assertEquals("an absent session was re-read", 1, prefs.reads)
	}

	// ── negative controls: the cache may never outlive a writer ──────────

	@Test
	fun `signing in is visible to the next read`() {
		val prefs = CountingPrefs()
		val vault = YouTubeSessionVault { prefs }
		assertNull(vault.session)

		vault.save("SID=abc", "someone")

		assertEquals("someone", vault.session?.accountLabel)
	}

	@Test
	fun `forgetting is visible to the next read`() {
		val (vault, _) = vaultWithSession()
		assertNotNull(vault.session)

		vault.forget()

		assertNull("a wiped session survived in the cache", vault.session)
	}

	@Test
	fun `a relabelled account is visible to the next read`() {
		val (vault, _) = vaultWithSession()
		assertEquals("someone", vault.session?.accountLabel)

		vault.rememberAccountLabel("somebody else")

		assertEquals("somebody else", vault.session?.accountLabel)
	}

	@Test
	fun `a cookie rotation is visible to the next read`() {
		val (vault, _) = vaultWithSession()
		val before = checkNotNull(vault.session).lastRefreshedAtEpochSec

		vault.mergeRotatedCookies(mapOf("SID" to "rotated"))

		val after = checkNotNull(vault.session).lastRefreshedAtEpochSec
		assertTrue(
			"a rotation did not reach the next read (before=$before after=$after)",
			after >= before,
		)
		assertTrue(
			"the rotated cookie was not stored",
			checkNotNull(vault.secretCookieHeader()).contains("SID=rotated"),
		)
	}

	/**
	 * The credential is deliberately **not** cached. Its residency must be
	 * exactly what it was before the cache existed.
	 */
	@Test
	fun `the cookie is decrypted on every call and never held`() {
		val (vault, prefs) = vaultWithSession()

		repeat(5) { assertNotNull(vault.secretCookieHeader()) }

		assertEquals(
			"the credential is being cached, which is not what this cache is for",
			5,
			prefs.reads,
		)
	}
}
