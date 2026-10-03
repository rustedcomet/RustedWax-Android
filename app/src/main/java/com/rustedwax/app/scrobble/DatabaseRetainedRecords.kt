package com.rustedwax.app.scrobble

import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.storage.db.LegacyImportRecord
import com.rustedwax.app.storage.db.LegacyImportState
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * History and Not Logged in the structured local database (Issue #41).
 *
 * A [RetainedRecordStore] like the SharedPreferences one. Once it opens it is
 * the authoritative persisted source: the runtime adopts it through
 * [FinalizationRuntime.adoptFrom], where stored state wins over the
 * provisional legacy rows restored at startup.
 *
 * ## Lifecycle
 *
 * [start] runs on [WRITER], one ordered background thread, because every
 * caller of `FinalizationRuntime.init` is on the main thread. There it opens
 * the database, imports the legacy blobs ([RetainedRecordImport]) and reads
 * each owner's newest [RETAINED_ROWS] rows. Only when all of that succeeded is
 * the store handed over. Any failure is reported and the store is never used:
 * the legacy store stays in charge for the run, exactly as before this class.
 *
 * ## Saving
 *
 * The runtime still hands over its whole list. Each save runs on [WRITER], in
 * call order:
 *
 *  1. the database first, in one transaction: every row is written under its
 *     own id (so an updated queue outcome updates its row), then each owner
 *     present is cut back to its newest [RETAINED_ROWS] — an owner is only ever
 *     pruned by its own rows — and the blob about to be shadowed is recorded
 *     with its fingerprint and the next write generation;
 *  2. then the legacy shadow: the same blob, at that generation, through the
 *     same `apply()` path as before, holding the same per-owner rows.
 *
 * Database first means the shadow can only ever lag the database, never lead
 * it, so a shadow left older by a crash between the two is recognised by its
 * lower generation and cannot roll a row back. If the database write fails,
 * the shadow is still written — at the advanced generation, so the next launch
 * knows it is newer and imports it over the stored rows. Nothing is lost.
 *
 * Nothing here deletes legacy bytes, and Forget Key does not reach this class:
 * an account's rows wait for that account to return.
 */
internal class DatabaseRetainedRecords private constructor(
	private val rows: RetainedRecordRows,
	private val legacy: LegacyRetainedBlobs,
	private val writer: Executor,
	private val restoredHistory: List<FinalizationRuntime.ScrobbleRecord>,
	private val restoredSkipped: List<FinalizationRuntime.SkipRecord>,
	private val nowMillis: () -> Long,
	/** The newest shadow generation per list. Touched only by tasks on [writer]. */
	private var historyGeneration: Long,
	private var skippedGeneration: Long,
) : RetainedRecordStore {

	/** What the database held when it opened. No I/O, so any thread may ask. */
	override fun loadHistory(): List<FinalizationRuntime.ScrobbleRecord> = restoredHistory

	override fun loadSkipped(): List<FinalizationRuntime.SkipRecord> = restoredSkipped

	override fun saveHistory(rows: List<FinalizationRuntime.ScrobbleRecord>) {
		val snapshot = rows.toList()
		writer.execute {
			val blob = RetainedRecordCodec.encodeHistory(snapshot)
			val generation = ++historyGeneration
			runCatching { writeHistory(this.rows, snapshot, blob, generation, nowMillis()) }
				.onFailure { reportWriteFailure("History", it) }
			legacy.writeHistory(blob, generation)
		}
	}

	override fun saveSkipped(rows: List<FinalizationRuntime.SkipRecord>) {
		val snapshot = rows.toList()
		writer.execute {
			val blob = RetainedRecordCodec.encodeSkipped(snapshot)
			val generation = ++skippedGeneration
			runCatching { writeSkipped(this.rows, snapshot, blob, generation, nowMillis()) }
				.onFailure { reportWriteFailure("Not Logged", it) }
			legacy.writeSkipped(blob, generation)
		}
	}

	private fun reportWriteFailure(list: String, failure: Throwable) {
		runCatching {
			EventLog.append(
				"retained",
				"could not write $list to the local database (${failure.javaClass.simpleName}); " +
					"the previous store has it and the next launch re-imports it.",
			)
		}
	}

	companion object {

		/** The one thread every database read and write for these lists runs on, in order. */
		internal val WRITER: Executor by lazy {
			Executors.newSingleThreadExecutor { task ->
				Thread(task, "rustedwax-retained-db").apply { isDaemon = true }
			}
		}

		/**
		 * Open, migrate and load on [writer], then hand the store to [onReady] —
		 * or hand the failure to [onFailure] and never produce a store.
		 */
		fun start(
			openRows: () -> RetainedRecordRows,
			legacy: LegacyRetainedBlobs,
			onReady: (DatabaseRetainedRecords) -> Unit,
			onFailure: (Throwable) -> Unit,
			writer: Executor = WRITER,
			nowMillis: () -> Long = System::currentTimeMillis,
		) {
			writer.execute {
				val store = runCatching { open(openRows(), legacy, writer, nowMillis) }
					.getOrElse { failure ->
						runCatching { onFailure(failure) }
						return@execute
					}
				onReady(store)
			}
		}

		/** Import both legacy sources, then read every owner's newest rows. Throws on any failure. */
		internal fun open(
			rows: RetainedRecordRows,
			legacy: LegacyRetainedBlobs,
			writer: Executor,
			nowMillis: () -> Long,
		): DatabaseRetainedRecords {
			val historyBlob = legacy.readHistory()
			val skippedBlob = legacy.readSkipped()
			RetainedRecordImport.importHistory(rows, historyBlob, nowMillis())
			RetainedRecordImport.importSkipped(rows, skippedBlob, nowMillis())
			return rows.transaction { tx ->
				val history = tx.historyOwners()
					.flatMap { tx.history(it, RETAINED_ROWS) }
					.sortedByDescending { it.atEpochSec }
				val skipped = tx.skippedOwners()
					.flatMap { tx.skipped(it, RETAINED_ROWS) }
					.sortedByDescending { it.atEpochSec }
				DatabaseRetainedRecords(
					rows = rows,
					legacy = legacy,
					writer = writer,
					restoredHistory = history,
					restoredSkipped = skipped,
					nowMillis = nowMillis,
					historyGeneration = maxOf(
						historyBlob.generation,
						tx.importRecord(RetainedRecordImport.HISTORY_SOURCE)?.generation ?: 0L,
					),
					skippedGeneration = maxOf(
						skippedBlob.generation,
						tx.importRecord(RetainedRecordImport.SKIPPED_SOURCE)?.generation ?: 0L,
					),
				)
			}
		}

		internal fun writeHistory(
			rows: RetainedRecordRows,
			list: List<FinalizationRuntime.ScrobbleRecord>,
			blob: String,
			generation: Long,
			nowMillis: Long,
		) = rows.transaction { tx ->
			val stored = list.filter { RetainedOwners.of(it) != null }
			// Oldest first, so a new row's insertion order agrees with its recency.
			stored.asReversed().forEach(tx::writeHistory)
			stored.mapNotNull(RetainedOwners::of).distinct().forEach { tx.pruneHistory(it, RETAINED_ROWS) }
			tx.putImportRecord(written(RetainedRecordImport.HISTORY_SOURCE, blob, generation, list.size, nowMillis))
		}

		internal fun writeSkipped(
			rows: RetainedRecordRows,
			list: List<FinalizationRuntime.SkipRecord>,
			blob: String,
			generation: Long,
			nowMillis: Long,
		) = rows.transaction { tx ->
			list.asReversed().forEach(tx::writeSkipped)
			list.map(RetainedOwners::of).distinct().forEach { tx.pruneSkipped(it, RETAINED_ROWS) }
			tx.putImportRecord(written(RetainedRecordImport.SKIPPED_SOURCE, blob, generation, list.size, nowMillis))
		}

		private fun written(source: String, blob: String, generation: Long, rows: Int, nowMillis: Long) =
			LegacyImportRecord(
				source = source,
				state = LegacyImportState.VERIFIED,
				parserVersion = RetainedRecordImport.PARSER_VERSION,
				fingerprint = RetainedRecordCodec.sha256(blob),
				generation = generation,
				seen = rows,
				imported = 0,
				rejected = 0,
				updatedAtMillis = nowMillis,
			)
	}
}
