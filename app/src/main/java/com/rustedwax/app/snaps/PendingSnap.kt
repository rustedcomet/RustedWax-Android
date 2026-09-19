package com.rustedwax.app.snaps

import android.content.Context
import android.content.SharedPreferences
import com.rustedwax.hive.HiveAccountName
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
	/**
	 * The user tapped Post and the **identity of the Snap is frozen** — but
	 * nothing has been built, signed or sent.
	 *
	 * This state exists so the card can appear without the network on the
	 * visible path. Everything it carries is computed locally: the permlink is
	 * minted from the clock and a random suffix, and the body and metadata come
	 * from [SnapPayloadBuilder], which needs no chain. What it does *not* carry
	 * is anything that requires a round trip — the parent container and the
	 * signed transaction are both empty here, and filling them is the
	 * background's job.
	 *
	 * It is emphatically **not permission to broadcast**. A record in this state
	 * has no transaction to send, and the only path out of it is through
	 * [PREPARED]: the identity frozen here is reused, a transaction is built
	 * around it, and *that* is persisted before anything reaches a node.
	 *
	 * Its real purpose is duplicate prevention across a crash. A permlink minted
	 * and committed before any slow work begins is a permlink that cannot be
	 * minted a second time, however many times the attempt is resumed.
	 */
	INTENT,

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
 * Which of the two write paths a durable record belongs to.
 *
 * Not decoration. A record decides whether a comment may be signed, delivered,
 * retried or reconciled, and the two paths build *different* comments: a root
 * Snap is parented on the `peak.snaps` container, a reply on the comment the
 * user tapped. A record consumed by the wrong path would therefore sign the
 * wrong parent under an identity minted for something else — so the kind is
 * part of the record's identity and is checked wherever the record can
 * authorize a write.
 *
 * [stored] is the durable spelling and must never change: it is what is already
 * written in every record on every device. The enum exists so that the set of
 * legal values is closed — an unrecognised string is not a third kind, it is a
 * record this code cannot read.
 */
enum class PendingSnapKind(val stored: String) {
	/** A Snap about something in History, parented on the Snap container. */
	ROOT("root_snap"),

	/**
	 * A reply comment, parented on another comment.
	 *
	 * Stored in a file of its own rather than beside root Snaps — see
	 * [SharedPreferencesPendingSnapStore] — so this is a second, independent
	 * check rather than the only thing keeping the two apart.
	 */
	REPLY("reply");

	companion object {
		/**
		 * The kind this durable string names, or **null**.
		 *
		 * Null for a missing value, an empty one and anything unrecognised.
		 * There is deliberately no fallback: defaulting an unreadable kind to
		 * [ROOT] is exactly how a reply record would come to be signed as a
		 * Snap, and a record whose kind this code cannot read is a record it
		 * cannot safely act on.
		 */
		fun of(stored: String?): PendingSnapKind? =
			entries.firstOrNull { it.stored == stored }
	}
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
	/**
	 * Which write path this record belongs to.
	 *
	 * Deliberately has **no default**. A record that did not say which kind it
	 * is would be one more place where "unspecified" silently becomes "root",
	 * and every caller here knows perfectly well what it is building.
	 */
	val kind: PendingSnapKind,
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
		.put("kind", kind.stored)
		.toString()

	companion object {

		/**
		 * Whether this expiration is one this state is allowed to carry.
		 *
		 * [PendingSnapState.INTENT] is the only state with no transaction, so
		 * it is the only state whose expiration is meaningfully **zero** — and
		 * it must be exactly zero, because an intent carrying a real-looking
		 * expiry would be a record claiming bytes it does not have.
		 *
		 * Every other state describes a signed transaction, and its expiration
		 * decides two different actionable things: whether those bytes may
		 * still be broadcast, and whether they must be rebuilt instead. A zero
		 * or negative value would read as long expired and invite a rebuild; a
		 * huge one would read as fresh forever and invite a doomed broadcast.
		 * Neither may be inferred from a number this code could not read.
		 */
		private fun PendingSnapState.allowsExpiration(value: Long): Boolean =
			if (this == PendingSnapState.INTENT) value == 0L else value > 0L

		/**
		 * One field as an **exact** whole number, or null.
		 *
		 * `JSONObject.getLong` coerces, and both of its coercions are unsafe
		 * here: it truncates a fractional number, so `1.9` would arrive as `1`,
		 * and it parses a string, so `"nonsense"` throws while `"12"` quietly
		 * succeeds. This record decides whether a signed transaction may reach
		 * the chain, so neither guess is acceptable — a value that is not
		 * exactly the integer that was written is a value this code does not
		 * have.
		 *
		 * Accepted: JSON integer tokens, however the runtime boxes them —
		 * `Int`, `Long`, `Short`, `Byte`, and a `BigInteger` that fits `Long`.
		 * Refused: **every** decimal type (`Double`, `Float`, `BigDecimal`),
		 * anything beyond `Long`, a string (the stored format never writes one),
		 * a boolean, an object, an array, an explicit null, and a missing key.
		 *
		 * Internal rather than private so the type-level rule can be exercised
		 * directly: Android hands over a `Double` for every decimal token, and
		 * that branch is unreachable through `fromJson(String)` on the JVM,
		 * whose parser boxes the same tokens as `BigDecimal`.
		 */
		internal fun JSONObject.exactLong(name: String): Long? = when (val v = opt(name)) {
			is Int -> v.toLong()
			is Long -> v
			is Short -> v.toLong()
			is Byte -> v.toLong()
			// `longValueExact()` is API 31 and this app runs from 26, so the
			// range is checked by hand — exact for the same reason and
			// available everywhere.
			is java.math.BigInteger -> v.takeIf { it.bitLength() < Long.SIZE_BITS }?.toLong()
			// **Everything else is refused, and the two decimal types are the
			// point of the rule.**
			//
			// Android's `JSONTokener` boxes any token containing `.`, `e` or
			// `E` as a `Double`, which has already discarded what would be
			// needed to trust it: `9007199254740993.0` arrives as
			// `9007199254740992.0`, byte-identical to the token one below it.
			// No test applied afterwards can separate them, so "it looks whole"
			// is not evidence of anything.
			//
			// The reference `org.json` this project's unit tests run against
			// boxes the same tokens as `BigDecimal`, and that is refused too —
			// `scale()` cannot distinguish them either, because
			// `BigDecimal("9.007199254740992E15")` and
			// `BigDecimal("9007199254740992")` both report scale zero and the
			// same precision. The only property that separates an integer token
			// from a decimal one is the *type the parser chose*, and both
			// parsers choose a decimal type for exactly the tokens to reject.
			//
			// Nothing legitimate is lost. `toJson` writes this field with
			// `put(Long)`, which emits an integer token, and an integer token
			// is boxed as `Int`, `Long` or `BigInteger` on both runtimes. A
			// decimal here means the record was not written by this app.
			else -> null
		}

		/**
		 * Parse a stored record, or null if it is unreadable.
		 *
		 * Fails to null rather than throwing: a record this code cannot read is
		 * already a record it cannot safely act on, and a crash loop on app start
		 * would be a worse outcome than one Snap needing to be reposted by hand.
		 */
		fun fromJson(raw: String): PendingSnap? = runCatching {
			val o = JSONObject(raw)
			val state = PendingSnapState.valueOf(o.getString("state"))
			// Read exactly, then judged against the state that claims it. A
			// record whose expiration cannot be trusted is a record that must
			// not be delivered *or* rebuilt, and the only way to guarantee
			// both is to refuse to build it at all — which lands it in
			// `Corrupt`, where every other unreadable record already goes.
			val expiration = o.exactLong("expirationEpochSec")
				?.takeIf { state.allowsExpiration(it) }
				?: return@runCatching null
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
				expirationEpochSec = expiration,
				state = state,
				createdAtEpochSec = o.getLong("createdAtEpochSec"),
				updatedAtEpochSec = o.getLong("updatedAtEpochSec"),
				lastError = o.optString("lastError").takeIf {
					it.isNotBlank() && !o.isNull("lastError")
				},
				// Fails closed, with no default. A missing or unrecognised
				// kind lands this record in `Corrupt`, where every other
				// unreadable record goes — never in `Absent`, which would let
				// it look brand new, and never reclassified as a root Snap,
				// which would let a reply be signed against the container.
				//
				// Nothing legitimate is lost: `toJson` has written this field
				// since the first version of this class, so a record without
				// one was not written by this app.
				kind = PendingSnapKind.of(o.optString("kind").takeIf { !o.isNull("kind") })
					?: return@runCatching null,
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

	const val SNAP_PREFIX = "rustedwax-snap"

	/**
	 * Replies get their own prefix.
	 *
	 * Cosmetic on the chain and useful everywhere else: a permlink in a log, a
	 * store key or a block explorer says which of the two write paths produced
	 * it without anyone having to look up the record it came from.
	 */
	const val REPLY_PREFIX = "rustedwax-reply"

	private const val SUFFIX_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
	private const val SUFFIX_LENGTH = 6

	fun generate(
		nowEpochSec: Long,
		random: java.util.Random = SecureRandom(),
		prefix: String = SNAP_PREFIX,
	): String {
		val suffix = (1..SUFFIX_LENGTH)
			.map { SUFFIX_CHARS[random.nextInt(SUFFIX_CHARS.length)] }
			.joinToString("")
		return "$prefix-$nowEpochSec-$suffix"
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
	 * Why this record's stored identity cannot be acted on, or null.
	 *
	 * The **author** is the field this answers for, and it is the one that
	 * reaches Hive. A record stored under Alice whose author says Bob is a
	 * record whose local half and published half disagree: the card would be
	 * drawn as Bob's while the comment would be signed as — whoever the caller
	 * happened to be. Whichever of the two is wrong, one of them is, and
	 * neither is safe to publish or to show.
	 *
	 * So three things must hold, and the first two are compared **without
	 * regard to case**, exactly as `HiveSnapPort` compares the identities it
	 * binds at signing time: one Hive posting key can authorize more than one
	 * account, so account names are what separate them, and a stored "Alice"
	 * naming the same account as a vault's "alice" must not be read as two
	 * different people.
	 *
	 *  - **both names are valid Hive account names exactly as stored.** Not
	 *    lowercased first, not trimmed, not repaired. A canonical Hive account
	 *    name is lowercase, so a durable `Alice` is not a spelling of a real
	 *    account — it is a name the chain would refuse and a value this app
	 *    never wrote. Normalising it here would mean inventing the account the
	 *    record probably meant and then signing as it, which is the one thing
	 *    a durable-identity check must never do;
	 *  - the author is the account the record is filed under. Compared
	 *    case-insensitively, which only runs *after* both names have passed
	 *    validation as stored — so by then the comparison is between two
	 *    canonical names and the tolerance costs nothing. It matches how
	 *    `HiveSnapPort` binds identities at signing time, where the vault's
	 *    spelling is outside this record's control;
	 *  - nothing is blank.
	 *
	 * Nothing here repairs or normalises anything. A durable identity that
	 * disagrees with itself, or that is not a name Hive could accept, is
	 * evidence — and the only safe thing to do with evidence is keep it.
	 */
	fun authorProblem(snap: PendingSnap): String? = when {
		snap.author.isBlank() -> "stored Snap author is missing"
		snap.account.isBlank() -> "stored Snap account is missing"
		// As stored, both of them. The frozen author is what reaches Hive
		// unchanged, so it has to be a name Hive could accept before anything
		// is built around it.
		!HiveAccountName.isValid(snap.author) ->
			"stored Snap author is not a Hive account name"
		!HiveAccountName.isValid(snap.account) ->
			"stored Snap account is not a Hive account name"
		!snap.author.equals(snap.account, ignoreCase = true) ->
			"stored Snap author is not the account it is filed under"
		else -> null
	}

	/**
	 * The same question, asked by somebody who wants to act as [account].
	 *
	 * Adds the third identity: the account asking. A record may be perfectly
	 * self-consistent and still not be this caller's to render, prepare, sign
	 * or send — which is the case an account switch produces, and the case a
	 * mis-keyed record produces.
	 */
	fun identityProblem(snap: PendingSnap, account: String): String? = when {
		authorProblem(snap) != null -> authorProblem(snap)
		account.isBlank() -> "no account was given"
		!account.equals(snap.account, ignoreCase = true) ->
			"this Snap belongs to a different account"
		!account.equals(snap.author, ignoreCase = true) ->
			"this Snap was written by a different account"
		else -> null
	}

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
				// The record disagrees with *itself* about who wrote it. Only
				// this event is implicated — no other key is named by it — and
				// it is locked for the same reason an unreadable record is:
				// there may already be a comment on chain under one of the two
				// names, and RustedWax cannot tell which.
				authorProblem(parsed) != null -> locked += keyEventId
			}
		}
		return locked
	}

	/**
	 * Records that are safe to enumerate: identity matching their own key, **and
	 * an event nothing else has implicated**.
	 *
	 * The second half is the part that is easy to miss. Agreeing with your own
	 * key is not enough, because a row is locked by what *other* rows say about
	 * it: a damaged entry filed under A that claims B locks B as well, and the
	 * well-formed record genuinely stored under B is, on its own, perfectly
	 * self-consistent. Judging it alone would let it through.
	 *
	 * It must not get through, for the same reason [lockedEventIds] locks it.
	 * When two entries both claim event B, RustedWax does not know which of them
	 * describes the comment that may already be on chain — and a caller handed
	 * the tidy-looking one would act on a record it cannot prove belongs to that
	 * event. So the answer is the same one [PendingSnapStore.read] gives for B:
	 * nothing usable.
	 *
	 * Computed against the whole map before anything is returned, so which entry
	 * the iteration happens to reach first cannot change the outcome.
	 */
	fun valid(account: String, entries: Map<String, String>): List<PendingSnap> {
		val locked = lockedEventIds(account, entries)
		return entries.mapNotNull { (keyEventId, raw) ->
			if (keyEventId in locked) return@mapNotNull null
			PendingSnap.fromJson(raw)?.takeIf {
				it.account == account && it.eventId == keyEventId
			}
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

	/**
	 * Every record for this account that [read] would hand back as `Present`.
	 *
	 * The two must agree. An event [read] refuses to answer — because some other
	 * stored row also claims it — is an event this list leaves out, so no caller
	 * can enumerate its way past a lock that a direct read would have enforced.
	 * The implicated ids are still reportable through [corruptEventIds]; they are
	 * simply never handed over as usable records.
	 */
	fun all(account: String): List<PendingSnap>

	/** Rows whose stored state could not be parsed. These are locked, not retried. */
	fun corruptEventIds(account: String): Set<String>
}

/**
 * @param prefsName which preference file this store owns.
 *
 * Root Snaps and replies get **separate files**, and the separation is the
 * point rather than a tidiness preference. Both are keyed by `account|eventId`,
 * and [PendingSnapIntegrity] locks an entire account's rows when any one record
 * disagrees with the key it is filed under — so a single file would let one
 * unreadable reply record lock a root Snap that has nothing to do with it, and
 * `restore` on either path would walk the other's records. Two files, one shape,
 * no shared blast radius.
 */
internal class SharedPreferencesPendingSnapStore(
	context: Context,
	prefsName: String = ROOT_SNAPS,
) : PendingSnapStore {

	private val prefs: SharedPreferences =
		context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

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

	companion object {
		const val ROOT_SNAPS = "rustedwax_pending_snaps"
		const val REPLIES = "rustedwax_pending_snap_replies"
	}
}
