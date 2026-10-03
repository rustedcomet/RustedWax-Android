package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.PostedSnapBody
import com.rustedwax.app.snaps.SnapMedia
import com.rustedwax.app.snaps.SnapPayloadBuilder
import com.rustedwax.app.snaps.SnapReply
import com.rustedwax.app.snaps.SnapThreadBuilder
import com.rustedwax.app.snaps.SnapThreadPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 40C presentation: what a comment and a History card print once their
 * media is drawn as media. The body itself is never an output here.
 */
class SnapMediaTextTest {

	private val png = "https://images.hive.blog/DQmX/photo.png"
	private val gif = "https://media1.giphy.com/media/abc/giphy.gif"
	private val gif2 = "https://static.klipy.com/ii/x/y.gif"
	private val yt = "https://youtu.be/fKtBfn4l2nI"
	private val page = "https://peakd.com/@someone/a-post"

	@Test
	fun `a previewed media URL is not printed above its preview`() {
		val shown = SnapMediaText.display("so good $gif")
		assertEquals("so good", shown.text)
		assertEquals(listOf(SnapMediaRef.Image(gif, animated = true)), shown.media)
		assertFalse(shown.text.contains("giphy"))
	}

	@Test
	fun `surrounding prose is kept character for character`() {
		assertEquals("look at this\nand this one!", SnapMediaText.display("look at this\n$png\nand this one!").text)
		assertEquals("before after", SnapMediaText.display("before $png after").text)
		assertEquals("see.", SnapMediaText.display("see $png.").text)
		assertEquals("café ☕  two  spaces", SnapMediaText.display("café ☕  two  spaces\n$png").text)
		assertEquals("a\n\nb", SnapMediaText.display("a\n\nb $yt").text)
	}

	@Test
	fun `YouTube previews leave the text too, every spelling of the same video`() {
		val shown = SnapMediaText.display("$yt\nwatch https://www.youtube.com/watch?v=fKtBfn4l2nI")
		assertEquals("watch", shown.text)
		assertEquals(1, shown.media.size)
	}

	@Test
	fun `markdown image syntax goes with its link`() {
		assertEquals("lol", SnapMediaText.display("lol ![gif]($gif)").text)
		assertEquals("lol", SnapMediaText.display("![]($gif)\nlol").text)
	}

	@Test
	fun `unsupported and unpreviewed links stay in the text`() {
		val shown = SnapMediaText.display("read $page and $png")
		assertEquals("read $page and", shown.text)
		assertEquals(listOf(page), SnapMediaParser.links(shown.text).map { it.second })
		// http images are never fetched, so never previewed, so never hidden.
		assertEquals("http://x.org/a.png", SnapMediaText.display("http://x.org/a.png").text)
		// Past the per-comment cap a link is only a link, and stays one.
		val six = (1..6).joinToString(" ") { "https://images.hive.blog/p$it.png" }
		val capped = SnapMediaText.display(six)
		assertEquals(SnapMediaParser.MAX_PER_COMMENT, capped.media.size)
		assertEquals("https://images.hive.blog/p5.png https://images.hive.blog/p6.png", capped.text)
	}

	@Test
	fun `a comment that is only media prints no text block`() {
		assertEquals("", SnapMediaText.display(gif).text)
		assertEquals("", SnapMediaText.display("  $png  \n\n$gif\n").text)
	}

	@Test
	fun `text without media is returned untouched`() {
		val text = "  hello\n\n world  "
		assertEquals(text, SnapMediaText.display(text).text)
		assertEquals(text, SnapMediaText.history(text).text)
	}

	@Test
	fun `the original body is not altered and stays available for Edit`() {
		val body = "ok $png"
		val copy = String(body.toCharArray())
		SnapMediaText.display(body)
		SnapMediaText.history(body)
		assertEquals(copy, body)
		// Display is derived each time: editing the link out redraws without it.
		assertTrue(SnapMediaText.display("ok").media.isEmpty())
		assertEquals("ok", SnapMediaText.display("ok").text)
	}

	@Test
	fun `History root with media gets one compact still thumbnail of the first image or GIF`() {
		val card = SnapMediaText.history("first $gif then $png and $gif2")
		assertEquals("first then and", card.text)
		assertEquals(SnapMediaRef.Image(gif, animated = true), card.thumbnail)
		// Fetched as a still: the proxy's 640 px resize keeps only frame one.
		val still = checkNotNull(card.thumbnailStill)
		assertFalse(still.animated)
		assertEquals("https://images.hive.blog/640x0/$gif", still.fetchUrl)
		assertFalse(card.showsReplyPreview)
	}

	@Test
	fun `History suppresses the reply preview only when the root carries an image or GIF`() {
		assertFalse(SnapMediaText.history(png).showsReplyPreview)
		assertFalse(SnapMediaText.history("x $gif").showsReplyPreview)
		assertTrue(SnapMediaText.history("just words").showsReplyPreview)
		assertTrue(SnapMediaText.history("a link $page").showsReplyPreview)
		// A YouTube link the user typed is not an image: old behaviour.
		assertTrue(SnapMediaText.history("listen $yt").showsReplyPreview)
	}

	@Test
	fun `non-media History cards keep their text and no thumbnail`() {
		listOf("just words", "a link $page", "listen $yt", "").forEach {
			val card = SnapMediaText.history(it)
			assertEquals(it, card.text)
			assertNull(card.thumbnail)
			assertNull(card.thumbnailStill)
		}
	}

	/**
	 * The A36 card that reopened 40C: root `vrurzh` edited to plain words, so
	 * its reply strip came back — and reply `s0h50w`, edited after the polish
	 * gate, is words plus an image link. The strip printed that URL verbatim.
	 */
	@Test
	fun `a History reply strip line does not print an image or GIF URL`() {
		val reply = "RustedWax40C-retestD h https://github.com/github.png"
		assertEquals("RustedWax40C-retestD h", SnapMediaText.previewLine(reply))
		assertEquals("so good", SnapMediaText.previewLine("so good $gif"))
		assertEquals("look\nnow", SnapMediaText.previewLine("look\n$png\nnow"))
		assertFalse(SnapMediaText.previewLine("x ![g]($gif) y").contains("giphy"))
	}

	@Test
	fun `a strip line that was only media says which kind instead of its URL`() {
		assertEquals("GIF", SnapMediaText.previewLine(gif))
		assertEquals("Image", SnapMediaText.previewLine(png))
		assertEquals("GIF", SnapMediaText.previewLine("  $gif\n$png "))
	}

	@Test
	fun `strip lines without image media are unchanged`() {
		listOf("just words", "a link $page", "listen $yt", "http://x.org/a.png", "", "  edged  ").forEach {
			assertEquals(it, SnapMediaText.previewLine(it))
		}
	}

	/**
	 * The strip is bounded to [SnapThreadPreview.MAX_PREVIEW_CHARS]. A URL that
	 * straddles that bound must not survive as a fragment whose extension was
	 * cut off — the media rule has to see the whole link before the cut.
	 */
	@Test
	fun `preview truncation cannot expose a partial image or GIF URL`() {
		val prose = "x".repeat(SnapThreadPreview.MAX_PREVIEW_CHARS - 20)
		listOf(png, gif, "![g]($gif)").forEach { link ->
			val body = "$prose $link tail words"
			val reply = SnapReply("bob", "rustedwax-reply-1-aaaaaa", "alice", "rustedwax-snap-1000-aaaaaa", body, 1L)
			val thread = SnapThreadBuilder.build("alice/rustedwax-snap-1000-aaaaaa", listOf(reply))
			// Without the rule, the clamp alone leaves a fragment: the bug.
			assertTrue(SnapThreadPreview.of(thread).items.single().text.contains("https"))
			// The path SnapThreading takes: the rule sees the whole body first.
			val preview = SnapThreadPreview.of(thread, SnapMediaText::previewLine)
			val line = SnapMediaText.previewLine(preview.items.single().text)
			assertFalse("partial URL shown for $link: $line", line.contains("https"))
			assertTrue(line.startsWith(prose))
		}
	}

	@Test
	fun `the stored strip restored after a restart still hides the URL`() {
		// The refresh path a reopened card takes: the summary is written to
		// disk as raw reply text and read back before the chain answers.
		val stored = SnapThreadPreview.Preview(
			items = listOf(
				SnapThreadPreview.Item("skiptvads", "rustedwax-reply-1790892598-s0h50w", "hi https://github.com/github.png", false),
			),
			total = 1,
		)
		val restored = checkNotNull(SnapThreadPreviewCodec.decode(SnapThreadPreviewCodec.encode(stored, 1L)))
		assertEquals("hi https://github.com/github.png", restored.items.single().text)
		assertEquals("hi", SnapMediaText.previewLine(restored.items.single().text))
	}

	@Test
	fun `editing root media out brings the ordinary strip back, and in again hides it`() {
		val withGif = SnapMediaText.history("RustedWax40C-polish $gif")
		assertFalse(withGif.showsReplyPreview)
		assertEquals("RustedWax40C-polish", withGif.text)
		val edited = SnapMediaText.history("RustedWax40C-polish-EDITED")
		assertTrue(edited.showsReplyPreview)
		assertNull(edited.thumbnailStill)
		assertEquals("RustedWax40C-polish-EDITED", edited.text)
		assertNotNull(SnapMediaText.history("again $png").thumbnailStill)
	}

	@Test
	fun `RustedWax's own appended YouTube link never becomes Snap media`() {
		val body = SnapPayloadBuilder.build("nice", SnapMedia(videoId = "fKtBfn4l2nI")).body
		val userText = checkNotNull(PostedSnapBody.userText(body))
		assertEquals("nice", userText)
		val card = SnapMediaText.history(userText)
		assertNull(card.thumbnail)
		assertEquals("nice", card.text)
		assertTrue(SnapMediaText.display(userText).media.isEmpty())
	}
}
