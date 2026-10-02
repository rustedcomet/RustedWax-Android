package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.PendingSnap
import com.rustedwax.app.snaps.PendingSnapKind
import com.rustedwax.app.snaps.PendingSnapRead
import com.rustedwax.app.snaps.PendingSnapState
import com.rustedwax.app.snaps.PendingSnapStore
import com.rustedwax.app.snaps.PostedSnap
import com.rustedwax.app.snaps.PostedSnapContent
import com.rustedwax.app.snaps.PostedSnapReader
import com.rustedwax.app.snaps.PostedSnaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 40C: where a link sits in a root Snap's chain body says nothing about
 * who wrote it. In the exact v1 shape RustedWax subtracts its generated tail.
 * In any body another frontend reshaped, it removes only the exact
 * `https://youtu.be/{id}` it attached to this same Snap — and only when that
 * link appears exactly once. The generic hashtags are no longer identifiable
 * once the structure is gone, so every occurrence of them stays: showing an
 * extra hashtag beats hiding one the author typed.
 *
 * Driven through [PostedSnaps.refreshed], the one place a chain body becomes
 * the text the History card and the Comments root draw.
 */
class SnapBodyMediaPositionTest {

	private val account = "alice"
	private val eventId = "evt-40c"
	private val vid = "7LnBvuzjpr4"
	private val generated = "https://youtu.be/$vid"
	private val tags = "#scrobblelife #scrobble #rustedwax"
	private val photo = "https://example.com/photo.jpg"
	private val gif = "https://static.klipy.com/ii/x/y.gif"

	/** What RustedWax itself published, exactly as stored locally. */
	private val published = "My comment\n\n$generated\n\n$tags"

	private class Store(private val snap: PendingSnap) : PendingSnapStore {
		override fun read(account: String, eventId: String): PendingSnapRead =
			if (account == snap.account && eventId == snap.eventId) PendingSnapRead.Present(snap)
			else PendingSnapRead.Absent
		override fun write(snap: PendingSnap) = true
		override fun clear(account: String, eventId: String) = Unit
		override fun all(account: String) = listOf(snap)
		override fun corruptEventIds(account: String) = emptySet<String>()
	}

	private fun record(body: String) = PendingSnap(
		account = account,
		eventId = eventId,
		author = account,
		permlink = "rustedwax-snap-1000-aaaaaa",
		parentAuthor = "peak.snaps",
		parentPermlink = "snap-container-1",
		body = body,
		jsonMetadata = """{"app":"rustedwax/test"}""",
		signedTransactionJson = """{"op":"x"}""",
		txId = "tx-1",
		expirationEpochSec = 2_000_000_000L,
		state = PendingSnapState.CONFIRMED,
		createdAtEpochSec = 900L,
		updatedAtEpochSec = 900L,
		kind = PendingSnapKind.ROOT,
	)

	/** The card after reading [chainBody] back from Hive. */
	private fun card(chainBody: String): PostedSnap {
		val reader = object : PostedSnapReader {
			override fun read(author: String, permlink: String) = PostedSnapContent(chainBody, 1_000L)
		}
		return checkNotNull(PostedSnaps(Store(record(published)), reader).refreshed(account, eventId))
	}

	private fun history(chainBody: String) = SnapMediaText.history(card(chainBody).userText)

	private fun assertNoGeneratedUrl(text: String) {
		assertFalse("generated URL shown: $text", Regex("(^|\\s)${Regex.escape(generated)}(\\s|$)").containsMatchIn(text))
	}

	private fun count(text: String, tag: String) =
		Regex("(^|\\s)${Regex.escape(tag)}(?=\\s|$)").findAll(text).count()

	@Test
	fun `image before the generated tail`() {
		val shown = history("My comment $photo\n\n$generated\n\n$tags")
		assertEquals("My comment", shown.text)
		assertEquals(photo, shown.thumbnail?.source)
	}

	@Test
	fun `image after all the generated tags keeps every hashtag`() {
		val text = card("$published\n$photo").userText
		assertEquals("My comment\n\n$tags\n$photo", text)
		assertNoGeneratedUrl(text)
		val shown = SnapMediaText.history(text)
		assertEquals(photo, shown.thumbnail?.source)
		assertFalse(shown.text.contains(photo))
	}

	@Test
	fun `image between the generated tags keeps the tags`() {
		val body = "My comment\n\n$generated\n\n#scrobblelife $photo #scrobble #rustedwax"
		val text = card(body).userText
		assertEquals("My comment\n\n#scrobblelife $photo #scrobble #rustedwax", text)
		assertEquals(photo, SnapMediaText.history(text).thumbnail?.source)
	}

	@Test
	fun `prose and image after the tags`() {
		val shown = history("$published\nlook at this $photo")
		assertEquals(photo, shown.thumbnail?.source)
		assertTrue(shown.text.contains("My comment"))
		assertTrue(shown.text.contains("look at this"))
		assertTrue(shown.text.contains(tags))
		assertFalse(shown.text.contains(photo))
	}

	@Test
	fun `GIF after the tags`() {
		val shown = history("$published\n\n$gif")
		assertEquals(SnapMediaRef.Image(gif, animated = true), shown.thumbnail)
		assertFalse(checkNotNull(shown.thumbnailStill).animated)
	}

	@Test
	fun `ordinary link after the tags stays visible text`() {
		val page = "https://peakd.com/@someone/a-post"
		val shown = history("$published\n$page")
		assertNull(shown.thumbnail)
		assertTrue(shown.text.contains(page))
		assertTrue(shown.showsReplyPreview)
	}

	@Test
	fun `the associated generated URL is hidden wherever it sits when it appears once`() {
		listOf(published, "$published\n$photo", "$tags\n$generated\nMy comment").forEach { body ->
			val text = card(body).userText
			assertNoGeneratedUrl(text)
			assertTrue(SnapMediaText.display(text).media.none { it is SnapMediaRef.YouTube && it.videoId == vid })
		}
	}

	@Test
	fun `the generated URL appearing twice is ambiguous and both copies stay`() {
		val body = "watch $generated again\n\n$generated\n\n$tags\n$photo"
		assertEquals(body, card(body).userText)
	}

	@Test
	fun `a YouTube URL this Snap's stored body did not generate is never removed`() {
		// Stored association names a different video: the chain link is not ours.
		val body = "$tags\n$generated\nMy comment"
		val reader = object : PostedSnapReader {
			override fun read(author: String, permlink: String) = PostedSnapContent(body, 1_000L)
		}
		val stored = record("My comment\n\nhttps://youtu.be/dQw4w9WgXcQ\n\n$tags")
		val text = checkNotNull(PostedSnaps(Store(stored), reader).refreshed(account, eventId)).userText
		assertEquals(body, text)
	}

	@Test
	fun `a different YouTube URL typed by the user stays visible and previewable`() {
		val other = "https://youtu.be/dQw4w9WgXcQ"
		listOf("My comment $other\n\n$generated\n\n$tags", "$published\n$other").forEach { body ->
			val text = card(body).userText
			assertTrue("user YouTube link lost: $text", text.contains(other))
			assertEquals(
				listOf("dQw4w9WgXcQ"),
				SnapMediaText.display(text).media.filterIsInstance<SnapMediaRef.YouTube>().map { it.videoId },
			)
		}
	}

	@Test
	fun `a body another frontend rearranged keeps the author's media and the tags`() {
		val body = "$tags\n$generated\nMy words $gif"
		val text = card(body).userText
		assertEquals("$tags\n\nMy words $gif", text)
		assertEquals(gif, SnapMediaText.history(text).thumbnail?.source)
	}

	@Test
	fun `a body with the generated tail removed elsewhere is shown as published`() {
		val shown = history("My new text $photo")
		assertEquals("My new text", shown.text)
		assertEquals(photo, shown.thumbnail?.source)
	}

	@Test
	fun `the author's own hashtag before the exact tail is kept`() {
		val text = card("love it #scrobble\n\n$generated\n\n$tags").userText
		assertEquals("love it #scrobble", text)
	}

	@Test
	fun `a duplicate generic hashtag typed after the generated one is preserved`() {
		val body = "My comment\n\n$generated\n\n$tags #scrobble"
		val text = card(body).userText
		assertEquals(2, count(text, "#scrobble"))
		assertEquals(1, count(text, "#scrobblelife"))
		assertEquals(1, count(text, "#rustedwax"))
	}

	@Test
	fun `no generic hashtag is removed by first, last or count once the shape is gone`() {
		listOf(
			"#scrobble first\n$generated\n$tags",
			"$tags\n$tags\n$generated",
			"#rustedwax #rustedwax $generated #scrobblelife",
		).forEach { body ->
			val text = card(body).userText
			listOf("#scrobblelife", "#scrobble", "#rustedwax").forEach { tag ->
				assertEquals("$tag in: $body", count(body, tag), count(text, tag))
			}
		}
	}

	@Test
	fun `any character the author used survives removal of the generated URL`() {
		val text = card("$published\nodd \u0000 chars \u202e $photo").userText
		assertEquals("My comment\n\n$tags\nodd \u0000 chars \u202e $photo", text)
		assertEquals(photo, SnapMediaText.history(text).thumbnail?.source)
	}

	@Test
	fun `the exact v1 body is unchanged, byte for byte`() {
		listOf("My comment", "  padded  ", "a\n\nb", "x $photo").forEach {
			assertEquals(it, card("$it\n\n$generated\n\n$tags").userText)
		}
	}
}
