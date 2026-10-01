package com.rustedwax.app.snaps

import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.SnapContainerResolver
import com.rustedwax.hive.TxSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Editing a Snap or reply is the same `comment` operation at the same
 * `author/permlink`, with new words and **nothing else** changed.
 *
 * What is pinned here is the identity half: no permlink is ever minted, the
 * parent and metadata come off the chain object byte for byte, a root Snap's
 * media link survives, a non-author is refused before anything is read or
 * signed, an absent object is never "edited" into existence, and success is
 * only reported once the chain has the new words.
 */
class SnapEditorTest {

	private val account = "rustedwaxtest"
	private val reply = SnapReplyTarget.of(account, "rustedwax-reply-1000-aaaaaa")!!
	private val root = SnapReplyTarget.of(account, "rustedwax-snap-1000-bbbbbb")!!

	private val tail = "\n\nhttps://youtu.be/rTKpYJ80OVQ\n\n#scrobblelife #scrobble #rustedwax"

	/** Older app version on purpose: an edit must not rewrite the metadata. */
	private val rootMetadata =
		"""{"app":"rustedwax/0.11.4","tags":["scrobblelife","scrobble","rustedwax"]}"""

	private fun replyOnChain(body: String = "first words") = SnapChainComment(
		author = account,
		permlink = reply.permlink,
		parentAuthor = "alice",
		parentPermlink = "rustedwax-snap-900-zzzzzz",
		title = "",
		body = body,
		jsonMetadata = """{"app":"rustedwax/0.12.2"}""",
	)

	private fun rootOnChain(text: String = "first words") = SnapChainComment(
		author = account,
		permlink = root.permlink,
		parentAuthor = "peak.snaps",
		parentPermlink = "snap-container-1789648560",
		title = "",
		body = text + tail,
		jsonMetadata = rootMetadata,
	)

	/**
	 * A chain of one object. A broadcast that "lands" replaces its body, which
	 * is exactly what Hive does with a second comment op at the same identity.
	 */
	private class Chain(
		var comment: SnapChainComment?,
		var result: HiveRpc.BroadcastResult = inBlock(),
		/** Whether a non-block result still changed the object. */
		var appliesAnyway: Boolean = false,
		var evidence: HiveRpc.TransactionEvidence = HiveRpc.TransactionEvidence.UNAVAILABLE,
		var refuseSigning: Boolean = false,
	) : SnapHivePort {
		val prepared = mutableListOf<TxSerializer.CommentOp>()
		var broadcasts = 0
		var reads = 0

		override fun resolveContainer(): SnapContainerResolver.Result =
			error("an edit must never look up a container")

		override fun prepareComment(
			operation: TxSerializer.CommentOp,
			author: String,
		): HivePreparationResult {
			if (refuseSigning) {
				return HivePreparationResult.Failed(
					HiveRpc.BroadcastResult.Rejected("You've switched Hive accounts."),
				)
			}
			prepared += operation
			return HivePreparationResult.Ready(
				PreparedHiveTransaction("""{"n":${prepared.size}}""", "tx-${prepared.size}", 2_000_000_000L),
			)
		}

		override fun broadcastPrepared(
			prepared: PreparedHiveTransaction,
			author: String,
		): HiveRpc.BroadcastResult {
			broadcasts++
			val op = this.prepared.last()
			val lands = result.let {
				it is HiveRpc.BroadcastResult.Success &&
					it.evidence == HiveRpc.BroadcastResult.Evidence.BLOCK
			} || appliesAnyway
			if (lands) comment = comment?.copy(body = op.body)
			return result
		}

		override fun observeTransaction(txId: String, expirationEpochSec: Long) = evidence

		override fun contentExists(author: String, permlink: String): Boolean? =
			error("an edit reads the whole object, never just its existence")

		override fun readComment(author: String, permlink: String): SnapChainComment? {
			reads++
			return comment?.takeIf { it.author == author && it.permlink == permlink }
		}

		companion object {
			fun inBlock() =
				HiveRpc.BroadcastResult.Success("tx", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)
		}
	}

	private class Store : PendingSnapStore {
		val saved = mutableMapOf<String, PendingSnap>()
		override fun read(account: String, eventId: String): PendingSnapRead =
			saved["$account|$eventId"]?.let(PendingSnapRead::Present) ?: PendingSnapRead.Absent
		override fun write(snap: PendingSnap): Boolean {
			saved["${snap.account}|${snap.eventId}"] = snap
			return true
		}
		override fun clear(account: String, eventId: String) { saved.remove("$account|$eventId") }
		override fun all(account: String) =
			saved.filterKeys { it.startsWith("$account|") }.values.toList()
		override fun corruptEventIds(account: String) = emptySet<String>()
	}

	private fun record(
		eventId: String,
		target: SnapReplyTarget,
		body: String,
		state: PendingSnapState,
		kind: PendingSnapKind,
	) = PendingSnap(
		account = account,
		eventId = eventId,
		author = target.author,
		permlink = target.permlink,
		parentAuthor = "peak.snaps",
		parentPermlink = "snap-container-1789648560",
		body = body,
		jsonMetadata = rootMetadata,
		signedTransactionJson = "{}",
		txId = "tx-original",
		expirationEpochSec = 1L,
		state = state,
		createdAtEpochSec = 1_000L,
		updatedAtEpochSec = 1_000L,
		kind = kind,
	)

	// ── identity ───────────────────────────────────────────────────────

	@Test
	fun `a reply edit is the same comment op at the same author, permlink and parent`() {
		val chain = Chain(replyOnChain())

		val outcome = SnapEditor(chain).edit(account, reply, SnapEditKind.REPLY, "better words")

		assertEquals(SnapEditor.Outcome.Edited(reply.contentId, "better words", "tx"), outcome)
		val op = chain.prepared.single()
		assertEquals(account, op.author)
		assertEquals(reply.permlink, op.permlink)
		assertEquals("alice", op.parentAuthor)
		assertEquals("rustedwax-snap-900-zzzzzz", op.parentPermlink)
		assertEquals("", op.title)
		assertEquals("better words", op.body)
		assertEquals("""{"app":"rustedwax/0.12.2"}""", op.jsonMetadata)
		assertEquals(1, chain.broadcasts)
	}

	@Test
	fun `a root edit replaces only the user's words and keeps the media link and tags exactly`() {
		val chain = Chain(rootOnChain("first words"))

		val outcome = SnapEditor(chain).edit(account, root, SnapEditKind.ROOT, "second words")

		assertTrue(outcome is SnapEditor.Outcome.Edited)
		val op = chain.prepared.single()
		assertEquals("second words$tail", op.body)
		assertEquals(root.permlink, op.permlink)
		assertEquals("peak.snaps", op.parentAuthor)
		assertEquals("snap-container-1789648560", op.parentPermlink)
		// Verbatim, including the version it was first posted with.
		assertEquals(rootMetadata, op.jsonMetadata)
		assertEquals("second words", PostedSnapBody.userText(op.body))
	}

	@Test
	fun `a user's own link inside the text is theirs to edit, the generated one is not`() {
		val chain = Chain(rootOnChain("see https://youtu.be/abc #scrobble"))

		SnapEditor(chain).edit(account, root, SnapEditKind.ROOT, "just words")

		assertEquals("just words$tail", chain.prepared.single().body)
	}

	@Test
	fun `editing twice never mints a second identity`() {
		val chain = Chain(replyOnChain())
		val editor = SnapEditor(chain)

		editor.edit(account, reply, SnapEditKind.REPLY, "one")
		editor.edit(account, reply, SnapEditKind.REPLY, "two")

		assertEquals(2, chain.prepared.size)
		assertEquals(1, chain.prepared.map { it.author to it.permlink }.toSet().size)
		assertEquals(1, chain.prepared.map { it.parentAuthor to it.parentPermlink }.toSet().size)
		assertEquals("two", chain.comment!!.body)
	}

	// ── who may edit ───────────────────────────────────────────────────

	@Test
	fun `a non-author is refused before anything is read, signed or sent`() {
		val chain = Chain(replyOnChain().copy(author = "alice"))
		val theirs = SnapReplyTarget.of("alice", reply.permlink)!!

		val outcome = SnapEditor(chain).edit(account, theirs, SnapEditKind.REPLY, "mine now")

		assertEquals(SnapEditor.Outcome.Failed(SnapEditor.NOT_AUTHOR), outcome)
		assertEquals(0, chain.reads)
		assertTrue(chain.prepared.isEmpty())
		assertEquals(0, chain.broadcasts)
	}

	@Test
	fun `an object the chain says belongs to someone else is refused`() {
		val chain = Chain(replyOnChain())
		// The read answers for a different author than the one asked for.
		val confused = object : SnapHivePort by chain {
			override fun readComment(author: String, permlink: String) =
				replyOnChain().copy(author = "mallory")
		}

		val outcome = SnapEditor(confused).edit(account, reply, SnapEditKind.REPLY, "x")

		assertEquals(SnapEditor.Outcome.Failed(SnapEditor.NOT_AUTHOR), outcome)
		assertTrue(chain.prepared.isEmpty())
	}

	@Test
	fun `nobody signed in cannot edit`() {
		val chain = Chain(replyOnChain())
		val outcome = SnapEditor(chain).edit("", reply, SnapEditKind.REPLY, "x")
		assertTrue(outcome is SnapEditor.Outcome.Failed)
		assertEquals(0, chain.reads)
	}

	@Test
	fun `a refusal at the signing boundary sends nothing`() {
		val chain = Chain(replyOnChain(), refuseSigning = true)

		val outcome = SnapEditor(chain).edit(account, reply, SnapEditKind.REPLY, "x")

		assertTrue(outcome is SnapEditor.Outcome.Failed)
		assertEquals(0, chain.broadcasts)
		assertEquals("first words", chain.comment!!.body)
	}

	// ── what can never be created ──────────────────────────────────────

	@Test
	fun `an object that is not on chain is never edited into existence`() {
		val chain = Chain(comment = null)

		val outcome = SnapEditor(chain).edit(account, reply, SnapEditKind.REPLY, "hello")

		assertTrue(outcome is SnapEditor.Outcome.Failed)
		assertTrue(chain.prepared.isEmpty())
		assertEquals(0, chain.broadcasts)
	}

	@Test
	fun `a top-level post is not a Snap and is not edited`() {
		val chain = Chain(replyOnChain().copy(parentAuthor = "", parentPermlink = "music"))

		val outcome = SnapEditor(chain).edit(account, reply, SnapEditKind.REPLY, "x")

		assertTrue(outcome is SnapEditor.Outcome.Failed)
		assertTrue(chain.prepared.isEmpty())
	}

	@Test
	fun `a root whose body is not the RustedWax shape is refused rather than guessed at`() {
		val chain = Chain(rootOnChain().copy(body = "written somewhere else"))

		val outcome = SnapEditor(chain).edit(account, root, SnapEditKind.ROOT, "x")

		assertTrue(outcome is SnapEditor.Outcome.Failed)
		assertTrue(chain.prepared.isEmpty())
	}

	// ── the limit ──────────────────────────────────────────────────────

	@Test
	fun `280 characters is an acceptable edit for a reply and a root`() {
		val replies = Chain(replyOnChain())
		val roots = Chain(rootOnChain())
		val text = "a".repeat(280)

		assertTrue(SnapEditor(replies).edit(account, reply, SnapEditKind.REPLY, text) is SnapEditor.Outcome.Edited)
		assertTrue(SnapEditor(roots).edit(account, root, SnapEditKind.ROOT, text) is SnapEditor.Outcome.Edited)
		assertEquals(text, replies.comment!!.body)
		assertEquals(text + tail, roots.comment!!.body)
	}

	@Test
	fun `281 characters is refused before anything is read or signed`() {
		val chain = Chain(replyOnChain())

		val outcome = SnapEditor(chain).edit(account, reply, SnapEditKind.REPLY, "a".repeat(281))

		assertTrue(outcome is SnapEditor.Outcome.Failed)
		assertEquals(0, chain.reads)
		assertTrue(chain.prepared.isEmpty())
	}

	@Test
	fun `an empty or invisible edit is refused`() {
		val chain = Chain(replyOnChain())
		listOf("", "   \n", "​", "\u0007").forEach {
			assertTrue(SnapEditor(chain).edit(account, reply, SnapEditKind.REPLY, it) is SnapEditor.Outcome.Failed)
		}
		assertTrue(chain.prepared.isEmpty())
	}

	// ── outcomes ───────────────────────────────────────────────────────

	@Test
	fun `unchanged words are not a write`() {
		val chain = Chain(replyOnChain("same"))

		val outcome = SnapEditor(chain).edit(account, reply, SnapEditKind.REPLY, "same")

		assertEquals(SnapEditor.Outcome.Edited(reply.contentId, "same", null), outcome)
		assertTrue(chain.prepared.isEmpty())
		assertEquals(0, chain.broadcasts)
	}

	@Test
	fun `an unreadable chain changes nothing and says so`() {
		val chain = Chain(replyOnChain())
		val down = object : SnapHivePort by chain {
			override fun readComment(author: String, permlink: String): SnapChainComment? =
				throw java.io.IOException("offline")
		}

		val outcome = SnapEditor(down).edit(account, reply, SnapEditKind.REPLY, "x")

		assertTrue(outcome is SnapEditor.Outcome.Failed)
		assertTrue(chain.prepared.isEmpty())
	}

	@Test
	fun `a broadcast proven absent is a failure and the old words stand`() {
		val chain = Chain(
			replyOnChain(),
			result = HiveRpc.BroadcastResult.Rejected("node said no"),
			evidence = HiveRpc.TransactionEvidence.ABSENT,
		)

		val outcome = SnapEditor(chain).edit(account, reply, SnapEditKind.REPLY, "new")

		assertTrue(outcome is SnapEditor.Outcome.Failed)
		assertEquals("first words", chain.comment!!.body)
	}

	@Test
	fun `a broadcast nobody can vouch for is uncertain, not success`() {
		val chain = Chain(replyOnChain(), result = HiveRpc.BroadcastResult.NetworkFailure("lost"))

		val outcome = SnapEditor(chain).edit(account, reply, SnapEditKind.REPLY, "new")

		assertTrue(outcome is SnapEditor.Outcome.Uncertain)
	}

	@Test
	fun `a lost response is success once the chain reads back the new words`() {
		val chain = Chain(
			replyOnChain(),
			result = HiveRpc.BroadcastResult.NetworkFailure("lost"),
			appliesAnyway = true,
		)

		val outcome = SnapEditor(chain).edit(account, reply, SnapEditKind.REPLY, "new")

		assertTrue(outcome is SnapEditor.Outcome.Edited)
		assertEquals(1, chain.broadcasts)
	}

	@Test
	fun `a broadcast that throws is not success`() {
		val chain = Chain(replyOnChain())
		val throwing = object : SnapHivePort by chain {
			override fun broadcastPrepared(prepared: PreparedHiveTransaction, author: String) =
				throw java.io.IOException("socket closed")
		}

		val outcome = SnapEditor(throwing).edit(account, reply, SnapEditKind.REPLY, "new")

		assertTrue(outcome !is SnapEditor.Outcome.Edited)
		assertEquals("first words", chain.comment!!.body)
	}

	// ── the local record ───────────────────────────────────────────────

	@Test
	fun `a proven edit updates the confirmed record's body and nothing else`() {
		val chain = Chain(rootOnChain("first words"))
		val store = Store()
		val confirmed = record("event-1", root, "first words$tail", PendingSnapState.CONFIRMED, PendingSnapKind.ROOT)
		store.write(confirmed)

		SnapEditor(chain, store).edit(account, root, SnapEditKind.ROOT, "second words")

		val after = (store.read(account, "event-1") as PendingSnapRead.Present).snap
		assertEquals(confirmed.copy(body = "second words$tail"), after)
	}

	@Test
	fun `retrying an edit already on chain repairs the confirmed local body without broadcasting`() {
		val chain = Chain(rootOnChain("second words"))
		val store = Store()
		val confirmed = record("event-1", root, "first words$tail", PendingSnapState.CONFIRMED, PendingSnapKind.ROOT)
		store.write(confirmed)

		val outcome = SnapEditor(chain, store).edit(account, root, SnapEditKind.ROOT, "second words")

		assertEquals(SnapEditor.Outcome.Edited(root.contentId, "second words", null), outcome)
		assertEquals(0, chain.broadcasts)
		assertEquals(confirmed.copy(body = "second words$tail"), (store.read(account, "event-1") as PendingSnapRead.Present).snap)
	}

	@Test
	fun `a failed edit leaves the record alone`() {
		val chain = Chain(
			rootOnChain("first words"),
			result = HiveRpc.BroadcastResult.Rejected("no"),
			evidence = HiveRpc.TransactionEvidence.ABSENT,
		)
		val store = Store()
		val confirmed = record("event-1", root, "first words$tail", PendingSnapState.CONFIRMED, PendingSnapKind.ROOT)
		store.write(confirmed)

		SnapEditor(chain, store).edit(account, root, SnapEditKind.ROOT, "second words")

		assertEquals(confirmed, (store.read(account, "event-1") as PendingSnapRead.Present).snap)
	}

	@Test
	fun `an unsettled record is never rewritten by an edit`() {
		val chain = Chain(rootOnChain("first words"))
		val store = Store()
		val unsettled = record(
			"event-1", root, "first words$tail",
			PendingSnapState.ACCEPTED_UNCONFIRMED, PendingSnapKind.ROOT,
		)
		store.write(unsettled)

		SnapEditor(chain, store).edit(account, root, SnapEditKind.ROOT, "second words")

		assertEquals(unsettled, (store.read(account, "event-1") as PendingSnapRead.Present).snap)
	}

	@Test
	fun `the text rule matches the composer's`() {
		assertNull(SnapEditor.textProblem("a".repeat(280)))
		assertTrue(SnapEditor.textProblem("a".repeat(281)) != null)
	}
}
