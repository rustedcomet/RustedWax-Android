package com.rustedwax.app.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The callback's lifetime, exercised rather than asserted in prose.
 *
 * A `NetworkCallback` registered against the application context lives until the
 * process exits or it is unregistered. `onListenerDisconnected` and
 * `Service.onDestroy` are separate platform callbacks and neither is promised to
 * imply the other, so the service releases the trigger from both — which is only
 * safe if releasing twice, releasing something never registered, and replacing a
 * live registration all behave. These cases drive exactly those sequences through
 * a stand-in watch, counting starts and stops.
 */
class ConnectivityRetryLifetimeTest {

	/** A watch that records how many times it was started and released. */
	private class CountingWatch(
		private val failToStart: Boolean = false,
		private val throwOnStop: Boolean = false,
	) : ConnectivityRetryTrigger.NetworkWatch {
		var starts = 0
			private set
		var stops = 0
			private set

		override fun start(onUsableNetwork: () -> Unit): (() -> Unit)? {
			starts++
			if (failToStart) return null
			return {
				stops++
				if (throwOnStop) throw IllegalStateException("callback was already dropped")
			}
		}
	}

	private fun trigger() = ConnectivityRetryTrigger(
		queueDepth = { 0 },
		flush = {},
		nowMs = { 0L },
	)

	@Test
	fun `a registered watch is released once by the disconnect boundary`() {
		val watch = CountingWatch()
		val trigger = trigger()

		trigger.registerWith(watch)
		assertTrue(trigger.isWatching)

		// onListenerDisconnected()
		trigger.unregister()

		assertEquals(1, watch.starts)
		assertEquals(1, watch.stops)
		assertFalse(trigger.isWatching)
	}

	/**
	 * The boundary the audit found missing.
	 *
	 * A service instance can be destroyed without the disconnect callback having
	 * run. Before `onDestroy` released the trigger, that path left the callback
	 * registered for the life of the process.
	 */
	@Test
	fun `a destroy with no preceding disconnect still releases the watch`() {
		val watch = CountingWatch()
		val trigger = trigger()

		trigger.registerWith(watch)
		// onDestroy() only
		trigger.unregister()

		assertEquals(1, watch.stops)
		assertFalse(trigger.isWatching)
	}

	@Test
	fun `disconnect followed by destroy releases exactly once`() {
		val watch = CountingWatch()
		val trigger = trigger()

		trigger.registerWith(watch)
		trigger.unregister() // onListenerDisconnected()
		trigger.unregister() // onDestroy()

		assertEquals("the second teardown released a watch it did not hold", 1, watch.stops)
		assertFalse(trigger.isWatching)
	}

	@Test
	fun `repeated cleanup is harmless`() {
		val watch = CountingWatch()
		val trigger = trigger()

		trigger.registerWith(watch)
		repeat(5) { trigger.unregister() }

		assertEquals(1, watch.stops)
		assertFalse(trigger.isWatching)
	}

	@Test
	fun `releasing a trigger that never registered does nothing`() {
		val trigger = trigger()

		trigger.unregister()
		trigger.unregister()

		assertFalse(trigger.isWatching)
	}

	/**
	 * A reconnect replaces rather than accumulates.
	 *
	 * `onListenerConnected` can run again without an intervening disconnect, and
	 * a callback per reconnect would pile up in the same process.
	 */
	@Test
	fun `re-registering releases the previous watch before starting a new one`() {
		val first = CountingWatch()
		val second = CountingWatch()
		val trigger = trigger()

		trigger.registerWith(first)
		trigger.registerWith(second)

		assertEquals("the replaced watch was left registered", 1, first.stops)
		assertEquals(1, second.starts)
		assertEquals(0, second.stops)
		assertTrue(trigger.isWatching)

		trigger.unregister()
		assertEquals(1, second.stops)
		assertEquals(1, first.stops)
	}

	@Test
	fun `connect disconnect connect destroy leaves nothing registered`() {
		val first = CountingWatch()
		val second = CountingWatch()
		val trigger = trigger()

		trigger.registerWith(first)   // onListenerConnected
		trigger.unregister()          // onListenerDisconnected
		trigger.registerWith(second)  // onListenerConnected again
		trigger.unregister()          // onListenerDisconnected
		trigger.unregister()          // onDestroy

		assertEquals(1, first.stops)
		assertEquals(1, second.stops)
		assertFalse(trigger.isWatching)
	}

	/**
	 * A platform that refuses the registration leaves nothing to release.
	 *
	 * `registerNetworkCallback` can throw — a revoked permission, a system that
	 * declines — and that must not turn the next teardown into a crash.
	 */
	@Test
	fun `a watch that could not start is not released later`() {
		val watch = CountingWatch(failToStart = true)
		val trigger = trigger()

		trigger.registerWith(watch)

		assertFalse(trigger.isWatching)
		trigger.unregister()
		assertEquals(0, watch.stops)
	}

	/**
	 * Unregistering a callback the framework has already dropped throws. The
	 * trigger must still end up released rather than holding a handle forever.
	 */
	@Test
	fun `a throwing release still leaves the trigger released`() {
		val watch = CountingWatch(throwOnStop = true)
		val trigger = trigger()

		trigger.registerWith(watch)
		trigger.unregister()

		assertFalse(trigger.isWatching)
		assertEquals(1, watch.stops)
		trigger.unregister()
		assertEquals(1, watch.stops)
	}

	/** The signal still reaches the drain through the seam the service uses. */
	@Test
	fun `a usable network delivered through the watch reaches the drain`() {
		var drains = 0
		val trigger = ConnectivityRetryTrigger(
			queueDepth = { 1 },
			flush = { drains++ },
			nowMs = { 0L },
		)
		var deliver: (() -> Unit)? = null
		trigger.registerWith(ConnectivityRetryTrigger.NetworkWatch { onUsable ->
			deliver = onUsable
			{}
		})

		deliver!!.invoke()

		assertEquals(1, drains)
	}
}
