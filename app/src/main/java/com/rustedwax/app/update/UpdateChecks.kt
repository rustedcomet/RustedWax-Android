package com.rustedwax.app.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rustedwax.app.BuildConfig
import com.rustedwax.app.R
import com.rustedwax.app.detect.EventLog
import java.io.File

/**
 * Issue #43: the update check, wired to Android.
 *
 * Run on the way into the app, on a worker thread, at most once a day after a
 * successful read and at most every six hours after a failed one. No job, no
 * service, no receiver: an app nobody opens is not told about updates, which is
 * the conservative trade the issue asks for.
 */
internal object UpdateChecks {

	private const val LOG_TAG = "RustedWaxUpdate"

	/**
	 * Debug builds only: a metadata file in the app's private storage, read in
	 * place of the network and on every start. It is how a device test shows
	 * "current" and "newer" without publishing anything; release builds never
	 * look for it.
	 */
	const val OVERRIDE_FILE = "update-metadata.override.json"

	/** Called off the main thread. Never throws. */
	fun runOnStart(context: Context) {
		val app = context.applicationContext
		val outcome = runCatching { create(app).run(System.currentTimeMillis()) }
			.getOrElse { UpdateCheck.Outcome.Failed("check failed: ${it.javaClass.simpleName}") }
		val line = "update check — ${describe(outcome)}"
		// Skips are the normal case on every start and say nothing worth keeping.
		if (outcome !is UpdateCheck.Outcome.Skipped || outcome.reason != "checked recently") {
			EventLog.append("update", line)
		}
		if (BuildConfig.DEBUG) Log.i(LOG_TAG, line)
	}

	private fun create(app: Context): UpdateCheck {
		val override = File(app.filesDir, OVERRIDE_FILE).takeIf { BuildConfig.DEBUG && it.isFile }
		val source = if (override != null) {
			UpdateSource {
				runCatching { checkNotNull(UpdateMetadata.parse(override.readText())) { "override refused" } }
			}
		} else {
			ReleaseMetadataSource(HttpsTransport("RustedWax/${BuildConfig.VERSION_NAME} (Android update check)"))
		}
		return UpdateCheck(
			installedVersionCode = BuildConfig.VERSION_CODE,
			distribution = Distribution.of(installerOf(app)),
			// The override keeps its own state, so a device test that announces
			// a made-up version never stops the real one from being announced.
			state = PreferencesUpdateCheckState(
				app.getSharedPreferences(if (override != null) OVERRIDE_PREFS else PREFS, Context.MODE_PRIVATE),
			),
			source = source,
			notifier = AndroidUpdateNotifier(app),
			ignoreThrottle = override != null,
		)
	}

	/** The package that installed this copy, or null when Android does not say. */
	private fun installerOf(app: Context): String? = runCatching {
		val pm = app.packageManager
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			pm.getInstallSourceInfo(app.packageName).installingPackageName
		} else {
			@Suppress("DEPRECATION")
			pm.getInstallerPackageName(app.packageName)
		}
	}.getOrNull()

	private fun describe(outcome: UpdateCheck.Outcome): String = when (outcome) {
		is UpdateCheck.Outcome.Skipped -> "skipped (${outcome.reason})"
		is UpdateCheck.Outcome.Failed -> "no answer (${outcome.reason})"
		is UpdateCheck.Outcome.Current -> "current (official ${outcome.official.versionCode}, installed ${BuildConfig.VERSION_CODE})"
		is UpdateCheck.Outcome.AlreadyNotified -> "already announced ${outcome.official.versionCode}"
		is UpdateCheck.Outcome.Announced ->
			"announced ${outcome.official.versionName} (${outcome.official.versionCode}) → ${outcome.destination}"
	}

	private const val PREFS = "rustedwax_update_check"
	private const val OVERRIDE_PREFS = "rustedwax_update_check_override"
}

/**
 * Its own small preference file: app-wide, not account data, so it has no place
 * in the account-scoped database and nothing to migrate. Written with `commit`
 * so "already announced" is on disk before the notification is posted.
 */
// `commit`, not `apply`: this runs on a worker thread, and "already announced"
// has to be on disk before the notification is posted.
@SuppressLint("ApplySharedPref")
internal class PreferencesUpdateCheckState(private val prefs: SharedPreferences) : UpdateCheckState {

	override var lastAttemptAt: Long
		get() = runCatching { prefs.getLong(LAST_ATTEMPT, 0L) }.getOrDefault(0L)
		set(value) {
			prefs.edit().putLong(LAST_ATTEMPT, value).commit()
		}

	override var lastSuccessAt: Long
		get() = runCatching { prefs.getLong(LAST_SUCCESS, 0L) }.getOrDefault(0L)
		set(value) {
			prefs.edit().putLong(LAST_SUCCESS, value).commit()
		}

	override var notifiedVersionCode: Int
		get() = runCatching { prefs.getInt(NOTIFIED, 0) }.getOrDefault(0)
		set(value) {
			prefs.edit().putInt(NOTIFIED, value).commit()
		}

	private companion object {
		const val LAST_ATTEMPT = "last_attempt_at"
		const val LAST_SUCCESS = "last_success_at"
		const val NOTIFIED = "notified_version_code"
	}
}

/**
 * One notification on its own channel. Its tap opens a web address chosen by
 * [UpdateDestination] — a page, never a download — and nothing in this app
 * fetches or installs an APK.
 */
internal class AndroidUpdateNotifier(private val context: Context) : UpdateNotifier {

	private val manager = NotificationManagerCompat.from(context)

	override fun canPost(): Boolean {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
			PackageManager.PERMISSION_GRANTED
		) {
			return false
		}
		if (!manager.areNotificationsEnabled()) return false
		ensureChannel()
		val channel = manager.getNotificationChannel(CHANNEL)
		return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
	}

	@SuppressLint("MissingPermission") // checked in canPost, and guarded below
	override fun post(available: UpdateMetadata, destination: String) {
		ensureChannel()
		val open = Intent(Intent.ACTION_VIEW, Uri.parse(destination))
			.addCategory(Intent.CATEGORY_BROWSABLE)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		val pending = PendingIntent.getActivity(
			context,
			ID,
			open,
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
		val where = if (destination == UpdateDestination.PLAY) {
			context.getString(R.string.app_update_open_play)
		} else {
			context.getString(R.string.app_update_open_github)
		}
		val notification = NotificationCompat.Builder(context, CHANNEL)
			.setSmallIcon(R.drawable.ic_notification_rustedwax)
			.setContentTitle(context.getString(R.string.app_update_title, available.versionName))
			.setContentText(where)
			.setContentIntent(pending)
			.setAutoCancel(true)
			.setOnlyAlertOnce(true)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.build()
		runCatching { manager.notify(TAG, ID, notification) }
	}

	override fun cancel() {
		runCatching { manager.cancel(TAG, ID) }
	}

	private fun ensureChannel() {
		val channel = NotificationChannel(
			CHANNEL,
			context.getString(R.string.app_update_channel_name),
			NotificationManager.IMPORTANCE_DEFAULT,
		).apply { description = context.getString(R.string.app_update_channel_description) }
		runCatching { manager.createNotificationChannel(channel) }
	}

	companion object {
		const val CHANNEL = "rustedwax_app_updates"
		const val TAG = "rustedwax-update"

		/** Distinct from the status card (0x5157_0004) and from reply notices (hashed ids, tagged). */
		const val ID = 0x5155_0043
	}
}
