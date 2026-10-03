package com.rustedwax.app.snaps

import com.rustedwax.app.ui.snaps.SnapMediaRef
import com.rustedwax.app.ui.snaps.SnapMediaText
import com.rustedwax.app.ui.snaps.SnapText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 40D's pure rules: which bytes are an acceptable image, how hosted
 * images ride in a body, that they never spend the 280 characters, and that
 * the resulting body is what 40C already renders, edits and refreshes.
 */
class SnapAttachmentsTest {

	private fun hosted(n: Int, ext: String = "jpg"): String {
		// A syntactically real content hash: "Qm" + 44 base58 characters.
		val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
		val tail = (0 until 44).map { alphabet[(it * 7 + n * 13) % alphabet.length] }.joinToString("")
		return "https://images.hive.blog/DQm$tail/image.$ext"
	}

	private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
	private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

	private fun ftyp(major: String, vararg compatible: String): ByteArray {
		val body = ascii("ftyp$major") + bytes(0, 0, 0, 0) + compatible.fold(ByteArray(0)) { a, b -> a + ascii(b) }
		val size = body.size + 4
		return bytes(0, 0, 0, size) + body
	}

	// ── formats ────────────────────────────────────────────────────────

	@Test
	fun `JPEG PNG WebP and GIF are recognised from their bytes`() {
		assertEquals(
			SnapImageSniff.Supported(SnapImageFormat.JPEG),
			SnapImageSniffer.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)),
		)
		assertEquals(
			SnapImageSniff.Supported(SnapImageFormat.PNG),
			SnapImageSniffer.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)),
		)
		assertEquals(
			SnapImageSniff.Supported(SnapImageFormat.WEBP),
			SnapImageSniffer.sniff(ascii("RIFF") + bytes(1, 2, 3, 4) + ascii("WEBPVP8 ")),
		)
		assertEquals(SnapImageSniff.Supported(SnapImageFormat.GIF), SnapImageSniffer.sniff(ascii("GIF89a....")))
		assertEquals(SnapImageSniff.Supported(SnapImageFormat.GIF), SnapImageSniffer.sniff(ascii("GIF87a....")))
	}

	@Test
	fun `HEIC and HEIF are accepted for conversion, AVIF is not`() {
		assertEquals(SnapImageSniff.Heif, SnapImageSniffer.sniff(ftyp("heic", "mif1", "heic")))
		assertEquals(SnapImageSniff.Heif, SnapImageSniffer.sniff(ftyp("mif1", "heic")))
		assertEquals(SnapImageSniff.Heif, SnapImageSniffer.sniff(ftyp("heix", "mif1")))
		// AVIF shares HEIF's container and its mif1 brand.
		assertEquals(SnapImageSniff.Unsupported("AVIF"), SnapImageSniffer.sniff(ftyp("avif", "mif1", "miaf")))
		assertEquals(SnapImageSniff.Unsupported("AVIF"), SnapImageSniffer.sniff(ftyp("mif1", "avif")))
		// An MP4 is not an image.
		assertEquals(SnapImageSniff.Unsupported("file"), SnapImageSniffer.sniff(ftyp("isom", "mp41")))
	}

	@Test
	fun `BMP TIFF SVG PDF Word ZIP and unknown bytes are refused by name`() {
		assertEquals(SnapImageSniff.Unsupported("BMP"), SnapImageSniffer.sniff(ascii("BM6\u0000\u0000")))
		assertEquals(SnapImageSniff.Unsupported("TIFF"), SnapImageSniffer.sniff(ascii("II*\u0000\u0008")))
		assertEquals(SnapImageSniff.Unsupported("TIFF"), SnapImageSniffer.sniff(ascii("MM\u0000*\u0000")))
		assertEquals(SnapImageSniff.Unsupported("PDF"), SnapImageSniffer.sniff(ascii("%PDF-1.7")))
		assertEquals(SnapImageSniff.Unsupported("document or ZIP"), SnapImageSniffer.sniff(ascii("PK\u0003\u0004..")))
		assertEquals(
			SnapImageSniff.Unsupported("document"),
			SnapImageSniffer.sniff(bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1)),
		)
		assertEquals(SnapImageSniff.Unsupported("SVG"), SnapImageSniffer.sniff(ascii("<svg xmlns=")))
		assertEquals(SnapImageSniff.Unsupported("SVG"), SnapImageSniffer.sniff(ascii("<?xml version=\"1.0\"?><svg")))
		assertEquals(SnapImageSniff.Unsupported("file"), SnapImageSniffer.sniff(ascii("hello world")))
		assertEquals(SnapImageSniff.Unsupported("file"), SnapImageSniffer.sniff(ByteArray(0)))
		// Truncated signatures are not images.
		assertEquals(SnapImageSniff.Unsupported("file"), SnapImageSniffer.sniff(bytes(0xFF, 0xD8)))
	}

	@Test
	fun `the host file name names the format 40C recognises`() {
		SnapImageFormat.entries.forEach {
			val url = "https://images.hive.blog/DQmXyz/${it.fileName}"
			val ref = com.rustedwax.app.ui.snaps.SnapMediaParser.recognise(url) as SnapMediaRef.Image
			assertEquals(it == SnapImageFormat.GIF, ref.animated)
		}
	}

	// ── the body block ─────────────────────────────────────────────────

	@Test
	fun `no images leaves the words byte for byte`() {
		assertEquals("  hi  \n", SnapAttachmentBlock.append("  hi  \n", emptyList()))
		assertEquals("  hi  \n" to emptyList<String>(), SnapAttachmentBlock.split("  hi  \n"))
	}

	@Test
	fun `one and four images append and split back exactly`() {
		val one = listOf(hosted(1))
		assertEquals("hello\n\n![](${one[0]})", SnapAttachmentBlock.append("hello", one))
		assertEquals("hello" to one, SnapAttachmentBlock.split(SnapAttachmentBlock.append("hello", one)))

		val four = (1..4).map { hosted(it, listOf("jpg", "png", "webp", "gif")[it - 1]) }
		val body = SnapAttachmentBlock.append("line one\nline two", four)
		assertEquals("line one\nline two" to four, SnapAttachmentBlock.split(body))
	}

	@Test
	fun `images alone stand without a caption`() {
		val urls = listOf(hosted(1), hosted(2))
		assertEquals("![](${urls[0]})\n![](${urls[1]})", SnapAttachmentBlock.append("", urls))
		assertEquals("![](${urls[0]})", SnapAttachmentBlock.append("   ", urls.take(1)))
		assertEquals("" to urls, SnapAttachmentBlock.split(SnapAttachmentBlock.append("", urls)))
	}

	@Test(expected = IllegalArgumentException::class)
	fun `a fifth image is refused by the builder`() {
		SnapAttachmentBlock.append("x", (1..5).map(::hosted))
	}

	@Test(expected = IllegalArgumentException::class)
	fun `only hosted addresses can enter the block`() {
		SnapAttachmentBlock.append("x", listOf("https://example.com/a.jpg"))
	}

	@Test
	fun `the exemption cannot be stretched to anything that is not exactly the block`() {
		val url = hosted(1)
		// Not set off by a blank line: the author's own text, counted.
		assertEquals("a\n![]($url)" to emptyList<String>(), SnapAttachmentBlock.split("a\n![]($url)"))
		// Another host, or alt text, or a bare URL: the author's text.
		listOf(
			"a\n\n![](https://files.peakd.com/x/image.jpg)",
			"a\n\n![alt]($url)",
			"a\n\n$url",
			"a\n\n![]($url) more",
		).forEach { assertEquals(it to emptyList<String>(), SnapAttachmentBlock.split(it)) }
		// At most four lines are ever the block; a fifth stays text.
		val five = "a\n\n" + (1..5).joinToString("\n") { "![](${hosted(it)})" }
		assertTrue(SnapAttachmentBlock.split(five).second.isEmpty())
	}

	// ── 280 + images, at every boundary that enforces it ───────────────

	@Test
	fun `280 characters plus four images is a valid Snap, reply and edit`() {
		val words = "x".repeat(SnapText.LIMIT)
		val composed = SnapAttachmentBlock.append(words, (1..4).map(::hosted))
		assertNull(SnapPayloadBuilder.textProblem(composed))
		assertNull(SnapReplyPayloadBuilder.problem(SnapReplyPayloadBuilder.build(composed)))
		assertNull(SnapEditor.textProblem(composed))
		assertTrue(SnapAttachmentBlock.canSend(words, 4))
	}

	@Test
	fun `281 characters is still over the limit with images`() {
		val words = "x".repeat(SnapText.LIMIT + 1)
		val composed = SnapAttachmentBlock.append(words, listOf(hosted(1)))
		assertNotNull(SnapPayloadBuilder.textProblem(composed))
		assertNotNull(SnapReplyPayloadBuilder.problem(SnapReplyPayloadBuilder.build(composed)))
		assertNotNull(SnapEditor.textProblem(composed))
		assertFalse(SnapAttachmentBlock.canSend(words, 1))
	}

	@Test
	fun `an image-only Snap or reply is valid and an empty composer is not`() {
		val imageOnly = SnapAttachmentBlock.append("", listOf(hosted(1)))
		assertNull(SnapPayloadBuilder.textProblem(imageOnly))
		assertNull(SnapReplyPayloadBuilder.problem(SnapReplyPayloadBuilder.build(imageOnly)))
		assertTrue(SnapAttachmentBlock.canSend("", 1))
		assertTrue(SnapAttachmentBlock.canSend("  \n", 1))

		assertFalse(SnapAttachmentBlock.canSend("", 0))
		assertFalse(SnapAttachmentBlock.canSend(" \n\t", 0))
		assertNotNull(SnapPayloadBuilder.textProblem(""))
		assertNotNull(SnapReplyPayloadBuilder.problem(SnapReplyPayloadBuilder.build("   ")))
	}

	@Test
	fun `text-only rules are unchanged`() {
		assertTrue(SnapAttachmentBlock.canSend("hi", 0))
		assertEquals(SnapText.isValid("x".repeat(280)), SnapAttachmentBlock.canSend("x".repeat(280), 0))
		assertEquals(SnapText.isValid("x".repeat(281)), SnapAttachmentBlock.canSend("x".repeat(281), 0))
	}

	// ── the result in 40C's render/edit/refresh paths ──────────────────

	@Test
	fun `a root Snap keeps its frozen tail after the images`() {
		val urls = listOf(hosted(1), hosted(2, "gif"))
		val payload = SnapPayloadBuilder.build(SnapAttachmentBlock.append("great track", urls), SnapMedia("dQw4w9WgXcQ"))
		assertEquals(
			"great track\n\n![](${urls[0]})\n![](${urls[1]})\n\nhttps://youtu.be/dQw4w9WgXcQ\n\n" +
				"#scrobblelife #scrobble #rustedwax",
			payload.body,
		)
		assertNull(SnapPayloadBuilder.problem(payload, SnapMedia("dQw4w9WgXcQ")))
		// Read back exactly as 40C reads any v1 body.
		val userText = PostedSnapBody.userText(payload.body)!!
		assertEquals(SnapAttachmentBlock.append("great track", urls), userText)
		assertEquals("https://youtu.be/dQw4w9WgXcQ", PostedSnapBody.generatedUrl(payload.body))
	}

	@Test
	fun `Comments shows the words and all four images, never their URLs`() {
		val urls = listOf(hosted(1), hosted(2, "png"), hosted(3, "webp"), hosted(4, "gif"))
		val shown = SnapMediaText.display(SnapAttachmentBlock.append("look at these", urls))
		assertEquals("look at these", shown.text)
		assertEquals(urls, shown.media.map { it.source })
		assertTrue((shown.media.last() as SnapMediaRef.Image).animated)
	}

	@Test
	fun `History shows one still thumbnail, and an image-only reply reads as its kind`() {
		val urls = listOf(hosted(1, "gif"), hosted(2))
		val history = SnapMediaText.history(SnapAttachmentBlock.append("caption", urls))
		assertEquals("caption", history.text)
		assertEquals(urls[0], history.thumbnail!!.source)
		assertFalse(history.thumbnailStill!!.animated)
		assertFalse(history.showsReplyPreview)

		assertEquals("GIF", SnapMediaText.previewLine(SnapAttachmentBlock.append("", listOf(hosted(1, "gif")))))
		assertEquals("Image", SnapMediaText.previewLine(SnapAttachmentBlock.append("", listOf(hosted(1)))))
	}

	@Test
	fun `a reshaped body from another frontend keeps every image`() {
		val url = hosted(1)
		val reshaped = "![]($url)\n\ngreat track https://youtu.be/dQw4w9WgXcQ #scrobblelife #scrobble #rustedwax"
		val authored = PostedSnapBody.authored(reshaped, "https://youtu.be/dQw4w9WgXcQ")
		assertTrue(authored.contains(url))
		assertEquals(listOf(url), SnapMediaText.display(authored).media.map { it.source })
	}
}
