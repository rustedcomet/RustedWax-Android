package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserForegroundLeadInTest {

	private val tracker = BrowserForegroundLeadIn()
	private val shortId = "LMvw_HabqEg"

	private fun observation(
		videoId: String? = shortId,
		generation: Long = 2,
		atMillis: Long = 1_000,
		isShort: Boolean = true,
	) = BrowserForegroundLeadIn.Observation(
		packageName = "com.brave.browser",
		videoId = videoId,
		urlGeneration = generation,
		isShort = isShort,
		atMillis = atMillis,
	)

	private fun candidate(
		key: String = "controller-1",
		describesVideo: Boolean = false,
		playing: Boolean = true,
		live: Boolean = true,
	) = BrowserForegroundLeadIn.Candidate(
		key = key,
		live = live,
		playing = playing,
		describesNamedVideo = describesVideo,
	)

	private fun claim() = tracker.claim(
		packageName = "com.brave.browser",
		videoId = shortId,
		urlGeneration = 2,
		key = "controller-1",
		instanceToken = 11,
		throughMillis = 33_000,
		nowMillis = 35_000,
		currentlySoleAndPlaying = true,
	)

	@Test
	fun `a later corroborated short claims the exact foreground playing interval`() {
		tracker.observe(observation(), listOf(candidate()))

		assertEquals(
			BrowserForegroundLeadIn.Credit(playedMs = 32_000, startedAtEpochSec = 1),
			claim(),
		)
		assertNull(claim())
	}

	@Test
	fun `an already matching listen starts no second clock`() {
		tracker.observe(observation(), listOf(candidate(describesVideo = true)))
		assertNull(claim())
	}

	@Test
	fun `multiple live tabs establish no lead in`() {
		tracker.observe(
			observation(),
			listOf(candidate(), candidate(key = "controller-2", playing = false)),
		)
		assertNull(claim())
	}

	@Test
	fun `a pause invalidates the unspent interval`() {
		tracker.observe(observation(), listOf(candidate()))
		tracker.invalidate("com.brave.browser", "controller-1")
		assertNull(claim())
	}

	@Test
	fun `a newer exact short replaces the old pending interval`() {
		tracker.observe(observation(), listOf(candidate()))
		tracker.observe(
			observation(videoId = "45qV0GydE3A", generation = 3, atMillis = 10_000),
			listOf(candidate()),
		)
		assertNull(claim())
	}

	@Test
	fun `a stale pending interval is refused`() {
		tracker.observe(observation(), listOf(candidate()))
		assertNull(
			tracker.claim(
				"com.brave.browser", shortId, 2, "controller-1", 11,
				throughMillis = 301_001, nowMillis = 301_001,
				currentlySoleAndPlaying = true,
			),
		)
	}

	@Test
	fun `an intervening metadata generation cannot inherit the interval`() {
		tracker.observe(observation(), listOf(candidate()))
		tracker.noteMetadataBoundary("com.brave.browser", "controller-1", 11, 33_000)
		tracker.noteMetadataBoundary("com.brave.browser", "controller-1", 12, 50_000)

		assertNull(
			tracker.claim(
				"com.brave.browser", shortId, 2, "controller-1", 12,
				throughMillis = 50_000, nowMillis = 52_000,
				currentlySoleAndPlaying = true,
			),
		)
	}
}
