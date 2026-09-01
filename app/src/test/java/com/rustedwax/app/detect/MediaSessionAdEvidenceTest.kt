package com.rustedwax.app.detect

import com.rustedwax.core.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MediaSessionAdEvidenceTest {

	private val chrome = "com.android.chrome"
	private val brave = "com.brave.browser"

	private fun track(
		packageName: String = chrome,
		token: Long = 1,
		title: String = "Public advert track",
	) = MediaSessionAdEvidence.TrackInstance(
		packageName = packageName,
		token = token,
		signature = TrackIdentity(title, "Uploader", null, 34_000),
	)

	private fun signal(
		packageName: String = chrome,
		atMillis: Long = 1_000,
		literal: String = "Sponsored",
	) = MediaSessionAdEvidence.Observation(packageName, literal, atMillis)

	@Before fun setUp() = MediaSessionAdEvidence.clearAll()
	@After fun tearDown() = MediaSessionAdEvidence.clearAll()

	@Test
	fun `an established unique watch track accepts its first exact label`() {
		val accepted = MediaSessionAdEvidence.observe(
			observation = signal(),
			instance = track(),
			instanceEstablishedBeforeObservation = true,
			unambiguous = true,
		)
		assertNotNull(accepted)
		assertEquals("Sponsored", accepted?.signal)
	}

	@Test
	fun `an unestablished instance requires the same signal to be re-observed`() {
		val instance = track()
		assertNull(
			MediaSessionAdEvidence.observe(
				signal(atMillis = 1_000), instance,
				instanceEstablishedBeforeObservation = false,
				unambiguous = true,
			),
		)
		assertNotNull(
			MediaSessionAdEvidence.observe(
				signal(atMillis = 1_100), instance,
				instanceEstablishedBeforeObservation = false,
				unambiguous = true,
			),
		)
	}

	@Test
	fun `label disappearance before confirmation clears provisional evidence`() {
		val instance = track()
		MediaSessionAdEvidence.observe(
			signal(atMillis = 1_000), instance,
			instanceEstablishedBeforeObservation = false,
			unambiguous = true,
		)
		MediaSessionAdEvidence.labelAbsent(chrome)
		assertNull(
			MediaSessionAdEvidence.observe(
				signal(atMillis = 1_100), instance,
				instanceEstablishedBeforeObservation = false,
				unambiguous = true,
			),
		)
	}

	@Test
	fun `label disappearance after acceptance does not erase frozen track evidence`() {
		val instance = track()
		assertNotNull(
			MediaSessionAdEvidence.observe(
				signal(), instance,
				instanceEstablishedBeforeObservation = true,
				unambiguous = true,
			),
		)
		MediaSessionAdEvidence.labelAbsent(chrome)
		assertEquals("Sponsored", MediaSessionAdEvidence.accepted(instance)?.signal)
	}

	@Test
	fun `ambiguous sessions clear provisional evidence and create no veto`() {
		val instance = track()
		MediaSessionAdEvidence.observe(
			signal(atMillis = 1_000), instance,
			instanceEstablishedBeforeObservation = false,
			unambiguous = true,
		)
		assertNull(
			MediaSessionAdEvidence.observe(
				signal(atMillis = 1_100), instance,
				instanceEstablishedBeforeObservation = true,
				unambiguous = false,
			),
		)
		assertNull(MediaSessionAdEvidence.accepted(instance, now = 1_101))
	}

	@Test
	fun `track change stale expiry and another package cannot inherit a signal`() {
		val advert = track(token = 10, title = "Advert B")
		MediaSessionAdEvidence.observe(
			signal(atMillis = 1_000), advert,
			instanceEstablishedBeforeObservation = false,
			unambiguous = true,
		)

		val organic = track(token = 11, title = "Organic A")
		assertNull(
			MediaSessionAdEvidence.observe(
				signal(atMillis = 1_100), organic,
				instanceEstablishedBeforeObservation = false,
				unambiguous = true,
			),
		)
		assertNull(MediaSessionAdEvidence.accepted(organic, now = 1_101))

		MediaSessionAdEvidence.clearPackage(chrome)
		val braveTrack = track(packageName = brave, token = 10, title = "Advert B")
		assertNull(MediaSessionAdEvidence.accepted(braveTrack, now = 1_102))

		MediaSessionAdEvidence.observe(
			signal(atMillis = 2_000), organic,
			instanceEstablishedBeforeObservation = false,
			unambiguous = true,
		)
		assertNull(
			MediaSessionAdEvidence.observe(
				signal(atMillis = 2_000 + MediaSessionAdEvidence.PROVISIONAL_TTL_MS + 1), organic,
				instanceEstablishedBeforeObservation = false,
				unambiguous = true,
			),
		)
	}

	@Test
	fun `stop reset and package teardown clear provisional state`() {
		val instance = track()
		MediaSessionAdEvidence.observe(
			signal(), instance,
			instanceEstablishedBeforeObservation = true,
			unambiguous = true,
		)
		MediaSessionAdEvidence.clearPackage(chrome)
		assertNull(MediaSessionAdEvidence.accepted(instance, now = 1_001))

		MediaSessionAdEvidence.observe(
			signal(atMillis = 2_000), instance,
			instanceEstablishedBeforeObservation = true,
			unambiguous = true,
		)
		MediaSessionAdEvidence.clearAll()
		assertNull(MediaSessionAdEvidence.accepted(instance, now = 2_001))
	}
}
