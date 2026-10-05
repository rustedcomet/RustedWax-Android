package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.ChainTime
import com.rustedwax.app.snaps.PostedSnapBody
import com.rustedwax.app.snaps.SnapPayloadBuilder
import com.rustedwax.app.snaps.SnapPermlink
import com.rustedwax.app.storage.db.LocalOwner
import com.rustedwax.hive.HiveCommentRead
import com.rustedwax.hive.SnapContainerResolver
import org.json.JSONArray
import org.json.JSONObject

/**
 * My Snaps discovery, Stage 47B (Issue #47): finding the account's RustedWax
 * root Snaps on Hive, including ones this installation never published.
 *
 * Reads only. Every function here answers from what a Hive node returned and
 * nothing here can sign, send, edit, delete or vote. The source is Hivemind's
 * `bridge.get_account_posts` with `sort = "comments"`, whose behaviour was
 * verified against the live chain on 2026-10-04:
 *
 *  - it lists only comments the account **authored**, and omits deleted ones;
 *  - a page is at most **20**, and a continued page repeats its cursor first;
 *  - it is **not** strictly ordered by `created` (inversions of ~50 minutes),
 *    and nodes order ties differently — so one pass never moves its cursor
 *    from one node to another.
 */
internal data class ChainCursor(val author: String, val permlink: String)

/**
 * Which chain objects are RustedWax root Snaps of an account.
 *
 * Every piece of evidence must hold, and they must agree; anything missing,
 * malformed or contradictory is not imported. Snaps from other frontends, and
 * RustedWax Snaps whose metadata another frontend has since rewritten, are
 * left out on purpose: the rule is "positively RustedWax", not "probably".
 */
internal object MySnapsIdentity {

	const val APP_PREFIX = "rustedwax/"

	/**
	 * No RustedWax root Snap can be older than this (2026-09-01T00:00:00Z):
	 * root Snap publication first landed on 2026-09-17, and the account's
	 * first one on chain is from 2026-09-20. Discovery stops paging here.
	 */
	const val FLOOR_EPOCH_SEC = 1_788_220_800L

	private val ROOT_PERMLINK = Regex("^${SnapPermlink.SNAP_PREFIX}-(\\d+)-[a-z0-9]{6}$")

	/**
	 * A catalog row for [o] when it is positively this account's RustedWax root Snap, else null.
	 *
	 * [rustedWaxApp] false drops only the `json_metadata.app` test (Stage 47E):
	 * another frontend's edit rewrites that field. Such an answer may only
	 * update the words of a Snap the catalog **already** holds under this exact
	 * identity — never import one — and every other check still applies,
	 * the intact frozen tail included.
	 */
	fun root(account: String, o: JSONObject, rustedWaxApp: Boolean = true): MySnapRow? {
		val owner = LocalOwner.account(account) ?: return null
		val author = o.optString("author")
		if (author != owner) return null
		val permlink = o.optString("permlink")
		val minted = ROOT_PERMLINK.matchEntire(permlink)?.groupValues?.get(1)?.toLongOrNull() ?: return null
		if (minted < FLOOR_EPOCH_SEC) return null
		if (o.optString("parent_author") != SnapContainerResolver.SNAP_CONTAINER_ACCOUNT) return null
		if (!SnapContainerResolver.CONTAINER_PERMLINK.matches(o.optString("parent_permlink"))) return null
		// A reply to a Snap is depth 2 or more; only the container's direct child is a root.
		when (val depth = o.opt("depth")) {
			is Int -> if (depth != 1) return null
			is Long -> if (depth != 1L) return null
			else -> return null
		}
		if (rustedWaxApp && !app(o).startsWith(APP_PREFIX)) return null
		val body = o.optString("body")
		// The frozen tail RustedWax writes, intact: the words before it and the
		// video it links are both read from it, never guessed.
		val text = PostedSnapBody.userText(body) ?: return null
		val videoId = PostedSnapBody.generatedUrl(body)
			?.removePrefix(SnapPayloadBuilder.canonicalUrl(""))
			?.takeIf { it.isNotEmpty() }
			?: return null
		val created = ChainTime.epochSec(o.optString("created")) ?: return null
		return MySnapRow(
			owner = owner,
			author = author,
			permlink = permlink,
			createdAtEpochSec = created,
			eventId = null,
			videoId = videoId,
			title = null,
			artist = null,
			service = null,
			userText = text,
		)
	}

	/** `json_metadata.app`, whether the node sent an object or a string; "" when unreadable. */
	private fun app(o: JSONObject): String {
		val metadata = when (val m = o.opt("json_metadata")) {
			is JSONObject -> m
			is String -> runCatching { JSONObject(m) }.getOrNull()
			else -> null
		}
		return metadata?.opt("app") as? String ?: ""
	}

	/**
	 * Hive proves this exact object gone: at least two current nodes answered
	 * that it is absent, and none that it is present — the same proof
	 * [com.rustedwax.app.snaps.SnapDeleter] accepts without block evidence. An
	 * empty list (offline, stale nodes, failed reads) proves nothing.
	 */
	fun provenAbsent(reads: List<HiveCommentRead>): Boolean = absence(reads) == true

	/**
	 * The same reads as a three-way answer: true when [provenAbsent], false when
	 * a current node has the object, null when too few current nodes answered
	 * to decide either way.
	 */
	fun absence(reads: List<HiveCommentRead>): Boolean? = when {
		reads.any { it is HiveCommentRead.Present } -> false
		reads.filterIsInstance<HiveCommentRead.Absent>().map { it.node }.toSet().size >= 2 -> true
		else -> null
	}
}

/**
 * How far discovery has got for one account — the `my_snaps_catalog`
 * checkpoint. Its own scope, never History's refresh stamp.
 *
 * @param highWater the newest `created` (epoch seconds) a completed scan from
 *   the top has covered; later passes scan down to a little before it.
 * @param deepDone the scan has reached [MySnapsIdentity.FLOOR_EPOCH_SEC] or
 *   the end of the account's comments, so older pages need no reading again.
 * @param resumeNode / [resume] where an unfinished deep scan stopped, valid
 *   only on that node.
 * @param verifyAt / [verifyPermlink] the catalog row the rotating exact-read
 *   check reached (newest first); null starts it again from the top.
 */
internal data class CatalogCheckpoint(
	val highWater: Long? = null,
	val deepDone: Boolean = false,
	val resumeNode: String? = null,
	val resume: ChainCursor? = null,
	val lastSuccessMs: Long? = null,
	val verifyAt: Long? = null,
	val verifyPermlink: String? = null,
	/**
	 * Stage 47E follow-up: where a manual refresh's exact reads of older
	 * discovered rows stopped (newest first); null starts the next one at the top.
	 */
	val contentAt: Long? = null,
	val contentPermlink: String? = null,
) {
	fun encode(): String = JSONObject()
		.put("v", 1)
		.put("highWater", highWater ?: JSONObject.NULL)
		.put("deepDone", deepDone)
		.put("resumeNode", resumeNode ?: JSONObject.NULL)
		.put("resume", resume?.let { JSONArray().put(it.author).put(it.permlink) } ?: JSONObject.NULL)
		.put("lastSuccessMs", lastSuccessMs ?: JSONObject.NULL)
		.put("verifyAt", verifyAt ?: JSONObject.NULL)
		.put("verifyPermlink", verifyPermlink ?: JSONObject.NULL)
		.put("contentAt", contentAt ?: JSONObject.NULL)
		.put("contentPermlink", contentPermlink ?: JSONObject.NULL)
		.toString()

	companion object {
		const val SCOPE = "my_snaps_catalog"

		/** An absent or unreadable checkpoint is a fresh start: a full scan, never a skipped one. */
		fun decode(raw: String?): CatalogCheckpoint = raw?.let {
			runCatching {
				val o = JSONObject(it)
				if (o.optInt("v") != 1) return@runCatching null
				fun long(name: String) = if (o.isNull(name)) null else o.getLong(name)
				val verifyAt = long("verifyAt")
				val verifyPermlink = if (o.isNull("verifyPermlink")) null else o.getString("verifyPermlink")
				val contentAt = if (o.has("contentAt")) long("contentAt") else null
				val contentPermlink = if (o.has("contentPermlink") && !o.isNull("contentPermlink")) o.getString("contentPermlink") else null
				val resume = if (o.isNull("resume")) null else o.getJSONArray("resume").let { a ->
					ChainCursor(a.getString(0), a.getString(1))
				}
				CatalogCheckpoint(
					highWater = long("highWater"),
					deepDone = o.optBoolean("deepDone"),
					resumeNode = if (o.isNull("resumeNode")) null else o.getString("resumeNode"),
					resume = resume,
					lastSuccessMs = long("lastSuccessMs"),
					// Both or neither: half a cursor restarts the rotation.
					verifyAt = verifyAt.takeIf { verifyPermlink != null },
					verifyPermlink = verifyPermlink.takeIf { verifyAt != null },
					contentAt = contentAt.takeIf { contentPermlink != null },
					contentPermlink = contentPermlink.takeIf { contentAt != null },
				)
			}.getOrNull()
		} ?: CatalogCheckpoint()
	}
}

/**
 * One bounded discovery pass: the newest pages down to a little before the
 * last scan's high water, then — until it is done — more of the deep scan
 * toward [MySnapsIdentity.FLOOR_EPOCH_SEC].
 *
 * A pass runs on one node from start to finish. A failed page abandons that
 * node's pass and the next node starts again from the top; when every node
 * fails the pass fails and nothing it saw is used.
 */
internal class MySnapsDiscovery(
	private val nodes: List<String>,
	/** One page of the account's comments from [node], after [after] when given. Throws on failure. */
	private val page: (node: String, account: String, after: ChainCursor?) -> List<JSONObject>,
	private val pageBudget: Int = PAGE_BUDGET,
) {

	sealed interface Result {
		/**
		 * @param found positively identified RustedWax root Snaps.
		 * @param seen every `author/permlink` the pass was shown, qualifying or not.
		 * @param coveredFrom / [coveredTo] the `created` span scanned without a gap
		 *   from the top down; null when the account had none. The top is "now":
		 *   every pass starts at the newest comment, so anything newer than the
		 *   first one listed is simply not listed.
		 */
		data class Done(
			val found: List<MySnapRow>,
			val seen: Set<String>,
			val coveredFrom: Long?,
			val coveredTo: Long?,
			val checkpoint: CatalogCheckpoint,
			/**
			 * Stage 47E: objects that pass every check but the RustedWax app tag —
			 * a Snap another frontend edited. Words for rows already in the
			 * catalog only; never imported.
			 */
			val known: List<MySnapRow> = emptyList(),
		) : Result

		data class Failed(val reason: String) : Result
	}

	fun pass(account: String, checkpoint: CatalogCheckpoint, nowMs: Long): Result {
		var last: Exception? = null
		for (node in nodes) {
			try {
				return run(node, account, checkpoint, nowMs)
			} catch (e: Exception) {
				last = e
			}
		}
		return Result.Failed("no Hive node answered: ${last?.message ?: "no nodes"}")
	}

	private class Page(val items: List<JSONObject>, val last: Boolean)

	private fun run(node: String, account: String, checkpoint: CatalogCheckpoint, nowMs: Long): Result.Done {
		val found = LinkedHashMap<String, MySnapRow>()
		val seen = HashSet<String>()
		val known = LinkedHashMap<String, MySnapRow>()
		var budget = pageBudget

		fun fetch(after: ChainCursor?): Page? {
			if (budget <= 0) return null
			budget--
			val raw = page(node, account, after)
			val items = if (after != null && raw.firstOrNull()?.let { cursorOf(it) } == after) raw.drop(1) else raw
			return Page(items, raw.size < PAGE_SIZE)
		}

		fun take(items: List<JSONObject>): LongRange? {
			var newest: Long? = null
			var oldest: Long? = null
			items.forEach { o ->
				val id = "${o.optString("author")}/${o.optString("permlink")}"
				seen += id
				val strict = MySnapsIdentity.root(account, o)
				if (strict != null) {
					found[id] = strict
				} else {
					MySnapsIdentity.root(account, o, rustedWaxApp = false)?.let { known[id] = it }
				}
				ChainTime.epochSec(o.optString("created"))?.let { at ->
					newest = maxOf(newest ?: at, at)
					oldest = minOf(oldest ?: at, at)
				}
			}
			return oldest?.let { it..newest!! }
		}

		// ── The top of the account, down to a little before the last high water.
		val headStop = checkpoint.highWater?.let { it - OVERLAP_SEC } ?: MySnapsIdentity.FLOOR_EPOCH_SEC
		var cursor: ChainCursor? = null
		var newest: Long? = null
		var oldest: Long? = null
		var headDone = false
		var reachedFloor = false
		while (true) {
			val p = fetch(cursor) ?: break
			val span = take(p.items)
			span?.let {
				newest = maxOf(newest ?: it.last, it.last)
				oldest = minOf(oldest ?: it.first, it.first)
			}
			p.items.lastOrNull()?.let { cursor = cursorOf(it) }
			if (p.last || p.items.isEmpty()) {
				headDone = true
				reachedFloor = true
				break
			}
			if (span != null && span.last < headStop) {
				headDone = true
				reachedFloor = headStop <= MySnapsIdentity.FLOOR_EPOCH_SEC
				break
			}
		}
		val coveredFrom = oldest
		val coveredTo = newest?.let { maxOf(it, nowMs / 1000) }
		val highWater = listOfNotNull(checkpoint.highWater, newest).maxOrNull()

		if (!headDone) {
			// Out of pages before the top was covered: everything below the cursor
			// becomes deep scan, resumed from here on this node next time.
			return Result.Done(
				found.values.toList(), seen, coveredFrom, coveredTo,
				CatalogCheckpoint(highWater, false, node, cursor, nowMs),
				known.values.toList(),
			)
		}
		if (reachedFloor || checkpoint.deepDone) {
			return Result.Done(
				found.values.toList(), seen, coveredFrom, coveredTo,
				CatalogCheckpoint(highWater, true, null, null, nowMs),
				known.values.toList(),
			)
		}

		// ── More of the deep scan, from where it stopped if that was on this node.
		var deep = if (checkpoint.resumeNode == node && checkpoint.resume != null) checkpoint.resume else cursor
		var deepDone = false
		while (true) {
			val p = fetch(deep) ?: break
			val span = take(p.items)
			p.items.lastOrNull()?.let { deep = cursorOf(it) }
			if (p.last || p.items.isEmpty() || (span != null && span.last < MySnapsIdentity.FLOOR_EPOCH_SEC)) {
				deepDone = true
				break
			}
		}
		return Result.Done(
			found.values.toList(), seen, coveredFrom, coveredTo,
			if (deepDone) CatalogCheckpoint(highWater, true, null, null, nowMs)
			else CatalogCheckpoint(highWater, false, node, deep, nowMs),
			known.values.toList(),
		)
	}

	private fun cursorOf(o: JSONObject) = ChainCursor(o.optString("author"), o.optString("permlink"))

	companion object {
		/** Hivemind's own maximum for `sort = "comments"`. */
		const val PAGE_SIZE = 20

		/** Pages per pass: 240 comments. */
		const val PAGE_BUDGET = 12

		/** How far below the last high water a later pass re-reads: far beyond the ~50-minute inversions seen live. */
		const val OVERLAP_SEC = 6 * 60 * 60L
	}
}
