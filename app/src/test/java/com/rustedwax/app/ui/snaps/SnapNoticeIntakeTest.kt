package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.PendingSnap
import com.rustedwax.app.snaps.PendingSnapRead
import com.rustedwax.app.snaps.PendingSnapStore
import com.rustedwax.app.snaps.SnapReply
import com.rustedwax.app.snaps.SnapReplyTarget
import com.rustedwax.app.snaps.SnapThreadPreview
import com.rustedwax.app.snaps.SnapThreadReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the bell's facts actually come from.
 *
 * Stage 6 adds no request of its own: an incoming reply is learned from the
 * thread read History was already doing for every posted Snap on screen. This
 * file pins that supply line — that a read reports what it found, that it
 * reports it under the account the read belonged to, that a failed read reports
 * nothing at all, and that asking the bell for a conversation costs no network.
 */
class SnapNoticeIntakeTest {

	private val alice = "alice"
	private val root = SnapReplyTarget.of(alice, "rustedwax-snap-1000-aaaaaa")!!

	private class Reader(var replies: List<SnapReply>?) : SnapThreadReader {
		var reads = 0
		/** Runs while a read is in flight, so an account can change under it. */
		var duringRead: (() -> Unit)? = null
		override fun read(
			rootAuthor: String,
			rootPermlink: String,
			viewer: String?,
		): List<SnapReply>? {
			reads++
			duringRead?.invoke()
			return replies
		}
	}

	private class Drafts : SnapReplyDraftStore {
		override fun read(key: String): SnapReplyDraftRead = SnapReplyDraftRead.Absent
		override fun writeText(key: String, text: String) = Unit
		override fun beginIntent(key: String, intentId: String) = true
		override fun removeIf(key: String, expected: SnapReplyDraft?) = true
		override fun settle(key: String, published: SnapReplyDraft) = SnapReplySettlement.Untouched
	}

	private class Previews : SnapThreadPreviewStore {
		override fun read(key: String): SnapThreadPreview.Preview? = null
		override fun write(key: String, preview: SnapThreadPreview.Preview) = Unit
	}

	private class Store : PendingSnapStore {
		override fun read(account: String, eventId: String) = PendingSnapRead.Absent
		override fun write(snap: PendingSnap) = true
		override fun clear(account: String, eventId: String) = Unit
		override fun all(account: String) = emptyList<PendingSnap>()
		override fun corruptEventIds(account: String) = emptySet<String>()
	}

	/** Everything one chain read reported, in order. */
	private class Seen {
		val rows = mutableListOf<Triple<String, SnapReplyTarget, List<SnapReply>>>()
		val hook: (String, SnapReplyTarget, List<SnapReply>) -> Unit =
			{ who, target, replies -> rows += Triple(who, target, replies) }
	}

	private fun controller(
		reader: Reader,
		seen: Seen,
		account: () -> String?,
	) = SnapThreadController(
		scope = CoroutineScope(Dispatchers.Unconfined),
		reader = { reader },
		// Never used by any test here: nothing in this file sends anything.
		publisher = { null },
		account = account,
		drafts = Drafts(),
		previewStore = Previews(),
		onChainRead = seen.hook,
		io = Dispatchers.Unconfined,
	)

	private fun reply(author: String, permlink: String, parent: SnapReplyTarget) =
		SnapReply(author, permlink, parent.author, parent.permlink, "hi", 1_000L)

	@Test
	fun `a successful read reports its rows under the account that made it`() {
		val rows = listOf(reply("bob", "r1", root))
		val seen = Seen()
		controller(Reader(rows), seen) { alice }.load(root)

		assertEquals(1, seen.rows.size)
		val (who, target, reported) = seen.rows.single()
		assertEquals(alice, who)
		assertEquals(root, target)
		assertEquals(rows, reported)
	}

	@Test
	fun `a read that failed reports nothing`() {
		val seen = Seen()
		controller(Reader(null), seen) { alice }.load(root)

		assertEquals(
			"a moment without signal is not the same as a conversation with no replies",
			emptyList<Any>(),
			seen.rows,
		)
	}

	@Test
	fun `an answer that lands after an account switch is not reported at all`() {
		val reader = Reader(listOf(reply("bob", "r1", root)))
		val seen = Seen()
		var who: String? = alice
		val threads = controller(reader, seen) { who }
		// Bob signs in while Alice's read is in flight.
		reader.duringRead = { who = "bob" }

		threads.load(root)

		assertEquals(
			"an answer fetched as Alice must not reach Bob's bell, or Alice's",
			emptyList<Any>(),
			seen.rows,
		)
	}

	@Test
	fun `an empty conversation is still reported, so its first read can be seeded`() {
		val seen = Seen()
		controller(Reader(emptyList()), seen) { alice }.load(root)

		assertEquals(1, seen.rows.size)
		assertEquals(emptyList<SnapReply>(), seen.rows.single().third)
	}

	@Test
	fun `a conversation already read is not re-read for the bell`() {
		val reader = Reader(listOf(reply("bob", "r1", root)))
		val seen = Seen()
		val threads = controller(reader, seen) { alice }

		threads.load(root)
		// What History does about once a second while the card is on screen.
		repeat(5) { threads.load(root) }

		assertEquals("Stage 6 adds no request of its own", 1, reader.reads)
		assertEquals(1, seen.rows.size)
	}

	// ── resolving a conversation for the bell ──────────────────────────

	@Test
	fun `a comment in a read conversation resolves to that conversation`() {
		val reader = Reader(listOf(reply("bob", "r1", root)))
		val threads = controller(reader, Seen()) { alice }
		threads.load(root)

		val found = threads.rootOf(SnapReplyTarget.of("bob", "r1")!!)

		assertEquals(root, found)
		assertEquals("resolving costs no network", 1, reader.reads)
	}

	@Test
	fun `the root of a conversation resolves to itself`() {
		val threads = controller(Reader(listOf(reply("bob", "r1", root))), Seen()) { alice }
		threads.load(root)

		assertEquals(root, threads.rootOf(root))
	}

	@Test
	fun `a comment from nothing this process read resolves to null`() {
		val threads = controller(Reader(listOf(reply("bob", "r1", root))), Seen()) { alice }
		threads.load(root)

		assertNull(threads.rootOf(SnapReplyTarget.of("carol", "elsewhere")!!))
	}

	@Test
	fun `another account's conversations are not visible to resolve against`() {
		val reader = Reader(listOf(reply("bob", "r1", root)))
		var who: String? = alice
		val threads = controller(reader, Seen()) { who }
		threads.load(root)
		assertTrue(threads.rootOf(root) != null)

		who = "carol"

		assertNull(
			"a conversation loaded as Alice is not an answer for Carol",
			threads.rootOf(SnapReplyTarget.of("bob", "r1")!!),
		)
	}
}
