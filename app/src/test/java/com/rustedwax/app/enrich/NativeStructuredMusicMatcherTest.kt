package com.rustedwax.app.enrich

import com.rustedwax.youtube.identity.VideoResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStructuredMusicMatcherTest {
	private val resolver = VideoIdResolver()

	private fun candidate(
		id: String,
		title: String,
		channel: String,
		duration: Long,
	) = VideoResolution(id, "fixture", title, channel, duration)

	@Test
	fun `four measured native playlist shapes reduce to exact works and credits`() {
		val cases = listOf(
			Triple(
				candidate(
					"U8urOf52AlA", "Ozuna - Se Preparó (Video Oficial) | Odisea", "Ozuna", 188,
				),
				"Se Preparó",
				"Ozuna",
			),
			Triple(
				candidate(
					"t_jHrUE5IOk", "Maluma - Felices los 4 (Official Video)", "Maluma", 230,
				),
				"Felices los 4",
				"Maluma",
			),
			Triple(
				candidate(
					"abcdef12345",
					"J Balvin - Si Tu Novio Te Deja Sola ft. Bad Bunny",
					"J Balvin",
					244,
				),
				"Si Tu Novio Te Deja Sola",
				"Bad Bunny",
			),
			Triple(
				candidate(
					"5YXxnHVYRDk",
					"Ozuna - No Quiere Enamorarse (Official Lyric Video)",
					"Ozunapr",
					213,
				),
				"No Quiere Enamorarse",
				"Ozuna",
			),
		)

		for ((page, nativeTitle, nativeArtist) in cases) {
			assertTrue(
				"${page.title} should prove $nativeArtist — $nativeTitle",
				NativeStructuredMusicMatcher.matches(
					page, nativeTitle, nativeArtist, page.lengthSeconds!!,
				),
			)
		}
	}

	@Test
	fun `bare canonical title still requires exact page channel credit`() {
		val exact = candidate("abcdefghijk", "Bonita", "Release - Topic", 265)
		assertTrue(
			NativeStructuredMusicMatcher.matches(exact, "Bonita", "Release - Topic", 265),
		)
		assertFalse(
			NativeStructuredMusicMatcher.matches(
				exact.copy(channel = "Unrelated Repost"), "Bonita", "Release - Topic", 265,
			),
		)
	}

	@Test
	fun `credit matching is complete and never substring based`() {
		val page = candidate(
			"abcdefghijk",
			"J Balvin - Si Tu Novio Te Deja Sola ft. Bad Bunny",
			"J Balvin",
			244,
		)
		assertTrue(
			NativeStructuredMusicMatcher.matches(
				page, "Si Tu Novio Te Deja Sola", "Bad Bunny", 244,
			),
		)
		assertFalse(
			NativeStructuredMusicMatcher.matches(
				page, "Si Tu Novio Te Deja Sola", "Bunny", 244,
			),
		)
		assertFalse(
			NativeStructuredMusicMatcher.matches(
				page, "Si Tu Novio Te Deja", "Bad Bunny", 244,
			),
		)
	}

	@Test
	fun `search card consumes budget only after exact work and complete artist credit`() {
		assertTrue(
			NativeStructuredMusicMatcher.couldDescribeTrack(
				"Ozuna - Se Preparó (Video Oficial)", "Ozuna", "Se Preparó", "Ozuna",
			),
		)
		assertTrue(
			NativeStructuredMusicMatcher.couldDescribeTrack(
				"J Balvin - Si Tu Novio Te Deja Sola ft. Bad Bunny",
				"J Balvin",
				"Si Tu Novio Te Deja Sola",
				"Bad Bunny",
			),
		)
		assertFalse(
			NativeStructuredMusicMatcher.couldDescribeTrack(
				"Ozuna - Se Preparó (Video Oficial)", "Ozuna", "Se Preparó", "Ozu",
			),
		)
		assertFalse(
			NativeStructuredMusicMatcher.couldDescribeTrack(
				"Se Preparó", "Unrelated Repost", "Se Preparó", "Ozuna",
			),
		)
		assertFalse(
			NativeStructuredMusicMatcher.couldDescribeTrack(
				"Ozuna - Se Preparó for the tour", "Ozuna", "Se Preparó", "Ozuna",
			),
		)
	}

	@Test
	fun `exact canonical author survives a collaboration presentation prefix`() {
		val criminal = candidate(
			"VqEbCxg2bNI",
			"Natti Natasha ❌ Ozuna - Criminal [Official Video]",
			"NATTI NATASHA",
			273,
		)
		assertTrue(
			NativeStructuredMusicMatcher.couldDescribeTrack(
				criminal.title, criminal.channel, "Criminal", "NATTI NATASHA",
			),
		)
		assertTrue(
			NativeStructuredMusicMatcher.matches(
				criminal, "Criminal", "NATTI NATASHA", 273,
			),
		)
		assertEquals(
			"VqEbCxg2bNI",
			NativeStructuredMusicMatcher.select(
				listOf(criminal), "Criminal", "NATTI NATASHA", 273,
			).resolution?.videoId,
		)

		assertFalse(
			NativeStructuredMusicMatcher.matches(
				criminal.copy(channel = "Unrelated Repost"),
				"Criminal",
				"NATTI NATASHA",
				273,
			),
		)
		assertFalse(
			NativeStructuredMusicMatcher.matches(criminal, "Criminal", "Natti", 273),
		)
	}

	@Test
	fun `native featured work uses the same narrow suffix grammar as canonical pages`() {
		val officialAudio = candidate(
			"ohp8cXhIHXc",
			"Aventura feat. Don Omar - Ella Y Yo (Official Audio)",
			"Aventura",
			269,
		)
		val topicLive = candidate(
			"3-t7qlOfikI",
			"Ella y Yo (Live)",
			"Aventura - Topic",
			268,
		)

		assertTrue(
			NativeStructuredMusicMatcher.matches(
				officialAudio, "Ella Y Yo (Feat. Don Omar)", "Aventura", 268,
			),
		)
		val ambiguous = NativeStructuredMusicMatcher.select(
			listOf(officialAudio, topicLive),
			"Ella Y Yo (Feat. Don Omar)",
			"Aventura",
			268,
		)
		assertNull(ambiguous.resolution)
		assertTrue(ambiguous.refusalReason.orEmpty().contains("ambiguous identity — 2 uploads"))

		assertFalse(
			NativeStructuredMusicMatcher.matches(
				officialAudio.copy(title = "Aventura - Ella Y Yo"),
				"Ella Y Yo (Remix)",
				"Aventura",
				268,
			),
		)
		assertFalse(
			NativeStructuredMusicMatcher.matches(
				officialAudio.copy(title = "Aventura - Ella Y Yo"),
				"Ella Y Yo (Sold Out at Madison Square Garden)",
				"Aventura",
				268,
			),
		)
	}

	@Test
	fun `measured release topic artist and duration contradictions remain unresolved`() {
		val rightDurationWrongCredits = candidate(
			"QkngZ1P3aKw",
			"Ozuna Feat. Juanka El Problematik - Si Te Dejas Llevar",
			"Juanka El Problematik",
			228,
		)
		val rightAuthorWrongDuration = candidate(
			"B5eRr5sds-M",
			"Si Te Dejas Llevar",
			"Release - Topic",
			218,
		)

		val attempt = NativeStructuredMusicMatcher.select(
			listOf(rightDurationWrongCredits, rightAuthorWrongDuration),
			"Si Te Dejas Llevar",
			"Release - Topic",
			228,
		)
		assertNull(attempt.resolution)
		assertTrue(attempt.refusalReason.orEmpty().contains("no fully fetched candidate matched"))
	}

	@Test
	fun `artist-aware prefilter removes title noise before bounded page verification`() {
		val exact = (0 until VideoIdResolver.MAX_WATCH_PAGE_CANDIDATES).map { index ->
			SearchResultsParser.Candidate(
				videoId = "exact${index.toString().padStart(6, '0')}",
				title = "Ozuna - Se Preparó (Video Oficial)",
				channel = "Ozuna",
				lengthSeconds = 188,
			)
		}
		val titleOnlyNoise = (0 until 20).map { index ->
			SearchResultsParser.Candidate(
				videoId = "noise${index.toString().padStart(6, '0')}",
				title = "Se Preparó",
				channel = "Unrelated Repost $index",
				lengthSeconds = 188,
			)
		}
		assertEquals(
			exact.map(SearchResultsParser.Candidate::videoId),
			resolver.structuredNativeCandidates(
				exact + titleOnlyNoise, "Se Preparó", "Ozuna", 188,
			).map(SearchResultsParser.Candidate::videoId),
		)
		val overBudget = exact + SearchResultsParser.Candidate(
			videoId = "exact999999",
			title = "Ozuna - Se Preparó (Lyrics)",
			channel = "Ozuna",
			lengthSeconds = 188,
		)
		assertEquals(
			VideoIdResolver.MAX_WATCH_PAGE_CANDIDATES + 1,
			resolver.structuredNativeCandidates(
				overBudget, "Se Preparó", "Ozuna", 188,
			).size,
		)
	}

	@Test
	fun `duration conflict and non structural containment refuse`() {
		val page = candidate(
			"abcdefghijk", "Ozuna - Se Preparó (Video Oficial)", "Ozuna", 188,
		)
		assertFalse(NativeStructuredMusicMatcher.matches(page, "Se Preparó", "Ozuna", 32))
		assertFalse(
			NativeStructuredMusicMatcher.matches(
				page.copy(title = "How Ozuna Se Preparó for the tour"),
				"Se Preparó",
				"Ozuna",
				188,
			),
		)
	}

	@Test
	fun `selection requires exactly one fully corroborated upload`() {
		val page = candidate(
			"abcdefghijk", "Ozuna - Se Preparó (Video Oficial)", "Ozuna", 188,
		)
		val unique = NativeStructuredMusicMatcher.select(
			listOf(page), "Se Preparó", "Ozuna", 188,
		)
		assertEquals("abcdefghijk", unique.resolution?.videoId)
		assertTrue(unique.resolution?.structuredNativeMusic == true)

		val ambiguous = NativeStructuredMusicMatcher.select(
			listOf(page, page.copy(videoId = "zyxwvutsrqp")), "Se Preparó", "Ozuna", 188,
		)
		assertNull(ambiguous.resolution)
		assertTrue(ambiguous.refusalReason.orEmpty().contains("ambiguous identity"))
	}

	/**
	 * The 2026-08-22 field class. YouTube ingested one master twice on the same
	 * auto-generated `- Topic` channel, so the pair is one recording rather than
	 * two candidate works.
	 */
	@Test
	fun `duplicate uploads of one recording collapse to a single deterministic id`() {
		val first = candidate("N0a9SYSaV4M", "Amazing Grace", "Mavado - Topic", 202)
		val second = candidate("SjaEZej8gnA", "Amazing Grace", "Mavado - Topic", 202)

		val collapsed = NativeStructuredMusicMatcher.sameRecording(listOf(first, second))
		assertEquals("N0a9SYSaV4M", collapsed?.videoId)
		// Deterministic regardless of the order the catalog returned them in.
		assertEquals(
			"N0a9SYSaV4M",
			NativeStructuredMusicMatcher.sameRecording(listOf(second, first))?.videoId,
		)

		assertEquals(
			"N0a9SYSaV4M",
			NativeStructuredMusicMatcher.sameRecordingAmong(
				listOf(second, first), "Amazing Grace", "Mavado", 202,
			)?.videoId,
		)
	}

	@Test
	fun `a genuinely different upload is still ambiguous, not a duplicate`() {
		val base = candidate("N0a9SYSaV4M", "Amazing Grace", "Mavado - Topic", 202)

		// One second apart is a different master — a radio edit or a remaster —
		// and must keep refusing even though it is inside the 5s search tolerance.
		assertNull(
			NativeStructuredMusicMatcher.sameRecording(
				listOf(base, base.copy(videoId = "SjaEZej8gnA", lengthSeconds = 203)),
			),
		)
		// A different uploader is two labels' uploads, not one ingest twice.
		assertNull(
			NativeStructuredMusicMatcher.sameRecording(
				listOf(base, base.copy(videoId = "SjaEZej8gnA", channel = "Mavado Gully Official")),
			),
		)
		// A different work never collapses.
		assertNull(
			NativeStructuredMusicMatcher.sameRecording(
				listOf(base, base.copy(videoId = "SjaEZej8gnA", title = "Don't Cry")),
			),
		)
		// A single candidate is not a duplicate set.
		assertNull(NativeStructuredMusicMatcher.sameRecording(listOf(base)))
		assertNull(NativeStructuredMusicMatcher.sameRecording(emptyList()))
	}

	/**
	 * Measured 2026-08-22 playing the Mavado album straight through. Three ingests
	 * of one master, trimmed differently, so nothing collapses on equal lengths —
	 * but the player published 169000 ms, which names exactly one of them.
	 */
	@Test
	fun `the player's own length names one ingest out of a differently-trimmed family`() {
		val family = listOf(
			candidate("ZV_VhN5g9hk", "Weh Dem A Do", "Mavado - Topic", 169),
			candidate("RJYPUKFnzs8", "Weh Dem A Do", "Mavado - Topic", 167),
			candidate("BIns5PQC7ck", "Weh Dem A Do", "Mavado - Topic", 166),
		)

		// 168925 ms rounds to 169 and names the canonical upload.
		assertEquals(
			"ZV_VhN5g9hk",
			NativeStructuredMusicMatcher.oneOfDuplicateFamily(family, playerSeconds = 169)?.videoId,
		)
		// Truncating that same duration to 168 names none of them, and the family
		// is not collapsible on length alone — so nothing is chosen arbitrarily.
		assertNull(NativeStructuredMusicMatcher.oneOfDuplicateFamily(family, playerSeconds = 168))
		assertNull(NativeStructuredMusicMatcher.oneOfDuplicateFamily(family, playerSeconds = null))
	}

	@Test
	fun `a duplicate family still refuses when it is not one recording`() {
		val base = candidate("ZV_VhN5g9hk", "Weh Dem A Do", "Mavado - Topic", 169)

		// A different uploader is not one channel ingesting twice.
		assertNull(
			NativeStructuredMusicMatcher.oneOfDuplicateFamily(
				listOf(base, base.copy(videoId = "RJYPUKFnzs8", channel = "Mavado Gully Official")),
				playerSeconds = 169,
			),
		)
        // A different work never becomes a family.
		assertNull(
			NativeStructuredMusicMatcher.oneOfDuplicateFamily(
				listOf(base, base.copy(videoId = "RJYPUKFnzs8", title = "Don't Cry", lengthSeconds = 142)),
				playerSeconds = 169,
			),
		)
		// An extended version far outside the family spread is a different item.
		assertNull(
			NativeStructuredMusicMatcher.oneOfDuplicateFamily(
				listOf(base, base.copy(videoId = "RJYPUKFnzs8", lengthSeconds = 400)),
				playerSeconds = 169,
			),
		)
	}

	@Test
	fun `identical-length ingests still collapse when the player cannot separate them`() {
		val pair = listOf(
			candidate("N0a9SYSaV4M", "Amazing Grace", "Mavado - Topic", 202),
			candidate("SjaEZej8gnA", "Amazing Grace", "Mavado - Topic", 202),
		)

		// Both are exactly the player's length, so the discriminator ties and the
		// deterministic representative applies.
		assertEquals(
			"N0a9SYSaV4M",
			NativeStructuredMusicMatcher.oneOfDuplicateFamily(pair, playerSeconds = 202)?.videoId,
		)
	}

	@Test
	fun `the collapse only sees candidates that already pass the structured test`() {
		val right = candidate("N0a9SYSaV4M", "Amazing Grace", "Mavado - Topic", 202)
		val wrongArtist = candidate("SjaEZej8gnA", "Amazing Grace", "George Nooks - Topic", 202)

		// The impostor is filtered out first, leaving one candidate — and one
		// candidate is not a duplicate set, so nothing is resolved by collapse.
		assertNull(
			NativeStructuredMusicMatcher.sameRecordingAmong(
                                listOf(right, wrongArtist), "Amazing Grace", "Mavado", 202,
			),
		)
	}

	/**
	 * Measured 2026-08-23. The player wrote the credit twice — once bare, once
	 * parenthesised — and the page wrote it once, so one stripping pass reduced
	 * them to two different works and a fully played listen was refused.
	 */
	@Test
	fun `every trailing feature marker is stripped, however many a surface wrote`() {
		val native = "Can't Take Wi Life Ft. Di Genius (feat. Di Genius)"
		val page = "Can't Take Wi Life Ft. Di Genius"

		assertTrue(NativeStructuredMusicMatcher.worksAgree(page, native))
		assertTrue(NativeStructuredMusicMatcher.worksAgree(native, page))
		assertTrue(NativeStructuredMusicMatcher.worksAgree(native, native))
		// A different work is still a different work.
		assertFalse(
			NativeStructuredMusicMatcher.worksAgree("Something Else (feat. Di Genius)", native),
		)
	}

	/**
	 * Measured 2026-08-23. The player and the watch page order the same title's
	 * parts differently, so a trailing-only strip left two different works.
	 */
	@Test
	fun `a parenthesised credit is stripped wherever the surface put it`() {
		val native = "Dancehall Frequency [Wavz] (feat. Jahnaton & 808 Delavega)"
		val page = "Dancehall Frequency (feat. Jahnaton, 808 Delavega) [Wavz]"

		assertTrue(NativeStructuredMusicMatcher.worksAgree(page, native))
		assertTrue(NativeStructuredMusicMatcher.worksAgree(native, page))
		// Only an explicit feature group is removed; other parentheticals are work.
		assertFalse(
			NativeStructuredMusicMatcher.worksAgree("Dancehall Frequency [Wavz] (Live)", native),
		)
	}

	/**
	 * Measured 2026-08-23. `Tan Tuddy - Raw` is a bare work, but the credit
	 * grammar split it on the dash for the candidate and not for the player, so a
	 * card that had already resolved was refused at finalization.
	 */
	@Test
	fun `a catalog title containing a dash still matches itself`() {
		val row = candidate("oI2Craz2f2s", "Tan Tuddy - Raw", "Aidonia", 190)

		assertTrue(NativeStructuredMusicMatcher.matches(row, "Tan Tuddy - Raw", "Aidonia", 190))
		// The `Artist - Track` page shape still resolves through the split path.
		assertTrue(
			NativeStructuredMusicMatcher.matches(
				candidate("U8urOf52AlA", "Ozuna - Se Preparó (Video Oficial)", "Ozuna", 188),
				"Se Preparó", "Ozuna", 188,
			),
		)
		// A genuinely different work is still refused.
		assertFalse(NativeStructuredMusicMatcher.matches(row, "Something Else", "Aidonia", 190))
	}
}
