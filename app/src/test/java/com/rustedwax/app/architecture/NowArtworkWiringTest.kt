package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #62: player artwork reaches the Now card and nothing else.
 *
 * The picture a player publishes is display-only. These pins keep it wired into
 * Now's banner, with retries, and keep every decision surface — finalization,
 * the scrobble pipeline, Hive — from ever reading it.
 */
class NowArtworkWiringTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private fun stripComments(source: String): String = source
		.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
		.lines()
		.joinToString("\n") { it.substringBefore("//") }

	private fun code(path: String): String = File(root, path).let {
		assertTrue("production source missing: $path", it.isFile)
		stripComments(it.readText())
	}

	@Test
	fun `the Now banner draws this listen's player frame and retries transient misses`() {
		val screen = code("app/src/main/java/com/rustedwax/app/ui/MainScreen.kt")
		assertTrue(screen.contains("PlayerArtwork.frames.collectAsState()"))
		assertTrue(screen.contains("playerFrames[s.trackInstance]"))
		assertTrue(screen.contains("playerArtworkMatches(s, it.title)"))
		assertTrue(screen.contains("fallback = fallback"))
		assertTrue(screen.contains("retryTransient = true"))
	}

	@Test
	fun `History and Not logged keep their recomposition retry and draw no player art`() {
		val screen = code("app/src/main/java/com/rustedwax/app/ui/MainScreen.kt")
		// Exactly one banner — Now's — opts in.
		assertTrue(Regex("retryTransient\\s*=\\s*true").findAll(screen).count() == 1)
		assertTrue(Regex("fallback\\s*=\\s*fallback").findAll(screen).count() == 1)
	}

	@Test
	fun `no decision surface reads player artwork`() {
		val forbidden = listOf("scrobble", "enrich", "storage")
		val main = File(root, "app/src/main/java/com/rustedwax/app")
		forbidden.forEach { dir ->
			File(main, dir).walkTopDown().filter { it.extension == "kt" }.forEach { file ->
				assertFalse(
					"${file.name} must not read display-only player artwork",
					stripComments(file.readText()).contains("PlayerArtwork"),
				)
			}
		}
		val snapshot = code("app/src/main/java/com/rustedwax/app/detect/SessionSnapshot.kt")
		assertFalse(snapshot.contains("Bitmap"))
		assertFalse(snapshot.contains("PlayerArtwork"))
	}
}
