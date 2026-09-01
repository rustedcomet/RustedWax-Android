package com.rustedwax.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one thumbnail decision with a lasting consequence.
 *
 * [Fetched.Absent] is remembered for the life of the process, so classifying a
 * dropped connection as Absent would blank that row until the app is killed.
 * v0.9.18 shipped exactly that bug: every failure path wrote the id off,
 * including a timeout.
 */
class ThumbnailFetchTest {

	@Test
	fun `a whole body is a body`() {
		assertTrue(ThumbnailFetch.classify(200, 18_000) is Fetched.Body)
	}

	@Test
	fun `a 404 is a permanent absence`() {
		assertTrue(ThumbnailFetch.classify(404, 0) is Fetched.Absent)
		assertTrue(ThumbnailFetch.classify(410, 0) is Fetched.Absent)
		assertTrue(ThumbnailFetch.classify(403, 0) is Fetched.Absent)
	}

	@Test
	fun `the grey placeholder YouTube serves instead of a 404 is an absence`() {
		assertTrue(ThumbnailFetch.classify(200, 800) is Fetched.Absent)
	}

	@Test
	fun `a server error is transient and must not be remembered`() {
		assertTrue(ThumbnailFetch.classify(500, 0) is Fetched.Unavailable)
		assertTrue(ThumbnailFetch.classify(503, 0) is Fetched.Unavailable)
		// A captive portal's interception, which is the flight-mode case.
		assertTrue(ThumbnailFetch.classify(511, 0) is Fetched.Unavailable)
		assertTrue(ThumbnailFetch.classify(302, 0) is Fetched.Unavailable)
	}

	@Test
	fun `a body that overran the cap was not read whole`() {
		val overran = ThumbnailFetch.MAX_BYTES + 8 * 1024
		assertTrue(ThumbnailFetch.classify(200, overran) is Fetched.Unavailable)
	}

	@Test
	fun `a body exactly at the cap is still a body`() {
		assertTrue(ThumbnailFetch.classify(200, ThumbnailFetch.MAX_BYTES) is Fetched.Body)
	}
}
