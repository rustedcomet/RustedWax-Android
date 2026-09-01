package com.rustedwax.app.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSourceSwitchesTest {

	/**
	 * Turning the master off has to invalidate in-flight work, not merely stop
	 * new work. A snapshot captured a second earlier must fail its epoch check,
	 * or a track the user just switched off could still finalize and broadcast.
	 */
	@Test
	fun `the master switch off invalidates every snapshot, browser included`() {
		var on = NativeSourceSwitches.Config()
		on = NativeSourceSwitches.toggled(on, YouTubeProbe.YOUTUBE_PACKAGE, true)
		on = NativeSourceSwitches.toggled(on, YouTubeProbe.YOUTUBE_MUSIC_PACKAGE, true)
		assertTrue(
			NativeSourceSwitches.isSnapshotCurrent(
				on,
				YouTubeProbe.YOUTUBE_PACKAGE,
				on.youtubeEpoch,
			),
		)
		assertTrue(NativeSourceSwitches.isSnapshotCurrent(on, "com.android.chrome", null))

		val off = on.copy(youTubeScrobbling = false)
		assertFalse(
			NativeSourceSwitches.isSnapshotCurrent(
				off,
				YouTubeProbe.YOUTUBE_PACKAGE,
				on.youtubeEpoch,
			),
		)
		assertFalse(
			NativeSourceSwitches.isSnapshotCurrent(
				off,
				YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
				on.youtubeMusicEpoch,
			),
		)
		assertFalse(NativeSourceSwitches.isSnapshotCurrent(off, "com.android.chrome", null))
		assertFalse(NativeSourceSwitches.accepts(off, "com.android.chrome"))
	}

	@Test
	fun `enabling and disabling one native source changes only its boundary`() {
		val initial = NativeSourceSwitches.Config()
		val youtubeOn = NativeSourceSwitches.toggled(
			initial,
			YouTubeProbe.YOUTUBE_PACKAGE,
			true,
		)

		assertTrue(NativeSourceSwitches.accepts(youtubeOn, YouTubeProbe.YOUTUBE_PACKAGE))
		assertFalse(NativeSourceSwitches.accepts(youtubeOn, YouTubeProbe.YOUTUBE_MUSIC_PACKAGE))
		assertTrue(
			NativeSourceSwitches.isSnapshotCurrent(
				youtubeOn,
				YouTubeProbe.YOUTUBE_PACKAGE,
				youtubeOn.youtubeEpoch,
			),
		)

		val youtubeOff = NativeSourceSwitches.toggled(
			youtubeOn,
			YouTubeProbe.YOUTUBE_PACKAGE,
			false,
		)
		assertFalse(
			NativeSourceSwitches.isSnapshotCurrent(
				youtubeOff,
				YouTubeProbe.YOUTUBE_PACKAGE,
				youtubeOn.youtubeEpoch,
			),
		)
		assertTrue(NativeSourceSwitches.accepts(youtubeOff, "com.android.chrome"))
	}

	@Test
	fun `Stop reset or listener reconnect invalidates both native snapshots`() {
		var enabled = NativeSourceSwitches.Config()
		enabled = NativeSourceSwitches.toggled(enabled, YouTubeProbe.YOUTUBE_PACKAGE, true)
		enabled = NativeSourceSwitches.toggled(enabled, YouTubeProbe.YOUTUBE_MUSIC_PACKAGE, true)
		val reset = NativeSourceSwitches.invalidated(enabled)

		assertFalse(
			NativeSourceSwitches.isSnapshotCurrent(
				reset,
				YouTubeProbe.YOUTUBE_PACKAGE,
				enabled.youtubeEpoch,
			),
		)
		assertFalse(
			NativeSourceSwitches.isSnapshotCurrent(
				reset,
				YouTubeProbe.YOUTUBE_MUSIC_PACKAGE,
				enabled.youtubeMusicEpoch,
			),
		)
		assertTrue(reset.youtubeEnabled)
		assertTrue(reset.youtubeMusicEnabled)
	}

}
