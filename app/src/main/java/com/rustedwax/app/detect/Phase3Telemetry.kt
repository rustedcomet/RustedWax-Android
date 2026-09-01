package com.rustedwax.app.detect

import android.os.SystemClock
import android.util.Log
import com.rustedwax.app.BuildConfig
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Structured, read-only playback telemetry for debug builds.
 *
 * This is deliberately a debug-build logcat surface rather than a preference,
 * file, receiver or provider. A host gate can sample the same immutable
 * [SessionSnapshot] the UI/finalizer consumes without restarting the process or
 * widening an Android component. Titles and owners are represented by stable
 * hashes: the gate needs continuity and presence, not a browsing-history leak.
 */
internal object Phase3Telemetry {

	const val TAG = "RustedWaxPhase3"
	private val logExecutor = Executors.newSingleThreadExecutor { task ->
		Thread(task, "rustedwax-phase3-log").apply { priority = Thread.MIN_PRIORITY }
	}

	fun snapshot(
		snapshot: SessionSnapshot,
		playbackRate: Double?,
		finalized: Boolean,
	) {
		if (!BuildConfig.DEBUG) return
		val observedAt = SystemClock.elapsedRealtime()
		// Live snapshots originate on SessionProbe's handler — the process main
		// looper. Hashing both labels, formatting the full record and synchronously
		// writing logcat there multiplied a burst of MediaSession callbacks into UI
		// starvation. The snapshot is immutable, so the debug evidence can be
		// rendered in order by its own worker without changing what it records.
		logExecutor.execute {
			Log.i(TAG, snapshotLine(snapshot, playbackRate, finalized, observedAt))
		}
	}

	fun pipEvidence(
		observedAtMillis: Long,
		mediaAudioStarted: Boolean,
		youTubeWindowVisible: Boolean,
	) {
		if (!BuildConfig.DEBUG) return
		Log.i(
			TAG,
			"kind=pip-evidence observedAt=$observedAtMillis " +
				"audio=$mediaAudioStarted window=$youTubeWindowVisible " +
				"paired=${mediaAudioStarted && youTubeWindowVisible}",
		)
	}

	/** Structural YouTube surface proof; contains no labels or browsing history. */
	internal fun shortSurfaceLine(
		observedAtMillis: Long,
		shortRoots: Int,
		shortPlayers: Int,
		progressSurfaces: Int,
		minimizedPlayers: Int,
	): String = "kind=short-surface observedAt=$observedAtMillis " +
		"roots=$shortRoots players=$shortPlayers progress=$progressSurfaces " +
		"minimized=$minimizedPlayers"

	internal fun snapshotLine(
		snapshot: SessionSnapshot,
		playbackRate: Double?,
		finalized: Boolean,
		elapsedRealtimeMs: Long,
	): String {
		val exactItem = (snapshot.identity as? YouTubeProbe.Identity.Confirmed)?.videoId ?: "-"
		val normal = (snapshot.playedMs - snapshot.inferredPlayedMs).coerceAtLeast(0)
		return listOf(
			"kind=snapshot",
			"elapsed=$elapsedRealtimeMs",
			"package=${snapshot.packageName}",
			"token=${snapshot.trackInstanceToken ?: -1}",
			"item=$exactItem",
			"titleHash=${hash(snapshot.title)}",
			"ownerHash=${hash(snapshot.ownerHandle ?: snapshot.artist)}",
			"played=${snapshot.playedMs}",
			"normal=$normal",
			"inferred=${snapshot.inferredPlayedMs}",
			"position=${snapshot.positionMs ?: -1}",
			"duration=${snapshot.durationMs ?: -1}",
			"rate=${playbackRate?.let { String.format(Locale.US, "%.3f", it) } ?: "-"}",
			"playing=${snapshot.isPlaying}",
			"progressLost=${snapshot.foregroundProgressLost}",
			"proof=${snapshot.sourceProof.name}",
			"finalized=$finalized",
		).joinToString(" ")
	}

	private fun hash(value: String?): String {
		val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return "-"
		return MessageDigest.getInstance("SHA-256")
			.digest(normalized.toByteArray(Charsets.UTF_8))
			.take(8)
			.joinToString("") { "%02x".format(it) }
	}
}
