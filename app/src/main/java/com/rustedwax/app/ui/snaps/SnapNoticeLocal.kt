package com.rustedwax.app.ui.snaps

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.WorkerThread
import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.storage.LocalMirror
import com.rustedwax.app.storage.MirrorRows
import com.rustedwax.app.storage.MirrorTx
import com.rustedwax.app.storage.SharedPreferencesKeyedSource
import com.rustedwax.app.storage.db.LegacyImportRecord
import com.rustedwax.app.storage.db.LegacyImportTable
import com.rustedwax.app.storage.db.LocalDatabase
import com.rustedwax.app.storage.db.LocalDatabaseSchema
import com.rustedwax.app.storage.db.LocalOwner

/**
 * The bell's durable state in the structured database (Issue #41 Slice 4).
 *
 * The legacy file keeps three kinds of entry under two key shapes:
 *
 *  - `n|account|author/permlink` holding a notice's JSON — a reply that is both
 *    **seen** and **displayed**, read or unread;
 *  - the same key holding `-` — a reply that is **seen only**: its display
 *    data was pruned, and the key stays so the reply can never be news again;
 *  - `s|account|author/permlink` holding a time — a conversation **seeded** on
 *    its first read.
 *
 * In the database those become three tables, and the two concepts the file
 * mixes come apart: `seen_reply` is written once per reply and never deleted,
 * `reply_notice` is display and read state that pruning removes, and
 * `thread_seed` is the seed mark. Removing or pruning a notice never touches
 * `seen_reply`, so no route through this store can make an old reply new.
 *
 * Storage only. Nothing here posts, cancels or schedules a notification, and
 * nothing here can reach Hive: delivery stays exactly where it was, in
 * [SnapNoticeController] and its notifier.
 */
internal sealed interface NoticeEntry {

	/** A displayed notice, read or unread, and when it was stored (epoch seconds). */
	data class Shown(val notice: SnapNotice, val atEpochSec: Long) : NoticeEntry

	/** Seen, with nothing left to draw. */
	data object Seen : NoticeEntry

	/** A conversation's first read happened at this time (epoch seconds). */
	data class Seed(val atEpochSec: Long) : NoticeEntry
}

/**
 * [SnapNoticeStore] over a [LocalMirror] of the legacy notice file, with every
 * rule of [SharedPreferencesSnapNoticeStore] kept: a conversation's first read
 * seeds and announces nothing; a reply already on file — shown or seen — is
 * never written again; the write behind an announcement is durable before
 * [record] answers, and a failed one answers null; display data beyond
 * [SharedPreferencesSnapNoticeStore.MAX_DISPLAYED_NOTICES] collapses to
 * seen-only, answered rows first.
 */
internal class MirroredSnapNoticeStore(
	private val mirror: LocalMirror<NoticeEntry>,
) : SnapNoticeStore {

	override fun all(account: String): List<SnapNotice> =
		mirror.entries().mapNotNull { (key, entry) ->
			if (SnapNoticeCodec.accountOf(key) != account) return@mapNotNull null
			(entry as? NoticeEntry.Shown)?.notice
		}

	@Synchronized
	override fun record(
		account: String,
		rootId: String,
		notices: List<SnapNotice>,
		atEpochSec: Long,
	): List<SnapNotice>? {
		// Retry on the next ordinary thread read once startup has established
		// durable seen/seed state. An older shadow cannot authorize an announcement.
		if (!mirror.isReady()) return null
		val seedKey = SnapNoticeCodec.seedKey(account, rootId)
		val seeding = !mirror.contains(seedKey)
		val unseen = notices.filterNot { mirror.contains(SnapNoticeCodec.noticeKey(account, it.contentId)) }
		if (!seeding && unseen.isEmpty()) return emptyList()

		val changes = LinkedHashMap<String, NoticeEntry?>()
		if (seeding) changes[seedKey] = NoticeEntry.Seed(atEpochSec)
		val fresh = mutableListOf<SnapNotice>()
		unseen.forEach { notice ->
			val written = notice.copy(read = seeding)
			changes[SnapNoticeCodec.noticeKey(account, notice.contentId)] = NoticeEntry.Shown(written, atEpochSec)
			if (!seeding) fresh += written
		}
		// Durable before answering: the caller announces `fresh` the moment this
		// returns, and an announcement is a promise that the record survives.
		if (!mirror.putAll(changes, durable = true)) return null
		prune()
		return fresh
	}

	@Synchronized
	override fun markRead(account: String, contentId: String) {
		val key = SnapNoticeCodec.noticeKey(account, contentId)
		val shown = mirror.get(key) as? NoticeEntry.Shown ?: return
		if (shown.notice.read) return
		mirror.put(key, shown.copy(notice = shown.notice.copy(read = true)))
	}

	@Synchronized
	override fun markThreadRead(account: String, rootId: String) {
		val changes = mirror.entries().mapNotNull { (key, entry) ->
			if (SnapNoticeCodec.accountOf(key) != account) return@mapNotNull null
			val shown = entry as? NoticeEntry.Shown ?: return@mapNotNull null
			if (shown.notice.rootId != rootId || shown.notice.read) return@mapNotNull null
			key to (shown.copy(notice = shown.notice.copy(read = true)) as NoticeEntry?)
		}.toMap()
		mirror.putAll(changes)
	}

	/** The existing collapse rule, over the same raw form it was written for. */
	private fun prune() {
		val raw = mirror.entries().mapNotNull { (key, entry) ->
			when (entry) {
				is NoticeEntry.Shown -> key to SnapNoticeCodec.encode(entry.notice, entry.atEpochSec)
				NoticeEntry.Seen -> key to SnapNoticeCodec.TOMBSTONE
				is NoticeEntry.Seed -> null
			}
		}.toMap()
		val collapsible = SnapNoticeCodec.collapsibleKeys(raw, SharedPreferencesSnapNoticeStore.MAX_DISPLAYED_NOTICES)
		if (collapsible.isNotEmpty()) mirror.putAll(collapsible.associateWith { NoticeEntry.Seen })
	}
}

/** Keys, the legacy file's format, and the owner rule for notices. */
internal object SnapNoticeLocalCodec {

	private const val GENERATION = "#rustedwax-generation"

	/** `n|account|id` or `s|account|id` with a stored account and a non-empty id. */
	fun storable(key: String): Boolean {
		val parts = key.split('|', limit = 3)
		if (parts.size != 3 || parts[0] !in setOf("n", "s") || parts[2].isEmpty()) return false
		return LocalOwner.account(parts[1]) == parts[1]
	}

	fun source(prefs: SharedPreferences) = SharedPreferencesKeyedSource<NoticeEntry>(
		prefs = prefs,
		generationKey = GENERATION,
		toMirrorKey = { it.takeIf { k -> k.startsWith("n|") || k.startsWith("s|") } },
		toStoredKey = { it },
		decode = { value -> decode(value) },
		encode = { edit, key, entry ->
			when (entry) {
				is NoticeEntry.Shown -> edit.putString(key, SnapNoticeCodec.encode(entry.notice, entry.atEpochSec))
				NoticeEntry.Seen -> edit.putString(key, SnapNoticeCodec.TOMBSTONE)
				is NoticeEntry.Seed -> edit.putLong(key, entry.atEpochSec)
			}
		},
	)

	/** A legacy value, re-validated by the existing codec; null when it is not one this store wrote. */
	fun decode(value: Any?): NoticeEntry? = when (value) {
		is Long -> NoticeEntry.Seed(value)
		SnapNoticeCodec.TOMBSTONE -> NoticeEntry.Seen
		is String -> SnapNoticeCodec.decode(value)?.let { NoticeEntry.Shown(it, SnapNoticeCodec.storedAt(value)) }
		else -> null
	}
}

/**
 * The notice mirror's three tables. Mapping only.
 *
 * `seen_reply` is insert-once: a row, once there, is never updated or deleted
 * by anything here. `seeded` records whether the reply was first stored
 * already read — a conversation's seeding read, or a read notice migrated from
 * the legacy file.
 */
internal class SqliteNoticeRows(private val database: LocalDatabase) : MirrorRows<NoticeEntry> {

	override fun <T> transaction(block: (MirrorTx<NoticeEntry>) -> T): T = database.write { db ->
		block(object : MirrorTx<NoticeEntry> {
			override fun all(): Map<String, NoticeEntry> = buildMap {
				db.rawQuery(
					"SELECT s.owner, s.content_id, n.root_id, n.text, n.created, n.read, n.at " +
						"FROM ${LocalDatabaseSchema.SEEN_REPLY} s LEFT JOIN ${LocalDatabaseSchema.REPLY_NOTICE} n " +
						"ON n.owner = s.owner AND n.content_id = s.content_id",
					null,
				).use { c ->
					while (c.moveToNext()) {
						val owner = c.getString(0)
						val contentId = c.getString(1)
						val key = SnapNoticeCodec.noticeKey(owner, contentId)
						if (c.isNull(2)) {
							put(key, NoticeEntry.Seen)
							continue
						}
						val notice = notice(contentId, c.getString(2), c.getString(3), if (c.isNull(4)) null else c.getLong(4), c.getInt(5) == 1)
							?: continue
						put(key, NoticeEntry.Shown(notice, c.getLong(6)))
					}
				}
				db.rawQuery("SELECT owner, root_id, seeded_at FROM ${LocalDatabaseSchema.THREAD_SEED}", null).use { c ->
					while (c.moveToNext()) put(SnapNoticeCodec.seedKey(c.getString(0), c.getString(1)), NoticeEntry.Seed(c.getLong(2)))
				}
			}

			override fun put(key: String, value: NoticeEntry) {
				val (kind, owner, id) = key.split('|', limit = 3)
				when (value) {
					is NoticeEntry.Seed -> upsert(
						db, LocalDatabaseSchema.THREAD_SEED, "owner = ? AND root_id = ?", arrayOf(owner, id),
						ContentValues().apply { put("owner", owner); put("root_id", id); put("seeded_at", value.atEpochSec) },
					)
					NoticeEntry.Seen -> {
						seen(db, owner, id, seeded = false, at = System.currentTimeMillis() / 1000)
						db.delete(LocalDatabaseSchema.REPLY_NOTICE, "owner = ? AND content_id = ?", arrayOf(owner, id))
					}
					is NoticeEntry.Shown -> {
						check(kind == "n")
						seen(db, owner, id, seeded = value.notice.read, at = value.atEpochSec)
						upsert(
							db, LocalDatabaseSchema.REPLY_NOTICE, "owner = ? AND content_id = ?", arrayOf(owner, id),
							ContentValues().apply {
								put("owner", owner)
								put("content_id", id)
								put("root_id", value.notice.rootId)
								put("text", value.notice.text)
								put("created", value.notice.createdAtEpochSec)
								put("read", if (value.notice.read) 1 else 0)
								put("at", value.atEpochSec)
							},
						)
					}
				}
			}

			/** Removes display data or a seed mark; never the fact that a reply was seen. */
			override fun delete(key: String) {
				val (kind, owner, id) = key.split('|', limit = 3)
				if (kind == "s") {
					db.delete(LocalDatabaseSchema.THREAD_SEED, "owner = ? AND root_id = ?", arrayOf(owner, id))
				} else {
					db.delete(LocalDatabaseSchema.REPLY_NOTICE, "owner = ? AND content_id = ?", arrayOf(owner, id))
				}
			}

			override fun importRecord(): LegacyImportRecord? = LegacyImportTable.read(db, SOURCE)

			override fun putImportRecord(record: LegacyImportRecord) = LegacyImportTable.put(db, record)
		})
	}

	private fun seen(db: SQLiteDatabase, owner: String, contentId: String, seeded: Boolean, at: Long) {
		db.insertWithOnConflict(
			LocalDatabaseSchema.SEEN_REPLY,
			null,
			ContentValues().apply {
				put("owner", owner)
				put("content_id", contentId)
				put("seeded", if (seeded) 1 else 0)
				put("first_seen_at", at)
			},
			SQLiteDatabase.CONFLICT_IGNORE,
		)
	}

	private fun upsert(db: SQLiteDatabase, table: String, where: String, args: Array<String>, values: ContentValues) {
		if (db.update(table, values, where, args) == 0) db.insertOrThrow(table, null, values)
	}

	/** A stored notice, re-validated through the existing codec exactly as a legacy one is. */
	private fun notice(contentId: String, rootId: String, text: String, created: Long?, read: Boolean): SnapNotice? {
		val author = contentId.substringBefore('/')
		val permlink = contentId.substringAfter('/', "")
		val rootAuthor = rootId.substringBefore('/')
		val rootPermlink = rootId.substringAfter('/', "")
		return SnapNoticeCodec.decode(
			SnapNoticeCodec.encode(SnapNotice(author, permlink, rootAuthor, rootPermlink, text, created, read), 0L),
		)
	}

	companion object {
		const val SOURCE = "snap_notices"
	}
}

/**
 * One account's (or the signed-out device's) named checkpoint values — the
 * storage primitive later background work can keep its progress in. #41 adds
 * the table and this API only: nothing produces or consumes a checkpoint yet.
 */
internal interface SyncCheckpoints {
	data class Checkpoint(val value: String, val updatedAtMillis: Long)

	fun get(owner: String, scope: String): Checkpoint?
	fun put(owner: String, scope: String, value: String, atMillis: Long)
	fun delete(owner: String, scope: String)
}

/** [SyncCheckpoints] in `sync_checkpoint`. Disk I/O: never on the main thread. */
internal class SqliteSyncCheckpoints(private val database: LocalDatabase) : SyncCheckpoints {

	@WorkerThread
	override fun get(owner: String, scope: String): SyncCheckpoints.Checkpoint? = database.read { db ->
		db.query(
			LocalDatabaseSchema.SYNC_CHECKPOINT, arrayOf("value", "updated_at"),
			"owner = ? AND scope = ?", arrayOf(owner, scope), null, null, null,
		).use { c -> if (c.moveToFirst()) SyncCheckpoints.Checkpoint(c.getString(0), c.getLong(1)) else null }
	}

	@WorkerThread
	override fun put(owner: String, scope: String, value: String, atMillis: Long) {
		database.write { db ->
			val values = ContentValues().apply {
				put("owner", owner)
				put("scope", scope)
				put("value", value)
				put("updated_at", atMillis)
			}
			if (db.update(LocalDatabaseSchema.SYNC_CHECKPOINT, values, "owner = ? AND scope = ?", arrayOf(owner, scope)) == 0) {
				db.insertOrThrow(LocalDatabaseSchema.SYNC_CHECKPOINT, null, values)
			}
		}
	}

	@WorkerThread
	override fun delete(owner: String, scope: String) {
		database.write { db ->
			db.delete(LocalDatabaseSchema.SYNC_CHECKPOINT, "owner = ? AND scope = ?", arrayOf(owner, scope))
		}
	}
}

/** The process's one notice mirror and store. */
internal object SnapNoticeLocal {

	@Volatile
	private var store: SnapNoticeStore? = null

	fun store(context: Context): SnapNoticeStore = store ?: synchronized(this) {
		store ?: create(context.applicationContext).also { store = it }
	}

	/** Built and started the way [store] builds it, over the given parts. */
	internal fun mirror(
		prefs: SharedPreferences,
		openRows: () -> MirrorRows<NoticeEntry>,
		writer: java.util.concurrent.Executor = LocalMirror.WRITER,
		report: (String) -> Unit = {},
	): LocalMirror<NoticeEntry> =
		LocalMirror(
			source = SqliteNoticeRows.SOURCE,
			legacy = SnapNoticeLocalCodec.source(prefs),
			storable = SnapNoticeLocalCodec::storable,
			writer = writer,
			report = report,
		).also { it.start(openRows) }

	private fun create(context: Context): SnapNoticeStore {
		val prefs = context.getSharedPreferences(SharedPreferencesSnapNoticeStore.NOTICES, Context.MODE_PRIVATE)
		return MirroredSnapNoticeStore(
			mirror(
				prefs = prefs,
				openRows = { SqliteNoticeRows(LocalDatabase.get(context)) },
				report = { runCatching { EventLog.append("snaps", it) } },
			),
		)
	}
}
