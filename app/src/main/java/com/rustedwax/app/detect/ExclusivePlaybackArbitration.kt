package com.rustedwax.app.detect

import com.rustedwax.core.*
/** Immutable facts needed to decide one cross-source playback handoff. */
data class ConcurrentPlaybackCandidate(
	val key: String,
	val packageName: String,
	val live: Boolean,
	val sourceProven: Boolean,
	val packageProvesSource: Boolean,
	val publishesHostScreenEvidence: Boolean,
	val transport: TransportState,
	val rawPositionMs: Long?,
	val metadataUsable: Boolean,
)

/** Pure seam for playback ownership when first-party YouTube takes audio over. */
object ExclusivePlaybackArbitration {
	fun staleHostWatchesToFinalize(
		takeover: ConcurrentPlaybackCandidate,
		candidates: List<ConcurrentPlaybackCandidate>,
	): Set<String> {
		// A package-proven first-party source with real progress is the bounded
		// ownership signal. A bare PLAYING bit is not: the field defect exists
		// precisely because Chromium leaves that bit behind after losing playback.
		if (!takeover.live || !takeover.sourceProven || !takeover.packageProvesSource ||
			takeover.transport != TransportState.PLAYING || takeover.rawPositionMs == null ||
			!takeover.metadataUsable
		) return emptySet()

		return candidates.asSequence()
			.filter { it.key != takeover.key && it.packageName != takeover.packageName }
			// Only a proven host session may be named or finalized. An unknown web
			// page remains private even while another source starts.
			.filter {
				it.live && it.sourceProven && it.publishesHostScreenEvidence &&
					it.transport == TransportState.PLAYING && it.metadataUsable
			}
			// A readable browser position is independent evidence that its content
			// may genuinely still be running. An unavailable position has no such proof;
			// only that unmeasurable clock is ended by the exclusive takeover.
			.filter { it.rawPositionMs == null }
			.map(ConcurrentPlaybackCandidate::key)
			.toSet()
	}
}
