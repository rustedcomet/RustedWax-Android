package com.rustedwax.app.scrobble

import com.rustedwax.app.storage.db.LegacyImportRecord
import com.rustedwax.app.storage.db.LocalOwner

/**
 * The structured store's History and Not Logged tables, as the handful of
 * operations the retained lists need (Issue #41).
 *
 * Narrow on purpose. Everything that decides — what to import, when a source
 * is verified, which rows a save keeps — lives in [RetainedRecordImport] and
 * [DatabaseRetainedRecords] and is written against this interface, so it runs
 * on the JVM against an in-memory implementation. [SqliteRetainedRecordRows]
 * is the only implementation that touches SQLite, and it decides nothing.
 */
internal interface RetainedRecordRows {
	/** Run [block] atomically: every change in it lands, or none does. */
	fun <T> transaction(block: (RetainedRecordTx) -> T): T
}

/**
 * Operations available inside one transaction.
 *
 * Owners are spelled as stored — see [RetainedOwners]. Row order everywhere is
 * newest first: `at` descending, then most recently written.
 */
internal interface RetainedRecordTx {

	/** Insert unless a row with this id, or this owner's queue operation, exists. True if inserted. */
	fun insertHistoryIfAbsent(row: FinalizationRuntime.ScrobbleRecord): Boolean

	/**
	 * Write this row's current state under its id.
	 *
	 * A row the runtime reports for a queue operation *is* that operation's
	 * row, so any other stored row this owner holds for the same operation is
	 * replaced by it — the same "update in place" Issue #9 B2 made the
	 * in-memory list do.
	 */
	fun writeHistory(row: FinalizationRuntime.ScrobbleRecord)

	fun historyRow(rowId: String): FinalizationRuntime.ScrobbleRecord?

	/** The id of the row this owner holds for a queue operation, if any. */
	fun historyRowForQueueOperation(owner: String, operationId: String): String?

	fun historyOwners(): List<String>

	fun history(owner: String, limit: Int): List<FinalizationRuntime.ScrobbleRecord>

	/** Delete this owner's rows beyond the newest [keep]. Never touches another owner. */
	fun pruneHistory(owner: String, keep: Int)

	/** Insert unless a row with this id exists. True if inserted. */
	fun insertSkippedIfAbsent(row: FinalizationRuntime.SkipRecord): Boolean

	fun writeSkipped(row: FinalizationRuntime.SkipRecord)

	fun skippedRow(rowId: String): FinalizationRuntime.SkipRecord?

	fun skippedOwners(): List<String>

	fun skipped(owner: String, limit: Int): List<FinalizationRuntime.SkipRecord>

	fun pruneSkipped(owner: String, keep: Int)

	fun importRecord(source: String): LegacyImportRecord?

	fun putImportRecord(record: LegacyImportRecord)
}

/**
 * How retained rows map to stored owners and ids. Shared by every
 * [RetainedRecordRows] implementation so they cannot disagree.
 */
internal object RetainedOwners {

	/**
	 * A History row's owner. Null for a row with no account, which is not a
	 * History row and is never stored — History never uses the signed-out owner.
	 */
	fun of(row: FinalizationRuntime.ScrobbleRecord): String? = LocalOwner.account(row.account)

	/** A Not Logged row's owner; the signed-out device is [LocalOwner.SIGNED_OUT]. */
	fun of(row: FinalizationRuntime.SkipRecord): String = LocalOwner.of(row.account)

	/** The model's account for a stored Not Logged owner. */
	fun skipAccount(owner: String): String? = owner.takeIf { it != LocalOwner.SIGNED_OUT }

	/**
	 * A History row's database identity.
	 *
	 * On this build every History row's `eventId` is minted once per row and
	 * never shared, so it *is* the row identity, and both legacy and new rows
	 * use it. The column stays separate from `event_id` because that stops
	 * being true once a listen can produce several rows (Stage 7B): this
	 * function is the one place that changes when it does.
	 */
	fun rowId(row: FinalizationRuntime.ScrobbleRecord): String = row.eventId
}
