package com.rustedwax.app.storage

/** A legacy source held in memory, so device tests never touch a real SharedPreferences file. */
internal class InMemoryKeyedSourceForDevice<V>(entries: Map<String, V>) : LegacyKeyedSource<V> {
	val entries = LinkedHashMap(entries)
	private var generation = 0L

	override fun readAll() = LegacyKeyedState(
		entries = LinkedHashMap(entries),
		rejected = 0,
		seen = entries.size,
		generation = generation,
		fingerprint = LocalMirror.fingerprint(entries.mapValues { it.value.toString() }),
	)

	override fun generation() = generation

	override fun writeAll(changes: List<Pair<String, V?>>, generation: Long?, commit: Boolean): Boolean {
		changes.forEach { (key, value) -> if (value == null) entries.remove(key) else entries[key] = value }
		if (generation != null) this.generation = generation
		return true
	}
}
