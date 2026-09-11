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

	/**
	 * What was measured under this title and then refused attribution, in ms.
	 *
	 * Non-zero only where [playedMs] was cleared because the source never
	 * established which presentation was the named work. It is not progress and
	 * must never be scored, broadcast, or added to [playedMs] — it may belong to
	 * an interstitial, which is exactly why the progress was cleared. Its only
	 * consumer is the refusal row, which would otherwise be suppressed by the
	 * notability floor and leave a listen with no explanation anywhere.
	 */
	val unattributedMeasuredMs: Long = 0,

	/**
	 * The part of [unattributedMeasuredMs] measured on the presentation this
	 * listen finalized on, in ms.
	 *
	 * Read only by finalization, and only where the resolver it was still waiting
	 * for has since pinned this very presentation to the named work — the same
	 * proof that would have cleared the refusal outright had it arrived while the
	 * track was still playing. It is not progress until then and is never scored,
	 * broadcast or added to [playedMs] by anything else.
	 */
	val refusedFinalPresentationMs: Long = 0,

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
	 * The id came from YouTube Music's own video shelf, over a row the player
	 * matched exactly on work, complete credit and running time.
	 *
	 * Kept distinct from [STRUCTURED_MUSIC] so finalization re-asks *that*
	 * question — the page's length and owner — instead of a title YouTube and
	 * YouTube Music spell differently for the same upload.
	 */
	MUSIC_VIDEO_ROW,

	/**
	 * The id came from the signed-in account's watch history.
	 *
	 * Kept distinct for the same reason as [PLAYLIST]: finalization re-asks the
	 * feed that produced it and requires the same id back, so a listen that
	 * history has since re-described refuses instead of carrying a stale answer.
	 */
	HISTORY,
}
