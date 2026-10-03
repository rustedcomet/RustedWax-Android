package com.rustedwax.app.storage

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.SharedPreferences
import android.database.Cursor
import com.rustedwax.app.storage.db.LegacyImportRecord
import com.rustedwax.app.storage.db.LegacyImportTable
import com.rustedwax.app.storage.db.LocalDatabase

/**
 * A [LegacyKeyedSource] over one SharedPreferences file — the file the store
 * has always used, with its keys and value formats unchanged.
 *
 * The file's generation is one extra entry, [generationKey], stored as a
 * string. A string rather than a long because older builds read some of these
 * files with `getString` over every key; a long there would throw, a string is
 * merely an entry they do not recognise.
 *
 * @param toMirrorKey the mirror key for a stored key, or null when that stored
 *   key belongs to another source sharing the file.
 */
internal class SharedPreferencesKeyedSource<V>(
	private val prefs: SharedPreferences,
	private val generationKey: String,
	private val toMirrorKey: (String) -> String?,
	private val toStoredKey: (String) -> String,
	private val decode: (Any?) -> V?,
	private val encode: (SharedPreferences.Editor, String, V) -> Unit,
) : LegacyKeyedSource<V> {

	override fun readAll(): LegacyKeyedState<V> {
		val all = runCatching { prefs.all }.getOrDefault(emptyMap<String, Any?>())
		val entries = LinkedHashMap<String, V>()
		val raw = HashMap<String, String>()
		var seen = 0
		var rejected = 0
		val rejectedKeys = HashSet<String>()
		for ((stored, value) in all) {
			if (stored == generationKey) continue
			val key = toMirrorKey(stored) ?: continue
			seen++
			raw[stored] = value.toString()
			val decoded = runCatching { decode(value) }.getOrNull()
			if (decoded == null) { rejected++; rejectedKeys += key } else entries[key] = decoded
		}
		return LegacyKeyedState(entries, rejected, seen, generation(), LocalMirror.fingerprint(raw), rejectedKeys)
	}

	override fun generation(): Long =
		runCatching { prefs.getString(generationKey, null)?.toLongOrNull() }.getOrNull() ?: 0L

	// `commit()` only when the caller asked for a synchronous, checked write — the
	// notice store's promise that a recorded reply survives before it is announced.
	@SuppressLint("ApplySharedPref")
	override fun writeAll(changes: List<Pair<String, V?>>, generation: Long?, commit: Boolean): Boolean =
		runCatching {
			val edit = prefs.edit()
			changes.forEach { (key, value) ->
				if (value == null) edit.remove(toStoredKey(key)) else encode(edit, toStoredKey(key), value)
			}
			if (generation != null) edit.putString(generationKey, generation.toString())
			if (commit) edit.commit() else edit.apply().let { true }
		}.getOrDefault(false)
}

/**
 * How one mirrored source maps onto its table: which columns form the key,
 * how a mirror key splits into them, and how a value becomes the rest.
 */
internal class MirrorTableSpec<V>(
	val table: String,
	val keyColumns: List<String>,
	val split: (String) -> List<String>,
	val join: (List<String>) -> String,
	val write: (V, ContentValues) -> Unit,
	val read: (Cursor) -> V?,
)

/** [MirrorRows] over the real database. Mapping only; decides nothing. */
internal class SqliteMirrorRows<V>(
	private val database: LocalDatabase,
	private val source: String,
	private val spec: MirrorTableSpec<V>,
) : MirrorRows<V> {

	private val where = spec.keyColumns.joinToString(" AND ") { "$it = ?" }

	override fun <T> transaction(block: (MirrorTx<V>) -> T): T = database.write { db ->
		block(object : MirrorTx<V> {
			override fun all(): Map<String, V> =
				db.query(spec.table, null, null, null, null, null, null).use { c ->
					buildMap {
						while (c.moveToNext()) {
							val key = spec.join(spec.keyColumns.map { c.getString(c.getColumnIndexOrThrow(it)) })
							runCatching { spec.read(c) }.getOrNull()?.let { put(key, it) }
						}
					}
				}

			override fun put(key: String, value: V) {
				val parts = spec.split(key)
				val values = ContentValues().apply {
					spec.keyColumns.zip(parts).forEach { (column, part) -> put(column, part) }
					spec.write(value, this)
				}
				if (db.update(spec.table, values, where, parts.toTypedArray()) == 0) {
					db.insertOrThrow(spec.table, null, values)
				}
			}

			override fun delete(key: String) {
				db.delete(spec.table, where, spec.split(key).toTypedArray())
			}

			override fun importRecord(): LegacyImportRecord? = LegacyImportTable.read(db, source)

			override fun putImportRecord(record: LegacyImportRecord) = LegacyImportTable.put(db, record)
		})
	}
}
