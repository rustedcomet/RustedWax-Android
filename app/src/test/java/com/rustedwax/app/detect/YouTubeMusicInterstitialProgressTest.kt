package com.rustedwax.app.detect

import com.rustedwax.core.*
import com.rustedwax.core.PlaybackSourceCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Interstitials published under the named work's own metadata, from captured traces.
 *
 * YouTube Music publishes the upcoming song's title and artist while a pre-roll
 * owns the transport's duration and position. The captured interstitial bundle is
 * field-for-field identical to the song's — same keys set, same keys unset, no
 * advertisement key on either — so length, position and transport are the whole
 * of the available evidence.
 *
 * Two things follow, and both are pinned here. A presentation that has been
 * consumed whole and is then replaced by a materially longer one under the same
 * title cannot have been the work, whatever either length happens to be. And an
 * end-to-start wrap on this source is as likely a pod boundary as a repeat, so
 * the interval it bounds stops being attributable rather than being guessed at.
 *
 * Neither test is a duration threshold, and the contrast case throughout is
 * native YouTube, whose behaviour must not move at all.
 */
class YouTubeMusicInterstitialProgressTest {

	/** Native YouTube: no alternate presentations for one work. */
	private val nativeYouTube = PlaybackSourceCapabilities(
		republishesShorterDurations = true,
		requiresExactIdToCarryProgress = true,
		usesStoppedReplacementGrace = true,
		supportsPictureInPictureInference = true,
	)

	/** YouTube Music: Song and Video are alternate renderings of one work. */
	private val youTubeMusic = nativeYouTube.copy(republishesAlternateMediaDurations = true)

	private fun identity(
		title: String,
		artist: String?,
		durationMs: Long?,
	) = TrackIdentity(title, artist, null, durationMs, null)

	private fun listen(identity: TrackIdentity) = ListenState(
		trackIdentity = identity,
		instanceToken = 1,
		instanceEstablishedAtMillis = 0,
		startedAtEpochSec = 1_700_000_000,
		everPublishedMetadata = true,
	)

	private fun metadata(
		identity: TrackIdentity,
		elapsedRealtimeMs: Long,
		hasPreResolvedNativeId: Boolean = false,
	) = PlaybackInput.MetadataPublished(
		identity = identity,
		namesTabOnly = false,
		outgoingTransportHasExactId = false,
		hasPreResolvedNativeId = hasPreResolvedNativeId,
		outgoingTitle = null,
		nowMillis = 1_700_000_000_000 + elapsedRealtimeMs,
		elapsedRealtimeMs = elapsedRealtimeMs,
		nextInstanceToken = 2,
	)

	private fun transport(
		elapsedRealtimeMs: Long,
		positionMs: Long?,
		durationMs: Long?,
		previousPositionMs: Long? = null,
		transport: TransportState = TransportState.PLAYING,
		stopped: Boolean = false,
	) = PlaybackInput.TransportChanged(
		transport = if (stopped) TransportState.STOPPED else transport,
		speed = 1.0,
		previousPositionMs = previousPositionMs,
		newPositionMs = positionMs,
		rawPositionMs = positionMs,
		durationMs = durationMs,
		transportHasExactId = false,
		elapsedRealtimeMs = elapsedRealtimeMs,
	)

	private fun finalizeRequest(elapsedRealtimeMs: Long, reason: String) =
		PlaybackInput.FinalizeRequested(
			reason = reason,
			elapsedRealtimeMs = elapsedRealtimeMs,
			persistRestartTombstone = false,
		)

	private fun notes(transition: PlaybackReducer.Transition): String =
		(transition.before + transition.effects)
			.filterIsInstance<PlaybackEffect.Note>()
			.joinToString(" | ") { it.message }

	private fun finalizes(transition: PlaybackReducer.Transition): Boolean =
		(transition.before + transition.effects).any { it is PlaybackEffect.Finalize }

	/**
	 * The two inputs production emits together when a duration-corroborated route
	 * names the item — see `SessionProbe.requestNativeCarryAuthority` and
	 * `NativePreResolvedRoute.corroboratesPresentationDuration`.
	 *
	 * Kept as a pair on purpose: the id and the attribution are separate facts,
	 * and the tests below turn on exactly that separation. If this ever stops
	 * describing something production does, the end-to-end scenarios in
	 * `YouTubeMusicPresentationAttributionReplayTest` fail rather than these —
	 * that suite drives the real carry-authority path and injects nothing.
	 */
	private fun establishPresentation(
		reducer: PlaybackReducer,
		state: ListenState,
		sourceItemId: String,
		durationMs: Long,
	): ListenState {
		val identified = reducer.reduce(state, PlaybackInput.ExactIdEstablished(sourceItemId)).state
		return reducer.reduce(
			identified,
			PlaybackInput.PresentationAttributionEstablished(sourceItemId, durationMs),
		).state
	}

	/** The two interstitials of reproduction A, up to the moment before the song. */
	private fun abandonedPod(reducer: PlaybackReducer): ListenState {
		val title = "Never Gonna Give You Up"
		val artist = "Rick Astley"
		var state = reducer.reduce(
			listen(identity(title, artist, 30_093)),
			transport(elapsedRealtimeMs = 0, positionMs = 0, durationMs = 30_093),
		).state
		// The first interstitial runs out and the pod hands over to the second.
		state = reducer.reduce(
			state,
			transport(
				elapsedRealtimeMs = 30_000,
				positionMs = 0,
				durationMs = 30_093,
				previousPositionMs = 30_000,
			),
		).state
		state = reducer.reduce(state, metadata(identity(title, artist, 30_058), 30_100)).state
		return reducer.reduce(
			state,
			transport(elapsedRealtimeMs = 60_000, positionMs = 29_900, durationMs = 30_058),
		).state
	}

	// ── 1-3. an abandoned pre-roll may not become the song's progress ──────

	@Test
	fun `one unproven pre-roll abandoned by STOPPED reports no song progress`() {
		val reducer = PlaybackReducer(youTubeMusic)
		var state = reducer.reduce(
			listen(identity("Upcoming Song", "Artist", 30_000)),
			transport(elapsedRealtimeMs = 0, positionMs = 0, durationMs = 30_000),
		).state
		assertTrue(state.presentationUnprovenForNamedWork)
		state = reducer.reduce(
			state,
			transport(elapsedRealtimeMs = 29_000, positionMs = 29_000, durationMs = 30_000),
		).state
		state = reducer.reduce(
			state,
			transport(
				elapsedRealtimeMs = 29_000,
				positionMs = 29_000,
				durationMs = 30_000,
				stopped = true,
			),
		).state

		val ended = reducer.reduce(state, finalizeRequest(29_100, "stopped"))
		assertEquals(0L, ended.state.playedMsAt(29_100))
		assertTrue(ended.state.finalized)
	}

	@Test
	fun `one unproven pre-roll abandoned by teardown reports no song progress`() {
		val reducer = PlaybackReducer(youTubeMusic)
		var state = reducer.reduce(
			listen(identity("Upcoming Song", "Artist", 30_000)),
			transport(elapsedRealtimeMs = 0, positionMs = 0, durationMs = 30_000),
		).state
		state = reducer.reduce(
			state,
			transport(elapsedRealtimeMs = 29_000, positionMs = 29_000, durationMs = 30_000),
		).state
		val torndown = reducer.reduce(
			state,
			PlaybackInput.Disposed(
				finalize = true,
				allowContinuation = false,
				elapsedRealtimeMs = 29_100,
				continuationOpen = false,
			),
		)
		val ended = if (torndown.state.finalized) {
			torndown
		} else {
			reducer.reduce(torndown.state, finalizeRequest(29_100, "session ended"))
		}

		assertEquals(0L, ended.state.playedMsAt(29_100))
		assertTrue(ended.state.finalized)
	}

	@Test
	fun `a pre-roll abandoned by STOPPED reports no song progress`() {
		val reducer = PlaybackReducer(youTubeMusic)
		val pod = abandonedPod(reducer)
		assertTrue(
			"a pod boundary leaves the interval unattributable",
			pod.presentationUnprovenForNamedWork,
		)
		assertEquals("60s was measured on the interstitials", 60_000L, pod.playedMsAt(60_000))

		val stopped = reducer.reduce(
			pod,
			transport(
				elapsedRealtimeMs = 60_000,
				positionMs = 29_900,
				durationMs = 30_058,
				stopped = true,
			),
		)
		val ended = reducer.reduce(
			stopped.state,
			finalizeRequest(60_100, "stopped"),
		)

		assertTrue("the listen still ends exactly once", ended.state.finalized)
		assertEquals(
			"no interstitial time may be reported as the song's",
			0L,
			ended.state.playedMsAt(60_100),
		)
		assertTrue(notes(ended), notes(ended).contains("is not credited to it"))
	}

	@Test
	fun `a pre-roll abandoned by teardown reports no song progress`() {
		val reducer = PlaybackReducer(youTubeMusic)
		val pod = abandonedPod(reducer)

		val torndown = reducer.reduce(
			pod,
			PlaybackInput.Disposed(
				finalize = true,
				allowContinuation = false,
				continuationOpen = false,
				elapsedRealtimeMs = 60_100,
			),
		)
		// Whatever route the teardown takes, the terminal report is the one gate.
		val ended = if (torndown.state.finalized) {
			torndown
		} else {
			reducer.reduce(torndown.state, finalizeRequest(60_100, "session ended"))
		}

		assertTrue(ended.state.finalized)
		assertEquals(0L, ended.state.playedMsAt(60_100))
	}

	@Test
	fun `an abandoned pod cannot reach the threshold on any terminal reason`() {
		for (reason in listOf("stopped", "track change", "session destroyed", "disposed")) {
			val reducer = PlaybackReducer(youTubeMusic)
			val ended = reducer.reduce(abandonedPod(reducer), finalizeRequest(60_100, reason))
			val duration = ended.state.establishedDurationMs(30_058) ?: 0
			val played = ended.state.playedMsAt(60_100)
			assertEquals("[$reason] no progress may be reported", 0L, played)
			assertTrue(
				"[$reason] played $played of $duration must not reach 60%",
				duration == 0L || played * 100 / duration < 60,
			)
		}
	}

	// ── 7. the captured pre-roll cases stay fixed ─────────────────────────

	@Test
	fun `consecutive interstitials credit the song nothing and start it at zero`() {
		val title = "Never Gonna Give You Up"
		val artist = "Rick Astley"
		val reducer = PlaybackReducer(youTubeMusic)
		var state = abandonedPod(reducer)

		// 60s measured against a 30s surface. This is where the field finalized the
		// song at 202% before a note of it had played.
		assertEquals(
			"an unattributable surface may not arm a deadline for the work it names",
			null,
			state.idleFinalizeDelayMs(elapsedRealtimeMs = 60_000, durationMs = 30_058),
		)
		val deadline = reducer.reduce(
			state,
			PlaybackInput.IdleDeadlineReached(durationMs = 30_058, elapsedRealtimeMs = 60_000),
		)
		assertFalse("no finalize on the interstitial's own length", finalizes(deadline))
		assertEquals(state, deadline.state)

		// The song itself takes the transport over.
		val organic = reducer.reduce(state, metadata(identity(title, artist, 213_000), 60_100))
		assertFalse("the song may not be finalized by its own pre-roll", finalizes(organic))
		state = organic.state

		assertEquals("the song begins at zero", 0L, state.playedMs)
		assertEquals(0L, state.playedMsAt(60_100))
		assertEquals(213_000L, state.longestDurationMs)
		assertEquals(213_000L, state.organicPresentationDurationMs)
		assertFalse("the song inherits no loop from the pod", state.loopDetected)
		assertFalse(
			"organic measurement makes the listen attributable again",
			state.presentationUnprovenForNamedWork,
		)
		assertTrue(
			notes(organic),
			notes(organic).contains("its interval is discarded and organic measurement starts here"),
		)

		// 130s of the song is 61% of the song, and of nothing else.
		state = reducer.reduce(
			state,
			transport(elapsedRealtimeMs = 190_100, positionMs = 130_000, durationMs = 213_000),
		).state
		assertEquals(130_000L, state.playedMsAt(190_100))
		val ended = reducer.reduce(state, finalizeRequest(190_100, "stopped"))
		assertEquals("the song reports its own time", 130_000L, ended.state.playedMsAt(190_100))
	}

	@Test
	fun `a pre-roll adds no lead to the song's played time`() {
		val title = "Somebody's Watching Me (Official Music Video)"
		val artist = "Rockwell"
		val reducer = PlaybackReducer(youTubeMusic)

		var state = reducer.reduce(
			listen(identity(title, artist, 15_082)),
			transport(elapsedRealtimeMs = 0, positionMs = 0, durationMs = 15_082),
		).state
		// The song's metadata lands just after the interstitial has run out, as it
		// did in the capture.
		state = reducer.reduce(state, metadata(identity(title, artist, 215_713), 15_534)).state
		assertEquals(0L, state.playedMs)
		assertFalse(state.presentationUnprovenForNamedWork)

		// The captured Phase 3 snapshot: position 69 551 ms into the song. Played
		// was 97 437 ms there — 27 886 ms of it the pre-roll's.
		val songPositionMs = 69_551L
		val atSnapshot = 15_534 + songPositionMs
		state = reducer.reduce(
			state,
			transport(
				elapsedRealtimeMs = atSnapshot,
				positionMs = songPositionMs,
				durationMs = 215_713,
			),
		).state

		val played = state.playedMsAt(atSnapshot)
		assertEquals(songPositionMs, played)
		assertTrue(
			"played $played must track song position $songPositionMs",
			kotlin.math.abs(played - songPositionMs) <= 2_000,
		)
		assertEquals(215_713L, state.establishedDurationMs(215_713))
	}

	// ── 4. a genuine short Song → its longer Video keeps its progress ─────

	@Test
	fun `a 45s song switched to its 120s video keeps every second measured`() {
		val title = "Interlude"
		val artist = "Bring Me The Horizon"
		val reducer = PlaybackReducer(youTubeMusic)

		var state = reducer.reduce(
			listen(identity(title, artist, 45_000)),
			transport(elapsedRealtimeMs = 0, positionMs = 0, durationMs = 45_000),
		).state
		state = establishPresentation(reducer, state, "interlude45", 45_000)
		// The user switches presentation 20s in — the work is still playing, so the
		// outgoing presentation was never consumed.
		state = reducer.reduce(
			state,
			transport(elapsedRealtimeMs = 20_000, positionMs = 20_000, durationMs = 45_000),
		).state
		val switched = reducer.reduce(
			state,
			metadata(identity(title, artist, 120_000), 20_000, hasPreResolvedNativeId = true),
		)
		state = switched.state

		assertEquals("crossing 60s is not evidence of anything", 20_000L, state.playedMsAt(20_000))
		assertNotEquals(0L, state.playedMs)
		assertEquals(120_000L, state.longestDurationMs)
		assertFalse(state.presentationUnprovenForNamedWork)
		assertTrue(notes(switched), notes(switched).contains("alternate media length"))

		val ended = reducer.reduce(state, finalizeRequest(20_000, "stopped"))
		assertEquals(20_000L, ended.state.playedMsAt(20_000))
	}

	@Test
	fun `a proven presentation that naturally ends keeps progress across a later duration update`() {
		val title = "Interlude"
		val artist = "Bring Me The Horizon"
		val reducer = PlaybackReducer(youTubeMusic)

		var state = reducer.reduce(
			listen(identity(title, artist, 15_000)),
			transport(elapsedRealtimeMs = 0, positionMs = 0, durationMs = 15_000),
		).state
		state = establishPresentation(reducer, state, "interlude15", 15_000)
		state = reducer.reduce(
			state,
			transport(elapsedRealtimeMs = 15_000, positionMs = 15_000, durationMs = 15_000),
		).state
		val updated = reducer.reduce(
			state,
			metadata(identity(title, artist, 215_000), 15_000, hasPreResolvedNativeId = true),
		)

		assertEquals(
			"positive attribution, not whether the surface was spent, controls retention",
			15_000L,
			updated.state.playedMsAt(15_000),
		)
		assertEquals(215_000L, updated.state.longestDurationMs)
		assertTrue(notes(updated), notes(updated).contains("alternate media length"))
	}

	// ── 5. an ordinary correction across 60s keeps its progress ───────────

	@Test
	fun `a 61s to 58s duration correction keeps the listen intact`() {
		val title = "Short Track"
		val artist = "Someone"
		val reducer = PlaybackReducer(youTubeMusic)

		var state = reducer.reduce(
			listen(identity(title, artist, 61_000)),
			transport(elapsedRealtimeMs = 0, positionMs = 0, durationMs = 61_000),
		).state
		state = establishPresentation(reducer, state, "shorttrack61", 61_000)
		state = reducer.reduce(
			state,
			transport(elapsedRealtimeMs = 30_000, positionMs = 30_000, durationMs = 61_000),
		).state
		val corrected = reducer.reduce(
			state,
			metadata(identity(title, artist, 58_000), 30_000, hasPreResolvedNativeId = true),
		)
		state = corrected.state

		assertEquals("a correction is not a replacement to quarantine", 30_000L, state.playedMsAt(30_000))
		assertEquals(null, state.durationReplacementMs)
		assertFalse(state.presentationUnprovenForNamedWork)

		state = reducer.reduce(
			state,
			transport(elapsedRealtimeMs = 50_000, positionMs = 50_000, durationMs = 58_000),
		).state
		val ended = reducer.reduce(state, finalizeRequest(50_000, "stopped"))
		assertEquals("the corrected listen keeps measuring", 50_000L, ended.state.playedMsAt(50_000))
		val duration = ended.state.establishedDurationMs(58_000) ?: 0
		assertTrue(
			"50s of $duration must still be able to reach the threshold",
			50_000L * 100 / duration >= 60,
		)
	}

	// ── 6. a legitimate short track is never an interstitial by length ────

	@Test
	fun `a legitimate 30s track scrobbles on its own terms`() {
		val title = "Skit"
		val artist = "Someone"
		val reducer = PlaybackReducer(youTubeMusic)

		var state = reducer.reduce(
			listen(identity(title, artist, 30_000)),
			transport(elapsedRealtimeMs = 0, positionMs = 0, durationMs = 30_000),
		).state
		val identified = reducer.reduce(
			state,
			PlaybackInput.ExactIdEstablished("skit30proof"),
		).state
		assertTrue(
			"an exact work id alone cannot attribute the duration surface",
			identified.presentationUnprovenForNamedWork,
		)
		val wrongDuration = reducer.reduce(
			identified,
			PlaybackInput.PresentationAttributionEstablished("skit30proof", 29_000),
		).state
		assertTrue(
			"proof for another duration cannot clear the current presentation",
			wrongDuration.presentationUnprovenForNamedWork,
		)
		state = reducer.reduce(
			wrongDuration,
			PlaybackInput.PresentationAttributionEstablished("skit30proof", 30_000),
		).state
		assertFalse(
			"being short is not evidence of being an interstitial",
			state.presentationUnprovenForNamedWork,
		)
		assertEquals(
			"a short track arms its own idle deadline exactly as any other does",
			30_000L - state.playedMsAt(10_000) + PlaybackReducer.IDLE_FINALIZE_GRACE_MS,
			state.idleFinalizeDelayMs(elapsedRealtimeMs = 10_000, durationMs = 30_000),
		)

		state = reducer.reduce(
			state,
			transport(elapsedRealtimeMs = 29_000, positionMs = 29_000, durationMs = 30_000),
		).state
		val ended = reducer.reduce(state, finalizeRequest(29_000, "stopped"))
		assertEquals("its whole listen is its own", 29_000L, ended.state.playedMsAt(29_000))
		assertTrue(29_000L * 100 / 30_000L >= 60)
	}

	// ── 8. consecutive interstitials stay loop and finalize safe ──────────

	@Test
	fun `an interstitial pod never hands the song a loop or a finalize`() {
		val title = "Never Gonna Give You Up"
		val reducer = PlaybackReducer(youTubeMusic)
		var state = listen(identity(title, "Rick Astley", 30_093))
		state = reducer.reduce(
			state,
			transport(elapsedRealtimeMs = 0, positionMs = 0, durationMs = 30_093),
		).state
		repeat(3) { pod ->
			val boundary = reducer.reduce(
				state,
				transport(
					elapsedRealtimeMs = 30_000L * (pod + 1),
					positionMs = 0,
					durationMs = 30_093,
					previousPositionMs = 30_000,
				),
			)
			state = boundary.state
			assertFalse("boundary ${pod + 1} must not finalize", finalizes(boundary))
			assertTrue(
				"boundary ${pod + 1} leaves the interval unattributable",
				state.presentationUnprovenForNamedWork,
			)
			assertEquals(
				"no deadline may be armed on an interstitial's length",
				null,
				state.idleFinalizeDelayMs(30_000L * (pod + 1), 30_093),
			)
		}
		state = reducer.reduce(state, metadata(identity(title, "Rick Astley", 213_000), 90_100)).state
		assertFalse("the song inherits no loop", state.loopDetected)
		assertFalse(state.presentationUnprovenForNamedWork)
		assertEquals(0L, state.playedMs)
	}

	@Test
	fun `a repeat after the song is established stays a genuine repeat`() {
		val title = "Never Gonna Give You Up"
		val reducer = PlaybackReducer(youTubeMusic)
		var state = reducer.reduce(
			listen(identity(title, "Rick Astley", 15_082)),
			transport(elapsedRealtimeMs = 0, positionMs = 0, durationMs = 15_082),
		).state
		// The song supersedes the pre-roll, which is what proves a presentation.
		state = reducer.reduce(state, metadata(identity(title, "Rick Astley", 213_000), 15_534)).state
		state = reducer.reduce(
			state,
			transport(elapsedRealtimeMs = 228_000, positionMs = 212_000, durationMs = 213_000),
		).state
		// The user repeats it.
		state = reducer.reduce(
			state,
			transport(
				elapsedRealtimeMs = 229_000,
				positionMs = 0,
				durationMs = 213_000,
				previousPositionMs = 212_000,
			),
		).state

		assertTrue("a repeat of a proven presentation is still a repeat", state.loopDetected)
		assertFalse(
			"a proven presentation is not un-proven by playing again",
			state.presentationUnprovenForNamedWork,
		)
		val ended = reducer.reduce(state, finalizeRequest(229_000, "stopped"))
		assertEquals("the established song keeps its measured time", 213_466L, ended.state.playedMsAt(229_000))
	}

	// ── 9. native YouTube is untouched ────────────────────────────────────

	@Test
	fun `native YouTube keeps its own organic anchor reset`() {
		val title = "PSY - GANGNAM STYLE(강남스타일) M_V"
		val reducer = PlaybackReducer(nativeYouTube)

		var state = reducer.reduce(
			listen(identity(title, "officialpsy", 7_000)),
			transport(elapsedRealtimeMs = 0, positionMs = 0, durationMs = 7_000),
		).state
		val superseded = reducer.reduce(state, metadata(identity(title, "officialpsy", 252_000), 5_000))
		state = superseded.state
		assertEquals(0L, state.playedMs)
		assertEquals(252_000L, state.longestDurationMs)
		assertTrue(
			notes(superseded),
			notes(superseded).contains("its interval is discarded and organic measurement starts here"),
		)
	}

	@Test
	fun `native YouTube still quarantines rather than supersedes below its own factor`() {
		val title = "Nobody Is Buying Jordans Anymore"
		val reducer = PlaybackReducer(nativeYouTube)

		var state = reducer.reduce(
			listen(identity(title, "Ballinonabudget", 30_000)),
			transport(elapsedRealtimeMs = 0, positionMs = 0, durationMs = 30_000),
		).state
		// Spent by its own length, which for a source without alternate media
		// presentations must change nothing at all.
		val replaced = reducer.reduce(state, metadata(identity(title, "Ballinonabudget", 213_000), 40_000))
		state = replaced.state
		assertEquals(
			"a 7.1x native replacement is still a quarantine, not a supersede",
			213_000L,
			state.durationReplacementMs,
		)
		assertEquals(40_000L, state.playedMs)
		assertTrue(notes(replaced), notes(replaced).contains("quarantining replacement measurement"))
	}

	@Test
	fun `native YouTube wraps and finalizes exactly as it always did`() {
		val title = "Some Video"
		val reducer = PlaybackReducer(nativeYouTube)
		var state = reducer.reduce(
			listen(identity(title, "Someone", 30_000)),
			transport(elapsedRealtimeMs = 0, positionMs = 0, durationMs = 30_000),
		).state
		state = reducer.reduce(
			state,
			transport(
				elapsedRealtimeMs = 30_000,
				positionMs = 0,
				durationMs = 30_000,
				previousPositionMs = 29_000,
			),
		).state
		assertTrue(state.loopDetected)
		assertFalse(
			"the unattributable fact is never set for a source without alternate media",
			state.presentationUnprovenForNamedWork,
		)
		val ended = reducer.reduce(state, finalizeRequest(30_000, "stopped"))
		assertEquals(30_000L, ended.state.playedMsAt(30_000))
	}

	@Test
	fun `native YouTube ignores Music presentation attribution`() {
		val reducer = PlaybackReducer(nativeYouTube)
		val identified = reducer.reduce(
			listen(identity("Some Video", "Channel", 30_000)),
			PlaybackInput.ExactIdEstablished("native-video"),
		).state
		val attributed = reducer.reduce(
			identified,
			PlaybackInput.PresentationAttributionEstablished("native-video", 30_000),
		).state

		assertEquals(null, attributed.organicPresentationDurationMs)
		assertFalse(attributed.presentationUnprovenForNamedWork)
	}

	// ── ordinary YouTube Music playback, and Bug 4's established case ─────

	@Test
	fun `a song with no interstitial measures exactly as before`() {
		val title = "Sleepwalking"
		val reducer = PlaybackReducer(youTubeMusic)

		var state = reducer.reduce(
			listen(identity(title, "Bring Me The Horizon", 213_000)),
			transport(elapsedRealtimeMs = 0, positionMs = 0, durationMs = 213_000),
		).state
		state = establishPresentation(reducer, state, "sleepwalking", 213_000)
		assertFalse(state.presentationUnprovenForNamedWork)
		state = reducer.reduce(
			state,
			transport(elapsedRealtimeMs = 130_000, positionMs = 130_000, durationMs = 213_000),
		).state
		assertEquals(130_000L, state.playedMsAt(130_000))
		assertEquals(null, state.durationReplacementMs)
		assertEquals(
			213_000L - 130_000L + PlaybackReducer.IDLE_FINALIZE_GRACE_MS,
			state.idleFinalizeDelayMs(elapsedRealtimeMs = 130_000, durationMs = 213_000),
		)
		val ended = reducer.reduce(state, finalizeRequest(130_000, "stopped"))
		assertEquals(130_000L, ended.state.playedMsAt(130_000))
	}

	@Test
	fun `a Song to Video switch stays one listen with its progress intact`() {
		val reducer = PlaybackReducer(youTubeMusic)
		val song = identity("Somebody's Watching Me", "Rockwell", 298_941)
		val video = identity("Somebody's Watching Me", "Rockwell", 215_713)

		var state = reducer.reduce(
			listen(song),
			transport(elapsedRealtimeMs = 0, positionMs = 0, durationMs = 298_941),
		).state
		state = establishPresentation(reducer, state, "song-presentation", 298_941)
		val switched = reducer.reduce(
			state,
			metadata(video, 90_000, hasPreResolvedNativeId = true),
		)
		state = switched.state

		assertEquals("the switch keeps the listen's progress", 90_000L, state.playedMsAt(90_000))
		assertEquals(298_941L, state.longestDurationMs)
		assertEquals(null, state.durationReplacementMs)
		assertFalse(state.presentationUnprovenForNamedWork)
		assertTrue(notes(switched), notes(switched).contains("alternate media length"))
		val ended = reducer.reduce(state, finalizeRequest(90_000, "stopped"))
		assertEquals(90_000L, ended.state.playedMsAt(90_000))
	}
}
