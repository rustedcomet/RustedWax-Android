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

	/**
	 * [hostOf], for text read out of a browser's own address bar.
	 *
	 * Chromium renders the current page with its scheme elided, so Brave's
	 * omnibox reads `m.youtube.com/watch?v=...` for a page that really is
	 * `https://m.youtube.com/watch?v=...`. [hostOf] refuses that shape on
	 * purpose — a bare host followed by a path is not an origin when it arrives
	 * as arbitrary text — and refusing it here cost the browser evidence path
	 * every video id it was built to read.
	 *
	 * So the elision is restored, for this one field and nowhere else. The
	 * caller has already proved the value came from `$package:id/url_bar`, the
	 * browser's own statement of where it is, which no page can write to.
	 *
	 * Restoration is a whitelist, not a repair: everything before the first
	 * `/`, `?` or `#` must already be an exact bare DNS host with at most a
	 * numeric port. Anything carrying a scheme, userinfo, a backslash, a
	 * non-ASCII label, an empty or non-numeric port, or any other malformed
	 * authority fails that test and reaches [hostOf] untouched — which is what
	 * rejects it, exactly as it did before.
	 */
	fun hostOfAddressBar(raw: String?): String? {
		val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		return hostOf(withElidedSchemeRestored(value))
	}

	private fun withElidedSchemeRestored(value: String): String {
		val authorityEnd = value.indexOfFirst { it == '/' || it == '?' || it == '#' }
		// No delimiter is a bare host, which [hostOf] already reads; a leading
		// one is a path, a query or a protocol-relative reference, and none of
		// those name an origin. Neither is ours to rewrite.
		if (authorityEnd <= 0) return value
		val authority = value.substring(0, authorityEnd)
		return if (BARE_AUTHORITY.matches(authority)) "https://$value" else value
	}

	private val ACCEPTED_SCHEMES = setOf("http", "https")
	private const val BARE_HOST_PATTERN =
		"""[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+"""
	private val BARE_HOST = Regex(BARE_HOST_PATTERN)
	private val BARE_AUTHORITY = Regex("""$BARE_HOST_PATTERN(?::\d{1,5})?""")
}
