package com.rustedwax.app.detect

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

		val finalized: Boolean = false,

		val playing: Boolean = true,
	)

	sealed interface Selection {

		data class Bound(val key: Long, val namedThisInstance: Boolean) : Selection

		data class Refused(val reason: String) : Selection
	}

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
