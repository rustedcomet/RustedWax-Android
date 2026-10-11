package com.rustedwax.app.scrobble

import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.core.TrackInstanceId
import com.rustedwax.hive.HiveScrobblePayload
import java.util.concurrent.atomic.AtomicBoolean

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
 * Told once per finalized track.
 *
 * The seam exists so the replay harness can watch the real engine's branches
 * instead of guessing at them from its side effects. In production the only
 * observer is the scrobbling-status card (#4), which records the verdict for
 * that exact listen and returns; it is told after the decision and cannot
 * change it.
 */
fun interface FinalizationObserver {
	fun onFinalizationOutcome(session: SessionSnapshot, outcome: FinalizationOutcome)

	/**
	 * The durable queue operation for one eligible payload was written, before
	 * anything was signed. [account] is the account it will be signed for.
	 *
	 * Read-only correlation for the status card (#4): the only statement that a
	 * given operation — and therefore the History row that names it — belongs
	 * to a given listen. Nothing may infer that from a video id or an order.
	 */
	fun onOperationCreated(link: DispatchLink, operationId: String, account: String) {}

	/** That payload's dispatch stopped before any operation existed; none ever will. */
	fun onDispatchEnded(link: DispatchLink, reason: String) {}

	companion object {
		val None = FinalizationObserver { _, _ -> }
	}
}

/**
 * Which listen, and which of its eligible payloads, one dispatch carries.
 *
 * Passed alongside the payload and read by nothing on the transport path: it
 * signs, queues and broadcasts nothing, and only the observer hears it back.
 */
data class DispatchLink(
	val listen: TrackInstanceId,
	val payloadIndex: Int,
	val payloadCount: Int,
)

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
			// Told, never asked. The verdict is already decided and filed; an
			// observer that throws — the status card is one — must not unwind
			// the path that dispatches it, which used to lose an eligible listen
			// with its dedup claim still held. Contained like the transport's
			// own observer callbacks.
			runCatching { observer.onFinalizationOutcome(session, outcome) }
				// The diagnostic is best-effort as well: `onFailure` runs outside the
				// protected lambda, so a logger that throws here would escape anyway.
				.onFailure { failure ->
					runCatching {
						EventLog.append("engine", "finalization observer failed: ${failure.javaClass.simpleName}")
					}
				}
		}
	}
}
