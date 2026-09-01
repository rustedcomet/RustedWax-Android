package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen may not be recomposed by things only one row draws.
 *
 * ## The defect this exists to stop
 *
 * `MainScreen` is one composable covering the tab strip, the pager and all five
 * destinations. Compose skips a composable only when every parameter is stable,
 * and `List<String>` is not — so passing the event log **by value** meant every
 * logged line invalidated the whole screen. `NativeShortsObserver.Status` is a
 * value class but it *changes* about once a second while a Short is on screen,
 * with the same effect.
 *
 * That is expensive here for a reason specific to this app: RustedWax enables
 * its own accessibility services, so `AccessibilityManager.isEnabled()` is true
 * and Compose runs its semantics pipeline —
 * `checkForSemanticsChanges → getCurrentSemanticsNodes → subtreeSortedByGeometryGrouping`,
 * an O(n log n) geometry sort over the whole tree — after each of those
 * invalidations. A `simpleperf` capture on 2026-08-26 showed that pipeline at
 * 35.75% of the main-thread Looper.
 *
 * Reading the value inside the one composable that draws it confines the
 * invalidation to that composable. Measured on the field device with YouTube
 * Music playing, Chrome open, RustedWax foreground and idle, interleaved
 * installs: **171.5 → 116 main-thread jiffies per 30 s.**
 *
 * ## Why source wiring
 *
 * The project has no Robolectric and no Compose UI-test dependency, so no JVM
 * test renders a composable or counts a recomposition. This is the same
 * supplement pattern as `UiSettingsLogWiringTest`: it proves the production
 * declaration keeps the shape the measurement depended on. It is not itself a
 * performance measurement.
 */
class RecompositionScopeWiringTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private fun text(path: String): String = File(root, path).let {
		assertTrue("production source missing: $path", it.isFile)
		it.readText()
	}

	private fun stripComments(source: String): String = source
		.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
		.lines()
		.joinToString("\n") { it.substringBefore("//") }

	private val screen: String get() =
		stripComments(text("app/src/main/java/com/rustedwax/app/ui/MainScreen.kt"))

	private val activity: String get() =
		stripComments(text("app/src/main/java/com/rustedwax/app/MainActivity.kt"))

	@Test
	fun `the event log reaches the screen deferred, not by value`() {
		assertTrue(
			"MainScreen takes logLines by value; an unstable List parameter makes " +
				"every logged line recompose the whole screen",
			screen.contains(Regex("""logLines:\s*\(\)\s*->\s*List<String>""")),
		)
		assertTrue(
			"Wired still reads every emitted log list through delegated state, so " +
				"each append invalidates the parent before the deferred lambda can help",
			activity.contains(Regex("""val\s+logLines\s*=\s*EventLog\.lines\.collectAsStateWithLifecycle\(\)""")),
		)
		assertTrue(
			"the deferred log lambda captures the changing list value instead of the " +
				"stable Compose State holder",
			activity.contains(Regex("""logLines\s*=\s*\{\s*logLines\.value\s*}""")),
		)
	}

	@Test
	fun `the log page is the only thing that reads the log`() {
		assertTrue(
			"LogList no longer takes the log deferred, so reading it happens in " +
				"MainScreen's scope and invalidates every destination",
			screen.contains(Regex("""fun LogList\(\s*lines:\s*\(\)\s*->\s*List<String>""")),
		)
	}

	@Test
	fun `the Shorts observer status reaches the screen deferred`() {
		assertTrue(
			"MainScreen takes nativeShortsStatus by value; it turns over about " +
				"once a second while a Short is on screen",
			screen.contains(Regex("""nativeShortsStatus:\s*\(\)\s*->\s*NativeShortsObserver\.Status""")),
		)
		assertTrue(
			"Wired still reads every Shorts status through delegated state, so the " +
				"parent remains invalidated once per status emission",
			activity.contains(
				Regex("""val\s+nativeShortsStatus\s*=\s*NativeShortsObserver\.status\.collectAsStateWithLifecycle\(\)"""),
			),
		)
		assertTrue(
			"the deferred Shorts lambda captures the changing status value instead " +
				"of the stable Compose State holder",
			activity.contains(
				Regex("""nativeShortsStatus\s*=\s*\{\s*nativeShortsStatus\.value\s*}"""),
			),
		)
	}

	@Test
	fun `live session progress invalidates only the Now list`() {
		assertTrue(
			"MainScreen takes live sessions by value; the once-per-second position " +
				"update recomposes every destination",
			screen.contains(
				Regex("""sessions:\s*\(\)\s*->\s*List<SessionSnapshot>"""),
			),
		)
		assertTrue(
			"the destination label needs a stable count instead of reading the live " +
				"session list in MainScreen",
			screen.contains(Regex("""sessionCount:\s*Int""")),
		)
		assertTrue(
			"SessionList must own the deferred read so progress invalidates only the " +
				"Now page",
			screen.contains(
				Regex("""fun SessionList\(\s*sessions:\s*\(\)\s*->\s*List<SessionSnapshot>"""),
			),
		)
		assertTrue(
			"Wired still delegates the live session value instead of retaining its " +
				"stable State holder",
			activity.contains(
				Regex("""val\s+sessions\s*=\s*remember\s*\{\s*mutableStateOf\("""),
			),
		)
		assertTrue(
			"the screen does not receive the live sessions through a stable State read",
			activity.contains(Regex("""sessions\s*=\s*\{\s*sessions\.value\s*}""")),
		)
	}

	/**
	 * The third cost on the same path: an `AppOpsManager` query and a fresh
	 * `PipPlaybackProbe` allocation that ran on every recomposition because it
	 * sat in the composable body rather than in the one-second poll.
	 */
	@Test
	fun `usage access is probed on the poll, not on every recomposition`() {
		assertTrue(
			"the PiP probe is still constructed per recomposition",
			activity.contains(Regex("""remember\s*\{\s*PipPlaybackProbe\(""")),
		)
		assertTrue(
			"usage access is no longer refreshed by the one-second poll, so it " +
				"would never notice a grant made in another app",
			activity.contains(Regex("""usageAccessGranted\s*=\s*pipProbe\.hasUsageAccess\(\)""")),
		)
	}
}
