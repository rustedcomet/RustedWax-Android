package com.rustedwax.app.replay.reference.current

import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.replay.ReplayEnvironment
import com.rustedwax.app.replay.ReplayScenarioTest
import com.rustedwax.app.replay.reference.phase01.Context
import com.rustedwax.app.replay.reference.phase01.MediaController
import com.rustedwax.app.replay.reference.phase01.MediaMetadata
import com.rustedwax.app.replay.reference.phase01.MediaSessionManager
import com.rustedwax.app.replay.reference.phase01.PlaybackState
import com.rustedwax.app.replay.reference.phase01.SystemClock
import com.rustedwax.app.replay.reference.phase01.VirtualTime
import com.rustedwax.app.replay.reference.phase01.resetSharedState
import com.rustedwax.app.scrobble.FinalizationObserver
import com.rustedwax.app.scrobble.FinalizationOutcome
import com.rustedwax.app.scrobble.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionMetadataFreshnessCurrentMirrorTest : ReplayScenarioTest() {

	private val native = YouTubeProbe.YOUTUBE_PACKAGE
	private val firstId = "aaaaaaaaaaa"
	private val secondId = "bbbbbbbbbbb"

	private fun metadata(
		title: String,
		durationMs: Long?,
		mediaId: String? = null,
		artist: String = "Channel",
	) = MediaMetadata(
		buildMap {
			put(MediaMetadata.METADATA_KEY_TITLE, title)
			put(MediaMetadata.METADATA_KEY_ARTIST, artist)
			durationMs?.let { put(MediaMetadata.METADATA_KEY_DURATION, it) }
			mediaId?.let { put(MediaMetadata.METADATA_KEY_MEDIA_ID, it) }
		},
	)

	private fun state(kind: Int, positionMs: Long, stamp: Long) = PlaybackState(
		state = kind,
		position = positionMs,
		lastPositionUpdateTime = stamp,
	)

	private data class Run(
		val time: VirtualTime,
		val manager: MediaSessionManager,
		val controller: MediaController,
		val probe: SessionProbe,
		val finalized: MutableList<SessionSnapshot>,
	)

	private fun run(
		initialMetadata: MediaMetadata? = null,
		packageName: String = native,
	): Run {
		resetSharedState()
		val time = VirtualTime()
		SystemClock.current = time
		val manager = MediaSessionManager()
		val controller = MediaController(packageName).apply {
			seed(initialMetadata, state(PlaybackState.STATE_NONE, 0, 1))
		}
		manager.publish(listOf(controller))
		val finalized = mutableListOf<SessionSnapshot>()
		val probe = SessionProbe(Context(manager), acceptsPackage = { true }).also {
			it.onTrackFinalized = { snapshot -> finalized += snapshot }
		}
		probe.start()
		time.drain()
		return Run(time, manager, controller, probe, finalized)
	}

	@Test
	fun `YouTube Music toggle keeps logical measurement but freezes current item duration`() {
		val run = run(packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE)
		run.controller.publishMetadata(
			metadata(
				"Mr. Vegas - Buss It Open (Official Video)",
				208_979,
				artist = "Mr. Vegas",
			),
		)
		run.controller.publishPlaybackState(state(PlaybackState.STATE_PLAYING, 0, 10))
		run.time.advance(30_000)
		run.controller.publishMetadata(
			metadata("Buss It Open", 196_533, artist = "Mr. Vegas"),
		)
		run.time.advance(30_000)
		// YouTube may clear DURATION in its final metadata bundle. Silence must
		// not erase the current Song presentation and fall back to Video's length.
		run.controller.publishMetadata(
			metadata("Buss It Open", null, artist = "Mr. Vegas"),
		)
		run.controller.destroySession()
		run.time.drain()

		assertEquals("the mode toggle split the listen", 1, run.finalized.size)
		val frozen = run.finalized.single()
		assertEquals(208_979L, frozen.durationMs)
		assertEquals(196_533L, frozen.resolverContext.presentationDurationMs)
		run.probe.stop(finalizeTracks = false)
	}

	@Test
	fun `a finalized Short's retained bundle cannot identify a later id-less playback`() {
		val run = run()
		run.controller.publishMetadata(metadata("Short A", 60_000, firstId))
		run.controller.publishPlaybackState(state(PlaybackState.STATE_PLAYING, 0, 10))
		run.time.advance(40_000)
		run.controller.publishPlaybackState(state(PlaybackState.STATE_STOPPED, 40_000, 11))
		assertEquals("Short A did not finalize at its own STOPPED boundary", 1, run.finalized.size)

		// Android publishes only transport for the later playback. Its live metadata
		// getter still returns Short A; production must not treat presence as freshness.
		run.controller.publishPlaybackState(state(PlaybackState.STATE_PLAYING, 0, 12))
		run.time.advance(40_000)
		run.controller.destroySession()
		run.time.drain()

		assertEquals("the later id-less playback did not get one terminal snapshot", 2, run.finalized.size)
		val later = run.finalized.last()
		assertEquals(40_000L, later.playedMs)
		assertNull("Short A's title crossed the playback-generation boundary", later.title)
		assertNull("Short A's duration crossed the playback-generation boundary", later.durationMs)
		assertNull("Short A's exact id crossed the playback-generation boundary", later.confirmed)
		assertTrue(
			"the later playback reused Short A's instance token",
			run.finalized.first().trackInstanceToken != later.trackInstanceToken,
		)

		val env = ReplayEnvironment()
		env.facts.put(
			VideoFacts(
				videoId = firstId,
				title = "Short A",
				author = "Channel",
				lengthSeconds = 60,
				category = "Music",
				watchPageResolved = true,
				isUnlisted = false,
			),
		)
		val outcomes = mutableListOf<FinalizationOutcome>()
		FinalizationRuntime.resetForReplay()
		FinalizationRuntime.installPortsForReplay(env.ports(), CoroutineScope(Dispatchers.Unconfined))
		FinalizationRuntime.finalizationObserver = FinalizationObserver { _, outcome -> outcomes += outcome }
		run.finalized.forEach(FinalizationRuntime::executeAutomatic)

		assertEquals("each actual finalized playback needs one typed decision", 2, outcomes.size)
		assertEquals(1, outcomes.count { it is FinalizationOutcome.Eligible })
		assertEquals(1, outcomes.count { it is FinalizationOutcome.Refused })
		assertEquals("the stale later playback produced a wrong second broadcast", 1, env.broadcaster.sent.size)
		assertTrue(env.broadcaster.sent.single().json.contains(firstId))
		run.probe.stop(finalizeTracks = false)
	}

	@Test
	fun `metadata then PLAYING in one lifecycle remains authoritative`() {
		val run = run()
		run.controller.publishMetadata(metadata("Current", 60_000, firstId))
		run.controller.publishPlaybackState(state(PlaybackState.STATE_PLAYING, 0, 10))
		run.time.advance(40_000)
		run.controller.publishPlaybackState(state(PlaybackState.STATE_STOPPED, 40_000, 11))

		assertEquals(1, run.finalized.size)
		assertEquals("Current", run.finalized.single().title)
		assertEquals(60_000L, run.finalized.single().durationMs)
		assertEquals(firstId, run.finalized.single().confirmed?.videoId)
		run.probe.stop(finalizeTracks = false)
	}

	@Test
	fun `PLAYING then fresh metadata becomes usable only from that observation onward`() {
		val run = run()
		run.controller.publishPlaybackState(state(PlaybackState.STATE_PLAYING, 0, 10))
		run.time.advance(10_000)
		run.controller.publishMetadata(metadata("Arrived late", 60_000, secondId))
		run.time.advance(40_000)
		run.controller.publishPlaybackState(state(PlaybackState.STATE_STOPPED, 50_000, 11))

		assertEquals("the unidentified prefix became a phantom finalized track", 1, run.finalized.size)
		assertEquals("pre-metadata playback was credited to the later identity", 40_000L, run.finalized.single().playedMs)
		assertEquals("Arrived late", run.finalized.single().title)
		assertEquals(secondId, run.finalized.single().confirmed?.videoId)
		run.probe.stop(finalizeTracks = false)
	}

	@Test
	fun `ordinary position pause and resume callbacks preserve current metadata`() {
		val run = run(metadata("Stable", 120_000, firstId))
		run.controller.publishPlaybackState(state(PlaybackState.STATE_PLAYING, 0, 10))
		run.time.advance(10_000)
		run.controller.publishPlaybackState(state(PlaybackState.STATE_PLAYING, 10_000, 11))
		run.time.advance(10_000)
		run.controller.publishPlaybackState(state(PlaybackState.STATE_PAUSED, 20_000, 12))
		run.time.advance(5_000)
		run.controller.publishPlaybackState(state(PlaybackState.STATE_PLAYING, 20_000, 13))
		run.time.advance(20_000)
		run.controller.publishPlaybackState(state(PlaybackState.STATE_STOPPED, 40_000, 14))

		assertEquals(1, run.finalized.size)
		assertEquals("Stable", run.finalized.single().title)
		assertEquals(firstId, run.finalized.single().confirmed?.videoId)
		assertEquals(40_000L, run.finalized.single().playedMs)
		run.probe.stop(finalizeTracks = false)
	}

	@Test
	fun `an exact-id replacement session retains legitimate continuation authority`() {
		val run = run(metadata("Continuous", 120_000, firstId))
		run.controller.publishPlaybackState(state(PlaybackState.STATE_PLAYING, 0, 10))
		run.time.advance(30_000)
		run.controller.destroySession()
		run.time.drain()
		assertTrue(run.finalized.isEmpty())

		val replacement = MediaController(native).apply {
			seed(metadata("Continuous", 120_000, firstId), state(PlaybackState.STATE_PLAYING, 30_000, 20))
		}
		run.manager.publish(listOf(replacement))
		run.time.drain()
		run.time.advance(20_000)
		replacement.publishPlaybackState(state(PlaybackState.STATE_STOPPED, 50_000, 21))

		assertEquals(1, run.finalized.size)
		assertEquals(firstId, run.finalized.single().confirmed?.videoId)
		assertEquals(50_000L, run.finalized.single().playedMs)
		run.probe.stop(finalizeTracks = false)
	}
}
