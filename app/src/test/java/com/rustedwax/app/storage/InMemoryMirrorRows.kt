package com.rustedwax.app.storage

import com.rustedwax.app.storage.db.LegacyImportRecord

/**
 * [MirrorRows] in memory: atomic transactions that roll back on any
 * exception, like SQLite's. [failNextTransactions] makes that many upcoming
 * transactions throw part-way, after their first write.
 */
internal class InMemoryMirrorRows<V> : MirrorRows<V> {

	var rows = LinkedHashMap<String, V>()
		private set
	var record: LegacyImportRecord? = null
		private set
	var failNextTransactions = 0

	@Synchronized
	override fun <T> transaction(block: (MirrorTx<V>) -> T): T {
		val savedRows = LinkedHashMap(rows)
		val savedRecord = record
		val failing = failNextTransactions > 0
		if (failing) failNextTransactions--
		var writes = 0
		fun touched() {
			writes++
			if (failing && writes > 1) throw IllegalStateException("simulated interruption")
		}
		try {
			return block(object : MirrorTx<V> {
				override fun all(): Map<String, V> = LinkedHashMap(rows)
				override fun put(key: String, value: V) { touched(); rows[key] = value }
				override fun delete(key: String) { touched(); rows.remove(key) }
				override fun importRecord(): LegacyImportRecord? = record
				override fun putImportRecord(record: LegacyImportRecord) { touched(); this@InMemoryMirrorRows.record = record }
			})
		} catch (failure: Throwable) {
			rows = savedRows
			record = savedRecord
			throw failure
		}
	}
}

/** A [LegacyKeyedSource] in memory, with a generation, counting writes. */
internal class InMemoryKeyedSource<V>(
	entries: Map<String, V> = emptyMap(),
	var generationValue: Long = 0,
	private val undecodable: Int = 0,
	private val rejectedKeys: Set<String> = emptySet(),
) : LegacyKeyedSource<V> {
	val entries = LinkedHashMap(entries)
	var writes = 0
		private set

	override fun readAll() = LegacyKeyedState(
		entries = LinkedHashMap(entries),
		rejected = undecodable,
		seen = entries.size + undecodable,
		generation = generationValue,
		fingerprint = LocalMirror.fingerprint(entries.mapValues { it.value.toString() }),
		rejectedKeys = rejectedKeys,
	)

	override fun generation() = generationValue

	/** Set to make the next [failCommits] committed writes fail, as a full disk would. */
	var failCommits = 0

	override fun writeAll(changes: List<Pair<String, V?>>, generation: Long?, commit: Boolean): Boolean {
		if (commit && failCommits > 0) {
			failCommits--
			return false
		}
		writes++
		changes.forEach { (key, value) -> if (value == null) entries.remove(key) else entries[key] = value }
		if (generation != null) generationValue = generation
		return true
	}
}
