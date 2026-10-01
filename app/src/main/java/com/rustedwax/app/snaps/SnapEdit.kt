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
 * Duplicate safety is therefore a property of the operation, not of a local
 * state machine: rebroadcasting the same edit is the same edit. That is why
 * there is no pending record here and no intent — there is nothing to
 * reconcile that could become a second comment. What *is* still owed is
 * honesty about the outcome, so success is reported only on block inclusion or
 * on reading the new body back, and everything else leaves the previous text
 * on screen.
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
		 * Not a risk of duplication — the operation targets an existing
		 * object — only of the screen being behind the chain for a while. So
		 * the previous text stays, and saving again is safe.
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

		val result = runCatching { hive.broadcastPrepared(prepared, account) }.getOrNull()
		if (result is HiveRpc.BroadcastResult.Success &&
			result.evidence == HiveRpc.BroadcastResult.Evidence.BLOCK
		) {
			return edited(account, target, kind, newText, body, result.txId)
		}
		return settleUnproven(account, target, kind, newText, body, prepared)
	}

	/**
	 * A broadcast that did not come back with a block. Read the object: the
	 * chain answers the only question that matters, which is what it says now.
	 */
	private fun settleUnproven(
		account: String,
		target: SnapReplyTarget,
		kind: SnapEditKind,
		newText: String,
		body: String,
		prepared: PreparedHiveTransaction,
	): Outcome {
		val after = runCatching { hive.readComment(target.author, target.permlink) }.getOrNull()
		if (after?.body == body) return edited(account, target, kind, newText, body, prepared.txId)
		return when (
			runCatching {
				hive.observeTransaction(prepared.txId, prepared.expirationEpochSec)
			}.getOrNull()
		) {
			HiveRpc.TransactionEvidence.BLOCK ->
				edited(account, target, kind, newText, body, prepared.txId)
			HiveRpc.TransactionEvidence.ABSENT -> Outcome.Failed(
				"Your edit didn't reach Hive, so nothing changed. You can save it again.",
			)
			else -> Outcome.Uncertain(
				"RustedWax couldn't confirm your edit yet, so the previous text is still " +
					"shown. Saving again is safe.",
			)
		}
	}

	private fun edited(
		account: String,
		target: SnapReplyTarget,
		kind: SnapEditKind,
		newText: String,
		body: String,
		txId: String,
	): Outcome.Edited {
		rememberBody(account, target, kind, body)
		return Outcome.Edited(target.contentId, newText, txId)
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

		/**
		 * Why this text cannot replace a Snap's or reply's words, or null.
		 *
		 * The same limit and the same "something visible" rule as writing a new
		 * one, so an edit cannot become the way around either.
		 */
		fun textProblem(text: String): String? = when {
			!SnapText.hasVisible(SnapReplyText.sanitize(text)) -> "An edit needs something in it."
			SnapText.count(text) > SnapText.LIMIT ->
				"This is ${SnapText.count(text)} characters, over ${SnapText.LIMIT}."
			else -> null
		}
	}
}
