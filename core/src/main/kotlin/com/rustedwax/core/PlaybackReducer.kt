package com.rustedwax.core

class PlaybackReducer(
	private val source: PlaybackSourceCapabilities,
	/** The run owner shared with this source's adapter; reducer state remains pure. */
	val evidenceRun: EvidenceRun? = null,
	/** The independently serialized source session this reducer belongs to. */
	val evidenceSourceSession: SourceSessionId? = null,
) {

	/**
	 * The state after one input, and the ordered work the caller owes.
	 *
	 * Two lists rather than one, because a track change genuinely has two halves
	 * and collapsing them would be a silent behaviour change. `finalizeCurrent`
	 * has to read the listen that is *ending* — its played time, its frozen
	 * start, its identity — and it runs before anything about the next listen
	 * exists. Everything after that point has to read the listen that is
	 * *starting*.
	 *
	 * So the caller's contract is exactly three steps, in order:
	 *
	 * 1. perform [before], while the outgoing state and the outgoing metadata
	 *    bundle are still installed;
	 * 2. install [state];
	 * 3. perform [effects].
	 */
	data class Transition(
		val state: ListenState,
		val before: List<PlaybackEffect> = emptyList(),
		val effects: List<PlaybackEffect> = emptyList(),
	)

	fun reduce(state: ListenState, input: PlaybackInput): Transition =
		dispatch(state, input).let { transition ->
			// "Has this transport ever named anything" is exactly "has a bundle
			// been installed", so it is derived from the effect rather than
			// maintained alongside it. Two facts that must agree and are written in
			// different places eventually stop agreeing.
			if (PlaybackEffect.InstallMetadata in transition.effects) {
				transition.copy(
					state = transition.state.copy(
						everPublishedMetadata = true,
						metadataAuthoritativeForCurrentPlayback =
							(input as? PlaybackInput.MetadataPublished)?.identity?.isUsable == true ||
								transition.state.metadataAuthoritativeForCurrentPlayback,
						metadataObservedSincePlaybackBoundary =
							(input as? PlaybackInput.MetadataPublished)?.identity?.isUsable == true ||
								transition.state.metadataObservedSincePlaybackBoundary,
						metadataInvalidatedAtPlaybackBoundary =
							if ((input as? PlaybackInput.MetadataPublished)?.identity?.isUsable == true) {
								false
							} else {
								transition.state.metadataInvalidatedAtPlaybackBoundary
							},
					),
				)
			} else {
				transition
			}
		}

	private fun dispatch(state: ListenState, input: PlaybackInput): Transition = when (input) {
		is PlaybackInput.MetadataPublished -> onMetadata(state, input)
		is PlaybackInput.TransportChanged -> onTransport(state, input)
		is PlaybackInput.PositionSeen -> Transition(
			state.notePosition(input.positionMs, input.establishFirst),
		)
		is PlaybackInput.SessionDestroyed -> onSessionDestroyed(state, input)
		is PlaybackInput.Disposed -> onDisposed(state, input)
		is PlaybackInput.FinalizeRequested -> onFinalizeRequested(state, input)
		is PlaybackInput.TrackFrozen -> Transition(
			state.copy(finalized = true, playingSinceElapsedMs = null),
		)
		is PlaybackInput.FreshPlaybackAfterRestartTombstone ->
			onFreshPlaybackAfterRestartTombstone(state, input)
		is PlaybackInput.ProgressCarried -> onProgressCarried(state, input)
		is PlaybackInput.VerifiedLeadIn -> onVerifiedLeadIn(state, input)
		is PlaybackInput.ExactIdEstablished -> Transition(
			state.copy(trackIdentity = state.trackIdentity.copy(sourceItemId = input.sourceItemId)),
		)
		is PlaybackInput.ForegroundShortTookOver -> onForegroundShortTookOver(state, input)
		is PlaybackInput.ForegroundShortReleased -> onForegroundShortReleased(state, input)
		is PlaybackInput.PictureInPictureObserved -> onPictureInPicture(state, input)
		is PlaybackInput.ForegroundSurface -> Transition(state)
		is PlaybackInput.IdleDeadlineReached -> onIdleDeadline(state, input)
	}

	private fun onIdleDeadline(
		state: ListenState,
		input: PlaybackInput.IdleDeadlineReached,
	): Transition {
		if (state.finalized || state.suppressedByForegroundShort || state.describingTabOnly) {
			return Transition(state)
		}
		if (state.transport != TransportState.PLAYING) return Transition(state)
		val played = state.playedMsAt(input.elapsedRealtimeMs)
		val duration = input.durationMs?.takeIf { it > 0 }
		if (duration == null) {
			// No length from anywhere — not the session, not the video's own cached
			// page. Nothing can say when this item should have ended, so the only
			// bound left is silence itself: a transport that has published nothing
			// at all for this long while claiming to play is not being watched.
			// Deliberately generous, because this branch cannot tell a long item
			// from an abandoned one and cutting a real listen short is the worse
			// mistake.
			return Transition(
				state,
				effects = listOf(
					PlaybackEffect.Note(
						"playback",
						"played ${played / 1000}s and published nothing for " +
							"${IDLE_FINALIZE_MAX_SILENCE_MS / 60_000}m, with no length known from " +
							"the session or the video's page; ending the listen rather than " +
							"counting wall clock indefinitely",
					),
					PlaybackEffect.Finalize(
						"playback went silent without the source saying so",
						persistRestartTombstone = true,
					),
				),
			)
		}
		// Only once the item's own length is genuinely used up. A deadline that
		// fired early — a rate change, a seek — must not end a listen still in
		// progress.
		if (played < duration) return Transition(state)
		return Transition(
			state,
			effects = listOf(
				PlaybackEffect.Note(
					"playback",
					"played ${played / 1000}s of a ${duration / 1000}s item with no further " +
						"transport update; treating the listen as ended rather than counting " +
						"wall clock nobody was watching",
				),
				PlaybackEffect.Finalize(
					"playback ran out without the source saying so",
					persistRestartTombstone = true,
				),
			),
		)
	}

	// ── 1. progress and speed accounting ───────────────────────────────────

	private fun ListenState.accumulate(elapsedRealtimeMs: Long): ListenState {
		if (suppressedByForegroundShort || describingTabOnly) {
			return copy(playingSinceElapsedMs = null)
		}
		val startedAt = playingSinceElapsedMs ?: return this
		return copy(
			playedMs = playedMs + ((elapsedRealtimeMs - startedAt) * speed).toLong(),
			fastestSpeedSeen = maxOf(fastestSpeedSeen, speed),
			playingSinceElapsedMs = null,
		)
	}

	/** Add source-proven playback measured before this metadata generation existed. */
	private fun onVerifiedLeadIn(
		state: ListenState,
		input: PlaybackInput.VerifiedLeadIn,
	): Transition {
		if (state.finalized || state.suppressedByForegroundShort || state.describingTabOnly ||
			state.durationReplacementMs != null || state.durationReplacementReturnPending ||
			input.playedMs <= 0
		) return Transition(state)
		val banked = state.accumulate(input.elapsedRealtimeMs)
		return Transition(
			banked.copy(
				playedMs = banked.playedMs + input.playedMs,
				startedAtEpochSec = minOf(banked.startedAtEpochSec, input.startedAtEpochSec),
				playingSinceElapsedMs = input.elapsedRealtimeMs
					.takeIf { banked.transport == TransportState.PLAYING },
			),
		)
	}

	/** The first position seen for a track, whatever announced it. */
	private fun ListenState.notePosition(positionMs: Long?, establishFirst: Boolean): ListenState {
		val observed = positionMs?.takeIf { it >= 0 } ?: return this
		// Retained as the last position either way: it is a true reading of the
		// transport. It just is not this listen's starting point.
		if (leadInSkipsNextPosition) {
			return copy(leadInSkipsNextPosition = false, lastObservedPositionMs = observed)
		}
		return copy(
			firstSeenPositionMs = firstSeenPositionMs ?: observed.takeIf { establishFirst },
			lastObservedPositionMs = observed,
		)
	}

	/**
	 * Record a strict end-to-start position reset.
	 *
	 * transport session exposes no "automatic loop" bit. The position boundary is the
	 * literal evidence available, and [positionWrapped] deliberately requires
	 * both ends of the item so ordinary backward seeking does not qualify.
	 */
	private fun ListenState.notePositionWrap(
		previousPositionMs: Long?,
		newPositionMs: Long?,
		durationMs: Long?,
		acrossSessionRestart: Boolean,
	): Pair<ListenState, List<PlaybackEffect>> {
		if (loopDetected || !positionWrapped(previousPositionMs, newPositionMs, durationMs)) {
			return this to emptyList()
		}
		return copy(loopDetected = true) to listOf(
			PlaybackEffect.Note(
				"playback",
				"playback position wrapped from end to start" +
					if (acrossSessionRestart) {
						" across a session restart — loop detected"
					} else {
						" — loop detected"
					},
			),
		)
	}

	// ── 2. metadata refinement and track transitions ───────────────────────

	private fun onMetadata(
		state: ListenState,
		input: PlaybackInput.MetadataPublished,
	): Transition {
		val new = input.identity

		// A new PLAYING generation began while platform's getter still returned the
		// previous item's bundle. The first usable callback after that boundary is
		// the first presentation this generation is allowed to own. Discard the
		// unidentified prefix and begin measurement at the observation itself;
		// finalizing the prefix would manufacture a phantom track from no identity.
		if (state.metadataInvalidatedAtPlaybackBoundary &&
			!state.suppressedByForegroundShort && new.isUsable
		) {
			return Transition(
				state.startNewTrack(input).copy(
					trackIdentity = new,
					metadataAuthoritativeForCurrentPlayback = true,
					metadataObservedSincePlaybackBoundary = true,
					metadataInvalidatedAtPlaybackBoundary = false,
				),
				effects = listOf(
					PlaybackEffect.ClearTrackScopedEvidence,
					PlaybackEffect.RebindTrackInstanceEvidence,
					PlaybackEffect.InstallMetadata,
					PlaybackEffect.RestoreCarriedProgress,
					PlaybackEffect.LogMetadata("first metadata for the current playback generation"),
				),
			)
		}

		// The foreground Shorts route owns the player. The transport session is still
		// read so the diagnostics stay current, and measures nothing.
		if (state.suppressedByForegroundShort) {
			return Transition(
				state.copy(trackIdentity = new),
				effects = listOf(
					PlaybackEffect.InstallMetadata,
					PlaybackEffect.LogMetadata("changed while foreground Short proof owned playback"),
				),
			)
		}

		// The browser is describing its *tab* now, not a track — the document
		// title, with the origin where the channel was. See [BrowserTabMetadata]
		// for what that cost.
		if (input.namesTabOnly) return enterTabTitleOnly(state, input)

		if (state.describingTabOnly) {
			if (!new.isUsable) {
				return Transition(
					state,
					effects = listOf(
						PlaybackEffect.InstallMetadata,
						PlaybackEffect.LogMetadata("changed while the browser named only its tab"),
					),
				)
			}
			// The next track, from zero. Explicitly rather than through the
			// freshly-created-session branch below, because the address bar may
			// have named two videos during the gap and this track must not inherit
			// a latch from the first of them.
			return Transition(
				state.startNewTrack(input).copy(trackIdentity = new, describingTabOnly = false),
				effects = listOf(
					PlaybackEffect.ClearTrackScopedEvidence,
					PlaybackEffect.InstallMetadata,
					PlaybackEffect.RestoreCarriedProgress,
					PlaybackEffect.LogMetadata("the browser named a track again"),
				),
			)
		}

		if (!new.isUsable && new.durationMs == null && state.trackIdentity.isUsable) {
			// The bundle is deliberately *not* installed: it says nothing, and
			// installing it would erase the fields this track established.
			return Transition(
				state,
				before = listOf(
					PlaybackEffect.Note(
						"native-identity",
						"published empty metadata while \"${input.outgoingTitle}\" was playing; " +
							"waiting for the real values rather than ending it on a placeholder",
					),
				),
			)
		}

		// A pre-resolved id was injected into the established identity, but the
		// transport session itself never published one. Compare on what the session
		// actually said, or the injected id defeats the exact-ID-less test below.
		val presentation = if (!input.outgoingTransportHasExactId && input.hasPreResolvedNativeId) {
			state.trackIdentity.copy(sourceItemId = null)
		} else {
			state.trackIdentity
		}
		if (source.republishesShorterDurations &&
			(
				presentation.isExactIdlessMaterialDurationReplacement(new) ||
					(state.durationReplacementMs != null && presentation.sameTrackAs(new))
			)
		) {
			return onDurationReplacement(state, input, new)
		}

		// A session created holding nothing has not been playing a track that can
		// now "end". It was the app preparing a controller, and this is the real
		// metadata arriving. Announcing a track change here finalized a phantom
		// `<untitled> — played 0s of 0s` on every tab switch, which is noise at
		// best and, when it lands between a teardown and its carry, throws the
		// real track's progress away.
		val outgoingWasPlaceholder = !state.trackIdentity.isUsable &&
			state.trackIdentity.durationMs == null &&
			state.playedMsAt(input.elapsedRealtimeMs) == 0L
		if (outgoingWasPlaceholder && new.isUsable) {
			return Transition(
				state.withNewInstance(input).copy(trackIdentity = new),
				effects = listOf(
					PlaybackEffect.InstallMetadata,
					PlaybackEffect.RestoreCarriedProgress,
					PlaybackEffect.LogMetadata("first real metadata for a freshly created session"),
				),
			)
		}

		if (state.trackIdentity.sameTrackAs(new) || outgoingWasPlaceholder) {
			return Transition(
				state.copy(
					trackIdentity = state.trackIdentity.refinedWith(new),
					durationReplacementCandidatePositionMs = null,
				),
				effects = listOf(
					PlaybackEffect.InstallMetadata,
					PlaybackEffect.LogMetadata("changed"),
				),
			)
		}

		// A real track change. A metadata change outranks the continuation grace
		// period: the old track has now demonstrably ended.
		return Transition(
			state.startNewTrack(input).copy(trackIdentity = new),
			before = listOf(
				PlaybackEffect.CancelStoppedFinalizationGrace,
				PlaybackEffect.Note(
					"track",
					"track change after ${state.playedMsAt(input.elapsedRealtimeMs) / 1000}s played",
				),
				PlaybackEffect.CancelContinuation,
				PlaybackEffect.Finalize("track change"),
			),
			effects = listOf(
				PlaybackEffect.ClearTrackScopedEvidence,
				PlaybackEffect.RebindTrackInstanceEvidence,
				PlaybackEffect.InstallMetadata,
				// The real title often arrives here rather than at construction —
				// page-backed source publishes a placeholder first — so this is where a resumed
				// track usually gets its time back.
				PlaybackEffect.RestoreCarriedProgress,
				PlaybackEffect.LogMetadata("changed"),
			),
		)
	}

	/**
	 * Same title, a different length, and no exact id on either side.
	 *
	 * The source can publish a short transition surface under the established
	 * organic metadata, or rebase a long-form duration from total time to bounded
	 * time remaining. Neither shape proves an ad. The reducer therefore keeps the
	 * longest established duration for scoring, quarantines elapsed measurement
	 * on the short replacement, and resumes only from a proven returning position
	 * or a previously established rebased surface that reached its own boundary.
	 * A different title, contradictory artist, replay from zero, or arithmetic
	 * outside the bounded rebase window ends the old listen fail-closed.
	 */
	private fun onDurationReplacement(
		state: ListenState,
		input: PlaybackInput.MetadataPublished,
		new: TrackIdentity,
	): Transition {
		val priorDuration = maxOf(
			state.trackIdentity.durationMs ?: 0,
			state.longestDurationMs ?: 0,
		).takeIf { it > 0 }
		if (source.republishesAlternateMediaDurations) {
			val longest = maxOf(priorDuration ?: 0, new.durationMs ?: 0).takeIf { it > 0 }
			return Transition(
				state.copy(
					longestDurationMs = longest,
					// The current representation owns payload and resolver fields. Song
					// and Video are different exact catalog items, so only progress and
					// the logical listen token survive the presentation boundary.
					trackIdentity = new.copy(sourceItemId = null),
				),
				before = listOf(
					PlaybackEffect.CancelStoppedFinalizationGrace,
					PlaybackEffect.CancelContinuation,
					PlaybackEffect.InvalidateInFlightIdentityRequest,
					PlaybackEffect.ClearPreResolvedNativeIdentity,
					PlaybackEffect.Note(
						"native-identity",
						"reported an alternate media length " +
							"(${state.trackIdentity.durationMs?.div(1000)}s → " +
							"${new.durationMs?.div(1000)}s) for the same work; keeping one listen",
					),
				),
				effects = listOf(
					PlaybackEffect.InstallMetadata,
					PlaybackEffect.RequestCarryAuthority,
					PlaybackEffect.LogMetadata("alternate media presentation for the same work"),
				),
			)
		}
		val newDuration = new.durationMs ?: 0
		val activeOrganicDuration = state.organicPresentationDurationMs
			?: state.trackIdentity.durationMs
			?: priorDuration
		val returnedToOrganicDuration = state.durationReplacementMs != null &&
			activeOrganicDuration != null &&
			kotlin.math.abs(newDuration - activeOrganicDuration) <=
				TrackIdentity.DURATION_REFINEMENT_TOLERANCE_MS
		if (returnedToOrganicDuration) {
			val organicPosition = state.durationReplacementOrganicPositionMs
			val returnedPosition = state.lastObservedPositionMs
			val positionContinues = organicPosition != null && returnedPosition != null &&
				kotlin.math.abs(returnedPosition - organicPosition) <=
					DURATION_REPLACEMENT_RESUME_WINDOW_MS
			val rebasedSurfaceReturned = state.organicPresentationRebased &&
				state.durationReplacementEndedAtBoundary
			val positionConfirmed = positionContinues || rebasedSurfaceReturned
			val returnPending = state.transport != TransportState.PLAYING || !positionConfirmed
			val armGrace = returnPending && !state.durationReplacementReturnGraceScheduled &&
				source.usesStoppedReplacementGrace
			val resumed = state.copy(
				trackIdentity = state.trackIdentity.refinedWith(new),
				longestDurationMs = priorDuration,
				organicPresentationDurationMs = activeOrganicDuration,
				durationReplacementMs = null,
				durationReplacementOrganicPositionMs = organicPosition.takeIf { returnPending },
				durationReplacementReturnPending = returnPending,
				durationReplacementEndedAtBoundary =
					state.durationReplacementEndedAtBoundary && returnPending,
				durationReplacementReturnGraceScheduled =
					(state.durationReplacementReturnGraceScheduled || armGrace) && returnPending,
				durationReplacementCandidatePositionMs = null,
				playingSinceElapsedMs = input.elapsedRealtimeMs.takeIf {
					state.transport == TransportState.PLAYING && positionConfirmed
				},
			)
			return Transition(
				resumed,
				before = buildList {
					if (!returnPending) add(PlaybackEffect.CancelStoppedFinalizationGrace)
					add(PlaybackEffect.CancelContinuation)
					add(
						PlaybackEffect.Note(
							"native-identity",
							if (returnPending) {
								"the established ${newDuration / 1000}s presentation returned; " +
									"holding the organic clock for bounded position confirmation"
							} else {
								"the established ${newDuration / 1000}s presentation returned; " +
									"ending duration-replacement measurement quarantine without " +
									"crediting the replacement interval"
							},
						),
					)
				},
				effects = buildList {
					if (armGrace) add(PlaybackEffect.ScheduleStoppedFinalizationGrace)
					add(PlaybackEffect.InstallMetadata)
					add(PlaybackEffect.LogMetadata("established presentation returned after duration replacement"))
				},
			)
		}
		val replacementIsDownward = priorDuration != null &&
			newDuration < priorDuration
		if (replacementIsDownward) {
			val alreadyPlayed = state.playedMsAt(input.elapsedRealtimeMs)
			val rebaseTolerance = minOf(
				DURATION_REBASE_MAX_TOLERANCE_MS,
				maxOf(DURATION_REBASE_MIN_TOLERANCE_MS, priorDuration / 10),
			)
			val boundedRemainingDurationRebase = state.durationReplacementMs == null &&
				alreadyPlayed > 0 &&
				newDuration >= (priorDuration * DURATION_REBASE_MIN_FRACTION).toLong() &&
				kotlin.math.abs(newDuration + alreadyPlayed - priorDuration) <= rebaseTolerance
			if (boundedRemainingDurationRebase) {
				return Transition(
					state.copy(
						trackIdentity = state.trackIdentity.refinedWith(
							new.copy(durationMs = state.trackIdentity.durationMs ?: priorDuration),
						),
						longestDurationMs = priorDuration,
						organicPresentationDurationMs = newDuration,
						organicPresentationRebased = true,
						durationReplacementReturnGraceScheduled = false,
						durationReplacementCandidatePositionMs = null,
					),
					before = listOf(
						PlaybackEffect.CancelStoppedFinalizationGrace,
						PlaybackEffect.CancelContinuation,
						PlaybackEffect.Note(
							"native-identity",
							"same-title duration rebased to a bounded remaining-time surface " +
								"(${priorDuration / 1000}s total, ${newDuration / 1000}s surface, " +
								"${alreadyPlayed / 1000}s played); retaining one organic listen",
						),
					),
					effects = listOf(
						PlaybackEffect.InstallMetadata,
						PlaybackEffect.LogMetadata("bounded remaining-duration presentation"),
					),
				)
			}
		}

		val provisionalAnchorSuperseded = priorDuration != null &&
			newDuration > priorDuration &&
			// A pod replaces one interstitial with the next, so the anchor is
			// usually already quarantining a surface by the time the work arrives.
			// An open quarantine therefore does not disqualify the supersede; an
			// established presentation returning to itself is handled above,
			// before this ever runs. Only a return still awaiting its position is
			// left alone, because that decision is already in flight.
			!state.durationReplacementReturnPending &&

			priorDuration * ORGANIC_ANCHOR_SUPERSEDE_FACTOR <= newDuration
		if (provisionalAnchorSuperseded) {
			val provisionalPlayedMs = state.playedMsAt(input.elapsedRealtimeMs)
			return Transition(
				state.copy(
					trackIdentity = new.copy(
						artist = new.artist ?: state.trackIdentity.artist,
						album = new.album ?: state.trackIdentity.album,
						sourceItemId = new.sourceItemId ?: state.trackIdentity.sourceItemId,
						durationMs = newDuration,
					),
					// The discarded interval belonged to the surface being
					// replaced. Carrying it forward would credit the work with
					// time the viewer spent on something else — the same false
					// credit the watch-path duration floor exists to prevent.
					playedMs = 0,
					pipInferredMs = 0,
					pipInference = null,
					fastestSpeedSeen = state.speed,
					firstSeenPositionMs = null,
					leadInSkipsNextPosition = true,
					loopDetected = false,
					longestDurationMs = newDuration,
					organicPresentationDurationMs = newDuration,
					organicPresentationRebased = false,
					durationReplacementMs = null,
					durationReplacementOrganicPositionMs = null,
					durationReplacementEndedAtBoundary = false,
					durationReplacementReturnGraceScheduled = false,
					durationReplacementCandidatePositionMs = null,
					playingSinceElapsedMs = input.elapsedRealtimeMs
						.takeIf { state.transport == TransportState.PLAYING },
				),
				before = listOf(
					PlaybackEffect.CancelStoppedFinalizationGrace,
					PlaybackEffect.CancelContinuation,
					PlaybackEffect.Note(
						"native-identity",
						"a ${priorDuration / 1000}s presentation held this listen for " +
							"${provisionalPlayedMs / 1000}s before a ${newDuration / 1000}s " +
							"presentation replaced it under the same title; the shorter surface " +
							"never established an organic anchor, so its interval is discarded " +
							"and organic measurement starts here",
					),
				),
				effects = listOf(
					PlaybackEffect.InstallMetadata,
					PlaybackEffect.LogMetadata(
						"organic presentation established after a provisional surface",
					),
				),
			)
		}
		// The source has replaced the established presentation with a materially
		// different duration while retaining the same non-contradictory metadata.
		// Device evidence includes both directions: short transition surfaces and
		// a longer sponsored surface published under an established shorter organic
		// duration. Neither direction proves an ad and neither is labelled one. Both
		// prove that this interval cannot safely be credited, so bank the organic
		// clock and quarantine measurement until its duration and position surface
		// demonstrably return.
		val establishedDuration = priorDuration ?: state.trackIdentity.durationMs ?: newDuration
		val banked = state.accumulate(input.elapsedRealtimeMs)
		val organicIdentity = banked.trackIdentity.refinedWith(
			new.copy(
				durationMs = banked.trackIdentity.durationMs ?: establishedDuration,
				sourceItemId = banked.trackIdentity.sourceItemId,
			),
		)
		return Transition(
			banked.copy(
				trackIdentity = organicIdentity,
				longestDurationMs = priorDuration,
				durationReplacementMs = newDuration,
				durationReplacementOrganicPositionMs =
					banked.durationReplacementOrganicPositionMs
						?: banked.durationReplacementCandidatePositionMs
						?: banked.lastObservedPositionMs,
				durationReplacementReturnPending = false,
				durationReplacementEndedAtBoundary =
					state.durationReplacementEndedAtBoundary &&
					state.durationReplacementMs == newDuration,
				durationReplacementReturnGraceScheduled = false,
				durationReplacementCandidatePositionMs = null,
				playingSinceElapsedMs = null,
			),
			before = listOf(
				PlaybackEffect.CancelStoppedFinalizationGrace,
				PlaybackEffect.CancelContinuation,
				PlaybackEffect.Note(
					"native-identity",
					"reported a materially different length (${establishedDuration / 1000}s → " +
						"${newDuration / 1000}s) for non-contradictory same-title metadata after " +
						"${banked.playedMsAt(input.elapsedRealtimeMs) / 1000}s played; keeping one " +
						"organic listen while quarantining replacement measurement",
				),
			),
			effects = listOf(
				PlaybackEffect.InstallMetadata,
				PlaybackEffect.LogMetadata("materially different length reported for an unchanged title"),
			),
		)
	}

	private fun enterTabTitleOnly(
		state: ListenState,
		input: PlaybackInput.MetadataPublished,
	): Transition {
		if (state.describingTabOnly) {
			return Transition(
				state,
				effects = listOf(
					PlaybackEffect.InstallMetadata,
					PlaybackEffect.LogMetadata("the browser is still naming only its tab"),
				),
			)
		}
		val before = buildList {
			add(PlaybackEffect.CancelStoppedFinalizationGrace)
			add(PlaybackEffect.CancelContinuation)
			if (state.trackIdentity.isUsable) {
				add(
					PlaybackEffect.Note(
						"track",
						"replaced the track with its tab's own title after " +
							"${state.playedMsAt(input.elapsedRealtimeMs) / 1000}s played",
					),
				)
				add(PlaybackEffect.Finalize("the browser named its tab instead of a track"))
			}
		}
		return Transition(
			state.startNewTrack(input).copy(
				describingTabOnly = true,

				playingSinceElapsedMs = null,
				trackIdentity = TrackIdentity(null, null, null, null),
			),
			before = before,
			effects = listOf(
				PlaybackEffect.ClearTrackScopedEvidence,
				PlaybackEffect.InstallMetadata,
				PlaybackEffect.LogMetadata("the browser named its tab, not a track"),
			),
		)
	}

	// ── 3. session destruction, recreation and continuation ────────────────

	private fun onSessionDestroyed(
		state: ListenState,
		input: PlaybackInput.SessionDestroyed,
	): Transition {
		if (state.suppressedByForegroundShort) {
			return Transition(
				state.copy(finalized = true, playingSinceElapsedMs = null),
				effects = listOf(
					PlaybackEffect.Note(
						"native-shorts",
						"suppressed native transport session destroyed; no carry or finalization",
					),
				),
			)
		}
		// Chrome tears sessions down around ad breaks and playlist transitions.
		// Disappearance starts a continuation window; it is not itself a track
		// ending.
		return deferForContinuation(
			state,
			"session destroyed",
			input.elapsedRealtimeMs,
			input.continuationOpen,
		)
	}

	private fun onDisposed(state: ListenState, input: PlaybackInput.Disposed): Transition {
		val opening = listOf(
			PlaybackEffect.CancelStoppedFinalizationGrace,
			PlaybackEffect.InvalidateInFlightIdentityRequest,
		)
		return when {
			state.suppressedByForegroundShort -> Transition(
				state.copy(finalized = true, playingSinceElapsedMs = null),
				before = opening + PlaybackEffect.CancelContinuation,
			)

			!input.finalize -> Transition(state, before = opening + PlaybackEffect.CancelContinuation)

			input.allowContinuation ->
				deferForContinuation(
					state,
					"session ended",
					input.elapsedRealtimeMs,
					input.continuationOpen,
				).let { Transition(it.state, before = opening + it.before, effects = it.effects) }

			// System teardown cannot observe a replacement. Score the aggregate
			// now; user Stop passes finalize=false above.
			else -> Transition(
				state,
				before = opening + PlaybackEffect.CancelContinuation +
					PlaybackEffect.Finalize("probe ended"),
			)
		}
	}

	/**
	 * Hold this track for a replacement transport without scoring the fragment on
	 * its own.
	 *
	 * The caller owns the timer and the token; what is decided here is whether a
	 * continuation may be opened at all, and what happens when it may not.
	 */
	private fun deferForContinuation(
		state: ListenState,
		reason: String,
		elapsedRealtimeMs: Long,
		continuationAlreadyOpen: Boolean,
	): Transition {
		if (state.finalized || continuationAlreadyOpen || !state.everPublishedMetadata) {
			return Transition(state)
		}
		if (state.suppressedByForegroundShort) {
			return Transition(
				state.copy(finalized = true, playingSinceElapsedMs = null),
				before = listOf(
					PlaybackEffect.CancelStoppedFinalizationGrace,
					PlaybackEffect.Note("native-shorts", "[$reason] suppressed transport session dropped"),
				),
			)
		}
		val banked = state.accumulate(elapsedRealtimeMs)
		if (source.requiresExactIdToCarryProgress && !banked.trackIdentity.hasExactSourceItemId) {
			return Transition(
				banked,
				effects = listOf(
					PlaybackEffect.CancelStoppedFinalizationGrace,
					PlaybackEffect.Note(
						"native-carry",
						"[$reason] exact-id-less transport session continuation refused; " +
							"resolver/site metadata cannot carry progress across controllers",
					),
					PlaybackEffect.Finalize("$reason; exact-id-less native continuation refused"),
				),
			)
		}
		return Transition(
			banked,
			effects = listOf(
				PlaybackEffect.CancelStoppedFinalizationGrace,
				PlaybackEffect.OpenContinuation(reason),
			),
		)
	}

	private fun onProgressCarried(
		state: ListenState,
		input: PlaybackInput.ProgressCarried,
	): Transition {
		if (state.finalized) return Transition(state)
		val restored = state.copy(
			instanceToken = input.instanceToken ?: state.instanceToken,
			instanceEstablishedAtMillis = input.nowMillis,
			playedMs = state.playedMs + input.playedMs,
			startedAtEpochSec = input.startedAtEpochSec,
			fastestSpeedSeen = input.fastestSpeedSeen,
			loopDetected = input.loopDetected,
		)
		val (wrapped, wrapEffects) = restored.notePositionWrap(
			previousPositionMs = input.lastPositionMs,
			newPositionMs = input.currentPositionMs,
			durationMs = input.durationMs,
			acrossSessionRestart = true,
		)
		return Transition(wrapped, wrapEffects)
	}

	// ── 4. finalization decisions ──────────────────────────────────────────

	/**
	 * Whether this track may be handed to the engine, and what it looks like when
	 * it is.
	 *
	 * Guarded so the several paths that can end a track — metadata change, stop,
	 * session destroyed, disposal, several of which fire together — only report
	 * once. The suppressed case is a refusal rather than a no-op so the caller
	 * can say why nothing was scored.
	 */
	private fun onFinalizeRequested(
		state: ListenState,
		input: PlaybackInput.FinalizeRequested,
	): Transition {
		if (state.finalized || !state.everPublishedMetadata) return Transition(state)
		if (state.suppressedByForegroundShort) {
			return Transition(
				state.copy(finalized = true, playingSinceElapsedMs = null),
				before = listOf(
					PlaybackEffect.CancelStoppedFinalizationGrace,
					PlaybackEffect.CancelContinuation,
					PlaybackEffect.Note(
						"native-shorts",
						"[${input.reason}] stale transport session finalization suppressed",
					),
				),
			)
		}
		return Transition(
			state.accumulate(input.elapsedRealtimeMs).copy(finalized = true),
			before = listOf(PlaybackEffect.CancelStoppedFinalizationGrace),
			effects = listOf(
				PlaybackEffect.FreezeAndReport(
					input.reason,
					input.persistRestartTombstone,
				),
			),
		)
	}

	private fun onTransport(
		state: ListenState,
		input: PlaybackInput.TransportChanged,
	): Transition {
		if (state.durationReplacementMs != null) {
			return onDurationReplacementTransport(state, input)
		}
		if (state.durationReplacementReturnPending && input.transport == TransportState.PLAYING) {
			val organicPosition = state.durationReplacementOrganicPositionMs
			val resumedPosition = input.newPositionMs
			val positionContinues = organicPosition != null && resumedPosition != null &&
				kotlin.math.abs(resumedPosition - organicPosition) <=
					DURATION_REPLACEMENT_RESUME_WINDOW_MS
			val rebasedSurfaceReturned = state.organicPresentationRebased &&
				state.durationReplacementEndedAtBoundary
			if (!positionContinues && !rebasedSurfaceReturned) {
				val armGrace = !state.durationReplacementReturnGraceScheduled &&
					source.usesStoppedReplacementGrace
				return Transition(
					state.copy(
						transport = TransportState.PLAYING,
						speed = input.speed,
						playingSinceElapsedMs = null,
						lastObservedPositionMs = resumedPosition,
						durationReplacementReturnGraceScheduled =
							state.durationReplacementReturnGraceScheduled || armGrace,
					),
					before = listOf(
						PlaybackEffect.CancelContinuation,
					),
					effects = buildList {
						if (armGrace) add(PlaybackEffect.ScheduleStoppedFinalizationGrace)
						add(
							PlaybackEffect.Note(
								"native-identity",
								"PLAYING returned before its organic position; retaining bounded " +
									"fail-closed quarantine",
							),
						)
						add(PlaybackEffect.LogTransportState("awaiting position after duration replacement"))
					},
				)
			}
			return Transition(
				state.copy(
					durationReplacementReturnPending = false,
					durationReplacementOrganicPositionMs = null,
					durationReplacementEndedAtBoundary = false,
					durationReplacementReturnGraceScheduled = false,
					transport = TransportState.PLAYING,
					speed = input.speed,
					playingSinceElapsedMs = input.elapsedRealtimeMs,
					lastObservedPositionMs = input.newPositionMs,
				),
				before = listOf(
					PlaybackEffect.CancelStoppedFinalizationGrace,
					PlaybackEffect.CancelContinuation,
				),
				effects = listOf(
					PlaybackEffect.Note(
						"native-identity",
						"PLAYING resumed the established presentation after a duration replacement; " +
							"continuing the same organic listen",
					),
					PlaybackEffect.RequestCarryAuthority,
					PlaybackEffect.LogTransportState("changed after duration replacement returned"),
				),
			)
		}
		val beginsAfterStoppedBoundary = state.transport == TransportState.STOPPED &&
			input.transport == TransportState.PLAYING
		if (beginsAfterStoppedBoundary) {
			val presentationIsCurrent = state.metadataObservedSincePlaybackBoundary
			val before = buildList {
				add(PlaybackEffect.CancelStoppedFinalizationGrace)
				add(PlaybackEffect.CancelContinuation)
				if (!state.finalized && state.playedMsAt(input.elapsedRealtimeMs) > 0) {
					add(PlaybackEffect.Finalize("a later playback generation began"))
				}
			}
			val next = state.startNewTrack(
				nowMillis = input.nowMillis,
				elapsedRealtimeMs = input.elapsedRealtimeMs,
				nextInstanceToken = input.nextInstanceToken,
			).copy(
				trackIdentity = if (presentationIsCurrent) {
					state.trackIdentity
				} else {
					TrackIdentity(null, null, null, null)
				},
				transport = input.transport,
				speed = input.speed,
				playingSinceElapsedMs = input.elapsedRealtimeMs,
				metadataAuthoritativeForCurrentPlayback = presentationIsCurrent,
				metadataObservedSincePlaybackBoundary = false,
				metadataInvalidatedAtPlaybackBoundary = !presentationIsCurrent,
			)
			return Transition(
				next,
				before = before,
				effects = buildList {
					add(PlaybackEffect.ClearTrackScopedEvidence)
					add(PlaybackEffect.RebindTrackInstanceEvidence)
					add(
						PlaybackEffect.Note(
							"metadata",
							if (presentationIsCurrent) {
								"PLAYING began after STOPPED with metadata published after the boundary"
							} else {
								"PLAYING began after STOPPED without a metadata publication; " +
									"the retained bundle is not authoritative for this playback"
							},
						),
					)
					if (presentationIsCurrent) add(PlaybackEffect.RequestCarryAuthority)
					add(PlaybackEffect.LogTransportState("changed"))
				},
			)
		}
		// page-backed source may keep publishing PLAYING for a controller whose silent deadline
		// already ended the listen. Keep the diagnostic transport current, but never
		// restart the spent clock, detect a new loop, or request carry authority. A
		// genuine successor is established by new metadata below, not by a stale
		// transport callback.
		if (state.finalized) {
			return Transition(
				state.copy(
					transport = input.transport,
					speed = input.speed,
					playingSinceElapsedMs = null,
				),
				effects = listOf(PlaybackEffect.LogTransportState("changed after finalization")),
			)
		}
		if (state.suppressedByForegroundShort) {
			return Transition(
				state.copy(
					transport = input.transport,
					speed = input.speed,
					playingSinceElapsedMs = null,
				),
				effects = listOf(
					PlaybackEffect.LogTransportState(
						"changed while foreground Short proof owned playback",
					),
				),
			)
		}
		val wasPlaying = state.transport == TransportState.PLAYING
		val replacementCandidatePosition = input.previousPositionMs?.takeIf { previous ->
			val current = input.newPositionMs ?: return@takeIf false
			previous - current > DURATION_REPLACEMENT_RESUME_WINDOW_MS
		}
		val banked = state.accumulate(input.elapsedRealtimeMs)
			.copy(
				transport = input.transport,
				speed = input.speed,
				// A transport can reset the position one callback before it
				// republishes a replacement duration. Keep only that immediately
				// preceding backward discontinuity; the next ordinary transport
				// callback clears it, and it is never authority by itself.
				durationReplacementCandidatePositionMs = replacementCandidatePosition,
			)
		val (wrapped, wrapEffects) = banked.notePositionWrap(
			previousPositionMs = input.previousPositionMs,
			newPositionMs = input.newPositionMs,
			durationMs = input.durationMs,
			acrossSessionRestart = false,
		)
		val effects = wrapEffects.toMutableList()

		var next = wrapped
		if (input.transport == TransportState.PLAYING) {
			if (next.playingSinceElapsedMs == null) {
				effects += PlaybackEffect.CancelStoppedFinalizationGrace
				next = next.copy(playingSinceElapsedMs = input.elapsedRealtimeMs)
			}
			effects += PlaybackEffect.RequestCarryAuthority
		}
		// STOPPED means the track is over; PAUSED does not — a paused track is
		// often resumed, and finalizing it would scrobble a half-listen and then
		// dedup-block the real one.
		if (wasPlaying && input.transport == TransportState.STOPPED) {
			next = next.copy(metadataObservedSincePlaybackBoundary = false)
			effects += PlaybackEffect.CancelContinuation
			// Resolver authority may authorize a controller handoff, but the
			// transport session is still exact-ID-less and retains replacement grace.
			if (source.usesStoppedReplacementGrace && !input.transportHasExactId) {
				effects += PlaybackEffect.ScheduleStoppedFinalizationGrace
			} else {
				effects += PlaybackEffect.Finalize("stopped")
			}
		}
		effects += PlaybackEffect.LogTransportState("changed")
		// The first-seen position is recorded by the log effect, not here: in the
		// outgoing implementation `noteFirstSeenPosition` ran inside
		// `logtransport state`, which is *after* a STOPPED transition has already
		// finalized. Recording it earlier would change the "first seen Ns in" note
		// on that one finalize line.
		return Transition(next, effects = effects)
	}

	/**
	 * Observe transport churn while a materially shorter same-title presentation
	 * owns the MediaSession. The replacement gets no clock and no terminal
	 * outcome. Long metadata can lead its matching organic position by one or more
	 * callbacks, so the clock remains quarantined under the existing bounded stop
	 * grace until that position arrives. A return is established only by that
	 * position or by a previously proven rebased surface reaching its boundary;
	 * no ad identity is inferred from any of those shapes.
	 */
	private fun onDurationReplacementTransport(
		state: ListenState,
		input: PlaybackInput.TransportChanged,
	): Transition {
		val replacementDuration = state.durationReplacementMs ?: return Transition(state)
		val organicPosition = state.durationReplacementOrganicPositionMs
		val resumedPosition = input.newPositionMs
		val replacementPosition = input.previousPositionMs
		val priorWasReplacementPosition = state.lastObservedPositionMs?.let {
			it <= replacementDuration + TrackIdentity.DURATION_REFINEMENT_TOLERANCE_MS
		} == true
		val replacementEndedAtBoundary = priorWasReplacementPosition &&
			replacementPosition != null && resumedPosition != null &&
			replacementPosition >= replacementDuration * LOOP_END_FRACTION &&
			(
				kotlin.math.abs(replacementPosition - resumedPosition) >=
					replacementDuration * LOOP_MIN_RESET_FRACTION ||
					resumedPosition > replacementDuration + TrackIdentity.DURATION_REFINEMENT_TOLERANCE_MS
			)
		val observedAtBoundary = listOfNotNull(input.newPositionMs, input.rawPositionMs).any {
			it >= replacementDuration * LOOP_END_FRACTION &&
				it <= replacementDuration + TrackIdentity.DURATION_REFINEMENT_TOLERANCE_MS
		}
		val endedAtBoundary = state.durationReplacementEndedAtBoundary || observedAtBoundary
		val positionContinues = organicPosition != null && resumedPosition != null &&
			kotlin.math.abs(resumedPosition - organicPosition) <=
				DURATION_REPLACEMENT_RESUME_WINDOW_MS
		val replacementEnded = input.transport == TransportState.PLAYING &&
			(positionContinues || state.organicPresentationRebased && endedAtBoundary) &&
			(replacementEndedAtBoundary || endedAtBoundary)
		if (replacementEnded) {
			return Transition(
				state.copy(
					durationReplacementMs = null,
					durationReplacementOrganicPositionMs = null,
					durationReplacementReturnPending = false,
					durationReplacementEndedAtBoundary = false,
					durationReplacementReturnGraceScheduled = false,
					transport = TransportState.PLAYING,
					speed = input.speed,
					playingSinceElapsedMs = input.elapsedRealtimeMs,
					lastObservedPositionMs = input.newPositionMs,
				),
				before = listOf(
					PlaybackEffect.CancelStoppedFinalizationGrace,
					PlaybackEffect.CancelContinuation,
				),
				effects = listOf(
					PlaybackEffect.Note(
						"native-identity",
						"the ${replacementDuration / 1000}s replacement position surface ended; " +
							"resuming organic measurement without crediting the quarantined interval",
					),
					PlaybackEffect.LogTransportState("changed after duration-replacement quarantine"),
				),
			)
		}

		val cancelGrace = input.transport == TransportState.PLAYING
		val scheduleGrace = state.transport == TransportState.PLAYING &&
			input.transport == TransportState.STOPPED &&
			source.usesStoppedReplacementGrace && !input.transportHasExactId
		val next = state.copy(
			transport = input.transport,
			speed = input.speed,
			playingSinceElapsedMs = null,
			lastObservedPositionMs = input.newPositionMs ?: state.lastObservedPositionMs,
			durationReplacementEndedAtBoundary = endedAtBoundary,
			durationReplacementReturnGraceScheduled = when {
				cancelGrace -> false
				scheduleGrace -> true
				else -> state.durationReplacementReturnGraceScheduled
			},
		)
		val effects = buildList {
			if (cancelGrace) {
				add(PlaybackEffect.CancelStoppedFinalizationGrace)
			}
			if (scheduleGrace) {
				add(PlaybackEffect.ScheduleStoppedFinalizationGrace)
			}
			add(PlaybackEffect.LogTransportState("changed during duration-replacement quarantine"))
		}
		return Transition(next, effects = effects)
	}

	/**
	 * A persisted tombstone suppressed this exact stale page-backed source transport, then
	 * the source published a strictly newer PLAYING update. That ordering is the
	 * authority for a genuine replay of the same presentation: metadata alone
	 * cannot distinguish it from the ended item, but a new transport sample can.
	 */
	private fun onFreshPlaybackAfterRestartTombstone(
		state: ListenState,
		input: PlaybackInput.FreshPlaybackAfterRestartTombstone,
	): Transition {
		if (!state.finalized) return Transition(state)
		return Transition(
			state.startNewTrack(
				input.nowMillis,
				input.elapsedRealtimeMs,
				input.nextInstanceToken,
			),
			effects = listOf(
				PlaybackEffect.ClearTrackScopedEvidence,
				PlaybackEffect.RebindTrackInstanceEvidence,
				PlaybackEffect.Note(
					"playback",
					"a transport update newer than the persisted finalization started a fresh listen",
				),
			),
		)
	}

	// ── foreground Shorts handover ─────────────────────────────────────────

	private fun onForegroundShortTookOver(
		state: ListenState,
		input: PlaybackInput.ForegroundShortTookOver,
	): Transition {
		if (state.suppressedByForegroundShort) return Transition(state)
		val banked = state.accumulate(input.elapsedRealtimeMs)
		val before = mutableListOf<PlaybackEffect>(
			PlaybackEffect.CancelStoppedFinalizationGrace,
			// A pending continuation cannot survive the hand-off: its expiry
			// callback would land on a suppressed state, where finalization is a
			// no-op. Whatever it was holding is decided here instead.
			PlaybackEffect.CancelContinuation,
		)
		if (banked.playedMs != 0L && banked.trackIdentity.isUsable) {
			if (input.shortDescribesSameItem) {
				before += PlaybackEffect.Note(
					"native-shorts",
					"transport session was already describing the Short the foreground route just " +
						"acquired; its ${banked.playedMs / 1000}s belongs to that route and is " +
						"not scored twice",
				)
			} else {
				before += PlaybackEffect.Finalize("foreground Short proof took over playback")
			}
		}
		return Transition(
			banked.copy(
				suppressedByForegroundShort = true,
				playedMs = 0,
				playingSinceElapsedMs = null,
				loopDetected = false,
				finalized = false,
				metadataObservedSincePlaybackBoundary = false,
			),
			before = before,
			effects = listOf(
				PlaybackEffect.Note(
					"native-shorts",
					"transport session hidden while complete foreground Shorts proof is active",
				),
			),
		)
	}

	/** Resume conservative transport session observation from a fresh zero baseline. */
	private fun onForegroundShortReleased(
		state: ListenState,
		input: PlaybackInput.ForegroundShortReleased,
	): Transition {
		if (!state.suppressedByForegroundShort) return Transition(state)
		val presentationIsCurrent = state.metadataObservedSincePlaybackBoundary
		return Transition(
			state.copy(
				suppressedByForegroundShort = false,
				trackIdentity = if (presentationIsCurrent) {
					input.identity
				} else {
					TrackIdentity(null, null, null, null)
				},
			)
				.startNewTrack(
					nowMillis = input.nowMillis,
					elapsedRealtimeMs = input.elapsedRealtimeMs,
					nextInstanceToken = input.nextInstanceToken,
				).copy(
					metadataAuthoritativeForCurrentPlayback = presentationIsCurrent,
					metadataObservedSincePlaybackBoundary = false,
					metadataInvalidatedAtPlaybackBoundary = !presentationIsCurrent,
				),
			effects = listOf(
				PlaybackEffect.ClearTrackScopedEvidence,
				PlaybackEffect.Note(
					"native-shorts",
					"foreground proof ended; transport session resumed at a fresh zero baseline",
				),
			),
		)
	}

	// ── 5. picture-in-picture measurement effects ──────────────────────────

	/**
	 * One tick of picture-in-picture evidence for a session that cannot be
	 * measured.
	 *
	 * Only ever credits when the session publishes no usable progress of its own:
	 * a regular video in PiP keeps reporting position and is measured normally,
	 * and must not be double-counted. That gate is the whole safety property, so
	 * it lives here rather than at the call site.
	 *
	 * The refusing branch still *records* the observation, because dropping the
	 * anchor is itself a decision: a session that publishes progress again for one
	 * tick and then stops must not have that gap credited when inference resumes.
	 * When the accumulator was mutable that write happened by side effect on the
	 * state this function was handed; it is now written into the state returned.
	 */
	private fun onPictureInPicture(
		state: ListenState,
		input: PlaybackInput.PictureInPictureObserved,
	): Transition {
		if (state.finalized || state.suppressedByForegroundShort ||
			state.durationReplacementMs != null || state.durationReplacementReturnPending ||
			!source.supportsPictureInPictureInference
		) {
			return Transition(state)
		}
		val duration = input.durationMs ?: return Transition(state)
		if (duration <= 0 || state.transport == TransportState.PLAYING) {
			val idle = state.pipInference?.observe(input.nowMillis, playing = false)?.next
				?: return Transition(state)
			return Transition(state.copy(pipInference = idle))
		}
		val running = state.pipInference
			?: PipPlaybackInference(durationMs = duration, measuredMs = state.playedMs)
		val stepped = running.observe(input.nowMillis, input.playing).next
		return Transition(state.copy(pipInference = stepped, pipInferredMs = stepped.credited))
	}

	// ── shared transitions ─────────────────────────────────────────────────

	private fun ListenState.startNewTrack(input: PlaybackInput.MetadataPublished): ListenState =
		startNewTrack(input.nowMillis, input.elapsedRealtimeMs, input.nextInstanceToken)

	/**
	 * Begin a new listen on the same transport.
	 *
	 * Everything per-track is cleared, including the duration cap: it belongs to
	 * the track that is ending, never to the next one. The evidence half of the
	 * reset is [PlaybackEffect.ClearTrackScopedEvidence], performed by the caller.
	 */
	private fun ListenState.startNewTrack(
		nowMillis: Long,
		elapsedRealtimeMs: Long,
		nextInstanceToken: Long,
	): ListenState = copy(
		describingTabOnly = false,
		playedMs = 0,
		longestDurationMs = null,
		durationReplacementMs = null,
		durationReplacementOrganicPositionMs = null,
		durationReplacementReturnPending = false,
		durationReplacementEndedAtBoundary = false,
		durationReplacementReturnGraceScheduled = false,
		durationReplacementCandidatePositionMs = null,
		organicPresentationDurationMs = null,
		organicPresentationRebased = false,
		lastObservedPositionMs = null,
		pipInference = null,
		pipInferredMs = 0,
		fastestSpeedSeen = 1.0,
		firstSeenPositionMs = null,
		loopDetected = false,
		playingSinceElapsedMs = elapsedRealtimeMs.takeIf { transport == TransportState.PLAYING },
		startedAtEpochSec = nowMillis / 1000,
		finalized = false,
		instanceToken = nextInstanceToken,
		instanceEstablishedAtMillis = nowMillis,
	)

	/** A new track instance on unchanged measurement — the placeholder handover. */
	private fun ListenState.withNewInstance(input: PlaybackInput.MetadataPublished): ListenState =
		copy(
			instanceToken = input.nextInstanceToken,
			instanceEstablishedAtMillis = input.nowMillis,
		)

	companion object {

		/**
		 * A rate this high is a data error, not a viewing.
		 *
		 * `transport state.getPlaybackSpeed()` is a float a session sets freely.
		 * Trusting it unbounded means one bad value can inflate a listen past any
		 * threshold, so it is clamped rather than believed.
		 */
		const val MAX_PLAYBACK_SPEED = 4.0

		/**
		 * How long past an item's own end a silent transport is given.
		 *
		 * Generous on purpose. A video that ends and auto-advances publishes new
		 * metadata within a second or two, and a video that ends and loops with a
		 * readable position publishes a wrap — both re-arm the deadline long before
		 * this elapses. What is left is the case where the source says nothing at
		 * all, and thirty seconds of slack costs a real listen nothing.
		 */
		const val IDLE_FINALIZE_GRACE_MS = 30_000L

		/**
		 * The bound on a transport that publishes no length and then goes quiet.
		 *
		 * This branch cannot distinguish a long item still playing from one that
		 * ended, so it is set well past any ordinary music video and accepts that a
		 * genuinely long silent listen is scored for what it had reached. The
		 * alternative is what the field actually did: accrue for one hour
		 * forty-seven minutes and then claim the item had been played twice.
		 */
		const val IDLE_FINALIZE_MAX_SILENCE_MS = 15 * 60_000L

		/** How close a returning position must be to the pre-replacement organic position. */
		const val DURATION_REPLACEMENT_RESUME_WINDOW_MS = 15_000L

		/** Bounded uncertainty allowed when a source rebases duration to time remaining. */
		const val DURATION_REBASE_MIN_FRACTION = 0.25
		const val DURATION_REBASE_MIN_TOLERANCE_MS = 15_000L
		const val DURATION_REBASE_MAX_TOLERANCE_MS = 180_000L

		const val ORGANIC_ANCHOR_SUPERSEDE_FACTOR = 10L

		/** Fractions of the item that make a position reset a wrap and not a seek. */
		const val LOOP_END_FRACTION = 0.8
		const val LOOP_START_FRACTION = 0.2
		const val LOOP_MIN_RESET_FRACTION = 0.5

		/**
		 * The scaling factor for a reported rate.
		 *
		 * Zero is "not moving", which the played-time clock already expresses by
		 * not running, so it reads as 1×. Negative, NaN and infinite are refused
		 * for the same reason: none of them describes content being consumed.
		 */
		fun speedFactor(reported: Float?): Double {
			val raw = reported?.toDouble() ?: return 1.0
			if (raw.isNaN() || raw.isInfinite() || raw <= 0.0) return 1.0
			return minOf(raw, MAX_PLAYBACK_SPEED)
		}

		/**
		 * A strict end-to-start position reset within one continuous viewing.
		 *
		 * Both ends of the item are required so that ordinary backward seeking —
		 * which produces a large negative delta but not from the end to the start
		 * — does not qualify.
		 */
		fun positionWrapped(
			previousPositionMs: Long?,
			newPositionMs: Long?,
			durationMs: Long?,
		): Boolean {
			val previous = previousPositionMs ?: return false
			val next = newPositionMs ?: return false
			val total = durationMs ?: return false
			if (previous < 0 || next < 0 || total <= 0) return false
			val nearEnd = previous.toDouble() >= total * LOOP_END_FRACTION
			val nearStart = next.toDouble() <= total * LOOP_START_FRACTION
			val largeReset = (previous - next).toDouble() >= total * LOOP_MIN_RESET_FRACTION
			return nearEnd && nearStart && largeReset
		}
	}
}

private fun Long?.orZero(): Long = this ?: 0

/** What a transport can be doing, with no platform constant in sight. */
enum class TransportState {
	PLAYING,
	PAUSED,
	STOPPED,
	OTHER,
}

/**
 * Everything one continuous listen has measured, plus where it is in its
 * lifecycle. A value: no callbacks, no platform, no evidence.
 */
data class ListenState(
	val trackIdentity: TrackIdentity,
	val instanceToken: Long,
	val instanceEstablishedAtMillis: Long,
	val startedAtEpochSec: Long,
	/** Content milliseconds banked so far. The running window is not included. */
	val playedMs: Long = 0,
	/**
	 * The elapsed-realtime reading when the played clock started running, or
	 * null when it is not running.
	 *
	 * Deliberately not a boolean plus a timestamp — one field cannot get out of
	 * step with itself — and deliberately not a `0` sentinel either: zero is a
	 * legitimate reading, and a clock that silently refused to run at one
	 * particular instant is the kind of defect that only ever shows up on someone
	 * else's device.
	 */
	val playingSinceElapsedMs: Long? = null,
	val transport: TransportState = TransportState.OTHER,
	val speed: Double = 1.0,
	/** Highest rate scored for this track, for the finalize line only. */
	val fastestSpeedSeen: Double = 1.0,

	val firstSeenPositionMs: Long? = null,
	/**
	 * The longest length this unchanged title has ever claimed.
	 *
	 * Keeping the longest is strictly safer than taking the newest: the case the
	 * shorter number would create is a song looking complete because an
	 * interstitial's length was written over it.
	 */
	val longestDurationMs: Long? = null,
	/** Materially shorter same-title presentation whose elapsed time is not organic progress. */
	val durationReplacementMs: Long? = null,
	/** Last organic position before the shorter presentation replaced the surface. */
	val durationReplacementOrganicPositionMs: Long? = null,
	/** The established duration returned while transport was not yet PLAYING. */
	val durationReplacementReturnPending: Boolean = false,
	/** The quarantined presentation reached its own end boundary. */
	val durationReplacementEndedAtBoundary: Boolean = false,
	/** A bounded fail-closed deadline is armed while a returning position is unresolved. */
	val durationReplacementReturnGraceScheduled: Boolean = false,
	/** Organic-side position immediately before a transport reset that may precede duration churn. */
	val durationReplacementCandidatePositionMs: Long? = null,
	/** Current organic duration surface when the source rebased from total to remaining time. */
	val organicPresentationDurationMs: Long? = null,
	/** True only after the bounded total-minus-played arithmetic established that rebase. */
	val organicPresentationRebased: Boolean = false,
	/** Last position published by this transport, retained across metadata callbacks. */
	val lastObservedPositionMs: Long? = null,

	val leadInSkipsNextPosition: Boolean = false,

	val loopDetected: Boolean = false,
	/** The browser is publishing its tab's own title instead of a track's. */
	val describingTabOnly: Boolean = false,
	/** The structurally proven foreground Shorts route owns this player. */
	val suppressedByForegroundShort: Boolean = false,
	/** One finalize per track, however many callbacks announce the end. */
	val finalized: Boolean = false,
	/**
	 * The running picture-in-picture accumulator.
	 *
	 * A value, like everything else here. It has to remember the previous
	 * observation — that is what enforces the caps — but remembering is done by
	 * being replaced rather than by being written to, so a state handed to
	 * [PlaybackReducer.reduce] is the same state afterwards.
	 */
	val pipInference: PipPlaybackInference? = null,
	/** The part of the measurement that was inferred rather than read. */
	val pipInferredMs: Long = 0,
	/** The transport has published a metadata bundle at least once. */
	val everPublishedMetadata: Boolean = false,
	/** The installed presentation was published for this playback generation. */
	val metadataAuthoritativeForCurrentPlayback: Boolean = true,
	/** A usable metadata callback landed after the most recent STOPPED/takeover boundary. */
	val metadataObservedSincePlaybackBoundary: Boolean = true,
	/** The current generation explicitly rejected a retained pre-boundary bundle. */
	val metadataInvalidatedAtPlaybackBoundary: Boolean = false,
) {
	/**
	 * Content consumed as of this instant, including the window still running.
	 *
	 * Zero while the foreground Shorts route owns the player or the browser is
	 * naming only its tab — in both states this transport is not measuring
	 * anything, and reporting a stale total would credit it to whatever claims
	 * the session next.
	 */
	fun playedMsAt(elapsedRealtimeMs: Long): Long =
		if (suppressedByForegroundShort || describingTabOnly) {
			0
		} else if (finalized || durationReplacementMs != null || durationReplacementReturnPending) {
			playedMs + pipInferredMs
		} else {
			playedMs + pipInferredMs +
				playingSinceElapsedMs?.let { ((elapsedRealtimeMs - it) * speed).toLong() }.orZero()
		}

	/**
	 * How long from now this listen should be given before it is treated as
	 * abandoned, or null when the question does not apply.
	 *
	 * The wall-clock time still needed to consume what is left of the item, plus
	 * a grace period. Re-derived after every observation, so any transport event
	 * — a pause, a seek, a rate change, the next track — pushes it out; only a
	 * source that says nothing at all lets it expire.
	 *
	 * Null when there is nothing to wait for: the listen is over, the transport
	 * is not playing, the foreground Shorts route owns the player, the browser is
	 * naming only its tab, or no length is known. A live stream has no end to run
	 * past and is never ended this way.
	 */
	fun idleFinalizeDelayMs(elapsedRealtimeMs: Long, durationMs: Long?): Long? {
		if (finalized || suppressedByForegroundShort || describingTabOnly) return null
		if (transport != TransportState.PLAYING) return null
		val total = durationMs?.takeIf { it > 0 }

			?: return PlaybackReducer.IDLE_FINALIZE_MAX_SILENCE_MS
		val remainingContent = (total - playedMsAt(elapsedRealtimeMs)).coerceAtLeast(0)
		val remainingWallClock = if (speed > 0) (remainingContent / speed).toLong() else remainingContent
		return remainingWallClock + PlaybackReducer.IDLE_FINALIZE_GRACE_MS
	}

	fun establishedDurationMs(publishedMs: Long?): Long? {
		val installedIsQuarantined = durationReplacementMs != null
		val accepted = if (installedIsQuarantined) null else publishedMs
		return accepted?.let { maxOf(it, longestDurationMs ?: 0) }
			?: longestDurationMs
			?: trackIdentity.durationMs?.takeIf { it > 0 }
	}
}

/** One thing that happened to the transport, in the order it happened. */
sealed interface PlaybackInput {
	/**
	 * A source-neutral observation from a foreground player surface.
	 *
	 * These inputs share the production playback vocabulary, but are reduced by
	 * [ForegroundShortTracker] because that lifecycle has no transport controller.
	 * If one reaches a controller reducer accidentally it is a safe no-op.
	 */
	sealed interface ForegroundSurface : PlaybackInput

	/** A foreground surface published identity and/or measurable progress. */
	data class ForegroundSurfaceObserved(
		val identity: TrackIdentity?,
		val positionMs: Long?,
		val durationMs: Long?,
		val nowMillis: Long,
		val sourceEpoch: Long,
		val playing: Boolean,
		/** Rate read from the foreground player's own visible speed chip. */
		val playbackRate: Double? = null,
		val explicitAdSignal: String? = null,
	) : ForegroundSurface

	/** Structural proof was absent for this observation. */
	data class ForegroundSurfaceUnavailable(
		val reason: String,
		val nowMillis: Long,
		val progressSurfaceLost: Boolean = false,
		val playing: Boolean = false,
		val playbackRate: Double? = null,
		val discard: Boolean = false,
		/** The display was not interactive when this observation was taken. */
		val displayOff: Boolean = false,
	) : ForegroundSurface

	/** The separately granted observer connected; no playback state changed. */
	data class ForegroundSurfaceConnected(val nowMillis: Long) : ForegroundSurface

	/** The session published a metadata bundle. */
	data class MetadataPublished(
		val identity: TrackIdentity,
		/** The bundle names the browser's own tab rather than a track. */
		val namesTabOnly: Boolean,
		/** The *outgoing* raw bundle carried an exact source item id. */
		val outgoingTransportHasExactId: Boolean,
		/** A pre-resolved id was injected into the established identity. */
		val hasPreResolvedNativeId: Boolean,
		/** Title of the outgoing bundle, for one diagnostic line. */
		val outgoingTitle: String?,
		val nowMillis: Long,
		val elapsedRealtimeMs: Long,
		val nextInstanceToken: Long,
	) : PlaybackInput

	/** The transport changed state, rate or position. */
	data class TransportChanged(
		val transport: TransportState,
		val speed: Double,
		/** Extrapolated position before this change, for wrap detection. */
		val previousPositionMs: Long?,
		/** Extrapolated position after it. */
		val newPositionMs: Long?,
		/** The raw published position, which is what a first sighting records. */
		val rawPositionMs: Long?,
		val durationMs: Long?,
		val transportHasExactId: Boolean,
		val elapsedRealtimeMs: Long,
		val nowMillis: Long = 0,
		val nextInstanceToken: Long = 0,
	) : PlaybackInput

	/** A position was published without a state change — the initial reading. */
	data class PositionSeen(
		val positionMs: Long?,
		/** False for a metadata-time checkpoint that must not change lead-in diagnostics. */
		val establishFirst: Boolean = true,
	) : PlaybackInput

	data class SessionDestroyed(
		val elapsedRealtimeMs: Long,
		/**
		 * A continuation is already parked for this transport.
		 *
		 * Asked of the caller rather than remembered here, because the caller owns
		 * the carry store and the timer that can end the wait at any moment. A
		 * copy of it in this state would be a second answer to a question that
		 * already has one, and the two would eventually disagree — the failure
		 * being a transport that could never park progress again.
		 */
		val continuationOpen: Boolean,
	) : PlaybackInput

	data class Disposed(
		val finalize: Boolean,
		val allowContinuation: Boolean,
		val elapsedRealtimeMs: Long,
		/** See [SessionDestroyed.continuationOpen]. */
		val continuationOpen: Boolean,
	) : PlaybackInput

	/** Something decided the track has ended. */
	data class FinalizeRequested(
		val reason: String,
		val elapsedRealtimeMs: Long,
		val persistRestartTombstone: Boolean = false,
	) : PlaybackInput

	/** The snapshot was taken and handed on; nothing further may score it. */
	data object TrackFrozen : PlaybackInput

	/** A newer transport sample proves a replay after restart tombstone suppression. */
	data class FreshPlaybackAfterRestartTombstone(
		val nowMillis: Long,
		val elapsedRealtimeMs: Long,
		val nextInstanceToken: Long,
	) : PlaybackInput

	/** A vanished session's progress was claimed by this transport. */
	data class ProgressCarried(
		val playedMs: Long,
		val startedAtEpochSec: Long,
		val fastestSpeedSeen: Double,
		val loopDetected: Boolean,
		val instanceToken: Long?,
		val lastPositionMs: Long?,
		val currentPositionMs: Long?,
		val durationMs: Long?,
		val nowMillis: Long,
	) : PlaybackInput

	/** Playback proven outside the transport before its current metadata appeared. */
	data class VerifiedLeadIn(
		val playedMs: Long,
		val startedAtEpochSec: Long,
		val elapsedRealtimeMs: Long,
	) : PlaybackInput

	/** A bounded lookup proved an exact id for a track that published none. */
	/**
	 * Evidence outside the transport proved which item is playing.
	 *
	 * [sourceItemId] is whatever stable identifier the *source* publishes — a
	 * source video id today, a another source track URI or a another source title id when those
	 * adapters arrive. The reducer does not parse it, validate its shape, or care
	 * where it came from; it stores it and uses its presence to decide whether
	 * progress may carry. Named for the concept rather than for the first source
	 * to use it, because the field used to carry a source-specific identifier and that is how a shared
	 * reducer quietly acquires a platform.
	 */
	data class ExactIdEstablished(val sourceItemId: String) : PlaybackInput

	data class ForegroundShortTookOver(
		/** The Short being acquired is the very item this session was describing. */
		val shortDescribesSameItem: Boolean,
		val elapsedRealtimeMs: Long,
	) : PlaybackInput

	data class ForegroundShortReleased(
		val identity: TrackIdentity,
		val nowMillis: Long,
		val elapsedRealtimeMs: Long,
		val nextInstanceToken: Long,
	) : PlaybackInput

	data class PictureInPictureObserved(
		val nowMillis: Long,
		val playing: Boolean,
		val durationMs: Long?,
	) : PlaybackInput

	/**
	 * The caller's idle timer fired. Whether it means anything is decided by the
	 * reducer, because only it knows how much of the item has actually been used.
	 */
	data class IdleDeadlineReached(
		val durationMs: Long?,
		val elapsedRealtimeMs: Long,
	) : PlaybackInput
}

/**
 * Something the caller must do after the state has been replaced.
 *
 * Ordered, and performed in order. The reducer decides *that* a track ends; the
 * caller owns the timers, the log, the evidence stores and the engine.
 */
sealed interface PlaybackEffect {

	/** End the track for this reason, through the caller's own guard. */
	data class Finalize(
		val reason: String,
		val persistRestartTombstone: Boolean = false,
	) : PlaybackEffect

	/** Build the snapshot and hand it to the engine. The track is already frozen. */
	data class FreezeAndReport(
		val reason: String,
		val persistRestartTombstone: Boolean = false,
	) : PlaybackEffect

	/**
	 * Install the incoming metadata bundle as the session's current one.
	 *
	 * An ordered effect rather than something the caller does around the
	 * reduction, because *when* it happens is load-bearing: a finalize that ran
	 * after it would describe the outgoing track using the incoming bundle, and
	 * the empty-metadata branch deliberately never installs at all.
	 */
	data object InstallMetadata : PlaybackEffect

	/** Clear the identity, latch, ad and resolver state a new track must not inherit. */
	data object ClearTrackScopedEvidence : PlaybackEffect

	/** Try to reclaim progress a vanished session left behind. */
	data object RestoreCarriedProgress : PlaybackEffect

	data object CancelContinuation : PlaybackEffect

	/** Hold this track for a replacement transport, on the caller's timer. */
	data class OpenContinuation(val reason: String) : PlaybackEffect

	data object CancelStoppedFinalizationGrace : PlaybackEffect
	data object ScheduleStoppedFinalizationGrace : PlaybackEffect
	data object InvalidateInFlightIdentityRequest : PlaybackEffect
	/** Alternate source presentation needs its own exact id without losing progress. */
	data object ClearPreResolvedNativeIdentity : PlaybackEffect

	/** Ask for an exact id for a stable track that publishes none. */
	data object RequestCarryAuthority : PlaybackEffect

	/** Re-bind the track-instance-scoped evidence stores to the new instance. */
	data object RebindTrackInstanceEvidence : PlaybackEffect

	/**
	 * Write one diagnostic line.
	 *
	 * The message deliberately carries **no package name**: the reducer does not
	 * know one, which is the boundary rule made structural rather than
	 * aspirational. The caller prefixes its own.
	 */
	data class Note(val tag: String, val message: String) : PlaybackEffect

	data class LogMetadata(val reason: String) : PlaybackEffect
	data class LogTransportState(val reason: String) : PlaybackEffect
}
