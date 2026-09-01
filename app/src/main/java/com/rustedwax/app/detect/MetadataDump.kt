package com.rustedwax.app.detect

import android.media.MediaMetadata
import com.rustedwax.core.MetadataFields

/**
 * Dumps every field a media session publishes.
 *
 * The Phase 0 question is "what does Brave actually put in a MediaMetadata,
 * and is it enough to build a HiveScrobblePayload from?" — so we dump the
 * whole surface, including keys we don't currently care about and any
 * non-standard keys the app invented. What's *absent* matters as much as
 * what's present, so unset known keys are reported explicitly rather than
 * skipped.
 */
object MetadataDump {

	/** Known text keys, in roughly the order of usefulness to us. */
	private val TEXT_KEYS = listOf(
		MediaMetadata.METADATA_KEY_TITLE,
		MediaMetadata.METADATA_KEY_ARTIST,
		MediaMetadata.METADATA_KEY_ALBUM,
		MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
		MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
		MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
		MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
		MediaMetadata.METADATA_KEY_ART_URI,
		MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
		MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
		MediaMetadata.METADATA_KEY_MEDIA_URI,
		MediaMetadata.METADATA_KEY_MEDIA_ID,
		MediaMetadata.METADATA_KEY_AUTHOR,
		MediaMetadata.METADATA_KEY_WRITER,
		MediaMetadata.METADATA_KEY_COMPOSER,
		MediaMetadata.METADATA_KEY_GENRE,
		MediaMetadata.METADATA_KEY_DATE,
	)

	/** Known numeric keys. 0 is indistinguishable from unset here — noted below. */
	private val LONG_KEYS = listOf(
		MediaMetadata.METADATA_KEY_DURATION,
		MediaMetadata.METADATA_KEY_YEAR,
		MediaMetadata.METADATA_KEY_TRACK_NUMBER,
		MediaMetadata.METADATA_KEY_NUM_TRACKS,
		MediaMetadata.METADATA_KEY_DISC_NUMBER,
	)

	private val BITMAP_KEYS = listOf(
		MediaMetadata.METADATA_KEY_ART,
		MediaMetadata.METADATA_KEY_ALBUM_ART,
		MediaMetadata.METADATA_KEY_DISPLAY_ICON,
	)

	/** Short key name for display: strips the `android.media.metadata.` prefix. */
	private fun short(key: String): String = key.substringAfterLast('.')

	private fun mmss(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

	/*
	 * The readers take `MetadataFields`, not `android.media.MediaMetadata`.
	 *
	 * The Android media path adapts once, at the call site, via `asFields()`.
	 * That is what lets the Phase 0/1 parity reference reach these *exact*
	 * bodies: if it had to reach a copy of the reader, a parity run would be
	 * comparing two readers as well as two state machines, and a difference in
	 * either would be reported as the other. See `core/MetadataFields.kt`.
	 *
	 * Deliberately not offered as an overload pair. `textOrNull(null, KEY)` was
	 * ambiguous between the two, which is a compile error in six existing tests
	 * and a silently different resolution anywhere it was not.
	 */

	fun textOrNull(md: MetadataFields?, key: String): String? =
		md?.getString(key)?.takeIf { it.isNotBlank() }

	fun longOrNull(md: MetadataFields?, key: String): Long? =
		md?.getLong(key)?.takeIf { it > 0L }

	/**
	 * Full human-readable dump. `present` keys first, then the known keys that
	 * came back empty, then anything unrecognised the app set itself.
	 */
	fun dump(md: MetadataFields?): List<String> {
		if (md == null) return listOf("<no metadata>")

		val out = mutableListOf<String>()
		val seen = mutableSetOf<String>()
		val missing = mutableListOf<String>()

		for (key in TEXT_KEYS) {
			seen += key
			val v = md.getString(key)
			if (v.isNullOrBlank()) missing += short(key) else out += "${short(key)} = \"$v\""
		}

		for (key in LONG_KEYS) {
			seen += key
			val v = md.getLong(key)
			when {
				v == 0L -> missing += short(key)
				// Duration is documented as milliseconds, but a browser
				// reporting seconds would look identical — print both readings
				// so a known-length video settles it (<redacted-private-path>, Q2).
				key == MediaMetadata.METADATA_KEY_DURATION ->
					out += "${short(key)} = $v  (${mmss(v / 1000)} if ms, " +
						"${mmss(v)} if seconds)"

				else -> out += "${short(key)} = $v"
			}
		}

		for (key in BITMAP_KEYS) {
			seen += key
			val bmp = md.bitmapDimensions(key)
			if (bmp == null) missing += short(key)
			else out += "${short(key)} = <bitmap ${bmp.first}x${bmp.second}>"
		}

		// Anything the app set that isn't in the standard table — OEM or
		// app-specific keys occasionally carry the page URL.
		val extras = md.keySet().filter { it !in seen }
		for (key in extras) {
			val asText = runCatching { md.getString(key) }.getOrNull()
			val asLong = runCatching { md.getLong(key) }.getOrNull()
			out += when {
				!asText.isNullOrBlank() -> "$key = \"$asText\"   (non-standard)"
				asLong != null && asLong != 0L -> "$key = $asLong   (non-standard)"
				else -> "$key = <present, unreadable as text/long>   (non-standard)"
			}
		}

		if (missing.isNotEmpty()) {
			out += "unset: ${missing.joinToString(", ")}"
		}
		return out
	}
}
