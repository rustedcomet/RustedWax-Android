package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.SnapClipIntake
import com.rustedwax.app.snaps.SnapImageFormat
import com.rustedwax.hive.ImageHoster
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/**
 * The one intake every picker, clipboard and keyboard image goes through
 * (Issue 40D), with the platform replaced: what is accepted, what is refused,
 * and that nothing is left behind or altered.
 */
class FileSnapImageIntakeTest {

	private val dir: File = Files.createTempDirectory("snap-intake").toFile()

	@After
	fun cleanUp() {
		dir.deleteRecursively()
	}

	private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(100) { it.toByte() }
	private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(50)
	private val webp = "RIFF".toByteArray() + byteArrayOf(1, 2, 3, 4) + "WEBPVP8X".toByteArray() + ByteArray(20)
	/** Two frames' worth of a GIF: the bytes must arrive untouched. */
	private val gif = "GIF89a".toByteArray() + ByteArray(300) { (it * 31).toByte() } + byteArrayOf(0x3B)
	private val heic = byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray() + byteArrayOf(0, 0, 0, 0) +
		"mif1heic".toByteArray() + ByteArray(40)

	private fun intake(
		sources: Map<String, () -> InputStream?>,
		decodes: (File) -> Boolean = { true },
		heifToJpeg: (File, File) -> Boolean = { _, out -> out.writeBytes(jpeg); true },
	) = FileSnapImageIntake(
		dir = dir,
		open = { uri -> (sources[uri] ?: error("unknown $uri"))() },
		decodes = decodes,
		heifToJpeg = heifToJpeg,
	)

	private fun src(name: String) = SnapImageSource("content://test/$name", SnapImageOrigin.PICKER)

	private fun bytes(b: ByteArray): () -> InputStream = { ByteArrayInputStream(b) }

	@Test
	fun `JPEG PNG WebP and GIF are kept byte for byte`() {
		val i = intake(
			mapOf(
				"content://test/a" to bytes(jpeg),
				"content://test/b" to bytes(png),
				"content://test/c" to bytes(webp),
				"content://test/d" to bytes(gif),
			),
		)
		listOf("a" to jpeg, "b" to png, "c" to webp, "d" to gif).forEach { (name, data) ->
			val taken = i.take(src(name)) as SnapImageIntake.Taken.Accepted
			assertArrayEquals("$name is untouched", data, taken.file.readBytes())
			assertEquals(data.size.toLong(), taken.bytes)
			assertEquals(dir, taken.file.parentFile)
		}
		assertEquals(
			SnapImageFormat.GIF,
			(i.take(src("d")) as SnapImageIntake.Taken.Accepted).format,
		)
	}

	@Test
	fun `HEIC is converted to JPEG and the original copy is removed`() {
		var converted = 0
		val i = intake(
			mapOf("content://test/h" to bytes(heic)),
			heifToJpeg = { from, out ->
				converted++
				assertArrayEquals(heic, from.readBytes())
				out.writeBytes(jpeg)
				true
			},
		)
		val taken = i.take(src("h")) as SnapImageIntake.Taken.Accepted
		assertEquals(1, converted)
		assertEquals(SnapImageFormat.JPEG, taken.format)
		assertArrayEquals(jpeg, taken.file.readBytes())
		assertEquals("only the converted JPEG remains", listOf(taken.file), dir.listFiles()!!.toList())
	}

	@Test
	fun `HEIC that cannot be converted, or converts past 14 MB, is refused and leaves nothing`() {
		val failing = intake(mapOf("content://test/h" to bytes(heic)), heifToJpeg = { _, _ -> false })
		assertTrue((failing.take(src("h")) as SnapImageIntake.Taken.Rejected).message.contains("HEIC"))

		val huge = intake(
			mapOf("content://test/h" to bytes(heic)),
			heifToJpeg = { _, out -> out.writeBytes(ByteArray(ImageHoster.MAX_SOURCE_BYTES + 1)); true },
		)
		assertTrue((huge.take(src("h")) as SnapImageIntake.Taken.Rejected).message.contains("14 MB"))
		assertTrue(dir.listFiles()!!.isEmpty())
	}

	@Test
	fun `a source over 14 MB is refused before anything is kept, and exactly 14 MB is accepted`() {
		val over = jpeg + ByteArray(ImageHoster.MAX_SOURCE_BYTES - jpeg.size + 1)
		val at = jpeg + ByteArray(ImageHoster.MAX_SOURCE_BYTES - jpeg.size)
		val i = intake(mapOf("content://test/over" to bytes(over), "content://test/at" to bytes(at)))

		assertTrue((i.take(src("over")) as SnapImageIntake.Taken.Rejected).message.contains("14 MB"))
		assertTrue(dir.listFiles()!!.isEmpty())
		assertEquals(
			ImageHoster.MAX_SOURCE_BYTES.toLong(),
			(i.take(src("at")) as SnapImageIntake.Taken.Accepted).bytes,
		)
	}

	@Test
	fun `unsupported formats are refused by name`() {
		val i = intake(
			mapOf(
				"content://test/pdf" to bytes("%PDF-1.4 ...".toByteArray()),
				"content://test/bmp" to bytes("BM....".toByteArray()),
				"content://test/zip" to bytes("PK\u0003\u0004....".toByteArray()),
				"content://test/svg" to bytes("<svg xmlns='x'/>".toByteArray()),
				"content://test/avif" to bytes(
					byteArrayOf(0, 0, 0, 20) + "ftypavif".toByteArray() + byteArrayOf(0, 0, 0, 0) + "mif1".toByteArray(),
				),
			),
		)
		mapOf("pdf" to "PDF", "bmp" to "BMP", "zip" to "ZIP", "svg" to "SVG", "avif" to "AVIF").forEach { (name, kind) ->
			val r = i.take(src(name)) as SnapImageIntake.Taken.Rejected
			assertTrue("$name: ${r.message}", r.message.contains(kind))
		}
		assertTrue(dir.listFiles()!!.isEmpty())
	}

	@Test
	fun `malformed, empty and undecodable images are refused`() {
		val i = intake(
			mapOf(
				"content://test/empty" to bytes(ByteArray(0)),
				"content://test/junk" to bytes("hello".toByteArray()),
				"content://test/broken" to bytes(jpeg),
			),
			decodes = { false },
		)
		assertTrue(i.take(src("empty")) is SnapImageIntake.Taken.Rejected)
		assertTrue(i.take(src("junk")) is SnapImageIntake.Taken.Rejected)
		assertTrue((i.take(src("broken")) as SnapImageIntake.Taken.Rejected).message.contains("damaged"))
		assertTrue(dir.listFiles()!!.isEmpty())
	}

	@Test
	fun `an inaccessible or revoked temporary URI is refused and leaves no partial copy`() {
		val revokedMidway: () -> InputStream = {
			object : InputStream() {
				var n = 0
				override fun read(): Int = if (n++ < 10) 0xFF else throw IOException("grant revoked")
				override fun read(b: ByteArray, off: Int, len: Int): Int {
					if (n++ > 0) throw IOException("grant revoked")
					jpeg.copyInto(b, off, 0, minOf(len, jpeg.size))
					return minOf(len, jpeg.size)
				}
			}
		}
		val i = intake(
			mapOf(
				"content://test/denied" to { throw SecurityException("no grant") },
				"content://test/null" to { null },
				"content://test/revoked" to revokedMidway,
			),
		)
		listOf("denied", "null", "revoked").forEach {
			assertTrue(it, i.take(src(it)) is SnapImageIntake.Taken.Rejected)
		}
		assertTrue(dir.listFiles()!!.isEmpty())
		// Only content:// is ever opened.
		assertTrue(i.take(SnapImageSource("file:///sdcard/x.jpg", SnapImageOrigin.CLIPBOARD)) is SnapImageIntake.Taken.Rejected)
	}

	@Test
	fun `once copied, the image no longer depends on its source`() {
		var live = true
		val i = intake(mapOf("content://test/a" to { if (live) ByteArrayInputStream(jpeg) else throw SecurityException() }))
		val taken = i.take(src("a")) as SnapImageIntake.Taken.Accepted
		live = false
		assertArrayEquals(jpeg, taken.file.readBytes())
	}

	@Test
	fun `discard removes only the intake's own copies`() {
		val i = intake(mapOf("content://test/a" to bytes(jpeg)))
		val taken = i.take(src("a")) as SnapImageIntake.Taken.Accepted
		val outside = File.createTempFile("not-ours", ".jpg").apply { deleteOnExit() }
		i.discard(outside)
		i.discard(taken.file)
		assertTrue(outside.exists())
		assertFalse(taken.file.exists())
		outside.delete()
	}

	// ── what a paste or keyboard commit counts as ──────────────────────

	@Test
	fun `copied text and image URLs stay text, image content becomes an attachment`() {
		// A copied link: text, no URI.
		assertFalse(SnapClipIntake.takesAsImage(null, "https://x.example/cat.png", clipDeclaresImage = false))
		assertFalse(SnapClipIntake.takesAsImage(null, "hello", clipDeclaresImage = true))
		// Image content from any app: a content URI on an image clip.
		assertTrue(SnapClipIntake.takesAsImage("content://any.app/1", null, clipDeclaresImage = true))
		assertTrue(SnapClipIntake.takesAsImage("content://any.app/1", "alt text", clipDeclaresImage = true))
		// A URI with nothing else to paste: handed to the intake, which judges the bytes.
		assertTrue(SnapClipIntake.takesAsImage("content://files/doc", null, clipDeclaresImage = false))
		// A URI that also has text, on a clip that is not an image: the text pastes.
		assertFalse(SnapClipIntake.takesAsImage("content://notes/1", "a note", clipDeclaresImage = false))
	}
}
