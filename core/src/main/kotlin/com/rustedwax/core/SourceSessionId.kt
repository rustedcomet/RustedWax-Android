package com.rustedwax.core

data class SourceSessionId(
	val packageName: String,
	val sourceEpoch: Long?,
) {
	init {
		// A session with no package is not a session. It would compare unequal to
		// every real one, so a staleness check against it can only ever say "stale"
		// — which is a listen silently dropped rather than an error anyone sees.
		require(packageName.isNotBlank()) { "a source session must name its package" }
		require(sourceEpoch == null || sourceEpoch > 0) {
			"a source epoch is a generation counter and starts at 1: $sourceEpoch"
		}
	}

	override fun toString(): String =
		if (sourceEpoch == null) packageName else "$packageName@$sourceEpoch"
}
