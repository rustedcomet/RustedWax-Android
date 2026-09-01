package com.rustedwax.app.detect

import com.rustedwax.core.SourceSessionId

/** The only production package-to-adapter registration boundary. */
object SourceRegistry {
	/** Source-kind lookup used only at registration and compatibility boundaries. */
	fun packageProvesSource(packageName: String): Boolean =
		packageName in YouTubeProbe.YOUTUBE_APP_PACKAGES

	fun forPackage(
		packageName: String,
		appLabel: String,
		evidenceCoordinator: EvidenceCoordinator? = null,
		evidenceSourceSession: SourceSessionId = SourceSessionId(
			packageName,
			NativeSourceSwitches.epochFor(packageName),
		),
	): SourceAdapter? = when (packageName) {
		YouTubeProbe.YOUTUBE_MUSIC_PACKAGE ->
			YouTubeMusicAdapter(packageName, appLabel, evidenceCoordinator, evidenceSourceSession)
		YouTubeProbe.YOUTUBE_PACKAGE ->
			NativeYouTubeAdapter(packageName, appLabel, evidenceCoordinator, evidenceSourceSession)
		in YouTubeProbe.TARGET_PACKAGES ->
			BrowserYouTubeAdapter(packageName, appLabel, evidenceCoordinator, evidenceSourceSession)
		else -> null
	}

	fun forWatch(
		packageName: String,
		appLabel: String,
		evidenceCoordinator: EvidenceCoordinator? = null,
		evidenceSourceSession: SourceSessionId = SourceSessionId(
			packageName,
			NativeSourceSwitches.epochFor(packageName),
		),
	): SourceAdapter =
		forPackage(packageName, appLabel, evidenceCoordinator, evidenceSourceSession)
			?: BrowserYouTubeAdapter(
				packageName, appLabel, evidenceCoordinator, evidenceSourceSession,
			)

	val watchedPackages: Set<String> =
		YouTubeProbe.YOUTUBE_APP_PACKAGES + YouTubeProbe.TARGET_PACKAGES

	val epochScopedPackages: Set<String> = YouTubeProbe.YOUTUBE_APP_PACKAGES

	fun ignoredReason(packageName: String): String =
		if (forPackage(packageName, packageName)
				?.evidenceCapabilities
				?.scopedBySourceEpoch == true
		) {
			"native source toggle is off"
		} else {
			"not a supported source package"
		}
}
