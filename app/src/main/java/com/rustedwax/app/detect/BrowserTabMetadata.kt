package com.rustedwax.app.detect

/**
 * What a browser publishes when the *page* has stopped describing a track.
 *
 * A browser MediaSession has two sources for its fields: the page's own
 * `navigator.mediaSession.metadata`, and — when the page has not set it, or has
 * torn it down between videos — the tab itself. The tab's answer is the
 * document title and the origin, and it arrives shaped exactly like a track:
 *
 *   TITLE  = "Bring Me The Horizon - Sleepwalking - YouTube"
 *   ARTIST = "m.youtube.com"
 *   unset: DURATION …                        pos=-1ms
 *
 * Measured 2026-08-11 in Brave: 129 bundles with the origin as the artist and
 * 43 with a document title, in one log. Reading either as a track cost three
 * different things:
 *
 *  - **Stolen listens.** "Sleepwalking - YouTube" became a *new* track and
 *    collected the 102 seconds the next video actually played (12:51:11 →
 *    12:52:54); "2Pac - Street Fame - YouTube" collected 22,425 s overnight;
 *    "YouTube" alone collected 580 s (12:55:16 → 13:04:57). Each then finalized
 *    against whatever id the address bar had latched by then.
 *  - **Refused scrobbles.** A finalized snapshot whose channel is
 *    `m.youtube.com` contradicts every real channel, so correct ids were thrown
 *    away — "candidate channel BMTHOfficialVEVO contradicts ended channel
 *    m.youtube.com". Twelve refusals on 2026-08-11 alone, including a
 *    187-second play of "Follow You" whose channel Brave simply never
 *    published.
 *  - **A wrong diagnosis.** Those refusals fed the "address bar has gone quiet"
 *    counter, so the app told the user to check Accessibility while the address
 *    bar was naming every video correctly.
 *
 * None of that was a disagreement about what played. It was one field saying
 * "I don't know" being read as an answer.
 *
 * Native packages are exempt throughout: the YouTube app's MediaSession has no
 * tab and no document title, and its subtitle is a real channel.
 */
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
