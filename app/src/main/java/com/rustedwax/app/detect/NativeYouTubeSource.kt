package com.rustedwax.app.detect

import com.rustedwax.core.*
import android.media.MediaMetadata
import com.rustedwax.core.MetadataFields
import com.rustedwax.core.PlaybackSourceCapabilities
import com.rustedwax.core.SourceSessionId

abstract class NativeYouTubeSource(
	override val packageName: String,
	override val appLabel: String,
	protected val evidenceCoordinator: EvidenceCoordinator? = null,
	protected val evidenceSourceSession: SourceSessionId = SourceSessionId(
		packageName,
		NativeSourceSwitches.epochFor(packageName),
	),
) : SourceAdapter {

	override val originName: String = YouTubeProbe.originForPackage(packageName).displayName

	/**
	 * All four native quirks, each measured rather than assumed — see
	 * [PlaybackSourceCapabilities] for the field evidence behind them.
	 */
	override open val playbackCapabilities = PlaybackSourceCapabilities(
		republishesShorterDurations = true,
		requiresExactIdToCarryProgress = true,
		usesStoppedReplacementGrace = true,
		supportsPictureInPictureInference = true,
	)

	override val evidenceCapabilities = SourceEvidenceCapabilities(
		packageProvesSource = true,
		publishesHostScreenEvidence = false,
		publishesStructuredTransportDump = true,
		scopedBySourceEpoch = true,
		presentsForegroundShorts = true,
	)

	override val profile: SourceProfile = SourceProfile.YOUTUBE.copy(
		packageProvesSource = true,
		presentsForegroundShorts = true,
	)

	/**
	 * A native listen does **not** survive a listener rebuild.
	 *
	 * Its identity is resolver-derived evidence stamped with a source generation
	 * rather than something readable off a bar afterwards, so a teardown is a hard
	 * boundary: the in-flight track is discarded and this package's observation
	 * state is reset, instead of being finished on evidence that no longer has an
	 * owner.
	 */
	override val teardownPolicy = SourceTeardownPolicy.DISCARD_AND_RESET

	// ── metadata interpretation ────────────────────────────────────────────

	/**
	 * The display keys are read here and nowhere else.
	 *
	 * `DISPLAY_TITLE` and `DISPLAY_SUBTITLE` are a tab's in a browser and a real
	 * track's in this app, which is the single clearest example of why one
	 * metadata reader for both sources was wrong. [BrowserTabMetadata] is not
	 * consulted at all: there is no tab, and its filters would strip a genuine
	 * "YouTube" channel name.
	 */
	override fun trackIdentity(fields: MetadataFields?): TrackIdentity = TrackIdentity(
		title = presentedTitle(fields),
		artist = presentedArtist(fields),
		album = MetadataDump.textOrNull(fields, MediaMetadata.METADATA_KEY_ALBUM),
		durationMs = MetadataDump.longOrNull(fields, MediaMetadata.METADATA_KEY_DURATION),
		// The one route by which a native session can name its item exactly. No
		// title, channel, duration or package-only inference may manufacture one.
		sourceItemId = (
			YouTubeProbe.identifyNative(packageName, fields) as? YouTubeProbe.Identity.Confirmed
			)?.videoId,
	)

	override fun presentedTitle(fields: MetadataFields?): String? =
		MetadataDump.textOrNull(fields, MediaMetadata.METADATA_KEY_TITLE)
			?: MetadataDump.textOrNull(fields, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)

	override fun presentedArtist(fields: MetadataFields?): String? =
		MetadataDump.textOrNull(fields, MediaMetadata.METADATA_KEY_ARTIST)
			?: MetadataDump.textOrNull(fields, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)

	/** There is no tab. */
	override fun namesContainerOnly(fields: MetadataFields?): Boolean = false

	// ── identity ───────────────────────────────────────────────────────────

	/** A browser notification describes a browser. There is nothing to bind. */
	override fun hostNotificationHint(
		fields: MetadataFields?,
		soleSession: Boolean,
	): NotificationHints.Hint? = null

	override fun readIdentity(request: SourceIdentityRequest): SourceIdentityReading =
		SourceIdentityReading(
			identity = YouTubeProbe.identifyNative(packageName, request.fields),
			resolverContext = withPlaylistEvidence(request.resolverContext),
			// A browser notification describes a browser. There is nothing to bind.
			notificationHint = null,
		)

	protected open fun withPlaylistEvidence(context: ResolverContext): ResolverContext =
		(if (evidenceCoordinator != null) {
			evidenceCoordinator.nativePlaylist(evidenceSourceSession)
		} else {
			NativePlaylistObserver.current()
		})?.let { playlist ->
			context.copy(
				nativePlaylistName = playlist.playlistName,
				nativePlaylistOwner = playlist.ownerName,
				nativePlaylistTotal = playlist.total,
			)
		} ?: context

	/** Nothing follows from a native identity that this source has to do itself. */
	override fun onIdentitySelected(request: SourceIdentitySelected): List<SourceEffect> =
		emptyList()

	/**
	 * Never. A host screen observation describes a browser's visible tab, and this
	 * source is not in a browser. Refused here rather than filtered by the host, so
	 * there is no downstream question about what kind of source this is.
	 */
	override fun selectForHostObservation(
		request: SourceHostObservationRequest,
	): SourceBinding = SourceBinding.Refused(
		"a host screen observation cannot describe a first-party app",
	)

	// ── track-instance evidence lifecycle ──────────────────────────────────
	//
	// All inert. The ad and coverage stores are keyed to browser track instances
	// and validated against the browser package set; a native listen has no
	// screen-scan surface to be covered by and no visible-label route to bind.

	override fun bindTrackInstance(
		instance: MediaSessionAdEvidence.TrackInstance,
		establishedAtMillis: Long,
	) = Unit

	override fun unbindTrackInstance(instance: MediaSessionAdEvidence.TrackInstance) = Unit

	override fun releaseTrackInstance(instance: MediaSessionAdEvidence.TrackInstance) = Unit

	override fun coverageFor(
		request: SourceCoverageRequest,
	): MediaSessionAccessibilityEvidence.Coverage? = null

	override fun restoreCarriedEvidence(
		request: SourceCarryRestoreRequest,
	): SourceCarryRestoreResult = SourceCarryRestoreResult()

	// ── host observations ──────────────────────────────────────────────────
	//
	// A browser's visible chrome says nothing about what this app is doing.
	// `METADATA_KEY_ADVERTISEMENT` is the one ad route that does apply, and it
	// arrives on the session's own bundle rather than through any of these.

	override fun bindVisibleAdEvidence(request: SourceVisibleAdRequest): SourceAdVerdict =
		SourceAdVerdict.NONE

	override fun bindHostAdLabel(request: SourceHostAdLabelRequest): SourceAdVerdict =
		SourceAdVerdict.NONE

	override fun bindScreenScan(request: SourceScreenScanRequest): SourceScreenScanVerdict =
		SourceScreenScanVerdict()

	// ── exact-id pre-resolution ────────────────────────────────────────────

	/**
	 * A stable presentation long enough to be worth one bounded lookup.
	 *
	 * The probe spends this only on controller continuity — a replacement session
	 * needs *something* exact to carry progress by, and this source publishes no
	 * id. Short transition phases are excluded by duration; foreground Shorts have
	 * their own exact owner-handle route and never reach here.
	 */
	override fun mayPreResolveExactItemId(request: SourceExactIdRequest): Boolean {
		if (presentedTitle(request.fields)?.isNotBlank() != true) return false
		if (presentedArtist(request.fields)?.isNotBlank() != true) return false
		return (request.durationMs ?: 0) >= SessionProbe.MIN_NATIVE_PRE_RESOLVE_DURATION_MS
	}

	// ── diagnostics and package-scoped state ───────────────────────────────

	override fun notes(moment: SourceMoment): List<SourceNote> = when (moment) {
		SourceMoment.ANNOUNCED -> listOf(
			SourceNote(
				"native",
				"$packageName MediaSession instrumentation active; browser visible-ad " +
					"protection does not cover native apps and no generic native ad flag is assumed",
			),
		)
		SourceMoment.FINALIZED -> listOf(
			SourceNote(
				"native",
				"$packageName finalized with exact MediaSession metadata/state captured; " +
					"native ad outcome remains unproven unless the dump contains a literal " +
					"structured signal",
			),
		)
	}

	override fun onPackageStateReset() {
		if (evidenceCoordinator != null) {
			evidenceCoordinator.clearNativePlaylist(evidenceSourceSession)
		} else {
			NativePlaylistObserver.clear("$packageName state reset")
		}
	}
}
