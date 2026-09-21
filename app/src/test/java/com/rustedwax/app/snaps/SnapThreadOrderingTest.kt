package com.rustedwax.app.snaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which way a conversation reads.
 *
 * Top-level entries newest first, every subtree oldest first. The two rules
 * meet in one place — [SnapThreadBuilder] — and the thing worth pinning is that
 * they do not contaminate each other: reversing the root's children must not
 * reverse anybody else's, and it must never lift a reply away from the comment
 * it answers just because the reply is newer than its neighbours.
 */
class SnapThreadOrderingTest {

	private val root = "alice/root"

	private fun reply(
		permlink: String,
		parent: String,
		at: Long?,
		author: String = "bob",
	): SnapReply {
		val parentAuthor = parent.substringBefore('/')
		val parentPermlink = parent.substringAfter('/')
		return SnapReply(
			author = author,
			permlink = permlink,
			parentAuthor = parentAuthor,
			parentPermlink = parentPermlink,
			body = permlink,
			createdAtEpochSec = at,
		)
	}

	/** The flat reading order the sheet actually renders. */
	private fun order(thread: SnapThread): List<String> = thread.rows.map { it.reply.permlink }

	@Test
	fun `top-level replies read newest first`() {
		val thread = SnapThreadBuilder.build(
			root,
			listOf(
				reply("oldest", root, 100),
				reply("middle", root, 200),
				reply("newest", root, 300),
			),
		)
		assertEquals(listOf("newest", "middle", "oldest"), order(thread))
	}

	@Test
	fun `a subtree still reads oldest first, under its own parent`() {
		val thread = SnapThreadBuilder.build(
			root,
			listOf(
				reply("top-old", root, 100),
				reply("top-new", root, 400),
				// Answers to the older top-level comment, in the order they were
				// written. These must stay forwards and must stay under it.
				reply("child-a", "bob/top-old", 150),
				reply("child-b", "bob/top-old", 250),
				reply("child-c", "bob/top-old", 350),
			),
		)
		assertEquals(
			listOf("top-new", "top-old", "child-a", "child-b", "child-c"),
			order(thread),
		)
	}

	@Test
	fun `a newer child never overtakes the parent it answers`() {
		// `child` is newer than every top-level comment. Flattening the tree by
		// timestamp would hoist it to the front; grouping keeps it under `old`.
		val thread = SnapThreadBuilder.build(
			root,
			listOf(
				reply("old", root, 100),
				reply("new", root, 200),
				reply("child", "bob/old", 999),
			),
		)
		assertEquals(listOf("new", "old", "child"), order(thread))
		val old = thread.children.single { it.reply.permlink == "old" }
		assertEquals(listOf("child"), old.children.map { it.reply.permlink })
	}

	@Test
	fun `nesting depth and its bound are unchanged`() {
		var parent = root
		val rows = mutableListOf<SnapReply>()
		repeat(SnapThreadBuilder.MAX_INDENT + 3) { i ->
			val permlink = "d$i"
			rows += reply(permlink, parent, 100L + i)
			parent = "bob/$permlink"
		}
		val thread = SnapThreadBuilder.build(root, rows)
		// One chain, so reading order is simply the chain.
		assertEquals(rows.map { it.permlink }, order(thread))
		assertTrue(
			"indentation stays bounded however deep the conversation goes",
			thread.rows.all { it.depth <= SnapThreadBuilder.MAX_INDENT },
		)
	}

	@Test
	fun `an undated top-level comment never displaces a dated one from the top`() {
		val thread = SnapThreadBuilder.build(
			root,
			listOf(
				reply("dated-old", root, 100),
				reply("undated", root, null),
				reply("dated-new", root, 300),
			),
		)
		// Newest first, and the one RustedWax cannot date sorts last rather than
		// claiming the position that makes a claim.
		assertEquals(listOf("dated-new", "dated-old", "undated"), order(thread))
	}

	/**
	 * A reply written a moment ago is merged into the chain answer before the
	 * tree is built, so the optimistic row has to land where the confirmed one
	 * eventually will: at the top, for a top-level reply.
	 */
	@Test
	fun `a freshly staged top-level reply lands at the top`() {
		val chain = listOf(reply("older", root, 100), reply("old", root, 200))
		val staged = reply("just-sent", root, 900, author = "alice")
		val thread = SnapThreadBuilder.build(root, chain + staged)
		assertEquals(listOf("just-sent", "old", "older"), order(thread))
	}

	/** And a staged *targeted* reply lands under the comment it answers. */
	@Test
	fun `a freshly staged targeted reply lands under its parent`() {
		val chain = listOf(reply("a", root, 100), reply("b", root, 200))
		val staged = reply("just-sent", "bob/a", 900, author = "alice")
		val thread = SnapThreadBuilder.build(root, chain + staged)
		assertEquals(listOf("b", "a", "just-sent"), order(thread))
		assertEquals(
			listOf("just-sent"),
			thread.children.single { it.reply.permlink == "a" }.children.map { it.reply.permlink },
		)
	}

	/**
	 * The confirmed copy and the optimistic copy are the same comment.
	 *
	 * `author/permlink` is unique on chain, and the builder de-duplicates on it,
	 * so reconciliation needs no extra step: once the chain answer carries the
	 * reply, merging the local row in again cannot draw it twice.
	 */
	@Test
	fun `a staged reply already on chain is not drawn twice`() {
		val confirmed = reply("just-sent", root, 900, author = "alice")
		val staged = reply("just-sent", root, 900, author = "alice")
		val thread = SnapThreadBuilder.build(root, listOf(confirmed, staged))
		assertEquals(listOf("just-sent"), order(thread))
	}
}
