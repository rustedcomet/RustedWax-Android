package com.rustedwax.hive

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `delete_comment`: its bytes, and the consensus read deletion is gated on.
 *
 * The serialization vector was produced by a live node, not by this code:
 * `condenser_api.get_transaction_hex` on `api.hive.blog`, 2026-10-01, for the
 * unsigned transaction below. The node appends the (empty) signature count, a
 * final `00` that [TxSerializer.serialize] does not write.
 *
 * The comment fixtures are `database_api.find_comments` shapes captured the
 * same day from all four default nodes, which agreed field for field —
 * including the paid-out shape: `cashout_time` `1969-12-31T23:59:59` with
 * `net_rshares` and `children` reading 0.
 */
class HiveDeleteCommentTest {

	private val tx = TxSerializer.Transaction(
		refBlockNum = 12345,
		refBlockPrefix = 2864434397L,
		expirationEpochSec = 1790884800L, // 2026-10-01T20:00:00
		operation = TxSerializer.DeleteCommentOp("skiptvads", "rustedwax-reply-1790877064-1h5fia"),
	)

	private val nodeHex =
		"3930ddccbbaac0bbbe6a011109736b69707476616473217275737465647761782d7265706c792d313739303837373036342d3168356669610000"

	@Test
	fun `serializes delete_comment exactly as a Hive node does`() {
		assertEquals(nodeHex.dropLast(2), TxSerializer.serialize(tx).toHex())
	}

	@Test
	fun `writes operation id 17`() {
		assertEquals(17, TxSerializer.DeleteCommentOp("a", "b").opId)
	}

	private val head = 1_790_884_800L

	private fun comment(
		children: Any = 0,
		net: Any = 0,
		cashout: String = "2026-10-08T17:51:03",
		author: String = "skiptvads",
		permlink: String = "rustedwax-reply-1",
	) = JSONObject().put(
		"comments",
		org.json.JSONArray().put(
			JSONObject()
				.put("author", author)
				.put("permlink", permlink)
				.put("parent_author", "skiptvads")
				.put("parent_permlink", "rustedwax-snap-1")
				.put("children", children)
				.put("net_rshares", net)
				.put("cashout_time", cashout),
		),
	)

	private fun parse(result: JSONObject?) =
		HiveCommentStates.parse(result, "skiptvads", "rustedwax-reply-1", head, "n")

	@Test
	fun `reads the evaluator's fields from find_comments`() {
		val read = parse(comment(children = 2, net = 143805803103L)) as HiveCommentRead.Present
		assertEquals(2L, read.state.children)
		assertEquals(143805803103L, read.state.netRshares)
		assertTrue(read.state.payoutPending)
	}

	@Test
	fun `a paid-out comment reads zero rshares but no pending payout`() {
		val read = parse(comment(cashout = HiveCommentStates.NO_CASHOUT)) as HiveCommentRead.Present
		assertEquals(0L, read.state.netRshares)
		assertFalse(read.state.payoutPending)
	}

	@Test
	fun `an empty comments array is the only absence`() {
		assertTrue(parse(JSONObject().put("comments", org.json.JSONArray())) is HiveCommentRead.Absent)
		assertNull(parse(null))
		assertNull(parse(JSONObject()))
	}

	@Test
	fun `a row for any other object, or unreadable numbers, is no answer`() {
		assertNull(parse(comment(permlink = "rustedwax-reply-2")))
		assertNull(parse(comment(author = "SKIPTVADS")))
		assertNull(parse(comment(net = 1.5)))
		assertNull(parse(comment(children = "lots")))
		assertNull(parse(comment(cashout = "soon")).let { (it as? HiveCommentRead.Present)?.state?.cashoutEpochSec })
	}

	@Test
	fun `only nodes proven current may answer, each answer from a different node`() {
		val rpc = HiveRpc(listOf("https://stale.example", "https://a.example", "https://b.example"))
		val asked = mutableListOf<String>()
		val reads = rpc.readCommentStateAcross(
			author = "skiptvads",
			permlink = "rustedwax-reply-1",
			limit = 2,
			headOf = { node -> if ("stale" in node) head - 3_600 else head },
			commentsFrom = { node -> asked += node; JSONObject().put("comments", org.json.JSONArray()) },
			nowEpochSec = { head + 3 },
		)
		assertEquals(listOf("https://a.example", "https://b.example"), asked)
		assertEquals(listOf("a.example", "b.example"), reads.map { it.node })
	}

	@Test
	fun `no current node means no answer at all`() {
		val rpc = HiveRpc(listOf("https://a.example"))
		val reads = rpc.readCommentStateAcross(
			"skiptvads", "rustedwax-reply-1", 1,
			headOf = { null },
			commentsFrom = { error("must not ask a node that failed its freshness check") },
		)
		assertTrue(reads.isEmpty())
	}

	@Test
	fun `negative reply counts and malformed timestamp suffixes authorize nothing`() {
		assertNull(parse(comment(children = -1)))
		assertFalse((parse(comment(cashout = "2026-10-08T17:51:03garbage")) as? HiveCommentRead.Present)?.state?.payoutPending == true)
		assertNull(ChainTimes.epochSec("2026-02-30T17:51:03"))
		assertNull(ChainTimes.epochSec("2026-10-08T17:51:03garbage"))
	}

	@Test
	fun `an implausibly future head cannot prove either presence or absence`() {
		val rpc = HiveRpc(listOf("https://a.example"))
		val reads = rpc.readCommentStateAcross(
			"skiptvads", "rustedwax-reply-1", 1,
			headOf = { head + HiveRpc.MAX_NODE_LAG_SEC + 1 },
			commentsFrom = { error("future head must not authorize a comment read") },
			nowEpochSec = { head },
		)
		assertTrue(reads.isEmpty())
	}

	@Test
	fun `a head that ages out during the comment read cannot prove absence`() {
		val rpc = HiveRpc(listOf("https://a.example"))
		var now = head + HiveRpc.MAX_NODE_LAG_SEC
		val reads = rpc.readCommentStateAcross(
			"skiptvads", "rustedwax-reply-1", 1,
			headOf = { head },
			commentsFrom = {
				now++
				JSONObject().put("comments", org.json.JSONArray())
			},
			nowEpochSec = { now },
		)
		assertTrue(reads.isEmpty())
	}

	@Test
	fun `the broadcast form is delete_comment with author and permlink`() {
		val prepared = HiveBroadcaster().prepareOperation(
			SnapTestKey.key,
			TxSerializer.DeleteCommentOp("skiptvads", "rustedwax-reply-1"),
			HiveRpc.GlobalProperties(12345, "00003039872300c0aabbccddeeff00112233445566778899", head),
		)
		val op = JSONObject(prepared.transaction.signedTransactionJson)
			.getJSONArray("operations").getJSONArray(0)
		assertEquals("delete_comment", op.getString(0))
		assertEquals("skiptvads", op.getJSONObject(1).getString("author"))
		assertEquals("rustedwax-reply-1", op.getJSONObject(1).getString("permlink"))
		assertEquals(2, op.getJSONObject(1).length())
	}
}
