package com.rustedwax.app.replay.reference.phase01

import com.rustedwax.app.detect.YouTubeProbe

/**
 * Test-only source snapshot support for the immutable Phase 0/1 reference.
 *
 * The recorded reference body still calls its historical native-package helper.
 * Keeping that spelling here avoids editing the recorded source while ensuring
 * no compatibility check remains in production code.
 */
fun YouTubeProbe.isNativePackage(packageName: String): Boolean =
	packageName == YouTubeProbe.YOUTUBE_PACKAGE ||
		packageName == YouTubeProbe.YOUTUBE_MUSIC_PACKAGE
