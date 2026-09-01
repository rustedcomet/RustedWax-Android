package com.rustedwax.app.replay.reference.phase01

import com.rustedwax.app.detect.AdEvidence
import com.rustedwax.app.detect.LegacyEvidenceCallbacks
import com.rustedwax.app.detect.MediaSessionAccessibilityEvidence
import com.rustedwax.app.detect.MediaSessionAdEvidence
import com.rustedwax.app.detect.NativeShortsObserver
import com.rustedwax.app.detect.NotificationHints
import com.rustedwax.app.detect.UrlEvidence

/** Extensions keep the byte-frozen reference executable after main callback deletion. */
internal var NotificationHints.onHint: ((String) -> Unit)?
	get() = LegacyEvidenceCallbacks.notificationHint
	set(value) { LegacyEvidenceCallbacks.notificationHint = value }

internal var UrlEvidence.onEvidence: ((String) -> Unit)?
	get() = LegacyEvidenceCallbacks.urlEvidence
	set(value) { LegacyEvidenceCallbacks.urlEvidence = value }

internal var AdEvidence.onEvidence: ((AdEvidence.Evidence) -> Unit)?
	get() = LegacyEvidenceCallbacks.adEvidence
	set(value) { LegacyEvidenceCallbacks.adEvidence = value }

internal var MediaSessionAdEvidence.onObservation: ((MediaSessionAdEvidence.Observation) -> Unit)?
	get() = LegacyEvidenceCallbacks.mediaSessionAd
	set(value) { LegacyEvidenceCallbacks.mediaSessionAd = value }

internal var MediaSessionAccessibilityEvidence.onScan:
	((MediaSessionAccessibilityEvidence.Scan) -> Unit)?
	get() = LegacyEvidenceCallbacks.accessibilityScan
	set(value) { LegacyEvidenceCallbacks.accessibilityScan = value }

internal var NativeShortsObserver.onEvent: ((NativeShortsObserver.Event) -> Unit)?
	get() = LegacyEvidenceCallbacks.nativeShortEvent
	set(value) { LegacyEvidenceCallbacks.nativeShortEvent = value }
