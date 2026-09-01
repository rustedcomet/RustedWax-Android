package com.rustedwax.app.enrich

import com.rustedwax.app.detect.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads a video's own metadata off its watch page.
 *
 * Chromium publishes no video id to the media session (PHASE0 Q3), so this can
 * only run once browser evidence or finalization recovery has obtained one.
 * Given an id, the watch page carries what the media session can't: YouTube's own **category** —
 * which answers `song` vs `video` far better than any title heuristic — plus
 * the description, where cover uploads routinely credit the original artist.
 *
 * ## Unsupported-page boundary
 *
 * `ytInitialPlayerResponse` is an undocumented blob in a page nobody promised
 * would keep its shape. The mitigations are not optional:
 *
 *  - Every extraction failure logs a **distinct** line, so breakage reads
 *    differently from "this video had nothing to add". Silent degradation here
 *    would look exactly like enrichment being switched off.
 *  - The parsing lives in [WatchPageParser], which is pure and unit-tested
 *    against a page fixture, so drift fails a test rather than a scrobble.
 *  - Everything is behind [MetadataResolver], so a Data-API implementation
 *    replaces it without touching the pipeline.
 *  - Nothing throws, nothing blocks past [TIMEOUT_MS], and a failure always
 *    falls back to the offline parse.
 *
 * ## What this sends
 *
 * One GET to youtube.com per new video, carrying the video id, from outside the
 * browser. Google already knows you watched it; the app is not telling them
 * anything new. It is still off-device traffic, so it stays a visible setting.
 */
class YouTubePageResolver(
	private val cache: FactsCache? = null,
) : MetadataResolver {

	override suspend fun resolve(videoId: String): VideoFacts? {
		cache?.get(videoId)?.let {
			EventLog.append("enrich", "cache hit for $videoId")
			return it
		}

		val music = resolveFromYouTubeMusic(videoId)

		val html = withTimeoutOrNull(TIMEOUT_MS) { fetch(videoId) }
		if (html == null) {
			EventLog.append("enrich", "fetch failed or timed out for $videoId")
			// Not a dead end any more. The music client alone still supplies the
			// length and classification facts. Its listed flag remains useful
			// metadata, but it is not watch-page proof and therefore cannot open
			// the lowered short-clip floor by itself.
			return music?.let { finish(fromMusicOnly(it), fellBackToMusic = true) }
		}

		val player = WatchPageParser.extractJson(html, PLAYER_RESPONSE)
		if (player == null) {
			// The distinctive failure: the page loaded but the blob we rely on
			// wasn't where it has always been. This is what markup drift looks
			// like, and it should be obvious in an exported log.
			EventLog.append(
				"enrich",
				"EXTRACTION FAILED — $PLAYER_RESPONSE not found for $videoId " +
					"(${html.length} bytes). YouTube markup may have changed.",
			)
			return music?.let { finish(fromMusicOnly(it), fellBackToMusic = true) }
		}

		val parsed = runCatching { WatchPageParser.parsePlayerResponse(videoId, player) }
			.getOrElse {
				EventLog.append(
					"enrich",
					"EXTRACTION FAILED — could not parse $PLAYER_RESPONSE for " +
						"$videoId: ${it.message}",
				)
				return music?.let { finish(fromMusicOnly(it), fellBackToMusic = true) }
			}

		// §6.3. Recorded, not acted on: the video still scrobbles exactly once.
		// The same fetch already has the page in hand, so this costs nothing, and
		// having the parser in the field is what makes per-chapter scrobbling a
		// measurement problem later rather than a parsing one.
		ChapterParser.parse(WatchPageParser.extractJson(html, INITIAL_DATA))
			.takeIf { it.isNotEmpty() }
			?.let { chapters ->
				EventLog.append(
					"chapters",
					"$videoId has ${chapters.size} chapters: " +
						chapters.joinToString(", ") { "${it.startMs / 1000}s ${it.title}" },
				)
			}

		return finish(merge(parsed, music), fellBackToMusic = false)
	}

	private fun merge(page: VideoFacts, music: YouTubeMusicParser.Result?): VideoFacts {
		if (music == null) return page
		return page.copy(
			category = page.category ?: music.category,
			lengthSeconds = page.lengthSeconds ?: music.lengthSeconds,
			isUnlisted = page.isUnlisted ?: music.unlisted,
			musicVideoType = music.musicVideoType,
			// Art Track credits are catalogue metadata, so they outrank a
			// description scrape. Only ATV — see Result.isArtTrack for why an OMV
			// is deliberately ignored here.
			originalArtist =
				if (music.isArtTrack) music.author ?: page.originalArtist else page.originalArtist,
			originalTitle =
				if (music.isArtTrack) music.title ?: page.originalTitle else page.originalTitle,
		)
	}

	/** Everything the music client can stand in for when the page is unavailable. */
	private fun fromMusicOnly(music: YouTubeMusicParser.Result) = VideoFacts(
		videoId = music.videoId,
		// Not `music.title` in general: on an OMV that is the uploader's video
		// title, which is what the session already has. On an Art Track it is a
		// catalogue track name, and the credits below carry it properly.
		title = if (music.isArtTrack) null else music.title,
		author = music.author,
		category = music.category,
		lengthSeconds = music.lengthSeconds,
		isUnlisted = music.unlisted,
		musicVideoType = music.musicVideoType,
		originalArtist = if (music.isArtTrack) music.author else null,
		originalTitle = if (music.isArtTrack) music.title else null,
	)

	private fun finish(facts: VideoFacts, fellBackToMusic: Boolean): VideoFacts {
		EventLog.append(
			"enrich",
			"${facts.videoId} → category=${facts.category ?: "?"} " +
				"originalArtist=${facts.originalArtist ?: "—"} " +
				// `listed` is what gates the short-clip floor, so it belongs in
				// the one line per video the log already writes. "?" means the
				// field was absent, which fails the gate just like unlisted does.
				"listed=${facts.isUnlisted?.let { if (it) "no (unlisted)" else "yes" } ?: "?"} " +
				"ytmusic=${facts.musicVideoType ?: "—"}" +
				if (fellBackToMusic) "  (watch page unavailable — music client only)" else "",
		)
		cache?.put(facts)
		return facts
	}

	/**
	 * The music client's verdict. Never throws, never blocks past its own
	 * timeout, and a failure is logged distinctly from "not in the catalogue" —
	 * the same D8 discipline the page fetch follows.
	 */
	private suspend fun resolveFromYouTubeMusic(videoId: String): YouTubeMusicParser.Result? {
		val body = withTimeoutOrNull(MUSIC_TIMEOUT_MS) { postToMusic(videoId) }
		if (body == null) {
			EventLog.append("enrich", "ytmusic lookup failed or timed out for $videoId")
			return null
		}
		return runCatching { YouTubeMusicParser.parse(videoId, body) }.getOrElse {
			EventLog.append(
				"enrich",
				"EXTRACTION FAILED — could not parse the ytmusic player response " +
					"for $videoId: ${it.message}",
			)
			null
		}
	}

	private suspend fun postToMusic(videoId: String): String? = withContext(Dispatchers.IO) {
		runCatching {
			val connection =
				URL(YouTubeMusicParser.ENDPOINT).openConnection() as HttpURLConnection
			try {
				connection.apply {
					requestMethod = "POST"
					connectTimeout = MUSIC_TIMEOUT_MS.toInt()
					readTimeout = MUSIC_TIMEOUT_MS.toInt()
					doOutput = true
					setRequestProperty("Content-Type", "application/json")
					setRequestProperty("User-Agent", USER_AGENT)
				}
				connection.outputStream.use {
					it.write(YouTubeMusicParser.requestBody(videoId).toByteArray())
				}
				if (connection.responseCode !in 200..299) return@withContext null
				connection.inputStream.bufferedReader().readText()
			} finally {
				connection.disconnect()
			}
		}.getOrNull()
	}

	private suspend fun fetch(videoId: String): String? = withContext(Dispatchers.IO) {
		runCatching {
			val connection = URL(WATCH_URL + videoId).openConnection() as HttpURLConnection
			try {
				connection.apply {
					requestMethod = "GET"
					connectTimeout = TIMEOUT_MS.toInt()
					readTimeout = TIMEOUT_MS.toInt()
					instanceFollowRedirects = true
					// A desktop UA gets the full page; the mobile shell serves a
					// different document that doesn't carry the blob.
					setRequestProperty("User-Agent", USER_AGENT)
					setRequestProperty("Accept-Language", "en-US,en;q=0.9")
				}
				connection.inputStream.bufferedReader().readText()
			} finally {
				connection.disconnect()
			}
		}.getOrNull()
	}

	private companion object {
		const val WATCH_URL = "https://www.youtube.com/watch?v="
		const val PLAYER_RESPONSE = "ytInitialPlayerResponse"

		/** Where the chapter markers live; the player response does not carry them. */
		const val INITIAL_DATA = "ytInitialData"
		const val TIMEOUT_MS = 4_000L

		/**
		 * Shorter than the page's, because the response is ~10 KB rather than
		 * ~615 KB and measured at ~0.3 s. A slow music lookup must not delay the
		 * page fetch that follows it.
		 */
		const val MUSIC_TIMEOUT_MS = 2_500L
		const val USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
				"(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
	}
}
