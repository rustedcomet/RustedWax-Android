package com.rustedwax.app.replay

import com.rustedwax.core.*
import com.rustedwax.app.enrich.VideoFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic regression for a finished video left active after playback ends.
 */
class AbandonedPlaybackReplayTest : ReplayScenarioTest() {

	private val videoId = "synthetic01"

	private fun harness(): ReplayHarness {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(
			VideoFacts(
				videoId = videoId,
				title = "Example Track",
				author = "Example Artist",
				lengthSeconds = 186,
				category = "Music",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
		// The synthetic fixture was classified `song`. A bare title like "Example Track"
		// names no `Artist - Track` structure of its own, so the category alone
		// does not carry it; MusicBrainz confirming the recording is what did.
		// Scripted here so the fixture reaches the same `kind` this regression needs —
		// and `kind == song` is one of the three conditions the double-listen cap
		// turns on, so getting it wrong would hide the defect rather than show it.
		harness.env.music.found("Example Artist", "Example Track", "Example Artist", "Example Track")
		return harness
	}

	/** The listen up to the point the video's own length runs out. */
	private fun watching() = listOf(
		PlaybackEvent.NotificationObserved(host = "youtube.com"),
		PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = videoId),
		PlaybackEvent.SessionMetadata(
			title = "Example Track",
			artist = "Example Artist",
			// The synthetic payload carried `"album":"Example Track"`, which is what made
			// `MusicClassifier` call it a song — and `kind == song` is one of the
			// three conditions the double-listen cap turns on.
			album = "Example Track",
			durationMs = 186_000,
		),
		// Position is never published: the field session reported `pos=-1ms` for
		// its entire life, which is what makes loop detection unavailable.
		PlaybackEvent.PlaybackStateChanged(playing = true),
	)

	@Test
	fun `a video watched once and left running finalizes far past its own length`() {
		val harness = harness()

		harness.feed(
			watching() +
				// 1h47m on a 3:06 song, which is the synthetic regression shape.
				PlaybackEvent.Advance(6_421_000) +
				PlaybackEvent.Finalized("the browser named its tab instead of a track"),
		)

		val snapshot = harness.finalized.single()
		assertEquals(6_421_000L, snapshot.playedMs)
		assertEquals(186_000L, snapshot.durationMs)
		// Nothing ever disproved the listen: no position means no wrap, so the
		// one signal that would cap this to a single transaction is unavailable.
		assertEquals(false, snapshot.loopDetected)
		assertTrue("the synthetic overrun exceeds 3400%", (snapshot.percentPlayed ?: 0.0) > 34.0)
	}

	@Test
	fun `and that one viewing earns exactly one transaction, not two`() {
		// The regression. Before the fix this shape produced two payloads with the
		// same frozen start — two immutable claims about one synthetic viewing.
		val harness = harness()

		harness.feed(
			watching() +
				PlaybackEvent.Advance(6_421_000) +
				PlaybackEvent.Finalized("the browser named its tab instead of a track"),
		)

		assertEquals(listOf("Eligible"), harness.outcomeKinds)
		assertEquals(1, harness.broadcasts.size)
		assertEquals(1, harness.transactionIds.size)
		// The listen is still recorded, and still at 100% — the viewing was real.
		// What it no longer does is claim to have happened twice.
		assertEquals(100, harness.broadcasts.single().percentPlayed)
		assertEquals("song", harness.broadcasts.single().kind)
	}

	@Test
	fun `the same over-run on a session that reports position still earns two`() {
		// The other side of the rule, so the fix is a gate on evidence rather than
		// a blanket cap. Same song, same 3452%, but this transport publishes a
		// position — so a second listen is corroborated and the 160% rule applies
		// exactly as it did before.
		val harness = harness()

		harness.feed(
			listOf(
				PlaybackEvent.NotificationObserved(host = "youtube.com"),
				PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = videoId),
				PlaybackEvent.SessionMetadata(
					title = "Example Track",
					artist = "Example Artist",
					album = "Example Track",
					durationMs = 186_000,
				),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
				PlaybackEvent.Advance(6_421_000),
				PlaybackEvent.Finalized("the browser named its tab instead of a track"),
			),
		)

		val snapshot = harness.finalized.single()
		assertEquals(false, snapshot.loopDetected)
		assertTrue("position was readable", snapshot.firstObservedPositionMs != null)
		assertEquals(2, harness.broadcasts.size)
	}

	@Test
	fun `a genuine second listen looks identical from the payload alone`() {
		// Why the fix is not simply "cap the percentage": a song really played
		// twice through produces the same shape, and the 160% rule exists for it.
		// The difference is not in the numbers — it is in whether anything
		// corroborated a second pass through the item.
		val harness = harness()

		harness.feed(
			watching() +
				PlaybackEvent.Advance(186_000) +
				// The player ran off the end and back to the start, and this time
				// the position said so.
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 180_000) +
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 1_000) +
				PlaybackEvent.Advance(186_000) +
				PlaybackEvent.Finalized(),
		)

		val snapshot = harness.finalized.single()
		// With the wrap observed, the loop cap applies and one transaction is sent
		// for the continuous viewing — which is the existing, correct behaviour.
		assertEquals(true, snapshot.loopDetected)
		assertEquals(1, harness.broadcasts.size)
	}

	@Test
	fun `an idle deadline ends the listen at what it actually reached`() {
		// The second fix, end to end. The video runs to its end and the source
		// says nothing; the deadline fires; the listen is scored for the item it
		// actually played rather than for the hours the phone sat there.
		val harness = harness()

		harness.feed(
			watching() +
				// Its own length, plus the grace period, and then the timer the probe
				// would have armed.
				PlaybackEvent.Advance(186_000 + 30_000) +
				PlaybackEvent.IdleDeadlineReached +
				// Nothing further happens: the phone is face-down on a table.
				PlaybackEvent.Advance(6_000_000),
		)

		val snapshot = harness.finalized.single()
		// 216 s of a 186 s item — its own length plus the grace — instead of 6421 s.
		assertEquals(216_000L, snapshot.playedMs)
		assertEquals(1, harness.broadcasts.size)
		assertEquals(100, harness.broadcasts.single().percentPlayed)
	}

	@Test
	fun `a deadline that arrives while the item is still playing ends nothing`() {
		val harness = harness()

		harness.feed(
			watching() +
				PlaybackEvent.Advance(60_000) +
				PlaybackEvent.IdleDeadlineReached +
				PlaybackEvent.Advance(60_000) +
				PlaybackEvent.Finalized(),
		)

		val snapshot = harness.finalized.single()
		assertEquals(120_000L, snapshot.playedMs)
		assertEquals(1, harness.finalized.size)
	}
}
