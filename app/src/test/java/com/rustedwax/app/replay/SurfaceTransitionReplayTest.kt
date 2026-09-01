package com.rustedwax.app.replay

import com.rustedwax.core.*
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Foreground, background, minimised and picture-in-picture.
 *
 * ## Read this before counting these as coverage
 *
 * The two halves of this file are worth different amounts, and saying so is the
 * point:
 *
 * | Surface | Status |
 * |---|---|
 * | picture-in-picture | reducer coverage here; installed-source production proof is host-side |
 * | foreground / background / minimised | fixture markers only; production proof is on-device |
 *
 * `<redacted-private-path>`, amended 2026-08-12, moved "real foreground/background
 * transitions through the production reducer" to the Phase 3 gate. That half of
 * the gate **cannot be met from a JVM trace**, for a reason worth stating rather
 * than working around: an ordinary MediaSession publishes *no callback at all*
 * when its window moves. There is no `PlaybackInput` for a backgrounding,
 * because on a device nothing happens. A harness event that translated into one
 * would be inventing a device behaviour in order to test it.
 *
 * **Closed 2026-08-13, on the device.**
 * `androidTest/.../SurfaceLifecycleDeviceTest` performs the transitions for real
 * — this app's Activity to the front, the launcher to the front — while a real
 * `MediaSession` keeps playing, and asserts through the production
 * `SessionProbe` that measurement continues across each one, that nothing
 * finalizes falsely, that a paused session still does not accrue while
 * minimised, and that the published UI state survives. What remains here is the
 * *fixture*: markers describing an input sequence, plus the structural check
 * below that they still reach nothing. That is what
 * `<redacted-private-path>` permits a retained fixture to be — "may describe
 * inputs, but must not remain a second behavioral implementation" — and the
 * single structural check below is the whole retained ordinary-surface half.
 *
 * Installed ordinary PiP and foreground-Short PiP are proved separately by
 * `tools/device/native-pip.sh` and `tools/device/native-shorts-pip.sh`. The PiP
 * tests retained here establish deterministic reducer behavior after the
 * Android producer has already supplied an observation; they are not counted as
 * installed-source evidence.
 */
class SurfaceTransitionReplayTest : ReplayScenarioTest() {

	private val videoId = "dQw4w9WgXcQ"

	private fun facts() = VideoFacts(
		videoId = videoId,
		title = "Rick Astley - Never Gonna Give You Up",
		author = "RickAstleyVEVO",
		lengthSeconds = 213,
		category = "Music",
		watchPageResolved = true,
		isUnlisted = false,
	)

	private fun browserHarness(): ReplayHarness {
		val harness = ReplayHarness(ReplaySource.BRAVE)
		harness.env.facts.put(facts())
		return harness
	}

	private fun nativeHarness(): ReplayHarness {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		harness.env.facts.put(facts())
		harness.env.identity.search = {
			VideoResolutionAttempt(
				resolution = VideoResolution(
					videoId = videoId,
					source = "surface fixture",
					title = "Rick Astley - Never Gonna Give You Up",
					channel = "RickAstleyVEVO",
					lengthSeconds = 213,
					uniquelyResolved = true,
				),
			)
		}
		return harness
	}

	private fun browserWatching() = listOf(
		PlaybackEvent.NotificationObserved(host = "youtube.com"),
		PlaybackEvent.UrlObserved(host = "www.youtube.com", videoId = videoId),
		PlaybackEvent.SessionMetadata(
			title = "Rick Astley - Never Gonna Give You Up",
			artist = "RickAstleyVEVO",
			durationMs = 213_000,
		),
		PlaybackEvent.PlaybackStateChanged(playing = true),
	)

	private fun nativeWatching() = listOf(
		PlaybackEvent.SessionMetadata(
			title = "Rick Astley - Never Gonna Give You Up",
			artist = "RickAstleyVEVO",
			durationMs = 213_000,
			mediaId = videoId,
		),
		PlaybackEvent.PlaybackStateChanged(playing = true),
	)

	// ---- what the ordinary surface markers actually are -----------------------

	/**
	 * The modelled claim, made checkable.
	 *
	 * A foreground/background/minimised marker is recorded and dispatched
	 * nowhere. Asserting that directly is the difference between "this gate is
	 * modelled" as a sentence in a document and as a property of the code: if one
	 * of these markers ever acquires an effect, the byte comparison below fails
	 * and this file stops being able to describe itself as modelled.
	 */
	@Test
	fun `ordinary surface markers are recorded and reach the reducer nowhere`() {
		val withMarkers = browserHarness()
		withMarkers.feed(
			browserWatching() +
				PlaybackEvent.SurfaceChanged(PlaybackEvent.Surface.FOREGROUND) +
				PlaybackEvent.Advance(100_000) +
				PlaybackEvent.SurfaceChanged(PlaybackEvent.Surface.BACKGROUND) +
				PlaybackEvent.Advance(100_000) +
				PlaybackEvent.Finalized(),
		)

		val without = browserHarness()
		without.feed(
			browserWatching() +
				PlaybackEvent.Advance(100_000) +
				PlaybackEvent.Advance(100_000) +
				PlaybackEvent.Finalized(),
		)

		assertEquals(
			listOf(PlaybackEvent.Surface.FOREGROUND, PlaybackEvent.Surface.BACKGROUND),
			withMarkers.trace.surfaceMarkers,
		)
		assertEquals(emptyList<PlaybackEvent.Surface>(), without.trace.surfaceMarkers)
		// The payload bytes, not a parsed view of the fields the test cared about.
		assertEquals(
			without.env.broadcaster.sent.map { it.json },
			withMarkers.env.broadcaster.sent.map { it.json },
		)
	}

	// ---- picture-in-picture reaches the inference gate ------------------------

	@Test
	fun `a measurable native session in picture-in-picture is not credited twice`() {
		// The safety property the gate exists for. This session keeps publishing
		// position, so wall-clock inference must contribute nothing: a listen that
		// counted both would reach any threshold in half the time.
		val harness = nativeHarness()

		harness.feed(
			nativeWatching() +
				PlaybackEvent.Advance(100_000) +
				PlaybackEvent.SurfaceChanged(PlaybackEvent.Surface.PICTURE_IN_PICTURE) +
				PlaybackEvent.Advance(100_000) +
				PlaybackEvent.SurfaceChanged(PlaybackEvent.Surface.PICTURE_IN_PICTURE) +
				PlaybackEvent.Advance(13_000) +
				PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.broadcasts.size)
		// 213 s measured out of 213 s, and nothing inferred on top of it.
		assertEquals(100, harness.broadcasts.single().percentPlayed)
		assertEquals(0, harness.finalized.single().inferredPlayedMs)
	}

	@Test
	fun `a browser in picture-in-picture never accrues inferred time`() {
		// A browser reports position wherever its window is, so the capability is
		// off for it entirely. Stated as its own scenario because the alternative
		// — inferring for a source that can be measured — is silent over-counting.
		val harness = browserHarness()

		harness.feed(
			browserWatching() +
				PlaybackEvent.SurfaceChanged(PlaybackEvent.Surface.PICTURE_IN_PICTURE) +
				PlaybackEvent.Advance(200_000) +
				PlaybackEvent.SurfaceChanged(PlaybackEvent.Surface.PICTURE_IN_PICTURE) +
				PlaybackEvent.Finalized(),
		)

		assertEquals(0, harness.finalized.single().inferredPlayedMs)
		assertEquals(1, harness.broadcasts.size)
		assertEquals(94, harness.broadcasts.single().percentPlayed)
	}

	@Test
	fun `a native session that stops publishing progress can be credited in PiP`() {
		// The other half of the gate. Here the transport is not playing as far as
		// the MediaSession is concerned — `STATE_NONE, pos=0` is what a PiP Short
		// actually publishes — so inference is the only thing that can speak, and
		// the credit is named separately all the way to the snapshot.
		val harness = nativeHarness()

		harness.feed(
			listOf(
				PlaybackEvent.SessionMetadata(
					title = "Rick Astley - Never Gonna Give You Up",
					artist = "RickAstleyVEVO",
					durationMs = 213_000,
					mediaId = videoId,
				),
				PlaybackEvent.PlaybackStateChanged(playing = false),
				PlaybackEvent.SurfaceChanged(PlaybackEvent.Surface.PICTURE_IN_PICTURE),
				PlaybackEvent.Advance(60_000),
				PlaybackEvent.SurfaceChanged(PlaybackEvent.Surface.PICTURE_IN_PICTURE),
				PlaybackEvent.Finalized(),
			),
		)

		val snapshot = harness.finalized.single()
		assertTrue(
			"a session with no measurable progress is what inference is for",
			snapshot.inferredPlayedMs > 0,
		)
		// Every millisecond of it is inferred, and the snapshot says so rather
		// than presenting it as measured.
		assertEquals(snapshot.playedMs, snapshot.inferredPlayedMs)
	}

	// ---- surfaces do not disturb the listen's identity ------------------------

	@Test
	fun `picture in picture observations never start a new listen or move the frozen start`() {
		// A PiP observation that reset the track would give the second half of
		// one viewing its own dedup key, and the chain would take both.
		val harness = browserHarness()

			harness.feed(
			browserWatching() +
				PlaybackEvent.Advance(100_000) +
				PlaybackEvent.SurfaceChanged(PlaybackEvent.Surface.PICTURE_IN_PICTURE) +
				PlaybackEvent.Advance(100_000) +
				PlaybackEvent.Finalized(),
		)

		assertEquals(1, harness.finalized.size)
		assertEquals(1, harness.transactionIds.size)
		assertEquals(emptyList<String>(), harness.duplicateAttempts)
		assertEquals(videoId, harness.broadcasts.single().videoId)
	}
}
