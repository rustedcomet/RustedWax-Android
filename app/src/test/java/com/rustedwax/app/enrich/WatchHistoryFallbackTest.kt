package com.rustedwax.app.enrich

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.net.URL

/**
 * The two shapes youtube.com serves for the same feed.
 *
 * A server-rendered page carries the feed in an `ytInitialData` assignment. A
 * client-rendered page carries a shell with no assignment and fetches the feed
 * over InnerTube instead. Both shapes have to keep working, and neither may be
 * inferred from the other.
 */
class WatchHistoryFallbackTest {

	private val feedJson = """
		{"responseContext":{"mainAppWebResponseContext":{"loggedOut":false}},
		 "contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
		   {"videoRenderer":{"videoId":"abcdefghijk",
		     "title":{"runs":[{"text":"A Video"}]},
		     "ownerText":{"runs":[{"text":"A Channel"}]},
		     "lengthText":{"simpleText":"4:49"}}}]}}]}}}
	""".trimIndent()

	/** The legacy path: a real assignment, read exactly as it always was. */
	@Test
	fun `server rendered page still parses`() {
		val page = "<html><script nonce=\"x\">var ytInitialData = $feedJson;</script></html>"
		val blob = WatchPageParser.extractJson(page, "ytInitialData")
		assertNotNull("a real assignment must still be found", blob)
		val parsed = WatchHistoryParser.parse(blob!!)
		assertTrue(parsed is WatchHistoryParser.Result.Feed)
		assertEquals(1, (parsed as WatchHistoryParser.Result.Feed).entries.size)
		assertEquals("abcdefghijk", parsed.entries.first().videoId)
	}

	@Test
	fun `legacy assignment wins without invoking fallback`() = runBlocking {
		val page = "<html><script>var ytInitialData = $feedJson;</script></html>"
		var fallbackCalls = 0
		val blob = WatchHistoryResolver.selectHistoryJson(page) {
			fallbackCalls++
			null
		}
		assertEquals(feedJson, blob)
		assertEquals(0, fallbackCalls)
	}

	/** `window["…"] =` is the same thing spelled differently. */
	@Test
	fun `bracket assignment is also an assignment`() {
		val page = "<html><script>window[\"ytInitialData\"] = $feedJson;</script></html>"
		assertNotNull(WatchPageParser.extractJson(page, "ytInitialData"))
	}

	/**
	 * A client-rendered shell can mention the name in trailing bootstrap code and
	 * assign it nowhere. Extraction must report absence, not brace-match the
	 * surrounding JavaScript.
	 */
	@Test
	fun `bare references are not data`() {
		val shell = """
			<html><body></body>
			<script>(function(a){if(a.ytInitialData){d.loadInitialData(a.ytInitialData)}})(w);</script>
			</html>
		""".trimIndent()
		assertNull(
			"a bare mention must not be read as a feed",
			WatchPageParser.extractJson(shell, "ytInitialData"),
		)
	}

	@Test
	fun `client rendered shell invokes fallback`() = runBlocking {
		val shell = "<script>if (window.ytInitialData) render(window.ytInitialData)</script>"
		var fallbackCalls = 0
		val blob = WatchHistoryResolver.selectHistoryJson(shell) {
			fallbackCalls++
			feedJson
		}
		assertEquals(feedJson, blob)
		assertEquals(1, fallbackCalls)
	}

	@Test
	fun `identifier suffix is not an assignment`() {
		val page = "<script>myvar ytInitialData = $feedJson;</script>"
		assertNull(WatchPageParser.extractJson(page, "ytInitialData"))
	}

	/** A browse response is shaped exactly like the inlined blob. */
	@Test
	fun `authenticated browse response parses unchanged`() {
		val parsed = WatchHistoryParser.parse(feedJson)
		assertTrue(parsed is WatchHistoryParser.Result.Feed)
		assertEquals(1, (parsed as WatchHistoryParser.Result.Feed).entries.size)
	}

	/** HTTP 200 is not consent: `loggedOut` is the verdict. */
	@Test
	fun `logged out browse response refuses`() {
		val signedOut = """
			{"responseContext":{"mainAppWebResponseContext":{"loggedOut":true}},
			 "contents":{"sectionListRenderer":{"contents":[]}}}
		""".trimIndent()
		val parsed = WatchHistoryParser.parse(signedOut)
		assertTrue(parsed is WatchHistoryParser.Result.Unreadable)
		assertEquals(
			WatchHistoryParser.Reason.SIGNED_OUT,
			(parsed as WatchHistoryParser.Result.Unreadable).reason,
		)
	}

	// ── InnerTube handshake ────────────────────────────────────────────────

	@Test
	fun `browse credentials are restricted to the exact origin`() {
		assertTrue(WatchHistoryResolver.isExactOrigin(URL(WatchHistoryResolver.BROWSE_URL)))
		assertTrue(!WatchHistoryResolver.isExactOrigin(URL("https://music.youtube.com/")))
		assertTrue(!WatchHistoryResolver.isExactOrigin(URL("http://www.youtube.com/")))
		assertTrue(!WatchHistoryResolver.isExactOrigin(URL("https://www.youtube.com:444/")))
	}

	private val config = """
		"INNERTUBE_API_KEY":"synthetic-key","INNERTUBE_CLIENT_NAME":"WEB",
		"INNERTUBE_CONTEXT_CLIENT_VERSION":"test-client-version"
	""".trimIndent()

	@Test
	fun `config is read from the page rather than compiled in`() {
		val read = WatchHistoryResolver.innertubeConfig("<html>$config</html>")
		assertNotNull(read)
		assertEquals("WEB", read!!.clientName)
		assertEquals("test-client-version", read.clientVersion)
	}

	/** No key, or no version, means this page cannot support the route. */
	@Test
	fun `missing config fails closed`() {
		assertNull(WatchHistoryResolver.innertubeConfig("<html>nothing</html>"))
		assertNull(
			WatchHistoryResolver.innertubeConfig("""<html>"INNERTUBE_API_KEY":"k"</html>"""),
		)
		assertNull(
			WatchHistoryResolver.innertubeConfig(
				"""<html>"INNERTUBE_CONTEXT_CLIENT_VERSION":"2.0"</html>""",
			),
		)
	}

	// ── Authorization ──────────────────────────────────────────────────────

	@Test
	fun `sapisid hash is built from the cookie the web client uses`() {
		val (name, header) = WatchHistoryResolver.sapisidAuthorization(
			"SID=x; SAPISID=test-secret; LOGIN_INFO=y",
			1_700_000_000L,
		)!!
		assertEquals("SAPISID", name)
		assertEquals(
			"SAPISIDHASH 1700000000_648ba2d6d91516b9ff3e43991c3c40dcf9cb13ea",
			header,
		)
	}

	/** The fallbacks, in the order the web client tries them. */
	@Test
	fun `apisid cookie fallbacks are honoured in order`() {
		assertEquals(
			"__Secure-3PAPISID",
			WatchHistoryResolver.sapisidAuthorization("__Secure-3PAPISID=v", 1L)!!.first,
		)
		assertEquals(
			"__Secure-1PAPISID",
			WatchHistoryResolver.sapisidAuthorization("__Secure-1PAPISID=v", 1L)!!.first,
		)
	}

	/** No APISID cookie means no header — never an unsigned request. */
	@Test
	fun `absent apisid cookie yields no authorization`() {
		assertNull(WatchHistoryResolver.sapisidAuthorization("SID=x; LOGIN_INFO=y", 1L))
		assertNull(WatchHistoryResolver.sapisidAuthorization("SAPISID=", 1L))
	}

	/**
	 * The secret may reach the wire, never the log. The pair exposes the cookie
	 * *name* for diagnostics precisely so nothing has to quote its value.
	 */
	@Test
	fun `authorization never carries the secret in clear`() {
		val (name, header) = WatchHistoryResolver.sapisidAuthorization(
			"SAPISID=test-secret",
			1_700_000_000L,
		)!!
		assertTrue("the cookie value must not appear", !header.contains("test-secret"))
		assertTrue("the name is safe to log", !name.contains("test-secret"))
	}

	@Test
	fun `browse response reading is bounded`() {
		assertEquals("small", WatchHistoryResolver.readBounded(StringReader("small"), 5))
		assertNull(WatchHistoryResolver.readBounded(StringReader("too large"), 5))
	}
}
