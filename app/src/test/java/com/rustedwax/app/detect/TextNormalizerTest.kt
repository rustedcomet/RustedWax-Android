package com.rustedwax.app.detect

import com.rustedwax.core.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** §3.5 and §3.6: one answer to "are these the same string", in its two forms. */
class TextNormalizerTest {

	/**
	 * The case §3.5 names: a zero-width space sits exactly where the parser looks
	 * for a word boundary, on a title that is visually perfect.
	 */
	@Test
	fun `an invisible character does not break the artist split`() {
		val withZeroWidth = "Artist​ - Song"
		assertEquals("Artist - Song", TextNormalizer.clean(withZeroWidth))
		assertEquals("Artist", TitleParser.parse(withZeroWidth, "Channel").artist)
		assertEquals("Song", TitleParser.parse(withZeroWidth, "Channel").track)
	}

	@Test
	fun `non-breaking spaces smart quotes and entities are folded`() {
		assertEquals("A B", TextNormalizer.clean("A B"))
		assertEquals("Don't", TextNormalizer.clean("Don’t"))
		assertEquals("\"Quoted\"", TextNormalizer.clean("“Quoted”"))
		assertEquals("Rock & Roll", TextNormalizer.clean("Rock &amp; Roll"))
		assertEquals("<tag>", TextNormalizer.clean("&lt;tag&gt;"))
		assertEquals("café", TextNormalizer.clean("caf&#233;"))
	}

	/** Decoded in browser order, so a double-escaped entity stays literal text. */
	@Test
	fun `an escaped entity is not decoded twice`() {
		assertEquals("&quot;", TextNormalizer.clean("&amp;quot;"))
	}

	@Test
	fun `one handle spelled two ways produces one dedup key`() {
		val composed = "@eduardarebouçass"
		val decomposed = "@eduardarebouçass"
		assertNotEquals(composed, decomposed)
		assertEquals(
			com.rustedwax.app.scrobble.DedupLedger.keyFor("Title", composed, 1_785_000_000),
			com.rustedwax.app.scrobble.DedupLedger.keyFor("Title", decomposed, 1_785_000_000),
		)
	}

	/**
	 * Presentation may fold compatibility variants — a ligature and its letters
	 * are the same title, and a listen should not dedup twice over typography.
	 */
	@Test
	fun `presentation folds compatibility variants`() {
		assertEquals(TextNormalizer.presentation("ﬁre"), TextNormalizer.presentation("fire"))
		assertEquals(TextNormalizer.presentation("Ａrtist"), TextNormalizer.presentation("artist"))
	}

	/**
	 * Identity may **not**. NFKC maps distinct characters onto each other, and
	 * two different creators must never collide into one.
	 */
	@Test
	fun `identity keeps distinct characters distinct`() {
		assertNotEquals(TextNormalizer.identity("Ａrtist"), TextNormalizer.identity("Artist"))
		// But it still composes what is genuinely the same character.
		assertEquals(
			TextNormalizer.identity("rebouçass"),
			TextNormalizer.identity("rebouçass"),
		)
	}
}
