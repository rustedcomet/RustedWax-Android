package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationlessHashtagSelectionTest {

	/** Exact field shape: empty semantic keys exceeded the eight-page budget. */
	@Test
	fun `a durationless hashtag title selects only its exact search cards`() {
		val correct = candidate("UXgqmLDgU4c", "#hoyoverse")
		val results = listOf(correct) + (0..10).map { index ->
			candidate("wrong${index.toString().padStart(6, '0')}", "#unrelated$index")
		}

		assertEquals(
			listOf(correct),
			VideoIdResolver.durationlessTitleCandidates(results, "#hoyoverse"),
		)
	}

	private fun candidate(id: String, title: String) = SearchResultsParser.Candidate(
		videoId = id,
		title = title,
		channel = "Mr Time Edits",
		lengthSeconds = null,
	)
}
