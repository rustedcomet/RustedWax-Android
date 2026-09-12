package com.rustedwax.app.detect

import java.net.URI
import java.util.Locale

/** Parses only an HTTP(S) URL or an exact bare DNS host from browser-owned UI. */
internal object BrowserOrigin {
	fun hostOf(raw: String?): String? {
		val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		if (value.any { it.isWhitespace() || it.isISOControl() }) return null

		val uri = if (BARE_HOST.matches(value)) {
			runCatching { URI("https://$value") }.getOrNull()
		} else {
			runCatching { URI(value) }.getOrNull()
		} ?: return null

		if (uri.isOpaque || uri.scheme?.lowercase(Locale.ROOT) !in ACCEPTED_SCHEMES) return null
		if (uri.rawUserInfo != null) return null
		if (uri.rawAuthority?.endsWith(':') == true) return null
		val host = uri.host?.lowercase(Locale.ROOT) ?: return null

		// Preserve the established canonical spelling without letting a generic
		// "www." removal turn a deeper, untrusted host into an allowlisted one.
		return if (host == "www.youtube.com") "youtube.com" else host
	}

	private val ACCEPTED_SCHEMES = setOf("http", "https")
	private val BARE_HOST = Regex(
		"""[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+""",
	)
}
