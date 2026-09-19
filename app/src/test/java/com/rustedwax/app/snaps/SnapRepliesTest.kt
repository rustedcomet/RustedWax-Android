package com.rustedwax.app.snaps

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The untrusted boundary: what the chain sends, and what is allowed past it.
 *
 * Every fixture here is a response RustedWax has no control over. The assertion
 * throughout is the same one — a reply that cannot be vouched for produces
 * `null`, never an exception and never a half-built object.
 */
class SnapRepliesTest {

	private fun chainReply(
		author: String = "bob",
		permlink: String = "re-20260917t120000000z",
		parentAuthor: String = "alice",
		parentPermlink: String = "rustedwax-snap-1000-aaaaaa",
		body: String = "nice one",
		created: String = "2026-09-17T12:36:00",
	): JSONObject = JSONObject()
		.put("author", author)
		.put("permlink", permlink)
		.put("parent_author", parentAuthor)
		.put("parent_permlink", parentPermlink)
		.put("body", body)
		.put("created", created)

	/**
	 * These tests are about identity and body parsing, not about votes, so they
	 * parse with nobody signed in. The vote state a viewer would carry is
	 * covered separately — see `HiveVoteReadTest` for the strict rules and
	 * `a reply carries the signed-in viewer's own vote` below for the wiring.
	 */
	private fun parse(o: JSONObject) = SnapReplies.parse(o, viewer = null)

	private fun parseAll(objects: List<JSONObject>) =
		SnapReplies.parseAll(objects, viewer = null)

	@Test
	fun `a well formed reply parses whole`() {
		val reply = parse(chainReply())!!

		assertEquals("bob/re-20260917t120000000z", reply.contentId)
		assertEquals("alice/rustedwax-snap-1000-aaaaaa", reply.parentId)
		assertEquals("nice one", reply.body)
		assertEquals(1_789_648_560L, reply.createdAtEpochSec)
	}

	@Test
	fun `an empty object is refused rather than thrown on`() {
		assertNull(parse(JSONObject()))
	}

	@Test
	fun `every missing field is a refusal, not a crash`() {
		listOf("author", "permlink", "parent_author", "parent_permlink").forEach { field ->
			val o = chainReply()
			o.remove(field)
			assertNull("missing $field must refuse", parse(o))
		}
	}

	/**
	 * `optString` coerces, and the coercion is the bug.
	 *
	 * A JSON number `42` comes back from `optString` as the string `"42"` — and
	 * `"42"` is a perfectly legal-looking permlink, so a wrong-typed response
	 * would have produced a reply target, and then a signed `parent_permlink`,
	 * out of a value that was never a string at all.
	 */
	@Test
	fun `a wrong-typed identity field is refused rather than coerced`() {
		listOf("author", "permlink", "parent_author", "parent_permlink").forEach { field ->
			assertNull("$field as a number", parse(chainReply().put(field, 42)))
			assertNull("$field as a boolean", parse(chainReply().put(field, true)))
			assertNull("$field as null", parse(chainReply().put(field, JSONObject.NULL)))
			assertNull(
				"$field as an object",
				parse(chainReply().put(field, JSONObject().put("a", 1))),
			)
		}
	}

	/** In particular: a numeric permlink must not become the permlink "42". */
	@Test
	fun `a numeric permlink does not become a usable permlink`() {
		val parsed = parse(chainReply().put("permlink", 42))

		assertNull(parsed)
	}

	/** A body is not an identity, so a wrong-typed one is treated as absent. */
	@Test
	fun `a wrong-typed body is read as empty rather than refusing the reply`() {
		val reply = parse(chainReply().put("body", 42))!!

		assertEquals("", reply.body)
	}

	@Test
	fun `a wrong-typed created field leaves the age unknown`() {
		assertNull(parse(chainReply().put("created", 1_789_648_560))!!.createdAtEpochSec)
	}

	/**
	 * These strings each become part of a URL path, a preference key, or the
	 * `parent_permlink` of a signed transaction. None of them may be a permlink.
	 */
	@Test
	fun `a permlink with structure in it is refused`() {
		listOf(
			"../../etc/passwd",
			"a/b",
			"a b",
			"a|b",
			"a\nb",
			"UPPER",
			"-leading-hyphen",
			"",
		).forEach {
			assertFalse("'$it' must not be a permlink", SnapReplies.isPermlink(it))
			assertNull(parse(chainReply(permlink = it)))
		}
	}

	@Test
	fun `an author that is not a Hive account name is refused`() {
		listOf("Alice", "al", "a..b", ".alice", "alice.", "alice/bob", "").forEach {
			assertNull("'$it' must not be an author", parse(chainReply(author = it)))
		}
	}

	/** A deleted comment comes back with its body gone. It is still a comment. */
	@Test
	fun `a body that is missing becomes empty rather than refusing the reply`() {
		val o = chainReply()
		o.remove("body")

		val reply = parse(o)!!

		assertEquals("", reply.body)
	}

	/**
	 * An unreadable timestamp is carried as null. The alternative — substituting
	 * a time — would print an age nobody can check.
	 */
	@Test
	fun `an unreadable created field leaves the age unknown`() {
		listOf("", "yesterday", "2026-09-17T12:36:00Z", "2026-02-30T00:00:00").forEach {
			assertNull(parse(chainReply(created = it))!!.createdAtEpochSec)
		}
	}

	@Test
	fun `a missing created field leaves the age unknown`() {
		val o = chainReply()
		o.remove("created")

		assertNull(parse(o)!!.createdAtEpochSec)
	}

	/**
	 * The limit RustedWax enforces binds what RustedWax writes. A reply from any
	 * other Hive client arrives complete and stays complete.
	 */
	@Test
	fun `a long external reply survives the boundary intact`() {
		val long = "e\u0301\uD83C\uDFB5 ".repeat(2_000)

		val reply = parse(chainReply(body = long))!!

		assertEquals(long, reply.body)
		assertTrue(reply.body.length > 5_000)
	}

	/**
	 * There is **no** length cap on a body, at any size.
	 *
	 * An earlier revision cut at 64 KiB. That is a silent content limit applied
	 * to somebody else's words, and on screen it is indistinguishable from the
	 * comment simply ending there.
	 */
	@Test
	fun `a body past 64 KiB is preserved whole`() {
		val huge = "x".repeat(70_000)

		val reply = parse(chainReply(body = huge))!!

		assertEquals(70_000, reply.body.length)
		assertEquals(huge, reply.body)
	}

	@Test
	fun `a body past 64 KiB reaches the thread whole`() {
		val huge = "long ".repeat(20_000)
		val rootId = "alice/rustedwax-snap-1000-aaaaaa"

		val thread = SnapThreadBuilder.build(
			rootId,
			parseAll(listOf(chainReply(body = huge))),
		)

		assertEquals(1, thread.total)
		assertEquals(huge, thread.rows.single().reply.body)
	}

	/** Sanitising still removes what cannot be drawn, at any length. */
	@Test
	fun `an enormous body is sanitised without being shortened`() {
		val huge = "a\u0000b".repeat(30_000)

		val cleaned = SnapReplyText.sanitize(huge)

		assertEquals("ab".repeat(30_000), cleaned)
	}

	@Test
	fun `markup stays as characters`() {
		val hostile = "<script>alert(1)</script> <img src=x onerror=y> [a](javascript:b)"

		assertEquals(hostile, SnapReplyText.sanitize(hostile))
	}

	@Test
	fun `control characters go and real text stays`() {
		val raw = "line one\r\nline two\u0000\u0007\ttabbed\u009C"

		assertEquals("line one\nline two\ttabbed", SnapReplyText.sanitize(raw))
	}

	@Test
	fun `bidirectional overrides are removed and ordinary Unicode is not`() {
		assertEquals("evil.moctxet", SnapReplyText.sanitize("evil\u202E.moctxet"))
		// Arabic, Hebrew, an emoji with a skin tone, and a combining accent all
		// survive untouched.
		val real = "\u0645\u0631\u062D\u0628\u0627 \u05E9\u05DC\u05D5\u05DD " +
			"\uD83D\uDC4B\uD83C\uDFFD e\u0301"
		assertEquals(real, SnapReplyText.sanitize(real))
	}

	@Test
	fun `null and empty bodies sanitize to empty`() {
		assertEquals("", SnapReplyText.sanitize(null))
		assertEquals("", SnapReplyText.sanitize(""))
	}

	@Test
	fun `parseAll keeps the good and drops the bad without failing`() {
		val parsed = parseAll(
			listOf(
				chainReply(author = "bob"),
				JSONObject(),
				chainReply(author = "NOPE"),
				chainReply(author = "carol", permlink = "re-two"),
			),
		)

		assertEquals(listOf("bob", "carol"), parsed.map { it.author })
	}

	// ── the viewer's own vote, for the heart ───────────────────────────

	/**
	 * `bridge.get_discussion` already carries `active_votes`, so the heart costs
	 * no extra request. Only the signed-in viewer's own row is kept — the rest
	 * of the list is other people's business and is unbounded in size.
	 */
	@Test
	fun `a reply carries the signed-in viewer's own vote`() {
		val o = chainReply().put(
			"active_votes",
			org.json.JSONArray()
				.put(JSONObject().put("voter", "carol").put("rshares", 99L))
				.put(JSONObject().put("voter", "alice").put("rshares", 5_000L)),
		)
		val reply = SnapReplies.parse(o, viewer = "alice")!!
		assertEquals(com.rustedwax.hive.ViewerVote.Positive(null, 5_000L), reply.viewerVote)
	}

	@Test
	fun `a reply nobody voted on carries no vote for the viewer`() {
		val o = chainReply().put("active_votes", org.json.JSONArray())
		assertEquals(
			com.rustedwax.hive.ViewerVote.None,
			SnapReplies.parse(o, viewer = "alice")!!.viewerVote,
		)
	}

	/**
	 * Nobody signed in is not the same fact as "this account has not voted", and
	 * only one of the two may ever authorize anything — so it reads as
	 * unreadable, which draws an inert heart rather than an inviting one.
	 */
	@Test
	fun `with nobody signed in the vote state is unreadable rather than absent`() {
		val o = chainReply().put("active_votes", org.json.JSONArray())
		listOf(null, "", "   ").forEach { viewer ->
			assertTrue(
				"viewer '$viewer' must not read as an absence",
				SnapReplies.parse(o, viewer = viewer)!!.viewerVote
					is com.rustedwax.hive.ViewerVote.Unreadable,
			)
		}
	}

	/** A response with no vote list at all is not a response saying nobody voted. */
	@Test
	fun `a reply with no active_votes carries an unreadable vote`() {
		assertTrue(
			SnapReplies.parse(chainReply(), viewer = "alice")!!.viewerVote
				is com.rustedwax.hive.ViewerVote.Unreadable,
		)
	}


	/**
	 * A reply whose vote list holds an unattributable row still renders, and
	 * still cannot show that row as the viewer's Like.
	 *
	 * `bridge` is the display path: it authorizes nothing, so one odd row does
	 * not blank a conversation. What it must never do is *match* — a heart drawn
	 * from `{"voter": ""}` would claim a Like this account never gave.
	 */
	@Test
	fun `an unattributable vote row never renders as the viewer's Like`() {
		val o = chainReply().put(
			"active_votes",
			org.json.JSONArray()
				.put(JSONObject().put("voter", "").put("rshares", 5_000L))
				.put(JSONObject().put("voter", "Alice").put("rshares", 5_000L)),
		)
		assertEquals(
			com.rustedwax.hive.ViewerVote.None,
			SnapReplies.parse(o, viewer = "alice")!!.viewerVote,
		)
	}

	// ── the social count ──────────────────────────────────────────────

	private fun vote(voter: String, rshares: Long) =
		JSONObject().put("voter", voter).put("rshares", rshares)

	@Test
	fun `a reply carries how many people liked it`() {
		val o = chainReply().put(
			"active_votes",
			org.json.JSONArray()
				.put(vote("carol", 5_000L))
				.put(vote("dave", 9_000L))
				.put(vote("erin", 1L))
				.put(vote("frank", -9_000L)),
		)

		assertEquals(3, parse(o)!!.positiveLikeCount)
	}

	@Test
	fun `a reply nobody voted on counts zero`() {
		assertEquals(0, parse(chainReply())!!.positiveLikeCount)
		assertEquals(
			0,
			parse(chainReply().put("active_votes", org.json.JSONArray()))!!.positiveLikeCount,
		)
	}

	/** Counted whether or not anybody is signed in: it is not about the viewer. */
	@Test
	fun `the count does not depend on a signed-in viewer`() {
		val o = chainReply().put(
			"active_votes",
			org.json.JSONArray().put(vote("carol", 5_000L)).put(vote("alice", 7_000L)),
		)

		assertEquals(2, SnapReplies.parse(o, viewer = null)!!.positiveLikeCount)
		assertEquals(2, SnapReplies.parse(o, viewer = "alice")!!.positiveLikeCount)
		assertEquals(2, SnapReplies.parse(o, viewer = "zoe")!!.positiveLikeCount)
	}

	/**
	 * The voter rows are reduced to two facts and then dropped.
	 *
	 * Asserted structurally because the cost is structural: a conversation is
	 * held in memory whole, so a [SnapReply] that kept its voter list would
	 * make opening a thread scale with other people's voting rather than with
	 * the size of the conversation. A field able to hold many voters is the
	 * thing that must not exist.
	 */
	@Test
	fun `no voter list is retained on a reply`() {
		val collections = SnapReply::class.java.declaredFields.filter {
			Collection::class.java.isAssignableFrom(it.type) ||
				Map::class.java.isAssignableFrom(it.type) ||
				it.type.isArray
		}

		assertEquals(emptyList<Any>(), collections)
		assertEquals(
			"and the vote data it does keep is one enum-ish value and one Int",
			listOf("viewerVote", "positiveLikeCount"),
			SnapReply::class.java.declaredFields
				.map { it.name }
				.filter { it == "viewerVote" || it == "positiveLikeCount" },
		)
	}

	/** The root's own count survives the builder dropping the root row. */
	@Test
	fun `the root Snap's like count reaches the thread`() {
		val rootId = "alice/rustedwax-snap-1000-aaaaaa"
		val root = chainReply(
			author = "alice",
			permlink = "rustedwax-snap-1000-aaaaaa",
			parentAuthor = "peak.snaps",
			parentPermlink = "snap-container-1789648560",
		).put(
			"active_votes",
			org.json.JSONArray().put(vote("carol", 5_000L)).put(vote("dave", 9_000L)),
		)
		val reply = chainReply().put("active_votes", org.json.JSONArray().put(vote("erin", 1L)))

		val thread = SnapThreadBuilder.build(rootId, parseAll(listOf(root, reply)))

		assertEquals("the root is not a reply to itself", 1, thread.rows.size)
		assertEquals(2, thread.rootLikeCount)
		assertEquals(1, thread.rows.single().reply.positiveLikeCount)
	}

	@Test
	fun `a thread with no root row in the response counts zero for it`() {
		val thread = SnapThreadBuilder.build(
			"alice/rustedwax-snap-1000-aaaaaa",
			parseAll(listOf(chainReply())),
		)

		assertEquals(0, thread.rootLikeCount)
	}
}
