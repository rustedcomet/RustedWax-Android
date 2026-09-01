package com.rustedwax.app.detect

import java.util.Locale

class NativePlaylistLatch {

	data class Held(
		val playlistName: String,
		val ownerName: String?,
		val position: Int,
		val total: Int,
		val observedAtMillis: Long,
	)

	sealed interface Decision {
		/** Newly latched, or replaced by a positively different playlist. */
		data class Latched(val held: Held, val reason: String) : Decision

		/** Still latched — re-seen, or absent for a reason that proves nothing. */
		data class Retained(val held: Held, val reason: String) : Decision

		/** Nothing latched and nothing to latch. */
		data class Idle(val reason: String) : Decision
	}

	private var held: Held? = null

	val current: Held? get() = held

	fun reset() {
		held = null
	}

	fun observe(result: NativePlaylistParser.Result, nowMillis: Long): Decision =
		when (result) {
			is NativePlaylistParser.Result.Context -> onContext(result, nowMillis)

			// Both absence flavours are kept apart for the event log — one means
			// "watch screen readable, no bar on it", the other "nothing readable
			// at all" — but neither may disturb the latch.
			is NativePlaylistParser.Result.NoPlaylist -> retainOrIdle(result.reason)
			is NativePlaylistParser.Result.Unobservable -> retainOrIdle(result.reason)
		}

	private fun onContext(
		context: NativePlaylistParser.Result.Context,
		nowMillis: Long,
	): Decision {
		val next = Held(
			playlistName = context.playlistName,
			ownerName = context.ownerName,
			position = context.position,
			total = context.total,
			observedAtMillis = nowMillis,
		)
		val previous = held
		held = next
		return when {
			previous == null ->
				Decision.Latched(next, "latched native playlist \"${context.playlistName}\"")

			!sameName(previous.playlistName, context.playlistName) ->
				Decision.Latched(
					next,
					"native playlist changed \"${previous.playlistName}\" → " +
						"\"${context.playlistName}\"",
				)

			else -> Decision.Retained(next, "native playlist bar re-observed")
		}
	}

	private fun retainOrIdle(reason: String): Decision =
		held?.let { Decision.Retained(it, "playlist bar not on screen: $reason") }
			?: Decision.Idle(reason)

	private fun sameName(a: String, b: String): Boolean =
		a.trim().lowercase(Locale.ROOT) == b.trim().lowercase(Locale.ROOT)
}
