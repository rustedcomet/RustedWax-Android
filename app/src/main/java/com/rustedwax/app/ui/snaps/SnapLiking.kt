package com.rustedwax.app.ui.snaps

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import com.rustedwax.app.snaps.SnapLikeDecision
import com.rustedwax.app.snaps.SnapLikeDecisions
import com.rustedwax.app.snaps.SnapLikePort
import com.rustedwax.app.snaps.SnapLikePrepare
import com.rustedwax.app.snaps.SnapReplyTarget
import com.rustedwax.app.snaps.SnapThread
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.HiveVoteRead
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.TxSerializer
import com.rustedwax.hive.ViewerVote
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What this device knows about one comment's Like *beyond what Hive just said*.
 *
 * Deliberately small and deliberately transient. Hive is authoritative for
 * whether a vote exists — that answer arrives with every thread read, free, in
 * [com.rustedwax.app.snaps.SnapReply.viewerVote] — so this type only ever holds
 * what a chain read cannot yet tell the screen: an attempt in flight, an
 * attempt whose outcome is unknown, and a refusal worth showing.
 *
 * There is no `Unliked`. Absence of an entry means "ask the thread", which is
 * what keeps Hive authoritative rather than this map.
 *
 * Nothing here is persisted, and that is a deliberate limit rather than an
 * omission: optimism is a statement about the few seconds after a tap, and a
 * statement that survived a process death would be this app remembering a vote
 * it never proved. After a restart the chain is asked again and answers.
 */
sealed interface SnapLikeState {

	/**
	 * Tapped, and **drawn as Liked straight away** while the safe pipeline runs
	 * behind it.
	 *
	 * This is the optimistic state, and it exists because the honest one read
	 * badly. The pipeline behind a Like is two authoritative reads, a signature
	 * and a broadcast confirmation — five seconds or so of real work — and
	 * showing "Liking…" for all of it made a tap feel like it had been sent
	 * somewhere to be approved. The work is unchanged; only the moment the heart
	 * fills has moved, from the end of the pipeline to the start of it.
	 *
	 * It also still locks the slot, so a second tap cannot start a second vote.
	 */
	data object Optimistic : SnapLikeState

	/**
	 * Proven: either a block-confirmed broadcast, or an authoritative read that
	 * found a positive vote already there.
	 *
	 * Kept even while a subsequent `bridge` read still shows no vote — that read
	 * comes from hivemind, which lags behind the block this was confirmed in.
	 * It is retired the moment a readable chain answer arrives, whatever that
	 * answer says: see [SnapLikeController.reconcile].
	 */
	data class Liked(val percent: Int) : SnapLikeState

	/**
	 * A transaction crossed the network boundary and RustedWax cannot say what
	 * became of it. **The heart stays filled.**
	 *
	 * Staying filled is the deliberate choice. The vote probably landed — the
	 * common causes are a lost reply and a mempool sighting, not a refusal — and
	 * reverting on a maybe would mean showing an outline heart for a vote that
	 * exists, which invites the one tap this state must not receive.
	 *
	 * The only thing that resolves it is a **read**. There is no resend, no
	 * timer and no queue: see [SnapLikeController.recheck].
	 */
	data class Pending(val message: String) : SnapLikeState

	/**
	 * Nothing was sent, or nothing is there. The heart goes back to an outline
	 * and the reason is shown.
	 *
	 * Reached from a refused preflight read, a refused signature, an account
	 * that changed under the attempt, and from a reconciliation authoritative
	 * enough to prove the vote is genuinely absent.
	 */
	data class Refused(val message: String) : SnapLikeState

	/** A downvote from elsewhere. The control is inert and nothing was sent. */
	data class Inert(val message: String) : SnapLikeState
}

/** How the heart should be drawn for one comment, right now. */
enum class SnapHeart {
	/** No vote, or a removed one. Tappable. */
	OUTLINE,

	/**
	 * Drawn as Liked. Not tappable — v1 has no Unlike.
	 *
	 * Covers a proven vote *and* an optimistic one. The screen deliberately
	 * cannot tell them apart: there is no spinner, no "Liking…" and no
	 * half-filled state, because a tap the app fully intends to honour should
	 * not look different from one it has already finished honouring.
	 */
	FILLED,

	/**
	 * Drawn, but doing nothing: a downvote from elsewhere, or a vote state this
	 * app could not establish. Better than hiding the control, which would read
	 * as "this comment cannot be Liked".
	 */
	INERT,
}

/**
 * Likes for the thread screen: one Hive vote per tap, and never more than one.
 *
 * Holds no safety rules of its own. Every decision about whether a vote may be
 * cast lives in [SnapLikeDecisions], which is pure; this exists to give Compose
 * something observable and to make three things structurally impossible from
 * the UI side:
 *
 *  - **a second tap while one is in flight.** The slot flips to
 *    [SnapLikeState.Optimistic] *synchronously*, inside the tap and before
 *    the coroutine is scheduled, so the guard does not depend on a click
 *    handler winning a race;
 *  - **acting as, or writing state for, the wrong account.** The account is
 *    read inside the coroutine at the moment of acting, and re-read again
 *    before anything is written back. State is keyed by account as well, so an
 *    account switch stops another account's hearts being readable at all rather
 *    than trying to migrate them;
 *  - **an automatic resend.** There is no retry, no backoff and no queue
 *    anywhere in this class. A new transaction only ever follows a fresh
 *    explicit tap, and only after a fresh read has authorized it.
 *
 * Nothing here is persisted. A vote's durable record is the chain's, and the
 * chain answers for free on the next thread read, so a local store would be a
 * second source of truth whose only possible contribution is to disagree.
 */
@Stable
class SnapLikeController internal constructor(
	private val scope: CoroutineScope,
	private val port: () -> SnapLikePort?,
	private val account: () -> String?,
	/** Read late, at the moment of acting: changing it affects future Likes only. */
	private val likePercent: () -> Int,
	private val io: CoroutineDispatcher = Dispatchers.IO,
) {

	/** Account-scoped `like|author/permlink` to what this device knows. */
	private val states = mutableStateMapOf<String, SnapLikeState>()

	/** The signed-in account as the key scheme spells it. Never null. */
	private fun accountId(): String = account()?.takeIf { it.isNotBlank() } ?: ANONYMOUS

	/**
	 * The key one comment's Like state is filed under, for this account.
	 *
	 * `like|` mirrors [com.rustedwax.app.snaps.SnapReplyKey]'s `reply|` so the
	 * two can never collide on a string, and the account prefix is what stops
	 * one Hive user seeing another's hearts — an account switch simply looks up
	 * keys that do not exist.
	 */
	fun likeKey(target: SnapReplyTarget): String = keyFor(accountId(), target)

	/**
	 * The key for one comment under a **named** account.
	 *
	 * An attempt has to be able to name its own slot after the signed-in account
	 * has changed underneath it — otherwise the claim it placed on the way in
	 * cannot be released on the way out, and the slot stays locked on
	 * [SnapLikeState.Optimistic] for the rest of the session. So the account is an
	 * argument here and read from the outside world only in [likeKey].
	 */
	private fun keyFor(who: String, target: SnapReplyTarget): String =
		SnapDraftKey.of(who, "$PREFIX|${target.contentId}")

	fun state(target: SnapReplyTarget): SnapLikeState? = states[likeKey(target)]

	/**
	 * True while an attempt is running. The heart is already filled; this is
	 * only about refusing a second attempt.
	 */
	fun isBusy(target: SnapReplyTarget): Boolean = state(target) == SnapLikeState.Optimistic

	/**
	 * Whether this comment gets a heart at all.
	 *
	 * False for the user's own root Snap and their own replies, matching
	 * [com.rustedwax.hive.HiveBroadcaster]'s own case-insensitive self-vote
	 * refusal — and false when nobody is signed in, because there is no account
	 * whose vote a heart could be describing.
	 */
	fun showsHeart(author: String): Boolean {
		val who = account()?.takeIf { it.isNotBlank() } ?: return false
		return !who.equals(author, ignoreCase = true)
	}

	/**
	 * How to draw this comment's heart.
	 *
	 * Local state first, because it is the only thing that knows about a tap
	 * that has just happened or an attempt whose outcome is unknown. Everything
	 * else falls through to [chainVote] — the state Hive reported on the last
	 * read — which is what keeps the chain authoritative over this map.
	 *
	 * Three local states all draw [SnapHeart.FILLED]: optimistic, proven and
	 * pending. That is the point of the change — the user tapped, RustedWax
	 * intends to honour it, and nothing in between is worth showing them.
	 */
	fun heart(target: SnapReplyTarget, chainVote: ViewerVote): SnapHeart =
		when (state(target)) {
			SnapLikeState.Optimistic -> SnapHeart.FILLED
			is SnapLikeState.Liked -> SnapHeart.FILLED
			is SnapLikeState.Pending -> SnapHeart.FILLED
			is SnapLikeState.Inert -> SnapHeart.INERT
			// Reverted. The control is live again, because nothing is there.
			is SnapLikeState.Refused -> SnapHeart.OUTLINE
			null -> when (chainVote) {
				is ViewerVote.Positive -> SnapHeart.FILLED
				is ViewerVote.Negative -> SnapHeart.INERT
				is ViewerVote.Unreadable -> SnapHeart.INERT
				ViewerVote.None, ViewerVote.Zero -> SnapHeart.OUTLINE
			}
		}

	/**
	 * The number beside the heart: how many people liked this, right now.
	 *
	 * [chainCount] is what the last chain read counted, and it is the whole
	 * answer except for one gap — the seconds between a tap and hivemind
	 * catching up, when the viewer's own brand-new vote is real but not yet in
	 * any response. So exactly one vote is added, and only under the condition
	 * that closes that gap without ever double-counting:
	 *
	 *  - **this device believes the viewer has voted** — optimistic, proven, or
	 *    an attempt whose outcome is unknown, which are the three states that
	 *    draw a filled heart — **and**
	 *  - **the authoritative read does not already include it.**
	 *
	 * The moment a fresh read carries the viewer's own positive vote, the
	 * second condition fails and the count is the chain's alone: `♡ 5` tapped
	 * reads `♥ 6` immediately, and stays `♥ 6` when the read catches up rather
	 * than becoming 7.
	 *
	 * A refusal proves the vote is not there, and the local state goes with it,
	 * so the count falls back to the chain's — the heart and the number revert
	 * together. An ambiguous attempt keeps both, because the vote probably
	 * landed and nothing here may resend to find out.
	 *
	 * Pure arithmetic over state that already exists: it reads nothing, sends
	 * nothing, and decides nothing about whether a Like may be cast.
	 */
	fun likeCount(target: SnapReplyTarget, chainVote: ViewerVote, chainCount: Int): Int {
		val counted = chainCount.coerceAtLeast(0)
		val mine = when (state(target)) {
			SnapLikeState.Optimistic, is SnapLikeState.Liked, is SnapLikeState.Pending -> true
			is SnapLikeState.Refused, is SnapLikeState.Inert, null -> false
		}
		val alreadyCounted = chainVote is ViewerVote.Positive
		return if (mine && !alreadyCounted) counted + 1 else counted
	}

	/** The message to show under this comment, if any. */
	fun notice(target: SnapReplyTarget): String? = when (val s = state(target)) {
		is SnapLikeState.Pending -> s.message
		is SnapLikeState.Refused -> s.message
		is SnapLikeState.Inert -> s.message
		// An optimistic Like says nothing at all. It looks exactly like a
		// finished one, which is the whole point.
		else -> null
	}

	/**
	 * True while the outcome is unknown and only a **read** is offered.
	 *
	 * The heart is filled in this state, so this drives the re-check control
	 * beside it rather than the heart itself.
	 */
	fun isPending(target: SnapReplyTarget): Boolean = state(target) is SnapLikeState.Pending

	/**
	 * Every Like this device tried and could not settle, for whoever is signed
	 * in now.
	 *
	 * The seam Stage 6 will read when it has somewhere to put this. It is a
	 * plain query over state that already exists — no store, no channel, no
	 * notification machinery — so adding the bell later is adding a reader, not
	 * unpicking this.
	 */
	fun needsAttention(): List<Pair<SnapReplyTarget, SnapLikeState>> {
		val prefix = "${accountId()}|$PREFIX|"
		return states.entries
			.filter { it.key.startsWith(prefix) }
			.filter { it.value is SnapLikeState.Pending || it.value is SnapLikeState.Refused }
			.mapNotNull { entry ->
				val id = entry.key.removePrefix(prefix)
				val author = id.substringBefore('/')
				val permlink = id.substringAfter('/', "")
				SnapReplyTarget.of(author, permlink)?.let { it to entry.value }
			}
			.sortedBy { it.first.contentId }
	}

	/**
	 * Fold a freshly-read conversation back in, so Hive stays authoritative.
	 *
	 * Called when a thread load lands. A conversation that has just been re-read
	 * describes these comments more recently than any local answer from an
	 * earlier tap, so the local answer is retired and the heart is drawn from
	 * the chain's state instead.
	 *
	 * **[SnapLikeState.Liked] is retired on any readable answer, including one
	 * that shows no vote.** It used to survive an absence, on the reasoning that
	 * hivemind lags behind the block a vote was confirmed in — which is true,
	 * and is also how a Like removed on another frontend an hour later stayed
	 * filled forever, because nothing was ever allowed to contradict it. A
	 * momentary flicker back to outline after a very fresh vote is the price of
	 * the chain being the thing that decides. The next read fills it again.
	 *
	 * Two states resist, each for a reason:
	 *
	 *  - **[SnapLikeState.Optimistic]** is an attempt still in flight, and this
	 *    read ran beside it rather than after it. Undoing a tap the app is
	 *    actively honouring would be the worst flicker of all;
	 *  - **[SnapLikeState.Pending]** is retired *only* by a positive vote. This
	 *    is a `bridge` read, from hivemind, which lags — so here "not there yet"
	 *    and "never landed" look identical, and a display-only absence is not
	 *    allowed to undo an ambiguous Like. Only [recheck], which asks a node
	 *    proven current, can decide that one.
	 *
	 * A viewer vote that could not be read changes nothing at all. That is the
	 * fail-safe direction: a display read this app does not understand is not
	 * evidence about anybody's vote, so whatever was known before stands.
	 */
	fun reconcile(thread: SnapThread) {
		thread.rows.forEach { node ->
			val target = SnapReplyTarget.of(node.reply) ?: return@forEach
			val vote = node.reply.viewerVote
			// Nothing trustworthy arrived for this comment, so nothing changes.
			if (vote is ViewerVote.Unreadable) return@forEach
			val key = likeKey(target)
			val positive = vote is ViewerVote.Positive
			when (states[key]) {
				SnapLikeState.Optimistic -> Unit
				// The chain now decides, whatever it says — filled for a positive
				// vote, outline for an absence or a zero, inert for a downvote.
				is SnapLikeState.Liked -> states.remove(key)
				// A positive vote settles it. An absence here proves nothing.
				is SnapLikeState.Pending -> if (positive) states.remove(key)
				is SnapLikeState.Refused, is SnapLikeState.Inert -> states.remove(key)
				null -> Unit
			}
		}
	}

	/**
	 * Like one comment: read the chain, decide, and only then maybe sign.
	 *
	 * The order is the safety contract and it is not negotiable. Nothing is
	 * prepared and nothing is broadcast until a node that was proven current
	 * has answered and [SnapLikeDecisions] has returned
	 * [SnapLikeDecision.Cast]. Every other decision leaves the network
	 * untouched.
	 */
	fun like(target: SnapReplyTarget) {
		val who = accountId()
		val key = keyFor(who, target)
		// Claimed before the coroutine exists, so a second tap arriving in the
		// same frame finds the slot taken and is turned away.
		//
		// Refused by *every* state that already draws something other than an
		// outline, not merely by one in flight. The screen disables the control
		// on those too, but the screen is not where this rule should live: a
		// tap on a heart that is already filled, pending or inert has nothing
		// to ask for, and a controller that relied on the UI to know that would
		// cast a second vote the first time some other caller forgot.
		// [SnapLikeState.Refused] is the one exception — it is the state that
		// means "nothing is there", so it is the one a fresh tap may follow.
		when (states[key]) {
			SnapLikeState.Optimistic,
			is SnapLikeState.Liked,
			is SnapLikeState.Pending,
			is SnapLikeState.Inert,
			-> return
			is SnapLikeState.Refused, null -> Unit
		}
		if (who == ANONYMOUS) {
			states[key] = SnapLikeState.Refused("Sign in to your Hive account to Like.")
			return
		}
		// **The heart fills here**, synchronously, inside the tap. Everything
		// below this line is the same pipeline as before and runs behind it;
		// nothing about what is read, checked or signed has moved.
		states[key] = SnapLikeState.Optimistic

		scope.launch {
			val next = withContext(io) { attempt(who, target) }
			// Two ways out with nothing written. `next == null` is an attempt
			// that aborted because the account changed under it; the account
			// check is the same question asked once more here, for a switch that
			// landed after the last check inside [attempt]. Either way the
			// outcome belongs to nobody on screen, so it is dropped and only the
			// claim is released.
			if (next == null || accountId() != who) release(key) else states[key] = next
		}
	}

	/**
	 * Re-ask the chain about a Like whose outcome is unknown. **Reads only.**
	 *
	 * There is no branch in this function that prepares, signs or broadcasts
	 * anything, and there must never be one: the whole reason a Like can end up
	 * [SnapLikeState.Pending] is that a second transaction might duplicate a
	 * vote that is already live.
	 *
	 * A read that finds no vote does *not* clear the ambiguity. hived is
	 * authoritative and current, but a transaction can still be sitting in a
	 * mempool seconds from inclusion, so absence here means "not yet", not
	 * "never".
	 */
	fun recheck(target: SnapReplyTarget) {
		val who = accountId()
		val key = keyFor(who, target)
		if (states[key] == SnapLikeState.Optimistic) return
		if (who == ANONYMOUS) return
		// Deliberately **not** claimed as Optimistic: this sends nothing, and
		// flipping the slot would make a read look like an attempt. The heart is
		// already filled and stays filled while the read runs.
		val before = states[key]

		scope.launch {
			val next = withContext(io) {
				when (val vote = readVote(who, target)) {
					// Proven. Settle to an ordinary Liked.
					is ViewerVote.Positive -> SnapLikeState.Liked(vote.percent ?: 0)
					is ViewerVote.Negative -> SnapLikeState.Inert(
						"You've already downvoted this on Hive. RustedWax won't change that.",
					)
					// A node proven current says there is no vote here. That is
					// authoritative enough to stop claiming there is one — so the
					// heart reverts and the failure is stated. Nothing is resent;
					// the control simply becomes live again, and any fresh tap
					// runs the whole pipeline from the start.
					ViewerVote.None, ViewerVote.Zero -> SnapLikeState.Refused(
						"That Like didn't reach Hive. Tap the heart to try again.",
					)
					// Still cannot tell. Stay exactly as we were — filled and
					// pending — and above all do not send anything.
					is ViewerVote.Unreadable -> before as? SnapLikeState.Pending
						?: SnapLikeState.Pending(
							"RustedWax still can't confirm this Like reached Hive. " +
								"It won't send it again until it knows.",
						)
				}
			}
			if (accountId() != who) return@launch
			states[key] = next
		}
	}

	/**
	 * Let go of a claim whose account is no longer signed in.
	 *
	 * Not the same thing as writing an outcome, and the distinction is the whole
	 * point. The outcome belongs to an account nobody is looking at and is
	 * simply dropped — but the [SnapLikeState.Optimistic] claim placed on the
	 * way in is a lock this coroutine took, and a lock nobody releases is a
	 * heart stuck filled for the rest of the session on a vote that was never
	 * cast, on a comment the user can then never Like. Released on every path out, exactly as
	 * [SnapThreadController]'s in-flight guard is.
	 *
	 * Only ever removes *its own* claim: a fresh attempt started since is left
	 * alone.
	 */
	private fun release(key: String) {
		if (states[key] == SnapLikeState.Optimistic) states.remove(key)
	}

	/** The fresh vote read, or an unreadable answer when there is no port. */
	private fun readVote(who: String, target: SnapReplyTarget): ViewerVote {
		val p = port() ?: return ViewerVote.Unreadable("Likes aren't available right now")
		return when (val read = runCatching {
			p.readViewerVote(target.author, target.permlink, who)
		}.getOrElse { HiveVoteRead.Unavailable("couldn't read your vote: ${it.message}") }) {
			is HiveVoteRead.Fresh -> read.vote
			is HiveVoteRead.Unavailable -> ViewerVote.Unreadable(read.reason)
		}
	}

	/**
	 * One attempt, start to finish, off the main thread.
	 *
	 * The sequence is the safety contract, and the order of it is the whole
	 * thing:
	 *
	 *  1. **first fresh read** — a node proven current, asked for this voter's
	 *     current vote;
	 *  2. **decide** — [SnapLikeDecisions], pure. Anything but `Cast` returns
	 *     here, having prepared nothing and sent nothing;
	 *  3. **account check** — before a signature exists;
	 *  4. **prepare** — sign, without sending. The signing boundary checks the
	 *     account again, against the vault rather than the UI;
	 *  5. **account check** — signing takes a round trip for the chain head, so
	 *     a switch can have landed during it;
	 *  6. **second fresh read** — the chain again, now that the slow part is
	 *     behind us;
	 *  7. **decide again** — and it must *still* be `Cast`. A vote that appeared
	 *     between the two reads vetoes the transaction that is already signed;
	 *  8. **account check** — the last one this class makes, immediately before
	 *     the wire;
	 *  9. **broadcast** — exactly what was prepared, with nothing in between,
	 *     and with the captured account handed to the port so it can make the
	 *     same check against the **vault**. This class can only see session
	 *     state, and a switch reaches the vault first — see
	 *     [com.rustedwax.app.snaps.HiveSnapLikePort.broadcast].
	 *
	 * This does not make the operation atomic with another frontend and cannot:
	 * a vote cast in the milliseconds between step 6 and step 9 will still be
	 * overwritten. What it does is shrink the window from "however long signing
	 * takes" to "one round trip", and make every longer pause — a slow chain
	 * head, a retried node, a user switching accounts mid-tap — fail closed.
	 *
	 * Returns null to mean **aborted**: write nothing, release the claim, say
	 * nothing to either account. It writes no state itself, so the caller's own
	 * check is what decides whether any of this reaches a screen.
	 */
	private fun attempt(who: String, target: SnapReplyTarget): SnapLikeState? {
		val p = port() ?: return SnapLikeState.Refused("Likes aren't available right now.")
		val percent = likePercent()

		// 1–2. Read, then decide. Nothing is signed before this.
		val first = decide(p, who, target, percent)
		if (first !is SnapLikeDecision.Cast) return stateFor(first)

		// 3. Before a signature exists.
		if (accountId() != who) return null

		// 4. Sign. Still nothing on the wire.
		val prepared = runCatching { p.prepare(voteOp(who, target, first.weight), who) }
			.getOrElse {
				return SnapLikeState.Refused("RustedWax couldn't prepare this Like.")
			}
		val signed = when (prepared) {
			is SnapLikePrepare.Refused -> return SnapLikeState.Refused(prepared.message)
			is SnapLikePrepare.Ready -> prepared.transaction
		}

		// 5. Signing cost a round trip; the account may have moved during it.
		if (accountId() != who) return null

		// 6–7. The second read, and the veto. A transaction that is already
		// signed is simply dropped here — it reached nobody, so abandoning it
		// costs nothing at all.
		val second = decide(p, who, target, percent)
		if (second !is SnapLikeDecision.Cast) return stateFor(second)

		// 8. The last check before the wire. Session state — the port makes
		// the same check against the vault, which is the one that counts.
		if (accountId() != who) return null

		// 9. Send what was signed, immediately, as the account it was signed
		// for. The captured name goes with it so the transmission boundary can
		// refuse a vote whose session ended while this ran.
		return send(p, signed, who, percent)
	}

	/** One fresh read and the decision that follows from it. */
	private fun decide(
		p: SnapLikePort,
		who: String,
		target: SnapReplyTarget,
		percent: Int,
	): SnapLikeDecision {
		val read = runCatching { p.readViewerVote(target.author, target.permlink, who) }
			.getOrElse { HiveVoteRead.Unavailable("couldn't read your current vote: ${it.message}") }
		return SnapLikeDecisions.of(who, target.author, read, percent)
	}

	private fun voteOp(who: String, target: SnapReplyTarget, weight: Int) = TxSerializer.VoteOp(
		voter = who,
		author = target.author,
		permlink = target.permlink,
		weight = weight,
	)

	/** Every decision that is not [SnapLikeDecision.Cast], as a state. */
	private fun stateFor(decision: SnapLikeDecision): SnapLikeState = when (decision) {
		is SnapLikeDecision.AlreadyLiked -> SnapLikeState.Liked(decision.percent ?: 0)
		is SnapLikeDecision.Inert -> SnapLikeState.Inert(decision.message)
		is SnapLikeDecision.Refuse -> SnapLikeState.Refused(decision.message)
		// Unreachable: the callers branch on `Cast` before asking for a state.
		// Present so a sixth decision added later cannot compile into silence.
		is SnapLikeDecision.Cast -> SnapLikeState.Refused("RustedWax couldn't complete this Like.")
	}

	/**
	 * Send, then classify what came back.
	 *
	 * Only [HiveRpc.BroadcastResult.Evidence.BLOCK] is proof. Everything else —
	 * a mempool sighting, an accepting node that could not be confirmed, a
	 * deferral, a rejection that may have raced an acceptance on another node, a
	 * transport failure — is [SnapLikeState.Pending], which keeps the heart
	 * filled and offers a re-read and nothing else.
	 */
	private fun send(
		p: SnapLikePort,
		prepared: PreparedHiveTransaction,
		voter: String,
		percent: Int,
	): SnapLikeState {
		val result = runCatching { p.broadcast(prepared, voter) }.getOrElse {
			// An exception on the way out says nothing about what the node
			// received. Treated exactly like a lost response.
			return SnapLikeState.Pending(lostMessage("lost contact while sending: ${it.message}"))
		}

		return when (result) {
			is HiveRpc.BroadcastResult.Success -> when (result.evidence) {
				HiveRpc.BroadcastResult.Evidence.BLOCK -> SnapLikeState.Liked(percent)
				// Relaying, in no block yet. A mempool transaction can still be
				// dropped or expire, so this is not proof.
				HiveRpc.BroadcastResult.Evidence.MEMPOOL ->
					SnapLikeState.Pending(lostMessage("this Like is still going through"))
			}
			is HiveRpc.BroadcastResult.Rejected ->
				SnapLikeState.Pending(lostMessage(result.message))
			is HiveRpc.BroadcastResult.Deferred ->
				SnapLikeState.Pending(lostMessage(result.message))
			is HiveRpc.BroadcastResult.AcceptedUnconfirmed ->
				SnapLikeState.Pending(lostMessage(result.message))
			is HiveRpc.BroadcastResult.NetworkFailure ->
				SnapLikeState.Pending(lostMessage(result.message))
		}
	}

	private fun lostMessage(detail: String) =
		"$detail — RustedWax can't confirm this Like reached Hive, so it won't send " +
			"it again until it knows."

	private companion object {
		/** What [SnapDraftKey] files a signed-out user's rows under. */
		const val ANONYMOUS = "-"

		/** Keeps a Like key from ever colliding with a reply slot. */
		const val PREFIX = "like"
	}
}
