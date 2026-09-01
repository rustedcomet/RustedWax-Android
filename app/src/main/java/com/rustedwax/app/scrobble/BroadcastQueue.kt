package com.rustedwax.app.scrobble

import android.content.Context
import com.rustedwax.app.detect.EventLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable queue of scrobbles waiting to reach the chain.
 *
 * The extension could afford to fire and forget — a desktop browser is nearly
 * always online, and a dropped scrobble was one lost listen. A phone spends
 * real time on flaky mobile data or offline entirely, so a scrobble that fails
 * to broadcast is parked here and retried rather than lost.
 *
 * Stored as JSON in `filesDir` rather than a database: the queue is tiny, and
 * keeping the whole `scrobble/` package free of Room/KSP keeps it unit-testable
 * and the build fast.
 *
 * The payload is stored **already serialized**. Rebuilding it later would
 * re-derive `timestamp` from the wrong clock; the bytes signed must be the
 * bytes decided at finalize time.
 */
class BroadcastQueue(context: Context) {

	private val file = File(context.filesDir, "broadcast-queue.json")

	data class Entry(
		val id: Long,
		val username: String,
		/** The exact `custom_json` payload string to broadcast. */
		val json: String,
		/** Human-readable, for the UI list. */
		val label: String,
		val percentPlayed: Int?,
		val videoId: String?,
		val attempts: Int,
		val nextAttemptAtMs: Long,
		val lastError: String?,
	)

	@Synchronized
	fun all(): List<Entry> = read()

	@Synchronized
	fun size(): Int = read().size

	@Synchronized
	fun add(
		username: String,
		json: String,
		label: String,
		percentPlayed: Int?,
		videoId: String?,
	): Boolean {
		val entries = read().toMutableList()
		entries += Entry(
			id = System.currentTimeMillis() + entries.size,
			username = username,
			json = json,
			label = label,
			percentPlayed = percentPlayed,
			videoId = videoId,
			attempts = 0,
			nextAttemptAtMs = 0,
			lastError = null,
		)
		return write(entries)
	}

	/** Entries whose backoff has elapsed. */
	@Synchronized
	fun due(nowMs: Long = System.currentTimeMillis()): List<Entry> =
		read().filter { it.nextAttemptAtMs <= nowMs }

	@Synchronized
	fun remove(id: Long): Boolean = write(read().filterNot { it.id == id })

	enum class FailureOutcome { RETAINED, DROPPED, NOT_FOUND, STORAGE_ERROR }

	/**
	 * Record a failed attempt and back off: 1, 2, 4 … minutes, capped at an
	 * hour. Dropped entirely after [MAX_ATTEMPTS] so a permanently rejected
	 * scrobble can't retry forever.
	 */
	@Synchronized
	fun recordFailure(id: Long, error: String): FailureOutcome {
		val entries = read().toMutableList()
		val idx = entries.indexOfFirst { it.id == id }
		if (idx < 0) return FailureOutcome.NOT_FOUND
		val entry = entries[idx]
		val attempts = entry.attempts + 1
		if (attempts >= MAX_ATTEMPTS) {
			entries.removeAt(idx)
			return if (write(entries)) FailureOutcome.DROPPED else FailureOutcome.STORAGE_ERROR
		}
		val backoff = minOf(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl (attempts - 1))
		entries[idx] = entry.copy(
			attempts = attempts,
			nextAttemptAtMs = System.currentTimeMillis() + backoff,
			lastError = error,
		)
		return if (write(entries)) FailureOutcome.RETAINED else FailureOutcome.STORAGE_ERROR
	}

	@Synchronized
	fun clear() {
		runCatching { file.delete() }.onFailure {
			EventLog.append("queue", "STORAGE ERROR clearing retry queue: ${it.message}")
		}
	}

	// ── storage ────────────────────────────────────────────────────────

	private fun read(): List<Entry> {
		if (!file.exists()) return emptyList()
		return runCatching {
			val array = JSONArray(file.readText())
			(0 until array.length()).map { i ->
				val o = array.getJSONObject(i)
				Entry(
					id = o.getLong("id"),
					username = o.getString("username"),
					json = o.getString("json"),
					label = o.optString("label"),
					percentPlayed = o.optInt("percentPlayed").takeIf {
						o.has("percentPlayed")
					},
					videoId = o.optString("videoId").takeIf { it.isNotBlank() },
					attempts = o.optInt("attempts"),
					nextAttemptAtMs = o.optLong("nextAttemptAtMs"),
					lastError = o.optString("lastError").takeIf { it.isNotBlank() },
				)
			}
		}.getOrElse { error ->
			val backup = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
			val recovered = runCatching { file.renameTo(backup) }.getOrDefault(false)
			EventLog.append(
				"queue",
				"STORAGE ERROR reading retry queue: ${error.message}. " +
					if (recovered) {
						"Corrupt file preserved as ${backup.name}"
					} else {
						"Corrupt file could not be preserved"
					},
			)
			emptyList()
		}
	}

	private fun write(entries: List<Entry>): Boolean {
		val array = JSONArray()
		entries.forEach { e ->
			array.put(
				JSONObject()
					.put("id", e.id)
					.put("username", e.username)
					.put("json", e.json)
					.put("label", e.label)
					.putOpt("percentPlayed", e.percentPlayed)
					.putOpt("videoId", e.videoId)
					.put("attempts", e.attempts)
					.put("nextAttemptAtMs", e.nextAttemptAtMs)
					.put("lastError", e.lastError ?: ""),
			)
		}
		return runCatching {
			val temp = File(file.parentFile, "${file.name}.tmp")
			temp.writeText(array.toString())
			try {
				Files.move(
					temp.toPath(),
					file.toPath(),
					StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE,
				)
			} catch (_: AtomicMoveNotSupportedException) {
				Files.move(
					temp.toPath(),
					file.toPath(),
					StandardCopyOption.REPLACE_EXISTING,
				)
			}
			true
		}.getOrElse { error ->
			EventLog.append("queue", "STORAGE ERROR writing retry queue: ${error.message}")
			false
		}
	}

	private companion object {
		const val MAX_ATTEMPTS = 8
		const val BASE_BACKOFF_MS = 60_000L
		const val MAX_BACKOFF_MS = 60 * 60_000L
	}
}
