package com.rustedwax.app.replay.reference.current

import com.rustedwax.core.*
import com.rustedwax.app.detect.FinalizedPlaybackTombstones
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.UrlEvidence
import com.rustedwax.app.detect.YouTubeProbe
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
 * The field takeover shape through the shipping SessionProbe mirror.
 *
 * This deliberately does not call ExclusivePlaybackArbitration. Controllers
 * enter through MediaSessionManager, proof arrives through UrlEvidence, native
 * progress reaches the controller callback, and snapshots emerge only through
 * the real PlaybackInput.FinalizeRequested reduction and freeze effect.
 */
class ExclusivePlaybackTakeoverCurrentMirrorTest {

	private val bravePackage = "com.brave.browser"
	private val videoId = "dQw4w9WgXcQ"

	private class RecordingTombstones : FinalizedPlaybackTombstones {
		data class Record(
			val packageName: String,
			val semanticKey: String,
			val finalizedAtElapsedMs: Long,
			val transportUpdatedAtElapsedMs: Long,
			val durationMs: Long?,
		)

		val records = mutableListOf<Record>()

		override fun record(
			packageName: String,
			semanticKey: String,
			finalizedAtEpochMs: Long,
			finalizedAtElapsedMs: Long,
			transportUpdatedAtElapsedMs: Long,
			durationMs: Long?,
		) {
			records += Record(
				packageName,
				semanticKey,
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
		): FinalizedPlaybackTombstones.Match? {
			val found = records.lastOrNull {
				it.packageName == packageName && it.semanticKey == semanticKey
			} ?: return null
			return found.takeIf {
				transportUpdatedAtElapsedMs <= it.transportUpdatedAtElapsedMs
			}?.let { FinalizedPlaybackTombstones.Match(it.transportUpdatedAtElapsedMs) }
		}
	}

	private fun metadata(title: String, durationMs: Long = 180_000) = MediaMetadata(
		mapOf(
			MediaMetadata.METADATA_KEY_TITLE to title,
			MediaMetadata.METADATA_KEY_ARTIST to "Channel",
			MediaMetadata.METADATA_KEY_DURATION to durationMs,
		),
	)

	private fun playing(positionMs: Long, stamp: Long) = PlaybackState(
		PlaybackState.STATE_PLAYING,
		position = positionMs,
		lastPositionUpdateTime = stamp,
	)

	@After
	fun tearDown() {
		resetSharedState()
		SystemClock.current = VirtualTime()
	}

	@Test
	fun `native progress finalizes one proven positionless Brave listen at ownership boundary`() {
		resetSharedState()
		val time = VirtualTime()
		SystemClock.current = time
		val manager = MediaSessionManager()
		val braveStamp = 10L
		val brave = MediaController(bravePackage).apply {
			seed(metadata("Brave song"), playing(positionMs = -1, stamp = braveStamp))
		}
		manager.publish(listOf(brave))
		val tombstones = RecordingTombstones()
		val finalized = mutableListOf<SessionSnapshot>()
		val probe = SessionProbe(
			Context(manager),
			acceptsPackage = { true },
			finalizedPlaybackTombstones = tombstones,
		).also { it.onTrackFinalized = { snapshot -> finalized += snapshot } }

		probe.start()
		probe.evidenceCoordinatorForProducers().putUrl(
			com.rustedwax.core.SourceSessionId(bravePackage, null),
			UrlEvidence.Evidence(
				host = "www.youtube.com",
				videoId = videoId,
				raw = "https://www.youtube.com/watch?v=$videoId",
			),
		)
		probe.acceptEvidenceEventForHarness(
			com.rustedwax.app.detect.EvidenceCoordinator.Event.UrlChanged(
				com.rustedwax.core.SourceSessionId(bravePackage, null),
			),
		)
		time.drain()
		time.advance(35_000)

		val native = MediaController(YouTubeProbe.YOUTUBE_PACKAGE).apply {
			seed(
				metadata("Native takeover", durationMs = 10 * 60_000L),
				playing(positionMs = 3_343, stamp = 20L),
			)
		}
		manager.publish(listOf(brave, native))
		time.drain()
		fun braveFinalized() = finalized.filter { it.packageName == bravePackage }

		assertEquals("the ownership boundary did not emit exactly one terminal snapshot", 1, braveFinalized().size)
		assertEquals("positionless playback was inflated beyond measured elapsed time", 35_000L, braveFinalized().single().playedMs)
		assertEquals(videoId, braveFinalized().single().confirmed?.videoId)
		assertEquals("the takeover finalization did not persist one restart tombstone", 1, tombstones.records.size)
		assertEquals(braveStamp, tombstones.records.single().transportUpdatedAtElapsedMs)

		// The unchanged stale transport is still allowed to callback, but it may
		// neither resurrect the Now row nor produce a delayed duplicate.
		brave.publishPlaybackState(playing(positionMs = -1, stamp = braveStamp))
		time.advance(5 * 60_000)
		assertEquals(1, braveFinalized().size)
		assertTrue(
			"the unchanged spent Brave transport re-entered live publication",
			probe.sessions.value.none { it.packageName == bravePackage },
		)

		// Presentation fields may be identical. Only a strictly newer transport
		// sample starts a new instance; a later native progress callback ends that
		// new listen once, proving the tombstone is not a permanent item ban.
		brave.publishPlaybackState(playing(positionMs = -1, stamp = braveStamp + 1))
		assertEquals(
			"a strictly newer Brave sample did not become live",
			1,
			probe.sessions.value.count { it.packageName == bravePackage },
		)
		time.advance(20_000)
		native.publishPlaybackState(playing(positionMs = 20_000, stamp = 21L))
		time.advance(5 * 60_000)

		assertEquals("the fresh listen was not finalized exactly once", 2, braveFinalized().size)
		assertEquals(20_000L, braveFinalized().last().playedMs)
		assertTrue(
			"the fresh listen inherited the spent track instance",
			braveFinalized().first().trackInstanceToken !=
				braveFinalized().last().trackInstanceToken,
		)
		assertEquals(2, tombstones.records.size)
		probe.stop(finalizeTracks = false)
	}

	@Test
	fun `takeover controls preserve readable paused unproven and same-source playback`() {
		data class Case(
			val name: String,
			val browserState: PlaybackState,
			val proveBrowser: Boolean = true,
			val nativeState: PlaybackState = playing(positionMs = 3_343, stamp = 20L),
		)

		val cases = listOf(
			Case("readable browser", playing(positionMs = 12_000, stamp = 10L)),
			Case(
				"paused browser",
				PlaybackState(PlaybackState.STATE_PAUSED, position = -1, lastPositionUpdateTime = 10L),
			),
			Case("unproven browser", playing(positionMs = -1, stamp = 10L), proveBrowser = false),
			Case(
				"paused native",
				playing(positionMs = -1, stamp = 10L),
				nativeState = PlaybackState(
					PlaybackState.STATE_PAUSED,
					position = 3_343,
					lastPositionUpdateTime = 20L,
				),
			),
			Case("positionless native", playing(positionMs = -1, stamp = 10L), nativeState = playing(-1, 20L)),
		)

		cases.forEach { case ->
			resetSharedState()
			val time = VirtualTime()
			SystemClock.current = time
			val manager = MediaSessionManager()
			val browser = MediaController(bravePackage).apply {
				seed(metadata(case.name), case.browserState)
			}
			manager.publish(listOf(browser))
			val finalized = mutableListOf<SessionSnapshot>()
			val probe = SessionProbe(Context(manager), acceptsPackage = { true }).also {
				it.onTrackFinalized = { snapshot -> finalized += snapshot }
			}
			probe.start()
			if (case.proveBrowser) {
				probe.evidenceCoordinatorForProducers().putUrl(
					com.rustedwax.core.SourceSessionId(bravePackage, null),
					UrlEvidence.Evidence(
						host = "youtube.com",
						videoId = videoId,
						raw = "https://youtube.com/watch?v=$videoId",
					),
				)
				probe.acceptEvidenceEventForHarness(
					com.rustedwax.app.detect.EvidenceCoordinator.Event.UrlChanged(
						com.rustedwax.core.SourceSessionId(bravePackage, null),
					),
				)
			}
			time.drain()
			time.advance(5_000)
			val native = MediaController(YouTubeProbe.YOUTUBE_PACKAGE).apply {
				seed(metadata("Native"), case.nativeState)
			}
			manager.publish(listOf(browser, native))
			time.drain()

			assertTrue("${case.name} was wrongly ended by takeover: $finalized", finalized.isEmpty())
			probe.stop(finalizeTracks = false)
		}
	}
}
