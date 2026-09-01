package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaylistLatchTest {

	private fun context(name: String = "Reggaeton 2016,17,18", position: Int = 61) =
		NativePlaylistParser.Result.Context(name, "Jhonny Gutierrez", position, 120)

	private val noPlaylist = NativePlaylistParser.Result.NoPlaylist("no bar on screen")
	private val unobservable = NativePlaylistParser.Result.Unobservable("no active root")

	@Test
	fun `first context latches`() {
		val latch = NativePlaylistLatch()
		val decision = latch.observe(context(), 1_000)
		assertTrue(decision is NativePlaylistLatch.Decision.Latched)
		assertEquals("Reggaeton 2016,17,18", latch.current?.playlistName)
	}

	@Test
	fun `a re-observation retains rather than re-latching`() {
		val latch = NativePlaylistLatch()
		latch.observe(context(), 1_000)
		val decision = latch.observe(context(position = 62), 2_000)
		assertTrue(decision is NativePlaylistLatch.Decision.Retained)
		assertEquals(62, latch.current?.position)
	}

	/**
	 * Ads, the miniplayer, fullscreen and simply scrolling down all remove the
	 * bar while the user is still in the playlist. None is distinguishable from
	 * leaving, so absence is never allowed to drop the latch — measured the hard
	 * way when a 30s grace dropped a good latch after 103s in the miniplayer and
	 * the next track mis-resolved through search.
	 */
	@Test
	fun `absence never drops the playlist however long it lasts`() {
		val latch = NativePlaylistLatch()
		latch.observe(context(), 1_000)
		listOf(40_000L, 600_000L, 86_400_000L).forEach { now ->
			assertTrue(
				"NoPlaylist dropped at $now",
				latch.observe(noPlaylist, now) is NativePlaylistLatch.Decision.Retained,
			)
			assertTrue(
				"Unobservable dropped at $now",
				latch.observe(unobservable, now) is NativePlaylistLatch.Decision.Retained,
			)
		}
		assertEquals("Reggaeton 2016,17,18", latch.current?.playlistName)
	}

	@Test
	fun `a different playlist replaces immediately`() {
		val latch = NativePlaylistLatch()
		latch.observe(context(), 1_000)
		val decision = latch.observe(context(name = "Bachata Mix", position = 1), 1_500)
		assertTrue(decision is NativePlaylistLatch.Decision.Latched)
		assertEquals("Bachata Mix", latch.current?.playlistName)
	}

	@Test
	fun `the same name in a different case is not a change`() {
		val latch = NativePlaylistLatch()
		latch.observe(context(name = "Reggaeton 2016,17,18"), 1_000)
		val decision = latch.observe(context(name = "REGGAETON 2016,17,18"), 2_000)
		assertTrue(decision is NativePlaylistLatch.Decision.Retained)
	}

	@Test
	fun `nothing latched reports idle rather than dropped`() {
		val latch = NativePlaylistLatch()
		assertTrue(latch.observe(noPlaylist, 1_000) is NativePlaylistLatch.Decision.Idle)
		assertTrue(latch.observe(unobservable, 2_000) is NativePlaylistLatch.Decision.Idle)
		assertNull(latch.current)
	}

	@Test
	fun `reset clears the latch`() {
		val latch = NativePlaylistLatch()
		latch.observe(context(), 1_000)
		latch.reset()
		assertNull(latch.current)
	}
}
