package com.rustedwax.app.replay.reference.current

import com.rustedwax.app.detect.SessionSnapshot

/**
 * One finalized listen, reduced to the facts a script determines.
 *
 * ## The one field that is deliberately excluded
 *
 * [SessionSnapshot.trackStartedAtEpochSec] is `System.currentTimeMillis()` at
 * the instant the track instance was established, and the two runs happen
 * milliseconds apart on a real wall clock. Comparing it raw would fail every
 * scenario for a reason neither implementation controls.
 *
 * It was previously kept as the offset from the first listen of each run. That
 * still depended on the wall-clock phase: if one sequential run crossed a whole
 * second while the other did not, byte-identical behavior compared as 0 versus
 * 1. The exact clean-clone gate reproduced that failure. Distinct-instance
 * relationships are compared deterministically by [AddedFields.instanceOrdinal],
 * which is the `TrackInstanceId` behavior this suite actually needs to preserve,
 * so the uncontrolled epoch second is excluded rather than weakly normalised.
 *
 * Nothing else is normalised. Identity, resolver context, metadata dump lines,
 * notification hint, percentage, position, played time, epoch, proof, coverage
 * and ad signal are compared as the two implementations produced them.
 *
 * ## What is compared separately instead
 *
 * Two `SessionSnapshot` fields did not exist at the reference commit at all —
 * see [AddedFields] — so an equality over them would not be a parity comparison
 * but a comparison against a field that had no value to have. They are asserted
 * as a declared difference, in both directions.
 */
data class Listen(
	val packageName: String,
	val appLabel: String,
	val isTarget: Boolean,
	val title: String?,
	val artist: String?,
	val album: String?,
	val durationMs: Long?,
	val positionMs: Long?,
	val playedMs: Long,
	val loopDetected: Boolean,
	val explicitAdSignal: String?,
	val browserEvidenceEnabled: Boolean,
	val accessibilityCoverage: String?,
	val playbackState: String,
	val foregroundProgressLost: Boolean,
	val inferredPlayedMs: Long,
	val isPlaying: Boolean,
	val percentPlayed: Double?,
	val identity: String,
	val resolverContext: String,
	val notificationHint: String?,
	val metadataLines: List<String>,
	val genre: String?,
	val sourceEpoch: Long?,
	val sourceProof: String,
	val ownerHandle: String?,
)

data class AddedFields(
	val firstObservedPositionMs: Long?,
	/** Which distinct listen instance this is, in first-seen order. */
	val instanceOrdinal: Int?,
)

/**
 * One run's diagnostic log, with only its timestamps taken out.
 *
 * `EventLog` stamps every line `HH:mm:ss.SSS`, and the two runs happen at
 * different instants. Nothing else is touched — the tag, the package prefix and
 * the whole message body are compared as written, which is what makes this the
 * most sensitive surface in the gate: it records the route identity took, the
 * carry decision, the finalize reason and the measured totals. Two
 * implementations can agree on a payload and still disagree about how they got
 * there.
 */
fun List<String>.normaliseLog(): List<String> = map {
	// Dated as well as timed since the log gained a retention window: an age
	// cannot be established from a bare wall-clock time. See
	// [com.rustedwax.app.detect.LogRetention].
	it.replace(Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"), "<t>")
}

/** The fields the reference commit had no place to put. */
fun List<SessionSnapshot>.addedFields(): List<AddedFields> {
	val ordinals = mutableMapOf<Long, Int>()
	return map { s ->
		AddedFields(
			firstObservedPositionMs = s.firstObservedPositionMs,
			instanceOrdinal = s.trackInstanceToken?.let { ordinals.getOrPut(it) { ordinals.size } },
		)
	}
}

/** Normalise a run's finalized snapshots into comparable listens. */
fun List<SessionSnapshot>.asListens(): List<Listen> {
	return map { s ->
		Listen(
			packageName = s.packageName,
			appLabel = s.appLabel,
			isTarget = s.isTarget,
			title = s.title,
			artist = s.artist,
			album = s.album,
			durationMs = s.durationMs,
			positionMs = s.positionMs,
			playedMs = s.playedMs,
			loopDetected = s.loopDetected,
			explicitAdSignal = s.explicitAdSignal,
			browserEvidenceEnabled = s.browserEvidenceEnabled,
			accessibilityCoverage = s.accessibilityCoverage?.toString(),
			playbackState = s.playbackState,
			foregroundProgressLost = s.foregroundProgressLost,
			inferredPlayedMs = s.inferredPlayedMs,
			isPlaying = s.isPlaying,
			percentPlayed = s.percentPlayed,
			identity = s.identity.toString(),
			resolverContext = s.resolverContext.toString(),
			notificationHint = s.notificationHint?.toString(),
			metadataLines = s.metadataLines,
			genre = s.genre,
			sourceEpoch = s.sourceEpoch,
			sourceProof = s.sourceProof.name,
			ownerHandle = s.ownerHandle,
		)
	}
}
