package com.rustedwax.app.scrobble

import android.content.SharedPreferences

/**
 * A `SharedPreferences` held in memory, so the real
 * [SharedPreferencesRetainedRecords] runs on the JVM. Edits land on `apply()`
 * or `commit()`, all at once, as Android's do in memory.
 */
internal class InMemorySharedPreferences : SharedPreferences {

	private val values = linkedMapOf<String, Any?>()

	@Synchronized override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)
	@Synchronized override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
	@Synchronized override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
	@Synchronized override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
	@Synchronized override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
	@Synchronized override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
	@Suppress("UNCHECKED_CAST")
	@Synchronized override fun getStringSet(key: String, defValues: MutableSet<String>?) =
		values[key] as? MutableSet<String> ?: defValues
	@Synchronized override fun contains(key: String) = key in values
	override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
	override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

	override fun edit(): SharedPreferences.Editor = Editor()

	private inner class Editor : SharedPreferences.Editor {
		private val pending = linkedMapOf<String, Any?>()
		private val removed = mutableSetOf<String>()
		private var clear = false

		override fun putString(key: String, value: String?) = apply { pending[key] = value }
		override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
		override fun putInt(key: String, value: Int) = apply { pending[key] = value }
		override fun putLong(key: String, value: Long) = apply { pending[key] = value }
		override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
		override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
		override fun remove(key: String) = apply { removed += key }
		override fun clear() = apply { clear = true }

		override fun commit(): Boolean {
			synchronized(this@InMemorySharedPreferences) {
				if (clear) values.clear()
				removed.forEach(values::remove)
				values.putAll(pending)
			}
			return true
		}

		override fun apply() {
			commit()
		}
	}
}
