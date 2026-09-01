package com.rustedwax.app.storage

/**
 * Keeps a changed default from rewriting a choice somebody already made.
 *
 * v0.11.0 changed three fresh-install defaults — `Event log` on to off,
 * `Short clips` on to off, `Disable Shorts` off to on — and replaced four
 * YouTube switches with one. v0.11.1 then removes `Short clips` outright. Every one of those is stored as "absent means the
 * default", so shipping the new defaults alone would silently flip all three
 * for anyone upgrading: their log would stop, their Shorts floor would rise,
 * and their Shorts would stop scrobbling, none of which they asked for.
 *
 * So the upgrade path writes the **old** defaults down explicitly. After that
 * "absent" only ever happens on a fresh install, and the new defaults mean what
 * they say. This runs once, from [Settings]'s constructor, before any read.
 *
 * ## How the four YouTube switches become one
 *
 * `Native YouTube` and `Native YouTube Music` were opt-in and off by default;
 * `Look videos up` and `Count picture in picture` were on. Browser YouTube was
 * never a setting at all — [com.rustedwax.app.detect.YouTubeProbe] accepted the
 * browser packages unconditionally — so **every** existing install was already
 * scrobbling YouTube from at least one surface, and there is no such thing as a
 * user who had YouTube off.
 *
 * The unified switch therefore migrates to **on** for everyone, and the two
 * halves of that decision are worth stating separately:
 *
 *  - Nobody loses a source. Turning it off for an install whose native switches
 *    happened to be off would also silence browser YouTube, which is the one
 *    surface that install definitely was using. That is the failure this
 *    resolves against: a silent stop is worse than a visible start.
 *  - Somebody may gain one. An install that had explicitly refused native
 *    YouTube or YouTube Music now accepts them, because the unified switch has
 *    no position that means "browser but not native". That is a real expansion
 *    and it is recorded here rather than left for someone to discover: it is
 *    listed in the release notes, it is one tap to undo, and nothing is
 *    broadcast by it on its own — `autoScrobble` is a separate opt-in, still
 *    off by default, and still requires a saved posting key.
 *
 * The old per-package keys are left in place. [com.rustedwax.app.detect.AppAllowlist]
 * reads them for its own one-way migration, and deleting a key it may not have
 * folded in yet would lose an answer rather than tidy one away.
 */
internal object SettingsMigration {

	/** Bump when a stored default changes again. */
	const val CURRENT_VERSION = 2

	private const val KEY_VERSION = "settingsSchemaVersion"

	/**
	 * Run every step the stored version has not seen, in order.
	 *
	 * Stepwise rather than one block guarded by a single comparison: an install
	 * already at v1 has had its old defaults pinned and must not have them
	 * pinned again over answers it has given since, while an install at v0 needs
	 * both steps in one pass. The old arrangement could only express "everything
	 * or nothing", which was correct while there was one step.
	 */
	@Synchronized
	fun ensure(store: SettingsStore) {
		val from = store.getInt(KEY_VERSION, 0)
		if (from >= CURRENT_VERSION) return
		if (from < 1) toV1(store)
		if (from < 2) toV2(store)
		store.putInt(KEY_VERSION, CURRENT_VERSION)
	}

	/** v0.11.0: three changed defaults and four YouTube switches becoming one. */
	private fun toV1(store: SettingsStore) {
		// Empty means nothing has ever been stored, which can only be a fresh
		// install: this runs from the constructor, so it is the first thing to
		// touch the file. A fresh install wants the new defaults, and the
		// cheapest way to give it them is to write nothing at all.
		if (!store.isEmpty()) {
			pin(store, Settings.KEY_EVENT_LOGGING, wasDefault = true)
			pin(store, Settings.KEY_DISABLE_SHORTS, wasDefault = false)
			pin(store, Settings.KEY_YOUTUBE_SCROBBLING, wasDefault = true)
			// `Short clips` was pinned here too until v2 deleted the setting.
			// Writing a key in one step so the next step can remove it would be
			// ceremony: both steps always run in the same pass, and the observable
			// result — no stored short-clip policy — is identical.
		}

		// Nothing reads these any more and both were on by default, so leaving
		// them would be a stored value that contradicts the switch now in
		// charge. The diagnostic deadline goes with the feature it belonged to.
		store.remove(Settings.KEY_LEGACY_ENRICH)
		store.remove(Settings.KEY_LEGACY_PIP_INFERENCE)
		store.remove(Settings.KEY_LEGACY_DIAGNOSTIC_UNTIL)
	}

	/**
	 * v0.11.1: `Short clips` stops being a setting.
	 *
	 * Whether a verified Short may use the 10-second floor is now decided by the
	 * evidence — resolved, and publicly listed — rather than by a switch, so the
	 * stored answer has nothing left to govern and is deleted rather than left
	 * behind to be read by something later.
	 *
	 * **`Disable Shorts` is deliberately untouched.** That is the switch people
	 * actually hold an opinion about, and an install that chose to let Shorts
	 * scrobble must not have them silently switched off by a migration that was
	 * only removing the other one.
	 */
	private fun toV2(store: SettingsStore) {
		store.remove(Settings.KEY_LEGACY_SHORT_CLIPS)
	}

	/** Write the pre-v0.11.0 default, but only where the user never answered. */
	private fun pin(store: SettingsStore, key: String, wasDefault: Boolean) {
		if (!store.contains(key)) store.putBoolean(key, wasDefault)
	}
}
