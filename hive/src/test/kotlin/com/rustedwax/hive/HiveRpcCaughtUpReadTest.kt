package com.rustedwax.hive

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read an edit is signed from (Issue #56): one node's consensus copy, and
 * only from a node that has caught up — with the newest head any current node
 * reports, and with the device clock — at the moment of the read.
 */
class HiveRpcCaughtUpReadTest {

	private val now = 2_000_000_000L

	private fun time(epochSec: Long): String =
		java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
			.apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
			.format(java.util.Date(epochSec * 1000))

	private fun holding(body: String) = JSONObject().put(
		"comments",
		JSONArray().put(
			JSONObject().put("author", "alice").put("permlink", "p1")
				.put("parent_author", "peak.snaps").put("parent_permlink", "c1")
				.put("children", 0).put("net_rshares", 0).put("cashout_time", time(now + 86_400))
				.put("title", "").put("body", body).put("json_metadata", "{}"),
		),
	)

	private val nothing = JSONObject().put("comments", JSONArray())

	private data class Node(val head: Long?, val comments: JSONObject?)

	private fun read(vararg nodes: Node, sleeps: MutableList<Long> = mutableListOf()): HiveCommentRead? {
		val byUrl = nodes.mapIndexed { i, n -> "https://node$i" to n }.toMap()
		return HiveRpc(byUrl.keys.toList()).readCommentCaughtUpAcross(
			author = "alice",
			permlink = "p1",
			headOf = { byUrl.getValue(it).head },
			commentsFrom = { byUrl.getValue(it).comments },
			nowEpochSec = { now },
			sleep = { sleeps += it },
		)
	}

	private fun body(read: HiveCommentRead?) = (read as HiveCommentRead.Present).state.body

	@Test
	fun `a node 45s behind is skipped for the caught-up node's copy`() {
		val r = read(Node(now - 45, holding("old")), Node(now, holding("changed")))
		assertEquals("changed", body(r))
		assertEquals(now, (r as HiveCommentRead.Present).state.headEpochSec)
	}

	@Test
	fun `a caught-up node's absence is the answer, not a lagging node's copy`() {
		val r = read(Node(now - 45, holding("old")), Node(now, nothing))
		assertTrue("got $r", r is HiveCommentRead.Absent)
	}

	@Test
	fun `four blocks behind the newest head is skipped, even inside the device bound`() {
		assertEquals("new", body(read(Node(now - 12, holding("old")), Node(now, holding("new")))))
	}

	@Test
	fun `the newest node failing its comment read does not let a slower one set the fence`() {
		assertNull(read(Node(now - 9, holding("old")), Node(now, null)))
	}

	@Test
	fun `a head from before the read started is not caught up, even one block behind`() {
		assertEquals("b", body(read(Node(now - 3, holding("a")), Node(now, holding("b")))))
	}

	@Test
	fun `every node 8 to 12 seconds behind means nothing to sign from, after one bounded retry`() {
		val sleeps = mutableListOf<Long>()
		assertNull(read(Node(now - 8, holding("old")), Node(now - 12, holding("old")), sleeps = sleeps))
		assertEquals(1, sleeps.size)
	}

	@Test
	fun `a node that catches up by the second attempt is read against the original start`() {
		var attempt = 0
		val clock = ArrayDeque(listOf(now, now, now + 4)) // start, attempt 1, attempt 2
		val r = HiveRpc(listOf("https://a", "https://b")).readCommentCaughtUpAcross(
			author = "alice",
			permlink = "p1",
			headOf = { url -> if (url == "https://a") (if (attempt == 0) now - 9 else now + 3) else null },
			commentsFrom = { if (attempt == 0) holding("old") else holding("newer") },
			nowEpochSec = { clock.removeFirstOrNull() ?: (now + 4) },
			sleep = { attempt++ },
		)
		// now + 3 is past the start (now), though behind the clock at attempt 2 (now + 4).
		assertEquals("newer", body(r))
		assertEquals(now + 3, (r as HiveCommentRead.Present).state.headEpochSec)
	}

	@Test
	fun `a caught-up node that cannot answer leaves nothing to sign from, after one bounded retry`() {
		val sleeps = mutableListOf<Long>()
		assertNull(read(Node(now - 45, holding("old")), Node(now, null), sleeps = sleeps))
		assertEquals("one short wait, then give up", 1, sleeps.size)
	}

	@Test
	fun `a lone node 45s behind is not caught up, even with every other node unreachable`() {
		assertNull(read(Node(now - 45, holding("old")), Node(null, holding("x"))))
	}

	@Test
	fun `a head in the future is not evidence of anything`() {
		assertEquals("ok", body(read(Node(now + 600, holding("future")), Node(now, holding("ok")))))
	}

	@Test
	fun `a copy naming another object is not an answer`() {
		val other = JSONObject().put(
			"comments",
			JSONArray().put(holding("x").getJSONArray("comments").getJSONObject(0).put("permlink", "p2")),
		)
		assertNull(read(Node(now, other)))
	}
}
