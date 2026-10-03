package com.rustedwax.hive

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Hive's ImageHoster (`images.hive.blog`): where a Snap's phone images are
 * uploaded before the Snap is published (Issue 40D).
 *
 * The protocol is the "sign the image" upload the service's own source
 * (`openhive-network/imagehoster`, `src/upload.ts`, `uploadHandler`) verifies,
 * checked against the live endpoint on 2026-10-02:
 *
 *  - `POST https://images.hive.blog/{account}/{signature}`, a multipart body
 *    holding exactly one file, with a real Content-Length (the server refuses
 *    a request without one);
 *  - `signature` is the account's posting key signing
 *    `sha256("ImageSigningChallenge" ‖ fileBytes)` — the same 65-byte compact
 *    recoverable hex form [HiveKey.sign] already produces for transactions.
 *    The server recovers the public key and requires it in the account's
 *    posting (or active) `key_auths` at threshold weight;
 *  - success is HTTP 200 with `{"url": "https://images.hive.blog/D{multihash}/{filename}"}`,
 *    where the multihash is of that same challenge digest. The address is
 *    therefore *content-addressed*: the same bytes always get the same URL,
 *    which lets this client predict it and refuse anything else.
 *
 * Nothing else stands between a device and the host: no RustedWax server, no
 * account other than the user's own, and the posting key never leaves the
 * device — only a signature over one image's digest does.
 */
object ImageHoster {

	const val BASE = "https://images.hive.blog"

	/**
	 * RustedWax's per-image ceiling, in bytes, checked before any network.
	 *
	 * The service's `max_image_size` is 15,000,000 bytes and is checked against
	 * the whole multipart Content-Length. 14,000,000 leaves room for the
	 * multipart framing and does not promise the boundary value; a live probe on
	 * 2026-10-02 confirmed a 14,000,000-byte (and a 14 MiB) body reaches the
	 * signature check while 15,000,500 bytes is refused.
	 */
	const val MAX_SOURCE_BYTES = 14_000_000

	private const val CHALLENGE = "ImageSigningChallenge"

	/** The digest the posting key signs, and the one the stored address names. */
	fun challenge(data: ByteArray): ByteArray = sha256(CHALLENGE.toByteArray(Charsets.UTF_8), data)

	/** `D` + base58(multihash sha2-256 of [challenge]), as the server names it. */
	fun contentHash(data: ByteArray): String =
		"D" + Base58.encode(byteArrayOf(0x12, 0x20) + challenge(data))

	/** The one address a successful upload of [data] as [fileName] may return. */
	fun expectedUrl(data: ByteArray, fileName: String): String = "$BASE/${contentHash(data)}/$fileName"

	/** File names this client ever sends: fixed, ASCII, and nothing the user typed. */
	internal val FILE_NAME = Regex("^image\\.(jpg|png|webp|gif)$")

	/**
	 * A hosted RustedWax attachment address, strictly: this host, a sha2-256
	 * content hash, and one of the file names [FILE_NAME] allows.
	 */
	val HOSTED_URL = Regex(
		"^https://images\\.hive\\.blog/DQm[1-9A-HJ-NP-Za-km-z]{44}/image\\.(jpg|png|webp|gif)$",
	)

	sealed interface Result {
		data class Uploaded(val url: String) : Result
		data class Failed(val message: String) : Result
	}

	/**
	 * Read one response. Only HTTP 200 carrying exactly [expectedUrl] counts as
	 * an upload; anything else — another address, no address, a proxy's HTML
	 * error page — is a failure, never a reference to publish.
	 */
	fun parse(status: Int, body: String?, expectedUrl: String): Result {
		val json = runCatching { body?.let(::JSONObject) }.getOrNull()
		if (status == 200) {
			val url = json?.optString("url").orEmpty()
			return if (url == expectedUrl && HOSTED_URL.matches(url)) {
				Result.Uploaded(url)
			} else {
				Result.Failed("Hive's image host answered with an unexpected address, so the image wasn't used.")
			}
		}
		val name = json?.optJSONObject("error")?.optString("name").orEmpty()
		return Result.Failed(
			when (name) {
				"invalid_signature" ->
					"Hive's image host didn't accept the signature from your posting key."
				"no_such_account" -> "Hive's image host couldn't find your Hive account."
				"deplorable" -> "Hive's image host only accepts uploads from accounts with a reputation of 10 or more."
				"qouta_exceeded", "quota_exceeded" ->
					"You've reached Hive's image upload limit for now. Try again later."
				"payload_too_large" -> "This image is too large for Hive's image host."
				"blacklisted" -> "Hive's image host isn't accepting uploads from this account."
				else -> "Hive's image host couldn't take the image right now (HTTP $status). Try again."
			},
		)
	}
}

/**
 * One signed upload to [ImageHoster]. The key is passed in for the length of
 * one call and never kept.
 */
class ImageHosterClient(
	/** (url, content type, body, progress) → (HTTP status, response text). The seam tests replace. */
	private val transport: (String, String, ByteArray, (Long, Long) -> Unit) -> Pair<Int, String?> = ::httpPost,
	private val random: SecureRandom = SecureRandom(),
) {

	fun upload(
		account: String,
		key: HiveKey,
		data: ByteArray,
		fileName: String,
		mimeType: String,
		onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
	): ImageHoster.Result {
		if (!HiveAccountName.isValid(account)) {
			return ImageHoster.Result.Failed("This isn't a Hive account name RustedWax can upload for.")
		}
		if (data.isEmpty() || data.size > ImageHoster.MAX_SOURCE_BYTES) {
			return ImageHoster.Result.Failed("This image is larger than the 14 MB limit.")
		}
		require(ImageHoster.FILE_NAME.matches(fileName)) { "unexpected file name" }
		val signature = key.sign(ImageHoster.challenge(data))
		val boundary = "rustedwax" + ByteArray(12).also(random::nextBytes).toHex()
		val head = (
			"--$boundary\r\n" +
				"Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
				"Content-Type: $mimeType\r\n\r\n"
			).toByteArray(Charsets.UTF_8)
		val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
		val body = head + data + tail
		val (status, text) = try {
			transport("${ImageHoster.BASE}/$account/$signature", "multipart/form-data; boundary=$boundary", body, onProgress)
		} catch (_: IOException) {
			return ImageHoster.Result.Failed("Couldn't reach Hive's image host. Check your connection and try again.")
		}
		return ImageHoster.parse(status, text, ImageHoster.expectedUrl(data, fileName))
	}

	private companion object {
		const val CONNECT_TIMEOUT_MS = 15_000
		const val READ_TIMEOUT_MS = 60_000
		const val MAX_RESPONSE_CHARS = 16 * 1024

		fun httpPost(
			url: String,
			contentType: String,
			body: ByteArray,
			onProgress: (Long, Long) -> Unit,
		): Pair<Int, String?> {
			val conn = (URL(url).openConnection() as HttpURLConnection).apply {
				requestMethod = "POST"
				connectTimeout = CONNECT_TIMEOUT_MS
				readTimeout = READ_TIMEOUT_MS
				doOutput = true
				// The service requires a Content-Length; fixed-length streaming
				// sends one and lets progress follow the bytes actually written.
				setFixedLengthStreamingMode(body.size)
				instanceFollowRedirects = false
				setRequestProperty("Content-Type", contentType)
				setRequestProperty("Accept", "application/json")
			}
			try {
				conn.outputStream.use { out ->
					val total = body.size.toLong()
					var sent = 0
					while (sent < body.size) {
						val n = minOf(64 * 1024, body.size - sent)
						out.write(body, sent, n)
						sent += n
						onProgress(sent.toLong(), total)
					}
				}
				val status = conn.responseCode
				val stream = if (status in 200..299) conn.inputStream else conn.errorStream
				// Bounded: the answer is a short JSON object, or a proxy's error page.
				val text = stream?.bufferedReader()?.use { r ->
					val buf = CharArray(MAX_RESPONSE_CHARS)
					var n = 0
					while (n < buf.size) {
						val read = r.read(buf, n, buf.size - n)
						if (read < 0) break
						n += read
					}
					String(buf, 0, n)
				}
				return status to text
			} finally {
				conn.disconnect()
			}
		}
	}
}
