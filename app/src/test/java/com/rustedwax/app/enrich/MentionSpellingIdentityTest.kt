package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionSpellingIdentityTest {

	private fun card(
		videoId: String,
		title: String,
		channel: String,
		lengthSeconds: Long,
	) = SearchResultsParser.Candidate(videoId, title, channel, lengthSeconds)

	// --- The three production misses --------------------------------------

	@Test
	fun `display-name mention resolves the handle-spelled card`() {
		val candidate = card(
			"aZaxQG3ggng",
			"YCB Frenzy - Crazy ( Official Video ) Shot and edited by: @spitcamuniversity",
			"YCB Frenzy",
			193,
		)
		val matches = SearchResultsParser.identityMatches(
			listOf(candidate),
			"YCB Frenzy - Crazy ( Official Video ) Shot and edited by: @Maggie Rudisill",
			"YCB Frenzy",
			192,
		)
		assertEquals(listOf(candidate), matches)
	}

	@Test
	fun `two mentions respaced resolve the card`() {
		val candidate = card(
			"MaE19-BdilM",
			"BRS Kash - Throat Baby Remix feat. @dababy and @CityGirls [Official Music Video]",
			"BRS Kash",
			236,
		)
		val matches = SearchResultsParser.identityMatches(
			listOf(candidate),
			"BRS Kash - Throat Baby Remix feat. @DaBaby and @City Girls [Official Music Video]",
			"BRS Kash",
			236,
		)
		assertEquals(listOf(candidate), matches)
	}

	@Test
	fun `respaced mention resolves the card`() {
		val candidate = card(
			"IFBXY61-14U",
			"NLE Choppa Feat. @SexyyRed - Slut Me Out Remix (Official Video)",
			"NLE CHOPPA",
			208,
		)
		val matches = SearchResultsParser.identityMatches(
			listOf(candidate),
			"NLE Choppa Feat. @Sexyy Red - Slut Me Out Remix (Official Video)",
			"NLE CHOPPA",
			207,
		)
		assertEquals(listOf(candidate), matches)
	}

	@Test
	fun `the card's own title and channel are returned unaltered`() {
		val cardTitle = "NLE Choppa Feat. @SexyyRed - Slut Me Out Remix (Official Video)"
		val candidate = card("IFBXY61-14U", cardTitle, "NLE CHOPPA", 208)
		val sessionTitle = "NLE Choppa Feat. @Sexyy Red - Slut Me Out Remix (Official Video)"
		val match = SearchResultsParser.bestMatch(
			listOf(candidate), sessionTitle, "NLE CHOPPA", 207,
		)
		assertEquals(cardTitle, match?.title)
		assertEquals("NLE CHOPPA", match?.channel)
		// The caller's own strings are inputs, never rewritten in place.
		assertEquals(
			"NLE Choppa Feat. @Sexyy Red - Slut Me Out Remix (Official Video)",
			sessionTitle,
		)
	}

	// --- Nothing else may move --------------------------------------------

	@Test
	fun `titles without a mention are unaffected`() {
		val exact = card("CZFTfYYql4k", "Doomed", "Bring Me The Horizon", 275)
		assertEquals(
			listOf(exact),
			SearchResultsParser.identityMatches(listOf(exact), "Doomed", "Bring Me The Horizon", 274),
		)
		val differentWork = card("DIEI2YLYg6o", "Sleepwalking", "Bring Me The Horizon", 275)
		assertTrue(
			SearchResultsParser.identityMatches(
				listOf(differentWork), "Doomed", "Bring Me The Horizon", 274,
			).isEmpty(),
		)
	}

	@Test
	fun `a different channel named by the mention is still a contradiction`() {
		// Same shape as the fixed cases, but the surrounding text is too thin to
		// anchor: two different people covering the same song must not merge.
		val candidate = card("aaaaaaaaaaa", "Cover by @alice", "Covers", 200)
		assertTrue(
			SearchResultsParser.identityMatches(
				listOf(candidate), "Cover by @bob", "Covers", 200,
			).isEmpty(),
		)
		assertFalse(
			SearchResultsParser.hasNoIdentityContradiction(
				candidate, "Cover by @bob", "Covers", 200,
			),
		)
	}

	@Test
	fun `a mention difference does not excuse a wrong duration or channel`() {
		val candidate = card(
			"IFBXY61-14U",
			"NLE Choppa Feat. @SexyyRed - Slut Me Out Remix (Official Video)",
			"NLE CHOPPA",
			208,
		)
		val sessionTitle = "NLE Choppa Feat. @Sexyy Red - Slut Me Out Remix (Official Video)"
		assertTrue(
			"duration must still be independently required",
			SearchResultsParser.identityMatches(
				listOf(candidate), sessionTitle, "NLE CHOPPA", 260,
			).isEmpty(),
		)
		assertTrue(
			"channel must still be independently required",
			SearchResultsParser.identityMatches(
				listOf(candidate), sessionTitle, "Some Other Channel", 207,
			).isEmpty(),
		)
	}

	@Test
	fun `a real word difference beside a matching mention is still rejected`() {
		// The mention agrees; the *work* does not. Only the mention run may vary.
		val candidate = card(
			"bbbbbbbbbbb",
			"NLE Choppa Feat. @SexyyRed - Different Song Entirely (Official Video)",
			"NLE CHOPPA",
			208,
		)
		assertTrue(
			SearchResultsParser.identityMatches(
				listOf(candidate),
				"NLE Choppa Feat. @Sexyy Red - Slut Me Out Remix (Official Video)",
				"NLE CHOPPA",
				207,
			).isEmpty(),
		)
	}

	private fun queries(title: String, channel: String) =
		VideoIdResolver().searchQueries(title, channel)

	@Test
	fun `a mentioned title also searches without the mention`() {
		assertTrue(
			queries(
				"YCB Frenzy - Crazy ( Official Video ) Shot and edited by: @Maggie Rudisill",
				"YCB Frenzy",
			).any { it == "YCB Frenzy - Crazy ( Official Video ) Shot and edited by: YCB Frenzy" },
		)
		assertTrue(
			queries(
				"BRS Kash - Throat Baby Remix feat. @DaBaby and @City Girls [Official Music Video]",
				"BRS Kash",
			).any { it == "BRS Kash - Throat Baby Remix feat. BRS Kash" },
		)
		assertTrue(
			queries(
				"NLE Choppa Feat. @Sexyy Red - Slut Me Out Remix (Official Video)",
				"NLE CHOPPA",
			).any { it == "NLE Choppa Feat. NLE CHOPPA" },
		)
	}

	@Test
	fun `a title without a mention generates exactly the queries it always did`() {
		assertEquals(
			listOf(
				"Doomed Bring Me The Horizon",
				"Doomed",
			),
			queries("Doomed", "Bring Me The Horizon"),
		)
		// The established presentation-cleaned variant is untouched.
		val kartel = queries("VYBZ KARTEL WHEN SINCE", "Vybz Kartel")
		assertTrue(kartel.any { it.equals("when since Vybz Kartel", ignoreCase = true) })
		assertTrue(kartel.none { it.contains('@') })
	}

	@Test
	fun `the query budget is not widened`() {
		listOf(
			"YCB Frenzy - Crazy ( Official Video ) Shot and edited by: @Maggie Rudisill" to "YCB Frenzy",
			"BRS Kash - Throat Baby Remix feat. @DaBaby and @City Girls [Official Music Video]" to "BRS Kash",
			"NLE Choppa Feat. @Sexyy Red - Slut Me Out Remix (Official Video)" to "NLE CHOPPA",
		).forEach { (title, channel) ->
			assertTrue(title, queries(title, channel).size <= VideoIdResolver.MAX_SEARCH_QUERIES)
		}
	}

	@Test
	fun `a title that is mostly mention gets no mention-free query`() {
		// Truncating leaves too little to name a work; searching it would recall
		// the channel's whole catalogue rather than this upload.
		val q = queries("@spitcamuniversity", "YCB Frenzy")
		assertTrue(q.none { it == "YCB Frenzy" })
		assertTrue(queries("Live @nightclub", "DJ").none { it.trim() == "Live" })
	}

	@Test
	fun `two uploads differing only by mention spelling stay ambiguous`() {
		// The relaxation must not turn a genuine tie into a silent pick.
		val first = card(
			"ccccccccccc",
			"NLE Choppa Feat. @SexyyRed - Slut Me Out Remix (Official Video)",
			"NLE CHOPPA",
			208,
		)
		val second = card(
			"ddddddddddd",
			"NLE Choppa Feat. @Sexyy_Red - Slut Me Out Remix (Official Video)",
			"NLE CHOPPA",
			207,
		)
		val matches = SearchResultsParser.identityMatches(
			listOf(first, second),
			"NLE Choppa Feat. @Sexyy Red - Slut Me Out Remix (Official Video)",
			"NLE CHOPPA",
			207,
		)
		assertEquals(2, matches.size)
		// bestMatch is single-or-null, so the resolver refuses rather than guesses.
		assertEquals(
			null,
			SearchResultsParser.bestMatch(
				listOf(first, second),
				"NLE Choppa Feat. @Sexyy Red - Slut Me Out Remix (Official Video)",
				"NLE CHOPPA",
				207,
			),
		)
	}
}
