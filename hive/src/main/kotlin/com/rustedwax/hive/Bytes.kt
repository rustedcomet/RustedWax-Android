package com.rustedwax.hive

import java.security.MessageDigest

/** Hex, SHA-256 and RIPEMD-160 helpers shared by the Hive layer. */

fun ByteArray.toHex(): String {
	val sb = StringBuilder(size * 2)
	for (b in this) sb.append("%02x".format(b.toInt() and 0xff))
	return sb.toString()
}

fun String.hexToBytes(): ByteArray {
	require(length % 2 == 0) { "odd-length hex string" }
	return ByteArray(length / 2) {
		((Character.digit(this[it * 2], 16) shl 4) or Character.digit(this[it * 2 + 1], 16))
			.toByte()
	}
}

fun sha256(vararg parts: ByteArray): ByteArray {
	val md = MessageDigest.getInstance("SHA-256")
	parts.forEach { md.update(it) }
	return md.digest()
}

/**
 * RIPEMD-160, used for the 4-byte checksum on WIF keys and STM public keys.
 * Taken from BouncyCastle's lightweight API rather than a JCE provider, since
 * Android's bundled BC provider does not register it.
 */
fun ripemd160(data: ByteArray): ByteArray {
	val digest = org.bouncycastle.crypto.digests.RIPEMD160Digest()
	digest.update(data, 0, data.size)
	val out = ByteArray(digest.digestSize)
	digest.doFinal(out, 0)
	return out
}

/** Constant-time-ish comparison. Not perf-critical; correctness matters more. */
fun ByteArray.constantTimeEquals(other: ByteArray): Boolean {
	if (size != other.size) return false
	var diff = 0
	for (i in indices) diff = diff or (this[i].toInt() xor other[i].toInt())
	return diff == 0
}
