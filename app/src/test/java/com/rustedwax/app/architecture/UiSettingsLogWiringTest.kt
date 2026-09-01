package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production wiring for the navigation, settings, theme and log-retention work.
 *
 * These are **supplements** to the behaviour suites, not substitutes for them.
 * `AppNavigationTest`, `SettingsOutlineTest`, `YouTubeConnectionWarningTest`,
 * `LogRetentionTest` and `EventLogRetentionTest` prove what the models decide;
 * this file proves the screens are actually the models' only caller, which is
 * the failure a pure model suite cannot see — a correct model that nothing on
 * the device consults.
 */
class UiSettingsLogWiringTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private val productionFiles: List<File> by lazy {
		File(root, "app/src/main/java").walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.toList()
	}

	private fun text(path: String): String = File(root, path).let { file ->
		assertTrue("production source missing: $path", file.isFile)
		file.readText()
	}

	private val mainScreen get() = text("app/src/main/java/com/rustedwax/app/ui/MainScreen.kt")

	/**
	 * The screen with its prose removed.
	 *
	 * The assertions below are about what the app *draws*. A doc comment
	 * explaining which diagnostic fields were removed and why necessarily names
	 * them, and a test that could not tell the two apart would force the
	 * explanation to be deleted along with the code — which is the opposite of
	 * what this repository does with the reason for a change.
	 */
	private val mainScreenCode: String get() = stripComments(mainScreen)

	private fun stripComments(source: String): String = source
		.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
		.lines()
		.joinToString("\n") { it.substringBefore("//") }
	private val mainActivity get() = text("app/src/main/java/com/rustedwax/app/MainActivity.kt")
	private val signIn get() =
		text("app/src/main/java/com/rustedwax/app/ui/YouTubeSignInActivity.kt")
	private val hiveAccount get() =
		text("app/src/main/java/com/rustedwax/app/ui/HiveAccountSection.kt")
	private val developerSettings get() =
		text("app/src/main/java/com/rustedwax/app/ui/DeveloperSettings.kt")

	private fun references(symbol: String): List<String> = buildList {
		productionFiles.forEach { file ->
			stripComments(file.readText()).lines().forEachIndexed { index, line ->
				if (Regex("\\b${Regex.escape(symbol)}\\b").containsMatchIn(line)) {
					add("${file.relativeTo(root)}:${index + 1}: ${line.trim()}")
				}
			}
		}
	}

	@Test
	fun `the main screen navigates by destination rather than by tab number`() {
		assertTrue(
			"the screen does not ask the navigation model which destinations exist",
			mainScreen.contains("AppNavigation.destinations("),
		)
		assertFalse(
			"a numeric tab index survives",
			Regex("var tab by remember \\{ mutableIntStateOf").containsMatchIn(mainScreen),
		)
		assertFalse("a numeric tab dispatch survives", mainScreen.contains("when (tab)"))
	}

	@Test
	fun `pages are swiped without retaining adjacent page trees`() {
		assertTrue(
			"horizontal swipes are not wired in",
			mainScreen.contains("detectHorizontalDragGestures("),
		)
		assertTrue(
			"swipes bypass the named navigation model",
			mainScreen.contains("AppNavigation.swipe("),
		)
		assertFalse(
			"a pager keeps adjacent accessibility semantics trees alive",
			mainScreen.contains("HorizontalPager("),
		)
		assertFalse(
			"numeric pager state survives beside named destinations",
			mainScreen.contains("rememberPagerState("),
		)
	}

	@Test
	fun `a tab tap changes the named destination directly`() {
		assertTrue(
			"the tab callback does not own the named selection",
			Regex("onSelect\\s*=\\s*\\{\\s*destination\\s*->\\s*chosen\\s*=\\s*destination")
				.containsMatchIn(mainScreen),
		)
		assertFalse("pager navigation survives", mainScreen.contains("scrollToPage"))
		assertFalse("pager navigation still animates", mainScreen.contains("animateScrollToPage"))
	}

	@Test
	fun `the Account destination and its screen are gone`() {
		assertFalse(
			"the Account tab file still exists",
			File(root, "app/src/main/java/com/rustedwax/app/ui/AccountTab.kt").exists(),
		)
		assertEquals(emptyList<String>(), references("AccountTab"))
	}

	@Test
	fun `the Hive account is drawn inside the settings list`() {
		assertTrue(
			"the settings list does not draw the Hive account section",
			mainScreen.contains("HiveAccountSection("),
		)
	}

	@Test
	fun `the settings list is ordered by the outline model`() {
		assertTrue(
			"the settings list does not render from SettingsOutline",
			mainScreen.contains("SettingsOutline.rows("),
		)
		assertTrue(
			"the always-visible strip does not use the monitoring status model",
			mainScreen.contains("MonitoringStatus."),
		)
	}

	@Test
	fun `the missing-connection warning is evaluated by the model`() {
		assertTrue(
			"nothing evaluates the connection warning",
			mainScreen.contains("YouTubeConnectionWarning.evaluate("),
		)
	}

	@Test
	fun `every app-owned window is themed through the shared wrapper`() {
		listOf("MainActivity.kt" to mainActivity, "YouTubeSignInActivity.kt" to signIn)
			.forEach { (name, source) ->
				assertTrue("$name does not use the shared window", source.contains("RustedWaxWindow("))
				assertFalse(
					"$name still composes a bare MaterialTheme",
					Regex("MaterialTheme\\s*\\(").containsMatchIn(source),
				)
			}
	}

	@Test
	fun `the short-clip policy is gone from production`() {
		val survivors = references("shortClips") +
			references("shortClipsEnabled") +
			references("shortClipScrobbling").filterNot {
				// The one permitted mention: the key the migration deletes.
				it.contains("SettingsMigration") || it.contains("KEY_LEGACY_SHORT_CLIPS")
			}
		assertEquals(emptyList<String>(), survivors)
	}

	@Test
	fun `the event log prunes through the retention model`() {
		val log = text("app/src/main/java/com/rustedwax/app/detect/EventLog.kt")
		assertTrue("the log has no retention policy", log.contains("LogRetention("))
		assertFalse(
			"pruning still runs on every append",
			Regex("fun append\\([^)]*\\)[^{]*\\{[^}]*retention\\.prune").containsMatchIn(log),
		)
	}

	@Test
	fun `production event log disk work is serialized off the calling thread`() {
		val log = text("app/src/main/java/com/rustedwax/app/detect/EventLog.kt")
		assertTrue(
			"production logging has no dedicated serial disk worker",
			log.contains("newSingleThreadExecutor"),
		)
		assertTrue(
			"startup retention still runs inline in init",
			Regex("fun init\\(.*?enqueueDisk", RegexOption.DOT_MATCHES_ALL)
				.containsMatchIn(log),
		)
		assertTrue(
			"append still writes or prunes on whichever Android callback called it",
			Regex("fun append\\(.*?enqueueDisk", RegexOption.DOT_MATCHES_ALL)
				.containsMatchIn(log),
		)
		val appendBeforeWorker = stripComments(log)
			.substringAfter("fun append(")
			.substringBefore("enqueueDisk")
		assertFalse(
			"append still formats timestamps on the Android callback thread",
			appendBeforeWorker.contains("stamp.format"),
		)
		assertFalse(
			"append still writes logcat on the Android callback thread",
			appendBeforeWorker.contains("Log.d"),
		)
	}

	@Test
	fun `accessibility services do not request the RustedWax compose tree`() {
		val browser = text("app/src/main/java/com/rustedwax/app/detect/UrlWatcherService.kt")
		val native = text(
			"app/src/main/java/com/rustedwax/app/detect/NativeShortsAccessibilityService.kt",
		)
		assertTrue(
			"MainActivity does not publish its resumed boundary",
			mainActivity.contains("RustedWaxUiVisibility.resumed()"),
		)
		assertTrue(
			"MainActivity does not publish its paused boundary",
			mainActivity.contains("RustedWaxUiVisibility.paused()"),
		)
		assertTrue(
			"the browser observer can request RustedWax's Compose root while it is foreground",
			browser.contains("RustedWaxUiVisibility.isResumed"),
		)
		assertTrue(
			"the native observer can request RustedWax's Compose root while it is foreground",
			native.contains("RustedWaxUiVisibility.isResumed"),
		)
	}

	// ── The Now card is a card, not the diagnostics screen ────────────────────

	/**
	 * The Now card is rendered from [com.rustedwax.app.ui.NowCard], which is a
	 * closed set of seven readable values. The point of routing it through a
	 * model is that the composable has nothing else in scope to draw.
	 */
	@Test
	fun `the Now card renders from the NowCard model`() {
		assertTrue(
			"the Now card does not build the presentation model",
			mainScreen.contains("NowCard.from("),
		)
	}

	/**
	 * Every field the simplification removed, pinned by the exact label it was
	 * drawn under. A regression here is not a style change: it is diagnostic
	 * evidence about the app reappearing on the screen a person opens to see
	 * what is playing.
	 */
	@Test
	fun `no diagnostic field survives on the Now card`() {
		listOf(
			"source proof",
			"observer coverage",
			"native ad guard",
			"browser scan",
			"raw metadata",
			"metadataLines",
			"video id",
			"preview video id",
			"id route",
			"proven by",
			"kind because",
			"musicbrainz",
			"yt music",
			"Text(\"payload\"",
			"Broadcast this scrobble",
		).forEach {
			assertFalse("\"$it\" is still drawn on the Now card", mainScreenCode.contains(it))
		}
		// Scoped to the screen: `WatchHistoryMatcher.Verdict` is an unrelated
		// identity type and is not what this is about.
		assertFalse(
			"the one-word Now verdict survives",
			Regex("\\bfun Verdict\\(").containsMatchIn(mainScreenCode),
		)
		assertFalse(
			"the label/value diagnostic row survives",
			Regex("\\bField\\(").containsMatchIn(mainScreenCode),
		)
	}

	/**
	 * The manual per-session broadcast is gone from the UI. The engine's
	 * `MANUAL` trigger is deliberately **not** removed — it is a finalization
	 * boundary with its own rules, and deleting a trigger to remove a button
	 * would be a change to scrobbling rather than to the screen.
	 */
	@Test
	fun `the manual session broadcast button is gone from production`() {
		assertEquals(emptyList<String>(), references("onBroadcastSession"))
		assertFalse(
			"the activity still finalizes a session from the UI",
			stripComments(mainActivity).contains("FinalizationTrigger.MANUAL"),
		)
	}

	@Test
	fun `the Now empty state names only the two apps it tells people to open`() {
		assertTrue(
			mainScreen.contains(
				"No active media session. Play something in the YouTube app or YouTube Music.",
			),
		)
	}

	// ── Settings ─────────────────────────────────────────────────────────────

	/** Removed with its code, not merely hidden behind a flag. */
	@Test
	fun `the test broadcast and its payload are gone`() {
		assertEquals(emptyList<String>(), references("onBroadcastTest"))
		assertEquals(emptyList<String>(), references("testPayload"))
		assertFalse(stripComments(hiveAccount).contains("Broadcast a test scrobble"))
	}

	@Test
	fun `the Hive account keeps the posting-key note and drops the affiliation note`() {
		assertTrue(
			"the posting-key security note was dropped",
			hiveAccount.contains("About your posting key"),
		)
		assertFalse(hiveAccount.contains("Unofficial and unsupported"))
		assertEquals(emptyList<String>(), references("AffiliationNote"))
	}

	/**
	 * Private scrobbles are off the menu and **not** deleted: the cipher, the
	 * per-kind settings and the broadcast path that consults them are untouched,
	 * so turning the rows back on is a UI change rather than a re-implementation.
	 */
	@Test
	fun `private scrobbles leave the menu with their code intact`() {
		val ui = productionFiles.filter { it.path.contains("/ui/") }
		ui.forEach { file ->
			assertFalse(
				"${file.name} still offers the privacy switches",
				stripComments(file.readText()).contains("Private scrobbles"),
			)
		}
		val settings = text("app/src/main/java/com/rustedwax/app/storage/Settings.kt")
		listOf("privacyMusic", "privacyVideos", "privacyMoviesTv", "privacyPodcasts")
			.forEach { assertTrue("$it was deleted rather than unlisted", settings.contains(it)) }
	}

	/**
	 * Developer mode replaces the Advanced tier outright, and everything that
	 * tier still offers is inside it.
	 */
	@Test
	fun `developer mode replaces Advanced and holds the tools`() {
		assertFalse(
			"the Advanced section file survives",
			File(root, "app/src/main/java/com/rustedwax/app/ui/AdvancedSettings.kt").exists(),
		)
		assertEquals(emptyList<String>(), references("AdvancedSettingsSection"))
		assertTrue(
			"the settings list does not draw the developer section",
			mainScreen.contains("DeveloperModeSection("),
		)
		listOf("Event log", "Disable Shorts", "Test Hive connection").forEach {
			assertTrue("developer mode does not offer \"$it\"", developerSettings.contains(it))
		}
	}

	@Test
	fun `the connection check is run through the hive model rather than by broadcasting`() {
		assertTrue(
			"nothing runs the connection check",
			developerSettings.contains("HiveConnectionCheck"),
		)
		assertFalse(
			"the check broadcasts something instead of asking",
			developerSettings.contains("broadcastScrobble"),
		)
	}

	/** Seven taps on the version, and the answer is remembered. */
	@Test
	fun `About shows the version and is the way in to developer mode`() {
		assertTrue(
			"the activity does not hand the screen its build version",
			mainActivity.contains("BuildConfig.VERSION_NAME"),
		)
		assertTrue("the settings list does not draw About", mainScreenCode.contains("AboutRow("))
		assertTrue(
			"the unlock is not seven taps",
			developerSettings.contains("DEVELOPER_TAPS") &&
				developerSettings.contains("const val DEVELOPER_TAPS = 7"),
		)
		assertTrue(
			"developer mode is not persisted",
			text("app/src/main/java/com/rustedwax/app/storage/Settings.kt")
				.contains("developerMode"),
		)
	}

	@Test
	fun `the settings list asks the outline whether developer mode is on`() {
		assertTrue(
			"the developer tier is not gated by the outline",
			Regex("SettingsOutline\\.rows\\([^)]*developerMode").containsMatchIn(mainScreen),
		)
	}

	// ── Log ──────────────────────────────────────────────────────────────────

	@Test
	fun `Export sits beside Clear log and the line count is gone`() {
		assertTrue(
			"the log page does not offer Export",
			// Spans the whole parameter list rather than stopping at the first
			// ")": since the log is passed deferred as `() -> List<String>` a
			// parameter type contains one. The assertion is unchanged — LogList
			// must still declare an onExport.
			Regex("fun LogList\\(.*?onExport", RegexOption.DOT_MATCHES_ALL)
				.containsMatchIn(mainScreen),
		)
		assertFalse("the line count survives", mainScreenCode.contains("lines.size} lines"))
		assertFalse(
			"the strip still carries a trailing Export button",
			mainScreenCode.contains("trailing ="),
		)
	}
}
