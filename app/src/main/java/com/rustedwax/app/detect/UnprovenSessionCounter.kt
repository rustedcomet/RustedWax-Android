package com.rustedwax.app.detect

/**
 * The only thing a non-YouTube session is allowed to leave behind.
 *
 * ## What this replaces
 *
 * `<redacted-private-path>` §4.1. A browser publishes a MediaSession for any video
 * site, so RustedWax created a `Watch` and logged its page title before anything
 * had proven the site was YouTube — producing a durable, exportable record of
 * what the user watched elsewhere. Every field of that record was identifying:
 * the title said what, the timestamp said when, the package said where.
 *
 * ## Why a count and not nothing at all
 *
 * "Is it running?" is a real question, and the honest answer to it was buried in
 * exactly the lines that had to go. A user whose music is not scrobbling needs
 * to be able to tell "the probe is seeing sessions and rejecting them" from "the
 * probe is seeing nothing" — those have completely different causes. A count
 * answers that and describes no one: `4 non-YouTube sessions ignored` says the
 * probe is alive and says nothing about where anybody went.
 *
 * Rate-limited rather than written per session, because a line per rejection
 * would restore the timing channel this exists to close — the *number* of lines
 * would track browsing even with every name removed.
 */
class UnprovenSessionCounter(
	private val intervalMillis: Long = REPORT_INTERVAL_MS,
	private val now: () -> Long = System::currentTimeMillis,
) {

	private var since: Long = 0
	private var lastReportAt: Long = 0

	/** A session was seen and could not be proven to be YouTube. */
	@Synchronized
	fun note() {
		since++
		val nowMillis = now()
		if (lastReportAt == 0L) {
			lastReportAt = nowMillis
			return
		}
		if (nowMillis - lastReportAt < intervalMillis) return
		flush(nowMillis)
	}

	/** Emit the pending count if there is one. */
	@Synchronized
	fun flush(nowMillis: Long = now()) {
		lastReportAt = nowMillis
		if (since == 0L) return
		val count = since
		since = 0
		EventLog.append(
			"session",
			"$count non-YouTube ${if (count == 1L) "session" else "sessions"} ignored " +
				"(nothing about them is recorded)",
		)
	}

	@Synchronized
	fun reset() {
		since = 0
		lastReportAt = 0
	}

	companion object {
		/**
		 * Long enough that the count cannot be read as a browsing timeline, short
		 * enough that "it is running" is still answerable while someone watches
		 * the log during setup.
		 */
		const val REPORT_INTERVAL_MS = 5 * 60_000L
	}
}
