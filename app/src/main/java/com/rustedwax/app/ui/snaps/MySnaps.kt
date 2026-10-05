package com.rustedwax.app.ui.snaps

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rustedwax.app.snaps.PendingSnap
import com.rustedwax.app.snaps.PendingSnapIntegrity
import com.rustedwax.app.snaps.PendingSnapKind
import com.rustedwax.app.snaps.PendingSnapState
import com.rustedwax.app.snaps.PostedSnap
import com.rustedwax.app.snaps.PostedSnapBody
import com.rustedwax.app.snaps.SnapPayloadBuilder
import com.rustedwax.app.storage.db.LocalDatabase
import com.rustedwax.app.storage.db.LocalDatabaseSchema
import com.rustedwax.app.storage.db.LocalOwner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * My Snaps, Stage 47A (Issue #47): the account's own root Snaps, from the
 * local catalog alone.
 *
 * The catalog is the `my_snap` table — see [LocalDatabaseSchema.UPGRADE_V2].
 * It is **presentation state**. Nothing in this file holds a key, a publisher,
 * an editor or a deleter, and nothing here writes a publication record: the
 * root Snap records are read, through a read-only function, to learn which
 * Snaps are proven on chain, and that is all. A row in this catalog cannot
 * authorize, retry or block any Hive operation.
 *
 * Not in this stage: account-wide Hive discovery and the catalog refresh that
 * goes with it. A Snap appears here once this installation has proven it
 * `CONFIRMED`; one published from another installation does not, yet.
 */
data class MySnapRow(
	val owner: String,
	val author: String,
	val permlink: String,
	/** The publication record's creation time; the newest-first order. */
	val createdAtEpochSec: Long,
	val eventId: String?,
	/** From the Snap's own frozen `youtu.be` tail, never guessed. */
	val videoId: String?,
	/** Only from the History row the Snap was posted on, while it existed. */
	val title: String?,
	val artist: String?,
	val service: String?,
	/** The last known words, for display before anything else has been read. */
	val userText: String?,
) {
	val contentId: String get() = "$author/$permlink"

	/** A card for this row when nothing newer is in memory. Never from the chain. */
	fun toPosted(): PostedSnap = PostedSnap(
		author = author,
		permlink = permlink,
		userText = userText.orEmpty(),
		createdAtEpochSec = createdAtEpochSec,
		fromChain = false,
	)
}

/** What a History row knows about the listen a Snap was posted on. */
data class MySnapMedia(
	val eventId: String,
	val videoId: String,
	val title: String,
	val artist: String?,
	val service: String? = null,
)

/** The catalog's table. Mapping only; decides nothing. */
internal interface MySnapRows {
	/**
	 * Insert or update by `(owner, author, permlink)`, all in one transaction.
	 * A null field never erases a known one: a Snap whose History row has gone
	 * keeps the title it was indexed with.
	 *
	 * [keepKnownText] (Stage 47E): an existing row keeps its words and only an
	 * absent one is filled — so a local record or cache read on open can never
	 * put older words back over ones a proven edit or a manual refresh accepted.
	 */
	fun upsert(rows: List<MySnapRow>, nowMillis: Long, keepKnownText: Boolean = false)

	/**
	 * Hive proved this exact root deleted: record that durably and drop its
	 * catalog row, in one transaction. Either both land or neither does, and
	 * the call throws when they did not. A tombstoned identity is never listed
	 * or upserted again.
	 */
	fun tombstone(owner: String, author: String, permlink: String, txId: String?, nowMillis: Long)

	/** Newest first, continuing after [after] when given. Never a tombstoned identity. */
	fun page(owner: String, limit: Int, after: MySnapRow?): List<MySnapRow>

	/** The `my_snaps_catalog` checkpoint's stored value (Stage 47B), or null. */
	fun checkpoint(owner: String): String?

	/**
	 * What one discovery pass found, and the checkpoint it reached, in one
	 * transaction — so the checkpoint can never move past rows that were not
	 * stored. A new identity is inserted whole; a known one only takes the
	 * chain's current words (when given) and a video id it lacked. Tombstoned
	 * identities are skipped.
	 *
	 * [reconciled] (Stage 47E): current words for rows already held, applied
	 * update-only as [reconcile] does, in the **same** transaction — so the
	 * checkpoint, and the content cursor in it, can never move past words that
	 * were not stored. Returns the `author/permlink` of every row they changed.
	 */
	fun applyDiscovery(
		owner: String,
		rows: List<MySnapRow>,
		checkpoint: String,
		nowMillis: Long,
		reconciled: List<MySnapRow> = emptyList(),
	): List<String>

	/** Live rows created within [from]..[to] (epoch seconds), for the deletion check. */
	fun createdBetween(owner: String, from: Long, to: Long): List<MySnapRow>

	/**
	 * Stage 47E: proven current words for Snaps the catalog **already** holds.
	 * Update only, never insert: a row whose exact identity is absent, is
	 * tombstoned, or whose known video differs from the answer's is left alone.
	 * Presentation only — a local publication record is never touched.
	 * Returns the `author/permlink` of every row it changed.
	 */
	fun reconcile(owner: String, rows: List<MySnapRow>, nowMillis: Long): List<String>
}

/**
 * Which root Snap records belong in the catalog, and what each row says.
 *
 * Pure. A record qualifies only when it is a **root** Snap, **CONFIRMED**,
 * passes the publication store's own identity rule for this account, and was
 * authored by it. Uncertain, failed and corrupt records never appear — those
 * stay on their History card's recovery path.
 *
 * Media context is attached only when the History row is still known **and**
 * names the same video the Snap's frozen body links to. Otherwise the row
 * carries the video id the body proves and nothing more.
 */
internal object MySnapsBackfill {

	fun rows(
		account: String,
		records: List<PendingSnap>,
		media: (eventId: String) -> MySnapMedia?,
		/** The newest locally known words for a record, if better than its body. */
		latestText: (PendingSnap) -> String? = { null },
	): List<MySnapRow> {
		val owner = LocalOwner.account(account) ?: return emptyList()
		return records.asSequence()
			.filter { it.kind == PendingSnapKind.ROOT }
			.filter { it.state == PendingSnapState.CONFIRMED }
			.filter { PendingSnapIntegrity.identityProblem(it, account) == null }
			// The catalog's own rule, which the schema also enforces.
			.filter { it.author == owner && it.permlink.isNotBlank() }
			.mapNotNull { record ->
				// Not a v1 Snap body: the posted card refuses it, and so does this.
				val written = PostedSnapBody.userText(record.body) ?: return@mapNotNull null
				val videoId = PostedSnapBody.generatedUrl(record.body)
					?.removePrefix(SnapPayloadBuilder.canonicalUrl(""))
					?.takeIf { it.isNotEmpty() }
				val known = media(record.eventId)?.takeIf { it.videoId == videoId }
				MySnapRow(
					owner = owner,
					author = record.author,
					permlink = record.permlink,
					createdAtEpochSec = record.createdAtEpochSec,
					eventId = record.eventId,
					videoId = videoId,
					title = known?.title,
					artist = known?.artist,
					service = known?.service,
					userText = latestText(record) ?: written,
				)
			}
			.distinctBy { it.permlink }
			.toList()
	}
}

/** [MySnapRows] over the real database. */
internal class SqliteMySnapRows(private val database: LocalDatabase) : MySnapRows {

	private val table = LocalDatabaseSchema.MY_SNAP

	override fun upsert(rows: List<MySnapRow>, nowMillis: Long, keepKnownText: Boolean) {
		if (rows.isEmpty()) return
		val text = if (keepKnownText) "COALESCE(user_text, ?)" else "COALESCE(?, user_text)"
		database.write { db ->
			// UPDATE-then-INSERT rather than SQLite's UPSERT, which needs 3.24 and
			// this app still runs on Android versions that ship older.
			val update = db.compileStatement(
				"UPDATE $table SET created_at = ?, event_id = COALESCE(?, event_id), " +
					"video_id = COALESCE(?, video_id), title = COALESCE(?, title), " +
					"artist = COALESCE(?, artist), service = COALESCE(?, service), " +
					"user_text = $text, updated_at = ? " +
					"WHERE owner = ? AND author = ? AND permlink = ?",
			)
			rows.forEach { row ->
				// A proven deletion outranks any record still on disk.
				if (tombstoned(db, row.owner, row.author, row.permlink)) return@forEach
				update.clearBindings()
				update.bindLong(1, row.createdAtEpochSec)
				listOf(row.eventId, row.videoId, row.title, row.artist, row.service, row.userText)
					.forEachIndexed { i, value -> if (value == null) update.bindNull(i + 2) else update.bindString(i + 2, value) }
				update.bindLong(8, nowMillis)
				update.bindString(9, row.owner)
				update.bindString(10, row.author)
				update.bindString(11, row.permlink)
				if (update.executeUpdateDelete() == 0) db.insertOrThrow(table, null, insertValues(row, nowMillis))
			}
		}
	}

	override fun tombstone(owner: String, author: String, permlink: String, txId: String?, nowMillis: Long) {
		database.write { db ->
			if (!tombstoned(db, owner, author, permlink)) {
				db.insertOrThrow(
					LocalDatabaseSchema.MY_SNAP_TOMBSTONE,
					null,
					ContentValues().apply {
						put("owner", owner)
						put("author", author)
						put("permlink", permlink)
						put("tx_id", txId)
						put("deleted_at", nowMillis)
					},
				)
			}
			db.delete(table, "owner = ? AND author = ? AND permlink = ?", arrayOf(owner, author, permlink))
		}
	}

	override fun checkpoint(owner: String): String? = database.read { db ->
		db.query(
			LocalDatabaseSchema.SYNC_CHECKPOINT, arrayOf("value"),
			"owner = ? AND scope = ?", arrayOf(owner, CatalogCheckpoint.SCOPE), null, null, null,
		).use { if (it.moveToFirst()) it.getString(0) else null }
	}

	override fun applyDiscovery(
		owner: String,
		rows: List<MySnapRow>,
		checkpoint: String,
		nowMillis: Long,
		reconciled: List<MySnapRow>,
	): List<String> =
		database.write { db ->
			val update = db.compileStatement(
				"UPDATE $table SET user_text = COALESCE(?, user_text), video_id = COALESCE(video_id, ?), " +
					"updated_at = ? WHERE owner = ? AND author = ? AND permlink = ?",
			)
			rows.filter { it.owner == owner }.forEach { row ->
				if (tombstoned(db, row.owner, row.author, row.permlink)) return@forEach
				update.clearBindings()
				if (row.userText == null) update.bindNull(1) else update.bindString(1, row.userText)
				if (row.videoId == null) update.bindNull(2) else update.bindString(2, row.videoId)
				update.bindLong(3, nowMillis)
				update.bindString(4, row.owner)
				update.bindString(5, row.author)
				update.bindString(6, row.permlink)
				if (update.executeUpdateDelete() == 0) db.insertOrThrow(table, null, insertValues(row, nowMillis))
			}
			val values = ContentValues().apply {
				put("owner", owner)
				put("scope", CatalogCheckpoint.SCOPE)
				put("value", checkpoint)
				put("updated_at", nowMillis)
			}
			if (db.update(LocalDatabaseSchema.SYNC_CHECKPOINT, values, "owner = ? AND scope = ?", arrayOf(owner, CatalogCheckpoint.SCOPE)) == 0) {
				db.insertOrThrow(LocalDatabaseSchema.SYNC_CHECKPOINT, null, values)
			}
			reconcileIn(db, owner, reconciled, nowMillis)
		}

	override fun reconcile(owner: String, rows: List<MySnapRow>, nowMillis: Long): List<String> {
		if (rows.none { it.owner == owner && it.userText != null }) return emptyList()
		return database.write { db -> reconcileIn(db, owner, rows, nowMillis) }
	}

	private fun reconcileIn(db: SQLiteDatabase, owner: String, rows: List<MySnapRow>, nowMillis: Long): List<String> {
		val words = rows.filter { it.owner == owner && it.userText != null }
		if (words.isEmpty()) return emptyList()
		val changed = mutableListOf<String>()
		val update = db.compileStatement(
			"UPDATE $table SET user_text = ?, updated_at = ? " +
				"WHERE owner = ? AND author = ? AND permlink = ? " +
				"AND (video_id IS NULL OR ? IS NULL OR video_id = ?) " +
				// Only real changes: the same words are not a change to report.
				"AND (user_text IS NULL OR user_text <> ?)",
		)
		words.forEach { row ->
			if (tombstoned(db, row.owner, row.author, row.permlink)) return@forEach
			update.clearBindings()
			update.bindString(1, row.userText)
			update.bindLong(2, nowMillis)
			update.bindString(3, row.owner)
			update.bindString(4, row.author)
			update.bindString(5, row.permlink)
			if (row.videoId == null) {
				update.bindNull(6)
				update.bindNull(7)
			} else {
				update.bindString(6, row.videoId)
				update.bindString(7, row.videoId)
			}
			update.bindString(8, row.userText)
			if (update.executeUpdateDelete() > 0) changed += row.contentId
		}
		return changed
	}

	override fun createdBetween(owner: String, from: Long, to: Long): List<MySnapRow> = database.read { db ->
		db.query(
			table, null,
			"owner = ? AND created_at >= ? AND created_at <= ? AND NOT EXISTS (SELECT 1 FROM " +
				"${LocalDatabaseSchema.MY_SNAP_TOMBSTONE} t WHERE t.owner = $table.owner AND " +
				"t.author = $table.author AND t.permlink = $table.permlink)",
			arrayOf(owner, from.toString(), to.toString()), null, null, "created_at DESC, permlink DESC",
		).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
	}

	private fun tombstoned(db: SQLiteDatabase, owner: String, author: String, permlink: String): Boolean =
		db.rawQuery(
			"SELECT 1 FROM ${LocalDatabaseSchema.MY_SNAP_TOMBSTONE} WHERE owner = ? AND author = ? AND permlink = ?",
			arrayOf(owner, author, permlink),
		).use { it.moveToFirst() }

	override fun page(owner: String, limit: Int, after: MySnapRow?): List<MySnapRow> =
		database.read { db -> query(db, owner, limit, after) }

	private fun query(db: SQLiteDatabase, owner: String, limit: Int, after: MySnapRow?): List<MySnapRow> {
		val live = " AND NOT EXISTS (SELECT 1 FROM ${LocalDatabaseSchema.MY_SNAP_TOMBSTONE} t " +
			"WHERE t.owner = $table.owner AND t.author = $table.author AND t.permlink = $table.permlink)"
		val (where, args) = if (after == null) {
			"owner = ?$live" to arrayOf(owner)
		} else {
			"owner = ? AND (created_at < ? OR (created_at = ? AND permlink < ?))$live" to
				arrayOf(owner, after.createdAtEpochSec.toString(), after.createdAtEpochSec.toString(), after.permlink)
		}
		return db.query(table, null, where, args, null, null, "created_at DESC, permlink DESC", limit.toString())
			.use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
	}

	private fun insertValues(row: MySnapRow, nowMillis: Long) = ContentValues().apply {
		put("owner", row.owner)
		put("author", row.author)
		put("permlink", row.permlink)
		put("created_at", row.createdAtEpochSec)
		put("event_id", row.eventId)
		put("video_id", row.videoId)
		put("title", row.title)
		put("artist", row.artist)
		put("service", row.service)
		put("user_text", row.userText)
		put("indexed_at", nowMillis)
		put("updated_at", nowMillis)
	}

	private fun read(c: Cursor): MySnapRow {
		fun text(name: String): String? = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getString(it) }
		return MySnapRow(
			owner = c.getString(c.getColumnIndexOrThrow("owner")),
			author = c.getString(c.getColumnIndexOrThrow("author")),
			permlink = c.getString(c.getColumnIndexOrThrow("permlink")),
			createdAtEpochSec = c.getLong(c.getColumnIndexOrThrow("created_at")),
			eventId = text("event_id"),
			videoId = text("video_id"),
			title = text("title"),
			artist = text("artist"),
			service = text("service"),
			userText = text("user_text"),
		)
	}
}

/**
 * The My Snaps page's state: one account's catalog rows, newest first, loaded
 * a page at a time.
 *
 * Local only. [open] shows what the catalog already holds, then folds in the
 * account's confirmed root Snap records and shows the result; neither step
 * touches the network. Every answer is checked against the account signed in
 * when it lands, so a switch mid-read cannot put one account's Snaps on
 * another's page.
 */
@Stable
class MySnapsController internal constructor(
	private val scope: CoroutineScope,
	private val account: () -> String?,
	/** The catalog; null when the local database cannot be opened. */
	private val catalog: () -> MySnapRows?,
	/** Read-only: the account's root Snap records, as the publication store vouches for them. */
	private val confirmedRoots: (account: String) -> List<PendingSnap>,
	private val latestText: (PendingSnap) -> String? = { null },
	private val io: CoroutineDispatcher = Dispatchers.IO,
	private val clock: () -> Long = System::currentTimeMillis,
	private val pageSize: Int = PAGE_SIZE,
	/** Hive discovery (Stage 47B); null keeps the page local-only. Reads only. */
	private val discovery: MySnapsDiscovery? = null,
	/**
	 * Whether Hive proves this exact object gone — see
	 * [MySnapsIdentity.absence]. True only on that proof; false when a current
	 * node still has it; null when nothing could be decided (offline, stale
	 * nodes), which leaves the row to be checked again.
	 */
	private val provenAbsent: (author: String, permlink: String) -> Boolean? = { _, _ -> null },
	/**
	 * One exact Hive read of a root Snap's current object (Stage 47E), or null
	 * when Hive has none; throws when it could not be asked. Reads only; used by
	 * a manual refresh's content check.
	 */
	private val readRoot: (author: String, permlink: String) -> org.json.JSONObject? = { _, _ -> null },
	/**
	 * Told, on the main thread, which Snaps' words a pass just changed, so the
	 * History card's own copy of a locally published one is re-read through its
	 * existing path rather than left showing older words.
	 */
	private val onReconciled: (account: String, contentIds: List<String>) -> Unit = { _, _ -> },
) {

	/** Serializes catalog writes, so a deletion can never be overtaken by an older backfill. */
	private val lock = Mutex()

	/** Proven deletions also suppress read snapshots captured before catalog removal. Main thread only. */
	private val deleted = mutableSetOf<Pair<String, String>>()

	private var ownerState by mutableStateOf<String?>(null)
	private val loaded = mutableStateListOf<MySnapRow>()
	private var readyState by mutableStateOf(false)
	private var moreState by mutableStateOf(false)
	private var failedState by mutableStateOf(false)
	private var loadingMore = false

	/** Bumped by every [open]; an older read's answer is dropped. */
	private var generation = 0L

	/**
	 * Accounts with a discovery pass in flight, one claim each, held for the
	 * whole pass. A second pass for the same account is refused even when the
	 * signed-in account changed and changed back meanwhile (A→B→A), so an older
	 * pass can never commit after a newer one for that account.
	 */
	private val refreshClaims = mutableStateListOf<String>()

	/** The account whose last discovery pass failed, or null. */
	private var refreshFailedFor by mutableStateOf<String?>(null)

	/** Whether the account on screen still has an unfinished deep scan. */
	private var deepPendingFor by mutableStateOf<String?>(null)

	/** The account whose last manual refresh could not check every older Snap's words. */
	private var contentPendingFor by mutableStateOf<String?>(null)

	/**
	 * When each Snap was last edited and proven here, `owner|author/permlink`
	 * to epoch millis. A discovery pass that began before the edit landed may
	 * carry the words from before it, so it may not write that Snap's words.
	 */
	private val edits = java.util.concurrent.ConcurrentHashMap<String, Long>()

	private fun owner(): String? = LocalOwner.account(account())

	private fun current(): Boolean = ownerState != null && ownerState == owner()

	/** The signed-in account's rows. Empty for anybody else's, whatever is loaded. */
	val rows: List<MySnapRow> get() = if (current()) loaded else emptyList()

	/** True once the catalog has answered for the signed-in account. */
	val ready: Boolean get() = current() && readyState

	/** True while older rows remain to be paged in. */
	val hasMore: Boolean get() = current() && moreState

	/** True when the local catalog could not be read. Never means "no Snaps". */
	val failed: Boolean get() = current() && failedState

	/** True while a Hive discovery pass runs for the account on screen. */
	val refreshing: Boolean get() = owner()?.let { it in refreshClaims } == true

	/** The last discovery pass failed: what is shown is the last known good list, not a deletion. */
	val refreshFailed: Boolean get() = refreshFailedFor != null && refreshFailedFor == owner()

	/** Older pages of the account are still to be scanned on Hive. */
	val deepPending: Boolean get() = deepPendingFor != null && deepPendingFor == owner()

	/**
	 * The last manual refresh did not get through every older Snap's current
	 * words (Stage 47E follow-up): the budget ran out or a read failed. Another
	 * pull continues from where it stopped. Never set by an automatic pass.
	 */
	val contentPending: Boolean get() = contentPendingFor != null && contentPendingFor == owner()

	/**
	 * Show the catalog, then fold in what the publication records prove.
	 *
	 * [history] is the account's History as it stands now, used only to attach
	 * a title and artist to a Snap whose row is still there.
	 */
	fun open(history: List<MySnapMedia>) {
		val raw = account()?.takeIf { it.isNotBlank() }
		val who = LocalOwner.account(raw)
		val gen = ++generation
		if (ownerState != who) {
			ownerState = who
			loaded.clear()
			readyState = false
			moreState = false
			failedState = false
		}
		if (raw == null || who == null) return
		val byEvent = history.associateBy { it.eventId }
		scope.launch {
			// The catalog as it stands, before anything else.
			val keep = maxOf(pageSize, loaded.size)
			show(gen, who, withContext(io) { read(who, keep) }, keep)
			// Then what the confirmed records prove. Disk only.
			val after = withContext(io) {
				runCatching {
					lock.withLock {
						val rows = catalog() ?: return@withLock null
						// Existing rows keep their words: a record or cache on disk may
						// be older than what a proven edit or a manual refresh stored.
						rows.upsert(
							MySnapsBackfill.rows(raw, confirmedRoots(raw), byEvent::get, latestText),
							clock(),
							keepKnownText = true,
						)
						rows.page(who, keep + 1, null)
					}
				}.getOrNull()
			}
			show(gen, who, after, keep)
		}
	}

	/** The next page, after the last row on screen. */
	fun loadMore() {
		val who = owner() ?: return
		if (!current() || !moreState || loadingMore) return
		val last = loaded.lastOrNull() ?: return
		val gen = generation
		loadingMore = true
		scope.launch {
			try {
				val page = withContext(io) {
					runCatching { catalog()?.page(who, pageSize + 1, last) }.getOrNull()
				} ?: return@launch
				if (gen != generation || owner() != who || ownerState != who) return@launch
				val known = loaded.mapTo(HashSet()) { it.contentId }
				loaded += page.take(pageSize).filter { it.contentId !in known && (who to it.contentId) !in deleted }
				moreState = page.size > pageSize
			} finally {
				loadingMore = false
			}
		}
	}

	/**
	 * A root Snap was **proven** deleted from Hive: stop listing it.
	 *
	 * Only ever called by the existing deletion path once it has that proof —
	 * never for a tap, an acknowledged broadcast or a failed read.
	 */
	fun applyDelete(account: String, contentId: String) {
		val who = LocalOwner.account(account) ?: return
		val author = contentId.substringBefore('/')
		val permlink = contentId.substringAfter('/', "")
		if (author != who || permlink.isEmpty()) return
		deleted += who to contentId
		if (ownerState == who) loaded.removeAll { it.contentId == contentId }
		// Normally already durable: the deletion path wrote the tombstone before
		// it retired the record (see [recordProvenDeletion]). Written again here,
		// idempotently, in case that write failed.
		scope.launch {
			withContext(io) {
				runCatching { lock.withLock { catalog()?.tombstone(who, author, permlink, null, clock()) } }
			}
		}
	}

	/**
	 * The deletion path's durable step, run on its own thread **after** Hive
	 * proved [contentId] deleted and **before** the Snap's confirmed record is
	 * retired: tombstone it and drop its catalog row in one transaction.
	 *
	 * True only when that transaction committed. False tells the caller to
	 * keep the confirmed record — the last local evidence — rather than retire
	 * it on the strength of a write that did not happen.
	 */
	fun recordProvenDeletion(account: String, contentId: String, txId: String?): Boolean {
		val who = LocalOwner.account(account) ?: return false
		val author = contentId.substringBefore('/')
		val permlink = contentId.substringAfter('/', "")
		if (author != who || permlink.isEmpty()) return false
		val rows = catalog() ?: return false
		return runCatching { rows.tombstone(who, author, permlink, txId, clock()) }.isSuccess
	}

	/** A proven edit of [contentId] by [account]: older discovery answers may not write its words. */
	fun noteEdit(account: String, contentId: String) {
		val who = LocalOwner.account(account) ?: return
		edits["$who|$contentId"] = clock()
	}

	/**
	 * The editor **proved** [contentId]'s new words on Hive (Stage 47E).
	 *
	 * Stamps the edit first, so any discovery pass already running cannot write
	 * the older words; then shows the words on the row at once and stores them
	 * on that same row, update only. A Snap discovered on Hive has no local
	 * publication record, so this is the only place its new words can come
	 * from; nothing is created for it. Afterwards the catalog is re-read as
	 * before, which also folds in what local records prove.
	 */
	fun applyEdit(account: String, contentId: String, userText: String) {
		val who = LocalOwner.account(account) ?: return
		val author = contentId.substringBefore('/')
		val permlink = contentId.substringAfter('/', "")
		if (author != who || permlink.isEmpty()) return
		noteEdit(account, contentId)
		if ((who to contentId) in deleted) return
		if (ownerState == who) {
			val i = loaded.indexOfFirst { it.contentId == contentId }
			if (i >= 0) loaded[i] = loaded[i].copy(userText = userText)
		}
		val row = MySnapRow(who, author, permlink, 0L, null, null, null, null, null, userText)
		scope.launch {
			withContext(io) {
				runCatching { lock.withLock { catalog()?.reconcile(who, listOf(row), clock()) } }
			}
			if (readyState && ownerState == who) open(emptyList())
		}
	}

	/**
	 * Discover the account's RustedWax root Snaps on Hive (Stage 47B).
	 *
	 * Without [force], only when the last successful pass is
	 * [STALE_AFTER_MILLIS] old or older — opening or
	 * returning to the page. Pull-to-refresh forces. One pass at a time per
	 * account; the rows on screen stay while it runs, and a failure keeps them.
	 *
	 * What a pass may change: it inserts positively identified Snaps, updates
	 * known ones' words, advances the checkpoint — all in one transaction — and
	 * tombstones a known Snap only when [provenAbsent] proves it gone. Nothing
	 * it does can remove a row on a failed, empty or partial answer.
	 *
	 * [manual] — pull-to-refresh only — also reads the current words of older
	 * Snaps discovered on Hive that the pass did not reach (Stage 47E follow-up):
	 * at most [CONTENT_CHECKS] exact reads, continuing next time from a durable
	 * cursor. An edit made in another frontend reaches its card this way.
	 */
	fun refresh(force: Boolean, manual: Boolean = false) {
		val disc = discovery ?: return
		val raw = account()?.takeIf { it.isNotBlank() } ?: return
		val who = LocalOwner.account(raw) ?: return
		if (who in refreshClaims) return
		refreshClaims += who
		val started = clock()
		scope.launch {
			try {
				val outcome = withContext(io) { discover(disc, raw, who, force, started, manual) }
				if (owner() != who) return@launch
				when (outcome) {
					null -> Unit
					is Discovered.Failed -> refreshFailedFor = who
					is Discovered.Applied -> {
						if (refreshFailedFor == who) refreshFailedFor = null
						deepPendingFor = if (outcome.deepPending) who else null
						outcome.contentComplete?.let { done -> contentPendingFor = if (done) null else who }
						if (outcome.reconciled.isNotEmpty()) onReconciled(raw, outcome.reconciled)
						outcome.tombstoned.forEach { deleted += who to it }
						if (ownerState == who) {
							val gen = ++generation
							val keep = maxOf(pageSize, loaded.size)
							show(gen, who, withContext(io) { read(who, keep) }, keep)
						}
					}
				}
			} finally {
				// Success, failure, cancellation: this pass's claim goes, and only it.
				refreshClaims -= who
			}
		}
	}

	private sealed interface Discovered {
		/** [contentComplete]: null when no manual content check ran. */
		data class Applied(
			val deepPending: Boolean,
			val tombstoned: List<String>,
			val contentComplete: Boolean? = null,
			val reconciled: List<String> = emptyList(),
		) : Discovered
		data object Failed : Discovered
	}

	/** On [io]. Null when the pass was skipped as fresh enough. */
	private suspend fun discover(
		disc: MySnapsDiscovery,
		raw: String,
		who: String,
		force: Boolean,
		started: Long,
		manual: Boolean = false,
	): Discovered? {
		val rows = catalog() ?: return Discovered.Failed
		val checkpoint = runCatching { CatalogCheckpoint.decode(rows.checkpoint(who)) }.getOrNull()
			?: return Discovered.Failed
		val last = checkpoint.lastSuccessMs
		if (!force && last != null && clock() - last < STALE_AFTER_MILLIS) {
			return Discovered.Applied(!checkpoint.deepDone, emptyList())
		}
		val result = runCatching { disc.pass(raw, checkpoint, clock()) }.getOrNull()
		if (result !is MySnapsDiscovery.Result.Done) return Discovered.Failed

		// Candidates for the exact-read check. A known Snap in the span just
		// scanned that Hive no longer lists, or one the rotation reaches, is only a
		// candidate: it is tombstoned when current nodes prove it gone — never
		// because a page left it out.
		val from = result.coveredFrom
		val to = result.coveredTo
		val inSpan = if (from != null && to != null && from + INVERSION_GUARD_SEC <= to) {
			runCatching { rows.createdBetween(who, from + INVERSION_GUARD_SEC, to) }
				.getOrDefault(emptyList())
				.filter { it.contentId !in result.seen }
		} else {
			emptyList()
		}
		// The in-span check never takes the rotation's share of the budget.
		val inSpanChecked = inSpan.take(MAX_ABSENCE_CHECKS - ROTATION_CHECKS)
		val checkedIds = inSpanChecked.mapTo(HashSet()) { it.contentId }

		// The rotating check: a few more catalog rows each pass, newest first and
		// round the whole catalog over time, so a Snap deleted long after it was
		// posted — elsewhere, or by an interrupted delete here — is found too.
		// Rows this pass saw on Hive or is already checking are passed over; an
		// in-span row the budget left out stays in the rotation's reach.
		val after = checkpoint.verifyAt?.let { at ->
			MySnapRow(who, who, checkpoint.verifyPermlink!!, at, null, null, null, null, null, null)
		}
		val window = runCatching { rows.page(who, ROTATION_WINDOW, after) }.getOrDefault(emptyList())
		val rotating = window
			.filter { it.contentId !in result.seen && it.contentId !in checkedIds }
			.take(ROTATION_CHECKS)

		// Exact reads, before anything is stored, so the cursor can say how far
		// the rotation truly got.
		val verdicts = (inSpanChecked + rotating).associate { row ->
			row.contentId to runCatching { provenAbsent(row.author, row.permlink) }.getOrNull()
		}
		// The rotation advances past a row only once that row had an answer. An
		// undecided row holds the cursor, and is the first one read next pass.
		val firstUndecided = rotating.indexOfFirst { verdicts[it.contentId] == null }
		val reached: MySnapRow? = when {
			firstUndecided == 0 -> after
			firstUndecided > 0 -> rotating[firstUndecided - 1]
			rotating.size == ROTATION_CHECKS -> rotating.last()
			window.size == ROTATION_WINDOW -> window.last()
			else -> null // the end of the catalog: start again from the top
		}
		// Manual only: the current words of older discovered Snaps this pass did
		// not see. Reads before the lock, like the exact reads above.
		val sweep = if (manual) contentSweep(rows, raw, who, result.seen, checkpoint) else null
		val next = result.checkpoint.copy(
			verifyAt = reached?.createdAtEpochSec,
			verifyPermlink = reached?.permlink,
			contentAt = if (sweep != null) sweep.cursor?.createdAtEpochSec else checkpoint.contentAt,
			contentPermlink = if (sweep != null) sweep.cursor?.permlink else checkpoint.contentPermlink,
		)

		val reconciled = runCatching {
			lock.withLock {
				// Exact reads may have waited while a local edit was proven. Check
				// its stamp at persistence, after those reads and inside the write lock.
				fun fresh(row: MySnapRow): Boolean {
					val edited = edits["$who|${row.contentId}"]
					return edited == null || edited < started
				}
				val found = result.found.map { row -> if (fresh(row)) row else row.copy(userText = null) }
				// Stage 47E: words for Snaps already listed — another frontend's
				// edit seen by this pass, and a manual refresh's exact reads — in
				// the same transaction as the checkpoint and its content cursor.
				// A failure stores neither, and the pass fails.
				val words = result.known.filter(::fresh) + sweep?.accepted.orEmpty().filter(::fresh)
				rows.applyDiscovery(who, found, next.encode(), clock(), words)
			}
		}.getOrElse { return Discovered.Failed }

		val tombstoned = mutableListOf<String>()
		(inSpanChecked + rotating).filter { verdicts[it.contentId] == true }.forEach { row ->
			val ok = runCatching {
				lock.withLock { rows.tombstone(who, row.author, row.permlink, null, clock()) }
			}.isSuccess
			if (ok) tombstoned += row.contentId
		}
		return Discovered.Applied(!result.checkpoint.deepDone, tombstoned, sweep?.complete, reconciled)
	}

	private class Sweep(val accepted: List<MySnapRow>, val cursor: MySnapRow?, val complete: Boolean)

	/**
	 * On [io]. Exact reads of known catalog rows — discovered or locally
	 * published alike — newest first after the stored cursor, skipping what
	 * this pass already saw. At most [CONTENT_CHECKS]
	 * reads. A read that throws is undecided: the cursor holds before it and
	 * the sweep is incomplete. An absent or foreign answer is decided and
	 * changes nothing. Every accepted answer is still exactly this Snap, under
	 * every identity check but the app tag.
	 */
	private fun contentSweep(
		rows: MySnapRows,
		raw: String,
		who: String,
		seen: Set<String>,
		from: CatalogCheckpoint,
	): Sweep {
		val start = from.contentAt?.let { at ->
			MySnapRow(who, who, from.contentPermlink!!, at, null, null, null, null, null, null)
		}
		val accepted = mutableListOf<MySnapRow>()
		var after = start
		var cursor = start
		var undecided = false
		var reads = 0
		while (true) {
			val page = runCatching { rows.page(who, CONTENT_PAGE, after) }.getOrNull()
				?: return Sweep(accepted, cursor, complete = false)
			for (row in page) {
				after = row
				if (row.contentId in seen) {
					if (!undecided) cursor = row
					continue
				}
				if (reads >= CONTENT_CHECKS) return Sweep(accepted, cursor, complete = false)
				reads++
				val answer = runCatching { readRoot(row.author, row.permlink) }
				if (answer.isFailure) {
					undecided = true
					continue
				}
				answer.getOrNull()
					?.let { MySnapsIdentity.root(raw, it, rustedWaxApp = false) }
					?.takeIf { it.contentId == row.contentId }
					?.let { accepted += it }
				if (!undecided) cursor = row
			}
			if (page.size < CONTENT_PAGE) {
				// The end of the catalog: complete only when nothing was left undecided.
				return if (undecided) Sweep(accepted, cursor, complete = false) else Sweep(accepted, null, complete = true)
			}
		}
	}

	/** Up to [limit] + 1 rows, so the caller can tell whether more remain. Null on failure. */
	private fun read(who: String, limit: Int): List<MySnapRow>? =
		runCatching { catalog()?.page(who, limit + 1, null) }.getOrNull()

	private fun show(gen: Long, who: String, page: List<MySnapRow>?, limit: Int) {
		if (gen != generation || owner() != who || ownerState != who) return
		if (page == null) {
			// Keep whatever is on screen; a failed read is not an empty catalog.
			failedState = true
			readyState = true
			return
		}
		failedState = false
		loaded.clear()
		loaded += page.take(limit).filter { (who to it.contentId) !in deleted }
		moreState = page.size > limit
		readyState = true
	}

	companion object {
		const val PAGE_SIZE = 30

		/** The established Snap freshness rule: five minutes, the same as History's refresh. */
		const val STALE_AFTER_MILLIS = 5 * 60 * 1000L

		/** Rows this close to the bottom of a scanned span may sit just past it in Hivemind's order. */
		const val INVERSION_GUARD_SEC = 2 * 60 * 60L

		/** Exact reads per pass for Snaps Hive stopped listing. */
		const val MAX_ABSENCE_CHECKS = 10

		/** Catalog rows the rotating check looks through per pass… */
		const val ROTATION_WINDOW = 20

		/** …and how many of those it exact-reads. */
		const val ROTATION_CHECKS = 3

		/** Exact content reads per manual refresh (Stage 47E follow-up). */
		const val CONTENT_CHECKS = 40

		/** Catalog rows read from disk at a time while choosing them. */
		const val CONTENT_PAGE = 50
	}
}
