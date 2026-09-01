package com.rustedwax.app.enrich

import java.util.concurrent.ConcurrentHashMap

internal class PlaylistRefreshThrottle(
	private val minIntervalMs: Long = MIN_INTERVAL_MS,
	private val maxRefreshes: Int = MAX_REFRESHES,
) {

	private data class Spent(val refreshes: Int, val lastAttemptMillis: Long)

	private val spent = ConcurrentHashMap<String, Spent>()

	fun claim(playlistId: String, cachedAtMillis: Long, nowMillis: Long): Boolean {
		if (nowMillis - cachedAtMillis < minIntervalMs) return false
		var granted = false
		spent.compute(playlistId) { _, previous ->
			val refreshes = previous?.refreshes ?: 0
			val lastAttempt = previous?.lastAttemptMillis
			granted = refreshes < maxRefreshes &&
				(lastAttempt == null || nowMillis - lastAttempt >= minIntervalMs)
			if (granted) Spent(refreshes + 1, nowMillis) else previous
		}
		return granted
	}

	/** Monitoring stop, package opt-out or source-epoch change starts over. */
	fun reset() {
		spent.clear()
	}

	internal companion object {
		/**
		 * Long enough that a playlist being played straight through never
		 * re-fetches, short enough that "I added a song, play it" works on the
		 * second or third track rather than after a restart.
		 */
		const val MIN_INTERVAL_MS = 10 * 60 * 1000L

		/** A playlist RustedWax simply is not playing costs at most this many pages. */
		const val MAX_REFRESHES = 4
	}
}
