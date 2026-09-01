package com.rustedwax.app.replay

import com.rustedwax.core.*
import com.rustedwax.android.sources.SourceAdapter
import com.rustedwax.app.detect.FinalizedTrack
import com.rustedwax.app.detect.IdentityEvidence
import com.rustedwax.app.scrobble.FinalizationOutcome
import com.rustedwax.app.scrobble.FinalizationRuntime
import com.rustedwax.app.scrobble.FinalizationTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Phase 7 fake non-YouTube adapter-to-dispatch expansion gate. */
class ForeignSourceFinalizationReplayTest : ReplayScenarioTest() {

	private data class FakeIdentity(
		override val sourceItemId: String = "fake:track:42",
		override val canonicalLink: String = "https://audio.example/tracks/42",
	) : ItemIdentity {
		override val source: String = "fake adapter metadata"
		override val isSourceProven: Boolean = true
	}

	private class Fields(private val values: Map<String, Any>) : MetadataFields {
		override fun getString(key: String): String? = values[key] as? String
		override fun getLong(key: String): Long = values[key] as? Long ?: 0
		override fun bitmapDimensions(key: String): Pair<Int, Int>? = null
		override fun keySet(): Set<String> = values.keys
	}

	private class FakeAudioAdapter : SourceAdapter {
		override val descriptor = SourceDescriptor(
			packageName = "com.example.audio",
			appLabel = "Fake Audio",
			originName = "fake-audio",
			packageProvesSource = false,
			isWatched = true,
			hasBrowserEvidence = false,
			trustsMetadataArtist = true,
		)
		override val playbackCapabilities = PlaybackSourceCapabilities(
			republishesShorterDurations = false,
			requiresExactIdToCarryProgress = false,
			usesStoppedReplacementGrace = false,
			supportsPictureInPictureInference = false,
		)

		override fun trackIdentity(fields: MetadataFields?): TrackIdentity = TrackIdentity(
			title = fields?.getString("title"),
			artist = fields?.getString("artist"),
			album = fields?.getString("album"),
			durationMs = fields?.getLong("duration")?.takeIf { it > 0 },
			sourceItemId = fields?.getString("item"),
		)

		override fun itemIdentity(fields: MetadataFields?): ItemIdentity = FakeIdentity(
			sourceItemId = checkNotNull(fields?.getString("item")),
		)
	}

	@Test
	fun `fake adapter reduces playback and reaches recorded dispatch without a video id`() {
		val adapter = FakeAudioAdapter()
		val fields = Fields(
			mapOf(
				"title" to "A Foreign Track",
				"artist" to "An Adapter",
				"duration" to 100_000L,
				"item" to "fake:track:42",
			),
		)
		val presentation = adapter.trackIdentity(fields)
		val reducer = PlaybackReducer(adapter.playbackCapabilities)
		var state = ListenState(
			trackIdentity = presentation,
			instanceToken = 7,
			instanceEstablishedAtMillis = 1_000,
			startedAtEpochSec = 1_800_000_000,
			everPublishedMetadata = true,
		)
		state = reducer.reduce(
			state,
			PlaybackInput.TransportChanged(
				transport = TransportState.PLAYING,
				speed = 1.0,
				previousPositionMs = null,
				newPositionMs = 0,
				rawPositionMs = 0,
				durationMs = 100_000,
				transportHasExactId = false,
				elapsedRealtimeMs = 0,
			),
		).state
		val frozen = reducer.reduce(
			state,
			PlaybackInput.FinalizeRequested("fake source ended", elapsedRealtimeMs = 70_000),
		)
		assertTrue(frozen.effects.single() is PlaybackEffect.FreezeAndReport)
		val identity = adapter.itemIdentity(fields)
		val track = FinalizedTrack(
			sourceSession = SourceSessionId(adapter.descriptor.packageName, null),
			trackInstance = TrackInstanceId(
				adapter.descriptor.packageName,
				frozen.state.instanceToken,
			),
			source = adapter.descriptor,
			metadata = TrackMetadata(
				title = presentation.title,
				artist = presentation.artist,
				album = presentation.album,
				genre = null,
				rawLines = emptyList(),
			),
			measurement = PlaybackMeasurement(
				playedMs = frozen.state.playedMs,
				durationMs = presentation.durationMs,
				positionMs = 70_000,
				firstObservedPositionMs = 0,
				transportState = "PLAYING",
				startedAtEpochSec = frozen.state.startedAtEpochSec,
				percentPlayed = frozen.state.playedMs.toDouble() / 100_000,
			),
			evidence = IdentityEvidence(identity),
		)

		val env = ReplayEnvironment()
		FinalizationRuntime.installPortsForReplay(
			env.ports(),
			CoroutineScope(Dispatchers.Unconfined),
		)
		val outcomes = mutableListOf<FinalizationOutcome>()
		FinalizationRuntime.finalizeTrack.execute(
			track,
			FinalizationTrigger.AUTOMATIC,
			onOutcome = outcomes::add,
		)

		assertTrue(outcomes.single() is FinalizationOutcome.Eligible)
		val recordedDispatch = env.broadcaster.sent
		assertEquals(1, recordedDispatch.size)
		val json = recordedDispatch.single().json
		assertTrue(json.contains("\"platform\":\"fake-audio\""))
		assertTrue(json.contains("https://audio.example/tracks/42"))
	}
}
