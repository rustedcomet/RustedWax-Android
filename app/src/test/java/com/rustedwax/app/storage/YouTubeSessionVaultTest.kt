package com.rustedwax.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cookie-jar arithmetic behind the YouTube session, without a device.
 *
 * The storage itself is `EncryptedSharedPreferences` and is not exercised here;
 * what is exercised is the part that decides whether a harvested jar is a
 * *session* at all. Storing a jar with no session cookie and then reporting a
 * successful sign-in would be the worst failure this screen has: a user who
 * believes history is connected and an app that silently resolves nothing.
 */
class YouTubeSessionVaultTest {

	@Test
	fun `parses a cookie header into its pairs`() {
		val parsed = YouTubeSessionVault.parseCookieHeader(
			"PREF=f6%3D40000000; VISITOR_INFO1_LIVE=abc; SID=xyz",
		)
		assertEquals(3, parsed.size)
		assertEquals("xyz", parsed["SID"])
	}

	/** Values contain `=` — a naive split on it loses the tail of every cookie. */
	@Test
	fun `keeps base64 padding inside a cookie value`() {
		val parsed = YouTubeSessionVault.parseCookieHeader("SID=a=b=c")
		assertEquals("a=b=c", parsed["SID"])
	}

	@Test
	fun `a jar with no session cookie is not a sign-in`() {
		assertFalse(
			YouTubeSessionVault.looksSignedIn(
				"PREF=f6%3D40000000; VISITOR_INFO1_LIVE=abc; YSC=def",
			),
		)
		assertFalse(YouTubeSessionVault.looksSignedIn(null))
		assertFalse(YouTubeSessionVault.looksSignedIn(""))
	}

	@Test
	fun `any of the real session cookies counts`() {
		assertTrue(YouTubeSessionVault.looksSignedIn("PREF=x; __Secure-3PSID=y"))
		assertTrue(YouTubeSessionVault.looksSignedIn("SID=y"))
		assertTrue(YouTubeSessionVault.looksSignedIn("LOGIN_INFO=y"))
	}
}
