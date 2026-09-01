package com.rustedwax.hive

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds, signs and broadcasts a scrobble — the local-signing replacement for
 * the extension's `requestCustomJson` Keychain call.
 *
 * Everything here is blocking; callers run it off the main thread.
 */
class HiveBroadcaster(private val rpc: HiveRpc = HiveRpc()) {

	/**
	 * @param username the account whose posting authority signs the op
	 * @param key parsed posting key — never persisted by this class
	 */
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
	): HiveRpc.BroadcastResult {
		if (!HiveScrobblePayload.serializedHasRequiredYouTubeUrl(payloadJson)) {
			return HiveRpc.BroadcastResult.Rejected(
				"refusing YouTube scrobble without a canonical video hyperlink",
			)
		}
		val props = try {
			rpc.getDynamicGlobalProperties()
		} catch (e: Exception) {
			return HiveRpc.BroadcastResult.NetworkFailure(
				"couldn't read chain head: ${e.message}",
			)
		}

		val tx = TxSerializer.Transaction(
			refBlockNum = refBlockNum(props.headBlockNumber),
			refBlockPrefix = refBlockPrefix(props.headBlockId),
			// Relative to *chain* time: a phone with a skewed clock would
			// otherwise produce an already-expired or too-distant expiration.
			expirationEpochSec = props.timeEpochSec + EXPIRY_SECONDS,
			operation = TxSerializer.CustomJsonOp(
				requiredPostingAuths = listOf(username),
				id = HiveScrobblePayload.CUSTOM_JSON_ID,
				json = payloadJson,
			),
		)

		val signature = key.sign(TxSerializer.digest(tx))
		// The node returns an empty result on success, so the id has to be derived
		// locally — and it is passed *in* rather than filled in afterwards, because
		// it's what the confirmation step looks the transaction up by. Before
		// v0.8.4 this was stitched on after the fact, which is how five
		// transactions that never existed got reported with ids.
		return rpc.broadcast(
			signedTx = toJson(tx, signature),
			expectedTxId = TxSerializer.transactionId(tx),
		)
	}

	/** JSON form of the signed transaction, as `broadcast_transaction` expects. */
	private fun toJson(tx: TxSerializer.Transaction, signature: String): JSONObject {
		val op = JSONObject()
			.put("required_auths", JSONArray(tx.operation.requiredAuths))
			.put("required_posting_auths", JSONArray(tx.operation.requiredPostingAuths))
			.put("id", tx.operation.id)
			.put("json", tx.operation.json)

		return JSONObject()
			.put("ref_block_num", tx.refBlockNum)
			.put("ref_block_prefix", tx.refBlockPrefix)
			.put("expiration", formatExpiration(tx.expirationEpochSec))
			.put("operations", JSONArray().put(JSONArray().put("custom_json").put(op)))
			.put("extensions", JSONArray())
			.put("signatures", JSONArray().put(signature))
	}

	companion object {
		private const val EXPIRY_SECONDS = 60L

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
