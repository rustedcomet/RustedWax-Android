package com.rustedwax.app.detect

import com.rustedwax.core.*
import com.rustedwax.core.MetadataFields
import com.rustedwax.core.PlaybackSourceCapabilities
import com.rustedwax.core.SourceSessionId
import com.rustedwax.core.SourceDescriptor

interface SourceAdapter : com.rustedwax.android.sources.SourceAdapter {

	/** Which package this adapter speaks for, and how the log names it. */
	val packageName: String

	/** What a person calls this app. Display only. */
	val appLabel: String

	/** How the log names where a listen came from. Display only. */
	val originName: String

	/** Platform gateway name used only when rendering reducer diagnostics. */
	val transportDiagnosticName: String get() = "MediaSession"

	/** What the transport may be assumed to do. Consumed by [PlaybackReducer]. */
	override val playbackCapabilities: PlaybackSourceCapabilities

	/** What this source's own observations can establish. */
	val evidenceCapabilities: SourceEvidenceCapabilities

	/** What this source publishes for the payload. */
	val profile: SourceProfile

	/** What a host-lifecycle teardown does to a listen from this source. */
	val teardownPolicy: SourceTeardownPolicy

	override val descriptor: SourceDescriptor
		get() = SourceDescriptor(
			packageName = packageName,
			appLabel = appLabel,
			originName = originName,
			packageProvesSource = evidenceCapabilities.packageProvesSource,
			isWatched = true,
			hasBrowserEvidence = evidenceCapabilities.publishesHostScreenEvidence,
			trustsMetadataArtist = profile.trustsMetadataArtist,
		)

	// ── metadata interpretation ────────────────────────────────────────────

	/**
	 * This bundle as the presentation identity the reducer compares tracks by.
	 *
	 * The two sources disagree about which keys are readable at all: a browser's
	 * `DISPLAY_TITLE`/`DISPLAY_SUBTITLE` is the tab, and its `ARTIST` may be an
	 * origin, while a native app's are real. Answering that here is what lets
	 * `SessionProbe` hand the reducer one neutral [TrackIdentity].
	 */
	override fun trackIdentity(fields: MetadataFields?): TrackIdentity

	/** The item's title, or null when the bundle is describing something else. */
	fun presentedTitle(fields: MetadataFields?): String?

	/** The item's uploader/performer, or null when the field holds an origin. */
	fun presentedArtist(fields: MetadataFields?): String?

	/**
	 * The bundle names the host's own container — a browser tab — not a track.
	 *
	 * False for every source that has no such container.
	 */
	fun namesContainerOnly(fields: MetadataFields?): Boolean

	// ── identity ───────────────────────────────────────────────────────────

	/**
	 * What this source's own evidence says is playing.
	 *
	 * Pure with respect to the listen: the request carries everything about the
	 * listen the reading may consult, and the reply carries the resolver evidence
	 * this source contributed plus any [SourceEffect] the host owes. Latching and
	 * corroboration of the reply stay with the caller.
	 */
	fun readIdentity(request: SourceIdentityRequest): SourceIdentityReading

	override fun itemIdentity(fields: MetadataFields?) = readIdentity(
		SourceIdentityRequest(
			fields = fields,
			rejectedItemIds = emptySet(),
			resolverContext = ResolverContext(),
			soleSession = true,
		),
	).identity

	/**
	 * The host notification currently bound to this listen, for diagnostics.
	 *
	 * Read separately from [readIdentity] because the diagnostics card asks for it
	 * on every snapshot, and a snapshot must never re-run an identity read: that
	 * read advances resolver evidence, and a card refresh is not an observation.
	 */
	fun hostNotificationHint(
		fields: MetadataFields?,
		soleSession: Boolean,
	): NotificationHints.Hint?

	/**
	 * The listen has settled on an identity; what this source does about it.
	 *
	 * Separate from [readIdentity] because the settling itself — latching,
	 * corroboration, and rejected ids — belongs to the caller. What is *this
	 * source's* is what follows from the answer: a browser retries a parked carry
	 * the moment the address bar finally names the item, and looks for visible ad
	 * evidence filed against exactly that id.
	 */
	fun onIdentitySelected(request: SourceIdentitySelected): List<SourceEffect>

	/**
	 * Which of this source's live listens a host screen observation belongs to.
	 *
	 * The policy, not the plumbing. A browser routinely has several live sessions
	 * — YouTube playing in one tab while the user browses another — and choosing
	 * wrongly hands an ad label or a coverage proof to a video the user actually
	 * chose to watch. That choice is browser knowledge, so it lives behind the
	 * browser adapter; a first-party app has no host chrome and refuses outright.
	 *
	 * The host supplies immutable candidate facts and performs the answer. It does
	 * not decide.
	 */
	fun selectForHostObservation(request: SourceHostObservationRequest): SourceBinding

	// ── track-instance evidence lifecycle ──────────────────────────────────

	/** A track instance was established; bind instance-scoped evidence to it. */
	fun bindTrackInstance(instance: MediaSessionAdEvidence.TrackInstance, establishedAtMillis: Long)

	/** The instance is being replaced; unbind before the successor is bound. */
	fun unbindTrackInstance(instance: MediaSessionAdEvidence.TrackInstance)

	/** The listen is over or the watcher is gone; release everything instance-scoped. */
	fun releaseTrackInstance(instance: MediaSessionAdEvidence.TrackInstance)

	/** Fresh screen coverage frozen for this exact instance, or null when this source has none. */
	fun coverageFor(request: SourceCoverageRequest): MediaSessionAccessibilityEvidence.Coverage?

	/** Re-establish instance-scoped evidence carried across a session recreation. */
	fun restoreCarriedEvidence(request: SourceCarryRestoreRequest): SourceCarryRestoreResult

	// ── host observations ──────────────────────────────────────────────────

	/** A visible ad label naming an item. Null when it does not belong to this listen. */
	fun bindVisibleAdEvidence(request: SourceVisibleAdRequest): SourceAdVerdict

	/** An id-less ad label from the host's media surface. */
	fun bindHostAdLabel(request: SourceHostAdLabelRequest): SourceAdVerdict

	/** One successful host screen scan: coverage, and independently ad input. */
	fun bindScreenScan(request: SourceScreenScanRequest): SourceScreenScanVerdict

	// ── exact-id pre-resolution ────────────────────────────────────────────

	/**
	 * Whether a stable presentation with no exact item id may be pre-resolved.
	 *
	 * A browser keeps its exact id in the address bar and never needs this. A
	 * native app publishes no id at all, so a bounded lookup is the only thing
	 * that can give a replacement controller something to carry progress by.
	 */
	fun mayPreResolveExactItemId(request: SourceExactIdRequest): Boolean

	// ── diagnostics and package-scoped state ───────────────────────────────

	/** Lines this source is entitled to write about itself at [moment]. */
	fun notes(moment: SourceMoment): List<SourceNote>

	/** Package-scoped observation state this source owns, reset by its host. */
	fun onPackageStateReset()
}

/**
 * What a source's own observations can establish, as opposed to what its
 * transport measures ([PlaybackSourceCapabilities]).
 *
 * Named separately for the same reason the four playback capabilities are: a
 * future source may prove its identity by package while publishing no screen
 * evidence at all, and a single `isNative` cannot express that.
 */
data class SourceEvidenceCapabilities(
	/**
	 * The package itself proves which service is playing.
	 *
	 * True for a first-party app. False for a browser, which publishes a
	 * MediaSession for *any* site and has to earn the right to be described in
	 * the log at all — §4.1, and the page-title leak that section removed.
	 */
	val packageProvesSource: Boolean,

	/**
	 * Address-bar, tab, host-notification and host-screen evidence describe this
	 * source's playback.
	 *
	 * False for every first-party app: a browser's chrome says nothing about what
	 * the YouTube app is doing.
	 */
	val publishesHostScreenEvidence: Boolean,

	/**
	 * The transport publishes a structured state worth dumping into diagnostics.
	 *
	 * A native session's `PlaybackState` carries actions, extras and a real error
	 * surface; a browser's is three fields and a position.
	 */
	val publishesStructuredTransportDump: Boolean,

	/**
	 * Listens from this source belong to a switch generation that can invalidate
	 * them mid-flight.
	 *
	 * Only an independently opted-in source has one. A listen stamped with a
	 * superseded epoch is discarded rather than finalized.
	 */
	val scopedBySourceEpoch: Boolean,

	/**
	 * This source's app also presents foreground Shorts, whose structural proof
	 * takes ownership of the player away from this transport.
	 *
	 * Declared rather than asked of a package constant so the handover has one
	 * owner. False for a browser — a Short in a tab is an ordinary browser listen
	 * with a `/shorts/` path — and false for YouTube Music, which has no such
	 * surface, so the two of them never see a suppression meant for the third.
	 */
	val presentsForegroundShorts: Boolean,

	/**
	 * This source's app draws its own advertisement controls over the player this
	 * transport describes, and the separately granted native observer reads them.
	 *
	 * The label is what marks which interval of the transport's playback is an
	 * advertisement, which its metadata cannot. False for a browser, whose ad
	 * labels bind through its own id- and token-scoped route, and for YouTube
	 * Music, whose player the observer cannot see at all.
	 */
	val presentsWatchPlayerAdSurface: Boolean = false,
)

/** What a host-lifecycle teardown does to a listen from this source. */
enum class SourceTeardownPolicy {
	/**
	 * Score what is in flight.
	 *
	 * The listener being recycled mid-playback is the system's doing, not the
	 * track ending, and a browser listen survives it: its identity is in the
	 * address bar, which is still there afterwards.
	 */
	FINALIZE_IN_FLIGHT,

	/**
	 * Discard what is in flight and reset this package's observation state.
	 *
	 * A native listen's identity lives in resolver-derived evidence with a
	 * generation attached, so a teardown is a hard boundary rather than a pause.
	 */
	DISCARD_AND_RESET,
}

/** A moment in a listen's lifecycle a source may want to describe. */
enum class SourceMoment {
	/** The listen has earned the right to be named in the log. */
	ANNOUNCED,

	/** The listen has been frozen and handed on. */
	FINALIZED,
}

/** One diagnostic line a source is entitled to write about itself. */
data class SourceNote(val tag: String, val message: String)

/**
 * Something a source's reading asks its host to do.
 *
 * The [PlaybackEffect] pattern, applied to evidence: an adapter decides, the
 * host performs, and the vocabulary cannot express broadcasting, queueing,
 * dedup or finalization.
 */
sealed interface SourceEffect {

	/**
	 * A replacement transport can now name itself, so a parked continuation may
	 * be claimable that was not claimable a moment ago.
	 */
	data object RetryCarriedProgressClaim : SourceEffect

	/** Bind this already-matched visible ad evidence to the listen. */
	data class BindVisibleAdEvidence(val evidence: AdEvidence.Evidence) : SourceEffect
}

/** Everything a source may consult about a listen when reading identity. */
data class SourceIdentityRequest(
	val fields: MetadataFields?,
	/** Item ids this listen has already disproved; not evidence any more. */
	val rejectedItemIds: Set<String>,
	/** Resolver evidence accumulated for this listen so far. */
	val resolverContext: ResolverContext,
	/**
	 * This source has exactly one live listen in its host, so a host notification
	 * cannot belong to a different one.
	 */
	val soleSession: Boolean,
)

data class SourceIdentityReading(
	val identity: com.rustedwax.core.ItemIdentity,
	/** Resolver evidence after this source's contribution, in place of the request's. */
	val resolverContext: ResolverContext,
	/** The host notification bound to this listen, for diagnostics. */
	val notificationHint: NotificationHints.Hint? = null,
	/** Work the host owes, in order. */
	val effects: List<SourceEffect> = emptyList(),
)

/** The identity a listen settled on, offered back to the source that read it. */
data class SourceIdentitySelected(
	val identity: com.rustedwax.core.ItemIdentity,
	/** This is the first confirmation this listen has reached. */
	val justConfirmed: Boolean,
)

/** One live listen, reduced to the facts a binding choice depends on. */
data class SourceObservationCandidate(
	/** The host's own handle for this listen; this contract never learns what it is. */
	val key: Long,
	/** This listen is the one playing the item the observation named. */
	val describesNamedItem: Boolean = false,

	val finalized: Boolean = false,
	/** The transport is playing. Carried so a refusal can say what the ambiguity was. */
	val playing: Boolean = true,
)

/** A host screen observation offered to one source's live listens. */
data class SourceHostObservationRequest(
	/** The item the observation itself named, or null when it named none. */
	val namedItemId: String?,
	val candidates: List<SourceObservationCandidate>,
)

/** Which listen an observation was bound to, or why it was refused. */
sealed interface SourceBinding {

	data class Bound(val key: Long, val namedThisInstance: Boolean) : SourceBinding

	data class Refused(val reason: String) : SourceBinding
}

/** What a source needs to answer "is there fresh screen coverage for this instance". */
data class SourceCoverageRequest(
	val instance: MediaSessionAdEvidence.TrackInstance,
	/** The URL generation this listen believes it is playing under. */
	val expectedUrlGeneration: Long?,
	/** Coverage this listen already holds, which may still be current. */
	val localCoverage: MediaSessionAccessibilityEvidence.Coverage?,
	val nowMillis: Long,
)

/** Instance-scoped evidence recovered from a carry, offered back to its source. */
data class SourceCarryRestoreRequest(
	val instance: MediaSessionAdEvidence.TrackInstance,
	val establishedAtMillis: Long,
	val explicitAdSignal: String?,
	val accessibilityCoverage: MediaSessionAccessibilityEvidence.Coverage?,
	val atMillis: Long,
)

/** What the source accepted back. */
data class SourceCarryRestoreResult(
	val accessibilityCoverage: MediaSessionAccessibilityEvidence.Coverage? = null,
)

/** A visible ad label offered to one listen. */
data class SourceVisibleAdRequest(
	val evidence: AdEvidence.Evidence,
	/** The identity this listen is currently playing, if any. */
	val identity: com.rustedwax.core.ItemIdentity?,
	/** The signal this listen already holds, so a repeat is silent. */
	val currentSignal: String?,
)

/** An id-less ad label from the host's own media surface. */
data class SourceHostAdLabelRequest(
	val observation: MediaSessionAdEvidence.Observation,
	val instance: MediaSessionAdEvidence.TrackInstance,
	/** The listen was established before the label appeared and is playing. */
	val instanceEstablishedBeforeObservation: Boolean,
	val currentSignal: String?,
)

/** One successful host screen scan offered to one listen. */
data class SourceScreenScanRequest(
	val scan: MediaSessionAccessibilityEvidence.Scan,
	val instance: MediaSessionAdEvidence.TrackInstance,
	/** This source has exactly one live listen in its host. */
	val unambiguous: Boolean,
	/** The scan named the very item this listen is playing. */
	val namedThisInstance: Boolean,
	/** See [SourceHostAdLabelRequest.instanceEstablishedBeforeObservation]. */
	val instanceEstablishedBeforeObservation: Boolean,
	val currentSignal: String?,
)

/** Whether a listen adopts an ad signal, and what to say about it. */
data class SourceAdVerdict(
	/** The signal to adopt, or null to leave the listen's own unchanged. */
	val signal: String? = null,
	val notes: List<SourceNote> = emptyList(),
) {
	companion object {
		val NONE = SourceAdVerdict()
	}
}

/** What a screen scan established for one listen. */
data class SourceScreenScanVerdict(
	val coverage: MediaSessionAccessibilityEvidence.Coverage? = null,
	val ad: SourceAdVerdict = SourceAdVerdict.NONE,
)

/** A stable presentation offered for bounded exact-id pre-resolution. */
data class SourceExactIdRequest(
	val fields: MetadataFields?,
	val durationMs: Long?,
)
