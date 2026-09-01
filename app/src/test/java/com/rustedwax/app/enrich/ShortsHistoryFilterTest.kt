package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortsHistoryFilterTest {

	private val chipBar = """
		{"chipBarViewModel":{"chips":[
		 {"chipViewModel":{"text":"All","selected":true,"tapCommand":{"innertubeCommand":
		   {"browseEndpoint":{"browseId":"FEhistory","params":"oggECgIwAQ%3D%3D"}}}}},
		 {"chipViewModel":{"text":"Videos","selected":false,"tapCommand":{"innertubeCommand":
		   {"browseEndpoint":{"browseId":"FEhistory","params":"oggECgIgAQ%3D%3D"}}}}},
		 {"chipViewModel":{"text":"Shorts","selected":false,"tapCommand":{"innertubeCommand":
		   {"browseEndpoint":{"browseId":"FEhistory","params":"oggECgIQAQ%3D%3D"}}}}},
		 {"chipViewModel":{"text":"Music","selected":false,"tapCommand":{"innertubeCommand":
		   {"browseEndpoint":{"browseId":"FEhistory","params":"oggECgIIAQ%3D%3D"}}}}}
		]}}
	""".trimIndent()

	/** The unfiltered feed as it behaves now: ordinary rows, no Shorts. */
	private fun unfilteredFeed() = """
		{"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
		{"videoRenderer":{"videoId":"kJQP7kiw5Fk","title":{"simpleText":"Despacito"},
		 "ownerText":{"runs":[{"text":"Luis Fonsi"}]},"lengthText":{"simpleText":"4:41"}}},
		{"videoRenderer":{"videoId":"60ItHLz5WEA","title":{"simpleText":"Faded"},
		 "ownerText":{"runs":[{"text":"Alan Walker"}]},"lengthText":{"simpleText":"3:32"}}}
		]}}]}},"header":$chipBar}
	""".trimIndent()

	/** The `?bp=` response: Shorts cards only. */
	private val filteredShortsFeed = """
		{"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
		{"shortsLockupViewModel":{"onTap":{"innertubeCommand":{"reelWatchEndpoint":
		  {"videoId":"1UvFBTlS9es"}}},"overlayMetadata":{"primaryText":{"content":"Tyla IS IT"}}}},
		{"shortsLockupViewModel":{"onTap":{"innertubeCommand":{"reelWatchEndpoint":
		  {"videoId":"KMLgYBwzq4s"}}},"overlayMetadata":{"primaryText":{"content":"CELEBRITIES REACT"}}}},
		{"shortsLockupViewModel":{"onTap":{"innertubeCommand":{"reelWatchEndpoint":
		  {"videoId":"S_G4wdaYj_0"}}},"overlayMetadata":{"primaryText":{"content":"Трек в описании"}}}},
		{"shortsLockupViewModel":{"onTap":{"innertubeCommand":{"reelWatchEndpoint":
		  {"videoId":"EhOEVmqSjoE"}}},"overlayMetadata":{"primaryText":{"content":"sin palabras snoop dogg"}}}}
		]}}]}}}
	""".trimIndent()

	// --- The token, read from the page rather than hardcoded ---------------

	@Test
	fun `the Shorts chip's own filter token is recovered from the page`() {
		assertEquals("oggECgIQAQ%3D%3D", WatchHistoryParser.shortsFilterToken(unfilteredFeed()))
	}

	@Test
	fun `the Shorts chip is chosen over the other filters`() {
		val token = WatchHistoryParser.shortsFilterToken(unfilteredFeed())
		// All / Videos / Music tokens must never be selected.
		assertTrue(token != "oggECgIwAQ%3D%3D")
		assertTrue(token != "oggECgIgAQ%3D%3D")
		assertTrue(token != "oggECgIIAQ%3D%3D")
	}

	// --- Fail closed --------------------------------------------------------

	@Test
	fun `a page with no chip bar yields no token`() {
		val noChips = """
			{"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
			{"videoRenderer":{"videoId":"kJQP7kiw5Fk","title":{"simpleText":"Despacito"},
			 "ownerText":{"runs":[{"text":"Luis Fonsi"}]},"lengthText":{"simpleText":"4:41"}}}
			]}}]}}}
		""".trimIndent()
		assertNull(WatchHistoryParser.shortsFilterToken(noChips))
	}

	@Test
	fun `a renamed or missing Shorts chip yields no token`() {
		assertNull(
			WatchHistoryParser.shortsFilterToken(
				"""{"chipViewModel":{"text":"Reels","tapCommand":{"innertubeCommand":
				   {"browseEndpoint":{"browseId":"FEhistory","params":"oggECgIQAQ%3D%3D"}}}}}"""
					.trimIndent(),
			),
		)
	}

	@Test
	fun `a Shorts chip pointing somewhere other than history yields no token`() {
		// A chip that would send the session's cookie at a different feed.
		assertNull(
			WatchHistoryParser.shortsFilterToken(
				"""{"chipViewModel":{"text":"Shorts","tapCommand":{"innertubeCommand":
				   {"browseEndpoint":{"browseId":"FEwhat_to_watch","params":"oggECgIQAQ%3D%3D"}}}}}"""
					.trimIndent(),
			),
		)
	}

	@Test
	fun `malformed json yields no token rather than throwing`() {
		assertNull(WatchHistoryParser.shortsFilterToken("not json"))
		assertNull(WatchHistoryParser.shortsFilterToken(""))
	}

	// --- The filtered response feeds the existing parser unchanged ---------

	@Test
	fun `the filtered response exposes the Shorts that failed to scrobble`() {
		val parsed = WatchHistoryParser.parse(filteredShortsFeed)
		val feed = parsed as WatchHistoryParser.Result.Feed
		assertEquals(
			listOf("1UvFBTlS9es", "KMLgYBwzq4s", "S_G4wdaYj_0", "EhOEVmqSjoE"),
			feed.shorts.map { it.videoId },
		)
		assertEquals("Tyla IS IT", feed.shorts.first().title)
	}

	// --- Ordinary history behaviour is untouched ---------------------------

	@Test
	fun `the unfiltered feed still yields its ordinary entries and no Shorts`() {
		val feed = WatchHistoryParser.parse(unfilteredFeed()) as WatchHistoryParser.Result.Feed
		assertEquals(listOf("kJQP7kiw5Fk", "60ItHLz5WEA"), feed.entries.map { it.videoId })
		assertEquals("Despacito", feed.entries.first().title)
		assertTrue("the unfiltered feed carries no Shorts", feed.shorts.isEmpty())
	}

	@Test
	fun `a feed that still carries Shorts inline is parsed without the filter`() {
		// If YouTube restores inline Shorts, the extra request must not be needed:
		// the resolver only reaches for the filter when this list is empty.
		val inline = """
			{"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
			{"videoRenderer":{"videoId":"kJQP7kiw5Fk","title":{"simpleText":"Despacito"},
			 "ownerText":{"runs":[{"text":"Luis Fonsi"}]},"lengthText":{"simpleText":"4:41"}}},
			{"shortsLockupViewModel":{"onTap":{"innertubeCommand":{"reelWatchEndpoint":
			  {"videoId":"1UvFBTlS9es"}}},"overlayMetadata":{"primaryText":{"content":"Tyla IS IT"}}}}
			]}}]}},"header":$chipBar}
		""".trimIndent()
		val feed = WatchHistoryParser.parse(inline) as WatchHistoryParser.Result.Feed
		assertEquals(listOf("kJQP7kiw5Fk"), feed.entries.map { it.videoId })
		assertEquals(listOf("1UvFBTlS9es"), feed.shorts.map { it.videoId })
	}

	// --- Nothing secret is derivable from what this route logs -------------

	@Test
	fun `the filter token carries no account data`() {
		val token = WatchHistoryParser.shortsFilterToken(unfilteredFeed())!!
		// YouTube's opaque feed selector: base64url plus percent-encoding only.
		assertTrue("token was $token", Regex("""^[A-Za-z0-9_\-%+=.]+$""").matches(token))
		assertTrue(token.length < 64)
	}
}
