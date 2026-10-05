package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.SnapMedia
import com.rustedwax.app.snaps.SnapPayloadBuilder
import com.rustedwax.hive.HiveCommentRead
import com.rustedwax.hive.HiveCommentState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 47B: which chain objects are RustedWax root Snaps, and how a bounded,
 * one-node-per-pass scan walks an account's comments. Fixtures copy the shapes
 * `bridge.get_account_posts` returned live on 2026-10-04; no network is used.
 */
class MySnapsDiscoveryTest {

	private val base = 1_791_000_000L

	private fun iso(sec: Long): String =
		java.time.Instant.ofEpochSecond(sec).toString().removeSuffix("Z")

	private fun comment(
		permlink: String,
		created: Long,
		author: String = "alice",
		parentAuthor: String = "peak.snaps",
		parentPermlink: String = "snap-container-1790947440",
		depth: Any = 1,
		app: Any? = JSONObject().put("app", "rustedwax/0.13.0"),
		body: String = SnapPayloadBuilder.build("words", SnapMedia("dQw4w9WgXcQ")).body,
	) = JSONObject()
		.put("author", author)
		.put("permlink", permlink)
		.put("parent_author", parentAuthor)
		.put("parent_permlink", parentPermlink)
		.put("depth", depth)
		.put("json_metadata", app ?: JSONObject.NULL)
		.put("body", body)
		.put("created", iso(created))

	private fun root(n: Long, created: Long = base + n) =
		comment("rustedwax-snap-$created-abc${n.toString().padStart(3, '0')}", created)

	// ── Positive identification ──────────────────────────────────────────

	@Test
	fun `a RustedWax root Snap with every piece of evidence is identified, words and video from its body`() {
		val row = MySnapsIdentity.root("alice", root(1))
		assertNotNull(row)
		assertEquals("alice", row!!.author)
		assertEquals("words", row.userText)
		assertEquals("dQw4w9WgXcQ", row.videoId)
		assertEquals(base + 1, row.createdAtEpochSec)
		assertNull("nothing is guessed", row.title)
		assertNull(row.eventId)
	}

	@Test
	fun `metadata sent as a string is read the same way`() {
		val o = root(1).put("json_metadata", """{"app":"rustedwax/0.12.2","tags":["scrobblelife"]}""")
		assertNotNull(MySnapsIdentity.root("alice", o))
	}

	@Test
	fun `any missing or contradictory evidence refuses the object`() {
		val ok = root(1)
		val good = ok.getString("permlink")
		val refused = mapOf(
			"another author" to comment(good, base + 1, author = "bob"),
			"a reply to a Snap" to comment(good, base + 1, parentAuthor = "alice", depth = 2),
			"depth 2 under the container" to comment(good, base + 1, depth = 2),
			"depth that is not a whole number" to comment(good, base + 1, depth = 1.0),
			"another parent account" to comment(good, base + 1, parentAuthor = "ecency.waves"),
			"a parent that is not a container" to comment(good, base + 1, parentPermlink = "some-post"),
			"PeakD metadata (a Snap edited elsewhere)" to comment(good, base + 1, app = JSONObject().put("app", "peakd/2026.7.5")),
			"no metadata" to comment(good, base + 1, app = null),
			"unreadable metadata" to comment(good, base + 1, app = "{not json"),
			"metadata without app" to comment(good, base + 1, app = JSONObject().put("tags", "x")),
			"a PeakD-shaped permlink" to comment("re-peaksnaps-ckrnnima", base + 1),
			"a RustedWax reply permlink" to comment("rustedwax-reply-${base + 1}-abcdef", base + 1),
			"a permlink minted before Snaps existed" to comment("rustedwax-snap-1700000000-abcdef", base + 1),
			"a malformed RustedWax permlink" to comment("rustedwax-snap-${base + 1}-ABCDEF", base + 1),
			"no frozen tail" to comment(good, base + 1, body = "words\n\nhttps://images.example/x.jpeg\n"),
			"a tail with a different hashtag line" to comment(good, base + 1, body = "words\n\nhttps://youtu.be/dQw4w9WgXcQ\n\n#snaps"),
			"no creation time" to ok.put("created", "yesterday"),
		)
		refused.forEach { (why, o) -> assertNull(why, MySnapsIdentity.root("alice", o)) }
	}

	@Test
	fun `nobody signed in identifies nothing`() {
		assertNull(MySnapsIdentity.root("", root(1)))
		assertNull(MySnapsIdentity.root("-", root(1)))
	}

	@Test
	fun `the three-way answer separates proven absence, presence and no decision`() {
		val state = HiveCommentState("alice", "p", "peak.snaps", "c", 0, 0, null, 0)
		assertEquals(true, MySnapsIdentity.absence(listOf(HiveCommentRead.Absent("a"), HiveCommentRead.Absent("b"))))
		assertEquals(false, MySnapsIdentity.absence(listOf(HiveCommentRead.Present(state, "a"))))
		assertEquals(false, MySnapsIdentity.absence(listOf(HiveCommentRead.Absent("a"), HiveCommentRead.Absent("b"), HiveCommentRead.Present(state, "c"))))
		assertNull("offline / stale nodes", MySnapsIdentity.absence(emptyList()))
		assertNull("one node", MySnapsIdentity.absence(listOf(HiveCommentRead.Absent("a"))))
	}

	@Test
	fun `absence is proven only by two current nodes and no presence`() {
		val state = HiveCommentState("alice", "p", "peak.snaps", "c", 0, 0, null, 0)
		assertTrue(MySnapsIdentity.provenAbsent(listOf(HiveCommentRead.Absent("a"), HiveCommentRead.Absent("b"))))
		assertFalse("one node", MySnapsIdentity.provenAbsent(listOf(HiveCommentRead.Absent("a"))))
		assertFalse("the same node twice", MySnapsIdentity.provenAbsent(listOf(HiveCommentRead.Absent("a"), HiveCommentRead.Absent("a"))))
		assertFalse("no answer at all", MySnapsIdentity.provenAbsent(emptyList()))
		assertFalse(
			"any node still has it",
			MySnapsIdentity.provenAbsent(listOf(HiveCommentRead.Absent("a"), HiveCommentRead.Absent("b"), HiveCommentRead.Present(state, "c"))),
		)
	}

	// ── Checkpoint ───────────────────────────────────────────────────────

	@Test
	fun `a checkpoint round-trips and an unreadable one is a fresh start`() {
		val cp = CatalogCheckpoint(123L, false, "n1", ChainCursor("alice", "p"), 456L, 789L, "q")
		assertEquals(cp, CatalogCheckpoint.decode(cp.encode()))
		assertEquals(CatalogCheckpoint(), CatalogCheckpoint.decode(null))
		assertEquals(CatalogCheckpoint(), CatalogCheckpoint.decode("garbage"))
		assertEquals(CatalogCheckpoint(), CatalogCheckpoint.decode("""{"v":2,"highWater":1}"""))
		assertEquals("my_snaps_catalog", CatalogCheckpoint.SCOPE)
		// Half a rotation cursor is no cursor.
		val half = CatalogCheckpoint.decode("""{"v":1,"highWater":null,"deepDone":true,"resumeNode":null,"resume":null,"lastSuccessMs":1,"verifyAt":5}""")
		assertNull(half.verifyAt)
		assertNull(half.verifyPermlink)
	}

	// ── Paging ───────────────────────────────────────────────────────────

	/** An account's comments as a node pages them: newest first, 20 at a time, cursor repeated. */
	private class Node(val comments: List<JSONObject>, var failAfter: Int = Int.MAX_VALUE) {
		val calls = mutableListOf<ChainCursor?>()
		fun page(after: ChainCursor?): List<JSONObject> {
			if (calls.size >= failAfter) error("node down")
			calls += after
			val start = after?.let { c ->
				comments.indexOfFirst { it.getString("author") == c.author && it.getString("permlink") == c.permlink }
			} ?: 0
			return comments.drop(start).take(MySnapsDiscovery.PAGE_SIZE)
		}
	}

	/** [n] comments, newest first, one an hour; every [every]th is a RustedWax root Snap. */
	private fun account(n: Int, every: Int = 5, newest: Long = base + 100_000): List<JSONObject> =
		(0 until n).map { i ->
			val at = newest - i * 3600L
			if (i % every == 0) comment("rustedwax-snap-$at-r${i.toString().padStart(5, '0')}", at)
			else comment("re-other-$i", at, parentAuthor = "someone", parentPermlink = "post", depth = 2, app = JSONObject().put("app", "leothreads/0.3"))
		}

	private fun discovery(nodes: Map<String, Node>, budget: Int = MySnapsDiscovery.PAGE_BUDGET) =
		MySnapsDiscovery(nodes.keys.toList(), { node, _, after -> nodes.getValue(node).page(after) }, budget)

	@Test
	fun `a first pass walks every page down to the end, without duplicates, and only imports roots`() {
		val n = Node(account(95))
		val result = discovery(mapOf("n1" to n)).pass("alice", CatalogCheckpoint(), 1L) as MySnapsDiscovery.Result.Done
		assertEquals(19, result.found.size)
		assertEquals(19, result.found.map { it.contentId }.toSet().size)
		assertEquals(95, result.seen.size)
		assertTrue(result.checkpoint.deepDone)
		assertEquals(base + 100_000, result.checkpoint.highWater)
		assertEquals(1L, result.checkpoint.lastSuccessMs)
		assertEquals(5, n.calls.size)
	}

	@Test
	fun `scanning stops at the floor rather than reading the account's whole life`() {
		val newest = MySnapsIdentity.FLOOR_EPOCH_SEC + 10 * 3600
		val n = Node(account(200, newest = newest))
		val result = discovery(mapOf("n1" to n)).pass("alice", CatalogCheckpoint(), 1L) as MySnapsDiscovery.Result.Done
		assertTrue(result.checkpoint.deepDone)
		assertEquals("the second page is wholly below the floor", 2, n.calls.size)
	}

	@Test
	fun `a later pass reads only the top, down to a little before the last high water`() {
		val comments = account(400)
		val n = Node(comments)
		val cp = CatalogCheckpoint(highWater = base + 100_000 - 20 * 3600, deepDone = true, lastSuccessMs = 1L)
		val result = discovery(mapOf("n1" to n)).pass("alice", cp, 2L) as MySnapsDiscovery.Result.Done
		// 20h below the top plus the 6h overlap: the third page is the first wholly below it.
		assertEquals(3, n.calls.size)
		assertTrue(result.checkpoint.deepDone)
		assertEquals(base + 100_000, result.checkpoint.highWater)
		assertEquals("the top of a pass is now", maxOf(base + 100_000, 2L / 1000), result.coveredTo)
	}

	@Test
	fun `an unfinished deep scan resumes where it stopped on the same node`() {
		val n = Node(account(200))
		val d = discovery(mapOf("n1" to n), budget = 3)
		val first = d.pass("alice", CatalogCheckpoint(), 1L) as MySnapsDiscovery.Result.Done
		assertFalse(first.checkpoint.deepDone)
		assertEquals("n1", first.checkpoint.resumeNode)
		assertEquals(3, n.calls.size)
		val all = first.found.map { it.contentId }.toMutableSet()
		var cp = first.checkpoint
		repeat(10) {
			if (cp.deepDone) return@repeat
			val next = d.pass("alice", cp, 2L) as MySnapsDiscovery.Result.Done
			all += next.found.map { it.contentId }
			cp = next.checkpoint
		}
		assertTrue(cp.deepDone)
		assertEquals("every root found exactly once across passes", 40, all.size)
	}

	@Test
	fun `a failing node restarts the pass on the next node from the top`() {
		val comments = account(60)
		val bad = Node(comments, failAfter = 1)
		val good = Node(comments)
		val result = discovery(mapOf("bad" to bad, "good" to good)).pass("alice", CatalogCheckpoint(), 1L) as MySnapsDiscovery.Result.Done
		assertEquals(12, result.found.size)
		assertNull("the good node started from the top, not the bad node's cursor", good.calls.first())
		assertTrue(result.checkpoint.deepDone)
	}

	@Test
	fun `when every node fails the pass fails and reports nothing found`() {
		val comments = account(60)
		val result = discovery(mapOf("a" to Node(comments, failAfter = 1), "b" to Node(comments, failAfter = 0)))
			.pass("alice", CatalogCheckpoint(), 1L)
		assertTrue(result is MySnapsDiscovery.Result.Failed)
	}

	@Test
	fun `an account with no comments is a complete, empty scan`() {
		val result = discovery(mapOf("n1" to Node(emptyList()))).pass("alice", CatalogCheckpoint(), 1L) as MySnapsDiscovery.Result.Done
		assertTrue(result.found.isEmpty())
		assertTrue(result.checkpoint.deepDone)
		assertNull(result.coveredFrom)
	}
}
