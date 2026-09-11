package com.rustedwax.app.detect

import com.rustedwax.core.PlaybackMeasurement
import com.rustedwax.core.AutomaticWriteAuthorization
import com.rustedwax.core.SourceDescriptor
import com.rustedwax.core.SourceSessionId
import com.rustedwax.core.TrackInstanceId
import com.rustedwax.core.TrackMetadata

data class FinalizedTrack(
	val sourceSession: SourceSessionId,
	val trackInstance: TrackInstanceId,
	val source: SourceDescriptor,
	val metadata: TrackMetadata,
	val measurement: PlaybackMeasurement,
	val evidence: IdentityEvidence,
	val automaticWriteAuthorization: AutomaticWriteAuthorization =
		AutomaticWriteAuthorization.LegacyEnabled,
	/** The track originated as a legacy snapshot and must preserve its refusal semantics. */
	val requiresCompatibilityPipeline: Boolean = false,
) {
	init {

		require(sourceSession.packageName == source.packageName) {
			"source session ${sourceSession.packageName} is not ${source.packageName}"
		}
		require(trackInstance.packageName == source.packageName) {
			"track instance ${trackInstance.packageName} is not ${source.packageName}"
		}
	}

	val packageName: String get() = source.packageName

	/** What a person calls this listen, when anything named it at all. */
	val title: String? get() = metadata.title

	/**
	 * Rebuild the transport object the existing pipeline consumes.
	 *
	 * The inverse of [from] for every field the snapshot carries. Two values are
	 * recomputed rather than stored — package proof and
	 * [SessionSnapshot.origin] are already derived from the package name by the
	 * snapshot itself — and everything else round-trips by identity.
	 */
	fun toSessionSnapshot(): SessionSnapshot = SessionSnapshot(
		packageName = source.packageName,
		appLabel = source.appLabel,
		isTarget = source.isWatched,
		title = metadata.title,
		artist = metadata.artist,
		album = metadata.album,
		durationMs = measurement.durationMs,
		positionMs = measurement.positionMs,
		firstObservedPositionMs = measurement.firstObservedPositionMs,
		playedMs = measurement.playedMs,
		unattributedMeasuredMs = measurement.unattributedMeasuredMs,
		refusedFinalPresentationMs = measurement.refusedFinalPresentationMs,
		loopDetected = measurement.loopDetected,
		explicitAdSignal = evidence.explicitAdSignal,
		browserEvidenceEnabled = source.hasBrowserEvidence,
		accessibilityCoverage = evidence.accessibilityCoverage,
		playbackState = measurement.transportState,
		foregroundProgressLost = measurement.progressSurfaceLost,
		inferredPlayedMs = measurement.inferredPlayedMs,
		isPlaying = measurement.isPlaying,
		percentPlayed = measurement.percentPlayed,
		// The compatibility boundary, named rather than hidden.
		//
		// `FinalizedTrack` accepts any source's `ItemIdentity`; the legacy
		// `SessionSnapshot` it converts back to still declares
		// `YouTubeProbe.Identity`, because widening that field reaches the engine,
		// the payload builder and the UI, outside this behavior-preserving
		// compatibility boundary. So the *domain* object is
		// source-neutral today and the *adapter* is not, and a non-YouTube source
		// fails loudly here rather than being silently mis-described as YouTube.
		identity = evidence.identity as? YouTubeProbe.Identity
			?: error(
				"SessionSnapshot cannot yet carry a ${evidence.identity.source} identity — " +
					"the legacy transport is YouTube-typed until the finalization split",
			),
		resolverContext = evidence.resolverContext,
		notificationHint = evidence.notificationHint,
		metadataLines = metadata.rawLines,
		trackStartedAtEpochSec = measurement.startedAtEpochSec,
		trackInstanceToken = trackInstance.instanceToken.takeIf { it > 0 },
		genre = metadata.genre,
		sourceEpoch = sourceSession.sourceEpoch,
		sourceProof = evidence.sourceProof,
		ownerHandle = evidence.ownerHandle,
		automaticWriteAuthorization = automaticWriteAuthorization,
	)

	companion object {

		/**
		 * Decompose a finalized snapshot.
		 *
		 * Compatibility adapter from the existing `SessionSnapshot`, retained so
		 * snapshot producers and the source-neutral finalization domain use one
		 * conversion boundary.
		 *
		 * Total by construction — every branch reads a field that exists — so a
		 * snapshot can always be decomposed and rebuilt, including the degenerate
		 * ones a refusal path produces.
		 */
		fun from(snapshot: SessionSnapshot): FinalizedTrack = FinalizedTrack(
			sourceSession = snapshot.sourceSession,
			trackInstance = snapshot.trackInstance,
			source = SourceDescriptor(
				packageName = snapshot.packageName,
				appLabel = snapshot.appLabel,
				originName = snapshot.origin.displayName,
				packageProvesSource = snapshot.profile.packageProvesSource,
				isWatched = snapshot.isTarget,
				hasBrowserEvidence = snapshot.browserEvidenceEnabled,
				trustsMetadataArtist = snapshot.profile.trustsMetadataArtist,
			),
			metadata = TrackMetadata(
				title = snapshot.title,
				artist = snapshot.artist,
				album = snapshot.album,
				genre = snapshot.genre,
				rawLines = snapshot.metadataLines,
			),
			measurement = PlaybackMeasurement(
				playedMs = snapshot.playedMs,
				unattributedMeasuredMs = snapshot.unattributedMeasuredMs,
				refusedFinalPresentationMs = snapshot.refusedFinalPresentationMs,
				durationMs = snapshot.durationMs,
				positionMs = snapshot.positionMs,
				firstObservedPositionMs = snapshot.firstObservedPositionMs,
				inferredPlayedMs = snapshot.inferredPlayedMs,
				loopDetected = snapshot.loopDetected,
				progressSurfaceLost = snapshot.foregroundProgressLost,
				isPlaying = snapshot.isPlaying,
				transportState = snapshot.playbackState,
				startedAtEpochSec = snapshot.trackStartedAtEpochSec,
				percentPlayed = snapshot.percentPlayed,
			),
			evidence = IdentityEvidence(
				identity = snapshot.identity,
				resolverContext = snapshot.resolverContext,
				sourceProof = snapshot.sourceProof,
				notificationHint = snapshot.notificationHint,
				accessibilityCoverage = snapshot.accessibilityCoverage,
				explicitAdSignal = snapshot.explicitAdSignal,
				ownerHandle = snapshot.ownerHandle,
			),
			automaticWriteAuthorization = snapshot.automaticWriteAuthorization,
			requiresCompatibilityPipeline = true,
		)
	}
}
