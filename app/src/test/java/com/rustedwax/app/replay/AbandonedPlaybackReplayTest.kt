package com.rustedwax.app.replay

import com.rustedwax.core.*
import com.rustedwax.app.enrich.VideoFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A finished video nobody came back to.
 *
 * ## The field case
 *
 * Measured 2026-08-12 on the retained device log, and reproduced here before any
 * fix, exactly as `<redacted-private-path>` requires: *"Production bug fixes
 * should not be mixed with structural migration unless the bug is first
 * reproduced in the replay corpus and the behavior change is explicitly
 * documented."*
 *
 * ```
 * 08:31:53  [finalize] com.brave.browser [the browser named its tab instead of
 *           a track] Cry Baby — played 6421s of 186s (up to 2.0× speed)
 * 08:31:53  broadcasting … "percent_played":100 … timestamp 11:46:25
 * 08:32:04  scrobbled (block): tx ae7ad561d297b9b7627240a51748da181fddcae3
 * 08:32:04  broadcasting … "percent_played":100 … timestamp 11:46:25
 * 08:32:08  scrobbled (block): tx 27c2171ca7586caff80bc9e0cda99a804b086173
 * ```
 *
 * One viewing of a 3:06 song, left sitting for one hour forty-seven minutes,
 * became **two immutable on-chain transactions**.
 *
 * ## Why it happens, in five steps
 *
 * 1. Brave never publishes `STATE_STOPPED`. Across the whole retained log its
 *    835 finalizations are 749 track changes, 49 tab-title replacements, 34
 *    continuation expiries and 3 probe teardowns — and zero stops. A video that
 *    reaches its end and is then left alone keeps its transport in `PLAYING`.
 * 2. Its published position is `-1`, i.e. unreadable, for the whole listen.
 * 3. Because there is no position, `positionWrapped` can never fire, so
 *    `loopDetected` stays false however many times the page loops.
 * 4. The played clock therefore accrues wall-clock indefinitely, and
 *    `ScrobbleRules.decide` computes `txCount = min(2, 1 + floor(progress −
 *    0.6))`, which saturates at two.
 * 5. `ScrobbleRules.capForKind` caps to one transaction only when
 *    `loopDetected || isShort || kind != song`. None of those hold, so both are
 *    sent.
 *
 * The trigger is the user doing *nothing* — falling asleep, putting the phone
 * down. That is ordinary use, not an edge case, and the second transaction is a
 * claim about a listen that did not happen.
 *
 * ## The fix
 *
 * A second scrobble is a *claim that a second listen happened*. Step 2 is what
 * makes that claim unfounded here: with no position ever readable, elapsed wall
 * clock is the only input, and wall clock runs whether or not anyone is still
 * watching. `ScrobbleRules.capForKind` now requires the progress to be
 * corroborated by a position that was actually readable before it will mint the
 * second transaction.
 *
 * Deliberately *not* the same test as `loopDetected`, which is the opposite
 * signal: a detected end-to-start wrap means the item auto-looped and caps for
 * that reason. This covers the case where nothing could be detected at all.
 *
 * ## The second fix: the listen is ended rather than left running
 *
 * Capping the transactions stopped the wrong data reaching the chain, but left
 * the cause alone — and the cause has a second consequence. Several evidence
 * gates require the browser to have exactly one live session, so a tab still
 * nominally playing a video that finished hours ago starves *every later listen*
 * in that browser of its accessibility coverage and ad evidence. It never
 * self-heals, because the only things that end a browser listen are a track
 * change, a navigation or a closed tab.
 *
 * So a listen that has consumed its own length with nothing further published is
 * now finalized on a timer, at the percentage it genuinely reached, and its
 * session is released. `PlaybackReducer.idleFinalizeDelayMs` derives the
 * deadline and every observation pushes it out; only a source that goes silent
 * lets it expire.
 */
class AbandonedPlaybackReplayTest : ReplayScenarioTest() {

	private val videoId = "KrcDaUp9cN0"

	private fun harness(): ReplayHarness {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(
			VideoFacts(
				videoId = videoId,
				title = "Cry Baby",
				author = "Evan Rion",
				lengthSeconds = 186,
				category = "Music",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
		// The field listen was classified `song`. A bare title like "Cry Baby"
		// names no `Artist - Track` structure of its own, so the category alone
		// does not carry it; MusicBrainz confirming the recording is what did.
		// Scripted here so the fixture reaches the same `kind` the field did —
		// and `kind == song` is one of the three conditions the double-listen cap
		// turns on, so getting it wrong would hide the defect rather than show it.
		harness.env.music.found("Evan Rion", "Cry Baby", "Evan Rion", "Cry Baby")
		return harness
	}

	/** The listen up to the point the video's own length runs out. */
	private fun watching() = listOf(
		PlaybackEvent.NotificationObserved(host = "youtube.com"),
		PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = videoId),
		PlaybackEvent.SessionMetadata(
			title = "Cry Baby",
			artist = "Evan Rion",
			// The field payload carried `"album":"Cry Baby"`, which is what made
			// `MusicClassifier` call it a song — and `kind == song` is one of the
			// three conditions the double-listen cap turns on.
			album = "Cry Baby",
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
				// 1h47m on a 3:06 song, which is what the field recorded.
				PlaybackEvent.Advance(6_421_000) +
				PlaybackEvent.Finalized("the browser named its tab instead of a track"),
		)

		val snapshot = harness.finalized.single()
		assertEquals(6_421_000L, snapshot.playedMs)
		assertEquals(186_000L, snapshot.durationMs)
		// Nothing ever disproved the listen: no position means no wrap, so the
		// one signal that would cap this to a single transaction is unavailable.
		assertEquals(false, snapshot.loopDetected)
		assertTrue("the field measured 3452%", (snapshot.percentPlayed ?: 0.0) > 34.0)
	}

	@Test
	fun `and that one viewing earns exactly one transaction, not two`() {
		// The regression. Before the fix this produced two payloads with the same
		// frozen start — two immutable claims about one sitting, on-chain as
		// `ae7ad561…` and `27c2171c…`.
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
					title = "Cry Baby",
					artist = "Evan Rion",
					album = "Cry Baby",
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
