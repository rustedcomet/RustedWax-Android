package com.rustedwax.app.detect

import com.rustedwax.core.*
import kotlin.math.abs

/**
 * Whether the Short taking over the player is the item the MediaSession was
 * already describing.
 *
 * ## The bug this exists for
 *
 * When the foreground Shorts route acquires a Short it takes ownership of the
 * player, and the Android session binding hides its own MediaSession so the same
 * seconds are not counted twice. Until v0.9.13 hiding it also *deleted*
 * whatever that session had accumulated, with no finalization — so a video
 * watched to the end and then followed by a Short simply vanished. Measured
 * 2026-08-07 on the owner's device:
 *
 * ```
 * 14:22:54  played=85778ms                    ← THE RUN, 104s long: 82%
 * 14:22:56  MediaSession hidden while complete foreground Shorts proof is active
 * 14:22:56  foreground Short proof acquired: "These singers crossed the line…"
 * ```
 *
 * No finalize line for the trailer exists anywhere after it. The same log holds
 * ten of these, including 168 seconds of a Caifanes song and 163 of an HBO
 * trailer.
 *
 * Progress earned before the hand-off belongs to whatever the session was
 * playing, and that has to be scored rather than discarded — *unless* the Short
 * taking over is that same item, in which case the foreground route is about to
 * score it and finalizing here would double-count it.
 *
 * ## Why a separate object
 *
 * `Watch` owns a `MediaController` and can't be unit-tested; this predicate is
 * pure, so it lives where a test can reach it — the same split
 * [TrackProgressCarry] exists for.
 */
object ForegroundShortHandover {

	/**
	 * True when [shortTitle]/[shortDurationMs] describe the same item as the
	 * MediaSession's current metadata.
	 *
	 * Title decides whenever both surfaces published one: YouTube puts the same
	 * string in the MediaSession and in the Shorts footer. A Short whose footer
	 * title YouTube declined to render (§16.7) is still recognisable by its
	 * length, which both surfaces do agree on.
	 *
	 * Anything less than that is *not* proof of sameness, and answers false. The
	 * two mistakes are not equal: refusing here costs at most one extra
	 * finalization of a fragment the dedup ledger already caps to one scrobble,
	 * while a wrong "same item" throws a whole earned listen away.
	 */
	fun describesSameItem(
		sessionTitle: String?,
		sessionDurationMs: Long?,
		shortTitle: String?,
		shortDurationMs: Long?,
	): Boolean {
		val session = sessionTitle?.trim().orEmpty()
		val short = shortTitle?.trim().orEmpty()
		if (session.isNotEmpty() && short.isNotEmpty()) return sameTitle(session, short)
		val sessionLength = sessionDurationMs?.takeIf { it > 0 } ?: return false
		val shortLength = shortDurationMs?.takeIf { it > 0 } ?: return false
		return abs(sessionLength - shortLength) <= TrackIdentity.DURATION_REFINEMENT_TOLERANCE_MS
	}

	/**
	 * Normalized title equality, borrowed from [TrackIdentity] rather than
	 * reimplemented, so a hand-off and a track change agree about what counts as
	 * the same title.
	 */
	private fun sameTitle(left: String, right: String): Boolean =
		TrackIdentity(left, null, null, null)
			.sameTrackAs(TrackIdentity(right, null, null, null))
}
