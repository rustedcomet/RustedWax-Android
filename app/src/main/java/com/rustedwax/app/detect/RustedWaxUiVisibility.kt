package com.rustedwax.app.detect

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
