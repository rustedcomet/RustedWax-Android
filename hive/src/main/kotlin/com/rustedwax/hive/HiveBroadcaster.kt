package com.rustedwax.hive

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Exact signed transaction persisted before a queued network attempt. */
data class PreparedHiveTransaction(
	val signedTransactionJson: String,
	val txId: String,
	val expirationEpochSec: Long,
)

sealed interface HivePreparationResult {
	data class Ready(val transaction: PreparedHiveTransaction) : HivePreparationResult
	data class Failed(val result: HiveRpc.BroadcastResult) : HivePreparationResult
}

/**
 * Builds, signs and broadcasts transactions — the local-signing replacement for
 * the extension's `requestCustomJson` Keychain call.
 *
 * Two paths, kept apart on purpose:
 *
 *  - **scrobbles** (`custom_json`), automatic and queue-retryable;
 *  - **Snaps** (`comment`, `vote`), user-triggered, irreversible, never queued.
 *
 * They share the envelope, digest and signing in [prepareOperation] and nothing
 * else. Both sign with posting authority only.
 *
 * Everything here is blocking; callers run it off the main thread.
 */
class HiveBroadcaster(private val rpc: HiveRpc = HiveRpc()) {

	fun broadcastScrobble(
		username: String,
		key: HiveKey,
		payload: HiveScrobblePayload,
	): HiveRpc.BroadcastResult = broadcastJson(username, key, payload.toJson())

	/**
	 * Broadcast an already-serialized payload.
	 *
	 * The queue stores payload *strings*, not objects: re-deriving a payload at
	 * retry time would stamp it with the wrong `timestamp`. What was decided at
	 * finalize is what gets signed, however much later it lands.
	 */
	fun broadcastJson(
		username: String,
		key: HiveKey,
		payloadJson: String,
	): HiveRpc.BroadcastResult = when (val prepared = prepareJson(username, key, payloadJson)) {
		is HivePreparationResult.Ready -> broadcastPrepared(prepared.transaction)
		is HivePreparationResult.Failed -> prepared.result
	}

	/** Build and sign without crossing the broadcast boundary. */
	fun prepareJson(
		username: String,
		key: HiveKey,
		payloadJson: String,
	): HivePreparationResult {
		if (!HiveScrobblePayload.serializedHasRequiredYouTubeUrl(payloadJson)) {
			return HivePreparationResult.Failed(
				HiveRpc.BroadcastResult.Rejected(
					"refusing YouTube scrobble without a canonical video hyperlink",
				),
			)
		}
		val props = try {
			rpc.getDynamicGlobalProperties()
		} catch (e: Exception) {
			return HivePreparationResult.Failed(
				HiveRpc.BroadcastResult.NetworkFailure(
					"couldn't read chain head: ${e.message}",
				),
			)
		}
		return prepareJson(username, key, payloadJson, props)
	}

	internal fun prepareJson(
		username: String,
		key: HiveKey,
		payloadJson: String,
		props: HiveRpc.GlobalProperties,
	): HivePreparationResult.Ready = prepareOperation(
		key,
		TxSerializer.CustomJsonOp(
			requiredPostingAuths = listOf(username),
			id = HiveScrobblePayload.CUSTOM_JSON_ID,
			json = payloadJson,
		),
		props,
	)

	/** Broadcast exactly the transaction prepared and persisted earlier. */
	fun broadcastPrepared(prepared: PreparedHiveTransaction): HiveRpc.BroadcastResult =
		rpc.broadcast(
			signedTx = JSONObject(prepared.signedTransactionJson),
			expectedTxId = prepared.txId,
		)

	fun observeTransaction(
		txId: String,
		expirationEpochSec: Long,
	): HiveRpc.TransactionEvidence = rpc.observeTransaction(txId, expirationEpochSec)

	// ── Snaps: manual comment and vote writes ──────────────────────────
	//
	// Deliberately a *separate* entry point from the scrobble path above, not a
	// widening of it. A scrobble is an automatic, idempotent-by-payload event
	// that the broadcast queue may safely rebuild and retry; a Snap, reply or
	// Like is an explicit irreversible content action, and blind retry creates
	// permanent duplicate comments on chain. Nothing here is wired into
	// `PayloadBroadcaster` or the retry queue, and that is the point.
	//
	// Both operations sign with **posting authority**. There is no code path
	// here, and must never be one, that asks for an active or owner key.

	/**
	 * Build and sign a `comment` — a root Snap or a reply — without
	 * broadcasting.
	 *
	 * The caller is expected to persist the returned [PreparedHiveTransaction]
	 * (and the author/permlink it commits to) *before* calling
	 * [broadcastPrepared], so an ambiguous result can be reconciled against the
	 * transaction id rather than resolved by posting a second comment.
	 *
	 * Production Snap posting goes `prepareComment` → persist →
	 * [broadcastPrepared]. It does **not** go through [broadcastComment], which
	 * cannot persist anything before crossing the network boundary.
	 */
	fun prepareComment(
		key: HiveKey,
		operation: TxSerializer.CommentOp,
	): HivePreparationResult {
		validateComment(operation)?.let { return it }
		val props = chainHead().getOrElse { return chainHeadUnavailable(it) }
		return prepareOperation(key, operation, props)
	}

	/** Build and sign a `vote` — what the UI calls a Like — without broadcasting. */
	fun prepareVote(
		key: HiveKey,
		operation: TxSerializer.VoteOp,
	): HivePreparationResult {
		validateVote(operation)?.let { return it }
		val props = chainHead().getOrElse { return chainHeadUnavailable(it) }
		return prepareOperation(key, operation, props)
	}

	/**
	 * Prepare and broadcast a comment in one call.
	 *
	 * Convenience only, and *not* what a real Snap post should use: a caller
	 * that cannot see the prepared transaction id has nothing to reconcile with
	 * if the response is lost. Stage 2B's posting path prepares, persists, then
	 * calls [broadcastPrepared].
	 */
	fun broadcastComment(
		key: HiveKey,
		operation: TxSerializer.CommentOp,
	): HiveRpc.BroadcastResult = when (val prepared = prepareComment(key, operation)) {
		is HivePreparationResult.Ready -> broadcastPrepared(prepared.transaction)
		is HivePreparationResult.Failed -> prepared.result
	}

	/** Prepare and broadcast a vote in one call. Same caveat as [broadcastComment]. */
	fun broadcastVote(
		key: HiveKey,
		operation: TxSerializer.VoteOp,
	): HiveRpc.BroadcastResult = when (val prepared = prepareVote(key, operation)) {
		is HivePreparationResult.Ready -> broadcastPrepared(prepared.transaction)
		is HivePreparationResult.Failed -> prepared.result
	}

	/**
	 * Build and sign one operation against already-fetched properties — the
	 * single signing path, shared by scrobbles and Snaps.
	 *
	 * Shared on purpose: the envelope, the digest and the id derivation are the
	 * parts that took the longest to get right, and a second copy of them is a
	 * second chance to get them wrong. What is *not* shared is everything above
	 * this line — validation, retry policy and queueing all stay per-path.
	 */
	internal fun prepareOperation(
		key: HiveKey,
		operation: TxSerializer.Operation,
		props: HiveRpc.GlobalProperties,
	): HivePreparationResult.Ready {
		// Relative to *chain* time: a phone with a skewed clock would otherwise
		// produce an already-expired or too-distant expiration.
		val expirationEpochSec = props.timeEpochSec + EXPIRY_SECONDS
		val tx = TxSerializer.Transaction(
			refBlockNum = refBlockNum(props.headBlockNumber),
			refBlockPrefix = refBlockPrefix(props.headBlockId),
			expirationEpochSec = expirationEpochSec,
			operation = operation,
		)
		val signature = key.sign(TxSerializer.digest(tx))
		// The node returns an empty result on success, so the id has to be derived
		// locally — and it is passed *in* rather than filled in afterwards, because
		// it's what the confirmation step looks the transaction up by. Before
		// v0.8.4 this was stitched on after the fact, which is how five
		// transactions that never existed got reported with ids.
		return HivePreparationResult.Ready(
			PreparedHiveTransaction(
				signedTransactionJson = toJson(tx, signature).toString(),
				txId = TxSerializer.transactionId(tx),
				expirationEpochSec = expirationEpochSec,
			),
		)
	}

	/**
	 * Chain head, or the network failure to report.
	 *
	 * Deliberately narrow: only the lookup is inside the `runCatching`. Wrapping
	 * the signing that follows would report a grinding failure as a network
	 * problem, which the caller would then retry forever.
	 */
	private fun chainHead(): Result<HiveRpc.GlobalProperties> =
		runCatching { rpc.getDynamicGlobalProperties() }

	private fun chainHeadUnavailable(cause: Throwable): HivePreparationResult.Failed =
		HivePreparationResult.Failed(
			HiveRpc.BroadcastResult.NetworkFailure("couldn't read chain head: ${cause.message}"),
		)

	/**
	 * Refusals that must happen *before* anything is signed or sent.
	 *
	 * Rejected rather than deferred throughout: none of these become correct by
	 * being retried, and a retried comment is a duplicate post.
	 */
	private fun validateComment(op: TxSerializer.CommentOp): HivePreparationResult.Failed? {
		// Every RustedWax comment is a child — a root Snap under the Snap
		// container, or a reply under another comment. An empty parent_author
		// would make this a top-level Hive post, which v1 never creates.
		if (op.parentAuthor.isBlank()) return reject("Snap has no parent author")
		if (op.parentPermlink.isBlank()) return reject("Snap has no parent permlink")
		if (op.author.isBlank()) return reject("no signed-in Hive account to post as")
		if (op.body.isBlank()) return reject("refusing to publish an empty Snap")
		permlinkProblem(op.permlink)?.let { return reject(it) }
		return null
	}

	private fun validateVote(op: TxSerializer.VoteOp): HivePreparationResult.Failed? {
		if (op.voter.isBlank()) return reject("no signed-in Hive account to vote as")
		if (op.author.isBlank()) return reject("Like has no target author")
		if (op.permlink.isBlank()) return reject("Like has no target permlink")
		// Product rule, not a chain rule: Hive permits self-votes, RustedWax
		// shows no Like control on the user's own content, so one arriving here
		// is a bug rather than an intent.
		if (op.voter.equals(op.author, ignoreCase = true)) {
			return reject("refusing to Like your own Snap")
		}
		// Positive only. v1 exposes no downvote, and the signed int16 the
		// serializer writes would happily encode one.
		if (op.weight !in MIN_LIKE_WEIGHT..MAX_LIKE_WEIGHT) {
			return reject("Like weight ${op.weight} is outside 1..$MAX_LIKE_WEIGHT")
		}
		return null
	}

	private fun reject(message: String): HivePreparationResult.Failed =
		HivePreparationResult.Failed(HiveRpc.BroadcastResult.Rejected(message))

	/** JSON form of the signed transaction, as `broadcast_transaction` expects. */
	private fun toJson(tx: TxSerializer.Transaction, signature: String): JSONObject {
		val (name, op) = operationJson(tx.operation)

		return JSONObject()
			.put("ref_block_num", tx.refBlockNum)
			.put("ref_block_prefix", tx.refBlockPrefix)
			.put("expiration", formatExpiration(tx.expirationEpochSec))
			.put("operations", JSONArray().put(JSONArray().put(name).put(op)))
			.put("extensions", JSONArray())
			.put("signatures", JSONArray().put(signature))
	}

	/**
	 * The `[name, fields]` pair the chain expects, derived from the *same*
	 * operation object that was serialized and signed.
	 *
	 * Taking the operation rather than rebuilding one from loose arguments is
	 * the whole point: signing one structure and broadcasting another is the
	 * failure mode here, and it produces a transaction the chain silently drops.
	 */
	private fun operationJson(op: TxSerializer.Operation): Pair<String, JSONObject> = when (op) {
		is TxSerializer.CustomJsonOp -> "custom_json" to JSONObject()
			.put("required_auths", JSONArray(op.requiredAuths))
			.put("required_posting_auths", JSONArray(op.requiredPostingAuths))
			.put("id", op.id)
			.put("json", op.json)

		is TxSerializer.CommentOp -> "comment" to JSONObject()
			.put("parent_author", op.parentAuthor)
			.put("parent_permlink", op.parentPermlink)
			.put("author", op.author)
			.put("permlink", op.permlink)
			.put("title", op.title)
			.put("body", op.body)
			.put("json_metadata", op.jsonMetadata)

		// `weight` is a JSON *number*, not a string — a quoted weight is
		// rejected by some nodes and silently coerced by others.
		is TxSerializer.VoteOp -> "vote" to JSONObject()
			.put("voter", op.voter)
			.put("author", op.author)
			.put("permlink", op.permlink)
			.put("weight", op.weight)
	}

	companion object {
		private const val EXPIRY_SECONDS = 60L

		/** `10000 = 100%`, per the Hive vote operation. */
		const val MAX_LIKE_WEIGHT = 10_000
		private const val MIN_LIKE_WEIGHT = 1

		/** RustedWax's Like-strength slider bounds, in percent. */
		const val MIN_LIKE_PERCENT = 10
		const val MAX_LIKE_PERCENT = 100

		/** Hive permlinks the app *generates*: lowercase, digits and hyphens. */
		private val GENERATED_PERMLINK = Regex("^[a-z0-9][a-z0-9-]*$")
		private const val MAX_PERMLINK_LENGTH = 255

		/**
		 * The one place a Like percentage becomes a chain weight.
		 *
		 * Clamped to the slider's own bounds rather than trusted, because the
		 * stored preference outlives the UI that wrote it: a value from an older
		 * build, a hand-edited preference file or an off-by-one at the slider's
		 * end must not turn into a 0% or >100% vote.
		 */
		fun likeWeightForPercent(percent: Int): Int =
			percent.coerceIn(MIN_LIKE_PERCENT, MAX_LIKE_PERCENT) * 100

		/** Null when the permlink is one Hive will accept from us. */
		internal fun permlinkProblem(permlink: String): String? = when {
			permlink.isBlank() -> "Snap has no permlink"
			permlink.length > MAX_PERMLINK_LENGTH ->
				"Snap permlink is ${permlink.length} characters, over $MAX_PERMLINK_LENGTH"
			!GENERATED_PERMLINK.matches(permlink) ->
				"Snap permlink is not lowercase alphanumeric-with-hyphens"
			else -> null
		}

		/** Low 16 bits of the head block number. */
		fun refBlockNum(headBlockNumber: Long): Int = (headBlockNumber and 0xffff).toInt()

		/**
		 * Little-endian uint32 read from bytes 4..8 of the head block id — the
		 * one piece of this that looks arbitrary and is easy to get wrong.
		 */
		fun refBlockPrefix(headBlockId: String): Long {
			val bytes = headBlockId.hexToBytes()
			require(bytes.size >= 8) { "short block id: $headBlockId" }
			var value = 0L
			for (i in 0 until 4) {
				value = value or ((bytes[4 + i].toLong() and 0xff) shl (8 * i))
			}
			return value
		}

		/** Chain expirations are UTC with no zone suffix. */
		fun formatExpiration(epochSeconds: Long): String {
			val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
			fmt.timeZone = TimeZone.getTimeZone("UTC")
			return fmt.format(Date(epochSeconds * 1000))
		}
	}
}
