package com.rustedwax.hive

import org.json.JSONObject

/**
 * The public envelope a private scrobble broadcasts, mirroring the extension's
 * `maybeEncryptPayload`.
 *
 * ```
 * { app, kind, timestamp, private: <base64 blob>, v: 1 }
 * ```
 *
 * `app`, `kind` and `timestamp` stay in the clear. That is not an oversight:
 * an indexer has to be able to count a listen and place it in time without
 * being able to read it, or a private scrobble is indistinguishable from noise
 * and gets filtered out of the very history it belongs to. Everything that says
 * *what* was played — title, artist, album, url, duration, percent — goes in
 * the blob.
 */
object PrivateScrobble {

	/** Which toggle governs a kind, matching the extension's `privacyFlagForKind`. */
	enum class Category { MUSIC, VIDEOS, MOVIES_TV, PODCASTS }

	fun categoryFor(kind: String): Category = when (kind) {
		HiveScrobblePayload.KIND_VIDEO -> Category.VIDEOS
		HiveScrobblePayload.KIND_MOVIE, HiveScrobblePayload.KIND_EPISODE -> Category.MOVIES_TV
		HiveScrobblePayload.KIND_PODCAST -> Category.PODCASTS
		// `song` and anything unrecognised: upstream's `default` arm. A kind this
		// build has never heard of is treated as music rather than as public,
		// which is the safe direction to be wrong in.
		else -> Category.MUSIC
	}

	/**
	 * The envelope JSON, or null when the secret is unavailable.
	 *
	 * **Null means do not broadcast.** It never means "send it in the clear".
	 * The user asked for this listen to be private; publishing it plainly to an
	 * immutable ledger because a key could not be loaded would be the single
	 * worst thing this feature could do, and it cannot be taken back. Upstream
	 * treats null as a hard skip and so must every caller here.
	 */
	fun envelope(payload: HiveScrobblePayload, secret: ByteArray?): String? {
		if (secret == null || secret.size != SECRET_BYTES) return null
		val blob = runCatching {
			PrivacyCipher.encrypt(privateFieldsJson(payload), secret)
		}.getOrNull() ?: return null

		val sb = StringBuilder("{")
		fun field(name: String, value: String, quote: Boolean = true) {
			if (sb.length > 1) sb.append(',')
			sb.append(HiveScrobblePayload.quoteJson(name)).append(':')
			if (quote) sb.append(HiveScrobblePayload.quoteJson(value)) else sb.append(value)
		}
		field("app", payload.app)
		field("kind", payload.kind)
		field("timestamp", payload.timestamp)
		field("private", blob)
		field("v", ENVELOPE_VERSION.toString(), quote = false)
		sb.append('}')
		return sb.toString()
	}

	/**
	 * Everything the envelope does not carry, as the JSON that gets encrypted.
	 *
	 * Built with `JSONObject` rather than by hand because, unlike the public
	 * payload, these bytes are never compared against the extension's — they are
	 * only ever read back by a decrypt, on either side, as a JSON object. Key
	 * order inside the ciphertext is not a contract, and pretending it is would
	 * be a claim nothing tests.
	 */
	internal fun privateFieldsJson(payload: HiveScrobblePayload): String {
		val json = JSONObject()
		json.put("title", payload.title)
		payload.artist?.let { json.put("artist", it) }
		payload.album?.let { json.put("album", it) }
		payload.duration?.let { json.put("duration", it) }
		payload.percentPlayed?.let { json.put("percent_played", it) }
		payload.platform?.let { json.put("platform", it) }
		payload.url?.let { json.put("url", it) }
		return json.toString()
	}

	/** Read a blob back — the other half of "the reverse must also work". */
	fun decryptFields(blob: String, secret: ByteArray): JSONObject =
		JSONObject(PrivacyCipher.decrypt(blob, secret))

	const val ENVELOPE_VERSION = 1
	private const val SECRET_BYTES = 32
}
