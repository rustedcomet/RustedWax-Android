package com.rustedwax.app.detect

import android.content.Context
import com.rustedwax.app.storage.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which YouTube surfaces are live, and the boundaries around turning one off.
 *
 * One user-facing answer — `YouTube Scrobbling` — over the three package-level
 * opt-ins it drives: browser YouTube, native YouTube, native YouTube Music. The
 * per-package layer is kept because it does something the master switch cannot:
 * each native package carries an **epoch**, and bumping it makes an opt-out a
 * hard boundary even for finalization work already running on the engine's IO
 * dispatcher. Three field rounds hardened those boundaries; unifying the UI is
 * not a reason to give them up.
 *
 * Browser packages still do not carry epochs and retain their v0.8.15 behavior;
 * an in-flight browser track is discarded by the probe's own package check when
 * the master goes off.
 */
object NativeSourceSwitches {

	data class Config(
		/**
		 * The master. Defaults on for the same reason [MonitorSwitch] does: this
		 * value is read before [init] has loaded the stored one, and every entry
		 * point calls [init] before a probe exists.
		 */
		val youTubeScrobbling: Boolean = true,
		val youtubeEnabled: Boolean = false,
		val youtubeMusicEnabled: Boolean = false,
		val youtubeEpoch: Long = 1,
		val youtubeMusicEpoch: Long = 1,
	)

	private var settings: Settings? = null
	private val _config = MutableStateFlow(Config())
	val config: StateFlow<Config> = _config.asStateFlow()

	/**
	 * Reads through [AppAllowlist] rather than the old per-package booleans.
	 *
	 * §5.1 changed where the *answer* comes from; v0.11.0 changes who gives it.
	 * The allowlist is still the record of which packages are permitted — it can
	 * hold a source this build has never heard of — but for the two YouTube
	 * packages the master switch is what writes it, so the stored list is
	 * reconciled to the switch here rather than being able to drift from it.
	 */
	@Synchronized
	fun init(context: Context) {
		if (settings != null) return
		settings = Settings(context.applicationContext).also { stored ->
			AppAllowlist.migrateIfNeeded(stored)
			val master = stored.youTubeScrobbling
			if (master != AppAllowlist.isAllowed(stored, YouTubeProbe.YOUTUBE_PACKAGE) ||
				master != AppAllowlist.isAllowed(stored, YouTubeProbe.YOUTUBE_MUSIC_PACKAGE)
			) {
				writeAllowlist(stored, master)
			}
			_config.value = _config.value.copy(
				youTubeScrobbling = master,
				youtubeEnabled = master,
				youtubeMusicEnabled = master,
			)
		}
	}

	/**
	 * The one switch, applied to every YouTube surface at once.
	 *
	 * Both native epochs are bumped on the way through, so anything already
	 * being finalized for either package is invalidated rather than allowed to
	 * land after the user said stop. Turning the switch off must not be a way to
	 * *cause* a broadcast.
	 */
	@Synchronized
	fun setYouTubeScrobbling(context: Context, enabled: Boolean) {
		init(context)
		val current = _config.value
		if (current.youTubeScrobbling == enabled) return
		settings?.let { stored ->
			stored.youTubeScrobbling = enabled
			writeAllowlist(stored, enabled)
		}
		var next = toggled(current, YouTubeProbe.YOUTUBE_PACKAGE, enabled)
		next = toggled(next, YouTubeProbe.YOUTUBE_MUSIC_PACKAGE, enabled)
		_config.value = next.copy(youTubeScrobbling = enabled)
		EventLog.append(
			"youtube",
			if (enabled) {
				"YouTube scrobbling on — browser, native YouTube and native YouTube " +
					"Music are watched, lookups may run, and picture-in-picture time " +
					"counts where Usage Access allows it"
			} else {
				"YouTube scrobbling off — every YouTube surface is ignored, in-flight " +
					"tracks are discarded rather than finalized, and no lookup runs"
			},
		)
	}

	private fun writeAllowlist(stored: Settings, enabled: Boolean) {
		AppAllowlist.setAllowed(stored, YouTubeProbe.YOUTUBE_PACKAGE, enabled)
		AppAllowlist.setAllowed(stored, YouTubeProbe.YOUTUBE_MUSIC_PACKAGE, enabled)
	}

	/** Invalidate in-flight native snapshots without changing either opt-in. */
	@Synchronized
	fun invalidateAll(reason: String) {
		val current = _config.value
		_config.value = invalidated(current)
		EventLog.append("native", "native source state cleared: $reason")
	}

	/** Invalidate one native package without disturbing the other opt-in/source. */
	@Synchronized
	fun invalidatePackage(packageName: String, reason: String) {
		val current = _config.value
		val next = when (packageName) {
			YouTubeProbe.YOUTUBE_PACKAGE -> current.copy(youtubeEpoch = current.youtubeEpoch + 1)
			YouTubeProbe.YOUTUBE_MUSIC_PACKAGE ->
				current.copy(youtubeMusicEpoch = current.youtubeMusicEpoch + 1)
			else -> current
		}
		if (next == current) return
		_config.value = next
		EventLog.append("native", "$packageName source state cleared: $reason")
	}

	fun acceptsPackage(packageName: String): Boolean {
		return accepts(_config.value, packageName)
	}

	/**
	 * The master, for the accessibility services.
	 *
	 * They are scoped to browsers and to the YouTube app by the OS, so with this
	 * off there is nothing they could legitimately be reading — and reading a
	 * URL bar for evidence nobody will use is the kind of quiet cost the switch
	 * exists to stop.
	 */
	val youTubeScrobblingEnabled: Boolean get() = _config.value.youTubeScrobbling

	fun epochFor(packageName: String): Long? = when (packageName) {
		YouTubeProbe.YOUTUBE_PACKAGE -> _config.value.youtubeEpoch
		YouTubeProbe.YOUTUBE_MUSIC_PACKAGE -> _config.value.youtubeMusicEpoch
		else -> null
	}

	fun isSnapshotCurrent(packageName: String, epoch: Long?): Boolean =
		isSnapshotCurrent(_config.value, packageName, epoch)

	internal fun toggled(current: Config, packageName: String, enabled: Boolean): Config =
		when (packageName) {
			YouTubeProbe.YOUTUBE_PACKAGE -> if (current.youtubeEnabled == enabled) current else {
				current.copy(
					youtubeEnabled = enabled,
					youtubeEpoch = current.youtubeEpoch + 1,
				)
			}
			YouTubeProbe.YOUTUBE_MUSIC_PACKAGE ->
				if (current.youtubeMusicEnabled == enabled) current else {
					current.copy(
						youtubeMusicEnabled = enabled,
						youtubeMusicEpoch = current.youtubeMusicEpoch + 1,
					)
				}
			else -> current
		}

	internal fun invalidated(current: Config): Config = current.copy(
		youtubeEpoch = current.youtubeEpoch + 1,
		youtubeMusicEpoch = current.youtubeMusicEpoch + 1,
	)

	internal fun accepts(current: Config, packageName: String): Boolean =
		YouTubeProbe.acceptsPackage(
			packageName,
			current.youTubeScrobbling,
			current.youtubeEnabled,
			current.youtubeMusicEnabled,
		)

	/**
	 * Set the live config directly, for the replay harness only.
	 *
	 * [init] needs a `Context` and reads persisted settings, so a JVM replay has
	 * no way to say "native YouTube is opted in" — and without that every native
	 * trace would be discarded at the epoch check before reaching a single
	 * decision. This writes the same field [init] writes and nothing else; it
	 * does not persist, and no production caller uses it.
	 */
	internal fun configureForReplay(config: Config) {
		_config.value = config
	}

	internal fun isSnapshotCurrent(
		current: Config,
		packageName: String,
		epoch: Long?,
	): Boolean = when {
		!current.youTubeScrobbling -> false
		packageName == YouTubeProbe.YOUTUBE_PACKAGE ->
			current.youtubeEnabled && epoch == current.youtubeEpoch
		packageName == YouTubeProbe.YOUTUBE_MUSIC_PACKAGE ->
			current.youtubeMusicEnabled && epoch == current.youtubeMusicEpoch
		else -> true
	}
}
