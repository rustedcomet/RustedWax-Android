package com.rustedwax.core

/**
 * Immutable authority carried by one logical automatic-write target.
 *
 * [generation] identifies the exact continuous Automatic Scrobbling opt-in
 * interval in which the target began. [enabledAtStart] makes a target first
 * observed while the switch was off permanently ineligible, even if a later
 * opt-in happens to have the same process lifetime.
 */
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
