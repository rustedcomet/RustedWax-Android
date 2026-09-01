package com.rustedwax.app.replay.reference.current

import com.rustedwax.core.*
import com.rustedwax.app.replay.reference.phase01.Context
import com.rustedwax.app.replay.reference.phase01.MediaController
import com.rustedwax.app.replay.reference.phase01.MediaMetadata
import com.rustedwax.app.replay.reference.phase01.MediaSessionManager
import com.rustedwax.app.replay.reference.phase01.PlaybackState
import com.rustedwax.app.replay.reference.phase01.SystemClock
import com.rustedwax.app.replay.reference.phase01.VirtualTime
import com.rustedwax.app.replay.reference.phase01.resetSharedState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipping `SessionProbe.AndroidSessionBinding` lifecycle after an idle deadline.
 *
 * [SessionProbe] in this package is the generated, provenance-checked JVM mirror
 * of the production body. This test therefore exercises the real watch registry,
 * callback translation, virtual `Handler`, reducer, timer, publication flow and
 * successor-track transition together; it does not call `IdleFinalization`
 * directly or inject `IdleDeadlineReached` by hand.
 */
class FinalizedWatchLifecycleTest {

	private val browser = "com.brave.browser"
	private class Tombstones : com.rustedwax.app.detect.FinalizedPlaybackTombstones {
		private data class Record(
			val packageName: String,
			val semanticKey: String,
			val finalizedAtEpochMs: Long,
			val finalizedAtElapsedMs: Long,
			val transportUpdatedAtElapsedMs: Long,
			val durationMs: Long?,
		)

		private var record: Record? = null
		val hasRecord: Boolean get() = record != null

		override fun record(
			packageName: String,
			semanticKey: String,
			finalizedAtEpochMs: Long,
			finalizedAtElapsedMs: Long,
			transportUpdatedAtElapsedMs: Long,
			durationMs: Long?,
		) {
			record = Record(
				packageName,
				semanticKey,
				finalizedAtEpochMs,
				finalizedAtElapsedMs,
				transportUpdatedAtElapsedMs,
				durationMs,
			)
		}

		override fun findStaleTransport(
			packageName: String,
			semanticKey: String,
			nowEpochMs: Long,
			nowElapsedMs: Long,
			transportUpdatedAtElapsedMs: Long,
			positionMs: Long?,
		): com.rustedwax.app.detect.FinalizedPlaybackTombstones.Match? {
			val found = record ?: return null
			if (found.packageName != packageName || found.semanticKey != semanticKey) return null
			if (nowEpochMs - found.finalizedAtEpochMs !in 0..(6 * 60 * 60 * 1000L)) return null
			if (nowElapsedMs < found.finalizedAtElapsedMs) return null
			val unchangedStamp = transportUpdatedAtElapsedMs <= found.transportUpdatedAtElapsedMs
			val ranPastEnd = found.durationMs?.let { duration ->
				positionMs != null && positionMs >= duration
			} == true
			if (!unchangedStamp && !ranPastEnd) return null
			return com.rustedwax.app.detect.FinalizedPlaybackTombstones.Match(
				if (ranPastEnd) transportUpdatedAtElapsedMs else found.transportUpdatedAtElapsedMs,
			)
		}
	}

	private fun metadata(title: String, durationMs: Long) = MediaMetadata(
		mapOf(
			MediaMetadata.METADATA_KEY_TITLE to title,
			MediaMetadata.METADATA_KEY_ARTIST to "Channel",
			MediaMetadata.METADATA_KEY_DURATION to durationMs,
		),
	)

	@After
	fun tearDown() {
		resetSharedState()
		SystemClock.current = VirtualTime()
	}

	@Test
	fun `idle-finalized watch leaves live UI and cannot restart its clock`() {
		resetSharedState()
		val time = VirtualTime()
		SystemClock.current = time
		val manager = MediaSessionManager()
		val controller = MediaController(browser).apply {
			seed(
				metadata("Finished song", 60_000),
				PlaybackState(PlaybackState.STATE_PLAYING, position = -1),
			)
		}
		manager.publish(listOf(controller))
		val probe = SessionProbe(Context(manager), acceptsPackage = { true })
		val finalized = mutableListOf<com.rustedwax.app.detect.SessionSnapshot>()
		probe.onTrackFinalized = { finalized += it }

		probe.start()
		time.drain()
		assertEquals("the already-playing controller was not published", 1, probe.sessions.value.size)

		// Known duration + production grace. No callback is injected: the initial
		// Watch arm must be the thing that reaches finalization.
		time.advance(60_000 + com.rustedwax.core.PlaybackReducer.IDLE_FINALIZE_GRACE_MS)

		assertEquals("the deadline did not finalize exactly once", 1, finalized.size)
		assertTrue(
			"a finished controller still appears as a live/running Now session: ${probe.sessions.value}",
			probe.sessions.value.isEmpty(),
		)
		assertEquals("the spent Watch was replaced over the same live token", 1, controller.callbackCount)
		manager.publish(listOf(controller))
		assertEquals("re-listing the same token created another Watch", 1, controller.callbackCount)

		// Brave can keep publishing PLAYING for the spent controller. That callback
		// may update diagnostics, but must not restart measurement or resurrect UI.
		controller.publishPlaybackState(
			PlaybackState(PlaybackState.STATE_PLAYING, position = -1),
		)
		time.advance(5 * 60_000)

		assertEquals("the finalized controller broadcast twice", 1, finalized.size)
		assertTrue("the finalized controller re-entered live UI", probe.sessions.value.isEmpty())
		probe.stop(finalizeTracks = true)
	}

	@Test
	fun `empty native Shorts controller never becomes a stale Now row`() {
		resetSharedState()
		val time = VirtualTime()
		SystemClock.current = time
		val manager = MediaSessionManager()
		val controller = MediaController("com.google.android.youtube").apply {
			seed(
				null,
				PlaybackState(PlaybackState.STATE_NONE, position = -1),
			)
		}
		manager.publish(listOf(controller))
		val probe = SessionProbe(Context(manager), acceptsPackage = { true })

		probe.start()
		time.drain()
		assertEquals("the empty controller was not actually watched", 1, controller.callbackCount)
		assertTrue(
			"an identity-less NONE controller appeared in Now: ${probe.sessions.value}",
			probe.sessions.value.isEmpty(),
		)

		controller.publishPlaybackState(
			PlaybackState(PlaybackState.STATE_STOPPED, position = -1),
		)
		assertTrue(
			"an identity-less STOPPED controller appeared in Now: ${probe.sessions.value}",
			probe.sessions.value.isEmpty(),
		)
		probe.stop(finalizeTracks = false)
	}

	@Test
	fun `finalized watch rejects evidence and does not block the live successor`() {
		resetSharedState()
		val time = VirtualTime()
		SystemClock.current = time
		val manager = MediaSessionManager()
		val finished = MediaController(browser).apply {
			seed(
				metadata("Finished song", 60_000),
				PlaybackState(PlaybackState.STATE_PLAYING, position = -1),
			)
		}
		manager.publish(listOf(finished))
		val probe = SessionProbe(Context(manager), acceptsPackage = { true })
		probe.start()
		time.drain()
		time.advance(60_000 + com.rustedwax.core.PlaybackReducer.IDLE_FINALIZE_GRACE_MS)
		assertEquals(0, com.rustedwax.app.detect.MediaSessionAccessibilityEvidence.trackedInstances())

		val successor = MediaController(browser).apply {
			seed(
				metadata("Live successor", 180_000),
				PlaybackState(PlaybackState.STATE_PLAYING, position = 0),
			)
		}
		manager.publish(listOf(finished, successor))
		val scanAt = System.currentTimeMillis()
		probe.acceptEvidenceEventForHarness(
			com.rustedwax.app.detect.EvidenceCoordinator.Event.ScreenScanned(
				sourceSession = com.rustedwax.core.SourceSessionId(browser, null),
				scan = com.rustedwax.app.detect.MediaSessionAccessibilityEvidence.Scan(
				packageName = browser,
				host = "www.youtube.com",
				rootVisible = true,
				videoId = null,
				atMillis = scanAt,
				),
			),
		)
		time.drain()

		assertEquals("the finished Watch remained in the live session count", 1, probe.sessions.value.size)
		assertEquals("Live successor", probe.sessions.value.single().title)
		assertTrue(
			"the finalized predecessor blocked safe elimination for the live successor",
			probe.sessions.value.single().accessibilityCoverage != null,
		)
		probe.stop(finalizeTracks = false)
	}

	@Test
	fun `genuine successor metadata reuses the controller as a fresh instance`() {
		resetSharedState()
		val time = VirtualTime()
		SystemClock.current = time
		val manager = MediaSessionManager()
		val controller = MediaController(browser).apply {
			seed(
				metadata("Finished song", 60_000),
				PlaybackState(PlaybackState.STATE_PLAYING, position = -1),
			)
		}
		manager.publish(listOf(controller))
		val probe = SessionProbe(Context(manager), acceptsPackage = { true })
		val finalized = mutableListOf<com.rustedwax.app.detect.SessionSnapshot>()
		probe.onTrackFinalized = { finalized += it }
		probe.start()
		time.drain()
		time.advance(60_000 + com.rustedwax.core.PlaybackReducer.IDLE_FINALIZE_GRACE_MS)
		assertEquals(1, finalized.size)

		controller.publishMetadata(metadata("Genuine next song", 120_000))

		assertEquals("the successor did not become the one live session", 1, probe.sessions.value.size)
		assertEquals("Genuine next song", probe.sessions.value.single().title)
		assertTrue(
			"the successor inherited the finalized instance token",
			probe.sessions.value.single().trackInstanceToken != finalized.single().trackInstanceToken,
		)
		probe.stop(finalizeTracks = false)
	}

	@Test
	fun `process restart does not recreate a stale ended browser transport`() {
		resetSharedState()
		val time = VirtualTime()
		SystemClock.current = time
		val manager = MediaSessionManager()
		val tombstones = Tombstones()
		val transportStamp = 10L
		val first = MediaController(browser).apply {
			seed(
				metadata("Finished song", 60_000),
				PlaybackState(
					PlaybackState.STATE_PLAYING,
					position = -1,
					lastPositionUpdateTime = transportStamp,
				),
			)
		}
		manager.publish(listOf(first))
		val finalized = mutableListOf<com.rustedwax.app.detect.SessionSnapshot>()
		val firstProbe = SessionProbe(
			Context(manager),
			acceptsPackage = { true },
			finalizedPlaybackTombstones = tombstones,
		).also { it.onTrackFinalized = { snapshot -> finalized += snapshot } }
		firstProbe.start()
		time.drain()
		time.advance(60_000 + com.rustedwax.core.PlaybackReducer.IDLE_FINALIZE_GRACE_MS)
		assertEquals(1, finalized.size)
		assertTrue("idle finalization did not persist its restart tombstone", tombstones.hasRecord)
		firstProbe.stop(finalizeTracks = false)

		// A fresh RustedWax process receives a new controller object/token, but Brave
		// is still advertising the exact transport update that already ran out.
		val staleAfterRestart = MediaController(browser).apply {
			seed(
				metadata("Finished song", 60_000),
				PlaybackState(
					PlaybackState.STATE_PLAYING,
					position = 100_000,
					lastPositionUpdateTime = transportStamp + 100_000,
				),
			)
		}
		manager.publish(listOf(staleAfterRestart))
		val restartedProbe = SessionProbe(
			Context(manager),
			acceptsPackage = { true },
			finalizedPlaybackTombstones = tombstones,
		).also { it.onTrackFinalized = { snapshot -> finalized += snapshot } }
		restartedProbe.start()
		time.drain()

		assertTrue(
			"the stale ended controller re-entered live UI after process restart",
			restartedProbe.sessions.value.isEmpty(),
		)
		time.advance(5 * 60_000)
		assertEquals("the stale ended controller scored again", 1, finalized.size)

		// A real replay of the same item may reuse every presentation field. The
		// strictly newer transport update — not that identity — is what reopens it.
		staleAfterRestart.publishPlaybackState(
			PlaybackState(
				PlaybackState.STATE_PLAYING,
				position = 0,
				lastPositionUpdateTime = transportStamp + 100_001,
			),
		)
		assertEquals("a genuinely newer replay did not become live", 1, restartedProbe.sessions.value.size)
		assertTrue(
			"the genuine replay inherited the finalized track instance",
			restartedProbe.sessions.value.single().trackInstanceToken !=
				finalized.single().trackInstanceToken,
		)
		restartedProbe.stop(finalizeTracks = false)
	}
}
