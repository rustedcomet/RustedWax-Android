package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #43: what the update check may and may not reach, asserted on source.
 *
 * It reads one metadata file and may post one notification. It never
 * downloads or installs an APK, adds no service, job, receiver or permission,
 * and touches no other notification channel.
 */
class UpdateCheckWiringTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private fun text(path: String): String = File(root, path).let {
		assertTrue("missing: $path", it.isFile)
		it.readText()
	}

	private fun code(path: String): String = text(path)
		.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
		.lines()
		.joinToString("\n") { it.substringBefore("//") }

	private val update by lazy {
		File(root, "app/src/main/java/com/rustedwax/app/update").listFiles()!!
			.filter { it.extension == "kt" }
			.joinToString("\n") { code(it.relativeTo(root).path) }
	}

	@Test
	fun `nothing downloads or installs an APK`() {
		val manifest = text("app/src/main/AndroidManifest.xml")
		listOf("REQUEST_INSTALL_PACKAGES", "INSTALL_PACKAGES", "DOWNLOAD_WITHOUT_NOTIFICATION")
			.forEach { assertFalse("manifest asks for $it", it in manifest) }
		listOf("DownloadManager", "PackageInstaller", "ACTION_INSTALL_PACKAGE", "application/vnd.android.package-archive", ".apk", "FileOutputStream")
			.forEach { assertFalse("update code names `$it`", it in update) }
	}

	@Test
	fun `no service, job, receiver, alarm or new permission`() {
		listOf("JobScheduler", "JobInfo", "AlarmManager", "BroadcastReceiver", "Service()", "WorkManager", "WakeLock", "while (true)")
			.forEach { assertFalse("update code uses `$it`", it in update) }
		val manifest = text("app/src/main/AndroidManifest.xml")
		assertEquals("no new permission", 5, Regex("<uses-permission").findAll(manifest).count())
		assertFalse("update component in the manifest", "update" in manifest.lowercase().substringAfter("<application"))
	}

	@Test
	fun `its own channel and id, and no other channel is touched`() {
		assertTrue("const val CHANNEL = \"rustedwax_app_updates\"" in update)
		assertTrue("const val ID = 0x5155_0043" in update)
		listOf("rustedwax_snap_replies", "rustedwax_scrobble_status", "0x5157_0004", "SnapNotice", "ScrobbleStatus")
			.forEach { assertFalse("update code names `$it`", it in update) }
	}

	@Test
	fun `it reaches nothing that detects, finalizes, signs or posts`() {
		listOf("scrobble.", "snaps.", "detect.Session", "Finalization", "KeyVault", "Broadcast", "hive.", "LocalDatabase")
			.forEach { assertFalse("update code imports `$it`", Regex("import com\\.rustedwax\\.app\\.${Regex.escape(it)}").containsMatchIn(update)) }
	}

	@Test
	fun `the override is debug only and the tap target is never read from metadata`() {
		assertTrue("BuildConfig.DEBUG && it.isFile" in update)
		assertFalse("a URL is read from metadata", Regex("""opt(String)?\("(url|downloadUrl|destination|html_url)""").containsMatchIn(update))
		assertTrue("destination comes from distribution", "UpdateDestination.of(distribution)" in update)
	}

	@Test
	fun `the activity runs it off the main thread on start`() {
		val activity = code("app/src/main/java/com/rustedwax/app/MainActivity.kt")
		assertTrue(
			"lifecycleScope.launch(Dispatchers.IO) { UpdateChecks.runOnStart(applicationContext) }" in activity,
		)
		assertEquals(1, Regex("UpdateChecks\\.").findAll(activity).count())
	}
}
