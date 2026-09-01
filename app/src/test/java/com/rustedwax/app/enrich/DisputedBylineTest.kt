package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The listing byline against the session's own channel name.
 *
 * Measured 2026-08-10 in Brave, playing a BMTH playlist: the media session
 * reported `ARTIST = "BMTHOfficialVEVO"` while both the search card and the
 * playlist row for the very same upload listed the owner as
 * "Bring Me The Horizon". Every one of those listens was refused with "video id
 * could not be verified".
 */
class DisputedBylineTest {

	private val resolver = VideoIdResolver()

	private val sessionTitle = "Bring Me The Horizon - Happy Song (Official Audio)"
	private val sessionChannel = "BMTHOfficialVEVO"
	private val sessionDuration = 236L

	private fun candidate(
		id: String,
		title: String = sessionTitle,
		channel: String? = "Bring Me The Horizon",
		length: Long? = 237L,
	) = SearchResultsParser.Candidate(id, title, channel, length)

	@Test
	fun `the VEVO card that was refused is put up for watch-page verification`() {
		val disputed = resolver.disputedBylineCandidates(
			listOf(candidate("GBRAnuT48qo")),
			sessionTitle,
			sessionChannel,
			sessionDuration,
		)
		assertEquals(listOf("GBRAnuT48qo"), disputed.map { it.videoId })
	}

	@Test
	fun `no string rule closes this gap, which is why the page has to decide`() {
		// If these ever normalized to each other the ordinary channel comparison
		// would already have accepted the card and nothing here would be needed.
		assertEquals("bmth", SearchResultsParser.channelKey(sessionChannel))
		assertEquals("bringmethehorizon", SearchResultsParser.channelKey("Bring Me The Horizon"))
	}

	@Test
	fun `the VEVO names that are not the artist run together are the ones in dispute`() {
		// Stripping ownership suffixes closes the gap only when what is left is
		// the artist's name with the spaces taken out. It is not, whenever the
		// channel is an abbreviation or a phrase — and those are the refusals.
		val stillBroken = listOf(
			"BMTHOfficialVEVO" to "Bring Me The Horizon",
			"IamdoechiiVEVO" to "Doechii",
			"FloLikeThisVEVO" to "FLO",
		)
		val alreadyFine = listOf(
			"dojacatVEVO" to "Doja Cat",
			"NickiMinajAtVEVO" to "Nicki Minaj",
		)
		stillBroken.forEach { (sessionName, listedName) ->
			assertEquals(
				"$sessionName vs $listedName",
				1,
				resolver.disputedBylineCandidates(
					listOf(candidate("GBRAnuT48qo", channel = listedName)),
					sessionTitle, sessionName, sessionDuration,
				).size,
			)
		}
		alreadyFine.forEach { (sessionName, listedName) ->
			assertTrue(
				"$sessionName vs $listedName",
				resolver.disputedBylineCandidates(
					listOf(candidate("GBRAnuT48qo", channel = listedName)),
					sessionTitle, sessionName, sessionDuration,
				).isEmpty(),
			)
		}
	}

	@Test
	fun `a byline that already agrees is not in dispute`() {
		// It resolves on the card, without spending a watch-page fetch.
		assertTrue(
			resolver.disputedBylineCandidates(
				listOf(candidate("GBRAnuT48qo", channel = "BMTHOfficialVEVO")),
				sessionTitle,
				sessionChannel,
				sessionDuration,
			).isEmpty(),
		)
	}

	@Test
	fun `only the byline may be in dispute — a different title or length is not`() {
		val wrongTitle = candidate("aaaaaaaaaaa", title = "Bring Me The Horizon - Drown")
		val wrongLength = candidate("bbbbbbbbbbb", length = 286L)
		val noByline = candidate("ccccccccccc", channel = null)
		listOf(wrongTitle, wrongLength, noByline).forEach {
			assertTrue(
				it.videoId,
				resolver.disputedBylineCandidates(
					listOf(it), sessionTitle, sessionChannel, sessionDuration,
				).isEmpty(),
			)
		}
	}

	@Test
	fun `the same five-second tolerance the card match uses`() {
		assertEquals(
			1,
			resolver.disputedBylineCandidates(
				listOf(candidate("GBRAnuT48qo", length = 241L)),
				sessionTitle, sessionChannel, sessionDuration,
			).size,
		)
		assertTrue(
			resolver.disputedBylineCandidates(
				listOf(candidate("GBRAnuT48qo", length = 242L)),
				sessionTitle, sessionChannel, sessionDuration,
			).isEmpty(),
		)
	}

	@Test
	fun `the whole live search page yields exactly one page to check, not dozens`() {
		// The measured 2026-08-10 search for this track: 79 unique candidates,
		// covers and live versions among them. Only the one upload survives
		// title + duration, so the bounded budget is never approached.
		val page = listOf(
			candidate("GBRAnuT48qo"),
			candidate("58LeVo7j46w", title = "Bring Me The Horizon - Happy Song (Live at the Royal Albert Hall)", length = 286L),
			candidate("_bDovaPkD7I", title = "Bring Me The Horizon - Happy Song", channel = "RockHype", length = 240L),
			candidate("q9fHyn3quKM", title = "Bring Me The Horizon - Happy Song Lyrics [HQ]", channel = "imnotugly", length = 240L),
			candidate("w2YKTu7dRr8", title = "Bring Me The Horizon - Happy Song (Clean)", channel = "R&M TV", length = 239L),
		)
		assertEquals(
			listOf("GBRAnuT48qo"),
			resolver.disputedBylineCandidates(page, sessionTitle, sessionChannel, sessionDuration)
				.map { it.videoId },
		)
	}

	@Test
	fun `a same-titled same-length cover by another channel still has to be checked, and its page refuses it`() {
		// "Doomed" / "Maphra - Topic" — the case this class exists to refuse. It
		// enters the dispute set, because its byline is exactly what disagrees,
		// and its watch page then names Maphra rather than the session's channel,
		// so the three-field rule throws it out there instead of here.
		val cover = SearchResultsParser.Candidate(
			"DIEI2YLYg6o", "Doomed", "Maphra - Topic", 276L,
		)
		val disputed = resolver.disputedBylineCandidates(
			listOf(cover), "Doomed", "Bring Me The Horizon - Topic", 274L,
		)
		assertEquals(listOf("DIEI2YLYg6o"), disputed.map { it.videoId })
		// What refuses it is the watch page's own author, checked by the same
		// strict three-field rule the card was checked with.
		assertTrue(
			!SearchResultsParser.matchesIdentity(
				cover, "Doomed", "Bring Me The Horizon - Topic", 274L,
			),
		)
	}
}
