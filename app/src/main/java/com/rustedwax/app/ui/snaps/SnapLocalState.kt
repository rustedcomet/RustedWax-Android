package com.rustedwax.app.ui.snaps

import android.content.Context
import android.content.SharedPreferences
import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.snaps.PostedSnapCache
import com.rustedwax.app.snaps.PostedSnapCacheEntry
import com.rustedwax.app.snaps.SnapThreadPreview
import com.rustedwax.app.storage.LegacyKeyedSource
import com.rustedwax.app.storage.LocalMirror
import com.rustedwax.app.storage.MirrorRows
import com.rustedwax.app.storage.MirrorTableSpec
import com.rustedwax.app.storage.SharedPreferencesKeyedSource
import com.rustedwax.app.storage.SqliteMirrorRows
import com.rustedwax.app.storage.db.LocalDatabase
import com.rustedwax.app.storage.db.LocalDatabaseSchema
import com.rustedwax.app.storage.db.LocalOwner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Snap display and local state in the structured database (Issue #41 Slice 3):
 * root Snap drafts, the posted-Snap cache, each account's last Snap refresh,
 * and thread previews.
 *
 * Each is a [LocalMirror] over the SharedPreferences file it has always used,
 * so identities are exactly today's keys — `account|eventId` for a draft,
 * `account|author/permlink` for a cached Snap and a preview, the account for a
 * refresh stamp — and the file stays a complete shadow for this release.
 *
 * Deliberately not here: pending Snaps and replies, reply drafts and their
 * intent ids, and notices. Those either authorize Hive writes or are a later
 * slice, and stay exactly where they are.
 *
 * One instance per process ([get]): the mirrors are in memory, and two copies
 * would each believe they held the truth.
 */
internal class SnapLocalState(
	draftSource: LegacyKeyedSource<String>,
	cacheSource: LegacyKeyedSource<CachedSnap>,
	refreshSource: LegacyKeyedSource<Long>,
	previewSource: LegacyKeyedSource<StoredPreview>,
	private val nowMillis: () -> Long = System::currentTimeMillis,
	report: (String) -> Unit = {},
	writer: java.util.concurrent.Executor = LocalMirror.WRITER,
) {

	/** A cached chain body, the record body it was reconciled against, and when it was written. */
	data class CachedSnap(val chainBody: String, val recordBody: String, val atMillis: Long)

	/** A preview as stored: its count, its items exactly as encoded, and when it was written (epoch seconds). */
	data class StoredPreview(val total: Int, val itemsJson: String, val atEpochSec: Long)

	val draftMirror = LocalMirror(SOURCE_DRAFTS, draftSource, SnapLocalKeys::draftStorable, null, writer, nowMillis, report)

	val cacheMirror = LocalMirror(
		SOURCE_POSTED, cacheSource, SnapLocalKeys::accountKeyStorable,
		LocalMirror.Retention(MAX_POSTED_PER_OWNER, SnapLocalKeys::owner) { it.atMillis },
		writer, nowMillis, report,
	)

	val refreshMirror = LocalMirror(SOURCE_REFRESH, refreshSource, SnapLocalKeys::accountStorable, null, writer, nowMillis, report)

	val previewMirror = LocalMirror(
		SOURCE_PREVIEWS, previewSource, SnapLocalKeys::draftStorable,
		LocalMirror.Retention(MAX_PREVIEWS_PER_OWNER, SnapLocalKeys::owner) { it.atEpochSec },
		writer, nowMillis, report,
	)

	/** Root Snap drafts. An empty draft is an absent one, as before. */
	val drafts: SnapDraftStore = object : SnapDraftStore {
		override fun read(key: String): String = draftMirror.get(key) ?: ""
		override fun write(key: String, text: String) {
			if (text.isEmpty()) draftMirror.remove(key) else draftMirror.put(key, text)
		}
		override fun clear(key: String) = draftMirror.remove(key)
		override fun keys(): Set<String> = draftMirror.keys()
	}

	/** The posted-Snap cache and refresh stamps, with the same body limit as before. */
	val postedCache: PostedSnapCache = object : PostedSnapCache {
		override fun read(account: String, contentId: String): PostedSnapCacheEntry? =
			cacheMirror.get(SnapLocalKeys.of(account, contentId))
				?.let { PostedSnapCacheEntry(chainBody = it.chainBody, recordBody = it.recordBody) }

		override fun write(account: String, contentId: String, entry: PostedSnapCacheEntry) {
			if (entry.chainBody.length > MAX_BODY_CHARS || entry.recordBody.length > MAX_BODY_CHARS) return
			cacheMirror.put(SnapLocalKeys.of(account, contentId), CachedSnap(entry.chainBody, entry.recordBody, nowMillis()))
		}

		override fun lastRefreshed(account: String): Long? = refreshMirror.get(account)

		override fun markRefreshed(account: String, atMillis: Long) = refreshMirror.put(account, atMillis)
	}

	/** Thread previews, re-validated on every read exactly as before. */
	val previews: SnapThreadPreviewStore = object : SnapThreadPreviewStore {
		override fun read(key: String): SnapThreadPreview.Preview? =
			previewMirror.get(key)?.let { SnapThreadPreviewCodec.decode(SnapLocalCodec.previewJson(it)) }

		override fun write(key: String, preview: SnapThreadPreview.Preview) {
			val raw = SnapThreadPreviewCodec.encode(preview, nowMillis() / 1000)
			SnapLocalCodec.preview(raw)?.let { previewMirror.put(key, it) }
		}
	}

	fun start(open: SnapLocalTables) {
		draftMirror.start(open.drafts)
		cacheMirror.start(open.posted)
		refreshMirror.start(open.refresh)
		previewMirror.start(open.previews)
	}

	/** How each mirror opens its table; production uses [sqlite]. */
	class SnapLocalTables(
		val drafts: () -> MirrorRows<String>,
		val posted: () -> MirrorRows<CachedSnap>,
		val refresh: () -> MirrorRows<Long>,
		val previews: () -> MirrorRows<StoredPreview>,
	)

	companion object {
		const val SOURCE_DRAFTS = "snap_drafts"
		const val SOURCE_POSTED = "posted_snap_cache"
		const val SOURCE_REFRESH = "snap_refresh"
		const val SOURCE_PREVIEWS = "thread_previews"

		const val MAX_POSTED_PER_OWNER = 100
		const val MAX_PREVIEWS_PER_OWNER = 60
		const val MAX_BODY_CHARS = 8_192

		const val DRAFTS_FILE = "rustedwax_snap_drafts"
		const val POSTED_FILE = "rustedwax_posted_snap_cache"
		const val PREVIEWS_FILE = SharedPreferencesSnapThreadPreviewStore.THREAD_PREVIEWS

		@Volatile
		private var instance: SnapLocalState? = null

		/** The process's one instance, migrated in the background on first use. */
		fun get(context: Context): SnapLocalState = instance ?: synchronized(this) {
			instance ?: create(context.applicationContext).also { instance = it }
		}

		private fun create(context: Context): SnapLocalState {
			fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
			val state = SnapLocalState(
				draftSource = SnapLocalCodec.draftSource(prefs(DRAFTS_FILE)),
				cacheSource = SnapLocalCodec.cacheSource(prefs(POSTED_FILE)),
				refreshSource = SnapLocalCodec.refreshSource(prefs(POSTED_FILE)),
				previewSource = SnapLocalCodec.previewSource(prefs(PREVIEWS_FILE)),
				report = { runCatching { EventLog.append("snaps", it) } },
			)
			val db = { LocalDatabase.get(context) }
			state.start(
				SnapLocalTables(
					drafts = { SqliteMirrorRows(db(), SOURCE_DRAFTS, SnapLocalCodec.DRAFT_TABLE) },
					posted = { SqliteMirrorRows(db(), SOURCE_POSTED, SnapLocalCodec.POSTED_TABLE) },
					refresh = { SqliteMirrorRows(db(), SOURCE_REFRESH, SnapLocalCodec.REFRESH_TABLE) },
					previews = { SqliteMirrorRows(db(), SOURCE_PREVIEWS, SnapLocalCodec.PREVIEW_TABLE) },
				),
			)
			return state
		}
	}
}

/**
 * Keys as callers already spell them. Exact strings throughout: nothing is
 * normalised, inferred or matched loosely — a key the database's owner rule
 * cannot hold simply stays a legacy-only entry.
 */
internal object SnapLocalKeys {

	fun of(owner: String, id: String): String = "$owner|$id"

	fun owner(key: String): String = key.substringBefore('|')

	fun id(key: String): String = key.substringAfter('|', "")

	/** `owner|id` where the owner is a stored account or the signed-out `-`. */
	fun draftStorable(key: String): Boolean {
		val owner = owner(key)
		return key.contains('|') && id(key).isNotEmpty() &&
			(owner == LocalOwner.SIGNED_OUT || LocalOwner.account(owner) == owner)
	}

	/** `account|id`: the posted cache is only ever written for a signed-in account. */
	fun accountKeyStorable(key: String): Boolean =
		key.contains('|') && id(key).isNotEmpty() && LocalOwner.account(owner(key)) == owner(key)

	fun accountStorable(key: String): Boolean = LocalOwner.account(key) == key
}

/** Legacy formats and table mappings for the four sources — unchanged from the stores they replace. */
internal object SnapLocalCodec {

	private const val GENERATION = "#rustedwax-generation"
	private const val ENTRY = "e|"
	private const val STAMP = "t|"

	fun draftSource(prefs: SharedPreferences) = SharedPreferencesKeyedSource(
		prefs = prefs,
		generationKey = GENERATION,
		toMirrorKey = { it },
		toStoredKey = { it },
		decode = { value -> (value as? String)?.takeIf { it.isNotEmpty() } },
		encode = { edit, key, text -> edit.putString(key, text) },
	)

	fun cacheSource(prefs: SharedPreferences) = SharedPreferencesKeyedSource(
		prefs = prefs,
		generationKey = "$GENERATION:posted",
		toMirrorKey = { stored -> stored.takeIf { it.startsWith(ENTRY) }?.removePrefix(ENTRY) },
		toStoredKey = { ENTRY + it },
		decode = { value ->
			val json = JSONObject(value as String)
			SnapLocalState.CachedSnap(json.getString("chain"), json.getString("record"), json.optLong("at", 0L))
		},
		encode = { edit, key, row ->
			edit.putString(
				key,
				JSONObject().put("chain", row.chainBody).put("record", row.recordBody).put("at", row.atMillis).toString(),
			)
		},
	)

	fun refreshSource(prefs: SharedPreferences) = SharedPreferencesKeyedSource(
		prefs = prefs,
		generationKey = "$GENERATION:refresh",
		toMirrorKey = { stored -> stored.takeIf { it.startsWith(STAMP) }?.removePrefix(STAMP) },
		toStoredKey = { STAMP + it },
		decode = { value -> (value as? Long)?.takeIf { it >= 0 } },
		encode = { edit, key, at -> edit.putLong(key, at) },
	)

	fun previewSource(prefs: SharedPreferences) = SharedPreferencesKeyedSource(
		prefs = prefs,
		generationKey = GENERATION,
		toMirrorKey = { it },
		toStoredKey = { it },
		decode = { value -> preview(value as String) },
		encode = { edit, key, row -> edit.putString(key, previewJson(row)) },
	)

	/** A stored preview from its legacy JSON — only when the existing codec accepts it. */
	fun preview(raw: String): SnapLocalState.StoredPreview? {
		if (SnapThreadPreviewCodec.decode(raw) == null) return null
		val json = JSONObject(raw)
		return SnapLocalState.StoredPreview(
			total = json.getInt("total"),
			itemsJson = json.getJSONArray("items").toString(),
			atEpochSec = json.optLong("at", 0L),
		)
	}

	/** The legacy JSON for a stored preview, in the key order the existing codec writes. */
	fun previewJson(row: SnapLocalState.StoredPreview): String =
		JSONObject().put("at", row.atEpochSec).put("total", row.total).put("items", JSONArray(row.itemsJson)).toString()

	val DRAFT_TABLE = MirrorTableSpec<String>(
		table = LocalDatabaseSchema.SNAP_DRAFT,
		keyColumns = listOf("owner", "event_id"),
		split = { listOf(SnapLocalKeys.owner(it), SnapLocalKeys.id(it)) },
		join = { SnapLocalKeys.of(it[0], it[1]) },
		write = { text, values ->
			values.put("text", text)
			values.put("updated_at", System.currentTimeMillis())
		},
		read = { c -> c.getString(c.getColumnIndexOrThrow("text")) },
	)

	val POSTED_TABLE = MirrorTableSpec<SnapLocalState.CachedSnap>(
		table = LocalDatabaseSchema.POSTED_SNAP_CACHE,
		keyColumns = listOf("owner", "content_id"),
		split = { listOf(SnapLocalKeys.owner(it), SnapLocalKeys.id(it)) },
		join = { SnapLocalKeys.of(it[0], it[1]) },
		write = { row, values ->
			values.put("chain_body", row.chainBody)
			values.put("record_body", row.recordBody)
			values.put("at", row.atMillis)
		},
		read = { c ->
			SnapLocalState.CachedSnap(
				c.getString(c.getColumnIndexOrThrow("chain_body")),
				c.getString(c.getColumnIndexOrThrow("record_body")),
				c.getLong(c.getColumnIndexOrThrow("at")),
			)
		},
	)

	val REFRESH_TABLE = MirrorTableSpec<Long>(
		table = LocalDatabaseSchema.SNAP_REFRESH,
		keyColumns = listOf("owner"),
		split = { listOf(it) },
		join = { it[0] },
		write = { at, values -> values.put("last_refreshed_ms", at) },
		read = { c -> c.getLong(c.getColumnIndexOrThrow("last_refreshed_ms")) },
	)

	val PREVIEW_TABLE = MirrorTableSpec<SnapLocalState.StoredPreview>(
		table = LocalDatabaseSchema.THREAD_PREVIEW,
		keyColumns = listOf("owner", "root_id"),
		split = { listOf(SnapLocalKeys.owner(it), SnapLocalKeys.id(it)) },
		join = { SnapLocalKeys.of(it[0], it[1]) },
		write = { row, values ->
			values.put("total", row.total)
			values.put("items_json", row.itemsJson)
			values.put("at", row.atEpochSec)
		},
		read = { c ->
			SnapLocalState.StoredPreview(
				total = c.getInt(c.getColumnIndexOrThrow("total")),
				itemsJson = c.getString(c.getColumnIndexOrThrow("items_json")),
				atEpochSec = c.getLong(c.getColumnIndexOrThrow("at")),
			)
		},
	)
}
