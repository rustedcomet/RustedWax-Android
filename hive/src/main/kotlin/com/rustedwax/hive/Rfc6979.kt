package com.rustedwax.hive

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import java.math.BigInteger

object Rfc6979 {

	fun generateNonce(
		privateKey: ByteArray,
		messageHash: ByteArray,
		extraEntropy: ByteArray? = null,
		attempt: Int = 0,
	): BigInteger {
		require(privateKey.size == 32) { "private key must be 32 bytes" }
		require(messageHash.size == 32) { "message hash must be 32 bytes" }
		require(extraEntropy == null || extraEntropy.size == 32) {
			"extra entropy must be 32 bytes"
		}

		val keyData = privateKey + messageHash + (extraEntropy ?: ByteArray(0))

		// RFC 6979 §3.2 steps b–g, as implemented by
		// secp256k1_rfc6979_hmac_sha256_initialize.
		var v = ByteArray(32) { 0x01 }
		var k = ByteArray(32) { 0x00 }

		k = hmac(k, v + byteArrayOf(0x00) + keyData)
		v = hmac(k, v)
		k = hmac(k, v + byteArrayOf(0x01) + keyData)
		v = hmac(k, v)

		// `generate` is called (attempt + 1) times; each call re-runs V = HMAC(V).
		var out = ByteArray(0)
		repeat(attempt + 1) {
			v = hmac(k, v)
			out = v
		}
		return BigInteger(1, out)
	}

	private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
		val mac = HMac(SHA256Digest())
		mac.init(KeyParameter(key))
		mac.update(data, 0, data.size)
		val out = ByteArray(mac.macSize)
		mac.doFinal(out, 0)
		return out
	}
}
