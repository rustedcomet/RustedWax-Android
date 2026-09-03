package com.rustedwax.app.detect

import com.rustedwax.core.*
import com.rustedwax.core.SourceSessionId
import com.rustedwax.core.TrackInstanceId

data class SessionSnapshot(
	val packageName: String,
	val appLabel: String,
	/**
	 * True for the target browsers. Unsupported browsers are not watched rather
	 * than being observed and skipped later.
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

	val loopDetected: Boolean,

	val explicitAdSignal: String? = null,

	val browserEvidenceEnabled: Boolean = false,
	/** Fresh successful visible-YouTube-root scan frozen for this exact track. */
	val accessibilityCoverage: MediaSessionAccessibilityEvidence.Coverage? = null,
	val playbackState: String,

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

	val nativePlaylistName: String? = null,
	val nativePlaylistOwner: String? = null,
	val nativePlaylistTotal: Int? = null,
)

enum class NativePreResolvedRoute {
	RAW_TITLE_CHANNEL,
	STRUCTURED_MUSIC,

	PLAYLIST,

	/**
	 * The id came from the signed-in account's watch history.
	 *
	 * Kept distinct for the same reason as [PLAYLIST]: finalization re-asks the
	 * feed that produced it and requires the same id back, so a listen that
	 * history has since re-described refuses instead of carrying a stale answer.
	 */
	HISTORY,

	;

	/**
	 * Whether this route also proved the *currently published length* is the
	 * named work's own, and not an interstitial's borrowing its metadata.
	 *
	 * Only [STRUCTURED_MUSIC] does. Its matcher requires the player's published
	 * duration to agree with a fetched catalog row's own length before it will
	 * name an id at all — see [com.rustedwax.app.enrich.NativeStructuredMusicMatcher.matches]
	 * and the duration checks around `structuredNativeMusic = true` in
	 * [com.rustedwax.app.enrich.VideoIdResolver]. A pre-roll publishes the song's
	 * title and artist with its own short length, so it cannot satisfy that
	 * agreement, and the resolver refuses while one is on screen.
	 *
	 * The other three name the work from a feed, a history entry or a title and
	 * channel. Each can be right about *which song* while an interstitial is
	 * still what is playing, so none of them may attribute measured time.
	 */
	val corroboratesPresentationDuration: Boolean
		get() = this == STRUCTURED_MUSIC
}
