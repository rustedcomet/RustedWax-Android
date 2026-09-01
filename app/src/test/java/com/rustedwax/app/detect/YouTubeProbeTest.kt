package com.rustedwax.app.detect

import android.media.MediaMetadata
import com.rustedwax.core.MetadataFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeProbeTest {

	private class Fields(private val values: Map<String, Any>) : MetadataFields {
		override fun getString(key: String): String? = values[key] as? String
		override fun getLong(key: String): Long = (values[key] as? Long) ?: 0
		override fun bitmapDimensions(key: String): Pair<Int, Int>? = null
		override fun keySet(): Set<String> = values.keys
	}

	private fun url(host: String?, videoId: String?, isShort: Boolean = false) =
		UrlEvidence.Evidence(host = host, videoId = videoId, isShort = isShort, raw = "test")

	private fun hint(host: String?) =
		NotificationHints.Hint(host = host, subText = host, title = null, text = null)

	private fun metadataUri(
		value: String,
		key: String = MediaMetadata.METADATA_KEY_MEDIA_URI,
	) = Fields(mapOf(key to value))

	private fun identifyMetadataUri(
		value: String,
		key: String = MediaMetadata.METADATA_KEY_MEDIA_URI,
		hintHost: String? = null,
	) = YouTubeProbe.identify(
		md = metadataUri(value, key),
		hint = hintHost?.let(::hint),
	)

	private fun assertNotConfirmed(identity: YouTubeProbe.Identity) {
		assertFalse("untrusted URI must not confirm YouTube identity: $identity", identity is YouTubeProbe.Identity.Confirmed)
	}

	@Test
	fun `bar plus agreeing notification confirms the video`() {
		val id = YouTubeProbe.identify(
			md = null,
			hint = hint("youtube.com"),
			url = url("youtube.com", "abcdefghijk"),
			soleSession = false,
		)
		assertTrue(id is YouTubeProbe.Identity.Confirmed)
		assertEquals("abcdefghijk", (id as YouTubeProbe.Identity.Confirmed).videoId)
	}

	/**
	 * The regression. Chromium's sub-text isn't always a parseable host; the
	 * hint then carries `host = null`. That is absence of information, not
	 * disagreement, and it used to block confirmation — costing the payload its
	 * `url` even though the address bar named the video outright.
	 */
	@Test
	fun `a hint with no host does not veto the address bar`() {
		val id = YouTubeProbe.identify(
			md = null,
			hint = hint(null),
			url = url("youtube.com", "abcdefghijk"),
			soleSession = true,
		)
		assertTrue(
			"a hostless hint must not suppress a good video id, got $id",
			id is YouTubeProbe.Identity.Confirmed,
		)
	}

	/** A hint naming a different site is real disagreement and must still win. */
	@Test
	fun `a hint naming another site vetoes the address bar`() {
		val id = YouTubeProbe.identify(
			md = null,
			hint = hint("soundcloud.com"),
			url = url("youtube.com", "abcdefghijk"),
			soleSession = true,
		)
		assertTrue(id !is YouTubeProbe.Identity.Confirmed)
	}

	/**
	 * With several sessions live and nothing corroborating, the bar describes
	 * the foreground tab — which need not be the tab that's playing.
	 */
	@Test
	fun `an uncorroborated bar is not trusted when other sessions exist`() {
		val id = YouTubeProbe.identify(
			md = null,
			hint = null,
			url = url("youtube.com", "abcdefghijk"),
			soleSession = false,
		)
		assertTrue(id !is YouTubeProbe.Identity.Confirmed)
	}

	@Test
	fun `a bar with a host but no video id yields site-only`() {
		val id = YouTubeProbe.identify(
			md = null,
			hint = hint("youtube.com"),
			url = url("youtube.com", null),
			soleSession = true,
		)
		assertTrue(id is YouTubeProbe.Identity.SiteOnly)
	}

	@Test
	fun `shorts flag survives into the confirmed identity`() {
		val id = YouTubeProbe.identify(
			md = null,
			hint = hint("youtube.com"),
			url = url("youtube.com", "abcdefghijk", isShort = true),
			soleSession = true,
		)
		assertTrue((id as YouTubeProbe.Identity.Confirmed).isShort)
	}

	@Test
	fun `host allowlist is exact`() {
		assertTrue(YouTubeProbe.isYouTubeHost("youtube.com"))
		assertTrue(YouTubeProbe.isYouTubeHost("m.youtube.com"))
		assertTrue(YouTubeProbe.isYouTubeHost("music.youtube.com"))
		assertTrue(YouTubeProbe.isYouTubeHost("youtu.be"))
		assertEquals(false, YouTubeProbe.isYouTubeHost("notyoutube.com"))
		assertEquals(false, YouTubeProbe.isYouTubeHost("youtube.com.evil.net"))
		assertEquals(false, YouTubeProbe.isYouTubeHost(null))
	}

	@Test
	fun `foreign host path substrings cannot confirm browser identity`() {
		assertNotConfirmed(
			identifyMetadataUri("https://attacker.invalid/path/youtube.com/watch?v=dQw4w9WgXcQ"),
		)
		assertNotConfirmed(
			identifyMetadataUri(
				"https://attacker.invalid/i.ytimg.com/vi/dQw4w9WgXcQ/x",
				MediaMetadata.METADATA_KEY_ART_URI,
			),
		)
	}

	@Test
	fun `user info and host lookalikes cannot confirm browser identity`() {
		listOf(
			"https://youtube.com@attacker.invalid/watch?v=dQw4w9WgXcQ",
			"https://youtube.com.attacker.invalid/watch?v=dQw4w9WgXcQ",
			"https://notyoutube.com/watch?v=dQw4w9WgXcQ",
			"https://i.ytimg.com.attacker.invalid/vi/dQw4w9WgXcQ/x",
		).forEach { assertNotConfirmed(identifyMetadataUri(it)) }
	}

	@Test
	fun `query fragment and encoded embeddings cannot confirm browser identity`() {
		listOf(
			"https://attacker.invalid/?next=https://youtube.com/watch?v=dQw4w9WgXcQ",
			"https://attacker.invalid/#https://youtu.be/dQw4w9WgXcQ",
			"https://attacker.invalid/?next=https%3A%2F%2Fyoutube.com%2Fwatch%3Fv%3DdQw4w9WgXcQ",
		).forEach { assertNotConfirmed(identifyMetadataUri(it)) }
	}

	@Test
	fun `malformed and opaque media URIs fail closed`() {
		listOf(
			"youtube.com/watch?v=dQw4w9WgXcQ",
			"https://www.youtube.com/watch?v=%ZZ",
			"https:youtube.com/watch?v=dQw4w9WgXcQ",
		).forEach { assertNotConfirmed(identifyMetadataUri(it)) }
	}

	@Test
	fun `session bound contradictory notification vetoes valid media URI`() {
		val identity = identifyMetadataUri(
			"https://www.youtube.com/watch?v=dQw4w9WgXcQ",
			hintHost = "soundcloud.com",
		)

		assertTrue(identity is YouTubeProbe.Identity.Unconfirmed)
		assertTrue((identity as YouTubeProbe.Identity.Unconfirmed).provenOtherSite)
	}

	@Test
	fun `canonical browser media URIs remain confirmed`() {
		val cases = mapOf(
			"https://www.youtube.com/watch?v=dQw4w9WgXcQ" to false,
			"https://youtube.com/watch?v=dQw4w9WgXcQ" to false,
			"https://m.youtube.com/watch?v=dQw4w9WgXcQ" to false,
			"https://music.youtube.com/watch?v=dQw4w9WgXcQ" to true,
			"https://youtu.be/dQw4w9WgXcQ" to false,
		)

		cases.forEach { (uri, isMusic) ->
			val identity = identifyMetadataUri(uri) as YouTubeProbe.Identity.Confirmed
			assertEquals("dQw4w9WgXcQ", identity.videoId)
			assertEquals(isMusic, identity.isMusic)
		}
	}

	@Test
	fun `canonical browser artwork URI remains confirmed`() {
		listOf("i.ytimg.com", "i1.ytimg.com").forEach { host ->
			val identity = identifyMetadataUri(
				"https://$host/vi/dQw4w9WgXcQ/hqdefault.jpg",
				MediaMetadata.METADATA_KEY_ART_URI,
			) as YouTubeProbe.Identity.Confirmed

			assertEquals("dQw4w9WgXcQ", identity.videoId)
			assertFalse(identity.isMusic)
		}
	}

	@Test
	fun `browser metadata does not gain native-only URI routes`() {
		listOf(
			"https://www.youtube.com/shorts/dQw4w9WgXcQ",
			"https://www.youtube.com/embed/dQw4w9WgXcQ",
			"https://www.youtube.com/live/dQw4w9WgXcQ",
		).forEach { assertNotConfirmed(identifyMetadataUri(it)) }
		assertNotConfirmed(
			identifyMetadataUri(
				"https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/maxresdefault.webp",
				MediaMetadata.METADATA_KEY_ART_URI,
			),
		)
	}

	@Test
	fun `youtube notification preserves site only when media URI has no exact identity`() {
		val identity = identifyMetadataUri(
			"https://attacker.invalid/path/youtube.com/watch?v=dQw4w9WgXcQ",
			hintHost = "youtube.com",
		)

		assertTrue(identity is YouTubeProbe.Identity.SiteOnly)
	}

	@Test
	fun `hostless notification evidence does not veto a canonical media URI`() {
		val identity = YouTubeProbe.identify(
			md = metadataUri("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
			hint = hint(null),
		)

		assertTrue(identity is YouTubeProbe.Identity.Confirmed)
	}
}
