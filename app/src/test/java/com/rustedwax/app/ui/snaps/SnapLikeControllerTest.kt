package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.SnapLikePort
import com.rustedwax.app.snaps.SnapLikePrepare
import com.rustedwax.app.snaps.SnapReply
import com.rustedwax.app.snaps.SnapReplyTarget
import com.rustedwax.app.snaps.SnapThreadBuilder
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.HiveVoteRead
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.TxSerializer
import com.rustedwax.hive.ViewerVote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Like control does, and — far more often — what it declines to do.
 *
 * Every test here counts [Port.prepares] and [Port.broadcasts] rather than
 * inspecting UI state. Those counts are the real assertions: the safety
 * contract is a list of situations in which **nothing is signed, or nothing is
 * sent**, and a controller that quietly broadcast on one of them would look
 * identical from the screen.
 */
class SnapLikeControllerTest {

	private val target = SnapReplyTarget("bob", "r1")!!
	private val own = SnapReplyTarget("alice", "mine")!!

	/**
	 * The chain, as far as this controller can tell.
	 *
	 * Records every read and every cast so the *order* can be asserted too: a
	 * cast that happened before a read would satisfy any count-based check and
	 * still be the bug.
	 */
	/**
	 * The chain, as far as this controller can tell.
	 *
	 * Counts reads, **prepares** and **broadcasts** separately. That separation
	 * is the point of most of these tests: the second safety read and the last
	 * account check both live between a signature and the wire, so a test that
	 * only counted "casts" could not tell a transaction that was abandoned after
	 * signing from one that was never signed at all — and only one of those is a
	 * vote somebody else has to live with.
	 */
	private class Port(
		var read: HiveVoteRead = HiveVoteRead.Fresh(ViewerVote.None, "https://node"),
		var result: HiveRpc.BroadcastResult = inBlock(),
	) : SnapLikePort {
		val log = mutableListOf<String>()
		val prepares = mutableListOf<TxSerializer.VoteOp>()
		val broadcasts = mutableListOf<PreparedHiveTransaction>()
		/** The voter each prepare was bound to, as the caller captured it. */
		val preparedFor = mutableListOf<String>()
		var reads = 0
		var lastVoter: String? = null
		var readThrows: Throwable? = null
		var broadcastThrows: Throwable? = null
		var prepareRefusal: String? = null

		/** Per-read overrides, so the first and second reads can differ. */
		val readQueue = ArrayDeque<HiveVoteRead>()

		/** Runs while a read is in flight — used to race a switch against it. */
		var duringRead: (() -> Unit)? = null
		/** Runs while the *second* read is in flight, specifically. */
		var duringSecondRead: (() -> Unit)? = null
		/** Runs while signing is in flight. */
		var duringPrepare: (() -> Unit)? = null

		override fun readViewerVote(
			author: String,
			permlink: String,
			voter: String,
		): HiveVoteRead {
			reads++
			lastVoter = voter
			log += "read"
			duringRead?.invoke()
			if (reads == 2) duringSecondRead?.invoke()
			readThrows?.let { throw it }
			return readQueue.removeFirstOrNull() ?: read
		}

		override fun prepare(operation: TxSerializer.VoteOp, voter: String): SnapLikePrepare {
			log += "prepare"
			prepares += operation
			preparedFor += voter
			duringPrepare?.invoke()
			prepareRefusal?.let { return SnapLikePrepare.Refused(it) }
			return SnapLikePrepare.Ready(
				PreparedHiveTransaction("{\"signed\":true}", "tx-${prepares.size}", 1_000L),
			)
		}

		/** The captured voter each broadcast was bound to, as the caller passed it. */
		val broadcastFor = mutableListOf<String>()

		override fun broadcast(
			prepared: PreparedHiveTransaction,
			voter: String,
		): HiveRpc.BroadcastResult {
			log += "broadcast"
			broadcasts += prepared
			broadcastFor += voter
			broadcastThrows?.let { throw it }
			return result
		}
	}

	private fun controller(
		port: Port = Port(),
		percent: () -> Int = { 10 },
		account: () -> String?,
	) = SnapLikeController(
		scope = CoroutineScope(Dispatchers.Unconfined),
		port = { port },
		account = account,
		likePercent = percent,
		io = Dispatchers.Unconfined,
	)

	private fun reply(
		author: String,
		permlink: String,
		vote: ViewerVote = ViewerVote.None,
	) = SnapReply(author, permlink, "alice", "root", "hi", 1_000L, vote)

	private fun thread(vararg replies: SnapReply) =
		SnapThreadBuilder.build("alice/root", replies.toList())

	// ── the eligible path ──────────────────────────────────────────────

	@Test
	fun `a Like reads the chain first and only then signs`() {
		val port = Port()
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals(listOf("read", "prepare", "read", "broadcast"), port.log)
		assertEquals(1, port.broadcasts.size)
		assertEquals(
			TxSerializer.VoteOp(voter = "alice", author = "bob", permlink = "r1", weight = 1000),
			port.prepares.single(),
		)
		assertEquals(SnapLikeState.Liked(10), likes.state(target))
	}

	@Test
	fun `the vote is read for the signed-in account`() {
		val port = Port()
		controller(port) { "alice" }.like(target)
		assertEquals("alice", port.lastVoter)
	}

	/** Read late, so changing the slider affects future Likes and nothing else. */
	@Test
	fun `the strength is read at the moment of casting`() {
		val port = Port()
		var percent = 10
		val likes = controller(port, percent = { percent }) { "alice" }

		likes.like(target)
		percent = 80
		likes.like(SnapReplyTarget("carol", "r2")!!)

		assertEquals(listOf(1000, 8000), port.prepares.map { it.weight })
	}

	@Test
	fun `a removed vote is eligible and casts`() {
		val port = Port(read = HiveVoteRead.Fresh(ViewerVote.Zero, "https://node"))
		controller(port) { "alice" }.like(target)
		assertEquals(1, port.broadcasts.size)
	}

	// ── everything that must not broadcast ─────────────────────────────

	@Test
	fun `an existing positive vote fills the heart and sends nothing`() {
		val port = Port(read = HiveVoteRead.Fresh(ViewerVote.Positive(10000, 9L), "https://node"))
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals("nothing may be signed", 0, port.prepares.size)
		assertEquals("nothing may be sent", 0, port.broadcasts.size)
		assertEquals(listOf("read"), port.log)
		assertEquals(SnapLikeState.Liked(10000), likes.state(target))
		assertEquals(SnapHeart.FILLED, likes.heart(target, ViewerVote.None))
	}

	@Test
	fun `an existing downvote goes inert and sends nothing`() {
		val port = Port(read = HiveVoteRead.Fresh(ViewerVote.Negative(-5000, -9L), "https://node"))
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals(0, port.broadcasts.size)
		assertTrue(likes.state(target) is SnapLikeState.Inert)
		assertEquals(SnapHeart.INERT, likes.heart(target, ViewerVote.None))
	}

	/** Every node stale. The one branch a bug here would make invisible. */
	@Test
	fun `a vote state no current node could establish sends nothing`() {
		val port = Port(read = HiveVoteRead.Unavailable("every node was behind the chain"))
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals(0, port.broadcasts.size)
		assertTrue(likes.state(target) is SnapLikeState.Refused)
	}

	@Test
	fun `an unreadable vote row sends nothing`() {
		val port = Port(read = HiveVoteRead.Fresh(ViewerVote.Unreadable("bad row"), "https://node"))
		controller(port) { "alice" }.like(target)
		assertEquals(0, port.broadcasts.size)
	}

	@Test
	fun `a read that throws sends nothing`() {
		val port = Port().apply { readThrows = IllegalStateException("socket") }
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals(0, port.broadcasts.size)
		assertTrue(likes.state(target) is SnapLikeState.Refused)
	}

	@Test
	fun `liking your own comment sends nothing and shows no control`() {
		val port = Port()
		val likes = controller(port) { "alice" }

		assertTrue("no heart on your own comment", !likes.showsHeart("alice"))
		assertTrue("nor on a differently-cased spelling of it", !likes.showsHeart("Alice"))
		assertTrue(likes.showsHeart("bob"))

		// And the port refuses it even if a control somehow got tapped.
		likes.like(own)
		assertEquals(0, port.broadcasts.size)
		assertTrue(likes.state(own) is SnapLikeState.Refused)
	}

	@Test
	fun `a signed-out user shows no heart and sends nothing`() {
		val port = Port()
		val likes = controller(port) { null }

		assertTrue(!likes.showsHeart("bob"))
		likes.like(target)

		assertEquals(0, port.reads)
		assertEquals(0, port.broadcasts.size)
	}

	// ── double taps ────────────────────────────────────────────────────

	/**
	 * The slot is claimed synchronously, so the guard does not depend on a click
	 * handler winning a race against a coroutine being scheduled.
	 */
	@Test
	fun `a second tap during a read starts no second vote`() {
		val port = Port()
		lateinit var likes: SnapLikeController
		port.duringRead = { likes.like(target) }
		likes = controller(port) { "alice" }

		likes.like(target)

		// One attempt: its own two safety reads, one signature, one broadcast.
		// The re-entrant tap fired by `duringRead` found the slot claimed and
		// started nothing of its own.
		assertEquals("only one attempt's worth of reads", 2, port.reads)
		assertEquals("only one signature", 1, port.prepares.size)
		assertEquals("only one vote", 1, port.broadcasts.size)
	}

	/**
	 * A tap on a pending Like starts nothing.
	 *
	 * The heart is filled in that state and the control is disabled, but the
	 * rule is enforced in the controller rather than relying on the screen: a
	 * heart that is already filled has nothing left to ask for, and the one
	 * thing it must never do is cast a second vote over an ambiguous first one.
	 */
	@Test
	fun `a tap on a pending Like starts no second vote`() {
		val port = Port(result = HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val likes = controller(port) { "alice" }
		likes.like(target)
		assertTrue(likes.isPending(target))
		val sent = port.broadcasts.size

		likes.like(target)
		likes.like(target)

		assertEquals("a pending Like is never re-cast", sent, port.broadcasts.size)
		assertTrue(likes.isPending(target))
	}

	/** Nor does one on a settled or inert heart. */
	@Test
	fun `a tap on an already settled or inert heart starts nothing`() {
		val settled = Port()
		val a = controller(settled) { "alice" }
		a.like(target)
		val sentAfterSettle = settled.broadcasts.size
		a.like(target)
		assertEquals(sentAfterSettle, settled.broadcasts.size)

		val down = Port(read = HiveVoteRead.Fresh(ViewerVote.Negative(-2500, -4L), "https://node"))
		val b = controller(down) { "alice" }
		b.like(target)
		b.like(target)
		assertEquals(0, down.broadcasts.size)
	}

	/** A reverted Like is the one state a fresh tap may follow. */
	@Test
	fun `a reverted Like can be tapped again`() {
		val port = Port(read = HiveVoteRead.Unavailable("stale"))
		val likes = controller(port) { "alice" }
		likes.like(target)
		assertEquals(SnapHeart.OUTLINE, likes.heart(target, ViewerVote.None))
		assertEquals(0, port.broadcasts.size)

		// The node comes back; the user taps again.
		port.read = HiveVoteRead.Fresh(ViewerVote.None, "https://node")
		likes.like(target)

		assertEquals("the retry is a fresh user action, not a resend", 1, port.broadcasts.size)
		assertEquals(SnapHeart.FILLED, likes.heart(target, ViewerVote.None))
	}

	// ── ambiguous broadcast results ────────────────────────────────────

	@Test
	fun `only block inclusion is treated as proof`() {
		val port = Port(result = inBlock())
		val likes = controller(port) { "alice" }
		likes.like(target)
		assertEquals(SnapLikeState.Liked(10), likes.state(target))
	}

	/**
	 * Everything else is [SnapLikeState.Pending]: a mempool sighting that can
	 * still be dropped, a node that accepted without independent confirmation, a
	 * deferral, a rejection that may have raced an acceptance elsewhere, and a
	 * transport failure that says nothing about what the node received.
	 */
	@Test
	fun `every non-definitive broadcast result becomes Unknown`() {
		val ambiguous = listOf<HiveRpc.BroadcastResult>(
			HiveRpc.BroadcastResult.Success("tx", "n", HiveRpc.BroadcastResult.Evidence.MEMPOOL),
			HiveRpc.BroadcastResult.AcceptedUnconfirmed("tx", "n", "no confirmation"),
			HiveRpc.BroadcastResult.Deferred("already submitted"),
			HiveRpc.BroadcastResult.Rejected("Cannot vote on a paid out post"),
			HiveRpc.BroadcastResult.NetworkFailure("all nodes unreachable"),
		)
		ambiguous.forEach { result ->
			val port = Port(result = result)
			val likes = controller(port) { "alice" }
			likes.like(target)
			assertTrue(
				"$result must be Unknown, got ${likes.state(target)}",
				likes.state(target) is SnapLikeState.Pending,
			)
			assertEquals("exactly one attempt", 1, port.broadcasts.size)
		}
	}

	@Test
	fun `a cast that throws becomes Unknown rather than a failure`() {
		val port = Port().apply { broadcastThrows = IllegalStateException("socket closed") }
		val likes = controller(port) { "alice" }
		likes.like(target)
		assertTrue(likes.state(target) is SnapLikeState.Pending)
	}

	/** A refusal that never crossed the network is definitive, not ambiguous. */
	@Test
	fun `a pre-flight refusal is a refusal, not an Unknown`() {
		val port = Port().apply { prepareRefusal = "RustedWax couldn't read your posting key." }
		val likes = controller(port) { "alice" }
		likes.like(target)
		assertTrue("got ${likes.state(target)}", likes.state(target) is SnapLikeState.Refused)
	}

	/**
	 * An ambiguous attempt keeps its **filled** heart.
	 *
	 * Reverted from the earlier behaviour on purpose. The common causes of an
	 * ambiguous result are a lost reply and a mempool sighting, not a refusal —
	 * so the vote probably exists, and showing an outline heart over a vote that
	 * exists invites the one tap this state must never receive.
	 */
	@Test
	fun `an ambiguous attempt keeps a filled heart and offers only a read`() {
		val port = Port(result = HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val likes = controller(port) { "alice" }
		likes.like(target)
		assertEquals(SnapHeart.FILLED, likes.heart(target, ViewerVote.None))
		assertTrue(likes.isPending(target))
	}

	/** There is no timer, no queue and no second attempt of any kind. */
	@Test
	fun `an Unknown attempt is never resent by anything but a fresh tap`() {
		val port = Port(result = HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val likes = controller(port) { "alice" }

		likes.like(target)
		assertEquals(1, port.broadcasts.size)

		// Every read-only thing the screen can do to it.
		likes.heart(target, ViewerVote.None)
		likes.notice(target)
		likes.recheck(target)
		likes.reconcile(thread(reply("bob", "r1")))

		assertEquals("still exactly one broadcast", 1, port.broadcasts.size)
	}

	// ── Check again reads, and only reads ──────────────────────────────

	@Test
	fun `Check again never broadcasts`() {
		val port = Port(result = HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val likes = controller(port) { "alice" }
		likes.like(target)
		val before = port.broadcasts.size

		port.read = HiveVoteRead.Fresh(ViewerVote.Positive(1000, 5L), "https://node")
		likes.recheck(target)

		assertEquals("a re-check may not sign anything", before, port.broadcasts.size)
		assertEquals(SnapLikeState.Liked(1000), likes.state(target))
	}

	/**
	 * A re-check that finds nothing does **not** clear the ambiguity. hived is
	 * current, but a transaction can still be in a mempool seconds from
	 * inclusion, so absence here means "not yet", not "never".
	 */
	@Test
	fun `Check again finding no vote reverts and says so`() {
		val port = Port(result = HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val likes = controller(port) { "alice" }
		likes.like(target)

		port.read = HiveVoteRead.Fresh(ViewerVote.None, "https://node")
		likes.recheck(target)

		// A node proven current says the vote is not there. Authoritative
		// enough to stop claiming it is — the heart reverts and the control
		// goes live again. Nothing is resent.
		assertTrue("got ${likes.state(target)}", likes.state(target) is SnapLikeState.Refused)
		assertEquals(SnapHeart.OUTLINE, likes.heart(target, ViewerVote.None))
		assertEquals("still exactly one broadcast", 1, port.broadcasts.size)
	}

	@Test
	fun `Check again on an unavailable read stays pending and sends nothing`() {
		val port = Port(result = HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val likes = controller(port) { "alice" }
		likes.like(target)

		port.read = HiveVoteRead.Unavailable("every node was stale")
		likes.recheck(target)

		assertTrue(likes.state(target) is SnapLikeState.Pending)
		assertEquals(1, port.broadcasts.size)
	}

	// ── account isolation ──────────────────────────────────────────────

	@Test
	fun `one account's Like state is invisible to another`() {
		val port = Port()
		var who: String? = "alice"
		val likes = controller(port) { who }

		likes.like(target)
		assertEquals(SnapLikeState.Liked(10), likes.state(target))

		who = "bob"
		assertNull("Bob must not see Alice's Like", likes.state(target))
		assertEquals(SnapHeart.OUTLINE, likes.heart(target, ViewerVote.None))

		// And it is still there when she comes back.
		who = "alice"
		assertEquals(SnapLikeState.Liked(10), likes.state(target))
	}

	/**
	 * A switch during the first read aborts before anything is signed.
	 *
	 * Counted at the port rather than read off the screen: "no state was shown"
	 * and "no vote was cast" are different claims, and only the second one
	 * matters to the account that was switched away from.
	 */
	@Test
	fun `a switch during the first read prepares and broadcasts nothing`() {
		val port = Port()
		var who: String? = "alice"
		port.duringRead = { who = "bob" }
		val likes = controller(port) { who }

		likes.like(target)

		assertEquals("nothing may be signed", 0, port.prepares.size)
		assertEquals("nothing may be sent", 0, port.broadcasts.size)
		assertNull("nothing under Bob", likes.state(target))
		who = "alice"
		assertNull("and nothing under Alice either", likes.state(target))
	}

	/**
	 * A switch **during signing** must stop the transaction reaching the wire.
	 *
	 * Signing costs a round trip for the chain head, so this is a real window.
	 * The transaction is genuinely built here — that is the point: a prepared
	 * transaction that is never broadcast reached nobody, and abandoning it is
	 * free.
	 */
	@Test
	fun `a switch after prepare broadcasts nothing`() {
		val port = Port()
		var who: String? = "alice"
		port.duringPrepare = { who = "bob" }
		val likes = controller(port) { who }

		likes.like(target)

		assertEquals("it did get as far as signing", 1, port.prepares.size)
		assertEquals("but nothing may reach the wire", 0, port.broadcasts.size)
		assertNull(likes.state(target))
		who = "alice"
		assertNull(likes.state(target))
	}

	/** And a switch during the second safety read, which is later still. */
	@Test
	fun `a switch during the second read broadcasts nothing`() {
		val port = Port()
		var who: String? = "alice"
		port.duringSecondRead = { who = "bob" }
		val likes = controller(port) { who }

		likes.like(target)

		assertEquals(1, port.prepares.size)
		assertEquals("the last check is immediately before the wire", 0, port.broadcasts.size)
		assertNull(likes.state(target))
		who = "alice"
		assertNull(likes.state(target))
	}

	/**
	 * The vote is always signed for the account that was captured on tap.
	 *
	 * Belt and braces with the port's own guard: the operation names a voter,
	 * and the account that voter was captured as is handed to `prepare` beside
	 * it, so the signing boundary can refuse a pair that no longer agrees with
	 * the vault.
	 */
	@Test
	fun `the prepared vote names the captured account, and says so`() {
		val port = Port()
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals("alice", port.prepares.single().voter)
		assertEquals(listOf("alice"), port.preparedFor)
	}

	/** A signing boundary that refuses stops the attempt dead, with nothing sent. */
	@Test
	fun `a prepare refused by the signing boundary broadcasts nothing`() {
		val port = Port().apply {
			prepareRefusal = "You've switched Hive accounts — this Like belonged to a different one."
		}
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals(0, port.broadcasts.size)
		assertTrue("got ${likes.state(target)}", likes.state(target) is SnapLikeState.Refused)
	}

	/**
	 * The claim taken on the way in is released on the way out, even when the
	 * outcome is discarded.
	 *
	 * Dropping the outcome is correct; dropping it *and* leaving the claim is
	 * not. The slot would stay locked on [SnapLikeState.Optimistic] for the rest of
	 * the session, so when Alice signed back in her heart would read "Liking…"
	 * forever and that comment could never be Liked again.
	 */
	@Test
	fun `a switch mid-flight does not leave the slot locked forever`() {
		val port = Port()
		var who: String? = "alice"
		port.duringRead = { who = "bob" }
		val likes = controller(port) { who }

		likes.like(target)

		who = "alice"
		assertNull("the claim must have been released", likes.state(target))
		assertTrue("and the control must work again", !likes.isBusy(target))

		// Which it does: a fresh tap starts a fresh attempt.
		port.duringRead = null
		likes.like(target)
		assertEquals(SnapLikeState.Liked(10), likes.state(target))
	}

	@Test
	fun `a re-check that completes after a switch writes nothing`() {
		val port = Port(read = HiveVoteRead.Fresh(ViewerVote.Positive(1000, 5L), "https://node"))
		var who: String? = "alice"
		val likes = controller(port) { who }
		port.duringRead = { who = "bob" }

		likes.recheck(target)

		assertNull(likes.state(target))
		who = "alice"
		assertNull(likes.state(target))
	}

	// ── the chain stays authoritative ──────────────────────────────────

	@Test
	fun `the heart is drawn from the chain when nothing local knows better`() {
		val likes = controller { "alice" }
		assertEquals(SnapHeart.FILLED, likes.heart(target, ViewerVote.Positive(1000, 5L)))
		assertEquals(SnapHeart.OUTLINE, likes.heart(target, ViewerVote.None))
		assertEquals(SnapHeart.OUTLINE, likes.heart(target, ViewerVote.Zero))
		assertEquals(SnapHeart.INERT, likes.heart(target, ViewerVote.Negative(-1, -1L)))
		assertEquals(SnapHeart.INERT, likes.heart(target, ViewerVote.Unreadable("?")))
	}

	@Test
	fun `a refusal is retired by a freshly-read thread`() {
		val port = Port(read = HiveVoteRead.Unavailable("stale"))
		val likes = controller(port) { "alice" }
		likes.like(target)
		assertTrue(likes.state(target) is SnapLikeState.Refused)

		likes.reconcile(thread(reply("bob", "r1", ViewerVote.None)))

		assertNull("the chain's answer supersedes it", likes.state(target))
		assertEquals(SnapHeart.OUTLINE, likes.heart(target, ViewerVote.None))
	}

	@Test
	fun `a proven Like is retired once the chain agrees`() {
		val port = Port()
		val likes = controller(port) { "alice" }
		likes.like(target)

		likes.reconcile(thread(reply("bob", "r1", ViewerVote.Positive(1000, 5L))))

		assertNull("local state is redundant once the chain says so", likes.state(target))
		assertEquals(SnapHeart.FILLED, likes.heart(target, ViewerVote.Positive(1000, 5L)))
	}

	/**
	 * A fresh thread read **replaces** a locally proven Like.
	 *
	 * This is the reverse of what an earlier revision asserted, and the reversal
	 * is the fix. Letting a block-confirmed `Liked` survive an absence meant it
	 * survived *every* later absence — including one caused by the user removing
	 * that vote on another frontend an hour afterwards, which left RustedWax
	 * showing a filled heart for a vote that no longer existed and no way to
	 * ever correct it. Hive decides; a brief flicker back to outline right after
	 * voting is what that costs, and the next read fills it again.
	 */
	@Test
	fun `a fresh read showing no vote clears a locally proven Like`() {
		val port = Port()
		val likes = controller(port) { "alice" }
		likes.like(target)
		assertEquals(SnapLikeState.Liked(10), likes.state(target))

		likes.reconcile(thread(reply("bob", "r1", ViewerVote.None)))

		assertNull("the chain's answer supersedes it", likes.state(target))
		assertEquals(SnapHeart.OUTLINE, likes.heart(target, ViewerVote.None))
	}

	/** The case that motivated it: a Like removed on another frontend. */
	@Test
	fun `an external positive to zero refresh clears a locally proven Like`() {
		val port = Port()
		val likes = controller(port) { "alice" }
		likes.like(target)

		// Somebody set the vote back to zero elsewhere; the next thread read
		// carries the new state.
		likes.reconcile(thread(reply("bob", "r1", ViewerVote.Zero)))

		assertNull(likes.state(target))
		assertEquals(SnapHeart.OUTLINE, likes.heart(target, ViewerVote.Zero))
	}

	/** And a Like turned into a downvote elsewhere becomes inert, not filled. */
	@Test
	fun `an external positive to negative refresh becomes inert`() {
		val port = Port()
		val likes = controller(port) { "alice" }
		likes.like(target)

		val downvoted = ViewerVote.Negative(-5000, -9L)
		likes.reconcile(thread(reply("bob", "r1", downvoted)))

		assertNull(likes.state(target))
		assertEquals(SnapHeart.INERT, likes.heart(target, downvoted))
	}

	/**
	 * A display read this app could not understand is not evidence about
	 * anybody's vote, so it changes nothing. Fail-safe: what was known stands.
	 */
	@Test
	fun `an unreadable viewer vote leaves local state exactly as it was`() {
		val port = Port()
		val likes = controller(port) { "alice" }
		likes.like(target)

		likes.reconcile(thread(reply("bob", "r1", ViewerVote.Unreadable("two rows"))))

		assertEquals(SnapLikeState.Liked(10), likes.state(target))
	}

	@Test
	fun `a pending attempt is answered by a chain read that shows the vote`() {
		val port = Port(result = HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val likes = controller(port) { "alice" }
		likes.like(target)
		assertTrue(likes.isPending(target))

		likes.reconcile(thread(reply("bob", "r1", ViewerVote.Positive(1000, 5L))))

		assertNull(likes.state(target))
		assertTrue(!likes.isPending(target))
	}

	/** Absence does not answer it: the transaction may still be in a mempool. */
	@Test
	fun `a pending attempt survives a display-only absence`() {
		val port = Port(result = HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val likes = controller(port) { "alice" }
		likes.like(target)

		likes.reconcile(thread(reply("bob", "r1", ViewerVote.None)))

		assertTrue(likes.isPending(target))
	}

	@Test
	fun `a reconcile never disturbs an attempt still in flight`() {
		val port = Port()
		lateinit var likes: SnapLikeController
		port.duringRead = { likes.reconcile(thread(reply("bob", "r1", ViewerVote.Positive(1, 1L)))) }
		likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals("the vote still went out", 1, port.broadcasts.size)
		assertEquals(SnapLikeState.Liked(10), likes.state(target))
	}


	// ── the second safety read ─────────────────────────────────────────

	/**
	 * The sequence, in order: read, sign, read again, send.
	 *
	 * Asserted as a literal call log rather than as counts, because the *order*
	 * is the safety property. A second read that happened after the broadcast
	 * would satisfy every count in this file and protect nobody.
	 */
	@Test
	fun `a Like reads, signs, reads again, and only then sends`() {
		val port = Port()
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals(listOf("read", "prepare", "read", "broadcast"), port.log)
		assertEquals(2, port.reads)
	}

	/**
	 * The race this exists to narrow: a vote that appears between the two reads.
	 *
	 * The transaction is already signed by then. It is dropped unsent — which
	 * costs nothing, because it reached nobody — rather than overwriting the
	 * vote that arrived while RustedWax was signing.
	 */
	@Test
	fun `a positive vote appearing between the two reads is prepared but never sent`() {
		val port = Port()
		port.readQueue += HiveVoteRead.Fresh(ViewerVote.None, "https://node")
		port.readQueue += HiveVoteRead.Fresh(ViewerVote.Positive(10000, 9L), "https://node")
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals("it was signed", 1, port.prepares.size)
		assertEquals("and never sent", 0, port.broadcasts.size)
		assertEquals(listOf("read", "prepare", "read"), port.log)
		// And the heart tells the truth about what is actually on chain.
		assertEquals(SnapLikeState.Liked(10000), likes.state(target))
	}

	@Test
	fun `a downvote appearing between the two reads is prepared but never sent`() {
		val port = Port()
		port.readQueue += HiveVoteRead.Fresh(ViewerVote.None, "https://node")
		port.readQueue += HiveVoteRead.Fresh(ViewerVote.Negative(-2500, -4L), "https://node")
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals(1, port.prepares.size)
		assertEquals(0, port.broadcasts.size)
		assertTrue(likes.state(target) is SnapLikeState.Inert)
	}

	/** The second read failing is as much a veto as the second read objecting. */
	@Test
	fun `a second read no current node could answer sends nothing`() {
		val port = Port()
		port.readQueue += HiveVoteRead.Fresh(ViewerVote.None, "https://node")
		port.readQueue += HiveVoteRead.Unavailable("every node went stale")
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals(1, port.prepares.size)
		assertEquals(0, port.broadcasts.size)
		assertTrue(likes.state(target) is SnapLikeState.Refused)
	}

	@Test
	fun `a second read that is unreadable sends nothing`() {
		val port = Port()
		port.readQueue += HiveVoteRead.Fresh(ViewerVote.None, "https://node")
		port.readQueue += HiveVoteRead.Fresh(
			ViewerVote.Unreadable("two vote rows for one account"),
			"https://node",
		)
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals(1, port.prepares.size)
		assertEquals(0, port.broadcasts.size)
		assertTrue(likes.state(target) is SnapLikeState.Refused)
	}

	/** A second read that still says nothing is there lets the vote through. */
	@Test
	fun `a second read agreeing with the first sends the prepared vote`() {
		val port = Port()
		port.readQueue += HiveVoteRead.Fresh(ViewerVote.None, "https://node")
		port.readQueue += HiveVoteRead.Fresh(ViewerVote.Zero, "https://node")
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals(1, port.prepares.size)
		assertEquals("exactly what was signed", 1, port.broadcasts.size)
		assertEquals(port.prepares.size, port.broadcasts.size)
	}

	/** Vetoed at the second read, and **not** quietly tried again. */
	@Test
	fun `a vetoed attempt is not retried by anything`() {
		val port = Port()
		port.readQueue += HiveVoteRead.Fresh(ViewerVote.None, "https://node")
		port.readQueue += HiveVoteRead.Fresh(ViewerVote.Positive(1000, 5L), "https://node")
		val likes = controller(port) { "alice" }

		likes.like(target)

		likes.heart(target, ViewerVote.None)
		likes.notice(target)
		likes.reconcile(thread(reply("bob", "r1", ViewerVote.Positive(1000, 5L))))

		assertEquals("still nothing sent", 0, port.broadcasts.size)
	}


	// ── the heart fills inside the tap ─────────────────────────────────
	//
	// The pipeline behind a Like takes seconds. These prove the user never
	// waits for it: the heart is already filled while each slow stage is still
	// running, and the stages themselves are unchanged.

	/**
	 * Filled **before the first RPC has returned**.
	 *
	 * Asserted from inside the port's own read, which is the only place that
	 * can prove ordering: the read has not returned yet, so anything the
	 * controller has done to the heart it did synchronously in the tap.
	 */
	@Test
	fun `the heart is filled before the first safety read finishes`() {
		val port = Port()
		lateinit var likes: SnapLikeController
		var duringFirstRead: SnapHeart? = null
		port.duringRead = {
			if (duringFirstRead == null) duringFirstRead = likes.heart(target, ViewerVote.None)
		}
		likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals("filled while read #1 was still running", SnapHeart.FILLED, duringFirstRead)
	}

	@Test
	fun `a slow signature does not delay the filled heart`() {
		val port = Port()
		lateinit var likes: SnapLikeController
		var duringPrepare: SnapHeart? = null
		port.duringPrepare = { duringPrepare = likes.heart(target, ViewerVote.None) }
		likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals(SnapHeart.FILLED, duringPrepare)
	}

	@Test
	fun `a slow second safety read does not delay the filled heart`() {
		val port = Port()
		lateinit var likes: SnapLikeController
		var duringSecond: SnapHeart? = null
		port.duringSecondRead = { duringSecond = likes.heart(target, ViewerVote.None) }
		likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals(SnapHeart.FILLED, duringSecond)
	}

	/**
	 * There is no busy state to render, so there is no "Liking…" to render it
	 * with. Pinned as an absence on the enum, because a label can only come
	 * back if a state comes back with it.
	 */
	@Test
	fun `there is no busy heart state at all`() {
		assertEquals(
			listOf("OUTLINE", "FILLED", "INERT"),
			SnapHeart.entries.map { it.name },
		)
	}

	/** And an optimistic Like says nothing — it looks exactly like a finished one. */
	@Test
	fun `an optimistic Like shows no notice`() {
		val port = Port()
		lateinit var likes: SnapLikeController
		var noticeDuring: String? = "unset"
		port.duringRead = { noticeDuring = likes.notice(target) }
		likes = controller(port) { "alice" }

		likes.like(target)

		assertNull("an in-flight Like must say nothing", noticeDuring)
	}

	/** A second tap on an already-filled optimistic heart starts nothing. */
	@Test
	fun `a second tap while optimistic starts no second attempt`() {
		val port = Port()
		lateinit var likes: SnapLikeController
		port.duringRead = { likes.like(target); likes.like(target) }
		likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals("one attempt's two reads", 2, port.reads)
		assertEquals(1, port.prepares.size)
		assertEquals(1, port.broadcasts.size)
	}

	// ── reversion ──────────────────────────────────────────────────────

	@Test
	fun `a refused first read reverts the optimistic heart`() {
		val port = Port(read = HiveVoteRead.Unavailable("every node was stale"))
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals(SnapHeart.OUTLINE, likes.heart(target, ViewerVote.None))
		assertTrue(likes.state(target) is SnapLikeState.Refused)
		assertNotNull("and says why", likes.notice(target))
		assertEquals(0, port.broadcasts.size)
	}

	@Test
	fun `a refused second read reverts the optimistic heart`() {
		val port = Port()
		port.readQueue += HiveVoteRead.Fresh(ViewerVote.None, "https://node")
		port.readQueue += HiveVoteRead.Unavailable("went stale between the reads")
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals(SnapHeart.OUTLINE, likes.heart(target, ViewerVote.None))
		assertEquals("signed but never sent", 1, port.prepares.size)
		assertEquals(0, port.broadcasts.size)
	}

	@Test
	fun `a refused signature reverts the optimistic heart`() {
		val port = Port().apply { prepareRefusal = "RustedWax couldn't read your posting key." }
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals(SnapHeart.OUTLINE, likes.heart(target, ViewerVote.None))
		assertEquals(0, port.broadcasts.size)
	}

	/** An account switch discards the optimism rather than leaving it filled. */
	@Test
	fun `an account switch discards the optimistic state`() {
		val port = Port()
		var who: String? = "alice"
		port.duringRead = { who = "bob" }
		val likes = controller(port) { who }

		likes.like(target)

		assertNull("nothing under Bob", likes.state(target))
		assertEquals(SnapHeart.OUTLINE, likes.heart(target, ViewerVote.None))
		who = "alice"
		assertNull("and nothing left filled under Alice", likes.state(target))
		assertEquals(SnapHeart.OUTLINE, likes.heart(target, ViewerVote.None))
		assertEquals(0, port.broadcasts.size)
	}

	/** A block-confirmed vote simply stays filled — the optimism was right. */
	@Test
	fun `a block-confirmed Like stays filled`() {
		val port = Port()
		val likes = controller(port) { "alice" }
		likes.like(target)
		assertEquals(SnapLikeState.Liked(10), likes.state(target))
		assertEquals(SnapHeart.FILLED, likes.heart(target, ViewerVote.None))
	}

	/** So does a positive vote discovered by the preflight read, with no send. */
	@Test
	fun `an existing positive discovered by the preflight stays filled with no broadcast`() {
		val port = Port(read = HiveVoteRead.Fresh(ViewerVote.Positive(7500, 9L), "https://node"))
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertEquals(SnapHeart.FILLED, likes.heart(target, ViewerVote.None))
		assertEquals(0, port.prepares.size)
		assertEquals(0, port.broadcasts.size)
	}

	// ── the Stage 6 seam ───────────────────────────────────────────────

	/**
	 * The unsettled Likes a bell would one day show, as a plain query over
	 * state that already exists. No store, no channel, no notification
	 * machinery — Stage 6 adds a reader, not a rewrite.
	 */
	@Test
	fun `unsettled Likes are queryable for a later notification surface`() {
		val port = Port(read = HiveVoteRead.Unavailable("stale"))
		val likes = controller(port) { "alice" }
		likes.like(target)

		val attention = likes.needsAttention()
		assertEquals(1, attention.size)
		assertEquals("bob/r1", attention.single().first.contentId)
		assertTrue(attention.single().second is SnapLikeState.Refused)
	}

	@Test
	fun `a settled Like needs no attention`() {
		val port = Port()
		val likes = controller(port) { "alice" }
		likes.like(target)
		assertEquals(emptyList<Any>(), likes.needsAttention())
	}

	@Test
	fun `another account's unsettled Likes are not surfaced`() {
		val port = Port(read = HiveVoteRead.Unavailable("stale"))
		var who: String? = "alice"
		val likes = controller(port) { who }
		likes.like(target)
		assertEquals(1, likes.needsAttention().size)

		who = "bob"
		assertEquals(emptyList<Any>(), likes.needsAttention())
	}

	private companion object {
		fun inBlock() =
			HiveRpc.BroadcastResult.Success("tx", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)
	}

	// ── the social count beside the heart ─────────────────────────────
	//
	// Two facts, deliberately separate: the heart is "did *I* like this", the
	// number is "how many people did". The only place they meet is the one
	// vote this device knows about before hivemind does — and the rule that
	// adds it is written so it can never add it twice.

	@Test
	fun `five other likes read as five, with the heart still an outline`() {
		val likes = controller { "alice" }

		assertEquals(5, likes.likeCount(target, ViewerVote.None, 5))
		assertEquals(SnapHeart.OUTLINE, likes.heart(target, ViewerVote.None))
	}

	@Test
	fun `a tap adds exactly one, immediately`() {
		val port = Port()
		port.result = HiveRpc.BroadcastResult.NetworkFailure("slow")
		val likes = controller(port) { "alice" }
		var duringPrepare: Int? = null
		port.duringPrepare = { duringPrepare = likes.likeCount(target, ViewerVote.None, 5) }

		likes.like(target)

		assertEquals("counted before anything was signed", 6, duringPrepare)
	}

	/**
	 * And the moment the chain catches up, it is still six.
	 *
	 * The count the read brings back already contains this vote, so the local
	 * +1 stands down. Without that condition the number would climb to seven
	 * the first time hivemind agreed with the user.
	 */
	@Test
	fun `authoritative agreement does not double-count`() {
		val likes = controller { "alice" }
		likes.like(target)
		assertEquals(SnapHeart.FILLED, likes.heart(target, ViewerVote.None))

		val mine = ViewerVote.Positive(1000, 5L)

		assertEquals("before the read catches up", 6, likes.likeCount(target, ViewerVote.None, 5))
		assertEquals("and after it does", 6, likes.likeCount(target, mine, 6))
	}

	/** A proven refusal takes the number back with the heart. */
	@Test
	fun `a definitive refusal rolls the count back`() {
		val port = Port(read = HiveVoteRead.Unavailable("no fresh node"))
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertTrue(likes.state(target) is SnapLikeState.Refused)
		assertEquals(SnapHeart.OUTLINE, likes.heart(target, ViewerVote.None))
		assertEquals("back to what the chain says", 5, likes.likeCount(target, ViewerVote.None, 5))
	}

	/** An ambiguous one keeps both, because the vote probably landed. */
	@Test
	fun `a pending attempt keeps its optimistic plus one`() {
		val port = Port(result = HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val likes = controller(port) { "alice" }

		likes.like(target)

		assertTrue(likes.state(target) is SnapLikeState.Pending)
		assertEquals(SnapHeart.FILLED, likes.heart(target, ViewerVote.None))
		assertEquals(6, likes.likeCount(target, ViewerVote.None, 5))
		assertEquals("one attempt, one vote", 1, port.broadcasts.size)
	}

	/**
	 * A vote cast somewhere else needs no help from this device.
	 *
	 * The heart is filled and the count is the chain's, exactly as read —
	 * nothing local is added, because nothing local happened.
	 */
	@Test
	fun `an externally cast vote is filled with the authoritative count`() {
		val likes = controller { "alice" }
		val external = ViewerVote.Positive(7500, 873948322L)

		assertEquals(SnapHeart.FILLED, likes.heart(target, external))
		assertEquals(3, likes.likeCount(target, external, 3))
		assertNull("and nothing local claims it", likes.state(target))
	}

	/** A downvote from elsewhere is inert, and adds nothing to the number. */
	@Test
	fun `an inert heart adds nothing`() {
		val likes = controller { "alice" }
		val down = ViewerVote.Negative(-2500, -1775976786L)

		assertEquals(SnapHeart.INERT, likes.heart(target, down))
		assertEquals(4, likes.likeCount(target, down, 4))
	}

	/** Counts are read-only: asking for one sends nothing and reads nothing. */
	@Test
	fun `asking for a count touches neither the chain nor the vote state`() {
		val port = Port()
		val likes = controller(port) { "alice" }

		repeat(3) { likes.likeCount(target, ViewerVote.None, 5) }

		assertEquals(0, port.reads)
		assertEquals(emptyList<Any>(), port.log)
		assertNull(likes.state(target))
	}

	/** A nonsensical count from anywhere never renders as a negative number. */
	@Test
	fun `a negative count reads as none`() {
		val likes = controller { "alice" }

		assertEquals(0, likes.likeCount(target, ViewerVote.None, -3))
	}
}
