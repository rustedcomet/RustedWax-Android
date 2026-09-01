package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerHandleTest {

	@Test
	fun `canonical YouTube owner profile URLs preserve exact handle punctuation`() {
		assertEquals("@sonadarus", OwnerHandle.fromOwnerProfileUrl("http://www.youtube.com/@SonaDarus"))
		assertEquals("@status_svijet", OwnerHandle.fromOwnerProfileUrl("https://youtube.com/@Status_svijet"))
		assertEquals("@beredist", OwnerHandle.fromOwnerProfileUrl("https://www.youtube.com/@Beredist/"))
		assertTrue(OwnerHandle.matches("@Status_svijet", "status_SVIJET"))
		assertFalse(OwnerHandle.matches("@Status_svijet", "@Status-svijet"))
	}

	/**
	 * Measured 2026-08-07: `Go to channel @eduardaarebouçass` was refused on
	 * the cedilla, and since v0.9.10 the handle is the one mandatory field — so
	 * the whole listen went with it. Every creator whose handle is not spelled in
	 * ASCII was invisible to RustedWax, on the accessibility footer and on the
	 * watch page alike.
	 */
	@Test
	fun `a handle spelled outside ASCII is read on both surfaces`() {
		val measured = "@eduardaarebouçass"
		assertEquals(measured, OwnerHandle.canonical(measured))
		assertEquals(measured, OwnerHandle.fromOwnerProfileUrl("https://www.youtube.com/$measured"))
		// The same URL as YouTube percent-encodes it.
		assertEquals(
			measured,
			OwnerHandle.fromOwnerProfileUrl("https://www.youtube.com/@eduardaarebou%C3%A7ass"),
		)
		assertEquals("@日本語ちゃん", OwnerHandle.canonical("@日本語ちゃん"))
	}

	@Test
	fun `one handle encoded two ways is one handle`() {
		// The footer and the watch page need not agree on whether a cedilla is
		// one character or a letter plus a combining mark.
		val composed = "@erebo\u00E7ass"
		val decomposed = "@erebo" + "c\u0327" + "ass"
		assertTrue(OwnerHandle.matches(composed, decomposed))
		assertEquals(OwnerHandle.canonical(composed), OwnerHandle.canonical(decomposed))
	}

	@Test
	fun `host confusion credentials ports queries fragments and extra paths refuse`() {
		listOf(
			"https://youtube.com.evil.example/@SonaDarus",
			"https://evil.example/youtube.com/@SonaDarus",
			"https://user@youtube.com/@SonaDarus",
			"https://youtube.com:443/@SonaDarus",
			"https://youtube.com/@SonaDarus/videos",
			"https://youtube.com/@SonaDarus?x=1",
			"https://youtube.com/@SonaDarus#about",
			"https://youtube.com/%40SonaDarus",
			"javascript:https://youtube.com/@SonaDarus",
			"https://youtube.com/@@SonaDarus",
			// An ASCII escape stays refused even now that non-ASCII ones are
			// read: nothing structural may be spelled sideways.
			"https://youtube.com/@Sona%2FDarus",
			"https://youtube.com/@Sona%2EDarus",
		).forEach { assertNull(it, OwnerHandle.fromOwnerProfileUrl(it)) }
	}
}
