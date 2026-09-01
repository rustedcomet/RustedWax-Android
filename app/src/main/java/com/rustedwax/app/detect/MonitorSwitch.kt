package com.rustedwax.app.detect

import android.content.Context
import com.rustedwax.app.storage.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Stop button, as a piece of state.
 *
 * The UI and [RustedWaxListenerService] run in the same process, so this object
 * is the whole control channel between them — the activity flips it, the
 * service collects it and builds or tears down the probe. No intents, no
 * binder, no service restart.
 *
 * Backed by [Settings.monitoringEnabled] so a Stop survives the activity being
 * closed, the service being recycled by the system, and a reboot. The flow is
 * the source of truth at runtime; the preference is how it's remembered.
 *
 * Distinct from `autoScrobble`, which gates only the final broadcast step.
 * Stopped here means nothing is *read*: no session watching, no notification
 * inspection, no log entries.
 */
object MonitorSwitch {

	private var settings: Settings? = null

	private val _enabled = MutableStateFlow(true)

	/** Collected by the service; emits its current value on subscribe. */
	val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

	/**
	 * Loads the persisted state. Safe to call repeatedly — both the activity and
	 * the service call it, and either may be created first.
	 */
	@Synchronized
	fun init(context: Context) {
		if (settings == null) {
			settings = Settings(context.applicationContext).also {
				_enabled.value = it.monitoringEnabled
			}
		}
	}

	@Synchronized
	fun set(context: Context, value: Boolean) {
		init(context)
		if (_enabled.value == value) return
		settings?.monitoringEnabled = value
		EventLog.append("monitor", if (value) "started by user" else "stopped by user")
		// Written last: the collector in the service reacts to this, and it
		// should never see a value the preference doesn't already agree with.
		_enabled.value = value
	}

	val isEnabled: Boolean get() = _enabled.value
}
