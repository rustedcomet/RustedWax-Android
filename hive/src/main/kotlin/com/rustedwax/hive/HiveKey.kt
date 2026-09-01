package com.rustedwax.hive

import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import java.math.BigInteger

/**
 * A Hive posting key: parse from WIF, derive the public key, sign digests.
 *
 * Replaces the Keychain relay from the extension — everything Keychain did
 * (`requestSignBuffer`, `requestCustomJson`) happens locally here. The
 * signature format is graphene's 65-byte compact recoverable form, documented
 * in `posting-key-verify.ts`:
 *
 *   byte[0]     = 31 + recoveryId   (27 + 4, the compressed-key flag)
 *   byte[1..33] = r, big-endian
 *   byte[33..65]= s, big-endian
 *
 * Signatures are ground until canonical, matching dhive's loop exactly so the
 * output is byte-identical to what Keychain produces for the same key and
 * message. See [Rfc6979] for why that matters.
 */
class HiveKey private constructor(private val d: BigInteger) {

	/** 33-byte compressed public key. */
	val publicKeyBytes: ByteArray by lazy {
		CURVE.g.multiply(d).normalize().getEncoded(true)
	}

	/** The `STM…` string form, as it appears in an account's `posting.key_auths`. */
	val publicKeyString: String by lazy {
		PUBLIC_KEY_PREFIX + Base58.encodeCheckRipemd(publicKeyBytes)
	}

	/**
	 * Sign a 32-byte digest, returning the 65-byte compact recoverable
	 * signature as lowercase hex.
	 *
	 * Grinding: dhive appends an incrementing counter byte to the message,
	 * hashes it, and feeds that as libsecp256k1's extra entropy until the
	 * result passes [isCanonical]. Reproduced here byte-for-byte.
	 */
	fun sign(digest: ByteArray): String {
		require(digest.size == 32) { "digest must be 32 bytes" }
		val privateKeyBytes = to32Bytes(d)

		var attempts = 0
		while (attempts < MAX_GRIND_ATTEMPTS) {
			attempts++
			val extraEntropy = sha256(digest, byteArrayOf(attempts.toByte()))
			val sig = trySign(privateKeyBytes, digest, extraEntropy)
			if (sig != null && isCanonical(sig)) return sig.toHex()
		}
		error("could not find a canonical signature in $MAX_GRIND_ATTEMPTS attempts")
	}

	/**
	 * One signing attempt with a fixed extra-entropy value. Returns null when
	 * the derived nonce yields an invalid signature and libsecp256k1 would have
	 * advanced its internal counter.
	 */
	private fun trySign(
		privateKey: ByteArray,
		digest: ByteArray,
		extraEntropy: ByteArray,
	): ByteArray? {
		val z = BigInteger(1, digest)
		var counter = 0
		while (counter < MAX_NONCE_ATTEMPTS) {
			val k = Rfc6979.generateNonce(privateKey, digest, extraEntropy, counter)
			counter++
			if (k.signum() <= 0 || k >= CURVE.n) continue

			val point = CURVE.g.multiply(k).normalize()
			val r = point.affineXCoord.toBigInteger().mod(CURVE.n)
			if (r.signum() == 0) continue

			var s = k.modInverse(CURVE.n).multiply(z.add(r.multiply(d))).mod(CURVE.n)
			if (s.signum() == 0) continue

			// Low-S normalisation — required by the chain, and it flips the
			// recovery id's parity bit when applied.
			var yParityOdd = point.affineYCoord.toBigInteger().testBit(0)
			val xOverflow = point.affineXCoord.toBigInteger() >= CURVE.n
			if (s > HALF_N) {
				s = CURVE.n.subtract(s)
				yParityOdd = !yParityOdd
			}

			val recovery = (if (yParityOdd) 1 else 0) or (if (xOverflow) 2 else 0)
			return byteArrayOf((31 + recovery).toByte()) + to32Bytes(r) + to32Bytes(s)
		}
		return null
	}

	companion object {
		private const val WIF_VERSION = 0x80
		const val PUBLIC_KEY_PREFIX = "STM"
		private const val MAX_GRIND_ATTEMPTS = 1000
		private const val MAX_NONCE_ATTEMPTS = 100

		private val PARAMS: X9ECParameters = CustomNamedCurves.getByName("secp256k1")
		val CURVE = ECDomainParameters(PARAMS.curve, PARAMS.g, PARAMS.n, PARAMS.h)
		private val HALF_N: BigInteger = CURVE.n.shiftRight(1)

		/**
		 * Parse a WIF private key (`5…`). Returns null for anything that isn't
		 * a valid, checksummed, in-range secp256k1 key — the caller shows the
		 * user an error rather than proceeding with a broken key.
		 */
		fun fromWif(wif: String): HiveKey? {
			val payload = Base58.decodeCheckSha(wif.trim()) ?: return null
			// version(1) + key(32), optionally + compression flag(1)
			if (payload.size != 33 && payload.size != 34) return null
			if ((payload[0].toInt() and 0xff) != WIF_VERSION) return null
			val keyBytes = payload.copyOfRange(1, 33)
			val d = BigInteger(1, keyBytes)
			if (d.signum() <= 0 || d >= CURVE.n) return null
			return HiveKey(d)
		}

		/** Decode an `STM…` public key to its 33 compressed bytes. */
		fun decodePublicKey(key: String): ByteArray? {
			val body = key.trim().removePrefix(PUBLIC_KEY_PREFIX)
			val decoded = Base58.decodeCheckRipemd(body) ?: return null
			return if (decoded.size == 33) decoded else null
		}

		/**
		 * Graphene's canonicality test, applied to r‖s (the 64 bytes after the
		 * recovery header). Copied from `isCanonicalSignature` in dhive.
		 */
		fun isCanonical(sig: ByteArray): Boolean {
			val c = sig.copyOfRange(1, 65)
			fun b(i: Int) = c[i].toInt() and 0xff
			return (b(0) and 0x80) == 0 &&
				!(b(0) == 0 && (b(1) and 0x80) == 0) &&
				(b(32) and 0x80) == 0 &&
				!(b(32) == 0 && (b(33) and 0x80) == 0)
		}

		fun to32Bytes(value: BigInteger): ByteArray {
			val bytes = value.toByteArray()
			return when {
				bytes.size == 32 -> bytes
				bytes.size == 33 && bytes[0].toInt() == 0 -> bytes.copyOfRange(1, 33)
				bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
				else -> error("value too large for 32 bytes")
			}
		}
	}
}
