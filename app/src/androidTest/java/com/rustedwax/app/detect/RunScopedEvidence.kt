package com.rustedwax.app.detect

/** Instrumentation-test reset support; absent from the production APK. */
internal object RunScopedEvidence {
	fun clearAll(includeCarriedProgress: Boolean) {
		NotificationHints.clearAll()
		UrlEvidence.clearAll()
		AdEvidence.clearAll()
		MediaSessionAdEvidence.clearAll()
		MediaSessionAccessibilityEvidence.clearAll()
		if (includeCarriedProgress) TrackProgressCarry.clear()
	}
}
