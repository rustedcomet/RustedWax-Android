package com.rustedwax.app.snaps

import com.rustedwax.hive.HiveBroadcaster
import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.TxSerializer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Issue #56 F02, over the wire: the real [HiveSnapPort], [HiveBroadcaster] and
 * [HiveRpc] against two local stub nodes.
 *
 * Node A takes the delete and loses its answer; node B then rejects the same
 * bytes as a duplicate. B's rejection says nothing about what A did, so the
 * deleter must keep the original transaction and settle *that* one by reading
 * — never sign a second delete while the first can still land.
 *
 * Signing goes through the port's own `signDelete` seam, so no key exists here.
 */
class SnapDeleteAmbiguousRejectionTest {

	private val account = "alice"
	private val target = SnapReplyTarget.of(account, "rustedwax-reply-1000-aaaaaa")!!

	/** What the chain holds, shared by both nodes. */
	private class World {
		@Volatile var deleted = false
		@Volatile var status = "unknown"
	}

	private enum class Broadcast { DROP_RESPONSE, DUPLICATE }

	/**
	 * A minimal HTTP/1.1 JSON-RPC node on a plain [ServerSocket] — the app
	 * module compiles against android.jar, which has no `com.sun.net.httpserver`.
	 */
	private class Node(private val world: World, private val target: SnapReplyTarget, private val mode: Broadcast) :
		AutoCloseable {
		val broadcasts = Collections.synchronizedList(mutableListOf<String>())
		val statusQueries = Collections.synchronizedList(mutableListOf<String>())

		private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
		private val thread = Thread {
			while (!server.isClosed) {
				val socket = runCatching { server.accept() }.getOrNull() ?: break
				socket.use { handle(it) }
			}
		}.apply {
			isDaemon = true
			start()
		}

		val url: String get() = "http://127.0.0.1:${server.localPort}"

		override fun close() = server.close()

		private fun handle(socket: Socket) {
			val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
			var length = 0
			while (true) {
				val line = input.readLine() ?: return
				if (line.isEmpty()) break
				if (line.startsWith("Content-Length:", ignoreCase = true)) {
					length = line.substringAfter(':').trim().toInt()
				}
			}
			val body = CharArray(length)
			var read = 0
			while (read < length) {
				val n = input.read(body, read, length - read)
				if (n < 0) return
				read += n
			}
			val request = JSONObject(String(body))
			val result: Any = when (request.getString("method")) {
				"condenser_api.get_dynamic_global_properties" -> JSONObject()
					.put("head_block_number", 1)
					.put("head_block_id", "00".repeat(20))
					.put("time", chainTime(0))
				"database_api.find_comments" -> JSONObject().put(
					"comments",
					if (world.deleted) {
						JSONArray()
					} else {
						JSONArray().put(
							JSONObject()
								.put("author", target.author)
								.put("permlink", target.permlink)
								.put("parent_author", "bob")
								.put("parent_permlink", "rustedwax-snap-900-zzzzzz")
								.put("children", 0)
								.put("net_rshares", 0)
								.put("cashout_time", chainTime(6 * 86_400)),
						)
					},
				)
				"transaction_status_api.find_transaction" -> {
					statusQueries += request.getJSONObject("params").getString("transaction_id")
					JSONObject().put("status", world.status)
				}
				"condenser_api.broadcast_transaction" -> {
					broadcasts += request.getJSONArray("params").getJSONObject(0).toString()
					when (mode) {
						// Received; the answer never makes it back.
						Broadcast.DROP_RESPONSE -> return
						Broadcast.DUPLICATE -> {
							reply(
								socket,
								JSONObject().put("jsonrpc", "2.0").put("id", 1).put(
									"error",
									JSONObject().put("code", -32003).put("message", "Duplicate transaction check failed"),
								),
							)
							return
						}
					}
				}
				else -> error("unexpected ${request.getString("method")}")
			}
			reply(socket, JSONObject().put("jsonrpc", "2.0").put("id", 1).put("result", result))
		}

		private fun reply(socket: Socket, body: JSONObject) {
			val bytes = body.toString().toByteArray(Charsets.UTF_8)
			val out = socket.getOutputStream()
			out.write(
				("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
					"Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
			)
			out.write(bytes)
			out.flush()
		}

		private fun chainTime(offsetSec: Long): String =
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
				.apply { timeZone = TimeZone.getTimeZone("UTC") }
				.format(Date(System.currentTimeMillis() + offsetSec * 1000))
	}

	private val world = World()
	private val a = Node(world, target, Broadcast.DROP_RESPONSE)
	private val b = Node(world, target, Broadcast.DUPLICATE)
	private val signed = mutableListOf<TxSerializer.DeleteCommentOp>()

	/** The vault's account, read by the real port's guards. */
	@Volatile private var vault: String? = account

	/** Applied right after signing: the round trip an account can change inside. */
	@Volatile private var switchAfterSigning: (() -> Unit)? = null
	private val tombstones = mutableListOf<String?>()

	private val deleter: SnapDeleter = run {
		val rpc = HiveRpc(listOf(a.url, b.url))
		val port = HiveSnapPort(
			loadKey = { null },
			storedAccount = { vault },
			broadcaster = HiveBroadcaster(rpc),
			rpc = rpc,
			signDelete = { op ->
				signed += op
				switchAfterSigning?.invoke()
				val n = signed.size
				HivePreparationResult.Ready(
					PreparedHiveTransaction(
						JSONObject().put("n", n).put("operations", JSONArray()).toString(),
						"tx-$n",
						System.currentTimeMillis() / 1000 + 60,
					),
				)
			},
		)
		SnapDeleter(port, sleep = {}, settleAttempts = 2, beforeRetire = { _, _, tx -> tombstones += tx; true })
	}

	@After
	fun stop() {
		a.close()
		b.close()
	}

	private fun broadcastsSent() = a.broadcasts.size + b.broadcasts.size

	@Test
	fun `a later node's rejection after a lost response keeps the original delete guarded`() {
		val outcome = deleter.delete(account, target)

		assertTrue("got $outcome", outcome is SnapDeleter.Outcome.Uncertain)
		assertTrue(deleter.hasUnsettled(account, target))
		assertEquals(1, signed.size)
		assertTrue("node A received it", a.broadcasts.isNotEmpty())
		assertEquals("node B was offered the same bytes", 1, b.broadcasts.size)
		assertEquals(a.broadcasts.first(), b.broadcasts.single())
		assertTrue("the original transaction is what gets reconciled", (a.statusQueries + b.statusQueries).all { it == "tx-1" })

		// Asking again reads the original; it never signs or sends another.
		val sent = broadcastsSent()
		repeat(2) { assertTrue(deleter.delete(account, target) is SnapDeleter.Outcome.Uncertain) }
		assertEquals(1, signed.size)
		assertEquals(sent, broadcastsSent())
	}

	@Test
	fun `a late confirmation of the original is a deletion with its tombstone`() {
		deleter.delete(account, target)
		val sent = broadcastsSent()

		world.deleted = true
		world.status = "within_irreversible_block"

		assertEquals(SnapDeleter.Outcome.Deleted(target.contentId, "tx-1"), deleter.delete(account, target))
		assertEquals(listOf<String?>("tx-1"), tombstones)
		assertFalse(deleter.hasUnsettled(account, target))
		assertEquals(1, signed.size)
		assertEquals(sent, broadcastsSent())
	}

	@Test
	fun `proven expiry of the original releases the guard and only then allows a new delete`() {
		deleter.delete(account, target)
		val sent = broadcastsSent()

		world.status = "expired_irreversible"
		val settled = deleter.delete(account, target)
		assertTrue("got $settled", settled is SnapDeleter.Outcome.Failed)
		assertFalse(deleter.hasUnsettled(account, target))
		assertEquals("proving expiry sends nothing", sent, broadcastsSent())
		assertTrue(tombstones.isEmpty())

		// The existing retry path: a fresh, re-checked, newly signed attempt.
		world.status = "unknown"
		deleter.delete(account, target)
		assertEquals(2, signed.size)
		assertTrue(b.broadcasts.last().contains("\"n\":2"))
		assertTrue(deleter.hasUnsettled(account, target))
	}

	// ── refused on this device, before the network boundary ───────────

	@Test
	fun `an account switch after signing fails truthfully, transmits nothing and locks nothing`() {
		listOf<String?>("bob", null).forEach { next ->
			vault = account
			switchAfterSigning = { vault = next }
			val before = signed.size

			val outcome = deleter.delete(account, target)

			assertTrue("got $outcome", outcome is SnapDeleter.Outcome.Failed)
			val message = (outcome as SnapDeleter.Outcome.Failed).message
			assertTrue(message, message.contains("switched Hive accounts"))
			assertTrue(message, message.contains("Nothing was deleted"))
			assertEquals(before + 1, signed.size)
			assertEquals("zero transmissions", 0, broadcastsSent())
			assertFalse("no false uncertainty", deleter.hasUnsettled(account, target))
			assertTrue("nothing to reconcile", (a.statusQueries + b.statusQueries).isEmpty())
			assertTrue(tombstones.isEmpty())
		}
	}

	@Test
	fun `after a local refusal the account can delete again, and that send is guarded as usual`() {
		switchAfterSigning = { vault = "bob" }
		deleter.delete(account, target)
		assertEquals(0, broadcastsSent())

		vault = account
		switchAfterSigning = null
		val outcome = deleter.delete(account, target)

		assertTrue("got $outcome", outcome is SnapDeleter.Outcome.Uncertain)
		assertEquals(2, signed.size)
		assertTrue(b.broadcasts.single().contains("\"n\":2"))
		assertTrue(deleter.hasUnsettled(account, target))
	}
}
