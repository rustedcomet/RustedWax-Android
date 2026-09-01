package com.rustedwax.hive

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM, wire-compatible with the extension's `privacy-cipher.ts`.
 *
 * Format on the wire: `base64( IV (12 bytes) ‖ ciphertext+authTag )`.
 *
 * The IV is fresh per call, and that is not incidental: Hive blocks are public,
 * so a deterministic ciphertext would let anyone recognise a replay of the same
 * song without ever decrypting it. Privacy mode that leaks "this is the track
 * you played an hour ago" is not privacy mode.
 *
 * Everything here is a port of a module the extension and zingit-web share
 * verbatim. §6.1's requirement is mutual: anything the extension can decrypt
 * this must decrypt, and the reverse — a user running both should see one
 * coherent history, not two halves neither side can read.
 */
object PrivacyCipher {

	/** GCM standard, and the extension's `IV_BYTES`. */
	private const val IV_BYTES = 12

	/** 128-bit tag, which is Web Crypto's default and therefore the contract. */
	private const val TAG_BITS = 128

	private val random = SecureRandom()

	class DecryptionFailed : Exception("decryption failed")

	fun encrypt(plaintextJson: String, secret: ByteArray): String {
		val iv = ByteArray(IV_BYTES).also(random::nextBytes)
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(
			Cipher.ENCRYPT_MODE,
			SecretKeySpec(secret, "AES"),
			GCMParameterSpec(TAG_BITS, iv),
		)
		// Java's GCM appends the tag to the ciphertext, which is the same layout
		// Web Crypto produces, so the concatenation below matches byte for byte.
		val body = cipher.doFinal(plaintextJson.toByteArray(Charsets.UTF_8))
		// Standard alphabet with padding, which is what `btoa` produces and what
		// the extension therefore reads back.
		return Base64.getEncoder().encodeToString(iv + body)
	}

	fun decrypt(blob: String, secret: ByteArray): String {
		val all = runCatching { Base64.getDecoder().decode(blob.trim()) }
			.getOrElse { throw DecryptionFailed() }
		if (all.size < IV_BYTES + TAG_BITS / 8) throw DecryptionFailed()
		val cipher = Cipher.getInstance(TRANSFORMATION)
		return runCatching {
			cipher.init(
				Cipher.DECRYPT_MODE,
				SecretKeySpec(secret, "AES"),
				GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES),
			)
			String(
				cipher.doFinal(all, IV_BYTES, all.size - IV_BYTES),
				Charsets.UTF_8,
			)
		}.getOrElse { throw DecryptionFailed() }
	}

	private const val TRANSFORMATION = "AES/GCM/NoPadding"
}
