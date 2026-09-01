package com.rustedwax.app.enrich

import java.util.concurrent.ConcurrentHashMap

/**
 * Bounds how often a playlist is re-fetched after a track failed to match it.
 *
 * ## Why a refresh is needed at all
 *
 * A playlist is fetched once and its entries serve every track in it — that is
 * what makes native identity cheap (§4 of `<redacted-private-path>`).
 * The cost is staleness: until v0.9.6 the entry list was never re-read, so a
 * song added to the playlist *after* the first fetch could not be found again
 * for the entire life of the process, and every play of it fell through to the
 * search route that has been measured picking the wrong upload.
 *
 * ## Why it has to be throttled
 *
 * A miss is not evidence of staleness. The commonest miss by far is a track
 * that is legitimately not in the playlist — autoplay carrying on past the last
 * entry, or the stale-but-harmless latch of §6.3 still naming the playlist the
 * user has left. Refreshing on every miss would re-download the playlist page
 * once per track for the rest of that listening session.
 *
 * So: a refresh is allowed only when the cached copy is already older than
 * [minIntervalMs], no more than [maxRefreshes] refreshes have been spent on
 * that playlist in this process, and the previous attempt is itself older than
 * [minIntervalMs]. A newly fetched playlist is therefore never re-fetched on
 * the very next track, and the worst case for a playlist RustedWax keeps
 * missing is a small fixed number of extra page loads.
 */
internal class PlaylistRefreshThrottle(
	private val minIntervalMs: Long = MIN_INTERVAL_MS,
	private val maxRefreshes: Int = MAX_REFRESHES,
) {

	private data class Spent(val refreshes: Int, val lastAttemptMillis: Long)

	private val spent = ConcurrentHashMap<String, Spent>()

	/**
	 * Whether to re-fetch [playlistId] now, counting the attempt when it says
	 * yes. One call, one decision — a caller cannot ask and then not spend.
	 *
	 * @param cachedAtMillis when the entries currently held were fetched
	 */
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
