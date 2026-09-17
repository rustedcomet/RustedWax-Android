package com.rustedwax.app.snaps

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Where a root Snap has got to.
 *
 * The states that matter are the uncertain ones. [ACCEPTED_UNCONFIRMED] and
 * [UNRESOLVED] both mean "a transaction crossed the network boundary and we do
 * not know whether Hive kept it", and neither may be turned back into a new
 * transaction without evidence — a rebuilt Snap under a fresh permlink is a
 * permanent duplicate public comment, and there is no undo for that.
 */
enum class PendingSnapState {
	/** Signed and persisted; nothing has been sent yet. */
	PREPARED,

	/** Handed to the network. Anything may have happened. */
	BROADCASTING,

	/** A node took it; no independent node could confirm inclusion. */
	ACCEPTED_UNCONFIRMED,

	/** Reconciliation ran and could not decide. Still not safe to resend. */
	UNRESOLVED,

	/** Proven on chain. */
	CONFIRMED,

	/** Proven *not* on chain, or refused before it was ever sent. */
	FAILED,

	/**
	 * A stored record exists for this row but cannot be read.
	 *
	 * Deliberately *not* the same as having no record. An unreadable record may
	 * describe a Snap that is already live, so this state locks the row: no new
	 * permlink, no new transaction, nothing sent.
	 */
	CORRUPT,
}

/**
 * What the store found for one History row.
 *
 * The three cases must stay distinct. Collapsing [Corrupt] into [Absent] — which
 * is what returning a nullable record does — lets a row whose state could not be
 * parsed look brand new, mint a second permlink and post a duplicate of a Snap
 * that may already exist.
 */
sealed interface PendingSnapRead {
	data object Absent : PendingSnapRead
	data class Present(val snap: PendingSnap) : PendingSnapRead
	data class Corrupt(val reason: String) : PendingSnapRead
}

/**
 * Everything needed to finish, or safely abandon, one root Snap — including
 * across process death.
 *
 * The private key is deliberately absent. What is stored is the *already signed*
 * transaction, which is exactly enough to rebroadcast the identical bytes (same
 * transaction id, so the chain de-duplicates it) and never enough to sign
 * anything new.
 */
data class PendingSnap(
	val account: String,
	val eventId: String,
	val author: String,
	val permlink: String,
	val parentAuthor: String,
	val parentPermlink: String,
	val body: String,
	val jsonMetadata: String,
	val signedTransactionJson: String,
	val txId: String,
	val expirationEpochSec: Long,
	val state: PendingSnapState,
	val createdAtEpochSec: Long,
	val updatedAtEpochSec: Long,
	/** Last thing that went wrong, for the card to show. Never key material. */
	val lastError: String? = null,
	val kind: String = KIND_ROOT_SNAP,
) {
	/** `@author/permlink` — the durable Hive identity of this Snap. */
	val contentId: String get() = "$author/$permlink"

	/** True while the outcome is genuinely unknown and no resend is permitted. */
	val isUncertain: Boolean
		get() = state == PendingSnapState.BROADCASTING ||
			state == PendingSnapState.ACCEPTED_UNCONFIRMED ||
			state == PendingSnapState.UNRESOLVED

	fun toJson(): String = JSONObject()
		.put("account", account)
		.put("eventId", eventId)
		.put("author", author)
		.put("permlink", permlink)
		.put("parentAuthor", parentAuthor)
		.put("parentPermlink", parentPermlink)
		.put("body", body)
		.put("jsonMetadata", jsonMetadata)
		.put("signedTransactionJson", signedTransactionJson)
		.put("txId", txId)
		.put("expirationEpochSec", expirationEpochSec)
		.put("state", state.name)
		.put("createdAtEpochSec", createdAtEpochSec)
		.put("updatedAtEpochSec", updatedAtEpochSec)
		.put("lastError", lastError ?: JSONObject.NULL)
		.put("kind", kind)
		.toString()

	companion object {
		const val KIND_ROOT_SNAP = "root_snap"

		/**
		 * Parse a stored record, or null if it is unreadable.
		 *
		 * Fails to null rather than throwing: a record this code cannot read is
		 * already a record it cannot safely act on, and a crash loop on app start
		 * would be a worse outcome than one Snap needing to be reposted by hand.
		 */
		fun fromJson(raw: String): PendingSnap? = runCatching {
			val o = JSONObject(raw)
			PendingSnap(
				account = o.getString("account"),
				eventId = o.getString("eventId"),
				author = o.getString("author"),
				permlink = o.getString("permlink"),
				parentAuthor = o.getString("parentAuthor"),
				parentPermlink = o.getString("parentPermlink"),
				body = o.getString("body"),
				jsonMetadata = o.getString("jsonMetadata"),
				signedTransactionJson = o.getString("signedTransactionJson"),
				txId = o.getString("txId"),
				expirationEpochSec = o.getLong("expirationEpochSec"),
				state = PendingSnapState.valueOf(o.getString("state")),
				createdAtEpochSec = o.getLong("createdAtEpochSec"),
				updatedAtEpochSec = o.getLong("updatedAtEpochSec"),
				lastError = o.optString("lastError").takeIf {
					it.isNotBlank() && !o.isNull("lastError")
				},
				kind = o.optString("kind", KIND_ROOT_SNAP),
			)
		}.getOrNull()
	}
}

/**
 * The permlink a Snap will live at forever.
 *
 * Minted **once** per History row and then read back for the rest of that row's
 * life. This is the single most important rule in the Snap write path: if a
 * timeout, a crash or a retry ever produced a second permlink for the same
 * intent, the recovery logic would be comparing the chain against an identity
 * the chain never saw, conclude the Snap was absent, and post it again.
 *
 * Shaped to satisfy Hive's permlink rules and RustedWax's own stricter
 * generated-permlink check: lowercase, digits and hyphens only.
 */
object SnapPermlink {

	private const val PREFIX = "rustedwax-snap"
	private const val SUFFIX_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
	private const val SUFFIX_LENGTH = 6

	fun generate(
		nowEpochSec: Long,
		random: java.util.Random = SecureRandom(),
	): String {
		val suffix = (1..SUFFIX_LENGTH)
			.map { SUFFIX_CHARS[random.nextInt(SUFFIX_CHARS.length)] }
			.joinToString("")
		return "$PREFIX-$nowEpochSec-$suffix"
	}
}

/**
 * Decides which stored rows are trustworthy and which must be locked.
 *
 * Pure, and separate from the preference file, so the one rule that matters
 * here can be tested without Android: **a record's own identity must agree with
 * the key it is filed under.** A row stored under event A whose decoded record
 * claims event B is not a row for A and not a row for B — it is evidence that
 * something went wrong, and either identity may be the one that already has a
 * Snap on chain.
 *
 * So both identities are locked. Treating the mismatch as mere absence for
 * whichever event was not asked about is the dangerous reading: that event
 * would look brand new, mint a fresh permlink and post a duplicate of a comment
 * that may already exist under the other one.
 */
internal object PendingSnapIntegrity {

	/**
	 * Every event id that must refuse to prepare, mint or broadcast, given the
	 * raw `eventId to json` entries stored for one account.
	 */
	fun lockedEventIds(account: String, entries: Map<String, String>): Set<String> {
		val locked = mutableSetOf<String>()
		entries.forEach { (keyEventId, raw) ->
			val parsed = PendingSnap.fromJson(raw)
			when {
				// Unreadable: it may describe a live Snap for this key.
				parsed == null -> locked += keyEventId
				// Identity disagrees with the key it was filed under. Both the key's
				// event and the one the record claims are implicated.
				parsed.account != account || parsed.eventId != keyEventId -> {
					locked += keyEventId
					locked += parsed.eventId
				}
			}
		}
		return locked
	}

	/** Records whose identity matches their key exactly. Everything else is locked. */
	fun valid(account: String, entries: Map<String, String>): List<PendingSnap> =
		entries.mapNotNull { (keyEventId, raw) ->
			PendingSnap.fromJson(raw)?.takeIf {
				it.account == account && it.eventId == keyEventId
			}
		}
}

/**
 * Persisted pending Snap writes, scoped by Hive account.
 *
 * Its own preference file, exactly like drafts, and deliberately **not** part of
 * `BroadcastQueue`: that queue exists to rebuild and retry scrobbles
 * automatically, which is the one thing a Snap must never be subjected to.
 */
interface PendingSnapStore {
	fun read(account: String, eventId: String): PendingSnapRead

	/**
	 * Store a record, returning true only when it is **durably on disk and reads
	 * back identical**.
	 *
	 * The return value is load-bearing: the caller must not cross the network
	 * boundary on false. A Snap broadcast without its record persisted is a Snap
	 * whose outcome can never be reconciled, which is the exact situation that
	 * produces a duplicate permanent comment.
	 */
	fun write(snap: PendingSnap): Boolean

	fun clear(account: String, eventId: String)

	/** Every readable record for one account, plus the event ids that are corrupt. */
	fun all(account: String): List<PendingSnap>

	/** Rows whose stored state could not be parsed. These are locked, not retried. */
	fun corruptEventIds(account: String): Set<String>
}

internal class SharedPreferencesPendingSnapStore(
	context: Context,
) : PendingSnapStore {

	private val prefs: SharedPreferences =
		context.getSharedPreferences("rustedwax_pending_snaps", Context.MODE_PRIVATE)

	override fun read(account: String, eventId: String): PendingSnapRead {
		// Checked first, and across the whole account rather than just this key:
		// the record implicating this event may be filed under a different one.
		if (eventId in PendingSnapIntegrity.lockedEventIds(account, entries(account))) {
			return PendingSnapRead.Corrupt("stored Snap state is inconsistent")
		}
		val raw = prefs.getString(key(account, eventId), null)
			?: return PendingSnapRead.Absent
		val parsed = PendingSnap.fromJson(raw)
			?: return PendingSnapRead.Corrupt("stored Snap state could not be read")
		return PendingSnapRead.Present(parsed)
	}

	override fun write(snap: PendingSnap): Boolean {
		val serialized = snap.toJson()
		// commit(), not apply(): this record is the only thing standing between a
		// lost response and a duplicate public comment, so it has to be on disk
		// before the network call starts rather than whenever the async writer
		// gets to it — and its result has to be checked, not assumed.
		val committed = runCatching {
			prefs.edit().putString(key(snap.account, snap.eventId), serialized).commit()
		}.getOrDefault(false)
		if (!committed) return false

		// Read back. A commit that returned true but stored something else — a
		// full disk, a corrupted preference file — must not be mistaken for
		// durable state.
		return prefs.getString(key(snap.account, snap.eventId), null) == serialized
	}

	/**
	 * Remove one record.
	 *
	 * Only ever called for a row the caller has resolved. Corrupt evidence is
	 * deliberately never cleaned up on its own: the inconsistent record is the
	 * only reason the affected events stay locked, and deleting it would silently
	 * convert the lock back into absence.
	 */
	override fun clear(account: String, eventId: String) {
		prefs.edit().remove(key(account, eventId)).commit()
	}

	override fun all(account: String): List<PendingSnap> =
		PendingSnapIntegrity.valid(account, entries(account))

	override fun corruptEventIds(account: String): Set<String> =
		PendingSnapIntegrity.lockedEventIds(account, entries(account))

	/** Stored `eventId to json` pairs for one account. */
	private fun entries(account: String): Map<String, String> {
		val prefix = "$account|"
		return prefs.all.entries
			.filter { it.key.startsWith(prefix) }
			.mapNotNull { entry ->
				(entry.value as? String)?.let { entry.key.removePrefix(prefix) to it }
			}
			.toMap()
	}

	private fun key(account: String, eventId: String) = "$account|$eventId"
}
