package com.rustedwax.app.scrobble

import android.content.Context
import android.content.SharedPreferences
import com.rustedwax.app.storage.db.LocalOwner
import com.rustedwax.app.detect.YouTubeProbe
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * How many rows either retained list keeps **per owner** (Issue #41), and how
 * many entries the legacy blob itself holds.
 *
 * One constant rather than a literal at each site, because the in-memory cap
 * and the stored cap have to be the same number: a store that kept more would
 * resurrect rows the running app had already dropped, and one that kept fewer
 * would silently shorten History across a restart.
 */
internal const val RETAINED_ROWS = 50

/**
 * The one retention rule for both lists: at most [RETAINED_ROWS] rows **per
 * owner**, kept in list order (Issue #41).
 *
 * Used by the runtime's lists, by the legacy blob in both directions, and so
 * by everything restored from it. A single global cap let a second account's
 * rows push the first account's out; per owner, no account — and not the
 * signed-out device, which is an owner of its own — can evict another.
 */
internal object RetainedRetention {

	fun history(rows: List<FinalizationRuntime.ScrobbleRecord>): List<FinalizationRuntime.ScrobbleRecord> =
		perOwner(rows) { LocalOwner.of(it.account) }

	fun skipped(rows: List<FinalizationRuntime.SkipRecord>): List<FinalizationRuntime.SkipRecord> =
		perOwner(rows) { LocalOwner.of(it.account) }

	private inline fun <T> perOwner(rows: List<T>, owner: (T) -> String): List<T> {
		val kept = HashMap<String, Int>()
		return rows.filter { row ->
			val key = owner(row)
			val n = kept.getOrDefault(key, 0)
			(n < RETAINED_ROWS).also { if (it) kept[key] = n + 1 }
		}
	}
}

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
		RetainedRetention.history(rows).forEach { row ->
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
					.put("account", row.account)
					// Absent, not null, when the row never came from the queue, so
					// rows written before this field existed read back identically.
					.putOpt("queueOperationId", row.queueOperationId),
			)
		}
		return array.toString()
	}

	fun decodeHistory(raw: String?): List<FinalizationRuntime.ScrobbleRecord> =
		decodeHistoryRows(raw).let(RetainedRetention::history)

	private fun decodeHistoryRows(raw: String?): List<FinalizationRuntime.ScrobbleRecord> =
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
				// Optional: older rows have no such key, and that is not damage.
				queueOperationId = (o.opt("queueOperationId") as? String)?.takeIf { it.isNotBlank() },
			)
		}

	fun encodeSkipped(rows: List<FinalizationRuntime.SkipRecord>): String {
		val array = JSONArray()
		RetainedRetention.skipped(rows).forEach { row ->
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
					.put("account", row.account ?: JSONObject.NULL)
					// Issue #41. Older builds read by key and ignore this one.
					.put("rowId", row.rowId),
			)
		}
		return array.toString()
	}

	/**
	 * Not Logged rows, each with its [FinalizationRuntime.SkipRecord.rowId].
	 *
	 * A row written with an id keeps it. A row written before ids existed gets
	 * one derived from its own fields plus how many identical rows precede it
	 * in the same blob: the same bytes always yield the same ids, so importing
	 * a legacy blob twice cannot duplicate a row — and two identical twins get
	 * two ids instead of collapsing into one.
	 */
	fun decodeSkipped(raw: String?): List<FinalizationRuntime.SkipRecord> {
		val decoded = decodeSkippedFields(raw)
		val seen = HashMap<String, Int>()
		return decoded.map { (row, storedId) ->
			if (storedId != null) return@map row.copy(rowId = storedId)
			val canonical = legacySkipCanonical(row)
			val occurrence = seen.getOrDefault(canonical, 0).also { seen[canonical] = it + 1 }
			row.copy(rowId = "legacy-${sha256(canonical).take(32)}-$occurrence")
		}.let(RetainedRetention::skipped)
	}

	/** Every field a pre-#41 row was stored with, in a fixed order. */
	private fun legacySkipCanonical(row: FinalizationRuntime.SkipRecord): String =
		JSONArray()
			.put(row.title)
			.put(row.artist ?: JSONObject.NULL)
			.put(row.reason)
			.put(row.atEpochSec)
			.put(row.playedSeconds)
			.put(row.durationSeconds ?: JSONObject.NULL)
			.put(row.videoId ?: JSONObject.NULL)
			.put(row.account ?: JSONObject.NULL)
			.toString()

	/** Lowercase hex SHA-256 of [text]'s UTF-8 bytes. */
	fun sha256(text: String): String =
		MessageDigest.getInstance("SHA-256")
			.digest(text.toByteArray(Charsets.UTF_8))
			.joinToString("") { "%02x".format(it) }

	/**
	 * How many entries a stored blob holds, or null when it is not an array at
	 * all — which is how an unreadable blob is told apart from an empty one.
	 */
	fun storedEntries(raw: String?): Int? {
		val text = raw?.takeIf { it.isNotBlank() } ?: return 0
		return runCatching { JSONArray(text).length() }.getOrNull()
	}

	private fun decodeSkippedFields(raw: String?): List<Pair<FinalizationRuntime.SkipRecord, String?>> =
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
				// Filled in by [decodeSkipped], which sees the whole blob.
				rowId = "",
			) to (o.opt("rowId") as? String)?.takeIf { it.isNotBlank() }
		}

	/**
	 * Walk a stored array, keeping the rows [row] can rebuild and no others.
	 *
	 * Order is the stored order, which is the list's own order: newest first.
	 * A blob that is not an array at all yields nothing rather than throwing —
	 * the caller's next write replaces it. The per-owner cap is the caller's,
	 * applied to what this returns.
	 */
	private inline fun <T> decodeArray(raw: String?, row: (JSONObject) -> T?): List<T> {
		val text = raw?.takeIf { it.isNotBlank() } ?: return emptyList()
		val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
		val out = ArrayList<T>(array.length())
		for (i in 0 until array.length()) {
			val o = array.optJSONObject(i) ?: continue
			val decoded = runCatching { row(o) }.getOrNull() ?: continue
			out.add(decoded)
		}
		return out
	}
}

/** One legacy blob as stored, with the generation it was written at. */
internal data class LegacyBlob(val text: String?, val generation: Long)

/**
 * The legacy store's raw bytes, for the structured store's import and its
 * shadow writes (Issue #41). Nothing here decodes or decides anything.
 *
 * Each blob is stored with a **generation**, written in the same edit, so the
 * structured store can tell whether a blob is older or newer than what it
 * holds: it records the generation of every blob it writes, and a blob whose
 * generation is higher than that was written by a run in which the database
 * was unavailable. Blobs from builds before #41 have generation 0.
 */
internal interface LegacyRetainedBlobs {
	fun readHistory(): LegacyBlob
	fun readSkipped(): LegacyBlob
	fun writeHistory(blob: String, generation: Long)
	fun writeSkipped(blob: String, generation: Long)
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
 *
 * ## As the runtime's store
 *
 * [saveHistory] and [saveSkipped] are the runtime writing through this store
 * directly, which happens in two situations (Issue #41):
 *
 *  - **provisionally**, at startup, before the structured store has answered.
 *    These writes keep the blob's generation: they are not newer than the
 *    database, which is about to reconcile and take over;
 *  - **as the fallback**, for a run whose database could not open. These
 *    writes advance the generation, so the next run that does open the
 *    database knows this blob is newer than what it holds.
 *
 * [advancesGeneration] says which; it is true unless the runtime sets it.
 */
internal class SharedPreferencesRetainedRecords internal constructor(
	private val prefs: SharedPreferences,
) : RetainedRecordStore,
	LegacyRetainedBlobs {

	constructor(context: Context) :
		this(context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE))

	@Volatile
	var advancesGeneration: Boolean = true

	override fun loadHistory(): List<FinalizationRuntime.ScrobbleRecord> =
		RetainedRecordCodec.decodeHistory(read(KEY_HISTORY))

	override fun saveHistory(rows: List<FinalizationRuntime.ScrobbleRecord>) {
		save(KEY_HISTORY, GEN_HISTORY, RetainedRecordCodec.encodeHistory(rows))
	}

	override fun loadSkipped(): List<FinalizationRuntime.SkipRecord> =
		RetainedRecordCodec.decodeSkipped(read(KEY_SKIPPED))

	override fun saveSkipped(rows: List<FinalizationRuntime.SkipRecord>) {
		save(KEY_SKIPPED, GEN_SKIPPED, RetainedRecordCodec.encodeSkipped(rows))
	}

	override fun readHistory(): LegacyBlob = LegacyBlob(read(KEY_HISTORY), generation(GEN_HISTORY))

	override fun readSkipped(): LegacyBlob = LegacyBlob(read(KEY_SKIPPED), generation(GEN_SKIPPED))

	override fun writeHistory(blob: String, generation: Long) = write(KEY_HISTORY, GEN_HISTORY, blob, generation)

	override fun writeSkipped(blob: String, generation: Long) = write(KEY_SKIPPED, GEN_SKIPPED, blob, generation)

	@Synchronized
	private fun save(key: String, genKey: String, blob: String) {
		val current = generation(genKey)
		write(key, genKey, blob, if (advancesGeneration) current + 1 else current)
	}

	private fun read(key: String): String? =
		runCatching { prefs.getString(key, null) }.getOrNull()

	private fun generation(key: String): Long =
		runCatching { prefs.getLong(key, 0L) }.getOrDefault(0L)

	/** Blob and generation in one edit, so neither can land without the other. */
	private fun write(key: String, genKey: String, value: String, generation: Long) {
		runCatching { prefs.edit().putString(key, value).putLong(genKey, generation).apply() }
	}

	private companion object {
		const val FILE_NAME = "rustedwax_retained"
		const val KEY_HISTORY = "history_v1"
		const val KEY_SKIPPED = "not_logged_v1"
		const val GEN_HISTORY = "history_v1_generation"
		const val GEN_SKIPPED = "not_logged_v1_generation"
	}
}
