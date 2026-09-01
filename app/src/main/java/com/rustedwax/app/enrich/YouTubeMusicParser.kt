package com.rustedwax.app.enrich

import org.json.JSONObject

/**
 * Reads YouTube Music's `youtubei/v1/player` response. Pure — no network.
 *
 * ## Why this exists
 *
 * Adapted from the desktop extension's `getTrackInfoFromYoutubeMusic`
 * (`src/connectors/youtube.ts`), which uses it as its authoritative
 * "is this a music recording?" signal. It answers the question RustedWax was
 * asking MusicBrainz — but keyed by **video id** rather than by a parsed
 * artist/track string, which is why it succeeds where MusicBrainz can't.
 *
 * The 2026-07-29 sessions are full of `[musicbrainz] no match` on
 * Spanish-language and small-channel uploads. Verified against those same ids:
 *
 * | video | `musicVideoType` | author / title |
 * | --- | --- | --- |
 * | `GQwj_FRntp8` | `MUSIC_VIDEO_TYPE_ATV` | Daddy Yankee / Con Calma |
 * | `mgoLdQZl_pQ` | `MUSIC_VIDEO_TYPE_ATV` | KAROL G & Nicki Minaj / Tusa |
 * | `l69Cq38GgZ4` | `MUSIC_VIDEO_TYPE_OMV` | Elena Verrier / Metallica - Blackened (guitar cover) |
 * | `cq2xXbWGHu8` | *absent* | — (a football short) |
 * | `CYgQQqvwwsY` | *absent* | — (a shorts-feed ad) |
 *
 * ## Why it is worth a second request
 *
 * 10 KB against ~615 KB for the watch page, and it carries `lengthSeconds`,
 * `category` and `unlisted` as well. The watch-page fetch timed out on ~12% of
 * ids in field testing, and one of those timeouts is what let a stale video id
 * reach the chain with the wrong `url`. This is the cheap second source that
 * closes that hole.
 *
 * ## Field names differ from the watch page
 *
 * The music client returns `microformat.microformatDataRenderer` with an
 * `unlisted` key, where the watch page returns
 * `microformat.playerMicroformatRenderer` with `isUnlisted`. Same fact, two
 * spellings — a detail worth stating because it is exactly the kind of thing a
 * refactor would "tidy" into a bug.
 */
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

	/**
	 * What the music client knows about a video.
	 *
	 * @param musicVideoType null when YouTube Music has no record of it — which
	 * is a clean negative for "is this music", but see [recognisedAsMusic] for
	 * why it is only ever read as a positive.
	 */
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

		/**
		 * An Art Track — audio delivered by a distributor, not a filmed video.
		 *
		 * The only case where [author] and [title] are trustworthy credits: they
		 * come from the catalogue, so `Daddy Yankee / Con Calma` rather than
		 * whatever the uploader typed.
		 *
		 * The extension trusts credits from `MUSIC_VIDEO_TYPE_OMV` too. We
		 * deliberately do **not**: for `l69Cq38GgZ4` that yields author
		 * `Elena Verrier` (the channel) and title `Metallica - Blackened (guitar
		 * cover)`, which is strictly worse than what [TitleParser] already
		 * produces for the same video. An OMV is a filmed video whose "author"
		 * is just the channel.
		 */
		val isArtTrack: Boolean get() = musicVideoType == ART_TRACK_TYPE
	}

	/** @throws org.json.JSONException if the response isn't the shape we expect */
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
