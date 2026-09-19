package com.rustedwax.app.snaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a History card is allowed to grow by.
 *
 * The card is a row in a list. The conversation under it is written by other
 * people and may be any size at all, so the preview is bounded in both
 * directions — how many replies, and how much of each — and both bounds are
 * asserted against deliberately hostile input rather than typical input.
 */
class SnapThreadPreviewTest {

	private val root = "alice/rustedwax-snap-1000-aaaaaa"

	private fun reply(id: String, at: Long?, body: String = "text") =
		SnapReply("bob", id, "alice", "rustedwax-snap-1000-aaaaaa", body, at)

	private fun thread(replies: List<SnapReply>) = SnapThreadBuilder.build(root, replies)

	@Test
	fun `a conversation of any size shows at most two replies`() {
		val many = (0 until 400).map { reply("r$it", 1_000L + it) }

		val preview = SnapThreadPreview.of(thread(many))

		assertEquals(SnapThreadPreview.MAX_PREVIEWS, preview.items.size)
		assertTrue(preview.hasMore)
		assertEquals(400, preview.total)
	}

	/**
	 * The card stays a card while the thread has no size limit at all.
	 *
	 * The count it prints is the real one — `View replies (3000)` — and what it
	 * draws is still two clamped lines.
	 */
	@Test
	fun `a thread far past the old node cap still previews two replies`() {
		val many = (0 until 3_000).map { reply("r$it", 1_000L + it) }

		val preview = SnapThreadPreview.of(thread(many))

		assertEquals(SnapThreadPreview.MAX_PREVIEWS, preview.items.size)
		assertEquals("the count must not lie about what the thread holds", 3_000, preview.total)
		assertTrue(preview.hasMore)
		assertTrue(preview.items.all { it.text.length <= SnapThreadPreview.MAX_PREVIEW_CHARS })
		// Still the newest two, picked without sorting the conversation.
		assertEquals(listOf("r2998", "r2999"), preview.items.map { it.permlink })
	}

	@Test
	fun `a reply of any length is clamped before it reaches the card`() {
		val huge = "x".repeat(50_000)

		val preview = SnapThreadPreview.of(thread(listOf(reply("r1", 1_000, huge))))

		val item = preview.items.single()
		assertEquals(SnapThreadPreview.MAX_PREVIEW_CHARS, item.text.length)
		assertTrue(item.truncated)
	}

	/**
	 * Both bounds at once: the worst case a card can be handed is a thread of
	 * enormous replies, and what it draws is still two clamped lines.
	 */
	@Test
	fun `the preview cannot grow with the conversation`() {
		val awful = (0 until 200).map { reply("r$it", 1_000L + it, "y".repeat(20_000)) }

		val preview = SnapThreadPreview.of(thread(awful))

		assertTrue(preview.items.size <= SnapThreadPreview.MAX_PREVIEWS)
		assertTrue(preview.items.all { it.text.length <= SnapThreadPreview.MAX_PREVIEW_CHARS })
	}

	@Test
	fun `a short reply is shown whole and not marked truncated`() {
		val preview = SnapThreadPreview.of(thread(listOf(reply("r1", 1_000, "nice one"))))

		assertEquals("nice one", preview.items.single().text)
		assertFalse(preview.items.single().truncated)
		assertFalse("one reply is the whole thread", preview.hasMore)
	}

	/** The two most recent, wherever in the tree they sit, oldest of the two first. */
	@Test
	fun `the newest replies are the ones shown`() {
		val preview = SnapThreadPreview.of(
			thread(
				listOf(
					reply("r1", 1_000),
					reply("r2", 5_000),
					reply("r3", 3_000),
				),
			),
		)

		assertEquals(listOf("r3", "r2"), preview.items.map { it.permlink })
	}

	/** A reply with no readable timestamp must not displace a dated one. */
	@Test
	fun `an undated reply does not claim to be the newest`() {
		val preview = SnapThreadPreview.of(
			thread(
				listOf(
					reply("r1", null),
					reply("r2", 1_000),
					reply("r3", 2_000),
				),
			),
		)

		assertEquals(listOf("r2", "r3"), preview.items.map { it.permlink })
	}

	/** Clamping counts code points, so an emoji is never cut in half. */
	@Test
	fun `clamping never splits a surrogate pair`() {
		val emoji = "\uD83C\uDFB5".repeat(500)

		val text = SnapThreadPreview.of(thread(listOf(reply("r1", 1_000, emoji)))).items.single().text

		assertEquals(SnapThreadPreview.MAX_PREVIEW_CHARS, text.codePointCount(0, text.length))
		assertFalse(Character.isHighSurrogate(text.last()))
	}

	@Test
	fun `an empty thread previews nothing`() {
		val preview = SnapThreadPreview.of(SnapThread.empty(root))

		assertTrue(preview.items.isEmpty())
		assertFalse(preview.hasMore)
	}
}
