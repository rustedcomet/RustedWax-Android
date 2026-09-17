package com.rustedwax.hive

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-implementation vectors for the two operations Snaps are built from,
 * `comment` and `vote`.
 *
 * These pin the **serializer**, not the product format. The bodies and metadata
 * below are arbitrary fixtures chosen to exercise the encoding; the frozen v1
 * Snap body is asserted in `SnapPayloadTest` instead.
 *
 * Every hex string below was produced by **dhive** (`@hiveio/dhive`, its own
 * `Types.Transaction` serializer plus `cryptoUtils`), not by this code. The
 * generator was first pointed at the existing frozen `custom_json` transaction
 * and reproduced its serialized bytes, digest, transaction id and signature
 * exactly — which is what makes these new vectors evidence rather than a
 * recording of our own output.
 *
 * The literals here are frozen for the same reason `HiveVectorsTest`'s are: the
 * metadata string contains an app version on purpose, and must **not** be made
 * to follow `BUILD_VERSION`. A rename or version bump that silently regenerated
 * these would turn a reference test into a self-comparison.
 *
 * `HiveVectorsTest` remains the guard on the scrobble path; it is unchanged, and
 * its byte-for-byte `custom_json` assertions are the proof that widening the
 * serializer left that path alone.
 *
 * The signing key here is built at runtime by [SnapTestKey] rather than written
 * out as a WIF, so nothing in this file resembles an account credential. The
 * digests below are unaffected by that — a digest does not depend on the key —
 * and the two signatures were regenerated with dhive against the same scalar.
 */
class HiveSnapVectorsTest {

	private companion object {
		/** The same frozen chain reference the scrobble vectors use. */
		val PROPS = HiveRpc.GlobalProperties(
			headBlockNumber = 12345,
			headBlockId = "00003039872300c0aabbccddeeff00112233445566778899",
			timeEpochSec = 1784785594L,
		)
		const val REF_BLOCK_NUM = 12345
		const val REF_BLOCK_PREFIX = 3221234567L
		const val EXPIRATION = 1784785654L

		// ── comment: a generic Hive comment ────────────────────────────
		//
		// **Serializer evidence, not a product-format example.** This body and
		// metadata predate the frozen v1 Snap format and deliberately still
		// differ from it — the bytes, digest and id below were produced by dhive
		// against these exact strings, so following the product format here would
		// invalidate every vector and turn a cross-implementation check into a
		// test of our own output. What it proves is that `comment` serializes
		// correctly, which is independent of what RustedWax chooses to put in a
		// body. `SnapPayloadTest` owns the product format.
		//
		// Emoji and newlines are in the body deliberately: the length prefix is
		// the UTF-8 *byte* count, so a code-unit count would pass an ASCII-only
		// vector and corrupt every real comment.
		const val COMMENT_BODY = "Absolute banger 🔥\n\n" +
			"https://www.youtube.com/watch?v=L12DOfNlPKE\n\n" +
			"#scrobblelife #scrobble #rustedwax"
		const val COMMENT_METADATA = """{"app":"rustedwax/0.11.4","format":"markdown",""" +
			""""tags":["snaps","scrobblelife","scrobble","rustedwax"]}"""

		const val COMMENT_SERIALIZED_HEX =
			"3930872300c0f6aa616a01010a7065616b2e736e6170731f736e61702d636f6e7461696e" +
				"65722d3230323630373233743035343530307a0d7275737465647761787465737420" +
				"7275737465647761782d736e61702d313738343738353539342d6131623263330065" +
				"4162736f6c7574652062616e67657220f09f94a50a0a68747470733a2f2f7777772e" +
				"796f75747562652e636f6d2f77617463683f763d4c3132444f664e6c504b450a0a23" +
				"7363726f62626c656c69666520237363726f62626c65202372757374656477617865" +
				"7b22617070223a227275737465647761782f302e31312e34222c22666f726d617422" +
				"3a226d61726b646f776e222c2274616773223a5b22736e617073222c227363726f62" +
				"626c656c696665222c227363726f62626c65222c22727573746564776178225d7d00"
		const val COMMENT_DIGEST_HEX =
			"28f4dc2fc004c8a912f82f94d4124bc5f5d5fa7a827f4261ba34227e252e3584"
		const val COMMENT_TX_ID = "92e0e6d98b5ff0c40e29eb2fbf4766247a9d9c7f"
		const val COMMENT_SIGNATURE_HEX =
			"206054425e89fdfb420bb2b842d2185cee75ea6b8c4d20c118371307f08f5a54ff1ba177" +
				"d6bd125ce7b48043dba6a5357c3a8b689141457e4cfe2baccceb52b27e"

		// ── comment: a reply, parented on another comment ──────────────
		const val REPLY_SERIALIZED_HEX =
			"3930872300c0f6aa616a01010d72757374656477617874657374207275737465647761782d" +
				"736e61702d313738343738353539342d6131623263330c7365636f6e6474657374657221" +
				"7275737465647761782d7265706c792d313738343738353939392d397a3879377800" +
				"276167726565642c20746865206472756d20746f6e65206f6e207468697320697320756e" +
				"7265616c3f7b22617070223a227275737465647761782f302e31312e34222c22666f726d" +
				"6174223a226d61726b646f776e222c2274616773223a5b22736e617073225d7d00"
		const val REPLY_DIGEST_HEX =
			"0f1ae7c11a3afb0a3616d5d5c1ed6ab98ae497096a04eebfd888ba05a27799d4"
		const val REPLY_TX_ID = "af5cdeef95aaa8d80263d327366e4c6aa04ce706"

		const val VOTE_PERMLINK = "rustedwax-snap-1784785594-a1b2c3"

		fun comment() = TxSerializer.CommentOp(
			parentAuthor = "peak.snaps",
			parentPermlink = "snap-container-20260723t054500z",
			author = "rustedwaxtest",
			permlink = VOTE_PERMLINK,
			title = "",
			body = COMMENT_BODY,
			jsonMetadata = COMMENT_METADATA,
		)

		fun replyComment() = TxSerializer.CommentOp(
			parentAuthor = "rustedwaxtest",
			parentPermlink = VOTE_PERMLINK,
			author = "secondtester",
			permlink = "rustedwax-reply-1784785999-9z8y7x",
			title = "",
			body = "agreed, the drum tone on this is unreal",
			jsonMetadata = """{"app":"rustedwax/0.11.4","format":"markdown","tags":["snaps"]}""",
		)

		fun vote(weight: Int) = TxSerializer.VoteOp(
			voter = "rustedwaxtest",
			author = "peak.snaps",
			permlink = VOTE_PERMLINK,
			weight = weight,
		)

		fun tx(operation: TxSerializer.Operation) = TxSerializer.Transaction(
			refBlockNum = REF_BLOCK_NUM,
			refBlockPrefix = REF_BLOCK_PREFIX,
			expirationEpochSec = EXPIRATION,
			operation = operation,
		)

		/**
		 * Derived at runtime from the scalar 1, so no WIF-shaped string exists in
		 * these sources. See [SnapTestKey].
		 */
		fun key() = SnapTestKey.key
	}

	// ── comment serialization ──────────────────────────────────────────

	@Test
	fun `serializes a comment exactly as dhive does`() {
		assertEquals(COMMENT_SERIALIZED_HEX, TxSerializer.serialize(tx(comment())).toHex())
	}

	@Test
	fun `computes dhive's digest and transaction id for a comment`() {
		assertEquals(COMMENT_DIGEST_HEX, TxSerializer.digest(tx(comment())).toHex())
		assertEquals(COMMENT_TX_ID, TxSerializer.transactionId(tx(comment())))
	}

	@Test
	fun `signs a comment byte-identically to dhive`() {
		assertEquals(COMMENT_SIGNATURE_HEX, key().sign(COMMENT_DIGEST_HEX.hexToBytes()))
	}

	/** A reply is the same operation with a different parent — no reply op exists. */
	@Test
	fun `serializes a reply comment exactly as dhive does`() {
		assertEquals(REPLY_SERIALIZED_HEX, TxSerializer.serialize(tx(replyComment())).toHex())
		assertEquals(REPLY_DIGEST_HEX, TxSerializer.digest(tx(replyComment())).toHex())
		assertEquals(REPLY_TX_ID, TxSerializer.transactionId(tx(replyComment())))
	}

	/** Op id 1, immediately after the single-operation count byte. */
	@Test
	fun `writes the comment operation id`() {
		assertEquals(1, TxSerializer.OP_ID_COMMENT)
		assertEquals("0101", COMMENT_SERIALIZED_HEX.substring(20, 24))
	}

	// ── vote serialization ─────────────────────────────────────────────

	@Test
	fun `serializes the three documented Like strengths exactly as dhive does`() {
		val vectors = mapOf(
			1000 to Triple(
				"3930872300c0f6aa616a01000d727573746564776178746573740a7065616b2e736e61" +
					"7073207275737465647761782d736e61702d31373834373835353934" +
					"2d613162326333e80300",
				"2ceec838de6c227ddcba96567a355d28e9fc34d607bb80da88d8f27b8d8c54aa",
				"4be6087f6de8cec950f302034e20c98b3b204a2c",
			),
			5000 to Triple(
				"3930872300c0f6aa616a01000d727573746564776178746573740a7065616b2e736e61" +
					"7073207275737465647761782d736e61702d31373834373835353934" +
					"2d613162326333881300",
				"b90356781292bf6c26365ff78583615e8c4eaad9325c7d073aa2f3a67784b4ba",
				"13faa259d081db84e7b84d0ae764451a371a8eac",
			),
			10000 to Triple(
				"3930872300c0f6aa616a01000d727573746564776178746573740a7065616b2e736e61" +
					"7073207275737465647761782d736e61702d31373834373835353934" +
					"2d613162326333102700",
				"5f4cc9c3ac81d7daccf4004845d6e34e9fb4fd48b0a55a120e8d30954ee66fbd",
				"727a7861b1b2b4669f1be134afe2a90e5cac7806",
			),
		)
		vectors.forEach { (weight, expected) ->
			val (hex, digest, txId) = expected
			assertEquals("weight $weight bytes", hex, TxSerializer.serialize(tx(vote(weight))).toHex())
			assertEquals("weight $weight digest", digest, TxSerializer.digest(tx(vote(weight))).toHex())
			assertEquals("weight $weight id", txId, TxSerializer.transactionId(tx(vote(weight))))
		}
	}

	@Test
	fun `signs a 10 percent vote byte-identically to dhive`() {
		assertEquals(
			"1f3f35b4309177107f882c96eb2c1a922e0f2ac29af41725fafd36d40ea8a44a" +
				"3837668d6a0337c138f9c17c77a4fa3a77e57e0236093f88786beee3e0b4cec362",
			key().sign(TxSerializer.digest(tx(vote(1000)))),
		)
	}

	/**
	 * The weight field is a **signed** int16. RustedWax never builds a negative
	 * one — [HiveBroadcaster.prepareVote] refuses them — but the primitive still
	 * has to match the protocol, and an unsigned writer would agree with dhive on
	 * every positive value while being wrong. These four vectors are what catch
	 * that, including both int16 extremes.
	 */
	@Test
	fun `writes vote weight as a signed little-endian int16`() {
		val expected = mapOf(
			-1 to "ffff",
			-10000 to "f0d8",
			32767 to "ff7f",
			-32768 to "0080",
		)
		expected.forEach { (weight, weightHex) ->
			val hex = TxSerializer.serialize(tx(vote(weight))).toHex()
			// …the last two bytes before the zero extension count.
			assertEquals("weight $weight", weightHex + "00", hex.takeLast(6))
		}
		// And the positive path agrees with the dhive-derived encodings above.
		assertEquals("e80300", TxSerializer.serialize(tx(vote(1000))).toHex().takeLast(6))
	}

	@Test
	fun `writes the vote operation id`() {
		assertEquals(0, TxSerializer.OP_ID_VOTE)
		assertEquals("0100", TxSerializer.serialize(tx(vote(1000))).toHex().substring(20, 24))
	}

	// ── the custom_json path is untouched ──────────────────────────────

	/**
	 * The scrobble vector, asserted again from the new sealed-operation shape.
	 *
	 * `HiveVectorsTest` already pins these bytes; repeating the assertion here
	 * makes the Snap test file fail too if a future Snap change reaches into the
	 * `custom_json` arm, rather than leaving that to a file nobody edits.
	 */
	@Test
	fun `widening the serializer left custom_json byte-for-byte identical`() {
		val scrobble = TxSerializer.CustomJsonOp(
			requiredPostingAuths = listOf("rustedwaxtest"),
			id = HiveScrobblePayload.CUSTOM_JSON_ID,
			json = """{"app":"hivescrobblesai/1.0","kind":"song","title":"Trash",""" +
				""""timestamp":"2026-07-23T05:46:34.000Z","artist":"Korn","duration":"3:28",""" +
				""""percent_played":61,"platform":"youtube"}""",
		)
		assertEquals(18, TxSerializer.OP_ID_CUSTOM_JSON)
		assertEquals(
			"3930872300c0f6aa616a011200010d7275737465647761787465737410686976655f73" +
				"63726f62626c655f6169ad017b22617070223a22686976657363726f62626c657361" +
				"692f312e30222c226b696e64223a22736f6e67222c227469746c65223a2254726173" +
				"68222c2274696d657374616d70223a22323032362d30372d32335430353a34363a33" +
				"342e3030305a222c22617274697374223a224b6f726e222c226475726174696f6e22" +
				"3a22333a3238222c2270657263656e745f706c61796564223a36312c22706c617466" +
				"6f726d223a22796f7574756265227d00",
			TxSerializer.serialize(tx(scrobble)).toHex(),
		)
		assertEquals(
			"e8b4e0cda6b2db5cd18d1915b7be3367b93ad987e29709fe03705bae688239c9",
			TxSerializer.digest(tx(scrobble)).toHex(),
		)
		assertEquals("03ebcc7b34a985c8a3650c67ab160deba77af7f1", TxSerializer.transactionId(tx(scrobble)))
	}

	// ── signed broadcast JSON ──────────────────────────────────────────

	private fun signedOperation(prepared: PreparedHiveTransaction): Pair<String, JSONObject> {
		val op = JSONObject(prepared.signedTransactionJson).getJSONArray("operations").getJSONArray(0)
		return op.getString(0) to op.getJSONObject(1)
	}

	@Test
	fun `broadcasts the comment it signed, with the chain's field names`() {
		val prepared = (
			HiveBroadcaster().prepareOperation(key(), comment(), PROPS)
			).transaction
		assertEquals(COMMENT_TX_ID, prepared.txId)
		assertEquals(EXPIRATION, prepared.expirationEpochSec)

		val signed = JSONObject(prepared.signedTransactionJson)
		assertEquals(COMMENT_SIGNATURE_HEX, signed.getJSONArray("signatures").getString(0))
		assertEquals("2026-07-23T05:47:34", signed.getString("expiration"))
		assertEquals(REF_BLOCK_NUM, signed.getInt("ref_block_num"))
		assertEquals(REF_BLOCK_PREFIX, signed.getLong("ref_block_prefix"))
		assertEquals(0, signed.getJSONArray("extensions").length())

		val (name, op) = signedOperation(prepared)
		assertEquals("comment", name)
		assertEquals(
			setOf(
				"parent_author", "parent_permlink", "author",
				"permlink", "title", "body", "json_metadata",
			),
			op.keys().asSequence().toSet(),
		)
		assertEquals("peak.snaps", op.getString("parent_author"))
		assertEquals("snap-container-20260723t054500z", op.getString("parent_permlink"))
		assertEquals("rustedwaxtest", op.getString("author"))
		assertEquals(VOTE_PERMLINK, op.getString("permlink"))
		assertEquals("", op.getString("title"))
		// The body and metadata that were signed, not a re-rendered copy.
		assertEquals(COMMENT_BODY, op.getString("body"))
		assertEquals(COMMENT_METADATA, op.getString("json_metadata"))
	}

	@Test
	fun `broadcasts the vote it signed, with weight as a number`() {
		val prepared = (
			HiveBroadcaster().prepareOperation(key(), vote(1000), PROPS)
			).transaction
		assertEquals("4be6087f6de8cec950f302034e20c98b3b204a2c", prepared.txId)

		val (name, op) = signedOperation(prepared)
		assertEquals("vote", name)
		assertEquals(setOf("voter", "author", "permlink", "weight"), op.keys().asSequence().toSet())
		assertEquals("rustedwaxtest", op.getString("voter"))
		assertEquals("peak.snaps", op.getString("author"))
		assertEquals(VOTE_PERMLINK, op.getString("permlink"))
		assertEquals(1000, op.getInt("weight"))
		assertTrue("weight must be a JSON number", op.get("weight") is Int)
	}

	/** The scrobble's broadcast JSON is still `custom_json` with its own fields. */
	@Test
	fun `still broadcasts custom_json unchanged`() {
		val prepared = HiveBroadcaster().prepareJson(
			username = "rustedwaxtest",
			key = key(),
			payloadJson = """{"app":"rustedwax/0.0.0","title":"t",""" +
				""""url":"https://www.youtube.com/watch?v=L12DOfNlPKE"}""",
			props = PROPS,
		).transaction

		val (name, op) = signedOperation(prepared)
		assertEquals("custom_json", name)
		assertEquals(
			setOf("required_auths", "required_posting_auths", "id", "json"),
			op.keys().asSequence().toSet(),
		)
		assertEquals(0, op.getJSONArray("required_auths").length())
		assertEquals("rustedwaxtest", op.getJSONArray("required_posting_auths").getString(0))
		assertEquals(HiveScrobblePayload.CUSTOM_JSON_ID, op.getString("id"))
	}

	// ── Like strength mapping ──────────────────────────────────────────

	@Test
	fun `maps Like percent to Hive weight`() {
		assertEquals(1000, HiveBroadcaster.likeWeightForPercent(10))
		assertEquals(2500, HiveBroadcaster.likeWeightForPercent(25))
		assertEquals(5000, HiveBroadcaster.likeWeightForPercent(50))
		assertEquals(7500, HiveBroadcaster.likeWeightForPercent(75))
		assertEquals(10000, HiveBroadcaster.likeWeightForPercent(100))
	}

	/** A stale or hand-edited preference must not become a 0% or >100% vote. */
	@Test
	fun `clamps Like percent to the slider's own bounds`() {
		assertEquals(1000, HiveBroadcaster.likeWeightForPercent(0))
		assertEquals(1000, HiveBroadcaster.likeWeightForPercent(9))
		assertEquals(1000, HiveBroadcaster.likeWeightForPercent(-40))
		assertEquals(10000, HiveBroadcaster.likeWeightForPercent(101))
		assertEquals(10000, HiveBroadcaster.likeWeightForPercent(9999))
	}

	// ── refusals, before anything is signed or sent ────────────────────

	/**
	 * An RPC with no nodes at all. Every chain-head lookup fails, so anything
	 * that still comes back [HiveRpc.BroadcastResult.Rejected] was refused by
	 * validation *before* the network — which is the property these tests are
	 * about. A validation failure that first cost a round trip would still be a
	 * failure, but an outage would then mask which one it was.
	 */
	private fun offlineRpc() = HiveRpc(nodes = emptyList())

	private fun rejection(result: HivePreparationResult): String {
		val failed = result as HivePreparationResult.Failed
		return (failed.result as HiveRpc.BroadcastResult.Rejected).message
	}

	private fun networkFailure(result: HivePreparationResult): String {
		val failed = result as HivePreparationResult.Failed
		return (failed.result as HiveRpc.BroadcastResult.NetworkFailure).message
	}

	/**
	 * Each of these must be [HiveRpc.BroadcastResult.Rejected], never
	 * `Deferred`: a queued retry of a comment is a duplicate post on chain.
	 */
	@Test
	fun `refuses malformed comments without touching the network`() {
		val broadcaster = HiveBroadcaster(offlineRpc())
		val good = comment()

		assertTrue(
			rejection(broadcaster.prepareComment(key(), good.copy(parentAuthor = "")))
				.contains("parent author"),
		)
		assertTrue(
			rejection(broadcaster.prepareComment(key(), good.copy(parentPermlink = "")))
				.contains("parent permlink"),
		)
		assertTrue(
			rejection(broadcaster.prepareComment(key(), good.copy(author = " ")))
				.contains("signed-in Hive account"),
		)
		assertTrue(
			rejection(broadcaster.prepareComment(key(), good.copy(body = "")))
				.contains("empty Snap"),
		)
		assertTrue(
			rejection(broadcaster.prepareComment(key(), good.copy(body = "   \n ")))
				.contains("empty Snap"),
		)
	}

	@Test
	fun `refuses permlinks Hive would not accept`() {
		val broadcaster = HiveBroadcaster(offlineRpc())
		val good = comment()
		listOf(
			"",
			"RustedWax-Snap-1",
			"rustedwax snap 1",
			"rustedwax_snap_1",
			"-leading-hyphen",
			"a".repeat(256),
		).forEach { permlink ->
			assertTrue(
				"should refuse permlink '$permlink'",
				broadcaster.prepareComment(key(), good.copy(permlink = permlink))
					is HivePreparationResult.Failed,
			)
		}
		assertEquals(null, HiveBroadcaster.permlinkProblem("rustedwax-snap-1784785594-a1b2c3"))
	}

	@Test
	fun `refuses malformed or self-directed Likes without touching the network`() {
		val broadcaster = HiveBroadcaster(offlineRpc())

		assertTrue(
			rejection(broadcaster.prepareVote(key(), vote(1000).copy(voter = "")))
				.contains("signed-in Hive account"),
		)
		assertTrue(
			rejection(broadcaster.prepareVote(key(), vote(1000).copy(author = "")))
				.contains("target author"),
		)
		assertTrue(
			rejection(broadcaster.prepareVote(key(), vote(1000).copy(permlink = "")))
				.contains("target permlink"),
		)
		// Self-like, including the casing Hive would treat as the same account.
		assertTrue(
			rejection(broadcaster.prepareVote(key(), vote(1000).copy(author = "rustedwaxtest")))
				.contains("your own Snap"),
		)
		assertTrue(
			rejection(broadcaster.prepareVote(key(), vote(1000).copy(author = "RustedWaxTest")))
				.contains("your own Snap"),
		)
	}

	/** v1 exposes no downvote, and the signed int16 would encode one happily. */
	@Test
	fun `refuses vote weights outside one to ten thousand`() {
		val broadcaster = HiveBroadcaster(offlineRpc())
		listOf(0, -1, -1000, -10000, 10001, 32767).forEach { weight ->
			assertTrue(
				"should refuse weight $weight",
				rejection(broadcaster.prepareVote(key(), vote(weight))).contains("outside"),
			)
		}
		// The usable range gets past validation and dies at the network instead —
		// which is how these two failure kinds are told apart.
		listOf(1, 1000, 5000, 10000).forEach { weight ->
			assertTrue(
				"should accept weight $weight",
				networkFailure(broadcaster.prepareVote(key(), vote(weight)))
					.contains("couldn't read chain head"),
			)
		}
	}

	/** A well-formed comment is likewise stopped by the network, not by validation. */
	@Test
	fun `a valid comment passes validation and fails only at the network`() {
		assertTrue(
			networkFailure(HiveBroadcaster(offlineRpc()).prepareComment(key(), comment()))
				.contains("couldn't read chain head"),
		)
	}

}
