package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The playlist path — exact video ids for playlist listening, which is where
 * the address bar fails hardest.
 *
 * The fixture is trimmed from the **real** page for the playlist in the field
 * logs (`PLmGqppZSJ9nXHUioh-WAy7ne8I3nP4FOR`, 2026-07-27), keeping the
 * `lockupViewModel` shape YouTube now serves — id in `contentId`, title at
 * `metadata.lockupMetadataViewModel.title.content`, channel in the next
 * metadata row, duration in a thumbnail badge.
 *
 * The four ids below are precisely the tracks that reached the chain with no
 * `url` in that session.
 */
class PlaylistPageParserTest {

	private fun entry(id: String, title: String, channel: String, length: String) =
		"""{"lockupViewModel":{"contentId":"$id",""" +
			""""contentType":"LOCKUP_CONTENT_TYPE_VIDEO",""" +
			""""metadata":{"lockupMetadataViewModel":{"title":{"content":"$title"},""" +
			""""metadata":{"contentMetadataViewModel":{"metadataRows":[""" +
			"""{"metadataParts":[{"text":{"content":"$channel"}}]},""" +
			"""{"metadataParts":[{"text":{"content":"34M views"}}]}]}}}},""" +
			""""contentImage":{"thumbnailViewModel":{"overlays":""" +
			"""[{"thumbnailBottomOverlayViewModel":{"badges":""" +
			"""[{"thumbnailBadgeViewModel":{"text":"$length"}}]}}]}}}}"""

	private val fixture = """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":""" +
		"""[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":""" +
		"""[{"itemSectionRenderer":{"contents":[""" +
		entry("5Oc0ja19_GU", "Doomed", "Bring Me The Horizon", "4:35") + "," +
		entry(
			"GBRAnuT48qo", "Bring Me The Horizon - Happy Song (Official Audio)",
			"Bring Me The Horizon", "3:57",
		) + "," +
		entry("Ow_qI_F2ZJI", "Bring Me The Horizon - Throne", "Bring Me The Horizon", "3:10") +
		"," +
		entry(
			"BpmJh2CjSIA", "Bring Me The Horizon - True Friends (Official Lyric Video)",
			"Bring Me The Horizon", "3:53",
		) + "," +
		entry("RqQKhSzThyc", "Follow You", "Bring Me The Horizon", "3:52") +
		"""]}}]}}}}]}}}"""

	@Test
	fun `reads the lockupViewModel shape playlists now use`() {
		val e = PlaylistPageParser.entries(fixture)
		assertEquals(5, e.size)
		assertEquals("5Oc0ja19_GU", e[0].videoId)
		assertEquals("Doomed", e[0].title)
		assertEquals("Bring Me The Horizon", e[0].channel)
		assertEquals(275L, e[0].lengthSeconds)
	}

	/**
	 * The whole reason this path exists. Searching "Doomed / Bring Me The
	 * Horizon" resolves `CZFTfYYql4k` — same song, same artist, same length,
	 * but a *different upload* from the one in the playlist. Only the playlist
	 * knows it was `5Oc0ja19_GU`.
	 */
	@Test
	fun `resolves the exact upload the playlist contains`() {
		val hit = PlaylistPageParser.match(
			PlaylistPageParser.entries(fixture),
			title = "Doomed",
			channel = "Bring Me The Horizon - Topic",
			durationSec = 274,
		)
		assertEquals("5Oc0ja19_GU", hit?.videoId)
	}

	/** The four tracks that went on-chain with no url in the field session. */
	@Test
	fun `resolves every track that previously lost its url`() {
		val e = PlaylistPageParser.entries(fixture)
		assertEquals(
			"5Oc0ja19_GU",
			PlaylistPageParser.match(e, "Doomed", "Bring Me The Horizon", 274)?.videoId,
		)
		assertEquals(
			"Ow_qI_F2ZJI",
			PlaylistPageParser.match(
				e, "Bring Me The Horizon - Throne", "Bring Me The Horizon", 190,
			)?.videoId,
		)
		assertEquals(
			"BpmJh2CjSIA",
			PlaylistPageParser.match(
				e, "Bring Me The Horizon - True Friends (Official Lyric Video)",
				"Bring Me The Horizon", 233,
			)?.videoId,
		)
		assertEquals(
			"RqQKhSzThyc",
			PlaylistPageParser.match(e, "Follow You", "Bring Me The Horizon", 232)?.videoId,
		)
	}

	@Test
	fun `a duration that disagrees is not a match`() {
		assertNull(
			PlaylistPageParser.match(
				PlaylistPageParser.entries(fixture),
				"Doomed", "Bring Me The Horizon", 120,
			),
		)
	}

	@Test
	fun `a title not in the playlist is not a match`() {
		assertNull(
			PlaylistPageParser.match(
				PlaylistPageParser.entries(fixture),
				"Some Other Song", "Bring Me The Horizon", 274,
			),
		)
	}

	/** A wrong channel must still lose, even inside a bounded playlist. */
	@Test
	fun `a channel that disagrees is not a match`() {
		assertNull(
			PlaylistPageParser.match(
				PlaylistPageParser.entries(fixture),
				"Doomed", "Maphra - Topic", 274,
			),
		)
	}

	@Test
	fun `no duration means no match`() {
		assertNull(
			PlaylistPageParser.match(
				PlaylistPageParser.entries(fixture),
				"Doomed", "Bring Me The Horizon", null,
			),
		)
	}

	@Test
	fun `a page with no entries yields nothing rather than throwing`() {
		assertEquals(0, PlaylistPageParser.entries("""{"contents":{}}""").size)
	}

	/**
	 * §7.2 rule 8. A playlist can hold the same song twice — two uploads of one
	 * track, or the same track added under a re-upload's id — and the page order
	 * says nothing about which one is playing. Until v0.9.6 `match` returned
	 * `entries.firstOrNull`, so it answered a question it could not answer, and
	 * the coin flip would have gone on an immutable chain.
	 */
	@Test
	fun `two indistinguishable entries in one playlist refuse rather than pick the first`() {
		val doubled = """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":""" +
			"""[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":""" +
			"""[{"itemSectionRenderer":{"contents":[""" +
			entry("4ns8D959YtA", "Criminal", "Natti Natasha", "4:33") + "," +
			entry("VqEbCxg2bNI", "Criminal", "Natti Natasha", "4:33") +
			"""]}}]}}}}]}}}"""

		val e = PlaylistPageParser.entries(doubled)
		assertEquals(2, e.size)
		assertEquals(2, PlaylistPageParser.matches(e, "Criminal", "Natti Natasha", 273).size)
		assertNull(PlaylistPageParser.match(e, "Criminal", "Natti Natasha", 273))
	}

	/** The same title twice is only ambiguous while the durations agree too. */
	@Test
	fun `a same-titled entry of a different length does not make the real one ambiguous`() {
		val doubled = """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":""" +
			"""[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":""" +
			"""[{"itemSectionRenderer":{"contents":[""" +
			entry("4ns8D959YtA", "Criminal", "Natti Natasha", "4:33") + "," +
			entry("VqEbCxg2bNI", "Criminal", "Natti Natasha", "2:10") +
			"""]}}]}}}}]}}}"""

		assertEquals(
			"4ns8D959YtA",
			PlaylistPageParser.match(
				PlaylistPageParser.entries(doubled), "Criminal", "Natti Natasha", 273,
			)?.videoId,
		)
	}
}
