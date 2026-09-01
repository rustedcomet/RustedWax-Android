package com.rustedwax.app.enrich

import org.json.JSONObject

object YouTubeMusicParser {

	/**
	 * The request body. A `WEB_REMIX` client identity is mandatory — the endpoint
	 * answers 400 without one, and the specific version string is what the
	 * extension found to work.
	 */
	fun requestBody(videoId: String): String =
		JSONObject()
			.put(
				"context",
				JSONObject().put(
					"client",
					JSONObject()
						.put("clientName", CLIENT_NAME)
						.put("clientVersion", CLIENT_VERSION),
				),
			)
			.put("captionParams", JSONObject())
			.put("videoId", videoId)
			.toString()

	data class Result(
		val videoId: String,
		val musicVideoType: String? = null,
		/** The channel, except on an Art Track where it is the real artist. */
		val author: String? = null,
		val title: String? = null,
		val lengthSeconds: Long? = null,
		val category: String? = null,
		/** Null when absent; see [VideoFacts.isUnlisted] for why that matters. */
		val unlisted: Boolean? = null,
	) {
		/**
		 * YouTube Music has this video in its catalogue.
		 *
		 * **Positive-only, and that is deliberate** — copied from the extension's
		 * reasoning verbatim, because it is right: indie, live and personal-channel
		 * uploads are absent from the catalogue, so absence is not evidence
		 * against music. It rescues songs the heuristics would miss; it never
		 * demotes one.
		 *
		 * `MUSIC_VIDEO_TYPE_PODCAST_EPISODE` is catalogue membership but not
		 * music evidence. The v0.8.7 device run received that type for an
		 * Education-category timer and classified it as a song, which then
		 * unlocked the song-only second transaction.
		 */
		val recognisedAsMusic: Boolean
			get() = isRecognisedMusicType(musicVideoType)

		val isArtTrack: Boolean get() = musicVideoType == ART_TRACK_TYPE
	}

	fun parse(videoId: String, json: String): Result {
		val root = JSONObject(json)
		val details = root.optJSONObject("videoDetails")
		val micro = root.optJSONObject("microformat")
			?.optJSONObject("microformatDataRenderer")

		return Result(
			videoId = videoId,
			musicVideoType = details?.optString("musicVideoType")?.ifBlank { null },
			author = details?.optString("author")?.ifBlank { null },
			title = details?.optString("title")?.ifBlank { null },
			lengthSeconds = details?.optString("lengthSeconds")
				?.toLongOrNull()?.takeIf { it > 0 },
			category = micro?.optString("category")?.ifBlank { null },
			unlisted = micro?.takeIf { it.has("unlisted") }?.optBoolean("unlisted"),
		)
	}

	const val ENDPOINT = "https://music.youtube.com/youtubei/v1/player"

	internal fun isRecognisedMusicType(type: String?): Boolean =
		type?.startsWith(MUSIC_VIDEO_PREFIX) == true &&
			type != PODCAST_EPISODE_TYPE

	private const val CLIENT_NAME = "WEB_REMIX"
	private const val CLIENT_VERSION = "1.20221212.01.00"
	private const val MUSIC_VIDEO_PREFIX = "MUSIC_VIDEO_"
	private const val ART_TRACK_TYPE = "MUSIC_VIDEO_TYPE_ATV"
	private const val PODCAST_EPISODE_TYPE = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"
}
