package com.rustedwax.hive

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class HiveScrobblePayload(
	val kind: String = KIND_VIDEO,
	val title: String,
	val timestamp: String,
	val artist: String? = null,
	val album: String? = null,
	/** Formatted `m:ss`, as the extension writes it. */
	val duration: String? = null,
	val percentPlayed: Int? = null,
	val platform: String? = null,
	val url: String? = null,
	val app: String = APP_NAME,
) {

	val hasRequiredYouTubeUrl: Boolean
		get() = canonicalUrlPatternFor(platform)?.matches(url.orEmpty()) ?: true

	/**
	 * Compact JSON, key order matching the extension's assignment order so
	 * on-chain payloads from phone and desktop look identical.
	 */
	fun toJson(): String {
		// JSONObject does not preserve insertion order, so build the string
		// directly — byte-identical output is worth the small amount of manual
		// work, and it keeps the dhive golden vector meaningful.
		val sb = StringBuilder("{")
		fun field(name: String, value: String, quote: Boolean = true) {
			if (sb.length > 1) sb.append(',')
			sb.append(quoteJson(name)).append(':')
			if (quote) sb.append(quoteJson(value)) else sb.append(value)
		}
		field("app", app)
		field("kind", kind)
		field("title", title)
		field("timestamp", timestamp)
		artist?.let { field("artist", it) }
		album?.let { field("album", it) }
		duration?.let { field("duration", it) }
		percentPlayed?.let { field("percent_played", it.toString(), quote = false) }
		platform?.let { field("platform", it) }
		url?.let { field("url", it) }
		sb.append('}')
		return sb.toString()
	}

	companion object {
		/**
		 * Applies the same hyperlink invariant to serialized retry-queue entries,
		 * including entries created by an older build that allowed a missing URL.
		 */
		fun serializedHasRequiredYouTubeUrl(json: String): Boolean = runCatching {
			val payload = JSONObject(json)
			val pattern = canonicalUrlPatternFor(payload.optString("platform"))
				?: return@runCatching true
			pattern.matches(payload.optString("url"))
		}.getOrDefault(false)

		/**
		 * The canonical link shape a platform's entries must have, or null when
		 * the platform declares none.
		 *
		 * Lives here rather than on `SourceProfile` because the queue re-checks
		 * serialized payloads that have no session behind them any more — all it
		 * has is the `platform` string it was written with.
		 */
		fun canonicalUrlPatternFor(platform: String?): Regex? =
			if (platform == PLATFORM_YOUTUBE) YOUTUBE_WATCH_URL else null

		fun quoteJson(value: String): String {
			val sb = StringBuilder(value.length + 2)
			sb.append('"')
			for (c in value) {
				when (c) {
					'"' -> sb.append("\\\"")
					'\\' -> sb.append("\\\\")
					'\n' -> sb.append("\\n")
					'\r' -> sb.append("\\r")
					'\t' -> sb.append("\\t")
					'\b' -> sb.append("\\b")
					'' -> sb.append("\\f")
					else -> if (c < ' ') {
						sb.append("\\u%04x".format(c.code))
					} else {
						sb.append(c)
					}
				}
			}
			sb.append('"')
			return sb.toString()
		}

		const val CUSTOM_JSON_ID = "hive_scrobble_ai"

		const val APP_NAME = "rustedwax/$BUILD_VERSION"

		const val KIND_SONG = "song"
		const val KIND_VIDEO = "video"
		const val KIND_PODCAST = "podcast"

		/**
		 * §6.2. Classification only: `imdb_id`, `wikipedia_url`, `series_*` and
		 * `poster_url` exist in the upstream type and stay absent here, because
		 * the extension fills them from a DOM and a Wikidata lookup that this app
		 * has no equivalent for. The defect being fixed is films filed as songs,
		 * and a `kind` fixes that on its own.
		 */
		const val KIND_MOVIE = "movie"
		const val KIND_EPISODE = "episode"
		const val PLATFORM_YOUTUBE = "youtube"
		private val YOUTUBE_WATCH_URL =
			Regex("""^https://www\.youtube\.com/watch\?v=[A-Za-z0-9_-]{11}$""")

		/** ISO-8601 UTC, matching `new Date(...).toISOString()` in the extension. */
		fun isoTimestamp(epochSeconds: Long): String {
			val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
			fmt.timeZone = TimeZone.getTimeZone("UTC")
			return fmt.format(Date(epochSeconds * 1000))
		}

		/** `m:ss`, matching the extension's duration formatting. */
		fun formatDuration(totalSeconds: Long): String =
			"%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
	}
}
