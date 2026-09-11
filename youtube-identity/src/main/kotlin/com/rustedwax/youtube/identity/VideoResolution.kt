package com.rustedwax.youtube.identity

import com.rustedwax.identity.api.ResolvedItemIdentity

/**
 * Resolver metadata describing how closely a published artist credit matched.
 *
 * This legacy field is diagnostic and may choose between equivalent metadata
 * descriptions. It does not authorize or veto a scrobble; verified media
 * identity and measured playback remain authoritative.
 */
enum class PerformerCreditEvidence {
	/** No additional artist-credit provenance was recorded. */
	NONE,

	/** A unique YouTube listing matched the work and complete published credit. */
	YOUTUBE_LISTING_COMPLETE_CREDIT,

	/** The video's canonical page matched the work and complete published credit. */
	CANONICAL_PAGE_COMPLETE_CREDIT,

	/** A complete YT Music catalog credit agreed with a canonical-page owner. */
	YOUTUBE_MUSIC_CATALOG_AND_CANONICAL_OWNER,
	;

	/** Used only to prefer an existing non-empty metadata grade while merging. */
	val authorizesSongWrite: Boolean get() = this != NONE
}

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
	/**
	 * The id came from YouTube Music's own *video* shelf, over a row whose work,
	 * complete credit and running time the player published exactly.
	 *
	 * Kept distinct because finalization has to re-ask the same question this
	 * route answered. YouTube and YouTube Music do not always spell one upload's
	 * title the same way — `Fade Away` in the player and the catalog, `Faded Away`
	 * on the canonical page — so re-checking such an id against the page's title
	 * would refuse the very upload it correctly named.
	 */
	val musicVideoRow: Boolean = false,
	val playlistVerified: Boolean = false,
	val historyVerified: Boolean = false,
	val localizedTitle: String? = null,
	/**
	 * This proof required the length the player was publishing to be the resolved
	 * work's own.
	 *
	 * Set by the matching code that actually performed the comparison, never
	 * inferred downstream from which route ran. The distinction is not about
	 * identity quality: a feed, a history entry or a title-and-channel match can
	 * all be right about *which* work while an interstitial borrowing that work's
	 * metadata is what is playing. Only a proof that also pinned the current
	 * length can say the interval being measured belongs to the work, which is
	 * what [com.rustedwax.core.PlaybackInput.PresentationAttributionEstablished]
	 * spends.
	 *
	 * Defaults to false so a new resolver path is unattributed until its author
	 * decides otherwise — the safe direction.
	 */
	val presentationDurationCorroborated: Boolean = false,
	/**
	 * Descriptive artist-credit provenance emitted by the resolver predicate.
	 *
	 * This is deliberately separate from exact video identity and presentation
	 * duration and is not a write gate. A missing value cannot refuse or degrade a
	 * verified listen.
	 */
	val performerCreditEvidence: PerformerCreditEvidence = PerformerCreditEvidence.NONE,
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
