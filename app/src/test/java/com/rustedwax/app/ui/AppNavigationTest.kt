package com.rustedwax.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Navigation is by named destination, not by tab number.
 *
 * The numeric scheme is what made `Log` unsafe to hide: every destination after
 * it shifted by one, so the same stored `4` meant `Log` on one install and
 * `Settings` on another. These tests are about identity surviving a change in
 * which destinations exist at all.
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
	fun `the event log switch decides whether Log is a destination`() {
		assertEquals(
			listOf(
				Destination.NOW,
				Destination.HISTORY,
				Destination.NOT_LOGGED,
				Destination.SETTINGS,
			),
			AppNavigation.destinations(eventLogEnabled = false),
		)
		assertEquals(
			listOf(
				Destination.NOW,
				Destination.HISTORY,
				Destination.NOT_LOGGED,
				Destination.SETTINGS,
				Destination.LOG,
			),
			AppNavigation.destinations(eventLogEnabled = true),
		)
	}

	/**
	 * `Log` is last, and that is the point of where it sits.
	 *
	 * It is the only conditional destination, so putting it anywhere else means
	 * the strip's contents shift when the switch is touched. At the end it can
	 * appear and disappear without moving a single neighbour — the four ordinary
	 * destinations occupy the same positions either way.
	 */
	@Test
	fun `Log is last, so turning it on moves no other destination`() {
		val on = AppNavigation.destinations(eventLogEnabled = true)
		assertEquals(Destination.LOG, on.last())
		assertEquals(
			"the ordinary destinations moved when Log appeared",
			AppNavigation.destinations(eventLogEnabled = false),
			on.dropLast(1),
		)
	}

	/**
	 * `Export` used to be a conditional trailing button on the tab strip, which
	 * needed a rule saying when it was allowed to exist. It is now inside the
	 * `Log` page beside `Clear log`, where the same guarantee holds structurally:
	 * the page exists only while a log does, so a button on it cannot attach an
	 * empty file. The rule that stood in for that is gone rather than kept as a
	 * second answer to a question the destination list already settles.
	 */
	@Test
	fun `the export visibility rule is gone, not merely unused`() {
		assertFalse(
			"exportVisible survives on the navigation model",
			AppNavigation::class.java.methods.any { it.name == "exportVisible" },
		)
	}

	/**
	 * The drift the numeric scheme had: adding `Log` in the middle moved every
	 * later tab down one, so a selection made before the toggle pointed at a
	 * different screen after it.
	 */
	@Test
	fun `adding or removing Log never moves another destination`() {
		val off = AppNavigation.destinations(eventLogEnabled = false)
		val on = AppNavigation.destinations(eventLogEnabled = true)
		off.forEach { destination ->
			assertEquals(
				"$destination was not preserved across the toggle",
				destination,
				AppNavigation.resolve(destination, on),
			)
			assertEquals(
				"$destination was not preserved across the toggle",
				destination,
				AppNavigation.resolve(destination, off),
			)
		}
	}

	@Test
	fun `a selection that stopped existing lands on Settings rather than a neighbour`() {
		assertEquals(
			Destination.SETTINGS,
			AppNavigation.resolve(
				Destination.LOG,
				AppNavigation.destinations(eventLogEnabled = false),
			),
		)
	}

	@Test
	fun `swiping moves one destination in each direction`() {
		val on = AppNavigation.destinations(eventLogEnabled = true)
		assertEquals(Destination.HISTORY, AppNavigation.swipe(Destination.NOW, on, 1))
		assertEquals(Destination.NOW, AppNavigation.swipe(Destination.HISTORY, on, -1))
		assertEquals(Destination.LOG, AppNavigation.swipe(Destination.SETTINGS, on, 1))
		assertEquals(Destination.SETTINGS, AppNavigation.swipe(Destination.LOG, on, -1))
	}

	@Test
	fun `swiping stops at both ends rather than wrapping`() {
		val on = AppNavigation.destinations(eventLogEnabled = true)
		val off = AppNavigation.destinations(eventLogEnabled = false)
		assertEquals(Destination.NOW, AppNavigation.swipe(Destination.NOW, on, -1))
		assertEquals(Destination.LOG, AppNavigation.swipe(Destination.LOG, on, 1))
		assertEquals(Destination.SETTINGS, AppNavigation.swipe(Destination.SETTINGS, off, 1))
	}

	@Test
	fun `with the log off Settings is simply the end of the strip`() {
		val off = AppNavigation.destinations(eventLogEnabled = false)
		assertEquals(Destination.SETTINGS, AppNavigation.swipe(Destination.NOT_LOGGED, off, 1))
		assertEquals(Destination.NOT_LOGGED, AppNavigation.swipe(Destination.SETTINGS, off, -1))
		assertEquals(Destination.SETTINGS, off.last())
	}

	/**
	 * A swipe and a tab tap are the same movement expressed twice, so they have
	 * to agree by construction rather than by two lists being kept in step.
	 */
	@Test
	fun `every destination is reachable by swiping from the first one`() {
		listOf(false, true).forEach { logging ->
			val destinations = AppNavigation.destinations(logging)
			var at = destinations.first()
			val visited = mutableListOf(at)
			repeat(destinations.size - 1) {
				at = AppNavigation.swipe(at, destinations, 1)
				visited += at
			}
			assertEquals("log=$logging", destinations, visited)
		}
	}

	@Test
	fun `a page index means the same destination either side of the toggle`() {
		val off = AppNavigation.destinations(eventLogEnabled = false)
		val on = AppNavigation.destinations(eventLogEnabled = true)

		off.forEachIndexed { index, destination ->
			assertEquals(
				"index $index is not the destination it was",
				destination,
				on[index],
			)
			assertEquals(index, AppNavigation.indexOf(destination, off))
			assertEquals(index, AppNavigation.indexOf(destination, on))
		}
		assertEquals(3, AppNavigation.indexOf(Destination.SETTINGS, off))
		assertEquals(3, AppNavigation.indexOf(Destination.SETTINGS, on))
		assertEquals(4, AppNavigation.indexOf(Destination.LOG, on))
	}

	@Test
	fun `every destination carries a label`() {
		Destination.entries.forEach { assertNotNull(it.name) }
	}
}
