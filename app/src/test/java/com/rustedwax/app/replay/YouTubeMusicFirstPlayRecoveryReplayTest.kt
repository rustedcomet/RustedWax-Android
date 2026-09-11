package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.youtube.identity.VideoResolutionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First play of a YouTube Music song that opens on someone else's presentation.
 *
 * On the free tier, YouTube Music publishes interstitial and alternate
	 * presentations under the song's **own** title, artist and album bundle,
	 * differing only in `DURATION`. The synthetic sequence uses two short
	 * presentations followed by the full song presentation.
 *
 * Every identity route is duration-gated, deliberately: a route that would name
 * the song regardless of the length being published is a route that cannot tell
 * the song from a pre-roll wearing its metadata. So while a non-canonical length
 * owns the MediaSession the correct catalog rows are filtered out *before* they
 * can be considered, and resolution refuses. That refusal is correct.
 *
 * What was not correct is that it could be final. The song is owed a lookup of
 * its own the moment its own presentation appears, and one boundary did not give
 * it one: a fully-spent short surface replaced by a longer one is discarded by
 * `provisionalAnchorSuperseded`, and that branch carried the discarded surface's
 * resolved id onto the presentation that replaced it while marking the listen
 * attributed. `listenNeedsPresentationProof` then saw an identified,
 * unambiguous listen and never asked again — so a 213 s song played to the end
 * under an id proved against a 91 s upload, and that id was the first thing
 * finalization tried to re-verify.
 *
 * Nothing here relaxes duration corroboration. Every scenario drives production's
 * own carry-authority request and lets the duration-gated route decide; what is
 * asserted is only that a new, unproven presentation of the same work gets to
 * ask.
 */
class YouTubeMusicFirstPlayRecoveryReplayTest : ReplayScenarioTest() {

	private val songId = "YHsP10noEAA"

	/** A real upload that is 91 s long and wears the song's title and artist. */
	private val interstitialId = "NxfN16Jtdrk"
	private val title = "Solid As A Rock"
	private val artist = "Sizzla"
	private val album = "Da Real Thing"
	private val songMs = 213_000L

	/**
	 * A resolver gated on length exactly as production's routes are.
	 *
	 * It names the song only while the song's own length is being published. When
	 * [alsoNamesShortSurface] is set it will additionally name a real 91 s upload
	 * while *that* length is being published — a title-and-channel match, right
	 * about the work and silent about which surface is playing, which is why it
	 * carries no `presentationDurationCorroborated`. That is the pairing the
	 * device produced and the one that used to end the song's chances.
	 */
	private fun harness(alsoNamesShortSurface: Boolean = false): ReplayHarness =
		ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC).also { harness ->
			harness.env.facts.put(
				VideoFacts(
					videoId = songId,
					title = title,
					author = artist,
					originalArtist = artist,
					watchPageArtistCredit = artist,
					lengthSeconds = songMs / 1000,
					category = "Music",
					watchPageResolved = true,
					isUnlisted = false,
				),
			)
			harness.env.facts.put(
				VideoFacts(
					videoId = interstitialId,
					title = title,
					author = artist,
					lengthSeconds = 91,
					category = "Music",
					watchPageResolved = true,
					isUnlisted = false,
				),
			)
			harness.env.identity.search = {
				when (harness.env.identity.searchRequests.lastOrNull()?.durationSec) {
					songMs / 1000 -> VideoResolutionAttempt(
						resolution = VideoResolution(
							videoId = songId,
							source = "structured native music title+artist+duration",
							title = title,
							channel = artist,
							lengthSeconds = songMs / 1000,
							uniquelyResolved = true,
							structuredNativeMusic = true,
							presentationDurationCorroborated = true,
						),
					)

					91L -> if (alsoNamesShortSurface) {
						VideoResolutionAttempt(
							resolution = VideoResolution(
								videoId = interstitialId,
								source = "title+channel search",
								title = title,
								channel = artist,
								lengthSeconds = 91,
								uniquelyResolved = true,
							),
						)
					} else {
						refusal()
					}

					else -> refusal()
				}
			}
		}

	private fun refusal() = VideoResolutionAttempt(
		refusalReason = "no fully fetched candidate matched structured native " +
			"music title+artist+duration",
	)

	/** The lengths the resolver was actually asked to corroborate, in order. */
	private val ReplayHarness.askedDurationsSec: List<Long?>
		get() = env.identity.searchRequests.map { it.durationSec }

	private fun metadata(durationMs: Long) = PlaybackEvent.SessionMetadata(
		title = title,
		artist = artist,
		album = album,
		durationMs = durationMs,
	)

	// ── A. three presentations, and the song still gets its own lookup ────────

	@Test
	fun `two refused presentations do not stop the song from resolving on the third`() {
		val harness = harness().feed(
			// 91 s. Nothing in the catalog is this song at this length, so the
			// route refuses — correctly.
			metadata(91_370),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(45_000),
			// 39 s, shorter again, and equally unnameable.
			metadata(39_636),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(30_000),
			// The song itself.
			metadata(songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(150_000),
		)

		assertEquals(
			"each presentation is owed one bounded lookup against its own length",
			listOf(91L, 39L, 213L),
			harness.askedDurationsSec,
		)
		assertEquals(
			"and exactly one each — the earlier refusals neither block the song " +
				"nor multiply into a storm",
			3,
			harness.trace.carryAuthorityRequests,
		)
		assertFalse(
			"the song's own presentation resolves and attributes normally",
			harness.trace.presentationAttributionAmbiguous,
		)
		assertEquals(songId, harness.trace.currentSourceItemId)

		harness.feed(PlaybackEvent.Finalized("song ended"))

		val listen = harness.finalized.single()
		assertEquals(
			"only the 150s heard on the song's own presentation is credited",
			150_000L,
			listen.playedMs,
		)
		assertEquals(
			"and the 75s measured on the two unproven surfaces is refused, not folded in",
			75_000L,
			listen.unattributedMeasuredMs,
		)
		assertEquals(listOf(songId), harness.broadcasts.map { it.videoId })
	}

	// ── A2. the boundary that used to end it: a spent short surface ───────────

	/**
	 * The reproduced first-play failure.
	 *
	 * The 91 s surface is played through to its own end, which is what sends this
	 * boundary down `provisionalAnchorSuperseded` rather than the alternate-media
	 * branch, and the resolver names a real 91 s upload while it owns the session.
	 * The song then replaces it. Before the fix the song inherited that id, was
	 * marked attributed without a proof of its own, and no second lookup was ever
	 * made: `carryAuthorityRequests` stayed at 1 for the whole listen.
	 */
	@Test
	fun `a song replacing a spent identified surface is asked for its own identity`() {
		val harness = harness(alsoNamesShortSurface = true).feed(
			metadata(91_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(91_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 91_000),
		)

		assertEquals(
			"the short surface resolved an id of its own",
			interstitialId,
			harness.trace.currentSourceItemId,
		)
		assertTrue(
			"but naming the work is not proving the surface, so nothing is attributed",
			harness.trace.presentationAttributionAmbiguous,
		)

		harness.feed(
			metadata(songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(150_000),
		)

		assertEquals(
			"the song is asked to corroborate its own length, not the discarded one's",
			listOf(91L, 213L),
			harness.askedDurationsSec,
		)
		assertEquals(2, harness.trace.carryAuthorityRequests)
		assertEquals(
			"and holds the id proved for it, not the one proved against the surface " +
				"whose interval was just thrown away",
			songId,
			harness.trace.currentSourceItemId,
		)
		assertEquals(
			"the carried pre-resolution is replaced too, or finalization re-verifies " +
				"the interstitial first",
			songId,
			harness.trace.preResolvedNativeVideoId,
		)

		harness.feed(PlaybackEvent.Finalized("song ended"))

		assertEquals(
			"only the song's own 150s is credited",
			150_000L,
			harness.finalized.single().playedMs,
		)
		assertEquals(listOf(songId), harness.broadcasts.map { it.videoId })
		assertNotEquals(
			"and the 91s upload must never reach the chain as this listen",
			interstitialId,
			harness.broadcasts.single().videoId,
		)
	}

	@Test
	fun `the song is asked at the boundary itself, with no transport callback behind it`() {
		// This source publishes a replacement presentation with nothing from the
		// transport behind it, sometimes for many seconds. The lookup is owed to
		// the presentation, not to the next callback that happens to arrive.
		val harness = harness(alsoNamesShortSurface = true).feed(
			metadata(91_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(91_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 91_000),
			metadata(songMs),
		)

		assertEquals(
			"the boundary asks; nothing else has happened yet",
			listOf(91L, 213L),
			harness.askedDurationsSec,
		)
		assertEquals(songId, harness.trace.currentSourceItemId)

		harness.feed(
			PlaybackEvent.Advance(150_000),
			PlaybackEvent.Finalized("song ended"),
		)
		assertEquals(listOf(songId), harness.broadcasts.map { it.videoId })
	}

	@Test
	fun `a spent unidentifiable surface leaves the song no id to inherit`() {
		// The same boundary with nothing resolved on the short surface: the song
		// must still get its own lookup, and must arrive at it holding no id.
		val harness = harness().feed(
			metadata(91_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(91_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 91_000),
		)
		assertNull(harness.trace.currentSourceItemId)

		harness.feed(
			metadata(songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(150_000),
		)

		// Read before the finalize, which spends a lookup of its own.
		assertEquals(listOf(91L, 213L), harness.askedDurationsSec)

		harness.feed(PlaybackEvent.Finalized("song ended"))
		assertEquals(listOf(songId), harness.broadcasts.map { it.videoId })
	}

	// ── A3. a mid-item Song↔Video switch is untouched by any of this ─────────

	@Test
	fun `a proven surface left mid-item still keeps every second across the switch`() {
		// The contrast, and the reason the wrap is part of the rule: no end, no
		// hand-over, so a Song↔Video switch is one listen continuing exactly as
		// before.
		val harness = harness().feed(
			metadata(songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(90_000),
		)
		assertFalse(harness.trace.presentationAttributionAmbiguous)

		harness.feed(
			// The other rendering of the same work, arriving mid-item.
			metadata(240_000),
			PlaybackEvent.Advance(60_000),
			PlaybackEvent.Finalized("track ended"),
		)

		assertEquals("one work rendered two ways is one listen", 1, harness.finalized.size)
		assertEquals(
			"the 90s already earned survives the switch",
			150_000L,
			harness.finalized.single().playedMs,
		)
	}

	// ── B. an ad first, then the song ────────────────────────────────────────

	@Test
	fun `a 30s interstitial is excluded and the song behind it still resolves`() {
		val harness = harness().feed(
			metadata(30_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(30_000),
			metadata(songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(150_000),
		)

		// Read before the finalize, which spends a lookup of its own.
		assertEquals(
			"the ad's own length is asked about, and refused; the song's is asked next",
			listOf(30L, 213L),
			harness.askedDurationsSec,
		)

		harness.feed(PlaybackEvent.Finalized("song ended"))
		assertEquals(
			"the interstitial may not finalize a listen of its own",
			1,
			harness.finalized.size,
		)
		assertEquals(
			"and none of its 30s is the song's",
			150_000L,
			harness.finalized.single().playedMs,
		)
		assertEquals(listOf(songId), harness.broadcasts.map { it.videoId })
	}

	@Test
	fun `an ad may not push a sub-threshold song over the line`() {
		// 30 s of interstitial plus 110 s of a 213 s song is 66 % of the song only
		// if the interstitial's seconds are counted as the song's. They are not.
		val harness = harness().feed(
			metadata(30_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(30_000),
			metadata(songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(110_000),
			PlaybackEvent.Finalized("song ended"),
		)

		assertEquals(110_000L, harness.finalized.single().playedMs)
		assertEquals(
			"51% of the song is not a scrobble",
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)
	}

	// ── C. no resolver storm ─────────────────────────────────────────────────

	@Test
	fun `repeated callbacks on one refused presentation spend exactly one lookup`() {
		val harness = harness().feed(
			metadata(91_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(10_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 10_000),
			PlaybackEvent.Advance(10_000),
			// The same bundle republished, which this source does constantly.
			metadata(91_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 20_000),
			PlaybackEvent.Advance(10_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 30_000),
			metadata(91_000),
			PlaybackEvent.Advance(10_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 40_000),
		)

		assertEquals(
			"a refusal is remembered for the presentation that earned it",
			1,
			harness.trace.carryAuthorityRequests,
		)
		assertEquals(listOf(91L), harness.askedDurationsSec)

		harness.feed(PlaybackEvent.Finalized("stopped"))
	}

	// ── D. a wrong or ambiguous candidate still attributes nothing ────────────

	@Test
	fun `an ambiguous candidate set attributes nothing and writes nothing`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC).also {
			it.env.identity.search = {
				VideoResolutionAttempt(
					refusalReason = "ambiguous identity — candidates were evaluated and disagree",
					failure = VideoResolutionFailure.AMBIGUOUS,
				)
			}
		}.feed(
			metadata(91_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(91_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 91_000),
			metadata(songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(150_000),
		)

		assertTrue(
			"the fresh attempt is made — and refused",
			harness.trace.carryAuthorityRequests >= 2,
		)
		assertEquals(
			"nothing is named, so nothing is held",
			null,
			harness.trace.currentSourceItemId,
		)

		harness.feed(PlaybackEvent.Finalized("song ended"))

		assertEquals(
			"an unresolvable listen is never broadcast",
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)
		assertEquals(
			"but the user is still told it happened",
			listOf(title),
			FinalizationRuntime.skipped.value.map { it.title },
		)
	}

	@Test
	fun `a candidate the corroborator contradicts is refused rather than attributed`() {
		// The resolver names an id at the song's length whose page is a different
		// work. Identity must fail closed: no attribution, no write.
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC).also {
			it.env.facts.put(
				VideoFacts(
					videoId = songId,
					title = "Something Else Entirely",
					author = "A Different Artist",
					lengthSeconds = songMs / 1000,
					category = "Music",
					watchPageResolved = true,
					isUnlisted = false,
				),
			)
			it.env.identity.search = {
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = songId,
						source = "structured native music title+artist+duration",
						title = "Something Else Entirely",
						channel = "A Different Artist",
						lengthSeconds = songMs / 1000,
						uniquelyResolved = true,
						structuredNativeMusic = true,
						presentationDurationCorroborated = true,
					),
				)
			}
		}.feed(
			metadata(songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(150_000),
		)

		assertTrue(
			"a contradicted candidate may not attribute the surface",
			harness.trace.presentationAttributionAmbiguous,
		)

		harness.feed(PlaybackEvent.Finalized("song ended"))
		assertEquals(
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)
	}

	// ── E. the ordinary first play is untouched ──────────────────────────────

	@Test
	fun `a first play that opens on the song itself is unchanged`() {
		val harness = harness().feed(
			metadata(songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(150_000),
		)

		assertEquals(
			"one presentation, one lookup",
			1,
			harness.trace.carryAuthorityRequests,
		)
		assertEquals(listOf(213L), harness.askedDurationsSec)
		assertFalse(harness.trace.presentationAttributionAmbiguous)

		harness.feed(PlaybackEvent.Finalized("song ended"))

		val listen = harness.finalized.single()
		assertEquals(150_000L, listen.playedMs)
		assertEquals(
			"nothing was refused, so nothing is recorded as refused",
			0L,
			listen.unattributedMeasuredMs,
		)
		assertEquals(listOf(songId), harness.broadcasts.map { it.videoId })
		assertEquals(
			emptyList<FinalizationRuntime.SkipRecord>(),
			FinalizationRuntime.skipped.value,
		)
	}

	@Test
	fun `a sub-threshold first play keeps its real time and earns a visible row`() {
		val harness = harness().feed(
			metadata(songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(54_000),
			PlaybackEvent.Finalized("track change"),
		)

		assertEquals(54_000L, harness.finalized.single().playedMs)
		assertEquals(
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)
		val row = FinalizationRuntime.skipped.value.single()
		assertEquals(54L, row.playedSeconds)
		assertTrue(
			"and says why truthfully: ${row.reason}",
			row.reason.contains("below 60% threshold"),
		)
	}
}
