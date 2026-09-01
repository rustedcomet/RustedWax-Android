package com.rustedwax.app.detect

import java.util.Locale

/**
 * Holds the playlist a native YouTube session is playing from.
 *
 * ## Why a latch rather than a per-observation read
 *
 * Accessibility is foreground-only. Backgrounded or with the screen off the
 * tree is gone entirely — the measured log line is
 * `no active native YouTube accessibility root` — yet playback continues and
 * tracks still have to resolve. The playlist is stable across every track in
 * it, so it is captured once and held.
 *
 * ## Why absence never drops it
 *
 * The first cut dropped the latch after 30 s without a playlist bar. Field
 * testing on 2026-08-04 killed that idea twice over:
 *
 * 1. YouTube collapsed to its **miniplayer** over the browse page. Playback
 *    continued from the playlist; the watch screen and its bar were gone. The
 *    latch dropped after 103 s, the next track fell through to search, and
 *    search resolved `Chulo Sin H` to `JZHOizv9G4E` — **not** the
 *    `2rZtRUDQXqg` that is actually in the playlist.
 * 2. The bar is **scroll-dependent**. The same video in the same session showed
 *    no playlist node scrolled down and a complete bar scrolled to the top.
 *
 * Ads and fullscreen remove it too. Four independent mechanisms hide the bar
 * while the user has not left the playlist, and none of them is distinguishable
 * from actually leaving. Absence is therefore not evidence, and this latch is
 * replaced only by *positive contradiction* — a different playlist name — or by
 * an explicit [reset] on session teardown, package opt-out, source-epoch change
 * or monitoring stop.
 *
 * ## Why holding a stale playlist is the safer error
 *
 * A stale latch cannot by itself produce a wrong scrobble: the resolver still
 * has to find exactly one entry in that playlist matching the finalized title,
 * artist and duration, and a track from somewhere else matches nothing and
 * falls through. Dropping the latch, by contrast, hands the track to the search
 * route — which has now been measured selecting the wrong upload three separate
 * times (`Unica`, `Criminal`, `Chulo Sin H`). Between a bounded miss and a
 * silent wrong id on an immutable chain, this errs toward the miss.
 */
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
