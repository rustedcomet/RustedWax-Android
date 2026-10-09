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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Issue #56: what an edit is built from and settled against, over the wire —
 * the real [SnapEditor], [HiveSnapPort] and [HiveRpc] against local stub nodes.
 *
 * Each node serves two copies of the same comment on purpose: the consensus
 * copy from `database_api.find_comments`, and a *different* presentation copy
 * from `condenser_api.get_content`, standing in for a lagging read. An edit
 * must be signed from, and settled against, the consensus copy of a node
 * proven current — never the other one.
 *
 * Signing and sending go through the port's own seams; no key exists here.
 */
class SnapEditConsensusReadTest {

	private val account = "alice"
	private val root = SnapReplyTarget.of(account, "rustedwax-snap-1000-aaaaaa")!!

	private val tailNow = "\n\nhttps://youtu.be/NEWNEWNEW01\n\n#scrobblelife #scrobble #rustedwax"
	private val tailOld = "\n\nhttps://youtu.be/OLDOLDOLD01\n\n#scrobblelife #scrobble #rustedwax"
	private val metaNow = """{"app":"rustedwax/0.13.0","tags":["scrobblelife"]}"""
	private val metaOld = """{"app":"rustedwax/0.11.0","tags":["old"]}"""

	private fun comment(body: String, meta: String) = JSONObject()
		.put("author", account).put("permlink", root.permlink)
		.put("parent_author", "peak.snaps").put("parent_permlink", "snap-container-1791463680")
		.put("children", 0).put("net_rshares", 0).put("cashout_time", chainTime(6 * 86_400))
		.put("title", "").put("body", body).put("json_metadata", meta)

	/** What every node answers; changed by the tests as the chain "moves". */
	private class World {
		@Volatile var headLagSec = 0L
		@Volatile var consensus: JSONObject? = null
		@Volatile var presentation: JSONObject? = null
	}

	private val world = World()
	private val nodes = List(2) { Node(world) }
	private val signed = mutableListOf<TxSerializer.CommentOp>()
	private var sendResult: HiveRpc.BroadcastResult = HiveRpc.BroadcastResult.NetworkFailure("lost")

	private val editor = run {
		val rpc = HiveRpc(nodes.map { it.url })
		SnapEditor(
			HiveSnapPort(
				loadKey = { null },
				storedAccount = { account },
				broadcaster = HiveBroadcaster(rpc),
				rpc = rpc,
				sign = { op ->
					signed += op
					HivePreparationResult.Ready(
						PreparedHiveTransaction("{}", "tx-${signed.size}", System.currentTimeMillis() / 1000 + 60),
					)
				},
				send = { sendResult },
			),
		)
	}

	@After
	fun stop() = nodes.forEach { it.close() }

	@Test
	fun `an edit is signed from the current node's consensus copy, never the presentation copy`() {
		world.consensus = comment("old words$tailNow", metaNow)
		world.presentation = comment("old words$tailOld", metaOld)
		sendResult = HiveRpc.BroadcastResult.Success("tx-1", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)

		val outcome = editor.edit(account, root, SnapEditKind.ROOT, "new words")

		assertTrue("got $outcome", outcome is SnapEditor.Outcome.Edited)
		val op = signed.single()
		assertEquals("new words$tailNow", op.body)
		assertEquals(metaNow, op.jsonMetadata)
		assertEquals("peak.snaps", op.parentAuthor)
		assertEquals("snap-container-1791463680", op.parentPermlink)
	}

	@Test
	fun `a comment current nodes no longer hold is never edited back into existence`() {
		world.consensus = null // deleted
		world.presentation = comment("old words$tailOld", metaOld)

		val outcome = editor.edit(account, root, SnapEditKind.ROOT, "new words")

		assertTrue("got $outcome", outcome is SnapEditor.Outcome.Failed)
		assertTrue("nothing signed", signed.isEmpty())
	}

	@Test
	fun `only stale nodes means nothing is signed`() {
		world.headLagSec = HiveRpc.MAX_NODE_LAG_SEC + 30
		world.consensus = comment("old words$tailNow", metaNow)
		world.presentation = comment("old words$tailNow", metaNow)

		val outcome = editor.edit(account, root, SnapEditKind.ROOT, "new words")

		assertTrue("got $outcome", outcome is SnapEditor.Outcome.Failed)
		assertTrue("nothing signed", signed.isEmpty())
	}

	@Test
	fun `a lagging copy showing the new words is not a confirmation, and a retry signs nothing`() {
		world.consensus = comment("old words$tailNow", metaNow)
		world.presentation = comment("old words$tailNow", metaNow)
		assertTrue(editor.edit(account, root, SnapEditKind.ROOT, "new words") is SnapEditor.Outcome.Uncertain)

		// Only the presentation copy claims the edit; the consensus copy does not.
		world.presentation = comment("new words$tailNow", metaNow)

		repeat(2) {
			val again = editor.edit(account, root, SnapEditKind.ROOT, "new words")
			assertTrue("got $again", again is SnapEditor.Outcome.Uncertain)
		}
		assertEquals("one signature, ever", 1, signed.size)
	}

	// ── action-time freshness: a node behind the action never supplies the edit ─

	private val tailElsewhere = "\n\nhttps://youtu.be/ELSEWHERE01\n\n#scrobblelife #scrobble #rustedwax"
	private val metaElsewhere = """{"app":"peakd/2026.10.1","tags":["scrobblelife"]}"""

	@Test
	fun `a node 45s behind is skipped for the one that has caught up with the newer content`() {
		nodes[0].lagSec = 45
		nodes[0].holds = comment("old words$tailNow", metaNow)
		nodes[1].holds = comment("changed elsewhere$tailElsewhere", metaElsewhere)
		world.presentation = comment("old words$tailNow", metaNow)
		sendResult = HiveRpc.BroadcastResult.Success("tx-1", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)

		editor.edit(account, root, SnapEditKind.ROOT, "new words")

		val op = signed.single()
		assertEquals("new words$tailElsewhere", op.body)
		assertEquals(metaElsewhere, op.jsonMetadata)
	}

	@Test
	fun `a comment deleted on the caught-up node is not edited back from a lagging one`() {
		nodes[0].lagSec = 45
		nodes[0].holds = comment("old words$tailNow", metaNow)
		nodes[1].holdsNothing = true
		world.presentation = comment("old words$tailNow", metaNow)

		val outcome = editor.edit(account, root, SnapEditKind.ROOT, "new words")

		assertTrue("got $outcome", outcome is SnapEditor.Outcome.Failed)
		assertTrue("zero signatures", signed.isEmpty())
	}

	@Test
	fun `no caught-up node able to answer means zero signatures`() {
		nodes[0].lagSec = 45
		nodes[0].holds = comment("old words$tailNow", metaNow)
		nodes[1].commentsFail = true
		world.presentation = comment("old words$tailNow", metaNow)

		val outcome = editor.edit(account, root, SnapEditKind.ROOT, "new words")

		assertTrue("got $outcome", outcome is SnapEditor.Outcome.Failed)
		assertTrue("zero signatures", signed.isEmpty())
	}

	// ── the read must reach the moment the edit started ─────────────────

	@Test
	fun `every node 8 to 12 seconds behind an external change means zero signatures`() {
		// Another frontend changed the Snap 5 s ago; no node has applied it yet.
		nodes[0].lagSec = 8
		nodes[1].lagSec = 12
		world.consensus = comment("old words$tailNow", metaNow)
		world.presentation = comment("changed elsewhere$tailElsewhere", metaElsewhere)

		val outcome = editor.edit(account, root, SnapEditKind.ROOT, "new words")

		assertTrue("got $outcome", outcome is SnapEditor.Outcome.Failed)
		assertTrue("zero signatures", signed.isEmpty())
	}

	@Test
	fun `a node that catches up on the second attempt supplies its newer comment, tail and metadata`() {
		nodes.forEach {
			it.lagSec = 9
			it.holds = comment("old words$tailNow", metaNow)
			it.behindForHeads = 1
			it.caughtUp = comment("changed elsewhere$tailElsewhere", metaElsewhere)
		}
		world.presentation = comment("old words$tailNow", metaNow)
		sendResult = HiveRpc.BroadcastResult.Success("tx-1", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)

		assertTrue(editor.edit(account, root, SnapEditKind.ROOT, "new words") is SnapEditor.Outcome.Edited)

		val op = signed.single()
		assertEquals("new words$tailElsewhere", op.body)
		assertEquals(metaElsewhere, op.jsonMetadata)
		assertEquals("peak.snaps", op.parentAuthor)
	}

	// ── a minimal JSON-RPC node (android.jar has no com.sun.net.httpserver) ──

	private inner class Node(private val world: World) : AutoCloseable {
		private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

		/** Per-node overrides of [world]: how far behind, what it holds, whether it answers. */
		@Volatile var lagSec: Long? = null
		@Volatile var holds: JSONObject? = null
		@Volatile var holdsNothing = false
		@Volatile var commentsFail = false

		/** Answer this many head requests [lagSec] behind, then catch up and hold [caughtUp]. */
		@Volatile var behindForHeads = Int.MAX_VALUE
		@Volatile var caughtUp: JSONObject? = null
		@Volatile private var headsServed = 0

		init {
			Thread {
				while (!server.isClosed) {
					val socket = runCatching { server.accept() }.getOrNull() ?: break
					socket.use { handle(it) }
				}
			}.apply { isDaemon = true }.start()
		}

		val url: String get() = "http://127.0.0.1:${server.localPort}"

		override fun close() = server.close()

		private fun handle(socket: Socket) {
			val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
			var length = 0
			while (true) {
				val line = input.readLine() ?: return
				if (line.isEmpty()) break
				if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
			}
			val body = CharArray(length)
			var read = 0
			while (read < length) {
				val n = input.read(body, read, length - read)
				if (n < 0) return
				read += n
			}
			val request = JSONObject(String(body))
			if (request.getString("method") == "condenser_api.get_dynamic_global_properties" &&
				headsServed++ == behindForHeads
			) {
				lagSec = 0
				caughtUp?.let { holds = it }
			}
			val lag = lagSec ?: world.headLagSec
			if (request.getString("method") == "database_api.find_comments" && commentsFail) {
				val bytes = JSONObject().put("jsonrpc", "2.0").put("id", 1)
					.put("error", JSONObject().put("message", "unavailable")).toString().toByteArray(Charsets.UTF_8)
				val out = socket.getOutputStream()
				out.write(("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
				out.write(bytes)
				out.flush()
				return
			}
			val result: Any = when (request.getString("method")) {
				"condenser_api.get_dynamic_global_properties" -> JSONObject()
					.put("head_block_number", 100)
					.put("head_block_id", "%08x".format(100) + "ab".repeat(16))
					.put("time", chainTime(-lag))
					.put("last_irreversible_block_num", 100)
				"block_api.get_block_header" -> JSONObject().put(
					"header",
					JSONObject().put("previous", "%08x".format(99) + "ab".repeat(16)).put("timestamp", chainTime(-world.headLagSec)),
				)
				"database_api.find_comments" -> {
					val c = if (holdsNothing) null else holds ?: world.consensus
					JSONObject().put("comments", c?.let { JSONArray().put(it) } ?: JSONArray())
				}
				"condenser_api.get_content" -> world.presentation ?: JSONObject().put("author", "")
				"transaction_status_api.find_transaction" -> JSONObject().put("status", "unknown")
				else -> error("unexpected ${request.getString("method")}")
			}
			val bytes = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("result", result)
				.toString().toByteArray(Charsets.UTF_8)
			val out = socket.getOutputStream()
			out.write(
				("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
					"Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
			)
			out.write(bytes)
			out.flush()
		}
	}

	private fun chainTime(offsetSec: Long): String =
		SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
			.apply { timeZone = TimeZone.getTimeZone("UTC") }
			.format(Date(System.currentTimeMillis() + offsetSec * 1000))
}
