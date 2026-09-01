package com.rustedwax.app.detect

import java.util.concurrent.ConcurrentHashMap

/**
 * What the browser's address bar last said, per browser package.
 *
 * The strongest evidence available: it names the origin exactly instead of
 * inferring it, and when the full URL is exposed it carries the video id — the
 * preferred exact route to the payload hyperlink and enrichment. Finalization
 * can also recover an id from playlist/search evidence when the bar stays bare.
 *
 * Three limits are baked into how this is consumed, not bolted on:
 *
 *  1. It describes the **foreground tab**, which is not necessarily the tab
 *     that's playing. Background-tab audio is normal on YouTube.
 *  2. Screen off means no accessibility events, so it goes stale silently.
 *     Hence [FRESH_MS] — an old reading is discarded, never trusted.
 *  3. It's optional. With the service off this stays empty and identity falls
 *     back to notification hints (decision D6).
 */
object UrlEvidence {

	data class Evidence(
		val host: String?,
		val videoId: String?,
		/** True when the bar showed a `/shorts/` path — shorts are classified more strictly. */
		val isShort: Boolean = false,
		/** `list=` from the bar, when playback came from a playlist. */
		val playlistId: String? = null,
		/** Exactly what the address bar contained, for the log. */
		val raw: String,
		val atMillis: Long = System.currentTimeMillis(),
		/** Monotonic per-package URL/track generation assigned by [put]. */
		val generation: Long = 0,
	)

	/**
	 * How long a reading stays usable. Generous enough to survive a track
	 * starting and the screen going off a moment later; short enough that
	 * yesterday's tab can never explain today's playback.
	 */
	private const val FRESH_MS = 5L * 60 * 1000

	private val byPackage = ConcurrentHashMap<String, Evidence>()
	private val generationByPackage = ConcurrentHashMap<String, Long>()

	private val playlistByPackage = ConcurrentHashMap<String, Pair<String, Long>>()

	private const val PLAYLIST_FRESH_MS = 3L * 60 * 60 * 1000

	/** Set when the watcher service is connected, purely so the UI can say so. */
	@Volatile
	var watcherConnected: Boolean = false
		private set

	fun setConnected(value: Boolean) {
		watcherConnected = value
		if (!value) byPackage.clear()
	}

	fun put(packageName: String, evidence: Evidence): Evidence {
		val previous = byPackage[packageName]
		// Chromium collapses the omnibox to the bare host as the toolbar hides,
		// which used to overwrite a video id captured seconds earlier with "no
		// id" for the same page — losing evidence rather than gaining any. A
		// host-only reading of the same host is a redraw, not navigation.
		if (previous?.videoId != null && evidence.videoId == null &&
			previous.host == evidence.host
		) {
			return previous
		}
		val changed = previous == null ||
			previous.host != evidence.host ||
			previous.videoId != evidence.videoId ||
			previous.isShort != evidence.isShort ||
			previous.playlistId != evidence.playlistId
		val generation = if (changed) {
			generationByPackage.merge(packageName, 1L, Long::plus) ?: 1L
		} else {
			previous.generation
		}
		val stored = evidence.copy(generation = generation)
		byPackage[packageName] = stored
		stored.playlistId?.let { playlistByPackage[packageName] = it to stored.atMillis }
		if (previous == null ||
			previous.host != stored.host ||
			previous.videoId != stored.videoId ||
			previous.playlistId != stored.playlistId
		) {
			runCatching {
				EventLog.append(
					"url",
					"$packageName → host=${stored.host ?: "?"} " +
						"video=${stored.videoId ?: "—"} list=${stored.playlistId ?: "—"} " +
						"generation=$generation  " +
						"(\"${stored.raw}\")",
				)
			}
		}
		return stored
	}

	fun get(packageName: String, now: Long = System.currentTimeMillis()): Evidence? =
		byPackage[packageName]?.takeIf { now - it.atMillis <= FRESH_MS }

	/** The playlist playback is coming from, if the bar named one recently enough. */
	fun playlistId(packageName: String, now: Long = System.currentTimeMillis()): String? =
		playlistByPackage[packageName]
			?.takeIf { now - it.second <= PLAYLIST_FRESH_MS }
			?.first

	fun clearAll() {
		byPackage.clear()
		playlistByPackage.clear()
		generationByPackage.clear()
	}

	fun clear(packageName: String) {
		byPackage.remove(packageName)
		playlistByPackage.remove(packageName)
		generationByPackage.remove(packageName)
	}
}
