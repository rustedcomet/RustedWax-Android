package com.rustedwax.app.scrobble

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.rustedwax.app.detect.EventLog

/**
 * The timing half of the queue wake-up: the one alarm [QueueWakeup] arms.
 *
 * Not exported and with no intent filter, so only this app's own PendingIntent
 * reaches it. It does no network and no Hive work: it reads the durable queue,
 * and if something the current account can send is due it schedules the one
 * expedited [QueueRetryJobService] run. Everything else — a stale alarm, another
 * account's row, a duplicate delivery, a run already going — is decided in
 * [QueueWakeup.alarmFired].
 */
class QueueRetryAlarmReceiver : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != ACTION) return
		val appContext = context.applicationContext
		EventLog.init(appContext)
		FinalizationRuntime.init(appContext)
		FinalizationRuntime.onQueueWakeupAlarm()
	}

	/**
	 * [QueueWakeup.Alarm] over one PendingIntent.
	 *
	 * `setAndAllowWhileIdle` on elapsed realtime: inexact, no exact-alarm
	 * permission, still delivered in Doze (rate-limited by the platform), and
	 * immune to the wall clock being changed. Setting it again with the same
	 * PendingIntent replaces the previous alarm.
	 */
	internal class Alarm(context: Context) : QueueWakeup.Alarm {
		private val context = context.applicationContext

		private val manager: AlarmManager?
			get() = context.getSystemService(AlarmManager::class.java)

		private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
			context,
			REQUEST_CODE,
			Intent(context, QueueRetryAlarmReceiver::class.java).setAction(ACTION),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)

		override fun arm(delayMs: Long): Boolean = runCatching {
			manager!!.setAndAllowWhileIdle(
				AlarmManager.ELAPSED_REALTIME_WAKEUP,
				SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(0),
				pendingIntent(),
			)
		}.onFailure {
			EventLog.append("queue", "retry wake-up alarm could not be set: ${it.message}")
		}.isSuccess

		override fun disarm() {
			runCatching { manager?.cancel(pendingIntent()) }
		}
	}

	private companion object {
		const val ACTION = "com.rustedwax.app.action.QUEUE_RETRY_WAKEUP"
		const val REQUEST_CODE = 0x5157
	}
}
