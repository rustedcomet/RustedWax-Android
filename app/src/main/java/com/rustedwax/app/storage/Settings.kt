package com.rustedwax.app.storage

import com.rustedwax.core.*
import android.content.Context
import android.content.SharedPreferences
import com.rustedwax.hive.HiveBroadcaster
import com.rustedwax.hive.PrivateScrobble
import com.rustedwax.core.ListenPolicyDefaults

internal interface SettingsStore {
	fun getBoolean(key: String, default: Boolean): Boolean
	fun putBoolean(key: String, value: Boolean)
	fun getInt(key: String, default: Int): Int
	fun putInt(key: String, value: Int)
	fun getString(key: String, default: String): String
	fun putString(key: String, value: String)

	/** Whether a value has ever been written for this key. */
	fun contains(key: String): Boolean

	/** True only before anything has ever been stored — i.e. a fresh install. */
	fun isEmpty(): Boolean

	fun remove(key: String)
}

private class SharedPreferencesSettingsStore(
	private val prefs: SharedPreferences,
) : SettingsStore {
	override fun getBoolean(key: String, default: Boolean): Boolean =
		prefs.getBoolean(key, default)

	override fun putBoolean(key: String, value: Boolean) {
		prefs.edit().putBoolean(key, value).apply()
	}

	override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

	override fun putInt(key: String, value: Int) {
		prefs.edit().putInt(key, value).apply()
	}

	override fun getString(key: String, default: String): String =
		prefs.getString(key, default) ?: default

	override fun putString(key: String, value: String) {
		prefs.edit().putString(key, value).apply()
	}

	override fun contains(key: String): Boolean = prefs.contains(key)

	override fun isEmpty(): Boolean = prefs.all.isEmpty()

	override fun remove(key: String) {
		prefs.edit().remove(key).apply()
	}
}

/**
 * User preferences. Key names match the extension's `options.ts` where the
 * setting is the same one, so a future import/export can map across directly.
 */
class Settings internal constructor(
	private val store: SettingsStore,
) {
	constructor(context: Context) : this(
		SharedPreferencesSettingsStore(
			context.getSharedPreferences("rustedwax_settings", Context.MODE_PRIVATE),
		),
	)

	init {
		// Before the first read, always. Three defaults changed in v0.11.0 and
		// an upgrading install must keep the old ones; the only way to be sure
		// nothing reads a new default first is to run the migration in the one
		// place every reader has to go through.
		SettingsMigration.ensure(store)
	}

	var monitoringEnabled: Boolean
		get() = store.getBoolean(KEY_MONITORING, true)
		set(value) = store.putBoolean(KEY_MONITORING, value)

	/**
	 * The one YouTube switch — every surface, one answer.
	 *
	 * It used to be four: `Native YouTube`, `Native YouTube Music`,
	 * `Look videos up` and `Count picture in picture`. None of those is a policy
	 * a person holds. They are the app's own implementation showing through:
	 * which detector runs, whether identity needs a fetch to be provable,
	 * whether PiP needs a different measurement path. The question someone
	 * actually has an opinion about is "do you scrobble my YouTube", and that is
	 * now the only one asked.
	 *
	 * Nothing underneath was removed. [enrichment] and [pipInference] read this
	 * value, the two native packages are allowed or blocked with it, and browser
	 * YouTube is accepted only while it is on — so all five mechanisms are still
	 * there, wired to one user-facing answer instead of four.
	 *
	 * On by default, and that is not the same as broadcasting: [autoScrobble] is
	 * a separate opt-in and still starts off, so a fresh install watches and
	 * publishes nothing until a key is added and the user says so.
	 */
	var youTubeScrobbling: Boolean
		get() = store.getBoolean(KEY_YOUTUBE_SCROBBLING, true)
		set(value) = store.putBoolean(KEY_YOUTUBE_SCROBBLING, value)

	/**
	 * Whether to look a video up on youtube.com to improve its metadata.
	 *
	 * Given an id, this fetches richer metadata. When the address bar did not
	 * produce one, the same switch also permits playlist/search/watch-page id
	 * recovery.
	 *
	 * No longer its own switch: an id that cannot be proven cannot be scrobbled,
	 * so "look videos up" was never a preference so much as a precondition of
	 * the thing above it being able to work at all. It follows
	 * [youTubeScrobbling], which is the answer it was always really asking for.
	 * Every call site is unchanged.
	 */
	val enrichment: Boolean
		get() = youTubeScrobbling

	/** Master switch for automatic scrobbling. Off until the user opts in. */
	var autoScrobble: Boolean
		get() = store.getBoolean(KEY_AUTO, false)
		set(value) = store.putBoolean(KEY_AUTO, value)

	/**
	 * Whether the Android 13 notification prompt has been shown once.
	 *
	 * Not a record of the *answer* — Android owns that, and asking it is a
	 * permission check rather than a stored flag. This exists only so the prompt
	 * is raised once, when there is first an account that could receive a reply,
	 * rather than on every sign-in. A user who declined and later changed their
	 * mind goes to the system settings for this app, which is where Android puts
	 * that decision after the second refusal anyway.
	 */
	var notificationsAsked: Boolean
		get() = store.getBoolean(KEY_NOTIFICATIONS_ASKED, false)
		set(value) = store.putBoolean(KEY_NOTIFICATIONS_ASKED, value)

	/**
	 * The pre-v0.11.0 per-package opt-in, read once by
	 * [com.rustedwax.app.detect.AppAllowlist.migrateIfNeeded] and never again.
	 *
	 * Kept only so an install that predates the app list still folds its answer
	 * in. Nothing writes it any more — [youTubeScrobbling] is what decides
	 * whether these packages are allowed now.
	 */
	internal val nativeYouTube: Boolean
		get() = store.getBoolean(KEY_NATIVE_YOUTUBE, false)

	/** As [nativeYouTube], for YouTube Music. */
	internal val nativeYouTubeMusic: Boolean
		get() = store.getBoolean(KEY_NATIVE_YOUTUBE_MUSIC, false)

	/**
	 * Whether the signed-in account's watch history may be read to identify a
	 * native track.
	 *
	 * Off until the user signs in, and independently revocable afterwards
	 * without wiping the session — the switch answers "use it", the session
	 * answers "have it". Requires [enrichment] like every other lookup, and
	 * applies only to native YouTube sessions: browsers have the address bar,
	 * and their behaviour is not allowed to change.
	 */
	var watchHistory: Boolean
		get() = store.getBoolean(KEY_WATCH_HISTORY, false)
		set(value) = store.putBoolean(KEY_WATCH_HISTORY, value)

	var browserEvidenceEverGranted: Boolean
		get() = store.getBoolean(KEY_BROWSER_EVER_GRANTED, false)
		set(value) = store.putBoolean(KEY_BROWSER_EVER_GRANTED, value)

	/** As [browserEvidenceEverGranted], for the native Shorts observer. */
	var nativeShortsEverGranted: Boolean
		get() = store.getBoolean(KEY_SHORTS_EVER_GRANTED, false)
		set(value) = store.putBoolean(KEY_SHORTS_EVER_GRANTED, value)

	/**
	 * Whether a Short that continues in picture-in-picture may be credited with
	 * inferred wall-clock time.
	 *
	 * PiP publishes no progress of any kind, so this is the difference between
	 * a PiP session counting for something and counting for nothing. It is
	 * inference, not measurement — see `PipPlaybackInference` — so it is its own
	 * switch, it never applies while a readable seekbar exists, and it needs
	 * Usage Access before it can attribute anything to YouTube at all.
	 *
	 * Not its own switch since v0.11.0. PiP is a measurement path, not a policy —
	 * "count what I watched in a floating window" is the same answer as "scrobble
	 * my YouTube", so it follows [youTubeScrobbling] and needs no second
	 * question. Usage Access is still required before anything can be attributed
	 * to YouTube at all, and without it this still credits nothing; that is a
	 * grant, which the UI asks for separately because Android does.
	 */
	val pipInference: Boolean
		get() = youTubeScrobbling

	/**
	 * Whether the event log is written at all — §4.2.
	 *
	 * **Off** on a fresh install as of v0.11.0. The log is a debugging artifact
	 * that records what someone watched, and starting to write one before anyone
	 * asked is the wrong default for a recording device — the earlier reasoning
	 * ("this app is a measurement instrument") described the developer's install,
	 * not everyone's. An existing user's answer is preserved; see
	 * [SettingsMigration].
	 *
	 * Off writes nothing anywhere: see
	 * [com.rustedwax.app.detect.EventLog.applyPolicy], which also erases what is
	 * already on disk, since "nothing is being recorded" has to be true about the
	 * past as well as the future.
	 *
	 * The single control for logging. The temporary 15-day *Diagnostic logging*
	 * mode that used to sit beside it is gone: it answered the same question in a
	 * second place, and two switches over one file is how someone ends up certain
	 * they turned logging off while a log keeps being written.
	 */
	var eventLogging: Boolean
		get() = store.getBoolean(KEY_EVENT_LOGGING, false)
		set(value) = store.putBoolean(KEY_EVENT_LOGGING, value)

	/**
	 * Never scrobble anything from a proven `/shorts/` path — §4.3.
	 *
	 * The only Shorts switch, as of v0.11.1. It used to have a neighbour called
	 * `Short clips` that decided whether a verified Short could use the lowered
	 * 10-second floor, and the two were routinely confused — turning the wrong
	 * one off left someone certain they had disabled Shorts while Shorts kept
	 * landing on an unerasable ledger. The floor is now a property of the
	 * evidence rather than a preference, so there is one switch and it answers
	 * the question people actually hold an opinion about.
	 *
	 * **On** on a fresh install as of v0.11.0: a Short is a few seconds of a feed
	 * someone scrolled past, and filling an unerasable ledger with them by
	 * default is a decision that should be made deliberately. Turning it off
	 * restores the old behaviour and takes one tap. An existing user's answer is
	 * preserved; see [SettingsMigration].
	 */
	var disableShorts: Boolean
		get() = store.getBoolean(KEY_DISABLE_SHORTS, true)
		set(value) = store.putBoolean(KEY_DISABLE_SHORTS, value)

	/**
	 * Whether the hidden developer tier is shown at all.
	 *
	 * Unlocked by seven taps on the version in **Settings › About**, which is
	 * the whole point: the tier holds the event log — a plain-text recording of
	 * what somebody watched — the Shorts rule, the foreground-Shorts grant and a
	 * connection check. None of those is a question an ordinary install has, and
	 * a settings screen that asks them anyway is a settings screen people stop
	 * reading.
	 *
	 * Persisted rather than held in memory: someone who unlocks it, opens a
	 * Short to reproduce something and comes back would otherwise find it locked
	 * again. Off on a fresh install, and turning it off is one tap in the tier
	 * itself.
	 *
	 * It gates **display only**. Every setting behind it keeps whatever value it
	 * had — the event log does not start or stop, and `Disable Shorts` still
	 * decides what it always decided — so locking the tier can never silently
	 * change what the app records or scrobbles.
	 */
	var developerMode: Boolean
		get() = store.getBoolean(KEY_DEVELOPER_MODE, false)
		set(value) = store.putBoolean(KEY_DEVELOPER_MODE, value)

	/**
	 * Packages the user has said yes to — §5.1.
	 *
	 * `nativeYouTube` and `nativeYouTubeMusic` were hardcoded switches, which
	 * does not survive a third source let alone a tenth. A set does, and it also
	 * lets a source the app has never heard of be enabled without a release.
	 */
	var allowedPackages: Set<String>
		get() = decodeSet(store.getString(KEY_ALLOWED_PACKAGES, ""))
		set(value) = store.putString(KEY_ALLOWED_PACKAGES, encodeSet(value))

	/**
	 * Packages the user has said no to.
	 *
	 * Kept separately from "not allowed" so a decision can be distinguished from
	 * an absence of one: the detection prompt must not ask again about an app
	 * that was already refused.
	 */
	var blockedPackages: Set<String>
		get() = decodeSet(store.getString(KEY_BLOCKED_PACKAGES, ""))
		set(value) = store.putString(KEY_BLOCKED_PACKAGES, encodeSet(value))

	/**
	 * Every package seen publishing a MediaSession, package → human label.
	 *
	 * This is what makes the app list configurable without the user typing a
	 * package name. It is a local record of installed apps that play media, so
	 * it never leaves the device and is never written to the event log.
	 */
	var seenApps: Map<String, String>
		get() = decodeSet(store.getString(KEY_SEEN_APPS, "")).mapNotNull { entry ->
			val index = entry.indexOf('=')
			if (index <= 0) null else entry.take(index) to entry.substring(index + 1)
		}.toMap()
		set(value) = store.putString(
			KEY_SEEN_APPS,
			encodeSet(value.map { (pkg, label) -> "$pkg=$label" }.toSet()),
		)

	/** Whether an unrecognised source should prompt rather than be ignored. */
	var autoDetectApps: Boolean
		get() = store.getBoolean(KEY_AUTO_DETECT_APPS, true)
		set(value) = store.putBoolean(KEY_AUTO_DETECT_APPS, value)

	/** One-way marker so the §5.1 migration runs once and never re-runs. */
	var appListMigrated: Boolean
		get() = store.getBoolean(KEY_APP_LIST_MIGRATED, false)
		set(value) = store.putBoolean(KEY_APP_LIST_MIGRATED, value)

	/**
	 * Per-kind privacy toggles — §6.1.
	 *
	 * Four, not one, and that split is the whole point: someone may be happy for
	 * their music to be public while their viewing is not. Key names match the
	 * extension's `options.ts` so a future import/export maps straight across.
	 *
	 * All off by default. Turning privacy *on* by default would silently change
	 * what an existing user's history looks like to every indexer reading it.
	 */
	var privacyMusic: Boolean
		get() = store.getBoolean(KEY_PRIVACY_MUSIC, false)
		set(value) = store.putBoolean(KEY_PRIVACY_MUSIC, value)

	var privacyVideos: Boolean
		get() = store.getBoolean(KEY_PRIVACY_VIDEOS, false)
		set(value) = store.putBoolean(KEY_PRIVACY_VIDEOS, value)

	var privacyMoviesTv: Boolean
		get() = store.getBoolean(KEY_PRIVACY_MOVIES_TV, false)
		set(value) = store.putBoolean(KEY_PRIVACY_MOVIES_TV, value)

	var privacyPodcasts: Boolean
		get() = store.getBoolean(KEY_PRIVACY_PODCASTS, false)
		set(value) = store.putBoolean(KEY_PRIVACY_PODCASTS, value)

	/** Whether this kind's listens must be encrypted before broadcast. */
	fun privacyEnabledFor(category: PrivateScrobble.Category): Boolean = when (category) {
		PrivateScrobble.Category.MUSIC -> privacyMusic
		PrivateScrobble.Category.VIDEOS -> privacyVideos
		PrivateScrobble.Category.MOVIES_TV -> privacyMoviesTv
		PrivateScrobble.Category.PODCASTS -> privacyPodcasts
	}

	val anyPrivacyEnabled: Boolean
		get() = privacyMusic || privacyVideos || privacyMoviesTv || privacyPodcasts

	/**
	 * Light, dark, or whatever the phone is doing.
	 *
	 * Stored as the enum name rather than an ordinal so reordering the enum
	 * can't silently repaint someone's app. An unrecognised value — a
	 * downgrade, a hand-edited preference — falls back to following the system
	 * rather than refusing to start.
	 */
	var themeChoice: String
		get() = store.getString(KEY_THEME, THEME_SYSTEM)
		set(value) = store.putString(KEY_THEME, value)

	/** `scrobblePercent` upstream — stored as a percentage, used as a fraction. */
	var scrobbleThreshold: Double
		get() = store.getInt(KEY_PERCENT, 60).coerceIn(20, 95) / 100.0
		set(value) = store.putInt(KEY_PERCENT, (value * 100).toInt().coerceIn(20, 95))

	val thresholdPercent: Int get() = (scrobbleThreshold * 100).toInt()

	/**
	 * How hard a Like votes, as a whole percentage.
	 *
	 * Clamped on **both** sides, to the same bounds
	 * [com.rustedwax.hive.HiveBroadcaster] enforces again at signing time. The
	 * duplication is deliberate: this value outlives the slider that wrote it,
	 * so a hand-edited preference file, a value from an older build or an
	 * off-by-one at the slider's end must not become a 0% vote — which Hive
	 * reads as *removing* a vote — or a vote stronger than the user ever chose.
	 *
	 * Stored as a whole percent and nothing finer. The slider moves
	 * continuously and rounds before it writes, so the stored value is always
	 * something the settings screen can show back exactly; there is deliberately
	 * no coarse 5% or 10% step, because a Like strength is a number the user
	 * picked rather than a bucket the app offers.
	 *
	 * Read late, at the moment a Like is cast, so changing it only ever affects
	 * future Likes — an existing vote on chain is never revisited.
	 */
	var likePercent: Int
		get() = store.getInt(KEY_LIKE_PERCENT, DEFAULT_LIKE_PERCENT)
			.coerceIn(HiveBroadcaster.MIN_LIKE_PERCENT, HiveBroadcaster.MAX_LIKE_PERCENT)
		set(value) = store.putInt(
			KEY_LIKE_PERCENT,
			value.coerceIn(HiveBroadcaster.MIN_LIKE_PERCENT, HiveBroadcaster.MAX_LIKE_PERCENT),
		)

	/**
	 * Newline-delimited, because a package name cannot contain one and a human
	 * label very well might contain a comma.
	 */
	private fun encodeSet(value: Set<String>): String =
		value.filter(String::isNotBlank).joinToString("\n")

	private fun decodeSet(value: String): Set<String> =
		value.split('\n').filter(String::isNotBlank).toSet()

	internal companion object {
		const val KEY_MONITORING = "monitoringEnabled"
		const val KEY_AUTO = "autoScrobble"
		const val KEY_YOUTUBE_SCROBBLING = "youtubeScrobbling"
		const val KEY_NATIVE_YOUTUBE = "nativeYouTube"
		const val KEY_NATIVE_YOUTUBE_MUSIC = "nativeYouTubeMusic"
		const val KEY_WATCH_HISTORY = "watchHistoryLookup"
		const val KEY_BROWSER_EVER_GRANTED = "browserEvidenceEverGranted"
		const val KEY_SHORTS_EVER_GRANTED = "nativeShortsEverGranted"
		const val KEY_EVENT_LOGGING = "eventLogging"
		const val KEY_DISABLE_SHORTS = "disableShorts"
		const val KEY_DEVELOPER_MODE = "developerMode"
		const val KEY_ALLOWED_PACKAGES = "allowedPackages"
		const val KEY_BLOCKED_PACKAGES = "blockedPackages"
		const val KEY_SEEN_APPS = "seenApps"
		const val KEY_AUTO_DETECT_APPS = "autoDetectApps"
		const val KEY_APP_LIST_MIGRATED = "appListMigrated"

		/** Written by v0.10.0 and earlier; deleted by [SettingsMigration]. */
		const val KEY_LEGACY_ENRICH = "enrichment"

		/**
		 * The `Short clips` opt-in, written up to v0.11.0 and deleted by
		 * [SettingsMigration]'s v2 step.
		 *
		 * It only ever decided whether a **verified public** Short could use the
		 * 10-second floor, and it sat one row away from `Disable Shorts`, which
		 * decides whether Shorts count at all. Two Shorts switches with almost
		 * the same name is how somebody ends up certain they stopped Shorts while
		 * Shorts keep landing on a ledger nothing can edit. The floor is no
		 * longer a preference: a Short is proven publicly listed and gets the
		 * lowered floor, or it is refused. The rule that decides that lives in
		 * the scrobble package, which storage does not name.
		 */
		const val KEY_LEGACY_SHORT_CLIPS = "shortClipScrobbling"
		const val KEY_LEGACY_PIP_INFERENCE = "pipInference"
		const val KEY_LEGACY_DIAGNOSTIC_UNTIL = "diagnosticLoggingUntil"

		/** Same keys the extension uses, for a future import/export. */
		const val KEY_PRIVACY_MUSIC = "hivePrivacyMusic"
		const val KEY_PRIVACY_VIDEOS = "hivePrivacyVideos"
		const val KEY_PRIVACY_MOVIES_TV = "hivePrivacyMoviesTv"
		const val KEY_PRIVACY_PODCASTS = "hivePrivacyPodcasts"
		const val KEY_THEME = "themeChoice"

		/** Matches `ThemeChoice.SYSTEM.name`; see [themeChoice]. */
		const val THEME_SYSTEM = "SYSTEM"

		/** Same key the extension uses. */
		const val KEY_PERCENT = "scrobblePercent"

		/**
		 * Default Like strength. A new key, so no [SettingsMigration] entry is
		 * needed: that mechanism exists to stop a *changed* default rewriting a
		 * choice somebody already made, and nobody has made this one yet.
		 */
		const val KEY_LIKE_PERCENT = "likeStrengthPercent"

		/**
		 * A new key with a `false` default, so no [SettingsMigration] entry is
		 * needed: nothing has ever written it and no existing choice can be
		 * overwritten by it.
		 */
		const val KEY_NOTIFICATIONS_ASKED = "notificationsAsked"

		/** The gentlest Like the slider offers, and what a fresh install votes at. */
		const val DEFAULT_LIKE_PERCENT = 10

		@Suppress("unused")
		val DEFAULT = ListenPolicyDefaults.THRESHOLD
	}
}
