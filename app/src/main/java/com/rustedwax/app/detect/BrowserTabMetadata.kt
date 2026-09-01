package com.rustedwax.app.detect

object BrowserTabMetadata {

	/**
	 * The site's own name, as Chromium's document title renders it.
	 *
	 * Both the bare name and the " - YouTube" suffix are the same thing — a page
	 * title. A watch page sets its `<title>` to "<video> - YouTube" and clears it
	 * to "YouTube" between videos, and in every one of the 43 measured cases the
	 * suffixed form *followed* the clean one for a track already being tracked.
	 * None ever started one.
	 */
	private val SITE_NAMES = listOf("youtube", "youtube music")

	/** Chromium's document-title separator, not a title's own hyphen. */
	private const val SUFFIX_SEPARATOR = " - "

	/**
	 * The title names the tab rather than a track.
	 *
	 * Deliberately a whole-value test plus an exact suffix test, never a
	 * "contains". "Bring Me The Horizon - Youtopia" has to survive it, and does.
	 */
	fun isTabTitle(packageName: String, title: String?): Boolean {
		if (SourceRegistry.packageProvesSource(packageName)) return false
		val trimmed = title?.trim()?.lowercase().orEmpty()
		if (trimmed.isEmpty()) return false
		if (YouTubeProbe.isYouTubeHost(trimmed)) return true
		return SITE_NAMES.any {
			trimmed == it || trimmed.endsWith("$SUFFIX_SEPARATOR$it")
		}
	}

	/** The title, or null when it belongs to the tab. */
	fun titleOrNull(packageName: String, title: String?): String? =
		title.takeUnless { isTabTitle(packageName, it) }

	/**
	 * The channel, or null when the browser filled the field with the origin.
	 *
	 * Absence, not a different channel — which is the whole point. A track whose
	 * uploader Brave never published still has a title and a duration to be
	 * corroborated by, and [VideoIdentityCorroborator] already skips a channel
	 * comparison it has no left-hand side for. What it cannot survive is being
	 * handed a hostname and told it is the artist.
	 *
	 * Only a host is removed. The real "YouTube" channel keeps its name.
	 */
	fun artistOrNull(packageName: String, artist: String?): String? {
		if (SourceRegistry.packageProvesSource(packageName)) return artist
		val trimmed = artist?.trim() ?: return artist
		return artist.takeUnless { YouTubeProbe.isYouTubeHost(trimmed) }
	}
}
