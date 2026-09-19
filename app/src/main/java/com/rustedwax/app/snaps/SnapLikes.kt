package com.rustedwax.app.snaps

import com.rustedwax.hive.HiveBroadcaster
import com.rustedwax.hive.HiveKey
import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.HiveVoteRead
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.TxSerializer
import com.rustedwax.hive.ViewerVote

/**
 * A signed vote, not yet sent.
 *
 * Splitting signing from sending is what makes the second safety read possible:
 * the window between "we decided to vote" and "the transaction is on the wire"
 * contains a signature, and signing is the slow part. Preparing first and then
 * re-reading the chain shrinks the window a competing frontend can slip through
 * to roughly one round trip, and — because a prepared transaction that is never
 * broadcast costs nothing and reaches nobody — the second read can veto it for
 * free.
 */
sealed interface SnapLikePrepare {

	/** Signed and ready. Broadcasting it is still the caller's decision. */
	data class Ready(val transaction: PreparedHiveTransaction) : SnapLikePrepare

	/**
	 * Nothing was signed. Definitive: no bytes exist, so no doubt does either.
	 *
	 * Covers a missing key, an operation [HiveBroadcaster.prepareVote] refuses,
	 * a chain head that could not be read, and — the one that matters most here
	 * — a stored account that is no longer the account this vote was decided
	 * for.
	 */
	data class Refused(val message: String) : SnapLikePrepare
}

/**
 * The three things a Like needs from Hive, behind one seam.
 *
 * [prepare] and [broadcast] are deliberately separate calls rather than one
 * `cast`. A single call cannot be interrupted between signing and sending, and
 * that interruption is exactly where the second safety read and the last
 * account check have to happen.
 *
 * All three live here rather than on [SnapThreadReader]'s read-only port,
 * because [prepare] signs: an object that can do this has a key behind it, and
 * keeping that fact visible in the type is how the thread-reading path stays
 * provably unable to write.
 */
interface SnapLikePort {

	/**
	 * The voter's current vote, from a node proven current. Never cached.
	 *
	 * [HiveVoteRead.Unavailable] is a real and frequent answer, and it must
	 * refuse the write rather than be retried into an optimistic one.
	 */
	fun readViewerVote(author: String, permlink: String, voter: String): HiveVoteRead

	/**
	 * Sign one vote with the posting key, **for [voter] and nobody else**.
	 *
	 * [voter] is the account the caller captured when the user tapped, passed
	 * back in so the signing boundary can refuse an operation that has outlived
	 * it. See [HiveSnapLikePort.prepare] for why the caller's own account checks
	 * are not enough on their own.
	 */
	fun prepare(operation: TxSerializer.VoteOp, voter: String): SnapLikePrepare

	/** Send exactly the transaction that was prepared, and nothing else. */
	fun broadcast(prepared: PreparedHiveTransaction): HiveRpc.BroadcastResult
}

internal class HiveSnapLikePort(
	/** Read at signing time, never cached — an account switch must change it. */
	private val loadKey: () -> HiveKey?,
	/**
	 * The username stored **beside** that key, read in the same breath.
	 *
	 * `KeyVault` writes the username and the WIF together and clears them
	 * together, so these two reads describe one account by construction. That is
	 * the property [prepare] leans on.
	 */
	private val storedAccount: () -> String?,
	private val broadcaster: HiveBroadcaster = HiveBroadcaster(),
	private val rpc: HiveRpc = HiveRpc(),
) : SnapLikePort {

	override fun readViewerVote(author: String, permlink: String, voter: String): HiveVoteRead =
		runCatching { rpc.findViewerVote(author, permlink, voter) }.getOrElse {
			// An exception on the way out is not an absence of votes. It is a
			// read that did not happen, and it refuses like every other one.
			HiveVoteRead.Unavailable("couldn't read your current vote: ${it.message}")
		}

	/**
	 * Sign, having first proved the key still belongs to the voter.
	 *
	 * The caller re-checks the signed-in account either side of this call, and
	 * that is still not sufficient on its own. One Hive posting key may sit in
	 * more than one account's posting authority, so a transaction that says
	 * `voter: alice`, signed after the user switched to `bob`, is not
	 * necessarily rejected by the chain — where the same key authorizes both, it
	 * is **accepted**, and Alice has cast a vote from Bob's session. Neither the
	 * signature nor the chain would object; the only thing that can object is
	 * this check.
	 *
	 * So the account is verified here, at the signing boundary, against the
	 * username the vault holds beside the key rather than against the UI's idea
	 * of who is signed in. Two guards for the same fact, and this is the one
	 * that cannot be outrun by a coroutine: whatever the caller believed when it
	 * started, nothing is signed unless the key on disk right now belongs to the
	 * account the vote names.
	 */
	override fun prepare(operation: TxSerializer.VoteOp, voter: String): SnapLikePrepare {
		if (!operation.voter.equals(voter, ignoreCase = true)) {
			return SnapLikePrepare.Refused("this Like was built for a different account.")
		}
		val stored = storedAccount()?.takeIf { it.isNotBlank() }
			?: return SnapLikePrepare.Refused("There's no saved Hive account to Like as.")
		if (!stored.equals(voter, ignoreCase = true)) {
			return SnapLikePrepare.Refused(
				"You've switched Hive accounts — this Like belonged to a different one.",
			)
		}
		val key = loadKey()
			?: return SnapLikePrepare.Refused("RustedWax couldn't read your posting key.")

		return when (val prepared = broadcaster.prepareVote(key, operation)) {
			is HivePreparationResult.Ready -> SnapLikePrepare.Ready(prepared.transaction)
			is HivePreparationResult.Failed -> when (val r = prepared.result) {
				// Everything `prepareVote` can fail with happens before a byte
				// leaves the device: its own validation, or a chain-head lookup
				// that never got as far as a transaction.
				is HiveRpc.BroadcastResult.Rejected -> SnapLikePrepare.Refused(r.message)
				is HiveRpc.BroadcastResult.NetworkFailure -> SnapLikePrepare.Refused(r.message)
				else -> SnapLikePrepare.Refused("RustedWax couldn't prepare this Like.")
			}
		}
	}

	/**
	 * Broadcast exactly what was signed — never the one-shot
	 * [HiveBroadcaster.broadcastVote].
	 *
	 * The same discipline [SnapPublisher] applies to comments. A helper that
	 * prepares and sends in one call cannot be stopped in between, and stopping
	 * in between is the whole mechanism by which a vote decided a second ago is
	 * abandoned rather than cast over somebody's newer one.
	 */
	override fun broadcast(prepared: PreparedHiveTransaction): HiveRpc.BroadcastResult =
		broadcaster.broadcastPrepared(prepared)
}

/**
 * Whether a Like may be cast, decided from chain state alone.
 *
 * [Cast] is the only outcome that permits a signature, and it carries the
 * weight rather than the percentage so that the conversion has already happened
 * by the time anything can act on it.
 */
sealed interface SnapLikeDecision {

	/** Eligible. The only branch that may prepare or broadcast anything. */
	data class Cast(val weight: Int) : SnapLikeDecision

	/**
	 * A positive vote is already there. Draw the heart filled and **send
	 * nothing** — see [ViewerVote.Positive].
	 */
	data class AlreadyLiked(val percent: Int?) : SnapLikeDecision

	/** A downvote from elsewhere. The control goes inert and sends nothing. */
	data class Inert(val message: String) : SnapLikeDecision

	/** The state could not be established, so nothing may be written. */
	data class Refuse(val message: String) : SnapLikeDecision
}

/**
 * The Like safety contract, as one pure function.
 *
 * Deliberately pure and deliberately separate from anything that can send: the
 * rules below are the whole of Stage 5's safety story, and they are worth
 * testing without a coroutine, a network, a key or an Android class anywhere
 * near them. Every branch that is not [SnapLikeDecision.Cast] is a branch on
 * which zero transactions are prepared and zero are broadcast.
 */
object SnapLikeDecisions {

	/**
	 * @param viewer the signed-in account, as the key scheme spells it.
	 * @param author the comment's author.
	 * @param read the result of the fresh, freshness-checked vote read.
	 * @param likePercent the stored Like strength, clamped again on the way in.
	 */
	fun of(
		viewer: String,
		author: String,
		read: HiveVoteRead,
		likePercent: Int,
	): SnapLikeDecision {
		if (viewer.isBlank()) return SnapLikeDecision.Refuse("Sign in to your Hive account to Like.")
		// Product rule, enforced again at the broadcaster. The control is hidden
		// on the user's own comments, so one arriving here is a bug rather than
		// an intent — and a bug that would spend the user's voting power on
		// themselves.
		if (viewer.equals(author, ignoreCase = true)) {
			return SnapLikeDecision.Refuse("You can't Like your own Snap.")
		}

		val fresh = when (read) {
			// No node was both current and able to answer. This is the branch
			// that must never optimistically become "no vote": that reading is
			// exactly how a stalled node causes RustedWax to overwrite a vote
			// cast minutes earlier somewhere else.
			is HiveVoteRead.Unavailable -> return SnapLikeDecision.Refuse(
				"RustedWax couldn't check your current Hive vote, so it didn't Like " +
					"anything. Try again in a moment.",
			)
			is HiveVoteRead.Fresh -> read.vote
		}

		return when (fresh) {
			// Already counted for something. v1 offers no Unlike and no
			// re-strength, so there is no second intent to express and nothing
			// to send.
			is ViewerVote.Positive -> SnapLikeDecision.AlreadyLiked(fresh.percent)

			is ViewerVote.Negative -> SnapLikeDecision.Inert(
				"You've already downvoted this on Hive. RustedWax won't change that.",
			)

			// A row that exists and cannot be read may be a vote, so it refuses
			// exactly as an unavailable read does.
			is ViewerVote.Unreadable -> SnapLikeDecision.Refuse(
				"RustedWax couldn't make sense of your current Hive vote (${fresh.reason}), " +
					"so it didn't Like anything.",
			)

			// The two eligible states: nothing there, or something worth nothing.
			ViewerVote.None, ViewerVote.Zero ->
				SnapLikeDecision.Cast(HiveBroadcaster.likeWeightForPercent(likePercent))
		}
	}
}
