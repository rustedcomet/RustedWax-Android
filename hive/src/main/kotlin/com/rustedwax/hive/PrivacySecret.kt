package com.rustedwax.hive

import java.security.MessageDigest

/**
 * The AES-256 secret private scrobbles are encrypted under, derived from the
 * posting key exactly as the extension's `privacy-secret.ts` derives it.
 *
 * ## Why signing a fixed string is a key-derivation function
 *
 * Hive's ECDSA is RFC 6979 — deterministic. Signing the same challenge with the
 * same posting key always yields the same signature, so hashing that signature
 * gives a stable 32-byte secret with no chain-side state, no server, and no
 * memo key (which most accounts never load into Keychain). The challenge string
 * is fixed for v1 precisely so every client derives the *same* secret; bumping
 * it would strand every blob already on chain.
 *
 * ## The one deliberate difference from the extension
 *
 * The extension never holds a key. It has to prompt Keychain in a trusted tab,
 * route the signature through a privileged channel so no page script can read
 * it, and then verify the signature really recovers to the account's on-chain
 * posting key — because a hijacked Keychain could otherwise hand back a forged
 * one and steer the derivation.
 *
 * RustedWax already holds the posting key locally, in
 * `EncryptedSharedPreferences` under a Keystore master key. So it signs
 * directly: no prompt, no relay, no tab, and no mid-listen interruption. The
 * forged-signature attack the extension defends against does not exist here,
 * because there is no third party in the path to forge anything — the same
 * verification would amount to checking our own arithmetic.
 *
 * What is **not** dropped is the failure mode: privacy on with no derivable
 * secret must refuse to broadcast rather than fall back to plaintext. That
 * guard is the whole feature, and it lives in [PrivateScrobble].
 */
object PrivacySecret {

	/** Fixed for v1, and shared with the extension and zingit-web. */
	const val CHALLENGE = "zingit:privacy-key:v1"

	/**
	 * @param wif the account's private posting key.
	 * @return 32 bytes for AES-256-GCM.
	 */
	fun derive(wif: String): ByteArray? {
		val key = HiveKey.fromWif(wif) ?: return null
		// Keychain's `requestSignBuffer` signs the SHA-256 of the message bytes,
		// so that is what has to be signed here for the signatures — and
		// therefore the secrets — to agree.
		val challengeDigest = sha256(CHALLENGE.toByteArray(Charsets.UTF_8))
		val signatureHex = key.sign(challengeDigest)
		// Hash the raw signature *bytes*, not its hex text: 32 bytes of ECDSA
		// output rather than something biased by the hex alphabet. Upstream is
		// explicit about this and the two must agree exactly.
		return sha256(hexToBytes(signatureHex))
	}

	private fun sha256(bytes: ByteArray): ByteArray =
		MessageDigest.getInstance("SHA-256").digest(bytes)

	internal fun hexToBytes(hex: String): ByteArray {
		require(hex.length % 2 == 0) { "odd-length hex string" }
		return ByteArray(hex.length / 2) { i ->
			hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
		}
	}
}
