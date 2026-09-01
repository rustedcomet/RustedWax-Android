package com.rustedwax.app.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3TelemetryTest {

	@Test
	fun `snapshot telemetry is exact and does not expose title or owner plaintext`() {
		val snapshot = SessionSnapshot(
			packageName = YouTubeProbe.YOUTUBE_PACKAGE,
			appLabel = "YouTube",
			isTarget = true,
			title = "Private title",
			artist = "@private-owner",
			album = null,
			durationMs = 60_000,
			positionMs = 12_000,
			playedMs = 11_000,
			loopDetected = false,
			inferredPlayedMs = 3_000,
			playbackState = "PLAYING",
			isPlaying = true,
			percentPlayed = 11.0 / 60.0,
			foregroundProgressLost = true,
			identity = YouTubeProbe.Identity.Confirmed(
				videoId = "tSi6Dn1H36Y",
				url = "https://www.youtube.com/watch?v=tSi6Dn1H36Y",
				isMusic = false,
				source = "test",
			),
			notificationHint = null,
			metadataLines = emptyList(),
			trackStartedAtEpochSec = 1,
			trackInstanceToken = 42,
			sourceProof = SourceProof.NATIVE_FOREGROUND_SHORT,
		)

		val line = Phase3Telemetry.snapshotLine(snapshot, 1.25, finalized = false, 99)

		assertTrue(line.contains("elapsed=99"))
		assertTrue(line.contains("token=42"))
		assertTrue(line.contains("item=tSi6Dn1H36Y"))
		assertTrue(line.contains("played=11000 normal=8000 inferred=3000"))
		assertTrue(line.contains("rate=1.250"))
		assertTrue(line.contains("progressLost=true"))
		assertTrue(line.contains("finalized=false"))
		assertFalse(line.contains("Private title"))
		assertFalse(line.contains("@private-owner"))
	}

	@Test
	fun `Short surface telemetry carries only exact structural counts`() {
		val line = Phase3Telemetry.shortSurfaceLine(
			observedAtMillis = 123,
			shortRoots = 1,
			shortPlayers = 1,
			progressSurfaces = 0,
			minimizedPlayers = 0,
		)

		assertTrue(line == "kind=short-surface observedAt=123 roots=1 players=1 progress=0 minimized=0")
	}

}
