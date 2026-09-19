package com.rustedwax.app.snaps

import com.rustedwax.hive.ViewerVote

/**
 * One comment in a Snap thread, after it has been made safe to hold.
 *
 * Every field here came off the chain, which means every field here was written
 * by somebody who is not this user and not this app. What arrives is a JSON
 * object with no guarantees at all: fields may be missing, empty, of the wrong
 * type, megabytes long, or full of characters chosen precisely because they do
 * something unexpected when drawn. So nothing is stored raw — a [SnapReply]
 * only ever exists because [SnapReplies] was willing to build one, and what it
 * refuses never reaches a screen.
 *
 * [createdAtEpochSec] is nullable and stays nullable. A comment whose `created`
 * field could not be read still has words worth showing, and inventing a
 * timestamp so the age label has something to print would be making up the one
 * thing the user might actually check.
 */
data class SnapReply(
	val author: String,
	val permlink: String,
	val parentAuthor: String,
	val parentPermlink: String,
	/** The comment's text, sanitised but never shortened. */
	val body: String,
	val createdAtEpochSec: Long?,
	/**
	 * What the **signed-in viewer's own** vote on this comment looked like in
	 * the response this was built from, for drawing an outline or a filled
	 * heart without a second request.
	 *
	 * Only the viewer's row is kept, never the whole `active_votes` list. A
	 * popular Snap carries thousands of votes per comment and every reply in a
	 * conversation is held in memory at once — see [SnapThreadBuilder] — so
	 * storing the list would make the cost of opening a thread scale with other
	 * people's voting rather than with the conversation.
	 *
	 * It is a **rendering** fact and never an authorization. `bridge` answers
	 * from hivemind, which can lag, and carries no declared percentage at all;
	 * every actual Like tap re-reads the chain through
	 * [com.rustedwax.hive.HiveRpc.findViewerVote] before anything is signed.
	 *
	 * Defaults to [com.rustedwax.hive.ViewerVote.Unreadable] rather than to
	 * `None`, so a [SnapReply] built by something that never looked at votes
	 * draws no heart state instead of asserting that nobody voted.
	 */
	val viewerVote: ViewerVote = ViewerVote.Unreadable("vote state was never read"),
	/**
	 * How many positive votes the chain showed on this comment when it was
	 * read. **Presentation only.**
	 *
	 * The other half of the heart, and a different question from
	 * [viewerVote]: that one says whether *this* account liked something, this
	 * says how many people did. Kept as one integer rather than as the voter
	 * list it was reduced from — a popular Snap carries thousands of votes per
	 * comment and a whole conversation is held in memory at once, so the cost
	 * of opening a thread must scale with the conversation and not with other
	 * people's voting.
	 *
	 * It decides nothing. It cannot authorize a Like, block one, change its
	 * strength or stand in for the authoritative `get_active_votes` read that
	 * every vote is gated on — see [com.rustedwax.hive.HiveVotes.positiveCount]
	 * for why an unreadable row lowers this number instead of failing a read.
	 *
	 * Defaults to zero, which is what a [SnapReply] built by something that
	 * never looked at votes should draw: no number at all.
	 */
	val positiveLikeCount: Int = 0,
) {
	val contentId: String get() = "$author/$permlink"
	val parentId: String get() = "$parentAuthor/$parentPermlink"
}

/**
 * A comment and everything posted under it.
 *
 * [depth] is what the screen indents by, and it is deliberately *not* the same
 * number as how deep the node really is: past [SnapThreadBuilder.MAX_INDENT] it
 * stops growing. A long argument between two people is a perfectly ordinary
 * thing to find under a Snap, and letting it indent forever would push its text
 * off the side of a phone. The nesting is preserved exactly; only the drawing
 * of it flattens out.
 */
data class SnapThreadNode(
	val reply: SnapReply,
	val depth: Int,
	val children: List<SnapThreadNode>,
)

/**
 * The conversation under one root Snap — **all of it**.
 *
 * [rows] is the tree in reading order, built once when the thread is built, and
 * it is what the dedicated thread screen draws through a `LazyColumn`: the list
 * is complete, and composition is what happens incrementally. That is the whole
 * scaling story. There is no window, no page and no cut-off, because there is
 * nothing to bound — every reply in this object was already in memory, parsed,
 * before the tree was assembled.
 *
 * [total] is derived from [rows] rather than counted separately, so
 * `View replies (N)` cannot promise a reply the thread does not hold.
 */
data class SnapThread(
	val rootId: String,
	/** Direct replies to the root, oldest first. */
	val children: List<SnapThreadNode>,
	/** Depth-first, in reading order — every node, exactly once. */
	val rows: List<SnapThreadNode>,
	/**
	 * Positive votes on the **root Snap itself**, for the same heart the
	 * replies get. Presentation only, exactly as
	 * [SnapReply.positiveLikeCount] is.
	 *
	 * It arrives here because the root is in the same response as its replies
	 * and is then dropped from the tree — it is the Snap, not a reply to it —
	 * so this is the one field of it worth keeping. Zero when the response
	 * carried no root row, which is what a thread built from local rows alone
	 * looks like.
	 */
	val rootLikeCount: Int = 0,
) {
	val total: Int get() = rows.size

	companion object {
		fun empty(rootId: String) = SnapThread(rootId, emptyList(), emptyList())
	}
}

/**
 * Turns whatever the chain returned into a tree, or into less of one.
 *
 * The rule throughout is that structure is **derived, never accepted**. Hive's
 * discussion response carries a `replies` array on every comment, and using it
 * would mean letting untrusted content tell RustedWax what the shape of the
 * conversation is; a hostile or simply broken entry could then claim a child it
 * does not have, or claim itself. What is used instead is each comment's own
 * `parent_author`/`parent_permlink`, cross-checked against the set of comments
 * that actually exist, so a node appears under a parent only when both ends
 * agree.
 *
 * **Every valid reply is kept.** There is no node limit. An earlier revision
 * stopped at 500 and silently dropped the rest, which was wrong twice over: a
 * conversation of 501 comments is an ordinary thing for a popular Snap to have,
 * and the dedicated thread exists precisely so that the whole of it is
 * reachable — but also the cap bought nothing. `bridge.get_discussion` returns
 * the complete subtree in one response, and every body in it has already been
 * read, sanitised and held in memory by the time this function is called. The
 * cap discarded data the app had already paid for, so removing it costs no
 * memory at all; it only stops the loss.
 *
 * What is still refused is refused for safety, not for size, and refused
 * quietly and completely:
 *
 *  - **a node whose parent is not in the set** is dropped. It cannot be placed,
 *    and placing it somewhere plausible would be RustedWax inventing a position
 *    in someone else's conversation;
 *  - **a node that is its own parent, or part of a cycle**, is dropped. A cycle
 *    is not a conversation, and walking one is how a thread screen hangs;
 *  - **a second node with an id already seen** is dropped. `author/permlink` is
 *    unique on chain, so a repeat is evidence the response is wrong.
 *
 * Nothing here recurses. Both the walk and the assembly are loops over an
 * explicit stack and an index, because with the node limit gone the depth of a
 * conversation is decided by strangers: a chain of ten thousand one-word
 * replies is a legal thing to find on Hive, and a recursive builder would meet
 * it with a `StackOverflowError` rather than a thread.
 *
 * Pure Kotlin, no Android and no JSON: the reconstruction is the part with the
 * rules in it, so it is the part that is testable on its own.
 */
object SnapThreadBuilder {

	/** Past this, replies stop moving right. The nesting itself is untouched. */
	const val MAX_INDENT = 5

	fun build(rootId: String, replies: List<SnapReply>): SnapThread {
		// De-duplicate first, and drop the root if the chain echoed it back: the
		// root is the Snap itself, not a reply to it.
		val byId = LinkedHashMap<String, SnapReply>()
		// The root's own row is dropped from the tree but not thrown away
		// entirely: its Like count is the one thing on it the screen still
		// needs, and this response is where it comes from.
		var rootLikes = 0
		replies.forEach { reply ->
			if (reply.contentId == rootId) {
				rootLikes = maxOf(rootLikes, reply.positiveLikeCount)
				return@forEach
			}
			if (reply.contentId == reply.parentId) return@forEach
			byId.putIfAbsent(reply.contentId, reply)
		}

		val children = HashMap<String, MutableList<SnapReply>>()
		byId.values.forEach { reply ->
			// A parent that is neither the root nor a comment in this response
			// cannot be resolved, so the node has nowhere to go.
			if (reply.parentId != rootId && reply.parentId !in byId) return@forEach
			children.getOrPut(reply.parentId) { mutableListOf() } += reply
		}

		// Oldest first, and ties broken by id so the order is the same on every
		// device and every refresh. An unreadable timestamp sorts as the oldest
		// thing there is — the same rule [SnapThreadPreview] uses to pick what a
		// card shows, and the conservative one: a comment RustedWax cannot date
		// never displaces a dated comment from the newest position, which is the
		// only position that makes a claim.
		val order = compareBy<SnapReply>(
			{ it.createdAtEpochSec ?: Long.MIN_VALUE },
			{ it.contentId },
		)

		// ── pass one: walk the tree, iteratively, in reading order ──
		//
		// A node reachable from the root cannot be part of a cycle — every reply
		// has exactly one parent, so a cycle has no path back to the root — but
		// `seen` guards the walk anyway rather than relying on that argument
		// holding after the next edit.
		val seen = HashSet<String>()
		val reached = ArrayList<SnapReply>()
		val parentOf = ArrayList<Int>()
		val depthOf = ArrayList<Int>()
		val stack = ArrayDeque<Visit>()

		fun push(parentId: String, parentIndex: Int) {
			val kids = children[parentId] ?: return
			// Pushed in reverse so the oldest child is popped first, which is what
			// makes the pop order a pre-order walk.
			kids.sortedWith(order).asReversed().forEach { stack.addLast(Visit(it, parentIndex)) }
		}

		push(rootId, ROOT)
		while (stack.isNotEmpty()) {
			val visit = stack.removeLast()
			if (!seen.add(visit.reply.contentId)) continue
			val index = reached.size
			reached += visit.reply
			parentOf += visit.parentIndex
			depthOf += if (visit.parentIndex == ROOT) 0 else depthOf[visit.parentIndex] + 1
			push(visit.reply.contentId, index)
		}

		// ── pass two: assemble immutable nodes, children first ──
		//
		// `reached` is pre-order, so a parent always sits before its children and
		// walking backwards means every child is built before the parent that
		// needs it.
		val childNodes = Array(reached.size) { ArrayList<SnapThreadNode>() }
		val rows = arrayOfNulls<SnapThreadNode>(reached.size)
		val top = ArrayList<SnapThreadNode>()

		for (i in reached.indices.reversed()) {
			// Children were appended youngest-first by this reverse walk.
			val kids = childNodes[i].also { it.reverse() }
			val node = SnapThreadNode(
				reply = reached[i],
				depth = depthOf[i].coerceAtMost(MAX_INDENT),
				children = kids,
			)
			rows[i] = node
			val parent = parentOf[i]
			if (parent == ROOT) top += node else childNodes[parent] += node
		}
		top.reverse()

		// `rows` is filled by index, and index order is the pre-order the walk
		// produced — so the reading order is a by-product of building, not a
		// second traversal.
		@Suppress("UNCHECKED_CAST")
		return SnapThread(rootId, top, (rows as Array<SnapThreadNode>).asList(), rootLikes)
	}

	/** One node waiting to be walked, and where it hangs. */
	private class Visit(val reply: SnapReply, val parentIndex: Int)

	/** The parent index used for a direct reply to the root Snap. */
	private const val ROOT = -1
}

/**
 * What the History card shows of a thread without becoming one.
 *
 * A History row is a row. It already carries a title, a status line, the Snap
 * itself and two buttons, and a conversation is allowed to be any length at
 * all, so the preview is bounded twice over: at most [MAX_PREVIEWS] replies,
 * each clamped to [MAX_PREVIEW_CHARS] before it is ever measured. Both limits
 * are enforced here, in a pure function, rather than by a `maxLines` on a
 * `Text` somewhere — a layout attribute is a request, and the thing being
 * bounded is chain content nobody vetted.
 *
 * The two shown are the two most **recent** in the whole tree rather than the
 * first two direct replies, because the question a collapsed card answers is
 * "has anything happened here", and the newest comment is the answer to it
 * wherever in the conversation it sits.
 */
object SnapThreadPreview {

	const val MAX_PREVIEWS = 2
	const val MAX_PREVIEW_CHARS = 140

	/**
	 * One line of the card's summary.
	 *
	 * Carries the two identity fields the card actually draws rather than the
	 * whole [SnapReply] it came from, which keeps this a plain value object: it
	 * can be written to a small cache and read back without dragging a parent
	 * reference, a body and a timestamp along with it, and without the storage
	 * format having any say over the reply model.
	 */
	data class Item(
		val author: String,
		val permlink: String,
		/** Clamped text, and whether clamping actually removed anything. */
		val text: String,
		val truncated: Boolean,
	) {
		val contentId: String get() = "$author/$permlink"
	}

	data class Preview(
		val items: List<Item>,
		val total: Int,
	) {
		/** True once the card has to send the reader somewhere with more room. */
		val hasMore: Boolean get() = total > items.size
	}

	/**
	 * The card's summary, in one pass over the conversation.
	 *
	 * Deliberately not a sort. With the node limit gone a thread has no size at
	 * all — a popular Snap can carry thousands of replies — and sorting the
	 * whole of it to look at the last two would make the cost of drawing a
	 * History row scale with somebody else's argument. This keeps a list of at
	 * most [MAX_PREVIEWS] and walks the rows once.
	 */
	fun of(thread: SnapThread): Preview {
		// Newest first while it is being built, reversed at the end so the card
		// reads oldest-of-the-two downwards, the way a conversation does.
		val newest = ArrayList<SnapReply>(MAX_PREVIEWS)
		thread.rows.forEach { node ->
			val reply = node.reply
			var at = newest.size
			while (at > 0 && isNewer(reply, newest[at - 1])) at--
			if (at >= MAX_PREVIEWS) return@forEach
			newest.add(at, reply)
			if (newest.size > MAX_PREVIEWS) newest.removeAt(newest.lastIndex)
		}
		return Preview(
			items = newest.asReversed().map { clamp(it) },
			total = thread.total,
		)
	}

	/**
	 * Which of two replies is the more recent.
	 *
	 * A reply with no readable timestamp is treated as the oldest thing there
	 * is, so it never displaces a dated comment from the newest slot — the same
	 * rule [SnapThreadBuilder] orders siblings by. Ties fall back to the content
	 * id so the answer is the same on every device.
	 */
	private fun isNewer(a: SnapReply, b: SnapReply): Boolean {
		val at = a.createdAtEpochSec ?: Long.MIN_VALUE
		val bt = b.createdAtEpochSec ?: Long.MIN_VALUE
		return if (at != bt) at > bt else a.contentId > b.contentId
	}

	private fun clamp(reply: SnapReply): Item =
		Item(
			author = reply.author,
			permlink = reply.permlink,
			text = clampText(reply.body),
			truncated = reply.body.codePointCount(0, reply.body.length) > MAX_PREVIEW_CHARS,
		)

	/**
	 * Cut to [MAX_PREVIEW_CHARS], counted in **code points**.
	 *
	 * The unit matters: cutting by UTF-16 index can land between the halves of a
	 * surrogate pair and leave a broken character on the card. Public because a
	 * cache that hands back stored preview text must re-apply this rather than
	 * trust whatever length it finds on disk.
	 */
	fun clampText(text: String): String {
		val count = text.codePointCount(0, text.length)
		if (count <= MAX_PREVIEW_CHARS) return text
		return text.substring(0, text.offsetByCodePoints(0, MAX_PREVIEW_CHARS))
	}
}
