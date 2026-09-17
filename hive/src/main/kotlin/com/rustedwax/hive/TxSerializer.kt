package com.rustedwax.hive

import java.io.ByteArrayOutputStream

/**
 * Graphene binary serialization for the operations we broadcast.
 *
 * Hive signs the *binary* form of a transaction, not its JSON, so this has to
 * be exact — a single wrong byte produces a valid-looking signature that the
 * chain rejects with `missing required posting authority`, which is a
 * maddening error to debug. Every operation here is verified against a
 * dhive-generated vector in `HiveVectorsTest`.
 *
 * Transaction envelope, little-endian throughout:
 *
 *   uint16   ref_block_num
 *   uint32   ref_block_prefix
 *   uint32   expiration (unix seconds)
 *   varint   operation count (always 1 — see [Transaction])
 *     varint op id
 *     ...operation body, per [Operation]
 *   varint   extension count (always 0)
 *
 * All three operations use **posting authority only**. Nothing here can reach
 * for an active or owner key.
 */
object TxSerializer {

	const val OP_ID_VOTE = 0
	const val OP_ID_COMMENT = 1
	const val OP_ID_CUSTOM_JSON = 18

	/** Hive mainnet. Note this is *not* Steem's all-zero chain id. */
	val CHAIN_ID = "beeab0de00000000000000000000000000000000000000000000000000000000".hexToBytes()

	/**
	 * One broadcastable operation.
	 *
	 * Sealed rather than a generic map on purpose: the binary field order below
	 * *is* the protocol, and a map would let a caller reorder or misname a field
	 * without the compiler noticing. Each variant carries its own op id so the
	 * serializer and the broadcast JSON writer cannot drift apart.
	 */
	sealed interface Operation {
		val opId: Int
	}

	data class CustomJsonOp(
		val requiredAuths: List<String> = emptyList(),
		val requiredPostingAuths: List<String>,
		val id: String,
		val json: String,
	) : Operation {
		override val opId: Int get() = OP_ID_CUSTOM_JSON
	}

	/**
	 * A Hive comment: a root Snap, or a reply. The only difference between the
	 * two is the parent — the chain has no separate "reply" operation.
	 *
	 * `title` is empty for both; Snaps are untitled by convention.
	 *
	 * `jsonMetadata` is passed through as an already-serialized string, exactly
	 * as the chain stores it. Building it is Stage 2B's job — signing a
	 * re-serialized copy of the metadata rather than the literal one that gets
	 * broadcast is precisely the class of bug this type is shaped to prevent.
	 */
	data class CommentOp(
		val parentAuthor: String,
		val parentPermlink: String,
		val author: String,
		val permlink: String,
		val title: String,
		val body: String,
		val jsonMetadata: String,
	) : Operation {
		override val opId: Int get() = OP_ID_COMMENT
	}

	/**
	 * A Hive vote — what the UI calls a Like.
	 *
	 * [weight] is the raw chain weight where `10000 = 100%`, **not** a
	 * percentage: the mapping lives in [HiveBroadcaster.likeWeightForPercent] so
	 * there is one place that can get it wrong. It serializes as a *signed*
	 * int16, which is why negative weights are representable at all; RustedWax
	 * refuses to build them (see [HiveBroadcaster.prepareVote]), but the
	 * primitive has to match the protocol or every vote digest would be wrong.
	 */
	data class VoteOp(
		val voter: String,
		val author: String,
		val permlink: String,
		val weight: Int,
	) : Operation {
		override val opId: Int get() = OP_ID_VOTE
	}

	data class Transaction(
		val refBlockNum: Int,
		val refBlockPrefix: Long,
		val expirationEpochSec: Long,
		val operation: Operation,
	)

	fun serialize(tx: Transaction): ByteArray {
		val out = ByteArrayOutputStream()
		out.writeUint16(tx.refBlockNum)
		out.writeUint32(tx.refBlockPrefix)
		out.writeUint32(tx.expirationEpochSec)

		out.writeVarInt(1) // one operation
		out.writeVarInt(tx.operation.opId)
		out.writeOperationBody(tx.operation)

		out.writeVarInt(0) // extensions
		return out.toByteArray()
	}

	/**
	 * Operation bodies, in exact protocol field order.
	 *
	 * The `custom_json` arm is byte-for-byte what it has always been; the
	 * frozen scrobble vector in `HiveVectorsTest` fails loudly if that changes.
	 */
	private fun ByteArrayOutputStream.writeOperationBody(op: Operation) {
		when (op) {
			is CustomJsonOp -> {
				writeVarInt(op.requiredAuths.size)
				op.requiredAuths.forEach { writeString(it) }

				writeVarInt(op.requiredPostingAuths.size)
				op.requiredPostingAuths.forEach { writeString(it) }

				writeString(op.id)
				writeString(op.json)
			}

			is CommentOp -> {
				writeString(op.parentAuthor)
				writeString(op.parentPermlink)
				writeString(op.author)
				writeString(op.permlink)
				writeString(op.title)
				writeString(op.body)
				writeString(op.jsonMetadata)
			}

			is VoteOp -> {
				writeString(op.voter)
				writeString(op.author)
				writeString(op.permlink)
				writeInt16(op.weight)
			}
		}
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

	/**
	 * Signed 16-bit little-endian, two's complement — graphene's `int16_t`,
	 * used by `vote.weight` and nothing else we send.
	 */
	private fun ByteArrayOutputStream.writeInt16(value: Int) {
		require(value in Short.MIN_VALUE..Short.MAX_VALUE) {
			"int16 out of range: $value"
		}
		write(value and 0xff)
		write((value shr 8) and 0xff)
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
