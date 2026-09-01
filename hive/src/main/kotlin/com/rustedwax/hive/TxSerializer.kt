package com.rustedwax.hive

import java.io.ByteArrayOutputStream

/**
 * Graphene binary serialization for the one operation we broadcast.
 *
 * Hive signs the *binary* form of a transaction, not its JSON, so this has to
 * be exact — a single wrong byte produces a valid-looking signature that the
 * chain rejects with `missing required posting authority`, which is a
 * maddening error to debug. It is verified against a dhive-generated vector in
 * `HiveVectorsTest`.
 *
 * Layout, little-endian throughout:
 *
 *   uint16   ref_block_num
 *   uint32   ref_block_prefix
 *   uint32   expiration (unix seconds)
 *   varint   operation count
 *     varint op id (18 = custom_json)
 *     varint required_auths count, then each as varint-length string
 *     varint required_posting_auths count, then each
 *     string id
 *     string json
 *   varint   extension count (always 0)
 */
object TxSerializer {

	const val OP_ID_CUSTOM_JSON = 18

	/** Hive mainnet. Note this is *not* Steem's all-zero chain id. */
	val CHAIN_ID = "beeab0de00000000000000000000000000000000000000000000000000000000".hexToBytes()

	data class CustomJsonOp(
		val requiredAuths: List<String> = emptyList(),
		val requiredPostingAuths: List<String>,
		val id: String,
		val json: String,
	)

	data class Transaction(
		val refBlockNum: Int,
		val refBlockPrefix: Long,
		val expirationEpochSec: Long,
		val operation: CustomJsonOp,
	)

	fun serialize(tx: Transaction): ByteArray {
		val out = ByteArrayOutputStream()
		out.writeUint16(tx.refBlockNum)
		out.writeUint32(tx.refBlockPrefix)
		out.writeUint32(tx.expirationEpochSec)

		out.writeVarInt(1) // one operation
		out.writeVarInt(OP_ID_CUSTOM_JSON)

		out.writeVarInt(tx.operation.requiredAuths.size)
		tx.operation.requiredAuths.forEach { out.writeString(it) }

		out.writeVarInt(tx.operation.requiredPostingAuths.size)
		tx.operation.requiredPostingAuths.forEach { out.writeString(it) }

		out.writeString(tx.operation.id)
		out.writeString(tx.operation.json)

		out.writeVarInt(0) // extensions
		return out.toByteArray()
	}

	/** The digest that actually gets signed: sha256(chain_id ‖ serialized tx). */
	fun digest(tx: Transaction): ByteArray = sha256(CHAIN_ID, serialize(tx))

	/**
	 * The transaction id, as block explorers show it: the first 20 bytes of
	 * `sha256(serialized tx)`, hex-encoded — note **no chain id**, unlike the
	 * signing digest, and computed over the unsigned form.
	 *
	 * Computed locally because `condenser_api.broadcast_transaction` returns an
	 * empty result; only `broadcast_transaction_synchronous` echoes an id, and
	 * that call blocks until the tx is in a block. This gives the user
	 * something to paste into an explorer without the wait.
	 */
	fun transactionId(tx: Transaction): String =
		sha256(serialize(tx)).copyOfRange(0, 20).toHex()

	// ── primitives ─────────────────────────────────────────────────────

	private fun ByteArrayOutputStream.writeUint16(value: Int) {
		write(value and 0xff)
		write((value ushr 8) and 0xff)
	}

	private fun ByteArrayOutputStream.writeUint32(value: Long) {
		write((value and 0xff).toInt())
		write(((value ushr 8) and 0xff).toInt())
		write(((value ushr 16) and 0xff).toInt())
		write(((value ushr 24) and 0xff).toInt())
	}

	/** LEB128 unsigned varint, as graphene uses for lengths and op ids. */
	private fun ByteArrayOutputStream.writeVarInt(value: Int) {
		var v = value
		while (true) {
			if ((v and 0x7f.inv()) == 0) {
				write(v)
				return
			}
			write((v and 0x7f) or 0x80)
			v = v ushr 7
		}
	}

	private fun ByteArrayOutputStream.writeString(value: String) {
		val bytes = value.toByteArray(Charsets.UTF_8)
		writeVarInt(bytes.size)
		write(bytes, 0, bytes.size)
	}
}
