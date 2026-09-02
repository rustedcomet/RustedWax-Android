package com.rustedwax.app.scrobble

import android.content.Context
import com.rustedwax.app.detect.EventLog
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.sha256
import com.rustedwax.hive.toHex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Durable queue and write-ahead state for scrobbles that may cross the network.
 *
 * A queued operation is either ordinary [State.QUEUED], durably
 * [State.IN_FLIGHT] with the exact signed Hive transaction that may have been
 * broadcast, or [State.SETTLED]. No network broadcast is allowed until the
 * IN_FLIGHT transition has been atomically persisted. If anything after that
 * point is ambiguous, the exact transaction remains available for
 * reconciliation/rebroadcast and a fresh transaction cannot be constructed by
 * accident after restart.
 */
class BroadcastQueue internal constructor(private val file: File) {

	constructor(context: Context) : this(File(context.filesDir, "broadcast-queue.json"))

	enum class State {
		/** Payload is owed and no transaction has crossed the network boundary. */
		QUEUED,

		/** Exact signed transaction is durable and may already have been accepted. */
		IN_FLIGHT,

		/** Chain acceptance is established. This entry must never be sent. */
		SETTLED,
	}

	data class Entry(
		val id: Long,
		/** Stable random identity for this logical queued operation. */
		val operationId: String,
		val username: String,
		/** Already-serialized scrobble payload; never rebuilt from current time. */
		val json: String,
		val label: String,
		val percentPlayed: Int?,
		val videoId: String?,
		val attempts: Int,
		val nextAttemptAtMs: Long,
		val lastError: String?,
		val state: State = State.QUEUED,
		val preparedTransactionJson: String? = null,
		val preparedTransactionId: String? = null,
		val preparedExpirationEpochSec: Long? = null,
		/** Integrity value for every persisted field; absent only on legacy QUEUED rows. */
		val stateSha256: String? = null,
	) {
		/** Null means durable state is incomplete and must fail closed. */
		fun preparedTransaction(): PreparedHiveTransaction? {
			if (state != State.IN_FLIGHT) return null
			val signed = preparedTransactionJson?.takeIf { it.isNotBlank() } ?: return null
			val txId = preparedTransactionId?.takeIf { it.isNotBlank() } ?: return null
			val expiration = preparedExpirationEpochSec ?: return null
			val prepared = PreparedHiveTransaction(signed, txId, expiration)
			if (runCatching { JSONObject(signed) }.isFailure) return null
			return prepared
		}
	}

	@Synchronized
	fun all(): List<Entry> = read() ?: emptyList()

	/** QUEUED and IN_FLIGHT operations remain visible; SETTLED ones are not owed. */
	@Synchronized
	fun size(): Int = read()?.count { it.state != State.SETTLED } ?: 0

	/** Compatibility wrapper used by direct queue tests. */
	@Synchronized
	fun add(
		username: String,
		json: String,
		label: String,
		percentPlayed: Int?,
		videoId: String?,
	): Boolean = enqueue(username, json, label, percentPlayed, videoId) != null

	/** Persist an ordinary unsent operation and return its stable identity. */
	@Synchronized
	fun enqueue(
		username: String,
		json: String,
		label: String,
		percentPlayed: Int?,
		videoId: String?,
	): Entry? {
		val existing = read() ?: return null
		val entries = existing.filterNot { it.state == State.SETTLED }.toMutableList()
		val nextId = maxOf(
			System.currentTimeMillis(),
			(entries.maxOfOrNull { it.id } ?: Long.MIN_VALUE).let {
				if (it == Long.MAX_VALUE) Long.MAX_VALUE else it + 1
			},
		)
		val entry = Entry(
			id = nextId,
			operationId = UUID.randomUUID().toString(),
			username = username,
			json = json,
			label = label,
			percentPlayed = percentPlayed,
			videoId = videoId,
			attempts = 0,
			nextAttemptAtMs = 0,
			lastError = null,
		)
		entries += entry
		return entry.takeIf { write(entries) }
	}

	/**
	 * Operations whose normal backoff elapsed.
	 *
	 * IN_FLIGHT means "reconcile first", never "build a new transaction". The
	 * engine performs that distinction while holding its existing broadcast lock.
	 */
	@Synchronized
	fun due(nowMs: Long = System.currentTimeMillis()): List<Entry> =
		read()?.filter {
			it.state != State.SETTLED && it.nextAttemptAtMs <= nowMs
		} ?: emptyList()

	enum class PrepareOutcome { STORED, NOT_FOUND, STORAGE_ERROR }

	/**
	 * Atomic write-ahead transition required before network broadcast.
	 *
	 * Legacy queued rows receive their stable random operation id in this same
	 * write. A failed write leaves the row QUEUED and the caller must not send.
	 */
	@Synchronized
	fun markInFlight(entry: Entry, prepared: PreparedHiveTransaction): PrepareOutcome {
		val entries = read()?.toMutableList() ?: return PrepareOutcome.STORAGE_ERROR
		val index = entries.indexOfOperation(entry)
		if (index < 0) return PrepareOutcome.NOT_FOUND
		val stored = entries[index]
		if (stored.state == State.SETTLED) return PrepareOutcome.NOT_FOUND
		val operationId = stored.operationId.ifBlank { UUID.randomUUID().toString() }
		entries[index] = stored.copy(
			operationId = operationId,
			state = State.IN_FLIGHT,
			preparedTransactionJson = prepared.signedTransactionJson,
			preparedTransactionId = prepared.txId,
			preparedExpirationEpochSec = prepared.expirationEpochSec,
		)
		return if (write(entries)) PrepareOutcome.STORED else PrepareOutcome.STORAGE_ERROR
	}

	/** Compatibility overload for direct queue callers predating operation IDs. */
	@Synchronized
	fun markInFlight(id: Long, prepared: PreparedHiveTransaction): PrepareOutcome {
		val entry = read()?.firstOrNull { it.id == id } ?: return PrepareOutcome.NOT_FOUND
		return markInFlight(entry, prepared)
	}

	enum class SettleOutcome {
		/** SETTLED was persisted and the row was removed. */
		RETIRED,

		/** SETTLED is durable, but the cleanup rewrite failed. */
		SETTLED_DURABLY,

		/** Cleanup writes failed; the prior durable IN_FLIGHT row remains fail-closed. */
		IN_FLIGHT_RETAINED,
	}

	/**
	 * Durably settle before trying to remove.
	 *
	 * If the SETTLED write itself fails after a network result, the already
	 * durable IN_FLIGHT transaction remains. A restart must reconcile it and can
	 * never mistake it for an ordinary payload that is safe to re-sign.
	 */
	@Synchronized
	fun settle(entry: Entry): SettleOutcome {
		val entries = read()?.toMutableList() ?: return SettleOutcome.IN_FLIGHT_RETAINED
		val index = entries.indexOfOperation(entry)
		if (index < 0) return SettleOutcome.RETIRED

		if (entries[index].state != State.SETTLED) {
			entries[index] = entries[index].copy(
				operationId = entries[index].operationId.ifBlank { UUID.randomUUID().toString() },
				state = State.SETTLED,
			)
			if (!write(entries)) return SettleOutcome.IN_FLIGHT_RETAINED
		}

		return if (write(entries.filterNot { it.isSameOperation(entry) })) {
			SettleOutcome.RETIRED
		} else {
			SettleOutcome.SETTLED_DURABLY
		}
	}

	enum class FailureOutcome {
		RETAINED,
		DROPPED,
		/** Attempt ceiling reached, but an ambiguous IN_FLIGHT transaction must be reconciled first. */
		AWAITING_RECONCILIATION,
		NOT_FOUND,
		STORAGE_ERROR,
	}

	/** Existing 1, 2, 4 … 60 minute backoff and eight-attempt ceiling. */
	@Synchronized
	fun recordFailure(entry: Entry, error: String): FailureOutcome {
		val entries = read()?.toMutableList() ?: return FailureOutcome.STORAGE_ERROR
		val index = entries.indexOfOperation(entry)
		if (index < 0) return FailureOutcome.NOT_FOUND
		val stored = entries[index]
		if (stored.state == State.SETTLED) return FailureOutcome.NOT_FOUND
		val attempts = stored.attempts + 1
		if (attempts >= MAX_ATTEMPTS) {
			if (stored.state == State.IN_FLIGHT) {
				entries[index] = stored.copy(
					attempts = MAX_ATTEMPTS,
					nextAttemptAtMs = System.currentTimeMillis() + MAX_BACKOFF_MS,
					lastError = error,
				)
				return if (write(entries)) {
					FailureOutcome.AWAITING_RECONCILIATION
				} else {
					FailureOutcome.STORAGE_ERROR
				}
			}
			entries.removeAt(index)
			return if (write(entries)) FailureOutcome.DROPPED else FailureOutcome.STORAGE_ERROR
		}
		val backoff = minOf(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl (attempts - 1))
		entries[index] = stored.copy(
			attempts = attempts,
			nextAttemptAtMs = System.currentTimeMillis() + backoff,
			lastError = error,
		)
		return if (write(entries)) FailureOutcome.RETAINED else FailureOutcome.STORAGE_ERROR
	}

	/** Compatibility overload for direct queue callers predating operation IDs. */
	@Synchronized
	fun recordFailure(id: Long, error: String): FailureOutcome {
		val entry = read()?.firstOrNull { it.id == id } ?: return FailureOutcome.NOT_FOUND
		return recordFailure(entry, error)
	}

	@Synchronized
	fun clear() {
		runCatching { file.delete() }.onFailure {
			EventLog.append("queue", "STORAGE ERROR clearing retry queue: ${it.message}")
		}
	}

	/**
	 * Null means the durable file is unreadable and every operation must fail
	 * closed. It is deliberately left in place: renaming it and returning empty
	 * would make the next read look like a safe empty queue.
	 */
	private fun read(): List<Entry>? {
		if (!file.exists()) return emptyList()
		return runCatching {
			val array = JSONArray(file.readText())
			(0 until array.length()).map { i ->
				val objectValue = array.getJSONObject(i)
				val stateText = objectValue.optString("state")
				val state = if (stateText.isBlank()) State.QUEUED else State.valueOf(stateText)
				val entry = Entry(
					id = objectValue.getLong("id"),
					operationId = objectValue.optString("operationId"),
					username = objectValue.getString("username"),
					json = objectValue.getString("json"),
					label = objectValue.optString("label"),
					percentPlayed = objectValue.optInt("percentPlayed").takeIf {
						objectValue.has("percentPlayed")
					},
					videoId = objectValue.optString("videoId").takeIf { it.isNotBlank() },
					attempts = objectValue.optInt("attempts"),
					nextAttemptAtMs = objectValue.optLong("nextAttemptAtMs"),
					lastError = objectValue.optString("lastError").takeIf { it.isNotBlank() },
					state = state,
					preparedTransactionJson = objectValue.optString("preparedTransactionJson")
						.takeIf { it.isNotBlank() },
					preparedTransactionId = objectValue.optString("preparedTransactionId")
						.takeIf { it.isNotBlank() },
					preparedExpirationEpochSec = objectValue.optLong("preparedExpirationEpochSec")
						.takeIf { objectValue.has("preparedExpirationEpochSec") },
					stateSha256 = objectValue.optString("stateSha256")
						.takeIf { it.isNotBlank() },
				)
				val legacyQueued = stateText.isBlank() && entry.operationId.isBlank() &&
					entry.stateSha256 == null
				if (!legacyQueued) {
					require(entry.stateSha256 == stateSha256(entry)) { "send-state checksum mismatch" }
				}
				when (state) {
					State.QUEUED -> require(
						entry.preparedTransactionJson == null &&
							entry.preparedTransactionId == null &&
							entry.preparedExpirationEpochSec == null,
					) { "QUEUED operation contains prepared transaction state" }
					State.IN_FLIGHT -> {
						require(entry.operationId.isNotBlank()) { "IN_FLIGHT operation id missing" }
						require(entry.preparedTransaction() != null) { "IN_FLIGHT transaction incomplete" }
					}
					State.SETTLED -> require(entry.operationId.isNotBlank()) {
						"SETTLED operation id missing"
					}
				}
				entry
			}
		}.getOrElse { error ->
			EventLog.append(
				"queue",
				"STORAGE ERROR reading retry send-state: ${error.message}. " +
					"Automatic queue broadcast is blocked until the state is repaired or cleared.",
			)
			null
		}
	}

	private fun moveInto(temp: File, target: File) {
		Files.move(
			temp.toPath(),
			target.toPath(),
			StandardCopyOption.REPLACE_EXISTING,
			StandardCopyOption.ATOMIC_MOVE,
		)
	}

	/** Stable random identity is authoritative; numeric id is legacy compatibility. */
	private fun List<Entry>.indexOfOperation(reference: Entry): Int =
		indexOfFirst { it.isSameOperation(reference) }

	private fun Entry.isSameOperation(reference: Entry): Boolean =
		if (reference.operationId.isNotBlank()) {
			operationId == reference.operationId
		} else {
			id == reference.id
		}

	/** Canonical full-row integrity value; any valid-JSON state mutation fails closed. */
	private fun stateSha256(entry: Entry): String = sha256(
		JSONArray()
			.put(entry.id)
			.put(entry.operationId)
			.put(entry.username)
			.put(entry.json)
			.put(entry.label)
			.put(entry.percentPlayed ?: JSONObject.NULL)
			.put(entry.videoId ?: JSONObject.NULL)
			.put(entry.attempts)
			.put(entry.nextAttemptAtMs)
			.put(entry.lastError ?: JSONObject.NULL)
			.put(entry.state.name)
			.put(entry.preparedTransactionJson ?: JSONObject.NULL)
			.put(entry.preparedTransactionId ?: JSONObject.NULL)
			.put(entry.preparedExpirationEpochSec ?: JSONObject.NULL)
			.toString()
			.toByteArray(Charsets.UTF_8),
	).toHex()

	private fun write(entries: List<Entry>): Boolean {
		val array = JSONArray()
		entries.forEach { entry ->
			val checksum = stateSha256(entry)
			array.put(
				JSONObject()
					.put("id", entry.id)
					.put("operationId", entry.operationId)
					.put("username", entry.username)
					.put("json", entry.json)
					.put("label", entry.label)
					.putOpt("percentPlayed", entry.percentPlayed)
					.putOpt("videoId", entry.videoId)
					.put("attempts", entry.attempts)
					.put("nextAttemptAtMs", entry.nextAttemptAtMs)
					.put("lastError", entry.lastError ?: "")
					.put("state", entry.state.name)
					.putOpt("preparedTransactionJson", entry.preparedTransactionJson)
					.putOpt("preparedTransactionId", entry.preparedTransactionId)
					.putOpt("preparedExpirationEpochSec", entry.preparedExpirationEpochSec)
					.put("stateSha256", checksum),
			)
		}
		return runCatching {
			val temp = File(file.parentFile, "${file.name}.tmp")
			FileOutputStream(temp).use { output ->
				output.write(array.toString().toByteArray(Charsets.UTF_8))
				output.fd.sync()
			}
			moveInto(temp, file)
			true
		}.getOrElse { error ->
			EventLog.append("queue", "STORAGE ERROR writing retry send-state: ${error.message}")
			false
		}
	}

	private companion object {
		const val MAX_ATTEMPTS = 8
		const val BASE_BACKOFF_MS = 60_000L
		const val MAX_BACKOFF_MS = 60 * 60_000L
	}
}
