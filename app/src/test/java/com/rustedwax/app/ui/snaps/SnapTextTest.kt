package com.rustedwax.app.ui.snaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 200-character rule, from the user's side of the glass.
 *
 * Every emoji here is written as explicit code units rather than pasted, so the
 * test says which sequence it means and cannot be changed by an editor quietly
 * normalising the file.
 */
class SnapTextTest {

	private val thumbsUp = "👍" // U+1F44D, 2 code units
	private val thumbsUpTanned = "👍🏽" // + skin tone, 4 units
	private val redHeart = "❤️" // heart + emoji variation selector
	private val family = "👨‍👩‍👧‍👦"
	private val flagGb = "🇬🇧" // two regional indicators
	private val keycapOne = "1️⃣"
	private val eAcuteCombining = "é"

	@Test
	fun `plain text counts one per character`() {
		assertEquals(0, SnapText.count(""))
		assertEquals(5, SnapText.count("hello"))
		assertEquals(11, SnapText.count("hello world"))
	}

	@Test
	fun `an ordinary emoji is one character, not two`() {
		// The whole reason this class exists: String.length says 2 here.
		assertEquals(2, thumbsUp.length)
		assertEquals(1, SnapText.count(thumbsUp))
	}

	@Test
	fun `a skin tone does not cost a second character`() {
		assertEquals(4, thumbsUpTanned.length)
		assertEquals(1, SnapText.count(thumbsUpTanned))
	}

	@Test
	fun `a variation selector rides along with its emoji`() {
		assertEquals(1, SnapText.count(redHeart))
	}

	@Test
	fun `a zero-width-joiner family is one character`() {
		assertEquals(11, family.length)
		assertEquals(1, SnapText.count(family))
	}

	/**
	 * A joiner between two letters joins nothing — both letters are on screen.
	 *
	 * Treating every `x ZWJ y` as one grapheme was a hole in the limit, not a
	 * nicety: the joiner is invisible, so a draft could carry twice the visible
	 * text the counter admitted to.
	 */
	@Test
	fun `a joiner between two letters does not fuse them`() {
		assertEquals(2, SnapText.count("a‍b"))
		assertEquals(3, SnapText.count("a‍b‍c"))
		assertEquals(2, SnapText.count("中‍文")) // and outside Latin
	}

	@Test
	fun `joiners cannot smuggle extra letters past the limit`() {
		// 400 visible letters, every pair welded by a joiner. Counted the old
		// way this read as 200 and Post would have been enabled on a Snap twice
		// the length RustedWax promises to create.
		val smuggled = "a‍b".repeat(200)

		assertEquals(400, SnapText.count(smuggled))
		assertTrue(SnapText.isOverflowing(smuggled))
		assertFalse(SnapText.isValid(smuggled))
		assertEquals(200, SnapText.overflow(smuggled))
		assertEquals("-200", SnapText.counterLabel(smuggled))
	}

	@Test
	fun `a joiner is never a character of its own`() {
		// It attaches to what it follows rather than starting a cluster, so a
		// joiner does not inflate the count either.
		assertEquals(1, SnapText.count("a‍"))
		assertEquals(2, SnapText.count("ab‍"))
	}

	/**
	 * An ordinary symbol is not an emoji just because emoji live nearby.
	 *
	 * `←` and `→` sit in the same Unicode block as `↔` and `↩`, which *are*
	 * pictographic. Classifying by block welded them through a joiner and
	 * reopened the bypass: two visible arrows reported as one character.
	 */
	@Test
	fun `plain arrows are not emoji and do not weld`() {
		assertEquals(2, SnapText.count("←‍→")) // ← ZWJ →
		assertEquals(2, SnapText.count("↑‍↓")) // ↑ ZWJ ↓
	}

	@Test
	fun `joined arrows cannot bypass the limit either`() {
		val smuggled = "←‍→".repeat(150) // 300 visible arrows

		assertEquals(300, SnapText.count(smuggled))
		assertTrue(SnapText.isOverflowing(smuggled))
		assertFalse(SnapText.isValid(smuggled))
		assertEquals("-100", SnapText.counterLabel(smuggled))
	}

	@Test
	fun `other ordinary symbols sharing an emoji block do not weld`() {
		// Misc Technical, Geometric Shapes and Misc Symbols all contain emoji and
		// non-emoji side by side; only the real property may decide.
		assertEquals(2, SnapText.count("⌁‍⌂")) // ⌁ ⌂
		assertEquals(2, SnapText.count("□‍▢")) // □ ▢
		assertEquals(2, SnapText.count("⬟‍⬠")) // ⬟ ⬠
	}

	@Test
	fun `the arrows that really are emoji still weld`() {
		// U+2194..2199 and U+21A9..21AA are Extended_Pictographic, unlike ← and →.
		assertEquals(1, SnapText.count("↔️‍↕️"))
	}

	@Test
	fun `emoji still weld across a joiner`() {
		// The behaviour the A12 verified, unchanged by the fix above.
		assertEquals(1, SnapText.count(family))
		// Heart-on-fire: a non-plane-1F emoji joined to a plane-1F one.
		assertEquals(1, SnapText.count("❤️‍🔥"))
		// Eye-in-speech-bubble, joiner with variation selectors on both sides.
		assertEquals(1, SnapText.count("👁️‍🗨️"))
	}

	@Test
	fun `an emoji joined to a letter stays two characters`() {
		// Half a weld is not a weld: only picture-to-picture merges.
		assertEquals(2, SnapText.count("$thumbsUp‍a"))
		assertEquals(2, SnapText.count("a‍$thumbsUp"))
	}

	@Test
	fun `a flag is one character, and two flags are two`() {
		assertEquals(1, SnapText.count(flagGb))
		assertEquals(2, SnapText.count(flagGb + flagGb))
	}

	@Test
	fun `a keycap is one character`() {
		assertEquals(1, SnapText.count(keycapOne))
	}

	@Test
	fun `a combining accent does not add a character`() {
		assertEquals(1, SnapText.count(eAcuteCombining))
	}

	@Test
	fun `mixed text counts the way it reads`() {
		// "hi " + 👍🏽 + "!" reads as five things on screen.
		assertEquals(5, SnapText.count("hi $thumbsUpTanned!"))
	}

	@Test
	fun `exactly two hundred is inside the limit`() {
		val text = "a".repeat(200)
		assertEquals(200, SnapText.count(text))
		assertTrue(SnapText.isValid(text))
		assertFalse(SnapText.isOverflowing(text))
		assertEquals("200/200", SnapText.counterLabel(text))
	}

	@Test
	fun `two hundred and one is over, and Post has to go dead`() {
		val text = "a".repeat(201)
		assertTrue(SnapText.isOverflowing(text))
		assertFalse(SnapText.isValid(text))
		assertEquals(1, SnapText.overflow(text))
		assertEquals("-1", SnapText.counterLabel(text))
	}

	@Test
	fun `the overflow counter reports how far past the limit it is`() {
		assertEquals("-12", SnapText.counterLabel("a".repeat(212)))
		assertEquals("-53", SnapText.counterLabel("a".repeat(253)))
	}

	@Test
	fun `two hundred emoji fit, because each one is a character`() {
		val text = thumbsUp.repeat(200)
		assertEquals(200, SnapText.count(text))
		assertTrue(SnapText.isValid(text))
		// Same draft measured naively would be 400 and be refused.
		assertEquals(400, text.length)
	}

	@Test
	fun `whitespace alone is not a Snap`() {
		assertFalse(SnapText.isValid(""))
		assertFalse(SnapText.isValid(" "))
		assertFalse(SnapText.isValid("   \n\t  "))
	}

	@Test
	fun `an emoji on its own is a Snap`() {
		assertTrue(SnapText.isValid(thumbsUp))
		assertTrue(SnapText.isValid(family))
		assertTrue(SnapText.isValid(flagGb))
	}

	@Test
	fun `text with spaces around it still counts as visible`() {
		assertTrue(SnapText.isValid("  hi  "))
	}

	@Test
	fun `invisible formatting alone does not make a Snap`() {
		// A draft of nothing but joiners has nothing on screen to post.
		assertFalse(SnapText.isValid("​"))
		assertFalse(SnapText.isValid("‍"))
		assertFalse(SnapText.isValid("﻿"))
	}

	@Test
	fun `the counter starts where the mockup says it does`() {
		assertEquals("0/200", SnapText.counterLabel(""))
		assertEquals("37/200", SnapText.counterLabel("a".repeat(37)))
		assertEquals("199/200", SnapText.counterLabel("a".repeat(199)))
	}

	@Test
	fun `counting never stalls on an unpaired surrogate`() {
		// Malformed input reaches here only via a broken IME, but a text field
		// that hangs is worse than one that miscounts a character nobody sees.
		assertEquals(1, SnapText.count("\uD83D"))
		assertEquals(2, SnapText.count("\uD83Da"))
	}
}
