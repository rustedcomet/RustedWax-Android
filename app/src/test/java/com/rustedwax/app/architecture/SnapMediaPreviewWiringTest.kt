package com.rustedwax.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 40C, asserted where behaviour cannot reach: previews are drawn in the
 * one place both a root Snap and a reply are drawn, and nothing behind a
 * preview can write to Hive, start playback inside RustedWax, or feed the
 * scrobble engine.
 */
class SnapMediaPreviewWiringTest {

	private val root: File by lazy {
		generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) {
			it.parentFile
		}.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("repository root was not found")
	}

	private fun code(path: String): String = File(root, path).let {
		assertTrue("production source missing: $path", it.isFile)
		it.readText()
			.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
			.replace(Regex("//[^\n]*"), " ")
	}

	private val dir = "app/src/main/java/com/rustedwax/app/ui/snaps"
	private val sheet by lazy { code("$dir/SnapThreadSheet.kt") }
	private val media by lazy { code("$dir/SnapMedia.kt") + code("$dir/SnapMediaPreview.kt") }

	@Test
	fun `root and reply share one comment renderer, and it is the only preview call`() {
		assertEquals(1, Regex("""SnapMediaPreviews\(""").findAll(sheet).count())
		val block = sheet.substringAfter("private fun CommentBlock(").substringBefore("\n@Composable")
		// Both draw from one display of the body: previewed links leave the
		// text, and only the text is printed.
		assertTrue(block.contains("SnapMediaText.display(body)"))
		assertTrue(block.contains("SnapMediaPreviews(shown.media, thumbnails)"))
		assertTrue(block.contains("shown.text.takeIf"))
		assertTrue(block.contains("SnapLinkedText(it"))
		assertFalse(block.contains("SnapLinkedText(body"))
		// The root item and every reply both go through CommentBlock.
		val rootItem = sheet.substringAfter("item(key = \"root\")").substringBefore("HorizontalDivider")
		assertTrue(rootItem.contains("CommentBlock("))
		val reply = sheet.substringAfter("private fun ReplyBlock(").substringBefore("\n@Composable")
		assertTrue(reply.contains("CommentBlock("))
		assertTrue(reply.contains("thumbnails = thumbnails"))
	}

	@Test
	fun `nothing behind a preview can write, scrobble, or play inside the app`() {
		listOf(
			"HiveBroadcaster", "SnapHivePort", "SnapPublisher", "SnapDeleter", "SnapEditor",
			"broadcast", "Finalization", "Scrobble", "PlaybackTrace", "MediaSession",
			"WebView", "ExoPlayer", "MediaPlayer", "VideoView", "YouTubePlayer",
		).forEach { assertFalse("media code references $it", media.contains(it)) }
	}

	@Test
	fun `the thread sheet passes the History thumbnail consent through`() {
		val main = code("app/src/main/java/com/rustedwax/app/ui/MainScreen.kt")
		assertTrue(main.contains("thumbnails = thumbnails,\n\t\t)"))
	}

	@Test
	fun `Edit still starts from the stored words, never the displayed ones`() {
		assertTrue(sheet.contains("threads.startEdit(root, root, SnapEditKind.ROOT, it.userText)"))
		assertTrue(sheet.contains("threads.startEdit(root, target, SnapEditKind.REPLY, reply.body)"))
		assertFalse(sheet.contains("startEdit(root, root, SnapEditKind.ROOT, shown"))
	}

	@Test
	fun `History draws the root's media from its authored words and gates the reply strip on it`() {
		val card = code("$dir/PostedSnapCard.kt")
		assertTrue(card.contains("SnapMediaText.history(posted.userText)"))
		assertTrue(card.contains("shown.text.takeIf"))
		assertTrue(card.contains("SnapHistoryThumbnail("))
		assertTrue(card.contains("shown.thumbnailStill"))
		// No animation anywhere on the History card.
		assertFalse(card.contains("AnimatedPreview"))
		assertFalse(card.contains("SnapMediaPreviews("))
		val main = code("app/src/main/java/com/rustedwax/app/ui/MainScreen.kt")
		assertTrue(main.contains("!SnapMediaText.history(published?.userText.orEmpty()).showsReplyPreview"))
		assertTrue(main.contains("threads.preview(root)?.takeUnless { rootHasImage }"))
		// The Comments count still comes from the same preview, ungated.
		assertTrue(main.contains("val replies = root?.let { threads.preview(it)?.total } ?: 0"))
		listOf("HiveBroadcaster", "SnapPublisher", "SnapDeleter", "SnapEditor", "broadcast", "Scrobble")
			.forEach { assertFalse("History card references $it", card.contains(it)) }
	}

	@Test
	fun `the History reply strip prints each line through the media rule`() {
		val strip = sheet.substringAfter("internal fun SnapThreadPreviewStrip(").substringBefore("\n}\n")
		assertTrue(strip.contains("SnapMediaText.previewLine(item.text)"))
		assertFalse(strip.contains("item.text + "))
		assertFalse(strip.contains("else item.text"))
		// Still no media drawn on the History list from a reply.
		assertFalse(strip.contains("SnapHistoryThumbnail("))
		assertFalse(strip.contains("SnapMediaPreviews("))
	}

	@Test
	fun `History previews are built with the media rule applied before the clamp`() {
		val threading = code("$dir/SnapThreading.kt")
		assertEquals(2, Regex("""SnapThreadPreview\.of\(built, SnapMediaText::previewLine\)""").findAll(threading).count())
		assertFalse(threading.contains("SnapThreadPreview.of(built)"))
	}
}
