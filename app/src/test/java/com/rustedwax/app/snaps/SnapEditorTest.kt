package com.rustedwax.app.snaps

import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.SnapContainerResolver
import com.rustedwax.hive.TxSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

		/** The irreversible block time proven past an expiration, or null. */
		var finality: Long? = null
		override fun irreversiblyPast(expirationEpochSec: Long): Long? =
			finality?.takeIf { it > expirationEpochSec }

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
	fun `a lost response whose words read back stays unproven until its transaction is`() {
		val chain = Chain(
			replyOnChain(),
			result = HiveRpc.BroadcastResult.NetworkFailure("lost"),
			appliesAnyway = true,
		)
		val editor = SnapEditor(chain)

		// The words alone are not this transaction's inclusion.
		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Uncertain)

		chain.evidence = HiveRpc.TransactionEvidence.BLOCK
		assertEquals(
			SnapEditor.Outcome.Edited(reply.contentId, "new", "tx-1"),
			editor.edit(account, reply, SnapEditKind.REPLY, "new"),
		)
		assertEquals(1, chain.broadcasts)
	}

	@Test
	fun `words already on chain while the transaction could still arrive keep the edit locked`() {
		val chain = Chain(replyOnChain("A"), result = HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val editor = SnapEditor(chain)
		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "B") is SnapEditor.Outcome.Uncertain)

		// "B" appears — but it was another frontend's earlier text coming back, not tx-1.
		chain.comment = replyOnChain("B")
		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "B") is SnapEditor.Outcome.Uncertain)
		// …and nothing new may be signed while tx-1 can still land after a newer edit.
		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "C") is SnapEditor.Outcome.Uncertain)
		assertEquals("one signature, ever", 1, chain.prepared.size)
		assertEquals(1, chain.broadcasts)

		// Definitive evidence later releases it.
		chain.evidence = HiveRpc.TransactionEvidence.ABSENT
		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "C") is SnapEditor.Outcome.Failed)
		chain.result = Chain.inBlock()
		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "C") is SnapEditor.Outcome.Edited)
		assertEquals(2, chain.prepared.size)
	}

	@Test
	fun `matching words are settled by finality only from a node past the proof`() {
		val chain = Chain(replyOnChain("A"), result = HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val editor = SnapEditor(chain)
		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "B") is SnapEditor.Outcome.Uncertain)

		chain.comment = replyOnChain("B").copy(headEpochSec = 2_000_000_003L)
		chain.finality = 2_000_000_003L

		assertEquals(
			SnapEditor.Outcome.Edited(reply.contentId, "B", "tx-1"),
			editor.edit(account, reply, SnapEditKind.REPLY, "B"),
		)
		assertEquals(1, chain.prepared.size)
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

	@Test
	fun `retry of an ambiguous transaction neither signs nor broadcasts until absence is proven`() {
		val chain = Chain(replyOnChain(), result = HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val editor = SnapEditor(chain)
		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Uncertain)
		repeat(2) {
			assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Uncertain)
		}
		assertEquals(1, chain.prepared.size)
		assertEquals(1, chain.broadcasts)
		chain.evidence = HiveRpc.TransactionEvidence.ABSENT
		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Failed)
		assertEquals(1, chain.broadcasts)
		chain.result = Chain.inBlock()
		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Edited)
		assertEquals(2, chain.broadcasts)
	}

	@Test
	fun `late inclusion confirms the retained words and repairs only the confirmed cache`() {
		val chain = Chain(rootOnChain(), result = HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val store = Store()
		val original = record("event-1", root, "first words$tail", PendingSnapState.CONFIRMED, PendingSnapKind.ROOT)
		store.write(original)
		val editor = SnapEditor(chain, store)
		assertTrue(editor.edit(account, root, SnapEditKind.ROOT, "saved") is SnapEditor.Outcome.Uncertain)
		assertEquals(original, store.saved.getValue("$account|event-1"))
		chain.evidence = HiveRpc.TransactionEvidence.BLOCK
		assertEquals(
			SnapEditor.Outcome.Edited(root.contentId, "saved", "tx-1"),
			editor.edit(account, root, SnapEditKind.ROOT, "later words"),
		)
		assertEquals(original.copy(body = "saved$tail"), store.saved.getValue("$account|event-1"))
		assertEquals(1, chain.broadcasts)
	}

	@Test
	fun `readback of matching words on a different object does not confirm an edit`() {
		val chain = Chain(replyOnChain(), result = HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val port = object : SnapHivePort by chain {
			override fun readComment(author: String, permlink: String): SnapChainComment? =
				if (chain.broadcasts == 0) chain.comment else chain.comment!!.copy(permlink = "another", body = "new")
		}
		assertTrue(SnapEditor(port).edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Uncertain)
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

	// ── an expired edit that can no longer be included ─────────────────

	/** tx-1's expiration in [Chain] is 2_000_000_000; this block is after it. */
	private val pastExpiry = 2_000_000_003L

	private fun lostEdit(chain: Chain, editor: SnapEditor = SnapEditor(chain)): SnapEditor {
		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Uncertain)
		return editor
	}

	@Test
	fun `status history gone and no finality proof keeps the edit locked and signs nothing`() {
		val chain = Chain(replyOnChain(), result = HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val editor = lostEdit(chain)

		repeat(3) {
			assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "other words") is SnapEditor.Outcome.Uncertain)
		}
		assertEquals(1, chain.prepared.size)
		assertEquals(1, chain.broadcasts)
	}

	@Test
	fun `an edit included before it expired is confirmed, never called unsent`() {
		val chain = Chain(replyOnChain(), result = HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val editor = lostEdit(chain)

		// tx-1 had landed; its status has aged out; finality is proven; a node past it reads.
		chain.comment = replyOnChain("new").copy(headEpochSec = pastExpiry)
		chain.finality = pastExpiry

		assertEquals(
			SnapEditor.Outcome.Edited(reply.contentId, "new", "tx-1"),
			editor.edit(account, reply, SnapEditKind.REPLY, "new"),
		)
		assertEquals(1, chain.broadcasts)
	}

	@Test
	fun `words that only a read after the proof shows still confirm the edit`() {
		val chain = Chain(replyOnChain(), result = HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val lagging = ArrayDeque<SnapChainComment>()
		val port = object : SnapHivePort by chain {
			override fun readComment(author: String, permlink: String) =
				lagging.removeFirstOrNull() ?: chain.readComment(author, permlink)
		}
		val editor = lostEdit(chain, SnapEditor(port))

		// tx-1 landed; the only read is the one after the proof, from a node past it.
		chain.comment = replyOnChain("new").copy(headEpochSec = pastExpiry)
		chain.finality = pastExpiry

		assertEquals(
			SnapEditor.Outcome.Edited(reply.contentId, "new", "tx-1"),
			editor.edit(account, reply, SnapEditKind.REPLY, "new"),
		)
		assertEquals(1, chain.broadcasts)
	}

	@Test
	fun `different words on chain after finality free the comment without overwriting them or claiming the edit never landed`() {
		val chain = Chain(replyOnChain(), result = HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val editor = lostEdit(chain)

		chain.comment = replyOnChain("words from another frontend").copy(headEpochSec = pastExpiry)
		chain.finality = pastExpiry
		val outcome = editor.edit(account, reply, SnapEditKind.REPLY, "new")

		assertTrue("got $outcome", outcome is SnapEditor.Outcome.Failed)
		val message = (outcome as SnapEditor.Outcome.Failed).message
		assertFalse(message, message.contains("didn't reach"))
		assertEquals("nothing was overwritten", "words from another frontend", chain.comment!!.body)
		assertEquals("settling signed and sent nothing", 1, chain.prepared.size)
		assertEquals(1, chain.broadcasts)

		// Only a fresh, explicit save signs again — against what Hive holds now.
		chain.result = Chain.inBlock()
		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Edited)
		assertEquals(2, chain.broadcasts)
	}

	@Test
	fun `finality without a readable comment keeps the edit locked`() {
		val chain = Chain(replyOnChain(), result = HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val editor = lostEdit(chain)

		chain.comment = null
		chain.finality = pastExpiry

		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Uncertain)
		chain.comment = replyOnChain()
		chain.finality = null
		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Uncertain)
		assertEquals(1, chain.broadcasts)
	}

	@Test
	fun `freeing one comment's edit leaves another comment's edit locked`() {
		val chain = Chain(replyOnChain(), result = HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val rootChain = object : SnapHivePort by chain {
			var rootComment: SnapChainComment? = rootOnChain()
			override fun readComment(author: String, permlink: String) =
				if (permlink == root.permlink) rootComment else chain.readComment(author, permlink)
		}
		val editor = SnapEditor(rootChain)
		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Uncertain)
		assertTrue(editor.edit(account, root, SnapEditKind.ROOT, "root words") is SnapEditor.Outcome.Uncertain)

		chain.comment = replyOnChain("someone else's words").copy(headEpochSec = pastExpiry)
		chain.finality = pastExpiry
		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Failed)

		// The root's lock is untouched: still only a check, no new signature.
		chain.finality = null
		assertTrue(editor.edit(account, root, SnapEditKind.ROOT, "other root words") is SnapEditor.Outcome.Uncertain)
		assertEquals(2, chain.prepared.size)
	}

	// ── Block 3B-R1: post-finality reads must be fenced by the proof ────

	@Test
	fun `R1 a matching body from a node behind the finality fence is not a confirmation`() {
		val chain = Chain(replyOnChain(), result = HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val lagging = ArrayDeque<SnapChainComment>()
		val port = object : SnapHivePort by chain {
			override fun readComment(author: String, permlink: String) =
				lagging.removeFirstOrNull() ?: chain.readComment(author, permlink)
		}
		val editor = lostEdit(chain, SnapEditor(port))

		chain.finality = pastExpiry
		// The read after the proof matches, but describes the chain before it.
		chain.comment = replyOnChain("new").copy(headEpochSec = pastExpiry - 1)

		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Uncertain)
		assertEquals(1, chain.broadcasts)
	}

	@Test
	fun `R1 a differing body from a node behind the finality fence keeps the lock`() {
		val chain = Chain(replyOnChain(), result = HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val editor = lostEdit(chain)

		chain.finality = pastExpiry
		chain.comment = replyOnChain("words from another frontend").copy(headEpochSec = pastExpiry - 1)

		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Uncertain)
		// An explicit retry after that stale settlement still only checks.
		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "newer") is SnapEditor.Outcome.Uncertain)
		assertEquals("no replacement signed", 1, chain.prepared.size)
		assertEquals(1, chain.broadcasts)
	}

	@Test
	fun `R1 a read that cannot name its chain point never settles after finality`() {
		val chain = Chain(replyOnChain(), result = HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val editor = lostEdit(chain)

		chain.finality = pastExpiry
		chain.comment = replyOnChain("words from another frontend") // headEpochSec = null

		assertTrue(editor.edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Uncertain)
		assertEquals(1, chain.prepared.size)
	}

	// ── 3A: a stale completion owns no local side effects ──────────────

	/**
	 * Two editors over one [SnapWriteGuards], as an old and a recreated Activity
	 * hold them. Transaction evidence is per id; the "old" thread's evidence read
	 * for tx-1 waits until [resume] so a newer edit can happen meanwhile.
	 */
	private inner class Recreated(chain: Chain, val store: Store) {
		val guards = SnapWriteGuards()
		val evidence = java.util.concurrent.ConcurrentHashMap<String, HiveRpc.TransactionEvidence>()
		val paused = java.util.concurrent.CountDownLatch(1)
		val resume = java.util.concurrent.CountDownLatch(1)
		val port = object : SnapHivePort by chain {
			override fun observeTransaction(txId: String, expirationEpochSec: Long): HiveRpc.TransactionEvidence {
				if (Thread.currentThread().name == "old" && txId == "tx-1") {
					paused.countDown()
					resume.await(5, java.util.concurrent.TimeUnit.SECONDS)
				}
				return evidence[txId] ?: HiveRpc.TransactionEvidence.UNAVAILABLE
			}
		}
		val old = SnapEditor(port, store, guards = guards)
		val fresh = SnapEditor(port, store, guards = guards)

		/** The old editor re-checks tx-1 on its own thread and is held mid-read. */
		fun oldChecksAgain(target: SnapReplyTarget, kind: SnapEditKind, words: String): Thread {
			val t = Thread { old.edit(account, target, kind, words) }.apply { name = "old"; start() }
			assertTrue(paused.await(5, java.util.concurrent.TimeUnit.SECONDS))
			return t
		}

		fun storedBody(eventId: String) = (store.read(account, eventId) as PendingSnapRead.Present).snap.body
	}

	@Test
	fun `a late completion of the old edit cannot overwrite the newer confirmed root body`() {
		val chain = Chain(rootOnChain("first words"), result = HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val store = Store().apply {
			write(record("event-1", root, "first words$tail", PendingSnapState.CONFIRMED, PendingSnapKind.ROOT))
		}
		val r = Recreated(chain, store)
		assertTrue(r.old.edit(account, root, SnapEditKind.ROOT, "older words") is SnapEditor.Outcome.Uncertain)

		val oldThread = r.oldChecksAgain(root, SnapEditKind.ROOT, "older words")
		// Meanwhile the recreated editor settles tx-1 by its inclusion…
		r.evidence["tx-1"] = HiveRpc.TransactionEvidence.BLOCK
		chain.comment = rootOnChain("older words")
		assertTrue(r.fresh.edit(account, root, SnapEditKind.ROOT, "older words") is SnapEditor.Outcome.Edited)
		assertEquals("older words$tail", r.storedBody("event-1"))
		// …and a newer edit is proven at once.
		chain.result = Chain.inBlock()
		assertTrue(r.fresh.edit(account, root, SnapEditKind.ROOT, "newest words") is SnapEditor.Outcome.Edited)
		assertEquals("newest words$tail", r.storedBody("event-1"))

		// The old check now finishes with tx-1's inclusion.
		r.resume.countDown()
		oldThread.join(5_000)

		assertEquals("the newer confirmed body stands", "newest words$tail", r.storedBody("event-1"))
		assertEquals("two signatures, never a third", 2, chain.prepared.size)
		assertEquals(2, chain.broadcasts)
	}

	@Test
	fun `a late completion of the old edit cannot overwrite the newer confirmed reply body`() {
		val chain = Chain(replyOnChain("first words"), result = HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val store = Store().apply {
			write(record("event-2", reply, "first words", PendingSnapState.CONFIRMED, PendingSnapKind.REPLY))
		}
		val r = Recreated(chain, store)
		assertTrue(r.old.edit(account, reply, SnapEditKind.REPLY, "older words") is SnapEditor.Outcome.Uncertain)

		val oldThread = r.oldChecksAgain(reply, SnapEditKind.REPLY, "older words")
		r.evidence["tx-1"] = HiveRpc.TransactionEvidence.BLOCK
		chain.comment = replyOnChain("older words")
		assertTrue(r.fresh.edit(account, reply, SnapEditKind.REPLY, "older words") is SnapEditor.Outcome.Edited)
		chain.result = Chain.inBlock()
		assertTrue(r.fresh.edit(account, reply, SnapEditKind.REPLY, "newest words") is SnapEditor.Outcome.Edited)
		assertEquals("newest words", r.storedBody("event-2"))

		r.resume.countDown()
		oldThread.join(5_000)

		assertEquals("the newer confirmed body stands", "newest words", r.storedBody("event-2"))
		assertEquals(2, chain.prepared.size)
	}

	@Test
	fun `a late completion of the old edit cannot release the newer edit's lock`() {
		val chain = Chain(replyOnChain("first words"), result = HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val store = Store().apply {
			write(record("event-2", reply, "first words", PendingSnapState.CONFIRMED, PendingSnapKind.REPLY))
		}
		val r = Recreated(chain, store)
		r.old.edit(account, reply, SnapEditKind.REPLY, "older words")

		val oldThread = r.oldChecksAgain(reply, SnapEditKind.REPLY, "older words")
		r.evidence["tx-1"] = HiveRpc.TransactionEvidence.BLOCK
		chain.comment = replyOnChain("older words")
		r.fresh.edit(account, reply, SnapEditKind.REPLY, "older words") // settles tx-1
		// A newer edit whose answer is lost: tx-2 now holds the lock.
		assertTrue(r.fresh.edit(account, reply, SnapEditKind.REPLY, "newest words") is SnapEditor.Outcome.Uncertain)

		r.resume.countDown()
		oldThread.join(5_000)

		assertTrue(r.guards.current(account, reply) is SnapWriteGuards.EditAttempt)
		assertTrue("still only checking tx-2", r.fresh.edit(account, reply, SnapEditKind.REPLY, "other") is SnapEditor.Outcome.Uncertain)
		assertEquals("older words", r.storedBody("event-2"))
		assertEquals(2, chain.prepared.size)
	}
}
