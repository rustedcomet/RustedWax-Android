package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserOriginWiringTest {
	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private fun code(name: String): String =
		File(root, "app/src/main/java/com/rustedwax/app/detect/$name").readText()
			.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
			.lines()
			.joinToString("\n") { it.substringBefore("//") }

	@Test
	fun `browser services use the structural origin parser`() {
		val watcher = code("UrlWatcherService.kt")
		val listener = code("RustedWaxListenerService.kt")

		assertTrue("address-bar evidence bypasses BrowserOrigin", "BrowserOrigin.hostOf(raw)" in watcher)
		assertTrue(
			"notification origin bypasses BrowserOrigin",
			"host = BrowserOrigin.hostOf(subText)" in listener,
		)
		assertFalse("an unanchored HOST extractor remains in the watcher", "HOST.find(" in watcher)
		assertFalse("an unanchored HOST extractor remains in the listener", "HOST.find(" in listener)
	}

	@Test
	fun `free-form fields cannot supply browser origin`() {
		val watcher = code("UrlWatcherService.kt")
		val listener = code("RustedWaxListenerService.kt")

		assertFalse("notification body text can supply a trusted host", "BrowserOrigin.hostOf(text)" in listener)
		assertFalse("an arbitrary page EditText can supply address-bar evidence", "firstEditableText(" in watcher)
	}
}
