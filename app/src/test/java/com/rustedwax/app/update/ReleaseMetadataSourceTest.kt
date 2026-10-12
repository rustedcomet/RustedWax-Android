package com.rustedwax.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseMetadataSourceTest {

	private val body = """{"schema":1,"applicationId":"com.rustedwax.app","versionCode":66,"versionName":"0.14.0"}"""
	private val tagged = "https://github.com/rustedcomet/RustedWax-Android/releases/download/v0.14.0/rustedwax-update.json"
	private val asset = "https://release-assets.githubusercontent.com/github-production-release-asset/1/abc?sp=r&sig=x"

	/** GitHub's real shape: latest → tagged download → asset host. */
	private fun github(
		tagHop: String = tagged,
		assetHop: String = asset,
		final: ReleaseMetadataSource.Response = ReleaseMetadataSource.Response(200, null, body),
		seen: MutableList<String> = mutableListOf(),
	) = ReleaseMetadataSource.Transport { url ->
		seen += url
		when (url) {
			ReleaseMetadataSource.URL_LATEST -> ReleaseMetadataSource.Response(302, tagHop, null)
			tagHop -> ReleaseMetadataSource.Response(302, assetHop, null)
			assetHop -> final
			else -> error("unexpected request $url")
		}
	}

	private fun failure(transport: ReleaseMetadataSource.Transport): String =
		ReleaseMetadataSource(transport).read().exceptionOrNull()!!.message!!

	@Test
	fun `the real redirect chain yields the metadata`() {
		val seen = mutableListOf<String>()
		assertEquals(UpdateMetadata(66, "0.14.0"), ReleaseMetadataSource(github(seen = seen)).read().getOrThrow())
		assertEquals(listOf(ReleaseMetadataSource.URL_LATEST, tagged, asset), seen)
	}

	@Test
	fun `a file attached to the wrong release is refused`() {
		val wrong = tagged.replace("v0.14.0", "v0.13.0")
		assertEquals("metadata refused", failure(github(tagHop = wrong)))
	}

	@Test
	fun `redirects off GitHub or off HTTPS are refused before they are requested`() {
		assertEquals("unexpected host evil.example", failure(github(assetHop = "https://evil.example/x.json")))
		assertEquals("not https", failure(github(assetHop = "http://release-assets.githubusercontent.com/x")))
	}

	@Test
	fun `HTTP failures, missing location, empty body and loops fail quietly`() {
		assertEquals("HTTP 404", failure { ReleaseMetadataSource.Response(404, null, null) })
		assertEquals("HTTP 500", failure(github(final = ReleaseMetadataSource.Response(500, null, null))))
		assertEquals("redirect without location", failure { ReleaseMetadataSource.Response(302, null, null) })
		assertEquals("empty body", failure(github(final = ReleaseMetadataSource.Response(200, null, null))))
		assertEquals(
			"too many redirects",
			failure { ReleaseMetadataSource.Response(302, ReleaseMetadataSource.URL_LATEST, null) },
		)
	}

	@Test
	fun `a transport exception such as a timeout or offline becomes a failure, not a crash`() {
		val result = ReleaseMetadataSource(transport = { throw java.net.SocketTimeoutException("timed out") }).read()
		assertTrue(result.exceptionOrNull() is java.net.SocketTimeoutException)
		val offline = ReleaseMetadataSource(transport = { throw java.net.UnknownHostException("github.com") }).read()
		assertTrue(offline.isFailure)
	}

	@Test
	fun `the tag is read only from the official release path`() {
		assertEquals("v0.14.0", ReleaseMetadataSource.tagOf("/rustedcomet/RustedWax-Android/releases/download/v0.14.0/rustedwax-update.json"))
		assertEquals(null, ReleaseMetadataSource.tagOf("/someone/else/releases/download/v0.14.0/rustedwax-update.json"))
		assertEquals(null, ReleaseMetadataSource.tagOf("/rustedcomet/RustedWax-Android/releases/latest/download/rustedwax-update.json"))
	}
}
