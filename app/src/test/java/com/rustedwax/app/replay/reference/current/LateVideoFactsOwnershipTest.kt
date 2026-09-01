package com.rustedwax.app.replay.reference.current

import com.rustedwax.core.*
import com.rustedwax.app.detect.EvidenceCoordinator
import com.rustedwax.app.detect.UrlEvidence
import com.rustedwax.app.replay.reference.phase01.Context
import com.rustedwax.app.replay.reference.phase01.MediaController
import com.rustedwax.app.replay.reference.phase01.MediaMetadata
import com.rustedwax.app.replay.reference.phase01.MediaSessionManager
import com.rustedwax.app.replay.reference.phase01.PlaybackState
import com.rustedwax.app.replay.reference.phase01.SystemClock
import com.rustedwax.app.replay.reference.phase01.UrlWatcherService
import com.rustedwax.app.replay.reference.phase01.VirtualTime
import com.rustedwax.app.replay.reference.phase01.resetSharedState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production-mirror coverage for the asynchronous duration handoff. */
class LateVideoFactsOwnershipTest {

	private val browser = "com.brave.browser"
	private val videoId = "dQw4w9WgXcQ"

	private fun metadata() = MediaMetadata(
		mapOf(
			MediaMetadata.METADATA_KEY_TITLE to "Duration-less song",
			MediaMetadata.METADATA_KEY_ARTIST to "Channel",
		),
	)

	@After
	fun tearDown() {
		resetSharedState()
		SystemClock.current = VirtualTime()
	}

	private data class Running(
		val time: VirtualTime,
		val probe: SessionProbe,
		val completions: MutableList<(Boolean) -> Unit>,
		val facts: MutableMap<String, SessionProbe.KnownVideo>,
		val finalized: MutableList<com.rustedwax.app.detect.SessionSnapshot>,
	)

	private fun start(): Running {
		resetSharedState()
		val time = VirtualTime()
		SystemClock.current = time
		UrlWatcherService.enabled = true
		val evidence = EvidenceCoordinator().also(EvidenceCoordinator::start)
		evidence.putUrl(
			com.rustedwax.core.SourceSessionId(browser, null),
			UrlEvidence.Evidence(
				host = "www.youtube.com",
				videoId = videoId,
				raw = "https://www.youtube.com/watch?v=$videoId",
			),
		)
		val controller = MediaController(browser).apply {
			seed(metadata(), PlaybackState(PlaybackState.STATE_PLAYING, position = -1))
		}
		val manager = MediaSessionManager().also { it.publish(listOf(controller)) }
		val facts = mutableMapOf<String, SessionProbe.KnownVideo>()
		val completions = mutableListOf<(Boolean) -> Unit>()
		val finalized = mutableListOf<com.rustedwax.app.detect.SessionSnapshot>()
		val probe = SessionProbe(
			Context(manager), acceptsPackage = { true }, evidenceCoordinator = evidence,
		).also { candidate ->
			candidate.knownVideoFor = facts::get
			candidate.onVideoConfirmed = { requested, completion ->
				assertEquals(videoId, requested)
				completions += completion
			}
			candidate.onTrackFinalized = { finalized += it }
			candidate.start()
		}
		time.drain()
		assertEquals("the Watch did not launch one owned prefetch", 1, completions.size)
		return Running(time, probe, completions, facts, finalized)
	}

	@Test
	fun `late cached duration shortens the owning watch deadline`() {
		val run = start()
		run.facts[videoId] = SessionProbe.KnownVideo(
			"Duration-less song", "Channel", lengthSeconds = 60,
		)

		// Duplicate engine delivery must be spent once by the Watch.
		run.completions.single()(true)
		run.completions.single()(true)
		run.time.drain()
		run.time.advance(60_000 + com.rustedwax.core.PlaybackReducer.IDLE_FINALIZE_GRACE_MS)

		assertEquals("the late duration did not replace the 15-minute fallback", 1, run.finalized.size)
		assertTrue(run.probe.sessions.value.isEmpty())
		run.probe.stop(finalizeTracks = true)
	}

	@Test
	fun `failed completion leaves fallback intact and disposed completion is inert`() {
		val failed = start()
		failed.completions.single()(false)
		failed.time.drain()
		failed.time.advance(60_000 + com.rustedwax.core.PlaybackReducer.IDLE_FINALIZE_GRACE_MS)
		assertTrue("a failed fetch invented a precise duration", failed.finalized.isEmpty())
		failed.probe.stop(finalizeTracks = false)

		failed.facts[videoId] = SessionProbe.KnownVideo(
			"Duration-less song", "Channel", lengthSeconds = 60,
		)
		failed.completions.single()(true)
		failed.time.drain()
		failed.time.advance(20 * 60_000)
		assertTrue("a disposed Watch consumed a stale completion", failed.finalized.isEmpty())
	}
}
