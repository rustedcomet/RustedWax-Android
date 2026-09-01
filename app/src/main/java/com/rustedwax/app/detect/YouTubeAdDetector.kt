package com.rustedwax.app.detect

/**
 * Recognises YouTube's own visible ad labels and controls.
 *
 * The desktop connector has direct DOM access and rejects playback while the
 * page has `.ad-showing`. RustedWax cannot inspect CSS state from another app;
 * the closest literal Android evidence is text YouTube exposes in Chrome's
 * accessibility tree. This intentionally matches exact UI labels, never brand
 * names, channel names, or arbitrary uses of "ad" inside a title.
 */
object YouTubeAdDetector {

	private val shortPath = Regex("""(?:^|/)shorts/([A-Za-z0-9_-]{11})(?:[?&#/]|$)""")

	private val exactLabels = setOf(
		// English
		"ad",
		"ads",
		"sponsored",
		"promoted",
		"skip ad",
		"skip ads",
		"visit advertiser",
		"why this ad",
		// Spanish
		"anuncio",
		"anuncios",
		"publicidad",
		"patrocinado",
		"patrocinada",
		"promocionado",
		"promocionada",
		"omitir anuncio",
		"saltar anuncio",
		"visitar anunciante",
		"por qué este anuncio",
		// Portuguese
		"anúncio",
		"anúncios",
		"publicidade",
		"pular anúncio",
		"visitar anunciante",
		"por que este anúncio",
	)

	private val countedLabel = Regex(
		"""^(?:ad|anuncio|anúncio)\s+\d+\s+(?:of|de)\s+\d+""" +
			"""(?:\s*[·•-]\s*\d{1,2}:\d{2})?$""",
		RegexOption.IGNORE_CASE,
	)

	/**
	 * @return the original trimmed label when it is explicit ad UI, otherwise
	 * null. Keeping the literal label makes field logs auditable.
	 */
	fun signalFor(label: CharSequence?): String? {
		val literal = label?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		matches(literal)?.let { return it }
		// Shorts ad cards publish the whole card as one multi-line content
		// description — measured 2026-08-05:
		// "Dola: Smart AI Assistant\nAd\n4.4 stars\nFREE" — where the ad label is
		// its own line rather than its own node. Whole-literal matching missed
		// every one of these, so an ad refused on the *owner handle* gate (ads
		// have no channel) and was logged as an identity failure rather than as
		// the ad it plainly is.
		//
		// Still line-*exact*: a line has to be nothing but the label. This keeps
		// the guarantee in the class comment — brand names, channel names and
		// arbitrary uses of "ad" inside a title are untouched, because none of
		// those occupy a line by themselves.
		if (literal.none { it == '\n' || it == '\r' }) return null
		return literal.split('\n', '\r')
			.firstNotNullOfOrNull { line -> line.takeIf(String::isNotBlank)?.let(::matches) }
	}

	/** Exact match of one whole label, normalized for case, spacing and punctuation. */
	private fun matches(literal: String): String? {
		val trimmed = literal.trim().takeIf { it.isNotEmpty() } ?: return null
		val normalized = trimmed
			.lowercase()
			.replace(Regex("""\s+"""), " ")
			.trim(' ', '?', '¿', '.', ':')
		return trimmed.takeIf { normalized in exactLabels || countedLabel.matches(normalized) }
	}

	/** Exact-label scanning is allowed on any visible YouTube host shape. */
	fun shouldScanHost(host: String?): Boolean = YouTubeProbe.isYouTubeHost(host)

	/**
	 * The id an ad label may be bound to in this exact accessibility snapshot.
	 *
	 * Ordinary [UrlEvidence] deliberately survives Chromium collapsing the bar
	 * to a bare host. Ad evidence may not: the next Short could already be on
	 * screen, so reusing that prior id would reject the wrong item.
	 */
	fun videoIdInSameShortSnapshot(host: String?, rawUrlBar: String?): String? {
		if (!YouTubeProbe.isYouTubeHost(host)) return null
		val raw = rawUrlBar?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		return shortPath.find(raw)?.groupValues?.get(1)
	}
}
