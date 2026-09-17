package com.rustedwax.hive

import java.math.BigInteger

/**
 * A signing key for tests, derived at runtime from a published constant.
 *
 * Deliberately **not** a WIF literal. A 51-character `5…` string sitting in a
 * source file is indistinguishable at a glance from a real posting key, and a
 * repository that contains one teaches every reader — and every future scan —
 * that such strings are normal here. Building the key from an integer instead
 * keeps the test vectors exact while leaving nothing in the tree that could be
 * mistaken for, or misused as, an account credential.
 *
 * The scalar is `1`: secp256k1's generator point, the most widely published
 * test value in the ecosystem, and an "account" that has been swept
 * continuously for over a decade. It cannot hold anything.
 */
internal object SnapTestKey {

	/** The signing key used by the cross-implementation vectors. */
	val key: HiveKey by lazy { fromScalar(BigInteger.ONE) }

	/** Its `STM…` public form, as `HiveVectorsTest` already pins for this scalar. */
	const val PUBLIC_KEY = "STM5p78kHbL33Rn3JWkTWRE2B9uz6gy4r1KbfAKLNQGE3ovMBS5bu"

	/**
	 * Build a Hive key from a raw secp256k1 scalar, by assembling the WIF the
	 * same way any wallet would: version byte, 32-byte scalar, then a
	 * double-SHA-256 checksum, base58-encoded.
	 */
	fun fromScalar(scalar: BigInteger): HiveKey {
		val payload = byteArrayOf(WIF_VERSION.toByte()) + HiveKey.to32Bytes(scalar)
		val checksum = sha256(sha256(payload)).copyOfRange(0, 4)
		return requireNotNull(HiveKey.fromWif(Base58.encode(payload + checksum))) {
			"test scalar did not produce a usable key"
		}
	}

	private const val WIF_VERSION = 0x80
}
