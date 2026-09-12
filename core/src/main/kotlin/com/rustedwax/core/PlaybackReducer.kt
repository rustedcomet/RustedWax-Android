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
		withTransportContentEvidence(
			state,
			input,
			withEstablishedTimeline(withPlayerAdSuppression(reduceCore(state, input)), input),
		)

	/**
	 * No clock runs while the source's own player is drawing its advertisement UI.
	 *
	 * Asked of every reduction rather than of the branches that start the clock,
	 * for the same reason [withEstablishedTimeline] is: several routes set
	 * [ListenState.playingSinceElapsedMs], and an interval the player itself
	 * labelled an advertisement must not be measured by any of them. The window is
	 * dropped rather than banked — the transitions that could have been running one
	 * across the label's arrival already settled it in [onPlayerAdVisible].
	 */
	private fun withPlayerAdSuppression(transition: Transition): Transition {
		val state = transition.state
		if (state.playerAdSignal == null || state.playingSinceElapsedMs == null) return transition
		return transition.copy(state = state.copy(playingSinceElapsedMs = null))
	}

	/**
	 * Record how far the transport's own last word can carry the running clock.
	 *
	 * Every PLAYING callback restates it from its position, rate and the length of
	 * the item it was published for; anything else that callback reports forgets
	 * it. A bundle for the same instance keeps it only while it names the same
	 * length, because the end is a statement about that length.
	 *
	 * Asked here rather than in each branch that starts a clock, because the rule is
	 * about the transport, not about which branch happened to run. A transition
	 * that changed nothing is left exactly as it was: the caller reads an unchanged
	 * state as "keep whatever a nested finalization installed".
	 */
	private fun withTransportContentEvidence(
		before: ListenState,
		input: PlaybackInput,
		transition: Transition,
	): Transition {
		val next = transition.state
		if (next == before) return transition
		val (endsAt, basis) = when (input) {
			is PlaybackInput.TransportChanged -> {
				val position = input.newPositionMs?.takeIf { it >= 0 }
				// The length of the item the running clock belongs to, never the
				// bundle's: a native source can resume the organic item while its
				// bundle still names the interstitial's length. The longest length
				// this listen established errs toward crediting, and a quarantined
				// replacement is described by the replacement's own length.
				val duration = (
					next.durationReplacementMs
						?: maxOf(next.trackIdentity.durationMs ?: 0, next.longestDurationMs ?: 0)
					).takeIf { it > 0 }
				if (next.finalized || input.transport != TransportState.PLAYING ||
					position == null || duration == null || next.speed <= 0
				) {
					null to null
				} else {
					val remaining = (duration - position).coerceAtLeast(0)
					(input.elapsedRealtimeMs + (remaining / next.speed).toLong()) to duration
				}
			}
			is PlaybackInput.MetadataPublished ->
				if (next.instanceToken == before.instanceToken &&
					(
						next.durationReplacementMs
							?: maxOf(next.trackIdentity.durationMs ?: 0, next.longestDurationMs ?: 0)
						) == before.transportContentDurationMs
				) {
					before.transportContentEndsAtElapsedMs to before.transportContentDurationMs
				} else {
					null to null
				}
			else -> return transition
		}
		if (endsAt == next.transportContentEndsAtElapsedMs && basis == next.transportContentDurationMs) {
			return transition
		}
		return transition.copy(
			state = next.copy(
				transportContentEndsAtElapsedMs = endsAt,
				transportContentDurationMs = basis,
			),
		)
	}

	/**
	 * Nothing is measured until the transport says where in the item it is.
	 *
	 * Asked of every reduction rather than of the branches that start the clock,
	 * because a listen that must not be measured must not be measured by any
	 * route — and there are several that set [ListenState.playingSinceElapsedMs].
	 *
	 * A timeline is established by the first position the transport publishes at
	 * or after zero, or by the first length it names. Either alone is enough:
	 * both are statements about a real item, and a source that publishes one
	 * before the other must not be held back for the second. Once established it
	 * stays established for the life of the listen — a mid-playback callback that
	 * happens to carry no position is an ordinary gap, not a return to the
	 * unknown — and [startNewTrack] clears it, because the next item has to say
	 * where it is for itself.
	 *
	 * While it is not established the clock is simply never started, so the wall
	 * time spent in that state is not banked and cannot be credited later. When
	 * it is established the ordinary machinery starts the clock at that moment,
	 * which is the first instant this listen is known to be an item at a position.
	 */
	private fun withEstablishedTimeline(
		transition: Transition,
		input: PlaybackInput,
	): Transition {
		if (!source.requiresEstablishedTimeline) return transition
		val state = transition.state
		val established = state.timelineEstablished ||
			// The page named the item itself, which is how a page-backed listen
			// says what it is. Such a session may legitimately publish neither
			// number for a while, and its length can arrive later from the item's
			// own page. Never required, only sufficient: the feed tile this gate
			// exists for names nothing at all.
			input is PlaybackInput.PageNamedItem ||
			positionOf(input)?.let { it >= 0 } == true ||
			(durationOf(input) ?: 0) > 0 ||
			(state.trackIdentity.durationMs ?: 0) > 0
		if (!established) {
			return transition.copy(state = state.copy(playingSinceElapsedMs = null))
		}
		if (state.timelineEstablished) return transition
		return transition.copy(state = state.copy(timelineEstablished = true))
	}

	private fun positionOf(input: PlaybackInput): Long? = when (input) {
		is PlaybackInput.TransportChanged -> input.newPositionMs
		is PlaybackInput.PositionSeen -> input.positionMs
		else -> null
	}

	private fun durationOf(input: PlaybackInput): Long? = when (input) {
		is PlaybackInput.TransportChanged -> input.durationMs
		is PlaybackInput.MetadataPublished -> input.identity.durationMs
		else -> null
	}

	private fun reduceCore(state: ListenState, input: PlaybackInput): Transition =
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
		}.let { transition ->
			val exactPublishedPresentation =
				(input as? PlaybackInput.MetadataPublished)?.identity?.takeIf {
					it.hasExactSourceItemId && (it.durationMs ?: 0) > 0
				}
			if (source.republishesAlternateMediaDurations &&
				exactPublishedPresentation != null && !transition.state.finalized
			) {
				return@let transition.copy(
					state = transition.state.copy(
						organicPresentationDurationMs = exactPublishedPresentation.durationMs,
						// The transport published the exact item itself.
						organicAnchorProvenByExactItem = true,
						presentationUnprovenForNamedWork = false,
					),
				)
			}
			val currentPresentationIsAmbiguous =
				source.republishesAlternateMediaDurations &&
					transition.state.trackIdentity.isUsable &&
					(transition.state.trackIdentity.durationMs ?: 0) > 0 &&
					transition.state.organicPresentationDurationMs == null &&
					!transition.state.finalized
			if (currentPresentationIsAmbiguous &&
				!transition.state.presentationUnprovenForNamedWork
			) {
				transition.copy(
					state = transition.state.copy(presentationUnprovenForNamedWork = true),
				)
			} else {
				transition
			}
		}

	private fun dispatch(state: ListenState, input: PlaybackInput): Transition = when (input) {
		is PlaybackInput.MetadataPublished -> onMetadata(state, input)
		is PlaybackInput.TransportChanged -> onTransport(state, input)
		is PlaybackInput.PositionSeen -> onPositionSeen(state, input)
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
		PlaybackInput.PageNamedItem -> Transition(state)
		is PlaybackInput.ExactIdEstablished -> Transition(
			state.copy(trackIdentity = state.trackIdentity.copy(sourceItemId = input.sourceItemId)),
		)
		is PlaybackInput.PresentationAttributionEstablished ->
			onPresentationAttributionEstablished(state, input)
		is PlaybackInput.ForegroundShortTookOver -> onForegroundShortTookOver(state, input)
		is PlaybackInput.ForegroundShortReleased -> onForegroundShortReleased(state, input)
		is PlaybackInput.PictureInPictureObserved -> onPictureInPicture(state, input)
		is PlaybackInput.PlayerAdSurfaceObserved -> onPlayerAdSurface(state, input)
		is PlaybackInput.ForegroundSurface -> Transition(state)
		is PlaybackInput.IdleDeadlineReached -> onIdleDeadline(state, input)
	}

	private fun onPresentationAttributionEstablished(
		state: ListenState,
		input: PlaybackInput.PresentationAttributionEstablished,
	): Transition {
		val currentDurationMs = state.trackIdentity.durationMs
		if (!source.republishesAlternateMediaDurations || state.finalized ||
			state.durationReplacementMs != null || state.durationReplacementReturnPending ||
			state.trackIdentity.sourceItemId != input.sourceItemId ||
			currentDurationMs == null || currentDurationMs <= 0 ||
			currentDurationMs != input.presentationDurationMs
		) return Transition(state)
		return Transition(
			state.copy(
				organicPresentationDurationMs = currentDurationMs,
				organicAnchorProvenByExactItem = true,
				presentationUnprovenForNamedWork = false,
			),
		)
	}

	private fun onIdleDeadline(
		state: ListenState,
		input: PlaybackInput.IdleDeadlineReached,
	): Transition {
		if (state.finalized || state.suppressedByForegroundShort || state.describingTabOnly) {
			return Transition(state)
		}
		if (state.transport != TransportState.PLAYING) return Transition(state)
		// An advertisement in progress: nothing is being measured, so nothing has run
		// out, and the presentation that follows it is the one this listen is for.
		if (state.playerAdSignal != null) return Transition(state)
		// A presentation the source itself has not let this reducer attribute cannot
		// say when the named work should have ended, and a finalize is the one
		// decision here that cannot be taken back. The field ended a 213 s song at
		// 202 % on an interstitial's 30 s before a note of the song had played. A
		// listen held open costs nothing: STOPPED, the next track and the session's
		// own teardown all still end it.
		if (state.presentationUnprovenForNamedWork) return Transition(state)
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
		// progress. A clock held at the transport's own end of the item has used
		// up everything the transport can vouch for, even when that falls short of
		// the length this listen established.
		val transportRanOut = state.durationReplacementMs == null &&
			!state.durationReplacementReturnPending &&
			state.transportContentEndsAtElapsedMs?.let { input.elapsedRealtimeMs >= it } == true
		if (played < duration && !transportRanOut) return Transition(state)
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

	// ── 0. the player's own advertisement UI ───────────────────────────────

	/**
	 * What the source's player drew, applied to the presentation it was drawn over.
	 *
	 * A source can publish a pre-roll under the upcoming item's title and artist
	 * with the advertisement's length, so nothing in the session can tell the two
	 * apart — but its player draws literal advertisement labels for exactly as long
	 * as the advertisement runs. That literal state is authoritative where duration shape never
	 * was: while it shows, no clock runs, and a presentation it was drawn over from
	 * the start is an advertisement whose seconds belong to nothing.
	 *
	 * A look that could not see the player learns nothing. It keeps a label already
	 * read for the presentation it was read on, and forgets it at the next one.
	 */
	private fun onPlayerAdSurface(
		state: ListenState,
		input: PlaybackInput.PlayerAdSurfaceObserved,
	): Transition {
		// The foreground Shorts route owns the player, and its ad labels travel its
		// own route. Nothing the watch player showed describes that listen.
		if (state.suppressedByForegroundShort) return Transition(state)
		return when (input.surface) {
			PlayerAdSurface.UNOBSERVED -> Transition(
				state.copy(
					playerAdSurfaceObservable = false,
					presentationAdAbsentSinceElapsedMs = null,
				),
			)
			PlayerAdSurface.VISIBLE -> onPlayerAdVisible(
				state,
				input.signal?.trim()?.takeIf(String::isNotEmpty) ?: UNNAMED_PLAYER_AD_SIGNAL,
				input.elapsedRealtimeMs,
			)
			PlayerAdSurface.ABSENT -> onPlayerAdAbsent(state, input.elapsedRealtimeMs)
		}
	}

	private fun onPlayerAdVisible(
		state: ListenState,
		signal: String,
		elapsedRealtimeMs: Long,
	): Transition {
		val seen = state.copy(
			playerAdSignal = signal,
			playerAdSurfaceObservable = true,
			presentationAdAbsentSinceElapsedMs = null,
		)
		if (state.finalized || state.describingTabOnly) return Transition(seen)
		val newlyVisible = state.playerAdSignal == null
		val banked = seen.accumulate(elapsedRealtimeMs)
		val sincePresentation =
			(banked.playedMs - state.presentationBaselinePlayedMs).coerceAtLeast(0)
		// A presentation already measured as organic, with the player seen without
		// its label, is not an advertisement because a label now covers it: the
		// player draws it a moment before the session republishes, and the organic
		// seconds before that are real. Likewise a presentation credited for longer
		// than any observation lag could explain before the first look at all.
		// Either way the clock stops here and nothing already earned is touched.
		val coversOrganic = state.presentationOrganicConfirmed ||
			(!state.presentationShownAsAd && sincePresentation > PLAYER_AD_ONSET_REFUSAL_LIMIT_MS)
		if (coversOrganic) {
			return Transition(
				banked.copy(playingSinceElapsedMs = null),
				effects = if (newlyVisible) {
					listOf(
						PlaybackEffect.Note(
							"ad",
							"the player drew its ad label \"$signal\" over a presentation already " +
								"measured as organic; not measuring while it shows",
						),
					)
				} else {
					emptyList()
				},
			)
		}
		// The label belongs to this presentation from its first second: whatever was
		// credited on it before the label was read is the observation's lag, and it
		// is the advertisement's, not anything's progress.
		val keptMs = minOf(banked.playedMs, state.presentationBaselinePlayedMs)
		val refusedMs = banked.playedMs - keptMs
		return Transition(
			banked.copy(
				playedMs = keptMs,
				playingSinceElapsedMs = null,
				presentationShownAsAd = true,
			),
			effects = if (newlyVisible || refusedMs > 0) {
				listOf(
					PlaybackEffect.Note(
						"ad",
						"the player drew its ad label \"$signal\"; this presentation is an " +
							"advertisement and nothing is measured while the label shows" +
							if (refusedMs > 0) {
								" — the ${refusedMs}ms counted before the label was read is removed"
							} else {
								""
							},
					),
				)
			} else {
				emptyList()
			},
		)
	}

	private fun onPlayerAdAbsent(state: ListenState, elapsedRealtimeMs: Long): Transition {
		val wasVisible = state.playerAdSignal != null
		val cleared = state.copy(playerAdSignal = null, playerAdSurfaceObservable = true)
		if (state.finalized || state.describingTabOnly) {
			return Transition(cleared.copy(presentationAdAbsentSinceElapsedMs = null))
		}
		val playing = state.transport == TransportState.PLAYING
		// Only the label held this clock. A quarantine or an unconfirmed return still
		// holds it for reasons of its own, and those are not this observation's to end.
		val measuring = playing && state.durationReplacementMs == null &&
			!state.durationReplacementReturnPending
		var next = cleared
		if (wasVisible && measuring && next.playingSinceElapsedMs == null) {
			next = next.copy(playingSinceElapsedMs = elapsedRealtimeMs)
		}
		// An advertisement draws its label for as long as it runs, but a first look
		// can land before the player has drawn it. A presentation is organic only once
		// the player has been seen playing it without the label for long enough that
		// no ad could still be waiting to label itself.
		if (!state.presentationOrganicConfirmed) {
			val absentSince = state.presentationAdAbsentSinceElapsedMs
			next = when {
				!playing -> next.copy(presentationAdAbsentSinceElapsedMs = null)
				absentSince == null || wasVisible ->
					next.copy(presentationAdAbsentSinceElapsedMs = elapsedRealtimeMs)
				elapsedRealtimeMs - absentSince >= PLAYER_AD_ABSENT_CONFIRM_MS -> next.copy(
					presentationOrganicConfirmed = true,
					presentationShownAsAd = false,
					presentationAdAbsentSinceElapsedMs = null,
				)
				else -> next
			}
		}
		return Transition(
			next,
			effects = if (wasVisible) {
				listOf(
					PlaybackEffect.Note(
						"ad",
						if (measuring) {
							"the player's ad label is gone; measuring resumes"
						} else {
							"the player's ad label is gone"
						},
					),
				)
			} else {
				emptyList()
			},
		)
	}

	/**
	 * Replace an advertisement that held this listen's anchor with what followed it.
	 *
	 * The listen's anchor is the presentation measurement is attributed to, and a
	 * pre-roll takes it simply by arriving first. Every duration rule below reads
	 * the anchor as organic — which is how the trailer that followed two sponsored
	 * surfaces was quarantined as their replacement. Once the player has labelled
	 * the anchor an advertisement there is nothing to protect: its seconds were
	 * never credited, its length was never the work's, and an id resolved against it
	 * described it. The incoming presentation starts measurement exactly where the
	 * advertisement began, whatever its length.
	 */
	private fun onAdvertisementAnchorReplaced(
		state: ListenState,
		input: PlaybackInput.MetadataPublished,
		new: TrackIdentity,
	): Transition {
		val adDuration = state.trackIdentity.durationMs ?: 0
		val newDuration = new.durationMs ?: 0
		val banked = state.accumulate(input.elapsedRealtimeMs)
		val keptMs = minOf(banked.playedMs, state.presentationBaselinePlayedMs)
		val refusedMs = banked.playedMs - keptMs
		val freshListen = keptMs == 0L
		val longest = maxOf(state.presentationPriorLongestDurationMs ?: 0, newDuration).takeIf { it > 0 }
		val replaced = banked.copy(
			trackIdentity = new.copy(
				artist = new.artist ?: state.trackIdentity.artist,
				album = new.album ?: state.trackIdentity.album,
				// Only an id this bundle itself published. One resolved while the
				// advertisement was installed was resolved for the advertisement.
				sourceItemId = new.sourceItemId,
				durationMs = newDuration,
			),
			playedMs = keptMs,
			pipInferredMs = if (freshListen) 0 else banked.pipInferredMs,
			pipInference = if (freshListen) null else banked.pipInference,
			fastestSpeedSeen = if (freshListen) banked.speed else banked.fastestSpeedSeen,
			firstSeenPositionMs = if (freshListen) null else banked.firstSeenPositionMs,
			// The next position belongs to the advertisement's own timeline.
			leadInSkipsNextPosition = true,
			loopDetected = if (freshListen) false else banked.loopDetected,
			longestDurationMs = longest,
			organicPresentationDurationMs = null,
			organicAnchorProvenByExactItem = false,
			organicPresentationRebased = false,
			durationReplacementMs = null,
			durationReplacementOrganicPositionMs = null,
			durationReplacementReturnPending = false,
			durationReplacementEndedAtBoundary = false,
			durationReplacementReturnGraceScheduled = false,
			durationReplacementCandidatePositionMs = null,
			presentationUnprovenForNamedWork = false,
			playingSinceElapsedMs = input.elapsedRealtimeMs
				.takeIf { banked.transport == TransportState.PLAYING },
		).beginPresentation(
			baselinePlayedMs = state.presentationBaselinePlayedMs,
			priorLongestDurationMs = state.presentationPriorLongestDurationMs,
		)
		return Transition(
			replaced,
			before = buildList {
				add(PlaybackEffect.CancelStoppedFinalizationGrace)
				add(PlaybackEffect.CancelContinuation)
				add(PlaybackEffect.InvalidateInFlightIdentityRequest)
				add(PlaybackEffect.ClearPreResolvedNativeIdentity)
				add(
					PlaybackEffect.Note(
						"ad",
						"the ${adDuration / 1000}s presentation the player labelled an advertisement " +
							"is replaced by a ${newDuration / 1000}s presentation under the same title; " +
							"none of the advertisement is credited and measurement starts here" +
							if (refusedMs > 0) " (${refusedMs}ms removed)" else "",
					),
				)
			},
			effects = listOf(
				PlaybackEffect.InstallMetadata,
				PlaybackEffect.RequestCarryAuthority,
				PlaybackEffect.LogMetadata("presentation that replaced a labelled advertisement"),
			),
		)
	}

	// ── 1. progress and speed accounting ───────────────────────────────────

	private fun ListenState.accumulate(elapsedRealtimeMs: Long): ListenState {
		if (suppressedByForegroundShort || describingTabOnly) {
			return copy(playingSinceElapsedMs = null)
		}
		if (playingSinceElapsedMs == null) return this
		return copy(
			playedMs = playedMs + runningWindowMs(playingSinceElapsedMs, elapsedRealtimeMs),
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
				presentationBaselinePlayedMs = banked.presentationBaselinePlayedMs + input.playedMs,
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
		// A source restoring a saved position publishes a bare zero — or the tail of
		// what it was showing a moment ago — *before* it seeks to where the viewer
		// left off. That reading arrives first, so it becomes the listen's starting
		// point and the real one never can.
		//
		// Regression: a resumed item can publish a near-zero placeholder before a
		// materially later restored position. Treating the placeholder as the start
		// would let `restoreCarriedProgress` claim against an unplayed position.
		//
		// Nothing has been measured when that happens, which is what makes the
		// earlier reading discardable: it cannot have been watched. Bounded three
		// ways — no banked progress, a forward jump too large for any callback gap
		// to be playback, and only where this reading may establish a start at all.
		// A seek *after* real playback banks time first and so never reaches here,
		// which leaves seek protection exactly as it was, and the measured clock is
		// not touched either way: this decides only where the listen is recorded as
		// having begun.
		val restoredAfterPlaceholder = establishFirst &&
			firstSeenPositionMs != null &&
			playedMs <= UNMEASURED_START_MS &&
			pipInferredMs == 0L &&
			observed - firstSeenPositionMs >= RESTORED_POSITION_JUMP_MS
		return copy(
			firstSeenPositionMs = if (restoredAfterPlaceholder) {
				observed
			} else {
				firstSeenPositionMs ?: observed.takeIf { establishFirst }
			},
			lastObservedPositionMs = observed,
		)
	}

	/**
	 * Record a position and retry a parked native continuation exactly when the
	 * source replaces its constructor-time placeholder with the credible restored
	 * position. [notePosition] makes that transition one-shot; requiring an exact
	 * item id preserves the native carry boundary, and [TrackProgressCarry]'s
	 * existing contradiction check still decides whether the stored fragment fits.
	 */
	private fun onPositionSeen(
		state: ListenState,
		input: PlaybackInput.PositionSeen,
	): Transition {
		val updated = state.notePosition(input.positionMs, input.establishFirst)
		val replacedConstructorPlaceholder = input.establishFirst &&
			state.firstSeenPositionMs != null &&
			state.firstSeenPositionMs < RESTORED_POSITION_JUMP_MS &&
			updated.firstSeenPositionMs != state.firstSeenPositionMs
		return Transition(
			updated,
			effects = if (
				replacedConstructorPlaceholder &&
				source.requiresExactIdToCarryProgress &&
				updated.trackIdentity.hasExactSourceItemId
			) {
				listOf(PlaybackEffect.RestoreCarriedProgress)
			} else {
				emptyList()
			},
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
		// A pod handing one interstitial to the next wraps end-to-start exactly like
		// a repeat does, and this source publishes nothing that separates them. The
		// wrap is recorded either way — it is a true reading — but for a source that
		// republishes alternate lengths for one work it stops being *proof* that the
		// named work was consumed twice, so the interval it bounds is no longer
		// attributable until a presentation is proven organic.
		return copy(
			loopDetected = true,
			presentationUnprovenForNamedWork = presentationUnprovenForNamedWork ||
				(source.republishesAlternateMediaDurations && organicPresentationDurationMs == null),
		) to listOf(
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
				state.withNewInstance(input).copy(trackIdentity = new).beginPresentation(
					baselinePlayedMs = state.playedMs,
					priorLongestDurationMs = state.longestDurationMs,
				),
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
		// Asked before any duration shape, because the player's label answers the one
		// question those shapes can only guess at. Only while the advertisement holds
		// the anchor itself: a labelled replacement inside a quarantine is already kept
		// off the organic clock by the quarantine, and its return is handled there.
		if (state.presentationShownAsAd && state.durationReplacementMs == null &&
			!state.durationReplacementReturnPending
		) {
			return onAdvertisementAnchorReplaced(state, input, new)
		}
		val priorDuration = maxOf(
			state.trackIdentity.durationMs ?: 0,
			state.longestDurationMs ?: 0,
		).takeIf { it > 0 }
		val newDuration = new.durationMs ?: 0
		// A source that republishes alternate media lengths names one selected work
		// across its presentations, and both renderings of that work are the work.
		// An interstitial published under that same title is not one of them, and
		// this source publishes no advertisement key to say so — the captured
		// interstitial bundle is field-for-field identical to the song's.
		//
		// A fully consumed surface followed by a longer one is enough to discard only
		// while the source still has not positively attributed that surface. Once a
		// live exact-item proof corroborates the current duration, natural end is
		// ordinary playback and a later Song↔Video update retains the listen. This is
		// scale-free: duration never decides whether a presentation is organic.
		val spentPlayedMs = state.playedMsAt(input.elapsedRealtimeMs)
		val outgoingWasSpent = priorDuration != null &&
			(
				spentPlayedMs >= priorDuration ||
					(state.lastObservedPositionMs ?: 0) >= priorDuration
				)
		// Whether the outgoing surface was ever shown to be a particular item.
		//
		// A source that republishes alternate media lengths answers this with the
		// proof flags its own attribution writes. A source without that capability
		// never writes them — both writers are gated on it — so there
		// `presentationUnprovenForNamedWork` is permanently false and cannot be
		// asked. What can be asked is whether anything ever named this surface:
		// the transport publishes no item id for an interstitial, and no resolver
		// answer has been installed against it either. Once one has, the surface
		// keeps its seconds exactly as a proven alternate-media presentation does.
		val outgoingSurfaceUnproven = if (source.republishesAlternateMediaDurations) {
			state.presentationUnprovenForNamedWork
		} else {
			state.trackIdentity.sourceItemId.isNullOrBlank()
		}
		// Regression: a source can publish the upcoming item's title over a pre-roll
		// pod whose duration belongs to neither the interstitial nor the work. If the
		// unidentified pod runs nearly to its reported end before the genuine
		// presentation replaces it, the pod interval must remain quarantined. Held by
		// a capability gate, this rule could not read that shape, so
		// the interval was banked instead: the listen carried the pod's seconds
		// across that boundary and every later one, the anchor stayed at the pod's
		// 127 s, and the work's own seconds were never measured at all.
		val spentSurfaceSuperseded = outgoingSurfaceUnproven &&
			priorDuration != null &&
			newDuration > priorDuration &&
			outgoingWasSpent
		if (source.republishesAlternateMediaDurations && !spentSurfaceSuperseded) {
			val longest = maxOf(priorDuration ?: 0, new.durationMs ?: 0).takeIf { it > 0 }
			// Whether the presentation being left had been proven to *be* the named
			// work, which is the only thing that makes the time measured on it that
			// work's progress.
			//
			// This boundary is the last moment the two can be told apart. Carried
			// across, an unattributed interval becomes indistinguishable from the
			// next presentation's own, and whichever surface is proven later spends
			// it: 91 s and 39 s published under one song's title scored that song
			// 100 % after 110 s of it had actually played, and a 30 s pre-roll did
			// the same to a 223 s song. Both wrote to the chain.
			//
			// A presentation that *was* proven keeps its progress untouched here —
			// a Song↔Video switch is one listen continuing, and this branch does not
			// change it.
			// Proven means *this* surface was proven, not that some earlier one was.
			//
			// `presentationUnprovenForNamedWork` cannot answer that on its own:
			// nothing clears `organicPresentationDurationMs` at a boundary, so the
			// re-arm in `reduce` is gated shut and every presentation after the
			// first attribution inherits a proof it never earned. That inheritance
			// is what carried a 57 s interstitial into a 214 s song on the device
			// and finalized it at 244 s.
			//
			// The organic duration *is* the identity of the proven surface — both
			// writers set it to that presentation's own published length — so the
			// outgoing presentation is the proven one exactly when the two agree.
			// A presentation that really was attributed still keeps its progress,
			// which is the Song↔Video contract; one that merely followed it does not.
			// A surface that ran to its own end, wrapped end-to-start, and was then
			// replaced by a *longer* presentation handed over. It did not continue,
			// and its seconds stay with it however well it was identified.
			//
			// Being proven is not the same as being the item that follows. The
			// structured route names a real short upload whenever one exists inside
			// the duration tolerance of the pod slot — `Ets811a2uyQ` at 57 s and
			// `NxfN16Jtdrk` at 30 s and again at 37 s, one id that cannot be two
			// lengths — so a pod can hold a genuine exact-item proof and still not
			// be the song.
			//
			// All three conditions are needed, and the third is what the first
			// attempt at this rule got wrong. Spent-and-wrapped alone fires
			// symmetrically, and on the device it destroyed a complete 216 s listen
			// that had merely finished before a pod rather than after one. Requiring
			// the replacement to be *longer* separates the two orders: short filler
			// completing into a longer item is the pod shape, and a long item
			// completing into a shorter one is content followed by filler, whose
			// earned seconds are its own. `spentSurfaceSuperseded` above already
			// reads the same shape for the unproven case.
			//
			// A Song↔Video switch is untouched: it replaces the rendering mid-item,
			// so it is neither spent nor wrapped.
			val outgoingHandedOver = outgoingWasSpent &&
				state.loopDetected &&
				priorDuration != null &&
				newDuration > priorDuration
			val outgoingWasProven = state.organicAnchorProvenByExactItem &&
				!outgoingHandedOver &&
				state.organicPresentationDurationMs != null &&
				state.trackIdentity.durationMs != null &&
				kotlin.math.abs(
					state.trackIdentity.durationMs - state.organicPresentationDurationMs,
				) <= TrackIdentity.DURATION_REFINEMENT_TOLERANCE_MS
			val banked = if (outgoingWasProven) state else state.accumulate(input.elapsedRealtimeMs)
			val refusedMs = if (outgoingWasProven) 0 else banked.playedMs + banked.pipInferredMs
			val continuing = if (outgoingWasProven) {
				banked
			} else {
				banked.copy(
					playedMs = 0,
					pipInferredMs = 0,
					pipInference = null,
					// Not progress and never added to any; kept only so the outcome
					// path can say what was measured and refused rather than report a
					// zero that also means "nothing played".
					unattributedMeasuredMs = banked.unattributedMeasuredMs + refusedMs,
					// The clock restarts against this presentation, so the window that
					// was still running on the refused one cannot be banked into it.
					playingSinceElapsedMs = input.elapsedRealtimeMs
						.takeIf { banked.transport == TransportState.PLAYING },
				)
			}
			return Transition(
				continuing.copy(
					longestDurationMs = longest,
					// The current representation owns payload and resolver fields. Song
					// and Video are different exact catalog items, so only progress and
					// the logical listen token survive the presentation boundary.
					trackIdentity = new.copy(sourceItemId = null),
				).beginPresentation(
					baselinePlayedMs = continuing.playedMs,
					priorLongestDurationMs = state.longestDurationMs,
				),
				before = buildList {
					add(PlaybackEffect.CancelStoppedFinalizationGrace)
					add(PlaybackEffect.CancelContinuation)
					add(PlaybackEffect.InvalidateInFlightIdentityRequest)
					add(PlaybackEffect.ClearPreResolvedNativeIdentity)
					add(
						PlaybackEffect.Note(
							"native-identity",
							"reported an alternate media length " +
								"(${state.trackIdentity.durationMs?.div(1000)}s → " +
								"${new.durationMs?.div(1000)}s) for the same work; keeping one listen",
						),
					)
					if (refusedMs > 0) {
						add(
							PlaybackEffect.Note(
								"native-identity",
								"the ${refusedMs / 1000}s measured on the " +
									"${state.trackIdentity.durationMs?.div(1000)}s presentation was " +
									"never attributed to the named work, so it is not carried into " +
									"the ${new.durationMs?.div(1000)}s one; measurement restarts here",
							),
						)
					}
				},
				effects = listOf(
					PlaybackEffect.InstallMetadata,
					PlaybackEffect.RequestCarryAuthority,
					PlaybackEffect.LogMetadata("alternate media presentation for the same work"),
				),
			)
		}
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
				presentationUnprovenForNamedWork =
					state.presentationUnprovenForNamedWork && returnPending,
				playingSinceElapsedMs = input.elapsedRealtimeMs.takeIf {
					state.transport == TransportState.PLAYING && positionConfirmed
				},
			).beginPresentation(
				baselinePlayedMs = state.playedMs,
				// The organic length is already among these, so a label still drawn over
				// the returning presentation can never cost the work its own length.
				priorLongestDurationMs = maxOf(priorDuration ?: 0, state.longestDurationMs ?: 0)
					.takeIf { it > 0 },
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
						presentationUnprovenForNamedWork = false,
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

			(
				(!source.republishesAlternateMediaDurations &&
					priorDuration * ORGANIC_ANCHOR_SUPERSEDE_FACTOR <= newDuration) ||
					// A surface already played through whole and then replaced by a
					// longer one is provisional by the same standard this branch
					// already applies, without asking how long either of them is.
					spentSurfaceSuperseded
			)
		if (provisionalAnchorSuperseded) {
			val provisionalPlayedMs = state.playedMsAt(input.elapsedRealtimeMs)
			// The identity this listen holds was resolved *for the surface being
			// discarded*. Where one work is published as several presentations,
			// that answer describes the surface it was asked about and not the one
			// replacing it: the interval is thrown away here precisely because it
			// belonged to something else, and the id proved alongside it belonged
			// to the same something else.
			//
			// Inheriting it is how a legitimate song lost its only chance to be
			// identified. A 91 s surface wearing "Solid As A Rock" resolved to a
			// real 91 s upload; when the 213 s song replaced it the listen still
			// held that id and had just been marked unambiguous, so
			// `listenNeedsPresentationProof` saw an identified, attributed listen
			// and never asked again. The song played to the end without one lookup
			// of its own, and the interstitial's id was the first thing
			// finalization tried to re-verify. The alternate-media boundary above
			// already drops the id and re-asks for exactly this reason; this branch
			// is the same boundary reached by the other route.
			//
			// Only the *inherited* id is dropped. One the transport published on
			// this bundle is this presentation's own. And a source that publishes a
			// single presentation per work never reaches this: it has no second
			// presentation to confuse the first with, and its id is often the only
			// evidence a backgrounded tab has left.
			val discardsProvisionalIdentity = source.republishesAlternateMediaDurations
			return Transition(
				state.copy(
					trackIdentity = new.copy(
						artist = new.artist ?: state.trackIdentity.artist,
						album = new.album ?: state.trackIdentity.album,
						sourceItemId = new.sourceItemId
							?: state.trackIdentity.sourceItemId
								.takeUnless { discardsProvisionalIdentity },
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
					// The anchor this branch establishes is presentation evidence, not
					// an exact-item proof: a surface ran to its own end and a longer one
					// began under the same title. That is enough to measure this
					// presentation — which is the whole of Bug 5 — and it is *not*
					// enough to spend this presentation's seconds on whatever replaces
					// it later.
					//
					// Leaving them indistinguishable is what inflated the device: a 19 s
					// pod, spent, superseded by a 45 s pod that took this anchor for
					// free, then a 214 s song that read the anchor back as
					// `outgoingWasProven` and inherited the 45 s, finalizing at
					// `244s of 214s`. Nothing in that chain had been identified as the
					// song.
					organicAnchorProvenByExactItem = false,
					organicPresentationRebased = false,
					durationReplacementMs = null,
					durationReplacementOrganicPositionMs = null,
					durationReplacementEndedAtBoundary = false,
					durationReplacementReturnGraceScheduled = false,
					durationReplacementCandidatePositionMs = null,
					// Organic measurement starts here, so the listen is attributable again.
					presentationUnprovenForNamedWork = false,
					playingSinceElapsedMs = input.elapsedRealtimeMs
						.takeIf { state.transport == TransportState.PLAYING },
				).beginPresentation(baselinePlayedMs = 0, priorLongestDurationMs = null),
				before = buildList {
					add(PlaybackEffect.CancelStoppedFinalizationGrace)
					add(PlaybackEffect.CancelContinuation)
					if (discardsProvisionalIdentity) {
						// An answer still in flight for the discarded surface must not
						// land on this one, and the carried pre-resolution must not be
						// what finalization re-verifies first.
						add(PlaybackEffect.InvalidateInFlightIdentityRequest)
						add(PlaybackEffect.ClearPreResolvedNativeIdentity)
					}
					add(
						PlaybackEffect.Note(
							"native-identity",
							"a ${priorDuration / 1000}s presentation held this listen for " +
								"${provisionalPlayedMs / 1000}s before a ${newDuration / 1000}s " +
								"presentation replaced it under the same title; the shorter surface " +
								"never established an organic anchor, so its interval is discarded " +
								"and organic measurement starts here",
						),
					)
					if (discardsProvisionalIdentity) {
						add(
							PlaybackEffect.Note(
								"native-identity",
								"the id proved against the ${priorDuration / 1000}s surface is " +
									"discarded with it; the ${newDuration / 1000}s presentation is " +
									"asked for its own",
							),
						)
					}
				},
				effects = buildList {
					add(PlaybackEffect.InstallMetadata)
					// After the install, so the lookup is made against the
					// presentation that replaced the discarded one. Bounded by the
					// same semantic-key/duration signature every other request is:
					// this asks once for this presentation, not once per callback.
					// Requesting here rather than waiting for the next PLAYING
					// callback matters — this source can publish a new presentation
					// with no transport callback behind it for many seconds.
					if (discardsProvisionalIdentity) add(PlaybackEffect.RequestCarryAuthority)
					add(
						PlaybackEffect.LogMetadata(
							"organic presentation established after a provisional surface",
						),
					)
				},
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
			).beginPresentation(
				baselinePlayedMs = banked.playedMs,
				priorLongestDurationMs = priorDuration,
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
			// Earned before this transport existed, so no label read on it can remove it.
			presentationBaselinePlayedMs = state.presentationBaselinePlayedMs + input.playedMs,
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
		// An item that never said where it was, and that the page never named, is
		// not a listen to file. The clock was never started for it, so this is a
		// row with nothing in it: the feed tile the viewer scrolled past, arriving
		// as a track change when the next one begins. Suppressed rather than
		// reported at zero — the same answer a foreground Short already gets — and
		// that is also what keeps watch history, the watch page's length and the
		// threshold from ever being asked about it.
		if (source.requiresEstablishedTimeline && !state.timelineEstablished) {
			return Transition(
				state.copy(
					playedMs = 0,
					pipInferredMs = 0,
					playingSinceElapsedMs = null,
					finalized = true,
				),
				before = listOf(
					PlaybackEffect.CancelStoppedFinalizationGrace,
					PlaybackEffect.CancelContinuation,
					PlaybackEffect.Note(
						"playback",
						"[${input.reason}] the page never published a position or a length for " +
							"this item and never named it, so nothing was measured and there is " +
							"no listen to file",
					),
				),
			)
		}
		// Every terminal event in the app arrives here — STOPPED, the next track,
		// session teardown, disposal, the idle deadline — so this is the one place
		// that has to refuse. While the source has published lengths this reducer
		// could not attribute to the work it named, the measured interval is not the
		// work's progress and may not be reported as it: a pre-roll abandoned before
		// the song begins would otherwise freeze the song's title over the
		// interstitial's played time and duration, which is a false record whether or
		// not identity verification later happens to refuse it. Reported as zero
		// rather than suppressed, because the listen still has to end exactly once
		// and the outcome path already treats no measured progress as nothing to
		// file. Nothing is invented: progress is removed, never added.
		if (state.presentationUnprovenForNamedWork) {
			return Transition(
				state.copy(
					playedMs = 0,
					// Not progress, and never added to any. Recorded so the outcome
					// path can say "228s were measured and none of them could be
					// credited" instead of vanishing on a zero that also means
					// "nothing happened".
					//
					// Added to rather than assigned: a presentation boundary may
					// already have refused an earlier surface's interval, and the
					// explanation owes the user both.
					unattributedMeasuredMs = state.unattributedMeasuredMs +
						state.playedMsAt(input.elapsedRealtimeMs),
					// The same interval, kept apart from the earlier surfaces' so a
					// consumer that later proves *this* presentation can tell which
					// part of the refusal it just answered for.
					refusedFinalPresentationMs = state.playedMsAt(input.elapsedRealtimeMs),
					pipInferredMs = 0,
					pipInference = null,
					playingSinceElapsedMs = null,
					finalized = true,
				),
				before = listOf(
					PlaybackEffect.CancelStoppedFinalizationGrace,
					PlaybackEffect.Note(
						"native-identity",
						"[${input.reason}] the source never established which presentation was " +
							"the named work, so the " +
							"${state.playedMsAt(input.elapsedRealtimeMs) / 1000}s measured here is not " +
							"credited to it; ending the listen with no progress rather than " +
							"reporting an interval that may belong to an interstitial",
					),
				),
				effects = listOf(
					PlaybackEffect.FreezeAndReport(
						input.reason,
						input.persistRestartTombstone,
					),
				),
			)
		}
		val banked = state.accumulate(input.elapsedRealtimeMs)
		// A listen that ends on a presentation the player labelled an advertisement
		// ends without it. Its label may have gone a moment before the listen did —
		// the overlay clears first — and whatever ran in that moment is still the
		// advertisement's.
		val adRefusedMs = if (state.presentationShownAsAd) {
			(banked.playedMs - state.presentationBaselinePlayedMs).coerceAtLeast(0)
		} else {
			0
		}
		return Transition(
			banked.copy(finalized = true, playedMs = banked.playedMs - adRefusedMs),
			before = buildList {
				add(PlaybackEffect.CancelStoppedFinalizationGrace)
				if (adRefusedMs > 0) {
					add(
						PlaybackEffect.Note(
							"ad",
							"[${input.reason}] the ${adRefusedMs}ms measured after the player's ad " +
								"label cleared still belongs to the advertisement; not credited",
						),
					)
				}
			},
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
				).beginPresentation(
					baselinePlayedMs = state.playedMs,
					priorLongestDurationMs = state.longestDurationMs,
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
				// The watch player is no longer what is on screen.
				playerAdSignal = null,
				playerAdSurfaceObservable = false,
				presentationAdAbsentSinceElapsedMs = null,
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
		unattributedMeasuredMs = 0,
		refusedFinalPresentationMs = 0,
		longestDurationMs = null,
		durationReplacementMs = null,
		durationReplacementOrganicPositionMs = null,
		durationReplacementReturnPending = false,
		durationReplacementEndedAtBoundary = false,
		durationReplacementReturnGraceScheduled = false,
		durationReplacementCandidatePositionMs = null,
		organicPresentationDurationMs = null,
		organicAnchorProvenByExactItem = false,
		organicPresentationRebased = false,
		presentationUnprovenForNamedWork = false,
		lastObservedPositionMs = null,
		pipInference = null,
		pipInferredMs = 0,
		fastestSpeedSeen = 1.0,
		firstSeenPositionMs = null,
		loopDetected = false,
		timelineEstablished = false,
		playingSinceElapsedMs = elapsedRealtimeMs.takeIf { transport == TransportState.PLAYING },
		startedAtEpochSec = nowMillis / 1000,
		finalized = false,
		instanceToken = nextInstanceToken,
		instanceEstablishedAtMillis = nowMillis,
	).beginPresentation(baselinePlayedMs = 0, priorLongestDurationMs = null)

	/**
	 * The installed presentation changed; what the player showed is re-asked of the next one.
	 *
	 * A label the player is still visibly drawing describes whatever is installed
	 * now — the session republishes a moment before the overlay goes. A label that
	 * was read and has since gone out of sight does not: it was read for the
	 * presentation that just ended, and holding the next one to it would suppress a
	 * video on the strength of an ad nobody can see any more.
	 */
	private fun ListenState.beginPresentation(
		baselinePlayedMs: Long,
		priorLongestDurationMs: Long?,
	): ListenState {
		val stillDrawn = playerAdSignal != null && playerAdSurfaceObservable
		return copy(
			playerAdSignal = playerAdSignal.takeIf { stillDrawn },
			presentationShownAsAd = stillDrawn,
			presentationOrganicConfirmed = false,
			presentationAdAbsentSinceElapsedMs = null,
			presentationBaselinePlayedMs = baselinePlayedMs,
			presentationPriorLongestDurationMs = priorLongestDurationMs,
		)
	}

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

		/**
		 * How long the player must be seen playing a presentation without its ad label
		 * before that presentation is organic.
		 *
		 * An advertisement labels itself for as long as it runs; this only covers the
		 * moment before the overlay is drawn, and the observer looks once a second.
		 */
		const val PLAYER_AD_ABSENT_CONFIRM_MS = 3_000L

		/**
		 * The most a presentation may already have been credited for a first label to
		 * still remove it.
		 *
		 * Beyond this no observation lag explains it: the player was simply not being
		 * looked at, and seconds that old are not taken back on a later look.
		 */
		const val PLAYER_AD_ONSET_REFUSAL_LIMIT_MS = 15_000L

		/** Recorded when a source reports a visible ad surface without its words. */
		const val UNNAMED_PLAYER_AD_SIGNAL = "the player showed its advertisement controls"

		/**
		 * Banked progress below which a listen has not yet measured anything.
		 *
		 * A couple of transport callbacks' worth, so the placeholder a restoring
		 * source publishes is still inside it and any real viewing is not.
		 */
		const val UNMEASURED_START_MS = 3_000L

		/**
		 * A forward position jump no callback gap can explain as playback.
		 *
		 * The same distance [com.rustedwax.app.detect.SessionSnapshot.UNOBSERVED_LEAD_IN_MS]
		 * uses to decide a mid-item start is a resume rather than ordinary timing.
		 */
		const val RESTORED_POSITION_JUMP_MS = 10_000L

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

/** What a transport can be doing, with no platform constant in sight. */
enum class TransportState {
	PLAYING,
	PAUSED,
	STOPPED,
	OTHER,
}

/** What one look at the source's own player said about its advertisement UI. */
enum class PlayerAdSurface {
	/** The player drew one of its literal advertisement labels or controls. */
	VISIBLE,

	/** The player itself was on screen and drew no advertisement label. */
	ABSENT,

	/** The player could not be seen, so nothing was learned either way. */
	UNOBSERVED,
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
	 * What was measured under this title and then refused attribution, in ms.
	 *
	 * Written only where [playedMs] is cleared because the source never
	 * established which presentation was the named work. It is deliberately not
	 * progress and may never be added to anything: the whole point of clearing
	 * [playedMs] is that this interval may belong to an interstitial. It exists so
	 * the outcome path can tell "nothing was measured" apart from "something was
	 * measured and could not be credited", which are the same zero and owe the
	 * user very different explanations.
	 */
	val unattributedMeasuredMs: Long = 0,
	/**
	 * Of [unattributedMeasuredMs], the part measured on the presentation this
	 * listen finalized on, in ms.
	 *
	 * [unattributedMeasuredMs] is a running total across presentation boundaries,
	 * so it cannot say how much of itself belongs to the surface that is still
	 * published at the end — a 26 s interstitial and the 331 s song after it reach
	 * finalization as one 357 s number, and crediting that to the song would score
	 * it 107 %. This names the second half of that alone.
	 *
	 * Still not progress. Written only where [playedMs] is cleared, exactly like
	 * [unattributedMeasuredMs], and readable only by a consumer that has since
	 * obtained the very proof whose absence cleared it. Nothing in this reducer
	 * ever adds it back.
	 */
	val refusedFinalPresentationMs: Long = 0,
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
	/**
	 * The transport has said where in the item it is, at least once.
	 *
	 * Only consulted where [PlaybackSourceCapabilities.requiresEstablishedTimeline]
	 * says the source can play something that never will. See
	 * `PlaybackReducer.withEstablishedTimeline`.
	 */
	val timelineEstablished: Boolean = false,
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
	/**
	 * Whether [organicPresentationDurationMs] was set by a *live exact-item
	 * proof*, rather than by presentation evidence alone.
	 *
	 * Two very different things write that anchor. A proof — the source's own
	 * exact id, or a resolver answer whose length had to be this presentation's —
	 * says *which item is playing*. The spent-surface supersede says only that
	 * something short ended and something longer began under the same title,
	 * which is enough to start measuring and is not enough to say what is
	 * playing.
	 *
	 * Only the first may authorize carrying a presentation's seconds across a
	 * later boundary into whatever replaces it. Reading the anchor without asking
	 * which of the two wrote it is how an unidentified pod surface's time reached
	 * a song's `percent_played`.
	 */
	val organicAnchorProvenByExactItem: Boolean = false,
	/** True only after the bounded total-minus-played arithmetic established that rebase. */
	val organicPresentationRebased: Boolean = false,
	/**
	 * The source has not established which of the lengths it published is the work
	 * it named, so this listen's measured interval is not attributable to it.
	 *
	 * Set for every positive-duration presentation from a source that republishes
	 * alternate media lengths for one work. A provider-corroborated exact-item
	 * proof clears it only while that id and duration still describe the current
	 * surface. Supersede, return and bounded rebase evidence may also establish the
	 * organic surface; a new track begins ambiguous again.
	 *
	 * It carries no claim about *what* the surface is, and none about how long it
	 * is. It says only that no terminal event may report this interval as the
	 * named work's progress, and no length-derived deadline may end the listen.
	 */
	val presentationUnprovenForNamedWork: Boolean = false,
	/** Last position published by this transport, retained across metadata callbacks. */
	val lastObservedPositionMs: Long? = null,
	/**
	 * When the transport's last PLAYING callback runs out of the item it was
	 * published for — its position, rate and length taken at their word — or null
	 * when any of them is unknown.
	 *
	 * A running window is never credited past this instant. Content beyond it
	 * needs a callback to say playback went on: a seek, a wrap, the next track. A
	 * process frozen by the OS delivers none of those until it thaws, and the wall
	 * clock that passed meanwhile is not playback.
	 */
	val transportContentEndsAtElapsedMs: Long? = null,
	/** The item length [transportContentEndsAtElapsedMs] was derived from. */
	val transportContentDurationMs: Long? = null,

	val leadInSkipsNextPosition: Boolean = false,

	val loopDetected: Boolean = false,
	/** The browser is publishing its tab's own title instead of a track's. */
	val describingTabOnly: Boolean = false,
	/** The structurally proven foreground Shorts route owns this player. */
	val suppressedByForegroundShort: Boolean = false,
	/**
	 * The literal advertisement label the source's own player is drawing now, or null.
	 *
	 * While set, no played clock runs. See `PlaybackReducer.onPlayerAdSurface`.
	 */
	val playerAdSignal: String? = null,
	/** The player was in sight at the last look, so [playerAdSignal] is current. */
	val playerAdSurfaceObservable: Boolean = false,
	/**
	 * The installed presentation is an advertisement: the player labelled it before
	 * it had been measured as anything else, and has not since been seen playing it
	 * unlabelled. Its seconds are not progress.
	 */
	val presentationShownAsAd: Boolean = false,
	/** The player was seen playing the installed presentation without its ad label. */
	val presentationOrganicConfirmed: Boolean = false,
	/** When that unlabelled stretch began, while it is still too short to confirm. */
	val presentationAdAbsentSinceElapsedMs: Long? = null,
	/** Progress this listen had already banked when the installed presentation began. */
	val presentationBaselinePlayedMs: Long = 0,
	/** [longestDurationMs] as it stood before the installed presentation arrived. */
	val presentationPriorLongestDurationMs: Long? = null,
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
			playedMs + pipInferredMs + runningWindowMs(playingSinceElapsedMs, elapsedRealtimeMs)
		}

	/**
	 * Content consumed by a window that started at [startedAtElapsedMs], at the
	 * current rate, no further than [transportContentEndsAtElapsedMs].
	 */
	fun runningWindowMs(startedAtElapsedMs: Long?, elapsedRealtimeMs: Long): Long {
		val startedAt = startedAtElapsedMs ?: return 0
		val evidencedUntil = transportContentEndsAtElapsedMs
			?.let { minOf(elapsedRealtimeMs, it) }
			?: elapsedRealtimeMs
		return ((evidencedUntil - startedAt).coerceAtLeast(0) * speed).toLong()
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
		if (playerAdSignal != null) return null
		// Nothing this deadline could decide: the source has not established which
		// length is the work, so neither that length nor silence measured against it
		// may end this listen.
		if (presentationUnprovenForNamedWork) return null
		val total = durationMs?.takeIf { it > 0 }
			?: return PlaybackReducer.IDLE_FINALIZE_MAX_SILENCE_MS
		val remainingContent = (total - playedMsAt(elapsedRealtimeMs)).coerceAtLeast(0)
		val remainingWallClock = if (speed > 0) (remainingContent / speed).toLong() else remainingContent
		return remainingWallClock + PlaybackReducer.IDLE_FINALIZE_GRACE_MS
	}

	fun establishedDurationMs(publishedMs: Long?): Long? {
		val installedIsQuarantined = durationReplacementMs != null
		// The longest length guards an unproven presentation: an interstitial's
		// shorter number must not make the work look complete. Once an exact-item
		// proof has attributed the installed presentation to the work, its length
		// is the work's, and a longer one this title showed earlier belonged to a
		// surface that was not — a pre-roll published under the song's own title.
		if (!installedIsQuarantined && !durationReplacementReturnPending) {
			organicPresentationDurationMs?.takeIf { attributed ->
				organicAnchorProvenByExactItem && attributed > 0 &&
					trackIdentity.durationMs?.let { installed ->
						kotlin.math.abs(installed - attributed) <= TrackIdentity.DURATION_REFINEMENT_TOLERANCE_MS
					} == true
			}?.let { return it }
		}
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
	/**
	 * The page named the item it is playing, latched for this track.
	 *
	 * Carries nothing: it is a statement that this session is a chosen item
	 * rather than a feed tile, consumed only by the established-timeline gate.
	 */
	data object PageNamedItem : PlaybackInput

	data class ExactIdEstablished(val sourceItemId: String) : PlaybackInput

	/**
	 * Live, source-corroborated proof that the current duration surface belongs to
	 * the exact item named by [sourceItemId]. Terminal identity verification is
	 * deliberately insufficient: it can prove the work while an interstitial is
	 * still borrowing that work's metadata. The reducer spends this proof only
	 * when both the exact id and the current presentation duration still match.
	 */
	data class PresentationAttributionEstablished(
		val sourceItemId: String,
		val presentationDurationMs: Long,
	) : PlaybackInput

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
	 * The source's own player was looked at for its advertisement UI.
	 *
	 * [signal] is the literal label read when [surface] is
	 * [PlayerAdSurface.VISIBLE], and null otherwise. The reducer never infers an
	 * advertisement from anything else; this is the one input that says so.
	 */
	data class PlayerAdSurfaceObserved(
		val surface: PlayerAdSurface,
		val signal: String?,
		val elapsedRealtimeMs: Long,
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
