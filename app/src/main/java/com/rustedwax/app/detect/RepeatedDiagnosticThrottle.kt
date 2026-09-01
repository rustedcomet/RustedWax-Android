package com.rustedwax.app.detect

/** Pure bounded coalescing for a diagnostic that may arrive on every UI callback. */
class RepeatedDiagnosticThrottle(
	private val repeatIntervalMs: Long = DEFAULT_REPEAT_INTERVAL_MS,
) {
	private var lastMessage: String? = null
	private var lastEmittedAtMillis: Long = 0

	fun shouldEmit(message: String, nowMillis: Long): Boolean {
		val same = message == lastMessage
		val forwardTime = nowMillis >= lastEmittedAtMillis
		if (same && forwardTime && nowMillis - lastEmittedAtMillis < repeatIntervalMs) {
			return false
		}
		lastMessage = message
		lastEmittedAtMillis = nowMillis
		return true
	}

	fun reset() {
		lastMessage = null
		lastEmittedAtMillis = 0
	}

	companion object {
		const val DEFAULT_REPEAT_INTERVAL_MS = 30_000L
	}
}

/** Stable equivalence key for dynamic native-Short capture diagnostics. */
object NativeShortDiagnosticKey {
	fun of(message: String): String {
		val lifecycle = when {
			"foreground proof grace expired" in message -> "proof-expired"
			"progress frozen during bounded refresh grace" in message -> "proof-frozen"
			else -> "inactive"
		}
		val structural = when {
			"expected exactly one visible Shorts player root; found 0" in message ->
				"no-visible-player-root"
			"expected exactly one visible Shorts player; found 0" in message ->
				"no-visible-player"
			// The handle refusal now names what the footer held, which is the
			// whole point of it — but that detail changes with every Short, and
			// keying on it would turn a coalesced line into one per callback.
			// Key on the shape instead: absent and ambiguous are the two
			// failures, and they have unrelated fixes.
			"exact visible owner handle; found none" in message -> "no-owner-handle"
			"exact visible owner handle; found" in message -> "ambiguous-owner-handle"
			// Same reason: the seconds differ on every refused jump, and keying on
			// them would print one line per poll while scrubbing.
			"seekbar jumped" in message -> "refused-jump"
			else -> message
		}
		return "$lifecycle|$structural"
	}
}
