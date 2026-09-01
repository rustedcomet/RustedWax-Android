package com.rustedwax.app.detect

import com.rustedwax.core.*
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi

/**
 * The two public signals that together say "this YouTube Short is still playing"
 * after its seekbar has gone away.
 *
 * See [PipPlaybackInference] for why it takes both and what the residual false
 * positive is. This class is the Android half: it asks the framework, holds the
 * small amount of state that makes the usage-events query cheap, and answers a
 * single boolean.
 */
class PipPlaybackProbe(private val context: Context) {
	data class Evidence(
		val mediaAudioStarted: Boolean,
		val visiblePinnedWindow: Boolean,
	) {
		val playing: Boolean get() = mediaAudioStarted && visiblePinnedWindow
	}

	/**
	 * Which YouTube activities are currently started.
	 *
	 * Kept across calls so each query only has to cover the window since the
	 * previous one. Querying a wide window every second would be wasteful and,
	 * on a busy device, slow. See [VisibleActivities] for why this is a set
	 * rather than the single last event it used to be.
	 */
	private val visibleActivities = VisibleActivities()
	private var queriedUpToMillis: Long = 0

	/** Whether Usage Access has been granted. Without it PiP cannot be attributed. */
	fun hasUsageAccess(): Boolean {
		if (!SUPPORTED) return false
		return runCatching {
			val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
			val mode = ops.unsafeCheckOpNoThrow(
				AppOpsManager.OPSTR_GET_USAGE_STATS,
				Process.myUid(),
				context.packageName,
			)
			mode == AppOpsManager.MODE_ALLOWED
		}.getOrDefault(false)
	}

	/**
	 * True when YouTube has a visible window *and* media audio is started.
	 *
	 * Order matters only for cost: the audio check is a cheap local call, the
	 * usage query is not, so a silent device short-circuits before asking.
	 */
	fun youTubePlayingWithoutSurface(nowMillis: Long): Boolean {
		return evidence(nowMillis).playing
	}

	/** Capture the two independent framework facts without interpreting their pair. */
	fun evidence(nowMillis: Long): Evidence {
		if (!SUPPORTED) return Evidence(false, false)
		val audio = mediaAudioStarted()
		val window = if (audio) youTubeWindowVisible(nowMillis) else false
		Phase3Telemetry.pipEvidence(nowMillis, audio, window)
		return Evidence(audio, window)
	}

	private fun mediaAudioStarted(): Boolean = runCatching {
		val audio = context.getSystemService(AudioManager::class.java) ?: return false
		audio.activePlaybackConfigurations.any {
			it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA
		}
	}.getOrDefault(false)

	/**
	 * Whether YouTube still owns a visible window.
	 *
	 * A PiP window leaves the activity `ACTIVITY_PAUSED` — visible but not
	 * focused — and only a real teardown produces `ACTIVITY_STOPPED`. So
	 * resumed-or-paused is exactly "YouTube is on screen somewhere", which
	 * includes the PiP case and excludes a fully backgrounded app.
	 *
	 * Fully-backgrounded playback is deliberately *not* credited: it is a
	 * different feature with a different risk profile, and the surface this
	 * fixes is the one the user can see.
	 */
	@RequiresApi(Build.VERSION_CODES.Q)
	private fun youTubeWindowVisible(nowMillis: Long): Boolean {
		val usage = runCatching {
			context.getSystemService(UsageStatsManager::class.java)
		}.getOrNull() ?: return false
		// First call has no anchor, so look back far enough to find the
		// transition that put YouTube on screen.
		val from = if (queriedUpToMillis == 0L) {
			nowMillis - COLD_START_LOOKBACK_MS
		} else {
			// Small overlap: usage events are not always delivered in order.
			(queriedUpToMillis - QUERY_OVERLAP_MS).coerceAtLeast(0)
		}
		runCatching {
			val events = usage.queryEvents(from, nowMillis) ?: return visibleActivities.visible
			val event = UsageEvents.Event()
			while (events.hasNextEvent()) {
				events.getNextEvent(event)
				if (event.packageName != YouTubeProbe.YOUTUBE_PACKAGE) continue
				val lifecycle = when (event.eventType) {
					UsageEvents.Event.ACTIVITY_RESUMED -> VisibleActivities.Lifecycle.RESUMED
					UsageEvents.Event.ACTIVITY_PAUSED -> VisibleActivities.Lifecycle.PAUSED
					UsageEvents.Event.ACTIVITY_STOPPED -> VisibleActivities.Lifecycle.STOPPED
					else -> continue
				}
				visibleActivities.onEvent(event.className, lifecycle)
			}
			queriedUpToMillis = nowMillis
		}.onFailure { return false }
		return visibleActivities.visible
	}

	private companion object {
		/**
		 * `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED`/`ACTIVITY_STOPPED` and
		 * `unsafeCheckOpNoThrow` are all API 29. `minSdk` is 26, and there is no
		 * older equivalent that distinguishes a PiP window from a stopped one —
		 * so on 26–28 the feature is simply absent rather than approximated.
		 */
		val SUPPORTED = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

		/** Enough to catch the transition that opened the Short being watched. */
		const val COLD_START_LOOKBACK_MS = 60 * 60 * 1000L
		const val QUERY_OVERLAP_MS = 2_000L
	}
}
