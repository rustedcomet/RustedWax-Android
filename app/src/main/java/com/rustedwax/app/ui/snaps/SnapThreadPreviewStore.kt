package com.rustedwax.app.ui.snaps

import android.content.Context
import android.content.SharedPreferences
import com.rustedwax.app.snaps.HiveAccountName
import com.rustedwax.app.snaps.SnapReplies
import com.rustedwax.app.snaps.SnapThreadPreview
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last thing a History card successfully knew about a conversation.
 *
 * Deliberately **not** a conversation cache. What is stored is the card's own
 * summary and nothing else: a reply count and at most
 * [SnapThreadPreview.MAX_PREVIEWS] already-clamped lines. A few hundred bytes
 * per root.
 *
 * Storing the thread itself was considered and rejected. A conversation has no
 * size limit by design — [com.rustedwax.app.snaps.SnapThreadBuilder] keeps every
 * valid reply — so the bodies are unbounded, and `SharedPreferences` reads its
 * entire file into memory at startup. The full thread stays Hive-authoritative
 * and is fetched when the thread is opened, which is the one moment the reader
 * is actually waiting for it.
 *
 * Why it exists at all: the controllers live in the Activity's composition, so
 * closing the app from Recents destroys them even though the process survives.
 * Without this, a reopened card forgets a conversation it had already read and
 * regresses to showing nothing until the network answers again.
 *
 * Nothing secret is kept here. Hive comments are public, and only the author,
 * the permlink and the visible preview text are written — no drafts, no intent
 * ids, no key material.
 */
internal interface SnapThreadPreviewStore {
	/**
	 * The stored summary, or null when there is none **or it cannot be trusted**.
	 *
	 * Null is the safe answer for a damaged entry: a card with no preview is a
	 * card that simply waits for the refresh, whereas a card drawn from a record
	 * this code could not fully validate would be asserting somebody's words on
	 * the strength of bytes it does not understand.
	 */
	fun read(key: String): SnapThreadPreview.Preview?

	/** Replace the summary for this key, evicting the oldest if the store is full. */
	fun write(key: String, preview: SnapThreadPreview.Preview)
}

internal class SharedPreferencesSnapThreadPreviewStore(
	context: Context,
	private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
) : SnapThreadPreviewStore {

	private val prefs: SharedPreferences =
		context.getSharedPreferences(THREAD_PREVIEWS, Context.MODE_PRIVATE)

	override fun read(key: String): SnapThreadPreview.Preview? {
		val raw = prefs.getString(key, null) ?: return null
		return decode(raw)
	}

	@Synchronized
	override fun write(key: String, preview: SnapThreadPreview.Preview) {
		val ages = prefs.all.keys.associateWith { k ->
			prefs.getString(k, null)?.let(SnapThreadPreviewCodec::storedAt) ?: 0L
		}
		val edit = prefs.edit()
		edit.putString(key, SnapThreadPreviewCodec.encode(preview, nowEpochSec()))
		SnapThreadPreviewCodec.surplusKeys(ages, keeping = key, max = MAX_ENTRIES)
			.forEach(edit::remove)
		// apply(), not commit(): this is a display cache. Losing the last write
		// to a crash costs one card one refresh, which the refresh was going to
		// do anyway — nothing here is load-bearing for a publication.
		edit.apply()
	}

	private fun decode(raw: String) = SnapThreadPreviewCodec.decode(raw)

	companion object {
		const val THREAD_PREVIEWS = "rustedwax_snap_thread_previews"

		/** Roughly a season of Snapped rows; small, and bounded. */
		const val MAX_ENTRIES = 60
	}
}

/**
 * The storage format and its rules, with no Android in sight.
 *
 * Split out so the part that decides what may be trusted off disk, and what may
 * be thrown away, is testable on its own — the preference file is only where
 * the bytes happen to live.
 */
internal object SnapThreadPreviewCodec {

	fun storedAt(raw: String): Long =
		runCatching { JSONObject(raw).optLong("at", 0L) }.getOrDefault(0L)

	/**
	 * Keys to drop so the store cannot grow for the life of the install.
	 *
	 * Every Snap ever posted would otherwise leave an entry behind forever.
	 * Oldest-written first, and never the key being written.
	 */
	fun surplusKeys(ages: Map<String, Long>, keeping: String, max: Int): List<String> {
		val others = ages.filterKeys { it != keeping }
		if (others.size < max) return emptyList()
		return others.entries
			.sortedWith(compareBy({ it.value }, { it.key }))
			.take(others.size - max + 1)
			.map { it.key }
	}

	fun encode(preview: SnapThreadPreview.Preview, at: Long): String {
		val items = JSONArray()
		preview.items.take(SnapThreadPreview.MAX_PREVIEWS).forEach { item ->
			items.put(
				JSONObject()
					.put("author", item.author)
					.put("permlink", item.permlink)
					.put("text", item.text)
					.put("truncated", item.truncated),
			)
		}
		return JSONObject()
			.put("at", at)
			.put("total", preview.total)
			.put("items", items)
			.toString()
	}

	/**
	 * Rebuild a summary from stored bytes, **re-validating every field**.
	 *
	 * Nothing on disk is taken on trust, even though this app wrote it: a file
	 * can be edited, truncated or written by an older build, and the values here
	 * become an account handle, an avatar request and visible text on somebody's
	 * History card. So the author must still be a Hive account name, the
	 * permlink must still be a permlink, the text is re-clamped rather than
	 * measured, the item list is re-capped, and a count that cannot be
	 * reconciled with the items is refused outright.
	 */
	fun decode(raw: String): SnapThreadPreview.Preview? = runCatching {
		val o = JSONObject(raw)
		if (!o.has("total") || !o.has("items")) return@runCatching null
		val total = (o.opt("total") as? Int) ?: return@runCatching null
		if (total < 0) return@runCatching null

		val array = o.opt("items") as? JSONArray ?: return@runCatching null
		if (array.length() > SnapThreadPreview.MAX_PREVIEWS) return@runCatching null

		val items = ArrayList<SnapThreadPreview.Item>(array.length())
		for (i in 0 until array.length()) {
			val entry = array.opt(i) as? JSONObject ?: return@runCatching null
			val author = (entry.opt("author") as? String)
				?.takeIf { HiveAccountName.isValid(it) } ?: return@runCatching null
			val permlink = (entry.opt("permlink") as? String)
				?.takeIf { SnapReplies.isPermlink(it) } ?: return@runCatching null
			val text = (entry.opt("text") as? String) ?: return@runCatching null
			val truncated = (entry.opt("truncated") as? Boolean) ?: return@runCatching null
			items += SnapThreadPreview.Item(
				author = author,
				permlink = permlink,
				// Re-clamped, never trusted: a stored line longer than the card
				// allows would make the card taller than the rule permits.
				text = SnapThreadPreview.clampText(text),
				truncated = truncated,
			)
		}

		// A total below what is shown would make `hasMore` lie in the one
		// direction that matters — hiding a way through to replies that exist.
		if (total < items.size) return@runCatching null
		SnapThreadPreview.Preview(items = items, total = total)
	}.getOrNull()
}
