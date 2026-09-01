package com.rustedwax.app.detect

import com.rustedwax.core.*
import com.rustedwax.core.PlaybackSourceCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playback state machine, driven directly.
 *
 * `<redacted-private-path>` §8 names the gap this closes: "no behavioral test
 * driving the real private callback-to-reducer/finalization state
 * machine". It was private, it was inner, and it was written against
 * `MediaController`, `PlaybackState` and `android.os.SystemClock`, so nothing on
 * the JVM could reach it at all.
 *
 * The replay corpus exercises the reducer end to end through the engine. This
 * file exercises it on its own, where a single transition can be stated and
 * checked without a listen having to survive identity, policy and dedup first.
 */
class PlaybackReducerTest {

	private val browser = PlaybackSourceCapabilities(
		republishesShorterDurations = false,
		requiresExactIdToCarryProgress = false,
		usesStoppedReplacementGrace = false,
		supportsPictureInPictureInference = false,
	)

	private val nativeApp = PlaybackSourceCapabilities(
		republishesShorterDurations = true,
		requiresExactIdToCarryProgress = true,
		usesStoppedReplacementGrace = true,
		supportsPictureInPictureInference = true,
	)

	private fun identity(
		title: String? = "Sleepwalking",
		artist: String? = "Bring Me The Horizon",
		durationMs: Long? = 213_000,
		sourceItemId: String? = null,
	) = TrackIdentity(title, artist, null, durationMs, sourceItemId)

	private fun start(
		capabilities: PlaybackSourceCapabilities = browser,
		identity: TrackIdentity = identity(),
	): Pair<PlaybackReducer, ListenState> = PlaybackReducer(capabilities) to ListenState(
		trackIdentity = identity,
		instanceToken = 1,
		instanceEstablishedAtMillis = 0,
		startedAtEpochSec = 1_700_000_000,
		everPublishedMetadata = true,
	)

	private fun metadata(
		identity: TrackIdentity,
		nowMillis: Long = 1_700_000_000_000,
		elapsedRealtimeMs: Long = 0,
		nextInstanceToken: Long = 2,
		namesTabOnly: Boolean = false,
		outgoingTransportHasExactId: Boolean = false,
		hasPreResolvedNativeId: Boolean = false,
		outgoingTitle: String? = null,
	) = PlaybackInput.MetadataPublished(
		identity = identity,
		namesTabOnly = namesTabOnly,
		outgoingTransportHasExactId = outgoingTransportHasExactId,
		hasPreResolvedNativeId = hasPreResolvedNativeId,
		outgoingTitle = outgoingTitle,
		nowMillis = nowMillis,
		elapsedRealtimeMs = elapsedRealtimeMs,
		nextInstanceToken = nextInstanceToken,
	)

	private fun playing(
		elapsedRealtimeMs: Long,
		speed: Double = 1.0,
		positionMs: Long? = 0,
	) = PlaybackInput.TransportChanged(
		transport = TransportState.PLAYING,
		speed = speed,
		previousPositionMs = null,
		newPositionMs = positionMs,
		rawPositionMs = positionMs,
		durationMs = 213_000,
		transportHasExactId = false,
		elapsedRealtimeMs = elapsedRealtimeMs,
	)

	@Test
	fun `PLAYING after STOPPED without metadata starts an unidentified generation`() {
		val exact = identity(sourceItemId = "aaaaaaaaaaa")
		val (reducer, initial) = start(nativeApp, exact)
		val running = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state
		val stopped = reducer.reduce(
			running,
			PlaybackInput.TransportChanged(
				transport = TransportState.STOPPED,
				speed = 1.0,
				previousPositionMs = 40_000,
				newPositionMs = 40_000,
				rawPositionMs = 40_000,
				durationMs = 213_000,
				transportHasExactId = true,
				elapsedRealtimeMs = 40_000,
			),
		).let { reducer.reduce(it.state, PlaybackInput.FinalizeRequested("stopped", 40_000)).state }

		val later = reducer.reduce(
			stopped,
			playing(elapsedRealtimeMs = 50_000).copy(
				nowMillis = 1_700_000_050_000,
				nextInstanceToken = 2,
			),
		).state

		assertFalse(later.finalized)
		assertFalse(later.trackIdentity.isUsable)
		assertFalse(later.metadataAuthoritativeForCurrentPlayback)
		assertTrue(later.metadataInvalidatedAtPlaybackBoundary)
		assertEquals(2, later.instanceToken)
		assertEquals(0, later.playedMs)
	}

	@Test
	fun `fresh metadata after an unidentified generation starts authority at that observation`() {
		val (reducer, initial) = start(
			nativeApp,
			identity = TrackIdentity(null, null, null, null),
		)
		val unidentified = initial.copy(
			transport = TransportState.PLAYING,
			playingSinceElapsedMs = 10_000,
			metadataAuthoritativeForCurrentPlayback = false,
			metadataObservedSincePlaybackBoundary = false,
			metadataInvalidatedAtPlaybackBoundary = true,
		)
		val fresh = identity(title = "Arrived late", sourceItemId = "bbbbbbbbbbb")

		val adopted = reducer.reduce(
			unidentified,
			metadata(
				identity = fresh,
				nowMillis = 1_700_000_020_000,
				elapsedRealtimeMs = 20_000,
				nextInstanceToken = 2,
			),
		).state

		assertEquals(fresh, adopted.trackIdentity)
		assertEquals(0, adopted.playedMs)
		assertEquals(20_000L, adopted.playingSinceElapsedMs)
		assertTrue(adopted.metadataAuthoritativeForCurrentPlayback)
		assertFalse(adopted.metadataInvalidatedAtPlaybackBoundary)
	}

	// ---- 1. progress and speed accounting -------------------------------------

	@Test
	fun `played time is content consumed, scaled by the rate`() {
		// The 2026-07-29 regression in one assertion: 50 s of wall clock at 1.25×
		// is 62.5 s of a video, and the threshold divides by the video's length.
		val (reducer, initial) = start()
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 1_000, speed = 1.25)).state

		assertEquals(62_500, playing.playedMsAt(elapsedRealtimeMs = 51_000))
	}

	@Test
	fun `a paused clock stops accruing and keeps what it earned`() {
		val (reducer, initial) = start()
		val started = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state
		val paused = reducer.reduce(
			started,
			PlaybackInput.TransportChanged(
				transport = TransportState.PAUSED,
				speed = 1.0,
				previousPositionMs = 30_000,
				newPositionMs = 30_000,
				rawPositionMs = 30_000,
				durationMs = 213_000,
				transportHasExactId = false,
				elapsedRealtimeMs = 30_000,
			),
		).state

		assertEquals(30_000, paused.playedMs)
		// Sixty seconds later, still paused, still thirty.
		assertEquals(30_000, paused.playedMsAt(elapsedRealtimeMs = 90_000))
	}

	@Test
	fun `the window that just ended is scored at the rate it was played at`() {
		// Not at the rate being switched to. Reading the new rate would credit a
		// listen that ran at 1× as if it had run at 2× the moment someone changed
		// the speed at the very end.
		val (reducer, initial) = start()
		val atOneX = reducer.reduce(initial, playing(elapsedRealtimeMs = 0, speed = 1.0)).state
		val atTwoX = reducer.reduce(atOneX, playing(elapsedRealtimeMs = 10_000, speed = 2.0)).state

		assertEquals(10_000, atTwoX.playedMs)
		assertEquals(2.0, atTwoX.speed, 0.0)
		assertEquals(30_000, atTwoX.playedMsAt(elapsedRealtimeMs = 20_000))
	}

	@Test
	fun `an absurd reported rate is clamped rather than believed`() {
		assertEquals(PlaybackReducer.MAX_PLAYBACK_SPEED, PlaybackReducer.speedFactor(500f), 0.0)
		assertEquals(1.0, PlaybackReducer.speedFactor(0f), 0.0)
		assertEquals(1.0, PlaybackReducer.speedFactor(null), 0.0)
		assertEquals(1.0, PlaybackReducer.speedFactor(Float.NaN), 0.0)
	}

	@Test
	fun `verified lead in is added before the currently measured window`() {
		val (reducer, initial) = start(
			identity = identity("This Save Changed The Whole Rally", durationMs = 59_561),
		)
		val state = initial.copy(
			startedAtEpochSec = 33,
			instanceEstablishedAtMillis = 33_000,
			transport = TransportState.PLAYING,
			playingSinceElapsedMs = 10_000,
		)

		val credited = reducer.reduce(
			state,
			PlaybackInput.VerifiedLeadIn(
				playedMs = 32_000,
				startedAtEpochSec = 1,
				elapsedRealtimeMs = 12_000,
			),
		).state

		assertEquals(34_000, credited.playedMs)
		assertEquals(1, credited.startedAtEpochSec)
		assertEquals(12_000L, credited.playingSinceElapsedMs)
		assertEquals(37_000, credited.playedMsAt(15_000))
	}

	@Test
	fun `a position wrap from the end to the start is a loop, a seek is not`() {
		val (reducer, initial) = start()
		val started = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state

		val wrapped = reducer.reduce(
			started,
			PlaybackInput.TransportChanged(
				transport = TransportState.PLAYING,
				speed = 1.0,
				previousPositionMs = 210_000,
				newPositionMs = 1_000,
				rawPositionMs = 1_000,
				durationMs = 213_000,
				transportHasExactId = false,
				elapsedRealtimeMs = 210_000,
			),
		)
		assertTrue(wrapped.state.loopDetected)

		val seeked = reducer.reduce(
			started,
			PlaybackInput.TransportChanged(
				transport = TransportState.PLAYING,
				speed = 1.0,
				previousPositionMs = 120_000,
				newPositionMs = 60_000,
				rawPositionMs = 60_000,
				durationMs = 213_000,
				transportHasExactId = false,
				elapsedRealtimeMs = 120_000,
			),
		)
		assertFalse(seeked.state.loopDetected)
	}

	// ---- 2. metadata refinement and track transitions --------------------------

	@Test
	fun `a track change finalizes the outgoing listen before starting the next`() {
		// The ordering is the whole point: `Finalize` reads the listen that is
		// ending, so it has to be in the half of the transition that runs before
		// the new state is installed. If it slipped into the other half, the
		// snapshot would describe the incoming track with the outgoing track's
		// progress.
		val (reducer, initial) = start()
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state
		val transition = reducer.reduce(
			playing,
			metadata(identity(title = "Doomed", durationMs = 300_000), elapsedRealtimeMs = 100_000),
		)

		assertTrue(transition.before.any { it is PlaybackEffect.Finalize })
		assertFalse(transition.effects.any { it is PlaybackEffect.Finalize })
		assertTrue(PlaybackEffect.ClearTrackScopedEvidence in transition.effects)
		assertTrue(PlaybackEffect.InstallMetadata in transition.effects)
		assertTrue(PlaybackEffect.RestoreCarriedProgress in transition.effects)

		// The new listen starts from zero, with a new instance and a new frozen start.
		assertEquals(0L, transition.state.playedMs)
		assertEquals(2L, transition.state.instanceToken)
		assertEquals("Doomed", transition.state.trackIdentity.title)
	}

	@Test
	fun `refined metadata is the same track and keeps its progress`() {
		val (reducer, initial) = start(identity = identity(durationMs = null))
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state
		val refined = reducer.reduce(
			playing,
			metadata(identity(durationMs = 213_000), elapsedRealtimeMs = 60_000),
		)

		assertFalse(refined.before.any { it is PlaybackEffect.Finalize })
		assertEquals(1L, refined.state.instanceToken)
		assertEquals(213_000L, refined.state.trackIdentity.durationMs)
		assertEquals(60_000, refined.state.playedMsAt(elapsedRealtimeMs = 60_000))
	}

	@Test
	fun `an empty bundle mid-track is silence, not a track ending`() {
		// Measured 2026-08-07: a 155-second trailer was finalized against a
		// placeholder on every tab switch and finished having banked 18 seconds.
		val (reducer, initial) = start()
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state
		val empty = reducer.reduce(
			playing,
			metadata(
				TrackIdentity(null, null, null, null),
				elapsedRealtimeMs = 60_000,
				outgoingTitle = "Sleepwalking",
			),
		)

		assertFalse(empty.before.any { it is PlaybackEffect.Finalize })
		// And the bundle is deliberately not installed: it would erase what this
		// track established.
		assertFalse(PlaybackEffect.InstallMetadata in empty.effects)
		assertEquals("Sleepwalking", empty.state.trackIdentity.title)
		assertEquals(60_000, empty.state.playedMsAt(elapsedRealtimeMs = 60_000))
	}

	@Test
	fun `a freshly created session's first real metadata is not a track change`() {
		val (reducer, _) = start()
		val placeholder = ListenState(
			trackIdentity = TrackIdentity(null, null, null, null),
			instanceToken = 1,
			instanceEstablishedAtMillis = 0,
			startedAtEpochSec = 1_700_000_000,
			everPublishedMetadata = true,
		)
		val transition = reducer.reduce(placeholder, metadata(identity()))

		assertFalse(transition.before.any { it is PlaybackEffect.Finalize })
		assertTrue(PlaybackEffect.RestoreCarriedProgress in transition.effects)
		assertEquals("Sleepwalking", transition.state.trackIdentity.title)
	}

	@Test
	fun `the browser naming its tab ends the track and starts nothing`() {
		// Measured 2026-08-11: Brave published TITLE = "YouTube" and then said
		// nothing for seven minutes while a real video played, and all seven
		// minutes landed on it.
		val (reducer, initial) = start()
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state
		val tabOnly = reducer.reduce(
			playing,
			metadata(
				TrackIdentity(null, null, null, null),
				namesTabOnly = true,
				elapsedRealtimeMs = 100_000,
			),
		)

		assertTrue(tabOnly.before.any { it is PlaybackEffect.Finalize })
		assertTrue(tabOnly.state.describingTabOnly)
		// The clock is stopped, not restarted, so the gap belongs to nobody.
		assertEquals(null, tabOnly.state.playingSinceElapsedMs)
		assertEquals(0, tabOnly.state.playedMsAt(elapsedRealtimeMs = 500_000))
	}

	@Test
	fun `a native session republishing a shorter length quarantines its measurement`() {
		// Measured 2026-08-06: a 415 s live set reported 415 s, then 11 s, then
		// 6 s while still playing the same thing.
		val (reducer, initial) = start(nativeApp, identity(durationMs = 415_000))
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state
		val shorter = reducer.reduce(
			playing,
			metadata(identity(durationMs = 11_000), elapsedRealtimeMs = 200_000),
		)

		assertFalse(shorter.before.any { it is PlaybackEffect.Finalize })
		assertEquals(415_000L, shorter.state.longestDurationMs)
		assertEquals(11_000L, shorter.state.durationReplacementMs)
		assertEquals(null, shorter.state.playingSinceElapsedMs)
		assertEquals(415_000L, shorter.state.establishedDurationMs(11_000))
		assertEquals(200_000, shorter.state.playedMsAt(elapsedRealtimeMs = 200_000))
		assertEquals(200_000, shorter.state.playedMsAt(elapsedRealtimeMs = 500_000))
	}

	@Test
	fun `late artist plus shorter duration remains one quarantined organic listen`() {
		val (reducer, initial) = start(
			nativeApp,
			identity(artist = null, durationMs = 952_000),
		)
		val running = reducer.reduce(initial, playing(elapsedRealtimeMs = 0, positionMs = 300_000)).state
		val positioned = reducer.reduce(running, PlaybackInput.PositionSeen(300_000)).state
		val replacement = reducer.reduce(
			positioned,
			metadata(
				identity(artist = "Ballinonabudget", durationMs = 7_000),
				elapsedRealtimeMs = 300_000,
			),
		)

		assertFalse(replacement.before.any { it is PlaybackEffect.Finalize })
		assertEquals(1, replacement.state.instanceToken)
		assertEquals("Ballinonabudget", replacement.state.trackIdentity.artist)
		assertEquals(300_000, replacement.state.playedMs)
		assertEquals(300_000L, replacement.state.durationReplacementOrganicPositionMs)
	}

	@Test
	fun `replacement STOPPED generations make no outcome and organic position return resumes the clock`() {
		val (reducer, initial) = start(nativeApp, identity(durationMs = 952_000))
		val running = reducer.reduce(initial, playing(elapsedRealtimeMs = 0, positionMs = 300_000)).state
		val positioned = reducer.reduce(running, PlaybackInput.PositionSeen(300_000)).state
		val short = reducer.reduce(
			positioned,
			metadata(identity(durationMs = 77_000), elapsedRealtimeMs = 300_000),
		).state
		val stopped = reducer.reduce(
			short,
			PlaybackInput.TransportChanged(
				transport = TransportState.STOPPED,
				speed = 1.0,
				previousPositionMs = 77_000,
				newPositionMs = 77_000,
				rawPositionMs = 77_000,
				durationMs = 77_000,
				transportHasExactId = false,
				elapsedRealtimeMs = 377_000,
			),
		)
		val resumed = reducer.reduce(
			stopped.state,
			playing(elapsedRealtimeMs = 378_000, positionMs = 300_500).copy(
				previousPositionMs = 77_000,
				durationMs = 77_000,
			),
		)

		assertFalse(stopped.effects.any { it is PlaybackEffect.Finalize })
		assertFalse(resumed.before.any { it is PlaybackEffect.Finalize })
		assertEquals(null, resumed.state.durationReplacementMs)
		assertEquals(1, resumed.state.instanceToken)
		assertEquals(300_000, resumed.state.playedMsAt(378_000))
		assertEquals(320_000, resumed.state.playedMsAt(398_000))
	}

	@Test
	fun `replacement ending at a replay from zero cannot inherit organic progress`() {
		val (reducer, initial) = start(nativeApp, identity(durationMs = 952_000))
		val running = reducer.reduce(initial, playing(elapsedRealtimeMs = 0, positionMs = 300_000)).state
		val positioned = reducer.reduce(running, PlaybackInput.PositionSeen(300_000)).state
		val short = reducer.reduce(
			positioned,
			metadata(identity(durationMs = 77_000), elapsedRealtimeMs = 300_000),
		).state
		val stopped = reducer.reduce(
			short,
			playing(elapsedRealtimeMs = 377_000, positionMs = 0).copy(
				transport = TransportState.STOPPED,
				previousPositionMs = 77_000,
				newPositionMs = 77_000,
				rawPositionMs = 77_000,
				durationMs = 77_000,
			),
		).state
		val replay = reducer.reduce(
			stopped,
			playing(elapsedRealtimeMs = 378_000, positionMs = 0).copy(
				previousPositionMs = 77_000,
				durationMs = 77_000,
			),
		)

		assertEquals(77_000L, replay.state.durationReplacementMs)
		assertEquals(null, replay.state.playingSinceElapsedMs)
		assertEquals(300_000, replay.state.playedMsAt(500_000))
		assertFalse(replay.before.any { it is PlaybackEffect.Finalize })
	}

	@Test
	fun `a materially longer same-title presentation is quarantined too`() {
		val (reducer, initial) = start(nativeApp, identity(durationMs = 1_192_000))
		val running = reducer.reduce(initial, playing(0, positionMs = 0)).state
		val positioned = reducer.reduce(running, PlaybackInput.PositionSeen(325_000)).state
		val replacement = reducer.reduce(
			positioned,
			metadata(identity(durationMs = 2_163_000), elapsedRealtimeMs = 325_000),
		)
		val replacementPlaying = reducer.reduce(
			replacement.state,
			playing(326_000, positionMs = 40).copy(previousPositionMs = 325_000),
		)

		assertFalse(replacement.before.any { it is PlaybackEffect.Finalize })
		assertEquals(2_163_000L, replacement.state.durationReplacementMs)
		assertEquals(1_192_000L, replacement.state.trackIdentity.durationMs)
		assertEquals(325_000L, replacement.state.playedMsAt(500_000))
		assertEquals(null, replacement.state.playingSinceElapsedMs)
		assertFalse(replacementPlaying.before.any { it is PlaybackEffect.Finalize })
		assertEquals(325_000L, replacementPlaying.state.playedMsAt(500_000))
	}

	@Test
	fun `a stopped organic metadata return resumes only at its prior position`() {
		val (reducer, initial) = start(nativeApp, identity(durationMs = 952_000))
		val running = reducer.reduce(initial, playing(0, positionMs = 300_000)).state
		val positioned = reducer.reduce(running, PlaybackInput.PositionSeen(300_000)).state
		val short = reducer.reduce(
			positioned,
			metadata(identity(durationMs = 77_000), elapsedRealtimeMs = 300_000),
		).state
		val stopped = reducer.reduce(
			short,
			playing(377_000, positionMs = 77_000).copy(
				transport = TransportState.STOPPED,
				previousPositionMs = 77_000,
				durationMs = 77_000,
			),
		).state
		val organicPosition = reducer.reduce(
			stopped,
			PlaybackInput.PositionSeen(300_500, establishFirst = false),
		).state
		val returned = reducer.reduce(
			organicPosition,
			metadata(identity(durationMs = 952_000), elapsedRealtimeMs = 378_000),
		).state
		val resumed = reducer.reduce(
			returned,
			playing(379_000, positionMs = 301_000).copy(previousPositionMs = 300_500),
		)

		assertTrue(returned.durationReplacementReturnPending)
		assertFalse(resumed.before.any { it is PlaybackEffect.Finalize })
		assertEquals(1, resumed.state.instanceToken)
		assertEquals(300_000, resumed.state.playedMsAt(379_000))
		assertEquals(320_000, resumed.state.playedMsAt(399_000))
	}

	@Test
	fun `a stopped organic metadata return waits through a stale position callback`() {
		val (reducer, initial) = start(nativeApp, identity(durationMs = 952_000))
		val running = reducer.reduce(initial, playing(0, positionMs = 300_000)).state
		val positioned = reducer.reduce(running, PlaybackInput.PositionSeen(300_000)).state
		val short = reducer.reduce(
			positioned,
			metadata(identity(durationMs = 77_000), elapsedRealtimeMs = 300_000),
		).state
		val stopped = reducer.reduce(
			short,
			playing(377_000, positionMs = 77_000).copy(
				transport = TransportState.STOPPED,
				previousPositionMs = 77_000,
				durationMs = 77_000,
			),
		).state
		val organicPosition = reducer.reduce(
			stopped,
			PlaybackInput.PositionSeen(300_500, establishFirst = false),
		).state
		val returned = reducer.reduce(
			organicPosition,
			metadata(identity(durationMs = 952_000), elapsedRealtimeMs = 378_000),
		).state
		val stalePosition = reducer.reduce(
			returned,
			playing(379_000, positionMs = 0).copy(
				previousPositionMs = 300_500,
				nowMillis = 1_700_000_379_000,
				nextInstanceToken = 2,
			),
		)
		val resumed = reducer.reduce(
			stalePosition.state,
			playing(380_000, positionMs = 301_000).copy(previousPositionMs = 0),
		)

		assertFalse(stalePosition.before.any { it is PlaybackEffect.Finalize })
		assertTrue(stalePosition.state.durationReplacementReturnPending)
		assertTrue(stalePosition.state.durationReplacementReturnGraceScheduled)
		assertEquals(null, stalePosition.state.playingSinceElapsedMs)
		assertEquals(1, stalePosition.state.instanceToken)
		assertEquals(300_000, stalePosition.state.playedMsAt(500_000))
		assertFalse(resumed.before.any { it is PlaybackEffect.Finalize })
		assertFalse(resumed.state.durationReplacementReturnPending)
		assertFalse(resumed.state.durationReplacementReturnGraceScheduled)
		assertEquals(1, resumed.state.instanceToken)
		assertEquals(300_000, resumed.state.playedMsAt(380_000))
		assertEquals(320_000, resumed.state.playedMsAt(400_000))
	}

	@Test
	fun `a browser is not held to the native duration-replacement rule`() {
		// The same bundles, a different source: the capability is what decides,
		// and a browser refines durations upward rather than churning them.
		val (reducer, initial) = start(browser, identity(durationMs = 415_000))
		val transition = reducer.reduce(initial, metadata(identity(durationMs = 11_000)))

		assertEquals(null, transition.state.longestDurationMs)
	}

	// ---- 3. destruction, recreation and continuation ---------------------------

	@Test
	fun `a destroyed session opens a continuation rather than ending the track`() {
		val (reducer, initial) = start()
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state
		val destroyed = reducer.reduce(
			playing,
			PlaybackInput.SessionDestroyed(
				elapsedRealtimeMs = 100_000,
				continuationOpen = false,
			),
		)

		assertTrue(destroyed.effects.any { it is PlaybackEffect.OpenContinuation })
		assertFalse(destroyed.effects.any { it is PlaybackEffect.Finalize })
		// The clock was banked on the way out, so nothing is lost and nothing
		// keeps running for a transport that no longer exists.
		assertEquals(100_000, destroyed.state.playedMs)
		assertEquals(100_000, destroyed.state.playedMsAt(elapsedRealtimeMs = 500_000))
	}

	@Test
	fun `a transport that already parked its progress does not park it twice`() {
		val (reducer, initial) = start()
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state
		val again = reducer.reduce(
			playing,
			PlaybackInput.SessionDestroyed(elapsedRealtimeMs = 100_000, continuationOpen = true),
		)

		assertEquals(emptyList<PlaybackEffect>(), again.effects)
		assertEquals(emptyList<PlaybackEffect>(), again.before)
	}

	@Test
	fun `whether a continuation is open is asked of the caller, not remembered`() {
		// This is a regression against a bug that a first extraction introduced and
		// the tests would not have caught for a long time. Keeping `continuationOpen`
		// in the reducer's own state means it has to be cleared on every path that
		// ends a wait — a claim by the replacement, a cancel, a displacement, or the
		// deadline — and three of those four happen in the caller, on a timer the
		// reducer cannot see. One missed clear and the transport can never park
		// progress again, silently, for the rest of its life.
		//
		// So the caller is asked each time. The same state, asked twice with
		// different answers, must behave differently.
		val (reducer, initial) = start()
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state

		val opens = reducer.reduce(
			playing,
			PlaybackInput.SessionDestroyed(elapsedRealtimeMs = 100_000, continuationOpen = false),
		)
		val declines = reducer.reduce(
			playing,
			PlaybackInput.SessionDestroyed(elapsedRealtimeMs = 100_000, continuationOpen = true),
		)

		assertTrue(opens.effects.any { it is PlaybackEffect.OpenContinuation })
		assertEquals(emptyList<PlaybackEffect>(), declines.effects)
	}

	@Test
	fun `a native session with no exact id refuses to carry and finalizes instead`() {
		val (reducer, initial) = start(nativeApp)
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state
		val destroyed = reducer.reduce(
			playing,
			PlaybackInput.SessionDestroyed(
				elapsedRealtimeMs = 100_000,
				continuationOpen = false,
			),
		)

		assertTrue(destroyed.effects.any { it is PlaybackEffect.Finalize })
		assertFalse(destroyed.effects.any { it is PlaybackEffect.OpenContinuation })
	}

	@Test
	fun `a native session that can name its item does carry`() {
		val (reducer, initial) = start(nativeApp, identity(sourceItemId = "dQw4w9WgXcQ"))
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state
		val destroyed = reducer.reduce(
			playing,
			PlaybackInput.SessionDestroyed(
				elapsedRealtimeMs = 100_000,
				continuationOpen = false,
			),
		)

		assertTrue(destroyed.effects.any { it is PlaybackEffect.OpenContinuation })
	}

	@Test
	fun `carried progress is added to what the replacement already measured`() {
		// Measured 2026-08-09: assigning over the running total threw away the
		// seconds between the replacement starting and the claim landing.
		val (reducer, initial) = start()
		val replacement = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state
		val carried = reducer.reduce(
			replacement,
			PlaybackInput.ProgressCarried(
				playedMs = 100_000,
				startedAtEpochSec = 1_699_999_000,
				fastestSpeedSeen = 1.25,
				loopDetected = false,
				instanceToken = 41,
				lastPositionMs = 100_000,
				currentPositionMs = 105_000,
				durationMs = 213_000,
				nowMillis = 1_700_000_010_000,
			),
		).state

		assertEquals(100_000, carried.playedMs)
		// And the clock is still running, so the seconds since the replacement
		// started are still being counted.
		assertEquals(110_000, carried.playedMsAt(elapsedRealtimeMs = 10_000))
		// The frozen start and the instance both survive, which is what keeps the
		// dedup key and the playback sequence stable across the rebuild.
		assertEquals(1_699_999_000L, carried.startedAtEpochSec)
		assertEquals(41L, carried.instanceToken)
		assertEquals(1.25, carried.fastestSpeedSeen, 0.0)
	}

	// ---- 4. finalization decisions ---------------------------------------------

	@Test
	fun `a track finalizes once however many callbacks announce the end`() {
		val (reducer, initial) = start()
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state
		val first = reducer.reduce(
			playing,
			PlaybackInput.FinalizeRequested("stopped", elapsedRealtimeMs = 200_000),
		)
		val second = reducer.reduce(
			first.state,
			PlaybackInput.FinalizeRequested("session ended", elapsedRealtimeMs = 200_000),
		)

		assertTrue(first.effects.any { it is PlaybackEffect.FreezeAndReport })
		assertEquals(emptyList<PlaybackEffect>(), second.effects)
		assertEquals(200_000, first.state.playedMs)
	}

	@Test
	fun `a transport that never published anything cannot finalize`() {
		val (reducer, _) = start()
		val silent = ListenState(
			trackIdentity = TrackIdentity(null, null, null, null),
			instanceToken = 1,
			instanceEstablishedAtMillis = 0,
			startedAtEpochSec = 1_700_000_000,
			everPublishedMetadata = false,
		)
		val transition = reducer.reduce(
			silent,
			PlaybackInput.FinalizeRequested("probe ended", elapsedRealtimeMs = 0),
		)

		assertEquals(emptyList<PlaybackEffect>(), transition.effects)
	}

	@Test
	fun `STOPPED ends the track and PAUSED does not`() {
		// A paused track is often resumed, and finalizing it would scrobble a
		// half-listen and then dedup-block the real one.
		val (reducer, initial) = start()
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state

		fun transitionTo(transport: TransportState) = reducer.reduce(
			playing,
			PlaybackInput.TransportChanged(
				transport = transport,
				speed = 1.0,
				previousPositionMs = 200_000,
				newPositionMs = 200_000,
				rawPositionMs = 200_000,
				durationMs = 213_000,
				transportHasExactId = false,
				elapsedRealtimeMs = 200_000,
			),
		)

		assertTrue(transitionTo(TransportState.STOPPED).effects.any { it is PlaybackEffect.Finalize })
		assertFalse(transitionTo(TransportState.PAUSED).effects.any { it is PlaybackEffect.Finalize })
	}

	@Test
	fun `a native STOPPED with no exact id waits for a replacement instead`() {
		val (reducer, initial) = start(nativeApp)
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state
		val stopped = reducer.reduce(
			playing,
			PlaybackInput.TransportChanged(
				transport = TransportState.STOPPED,
				speed = 1.0,
				previousPositionMs = 200_000,
				newPositionMs = 200_000,
				rawPositionMs = 200_000,
				durationMs = 213_000,
				transportHasExactId = false,
				elapsedRealtimeMs = 200_000,
			),
		)

		assertTrue(PlaybackEffect.ScheduleStoppedFinalizationGrace in stopped.effects)
		assertFalse(stopped.effects.any { it is PlaybackEffect.Finalize })
	}

	@Test
	fun `user Stop discards an in-flight track and system teardown scores it`() {
		// A Stop button that writes to an immutable chain on its way out is a bad
		// Stop button.
		val (reducer, initial) = start()
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state

		val userStop = reducer.reduce(
			playing,
			PlaybackInput.Disposed(
				finalize = false,
				allowContinuation = false,
				elapsedRealtimeMs = 200_000,
				continuationOpen = false,
			),
		)
		val systemTeardown = reducer.reduce(
			playing,
			PlaybackInput.Disposed(
				finalize = true,
				allowContinuation = false,
				elapsedRealtimeMs = 200_000,
				continuationOpen = false,
			),
		)

		assertFalse(userStop.before.any { it is PlaybackEffect.Finalize })
		assertTrue(systemTeardown.before.any { it is PlaybackEffect.Finalize })
	}

	// ---- 5. picture-in-picture measurement effects -----------------------------

	@Test
	fun `picture-in-picture credit is refused while the session still reports progress`() {
		// The safety property: a regular video in PiP keeps publishing position
		// and is measured normally. Crediting it as well would double-count.
		val (reducer, initial) = start(nativeApp)
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state
		val ticked = reducer.reduce(
			playing,
			PlaybackInput.PictureInPictureObserved(
				nowMillis = 1_700_000_010_000,
				playing = true,
				durationMs = 60_000,
			),
		).state

		assertEquals(0L, ticked.pipInferredMs)
	}

	@Test
	fun `picture-in-picture credit needs two consecutive observations that both say playing`() {
		// A gap in polling must not be back-filled, so the first observation can
		// only ever establish a baseline.
		val (reducer, initial) = start(nativeApp)
		val idle = reducer.reduce(
			initial,
			PlaybackInput.TransportChanged(
				transport = TransportState.OTHER,
				speed = 1.0,
				previousPositionMs = null,
				newPositionMs = null,
				rawPositionMs = null,
				durationMs = 60_000,
				transportHasExactId = false,
				elapsedRealtimeMs = 0,
			),
		).state

		val first = reducer.reduce(
			idle,
			PlaybackInput.PictureInPictureObserved(1_700_000_000_000, true, 60_000),
		).state
		assertEquals(0L, first.pipInferredMs)

		val second = reducer.reduce(
			first,
			PlaybackInput.PictureInPictureObserved(1_700_000_005_000, true, 60_000),
		).state
		assertTrue("a second playing observation credits the gap", second.pipInferredMs > 0)
		assertTrue("and never more than the gap", second.pipInferredMs <= 5_000)
	}

	@Test
	fun `a browser never accrues picture-in-picture credit`() {
		val (reducer, initial) = start(browser)
		val ticked = reducer.reduce(
			initial,
			PlaybackInput.PictureInPictureObserved(1_700_000_000_000, true, 60_000),
		).state

		assertEquals(0L, ticked.pipInferredMs)
	}

	// ---- the abandoned listen ---------------------------------------------------

	@Test
	fun `a listen that runs past its own length with nothing published is ended`() {
		// Measured 2026-08-12: a browser video reached its end, the transport
		// stayed PLAYING with an unchanged update time, and the clock accrued for
		// a further 1h47m because a browser listen only ends on a track change, a
		// navigation or a closed tab.
		val (reducer, initial) = start()
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 1_000)).state

		val transition = reducer.reduce(
			playing,
			PlaybackInput.IdleDeadlineReached(
				durationMs = 213_000,
				elapsedRealtimeMs = 1_000 + 243_000,
			),
		)

		assertTrue(transition.effects.any { it is PlaybackEffect.Finalize })
	}

	@Test
	fun `a deadline that fires while the item is still playing changes nothing`() {
		// A rate change or a seek can move the real end past a deadline that was
		// armed earlier. Ending the listen there would cut a viewing short.
		val (reducer, initial) = start()
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 1_000)).state

		val transition = reducer.reduce(
			playing,
			PlaybackInput.IdleDeadlineReached(
				durationMs = 213_000,
				elapsedRealtimeMs = 1_000 + 100_000,
			),
		)

		assertEquals(emptyList<PlaybackEffect>(), transition.effects)
	}

	@Test
	fun `a paused listen is never ended by the idle deadline`() {
		// Pausing is not abandoning. A paused track is often resumed, and the
		// existing rule that PAUSED never finalizes has to keep holding.
		val (reducer, initial) = start()
		val paused = reducer.reduce(
			initial,
			PlaybackInput.TransportChanged(
				transport = TransportState.PAUSED,
				speed = 1.0,
				previousPositionMs = null,
				newPositionMs = 100_000,
				rawPositionMs = 100_000,
				durationMs = 213_000,
				transportHasExactId = false,
				elapsedRealtimeMs = 1_000,
			),
		).state

		val transition = reducer.reduce(
			paused,
			PlaybackInput.IdleDeadlineReached(213_000, 1_000_000),
		)

		assertEquals(emptyList<PlaybackEffect>(), transition.effects)
	}

	@Test
	fun `the deadline is the wall clock the item still needs, plus a grace`() {
		val (reducer, initial) = start()
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 1_000)).state

		// Nothing played yet: the whole 213 s, plus the grace.
		assertEquals(
			213_000L + PlaybackReducer.IDLE_FINALIZE_GRACE_MS,
			playing.idleFinalizeDelayMs(elapsedRealtimeMs = 1_000, durationMs = 213_000),
		)
		// Sixty seconds in, sixty seconds less.
		assertEquals(
			153_000L + PlaybackReducer.IDLE_FINALIZE_GRACE_MS,
			playing.idleFinalizeDelayMs(elapsedRealtimeMs = 61_000, durationMs = 213_000),
		)
	}

	@Test
	fun `a faster rate brings the deadline forward, because content runs out sooner`() {
		val (reducer, initial) = start()
		val doubled = reducer.reduce(initial, playing(elapsedRealtimeMs = 1_000, speed = 2.0)).state

		assertEquals(
			106_500L + PlaybackReducer.IDLE_FINALIZE_GRACE_MS,
			doubled.idleFinalizeDelayMs(elapsedRealtimeMs = 1_000, durationMs = 213_000),
		)
	}

	@Test
	fun `a source that publishes no length at all is still bounded`() {
		// The hole the first version of this fix had, found on-device: Brave listed
		// `DURATION` among the *unset* metadata keys for an entire listen, so a
		// deadline computed from the item's length was never armed at all — and the
		// source that most needed bounding was the one that never got it.
		val (reducer, initial) = start()
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 1_000)).state

		assertEquals(
			PlaybackReducer.IDLE_FINALIZE_MAX_SILENCE_MS,
			playing.idleFinalizeDelayMs(elapsedRealtimeMs = 1_000, durationMs = null),
		)
		assertEquals(
			PlaybackReducer.IDLE_FINALIZE_MAX_SILENCE_MS,
			playing.idleFinalizeDelayMs(elapsedRealtimeMs = 1_000, durationMs = 0),
		)
	}

	@Test
	fun `a silent transport with no known length is ended when the ceiling expires`() {
		val (reducer, initial) = start()
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 1_000)).state

		val transition = reducer.reduce(
			playing,
			PlaybackInput.IdleDeadlineReached(
				durationMs = null,
				elapsedRealtimeMs = 1_000 + PlaybackReducer.IDLE_FINALIZE_MAX_SILENCE_MS,
			),
		)

		assertTrue(transition.effects.any { it is PlaybackEffect.Finalize })
	}

	@Test
	fun `a paused transport with no known length is still never ended`() {
		// The ceiling must not become a way to finalize a paused track, which the
		// engine would then dedup-block when the listen actually resumes.
		val (reducer, initial) = start()
		val paused = reducer.reduce(
			initial,
			PlaybackInput.TransportChanged(
				transport = TransportState.PAUSED,
				speed = 1.0,
				previousPositionMs = null,
				newPositionMs = 1_000,
				rawPositionMs = 1_000,
				durationMs = null,
				transportHasExactId = false,
				elapsedRealtimeMs = 1_000,
			),
		).state

		assertEquals(null, paused.idleFinalizeDelayMs(elapsedRealtimeMs = 1_000, durationMs = null))
		assertEquals(
			emptyList<PlaybackEffect>(),
			reducer.reduce(paused, PlaybackInput.IdleDeadlineReached(null, 1_000_000)).effects,
		)
	}

	@Test
	fun `a finished listen arms no deadline`() {
		val (reducer, initial) = start()
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 1_000)).state
		val finalized = reducer.reduce(
			playing,
			PlaybackInput.FinalizeRequested("stopped", elapsedRealtimeMs = 200_000),
		).state

		assertEquals(
			null,
			finalized.idleFinalizeDelayMs(elapsedRealtimeMs = 200_000, durationMs = 213_000),
		)
	}

	// ---- instance identity ------------------------------------------------------

	@Test
	fun `two tracks that change inside one second get different instances`() {
		// The Phase 2 collision, at the point where the two instances are minted.
		// `startedAtEpochSec` is stamped from the same millisecond reading the
		// finalize just used, so consecutive tracks sharing a start second is
		// ordinary rather than exotic.
		val (reducer, initial) = start()
		val playing = reducer.reduce(initial, playing(elapsedRealtimeMs = 0)).state
		val second = reducer.reduce(
			playing,
			metadata(
				identity(title = "Doomed"),
				nowMillis = 1_700_000_000_400,
				elapsedRealtimeMs = 400,
				nextInstanceToken = 2,
			),
		).state
		val third = reducer.reduce(
			second,
			metadata(
				identity(title = "Avalanche"),
				nowMillis = 1_700_000_000_900,
				elapsedRealtimeMs = 900,
				nextInstanceToken = 3,
			),
		).state

		assertEquals(second.startedAtEpochSec, third.startedAtEpochSec)
		assertNotEquals(second.instanceToken, third.instanceToken)
	}
}
