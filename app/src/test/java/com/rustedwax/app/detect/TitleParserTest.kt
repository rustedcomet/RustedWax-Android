package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Q5 finding from <redacted-private-path>: a browser media session reports the *channel*
 * as ARTIST and packs the real artist into the title. These cases come from
 * real observed sessions plus the shapes upstream's YouTube rules handle.
 */
class TitleParserTest {

	@Test
	fun `splits the observed Korn session correctly`() {
		// Exactly what Chrome reported in Phase 0 run 2.
		val parsed = TitleParser.parse("Korn - Trash (Official Audio)", "KornVEVO")
		assertEquals("Korn", parsed.artist)
		assertEquals("Trash", parsed.track)
	}

	@Test
	fun `strips promotional noise`() {
		assertEquals("Song", TitleParser.clean("Song (Official Video)"))
		assertEquals("Song", TitleParser.clean("Song [Official Music Video]"))
		assertEquals("Song", TitleParser.clean("Song (Lyrics)"))
		assertEquals("Song", TitleParser.clean("Song (HD)"))
		assertEquals("Song", TitleParser.clean("Song | Official Video"))
	}

	/**
	 * Regression: v0.2.1 stripped "(Official Video)" and "(HD)" but not the
	 * combination, so this exact title reached the chain with its suffix
	 * intact. Observed 2026-07-23.
	 */
	@Test
	fun `strips combined promotional qualifiers`() {
		assertEquals("Thoughtless", TitleParser.clean("Thoughtless (Official HD Video)"))
		assertEquals("Song", TitleParser.clean("Song (Official 4K Video)"))
		assertEquals("Song", TitleParser.clean("Song [Official Lyric Video]"))
		assertEquals("Song", TitleParser.clean("Song (Official Music Video HD)"))
		assertEquals(
			"Thoughtless",
			TitleParser.parse("Korn - Thoughtless (Official HD Video)", "KornVEVO").track,
		)
	}

	@Test
	fun `keeps bracketed content that is not purely promotional`() {
		assertEquals("Song (Radio Edit)", TitleParser.clean("Song (Radio Edit)"))
		assertEquals("Song (Sol Invicto Remix)", TitleParser.clean("Song (Sol Invicto Remix)"))
		assertEquals("Song (MAPHRA Vocal Cover)", TitleParser.clean("Song (MAPHRA Vocal Cover)"))
	}

	@Test
	fun `identifies music by channel or title shape`() {
		assertTrue(TitleParser.looksLikeMusic("Korn - Thoughtless", "KornVEVO"))
		assertTrue(TitleParser.looksLikeMusic("Anything at all", "Radiohead - Topic"))
		assertTrue(TitleParser.looksLikeMusic("Slipknot - Duality", "SomeUploader"))
		assertFalse(TitleParser.looksLikeMusic("How to fix a sink", "DIY Channel"))
	}

	/**
	 * Phase 4 decision D7 reversed this. Live and acoustic takes now fold into
	 * the original recording so play counts land on one entry — the point of
	 * the change is that a cover of a song *is* a listen to that song.
	 *
	 * `(Remix)` is the deliberate exception: a remix is a distinct work,
	 * usually credited to a different artist, so folding it in would be wrong
	 * rather than tidy.
	 */
	@Test
	fun `normalizes performance qualifiers to the original recording`() {
		assertEquals("Song", TitleParser.clean("Song (Live)"))
		assertEquals("Song", TitleParser.clean("Song (Acoustic)"))
		assertEquals("Song", TitleParser.clean("Song (Instrumental)"))
		assertEquals("Song", TitleParser.clean("Song (Guitar Cover)"))
		assertEquals("Song", TitleParser.clean("Song 【Drum Cover】"))
		assertEquals("Song (Remix)", TitleParser.clean("Song (Remix)"))
	}

	/**
	 * A group is only dropped when every word in it is a known qualifier, so a
	 * *named* live recording survives. No table can enumerate venues, and this
	 * is the better failure of the two.
	 */
	@Test
	fun `keeps named live recordings`() {
		assertEquals("Song (Live at Wembley)", TitleParser.clean("Song (Live at Wembley)"))
	}

	/**
	 * The reported misindexed scrobble: https://youtube.com/watch?v=ICSCJBc9fkY
	 *
	 * Went on-chain as artist "OLD MOON CHILD" (the channel) with the entire
	 * raw title as its title. The artist was in the title the whole time, in
	 * brackets the parser didn't recognise.
	 */
	@Test
	fun `parses the reported guitar cover correctly`() {
		val raw = "【Bring Me The Horizon】I Used to Make Out With Medusa " +
			"(Instrumental) 2023【Guitar Cover】＋Screen Tabs"
		val parsed = TitleParser.parse(raw, "OLD MOON CHILD")
		assertEquals("Bring Me The Horizon", parsed.artist)
		assertEquals("I Used to Make Out With Medusa", parsed.track)
	}

	@Test
	fun `reads an artist out of leading CJK brackets`() {
		assertEquals("Artist", TitleParser.parse("【Artist】Track", "Some Channel").artist)
		assertEquals("Track", TitleParser.parse("【Artist】Track", "Some Channel").track)
		// The bracket wins over a separator later in the title, which would
		// otherwise put "【Artist】Song" in the artist field.
		assertEquals("Artist", TitleParser.parse("【Artist】Song - Live", "Chan").artist)
	}

	@Test
	fun `strips trailing tab and gear suffixes`() {
		assertEquals("Song", TitleParser.clean("Song ＋Screen Tabs"))
		assertEquals("Song", TitleParser.clean("Song + Tabs"))
		assertEquals("Song", TitleParser.clean("Song w/ Screen Tabs"))
	}

	@Test
	fun `strips a trailing year but never a title that is one`() {
		assertEquals("Some Long Song Name", TitleParser.clean("Some Long Song Name 2023"))
		// "1979" and "1999" are real songs. A title that is nothing but a year
		// must survive, which is why the strip requires two words to remain.
		assertEquals("1979", TitleParser.clean("1979"))
		assertEquals("1979", TitleParser.parse("The Smashing Pumpkins - 1979", null).track)
	}

	@Test
	fun `handles the separators youtube titles actually use`() {
		assertEquals("Artist", TitleParser.parse("Artist – Track", null).artist)
		assertEquals("Artist", TitleParser.parse("Artist — Track", null).artist)
		assertEquals("Artist", TitleParser.parse("Artist ~ Track", null).artist)
		assertEquals("Track", TitleParser.parse("Artist | Track", null).track)
	}

	@Test
	fun `falls back to the channel when the title has no separator`() {
		val parsed = TitleParser.parse("Trash", "KornVEVO")
		assertEquals("Korn", parsed.artist)
		assertEquals("Trash", parsed.track)
	}

	@Test
	fun `cleans channel suffixes`() {
		assertEquals("Korn", TitleParser.cleanChannel("KornVEVO"))
		assertEquals("Korn", TitleParser.cleanChannel("Korn - Topic"))
		assertEquals("Radiohead", TitleParser.cleanChannel("Radiohead"))
		assertNull(TitleParser.cleanChannel(null))
		assertNull(TitleParser.cleanChannel("   "))
	}

	@Test
	fun `omits artist rather than inventing one`() {
		val parsed = TitleParser.parse("Some random upload", null)
		assertNull(parsed.artist)
		assertEquals("Some random upload", parsed.track)
	}

	@Test
	fun `does not split on a leading separator`() {
		val parsed = TitleParser.parse("- Track", "Channel")
		assertEquals("Channel", parsed.artist)
	}

	// region shapes adapted from the desktop extension's ytTitleRegExps

	/** Quotes name the track outright, which beats guessing at a separator. */
	@Test
	fun `splits a quoted track`() {
		TitleParser.parse("""BABYMETAL "Gimme Chocolate!!"""", "Some Channel").let {
			assertEquals("BABYMETAL", it.artist)
			assertEquals("Gimme Chocolate!!", it.track)
		}
		TitleParser.parse("""Korn - "Trash"""", "KornVEVO").let {
			assertEquals("Korn", it.artist)
			assertEquals("Trash", it.track)
		}
	}

	@Test
	fun `log 14 song-credit fixtures are structure and channel aware`() {
		TitleParser.parse(
			"Ice Spice Performs \"Think You The Sh*t (Fart)\" Live On The BET Stage! | " +
				"BET Awards '24",
			"BET International",
		).let {
			assertEquals("Ice Spice", it.artist)
			assertEquals("Think You The Sh*t (Fart)", it.track)
		}

		TitleParser.parse(
			"Sexyy Red \"Get It Sexyy\" (Official Video - No Skits)",
			"Sexyy Red",
		).let {
			assertEquals("Sexyy Red", it.artist)
			assertEquals("Get It Sexyy", it.track)
		}

		TitleParser.parse(
			"6IX9INE \"Gotti\" (WSHH Exclusive - Official Music Video)",
			"WORLDSTARHIPHOP",
		).let {
			assertEquals("6IX9INE", it.artist)
			assertEquals("Gotti", it.track)
		}

		TitleParser.parse(
			"TROLLZ - 6ix9ine & Nicki Minaj (Official Music Video)",
			"Tekashi 6ix9ine",
		).let {
			assertEquals("6ix9ine & Nicki Minaj", it.artist)
			assertEquals("TROLLZ", it.track)
		}
	}

	@Test
	fun `log 16 quoted work with trailing credits is structure aware`() {
		TitleParser.parse(
			"W Sound 05 \"LA PLENA\" - Beéle, Westcol, Ovy On The Drums",
			"W Sound",
		).let {
			assertEquals("Beéle, Westcol, Ovy On The Drums", it.artist)
			assertEquals("LA PLENA", it.track)
		}
	}

	@Test
	fun `quoted work event and promo suffixes never become artist credits`() {
		TitleParser.parse(
			"Artist \"Song\" - Official Video",
			"Artist",
		).let {
			assertEquals("Artist", it.artist)
			assertEquals("Artist \"Song\" - Official Video", it.track)
		}
		TitleParser.parse(
			"Artist \"Song\" - Live at Wembley",
			"Artist",
		).let {
			assertEquals("Artist", it.artist)
			assertEquals("Artist \"Song\" - Live at Wembley", it.track)
		}
		TitleParser.parse(
			"Publisher \"Song\" - Ambiguous Suffix",
			"Publisher",
		).let {
			assertEquals("Publisher", it.artist)
			assertEquals("Publisher \"Song\" - Ambiguous Suffix", it.track)
		}
	}

	@Test
	fun `separators inside balanced syntax are never credit boundaries`() {
		TitleParser.parse(
			"Sexyy Red \"Get It Sexyy\" (Official Video - No Skits)",
			"Sexyy Red",
		).let {
			assertFalse(it.artist?.contains("Official Video") == true)
			assertFalse(it.track.contains("No Skits"))
		}
		val ambiguous = TitleParser.parse(
			"Blade II | Sewers of the Damned | ClipZone: Heroes & Villains",
			"ClipZone",
		)
		assertEquals("ClipZone", ambiguous.artist)
		assertEquals(
			"Blade II | Sewers of the Damned | ClipZone: Heroes & Villains",
			ambiguous.track,
		)
	}

	@Test
	fun `log 17 primary dash and pipe fixtures keep their proven work boundary`() {
		TitleParser.parse(
			"Bad Bunny (ft. Chencho Corleone) - Me Porto Bonito (Official Video) | Un Verano Sin Ti",
			"Bad Bunny",
		).let {
			assertEquals("Bad Bunny", it.artist) // saGYMhApaH8
			assertEquals("Me Porto Bonito", it.track)
		}
		TitleParser.parse(
			"BAD BUNNY - YO PERREO SOLA | YHLQMDLG (Official Video)",
			"Bad Bunny",
		).let {
			assertEquals("Bad Bunny", it.artist) // GtSRKwDCaZM
			assertEquals("YO PERREO SOLA", it.track)
		}
	}

	@Test
	fun `log 17 featured channel cannot reverse conventional artist first input`() {
		TitleParser.parse(
			"Anuel - Ayer ft. Dj Nelson [Official Video]",
			"DJ Nelson",
		).let {
			assertEquals("Anuel", it.artist) // AnKdQ5p5Ks8
			assertEquals("Ayer ft. Dj Nelson", it.track)
		}
		TitleParser.parse("Artist - Track ft. Featured Artist", "Featured Artist").let {
			assertEquals("Artist", it.artist)
			assertEquals("Track ft. Featured Artist", it.track)
		}
	}

	@Test
	fun `log 17 explicit multi artist lists prove track first orientation`() {
		TitleParser.parse(
			"🌡105F RMX - Kevvo FT Chencho Corleone, Farruko , Myke Towers, Arcangel, " +
				"Ñengo Flow, Darell, Brytiago",
			"MrPepeQuintana",
		).let {
			assertEquals(
				"Kevvo FT Chencho Corleone, Farruko , Myke Towers, Arcangel, Ñengo Flow, " +
					"Darell, Brytiago",
				it.artist,
			) // qA6FBDYncGk
			assertEquals("🌡105F RMX", it.track)
		}
		TitleParser.parse(
			"Me Llama Todavía [Remix] - Super Yei × Towy × Osquel × Gotay × Agus Padilla " +
				"[Video Lyric] 2018",
			"Superiority",
		).let {
			assertEquals("Super Yei × Towy × Osquel × Gotay × Agus Padilla", it.artist)
			assertEquals("Me Llama Todavía [Remix]", it.track) // lA8OhVn-o7M
		}
		TitleParser.parse(
			"Diles - Bad Bunny, Ozuna, Farruko, Arcangel, Ñengo Flow",
			"Hear This Music",
		).let {
			assertEquals("Bad Bunny, Ozuna, Farruko, Arcangel, Ñengo Flow", it.artist)
			assertEquals("Diles", it.track) // UWV41yEiGq0
		}
		TitleParser.parse("Work - Artist One & Artist Two", "Unrelated Publisher").let {
			assertEquals("Artist One & Artist Two", it.artist)
			assertEquals("Work", it.track)
		}
	}

	@Test
	fun `only an exact repeated trailing feature phrase collapses`() {
		TitleParser.parse(
			"The Weeknd - Starboy ft. Daft Punk (Official Video) ft. Daft Punk",
			"TheWeekndVEVO",
		).let {
			assertEquals("The Weeknd", it.artist)
			assertEquals("Starboy ft. Daft Punk", it.track) // 34Na4j8AVgA
		}
		assertEquals(
			"Song ft. Artist One ft. Artist Two",
			TitleParser.parse(
				"Lead - Song ft. Artist One ft. Artist Two",
				"Lead",
			).track,
		)
	}

	@Test
	fun `unproven multi separator and ambiguous pipe inputs remain conservative`() {
		TitleParser.parse("Artist - Track | Live at Wembley", null).let {
			assertNull(it.artist)
			assertEquals("Artist - Track | Live at Wembley", it.track)
		}
		TitleParser.parse("Possible Work | Possible Credit", "Publisher").let {
			assertEquals("Publisher", it.artist)
			assertEquals("Possible Work | Possible Credit", it.track)
		}
	}

	/** The one common shape where the artist comes second. */
	@Test
	fun `splits Track (by Artist)`() {
		TitleParser.parse("Nevada (by Vicetone)", "Some Channel").let {
			assertEquals("Vicetone", it.artist)
			assertEquals("Nevada", it.track)
		}
		TitleParser.parse("Yesterday (performed by The Beatles)", null).let {
			assertEquals("The Beatles", it.artist)
			assertEquals("Yesterday", it.track)
		}
	}

	@Test
	fun `MONTERO parenthetical is title text rather than a by-credit`() {
		TitleParser.parse(
			"Lil Nas X - MONTERO (Call Me By Your Name) (Official Video)",
			"LilNasXVEVO",
		).let {
			assertEquals("Lil Nas X", it.artist)
			assertEquals("MONTERO (Call Me By Your Name)", it.track)
		}
		TitleParser.parse("Work (inspired by a true story)", "Publisher").let {
			assertEquals("Publisher", it.artist)
			assertEquals("Work (inspired by a true story)", it.track)
		}
	}

	@Test
	fun `compound YouTube ownership suffixes normalize one layer at a time`() {
		assertEquals("Spice", TitleParser.cleanChannel("SpiceOfficialVEVO"))
		assertEquals("Spice", TitleParser.cleanChannel("Spice Official"))
		assertEquals("Official", TitleParser.cleanChannel("Official"))
	}

	@Test
	fun `log 19 paired promo year group is removed without damaging delimiters`() {
		TitleParser.parse(
			"Marlon Asher - Strictly High Grade [Official Video 2024]",
			"Reggaeville",
		).let {
			assertEquals("Marlon Asher", it.artist)
			assertEquals("Strictly High Grade", it.track)
		}
		assertEquals("Song (Summer 2024)", TitleParser.clean("Song (Summer 2024)"))
		assertEquals("Song [Song 2024]", TitleParser.clean("Song [Song 2024]"))
		assertEquals("1979", TitleParser.clean("1979"))
		assertEquals(
			"Song [Official Video 2024)",
			TitleParser.clean("Song [Official Video 2024)"),
		)
		assertEquals(
			"Song (Official Video 2024]",
			TitleParser.clean("Song (Official Video 2024]"),
		)
		assertEquals(
			"DNA [Loving You Is in My DNA) [feat. Hannah Boleyn]",
			TitleParser.parse(
				"Billy Gillies - DNA [Loving You Is in My DNA) " +
					"[feat. Hannah Boleyn] [Official Lyric Video]",
				"Billy Gillies",
			).track,
		)
	}

	@Test
	fun `log 19 structurally balanced single quoted work protects its dash`() {
		TitleParser.parse(
			"TVXQ! 동방신기 '주문 - MIROTIC' MV",
			"SMTOWN",
		).let {
			assertEquals("TVXQ! 동방신기", it.artist)
			assertEquals("주문 - MIROTIC", it.track)
		}
		TitleParser.parse(
			"TVXQ! 동방신기 ‘주문 - MIROTIC’ MV",
			"SMTOWN",
		).let {
			assertEquals("TVXQ! 동방신기", it.artist)
			assertEquals("주문 - MIROTIC", it.track)
		}
	}

	@Test
	fun `apostrophes unmatched quotes and quoted event suffixes stay conservative`() {
		assertEquals(
			"Gangsta's Paradise",
			TitleParser.parse("Gangsta's Paradise", "Coolio").track,
		)
		assertEquals(
			"Don't Start Now",
			TitleParser.parse("Don't Start Now", "Dua Lipa").track,
		)
		assertEquals(
			"Guns N' Roses",
			TitleParser.parse("Guns N' Roses", "Publisher").track,
		)
		assertEquals(
			"Artist's Song",
			TitleParser.parse("Artist's Song", "Artist").track,
		)
		// The unmatched apostrophe still keeps the whole title together, which is
		// what this case is about. The bare `MV` now comes off it — §3.7 — so the
		// same upload dedups the same way whether its uploader wrote `MV`, `(MV)`
		// or `【MV】`.
		assertEquals(
			"Artist 'Unmatched - Work",
			TitleParser.parse("Artist 'Unmatched - Work MV", "Artist").track,
		)
		assertEquals(
			"Artist 'Song' - Live at Wembley",
			TitleParser.parse("Artist 'Song' - Live at Wembley", "Artist").track,
		)
		assertEquals(
			"Artist 'Song' - Official Promo",
			TitleParser.parse("Artist 'Song' - Official Promo", "Artist").track,
		)
	}

	@Test
	fun `log 19 exact collapsed owner proof preserves conventional featured title`() {
		TitleParser.parse(
			"DJ Snake - Taki Taki ft. Selena Gomez, Ozuna, Cardi B",
			"DJSnakeVEVO",
		).let {
			assertEquals("DJ Snake", it.artist)
			assertEquals("Taki Taki ft. Selena Gomez, Ozuna, Cardi B", it.track)
		}
	}

	@Test
	fun `log 20 conventional featured titles remain artist first without owner proof`() {
		TitleParser.parse(
			"6IX9INE - SIP ft. Tyga, Nicki Minaj, Blueface (RapKing Music Video)",
			"RapKing",
		).let {
			assertEquals("6IX9INE", it.artist)
			assertEquals(
				"SIP ft. Tyga, Nicki Minaj, Blueface (RapKing Music Video)",
				it.track,
			)
		}
		TitleParser.parse(
			"Ed Sheeran – Bad Habits Feat. Tion Wayne & Central Cee " +
				"(Fumez The Engineer Remix) [Official Video]",
			"Tion Wayne",
		).let {
			assertEquals("Ed Sheeran", it.artist)
			assertEquals(
				"Bad Habits Feat. Tion Wayne & Central Cee (Fumez The Engineer Remix)",
				it.track,
			)
		}
	}

	@Test
	fun `conventional featured title does not invert for substring partial or unrelated channels`() {
		TitleParser.parse(
			"DJ Snake - Taki Taki ft. Selena Gomez, Ozuna, Cardi B",
			"DJ Snake Fan Channel",
		).let {
			assertEquals("DJ Snake", it.artist)
			assertEquals("Taki Taki ft. Selena Gomez, Ozuna, Cardi B", it.track)
		}
		TitleParser.parse(
			"DJ Snake - Taki Taki ft. Selena Gomez, Ozuna, Cardi B",
			"SnakeVEVO",
		).let {
			assertEquals("DJ Snake", it.artist)
			assertEquals("Taki Taki ft. Selena Gomez, Ozuna, Cardi B", it.track)
		}
	}

	@Test
	fun `log 19 exact owner and bounded version suffix establish only first dash`() {
		TitleParser.parse(
			"BENNETT - Mamma Mia (feat. Mentissa) - Techno Mix (Official Lyric Video)",
			"BENNETT",
		).let {
			assertEquals("BENNETT", it.artist)
			assertEquals("Mamma Mia (feat. Mentissa) - Techno Mix", it.track)
		}
	}

	@Test
	fun `version suffix boundary stays conservative without exact structural proof`() {
		listOf(
			"Artist - Album - Song" to "Artist",
			"Artist - Song - Live at Wembley" to "Artist",
			"Artist - Song - Official Promo" to "Artist",
			"Artist - Song - Techno Mix" to null,
			"Artist - Song - Techno Mix" to "Conflicting Owner",
			"Label - Artist - Techno Mix" to "Publisher Records",
			"Artist - Song - 'Techno Mix'" to "Artist",
			"Artist - Song - Techno Mix [Festival]" to "Artist",
		).forEach { (raw, channel) ->
			val parsed = TitleParser.parse(raw, channel)
			assertEquals(raw, parsed.track)
		}
	}

	/** Album and vinyl rips carry a track number the chain shouldn't see. */
	@Test
	fun `strips a track-number prefix`() {
		assertEquals("Trash", TitleParser.clean("03. Trash"))
		assertEquals("Trash", TitleParser.clean("A1. Trash"))
		assertEquals("Trash", TitleParser.clean("12) Trash"))
		TitleParser.parse("07. Korn - Trash", null).let {
			assertEquals("Korn", it.artist)
			assertEquals("Trash", it.track)
		}
	}

	/** A number that *is* the title has no separator after it, so it survives. */
	@Test
	fun `does not strip numbers that are part of the title`() {
		assertEquals("1999", TitleParser.clean("1999"))
		assertEquals("7 Rings", TitleParser.clean("7 Rings"))
		assertEquals("100 Bad Days", TitleParser.clean("100 Bad Days"))
	}

	/** A leading genre tag is noise — but only when an artist survives without it. */
	@Test
	fun `strips a leading tag when a separator survives`() {
		TitleParser.parse("[Future Bass] Vicetone - Nevada", null).let {
			assertEquals("Vicetone", it.artist)
			assertEquals("Nevada", it.track)
		}
	}

	/**
	 * Regression guard for the interaction: the reported guitar cover puts the
	 * *artist* in a leading CJK bracket, so the tag strip must not eat it.
	 */
	@Test
	fun `keeps a leading bracket that names the artist`() {
		val parsed = TitleParser.parse(
			"【Bring Me The Horizon】I Used to Make Out With Medusa",
			"OLD MOON CHILD",
		)
		assertEquals("Bring Me The Horizon", parsed.artist)
		assertEquals("I Used to Make Out With Medusa", parsed.track)
	}
	// endregion

	// region trailing hashtags
	//
	// All of these went on-chain verbatim in the 2026-07-29 session. The cost
	// isn't only cosmetic: the tag run is part of the title, so the same clip
	// reposted with different tags dedups as a different listen.

	@Test
	fun `strips a trailing hashtag run`() {
		assertEquals(
			"Rüyamda seni gördüm",
			TitleParser.clean(
				"Rüyamda seni gördüm #ilkveson #dizi #blutv #hazalsubaşı #ulastunaastepe",
			),
		)
		assertEquals(
			"🏫Un reggaeton educativo",
			TitleParser.clean("🏫Un reggaeton educativo #insulini #reggaeton #perreo #poliglota"),
		)
	}

	/** Non-Latin tags: the session was full of Turkish, Korean and Chinese ones. */
	@Test
	fun `strips non-latin hashtags`() {
		assertEquals(
			"下班后的日常",
			TitleParser.clean("下班后的日常 #老人与海 #shortsvideo #dance #护士跳舞"),
		)
	}

	/** `#plena#panama 🇵🇦` — no spaces between tags, an emoji after the last one. */
	@Test
	fun `strips runs with no spaces and trailing emoji`() {
		assertTrue(TitleParser.isHashtagOnly("#plena#panama 🇵🇦"))
		assertEquals(
			"PREGUNTO.",
			TitleParser.clean("PREGUNTO. #oldveteran #plena #reggaeespañol #panama"),
		)
	}

	@Test
	fun `keeps a hashtag that is not trailing`() {
		assertEquals("Song #2 of the series", TitleParser.clean("Song #2 of the series"))
	}

	/**
	 * `#` is also a sharp. A tag needs word characters immediately after the
	 * hash, which a note name never has — the letter comes *before* it.
	 */
	@Test
	fun `does not mistake sharp notes for hashtags`() {
		assertEquals("Prelude in C#", TitleParser.clean("Prelude in C#"))
		assertEquals("Nocturne in C# Minor", TitleParser.clean("Nocturne in C# Minor"))
		assertEquals("Learn C# Programming", TitleParser.clean("Learn C# Programming"))
		assertFalse(TitleParser.isHashtagOnly("Prelude in C#"))
	}

	/**
	 * A tag run hiding behind a promo bracket. The clean loop has to reach it
	 * whichever order the uploader used.
	 */
	@Test
	fun `strips a hashtag run behind promotional noise`() {
		assertEquals("Song", TitleParser.clean("Song #shorts (Official Video)"))
		assertEquals("Song", TitleParser.clean("Song (Official Video) #shorts"))
	}

	/**
	 * When the tags are all there is, the title is returned unchanged — a
	 * payload needs some title, and empty is worse than ugly. The classifier
	 * uses [TitleParser.isHashtagOnly] to refuse the `song` kind instead.
	 */
	@Test
	fun `never strips a title down to nothing`() {
		assertEquals("#guitar", TitleParser.clean("#guitar"))
		assertEquals(
			"#guitar #dubstep #djdubstep #fnaf",
			TitleParser.clean("#guitar #dubstep #djdubstep #fnaf"),
		)
	}

	@Test
	fun `recognises a title that is only hashtags`() {
		assertTrue(TitleParser.isHashtagOnly("#guitar #dubstep #fnaf"))
		assertTrue(TitleParser.isHashtagOnly("#skrillex #electricguitar  #dubstep"))
		assertFalse(TitleParser.isHashtagOnly("That's sharp 🔥 #artist #music #guitar"))
		assertFalse(TitleParser.isHashtagOnly("Blackened"))
	}

	/** Tags must not survive into the artist half of a split, either. */
	@Test
	fun `strips tags before splitting artist and track`() {
		val parsed = TitleParser.parse("Slayer - South of Heaven #metal #thrash", null)
		assertEquals("Slayer", parsed.artist)
		assertEquals("South of Heaven", parsed.track)
	}
	// endregion

	@Test
	fun `the AtVEVO channel form resolves to the artist`() {
		// Measured 2026-08-05: stripping bare "VEVO" from "NickiMinajAtVEVO"
		// left "NickiMinajAt", whose key never equalled "nickiminaj", so every
		// Nicki Minaj upload was rejected as "channel contradicts ended channel"
		// — 14 in one session — when the two names are the same channel.
		assertEquals("NickiMinaj", TitleParser.cleanChannel("NickiMinajAtVEVO"))
		assertEquals("Korn", TitleParser.cleanChannel("KornVEVO"))
		assertEquals("Spice", TitleParser.cleanChannel("SpiceOfficialVEVO"))
		assertEquals("Korn", TitleParser.cleanChannel("Korn - Topic"))
		// A channel that is only the marker word is never erased.
		assertEquals("VEVO", TitleParser.cleanChannel("VEVO"))
	}

	@Test
	fun `an ordinary name ending in at keeps its last two letters`() {
		// Measured 2026-08-10: matched case-insensitively, the "AtVEVO" marker
		// also ate the tail of every name ending in "at". "dojacatVEVO" became
		// "dojac", which matches neither the "Doja Cat" a search card or a
		// playlist row lists nor anything a person would want written as the
		// artist of their listen.
		assertEquals("dojacat", TitleParser.cleanChannel("dojacatVEVO"))
		assertEquals("TheCat", TitleParser.cleanChannel("TheCatVEVO"))
		assertEquals("NickiMinaj", TitleParser.cleanChannel("NickiMinajAtVEVO"))
		// The marker needs a name in front of it, and a name is never erased.
		assertEquals("At", TitleParser.cleanChannel("AtVEVO"))
	}

	@Test
	fun `an uploader on the right of the dash is the artist, not the track`() {
		// Measured 2026-08-11, on chain: "Runaway - Linkin Park (Hybrid Theory)"
		// uploaded by "Linkin Park" was broadcast as
		// artist "Runaway" / title "Linkin Park (Hybrid Theory)". Artist-first is
		// a convention, not a fact, and the channel said which end was which.
		val parsed = TitleParser.parse("Runaway - Linkin Park (Hybrid Theory)", "Linkin Park")
		assertEquals("Linkin Park", parsed.artist)
		assertEquals("Runaway", parsed.track)

		// Same shape with no album bracket.
		val bare = TitleParser.parse("Numb - Linkin Park", "Linkin Park")
		assertEquals("Linkin Park", bare.artist)
		assertEquals("Numb", bare.track)
	}

	@Test
	fun `nothing weaker than the whole uploader name reverses a dash`() {
		// The ordinary shape is untouched, whether or not the channel agrees.
		assertEquals(
			"Linkin Park",
			TitleParser.parse("Linkin Park - Numb", "Linkin Park").artist,
		)
		assertEquals(
			"Metallica",
			TitleParser.parse("Metallica - Sad But True", "camille martin").artist,
		)
		assertEquals(
			"Bring Me The Horizon",
			TitleParser.parse(
				"Bring Me The Horizon - Sleepwalking",
				"BMTHOfficialVEVO",
			).artist,
		)
		// The uploader's words inside a qualifier are not a byline.
		assertEquals(
			"Illenium",
			TitleParser.parse("Illenium - Fractures (Trap Nation Release)", "Trap Nation").artist,
		)
		// Named on both sides, so the conventional reading stands.
		assertEquals(
			"Linkin Park Tribute",
			TitleParser.parse("Linkin Park Tribute - Linkin Park", "Linkin Park").artist,
		)
		// No channel is no evidence.
		assertEquals("Runaway", TitleParser.parse("Runaway - Linkin Park", null).artist)
	}
}
