package com.rustedwax.hive

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chain-error handling, from the 2026-07-30 frozen-node incident.
 *
 * `api.openhive.network` stalled at block 108575690 and stayed 77 minutes behind
 * while answering every RPC normally. It swallowed five scrobbles and refused two
 * more, and the app logged the refusal as:
 *
 * ```
 * rejected: Account ${'$'}{a} already submitted ${'$'}{n} custom json operation(s) this block.
 * ```
 *
 * Two separate defects in one line — the placeholders were never filled in, and
 * a rate limit was classified as permanent and the listens discarded.
 */
class HiveRpcErrorTest {

	@Test
	fun `block result outranks an earlier unknown node`() {
		assertEquals(
			"within_reversible_block",
			HiveRpc().strongestTransactionStatus(
				listOf("unknown", "within_reversible_block"),
			),
		)
	}

	@Test
	fun `mempool outranks unknown regardless of node order`() {
		assertEquals(
			"within_mempool",
			HiveRpc().strongestTransactionStatus(
				listOf("unknown", "within_mempool", "unknown"),
			),
		)
	}

	@Test
	fun `no node answers stays unavailable`() {
		assertNull(HiveRpc().strongestTransactionStatus(emptyList()))
	}

	private val rpc = HiveRpc()

	// region error extraction

	/** The interpolated `message` is preferred over the raw template. */
	@Test
	fun `prefers the interpolated message over the format template`() {
		val error = JSONObject(
			"""
			{"code":-32003,
			 "message":"Assert Exception:false: Account example-account already submitted 5 custom json operation(s) this block.",
			 "data":{"stack":[{"format":"Account ${'$'}{a} already submitted ${'$'}{n} custom json operation(s) this block.",
			                   "data":{"a":"example-account","n":5}}]}}
			""".trimIndent(),
		)
		val message = rpc.errorMessage(error)
		assertTrue(message.contains("example-account"))
		assertTrue(message.contains("5"))
		// The bug: the template, with its placeholders, is what used to be logged.
		assertFalse(message.contains("\${a}"))
		assertFalse(message.contains("\${n}"))
	}

	/**
	 * When only the template exists, its values are appended — otherwise the log
	 * still says nothing useful.
	 */
	@Test
	fun `appends the values when only a template is available`() {
		val error = JSONObject(
			"""
			{"code":-32003,
			 "data":{"stack":[{"format":"Account ${'$'}{a} already submitted ${'$'}{n} custom json operation(s) this block.",
			                   "data":{"a":"example-account","n":5}}]}}
			""".trimIndent(),
		)
		val message = rpc.errorMessage(error)
		assertTrue(message.contains("example-account"))
		assertTrue(message.contains("5"))
	}

	/** A clear message is left alone rather than having a data blob stapled on. */
	@Test
	fun `does not append values to an already-complete message`() {
		val error = JSONObject(
			"""
			{"message":"transaction expiration exception",
			 "data":{"stack":[{"format":"",
			                   "data":{"now":"2026-07-30T05:09:45"}}]}}
			""".trimIndent(),
		)
		assertEquals("transaction expiration exception", rpc.errorMessage(error))
	}

	@Test
	fun `falls back to the raw error when nothing is usable`() {
		assertTrue(rpc.errorMessage(JSONObject("""{"code":-1}""")).contains("-1"))
	}
	// endregion

	// region transient vs permanent

	/**
	 * A per-block rate limit is about transient capacity, not correctness; the
	 * same synthetic operation can succeed in a later block.
	 */
	@Test
	fun `the per-block custom json limit is transient`() {
		assertTrue(
			rpc.isTransient(
				"Assert Exception:false: Account example-account already submitted 5 " +
					"custom json operation(s) this block.",
			),
		)
	}

	@Test
	fun `resource credit exhaustion is transient`() {
		assertTrue(rpc.isTransient("Account: example-account has insufficient RC for transaction"))
		assertTrue(rpc.isTransient("Resource Credit limit exceeded"))
	}

	/**
	 * Everything about the transaction being *wrong* stays permanent — retrying a
	 * bad signature forever is the loop this classification exists to avoid.
	 */
	@Test
	fun `authority and validity failures are permanent`() {
		assertFalse(rpc.isTransient("missing required posting authority"))
		assertFalse(rpc.isTransient("transaction expiration exception"))
		assertFalse(rpc.isTransient("Invalid signature"))
		assertFalse(rpc.isTransient("unknown key"))
	}
	// endregion

	// region node list

	/**
	 * Both nodes dropped on 2026-07-30 — one frozen off the front, one removed
	 * outright for answering a broadcast with an empty body.
	 */
	@Test
	fun `the dead node is gone and the frozen one is no longer tried first`() {
		assertFalse(HiveRpc.DEFAULT_NODES.any { it.contains("arcange") })
		assertFalse(HiveRpc.DEFAULT_NODES.first().contains("openhive"))
		// Failover needs somewhere to fail over to.
		assertTrue(HiveRpc.DEFAULT_NODES.size >= 3)
	}

	/** A block is 3s, so the tolerance has to be well above one block. */
	@Test
	fun `the staleness tolerance is generous but far below the observed stall`() {
		assertTrue(HiveRpc.MAX_NODE_LAG_SEC > 30)
		// The stall that lost the session was ~4,600s.
		assertTrue(HiveRpc.MAX_NODE_LAG_SEC < 600)
	}
	// endregion
}
