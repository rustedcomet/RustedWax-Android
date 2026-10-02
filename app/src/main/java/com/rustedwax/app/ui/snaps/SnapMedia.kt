package com.rustedwax.app.ui.snaps

import java.net.URI
import java.net.URLDecoder
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reject oversized source canvases before a decoder allocates them. */
internal object SnapMediaDecode {
	private const val MAX_SOURCE_PIXELS = 4L * 1024 * 1024
	private const val MAX_WIDTH = 720
	private const val MAX_HEIGHT = 1440

	fun target(width: Int, height: Int): Pair<Int, Int>? {
		if (width <= 0 || height <= 0 || width.toLong() * height > MAX_SOURCE_PIXELS) return null
		val scale = minOf(1.0, MAX_WIDTH.toDouble() / width, MAX_HEIGHT.toDouble() / height)
		return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
	}

	fun sample(width: Int, height: Int): Int? {
		val (w, h) = target(width, height) ?: return null
		var sample = 1
		while ((width.toLong() + sample - 1) / sample > w ||
			(height.toLong() + sample - 1) / sample > h) sample *= 2
		return sample
	}
}

/**
 * Which redirects a preview fetch may follow: the proxy's own, and no others.
 *
 * `images.hive.blog` answers every `/640x0/<url>` and `/0x0/<url>` request with
 * a 301 to its own `/p/…` form (observed 2026-10-01 on every image tried), so
 * refusing all redirects refuses every preview. What must never happen is the
 * device being sent on to the image's origin, so a Location is followed only
 * when it stays on the proxy's exact origin — and anything else is not
 * requested at all.
 */
internal object SnapMediaRedirect {
	const val MAX_HOPS = 3
	private const val PROXY_HOST = "images.hive.blog"

	/** The next URL to request, or null when [location] would leave the proxy. */
	fun next(current: String, location: String?): String? {
		val raw = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		val resolved = runCatching { URI(current).resolve(raw) }.getOrNull() ?: return null
		if (resolved.scheme?.lowercase() != "https") return null
		if (resolved.host?.lowercase() != PROXY_HOST) return null
		if (resolved.port != -1 && resolved.port != 443) return null
		if (resolved.rawUserInfo != null) return null
		return resolved.toString()
	}
}

/** Byte and elapsed-time bounds also apply to slow, chunked responses. */
internal object SnapMediaRead {
	const val MAX_DOWNLOAD_MS = 30_000L

	suspend fun read(input: InputStream, limit: Int, startedNanos: Long): ByteArray {
		val out = ByteArrayOutputStream()
		val buffer = ByteArray(16 * 1024)
		fun checkDeadline() {
			if ((System.nanoTime() - startedNanos) / 1_000_000 >= MAX_DOWNLOAD_MS) {
				throw SocketTimeoutException("Media download deadline exceeded")
			}
		}
		while (out.size() <= limit) {
			currentCoroutineContext().ensureActive()
			checkDeadline()
			val read = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
			currentCoroutineContext().ensureActive()
			checkDeadline()
			if (read < 0) break
			if (read > 0) out.write(buffer, 0, read)
		}
		return out.toByteArray()
	}
}

/**
 * One piece of media a Snap or reply already links to, as RustedWax will
 * preview it.
 *
 * Found in the comment's own words and nowhere else: a preview is a way of
 * drawing a link the author wrote, never a fetch the author did not ask for.
 * The text stays exactly as written beside it — a preview is an addition, so a
 * preview that fails to load costs the reader nothing they had before.
 */
sealed interface SnapMediaRef {
	/** The URL exactly as the author wrote it. What every fallback opens. */
	val source: String

	/**
	 * A direct image link. [animated] is a GIF, which is decoded as an
	 * animation where the platform can.
	 */
	data class Image(override val source: String, val animated: Boolean) : SnapMediaRef {
		/**
		 * Where the bytes are actually fetched from: Hive's own image proxy,
		 * the host every Hive frontend already routes post images through and
		 * the one RustedWax already asks for avatars. So a preview adds no new
		 * party who learns that this device is reading this thread.
		 *
		 * Stills are asked for at 640 px wide, which is both smaller to fetch
		 * and enough for a phone-width card. A GIF is asked for unresized:
		 * the proxy's resize keeps only the first frame (checked 2026-10-01).
		 */
		val fetchUrl: String
			get() = "$PROXY/${if (animated) "0x0" else "640x0"}/$source"
	}

	/** A YouTube video. Previewed as a thumbnail; played only by YouTube itself. */
	data class YouTube(
		override val source: String,
		val videoId: String,
		val isShort: Boolean,
	) : SnapMediaRef {
		/** The exact video, in the form the YouTube app opens directly. */
		val openUrl: String
			get() = if (isShort) {
				"https://www.youtube.com/shorts/$videoId"
			} else {
				"https://www.youtube.com/watch?v=$videoId"
			}
	}

	companion object {
		const val PROXY = "https://images.hive.blog"
	}
}

/**
 * Finds the media in a comment's text. Pure, so the rules are pinned by JVM
 * tests rather than by eye.
 *
 * Deliberately small. What is recognised is what the Snaps feed actually
 * carries (surveyed over 1,743 `peak.snaps` comments on 2026-10-01): direct
 * `.png`/`.jpg`/`.jpeg`/`.webp` images — almost all on `images.hive.blog`,
 * `files.peakd.com` and `i.ecency.com` — direct `.gif` files from GIF pickers
 * such as `static.klipy.com` and `media*.giphy.com`, and YouTube watch,
 * `youtu.be` and Shorts links. Everything else — a page that merely *contains*
 * a picture, a 3Speak embed, an IPFS hash with no extension — stays a link.
 */
object SnapMediaParser {

	/** At most this many previews under one comment. The rest stay links. */
	const val MAX_PER_COMMENT = 3

	/** Longer than any real media URL; past this it is not one worth fetching. */
	private const val MAX_URL_LENGTH = 2048

	/**
	 * A web link in running text. Stops at whitespace, quotes, angle brackets
	 * and the brackets Markdown wraps a link in, so `![gif](https://x/a.gif)`
	 * yields the URL alone.
	 */
	private val LINK = Regex("""https?://[^\s<>"'()\[\]]+""", RegexOption.IGNORE_CASE)

	/** Sentence punctuation that ends a link in prose rather than belonging to it. */
	private const val TRAILING = ".,!?;:"

	private val STILL = setOf("png", "jpg", "jpeg", "webp")
	private const val GIF = "gif"

	private val VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")
	private val YOUTUBE_HOSTS = setOf("youtube.com", "m.youtube.com", "music.youtube.com")

	/** Every web link in [text], with where it sits, for drawing it tappable. */
	fun links(text: String): List<Pair<IntRange, String>> =
		LINK.findAll(text).mapNotNull { m ->
			val url = m.value.trimEnd { it in TRAILING }
			if (url.length <= "https://".length) return@mapNotNull null
			(m.range.first until m.range.first + url.length) to url
		}.toList()

	/** The previews for [text], in the order written, duplicates dropped. */
	fun find(text: String): List<SnapMediaRef> {
		val seen = mutableSetOf<String>()
		val out = mutableListOf<SnapMediaRef>()
		for ((_, url) in links(text)) {
			val ref = recognise(url) ?: continue
			if (!seen.add(SnapMediaText.identity(ref))) continue
			out += ref
			if (out.size == MAX_PER_COMMENT) break
		}
		return out
	}

	/** One URL, or null when it is not something RustedWax previews. */
	fun recognise(url: String): SnapMediaRef? {
		if (url.length > MAX_URL_LENGTH) return null
		val uri = runCatching { URI(url) }.getOrNull() ?: return null
		val scheme = uri.scheme?.lowercase() ?: return null
		val host = uri.host?.lowercase()?.takeIf { it.contains('.') } ?: return null
		if (uri.userInfo != null) return null
		youtube(url, uri, scheme, host)?.let { return it }
		// Fetched, so https only: a cleartext image link stays a link.
		if (scheme != "https") return null
		val name = uri.rawPath.orEmpty().substringAfterLast('/')
		val ext = name.substringAfterLast('.', "").lowercase()
		return when {
			name.isEmpty() || ext.isEmpty() -> null
			ext == GIF -> SnapMediaRef.Image(url, animated = true)
			ext in STILL -> SnapMediaRef.Image(url, animated = false)
			else -> null
		}
	}

	private fun youtube(url: String, uri: URI, scheme: String, host: String): SnapMediaRef.YouTube? {
		if (scheme != "https" && scheme != "http") return null
		val bare = host.removePrefix("www.")
		val path = uri.rawPath.orEmpty()
		val segments = path.split('/').filter { it.isNotEmpty() }
		val (id, short) = when {
			bare == "youtu.be" -> segments.firstOrNull() to false
			bare in YOUTUBE_HOSTS && path == "/watch" -> queryValue(uri.rawQuery, "v") to false
			bare in YOUTUBE_HOSTS && segments.firstOrNull() == "shorts" -> segments.getOrNull(1) to true
			bare in YOUTUBE_HOSTS && segments.firstOrNull() == "live" -> segments.getOrNull(1) to false
			else -> return null
		}
		val videoId = id?.takeIf(VIDEO_ID::matches) ?: return null
		return SnapMediaRef.YouTube(url, videoId, short)
	}

	/** The single value of [name]; null when absent or given twice. */
	private fun queryValue(rawQuery: String?, name: String): String? {
		val values = rawQuery.orEmpty().split('&')
			.filter { it.substringBefore('=') == name }
			.map { runCatching { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }.getOrNull() }
		return values.singleOrNull()
	}
}

/**
 * What a comment looks like once its media is drawn as media: the words to
 * print, with the links RustedWax already shows as a preview taken out.
 *
 * Presentation only. The body on chain, the text Edit starts from and every
 * identity are untouched — this is computed from them each time it is drawn,
 * so editing a link out (or back in) redraws both the words and the preview.
 */
data class SnapMediaDisplay(
	/** The comment's words minus previewed media links. Empty when nothing else was written. */
	val text: String,
	/** What is drawn as a preview, in the order written. */
	val media: List<SnapMediaRef>,
)

/**
 * The compact form a History card gives a root Snap that carries an image:
 * its words with image/GIF links taken out, one still thumbnail, and no reply
 * preview under it — the card's Comments (N) already says replies exist.
 */
data class SnapHistoryMedia(
	val text: String,
	/** The first image or GIF the Snap links, or null when it links none. */
	val thumbnail: SnapMediaRef.Image?,
) {
	/** The reply strip stays exactly as before on every card without an image. */
	val showsReplyPreview: Boolean get() = thumbnail == null

	/**
	 * Fetched as a still on purpose: the proxy's resize keeps only a GIF's
	 * first frame, which is exactly what History draws — nothing animates there.
	 */
	val thumbnailStill: SnapMediaRef.Image? get() = thumbnail?.copy(animated = false)
}

object SnapMediaText {

	/** The Comments sheet: every link drawn as a preview leaves the text. */
	fun display(text: String): SnapMediaDisplay {
		val media = SnapMediaParser.find(text)
		val shown = media.mapTo(mutableSetOf(), ::identity)
		return SnapMediaDisplay(strip(text) { identity(it) in shown }, media)
	}

	/**
	 * A History card. [userText] is the root's authored words — RustedWax's own
	 * appended YouTube link and tags are already outside it, so they can never
	 * become the thumbnail.
	 */
	fun history(userText: String): SnapHistoryMedia {
		val first = SnapMediaParser.links(userText).firstNotNullOfOrNull { (_, url) ->
			SnapMediaParser.recognise(url) as? SnapMediaRef.Image
		} ?: return SnapHistoryMedia(userText, null)
		return SnapHistoryMedia(strip(userText) { it is SnapMediaRef.Image }, first)
	}

	/**
	 * One reply line in a History card's preview strip. Image/GIF links leave
	 * it under the same rule as [history]; the strip draws no media, so a reply
	 * that was only an image says which kind it was instead of printing its URL.
	 * Presentation only: the stored and on-chain reply text is untouched.
	 */
	fun previewLine(text: String): String {
		val shown = history(text)
		val image = shown.thumbnail ?: return text
		return when {
			shown.text.isNotEmpty() -> shown.text
			image.animated -> "GIF"
			else -> "Image"
		}
	}

	internal fun identity(ref: SnapMediaRef): String = when (ref) {
		is SnapMediaRef.YouTube -> "yt:${ref.videoId}"
		is SnapMediaRef.Image -> "img:${ref.source}"
	}

	/** Markdown image syntax around a link: `![alt](` before it, `)` after. */
	private val MD_IMAGE_OPEN = Regex("""!\[[^\]\n]*]\(\s*$""")

	/**
	 * [text] with every link [hide] accepts removed. Everything else is kept
	 * character for character; only the space a removed link leaves behind is
	 * closed up, and a line holding nothing but removed links goes with them.
	 */
	private fun strip(text: String, hide: (SnapMediaRef) -> Boolean): String {
		val cuts = SnapMediaParser.links(text).mapNotNull { (range, url) ->
			val ref = SnapMediaParser.recognise(url) ?: return@mapNotNull null
			if (!hide(ref)) return@mapNotNull null
			var start = range.first
			var end = range.last + 1
			val open = MD_IMAGE_OPEN.find(text.substring(0, start))
			if (open != null) {
				val close = text.indexOf(')', end)
				if (close >= 0 && text.substring(end, close).isBlank()) {
					start = open.range.first
					end = close + 1
				}
			}
			start until end
		}
		if (cuts.isEmpty()) return text
		val out = StringBuilder()
		var at = 0
		for (cut in cuts) {
			if (cut.first < at) continue
			out.append(text, at, cut.first).append(CUT)
			at = cut.last + 1
		}
		out.append(text, at, text.length)
		// A line that only held media goes entirely. On any other line the gap
		// a link leaves closes up — "see URL now" → "see now", "see URL." →
		// "see." — and every other character stays where the author put it.
		val lines = out.toString().split('\n').mapNotNull { line ->
			if (!line.contains(CUT)) return@mapNotNull line
			if (line.replace(CUT.toString(), "").isBlank()) return@mapNotNull null
			GAP.replace(line) { m ->
				val atEdge = m.range.first == 0 || m.range.last == line.length - 1
				val beforePunct = line.getOrNull(m.range.last + 1)?.let { it in ".,!?;:" } == true
				if (atEdge || beforePunct || m.value.first() == CUT && m.value.last() == CUT) "" else " "
			}
		}
		return lines.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n")
	}

	/** One or more cuts in a row, with the spaces around and between them. */
	private val GAP = Regex("""[ \t]*\u0000(?:[ \t]*\u0000)*[ \t]*""")

	/** Marks where a link was cut, so only lines emptied by a cut are dropped. */
	private const val CUT = '\u0000'
}

/**
 * How a YouTube preview is opened: the YouTube app first, then whatever the
 * device opens YouTube links with, and nothing inside RustedWax. Pure data so
 * the order is testable; the Activity is what actually starts it.
 */
object SnapMediaOpen {
	const val YOUTUBE_PACKAGE = "com.google.android.youtube"

	/** (package or null for "any handler", URL), tried in order. */
	fun youtube(ref: SnapMediaRef.YouTube): List<Pair<String?, String>> = listOf(
		YOUTUBE_PACKAGE to ref.openUrl,
		null to ref.openUrl,
	)

	/** Any other link: whatever handles it on the device. Web links only. */
	fun link(url: String): List<Pair<String?, String>> {
		val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
		return if (scheme == "https" || scheme == "http") listOf(null to url) else emptyList()
	}
}
