package com.rustedwax.hive

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Like safety read: which node is allowed to answer, and what an answer may
 * be read as.
 *
 * Two properties are pinned here and both are invisible from the outside until
 * the day they matter:
 *
 *  - **the node that proved it was current is the node that answered.** A
 *    freshness check against one machine and a vote list from another is a
 *    freshness check in name only, and the failure looks exactly like correct
 *    behaviour until a node stalls;
 *  - **nothing is coerced.** A malformed identity field must not become a
 *    string, and a row that cannot be read must not become an absence — an
 *    absence authorizes a vote, and that is the one thing an unreadable answer
 *    may never do.
 *
 * The fixtures are the live response shapes, captured from `api.hive.blog`,
 * `api.deathwing.me`, `api.syncad.com` and `api.openhive.network` on
 * 2026-09-18. All four agreed field for field.
 */
class HiveVoteReadTest {

	private val nodes = listOf("https://one.example", "https://two.example", "https://three.example")

	private fun rpc() = HiveRpc(nodes)

	/**
	 * One `condenser_api.get_active_votes` row, exactly as the live API writes
	 * it.
	 *
	 * Captured from `api.hive.blog`, `api.deathwing.me`, `api.syncad.com` and
	 * `api.openhive.network` on 2026-09-19. All four agreed field for field, and
	 * all four answered an unvoted comment with a bare empty array.
	 */
	private fun voteRow(
		voter: String = "alice",
		percent: Any? = 2500,
		rshares: Any? = 120728973539L,
	) = JSONObject().apply {
		put("time", "2026-09-18T18:06:03")
		put("voter", voter)
		put("weight", 120728973539L)
		put("reputation", 1917125239485040L)
		percent?.let { put("percent", it) }
		rshares?.let { put("rshares", it) }
	}

	private fun votes(vararg rows: JSONObject) = JSONArray().apply { rows.forEach { put(it) } }

	// ── fail-closed parsing of get_active_votes ───────────────────────

	@Test
	fun `a live get_active_votes row reads as the positive vote it is`() {
		val vote = HiveVotes.fromActiveVotes(votes(voteRow()), "alice")
		assertEquals(ViewerVote.Positive(2500, 120728973539L), vote)
	}

	@Test
	fun `a voter with no row has no vote`() {
		assertEquals(ViewerVote.None, HiveVotes.fromActiveVotes(votes(voteRow()), "carol"))
	}

	@Test
	fun `an empty vote list has no vote`() {
		assertEquals(ViewerVote.None, HiveVotes.fromActiveVotes(votes(), "alice"))
	}

	/**
	 * The two sides of the comparison are treated differently, on purpose.
	 *
	 * The **viewer** is normalised: it comes from this device, the vault already
	 * lowercases it, and a caller that handed over `ALICE` is naming a real
	 * account rather than a malformed one. The **row** is not: it came off the
	 * network, `Alice` is not a syntactically valid Hive account name, and on
	 * the authoritative path a row that cannot name an account is a row that
	 * might be a damaged copy of this viewer's own.
	 */
	@Test
	fun `the viewer is normalised but the row is judged strictly`() {
		assertTrue(
			"a caller's uppercase spelling of a real account still matches",
			HiveVotes.fromActiveVotes(votes(voteRow(voter = "alice")), "ALICE")
				is ViewerVote.Positive,
		)
		assertUnreadable(
			HiveVotes.fromActiveVotes(votes(voteRow(voter = "Alice")), "alice"),
		)
	}

	/**
	 * A viewer that is not an account name even after normalising.
	 *
	 * `"Alice"` is deliberately **not** in this list — it lowercases to a real
	 * account and is covered by the test above. What is here is what no amount
	 * of trimming or lowercasing turns into a Hive account, and there is nothing
	 * useful to look such a viewer up against.
	 */
	@Test
	fun `a viewer that is not a Hive account name refuses`() {
		listOf("", "   ", "a..b", "al/ice", "ab", "x".repeat(17)).forEach { bad ->
			assertUnreadable(HiveVotes.fromActiveVotes(votes(voteRow()), bad))
		}
	}

	@Test
	fun `a removed vote is zero and stays eligible`() {
		assertEquals(
			ViewerVote.Zero,
			HiveVotes.fromActiveVotes(votes(voteRow(percent = 0, rshares = 0)), "alice"),
		)
	}

	@Test
	fun `a downvote reads as negative`() {
		val vote = HiveVotes.fromActiveVotes(
			votes(voteRow(percent = -5000, rshares = -900L)),
			"alice",
		)
		assertEquals(ViewerVote.Negative(-5000, -900L), vote)
	}

	/**
	 * The declared percentage outranks the computed weight.
	 *
	 * A genuine 10% vote from an account with no voting power left rounds to
	 * zero rshares. Reading that as "no vote" would let RustedWax overwrite it,
	 * which is the exact failure the whole read exists to prevent.
	 */
	@Test
	fun `a real vote worth zero rshares is still a vote`() {
		assertEquals(
			ViewerVote.Positive(1000, 0L),
			HiveVotes.fromActiveVotes(votes(voteRow(percent = 1000, rshares = 0)), "alice"),
		)
	}

	/** `bridge` carries no percent at all, so the sign of rshares has to serve. */
	@Test
	fun `without a percent the rshares sign decides`() {
		assertEquals(ViewerVote.Positive(null, 5L), HiveVotes.classify(null, 5L))
		assertEquals(ViewerVote.Negative(null, -5L), HiveVotes.classify(null, -5L))
		assertEquals(ViewerVote.Zero, HiveVotes.classify(null, 0L))
	}

	/**
	 * `optString` coerces — `42` becomes `"42"`, `true` becomes `"true"` — so a
	 * wrong-typed identity must never be read as an account.
	 *
	 * And it must not be quietly skipped either. A row whose voter cannot be
	 * read cannot be attributed to anybody, which means it might be *this*
	 * voter's — so the whole read fails rather than concluding an absence around
	 * it. An absence is the one answer that authorizes a write.
	 */
	@Test
	fun `a non-string voter refuses the read instead of being skipped`() {
		listOf(42, true, JSONObject(), JSONArray(), JSONObject.NULL).forEach { bad ->
			val row = voteRow().put("voter", bad)
			val vote = HiveVotes.fromActiveVotes(votes(row), "alice")
			assertTrue(
				"a $bad voter must refuse, got $vote",
				vote is ViewerVote.Unreadable,
			)
			assertTrue("and must never read as an absence", vote != ViewerVote.None)
		}
	}

	/** Including when the malformed row sits beside perfectly good ones. */
	@Test
	fun `one unreadable row refuses a list that otherwise looks fine`() {
		val mixed = votes(
			voteRow(voter = "carol"),
			voteRow().put("voter", 42),
			voteRow(voter = "dave"),
		)
		assertTrue(HiveVotes.fromActiveVotes(mixed, "alice") is ViewerVote.Unreadable)
	}

	@Test
	fun `a row with no readable weight at all is unreadable rather than absent`() {
		val row = voteRow(percent = null, rshares = null)
		val vote = HiveVotes.fromActiveVotes(votes(row), "alice")
		assertTrue("got $vote", vote is ViewerVote.Unreadable)
	}

	@Test
	fun `a wrong-typed weight is unreadable rather than absent`() {
		listOf(true, JSONObject(), "not a number").forEach { bad ->
			val row = voteRow(percent = null).put("rshares", bad)
			assertTrue(
				"a $bad rshares must not read as a vote",
				HiveVotes.fromActiveVotes(votes(row), "alice") is ViewerVote.Unreadable,
			)
		}
	}

	/** Hive has shipped `rshares` as a string; refusing that would refuse a real Like. */
	@Test
	fun `a numeric string weight is accepted`() {
		val row = voteRow(percent = 2500, rshares = "120728973539")
		assertEquals(
			ViewerVote.Positive(2500, 120728973539L),
			HiveVotes.fromActiveVotes(votes(row), "alice"),
		)
	}

	/** And on the display shape, where there is no percent to lean on. */
	@Test
	fun `a numeric string weight is accepted on a bridge comment`() {
		val comment = JSONObject().put(
			"active_votes",
			JSONArray().put(JSONObject().put("voter", "alice").put("rshares", "120728973539")),
		)
		assertEquals(
			ViewerVote.Positive(null, 120728973539L),
			HiveVotes.inComment(comment, "alice"),
		)
	}

	@Test
	fun `a missing vote list is unreadable rather than absent`() {
		assertTrue(HiveVotes.fromActiveVotes(null, "alice") is ViewerVote.Unreadable)
	}

	/** `(voter, author, permlink)` is unique on chain, so two rows is a broken response. */
	@Test
	fun `two rows for one account refuse the whole read`() {
		val vote = HiveVotes.fromActiveVotes(
			votes(voteRow(), voteRow(percent = 10000)),
			"alice",
		)
		assertTrue("got $vote", vote is ViewerVote.Unreadable)
	}

	/**
	 * A row that is not an object stops the read dead.
	 *
	 * The old behaviour skipped it and carried on, which is the ordinary parser
	 * instinct and exactly wrong here: the skipped row may have been this
	 * voter's, and skipping it turns a vote into an absence and an absence into
	 * an overwrite.
	 */
	@Test
	fun `a row that is not an object refuses the read`() {
		listOf<Any>("junk", 7, true, JSONArray(), JSONObject.NULL).forEach { bad ->
			val mixed = JSONArray().put(bad).put(voteRow())
			val vote = HiveVotes.fromActiveVotes(mixed, "alice")
			assertTrue("a $bad row must refuse, got $vote", vote is ViewerVote.Unreadable)
		}
	}

	/** A result that is not a list at all is not an answer to anything. */
	@Test
	fun `a non-array active_votes refuses`() {
		val comment = JSONObject().put("active_votes", JSONObject().put("voter", "alice"))
		assertTrue(HiveVotes.inComment(comment, "alice") is ViewerVote.Unreadable)
	}

	// ── no lossy numbers ───────────────────────────────────────────────

	/**
	 * The truncation that used to be silent, and what it cost.
	 *
	 * `toInt()` on a `Double` discards the fraction, so `"percent": 0.9` became
	 * `0`, which classifies as [ViewerVote.Zero], which is *eligible to Like* —
	 * a vote cast straight over whatever that row really said. Fractional values
	 * are refused outright rather than read approximately.
	 */
	@Test
	fun `a fractional percent refuses rather than truncating to zero`() {
		listOf(0.9, 0.5, -0.4, 2500.5, 1e-3).forEach { bad ->
			val vote = HiveVotes.fromActiveVotes(votes(voteRow(percent = bad)), "alice")
			assertTrue("percent $bad must refuse, got $vote", vote is ViewerVote.Unreadable)
			assertTrue("and must never be eligible", vote != ViewerVote.Zero)
		}
	}

	@Test
	fun `a fractional rshares refuses rather than truncating`() {
		listOf(0.9, -0.9, 120728973539.5).forEach { bad ->
			val vote = HiveVotes.fromActiveVotes(
				votes(voteRow(percent = null, rshares = bad)),
				"alice",
			)
			assertTrue("rshares $bad must refuse, got $vote", vote is ViewerVote.Unreadable)
		}
	}

	/**
	 * **Every decimal value is refused, whole-looking or not.**
	 *
	 * Reversed deliberately. An earlier revision accepted `2500.0` on the
	 * reasoning that a node writing it has still said 2500 — true of that
	 * value, and not true in general. Android's `JSONTokener` boxes any token
	 * containing `.`, `e` or `E` as a `Double`, and by the time this code sees
	 * one the rounding has already happened: `9007199254740993.0` arrives as
	 * `9007199254740992.0`, identical to the token below it. `toLong()`, a
	 * floor check, a `% 1.0` check and a 2^53 bound all agree the rounded value
	 * is whole, because it is — none of them can see it is the *wrong* whole
	 * number.
	 *
	 * On this field that is a write-safety hole rather than a display bug. A
	 * row whose `rshares` rounds and whose `percent` reads 0 classifies as
	 * [ViewerVote.Zero], which is eligible, which authorizes an irreversible
	 * vote over whatever that row really said.
	 */
	@Test
	fun `every decimal percent is refused however whole it looks`() {
		listOf<Any>(1000.0, 0.0, -1000.0, 1000.5, 2500.0, 2.0e9, 1.0f, 2500.0f).forEach { bad ->
			val vote = HiveVotes.fromActiveVotes(votes(voteRow(percent = bad)), "alice")
			assertUnreadable(vote)
		}
	}

	@Test
	fun `every decimal rshares is refused however whole it looks`() {
		listOf<Any>(
			123.0,
			9_007_199_254_740_993.0,
			9_007_199_254_740_992.0,
			0.0,
			-900.0,
			2.0e9,
			123.5,
			1.0f,
			2.0e9f,
		).forEach { bad ->
			val vote = HiveVotes.fromActiveVotes(
				votes(voteRow(percent = 2500, rshares = bad)),
				"alice",
			)
			assertUnreadable(vote)
		}
	}

	/**
	 * `BigDecimal` too: the reference `org.json` boxes the same tokens that
	 * way, and its `scale()` cannot separate `9.007199254740992E15` from
	 * `9007199254740992` — both report scale zero and equal precision.
	 */
	@Test
	fun `a BigDecimal vote value is refused whatever its scale`() {
		listOf("9.007199254740992E15", "2E9", "2500.0", "2500", "0").forEach { text ->
			assertUnreadable(
				HiveVotes.fromActiveVotes(
					votes(voteRow(percent = 2500, rshares = java.math.BigDecimal(text))),
					"alice",
				),
			)
			assertUnreadable(
				HiveVotes.fromActiveVotes(
					votes(voteRow(percent = java.math.BigDecimal(text))),
					"alice",
				),
			)
		}
	}

	/** The rounding pair that motivated the rule, stated on its own. */
	@Test
	fun `the two tokens Android cannot tell apart are both refused`() {
		// 9007199254740993.0 is delivered as 9007199254740992.0. Neither may
		// be trusted, because neither can be distinguished from the other.
		assertUnreadable(
			HiveVotes.fromActiveVotes(
				votes(voteRow(percent = 0, rshares = 9_007_199_254_740_993.0)),
				"alice",
			),
		)
		assertUnreadable(
			HiveVotes.fromActiveVotes(
				votes(voteRow(percent = 0, rshares = 9_007_199_254_740_992.0)),
				"alice",
			),
		)
	}

	/** Integer values still classify exactly as before. */
	@Test
	fun `integer vote values still classify normally`() {
		assertEquals(
			ViewerVote.Zero,
			HiveVotes.fromActiveVotes(votes(voteRow(percent = 0, rshares = 0)), "alice"),
		)
		assertEquals(
			ViewerVote.Positive(2500, 120728973539L),
			HiveVotes.fromActiveVotes(votes(voteRow(percent = 2500)), "alice"),
		)
		assertEquals(
			ViewerVote.Negative(-2500, -900L),
			HiveVotes.fromActiveVotes(votes(voteRow(percent = -2500, rshares = -900L)), "alice"),
		)
		assertEquals(
			ViewerVote.Positive(900, 0L),
			HiveVotes.fromActiveVotes(votes(voteRow(percent = 900, rshares = 0)), "alice"),
		)
		assertEquals(
			ViewerVote.Negative(-900, 0L),
			HiveVotes.fromActiveVotes(votes(voteRow(percent = -900, rshares = 0)), "alice"),
		)
	}

	/**
	 * The consequence, end to end: a decimal row can never authorize anything.
	 *
	 * `None` and `Zero` are the only two answers a Like may be cast on, so
	 * these are the two a malformed row must never become — and a fresh read
	 * built from such a row must never come back eligible.
	 */
	@Test
	fun `a decimal viewer row can never become None, Zero, or an eligible read`() {
		listOf<Pair<Any, Any>>(
			0 to 9_007_199_254_740_993.0,
			0.0 to 0,
			0.0 to 0.0,
			1000.0 to 5L,
			2500 to 2.0e9,
		).forEach { (percent, rshares) ->
			val row = voteRow(percent = percent, rshares = rshares)
			val vote = HiveVotes.fromActiveVotes(votes(row), "alice")
			assertTrue("got $vote", vote is ViewerVote.Unreadable)
			assertTrue("must never be an absence", vote !== ViewerVote.None)
			assertTrue("must never be eligible-zero", vote !== ViewerVote.Zero)

			// And through the read that actually authorizes.
			val read = rpc().findViewerVoteAcross(
				voter = "alice",
				lagOf = { 3L },
				votesFrom = { votes(row) },
			)
			assertTrue("got $read", read is HiveVoteRead.Unavailable)
		}
	}

	/** A node answering with a decimal row hands over to the next fresh node. */
	@Test
	fun `a node with a decimal vote value hands over to the next`() {
		val calls = Calls()
		val read = rpc().findViewerVoteAcross(
			voter = "alice",
			lagOf = { calls.lag(it); 3L },
			votesFrom = { node ->
				calls.fetch(node)
				if (node == nodes[0]) votes(voteRow(percent = 2500.0)) else votes(voteRow())
			},
		)

		assertEquals(
			listOf("lag" to nodes[0], "fetch" to nodes[0], "lag" to nodes[1], "fetch" to nodes[1]),
			calls.log,
		)
		assertEquals(nodes[1], (read as HiveVoteRead.Fresh).node)
		assertTrue(read.vote is ViewerVote.Positive)
	}

	/** A numeric string has to be strictly integral too. */
	@Test
	fun `a non-integral numeric string refuses`() {
		listOf("2500.0", "1e3", "0x10", " 2500", "2500 ", "", "+-1", "２５００").forEach { bad ->
			val vote = HiveVotes.fromActiveVotes(
				votes(voteRow(percent = null, rshares = bad)),
				"alice",
			)
			assertTrue("rshares \"$bad\" must refuse, got $vote", vote is ViewerVote.Unreadable)
		}
	}

	@Test
	fun `a signed integral string is accepted`() {
		assertEquals(
			ViewerVote.Negative(-2500, -900L),
			HiveVotes.fromActiveVotes(votes(voteRow(percent = -2500, rshares = "-900")), "alice"),
		)
	}

	/** An explicit null is a present-but-unusable value, not a missing field. */
	@Test
	fun `an explicit null weight refuses`() {
		val row = voteRow(percent = null).put("rshares", JSONObject.NULL)
		assertTrue(HiveVotes.fromActiveVotes(votes(row), "alice") is ViewerVote.Unreadable)
	}

	/** A percent that cannot fit an Int is not a percent this can read back. */
	@Test
	fun `an out-of-range percent refuses`() {
		val row = voteRow(percent = 9_000_000_000L)
		assertTrue(HiveVotes.fromActiveVotes(votes(row), "alice") is ViewerVote.Unreadable)
	}

	// ── active_votes, as bridge.get_discussion carries it ──────────────

	/** Verified live: `bridge` gives `voter` and `rshares` and no percent. */
	@Test
	fun `a bridge comment yields the viewer's vote from rshares alone`() {
		val comment = JSONObject().put(
			"active_votes",
			JSONArray().put(JSONObject().put("voter", "alice").put("rshares", 120728973539L)),
		)
		assertEquals(
			ViewerVote.Positive(null, 120728973539L),
			HiveVotes.inComment(comment, "alice"),
		)
	}

	@Test
	fun `a bridge comment nobody voted on yields no vote`() {
		val comment = JSONObject().put("active_votes", JSONArray())
		assertEquals(ViewerVote.None, HiveVotes.inComment(comment, "alice"))
	}

	/**
	 * A comment with no `active_votes` at all was never parsed, and guessing
	 * "nobody voted" about an object this code does not recognise is the same
	 * optimism everything else here refuses.
	 */
	@Test
	fun `a comment with no active_votes is unreadable rather than absent`() {
		assertTrue(HiveVotes.inComment(JSONObject(), "alice") is ViewerVote.Unreadable)
		assertTrue(
			HiveVotes.inComment(JSONObject().put("active_votes", JSONObject.NULL), "alice")
				is ViewerVote.Unreadable,
		)
	}

	/** `condenser_api.get_active_votes` does carry a percent, and it is preferred. */
	@Test
	fun `a condenser active_votes percent outranks its rshares`() {
		val list = JSONArray().put(
			JSONObject()
				.put("voter", "alice")
				.put("percent", 2500)
				.put("rshares", 0),
		)
		assertEquals(ViewerVote.Positive(2500, 0L), HiveVotes.fromActiveVotes(list, "alice"))
	}

	// ── same-node freshness ────────────────────────────────────────────

	/** Records every question asked of every node, in order. */
	private class Calls {
		val log = mutableListOf<Pair<String, String>>()
		fun lag(node: String) = log.add("lag" to node)
		fun fetch(node: String) = log.add("fetch" to node)
	}

	@Test
	fun `the node that proved it was current is the node that answered`() {
		val calls = Calls()
		val read = rpc().findViewerVoteAcross(
			voter = "alice",
			lagOf = { calls.lag(it); 5L },
			votesFrom = { calls.fetch(it); votes(voteRow()) },
		)

		// Freshness first, then the vote list, both against node one — and
		// nothing is asked of node two, because node one answered.
		assertEquals(
			listOf("lag" to nodes[0], "fetch" to nodes[0]),
			calls.log,
		)
		assertEquals(nodes[0], (read as HiveVoteRead.Fresh).node)
		assertTrue(read.vote is ViewerVote.Positive)
	}

	/**
	 * The failure this whole shape exists to prevent: checking one node's clock
	 * and then believing another node's vote list.
	 */
	@Test
	fun `a stale node is never asked for votes`() {
		val calls = Calls()
		val read = rpc().findViewerVoteAcross(
			voter = "alice",
			// One and two are hours behind; three is current.
			lagOf = { node -> calls.lag(node); if (node == nodes[2]) 4L else 9_000L },
			votesFrom = { calls.fetch(it); votes(voteRow()) },
		)

		assertEquals(
			listOf(
				"lag" to nodes[0],
				"lag" to nodes[1],
				"lag" to nodes[2],
				"fetch" to nodes[2],
			),
			calls.log,
		)
		assertEquals(nodes[2], (read as HiveVoteRead.Fresh).node)
	}

	@Test
	fun `a node that cannot say how far behind it is is never asked for votes`() {
		val calls = Calls()
		val read = rpc().findViewerVoteAcross(
			voter = "alice",
			lagOf = { calls.lag(it); null },
			votesFrom = { calls.fetch(it); votes(voteRow()) },
		)

		assertTrue("no node may be fetched from", calls.log.none { it.first == "fetch" })
		assertTrue(read is HiveVoteRead.Unavailable)
	}

	/**
	 * The one that authorizes nothing. Every node stale or silent has to answer
	 * [HiveVoteRead.Unavailable] — never an empty vote list, which would read as
	 * "no vote" and permit a write over whatever is really there.
	 */
	@Test
	fun `every node stale refuses rather than reporting no vote`() {
		val read = rpc().findViewerVoteAcross(
			voter = "alice",
			lagOf = { 9_000L },
			votesFrom = { votes(voteRow()) },
		)
		assertTrue("got $read", read is HiveVoteRead.Unavailable)
		assertTrue(
			(read as HiveVoteRead.Unavailable).reason.contains("behind the chain"),
		)
	}

	@Test
	fun `exactly the lag limit is still fresh and one second past it is not`() {
		val at = rpc().findViewerVoteAcross(
			voter = "alice",
			lagOf = { HiveRpc.MAX_NODE_LAG_SEC },
			votesFrom = { votes(voteRow()) },
		)
		assertTrue(at is HiveVoteRead.Fresh)

		val past = rpc().findViewerVoteAcross(
			voter = "alice",
			lagOf = { HiveRpc.MAX_NODE_LAG_SEC + 1 },
			votesFrom = { votes(voteRow()) },
		)
		assertTrue(past is HiveVoteRead.Unavailable)
	}

	/** A fresh node that answers with nothing usable is skipped, lag check and all. */
	@Test
	fun `a fresh node that cannot answer hands over to the next fresh node`() {
		val calls = Calls()
		val read = rpc().findViewerVoteAcross(
			voter = "alice",
			lagOf = { calls.lag(it); 3L },
			votesFrom = { node ->
				calls.fetch(node)
				if (node == nodes[0]) null else votes(voteRow())
			},
		)

		assertEquals(
			listOf(
				"lag" to nodes[0],
				"fetch" to nodes[0],
				"lag" to nodes[1],
				"fetch" to nodes[1],
			),
			calls.log,
		)
		assertEquals(nodes[1], (read as HiveVoteRead.Fresh).node)
	}

	@Test
	fun `no node able to answer refuses`() {
		val read = rpc().findViewerVoteAcross(
			voter = "alice",
			lagOf = { 3L },
			votesFrom = { null },
		)
		assertTrue("got $read", read is HiveVoteRead.Unavailable)
	}

	/** An absence proven by a current node is a real answer, and authorizes a Like. */
	@Test
	fun `a current node reporting no vote answers freshly`() {
		val read = rpc().findViewerVoteAcross(
			voter = "alice",
			lagOf = { 3L },
			votesFrom = { votes() },
		)
		assertEquals(ViewerVote.None, (read as HiveVoteRead.Fresh).vote)
	}


	/**
	 * A fresh node whose answer cannot be parsed has not answered the question.
	 *
	 * Another node may hold the same votes in a form this understands, so the
	 * list continues — but the parser's refusal is never handed back as though
	 * it were the chain saying "no vote".
	 */
	@Test
	fun `a fresh node answering unreadably hands over to the next`() {
		val calls = Calls()
		val read = rpc().findViewerVoteAcross(
			voter = "alice",
			lagOf = { calls.lag(it); 3L },
			votesFrom = { node ->
				calls.fetch(node)
				// Node one answers with a row nobody can attribute.
				if (node == nodes[0]) votes(voteRow().put("voter", 42)) else votes(voteRow())
			},
		)

		assertEquals(
			listOf(
				"lag" to nodes[0],
				"fetch" to nodes[0],
				"lag" to nodes[1],
				"fetch" to nodes[1],
			),
			calls.log,
		)
		assertEquals(nodes[1], (read as HiveVoteRead.Fresh).node)
		assertTrue(read.vote is ViewerVote.Positive)
	}

	/** Every node unreadable refuses; it never collapses into an absence. */
	@Test
	fun `every node answering unreadably refuses rather than reporting no vote`() {
		val read = rpc().findViewerVoteAcross(
			voter = "alice",
			lagOf = { 3L },
			votesFrom = { votes(voteRow().put("voter", JSONObject.NULL)) },
		)
		assertTrue("got $read", read is HiveVoteRead.Unavailable)
	}

	/**
	 * The empty array is a real answer and must survive the fail-closed rules.
	 *
	 * Every one of the four live nodes returns exactly `[]` for a comment nobody
	 * has voted on, so if strictness swallowed that, no Like could ever be cast.
	 */
	@Test
	fun `the live empty answer is a usable absence`() {
		val read = rpc().findViewerVoteAcross(
			voter = "alice",
			lagOf = { 3L },
			votesFrom = { JSONArray() },
		)
		assertEquals(ViewerVote.None, (read as HiveVoteRead.Fresh).vote)
	}


	// ── an incomplete viewer row can never authorize a vote ────────────
	//
	// [ViewerVote.Zero] flows straight through to `SnapLikeDecision.Cast`, so
	// every route to it has to be a positive statement that the chain holds a
	// vote worth nothing. A field this parser simply could not find is not that
	// statement — the missing half may have described a real vote, and Liking
	// over it would overwrite exactly what the read was supposed to protect.
	//
	// Verified against 197 authoritative rows from 24 live comments on
	// 2026-09-19: every single one carried both `percent` and `rshares` as JSON
	// integers, so a row missing either is not a shape any real node produces.

	@Test
	fun `a viewer row missing percent with zero rshares refuses`() {
		val vote = HiveVotes.fromActiveVotes(votes(voteRow(percent = null, rshares = 0)), "alice")
		assertUnreadable(vote)
	}

	@Test
	fun `a viewer row missing percent with positive rshares refuses`() {
		val vote = HiveVotes.fromActiveVotes(
			votes(voteRow(percent = null, rshares = 120728973539L)),
			"alice",
		)
		assertUnreadable(vote)
	}

	@Test
	fun `a viewer row missing rshares with zero percent refuses`() {
		val vote = HiveVotes.fromActiveVotes(votes(voteRow(percent = 0, rshares = null)), "alice")
		assertUnreadable(vote)
	}

	@Test
	fun `a viewer row missing rshares with positive percent refuses`() {
		val vote = HiveVotes.fromActiveVotes(votes(voteRow(percent = 2500, rshares = null)), "alice")
		assertUnreadable(vote)
	}

	@Test
	fun `a viewer row missing rshares with negative percent refuses`() {
		val vote = HiveVotes.fromActiveVotes(
			votes(voteRow(percent = -2500, rshares = null)),
			"alice",
		)
		assertUnreadable(vote)
	}

	@Test
	fun `a viewer row missing both fields refuses`() {
		assertUnreadable(
			HiveVotes.fromActiveVotes(votes(voteRow(percent = null, rshares = null)), "alice"),
		)
	}

	@Test
	fun `an explicit null percent refuses`() {
		val row = voteRow().put("percent", JSONObject.NULL)
		assertUnreadable(HiveVotes.fromActiveVotes(votes(row), "alice"))
	}

	@Test
	fun `an explicit null rshares refuses`() {
		val row = voteRow().put("rshares", JSONObject.NULL)
		assertUnreadable(HiveVotes.fromActiveVotes(votes(row), "alice"))
	}

	// ── a complete viewer row classifies by percent ────────────────────

	@Test
	fun `a complete zero row is Zero`() {
		assertEquals(
			ViewerVote.Zero,
			HiveVotes.fromActiveVotes(votes(voteRow(percent = 0, rshares = 0)), "alice"),
		)
	}

	/**
	 * Zero rshares is **not** a reason to doubt a vote.
	 *
	 * The live sample held 23 rows with a positive percent whose rshares had
	 * rounded to zero — `shaktimaaan` at `percent: 900, rshares: 0` among them.
	 * A small vote from a drained or low-stake account is ordinary, and refusing
	 * it would refuse a real vote and then overwrite it.
	 */
	@Test
	fun `a positive percent with zero rshares is Positive`() {
		assertEquals(
			ViewerVote.Positive(900, 0L),
			HiveVotes.fromActiveVotes(votes(voteRow(percent = 900, rshares = 0)), "alice"),
		)
	}

	/** The same in the other direction; the live sample held two of these too. */
	@Test
	fun `a negative percent with zero rshares is Negative`() {
		assertEquals(
			ViewerVote.Negative(-900, 0L),
			HiveVotes.fromActiveVotes(votes(voteRow(percent = -900, rshares = 0)), "alice"),
		)
	}

	/** The real downvote, as the chain wrote it: `percent: -25, rshares: -1775976786`. */
	@Test
	fun `the live downvote shape reads as Negative`() {
		assertEquals(
			ViewerVote.Negative(-25, -1_775_976_786L),
			HiveVotes.fromActiveVotes(
				votes(voteRow(percent = -25, rshares = -1_775_976_786L)),
				"alice",
			),
		)
	}

	// ── contradictory signs ────────────────────────────────────────────

	/**
	 * rshares is the percent scaled by voting power and stake, so the two cannot
	 * point opposite ways. Checked rather than assumed: none of the 197 live
	 * rows sampled on 2026-09-19 had strictly opposite signs.
	 */
	@Test
	fun `a percent and rshares pointing opposite ways refuses`() {
		assertUnreadable(
			HiveVotes.fromActiveVotes(votes(voteRow(percent = 2500, rshares = -5L)), "alice"),
		)
		assertUnreadable(
			HiveVotes.fromActiveVotes(votes(voteRow(percent = -2500, rshares = 5L)), "alice"),
		)
	}

	/** Zero on either side is never a contradiction — see the live sample. */
	@Test
	fun `zero on either side is not treated as a contradiction`() {
		assertTrue(
			HiveVotes.fromActiveVotes(votes(voteRow(percent = 900, rshares = 0)), "alice")
				is ViewerVote.Positive,
		)
		assertTrue(
			HiveVotes.fromActiveVotes(votes(voteRow(percent = -900, rshares = 0)), "alice")
				is ViewerVote.Negative,
		)
		assertEquals(
			ViewerVote.Zero,
			HiveVotes.fromActiveVotes(votes(voteRow(percent = 0, rshares = 0)), "alice"),
		)
	}

	// ── the invariant, swept ───────────────────────────────────────────

	/**
	 * **No malformed viewer row may ever read as an absence.**
	 *
	 * [ViewerVote.None] is the one answer that authorizes a write, so this is
	 * the single property the whole parser exists to hold. Swept across every
	 * way a viewer row can be wrong, rather than asserted case by case, so a
	 * new kind of damage cannot quietly land on the eligible side.
	 */
	@Test
	fun `no malformed viewer row can ever produce None`() {
		val damaged: List<Pair<String, JSONObject>> = listOf(
			"missing percent" to voteRow(percent = null),
			"missing rshares" to voteRow(rshares = null),
			"missing both" to voteRow(percent = null, rshares = null),
			"null percent" to voteRow().put("percent", JSONObject.NULL),
			"null rshares" to voteRow().put("rshares", JSONObject.NULL),
			"boolean percent" to voteRow().put("percent", true),
			"object rshares" to voteRow().put("rshares", JSONObject()),
			"fractional percent" to voteRow(percent = 0.9),
			"fractional rshares" to voteRow(rshares = 0.5),
			"percent out of int range" to voteRow(percent = 9_000_000_000L),
			"non-integral string" to voteRow(rshares = "2500.0"),
			"empty string" to voteRow(rshares = ""),
			"contradictory signs" to voteRow(percent = 2500, rshares = -5L),
		)
		damaged.forEach { (why, row) ->
			val vote = HiveVotes.fromActiveVotes(votes(row), "alice")
			assertTrue("$why must refuse, got $vote", vote is ViewerVote.Unreadable)
			assertTrue("$why must never read as an absence", vote !== ViewerVote.None)
			assertTrue("$why must never read as eligible-zero", vote !== ViewerVote.Zero)
		}
	}

	/**
	 * And the same sweep through a **fresh read**, which is the thing that
	 * actually authorizes.
	 *
	 * A node that answers with a damaged viewer row must never produce
	 * `Fresh(None)` or `Fresh(Zero)` — either would flow straight to
	 * `SnapLikeDecision.Cast`. With every node answering that way the read is
	 * [HiveVoteRead.Unavailable], which authorizes nothing at all.
	 */
	@Test
	fun `a damaged viewer row can never reach an eligible fresh read`() {
		listOf(
			voteRow(percent = null),
			voteRow(rshares = null),
			voteRow().put("percent", JSONObject.NULL),
			voteRow(percent = 0.9),
			voteRow(percent = 2500, rshares = -5L),
		).forEach { row ->
			val read = rpc().findViewerVoteAcross(
				voter = "alice",
				lagOf = { 3L },
				votesFrom = { votes(row) },
			)
			assertTrue("got $read", read is HiveVoteRead.Unavailable)
		}
	}

	/**
	 * A stranger's broken numbers must not stop this user Liking anything.
	 *
	 * Their *identity* still has to be readable — an unattributable row might be
	 * the viewer's — but once a row is provably somebody else's, whatever is
	 * wrong with its `percent` or `rshares` is none of this decision's business.
	 */
	@Test
	fun `another voter's malformed numbers do not block the viewer lookup`() {
		val list = votes(
			voteRow(voter = "carol").put("percent", JSONObject.NULL),
			voteRow(voter = "dave", percent = null, rshares = null),
			voteRow(voter = "erin").put("rshares", "not a number"),
			voteRow(voter = "alice", percent = 2500, rshares = 5L),
		)
		assertEquals(
			ViewerVote.Positive(2500, 5L),
			HiveVotes.fromActiveVotes(list, "alice"),
		)
	}

	/** And an absence is still reachable past them, so Liking still works. */
	@Test
	fun `another voter's malformed numbers still leave a clean absence readable`() {
		val list = votes(
			voteRow(voter = "carol").put("percent", JSONObject.NULL),
			voteRow(voter = "dave", rshares = null),
		)
		assertEquals(ViewerVote.None, HiveVotes.fromActiveVotes(list, "alice"))
	}

	private fun assertUnreadable(vote: ViewerVote) {
		assertTrue("expected Unreadable, got $vote", vote is ViewerVote.Unreadable)
		assertTrue("must never be an absence", vote !== ViewerVote.None)
		assertTrue("must never be eligible-zero", vote !== ViewerVote.Zero)
	}


	// ── authoritative voter identity ───────────────────────────────────
	//
	// `{"voter": "", "percent": 2500, "rshares": 5}` is the shape that made the
	// point. `""` is a perfectly good `String`, so a type check waved it
	// through; it equals no real account name, so it was skipped; and the array
	// it sat in then answered "you have not voted here" — which authorizes a
	// Cast straight over whatever that row really was.
	//
	// Judged with [HiveAccountName], the same validator the rest of the app uses
	// to decide what an account is. Verified harmless against real traffic: all
	// 558 distinct voter names across the live sample on 2026-09-19 pass it.

	@Test
	fun `an empty voter refuses instead of being skipped`() {
		val row = voteRow().put("voter", "")
		assertUnreadable(HiveVotes.fromActiveVotes(votes(row), "alice"))
	}

	@Test
	fun `a whitespace voter refuses`() {
		val row = voteRow().put("voter", "   ")
		assertUnreadable(HiveVotes.fromActiveVotes(votes(row), "alice"))
	}

	@Test
	fun `a voter that is not a valid Hive account name refuses`() {
		listOf(
			"Alice",        // uppercase is not a Hive account name
			"a..b",         // empty segment
			".alice",       // leading dot
			"alice.",       // trailing dot
			"al/ice",       // a slash, which would also poison a URL or a key
			"ab",           // too short
			"a b",          // a space
			"alice\n",      // a newline
			"x".repeat(17), // too long
		).forEach { bad ->
			val row = voteRow().put("voter", bad)
			assertUnreadable(HiveVotes.fromActiveVotes(votes(row), "alice"))
		}
	}

	@Test
	fun `a non-string voter refuses`() {
		listOf(42, true, JSONObject(), JSONArray()).forEach { bad ->
			val row = voteRow().put("voter", bad)
			assertUnreadable(HiveVotes.fromActiveVotes(votes(row), "alice"))
		}
	}

	@Test
	fun `an explicit null voter refuses`() {
		val row = voteRow().put("voter", JSONObject.NULL)
		assertUnreadable(HiveVotes.fromActiveVotes(votes(row), "alice"))
	}

	/**
	 * The heart of it: the bad row belongs to "someone else", and it still
	 * refuses.
	 *
	 * That is the whole reason this cannot be a skip. An identity nobody can
	 * read is not evidence that it was somebody else's — it is evidence that the
	 * response is damaged, and a damaged response cannot prove this viewer has
	 * no vote.
	 */
	@Test
	fun `a malformed other-voter identity refuses rather than reporting no vote`() {
		val list = votes(
			voteRow(voter = "carol"),
			voteRow().put("voter", ""),
			voteRow(voter = "dave"),
		)
		val vote = HiveVotes.fromActiveVotes(list, "alice")
		assertUnreadable(vote)
	}

	@Test
	fun `one invalid row among otherwise valid non-viewer rows refuses`() {
		val list = votes(
			voteRow(voter = "carol"),
			voteRow(voter = "dave"),
			voteRow().put("voter", "Bob"),
			voteRow(voter = "erin"),
		)
		assertUnreadable(HiveVotes.fromActiveVotes(list, "alice"))
	}

	// ── and the absence contract still works ───────────────────────────

	@Test
	fun `all rows valid and none the viewer is a clean absence`() {
		val list = votes(
			voteRow(voter = "carol"),
			voteRow(voter = "dave"),
			voteRow(voter = "erin"),
		)
		assertEquals(ViewerVote.None, HiveVotes.fromActiveVotes(list, "alice"))
	}

	@Test
	fun `valid unrelated voters beside a valid viewer positive read as Positive`() {
		val list = votes(
			voteRow(voter = "carol"),
			voteRow(voter = "alice", percent = 2500, rshares = 5L),
			voteRow(voter = "dave"),
		)
		assertEquals(
			ViewerVote.Positive(2500, 5L),
			HiveVotes.fromActiveVotes(list, "alice"),
		)
	}

	@Test
	fun `valid unrelated voters beside a valid viewer zero read as Zero`() {
		val list = votes(
			voteRow(voter = "carol"),
			voteRow(voter = "alice", percent = 0, rshares = 0),
			voteRow(voter = "dave"),
		)
		assertEquals(ViewerVote.Zero, HiveVotes.fromActiveVotes(list, "alice"))
	}

	/** Dotted account names are legal on Hive and must keep working. */
	@Test
	fun `a dotted account name is a valid voter`() {
		val list = votes(voteRow(voter = "hivesuite.app"), voteRow(voter = "peak.snaps"))
		assertEquals(ViewerVote.None, HiveVotes.fromActiveVotes(list, "alice"))
	}

	// ── the invariant, through the read that authorizes ────────────────

	/**
	 * An invalid identity can never reach `Fresh(None)`.
	 *
	 * `Fresh(None)` is what `SnapLikeDecisions` turns into `Cast`, so this is
	 * the property the whole fix exists to hold. Asserted through
	 * [HiveRpc.findViewerVoteAcross] rather than the parser alone, because that
	 * is the path a real vote is authorized by.
	 */
	@Test
	fun `an invalid identity can never reach a fresh absence`() {
		listOf(
			voteRow().put("voter", ""),
			voteRow().put("voter", "   "),
			voteRow().put("voter", "Alice"),
			voteRow().put("voter", "a..b"),
			voteRow().put("voter", "al/ice"),
			voteRow().put("voter", 42),
			voteRow().put("voter", JSONObject.NULL),
		).forEach { row ->
			val read = rpc().findViewerVoteAcross(
				voter = "alice",
				lagOf = { 3L },
				votesFrom = { votes(row) },
			)
			assertTrue("got $read", read is HiveVoteRead.Unavailable)
		}
	}

	/** A node answering with an unusable identity hands over to the next fresh node. */
	@Test
	fun `a node with an invalid authoritative identity hands over to the next`() {
		val calls = Calls()
		val read = rpc().findViewerVoteAcross(
			voter = "alice",
			lagOf = { calls.lag(it); 3L },
			votesFrom = { node ->
				calls.fetch(node)
				if (node == nodes[0]) votes(voteRow().put("voter", "")) else votes(voteRow())
			},
		)

		assertEquals(
			listOf(
				"lag" to nodes[0],
				"fetch" to nodes[0],
				"lag" to nodes[1],
				"fetch" to nodes[1],
			),
			calls.log,
		)
		assertEquals(nodes[1], (read as HiveVoteRead.Fresh).node)
		assertTrue(read.vote is ViewerVote.Positive)
	}

	/** And every node doing so is Unavailable, never an absence. */
	@Test
	fun `every node with an invalid authoritative identity refuses`() {
		val read = rpc().findViewerVoteAcross(
			voter = "alice",
			lagOf = { 3L },
			votesFrom = { votes(voteRow().put("voter", "")) },
		)
		assertTrue("got $read", read is HiveVoteRead.Unavailable)
	}

	// ── display stays legible, and still cannot mis-attribute ──────────

	/**
	 * Display authorizes nothing, so one odd row does not blank a whole
	 * conversation — but it can never be read as this viewer's Like either.
	 */
	@Test
	fun `a bridge row with an invalid identity is passed over rather than matched`() {
		val comment = JSONObject().put(
			"active_votes",
			JSONArray()
				.put(JSONObject().put("voter", "").put("rshares", 5L))
				.put(JSONObject().put("voter", "Alice").put("rshares", 5L)),
		)
		assertEquals(ViewerVote.None, HiveVotes.inComment(comment, "alice"))
	}

	@Test
	fun `a bridge comment still finds a validly named viewer`() {
		val comment = JSONObject().put(
			"active_votes",
			JSONArray()
				.put(JSONObject().put("voter", "").put("rshares", 5L))
				.put(JSONObject().put("voter", "alice").put("rshares", 7L)),
		)
		assertEquals(ViewerVote.Positive(null, 7L), HiveVotes.inComment(comment, "alice"))
	}

	/** The call, pinned. `find_votes` is obsolete and must not come back. */
	@Test
	fun `the safety read uses the current get_active_votes call`() {
		val src = java.io.File("src/main/kotlin/com/rustedwax/hive/HiveRpc.kt").readText()
		assertTrue(
			"the safety read must call condenser_api.get_active_votes",
			src.contains("\"condenser_api.get_active_votes\""),
		)
		assertTrue(
			"database_api.find_votes is obsolete and must not be used",
			!src.contains("database_api.find_votes"),
		)
		// It must not have been quietly rebuilt on the unchecked read path.
		val body = src.substringAfter("fun findViewerVote(").substringBefore("fun broadcast(")
		assertNull(
			"findViewerVote must not go through callAny/call",
			Regex("""\bcall(Any|Object)?\(""").find(body),
		)
	}

	// ── the social count: positives only, and never a gate ────────────
	//
	// A second, separate question from everything above. The viewer read
	// decides whether an irreversible vote may be cast and fails closed on
	// anything doubtful; this one decides a number next to a heart, so a row it
	// cannot read is skipped rather than fatal. Both rules are deliberate, and
	// they are opposite for a reason.

	private fun comment(vararg rows: JSONObject) =
		JSONObject().put("active_votes", votes(*rows))

	@Test
	fun `five positive votes count five`() {
		val c = comment(
			voteRow(voter = "alice"),
			voteRow(voter = "bob"),
			voteRow(voter = "carol"),
			voteRow(voter = "dave"),
			voteRow(voter = "erin"),
		)

		assertEquals(5, HiveVotes.positiveCount(c))
	}

	/** Downvotes and weightless votes are not Likes. */
	@Test
	fun `only positive rows are counted`() {
		val c = comment(
			voteRow(voter = "alice", percent = 2500, rshares = 120728973539L),
			voteRow(voter = "bob", percent = -2500, rshares = -1775976786L),
			voteRow(voter = "carol", percent = 0, rshares = 0L),
			voteRow(voter = "dave", percent = 10000, rshares = 5L),
		)

		assertEquals("two positives among four votes", 2, HiveVotes.positiveCount(c))
	}

	/**
	 * A `bridge` row carries no percent at all, so the sign of `rshares`
	 * decides — the same precedence the viewer's own display row uses.
	 */
	@Test
	fun `a bridge row with no percent counts by its rshares`() {
		val c = comment(
			voteRow(voter = "alice", percent = null, rshares = 5_000L),
			voteRow(voter = "bob", percent = null, rshares = -5_000L),
			// A real vote whose weight rounded away. Not a Like by this rule,
			// and deliberately so: with no percent there is nothing left that
			// says which way it pointed.
			voteRow(voter = "carol", percent = null, rshares = 0L),
		)

		assertEquals(1, HiveVotes.positiveCount(c))
	}

	/**
	 * A row this cannot read lowers the number; it never fails the read.
	 *
	 * The opposite of [viewerVote]'s rule, and the difference is what is at
	 * stake: there an unreadable row might be the viewer's own vote about to be
	 * overwritten, here it is one missing unit in a label.
	 */
	@Test
	fun `unreadable rows are skipped rather than fatal`() {
		val c = comment(
			voteRow(voter = "alice"),
			voteRow(voter = "bob", rshares = 12.5),
			voteRow(voter = "carol", rshares = null),
			voteRow(voter = "Dave"),
			voteRow(voter = ""),
			voteRow(voter = "erin"),
		)
		// `voter: 42` and a row that is not an object at all, in the same list.
		val messy = JSONObject().put(
			"active_votes",
			votes(voteRow(voter = "alice")).put(42).put(JSONObject().put("voter", 7)),
		)

		assertEquals("only the two readable positives", 2, HiveVotes.positiveCount(c))
		assertEquals(1, HiveVotes.positiveCount(messy))
	}

	@Test
	fun `a comment with no readable votes counts zero`() {
		assertEquals(0, HiveVotes.positiveCount(JSONObject()))
		assertEquals(0, HiveVotes.positiveCount(JSONObject().put("active_votes", JSONArray())))
		assertEquals(0, HiveVotes.positiveCount(JSONObject().put("active_votes", "nonsense")))
		assertEquals(0, HiveVotes.positiveCount(JSONObject().put("active_votes", JSONObject.NULL)))
	}

	/**
	 * The count cannot reach the authorization read, in either direction.
	 *
	 * One list, two verdicts, and the contrast is the point. A row naming no
	 * valid account is skipped by the count — the number is one low and the
	 * conversation still draws — while the *viewer's* authoritative read over
	 * the same list refuses outright, because that row might be a damaged copy
	 * of the viewer's own and an absence measured around it was never proven.
	 *
	 * And in the other direction: a healthy count is no evidence at all about
	 * whether this viewer voted.
	 */
	@Test
	fun `counting changes nothing about what the viewer read decides`() {
		val rows = votes(
			voteRow(voter = "alice"),
			voteRow(voter = "bob"),
			// A `String`, and not an account name. Unattributable.
			voteRow(voter = "Carol"),
		)

		assertEquals("two attributable positives", 2, HiveVotes.positiveCount(JSONObject().put("active_votes", rows)))
		assertUnreadable(HiveVotes.fromActiveVotes(rows, "dave"))
		assertEquals(
			"and a count of two says nothing about dave",
			ViewerVote.None,
			HiveVotes.fromActiveVotes(votes(voteRow(voter = "alice"), voteRow(voter = "bob")), "dave"),
		)
	}
}
