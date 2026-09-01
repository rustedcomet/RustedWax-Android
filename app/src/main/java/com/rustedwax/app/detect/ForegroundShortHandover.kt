package com.rustedwax.app.detect

import com.rustedwax.core.*
import kotlin.math.abs

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
