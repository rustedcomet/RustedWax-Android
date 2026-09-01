package com.rustedwax.hive

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import java.math.BigInteger

/**
 * Deterministic nonce generation, replicating **libsecp256k1's**
 * `secp256k1_nonce_function_rfc6979` rather than textbook RFC 6979.
 *
 * This distinction is load-bearing. Hive Keychain signs via dhive, which uses
 * the `secp256k1` npm binding to libsecp256k1. Its nonce derivation differs
 * from the RFC in two ways:
 *
 *   1. The HMAC key material is `privkey ‖ msg32 ‖ extraEntropy?` — the message
 *      hash is used raw, with no `bits2octets` reduction mod n.
 *   2. Extra entropy is appended as a third 32-byte block, which is how dhive
 *      grinds for a canonical signature (see [HiveKey.sign]).
 *
 * Matching this exactly is what makes our signatures byte-identical to
 * Keychain's, which the privacy-key derivation depends on: the AES secret is
 * `SHA-256(signature)`, so a different-but-valid signature yields a different
 * key and silently breaks cross-device decryption.
 */
object Rfc6979 {

	/**
	 * @param privateKey 32-byte private key
	 * @param messageHash 32-byte digest being signed
	 * @param extraEntropy optional 32-byte extra data (libsecp256k1's `ndata`)
	 * @param attempt which nonce to return; libsecp256k1's `counter` parameter,
	 *   incremented when a candidate nonce yields an invalid signature
	 */
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
