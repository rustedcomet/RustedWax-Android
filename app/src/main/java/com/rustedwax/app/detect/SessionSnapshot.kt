package com.rustedwax.app.detect

import com.rustedwax.core.*
import com.rustedwax.core.SourceSessionId
import com.rustedwax.core.TrackInstanceId

/**
 * One media session as observed right now.
 *
 * Field names deliberately echo `HiveScrobblePayload` (title / artist / album /
 * duration / percent_played / url) so the diagnostics read as "here is the
 * payload we could have built, and here is what's missing."
 */
data class SessionSnapshot(
	val packageName: String,
	val appLabel: String,
	/**
	 * True for the target browsers. Always true since Phase 4 — anything else
	 * is no longer watched at all, rather than watched and skipped.
	 */
	val isTarget: Boolean,
	val title: String?,
	val artist: String?,
	val album: String?,
	val durationMs: Long?,
	val positionMs: Long?,
	/**
	 * Content consumed in this track — time in STATE_PLAYING scaled by the
	 * playback rate. Drives the 60% rule, so it has to be in the same units as
	 * [durationMs]. Measurement is owned by [com.rustedwax.core.PlaybackReducer].
	 */
	val playedMs: Long,
	/**
	 * True when playback was observed moving from the end of this media item
	 * back to its beginning during the same continuous viewing. Carried across
	 * Chromium media-session recreation.
	 */
	val loopDetected: Boolean,
	/**
	 * Exact visible YouTube UI label bound to this track instance as an
	 * advertisement, or null when no explicit label was observed.
	 */
	val explicitAdSignal: String? = null,
	/** Browser evidence access was enabled for the monitoring run that observed this track. */
	val browserEvidenceEnabled: Boolean = false,
	/** Fresh successful visible-YouTube-root scan frozen for this exact track. */
	val accessibilityCoverage: MediaSessionAccessibilityEvidence.Coverage? = null,
	val playbackState: String,
	/**
	 * The Short is still playing but its progress surface has gone away, so
	 * nothing further can be measured.
	 *
	 * Measured on 2026-08-05 in picture-in-picture: the accessibility tree keeps
	 * `reel_watch_fragment_root`, `reel_watch_player` and `reel_time_bar`, but
	 * the time bar loses its `SeekBar` child and no time text exists anywhere in
	 * the window, while YouTube's MediaSession reports `STATE_NONE` with
	 * `position=0`. Neither source can say how much was played.
	 *
	 * This is not the same as "0% was played", and reporting it as such is what
	 * made a PiP session indistinguishable from a parser bug for most of a day.
	 */
	val foregroundProgressLost: Boolean = false,
	/**
	 * The part of [playedMs] that was inferred rather than measured.
	 *
	 * Non-zero only for a foreground Short that continued in picture-in-picture,
	 * where no progress source exists at all and elapsed wall-clock was credited
	 * on the evidence that YouTube still held a visible window and media audio
	 * was started. See [PipPlaybackInference] for what that evidence can and
	 * cannot prove.
	 *
	 * Carried separately so every listen can say how much of it was watched on a
	 * seekbar we could read and how much was deduced. A scrobble built on this
	 * is still a claim about a real listen — it is just a weaker one, and the log
	 * has to be able to say so.
	 */
	val inferredPlayedMs: Long = 0,
	val isPlaying: Boolean,
	/** playedMs / durationMs; null when duration is unknown. */
	val percentPlayed: Double?,
	val identity: YouTubeProbe.Identity,
	/**
	 * Resolver inputs frozen while this track was active. Asynchronous
	 * finalization must never consult the package's later foreground URL.
	 */
	val resolverContext: ResolverContext = ResolverContext(),
	/** Most recent browser media notification seen for this package, if any. */
	val notificationHint: NotificationHints.Hint?,
	val metadataLines: List<String>,
	/**
	 * The first readable transport position seen for this listen, or null when
	 * the source never published one.
	 *
	 * Null is a statement about the *evidence*, not about the playback: a session
	 * reporting `pos=-1` for its whole life has been measured entirely from wall
	 * clock. `ScrobbleRules.capForKind` refuses to mint a second transaction on
	 * that basis — see the 2026-08-12 `Cry Baby` case recorded there.
	 */
	val firstObservedPositionMs: Long? = null,
	val trackStartedAtEpochSec: Long,
	/**
	 * Which listen this is, as a value a wall-clock second cannot collide on.
	 *
	 * Allocated from `MediaSessionAdEvidence.nextTrackToken()` when the track
	 * instance is established, and **carried across MediaSession recreation** by
	 * `TrackProgressCarry`, so one continuous listen keeps one token however
	 * many times Chromium rebuilds its session underneath it.
	 *
	 * Null only for a snapshot built by something that publishes no instance of
	 * its own; both production producers stamp it. See
	 * [com.rustedwax.core.TrackInstanceId] for what depends on it and what
	 * the fallback costs.
	 */
	val trackInstanceToken: Long? = null,
	/** Structured native metadata that can explicitly identify podcast/episode context. */
	val genre: String? = null,
	/** Native source generation; invalidated by opt-out, Stop and listener reconnect. */
	val sourceEpoch: Long? = null,
	/** Literal observer provenance; never inferred from a resolver result. */
	val sourceProof: SourceProof = SourceProof.MEDIA_SESSION,
	/** Exact accessibility owner handle for the native foreground-Short route. */
	val ownerHandle: String? = null,
	/** Automatic-write authority frozen when this logical listen began. */
	val automaticWriteAuthorization: AutomaticWriteAuthorization =
		AutomaticWriteAuthorization.LegacyEnabled,
) {
	val confirmed: YouTubeProbe.Identity.Confirmed?
		get() = identity as? YouTubeProbe.Identity.Confirmed

	/** The source generation this listen belongs to. */
	val sourceSession: SourceSessionId
		get() = SourceSessionId(packageName, sourceEpoch)

	/**
	 * Which listen this is. Falls back to the frozen start second — and to the
	 * same-second collision that goes with it — only when no token was stamped.
	 */
	val trackInstance: TrackInstanceId
		get() = trackInstanceToken
			?.let { TrackInstanceId(packageName, it) }
			?: TrackInstanceId.fromStartSecond(packageName, trackStartedAtEpochSec)

	/** True when the site is proven YouTube, with or without a video id. */
	val isYouTube: Boolean
		get() = identity is YouTubeProbe.Identity.Confirmed ||
			identity is YouTubeProbe.Identity.SiteOnly

	val origin: YouTubeProbe.Origin get() = YouTubeProbe.originForPackage(packageName)

	/**
	 * What this session's source can be relied on to publish.
	 *
	 * Read rather than branched on: asking a package-specific question at a decision
	 * site spreads one source's quirks across the codebase, which is how the
	 * §3.2 artist bug survived — a source-kind check stood in for a
	 * question only one of the two native packages answered the same way.
	 */
	val profile: SourceProfile get() = SourceProfile.forPackage(packageName)

	val isForegroundShort: Boolean
		get() = sourceProof == SourceProof.NATIVE_FOREGROUND_SHORT

	/** Browser path proof or the separately proven native foreground player. */
	val hasShortSourceProof: Boolean
		get() = isForegroundShort || confirmed?.isShort == true

	/** Progress-only threshold check; final eligibility still belongs to ScrobbleRules. */
	fun reachedThreshold(threshold: Double): Boolean =
		(percentPlayed ?: 0.0) >= threshold

	/**
	 * Content the player had already passed that this listen never measured.
	 *
	 * Zero for the ordinary case of a track watched from its start, so it is
	 * silent unless it has something to say.
	 *
	 * [firstObservedPositionMs] is where this listen was first shown the player.
	 * The value is only as good as the position that established it, and a source that publishes an interstitial's own position
	 * under the video's title can land it on material that was then discarded.
	 * Measured 2026-08-28: YouTube resumed a 1,192-second video 17:48 in, played a
	 * 27-second and a 6-second interstitial, then ran the last 124 s to the end.
	 * First-seen was **6 s** — the trailing position of the six-second
	 * interstitial — so nothing was said, and "played 10%" read as a lost
	 * measurement rather than a resume.
	 *
	 * The reducer therefore refuses to let that trailing position establish the
	 * lead-in at all — see the supersede branch in `PlaybackReducer` — so what
	 * reaches here is the position of the work itself.
	 */
	val unobservedLeadInMs: Long
		get() {
			val firstSeen = firstObservedPositionMs ?: return 0
			if (firstSeen < UNOBSERVED_LEAD_IN_MS) return 0
			// A listen whose progress was carried across a replacement session has
			// already accounted for its lead-in; only an unexplained one is news.
			if (playedMs >= firstSeen) return 0
			return firstSeen
		}

	companion object {
		/** Below this, a mid-track start is ordinary timing rather than a resume. */
		const val UNOBSERVED_LEAD_IN_MS = 10_000L
	}
}

enum class SourceProof {
	MEDIA_SESSION,
	NATIVE_FOREGROUND_SHORT,
}

/** Immutable URL, playlist and cache evidence carried into finalization. */
data class ResolverContext(
	val playlistId: String? = null,
	/**
	 * Duration published by the currently visible source presentation.
	 *
	 * [SessionSnapshot.durationMs] is the conservative established duration used
	 * for listen measurement. A source may publish the same logical work as two
	 * exact catalog items with different lengths, so identity resolution needs
	 * the current item's own length without weakening the threshold denominator.
	 */
	val presentationDurationMs: Long? = null,
	val urlGeneration: Long? = null,
	val observedVideoId: String? = null,
	val observedUrl: String? = null,
	val knownTitle: String? = null,
	val knownChannel: String? = null,
	val knownDurationSeconds: Long? = null,
	val rejectedVideoIds: Set<String> = emptySet(),
	/**
	 * Of [rejectedVideoIds], the ones rejected purely because a page title and a
	 * session title were written differently. Never a licence on its own — see
	 * [com.rustedwax.app.enrich.VideoIdentityCorroborator].
	 */
	val titleOnlyRejectedVideoIds: Set<String> = emptySet(),
	/**
	 * Unique structured id proven while an exact-ID-less native track was still
	 * playing. Memory-only carry authority; finalization must re-fetch it.
	 */
	val preResolvedNativeVideoId: String? = null,
	/** The resolver predicate whose uniqueness proof authorized that id. */
	val preResolvedNativeRoute: NativePreResolvedRoute? = null,
	/**
	 * Playlist bar name read off the native watch screen.
	 *
	 * Kept separate from [playlistId], which stays exclusively browser
	 * address-bar evidence, so a native observation can never be mistaken for a
	 * proven URL. Resolved to an id at finalization
	 * (`<redacted-private-path>` §7).
	 */
	val nativePlaylistName: String? = null,
	val nativePlaylistOwner: String? = null,
	val nativePlaylistTotal: Int? = null,
)

enum class NativePreResolvedRoute {
	RAW_TITLE_CHANNEL,
	STRUCTURED_MUSIC,

	/**
	 * The id came from the bounded entry list of the playlist being played.
	 *
	 * Kept distinct so finalization re-verifies against that same playlist.
	 * Re-deriving it from the watch page instead loses the playlist's own
	 * channel evidence, which is what silently dropped `Te Busco` /
	 * `7J6xA1_f8as` on 2026-08-04.
	 */
	PLAYLIST,

	/**
	 * The id came from the signed-in account's watch history.
	 *
	 * Kept distinct for the same reason as [PLAYLIST]: finalization re-asks the
	 * feed that produced it and requires the same id back, so a listen that
	 * history has since re-described refuses instead of carrying a stale answer.
	 */
	HISTORY,
}
