package com.rustedwax.app.enrich

import com.rustedwax.core.TextNormalizer
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Locale

/** Exact YouTube owner-handle evidence; punctuation and underscores are identity. */
object OwnerHandle {

	/**
	 * Letters, marks, digits and the three punctuation characters YouTube allows.
	 *
	 * Deliberately **not** ASCII-only. Measured 2026-08-07: the footer of a
	 * Short read `Go to channel @eduardaarebouçass`, the `ç` fell outside the
	 * old `[A-Za-z0-9._-]`, and the handle was refused — which since v0.9.10
	 * means the whole listen is refused, because the handle is the one mandatory
	 * field. Every creator whose handle is not spelled in ASCII was invisible.
	 */
	private val HANDLE = Regex("""^[\p{L}\p{M}\p{N}._-]{3,30}$""")

	/**
	 * Normalizes only the presentation allowed by the native-Short contract:
	 * trim, remove at most one leading `@`, and compare case-insensitively.
	 *
	 * Composed to NFC first, so the same handle read off the accessibility tree
	 * and off a watch page compares equal whether the source spelled `ç` as one
	 * character or as `c` plus a cedilla. NFC only merges sequences that *are*
	 * the same character; unlike NFKC it never folds two distinct ones together,
	 * which an identity field cannot afford.
	 */
	fun normalize(value: String?): String? {
		val trimmed = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
		val withoutAt = if (trimmed.startsWith('@')) trimmed.drop(1) else trimmed
		// Identity form from the shared normalizer — §3.6. Same NFC as before, now
		// stated in one place, and with zero-width characters stripped first: a
		// handle scraped off the accessibility tree can carry them, and an
		// invisible character in a handle is a refused listen.
		val composed = TextNormalizer.identity(withoutAt)
		if (composed.startsWith('@') || !HANDLE.matches(composed)) return null
		return composed.lowercase(Locale.ROOT)
	}

	fun canonical(value: String?): String? {
		val normalized = normalize(value) ?: return null
		return "@$normalized"
	}

	fun matches(first: String?, second: String?): Boolean {
		val left = normalize(first) ?: return false
		return left == normalize(second)
	}

	/**
	 * Parses only a canonical, single-segment YouTube owner profile URL.
	 * Host suffix confusion, credentials, ports, query/fragment data and
	 * multi-segment paths all fail closed.
	 *
	 * A handle spelled outside ASCII reaches this function in one of two shapes,
	 * and until v0.9.13 both were refused — which made the parser fix above
	 * pointless on its own, because a Short is only scrobbled once its handle is
	 * corroborated against the video's own page. The literal form is
	 * percent-encoded before parsing, because `java.net.URI` rejects a raw `ç`
	 * outright; the encoded form is decoded again *after* the structure has been
	 * settled, and still has to be a legal handle — so a `%2F` cannot smuggle in
	 * a second path segment.
	 */
	fun fromOwnerProfileUrl(value: String?): String? {
		val raw = percentEncodeNonAscii(value?.trim().orEmpty())
		val uri = runCatching { URI(raw) }.getOrNull() ?: return null
		if (uri.scheme != "http" && uri.scheme != "https") return null
		if (uri.rawUserInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null) {
			return null
		}
		val host = uri.host?.lowercase(Locale.ROOT) ?: return null
		if (host != "youtube.com" && host != "www.youtube.com") return null
		val rawPath = uri.rawPath ?: return null
		val segments = rawPath.split('/').filter(String::isNotEmpty)
		if (segments.size != 1) return null
		val segment = decodePercentEscapes(segments.single()) ?: return null
		if (!segment.startsWith('@')) return null
		return canonical(segment)
	}

	/** Leaves every ASCII character — and so every structural one — untouched. */
	private fun percentEncodeNonAscii(value: String): String {
		if (value.none { it.code > 0x7F }) return value
		val out = StringBuilder(value.length)
		var index = 0
		while (index < value.length) {
			val codePoint = value.codePointAt(index)
			val width = Character.charCount(codePoint)
			if (codePoint <= 0x7F) {
				out.append(value[index])
			} else {
				value.substring(index, index + width).toByteArray(Charsets.UTF_8).forEach { byte ->
					out.append('%').append(String.format(Locale.ROOT, "%02X", byte.toInt() and 0xFF))
				}
			}
			index += width
		}
		return out.toString()
	}

	/**
	 * Decodes only the escapes that stand for a character outside ASCII.
	 *
	 * An ASCII escape — `%40` for `@`, `%2F` for a path separator — is still
	 * refused outright, exactly as it was before non-ASCII handles were read
	 * here, so nothing structural can be spelled sideways. The only thing this
	 * admits is a letter that has no ASCII spelling in the first place.
	 */
	private fun decodePercentEscapes(value: String): String? {
		if ('%' !in value) return value
		val bytes = ByteArrayOutputStream(value.length)
		var index = 0
		while (index < value.length) {
			val char = value[index]
			if (char != '%') {
				if (char.code > 0x7F) return null
				bytes.write(char.code)
				index++
				continue
			}
			if (index + 3 > value.length) return null
			val byte = value.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
			if (byte < 0x80) return null
			bytes.write(byte)
			index += 3
		}
		return String(bytes.toByteArray(), Charsets.UTF_8)
	}
}
