package com.rustedwax.app.scrobble

import android.content.Context
import com.rustedwax.app.detect.YouTubeProbe
import org.json.JSONArray
import org.json.JSONObject

/**
 * How many rows either retained list keeps.
 *
 * One constant rather than a literal at each site, because the in-memory cap
 * and the stored cap have to be the same number: a store that kept more would
 * resurrect rows the running app had already dropped, and one that kept fewer
 * would silently shorten History across a restart.
 */
internal const val RETAINED_ROWS = 50

/**
 * Where History and Not Logged live between one process and the next.
 *
 * Both lists are presentation state the engine has already finished producing —
 * a decided scrobble, or a decided refusal with its reason. Nothing here
 * participates in detection, measurement or finalization, and nothing read back
 * from this store is ever consulted to make a scrobbling decision. It exists so
 * that an ordinary Android process death stops erasing the user's own record of
 * what the app did.
 *
 * Deliberately not the dedup ledger and not the retry queue. Those two are
 * engine state with correctness consequences; these are rows on a screen. Kept
 * apart so that a damaged or deleted retained file can never be a reason a
 * listen is re-broadcast or dropped.
 */
internal interface RetainedRecordStore {

	fun loadHistory(): List<FinalizationRuntime.ScrobbleRecord>

	fun saveHistory(rows: List<FinalizationRuntime.ScrobbleRecord>)

	fun loadSkipped(): List<FinalizationRuntime.SkipRecord>

	fun saveSkipped(rows: List<FinalizationRuntime.SkipRecord>)
}

/**
 * The stored form of both lists, and the only thing that understands it.
 *
 * Pure, so the whole encode/decode contract — ordering, the cap, every field,
 * and what happens to bytes this code did not write — is testable without a
 * device.
 *
 * **Nothing on disk is taken on trust, even though this app wrote it.** A row
 * is rebuilt only when every field it cannot do without is present and still
 * valid; anything else is dropped. Restoring one row fewer costs a line on a
 * screen, whereas restoring a row assembled from bytes this code does not
 * understand would be telling the user something happened that may not have.
 * An unreadable blob restores as nothing at all, which is exactly the state the
 * app was already in before this store existed.
 */
internal object RetainedRecordCodec {

	fun encodeHistory(rows: List<FinalizationRuntime.ScrobbleRecord>): String {
		val array = JSONArray()
		rows.take(RETAINED_ROWS).forEach { row ->
			array.put(
				JSONObject()
					.put("title", row.title)
					.put("artist", row.artist ?: JSONObject.NULL)
					.put("percent", row.percentPlayed)
					.put("at", row.atEpochSec)
					.put("status", row.status)
					.put("tx", row.txId ?: JSONObject.NULL)
					.put("queued", row.queued)
					.put("videoId", row.videoId)
					.put("eventId", row.eventId)
					.put("account", row.account),
			)
		}
		return array.toString()
	}

	fun decodeHistory(raw: String?): List<FinalizationRuntime.ScrobbleRecord> =
		decodeArray(raw) { o ->
			// The same gate the writer applies. History is a hyperlink surface,
			// so a row that cannot prove its video is not a History row — on the
			// way in or on the way back.
			val videoId = (o.opt("videoId") as? String)
				?.takeIf { YouTubeProbe.canonicalWatchUrl(it) != null } ?: return@decodeArray null
			val eventId = (o.opt("eventId") as? String)?.takeIf { it.isNotBlank() }
				?: return@decodeArray null
			// Non-null in the model and non-blank in practice: a scrobble cannot
			// exist without an account that signed it. A row that lost its stamp
			// would be visible to whoever signed in next, which is the leak the
			// account boundary exists to stop, so it is dropped instead.
			val account = (o.opt("account") as? String)?.takeIf { it.isNotBlank() }
				?: return@decodeArray null
			val title = (o.opt("title") as? String) ?: return@decodeArray null
			val status = (o.opt("status") as? String) ?: return@decodeArray null
			val percent = (o.opt("percent") as? Number)?.toInt() ?: return@decodeArray null
			val at = (o.opt("at") as? Number)?.toLong() ?: return@decodeArray null
			val queued = (o.opt("queued") as? Boolean) ?: return@decodeArray null
			FinalizationRuntime.ScrobbleRecord(
				title = title,
				artist = o.opt("artist") as? String,
				percentPlayed = percent,
				atEpochSec = at,
				status = status,
				txId = o.opt("tx") as? String,
				queued = queued,
				videoId = videoId,
				eventId = eventId,
				account = account,
			)
		}

	fun encodeSkipped(rows: List<FinalizationRuntime.SkipRecord>): String {
		val array = JSONArray()
		rows.take(RETAINED_ROWS).forEach { row ->
			array.put(
				JSONObject()
					.put("title", row.title)
					.put("artist", row.artist ?: JSONObject.NULL)
					.put("reason", row.reason)
					.put("at", row.atEpochSec)
					.put("played", row.playedSeconds)
					.put("duration", row.durationSeconds ?: JSONObject.NULL)
					.put("videoId", row.videoId ?: JSONObject.NULL)
					// Written as a real value, including its absence. Null is the
					// signed-out device's own stamp and not a placeholder, so it
					// has to survive the round trip as null rather than as a
					// missing key that could later be read as "unknown".
					.put("account", row.account ?: JSONObject.NULL),
			)
		}
		return array.toString()
	}

	fun decodeSkipped(raw: String?): List<FinalizationRuntime.SkipRecord> =
		decodeArray(raw) { o ->
			val title = (o.opt("title") as? String) ?: return@decodeArray null
			val reason = (o.opt("reason") as? String) ?: return@decodeArray null
			val at = (o.opt("at") as? Number)?.toLong() ?: return@decodeArray null
			val played = (o.opt("played") as? Number)?.toLong() ?: return@decodeArray null
			val account = when {
				!o.has("account") -> return@decodeArray null
				o.isNull("account") -> null
				else -> (o.opt("account") as? String)?.takeIf { it.isNotBlank() }
					?: return@decodeArray null
			}
			FinalizationRuntime.SkipRecord(
				title = title,
				artist = o.opt("artist") as? String,
				reason = reason,
				atEpochSec = at,
				playedSeconds = played,
				durationSeconds = (o.opt("duration") as? Number)?.toLong(),
				// A refusal whose id no longer validates keeps the row and loses
				// the link, which is what the writer does with an unproven id.
				// The row still answers "why wasn't this scrobbled"; that is the
				// entire job of the tab, and it does not depend on the link.
				videoId = (o.opt("videoId") as? String)
					?.takeIf { YouTubeProbe.canonicalWatchUrl(it) != null },
				// `opt` rather than `optString`, which turns JSON null into the
				// four-character string "null" — an account name that matches
				// nobody and hides the row from the signed-out device it belongs
				// to.
				account = account,
			)
		}

	/**
	 * Walk a stored array, keeping the rows [row] can rebuild and no others.
	 *
	 * Order is the stored order, which is the list's own order: newest first.
	 * A blob that is not an array at all yields nothing rather than throwing —
	 * the caller's next write replaces it.
	 */
	private inline fun <T> decodeArray(raw: String?, row: (JSONObject) -> T?): List<T> {
		val text = raw?.takeIf { it.isNotBlank() } ?: return emptyList()
		val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
		val out = ArrayList<T>(minOf(array.length(), RETAINED_ROWS))
		for (i in 0 until array.length()) {
			if (out.size == RETAINED_ROWS) break
			val o = array.optJSONObject(i) ?: continue
			val decoded = runCatching { row(o) }.getOrNull() ?: continue
			out.add(decoded)
		}
		return out
	}
}

/**
 * The retained lists, in this app's own preferences.
 *
 * Two string values in one file rather than a row per key: the lists are
 * bounded, small, and only ever read or written whole, so a single `apply()`
 * per change is both the cheapest write and the one that cannot leave half a
 * list behind. `apply()` also keeps the disk write off the caller — one of
 * these writes happens on the media-session callback, which is the main looper.
 *
 * Every entry point is fail-safe. A read that cannot produce a list produces an
 * empty one; a write that cannot complete is reported and dropped. Neither is
 * allowed to propagate: the retained lists are a record of what the app did,
 * and losing that record must never be able to stop the app doing it.
 */
internal class SharedPreferencesRetainedRecords(context: Context) : RetainedRecordStore {

	private val prefs =
		context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

	override fun loadHistory(): List<FinalizationRuntime.ScrobbleRecord> =
		RetainedRecordCodec.decodeHistory(read(KEY_HISTORY))

	override fun saveHistory(rows: List<FinalizationRuntime.ScrobbleRecord>) {
		write(KEY_HISTORY, RetainedRecordCodec.encodeHistory(rows))
	}

	override fun loadSkipped(): List<FinalizationRuntime.SkipRecord> =
		RetainedRecordCodec.decodeSkipped(read(KEY_SKIPPED))

	override fun saveSkipped(rows: List<FinalizationRuntime.SkipRecord>) {
		write(KEY_SKIPPED, RetainedRecordCodec.encodeSkipped(rows))
	}

	private fun read(key: String): String? =
		runCatching { prefs.getString(key, null) }.getOrNull()

	private fun write(key: String, value: String) {
		runCatching { prefs.edit().putString(key, value).apply() }
	}

	private companion object {
		const val FILE_NAME = "rustedwax_retained"
		const val KEY_HISTORY = "history_v1"
		const val KEY_SKIPPED = "not_logged_v1"
	}
}
