package com.rustedwax.app.enrich

import com.rustedwax.youtube.identity.PerformerCreditEvidence
import com.rustedwax.youtube.identity.VideoResolution
import org.junit.Assert.assertEquals
import org.junit.Test

/** Legacy credit-provenance metadata is descriptive and never a scrobble gate. */
class PerformerCreditEvidenceTest {
	private fun page(
		title: String,
		channel: String,
		seconds: Long = 290,
	) = VideoResolution(
		videoId = "abcdefghijk",
		source = "fixture",
		title = title,
		channel = channel,
		lengthSeconds = seconds,
	)

	@Test
	fun `matching work credit and duration record listing provenance`() {
		val resolution = NativeStructuredMusicMatcher.withPerformerCreditEvidence(
			candidate = page("Bring Me The Horizon - Follow You (Official Video)", "Bring Me The Horizon"),
			nativeTitle = "Follow You (Official Video)",
			nativeArtist = "Bring Me The Horizon",
			durationSec = 290,
			evidence = PerformerCreditEvidence.YOUTUBE_LISTING_COMPLETE_CREDIT,
		)

		assertEquals(
			PerformerCreditEvidence.YOUTUBE_LISTING_COMPLETE_CREDIT,
			resolution.performerCreditEvidence,
		)
	}

	@Test
	fun `a different published artist records no credit provenance`() {
		val resolution = NativeStructuredMusicMatcher.withPerformerCreditEvidence(
			candidate = page("Actual Artist - Follow You (Official Video)", "Actual Artist"),
			nativeTitle = "Follow You (Official Video)",
			nativeArtist = "Bring Me The Horizon",
			durationSec = 290,
			evidence = PerformerCreditEvidence.YOUTUBE_LISTING_COMPLETE_CREDIT,
		)

		assertEquals(PerformerCreditEvidence.NONE, resolution.performerCreditEvidence)
	}

	@Test
	fun `one collaboration member records no complete-credit provenance`() {
		val resolution = NativeStructuredMusicMatcher.withPerformerCreditEvidence(
			candidate = page("Act One - Shared Work", "Act One"),
			nativeTitle = "Shared Work",
			nativeArtist = "Act One & Act Two",
			durationSec = 290,
			evidence = PerformerCreditEvidence.CANONICAL_PAGE_COMPLETE_CREDIT,
		)

		assertEquals(PerformerCreditEvidence.NONE, resolution.performerCreditEvidence)
	}

	@Test
	fun `complete collaboration may record canonical credit provenance`() {
		val resolution = NativeStructuredMusicMatcher.withPerformerCreditEvidence(
			candidate = page("Act One & Act Two - Shared Work", "Act One"),
			nativeTitle = "Shared Work",
			nativeArtist = "Act One & Act Two",
			durationSec = 290,
			evidence = PerformerCreditEvidence.CANONICAL_PAGE_COMPLETE_CREDIT,
		)

		assertEquals(
			PerformerCreditEvidence.CANONICAL_PAGE_COMPLETE_CREDIT,
			resolution.performerCreditEvidence,
		)
	}

	@Test
	fun `an unrelated distributor records no page-owner credit provenance`() {
		val resolution = NativeStructuredMusicMatcher.withPerformerCreditEvidence(
			candidate = page("Jah Cure - Georgina", "Zojak World Wide Official", 240),
			nativeTitle = "Georgina",
			nativeArtist = "Jah Cure",
			durationSec = 240,
			evidence = PerformerCreditEvidence.CANONICAL_PAGE_COMPLETE_CREDIT,
		)

		assertEquals(PerformerCreditEvidence.NONE, resolution.performerCreditEvidence)
	}

	@Test
	fun `listing backed routes share the same descriptive credit comparison`() {
		val routes = listOf(
			page("Known Artist - Known Work", "Known Artist").copy(
				source = "title+channel+duration search",
			),
			page("Known Artist - Known Work", "Known Artist").copy(
				source = "playlist public-list", playlistVerified = true,
			),
			page("Known Artist - Known Work", "Known Artist").copy(
				source = "watch history", historyVerified = true,
			),
			page("Known Artist - Known Work", "Known Artist").copy(
				source = "duplicate uploads of one recording",
			),
		)

		for (route in routes) {
			val trusted = NativeStructuredMusicMatcher.withPerformerCreditEvidence(
				route, "Known Work", "Known Artist", 290,
				PerformerCreditEvidence.YOUTUBE_LISTING_COMPLETE_CREDIT,
			)
			val mismatched = NativeStructuredMusicMatcher.withPerformerCreditEvidence(
				route, "Known Work", "Another Artist", 290,
				PerformerCreditEvidence.YOUTUBE_LISTING_COMPLETE_CREDIT,
			)
			val partial = NativeStructuredMusicMatcher.withPerformerCreditEvidence(
				route.copy(title = "Known Artist - Known Work", channel = "Known Artist"),
				"Known Work", "Known Artist & Collaborator", 290,
				PerformerCreditEvidence.YOUTUBE_LISTING_COMPLETE_CREDIT,
			)

			assertEquals(route.source, PerformerCreditEvidence.YOUTUBE_LISTING_COMPLETE_CREDIT,
				trusted.performerCreditEvidence)
			assertEquals(route.source, PerformerCreditEvidence.NONE, mismatched.performerCreditEvidence)
			assertEquals(route.source, PerformerCreditEvidence.NONE, partial.performerCreditEvidence)
		}
	}

	@Test
	fun `managed owner spelling may record canonical credit provenance`() {
		val resolution = NativeStructuredMusicMatcher.withPerformerCreditEvidence(
			candidate = page(
				"Sizzla Kalonji - Solid As A Rock (Official Audio)", "SizzlaVEVO", 215,
			),
			nativeTitle = "Solid As A Rock (Official Audio)",
			nativeArtist = "Sizzla Kalonji",
			durationSec = 215,
			evidence = PerformerCreditEvidence.CANONICAL_PAGE_COMPLETE_CREDIT,
		)

		assertEquals(
			PerformerCreditEvidence.CANONICAL_PAGE_COMPLETE_CREDIT,
			resolution.performerCreditEvidence,
		)
	}
}
