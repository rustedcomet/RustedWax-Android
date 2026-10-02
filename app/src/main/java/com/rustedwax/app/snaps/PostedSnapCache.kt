package com.rustedwax.app.snaps

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * The last chain body a root Snap was successfully read with (Issue 40C).
 *
 * The chain is the source of truth for what a Snap says; the stored pending
 * record is only the write path's association — which author/permlink, and
 * which generated link, are this app's. Without this, a Snap another frontend
 * edited would draw the old local words again every time the app reopened,
 * until (and unless) the network answered.
 *
 * [recordBody] is the stored record body this entry was reconciled against.
 * When RustedWax itself edits the Snap, that record moves, and an entry made
 * against the older one no longer describes the newest known state — so it is
 * ignored rather than allowed to draw words the user has since replaced.
 */
data class PostedSnapCacheEntry(
	val chainBody: String,
	val recordBody: String,
)

/**
 * Account-scoped cache of [PostedSnapCacheEntry] plus each account's last
 * fully successful Snap refresh time.
 *
 * Written only after a successful, identity-checked chain read. A failed read
 * writes nothing, so the cache only ever moves forward.
 */
interface PostedSnapCache {
	fun read(account: String, contentId: String): PostedSnapCacheEntry?
	fun write(account: String, contentId: String, entry: PostedSnapCacheEntry)

	/** Epoch millis of [account]'s last fully successful Snap refresh, or null. */
	fun lastRefreshed(account: String): Long?
	fun markRefreshed(account: String, atMillis: Long)
}

/** Process-lifetime cache, for tests and for callers with no context. */
class InMemoryPostedSnapCache : PostedSnapCache {
	private val entries = mutableMapOf<String, PostedSnapCacheEntry>()
	private val stamps = mutableMapOf<String, Long>()

	@Synchronized
	override fun read(account: String, contentId: String) = entries["$account|$contentId"]

	@Synchronized
	override fun write(account: String, contentId: String, entry: PostedSnapCacheEntry) {
		entries["$account|$contentId"] = entry
	}

	@Synchronized
	override fun lastRefreshed(account: String) = stamps[account]

	@Synchronized
	override fun markRefreshed(account: String, atMillis: Long) {
		stamps[account] = atMillis
	}
}

/**
 * The on-disk form. Bounded: at most [MAX_ENTRIES] Snaps (oldest written goes
 * first) and no body over [MAX_BODY_CHARS] — a longer body is drawn, just not
 * remembered, because `SharedPreferences` loads the whole file into memory.
 * Only public Hive comment bodies and ids are kept.
 */
class SharedPreferencesPostedSnapCache(context: Context) : PostedSnapCache {

	private val prefs: SharedPreferences =
		context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

	override fun read(account: String, contentId: String): PostedSnapCacheEntry? = runCatching {
		val raw = prefs.getString(ENTRY + "$account|$contentId", null) ?: return null
		val json = JSONObject(raw)
		PostedSnapCacheEntry(
			chainBody = json.getString("chain"),
			recordBody = json.getString("record"),
		)
	}.getOrNull()

	@Synchronized
	override fun write(account: String, contentId: String, entry: PostedSnapCacheEntry) {
		if (entry.chainBody.length > MAX_BODY_CHARS || entry.recordBody.length > MAX_BODY_CHARS) return
		val key = ENTRY + "$account|$contentId"
		val json = JSONObject()
			.put("chain", entry.chainBody)
			.put("record", entry.recordBody)
			.put("at", System.currentTimeMillis())
		val edit = prefs.edit().putString(key, json.toString())
		val others = prefs.all.keys.filter { it.startsWith(ENTRY) && it != key }
		if (others.size >= MAX_ENTRIES) {
			others.sortedBy { k ->
				runCatching { JSONObject(prefs.getString(k, null)!!).getLong("at") }.getOrDefault(0L)
			}.take(others.size - MAX_ENTRIES + 1).forEach { edit.remove(it) }
		}
		edit.apply()
	}

	override fun lastRefreshed(account: String): Long? =
		prefs.getLong(STAMP + account, -1L).takeIf { it >= 0 }

	override fun markRefreshed(account: String, atMillis: Long) {
		prefs.edit().putLong(STAMP + account, atMillis).apply()
	}

	private companion object {
		const val FILE = "rustedwax_posted_snap_cache"
		const val ENTRY = "e|"
		const val STAMP = "t|"
		const val MAX_ENTRIES = 100
		const val MAX_BODY_CHARS = 8_192
	}
}
