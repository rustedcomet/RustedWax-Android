package com.rustedwax.app.scrobble

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.rustedwax.app.storage.db.LegacyImportRecord
import com.rustedwax.app.storage.db.LegacyImportTable
import com.rustedwax.app.storage.db.LocalDatabase
import com.rustedwax.app.storage.db.LocalDatabaseSchema

/**
 * [RetainedRecordRows] over the real database. Pure mapping between rows and
 * columns: every rule lives in the callers, and this class decides nothing.
 *
 * Plain `UPDATE`-then-`INSERT` rather than SQLite's upsert syntax, which needs
 * a newer SQLite than this app's minimum Android version ships.
 *
 * Ordering is `at` descending, then the implicit `rowid` descending — the
 * most recently inserted row first among rows filed in the same second.
 */
internal class SqliteRetainedRecordRows(private val database: LocalDatabase) : RetainedRecordRows {

	override fun <T> transaction(block: (RetainedRecordTx) -> T): T =
		database.write { db -> block(Tx(db)) }

	private class Tx(private val db: SQLiteDatabase) : RetainedRecordTx {

		override fun insertHistoryIfAbsent(row: FinalizationRuntime.ScrobbleRecord): Boolean {
			val values = historyValues(row) ?: return false
			return db.insertWithOnConflict(HISTORY, null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
		}

		override fun writeHistory(row: FinalizationRuntime.ScrobbleRecord) {
			val values = historyValues(row) ?: return
			val rowId = RetainedOwners.rowId(row)
			row.queueOperationId?.let { operation ->
				db.delete(
					HISTORY,
					"owner = ? AND queue_op_id = ? AND row_id <> ?",
					arrayOf(values.getAsString("owner"), operation, rowId),
				)
			}
			if (db.update(HISTORY, values, "row_id = ?", arrayOf(rowId)) == 0) {
				db.insertOrThrow(HISTORY, null, values)
			}
		}

		override fun historyRow(rowId: String): FinalizationRuntime.ScrobbleRecord? =
			db.query(HISTORY, null, "row_id = ?", arrayOf(rowId), null, null, null).use {
				if (it.moveToFirst()) history(it) else null
			}

		override fun historyRowForQueueOperation(owner: String, operationId: String): String? =
			db.query(HISTORY, arrayOf("row_id"), "owner = ? AND queue_op_id = ?", arrayOf(owner, operationId), null, null, null)
				.use { if (it.moveToFirst()) it.getString(0) else null }

		override fun historyOwners(): List<String> = owners(HISTORY)

		override fun history(owner: String, limit: Int): List<FinalizationRuntime.ScrobbleRecord> =
			newest(HISTORY, owner, limit).use { c -> buildList { while (c.moveToNext()) history(c)?.let(::add) } }

		override fun pruneHistory(owner: String, keep: Int) = prune(HISTORY, owner, keep)

		override fun insertSkippedIfAbsent(row: FinalizationRuntime.SkipRecord): Boolean =
			db.insertWithOnConflict(NOT_LOGGED, null, skippedValues(row), SQLiteDatabase.CONFLICT_IGNORE) != -1L

		override fun writeSkipped(row: FinalizationRuntime.SkipRecord) {
			val values = skippedValues(row)
			if (db.update(NOT_LOGGED, values, "row_id = ?", arrayOf(row.rowId)) == 0) {
				db.insertOrThrow(NOT_LOGGED, null, values)
			}
		}

		override fun skippedRow(rowId: String): FinalizationRuntime.SkipRecord? =
			db.query(NOT_LOGGED, null, "row_id = ?", arrayOf(rowId), null, null, null).use {
				if (it.moveToFirst()) skipped(it) else null
			}

		override fun skippedOwners(): List<String> = owners(NOT_LOGGED)

		override fun skipped(owner: String, limit: Int): List<FinalizationRuntime.SkipRecord> =
			newest(NOT_LOGGED, owner, limit).use { c -> buildList { while (c.moveToNext()) skipped(c)?.let(::add) } }

		override fun pruneSkipped(owner: String, keep: Int) = prune(NOT_LOGGED, owner, keep)

		override fun importRecord(source: String): LegacyImportRecord? = LegacyImportTable.read(db, source)

		override fun putImportRecord(record: LegacyImportRecord) = LegacyImportTable.put(db, record)

		private fun owners(table: String): List<String> =
			db.rawQuery("SELECT DISTINCT owner FROM $table", null)
				.use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

		private fun newest(table: String, owner: String, limit: Int): Cursor =
			db.rawQuery(
				"SELECT * FROM $table WHERE owner = ? ORDER BY at DESC, rowid DESC LIMIT $limit",
				arrayOf(owner),
			)

		private fun prune(table: String, owner: String, keep: Int) {
			db.execSQL(
				"DELETE FROM $table WHERE owner = ? AND row_id NOT IN " +
					"(SELECT row_id FROM $table WHERE owner = ? ORDER BY at DESC, rowid DESC LIMIT $keep)",
				arrayOf<Any>(owner, owner),
			)
		}

		private fun historyValues(row: FinalizationRuntime.ScrobbleRecord): ContentValues? {
			val owner = RetainedOwners.of(row) ?: return null
			return ContentValues().apply {
				put("row_id", RetainedOwners.rowId(row))
				put("event_id", row.eventId)
				put("owner", owner)
				put("title", row.title)
				put("artist", row.artist)
				put("percent", row.percentPlayed)
				put("at", row.atEpochSec)
				put("status", row.status)
				put("tx_id", row.txId)
				put("queued", if (row.queued) 1 else 0)
				put("video_id", row.videoId)
				put("queue_op_id", row.queueOperationId)
			}
		}

		private fun skippedValues(row: FinalizationRuntime.SkipRecord) = ContentValues().apply {
			put("row_id", row.rowId)
			put("owner", RetainedOwners.of(row))
			put("title", row.title)
			put("artist", row.artist)
			put("reason", row.reason)
			put("at", row.atEpochSec)
			put("played", row.playedSeconds)
			put("duration", row.durationSeconds)
			put("video_id", row.videoId)
		}

		/** A stored History row, or null when one of its required columns is unusable. */
		private fun history(c: Cursor): FinalizationRuntime.ScrobbleRecord? = runCatching {
			FinalizationRuntime.ScrobbleRecord(
				title = c.string("title")!!,
				artist = c.string("artist"),
				percentPlayed = c.int("percent"),
				atEpochSec = c.long("at"),
				status = c.string("status")!!,
				txId = c.string("tx_id"),
				queued = c.int("queued") == 1,
				videoId = c.string("video_id")!!,
				eventId = c.string("event_id")!!,
				account = c.string("owner")!!,
				queueOperationId = c.string("queue_op_id"),
			)
		}.getOrNull()

		private fun skipped(c: Cursor): FinalizationRuntime.SkipRecord? = runCatching {
			FinalizationRuntime.SkipRecord(
				title = c.string("title")!!,
				artist = c.string("artist"),
				reason = c.string("reason")!!,
				atEpochSec = c.long("at"),
				playedSeconds = c.long("played"),
				durationSeconds = c.nullableLong("duration"),
				videoId = c.string("video_id"),
				account = RetainedOwners.skipAccount(c.string("owner")!!),
				rowId = c.string("row_id")!!,
			)
		}.getOrNull()

		private fun Cursor.string(column: String): String? =
			getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

		private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))

		private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

		private fun Cursor.nullableLong(column: String): Long? =
			getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
	}

	private companion object {
		const val HISTORY = LocalDatabaseSchema.HISTORY
		const val NOT_LOGGED = LocalDatabaseSchema.NOT_LOGGED
	}
}
