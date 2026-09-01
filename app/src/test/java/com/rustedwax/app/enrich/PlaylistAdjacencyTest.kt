package com.rustedwax.app.enrich

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistAdjacencyTest {
	private fun entry(id: String) = SearchResultsParser.Candidate(id, id, null, 100)

	@Test
	fun `requires exact adjacency and direction`() {
		val entries = listOf(
			entry("8RddqlctLnk"),
			entry("FGjyRjd1_jQ"),
			entry("4ZnHHd3i8I4"),
		)

		assertTrue(PlaylistPageParser.containsAdjacent(entries, "8RddqlctLnk", "FGjyRjd1_jQ"))
		assertFalse(PlaylistPageParser.containsAdjacent(entries, "FGjyRjd1_jQ", "8RddqlctLnk"))
		assertFalse(PlaylistPageParser.containsAdjacent(entries, "8RddqlctLnk", "4ZnHHd3i8I4"))
		assertEquals(
			listOf("4ZnHHd3i8I4"),
			PlaylistPageParser.followersAfterAdjacent(
				entries, "8RddqlctLnk", "FGjyRjd1_jQ",
			).map(SearchResultsParser.Candidate::videoId),
		)
	}

	@Test
	fun `finds every follower when a predecessor pair repeats`() {
		val entries = listOf(
			entry("8RddqlctLnk"), entry("FGjyRjd1_jQ"), entry("4ZnHHd3i8I4"),
			entry("xxxxxxxxxxx"),
			entry("8RddqlctLnk"), entry("FGjyRjd1_jQ"), entry("yyyyyyyyyyy"),
		)

		assertEquals(
			listOf("4ZnHHd3i8I4", "yyyyyyyyyyy"),
			PlaylistPageParser.followersAfterAdjacent(
				entries, "8RddqlctLnk", "FGjyRjd1_jQ",
			).map(SearchResultsParser.Candidate::videoId),
		)
	}
}
