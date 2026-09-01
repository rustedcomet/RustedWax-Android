package com.rustedwax.app.scrobble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production-wiring gates for Architecture Audit Phase 7. */
class UnifiedFinalizationWiringTest {

	private fun source(path: String): String {
		val file = java.io.File("src/main/java/com/rustedwax/app/$path")
		assertTrue("production source missing at ${file.absolutePath}", file.isFile)
		return file.readText()
	}

	/**
	 * One use case, and no forwarding facade in front of it.
	 *
	 * The manual clause changed shape in v0.11.1b and did not weaken. It used to
	 * read "the Now card enters the use case directly", which was the strongest
	 * statement available while a `Broadcast this scrobble` button existed. That
	 * button is gone, so the activity is now asserted to reach finalization
	 * **not at all** — it does not execute the use case, decide eligibility,
	 * build a payload, claim dedup, or hold a transport path of its own.
	 *
	 * The `MANUAL` and `SHADOW` modes themselves are deliberately untouched and
	 * remain behaviorally tested. Neither has a current live caller, so this
	 * wiring test makes no fabricated caller claim: it proves the automatic
	 * caller and separately keeps the UI and old compatibility entries out.
	 */
	@Test
	fun `automatic production finalization enters one use case and the UI does not`() {
		val runtime = source("scrobble/FinalizationRuntime.kt")
		val service = source("detect/RustedWaxListenerService.kt")
		val activity = source("MainActivity.kt")

		assertTrue("the production runtime does not own the Phase 7 use case", "FinalizeTrackUseCase" in runtime)
		assertTrue("automatic playback does not invoke the shared use case", "finalizeTrack.execute" in service)
		assertFalse("the automatic compatibility entry point survived", "fun onTrackFinalized" in runtime)
		assertFalse("the manual compatibility entry point survived", "fun finalizeManually" in runtime)

		assertFalse("MainActivity finalizes a listen itself", "finalizeTrack.execute" in activity)
		assertFalse("MainActivity still re-runs eligibility", "ScrobbleRules.decide" in activity)
		assertFalse("MainActivity still rebuilds payloads", "ScrobbleBuilder.from" in activity)
		assertFalse("MainActivity still owns manual dedup", "claimManual" in activity)
		assertFalse("MainActivity still broadcasts finalized listens itself", "broadcast(manualPayload" in activity)
		assertFalse("a second transport path survives in the UI", "broadcaster.broadcastScrobble" in activity)
	}

	@Test
	fun `queue retry is dispatch only and never re-enters finalization`() {
		val runtime = source("scrobble/FinalizationRuntime.kt")

		assertTrue("the production runtime does not own a dispatcher", "ScrobbleDispatcher" in runtime)
		assertTrue("the retry path does not delegate to dispatch retry", "dispatcher.retryDue" in runtime)
		val retryBody = runtime.substringAfter("fun flushQueue()", "")
		assertFalse("queue retry can re-finalize a listen", "finalizeTrack.execute" in retryBody)
	}
}
