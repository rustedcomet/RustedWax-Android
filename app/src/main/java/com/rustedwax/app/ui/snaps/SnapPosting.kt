package com.rustedwax.app.ui.snaps

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import com.rustedwax.app.snaps.PostedSnap
import com.rustedwax.app.snaps.PostedSnaps
import com.rustedwax.app.snaps.SnapMedia
import com.rustedwax.app.snaps.SnapPublisher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Snap area of one History card is currently showing. */
sealed interface SnapPostStatus {
	/** No attempt yet — compose and post. */
	data object Idle : SnapPostStatus

	/** In flight. Every control is locked, including Post. */
	data object Posting : SnapPostStatus

	/** On chain, proven. */
	data class Posted(val contentId: String) : SnapPostStatus

	/** Nothing reached Hive. The draft is intact and Retry is offered. */
	data class Failed(val message: String) : SnapPostStatus

	/**
	 * It may have posted. The draft is intact, and **Retry is not offered** —
	 * only a re-check, because another broadcast could duplicate a live comment.
	 */
	data class Uncertain(val message: String) : SnapPostStatus
}

/**
 * Drives posting for the History cards, and keeps the result off the main thread.
 *
 * Holds no Snap rules of its own. Everything about duplicate safety lives in
 * [SnapPublisher]; this exists to give Compose something observable and to make
 * two things structurally impossible from the UI side:
 *
 *  - **a second tap while one is in flight.** The status flips to
 *    [SnapPostStatus.Posting] before the coroutine launches, and the button is
 *    disabled on it, so the "disable repeated Post taps" rule does not depend on
 *    a click handler winning a race;
 *  - **acting as the wrong account.** The signed-in account is read *inside* the
 *    coroutine and checked against the draft key, at the moment of acting rather
 *    than when the card composed. Both [post] and [recheck] run that same check:
 *    a read is not harmless here either, because writing a status under another
 *    account's draft key would show one user another user's Snap state.
 */
@Stable
class SnapPostController(
	private val scope: CoroutineScope,
	private val publisher: () -> SnapPublisher?,
	private val account: () -> String?,
	private val io: CoroutineDispatcher = Dispatchers.IO,
	/**
	 * Read-only view of what has already been posted, for the card's posted
	 * state. Null-tolerant: without it the card still knows a Snap is on chain
	 * from its status, it just has no words to show.
	 */
	private val postedSnaps: () -> PostedSnaps? = { null },
) {

	private val statuses = mutableStateMapOf<String, SnapPostStatus>()

	/**
	 * The posted card's content, keyed exactly as [statuses] is.
	 *
	 * Keyed by draft key rather than by event means the account is part of the
	 * key, so one Hive user's posted Snap cannot be read out under another's —
	 * the same isolation drafts and statuses already have, for the same reason.
	 */
	private val posted = mutableStateMapOf<String, PostedSnap>()

	fun status(key: String): SnapPostStatus = statuses[key] ?: SnapPostStatus.Idle

	/** What this card's posted Snap says, once there is one to show. */
	fun posted(key: String): PostedSnap? = posted[key]

	/** True while this card must not accept another Post tap. */
	fun isBusy(key: String): Boolean = statuses[key] == SnapPostStatus.Posting

	fun post(
		key: String,
		eventId: String,
		media: SnapMedia,
		userText: String,
		onPublished: (String) -> Unit,
	) {
		// Claim the card synchronously. A second tap arriving before the coroutine
		// is scheduled still finds Posting here and is turned away.
		if (isBusy(key)) return
		statuses[key] = SnapPostStatus.Posting

		scope.launch {
			val outcome = withContext(io) {
				owner(key, eventId) { who, snapPublisher ->
					snapPublisher.publishRoot(who, eventId, media, userText)
				}
			}
			statuses[key] = outcome.toStatus()
			if (outcome is SnapPublisher.Outcome.Published) {
				onPublished(outcome.contentId)
				capturePosted(key, eventId)
			}
		}
	}

	/**
	 * Re-ask the chain about a card whose outcome is unknown.
	 *
	 * The only action offered on an uncertain Snap. It reads; it never sends.
	 */
	fun recheck(key: String, eventId: String) {
		if (isBusy(key)) return
		statuses[key] = SnapPostStatus.Posting
		scope.launch {
			val outcome = withContext(io) {
				// Same ownership check as posting. Reconciling under a stale key
				// would write one account's Snap state onto another's card.
				owner(key, eventId) { who, snapPublisher ->
					snapPublisher.reconcile(who, eventId)
						?: SnapPublisher.Outcome.Uncertain(
							"RustedWax still can't tell whether this Snap posted.",
						)
				}
			}
			statuses[key] = outcome.toStatus()
			if (outcome is SnapPublisher.Outcome.Published) capturePosted(key, eventId)
		}
	}

	/**
	 * Run [action] only if this card's draft key still belongs to the account
	 * signed in right now.
	 */
	private inline fun owner(
		key: String,
		eventId: String,
		action: (String, SnapPublisher) -> SnapPublisher.Outcome,
	): SnapPublisher.Outcome {
		val who = account()
		val snapPublisher = publisher()
		return when {
			who.isNullOrBlank() ->
				SnapPublisher.Outcome.Failed("Sign in to your Hive account to post a Snap.")
			snapPublisher == null ->
				SnapPublisher.Outcome.Failed("Snap posting isn't available right now.")
			key != SnapDraftKey.of(who, eventId) ->
				SnapPublisher.Outcome.Failed(
					"You've switched Hive accounts — this draft belongs to a different one.",
				)
			else -> action(who, snapPublisher)
		}
	}

	/**
	 * Settle anything that was in flight when the app last died.
	 *
	 * Restores confirmed cards to their posted state and reconciles unresolved
	 * ones by *reading* the chain. No Snap is ever (re)sent from here.
	 */
	fun resumePending() {
		scope.launch {
			val who = account()?.takeIf { it.isNotBlank() } ?: return@launch

			// ── Phase one: what is already proven, from disk, before a single
			// byte of network. ────────────────────────────────────────────────
			//
			// This is what stops a reopened app looking like it has forgotten
			// what it published. Everything a posted card needs — author,
			// permlink, body, time — is already in the pending store, proven and
			// durable, so none of it has any business waiting on Hive.
			//
			// It is a *separate* enumeration from the reconciling one on purpose.
			// `restore` decides confirmed and unresolved rows in one pass, and it
			// reads the chain to do it, so one row whose outcome is genuinely
			// unknown — a node that hangs, a device with no signal — used to hold
			// back every row whose outcome was never in doubt. A Snap known to be
			// on chain must not sit blank behind a different Snap's uncertainty.
			//
			// Nothing here asserts anything the disk does not already say:
			// `restoreLocal` returns confirmed rows only, and makes no RPC and no
			// write of its own.
			val confirmed = withContext(io) { publisher()?.restoreLocal(who).orEmpty() }
			// Re-check the account: it can change while a restore is running.
			if (account() != who) return@launch
			confirmed.forEach { (eventId, outcome) ->
				statuses[SnapDraftKey.of(who, eventId)] = outcome.toStatus()
			}
			confirmed.forEach { (eventId, _) ->
				captureLocal(SnapDraftKey.of(who, eventId), eventId)
			}

			// ── Phase two: reconciliation, exactly as before. ─────────────────
			//
			// Unchanged behaviour, and still the only thing that decides an
			// unresolved row. It may now take as long as it takes.
			val resolved = withContext(io) { publisher()?.restore(who).orEmpty() }
			if (account() != who) return@launch
			resolved.forEach { (eventId, outcome) ->
				statuses[SnapDraftKey.of(who, eventId)] = outcome.toStatus()
			}

			// The chain refresh, each row on its own coroutine so one slow or
			// failing lookup delays nobody else. Order is irrelevant here — the
			// rows phase one drew are already on screen, and a read can only
			// improve one.
			val drawn = confirmed.mapTo(mutableSetOf()) { it.first }
			resolved.asSequence()
				.filter { it.second is SnapPublisher.Outcome.Published }
				.forEach { (eventId, _) ->
					val key = SnapDraftKey.of(who, eventId)
					scope.launch {
						// A row phase one could not draw — reconciliation has only
						// just proved it — still gets its local read first, so it
						// survives a chain read that fails.
						if (eventId !in drawn) captureLocal(key, eventId)
						refreshPosted(key, eventId)
					}
				}
		}
	}

	/**
	 * Fill in a posted card, locally first and then from the chain.
	 *
	 * Two steps on purpose. The local record already holds everything the card
	 * needs, so the Snap appears the moment it is confirmed rather than after a
	 * round trip — and a device with no signal shows the same card, permanently,
	 * rather than an empty space waiting for a refresh that will not come. The
	 * chain read that follows can only *improve* it: the canonical body and the
	 * real creation time, and on any failure the local version simply stays.
	 *
	 * Nothing here writes. [PostedSnaps] holds a reader and a store it only
	 * reads from, so no path through this function can mint a permlink, send a
	 * transaction, or move a Snap's stored state — recovery of the display is
	 * not recovery of the write.
	 */
	private suspend fun capturePosted(key: String, eventId: String) {
		captureLocal(key, eventId)
		refreshPosted(key, eventId)
	}

	/**
	 * Draw this card from the stored record alone. Disk only — never the chain.
	 *
	 * The record is already proven: `CONFIRMED` means the comment is on Hive and
	 * the body beside it is the body that went there. Rendering it costs a
	 * preference read, and nothing about it can be improved by waiting.
	 */
	private suspend fun captureLocal(key: String, eventId: String) {
		val who = account()?.takeIf { it.isNotBlank() } ?: return
		// The same ownership rule the write path uses, for the same reason: this
		// writes into a map the card reads by key, and filling another account's
		// key would put one user's words on another user's screen.
		if (key != SnapDraftKey.of(who, eventId)) return
		val source = postedSnaps() ?: return
		withContext(io) { source.local(who, eventId) }?.let {
			if (account() == who) posted[key] = it
		}
	}

	/**
	 * Improve an already-drawn card with what the chain says.
	 *
	 * Strictly an upgrade: the canonical body and the real creation time. On any
	 * failure the locally drawn card simply stays, which is why this may run
	 * late, concurrently, or not at all without costing the user anything.
	 */
	private suspend fun refreshPosted(key: String, eventId: String) {
		val who = account()?.takeIf { it.isNotBlank() } ?: return
		if (key != SnapDraftKey.of(who, eventId)) return
		val source = postedSnaps() ?: return
		withContext(io) { runCatching { source.refreshed(who, eventId) }.getOrNull() }?.let {
			if (account() == who) posted[key] = it
		}
	}

	private fun SnapPublisher.Outcome.toStatus(): SnapPostStatus = when (this) {
		is SnapPublisher.Outcome.Published -> SnapPostStatus.Posted(contentId)
		is SnapPublisher.Outcome.Failed -> SnapPostStatus.Failed(message)
		is SnapPublisher.Outcome.Uncertain -> SnapPostStatus.Uncertain(message)
	}
}
