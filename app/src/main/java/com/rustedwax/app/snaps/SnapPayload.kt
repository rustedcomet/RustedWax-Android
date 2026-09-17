package com.rustedwax.app.snaps

import com.rustedwax.hive.HiveScrobblePayload

/**
 * The media anchor for a Snap: the History row's verified YouTube identity.
 *
 * Deliberately *only* the video id. A History row has already been through
 * identity resolution, classification and finalization — re-deriving an artist,
 * a title or a media kind here would be second-guessing a decision the engine
 * already made, and would let the Snap path refuse a row History accepted.
 */
data class SnapMedia(val videoId: String)

/** Exactly what gets signed: the on-chain body and the metadata string. */
data class SnapPayload(
	val body: String,
	val jsonMetadata: String,
)

/**
 * Builds the on-chain body and metadata for a root History Snap.
 *
 * The v1 body is frozen at three parts and nothing else:
 *
 * ```
 * {user text}
 *
 * https://youtu.be/{videoId}
 *
 * #scrobblelife #scrobble #rustedwax
 * ```
 *
 * **Eligibility is History membership.** If a row is in History it has already
 * been judged; this builder does not re-open artist, title, performer, kind or
 * any song/video/music classification, and cannot refuse a Snap for lacking
 * them. The only thing it requires is a YouTube identity good enough to build
 * the canonical URL from, because the URL is the media anchor and a Snap
 * without it anchors to nothing.
 *
 * **The user's text is published byte-for-byte.** It is not trimmed, rewritten,
 * normalized or deduplicated here — leading and trailing whitespace, embedded
 * newlines and Unicode all reach the chain exactly as typed. Whether a draft is
 * acceptable at all is the composer's question, already answered before
 * anything gets here; this builder's only job is to append, never to edit. An
 * earlier revision trimmed, which quietly made RustedWax the author of part of
 * what it published.
 *
 * The generated tail — the blank line, the canonical URL, the blank line, the
 * hashtag line — is appended **unconditionally**. It is not deduplicated against
 * anything the user typed. An earlier revision suppressed the URL or a hashtag
 * when it thought the user had already supplied one, which made the final body
 * depend on a fuzzy reading of free text: a `watch?v=` link, a bare id or an
 * uppercase `#Scrobble` each changed what RustedWax appended, so the on-chain
 * shape was no longer fixed. It is fixed now — every Snap body is exactly the
 * user's text followed by exactly [suffix], and a user who types a link simply
 * has their own copy plus ours.
 *
 * An earlier revision also inserted an `Artist — Title` header and a `zingit`
 * association block carrying artist and title, to make Snaps land on
 * scrobble.life's per-track media page. Both are gone: they made the Snap body
 * assert a classification RustedWax had no business re-asserting, and the
 * association they bought is not part of the v1 product contract.
 */
object SnapPayloadBuilder {

	/**
	 * The hashtags every RustedWax Snap carries.
	 *
	 * These are the specification's own list. They describe *where the Snap came
	 * from*, not what the media is — there is deliberately no genre or media-kind
	 * tag among them, because RustedWax does not classify at Snap time.
	 */
	val REQUIRED_TAGS = listOf("scrobblelife", "scrobble", "rustedwax")

	/** The canonical short form, and the exact string the body must contain. */
	fun canonicalUrl(videoId: String): String = "https://youtu.be/$videoId"

	fun build(userText: String, media: SnapMedia): SnapPayload = SnapPayload(
		body = buildBody(userText, media),
		jsonMetadata = buildMetadata(),
	)

	private fun buildBody(userText: String, media: SnapMedia): String =
		userText + SEPARATOR + canonicalUrl(media.videoId) + SEPARATOR + TAG_LINE

	/**
	 * The smallest metadata a Snap needs, and nothing more.
	 *
	 * Hive itself requires nothing here — `json_metadata` is an opaque string to
	 * the chain. What remains is the two fields every real Snap in the ecosystem
	 * carries and that Snap frontends actually read:
	 *
	 *  - `app`, which identifies the client honestly as RustedWax;
	 *  - `tags`, which is how a Snap is indexed and found, mirroring the body's
	 *    hashtags exactly so the two can never disagree.
	 *
	 * Nothing describing the media is included. No artist, no title, no kind, no
	 * ISRC, no association block — RustedWax does not restate a classification at
	 * Snap time, and v1 does not depend on media-page association.
	 *
	 * Built as text rather than through a JSON object because this exact string
	 * is signed *and* broadcast, so it must not depend on a map's iteration order.
	 */
	private fun buildMetadata(): String {
		val q = HiveScrobblePayload::quoteJson
		val tags = REQUIRED_TAGS.joinToString(",") { q(it) }
		return """{"app":${q(HiveScrobblePayload.APP_NAME)},"tags":[$tags]}"""
	}

	/**
	 * Why this Snap must not be broadcast, or null when it is fit to sign.
	 *
	 * Checks the *finished* body rather than trusting that [build] assembled it,
	 * so a future edit cannot quietly ship Snaps with no anchor. Note what is
	 * **not** checked: artist, title, media kind, or anything else History
	 * already settled.
	 */
	fun problem(payload: SnapPayload, media: SnapMedia): String? {
		mediaProblem(media)?.let { return it }
		if (!payload.body.endsWith(suffix(media))) {
			return "refusing to publish a Snap without its canonical YouTube link"
		}
		return null
	}

	/** The exact generated tail every Snap body ends with. */
	fun suffix(media: SnapMedia): String =
		SEPARATOR + canonicalUrl(media.videoId) + SEPARATOR + TAG_LINE

	/**
	 * Whether this row has a YouTube identity a canonical URL can be built from.
	 *
	 * The single eligibility rule, and it is about the *anchor*, not the media.
	 * The character check is what stops a malformed id turning into a URL that
	 * points somewhere else entirely.
	 */
	fun mediaProblem(media: SnapMedia): String? = when {
		media.videoId.isBlank() -> "this History row has no video to link"
		!VIDEO_ID.matches(media.videoId) -> "this History row's video id isn't usable as a link"
		else -> null
	}

	/** The exact hashtag line, lowercase, in the frozen order. */
	val TAG_LINE: String = REQUIRED_TAGS.joinToString(" ") { "#$it" }

	private const val SEPARATOR = "\n\n"

	/** YouTube's own id alphabet. Anything else cannot be pasted into a URL safely. */
	private val VIDEO_ID = Regex("^[A-Za-z0-9_-]+$")
}
