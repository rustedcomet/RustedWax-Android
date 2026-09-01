package com.rustedwax.app.enrich

import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.app.detect.ScrobbleBuilder
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.SourceProof
import com.rustedwax.app.detect.YouTubeProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identity for a Short whose on-screen title could not be read.
 *
 * The title was never authority — it was a selector. These pin that dropping it
 * costs one of three agreeing fields and keeps the two YouTube cannot restyle,
 * and that the single-match rule is untouched.
 */
class UntitledShortIdentityTest {

	private fun candidate(id: String, title: String, handle: String, length: Long) =
		VideoResolution(
			videoId = id,
			source = "test",
			title = title,
			channel = "Channel",
			ownerHandle = handle,
			lengthSeconds = length,
		)

	@Test
	fun `owner handle and duration alone can resolve uniquely`() {
		val attempt = VideoIdResolver().selectOwnerHandleMatch(
			candidates = listOf(
				candidate("aaaaaaaaaaa", "Whatever the screen could not read", "@creator", 50),
				candidate("bbbbbbbbbbb", "A different Short", "@creator", 22),
				candidate("ccccccccccc", "Same length, other channel", "@someoneelse", 50),
			),
			title = null,
			ownerHandle = "@creator",
			durationSec = 50,
		)
		assertEquals("aaaaaaaaaaa", attempt.resolution?.videoId)
	}

	@Test
	fun `same channel same length is broken by watch-history recency`() {

		val attempt = VideoIdResolver().selectOwnerHandleMatch(
			candidates = listOf(
				candidate("aaaaaaaaaaa", "Watched just now", "@creator", 50),
				candidate("bbbbbbbbbbb", "Watched last week", "@creator", 50),
			),
			title = null,
			ownerHandle = "@creator",
			durationSec = 50,
		)
		assertEquals("aaaaaaaaaaa", attempt.resolution?.videoId)
	}

	@Test
	fun `recency never rescues a candidate that fails handle or duration`() {
		// Recency is a tie-break among survivors, never a way in. A newest entry
		// that disagrees on either field is still refused.
		val resolver = VideoIdResolver()
		assertNull(
			resolver.selectOwnerHandleMatch(
				candidates = listOf(
					candidate("aaaaaaaaaaa", "Newest but wrong length", "@creator", 91),
					candidate("bbbbbbbbbbb", "Older, wrong channel", "@other", 50),
				),
				title = null,
				ownerHandle = "@creator",
				durationSec = 50,
			).resolution,
		)
	}

	@Test
	fun `with a title present two matches still refuse`() {
		// The rule only changes where the title is genuinely unavailable. When
		// one exists and two uploads still match all three fields, that is real
		// ambiguity and it fails closed exactly as before.
		val attempt = VideoIdResolver().selectOwnerHandleMatch(
			candidates = listOf(
				candidate("aaaaaaaaaaa", "Same title", "@creator", 50),
				candidate("bbbbbbbbbbb", "Same title", "@creator", 50),
			),
			title = "Same title",
			ownerHandle = "@creator",
			durationSec = 50,
		)
		assertNull(attempt.resolution)
		assertTrue(attempt.refusalReason!!.contains("ambiguous identity"))
	}

	@Test
	fun `a wrong-length or wrong-channel candidate is never accepted`() {
		val resolver = VideoIdResolver()
		assertNull(
			resolver.selectOwnerHandleMatch(
				candidates = listOf(candidate("aaaaaaaaaaa", "One", "@creator", 91)),
				title = null,
				ownerHandle = "@creator",
				durationSec = 50,
			).resolution,
		)
		assertNull(
			resolver.selectOwnerHandleMatch(
				candidates = listOf(candidate("aaaaaaaaaaa", "One", "@othercreator", 50)),
				title = null,
				ownerHandle = "@creator",
				durationSec = 50,
			).resolution,
		)
	}

	@Test
	fun `supplying a title still tightens the gate`() {
		// Null must widen nothing that a present title would have caught.
		val attempt = VideoIdResolver().selectOwnerHandleMatch(
			candidates = listOf(candidate("aaaaaaaaaaa", "The real title", "@creator", 50)),
			title = "Something else entirely",
			ownerHandle = "@creator",
			durationSec = 50,
		)
		assertNull(attempt.resolution)
	}

	@Test
	fun `a Short with no on-screen title still builds a payload`() {

		val session = SessionSnapshot(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube Shorts (foreground)",
			isTarget = true,
			title = null,
			artist = "@creator",
			album = null,
			durationMs = 33_000,
			positionMs = 0,
			playedMs = 33_000,
			loopDetected = false,
			playbackState = "FOREGROUND_SHORT",
			isPlaying = false,
			percentPlayed = 1.0,
			identity = YouTubeProbe.Identity.SiteOnly(
				host = YouTubeProbe.YOUTUBE_PACKAGE,
				isMusic = false,
				source = "foreground native Shorts accessibility player",
			),
			notificationHint = null,
			metadataLines = emptyList(),
			trackStartedAtEpochSec = 0,
			sourceProof = SourceProof.NATIVE_FOREGROUND_SHORT,
			ownerHandle = "@creator",
		)
		val payload = ScrobbleBuilder.from(
			session = session,
			videoId = "aaaaaaaaaaa",
			durationMs = 33_000,
			resolvedTitle = "PA TI TOA",
		)
		assertEquals("PA TI TOA", payload?.title)
		assertEquals("https://www.youtube.com/watch?v=aaaaaaaaaaa", payload?.url)
	}
}
