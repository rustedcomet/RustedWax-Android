package com.rustedwax.app.snaps

import com.rustedwax.hive.HiveVoteRead
import com.rustedwax.hive.ViewerVote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole Like safety contract, as a table.
 *
 * Every rule Stage 5 was given lives in one pure function, so it can be pinned
 * without a coroutine, a network, a key or an Android class anywhere near it.
 * The property these tests actually assert is an **absence**: on every branch
 * that is not [SnapLikeDecision.Cast], zero transactions are prepared and zero
 * are broadcast — which is provable here because reaching
 * [SnapLikeDecision.Cast] is the only way a caller is handed a weight to sign.
 *
 * The case that matters most is the one that looks least dramatic: an existing
 * positive vote at a strength the user chose somewhere else. Re-voting it at
 * this app's default would silently turn a 100% vote into a 10% one, and Hive
 * would accept that quietly and permanently.
 */
class SnapLikeDecisionTest {

	private val viewer = "alice"
	private val author = "bob"

	private fun decide(
		vote: ViewerVote,
		percent: Int = 10,
		viewer: String = this.viewer,
		author: String = this.author,
	) = SnapLikeDecisions.of(viewer, author, HiveVoteRead.Fresh(vote, "https://node"), percent)

	private fun unavailable(percent: Int = 10) = SnapLikeDecisions.of(
		viewer,
		author,
		HiveVoteRead.Unavailable("every node was stale"),
		percent,
	)

	// ── eligible ───────────────────────────────────────────────────────

	@Test
	fun `no vote casts at the stored strength`() {
		assertEquals(SnapLikeDecision.Cast(1000), decide(ViewerVote.None))
	}

	@Test
	fun `a removed vote casts, because zero is not a vote`() {
		assertEquals(SnapLikeDecision.Cast(1000), decide(ViewerVote.Zero))
	}

	@Test
	fun `the stored percent becomes the chain weight`() {
		assertEquals(SnapLikeDecision.Cast(1000), decide(ViewerVote.None, percent = 10))
		assertEquals(SnapLikeDecision.Cast(3700), decide(ViewerVote.None, percent = 37))
		assertEquals(SnapLikeDecision.Cast(10000), decide(ViewerVote.None, percent = 100))
	}

	/**
	 * The stored preference outlives the slider that wrote it, so a value from
	 * an older build or a hand-edited file must not become a 0% vote — which
	 * Hive reads as *removing* one — or a vote stronger than anybody chose.
	 */
	@Test
	fun `an out-of-range stored percent is clamped rather than obeyed`() {
		assertEquals(SnapLikeDecision.Cast(1000), decide(ViewerVote.None, percent = 0))
		assertEquals(SnapLikeDecision.Cast(1000), decide(ViewerVote.None, percent = -40))
		assertEquals(SnapLikeDecision.Cast(10000), decide(ViewerVote.None, percent = 9_999))
	}

	// ── an existing positive vote is never overwritten ─────────────────

	/**
	 * The central safety case. A vote cast at any strength, on any frontend, is
	 * left exactly as it is.
	 */
	@Test
	fun `an existing positive vote is never overwritten, at any strength`() {
		listOf(1, 1000, 2500, 5000, 10000).forEach { existing ->
			val decision = decide(ViewerVote.Positive(existing, 5L))
			assertEquals(
				"an existing $existing vote must not be rewritten",
				SnapLikeDecision.AlreadyLiked(existing),
				decision,
			)
		}
	}

	/** Including — especially — when this app would have voted more weakly. */
	@Test
	fun `a strong existing vote is not weakened by a weak setting`() {
		val decision = decide(ViewerVote.Positive(10000, 5L), percent = 10)
		assertTrue("a 100% vote must survive a 10% setting", decision is SnapLikeDecision.AlreadyLiked)
	}

	/** And when this app would have voted more strongly, which is no better. */
	@Test
	fun `a weak existing vote is not strengthened by a strong setting`() {
		val decision = decide(ViewerVote.Positive(1000, 5L), percent = 100)
		assertTrue(decision is SnapLikeDecision.AlreadyLiked)
	}

	/** A vote `bridge` reported with no percent still counts as a vote. */
	@Test
	fun `a positive vote with no readable percent is still never overwritten`() {
		assertEquals(
			SnapLikeDecision.AlreadyLiked(null),
			decide(ViewerVote.Positive(null, 900L)),
		)
	}

	// ── a downvote from elsewhere is left alone ────────────────────────

	@Test
	fun `an existing downvote sends nothing`() {
		val decision = decide(ViewerVote.Negative(-10000, -5L))
		assertTrue("got $decision", decision is SnapLikeDecision.Inert)
	}

	@Test
	fun `a downvote is not quietly turned into an upvote`() {
		assertTrue(decide(ViewerVote.Negative(-500, -1L)) !is SnapLikeDecision.Cast)
	}

	// ── anything unestablished refuses ─────────────────────────────────

	/**
	 * The branch a stalled node reaches. It must never become "no vote": that
	 * reading is exactly how RustedWax would overwrite a vote cast minutes
	 * earlier somewhere else.
	 */
	@Test
	fun `a read no current node could answer refuses the write`() {
		val decision = unavailable()
		assertTrue("got $decision", decision is SnapLikeDecision.Refuse)
		assertTrue(decision !is SnapLikeDecision.Cast)
	}

	@Test
	fun `an unreadable vote row refuses the write`() {
		val decision = decide(ViewerVote.Unreadable("two vote rows for one account"))
		assertTrue("got $decision", decision is SnapLikeDecision.Refuse)
		assertTrue(
			"the reason should reach the user",
			(decision as SnapLikeDecision.Refuse).message.contains("two vote rows"),
		)
	}

	// ── self-authored ──────────────────────────────────────────────────

	@Test
	fun `liking your own comment is refused`() {
		val decision = decide(ViewerVote.None, viewer = "alice", author = "alice")
		assertTrue("got $decision", decision is SnapLikeDecision.Refuse)
	}

	/** The same casing rule the broadcaster's own self-vote refusal uses. */
	@Test
	fun `a self-like is refused whatever the casing`() {
		assertTrue(
			decide(ViewerVote.None, viewer = "alice", author = "Alice")
				is SnapLikeDecision.Refuse,
		)
		assertTrue(
			decide(ViewerVote.None, viewer = "ALICE", author = "alice")
				is SnapLikeDecision.Refuse,
		)
	}

	@Test
	fun `nobody signed in cannot Like`() {
		val decision = decide(ViewerVote.None, viewer = "")
		assertTrue("got $decision", decision is SnapLikeDecision.Refuse)
	}

	/**
	 * Self-authorship is checked **before** the read is even consulted, so a
	 * stale or broken answer cannot change whether the user may vote for
	 * themselves.
	 */
	@Test
	fun `a self-like is refused even when the read was unavailable`() {
		val decision = SnapLikeDecisions.of(
			"alice",
			"alice",
			HiveVoteRead.Unavailable("nothing answered"),
			10,
		)
		assertTrue(decision is SnapLikeDecision.Refuse)
	}

	// ── the absence, stated ────────────────────────────────────────────

	/**
	 * Exactly one branch of the table produces a weight to sign. Written as a
	 * sweep rather than as a comment so that a sixth outcome added later cannot
	 * quietly become castable.
	 */
	@Test
	fun `only a missing or zero vote ever authorizes a broadcast`() {
		val castable = listOf<ViewerVote>(ViewerVote.None, ViewerVote.Zero)
		val refusing = listOf(
			ViewerVote.Positive(2500, 1L),
			ViewerVote.Positive(null, 1L),
			ViewerVote.Negative(-2500, -1L),
			ViewerVote.Unreadable("malformed"),
		)
		castable.forEach {
			assertTrue("$it must authorize", decide(it) is SnapLikeDecision.Cast)
		}
		refusing.forEach {
			assertTrue("$it must not authorize", decide(it) !is SnapLikeDecision.Cast)
		}
		assertTrue("an unavailable read must not authorize", unavailable() !is SnapLikeDecision.Cast)
	}

	// ── an incomplete row cannot reach Cast ────────────────────────────

	/**
	 * The end of the chain the parser fix protects.
	 *
	 * `HiveVotes` now refuses a viewer row missing `percent` or `rshares`
	 * instead of letting it fall through to [ViewerVote.Zero] — but the reason
	 * that mattered is here: `Zero` is *eligible*, and `Cast` is the only
	 * decision that signs anything. This pins the consequence rather than the
	 * mechanism, so the two cannot drift apart.
	 */
	@Test
	fun `an unreadable viewer row can never reach Cast`() {
		val reasons = listOf(
			"the vote row carried no percent",
			"the vote row carried no rshares",
			"vote percent: was null",
			"vote weight: was null",
			"vote percent: not a whole number: 0.9",
			"vote percent 2500 disagrees with rshares -5",
		)
		reasons.forEach { reason ->
			val decision = decide(ViewerVote.Unreadable(reason))
			assertTrue("'$reason' must refuse, got $decision", decision is SnapLikeDecision.Refuse)
			assertTrue("'$reason' must never authorize", decision !is SnapLikeDecision.Cast)
		}
	}

	/**
	 * And the only two states that may.
	 *
	 * Stated as a closed sweep over every [ViewerVote] a fresh read can now
	 * carry, so a sixth case added later has to be classified deliberately
	 * rather than inheriting the eligible branch by default.
	 */
	@Test
	fun `exactly two viewer states authorize a Cast`() {
		val eligible = listOf<ViewerVote>(ViewerVote.None, ViewerVote.Zero)
		val refusing = listOf(
			ViewerVote.Positive(2500, 5L),
			ViewerVote.Positive(900, 0L),
			ViewerVote.Negative(-2500, -5L),
			ViewerVote.Negative(-900, 0L),
			ViewerVote.Unreadable("anything at all"),
		)
		eligible.forEach { assertTrue("$it must authorize", decide(it) is SnapLikeDecision.Cast) }
		refusing.forEach { assertTrue("$it must not authorize", decide(it) !is SnapLikeDecision.Cast) }
	}

	/** A zero-rshares vote is a real vote and is never overwritten. */
	@Test
	fun `a real vote whose rshares rounded to zero is not overwritten`() {
		assertEquals(SnapLikeDecision.AlreadyLiked(900), decide(ViewerVote.Positive(900, 0L)))
		assertTrue(decide(ViewerVote.Negative(-900, 0L)) is SnapLikeDecision.Inert)
	}


	/**
	 * An unattributable vote row can never reach [SnapLikeDecision.Cast].
	 *
	 * The parser now refuses an authoritative response containing a row whose
	 * `voter` is not a valid Hive account — `""`, `"Alice"`, `"a..b"`, anything
	 * with a slash — instead of skipping it and letting the array answer "you
	 * have not voted here". This pins the consequence of that, at the end of the
	 * chain where it would actually have cost something: the decision that signs.
	 */
	@Test
	fun `an unattributable voter row can never reach Cast`() {
		val reasons = listOf(
			"a vote row named no valid Hive account",
			"a vote row had no readable voter",
			"no readable signed-in account to look a vote up for",
		)
		reasons.forEach { reason ->
			val decision = decide(ViewerVote.Unreadable(reason))
			assertTrue("'$reason' must refuse, got $decision", decision is SnapLikeDecision.Refuse)
			assertTrue("'$reason' must never authorize", decision !is SnapLikeDecision.Cast)
		}
	}

	/**
	 * And the same through a read that could not be established at all, which is
	 * what a whole response full of unattributable rows becomes.
	 */
	@Test
	fun `a read refused over identity authorizes nothing`() {
		val decision = SnapLikeDecisions.of(
			viewer,
			author,
			HiveVoteRead.Unavailable("one.example: a vote row named no valid Hive account"),
			10,
		)
		assertTrue(decision is SnapLikeDecision.Refuse)
		assertTrue(decision !is SnapLikeDecision.Cast)
	}

}
