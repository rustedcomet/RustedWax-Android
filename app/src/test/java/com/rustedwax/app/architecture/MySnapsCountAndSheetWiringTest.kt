package com.rustedwax.app.architecture

import com.rustedwax.app.ui.snaps.SnapThreadSheetHeight
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #47 Stage 47D, asserted where a unit test cannot reach the screen: a
 * My Snaps row asks for its own Comments count the way a History row does, and
 * the conversation sheet opens at about three quarters of the window whatever
 * has been said, growing only for the keyboard.
 */
class MySnapsCountAndSheetWiringTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private fun code(path: String): String = File(root, path).let {
		assertTrue("production source missing: $path", it.isFile)
		it.readText()
			.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
			.replace(Regex("//[^\n]*"), " ")
	}

	private val main by lazy { code("app/src/main/java/com/rustedwax/app/ui/MainScreen.kt") }
	private val sheet by lazy { code("app/src/main/java/com/rustedwax/app/ui/snaps/SnapThreadSheet.kt") }

	private val mySnaps get() = main.substringAfter("private fun MySnapsList(").substringBefore("\nprivate fun ")

	@Test
	fun `each My Snaps row asks for its count once, keyed by account and Snap`() {
		val row = mySnaps.substringAfter("items(rows, key = { it.contentId }) { row ->")
		assertEquals(
			1,
			Regex("""LaunchedEffect\(threads\.threadKey\(root\)\) \{ threads\.loadWhenSettled\(root\) \}""")
				.findAll(row).count(),
		)
		assertTrue(row.contains("threads.preview(it)?.total"))
		assertFalse("a row must never force a read", row.contains("force = true"))
		assertFalse("a row must not read without settling", Regex("""threads\.load\(""").containsMatchIn(row))
	}

	@Test
	fun `History's own count path is unchanged`() {
		assertTrue(main.contains("LaunchedEffect(threads.threadKey(root)) { threads.load(root) }"))
	}

	@Test
	fun `the sheet skips the partial state and takes its height from the keyboard rule`() {
		assertTrue(sheet.contains("rememberModalBottomSheetState(skipPartiallyExpanded = true)"))
		assertTrue(sheet.contains("val typing = WindowInsets.isImeVisible"))
		assertEquals(1, Regex("""\.fillMaxHeight\(""").findAll(sheet.substringAfter("internal fun SnapThreadSheet(")
			.substringBefore("\nprivate fun ")).count())
		assertTrue(sheet.contains(".fillMaxHeight(SnapThreadSheetHeight.fraction(typing))"))
		assertTrue("the composer still rides the keyboard", sheet.contains(".imePadding()"))
		assertFalse(sheet.contains("fillMaxHeight(0.88f)"))
	}

	/**
	 * The whole sheet as a share of the window, the way Material3 1.3.1 builds
	 * it: a 48dp drag handle and the navigation bar outside the body, the body
	 * a share of what is left.
	 */
	private fun sheetShare(windowDp: Float, navDp: Float, imeVisible: Boolean): Float {
		val chrome = 48f + navDp
		return (chrome + SnapThreadSheetHeight.fraction(imeVisible) * (windowDp - chrome)) / windowDp
	}

	@Test
	fun `at rest the sheet is about three quarters of the window on any phone`() {
		for (window in listOf(640f, 780f, 891f, 1000f)) {
			for (nav in listOf(16f, 24f, 48f)) {
				val share = sheetShare(window, nav, imeVisible = false)
				assertTrue("window $window nav $nav gave $share", share in 0.72f..0.79f)
			}
		}
	}

	@Test
	fun `the keyboard grows the sheet rather than squeezing the conversation`() {
		assertTrue(SnapThreadSheetHeight.fraction(imeVisible = true) > SnapThreadSheetHeight.fraction(imeVisible = false))
		assertEquals(0.88f, SnapThreadSheetHeight.fraction(imeVisible = true))
	}
}
