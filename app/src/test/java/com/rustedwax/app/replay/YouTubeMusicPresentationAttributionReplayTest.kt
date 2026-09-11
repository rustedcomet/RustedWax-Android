package com.rustedwax.app.replay

import com.rustedwax.app.enrich.NativeStructuredMusicMatcher
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordinary YouTube Music playback, through the production attribution path.
 *
 * v0.11.2 shipped `presentationUnprovenForNamedWork` with no production emitter
 * for the input that clears it, so a clean Music track — one that simply plays
 * under a single stable duration, with no interstitial to supersede — reached
 * finalization still unattributed and was reported as `playedMs = 0`. That zero
 * then fell under the Not-logged notability floor, and the listen vanished from
 * History *and* from Not logged. A 266 s song played end to end left no trace
 * of any kind.
 *
 * The suite that was supposed to protect this case passed anyway, because its
 * happy-path fixtures injected `PresentationAttributionEstablished` by hand.
 * Nothing here does. Every scenario below drives the real carry-authority
 * request that `SessionProbe.requestNativeCarryAuthority` makes and lets the
 * production route decide, so a signal that stops being emitted fails these.
 *
 * The resolver stub is duration-aware on purpose. Production's structured-music
 * route only names an id when the *published* length agrees with a fetched
 * catalog row's own length ([com.rustedwax.app.enrich.NativeStructuredMusicMatcher]),
 * and that agreement is the whole reason the route may attribute a surface. A
 * stub that answered regardless of duration would hand every pre-roll the proof
 * its own length disqualifies it from, and would prove nothing about Bug 5.
 */
class YouTubeMusicPresentationAttributionReplayTest : ReplayScenarioTest() {

	private val videoId = "dQw4w9WgXcQ"
	private val title = "Never Gonna Give You Up"
	private val artist = "Rick Astley"
	private val songMs = 213_000L

	/**
	 * A harness whose structured-music route behaves like the real matcher: it
	 * names the work only while the length the player is publishing is the work's
	 * own, within the resolver's tolerance.
	 */
	private fun harness(
		durationMs: Long = songMs,
		videoId: String = this.videoId,
		title: String = this.title,
	): ReplayHarness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC).also { harness ->
		harness.env.facts.put(
			VideoFacts(
				videoId = videoId,
				title = title,
				author = artist,
				originalArtist = artist,
				watchPageArtistCredit = artist,
				lengthSeconds = durationMs / 1000,
				category = "Music",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
		harness.env.identity.search = {
			val askedDurationSec = harness.env.identity.searchRequests.lastOrNull()?.durationSec
			if (askedDurationSec != null && askedDurationSec == durationMs / 1000) {
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = videoId,
						source = "structured native music title+artist+duration",
						title = title,
						channel = artist,
						lengthSeconds = durationMs / 1000,
						uniquelyResolved = true,
						structuredNativeMusic = true,
						presentationDurationCorroborated = true,
					),
				)
			} else {
				VideoResolutionAttempt(
					refusalReason = "no fully fetched candidate matched structured native " +
						"music title+artist+duration",
				)
			}
		}
	}

	/**
	 * A clean track: one duration, published once, played straight through, and
	 * then ended.
	 *
	 * Attribution is asserted in the middle, while the listen is still live —
	 * freezing it starts the next listen, and a fresh listen is unambiguous by
	 * construction, so the same assertion after the finalize would prove nothing.
	 */
	private fun ReplayHarness.playCleanly(
		title: String,
		durationMs: Long,
		playedMs: Long,
		reason: String = "track ended",
	): ReplayHarness {
		feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = durationMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(playedMs),
		)
		assertTrue(
			"the scenario must exercise production's own carry-authority request",
			trace.carryAuthorityRequests > 0,
		)
		assertTrue(
			"and that request must be what resolves the id",
			trace.carryAuthorityResolutions > 0,
		)
		assertFalse(
			"a duration-corroborated route attributes the surface it corroborated",
			trace.presentationAttributionAmbiguous,
		)
		return feed(PlaybackEvent.Finalized(reason))
	}

	// ── A. sub-threshold playback keeps its real measurement and its row ──────

	@Test
	fun `a clean sub-threshold track keeps its real played time and earns a Not-logged row`() {
		val harness = harness().playCleanly(title, songMs, playedMs = 54_000)

		assertEquals(
			"the 54s the listener actually heard is the listen's own",
			54_000L,
			harness.finalized.single().playedMs,
		)
		assertEquals(
			"below threshold is a refusal, not a broadcast",
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)

		val row = FinalizationRuntime.skipped.value.single()
		assertEquals(title, row.title)
		assertEquals(
			"the row must state the time that was measured, not a zero",
			54L,
			row.playedSeconds,
		)
		assertTrue(
			"and must explain the refusal truthfully: ${row.reason}",
			row.reason.contains("25%") && row.reason.contains("below 60% threshold"),
		)
	}

	// ── B. above threshold still scrobbles normally ───────────────────────────

	@Test
	fun `a clean track past the threshold scrobbles normally`() {
		val harness = harness().playCleanly(title, songMs, playedMs = 140_000)

		assertEquals(140_000L, harness.finalized.single().playedMs)
		assertEquals(1, harness.broadcasts.size)
		assertEquals(
			"an eligible listen leaves no refusal row",
			emptyList<FinalizationRuntime.SkipRecord>(),
			FinalizationRuntime.skipped.value,
		)
	}

	// ── C. the short intro: refused on the hard floor, and visible ────────────

	@Test
	fun `an 18s intro played whole is refused on the 30s floor and appears in Not logged`() {
		val introMs = 18_000L
		val harness = harness(
			durationMs = introMs,
			videoId = "G7vJedJkTto",
			title = "Listening Advisory",
		).playCleanly("Listening Advisory", introMs, playedMs = introMs)

		assertEquals(
			"being short is not being an interstitial — every second was real",
			introMs,
			harness.finalized.single().playedMs,
		)
		assertEquals(
			"an 18s work may not reach the chain",
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)

		val row = FinalizationRuntime.skipped.value.single()
		assertEquals("Listening Advisory", row.title)
		assertEquals(18L, row.playedSeconds)
		assertTrue(
			"the refusal must be the duration floor, not a fabricated 0%: ${row.reason}",
			row.reason.contains("track is 18s, under the 30s minimum"),
		)
		assertFalse(
			"it played in full, so no threshold complaint is truthful here",
			row.reason.contains("below 60% threshold"),
		)
	}

	// ── D. a full-length song must never finalize as nothing ──────────────────

	@Test
	fun `a full-length song played end to end never finalizes as zero`() {
		val longMs = 266_000L
		val harness = harness(durationMs = longMs, videoId = "9ReXl37XTx0", title = "They Fear Me")
			.playCleanly("They Fear Me", longMs, playedMs = longMs)

		val song = harness.finalized.single()
		assertNotNull(song.title)
		assertEquals("a song heard whole is not zero progress", longMs, song.playedMs)
		assertEquals(1, harness.broadcasts.size)
	}

	@Test
	fun `no finalized clean listen is ever absent from both History and Not logged`() {
		val harness = harness().playCleanly(title, songMs, playedMs = 54_000)

		val accountedFor = harness.broadcasts.size + FinalizationRuntime.skipped.value.size
		assertEquals(
			"every finalized listen owes the user either a scrobble or a visible refusal",
			harness.finalized.size,
			accountedFor,
		)
	}

	// ── E. the pre-roll is still excluded, by the same evidence ───────────────

	@Test
	fun `a pre-roll is refused attribution by its own length and adds nothing to the song`() {
		val harness = harness().feed(
			// The interstitial, published under the song's title and artist with
			// its own 30s length. The structured route cannot name the work at this
			// length, so nothing attributes this surface.
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = 30_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(30_000),
			// The song itself arrives.
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(140_000),
			PlaybackEvent.Finalized("song ended"),
		)

		assertEquals("the interstitial may not finalize the song", 1, harness.finalized.size)
		assertEquals(
			"only the song's own 140s is credited; the 30s pre-roll is not",
			140_000L,
			harness.finalized.single().playedMs,
		)
		assertEquals(1, harness.broadcasts.size)
	}

	// ── F. an abandoned pre-roll still writes nothing ─────────────────────────

	@Test
	fun `a pre-roll abandoned before the song writes nothing and claims no progress`() {
		val harness = harness().feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = 30_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(29_000),
		)

		// Asserted here rather than after the finalize: freezing the listen starts
		// the next one, and the next one is unambiguous by construction.
		assertTrue(
			"the abandoned pre-roll must still have asked for carry authority",
			harness.trace.carryAuthorityRequests > 0,
		)
		assertTrue(
			"its own length must leave the surface unattributed",
			harness.trace.presentationAttributionAmbiguous,
		)

		harness.feed(
			PlaybackEvent.PlaybackStateChanged(playing = false, stopped = true, positionMs = 29_000),
			PlaybackEvent.Finalized("stopped"),
		)

		assertEquals(
			"no interstitial second may be reported as the song's",
			emptyList<Long>(),
			harness.finalized.map { it.playedMs }.filter { it > 0 },
		)
		assertEquals(
			"and none may be broadcast",
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)
		assertEquals(
			"nor written down as a listen that happened",
			emptyList<String>(),
			FinalizationRuntime.skipped.value
				.filter { it.playedSeconds > 0 }
				.map { "${it.title} played ${it.playedSeconds}s" },
		)
	}

	// ── G. identity alone is never attribution ───────────────────────────────

	@Test
	fun `a route that never checked the length resolves the id but attributes nothing`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE_MUSIC).also {
			it.env.facts.put(
				VideoFacts(
					videoId = videoId,
					title = title,
					author = artist,
					lengthSeconds = songMs / 1000,
					category = "Music",
					watchPageResolved = true,
					isUnlisted = false,
				),
			)
			// A title-and-channel match: right about the song, silent about which
			// surface is playing. Production must not spend it as attribution.
			it.env.identity.search = {
				VideoResolutionAttempt(
					resolution = VideoResolution(
						videoId = videoId,
						source = "search",
						title = title,
						channel = artist,
						lengthSeconds = songMs / 1000,
						uniquelyResolved = true,
					),
				)
			}
		}.feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(54_000),
		)

		assertTrue(
			"the id must genuinely resolve, or this proves nothing",
			harness.trace.carryAuthorityResolutions > 0,
		)
		assertTrue(
			"an exact id from an uncorroborated route may not attribute the surface",
			harness.trace.presentationAttributionAmbiguous,
		)
	}

	@Test
	fun `the structured matcher marks its own proof as duration-corroborated`() {
		val resolved = NativeStructuredMusicMatcher.select(
			candidates = listOf(
				VideoResolution(
					videoId = videoId,
					source = "search",
					title = title,
					channel = artist,
					lengthSeconds = songMs / 1000,
				),
			),
			nativeTitle = title,
			nativeArtist = artist,
			durationSec = songMs / 1000,
		).resolution
		assertNotNull(resolved)
		assertTrue(
			"the matcher that compared the lengths is what records that it did",
			resolved!!.presentationDurationCorroborated,
		)
	}

	@Test
	fun `a proof is unattributed until the code that matched it says otherwise`() {
		assertFalse(
			"the safe default: a new resolver path attributes nothing until its " +
				"author has decided the length was checked",
			VideoResolution(videoId = videoId, source = "some future route")
				.presentationDurationCorroborated,
		)
	}

	// ── H. time measured on an unattributed presentation is never folded on ───

	/**
	 * Synthetic regression for unattributed duration churn.
	 *
	 * YouTube Music published "Solid As A Rock" / Sizzla under three lengths in one
	 * listen — 91 s, then 39 s, then the real 213 s song. The first two were never
	 * attributed to the work; only the third was. The listen nonetheless finalized
	 * at `played 243s of 213s` and broadcast `percent_played: 100` after about
	 * 110 s of the song had actually played.
	 *
	 * The 130 s belonged to whatever those two surfaces were. Once carried past the
	 * boundary it became indistinguishable from the song's own, and the song spent
	 * it.
	 */
	@Test
	fun `two unattributed presentations add nothing to the song that follows them`() {
		val harness = harness().feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = 91_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			// Left before its own end, so the spent-surface rule cannot reach it.
			PlaybackEvent.Advance(40_000),
			// And shorter than the one before it, which that rule also cannot reach:
			// it only discards a surface a *longer* one replaces.
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = 39_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(30_000),
			// The song itself, and the only surface this harness will attribute.
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(110_000),
			PlaybackEvent.Finalized("song ended"),
		)

		val song = harness.finalized.single()
		assertEquals(
			"only the 110s observed on the proven 213s presentation may count",
			110_000L,
			song.playedMs,
		)
		assertTrue(
			"progress may never exceed the work it is credited to",
			song.playedMs <= songMs,
		)
		assertEquals(
			"110s of 213s is 51%; folding the 70s would have made it 84% and eligible",
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)
	}

	/**
	 * The Dai Dai shape: an interstitial that is *not* fully spent before the song
	 * replaces it, so the spent-surface rule cannot discard it. Finalized
	 * `253s of 223s` and wrote 100 %.
	 */
	@Test
	fun `an interstitial cut short before the song still adds nothing to it`() {
		val harness = harness().feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = 30_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			// Cut off at 18s of its own 30s: never spent, so the surface is replaced
			// rather than superseded.
			PlaybackEvent.Advance(18_000),
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(120_000),
			PlaybackEvent.Finalized("song ended"),
		)

		assertEquals(
			"the 18s of interstitial is not the song's",
			120_000L,
			harness.finalized.single().playedMs,
		)
		assertEquals(
			"120s of 213s is 56% — below threshold, so nothing may be written",
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)
	}

	/**
	 * The one thing an interstitial always has is the song's own name. If sharing
	 * a title and artist were enough, every scenario above would fail — so this
	 * asserts the negative directly: provisional time may not be made organic by
	 * the metadata agreeing with itself.
	 */
	@Test
	fun `identical title and artist across surfaces does not make provisional time organic`() {
		val harness = harness().feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = 45_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			// Short of its own length: not a spent surface, so nothing but the
			// attribution rule can keep this interval out of the song.
			PlaybackEvent.Advance(40_000),
		)
		assertTrue(
			"the surface must be unattributed for this scenario to mean anything",
			harness.trace.presentationAttributionAmbiguous,
		)

		harness.feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(100_000),
			PlaybackEvent.Finalized("song ended"),
		)

		assertEquals(100_000L, harness.finalized.single().playedMs)
		assertEquals(emptyList<ReplayHarness.BroadcastPayload>(), harness.broadcasts)
	}

	/**
	 * Ad time may not carry a genuine sub-threshold play over the line. This is the
	 * user-visible harm: 45 s of interstitial plus 100 s of a 213 s song is 68 % of
	 * nothing anyone listened to.
	 */
	@Test
	fun `interstitial seconds cannot lift a sub-threshold song over the line`() {
		val harness = harness().feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = 45_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(40_000),
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(100_000),
			PlaybackEvent.Finalized("song ended"),
		)

		assertEquals(
			"140s would have been 65% and eligible; 100s is 46% and is not",
			emptyList<ReplayHarness.BroadcastPayload>(),
			harness.broadcasts,
		)
		assertEquals(100_000L, harness.finalized.single().playedMs)
	}

	/**
	 * The other half of the rule, and the one that must not change: a presentation
	 * that *was* proven keeps its progress across the boundary. A Song↔Video switch
	 * is one listen continuing, and this fix does not touch it.
	 */
	@Test
	fun `a proven presentation keeps its progress across an alternate-length switch`() {
		val harness = harness().feed(
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = songMs),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
			PlaybackEvent.Advance(100_000),
		)
		assertFalse(
			"the song must be attributed before the switch, or this proves nothing",
			harness.trace.presentationAttributionAmbiguous,
		)

		harness.feed(
			// The video rendering of the same work, at its own length.
			PlaybackEvent.SessionMetadata(title = title, artist = artist, durationMs = 240_000),
			PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 100_000),
			PlaybackEvent.Advance(40_000),
			PlaybackEvent.Finalized("song ended"),
		)

		assertEquals(
			"the 100s earned on the proven surface survives the switch",
			140_000L,
			harness.finalized.single().playedMs,
		)
	}
}
