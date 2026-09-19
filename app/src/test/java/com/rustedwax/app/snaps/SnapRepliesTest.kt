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

	@Test
	fun `a well formed reply parses whole`() {
		val reply = SnapReplies.parse(chainReply())!!

		assertEquals("bob/re-20260917t120000000z", reply.contentId)
		assertEquals("alice/rustedwax-snap-1000-aaaaaa", reply.parentId)
		assertEquals("nice one", reply.body)
		assertEquals(1_789_648_560L, reply.createdAtEpochSec)
	}

	@Test
	fun `an empty object is refused rather than thrown on`() {
		assertNull(SnapReplies.parse(JSONObject()))
	}

	@Test
	fun `every missing field is a refusal, not a crash`() {
		listOf("author", "permlink", "parent_author", "parent_permlink").forEach { field ->
			val o = chainReply()
			o.remove(field)
			assertNull("missing $field must refuse", SnapReplies.parse(o))
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
			assertNull("$field as a number", SnapReplies.parse(chainReply().put(field, 42)))
			assertNull("$field as a boolean", SnapReplies.parse(chainReply().put(field, true)))
			assertNull("$field as null", SnapReplies.parse(chainReply().put(field, JSONObject.NULL)))
			assertNull(
				"$field as an object",
				SnapReplies.parse(chainReply().put(field, JSONObject().put("a", 1))),
			)
		}
	}

	/** In particular: a numeric permlink must not become the permlink "42". */
	@Test
	fun `a numeric permlink does not become a usable permlink`() {
		val parsed = SnapReplies.parse(chainReply().put("permlink", 42))

		assertNull(parsed)
	}

	/** A body is not an identity, so a wrong-typed one is treated as absent. */
	@Test
	fun `a wrong-typed body is read as empty rather than refusing the reply`() {
		val reply = SnapReplies.parse(chainReply().put("body", 42))!!

		assertEquals("", reply.body)
	}

	@Test
	fun `a wrong-typed created field leaves the age unknown`() {
		assertNull(SnapReplies.parse(chainReply().put("created", 1_789_648_560))!!.createdAtEpochSec)
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
			assertNull(SnapReplies.parse(chainReply(permlink = it)))
		}
	}

	@Test
	fun `an author that is not a Hive account name is refused`() {
		listOf("Alice", "al", "a..b", ".alice", "alice.", "alice/bob", "").forEach {
			assertNull("'$it' must not be an author", SnapReplies.parse(chainReply(author = it)))
		}
	}

	/** A deleted comment comes back with its body gone. It is still a comment. */
	@Test
	fun `a body that is missing becomes empty rather than refusing the reply`() {
		val o = chainReply()
		o.remove("body")

		val reply = SnapReplies.parse(o)!!

		assertEquals("", reply.body)
	}

	/**
	 * An unreadable timestamp is carried as null. The alternative — substituting
	 * a time — would print an age nobody can check.
	 */
	@Test
	fun `an unreadable created field leaves the age unknown`() {
		listOf("", "yesterday", "2026-09-17T12:36:00Z", "2026-02-30T00:00:00").forEach {
			assertNull(SnapReplies.parse(chainReply(created = it))!!.createdAtEpochSec)
		}
	}

	@Test
	fun `a missing created field leaves the age unknown`() {
		val o = chainReply()
		o.remove("created")

		assertNull(SnapReplies.parse(o)!!.createdAtEpochSec)
	}

	/**
	 * The limit RustedWax enforces binds what RustedWax writes. A reply from any
	 * other Hive client arrives complete and stays complete.
	 */
	@Test
	fun `a long external reply survives the boundary intact`() {
		val long = "e\u0301\uD83C\uDFB5 ".repeat(2_000)

		val reply = SnapReplies.parse(chainReply(body = long))!!

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

		val reply = SnapReplies.parse(chainReply(body = huge))!!

		assertEquals(70_000, reply.body.length)
		assertEquals(huge, reply.body)
	}

	@Test
	fun `a body past 64 KiB reaches the thread whole`() {
		val huge = "long ".repeat(20_000)
		val rootId = "alice/rustedwax-snap-1000-aaaaaa"

		val thread = SnapThreadBuilder.build(
			rootId,
			SnapReplies.parseAll(listOf(chainReply(body = huge))),
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
		val parsed = SnapReplies.parseAll(
			listOf(
				chainReply(author = "bob"),
				JSONObject(),
				chainReply(author = "NOPE"),
				chainReply(author = "carol", permlink = "re-two"),
			),
		)

		assertEquals(listOf("bob", "carol"), parsed.map { it.author })
	}
}
