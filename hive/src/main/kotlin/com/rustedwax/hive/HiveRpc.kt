package com.rustedwax.hive

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal `condenser_api` client with node failover.
 *
 * Node list copied from the extension's `posting-key-verify.ts`, so the phone
 * talks to the same infrastructure the desktop extension already trusts.
 * Deliberately dependency-free (HttpURLConnection + org.json): the whole
 * `hive/` package stays pure JVM so it can be unit-tested without a device.
 */
class HiveRpc(private val nodes: List<String> = DEFAULT_NODES) {

	class RpcException(message: String) : Exception(message)

	/** Result of a broadcast attempt, kept structured for UI error reporting. */
	sealed interface BroadcastResult {
		enum class Evidence {
			/** An independent healthy node found the transaction in a block. */
			BLOCK,

			/** An independent healthy node found it relaying in its mempool. */
			MEMPOOL,
		}

		data class Success(
			val txId: String,
			val node: String,
			val evidence: Evidence,
		) : BroadcastResult

		/**
		 * The accepting node returned success, but no independent healthy node
		 * could answer. Kept distinct from both confirmation and failure: retrying
		 * an accepted transaction could create a permanent duplicate.
		 */
		data class AcceptedUnconfirmed(
			val txId: String?,
			val node: String,
			val message: String,
		) : BroadcastResult

		/** The chain rejected it for a reason that won't change on retry. */
		data class Rejected(val message: String) : BroadcastResult

		/**
		 * Refused for a reason that is expected to pass later — a per-block
		 * custom_json rate limit, a node that had a moment. Distinguished from
		 * [Rejected] because the caller must **queue** these, not discard them.
		 */
		data class Deferred(val message: String) : BroadcastResult

		/** Every node failed for transport reasons — retrying might help. */
		data class NetworkFailure(val message: String) : BroadcastResult
	}

	data class GlobalProperties(
		val headBlockNumber: Long,
		val headBlockId: String,
		/** Chain time, not device time — expiration must be relative to this. */
		val timeEpochSec: Long,
	)

	/** Durable-queue reconciliation result for a transaction prepared earlier. */
	enum class TransactionEvidence {
		BLOCK,
		MEMPOOL,
		/** Independent nodes proved the transaction expired beyond irreversibility without inclusion. */
		ABSENT,
		/** Too little independent evidence to decide safely. */
		UNAVAILABLE,
	}

	/**
	 * Head block and chain time, **from a node that is actually current**.
	 *
	 * Freshness is enforced here and not only at broadcast time, because this call
	 * is what the transaction is *built* from. A stalled node hands back an old
	 * `time`, and `expiration = time + 60s` computed from it can already be in the
	 * past by the time a healthy node sees the transaction — so the frozen node
	 * would have poisoned the transaction even if something else broadcast it.
	 */
	fun getDynamicGlobalProperties(): GlobalProperties {
		var lastError: String? = null
		for (node in nodes) {
			val props = runCatching {
				val result = post(node, "condenser_api.get_dynamic_global_properties", JSONArray())
					.optJSONObject("result") ?: throw RpcException("unexpected DGP response")
				GlobalProperties(
					headBlockNumber = result.getLong("head_block_number"),
					headBlockId = result.getString("head_block_id"),
					timeEpochSec = parseChainTime(result.getString("time")),
				)
			}.getOrElse {
				lastError = "${node.substringAfter("//")}: ${it.message}"
				null
			} ?: continue

			val lag = System.currentTimeMillis() / 1000 - props.timeEpochSec
			if (lag > MAX_NODE_LAG_SEC) {
				lastError = "${node.substringAfter("//")}: ${lag}s behind the chain"
				continue
			}
			return props
		}
		throw RpcException("no node had a current head block (${lastError ?: "none reachable"})")
	}

	/**
	 * The account's posting public keys, for validating a key the user typed.
	 * Ported from `fetchPostingPubkeys` in `posting-key-verify.ts`.
	 */
	fun getPostingPublicKeys(username: String): List<String> {
		val params = JSONArray().put(JSONArray().put(username))
		val result = call("condenser_api.get_accounts", params) as? JSONArray
			?: throw RpcException("unexpected get_accounts response")
		if (result.length() == 0) return emptyList()
		val auths = result.getJSONObject(0)
			.getJSONObject("posting")
			.getJSONArray("key_auths")
		return (0 until auths.length()).mapNotNull { i ->
			auths.optJSONArray(i)?.optString(0)?.takeIf { it.isNotBlank() }
		}
	}

	/**
	 * Posts authored by an account, via Hivemind's `bridge` API.
	 *
	 * `sort` matters more than it looks: the Snap containers RustedWax needs are
	 * returned by **`posts`** and not by `blog`, which answers with an empty list
	 * for `@peak.snaps`. Verified against the live chain on 2026-09-17.
	 */
	fun getAccountPosts(
		account: String,
		sort: String,
		limit: Int,
		/**
		 * Continue after this `author/permlink` (Issue #47). Hivemind answers
		 * with the cursor object itself first, and a page is at most 20 for
		 * `sort = "comments"`; both verified live on 2026-10-04.
		 */
		startAuthor: String? = null,
		startPermlink: String? = null,
	): List<JSONObject> {
		val params = JSONObject()
			.put("sort", sort)
			.put("account", account)
			.put("limit", limit)
		if (startAuthor != null && startPermlink != null) {
			params.put("start_author", startAuthor).put("start_permlink", startPermlink)
		}
		val result = callObject("bridge.get_account_posts", params) as? JSONArray
			?: throw RpcException("unexpected get_account_posts response")
		return (0 until result.length()).mapNotNull { result.optJSONObject(it) }
	}

	/**
	 * Every comment in the discussion rooted at `author/permlink`, that root
	 * included.
	 *
	 * `bridge.get_discussion` answers with a **map** keyed by `author/permlink`
	 * rather than a list, and each entry carries its own `parent_author` and
	 * `parent_permlink`. Only the values are returned here: the keys are
	 * redundant with the objects, and trusting a key that disagreed with the
	 * object under it would be choosing which of two untrusted strings to
	 * believe.
	 *
	 * Deliberately one call rather than a recursive walk of
	 * `get_content_replies`. A tree fetched a level at a time is a tree whose
	 * depth decides how many round trips a reply thread costs, and an untrusted
	 * `replies` array would be deciding how many.
	 *
	 * Every object in the returned list is **untrusted chain content**. Nothing
	 * here validates a field; that is the caller's job, and the caller does it
	 * before any of this reaches a screen.
	 */
	fun getDiscussion(author: String, permlink: String): List<JSONObject> {
		val params = JSONObject().put("author", author).put("permlink", permlink)
		val result = callObject("bridge.get_discussion", params) as? JSONObject
			?: throw RpcException("unexpected get_discussion response")
		return result.keys().asSequence().mapNotNull { result.optJSONObject(it) }.toList()
	}

	/**
	 * One comment or post by `author/permlink`, or null when the chain has none.
	 *
	 * This is the *content* half of Snap reconciliation. Transaction evidence
	 * alone cannot clear an ambiguous Snap: a transaction that expired without
	 * inclusion and a transaction that was never sent look identical, while the
	 * comment either exists under that permlink or it does not. Hive returns a
	 * populated object with an empty `author` for content it does not have.
	 */
	fun getContent(author: String, permlink: String): JSONObject? {
		val params = JSONArray().put(author).put(permlink)
		val result = call("condenser_api.get_content", params) as? JSONObject ?: return null
		return result.takeIf { it.optString("author").isNotBlank() }
	}

	/**
	 * The signed-in account's current vote on one comment, **from a node proven
	 * current at the moment it answered**.
	 *
	 * This is the safety read a Like is gated on, and it is deliberately not
	 * built out of [call]/[callAny] like every other read in this class.
	 * [callAny] walks the node list and returns the first answer it gets, with
	 * no freshness check at all — which is right for the reads it serves, where
	 * a slightly stale comment body costs nothing. It is wrong here. A node that
	 * stopped following the chain an hour ago answers "no vote" with total
	 * confidence for a vote cast fifty minutes ago on another frontend, and
	 * acting on that answer is precisely the overwrite Stage 5 exists to
	 * prevent.
	 *
	 * So the rule is **per node, and both halves against the same node**: ask
	 * node N how far behind it is, and only if N is within [MAX_NODE_LAG_SEC]
	 * ask N for the votes. Checking one node's clock and then believing a
	 * different node's answer would be a freshness check in name only. A node
	 * that is stale, unreachable, or answers with something that is not a vote
	 * list is skipped and the next one is tried from the top, lag check
	 * included.
	 *
	 * When no node manages both halves the answer is
	 * [HiveVoteRead.Unavailable], which authorizes nothing. There is no
	 * fall-through to an unchecked node and there must never be one.
	 *
	 * `condenser_api.get_active_votes` is the current call for this question and
	 * the one this read uses. It answers with a **top-level array** of
	 * `{time, voter, weight, percent, rshares, reputation}`, and with an empty
	 * array — a real, usable "nobody voted" — for an unvoted comment. Verified
	 * identical across all four [DEFAULT_NODES] on 2026-09-19.
	 */
	fun findViewerVote(author: String, permlink: String, voter: String): HiveVoteRead {
		val params = JSONArray().put(author).put(permlink)
		return findViewerVoteAcross(
			voter = voter,
			lagOf = { node -> chainLagSeconds(node) },
			votesFrom = { node -> activeVotesFrom(node, params) },
		)
	}

	/**
	 * One node's `get_active_votes` answer, or null if it did not give one.
	 *
	 * Null covers every way this can fail to be an answer — a transport error, a
	 * JSON-RPC `error`, a missing `result`, a `result` that is not an array —
	 * and all of them mean "ask somebody else", never "there are no votes". The
	 * empty array is a different thing entirely and is returned as itself.
	 */
	private fun activeVotesFrom(node: String, params: JSONArray): JSONArray? = runCatching {
		val response = post(node, "condenser_api.get_active_votes", params)
		if (response.optJSONObject("error") != null) return@runCatching null
		response.opt("result") as? JSONArray
	}.getOrNull()

	/**
	 * The node-selection rule on its own, with the network lifted out.
	 *
	 * Internal, and shaped this way so a test can prove the property that
	 * matters and cannot be observed from the outside: that [votesFrom] is only
	 * ever called for a node [lagOf] has just cleared, and always for *that*
	 * node. A comment claiming as much would be worth nothing — this is the one
	 * rule whose violation looks identical to correct behaviour until the day a
	 * node stalls.
	 */
	internal fun findViewerVoteAcross(
		voter: String,
		lagOf: (String) -> Long?,
		votesFrom: (String) -> JSONArray?,
	): HiveVoteRead {
		var lastProblem: String? = null
		for (node in nodes) {
			val name = node.substringAfter("//")
			// First, and against this node. Its own answer below is worthless
			// until this passes, so nothing else happens for this node yet.
			val age = lagOf(node)
			if (age == null || age > MAX_NODE_LAG_SEC) {
				lastProblem = "$name: " + (age?.let { "${it}s behind the chain" }
					?: "no usable head block")
				continue
			}
			// Same node, immediately after. The vote list and the proof of
			// freshness have to come from one machine or the proof is about
			// somebody else.
			val votes = votesFrom(node)
			if (votes == null) {
				lastProblem = "$name: no usable vote list"
				continue
			}
			val vote = HiveVotes.fromActiveVotes(votes, voter)
			// A node that answered with something unreadable has not answered
			// the question. Another node may hold the same votes in a form this
			// can parse, so the list is continued rather than the parser's
			// refusal being handed back as though it were the chain's verdict.
			if (vote is ViewerVote.Unreadable) {
				lastProblem = "$name: ${vote.reason}"
				continue
			}
			return HiveVoteRead.Fresh(vote, node)
		}
		return HiveVoteRead.Unavailable(
			"couldn't read your current vote from an up-to-date Hive node " +
				"(${lastProblem ?: "none reachable"})",
		)
	}

	/**
	 * Up to [limit] answers about one comment, each from a **different node
	 * proven current at the moment it answered**.
	 *
	 * The read a deletion is gated on, before signing and after broadcasting,
	 * built on the same per-node rule as [findViewerVote]: ask node N for its
	 * head block, and only if N is within [MAX_NODE_LAG_SEC] ask N for the
	 * comment. A stale node can report a deleted comment as present or a new
	 * reply as missing with total confidence, so nothing here falls through to
	 * an unchecked node. An empty list means nobody current could answer.
	 */
	fun readCommentState(author: String, permlink: String, limit: Int = 1): List<HiveCommentRead> {
		val params = HiveCommentStates.params(author, permlink)
		return readCommentStateAcross(
			author = author,
			permlink = permlink,
			limit = limit,
			headOf = { node -> nodeHeadEpochSec(node) },
			commentsFrom = { node ->
				runCatching {
					val response = post(node, "database_api.find_comments", params)
					if (response.optJSONObject("error") != null) null
					else response.optJSONObject("result")
				}.getOrNull()
			},
		)
	}

	internal fun readCommentStateAcross(
		author: String,
		permlink: String,
		limit: Int,
		headOf: (String) -> Long?,
		commentsFrom: (String) -> JSONObject?,
		nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
	): List<HiveCommentRead> {
		val answers = mutableListOf<HiveCommentRead>()
		for (node in nodes) {
			if (answers.size >= limit) break
			val head = headOf(node) ?: continue
			if (head !in (nowEpochSec() - MAX_NODE_LAG_SEC)..(nowEpochSec() + MAX_NODE_LAG_SEC)) continue
			val comments = commentsFrom(node)
			// A head near the age limit can expire while the comment request runs.
			if (head !in (nowEpochSec() - MAX_NODE_LAG_SEC)..(nowEpochSec() + MAX_NODE_LAG_SEC)) continue
			val read = HiveCommentStates.parse(
				comments,
				author,
				permlink,
				head,
				node.substringAfter("//"),
			) ?: continue
			answers += read
		}
		return answers
	}

	/**
	 * One comment, read from a node that has **caught up** at the moment of the
	 * read — the read an edit is signed from and settled against (Issue #56).
	 *
	 * [readCommentState] accepts any node within [MAX_NODE_LAG_SEC], which is
	 * right for deciding whether something may be deleted and wrong for copying
	 * an object back verbatim: a node 45 seconds behind still shows a comment
	 * another frontend has just changed or deleted, and an edit built from it
	 * would overwrite the newer words or recreate the deleted permlink.
	 *
	 * So the moment the read starts is fixed first, by this device's clock, and
	 * every node's head is read after it. The answer must come from one node
	 * whose own head has reached that start and is within a block of the newest
	 * current head — head and content from the same node, as everywhere else in
	 * this class. Lagging nodes are skipped, never used as a fallback, and the
	 * start is never lowered because every node trails; with no such node there
	 * is one short wait and one more try, then null. Heads outside
	 * [MAX_NODE_LAG_SEC] of the clock are not evidence at all. Narrowed to this
	 * read: nothing else changes its freshness rule.
	 */
	fun readCommentCaughtUp(author: String, permlink: String): HiveCommentRead? {
		val params = HiveCommentStates.params(author, permlink)
		return readCommentCaughtUpAcross(
			author = author,
			permlink = permlink,
			headOf = { node -> nodeHeadEpochSec(node) },
			commentsFrom = { node ->
				val response = post(node, "database_api.find_comments", params)
				if (response.optJSONObject("error") != null) null else response.optJSONObject("result")
			},
		)
	}

	internal fun readCommentCaughtUpAcross(
		author: String,
		permlink: String,
		headOf: (String) -> Long?,
		commentsFrom: (String) -> JSONObject?,
		nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
		sleep: (Long) -> Unit = { Thread.sleep(it) },
	): HiveCommentRead? {
		// Fixed before any node is asked, and never lowered: a node that has not
		// reached the moment this read began cannot have seen a change made just
		// before it, however far behind every other node is too.
		val start = nowEpochSec()
		repeat(CAUGHT_UP_ATTEMPTS) { attempt ->
			if (attempt > 0) sleep(CAUGHT_UP_RETRY_MS)
			val now = nowEpochSec()
			// Every head first, so a lagging node cannot become the fence by
			// being the only one whose comment answer arrived.
			val heads = nodes.associateWith { node ->
				runCatching { headOf(node) }.getOrNull()
					?.takeIf { it in (now - MAX_NODE_LAG_SEC)..(now + MAX_NODE_LAG_SEC) }
			}
			val fence = heads.values.filterNotNull().maxOrNull() ?: return@repeat
			for (node in nodes) {
				val head = heads[node] ?: continue
				if (head < start || head < fence - BLOCK_SECONDS) continue
				val comments = runCatching { commentsFrom(node) }.getOrNull()
				HiveCommentStates.parse(comments, author, permlink, head, node.substringAfter("//"))
					?.let { return it }
			}
		}
		return null
	}

	private fun nodeHeadEpochSec(node: String): Long? = runCatching {
		val result = post(node, "condenser_api.get_dynamic_global_properties", JSONArray())
			.optJSONObject("result") ?: return null
		ChainTimes.epochSec(result.getString("time"))
	}.getOrNull()

	fun broadcast(
		signedTx: JSONObject,
		expectedTxId: String? = null,
		sleep: (Long) -> Unit = { Thread.sleep(it) },
	): BroadcastResult {
		val params = JSONArray().put(signedTx)
		var lastTransport: String? = null
		var lastSkipped: String? = null

		for (node in nodes) {
			try {
				// Cheap and first: a stale node's answers are worthless, and this
				// is one round-trip against seven lost listens.
				val age = chainLagSeconds(node)
				if (age == null || age > MAX_NODE_LAG_SEC) {
					lastSkipped = "${node.substringAfter("//")}: " +
						(age?.let { "${it}s behind the chain" } ?: "no usable head block")
					continue
				}

				val response = post(node, "condenser_api.broadcast_transaction", params)
				response.optJSONObject("error")?.let { err ->
					val message = errorMessage(err)
					return if (isTransient(message)) {
						BroadcastResult.Deferred(message)
					} else {
						BroadcastResult.Rejected(message)
					}
				}

				val result = response.opt("result")
				val txId = (result as? JSONObject)?.optString("id")?.takeIf { it.isNotBlank() }
					?: expectedTxId
					?: return BroadcastResult.AcceptedUnconfirmed(
						txId = null,
						node = node,
						message = "accepting node returned no transaction id to confirm",
					)

				return when (confirm(txId, acceptingNode = node, sleep = sleep)) {
					Confirmation.BLOCK -> BroadcastResult.Success(
						txId,
						node,
						BroadcastResult.Evidence.BLOCK,
					)
					Confirmation.MEMPOOL -> BroadcastResult.Success(
						txId,
						node,
						BroadcastResult.Evidence.MEMPOOL,
					)
					// Accepted and then never included. Treated as deferred rather
					// than rejected: the operation is still owed, and the queue is
					// what gets it there.
					Confirmation.NOT_FOUND -> BroadcastResult.Deferred(
						"accepted by ${node.substringAfter("//")} but not in a block " +
							"after ${CONFIRM_ATTEMPTS * CONFIRM_INTERVAL_MS / 1000}s",
					)
					// Couldn't ask. Reported as success rather than invented as a
					// failure — but explicitly *unconfirmed*. The transaction may
					// have landed, and queuing it would risk a duplicate.
					Confirmation.UNAVAILABLE -> BroadcastResult.AcceptedUnconfirmed(
						txId = txId,
						node = node,
						message = "no independent healthy confirmation node answered",
					)
				}
			} catch (e: Exception) {
				lastTransport = "${node.substringAfter("//")}: ${e.message}"
			}
		}
		return BroadcastResult.NetworkFailure(
			lastTransport ?: lastSkipped ?: "all nodes unreachable",
		)
	}

	/**
	 * How far behind the chain a node is, in seconds, or null if it can't say.
	 *
	 * Compared against the **device** clock on purpose. Asking a stalled node
	 * whether it is stalled is circular — its own head time is exactly the value
	 * that stopped moving. Phone clocks are network-synced to within seconds, so a
	 * generous [MAX_NODE_LAG_SEC] absorbs ordinary drift while still catching a
	 * node minutes or hours adrift.
	 */
	private fun chainLagSeconds(node: String): Long? = runCatching {
		val result = post(node, "condenser_api.get_dynamic_global_properties", JSONArray())
			.optJSONObject("result") ?: return null
		val head = parseChainTime(result.getString("time"))
		System.currentTimeMillis() / 1000 - head
	}.getOrNull()

	private enum class Confirmation { BLOCK, MEMPOOL, NOT_FOUND, UNAVAILABLE }

	private enum class TransactionObservation { BLOCK, MEMPOOL, UNKNOWN, UNAVAILABLE }

	/**
	 * Reconcile a transaction that may already have crossed the network boundary.
	 *
	 * Absence is deliberately stricter than ordinary post-broadcast confirmation:
	 * replacing an expired prepared transaction creates a new transaction id, so
	 * bare `unknown` is not evidence: it can also mean status tracking has not
	 * started or the node lacks the relevant history. Inclusion or mempool
	 * evidence from any healthy node wins; absence requires independent
	 * `expired_irreversible` answers obtained with the persisted expiration.
	 */
	fun observeTransaction(txId: String, expirationEpochSec: Long): TransactionEvidence =
		evidenceFromStatuses(
			transactionStatuses(
				txId,
				acceptingNode = null,
				expirationEpochSec = expirationEpochSec,
			),
		)

	/**
	 * The timestamp of the last irreversible block, when at least
	 * [MIN_FINALITY_CONFIRMATIONS] current nodes each show it is **after**
	 * [expirationEpochSec]; otherwise null (Issue #56).
	 *
	 * A transaction is applied only while its expiration is ahead of the chain,
	 * so once an irreversible block is timestamped after it, every block that
	 * could still carry it is already final: it **can no longer be included**.
	 * That is all this proves. It says nothing about whether it was included
	 * earlier, so it is not [TransactionEvidence.ABSENT], and callers decide
	 * that from the object itself. Unlike `transaction_status_api`, whose
	 * history nodes keep for about two days, it never ages out.
	 *
	 * Per node, from one properties answer: the node must be current, its
	 * irreversible block number a positive integer no higher than its head, and
	 * that block's header must name its predecessor and carry a timestamp no
	 * later than the head's. Any node that fails one of those is skipped, never
	 * counted. Chain time throughout; the device clock only judges freshness.
	 */
	fun irreversiblyPast(expirationEpochSec: Long): Long? = irreversiblyPastAcross(
		expirationEpochSec = expirationEpochSec,
		propertiesOf = { node ->
			post(node, "condenser_api.get_dynamic_global_properties", JSONArray()).optJSONObject("result")
		},
		headerOf = { node, blockNum ->
			post(node, "block_api.get_block_header", JSONObject().put("block_num", blockNum))
				.optJSONObject("result")
				?.optJSONObject("header")
		},
	)

	internal fun irreversiblyPastAcross(
		expirationEpochSec: Long,
		propertiesOf: (String) -> JSONObject?,
		headerOf: (String, Long) -> JSONObject?,
		nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
	): Long? {
		// One proof per endpoint: the same node listed twice is not a second witness.
		val proofs = mutableMapOf<String, Long>()
		for (node in nodes) {
			val endpoint = node.substringAfter("//").trimEnd('/').lowercase()
			if (endpoint in proofs) continue
			runCatching { irreversibleTime(node, propertiesOf, headerOf, nowEpochSec) }.getOrNull()
				?.takeIf { it > expirationEpochSec }
				?.let { proofs[endpoint] = it }
		}
		return if (proofs.size >= MIN_FINALITY_CONFIRMATIONS) proofs.values.min() else null
	}

	/** One node's last irreversible block time, or null when it is not trustworthy evidence. */
	private fun irreversibleTime(
		node: String,
		propertiesOf: (String) -> JSONObject?,
		headerOf: (String, Long) -> JSONObject?,
		nowEpochSec: () -> Long,
	): Long? {
		val props = propertiesOf(node) ?: return null
		val headTime = ChainTimes.epochSec(props.opt("time") as? String ?: return null) ?: return null
		val now = nowEpochSec()
		if (headTime !in (now - MAX_NODE_LAG_SEC)..(now + MAX_NODE_LAG_SEC)) return null
		val head = HiveCommentStates.integer(props.opt("head_block_number")) ?: return null
		val irreversible = HiveCommentStates.integer(props.opt("last_irreversible_block_num"))
			?.takeIf { it in 1..head }
			?: return null
		val header = headerOf(node, irreversible) ?: return null
		// A block id is 20 bytes as 40 lowercase hex digits, beginning with its
		// number: the header must be whole, well formed and the one asked for.
		val previous = header.opt("previous") as? String ?: return null
		if (!BLOCK_ID.matches(previous) || previous.substring(0, 8).toLong(16) != irreversible - 1) return null
		val blockTime = ChainTimes.epochSec(header.opt("timestamp") as? String ?: return null) ?: return null
		return blockTime.takeIf { it <= headTime }
	}

	internal fun evidenceFromStatuses(statuses: List<String>): TransactionEvidence {
		return when (strongestTransactionStatus(statuses)) {
			in INCLUDED_STATUSES -> TransactionEvidence.BLOCK
			MEMPOOL_STATUS -> TransactionEvidence.MEMPOOL
			else -> if (
				statuses.size >= MIN_ABSENCE_CONFIRMATIONS &&
				statuses.all { it == EXPIRED_IRREVERSIBLE_STATUS }
			) {
				TransactionEvidence.ABSENT
			} else {
				TransactionEvidence.UNAVAILABLE
			}
		}
	}

	private fun confirm(
		txId: String,
		acceptingNode: String,
		sleep: (Long) -> Unit,
	): Confirmation {
		var sawAnswer = false
		var sawMempool = false
		repeat(CONFIRM_ATTEMPTS) {
			sleep(CONFIRM_INTERVAL_MS)
			when (transactionObservation(txId, acceptingNode)) {
				TransactionObservation.BLOCK -> return Confirmation.BLOCK
				TransactionObservation.MEMPOOL -> {
					sawAnswer = true
					sawMempool = true
				}
				TransactionObservation.UNKNOWN -> sawAnswer = true
				TransactionObservation.UNAVAILABLE -> Unit
			}
		}
		return when {
			sawMempool -> Confirmation.MEMPOOL
			sawAnswer -> Confirmation.NOT_FOUND
			else -> Confirmation.UNAVAILABLE
		}
	}

	/**
	 * Ask every independent healthy node and keep the strongest answer.
	 *
	 * Returning the first nonblank status was wrong in both directions: an early
	 * `unknown` hid a later block result, while asking the accepting node first
	 * let its private mempool masquerade as independent relay.
	 */
	private fun transactionObservation(
		txId: String,
		acceptingNode: String,
	): TransactionObservation {
		val statuses = transactionStatuses(txId, acceptingNode)
		return when (strongestTransactionStatus(statuses)) {
			null -> TransactionObservation.UNAVAILABLE
			in INCLUDED_STATUSES -> TransactionObservation.BLOCK
			MEMPOOL_STATUS -> TransactionObservation.MEMPOOL
			else -> TransactionObservation.UNKNOWN
		}
	}

	private fun transactionStatuses(
		txId: String,
		acceptingNode: String?,
		expirationEpochSec: Long? = null,
	): List<String> {
		val params = JSONObject().put("transaction_id", txId)
		expirationEpochSec?.let {
			params.put("expiration", HiveBroadcaster.formatExpiration(it))
		}
		val statuses = mutableListOf<String>()
		for (node in nodes) {
			if (acceptingNode != null && node == acceptingNode) continue
			val age = chainLagSeconds(node)
			if (age == null || age > MAX_NODE_LAG_SEC) {
				if (expirationEpochSec != null) statuses += UNAVAILABLE_STATUS
				continue
			}
			val status = runCatching {
				val response = post(node, "transaction_status_api.find_transaction", params)
				if (response.optJSONObject("error") != null) return@runCatching null
				response.optJSONObject("result")
					?.optString("status")
					?.takeIf { it.isNotBlank() }
			}.getOrNull()
			if (status != null) {
				statuses += status
			} else if (expirationEpochSec != null) {
				// Reconciliation is fail-closed across the full queried node set:
				// one transport/RPC/malformed answer makes absence mixed evidence.
				statuses += UNAVAILABLE_STATUS
			}
		}
		return statuses
	}

	/**
	 * Select a network-wide verdict without allowing node order to decide it.
	 * Internal so the aggregation rule is pinned by pure JVM tests.
	 */
	internal fun strongestTransactionStatus(statuses: List<String>): String? =
		statuses.firstOrNull { it in INCLUDED_STATUSES }
			?: statuses.firstOrNull { it == MEMPOOL_STATUS }
			?: statuses.firstOrNull()

	/**
	 * Refusals that a later attempt is expected to clear.
	 *
	 * Kept to shapes that are unambiguously about *timing and capacity* rather
	 * than about the transaction being wrong. Everything else stays [Rejected] —
	 * a bad signature or a missing authority will fail identically forever, and
	 * retrying it would loop.
	 */
	internal fun isTransient(message: String): Boolean {
		val m = message.lowercase()
		return TRANSIENT_MARKERS.any { it in m }
	}

	// ── internals ──────────────────────────────────────────────────────

	private fun callObject(method: String, params: JSONObject): Any? = callAny(method, params)

	private fun call(method: String, params: JSONArray): Any? = callAny(method, params)

	private fun callAny(method: String, params: Any): Any? {
		var lastError: Exception? = null
		for (node in nodes) {
			try {
				val response = post(node, method, params)
				response.optJSONObject("error")?.let {
					throw RpcException(errorMessage(it))
				}
				return response.opt("result")
			} catch (e: Exception) {
				lastError = e
			}
		}
		throw RpcException("all nodes failed: ${lastError?.message}")
	}

	private fun post(node: String, method: String, params: Any): JSONObject {
		val body = JSONObject()
			.put("jsonrpc", "2.0")
			.put("method", method)
			.put("params", params)
			.put("id", 1)
			.toString()

		val conn = (URL(node).openConnection() as HttpURLConnection).apply {
			requestMethod = "POST"
			connectTimeout = TIMEOUT_MS
			readTimeout = TIMEOUT_MS
			doOutput = true
			setRequestProperty("Content-Type", "application/json")
		}
		try {
			conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
			val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
			val text = stream?.bufferedReader()?.use { it.readText() }
				?: throw RpcException("empty response (HTTP ${conn.responseCode})")
			return JSONObject(text)
		} finally {
			conn.disconnect()
		}
	}

	/**
	 * The most informative form of a chain error.
	 *
	 * `error.message` is the **interpolated** text. `data.stack[0].format` is the
	 * uninterpolated *template* — it still contains `${'$'}{a}`-style
	 * placeholders, and is frequently empty. Preferring the template is how the
	 * frozen-node incident logged
	 *
	 * ```
	 * rejected: Account ${'$'}{a} already submitted ${'$'}{n} custom json operation(s) this block.
	 * ```
	 *
	 * instead of the account name and the count — which would have named the
	 * stalled node's jammed block on the spot. Values live in `stack[0].data`, so
	 * they're appended when the message alone doesn't carry them.
	 */
	internal fun errorMessage(error: JSONObject): String {
		val stack = error.optJSONObject("data")?.optJSONArray("stack")?.optJSONObject(0)
		val interpolated = error.optString("message").takeIf { it.isNotBlank() }
		val template = stack?.optString("format")?.takeIf { it.isNotBlank() }
		val base = interpolated ?: template ?: error.toString()

		// Only when the text still has placeholders in it, or carries no detail at
		// all — otherwise this would append noise to an already-clear message.
		val needsValues = PLACEHOLDER.containsMatchIn(base) || interpolated == null
		val values = stack?.optJSONObject("data")?.takeIf { it.length() > 0 }
		return if (needsValues && values != null) "$base $values" else base
	}

	private fun parseChainTime(value: String): Long {
		val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
		fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
		return (fmt.parse(value)?.time ?: throw RpcException("bad chain time: $value")) / 1000
	}

	companion object {
		private const val TIMEOUT_MS = 8000

		const val MAX_NODE_LAG_SEC = 90L

		/** Blocks are 3s; this waits ~5 of them before giving up on inclusion. */
		private const val CONFIRM_ATTEMPTS = 5
		private const val CONFIRM_INTERVAL_MS = 3_000L

		/** `transaction_status_api` verdicts that mean it made it into a block. */
		private val INCLUDED_STATUSES = setOf(
			"within_reversible_block",
			"within_irreversible_block",
		)

		/**
		 * Known to a healthy node but not yet in a block. Accepted as confirmation
		 * on the final attempt — see [confirm] for why that's the safer error.
		 */
		private const val MEMPOOL_STATUS = "within_mempool"
		private const val EXPIRED_IRREVERSIBLE_STATUS = "expired_irreversible"
		private const val UNAVAILABLE_STATUS = "__unavailable__"
		private const val MIN_ABSENCE_CONFIRMATIONS = 2
		private const val MIN_FINALITY_CONFIRMATIONS = 2
		private val BLOCK_ID = Regex("[0-9a-f]{40}")

		private const val BLOCK_SECONDS = 3L
		private const val CAUGHT_UP_ATTEMPTS = 2
		private const val CAUGHT_UP_RETRY_MS = 3_000L

		private val PLACEHOLDER = Regex("""\$\{\w+\}""")

		private val TRANSIENT_MARKERS = listOf(
			"already submitted",
			"custom json operation",
			"resource credit",
			"insufficient rc",
			"too many",
			"rate limit",
		)

		val DEFAULT_NODES = listOf(
			"https://api.hive.blog",
			"https://api.deathwing.me",
			"https://api.syncad.com",
			"https://api.openhive.network",
		)
	}
}
