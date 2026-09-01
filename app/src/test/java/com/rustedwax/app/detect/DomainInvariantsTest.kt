package com.rustedwax.app.detect

import com.rustedwax.core.PlaybackMeasurement
import com.rustedwax.core.SourceDescriptor
import com.rustedwax.core.SourceSessionId
import com.rustedwax.core.TrackInstanceId
import com.rustedwax.core.TrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * What the seven Phase 2 types refuse to be.
 *
 * An invariant only earns its place if it rules out a state that would cause a
 * *decision* to go wrong, so each one below names the decision. The constraint
 * on all of them is the same: they must not narrow what a valid
 * `SessionSnapshot` may say. `FinalizedTrackAdapterTest` and
 * `DomainParityReplayTest` are the other half of that — the second decomposes
 * every one of the 1,414 imported field finalizations through these
 * constructors, so an invariant that a real listen violates fails there rather
 * than in the field.
 */
class DomainInvariantsTest {

	private fun measurement() = PlaybackMeasurement(
		playedMs = 195_000,
		durationMs = 213_000,
		positionMs = 200_000,
		inferredPlayedMs = 12_000,
		transportState = "STATE_PLAYING",
		startedAtEpochSec = 1_700_000_000,
		percentPlayed = 0.91,
	)

	private fun rejects(message: String, build: () -> Any) {
		val thrown = assertThrows(IllegalArgumentException::class.java) { build() }
		assertEquals(message, thrown.message)
	}

	// ── SourceSessionId ────────────────────────────────────────────────────

	@Test
	fun `a source session must name its package`() {
		// It is compared against the epoch the engine re-checks before dedup and
		// signing. A nameless one matches nothing, so every listen it describes is
		// discarded as stale — silently, because staleness is a normal outcome.
		rejects("a source session must name its package") { SourceSessionId("", 3) }
		rejects("a source session must name its package") { SourceSessionId("  ", null) }
	}

	@Test
	fun `a source epoch is a generation counter`() {
		rejects("a source epoch is a generation counter and starts at 1: 0") {
			SourceSessionId("com.brave.browser", 0)
		}
		// Null is a browser session, which carries no epoch. That is a real
		// distinction rather than missing data and stays permitted.
		assertEquals(null, SourceSessionId("com.brave.browser", null).sourceEpoch)
	}

	// ── TrackInstanceId ────────────────────────────────────────────────────

	@Test
	fun `a track instance token is never zero`() {
		rejects("a track instance token is never zero") {
			TrackInstanceId("com.brave.browser", 0)
		}
	}

	@Test
	fun `the degraded identity cannot collide with an allocated token`() {
		// The counter allocates upward from 1; the fallback negates a start second.
		// Epoch second zero is reachable from a frozen test clock, so the fallback
		// offsets by one rather than landing on the unset value.
		val atEpoch = TrackInstanceId.fromStartSecond("com.brave.browser", 0)
		assertEquals(-1L, atEpoch.instanceToken)
		assertEquals(
			-1_700_000_001L,
			TrackInstanceId.fromStartSecond("com.brave.browser", 1_700_000_000).instanceToken,
		)
	}

	// ── SourceDescriptor ───────────────────────────────────────────────────

	@Test
	fun `a native app cannot have browser evidence`() {
		// The reducer reads this instead of asking `isNative`. If the two could
		// disagree, address-bar and tab evidence would be in reach of a native
		// listen — the misattribution the capability split exists to stop.
		rejects("com.google.android.youtube is a native app and cannot have browser evidence") {
			SourceDescriptor(
				packageName = "com.google.android.youtube",
				appLabel = "YouTube",
				originName = "YouTube",
				packageProvesSource = true,
				isWatched = true,
				hasBrowserEvidence = true,
				trustsMetadataArtist = false,
			)
		}
	}

	// ── TrackMetadata ──────────────────────────────────────────────────────

	@Test
	fun `a blank name must be null rather than present and empty`() {
		// `isNamed` reads blankness; every other caller reads nullity. A blank
		// title is a track that is named and unnamed at the same time depending on
		// who asks, which is how a listen reaches the payload with no name on it.
		rejects("a blank title must be null") { TrackMetadata(title = "   ", artist = null, album = null) }
		rejects("a blank artist must be null") { TrackMetadata(title = "Drown", artist = "", album = null) }
		rejects("a blank album must be null") { TrackMetadata(title = "Drown", artist = null, album = " ") }
		rejects("a blank genre must be null") {
			TrackMetadata(title = "Drown", artist = null, album = null, genre = "")
		}
	}

	@Test
	fun `an unnamed track is still a legal one`() {
		// Ad fragments, teardown bundles and the browser's tab-title state all
		// produce these, and they have to reach the log and the Not-logged list.
		assertEquals(false, TrackMetadata(null, null, null).isNamed)
	}

	// ── PlaybackMeasurement ────────────────────────────────────────────────

	@Test
	fun `inferred content can never exceed the content played`() {
		// The two are kept apart so a caller can ask how much was genuinely read
		// off a progress surface. If inferred could exceed the total, that answer
		// would be a clamp rather than a measurement — and a scrobble built on
		// nothing but wall clock would be indistinguishable from a measured one.
		rejects("inferred 20000ms exceeds the 12000ms played") {
			measurement().copy(playedMs = 12_000, inferredPlayedMs = 20_000)
		}
		assertEquals(183_000, measurement().measuredPlayedMs)
	}

	@Test
	fun `negative measurements are refused`() {
		rejects("played content cannot be negative: -1") { measurement().copy(playedMs = -1) }
		rejects("a negative length: -1") { measurement().copy(durationMs = -1) }
		rejects("a negative fraction played: -0.5") { measurement().copy(percentPlayed = -0.5) }
		rejects("a listen must name its transport state") { measurement().copy(transportState = "") }
	}

	@Test
	fun `a zero length and an unknown one both stay legal`() {
		// "played 260s of 0s" is an honest report of a source that never published
		// a length, and the finalize line is the only visible symptom of it. Null
		// is the same statement from the MediaSession path.
		assertEquals(0L, measurement().copy(durationMs = 0, percentPlayed = null).durationMs)
		assertEquals(null, measurement().copy(durationMs = null, percentPlayed = null).durationMs)
	}

	// ── IdentityEvidence ───────────────────────────────────────────────────

	@Test
	fun `a frozen verdict cannot name a video the same listen disproved`() {
		// The fail-closed property, checked in one place rather than left to the
		// several routes that each preserve it. The rejection travelling while the
		// verdict does not is exactly how a listen goes on-chain under a video id
		// its own page contradicted.
		rejects("identity confirms dQw4w9WgXcQ, which this listen already disproved") {
			IdentityEvidence(
				identity = YouTubeProbe.Identity.Confirmed(
					videoId = "dQw4w9WgXcQ",
					url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
					isMusic = false,
					source = "address bar",
				),
				resolverContext = ResolverContext(rejectedVideoIds = setOf("dQw4w9WgXcQ")),
			)
		}
	}

	@Test
	fun `an ad veto must carry the label it was read from`() {
		rejects("an ad veto must carry the label it was read from") {
			IdentityEvidence(
				identity = YouTubeProbe.Identity.Unconfirmed("nothing named it", false),
				explicitAdSignal = "  ",
			)
		}
	}

	// ── FinalizedTrack ─────────────────────────────────────────────────────

	@Test
	fun `the three package-bearing types must agree`() {
		// Three views of one listen. If they could disagree, the dedup key, the
		// staleness check and the evidence stores would each be keyed to a
		// different source.
		val descriptor = SourceDescriptor(
			packageName = "com.brave.browser",
			appLabel = "Brave",
			originName = "Brave",
			packageProvesSource = false,
			isWatched = true,
			hasBrowserEvidence = true,
			trustsMetadataArtist = false,
		)
		rejects("source session com.android.chrome is not com.brave.browser") {
			FinalizedTrack(
				sourceSession = SourceSessionId("com.android.chrome", null),
				trackInstance = TrackInstanceId("com.brave.browser", 4),
				source = descriptor,
				metadata = TrackMetadata("Drown", "Bring Me The Horizon", null),
				measurement = measurement(),
				evidence = IdentityEvidence(
					identity = YouTubeProbe.Identity.Unconfirmed("nothing named it", false),
				),
			)
		}
		rejects("track instance com.android.chrome is not com.brave.browser") {
			FinalizedTrack(
				sourceSession = SourceSessionId("com.brave.browser", null),
				trackInstance = TrackInstanceId("com.android.chrome", 4),
				source = descriptor,
				metadata = TrackMetadata("Drown", "Bring Me The Horizon", null),
				measurement = measurement(),
				evidence = IdentityEvidence(
					identity = YouTubeProbe.Identity.Unconfirmed("nothing named it", false),
				),
			)
		}
	}
}
