package com.rustedwax.app.replay.reference.phase01

import com.rustedwax.core.ListenState
import com.rustedwax.app.detect.NativeShortsObserver
import com.rustedwax.app.detect.LegacyEvidenceCallbacks
import com.rustedwax.app.detect.NativeSourceSwitches
import com.rustedwax.core.PlaybackEffect
import com.rustedwax.core.PlaybackInput
import com.rustedwax.core.PlaybackReducer
import com.rustedwax.app.detect.RunScopedEvidence
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.SourceProfile
import com.rustedwax.core.TrackIdentity
import com.rustedwax.core.TransportState
import com.rustedwax.app.detect.YouTubeProbe

/**
 * One script, run through the pre-migration state machine and the replacement.
 *
 * ## What this compares, and why that is worth doing
 *
 * The old side here is a retained legacy `phase01/SessionProbe.kt`. It measures, decides
 * lifecycle, resolves identity through the same production `YouTubeProbe`, and
 * emits real `SessionSnapshot`s through its own `onTrackFinalized`. It has never
 * seen `PlaybackReducer`, which did not exist when it was written. That is what
 * makes disagreement meaningful.
 *
 * ## What this is not
 *
 * [ReducerRun] translates a script step into `PlaybackInput`. That translation
 * is **harness code, not the production adapter.** Production's translation from
 * Android callbacks lives in `detect/SessionProbe.kt` and is not executed by any
 * JVM test — that is the separate Android-lifecycle gate, and it is reported as
 * NOT ESTABLISHED rather than covered here. So this file establishes that the
 * two *state machines* agree given equivalent input; it establishes nothing
 * about the adapter that produces that input in production.
 *
 * Saying so is the point. A harness that quietly counted its own translator as
 * production coverage is exactly the error this remediation exists to correct.
 */

/** An instruction both implementations understand. */
sealed interface ParityStep {

	/** The session publishes a metadata bundle. */
	data class Metadata(
		val title: String?,
		val artist: String? = null,
		val durationMs: Long? = null,
		val mediaId: String? = null,
	) : ParityStep

	/** The session publishes a transport state. */
	data class Transport(
		val state: Int,
		val positionMs: Long,
		val speed: Float = 1f,
	) : ParityStep

	/** Wall time passes. Both sides read it from the same virtual clock. */
	data class Advance(val millis: Long) : ParityStep

	/** The MediaSession goes away. */
	data object DestroySession : ParityStep

	/**
	 * Chromium rebuilds its session: a *different* token for the same package.
	 *
	 * The one input that exercises continuation end to end — the vanished
	 * transport parks its progress, the replacement claims it, and one listen
	 * keeps one instance token across two controllers. A same-token republish
	 * would be a no-op in both implementations, so the stage always mints a new
	 * one.
	 */
	data object ReplaceSession : ParityStep

	/** The active-session list goes empty without the session being destroyed. */
	data object RemoveSession : ParityStep

	data object RelistDestroyedSession : ParityStep

	data class StopProbe(val finalizeTracks: Boolean = true) : ParityStep

	/** The listener service comes back and re-attaches to whatever is active. */
	data object StartProbe : ParityStep

	/**
	 * One picture-in-picture observation, through the production observer bus.
	 *
	 * [atMillis] is passed rather than read from the wall clock so both runs
	 * observe at the same instants; the accumulator caps on the interval between
	 * observations, and two runs started a second apart would otherwise credit
	 * different amounts for reasons that have nothing to do with either
	 * implementation.
	 */
	data class PictureInPicture(val playing: Boolean, val atMillis: Long) : ParityStep
}

/** Start/stop for whichever probe a run is driving. */
interface ProbeControl {
	fun start()
	fun stop(finalizeTracks: Boolean)
}

/**
 * The Android surface a parity script drives, shared by both implementations.
 *
 * One class rather than one per side, because a difference in the *stage* would
 * be indistinguishable from a difference in the state machines: if the reference
 * were handed a fresh controller where the mirror got a republished one, the
 * comparison would report a continuation bug that neither implementation has.
 */
class ParityStage(val packageName: String = PARITY_PACKAGE) {

	val time = VirtualTime()
	val manager = MediaSessionManager()
	val context = Context(manager)

	/** The controller currently published as active. */
	var controller: MediaController = MediaController(packageName)
		private set

	fun begin(probe: ProbeControl) {
		SystemClock.current = time
		manager.publish(listOf(controller))
		probe.start()
		time.drain()
	}

	fun perform(step: ParityStep, probe: ProbeControl) {
		when (step) {
			is ParityStep.Metadata -> controller.publishMetadata(step.asMetadata())
			is ParityStep.Transport -> controller.publishPlaybackState(
				PlaybackState(
					state = step.state,
					position = step.positionMs,
					playbackSpeed = step.speed,
					lastPositionUpdateTime = time.elapsedRealtime(),
				),
			)

			is ParityStep.Advance -> time.advance(step.millis)
			ParityStep.DestroySession -> controller.destroySession()
			ParityStep.ReplaceSession -> {
				controller = MediaController(packageName)
				manager.publish(listOf(controller))
			}

			ParityStep.RemoveSession -> manager.publish(emptyList())
			ParityStep.RelistDestroyedSession -> {
				manager.publish(emptyList())
				time.drain()
				manager.publish(listOf(controller))
			}
			is ParityStep.StopProbe -> probe.stop(step.finalizeTracks)
			ParityStep.StartProbe -> probe.start()
			is ParityStep.PictureInPicture -> LegacyEvidenceCallbacks.nativeShortEvent?.invoke(
				NativeShortsObserver.Event.Missing(
					reason = "progress surface lost",
					observedAtMillis = step.atMillis,
					progressSurfaceLost = true,
					inferredPlaying = step.playing,
				),
			)
		}
		time.drain()
	}

	private fun ParityStep.Metadata.asMetadata() = MediaMetadata(
		buildMap {
			title?.let { put(MediaMetadata.METADATA_KEY_TITLE, it) }
			artist?.let { put(MediaMetadata.METADATA_KEY_ARTIST, it) }
			durationMs?.let { put(MediaMetadata.METADATA_KEY_DURATION, it) }
			mediaId?.let { put(MediaMetadata.METADATA_KEY_MEDIA_ID, it) }
		},
	)
}

/**
 * What a finalized listen looks like, reduced to the facts both sides define.
 *
 * Deliberately not "every field of `SessionSnapshot`". The replacement is a
 * measurement and lifecycle reducer; it has no opinion on `metadataLines` or on
 * the notification hint, and comparing those would be comparing the old machine
 * against the absence of an opinion. Every field here is one both
 * implementations compute independently.
 */
data class ListenOutcome(
	val playedMs: Long,
	val durationMs: Long?,
	val loopDetected: Boolean,
	val firstSeenPositionMs: Long?,
	val title: String?,
	val sourceItemId: String?,
)

/** Fixed package for the scripts below; native so exact-ID routes are live. */
const val PARITY_PACKAGE: String = YouTubeProbe.YOUTUBE_PACKAGE

/**
 * Reset every store the two runs share.
 *
 * Both sides call the same production singletons. Without this, run *n+1*
 * inherits run *n*'s URL evidence, ad evidence and carried progress, and the
 * second implementation to run would be judged on the first one's leftovers.
 */
fun resetSharedState() {
	RunScopedEvidence.clearAll(includeCarriedProgress = true)
	LegacyEvidenceCallbacks.nativeShortEvent = null
	NativeSourceSwitches.configureForReplay(
		NativeSourceSwitches.Config(
			youTubeScrobbling = true,
			youtubeEnabled = true,
			youtubeMusicEnabled = true,
		),
	)
}

/** Runs a script through the authentic pre-migration `SessionProbe`. */
object ReferenceRun {

	fun play(script: List<ParityStep>): List<ListenOutcome> = snapshots(script).map {
		ListenOutcome(
			playedMs = it.playedMs,
			durationMs = it.durationMs,
			loopDetected = it.loopDetected,
			firstSeenPositionMs = it.firstObservedPositionMs,
			title = it.title,
			sourceItemId = (it.identity as? YouTubeProbe.Identity.Confirmed)?.videoId,
		)
	}

	/**
	 * The frozen listens the pre-migration probe emitted, unreduced.
	 *
	 * `play` narrows these to the fields the reducer also computes. The
	 * end-to-end parity comparison needs the whole snapshot, because what it
	 * feeds the engine is what the old probe actually handed it.
	 */
	fun snapshots(
		script: List<ParityStep>,
		packageName: String = PARITY_PACKAGE,
	): List<SessionSnapshot> {
		resetSharedState()
		val stage = ParityStage(packageName)
		UrlWatcherService.enabled = false

		val probe = SessionProbe(stage.context)
		val finalized = mutableListOf<SessionSnapshot>()
		probe.onTrackFinalized = { finalized += it }

		val control = object : ProbeControl {
			override fun start() = probe.start()
			override fun stop(finalizeTracks: Boolean) = probe.stop(finalizeTracks)
		}

		stage.begin(control)
		script.forEach { stage.perform(it, control) }

		probe.stop()
		stage.time.drain()
		SystemClock.current = VirtualTime()
		return finalized.toList()
	}
}

class ReducerRun(private val speedScale: Double = 1.0) {

	private val reducer = PlaybackReducer(SourceProfile.playbackCapabilitiesFor(PARITY_PACKAGE))
	private val outcomes = mutableListOf<ListenOutcome>()
	private var nextToken = 1L

	/** Same virtual clock the reference side runs on, so "now" means one thing. */
	private val time = VirtualTime()

	fun play(script: List<ParityStep>): List<ListenOutcome> {
		resetSharedState()
		var state = ListenState(
			trackIdentity = TrackIdentity(
				title = null,
				artist = null,
				album = null,
				durationMs = null,
			),
			instanceToken = nextToken++,
			instanceEstablishedAtMillis = 0,
			startedAtEpochSec = 0,
		)
		var durationMs: Long? = null
		var lastPositionMs: Long? = null

		fun applyWith(outgoingDuration: Long?, input: PlaybackInput) {
			val transition = reducer.reduce(state, input)
			// The reducer's contract: `before` reads the outgoing listen, so a
			// finalize is recorded against the state still installed.
			if (transition.before.any { it is PlaybackEffect.Finalize }) {
				record(state, outgoingDuration)
			}
			state = transition.state
			if (transition.effects.any { it is PlaybackEffect.Finalize }) record(state, durationMs)
		}

		fun apply(input: PlaybackInput) = applyWith(durationMs, input)

		for (step in script) {
			when (step) {
				is ParityStep.Metadata -> {
					// The outgoing listen's length, captured before the incoming
					// bundle overwrites it. Recording after the update credited the
					// finalized track with its successor's duration — a harness
					// fault the first parity run caught, which is the sort of thing
					// a comparison against a real second implementation is for.
					val outgoingDuration = durationMs
					step.durationMs?.let { durationMs = it }
					applyWith(outgoingDuration,
						PlaybackInput.MetadataPublished(
							identity = TrackIdentity(
								title = step.title,
								artist = step.artist,
								album = null,
								durationMs = step.durationMs,
								sourceItemId = step.mediaId,
							),
							namesTabOnly = false,
							outgoingTransportHasExactId =
								state.trackIdentity.sourceItemId != null,
							hasPreResolvedNativeId = false,
							outgoingTitle = state.trackIdentity.title,
							nowMillis = time.elapsedRealtime(),
							elapsedRealtimeMs = time.elapsedRealtime(),
							nextInstanceToken = nextToken++,
						),
					)
				}

				is ParityStep.Transport -> {
					val previous = lastPositionMs
					lastPositionMs = step.positionMs
					apply(
						PlaybackInput.TransportChanged(
							transport = transportOf(step.state),
							speed = PlaybackReducer.speedFactor(step.speed) * speedScale,
							previousPositionMs = previous,
							newPositionMs = step.positionMs,
							rawPositionMs = step.positionMs,
							durationMs = durationMs,
							transportHasExactId =
								state.trackIdentity.sourceItemId != null,
							elapsedRealtimeMs = time.elapsedRealtime(),
						),
					)
				}

				is ParityStep.Advance -> time.advance(step.millis)

				ParityStep.DestroySession -> apply(
					PlaybackInput.SessionDestroyed(
						elapsedRealtimeMs = time.elapsedRealtime(),
						continuationOpen = false,
					),
				)

				// Lifecycle beyond one controller's callbacks — session replacement,
				// listener teardown, the picture-in-picture bus — is the *host's*
				// work, not the reducer's, and this class is only a reducer. Modelling
				// it here would be inventing an adapter and then comparing the
				// invention against the reference. `CurrentRun` runs the real host
				// instead; these scripts belong there.
				else -> throw IllegalArgumentException(
					"$step is host lifecycle: run it through CurrentRun, not ReducerRun",
				)
			}
		}

		if (!state.finalized) {
			apply(
				PlaybackInput.FinalizeRequested(
					reason = "probe stopped",
					elapsedRealtimeMs = time.elapsedRealtime(),
				),
			)
		}
		return outcomes.toList()
	}

	private fun record(state: ListenState, durationMs: Long?) {
		outcomes += ListenOutcome(
			playedMs = state.playedMsAt(time.elapsedRealtime()),
			durationMs = state.establishedDurationMs(durationMs),
			loopDetected = state.loopDetected,
			firstSeenPositionMs = state.firstSeenPositionMs,
			title = state.trackIdentity.title,
			sourceItemId = state.trackIdentity.sourceItemId,
		)
	}

	private fun transportOf(state: Int): TransportState = when (state) {
		PlaybackState.STATE_PLAYING -> TransportState.PLAYING
		PlaybackState.STATE_PAUSED -> TransportState.PAUSED
		PlaybackState.STATE_STOPPED -> TransportState.STOPPED
		else -> TransportState.OTHER
	}
}
