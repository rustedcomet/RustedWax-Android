package com.rustedwax.app.scrobble

import com.rustedwax.core.TextNormalizer
import android.content.Context
import com.rustedwax.app.detect.TrackProgressCarry

/**
 * Remembers which listens have already been scrobbled, so a session rebuild or
 * a double finalize can't write the same listen twice. A replay is a new listen
 * and must get a new key even when it starts only seconds later.
 *
 * Ported from the `finalized_<startTimestamp>` scheme in `hive-scrobbler.ts`,
 * including the 6-hour prune. Two deliberate changes:
 *
 *  - **Durable, not session-scoped.** The extension used
 *    `browser.storage.session`, which the browser clears on exit. Android kills
 *    and restarts our process freely, so a session-scoped ledger would forget
 *    everything mid-listen and re-scrobble.
 *  - **Keyed by content and the listen start.** The title and artist let the
 *    manual and automatic paths meet across sources, while the exact frozen
 *    start identifies the viewing. [TrackProgressCarry] restores that start
 *    when Android rebuilds a session mid-listen.
 */
class DedupLedger(context: Context) {

	private val prefs =
		context.getSharedPreferences("rustedwax_dedup", Context.MODE_PRIVATE)

	/** True if this is the first time we've seen the key; marks it as used. */
	@Synchronized
	fun claim(key: String): Boolean {
		val storageKey = PREFIX + key
		if (prefs.contains(storageKey)) return false
		prefs.edit().putLong(storageKey, System.currentTimeMillis()).apply()
		return true
	}

	@Synchronized
	fun contains(key: String): Boolean = prefs.contains(PREFIX + key)

	/**
	 * Give a claim back.
	 *
	 * Only for the manual-broadcast path, which claims *before* sending so two
	 * quick taps can't both get through, and must undo that if the send fails —
	 * otherwise a network error would permanently block retrying the listen.
	 * The automatic path never releases: a failed send there goes to
	 * [BroadcastQueue], so the listen is still owed.
	 */
	@Synchronized
	fun release(key: String) {
		prefs.edit().remove(PREFIX + key).apply()
	}

	/** Drop entries older than 6 hours, matching upstream's prune window. */
	@Synchronized
	fun prune() {
		val cutoff = System.currentTimeMillis() - RETENTION_MS
		val editor = prefs.edit()
		var removed = 0
		for ((k, v) in prefs.all) {
			if (!k.startsWith(PREFIX)) continue
			val stamp = v as? Long ?: 0L
			if (stamp < cutoff) {
				editor.remove(k)
				removed++
			}
		}
		if (removed > 0) editor.apply()
	}

	companion object {
		private const val PREFIX = "finalized_"
		private const val RETENTION_MS = 6 * 60 * 60 * 1000L

		/**
		 * Key for one listen.
		 *
		 * This deliberately uses the exact frozen start, not a clock-hour bucket.
		 * The old bucket made the answer depend on UTC boundaries: starts at 19:57
		 * and 20:21 were distinct, while genuine starts at 20:21 and 20:52
		 * collided. All finalize paths for one listen carry the same start; a
		 * replay gets a new one.
		 *
		 * Normalized through [TextNormalizer], not merely lowercased.
		 *
		 * `<redacted-private-path>` §3.6: this key folds in an artist field that may
		 * now be an owner handle, and `OwnerHandle` composes to NFC while this
		 * lowercased raw text. Two spellings of one cedilla therefore produced two
		 * keys for one listen, and the ledger's whole job is to stop that landing
		 * twice on a chain nobody can edit.
		 *
		 * Presentation form: a title and an artist are text a human reads, so a
		 * ligature and its letters are the same listen.
		 */
		fun keyFor(title: String, artist: String?, startedAtEpochSec: Long): String {
			return "${TextNormalizer.presentation(title)}|" +
				"${TextNormalizer.presentation(artist)}|start:$startedAtEpochSec"
		}
	}
}
