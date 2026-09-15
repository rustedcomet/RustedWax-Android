package com.rustedwax.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserOriginTest {

	@Test
	fun `host comes from the parsed authority rather than path text`() {
		val hostileUrls = mapOf(
			"https://evil.example/youtube.com/watch?v=dQw4w9WgXcQ" to "evil.example",
			"https://192.0.2.1/youtube.com/watch?v=dQw4w9WgXcQ" to "192.0.2.1",
			"https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ" to
				"youtube.com.evil.example",
			"https://www.www.youtube.com/watch?v=dQw4w9WgXcQ" to "www.www.youtube.com",
			"https://evil-youtube.com/watch?v=dQw4w9WgXcQ" to "evil-youtube.com",
			"prefix-youtube.com-suffix" to "prefix-youtube.com-suffix",
		)

		hostileUrls.forEach { (url, expectedHost) ->
			val host = BrowserOrigin.hostOf(url)
			assertEquals(expectedHost, host)
			assertFalse(YouTubeProbe.isYouTubeHost(host))
		}
	}

	@Test
	fun `userinfo and malformed embedded domains are rejected`() {
		listOf(
			"https://youtube.com@evil.example/watch?v=dQw4w9WgXcQ",
			"https://user:secret@youtube.com/watch?v=dQw4w9WgXcQ",
			"https://evil.example youtube.com/watch?v=dQw4w9WgXcQ",
			"youtube.com/watch?v=dQw4w9WgXcQ",
			"prefix youtube.com suffix",
			"https://youtube.com:/watch?v=dQw4w9WgXcQ",
			"javascript:https://youtube.com/watch?v=dQw4w9WgXcQ",
			"//youtube.com/watch?v=dQw4w9WgXcQ",
		).forEach { value ->
			assertNull(value, BrowserOrigin.hostOf(value))
		}
	}

	@Test
	fun `legitimate youtube origins remain accepted`() {
		val legitimateUrls = mapOf(
			"https://www.youtube.com/watch?v=dQw4w9WgXcQ" to "youtube.com",
			"http://youtube.com/watch?v=dQw4w9WgXcQ" to "youtube.com",
			"https://youtube.com/watch?v=dQw4w9WgXcQ" to "youtube.com",
			"https://m.youtube.com/watch?v=dQw4w9WgXcQ" to "m.youtube.com",
			"https://music.youtube.com/watch?v=dQw4w9WgXcQ" to "music.youtube.com",
			"https://youtu.be/dQw4w9WgXcQ" to "youtu.be",
			"www.youtube.com" to "youtube.com",
			"youtube.com" to "youtube.com",
			"m.youtube.com" to "m.youtube.com",
			"music.youtube.com" to "music.youtube.com",
		)

		legitimateUrls.forEach { (url, expectedHost) ->
			val host = BrowserOrigin.hostOf(url)
			assertEquals(expectedHost, host)
			assertTrue(YouTubeProbe.isYouTubeHost(host))
		}
	}

	@Test
	fun `address bar accepts the scheme Chromium elides`() {
		val elided = mapOf(
			"m.youtube.com/watch?v=dQw4w9WgXcQ" to "m.youtube.com",
			"youtube.com/watch?v=dQw4w9WgXcQ" to "youtube.com",
			"www.youtube.com/watch?v=dQw4w9WgXcQ" to "youtube.com",
			"music.youtube.com/watch?v=dQw4w9WgXcQ" to "music.youtube.com",
			"youtu.be/dQw4w9WgXcQ" to "youtu.be",
			"m.youtube.com/shorts/dQw4w9WgXcQ" to "m.youtube.com",
			"m.youtube.com?v=dQw4w9WgXcQ" to "m.youtube.com",
			"m.youtube.com#t=1" to "m.youtube.com",
			"m.youtube.com:8443/watch?v=dQw4w9WgXcQ" to "m.youtube.com",
			"M.YouTube.COM/watch?v=dQw4w9WgXcQ" to "m.youtube.com",
		)

		elided.forEach { (value, expectedHost) ->
			val host = BrowserOrigin.hostOfAddressBar(value)
			assertEquals(value, expectedHost, host)
			assertTrue(value, YouTubeProbe.isYouTubeHost(host))
		}
	}

	@Test
	fun `address bar reads full urls and exact bare hosts unchanged`() {
		val unchanged = mapOf(
			"https://www.youtube.com/watch?v=dQw4w9WgXcQ" to "youtube.com",
			"http://youtube.com/watch?v=dQw4w9WgXcQ" to "youtube.com",
			"https://m.youtube.com/watch?v=dQw4w9WgXcQ" to "m.youtube.com",
			"https://music.youtube.com/watch?v=dQw4w9WgXcQ" to "music.youtube.com",
			"https://youtu.be/dQw4w9WgXcQ" to "youtu.be",
			"www.youtube.com" to "youtube.com",
			"youtube.com" to "youtube.com",
			"m.youtube.com" to "m.youtube.com",
		)

		unchanged.forEach { (value, expectedHost) ->
			assertEquals(value, expectedHost, BrowserOrigin.hostOfAddressBar(value))
		}
	}

	@Test
	fun `restoring the scheme still takes the host from the authority`() {
		val hostileUrls = mapOf(
			"evil.example/youtube.com/watch?v=dQw4w9WgXcQ" to "evil.example",
			"youtube.com.evil.example/watch?v=dQw4w9WgXcQ" to "youtube.com.evil.example",
			"evil-youtube.com/watch?v=dQw4w9WgXcQ" to "evil-youtube.com",
			"192.0.2.1/youtube.com/watch?v=dQw4w9WgXcQ" to "192.0.2.1",
			"https://evil.example/youtube.com/watch?v=dQw4w9WgXcQ" to "evil.example",
		)

		hostileUrls.forEach { (value, expectedHost) ->
			val host = BrowserOrigin.hostOfAddressBar(value)
			assertEquals(value, expectedHost, host)
			assertFalse(value, YouTubeProbe.isYouTubeHost(host))
		}
	}

	@Test
	fun `restoring the scheme cannot rescue a malformed or hostile authority`() {
		listOf(
			"//youtube.com/watch?v=dQw4w9WgXcQ",
			"javascript:https://youtube.com/watch?v=dQw4w9WgXcQ",
			"data:text/html,youtube.com/watch?v=dQw4w9WgXcQ",
			"chrome://youtube.com/watch?v=dQw4w9WgXcQ",
			"youtube.com@evil.example/watch?v=dQw4w9WgXcQ",
			"user:secret@youtube.com/watch?v=dQw4w9WgXcQ",
			"youtube.com:/watch?v=dQw4w9WgXcQ",
			"youtube.com:evil/watch?v=dQw4w9WgXcQ",
			"youtube.com:99999999/watch?v=dQw4w9WgXcQ",
			"youtube.com\\@evil.example/watch?v=dQw4w9WgXcQ",
			"evil.example youtube.com/watch?v=dQw4w9WgXcQ",
			"youtube.com./watch?v=dQw4w9WgXcQ",
			"/watch?v=dQw4w9WgXcQ",
			"?v=dQw4w9WgXcQ",
			"#youtube.com/watch?v=dQw4w9WgXcQ",
		).forEach { value ->
			assertNull(value, BrowserOrigin.hostOfAddressBar(value))
		}
	}

	@Test
	fun `hostOf itself still refuses a schemeless host and path`() {
		listOf(
			"youtube.com/watch?v=dQw4w9WgXcQ",
			"m.youtube.com/watch?v=dQw4w9WgXcQ",
			"evil.example/youtube.com/watch?v=dQw4w9WgXcQ",
		).forEach { value ->
			assertNull(value, BrowserOrigin.hostOf(value))
		}
	}
}
