package com.rustedwax.hive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class HiveVectorsTest {

	private companion object {
		// Deterministic throwaway key from the seed "rustedwax-mobile-test-vector-v1".
		// NOT a real account; safe to keep in the repo.
		const val WIF = "5JEofkGSyRCqNe298aiQqiLwH" +
			"gXYaPBKXe1oeaepituuwofqipA"
		const val PUBLIC_KEY = "STM6byQCqnKfp4igsEzNgtu9nH1yptU1dmKD3fGtt9bYopAM2k9Du"

		// Bitcoin's classic k=1 vector — the WIF for private key 1, whose
		// public key is the secp256k1 generator point.
		const val WIF_ONE = "5HpHagT65TZzG1PH3CSu63k8D" +
			"bpvD8s5ip4nEB3kEsreAnchuDf"
		const val PUBLIC_KEY_ONE = "STM5p78kHbL33Rn3JWkTWRE2B9uz6gy4r1KbfAKLNQGE3ovMBS5bu"
		const val GENERATOR_COMPRESSED =
			"0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

		/**
		 * Frozen input for the dhive vectors below.
		 *
		 * Deliberately a literal, and deliberately still the old `app` value: the
		 * serialized bytes, digest and signature underneath it were produced by
		 * dhive against *this exact string*. Following `APP_NAME` would invalidate
		 * every one of them on any future rename, and would quietly turn a
		 * cross-implementation check into a test of our own output against itself.
		 * The current `app` value is asserted separately, against payloads the app
		 * builds.
		 */
		const val PAYLOAD_JSON =
			"""{"app":"hivescrobblesai/1.0","kind":"song","title":"Trash",""" +
				""""timestamp":"2026-07-23T05:46:34.000Z","artist":"Korn","duration":"3:28",""" +
				""""percent_played":61,"platform":"youtube"}"""

		const val SERIALIZED_TX_HEX =
			"3930872300c0f6aa616a011200010d7275737465647761787465737410686976655f73" +
				"63726f62626c655f6169ad017b22617070223a22686976657363726f62626c65736169" +
				"2f312e30222c226b696e64223a22736f6e67222c227469746c65223a22547261736822" +
				"2c2274696d657374616d70223a22323032362d30372d32335430353a34363a33342e30" +
				"30305a222c22617274697374223a224b6f726e222c226475726174696f6e223a22333a" +
				"3238222c2270657263656e745f706c61796564223a36312c22706c6174666f726d223a" +
				"22796f7574756265227d00"

		const val DIGEST_HEX =
			"e8b4e0cda6b2db5cd18d1915b7be3367b93ad987e29709fe03705bae688239c9"

		const val SIGNATURE_HEX =
			"203266ef96a227216055b0e404c05237f20ba2983463b911916e6fad1ce512c2ec4095064a" +
				"41be6fc03de40e1e4d29f0221857ab997f0d31d3866e0095b2851193"

		// Phase 4 (privacy key) parity, captured now so the derivation can be
		// implemented against a known-good target later.
		const val CHALLENGE = "zingit:privacy-key:v1"
		const val CHALLENGE_SIGNATURE_HEX =
			"1f4f0c25c64c58c47d50faedee867847a4683ab6ac911dcafc337b0c8d280bc207368b3bf2" +
				"06126d947f540f7b383962b4a7f0fb1067ecbd016fcf8eefa705bdf4"
		const val PRIVACY_SECRET_HEX =
			"40e1d5b182ac1692b8944b3d38a4ecf64fcbd914c30991f2ee737ac69880cd4d"

		fun testTransaction() = TxSerializer.Transaction(
			refBlockNum = 12345,
			refBlockPrefix = 3221234567L,

			expirationEpochSec = 1784785654L,
			operation = TxSerializer.CustomJsonOp(
				requiredPostingAuths = listOf("rustedwaxtest"),
				id = HiveScrobblePayload.CUSTOM_JSON_ID,
				json = PAYLOAD_JSON,
			),
		)
	}

	// ── keys ───────────────────────────────────────────────────────────

	@Test
	fun `derives the generator point from private key one`() {
		val key = HiveKey.fromWif(WIF_ONE)
		assertNotNull("k=1 WIF should parse", key)
		assertEquals(GENERATOR_COMPRESSED, key!!.publicKeyBytes.toHex())
		assertEquals(PUBLIC_KEY_ONE, key.publicKeyString)
	}

	@Test
	fun `derives the dhive public key from its WIF`() {
		val key = HiveKey.fromWif(WIF)
		assertNotNull(key)
		assertEquals(PUBLIC_KEY, key!!.publicKeyString)
	}

	@Test
	fun `public key round-trips through STM encoding`() {
		val decoded = HiveKey.decodePublicKey(PUBLIC_KEY)
		assertNotNull(decoded)
		assertEquals(33, decoded!!.size)
		assertEquals(PUBLIC_KEY, HiveKey.PUBLIC_KEY_PREFIX + Base58.encodeCheckRipemd(decoded))
	}

	/** Gate G5: a malformed or wrong-checksum key must be refused, not guessed at. */
	@Test
	fun `rejects malformed keys`() {
		assertNull(HiveKey.fromWif(""))
		assertNull(HiveKey.fromWif("not a key"))
		assertNull(HiveKey.fromWif("5JEofkGSyRCqNe298aiQqiLwH" +
			"gXYaPBKXe1oeaepituuwofqipB"))
		assertNull(HiveKey.fromWif(PUBLIC_KEY)) // a public key is not a WIF
		assertNull(HiveKey.decodePublicKey("STMnonsense"))
	}

	// ── serialization (gate G3) ────────────────────────────────────────

	@Test
	fun `serializes a custom_json transaction exactly as dhive does`() {
		assertEquals(SERIALIZED_TX_HEX, TxSerializer.serialize(testTransaction()).toHex())
	}

	@Test
	fun `computes the same signing digest as dhive`() {
		assertEquals(DIGEST_HEX, TxSerializer.digest(testTransaction()).toHex())
	}

	/** The id an explorer will show — derived locally, since the node returns none. */
	@Test
	fun `computes the same transaction id as dhive`() {
		assertEquals(
			"03ebcc7b34a985c8a3650c67ab160deba77af7f1",
			TxSerializer.transactionId(testTransaction()),
		)
	}

	@Test
	fun `builds the payload JSON byte-identically`() {
		val payload = HiveScrobblePayload(
			kind = HiveScrobblePayload.KIND_SONG,
			title = "Trash",
			artist = "Korn",
			timestamp = "2026-07-23T05:46:34.000Z",
			duration = "3:28",
			percentPlayed = 61,
			platform = "youtube",
		)
		// The same shape as the frozen dhive vector, with the app's current
		// authorship. Key order and escaping are the contract being asserted here;
		// PAYLOAD_JSON keeps its original `app` value because the bytes signed
		// under it are what dhive was actually run against.
		assertEquals(
			PAYLOAD_JSON.replace("hivescrobblesai/1.0", HiveScrobblePayload.APP_NAME),
			payload.toJson(),
		)
		// The rename must not have quietly reintroduced an escaped slash.
		assertTrue(payload.toJson().contains("\"app\":\"rustedwax/"))
	}

	/**
	 * The app version that goes on chain is a real version, not Gradle's default.
	 *
	 * Every other assertion in this suite reads [HiveScrobblePayload.APP_NAME]
	 * symbolically, which is correct — the version must stay dynamic — but it
	 * means they all pass whatever it happens to contain. They did: for a while
	 * `BUILD_VERSION` was generated as `unspecified`, because
	 * `hive/build.gradle.kts` read `project.version` at execution time, which the
	 * configuration cache does not support. Scrobbles and Snap metadata went to
	 * Hive announcing `"app":"rustedwax/unspecified"`, permanently and publicly,
	 * and the whole suite stayed green.
	 *
	 * This is the one assertion that looks at the value itself. It deliberately
	 * does **not** name a version: pinning `0.11.4` here would have to be edited
	 * on every release and would turn a real invariant into a chore, and the
	 * version has exactly one authority — `allprojects { version = … }` in the
	 * root build script, which reaches this constant through the generated
	 * `BUILD_VERSION`. What is asserted is only that the generation *worked*.
	 */
	@Test
	fun `the generated build version is a real version, not Gradle's default`() {
		assertTrue("BUILD_VERSION is blank — version generation produced nothing", BUILD_VERSION.isNotBlank())
		assertFalse(
			"BUILD_VERSION is Gradle's default: `project.version` was not read at " +
				"configuration time, so every Hive payload would claim to come from " +
				"`rustedwax/unspecified`",
			BUILD_VERSION == "unspecified",
		)
		assertTrue(
			"BUILD_VERSION `$BUILD_VERSION` has no digit in it, so it is not a version",
			BUILD_VERSION.any { it.isDigit() },
		)
		// And the constant the payloads actually carry is still composed from it,
		// so the check above cannot be satisfied by a value nothing uses.
		assertEquals("rustedwax/$BUILD_VERSION", HiveScrobblePayload.APP_NAME)
	}

	@Test
	fun `does not escape forward slashes like JSON stringify does not`() {
		val json = HiveScrobblePayload(
			title = "a/b",
			timestamp = "2026-07-23T05:46:34.000Z",
		).toJson()
		assertTrue("must not contain an escaped slash", !json.contains("\\/"))
		assertTrue(json.contains(HiveScrobblePayload.APP_NAME))
		assertFalse(json.contains("rustedwax\\/"))
		assertTrue(json.contains(""""title":"a/b""""))
	}

	@Test
	fun `escapes what JSON actually requires`() {
		assertEquals(""""a\"b"""", HiveScrobblePayload.quoteJson("a\"b"))
		assertEquals(""""a\\b"""", HiveScrobblePayload.quoteJson("a\\b"))
		assertEquals(""""a\nb"""", HiveScrobblePayload.quoteJson("a\nb"))
		assertEquals(""""héllo ☃"""", HiveScrobblePayload.quoteJson("héllo ☃"))
	}

	@Test
	fun `omits absent optional fields`() {
		val json = HiveScrobblePayload(
			title = "Untitled",
			timestamp = "2026-07-23T05:46:34.000Z",
		).toJson()
		assertEquals(
			"""{"app":"${HiveScrobblePayload.APP_NAME}","kind":"video","title":"Untitled",""" +
				""""timestamp":"2026-07-23T05:46:34.000Z"}""",
			json,
		)
	}

	@Test
	fun `YouTube payload policy requires one canonical video hyperlink`() {
		val missing = HiveScrobblePayload(
			title = "Unresolved Short",
			timestamp = "2026-07-31T18:42:01.000Z",
			platform = HiveScrobblePayload.PLATFORM_YOUTUBE,
		)
		assertTrue(!missing.hasRequiredYouTubeUrl)
		assertTrue(!HiveScrobblePayload.serializedHasRequiredYouTubeUrl(missing.toJson()))
		assertTrue(
			HiveBroadcaster().broadcastJson(
				"throwaway",
				HiveKey.fromWif(WIF)!!,
				missing.toJson(),
			) is HiveRpc.BroadcastResult.Rejected,
		)

		val linked = missing.copy(url = "https://www.youtube.com/watch?v=L12DOfNlPKE")
		assertTrue(linked.hasRequiredYouTubeUrl)
		assertTrue(HiveScrobblePayload.serializedHasRequiredYouTubeUrl(linked.toJson()))
		assertTrue(
			!missing.copy(url = "https://youtube.com/shorts/L12DOfNlPKE")
				.hasRequiredYouTubeUrl,
		)

		// The Account-tab transport test is not a YouTube listen.
		assertTrue(
			HiveScrobblePayload.serializedHasRequiredYouTubeUrl(
				HiveScrobblePayload(title = "test", timestamp = "x", platform = "test").toJson(),
			),
		)
	}

	// ── signing (gate G1) ──────────────────────────────────────────────

	@Test
	fun `signs a transaction digest byte-identically to dhive`() {
		val key = HiveKey.fromWif(WIF)!!
		assertEquals(SIGNATURE_HEX, key.sign(DIGEST_HEX.hexToBytes()))
	}

	/**
	 * Gate G1 proper: the privacy secret is SHA-256 of this signature, so a
	 * different-but-valid signature would silently break cross-device
	 * decryption. Keychain signs `sha256(message)`.
	 */
	@Test
	fun `signs the privacy challenge byte-identically to dhive`() {
		val key = HiveKey.fromWif(WIF)!!
		val digest = sha256(CHALLENGE.toByteArray(Charsets.UTF_8))
		val signature = key.sign(digest)
		assertEquals(CHALLENGE_SIGNATURE_HEX, signature)
		assertEquals(PRIVACY_SECRET_HEX, sha256(signature.hexToBytes()).toHex())
	}

	@Test
	fun `signatures are deterministic and canonical`() {
		val key = HiveKey.fromWif(WIF)!!
		val digest = TxSerializer.digest(testTransaction())
		val first = key.sign(digest)
		assertEquals(first, key.sign(digest))
		assertTrue(HiveKey.isCanonical(first.hexToBytes()))
		assertEquals(65, first.hexToBytes().size)
	}

	// ── transaction reference fields ───────────────────────────────────

	@Test
	fun `derives ref block fields from the head block id`() {
		// Head block 12345 → low 16 bits, and prefix from bytes 4..8 LE.
		assertEquals(12345, HiveBroadcaster.refBlockNum(12345L))
		assertEquals(0xFFFF, HiveBroadcaster.refBlockNum(0x1FFFFL))
		assertEquals(
			3221234567L,
			HiveBroadcaster.refBlockPrefix("00003039872300c0aabbccddeeff00112233445566778899"),
		)
	}

	@Test
	fun `formats expiration as chain-style UTC`() {
		assertEquals("2026-07-23T05:47:34", HiveBroadcaster.formatExpiration(1784785654L))
	}

	@Test
	fun `prepares the exact signed transaction without broadcasting`() {
		val prepared = HiveBroadcaster().prepareJson(
			username = "rustedwaxtest",
			key = HiveKey.fromWif(WIF)!!,
			payloadJson = PAYLOAD_JSON,
			props = HiveRpc.GlobalProperties(
				headBlockNumber = 12345,
				headBlockId = "00003039872300c0aabbccddeeff00112233445566778899",
				timeEpochSec = 1784785594L,
			),
		).transaction

		assertEquals("03ebcc7b34a985c8a3650c67ab160deba77af7f1", prepared.txId)
		assertEquals(1784785654L, prepared.expirationEpochSec)
		val signed = JSONObject(prepared.signedTransactionJson)
		assertEquals(SIGNATURE_HEX, signed.getJSONArray("signatures").getString(0))
		assertEquals(PAYLOAD_JSON, signed.getJSONArray("operations").getJSONArray(0)
			.getJSONObject(1).getString("json"))
	}

	@Test
	fun `formats payload timestamps as the extension does`() {
		assertEquals(
			"2026-07-23T05:46:34.000Z",
			HiveScrobblePayload.isoTimestamp(1784785594L),
		)
		assertEquals("3:28", HiveScrobblePayload.formatDuration(208))
	}
}
