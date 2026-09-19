package com.rustedwax.app.ui.snaps

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted reply-draft schema, and why it is strict.
 *
 * A stored draft is the only thing tying an unsent reply to the publication
 * attempt it may already have become. A record this parser accepts too
 * generously is a record whose intent can go missing without anyone noticing —
 * and a draft that looks like it has never been sent is a draft the next Send
 * publishes under a brand-new permlink, beside the one that may already be
 * live.
 *
 * So the rule is: exactly the record [SnapReplyDraft.toJson] writes, or nothing.
 */
class SnapReplyDraftTest {

	private val intent = "a1b2c3d4e5f60718"

	private fun json(build: JSONObject.() -> Unit): String = JSONObject().apply(build).toString()

	// ── accepted ───────────────────────────────────────────────────────

	@Test
	fun `an explicit null intent is a genuinely unsent draft`() {
		val parsed = SnapReplyDraft.fromJson(
			json {
				put("text", "half typed")
				put("intentId", JSONObject.NULL)
			},
		)

		assertEquals(SnapReplyDraft("half typed", null), parsed)
	}

	@Test
	fun `a valid intent string is kept`() {
		val parsed = SnapReplyDraft.fromJson(
			json {
				put("text", "nice one")
				put("intentId", intent)
			},
		)

		assertEquals(SnapReplyDraft("nice one", intent), parsed)
	}

	@Test
	fun `an empty text with an intent is still a record`() {
		assertEquals(
			SnapReplyDraft("", intent),
			SnapReplyDraft.fromJson(json { put("text", ""); put("intentId", intent) }),
		)
	}

	@Test
	fun `everything this class writes, it can read back`() {
		listOf(
			SnapReplyDraft("nice one", intent),
			SnapReplyDraft("half typed", null),
			SnapReplyDraft("", null),
			SnapReplyDraft("newlines\nand \"quotes\" and 🔥", intent),
		).forEach {
			assertEquals(it, SnapReplyDraft.fromJson(it.toJson()))
		}
	}

	// ── refused ────────────────────────────────────────────────────────

	/**
	 * The case that was wrong.
	 *
	 * `{"text":"nice one"}` is valid JSON, and `isNull` answers true for a key
	 * that is simply not there — so a draft already sent under some intent came
	 * back looking brand new.
	 */
	@Test
	fun `a missing intentId key is not an unsent draft`() {
		assertNull(SnapReplyDraft.fromJson("""{"text":"nice one"}"""))
	}

	@Test
	fun `a missing text key is refused`() {
		assertNull(SnapReplyDraft.fromJson(json { put("intentId", intent) }))
	}

	@Test
	fun `an empty object is refused`() {
		assertNull(SnapReplyDraft.fromJson("{}"))
	}

	@Test
	fun `a wrong-typed text is refused rather than coerced`() {
		listOf<Any>(42, true, 1.5, JSONObject().put("a", 1)).forEach { wrong ->
			assertNull(
				"text as $wrong",
				SnapReplyDraft.fromJson(json { put("text", wrong); put("intentId", intent) }),
			)
		}
		// `null` text is not the same as an empty one either.
		assertNull(
			SnapReplyDraft.fromJson(
				json { put("text", JSONObject.NULL); put("intentId", intent) },
			),
		)
	}

	@Test
	fun `a wrong-typed intentId is refused rather than coerced`() {
		listOf<Any>(42, true, 1.5, JSONObject().put("a", 1)).forEach { wrong ->
			assertNull(
				"intentId as $wrong",
				SnapReplyDraft.fromJson(json { put("text", "nice one"); put("intentId", wrong) }),
			)
		}
	}

	/**
	 * A string that could never have named a pending record is no more usable
	 * than a missing one.
	 */
	@Test
	fun `an intent string that is not an intent id is refused`() {
		listOf(
			"",
			"recovered-1f2e3d4c",
			"A1B2C3D4E5F60718",
			"a1b2c3d4e5f6071",
			"a1b2c3d4e5f607189",
			"a1b2c3d4e5f6071z",
			"a1b2c3d4|e5f60718",
			"  a1b2c3d4e5f60718  ",
		).forEach { bad ->
			assertNull(
				"'$bad' is not an intent id",
				SnapReplyDraft.fromJson(json { put("text", "nice one"); put("intentId", bad) }),
			)
		}
	}

	@Test
	fun `text that is not JSON at all is refused`() {
		listOf("", "not json", "{", "[]", "\"a string\"", "null", "7").forEach {
			assertNull("'$it'", SnapReplyDraft.fromJson(it))
		}
	}

	/** Extra keys are tolerated: the two that matter are the two that are checked. */
	@Test
	fun `an unknown extra key does not make a record unreadable`() {
		val parsed = SnapReplyDraft.fromJson(
			json {
				put("text", "nice one")
				put("intentId", intent)
				put("somethingNew", "from a later build")
			},
		)

		assertEquals(SnapReplyDraft("nice one", intent), parsed)
	}

	@Test
	fun `a minted intent always satisfies the stored shape`() {
		repeat(50) {
			val minted = com.rustedwax.app.snaps.SnapReplyIntent.generate()
			assertTrue(com.rustedwax.app.snaps.SnapReplyIntent.isValid(minted))
			assertEquals(
				SnapReplyDraft("x", minted),
				SnapReplyDraft.fromJson(SnapReplyDraft("x", minted).toJson()),
			)
		}
	}
}
