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

		/**
		 * Accepted and independently observed. [evidence] says whether the
		 * observation was block inclusion or mempool relay.
		 */
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
	 * Broadcast, then **verify the transaction reached a block**.
	 *
	 * @param expectedTxId the id computed locally from the signed transaction.
	 * Required for confirmation — `broadcast_transaction` returns an empty result,
	 * so there is nothing to confirm against otherwise.
	 * @param sleep injected so tests don't wait on real block time
	 *
	 * ## Why this doesn't just trust the RPC
	 *
	 * It used to. On 2026-07-30 `api.openhive.network` froze at block 108575690
	 * and stayed there — answering every RPC normally while 77 minutes behind the
	 * chain. It accepted five scrobbles into a pending block that would never be
	 * produced, returned no error for any of them, and the app reported five
	 * successes with locally-computed transaction ids. None existed on-chain.
	 *
	 * It then *refused* the next two with "Account ${'$'}{a} already submitted
	 * ${'$'}{n} custom json operation(s) this block" — an error that cannot
	 * legitimately fire for operations minutes apart, and the symptom that gave
	 * the stall away: the node's block never advanced, so the five stuck
	 * operations were forever "this block".
	 *
	 * A freshness check ([chainLagSeconds]) would have skipped that node, and does
	 * now. But no liveness check can cover every way a node can misbehave, so
	 * success is confirmed against a *different* node than the one that accepted
	 * it — see [confirm].
	 */
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

	/**
	 * How strongly an independent healthy node could corroborate acceptance.
	 *
	 * Polls because inclusion takes a block or two; Hive produces one every
	 * three seconds. Block, mempool, explicit unknown, and no-answer remain
	 * separate so callers cannot accidentally display ambiguity as inclusion.
	 *
	 * ## What counts as confirmed, and why mempool does
	 *
	 * The question being asked is **"does any healthy node other than the one
	 * that accepted it know this transaction exists?"** That is the check the
	 * frozen node failed: it held five
	 * transactions in a pending block it would never produce, and no other node
	 * had ever heard of them, so every healthy node answers `unknown`.
	 *
	 * So `within_mempool` on the last attempt counts as independently observed,
	 * but it is not labelled as block inclusion. A transaction that is relaying
	 * and alive will almost always land in the next block or
	 * two, and the alternative is worse in a specific way: reporting failure
	 * queues it, the retry rebuilds it with a fresh expiration and therefore a new
	 * id, and if the original *did* land the result is a duplicate on a chain that
	 * cannot be edited. A missed entry can be earned again by watching. A
	 * duplicate is permanent. On ambiguity, don't retry.
	 */
	private enum class Confirmation { BLOCK, MEMPOOL, NOT_FOUND, UNAVAILABLE }

	private enum class TransactionObservation { BLOCK, MEMPOOL, UNKNOWN, UNAVAILABLE }

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
		val params = JSONObject().put("transaction_id", txId)
		val statuses = mutableListOf<String>()
		for (node in nodes) {
			if (node == acceptingNode) continue
			val age = chainLagSeconds(node)
			if (age == null || age > MAX_NODE_LAG_SEC) continue
			runCatching {
				val response = post(node, "transaction_status_api.find_transaction", params)
				if (response.optJSONObject("error") != null) return@runCatching
				response.optJSONObject("result")
					?.optString("status")
					?.takeIf { it.isNotBlank() }
					?.let(statuses::add)
			}
		}
		return when (strongestTransactionStatus(statuses)) {
			null -> TransactionObservation.UNAVAILABLE
			in INCLUDED_STATUSES -> TransactionObservation.BLOCK
			MEMPOOL_STATUS -> TransactionObservation.MEMPOOL
			else -> TransactionObservation.UNKNOWN
		}
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

	private fun call(method: String, params: JSONArray): Any? {
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

	/**
	 * @param params a `JSONArray` for the positional `condenser_api` calls, or a
	 * `JSONObject` for the newer named-argument APIs such as
	 * `transaction_status_api`.
	 */
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

	/** Chain timestamps are `2026-07-23T05:47:34`, implicitly UTC. */
	private fun parseChainTime(value: String): Long {
		val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
		fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
		return (fmt.parse(value)?.time ?: throw RpcException("bad chain time: $value")) / 1000
	}

	companion object {
		private const val TIMEOUT_MS = 8000

		/**
		 * How far behind the chain a node may be and still be used.
		 *
		 * Hive produces a block every three seconds, so a healthy node is within a
		 * few seconds. Generous enough to absorb phone-clock drift and a slow
		 * round-trip; tight enough that the 77-minute stall which lost seven
		 * listens on 2026-07-30 is skipped instantly.
		 */
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

		private val PLACEHOLDER = Regex("""\$\{\w+\}""")

		/**
		 * Substrings that mark a refusal as worth retrying. Narrow on purpose: the
		 * per-block `custom_json` limit is a rate limit, and resource credits
		 * regenerate, but a bad signature never becomes good.
		 */
		private val TRANSIENT_MARKERS = listOf(
			"already submitted",
			"custom json operation",
			"resource credit",
			"insufficient rc",
			"too many",
			"rate limit",
		)

		/**
		 * Failover order matters — the first healthy node wins, and freshness is
		 * checked before anything is sent.
		 *
		 * `hive-api.arcange.eu` was dropped on 2026-07-30: it answered a broadcast
		 * with an empty body and its DGP call with nothing at all.
		 * `api.openhive.network` moved off the front for being the node that
		 * froze — it is kept because a stall is a transient condition and the
		 * freshness check now handles it, not because it is trusted more.
		 *
		 * Every node here was probed for a current head block *and* for
		 * `transaction_status_api` support, which the confirmation step needs.
		 * `anyx.io` was considered and dropped: unreachable on both counts.
		 */
		val DEFAULT_NODES = listOf(
			"https://api.hive.blog",
			"https://api.deathwing.me",
			"https://api.syncad.com",
			"https://api.openhive.network",
		)
	}
}
