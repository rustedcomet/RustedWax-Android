package com.rustedwax.app.snaps

import com.rustedwax.hive.HiveCommentRead
import com.rustedwax.hive.HiveCommentState
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
 * Deleting a Snap or reply: Hive `delete_comment` at the chain's own
 * `author/permlink`, only for the author, only while consensus state allows
 * it, and reported as done only once the object is proven gone.
 */
class SnapDeleterTest {

	private val account = "rustedwaxtest"
	private val reply = SnapReplyTarget.of(account, "rustedwax-reply-1000-aaaaaa")!!
	private val root = SnapReplyTarget.of(account, "rustedwax-snap-1000-bbbbbb")!!
	private val other = SnapReplyTarget.of(account, "rustedwax-reply-1001-cccccc")!!

	private val head = 2_000_000_000L

	private fun state(
		target: SnapReplyTarget,
		children: Long = 0,
		net: Long = 0,
		cashout: Long? = head + 6 * 86_400,
		parentAuthor: String = "alice",
		headAt: Long = head,
	) = HiveCommentState(
		author = target.author,
		permlink = target.permlink,
		parentAuthor = parentAuthor,
		parentPermlink = "rustedwax-snap-900-zzzzzz",
		children = children,
		netRshares = net,
		cashoutEpochSec = cashout,
		headEpochSec = headAt,
	)

	/**
	 * Consensus state behind [nodes] current nodes. A delete that "lands"
	 * removes the object, as `delete_comment_evaluator` does.
	 */
	private class Chain(
		var nodes: Int = 2,
		var result: HiveRpc.BroadcastResult = inBlock(),
		/** Whether a broadcast actually removes the object. */
		var applies: Boolean = true,
		var evidence: HiveRpc.TransactionEvidence = HiveRpc.TransactionEvidence.UNAVAILABLE,
		var refuseSigning: Boolean = false,
	) : SnapHivePort {
		val objects = mutableMapOf<String, HiveCommentState>()
		val prepared = mutableListOf<TxSerializer.DeleteCommentOp>()
		var broadcasts = 0
		var reads = 0
		var beforeSigning: (() -> Unit)? = null
		/**
		 * An earlier node took the transaction and lost its answer; a later
		 * node then rejected the same bytes. The deletion still applies.
		 */
		var landsDespiteRejection = false

		fun put(s: HiveCommentState) { objects["${s.author}/${s.permlink}"] = s }

		override fun resolveContainer(): SnapContainerResolver.Result =
			error("a delete must never look up a container")
		override fun prepareComment(operation: TxSerializer.CommentOp, author: String): HivePreparationResult =
			error("a delete must never sign a comment")
		override fun contentExists(author: String, permlink: String): Boolean? =
			error("a delete reads consensus state, not existence")

		/** Head time the answering nodes report with an absence. */
		var absentHead: Long? = 2_000_000_000L
		/** Per-node answer, when a test needs nodes to differ. */
		var answerFrom: ((Int) -> HiveCommentRead)? = null

		override fun readCommentState(author: String, permlink: String, limit: Int): List<HiveCommentRead> {
			reads++
			return (0 until minOf(limit, nodes)).map { n ->
				answerFrom?.invoke(n)
					?: objects["$author/$permlink"]?.let { HiveCommentRead.Present(it, "node$n") }
					?: HiveCommentRead.Absent("node$n", absentHead)
			}
		}

		override fun prepareDelete(operation: TxSerializer.DeleteCommentOp, author: String): HivePreparationResult {
			beforeSigning?.invoke()
			if (refuseSigning) {
				return HivePreparationResult.Failed(
					HiveRpc.BroadcastResult.Rejected("You've switched Hive accounts."),
				)
			}
			prepared += operation
			return HivePreparationResult.Ready(
				PreparedHiveTransaction("""{"n":${prepared.size}}""", "tx-${prepared.size}", 2_000_000_060L),
			)
		}

		override fun broadcastPrepared(prepared: PreparedHiveTransaction, author: String): HiveRpc.BroadcastResult {
			broadcasts++
			val op = this.prepared.last()
			if (applies && (result !is HiveRpc.BroadcastResult.Rejected || landsDespiteRejection)) {
				objects.remove("${op.author}/${op.permlink}")
			}
			return result
		}

		override fun observeTransaction(txId: String, expirationEpochSec: Long) = evidence

		/** The irreversible block time proven past an expiration, or null — see [HiveRpc.irreversiblyPast]. */
		var finality: Long? = null
		val finalityAsked = mutableListOf<Long>()
		/** Runs while finality is being proven — nodes catching up meanwhile. */
		var duringFinality: (() -> Unit)? = null
		override fun irreversiblyPast(expirationEpochSec: Long): Long? {
			finalityAsked += expirationEpochSec
			duringFinality?.invoke()
			return finality?.takeIf { it > expirationEpochSec }
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

	private fun record(eventId: String, target: SnapReplyTarget, state: PendingSnapState, owner: String = account) =
		PendingSnap(
			account = owner,
			eventId = eventId,
			author = target.author,
			permlink = target.permlink,
			parentAuthor = "alice",
			parentPermlink = "rustedwax-snap-900-zzzzzz",
			body = "words",
			jsonMetadata = "{}",
			signedTransactionJson = "{}",
			txId = "tx-original",
			expirationEpochSec = 1L,
			state = state,
			createdAtEpochSec = 1_000L,
			updatedAtEpochSec = 1_000L,
			kind = PendingSnapKind.REPLY,
		)

	private fun deleter(chain: Chain, store: PendingSnapStore? = null) =
		SnapDeleter(chain, store, sleep = {}, settleAttempts = 3)

	// ── eligible deletions ─────────────────────────────────────────────

	@Test
	fun `an eligible reply is deleted at the chain's own author and permlink`() {
		val chain = Chain().apply { put(state(reply)) }
		val store = Store().apply {
			write(record("e1", reply, PendingSnapState.CONFIRMED))
			write(record("e2", other, PendingSnapState.CONFIRMED))
		}

		val outcome = deleter(chain, store).delete(account, reply)

		assertEquals(SnapDeleter.Outcome.Deleted(reply.contentId, "tx-1"), outcome)
		assertEquals(TxSerializer.DeleteCommentOp(account, reply.permlink), chain.prepared.single())
		assertEquals(1, chain.broadcasts)
		// This reply's record retired; an unrelated reply's left exactly as it was.
		assertEquals(setOf("$account|e2"), store.saved.keys)
	}

	@Test
	fun `an eligible root Snap is deleted the same way`() {
		val chain = Chain().apply { put(state(root, parentAuthor = "peak.snaps")) }

		val outcome = deleter(chain).delete(account, root)

		assertEquals(SnapDeleter.Outcome.Deleted(root.contentId, "tx-1"), outcome)
		assertEquals(TxSerializer.DeleteCommentOp(account, root.permlink), chain.prepared.single())
	}

	@Test
	fun `zero or negative net rshares are deletable however many votes there were`() {
		listOf(0L, -5_000L).forEach { net ->
			val chain = Chain().apply { put(state(reply, net = net)) }
			assertTrue(deleter(chain).check(account, reply) is SnapDeleteCheck.Eligible)
		}
	}

	// ── refusals: nothing read, signed or sent ─────────────────────────

	@Test
	fun `another author's comment is refused before anything is read`() {
		val theirs = SnapReplyTarget.of("bob", "re-bob-1")!!
		val chain = Chain().apply { put(state(theirs)) }

		val outcome = deleter(chain).delete(account, theirs)

		assertEquals(SnapDeleter.Outcome.Failed(SnapDeleter.NOT_AUTHOR), outcome)
		assertEquals(0, chain.reads)
		assertTrue(chain.prepared.isEmpty())
		assertEquals(0, chain.broadcasts)
	}

	@Test
	fun `nobody signed in cannot delete`() {
		val chain = Chain().apply { put(state(reply)) }
		assertEquals(SnapDeleter.Outcome.Failed(SnapDeleter.SIGN_IN), deleter(chain).delete("", reply))
		assertEquals(0, chain.broadcasts)
	}

	@Test
	fun `a comment with replies is blocked`() {
		val chain = Chain().apply { put(state(reply, children = 1)) }
		assertEquals(SnapDeleter.Outcome.Blocked(SnapDeleter.HAS_REPLIES), deleter(chain).delete(account, reply))
		assertTrue(chain.prepared.isEmpty())
	}

	@Test
	fun `positive net rshares are blocked even when the payout would round to zero`() {
		val chain = Chain().apply { put(state(reply, net = 1)) }
		assertEquals(SnapDeleter.Outcome.Blocked(SnapDeleter.HAS_VOTES), deleter(chain).delete(account, reply))
		assertTrue(chain.prepared.isEmpty())
	}

	@Test
	fun `a paid-out comment is blocked although it reads zero rshares and zero replies`() {
		val chain = Chain().apply { put(state(reply, cashout = null)) }
		assertEquals(SnapDeleter.Outcome.Blocked(SnapDeleter.PAID_OUT), deleter(chain).delete(account, reply))
		assertTrue(chain.prepared.isEmpty())
	}

	@Test
	fun `a cashout time already reached is treated as paid`() {
		val chain = Chain().apply { put(state(reply, cashout = head)) }
		assertEquals(SnapDeleter.Outcome.Blocked(SnapDeleter.PAID_OUT), deleter(chain).delete(account, reply))
	}

	@Test
	fun `a top-level post is not a Snap or reply and is refused`() {
		val chain = Chain().apply { put(state(reply, parentAuthor = "")) }
		assertEquals(SnapDeleter.Outcome.Blocked(SnapDeleter.NOT_A_COMMENT), deleter(chain).delete(account, reply))
	}

	@Test
	fun `a chain object naming a different identity is never deleted`() {
		val chain = Chain()
		chain.objects[reply.contentId] = state(reply).copy(permlink = "rustedwax-reply-other")
		assertEquals(SnapDeleter.Outcome.Blocked(SnapDeleter.NOT_AUTHOR), deleter(chain).delete(account, reply))
		assertTrue(chain.prepared.isEmpty())
	}

	@Test
	fun `no current node means nothing is deleted`() {
		val chain = Chain(nodes = 0).apply { put(state(reply)) }
		assertEquals(SnapDeleter.Outcome.Failed(SnapDeleter.UNREADABLE), deleter(chain).delete(account, reply))
		assertTrue(chain.prepared.isEmpty())
	}

	@Test
	fun `an unsettled local write for the object blocks deletion`() {
		val chain = Chain().apply { put(state(reply)) }
		val store = Store().apply { write(record("e1", reply, PendingSnapState.ACCEPTED_UNCONFIRMED)) }
		val outcome = deleter(chain, store).delete(account, reply)
		assertTrue(outcome is SnapDeleter.Outcome.Blocked)
		assertTrue(chain.prepared.isEmpty())
	}

	// ── eligibility re-read at signing time ────────────────────────────

	@Test
	fun `eligibility that changes after the confirmation is caught before signing`() {
		val chain = Chain().apply { put(state(reply)) }
		val del = deleter(chain)
		assertTrue(del.check(account, reply) is SnapDeleteCheck.Eligible)

		// Somebody replies while the dialog is open.
		chain.put(state(reply, children = 1))

		assertEquals(SnapDeleter.Outcome.Blocked(SnapDeleter.HAS_REPLIES), del.delete(account, reply))
		assertTrue(chain.prepared.isEmpty())
		assertEquals(0, chain.broadcasts)
	}

	@Test
	fun `signing refused at the account boundary sends nothing`() {
		val chain = Chain(refuseSigning = true).apply { put(state(reply)) }
		val outcome = deleter(chain).delete(account, reply)
		assertTrue(outcome is SnapDeleter.Outcome.Failed)
		assertEquals(0, chain.broadcasts)
		assertTrue(chain.objects.containsKey(reply.contentId))
	}

	// ── failures never remove anything ─────────────────────────────────

	@Test
	fun `a chain rejection is not proof, so the delete stays guarded and everything is kept`() {
		val chain = Chain(result = HiveRpc.BroadcastResult.Rejected("Cannot delete a comment with replies."))
			.apply { put(state(reply)) }
		val store = Store().apply { write(record("e1", reply, PendingSnapState.CONFIRMED)) }
		val del = deleter(chain, store)

		val outcome = del.delete(account, reply)

		assertTrue("got $outcome", outcome is SnapDeleter.Outcome.Uncertain)
		assertTrue(
			"Hive's words are still shown",
			(outcome as SnapDeleter.Outcome.Uncertain).message.contains("Cannot delete a comment with replies."),
		)
		assertTrue(del.hasUnsettled(account, reply))
		assertTrue(chain.objects.containsKey(reply.contentId))
		assertEquals(setOf("$account|e1"), store.saved.keys)
	}

	// ── a rejection that may have raced an earlier node's acceptance ───

	@Test
	fun `a later node's rejection keeps the original transaction, and asking again only settles it`() {
		val chain = Chain(
			result = HiveRpc.BroadcastResult.Rejected("Duplicate transaction check failed"),
			applies = false,
		).apply { put(state(reply)) }
		val del = deleter(chain)

		assertTrue(del.delete(account, reply) is SnapDeleter.Outcome.Uncertain)
		assertTrue(del.hasUnsettled(account, reply))

		repeat(3) { assertTrue(del.delete(account, reply) is SnapDeleter.Outcome.Uncertain) }
		assertEquals("signed exactly once", 1, chain.prepared.size)
		assertEquals("sent exactly once", 1, chain.broadcasts)
	}

	@Test
	fun `a rejected delete that landed anyway is proven deleted, with its tombstone, without resending`() {
		val chain = Chain(result = HiveRpc.BroadcastResult.Rejected("Duplicate transaction check failed"))
			.apply {
				put(state(root, parentAuthor = "peak.snaps"))
				landsDespiteRejection = true
			}
		val store = Store().apply { write(record("e1", root, PendingSnapState.CONFIRMED)) }
		val hook = Hook()

		val outcome = hooked(chain, store, hook).delete(account, root)

		assertEquals(SnapDeleter.Outcome.Deleted(root.contentId, "tx-1"), outcome)
		assertEquals(listOf(Triple(account, root.contentId, "tx-1")), hook.calls)
		assertTrue(store.saved.isEmpty())
		assertEquals(1, chain.broadcasts)
	}

	@Test
	fun `a rejected delete confirmed late is settled by reading the original transaction`() {
		val chain = Chain(
			result = HiveRpc.BroadcastResult.Rejected("Duplicate transaction check failed"),
			applies = false,
		).apply { put(state(root, parentAuthor = "peak.snaps")) }
		val store = Store().apply { write(record("e1", root, PendingSnapState.CONFIRMED)) }
		val hook = Hook()
		val del = hooked(chain, store, hook)
		assertTrue(del.delete(account, root) is SnapDeleter.Outcome.Uncertain)
		assertTrue(hook.calls.isEmpty())

		// The original lands: block evidence for tx-1, and the object is gone.
		chain.objects.clear()
		chain.evidence = HiveRpc.TransactionEvidence.BLOCK

		assertEquals(SnapDeleter.Outcome.Deleted(root.contentId, "tx-1"), del.delete(account, root))
		assertEquals(listOf(Triple(account, root.contentId, "tx-1")), hook.calls)
		assertTrue(store.saved.isEmpty())
		assertFalse(del.hasUnsettled(account, root))
		assertEquals(1, chain.prepared.size)
		assertEquals(1, chain.broadcasts)
	}

	@Test
	fun `a rejected delete proven expired is a plain failure, and only then may be retried`() {
		val chain = Chain(
			result = HiveRpc.BroadcastResult.Rejected("Duplicate transaction check failed"),
			applies = false,
		).apply { put(state(reply)) }
		val del = deleter(chain)
		assertTrue(del.delete(account, reply) is SnapDeleter.Outcome.Uncertain)

		chain.evidence = HiveRpc.TransactionEvidence.ABSENT
		val settled = del.delete(account, reply)
		assertTrue("got $settled", settled is SnapDeleter.Outcome.Failed)
		assertFalse(del.hasUnsettled(account, reply))
		assertEquals("expiry proof alone sends nothing", 1, chain.broadcasts)

		chain.result = Chain.inBlock()
		chain.applies = true
		chain.evidence = HiveRpc.TransactionEvidence.UNAVAILABLE
		assertEquals(SnapDeleter.Outcome.Deleted(reply.contentId, "tx-2"), del.delete(account, reply))
		assertEquals(2, chain.prepared.size)
	}

	@Test
	fun `a guarded rejection blocks only that object, for that account`() {
		val chain = Chain(
			result = HiveRpc.BroadcastResult.Rejected("Duplicate transaction check failed"),
			applies = false,
		).apply {
			put(state(reply))
			put(state(other))
		}
		val del = deleter(chain)
		del.delete(account, reply)

		assertTrue(del.hasUnsettled(account, reply))
		assertFalse(del.hasUnsettled(account, other))
		assertFalse(del.hasUnsettled("someoneelse", reply))

		chain.result = Chain.inBlock()
		chain.applies = true
		assertEquals(SnapDeleter.Outcome.Deleted(other.contentId, "tx-2"), del.delete(account, other))
		assertTrue(del.hasUnsettled(account, reply))
	}

	@Test
	fun `a network failure proven never included is a plain failure, and may be retried`() {
		val chain = Chain(
			result = HiveRpc.BroadcastResult.NetworkFailure("down"),
			applies = false,
			evidence = HiveRpc.TransactionEvidence.ABSENT,
		).apply { put(state(reply)) }
		val store = Store().apply { write(record("e1", reply, PendingSnapState.CONFIRMED)) }
		val del = deleter(chain, store)

		val first = del.delete(account, reply)
		assertTrue(first is SnapDeleter.Outcome.Failed)
		assertFalse(del.hasUnsettled(account, reply))
		assertEquals(setOf("$account|e1"), store.saved.keys)

		// Now the network is back: a fresh, re-checked attempt is allowed.
		chain.result = Chain.inBlock()
		chain.applies = true
		assertEquals(SnapDeleter.Outcome.Deleted(reply.contentId, "tx-2"), del.delete(account, reply))
	}

	@Test
	fun `a deletion in a block that leaves the object is reported as ineffective`() {
		val chain = Chain(applies = false).apply { put(state(reply)) }
		val store = Store().apply { write(record("e1", reply, PendingSnapState.CONFIRMED)) }

		val outcome = deleter(chain, store).delete(account, reply)

		assertTrue(outcome is SnapDeleter.Outcome.Failed)
		assertTrue((outcome as SnapDeleter.Outcome.Failed).message.contains("still there"))
		assertEquals(setOf("$account|e1"), store.saved.keys)
	}

	@Test
	fun `acknowledgement alone is not success`() {
		val chain = Chain(
			result = HiveRpc.BroadcastResult.AcceptedUnconfirmed("tx-1", "n", "no confirmation"),
			applies = false,
		).apply { put(state(reply)) }
		val store = Store().apply { write(record("e1", reply, PendingSnapState.CONFIRMED)) }

		val outcome = deleter(chain, store).delete(account, reply)

		assertTrue(outcome is SnapDeleter.Outcome.Uncertain)
		assertEquals(setOf("$account|e1"), store.saved.keys)
	}

	// ── delayed indexing and duplicate prevention ──────────────────────

	@Test
	fun `a delayed deletion is settled by reading, never by a second delete`() {
		val chain = Chain(
			result = HiveRpc.BroadcastResult.AcceptedUnconfirmed("tx-1", "n", "no confirmation"),
			applies = false,
		).apply { put(state(reply)) }
		val store = Store().apply { write(record("e1", reply, PendingSnapState.CONFIRMED)) }
		val del = deleter(chain, store)

		assertTrue(del.delete(account, reply) is SnapDeleter.Outcome.Uncertain)
		assertTrue(del.hasUnsettled(account, reply))
		// Still nothing on a second ask: the same transaction, read again.
		assertTrue(del.delete(account, reply) is SnapDeleter.Outcome.Uncertain)

		// The deletion lands late.
		chain.objects.remove(reply.contentId)
		assertEquals(SnapDeleter.Outcome.Deleted(reply.contentId, "tx-1"), del.delete(account, reply))

		assertEquals(1, chain.prepared.size)
		assertEquals(1, chain.broadcasts)
		assertTrue(store.saved.isEmpty())
		assertFalse(del.hasUnsettled(account, reply))
	}

	@Test
	fun `absence on a single node without block evidence is not proof`() {
		val chain = Chain(
			nodes = 1,
			result = HiveRpc.BroadcastResult.AcceptedUnconfirmed("tx-1", "n", "no confirmation"),
		).apply { put(state(reply)) }

		val outcome = deleter(chain).delete(account, reply)

		assertTrue(outcome is SnapDeleter.Outcome.Uncertain)
	}

	@Test
	fun `block evidence found later plus one absent node is proof`() {
		val chain = Chain(
			nodes = 1,
			result = HiveRpc.BroadcastResult.AcceptedUnconfirmed("tx-1", "n", "no confirmation"),
			evidence = HiveRpc.TransactionEvidence.BLOCK,
		).apply { put(state(reply)) }

		assertEquals(SnapDeleter.Outcome.Deleted(reply.contentId, "tx-1"), deleter(chain).delete(account, reply))
	}

	@Test
	fun `something already gone is reconciled without sending, once two nodes agree`() {
		val chain = Chain()
		val store = Store().apply { write(record("e1", reply, PendingSnapState.CONFIRMED)) }

		assertEquals(SnapDeleter.Outcome.Deleted(reply.contentId, null), deleter(chain, store).delete(account, reply))
		assertTrue(chain.prepared.isEmpty())
		assertTrue(store.saved.isEmpty())

		val single = Chain(nodes = 1)
		val kept = Store().apply { write(record("e1", reply, PendingSnapState.CONFIRMED)) }
		assertEquals(
			SnapDeleter.Outcome.Blocked(SnapDeleter.ALREADY_GONE),
			deleter(single, kept).delete(account, reply),
		)
		assertEquals(1, kept.saved.size)
	}

	// ── account isolation ──────────────────────────────────────────────

	@Test
	fun `an unsettled delete belongs to the account that sent it`() {
		val chain = Chain(
			result = HiveRpc.BroadcastResult.AcceptedUnconfirmed("tx-1", "n", "no confirmation"),
			applies = false,
		).apply { put(state(reply)) }
		val del = deleter(chain)
		del.delete(account, reply)
		assertTrue(del.hasUnsettled(account, reply))
		assertFalse(del.hasUnsettled("someoneelse", reply))
	}

	@Test
	fun `only this account's records are retired`() {
		val chain = Chain().apply { put(state(reply)) }
		val store = Store().apply {
			write(record("e1", reply, PendingSnapState.CONFIRMED))
			write(record("e9", reply, PendingSnapState.CONFIRMED, owner = "otheraccount"))
		}
		deleter(chain, store).delete(account, reply)
		assertEquals(setOf("otheraccount|e9"), store.saved.keys)
	}

	@Test
	fun `rules are checked against consensus fields in the evaluator's order`() {
		assertNull(SnapDeleter.rulesProblem(account, reply, state(reply)))
		assertEquals(
			SnapDeleter.HAS_REPLIES,
			SnapDeleter.rulesProblem(account, reply, state(reply, children = 2, net = 9, cashout = null)),
		)
		assertEquals(
			SnapDeleter.PAID_OUT,
			SnapDeleter.rulesProblem(account, reply, state(reply, net = 9, cashout = null)),
		)
		assertEquals(SnapDeleter.HAS_VOTES, SnapDeleter.rulesProblem(account, reply, state(reply, net = 9)))
		// Case-only account difference is the same account, as at signing.
		assertNull(SnapDeleter.rulesProblem(account.uppercase(), reply, state(reply)))
	}

	// ── the durable step before retirement (Issue #47) ─────────────────

	/** Records each hook call and what the store held at that moment. */
	private class Hook(var answer: () -> Boolean = { true }) {
		val calls = mutableListOf<Triple<String, String, String?>>()
		val heldAtCall = mutableListOf<Int>()
	}

	private fun hooked(chain: Chain, store: Store, hook: Hook) =
		SnapDeleter(chain, store, sleep = {}, settleAttempts = 3, beforeRetire = { who, target, tx ->
			hook.calls += Triple(who, target.contentId, tx)
			hook.heldAtCall += store.saved.size
			hook.answer()
		})

	@Test
	fun `the durable step runs after proof and before the record is retired`() {
		val chain = Chain().apply { put(state(root, parentAuthor = "peak.snaps")) }
		val store = Store().apply { write(record("e1", root, PendingSnapState.CONFIRMED)) }
		val hook = Hook()

		val outcome = hooked(chain, store, hook).delete(account, root)

		assertEquals(SnapDeleter.Outcome.Deleted(root.contentId, "tx-1"), outcome)
		assertEquals(listOf(Triple(account, root.contentId, "tx-1")), hook.calls)
		assertEquals("the record was still there when the step ran", listOf(1), hook.heldAtCall)
		assertTrue(store.saved.isEmpty())
	}

	@Test
	fun `a failed durable step keeps the record, sends nothing more, and still reports what Hive proved`() {
		listOf<() -> Boolean>({ false }, { error("disk") }).forEach { answer ->
			val chain = Chain().apply { put(state(root, parentAuthor = "peak.snaps")) }
			val store = Store().apply { write(record("e1", root, PendingSnapState.CONFIRMED)) }

			val outcome = hooked(chain, store, Hook(answer)).delete(account, root)

			assertEquals(SnapDeleter.Outcome.Deleted(root.contentId, "tx-1"), outcome)
			assertEquals("the last local evidence was kept", setOf("$account|e1"), store.saved.keys)
			assertEquals(1, chain.broadcasts)
		}
	}

	@Test
	fun `after a failed step, asking again proves absence by reading and finishes without a broadcast`() {
		val chain = Chain().apply { put(state(root, parentAuthor = "peak.snaps")) }
		val store = Store().apply { write(record("e1", root, PendingSnapState.CONFIRMED)) }
		val hook = Hook { false }
		val del = hooked(chain, store, hook)
		del.delete(account, root)

		// As after a restart: the same object asked about again, by a fresh deleter.
		hook.answer = { true }
		val again = hooked(chain, store, hook).delete(account, root)

		assertEquals(SnapDeleter.Outcome.Deleted(root.contentId, null), again)
		assertEquals("no second delete was signed or sent", 1, chain.prepared.size)
		assertEquals(1, chain.broadcasts)
		assertTrue(store.saved.isEmpty())
	}

	@Test
	fun `the durable step never runs without proven deletion`() {
		val cases = listOf(
			Chain(result = HiveRpc.BroadcastResult.AcceptedUnconfirmed("tx-1", "n", "no"), applies = false),
			Chain(result = HiveRpc.BroadcastResult.Rejected("no")),
			Chain(nodes = 1, result = HiveRpc.BroadcastResult.AcceptedUnconfirmed("tx-1", "n", "no")),
			Chain(nodes = 0),
		)
		cases.forEach { chain ->
			chain.put(state(root, parentAuthor = "peak.snaps"))
			val store = Store().apply { write(record("e1", root, PendingSnapState.CONFIRMED)) }
			val hook = Hook()
			val outcome = hooked(chain, store, hook).delete(account, root)
			assertFalse("$outcome", outcome is SnapDeleter.Outcome.Deleted)
			assertTrue("$outcome wrote a tombstone", hook.calls.isEmpty())
			assertEquals(1, store.saved.size)
		}
		// Blocked by the rules: nothing proven either.
		val blocked = Chain().apply { put(state(root, children = 1, parentAuthor = "peak.snaps")) }
		val hook = Hook()
		hooked(blocked, Store(), hook).delete(account, root)
		assertTrue(hook.calls.isEmpty())
	}

	@Test
	fun `without a durable step a deleter retires exactly as before`() {
		val chain = Chain().apply { put(state(reply)) }
		val store = Store().apply { write(record("e1", reply, PendingSnapState.CONFIRMED)) }
		assertEquals(SnapDeleter.Outcome.Deleted(reply.contentId, "tx-1"), deleter(chain, store).delete(account, reply))
		assertTrue(store.saved.isEmpty())
	}

	// ── an expired transaction that can no longer be included ──────────

	/** tx-1's expiration in [Chain]; the proof below is the irreversible block after it. */
	private val expiry = 2_000_000_060L
	private val pastExpiry = expiry + 3

	private fun lostDelete(chain: Chain): SnapDeleter {
		val del = deleter(chain)
		assertTrue(del.delete(account, reply) is SnapDeleter.Outcome.Uncertain)
		return del
	}

	@Test
	fun `status history gone and no finality proof keeps the delete locked and signs nothing`() {
		val chain = Chain(result = HiveRpc.BroadcastResult.NetworkFailure("lost"), applies = false)
			.apply { put(state(reply)) }
		val del = lostDelete(chain)

		// too_old / unknown answers reach the deleter as UNAVAILABLE.
		repeat(3) { assertTrue(del.delete(account, reply) is SnapDeleter.Outcome.Uncertain) }
		assertTrue(del.hasUnsettled(account, reply))
		assertEquals(1, chain.prepared.size)
		assertEquals(1, chain.broadcasts)
		assertTrue("it asked about tx-1's own expiration", chain.finalityAsked.all { it == expiry })
	}

	@Test
	fun `proven unable to land and still present on a node past that point is a plain failure that frees the object`() {
		val chain = Chain(result = HiveRpc.BroadcastResult.NetworkFailure("lost"), applies = false)
			.apply { put(state(reply)) }
		val del = lostDelete(chain)

		chain.finality = pastExpiry
		chain.put(state(reply, headAt = pastExpiry))
		val outcome = del.delete(account, reply)

		assertTrue("got $outcome", outcome is SnapDeleter.Outcome.Failed)
		val message = (outcome as SnapDeleter.Outcome.Failed).message
		assertFalse("never claims it did not reach Hive: $message", message.contains("didn't reach"))
		// It may have been deleted and recreated since: only "it is there now" is proven.
		assertFalse("never claims the delete did not happen: $message", message.contains("nothing was deleted"))
		assertTrue(message, message.contains("can no longer"))
		assertFalse(del.hasUnsettled(account, reply))
		assertEquals("settling sent nothing", 1, chain.broadcasts)

		// Only now may a fresh, re-checked delete be signed.
		chain.result = Chain.inBlock()
		chain.applies = true
		assertEquals(SnapDeleter.Outcome.Deleted(reply.contentId, "tx-2"), del.delete(account, reply))
	}

	@Test
	fun `a presence read from a node not yet past the proof keeps the lock`() {
		val chain = Chain(result = HiveRpc.BroadcastResult.NetworkFailure("lost"), applies = false)
			.apply { put(state(reply)) }
		val del = lostDelete(chain)

		chain.finality = pastExpiry
		chain.put(state(reply, headAt = pastExpiry - 1))

		assertTrue(del.delete(account, reply) is SnapDeleter.Outcome.Uncertain)
		assertTrue(del.hasUnsettled(account, reply))
		assertEquals(1, chain.broadcasts)
	}

	@Test
	fun `a delete included before it expired is still proven by absence, with its tombstone`() {
		val chain = Chain(result = HiveRpc.BroadcastResult.NetworkFailure("lost"), applies = false)
			.apply { put(state(root, parentAuthor = "peak.snaps")) }
		val store = Store().apply { write(record("e1", root, PendingSnapState.CONFIRMED)) }
		val hook = Hook()
		val del = hooked(chain, store, hook)
		assertTrue(del.delete(account, root) is SnapDeleter.Outcome.Uncertain)

		// tx-1 had landed; its status has since aged out (UNAVAILABLE).
		chain.objects.clear()
		chain.finality = pastExpiry

		assertEquals(SnapDeleter.Outcome.Deleted(root.contentId, "tx-1"), del.delete(account, root))
		assertEquals(listOf(Triple(account, root.contentId, "tx-1")), hook.calls)
		assertTrue(store.saved.isEmpty())
		assertEquals(1, chain.broadcasts)
	}

	@Test
	fun `absence that only shows once finality is proven is still read as a deletion, with its tombstone`() {
		val chain = Chain(result = HiveRpc.BroadcastResult.NetworkFailure("lost"), applies = false)
			.apply { put(state(root, parentAuthor = "peak.snaps")) }
		val store = Store().apply { write(record("e1", root, PendingSnapState.CONFIRMED)) }
		val hook = Hook()
		val del = hooked(chain, store, hook)
		assertTrue(del.delete(account, root) is SnapDeleter.Outcome.Uncertain)

		// The settle loop still sees the Snap; it is gone by the time the proof lands.
		chain.finality = pastExpiry
		chain.duringFinality = {
			chain.objects.clear()
			chain.absentHead = pastExpiry
		}

		assertEquals(SnapDeleter.Outcome.Deleted(root.contentId, "tx-1"), del.delete(account, root))
		assertEquals(listOf(Triple(account, root.contentId, "tx-1")), hook.calls)
		assertTrue(store.saved.isEmpty())
		assertEquals(1, chain.broadcasts)
	}

	@Test
	fun `absence on one node only is still not proof, with or without finality`() {
		val chain = Chain(result = HiveRpc.BroadcastResult.NetworkFailure("lost"), applies = false)
			.apply { put(state(reply)) }
		val del = lostDelete(chain)
		chain.objects.clear()
		chain.nodes = 1
		chain.finality = pastExpiry

		assertTrue(del.delete(account, reply) is SnapDeleter.Outcome.Uncertain)
		assertTrue(del.hasUnsettled(account, reply))
	}

	@Test
	fun `proven unable to land but nothing current can read the object keeps the lock`() {
		val chain = Chain(result = HiveRpc.BroadcastResult.NetworkFailure("lost"), applies = false)
			.apply { put(state(reply)) }
		val del = lostDelete(chain)
		chain.finality = pastExpiry
		chain.nodes = 0

		assertTrue(del.delete(account, reply) is SnapDeleter.Outcome.Uncertain)
		assertTrue(del.hasUnsettled(account, reply))
	}

	@Test
	fun `a rejected delete proven unable to land and still present is a plain failure`() {
		val chain = Chain(
			result = HiveRpc.BroadcastResult.Rejected("Duplicate transaction check failed"),
			applies = false,
		).apply { put(state(reply, headAt = pastExpiry)) }
		chain.finality = pastExpiry

		val outcome = deleter(chain).delete(account, reply)

		assertTrue("got $outcome", outcome is SnapDeleter.Outcome.Failed)
		assertEquals(1, chain.broadcasts)
	}

	@Test
	fun `freeing one object leaves another object's lock and other accounts alone`() {
		val chain = Chain(result = HiveRpc.BroadcastResult.NetworkFailure("lost"), applies = false).apply {
			put(state(reply))
			put(state(other))
		}
		val del = deleter(chain)
		del.delete(account, reply)
		del.delete(account, other)
		assertTrue(del.hasUnsettled(account, reply))
		assertTrue(del.hasUnsettled(account, other))

		// Settling reply frees reply alone; other is never touched by it.
		chain.finality = pastExpiry
		chain.put(state(reply, headAt = pastExpiry))
		assertTrue(del.delete(account, reply) is SnapDeleter.Outcome.Failed)

		assertFalse(del.hasUnsettled(account, reply))
		assertTrue(del.hasUnsettled(account, other))
		assertFalse(del.hasUnsettled("someoneelse", other))
		assertEquals(2, chain.broadcasts)
	}

	// ── Block 3B-R1: absence after finality must be fenced by the proof ─

	private fun rootLostDelete(): Triple<Chain, Store, Pair<SnapDeleter, Hook>> {
		val chain = Chain(result = HiveRpc.BroadcastResult.NetworkFailure("lost"), applies = false)
			.apply { put(state(root, parentAuthor = "peak.snaps")) }
		val store = Store().apply { write(record("e1", root, PendingSnapState.CONFIRMED)) }
		val hook = Hook()
		val del = hooked(chain, store, hook)
		assertTrue(del.delete(account, root) is SnapDeleter.Outcome.Uncertain)
		return Triple(chain, store, del to hook)
	}

	@Test
	fun `R1 two absences from nodes behind the finality fence write no tombstone`() {
		val (chain, store, pair) = rootLostDelete()
		val (del, hook) = pair
		chain.finality = pastExpiry
		chain.duringFinality = {
			chain.objects.clear()
			chain.absentHead = pastExpiry - 1
		}

		assertTrue(del.delete(account, root) is SnapDeleter.Outcome.Uncertain)
		assertTrue("no tombstone", hook.calls.isEmpty())
		assertEquals(setOf("$account|e1"), store.saved.keys)
		assertTrue(del.hasUnsettled(account, root))
	}

	@Test
	fun `R1 absences without a known head never count after finality`() {
		val (chain, _, pair) = rootLostDelete()
		val (del, hook) = pair
		chain.finality = pastExpiry
		chain.duringFinality = {
			chain.objects.clear()
			chain.absentHead = null
		}

		assertTrue(del.delete(account, root) is SnapDeleter.Outcome.Uncertain)
		assertTrue(hook.calls.isEmpty())
	}

	@Test
	fun `R1 the same node twice is not two absences`() {
		val (chain, _, pair) = rootLostDelete()
		val (del, hook) = pair
		chain.finality = pastExpiry
		chain.duringFinality = { chain.answerFrom = { HiveCommentRead.Absent("node0", pastExpiry) } }

		assertTrue(del.delete(account, root) is SnapDeleter.Outcome.Uncertain)
		assertTrue(hook.calls.isEmpty())
	}

	@Test
	fun `R1 advanced nodes that disagree keep the lock`() {
		val (chain, _, pair) = rootLostDelete()
		val (del, hook) = pair
		chain.finality = pastExpiry
		val there = state(root, parentAuthor = "peak.snaps", headAt = pastExpiry)
		chain.duringFinality = {
			chain.answerFrom = { n ->
				if (n == 0) HiveCommentRead.Absent("node0", pastExpiry) else HiveCommentRead.Present(there, "node1")
			}
		}

		assertTrue(del.delete(account, root) is SnapDeleter.Outcome.Uncertain)
		assertTrue(hook.calls.isEmpty())
		assertTrue(del.hasUnsettled(account, root))
	}
}
