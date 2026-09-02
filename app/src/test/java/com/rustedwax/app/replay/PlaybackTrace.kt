package com.rustedwax.app.replay

import com.rustedwax.core.*
import com.rustedwax.app.detect.ForegroundShortTracker
import com.rustedwax.core.ListenState
import com.rustedwax.app.detect.MediaSessionAccessibilityEvidence
import com.rustedwax.app.detect.MediaSessionAdEvidence
import com.rustedwax.app.detect.NativePreResolvedRoute
import com.rustedwax.app.detect.NativeShortParser
import com.rustedwax.app.detect.NativeShortsAdapter
import com.rustedwax.app.detect.NativeShortsObserver
import com.rustedwax.app.detect.NativeSourceSwitches
import com.rustedwax.app.detect.NotificationHints
import com.rustedwax.core.PlaybackEffect
import com.rustedwax.core.PlaybackInput
import com.rustedwax.core.PlaybackReducer
import com.rustedwax.app.detect.ResolverContext
import com.rustedwax.app.detect.RunScopedEvidence
import com.rustedwax.app.detect.SessionProbe
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.SourceRegistry
import com.rustedwax.app.detect.SourceExactIdRequest
import com.rustedwax.app.detect.SourceProfile
import com.rustedwax.app.detect.SourceProof
import com.rustedwax.core.TrackIdentity
import com.rustedwax.app.detect.TrackProgressCarry
import com.rustedwax.core.TransportState
import com.rustedwax.app.detect.UrlEvidence
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.app.detect.resolverContextWithObservedUrl

enum class ReplaySource(val packageName: String, val label: String) {
	BRAVE("com.brave.browser", "Brave"),
	CHROME("com.android.chrome", "Chrome"),
	NATIVE_YOUTUBE(YouTubeProbe.YOUTUBE_PACKAGE, "YouTube"),
	NATIVE_YOUTUBE_MUSIC(YouTubeProbe.YOUTUBE_MUSIC_PACKAGE, "YouTube Music"),
	;

	val isNative: Boolean get() = SourceRegistry.packageProvesSource(packageName)
}

class PlaybackTrace(
	val source: ReplaySource,
	private val clock: ReplayClock,
	/** False when the browser evidence services were never granted. */
	val browserEvidenceEnabled: Boolean = true,
) {

	private val reducer = PlaybackReducer(SourceProfile.playbackCapabilitiesFor(source.packageName))

	/**
	 * What the session has published about the current item.
	 *
	 * The JVM stand-in for `MediaMetadata`. Merged rather than replaced, because
	 * that is what a session republishing a partial bundle looks like to the
	 * probe, and because the fixtures describe observations rather than complete
	 * bundles.
	 */
	private data class Bundle(
		val title: String? = null,
		val artist: String? = null,
		val album: String? = null,
		val durationMs: Long? = null,
		val genre: String? = null,
		val mediaId: String? = null,
		val mediaUri: String? = null,
		val artUri: String? = null,
	) : MetadataFields {
		override fun getString(key: String): String? = when (key) {
			"android.media.metadata.TITLE" -> title
			"android.media.metadata.ARTIST" -> artist
			"android.media.metadata.ALBUM" -> album
			"android.media.metadata.GENRE" -> genre
			"android.media.metadata.MEDIA_ID" -> mediaId
			"android.media.metadata.MEDIA_URI" -> mediaUri
			"android.media.metadata.ART_URI" -> artUri
			else -> null
		}

		override fun getLong(key: String): Long = when (key) {
			"android.media.metadata.DURATION" -> durationMs ?: 0
			else -> 0
		}

		override fun bitmapDimensions(key: String): Pair<Int, Int>? = null

		override fun keySet(): Set<String> = buildSet {
			if (title != null) add("android.media.metadata.TITLE")
			if (artist != null) add("android.media.metadata.ARTIST")
			if (album != null) add("android.media.metadata.ALBUM")
			if (durationMs != null) add("android.media.metadata.DURATION")
			if (genre != null) add("android.media.metadata.GENRE")
			if (mediaId != null) add("android.media.metadata.MEDIA_ID")
			if (mediaUri != null) add("android.media.metadata.MEDIA_URI")
			if (artUri != null) add("android.media.metadata.ART_URI")
		}

		fun mergedWith(event: PlaybackEvent.SessionMetadata) = Bundle(
			title = event.title ?: title,
			artist = event.artist ?: artist,
			album = event.album ?: album,
			durationMs = event.durationMs ?: durationMs,
			genre = event.genre ?: genre,
			mediaId = event.mediaId ?: mediaId,
			mediaUri = event.mediaUri ?: mediaUri,
			artUri = event.artUri ?: artUri,
		)
	}

	/**
	 * Everything about the current listen that is *not* the reducer's.
	 *
	 * Identity, the latch, ad evidence and resolver inputs. Cleared exactly when
	 * the reducer asks for it, through [PlaybackEffect.ClearTrackScopedEvidence],
	 * so the two halves can never fall out of step.
	 */
	private class Evidence {
		var explicitAdSignal: String? = null
		var ownerHandle: String? = null
		var foregroundShort = false
		var accessibilityCoverage: MediaSessionAccessibilityEvidence.Coverage? = null
		var resolverContext = ResolverContext()
		val rejectedVideoIds = mutableSetOf<String>()

		/**
		 * The id this track established, kept until something disproves it.
		 *
		 * the Android session binding's latched video. It is what makes a background tab
		 * survivable: the address bar describes whichever tab is in front, so a
		 * track that proved its id and then went to the background would otherwise
		 * lose that id the moment the user opened anything else.
		 */
		var latchedVideo: YouTubeProbe.Identity.Confirmed? = null

		/** A different site was positively proven; the track is poisoned for good. */
		var taintedReason: String? = null

	}

	private var bundle = Bundle()
	private var pendingBundle = Bundle()
	private var evidence = Evidence()
	private var listen = freshListen()
	private var positionMs: Long? = null

	/**
	 * The production foreground-Shorts route, run for real.
	 *
	 * `ForegroundShortTracker` is ordinary Kotlin with no Android in it, so there
	 * was never a reason for this harness to reproduce its output — and while it
	 * did, the numbers a Shorts scenario asserted were the harness's own. Its
	 * measurement, its picture-in-picture inference, its caps, its grace and the
	 * `SessionSnapshot` it builds are now all the production ones, driven exactly
	 * as `SessionProbe.handleNativeShortEvent` drives them.
	 */
	private val shortTracker = ForegroundShortTracker()
	private val shortAdapter = NativeShortsAdapter()

	/** Where the Short's seekbar has been read to, in seconds. */
	private var shortSeekbarSeconds = 0L

	/** Length the footer published, or 0 when YouTube drew no seekbar at all. */
	private var shortTotalSeconds = 0L
	private var shortTitle: String? = null
	private var shortHandle: String? = null

	/**
	 * YouTube is rendering no seekbar for this Short at all.
	 *
	 * A proven, playing Short with no progress of any kind, which on a device is
	 * `NativeShortParser.Result.OrganicUnmeasured`. It keeps producing
	 * observations — the footer is still readable — and the tracker credits wall
	 * clock for each one.
	 */
	private var shortHasNoSeekbar = false

	private var shortProofMissing = false

	/** The ad label the Shorts feed showed for this item, when it showed one. */
	private var shortAdSignal: String? = null

	/**
	 * Window placement markers, in order.
	 *
	 * Recorded rather than dispatched, and that is a statement about the device
	 * rather than a shortcut: an ordinary MediaSession publishes **no callback**
	 * when its window moves between foreground, background and minimised, so
	 * there is no production input for one to translate into. What the invariant
	 * claims is exactly that absence, and `SurfaceTransitionReplayTest` says so
	 * plainly rather than dressing an unexecuted marker up as coverage.
	 *
	 * Picture-in-picture is the exception and is *not* recorded here: it is the
	 * one surface that leaves a native session with no readable progress, it has
	 * its own accessibility evidence on a device, and it goes to the reducer's
	 * own inference gate.
	 */
	val surfaceMarkers = mutableListOf<PlaybackEvent.Surface>()

	/** Continuation token held by the current transport, when one is open. */
	private var continuationToken: Long? = null
	private var continuationIdentity: TrackIdentity? = null
	private var continuationVerdict: YouTubeProbe.Identity? = null

	/** Set when the reducer asked for a delayed finalize this harness cannot schedule. */
	var stoppedGracePending: Boolean = false
		private set

	private var hint: NotificationHints.Hint? = null
	private var url: UrlEvidence.Evidence? = null
	private var urlGeneration = 0L

	/** The last verdict reached while this listen was active. */
	private var lastStableIdentity: YouTubeProbe.Identity? = null
	private val sourceAdapter = SourceRegistry.forPackage(source.packageName, source.label)
	private var carryResolutionSignature: String? = null
	private var carryResolutionGeneration = 0L

	/** Production carry requests actually executed by this trace. */
	var carryAuthorityRequests: Int = 0
		private set

	/** Successful production carry results delivered back to the reducer. */
	var carryAuthorityResolutions: Int = 0
		private set

	/** Production-equivalent `restoreCarriedProgress` retries after resolution. */
	var carryRestoreAttemptsAfterResolution: Int = 0
		private set

	/** Post-resolution carry retries that reclaimed positive played time. */
	var carryProgressRestorationsAfterResolution: Int = 0
		private set

	/** Positive played time reclaimed by those post-resolution retries. */
	var carriedPlayedMsAfterResolution: Long = 0
		private set

	/** Current reducer measurement, exposed only for an in-flight replay assertion. */
	val currentPlayedMs: Long get() = listen.playedMsAt(clock.nowMillis())

	/** Identity resolution must leave this true until independent playback evidence. */
	val presentationAttributionAmbiguous: Boolean
		get() = listen.presentationUnprovenForNamedWork

	/** Wired by [ReplayHarness] to the same runtime entry point production uses. */
	var onCarryAuthorityRequested:
		((SessionSnapshot, (SessionProbe.NativeResolvedIdentity?) -> Unit) -> Unit)? = null

	/** Snapshots this trace froze, oldest first. */
	val finalized = mutableListOf<SessionSnapshot>()

	/**
	 * A transport that has just been built.
	 *
	 * [alreadyNaming] mirrors `Watch`'s `metadata = controller.metadata` at
	 * construction: a replacement session is handed the page's bundle
	 * immediately, which is why the probe tries to reclaim a continuation from
	 * its constructor. Getting this wrong is not subtle — a transport that has
	 * never named anything cannot finalize at all.
	 */
	private fun freshListen(alreadyNaming: Boolean = false) = ListenState(
		trackIdentity = TrackIdentity(null, null, null, null),
		instanceToken = MediaSessionAdEvidence.nextTrackToken(),
		instanceEstablishedAtMillis = clock.nowMillis(),
		startedAtEpochSec = clock.nowEpochSeconds(),
		everPublishedMetadata = alreadyNaming,
	)

	// ---- the reducer boundary --------------------------------------------------

	/**
	 * Feed the production reducer and perform what it asks for.
	 *
	 * The same three-step contract `MediaSessionDriver.dispatch` keeps, including
	 * the re-entrancy rule: a `Finalize` effect reduces again, and when the outer
	 * reduction asked for no state change of its own the inner result is the one
	 * that must survive.
	 */
	private fun dispatch(input: PlaybackInput) {
		val before = listen
		val transition = reducer.reduce(before, input)
		transition.before.forEach(::perform)
		listen = if (transition.state == before) listen else transition.state
		transition.effects.forEach(::perform)
	}

	private fun perform(effect: PlaybackEffect) {
		when (effect) {
			is PlaybackEffect.Finalize -> dispatch(
				PlaybackInput.FinalizeRequested(effect.reason, clock.nowMillis()),
			)
			is PlaybackEffect.FreezeAndReport -> freeze()
			PlaybackEffect.InstallMetadata -> bundle = pendingBundle
			PlaybackEffect.ClearTrackScopedEvidence -> {
				evidence = Evidence()
				lastStableIdentity = null
				invalidateCarryAuthority()
			}
			PlaybackEffect.RestoreCarriedProgress -> restoreCarriedProgress()
			PlaybackEffect.CancelContinuation -> cancelContinuation()
			is PlaybackEffect.OpenContinuation -> openContinuation()
			// No `Handler` exists here, so the delayed finalize is recorded rather
			// than scheduled. A scenario that wants it to fire says so explicitly.
			PlaybackEffect.ScheduleStoppedFinalizationGrace -> stoppedGracePending = true
			PlaybackEffect.CancelStoppedFinalizationGrace -> stoppedGracePending = false
			PlaybackEffect.ClearPreResolvedNativeIdentity -> {
				evidence.resolverContext = evidence.resolverContext.copy(
					preResolvedNativeVideoId = null,
					preResolvedNativeRoute = null,
				)
			}
			// `SessionProbe.logPlaybackState` records the first readable position on
			// its way past — it is where `noteFirstSeenPosition` has always lived,
			// deliberately *after* a STOPPED transition has already finalized. The
			// same call is made here, or a replayed listen would never look
			// position-corroborated and the abandoned-playback rule would appear to
			// fire for every trace.
			is PlaybackEffect.LogTransportState ->
				dispatch(PlaybackInput.PositionSeen(positionMs))
			// Android-side plumbing with no observable effect on a replayed
			// decision: the in-flight identity request, the carry-authority request
			// and the evidence-store rebinding are all supplied by explicit events
			// in a trace, and the remaining log effects write to `EventLog`.
			PlaybackEffect.InvalidateInFlightIdentityRequest -> invalidateCarryAuthority()
			PlaybackEffect.RequestCarryAuthority -> requestCarryAuthority()
			PlaybackEffect.RebindTrackInstanceEvidence,
			is PlaybackEffect.Note,
			is PlaybackEffect.LogMetadata,
			-> Unit
		}
	}

	private fun invalidateCarryAuthority() {
		carryResolutionGeneration++
		carryResolutionSignature = null
	}

	/**
	 * Replay the production `requestNativeCarryAuthority` path, including adapter
	 * eligibility, one request per stable semantic-key/duration signature, and a
	 * stale-callback check. A successful result establishes immutable carry
	 * identity only; presentation attribution remains a separate reducer fact.
	 */
	private fun requestCarryAuthority() {
		if (listen.suppressedByForegroundShort || listen.finalized ||
			listen.trackIdentity.hasExactSourceItemId ||
			listen.transport != TransportState.PLAYING
		) return
		val duration = bundle.durationMs ?: return
		val adapter = sourceAdapter ?: return
		if (!adapter.mayPreResolveExactItemId(SourceExactIdRequest(bundle, duration))) return
		val requester = onCarryAuthorityRequested ?: return
		val signature = "${listen.trackIdentity.semanticKey}|$duration"
		if (carryResolutionSignature == signature) return
		carryResolutionSignature = signature
		val generation = ++carryResolutionGeneration
		carryAuthorityRequests++
		requester(snapshot()) { proof ->
			val currentDuration = bundle.durationMs ?: return@requester
			val currentSignature = "${listen.trackIdentity.semanticKey}|$currentDuration"
			if (generation != carryResolutionGeneration || listen.finalized ||
				currentSignature != signature || proof == null
			) return@requester
			carryAuthorityResolutions++
			dispatch(PlaybackInput.ExactIdEstablished(proof.videoId))
			evidence.resolverContext = evidence.resolverContext.copy(
				preResolvedNativeVideoId = proof.videoId,
				preResolvedNativeRoute = proof.route,
			)
			// Production retries the same real claim only after the immutable id is
			// installed. The claim dispatches ProgressCarried; no replay-only progress
			// value is injected here.
			val playedBeforeRestore = listen.playedMs
			carryRestoreAttemptsAfterResolution++
			restoreCarriedProgress()
			val restoredPlayedMs = (listen.playedMs - playedBeforeRestore).coerceAtLeast(0)
			if (restoredPlayedMs > 0) {
				carryProgressRestorationsAfterResolution++
				carriedPlayedMsAfterResolution += restoredPlayedMs
			}
			// Match the next production side effect: anything this exact item could
			// not claim is an incompatible pending continuation.
			TrackProgressCarry.abandon(source.packageName, listen.trackIdentity)
		}
	}

	// ---- events ----------------------------------------------------------------

	/**
	 * Feed one event.
	 *
	 * Returns **every** snapshot the event froze, in order. More than one is
	 * ordinary rather than exotic: the foreground-Shorts route banks an outgoing
	 * listen and can complete the incoming one in the same observation, and a
	 * harness that returned only the first would silently drop a finalization the
	 * engine was owed.
	 */
	fun apply(event: PlaybackEvent): List<SessionSnapshot> {
		val frozenBefore = finalized.size
		when (event) {
			is PlaybackEvent.Advance -> advance(event.millis)

			is PlaybackEvent.SessionMetadata -> {
				dispatch(PlaybackInput.PositionSeen(positionMs, establishFirst = false))
				pendingBundle = bundle.mergedWith(event)
				dispatch(
					PlaybackInput.MetadataPublished(
						identity = identityOf(pendingBundle),
						namesTabOnly = isTabTitle(pendingBundle.title),
						outgoingTransportHasExactId = identityOf(bundle).hasExactSourceItemId,
						hasPreResolvedNativeId =
							evidence.resolverContext.preResolvedNativeVideoId != null,
						outgoingTitle = bundle.title,
						nowMillis = clock.nowMillis(),
						elapsedRealtimeMs = clock.nowMillis(),
						nextInstanceToken = MediaSessionAdEvidence.nextTrackToken(),
					),
				)
			}

			is PlaybackEvent.PlaybackStateChanged -> {
				val previous = positionMs
				event.positionMs?.let { positionMs = it }
				dispatch(
					PlaybackInput.TransportChanged(
						transport = when {
							event.playing -> TransportState.PLAYING
							event.stopped -> TransportState.STOPPED
							else -> TransportState.PAUSED
						},
						speed = event.speed,
						previousPositionMs = previous,
						newPositionMs = positionMs,
						rawPositionMs = positionMs,
						durationMs = bundle.durationMs,
						transportHasExactId = identityOf(bundle).hasExactSourceItemId,
						elapsedRealtimeMs = clock.nowMillis(),
						nowMillis = clock.nowMillis(),
						nextInstanceToken = MediaSessionAdEvidence.nextTrackToken(),
					),
				)
			}

			// An ordinary MediaSession publishes no callback at all when its window
			// moves, so a foreground/background/minimised marker has no production
			// input to become and is **recorded, not dispatched**. That is a modelled
			// claim about the device and is documented as one — see [surfaceMarkers]
			// and `SurfaceTransitionReplayTest`.
			//
			// Picture-in-picture is different. It is the one surface that can leave a
			// native session with no readable progress at all, so it goes to the
			// reducer's own inference gate, which decides whether the session still
			// publishes progress of its own.
			is PlaybackEvent.SurfaceChanged -> if (
				event.surface != PlaybackEvent.Surface.PICTURE_IN_PICTURE
			) {
				surfaceMarkers += event.surface
			} else {
				dispatch(
					PlaybackInput.PictureInPictureObserved(
						nowMillis = clock.nowMillis(),
						// The marker *is* the observation: on a device this evidence
						// is "YouTube holds a visible window and media audio is
						// started", which `PipPlaybackInference` documents and which is
						// deliberately independent of what the MediaSession reports —
						// it exists precisely for the case where the session reports
						// nothing. Whether that evidence may be spent is the reducer's
						// gate, not this one's.
						playing = true,
						durationMs = bundle.durationMs,
					),
				)
			}

			is PlaybackEvent.UrlObserved -> {

				val stored = UrlEvidence.put(
					source.packageName,
					UrlEvidence.Evidence(
						host = event.host,
						videoId = event.videoId,
						isShort = event.isShort,
						playlistId = event.playlistId,
						raw = event.raw.ifEmpty { describeUrl(event) },
						atMillis = clock.nowMillis(),
					),
				)
				url = stored
				urlGeneration = stored.generation
				// A playlist is context, not a position: the store keeps the last
				// `list=` for three hours where a video id gets five minutes,
				// because the bar stops naming individual videos within seconds
				// while the playlist keeps advancing.
				if (evidence.resolverContext.playlistId == null) {
					UrlEvidence.playlistId(source.packageName, clock.nowMillis())?.let {
						evidence.resolverContext = evidence.resolverContext.copy(playlistId = it)
					}
				}
				evidence.resolverContext = resolverContextWithObservedUrl(
					evidence.resolverContext,
					stored,
					evidence.rejectedVideoIds,
				)
			}

			is PlaybackEvent.NotificationObserved -> {
				hint = NotificationHints.Hint(
					host = event.host,
					subText = event.host,
					title = event.title,
					text = event.text,
					atMillis = clock.nowMillis(),
				)
			}

			PlaybackEvent.NotificationCleared -> hint = null

			// A visible ad label. For a browser listen it is track-scoped evidence on
			// the snapshot; for a foreground Short it is what the parser classified
			// the item as, so it travels the tracker's own ad route.
			is PlaybackEvent.AdLabelObserved -> {
				evidence.explicitAdSignal = event.label
				shortAdSignal = event.label
				if (shortTracker.hasActive) observeShort(clock.nowMillis())
			}

			is PlaybackEvent.AccessibilityScan -> {
				evidence.accessibilityCoverage = if (event.sawYouTubeRoot) {
					MediaSessionAccessibilityEvidence.Coverage(
						instance = MediaSessionAdEvidence.TrackInstance(
							packageName = source.packageName,
							token = MediaSessionAdEvidence.nextTrackToken(),
							signature = listen.trackIdentity,
						),
						atMillis = clock.nowMillis(),
						urlGeneration = urlGeneration.takeIf { it > 0 },
						videoId = url?.videoId,
					)
				} else {
					null
				}
			}

			is PlaybackEvent.NativePlaylistObserved -> {
				evidence.resolverContext = evidence.resolverContext.copy(
					nativePlaylistName = event.name,
					nativePlaylistOwner = event.owner,
					nativePlaylistTotal = event.total,
				)
			}

			is PlaybackEvent.NativeIdentityPreResolved -> {
				evidence.resolverContext = evidence.resolverContext.copy(
					preResolvedNativeVideoId = event.videoId,
					preResolvedNativeRoute = event.route,
				)
				dispatch(PlaybackInput.ExactIdEstablished(event.videoId))
			}

			is PlaybackEvent.PresentationAttributionEstablished -> {
				evidence.resolverContext = evidence.resolverContext.copy(
					preResolvedNativeVideoId = event.videoId,
					preResolvedNativeRoute = NativePreResolvedRoute.RAW_TITLE_CHANNEL,
				)
				dispatch(PlaybackInput.ExactIdEstablished(event.videoId))
				dispatch(
					PlaybackInput.PresentationAttributionEstablished(
						sourceItemId = event.videoId,
						presentationDurationMs = event.durationMs,
					),
				)
			}

			// The accessibility tree named a Short. Straight into the production
			// `ForegroundShortTracker`, exactly as `SessionProbe` does with a parsed
			// observer event — the tracker owns the measurement, the caps, the
			// inference and the snapshot, and the MediaSession watch is suppressed
			// so the same seconds cannot be counted on both surfaces.
			is PlaybackEvent.ForegroundShortObserved -> {
				evidence.foregroundShort = true
				evidence.ownerHandle = event.ownerHandle
				shortTitle = event.title
				shortHandle = event.ownerHandle
				shortTotalSeconds = (event.durationMs ?: 0) / 1000
				shortSeekbarSeconds = event.positionMs / 1_000
				shortHasNoSeekbar = event.durationMs == null
				shortProofMissing = false
				observeShort(clock.nowMillis())
				suppressMediaSessionForShort()
			}

			// The Short is still playing and its progress surface has gone. The
			// amount credited is **not** this event's to decide: the event says how
			// much wall clock passed while YouTube held a visible window, and
			// `ForegroundShortTracker`'s own `PipPlaybackInference` decides what that
			// is worth — one step at a time, capped, and never past the Short's own
			// length. That is the production owner of this number.
			is PlaybackEvent.ProgressSurfaceLost -> {
				shortProofMissing = true
				noteShortProofMissing(
					clock.nowMillis(),
					"progress surface lost",
					inferredPlaying = event.playing,
					displayOff = event.displayOff,
				)
				// One observation per second of wall clock, which is what the device
				// poll does, because the inference credits only between two
				// consecutive observations that both say playing.
				var remaining = event.inferredMs
				while (remaining >= 1_000) {
					clock.advance(1_000)
					remaining -= 1_000
					noteShortProofMissing(
						clock.nowMillis(),
						"progress surface lost",
						inferredPlaying = event.playing,
						displayOff = event.displayOff,
					)
				}
				if (remaining > 0 && shortTracker.hasActive) {
					clock.advance(remaining)
					noteShortProofMissing(
						clock.nowMillis(),
						"progress surface lost",
						inferredPlaying = event.playing,
						displayOff = event.displayOff,
					)
				}
			}

			// The foreground-Shorts route detects its own loops off the seekbar, and
			// that rule is `ForegroundShortTracker`'s: a wrap is a seekbar reading
			// that went back to the start. So this is expressed as one, rather than
			// as a flag the harness sets on the snapshot it builds itself.
			PlaybackEvent.LoopObserved -> if (shortTracker.hasActive) {
				shortSeekbarSeconds = 0
				observeShort(clock.nowMillis())
			}

			is PlaybackEvent.VideoIdRejected -> {
				evidence.rejectedVideoIds += event.videoId
				evidence.resolverContext = evidence.resolverContext.copy(
					rejectedVideoIds = evidence.rejectedVideoIds.toSet(),
				)
				// The latch goes for good. A later pass may still re-derive the
				// same id from live evidence, and `rejectedVideoIds` is what
				// catches it there — which is why `identityAfterCorroboration`
				// takes the rejected set as well as the flag.
				if (evidence.latchedVideo?.videoId == event.videoId) evidence.latchedVideo = null
			}

			// The transport is rebuilt; the listen is not. Both halves run for real:
			// the outgoing transport parks its progress through the production
			// `TrackProgressCarry`, and the replacement claims it back.
			PlaybackEvent.SessionRecreated -> recreateSession()

			PlaybackEvent.NativeSessionRecreatedAwaitingCarryAuthority ->
				recreateNativeSessionAwaitingCarryAuthority()

			is PlaybackEvent.ProbeDisposed -> dispatch(
				PlaybackInput.Disposed(
					finalize = event.finalize,
					allowContinuation = event.allowContinuation,
					elapsedRealtimeMs = clock.nowMillis(),
					continuationOpen = continuationToken != null,
				),
			)

			// Memory dies, disk does not. *Which* stores are memory is
			// [RunScopedEvidence]'s answer, and it is the same call the listener
			// service makes on the boundary it owns — a second list here is a
			// harness modelling a different process from the one the app has.
			PlaybackEvent.ProcessRestarted -> {
				RunScopedEvidence.clearAll(includeCarriedProgress = true)
				shortTracker.discard("process restarted")
				shortHasNoSeekbar = false
				shortProofMissing = false
				shortAdSignal = null
				shortSeekbarSeconds = 0
				shortTotalSeconds = 0
				shortTitle = null
				shortHandle = null
				hint = null
				url = null
				urlGeneration = 0
				bundle = Bundle()
				pendingBundle = Bundle()
				evidence = Evidence()
				lastStableIdentity = null
				positionMs = null
				continuationToken = null
				continuationIdentity = null
				continuationVerdict = null
				listen = freshListen()
			}

			PlaybackEvent.IdleDeadlineReached -> dispatch(
				PlaybackInput.IdleDeadlineReached(
					durationMs = listen.establishedDurationMs(bundle.durationMs),
					elapsedRealtimeMs = clock.nowMillis(),
				),
			)

			is PlaybackEvent.Finalized -> if (shortTracker.hasActive) {
				endForegroundShort(event.reason)
			} else {
				dispatch(PlaybackInput.FinalizeRequested(event.reason, clock.nowMillis()))
			}
		}
		observeIdentity()
		return finalized.drop(frozenBefore)
	}

	/**
	 * Wall clock moves, and so does the transport's position while it is playing.
	 *
	 * Played time is deliberately *not* touched here. The reducer extrapolates it
	 * from the moment the clock started, which is exactly what `Watch` does with
	 * `SystemClock.elapsedRealtime()`, so an `Advance` that this harness forgot to
	 * account for would show up as a wrong percentage rather than as nothing.
	 */
	private fun advance(millis: Long) {
		if (shortTracker.hasActive) return advanceForegroundShort(millis)
		clock.advance(millis)
		if (listen.transport != TransportState.PLAYING) return
		positionMs = (positionMs ?: 0L) + (millis * listen.speed).toLong()
	}

	/**
	 * Time passing while the foreground-Shorts route owns the player.
	 *
	 * The MediaSession watch is suppressed, so nothing accrues there; what moves
	 * is the seekbar, and the tracker is polled with it exactly as the observer
	 * polls it on a device.
	 *
	 * Two shapes, and the difference is production's:
	 *
	 *  - a **readable seekbar** advances and one observation carries the whole
	 *    interval. The tracker's own `acceptedDelta` bounds it against elapsed
	 *    wall clock, so a jump it would refuse on a device is refused here.
	 *  - **no readable progress at all** — picture-in-picture, or a Short YouTube
	 *    drew no seekbar for — credits wall clock instead, and that route caps a
	 *    single step at three seconds. So it is polled once a second, which is
	 *    what the device does and the only way the caps mean anything.
	 */
	private fun advanceForegroundShort(millis: Long) {

		if (shortProofMissing) {
			clock.advance(millis)
			return
		}
		if (!shortHasNoSeekbar) {
			clock.advance(millis)
			shortSeekbarSeconds += millis / 1000
			observeShort(clock.nowMillis())
			return
		}
		var remaining = millis
		while (remaining >= 1_000) {
			clock.advance(1_000)
			remaining -= 1_000
			observeShort(clock.nowMillis())
		}
		if (remaining > 0) clock.advance(remaining)
	}

	// ---- the foreground-Shorts route ------------------------------------------

	/** The epoch the probe stamps on every foreground-Short observation. */
	private fun shortSourceEpoch(): Long =
		NativeSourceSwitches.epochFor(source.packageName) ?: 1L

	/**
	 * One poll of the Shorts accessibility tree, through the production tracker.
	 *
	 * Which observation shape is used is the parser's decision on a device and is
	 * reproduced by the same rule: a footer with a readable seekbar is
	 * `OrganicObservation`; a proven Short with no progress at all is
	 * `UnmeasuredObservation`, which is the route that credits inferred wall
	 * clock and marks the surface lost.
	 */
	private fun observeShort(nowMillis: Long) {
		val handle = shortHandle ?: return
		val signal = shortAdSignal
		val result = if (signal != null) {
			NativeShortParser.Result.Ad(
					signal = signal,
					title = shortTitle,
					currentSeconds = shortSeekbarSeconds,
					totalSeconds = shortTotalSeconds,
			)
		} else if (shortHasNoSeekbar) {
			NativeShortParser.Result.OrganicUnmeasured(
					title = shortTitle,
					ownerHandle = handle,
			)
		} else {
			NativeShortParser.Result.Organic(
					title = shortTitle,
					ownerHandle = handle,
					currentSeconds = shortSeekbarSeconds,
					totalSeconds = shortTotalSeconds,
			)
		}
		routeNativeShortEvent(
			NativeShortsObserver.Event.Parsed(
				result = result,
				observedAtMillis = nowMillis,
				inferredPlaying = shortHasNoSeekbar,
			),
		)
	}

	/** A poll that could not read the player — the picture-in-picture signature. */
	private fun noteShortProofMissing(
		nowMillis: Long,
		reason: String,
		inferredPlaying: Boolean = true,
		displayOff: Boolean = false,
	) {
		if (!shortTracker.hasActive) return
		routeNativeShortEvent(
			NativeShortsObserver.Event.Missing(
			reason = reason,
			observedAtMillis = nowMillis,
			progressSurfaceLost = true,
			inferredPlaying = inferredPlaying,
			displayOff = displayOff,
			),
		)
	}

	/** SessionProbe's host half: adapter inputs -> tracker/reducer -> ordered effects. */
	private fun routeNativeShortEvent(event: NativeShortsObserver.Event) {
		shortAdapter.read(
			event = event,
			foregroundSurfaceOwned = shortTracker.hasActive,
			hostNowMillis = clock.nowMillis(),
		).forEach { input ->
			when (input) {
				is PlaybackInput.ForegroundSurface -> {
					val update = shortTracker.reduce(input)
					finalized += update.finalized
					if (shortTracker.hasActive) suppressMediaSessionForShort()
					else releaseMediaSessionFromShort()
				}

				is PlaybackInput.PictureInPictureObserved -> dispatch(input)
				else -> error("native Shorts adapter emitted unsupported replay input: $input")
			}
		}
	}

	/**
	 * End a foreground Short the way a device ends one.
	 *
	 * There is no "finalize" call on this route. A Short ends because its player
	 * stopped being readable and stayed that way past
	 * `ForegroundShortTracker.MISSING_PROOF_GRACE_MS` — so that is what is fed,
	 * and the tracker decides whether anything is scored. A listen already banked
	 * at its own full length produces nothing here, which is correct: it has
	 * already been finalized.
	 *
	 * The grace is expressed by handing the tracker a later reading rather than by
	 * moving this trace's clock, so a scenario's own timeline is not silently
	 * extended by three seconds every time a Short ends.
	 */
	private fun endForegroundShort(reason: String) {
		shortProofMissing = true
		val now = clock.nowMillis()
		// The first poll may already be past the grace — a Short whose surface went
		// away thirty seconds ago ends on the next poll, not three seconds after
		// this one — so both results are collected.
		routeNativeShortEvent(
			NativeShortsObserver.Event.Missing(reason, now),
		)
		if (shortTracker.hasActive) {
			routeNativeShortEvent(
				NativeShortsObserver.Event.Missing(
					reason,
					now + ForegroundShortTracker.MISSING_PROOF_GRACE_MS,
				),
			)
		}
	}

	/**
	 * Hand the player to the foreground route, as `SessionProbe` does.
	 *
	 * The MediaSession watch measures nothing while the Short owns the player,
	 * which is the whole reason the two surfaces do not double-count.
	 */
	private fun suppressMediaSessionForShort() {
		if (listen.suppressedByForegroundShort) return
		dispatch(
			PlaybackInput.ForegroundShortTookOver(
				// The MediaSession side of a foreground Short publishes nothing that
				// names the Short, so it is never describing the same item.
				shortDescribesSameItem = false,
				elapsedRealtimeMs = clock.nowMillis(),
			),
		)
	}

	private fun releaseMediaSessionFromShort() {
		evidence.foregroundShort = false
		shortHasNoSeekbar = false
		shortProofMissing = false
		shortAdSignal = null
		shortSeekbarSeconds = 0
		shortTotalSeconds = 0
		shortTitle = null
		shortHandle = null
		if (!listen.suppressedByForegroundShort) return
		dispatch(
			PlaybackInput.ForegroundShortReleased(
				identity = identityOf(bundle),
				nowMillis = clock.nowMillis(),
				elapsedRealtimeMs = clock.nowMillis(),
				nextInstanceToken = MediaSessionAdEvidence.nextTrackToken(),
			),
		)
	}

	// ---- session recreation ----------------------------------------------------

	/**
	 * Tear the transport down and build its replacement, for real.
	 *
	 * The production path end to end: the outgoing transport reduces
	 * `SessionDestroyed`, which parks its progress in `TrackProgressCarry`; a
	 * fresh [ListenState] stands in for the replacement `Watch`; and the first
	 * metadata bundle it publishes triggers the claim. The frozen start, the
	 * banked play time and the instance token all have to survive, and if they
	 * do not the dedup key moves and the scenario fails.
	 */
	private fun recreateSession() {
		dispatch(
			PlaybackInput.SessionDestroyed(
				elapsedRealtimeMs = clock.nowMillis(),
				continuationOpen = continuationToken != null,
			),
		)
		val carriedIdentity = listen.trackIdentity
		// The replacement is built holding the page's current bundle, exactly as
		// `Watch` is, which is why it can claim a continuation immediately.
		listen = freshListen(alreadyNaming = bundle != Bundle())
			.copy(trackIdentity = carriedIdentity)
		positionMs = null
		restoreCarriedProgress()
	}

	/**
	 * Rebuild a native binding from its still-current metadata bundle without
	 * copying the outgoing binding's resolver-derived id. This is the production
	 * shape that makes the first constructor-time claim fail closed and requires
	 * `RequestCarryAuthority` to resolve the id before retrying the real claim.
	 */
	private fun recreateNativeSessionAwaitingCarryAuthority() {
		check(source.isNative) { "only a native replacement loses resolver-derived carry identity" }
		dispatch(
			PlaybackInput.SessionDestroyed(
				elapsedRealtimeMs = clock.nowMillis(),
				continuationOpen = continuationToken != null,
			),
		)
		listen = freshListen()
		positionMs = null
		// These are fields of the production binding, not package-global evidence.
		// A replacement begins with a fresh request generation and no inherited
		// resolver result even though the MediaSession bundle is still current.
		evidence = Evidence()
		lastStableIdentity = null
		invalidateCarryAuthority()
		pendingBundle = bundle
		dispatch(
			PlaybackInput.MetadataPublished(
				identity = identityOf(pendingBundle),
				namesTabOnly = false,
				outgoingTransportHasExactId = false,
				hasPreResolvedNativeId = false,
				outgoingTitle = null,
				nowMillis = clock.nowMillis(),
				elapsedRealtimeMs = clock.nowMillis(),
				nextInstanceToken = MediaSessionAdEvidence.nextTrackToken(),
			),
		)
	}

	private fun openContinuation() {
		val identityKey = listen.trackIdentity
		val verdict = lastStableIdentity ?: YouTubeProbe.Identity.Unconfirmed(
			"identity was not established before the session ended",
		)
		val token = TrackProgressCarry.remember(
			packageName = source.packageName,
			trackIdentity = identityKey,
			progress = TrackProgressCarry.Progress(
				playedMs = listen.playedMs,
				trackStartedAtEpochSec = listen.startedAtEpochSec,
				fastestSpeedSeen = listen.fastestSpeedSeen,
				atMillis = clock.nowMillis(),
				lastPositionMs = positionMs,
				loopDetected = listen.loopDetected,
				identity = verdict,
				explicitAdSignal = evidence.explicitAdSignal,
				accessibilityCoverage = evidence.accessibilityCoverage,
				trackInstanceToken = listen.instanceToken,
			),
		) ?: return
		continuationToken = token
		continuationIdentity = identityKey
		continuationVerdict = verdict
	}

	private fun cancelContinuation() {
		val token = continuationToken ?: return
		TrackProgressCarry.cancel(
			source.packageName,
			continuationIdentity ?: listen.trackIdentity,
			token,
		)
		continuationToken = null
		continuationIdentity = null
		continuationVerdict = null
	}

	private fun restoreCarriedProgress() {
		val carried = TrackProgressCarry.claim(
			packageName = source.packageName,
			trackIdentity = listen.trackIdentity,
			now = clock.nowMillis(),
			resumePositionMs = positionMs,
			resumeVideoId = (lastStableIdentity as? YouTubeProbe.Identity.Confirmed)?.videoId
				?: evidence.latchedVideo?.videoId,
		) ?: return
		dispatch(
			PlaybackInput.ProgressCarried(
				playedMs = carried.playedMs,
				startedAtEpochSec = carried.trackStartedAtEpochSec,
				fastestSpeedSeen = carried.fastestSpeedSeen,
				loopDetected = carried.loopDetected,
				instanceToken = carried.trackInstanceToken,
				lastPositionMs = carried.lastPositionMs,
				currentPositionMs = positionMs,
				durationMs = bundle.durationMs,
				nowMillis = clock.nowMillis(),
			),
		)
		continuationToken = null
		continuationIdentity = null
		continuationVerdict = null
		carried.identity?.let { identity ->
			lastStableIdentity = when (identity) {
				is YouTubeProbe.Identity.Confirmed -> identity.copy(
					source = "${identity.source} (carried across session restart)",
				).also { evidence.latchedVideo = it }
				else -> identity
			}
		}
		evidence.explicitAdSignal = carried.explicitAdSignal
		carried.accessibilityCoverage?.let { evidence.accessibilityCoverage = it }
	}

	// ---- identity --------------------------------------------------------------

	/** The identity the probe would build from this bundle. Production rules. */
	private fun identityOf(bundle: Bundle) = TrackIdentity(
		title = bundle.title,
		artist = bundle.artist,
		album = bundle.album,
		durationMs = bundle.durationMs,
		sourceItemId = if (source.isNative) {
			(nativeIdentity(bundle) as? YouTubeProbe.Identity.Confirmed)?.videoId
		} else {
			null
		},
	)

	private fun isTabTitle(title: String?): Boolean =
		com.rustedwax.app.detect.BrowserTabMetadata.isTabTitle(source.packageName, title)

	private fun nativeIdentity(bundle: Bundle) = YouTubeProbe.identifyNative(
		source.packageName,
		YouTubeProbe.NativeMetadataFields(
			mediaId = bundle.mediaId,
			mediaUri = bundle.mediaUri,
			artUri = bundle.artUri,
		),
	)

	/**
	 * Re-decide identity from the evidence available right now, and latch it.
	 *
	 * Run after every event because that is what the probe does — a URL, a
	 * notification and a metadata change each re-enter identity, and the whole
	 * reason the URL store has an `onEvidence` callback is that the first
	 * verdict for a track is always made before the address bar has spoken.
	 */
	private fun observeIdentity() {
		val live = liveIdentity()
		if (live is YouTubeProbe.Identity.Unconfirmed && live.provenOtherSite) {
			evidence.taintedReason = live.reason
		}
		if (evidence.latchedVideo == null &&
			live is YouTubeProbe.Identity.Confirmed &&
			live.videoId !in evidence.rejectedVideoIds
		) {
			evidence.latchedVideo = live.copy(source = "${live.source} (latched)")
		}
		corroborateLatch()
		lastStableIdentity = selectedIdentity()
	}

	/**
	 * Check the latched id against the video's own page, and drop it if they
	 * disagree.
	 *
	 * The probe's own step, using the probe's own predicate. Without it a stale
	 * address bar — Chromium keeps the previous tab's URL readable for minutes —
	 * would supply an id for a track it has nothing to do with, and the only
	 * thing that would catch it is the finalized corroborator, one stage too
	 * late and under the wrong name. The page facts come from the same cache the
	 * engine reads through `knownVideoFor`.
	 */
	private fun corroborateLatch() {
		val latched = evidence.latchedVideo ?: return
		val known = knownVideo(latched.videoId) ?: return
		val titleEvidence = if (known.title != null && bundle.title != null) {
			SessionProbe.titleEvidence(known.title, bundle.title!!)
		} else {
			null
		}
		val weakEvidenceAllowed = titleEvidence?.let {
			SessionProbe.titleEvidenceMayRetainObservedId(
				evidence = it,
				candidateVideoId = latched.videoId,
				candidateGeneration = latched.urlGeneration,
				observedVideoId = evidence.resolverContext.observedVideoId,
				observedGeneration = evidence.resolverContext.urlGeneration,
				sessionDurationMs = bundle.durationMs,
				pageDurationSeconds = known.lengthSeconds,
			)
		} == true
		val disagreement = SessionProbe.latchDisagreement(
			titleEvidence = titleEvidence,
			weakEvidenceAllowed = weakEvidenceAllowed,
			pageTitle = known.title,
			sessionTitle = bundle.title,
			sessionDurationMs = bundle.durationMs,
			pageDurationSeconds = known.lengthSeconds,
		) ?: return
		if (disagreement.contradicts) evidence.rejectedVideoIds += latched.videoId
		evidence.latchedVideo = null
	}

	/**
	 * Already-known facts about a video, cache-only.
	 *
	 * Wired by the harness to the same store the engine's `knownVideoFor`
	 * callback reads, so the probe half and the finalization half corroborate
	 * against one set of facts rather than two.
	 */
	var knownVideo: (String) -> SessionProbe.KnownVideo? = { null }

	/** What the evidence says this instant, with no memory of the track. */
	private fun liveIdentity(): YouTubeProbe.Identity = if (source.isNative) {
		nativeIdentity(bundle)
	} else {
		// Evidence whose id was disproven for this track is not evidence — and
		// dropping the *reading* rather than the verdict is what keeps the
		// notification's independent proof of the site intact. Without this the
		// track stops being YouTube at all, and a listen that deserves an
		// explanation in Not logged disappears instead.
		YouTubeProbe.identify(
			md = null,
			hint = hint,
			url = url?.takeUnless { it.videoId != null && it.videoId in evidence.rejectedVideoIds },
			soleSession = true,
		)
	}

	/**
	 * The verdict, by the production selection rule.
	 *
	 * A scenario cannot grant itself an identity — it can only supply the
	 * evidence one is derived from.
	 */
	private fun selectedIdentity(): YouTubeProbe.Identity =
		evidence.taintedReason?.let {
			YouTubeProbe.Identity.Unconfirmed("another site was proven earlier: $it", true)
		} ?: SessionProbe.identityAfterCorroboration(
			latched = evidence.latchedVideo,
			live = liveIdentity(),
			rejectedVideoIds = evidence.rejectedVideoIds,
			// `rejectedThisPass` is a per-callback local in `AndroidSessionBinding`
			// and a finalize is always a later pass than the rejection that set
			// it, so it is false here by construction. The rejected set is what
			// carries the rejection forward.
			rejectedThisPass = false,
		)

	// ---- the freeze ------------------------------------------------------------

	private fun snapshot(): SessionSnapshot {
		val durationMs = listen.establishedDurationMs(bundle.durationMs)
		val identity = continuationVerdict ?: lastStableIdentity ?: selectedIdentity()
		val confirmed = identity as? YouTubeProbe.Identity.Confirmed
		// The MediaSession's own measurement, and only ever that. A foreground
		// Short is measured by `ForegroundShortTracker`, which builds its own
		// snapshot; while it owns the player this watch is suppressed and
		// `playedMsAt` is zero by construction, which is the production guarantee
		// that the same seconds are never counted on both surfaces.
		val playedMs = listen.playedMsAt(clock.nowMillis())
		return SessionSnapshot(
			packageName = source.packageName,
			appLabel = source.label,
			isTarget = NativeSourceSwitches.acceptsPackage(source.packageName),
			title = bundle.title,
			artist = bundle.artist,
			album = bundle.album,
			durationMs = durationMs,
			positionMs = positionMs,
			playedMs = playedMs,
			loopDetected = listen.loopDetected,
			explicitAdSignal = evidence.explicitAdSignal,
			browserEvidenceEnabled = browserEvidenceEnabled && !source.isNative,
			accessibilityCoverage = evidence.accessibilityCoverage,
			playbackState = when (listen.transport) {
				TransportState.PLAYING -> "STATE_PLAYING"
				TransportState.STOPPED -> "STATE_STOPPED"
				TransportState.PAUSED -> "STATE_PAUSED"
				TransportState.OTHER -> "STATE_PAUSED"
			},
			foregroundProgressLost = false,
			inferredPlayedMs = listen.pipInferredMs,
			isPlaying = listen.transport == TransportState.PLAYING,
			percentPlayed = durationMs
				?.takeIf { it > 0 }
				?.let { playedMs.toDouble() / it },
			identity = identity,
			resolverContext = evidence.resolverContext.copy(
				knownTitle = confirmed?.let { bundle.title },
				knownChannel = confirmed?.let { bundle.artist },
				knownDurationSeconds = confirmed?.let { durationMs?.div(1000) },
				rejectedVideoIds = evidence.rejectedVideoIds.toSet(),
			),
			notificationHint = hint,
			metadataLines = emptyList(),
			firstObservedPositionMs = listen.firstSeenPositionMs,
			trackStartedAtEpochSec = listen.startedAtEpochSec,
			trackInstanceToken = listen.instanceToken,
			genre = bundle.genre,
			sourceEpoch = NativeSourceSwitches.epochFor(source.packageName),
			sourceProof = SourceProof.MEDIA_SESSION,
			ownerHandle = evidence.ownerHandle,
		)
	}

	private fun freeze() {
		finalized += snapshot()
		// The next listen inherits the source and the browser's evidence, and
		// nothing else. Carrying anything further is the bug class the frozen
		// snapshot exists to prevent. The reducer has already latched `finalized`,
		// so the next `SessionMetadata` starts a track rather than refining this one.
		bundle = Bundle()
		// Do not clear `pendingBundle` here. A metadata-driven track boundary
		// finalizes through a reducer `before` effect, then the outer transition's
		// `InstallMetadata` effect must still install the new controller bundle.
		// Production reads that same bundle from the callback after finalization;
		// erasing it here made replay-only track changes lose their title.
		evidence = Evidence()
		lastStableIdentity = null
		invalidateCarryAuthority()
		positionMs = null
		listen = freshListen()
	}

	private fun describeUrl(event: PlaybackEvent.UrlObserved): String = buildString {
		append("https://").append(event.host ?: "?")
		when {
			event.isShort && event.videoId != null -> append("/shorts/").append(event.videoId)
			event.videoId != null -> append("/watch?v=").append(event.videoId)
		}
		event.playlistId?.let { append(if (event.videoId == null) "/playlist?list=" else "&list=").append(it) }
	}

	companion object {
		/** The pre-resolved routes, re-exported so scenarios read as prose. */
		val PLAYLIST_ROUTE = NativePreResolvedRoute.PLAYLIST
		val HISTORY_ROUTE = NativePreResolvedRoute.HISTORY
		val RAW_TITLE_CHANNEL_ROUTE = NativePreResolvedRoute.RAW_TITLE_CHANNEL
		val STRUCTURED_MUSIC_ROUTE = NativePreResolvedRoute.STRUCTURED_MUSIC
	}
}
