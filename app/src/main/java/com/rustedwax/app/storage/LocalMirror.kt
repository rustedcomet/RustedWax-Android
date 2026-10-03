package com.rustedwax.app.storage

import com.rustedwax.app.storage.db.LegacyImportRecord
import com.rustedwax.app.storage.db.LegacyImportState
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * A per-key local store held in memory, made durable by the structured
 * database, and shadowed into its legacy SharedPreferences file (Issue #41).
 *
 * The Snap drafts, posted-Snap cache, refresh stamps and thread previews are
 * all read synchronously, often from the main thread, so the in-memory map is
 * what callers see. It goes through the same three phases the History lists
 * do:
 *
 *  1. **Provisional.** At construction the legacy entries are loaded exactly
 *     as before, so everything is on screen at once. Writes in this phase go to
 *     the legacy file as before and keep its generation.
 *  2. **Database.** On [WRITER] the database opens and the legacy source is
 *     imported ([importLegacy]); the stored entries then become the authority
 *     for every key not written since phase 1 began. A key written in the
 *     meantime keeps its live value — an open draft is never replaced by an
 *     older stored one — and that value is written to the database. From then
 *     on each write goes to the database first and to the legacy shadow
 *     second, both on [WRITER], in call order, the shadow at the next
 *     generation.
 *  3. **Fallback**, when the database could not open: the legacy file stays
 *     the store, as it always was, and its writes advance the generation so the
 *     next run that does open the database imports them as newer.
 *
 * Keys are the legacy keys, unchanged — the identity callers already use. A
 * key the database cannot hold (its owner breaks the owner rule) stays a
 * legacy-only entry rather than disappearing. Nothing here deletes a legacy
 * file, and nothing here can reach Hive: it reads and writes local entries.
 */
internal class LocalMirror<V>(
	private val source: String,
	private val legacy: LegacyKeyedSource<V>,
	/** Whether the database's owner rule admits this key. */
	private val storable: (String) -> Boolean,
	private val retention: Retention<V>? = null,
	private val writer: Executor = WRITER,
	private val nowMillis: () -> Long = System::currentTimeMillis,
	private val report: (String) -> Unit = {},
) {

	/** At most [max] entries per owner; the oldest by [at] (then key) go first. */
	class Retention<V>(val max: Int, val owner: (String) -> String, val at: (V) -> Long)

	private enum class Mode { PROVISIONAL, DATABASE, FALLBACK }

	private val lock = Any()
	private val live = LinkedHashMap<String, V>()
	private var provisional: Map<String, V>
	private val dirty = HashSet<String>()
	private var mode = Mode.PROVISIONAL
	private var rows: MirrorRows<V>? = null
	/** Shadow generation. Touched only by tasks on [writer] once in database mode. */
	private var generation = 0L
	/** Failed asynchronous changes not yet absorbed by a committed transaction. Writer only. */
	private val uncommitted = LinkedHashMap<String, V?>()

	init {
		val state = legacy.readAll()
		live.putAll(state.entries)
		provisional = state.entries
	}

	fun get(key: String): V? = synchronized(lock) { live[key] }

	fun keys(): Set<String> = synchronized(lock) { live.keys.toSet() }

	/** A copy of every entry, for callers that filter by owner. */
	fun entries(): Map<String, V> = synchronized(lock) { LinkedHashMap(live) }

	fun put(key: String, value: V) = change(key, value)

	fun contains(key: String): Boolean = synchronized(lock) { key in live }

	/** A provisional shadow cannot establish whether a reply is genuinely unseen. */
	fun isReady(): Boolean = synchronized(lock) { mode != Mode.PROVISIONAL }

	/** Caller holds [lock]. */
	private fun set(key: String, value: V?) {
		if (value == null) live.remove(key) else live[key] = value
	}

	/**
	 * Several changes as one write (Issue #41 Slice 4). Every key lands, or none
	 * does.
	 *
	 * With [durable], this returns only once the changes are durably stored —
	 * the database transaction committed, or the legacy file committed in the
	 * phases where it is the store — and returns false, with memory put back
	 * exactly as it was, when they could not be. A caller that makes a promise
	 * on the strength of a write (a notification is one) needs that answer
	 * before it acts. Must not be called on [writer] itself.
	 */
	fun putAll(changes: Map<String, V?>, durable: Boolean = false): Boolean {
		if (changes.isEmpty()) return true
		val (prior, current) = synchronized(lock) {
			val prior = changes.keys.associateWith { live[it] }
			changes.forEach { (k, v) -> set(k, v) }
			dirty += changes.keys
			prior to mode
		}
		val list = changes.toList()
		val stored = when (current) {
			Mode.PROVISIONAL -> legacy.writeAll(list, generation = null, commit = durable)
			Mode.FALLBACK -> legacy.writeAll(list, legacy.generation() + 1, commit = durable)
			Mode.DATABASE -> if (durable) {
				val task = java.util.concurrent.FutureTask { writeThrough(list, shadowOnFailure = false) }
				writer.execute(task)
				runCatching { task.get() }.getOrDefault(false)
			} else {
				writer.execute { writeThrough(list) }
				true
			}
		}
		if (!stored) {
			synchronized(lock) { prior.forEach { (k, v) -> set(k, v) } }
			// The legacy file's in-process map took the failed batch; put it back.
			if (current != Mode.DATABASE) legacy.writeAll(prior.toList(), generation = null, commit = false)
		}
		return stored
	}

	fun remove(key: String) = change(key, null)

	private fun change(key: String, value: V?) {
		val (changes, current) = synchronized(lock) {
			if (value == null) {
				live.remove(key)
			} else {
				live[key] = value
			}
			dirty += key
			val evicted = if (value != null) evict(key) else emptyList()
			(listOf(key to value) + evicted.map { it to null }) to mode
		}
		persist(changes, current)
	}

	/** Keys over this key's owner's limit, never [keep] itself. Caller holds [lock]. */
	private fun evict(keep: String): List<String> {
		val rule = retention ?: return emptyList()
		val owner = rule.owner(keep)
		val mine = live.entries.filter { rule.owner(it.key) == owner }
		if (mine.size <= rule.max) return emptyList()
		val gone = mine.filter { it.key != keep }
			.sortedWith(compareBy({ rule.at(it.value) }, { it.key }))
			.take(mine.size - rule.max)
			.map { it.key }
		gone.forEach { live.remove(it); dirty += it }
		return gone
	}

	private fun persist(changes: List<Pair<String, V?>>, current: Mode) {
		when (current) {
			Mode.PROVISIONAL -> changes.forEach { (k, v) -> legacy.write(k, v, generation = null) }
			Mode.FALLBACK -> {
				val next = legacy.generation() + 1
				changes.forEach { (k, v) -> legacy.write(k, v, next) }
			}
			Mode.DATABASE -> writer.execute { writeThrough(changes) }
		}
	}

	/**
	 * Database first, then the shadow at the same new generation. On [writer].
	 * True when the database transaction committed.
	 */
	private fun writeThrough(changes: List<Pair<String, V?>>, shadowOnFailure: Boolean = true): Boolean {
		if (changes.isEmpty()) return true
		val next = ++generation
		val batch = LinkedHashMap(uncommitted).apply { changes.forEach { (k, v) -> put(k, v) } }.toList()
		val committed = runCatching {
			checkNotNull(rows).transaction { tx ->
				batch.forEach { (k, v) ->
					if (v == null) tx.delete(k) else if (storable(k)) tx.put(k, v)
				}
				val prior = tx.importRecord()
				tx.putImportRecord(record(LegacyImportState.VERIFIED, prior?.fingerprint, next, prior?.seen ?: 0, 0, prior?.rejected ?: 0))
			}
		}.onFailure {
			report("could not write $source to the local database (${it.javaClass.simpleName}); the previous store has it")
		}.isSuccess
		if (committed) uncommitted.clear()
		else if (shadowOnFailure) changes.forEach { (k, v) -> uncommitted[k] = v }
		// A failed durable batch is rolled back by its caller and must never be retried.
		if (committed || shadowOnFailure) legacy.writeAll(changes, next, commit = false)
		return committed
	}

	/** Open, import and adopt on [writer]; on failure fall back for the run. */
	fun start(openRows: () -> MirrorRows<V>) {
		writer.execute {
			runCatching { adopt(openRows()) }.onFailure { failure ->
				fallBack()
				report("local database unavailable for $source (${failure.javaClass.simpleName}); the previous store stays in charge this run")
			}
		}
	}

	private fun adopt(opened: MirrorRows<V>) {
		val imported = importLegacy(opened)
		val stored = opened.transaction { it.all() }
		val (pending, restored, absorbed) = synchronized(lock) {
			val keys = live.keys + stored.keys + provisional.keys
			for (k in keys) {
				when {
					k in dirty -> Unit
					k in stored -> live[k] = stored.getValue(k)
					k in imported.legacyOnly -> Unit
					else -> live.remove(k)
				}
			}
			val trimmed = trimAll()
			val toWrite = dirty.map { it to live[it] } + trimmed.map { it to null }
			val shadowFix = live.filterKeys { it !in dirty && it !in imported.legacyOnly && provisional[it] != live[it] }
			val stale = provisional.keys.filter { it !in live && it !in dirty && it !in imported.legacyOnly }
			dirty.clear()
			provisional = emptyMap()
			rows = opened
			mode = Mode.DATABASE
			Triple(toWrite, shadowFix.map { it.key to it.value as V? } + stale.map { it to null }, imported.generation)
		}
		generation = maxOf(generation, absorbed)
		writeThrough(pending)
		// Bring the shadow up to the database for keys it lagged on, so a later
		// fallback run starts from the same entries.
		if (restored.isNotEmpty()) {
			val shadowGeneration = generation
			restored.forEach { (k, v) -> legacy.write(k, v, shadowGeneration) }
		}
	}

	/** Every owner over its limit, trimmed. Caller holds [lock]. */
	private fun trimAll(): List<String> {
		val rule = retention ?: return emptyList()
		return live.keys.groupBy(rule.owner).flatMap { (_, keys) ->
			if (keys.size <= rule.max) emptyList()
			else keys.sortedWith(compareBy({ rule.at(live.getValue(it)) }, { it })).take(keys.size - rule.max)
		}.onEach { live.remove(it) }
	}

	private fun fallBack() {
		val pending = synchronized(lock) {
			mode = Mode.FALLBACK
			provisional = emptyMap()
			dirty.map { it to live[it] }.also { dirty.clear() }
		}
		if (pending.isNotEmpty()) {
			val next = legacy.generation() + 1
			pending.forEach { (k, v) -> legacy.write(k, v, next) }
		}
	}

	/** What an import learned: the generation absorbed, and the keys only the legacy file can hold. */
	class Imported(val generation: Long, val legacyOnly: Set<String>, val written: Int, val newer: Boolean)

	/**
	 * Bring the legacy source into the database, in one transaction.
	 *
	 *  - **Never imported:** every entry the database can hold is written.
	 *  - **Legacy newer** (its generation is above the one absorbed — written by
	 *    a run that could not open the database): the legacy entries become the
	 *    stored state, including keys that run removed.
	 *  - **Otherwise** the database is at least as new and nothing changes; an
	 *    older shadow cannot resurrect a deleted draft or roll an entry back.
	 *
	 * Entries the legacy file holds but cannot decode are counted and left
	 * where they are; they never remove anything valid.
	 */
	internal fun importLegacy(target: MirrorRows<V>): Imported {
		val state = legacy.readAll()
		val legacyOnly = state.entries.keys.filterNot(storable).toSet() + state.rejectedKeys
		return target.transaction { tx ->
			val prior = tx.importRecord()
			val absorbed = prior?.generation ?: 0L
			val newer = prior != null && state.generation > absorbed
			if (prior != null && !newer) return@transaction Imported(absorbed, legacyOnly, 0, false)
			val importable = state.entries.filterKeys(storable)
			if (newer) tx.all().keys.filter { it !in importable && it !in state.rejectedKeys }.forEach(tx::delete)
			importable.forEach { (k, v) -> tx.put(k, v) }
			val verified = tx.all().let { stored -> importable.all { (k, v) -> stored[k] == v } }
			val generation = maxOf(absorbed, state.generation)
			tx.putImportRecord(
				record(
					state = if (verified) LegacyImportState.VERIFIED else LegacyImportState.IMPORTED,
					fingerprint = state.fingerprint,
					generation = generation,
					seen = state.seen,
					imported = importable.size,
					rejected = state.rejected + state.entries.keys.count { !storable(it) },
				),
			)
			Imported(generation, legacyOnly, importable.size, newer)
		}
	}

	private fun record(
		state: LegacyImportState,
		fingerprint: String?,
		generation: Long,
		seen: Int,
		imported: Int,
		rejected: Int,
	) = LegacyImportRecord(source, state, PARSER_VERSION, fingerprint, generation, seen, imported, rejected, nowMillis())

	companion object {
		const val PARSER_VERSION = 1

		/** The one ordered thread Snap local state is read and written on. */
		val WRITER: Executor by lazy {
			Executors.newSingleThreadExecutor { task ->
				Thread(task, "rustedwax-snap-db").apply { isDaemon = true }
			}
		}

		/** Lowercase hex SHA-256 of the sorted raw legacy entries. */
		fun fingerprint(raw: Map<String, String>): String =
			MessageDigest.getInstance("SHA-256")
				.digest(raw.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray())
				.joinToString("") { "%02x".format(it) }
	}
}

/** One legacy per-key source, read whole and written one entry at a time. */
internal interface LegacyKeyedSource<V> {
	fun readAll(): LegacyKeyedState<V>

	/** The generation the legacy file currently carries. */
	fun generation(): Long

	/**
	 * Write one entry (null removes it) and, when [generation] is given, the
	 * file's generation — in one edit, so neither lands without the other.
	 */
	fun write(key: String, value: V?, generation: Long?) {
		writeAll(listOf(key to value), generation, commit = false)
	}

	/**
	 * Several entries and the generation in one edit. With [commit] the edit is
	 * committed synchronously and the answer is whether the disk took it;
	 * otherwise it is applied and the answer is true.
	 */
	fun writeAll(changes: List<Pair<String, V?>>, generation: Long?, commit: Boolean): Boolean
}

/** A legacy source as read: its decodable entries, and what it could not decode. */
internal data class LegacyKeyedState<V>(
	val entries: Map<String, V>,
	val rejected: Int,
	val seen: Int,
	val generation: Long,
	val fingerprint: String,
	/** Present but unreadable keys are not deletions and their raw bytes stay in the legacy file. */
	val rejectedKeys: Set<String> = emptySet(),
)

/** A source's table, in transactions. */
internal interface MirrorRows<V> {
	fun <T> transaction(block: (MirrorTx<V>) -> T): T
}

internal interface MirrorTx<V> {
	fun all(): Map<String, V>
	fun put(key: String, value: V)
	fun delete(key: String)
	fun importRecord(): LegacyImportRecord?
	fun putImportRecord(record: LegacyImportRecord)
}
