package com.rustedwax.hive

import java.math.BigInteger

/**
 * Base58 with the Bitcoin alphabet, plus the two checksum flavours Hive uses.
 *
 * Ported from `posting-key-verify.ts#base58Decode` in the extension, extended
 * with encoding and checksum validation (the extension only ever needed to
 * decode public keys; we also parse WIF private keys and render public keys).
 *
 * Two different checksums are in play, which is a classic source of bugs:
 *   - WIF private keys: `sha256(sha256(payload))[0..4]`  (Bitcoin style)
 *   - STM public keys:  `ripemd160(payload)[0..4]`       (graphene style)
 */
object Base58 {

	private const val ALPHABET =
		"123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

	private val INDEX = IntArray(128) { -1 }.apply {
		ALPHABET.forEachIndexed { i, c -> this[c.code] = i }
	}

	fun encode(input: ByteArray): String {
		if (input.isEmpty()) return ""
		var num = BigInteger(1, input)
		val sb = StringBuilder()
		val base = BigInteger.valueOf(58)
		while (num.signum() > 0) {
			val (q, r) = num.divideAndRemainder(base)
			sb.append(ALPHABET[r.toInt()])
			num = q
		}
		// Leading zero bytes become leading '1's.
		for (b in input) {
			if (b.toInt() != 0) break
			sb.append(ALPHABET[0])
		}
		return sb.reverse().toString()
	}

	fun decode(input: String): ByteArray {
		if (input.isEmpty()) return ByteArray(0)
		var num = BigInteger.ZERO
		val base = BigInteger.valueOf(58)
		for (c in input) {
			val idx = if (c.code < 128) INDEX[c.code] else -1
			require(idx >= 0) { "invalid base58 character '$c'" }
			num = num.multiply(base).add(BigInteger.valueOf(idx.toLong()))
		}
		var bytes = num.toByteArray()
		// BigInteger prepends a sign byte; drop it.
		if (bytes.size > 1 && bytes[0].toInt() == 0) bytes = bytes.copyOfRange(1, bytes.size)
		var leadingZeros = 0
		for (c in input) {
			if (c != ALPHABET[0]) break
			leadingZeros++
		}
		return ByteArray(leadingZeros) + bytes
	}

	/** Decode and verify a double-SHA256 checksum (WIF private keys). */
	fun decodeCheckSha(input: String): ByteArray? {
		val raw = runCatching { decode(input) }.getOrNull() ?: return null
		if (raw.size < 5) return null
		val payload = raw.copyOfRange(0, raw.size - 4)
		val checksum = raw.copyOfRange(raw.size - 4, raw.size)
		val expected = sha256(sha256(payload)).copyOfRange(0, 4)
		return if (checksum.constantTimeEquals(expected)) payload else null
	}

	/** Decode and verify a RIPEMD-160 checksum (STM public keys). */
	fun decodeCheckRipemd(input: String): ByteArray? {
		val raw = runCatching { decode(input) }.getOrNull() ?: return null
		if (raw.size < 5) return null
		val payload = raw.copyOfRange(0, raw.size - 4)
		val checksum = raw.copyOfRange(raw.size - 4, raw.size)
		val expected = ripemd160(payload).copyOfRange(0, 4)
		return if (checksum.constantTimeEquals(expected)) payload else null
	}

	fun encodeCheckRipemd(payload: ByteArray): String =
		encode(payload + ripemd160(payload).copyOfRange(0, 4))
}
