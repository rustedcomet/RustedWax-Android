package com.rustedwax.app.detect

import com.rustedwax.core.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaSessionAccessibilityEvidenceTest {

	private val chrome = "com.android.chrome"
	private val brave = "com.brave.browser"

	private fun track(
		packageName: String = chrome,
		token: Long = 1,
		title: String = "Organic track A",
	) = MediaSessionAdEvidence.TrackInstance(
		packageName = packageName,
		token = token,
		signature = TrackIdentity(title, "Uploader", null, 180_000),
	)

	private fun scan(
		packageName: String = chrome,
		host: String? = "m.youtube.com",
		atMillis: Long = 2_000,
		urlGeneration: Long? = 7,
		videoId: String? = "abcdefghijk",
		signal: String? = null,
		rootVisible: Boolean = true,
	) = MediaSessionAccessibilityEvidence.Scan(
		packageName = packageName,
		host = host,
		rootVisible = rootVisible,
		urlGeneration = urlGeneration,
		videoId = videoId,
		adSignal = signal,
		atMillis = atMillis,
	)

	@Before fun setUp() = MediaSessionAccessibilityEvidence.clearAll()
	@After fun tearDown() = MediaSessionAccessibilityEvidence.clearAll()

	@Test
	fun `successful clean visible YouTube scan covers only its active track`() {
		val current = track(token = 10)
		val successor = track(token = 11, title = "Successor B")
		MediaSessionAccessibilityEvidence.activate(current, establishedAtMillis = 1_000)
		val coverage = MediaSessionAccessibilityEvidence.observe(
			scan(), current, unambiguous = true,
		)
		assertNotNull(coverage)
		assertNotNull(MediaSessionAccessibilityEvidence.current(current, now = 2_001))
		assertNull(MediaSessionAccessibilityEvidence.current(successor, now = 2_001))
	}

	@Test
	fun `connection old scan and invalid roots do not provide coverage`() {
		val current = track()
		MediaSessionAccessibilityEvidence.activate(current, establishedAtMillis = 2_000)
		assertNull(MediaSessionAccessibilityEvidence.current(current, now = 2_001))
		assertNull(MediaSessionAccessibilityEvidence.observe(scan(atMillis = 1_999), current, true))
		assertNull(MediaSessionAccessibilityEvidence.observe(scan(host = null), current, true))
		assertNull(
			MediaSessionAccessibilityEvidence.observe(
				scan(host = "example.com"), current, true,
			),
		)
		assertNull(
			MediaSessionAccessibilityEvidence.observe(
				scan(rootVisible = false), current, true,
			),
		)
		assertNull(
			MediaSessionAccessibilityEvidence.observe(
				scan(packageName = "com.example.browser"), current, true,
			),
		)
	}

	@Test
	fun `ambiguous sessions other package and changed generation receive no coverage`() {
		val current = track(token = 20)
		MediaSessionAccessibilityEvidence.activate(current, establishedAtMillis = 1_000)
		assertNull(MediaSessionAccessibilityEvidence.observe(scan(), current, false))
		assertNull(
			MediaSessionAccessibilityEvidence.observe(
				scan(packageName = brave), current, true,
			),
		)
		assertNotNull(MediaSessionAccessibilityEvidence.observe(scan(), current, true))
		assertNull(
			MediaSessionAccessibilityEvidence.current(
				current, expectedUrlGeneration = 8, now = 2_001,
			),
		)
	}

	@Test
	fun `coverage expires and clear boundaries remove it`() {
		val current = track()
		MediaSessionAccessibilityEvidence.activate(current, establishedAtMillis = 1_000)
		MediaSessionAccessibilityEvidence.observe(scan(), current, true)
		assertNull(
			MediaSessionAccessibilityEvidence.current(
				current,
				now = 2_000 + MediaSessionAccessibilityEvidence.COVERAGE_FRESH_MS + 1,
			),
		)

		MediaSessionAccessibilityEvidence.observe(scan(atMillis = 40_000), current, true)
		MediaSessionAccessibilityEvidence.deactivate(current)
		assertNull(MediaSessionAccessibilityEvidence.current(current, now = 40_001))

		MediaSessionAccessibilityEvidence.activate(current, establishedAtMillis = 50_000)
		MediaSessionAccessibilityEvidence.observe(scan(atMillis = 50_001), current, true)
		MediaSessionAccessibilityEvidence.clearPackage(chrome)
		assertNull(MediaSessionAccessibilityEvidence.current(current, now = 50_002))

		MediaSessionAccessibilityEvidence.activate(current, establishedAtMillis = 60_000)
		MediaSessionAccessibilityEvidence.observe(scan(atMillis = 60_001), current, true)
		MediaSessionAccessibilityEvidence.clearAll()
		assertNull(MediaSessionAccessibilityEvidence.current(current, now = 60_002))
	}

	@Test
	fun `genuine same track recreation can restore coverage but successor cannot`() {
		val first = track(token = 30)
		MediaSessionAccessibilityEvidence.activate(first, establishedAtMillis = 1_000)
		val coverage = MediaSessionAccessibilityEvidence.observe(scan(), first, true)!!
		MediaSessionAccessibilityEvidence.deactivate(first)

		val recreated = track(token = 30)
		MediaSessionAccessibilityEvidence.activate(recreated, establishedAtMillis = 2_100)
		assertTrue(MediaSessionAccessibilityEvidence.restore(recreated, coverage, now = 2_100))
		assertNotNull(MediaSessionAccessibilityEvidence.current(recreated, now = 2_101))

		val successor = track(token = 31, title = "Autoplay successor")
		MediaSessionAccessibilityEvidence.activate(successor, establishedAtMillis = 2_100)
		assertFalse(MediaSessionAccessibilityEvidence.restore(successor, coverage, now = 2_100))
		assertNull(MediaSessionAccessibilityEvidence.current(successor, now = 2_101))
	}

	@Test
	fun `outage transition is logged once while retries continue and recovery is explicit`() {
		val current = track()
		MediaSessionAccessibilityEvidence.activate(current, establishedAtMillis = 1_000)
		assertEquals(
			emptyList<MediaSessionAccessibilityEvidence.FreshnessTransition>(),
			MediaSessionAccessibilityEvidence.noteRefreshResult(null, now = 1_001),
		)
		val outageAt = 1_000 + MediaSessionAccessibilityEvidence.OUTAGE_AFTER_MS
		val outage = MediaSessionAccessibilityEvidence.noteRefreshResult(null, now = outageAt)
		assertEquals(1, outage.size)
		assertEquals(MediaSessionAccessibilityEvidence.Freshness.OUTAGE, outage.single().freshness)
		assertEquals(
			emptyList<MediaSessionAccessibilityEvidence.FreshnessTransition>(),
			MediaSessionAccessibilityEvidence.noteRefreshResult(null, now = outageAt + 1_000),
		)
		val recovered = MediaSessionAccessibilityEvidence.noteRefreshResult(chrome, now = outageAt + 2_000)
		assertEquals(1, recovered.size)
		assertEquals(MediaSessionAccessibilityEvidence.Freshness.RECOVERED, recovered.single().freshness)
	}
}
