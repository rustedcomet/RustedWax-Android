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
	) = HiveCommentState(
		author = target.author,
		permlink = target.permlink,
		parentAuthor = parentAuthor,
		parentPermlink = "rustedwax-snap-900-zzzzzz",
		children = children,
		netRshares = net,
		cashoutEpochSec = cashout,
		headEpochSec = head,
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

		fun put(s: HiveCommentState) { objects["${s.author}/${s.permlink}"] = s }

		override fun resolveContainer(): SnapContainerResolver.Result =
			error("a delete must never look up a container")
		override fun prepareComment(operation: TxSerializer.CommentOp, author: String): HivePreparationResult =
			error("a delete must never sign a comment")
		override fun contentExists(author: String, permlink: String): Boolean? =
			error("a delete reads consensus state, not existence")

		override fun readCommentState(author: String, permlink: String, limit: Int): List<HiveCommentRead> {
			reads++
			return (0 until minOf(limit, nodes)).map { n ->
				objects["$author/$permlink"]?.let { HiveCommentRead.Present(it, "node$n") }
					?: HiveCommentRead.Absent("node$n")
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
			if (applies && result !is HiveRpc.BroadcastResult.Rejected) objects.remove("${op.author}/${op.permlink}")
			return result
		}

		override fun observeTransaction(txId: String, expirationEpochSec: Long) = evidence

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
	fun `a chain rejection reports Hive's reason and keeps everything`() {
		val chain = Chain(result = HiveRpc.BroadcastResult.Rejected("Cannot delete a comment with replies."))
			.apply { put(state(reply)) }
		val store = Store().apply { write(record("e1", reply, PendingSnapState.CONFIRMED)) }

		val outcome = deleter(chain, store).delete(account, reply)

		assertEquals(
			SnapDeleter.Outcome.Failed("Hive refused the deletion: Cannot delete a comment with replies."),
			outcome,
		)
		assertTrue(chain.objects.containsKey(reply.contentId))
		assertEquals(setOf("$account|e1"), store.saved.keys)
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
}
