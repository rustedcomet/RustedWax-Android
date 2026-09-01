package com.rustedwax.app.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatchCorroborationTest {

	// region duration

	/** The field case, to the second. */
	@Test
	fun `the wrong-url case is caught by duration alone`() {
		assertTrue(SessionProbe.durationsDisagree(sessionMs = 226_000, pageSeconds = 193))
	}

	/**
	 * `lengthSeconds` and the session's `DURATION` routinely differ by a second
	 * of rounding — a live page reported 39 where the session said 39141 ms.
	 */
	@Test
	fun `rounding slack is not a disagreement`() {
		assertFalse(SessionProbe.durationsDisagree(sessionMs = 39_141, pageSeconds = 39))
		assertFalse(SessionProbe.durationsDisagree(sessionMs = 39_141, pageSeconds = 40))
		assertFalse(SessionProbe.durationsDisagree(sessionMs = 240_000, pageSeconds = 240))
	}

	/**
	 * The proportional half of the tolerance: a flat 5 s threshold alone would
	 * reject a two-hour video over six seconds of rounding.
	 */
	@Test
	fun `a small absolute gap on a long video is tolerated`() {
		assertFalse(SessionProbe.durationsDisagree(sessionMs = 7_206_000, pageSeconds = 7_200))
	}

	/**
	 * And the absolute half: a percentage alone would let a 30-second
	 * disagreement pass on that same video.
	 */
	@Test
	fun `a large absolute gap on a long video is still caught`() {
		assertTrue(SessionProbe.durationsDisagree(sessionMs = 7_200_000, pageSeconds = 3_600))
	}

	/** A short clip has little room, so a few seconds is a real disagreement. */
	@Test
	fun `a few seconds is a disagreement on a short clip`() {
		assertTrue(SessionProbe.durationsDisagree(sessionMs = 18_000, pageSeconds = 40))
	}

	/** Nothing to compare is not a disagreement — the check must not fail closed. */
	@Test
	fun `missing values are never a disagreement`() {
		assertFalse(SessionProbe.durationsDisagree(sessionMs = null, pageSeconds = 193))
		assertFalse(SessionProbe.durationsDisagree(sessionMs = 226_000, pageSeconds = null))
		assertFalse(SessionProbe.durationsDisagree(sessionMs = null, pageSeconds = null))
		assertFalse(SessionProbe.durationsDisagree(sessionMs = 0, pageSeconds = 193))
		assertFalse(SessionProbe.durationsDisagree(sessionMs = 226_000, pageSeconds = 0))
	}
	// endregion

	// region title
	private fun corroborates(first: String, second: String): Boolean =
		SessionProbe.titleEvidence(first, second) != VideoTitleMatcher.Evidence.CONTRADICTION

	@Test
	fun `identical titles match`() {
		assertTrue(corroborates("Korn - Trash", "Korn - Trash"))
		assertTrue(corroborates("KORN - TRASH", "korn - trash"))
		assertTrue(corroborates("Korn  -  Trash", "Korn - Trash"))
	}

	/** Chromium truncates the media-session title on long ones. */
	@Test
	fun `a truncated title still matches`() {
		assertTrue(
			corroborates(
				"Fall 2: Deadpoint (2026) Official Trailer 2 - Harriet Slater",
				"Fall 2: Deadpoint (2026) Official Trailer 2",
			),
		)
	}

	@Test
	fun `two different videos do not match`() {
		assertFalse(corroborates("Con Calma", "Para Mis Soldados - Danger Man"))
		assertFalse(corroborates("Bad Bunny", "Bad Bunny - Another Song"))
	}

	@Test
	fun `localized and structural title presentations retain their own ids`() {
		assertTrue(
			corroborates(
				"BAD BUNNY - SOY PEOR (Video Oficial)",
				"BAD BUNNY - SOY PEOR (Official Video)",
			),
		)
		assertTrue(
			corroborates(
				"Bad Bunny ft. Chencho Corleone - Me Porto Bonito (Video Oficial) | " +
					"Un Verano Sin Ti",
				"Bad Bunny (ft. Chencho Corleone) - Me Porto Bonito (Official Video) | " +
					"Un Verano Sin Ti",
			),
		)
		assertTrue(
			corroborates(
				"BAD BUNNY x JHAY CORTEZ - DÁKITI (Video Oficial)",
				"BAD BUNNY x JHAY CORTEZ - DÁKITI | EL ÚLTIMO TOUR DEL MUNDO " +
					"(Official Video)",
			),
		)
	}

	@Test
	fun `adjacent tracks and ads remain contradictions`() {
		assertFalse(
			corroborates(
				"BAD BUNNY - SOY PEOR (Official Video)",
				"Bad Bunny ft. Chencho Corleone - Me Porto Bonito (Video Oficial) | " +
					"Un Verano Sin Ti",
			),
		)
		assertFalse(
			corroborates(
				"Bad Bunny (ft. Chencho Corleone) - Me Porto Bonito (Official Video) | " +
					"Un Verano Sin Ti",
				"KAROL G, Shakira - TQG (Official Video)",
			),
		)
		assertFalse(
			corroborates(
				"BAD BUNNY x JHAY CORTEZ - DÁKITI | EL ÚLTIMO TOUR DEL MUNDO " +
					"(Official Video)",
				"FloyyMenor, Cris MJ - Gata Only (Video Oficial)",
			),
		)
		assertFalse(
			corroborates(
				"Abre la puerta a un mundo de experiencias con Mastercard",
				"Arcángel, Bad Bunny - Me Acostumbré (Video Oficial)",
			),
		)
		assertFalse(
			corroborates(
				"PA P&G 2026 Vick + Jarabe 15s",
				"W Sound 05 \"LA PLENA\" - Beéle, Westcol, Ovy On The Drums",
			),
		)
	}

	@Test
	fun `an empty title is never a match`() {
		assertFalse(corroborates("", "Con Calma"))
		assertFalse(corroborates("Con Calma", "   "))
	}
	// endregion

	/**
	 * v0.8.8's delayed-finalization failure: the live value and the latch were
	 * the same next-video id. Clearing the latch and then returning
	 * `latchedVideo ?: live` resurrected it immediately.
	 */
	@Test
	fun `a live identity rejected on this pass cannot enter the snapshot`() {
		val nextVideo = YouTubeProbe.Identity.Confirmed(
			videoId = "ysY13cbxJR4",
			url = "https://www.youtube.com/watch?v=ysY13cbxJR4",
			isMusic = false,
			isShort = true,
			source = "address bar",
		)
		val selected = SessionProbe.identityAfterCorroboration(
			latched = null,
			live = nextVideo,
			rejectedVideoIds = setOf(nextVideo.videoId),
			rejectedThisPass = true,
		)
		assertTrue(selected is YouTubeProbe.Identity.Unconfirmed)
	}

	@Test
	fun `a correct address bar id replaces the outgoing id after rejection`() {
		val outgoing = UrlEvidence.Evidence(
			host = "m.youtube.com",
			videoId = "QuQW1vkDA1c",
			playlistId = "PLoldPlaylist000000",
			raw = "m.youtube.com/watch?v=QuQW1vkDA1c",
			generation = 12,
		)
		val correct = UrlEvidence.Evidence(
			host = "m.youtube.com",
			videoId = "lir3dzYIhz0",
			playlistId = "PLnewPlaylist000000",
			raw = "m.youtube.com/watch?v=lir3dzYIhz0",
			generation = 13,
		)
		val first = resolverContextWithObservedUrl(ResolverContext(), outgoing, emptySet())
		val repaired = resolverContextWithObservedUrl(
			first, correct, rejectedVideoIds = setOf("QuQW1vkDA1c"),
		)

		assertEquals("lir3dzYIhz0", repaired.observedVideoId)
		assertEquals(13L, repaired.urlGeneration)
		assertEquals(correct.raw, repaired.observedUrl)
		assertEquals("PLnewPlaylist000000", repaired.playlistId)
		assertTrue(
			SessionProbe.titleEvidenceMayRetainObservedId(
				evidence = VideoTitleMatcher.Evidence.WEAK_SHORT_CANONICAL_CORE,
				candidateVideoId = "lir3dzYIhz0",
				candidateGeneration = 13,
				observedVideoId = repaired.observedVideoId,
				observedGeneration = repaired.urlGeneration,
				sessionDurationMs = 236_741,
				pageDurationSeconds = 237,
			),
		)
	}

	@Test
	fun `a later address bar id cannot replace a still-valid track id`() {
		val current = UrlEvidence.Evidence(
			host = "m.youtube.com",
			videoId = "lir3dzYIhz0",
			raw = "m.youtube.com/watch?v=lir3dzYIhz0",
			generation = 13,
		)
		val successor = UrlEvidence.Evidence(
			host = "m.youtube.com",
			videoId = "UNaYpBpRJOY",
			raw = "m.youtube.com/watch?v=UNaYpBpRJOY",
			generation = 14,
		)
		val first = resolverContextWithObservedUrl(ResolverContext(), current, emptySet())
		val frozen = resolverContextWithObservedUrl(first, successor, emptySet())

		assertEquals("lir3dzYIhz0", frozen.observedVideoId)
		assertEquals(13L, frozen.urlGeneration)
	}

	// region what counts against an id

	private fun disagreement(
		evidence: VideoTitleMatcher.Evidence?,
		weakAllowed: Boolean = false,
		sessionMs: Long? = 236_981,
		pageSeconds: Long? = 237,
	) = SessionProbe.latchDisagreement(
		titleEvidence = evidence,
		weakEvidenceAllowed = weakAllowed,
		pageTitle = "Happy Song (Official Audio)",
		sessionTitle = "Bring Me The Horizon - Happy Song (Official Audio)",
		sessionDurationMs = sessionMs,
		pageDurationSeconds = pageSeconds,
	)

	@Test
	fun `weak title evidence drops the latch without counting against the id`() {
		val weak = disagreement(VideoTitleMatcher.Evidence.WEAK_SHORT_CANONICAL_CORE)
		assertEquals(false, weak?.contradicts)
		assertTrue(weak != null)
	}

	@Test
	fun `a page naming a different video is held against the id`() {
		assertEquals(
			true,
			disagreement(VideoTitleMatcher.Evidence.CONTRADICTION)?.contradicts,
		)
	}

	@Test
	fun `a duration that disagrees is held against the id`() {
		// The Con Calma case: a 193-second page against a 226-second session.
		val wrong = disagreement(
			VideoTitleMatcher.Evidence.EXACT, sessionMs = 226_000, pageSeconds = 193,
		)
		assertEquals(true, wrong?.contradicts)
	}

	@Test
	fun `a duration contradiction outranks a merely weak title`() {
		val both = disagreement(
			VideoTitleMatcher.Evidence.WEAK_SHORT_CANONICAL_CORE,
			sessionMs = 226_000,
			pageSeconds = 193,
		)
		assertEquals(true, both?.contradicts)
	}

	@Test
	fun `an agreeing page keeps the latch`() {
		assertEquals(null, disagreement(VideoTitleMatcher.Evidence.EXACT))
		assertEquals(null, disagreement(VideoTitleMatcher.Evidence.STRONG_CONTAINMENT))
		// Weak, but the duration corroborates it in the same generation.
		assertEquals(
			null,
			disagreement(
				VideoTitleMatcher.Evidence.WEAK_SHORT_CANONICAL_CORE, weakAllowed = true,
			),
		)
	}

	// endregion

	@Test
	fun `a corroborated latch remains preferred to later live evidence`() {
		val track = YouTubeProbe.Identity.Confirmed(
			videoId = "grNk0DpiaEE",
			url = "https://www.youtube.com/watch?v=grNk0DpiaEE",
			isMusic = false,
			isShort = true,
			source = "latched",
		)
		val later = track.copy(videoId = "ysY13cbxJR4")
		assertEquals(
			track,
			SessionProbe.identityAfterCorroboration(
				latched = track,
				live = later,
				rejectedVideoIds = emptySet(),
				rejectedThisPass = false,
			),
		)
	}
}
