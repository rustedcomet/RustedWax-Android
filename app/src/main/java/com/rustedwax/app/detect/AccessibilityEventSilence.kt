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

/**
 * Names the outage that `<redacted-private-path>` §5.1 could only infer from gaps.
 *
 * ## The bug this instruments
 *
 * Between 17:19:40 and 17:21:07 on 2026-08-05 the log held two lines, 30s apart
 * — the idle poll and nothing else — while a Short was playing. The Shorts
 * observer had received no YouTube accessibility event for 87 seconds. In the
 * same session a Short titled `💯💯💯🔥😎` never appears in the log at all:
 * never observed, never finalized. It is the same shape as the earlier 13h
 * outage, it explains the regular-mode Shorts that did not scrobble, and it is
 * very likely why roughly half that day's watch history never reached the app.
 *
 * At hand-off the service looked healthy — `crashed services: {}` empty, both
 * grants on, standby bucket 10 — so it is intermittent and cannot be reproduced
 * on demand. Hunting a cause that only appears by surprise, from evidence that
 * only exists as an absence of lines, is how the 13h outage went unexplained.
 * So this measures it first: the next occurrence writes its own name, its own
 * duration and what was on screen at the time.
 *
 * ## What the first field day proved about this detector, 2026-08-06
 *
 * It reported **111 outages in one day**, median 111 seconds. Almost none were
 * real, and two measurements killed the design that produced them.
 *
 * **Event silence is not observer silence.** At 15:08:39 it reported
 * `silent for 45s … (Shorts player on screen) — anything played in this window
 * is unobserved` while, inside that exact window, the service had measured the
 * seekbar thirteen times and credited forty seconds. Once a Short is latched the
 * observer stops needing callbacks and polls on its own second, so *events* stop
 * while *observation* continues perfectly. A successful capture is therefore
 * liveness, and the only liveness that matters.
 *
 * **`YOUTUBE_NO_PLAYER` is usually the user leaving Shorts.** Reproduced on
 * demand, first try: press Back out of the Shorts player and YouTube keeps
 * `reel_time_bar` in its hierarchy while `reel_watch_fragment_root` stops being
 * visible, so every capture reads `found 0; captured 2 nodes`. 36% of the day's
 * captures were that shape. It is the app being open and idle, not an outage.
 *
 * ## The evidence that a report actually needs
 *
 * The report has to answer "is something playing that we cannot see?", and the
 * accessibility tree cannot answer it — measured 2026-08-06, while a Short plays
 * YouTube's MediaSession is `active=false`, `state=1`, `metadata: size=0`. It
 * says nothing about Shorts at all, so asking it would be circular.
 *
 * The independent answer already exists, built for picture-in-picture (§8.3):
 * media audio in the `started` state paired with a YouTube window that
 * `UsageStatsManager` reports visible. That pair is what [PipPlaybackProbe]
 * answers, and it is true exactly when a listen is being lost.
 *
 * So a report now requires the screen on, the surface not to be another app, no
 * successful capture within the window — and evidence that something is playing
 * that **nothing else is already counting**. Ordinary watch playback is measured
 * by the MediaSession, and picture-in-picture time is credited by its own
 * inference; neither is a loss, so neither reports. When Usage Access is missing
 * the question cannot be answered at all, and only [ObservedSurface.NO_ROOT] —
 * the state where the service cannot see anything — still reports on its own.
 *
 * A paused Short left on screen no longer reports at all: paused audio drops out
 * of the started list, which is the same property the PiP inference relies on.
 *
 * ## Reporting-only
 *
 * Nothing here changes a scrobble decision, a capture, or any gate. It observes
 * and it writes lines.
 */
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

	/**
	 * A YouTube accessibility event arrived: the stream is alive.
	 *
	 * @return a line to log when this ends an outage that was reported, naming
	 * the measured duration, or null. The recovery line is the half that makes
	 * the next occurrence self-describing — a start with no end is how long an
	 * outage lasted becomes guesswork from log gaps again.
	 */
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

	/**
	 * A capture parsed a complete Shorts player: the observer can see.
	 *
	 * Identical to [eventReceived] in effect, and separate only so the call site
	 * reads as what it is. A latched Short is measured by the service's own 1s
	 * poll, and YouTube emits no callbacks while it does — measured 2026-08-06,
	 * the old detector called forty seconds of successful per-second measurement
	 * an outage because none of it arrived as an event.
	 */
	@Synchronized
	fun captureSucceeded(atElapsed: Long): String? = eventReceived(atElapsed)

	/**
	 * The outcome of one capture attempt, whatever it found.
	 *
	 * @param screenInteractive the display is on. Silence with the screen off is
	 * correct behaviour and must never be reported.
	 * @param unmeasuredPlayback asked only when a report is otherwise due,
	 * because it costs a `UsageStatsManager` query and this runs on every
	 * capture. True when something is playing in YouTube that nothing else is
	 * counting — not the seekbar, not the MediaSession, not the picture-in-
	 * picture inference. False when nothing is playing *or* when something else
	 * already has it. `null` when Usage Access is not granted and the question
	 * cannot be answered at all, which is not the same as "nothing is playing"
	 * and is treated as unknown.
	 * @return a line to log, or null when the observer is alive, the quiet is
	 * explained, nothing is playing, or a report is not yet due.
	 */
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
