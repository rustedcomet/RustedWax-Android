package com.rustedwax.app.detect

/**
 * What the accessibility service could see while it was receiving nothing.
 *
 * The distinction is the whole point: silence only means something once paired
 * with what was on screen at the time.
 */
enum class ObservedSurface {
	/** A complete Shorts player parsed on this capture. */
	YOUTUBE_SHORTS_PLAYER,

	/** A visible native-YouTube root, but no complete Shorts player in it. */
	YOUTUBE_NO_PLAYER,

	/**
	 * `rootInActiveWindow` returned null: the service has no active window at
	 * all. With the screen on this is itself anomalous — it is the service being
	 * unable to see, not the user being elsewhere.
	 */
	NO_ROOT,

	/** Some other package is foreground. Silence is fully explained. */
	OTHER_APP,
}

class AccessibilityEventSilence(
	private val silenceThresholdMillis: Long = SILENCE_THRESHOLD_MS,
	private val repeatIntervalMillis: Long = REPEAT_INTERVAL_MS,
	private val probeIntervalMillis: Long = PROBE_INTERVAL_MS,
) {

	private var lastSignalAtElapsed: Long? = null
	private var lastReportAtElapsed: Long? = null
	private var lastProbeAtElapsed: Long? = null
	private var reported = false

	/**
	 * The service connected. Starts the clock without claiming anything: a
	 * freshly connected service has legitimately seen no events yet, and the
	 * window before the first one must not read as an outage.
	 */
	@Synchronized
	fun started(atElapsed: Long) {
		lastSignalAtElapsed = atElapsed
		lastReportAtElapsed = null
		lastProbeAtElapsed = null
		reported = false
	}

	@Synchronized
	fun eventReceived(atElapsed: Long): String? {
		val since = lastSignalAtElapsed
		val recovered = if (reported && since != null) {
			"accessibility observation recovered after ${seconds(atElapsed - since)}s"
		} else {
			null
		}
		lastSignalAtElapsed = atElapsed
		lastReportAtElapsed = null
		lastProbeAtElapsed = null
		reported = false
		return recovered
	}

	@Synchronized
	fun captureSucceeded(atElapsed: Long): String? = eventReceived(atElapsed)

	@Synchronized
	fun observed(
		atElapsed: Long,
		surface: ObservedSurface,
		screenInteractive: Boolean,
		unmeasuredPlayback: () -> Boolean? = { null },
	): String? {
		val since = lastSignalAtElapsed
		if (since == null) {
			lastSignalAtElapsed = atElapsed
			return null
		}
		// Another app being foreground, or a dark screen, fully explains the
		// quiet. Reporting either would bury the case that matters.
		if (!screenInteractive || surface == ObservedSurface.OTHER_APP) return null
		val silentFor = atElapsed - since
		if (silentFor < silenceThresholdMillis) return null
		lastReportAtElapsed?.let { if (atElapsed - it < repeatIntervalMillis) return null }
		// Nothing is being lost unless something is playing. Proven false is a
		// full stop; unknown still reports the one state where the service
		// cannot see anything at all, because that is anomalous by itself.
		val audible = unmeasuredPlayback()
		if (audible == false) return null
		if (audible == null && surface != ObservedSurface.NO_ROOT) return null
		lastReportAtElapsed = atElapsed
		val first = !reported
		reported = true
		val playing = if (audible == true) {
			"YouTube is playing audio with a visible window"
		} else {
			"whether anything is playing is unknown without Usage Access"
		}
		return if (first) {
			"nothing observable for ${seconds(silentFor)}s with the screen on " +
				"(${describe(surface)}; $playing) — anything played in this window is " +
				"unobserved, not idle"
		} else {
			"still nothing observable after ${seconds(silentFor)}s (${describe(surface)}; $playing)"
		}
	}

	/**
	 * Whether a foreground capture is worth making purely to check for silence.
	 *
	 * The outage that matters happens when *nothing* is latched — no active
	 * Short means no refresh, and a latched playlist stops the acquisition poll
	 * too, so the observer can go completely quiet and leave no trace. Without
	 * this probe the detector would only ever see the outages it least needs to.
	 *
	 * Mutating, like the playlist poll beside it: asking rate-limits the asking.
	 */
	@Synchronized
	fun probeDue(nowElapsed: Long): Boolean {
		val since = lastSignalAtElapsed ?: return false
		if (nowElapsed - since < silenceThresholdMillis) return false
		lastProbeAtElapsed?.let { if (nowElapsed - it < probeIntervalMillis) return false }
		lastProbeAtElapsed = nowElapsed
		return true
	}

	private fun describe(surface: ObservedSurface): String = when (surface) {
		ObservedSurface.YOUTUBE_SHORTS_PLAYER -> "Shorts player on screen"
		ObservedSurface.YOUTUBE_NO_PLAYER -> "YouTube on screen, no complete Shorts player"
		ObservedSurface.NO_ROOT ->
			"no active window visible to the service at all — the service cannot see"
		ObservedSurface.OTHER_APP -> "another app is foreground"
	}

	private fun seconds(millis: Long): Long = millis / 1000

	companion object {
		/**
		 * Comfortably longer than any healthy gap.
		 *
		 * A Shorts feed emits constantly while scrolling, and the measured
		 * outage ran 87 seconds against a 30-second idle poll. 45s is past
		 * anything the seekbar's own updates leave behind, and still short
		 * enough that a report and its recovery both land inside one outage.
		 */
		const val SILENCE_THRESHOLD_MS = 45_000L

		/** A long outage should leave a trail, not a flood. */
		const val REPEAT_INTERVAL_MS = 60_000L

		/** Cheap enough to run while silent, rare enough to cost nothing. */
		const val PROBE_INTERVAL_MS = 15_000L
	}
}
