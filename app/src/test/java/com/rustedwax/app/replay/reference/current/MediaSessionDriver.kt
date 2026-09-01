package com.rustedwax.app.replay.reference.current

import com.rustedwax.core.ListenState
import com.rustedwax.core.PlaybackEffect
import com.rustedwax.core.PlaybackInput
import com.rustedwax.core.PlaybackReducer
import com.rustedwax.app.replay.reference.phase01.MediaMetadata

/** JVM-platform mirror of the shipping driver; only MediaMetadata is substituted. */
internal class MediaSessionDriver(
	private val reducer: PlaybackReducer,
	initialState: ListenState,
	private val perform: (PlaybackEffect, MediaMetadata?) -> Unit,
	private val afterOutermostDispatch: () -> Unit,
) {
	var state: ListenState = initialState
		private set
	private var dispatchDepth = 0

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
