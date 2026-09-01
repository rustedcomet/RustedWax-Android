package com.rustedwax.app.detect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lets the UI observe the probe that the listener service owns.
 *
 * Detection lives in [RustedWaxListenerService] so it keeps running
 * with the app closed, but the "Now playing" screen still needs to see it. The
 * service publishes its probe here; the activity reads it and renders whatever
 * is present. When the service isn't connected the value is null and the UI
 * says so, rather than silently showing an empty list.
 */
object ProbeHolder {

	private val _probe = MutableStateFlow<SessionProbe?>(null)
	val probe: StateFlow<SessionProbe?> = _probe.asStateFlow()

	fun set(value: SessionProbe?) {
		_probe.value = value
	}

	val current: SessionProbe? get() = _probe.value
}
