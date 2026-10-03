package com.rustedwax.app.snaps

import com.rustedwax.app.ui.snaps.SnapText
import com.rustedwax.hive.ImageHoster

/**
 * The phone-image formats a Snap or reply can carry (Issue 40D), and how each
 * is recognised.
 *
 * Decided by the file's own first bytes, never by its name or by the MIME type
 * a picker, clipboard or keyboard declared: a vendor picker can hand back a
 * PDF under an image filter, and a declared type is only a claim. HEIC/HEIF is
 * accepted as input and converted to JPEG before upload; everything else that
 * is recognised but not listed here is refused by name.
 */
enum class SnapImageFormat(val mimeType: String, val extension: String) {
	JPEG("image/jpeg", "jpg"),
	PNG("image/png", "png"),
	WEBP("image/webp", "webp"),
	GIF("image/gif", "gif"),
	;

	/** The file name sent to the host, which also names the format in the URL 40C reads. */
	val fileName: String get() = "image.$extension"
}

/** What a file's first bytes say it is. */
sealed interface SnapImageSniff {
	data class Supported(val format: SnapImageFormat) : SnapImageSniff
	/** HEIC/HEIF: accepted, but only after conversion to JPEG. */
	data object Heif : SnapImageSniff
	/** Recognised or not, this is not something 40D accepts. [kind] names it for the message. */
	data class Unsupported(val kind: String) : SnapImageSniff
}

object SnapImageSniffer {

	/** Enough to see every signature below, including an ISO-BMFF brand list. */
	const val HEADER_BYTES = 64

	fun sniff(header: ByteArray): SnapImageSniff {
		fun at(i: Int) = if (i < header.size) header[i].toInt() and 0xff else -1
		fun ascii(from: Int, text: String) =
			text.indices.all { at(from + it) == text[it].code }

		return when {
			at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> SnapImageSniff.Supported(SnapImageFormat.JPEG)
			at(0) == 0x89 && ascii(1, "PNG\r\n") && at(5) == 0x0A && at(6) == 0x1A && at(7) == 0x0A ->
				SnapImageSniff.Supported(SnapImageFormat.PNG)
			ascii(0, "GIF87a") || ascii(0, "GIF89a") -> SnapImageSniff.Supported(SnapImageFormat.GIF)
			ascii(0, "RIFF") && ascii(8, "WEBP") -> SnapImageSniff.Supported(SnapImageFormat.WEBP)
			ascii(4, "ftyp") -> isoBrand(header)
			ascii(0, "BM") -> SnapImageSniff.Unsupported("BMP")
			ascii(0, "II*\u0000") || ascii(0, "MM\u0000*") -> SnapImageSniff.Unsupported("TIFF")
			ascii(0, "%PDF") -> SnapImageSniff.Unsupported("PDF")
			ascii(0, "PK\u0003\u0004") -> SnapImageSniff.Unsupported("document or ZIP")
			at(0) == 0xD0 && at(1) == 0xCF && at(2) == 0x11 && at(3) == 0xE0 ->
				SnapImageSniff.Unsupported("document")
			looksLikeSvg(header) -> SnapImageSniff.Unsupported("SVG")
			else -> SnapImageSniff.Unsupported("file")
		}
	}

	/**
	 * An ISO-BMFF file (`ftyp` box): HEIC/HEIF, AVIF, or a video. AVIF shares
	 * HEIF's container and even its `mif1` brand, so any `avif`/`avis` brand —
	 * major or compatible — makes it AVIF, which 40D does not accept.
	 */
	private fun isoBrand(header: ByteArray): SnapImageSniff {
		val boxSize = ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
			((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
		val end = minOf(boxSize.takeIf { it >= 16 } ?: 16, header.size)
		val brands = buildList {
			if (header.size >= 12) add(String(header, 8, 4, Charsets.ISO_8859_1))
			var i = 16
			while (i + 4 <= end) {
				add(String(header, i, 4, Charsets.ISO_8859_1))
				i += 4
			}
		}
		return when {
			brands.any { it == "avif" || it == "avis" } -> SnapImageSniff.Unsupported("AVIF")
			brands.any { it in HEIF_BRANDS } -> SnapImageSniff.Heif
			else -> SnapImageSniff.Unsupported("file")
		}
	}

	private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1")

	private fun looksLikeSvg(header: ByteArray): Boolean {
		val text = String(header, Charsets.ISO_8859_1)
			// A UTF-8 byte-order mark, read as Latin-1, then any leading whitespace.
			.removePrefix("\u00EF\u00BB\u00BF")
			.trimStart(' ', '\t', '\r', '\n')
			.lowercase()
		return text.startsWith("<svg") || (text.startsWith("<?xml") && "<svg" in text)
	}
}

/**
 * How hosted images ride in a Snap or reply body, and how the 280-character
 * rule is kept off them.
 *
 * The block is one Markdown image per line, after the user's words and a blank
 * line:
 *
 * ```
 * {user text}
 *
 * ![](https://images.hive.blog/DQm…/image.jpg)
 * ![](https://images.hive.blog/DQm…/image.png)
 * ```
 *
 * On a root Snap it sits inside the authored part, before RustedWax's frozen
 * `youtu.be` + hashtag tail, so that tail — and scrobble.life's association by
 * it — is exactly what it was. Markdown image syntax is what other Hive
 * frontends render inline, and it is the form 40C's Comments and History
 * presentation already recognises and hides behind its previews.
 *
 * Only a trailing run of at most [MAX] lines that are each *exactly* a
 * RustedWax-hosted address ([ImageHoster.HOSTED_URL]) is read back as the block.
 * Anything looser stays the author's text and is counted as such, so the
 * exemption cannot be stretched to smuggle prose past the limit.
 */
object SnapAttachmentBlock {

	const val MAX = 4

	private val LINE = Regex("^!\\[]\\((https://images\\.hive\\.blog/[^)\\s]+)\\)$")

	fun line(url: String): String = "![]($url)"

	/**
	 * The body text for [userText] plus [urls]. Text-only when there are none,
	 * byte for byte. With no visible text the block stands alone — leftover
	 * spaces are not a caption.
	 */
	fun append(userText: String, urls: List<String>): String {
		if (urls.isEmpty()) return userText
		require(urls.size <= MAX) { "at most $MAX attachments" }
		require(urls.all(ImageHoster.HOSTED_URL::matches)) { "not a hosted attachment" }
		val block = urls.joinToString("\n", transform = ::line)
		return if (SnapText.hasVisible(userText)) userText + SEPARATOR + block else block
	}

	/** (the author's words, the hosted image URLs), the inverse of [append]. */
	fun split(text: String): Pair<String, List<String>> {
		val lines = text.split('\n')
		var first = lines.size
		while (first > 0 && lines.size - first < MAX) {
			val url = LINE.find(lines[first - 1])?.groupValues?.get(1) ?: break
			if (!ImageHoster.HOSTED_URL.matches(url)) break
			first--
		}
		if (first == lines.size) return text to emptyList()
		val urls = lines.subList(first, lines.size).map { LINE.find(it)!!.groupValues[1] }
		return when {
			first == 0 -> "" to urls
			// The block is always set off by exactly one blank line.
			first >= 2 && lines[first - 1].isEmpty() ->
				lines.subList(0, first - 1).joinToString("\n") to urls
			else -> text to emptyList()
		}
	}

	/** The composer's Send gate: words within the limit, and words or images. */
	fun canSend(text: String, attachments: Int): Boolean =
		SnapText.count(text) <= SnapText.LIMIT && (SnapText.hasVisible(text) || attachments > 0)

	private const val SEPARATOR = "\n\n"
}

/**
 * Which pasted or keyboard-committed items are images (Issue 40D).
 *
 * Text — including the text of a copied image *URL* — is never taken: it
 * pastes as words. An item is taken only when it carries a content URI and
 * either the clip declares an image type or the item has no text to paste
 * instead. What it really is, the intake then decides from its bytes, so a
 * non-image taken here is refused by name rather than pasted as a path.
 */
object SnapClipIntake {
	fun takesAsImage(uri: String?, text: CharSequence?, clipDeclaresImage: Boolean): Boolean = when {
		uri.isNullOrEmpty() -> false
		clipDeclaresImage -> true
		else -> text.isNullOrEmpty()
	}
}
