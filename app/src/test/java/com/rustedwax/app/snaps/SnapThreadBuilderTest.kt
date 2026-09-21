package com.rustedwax.app.snaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The render model a thread screen is built from.
 *
 * Every case here is a shape the chain can genuinely return — including the
 * broken ones. The builder's contract is that none of them produces a crash, an
 * infinite walk, or a node in a place the data did not put it.
 */
class SnapThreadBuilderTest {

	private val root = "alice/rustedwax-snap-1000-aaaaaa"

	private fun reply(
		author: String,
		permlink: String,
		parent: String,
		createdAtEpochSec: Long? = 1_000L,
		body: String = "text",
	): SnapReply {
		val (pa, pp) = parent.split("/", limit = 2)
		return SnapReply(author, permlink, pa, pp, body, createdAtEpochSec)
	}

	@Test
	fun `direct replies hang off the root, newest first`() {
		val thread = SnapThreadBuilder.build(
			root,
			listOf(
				reply("bob", "r2", root, createdAtEpochSec = 2_000),
				reply("carol", "r1", root, createdAtEpochSec = 1_500),
			),
		)

		assertEquals(2, thread.total)
		// Newest first at the top level — see [SnapThreadBuilder.build].
		assertEquals(listOf("bob/r2", "carol/r1"), thread.children.map { it.reply.contentId })
		assertTrue(thread.children.all { it.depth == 0 })
	}

	@Test
	fun `nested replies keep their nesting and their indent`() {
		val thread = SnapThreadBuilder.build(
			root,
			listOf(
				reply("bob", "r1", root),
				reply("carol", "r2", "bob/r1", createdAtEpochSec = 1_100),
				reply("dave", "r3", "carol/r2", createdAtEpochSec = 1_200),
			),
		)

		assertEquals(3, thread.total)
		val flat = thread.rows
		assertEquals(listOf("bob/r1", "carol/r2", "dave/r3"), flat.map { it.reply.contentId })
		assertEquals(listOf(0, 1, 2), flat.map { it.depth })
		// Reading order is depth-first, so a reply sits directly under the
		// comment it answers rather than after every sibling of that comment.
		assertEquals(1, thread.children.size)
	}

	@Test
	fun `indentation stops growing but nesting does not`() {
		val chain = mutableListOf<SnapReply>()
		var parent = root
		repeat(12) { i ->
			chain += reply("bob", "r$i", parent, createdAtEpochSec = 1_000L + i)
			parent = "bob/r$i"
		}

		val flat = SnapThreadBuilder.build(root, chain).rows

		assertEquals(12, flat.size)
		assertEquals(SnapThreadBuilder.MAX_INDENT, flat.last().depth)
		assertTrue(flat.all { it.depth <= SnapThreadBuilder.MAX_INDENT })
	}

	/** A reply whose parent is not in the response cannot be placed. */
	@Test
	fun `drops orphans rather than inventing a position for them`() {
		val thread = SnapThreadBuilder.build(
			root,
			listOf(
				reply("bob", "r1", root),
				reply("carol", "r2", "nobody/missing"),
			),
		)

		assertEquals(1, thread.total)
		assertEquals(listOf("bob/r1"), thread.rows.map { it.reply.contentId })
	}

	@Test
	fun `a comment that is its own parent is dropped`() {
		val thread = SnapThreadBuilder.build(root, listOf(reply("bob", "r1", "bob/r1")))

		assertEquals(0, thread.total)
		assertTrue(thread.children.isEmpty())
	}

	/**
	 * Two comments claiming each other as parent. Neither can be reached from
	 * the root, and the walk must not follow the loop looking.
	 */
	@Test
	fun `a cycle is dropped and does not hang the walk`() {
		val thread = SnapThreadBuilder.build(
			root,
			listOf(
				reply("bob", "r1", "carol/r2"),
				reply("carol", "r2", "bob/r1"),
				reply("dave", "r3", root),
			),
		)

		assertEquals(1, thread.total)
		assertEquals(listOf("dave/r3"), thread.rows.map { it.reply.contentId })
	}

	@Test
	fun `a repeated content id is kept once`() {
		val thread = SnapThreadBuilder.build(
			root,
			listOf(
				reply("bob", "r1", root, body = "first"),
				reply("bob", "r1", root, body = "second"),
			),
		)

		assertEquals(1, thread.total)
		assertEquals("first", thread.children.single().reply.body)
	}

	@Test
	fun `the root echoed back is not a reply to itself`() {
		val thread = SnapThreadBuilder.build(
			root,
			listOf(
				reply("alice", "rustedwax-snap-1000-aaaaaa", "peak.snaps/snap-container-1"),
				reply("bob", "r1", root),
			),
		)

		assertEquals(1, thread.total)
	}

	// ── every valid reply stays reachable ──────────────────────────────

	/**
	 * The conversation immediately past the old 500-node cut-off.
	 *
	 * 501 replies under one Snap is an ordinary thing for a popular post to
	 * have, and the dedicated thread exists precisely so the whole of it can be
	 * read. The 501st used to be discarded without a word.
	 */
	@Test
	fun `501 replies are all reachable`() {
		val many = (0 until 501).map { reply("bob", "r$it", root, createdAtEpochSec = 1_000L + it) }

		val thread = SnapThreadBuilder.build(root, many)

		assertEquals(501, thread.total)
		assertEquals(501, thread.rows.size)
		assertEquals(501, thread.children.size)
		assertEquals(
			"every id the chain supplied is present exactly once",
			many.map { it.contentId }.toSet(),
			thread.rows.map { it.reply.contentId }.toSet(),
		)
	}

	@Test
	fun `more than a thousand replies are all reachable`() {
		val many = (0 until 2_500).map {
			reply("bob", "r$it", root, createdAtEpochSec = 1_000L + it)
		}

		val thread = SnapThreadBuilder.build(root, many)

		assertEquals(2_500, thread.total)
		assertEquals(2_500, thread.rows.size)
		assertEquals(many.map { it.contentId }.toSet(), thread.rows.map { it.reply.contentId }.toSet())
	}

	/** Nesting is preserved across the old boundary, not flattened past it. */
	@Test
	fun `a chain of 1200 nested replies keeps its structure`() {
		val chain = mutableListOf<SnapReply>()
		var parent = root
		repeat(1_200) { i ->
			chain += reply("bob", "r$i", parent, createdAtEpochSec = 1_000L + i)
			parent = "bob/r$i"
		}

		val thread = SnapThreadBuilder.build(root, chain)

		assertEquals(1_200, thread.total)
		// One top-level reply, and every other node hanging off the one before.
		assertEquals(1, thread.children.size)
		assertEquals(
			(0 until 1_200).map { "bob/r$it" },
			thread.rows.map { it.reply.contentId },
		)
		// Each node still holds its own child, all the way down.
		var node = thread.children.single()
		repeat(1_199) {
			assertEquals(1, node.children.size)
			node = node.children.single()
		}
		assertTrue(node.children.isEmpty())
	}

	/**
	 * Wide and deep at once, straddling the old cut-off: reading order is still
	 * depth-first, so a reply sits directly under the comment it answers.
	 */
	@Test
	fun `nested replies crossing the old boundary stay in reading order`() {
		val replies = mutableListOf<SnapReply>()
		// 300 top-level replies, each with two children: 900 nodes in total.
		repeat(300) { i ->
			replies += reply("bob", "t$i", root, createdAtEpochSec = 1_000L + i)
			replies += reply("carol", "t$i-a", "bob/t$i", createdAtEpochSec = 2_000L + i)
			replies += reply("dave", "t$i-b", "bob/t$i", createdAtEpochSec = 3_000L + i)
		}

		val thread = SnapThreadBuilder.build(root, replies)

		assertEquals(900, thread.total)
		assertEquals(300, thread.children.size)
		val ids = thread.rows.map { it.reply.contentId }
		// The 200th top-level reply and its children are past the old cap, and
		// they are adjacent and correctly ordered.
		val at = ids.indexOf("bob/t250")
		assertEquals(listOf("bob/t250", "carol/t250-a", "dave/t250-b"), ids.subList(at, at + 3))
		assertEquals(listOf(0, 1, 1), thread.rows.subList(at, at + 3).map { it.depth })
	}

	/** Depth is not recursion here: a very deep thread must not overflow a stack. */
	@Test
	fun `an extremely deep conversation builds without recursing`() {
		val chain = mutableListOf<SnapReply>()
		var parent = root
		repeat(20_000) { i ->
			chain += reply("bob", "r$i", parent, createdAtEpochSec = 1_000L + i)
			parent = "bob/r$i"
		}

		val thread = SnapThreadBuilder.build(root, chain)

		assertEquals(20_000, thread.total)
		assertEquals(20_000, thread.rows.size)
		assertEquals(SnapThreadBuilder.MAX_INDENT, thread.rows.last().depth)
	}

	@Test
	fun `an empty response is an empty thread, not a failure`() {
		val thread = SnapThreadBuilder.build(root, emptyList())

		assertEquals(0, thread.total)
		assertTrue(thread.rows.isEmpty())
	}

	/** `View replies (N)` must never promise a reply the thread cannot show. */
	@Test
	fun `total counts only what the tree actually holds`() {
		val thread = SnapThreadBuilder.build(
			root,
			listOf(
				reply("bob", "r1", root),
				reply("carol", "r2", "nobody/missing"),
				reply("dave", "r3", "dave/r3"),
			),
		)

		assertEquals(thread.rows.size, thread.total)
	}
}
