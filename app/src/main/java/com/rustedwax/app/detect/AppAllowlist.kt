package com.rustedwax.app.detect

import com.rustedwax.app.storage.Settings

/**
 * Which apps RustedWax scrobbles from — `<redacted-private-path>` §5.1.
 *
 * ## What this replaces
 *
 * `Settings.nativeYouTube` and `Settings.nativeYouTubeMusic`: two hardcoded
 * booleans, one per package. That shape does not survive a third source, and
 * every source added under it costs a setting, a UI row, a migration and a
 * branch. A set costs none of those, and it can hold a package this build has
 * never heard of.
 *
 * ## The conflict rule
 *
 * **The allowlist wins.** Taken verbatim from Pano (`bSet.removeAll(aSet)`)
 * because it prevents a class of bug rather than merely choosing a winner: a
 * package that ends up in both sets — through a migration, an import, or a
 * race between two screens — resolves to *enabled*, deterministically, and the
 * contradiction is repaired on write instead of being re-decided on every read.
 * The alternative fails in the direction where a user's explicit yes silently
 * stops working.
 *
 * ## Why a migration exists at all
 *
 * Someone has already made these choices. Reading the old booleans once and
 * folding them into the new sets means nobody re-enables anything after an
 * update — and the marker is one-way, so a later "block YouTube" cannot be
 * undone by the migration running a second time.
 */
object AppAllowlist {

	/**
	 * Fold the pre-§5.1 per-package booleans into the sets, once.
	 *
	 * Runs before any read. A package the user had switched **off** is recorded
	 * as blocked rather than merely absent, because "I turned that off" and "I
	 * have never been asked" are different answers and only one of them should
	 * produce a detection prompt later.
	 */
	@Synchronized
	fun migrateIfNeeded(settings: Settings) {
		if (settings.appListMigrated) return
		val allowed = settings.allowedPackages.toMutableSet()
		val blocked = settings.blockedPackages.toMutableSet()
		listOf(
			YouTubeProbe.YOUTUBE_PACKAGE to settings.nativeYouTube,
			YouTubeProbe.YOUTUBE_MUSIC_PACKAGE to settings.nativeYouTubeMusic,
		).forEach { (pkg, wasEnabled) ->
			if (wasEnabled) allowed += pkg else blocked += pkg
		}
		settings.allowedPackages = allowed
		settings.blockedPackages = blocked - allowed
		settings.appListMigrated = true
		EventLog.append(
			"apps",
			"migrated the per-app switches into the app list: " +
				"${allowed.size} allowed, ${(blocked - allowed).size} blocked",
		)
	}

	/** Whether this package may be scrobbled from. */
	fun isAllowed(settings: Settings, packageName: String): Boolean {
		migrateIfNeeded(settings)
		return packageName in settings.allowedPackages
	}

	/** Whether the user has already decided about this package, either way. */
	fun hasDecision(settings: Settings, packageName: String): Boolean {
		migrateIfNeeded(settings)
		return packageName in settings.allowedPackages ||
			packageName in settings.blockedPackages
	}

	/**
	 * Record a decision, repairing any contradiction in the same write.
	 *
	 * Both sets are rewritten together so they cannot disagree between two
	 * partial writes — the state the conflict rule exists to resolve should be
	 * unreachable, not merely handled.
	 */
	@Synchronized
	fun setAllowed(settings: Settings, packageName: String, allowed: Boolean) {
		migrateIfNeeded(settings)
		val allowSet = settings.allowedPackages.toMutableSet()
		val blockSet = settings.blockedPackages.toMutableSet()
		if (allowed) {
			allowSet += packageName
			blockSet -= packageName
		} else {
			allowSet -= packageName
			blockSet += packageName
		}
		settings.allowedPackages = allowSet
		// The allowlist wins: anything in both leaves the block set.
		settings.blockedPackages = blockSet - allowSet
		EventLog.append(
			"apps",
			"$packageName ${if (allowed) "allowed" else "blocked"} by the user",
		)
	}

	/**
	 * Note that a package published a MediaSession, and whether it is new.
	 *
	 * Returns true exactly once per package: when it has never been seen and the
	 * user has never decided about it. That is the moment worth a prompt, and
	 * asking a second time about an app someone already refused is how a
	 * detection feature becomes something people turn off.
	 *
	 * The label is stored locally so the app list can show "Deezer" rather than
	 * `com.deezer.android.app`. It is deliberately never written to the event
	 * log — §4.1 removed package names from it, and a *label* is no better.
	 */
	@Synchronized
	fun noteSeen(settings: Settings, packageName: String, label: String): Boolean {
		migrateIfNeeded(settings)
		val seen = settings.seenApps
		val known = packageName in seen
		if (!known || seen[packageName] != label) {
			settings.seenApps = seen + (packageName to label)
		}
		if (!settings.autoDetectApps) return false
		return !known && !hasDecision(settings, packageName)
	}
}
