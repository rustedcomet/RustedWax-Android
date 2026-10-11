package com.rustedwax.app.snaps

import android.content.Context
import com.rustedwax.hive.HiveAccountName
import com.rustedwax.hive.PreparedHiveTransaction
import org.json.JSONObject

/**
 * Which Edit or Delete of one comment is in progress or unsettled, shared by
 * every [SnapEditor] and [SnapDeleter] in the process (Issue #56, 3A).
 *
 * The editor and deleter are rebuilt whenever the Activity is, so a guard kept
 * inside them was forgotten exactly when it mattered: an Edit whose answer was
 * lost could be followed, in the new Activity, by a Delete — or the reverse —
 * while the first transaction could still land. One slot per account and
 * exact `author/permlink` closes that: it is taken before anything is signed,
 * holds the original prepared attempt while it is unsettled, and is released
 * only by the attempt that holds it.
 *
 * Data only. Nothing here refers to an Activity, a screen, a coroutine or a
 * callback, so keeping it for the life of the process keeps nothing else
 * alive. With a [SnapAttemptStore] (3C) an attempt is also saved before it can
 * be sent and restored by the next process; the private key never is.
 */
class SnapWriteGuards(
	/** Where attempts outlive the process (Issue #56, 3C); null keeps them in memory only. */
	private val durable: SnapAttemptStore? = null,
) {

	/** What holds one comment's slot. [id] is unique within the process. */
	sealed interface Slot {
		val id: Long
	}

	/** Taken before signing; nothing has been sent yet. */
	data class Reserved(override val id: Long, val operation: Operation) : Slot

	/** An edit whose transaction may have crossed the network boundary. */
	data class EditAttempt(
		override val id: Long,
		val kind: SnapEditKind,
		val text: String,
		val body: String,
		val prepared: PreparedHiveTransaction,
	) : Slot

	/** A delete whose transaction may have crossed the network boundary. */
	data class DeleteAttempt(override val id: Long, val prepared: PreparedHiveTransaction) : Slot

	/**
	 * Saved state for this comment could not be read back as an attempt. It may
	 * describe a transaction that can still land, so nothing may be signed for
	 * this comment, and nothing here ever releases it.
	 */
	data class Locked(override val id: Long) : Slot

	enum class Operation { EDIT, DELETE }

	private val slots = HashMap<String, Slot>()
	private var next = 0L

	/** Nothing durable could be read at all: every comment is [Locked]. */
	private var unreadable = false

	init {
		if (durable != null) restore(durable)
	}

	/** What holds this comment's slot for [account], or null when it is free. */
	@Synchronized
	fun current(account: String, target: SnapReplyTarget): Slot? =
		if (unreadable) Locked(0) else slots[key(account, target)]

	/** Take the free slot for [operation], atomically; null when anything holds it. */
	@Synchronized
	fun reserve(account: String, target: SnapReplyTarget, operation: Operation): Reserved? {
		val key = key(account, target)
		if (unreadable || key in slots) return null
		return Reserved(++next, operation).also { slots[key] = it }
	}

	/**
	 * Replace this caller's own reservation with its prepared attempt, keeping
	 * the reservation's id. False when the slot is no longer this caller's — or
	 * when the attempt could not be made durable, in which case it must not be
	 * sent: a transaction that left the device with no saved record would be
	 * forgotten by the next process.
	 */
	@Synchronized
	fun attach(account: String, target: SnapReplyTarget, attempt: Slot): Boolean {
		val key = key(account, target)
		if (slots[key]?.id != attempt.id) return false
		val store = durable
		if (store != null) {
			val record = encode(account, target, attempt) ?: return false
			if (!runCatching { store.save(key, record) }.getOrDefault(false)) return false
		}
		slots[key] = attempt
		return true
	}

	/** Free the slot only if [id] still holds it; true when this call freed it. */
	@Synchronized
	fun release(account: String, target: SnapReplyTarget, id: Long): Boolean = finish(account, target, id) {}

	/**
	 * Settle the attempt [id] holds: run its [localSteps] — a confirmed body
	 * repaired, a tombstone written and a record retired — and only then free
	 * the slot, on disk first. False, with nothing run, when [id] no longer
	 * holds it, so a late completion cannot touch a newer attempt.
	 *
	 * The order is the crash safety. A process that dies after the steps and
	 * before the removal restores this same attempt and settles it again by
	 * reading; the steps are repeatable. A removal that fails or cannot be
	 * confirmed keeps the slot and returns false, so the same attempt is settled
	 * again here or by the next process. Nothing here ever signs or sends.
	 */
	@Synchronized
	fun finish(account: String, target: SnapReplyTarget, id: Long, localSteps: () -> Unit): Boolean {
		val key = key(account, target)
		val slot = slots[key]
		if (slot?.id != id) return false
		localSteps()
		val store = durable
		// Only an attempt was ever saved. Its row must be gone before the slot is:
		// a row left on disk is an attempt the next process restores, so this one
		// stays locked too until the removal is confirmed, and settling it again
		// only reads. (An Error is the process dying, and passes straight through.)
		if (store != null && (slot is EditAttempt || slot is DeleteAttempt)) {
			val removed = try {
				store.remove(key)
			} catch (e: Exception) {
				false
			}
			if (!removed) return false
		}
		slots.remove(key)
		return true
	}

	/**
	 * Rebuild the slots a previous process left, before anyone can sign.
	 *
	 * Fail closed throughout. A row that cannot be read back as exactly the
	 * attempt its key names locks that comment — and the comment it claims to
	 * be, if different. A key that names no comment, or a store that cannot be
	 * read, locks every comment. Locked rows are never removed by this class.
	 */
	private fun restore(store: SnapAttemptStore) {
		val rows = runCatching { store.load() }.getOrNull()
		if (rows == null) {
			unreadable = true
			return
		}
		var highest = 0L
		val locks = mutableSetOf<String>()
		for ((key, value) in rows) {
			if (identityOf(key) == null) {
				unreadable = true
				return
			}
			val decoded = decode(value)
			if (decoded == null) {
				locks += key
				continue
			}
			val (claimedKey, slot) = decoded
			highest = maxOf(highest, slot.id)
			if (claimedKey != key) {
				locks += key
				locks += claimedKey
				continue
			}
			slots[key] = slot
		}
		for (key in locks) slots[key] = Locked(++highest)
		next = highest
	}

	private fun key(account: String, target: SnapReplyTarget) = "${account.lowercase()}|${target.contentId}"

	companion object {
		@Volatile
		private var instance: SnapWriteGuards? = null

		/**
		 * The one the app's editors and deleters share, for the life of the
		 * process — restored from disk before it is handed to anyone.
		 */
		fun process(context: Context): SnapWriteGuards = instance ?: synchronized(this) {
			instance ?: SnapWriteGuards(SharedPreferencesSnapAttemptStore(context.applicationContext))
				.also { instance = it }
		}

		private const val VERSION = 1

		/** `account|author/permlink` to its account and target, or null. */
		private fun identityOf(key: String): Pair<String, SnapReplyTarget>? {
			val account = key.substringBefore('|', "")
			val content = key.substringAfter('|', "")
			if (account.isEmpty() || account != account.lowercase()) return null
			val target = SnapReplyTarget.of(content.substringBefore('/', ""), content.substringAfter('/', "")) ?: return null
			if (!HiveAccountName.isValid(account)) return null
			return account to target
		}

		/** The durable record of an attempt; null for a slot that is never stored. */
		internal fun encode(account: String, target: SnapReplyTarget, slot: Slot): String? {
			val json = JSONObject()
				.put("v", VERSION)
				.put("account", account.lowercase())
				.put("author", target.author)
				.put("permlink", target.permlink)
				.put("id", slot.id)
			val prepared = when (slot) {
				is EditAttempt -> {
					json.put("op", "edit").put("kind", slot.kind.name).put("text", slot.text).put("body", slot.body)
					slot.prepared
				}
				is DeleteAttempt -> {
					json.put("op", "delete")
					slot.prepared
				}
				is Reserved, is Locked -> return null
			}
			return json
				.put("signed", prepared.signedTransactionJson)
				.put("txId", prepared.txId)
				.put("expiration", prepared.expirationEpochSec)
				.toString()
		}

		/** The key a record claims and the attempt it holds, or null when it is not exactly one. */
		internal fun decode(raw: String): Pair<String, Slot>? = runCatching {
			val json = JSONObject(raw)
			if (json.getInt("v") != VERSION) return null
			val account = json.getString("account")
			val target = SnapReplyTarget.of(json.getString("author"), json.getString("permlink")) ?: return null
			val key = "$account|${target.contentId}"
			if (identityOf(key) == null) return null
			val id = json.getLong("id").takeIf { it > 0 } ?: return null
			val txId = json.getString("txId").takeIf { it.isNotBlank() } ?: return null
			val signed = json.getString("signed").takeIf { it.isNotBlank() } ?: return null
			val expiration = json.getLong("expiration").takeIf { it > 0 } ?: return null
			val prepared = PreparedHiveTransaction(signed, txId, expiration)
			val slot: Slot = when (json.getString("op")) {
				"edit" -> EditAttempt(
					id,
					SnapEditKind.entries.firstOrNull { it.name == json.getString("kind") } ?: return null,
					json.getString("text"),
					json.getString("body"),
					prepared,
				)
				"delete" -> DeleteAttempt(id, prepared)
				else -> return null
			}
			key to slot
		}.getOrNull()
	}
}
