package com.rustedwax.app.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering that made a single last-event field wrong, measured on the
 * device 2026-08-06.
 */
class VisibleActivitiesTest {

	private val main = "com.google.android.apps.youtube.app.watchwhile.MainActivity"
	private val shell = "com.google.android.apps.youtube.app.application.Shell_UrlActivity"

	/**
	 * The exact `dumpsys usagestats` sequence for opening a Short by URL. The
	 * departing activity's STOPPED lands after the arriving one's RESUMED, so
	 * remembering only the last event concluded YouTube was gone while its
	 * player was on screen and audible — which silenced both the picture-in-
	 * picture credit and the §5.1 detector.
	 */
	@Test
	fun `a trailing STOPPED from the departing activity does not hide the app`() {
		val activities = VisibleActivities()
		activities.onEvent(main, VisibleActivities.Lifecycle.PAUSED)
		activities.onEvent(shell, VisibleActivities.Lifecycle.RESUMED)
		activities.onEvent(shell, VisibleActivities.Lifecycle.PAUSED)
		activities.onEvent(main, VisibleActivities.Lifecycle.RESUMED)
		assertTrue(activities.onEvent(shell, VisibleActivities.Lifecycle.STOPPED))
		assertTrue("MainActivity is on screen", activities.visible)
	}

	/** Leaving the app for real still reads as gone. */
	@Test
	fun `stopping the last activity is not visible`() {
		val activities = VisibleActivities()
		activities.onEvent(main, VisibleActivities.Lifecycle.RESUMED)
		assertTrue(activities.visible)
		activities.onEvent(main, VisibleActivities.Lifecycle.PAUSED)
		assertTrue("a paused activity is still on screen — that is picture-in-picture", activities.visible)
		assertFalse(activities.onEvent(main, VisibleActivities.Lifecycle.STOPPED))
	}

	/** Nothing seen yet is not visible; a fresh probe must not assume. */
	@Test
	fun `an empty tracker is not visible`() {
		assertFalse(VisibleActivities().visible)
	}

	@Test
	fun `tracking is bounded and the oldest activity is dropped first`() {
		val activities = VisibleActivities(maxTracked = 2)
		activities.onEvent("A", VisibleActivities.Lifecycle.RESUMED)
		activities.onEvent("B", VisibleActivities.Lifecycle.RESUMED)
		activities.onEvent("C", VisibleActivities.Lifecycle.RESUMED)
		// A was evicted, so stopping it changes nothing; B and C still hold.
		assertTrue(activities.onEvent("A", VisibleActivities.Lifecycle.STOPPED))
		activities.onEvent("B", VisibleActivities.Lifecycle.STOPPED)
		assertFalse(activities.onEvent("C", VisibleActivities.Lifecycle.STOPPED))
	}

	@Test
	fun `events with no class name still track`() {
		val activities = VisibleActivities()
		assertTrue(activities.onEvent(null, VisibleActivities.Lifecycle.RESUMED))
		assertFalse(activities.onEvent(null, VisibleActivities.Lifecycle.STOPPED))
	}
}
