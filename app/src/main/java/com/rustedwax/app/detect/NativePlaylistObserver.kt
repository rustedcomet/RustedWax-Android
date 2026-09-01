package com.rustedwax.app.detect

/**
 * Process-local bridge carrying the latched native playlist to SessionProbe.
 *
 * Mirrors [NativeShortsObserver]: the accessibility service writes, the probe
 * reads, and nothing else touches it. Scoped to `com.google.android.youtube`
 * only — YouTube Music was not probed for this phase and its resource ids must
 * not be assumed to transfer (`<redacted-private-path>` §7.1).
 */
object NativePlaylistObserver {

	private val latch = NativePlaylistLatch()
	private val throttle = RepeatedDiagnosticThrottle()

	/** The playlist currently being played from, or null. */
	@Volatile
	private var held: NativePlaylistLatch.Held? = null

	fun current(): NativePlaylistLatch.Held? = held

	@Synchronized
	fun observe(result: NativePlaylistParser.Result, nowMillis: Long) {
		when (val decision = latch.observe(result, nowMillis)) {
			is NativePlaylistLatch.Decision.Latched -> {
				held = decision.held
				// A change of playlist is always worth a line; it is rare and it
				// explains every id that follows it.
				EventLog.append(
					"native-playlist",
					"${decision.reason} (position ${decision.held.position}/${decision.held.total})",
				)
			}

			is NativePlaylistLatch.Decision.Retained -> {
				held = decision.held
				emitThrottled(decision.reason, nowMillis)
			}

			is NativePlaylistLatch.Decision.Idle -> {
				held = null
				emitThrottled(decision.reason, nowMillis)
			}
		}
	}

	/** Monitoring stop, package opt-out or source-epoch change. */
	@Synchronized
	fun clear(reason: String) {
		val had = held != null
		latch.reset()
		throttle.reset()
		held = null
		if (had) EventLog.append("native-playlist", "cleared: $reason")
	}

	private fun emitThrottled(message: String, nowMillis: Long) {
		if (throttle.shouldEmit(message, nowMillis)) {
			EventLog.append("native-playlist", message)
		}
	}
}
