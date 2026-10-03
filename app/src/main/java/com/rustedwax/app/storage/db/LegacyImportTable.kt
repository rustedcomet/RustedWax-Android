package com.rustedwax.app.storage.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** One row of `legacy_import`: how far one legacy SharedPreferences source has been brought in. */
internal data class LegacyImportRecord(
	val source: String,
	val state: LegacyImportState,
	val parserVersion: Int,
	val fingerprint: String?,
	/** The newest legacy write generation this database has absorbed for the source. */
	val generation: Long,
	val seen: Int,
	val imported: Int,
	val rejected: Int,
	val updatedAtMillis: Long,
)

/**
 * Reading and writing `legacy_import` rows, shared by every store that
 * imports a legacy source (Issue #41) so they cannot disagree on its shape.
 */
internal object LegacyImportTable {

	fun read(db: SQLiteDatabase, source: String): LegacyImportRecord? =
		db.query(LocalDatabaseSchema.LEGACY_IMPORT, null, "source = ?", arrayOf(source), null, null, null).use { c ->
			if (!c.moveToFirst()) return@use null
			val state = LegacyImportState.entries
				.firstOrNull { it.name == c.getString(c.getColumnIndexOrThrow("state")) }
				?: return@use null
			val fingerprint = c.getColumnIndexOrThrow("fingerprint").let { if (c.isNull(it)) null else c.getString(it) }
			LegacyImportRecord(
				source = source,
				state = state,
				parserVersion = c.getInt(c.getColumnIndexOrThrow("parser_version")),
				fingerprint = fingerprint,
				generation = c.getLong(c.getColumnIndexOrThrow("generation")),
				seen = c.getInt(c.getColumnIndexOrThrow("seen")),
				imported = c.getInt(c.getColumnIndexOrThrow("imported")),
				rejected = c.getInt(c.getColumnIndexOrThrow("rejected")),
				updatedAtMillis = c.getLong(c.getColumnIndexOrThrow("updated_at")),
			)
		}

	fun put(db: SQLiteDatabase, record: LegacyImportRecord) {
		val values = ContentValues().apply {
			put("source", record.source)
			put("state", record.state.name)
			put("parser_version", record.parserVersion)
			put("fingerprint", record.fingerprint)
			put("generation", record.generation)
			put("seen", record.seen)
			put("imported", record.imported)
			put("rejected", record.rejected)
			put("updated_at", record.updatedAtMillis)
		}
		db.insertWithOnConflict(LocalDatabaseSchema.LEGACY_IMPORT, null, values, SQLiteDatabase.CONFLICT_REPLACE)
	}
}
