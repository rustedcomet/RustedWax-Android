package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the watch-history feed is allowed to conclude.
 *
 * The cases are the measured duplicate-upload pairs from
 * `<redacted-private-path>` §10.1 — `Criminal` at `4ns8D959YtA` vs
 * `VqEbCxg2bNI` and `Unica` at `YbZwlNmnUvw` vs `7uxTya2PX3c`, each pair exactly
 * duration-identical. Those are precisely the pairs the search route was
 * measured getting wrong, so they are the pairs this route has to get right or
 * refuse.
 */
class WatchHistoryMatcherTest {

	private fun entry(id: String, title: String, channel: String, length: Long) =
		WatchHistoryParser.Entry(id, title, channel, length)

	private val feed = listOf(
		entry("4ns8D959YtA", "Criminal", "Natti Natasha - Topic", 273),
		entry("YbZwlNmnUvw", "Unica", "Ozuna - Topic", 218),
		entry("9mxGT0j1e1U", "Báilame (Remix)", "Release - Topic", 217),
	)

	/**
	 * The point of the whole route: the history names the upload the phone
	 * actually played, so the duration-identical re-upload never enters the
	 * question.
	 */
	@Test
	fun `names the exact upload that was played`() {
		val verdict = WatchHistoryMatcher.candidate(feed, "Criminal", "Natti Natasha", 273)
		assertTrue(verdict is WatchHistoryMatcher.Verdict.Candidate)
		verdict as WatchHistoryMatcher.Verdict.Candidate
		assertEquals("4ns8D959YtA", verdict.entry.videoId)
		assertEquals(0, verdict.positionFromNewest)
	}

	/** The track that just ended is not always at the top by the time we ask. */
	@Test
	fun `a track one position down still resolves and reports its position`() {
		val verdict = WatchHistoryMatcher.candidate(feed, "Unica", "Ozuna", 218)
			as WatchHistoryMatcher.Verdict.Candidate
		assertEquals("YbZwlNmnUvw", verdict.entry.videoId)
		assertEquals(1, verdict.positionFromNewest)
	}

	@Test
	fun `a duration that disagrees refuses rather than taking the newest entry`() {
		val verdict = WatchHistoryMatcher.candidate(feed, "Criminal", "Natti Natasha", 120)
		assertTrue(verdict is WatchHistoryMatcher.Verdict.Refused)
		assertTrue((verdict as WatchHistoryMatcher.Verdict.Refused).reason.startsWith("none of the"))
	}

	@Test
	fun `a track absent from the feed refuses`() {
		val verdict = WatchHistoryMatcher.candidate(feed, "Se Preparó", "Ozuna", 188)
		assertTrue(verdict is WatchHistoryMatcher.Verdict.Refused)
	}

	/**
	 * Two indistinguishable recent entries — the same song replayed from two
	 * different uploads — cannot say which one this listen was.
	 */
	@Test
	fun `two matching recent entries refuse every id`() {
		val doubled = listOf(
			entry("4ns8D959YtA", "Criminal", "Natti Natasha", 273),
			entry("VqEbCxg2bNI", "Criminal", "Natti Natasha", 273),
		)
		val verdict = WatchHistoryMatcher.candidate(doubled, "Criminal", "Natti Natasha", 273)
		assertTrue(
			(verdict as WatchHistoryMatcher.Verdict.Refused).reason
				.startsWith("ambiguous identity"),
		)
	}

	/** Beyond the recent window the feed is describing a different session. */
	@Test
	fun `an entry older than the recent window is not reachable`() {
		val long = List(WatchHistoryMatcher.RECENT_WINDOW) {
			entry("padpadpad$it".take(11).padEnd(11, 'x'), "Filler $it", "Someone", 100)
		} + entry("4ns8D959YtA", "Criminal", "Natti Natasha", 273)
		val verdict = WatchHistoryMatcher.candidate(long, "Criminal", "Natti Natasha", 273)
		assertTrue(verdict is WatchHistoryMatcher.Verdict.Refused)
	}

	@Test
	fun `an unknown duration still resolves one exact finalized title and channel`() {
		val verdict = WatchHistoryMatcher.candidate(feed, "Criminal", "Natti Natasha", null)
		assertTrue(verdict is WatchHistoryMatcher.Verdict.Candidate)
		assertEquals(
			"4ns8D959YtA",
			(verdict as WatchHistoryMatcher.Verdict.Candidate).entry.videoId,
		)
	}

	@Test
	fun `an unknown duration refuses two same-title uploads`() {
		val doubled = listOf(
			entry("4ns8D959YtA", "Criminal", "Natti Natasha", 273),
			entry("VqEbCxg2bNI", "Criminal", "Natti Natasha", 273),
		)
		val verdict = WatchHistoryMatcher.candidate(doubled, "Criminal", "Natti Natasha", null)
		assertTrue(
			(verdict as WatchHistoryMatcher.Verdict.Refused).reason
				.startsWith("ambiguous identity"),
		)
	}

	@Test
	fun `an unknown duration may offer one exact title for canonical channel verification`() {
		val verdict = WatchHistoryMatcher.candidate(
			listOf(entry("lir3dzYIhz0", "Bring Me The Horizon - Sleepwalking", "Bring Me The Horizon", 237)),
			"Bring Me The Horizon - Sleepwalking",
			"BMTHOfficialVEVO",
			null,
		)
		assertTrue(verdict is WatchHistoryMatcher.Verdict.Candidate)
	}

	@Test
	fun `an unknown duration and channel cannot establish identity from title alone`() {
		val verdict = WatchHistoryMatcher.candidate(feed, "Criminal", null, null)

		assertTrue(verdict is WatchHistoryMatcher.Verdict.Refused)
		assertTrue(
			(verdict as WatchHistoryMatcher.Verdict.Refused).reason.contains("title alone"),
		)
	}

	/**
	 * A carried id is re-derived at finalization, and a feed that now names a
	 * different video for the same tuple means neither answer was proven.
	 */
	@Test
	fun `revalidation refuses when history now names a different id`() {
		val moved = listOf(entry("VqEbCxg2bNI", "Criminal", "Natti Natasha", 273))
		val verdict = WatchHistoryMatcher.revalidate(
			moved, "4ns8D959YtA", "Criminal", "Natti Natasha", 273,
		)
		assertTrue(
			(verdict as WatchHistoryMatcher.Verdict.Refused).reason.contains("not the carried"),
		)
	}

	@Test
	fun `revalidation passes when history still names the carried id`() {
		val verdict = WatchHistoryMatcher.revalidate(
			feed, "4ns8D959YtA", "Criminal", "Natti Natasha", 273,
		)
		assertTrue(verdict is WatchHistoryMatcher.Verdict.Candidate)
	}
}
