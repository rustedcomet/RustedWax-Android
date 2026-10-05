package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #47 Stage 47C: About is the third overflow entry, a utility page with
 * the installed version and the project's own words and links — and the
 * Settings version row keeps its seven-tap developer unlock exactly as it was.
 */
class AboutOverflowWiringTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private fun text(path: String) = File(root, path).readText()

	private fun code(source: String) = source
		.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
		.lines().joinToString("\n") { it.substringBefore("//") }

	private val screen get() = code(text("app/src/main/java/com/rustedwax/app/ui/MainScreen.kt"))
	private val readme get() = text("README.md")

	@Test
	fun `every overflow entry has its own icon and a visible label`() {
		val menu = screen.substringAfter("AppNavigation.overflow.forEach").substringBefore("onClick = {")
		assertTrue(menu.contains("text = { Text(AppNavigation.label(destination, 0, 0, 0)) }"))
		assertTrue(menu.contains("Destination.SETTINGS -> WaxIcons.Gear"))
		assertTrue(menu.contains("Destination.ABOUT -> WaxIcons.Info"))
		assertTrue(menu.contains("else -> WaxIcons.ListLines"))
	}

	@Test
	fun `About is drawn from the installed version, as a page of its own`() {
		assertTrue(screen.contains("Destination.ABOUT -> AboutPage(appVersion)"))
		assertTrue(
			"the version is the build's own",
			text("app/src/main/java/com/rustedwax/app/MainActivity.kt").contains("appVersion = BuildConfig.VERSION_NAME"),
		)
		val page = screen.substringAfter("private fun AboutPage(").substringBefore("internal val ABOUT_LINKS")
		assertTrue(page.contains("\"Version \$version\""))
		assertFalse("About must not unlock developer mode", page.contains("onUnlock") || page.contains("DEVELOPER_TAPS"))
	}

	@Test
	fun `About says only what the README says and links only where it links`() {
		val page = screen.substringAfter("private fun AboutPage(").substringBefore("internal val ABOUT_LINKS")
		val readmeTagline = "Android media scrobbling, with conversations around what you play."
		assertTrue(readme.contains(readmeTagline))
		assertTrue(readme.contains("Keep a verified listening and viewing history on Hive."))
		assertTrue(page.contains(readmeTagline))
		// Read from the raw source: stripping `//` comments would cut every URL.
		val raw = text("app/src/main/java/com/rustedwax/app/ui/MainScreen.kt")
		val links = Regex("\"(https://[^\"]+)\"").findAll(raw.substringAfter("internal val ABOUT_LINKS").substringBefore("\n)\n")).map { it.groupValues[1] }.toList()
		assertEquals(3, links.size)
		links.forEach { url ->
			val base = url.removeSuffix("/")
			assertTrue("$url is not a README link", readme.contains(base) || base.endsWith("/releases/latest") && readme.contains("github.com/rustedcomet/RustedWax-Android/releases"))
		}
	}

	@Test
	fun `the Settings version row keeps its seven-tap unlock`() {
		assertTrue(screen.contains("SettingsRow.ABOUT -> AboutRow("))
		assertTrue(screen.contains("onUnlock = { onSetDeveloperMode(true) }"))
		val dev = text("app/src/main/java/com/rustedwax/app/ui/DeveloperSettings.kt")
		assertTrue(dev.contains("const val DEVELOPER_TAPS = 7"))
		assertTrue(dev.contains("if (taps >= DEVELOPER_TAPS) onUnlock()"))
		assertEquals("one unlock, in Settings only", 1, Regex("AboutRow\\(").findAll(screen).count())
	}

	@Test
	fun `utility pages return to the tab they were opened over and the Event Log opens with logging off`() {
		assertTrue(screen.contains("BackHandler(enabled = !AppNavigation.isPrimary(selected)) { chosen = lastPrimary }"))
		assertTrue(screen.contains("EventLogOff(onOpenSettings = { chosen = Destination.SETTINGS })"))
		assertFalse("My Snaps is not repeated in the overflow", screen.substringAfter("AppNavigation.overflow.forEach").substringBefore("onClick = {").contains("MY_SNAPS"))
	}
}
