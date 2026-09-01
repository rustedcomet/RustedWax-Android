package com.rustedwax.hive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The privacy secret has to agree with the extension exactly, or a user running
 * both ends up with two halves of a history neither side can read.
 *
 * The targets were captured into `HiveVectorsTest` before any of this existed,
 * from the extension's own derivation: sign the fixed challenge with a known
 * posting key, then SHA-256 the signature *bytes*.
 */
class PrivacyVectorsTest {

	private companion object {
		const val WIF = "5JEofkGSyRCqNe298aiQqiLwHgXYaPBKXe1oeaepituuwofqipA"
		const val CHALLENGE_SIGNATURE_HEX =
			"1f4f0c25c64c58c47d50faedee867847a4683ab6ac911dcafc337b0c8d280bc207368b3bf2" +
				"06126d947f540f7b383962b4a7f0fb1067ecbd016fcf8eefa705bdf4"
		const val PRIVACY_SECRET_HEX =
			"40e1d5b182ac1692b8944b3d38a4ecf64fcbd914c30991f2ee737ac69880cd4d"

		/**
		 * Produced by Web Crypto through the extension's exact `privacy-cipher.ts`
		 * steps, under the secret above. Frozen: regenerating it on the fly would
		 * only ever test this code against itself.
		 */
		const val EXTENSION_BLOB =
			"iB9rPhKGBzQx1jDL8Te0ujQlo7y9ga5ZHE4gGSv+1WUi4Bmnx+UIGq2f4oOn" +
				"58yPcYg5b6ydgY17vYoMQKlDdn2uxWT9VW+FNOztO0ap9nR0c1MjHO1nI/zw" +
				"z63mqvMeubKkQEjb8s511k06VG3MRq1hGoxOP1fxvjGdoabeZQrkYp+J8l4L" +
				"nKcrQ5PrItmaiyChmhfxn0W4meYbvDOTfXw+9o66WEgaK5RcYA=="
	}

	private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

	/** The challenge string is the whole compatibility contract for v1. */
	@Test
	fun `the challenge matches the extension`() {
		assertEquals("zingit:privacy-key:v1", PrivacySecret.CHALLENGE)
	}

	@Test
	fun `signing the challenge reproduces the extension's signature`() {
		val key = HiveKey.fromWif(WIF)!!
		val digest = java.security.MessageDigest.getInstance("SHA-256")
			.digest(PrivacySecret.CHALLENGE.toByteArray(Charsets.UTF_8))
		assertEquals(CHALLENGE_SIGNATURE_HEX, key.sign(digest))
	}

	/**
	 * A blob produced by the extension, under the vector secret above. Decrypting
	 * it here is the half of §6.1's requirement that a round-trip cannot prove:
	 * our own encrypt/decrypt agreeing says nothing about whether the *other*
	 * implementation can read us, or we it.
	 */
	@Test
	fun `an extension-produced blob decrypts here`() {
		val secret = PrivacySecret.derive(WIF)!!
		val fields = PrivateScrobble.decryptFields(EXTENSION_BLOB, secret)
		assertEquals("Trash", fields.getString("title"))
		assertEquals("Korn", fields.getString("artist"))
		assertEquals(61, fields.getInt("percent_played"))
	}

	/** Round-trip, so a change to either side is caught even without a fixture. */
	@Test
	fun `an envelope encrypts and reads back`() {
		val secret = PrivacySecret.derive(WIF)!!
		val payload = HiveScrobblePayload(
			kind = HiveScrobblePayload.KIND_SONG,
			title = "Trash",
			artist = "Korn",
			timestamp = "2026-07-23T05:46:34.000Z",
			duration = "3:28",
			percentPlayed = 61,
			platform = "youtube",
			url = "https://www.youtube.com/watch?v=abcdefghijk",
		)
		val envelope = org.json.JSONObject(PrivateScrobble.envelope(payload, secret)!!)
		// What stays public: enough to count and place a listen, nothing about
		// what it was.
		assertEquals(HiveScrobblePayload.APP_NAME, envelope.getString("app"))
		assertEquals("song", envelope.getString("kind"))
		assertEquals("2026-07-23T05:46:34.000Z", envelope.getString("timestamp"))
		assertEquals(1, envelope.getInt("v"))
		assertFalse(envelope.has("title"))
		assertFalse(envelope.has("artist"))
		assertFalse(envelope.has("url"))

		val fields = PrivateScrobble.decryptFields(envelope.getString("private"), secret)
		assertEquals("Trash", fields.getString("title"))
		assertEquals("Korn", fields.getString("artist"))
		assertEquals("https://www.youtube.com/watch?v=abcdefghijk", fields.getString("url"))
	}

	/** A fresh IV per call, or a public ledger reveals replays without decrypting. */
	@Test
	fun `the same payload never encrypts to the same blob twice`() {
		val secret = PrivacySecret.derive(WIF)!!
		val first = PrivacyCipher.encrypt("""{"title":"Trash"}""", secret)
		val second = PrivacyCipher.encrypt("""{"title":"Trash"}""", secret)
		assertNotEquals(first, second)
		assertEquals("Trash", org.json.JSONObject(PrivacyCipher.decrypt(first, secret)).getString("title"))
		assertEquals("Trash", org.json.JSONObject(PrivacyCipher.decrypt(second, secret)).getString("title"))
	}

	/**
	 * §6.1's preserved failure mode. Privacy on with no usable secret must refuse
	 * to produce an envelope, so the caller drops the listen rather than
	 * publishing it in the clear to a ledger with no undo.
	 */
	@Test
	fun `no secret yields no envelope rather than a plaintext one`() {
		val payload = HiveScrobblePayload(
			kind = HiveScrobblePayload.KIND_SONG,
			title = "Trash",
			timestamp = "2026-07-23T05:46:34.000Z",
		)
		assertNull(PrivateScrobble.envelope(payload, null))
		assertNull(PrivateScrobble.envelope(payload, ByteArray(16)))
	}

	/** The kind→toggle mapping is upstream's, including its default arm. */
	@Test
	fun `each kind maps to the toggle the extension uses`() {
		assertEquals(PrivateScrobble.Category.MUSIC, PrivateScrobble.categoryFor("song"))
		assertEquals(PrivateScrobble.Category.VIDEOS, PrivateScrobble.categoryFor("video"))
		assertEquals(PrivateScrobble.Category.MOVIES_TV, PrivateScrobble.categoryFor("movie"))
		assertEquals(PrivateScrobble.Category.MOVIES_TV, PrivateScrobble.categoryFor("episode"))
		assertEquals(PrivateScrobble.Category.PODCASTS, PrivateScrobble.categoryFor("podcast"))
		assertEquals(PrivateScrobble.Category.MUSIC, PrivateScrobble.categoryFor("something-new"))
	}

	@Test
	fun `the derived secret matches the extension byte for byte`() {
		assertEquals(PRIVACY_SECRET_HEX, PrivacySecret.derive(WIF)!!.hex())
		// AES-256 needs exactly this much, and a short key would fail late.
		assertEquals(32, PrivacySecret.derive(WIF)!!.size)
	}
}
