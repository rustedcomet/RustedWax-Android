package com.rustedwax.hive

/**
 * The `@peak.snaps` container a root Snap is posted under.
 *
 * Snaps are not top-level Hive posts. Each one is a child comment of a rolling
 * container post that `@peak.snaps` publishes every few hours, and the Snap
 * ecosystem treats "the current container" as wherever new Snaps go. A Snap
 * parented on a stale container still lands on chain but drifts out of the feed
 * everyone actually reads, so the container is resolved immediately before
 * preparing the transaction and never cached across sessions.
 */
data class SnapContainer(
	val author: String,
	val permlink: String,
	val createdIso: String,
)

/**
 * Finds the container a new Snap should be parented on.
 *
 * Two things here were established against the live chain on 2026-09-17 and are
 * easy to get wrong from the documentation alone:
 *
 *  - the containers come back under `bridge.get_account_posts` with
 *    **`sort = "posts"`**. The obvious-looking `"blog"` returns an empty list
 *    for this account, which reads exactly like "no container exists";
 *  - container permlinks are exactly `snap-container-<digits>`. That shape is a
 *    convention of whoever publishes them, so it is used to *recognise* a
 *    container and never to construct one.
 *
 * Nothing here hardcodes a particular container. [CONTAINER_PERMLINK] is a
 * sanity filter so a malformed or unrelated post cannot silently become the
 * parent of a permanent public comment — a prefix test alone was too loose,
 * since `snap-container-announcement` would have passed it.
 *
 * There is deliberately no freshness or staleness logic beyond taking the
 * newest recognised container the API returns. The API already answers
 * newest-first, and inventing an age policy here would be guessing at a
 * publication cadence RustedWax does not control.
 */
class SnapContainerResolver(
	/**
	 * How the candidate posts are fetched. A function rather than the client
	 * itself so the recognition rules below can be tested against fixtures — the
	 * `sort` value in particular is a fact about the live API that deserves a
	 * test, not a comment.
	 */
	private val fetch: (account: String, sort: String, limit: Int) -> List<org.json.JSONObject>,
) {

	constructor(rpc: HiveRpc = HiveRpc()) : this(
		{ account, sort, limit -> rpc.getAccountPosts(account, sort, limit) },
	)

	sealed interface Result {
		data class Resolved(val container: SnapContainer) : Result
		/** No usable container. Never broadcast on this — the parent would be wrong. */
		data class Unavailable(val reason: String) : Result
	}

	fun resolve(): Result {
		val posts = try {
			fetch(SNAP_CONTAINER_ACCOUNT, CONTAINER_SORT, CANDIDATES)
		} catch (e: Exception) {
			return Result.Unavailable("couldn't reach Hive to find the Snap container: ${e.message}")
		}

		// Newest first is what the API gives, and what we want; the fallback to a
		// slightly older container exists only for the minutes around a rollover
		// when the newest post might not be a container at all.
		val container = posts.asSequence()
			.mapNotNull { post ->
				val permlink = post.optString("permlink").takeIf { it.isNotBlank() }
					?: return@mapNotNull null
				if (!CONTAINER_PERMLINK.matches(permlink)) return@mapNotNull null
				val author = post.optString("author").takeIf { it == SNAP_CONTAINER_ACCOUNT }
					?: return@mapNotNull null
				SnapContainer(
					author = author,
					permlink = permlink,
					createdIso = post.optString("created"),
				)
			}
			.firstOrNull()
			?: return Result.Unavailable(
				"no current @$SNAP_CONTAINER_ACCOUNT Snap container in the latest " +
					"${posts.size} posts",
			)

		return Result.Resolved(container)
	}

	companion object {
		const val SNAP_CONTAINER_ACCOUNT = "peak.snaps"
		const val SNAP_CONTAINER_PREFIX = "snap-container-"

		/** Exactly the published shape: the prefix followed by digits, nothing else. */
		val CONTAINER_PERMLINK = Regex("^${Regex.escape(SNAP_CONTAINER_PREFIX)}\\d+$")

		/**
		 * The one that works. `"blog"` returns an empty list for this account,
		 * which is indistinguishable from "there is no container right now".
		 */
		const val CONTAINER_SORT = "posts"
		private const val CANDIDATES = 5
	}
}
