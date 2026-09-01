package com.rustedwax.app.storage

import android.content.Context

/**
 * Video ids the user has told the app never to scrobble again.
 *
 * ## Why this exists
 *
 * There are two kinds of advertisement in the YouTube Shorts feed, and only one
 * is detectable. A dedicated ad creative is **unlisted**, which
 * [com.rustedwax.app.enrich.VideoFacts.isUnlisted] catches. The other kind is an
 * ordinary *public* video on a brand channel that YouTube promoted — verified
 * 2026-07-29, when `PONDS CAM — Consigue tu rutina ahora.` reached the chain:
 *
 * ```
 *                   POND'S (leaked)   Susy Mouriz (organic)
 *   unlisted        false             false
 *   isCrawlable     true              true
 *   noindex         false             false
 *   viewCount       1,138,564         20,886,671
 * ```
 *
 * Every field in the player response is the same, including
 * `adBreakHeartbeatParams`, `paid` and `playabilityStatus`. They are the same
 * because the promoted video **is** ordinary content — the identical video could
 * have reached the feed organically. No signal available to a media-session
 * observer separates them, so no rule can.
 *
 * What is left is the user's own judgement, applied once. Muting an id can't
 * unwrite what is already on-chain — nothing can — but a promoted video that
 * comes round the feed again won't scrobble a second time.
 *
 * ## Not a dedup ledger
 *
 * The transient listen ledger answers "has this *listen* already been written",
 * is keyed by title and hour, and prunes after six hours. This store answers
 * "is this *video* unwanted, ever", is keyed by video id, and never expires.
 * Different questions, so they stay behind separate boundaries.
 */
class MutedVideos(context: Context) {

	private val prefs =
		context.getSharedPreferences("rustedwax_muted", Context.MODE_PRIVATE)

	@Synchronized
	fun isMuted(videoId: String): Boolean = prefs.contains(KEY_PREFIX + videoId)

	/**
	 * @param label what the entry was called, so the UI can list mutes in terms
	 * the user recognises rather than as eleven-character ids
	 */
	@Synchronized
	fun mute(videoId: String, label: String) {
		if (!isValidVideoId(videoId)) return
		prefs.edit().putString(KEY_PREFIX + videoId, label).apply()
	}

	@Synchronized
	fun unmute(videoId: String) {
		prefs.edit().remove(KEY_PREFIX + videoId).apply()
	}

	/** Muted ids with their labels, for the settings list. */
	@Synchronized
	fun all(): Map<String, String> = prefs.all
		.filterKeys { it.startsWith(KEY_PREFIX) }
		.map { (k, v) -> k.removePrefix(KEY_PREFIX) to (v as? String).orEmpty() }
		.toMap()

	companion object {
		private const val KEY_PREFIX = "muted_"

		/**
		 * Ids come from a scraped address bar, so they are validated rather than
		 * trusted — the same discipline `FactsCache` applies before building a file
		 * path. Nothing here writes to the filesystem, but a preference key is
		 * still persistent state keyed on untrusted input.
		 */
		private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")

		fun isValidVideoId(videoId: String): Boolean = VIDEO_ID.matches(videoId)
	}
}
