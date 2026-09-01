package com.rustedwax.app.detect

import com.rustedwax.core.*
import com.rustedwax.core.MetadataFields
import com.rustedwax.core.SourceSessionId

/**
 * The native YouTube Music app.
 *
 * ## The one difference that has teeth
 *
 * `<redacted-private-path>` §3.2, in one field. The YouTube **app** publishes the
 * *channel* in the MediaSession artist slot; YouTube **Music** publishes a real
 * artist and a real album. One rule covering both packages was correct for one
 * of them and wrote the channel on-chain as the artist for the other:
 *
 * ```
 * TITLE:  Snoop Doggy Dogg - Intro
 * ARTIST: King Of Rap          ← the channel
 *   → broadcast as artist "King Of Rap", title "Snoop Doggy Dogg - Intro"
 * ```
 *
 * That is [SourceProfile.trustsMetadataArtist], and it is declared **here and
 * only here**. Everything downstream reads the declaration; nothing asks whether
 * the package happens to be YouTube Music. Pano reaches the same split by
 * package list — every YouTube fork is in
 * `DEFAULT_IGNORE_ARTIST_META_WITHOUT_FALLBACK`, and YouTube Music deliberately
 * is not.
 *
 * ## The two things it must *not* inherit
 *
 * The native playlist observer is scoped to the YouTube app's watch screen by
 * the accessibility service that feeds it. Reading it here would attach one
 * app's visible playlist bar to another app's listen, and clearing it here would
 * discard evidence the YouTube app is still using. Both are refused explicitly
 * rather than by the base class happening not to be reached.
 */
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
