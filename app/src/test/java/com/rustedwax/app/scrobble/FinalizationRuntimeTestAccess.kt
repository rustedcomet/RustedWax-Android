package com.rustedwax.app.scrobble

import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.detect.FinalizedTrack
import com.rustedwax.app.detect.SessionSnapshot

/** Test-only spelling for driving the production use case without recreating a facade. */
internal fun FinalizationRuntime.executeAutomatic(session: SessionSnapshot) {
	if (isReady) finalizeTrack.execute(FinalizedTrack.from(session), FinalizationTrigger.AUTOMATIC)
}

internal fun FinalizationRuntime.executeManual(
	session: SessionSnapshot,
	onFeedback: (String, Boolean) -> Unit,
) {
	if (!isReady) {
		onFeedback("Scrobble engine is not ready. Nothing sent.", true)
		return
	}
	finalizeTrack.execute(
		FinalizedTrack.from(session),
		FinalizationTrigger.MANUAL,
		onFeedback = onFeedback,
	)
}

internal fun FinalizationRuntime.executeShadow(session: SessionSnapshot) {
	EventLog.withWritesSuppressed {
		if (isReady) finalizeTrack.execute(FinalizedTrack.from(session), FinalizationTrigger.SHADOW)
	}
}
