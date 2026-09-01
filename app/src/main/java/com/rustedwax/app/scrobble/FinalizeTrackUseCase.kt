package com.rustedwax.app.scrobble

import com.rustedwax.app.detect.FinalizedTrack

internal enum class FinalizationTrigger {
	AUTOMATIC,
	MANUAL,
	SHADOW,
}

internal fun interface FinalizationOrchestrator {
	fun execute(
		track: FinalizedTrack,
		trigger: FinalizationTrigger,
		onFeedback: ((String, Boolean) -> Unit)?,
		onOutcome: ((FinalizationOutcome) -> Unit)?,
	)
}

/**
 * The single boundary for every supported finalization trigger.
 *
 * It accepts one frozen domain track. Transport retry accepts serialized bytes
 * elsewhere and therefore cannot re-enter this use case. Automatic is the live
 * production caller today; manual and shadow remain behaviorally tested modes
 * without a current UI or service caller.
 */
internal class FinalizeTrackUseCase(
	private val orchestrator: FinalizationOrchestrator,
) {
	fun execute(
		track: FinalizedTrack,
		trigger: FinalizationTrigger,
		onFeedback: ((String, Boolean) -> Unit)? = null,
		onOutcome: ((FinalizationOutcome) -> Unit)? = null,
	) = orchestrator.execute(track, trigger, onFeedback, onOutcome)
}
