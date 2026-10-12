package com.rustedwax.app.update

import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Issue #43: is a newer *official* RustedWax release available?
 *
 * Pure apart from the four ports it is handed, so every rule here is tested on
 * the JVM. Three questions are kept apart on purpose:
 *
 *  - **what is official** — [UpdateMetadata], read from one small release asset
 *    and refused rather than repaired when anything about it is off;
 *  - **whether to say so** — [UpdateCheck], which compares the numeric Android
 *    `versionCode` and remembers the highest version already announced;
 *  - **where a tap goes** — [UpdateDestination], decided from how this copy was
 *    installed. Nothing in the metadata can name a URL.
 *
 * Every failure is quiet. A missing asset, a timeout, a malformed body, an
 * unexpected redirect: each ends as [UpdateCheck.Outcome.Failed], which is
 * logged and never shown.
 */
internal data class UpdateMetadata(val versionCode: Int, val versionName: String) {

	companion object {
		const val SCHEMA = 1

		/** The official package. Fixed, so a debug variant's suffix cannot change what is accepted. */
		const val APPLICATION_ID = "com.rustedwax.app"

		/** Play's own ceiling for `versionCode`. */
		private const val MAX_VERSION_CODE = 2_100_000_000

		private val VERSION_NAME = Regex("""\d{1,4}\.\d{1,4}\.\d{1,4}""")

		/**
		 * The metadata in [body], or null when it is not exactly what an official
		 * release publishes.
		 *
		 * [tag] is the release tag the asset was served from, when the redirect
		 * named one; it must then agree with `versionName`, so a file attached to
		 * the wrong release is refused instead of trusted.
		 */
		fun parse(body: String, tag: String? = null): UpdateMetadata? {
			val json = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return null
			if (json.opt("schema").exactInt() != SCHEMA) return null
			if (json.opt("applicationId") != APPLICATION_ID) return null
			val code = json.opt("versionCode").exactInt() ?: return null
			if (code !in 1..MAX_VERSION_CODE) return null
			val name = json.opt("versionName") as? String ?: return null
			if (!VERSION_NAME.matches(name)) return null
			if (tag != null && tag != "v$name") return null
			return UpdateMetadata(code, name)
		}

		/** An integer written as one. `"66"`, `66.0` and `true` are not version codes. */
		private fun Any?.exactInt(): Int? = when (this) {
			is Int -> this
			is Long -> if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else null
			else -> null
		}
	}
}

/** How this copy was installed, as far as a tap is concerned. */
internal enum class Distribution {
	/** Installed by the Play Store. Never sent to a GitHub APK. */
	PLAY,

	/** Sideloaded, installed over adb, or by anything that is not Play. */
	GITHUB,
	;

	companion object {
		const val PLAY_STORE = "com.android.vending"

		/**
		 * Only the Play Store's own package counts as Play. Anything else —
		 * nothing, adb, the system package installer, a browser, another store —
		 * is sent to the official GitHub release page, which is the only other
		 * place an official build is published.
		 */
		fun of(installer: String?): Distribution = if (installer == PLAY_STORE) PLAY else GITHUB
	}
}

/** Where a tap on the notification goes. Fixed per distribution; never read from metadata. */
internal object UpdateDestination {
	const val GITHUB = "https://github.com/rustedcomet/RustedWax-Android/releases/latest"

	/** Opens the Play Store app when it is there, and Play's web page when it is not. */
	const val PLAY = "https://play.google.com/store/apps/details?id=${UpdateMetadata.APPLICATION_ID}"

	fun of(distribution: Distribution): String = when (distribution) {
		Distribution.PLAY -> PLAY
		Distribution.GITHUB -> GITHUB
	}
}

/** What is remembered between checks. Survives process death and reboot. */
internal interface UpdateCheckState {
	/** When a check last started, successful or not. 0 when never. */
	var lastAttemptAt: Long

	/** When metadata was last read and accepted. 0 when never. */
	var lastSuccessAt: Long

	/** The highest `versionCode` ever announced. 0 when none. */
	var notifiedVersionCode: Int
}

/** One read of the official metadata. */
internal fun interface UpdateSource {
	/** The accepted metadata, or a short reason it was not. Never throws on purpose. */
	fun read(): Result<UpdateMetadata>
}

internal interface UpdateNotifier {
	/** Whether a notification posted now would actually be shown. */
	fun canPost(): Boolean

	fun post(available: UpdateMetadata, destination: String)

	fun cancel()
}

internal class UpdateCheck(
	private val installedVersionCode: Int,
	private val distribution: Distribution,
	private val state: UpdateCheckState,
	private val source: UpdateSource,
	private val notifier: UpdateNotifier,
	/** Debug override only: re-read on every start instead of once a day. */
	private val ignoreThrottle: Boolean = false,
) {

	sealed interface Outcome {
		data class Skipped(val reason: String) : Outcome
		data class Failed(val reason: String) : Outcome
		data class Current(val official: UpdateMetadata) : Outcome
		data class AlreadyNotified(val official: UpdateMetadata) : Outcome
		data class Announced(val official: UpdateMetadata, val destination: String) : Outcome
	}

	fun run(now: Long): Outcome {
		if (!running.compareAndSet(false, true)) return Outcome.Skipped("already running")
		try {
			if (!ignoreThrottle && !due(now, state.lastAttemptAt, state.lastSuccessAt)) {
				return Outcome.Skipped("checked recently")
			}
			// Written before the read, so a process killed mid-request does not
			// come back and ask again straight away.
			state.lastAttemptAt = now
			val official = source.read().getOrElse {
				return Outcome.Failed(it.message ?: it.javaClass.simpleName)
			}
			state.lastSuccessAt = now
			if (official.versionCode <= installedVersionCode) {
				// Installed the update, or never behind: a leftover notice goes.
				notifier.cancel()
				return Outcome.Current(official)
			}
			if (official.versionCode <= state.notifiedVersionCode) return Outcome.AlreadyNotified(official)
			// Not remembered as announced unless it can actually be shown, so a
			// later grant of the permission still hears about it once.
			if (!notifier.canPost()) return Outcome.Skipped("notifications off")
			// Remembered first: a process killed between the two lines costs one
			// notice, never a second one for a version already shown.
			state.notifiedVersionCode = official.versionCode
			val destination = UpdateDestination.of(distribution)
			notifier.post(official, destination)
			return Outcome.Announced(official, destination)
		} finally {
			running.set(false)
		}
	}

	companion object {
		/** After a successful read. */
		const val SUCCESS_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L

		/** After any attempt, including one that failed: offline is not a reason to ask more often. */
		const val RETRY_INTERVAL_MILLIS = 6 * 60 * 60 * 1000L

		/** Process-wide: two starts in quick succession share one check. */
		private val running = AtomicBoolean(false)

		/**
		 * A clock that went backwards counts as stale: otherwise a phone set a
		 * year ahead and back again would not check for a year.
		 */
		fun due(now: Long, lastAttemptAt: Long, lastSuccessAt: Long): Boolean {
			fun elapsed(since: Long, interval: Long) = since <= 0L || now < since || now - since >= interval
			return elapsed(lastAttemptAt, RETRY_INTERVAL_MILLIS) && elapsed(lastSuccessAt, SUCCESS_INTERVAL_MILLIS)
		}
	}
}
