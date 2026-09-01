package com.rustedwax.app.detect

import com.rustedwax.core.PlaybackMeasurement
import com.rustedwax.core.AutomaticWriteAuthorization
import com.rustedwax.core.SourceDescriptor
import com.rustedwax.core.SourceSessionId
import com.rustedwax.core.TrackInstanceId
import com.rustedwax.core.TrackMetadata

/**
 * One finished listen, decomposed into the concepts that decide it.
 *
 * ## What this is for
 *
 * `<redacted-private-path>` §5 counts 27 fields on `SessionSnapshot` and names the
 * eight unrelated responsibilities they represent — measurement, Android source
 * identity, raw metadata, browser evidence, native evidence, diagnostics,
 * resolver inputs and policy inputs. Immutability is a strength; the breadth is
 * what keeps them coupled, because any consumer can reach any field and no
 * signature records which ones it actually used.
 *
 * This is the audit's Phase 2 `FinalizedTrack`: the same facts, grouped so a
 * later stage can be handed [measurement] without being handed [evidence], and
 * so the reducer extracted in Phase 3 has a typed thing to produce.
 *
 * ## Strangler, not replacement
 *
 * Nothing in the pipeline consumes this yet. `SessionSnapshot` remains the
 * transport the production path uses end to end, exactly as the audit requires:
 * *"the current and replacement implementations run side by side until their
 * results agree"*, and *"the replacement remains shadow-only and must not
 * broadcast until parity is established."*
 *
 * What exists instead is a **total, lossless adapter in both directions**.
 * [from] decomposes a snapshot; [toSessionSnapshot] rebuilds one. Every field of
 * `SessionSnapshot` is either carried through or derived from a value that is,
 * and `FinalizedTrackAdapterTest` proves that by reflection over the data class
 * itself rather than by a hand-written field list that a later `copy(` would
 * silently outgrow.
 *
 * `DomainParityReplayTest` is the parity gate the audit moves to Phase 2: it
 * replays the corpus twice through the real `FinalizationRuntime`, once on the
 * original snapshots and once on snapshots that have been through this
 * decomposition, and compares the broadcast payload **bytes** and the typed
 * terminal outcomes. That is a genuine old/new comparison of what this phase
 * actually changes — the transport object — and it is deliberately not
 * described as reducer parity, which there is no second implementation of until
 * Phase 3.
 */
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
		// Three types carry a package name, and they are three views of one
		// listen. If they could disagree, the dedup key, the staleness check and
		// the evidence stores would each be keyed to a different source — which is
		// `<redacted-private-path>` §2's "keyed primarily by package" defect made
		// worse rather than better by typing it.
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
		// the payload builder and the UI — the audit's Phase 7 split, not this
		// phase's behaviour-preserving contract. So the *domain* object is
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
		 * The compatibility adapter the audit's Phase 2 asks for: *"add a
		 * compatibility adapter from the existing `SessionSnapshot` so the old
		 * pipeline continues working while migration proceeds."*
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
