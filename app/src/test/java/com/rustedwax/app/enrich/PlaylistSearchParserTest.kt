package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Name → playlist id, the native equivalent of the browser address bar.
 *
 * The fixture keeps the `lockupViewModel` shape the real playlist-filtered
 * search page served on 2026-08-04 for `Reggaeton 2016,17,18`: playlist id in
 * `contentId`, title at `metadata.lockupMetadataViewModel.title.content`, owner
 * in the first metadata row, and the `N videos` badge elsewhere in the lockup.
 */
class PlaylistSearchParserTest {

	private fun entry(id: String, title: String, owner: String, videos: Int) =
		"""{"lockupViewModel":{"contentId":"$id",""" +
			""""contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",""" +
			""""metadata":{"lockupMetadataViewModel":{"title":{"content":"$title"},""" +
			""""metadata":{"contentMetadataViewModel":{"metadataRows":[""" +
			"""{"metadataParts":[{"text":{"content":"$owner"}}]}]}}}},""" +
			""""contentImage":{"collectionThumbnailViewModel":{"overlays":""" +
			"""[{"thumbnailBadgeViewModel":{"text":"$videos videos"}}]}}}}"""

	private fun page(vararg entries: String) =
		"""{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":""" +
			"""{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[""" +
			entries.joinToString(",") +
			"""]}}]}}}}}"""

	private val measured = page(
		entry("PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm", "Reggaeton 2016,17,18", "Jhonny Gutierrez", 120),
		entry("PLT9hofZk02ypYdoIf8HopD_OUlOwWjA4g", "Reggaeton Old School Mix", "DJ Nova", 85),
		entry("PLJG8wKD6h6qRl46e9wtjJPiljnHqF9ZaF", "Reggaeton 2016 2017 2018 Hits", "Mix Latino", 46),
	)

	@Test
	fun `reads id title owner and count`() {
		val c = PlaylistSearchParser.candidates(measured)
		assertEquals(3, c.size)
		assertEquals("PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm", c[0].playlistId)
		assertEquals("Reggaeton 2016,17,18", c[0].title)
		assertEquals("Jhonny Gutierrez", c[0].owner)
		assertEquals(120, c[0].videoCount)
	}

	@Test
	fun `the measured playlist resolves to exactly one id`() {
		val c = PlaylistSearchParser.candidates(measured)
		assertEquals(
			"PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm",
			PlaylistSearchParser.match(c, "Reggaeton 2016,17,18", "Jhonny Gutierrez", 120)?.playlistId,
		)
	}

	/** Measured: a generic name produced zero exact-title hits, so it fails closed. */
	@Test
	fun `a generic name that matches nothing exactly refuses`() {
		val c = PlaylistSearchParser.candidates(measured)
		assertNull(PlaylistSearchParser.match(c, "Reggaeton"))
		assertNull(PlaylistSearchParser.match(c, "Workout"))
	}

	@Test
	fun `two playlists with the same exact name refuse`() {
		val c = PlaylistSearchParser.candidates(
			page(
				entry("PLaaaaaaaaaaaaaaaaaa", "Road Trip", "Ann", 20),
				entry("PLbbbbbbbbbbbbbbbbbb", "Road Trip", "Bo", 31),
			),
		)
		assertNull(PlaylistSearchParser.match(c, "Road Trip"))
	}

	@Test
	fun `a contradicting owner rejects the candidate`() {
		val c = PlaylistSearchParser.candidates(measured)
		assertNull(PlaylistSearchParser.match(c, "Reggaeton 2016,17,18", "Someone Else", 120))
	}

	@Test
	fun `a contradicting total rejects the candidate`() {
		val c = PlaylistSearchParser.candidates(measured)
		assertNull(PlaylistSearchParser.match(c, "Reggaeton 2016,17,18", "Jhonny Gutierrez", 7))
	}

	@Test
	fun `owner and total are optional corroboration`() {
		val c = PlaylistSearchParser.candidates(measured)
		assertEquals(
			"PL7NMzffnWK8RMWFO3rZABAgN-Rk9pCkEm",
			PlaylistSearchParser.match(c, "Reggaeton 2016,17,18")?.playlistId,
		)
	}

	@Test
	fun `accents and punctuation normalize`() {
		val c = PlaylistSearchParser.candidates(
			page(entry("PLcccccccccccccccccc", "Música Romántica", "Radio", 12)),
		)
		assertEquals(
			"PLcccccccccccccccccc",
			PlaylistSearchParser.match(c, "Musica Romantica")?.playlistId,
		)
	}

	/** Mixes have no fetchable page of entries, so they never become candidates. */
	@Test
	fun `mix and radio ids are not candidates`() {
		val c = PlaylistSearchParser.candidates(
			page(entry("RDfOT0BUpITw8xx", "Reggaeton 2016,17,18", "YouTube", 50)),
		)
		assertEquals(0, c.size)
	}

	@Test
	fun `an empty name refuses`() {
		val c = PlaylistSearchParser.candidates(measured)
		assertNull(PlaylistSearchParser.match(c, "   "))
	}
}
