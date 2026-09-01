package com.rustedwax.app.detect

import com.rustedwax.core.SourceSessionId

/** Source registration for the ordinary first-party video player. */
class NativeYouTubeAdapter(
	packageName: String,
	appLabel: String,
	evidenceCoordinator: EvidenceCoordinator? = null,
	evidenceSourceSession: SourceSessionId = SourceSessionId(
		packageName,
		NativeSourceSwitches.epochFor(packageName),
	),
) : NativeYouTubeSource(packageName, appLabel, evidenceCoordinator, evidenceSourceSession)
