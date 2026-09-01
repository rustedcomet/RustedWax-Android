package com.rustedwax.core

/**
 * Wall-clock time, as a value the caller is handed rather than a fact it reads.
 *
 * The only reason this exists is that a scrobble carries two timestamps a
 * person can check — the listen's own `trackStartedAtEpochSec` and the moment a
 * decision was recorded — and a replay that cannot fix both cannot assert on
 * either. Every production wiring passes [SystemClock], so nothing about the
 * shipped behaviour changes; the seam is what lets an ordered event trace be
 * replayed twice and produce the same bytes.
 *
 * Deliberately a one-method interface so pure policy, transport code, and
 * deterministic replays can share it without inheriting a runtime clock API.
 */
fun interface Clock {
	fun nowMillis(): Long

	/** The same reading in whole seconds, which is what payload timestamps use. */
	fun nowEpochSeconds(): Long = nowMillis() / 1000
}

/** The real clock. The default everywhere in production. */
object SystemClock : Clock {
	override fun nowMillis(): Long = System.currentTimeMillis()
}
