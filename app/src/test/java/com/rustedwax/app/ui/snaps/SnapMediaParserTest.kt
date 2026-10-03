package com.rustedwax.app.ui.snaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * What a Snap or reply previews, from its own words. The fixtures are the URL
 * shapes surveyed in `peak.snaps` comments on 2026-10-01.
 */
class SnapMediaParserTest {

	@Test
	fun `compressed narrow canvases cannot bypass the decode memory bound`() {
		assertNull(SnapMediaDecode.target(720, 60_000))
		assertNull(SnapMediaDecode.sample(720, 60_000))
		assertNull(SnapMediaDecode.target(Int.MAX_VALUE, Int.MAX_VALUE))
		assertNull(SnapMediaDecode.target(0, 100))
		assertNull(SnapMediaDecode.sample(100, -1))
	}

	@Test
	fun `accepted tall and wide images bound both decoded dimensions`() {
		listOf(640 to 480, 720 to 5000, 4000 to 720, 1920 to 1080).forEach { (w, h) ->
			val target = checkNotNull(SnapMediaDecode.target(w, h))
			assertTrue(target.first in 1..720)
			assertTrue(target.second in 1..1440)
			val sample = checkNotNull(SnapMediaDecode.sample(w, h))
			assertTrue((w + sample - 1) / sample <= 720)
			assertTrue((h + sample - 1) / sample <= 1440)
		}
		assertEquals(640 to 480, SnapMediaDecode.target(640, 480))
		assertEquals(1, SnapMediaDecode.sample(640, 480))
	}

	@Test
	fun `a response consumes at most one byte beyond its cap`() = runBlocking {
		val input = ByteArrayInputStream(ByteArray(100_000))
		assertEquals(101, SnapMediaRead.read(input, 100, System.nanoTime()).size)
		assertEquals(99_899, input.available())
		assertEquals(10, SnapMediaRead.read(ByteArrayInputStream(ByteArray(10)), 100, System.nanoTime()).size)
	}

	@Test
	fun `expired downloads stop before reading more bytes`() = runBlocking {
		val input = ByteArrayInputStream(ByteArray(10))
		val started = System.nanoTime() - (SnapMediaRead.MAX_DOWNLOAD_MS + 1) * 1_000_000
		try {
			SnapMediaRead.read(input, 100, started)
			error("expired response was accepted")
		} catch (_: SocketTimeoutException) {
			assertEquals(10, input.available())
		}
	}

	@Test
	fun `row cancellation stops a response after the active read`() = runBlocking {
		var reads = 0
		val child = launch {
			val job = checkNotNull(currentCoroutineContext()[Job])
			val input = object : InputStream() {
				override fun read(): Int {
					reads++
					job.cancel()
					return 0
				}
				override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
					read()
					return 1
				}
			}
			SnapMediaRead.read(input, 100, System.nanoTime())
			error("cancelled response was accepted")
		}
		child.join()
		assertTrue(child.isCancelled)
		assertEquals(1, reads)
	}

	private fun one(text: String) = SnapMediaParser.find(text).single()

	// ── images ─────────────────────────────────────────────────────────

	@Test
	fun `direct still images on the hosts Snaps use are previewed`() {
		listOf(
			"https://images.hive.blog/DQmYK5okC9tVpjGeZGsZ4dNhGMJm8H6rGAxF6DA6fgE5P9n/snap.png",
			"https://files.peakd.com/file/peakd-hive/renol/23zRxagVKw589FX3Fr3Dr9aeBsumhHFKC9o6TssU6f3ZRyp8xERLnWE5qEwhJ4z2U2Cwu.jpg",
			"https://i.ecency.com/DQmQV4Ss6vrwuMCQ8AUiaoEHziuFDxndQ1SRstMDoMQoday/1790877066620.JPEG",
			"https://i.imgur.com/XsrNmcl.webp",
		).forEach { url ->
			assertEquals(SnapMediaRef.Image(url, animated = false), one("look $url nice"))
		}
	}

	@Test
	fun `gifs are animated, query strings included`() {
		val klipy = "https://static.klipy.com/ii/84b4c0b02782dda9051003f9e36484ec/f6/18/dBaX8qXI.gif"
		val giphy = "https://media0.giphy.com/media/NryEdIZvKTHGHE0rvP/giphy.gif?cid=c1&ep=v1_gifs_search&rid=giphy.gif&ct=g"
		assertEquals(SnapMediaRef.Image(klipy, animated = true), one("![untitled.gif]($klipy)"))
		assertEquals(SnapMediaRef.Image(giphy, animated = true), one(giphy))
	}

	@Test
	fun `markdown image syntax yields just the url`() {
		val url = "https://images.hive.blog/DQm/a.png"
		assertEquals(url, one("![alt text]($url) trailing").source)
	}

	@Test
	fun `stills are fetched resized through the Hive proxy, gifs unresized`() {
		assertEquals(
			"https://images.hive.blog/640x0/https://x.example/a.png",
			SnapMediaRef.Image("https://x.example/a.png", false).fetchUrl,
		)
		assertEquals(
			"https://images.hive.blog/0x0/https://x.example/a.gif",
			SnapMediaRef.Image("https://x.example/a.gif", true).fetchUrl,
		)
	}

	// ── unsupported / malformed stay links ─────────────────────────────

	@Test
	fun `pages, embeds, extensionless and cleartext images are not previewed`() {
		listOf(
			"https://hivesuite.app/@bhattg/3k16717j",
			"https://ipfs.3speak.tv/ipfs/QmP7v6C5CNqLaYVXBcZmQcT4xVkfb3uiY9h2LFNAVJYQvc",
			"https://play.3speak.tv/embed?v=melihatbiru/y8xa1ks7",
			"https://www.reddit.com/r/FoggyPics/comments/1wu8syo/foggy_winter/",
			"http://images.hive.blog/DQm/a.png",
			"https://example.com/a.svg",
			"https://example.com/a.mp4",
			"https://example.com/folder.png/",
			"https://user:pw@example.com/a.png",
			"https://localhost/a.png",
			"https://exa mple.com/a.png",
			"ftp://example.com/a.png",
			"https://example.com/" + "a".repeat(2100) + ".png",
		).forEach { assertTrue(it, SnapMediaParser.find(it).isEmpty()) }
	}

	@Test
	fun `text with no links has no previews`() {
		assertTrue(SnapMediaParser.find("just words, youtu.be/abc and images.hive.blog").isEmpty())
		assertTrue(SnapMediaParser.find("").isEmpty())
	}

	// ── YouTube ────────────────────────────────────────────────────────

	@Test
	fun `youtube links resolve to the exact video id`() {
		val cases = mapOf(
			"https://youtu.be/UNaYpBpRJOY" to ("UNaYpBpRJOY" to false),
			"https://youtu.be/UNaYpBpRJOY?si=abc&t=30" to ("UNaYpBpRJOY" to false),
			"https://www.youtube.com/watch?v=fKtBfn4l2nI&list=PL1" to ("fKtBfn4l2nI" to false),
			"https://m.youtube.com/watch?feature=share&v=fKtBfn4l2nI" to ("fKtBfn4l2nI" to false),
			"https://music.youtube.com/watch?v=fKtBfn4l2nI" to ("fKtBfn4l2nI" to false),
			"https://www.youtube.com/shorts/QXDvYL2mSr0?si=f7Bw24W1it1zrOeH" to ("QXDvYL2mSr0" to true),
			"https://youtube.com/live/QXDvYL2mSr0" to ("QXDvYL2mSr0" to false),
		)
		cases.forEach { (url, want) ->
			val ref = one(url) as SnapMediaRef.YouTube
			assertEquals(url, want.first, ref.videoId)
			assertEquals(url, want.second, ref.isShort)
			assertEquals(url, ref.source)
		}
	}

	@Test
	fun `the open url names the same video, shorts as shorts`() {
		assertEquals(
			"https://www.youtube.com/watch?v=fKtBfn4l2nI",
			(one("https://youtu.be/fKtBfn4l2nI?t=9") as SnapMediaRef.YouTube).openUrl,
		)
		assertEquals(
			"https://www.youtube.com/shorts/QXDvYL2mSr0",
			(one("https://youtube.com/shorts/QXDvYL2mSr0") as SnapMediaRef.YouTube).openUrl,
		)
	}

	@Test
	fun `malformed youtube links are not previewed`() {
		listOf(
			"https://youtu.be/",
			"https://youtu.be/short",
			"https://youtu.be/UNaYpBpRJOYx",
			"https://www.youtube.com/watch?v=",
			"https://www.youtube.com/watch?v=aaaaaaaaaaa&v=bbbbbbbbbbb",
			"https://www.youtube.com/channel/UCabcdefghijk",
			"https://www.youtube.com/feed/history",
			"https://notyoutube.com/watch?v=fKtBfn4l2nI",
			"https://youtube.com.evil.example/watch?v=fKtBfn4l2nI",
			"https://www.youtube.com/shorts/bad\$id12345",
		).forEach { assertTrue(it, SnapMediaParser.find(it).isEmpty()) }
	}

	// ── ordering, duplicates, bounds ───────────────────────────────────

	@Test
	fun `order is as written, duplicates once, at most four`() {
		val text = "https://youtu.be/AAAAAAAAAAA https://x.example/1.png " +
			"https://www.youtube.com/watch?v=AAAAAAAAAAA https://x.example/1.png " +
			"https://x.example/2.gif https://x.example/3.jpg https://x.example/4.jpg"
		val found = SnapMediaParser.find(text)
		// Four since Issue 40D, so a comment with four attached images shows them all.
		assertEquals(4, SnapMediaParser.MAX_PER_COMMENT)
		assertEquals(SnapMediaParser.MAX_PER_COMMENT, found.size)
		assertEquals(
			listOf(
				"https://youtu.be/AAAAAAAAAAA", "https://x.example/1.png",
				"https://x.example/2.gif", "https://x.example/3.jpg",
			),
			found.map { it.source },
		)
	}

	@Test
	fun `sentence punctuation after a link is not part of it`() {
		assertEquals("https://x.example/a.png", one("see https://x.example/a.png.").source)
		assertEquals("https://youtu.be/AAAAAAAAAAA", one("(https://youtu.be/AAAAAAAAAAA), wow").source)
	}

	@Test
	fun `links keep their place in the text so they can be drawn tappable`() {
		val text = "a https://x.example/p b http://y.example/q."
		val links = SnapMediaParser.links(text)
		assertEquals(listOf("https://x.example/p", "http://y.example/q"), links.map { it.second })
		links.forEach { (range, url) -> assertEquals(url, text.substring(range.first, range.last + 1)) }
	}

	// ── edit refresh ───────────────────────────────────────────────────

	@Test
	fun `an edit that changes the link changes the preview`() {
		val before = SnapMediaParser.find("old https://youtu.be/AAAAAAAAAAA")
		val after = SnapMediaParser.find("new https://x.example/a.gif")
		assertTrue(before != after)
		assertTrue(SnapMediaParser.find("edited to plain words").isEmpty())
	}

	// ── opening ────────────────────────────────────────────────────────

	@Test
	fun `youtube opens in the YouTube app first, then any handler, never in RustedWax`() {
		val ref = one("https://youtu.be/fKtBfn4l2nI") as SnapMediaRef.YouTube
		assertEquals(
			listOf(
				"com.google.android.youtube" to "https://www.youtube.com/watch?v=fKtBfn4l2nI",
				null to "https://www.youtube.com/watch?v=fKtBfn4l2nI",
			),
			SnapMediaOpen.youtube(ref),
		)
		SnapMediaOpen.youtube(ref).forEach { assertTrue(it.first != "com.rustedwax.app") }
	}

	@Test
	fun `only web links open externally`() {
		assertEquals(listOf(null to "https://x.example/a"), SnapMediaOpen.link("https://x.example/a"))
		assertTrue(SnapMediaOpen.link("javascript:alert(1)").isEmpty())
		assertTrue(SnapMediaOpen.link("intent://x#Intent;end").isEmpty())
		assertTrue(SnapMediaOpen.link("not a url").isEmpty())
	}

	// ── fetch verdicts ─────────────────────────────────────────────────

	@Test
	fun `failed loads fall back, and only definite failures are remembered`() {
		val cap = SnapMediaFetch.MAX_STILL_BYTES
		assertTrue(SnapMediaFetch.classify(200, 10, cap) is SnapMediaFetch.Body)
		assertEquals(SnapMediaFetch.Absent, SnapMediaFetch.classify(200, 0, cap))
		assertEquals(SnapMediaFetch.Absent, SnapMediaFetch.classify(200, cap + 1, cap))
		assertEquals(SnapMediaFetch.Absent, SnapMediaFetch.classify(400, 0, cap))
		assertEquals(SnapMediaFetch.Absent, SnapMediaFetch.classify(404, 0, cap))
		assertEquals(SnapMediaFetch.Unavailable, SnapMediaFetch.classify(503, 0, cap))
		assertEquals(SnapMediaFetch.Unavailable, SnapMediaFetch.classify(-1, 0, cap))
		assertNull(SnapMediaParser.recognise("https://x.example/a.txt"))
	}

	// ── proxy redirects ────────────────────────────────────────────────

	@Test
	fun `the proxy's own redirect to its p form is followed`() {
		// The deployed images.hive.blog answer to every resize request (2026-10-01).
		val start = "https://images.hive.blog/640x0/https://images.hive.blog/DQm/snap.png"
		val loc = "https://images.hive.blog/p/62PdCouTvNPDFdpB?format=match&mode=fit&width=640"
		assertEquals(loc, SnapMediaRedirect.next(start, loc))
		assertEquals(
			"https://images.hive.blog/p/abc?format=match",
			SnapMediaRedirect.next(start, "/p/abc?format=match"),
		)
		assertEquals(
			"https://images.hive.blog/p/abc",
			SnapMediaRedirect.next(start, "https://IMAGES.HIVE.BLOG:443/p/abc")?.lowercase()?.replace(":443", ""),
		)
	}

	@Test
	fun `a redirect off the proxy's origin is never followed`() {
		val start = "https://images.hive.blog/0x0/https://github.com/github.png"
		listOf(
			"https://avatars.githubusercontent.com/u/9919?v=4",
			"http://images.hive.blog/p/abc",
			"https://images.hive.blog.evil.example/p/abc",
			"https://evil.example/images.hive.blog/p/abc",
			"https://images.hive.blog:8443/p/abc",
			"https://user@images.hive.blog/p/abc",
			"//avatars.githubusercontent.com/u/1",
			"intent://x#Intent;end",
			"",
			null,
		).forEach { assertNull(it.toString(), SnapMediaRedirect.next(start, it)) }
	}
}
