package com.rustedwax.app.snaps

import com.rustedwax.hive.HiveBroadcaster
import com.rustedwax.hive.HiveKey
import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.SnapContainerResolver
import com.rustedwax.hive.TxSerializer

/**
 * The Hive reads and writes a Snap needs, behind one seam.
 *
 * Signing lives *behind* this port rather than in front of it, so the
 * publication rules can be tested without a private key existing anywhere in
 * the test sources. [SnapPublisher] never holds key material.
 */
interface SnapHivePort {
	fun resolveContainer(): SnapContainerResolver.Result

	/** Sign a comment with the signed-in account's posting key. */
	fun prepareComment(operation: TxSerializer.CommentOp): HivePreparationResult

	fun broadcastPrepared(prepared: PreparedHiveTransaction): HiveRpc.BroadcastResult
	fun observeTransaction(txId: String, expirationEpochSec: Long): HiveRpc.TransactionEvidence

	/** True/false when the chain could answer, null when it could not be asked. */
	fun contentExists(author: String, permlink: String): Boolean?
}

internal class HiveSnapPort(
	/** Read at signing time, never cached — an account switch must change it. */
	private val loadKey: () -> HiveKey?,
	private val broadcaster: HiveBroadcaster = HiveBroadcaster(),
	private val resolver: SnapContainerResolver = SnapContainerResolver(),
	private val rpc: HiveRpc = HiveRpc(),
) : SnapHivePort {
	override fun resolveContainer() = resolver.resolve()

	override fun prepareComment(operation: TxSerializer.CommentOp): HivePreparationResult {
		val key = loadKey() ?: return HivePreparationResult.Failed(
			HiveRpc.BroadcastResult.Rejected("RustedWax couldn't read your posting key."),
		)
		return broadcaster.prepareComment(key, operation)
	}

	override fun broadcastPrepared(prepared: PreparedHiveTransaction) =
		broadcaster.broadcastPrepared(prepared)

	override fun observeTransaction(txId: String, expirationEpochSec: Long) =
		broadcaster.observeTransaction(txId, expirationEpochSec)

	override fun contentExists(author: String, permlink: String): Boolean? =
		runCatching { rpc.getContent(author, permlink) != null }.getOrNull()
}

/**
 * Publishes one root History Snap, and cleans up after the ones that went wrong.
 *
 * Organised around a single asymmetry: a Snap that fails to post is an
 * inconvenience, a Snap that posts twice is permanent public litter RustedWax
 * cannot take back. Every uncertain outcome therefore resolves *towards* "it may
 * have landed".
 *
 *  1. **Prepare, persist, verify, then broadcast.** The record is committed and
 *     read back before a byte leaves the device. If persistence cannot be
 *     proven, nothing is sent at all.
 *  2. **The permlink is minted once**, and never while stored state is
 *     unreadable. A second broadcast under the same `author/permlink` is an
 *     *edit* on Hive; under a fresh one it is a duplicate.
 *  3. **Nothing retries by itself.** No timer, no queue, no background sweep. A
 *     new transaction only ever follows a fresh explicit user action, and only
 *     after absence has been positively established.
 */
class SnapPublisher(
	private val hive: SnapHivePort,
	private val store: PendingSnapStore,
	private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
	private val newPermlink: (Long) -> String = { SnapPermlink.generate(it) },
	private val newReplyPermlink: (Long) -> String = {
		SnapPermlink.generate(it, prefix = SnapPermlink.REPLY_PREFIX)
	},
) {

	sealed interface Outcome {
		/** On chain, proven by block inclusion or by the content itself. */
		data class Published(val contentId: String, val txId: String) : Outcome

		/** Nothing reached the chain. The draft is safe and may be retried. */
		data class Failed(val message: String) : Outcome

		/**
		 * It may or may not have landed. The draft is preserved, the card locks,
		 * and RustedWax will not offer another broadcast until it knows more.
		 */
		data class Uncertain(val message: String) : Outcome
	}

	/**
	 * Where a comment is going and what it will say.
	 *
	 * Deliberately computed *late* — inside [publish], after the stored record
	 * has already decided whether anything may be sent at all. Resolving a Snap
	 * container or building a body before that check would be doing work, and
	 * asking the network questions, on behalf of a row that is locked.
	 */
	private data class Destination(
		val parentAuthor: String,
		val parentPermlink: String,
		val body: String,
		val jsonMetadata: String,
	)

	private sealed interface DestinationResult {
		data class Ready(val destination: Destination) : DestinationResult
		data class Refused(val message: String) : DestinationResult
	}

	/** Post, or resume posting, the root Snap for one History row. */
	fun publishRoot(
		account: String,
		eventId: String,
		media: SnapMedia,
		userText: String,
	): Outcome = publish(
		account = account,
		eventId = eventId,
		kind = PendingSnap.KIND_ROOT_SNAP,
		mintPermlink = newPermlink,
		destination = { rootDestination(media, userText) },
	)

	/**
	 * Post, or resume posting, one reply *intent*.
	 *
	 * [target] may be the user's own root Snap or any comment beneath it,
	 * including one written by somebody else, and it has already been through
	 * [SnapReplyTarget.of] — so the strings that become `parent_author` and
	 * `parent_permlink` are shaped like an account and a permlink before a
	 * signature is anywhere near them.
	 *
	 * [intentId] is what makes a reply slot reusable without being repeatable.
	 * The record is filed under the parent **and** the intent, so:
	 *
	 *  - the same intent attempted again finds its own record. Confirmed means
	 *    published, and answers with the comment that already exists rather than
	 *    minting a second permlink — which is exactly the case a crash between
	 *    confirmation and the draft being cleared produces;
	 *  - an uncertain intent locks, and only reconciliation can move it;
	 *  - a *different* intent is a different record, so a genuinely later reply
	 *    to the same comment publishes normally.
	 *
	 * There is deliberately no flag here that lets a confirmed record be thrown
	 * away and rebuilt. Within one intent this behaves exactly like
	 * [publishRoot]: confirmed is final.
	 */
	fun publishReply(
		account: String,
		target: SnapReplyTarget,
		intentId: String,
		userText: String,
	): Outcome = publish(
		account = account,
		eventId = SnapReplyKey.of(target, intentId),
		kind = PendingSnap.KIND_REPLY,
		mintPermlink = newReplyPermlink,
		destination = { replyDestination(target, userText) },
	)

	private fun rootDestination(media: SnapMedia, userText: String): DestinationResult {
		val payload = SnapPayloadBuilder.build(userText, media)
		SnapPayloadBuilder.problem(payload, media)?.let { return DestinationResult.Refused(it) }

		return when (val c = hive.resolveContainer()) {
			is SnapContainerResolver.Result.Resolved -> DestinationResult.Ready(
				Destination(
					parentAuthor = c.container.author,
					parentPermlink = c.container.permlink,
					body = payload.body,
					jsonMetadata = payload.jsonMetadata,
				),
			)
			is SnapContainerResolver.Result.Unavailable -> DestinationResult.Refused(c.reason)
		}
	}

	/**
	 * A reply needs no container lookup: its parent is the comment the user
	 * tapped Reply on, and that is already an identity the chain gave us.
	 */
	private fun replyDestination(
		target: SnapReplyTarget,
		userText: String,
	): DestinationResult {
		val payload = SnapReplyPayloadBuilder.build(userText)
		SnapReplyPayloadBuilder.problem(payload)?.let { return DestinationResult.Refused(it) }
		return DestinationResult.Ready(
			Destination(
				parentAuthor = target.author,
				parentPermlink = target.permlink,
				body = payload.body,
				jsonMetadata = payload.jsonMetadata,
			),
		)
	}

	/**
	 * The one publication path, shared by root Snaps and replies.
	 *
	 * Shared on purpose and shared *entirely*: prepare, persist, verify,
	 * broadcast, reconcile. The duplicate-safety rules in this class were the
	 * expensive part to get right, and a second copy of them for replies would
	 * be a second chance to get them wrong — a reply is just as permanent and
	 * just as public as a Snap. What differs between the two callers is only
	 * where the comment goes and what it says, which is [destination]. The
	 * *rules* do not differ at all.
	 */
	private fun publish(
		account: String,
		eventId: String,
		kind: String,
		mintPermlink: (Long) -> String,
		destination: () -> DestinationResult,
	): Outcome {
		val existing = when (val read = store.read(account, eventId)) {
			is PendingSnapRead.Corrupt ->
				// Locked. Unreadable state may describe a comment that is already
				// live, so this row gets no new permlink and no transaction.
				return Outcome.Uncertain(corruptMessage(read.reason))
			is PendingSnapRead.Present -> read.snap
			PendingSnapRead.Absent -> null
		}

		when {
			existing == null -> Unit
			// Proven on chain: the final answer for this row or this intent, and
			// never the starting point for another transaction.
			existing.state == PendingSnapState.CONFIRMED ->
				return Outcome.Published(existing.contentId, existing.txId)
			existing.isUncertain ->
				return reconcile(account, eventId) ?: Outcome.Uncertain(UNCERTAIN_MESSAGE)
			else -> Unit
		}

		// A prepared-but-unsent transaction that has not expired is still exactly
		// the right bytes, and reusing it keeps the transaction id reconcilable.
		existing?.let {
			if (it.state == PendingSnapState.PREPARED && it.expirationEpochSec > nowEpochSec()) {
				return broadcast(it)
			}
		}

		val going = when (val d = destination()) {
			is DestinationResult.Ready -> d.destination
			is DestinationResult.Refused -> return Outcome.Failed(d.message)
		}

		val permlink = existing?.permlink ?: mintPermlink(nowEpochSec())

		val prepared = when (
			val p = hive.prepareComment(
				TxSerializer.CommentOp(
					parentAuthor = going.parentAuthor,
					parentPermlink = going.parentPermlink,
					author = account,
					permlink = permlink,
					title = "",
					body = going.body,
					jsonMetadata = going.jsonMetadata,
				),
			)
		) {
			is HivePreparationResult.Ready -> p.transaction
			is HivePreparationResult.Failed -> return when (val r = p.result) {
				is HiveRpc.BroadcastResult.Rejected -> Outcome.Failed(r.message)
				is HiveRpc.BroadcastResult.NetworkFailure -> Outcome.Failed(r.message)
				else -> Outcome.Failed("couldn't prepare this Snap")
			}
		}

		val now = nowEpochSec()
		val record = PendingSnap(
			account = account,
			eventId = eventId,
			author = account,
			permlink = permlink,
			parentAuthor = going.parentAuthor,
			parentPermlink = going.parentPermlink,
			body = going.body,
			jsonMetadata = going.jsonMetadata,
			signedTransactionJson = prepared.signedTransactionJson,
			txId = prepared.txId,
			expirationEpochSec = prepared.expirationEpochSec,
			state = PendingSnapState.PREPARED,
			createdAtEpochSec = existing?.createdAtEpochSec ?: now,
			updatedAtEpochSec = now,
			kind = kind,
		)

		// The network boundary is gated on durable state, not on hope. A Snap sent
		// without a persisted record could never be reconciled afterwards.
		if (!persist(record)) {
			return Outcome.Failed(
				"RustedWax couldn't save this Snap safely, so it didn't post. " +
					"Your draft is still here.",
			)
		}

		return broadcast(record)
	}

	/**
	 * Write, then prove the write. Returns false if either half fails, and the
	 * caller must then not broadcast.
	 */
	private fun persist(record: PendingSnap): Boolean {
		if (!store.write(record)) return false
		val readBack = store.read(record.account, record.eventId)
		return readBack is PendingSnapRead.Present && readBack.snap == record
	}

	private fun broadcast(record: PendingSnap): Outcome {
		val sending = record.advanced(PendingSnapState.BROADCASTING)
		if (!persist(sending)) {
			return Outcome.Failed(
				"RustedWax couldn't save this Snap safely, so it didn't post. " +
					"Your draft is still here.",
			)
		}

		val prepared = PreparedHiveTransaction(
			signedTransactionJson = sending.signedTransactionJson,
			txId = sending.txId,
			expirationEpochSec = sending.expirationEpochSec,
		)
		val result = runCatching { hive.broadcastPrepared(prepared) }.getOrElse {
			// An exception on the way out says nothing about what the node
			// received. Treated exactly like a lost response.
			return uncertain(sending, "lost contact while posting: ${it.message}")
		}

		return when (result) {
			is HiveRpc.BroadcastResult.Success -> when (result.evidence) {
				// In a block. The only broadcast-time proof of publication.
				HiveRpc.BroadcastResult.Evidence.BLOCK -> {
					persist(sending.advanced(PendingSnapState.CONFIRMED))
					Outcome.Published(sending.contentId, result.txId)
				}
				// Relaying, but in no block yet. A mempool transaction can still be
				// dropped or expire, so this is pending — not published. Calling it
				// published here would let the card clear a draft for a Snap that
				// never lands.
				HiveRpc.BroadcastResult.Evidence.MEMPOOL ->
					uncertain(sending, "this Snap is still going through — give it a moment")
			}

			// A refusal is *not* proof that nothing landed. The broadcast loop moves
			// on when a node's reply is lost, so the node that rejected may be the
			// second one asked while the first accepted it. Reconciled, never
			// assumed.
			is HiveRpc.BroadcastResult.Rejected -> uncertain(sending, result.message)

			// `Deferred` covers both a pre-acceptance refusal and "a node accepted
			// it and no block carried it yet" — opposite situations under one
			// label, so the whole category is uncertain.
			is HiveRpc.BroadcastResult.Deferred -> uncertain(sending, result.message)
			is HiveRpc.BroadcastResult.AcceptedUnconfirmed -> uncertain(sending, result.message)
			// Likewise not proof of absence: a node can accept and still lose its
			// reply, after which every remaining node fails and this comes back.
			is HiveRpc.BroadcastResult.NetworkFailure -> uncertain(sending, result.message)
		}
	}

	private fun uncertain(record: PendingSnap, message: String): Outcome {
		persist(record.advanced(PendingSnapState.ACCEPTED_UNCONFIRMED, message))
		// Ask the chain immediately — often it settles the question outright.
		return reconcile(record.account, record.eventId) ?: Outcome.Uncertain(message)
	}

	/**
	 * Decide an uncertain Snap against the chain, or leave it uncertain.
	 *
	 * Content evidence outranks transaction evidence. A transaction that expired
	 * without inclusion and one that was never sent are indistinguishable by id,
	 * but `author/permlink` either exists on chain or does not — and that is the
	 * question actually being asked.
	 *
	 * Returns null when there is nothing to reconcile.
	 */
	fun reconcile(account: String, eventId: String): Outcome? {
		val record = when (val read = store.read(account, eventId)) {
			is PendingSnapRead.Corrupt -> return Outcome.Uncertain(corruptMessage(read.reason))
			is PendingSnapRead.Present -> read.snap
			PendingSnapRead.Absent -> return null
		}
		if (record.state == PendingSnapState.CONFIRMED) {
			return Outcome.Published(record.contentId, record.txId)
		}
		if (record.state == PendingSnapState.FAILED) return null

		when (hive.contentExists(record.author, record.permlink)) {
			true -> {
				persist(record.advanced(PendingSnapState.CONFIRMED))
				return Outcome.Published(record.contentId, record.txId)
			}
			// Absent content is not on its own a verdict: the transaction may still
			// be in a mempool, seconds from inclusion.
			false -> Unit
			null -> return Outcome.Uncertain(UNCERTAIN_MESSAGE)
		}

		return when (hive.observeTransaction(record.txId, record.expirationEpochSec)) {
			HiveRpc.TransactionEvidence.BLOCK -> {
				persist(record.advanced(PendingSnapState.CONFIRMED))
				Outcome.Published(record.contentId, record.txId)
			}

			HiveRpc.TransactionEvidence.MEMPOOL ->
				Outcome.Uncertain("this Snap is still going through — give it a moment")

			// The only route to a clean slate, and it needs **both** halves:
			// independent nodes agreeing the transaction expired without inclusion,
			// *and* no comment at the permlink.
			HiveRpc.TransactionEvidence.ABSENT -> {
				persist(record.advanced(PendingSnapState.FAILED, "this Snap never reached Hive"))
				Outcome.Failed("this Snap never reached Hive — you can post it again")
			}

			HiveRpc.TransactionEvidence.UNAVAILABLE -> {
				persist(record.advanced(PendingSnapState.UNRESOLVED, UNCERTAIN_MESSAGE))
				Outcome.Uncertain(UNCERTAIN_MESSAGE)
			}
		}
	}

	/**
	 * Whether this row's outcome is still unknown, without asking the network.
	 *
	 * A pure store read, and the only question a caller needs answered before
	 * destroying something that points at a record: a row that is uncertain, or
	 * whose stored state cannot be read, must keep whatever still refers to it.
	 * Absent and decided rows answer false.
	 */
	fun isUnresolved(account: String, eventId: String): Boolean =
		when (val read = store.read(account, eventId)) {
			is PendingSnapRead.Corrupt -> true
			is PendingSnapRead.Present -> read.snap.isUncertain
			PendingSnapRead.Absent -> false
		}

	/**
	 * The body this row actually published, or null when there is no proven one.
	 *
	 * A pure store read. It exists so a caller settling a confirmed write can
	 * compare against **what reached Hive** rather than against what it happens
	 * to believe it sent: the two differ whenever a draft was edited while the
	 * network was busy, and the difference is exactly the text that must not be
	 * thrown away.
	 */
	fun publishedBody(account: String, eventId: String): String? =
		(store.read(account, eventId) as? PendingSnapRead.Present)?.snap
			?.takeIf { it.state == PendingSnapState.CONFIRMED }
			?.body

	/**
	 * Every Snap this account has **already proven**, read from disk alone.
	 *
	 * The local half of [restore], split out so a caller can draw what is
	 * settled before anything that needs the network runs. [restore] decides
	 * confirmed and unresolved rows in one pass, which means a single stalled
	 * reconciliation — a node that hangs, a device with no signal — holds back
	 * the whole list, including rows whose outcome was never in question. A row
	 * stored as `CONFIRMED` is on chain and known to be on chain; making it wait
	 * behind a different row's uncertainty is a reopened app looking like it
	 * forgot what it published.
	 *
	 * Deliberately narrow:
	 *
	 *  - **no RPC.** There is no call to [hive] on this path at all;
	 *  - **no mutation.** Nothing is persisted, cleared or advanced, so this
	 *    cannot move the duplicate-safety state machine. Reconciliation remains
	 *    the only thing that decides an unresolved row, and it still happens in
	 *    [restore], unchanged;
	 *  - **confirmed only.** Uncertain, unresolved, failed and corrupt rows are
	 *    absent rather than optimistically included. A card drawn for one of them
	 *    would assert a publication the app has refused to assert everywhere
	 *    else, which is the opposite of the point;
	 *  - **ordered.** `store.all` reflects `SharedPreferences.getAll()`'s hash
	 *    order, which is arbitrary and unstable. Sorting means what the caller
	 *    does with this list cannot depend on it.
	 */
	fun restoreLocal(account: String): List<Pair<String, Outcome.Published>> =
		store.all(account)
			.filter { it.state == PendingSnapState.CONFIRMED }
			.sortedBy { it.eventId }
			.map { it.eventId to Outcome.Published(it.contentId, it.txId) }

	/**
	 * Restore every stored Snap for an account after a restart.
	 *
	 * Confirmed rows come back as [Outcome.Published] so the card shows its posted
	 * state again; unresolved rows are reconciled by *reading* the chain. Nothing
	 * here broadcasts, and corrupt rows stay locked.
	 *
	 * This reads the chain, so it can be slow or stall outright. Callers that
	 * have something to show without it should draw [restoreLocal] first.
	 */
	fun restore(account: String): List<Pair<String, Outcome>> {
		val corrupt = store.corruptEventIds(account).map { eventId ->
			eventId to Outcome.Uncertain(corruptMessage("stored Snap state could not be read"))
		}
		val readable = store.all(account).mapNotNull { record ->
			when (record.state) {
				PendingSnapState.CONFIRMED ->
					record.eventId to Outcome.Published(record.contentId, record.txId)
				PendingSnapState.FAILED -> null
				else -> reconcile(account, record.eventId)?.let { record.eventId to it }
			}
		}
		return corrupt + readable
	}

	private fun PendingSnap.advanced(
		next: PendingSnapState,
		error: String? = null,
	): PendingSnap = copy(
		state = next,
		updatedAtEpochSec = nowEpochSec(),
		lastError = error,
	)

	private fun corruptMessage(reason: String) =
		"RustedWax can't read this Snap's saved state ($reason), so it won't post " +
			"again in case it already did."

	private companion object {
		const val UNCERTAIN_MESSAGE =
			"RustedWax couldn't confirm whether this Snap posted. " +
				"It won't post again until it knows."
	}
}
