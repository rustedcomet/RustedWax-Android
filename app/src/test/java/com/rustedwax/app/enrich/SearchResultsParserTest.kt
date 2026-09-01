package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SearchResultsParserTest {

	private val fixture = """
		{"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
		{"videoRenderer":{"videoId":"CZFTfYYql4k","title":{"runs":[{"text":"Doomed"}]},
		"ownerText":{"runs":[{"text":"Bring Me The Horizon"}]},"lengthText":{"simpleText":"4:35"}}},
		{"videoRenderer":{"videoId":"DIEI2YLYg6o","title":{"runs":[{"text":"Doomed"}]},
		"ownerText":{"runs":[{"text":"Maphra - Topic"}]},"lengthText":{"simpleText":"4:36"}}},
		{"videoRenderer":{"videoId":"r6L-GUOAhGo",
		"title":{"runs":[{"text":"Bring Me The Horizon - Doomed (MAPHRA Vocal Cover)"}]},
		"ownerText":{"runs":[{"text":"MAPHRA"}]},"lengthText":{"simpleText":"4:27"}}},
		{"videoRenderer":{"videoId":"ZWB9_gKmzxQ",
		"title":{"runs":[{"text":"BRING ME THE HORIZON - Doomed (AUDIO)"}]},
		"ownerText":{"runs":[{"text":"Psychotic Vampire"}]},"lengthText":{"simpleText":"4:33"}}}
		]}}]}}}
	""".trimIndent()

	@Test
	fun `extracts candidates regardless of renderer nesting`() {
		val c = SearchResultsParser.candidates(fixture)
		assertEquals(4, c.size)
		assertEquals("CZFTfYYql4k", c[0].videoId)
		assertEquals("Doomed", c[0].title)
		assertEquals("Bring Me The Horizon", c[0].channel)
		assertEquals(275L, c[0].lengthSeconds)
	}

	/**
	 * A renderer shape that previously produced unlinked entries. A
	 * Shorts card no longer has `title`, `ownerText`, or `lengthText`; the old
	 * generic walker therefore reported zero candidates even though the ids were
	 * present in `reelWatchEndpoint`.
	 */
	@Test
	fun `extracts modern Shorts lockup cards for watch-page corroboration`() {
		val body = """{"contents":[{"shortsLockupViewModel":{
			"entityId":"shorts-shelf-item-L12DOfNlPKE",
			"onTap":{"innertubeCommand":{"reelWatchEndpoint":{
				"videoId":"L12DOfNlPKE"}}},
			"overlayMetadata":{"primaryText":{"content":
				"YOU NEED A NEW MASTER🔥 MR STARK- Tony Stark Is Back - AL NACER SLOWED #edit #marvel #MCU#shorts"},
				"secondaryText":{"content":"5.5K views"}}
		}}]}""".trimIndent()
		val candidates = SearchResultsParser.candidates(body)
		assertEquals(1, candidates.size)
		assertEquals("L12DOfNlPKE", candidates.single().videoId)
		assertEquals(
			"YOU NEED A NEW MASTER🔥 MR STARK- Tony Stark Is Back - AL NACER SLOWED #edit #marvel #MCU#shorts",
			candidates.single().title,
		)
		assertNull(candidates.single().channel)
		assertNull(candidates.single().lengthSeconds)
	}

	@Test
	fun `a Shorts card can be shortlisted only when its known fields do not contradict`() {
		val incomplete = SearchResultsParser.Candidate(
			videoId = "L12DOfNlPKE",
			title = "How Unai Simon Denied Messi's Clearest Goal Scoring Chance",
			channel = null,
			lengthSeconds = null,
		)
		assertEquals(
			true,
			SearchResultsParser.hasNoIdentityContradiction(
				incomplete,
				"How Unai Simón Denied Messi’s Clearest Goal Scoring Chance 🧠 #football",
				"Mind-boggling Football",
				59,
			),
		)
		assertEquals(
			false,
			SearchResultsParser.hasNoIdentityContradiction(
				incomplete.copy(title = "A different Messi clip"),
				"How Unai Simón Denied Messi’s Clearest Goal Scoring Chance 🧠 #football",
				"Mind-boggling Football",
				59,
			),
		)
		assertEquals(
			false,
			SearchResultsParser.hasNoIdentityContradiction(
				incomplete.copy(title = "#fyp #viral"),
				"#viral #fyp",
				"Mind-boggling Football",
				59,
			),
		)
	}

	@Test
	fun `watch-page completion tolerates Shorts presentation noise but still needs all evidence`() {
		val completed = SearchResultsParser.Candidate(
			videoId = "L12DOfNlPKE",
			title = "How Unai Simon Denied Messi's Clearest Goal Scoring Chance",
			channel = "Mind-boggling Football",
			lengthSeconds = 60,
		)
		assertEquals(
			true,
			SearchResultsParser.matchesIdentity(
				completed,
				"How Unai Simón Denied Messi’s Clearest Goal Scoring Chance 🧠 #football",
				"Mind-boggling Football",
				59,
			),
		)
		assertEquals(
			false,
			SearchResultsParser.matchesIdentity(
				completed.copy(channel = "A repost channel"),
				"How Unai Simón Denied Messi’s Clearest Goal Scoring Chance 🧠 #football",
				"Mind-boggling Football",
				59,
			),
		)
	}

	@Test
	fun `an exact hashtag-only title still requires channel and duration`() {
		val correct = SearchResultsParser.Candidate(
			videoId = "UXgqmLDgU4c",
			title = "#hoyoverse",
			channel = "Mr Time Edits",
			lengthSeconds = 135,
		)
		val candidates = listOf(
			correct,
			correct.copy(videoId = "wrongHash01", title = "#unrelated"),
			correct.copy(videoId = "wrongDur001", lengthSeconds = 99),
			correct.copy(videoId = "wrongOwner1", channel = "Repost Channel"),
		)

		assertEquals(
			listOf(correct),
			SearchResultsParser.identityMatches(
				candidates, "#hoyoverse", "Mr Time Edits", 135,
			),
		)
		assertEquals(
			true,
			SearchResultsParser.hasNoIdentityContradiction(
				correct.copy(channel = null, lengthSeconds = null),
				"#hoyoverse",
				"Mr Time Edits",
				135,
			),
		)
	}

	/**
	 * The reported track. The session said 274 s while search displays 4:35
	 * (275 s) — search rounds, so the duration check needs tolerance. Verified
	 * against the watch page: CZFTfYYql4k really is 274 s, "Bring Me The
	 * Horizon - Topic".
	 */
	@Test
	fun `resolves the reported track`() {
		val match = SearchResultsParser.bestMatch(
			SearchResultsParser.candidates(fixture),
			title = "Doomed",
			channel = "Bring Me The Horizon - Topic",
			durationSec = 274,
		)
		assertEquals("CZFTfYYql4k", match?.videoId)
	}

	/**
	 * The dangerous one. Same title, nearly the same length, different artist.
	 * Asking as Maphra must return Maphra's upload, never the BMTH original.
	 */
	@Test
	fun `does not confuse a same-titled cover by another artist`() {
		val match = SearchResultsParser.bestMatch(
			SearchResultsParser.candidates(fixture),
			title = "Doomed",
			channel = "Maphra - Topic",
			durationSec = 276,
		)
		assertEquals("DIEI2YLYg6o", match?.videoId)
	}

	@Test
	fun `a wrong duration is not a match`() {
		assertNull(
			SearchResultsParser.bestMatch(
				SearchResultsParser.candidates(fixture),
				title = "Doomed",
				channel = "Bring Me The Horizon - Topic",
				durationSec = 200,
			),
		)
	}

	@Test
	fun `a channel that matches nothing is not a match`() {
		assertNull(
			SearchResultsParser.bestMatch(
				SearchResultsParser.candidates(fixture),
				title = "Doomed",
				channel = "Some Reupload Channel",
				durationSec = 274,
			),
		)
	}

	@Test
	fun `two indistinguishable uploads are ambiguous rather than first one wins`() {
		val duplicate = SearchResultsParser.Candidate(
			videoId = "abcdefghijk",
			title = "Doomed",
			channel = "Bring Me The Horizon",
			lengthSeconds = 275,
		)
		assertNull(
			SearchResultsParser.bestMatch(
				listOf(duplicate, duplicate.copy(videoId = "zyxwvutsrqp")),
				"Doomed",
				"Bring Me The Horizon - Topic",
				274,
			),
		)
	}

	/** Without a duration the match can't be verified, so it must be refused. */
	@Test
	fun `no duration means no match`() {
		assertNull(
			SearchResultsParser.bestMatch(
				SearchResultsParser.candidates(fixture),
				title = "Doomed",
				channel = "Bring Me The Horizon - Topic",
				durationSec = null,
			),
		)
	}

	/** A blank channel is the common no-evidence case and must not match. */
	@Test
	fun `no channel means no match`() {
		assertNull(
			SearchResultsParser.bestMatch(
				SearchResultsParser.candidates(fixture),
				title = "Doomed",
				channel = null,
				durationSec = 274,
			),
		)
	}

	@Test
	fun `a VEVO channel matches its spaced-out search listing`() {
		val body = """{"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":
			{"contents":[{"videoRenderer":{"videoId":"CSvFpBOe8eY",
			"title":{"runs":[{"text":"System Of A Down - Chop Suey! (Official HD Video)"}]},
			"ownerText":{"runs":[{"text":"System Of A Down"}]},
			"lengthText":{"simpleText":"3:29"}}}]}}]}}}""".trimIndent()
		val match = SearchResultsParser.bestMatch(
			SearchResultsParser.candidates(body),
			title = "System Of A Down - Chop Suey! (Official HD Video)",
			channel = "systemofadownVEVO",
			durationSec = 208,
		)
		assertEquals("CSvFpBOe8eY", match?.videoId)
	}

	@Test
	fun `channel keys ignore spacing and known suffixes`() {
		assertEquals(
			SearchResultsParser.channelKey("System Of A Down"),
			SearchResultsParser.channelKey("systemofadownVEVO"),
		)
		assertEquals(
			SearchResultsParser.channelKey("The Verve"),
			SearchResultsParser.channelKey("TheVerveVEVO"),
		)
		assertEquals(
			SearchResultsParser.channelKey("Linkin Park"),
			SearchResultsParser.channelKey("Linkin Park - Topic"),
		)
		assertEquals(
			SearchResultsParser.channelKey("SUBHAN-EDITS"),
			SearchResultsParser.channelKey("Subhan Edits"),
		)
		assertEquals(
			SearchResultsParser.channelKey("Música Panamá"),
			SearchResultsParser.channelKey("Musica-Panama"),
		)
		// Different artists must still differ.
		assertNotEquals(
			SearchResultsParser.channelKey("Bon Jovi"),
			SearchResultsParser.channelKey("Bon Iver"),
		)
	}

	@Test
	fun `compound owner and explicit collaborator bylines resolve safely`() {
		val body = """{"contents":[
			{"videoRenderer":{"videoId":"lZizLbWxr_E",
			"title":{"runs":[{"text":"Spice, Sean Paul, Shaggy - Go Down Deh | Official Music Video"}]},
			"longBylineText":{"runs":[{"text":"Spice Official"}]},"lengthText":{"simpleText":"3:06"}}},
			{"videoRenderer":{"videoId":"dn3d8awSA0c",
			"title":{"runs":[{"text":"Kybba, Ryan Castro, Sean Paul & Busy Signal - BA BA BAD REMIX (Video Oficial)"}]},
			"longBylineText":{"runs":[{"text":"Ryan Castro and Kybba","navigationEndpoint":
			{"showDialogCommand":{"panelLoadingStrategy":{}}}}]},"lengthText":{"simpleText":"2:30"}}},
			{"videoRenderer":{"videoId":"napM9rZUzmU",
			"title":{"runs":[{"text":"Blaiz Fayah X Maureen - Money Pull Up (Official Video)"}]},
			"longBylineText":{"runs":[{"text":"Blaiz Fayah and 2 more","navigationEndpoint":
			{"showDialogCommand":{"panelLoadingStrategy":{}}}}]},"lengthText":{"simpleText":"2:18"}}}
		]}""".trimIndent()
		val candidates = SearchResultsParser.candidates(body)
		assertEquals(
			"lZizLbWxr_E",
			SearchResultsParser.bestMatch(
				candidates, "Spice, Sean Paul, Shaggy - Go Down Deh | Official Music Video",
				"SpiceOfficialVEVO", 185,
			)?.videoId,
		)
		assertEquals(
			"dn3d8awSA0c",
			SearchResultsParser.bestMatch(
				candidates,
				"Kybba, Ryan Castro, Sean Paul & Busy Signal - BA BA BAD REMIX (Video Oficial)",
				"Ryan Castro", 149,
			)?.videoId,
		)
		assertEquals(
			"napM9rZUzmU",
			SearchResultsParser.bestMatch(
				candidates, "Blaiz Fayah X Maureen - Money Pull Up (Official Video)",
				"Blaiz Fayah", 137,
			)?.videoId,
		)
	}

	@Test
	fun `collaborator relaxation requires YouTube marker and unique identity`() {
		val title = "Feid, Young Miko - Classy 101 (Official Video)"
		val duplicate = SearchResultsParser.Candidate(
			"cD5T1Y4b7wA", title, "Feid and Young Miko", 195,
			collaborativeChannel = true,
		)
		assertNull(
			SearchResultsParser.bestMatch(
				listOf(duplicate, duplicate.copy(videoId = "DwUA6misBRg")),
				title, "FeidVEVO", 195,
			),
		)
		assertNull(
			SearchResultsParser.bestMatch(
				listOf(duplicate.copy(collaborativeChannel = false)),
				title, "FeidVEVO", 195,
			),
		)
	}

	@Test
	fun `clock parsing handles both shapes`() {
		assertEquals(275L, SearchResultsParser.parseClock("4:35"))
		assertEquals(3753L, SearchResultsParser.parseClock("1:02:33"))
		assertNull(SearchResultsParser.parseClock("LIVE"))
	}

	@Test
	fun `empty results yield nothing rather than throwing`() {
		val c = SearchResultsParser.candidates("""{"contents":{}}""")
		assertEquals(0, c.size)
		assertNull(SearchResultsParser.bestMatch(c, "Doomed", "BMTH", 274))
	}

	/** The extractor the resolver reuses must find ytInitialData in a page. */
	@Test
	fun `initial data is extractable from a page wrapper`() {
		val page = "<html><script>var ytInitialData = $fixture;</script></html>"
		assertNotNull(WatchPageParser.extractJson(page, "ytInitialData"))
	}

	@Test
	fun `zero-width spaces in a screen title are not identity evidence`() {
		val screen = "#music​ #news​ #hiphop​"
		val page = "#music #news #hiphop"
		assertEquals(SearchResultsParser.titleKey(page), SearchResultsParser.titleKey(screen))
		assertEquals(
			com.rustedwax.app.detect.VideoTitleMatcher.Evidence.EXACT,
			com.rustedwax.app.detect.VideoTitleMatcher.compare(screen, page),
		)

		// A zero-width space inside a word is a different question — it must not
		// silently join or split one. Both sides normalize the same way.
		assertEquals(
			SearchResultsParser.titleKey("Barbie Tingz"),
			SearchResultsParser.titleKey("Barbie​ Tingz"),
		)

		// And it must not survive into a key as a character of its own, which
		// would make two identical titles compare unequal.
		val withMarks = "Bam Bam Dance​ Performance​"
		assertEquals("bam bam dance performance", SearchResultsParser.titleKey(withMarks))
	}
}
