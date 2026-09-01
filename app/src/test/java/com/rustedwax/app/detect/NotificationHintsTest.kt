package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The cross-tab bleed found in Phase 4.
 *
 * Hints used to be one-per-package, last write wins, so a browser with audio in
 * two tabs had exactly one slot for both. That produced two failures, and the
 * second one is the reason this is tested rather than merely fixed: a
 * non-YouTube session could inherit a `youtube.com` hint and be broadcast as
 * YouTube — wrong attribution on a chain that cannot be edited.
 */
class NotificationHintsTest {

	private val brave = "com.brave.browser"

	private fun hint(host: String?, title: String?, text: String? = null, at: Long = NOW) =
		NotificationHints.Hint(host = host, subText = host, title = title, text = text, atMillis = at)

	@Before
	fun reset() {
		NotificationHints.clearAll()
	}

	@Test
	fun `binds each session to its own tab's hint`() {
		NotificationHints.put(brave, hint("youtube.com", "Korn - Trash"))
		NotificationHints.put(brave, hint("soundcloud.com", "Some Demo"))

		// Two sessions live, so no fallback guessing is allowed.
		val forYouTube = NotificationHints.bestFor(brave, "Korn - Trash", null, soleSession = false)
		val forOther = NotificationHints.bestFor(brave, "Some Demo", null, soleSession = false)

		assertEquals("youtube.com", forYouTube?.host)
		assertEquals("soundcloud.com", forOther?.host)
	}

	/**
	 * The dangerous direction. Before the fix, the newest hint answered for
	 * every session, so this returned youtube.com for a SoundCloud track.
	 */
	@Test
	fun `does not lend a youtube hint to another tab's session`() {
		NotificationHints.put(brave, hint("soundcloud.com", "Some Demo"))
		NotificationHints.put(brave, hint("youtube.com", "Korn - Trash"))

		val forOther = NotificationHints.bestFor(brave, "Some Demo", null, soleSession = false)
		assertEquals("soundcloud.com", forOther?.host)
	}

	@Test
	fun `refuses to guess when several sessions are live and nothing matches`() {
		NotificationHints.put(brave, hint("youtube.com", "Korn - Trash"))
		assertNull(NotificationHints.bestFor(brave, "Something else", null, soleSession = false))
	}

	/** With one session there is no other tab the notification could be from. */
	@Test
	fun `falls back to the newest hint when there is only one session`() {
		NotificationHints.put(brave, hint("youtube.com", "Korn - Trash"))
		val bound = NotificationHints.bestFor(brave, "Something else", null, soleSession = true)
		assertEquals("youtube.com", bound?.host)
	}

	@Test
	fun `stale hints are not used as a fallback`() {
		NotificationHints.put(brave, hint("youtube.com", "Korn - Trash", at = NOW - 10 * 60 * 1000))
		assertNull(NotificationHints.bestFor(brave, "Anything", null, soleSession = true, now = NOW))
	}

	/**
	 * A title match is a binding, not a guess, so age is irrelevant — the track
	 * that started twenty minutes ago is still the track that's playing.
	 */
	@Test
	fun `a title match has no expiry`() {
		val old = NOW - 30 * 60 * 1000
		NotificationHints.put(brave, hint("youtube.com", "Korn - Trash", at = old))
		val bound =
			NotificationHints.bestFor(brave, "Korn - Trash", null, soleSession = false, now = NOW)
		assertEquals("youtube.com", bound?.host)
	}

	/**
	 * Removing one tab's notification used to clear the whole package, so
	 * closing one tab silently killed the surviving tab's scrobble.
	 */
	@Test
	fun `removing one notification leaves the other tab's hint alone`() {
		NotificationHints.put(brave, hint("youtube.com", "Korn - Trash"))
		NotificationHints.put(brave, hint("soundcloud.com", "Some Demo"))

		NotificationHints.remove(brave, "Some Demo")

		assertEquals(
			"youtube.com",
			NotificationHints.bestFor(brave, "Korn - Trash", null, soleSession = false)?.host,
		)
	}

	@Test
	fun `repeated posts of the same notification do not fill the history`() {
		repeat(10) { NotificationHints.put(brave, hint("youtube.com", "Korn - Trash")) }
		NotificationHints.put(brave, hint("soundcloud.com", "Some Demo"))
		// The YouTube hint is still reachable, so it wasn't evicted by copies.
		assertEquals(
			"youtube.com",
			NotificationHints.bestFor(brave, "Korn - Trash", null, soleSession = false)?.host,
		)
	}

	private companion object {
		const val NOW = 1_800_000_000_000L
	}
}
