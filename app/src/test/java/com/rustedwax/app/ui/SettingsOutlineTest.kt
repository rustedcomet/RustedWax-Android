package com.rustedwax.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order of the settings screen, and the copy the rows are found by.
 *
 * Order is a product decision — "the switch someone came for is the first thing
 * on the screen" — so it is asserted here rather than left to whatever order
 * the composable happens to declare its cards in.
 */
class SettingsOutlineTest {

	private fun rows(
		usageAccessGranted: Boolean = true,
		queuedCount: Int = 0,
		developerMode: Boolean = false,
	) = SettingsOutline.rows(usageAccessGranted, queuedCount, developerMode)

	@Test
	fun `automatic scrobbling is the first row`() {
		assertEquals(SettingsRow.AUTOMATIC_SCROBBLING, rows().first())
	}

	/**
	 * The card said "Watching YouTube in Brave and Chrome, the YouTube app, and
	 * YouTube Music" — a paragraph restating the switch two rows below it. The
	 * monitoring *status* it also carried is not lost: it is in the strip that
	 * is visible from every destination, which is where a stop control belongs.
	 */
	@Test
	fun `the standalone monitoring card is gone`() {
		assertFalse(
			"the standalone watching card is still drawn",
			SettingsRow.entries.any { it.name == "MONITORING_STATUS" },
		)
	}

	/**
	 * The strip says what the app is doing *for you*, not that it is watching
	 * you. "Monitoring" over a line that already reads "Scrobbling at 60%
	 * played" added nothing except the impression of surveillance, so while the
	 * app is running there is no headline at all — only the one line that says
	 * what will happen. Stopped still has a headline, because that is a state
	 * someone needs to notice.
	 */
	@Test
	fun `a running app has no headline above the one line that matters`() {
		assertNull(MonitoringStatus.headline(monitoring = true))
		assertEquals("Stopped", MonitoringStatus.headline(monitoring = false))
	}

	@Test
	fun `the strip states the rule rather than the watching`() {
		assertEquals(
			"Nothing is being read",
			MonitoringStatus.detail(false, hasKey = true, autoScrobble = true, 60, 0),
		)
		assertEquals(
			"Scrobbling at 60% played · 2 waiting to send",
			MonitoringStatus.detail(true, hasKey = true, autoScrobble = true, 60, 2),
		)
		assertEquals(
			"Add a Hive key to scrobble",
			MonitoringStatus.detail(true, hasKey = false, autoScrobble = true, 60, 0),
		)
		assertEquals(
			"Automatic scrobbling is off",
			MonitoringStatus.detail(true, hasKey = true, autoScrobble = false, 60, 0),
		)
		listOf(true, false).forEach { monitoring ->
			assertFalse(
				"the strip still calls it monitoring",
				MonitoringStatus.detail(monitoring, true, true, 60, 0)
					.contains("onitoring"),
			)
		}
	}

	/** The two account rows are read together, so they are drawn together. */
	@Test
	fun `the Hive account row sits directly beside YouTube watch history`() {
		val order = rows()
		val history = order.indexOf(SettingsRow.WATCH_HISTORY)
		val hive = order.indexOf(SettingsRow.HIVE_ACCOUNT)
		assertTrue("YouTube watch history is missing", history >= 0)
		assertTrue("Hive account is missing", hive >= 0)
		assertEquals("the two account rows are not adjacent", 1, hive - history)
	}

	@Test
	fun `the ordinary order is fixed`() {
		assertEquals(
			listOf(
				SettingsRow.AUTOMATIC_SCROBBLING,
				SettingsRow.YOUTUBE_SCROBBLING,
				SettingsRow.BROWSER_EVIDENCE,
				SettingsRow.WATCH_HISTORY,
				SettingsRow.HIVE_ACCOUNT,
				SettingsRow.SNAPS_AND_LIKES,
				SettingsRow.APPEARANCE,
				SettingsRow.ABOUT,
			),
			rows(),
		)
	}

	/** About is the way in to developer mode, so it has to be findable. */
	@Test
	fun `About is always the last row`() {
		listOf(true, false).forEach { developer ->
			listOf(true, false).forEach { usage ->
				assertEquals(
					"developer=$developer usage=$usage",
					SettingsRow.ABOUT,
					rows(usageAccessGranted = usage, queuedCount = 3, developerMode = developer).last(),
				)
			}
		}
	}

	/**
	 * The Advanced tier is gone and developer mode replaces it. Everything it
	 * held that is still offered — the event log, `Disable Shorts` — now lives
	 * behind seven taps on About, together with the foreground-Shorts grant and
	 * the connection check.
	 */
	@Test
	fun `the Advanced row is gone`() {
		assertFalse(
			"an Advanced row survives",
			SettingsRow.entries.any { it.name == "ADVANCED" },
		)
	}

	@Test
	fun `the developer rows appear only once developer mode is on`() {
		assertFalse(SettingsRow.DEVELOPER_MODE in rows(developerMode = false))
		assertFalse(SettingsRow.FOREGROUND_SHORTS in rows(developerMode = false))

		val developer = rows(developerMode = true)
		assertTrue(SettingsRow.DEVELOPER_MODE in developer)
		assertTrue(SettingsRow.FOREGROUND_SHORTS in developer)
		assertEquals(
			"the foreground-Shorts grant is not inside the developer tier",
			1,
			developer.indexOf(SettingsRow.FOREGROUND_SHORTS) -
				developer.indexOf(SettingsRow.DEVELOPER_MODE),
		)
	}

	@Test
	fun `picture-in-picture appears only while the grant is missing`() {
		assertTrue(SettingsRow.PICTURE_IN_PICTURE in rows(usageAccessGranted = false))
		assertFalse(SettingsRow.PICTURE_IN_PICTURE in rows(usageAccessGranted = true))
	}

	@Test
	fun `the queue row appears only while something is waiting`() {
		assertTrue(SettingsRow.QUEUE in rows(queuedCount = 1))
		assertFalse(SettingsRow.QUEUE in rows(queuedCount = 0))
	}

	@Test
	fun `the rows are titled the way the screen names them`() {
		assertEquals("Automatic scrobbling", SettingsOutline.title(SettingsRow.AUTOMATIC_SCROBBLING))
		assertEquals("Hive account", SettingsOutline.title(SettingsRow.HIVE_ACCOUNT))
		assertEquals("YouTube watch history", SettingsOutline.title(SettingsRow.WATCH_HISTORY))
		assertEquals("Developer mode", SettingsOutline.title(SettingsRow.DEVELOPER_MODE))
		assertEquals("About", SettingsOutline.title(SettingsRow.ABOUT))
		SettingsRow.entries.forEach { row ->
			assertTrue("$row has no title", SettingsOutline.title(row).isNotBlank())
		}
	}

	/**
	 * The switch is on, and what it watches is stated without advertising a
	 * browser integration the project does not publicise.
	 */
	@Test
	fun `the YouTube scrobbling copy names the two apps and nothing else`() {
		val body = SettingsOutline.youTubeScrobblingBody(on = true)
		assertEquals(
			"On — YouTube app and YouTube Music. RustedWax may look up YouTube " +
				"metadata when needed to verify what played. Picture-in-picture " +
				"time is counted where Usage Access allows it.",
			body,
		)
		listOf("Brave", "Chrome", "browser").forEach {
			assertFalse("\"$it\" is named in the settings copy", body.contains(it, ignoreCase = true))
		}
	}

	// ── Snaps & Likes ──────────────────────────────────────────────────

	/**
	 * Directly under the Hive account, because it is a preference *about* that
	 * account's voting power and says nothing to anyone without a key saved.
	 */
	@Test
	fun `Snaps and Likes sits immediately below the Hive account`() {
		val order = rows()
		assertEquals(
			order.indexOf(SettingsRow.HIVE_ACCOUNT) + 1,
			order.indexOf(SettingsRow.SNAPS_AND_LIKES),
		)
	}

	@Test
	fun `Snaps and Likes is always offered`() {
		assertTrue(SettingsRow.SNAPS_AND_LIKES in rows())
		assertTrue(SettingsRow.SNAPS_AND_LIKES in rows(usageAccessGranted = false))
		assertTrue(SettingsRow.SNAPS_AND_LIKES in rows(developerMode = true))
		assertTrue(SettingsRow.SNAPS_AND_LIKES in rows(queuedCount = 3))
	}

	@Test
	fun `the Snaps and Likes row is found by its own title`() {
		assertEquals("Snaps & Likes", SettingsOutline.title(SettingsRow.SNAPS_AND_LIKES))
	}

	/** It is a setting, not a grant, so it never appears behind the hidden tier. */
	@Test
	fun `Snaps and Likes is not a developer row`() {
		assertTrue(SettingsRow.SNAPS_AND_LIKES in rows(developerMode = false))
	}

}
