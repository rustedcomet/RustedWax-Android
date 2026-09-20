package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.SnapReplyKey
import com.rustedwax.app.snaps.SnapReplyTarget

/** Where a bell row sends the user. Null on a row that has nowhere to go. */
sealed interface SnapAttentionTarget {
	/** Open this conversation over History. The root is already known. */
	data class Thread(val root: SnapReplyTarget) : SnapAttentionTarget

	/**
	 * Open the conversation this comment is in.
	 *
	 * A separate case from [Thread] because the answer is not known when the row
	 * is built and must not be: the conversations this process has read live in
	 * a plain map rather than in snapshot state, so resolving one while the bell
	 * is being composed would read state Compose is not watching. It is resolved
	 * at the moment of the tap instead — which is also the later, better-informed
	 * moment, since another thread may have loaded in between.
	 *
	 * See [SnapThreadController.rootOf], and what is done when it answers null.
	 */
	data class Comment(val comment: SnapReplyTarget) : SnapAttentionTarget

	/** Go to History and bring this row into view. */
	data class HistoryEvent(val eventId: String) : SnapAttentionTarget
}

/** What a bell row is about, for the icon and the ordering. */
enum class SnapAttentionKind {
	/** Somebody else answered this account. The only row that is *news*. */
	INCOMING_REPLY,

	/** A root Snap that did not finish, or whose outcome is unknown. */
	ROOT_SNAP,

	/** A reply that did not finish, or whose outcome is unknown. */
	REPLY,

	/** A Like that was refused, or whose outcome is unknown. */
	LIKE,
}

/**
 * One line in the bell.
 *
 * [id] is stable across rebuilds — it is the identity of the thing the row is
 * about, not its position — so a list that is recomputed on every recomposition
 * does not churn keys underneath Compose.
 */
data class SnapAttentionRow(
	val id: String,
	val kind: SnapAttentionKind,
	val title: String,
	val detail: String,
	val target: SnapAttentionTarget?,
)

/**
 * The bell's contents, assembled from state that already exists.
 *
 * Pure, and deliberately so: this is the one place the four Stage 6 categories
 * are turned into rows, and every input is handed in rather than reached for.
 * It holds no controller, no store, no publisher and no port — there is nothing
 * here that could send, retry or resend anything, and nothing that could ask
 * something else to. A row is a label and a destination.
 *
 * It also re-derives nothing. The three unresolved-publication categories are
 * read straight out of the `needsAttention()` seams Stage 5 left on
 * [SnapPostController], [SnapThreadController] and [SnapLikeController], which
 * own those state machines; this only decides what to call them and where a tap
 * should go. Copying their contents into a store of its own is precisely the
 * mistake that would let the bell and the card disagree.
 *
 * ## Why unresolved work sorts above incoming replies
 *
 * A reply is something to read; an unfinished Snap is something only the user
 * can resolve, and it stays unresolved until they do. Nothing in this app
 * retries on the user's behalf, so a row that needs a tap has to be the row
 * that is easiest to reach — otherwise the one category the bell exists to
 * rescue is the one it buries under conversation.
 */
object SnapAttentionModel {

	/**
	 * The bell should hold a glance, not an inbox.
	 *
	 * Past this the rows stop being drawn and the count carries the rest. The
	 * count itself is never clamped: "3 more" is a fact, and a badge that stopped
	 * at twenty would be the app quietly deciding the user had enough problems.
	 */
	const val MAX_ROWS = 12

	fun of(
		notices: List<SnapNotice>,
		rootAttention: List<SnapAttention>,
		replyAttention: List<SnapAttention>,
		likeAttention: List<Pair<SnapReplyTarget, SnapLikeState>>,
	): List<SnapAttentionRow> =
		rootAttention.map(::rootRow) +
			replyAttention.mapNotNull(::replyRow) +
			likeAttention.map { likeRow(it.first, it.second) } +
			notices.map(::noticeRow)

	private fun noticeRow(notice: SnapNotice) = SnapAttentionRow(
		id = "reply-in|${notice.contentId}",
		kind = SnapAttentionKind.INCOMING_REPLY,
		title = "@${notice.author} replied",
		// The reply's own words, already clamped when the notice was built. An
		// empty body is an ordinary thing to find on chain — a deleted comment
		// arrives that way — and has to read as one rather than as a blank row.
		detail = notice.text.ifBlank { "(no text)" },
		target = notice.root()?.let(SnapAttentionTarget::Thread),
	)

	private fun rootRow(attention: SnapAttention): SnapAttentionRow {
		val eventId = attention.key.substringAfter('|', "")
		return SnapAttentionRow(
			id = "root|${attention.key}",
			kind = SnapAttentionKind.ROOT_SNAP,
			title = when (attention.status) {
				is SnapPostStatus.Interrupted -> "Snap didn't finish posting"
				is SnapPostStatus.Uncertain -> "Snap may not have posted"
				else -> "Snap didn't post"
			},
			detail = detailOf(attention.status, interrupted = "Your words are saved."),
			target = eventId.takeIf { it.isNotEmpty() }?.let(SnapAttentionTarget::HistoryEvent),
		)
	}

	private fun replyRow(attention: SnapAttention): SnapAttentionRow? {
		// `account|reply|author/permlink`. The account cannot contain a `|` —
		// neither a Hive account name nor a permlink RustedWax will act on may —
		// so the slot is unambiguously everything after the first one.
		val slot = attention.key.substringAfter('|', "")
		val parent = SnapReplyKey.targetOf(slot) ?: return null
		return SnapAttentionRow(
			id = "reply|${attention.key}",
			kind = SnapAttentionKind.REPLY,
			title = when (attention.status) {
				is SnapPostStatus.Interrupted -> "Reply didn't finish posting"
				is SnapPostStatus.Uncertain -> "Reply may not have posted"
				else -> "Reply didn't post"
			},
			detail = detailOf(attention.status, interrupted = "Your words are saved."),
			target = SnapAttentionTarget.Comment(parent),
		)
	}

	private fun likeRow(target: SnapReplyTarget, state: SnapLikeState) = SnapAttentionRow(
		id = "like|${target.contentId}",
		kind = SnapAttentionKind.LIKE,
		title = when (state) {
			is SnapLikeState.Pending -> "Like not confirmed"
			else -> "Like didn't go through"
		},
		detail = when (state) {
			// The message the heart already carries, and the same one: a
			// second wording for the same failure is a second thing to keep
			// true.
			is SnapLikeState.Pending -> state.message
			is SnapLikeState.Refused -> state.message
			else -> "Open the thread to check it."
		},
		target = SnapAttentionTarget.Comment(target),
	)

	/**
	 * What the row says underneath its title.
	 *
	 * An [SnapPostStatus.Uncertain] row deliberately never suggests posting
	 * again. The only thing offered anywhere in this app for an unknown outcome
	 * is another read, and a bell that said "try again" would be inviting the
	 * one tap that could duplicate a live comment.
	 */
	private fun detailOf(status: SnapPostStatus, interrupted: String): String = when (status) {
		is SnapPostStatus.Interrupted -> interrupted
		is SnapPostStatus.Uncertain -> status.message
		is SnapPostStatus.Failed -> status.message
		else -> ""
	}
}
