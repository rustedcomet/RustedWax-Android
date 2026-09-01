package com.rustedwax.app.detect

/**
 * Test-only callback buses needed solely by the frozen pre-migration mirror.
 * They are absent from the production APK and must never be imported by main.
 */
internal object LegacyEvidenceCallbacks {
	var notificationHint: ((String) -> Unit)? = null
	var urlEvidence: ((String) -> Unit)? = null
	var adEvidence: ((AdEvidence.Evidence) -> Unit)? = null
	var mediaSessionAd: ((MediaSessionAdEvidence.Observation) -> Unit)? = null
	var accessibilityScan: ((MediaSessionAccessibilityEvidence.Scan) -> Unit)? = null
	var nativeShortEvent: ((NativeShortsObserver.Event) -> Unit)? = null

	fun clear() {
		notificationHint = null
		urlEvidence = null
		adEvidence = null
		mediaSessionAd = null
		accessibilityScan = null
		nativeShortEvent = null
	}
}

/** Frozen replay-only model of the former process-wide clear list. */
internal object RunScopedEvidence {
	fun clearAll(includeCarriedProgress: Boolean) {
		NotificationHints.clearAll()
		UrlEvidence.clearAll()
		AdEvidence.clearAll()
		MediaSessionAdEvidence.clearAll()
		MediaSessionAccessibilityEvidence.clearAll()
		if (includeCarriedProgress) TrackProgressCarry.clear()
		LegacyEvidenceCallbacks.clear()
	}
}
