package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production-shape gates for the temporal automatic-write boundary. */
class AutomaticScrobbleAuthorizationWiringTest {
	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.first { File(it, "settings.gradle.kts").isFile }
	}

	private fun text(path: String): String = File(root, path).readText()

	@Test
	fun `production injects the runtime authorization into every playback producer`() {
		val service = text("app/src/main/java/com/rustedwax/app/detect/RustedWaxListenerService.kt")
		val probe = text("app/src/main/java/com/rustedwax/app/detect/SessionProbe.kt")
		val shorts = text("app/src/main/java/com/rustedwax/app/detect/ForegroundShortTracker.kt")

		assertTrue(service.contains("FinalizationRuntime::automaticWriteAuthorization"))
		assertTrue(probe.contains("automaticWriteAuthorization = trackAutomaticWriteAuthorization"))
		assertTrue(probe.contains("trackAutomaticWriteAuthorization = carried.automaticWriteAuthorization"))
		assertTrue(shorts.contains("resumed?.automaticWriteAuthorization"))
		assertTrue(shorts.contains("automaticWriteAuthorization = state.automaticWriteAuthorization"))
	}

	@Test
	fun `auto mutation and transport commit share one short policy monitor`() {
		val ports = text("app/src/main/java/com/rustedwax/app/scrobble/EnginePorts.kt")
		val policy = ports.substringAfter("internal class SettingsPolicy")
		val setter = policy
			.substringAfter("override var autoScrobble")
			.substringBefore("override val automaticWriteAuthorization")
		val commit = policy
			.substringAfter("override fun tryCommitAutomaticWrite")
			.substringBefore("override val enrichment")

		assertTrue("Auto mutation does not use the policy monitor", setter.contains("synchronized(this)"))
		assertTrue("transport commit does not use the policy monitor", commit.contains("synchronized(this)"))
		assertTrue("transport commit is not tied to the complete target stamp", commit.contains("stamp =="))
	}

	@Test
	fun `automatic transport commits after claim and before eligible dispatch`() {
		val orchestrator = text(
			"app/src/main/java/com/rustedwax/app/scrobble/ProductionFinalizationOrchestrator.kt",
		)
		val sourceNeutral = orchestrator
			.substringAfter("private fun executeSourceNeutral(")
			.substringBefore("\n\tfun execute(\n\t\tsession: SessionSnapshot")
		val youtube = orchestrator.substringAfter("\n\tfun execute(\n\t\tsession: SessionSnapshot")

		fun assertOrdering(path: String, eligibleMarker: String) {
			val claim = path.indexOf("dispatcher.claim")
			val commit = path.indexOf("settings.tryCommitAutomaticWrite")
			val eligible = path.indexOf(eligibleMarker)
			val dispatch = path.indexOf("dispatcher.dispatch")

			assertTrue("automatic finalization has no dedup claim", claim >= 0)
			assertTrue("automatic transport can commit before its claim", commit > claim)
			assertTrue("Eligible can be filed before transport commitment", eligible > commit)
			assertTrue("dispatch can happen before transport commitment", dispatch > eligible)
		}

		assertOrdering(sourceNeutral, "file(FinalizationOutcome.Eligible")
		assertOrdering(youtube, "report.eligible")
	}

	@Test
	fun `transport requires immutable commit proof before any signing capability`() {
		val runtime = text("app/src/main/java/com/rustedwax/app/scrobble/FinalizationRuntime.kt")
		val enqueue = runtime
			.substringAfter("private fun enqueueAndSend(")
		val commitProof = enqueue.indexOf("!automaticTransportCommitted")
		val accountRead = enqueue.indexOf("val account = vault.account")
		val privacySecret = enqueue.indexOf("vault.privacySecret()")
		val keyLoad = enqueue.indexOf("val key = vault.loadKey()")

		assertTrue("automatic dispatch does not require commit proof", commitProof >= 0)
		assertTrue("account access can occur before commit proof", accountRead > commitProof)
		assertTrue("privacy signing capability can be used before commit proof", privacySecret > commitProof)
		assertTrue("a posting key can be loaded before commit proof", keyLoad > commitProof)
		assertTrue(
			"transport reopened live Auto authorization after commitment",
			!enqueue.contains("authorizesAutomaticWrite"),
		)
	}
}
