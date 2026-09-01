package com.rustedwax.app.enrich

import android.content.Context
import com.rustedwax.app.BuildConfig
import com.rustedwax.app.detect.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class MusicBrainzVerifier(context: Context) {

	/** The verdict for one artist/track pair. Cached, including negatives. */
	data class Match(
		val found: Boolean,
		/** MusicBrainz's canonical spelling, when found. */
		val artist: String? = null,
		val title: String? = null,
	)

	private val dir = File(context.filesDir, "musicbrainz").apply { mkdirs() }
	private val memory = ConcurrentHashMap<String, Match>()

	/** Cache-only lookup for the UI. Never touches the network. */
	fun cached(artist: String, track: String): Match? {
		val key = keyFor(artist, track)
		memory[key]?.let { return it }
		val file = File(dir, "$key.json")
		if (!file.exists()) return null
		return runCatching {
			val o = JSONObject(file.readText())
			Match(
				found = o.getBoolean("found"),
				artist = if (o.isNull("artist")) null else o.getString("artist"),
				title = if (o.isNull("title")) null else o.getString("title"),
			)
		}.getOrNull()?.also { memory[key] = it }
	}

	/**
	 * Full lookup. Returns the cached or fresh verdict, or null when the
	 * network couldn't answer — null is "unknown", not "no match", and is
	 * deliberately not cached so a later attempt can still succeed.
	 */
	suspend fun verify(artist: String, track: String): Match? {
		if (artist.isBlank() || track.isBlank()) return null
		if (artist.length > 100 || track.length > 150) return null
		cached(artist, track)?.let { return it }

		// No outer timeout: it used to wrap the rate-limit queue wait as well,
		// so during rapid shorts browsing most lookups timed out before their
		// HTTP request ever started. The connection's own connect/read
		// timeouts bound the actual network time.
		val body = fetch(artist, track)
		if (body == null) {
			EventLog.append("musicbrainz", "lookup failed for \"$artist\" / \"$track\"")
			return null
		}

		val match = runCatching { matchFrom(body, artist, track) }.getOrElse {
			EventLog.append("musicbrainz", "parse failed: ${it.message}")
			return null
		}
		EventLog.append(
			"musicbrainz",
			if (match.found) {
				"confirmed: ${match.artist} — ${match.title}"
			} else {
				"no match for \"$artist\" / \"$track\""
			},
		)
		store(artist, track, match)
		return match
	}

	private suspend fun fetch(artist: String, track: String): String? =
		withContext(Dispatchers.IO) {
			// ≤1 request per second, per MusicBrainz's rate rules. The mutex
			// also serializes concurrent finalizes so bursts queue up rather
			// than hammer.
			rateLimit.withLock {
				val since = System.currentTimeMillis() - lastRequestAt
				if (since < MIN_INTERVAL_MS) delay(MIN_INTERVAL_MS - since)
				lastRequestAt = System.currentTimeMillis()
			}
			runCatching {
				val query = """artist:"${sanitize(artist)}" AND recording:"${sanitize(track)}""""
				val url = URL(
					"https://musicbrainz.org/ws/2/recording?query=" +
						URLEncoder.encode(query, "UTF-8") + "&fmt=json&limit=8",
				)
				val connection = url.openConnection() as HttpURLConnection
				try {
					connection.apply {
						requestMethod = "GET"
						connectTimeout = TIMEOUT_MS.toInt()
						readTimeout = TIMEOUT_MS.toInt()
						setRequestProperty("User-Agent", USER_AGENT)
						setRequestProperty("Accept", "application/json")
					}
					connection.inputStream.bufferedReader().readText()
				} finally {
					connection.disconnect()
				}
			}.getOrNull()
		}

	private fun store(artist: String, track: String, match: Match) {
		val key = keyFor(artist, track)
		memory[key] = match
		runCatching {
			File(dir, "$key.json").writeText(
				JSONObject()
					.put("found", match.found)
					.putOpt("artist", match.artist)
					.putOpt("title", match.title)
					.toString(),
			)
		}
	}

	private fun keyFor(artist: String, track: String): String {
		val digest = MessageDigest.getInstance("SHA-1")
			.digest("${normalize(artist)}|${normalize(track)}".toByteArray())
		return digest.joinToString("") { "%02x".format(it) }
	}

	/** Quotes would end the Lucene phrase early; drop them from the query. */
	private fun sanitize(value: String) = value.replace("\"", " ").trim()

	companion object {
		private const val TIMEOUT_MS = 4_000L
		private const val MIN_INTERVAL_MS = 1_100L
		private const val MIN_SCORE = 80

		/**
		 * MusicBrainz requires a User-Agent carrying the application name, its
		 * version, and **contact information** — a URL or email they can reach
		 * if the client misbehaves; generic or uninformative agents get
		 * blocked. The previous value described the app instead of naming a
		 * contact, and hardcoded "0.5" while the app moved on to 0.5.4.
		 *
		 * Format per their guidelines: `Name/Version ( contact )`.
		 */
		private val USER_AGENT =
			"RustedWax/${BuildConfig.VERSION_NAME} " +
				"( https://github.com/rustedcomet/rustedwax )"

		private val rateLimit = Mutex()

		@Volatile
		private var lastRequestAt = 0L

		/**
		 * Pure so it's testable against a fixture. A recording counts only
		 * when its title **and** one of its credited artists both equal the
		 * candidates after normalization, and the search score is healthy —
		 * title-only matches would claim every clip named like some song.
		 */
		fun matchFrom(body: String, artist: String, track: String): Match {
			val root = JSONObject(body)
			val recordings = root.optJSONArray("recordings") ?: return Match(found = false)
			val wantArtist = normalize(artist)
			val wantTrack = normalize(track)

			for (i in 0 until recordings.length()) {
				val rec = recordings.optJSONObject(i) ?: continue
				if (rec.optInt("score") < MIN_SCORE) continue
				val title = rec.optString("title")
				if (normalize(title) != wantTrack) continue

				val credits = rec.optJSONArray("artist-credit") ?: continue
				for (j in 0 until credits.length()) {
					val credit = credits.optJSONObject(j) ?: continue
					val name = credit.optString("name")
						.ifBlank { credit.optJSONObject("artist")?.optString("name").orEmpty() }
					if (normalize(name) == wantArtist) {
						return Match(found = true, artist = name, title = title)
					}
				}
			}
			return Match(found = false)
		}

		/** Case, whitespace and stray quotes must not defeat equality. */
		private fun normalize(value: String): String =
			value.lowercase()
				.replace(Regex("""["'’‘“”]"""), "")
				.replace(Regex("""\s+"""), " ")
				.trim()
	}
}
