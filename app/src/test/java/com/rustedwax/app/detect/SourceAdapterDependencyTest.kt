package com.rustedwax.app.detect

import com.rustedwax.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAdapterDependencyTest {

	private val adapterSources = listOf(
		"SourceAdapter.kt",
		"BrowserYouTubeAdapter.kt",
		"NativeYouTubeAdapter.kt",
		"NativeYouTubeSource.kt",
		"NativeShortsAdapter.kt",
		"YouTubeMusicAdapter.kt",
	)

	/**
	 * Named by the concept they represent, so the rule reads as the boundary
	 * rather than as a list of files someone may rename around it.
	 */
	private val forbidden = mapOf(
		"FinalizationRuntime" to "the finalization orchestrator",
		"BroadcastQueue" to "the retry queue",
		"DedupLedger" to "the deduplication ledger",
		"HiveScrobblePayload" to "the on-chain payload",
		"HiveRpc" to "the chain client",
		"ScrobbleRules" to "the eligibility policy",
		"FinalizationOutcome" to "the terminal-outcome vocabulary",
		"onTrackFinalized" to "the finalization callback",
		"SessionSnapshot" to "the finalized transport object",
	)

	private fun read(name: String): List<String> {
		val file = java.io.File("src/main/java/com/rustedwax/app/detect/$name")
		assertTrue("adapter source not found at ${file.absolutePath}", file.isFile)
		return file.readLines()
	}

	/**
	 * The four adapters and their shared contract may not name any of it.
	 *
	 * Comments and KDoc legitimately discuss why finalization stays with the host
	 * — several of them say so at length — so the ban is on *code*.
	 */
	@Test
	fun `no adapter can reach the engine, queue, dedup ledger, payload or finalized transport`() {
		val offenders = adapterSources.flatMap { name ->
			read(name).withIndex().mapNotNull { (index, line) ->
				val code = line.substringBefore("//").trim()
				if (code.isEmpty() || code.startsWith("*") || code.startsWith("/*")) return@mapNotNull null
				forbidden.keys.firstOrNull { code.contains(it) }
					?.let { "$name:${index + 1} names ${forbidden[it]} ($it): ${code.take(80)}" }
			}
		}

		assertEquals(
			"a source adapter reached past its boundary:\n" + offenders.joinToString("\n"),
			emptyList<String>(),
			offenders,
		)
	}

	@Test
	fun `the dependency scan reads real adapter code and would catch a violation`() {
		adapterSources.forEach { name -> assertTrue("$name is empty", read(name).isNotEmpty()) }
		assertTrue("adapter behavior sources look implausibly short", adapterSources.sumOf {
			read(it).size
		} > 300)
		// The identical predicate, applied to a file that legitimately does name
		// the forbidden concepts, must report them.
		val host = java.io.File("src/main/java/com/rustedwax/app/detect/SessionProbe.kt").readLines()
		val hostHits = host.count { line ->
			val code = line.substringBefore("//").trim()
			code.isNotEmpty() && !code.startsWith("*") &&
				forbidden.keys.any { code.contains(it) }
		}
		assertTrue(
			"the scan found nothing even in the host, so it cannot be catching anything",
			hostHits > 0,
		)
	}

	@Test
	fun `the Shorts boundary emits neutral inputs and owns production stabilization`() {
		val adapter = read("NativeShortsAdapter.kt").joinToString("\n")
		val probe = java.io.File(
			"src/main/java/com/rustedwax/app/detect/SessionProbe.kt",
		).readText()
		val observer = java.io.File(
			"src/main/java/com/rustedwax/app/detect/NativeShortsObserver.kt",
		).readText()

		assertTrue("the Shorts adapter does not return PlaybackInput", "List<PlaybackInput>" in adapter)
		assertTrue("the removed parallel ShortsObservation model returned", "ShortsObservation" !in adapter)
		assertTrue("SessionProbe resumed parsing source observations", "NativeShortParser" !in probe)
		assertTrue(
			"SessionProbe resumed constructing tracker-specific source observations",
			"ForegroundShortTracker.OrganicObservation" !in probe &&
				"ForegroundShortTracker.AdObservation" !in probe,
		)
		assertTrue("the adapter no longer owns stabilization", "NativeShortStabilizer()" in adapter)
		assertTrue(
			"global observer stabilization survived outside the adapter",
			"NativeShortStabilizer" !in observer,
		)
	}

	@Test
	fun `the Android Shorts gateway cannot interpret unavailable-surface stability or promotion`() {
		val service = java.io.File(
			"src/main/java/com/rustedwax/app/detect/NativeShortsAccessibilityService.kt",
		).readLines()
		val forbiddenGatewayPolicy = listOf(
			"resetSurfaceStability(",
			"promotePendingOrganicForImmediatePip(",
			"resetSurfaceStability =",
			"pending != null",
		)
		val offenders = service.withIndex().mapNotNull { (index, line) ->
			val code = line.substringBefore("//").trim()
			forbiddenGatewayPolicy.firstOrNull(code::contains)?.let {
				"NativeShortsAccessibilityService.kt:${index + 1} contains $it: ${code.take(100)}"
			}
		}

		assertEquals(
			"the Android gateway still owns native Shorts stabilization policy:\n" +
				offenders.joinToString("\n"),
			emptyList<String>(),
			offenders,
		)
	}
}
