package com.rustedwax.app.detect

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
