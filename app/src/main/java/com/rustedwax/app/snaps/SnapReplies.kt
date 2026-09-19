package com.rustedwax.app.snaps

import com.rustedwax.hive.HiveRpc
import org.json.JSONObject

/**
 * Builds [SnapReply]s out of chain objects, and refuses the ones it cannot.
 *
 * This is the boundary. Above it a reply is a JSON object somebody else wrote;
 * below it a reply is four validated strings and a nullable number. Nothing
 * skips it — [HiveSnapThreadReader] is the only thing that reads the chain for
 * a thread, and it returns what this produced or nothing at all.
 *
 * What gets refused is narrow and structural. The author and both halves of the
 * parent reference have to be shaped like the things they claim to be, because
 * those strings become an avatar request, a store key and the `parent_author`
 * of a comment this user may sign. The **body is never refused**: it is the one
 * field that is supposed to be arbitrary, and a reply RustedWax cannot show is
 * worse than a reply it shows exactly as written.
 */
object SnapReplies {

	/**
	 * One chain object as a reply, or null when it is not usable as one.
	 *
	 * Every field is read with [string], never with `optString`, and the
	 * difference matters for exactly the fields that become an identity.
	 * `optString` **coerces**: a JSON number `42` comes back as `"42"`, `true`
	 * as `"true"`. So a response carrying `"permlink": 42` would have produced
	 * the perfectly valid-looking permlink `"42"`, and RustedWax would have been
	 * willing to build a reply target — and then a signed `parent_permlink` —
	 * out of a value that was never a string at all. A wrong-typed identity
	 * field is a malformed response, and the only safe reading of it is to
	 * refuse the whole object.
	 *
	 * Nothing here throws. A missing field, a null, a number where a string
	 * belongs and an object where a string belongs are all ordinary things to
	 * find in an untrusted response, and none of them is worth an exception on
	 * a screen that is drawing somebody's conversation.
	 */
	fun parse(o: JSONObject): SnapReply? {
		val author = o.string("author")?.takeIf { HiveAccountName.isValid(it) } ?: return null
		val permlink = o.string("permlink")?.takeIf { isPermlink(it) } ?: return null
		val parentAuthor = o.string("parent_author")
			?.takeIf { HiveAccountName.isValid(it) } ?: return null
		val parentPermlink = o.string("parent_permlink")
			?.takeIf { isPermlink(it) } ?: return null

		return SnapReply(
			author = author,
			permlink = permlink,
			parentAuthor = parentAuthor,
			parentPermlink = parentPermlink,
			// Not an identity, so a wrong-typed body is treated as an absent one
			// rather than as a reason to hide the comment. A deleted reply
			// already arrives this way.
			body = SnapReplyText.sanitize(o.string("body")),
			// Null is a real answer here and is carried as one. Hive writes
			// `created` in exactly one format; anything else is a field that
			// could not be read, not a reply that happened at midnight in 1970.
			createdAtEpochSec = ChainTime.epochSec(o.string("created")?.takeIf { it.isNotEmpty() }),
		)
	}

	/**
	 * The value at [name] **only when it really is a string**.
	 *
	 * No coercion, in either direction: `JSONObject.NULL`, a number, a boolean,
	 * a nested object and a missing key all answer null.
	 */
	private fun JSONObject.string(name: String): String? = opt(name) as? String

	fun parseAll(objects: List<JSONObject>): List<SnapReply> = objects.mapNotNull { parse(it) }

	/**
	 * Whether a string is usable as a Hive permlink.
	 *
	 * Deliberately wider than [com.rustedwax.hive.HiveBroadcaster]'s check on the
	 * permlinks RustedWax *generates* — this one judges permlinks other people
	 * generated, and refusing a legal one means refusing to show a real comment.
	 * It is still a whitelist: the string ends up in a URL-shaped id, a
	 * preference key and, when the user replies, the `parent_permlink` of a
	 * signed transaction, so a slash, a space, a quote, a newline or a `|` in it
	 * is a reason not to touch it at all.
	 */
	fun isPermlink(value: String): Boolean =
		value.length in 1..MAX_PERMLINK_LENGTH && PERMLINK.matches(value)

	private const val MAX_PERMLINK_LENGTH = 256

	private val PERMLINK = Regex("^[a-z0-9][a-z0-9._-]*$")
}

/**
 * Makes a chain-authored body safe to draw, **without making it shorter**.
 *
 * There is no length limit here, and there must not be one. A reply written in
 * any other Hive client may be as long as Hive allows, and the dedicated thread
 * screen exists to show it complete; a cap in this function would be a silent
 * content limit applied to somebody else's words, indistinguishable on screen
 * from the comment simply ending there. An earlier revision capped at 64 KiB
 * "for memory", which is a policy about RustedWax's convenience being paid for
 * out of another author's text.
 *
 * Bounding what a *card* draws is a different question with a different answer:
 * [SnapThreadPreview] clamps the two-line summary on a History row, visibly and
 * reversibly, and the full text is one tap away.
 *
 * Two things are removed and nothing else:
 *
 *  - **control characters.** C0 and C1 apart from tab and newline draw as
 *    nothing, or as a replacement box, or — depending on the font — as
 *    something that moves the cursor. None of them is content;
 *  - **bidirectional overrides and isolates.** `U+202E` and its relatives
 *    reverse the direction of everything after them, which is how a line of
 *    text can be made to read as its own opposite. A reply is allowed to be in
 *    Arabic or Hebrew — the strong and marked characters that actually carry
 *    that are untouched — but it is not allowed to re-point the text around it.
 *
 * Everything else survives exactly: emoji, combining marks, unusual scripts,
 * Markdown, and any HTML the author typed. The HTML matters in the negative —
 * it stays *as characters*, because the only thing that ever draws a reply is
 * Compose's `Text`, which has no markup, no HTML and no script in it. There is
 * no renderer to sanitise for, and adding one would be the change that made
 * this function load-bearing in a way it is not now.
 *
 * Nothing bounds a body, and nothing bounds how many of them one conversation
 * may hold either. What the app actually pays for is this one response, which
 * `bridge.get_discussion` returns whole; once it has been parsed, the thread
 * screen renders it incrementally through a `LazyColumn` rather than discarding
 * any of it. See [SnapThreadBuilder].
 */
object SnapReplyText {

	fun sanitize(raw: String?): String {
		if (raw.isNullOrEmpty()) return ""
		val out = StringBuilder(raw.length)
		var i = 0
		while (i < raw.length) {
			val cp = raw.codePointAt(i)
			val width = Character.charCount(cp)
			if (!isStripped(cp)) out.appendCodePoint(cp)
			i += width
		}
		return out.toString()
	}

	private fun isStripped(cp: Int): Boolean = when {
		cp == '\n'.code || cp == '\t'.code -> false
		// `\r` goes: it is half of a line break whose other half is kept, and on
		// its own it is a cursor movement rather than a character.
		cp == '\r'.code -> true
		cp < 0x20 -> true
		cp in 0x7F..0x9F -> true
		// LRO, RLO, PDF, LRI, RLI, FSI, PDI — direction *overrides*, not the
		// ordinary marks a right-to-left language needs.
		cp in 0x202A..0x202E -> true
		cp in 0x2066..0x2069 -> true
		else -> false
	}
}

/**
 * Reads a whole Snap thread off Hive.
 *
 * A third port, kept apart from both [SnapHivePort] (which signs and
 * broadcasts) and [PostedSnapReader] (which reads one known comment) for the
 * same reason those two are apart: what an object *can* do is the only reliable
 * statement about what it will do. Loading a conversation cannot mint a
 * permlink or send a transaction, because there is no key and no broadcaster
 * anywhere behind this interface.
 */
interface SnapThreadReader {
	/**
	 * Every reply under this root, or **null when the chain could not be asked**.
	 *
	 * The distinction is the whole point of the nullable return. An empty list
	 * means "this Snap has no replies", which the screen states plainly; null
	 * means "RustedWax does not know", which it also states plainly, and the two
	 * must never be shown as the same thing.
	 */
	fun read(rootAuthor: String, rootPermlink: String): List<SnapReply>?
}

internal class HiveSnapThreadReader(
	private val rpc: HiveRpc = HiveRpc(),
) : SnapThreadReader {

	override fun read(rootAuthor: String, rootPermlink: String): List<SnapReply>? = runCatching {
		SnapReplies.parseAll(rpc.getDiscussion(rootAuthor, rootPermlink))
	}.getOrNull()
}
