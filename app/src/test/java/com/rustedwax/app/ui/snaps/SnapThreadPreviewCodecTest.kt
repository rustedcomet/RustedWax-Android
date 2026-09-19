package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.SnapThreadPreview
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What may be trusted off disk, and what the store is allowed to throw away.
 *
 * The values here become an account handle, an avatar request and visible text
 * on somebody's History card, so none of them is taken on trust — not even
 * though this app wrote them. A file can be hand-edited, truncated, or written
 * by an older build.
 */
class SnapThreadPreviewCodecTest {

	private fun item(
		author: String = "bob",
		permlink: String = "re-one",
		text: String = "hello",
		truncated: Boolean = false,
	) = SnapThreadPreview.Item(author, permlink, text, truncated)

	private fun preview(items: List<SnapThreadPreview.Item>, total: Int) =
		SnapThreadPreview.Preview(items, total)

	// ── round trip ─────────────────────────────────────────────────────

	@Test
	fun `a summary survives a round trip unchanged`() {
		val p = preview(listOf(item(), item("carol", "re-two", "second", truncated = true)), 9)

		val back = SnapThreadPreviewCodec.decode(SnapThreadPreviewCodec.encode(p, 1_000L))

		assertEquals(p, back)
		assertTrue(back!!.hasMore)
	}

	@Test
	fun `an empty summary round trips`() {
		val p = preview(emptyList(), 0)

		assertEquals(p, SnapThreadPreviewCodec.decode(SnapThreadPreviewCodec.encode(p, 1L)))
	}

	@Test
	fun `the write time is recorded and readable`() {
		val raw = SnapThreadPreviewCodec.encode(preview(emptyList(), 0), 1_789_751_249L)

		assertEquals(1_789_751_249L, SnapThreadPreviewCodec.storedAt(raw))
		assertEquals(0L, SnapThreadPreviewCodec.storedAt("not json"))
	}

	// ── stale counts still behave ──────────────────────────────────────

	/**
	 * A remembered total may be behind the chain — that is the point of showing
	 * it before the refresh. It must still drive `hasMore` correctly.
	 */
	@Test
	fun `a stale total larger than the items shown still offers a way through`() {
		val back = SnapThreadPreviewCodec.decode(
			SnapThreadPreviewCodec.encode(preview(listOf(item(), item("carol", "re-two")), 47), 1L),
		)!!

		assertEquals(47, back.total)
		assertEquals(2, back.items.size)
		assertTrue(back.hasMore)
	}

	@Test
	fun `a total equal to the items shown offers no way through`() {
		val back = SnapThreadPreviewCodec.decode(
			SnapThreadPreviewCodec.encode(preview(listOf(item()), 1), 1L),
		)!!

		assertFalse(back.hasMore)
	}

	// ── fail safe ──────────────────────────────────────────────────────

	@Test
	fun `bytes that are not the stored record decode to nothing`() {
		listOf("", "not json", "{", "[]", "null", "7", "\"a string\"", "{}").forEach {
			assertNull("'$it'", SnapThreadPreviewCodec.decode(it))
		}
	}

	@Test
	fun `a missing field decodes to nothing`() {
		assertNull(SnapThreadPreviewCodec.decode("""{"items":[]}"""))
		assertNull(SnapThreadPreviewCodec.decode("""{"total":3}"""))
	}

	@Test
	fun `wrong-typed fields decode to nothing`() {
		assertNull(SnapThreadPreviewCodec.decode("""{"total":"3","items":[]}"""))
		assertNull(SnapThreadPreviewCodec.decode("""{"total":3,"items":{}}"""))
		assertNull(SnapThreadPreviewCodec.decode("""{"total":3,"items":[42]}"""))
	}

	@Test
	fun `a negative total decodes to nothing`() {
		assertNull(SnapThreadPreviewCodec.decode("""{"total":-1,"items":[]}"""))
	}

	/** A count below what is drawn would hide replies that exist. */
	@Test
	fun `a total smaller than the items shown decodes to nothing`() {
		val raw = SnapThreadPreviewCodec.encode(
			preview(listOf(item(), item("carol", "re-two")), 2), 1L,
		)
		val tampered = JSONObject(raw).put("total", 1).toString()

		assertNull(SnapThreadPreviewCodec.decode(tampered))
	}

	@Test
	fun `an item whose author is not a Hive account name decodes to nothing`() {
		listOf("Alice", "al", "a..b", ".alice", "alice/bob", "").forEach { bad ->
			val raw = SnapThreadPreviewCodec.encode(preview(listOf(item(author = bad)), 1), 1L)
			assertNull("'$bad'", SnapThreadPreviewCodec.decode(raw))
		}
	}

	@Test
	fun `an item whose permlink is not a permlink decodes to nothing`() {
		listOf("a/b", "a b", "a|b", "UPPER", "-leading", "").forEach { bad ->
			val raw = SnapThreadPreviewCodec.encode(preview(listOf(item(permlink = bad)), 1), 1L)
			assertNull("'$bad'", SnapThreadPreviewCodec.decode(raw))
		}
	}

	// ── bounds are re-applied, never trusted ───────────────────────────

	@Test
	fun `more items than the card allows decodes to nothing`() {
		val raw = SnapThreadPreviewCodec.encode(
			preview(listOf(item(), item("carol", "re-two"), item("dave", "re-three")), 3),
			1L,
		)
		// `encode` caps at MAX_PREVIEWS, so tamper with the stored form directly.
		val tampered = JSONObject(raw).put(
			"items",
			JSONObject(
				SnapThreadPreviewCodec.encode(
					preview(listOf(item(), item("carol", "re-two")), 2), 1L,
				),
			).getJSONArray("items").put(
				JSONObject().put("author", "dave").put("permlink", "re-three")
					.put("text", "x").put("truncated", false),
			),
		).toString()

		assertNull(SnapThreadPreviewCodec.decode(tampered))
	}

	@Test
	fun `encode never writes more items than the card allows`() {
		val tooMany = preview(
			listOf(item(), item("carol", "re-two"), item("dave", "re-three")),
			3,
		)

		val back = SnapThreadPreviewCodec.decode(SnapThreadPreviewCodec.encode(tooMany, 1L))!!

		assertEquals(SnapThreadPreview.MAX_PREVIEWS, back.items.size)
	}

	/** A stored line longer than the rule would make the card taller than allowed. */
	@Test
	fun `stored text is re-clamped rather than measured`() {
		val long = "x".repeat(SnapThreadPreview.MAX_PREVIEW_CHARS * 3)
		val raw = JSONObject()
			.put("at", 1L)
			.put("total", 1)
			.put(
				"items",
				org.json.JSONArray().put(
					JSONObject().put("author", "bob").put("permlink", "re-one")
						.put("text", long).put("truncated", true),
				),
			)
			.toString()

		val back = SnapThreadPreviewCodec.decode(raw)!!

		assertEquals(SnapThreadPreview.MAX_PREVIEW_CHARS, back.items.single().text.length)
	}

	@Test
	fun `re-clamping never splits a surrogate pair`() {
		val emoji = "🎵".repeat(SnapThreadPreview.MAX_PREVIEW_CHARS * 2)
		val raw = JSONObject()
			.put("at", 1L).put("total", 1)
			.put(
				"items",
				org.json.JSONArray().put(
					JSONObject().put("author", "bob").put("permlink", "re-one")
						.put("text", emoji).put("truncated", true),
				),
			).toString()

		val text = SnapThreadPreviewCodec.decode(raw)!!.items.single().text

		assertEquals(SnapThreadPreview.MAX_PREVIEW_CHARS, text.codePointCount(0, text.length))
		assertFalse(Character.isHighSurrogate(text.last()))
	}

	// ── eviction ───────────────────────────────────────────────────────

	@Test
	fun `nothing is evicted while the store is under its bound`() {
		val ages = (1..10).associate { "k$it" to it.toLong() }

		assertTrue(SnapThreadPreviewCodec.surplusKeys(ages, keeping = "k1", max = 60).isEmpty())
	}

	@Test
	fun `the oldest entries are evicted once the bound is reached`() {
		val ages = (1..60).associate { "k$it" to it.toLong() }

		val dropped = SnapThreadPreviewCodec.surplusKeys(ages, keeping = "new", max = 60)

		assertEquals(listOf("k1"), dropped)
	}

	@Test
	fun `a store past its bound sheds enough to come back under it`() {
		val ages = (1..70).associate { "k$it" to it.toLong() }

		val dropped = SnapThreadPreviewCodec.surplusKeys(ages, keeping = "new", max = 60)

		assertEquals(11, dropped.size)
		assertEquals("the oldest go first", (1..11).map { "k$it" }, dropped)
		// What survives, plus the row being written, is exactly the bound.
		assertEquals("the store lands exactly on its bound", 60, ages.size - dropped.size + 1)
	}

	/** The row being written is the one row that must never be evicted. */
	@Test
	fun `the key being written is never evicted`() {
		val ages = (1..80).associate { "k$it" to it.toLong() } + ("k1" to 0L)

		val dropped = SnapThreadPreviewCodec.surplusKeys(ages, keeping = "k1", max = 60)

		assertFalse("k1" in dropped)
	}

	@Test
	fun `eviction is deterministic when write times tie`() {
		val ages = (1..70).associate { "k$it" to 5L }

		val a = SnapThreadPreviewCodec.surplusKeys(ages, keeping = "new", max = 60)
		val b = SnapThreadPreviewCodec.surplusKeys(ages, keeping = "new", max = 60)

		assertEquals(a, b)
	}
}
