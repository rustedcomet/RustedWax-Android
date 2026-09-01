package com.rustedwax.app.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Id validation for the mute store. `mute`/`isMuted` need a `Context`, but the
 * guard on what may become a persistent preference key is pure — and it is
 * guarding untrusted input, since ids arrive from a scraped address bar.
 */
class MutedVideosTest {

	@Test
	fun `accepts real video ids`() {
		// From the field logs, including the promoted video this store exists for.
		assertTrue(MutedVideos.isValidVideoId("jpYDdNj9tTw"))
		assertTrue(MutedVideos.isValidVideoId("cq2xXbWGHu8"))
		assertTrue(MutedVideos.isValidVideoId("_PMOc_NQmEc"))
		assertTrue(MutedVideos.isValidVideoId("7DOU5fxCOsw"))
	}

	@Test
	fun `rejects anything that is not an id`() {
		assertFalse(MutedVideos.isValidVideoId(""))
		assertFalse(MutedVideos.isValidVideoId("short"))
		assertFalse(MutedVideos.isValidVideoId("waytoolongforanid"))
		assertFalse(MutedVideos.isValidVideoId("has space11"))
	}

	/** A preference key built from untrusted input must not carry separators. */
	@Test
	fun `rejects path and key separators`() {
		assertFalse(MutedVideos.isValidVideoId("../../etc/pw"))
		assertFalse(MutedVideos.isValidVideoId("abc/def/ghi"))
		assertFalse(MutedVideos.isValidVideoId("abcdefghij."))
	}
}
