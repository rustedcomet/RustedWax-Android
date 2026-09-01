package com.rustedwax.app.scrobble

import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.detect.FinalizedTrack
import com.rustedwax.app.detect.NativeSourceSwitches
import com.rustedwax.app.detect.ScrobbleBuilder
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.app.enrich.FinalizedVideoIdentityContract
import com.rustedwax.app.enrich.MusicBrainzVerifier
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.youtube.identity.VideoResolution
import com.rustedwax.youtube.identity.VideoResolutionAttempt
import com.rustedwax.hive.HiveScrobblePayload
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Source-specific identity work. Shared policy never parses the returned id. */
internal fun interface IdentityService {
	suspend fun resolve(
		session: SessionSnapshot,
		sequence: VerifiedPlaybackSequence,
		history: WatchHistorySource,
	): VideoResolutionAttempt
}

/** Network/cache enrichment, kept separate from identity and policy. */
internal interface EnrichmentService {
	suspend fun facts(videoId: String): VideoFacts?
	suspend fun music(session: SessionSnapshot, facts: VideoFacts?): MusicBrainzVerifier.Match?
}

/** Identity corroboration, cache ownership, and the user's item veto. */
internal interface ClassificationService {
	fun contradiction(
		session: SessionSnapshot,
		resolution: VideoResolution,
		facts: VideoFacts?,
	): String?

	fun rememberVerified(
		session: SessionSnapshot,
		resolution: VideoResolution,
		facts: VideoFacts?,
		shadow: Boolean,
	)

	fun isMuted(sourceItemId: String): Boolean
}

/** The only owner of progress, duration-floor, ad, and Short eligibility. */
internal interface EligibilityPolicy {
	fun prefilter(session: SessionSnapshot): String?
	fun decide(session: SessionSnapshot, facts: VideoFacts?, durationMs: Long?): ScrobbleRules.Decision
	fun prefilter(track: FinalizedTrack): String?
	fun decide(track: FinalizedTrack, durationMs: Long?): ScrobbleRules.Decision
}

/** Builds and caps the exact ordered payload bytes for an eligible listen. */
internal interface PayloadFactory {
	fun effectiveDurationMs(session: SessionSnapshot, facts: VideoFacts?): Long?
	fun build(
		session: SessionSnapshot,
		facts: VideoFacts?,
		music: MusicBrainzVerifier.Match?,
		identity: VideoResolution,
		durationMs: Long?,
	): HiveScrobblePayload?

	fun cap(
		decision: ScrobbleRules.Decision,
		payload: HiveScrobblePayload,
		session: SessionSnapshot,
	): List<Int>

	fun buildSourceNeutral(track: FinalizedTrack, durationMs: Long?): HiveScrobblePayload?
	fun cap(
		decision: ScrobbleRules.Decision,
		payload: HiveScrobblePayload,
		track: FinalizedTrack,
	): List<Int>
}

/** Transport is downstream of the one terminal finalization decision. */
internal interface ScrobbleDispatcher {
	val hasPostingAccount: Boolean

	fun claim(
		claims: DedupClaims,
		payload: HiveScrobblePayload,
		startedAtEpochSec: Long,
	): Boolean

	fun release(
		claims: DedupClaims,
		payload: HiveScrobblePayload,
		startedAtEpochSec: Long,
	)

	fun dispatch(
		payload: HiveScrobblePayload,
		sourceItemId: String,
		sourcePackage: String,
		sourceEpoch: Long?,
		automaticTransportCommitted: Boolean,
		trigger: FinalizationTrigger,
		onFeedback: ((String, Boolean) -> Unit)?,
	)

	/** Retry already-serialized bytes. This method has no finalized-track input. */
	fun retryDue()
}

/** Product-facing records and diagnostics owned outside the use case. */
internal interface FinalizationEffects {
	fun skip(
		report: FinalizationReport,
		session: SessionSnapshot,
		reason: String,
		durationMs: Long? = session.durationMs,
		log: Boolean = true,
		videoId: String? = session.confirmed?.videoId,
		resolvedTitle: String? = null,
	)

	fun couldBecomeUserFacingSkip(session: SessionSnapshot): Boolean
	fun noteIdentity(session: SessionSnapshot, sourceItemId: String?, shadow: Boolean)
}

/**
 * The single production finalization orchestration for every supported trigger.
 * Trigger changes dispatch presentation/queueing only; every identity,
 * enrichment, classification, policy, and payload stage is shared. Automatic
 * is the live production caller today; manual and shadow remain behaviorally
 * tested modes without a current UI or service caller.
 */
internal class ProductionFinalizationOrchestrator(
	private val scope: CoroutineScope,
	private val settings: ScrobblePolicy,
	private val sequence: VerifiedPlaybackSequence,
	private val claims: DedupClaims,
	private val history: WatchHistorySource,
	private val identity: IdentityService,
	private val enrichment: EnrichmentService,
	private val classification: ClassificationService,
	private val eligibility: EligibilityPolicy,
	private val payloads: PayloadFactory,
	private val dispatcher: ScrobbleDispatcher,
	private val effects: FinalizationEffects,
	private val observer: () -> FinalizationObserver,
) : FinalizationOrchestrator {
	/** The production boundary is the source-neutral Phase 2 domain object. */
	override fun execute(
		track: FinalizedTrack,
		trigger: FinalizationTrigger,
		onFeedback: ((String, Boolean) -> Unit)?,
		onOutcome: ((FinalizationOutcome) -> Unit)?,
	) {
		if (track.requiresCompatibilityPipeline || track.evidence.isYouTube) {
			execute(track.toSessionSnapshot(), trigger, onFeedback)
			return
		}
		executeSourceNeutral(track, trigger, onFeedback, onOutcome)
	}

	/** Non-YouTube sources spend only their own frozen, adapter-published identity. */
	private fun executeSourceNeutral(
		track: FinalizedTrack,
		trigger: FinalizationTrigger,
		onFeedback: ((String, Boolean) -> Unit)?,
		onOutcome: ((FinalizationOutcome) -> Unit)?,
	) {
		val filed = AtomicBoolean(false)
		val runClaims = if (trigger == FinalizationTrigger.SHADOW) ShadowClaims(claims) else claims
		fun file(outcome: FinalizationOutcome) {
			if (filed.compareAndSet(false, true)) onOutcome?.invoke(outcome)
		}
		if (!settings.monitoringEnabled) {
			file(FinalizationOutcome.Ignored("monitoring stopped"))
			return
		}
		if (trigger != FinalizationTrigger.MANUAL &&
			!settings.authorizesAutomaticWrite(track.automaticWriteAuthorization)
		) {
			file(FinalizationOutcome.Ignored(automaticAuthorizationRefusal(track.automaticWriteAuthorization)))
			return
		}
		if (!track.source.isWatched) {
			file(FinalizationOutcome.Ignored("${track.packageName} is not a watched source"))
			return
		}
		val sourceItemId = track.evidence.sourceItemId?.takeIf(String::isNotBlank)
		if (!track.evidence.isSourceProven || sourceItemId == null) {
			val reason = "source item identity was not proven"
			file(FinalizationOutcome.Refused(reason))
			onFeedback?.invoke(reason, true)
			return
		}
		val prefilter = eligibility.prefilter(track)
		if (prefilter != null) {
			file(FinalizationOutcome.Refused(prefilter))
			onFeedback?.invoke(prefilter, true)
			return
		}
		if (classification.isMuted(sourceItemId)) {
			val reason = "muted item — you asked never to scrobble this one again"
			file(FinalizationOutcome.Refused(reason))
			onFeedback?.invoke(reason, true)
			return
		}
		val durationMs = track.measurement.durationMs
		val decision = eligibility.decide(track, durationMs)
		if (!decision.shouldScrobble) {
			val reason = decision.skippedBecause ?: "no reason given"
			file(FinalizationOutcome.Refused(reason))
			onFeedback?.invoke(reason, true)
			return
		}
		val base = payloads.buildSourceNeutral(track, durationMs)
		if (base == null) {
			val reason = "payload not buildable"
			file(FinalizationOutcome.Refused(reason))
			onFeedback?.invoke(reason, true)
			return
		}
		if (!dispatcher.claim(runClaims, base, track.measurement.startedAtEpochSec)) {
			val reason = "already scrobbled this listen"
			EventLog.append(
				"engine",
				"skipped: already scrobbled [${DedupLedger.keyFor(base.title, base.artist, track.measurement.startedAtEpochSec)}]",
			)
			file(FinalizationOutcome.Refused(reason))
			onFeedback?.invoke(reason, true)
			return
		}
		if (trigger == FinalizationTrigger.MANUAL) {
			EventLog.append(
				"engine",
				"manual broadcast claimed [${DedupLedger.keyFor(base.title, base.artist, track.measurement.startedAtEpochSec)}]",
			)
		}
		val ordered = payloads.cap(decision, base, track).map { base.copy(percentPlayed = it) }
		if (trigger != FinalizationTrigger.MANUAL &&
			!settings.authorizesAutomaticWrite(track.automaticWriteAuthorization)
		) {
			dispatcher.release(runClaims, base, track.measurement.startedAtEpochSec)
			file(FinalizationOutcome.Ignored(automaticAuthorizationRefusal(track.automaticWriteAuthorization)))
			return
		}
		if (!dispatcher.hasPostingAccount) {
			if (trigger == FinalizationTrigger.MANUAL) {
				dispatcher.release(runClaims, base, track.measurement.startedAtEpochSec)
				EventLog.append("engine", "manual claim released after a failed send")
			}
			file(FinalizationOutcome.Ignored("no posting key saved"))
			return
		}
		val automaticTransportCommitted = trigger == FinalizationTrigger.AUTOMATIC &&
			settings.tryCommitAutomaticWrite(track.automaticWriteAuthorization)
		if (trigger == FinalizationTrigger.AUTOMATIC && !automaticTransportCommitted) {
			dispatcher.release(runClaims, base, track.measurement.startedAtEpochSec)
			file(FinalizationOutcome.Ignored(automaticAuthorizationRefusal(track.automaticWriteAuthorization)))
			return
		}
		file(FinalizationOutcome.Eligible(ordered))
		if (trigger == FinalizationTrigger.SHADOW) return
		ordered.forEach { payload ->
			val dispatchFeedback: ((String, Boolean) -> Unit)? = if (
				trigger == FinalizationTrigger.MANUAL
			) {
				{ message, isError ->
					if (isError) {
						dispatcher.release(runClaims, base, track.measurement.startedAtEpochSec)
						EventLog.append("engine", "manual claim released after a failed send")
					}
					onFeedback?.invoke(message, isError)
				}
			} else {
				onFeedback
			}
			dispatcher.dispatch(
				payload,
				sourceItemId,
				track.packageName,
				track.sourceSession.sourceEpoch,
				automaticTransportCommitted,
				trigger,
				dispatchFeedback,
			)
		}
	}

	fun execute(
		session: SessionSnapshot,
		trigger: FinalizationTrigger,
		onFeedback: ((String, Boolean) -> Unit)? = null,
	) {
		val shadow = trigger == FinalizationTrigger.SHADOW
		val report = FinalizationReport(
			session,
			observer(),
			shadow = shadow,
			sequence = if (shadow) sequence.isolatedCopy() else sequence,
			claims = if (shadow) ShadowClaims(claims) else claims,
			history = if (shadow) history.isolatedCopy() else history,
		)
		if (!settings.monitoringEnabled) {
			EventLog.append("engine", "monitoring stopped — not scrobbling")
			report.ignored("monitoring stopped")
			onFeedback?.invoke("Monitoring is stopped. Nothing sent.", true)
			return
		}
		if (trigger != FinalizationTrigger.MANUAL &&
			!settings.authorizesAutomaticWrite(session.automaticWriteAuthorization)
		) {
			EventLog.append("engine", "auto-scrobble off — not scrobbling")
			report.ignored(automaticAuthorizationRefusal(session.automaticWriteAuthorization))
			return
		}
		if (!session.isTarget) {
			report.ignored("${session.packageName} is not a watched source")
			onFeedback?.invoke("That source is not watched. Nothing sent.", true)
			return
		}
		if (!NativeSourceSwitches.isSnapshotCurrent(session.packageName, session.sourceEpoch)) {
			EventLog.append(
				"native",
				"${session.packageName} finalized after its opt-in/lifecycle boundary — discarded",
			)
			report.ignored("finalized after its opt-in/lifecycle boundary")
			onFeedback?.invoke("That source was disabled or reset. Nothing sent.", true)
			return
		}

		report.sequence.begin(session.trackInstance, session.trackStartedAtEpochSec)
		EventLog.append(
			"sequence",
			"${session.packageName} finalized start=${session.trackStartedAtEpochSec}; " +
				report.sequence.describe(session.packageName),
		)
		if (!session.isYouTube) {
			effects.skip(report, session, "source not proven YouTube")
			onFeedback?.invoke("Source identity was not proven. Nothing sent.", true)
			return
		}
		if (settings.disableShorts && session.hasShortSourceProof) {
			val reason = "Shorts are turned off — you asked never to scrobble a Short"
			effects.skip(report, session, reason)
			onFeedback?.invoke(reason, true)
			return
		}

		val prefilterReason = eligibility.prefilter(session)
		if (prefilterReason != null) {
			val frozenVideoId = session.confirmed?.videoId
				?.takeIf { YouTubeProbe.canonicalWatchUrl(it) != null }
			if (frozenVideoId != null && !session.title.isNullOrBlank()) {
				effects.skip(report, session, prefilterReason, videoId = frozenVideoId)
				onFeedback?.invoke(prefilterReason, true)
				return
			}
		}
		if (prefilterReason != null && !effects.couldBecomeUserFacingSkip(session)) {
			effects.skip(report, session, prefilterReason)
			onFeedback?.invoke(prefilterReason, true)
			return
		}

		val job = scope.launch(if (shadow) EventLog.shadowContext() else EmptyCoroutineContext) {
			resolveAndFinalize(session, report, prefilterReason, trigger, onFeedback)
		}
		job.invokeOnCompletion { cause ->
			if (cause is CancellationException && !report.isFiled) {
				report.ignored("finalization cancelled — monitoring stopped or the app is shutting down")
			}
		}
	}

	@Suppress("TooGenericExceptionCaught")
	private suspend fun resolveAndFinalize(
		session: SessionSnapshot,
		report: FinalizationReport,
		prefilterReason: String?,
		trigger: FinalizationTrigger,
		onFeedback: ((String, Boolean) -> Unit)?,
	) {
		try {
			resolveAndFinalizeOrThrow(session, report, prefilterReason, trigger, onFeedback)
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (failure: Throwable) {
			EventLog.append(
				"engine",
				"finalization failed after the prefilter: " +
					"${failure.javaClass.simpleName}: ${failure.message}",
			)
			if (!report.isFiled) {
				val reason = "finalization failed unexpectedly — " +
					"${failure.javaClass.simpleName}: ${failure.message}"
				effects.skip(report, session, reason)
				onFeedback?.invoke(reason, true)
			}
		}
	}

	@Suppress("LongMethod")
	private suspend fun resolveAndFinalizeOrThrow(
		session: SessionSnapshot,
		report: FinalizationReport,
		prefilterReason: String?,
		trigger: FinalizationTrigger,
		onFeedback: ((String, Boolean) -> Unit)?,
	) {
		if (trigger != FinalizationTrigger.MANUAL &&
			!settings.authorizesAutomaticWrite(session.automaticWriteAuthorization)
		) {
			report.ignored(automaticAuthorizationRefusal(session.automaticWriteAuthorization))
			return
		}
		if (!NativeSourceSwitches.isSnapshotCurrent(session.packageName, session.sourceEpoch)) {
			EventLog.append("native", "${session.packageName} stale finalization refused")
			report.ignored("source generation changed before resolution")
			return
		}
		val attempt = identity.resolve(session, report.sequence, report.history)
		if (trigger != FinalizationTrigger.MANUAL &&
			!settings.authorizesAutomaticWrite(session.automaticWriteAuthorization)
		) {
			report.ignored(automaticAuthorizationRefusal(session.automaticWriteAuthorization))
			return
		}
		if (!NativeSourceSwitches.isSnapshotCurrent(session.packageName, session.sourceEpoch)) {
			EventLog.append(
				"native",
				"${session.packageName} stale finalization refused after resolution",
			)
			report.ignored("source generation changed during resolution")
			return
		}
		val resolution = attempt.resolution
		if (resolution == null) {
			effects.noteIdentity(session, null, report.shadow)
			val reason = "video id could not be verified — " +
				(attempt.refusalReason ?: "no unique candidate corroborated") +
				"; no scrobble was broadcast because every YouTube entry requires a hyperlink"
			effects.skip(report, session, reason)
			onFeedback?.invoke(reason, true)
			return
		}
		if (FinalizedVideoIdentityContract.authority(session.confirmed?.videoId, resolution) == null) {
			effects.noteIdentity(session, null, report.shadow)
			val reason = "video id could not be verified — " +
				FinalizedVideoIdentityContract.refusalReason(session.confirmed?.videoId, resolution) +
				"; no scrobble was broadcast because every YouTube entry requires a hyperlink"
			effects.skip(report, session, reason)
			onFeedback?.invoke(reason, true)
			return
		}

		val videoId = resolution.videoId
		val facts = enrichment.facts(videoId)
		val resolvedTitle = resolution.title ?: facts?.title
		classification.contradiction(session, resolution, facts)?.let { mismatch ->
			effects.noteIdentity(session, null, report.shadow)
			val reason = "video id could not be verified against the finalized snapshot — $mismatch"
			effects.skip(report, session, reason)
			onFeedback?.invoke(reason, true)
			return
		}
		classification.rememberVerified(session, resolution, facts, report.shadow)
		effects.noteIdentity(session, videoId, report.shadow)
		report.sequence.remember(
			session.trackInstance,
			session.trackStartedAtEpochSec,
			videoId,
			session.title,
		)
		EventLog.append(
			"sequence",
			"${session.packageName} verified start=${session.trackStartedAtEpochSec} → $videoId; " +
				report.sequence.describe(session.packageName),
		)
		if (session.profile.packageProvesSource) {
			val route = session.confirmed?.exactIdRoute
			EventLog.append(
				"native-id",
				if (route != null) {
					"${session.packageName} verified $videoId via $route"
				} else {
					"${session.packageName} verified $videoId via corroborated resolver (${resolution.source})"
				},
			)
		}
		if (prefilterReason != null) {
			effects.skip(report, session, prefilterReason, videoId = videoId, resolvedTitle = resolvedTitle)
			onFeedback?.invoke(prefilterReason, true)
			return
		}
		if (classification.isMuted(videoId)) {
			val reason = "muted video — you asked never to scrobble this one again"
			effects.skip(report, session, reason, videoId = videoId, resolvedTitle = resolvedTitle)
			onFeedback?.invoke(reason, true)
			return
		}

		val music = enrichment.music(session, facts)
		val durationMs = payloads.effectiveDurationMs(session, facts)
		if (session.durationMs == null && durationMs != null) {
			EventLog.append(
				"engine",
				"duration recovered from the watch page: ${durationMs / 1000}s",
			)
		}
		val decision = eligibility.decide(session, facts, durationMs)
		if (!decision.shouldScrobble) {
			val reason = decision.skippedBecause ?: "no reason given"
			effects.skip(
				report, session, reason, durationMs,
				videoId = videoId, resolvedTitle = resolvedTitle,
			)
			onFeedback?.invoke(reason, true)
			return
		}
		val basePayload = payloads.build(session, facts, music, resolution, durationMs)
		if (basePayload == null || basePayload.url == null) {
			val reason = if (basePayload == null) {
				"payload not buildable"
			} else {
				"internal hyperlink invariant failed — nothing broadcast"
			}
			effects.skip(
				report, session, reason, durationMs,
				videoId = videoId, resolvedTitle = resolvedTitle,
			)
			onFeedback?.invoke(reason, true)
			return
		}
		if (!NativeSourceSwitches.isSnapshotCurrent(session.packageName, session.sourceEpoch)) {
			report.ignored("opt-in/lifecycle changed before dedup or signing")
			return
		}
		if (trigger != FinalizationTrigger.MANUAL &&
			!settings.authorizesAutomaticWrite(session.automaticWriteAuthorization)
		) {
			report.ignored(automaticAuthorizationRefusal(session.automaticWriteAuthorization))
			return
		}
		if (!dispatcher.claim(report.claims, basePayload, session.trackStartedAtEpochSec)) {
			val reason = "already scrobbled this listen"
			EventLog.append(
				"engine",
				"skipped: already scrobbled [${DedupLedger.keyFor(basePayload.title, basePayload.artist, session.trackStartedAtEpochSec)}]",
			)
			effects.skip(
				report, session, reason, durationMs, log = false,
				videoId = videoId, resolvedTitle = resolvedTitle,
			)
			onFeedback?.invoke(reason, true)
			return
		}
		if (trigger == FinalizationTrigger.MANUAL) {
			EventLog.append(
				"engine",
				"manual broadcast claimed [${DedupLedger.keyFor(basePayload.title, basePayload.artist, session.trackStartedAtEpochSec)}]",
			)
		}

		val percentages = payloads.cap(decision, basePayload, session)
		if (session.loopDetected) {
			EventLog.append(
				"engine",
				"playback loop detected — first viewing kept; continuous viewing capped to one scrobble",
			)
		} else if (decision.probableLoop) {
			EventLog.append(
				"engine",
				"probable short loop detected — first viewing kept; video remains capped to one scrobble",
			)
		} else if (percentages.size < decision.percentages.size) {
			EventLog.append(
				"engine",
				"repeat playback capped to one tx (kind=${basePayload.kind})",
			)
		}
		val orderedPayloads = percentages
			.map { basePayload.copy(percentPlayed = it) }
		if (trigger != FinalizationTrigger.MANUAL &&
			!settings.authorizesAutomaticWrite(session.automaticWriteAuthorization)
		) {
			dispatcher.release(report.claims, basePayload, session.trackStartedAtEpochSec)
			report.ignored(automaticAuthorizationRefusal(session.automaticWriteAuthorization))
			return
		}
		if (!dispatcher.hasPostingAccount) {
			if (trigger == FinalizationTrigger.MANUAL) {
				dispatcher.release(report.claims, basePayload, session.trackStartedAtEpochSec)
				EventLog.append("engine", "manual claim released after a failed send")
			}
			report.ignored("no posting key saved")
			onFeedback?.invoke("No key saved — add one on the Account tab first.", true)
		} else {
			val automaticTransportCommitted = trigger == FinalizationTrigger.AUTOMATIC &&
				settings.tryCommitAutomaticWrite(session.automaticWriteAuthorization)
			if (trigger == FinalizationTrigger.AUTOMATIC && !automaticTransportCommitted) {
				dispatcher.release(report.claims, basePayload, session.trackStartedAtEpochSec)
				report.ignored(automaticAuthorizationRefusal(session.automaticWriteAuthorization))
				return
			}
			report.eligible(orderedPayloads)
			if (report.shadow) return
			orderedPayloads.forEach { payload ->
				val dispatchFeedback: ((String, Boolean) -> Unit)? = if (
					trigger == FinalizationTrigger.MANUAL
				) {
					{ message, isError ->
						if (isError) {
							dispatcher.release(report.claims, basePayload, session.trackStartedAtEpochSec)
							EventLog.append("engine", "manual claim released after a failed send")
						}
						onFeedback?.invoke(message, isError)
					}
				} else {
					onFeedback
				}
				dispatcher.dispatch(
					payload,
					videoId,
					session.packageName,
					session.sourceEpoch,
					automaticTransportCommitted,
					trigger,
					dispatchFeedback,
				)
			}
		}
	}

	private fun automaticAuthorizationRefusal(
		stamp: com.rustedwax.core.AutomaticWriteAuthorization,
	): String = if (!settings.autoScrobble) {
		"auto-scrobble off"
	} else if (!stamp.enabledAtStart) {
		"automatic target began while auto-scrobble was off"
	} else {
		"automatic-scrobble authorization changed after the target began"
	}
}
