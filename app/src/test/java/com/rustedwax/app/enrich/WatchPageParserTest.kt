package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the fragile half of decision D8.
 *
 * The resolver scrapes an undocumented blob out of a page that can change
 * without notice. These tests are what turns that drift into a failing build
 * rather than a scrobble that quietly stops being enriched — the fixture below
 * is the shape the parser expects, trimmed to the fields actually read.
 */
class WatchPageParserTest {

	private val fixture = """
		<!DOCTYPE html><html><head><title>x</title></head><body>
		<script nonce="abc">var ytInitialPlayerResponse = {"responseContext":{},
		"videoDetails":{"videoId":"ICSCJBc9fkY",
		"title":"【Bring Me The Horizon】I Used to Make Out With Medusa",
		"lengthSeconds":"254","author":"OLD MOON CHILD",
		"shortDescription":"Original song by Bring Me The Horizon\nTabs in the video {not json}"},
		"microformat":{"playerMicroformatRenderer":{"category":"Music",
		"publishDate":"2023-01-01"}}};var meta = {"other":"thing"};</script>
		</body></html>
	""".trimIndent()

	@Test
	fun `extracts the player response from a watch page`() {
		val json = WatchPageParser.extractJson(fixture, "ytInitialPlayerResponse")
		assertNotNull(json)
		val facts = WatchPageParser.parsePlayerResponse("ICSCJBc9fkY", json!!)
		assertEquals("OLD MOON CHILD", facts.author)
		assertEquals("Music", facts.category)
		assertEquals("Bring Me The Horizon", facts.originalArtist)
		assertEquals(254L, facts.lengthSeconds)
	}

	@Test
	fun `extracts exact owner handle only from canonical microformat profile URL`() {
		val facts = WatchPageParser.parsePlayerResponse(
			"s8ZQSxuKPb0",
			"""{"videoDetails":{"videoId":"s8ZQSxuKPb0","title":"The Mask"},
			"microformat":{"playerMicroformatRenderer":{
			"ownerProfileUrl":"http://www.youtube.com/@SonaDarus"}}}""",
		)
		assertEquals("@sonadarus", facts.ownerHandle)
		val hostile = WatchPageParser.parsePlayerResponse(
			"s8ZQSxuKPb0",
			"""{"videoDetails":{"videoId":"s8ZQSxuKPb0"},
			"microformat":{"playerMicroformatRenderer":{
			"ownerProfileUrl":"https://youtube.com.evil.example/@SonaDarus"}}}""",
		)
		assertNull(hostile.ownerHandle)
	}

	/**
	 * `lengthSeconds` does two jobs. It is the duration when the media session
	 * publishes none — 10 shorts were skipped as "no duration" in the 2026-07-29
	 * session while this field sat in a page the app had already fetched. And
	 * its presence is the existence proof that gates the short-clip floor: an
	 * ad creative has no public watch page to read it from.
	 */
	@Test
	fun `a parsed length counts as watch-page proof`() {
		val json = WatchPageParser.extractJson(fixture, "ytInitialPlayerResponse")!!
		assertEquals(true, WatchPageParser.parsePlayerResponse("ICSCJBc9fkY", json).resolvedOnWatchPage)
	}

	@Test
	fun `no videoDetails means no proof and no length`() {
		val facts = WatchPageParser.parsePlayerResponse(
			"ICSCJBc9fkY",
			"""{"responseContext":{},"playabilityStatus":{"status":"ERROR"}}""",
		)
		assertNull(facts.lengthSeconds)
		assertEquals(false, facts.resolvedOnWatchPage)
	}

	/**
	 * The shorts-feed ad from 2026-07-29, with the fields that actually separate
	 * it from a real short. Fetched from the live page: `isUnlisted: true`,
	 * `isCrawlable: false`, `viewCount: 1` — against `false`, `true` and 96229
	 * for a real short from the same feed.
	 */
	@Test
	fun `an unlisted ad creative is not a proven public video`() {
		val facts = WatchPageParser.parsePlayerResponse(
			"CYgQQqvwwsY",
			"""{"videoDetails":{"videoId":"CYgQQqvwwsY","title":"Blurry: Formula única",
			"lengthSeconds":"18","author":"Video ad upload channel","isCrawlable":false,
			"viewCount":"1","shortDescription":""},
			"microformat":{"playerMicroformatRenderer":{"category":"People & Blogs",
			"isUnlisted":true}}}""",
		)
		assertEquals(18L, facts.lengthSeconds)
		assertEquals(true, facts.isUnlisted)
		assertEquals(false, facts.isCrawlable)
		// It resolved — that was never the problem. It just isn't public.
		assertEquals(true, facts.resolvedOnWatchPage)
		assertEquals(false, facts.provenPublicVideo)
	}

	@Test
	fun `a real listed short is a proven public video`() {
		val facts = WatchPageParser.parsePlayerResponse(
			"cq2xXbWGHu8",
			"""{"videoDetails":{"videoId":"cq2xXbWGHu8","lengthSeconds":"39",
			"isCrawlable":true,"viewCount":"96229","shortDescription":""},
			"microformat":{"playerMicroformatRenderer":{"category":"Gaming",
			"isUnlisted":false}}}""",
		)
		assertEquals(true, facts.provenPublicVideo)
	}

	/**
	 * If YouTube renames the field, the gate must close rather than default to
	 * "public" — that default is exactly what let the ad through.
	 */
	@Test
	fun `an absent unlisted flag is null, not false`() {
		val json = WatchPageParser.extractJson(fixture, "ytInitialPlayerResponse")!!
		val facts = WatchPageParser.parsePlayerResponse("ICSCJBc9fkY", json)
		assertNull(facts.isUnlisted)
		assertEquals(true, facts.resolvedOnWatchPage)
		assertEquals(false, facts.provenPublicVideo)
	}

	/**
	 * A zero or unparseable length is absence, not a duration of nothing. The
	 * page still resolved; duration and provenance are deliberately independent.
	 */
	@Test
	fun `a zero length is treated as missing`() {
		val facts = WatchPageParser.parsePlayerResponse(
			"ICSCJBc9fkY",
			"""{"videoDetails":{"lengthSeconds":"0","title":"Live stream"}}""",
		)
		assertNull(facts.lengthSeconds)
		assertEquals(true, facts.resolvedOnWatchPage)
	}

	@Test
	fun `fallback duration alone is not watch page proof`() {
		val facts = VideoFacts(videoId = "ICSCJBc9fkY", lengthSeconds = 20)
		assertEquals(false, facts.resolvedOnWatchPage)
	}

	/**
	 * The description contains braces and the script continues after the blob,
	 * so brace matching has to stop in the right place. A regex to the first
	 * `}` truncates; a regex to the last swallows the next statement.
	 */
	@Test
	fun `brace matching stops at the end of the object`() {
		val json = WatchPageParser.extractJson(fixture, "ytInitialPlayerResponse")!!
		assertEquals('{', json.first())
		assertEquals('}', json.last())
		assertEquals(false, json.contains("var meta"))
	}

	@Test
	fun `reports nothing when the marker is missing`() {
		assertNull(WatchPageParser.extractJson("<html>nothing here</html>", "ytInitialPlayerResponse"))
	}

	// region album
	//
	// `album` has been a payload field since Phase 0 and nothing populated it.
	// Auto-generated descriptions have a fixed shape, which is the only reason
	// it's safe to read the line after the credits.

	private val topicDescription = """
		Provided to YouTube by Universal Music Group

		Trash · Korn

		Untouchables

		℗ 2002 Epic Records
	""".trimIndent()

	@Test
	fun `mines the album from an auto-generated description`() {
		assertEquals("Untouchables", WatchPageParser.mineAlbum(topicDescription))
	}

	/** A track with no album puts the ℗ line where the album would be. */
	@Test
	fun `does not mistake the rights line for an album`() {
		assertNull(
			WatchPageParser.mineAlbum(
				"Provided to YouTube by Distributor\n\nSong · Artist\n\n℗ 2019 Some Label",
			),
		)
	}

	/**
	 * Hand-written descriptions have no fixed shape, so the line after any credit
	 * is prose. Inventing an album from prose would write nonsense on-chain.
	 */
	@Test
	fun `never guesses an album from a hand-written description`() {
		assertNull(WatchPageParser.mineAlbum("Original song by Bring Me The Horizon\n\nEnjoy!"))
		assertNull(WatchPageParser.mineAlbum("Thanks for watching! Subscribe for more."))
		assertNull(WatchPageParser.mineAlbum(""))
	}

	@Test
	fun `the album reaches VideoFacts`() {
		val facts = WatchPageParser.parsePlayerResponse(
			"abcdefghijk",
			"""{"videoDetails":{"lengthSeconds":"240","shortDescription":
			"Provided to YouTube by UMG\n\nTrash · Korn\n\nUntouchables\n\n℗ 2002"}}""",
		)
		assertEquals("Untouchables", facts.album)
		assertEquals(true, facts.autoGenerated)
	}
	// endregion

	@Test
	fun `mines auto-generated topic credits`() {
		val credits = WatchPageParser.mineDescription(
			"Provided to YouTube by Universal Music Group\n\nTrash · Korn\n\nUntouchables",
		)
		assertEquals("Korn", credits?.first)
		assertEquals("Trash", credits?.second)
	}

	@Test
	fun `mines hand-written cover credits`() {
		assertEquals(
			"Bring Me The Horizon",
			WatchPageParser.mineDescription("Original song by Bring Me The Horizon")?.first,
		)
		assertEquals(
			"Slipknot",
			WatchPageParser.mineDescription("Cover I did.\nOriginally by Slipknot\nEnjoy")?.first,
		)
		assertEquals(
			"Korn",
			WatchPageParser.mineDescription("Artist: Korn\nAlbum: Untouchables")?.first,
		)
	}

	/**
	 * Free prose must yield nothing. Half-guessing an artist here writes
	 * confident nonsense to a chain that can't be edited.
	 */
	@Test
	fun `leaves prose alone`() {
		assertNull(WatchPageParser.mineDescription("Thanks for watching! Subscribe for more."))
		assertNull(WatchPageParser.mineDescription(""))
	}
}
