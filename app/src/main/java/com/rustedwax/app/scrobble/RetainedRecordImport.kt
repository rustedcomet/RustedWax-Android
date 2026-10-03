package com.rustedwax.app.scrobble

import com.rustedwax.app.storage.db.LegacyImportRecord
import com.rustedwax.app.storage.db.LegacyImportState

/**
 * Bringing the SharedPreferences History and Not Logged blobs into the
 * structured store (Issue #41).
 *
 * One legacy source at a time, each in **one transaction**:
 *
 *  1. No blob, or a blank one: nothing to do, nothing recorded.
 *  2. Fingerprint the raw bytes, and compare the blob's write generation with
 *     the newest one this database has absorbed for the source (see
 *     [LegacyRetainedBlobs]). If the source is already VERIFIED or UNREADABLE
 *     under the same fingerprint and parser version, and the blob is not
 *     newer, stop: an unchanged blob — readable or not — is not parsed again.
 *  3. A blob that is not a JSON array at all is recorded UNREADABLE and left
 *     exactly where it is.
 *  4. Otherwise decode it with the production codec, the same rules #31
 *     restores with, and bring every row in:
 *      - **the blob is newer** — written by a run that could not open the
 *        database — so each of its rows is written over the stored one;
 *      - **otherwise the database is newer or equal**, so only rows it does
 *        not hold are inserted and a stored row is never overwritten. An older
 *        blob cannot roll a row's status back.
 *  5. Verify inside the same transaction that every decoded row is now present
 *     under its own owner, and record VERIFIED, or IMPORTED when it is not.
 *     IMPORTED is retried on the next launch. The absorbed generation becomes
 *     the higher of the two.
 *
 * A failure anywhere rolls the whole source back, so an interrupted import
 * leaves either nothing or everything, and running it again is always safe.
 * The legacy blob is only ever read here — never rewritten, never removed.
 *
 * Nothing here can produce a playback event, a queue entry or a Hive write: it
 * reads bytes and writes rows, and the architecture tests hold it to that.
 */
internal object RetainedRecordImport {

	/** Bump when decoding changes, so blobs recorded UNREADABLE get another look. */
	const val PARSER_VERSION = 1

	/** The legacy preference keys, which are also the `legacy_import` source names. */
	const val HISTORY_SOURCE = "history_v1"
	const val SKIPPED_SOURCE = "not_logged_v1"

	sealed interface Result {
		/** No legacy bytes. */
		data object Absent : Result

		/** Already handled under this fingerprint, parser version and generation. */
		data class Unchanged(val state: LegacyImportState) : Result

		/** Not an array; recorded and left in place. */
		data object Unreadable : Result

		/**
		 * [written] rows were inserted, or — when [legacyNewer] — written over
		 * the stored ones.
		 */
		data class Imported(val written: Int, val verified: Boolean, val legacyNewer: Boolean = false) : Result
	}

	fun importHistory(rows: RetainedRecordRows, blob: LegacyBlob, nowMillis: Long): Result =
		importSource(
			rows = rows,
			source = HISTORY_SOURCE,
			blob = blob,
			nowMillis = nowMillis,
			decode = RetainedRecordCodec::decodeHistory,
			insert = { tx, row -> RetainedOwners.of(row) != null && tx.insertHistoryIfAbsent(row) },
			overwrite = { tx, row -> (RetainedOwners.of(row) != null).also { if (it) tx.writeHistory(row) } },
			present = { tx, row ->
				val owner = RetainedOwners.of(row) ?: return@importSource false
				val stored = tx.historyRow(RetainedOwners.rowId(row))
				if (stored != null) return@importSource RetainedOwners.of(stored) == owner
				// The owner already holds a row for this queue operation, which is
				// that operation's row; the older copy is not a second one.
				row.queueOperationId?.let { tx.historyRowForQueueOperation(owner, it) } != null
			},
		)

	fun importSkipped(rows: RetainedRecordRows, blob: LegacyBlob, nowMillis: Long): Result =
		importSource(
			rows = rows,
			source = SKIPPED_SOURCE,
			blob = blob,
			nowMillis = nowMillis,
			decode = RetainedRecordCodec::decodeSkipped,
			insert = { tx, row -> tx.insertSkippedIfAbsent(row) },
			overwrite = { tx, row -> tx.writeSkipped(row); true },
			present = { tx, row ->
				tx.skippedRow(row.rowId)?.let { RetainedOwners.of(it) == RetainedOwners.of(row) } == true
			},
		)

	private inline fun <T> importSource(
		rows: RetainedRecordRows,
		source: String,
		blob: LegacyBlob,
		nowMillis: Long,
		crossinline decode: (String) -> List<T>,
		crossinline insert: (RetainedRecordTx, T) -> Boolean,
		crossinline overwrite: (RetainedRecordTx, T) -> Boolean,
		crossinline present: (RetainedRecordTx, T) -> Boolean,
	): Result {
		val text = blob.text?.takeIf { it.isNotBlank() } ?: return Result.Absent
		val fingerprint = RetainedRecordCodec.sha256(text)
		return rows.transaction { tx ->
			val prior = tx.importRecord(source)
			val absorbed = prior?.generation ?: 0L
			val legacyNewer = blob.generation > absorbed
			if (
				prior != null &&
				!legacyNewer &&
				prior.fingerprint == fingerprint &&
				prior.parserVersion == PARSER_VERSION &&
				prior.state != LegacyImportState.IMPORTED
			) {
				return@transaction Result.Unchanged(prior.state)
			}
			val generation = maxOf(absorbed, blob.generation)

			val entries = RetainedRecordCodec.storedEntries(text)
			if (entries == null) {
				tx.putImportRecord(
					LegacyImportRecord(
						source = source,
						state = LegacyImportState.UNREADABLE,
						parserVersion = PARSER_VERSION,
						fingerprint = fingerprint,
						generation = generation,
						seen = 0,
						imported = 0,
						rejected = 0,
						updatedAtMillis = nowMillis,
					),
				)
				return@transaction Result.Unreadable
			}

			val decoded = decode(text)
			// Oldest first, so insertion order agrees with recency.
			val written = if (legacyNewer) {
				decoded.asReversed().count { overwrite(tx, it) }
			} else {
				decoded.asReversed().count { insert(tx, it) }
			}
			val verified = decoded.all { present(tx, it) }
			tx.putImportRecord(
				LegacyImportRecord(
					source = source,
					state = if (verified) LegacyImportState.VERIFIED else LegacyImportState.IMPORTED,
					parserVersion = PARSER_VERSION,
					fingerprint = fingerprint,
					generation = generation,
					seen = entries,
					imported = written,
					// Entries the codec refused, or that lay beyond the per-owner cap.
					rejected = (entries - decoded.size).coerceAtLeast(0),
					updatedAtMillis = nowMillis,
				),
			)
			Result.Imported(written, verified, legacyNewer)
		}
	}
}
