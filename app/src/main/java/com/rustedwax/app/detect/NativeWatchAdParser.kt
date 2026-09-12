package com.rustedwax.app.detect

import com.rustedwax.core.PlayerAdSurface

/**
 * One look at the ordinary native watch player's own advertisement controls.
 *
 * Android-free, like [NativeShortTree], so the reading is testable on the JVM.
 * The service fills it from exact view ids only; nothing here walks a tree.
 */
data class NativeWatchAdCapture(
	val packageName: String?,
	/** The watch player's overlay container is on screen at full size. */
	val watchPlayerVisible: Boolean,
	/**
	 * A surface whose labels do not describe the watch player at full size is in
	 * front: the Shorts player, or the minimized watch player.
	 */
	val otherPlayerSurfaceVisible: Boolean,
	/** Nodes found under [NativeWatchAdParser.AD_CONTROL_VIEW_IDS], as captured. */
	val adControlNodes: List<NativeShortNode>,
)

/**
 * What the native watch player said about advertising at one instant.
 *
 * During a pre-roll published under the upcoming video's own title, the player
 * can expose
 * `ad_progress_text` "Sponsored", `skip_ad_button_container` "Skip ad" and
 * `player_learn_more_button` "Visit advertiser". Both halves are required — the
 * player's own ad-control view id *and* one of [YouTubeAdDetector]'s exact labels —
 * so a promoted card below the player, a title containing "ad", or a channel name
 * can never be read as the player's state.
 *
 * Absence is claimed only when it was observable: the full-size watch player was
 * on screen and none of its ad controls was drawn at all. An ad control that is
 * drawn but reads as none of the exact labels proves nothing either way, and
 * neither does any other surface being in front.
 */
object NativeWatchAdParser {

	data class Reading(
		val surface: PlayerAdSurface,
		/** The exact label read, when [surface] is [PlayerAdSurface.VISIBLE]. */
		val signal: String? = null,
		val reason: String,
	)

	/**
	 * The watch player's own advertisement controls, by view id.
	 *
	 * `skip_ad_button_text` is deliberately absent: it reads a bare "Skip", and its
	 * container already carries the exact "Skip ad".
	 */
	val AD_CONTROL_VIEW_IDS: List<String> = listOf(
		"ad_progress_text",
		"skip_ad_button_container",
		"player_learn_more_button",
	)

	/** The full-size watch player's overlay container. */
	const val WATCH_PLAYER_VIEW_ID = "player_overlays"

	/** Surfaces in front of which the watch player's labels say nothing about playback. */
	val OTHER_PLAYER_SURFACE_VIEW_IDS: List<String> = listOf(
		"reel_watch_fragment_root",
		"modern_miniplayer",
		"floaty_bar_controls_view",
	)

	fun unobserved(reason: String) = Reading(PlayerAdSurface.UNOBSERVED, reason = reason)

	fun parse(capture: NativeWatchAdCapture): Reading {
		if (capture.packageName != YouTubeProbe.YOUTUBE_PACKAGE) {
			return Reading(PlayerAdSurface.UNOBSERVED, reason = "native YouTube is not in front")
		}
		val controls = capture.adControlNodes.filter { node ->
			node.visible && node.resourceId?.substringAfter(":id/") in AD_CONTROL_VIEW_IDS
		}
		controls.firstNotNullOfOrNull { node ->
			YouTubeAdDetector.signalFor(node.text) ?: YouTubeAdDetector.signalFor(node.contentDescription)
		}?.let { signal ->
			return Reading(
				PlayerAdSurface.VISIBLE,
				signal = signal,
				reason = "the watch player drew its ad label \"$signal\"",
			)
		}
		if (controls.isNotEmpty()) {
			return Reading(
				PlayerAdSurface.UNOBSERVED,
				reason = "a watch-player ad control is drawn without an exact ad label",
			)
		}
		if (capture.otherPlayerSurfaceVisible) {
			return Reading(
				PlayerAdSurface.UNOBSERVED,
				reason = "the Shorts player or the minimized watch player is in front",
			)
		}
		if (!capture.watchPlayerVisible) {
			return Reading(PlayerAdSurface.UNOBSERVED, reason = "the watch player is not on screen")
		}
		return Reading(PlayerAdSurface.ABSENT, reason = "the watch player drew no ad label")
	}
}
