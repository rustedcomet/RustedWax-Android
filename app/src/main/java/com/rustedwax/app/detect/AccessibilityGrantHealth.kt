package com.rustedwax.app.detect

/**
 * Distinguishes an accessibility grant that was never given from one that was
 * given and has since gone away.
 *
 * ## Why this exists
 *
 * Android removes a crashed accessibility service from
 * `enabled_accessibility_services`. From the app's side that is byte-identical
 * to the user switching it off, so a single boolean cannot tell the two apart —
 * and the honest message for each is completely different.
 *
 * On 2026-08-05 the browser watcher was dropped exactly that way. It stayed off
 * for most of a day, the Settings row said "Off" as though that were a choice,
 * and roughly half that day's YouTube watch history never reached the app.
 * `dumpsys accessibility` listed it under `crashed services`, but nothing the
 * owner could see did.
 *
 * The evidence is therefore "was it ever live", which only a live service can
 * establish and which no crash can retract.
 *
 * ## Why a settle window
 *
 * `<redacted-private-path>` §2.3 records that `accessibility_enabled` reads `0`
 * for a few seconds after an APK install and then returns to `1` on its own,
 * with the services reconnecting. Measured again on 2026-08-05 immediately after
 * `adb install -r`: the flag read `0` while both services were still named in
 * the list, and had settled to `1` seconds later.
 *
 * Without a dwell time this class would therefore shout "this crashed" on every
 * install — the mirror image of the bug it exists to catch, and the fastest way
 * to teach the owner to ignore the one warning that matters. [SETTLE_MS] is the
 * grace before a missing grant is called a drop.
 */
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

	/**
	 * @param live whether the grant is enabled *and* the accessibility master
	 * switch is on, per the service's own `isEnabled`.
	 * @param everGranted whether [live] has ever been observed true and
	 * remembered across restarts.
	 * @param notLiveForMillis how long [live] has been continuously false;
	 * ignored when [live] is true.
	 */
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
