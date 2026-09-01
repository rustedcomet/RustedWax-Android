package com.rustedwax.app.detect

import com.rustedwax.core.*
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production-source assertions for the Phase 5 ownership move. */
class Phase5ProductionWiringTest {
	private val root: File by lazy {
		val workingDirectory = requireNotNull(System.getProperty("user.dir"))
		generateSequence(File(workingDirectory).absoluteFile) { it.parentFile }
			.firstOrNull { File(it, "app/src/main/java/com/rustedwax/app/detect/SessionProbe.kt").isFile }
			?: error("repository root was not found")
	}

	private fun read(name: String): String =
		File(root, "app/src/main/java/com/rustedwax/app/detect/$name").readText()

	@Test
	fun `the listener service owns and injects one coordinator`() {
		val listener = read("RustedWaxListenerService.kt")
		val probe = read("SessionProbe.kt")
		assertTrue(listener.contains("private var evidenceCoordinator: EvidenceCoordinator?"))
		assertTrue(listener.contains("EvidenceCoordinator().also { it.start() }"))
		assertTrue(listener.contains("evidenceCoordinator = coordinator"))
		assertTrue(probe.contains("private val evidenceCoordinator: EvidenceCoordinator"))
	}

	@Test
	fun `production probe uses a flow and no singleton callback assignments`() {
		val probe = read("SessionProbe.kt")
		assertTrue(probe.contains("evidenceCoordinator.events.collect"))
		listOf(
			"NotificationHints.onHint =",
			"UrlEvidence.onEvidence =",
			"AdEvidence.onEvidence =",
			"MediaSessionAdEvidence.onObservation =",
			"MediaSessionAccessibilityEvidence.onScan =",
			"NativeShortsObserver.onEvent =",
		).forEach { forbidden -> assertFalse(forbidden, probe.contains(forbidden)) }
	}

	@Test
	fun `production adapters and reducer receive the service owned coordinator`() {
		val probe = read("SessionProbe.kt").replace(Regex("\\s+"), "")
		assertTrue(probe.contains("SourceRegistry.forWatch(packageName,appLabel,evidenceCoordinator,evidenceSourceSession,"))
		assertTrue(probe.contains("PlaybackReducer(adapter.playbackCapabilities,evidenceCoordinator,"))
		assertTrue(probe.contains("evidenceCoordinator.allocateTrackInstance(sourceSession)"))
		assertFalse(probe.contains("MediaSessionAdEvidence.nextTrackToken()"))
	}

	@Test
	fun `URL clearing has no cross layer clearing calls`() {
		val urlStore = read("UrlEvidence.kt")
		assertFalse(urlStore.contains("MediaSessionAdEvidence.clear"))
		assertFalse(urlStore.contains("MediaSessionAccessibilityEvidence.clear"))
		assertFalse(urlStore.contains("VerifiedIdentityCandidateCache.clear"))
	}

	@Test
	fun `shadow finalization has no route to the live evidence owner`() {
		val runtime = File(root, "app/src/main/java/com/rustedwax/app/scrobble/FinalizationRuntime.kt")
			.readText()
		assertFalse(runtime.contains("EvidenceCoordinator"))
		assertFalse(
			File(root, "app/src/main/java/com/rustedwax/app/detect/RunScopedEvidence.kt").exists(),
		)
	}
}
