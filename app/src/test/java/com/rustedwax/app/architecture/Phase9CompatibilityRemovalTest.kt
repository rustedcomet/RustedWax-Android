package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executable compatibility-removal boundaries. */
class Phase9CompatibilityRemovalTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private val production: List<File> by lazy {
		File(root, "app/src/main/java").walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.toList()
	}

	private fun text(path: String): String = File(root, path).let { file ->
		assertTrue("production source missing: ${file.relativeTo(root)}", file.isFile)
		file.readText()
	}

	private fun references(symbol: String): List<String> = buildList {
		production.forEach { file ->
			file.readLines().forEachIndexed { index, line ->
				if (Regex("\\b${Regex.escape(symbol)}\\b").containsMatchIn(line)) {
					add("${file.relativeTo(root)}:${index + 1}: ${line.trim()}")
				}
			}
		}
	}

	@Test
	fun `the ScrobbleEngine compatibility facade is deleted`() {
		assertFalse(
			"the compatibility facade file still exists",
			File(root, "app/src/main/java/com/rustedwax/app/scrobble/ScrobbleEngine.kt").exists(),
		)
		assertEquals(emptyList<String>(), references("ScrobbleEngine"))
	}

	@Test
	fun `automatic production finalization enters the use case and the UI cannot finalize`() {
		val service = text(
			"app/src/main/java/com/rustedwax/app/detect/RustedWaxListenerService.kt",
		)
		val activity = text("app/src/main/java/com/rustedwax/app/MainActivity.kt")
		val runtime = text(
			"app/src/main/java/com/rustedwax/app/scrobble/FinalizationRuntime.kt",
		)

		assertTrue("automatic finalization bypasses the use case", service.contains("finalizeTrack.execute"))
		// v0.11.1b removed the manual Now-card caller. Manual and shadow remain
		// supported and behaviorally tested modes, but neither has a current live
		// caller. This wiring gate therefore asserts only callers that actually
		// exist and separately keeps the UI outside finalization.
		assertFalse("the UI finalizes a listen itself", activity.contains("finalizeTrack.execute"))
		assertTrue("the concrete runtime does not own one use case", runtime.contains("FinalizeTrackUseCase"))
		assertFalse("the automatic forwarding facade survived", runtime.contains("fun onTrackFinalized"))
		assertFalse("the manual forwarding facade survived", runtime.contains("fun finalizeManually"))
		assertFalse("the shadow forwarding facade survived", runtime.contains("fun finalizeInShadow"))
	}

	@Test
	fun `the legacy SessionProbe Watch state machine is deleted`() {
		val probe = text("app/src/main/java/com/rustedwax/app/detect/SessionProbe.kt")
		assertFalse("legacy Watch symbol survives", Regex("\\bWatch\\b").containsMatchIn(probe))
		assertTrue(
			"the registry does not delegate state ownership to the reducer-backed driver",
			probe.contains("private val driver = MediaSessionDriver("),
		)
		val driver = text("app/src/main/java/com/rustedwax/app/detect/MediaSessionDriver.kt")
		assertTrue("the replacement does not invoke the reducer", driver.contains("PlaybackReducer"))
		assertTrue("metadata callbacks do not become reducer input", driver.contains("PlaybackInput.MetadataChanged"))
		assertTrue("transport callbacks do not become reducer input", driver.contains("PlaybackInput.TransportChanged"))
		assertTrue("destroy callbacks do not become reducer input", driver.contains("PlaybackInput.SessionDestroyed"))
	}

	@Test
	fun `obsolete singleton callbacks and cross component clear owner are deleted`() {
		val callbackOwners = listOf(
			"NotificationHints.kt",
			"UrlEvidence.kt",
			"AdEvidence.kt",
			"MediaSessionAdEvidence.kt",
			"MediaSessionAccessibilityEvidence.kt",
			"NativeShortsObserver.kt",
		)
		callbackOwners.forEach { name ->
			val source = text("app/src/main/java/com/rustedwax/app/detect/$name")
			assertFalse("$name retains a mutable callback", Regex("var\\s+on[A-Z]").containsMatchIn(source))
		}
		assertFalse(
			"the obsolete cross-component clear owner still exists",
			File(root, "app/src/main/java/com/rustedwax/app/detect/RunScopedEvidence.kt").exists(),
		)
	}
}
