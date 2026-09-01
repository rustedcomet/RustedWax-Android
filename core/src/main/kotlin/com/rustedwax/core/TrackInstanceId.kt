package com.rustedwax.core

/**
 * One continuous listen, identified by something a second cannot collide on.
 *
 * ## The defect this replaces
 *
 * `VerifiedPlaybackSequence` used to key its ordered entries on
 * `(packageName, trackStartedAtEpochSec)`. Two listens that begin inside the
 * same wall-clock second are then the *same* entry, and both of the sequence's
 * jobs break at once:
 *
 *  - `begin()` sees an entry already present and adds nothing, so the boundary
 *    an unresolved middle item is supposed to create never exists;
 *  - `remember()` finds the earlier row by start second and overwrites it, so
 *    the first track's verified id is replaced by the second's.
 *
 * A caller then asks for the two immediately preceding finalized tracks and is
 * handed a pair that were never adjacent. That pair feeds
 * `resolveEvidenceFromAdjacentPredecessors`, which recovers a *public playlist*
 * from it — so the consequence is not a missing scrobble, it is a listen
 * attributed to the wrong playlist and, through it, potentially the wrong video.
 *
 * Same-second transitions are ordinary rather than exotic. A track change
 * announced by a metadata callback finalizes the outgoing listen and starts the
 * incoming one in the same callback; a duration replacement
 * does the same; starting the next track re-stamps `trackStartedAtEpochSec` from
 * the same `System.currentTimeMillis()` reading the finalize just used.
 *
 * ## What makes the token unique
 *
	* [instanceToken] is allocated from a process-wide monotonic counter. It was
 * introduced so an ad label could be bound to one exact track instance, which is
 * the same question this asks, so the two now share one answer.
 *
 * The token is **monotonic within a run and carried across transport
 * recreation**: carry restoration retains the token along with the played time
 * and the frozen start, so a listen that survives a source
 * rebuilding its session keeps one identity rather than acquiring a second.
 * That is exactly the property the sequence needs — it must not see a resumed
 * listen as a new entry.
 *
 * Because a process restart resets the counter, [packageName] is part of the
 * identity and the sequence still orders by the frozen start. The token breaks
 * ties; it does not replace the ordering.
 */
data class TrackInstanceId(
	val packageName: String,
	val instanceToken: Long,
) {
	init {
		require(packageName.isNotBlank()) { "a track instance must name its package" }
		// Zero is the one value that cannot mean anything here: the process-wide
		// counter starts at 1, and the degraded [fromStartSecond] identity negates a
		// start second. A zero token would be two different listens comparing equal,
		// which is the collision this type exists to remove.
		require(instanceToken != 0L) { "a track instance token is never zero" }
	}

	override fun toString(): String = "$packageName#$instanceToken"

	companion object {
		/**
		 * The identity for a listen whose source published no instance token.
		 *
		 * No production path reaches this today — both `MediaSessionDriver` and
		 * `ForegroundShortTracker` receive a real token — and it exists so the
		 * adapter is total rather than throwing on a snapshot built by a test or
		 * by a future caller. It degrades to the old start-second key, which
		 * means it degrades to the old collision: that is stated here rather than
		 * hidden, because a silent fallback that quietly reintroduced the bug
		 * would be worse than the bug.
		 */
		fun fromStartSecond(packageName: String, startedAtEpochSec: Long): TrackInstanceId =
			// Offset by one so the epoch second `0` — which a fixture or a frozen
			// test clock reaches easily — does not land on the one token value that
			// means "unset". Still injective, and still negative, so a degraded
			// identity can never collide with a real allocated token.
			TrackInstanceId(packageName, -(startedAtEpochSec + 1))
	}
}
