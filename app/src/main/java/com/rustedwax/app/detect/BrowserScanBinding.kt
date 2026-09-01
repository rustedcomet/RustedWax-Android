package com.rustedwax.app.detect

/**
 * Which listen a browser screen observation belongs to.
 *
 * ## The question
 *
 * Brave and Chrome let YouTube play in one tab while the user browses another,
 * so a browser package routinely has more than one live MediaSession. An
 * accessibility scan, or an ordinary watch-page ad label, describes exactly one
 * of them — the tab that was on screen — and attributing it to the wrong one
 * hands an ad label, or a coverage proof, to a video the user actually chose to
 * watch.
 *
 * ## The rule, and the defect it replaces
 *
 * The previous selection read:
 *
 * ```kotlin
 * val named = scan.videoId?.let { id -> candidates.singleOrNull { it.describesVideo(id) } }
 * val bound = candidates.singleOrNull() ?: named
 * ```
 *
 * Elimination was tried **first**, so a scan that named a video bound to the
 * sole session even when that session was playing something else entirely. One
 * tab playing video A, a second visible tab showing video B, and the moment the
 * second tab's session had not yet appeared — or had already gone — B's scan
 * became A's coverage and A's ad evidence. That is the exact misattribution the
 * count check existed to prevent, reintroduced by the fix for the count check.
 *
 * The two routes are therefore kept apart and neither falls back to the other:
 *
 * - **A scan that names a video** binds only to a listen describing *that*
 *   video. Not the sole listen, not the playing one — that one. If two listens
 *   claim it, or none does, nothing is bound.
 * - **A scan that names no video** binds only by elimination: exactly one live
 *   listen, so there is nothing else it could have been.
 *
 * ## What counts as a candidate
 *
 * A listen that has already been finalized is not a candidate: the track it
 * described has ended, and evidence observed afterwards cannot belong to it.
 *
 * A paused listen remains present for elimination, because its visible tab may
 * be the source of an id-less scan. It is not eligible to receive evidence,
 * though: a latched id says what a tab describes, not which session is producing
 * playback. Named evidence therefore needs one matching **playing** listen, and
 * id-less evidence needs one live listen total which is itself playing.
 */
object BrowserScanBinding {

	/**
	 * One live listen, as much of it as the choice depends on.
	 *
	 * [key] is the caller's own handle — `SessionProbe` passes the track instance
	 * token — so this file never learns what a `Watch` is.
	 */
	data class Candidate(
		val key: Long,
		/** This listen is the one playing the video the scan named. */
		val describesNamedVideo: Boolean = false,
		/** The listen has ended; nothing observed now belongs to it. */
		val finalized: Boolean = false,
		/**
		 * The transport is currently playing.
		 *
		 * Playback eligibility. Paused/non-playing sessions still block unsafe
		 * elimination, but cannot receive evidence themselves. A
		 * field log reading "2 active MediaSession tracks (1 paused)" is the
		 * difference between a second tab and a stuck one.
		 */
		val playing: Boolean = true,
	)

	sealed interface Selection {

		/**
		 * @param namedThisInstance the scan carried a video id and this listen is
		 * the one playing it, so the binding rests on the observation naming
		 * itself rather than on there being nothing else it could be.
		 */
		data class Bound(val key: Long, val namedThisInstance: Boolean) : Selection

		/** @param reason the tail of one log line; the caller prefixes its own. */
		data class Refused(val reason: String) : Selection
	}

	/**
	 * @param namedVideoId the video the observation itself named, or null when it
	 * named none.
	 */
	fun select(namedVideoId: String?, candidates: List<Candidate>): Selection {
		val live = candidates.filterNot { it.finalized }
		val ended = candidates.size - live.size
		val eligible = live.filter { it.playing }
		val paused = live.size - eligible.size
		val notes = buildList {
			if (ended > 0) add("$ended already finalized")
			if (paused > 0) add("$paused paused")
		}
		val endedNote = if (notes.isEmpty()) "" else " (${notes.joinToString(", ")})"

		if (namedVideoId == null) {
			// Safe elimination, and nothing else. There is no evidence tying this
			// observation to any particular listen, so the only sound binding is one
			// where no other listen exists to be wrong about.
			return when {
				live.size == 1 && eligible.size == 1 ->
					Selection.Bound(eligible.single().key, namedThisInstance = false)
				else -> Selection.Refused(
					"${eligible.size} playback-eligible of ${live.size} active " +
						"MediaSession tracks$endedNote and " +
						"the observation named no video",
				)
			}
		}

		val matching = live.filter { it.describesNamedVideo }
		return when (matching.size) {
			1 -> matching.single().let { match ->
				if (match.playing) {
					Selection.Bound(match.key, namedThisInstance = true)
				} else {
					Selection.Refused(
						"the only active MediaSession track claiming $namedVideoId is paused; " +
							"identity alone is not playback proof",
					)
				}
			}
			0 -> Selection.Refused(
				"${live.size} active MediaSession tracks$endedNote and " +
					"none is playing $namedVideoId",
			)
			// Two listens both claiming the same id is a latch that has not caught
			// up with a tab change, not a reason to pick one. Binding either would
			// be a coin toss with an ad label riding on it.
			else -> Selection.Refused(
				"${matching.size} of ${live.size} active MediaSession tracks$endedNote " +
					"claim $namedVideoId",
			)
		}
	}
}
