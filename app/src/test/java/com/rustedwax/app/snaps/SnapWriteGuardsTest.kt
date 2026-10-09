package com.rustedwax.app.snaps

import com.rustedwax.hive.PreparedHiveTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The slot itself (Issue #56, 3A): one per account and exact comment, freed only by its holder. */
class SnapWriteGuardsTest {

	private val a = SnapReplyTarget.of("alice", "rustedwax-reply-1-a")!!
	private val b = SnapReplyTarget.of("alice", "rustedwax-reply-2-b")!!
	private val tx = PreparedHiveTransaction("{}", "tx-1", 2_000_000_060L)

	@Test
	fun `a reservation is exclusive for that comment, whichever operation asks`() {
		val g = SnapWriteGuards()
		assertNotNull(g.reserve("alice", a, SnapWriteGuards.Operation.EDIT))
		assertNull(g.reserve("alice", a, SnapWriteGuards.Operation.EDIT))
		assertNull(g.reserve("alice", a, SnapWriteGuards.Operation.DELETE))
		assertNull("account names compare without case", g.reserve("ALICE", a, SnapWriteGuards.Operation.DELETE))
	}

	@Test
	fun `other comments and other accounts are free`() {
		val g = SnapWriteGuards()
		g.reserve("alice", a, SnapWriteGuards.Operation.DELETE)
		assertNotNull(g.reserve("alice", b, SnapWriteGuards.Operation.EDIT))
		assertNotNull(g.reserve("bob", a, SnapWriteGuards.Operation.EDIT))
	}

	@Test
	fun `only the holder can attach its attempt or release the slot`() {
		val g = SnapWriteGuards()
		val mine = g.reserve("alice", a, SnapWriteGuards.Operation.DELETE)!!
		assertFalse(g.attach("alice", a, SnapWriteGuards.DeleteAttempt(mine.id + 1, tx)))
		assertTrue(g.attach("alice", a, SnapWriteGuards.DeleteAttempt(mine.id, tx)))
		assertEquals(SnapWriteGuards.DeleteAttempt(mine.id, tx), g.current("alice", a))

		assertFalse("another id never frees it", g.release("alice", a, mine.id + 1))
		assertNotNull(g.current("alice", a))
		assertTrue(g.release("alice", a, mine.id))
		assertNull(g.current("alice", a))
		assertFalse("releasing twice frees nothing", g.release("alice", a, mine.id))
	}

	@Test
	fun `a stale release cannot free the next holder`() {
		val g = SnapWriteGuards()
		val first = g.reserve("alice", a, SnapWriteGuards.Operation.DELETE)!!
		g.release("alice", a, first.id)
		val second = g.reserve("alice", a, SnapWriteGuards.Operation.EDIT)!!
		assertFalse(g.release("alice", a, first.id))
		assertEquals(second, g.current("alice", a))
	}

	// ── 3C: the durable record ─────────────────────────────────────────

	@Test
	fun `an attempt round-trips exactly, and only attempts are ever stored`() {
		val edit = SnapWriteGuards.EditAttempt(7, SnapEditKind.ROOT, "words", "words\n\ntail", tx)
		val raw = SnapWriteGuards.encode("Alice", a, edit)!!
		assertEquals("alice|${a.contentId}" to edit, SnapWriteGuards.decode(raw))
		val del = SnapWriteGuards.DeleteAttempt(8, tx)
		assertEquals("alice|${a.contentId}" to del, SnapWriteGuards.decode(SnapWriteGuards.encode("alice", a, del)!!))
		assertNull(SnapWriteGuards.encode("alice", a, SnapWriteGuards.Reserved(9, SnapWriteGuards.Operation.EDIT)))
		assertNull(SnapWriteGuards.encode("alice", a, SnapWriteGuards.Locked(10)))
	}

	@Test
	fun `a record that is not exactly one well-formed attempt is not read as one`() {
		val good = org.json.JSONObject(SnapWriteGuards.encode("alice", a, SnapWriteGuards.DeleteAttempt(3, tx))!!)
		fun without(field: String) = org.json.JSONObject(good.toString()).apply { remove(field) }.toString()
		fun with(field: String, value: Any) = org.json.JSONObject(good.toString()).put(field, value).toString()
		listOf(
			"", "{}", "not json",
			with("v", 2), with("op", "vote"), with("id", 0), with("txId", ""), with("signed", ""),
			with("expiration", 0), with("author", "Not A Name"), without("txId"), without("permlink"),
			with("op", "edit"), // an edit with no kind, text or body
		).forEach { raw -> assertNull("read '$raw'", SnapWriteGuards.decode(raw)) }
	}
}
