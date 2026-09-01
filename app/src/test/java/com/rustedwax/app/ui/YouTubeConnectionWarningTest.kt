package com.rustedwax.app.ui

import com.rustedwax.app.enrich.WatchHistoryHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Using RustedWax with no watch-history session connected has to be visibly a
 * choice rather than a silent default.
 *
 * Without the session, native playback outside a playlist can only be
 * identified by search, which routinely cannot separate two uploads of the same
 * thing and then logs nothing. That is a working app producing no entries, and
 * nothing on screen used to say why.
 */
class YouTubeConnectionWarningTest {

	private fun evaluate(
		connected: Boolean = true,
		refusal: WatchHistoryHealth.Refusal? = null,
	) = YouTubeConnectionWarning.evaluate(connected, refusal)

	@Test
	fun `a healthy connected session shows nothing`() {
		assertNull(evaluate(connected = true, refusal = null))
	}

	@Test
	fun `no connected session warns and offers sign-in`() {
		val state = evaluate(connected = false)!!
		assertEquals(YouTubeConnectionWarning.Action.SIGN_IN, state.action)
		assertEquals("Sign in", state.actionLabel)
		assertTrue("the title does not name the missing connection", state.title.isNotBlank())
		assertTrue(
			"the body does not say what is lost: ${state.body}",
			state.body.contains("identified by search") || state.body.contains("search"),
		)
	}

	/**
	 * A stored session that YouTube no longer accepts is a different problem
	 * from never having connected one, and it has a different fix.
	 */
	@Test
	fun `a signed-out session says so and offers sign-in`() {
		val state = evaluate(refusal = WatchHistoryHealth.Refusal.SIGNED_OUT)!!
		assertEquals(YouTubeConnectionWarning.Action.SIGN_IN, state.action)
		assertTrue(state.title.contains("sign", ignoreCase = true))
	}

	@Test
	fun `a paused history says so and points at YouTube's own setting`() {
		val state = evaluate(refusal = WatchHistoryHealth.Refusal.HISTORY_PAUSED)!!
		assertEquals(YouTubeConnectionWarning.Action.OPEN_YOUTUBE_HISTORY, state.action)
		assertTrue(state.title.contains("paused", ignoreCase = true))
		assertTrue(state.body.contains("YouTube", ignoreCase = true))
	}

	/**
	 * The feed proves that what this phone plays is not landing in the history
	 * RustedWax reads. It cannot prove *which* account the YouTube app is using,
	 * and the copy must not pretend otherwise — naming one would be a guess
	 * dressed as a diagnosis.
	 */
	@Test
	fun `a mismatch names the condition without guessing the app's account`() {
		val state = evaluate(refusal = WatchHistoryHealth.Refusal.ACCOUNT_MISMATCH)!!
		assertTrue(
			"the mismatch body does not offer the three causes: ${state.body}",
			state.body.contains("signed out") &&
				state.body.contains("different account") &&
				state.body.contains("incognito"),
		)
		assertFalse(
			"the copy asserts one cause it cannot prove",
			state.body.contains("is signed into a different account."),
		)
	}

	/** A parser break is not fixed by signing in again, so it does not pretend to be. */
	@Test
	fun `an unreadable history page offers no false recovery`() {
		val state = evaluate(refusal = WatchHistoryHealth.Refusal.MARKUP_CHANGED)!!
		assertEquals(YouTubeConnectionWarning.Action.NONE, state.action)
		assertNull(state.actionLabel)
		assertTrue(state.body.contains("guess", ignoreCase = true))
	}

	/**
	 * Not connecting is the louder condition, so it wins: a refusal recorded
	 * against a session that has since been forgotten must not replace "you
	 * have not connected an account" with "your account has a problem".
	 */
	@Test
	fun `a disconnected account outranks a stale refusal`() {
		val state = evaluate(
			connected = false,
			refusal = WatchHistoryHealth.Refusal.HISTORY_PAUSED,
		)!!
		assertEquals(YouTubeConnectionWarning.Action.SIGN_IN, state.action)
	}

	@Test
	fun `every warning carries a title and a body`() {
		val states = buildList {
			add(evaluate(connected = false))
			WatchHistoryHealth.Refusal.entries.forEach { add(evaluate(refusal = it)) }
		}
		states.forEach { state ->
			assertNotNull("a warning state was missing", state)
			assertTrue("blank title", state!!.title.isNotBlank())
			assertTrue("blank body", state.body.isNotBlank())
		}
	}
}
