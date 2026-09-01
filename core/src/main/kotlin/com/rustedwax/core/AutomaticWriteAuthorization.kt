package com.rustedwax.core

data class AutomaticWriteAuthorization(
	val generation: Long,
	val enabledAtStart: Boolean,
) {
	companion object {
		/** Compatibility value for old fixtures; production producers stamp explicitly. */
		val LegacyEnabled = AutomaticWriteAuthorization(
			generation = 0,
			enabledAtStart = true,
		)
	}
}
