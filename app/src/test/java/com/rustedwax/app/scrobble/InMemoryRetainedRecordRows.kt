package com.rustedwax.app.scrobble

import com.rustedwax.app.storage.db.LegacyImportRecord

/**
 * [RetainedRecordRows] in memory, with the semantics [SqliteRetainedRecordRows]
 * gets from SQLite: atomic transactions that roll back on any exception,
 * owner-normalised rows, one row per owner's queue operation, newest-first
 * order by `at` then most recent write, and per-owner pruning.
 *
 * [failAfterWrites] simulates an interruption: after that many row writes in
 * the process the next one throws, and the transaction it is in rolls back.
 */
internal class InMemoryRetainedRecordRows : RetainedRecordRows {

	private data class Stored<T>(val row: T, val seq: Long)

	private var history = LinkedHashMap<String, Stored<FinalizationRuntime.ScrobbleRecord>>()
	private var skipped = LinkedHashMap<String, Stored<FinalizationRuntime.SkipRecord>>()
	private var imports = LinkedHashMap<String, LegacyImportRecord>()
	private var seq = 0L

	var failAfterWrites: Int? = null
	var transactions = 0
		private set

	/** Every stored History row, any owner, newest first. */
	val allHistory: List<FinalizationRuntime.ScrobbleRecord>
		get() = history.values.sortedWith(order()).map { it.row }

	val allSkipped: List<FinalizationRuntime.SkipRecord>
		get() = skipped.values.sortedWith(orderSkipped()).map { it.row }

	fun import(source: String): LegacyImportRecord? = imports[source]

	@Synchronized
	override fun <T> transaction(block: (RetainedRecordTx) -> T): T {
		transactions++
		val saved = Triple(LinkedHashMap(history), LinkedHashMap(skipped), LinkedHashMap(imports))
		val savedSeq = seq
		try {
			return block(Tx())
		} catch (failure: Throwable) {
			history = saved.first
			skipped = saved.second
			imports = saved.third
			seq = savedSeq
			throw failure
		}
	}

	private fun countWrite() {
		val left = failAfterWrites ?: return
		if (left <= 0) throw IllegalStateException("simulated interruption")
		failAfterWrites = left - 1
	}

	private fun order() = compareByDescending<Stored<FinalizationRuntime.ScrobbleRecord>> { it.row.atEpochSec }
		.thenByDescending { it.seq }

	private fun orderSkipped() = compareByDescending<Stored<FinalizationRuntime.SkipRecord>> { it.row.atEpochSec }
		.thenByDescending { it.seq }

	/** What SQLite hands back: the owner column, lowercased, as the account. */
	private fun normal(row: FinalizationRuntime.ScrobbleRecord) = row.copy(account = RetainedOwners.of(row)!!)

	private fun normal(row: FinalizationRuntime.SkipRecord) =
		row.copy(account = RetainedOwners.skipAccount(RetainedOwners.of(row)))

	private inner class Tx : RetainedRecordTx {

		override fun insertHistoryIfAbsent(row: FinalizationRuntime.ScrobbleRecord): Boolean {
			val owner = RetainedOwners.of(row) ?: return false
			val id = RetainedOwners.rowId(row)
			if (id in history) return false
			if (row.queueOperationId != null && historyRowForQueueOperation(owner, row.queueOperationId) != null) {
				return false
			}
			countWrite()
			history[id] = Stored(normal(row), ++seq)
			return true
		}

		override fun writeHistory(row: FinalizationRuntime.ScrobbleRecord) {
			val owner = RetainedOwners.of(row) ?: return
			val id = RetainedOwners.rowId(row)
			countWrite()
			row.queueOperationId?.let { op ->
				history.entries.removeAll {
					it.key != id && RetainedOwners.of(it.value.row) == owner && it.value.row.queueOperationId == op
				}
			}
			val existing = history[id]
			history[id] = Stored(normal(row), existing?.seq ?: ++seq)
		}

		override fun historyRow(rowId: String) = history[rowId]?.row

		override fun historyRowForQueueOperation(owner: String, operationId: String): String? =
			history.entries.firstOrNull {
				RetainedOwners.of(it.value.row) == owner && it.value.row.queueOperationId == operationId
			}?.key

		override fun historyOwners() = history.values.mapNotNull { RetainedOwners.of(it.row) }.distinct()

		override fun history(owner: String, limit: Int) =
			history.values.filter { RetainedOwners.of(it.row) == owner }.sortedWith(order()).take(limit).map { it.row }

		override fun pruneHistory(owner: String, keep: Int) {
			val kept = history(owner, keep).map(RetainedOwners::rowId).toSet()
			history.entries.removeAll { RetainedOwners.of(it.value.row) == owner && it.key !in kept }
		}

		override fun insertSkippedIfAbsent(row: FinalizationRuntime.SkipRecord): Boolean {
			if (row.rowId in skipped) return false
			countWrite()
			skipped[row.rowId] = Stored(normal(row), ++seq)
			return true
		}

		override fun writeSkipped(row: FinalizationRuntime.SkipRecord) {
			countWrite()
			skipped[row.rowId] = Stored(normal(row), skipped[row.rowId]?.seq ?: ++seq)
		}

		override fun skippedRow(rowId: String) = skipped[rowId]?.row

		override fun skippedOwners() = skipped.values.map { RetainedOwners.of(it.row) }.distinct()

		override fun skipped(owner: String, limit: Int) =
			skipped.values.filter { RetainedOwners.of(it.row) == owner }.sortedWith(orderSkipped()).take(limit).map { it.row }

		override fun pruneSkipped(owner: String, keep: Int) {
			val kept = skipped(owner, keep).map { it.rowId }.toSet()
			skipped.entries.removeAll { RetainedOwners.of(it.value.row) == owner && it.key !in kept }
		}

		override fun importRecord(source: String) = imports[source]

		override fun putImportRecord(record: LegacyImportRecord) {
			imports[record.source] = record
		}
	}
}

/** The legacy blobs, in memory, with their generations, counting writes. */
internal class InMemoryLegacyBlobs(
	var history: String? = null,
	var skipped: String? = null,
	var historyGeneration: Long = 0,
	var skippedGeneration: Long = 0,
) : LegacyRetainedBlobs {
	var writes = 0
		private set

	override fun readHistory() = LegacyBlob(history, historyGeneration)
	override fun readSkipped() = LegacyBlob(skipped, skippedGeneration)

	override fun writeHistory(blob: String, generation: Long) {
		history = blob; historyGeneration = generation; writes++
	}

	override fun writeSkipped(blob: String, generation: Long) {
		skipped = blob; skippedGeneration = generation; writes++
	}
}
