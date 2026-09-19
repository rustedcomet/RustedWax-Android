package com.rustedwax.app.snaps

import com.rustedwax.app.ui.snaps.SnapText
import com.rustedwax.hive.HiveScrobblePayload
import java.security.SecureRandom

/**
 * The comment a reply will be parented on.
 *
 * Could be the user's own root Snap, somebody else's reply to it, or anything
 * further down — Hive makes no distinction and neither does this. What it is
 * *not* is free text: both fields are validated on construction, because this
 * pair becomes the `parent_author` and `parent_permlink` of a transaction this
 * user signs, and half of the time it arrived from a stranger's comment.
 */
data class SnapReplyTarget(val author: String, val permlink: String) {
	val contentId: String get() = "$author/$permlink"

	companion object {
		/** Null when this is not a comment RustedWax is willing to reply to. */
		fun of(author: String, permlink: String): SnapReplyTarget? =
			if (HiveAccountName.isValid(author) && SnapReplies.isPermlink(permlink)) {
				SnapReplyTarget(author, permlink)
			} else {
				null
			}

		fun of(reply: SnapReply): SnapReplyTarget? = of(reply.author, reply.permlink)
	}
}

/**
 * The durable identity of one reply *intent* — one thing the user meant to say,
 * once.
 *
 * This exists because a reply slot cannot be the parent comment alone. Replying
 * twice to the same comment is ordinary, so the slot has to be reusable; but a
 * reusable slot whose only key is the parent cannot tell "this attempt already
 * published" from "this is a new thing to say", and after a crash between the
 * confirmation and the draft being cleared, it guesses wrong in the direction
 * that posts the same words twice.
 *
 * An intent id settles it. It is minted once, when Send is tapped, committed
 * alongside the draft before anything is sent, and retired only when the draft
 * is cleared. So:
 *
 *  - the same intent, attempted again, resolves against the same pending record
 *    — a confirmed one answers "already published" and broadcasts nothing;
 *  - a *new* draft has no intent until its own Send, so a genuinely later reply
 *    to the same parent gets its own record and its own permlink.
 *
 * Hex, so it can never contain the `|` that separates it from the parent in a
 * store key, and long enough that two intents cannot collide.
 */
object SnapReplyIntent {

	private const val BYTES = 8

	fun generate(random: java.util.Random = SecureRandom()): String {
		val bytes = ByteArray(BYTES)
		random.nextBytes(bytes)
		return bytes.joinToString("") { "%02x".format(it) }
	}

	/**
	 * Whether a stored string is still shaped like an intent id this minted.
	 *
	 * Checked when a persisted draft is read back, because an intent id has one
	 * job — naming the pending record for an attempt — and a string that could
	 * never have named one is not a usable intent. It is also what guarantees
	 * the id cannot carry the `|` that separates it from the parent comment in a
	 * store key, however the entry was written.
	 */
	fun isValid(intentId: String): Boolean = SHAPE.matches(intentId)

	private val SHAPE = Regex("^[0-9a-f]{${BYTES * 2}}$")

	// There is deliberately **no** way to derive an intent id for a draft whose
	// stored entry cannot be read.
	//
	// An earlier revision supplied one, computed from the draft's key so that it
	// was at least stable across attempts. Stable is not the same as correct: the
	// unreadable entry may be a draft that has already been sent under some
	// *other* intent X, and a synthetic intent Y is a different pending record,
	// a different permlink and therefore a second permanent public comment
	// saying the same thing. An entry that cannot be decoded cannot be resumed,
	// and the only safe response is to refuse — see [SnapReplyDraftRead.Corrupt].
}

/**
 * The slot a pending reply occupies in the reply store.
 *
 * Two levels, and both are load-bearing:
 *
 *  - the **slot** (`reply|<author>/<permlink>`) is the composer. One per parent
 *    comment per account: it is what the draft is filed under and what the UI
 *    keys its status by;
 *  - the **event id** (`reply|<author>/<permlink>|<intentId>`) is the
 *    publication. One per attempt, so a confirmed reply and a later fresh reply
 *    to the same parent are different records that cannot be mistaken for each
 *    other.
 *
 * The `reply|` prefix is not decoration. The pending store keys rows as
 * `account|eventId`, and History's own event ids are opaque; prefixing means a
 * reply attempt and a History row can never collide on a string even if the two
 * stores were ever pointed at one file by mistake.
 */
object SnapReplyKey {

	private const val PREFIX = "reply"

	/** The composer/draft slot for one parent comment. */
	fun slot(target: SnapReplyTarget): String = "$PREFIX|${target.contentId}"

	/** The pending-store event id for one attempt at that slot. */
	fun of(target: SnapReplyTarget, intentId: String): String = "${slot(target)}|$intentId"

	/**
	 * The slot an event id belongs to, or null when it is not a reply event id.
	 *
	 * Splitting is unambiguous because neither a Hive account name nor a
	 * permlink RustedWax will act on may contain `|` — [SnapReplies.isPermlink]
	 * and [HiveAccountName] both refuse it — and an intent id is hex.
	 */
	fun slotOf(eventId: String): String? {
		val parts = eventId.split('|')
		return if (parts.size == 3 && parts[0] == PREFIX) "${parts[0]}|${parts[1]}" else null
	}

	/** The intent id inside an event id, or null when it is not a reply event id. */
	fun intentOf(eventId: String): String? {
		val parts = eventId.split('|')
		return if (parts.size == 3 && parts[0] == PREFIX) parts[2] else null
	}

	/**
	 * The comment a slot or event id belongs to, or null when it names none.
	 *
	 * Goes back through [SnapReplyTarget.of], so a slot string that has been
	 * damaged in storage cannot produce a target the validator would have
	 * refused on the way in.
	 */
	fun targetOf(slotOrEventId: String): SnapReplyTarget? {
		val parts = slotOrEventId.split('|')
		if (parts.size !in 2..3 || parts[0] != PREFIX) return null
		val slash = parts[1].indexOf('/')
		if (slash <= 0) return null
		return SnapReplyTarget.of(parts[1].substring(0, slash), parts[1].substring(slash + 1))
	}
}

/**
 * What a reply actually says on chain.
 *
 * The body is the user's text and **nothing else**. No URL, no hashtags, no
 * header — the root Snap already carries the media anchor and the tags, and a
 * reply that repeated them would be appending RustedWax's boilerplate to every
 * line of somebody's conversation. This is deliberately not the frozen v1 root
 * body and does not share a line of code with it: [SnapPayloadBuilder] is
 * untouched by Stage 4.
 *
 * Because nothing is appended, there is no generated text inside the 200-cluster
 * limit to account for — what the composer counted is exactly what is published.
 * The only generated part of a reply is [buildMetadata], which is a separate
 * field on the operation and was never in the body to begin with.
 */
object SnapReplyPayloadBuilder {

	fun build(userText: String): SnapPayload = SnapPayload(
		// Byte for byte, the same promise the root builder makes: not trimmed,
		// not normalised, not rewritten.
		body = userText,
		jsonMetadata = buildMetadata(),
	)

	/**
	 * `app` and nothing else.
	 *
	 * A reply carries no `tags`: tags index a *post*, the body has no hashtags
	 * to mirror, and a tag list that disagreed with the body is the exact
	 * inconsistency the root builder goes out of its way to avoid.
	 */
	private fun buildMetadata(): String =
		"""{"app":${HiveScrobblePayload.quoteJson(HiveScrobblePayload.APP_NAME)}}"""

	/**
	 * Why this reply must not be broadcast, or null when it is fit to sign.
	 *
	 * The composer enforces the same two rules while the user types, and that is
	 * not enough. A control can be bypassed by a stale composition, a restored
	 * draft written by an older build, a hand-edited preference file, or simply
	 * a future caller that forgets — and what is at stake is a permanent public
	 * comment. So the rules are enforced **again here**, at the last point
	 * before a signature, against the finished payload rather than against
	 * whatever the caller believes it passed in.
	 *
	 * [SnapText] is called rather than re-implemented deliberately. The cluster
	 * rule is genuinely intricate — joiners, flags, keycaps, skin tones — and a
	 * second copy of it here would be a second copy that could disagree with the
	 * counter the user was watching, which is the one outcome worse than no
	 * check at all.
	 *
	 * This binds **only** what RustedWax creates. External replies are read
	 * through a different path entirely and are never measured against it.
	 */
	fun problem(payload: SnapPayload): String? = when {
		!hasRenderable(payload.body) -> "a reply needs something in it"
		SnapText.count(payload.body) > SnapText.LIMIT ->
			"this reply is ${SnapText.count(payload.body)} characters, over ${SnapText.LIMIT}"
		else -> null
	}

	/**
	 * Whether anything in this reply would actually put a mark on a screen.
	 *
	 * [SnapText.hasVisible] answers *almost* this question and is deliberately
	 * left alone — it is the shared rule the root Snap composer is held to, and
	 * Stage 4 does not get to change what a root Snap considers visible. What it
	 * does not account for is control characters: `Character.isWhitespace` is
	 * false for NUL, BEL, ESC and the C1 block, so a draft made of nothing but
	 * those looked like content to it and could have been signed.
	 *
	 * So the controls are removed first, by the very same function the *read*
	 * path uses on somebody else's reply, and the shared rule is asked about
	 * what is left. One definition of "a character that can appear in a reply",
	 * applied in both directions, and no second copy of the joiner and
	 * combining-mark logic to drift out of step.
	 *
	 * This decides only whether the reply may be published. It does **not**
	 * change what is published: the body reaches Hive byte for byte as typed,
	 * exactly as before.
	 */
	private fun hasRenderable(body: String): Boolean =
		SnapText.hasVisible(SnapReplyText.sanitize(body))
}
