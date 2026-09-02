package com.rustedwax.app.detect

import com.rustedwax.core.*
import com.rustedwax.core.MetadataFields
import com.rustedwax.core.SourceSessionId

class YouTubeMusicAdapter(
	packageName: String,
	appLabel: String,
	evidenceCoordinator: EvidenceCoordinator? = null,
	evidenceSourceSession: SourceSessionId = SourceSessionId(
		packageName,
		NativeSourceSwitches.epochFor(packageName),
	),
) : NativeYouTubeSource(packageName, appLabel, evidenceCoordinator, evidenceSourceSession) {

	/** Song and Video are alternate renderings of the selected work. */
	override val playbackCapabilities = super.playbackCapabilities.copy(
		republishesAlternateMediaDurations = true,
	)

	/** A music player, publishing a real artist and a real album. */
	override val profile: SourceProfile = SourceProfile.YOUTUBE_MUSIC

	/**
	 * Every positive-duration Music presentation is eligible for the existing
	 * bounded, contradiction-checked identity lookup. Native YouTube retains its
	 * 60-second continuity optimization; Music also needs this proof to establish
	 * genuine short works without treating their duration as ad evidence.
	 */
	override fun mayPreResolveExactItemId(request: SourceExactIdRequest): Boolean {
		if (presentedTitle(request.fields)?.isNotBlank() != true) return false
		if (presentedArtist(request.fields)?.isNotBlank() != true) return false
		return (request.durationMs ?: 0) > 0
	}

	/**
	 * YouTube Music's Video mode publishes a YouTube upload title while Song
	 * mode publishes the separated work title. Declare their shared work without
	 * replacing either raw presentation: exact-id resolution and the final payload
	 * still describe the representation the player actually published.
	 * [TitleParser] is already the production scrobble normalization grammar, so
	 * this creates no second title heuristic.
	 */
	override fun trackIdentity(fields: MetadataFields?): TrackIdentity {
		val observed = super.trackIdentity(fields)
		val work = observed.title?.let { TitleParser.parse(it, observed.artist).track }
		val isCatalogPresentation = observed.title?.let(TextNormalizer::presentation) ==
			work?.let(TextNormalizer::presentation)
		return observed.copy(
			logicalWork = work,
			logicalVariant = if (isCatalogPresentation) "catalog work" else "video upload",
		)
	}

	/** No Shorts surface, so no handover may ever take this player away. */
	override val evidenceCapabilities =
		super.evidenceCapabilities.copy(presentsForegroundShorts = false)

	/** The playlist bar belongs to the YouTube app's watch screen, not to this one. */
	override fun withPlaylistEvidence(context: ResolverContext): ResolverContext = context

	/** Nothing package-scoped here; the observer this would clear is not this app's. */
	override fun onPackageStateReset() = Unit
}
