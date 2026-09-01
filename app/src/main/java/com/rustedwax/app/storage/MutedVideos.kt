package com.rustedwax.app.storage

import android.content.Context

class MutedVideos(context: Context) {

	private val prefs =
		context.getSharedPreferences("rustedwax_muted", Context.MODE_PRIVATE)

	@Synchronized
	fun isMuted(videoId: String): Boolean = prefs.contains(KEY_PREFIX + videoId)

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
