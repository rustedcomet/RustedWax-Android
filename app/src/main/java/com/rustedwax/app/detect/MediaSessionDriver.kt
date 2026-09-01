package com.rustedwax.app.detect

import android.media.MediaMetadata
import com.rustedwax.core.ListenState
import com.rustedwax.core.PlaybackEffect
import com.rustedwax.core.PlaybackInput
import com.rustedwax.core.PlaybackReducer

/**
 * Owns the one reducer state for an Android MediaSession binding.
 *
 * Android callbacks translate to [PlaybackInput.MetadataChanged],
 * [PlaybackInput.TransportChanged], or [PlaybackInput.SessionDestroyed]. The
 * reducer is the only state-machine authority; the binding only performs the
 * returned effects against Android and the run-scoped evidence coordinator.
 */
internal class MediaSessionDriver(
	private val reducer: PlaybackReducer,
	initialState: ListenState,
	private val perform: (PlaybackEffect, MediaMetadata?) -> Unit,
	private val afterOutermostDispatch: () -> Unit,
) {
	var state: ListenState = initialState
		private set

	private var dispatchDepth = 0

	/**
	 * Preserve reducer re-entrancy semantics: a nested finalization may advance
	 * [state] while an outer no-op transition is performing its effects.
	 */
	fun dispatch(input: PlaybackInput, incoming: MediaMetadata? = null) {
		val before = state
		val transition = reducer.reduce(before, input)
		dispatchDepth++
		try {
			transition.before.forEach { perform(it, incoming) }
			state = if (transition.state == before) state else transition.state
			transition.effects.forEach { perform(it, incoming) }
		} finally {
			dispatchDepth--
		}
		if (dispatchDepth == 0) afterOutermostDispatch()
	}
}
