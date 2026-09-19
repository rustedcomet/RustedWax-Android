package com.rustedwax.app.ui.snaps

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rustedwax.app.snaps.PostedSnap
import com.rustedwax.app.snaps.SnapPublisher
import com.rustedwax.app.snaps.SnapReplyIntent
import com.rustedwax.app.snaps.SnapReplyKey
import com.rustedwax.app.snaps.SnapReplyTarget
import com.rustedwax.app.snaps.SnapThread
import com.rustedwax.app.snaps.SnapThreadBuilder
import com.rustedwax.app.snaps.SnapThreadPreview
import com.rustedwax.app.snaps.SnapThreadReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where one root Snap's conversation has got to. */
sealed interface SnapThreadLoad {
	data object Loading : SnapThreadLoad

	data class Ready(val thread: SnapThread) : SnapThreadLoad

	/**
	 * The chain could not be read. Distinct from a thread with no replies, and
	 * the screen says so — "RustedWax couldn't load the replies" and "no replies
	 * yet" are different facts about somebody's conversation.
	 */
	data class Unavailable(val message: String) : SnapThreadLoad
}

/**
 * Threads, reply drafts and reply publication for the History screen.
 *
 * Built alongside [SnapPostController] rather than inside it. The two share no
 * state and no store: root Snaps and replies are separate write slots in
 * separate preference files, and keeping the drivers apart means a change to
 * one cannot quietly alter the other's duplicate rules — Stage 1–3 behaviour is
 * preserved by this class not being able to reach it.
 *
 * **Account isolation has two halves here, and both are needed.**
 *
 * The first is keying. Threads, previews, drafts and post statuses are all
 * filed under [SnapDraftKey.of], so a new account simply looks up keys that do
 * not exist yet and the previous account's data stays where it is, intact and
 * recoverable when they sign back in.
 *
 * The second is *active UI state*, which has no key at all. Which thread is
 * open, which composer is expanded and which root Snap the sheet is drawing are
 * single values, and an earlier revision held them as plain globals — so Alice's
 * open thread stayed on screen after Bob signed in, and Bob's taps operated it.
 * They are now stamped with the account that opened them and read back through
 * getters that answer null to anybody else, which closes the sheet the instant
 * the account changes rather than trying to restore it across a switch.
 *
 * Both halves are backed up by a third rule: **every asynchronous completion
 * re-reads the account and refuses to mutate anything if it has changed.** A
 * load, a send and a recheck all start under one account and finish under
 * whoever is signed in when the network answers, and none of them may write a
 * status, clear a draft or collapse a composer belonging to the other.
 */
@Stable
class SnapThreadController internal constructor(
	private val scope: CoroutineScope,
	private val reader: () -> SnapThreadReader?,
	private val publisher: () -> SnapPublisher?,
	private val account: () -> String?,
	private val drafts: SnapReplyDraftStore,
	/**
	 * The last good card summary for each conversation, across an Activity
	 * being destroyed and rebuilt. See [SnapThreadPreviewStore] for why only the
	 * summary is kept and never the thread.
	 */
	private val previewStore: SnapThreadPreviewStore,
	private val io: CoroutineDispatcher = Dispatchers.IO,
) {

	/** Account-scoped `rootId` to what is known about that conversation. */
	private val loads = mutableStateMapOf<String, SnapThreadLoad>()

	/**
	 * The card-sized form of each loaded thread, computed once when it loads.
	 *
	 * Not derived on demand. History recomposes about once a second and may have
	 * several posted Snaps on screen, and [SnapThreadPreview.of] flattens and
	 * sorts the whole conversation — a few hundred comparisons per card per
	 * second, on the thread that draws, for an answer that only changes when the
	 * thread is re-read.
	 *
	 * Seeded from [previewStore] in [load] so a reopened app draws what it last
	 * knew instead of nothing. Read from composition, so it is only ever written
	 * from an effect or a coroutine — never during a draw.
	 */
	private val previews = mutableStateMapOf<String, SnapThreadPreview.Preview>()

	/** Account-scoped reply slot to the text typed against it. Write-through. */
	private val draftCache = mutableStateMapOf<String, String>()

	private val statuses = mutableStateMapOf<String, SnapPostStatus>()

	/**
	 * Slots whose stored draft could not be decoded, and why.
	 *
	 * Filled at the moments the user actually reaches for one — opening a reply
	 * composer, or attempting a send — rather than by reading the disk from
	 * composition. A slot in here is locked: no composer, no Send, no intent, no
	 * signature. The only way out is the explicit Discard.
	 */
	private val corruptDrafts = mutableStateMapOf<String, String>()

	/**
	 * Threads with a read already on its way.
	 *
	 * One read per conversation at a time. Opening the sheet is a deliberate
	 * refresh, and a deliberate action can still arrive more than once — a
	 * double tap, or a recomposition landing on the same click handler — so the
	 * second arrival joins the first rather than starting a second RPC for the
	 * same answer.
	 *
	 * A plain set, not snapshot state: it is only ever touched from the main
	 * thread inside [load] and never read from composition, so making it
	 * observable would cost recompositions for something no screen draws.
	 */
	private val inFlight = mutableSetOf<String>()

	// ── active UI state, and the account it belongs to ─────────────────

	/**
	 * The account that opened whatever is currently on screen.
	 *
	 * Not a key: a stamp. The three values below are meaningless to anybody
	 * else, so rather than being migrated or cleared on a switch — which would
	 * mean guessing what the new account wanted to be looking at — they simply
	 * stop being readable.
	 */
	private var uiOwner by mutableStateOf<String?>(null)

	private var openThreadState by mutableStateOf<SnapReplyTarget?>(null)
	private var openRootSnapState by mutableStateOf<PostedSnap?>(null)
	private var replyingToState by mutableStateOf<String?>(null)

	/** The root whose full thread is on screen — for *this* account, or null. */
	val openThread: SnapReplyTarget?
		get() = openThreadState.takeIf { uiOwner == accountId() }

	/**
	 * The root Snap the open thread hangs under, as History already vouched for
	 * it.
	 *
	 * Carried rather than re-read. The card that opened the sheet had already
	 * checked this Snap against the signed-in account
	 * ([com.rustedwax.app.snaps.PostedSnaps] refuses the rest), and re-deriving
	 * it here would be a second, looser route to the same thing — which is also
	 * why it must disappear with the sheet rather than outlive it.
	 */
	val openRootSnap: PostedSnap?
		get() = openRootSnapState.takeIf { uiOwner == accountId() }

	/** The account-scoped reply slot whose composer is open, at most one. */
	val replyingTo: String?
		get() = replyingToState.takeIf { uiOwner == accountId() }

	fun threadKey(root: SnapReplyTarget): String = SnapDraftKey.of(account(), root.contentId)

	/** The composer/draft key for one parent comment, under this account. */
	fun replyKey(target: SnapReplyTarget): String =
		SnapDraftKey.of(account(), SnapReplyKey.slot(target))

	/** The signed-in account as the key scheme spells it. Never null. */
	private fun accountId(): String = account()?.takeIf { it.isNotBlank() } ?: ANONYMOUS

	// ── reading ────────────────────────────────────────────────────────

	/** What is known now. Null means nothing has been asked yet. */
	fun state(root: SnapReplyTarget): SnapThreadLoad? = loads[threadKey(root)]

	fun thread(root: SnapReplyTarget): SnapThread? =
		(state(root) as? SnapThreadLoad.Ready)?.thread

	/** The bounded form the History card draws. Null until a thread is loaded. */
	fun preview(root: SnapReplyTarget): SnapThreadPreview.Preview? = previews[threadKey(root)]

	/**
	 * Fetch this conversation.
	 *
	 * Two callers with two different needs, and [force] is what separates them.
	 *
	 * **Unforced** is for composition. History recomposes about once a second,
	 * and scrolling a list of posted Snaps back and forth must not become a
	 * request per pass, so a thread that has already been read is left alone.
	 *
	 * **Forced** is for a deliberate act: opening the thread, retrying a failed
	 * read, or the moment after this user's own reply lands. A conversation is
	 * other people's, and it changes without this app being told — an earlier
	 * revision opened the sheet unforced, so a thread read once was never read
	 * again for the life of the process and replies posted by anybody else were
	 * simply never seen.
	 *
	 * A refresh is deliberately **not** a reset:
	 *
	 *  - the cached conversation stays on screen while the new read runs, so
	 *    opening the sheet shows the thread immediately rather than a spinner;
	 *  - a read that fails leaves good cached content exactly where it is. A
	 *    moment without signal is not a reason to throw away a conversation this
	 *    app has already successfully read, so [SnapThreadLoad.Unavailable] is
	 *    only ever reported when there is nothing better to show.
	 */
	fun load(root: SnapReplyTarget, force: Boolean = false) {
		val key = threadKey(root)
		// Draw the last good summary before anything is asked of the network.
		// Called from an effect and from the open handler, never from a draw, so
		// seeding snapshot state here is safe.
		if (!previews.containsKey(key)) previewStore.read(key)?.let { previews[key] = it }

		val cached = loads[key]
		if (!force && cached != null) return
		// One read at a time per conversation. A second arrival of the same
		// deliberate action joins this one instead of duplicating it.
		if (!inFlight.add(key)) return
		val who = accountId()
		// Only claim the screen when there is nothing on it. Over a conversation
		// already loaded, a refresh is invisible until it has something better.
		if (cached !is SnapThreadLoad.Ready) loads[key] = SnapThreadLoad.Loading

		scope.launch {
			try {
				val replies = withContext(io) {
					runCatching { reader()?.read(root.author, root.permlink) }.getOrNull()
				}
				// The account can change while a read is in flight. Alice's answer
				// must not land anywhere at all once Bob is signed in — not under
				// Bob's key, and not under Alice's either, because writing it would
				// make Bob's screen recompose on a conversation he cannot see.
				if (accountId() != who) return@launch
				if (replies == null) {
					// Never downgrade a conversation that is already readable.
					if (loads[key] !is SnapThreadLoad.Ready) {
						loads[key] =
							SnapThreadLoad.Unavailable("RustedWax couldn't load the replies.")
					}
					// The previous preview stays too: a read that failed says
					// nothing about the replies the card was already showing.
					return@launch
				}
				val built = SnapThreadBuilder.build(root.contentId, replies)
				val preview = SnapThreadPreview.of(built)
				loads[key] = SnapThreadLoad.Ready(built)
				previews[key] = preview
				// A proven answer replaces the remembered one. A failed read
				// above returned already, so the stored summary only ever moves
				// forward.
				withContext(io) { previewStore.write(key, preview) }
			} finally {
				// Released on every path, including the account-switch return, so
				// a later open is never refused by a guard nobody cleared.
				inFlight.remove(key)
			}
		}
	}

	// ── the sheet ──────────────────────────────────────────────────────

	/**
	 * Show the full conversation, and re-read it.
	 *
	 * Opening the thread is the deliberate act that asks Hive what is there
	 * *now*. The cached conversation is drawn immediately and replaced when the
	 * fresh read arrives, so this costs the reader nothing and catches every
	 * reply somebody else added since the last look.
	 */
	fun open(root: SnapReplyTarget, rootSnap: PostedSnap?) {
		uiOwner = accountId()
		openThreadState = root
		openRootSnapState = rootSnap
		replyingToState = null
		load(root, force = true)
	}

	/** Leaves every draft exactly as typed — closing a thread never discards. */
	fun close() {
		uiOwner = null
		openThreadState = null
		openRootSnapState = null
		replyingToState = null
	}

	// ── reply drafts ───────────────────────────────────────────────────

	fun draft(key: String): String = draftCache[key]
		?: (drafts.read(key) as? SnapReplyDraftRead.Present)?.draft?.text
		?: ""

	/**
	 * Why this slot is locked, or null when it is fine.
	 *
	 * A pure in-memory read, safe to call from composition. See [corruptDrafts]
	 * for when it is populated.
	 */
	fun corruptReason(key: String): String? = corruptDrafts[key]

	fun isReplying(key: String): Boolean = replyingTo == key

	/**
	 * Open one reply composer, closing whatever was open. Nothing is lost.
	 *
	 * Refused outright when the sheet on screen belongs to another account:
	 * there is no control to tap in that state, and honouring it anyway would
	 * be letting one account steer the other's UI.
	 */
	fun startReply(key: String) {
		if (uiOwner != accountId()) return
		replyingToState = key
		// One disk read per tap, on the way in, so composition never has to ask.
		// The composer still opens on a corrupt slot — it opens onto the refusal
		// and the Discard control, which is the only honest thing to show.
		when (val read = drafts.read(key)) {
			is SnapReplyDraftRead.Corrupt -> corruptDrafts[key] = corruptMessage(read.reason)
			else -> corruptDrafts.remove(key)
		}
	}

	/** Collapse the composer. The draft stays exactly as typed. */
	fun cancelReply() {
		replyingToState = null
	}

	fun edit(key: String, text: String) {
		// A locked slot takes no edits. The store refuses too; this stops the
		// cache showing text the store never accepted.
		if (corruptDrafts.containsKey(key)) return
		draftCache[key] = text
		drafts.writeText(key, text)
	}

	/**
	 * Throw this reply draft away, on purpose.
	 *
	 * The one route that destroys typed text, so it is the one route with a
	 * refusal in it: a draft whose publication outcome is **unknown** is not
	 * discarded, because its stored intent id is the only thing still pointing
	 * at the pending record, and dropping that pointer would orphan a reply that
	 * may be live and let the next Send mint a fresh permlink for the same
	 * words. The user is told why and offered the re-check instead.
	 *
	 * Scoped exactly: one account, one parent comment. It clears the single
	 * entry at [replyKey] and touches nothing else — not another account's copy
	 * of the same conversation, not another comment's draft in the same thread,
	 * and not the root-Snap drafts, which live in a different store entirely.
	 */
	fun discard(target: SnapReplyTarget) {
		val key = replyKey(target)
		if (isBusy(key)) return
		val who = accountId()
		scope.launch {
			val read = withContext(io) { drafts.read(key) }
			if (accountId() != who) return@launch

			val expected: SnapReplyDraft? = when (read) {
				// Nothing stored. Clear the local traces and stop.
				SnapReplyDraftRead.Absent -> {
					forget(key)
					return@launch
				}
				// Undecodable, so there is no intent to check and no text to
				// weigh. Removing it is destructive and the user has just asked
				// for it; `null` is what tells the store to remove the entry only
				// while it is *still* unreadable, so this call can never take a
				// draft that has since been written cleanly.
				is SnapReplyDraftRead.Corrupt -> null
				is SnapReplyDraftRead.Present -> {
					// A draft whose attempt is still undecided keeps its pointer.
					if (withContext(io) { isUnresolved(who, read.draft.intentId, target) }) {
						if (accountId() != who) return@launch
						statuses[key] = SnapPostStatus.Uncertain(
							"RustedWax can't tell whether this reply posted, so the draft " +
								"stays until it knows. Check again first.",
						)
						return@launch
					}
					read.draft
				}
			}

			val removed = withContext(io) { drafts.removeIf(key, expected) }
			if (accountId() != who) return@launch
			if (!removed) {
				// Either the entry changed underneath — a newer draft, which must
				// not be destroyed by a decision taken about an older one — or the
				// write did not stick. Neither is a discard, and neither is
				// reported as one.
				draftCache.remove(key)
				statuses[key] = SnapPostStatus.Failed(
					"RustedWax couldn't discard this draft, so it's still here.",
				)
				return@launch
			}
			forget(key)
		}
	}

	/** Drop every local trace of a slot whose stored entry is gone. */
	private fun forget(key: String) {
		draftCache[key] = ""
		corruptDrafts.remove(key)
		statuses.remove(key)
		if (replyingToState == key) replyingToState = null
	}

	/** Whether an attempt, if this draft has made one, is still undecided. */
	private fun isUnresolved(who: String, intentId: String?, target: SnapReplyTarget): Boolean {
		if (intentId == null) return false
		// No publisher means no way to ask, and "cannot tell" resolves towards
		// keeping the draft rather than towards destroying it.
		val pub = publisher() ?: return true
		return pub.isUnresolved(who, SnapReplyKey.of(target, intentId))
	}

	// ── writing ────────────────────────────────────────────────────────

	fun status(key: String): SnapPostStatus = statuses[key] ?: SnapPostStatus.Idle

	fun isBusy(key: String): Boolean = statuses[key] == SnapPostStatus.Posting

	/**
	 * Publish one reply, once.
	 *
	 * The same guarantees the root Snap path makes, for the same reason — a
	 * reply is just as permanent and just as public:
	 *
	 *  - the slot is claimed **synchronously**, so a second tap arriving before
	 *    the coroutine is scheduled finds [SnapPostStatus.Posting] and is turned
	 *    away rather than starting a second broadcast;
	 *  - the intent id is minted and **committed before the network**, so the
	 *    attempt has a durable identity that survives process death;
	 *  - the account is re-read inside the coroutine, both before acting and
	 *    before any state is written back;
	 *  - the draft is destroyed only on a proven [SnapPublisher.Outcome.Published],
	 *    never optimistically. Failure keeps it; ambiguity keeps it and offers no
	 *    resend at all.
	 */
	fun send(root: SnapReplyTarget, target: SnapReplyTarget) {
		val key = replyKey(target)
		if (isBusy(key)) return
		val text = draft(key)
		// Checked here so the control is honest, and checked again at the
		// publication boundary, which is what actually enforces it.
		if (!SnapText.isValid(text)) return
		val who = accountId()
		statuses[key] = SnapPostStatus.Posting

		scope.launch {
			val attempt = withContext(io) { publish(key, target, text) }
			if (accountId() != who) return@launch
			if (attempt.locked) corruptDrafts[key] = attempt.outcome.messageOrEmpty()
			statuses[key] = attempt.outcome.toStatus()
			if (attempt.outcome is SnapPublisher.Outcome.Published) {
				settle(root, key, who, target, attempt.intentId)
			}
		}
	}

	/**
	 * What one attempt did, and what it believes is on disk because of it.
	 *
	 * [intentId] names the attempt. Settlement needs it to find the pending
	 * record and read back the body that actually reached Hive — which is not
	 * necessarily the text this controller passed in, and is never whatever
	 * happens to be in the draft now.
	 */
	private data class Attempt(
		val outcome: SnapPublisher.Outcome,
		/** The intent this attempt ran under, when it got as far as having one. */
		val intentId: String?,
		/** True when the slot is locked because its stored draft is unreadable. */
		val locked: Boolean = false,
	)

	/**
	 * Mint-or-reuse the intent, prove it is stored, then publish under it.
	 *
	 * The order is the whole fix. An intent that is not durably on disk before
	 * the broadcast is an attempt that a crash can detach from its draft, and a
	 * detached draft is one whose next Send mints a second permlink for words
	 * that may already be on chain.
	 *
	 * An undecodable stored draft stops here, before an intent exists. Nothing
	 * is minted, nothing is prepared, nothing is signed and nothing is sent —
	 * see [SnapReplyDraftRead.Corrupt] for why there is no safe way to guess
	 * what it was.
	 */
	private fun publish(
		key: String,
		target: SnapReplyTarget,
		text: String,
	): Attempt {
		val read = drafts.read(key)
		if (read is SnapReplyDraftRead.Corrupt) {
			return Attempt(
				outcome = SnapPublisher.Outcome.Failed(corruptMessage(read.reason)),
				intentId = null,
				locked = true,
			)
		}
		val stored = (read as? SnapReplyDraftRead.Present)?.draft?.intentId
		var used: String? = null
		val outcome = owner(key, target) { who, pub ->
			val intentId = stored ?: SnapReplyIntent.generate()
			if (stored == null && !drafts.beginIntent(key, intentId)) {
				return@owner SnapPublisher.Outcome.Failed(
					"RustedWax couldn't save this reply safely, so it didn't send. " +
						"Your draft is still here.",
				)
			}
			used = intentId
			pub.publishReply(who, target, intentId, text)
		}
		return Attempt(outcome, used)
	}

	/**
	 * Re-ask the chain about a reply whose outcome is unknown.
	 *
	 * The only action offered on an ambiguous reply. It reads; it never sends.
	 */
	fun recheck(root: SnapReplyTarget, target: SnapReplyTarget) {
		val key = replyKey(target)
		if (isBusy(key)) return
		val who = accountId()
		statuses[key] = SnapPostStatus.Posting
		scope.launch {
			val attempt = withContext(io) {
				val read = drafts.read(key)
				if (read is SnapReplyDraftRead.Corrupt) {
					return@withContext Attempt(
						SnapPublisher.Outcome.Failed(corruptMessage(read.reason)),
						intentId = null,
						locked = true,
					)
				}
				val stored = (read as? SnapReplyDraftRead.Present)?.draft
				var checked: String? = null
				val outcome = owner(key, target) { w, pub ->
					val intentId = stored?.intentId
						?: return@owner SnapPublisher.Outcome.Failed(
							"There's nothing to check — this reply hasn't been sent.",
						)
					checked = intentId
					pub.reconcile(w, SnapReplyKey.of(target, intentId))
						?: SnapPublisher.Outcome.Uncertain(
							"RustedWax still can't tell whether this reply posted.",
						)
				}
				Attempt(outcome, checked)
			}
			if (accountId() != who) return@launch
			if (attempt.locked) corruptDrafts[key] = attempt.outcome.messageOrEmpty()
			statuses[key] = attempt.outcome.toStatus()
			if (attempt.outcome is SnapPublisher.Outcome.Published) {
				settle(root, key, who, target, attempt.intentId)
			}
		}
	}

	/**
	 * A reply is proven on chain: settle the draft it was written from, and read
	 * the conversation back.
	 *
	 * Everything that decides anything happens inside
	 * [SnapReplyDraftStore.settle], in one atomic step, against the body that
	 * actually reached Hive — read back from the pending record rather than
	 * assumed, because a draft edited while the network was busy makes the two
	 * differ, and the difference is the text that must survive.
	 *
	 * This function only reflects the answer on screen. It never decides to
	 * publish anything: a released draft becomes a fresh unsent one and waits
	 * for the user to press Send again, which is the only thing that mints a new
	 * intent.
	 *
	 * The thread is re-read rather than having the new reply spliced in locally:
	 * the thread on screen should be what Hive holds, and a locally invented
	 * node would be RustedWax asserting a publication it has already proven,
	 * with the wrong timestamp.
	 */
	private suspend fun settle(
		root: SnapReplyTarget,
		key: String,
		who: String,
		target: SnapReplyTarget,
		intentId: String?,
	) {
		applySettlement(key, who, target, intentId)
		if (accountId() != who) return
		load(root, force = true)
	}

	/**
	 * The settlement itself, with no screen refresh attached.
	 *
	 * Split out so the restart path can settle without pretending to know which
	 * conversation to reload — at startup there is no thread on screen, and
	 * fetching one rooted at a reply's parent would be asking Hive the wrong
	 * question.
	 */
	private suspend fun applySettlement(
		key: String,
		who: String,
		target: SnapReplyTarget,
		intentId: String?,
	) {
		val result = withContext(io) {
			// No intent means nothing was ever attached to this draft, so there
			// is nothing of this draft's to settle.
			val id = intentId ?: return@withContext SnapReplySettlement.Untouched
			val body = publisher()?.publishedBody(who, SnapReplyKey.of(target, id))
				?: return@withContext SnapReplySettlement.Untouched
			drafts.settle(key, SnapReplyDraft(body, id))
		}
		if (accountId() != who) return

		when (result) {
			SnapReplySettlement.Retired -> {
				draftCache[key] = ""
				corruptDrafts.remove(key)
				if (replyingToState == key) replyingToState = null
			}
			// Somebody typed while this was in flight. Their words are still
			// there, no longer tied to the published attempt, and the composer
			// stays open on them.
			is SnapReplySettlement.Released -> draftCache[key] = result.draft.text
			// Nothing was changed, or the write did not stick. Neither is a
			// settled draft, and neither is reported as one — drop the cache so
			// the screen shows whatever the store really holds.
			SnapReplySettlement.Untouched, SnapReplySettlement.Failed -> draftCache.remove(key)
		}
	}

	/**
	 * Settle anything that was in flight when the app last died.
	 *
	 * Reconciles by *reading* the chain — [SnapPublisher.restore] broadcasts
	 * nothing — so an ambiguous reply is decided before the user is offered
	 * anything, rather than after they tap Send a second time.
	 *
	 * The draft-clearing rule here is deliberately narrow: a draft is only
	 * cleared when **its own stored intent id is the one that was confirmed**.
	 * That is what separates "the crash happened between the confirmation and
	 * the draft being cleared" from "the user has since started writing
	 * something new to the same person" — the first has a matching intent and is
	 * finished, the second has no intent yet and is left completely alone.
	 */
	fun resumePending() {
		scope.launch {
			val who = accountId()
			if (who == ANONYMOUS) return@launch
			val resolved = withContext(io) { publisher()?.restore(who).orEmpty() }
			if (accountId() != who) return@launch

			resolved.forEach { (eventId, outcome) ->
				val slot = SnapReplyKey.slotOf(eventId) ?: return@forEach
				val intentId = SnapReplyKey.intentOf(eventId) ?: return@forEach
				val key = SnapDraftKey.of(who, slot)

				val read = withContext(io) { drafts.read(key) }
				if (accountId() != who) return@forEach

				// An undecodable entry is locked, not resolved. Nothing here may
				// clear it, repair it or speak for it — only the user can, and
				// only by discarding it on purpose.
				if (read is SnapReplyDraftRead.Corrupt) {
					corruptDrafts[key] = corruptMessage(read.reason)
					return@forEach
				}

				// A record whose intent is not the one this draft is carrying
				// belongs to an earlier, finished attempt. Its status is not this
				// composer's status and its resolution is not this draft's
				// business.
				val stored = (read as? SnapReplyDraftRead.Present)?.draft ?: return@forEach
				if (stored.intentId != intentId) return@forEach

				statuses[key] = outcome.toStatus()
				if (outcome is SnapPublisher.Outcome.Published) {
					// The same atomic settlement the live path uses, for the same
					// reasons: the slot may have been rewritten since the read
					// above, and the text that was published is the pending
					// record's, not this draft's.
					val target = SnapReplyKey.targetOf(slot) ?: return@forEach
					applySettlement(key, who, target, intentId)
				}
			}
		}
	}

	private inline fun owner(
		key: String,
		target: SnapReplyTarget,
		action: (String, SnapPublisher) -> SnapPublisher.Outcome,
	): SnapPublisher.Outcome {
		val who = account()
		val pub = publisher()
		return when {
			who.isNullOrBlank() ->
				SnapPublisher.Outcome.Failed("Sign in to your Hive account to reply.")
			pub == null ->
				SnapPublisher.Outcome.Failed("Replying isn't available right now.")
			key != SnapDraftKey.of(who, SnapReplyKey.slot(target)) ->
				SnapPublisher.Outcome.Failed(
					"You've switched Hive accounts — this draft belongs to a different one.",
				)
			else -> action(who, pub)
		}
	}

	private fun corruptMessage(reason: String) =
		"RustedWax can't read this reply draft's saved state ($reason), so it won't " +
			"send in case it already did. Discard it to start again."

	private fun SnapPublisher.Outcome.messageOrEmpty(): String = when (this) {
		is SnapPublisher.Outcome.Failed -> message
		is SnapPublisher.Outcome.Uncertain -> message
		is SnapPublisher.Outcome.Published -> ""
	}

	private fun SnapPublisher.Outcome.toStatus(): SnapPostStatus = when (this) {
		is SnapPublisher.Outcome.Published -> SnapPostStatus.Posted(contentId)
		is SnapPublisher.Outcome.Failed -> SnapPostStatus.Failed(message)
		is SnapPublisher.Outcome.Uncertain -> SnapPostStatus.Uncertain(message)
	}

	private companion object {
		/** What [SnapDraftKey] files a signed-out user's rows under. */
		const val ANONYMOUS = "-"
	}
}
