package com.rustedwax.app.enrich

import com.rustedwax.youtube.identity.VideoResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicCatalogSearchParserTest {

	/** An unfiltered response: `Song • Artist`, no album, no running time. */
	private val unfiltered = """
		{
		  "contents": [{"musicResponsiveListItemRenderer": {
		    "flexColumns": [
		      {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
		        {"text": "Buss It Open", "navigationEndpoint": {"watchEndpoint": {
		          "videoId": "nrHp1qUcuv0", "watchEndpointMusicSupportedConfigs": {
		            "watchEndpointMusicConfig": {"musicVideoType": "MUSIC_VIDEO_TYPE_ATV"}
		          }
		        }}}
		      ]}}},
		      {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
		        {"text": "Song"}, {"text": " • "},
		        {"text": "Mr. Vegas", "navigationEndpoint": {"browseEndpoint": {
		          "browseId": "UCVrkxPNZb4ZAYKHJumyZmTw",
		          "browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {
		            "pageType": "MUSIC_PAGE_TYPE_ARTIST"
		          }}
		        }}}
		      ]}}}
		    ],
		    "playlistItemData": {"videoId": "nrHp1qUcuv0"}
		  }}]
		}
	""".trimIndent()

	private fun songRow(
		videoId: String,
		title: String,
		artists: List<String>,
		album: String,
		duration: String,
		albumBrowseId: String = "MPREb_test_album",
		musicVideoType: String = "MUSIC_VIDEO_TYPE_ATV",
	): String {
		val artistRuns = artists.joinToString(""", {"text": ", "}, """) { name ->
			"""{"text": "$name", "navigationEndpoint": {"browseEndpoint": {
				"browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {
				  "pageType": "MUSIC_PAGE_TYPE_ARTIST"}}}}}"""
		}
		return """
			{"musicResponsiveListItemRenderer": {
			  "flexColumns": [
			    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
			      {"text": "$title", "navigationEndpoint": {"watchEndpoint": {
			        "videoId": "$videoId", "watchEndpointMusicSupportedConfigs": {
			          "watchEndpointMusicConfig": {"musicVideoType": "$musicVideoType"}}}}}
			    ]}}},
			    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
			      $artistRuns,
			      {"text": " • "},
			      {"text": "$album", "navigationEndpoint": {"browseEndpoint": {
			        "browseId": "$albumBrowseId",
			        "browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {
			          "pageType": "MUSIC_PAGE_TYPE_ALBUM"}}}}},
			      {"text": " • "},
			      {"text": "$duration"}
			    ]}}}
			  ],
			  "playlistItemData": {"videoId": "$videoId"}
			}}
		""".trimIndent()
	}

	/** A row whose byline artist YouTube did **not** hyperlink. */
	private fun unlinkedSongRow(
		videoId: String,
		title: String,
		bylineArtist: String,
		album: String,
		duration: String,
	) = """
		{"musicResponsiveListItemRenderer": {
		  "flexColumns": [
		    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
		      {"text": "$title", "navigationEndpoint": {"watchEndpoint": {
		        "videoId": "$videoId", "watchEndpointMusicSupportedConfigs": {
		          "watchEndpointMusicConfig": {"musicVideoType": "MUSIC_VIDEO_TYPE_ATV"}}}}}
		    ]}}},
		    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
		      {"text": "$bylineArtist"},
		      {"text": " \u2022 "},
		      {"text": "$album", "navigationEndpoint": {"browseEndpoint": {
		        "browseId": "MPREb_unlinked",
		        "browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {
		          "pageType": "MUSIC_PAGE_TYPE_ALBUM"}}}}},
		      {"text": " \u2022 "},
		      {"text": "$duration"}
		    ]}}}
		  ],
		  "playlistItemData": {"videoId": "$videoId"}
		}}
	""".trimIndent()

	private fun response(vararg rows: String) = """{"contents": [${rows.joinToString(",")}]}"""

	@Test
	fun `measured art-track result exposes exact catalog identity`() {
		val candidate = YouTubeMusicCatalogSearchParser.candidates(unfiltered).single()

		assertEquals("nrHp1qUcuv0", candidate.videoId)
		assertEquals("Buss It Open", candidate.title)
		assertEquals(listOf("Mr. Vegas"), candidate.artists)
		assertEquals("MUSIC_VIDEO_TYPE_ATV", candidate.musicVideoType)
		assertNull(candidate.album)
		assertNull(candidate.durationSeconds)
		assertTrue(
			YouTubeMusicCatalogSearchParser.matches(
				candidate, nativeTitle = "Buss It Open", nativeArtist = "Mr. Vegas",
			),
		)
	}

	@Test
	fun `podcast catalog rows never become music identity`() {
		val podcast = unfiltered
			.replace("nrHp1qUcuv0", "rUl1tYXyrys")
			.replace("Buss It Open", "An episode")
			.replace("Mr. Vegas", "A Podcast")
			.replace("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_PODCAST_EPISODE")
		val candidate = YouTubeMusicCatalogSearchParser.candidates(podcast).single()

		assertFalse(
			YouTubeMusicCatalogSearchParser.matches(
				candidate, nativeTitle = "An episode", nativeArtist = "A Podcast",
			),
		)
	}

	@Test
	fun `bootstrap config is read from the live shell fields without fixed credentials`() {
		val shell = """{"INNERTUBE_API_KEY":"public-key","INNERTUBE_CLIENT_VERSION":"1.2.3","INNERTUBE_CONTEXT_CLIENT_NAME":67}"""

		assertEquals(
			YouTubeMusicCatalogSearchParser.Config("public-key", "1.2.3", "67"),
			YouTubeMusicCatalogSearchParser.config(shell),
		)
	}

	@Test
	fun `songs-filtered rows carry the album and running time that separate duplicate art tracks`() {
		val rows = YouTubeMusicCatalogSearchParser.candidates(
			response(
				songRow("oRuSuMag9CU", "Jah Jah City", listOf("Capleton"), "Reggae Gold 1999", "3:37"),
				songRow(
					"-uQ--ieyL-4", "Jah Jah City", listOf("Capleton"),
					"The Very Best of Capleton Gold", "3:34",
				),
			),
		)

		assertEquals(listOf("oRuSuMag9CU", "-uQ--ieyL-4"), rows.map { it.videoId })
		assertEquals("Reggae Gold 1999", rows[0].album)
		assertEquals("MPREb_test_album", rows[0].albumBrowseId)
		assertEquals(217L, rows[0].durationSeconds)
		assertEquals(214L, rows[1].durationSeconds)

		// Both are the same work by the same artist; only the album tells them apart.
		rows.forEach {
			assertTrue(YouTubeMusicCatalogSearchParser.matches(it, "Jah Jah City", "Capleton"))
		}
		assertTrue(NativeStructuredMusicMatcher.albumsAgree(rows[0].album, "Reggae Gold 1999"))
		assertFalse(NativeStructuredMusicMatcher.albumsAgree(rows[1].album, "Reggae Gold 1999"))
	}

	@Test
	fun `album page rows expose the exact track credit duration and album`() {
		val albumPage = """
			{"contents": [{"musicResponsiveListItemRenderer": {
			  "flexColumns": [
			    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
			      {"text": "Diamonds and Gold", "navigationEndpoint": {"watchEndpoint": {
			        "videoId": "c-qLLolHJLc", "watchEndpointMusicSupportedConfigs": {
			          "watchEndpointMusicConfig": {"musicVideoType": "MUSIC_VIDEO_TYPE_ATV"}}}}}
			    ]}}},
			    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
			      {"text": "Marlon Asher", "navigationEndpoint": {"browseEndpoint": {
			        "browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {
			          "pageType": "MUSIC_PAGE_TYPE_ARTIST"}}}}},
			      {"text": ", "},
			      {"text": "Tarrus Riley", "navigationEndpoint": {"browseEndpoint": {
			        "browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {
			          "pageType": "MUSIC_PAGE_TYPE_ARTIST"}}}}},
			      {"text": " & "},
			      {"text": "Capleton", "navigationEndpoint": {"browseEndpoint": {
			        "browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {
			          "pageType": "MUSIC_PAGE_TYPE_ARTIST"}}}}}
			    ]}}}
			  ],
			  "fixedColumns": [{"musicResponsiveListItemFixedColumnRenderer": {"text": {"runs": [
			    {"text": "3:40"}
			  ]}}}],
			  "playlistItemData": {"videoId": "c-qLLolHJLc"}
			}}]}
		""".trimIndent()

		val row = YouTubeMusicCatalogSearchParser.albumCandidates(albumPage, "Safe").single()

		assertEquals("c-qLLolHJLc", row.videoId)
		assertEquals("Diamonds and Gold", row.title)
		assertEquals(listOf("Marlon Asher", "Tarrus Riley", "Capleton"), row.artists)
		assertEquals("Safe", row.album)
		assertEquals(220L, row.durationSeconds)
		assertTrue(
			YouTubeMusicCatalogSearchParser.matches(
				row, "Diamonds and Gold", "Marlon Asher, Tarrus Riley & Capleton",
			),
		)
	}

	/**
	 * The defect this fix exists for: the player joins a collaboration into one
	 * credit string, the catalog splits it, and the old one-artist-at-a-time test
	 * could never match either way round.
	 */
	@Test
	fun `a collaboration matches whichever order the catalog lists its artists in`() {
		val row = YouTubeMusicCatalogSearchParser.candidates(
			response(
				songRow(
					"0INJOMzG0rM", "So High",
					listOf("Mr. Vegas", "Lizi", "Walshy Fire"), "So High", "2:48",
				),
			),
		).single()

		assertTrue(
			YouTubeMusicCatalogSearchParser.matches(
				row, nativeTitle = "So High", nativeArtist = "Walshy Fire, Lizi & Mr. Vegas",
			),
		)
		assertTrue(
			YouTubeMusicCatalogSearchParser.matches(
				row, nativeTitle = "So High", nativeArtist = "Mr. Vegas, Lizi & Walshy Fire",
			),
		)
	}

	@Test
	fun `an incomplete credit is not the same act`() {
		val row = YouTubeMusicCatalogSearchParser.candidates(
			response(songRow("w000000000f", "Woof", listOf("Vybz Kartel", "Ishawna"), "Woof", "2:19")),
		).single()

		// The fix: the joined player credit now matches the split catalog credit.
		assertTrue(
			YouTubeMusicCatalogSearchParser.matches(row, "Woof", "Vybz Kartel & Ishawna"),
		)
		// A *different* collaboration on the same work still does not.
		assertFalse(
			YouTubeMusicCatalogSearchParser.matches(row, "Woof", "Vybz Kartel & Spice"),
		)
		assertFalse(
			YouTubeMusicCatalogSearchParser.matches(row, "Woof", "Ishawna & Spice"),
		)
		// The catalog row publishes a complete linked credit. One member alone is
		// not that credit, even when album and duration happen to agree.
		assertFalse(YouTubeMusicCatalogSearchParser.matches(row, "Woof", "Vybz Kartel"))
	}

	/** `Capleton & Derrick Sound` is one act, and splitting it would be the mirror bug. */
	@Test
	fun `an artist whose own name contains a separator still matches whole`() {
		val row = YouTubeMusicCatalogSearchParser.candidates(
			response(
				songRow(
					"d3rr1cks000", "Bun Dung Dreddie",
					listOf("Capleton & Derrick Sound"), "Dubplate", "3:19",
				),
			),
		).single()

		assertTrue(
			YouTubeMusicCatalogSearchParser.matches(
				row, "Bun Dung Dreddie", "Capleton & Derrick Sound",
			),
		)
	}

	@Test
	fun `running times parse in both rendered shapes and reject anything else`() {
		assertEquals(217L, YouTubeMusicCatalogSearchParser.durationSeconds("3:37"))
		assertEquals(3731L, YouTubeMusicCatalogSearchParser.durationSeconds("1:02:11"))
		assertNull(YouTubeMusicCatalogSearchParser.durationSeconds("1:99:00"))
		assertNull(YouTubeMusicCatalogSearchParser.durationSeconds("13M plays"))
		assertNull(YouTubeMusicCatalogSearchParser.durationSeconds("Song"))
		assertNull(YouTubeMusicCatalogSearchParser.durationSeconds("3:77"))
		assertNull(YouTubeMusicCatalogSearchParser.durationSeconds(null))
	}

	@Test
	fun `an absent album on either side is never a mismatch`() {
		assertTrue(NativeStructuredMusicMatcher.albumsAgree(null, "More Fire"))
		assertTrue(NativeStructuredMusicMatcher.albumsAgree("More Fire", null))
		assertTrue(NativeStructuredMusicMatcher.albumsAgree(null, null))
		assertFalse(NativeStructuredMusicMatcher.albumsAgree("More Fire", "Reign Of Fire"))
	}

	@Test
	fun `explicit album and duration contradictions cannot be restored after narrowing`() {
		val rows = YouTubeMusicCatalogSearchParser.candidates(
			response(
				songRow(
					"oRuSuMag9CU", "Jah Jah City", listOf("Capleton"),
					"Reggae Gold 1999", "3:37",
				),
				songRow(
					"-uQ--ieyL-4", "Jah Jah City", listOf("Capleton"),
					"The Very Best of Capleton Gold", "3:34",
				),
			),
		)

		assertTrue(
			VideoIdResolver.narrowYouTubeMusicCatalogCandidates(
				rows, nativeAlbum = "A release absent from these results", durationSec = 217,
			).isEmpty(),
		)
		assertTrue(
			VideoIdResolver.narrowYouTubeMusicCatalogCandidates(
				rows, nativeAlbum = "Reggae Gold 1999", durationSec = 400,
			).isEmpty(),
		)
	}

	@Test
	fun `card authority requires complete catalog fields and never overrules a page contradiction`() {
		val row = YouTubeMusicCatalogSearchParser.candidates(
			response(
				songRow(
					"0INJOMzG0rM", "So High",
					listOf("Mr. Vegas", "Lizi", "Walshy Fire"), "So High", "2:48",
				),
			),
		).single()
		val agreeingPage = VideoResolution(
			videoId = row.videoId,
			source = "canonical page",
			title = "So High",
			channel = "Mr. Vegas - Topic",
			lengthSeconds = 168,
		)

		assertNotNull(
			VideoIdResolver.cardBackedYouTubeMusicResolution(
				row, fetchedPage = null, nativeTitle = "So High",
				nativeAlbum = "So High", durationSec = 168,
			),
		)
		assertNotNull(
			VideoIdResolver.cardBackedYouTubeMusicResolution(
				row, agreeingPage, "So High", "So High", 168,
			),
		)
		assertNull(
			VideoIdResolver.cardBackedYouTubeMusicResolution(
				row, agreeingPage.copy(title = "A different work"),
				"So High", "So High", 168,
			),
		)
		assertNull(
			VideoIdResolver.cardBackedYouTubeMusicResolution(
				row.copy(album = "A different release"), fetchedPage = null,
				nativeTitle = "So High", nativeAlbum = "So High", durationSec = 168,
			),
		)
	}

	@Test
	fun `card authority still applies to a single the player published no album for`() {
		val row = YouTubeMusicCatalogSearchParser.candidates(
			response(songRow("_Fpin7y9gTM", "Wine Pon It", listOf("Munga"), "Wine Pon It", "3:09")),
		).single()

		assertNotNull(
			VideoIdResolver.cardBackedYouTubeMusicResolution(
				row, fetchedPage = null, nativeTitle = "Wine Pon It",
				nativeAlbum = null, durationSec = 188,
			),
		)
		// A published album that disagrees is still a veto.
		assertNull(
			VideoIdResolver.cardBackedYouTubeMusicResolution(
				row, fetchedPage = null, nativeTitle = "Wine Pon It",
				nativeAlbum = "Some other release", durationSec = 188,
			),
		)
	}

	@Test
	fun `a page naming a different alias of the same act still corroborates`() {
		val row = YouTubeMusicCatalogSearchParser.candidates(
			response(songRow("_Fpin7y9gTM", "Wine Pon It", listOf("Munga"), "Wine Pon It", "3:09")),
		).single()
		val aliasPage = VideoResolution(
			videoId = row.videoId,
			source = "canonical page",
			title = "Wine Pon It",
			channel = "Munga Honorable - Topic",
			lengthSeconds = 189,
		)

		assertNotNull(
			VideoIdResolver.cardBackedYouTubeMusicResolution(
				row, aliasPage, "Wine Pon It", null, 188,
			),
		)
		// The work and the length are still hard requirements.
		assertNull(
			VideoIdResolver.cardBackedYouTubeMusicResolution(
				row, aliasPage.copy(title = "A different work"), "Wine Pon It", null, 188,
			),
		)
		assertNull(
			VideoIdResolver.cardBackedYouTubeMusicResolution(
				row, aliasPage.copy(lengthSeconds = 400), "Wine Pon It", null, 188,
			),
		)
	}

	@Test
	fun `a row whose artist is not hyperlinked is still read from its byline`() {
		val row = YouTubeMusicCatalogSearchParser.candidates(
			response(unlinkedSongRow("h7YAywGQ_n8", "Live n' Learn", "450", "Live n' Learn", "2:42")),
		).single()

		assertEquals("h7YAywGQ_n8", row.videoId)
		assertEquals(listOf("450"), row.artists)
		assertEquals(162L, row.durationSeconds)
		assertEquals("Live n' Learn", row.album)
		assertTrue(YouTubeMusicCatalogSearchParser.matches(row, "Live n' Learn", "450"))
	}

	/** The byline credit is kept whole so `creditsAgree` compares the whole string first. */
	@Test
	fun `an unlinked collaboration keeps the credit exactly as the byline wrote it`() {
		val long = "Di Genius, Bounty Killer, Bling Dawg, Wayne Marshall, Mavado, and Busy Signal"
		val row = YouTubeMusicCatalogSearchParser.candidates(
			response(
				unlinkedSongRow(
					"TGZA0_vsQEE", "Keep It Gangster", long,
					"Di Genius Presents... The Recovered Files", "3:28",
				),
			),
		).single()

		assertEquals(listOf(long), row.artists)
		assertTrue(YouTubeMusicCatalogSearchParser.matches(row, "Keep It Gangster", long))
		// A different act on the same work still does not match.
		assertFalse(
			YouTubeMusicCatalogSearchParser.matches(row, "Keep It Gangster", "Someone Else"),
		)
	}

	/** Linked artist entities remain the preferred evidence when present. */
	@Test
	fun `linked artist entities still win over the byline text`() {
		val row = YouTubeMusicCatalogSearchParser.candidates(
			response(songRow("oRuSuMag9CU", "Jah Jah City", listOf("Capleton"), "Reggae Gold 1999", "3:37")),
		).single()

		assertEquals(listOf("Capleton"), row.artists)
	}

	/**
	 * The unfiltered shape is `Song \u2022 Artist` with a play count in column three,
	 * so the artist is the *second* segment. Reading position 0 would make the
	 * literal word "Song" the artist.
	 */
	@Test
	fun `an unlinked unfiltered row reads the artist after the item type`() {
		val unlinkedUnfiltered = unfiltered.replace(
			"""{"text": "Mr. Vegas", "navigationEndpoint": {"browseEndpoint": {
			          "browseId": "UCVrkxPNZb4ZAYKHJumyZmTw",
			          "browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {
			            "pageType": "MUSIC_PAGE_TYPE_ARTIST"
			          }}
			        }}}""",
			"""{"text": "Mr. Vegas"}""",
		)
		val row = YouTubeMusicCatalogSearchParser.candidates(unlinkedUnfiltered).single()

		assertEquals(listOf("Mr. Vegas"), row.artists)
		assertNull(row.durationSeconds)
	}

	@Test
	fun `two identical catalog rows reduce to one deterministic recording`() {
		val rows = YouTubeMusicCatalogSearchParser.candidates(
			response(
				songRow("FcYm_6kR3Eg", "Question", listOf("Jamal", "Attomatic"), "Question", "2:45"),
				songRow("k0cAgBwJmD4", "Question", listOf("Jamal", "Attomatic"), "Question", "2:45"),
			),
		)

		assertEquals(
			"FcYm_6kR3Eg",
			VideoIdResolver.soleOrDuplicateFamilyRow(rows, playerSeconds = 165)?.videoId,
		)
		// Order-independent.
		assertEquals(
			"FcYm_6kR3Eg",
			VideoIdResolver.soleOrDuplicateFamilyRow(rows.reversed(), playerSeconds = null)?.videoId,
		)
	}

	@Test
	fun `the player's own length picks one row out of a differently-trimmed family`() {
		val rows = YouTubeMusicCatalogSearchParser.candidates(
			response(
				songRow("jknS80RoAh8", "Sort Dem Out", listOf("Demarco"), "Warning Riddim", "2:53"),
				songRow("DSMsTwA1XBw", "Sort Dem Out", listOf("Demarco"), "Warning Riddim", "2:54"),
			),
		)

		assertEquals(
			"DSMsTwA1XBw",
			VideoIdResolver.soleOrDuplicateFamilyRow(rows, playerSeconds = 174)?.videoId,
		)
	}

	@Test
	fun `rows that are not one recording never reduce`() {
		val base = songRow("FcYm_6kR3Eg", "Question", listOf("Jamal", "Attomatic"), "Question", "2:45")
		fun rowsOf(other: String) =
			YouTubeMusicCatalogSearchParser.candidates(response(base, other))

		// A different credit.
		assertNull(
			VideoIdResolver.soleOrDuplicateFamilyRow(
				rowsOf(songRow("k0cAgBwJmD4", "Question", listOf("Someone Else"), "Question", "2:45")),
				playerSeconds = null,
			),
		)
		// A different work.
		assertNull(
			VideoIdResolver.soleOrDuplicateFamilyRow(
				rowsOf(songRow("k0cAgBwJmD4", "Answer", listOf("Jamal", "Attomatic"), "Question", "2:45")),
				playerSeconds = null,
			),
		)
		// A length far outside the family spread.
		assertNull(
			VideoIdResolver.soleOrDuplicateFamilyRow(
				rowsOf(songRow("k0cAgBwJmD4", "Question", listOf("Jamal", "Attomatic"), "Question", "9:45")),
				playerSeconds = null,
			),
		)
		// Different releases are not duplicate rows merely because the player did
		// not publish an album and their other card fields happen to agree.
		assertNull(
			VideoIdResolver.soleOrDuplicateFamilyRow(
				rowsOf(
					songRow(
						"k0cAgBwJmD4", "Question", listOf("Jamal", "Attomatic"),
						"Question (Remastered)", "2:45",
					),
				),
				playerSeconds = null,
			),
		)
		// Song and Video are different exact catalog items, not duplicate ingests.
		assertNull(
			VideoIdResolver.soleOrDuplicateFamilyRow(
				rowsOf(
					songRow(
						"k0cAgBwJmD4", "Question", listOf("Jamal", "Attomatic"),
						"Question", "2:45", musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
					),
				),
				playerSeconds = null,
			),
		)
	}
}
