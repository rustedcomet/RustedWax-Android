package com.rustedwax.hive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The ImageHoster upload protocol (Issue 40D), pinned to vectors produced by
 * `@hiveio/dhive` 1.x and `multihashes` 4.x — the same libraries the service's
 * `uploadHandler` verifies with — for the scalar-1 test key ([SnapTestKey]).
 */
class ImageHosterTest {

	private val textData = "RustedWax 40D vector".toByteArray(Charsets.UTF_8)
	private val gifData = "474946383961010001000000003b".hexToBytes()

	@Test
	fun `the signed digest is sha256 of the challenge prefix and the bytes`() {
		assertEquals(
			"9eca1ffaaff7075f9408b740df4080e9eae5036bc684fe7013b9f3b2b6dcc176",
			ImageHoster.challenge(textData).toHex(),
		)
		assertEquals(
			"3c2d318eed354fbfaf55e10b0311de90fb1913ed56f3b835c7a3396a7f9ff454",
			ImageHoster.challenge(gifData).toHex(),
		)
	}

	@Test
	fun `the signature is byte-identical to dhive's for the same key`() {
		assertEquals(
			"201e1db3605dfd11874162b9816e3ea71659e25f09f79a9cb85464339f4226cd97" +
				"413bb229c5289d0c8ee91b79bc67119abf34c3b97631a7fb6368dc2640e4e2cb",
			SnapTestKey.key.sign(ImageHoster.challenge(textData)),
		)
		assertEquals(
			"1f51a79d185d8246704f54e91c5024391b8b766f6dddbfb3bb9ff65bfd9051a38a" +
				"3fbd9cfe3813fff767b99dd57f23bc65bb3fdf360787b2147ee1a628f59d74ee",
			SnapTestKey.key.sign(ImageHoster.challenge(gifData)),
		)
	}

	@Test
	fun `the predicted address matches the server's content hash`() {
		assertEquals("DQmZ2VtM3sBzSDi1Xf9TYGapC4AcWPVwD9wAZzeMNHsoqKK", ImageHoster.contentHash(textData))
		assertEquals(
			"https://images.hive.blog/DQmSPZ983RvnxCr7QC7a96Z2yjpS1d7qgvo9GXe4NSV5XZV/image.gif",
			ImageHoster.expectedUrl(gifData, "image.gif"),
		)
		assertTrue(ImageHoster.HOSTED_URL.matches(ImageHoster.expectedUrl(gifData, "image.gif")))
	}

	@Test
	fun `only HTTP 200 with exactly the predicted address is an upload`() {
		val expected = ImageHoster.expectedUrl(gifData, "image.gif")
		assertEquals(
			ImageHoster.Result.Uploaded(expected),
			ImageHoster.parse(200, """{"url":"$expected"}""", expected),
		)
		val other = expected.replace("image.gif", "image.png")
		assertTrue(ImageHoster.parse(200, """{"url":"$other"}""", expected) is ImageHoster.Result.Failed)
		assertTrue(ImageHoster.parse(200, """{"nope":1}""", expected) is ImageHoster.Result.Failed)
		assertTrue(ImageHoster.parse(200, "<html>ok</html>", expected) is ImageHoster.Result.Failed)
		assertTrue(ImageHoster.parse(200, null, expected) is ImageHoster.Result.Failed)
	}

	@Test
	fun `service errors become plain reasons, and a proxy's HTML page is still a failure`() {
		fun msg(status: Int, body: String?) =
			(ImageHoster.parse(status, body, "x") as ImageHoster.Result.Failed).message
		assertTrue(msg(400, """{"error":{"name":"invalid_signature"}}""").contains("signature"))
		assertTrue(msg(403, """{"error":{"name":"deplorable"}}""").contains("reputation"))
		assertTrue(msg(429, """{"error":{"name":"qouta_exceeded"}}""").contains("limit"))
		assertTrue(msg(413, """{"error":{"name":"payload_too_large"}}""").contains("too large"))
		assertTrue(msg(503, "<html>503 Backend fetch failed</html>").contains("HTTP 503"))
	}

	@Test
	fun `an upload signs the bytes and sends one multipart file with a length`() {
		var seenUrl = ""
		var seenType = ""
		var seenBody = ByteArray(0)
		val client = ImageHosterClient(transport = { url, type, body, progress ->
			seenUrl = url
			seenType = type
			seenBody = body
			progress(body.size.toLong(), body.size.toLong())
			200 to """{"url":"${ImageHoster.expectedUrl(gifData, "image.gif")}"}"""
		})

		val result = client.upload("alice", SnapTestKey.key, gifData, "image.gif", "image/gif")

		assertEquals(ImageHoster.Result.Uploaded(ImageHoster.expectedUrl(gifData, "image.gif")), result)
		assertEquals(
			"https://images.hive.blog/alice/" + SnapTestKey.key.sign(ImageHoster.challenge(gifData)),
			seenUrl,
		)
		val boundary = seenType.substringAfter("boundary=")
		assertTrue(seenType.startsWith("multipart/form-data; boundary="))
		val text = String(seenBody, Charsets.ISO_8859_1)
		assertTrue(text.startsWith("--$boundary\r\n"))
		assertTrue(text.contains("Content-Disposition: form-data; name=\"file\"; filename=\"image.gif\"\r\n"))
		assertTrue(text.contains("Content-Type: image/gif\r\n\r\n"))
		assertTrue(text.endsWith("\r\n--$boundary--\r\n"))
		// The file's bytes travel untouched: a GIF keeps every frame.
		assertTrue(text.contains(String(gifData, Charsets.ISO_8859_1)))
	}

	@Test
	fun `an oversized file never reaches the network`() {
		var calls = 0
		val client = ImageHosterClient(transport = { _, _, _, _ -> calls++; 200 to "{}" })

		val result = client.upload(
			"alice",
			SnapTestKey.key,
			ByteArray(ImageHoster.MAX_SOURCE_BYTES + 1),
			"image.jpg",
			"image/jpeg",
		)

		assertTrue(result is ImageHoster.Result.Failed)
		assertEquals(0, calls)
	}

	@Test
	fun `a network failure is a failure, not an address`() {
		val client = ImageHosterClient(transport = { _, _, _, _ -> throw IOException("offline") })
		val result = client.upload("alice", SnapTestKey.key, gifData, "image.gif", "image/gif")
		assertTrue((result as ImageHoster.Result.Failed).message.contains("Couldn't reach"))
	}

	@Test
	fun `an invalid account name is refused before signing`() {
		var calls = 0
		val client = ImageHosterClient(transport = { _, _, _, _ -> calls++; 200 to "{}" })
		val result = client.upload("Not/An/Account", SnapTestKey.key, gifData, "image.gif", "image/gif")
		assertTrue(result is ImageHoster.Result.Failed)
		assertEquals(0, calls)
	}
}
