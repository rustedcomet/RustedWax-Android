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

	/**
	 * Sign a comment with the posting key, **for [author] and nobody else**.
	 *
	 * [author] is the account the caller captured when the user tapped, handed
	 * back so the signing boundary can refuse an operation that has outlived
	 * it. See [HiveSnapPort.prepareComment] for why the caller's own account
	 * checks are not sufficient on their own.
	 */
	fun prepareComment(
		operation: TxSerializer.CommentOp,
		author: String,
	): HivePreparationResult

	/**
	 * Send exactly what was signed, **still as [author]**.
	 *
	 * Checked again here because signing and sending are separated by a
	 * network round trip, and an account can change inside it.
	 */
	fun broadcastPrepared(
		prepared: PreparedHiveTransaction,
		author: String,
	): HiveRpc.BroadcastResult
	fun observeTransaction(txId: String, expirationEpochSec: Long): HiveRpc.TransactionEvidence

	/** True/false when the chain could answer, null when it could not be asked. */
	fun contentExists(author: String, permlink: String): Boolean?
}

internal class HiveSnapPort(
	/** Read at signing time, never cached — an account switch must change it. */
	private val loadKey: () -> HiveKey?,
	/**
	 * The username stored **beside** that key, read in the same breath.
	 *
	 * `KeyVault` writes the username and the WIF together and clears them
	 * together, so these two reads describe one account by construction. That
	 * is the property [prepareComment] leans on.
	 */
	private val storedAccount: () -> String?,
	private val broadcaster: HiveBroadcaster = HiveBroadcaster(),
	private val resolver: SnapContainerResolver = SnapContainerResolver(),
	private val rpc: HiveRpc = HiveRpc(),
	/**
	 * The signing seam, paired with [send] below and there for the same reason.
	 *
	 * It wraps the key read *and* the signature, so a test can drive the real
	 * [prepareComment] — real account guards, real orchestration — and get a
	 * usable [PreparedHiveTransaction] back without a private key existing
	 * anywhere in the test sources. The guards run **before** this is invoked,
	 * so overriding it cannot skip them.
	 *
	 * Defaults to exactly what it replaced: read the key, refuse if there is
	 * none, otherwise sign. No caller passes it, so production is unchanged.
	 */
	private val sign: (TxSerializer.CommentOp) -> HivePreparationResult = { operation ->
		when (val key = loadKey()) {
			null -> HivePreparationResult.Failed(
				HiveRpc.BroadcastResult.Rejected("RustedWax couldn't read your posting key."),
			)
			else -> broadcaster.prepareComment(key, operation)
		}
	},
	/**
	 * The one injectable seam, and it exists so the **real** guards below can
	 * be tested without a node.
	 *
	 * The account checks in this class are the last thing standing between a
	 * switched account and a published comment, and a test that re-implements
	 * them in a fake proves only that the fake agrees with itself. Lifting the
	 * single line that actually transmits lets a test drive this class —
	 * genuinely this class — and assert that nothing reached the wire.
	 *
	 * Defaults to exactly what it replaced, so production is unchanged: no
	 * caller passes it.
	 */
	private val send: (PreparedHiveTransaction) -> HiveRpc.BroadcastResult = {
		broadcaster.broadcastPrepared(it)
	},
) : SnapHivePort {
	override fun resolveContainer() = resolver.resolve()

	/**
	 * Sign, having first proved the key still belongs to the author.
	 *
	 * The caller re-checks the signed-in account around this, and that is not
	 * sufficient on its own. One Hive posting key may sit in more than one
	 * account's posting authority, so a comment that says `author: alice`,
	 * signed after the user switched to `bob`, is not necessarily rejected by
	 * the chain — where the same key authorizes both it is **accepted**, and
	 * Alice has published from Bob's session. Neither the signature nor the
	 * chain would object; only this check can.
	 *
	 * Three identities must agree, case-insensitively: the author the operation
	 * names, the author the caller captured, and the username the vault holds
	 * beside the key right now. The last is what cannot be outrun by a
	 * coroutine — whatever the caller believed when it started, nothing is
	 * signed unless the key on disk belongs to the account the comment names.
	 *
	 * The same boundary `HiveSnapLikePort.prepare` enforces for votes.
	 */
	override fun prepareComment(
		operation: TxSerializer.CommentOp,
		author: String,
	): HivePreparationResult {
		accountMismatch(operation.author, author)?.let { return it }
		return sign(operation)
	}

	/**
	 * Send, having proved the account again.
	 *
	 * Signing and sending are separated by at least one round trip, so the
	 * account can change between them. A transaction signed for Alice must not
	 * leave the device after the user has become Bob, even though the bytes
	 * themselves are already fixed — publishing Alice's words from Bob's
	 * session is the thing being prevented, not a malformed signature.
	 */
	override fun broadcastPrepared(
		prepared: PreparedHiveTransaction,
		author: String,
	): HiveRpc.BroadcastResult {
		accountMismatch(author, author)?.let {
			return HiveRpc.BroadcastResult.Rejected(
				"You've switched Hive accounts — this Snap belonged to a different one.",
			)
		}
		return send(prepared)
	}

	/**
	 * Null when the operation's author, the captured author and the vault's
	 * stored username are all the same account.
	 */
	private fun accountMismatch(
		operationAuthor: String,
		captured: String,
	): HivePreparationResult.Failed? {
		if (!operationAuthor.equals(captured, ignoreCase = true)) {
			return refusal("this Snap was built for a different account.")
		}
		val stored = storedAccount()?.takeIf { it.isNotBlank() }
			?: return refusal("There's no saved Hive account to post as.")
		if (!stored.equals(captured, ignoreCase = true)) {
			return refusal("You've switched Hive accounts — this Snap belonged to a different one.")
		}
		return null
	}

	private fun refusal(message: String) =
		HivePreparationResult.Failed(HiveRpc.BroadcastResult.Rejected(message))

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
	 * What staging a comment achieved, **before anything crossed the network**.
	 *
	 * The split exists for one reason: the durable checkpoint and the broadcast
	 * used to be the same call, so the screen could not show a Snap until Hive
	 * had confirmed it — up to fifteen seconds of confirmation polling with a
	 * "Posting…" label over it. The safety rule was never "wait for the chain";
	 * it was **persist the exact signed transaction before sending it**. Once
	 * that record is on disk and reads back identical, the Snap has a permanent
	 * identity, a minted permlink that will never be minted twice, and bytes
	 * that can be rebroadcast or reconciled after a crash. That is the moment it
	 * is safe to show, and [Ready] is that moment.
	 *
	 * Nothing about the rules moved. [stage] performs exactly the checks
	 * [publish] always performed, in the same order, and [deliver] performs
	 * exactly what followed them.
	 */
	sealed interface Staged {

		/**
		 * Signed, persisted, and read back identical. Not sent yet.
		 *
		 * The caller may render this Snap immediately. It may **not** claim the
		 * chain has it — see [SnapPublisher.deliver].
		 */
		data class Ready(val contentId: String) : Staged

		/** Already proven on chain by an earlier attempt. Nothing to send. */
		data class Published(val contentId: String, val txId: String) : Staged

		/** Nothing was staged and nothing exists. The draft must be kept. */
		data class Failed(val message: String) : Staged

		/**
		 * An earlier attempt's outcome is unknown, or stored state is unreadable.
		 * Locked: no new permlink, no new transaction.
		 */
		data class Uncertain(val message: String) : Staged
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

	/**
	 * What the store holds for one event **on one write path**.
	 *
	 * Every entry point that can lead to a signature, a broadcast, a retry or a
	 * state advance reads through this rather than through [PendingSnapStore]
	 * directly, because "is there a record?" is the wrong question. The right
	 * one is "is there a record *of this kind*?", and the difference is not
	 * academic: the two paths build different comments, so a record consumed by
	 * the wrong one would sign a parent the user never chose under an identity
	 * minted for something else.
	 *
	 * The three answers stay distinct for the same reason [PendingSnapRead]'s
	 * do. A mismatched or unreadable record is [Locked] — never [Absent], which
	 * would let it look brand new and mint a second permlink, and never
	 * silently reclassified, which is the failure this exists to prevent.
	 */
	private sealed interface Stored {
		data class Present(val snap: PendingSnap) : Stored
		data object Absent : Stored
		data class Locked(val message: String) : Stored
	}

	/**
	 * Read one record for one kind, failing closed on anything else.
	 *
	 * A record whose stored kind cannot be read never gets here at all:
	 * [PendingSnap.fromJson] refuses it, so the store answers
	 * [PendingSnapRead.Corrupt] and it arrives as [Stored.Locked].
	 */
	private fun stored(account: String, eventId: String, kind: PendingSnapKind): Stored =
		when (val read = store.read(account, eventId)) {
			// Locked. Unreadable state may describe a comment that is already
			// live, so this row gets no new permlink and no transaction.
			is PendingSnapRead.Corrupt -> Stored.Locked(corruptMessage(read.reason))
			PendingSnapRead.Absent -> Stored.Absent
			is PendingSnapRead.Present -> when {
				read.snap.kind != kind -> Stored.Locked(WRONG_KIND_MESSAGE)
				// Three identities, case-insensitively: the account asking, the
				// account the record is filed under, and the author the comment
				// will carry. All three must be one account, and that author
				// must be shaped like a Hive account name.
				//
				// This is what stops an optimistic card and an irreversible
				// comment describing different people. Whatever disagrees, the
				// record is locked rather than repaired: RustedWax cannot know
				// which of the two names has a comment on chain already.
				PendingSnapIntegrity.identityProblem(read.snap, account) != null ->
					Stored.Locked(WRONG_AUTHOR_MESSAGE)
				else -> Stored.Present(read.snap)
			}
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
		kind = PendingSnapKind.ROOT,
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
		kind = PendingSnapKind.REPLY,
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
		kind: PendingSnapKind,
		mintPermlink: (Long) -> String,
		destination: () -> DestinationResult,
	): Outcome = when (val staged = stage(account, eventId, kind, mintPermlink, destination)) {
		// Durably on disk. Sending is the second half, and it is the same second
		// half it always was.
		is Staged.Ready -> deliver(account, eventId, kind)
		is Staged.Published -> Outcome.Published(staged.contentId, staged.txId)
		is Staged.Failed -> Outcome.Failed(staged.message)
		is Staged.Uncertain -> Outcome.Uncertain(staged.message)
	}

	/**
	 * Everything that happens **before** the network boundary, for one comment.
	 *
	 * Lifted out of [publish] verbatim: the corrupt lock, the confirmed-is-final
	 * rule, the reconcile-before-resend rule, the reuse of an unexpired prepared
	 * transaction, the single mint of a permlink, the signature, and the
	 * write-then-prove-the-write. What changed is only that it now *returns*
	 * after the durable checkpoint instead of falling straight into the
	 * broadcast.
	 *
	 * Callers that want the old one-shot behaviour keep it, because [publish]
	 * still composes the two in the same order. Callers that want to show
	 * something the moment it is safe call this and then [deliver].
	 */
	private fun stage(
		account: String,
		eventId: String,
		kind: PendingSnapKind,
		mintPermlink: (Long) -> String,
		destination: () -> DestinationResult,
	): Staged {
		val existing = when (val read = stored(account, eventId, kind)) {
			// Locked, including when the record belongs to the other write
			// path: whatever it is, it is not this publication's to consume.
			is Stored.Locked -> return Staged.Uncertain(read.message)
			is Stored.Present -> read.snap
			Stored.Absent -> null
		}

		when {
			existing == null -> Unit
			// Proven on chain: the final answer for this row or this intent, and
			// never the starting point for another transaction.
			existing.state == PendingSnapState.CONFIRMED ->
				return Staged.Published(existing.contentId, existing.txId)
			existing.isUncertain ->
				return (
					reconcile(account, eventId, kind) ?: Outcome.Uncertain(UNCERTAIN_MESSAGE)
					).staged()
			else -> Unit
		}

		// A prepared-but-unsent transaction that has not expired is still exactly
		// the right bytes, and reusing it keeps the transaction id reconcilable.
		// Already durable, so it is already showable.
		existing?.let {
			if (it.state == PendingSnapState.PREPARED && it.expirationEpochSec > nowEpochSec()) {
				return Staged.Ready(it.contentId)
			}
		}

		val going = when (val d = destination()) {
			is DestinationResult.Ready -> d.destination
			is DestinationResult.Refused -> return Staged.Failed(d.message)
		}

		val permlink = existing?.permlink ?: mintPermlink(nowEpochSec())

		// Resuming: the words the user posted were frozen when they tapped, and
		// they are what gets signed. Rebuilding the body here would let a draft
		// edited in the meantime — or any later change to how a body is
		// composed — silently publish something other than what the card has
		// been showing them since.
		//
		// The two paths freeze from slightly different points, and the
		// difference is not arbitrary:
		//
		//  - a **reply** is frozen from its intent onwards, including when an
		//    expired prepared transaction has to be re-signed. Its caller is
		//    the thread controller, which passes whatever the composer holds
		//    now, so the record is the only copy of what the user actually
		//    sent;
		//  - a **root Snap** freezes at the intent. Past that, its one
		//    user-facing resume — [retryRoot] — hands the frozen body back
		//    through [frozenRootDestination], and its container genuinely
		//    cannot be known until the network is asked.
		val intended = existing?.takeIf { it.state == PendingSnapState.INTENT }
		val frozen = when (kind) {
			PendingSnapKind.REPLY -> existing
			PendingSnapKind.ROOT -> intended
		}
		val body = frozen?.body ?: going.body
		val jsonMetadata = frozen?.jsonMetadata ?: going.jsonMetadata

		// And the same rule for where it is going. See [frozenParent].
		val parent = when (val p = frozenParent(frozen, kind, going)) {
			is ParentResult.Ready -> p.parent
			is ParentResult.Refused -> return Staged.Failed(p.message)
		}

		// And for **who wrote it**. An existing record already carries an
		// author, and that author is what the card has been showing since the
		// tap — so it is what gets signed, rather than whatever the caller
		// passed in this time. The gate above has already proven the two name
		// the same account, so this changes no outcome; what it changes is that
		// the irreversible identity now provably comes from the durable record
		// instead of from a parameter that happened to agree.
		val author = existing?.author ?: account

		val prepared = when (
			val p = hive.prepareComment(
				operation = TxSerializer.CommentOp(
					parentAuthor = parent.author,
					parentPermlink = parent.permlink,
					author = author,
					permlink = permlink,
					title = "",
					body = body,
					jsonMetadata = jsonMetadata,
				),
				// The same frozen name is what the port binds against the
				// vault, so a switch between here and the signature is still
				// refused — by a comparison of two names neither of which was
				// invented at this moment.
				author = author,
			)
		) {
			is HivePreparationResult.Ready -> p.transaction
			is HivePreparationResult.Failed -> return when (val r = p.result) {
				is HiveRpc.BroadcastResult.Rejected -> Staged.Failed(r.message)
				is HiveRpc.BroadcastResult.NetworkFailure -> Staged.Failed(r.message)
				else -> Staged.Failed("couldn't prepare this Snap")
			}
		}

		val now = nowEpochSec()
		val record = PendingSnap(
			account = existing?.account ?: account,
			eventId = eventId,
			author = author,
			permlink = permlink,
			parentAuthor = parent.author,
			parentPermlink = parent.permlink,
			body = body,
			jsonMetadata = jsonMetadata,
			signedTransactionJson = prepared.signedTransactionJson,
			txId = prepared.txId,
			expirationEpochSec = prepared.expirationEpochSec,
			state = PendingSnapState.PREPARED,
			createdAtEpochSec = existing?.createdAtEpochSec ?: now,
			updatedAtEpochSec = now,
			kind = kind,
		)

		// The network boundary is gated on durable state, not on hope. A Snap sent
		// without a persisted record could never be reconciled afterwards — and a
		// Snap *shown* without one could never be recovered after a crash, which
		// is why this same checkpoint is what the optimistic card waits for.
		if (!persist(record)) {
			return Staged.Failed(
				"RustedWax couldn't save this Snap safely, so it didn't post. " +
					"Your draft is still here.",
			)
		}

		return Staged.Ready(record.contentId)
	}

	/** Where a comment will hang on the chain. */
	private data class Parent(val author: String, val permlink: String)

	private sealed interface ParentResult {
		data class Ready(val parent: Parent) : ParentResult
		data class Refused(val message: String) : ParentResult
	}

	/**
	 * The parent a resumed comment is signed against.
	 *
	 * **A reply's parent is frozen at the intent.** It is the one thing about a
	 * reply the user actually chose — they tapped Reply on a specific comment,
	 * and the reply appeared under it the instant the intent was committed.
	 * Everything signed afterwards has to match what they were shown, so once a
	 * reply intent exists its stored `parentAuthor`/`parentPermlink` are
	 * authoritative and the freshly computed destination cannot replace them.
	 * Recomputing silently would let a later, differently-parented call sign a
	 * comment that lands somewhere else entirely under the identity the user
	 * has already seen in the thread.
	 *
	 * Belt and braces, in this order:
	 *
	 *  - a reply intent with a blank stored parent is **refused**. It describes
	 *    a reply to nothing, and guessing a parent for it is precisely the
	 *    silent replacement this exists to prevent;
	 *  - a reply intent whose stored parent disagrees with the destination just
	 *    computed is **refused** rather than resolved either way. The event id
	 *    carries the target, so the two can only disagree if something is
	 *    wrong, and neither answer is safe to sign;
	 *  - otherwise the stored parent is used, byte for byte.
	 *
	 * A **root** intent is the opposite case and is left alone: its container
	 * genuinely is not known until the network is asked, so [intendRoot] stores
	 * an empty parent on purpose and the fresh destination fills it. A root
	 * intent that somehow carries a parent was not written by [intendRoot], so
	 * it is refused rather than trusted.
	 */
	private fun frozenParent(
		record: PendingSnap?,
		kind: PendingSnapKind,
		going: Destination,
	): ParentResult {
		val fresh = Parent(going.parentAuthor, going.parentPermlink)
		if (record == null) return ParentResult.Ready(fresh)

		return when (kind) {
			PendingSnapKind.REPLY -> {
				val frozen = Parent(record.parentAuthor, record.parentPermlink)
				when {
					frozen.author.isBlank() || frozen.permlink.isBlank() ->
						ParentResult.Refused(FROZEN_PARENT_UNREADABLE)
					frozen != fresh -> ParentResult.Refused(FROZEN_PARENT_MOVED)
					else -> ParentResult.Ready(frozen)
				}
			}
			// The container is the one thing a root Snap cannot know locally,
			// so an intent stores none and this fills it in.
			PendingSnapKind.ROOT ->
				if (record.parentAuthor.isBlank() && record.parentPermlink.isBlank()) {
					ParentResult.Ready(fresh)
				} else {
					ParentResult.Refused(FROZEN_PARENT_UNREADABLE)
				}
		}
	}

	/**
	 * One durably staged Snap, as a reopened app finds it.
	 *
	 * [interrupted] is the distinction the screen has to make. A row that was
	 * intended and never built reached no node at all, so it is *not* on Hive
	 * and never will be unless the user asks again — it needs a retry, not a
	 * spinner. Every other staged state has a real transaction behind it and
	 * may already be on chain, so it is reconciled rather than re-offered.
	 */
	data class StagedSnap(
		val eventId: String,
		val contentId: String,
		val interrupted: Boolean,
	)

	/**
	 * Whether this row is an intent nobody finished — a pure store read.
	 *
	 * The question a caller asks after a failure to decide whether the Snap is
	 * lost or merely unfinished.
	 */
	fun isInterrupted(account: String, eventId: String, kind: PendingSnapKind): Boolean =
		(stored(account, eventId, kind) as? Stored.Present)
			?.snap?.state == PendingSnapState.INTENT

	/**
	 * Finish a root Snap the user has explicitly asked to retry.
	 *
	 * The **same** publication path, resumed — not a second one. Everything
	 * that identifies the Snap is read back off the durable record rather than
	 * rebuilt: the permlink [stage] reuses, and the body and metadata this
	 * hands it. Nothing about the Snap's identity can change between the tap
	 * that intended it and the retry that completes it, which is what makes a
	 * retry safe to offer at all.
	 *
	 * Each stored state is answered by the rule that already governs it:
	 *
	 *  - **intent** — the only case that publishes. Resolve the container,
	 *    sign, persist, then send, exactly as a first attempt would;
	 *  - **prepared** — already signed, so only the send is owed, and
	 *    [deliver] sends the bytes already on disk;
	 *  - **uncertain** — a transaction crossed the boundary. Read, never
	 *    resend;
	 *  - **confirmed** — done, and answered from disk;
	 *  - **failed / corrupt / absent** — nothing to retry, and in particular
	 *    nothing to rebuild.
	 */
	fun retryRoot(account: String, eventId: String): Outcome {
		val record = when (val read = stored(account, eventId, PendingSnapKind.ROOT)) {
			// Includes a record that is not a root Snap at all. A retry is a
			// signature and a broadcast, so it is exactly where a reply record
			// must never be consumed.
			is Stored.Locked -> return Outcome.Uncertain(read.message)
			is Stored.Present -> read.snap
			Stored.Absent -> return Outcome.Failed(
				"RustedWax couldn't find this Snap to post.",
			)
		}

		return when {
			record.state == PendingSnapState.CONFIRMED ->
				Outcome.Published(record.contentId, record.txId)
			// Crossed the network boundary already. Reading is the only move.
			record.isUncertain ->
				reconcile(account, eventId, PendingSnapKind.ROOT)
					?: Outcome.Uncertain(UNCERTAIN_MESSAGE)
			record.state == PendingSnapState.FAILED ->
				Outcome.Failed(record.lastError ?: "this Snap never reached Hive")
			// Signed but unsent. What is owed depends on whether those bytes are
			// still usable.
			//
			// A Hive transaction expires sixty seconds after the chain head it
			// was built on, and a record can sit in this state far longer than
			// that — the process died between persisting it and sending it. The
			// old bytes are then guaranteed to be refused, so broadcasting them
			// would be a doomed write followed by a reconciliation that marks a
			// Snap failed which was never actually attempted.
			//
			// So an expired transaction is **rebuilt rather than sent**, and
			// `stage` does it: it reuses this record's permlink, takes the body
			// and metadata from the record via [frozenRootDestination], signs
			// against a fresh chain head, and persists the new exact
			// transaction before anything is broadcast. The publication
			// identity does not move; only the envelope around it is renewed.
			//
			// Allowed only because Retry is a fresh explicit user action. There
			// is no path here from an *uncertain* record — `isUncertain` is
			// matched above, so a transaction that may already be on chain is
			// reconciled and never rebuilt.
			record.state == PendingSnapState.PREPARED ->
				if (record.expirationEpochSec > nowEpochSec()) {
					deliver(account, eventId, PendingSnapKind.ROOT)
				} else {
					publish(
						account = account,
						eventId = eventId,
						kind = PendingSnapKind.ROOT,
						mintPermlink = newPermlink,
						destination = { frozenRootDestination(account, eventId) },
					)
				}
			// The interrupted case, and the whole point of this entry point.
			record.state == PendingSnapState.INTENT -> publish(
				account = account,
				eventId = eventId,
				kind = PendingSnapKind.ROOT,
				mintPermlink = newPermlink,
				destination = { frozenRootDestination(account, eventId) },
			)
			else -> Outcome.Uncertain(UNCERTAIN_MESSAGE)
		}
	}

	/**
	 * Where a *resumed* root Snap is going, and what it will say.
	 *
	 * The words come off the durable record, never from whatever the composer
	 * happens to hold now. Those are the words the card has been showing since
	 * the tap, and re-deriving them would let a draft edited in between — or a
	 * later change to how a body is composed — publish something the user was
	 * never shown.
	 *
	 * Only the container is asked of the network, because only the container
	 * cannot be known locally.
	 */
	private fun frozenRootDestination(account: String, eventId: String): DestinationResult {
		val record = (stored(account, eventId, PendingSnapKind.ROOT) as? Stored.Present)?.snap
			?: return DestinationResult.Refused("RustedWax couldn't read this Snap's saved text.")
		if (record.body.isBlank()) {
			return DestinationResult.Refused("RustedWax couldn't read this Snap's saved text.")
		}
		return when (val c = hive.resolveContainer()) {
			is SnapContainerResolver.Result.Resolved -> DestinationResult.Ready(
				Destination(
					parentAuthor = c.container.author,
					parentPermlink = c.container.permlink,
					body = record.body,
					jsonMetadata = record.jsonMetadata,
				),
			)
			is SnapContainerResolver.Result.Unavailable -> DestinationResult.Refused(c.reason)
		}
	}

	/**
	 * Freeze the identity of one root Snap on disk, **touching no network at
	 * all**.
	 *
	 * This is the whole of the visible path. The permlink is minted from the
	 * clock and a random suffix; the body and metadata come from
	 * [SnapPayloadBuilder]; neither needs the chain. So the only thing between
	 * the tap and the card is one local write — and because that write commits
	 * and reads back before this returns, the Snap it describes has an identity
	 * that survives a crash before it is ever shown.
	 *
	 * Deliberately **not** a publication. Nothing here builds a transaction,
	 * resolves a container or contacts a node, and a record left in
	 * [PendingSnapState.INTENT] has nothing that could be broadcast even by
	 * mistake. Finishing the job is [publishRoot]'s, which resumes from exactly
	 * this record.
	 *
	 * Root Snaps only. Replies do not use it, and their path is unchanged.
	 */
	fun intendRoot(
		account: String,
		eventId: String,
		media: SnapMedia,
		userText: String,
	): Staged {
		val existing = when (val read = stored(account, eventId, PendingSnapKind.ROOT)) {
			// Unreadable state may describe a comment that is already live, and
			// a record of the other kind is not this path's to reuse.
			is Stored.Locked -> return Staged.Uncertain(read.message)
			is Stored.Present -> read.snap
			Stored.Absent -> null
		}

		when {
			existing == null -> Unit
			// Proven on chain: final, and never the start of anything new.
			existing.state == PendingSnapState.CONFIRMED ->
				return Staged.Published(existing.contentId, existing.txId)
			// Already crossed the network boundary. Reading is the only safe
			// move, and minting a fresh intent over it would be the worst one.
			existing.isUncertain -> return Staged.Uncertain(UNCERTAIN_MESSAGE)
			// Already has an identity — intended, or already signed. Reuse it
			// exactly; this is the single most important rule in the path.
			existing.state == PendingSnapState.INTENT ||
				existing.state == PendingSnapState.PREPARED ->
				return Staged.Ready(existing.contentId)
			else -> Unit
		}

		val payload = SnapPayloadBuilder.build(userText, media)
		SnapPayloadBuilder.problem(payload, media)?.let { return Staged.Failed(it) }

		val now = nowEpochSec()
		val record = PendingSnap(
			account = account,
			eventId = eventId,
			author = account,
			// Minted once, here, before anything slow can fail around it.
			permlink = existing?.permlink ?: newPermlink(now),
			// Unknown until the container is resolved, which needs the network.
			// Empty is honest; the background fills them in.
			parentAuthor = "",
			parentPermlink = "",
			body = payload.body,
			jsonMetadata = payload.jsonMetadata,
			// No transaction exists yet, and that is what makes this state safe.
			signedTransactionJson = "",
			txId = "",
			expirationEpochSec = 0L,
			state = PendingSnapState.INTENT,
			createdAtEpochSec = existing?.createdAtEpochSec ?: now,
			updatedAtEpochSec = now,
			kind = PendingSnapKind.ROOT,
		)

		// The card is gated on durable state, exactly as the broadcast is. A
		// Snap shown without a committed record is one a crash could lose.
		if (!persist(record)) {
			return Staged.Failed(
				"RustedWax couldn't save this Snap safely, so it didn't post. " +
					"Your draft is still here.",
			)
		}
		return Staged.Ready(record.contentId)
	}

	/**
	 * Freeze the identity of one reply on disk, **touching no network at all**.
	 *
	 * The reply counterpart of [intendRoot], and simpler: a reply's parent is
	 * the comment the user tapped Reply on, which is already an identity the
	 * chain gave us, so there is no container to resolve. The permlink is
	 * minted from the clock, the body is the typed text, and neither needs
	 * Hive. One local write stands between the tap and the reply appearing.
	 *
	 * Not a publication. A record left in [PendingSnapState.INTENT] carries no
	 * transaction, so there is nothing here that could be broadcast even by
	 * mistake; [publishReply] resumes from exactly this record and does the
	 * rest. The [intentId] keeps a reusable reply slot from becoming a
	 * repeatable one — see [SnapReplyIntent].
	 */
	fun intendReply(
		account: String,
		target: SnapReplyTarget,
		intentId: String,
		userText: String,
	): Staged {
		val eventId = SnapReplyKey.of(target, intentId)
		val existing = when (val read = stored(account, eventId, PendingSnapKind.REPLY)) {
			is Stored.Locked -> return Staged.Uncertain(read.message)
			is Stored.Present -> read.snap
			Stored.Absent -> null
		}

		when {
			existing == null -> Unit
			existing.state == PendingSnapState.CONFIRMED ->
				return Staged.Published(existing.contentId, existing.txId)
			// Already across the network boundary. Reading is the only safe
			// move, and minting a fresh intent over it would be the worst one.
			existing.isUncertain -> return Staged.Uncertain(UNCERTAIN_MESSAGE)
			existing.state == PendingSnapState.INTENT ||
				existing.state == PendingSnapState.PREPARED ->
				return Staged.Ready(existing.contentId)
			else -> Unit
		}

		val payload = SnapReplyPayloadBuilder.build(userText)
		SnapReplyPayloadBuilder.problem(payload)?.let { return Staged.Failed(it) }

		val now = nowEpochSec()
		val record = PendingSnap(
			account = account,
			eventId = eventId,
			author = account,
			permlink = existing?.permlink ?: newReplyPermlink(now),
			// Known without asking anyone: the comment the user replied to.
			parentAuthor = target.author,
			parentPermlink = target.permlink,
			body = payload.body,
			jsonMetadata = payload.jsonMetadata,
			signedTransactionJson = "",
			txId = "",
			expirationEpochSec = 0L,
			state = PendingSnapState.INTENT,
			createdAtEpochSec = existing?.createdAtEpochSec ?: now,
			updatedAtEpochSec = now,
			kind = PendingSnapKind.REPLY,
		)

		if (!persist(record)) {
			return Staged.Failed(
				"RustedWax couldn't save this reply safely, so it didn't send. " +
					"Your draft is still here.",
			)
		}
		return Staged.Ready(record.contentId)
	}

	/**
	 * Replies this device has committed to but not yet seen on chain, shaped
	 * as thread rows.
	 *
	 * What lets a reply appear the instant it is written. Every field comes off
	 * the durable record — the same permlink and the same body that will be
	 * signed — so the row on screen is the reply that is being published, not a
	 * hopeful copy of it.
	 *
	 * A pure store read: no RPC, no mutation, nothing sent. Rows whose outcome
	 * is [PendingSnapState.FAILED] are left out, because those are proven
	 * absent; everything else is included, confirmed rows too. A confirmed
	 * reply the chain has already returned is de-duplicated by
	 * [SnapThreadBuilder] against its own `author/permlink`, so including it
	 * costs nothing and covers the seconds where hivemind has not caught up.
	 */
	fun stagedReplyRows(account: String): List<SnapReply> =
		store.all(account)
			.filter { it.kind == PendingSnapKind.REPLY }
			// A row whose author is not this account is not this account's
			// reply, and a thread drawing it would be showing the user a
			// comment under somebody else's name as if it were theirs.
			.filter { PendingSnapIntegrity.identityProblem(it, account) == null }
			.filter { it.state != PendingSnapState.FAILED }
			.sortedBy { it.createdAtEpochSec }
			.map {
				SnapReply(
					author = it.author,
					permlink = it.permlink,
					parentAuthor = it.parentAuthor,
					parentPermlink = it.parentPermlink,
					// A reply's stored body *is* the typed text — no generated
					// tail to subtract, unlike a root Snap.
					body = it.body,
					createdAtEpochSec = it.createdAtEpochSec,
				)
			}

	/**
	 * Throw away a reply the user has explicitly discarded, and only while it
	 * is still an intent.
	 *
	 * The counterpart of [intendReply], and the only thing that may delete one.
	 * An intent carries no transaction and reached no node, so it is certainly
	 * not on Hive and deleting it can orphan nothing — which is exactly why the
	 * state is re-read here rather than trusted from the caller. A record that
	 * has since been prepared or sent is left completely alone and answers
	 * false: those rows are decided by reconciliation, never by a discard. A
	 * confirmed or failed row answers true without being touched — there is
	 * nothing there to abandon.
	 *
	 * Without this, discarding the draft of an unfinished reply would leave its
	 * record behind, and [stagedReplyRows] would keep drawing a comment that
	 * exists nowhere but this device and that nothing on screen could finish.
	 */
	fun abandonIntendedReply(account: String, target: SnapReplyTarget, intentId: String): Boolean {
		val eventId = SnapReplyKey.of(target, intentId)
		val record = when (val read = stored(account, eventId, PendingSnapKind.REPLY)) {
			// Unreadable state, or a record of the other kind, may describe a
			// comment that is already live. Neither is this path's to delete.
			is Stored.Locked -> return false
			is Stored.Present -> read.snap
			Stored.Absent -> return true
		}
		return when (record.state) {
			// The one deletable state, and the only one this exists for.
			PendingSnapState.INTENT -> {
				store.clear(account, eventId)
				store.read(account, eventId) is PendingSnapRead.Absent
			}
			// Nothing to abandon. A confirmed reply is a real comment and keeps
			// its record; a failed one is proven absent and is not drawn by
			// [stagedReplyRows] either way. Neither blocks discarding the draft.
			PendingSnapState.CONFIRMED, PendingSnapState.FAILED -> true
			// Signed, or across the network boundary. These are drawn, they may
			// already be on Hive, and reconciliation is the only thing allowed
			// to decide them — so the discard is refused rather than silently
			// leaving a row nothing can finish.
			else -> false
		}
	}

	/** Whether this reply is an intent nobody finished. A pure store read. */
	fun isReplyInterrupted(account: String, target: SnapReplyTarget, intentId: String): Boolean =
		isInterrupted(account, SnapReplyKey.of(target, intentId), PendingSnapKind.REPLY)

	/**
	 * Stage one root Snap and stop at the durable checkpoint.
	 *
	 * The caller renders on [Staged.Ready] and then calls [deliver] to finish.
	 * Splitting the two is what lets the card appear without the chain being
	 * asked first; it changes nothing about what is written or in what order.
	 */
	fun stageRoot(
		account: String,
		eventId: String,
		media: SnapMedia,
		userText: String,
	): Staged = stage(
		account = account,
		eventId = eventId,
		kind = PendingSnapKind.ROOT,
		mintPermlink = newPermlink,
		destination = { rootDestination(media, userText) },
	)

	/**
	 * Send the transaction staging already persisted, and settle it.
	 *
	 * The second half of the one publication path — not a second path. It
	 * broadcasts **exactly** the bytes on disk and never builds any, so it
	 * cannot mint a permlink and cannot create a transaction the store has not
	 * already committed to.
	 *
	 * Safe to call more than once for one row, and safe to call after a
	 * restart: a confirmed record answers from disk, an uncertain one is
	 * *reconciled* rather than resent, and a record whose prepared transaction
	 * has expired is failed rather than rebuilt here. There is deliberately no
	 * branch in this function that reaches [stage].
	 */
	fun deliver(account: String, eventId: String, kind: PendingSnapKind): Outcome {
		val record = when (val read = stored(account, eventId, kind)) {
			// The last store read before the wire, and the last place a record
			// of the wrong kind can be turned away. Locked sends nothing.
			is Stored.Locked -> return Outcome.Uncertain(read.message)
			is Stored.Present -> read.snap
			// Nothing staged. Never a reason to build something here.
			Stored.Absent -> return Outcome.Failed(
				"RustedWax couldn't find this Snap to send.",
			)
		}

		return when {
			record.state == PendingSnapState.CONFIRMED ->
				Outcome.Published(record.contentId, record.txId)
			record.state == PendingSnapState.FAILED ->
				Outcome.Failed(record.lastError ?: "this Snap never reached Hive")
			// Crossed the boundary already: read, never resend.
			record.isUncertain ->
				reconcile(account, eventId, kind) ?: Outcome.Uncertain(UNCERTAIN_MESSAGE)
			// The boundary, stated in code: an intent carries no transaction, so
			// there is nothing here that could be sent. It has to go through
			// `stage` and be persisted as PREPARED first, and this branch exists
			// so that "intent straight to broadcast" is impossible rather than
			// merely unreachable.
			record.state == PendingSnapState.INTENT -> Outcome.Failed(
				"this Snap hasn't been prepared yet",
			)
			record.state == PendingSnapState.PREPARED -> broadcast(record)
			else -> Outcome.Uncertain(UNCERTAIN_MESSAGE)
		}
	}

	private fun Outcome.staged(): Staged = when (this) {
		is Outcome.Published -> Staged.Published(contentId, txId)
		is Outcome.Failed -> Staged.Failed(message)
		is Outcome.Uncertain -> Staged.Uncertain(message)
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
		val result = runCatching { hive.broadcastPrepared(prepared, sending.author) }.getOrElse {
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
		return reconcile(record.account, record.eventId, record.kind)
			?: Outcome.Uncertain(message)
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
	fun reconcile(account: String, eventId: String, kind: PendingSnapKind): Outcome? {
		val record = when (val read = stored(account, eventId, kind)) {
			// A wrong-kind record is locked here too. Reconciliation writes
			// state — it can mark a row CONFIRMED or FAILED — and those states
			// are what later decide whether anything may be sent.
			is Stored.Locked -> return Outcome.Uncertain(read.message)
			is Stored.Present -> read.snap
			Stored.Absent -> return null
		}
		if (record.state == PendingSnapState.CONFIRMED) {
			return Outcome.Published(record.contentId, record.txId)
		}
		if (record.state == PendingSnapState.FAILED) return null
		// An intent has no transaction and never reached a node, so there is
		// nothing to ask the chain about. Answering "nothing to reconcile"
		// leaves the row exactly as it is rather than inventing evidence from an
		// empty transaction id.
		if (record.state == PendingSnapState.INTENT) return null

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
	fun isUnresolved(account: String, eventId: String, kind: PendingSnapKind): Boolean =
		when (val read = stored(account, eventId, kind)) {
			// A record of the wrong kind answers true for the same reason a
			// corrupt one does: whatever it is, it is not proof of absence.
			is Stored.Locked -> true
			is Stored.Present -> read.snap.isUncertain
			Stored.Absent -> false
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
	fun publishedBody(account: String, eventId: String, kind: PendingSnapKind): String? =
		(stored(account, eventId, kind) as? Stored.Present)?.snap
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
			// Identity first: a record naming another author is never drawn as
			// this user's Snap, whatever state it claims.
			.filter { PendingSnapIntegrity.identityProblem(it, account) == null }
			// Root Snaps only. A reply lives in a thread, under a key of a
			// different shape, and handing one to the History screen would file
			// it under a card that does not exist.
			.filter { it.kind == PendingSnapKind.ROOT }
			.sortedBy { it.eventId }
			.map { it.eventId to Outcome.Published(it.contentId, it.txId) }

	/**
	 * Every Snap this account **staged and has not settled**, from disk alone.
	 *
	 * The optimistic half of [restoreLocal], and it exists for the same reason:
	 * a Snap the device has already committed to should be on screen before any
	 * network call, because the record proving it is already on disk. After an
	 * Activity is destroyed and rebuilt — or the process dies between the
	 * durable checkpoint and the broadcast — this is what puts the card back.
	 *
	 * Exactly as narrow as [restoreLocal]:
	 *
	 *  - **no RPC.** There is no call to [hive] on this path at all;
	 *  - **no mutation.** Nothing is persisted, cleared or advanced, so it
	 *    cannot move the duplicate-safety state machine — and in particular it
	 *    cannot mint a permlink or build a transaction. Reconciliation remains
	 *    the only thing that decides an unresolved row;
	 *  - **no send.** Returning a row here does not deliver it. A staged Snap
	 *    that was never broadcast waits for a fresh explicit tap, which reuses
	 *    the transaction already on disk rather than making another;
	 *  - **staged only.** Confirmed rows belong to [restoreLocal], and failed
	 *    and corrupt rows are not shown at all;
	 *  - **ordered**, because `store.all` reflects `SharedPreferences.getAll()`'s
	 *    arbitrary hash order.
	 */
	fun restoreStaged(account: String): List<StagedSnap> =
		stagedRows(account, PendingSnapKind.ROOT)

	/**
	 * The same read, for replies.
	 *
	 * Separate because the two go to different screens under different keys:
	 * a reply's `eventId` carries the comment it answers, and only the thread
	 * knows what to do with that. Identical guarantees — no RPC, no mutation,
	 * no send — and it is what puts an unfinished reply back in its thread,
	 * marked as needing another tap rather than quietly drawn as if it were on
	 * Hive.
	 */
	fun restoreStagedReplies(account: String): List<StagedSnap> =
		stagedRows(account, PendingSnapKind.REPLY)

	private fun stagedRows(account: String, kind: PendingSnapKind): List<StagedSnap> =
		store.all(account)
			.filter { it.kind == kind }
			.filter { PendingSnapIntegrity.identityProblem(it, account) == null }
			.filter { it.state in STAGED_STATES }
			.sortedBy { it.eventId }
			.map {
				StagedSnap(
					eventId = it.eventId,
					contentId = it.contentId,
					// An intent was never built, so it was never sent, so it is
					// certainly not on Hive. Every other staged state has a
					// transaction behind it and may well be.
					interrupted = it.state == PendingSnapState.INTENT,
				)
			}

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
	fun restore(account: String, kind: PendingSnapKind? = null): List<Pair<String, Outcome>> {
		val corrupt = store.corruptEventIds(account)
			// A corrupt row cannot say what kind it is — its stored state is
			// exactly what could not be read. Its `eventId` still can: a reply's
			// carries the comment it answers, and nothing else does. Routing on
			// that keeps a locked reply in its thread and a locked Snap on its
			// card, and either way the row stays locked.
			.filter { kind == null || isReplyEvent(it) == (kind == PendingSnapKind.REPLY) }
			.map { eventId ->
				eventId to Outcome.Uncertain(corruptMessage("stored Snap state could not be read"))
			}
		val readable = store.all(account).filter {
			kind == null || it.kind == kind
		}.mapNotNull { record ->
			// Surfaced, never acted on. A record whose identity disagrees with
			// itself is attention-worthy — the user may have a comment on chain
			// under it — but it is not reconciled, because reconciliation
			// writes state that later decides whether anything may be sent.
			if (PendingSnapIntegrity.identityProblem(record, account) != null) {
				return@mapNotNull record.eventId to
					Outcome.Uncertain(corruptMessage("stored Snap identity could not be trusted"))
			}
			when (record.state) {
				PendingSnapState.CONFIRMED ->
					record.eventId to Outcome.Published(record.contentId, record.txId)
				PendingSnapState.FAILED -> null
				else -> reconcile(account, record.eventId, record.kind)
					?.let { record.eventId to it }
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

	private fun isReplyEvent(eventId: String): Boolean = SnapReplyKey.slotOf(eventId) != null

	private fun corruptMessage(reason: String) =
		"RustedWax can't read this Snap's saved state ($reason), so it won't post " +
			"again in case it already did."

	private companion object {
		/**
		 * Durably committed, outcome not yet decided — the rows a reopened app
		 * may draw optimistically. Mirrors `PostedSnaps.STAGED_STATES`; a test
		 * pins that the two agree.
		 */
		val STAGED_STATES = setOf(
			PendingSnapState.INTENT,
			PendingSnapState.PREPARED,
			PendingSnapState.BROADCASTING,
			PendingSnapState.ACCEPTED_UNCONFIRMED,
			PendingSnapState.UNRESOLVED,
		)

		/**
		 * Said when a stored record belongs to the other write path.
		 *
		 * Worded like the corrupt lock because it means the same thing to the
		 * user: RustedWax will not act on this row, and it will not try again
		 * on its own.
		 */
		/**
		 * Said when a record's account, author and caller do not name one
		 * account — or when the stored author is not a usable Hive name.
		 */
		const val WRONG_AUTHOR_MESSAGE =
			"RustedWax can't tell who this post belongs to, so it won't post it " +
				"in case it already did."

		const val WRONG_KIND_MESSAGE =
			"RustedWax found the wrong kind of saved post here, so it won't post " +
				"it in case it already did."

		const val FROZEN_PARENT_UNREADABLE =
			"RustedWax couldn't read which comment this reply belongs to, so it " +
				"didn't send. Your draft is still here."

		const val FROZEN_PARENT_MOVED =
			"This reply was written to a different comment, so RustedWax didn't " +
				"send it. Your draft is still here."

		const val UNCERTAIN_MESSAGE =
			"RustedWax couldn't confirm whether this Snap posted. " +
				"It won't post again until it knows."
	}
}
