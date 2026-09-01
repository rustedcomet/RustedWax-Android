package com.rustedwax.app.scrobble

import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.hive.HiveScrobblePayload
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What became of one finalized track, as a value.
 *
 * ## Why this type exists
 *
 * `<redacted-private-path>` asked for "exactly one payload or one typed refusal
 * per finalized track". That wording cannot be satisfied by this engine and
 * should not be: it has no name for the boundaries that are *deliberately*
 * silent — monitoring stopped, auto-scrobble off, a package that is not a
 * target, a stale source epoch, no posting key — and it forbids the second
 * payload the existing repeat-listen rule legitimately produces. Making the
 * code match that sentence would mean inventing Not-logged rows for switches
 * the user themselves turned off, which is a product regression dressed as a
 * test fix.
 *
 * So the gate is restated as the thing that is actually worth guaranteeing:
 * **every finalized target presented to an initialized engine produces exactly
 * one terminal outcome, and that outcome is one of these three.** No silent
 * fall-through, no double-counting, no path that reaches the end of
 * finalization having decided nothing.
 *
 * ## What this type is not
 *
 * It is not a new decision. Nothing here chooses an outcome; each variant is
 * reported *from* the branch the engine already takes, at the moment it takes
 * it. A recorder that inferred the outcome afterwards — from the skip list, the
 * broadcast list and some rules about which wins — would be a second
 * implementation of the pipeline, and would agree with the first right up until
 * the moment it mattered.
 */
sealed interface FinalizationOutcome {

	/**
	 * A boundary that is meant to be invisible.
	 *
	 * These produce no Not-logged row and no broadcast, and that is the product
	 * behaviour rather than an oversight: a hundred rows reading "auto-scrobble
	 * off" explain nothing the switch is not already saying. Recording them here
	 * makes them auditable without making them visible.
	 */
	data class Ignored(val reason: String) : FinalizationOutcome

	/**
	 * The track was processed and then declined — identity, policy, dedup or
	 * payload construction.
	 *
	 * Most of these do surface in Not logged. Some deliberately do not: a
	 * session never proven to be YouTube is a page watched somewhere else, and
	 * §4.1 keeps it out of the list precisely so the app does not write down
	 * where else someone browsed. Visibility is a separate question from the
	 * outcome, and this type answers only the outcome.
	 */
	data class Refused(val reason: String) : FinalizationOutcome

	/**
	 * The listen earned its entry, with the complete ordered payload list.
	 *
	 * More than one payload only where the existing rule already produces one —
	 * the ≥160% double-listen, songs, no loop evidence. What happens to each
	 * payload afterwards (sent, queued, rejected) is a dispatch result, not a
	 * finalization outcome, and is observable through the broadcaster and the
	 * retry queue.
	 */
	data class Eligible(val payloads: List<HiveScrobblePayload>) : FinalizationOutcome
}

/**
 * Told once per finalized track. A no-op in production.
 *
 * The seam exists so the replay harness can watch the real engine's branches
 * instead of guessing at them from its side effects. Production installs
 * nothing and pays one virtual call per finalize.
 */
fun interface FinalizationObserver {
	fun onFinalizationOutcome(session: SessionSnapshot, outcome: FinalizationOutcome)

	companion object {
		val None = FinalizationObserver { _, _ -> }
	}
}

/**
 * One finalization's report, which can only be filed once.
 *
 * First write wins, and that is the whole design: every exit in
 * `onTrackFinalized` reports, the earliest one reached is the terminal outcome,
 * and later reports from the same finalization — a second payload's dispatch
 * refusing, say — cannot overwrite it. That makes "exactly one outcome" a
 * property of this class rather than a rule every future edit has to remember.
 *
 * Thread-safe because finalization straddles a dispatcher: the synchronous
 * prefilter runs on the media-session callback and everything after enrichment
 * runs on the engine's IO scope.
 */
internal class FinalizationReport(
	private val session: SessionSnapshot,
	private val observer: FinalizationObserver,
	/**
	 * This finalization is a shadow run and may not change anything.
	 *
	 * Carried on the report rather than passed as a second parameter through
	 * forty call sites, because the report is already the one value every branch
	 * of `onTrackFinalized` holds. Where it is read is deliberately narrow — the
	 * two places that write something a user or a chain can see — so a shadow
	 * run takes the *same* decisions as a live one and simply stops before
	 * spending them.
	 */
	val shadow: Boolean = false,
	/**
	 * The playback sequence this finalization may write to.
	 *
	 * The live instance for a live run; a detached copy for a shadow one. Carried
	 * here rather than read from the engine field because the choice has to hold
	 * for the *whole* finalization, across the dispatcher hop and every branch
	 * downstream of it — a per-call-site `if (shadow)` is one forgotten call site
	 * away from a shadow run inserting a listen that never happened.
	 */
	val sequence: VerifiedPlaybackSequence,
	/** Dedup for this run: the real ledger live, a read-through copy in shadow. */
	val claims: DedupClaims,
	/** Watch history for this run: live state or a detached read-equivalent view. */
	val history: WatchHistorySource,
) {
	private val filed = AtomicBoolean(false)

	/**
	 * Whether this finalization has already decided.
	 *
	 * Read by exactly one caller: the failure handler around the launched half,
	 * which must not add a Not-logged row for a listen that had already been
	 * ruled eligible before something downstream threw. It is not a way to ask
	 * "did we decide yet" before deciding — every branch reports as it is taken.
	 */
	val isFiled: Boolean get() = filed.get()

	fun ignored(reason: String) = file(FinalizationOutcome.Ignored(reason))

	fun refused(reason: String) = file(FinalizationOutcome.Refused(reason))

	fun eligible(payloads: List<HiveScrobblePayload>) =
		file(FinalizationOutcome.Eligible(payloads))

	private fun file(outcome: FinalizationOutcome) {
		if (filed.compareAndSet(false, true)) {
			observer.onFinalizationOutcome(session, outcome)
		}
	}
}
