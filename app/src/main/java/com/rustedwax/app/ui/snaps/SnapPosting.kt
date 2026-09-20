package com.rustedwax.app.ui.snaps

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import com.rustedwax.app.snaps.PendingSnapKind
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

	/**
	 * Staging: signing the comment and committing it to disk.
	 *
	 * Deliberately still a visible wait, and a short one. A valid Snap
	 * transaction cannot be built without a current chain head and the live
	 * Snap container, so this window is two reads wide and irreducible — but it
	 * is *all* that is left of the old wait. What used to sit behind the same
	 * label was this plus the broadcast plus up to fifteen seconds of
	 * confirmation polling.
	 */
	data object Posting : SnapPostStatus

	/**
	 * The tap has been accepted and the intent is being written to disk.
	 *
	 * Local only, and one `SharedPreferences` commit wide. It exists to claim
	 * the card synchronously so a second tap cannot start a second attempt, and
	 * the screen deliberately draws it exactly like [Idle] with the control
	 * disabled — no label change, no spinner. There is nothing to narrate about
	 * a local write, and narrating it is what made posting feel slow.
	 */
	data object Claiming : SnapPostStatus

	/**
	 * Durably staged: signed, persisted, read back identical — and **not yet
	 * sent**.
	 *
	 * The card renders exactly as it does for [Posted], because from the
	 * user's side the Snap is theirs now: it has a permanent identity, a
	 * permlink that will never be minted twice, and bytes that survive a crash.
	 * What the app must not do is *claim the chain has it*, which is why this
	 * is a separate state and not a reuse of [Posted].
	 */
	data class Optimistic(val contentId: String) : SnapPostStatus

	/**
	 * Intended, never built, and nobody is working on it.
	 *
	 * The state a crash between the durable intent and the signed transaction
	 * leaves behind. The Snap exists on this device — frozen permlink, frozen
	 * body — and exists nowhere else, because nothing was ever sent.
	 *
	 * It must never be drawn like a delivered Snap. An intent that looked
	 * posted would be a permanent ghost: a card claiming publication, a Thread
	 * button leading to a comment that does not exist, and no way for the user
	 * to finish what they started. So it keeps its card — the words are still
	 * theirs — and says plainly that it needs another tap.
	 *
	 * Resolved only by [SnapPostController.retry], which the user asks for.
	 * Nothing here resumes on its own.
	 */
	data class Interrupted(val contentId: String) : SnapPostStatus

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
 * One social write this device started and could not finish, for whoever
 * eventually surfaces them.
 *
 * [kind] is the part a reader cannot work out for itself. An unfinished root
 * Snap and an unfinished reply need different words and lead to different
 * places — the History card in one case, a comment inside a thread in the
 * other — and the two live in different controllers, so the distinction has to
 * travel with the row rather than be inferred from the shape of a key.
 */
data class SnapAttention(
	val kind: Kind,
	/** The draft key the status is filed under, in its own controller. */
	val key: String,
	val status: SnapPostStatus,
) {
	enum class Kind {
		/** A Snap about something in History. */
		ROOT,

		/** A comment answering somebody, inside a thread. */
		REPLY,
	}
}

/**
 * Drives posting for the History cards, and keeps the result off the main thread.
 *
 * Holds no Snap rules of its own. Everything about duplicate safety lives in
 * [SnapPublisher]; this exists to give Compose something observable and to make
 * two things structurally impossible from the UI side:
 *
 *  - **a second tap while one is in flight.** The attempt claims its key
 *    synchronously, before the coroutine launches, and [isBusy] answers from
 *    that claim rather than from how the card reads — so the "disable repeated
 *    Post taps" rule does not depend on a click handler winning a race, and it
 *    keeps holding after the card has gone optimistic;
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
	 * Cards with a publication attempt **actually running**.
	 *
	 * Separate from [statuses] on purpose, and the separation is the fix. A
	 * status describes what the user should see; this describes whether a
	 * coroutine is live. They used to be the same thing, and once an attempt
	 * flipped the card to [SnapPostStatus.Optimistic] — which it does long
	 * before the broadcast — the status no longer said "busy", so a second
	 * call could start a parallel attempt: a second signature over the same
	 * row, overwriting the stored bytes, and a second broadcast.
	 *
	 * Ownership is held for the whole attempt and released in a `finally`, so
	 * a restored optimistic or interrupted card — whose original coroutine is
	 * long gone — is **not** locked and can still accept one explicit
	 * recovery action. A record is never permanently locked by how its status
	 * happens to read.
	 *
	 * A plain set, not snapshot state: it is only touched from the main thread
	 * and nothing draws it, so making it observable would buy recompositions
	 * for nothing. The same shape [SnapThreadController] uses for thread reads.
	 */
	private val running = mutableSetOf<String>()

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

	/**
	 * True while this card must not accept another Post tap.
	 *
	 * Answered by whether an attempt is *running*, not by how the card reads.
	 * The screen also disables the control, but the rule lives here: a
	 * controller that trusted the UI would start a second publication the
	 * first time some other caller forgot to.
	 */
	fun isBusy(key: String): Boolean = key in running

	fun post(
		key: String,
		eventId: String,
		media: SnapMedia,
		userText: String,
		/**
		 * Fired at the **durable intent**, before any network call.
		 *
		 * This is what closes the composer. It used to happen inside
		 * [onPublished], which only fires once Hive has confirmed — so the card
		 * appeared instantly and the text box stayed open above it for the
		 * whole background pipeline, which reads as "it didn't work". The Snap
		 * is the user's the moment the record is on disk; the box has nothing
		 * left to say from then on.
		 *
		 * Deliberately *not* the same callback as [onPublished]: closing a
		 * composer and destroying a draft are different acts, and only the
		 * second one may wait for the chain.
		 */
		onStaged: () -> Unit,
		onPublished: (String) -> Unit,
	) {
		// Claim the attempt synchronously. A second call arriving before the
		// coroutine is scheduled — or while it is still preparing behind an
		// optimistic card — finds the claim here and is turned away.
		if (!running.add(key)) return
		statuses[key] = SnapPostStatus.Claiming

		scope.launch {
			try {
			// ── Phase one: freeze the identity on disk. No network at all. ──
			//
			// `intendRoot` mints the permlink and builds the body locally, so
			// the only thing between the tap and the card is one local commit.
			// The container lookup, the chain head, the signature and the
			// broadcast all happen after the card is already up.
			val intent = withContext(io) {
				ownerStaged(key, eventId) { who, snapPublisher ->
					snapPublisher.intendRoot(who, eventId, media, userText)
				}
			}

			// Terminal answers are written back as they always were.
			// `ownerStaged` has already refused a stale key, so a refusal here
			// is this row's own and belongs on this row's card.
			when (intent) {
				// Nothing was committed, so nothing may be shown. This is the
				// only thing allowed to block the immediate card.
				is SnapPublisher.Staged.Failed -> {
					statuses[key] = SnapPostStatus.Failed(intent.message)
					return@launch
				}
				is SnapPublisher.Staged.Uncertain -> {
					statuses[key] = SnapPostStatus.Uncertain(intent.message)
					return@launch
				}
				is SnapPublisher.Staged.Published -> {
					statuses[key] = SnapPostStatus.Posted(intent.contentId)
					onPublished(intent.contentId)
					capturePosted(key, eventId)
					return@launch
				}
				is SnapPublisher.Staged.Ready -> Unit
			}

			val who = account()?.takeIf { it.isNotBlank() }
			if (who == null || key != SnapDraftKey.of(who, eventId)) return@launch release(key)

			// ── The durable boundary. The card goes up now. ──
			//
			// The permlink is minted and committed, the body is frozen, and
			// both survive a crash. Everything past this line is background and
			// none of it is on the visible path.
			statuses[key] = SnapPostStatus.Optimistic(intent.contentId)
			captureStaged(key, eventId)
			// The box closes here, with the card. The draft stays on disk,
			// invisible, until the chain confirms — see [onStaged].
			onStaged()

			// ── Phase two: the ordinary publication path, behind the card. ──
			//
			// `publishRoot` is the same call it has always been — stage, then
			// deliver. It resumes from the record phase one committed, reusing
			// that permlink and that body, resolves the container, signs,
			// persists the exact prepared transaction, and only then broadcasts.
			// There is no route from an intent straight to a node.
			val outcome = withContext(io) {
				owner(key, eventId) { w, snapPublisher ->
					snapPublisher.publishRoot(w, eventId, media, userText)
				}
			}
			if (account()?.takeIf { it.isNotBlank() } != who) return@launch
			settle(key, eventId, who, outcome, intent.contentId, onPublished)
			} finally {
				// Released on every path out, including each early return, so a
				// card is never locked by an attempt that has finished.
				running.remove(key)
			}
		}
	}

	/**
	 * Write back what an attempt decided, without losing an intent it left
	 * behind.
	 *
	 * A failure is only a *failure* if there is nothing on disk any more. When
	 * the record is still an intent — the container could not be resolved, the
	 * chain head could not be read — the Snap has not been lost, it has been
	 * interrupted, and the honest thing is to keep its card and offer another
	 * go rather than hide it behind an error.
	 */
	private suspend fun settle(
		key: String,
		eventId: String,
		who: String,
		outcome: SnapPublisher.Outcome,
		contentId: String,
		onPublished: (String) -> Unit,
	) {
		if (outcome is SnapPublisher.Outcome.Published) {
			statuses[key] = SnapPostStatus.Posted(outcome.contentId)
			onPublished(outcome.contentId)
			capturePosted(key, eventId)
			return
		}
		val stillIntended = outcome is SnapPublisher.Outcome.Failed &&
			withContext(io) { publisher()?.isInterrupted(who, eventId, PendingSnapKind.ROOT) == true }
		if (account()?.takeIf { it.isNotBlank() } != who) return
		statuses[key] = if (stillIntended) {
			SnapPostStatus.Interrupted(contentId)
		} else {
			outcome.toStatus()
		}
	}

	/**
	 * Finish a Snap the user intended and nobody completed. **Explicit only.**
	 *
	 * The single way out of [SnapPostStatus.Interrupted], and it exists because
	 * the alternative — resuming automatically on app start — publishes without
	 * a fresh user action, which is the one thing this whole path is built to
	 * avoid. A Snap intended yesterday is not a Snap the app may post today on
	 * its own.
	 *
	 * It mints nothing. [SnapPublisher.retryRoot] resumes the stored record, so
	 * the permlink, the body and the metadata are the ones frozen at the
	 * original tap, and each stored state is answered by the rule that already
	 * governs it — an uncertain row is still read rather than resent.
	 *
	 * The slot is claimed synchronously, so a second Retry tap cannot start a
	 * parallel attempt.
	 *
	 * [onPublished] is the **same** success callback [post] takes, fired from
	 * the same place — [settle], and only on a proven
	 * [SnapPublisher.Outcome.Published]. A Snap finished here is as published
	 * as one finished on the first attempt, so the draft it was written from
	 * has to be retired the same way; passing a no-op here is what left a
	 * retried Snap's draft on disk forever. Nothing else about the retry moves:
	 * an interrupted, uncertain or failed outcome never reaches this callback,
	 * and the callback cannot start anything.
	 */
	fun retry(key: String, eventId: String, onPublished: (String) -> Unit) {
		if (!running.add(key)) return
		val contentId = (statuses[key] as? SnapPostStatus.Interrupted)?.contentId
		statuses[key] = SnapPostStatus.Claiming

		scope.launch {
			try {
				val outcome = withContext(io) {
					owner(key, eventId) { w, snapPublisher -> snapPublisher.retryRoot(w, eventId) }
				}
				val who = account()?.takeIf { it.isNotBlank() }
				if (who == null || key != SnapDraftKey.of(who, eventId)) {
					return@launch release(key)
				}
				settle(key, eventId, who, outcome, contentId ?: "", onPublished)
				// Keep the card drawn whatever happened: the record is still on
				// disk, and the words on it are still the user's.
				if (outcome !is SnapPublisher.Outcome.Published) captureStaged(key, eventId)
			} finally {
				running.remove(key)
			}
		}
	}

	/**
	 * Drop a claim whose account is no longer signed in.
	 *
	 * Only ever removes a status a running attempt still owns, so a row that
	 * has since moved on is left alone. Without it a switch mid-stage would
	 * leave the card stuck on its claim for the session.
	 */
	private fun release(key: String) {
		if (isBusy(key)) statuses.remove(key)
	}

	/** [owner], for the staging half, which answers with a [SnapPublisher.Staged]. */
	private inline fun ownerStaged(
		key: String,
		eventId: String,
		action: (String, SnapPublisher) -> SnapPublisher.Staged,
	): SnapPublisher.Staged {
		val who = account()
		val snapPublisher = publisher()
		return when {
			who.isNullOrBlank() ->
				SnapPublisher.Staged.Failed("Sign in to your Hive account to post a Snap.")
			snapPublisher == null ->
				SnapPublisher.Staged.Failed("Snap posting isn't available right now.")
			key != SnapDraftKey.of(who, eventId) ->
				SnapPublisher.Staged.Failed(
					"You've switched Hive accounts — this draft belongs to a different one.",
				)
			else -> action(who, snapPublisher)
		}
	}

	/**
	 * Re-ask the chain about a card whose outcome is unknown.
	 *
	 * The only action offered on an uncertain Snap. It reads; it never sends.
	 */
	fun recheck(key: String, eventId: String) {
		if (!running.add(key)) return
		statuses[key] = SnapPostStatus.Posting
		scope.launch {
			try {
				val outcome = withContext(io) {
					// Same ownership check as posting. Reconciling under a stale
					// key would write one account's Snap state onto another's card.
					owner(key, eventId) { who, snapPublisher ->
						snapPublisher.reconcile(who, eventId, PendingSnapKind.ROOT)
							?: SnapPublisher.Outcome.Uncertain(
								"RustedWax still can't tell whether this Snap posted.",
							)
					}
				}
				statuses[key] = outcome.toStatus()
				if (outcome is SnapPublisher.Outcome.Published) capturePosted(key, eventId)
			} finally {
				running.remove(key)
			}
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

			// ── Phase one and a half: Snaps this device staged but never
			// settled, also straight from disk. ──────────────────────────────
			//
			// A crash between the durable checkpoint and the broadcast, or an
			// Activity rebuilt while one was in flight, leaves a record that is
			// signed, committed and undecided. The card belongs back on screen
			// immediately for exactly the reason it was shown in the first
			// place: the Snap has a permanent identity on this device.
			//
			// Drawing it sends nothing and decides nothing. `restoreStaged` is a
			// pure store read, and the reconciliation below is still the only
			// thing that can move any of these rows.
			val staged = withContext(io) { publisher()?.restoreStaged(who).orEmpty() }
			if (account() != who) return@launch
			staged.forEach { row ->
				val key = SnapDraftKey.of(who, row.eventId)
				// An intent that was never built reached no node, so it is
				// certainly not on Hive and needs the user, not a spinner.
				// Everything else has a transaction behind it and is left to
				// the reconciliation below.
				statuses[key] = if (row.interrupted) {
					SnapPostStatus.Interrupted(row.contentId)
				} else {
					SnapPostStatus.Optimistic(row.contentId)
				}
			}
			staged.forEach { row ->
				captureStaged(SnapDraftKey.of(who, row.eventId), row.eventId)
			}

			// ── Phase two: reconciliation, exactly as before. ─────────────────
			//
			// Unchanged behaviour, and still the only thing that decides an
			// unresolved row. It may now take as long as it takes.
			val resolved = withContext(io) {
				// Root Snaps only: a reply resolved here would be filed under a
				// History key that belongs to nothing, and reported as a Snap by
				// [needsAttention].
				publisher()?.restore(who, PendingSnapKind.ROOT).orEmpty()
			}
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
	/**
	 * Draw the card from the **staged** record, before the chain has been told.
	 *
	 * The same disk read [captureLocal] performs, against the same store, with
	 * the same ownership check — it differs only in which states it will answer
	 * for. See [PostedSnaps.staged].
	 */
	private suspend fun captureStaged(key: String, eventId: String) {
		val who = account()?.takeIf { it.isNotBlank() } ?: return
		if (key != SnapDraftKey.of(who, eventId)) return
		val source = postedSnaps() ?: return
		withContext(io) { source.staged(who, eventId) }?.let {
			if (account() == who) posted[key] = it
		}
	}

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

	/**
	 * Every Snap this device staged and could not settle, for the account
	 * signed in now.
	 *
	 * The seam Stage 6 will read when it has somewhere to put this. A plain
	 * query over state that already exists — no store, no channel, no retry and
	 * no notification machinery. Adding the bell later means adding a reader.
	 */
	fun needsAttention(): List<SnapAttention> {
		val prefix = "${account()?.takeIf { it.isNotBlank() } ?: "-"}|"
		return statuses.entries
			.filter { it.key.startsWith(prefix) }
			.filter {
				it.value is SnapPostStatus.Uncertain ||
					it.value is SnapPostStatus.Failed ||
					// Intended and never built. The one Stage 6 will most want:
					// nothing is on Hive and only the user can change that.
					it.value is SnapPostStatus.Interrupted
			}
			.map { SnapAttention(SnapAttention.Kind.ROOT, it.key, it.value) }
			.sortedBy { it.key }
	}
}
