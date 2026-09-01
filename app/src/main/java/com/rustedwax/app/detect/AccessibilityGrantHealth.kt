package com.rustedwax.app.detect

enum class GrantHealth {
	/** Granted and receiving events. */
	LIVE,

	/** Never granted on this install. Asking the user to enable it is correct. */
	NEVER_GRANTED,

	/**
	 * Was live, is not live now, and has not been gone long enough to be
	 * distinguished from the post-install window. Say nothing yet.
	 */
	SETTLING,

	/**
	 * Granted at some point and not enabled now, for longer than any install
	 * settles. Either the user revoked it or the service crashed and Android
	 * disabled it; the app cannot tell which, so it must not claim the user
	 * did it.
	 */
	DROPPED,
}

object AccessibilityGrantHealth {

	/**
	 * A grant must be missing for this long before it is called a drop. Well
	 * clear of the few seconds §2.3 measured, and still fast enough that a real
	 * crash is reported while the owner is still looking at the screen.
	 */
	const val SETTLE_MS = 15_000L

	fun classify(
		live: Boolean,
		everGranted: Boolean,
		notLiveForMillis: Long = Long.MAX_VALUE,
		settleMillis: Long = SETTLE_MS,
	): GrantHealth = when {
		live -> GrantHealth.LIVE
		!everGranted -> GrantHealth.NEVER_GRANTED
		notLiveForMillis < settleMillis -> GrantHealth.SETTLING
		else -> GrantHealth.DROPPED
	}
}
