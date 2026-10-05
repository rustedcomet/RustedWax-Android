package com.rustedwax.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Navigation is by named destination, not by tab number, and since Issue #47
 * the strip holds exactly four primary destinations while Settings and the
 * Event Log live in the top bar's overflow menu.
 */
class AppNavigationTest {

	@Test
	fun `the account destination no longer exists`() {
		assertFalse(
			"Account is still a destination",
			Destination.entries.any { it.name == "ACCOUNT" },
		)
	}

	@Test
	fun `the primary destinations are exactly Now, History, My Snaps and Not logged`() {
		assertEquals(
			listOf(
				Destination.NOW,
				Destination.HISTORY,
				Destination.MY_SNAPS,
				Destination.NOT_LOGGED,
			),
			AppNavigation.destinations(),
		)
	}

	@Test
	fun `Settings, the Event Log and About are overflow entries, in that order, and only there`() {
		assertEquals(listOf(Destination.SETTINGS, Destination.LOG, Destination.ABOUT), AppNavigation.overflow)
		AppNavigation.overflow.forEach {
			assertFalse("$it is in the strip", it in AppNavigation.destinations())
			assertFalse(AppNavigation.isPrimary(it))
		}
		assertFalse("My Snaps is repeated in the overflow", Destination.MY_SNAPS in AppNavigation.overflow)
	}

	@Test
	fun `every destination is either primary or in the overflow, never both`() {
		Destination.entries.forEach {
			assertTrue(
				"$it is reachable from exactly one place",
				(it in AppNavigation.destinations()) != (it in AppNavigation.overflow),
			)
		}
	}

	/** The Event Log no longer appears and disappears with the logging switch. */
	@Test
	fun `the destination lists take no logging switch`() {
		assertTrue(
			AppNavigation::class.java.methods
				.filter { it.name == "destinations" }
				.all { it.parameterCount == 0 },
		)
	}

	@Test
	fun `the export visibility rule is gone, not merely unused`() {
		assertFalse(
			"exportVisible survives on the navigation model",
			AppNavigation::class.java.methods.any { it.name == "exportVisible" },
		)
	}

	@Test
	fun `swiping moves one primary destination in each direction`() {
		val strip = AppNavigation.destinations()
		assertEquals(Destination.HISTORY, AppNavigation.swipe(Destination.NOW, strip, 1))
		assertEquals(Destination.MY_SNAPS, AppNavigation.swipe(Destination.HISTORY, strip, 1))
		assertEquals(Destination.NOT_LOGGED, AppNavigation.swipe(Destination.MY_SNAPS, strip, 1))
		assertEquals(Destination.MY_SNAPS, AppNavigation.swipe(Destination.NOT_LOGGED, strip, -1))
		assertEquals(Destination.NOW, AppNavigation.swipe(Destination.HISTORY, strip, -1))
	}

	@Test
	fun `swiping stops at both ends rather than wrapping or reaching the overflow`() {
		val strip = AppNavigation.destinations()
		assertEquals(Destination.NOW, AppNavigation.swipe(Destination.NOW, strip, -1))
		assertEquals(Destination.NOT_LOGGED, AppNavigation.swipe(Destination.NOT_LOGGED, strip, 1))
	}

	@Test
	fun `a swipe on an overflow page stays there`() {
		val strip = AppNavigation.destinations()
		AppNavigation.overflow.forEach { page ->
			assertEquals(page, AppNavigation.swipe(page, strip, 1))
			assertEquals(page, AppNavigation.swipe(page, strip, -1))
		}
	}

	@Test
	fun `every primary destination is reachable by swiping from the first one`() {
		val strip = AppNavigation.destinations()
		var at = strip.first()
		val visited = mutableListOf(at)
		repeat(strip.size - 1) {
			at = AppNavigation.swipe(at, strip, 1)
			visited += at
		}
		assertEquals(strip, visited)
	}

	@Test
	fun `labels name every destination`() {
		assertEquals("My Snaps", AppNavigation.label(Destination.MY_SNAPS, 1, 2, 3))
		assertEquals("Settings", AppNavigation.label(Destination.SETTINGS, 1, 2, 3))
		assertEquals("Event Log", AppNavigation.label(Destination.LOG, 1, 2, 3))
		assertEquals("About", AppNavigation.label(Destination.ABOUT, 1, 2, 3))
		assertEquals("Now (1)", AppNavigation.label(Destination.NOW, 1, 2, 3))
		assertEquals("History (2)", AppNavigation.label(Destination.HISTORY, 1, 2, 3))
		assertEquals("Not logged (3)", AppNavigation.label(Destination.NOT_LOGGED, 1, 2, 3))
	}

	@Test
	fun `no swipe ever reaches a utility page from the four tabs`() {
		val strip = AppNavigation.destinations()
		strip.forEach { from ->
			listOf(-1, 1).forEach { delta ->
				assertTrue("$from $delta", AppNavigation.swipe(from, strip, delta) in strip)
			}
		}
	}
}
