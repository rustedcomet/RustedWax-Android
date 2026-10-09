package com.rustedwax.hive

import org.json.JSONArray
import org.json.JSONObject

/**
 * One comment as **consensus state** holds it, read from a node proven current.
 *
 * Exactly the fields hived's `delete_comment_evaluator` consults, and nothing a
 * display path would round. Read from `database_api.find_comments` — which
 * answers out of the chain's own comment objects — and never from hivemind's
 * `bridge` or `condenser_api.get_content` presentation, which can trail the
 * chain and which report payouts as rounded HBD amounts.
 *
 * The deployed evaluator (hive `libraries/chain/hive_evaluator_social.cpp`,
 * checked 2026-10-01) refuses a deletion when the comment does not exist, when
 * it has replies, when its cashout object is gone (it has paid out), and when
 * its net rshares are positive. Observed on the live nodes the same day: after
 * payout `cashout_time` reads `1969-12-31T23:59:59` while `net_rshares` and
 * `children` read **0** — so a paid-out comment looks deletable to anything
 * that checks votes and replies alone, and the cashout time is what tells them
 * apart.
 */
data class HiveCommentState(
	val author: String,
	val permlink: String,
	val parentAuthor: String,
	val parentPermlink: String,
	val children: Long,
	val netRshares: Long,
	/** Null once the comment has paid out (or the time is unreadable). */
	val cashoutEpochSec: Long?,
	/** Head block time of the node that answered, for comparing [cashoutEpochSec]. */
	val headEpochSec: Long,
	/**
	 * The object's own words, read in the same answer from the same current
	 * node (Issue #56). What an edit carries forward and settles against; null
	 * when that answer did not carry them as strings.
	 */
	val title: String? = null,
	val body: String? = null,
	val jsonMetadata: String? = null,
) {
	/** Payout still ahead of this node's head block: the cashout object exists. */
	val payoutPending: Boolean
		get() = cashoutEpochSec != null && cashoutEpochSec > headEpochSec
}

/** One fresh node's answer about one comment. */
sealed interface HiveCommentRead {
	val node: String

	data class Present(val state: HiveCommentState, override val node: String) : HiveCommentRead

	/**
	 * A current node holds no comment at that `author/permlink`. [headEpochSec]
	 * is that node's head block time when this answer was read, so absence can
	 * be fenced against a later chain point (Issue #56).
	 */
	data class Absent(override val node: String, val headEpochSec: Long? = null) : HiveCommentRead
}

internal object HiveCommentStates {

	/** Hive's "no cashout" sentinel: `fc::time_point_sec::maximum()` printed as int32. */
	const val NO_CASHOUT = "1969-12-31T23:59:59"

	/**
	 * Parse one node's `find_comments` result for exactly `author/permlink`.
	 *
	 * Null when the answer is not usable — a missing array, more than one row,
	 * a row naming some other object, or a numeric field that is not an exact
	 * integer. Null means "ask somebody else", never "absent": the empty array
	 * is the only absence there is.
	 */
	fun parse(
		result: JSONObject?,
		author: String,
		permlink: String,
		headEpochSec: Long,
		node: String,
	): HiveCommentRead? {
		val comments = result?.optJSONArray("comments") ?: return null
		if (comments.length() == 0) return HiveCommentRead.Absent(node, headEpochSec)
		if (comments.length() != 1) return null
		val c = comments.optJSONObject(0) ?: return null
		fun str(name: String): String? = c.opt(name) as? String
		val a = str("author") ?: return null
		val p = str("permlink") ?: return null
		// Byte for byte. A node answering about any other object has not
		// answered this question.
		if (a != author || p != permlink) return null
		val children = integer(c.opt("children"))?.takeIf { it >= 0 } ?: return null
		val net = integer(c.opt("net_rshares")) ?: return null
		val cashout = str("cashout_time") ?: return null
		return HiveCommentRead.Present(
			HiveCommentState(
				author = a,
				permlink = p,
				parentAuthor = str("parent_author") ?: return null,
				parentPermlink = str("parent_permlink") ?: return null,
				children = children,
				netRshares = net,
				cashoutEpochSec = if (cashout == NO_CASHOUT) null else ChainTimes.epochSec(cashout),
				headEpochSec = headEpochSec,
				title = str("title"),
				body = str("body"),
				jsonMetadata = str("json_metadata"),
			),
			node,
		)
	}

	/** Exact integers only: a number with a fraction or an unparseable string is not one. */
	fun integer(value: Any?): Long? = when (value) {
		is Int -> value.toLong()
		is Long -> value
		is String -> value.toLongOrNull()
		else -> null
	}

	fun params(author: String, permlink: String): JSONObject =
		JSONObject().put("comments", JSONArray().put(JSONArray().put(author).put(permlink)))
}

internal object ChainTimes {
	private val SHAPE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}")

	fun epochSec(value: String): Long? = runCatching {
		if (!SHAPE.matches(value)) return null
		val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
		fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
		fmt.isLenient = false
		fmt.parse(value)!!.time / 1000
	}.getOrNull()
}
