package com.rustedwax.app.replay

import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 9 load gate; synthetic because the observed 180 row bodies were not retained. */
class Phase9LongPlaylistSoakTest : ReplayScenarioTest() {

	@Test
	fun `synthetic 180-entry native playlist keeps order identity and one dispatch per listen`() {
		val harness = ReplayHarness(ReplaySource.NATIVE_YOUTUBE)
		val playlistName = "Synthetic 180-entry Phase 9 playlist"
		val playlistId = "PL_phase9_soak_180"
		val idsByTitle = (0 until 180).associate { index ->
			"Synthetic playlist item ${index.toString().padStart(3, '0')}" to
				"p9${index.toString().padStart(9, '0')}"
		}
		harness.env.identity.nativePlaylistId = { name ->
			playlistId.takeIf { name == playlistName }
		}
		harness.env.identity.fromPlaylist = { list, title ->
			idsByTitle[title]?.takeIf { list == playlistId }?.let { videoId ->
				VideoResolution(
					videoId = videoId,
					source = "synthetic Phase 9 playlist soak",
					title = title,
					channel = "Synthetic Playlist Owner",
					lengthSeconds = 60,
					uniquelyResolved = true,
					playlistVerified = true,
				)
			}
		}
		idsByTitle.forEach { (title, videoId) ->
			harness.env.facts.put(
				VideoFacts(
					videoId = videoId,
					title = title,
					author = "Synthetic Playlist Owner",
					lengthSeconds = 60,
					category = "Music",
					watchPageResolved = true,
					isUnlisted = false,
				),
			)
		}

		idsByTitle.forEach { (title, _) ->
			harness.feed(
				PlaybackEvent.NativePlaylistObserved(playlistName, total = 180),
				PlaybackEvent.SessionMetadata(
					title = title,
					artist = "Synthetic Playlist Owner",
					durationMs = 60_000,
				),
				PlaybackEvent.PlaybackStateChanged(playing = true, positionMs = 0),
				PlaybackEvent.Advance(60_000),
				PlaybackEvent.Finalized("synthetic playlist successor"),
			)
		}

		assertEquals(180, harness.presentedToEngine)
		assertEquals(180, harness.outcomes.size)
		assertEquals(180, harness.broadcasts.size)
		assertEquals(180, harness.env.broadcaster.sent.size)
		assertEquals(180, harness.broadcasts.map { it.videoId }.distinct().size)
		assertEquals(emptyList<String>(), harness.duplicateAttempts)
		assertEquals(emptyList<ReplayHarness.RefusalKind>(), harness.refusalKinds)
	}
}
