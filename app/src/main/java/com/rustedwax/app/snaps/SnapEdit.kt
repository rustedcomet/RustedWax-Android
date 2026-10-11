package com.rustedwax.app.snaps

import com.rustedwax.app.ui.snaps.SnapText
import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.TxSerializer

/**
 * One comment exactly as the chain holds it now — every field an edit has to
 * carry forward unchanged.
 *
 * Read fresh for every edit rather than taken from a thread row or a pending
 * record: an edit is a whole new `comment` operation, and Hive replaces the
 * object's body, title and metadata with whatever that operation says. So the
 * parts the user is *not* changing have to come from the object itself, as it
 * is at the moment of signing.
 */
data class SnapChainComment(
	val author: String,
	val permlink: String,
	val parentAuthor: String,
	val parentPermlink: String,
	val title: String,
	val body: String,
	val jsonMetadata: String,
	/**
	 * Head block time of the current node this was read from, when known — the
	 * chain point the words describe (Issue #56). Null for a read that cannot say.
	 */
	val headEpochSec: Long? = null,
)

/** Which shape of body is being edited. The two are not built the same way. */
enum class SnapEditKind {
	/** A History Snap: the user's text, then RustedWax's frozen generated tail. */
	ROOT,

	/** A reply: the user's text and nothing else. */
	REPLY,
}

/**
 * Edits the signed-in author's own Snap or reply **in place**.
 *
 * On Hive an edit is not a separate operation. It is the same `comment`
 * operation that created the object, broadcast again under the same
 * `author/permlink` — the chain sees the existing object and replaces its body
 * rather than creating anything. That is the whole design:
 *
 *  - **the identity is never minted.** Author and permlink are the ones the
 *    chain returned for the object being edited, so there is no second object
 *    an edit could create. And because the object is read first and an edit is
 *    refused when it is absent, a comment operation from this class can never
 *    be the one that brings an object into existence;
 *  - **the parent is never chosen.** `parent_author`/`parent_permlink` are
 *    carried from the chain object byte for byte. Hive refuses an edit that
 *    moves a comment anyway; carrying them means RustedWax never asks it to;
 *  - **the metadata is never rebuilt.** `json_metadata` and `title` are carried
 *    verbatim, so whatever `app`, `tags` or anything else the original carried
 *    — and whatever scrobble.life reads out of it — is exactly what it was;
 *  - **a root Snap keeps its media anchor.** Only the user's words are
 *    replaced. The generated tail after them — the canonical `youtu.be` link
 *    and the hashtag line — is cut from the current chain body and re-appended
 *    exactly, so the link that associates the Snap with its media page cannot
 *    change, even if [SnapPayloadBuilder] has changed since it was posted.
 *
 * An ambiguous broadcast keeps its transaction in memory. Retry reconciles
 * that exact attempt without signing or sending again; another edit is allowed
 * only after inclusion, proven irreversible absence, or proof that it can no
 * longer be included read against the comment as it is now. This also keeps a late
 * edit from undoing a newer edit or recreating a deleted comment.
 *
 * The posting key is never held here. Signing and sending go through the same
 * [SnapHivePort] every Snap and reply already uses, with the same three-way
 * account binding at both steps.
 */
class SnapEditor(
	private val hive: SnapHivePort,
	/**
	 * Where this kind's confirmed records live, so a successful edit can keep
	 * the locally stored body in step with the chain. Null skips that step,
	 * which costs nothing but the offline fallback showing the older text.
	 */
	private val store: PendingSnapStore? = null,
	/** Shared with every editor and deleter in the process; see [SnapWriteGuards]. */
	private val guards: SnapWriteGuards = SnapWriteGuards(),
) {

	sealed interface Outcome {
		/** On chain. [userText] is what the screen should now show. */
		data class Edited(
			val contentId: String,
			val userText: String,
			/** Null when nothing had to be sent because nothing changed. */
			val txId: String?,
		) : Outcome

		/** Nothing changed on chain, as far as RustedWax can prove. */
		data class Failed(val message: String) : Outcome

		/**
		 * The edit was sent and could not be proven either way.
		 *
		 * The previous text stays. Retry checks the retained transaction;
		 * it never signs or broadcasts a replacement while this is unresolved.
		 */
		data class Uncertain(val message: String) : Outcome
	}

	fun edit(
		account: String,
		target: SnapReplyTarget,
		kind: SnapEditKind,
		newText: String,
	): Outcome {
		if (account.isBlank()) return Outcome.Failed("Sign in to your Hive account to edit.")
		// The product rule, stated where the signature is: only the author edits.
		// The UI only offers Edit on the viewer's own comments; this is what makes
		// that more than a matter of which button was drawn.
		if (!target.author.equals(account, ignoreCase = true)) {
			return Outcome.Failed(NOT_AUTHOR)
		}
		textProblem(newText)?.let { return Outcome.Failed(it) }
		// One Edit or Delete per comment at a time, across every editor and
		// deleter in the process — including ones built by an earlier Activity.
		when (val held = guards.current(account, target)) {
			// The edit already sent is settled, never repeated or replaced.
			is SnapWriteGuards.EditAttempt ->
				return if (held.kind == kind) settleUnproven(account, target, held) else Outcome.Failed(BUSY)
			is SnapWriteGuards.DeleteAttempt -> return Outcome.Failed(DELETE_UNSETTLED)
			is SnapWriteGuards.Reserved -> return Outcome.Failed(BUSY)
			is SnapWriteGuards.Locked -> return Outcome.Failed(LOCKED)
			null -> Unit
		}
		val reservation = guards.reserve(account, target, SnapWriteGuards.Operation.EDIT)
			?: return Outcome.Failed(BUSY)
		var attached = false
		try {
			return signAndSend(account, target, kind, newText, reservation.id) { attached = true }
		} finally {
			if (!attached) guards.release(account, target, reservation.id)
		}
	}

	/** The edit itself, under this caller's reservation. [onAttached] marks the moment it may be sent. */
	private fun signAndSend(
		account: String,
		target: SnapReplyTarget,
		kind: SnapEditKind,
		newText: String,
		reservationId: Long,
		onAttached: () -> Unit,
	): Outcome {
		val current = runCatching { hive.readComment(target.author, target.permlink) }.getOrNull()
			?: return Outcome.Failed(
				"RustedWax couldn't read this from Hive right now, so nothing was changed.",
			)
		// The chain must be describing the object that was asked for, and it
		// must be a child comment: a blank parent is a top-level post, which is
		// not something RustedWax ever wrote and not something it edits.
		if (!current.author.equals(target.author, ignoreCase = true) ||
			current.permlink != target.permlink ||
			!current.author.equals(account, ignoreCase = true)
		) {
			return Outcome.Failed(NOT_AUTHOR)
		}
		if (current.parentAuthor.isBlank() || current.parentPermlink.isBlank()) {
			return Outcome.Failed("RustedWax can only edit Snaps and replies.")
		}

		val body = when (kind) {
			SnapEditKind.ROOT -> {
				val oldText = PostedSnapBody.userText(current.body)
					?: return Outcome.Failed(
						"This Snap isn't in the format RustedWax writes, so it can't be edited here.",
					)
				// Everything after the user's words, exactly as it is on chain.
				newText + current.body.substring(oldText.length)
			}
			SnapEditKind.REPLY -> newText
		}

		// Nothing to say that the chain does not already say. Not a write.
		if (body == current.body) {
			rememberBody(account, target, kind, body)
			return Outcome.Edited(target.contentId, newText, txId = null)
		}

		val operation = TxSerializer.CommentOp(
			parentAuthor = current.parentAuthor,
			parentPermlink = current.parentPermlink,
			// The chain's own spelling of the identity, which is the object's.
			author = current.author,
			permlink = current.permlink,
			title = current.title,
			body = body,
			jsonMetadata = current.jsonMetadata,
		)

		val prepared = when (val p = hive.prepareComment(operation, author = account)) {
			is HivePreparationResult.Ready -> p.transaction
			is HivePreparationResult.Failed -> return Outcome.Failed(
				when (val r = p.result) {
					is HiveRpc.BroadcastResult.Rejected -> r.message
					is HiveRpc.BroadcastResult.NetworkFailure -> r.message
					else -> "RustedWax couldn't prepare this edit."
				},
			)
		}

		// Claim before crossing the network boundary, including a thrown/lost answer.
		val attempt = SnapWriteGuards.EditAttempt(reservationId, kind, newText, body, prepared)
		if (!guards.attach(account, target, attempt)) return Outcome.Failed(NOT_SAVED)
		onAttached()
		val result = runCatching { hive.broadcastPrepared(prepared, account) }.getOrNull()
		if (result is HiveRpc.BroadcastResult.Success &&
			result.evidence == HiveRpc.BroadcastResult.Evidence.BLOCK
		) {
			return edited(account, target, attempt, result.txId)
		}
		return settleUnproven(account, target, attempt)
	}

	/**
	 * A broadcast that did not come back with a block. Read the object: the
	 * chain answers the only question that matters, which is what it says now.
	 */
	private fun settleUnproven(
		account: String,
		target: SnapReplyTarget,
		attempt: SnapWriteGuards.EditAttempt,
	): Outcome {
		val prepared = attempt.prepared
		// The words being on chain are not this transaction's proof: the same
		// text may have been there before, or put back by someone else, while
		// this one can still land after a newer edit (Issue #56). Only the
		// transaction's own inclusion, its proven absence, or proof that it can
		// no longer be included — read against the comment past that point —
		// settles it.
		return when (
			runCatching {
				hive.observeTransaction(prepared.txId, prepared.expirationEpochSec)
			}.getOrNull()
		) {
			HiveRpc.TransactionEvidence.BLOCK ->
				edited(account, target, attempt, prepared.txId)
			HiveRpc.TransactionEvidence.ABSENT -> {
				guards.release(account, target, attempt.id)
				Outcome.Failed("Your edit didn't reach Hive, so nothing changed. You can save it again.")
			}
			else -> cannotLand(account, target, attempt) ?: Outcome.Uncertain(
				"RustedWax couldn't confirm your edit yet, so the previous text is still " +
					"shown. Retry will check this edit without sending another.",
			)
		}
	}

	/**
	 * Settle an edit that current nodes prove can no longer be included (Issue
	 * #56) — the case transaction status cannot answer once its history ages out.
	 *
	 * The proof says nothing about whether it already was included, so the
	 * object is read again after it, from a node at or past the proving block.
	 * These exact words there is the edit.
	 * Different words free the comment without touching them and without
	 * claiming the edit never landed: it may have landed and been replaced since,
	 * and either way it cannot arrive later. An unreadable comment changes
	 * nothing.
	 */
	private fun cannotLand(
		account: String,
		target: SnapReplyTarget,
		attempt: SnapWriteGuards.EditAttempt,
	): Outcome? {
		val prepared = attempt.prepared
		val final = runCatching { hive.irreversiblyPast(prepared.expirationEpochSec) }.getOrNull() ?: return null
		val now = runCatching { hive.readComment(target.author, target.permlink) }.getOrNull() ?: return null
		if (!now.author.equals(target.author, ignoreCase = true) || now.permlink != target.permlink) return null
		// Only a node at or past the proving block describes the chain the proof
		// is about; an earlier answer can neither confirm nor free the edit.
		if ((now.headEpochSec ?: return null) < final) return null
		if (now.body == attempt.body) return edited(account, target, attempt, prepared.txId)
		guards.release(account, target, attempt.id)
		return Outcome.Failed(
			"RustedWax couldn't confirm your edit, and it can no longer arrive. " +
				"Hive shows different words now, so those stay. You can save your edit again.",
		)
	}

	private fun edited(
		account: String,
		target: SnapReplyTarget,
		attempt: SnapWriteGuards.EditAttempt,
		txId: String,
	): Outcome.Edited {
		// Only the caller that frees this attempt updates the stored body: a late
		// completion from an earlier Activity, settling an attempt already
		// settled, must not write its older words over a newer edit (Issue #56).
		// The repair runs before the saved attempt is removed, so a process that
		// dies in between settles it again rather than losing the repair.
		guards.finish(account, target, attempt.id) {
			rememberBody(account, target, attempt.kind, attempt.body)
		}
		return Outcome.Edited(target.contentId, attempt.text, txId)
	}

	/**
	 * Keep a **confirmed** record's stored body in step with the chain.
	 *
	 * The History card draws a confirmed Snap from this record before Hive is
	 * asked, so leaving it alone would show the pre-edit words every time the
	 * app reopens, until the chain read lands. Only the body moves, and only on
	 * a record that is already final: a confirmed record is never signed or
	 * sent again, so its body has no further say in what reaches Hive.
	 *
	 * Best effort. If the write does not stick, the chain is still right and the
	 * next chain read draws it.
	 */
	private fun rememberBody(
		account: String,
		target: SnapReplyTarget,
		kind: SnapEditKind,
		body: String,
	) {
		val store = store ?: return
		val wanted = when (kind) {
			SnapEditKind.ROOT -> PendingSnapKind.ROOT
			SnapEditKind.REPLY -> PendingSnapKind.REPLY
		}
		runCatching {
			store.all(account)
				.filter {
					it.kind == wanted &&
						it.state == PendingSnapState.CONFIRMED &&
						it.contentId == target.contentId &&
						PendingSnapIntegrity.identityProblem(it, account) == null
				}
				.forEach { store.write(it.copy(body = body)) }
		}
	}

	companion object {
		const val NOT_AUTHOR = "Only the author can edit this."
		const val DELETE_UNSETTLED = "This one's deletion isn't settled yet, so your edit wasn't saved."
		const val BUSY = "RustedWax is already working on this, so your edit wasn't saved. Try again in a moment."
		const val LOCKED =
			"RustedWax couldn't read what it saved about an earlier change to this, so it won't change it here."
		const val NOT_SAVED = "RustedWax couldn't save this edit safely on your phone, so it wasn't sent."

		/**
		 * Why this text cannot replace a Snap's or reply's words, or null.
		 *
		 * The same limit and the same "something visible" rule as writing a new
		 * one, so an edit cannot become the way around either.
		 */
		fun textProblem(text: String): String? {
			// Attached images (Issue 40D) are kept through an edit and never
			// counted against the words.
			val (words, images) = SnapAttachmentBlock.split(text)
			return when {
				!SnapText.hasVisible(SnapReplyText.sanitize(words)) && images.isEmpty() ->
					"An edit needs something in it."
				SnapText.count(words) > SnapText.LIMIT ->
					"This is ${SnapText.count(words)} characters, over ${SnapText.LIMIT}."
				else -> null
			}
		}
	}
}
