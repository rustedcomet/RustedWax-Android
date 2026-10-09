package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #56, 3A: the Activity hands every editor and deleter the one
 * process-wide [com.rustedwax.app.snaps.SnapWriteGuards] — never a guard of its
 * own, which would be forgotten on recreation exactly as before.
 */
class SnapWriteGuardsWiringTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root not found")
	}

	private val activity get() = File(root, "app/src/main/java/com/rustedwax/app/MainActivity.kt").readText()

	@Test
	fun `the activity shares the process guard and never builds its own`() {
		assertTrue(activity.contains("val writeGuards = SnapWriteGuards.process(applicationContext)"))
		assertFalse("an Activity-scoped guard is forgotten on recreation", activity.contains("SnapWriteGuards()"))
	}

	@Test
	fun `both editors and both deleters are given it`() {
		val built = Regex("""Snap(Editor|Deleter)\(""").findAll(activity).count()
		assertEquals(4, built)
		assertEquals("every editor and deleter shares it", built, Regex("guards = writeGuards").findAll(activity).count())
	}
}
