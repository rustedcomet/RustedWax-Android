package com.rustedwax.hive

import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

/**
 * The "can no longer be included" barrier (Issue #56).
 *
 * A transaction is applied only while its expiration is still ahead of the
 * chain, so once an **irreversible** block is timestamped after it, no block
 * that could still be produced can carry it. That is all this proves: whether
 * it was included earlier is a separate question the callers answer by reading
 * the comment.
 */
class HiveRpcFinalityTest {

	private val now = 2_000_000_000L
	private val expiration = now - 600

	private fun time(epochSec: Long): String =
		java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
			.apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
			.format(java.util.Date(epochSec * 1000))

	private fun blockId(num: Long) = "%08x".format(num) + "ab".repeat(16)

	/** One node: its head, its last irreversible block and that block's header. */
	private data class Node(
		val headTime: Long,
		val head: Long = 1_000L,
		val lib: Long = 999L,
		val libTime: Long? = null,
		val previous: String? = null,
		val propertiesMissing: Boolean = false,
		val headerMissing: Boolean = false,
		val libField: Any? = null,
	)

	private fun across(vararg nodes: Node): Long? {
		val byUrl = nodes.mapIndexed { i, n -> "https://node$i" to n }.toMap()
		return HiveRpc(byUrl.keys.toList()).irreversiblyPastAcross(
			expirationEpochSec = expiration,
			propertiesOf = { url ->
				val n = byUrl.getValue(url)
				if (n.propertiesMissing) null else JSONObject()
					.put("head_block_number", n.head)
					.put("time", time(n.headTime))
					.put("last_irreversible_block_num", n.libField ?: n.lib)
			},
			headerOf = { url, num ->
				val n = byUrl.getValue(url)
				if (n.headerMissing) null else JSONObject()
					.put("previous", n.previous ?: blockId(num - 1))
					.apply { n.libTime?.let { put("timestamp", time(it)) } }
			},
			nowEpochSec = { now },
		)
	}

	private val past = expiration + 3
	private fun proving(libTime: Long = past) = Node(headTime = now, libTime = libTime)

	@Test
	fun `two current nodes with an irreversible block after expiration prove it can no longer land`() {
		assertEquals(past, across(proving(), proving(past + 30)))
	}

	@Test
	fun `an irreversible block at or before expiration keeps the lock`() {
		assertNull(across(proving(expiration), proving(expiration)))
		assertNull(across(proving(expiration - 3), proving(expiration - 3)))
	}

	@Test
	fun `one proving node is not enough`() {
		assertNull(across(proving()))
		assertNull(across(proving(), Node(headTime = now, propertiesMissing = true)))
	}

	@Test
	fun `a stale node is not evidence, however late its irreversible block`() {
		val stale = Node(headTime = now - HiveRpc.MAX_NODE_LAG_SEC - 1, libTime = past)
		assertNull(across(proving(), stale))
	}

	@Test
	fun `malformed or inconsistent block evidence is skipped`() {
		listOf(
			Node(headTime = now, headerMissing = true),
			Node(headTime = now, libTime = null),
			Node(headTime = now, libTime = past, previous = blockId(5)),
			Node(headTime = now, libTime = past, previous = "zz"),
			Node(headTime = now, libTime = past, lib = 2_000L),
			Node(headTime = now, libTime = past, libField = "not a number"),
			Node(headTime = now, libTime = past, libField = 0),
		).forEach { bad ->
			assertNull("accepted $bad", across(proving(), bad))
		}
	}

	@Test
	fun `R1 a previous block id that is not exactly forty lowercase hex digits is rejected`() {
		val good = blockId(998)
		listOf(
			good.dropLast(1),               // truncated, prefix still right
			good + "a",                     // too long
			good.dropLast(1) + "g",         // non-hex tail
			good.substring(0, 8) + " ".repeat(32),
			good.uppercase(),               // not Hive's encoding
			"0x" + good.drop(2),
		).forEach { previous ->
			assertNull("accepted '$previous'", across(proving(), Node(headTime = now, libTime = past, previous = previous)))
		}
		assertEquals(past, across(proving(), Node(headTime = now, libTime = past, previous = good)))
	}

	@Test
	fun `R1 the same endpoint listed twice is one node, not two`() {
		val n = proving()
		listOf(
			listOf("https://node0", "https://node0"),
			listOf("https://node0", "https://node0/"),
			listOf("https://node0", "HTTPS://NODE0"),
		).forEach { urls ->
			val proven = HiveRpc(urls).irreversiblyPastAcross(
				expirationEpochSec = expiration,
				propertiesOf = { JSONObject().put("head_block_number", n.head).put("time", time(n.headTime)).put("last_irreversible_block_num", n.lib) },
				headerOf = { _, num -> JSONObject().put("previous", blockId(num - 1)).put("timestamp", time(past)) },
				nowEpochSec = { now },
			)
			assertNull("counted $urls twice", proven)
		}
	}

	@Test
	fun `R1 a consensus read carries the words and the absent node's head from the same answer`() {
		val rpc = HiveRpc(listOf("https://a", "https://b"))
		val reads = rpc.readCommentStateAcross(
			author = "alice",
			permlink = "p1",
			limit = 2,
			headOf = { now },
			commentsFrom = { node ->
				if (node == "https://a") JSONObject().put(
					"comments",
					JSONArray().put(
						JSONObject().put("author", "alice").put("permlink", "p1")
							.put("parent_author", "peak.snaps").put("parent_permlink", "c1")
							.put("children", 0).put("net_rshares", 0).put("cashout_time", time(now + 86_400))
							.put("title", "").put("body", "words\n\nhttps://youtu.be/x").put("json_metadata", "{\"app\":\"rustedwax\"}"),
					),
				) else JSONObject().put("comments", JSONArray())
			},
			nowEpochSec = { now },
		)
		val present = reads[0] as HiveCommentRead.Present
		assertEquals("words\n\nhttps://youtu.be/x", present.state.body)
		assertEquals("", present.state.title)
		assertEquals("{\"app\":\"rustedwax\"}", present.state.jsonMetadata)
		assertEquals(now, present.state.headEpochSec)
		assertEquals(HiveCommentRead.Absent("b", now), reads[1])
	}

	@Test
	fun `a node whose irreversible block is not yet past expiration does not veto two that are`() {
		assertEquals(past, across(proving(), Node(headTime = now, libTime = expiration - 3), proving()))
	}

	// ── over the wire ───────────────────────────────────────────────────

	@Test
	fun `the real request reads the head and irreversible header from the same node`() {
		val realNow = System.currentTimeMillis() / 1000
		val libTime = realNow - 3
		Stub(libTime = libTime).use { a ->
			Stub(libTime = libTime).use { b ->
				assertEquals(libTime, HiveRpc(listOf(a.url, b.url)).irreversiblyPast(realNow - 600))
				assertNull("not yet past", HiveRpc(listOf(a.url, b.url)).irreversiblyPast(realNow))
				listOf(a, b).forEach { node ->
					assertEquals(
						List(2) { listOf("condenser_api.get_dynamic_global_properties", "block_api.get_block_header") }.flatten(),
						node.methods,
					)
					assertEquals(listOf(41L, 41L), node.headerParams.map { it.getLong("block_num") })
				}
			}
		}
	}

	/** A node whose clock is real, because [HiveRpc.irreversiblyPast] uses the device clock for freshness. */
	private inner class Stub(private val libTime: Long) : AutoCloseable {
		val methods = Collections.synchronizedList(mutableListOf<String>())
		val headerParams = Collections.synchronizedList(mutableListOf<JSONObject>())
		private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
			createContext("/") { exchange ->
				val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
				val method = request.getString("method")
				methods += method
				val result: Any = when (method) {
					"condenser_api.get_dynamic_global_properties" -> JSONObject()
						.put("head_block_number", 42)
						.put("head_block_id", blockId(42))
						.put("time", time(System.currentTimeMillis() / 1000))
						.put("last_irreversible_block_num", 41)
					"block_api.get_block_header" -> {
						headerParams += request.getJSONObject("params")
						JSONObject().put(
							"header",
							JSONObject().put("previous", blockId(40)).put("timestamp", time(libTime)),
						)
					}
					else -> JSONArray()
				}
				val bytes = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("result", result)
					.toString().toByteArray(Charsets.UTF_8)
				exchange.sendResponseHeaders(200, bytes.size.toLong())
				exchange.responseBody.use { it.write(bytes) }
			}
			start()
		}
		val url: String get() = "http://127.0.0.1:${server.address.port}"
		override fun close() = server.stop(0)
	}
}
