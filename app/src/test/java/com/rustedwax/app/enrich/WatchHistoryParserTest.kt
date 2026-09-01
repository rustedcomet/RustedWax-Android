package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch-history feed, which is the only surface that names an exact video
 * id for native playback outside a playlist.
 *
 * The signed-out shape is taken from the **real** page measured on 2026-08-04
 * (`http=200 bytes=770396`, `responseContext.mainAppWebResponseContext.loggedOut
 * = true`, zero `videoRenderer` nodes, a `messageRenderer` reading "Keep track
 * of what you watch" with a "Sign in" button). The signed-in shape uses the
 * ordinary `videoRenderer` the search page serves, which is what the feed is
 * built from; the live authenticated markup is checked in the field test rather
 * than assumed here.
 */
class WatchHistoryParserTest {

	private fun row(id: String, title: String, channel: String, length: String) =
		"""{"videoRenderer":{"videoId":"$id","title":{"runs":[{"text":"$title"}]},
		"longBylineText":{"runs":[{"text":"$channel"}]},
		"lengthText":{"simpleText":"$length"}}}"""

	private fun feed(vararg rows: String, loggedOut: Boolean = false) =
		"""{"responseContext":{"mainAppWebResponseContext":{"loggedOut":$loggedOut}},
		"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":
		{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
		${rows.joinToString(",")}
		]}}]}}}}]}}}"""

	private val today = feed(
		row("4ns8D959YtA", "Criminal", "Natti Natasha - Topic", "4:33"),
		row("YbZwlNmnUvw", "Unica", "Ozuna - Topic", "3:38"),
		row("7J6xA1_f8as", "Te Busco", "Cosculluela - Topic", "3:54"),
	)

	@Test
	fun `reads the feed newest first`() {
		val result = WatchHistoryParser.parse(today)
		assertTrue(result is WatchHistoryParser.Result.Feed)
		val entries = (result as WatchHistoryParser.Result.Feed).entries
		assertEquals(3, entries.size)
		assertEquals("4ns8D959YtA", entries[0].videoId)
		assertEquals("Criminal", entries[0].title)
		assertEquals("Natti Natasha - Topic", entries[0].channel)
		assertEquals(273L, entries[0].lengthSeconds)
		assertEquals("7J6xA1_f8as", entries[2].videoId)
	}

	/** Order is the whole point, so it comes from arrays and two sections keep it. */
	@Test
	fun `entries keep their order across day sections`() {
		val twoSections =
			"""{"responseContext":{"mainAppWebResponseContext":{"loggedOut":false}},
			"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":
			{"sectionListRenderer":{"contents":[
			{"itemSectionRenderer":{"contents":[${row("aaaaaaaaaaa", "Today song", "A", "3:00")}]}},
			{"itemSectionRenderer":{"contents":[${row("bbbbbbbbbbb", "Older song", "B", "3:00")}]}}
			]}}}}]}}}"""
		val entries = (WatchHistoryParser.parse(twoSections) as WatchHistoryParser.Result.Feed)
			.entries
		assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), entries.map { it.videoId })
	}

	/**
	 * The measured signed-out answer. It must be distinguishable from an empty
	 * history: only one of the two is something the user can fix, and guessing
	 * between them is how a dead session becomes a silent failure.
	 */
	@Test
	fun `a signed-out feed says so exactly`() {
		val result = WatchHistoryParser.parse(feed(loggedOut = true))
		assertTrue(result is WatchHistoryParser.Result.Unreadable)
		assertEquals(
			WatchHistoryParser.Reason.SIGNED_OUT,
			(result as WatchHistoryParser.Result.Unreadable).reason,
		)
	}

	@Test
	fun `a paused history is not reported as an empty one`() {
		val paused =
			"""{"responseContext":{"mainAppWebResponseContext":{"loggedOut":false}},
			"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":
			{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
			{"messageRenderer":{"text":{"runs":[{"text":"Turn on watch history"}]}}}
			]}}]}}}}]}}}"""
		val result = WatchHistoryParser.parse(paused) as WatchHistoryParser.Result.Unreadable
		assertEquals(WatchHistoryParser.Reason.HISTORY_PAUSED, result.reason)
	}

	@Test
	fun `a live session with nothing watched is empty rather than broken`() {
		val result = WatchHistoryParser.parse(feed()) as WatchHistoryParser.Result.Unreadable
		assertEquals(WatchHistoryParser.Reason.EMPTY, result.reason)
	}

	/** Breakage must be distinguishable from absence — decision D8. */
	@Test
	fun `a document without the shape this reads reports markup change`() {
		val result = WatchHistoryParser.parse("""{"contents":{}}""")
			as WatchHistoryParser.Result.Unreadable
		assertEquals(WatchHistoryParser.Reason.MARKUP_CHANGED, result.reason)
	}

	/**
	 * Playlist pages dropped `playlistVideoRenderer` for `lockupViewModel` and
	 * the symptom of not following was indistinguishable from an empty page. If
	 * the history feed makes the same move, it must not read as "you have
	 * watched nothing".
	 */
	@Test
	fun `reads the lockupViewModel shape playlists already migrated to`() {
		val lockup = """{"responseContext":{"mainAppWebResponseContext":{"loggedOut":false}},
			"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":
			{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
			{"lockupViewModel":{"contentId":"4ns8D959YtA",
			"metadata":{"lockupMetadataViewModel":{"title":{"content":"Criminal"},
			"metadata":{"contentMetadataViewModel":{"metadataRows":[
			{"metadataParts":[{"text":{"content":"Natti Natasha"}}]},
			{"metadataParts":[{"text":{"content":"119M views"}}]}]}}}},
			"contentImage":{"thumbnailViewModel":{"overlays":[{"thumbnailBottomOverlayViewModel":
			{"badges":[{"thumbnailBadgeViewModel":{"text":"4:33"}}]}}]}}}}
			]}}]}}}}]}}}"""
		val entries = (WatchHistoryParser.parse(lockup) as WatchHistoryParser.Result.Feed).entries
		assertEquals("4ns8D959YtA", entries.single().videoId)
		assertEquals("Criminal", entries.single().title)
		assertEquals("Natti Natasha", entries.single().channel)
		assertEquals(273L, entries.single().lengthSeconds)
	}

	/** Home-feed style wrapping must not hide an ordinary row. */
	@Test
	fun `a row wrapped in a richItemRenderer is still read`() {
		val wrapped = """{"responseContext":{"mainAppWebResponseContext":{"loggedOut":false}},
			"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":
			{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
			{"richItemRenderer":{"content":${row("YbZwlNmnUvw", "Unica", "Ozuna - Topic", "3:38")}}}
			]}}]}}}}]}}}"""
		val entries = (WatchHistoryParser.parse(wrapped) as WatchHistoryParser.Result.Feed).entries
		assertEquals("YbZwlNmnUvw", entries.single().videoId)
	}

	/**
	 * A foreground Short knows its owner by `@handle`; the feed writes a display
	 * name. The handle is taken from the byline's own canonical link, so a
	 * display name that merely looks like a handle can never be read as one.
	 */
	@Test
	fun `reads the owner handle from the byline's canonical link`() {
		val withHandle = """{"responseContext":{"mainAppWebResponseContext":{"loggedOut":false}},
			"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":
			{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
			{"videoRenderer":{"videoId":"M3Ax-bwXRgs","title":{"runs":[{"text":"Montagem"}]},
			"longBylineText":{"runs":[{"text":"Subhan Edits","navigationEndpoint":
			{"browseEndpoint":{"canonicalBaseUrl":"/@subhan-edits"}}}]}}}
			]}}]}}}}]}}}"""
		val entry = (WatchHistoryParser.parse(withHandle) as WatchHistoryParser.Result.Feed)
			.entries.single()
		assertEquals("@subhan-edits", entry.ownerHandle)
		assertEquals("Subhan Edits", entry.channel)
	}

	@Test
	fun `a byline linking to a channel id yields no handle rather than a guess`() {
		val noHandle = """{"responseContext":{"mainAppWebResponseContext":{"loggedOut":false}},
			"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":
			{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
			{"videoRenderer":{"videoId":"M3Ax-bwXRgs","title":{"runs":[{"text":"Montagem"}]},
			"longBylineText":{"runs":[{"text":"@notreallyahandle","navigationEndpoint":
			{"browseEndpoint":{"canonicalBaseUrl":"/channel/UC123"}}}]}}}
			]}}]}}}}]}}}"""
		val entry = (WatchHistoryParser.parse(noHandle) as WatchHistoryParser.Result.Feed)
			.entries.single()
		assertNull(entry.ownerHandle)
	}

	/**
	 * The cause of the reported "Shorts are being skipped". A Short is not a
	 * `videoRenderer` at all — measured 2026-08-05, a public search page served
	 * 26 `shortsLockupViewModel` cards and zero `videoRenderer` — so a
	 * Shorts-only viewing session left the ordinary list unchanged and every
	 * Short refused.
	 */
	@Test
	fun `reads Shorts, and keeps them out of the ordinary entry list`() {
		val withShorts = """{"responseContext":{"mainAppWebResponseContext":{"loggedOut":false}},
			"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":
			{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
			{"shortsLockupViewModel":{"entityId":"shorts-shelf-item-M3Ax-bwXRgs",
			"onTap":{"innertubeCommand":{"reelWatchEndpoint":{"videoId":"M3Ax-bwXRgs"}}},
			"overlayMetadata":{"primaryText":{"content":"Montagem Guerreiro #edit"},
			"secondaryText":{"content":"1.2M views"}}}},
			${row("4ns8D959YtA", "Criminal", "Natti Natasha - Topic", "4:33")}
			]}}]}}}}]}}}"""
		val feed = WatchHistoryParser.parse(withShorts) as WatchHistoryParser.Result.Feed

		// The ordinary entry is untouched and still first in its own list, so a
		// Short can never push a matchable video out of the recent window.
		assertEquals(listOf("4ns8D959YtA"), feed.entries.map { it.videoId })
		assertEquals(listOf("M3Ax-bwXRgs"), feed.shorts.map { it.videoId })
		assertEquals("Montagem Guerreiro #edit", feed.shorts.single().title)
	}

	/** A feed holding only Shorts is not an empty feed. */
	@Test
	fun `a Shorts-only feed is not reported as empty`() {
		val onlyShorts = """{"responseContext":{"mainAppWebResponseContext":{"loggedOut":false}},
			"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":
			{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
			{"shortsLockupViewModel":{
			"onTap":{"innertubeCommand":{"reelWatchEndpoint":{"videoId":"M3Ax-bwXRgs"}}},
			"overlayMetadata":{"primaryText":{"content":"Montagem"}}}}
			]}}]}}}}]}}}"""
		val feed = WatchHistoryParser.parse(onlyShorts)
		assertTrue(feed is WatchHistoryParser.Result.Feed)
		assertTrue((feed as WatchHistoryParser.Result.Feed).entries.isEmpty())
		assertEquals(1, feed.shorts.size)
	}

	@Test
	fun `a row without a parseable length still yields its id`() {
		val noLength = """{"responseContext":{"mainAppWebResponseContext":{"loggedOut":false}},
			"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":
			{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
			{"videoRenderer":{"videoId":"4ns8D959YtA","title":{"runs":[{"text":"Criminal"}]},
			"longBylineText":{"runs":[{"text":"Natti Natasha"}]},
			"thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":
			{"text":{"simpleText":"4:33"}}}]}}
			]}}]}}}}]}}}"""
		val entries = (WatchHistoryParser.parse(noLength) as WatchHistoryParser.Result.Feed).entries
		assertEquals(273L, entries.single().lengthSeconds)
	}
}
