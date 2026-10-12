package com.rustedwax.app.update

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Reads the official metadata asset of the latest GitHub release.
 *
 * Redirects are followed by hand rather than by `HttpURLConnection`, so every
 * hop is checked: HTTPS only, GitHub's own hosts only, at most [MAX_HOPS]. The
 * hop that names the release (`…/releases/download/v0.14.0/…`) is remembered
 * and handed to [UpdateMetadata.parse], which refuses a file whose
 * `versionName` disagrees with it.
 */
internal class ReleaseMetadataSource(
	private val transport: Transport,
	private val url: String = URL_LATEST,
) : UpdateSource {

	/** One request, no redirect following. */
	fun interface Transport {
		fun get(url: String): Response
	}

	class Response(val status: Int, val location: String?, val body: String?)

	override fun read(): Result<UpdateMetadata> = runCatching {
		var current = url
		var tag: String? = null
		repeat(MAX_HOPS) {
			val uri = URI(current)
			check(uri.scheme == "https") { "not https" }
			check(uri.host in HOSTS) { "unexpected host ${uri.host}" }
			tag = tag ?: tagOf(uri.path)
			val response = transport.get(current)
			when (response.status) {
				HttpURLConnection.HTTP_OK -> {
					val body = checkNotNull(response.body) { "empty body" }
					return@runCatching checkNotNull(UpdateMetadata.parse(body, tag)) { "metadata refused" }
				}
				301, 302, 303, 307, 308 -> {
					current = uri.resolve(checkNotNull(response.location) { "redirect without location" }).toString()
				}
				else -> error("HTTP ${response.status}")
			}
		}
		error("too many redirects")
	}

	companion object {
		const val ASSET = "rustedwax-update.json"
		const val URL_LATEST = "https://github.com/rustedcomet/RustedWax-Android/releases/latest/download/$ASSET"

		const val MAX_HOPS = 4

		/** GitHub, and the hosts it serves release assets from. */
		val HOSTS = setOf(
			"github.com",
			"release-assets.githubusercontent.com",
			"objects.githubusercontent.com",
		)

		private val RELEASE_PATH = Regex("""/rustedcomet/RustedWax-Android/releases/download/([^/]+)/${Regex.escape(ASSET)}""")

		fun tagOf(path: String?): String? = path?.let { RELEASE_PATH.matchEntire(it)?.groupValues?.get(1) }
	}
}

/** The real transport: one bounded GET. Called off the main thread only. */
internal class HttpsTransport(private val userAgent: String) : ReleaseMetadataSource.Transport {

	override fun get(url: String): ReleaseMetadataSource.Response {
		val connection = URL(url).openConnection() as HttpURLConnection
		try {
			connection.instanceFollowRedirects = false
			connection.connectTimeout = TIMEOUT_MS
			connection.readTimeout = TIMEOUT_MS
			connection.useCaches = false
			connection.setRequestProperty("User-Agent", userAgent)
			connection.setRequestProperty("Accept", "application/json, application/octet-stream")
			val status = connection.responseCode
			val body = if (status == HttpURLConnection.HTTP_OK) {
				connection.inputStream.use { input ->
					val out = ByteArrayOutputStream()
					val buffer = ByteArray(1024)
					var n = input.read(buffer)
					while (n >= 0) {
						out.write(buffer, 0, n)
						check(out.size() <= MAX_BYTES) { "metadata too large" }
						n = input.read(buffer)
					}
					out.toString(Charsets.UTF_8.name())
				}
			} else {
				null
			}
			return ReleaseMetadataSource.Response(status, connection.getHeaderField("Location"), body)
		} finally {
			connection.disconnect()
		}
	}

	private companion object {
		const val TIMEOUT_MS = 10_000
		const val MAX_BYTES = 8 * 1024
	}
}
