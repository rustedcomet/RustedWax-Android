package com.rustedwax.app.replay

import com.rustedwax.core.*
import com.rustedwax.app.detect.NativePreResolvedRoute

sealed interface PlaybackEvent {

	enum class Surface {
		FOREGROUND,
		BACKGROUND,
		PICTURE_IN_PICTURE,
	}

	/** Wall clock and, when playing, watched content both move forward. */
	data class Advance(val millis: Long) : PlaybackEvent

	/** The session published new metadata. Null fields mean "not published". */
	data class SessionMetadata(
		val title: String? = null,
		val artist: String? = null,
		val album: String? = null,
		val durationMs: Long? = null,
		val genre: String? = null,
		/** Exact id fields a native MediaSession may carry. */
		val mediaId: String? = null,
		val mediaUri: String? = null,
		val artUri: String? = null,
	) : PlaybackEvent

	/** Transport state changed. [speed] scales how fast content is consumed. */
	data class PlaybackStateChanged(
		val playing: Boolean,
		val positionMs: Long? = null,
		val speed: Double = 1.0,
		/** Native MediaSession STATE_STOPPED, distinct from an ordinary pause. */
		val stopped: Boolean = false,
	) : PlaybackEvent {
		init {
			require(!playing || !stopped) { "a transport cannot be PLAYING and STOPPED" }
		}
	}

	/** UI placement changed; ordinary MediaSession playback itself did not. */
	data class SurfaceChanged(val surface: Surface) : PlaybackEvent

	/** The address bar was read. Browser sources only. */
	data class UrlObserved(
		val host: String?,
		val videoId: String? = null,
		val isShort: Boolean = false,
		val playlistId: String? = null,
		val raw: String = "",
	) : PlaybackEvent

	/** A browser media notification named the page origin. */
	data class NotificationObserved(
		val host: String?,
		val title: String? = null,
		val text: String? = null,
	) : PlaybackEvent

	/** The notification for this session went away — tab closed, playback ended. */
	data object NotificationCleared : PlaybackEvent

	/**
	 * YouTube's own visible UI marked the current track as an advertisement.
	 *
	 * The literal on-screen label, because that is what the accessibility
	 * observer reads and what the refusal quotes back to the user.
	 */
	data class AdLabelObserved(val label: String) : PlaybackEvent

	/** A fresh successful scan of the visible YouTube root. */
	data class AccessibilityScan(val sawYouTubeRoot: Boolean) : PlaybackEvent

	/** The native watch screen showed a playlist bar. */
	data class NativePlaylistObserved(
		val name: String,
		val owner: String? = null,
		val total: Int? = null,
	) : PlaybackEvent

	/**
	 * A stable native track was identified while it was still playing.
	 *
	 * Memory-only carry authority — finalization must re-fetch it, which is the
	 * behaviour the `PLAYLIST` and `HISTORY` routes exist to re-prove.
	 */
	data class NativeIdentityPreResolved(
		val videoId: String,
		val route: NativePreResolvedRoute,
	) : PlaybackEvent

	/** Independent playback evidence attributed the current duration surface. */
	data class PresentationAttributionEstablished(
		val videoId: String,
		val durationMs: Long,
	) : PlaybackEvent

	/** The foreground Shorts observer read the player. */
	data class ForegroundShortObserved(
		val title: String?,
		val ownerHandle: String,
		val durationMs: Long? = null,
		/** Absolute seekbar position when this is a recovery observation. */
		val positionMs: Long = 0,
	) : PlaybackEvent

	data class ProgressSurfaceLost(
		/** Elapsed wall time covered by repeated production-style observer polls. */
		val inferredMs: Long = 0,
		val playing: Boolean = true,
		val displayOff: Boolean = false,
	) : PlaybackEvent

	/** Playback ran off the end of the item and back to its start. */
	data object LoopObserved : PlaybackEvent

	/** A candidate id was disproved while the track was still active. */
	data class VideoIdRejected(val videoId: String) : PlaybackEvent

	/**
	 * Android destroyed and rebuilt the media session mid-listen.
	 *
	 * Measurement and the frozen listen start survive; the transport does not.
	 * This is the event behind the "no duplicate transaction after
	 * MediaSession recreation" gate.
	 */
	data object SessionRecreated : PlaybackEvent

	/**
	 * A native replacement controller starts from the same metadata bundle but
	 * without the resolver-derived exact id held by the outgoing binding.
	 * Production can reclaim its continuation only after carry authority resolves.
	 */
	data object NativeSessionRecreatedAwaitingCarryAuthority : PlaybackEvent

	/** The probe binding is disposed and must run its real terminal policy. */
	data class ProbeDisposed(
		val finalize: Boolean = true,
		val allowContinuation: Boolean = false,
	) : PlaybackEvent

	/**
	 * The whole app process went away and came back.
	 *
	 * Everything held in memory is lost — including the run-local identity
	 * cache and the verified playback sequence — while the durable dedup ledger
	 * is not. Separating the two is the entire point of replaying it.
	 */
	data object ProcessRestarted : PlaybackEvent

	/**
	 * The native STOPPED replacement grace ran out.
	 *
	 * A trace has no `Handler`, so the deadline `SessionProbe` arms from
	 * `PlaybackEffect.ScheduleStoppedFinalizationGrace` is expressed as an
	 * explicit event. Whether it ends the listen is still
	 * `StoppedInterruption`'s decision, made against the same display state the
	 * probe reads on a device.
	 */
	data class StoppedGraceExpired(
		/** The screen was on, so this STOPPED is the user's own. */
		val displayInteractive: Boolean = true,
	) : PlaybackEvent

	/**
	 * The transport is rebuilt already knowing where the item came back.
	 *
	 * [SessionRecreated] models the rebuild that happens under a live listen,
	 * where the replacement has published no position yet. A session that
	 * reappears after an interruption has one — Android hands the fresh
	 * controller a `PlaybackState` — and past the metadata window that position
	 * is the whole evidence the claim runs on.
	 */
	data class SessionRecreatedAtPosition(val positionMs: Long) : PlaybackEvent

	/**
	 * The probe's idle timer fired.
	 *
	 * A trace has no `Handler`, so the deadline `SessionProbe` arms from
	 * `PlaybackReducer.idleFinalizeDelayMs` is expressed as an explicit event.
	 * Whether it ends the listen is still the reducer's decision.
	 */
	data object IdleDeadlineReached : PlaybackEvent

	/** The track ended, for the stated reason, and finalization runs. */
	data class Finalized(val reason: String = "track ended") : PlaybackEvent
}
