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

	/** Post, or resume posting, the root Snap for one History row. */
	fun publishRoot(
		account: String,
		eventId: String,
		media: SnapMedia,
		userText: String,
	): Outcome {
		val existing = when (val read = store.read(account, eventId)) {
			is PendingSnapRead.Corrupt ->
				// Locked. Unreadable state may describe a Snap that is already
				// live, so this row gets no new permlink and no transaction.
				return Outcome.Uncertain(corruptMessage(read.reason))
			is PendingSnapRead.Present -> read.snap
			PendingSnapRead.Absent -> null
		}

		when {
			existing == null -> Unit
			existing.state == PendingSnapState.CONFIRMED ->
				return Outcome.Published(existing.contentId, existing.txId)
			existing.isUncertain ->
				return reconcile(account, eventId) ?: Outcome.Uncertain(UNCERTAIN_MESSAGE)
			else -> Unit
		}

		// A prepared-but-unsent transaction that has not expired is still exactly
		// the right bytes, and reusing it keeps the transaction id reconcilable.
		if (existing != null &&
			existing.state == PendingSnapState.PREPARED &&
			existing.expirationEpochSec > nowEpochSec()
		) {
			return broadcast(existing)
		}

		val payload = SnapPayloadBuilder.build(userText, media)
		SnapPayloadBuilder.problem(payload, media)?.let { return Outcome.Failed(it) }

		val container = when (val c = hive.resolveContainer()) {
			is SnapContainerResolver.Result.Resolved -> c.container
			is SnapContainerResolver.Result.Unavailable -> return Outcome.Failed(c.reason)
		}

		val permlink = existing?.permlink ?: newPermlink(nowEpochSec())

		val prepared = when (
			val p = hive.prepareComment(
				TxSerializer.CommentOp(
					parentAuthor = container.author,
					parentPermlink = container.permlink,
					author = account,
					permlink = permlink,
					title = "",
					body = payload.body,
					jsonMetadata = payload.jsonMetadata,
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
			parentAuthor = container.author,
			parentPermlink = container.permlink,
			body = payload.body,
			jsonMetadata = payload.jsonMetadata,
			signedTransactionJson = prepared.signedTransactionJson,
			txId = prepared.txId,
			expirationEpochSec = prepared.expirationEpochSec,
			state = PendingSnapState.PREPARED,
			createdAtEpochSec = existing?.createdAtEpochSec ?: now,
			updatedAtEpochSec = now,
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
	 * Restore every stored Snap for an account after a restart.
	 *
	 * Confirmed rows come back as [Outcome.Published] so the card shows its posted
	 * state again; unresolved rows are reconciled by *reading* the chain. Nothing
	 * here broadcasts, and corrupt rows stay locked.
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
