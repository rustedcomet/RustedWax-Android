package com.rustedwax.youtube.identity

import com.rustedwax.identity.api.ResolvedItemIdentity

/** Structured source-owned resolver evidence carried into finalization. */
data class VideoResolution(
	val videoId: String,
	val source: String,
	val title: String? = null,
	val channel: String? = null,
	val lengthSeconds: Long? = null,
	val uniquelyResolved: Boolean = false,
	val collaborativeChannel: Boolean = false,
	val ownerHandle: String? = null,
	val structuredNativeMusic: Boolean = false,
	val creditedArtists: List<String> = emptyList(),
	val playlistVerified: Boolean = false,
	val historyVerified: Boolean = false,
	val localizedTitle: String? = null,
) : ResolvedItemIdentity {
	override val sourceItemId: String get() = videoId
	override val canonicalLink: String get() = "https://www.youtube.com/watch?v=$videoId"
	override val provenance: String get() = source
}

enum class VideoResolutionFailure {
	NOT_APPLICABLE,
	NO_MATCH,
	AMBIGUOUS,
	CONTRADICTION,
	TEMPORARY_FAILURE,
}

data class VideoResolutionAttempt(
	val resolution: VideoResolution? = null,
	val refusalReason: String? = null,
	val failure: VideoResolutionFailure? =
		if (resolution == null) VideoResolutionFailure.NO_MATCH else null,
) {
	init {
		require((resolution == null) == (failure != null)) {
			"a resolution and typed failure are mutually exclusive"
		}
	}
}
