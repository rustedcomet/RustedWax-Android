package com.rustedwax.app.detect

/**
 * Whether RustedWax's own main window currently owns the foreground.
 *
 * Both accessibility services periodically ask Android for the active root.
 * When this activity is foreground, that request cannot yield browser or native
 * YouTube evidence; it only forces Compose to serialize, compare and
 * geometrically sort RustedWax's full semantics tree on the drawing thread.
 * The field device measured that work as multi-second input starvation.
 *
 * This is a process-local lifecycle fact, not evidence about another app and
 * never a detection authority. It only prevents a provably useless root request.
 */
internal object RustedWaxUiVisibility {
	@Volatile
	var isResumed: Boolean = false
		private set

	fun resumed() {
		isResumed = true
	}

	fun paused() {
		isResumed = false
	}
}
