package com.rustedwax.app.detect

import com.rustedwax.core.*
import com.rustedwax.core.TrackInstanceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier

class FinalizedTrackAdapterTest {

	/**
	 * The snapshot's constructor-declared state.
	 *
	 * Backing fields rather than Kotlin reflection, because `kotlin-reflect` is
	 * not on this module's test classpath — and they are the more precise
	 * question anyway: a data class's backing fields are exactly its constructor
	 * parameters, so the derived `val`s the snapshot computes from them are
	 * correctly excluded from a losslessness check.
	 */
	private fun declaredState(): List<Field> = SessionSnapshot::class.java.declaredFields
		.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
		.onEach { it.isAccessible = true }

	private fun coverage(videoId: String) = MediaSessionAccessibilityEvidence.Coverage(
		instance = MediaSessionAdEvidence.TrackInstance(
			packageName = "com.brave.browser",
			token = 41,
			signature = TrackIdentity(
				title = "Sleepwalking",
				artist = "Bring Me The Horizon",
				album = null,
				durationMs = 213_000,
			),
		),
		atMillis = 1_700_000_000_000,
		urlGeneration = 7,
		videoId = videoId,
	)

	/**
	 * A snapshot with **every** nullable field populated and every flag flipped
	 * off its default.
	 *
	 * Deliberately not a realistic listen. A fixture built from defaults would
	 * round-trip through an adapter that dropped half of them, because `null ==
	 * null` and `false == false`; the only fixture that can catch a dropped
	 * field is one where no field holds its default value.
	 */
	private fun fullyPopulated() = SessionSnapshot(
		packageName = "com.brave.browser",
		appLabel = "Brave",
		isTarget = true,
		title = "Sleepwalking",
		artist = "Bring Me The Horizon",
		album = "Sempiternal",
		durationMs = 213_000,
		positionMs = 190_000,
		firstObservedPositionMs = 1_500,
		playedMs = 195_000,
		unattributedMeasuredMs = 41_000,
		refusedFinalPresentationMs = 29_000,
		loopDetected = true,
		explicitAdSignal = "Sponsored · Ad",
		browserEvidenceEnabled = true,
		accessibilityCoverage = coverage("dQw4w9WgXcQ"),
		playbackState = "STATE_PLAYING",
		foregroundProgressLost = true,
		inferredPlayedMs = 12_000,
		isPlaying = true,
		percentPlayed = 0.915,
		identity = YouTubeProbe.Identity.Confirmed(
			videoId = "dQw4w9WgXcQ",
			url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
			isMusic = true,
			isShort = true,
			source = "address bar (latched)",
			urlGeneration = 7,
			exactIdRoute = "playlist",
		),
		resolverContext = ResolverContext(
			playlistId = "PLtest",
			urlGeneration = 7,
			observedVideoId = "dQw4w9WgXcQ",
			observedUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
			knownTitle = "Rick Astley - Never Gonna Give You Up",
			knownChannel = "RickAstleyVEVO",
			knownDurationSeconds = 213,
			rejectedVideoIds = setOf("aaaaaaaaaaa"),
			preResolvedNativeVideoId = "bbbbbbbbbbb",
			preResolvedNativeRoute = NativePreResolvedRoute.PLAYLIST,
			nativePlaylistName = "2pac Playlist",
			nativePlaylistOwner = "someone",
			nativePlaylistTotal = 180,
		),
		notificationHint = NotificationHints.Hint(
			host = "youtube.com",
			subText = "youtube.com",
			title = "Sleepwalking",
			text = "Bring Me The Horizon",
			atMillis = 1_700_000_000_000,
		),
		metadataLines = listOf("TITLE = Sleepwalking", "DURATION = 213000"),
		trackStartedAtEpochSec = 1_700_000_000,
		trackInstanceToken = 41,
		genre = "Music",
		sourceEpoch = 3,
		sourceProof = SourceProof.NATIVE_FOREGROUND_SHORT,
		ownerHandle = "@bmthofficial",
		automaticWriteAuthorization = AutomaticWriteAuthorization(
			generation = 9,
			enabledAtStart = false,
		),
	)

	@Test
	fun `every declared snapshot field survives the round trip`() {
		val original = fullyPopulated()
		val rebuilt = FinalizedTrack.from(original).toSessionSnapshot()

		val differing = declaredState()
			.filter { it.get(original) != it.get(rebuilt) }
			.map { it.name }

		assertEquals(emptyList<String>(), differing)
		// A field count that silently fell to zero would make the list above pass
		// by being empty for the wrong reason.
		assertTrue(declaredState().size >= 27)
		assertEquals(original, rebuilt)
	}

	@Test
	fun `the fixture leaves no field at its default, so a dropped field would show`() {
		// The guard on the guard. If this fixture ever drifts back toward defaults
		// the coverage test above silently weakens, because a dropped field would
		// compare equal to the value it was dropped in favour of.
		val populated = fullyPopulated()
		val minimal = SessionSnapshot(
			packageName = "com.brave.browser",
			appLabel = "Brave",
			isTarget = false,
			title = null,
			artist = null,
			album = null,
			durationMs = null,
			positionMs = null,
			playedMs = 0,
			loopDetected = false,
			playbackState = "STATE_NONE",
			isPlaying = false,
			percentPlayed = null,
			identity = YouTubeProbe.Identity.Unconfirmed("nothing proved this"),
			notificationHint = null,
			metadataLines = emptyList(),
			trackStartedAtEpochSec = 0,
		)

		val shared = declaredState()
			.filter { it.name != "packageName" && it.name != "appLabel" }
			// Presentation only, and deliberately not carried. A finalized track is
			// by definition no longer waiting for anything to come back, so
			// `FinalizedTrack` has no field for this one and the losslessness check
			// must not demand it grow one. The test below pins that it is dropped.
			.filter { it.name != "awaitingContinuation" }
			.filter { it.get(populated) == it.get(minimal) }
			.map { it.name }

		assertEquals(emptyList<String>(), shared)
	}

	@Test
	fun `a waiting row's presentation flag does not survive finalization`() {
		val waiting = fullyPopulated().copy(awaitingContinuation = true)

		assertFalse(
			"a finalized listen came back still claiming to be waiting to resume",
			FinalizedTrack.from(waiting).toSessionSnapshot().awaitingContinuation,
		)
	}

	@Test
	fun `a snapshot built with no instance token still decomposes`() {
		// The adapter has to be total. A refusal path, a test fixture or a future
		// producer that stamps no token must decompose and rebuild rather than
		// throw into a MediaSession callback.
		val untokenized = fullyPopulated().copy(trackInstanceToken = null)
		val rebuilt = FinalizedTrack.from(untokenized).toSessionSnapshot()

		assertEquals(untokenized, rebuilt)
		assertEquals(
			TrackInstanceId.fromStartSecond("com.brave.browser", 1_700_000_000),
			FinalizedTrack.from(untokenized).trackInstance,
		)
	}

	@Test
	fun `the instance id separates two listens that begin in the same second`() {
		val first = fullyPopulated().copy(trackInstanceToken = 41)
		val second = fullyPopulated().copy(trackInstanceToken = 42)

		assertEquals(first.trackStartedAtEpochSec, second.trackStartedAtEpochSec)
		assertNotEquals(first.trackInstance, second.trackInstance)
	}

	@Test
	fun `the fallback instance token can never collide with a real one`() {
		// Real tokens come from an `AtomicLong(1)` and only ever increase, so the
		// fallback's negative space is disjoint by construction rather than by
		// luck. Stated as a test because the two facts live in different files.
		val fallback = TrackInstanceId.fromStartSecond("com.brave.browser", 1_700_000_000)
		assertTrue(fallback.instanceToken < 0)
		assertTrue(MediaSessionAdEvidence.nextTrackToken() > 0)
	}

	@Test
	fun `the decomposition groups the concepts it claims to group`() {
		val track = FinalizedTrack.from(fullyPopulated())

		// Source: what the listen came from, with capabilities rather than a
		// package check at the point of use.
		assertEquals("com.brave.browser", track.source.packageName)
		assertEquals(false, track.source.packageProvesSource)
		assertEquals(false, track.source.trustsMetadataArtist)

		// Measurement: content consumed, and how much of it was inferred.
		assertEquals(195_000, track.measurement.playedMs)
		assertEquals(12_000, track.measurement.inferredPlayedMs)
		assertEquals(183_000, track.measurement.measuredPlayedMs)
		assertTrue(track.measurement.reachedThreshold(0.6))

		// Evidence: what it was, and what could veto it.
		assertEquals("dQw4w9WgXcQ", track.evidence.confirmed?.videoId)
		assertTrue(track.evidence.isYouTube)
		assertTrue(track.evidence.hasShortSourceProof)
		assertEquals("Sponsored · Ad", track.evidence.explicitAdSignal)
	}

	@Test
	fun `YouTube Music is the source that trusts its own artist field`() {

		val music = FinalizedTrack.from(
			fullyPopulated().copy(
				packageName = YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
				browserEvidenceEnabled = false,
			),
		)
		val app = FinalizedTrack.from(
			fullyPopulated().copy(
				packageName = YouTubeProbe.YOUTUBE_PACKAGE,
				browserEvidenceEnabled = false,
			),
		)

		assertTrue(music.source.trustsMetadataArtist)
		assertTrue(music.source.packageProvesSource)
		assertEquals(false, app.source.trustsMetadataArtist)
		assertTrue(app.source.packageProvesSource)
	}
}
