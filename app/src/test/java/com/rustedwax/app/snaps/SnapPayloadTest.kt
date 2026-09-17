package com.rustedwax.app.snaps

import com.rustedwax.hive.HiveScrobblePayload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frozen v1 Snap body and metadata.
 *
 * The contract is three parts and nothing else: the user's text, the canonical
 * YouTube URL, the required hashtags. These tests exist mostly to keep things
 * *out* — an earlier revision inserted an `Artist — Title` header and an
 * artist/title association block, and several assertions here are there
 * specifically so neither can come back.
 */
class SnapPayloadTest {

	private val media = SnapMedia("8pSS6wdojqY")

	@Test
	fun `builds user text, canonical url, hashtags — and nothing else`() {
		assertEquals(
			"what a groove\n\nhttps://youtu.be/8pSS6wdojqY\n\n#scrobblelife #scrobble #rustedwax",
			SnapPayloadBuilder.build("what a groove", media).body,
		)
	}

	@Test
	fun `inserts no artist or title header`() {
		val body = SnapPayloadBuilder.build("just this", media).body
		assertTrue("no bolding may be inserted", !body.contains("**"))
		assertTrue("no em-dash header may be inserted", !body.contains("—"))
		assertTrue("no media emoji header may be inserted", !body.contains("🎵"))
		assertTrue("the body must begin with the user's own words", body.startsWith("just this"))
	}

	@Test
	fun `carries no media classification in metadata`() {
		val raw = SnapPayloadBuilder.build("hi", media).jsonMetadata
		listOf("zingit", "artist", "title", "kind", "isrc", "timed_comment", "source_url", "music")
			.forEach { assertTrue("metadata must not carry '$it'", !raw.contains(it)) }

		val meta = JSONObject(raw)
		assertEquals(
			"only app and tags may remain",
			setOf("app", "tags"),
			meta.keys().asSequence().toSet(),
		)
		assertEquals(HiveScrobblePayload.APP_NAME, meta.getString("app"))
		assertEquals(
			listOf("scrobblelife", "scrobble", "rustedwax"),
			(0 until meta.getJSONArray("tags").length()).map { meta.getJSONArray("tags").getString(it) },
		)
	}

	/** The signed string must not depend on a map's iteration order. */
	@Test
	fun `metadata is byte-stable`() {
		val once = SnapPayloadBuilder.build("hi", media).jsonMetadata
		repeat(20) { assertEquals(once, SnapPayloadBuilder.build("hi", media).jsonMetadata) }
		assertEquals(
			"""{"app":"${HiveScrobblePayload.APP_NAME}",""" +
				""""tags":["scrobblelife","scrobble","rustedwax"]}""",
			once,
		)
	}

	@Test
	fun `body and metadata tags agree`() {
		val payload = SnapPayloadBuilder.build("x", media)
		SnapPayloadBuilder.REQUIRED_TAGS.forEach {
			assertTrue(payload.body.contains("#$it"))
			assertTrue(payload.jsonMetadata.contains("\"$it\""))
		}
	}

	@Test
	fun `carries unicode and awkward text through intact`() {
		val body = SnapPayloadBuilder.build("goosebumps 🔥 \"quoted\" back\\slash", media).body
		assertTrue(body.startsWith("goosebumps 🔥 \"quoted\" back\\slash"))
	}

	// ── the generated suffix is unconditional ──────────────────────────

	/** The exact tail every body ends with, whatever the user typed. */
	private val suffix =
		"\n\nhttps://youtu.be/8pSS6wdojqY\n\n#scrobblelife #scrobble #rustedwax"

	/**
	 * Exact equality, not `contains`, and the user's text **unmodified**.
	 *
	 * Two earlier defects live in this one assertion. The builder used to
	 * deduplicate the suffix against the user's prose, so what RustedWax appended
	 * depended on a fuzzy reading of free text; and it used to `trim()`, so
	 * RustedWax silently authored part of what it published. Comparing whole
	 * bodies against the raw input catches both — a `contains` check would pass
	 * happily while the suffix went missing or the text was quietly edited.
	 */
	private fun assertBody(userText: String) {
		assertEquals(
			userText + suffix,
			SnapPayloadBuilder.build(userText, media).body,
		)
	}

	@Test
	fun `ordinary text gets the exact suffix`() {
		assertBody("what a groove")
	}

	@Test
	fun `text already holding the exact canonical url still gets the suffix`() {
		assertBody("listen https://youtu.be/8pSS6wdojqY now")
	}

	@Test
	fun `other youtube url forms still get the suffix`() {
		assertBody("https://www.youtube.com/watch?v=8pSS6wdojqY")
		assertBody("https://youtube.com/shorts/8pSS6wdojqY")
		assertBody("https://m.youtube.com/watch?v=8pSS6wdojqY&t=30")
		assertBody("the id is 8pSS6wdojqY")
	}

	@Test
	fun `each required hashtag typed by the user is not deduplicated`() {
		assertBody("great one #scrobblelife")
		assertBody("great one #scrobble")
		assertBody("great one #rustedwax")
	}

	@Test
	fun `uppercase hashtag variants are not deduplicated`() {
		assertBody("#SCROBBLELIFE")
		assertBody("#Scrobble")
		assertBody("#RustedWax")
		assertBody("#ScRoBbLeLiFe #SCROBBLE #Rustedwax")
	}

	/** Everything at once: the user's copy stays, ours is still appended. */
	@Test
	fun `text containing every generated element still gets the suffix once`() {
		val userText =
			"https://youtu.be/8pSS6wdojqY #scrobblelife #scrobble #rustedwax #SCROBBLE 8pSS6wdojqY"
		assertBody(userText)
		val body = SnapPayloadBuilder.build(userText, media).body
		// Appended exactly once — theirs plus ours, never ours twice.
		assertEquals(2, body.split("https://youtu.be/8pSS6wdojqY").size - 1)
		assertTrue(body.endsWith(suffix))
	}

	@Test
	fun `the suffix is the exact lowercase hashtag line in the frozen order`() {
		assertEquals("#scrobblelife #scrobble #rustedwax", SnapPayloadBuilder.TAG_LINE)
		assertEquals(suffix, SnapPayloadBuilder.suffix(media))
	}

	// ── the user's text is published byte-for-byte ─────────────────────

	@Test
	fun `leading whitespace is preserved`() {
		assertBody("   indented on purpose")
		assertBody("\n\nstarts after blank lines")
		assertBody("\tleading tab")
	}

	@Test
	fun `trailing whitespace is preserved`() {
		assertBody("trailing spaces   ")
		assertBody("trailing newline\n")
		assertBody("trailing blank lines\n\n\n")
		assertBody("trailing tab\t")
	}

	@Test
	fun `leading and trailing whitespace together are preserved`() {
		assertBody("  \n both ends \n  ")
	}

	@Test
	fun `embedded newlines are preserved`() {
		assertBody("first line\nsecond line")
		assertBody("a paragraph\n\nand another")
		assertBody("one\ntwo\n\n\nthree")
	}

	@Test
	fun `emoji and unicode content are preserved`() {
		assertBody("goosebumps 🔥")
		assertBody("👩‍💻 zero-width joiner sequence")
		assertBody("ہجرِ محبت — Urdu with combining marks")
		assertBody("日本語とEmoji 🎵🤘")
		assertBody("\u00A0non-breaking space\u00A0")
	}

	@Test
	fun `punctuation and escapes are preserved`() {
		assertBody("Don't  touch   this — \"quoted\" back\\slash")
	}

	/** Whatever is preserved, the exact suffix still follows it. */
	@Test
	fun `the exact suffix follows even whitespace-only-padded text`() {
		val userText = "  padded  \n"
		val body = SnapPayloadBuilder.build(userText, media).body
		assertTrue("the original text must come first", body.startsWith(userText))
		assertTrue("the exact suffix must follow", body.endsWith(suffix))
		assertEquals(userText + suffix, body)
		assertNull(SnapPayloadBuilder.problem(SnapPayloadBuilder.build(userText, media), media))
	}

	/** Text carrying the generated elements is still untouched, suffix still added. */
	@Test
	fun `text holding url and hashtags is untouched and still gets the suffix`() {
		val userText = "  https://youtu.be/8pSS6wdojqY #scrobblelife #SCROBBLE #rustedwax  \n"
		val body = SnapPayloadBuilder.build(userText, media).body
		assertEquals(userText + suffix, body)
		assertTrue(body.startsWith(userText))
		assertEquals(2, body.split("https://youtu.be/8pSS6wdojqY").size - 1)
	}

	// ── eligibility is History membership ──────────────────────────────

	/** The only gate: a video id good enough to build the anchor URL from. */
	@Test
	fun `accepts any row with a usable video id`() {
		listOf("8pSS6wdojqY", "L12DOfNlPKE", "a", "_-aA09zZ").forEach {
			assertNull(SnapPayloadBuilder.mediaProblem(SnapMedia(it)))
			assertNull(SnapPayloadBuilder.problem(SnapPayloadBuilder.build("x", SnapMedia(it)), SnapMedia(it)))
		}
	}

	@Test
	fun `refuses only an unusable video id`() {
		listOf("", "   ", "abc/../def", "watch?v=abc", "a b", "id#frag").forEach {
			assertNotNull("must refuse '$it'", SnapPayloadBuilder.mediaProblem(SnapMedia(it)))
		}
	}

	// ── the guard checks the finished body ─────────────────────────────

	@Test
	fun `refuses a body that lost its canonical link`() {
		val meta = SnapPayloadBuilder.build("x", media).jsonMetadata
		val problem = SnapPayloadBuilder.problem(
			SnapPayload("just words\n\n#scrobblelife #scrobble #rustedwax", meta),
			media,
		)
		assertNotNull(problem)
		assertTrue(problem!!.contains("canonical YouTube link"))
	}

	@Test
	fun `refuses a body whose tail is not the exact suffix`() {
		val meta = SnapPayloadBuilder.build("x", media).jsonMetadata
		listOf(
			"hi\n\nhttps://youtu.be/8pSS6wdojqY\n\n#scrobblelife #scrobble",
			"hi\n\nhttps://youtu.be/8pSS6wdojqY\n\n#SCROBBLELIFE #SCROBBLE #RUSTEDWAX",
			"hi\n\nhttps://youtu.be/8pSS6wdojqY #scrobblelife #scrobble #rustedwax",
			"hi\n\n#scrobblelife #scrobble #rustedwax",
		).forEach { body ->
			assertNotNull("must refuse tail of: $body", SnapPayloadBuilder.problem(SnapPayload(body, meta), media))
		}
		assertNull(
			SnapPayloadBuilder.problem(SnapPayloadBuilder.build("hi", media), media),
		)
	}
}
