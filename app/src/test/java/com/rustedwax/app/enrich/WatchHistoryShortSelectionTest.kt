package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchHistoryShortSelectionTest {

	private fun short(id: String, title: String) = WatchHistoryParser.ShortEntry(id, title)

	@Test
	fun `a hashtag-only title selects itself rather than every empty semantic key`() {
		val feed = listOf(
			short("wrongHash01", "#unrelated"),
			short("UXgqmLDgU4c", "#hoyoverse"),
			short("wrongEmoji1", "🥰❤️"),
		)

		assertEquals(
			listOf("UXgqmLDgU4c"),
			WatchHistoryResolver.shortIdsMatchingTitle(feed, "#hoyoverse"),
		)
	}

	@Test
	fun `an emoji-only title selects the same emoji and no other one`() {
		val feed = listOf(
			short("wrongEmoji1", "😂❤️"),
			short("-8yHg3sb55I", "🥰❤️"),
			short("wrongHash01", "#hoyoverse"),
		)

		assertEquals(
			listOf("-8yHg3sb55I"),
			WatchHistoryResolver.shortIdsMatchingTitle(feed, "🥰❤️"),
		)
	}
}
