package com.rustedwax.app.enrich

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VerifiedIdentityCandidateCacheTest {

	private val pkg = "com.android.chrome"
	private val plus57 =
		"KAROL G, Feid, DFZM ft. Ovy On The Drums, J Balvin, Maluma, Ryan Castro, Blessd - +57"

	@Before fun setUp() = VerifiedIdentityCandidateCache.clearAll()
	@After fun tearDown() = VerifiedIdentityCandidateCache.clearAll()

	@Test
	fun `5r5UePOgMQU later replay becomes a candidate in the same run`() {
		VerifiedIdentityCandidateCache.remember(
			packageName = pkg,
			videoId = "5r5UePOgMQU",
			title = plus57,
			channel = "KarolGVEVO",
			durationMs = 301_701,
			now = 10_000,
		)
		assertEquals(
			listOf("5r5UePOgMQU"),
			VerifiedIdentityCandidateCache.candidates(
				pkg, plus57, "KarolGVEVO", 301_000, now = 20_000,
			),
		)
	}

	@Test
	fun `cache is capped aged and cleared at lifecycle boundaries`() {
		repeat(VerifiedIdentityCandidateCache.MAX_ENTRIES + 5) { index ->
			VerifiedIdentityCandidateCache.remember(
				pkg,
				"id${index.toString().padStart(9, '0')}",
				"Replay",
				"Uploader",
				180_000,
				now = index.toLong(),
			)
		}
		assertEquals(VerifiedIdentityCandidateCache.MAX_ENTRIES, VerifiedIdentityCandidateCache.size())
		VerifiedIdentityCandidateCache.clear(pkg)
		assertEquals(0, VerifiedIdentityCandidateCache.size())

		VerifiedIdentityCandidateCache.remember(
			pkg, "5r5UePOgMQU", plus57, "KarolGVEVO", 301_000, now = 1_000,
		)
		assertTrue(
			VerifiedIdentityCandidateCache.candidates(
				pkg, plus57, "KarolGVEVO", 301_000,
				now = 1_001 + VerifiedIdentityCandidateCache.MAX_AGE_MS,
			).isEmpty(),
		)
	}

	@Test
	fun `Best movie duplicate uploads remain ambiguous`() {
		val candidates = listOf("PlTiqSpwzTI", "VL_1TfgB2pw").map { id ->
			SearchResultsParser.Candidate(id, "Best movie!!! #movie #shorts", "Edit Channel", 173)
		}
		assertEquals(
			2,
			SearchResultsParser.identityMatches(
				candidates,
				"Best movie!!! #movie #shorts",
				"Edit Channel",
				173,
			).size,
		)
		assertNull(
			SearchResultsParser.bestMatch(
				candidates,
				"Best movie!!! #movie #shorts",
				"Edit Channel",
				173,
			),
		)
	}

	@Test
	fun `WHEN SINCE gets a bounded presentation-cleaned search variant`() {
		val queries = VideoIdResolver().searchQueries(
			"VYBZ KARTEL WHEN SINCE",
			"Vybz Kartel",
		)
		assertTrue(queries.any { it.equals("when since Vybz Kartel", ignoreCase = true) })
		assertTrue(queries.size <= 6)
	}

	@Test
	fun `foreground handle candidates are exact and legacy channel rows cannot satisfy them`() {
		val title = "Which is your favorite team?"
		VerifiedIdentityCandidateCache.remember(
			pkg, "Bf7Qtyr-2IQ", title, "Status", 41_000,
			ownerHandle = "@Status_svijet", now = 10_000,
		)
		VerifiedIdentityCandidateCache.remember(
			pkg, "abcdefghijk", title, "Status", 41_000, now = 11_000,
		)
		assertEquals(
			listOf("Bf7Qtyr-2IQ"),
			VerifiedIdentityCandidateCache.candidates(
				pkg, title, "Different display author", 42_000,
				ownerHandle = "@status_SVIJET", now = 12_000,
			),
		)
		assertTrue(
			VerifiedIdentityCandidateCache.candidates(
				pkg, title, "Status", 42_000,
				ownerHandle = "@status-svijet", now = 12_000,
			).isEmpty(),
		)
	}
}
