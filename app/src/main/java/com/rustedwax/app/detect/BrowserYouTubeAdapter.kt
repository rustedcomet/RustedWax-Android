package com.rustedwax.app.detect

import com.rustedwax.core.*
import android.media.MediaMetadata
import com.rustedwax.core.MetadataFields
import com.rustedwax.core.PlaybackSourceCapabilities
import com.rustedwax.core.SourceSessionId
import com.rustedwax.core.TrackInstanceId

class BrowserYouTubeAdapter(
	override val packageName: String,
	override val appLabel: String,
	private val evidenceCoordinator: EvidenceCoordinator? = null,
	private val sourceSession: SourceSessionId = SourceSessionId(packageName, null),
) : SourceAdapter {

	override val originName: String = YouTubeProbe.originForPackage(packageName).displayName

	/**
	 * A browser refines a duration upward and never republishes a shorter one,
	 * keeps its identity in the address bar rather than in an item id, ends a
	 * track by navigation rather than by `STOPPED`, and always publishes a
	 * readable position — so all four measurement quirks are absent here.
	 */
	override val playbackCapabilities = PlaybackSourceCapabilities(
		republishesShorterDurations = false,
		requiresExactIdToCarryProgress = false,
		usesStoppedReplacementGrace = false,
		supportsPictureInPictureInference = false,
		requiresEstablishedTimeline = true,
	)

	override val evidenceCapabilities = SourceEvidenceCapabilities(
		// §4.1. A browser session is *not* proven by its package, and treating it
		// as proven is what wrote `w3schools.com/html/mov_bbb.mp4` into an
		// exportable log. It earns the right to be described, one line at a time.
		packageProvesSource = false,
		// A package outside the known host set — the instrumented suite's own —
		// has no address bar, no media notification and no scannable root, and
		// every one of those stores already refuses it by package. Declaring it
		// here is the same answer, stated once instead of discovered three times.
		publishesHostScreenEvidence = packageName in YouTubeProbe.TARGET_PACKAGES,
		publishesStructuredTransportDump = false,
		// Browser acceptance is gated by the YouTube master switch, but carries no
		// generation of its own: there is no per-browser opt-in to supersede.
		scopedBySourceEpoch = false,
		// A Short in a tab is an ordinary browser listen with a `/shorts/` path.
		presentsForegroundShorts = false,
	)

	override val profile: SourceProfile = SourceProfile.YOUTUBE

	/**
	 * A browser listen survives the listener being recycled: its identity is in
	 * the address bar, which is still there afterwards, so the track really did
	 * end and dropping it would lose a legitimate scrobble.
	 */
	override val teardownPolicy = SourceTeardownPolicy.FINALIZE_IN_FLIGHT

	// ── metadata interpretation ────────────────────────────────────────────

	/**
	 * `TITLE` and `ARTIST` only, both filtered.
	 *
	 * `DISPLAY_TITLE` and `DISPLAY_SUBTITLE` are deliberately **not** consulted
	 * for the comparison identity. In a browser they are the tab's, and a track
	 * identity built from them ends the real track and starts a phantom one —
	 * which is what [BrowserTabMetadata] documents costing 102 seconds of one
	 * listen, 22,425 seconds of another, and twelve refused scrobbles in a day.
	 */
	override fun trackIdentity(fields: MetadataFields?): TrackIdentity = TrackIdentity(
		title = BrowserTabMetadata.titleOrNull(
			packageName,
			MetadataDump.textOrNull(fields, MediaMetadata.METADATA_KEY_TITLE),
		),
		artist = BrowserTabMetadata.artistOrNull(
			packageName,
			MetadataDump.textOrNull(fields, MediaMetadata.METADATA_KEY_ARTIST),
		),
		album = MetadataDump.textOrNull(fields, MediaMetadata.METADATA_KEY_ALBUM),
		durationMs = MetadataDump.longOrNull(fields, MediaMetadata.METADATA_KEY_DURATION),
		// No browser surface publishes one. The resolver exists because of this.
		sourceItemId = null,
	)

	override fun presentedTitle(fields: MetadataFields?): String? =
		BrowserTabMetadata.titleOrNull(packageName, rawTitle(fields))

	override fun presentedArtist(fields: MetadataFields?): String? =
		BrowserTabMetadata.artistOrNull(packageName, rawArtist(fields))

	override fun namesContainerOnly(fields: MetadataFields?): Boolean =
		BrowserTabMetadata.isTabTitle(packageName, rawTitle(fields))

	private fun rawTitle(fields: MetadataFields?): String? =
		MetadataDump.textOrNull(fields, MediaMetadata.METADATA_KEY_TITLE)
			?: MetadataDump.textOrNull(fields, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)

	private fun rawArtist(fields: MetadataFields?): String? =
		MetadataDump.textOrNull(fields, MediaMetadata.METADATA_KEY_ARTIST)
			?: MetadataDump.textOrNull(fields, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)

	// ── identity ───────────────────────────────────────────────────────────

	override fun hostNotificationHint(
		fields: MetadataFields?,
		soleSession: Boolean,
	): NotificationHints.Hint? = if (evidenceCoordinator != null) {
		evidenceCoordinator.notificationFor(
			sourceSession = sourceSession,
			title = presentedTitle(fields),
			artist = presentedArtist(fields),
			soleSession = soleSession,
		)
	} else {
		NotificationHints.bestFor(
			packageName = packageName,
			title = presentedTitle(fields),
			artist = presentedArtist(fields),
			soleSession = soleSession,
		)
	}

	override fun readIdentity(request: SourceIdentityRequest): SourceIdentityReading {
		val fields = request.fields
		val hint = hostNotificationHint(fields, request.soleSession)
		// Evidence whose id was disproven for this track is not evidence.
		val url = (if (evidenceCoordinator != null) {
			evidenceCoordinator.urlFor(sourceSession)
		} else {
			UrlEvidence.get(packageName)
		})
			?.takeUnless { it.videoId != null && it.videoId in request.rejectedItemIds }

		var context = request.resolverContext

		if (context.playlistId == null) {
			(if (evidenceCoordinator != null) {
				evidenceCoordinator.playlistFor(sourceSession)
			} else {
				UrlEvidence.playlistId(packageName)
			})?.let { remembered ->
				context = context.copy(playlistId = remembered)
			}
		}
		if (url != null) {
			context = resolverContextWithObservedUrl(context, url, request.rejectedItemIds)
		}

		return SourceIdentityReading(
			identity = YouTubeProbe.identify(fields, hint, url, request.soleSession),
			resolverContext = context,
			notificationHint = hint,
		)
	}

	override fun onIdentitySelected(request: SourceIdentitySelected): List<SourceEffect> {
		val effects = mutableListOf<SourceEffect>()
		// The browser mirror of the native resolver's retry: a replacement session
		// exists before the address bar has named what it is playing, so its first
		// attempt to reclaim a continuation could not identify itself. This is the
		// moment it can.
		if (request.justConfirmed) effects += SourceEffect.RetryCarriedProgressClaim
		val confirmed = request.identity as? YouTubeProbe.Identity.Confirmed
		if (confirmed != null && confirmed.isShort) {
			confirmed.urlGeneration?.let { generation ->
				if (evidenceCoordinator != null) {
					evidenceCoordinator.markVisibleAdSessionEstablished(
						sourceSession, confirmed.videoId, generation,
					)
				} else {
					AdEvidence.markSessionEstablished(packageName, confirmed.videoId, generation)
				}
			}
			val visibleAd = if (evidenceCoordinator != null) {
				evidenceCoordinator.visibleAd(
					sourceSession, confirmed.videoId, confirmed.urlGeneration,
				)
			} else {
				AdEvidence.get(packageName, confirmed.videoId, confirmed.urlGeneration)
			}
			visibleAd?.let {
				effects += SourceEffect.BindVisibleAdEvidence(it)
			}
		}
		return effects
	}

	/**
	 * The choice itself is [BrowserScanBinding], which is pure and separately
	 * tested; this is what makes it *this source's* decision rather than the
	 * host's. `SessionProbe` used to filter the candidates, build them, call the
	 * selector and resolve the answer back to a watch, so the browser's
	 * multiple-tab policy belongs to this source adapter rather than the shared
	 * registry.
	 */
	override fun selectForHostObservation(
		request: SourceHostObservationRequest,
	): SourceBinding = when (
		val selection = BrowserScanBinding.select(
			namedVideoId = request.namedItemId,
			candidates = request.candidates.map {
				BrowserScanBinding.Candidate(
					key = it.key,
					describesNamedVideo = it.describesNamedItem,
					finalized = it.finalized,
					playing = it.playing,
				)
			},
		)
	) {
		is BrowserScanBinding.Selection.Bound ->
			SourceBinding.Bound(selection.key, selection.namedThisInstance)

		is BrowserScanBinding.Selection.Refused -> SourceBinding.Refused(selection.reason)
	}

	// ── track-instance evidence lifecycle ──────────────────────────────────

	override fun bindTrackInstance(
		instance: MediaSessionAdEvidence.TrackInstance,
		establishedAtMillis: Long,
	) {
		if (evidenceCoordinator != null) {
			evidenceCoordinator.bindTrack(sourceSession, instance, establishedAtMillis)
		} else {
			MediaSessionAccessibilityEvidence.activate(instance, establishedAtMillis)
		}
	}

	override fun unbindTrackInstance(instance: MediaSessionAdEvidence.TrackInstance) {
		if (evidenceCoordinator != null) {
			evidenceCoordinator.releaseTrack(
				sourceSession, TrackInstanceId(instance.packageName, instance.token),
			)
		} else {
			MediaSessionAccessibilityEvidence.deactivate(instance)
		}
	}

	override fun releaseTrackInstance(instance: MediaSessionAdEvidence.TrackInstance) {
		if (evidenceCoordinator != null) {
			evidenceCoordinator.releaseTrack(
				sourceSession, TrackInstanceId(instance.packageName, instance.token),
			)
		} else {
			MediaSessionAdEvidence.clearInstance(instance)
			MediaSessionAccessibilityEvidence.deactivate(instance)
		}
	}

	override fun coverageFor(
		request: SourceCoverageRequest,
	): MediaSessionAccessibilityEvidence.Coverage? {
		val current = if (evidenceCoordinator != null) {
			evidenceCoordinator.currentCoverage(
				sourceSession = sourceSession,
				instance = request.instance,
				expectedUrlGeneration = request.expectedUrlGeneration,
				nowMillis = request.nowMillis,
			)
		} else {
			MediaSessionAccessibilityEvidence.current(
				instance = request.instance,
				expectedUrlGeneration = request.expectedUrlGeneration,
				now = request.nowMillis,
			)
		}
		current?.let { return it }
		val local = request.localCoverage ?: return null
		val sameInstance = local.instance.packageName == request.instance.packageName &&
			local.instance.token == request.instance.token &&
			local.instance.signature.sameTrackAs(request.instance.signature)
		val lifecycleCurrent = if (evidenceCoordinator != null) {
			evidenceCoordinator.isCoverageLifecycleCurrent(local)
		} else {
			MediaSessionAccessibilityEvidence.isLifecycleCurrent(local)
		}
		val fresh = request.nowMillis >= local.atMillis &&
			request.nowMillis - local.atMillis <= MediaSessionAccessibilityEvidence.COVERAGE_FRESH_MS
		val sameGeneration = request.expectedUrlGeneration == null ||
			local.urlGeneration == null || local.urlGeneration == request.expectedUrlGeneration
		return local.takeIf { sameInstance && lifecycleCurrent && fresh && sameGeneration }
	}

	override fun restoreCarriedEvidence(
		request: SourceCarryRestoreRequest,
	): SourceCarryRestoreResult {
		request.explicitAdSignal?.let { signal ->
			if (evidenceCoordinator != null) {
				evidenceCoordinator.restoreTrackAd(
					sourceSession, request.instance, signal, request.atMillis,
				)
			} else {
				MediaSessionAdEvidence.restoreAccepted(request.instance, signal, request.atMillis)
			}
		}
		val coverage = request.accessibilityCoverage?.takeIf {
			if (evidenceCoordinator != null) {
				evidenceCoordinator.restoreCoverage(
					sourceSession, request.instance, it, request.establishedAtMillis,
				)
			} else {
				MediaSessionAccessibilityEvidence.restore(
					request.instance, it, request.establishedAtMillis,
				)
			}
		}?.copy(instance = request.instance)
		return SourceCarryRestoreResult(accessibilityCoverage = coverage)
	}

	// ── host observations ──────────────────────────────────────────────────

	/**
	 * The id binding is mandatory.
	 *
	 * A visible watch-page pre-roll label sits over the real video's URL, so
	 * treating the package alone as sufficient would veto the content the user
	 * actually chose.
	 */
	override fun bindVisibleAdEvidence(request: SourceVisibleAdRequest): SourceAdVerdict {
		val confirmed = request.identity as? YouTubeProbe.Identity.Confirmed
		if (!SessionProbe.adEvidenceMatches(confirmed, request.evidence)) {
			return SourceAdVerdict.NONE
		}
		if (request.currentSignal == request.evidence.signal) return SourceAdVerdict.NONE
		return SourceAdVerdict(
			signal = request.evidence.signal,
			notes = listOf(
				SourceNote(
					"ad",
					"$packageName bound explicit ad evidence to ${request.evidence.videoId}: " +
						"\"${request.evidence.signal}\"",
				),
			),
		)
	}

	override fun bindHostAdLabel(request: SourceHostAdLabelRequest): SourceAdVerdict {
		val accepted = if (evidenceCoordinator != null) {
			evidenceCoordinator.observeTrackAd(
				sourceSession = sourceSession,
				observation = request.observation,
				instance = request.instance,
				instanceEstablishedBeforeObservation =
					request.instanceEstablishedBeforeObservation,
				unambiguous = true,
			)
		} else {
			MediaSessionAdEvidence.observe(
				observation = request.observation,
				instance = request.instance,
				instanceEstablishedBeforeObservation =
					request.instanceEstablishedBeforeObservation,
				unambiguous = true,
			)
		} ?: return SourceAdVerdict.NONE
		if (request.currentSignal == accepted.signal) return SourceAdVerdict.NONE
		return SourceAdVerdict(
			signal = accepted.signal,
			notes = listOf(
				SourceNote(
					"ad",
					"$packageName bound explicit ad evidence to MediaSession track " +
						"${accepted.instance.token}: \"${accepted.signal}\"",
				),
			),
		)
	}

	override fun bindScreenScan(request: SourceScreenScanRequest): SourceScreenScanVerdict {
		val coverage = if (evidenceCoordinator != null) {
			evidenceCoordinator.observeCoverage(
				sourceSession = sourceSession,
				scan = request.scan,
				instance = request.instance,
				unambiguous = request.unambiguous,
				namedThisInstance = request.namedThisInstance,
			)
		} else {
			MediaSessionAccessibilityEvidence.observe(
				scan = request.scan,
				instance = request.instance,
				unambiguous = request.unambiguous,
				namedThisInstance = request.namedThisInstance,
			)
		} ?: return SourceScreenScanVerdict()
		// A Short's own ad evidence travels the id-bound route instead; what a
		// Short scan supplies here is the *absence* of a label, which only clears
		// provisional state.
		if (request.scan.isShort) {
			if (request.scan.adSignal == null) {
				if (evidenceCoordinator != null) {
					evidenceCoordinator.clearTrackAdProvisional(sourceSession)
				} else {
					MediaSessionAdEvidence.labelAbsent(packageName)
				}
			}
			return SourceScreenScanVerdict(coverage = coverage)
		}
		return SourceScreenScanVerdict(
			coverage = coverage,
			ad = bindHostAdLabel(
				SourceHostAdLabelRequest(
					observation = MediaSessionAdEvidence.Observation(
						packageName = packageName,
						signal = request.scan.adSignal,
						atMillis = request.scan.atMillis,
					),
					instance = request.instance,
					instanceEstablishedBeforeObservation =
						request.instanceEstablishedBeforeObservation,
					currentSignal = request.currentSignal,
				),
			),
		)
	}

	// ── exact-id pre-resolution ────────────────────────────────────────────

	/**
	 * Never. A browser's exact id is in the address bar, which survives the
	 * session being rebuilt; spending a bounded lookup on it would buy nothing
	 * and would put a network answer where a read answer already is.
	 */
	override fun mayPreResolveExactItemId(request: SourceExactIdRequest): Boolean = false

	// ── diagnostics and package-scoped state ───────────────────────────────

	override fun notes(moment: SourceMoment): List<SourceNote> = emptyList()

	override fun onPackageStateReset() = Unit
}
