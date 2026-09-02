package com.rustedwax.app.replay

import com.rustedwax.app.detect.FinalizedTrack
import com.rustedwax.app.detect.MediaSessionAccessibilityEvidence
import com.rustedwax.app.detect.MediaSessionAdEvidence
import com.rustedwax.app.detect.NativeSourceSwitches
import com.rustedwax.app.detect.NotificationHints
import com.rustedwax.app.detect.SessionProbe
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.UrlEvidence
import com.rustedwax.app.enrich.VerifiedIdentityCandidateCache
import com.rustedwax.app.scrobble.FinalizationObserver
import com.rustedwax.app.scrobble.FinalizationOutcome
import com.rustedwax.app.scrobble.IdentityResolverMode
import com.rustedwax.app.scrobble.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject

class ReplayHarness(
	val source: ReplaySource,
	val env: ReplayEnvironment = ReplayEnvironment(),
	browserEvidenceEnabled: Boolean = true,
	/**
	 * Where the engine's finalization coroutine runs.
	 *
	 * [Dispatchers.Unconfined] by default, so a scenario's assertions run after
	 * the work has finished rather than racing it. A scenario that needs to act
	 * *between* the synchronous prefilter and the asynchronous remainder — the
	 * only way to reach the stale-source guard at the top of the launch — passes
	 * a [DeferredDispatcher] and drains it by hand.
	 */
	private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
	/**
	 * Which transport object a frozen listen reaches the engine as.
	 *
	 * Both settings run the same
	 * production `FinalizationRuntime`; what differs is whether the snapshot it is
	 * handed is the one the probe froze or one that has been through the domain
	 * decomposition and back. See [Presentation].
	 */
	val presentation: Presentation = Presentation.REFERENCE,
	/** Independently executable old/new identity control flow for parity. */
	val identityResolverMode: IdentityResolverMode = IdentityResolverMode.TYPED,
) {

	enum class Presentation {
		/** The snapshot the probe froze, handed over unchanged. The reference path. */
		REFERENCE,

		/** The same listen, through `FinalizedTrack.from(…).toSessionSnapshot()`. */
		DOMAIN,
	}

	val trace = PlaybackTrace(source, env.clock, browserEvidenceEnabled)

	/** Every snapshot this run froze, in playback order. */
	val finalized: List<SessionSnapshot> get() = trace.finalized

	init {
		reset()
		live += this
		installEngine()
		// The probe's latch corroboration and the engine's finalized
		// corroboration read one set of facts, exactly as `knownVideoFor` wires
		// them in `RustedWaxListenerService`. Two caches here would let a replay
		// agree with itself about a video the two halves disagreed about.
		trace.knownVideo = { videoId ->
			env.facts.get(videoId)?.let {
				SessionProbe.KnownVideo(
					title = it.title,
					channel = it.author,
					lengthSeconds = it.lengthSeconds,
				)
			}
		}
	}

	private fun installEngine() {
		// Native traces are discarded at the epoch check unless the surface is
		// opted in, which on a device is a persisted setting. Enabling every
		// surface here means a scenario states its own opt-out explicitly rather
		// than inheriting one.
		NativeSourceSwitches.configureForReplay(
			NativeSourceSwitches.Config(
				youTubeScrobbling = true,
				youtubeEnabled = true,
				youtubeMusicEnabled = true,
			),
		)
		FinalizationRuntime.installPortsForReplay(
			env.ports(identityResolverMode),
			CoroutineScope(dispatcher),
		)
		FinalizationRuntime.finalizationObserver = FinalizationObserver { session, outcome ->
			outcomes += RecordedOutcome(session, outcome)
		}
	}

	/** One finalized listen and the single terminal outcome the engine filed. */
	data class RecordedOutcome(
		val session: SessionSnapshot,
		val outcome: FinalizationOutcome,
	)

	val outcomes = mutableListOf<RecordedOutcome>()

	/**
	 * How many times this harness handed a finalized snapshot to an initialized
	 * engine.
	 *
	 * Not the same as `finalized.size`: [refinalizeLastListen] presents an
	 * already-frozen listen a second time, and that second presentation is
	 * exactly the one the duplicate gate is about. Counting presentations rather
	 * than freezes is what lets the exactly-one-outcome rule cover it.
	 */
	var presentedToEngine = 0
		private set

	/**
	 * The exactly-one-terminal-outcome gate, as an assertion any scenario can make.
	 *
	 * Counts finalized snapshots this run handed to an initialized engine and
	 * requires one outcome each. A finalization that threw before filing shows up
	 * here as a missing outcome rather than as a quietly shorter list, which is
	 * the failure this gate exists to catch.
	 */
	fun assertOneOutcomePerFinalization(expected: Int = presentedToEngine) {
		check(outcomes.size == expected) {
			"expected exactly one terminal outcome for each of $expected finalized " +
				"presentation(s), got ${outcomes.size}: " +
				outcomes.joinToString { it.outcome::class.simpleName.orEmpty() }
		}
	}

	val eligiblePayloads: List<List<com.rustedwax.hive.HiveScrobblePayload>>
		get() = outcomes.mapNotNull { (it.outcome as? FinalizationOutcome.Eligible)?.payloads }

	/** Short class names of the outcomes, for readable scenario assertions. */
	val outcomeKinds: List<String>
		get() = outcomes.map { it.outcome::class.simpleName.orEmpty() }

	/** Run the trace. Returns this harness so a scenario reads as one statement. */
	fun feed(vararg events: PlaybackEvent): ReplayHarness = feed(events.toList())

	/**
	 * The transport object this run hands to the engine.
	 *
	 * The decomposition round-trip is applied *here*, at the boundary, rather
	 * than inside `PlaybackTrace`: what is under test is the adapter, so the
	 * snapshot the reference run uses and the snapshot the domain run decomposes
	 * must be produced by identical code up to this line.
	 */
	private fun present(snapshot: SessionSnapshot): SessionSnapshot = when (presentation) {
		Presentation.REFERENCE -> snapshot
		Presentation.DOMAIN -> FinalizedTrack.from(snapshot).toSessionSnapshot()
	}

	/**
	 * Run this harness's finalizations through the shadow boundary instead.
	 *
	 * Set for the shadow half of a parity comparison. Nothing else about the run
	 * changes: the same trace, the same reducer, the same engine, the same
	 * decisions — only whether the decisions are allowed to become effects.
	 */
	var shadow: Boolean = false

	/**
	 * Collect frozen listens rather than presenting them to the engine.
	 *
	 * Null in every ordinary scenario, which keeps the harness's usual job — feed
	 * events, watch the engine — exactly as it was.
	 */
	var captureFinalized: ((SessionSnapshot) -> Unit)? = null

	fun feed(events: List<PlaybackEvent>): ReplayHarness {
		for (event in events) {
			trace.apply(event).forEach {
				val capture = captureFinalized
				if (capture != null) {
					// The snapshot is collected instead of presented. Used by the
					// Phase 0/1 end-to-end parity comparison, which runs the engine
					// itself so that both sides reach one engine through one route —
					// letting the harness present them too would run the new side's
					// listens through the engine twice and its dedup ledger would be
					// the only thing to notice.
					capture(present(it))
					return@forEach
				}
				presentedToEngine++
				if (shadow) {
					FinalizationRuntime.executeShadow(present(it))
				} else {
					FinalizationRuntime.executeAutomatic(present(it))
				}
			}
			if (event == PlaybackEvent.ProcessRestarted) {
				// A process death loses every singleton and run-local sequence, while
				// the scripted disk ports (ledger, queue, settings and account) remain.
				// Reinstalling the same port objects models exactly that boundary.
				FinalizationRuntime.resetForReplay()
				installEngine()
			}
		}
		return this
	}

	/**
	 * Hand the last frozen listen to finalization a second time.
	 *
	 * This is what a double finalize actually is. It is not "play the same video
	 * again" — that is a new listen with a new frozen start and legitimately
	 * earns its own transaction. It is the *same immutable snapshot* arriving
	 * twice, which is what a `MediaController` teardown racing a rebuild
	 * produces, and the ledger is the only thing between it and a duplicate on a
	 * chain nothing can edit.
	 */
	fun refinalizeLastListen(): ReplayHarness {
		val last = finalized.lastOrNull()
			?: error("nothing has been finalized yet, so there is nothing to re-finalize")
		presentedToEngine++
		FinalizationRuntime.executeAutomatic(present(last))
		return this
	}

	/** Present the last frozen listen through the production Now-card entry. */
	fun finalizeLastManually(
		onDone: (String, Boolean) -> Unit = { _, _ -> },
	): ReplayHarness {
		val last = finalized.lastOrNull()
			?: error("nothing has been finalized yet, so there is nothing to finalize manually")
		presentedToEngine++
		FinalizationRuntime.executeManual(present(last), onDone)
		return this
	}

	/**
	 * Hand a snapshot this trace did not freeze to the engine.
	 *
	 * Counted like any other presentation, so the exactly-one-outcome gate still
	 * covers it. It exists for one job: the parity gate's negative control, which
	 * has to feed a *deliberately wrong* transport object and require the
	 * comparison to notice. Building that object outside the trace is the point —
	 * a corruption the trace could produce would be a defect rather than a
	 * control.
	 */
	fun finalizeDirectly(snapshot: SessionSnapshot): ReplayHarness {
		presentedToEngine++
		FinalizationRuntime.executeAutomatic(present(snapshot))
		return this
	}

	// ---- typed outcomes ------------------------------------------------------

	/** What reached the broadcaster seam, parsed back out of the payload JSON. */
	data class BroadcastPayload(
		val kind: String,
		val title: String,
		val artist: String?,
		val album: String?,
		val duration: String?,
		val percentPlayed: Int?,
		val url: String?,
		val timestamp: String?,
		val app: String?,
	) {
		/** The id in the canonical hyperlink used by identity-parity gates. */
		val videoId: String? get() = url?.substringAfter("watch?v=", "")?.takeIf { it.length == 11 }
	}

	val broadcasts: List<BroadcastPayload>
		get() = env.broadcaster.sent.map { sent ->
			val json = JSONObject(sent.json)
			BroadcastPayload(
				kind = json.optString("kind"),
				title = json.optString("title"),
				artist = json.optStringOrNull("artist"),
				album = json.optStringOrNull("album"),
				duration = json.optStringOrNull("duration"),
				percentPlayed = if (json.has("percent_played")) json.getInt("percent_played") else null,
				url = json.optStringOrNull("url"),
				timestamp = json.optStringOrNull("timestamp"),
				app = json.optStringOrNull("app"),
			)
		}

	/** Transaction ids the recording broadcaster handed back, in order. */
	val transactionIds: List<String>
		get() = FinalizationRuntime.recent.value.reversed().mapNotNull { it.txId }

	/** Ids the ledger refused a second time. Empty is the healthy answer. */
	val duplicateAttempts: List<String> get() = env.claims.duplicateAttempts

	/** Which resolver routes ran, in the order the engine asked them. */
	val identityRoutes: List<ReplayIdentitySource.Route> get() = env.identity.calls

	/**
	 * Why a finalized track did not become an entry, as a typed value.
	 *
	 * The engine composes its reasons as prose because the reason is a product
	 * surface — the Not-logged tab shows it to a person. Tests assert on
	 * [RefusalKind] instead, so a reworded explanation changes exactly one
	 * mapping in [classify] rather than every scenario that touches it. The raw
	 * text is kept alongside for the cases where the wording *is* the point.
	 */
	enum class RefusalKind {
		NOT_YOUTUBE,
		SHORTS_DISABLED,
		NO_DURATION,
		BELOW_THRESHOLD,
		TOO_SHORT,
		EXPLICIT_AD,
		UNLISTED_SHORT,

		/**
		 * A proven `/shorts/` path whose video could not be shown to be a real,
		 * publicly listed one. Distinct from [TOO_SHORT]: nothing about its
		 * length was the problem, and it is refused at any length.
		 */
		UNPROVEN_SHORT,
		PROGRESS_SURFACE_LOST,
		NO_VERIFIED_VIDEO_ID,
		IDENTITY_CONTRADICTION,
		MUTED,
		ALREADY_SCROBBLED,
		PAYLOAD_NOT_BUILDABLE,
		OTHER,
		;
	}

	data class Refusal(
		val kind: RefusalKind,
		val reason: String,
		val title: String,
		val videoId: String?,
		val playedSeconds: Long,
		val durationSeconds: Long?,
	)

	/** Refusals in playback order — the state flow prepends, so this reverses it. */
	val refusals: List<Refusal>
		get() = FinalizationRuntime.skipped.value.reversed().map {
			Refusal(
				kind = classify(it.reason),
				reason = it.reason,
				title = it.title,
				videoId = it.videoId,
				playedSeconds = it.playedSeconds,
				durationSeconds = it.durationSeconds,
			)
		}

	val refusalKinds: List<RefusalKind> get() = refusals.map { it.kind }

	/**
	 * Not-logged rows for refusals that never proved a video id.
	 *
	 * These used to be dropped between the event log and every user-facing
	 * surface: the row required a canonical hyperlink, and an id is precisely
	 * what a refused-for-identity listen does not have — offline, where no id
	 * route can run at all, it is the normal outcome. The row is now filed
	 * without a link, so a scenario that once asserted "and the user is shown
	 * nothing" asserts the shape of what they are shown instead. Nothing here
	 * relaxes the broadcast rule: an unlinked refusal is still never published.
	 */
	val unlinkedRefusalKinds: List<RefusalKind>
		get() = refusals.filter { it.videoId == null }.map { it.kind }

	/** Refusals as decisions, including ones deliberately withheld from Not logged. */
	val terminalRefusalReasons: List<String>
		get() = outcomes.mapNotNull { (it.outcome as? FinalizationOutcome.Refused)?.reason }

	val terminalRefusalKinds: List<RefusalKind>
		get() = terminalRefusalReasons.map(::classify)

	/** The "address bar has gone quiet" counter, which is a browser-only signal. */
	val consecutiveTracksWithoutVideoId: Int get() = FinalizationRuntime.tracksWithoutVideoId.value

	val queuedForRetry: Int get() = env.retryQueue.size()

	fun reset() {
		FinalizationRuntime.resetForReplay()
		UrlEvidence.clearAll()
		NotificationHints.clearAll()
		MediaSessionAdEvidence.clearAll()
		MediaSessionAccessibilityEvidence.clearAll()
		VerifiedIdentityCandidateCache.clearAll()
	}

	companion object {

		/**
		 * Every harness built in the current test.
		 *
		 * `ReplayScenarioTest` holds all of them to the exactly-one-outcome rule
		 * on the way out, so the gate covers every scenario in the corpus rather
		 * than the handful that remember to ask for it.
		 */
		internal val live = mutableListOf<ReplayHarness>()

		/**
		 * Prose → [RefusalKind]. Ordered most specific first.
		 *
		 * Each pattern is anchored on a phrase the engine or `ScrobbleRules`
		 * builds deliberately rather than on incidental punctuation, and
		 * [RefusalKind.OTHER] is a real answer rather than a default — a scenario
		 * asserting `OTHER` is saying "this refusal is not one of the known
		 * shapes", which is a finding, not a fallthrough.
		 */
		val CLASSIFIERS: List<Pair<Regex, RefusalKind>> = listOf(
			Regex("source not proven YouTube") to RefusalKind.NOT_YOUTUBE,
			Regex("Shorts are turned off") to RefusalKind.SHORTS_DISABLED,
			Regex("marked this track as an ad") to RefusalKind.EXPLICIT_AD,
			Regex("the video is unlisted") to RefusalKind.UNLISTED_SHORT,
			Regex("a short, but (its watch page didn't resolve|the page never said)") to
				RefusalKind.UNPROVEN_SHORT,
			Regex("progress surface went away|played on with no progress surface") to
				RefusalKind.PROGRESS_SURFACE_LOST,
			Regex("could not be verified against the finalized snapshot") to
				RefusalKind.IDENTITY_CONTRADICTION,
			Regex("video id could not be verified") to RefusalKind.NO_VERIFIED_VIDEO_ID,
			Regex("muted video") to RefusalKind.MUTED,
			Regex("already scrobbled this listen") to RefusalKind.ALREADY_SCROBBLED,
			Regex("payload not buildable|hyperlink invariant failed") to
				RefusalKind.PAYLOAD_NOT_BUILDABLE,
			Regex("under the \\d+s (hard floor|minimum)") to RefusalKind.TOO_SHORT,
			Regex("below \\d+% threshold") to RefusalKind.BELOW_THRESHOLD,
			Regex("no duration") to RefusalKind.NO_DURATION,
		)

		fun classify(reason: String): RefusalKind =
			CLASSIFIERS.firstOrNull { it.first.containsMatchIn(reason) }?.second
				?: RefusalKind.OTHER

		fun JSONObject.optStringOrNull(key: String): String? =
			if (has(key) && !isNull(key)) getString(key) else null
	}
}
