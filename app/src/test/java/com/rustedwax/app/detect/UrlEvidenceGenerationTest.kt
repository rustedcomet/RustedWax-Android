package com.rustedwax.app.detect

import com.rustedwax.core.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class UrlEvidenceGenerationTest {

	private val pkg = "com.android.chrome"

	@Before fun setUp() = UrlEvidence.clearAll()
	@After fun tearDown() {
		UrlEvidence.clearAll()
	}

	private fun url(id: String?, raw: String) = UrlEvidence.Evidence(
		host = "m.youtube.com",
		videoId = id,
		isShort = id != null,
		raw = raw,
		atMillis = 1_000,
	)

	@Test
	fun `same video in a new playlist is a new observation`() {
		val first = UrlEvidence.put(
			pkg,
			url("IW524Zl2Pus", "m.youtube.com/watch?v=IW524Zl2Pus&list=PLfirst00000000")
				.copy(playlistId = "PLfirst00000000"),
		)
		val moved = UrlEvidence.put(
			pkg,
			url("IW524Zl2Pus", "m.youtube.com/watch?v=IW524Zl2Pus&list=PLsecond0000000")
				.copy(playlistId = "PLsecond0000000"),
		)

		assertEquals(1L, first.generation)
		assertEquals(2L, moved.generation)
		assertEquals("PLsecond0000000", moved.playlistId)
	}

	@Test
	fun `each concrete URL transition gets a new package generation`() {
		val first = UrlEvidence.put(pkg, url("stadiumAd01", "m.youtube.com/shorts/stadiumAd01"))
		val redraw = UrlEvidence.put(pkg, url("stadiumAd01", "m.youtube.com/shorts/stadiumAd01?x=1"))
		val successor = UrlEvidence.put(pkg, url("IW524Zl2Pus", "m.youtube.com/shorts/IW524Zl2Pus"))
		assertEquals(1L, first.generation)
		assertEquals(first.generation, redraw.generation)
		assertEquals(2L, successor.generation)
	}

	@Test
	fun `collapsed host redraw preserves the concrete id and generation`() {
		val concrete = UrlEvidence.put(pkg, url("IW524Zl2Pus", "m.youtube.com/shorts/IW524Zl2Pus"))
		val collapsed = UrlEvidence.put(pkg, url(null, "m.youtube.com"))
		assertEquals(concrete.videoId, collapsed.videoId)
		assertEquals(concrete.generation, collapsed.generation)
	}

	@Test
	fun `URL package clear cannot clear accepted ordinary watch ad evidence`() {
		val instance = MediaSessionAdEvidence.TrackInstance(
			packageName = pkg,
			token = 17,
			signature = TrackIdentity("Advert", "Sponsor", null, 30_000),
		)
		MediaSessionAdEvidence.observe(
			observation = MediaSessionAdEvidence.Observation(pkg, "Sponsored", atMillis = 2_000),
			instance = instance,
			instanceEstablishedBeforeObservation = true,
			unambiguous = true,
		)
		assertEquals("Sponsored", MediaSessionAdEvidence.accepted(instance, now = 2_001)?.signal)

		UrlEvidence.clear(pkg)

		assertEquals("Sponsored", MediaSessionAdEvidence.accepted(instance, now = 2_002)?.signal)
	}
}
