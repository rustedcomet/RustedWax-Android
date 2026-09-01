package com.rustedwax.app.replay

import com.rustedwax.app.detect.MediaSessionAccessibilityEvidence
import com.rustedwax.app.detect.MediaSessionAdEvidence
import com.rustedwax.app.detect.NativeSourceSwitches
import com.rustedwax.app.detect.NotificationHints
import com.rustedwax.app.detect.UrlEvidence
import com.rustedwax.app.enrich.VerifiedIdentityCandidateCache
import com.rustedwax.app.scrobble.FinalizationRuntime
import org.junit.After

/**
 * Puts the process-wide state back the way it was found.
 *
 * `FinalizationRuntime` is an object and the evidence stores are objects, so a JVM
 * that has run one replay is not the JVM the next test expects. `ReplayHarness`
 * already clears everything on the way *in*, which is what makes the scenarios
 * independent of each other; this clears on the way *out*, so the replay package
 * cannot change what a test in some other package sees.
 *
 * That asymmetry is deliberate. Cleaning up after yourself is politeness;
 * refusing to trust the state you were handed is what actually makes a suite
 * order-independent, and the harness does both.
 *
 * This exists at all because of `<redacted-private-path>` §2 — mutable evidence
 * with no single owner. Phase 5 gives that state a run-scoped owner and this
 * class becomes unnecessary. Until then, one teardown in one place beats six.
 */
abstract class ReplayScenarioTest {

	/**
	 * Hold every harness this test built to the exactly-one-terminal-outcome rule.
	 *
	 * Applied here rather than per scenario on purpose. The gate is a property of
	 * the engine, not of the handful of scenarios that would have remembered to
	 * assert it, and a rule that has to be opted into is the rule that is missing
	 * from the case that eventually breaks. Every scenario in this package now
	 * proves it, including the ones written before the rule existed.
	 *
	 * Runs before the state reset so a failure names the outcomes that were
	 * actually filed.
	 */
	@After
	fun everyFinalizationProducedExactlyOneOutcome() {
		try {
			ReplayHarness.live.forEach { it.assertOneOutcomePerFinalization() }
		} finally {
			ReplayHarness.live.clear()
		}
	}

	@After
	fun restoreProcessWideState() {
		FinalizationRuntime.resetForReplay()
		NativeSourceSwitches.configureForReplay(NativeSourceSwitches.Config())
		UrlEvidence.clearAll()
		NotificationHints.clearAll()
		MediaSessionAdEvidence.clearAll()
		MediaSessionAccessibilityEvidence.clearAll()
		VerifiedIdentityCandidateCache.clearAll()
	}
}
