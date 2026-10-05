package com.rustedwax.app.snaps

import com.rustedwax.hive.HiveCommentRead
import com.rustedwax.hive.HiveCommentState
import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.TxSerializer

/** Whether the chain, read just now, would let the author delete this. */
sealed interface SnapDeleteCheck {
	/** Every rule passes against current consensus state. */
	data class Eligible(val state: HiveCommentState) : SnapDeleteCheck

	/** Hive (or RustedWax's own rule) refuses it. [reason] says which. */
	data class Blocked(val reason: String) : SnapDeleteCheck

	/** The chain could not be asked well enough to say. Authorizes nothing. */
	data class Unknown(val message: String) : SnapDeleteCheck
}

/**
 * Deletes the signed-in author's own Snap or reply with Hive `delete_comment`.
 *
 * A deletion is the one Snap write that cannot be undone and cannot be
 * repaired by writing again, so the rules are stricter than an edit's:
 *
 *  - **the identity is the chain's.** The operation's `author/permlink` is the
 *    pair the chain returned for the object a moment before signing, and it
 *    must equal — byte for byte — the target the user confirmed. Nothing is
 *    rebuilt from display state;
 *  - **eligibility is read, never inferred.** Every attempt re-reads consensus
 *    state ([HiveRpc.readCommentState]) from a node proven current, and applies
 *    the deployed evaluator's rules — no replies, net rshares not positive,
 *    payout not yet made — to it. Rounded payout values, vote lists and thread
 *    rows decide nothing;
 *  - **success is absence.** Neither a node's acknowledgement nor even block
 *    inclusion is reported as a deletion. Only a current node answering that
 *    the object is gone — backed by block evidence for the transaction, or by
 *    a second current node — counts, and only then are local records touched;
 *  - **one delete in flight per object.** A sent transaction that could not
 *    be settled is remembered, and asking again settles *that* transaction by
 *    reading rather than signing a second one.
 *
 * Signing goes through the same [SnapHivePort] as every other Snap write, with
 * the same three-way account binding at both signing and sending.
 */
class SnapDeleter(
	private val hive: SnapHivePort,
	/**
	 * Where this kind's local records live. A confirmed record for a deleted
	 * object is removed once — and only once — the chain has proven the
	 * deletion, so it cannot draw the comment back from disk. Null skips that.
	 */
	private val store: PendingSnapStore? = null,
	private val sleep: (Long) -> Unit = { Thread.sleep(it) },
	private val settleAttempts: Int = SETTLE_ATTEMPTS,
	/**
	 * A durable local step that must land **after** the chain proved the
	 * deletion and **before** [store]'s record is retired — My Snaps' tombstone
	 * for a root (Issue #47). False, or a throw, means it did not land: the
	 * record is then kept as the last local evidence, and nothing is sent. The
	 * outcome is still [Outcome.Deleted], because that is what Hive proved.
	 */
	private val beforeRetire: (account: String, target: SnapReplyTarget, txId: String?) -> Boolean =
		{ _, _, _ -> true },
) {

	sealed interface Outcome {
		/** Proven gone from Hive. Safe to take off the screen. */
		data class Deleted(
			val contentId: String,
			/** Null when the object was already gone and nothing had to be sent. */
			val txId: String?,
		) : Outcome

		/** Nothing was sent: Hive's rules, or RustedWax's, refuse it now. */
		data class Blocked(val reason: String) : Outcome

		/** Not deleted, as far as RustedWax can prove. The content stays. */
		data class Failed(val message: String) : Outcome

		/**
		 * A delete was sent and its effect is not yet proven either way. The
		 * content stays on screen; asking again reads, it does not resend.
		 */
		data class Uncertain(val message: String) : Outcome
	}

	/** Account-scoped `author/permlink` to the delete sent and not yet settled. */
	private val unsettled = mutableMapOf<String, PreparedHiveTransaction>()

	/** True while a sent delete for this object still awaits proof. */
	fun hasUnsettled(account: String, target: SnapReplyTarget): Boolean =
		synchronized(unsettled) { slot(account, target) in unsettled }

	/**
	 * Read whether this may be deleted right now. Read-only: never signs.
	 *
	 * What the confirmation dialog is gated on, and the same function the
	 * delete itself runs again immediately before signing.
	 */
	fun check(account: String, target: SnapReplyTarget): SnapDeleteCheck {
		if (account.isBlank()) return SnapDeleteCheck.Blocked(SIGN_IN)
		if (!target.author.equals(account, ignoreCase = true)) {
			return SnapDeleteCheck.Blocked(NOT_AUTHOR)
		}
		unsettledWrite(account, target)?.let { return SnapDeleteCheck.Blocked(it) }
		val read = runCatching { hive.readCommentState(target.author, target.permlink, 1) }
			.getOrNull()
			?.firstOrNull()
			?: return SnapDeleteCheck.Unknown(UNREADABLE)
		return when (read) {
			is HiveCommentRead.Absent -> SnapDeleteCheck.Blocked(ALREADY_GONE)
			is HiveCommentRead.Present -> {
				rulesProblem(account, target, read.state)?.let { SnapDeleteCheck.Blocked(it) }
					?: SnapDeleteCheck.Eligible(read.state)
			}
		}
	}

	/** Delete — after reading eligibility again, at the moment of signing. */
	fun delete(account: String, target: SnapReplyTarget): Outcome {
		if (account.isBlank()) return Outcome.Failed(SIGN_IN)
		if (!target.author.equals(account, ignoreCase = true)) return Outcome.Failed(NOT_AUTHOR)

		// A delete already sent for this object is settled, never repeated.
		synchronized(unsettled) { unsettled[slot(account, target)] }?.let {
			return settle(account, target, it, blockProven = false)
		}

		val state = when (val c = check(account, target)) {
			is SnapDeleteCheck.Eligible -> c.state
			is SnapDeleteCheck.Unknown -> return Outcome.Failed(c.message)
			is SnapDeleteCheck.Blocked -> {
				// Gone already — by another frontend, or an earlier attempt
				// whose answer was lost. Proven twice before anything local
				// moves; nothing is sent either way.
				if (c.reason == ALREADY_GONE && provenAbsent(target, blockProven = false)) {
					return deleted(account, target, txId = null)
				}
				return Outcome.Blocked(c.reason)
			}
		}

		val operation = TxSerializer.DeleteCommentOp(
			// The chain's own spelling of the object, already proven equal to
			// the confirmed target by [rulesProblem].
			author = state.author,
			permlink = state.permlink,
		)
		val prepared = when (val p = runCatching { hive.prepareDelete(operation, account) }.getOrNull()) {
			is HivePreparationResult.Ready -> p.transaction
			is HivePreparationResult.Failed -> return Outcome.Failed(
				when (val r = p.result) {
					is HiveRpc.BroadcastResult.Rejected -> r.message
					is HiveRpc.BroadcastResult.NetworkFailure ->
						"RustedWax couldn't reach Hive, so nothing was deleted."
					else -> "RustedWax couldn't prepare this deletion, so nothing was deleted."
				},
			)
			null -> return Outcome.Failed("RustedWax couldn't prepare this deletion, so nothing was deleted.")
		}

		// Remembered before it can reach a node: from here on, a second tap
		// settles this transaction instead of signing another.
		synchronized(unsettled) { unsettled[slot(account, target)] = prepared }
		val result = runCatching { hive.broadcastPrepared(prepared, account) }.getOrNull()
		if (result is HiveRpc.BroadcastResult.Rejected) {
			// Refused outright: the transaction cannot land. Say why, in the
			// chain's words — a rule that changed after the check reads here.
			forget(account, target)
			return Outcome.Failed("Hive refused the deletion: ${result.message}")
		}
		val blockProven = result is HiveRpc.BroadcastResult.Success &&
			result.evidence == HiveRpc.BroadcastResult.Evidence.BLOCK
		return settle(account, target, prepared, blockProven)
	}

	/**
	 * Settle a sent delete by reading. Never signs, never broadcasts.
	 *
	 * Absence on a current node, backed by block evidence or a second current
	 * node, is a deletion. A transaction proven expired without inclusion is a
	 * failure that may be retried. A transaction in a block whose object is
	 * still present is an ineffective deletion, reported as such. Everything
	 * else is still uncertain and stays remembered.
	 */
	private fun settle(
		account: String,
		target: SnapReplyTarget,
		prepared: PreparedHiveTransaction,
		blockProven: Boolean,
	): Outcome {
		var included = blockProven
		repeat(settleAttempts) { attempt ->
			if (attempt > 0) sleep(SETTLE_INTERVAL_MS)
			if (provenAbsent(target, included)) return deleted(account, target, prepared.txId)
			if (!included) {
				when (observe(prepared)) {
					HiveRpc.TransactionEvidence.BLOCK -> {
						included = true
						if (provenAbsent(target, true)) return deleted(account, target, prepared.txId)
					}
					HiveRpc.TransactionEvidence.ABSENT -> {
						forget(account, target)
						return Outcome.Failed("Your deletion didn't reach Hive, so nothing was deleted.")
					}
					else -> Unit
				}
			}
		}
		if (included && stillPresent(target)) {
			// In a block, and the object is still there on a current node.
			forget(account, target)
			return Outcome.Failed(
				"Hive accepted the deletion but this is still there, so RustedWax is leaving it on screen.",
			)
		}
		return Outcome.Uncertain(
			"RustedWax couldn't confirm the deletion yet, so this is still shown. " +
				"Checking again won't send another.",
		)
	}

	private fun observe(prepared: PreparedHiveTransaction): HiveRpc.TransactionEvidence? =
		runCatching { hive.observeTransaction(prepared.txId, prepared.expirationEpochSec) }.getOrNull()

	/**
	 * Absent on a current node — and, unless block evidence already backs that,
	 * on a second, different current node too. No current node may disagree.
	 */
	private fun provenAbsent(target: SnapReplyTarget, blockProven: Boolean): Boolean {
		val reads = runCatching {
			hive.readCommentState(target.author, target.permlink, if (blockProven) 1 else 2)
		}.getOrNull().orEmpty()
		if (reads.any { it is HiveCommentRead.Present }) return false
		val absentNodes = reads.filterIsInstance<HiveCommentRead.Absent>().map { it.node }.toSet()
		return absentNodes.size >= if (blockProven) 1 else 2
	}

	private fun stillPresent(target: SnapReplyTarget): Boolean =
		runCatching { hive.readCommentState(target.author, target.permlink, 1) }
			.getOrNull()
			?.firstOrNull() is HiveCommentRead.Present

	private fun deleted(account: String, target: SnapReplyTarget, txId: String?): Outcome.Deleted {
		forget(account, target)
		if (runCatching { beforeRetire(account, target, txId) }.getOrDefault(false)) {
			retireLocal(account, target)
		}
		return Outcome.Deleted(target.contentId, txId)
	}

	private fun forget(account: String, target: SnapReplyTarget) {
		synchronized(unsettled) { unsettled.remove(slot(account, target)) }
	}

	/**
	 * Remove this account's settled records of the proven-deleted object, so no
	 * local copy draws it back. Only CONFIRMED/FAILED rows exist for it by now:
	 * an unsettled write would have blocked the delete. Best effort — a record
	 * left behind is drawn until the next attempt, never re-sent.
	 */
	private fun retireLocal(account: String, target: SnapReplyTarget) {
		val store = store ?: return
		runCatching {
			store.all(account)
				.filter {
					it.contentId == target.contentId &&
						it.state in SETTLED &&
						PendingSnapIntegrity.identityProblem(it, account) == null
				}
				.forEach { store.clear(account, it.eventId) }
		}
	}

	/**
	 * A local write for this object that has not settled. Deleting under it
	 * would race a transaction that may still create or recreate the object.
	 */
	private fun unsettledWrite(account: String, target: SnapReplyTarget): String? {
		val store = store ?: return null
		val rows = runCatching { store.all(account) }.getOrNull() ?: return null
		return if (rows.any { it.contentId == target.contentId && it.state !in SETTLED }) {
			"This is still being posted. Try again once it has settled."
		} else {
			null
		}
	}

	private fun slot(account: String, target: SnapReplyTarget) =
		"${account.lowercase()}|${target.contentId}"

	companion object {
		const val NOT_AUTHOR = "Only the author can delete this."
		const val SIGN_IN = "Sign in to your Hive account to delete."
		const val UNREADABLE =
			"RustedWax couldn't read this from an up-to-date Hive node, so nothing was deleted."
		const val ALREADY_GONE = "This is no longer on Hive."
		const val HAS_REPLIES = "Hive doesn't allow deleting this because it has replies."
		const val HAS_VOTES = "Hive doesn't allow deleting this because it has net positive votes."
		const val PAID_OUT = "Hive doesn't allow deleting this because its payout has already happened."
		const val NOT_A_COMMENT = "RustedWax can only delete Snaps and replies."

		private const val SETTLE_ATTEMPTS = 6
		private const val SETTLE_INTERVAL_MS = 3_000L

		private val SETTLED = setOf(PendingSnapState.CONFIRMED, PendingSnapState.FAILED)

		/**
		 * Why the chain object [state] may not be deleted by [account] as
		 * [target], or null. Pure, so the rules are pinned by tests.
		 *
		 * The deployed evaluator's own order: replies, then payout, then
		 * positive net rshares. Payout is checked from the cashout time, not
		 * from rshares — a paid comment reads `net_rshares` 0.
		 */
		fun rulesProblem(account: String, target: SnapReplyTarget, state: HiveCommentState): String? = when {
			state.author != target.author || state.permlink != target.permlink -> NOT_AUTHOR
			!state.author.equals(account, ignoreCase = true) -> NOT_AUTHOR
			state.parentAuthor.isBlank() || state.parentPermlink.isBlank() -> NOT_A_COMMENT
			state.children > 0 -> HAS_REPLIES
			!state.payoutPending -> PAID_OUT
			state.netRshares > 0 -> HAS_VOTES
			else -> null
		}
	}
}
