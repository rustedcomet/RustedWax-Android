package com.rustedwax.app.ui.snaps

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rustedwax.app.snaps.PendingSnapKind
import com.rustedwax.app.snaps.PostedSnap
import com.rustedwax.app.snaps.SnapDeleteCheck
import com.rustedwax.app.snaps.SnapDeleter
import com.rustedwax.app.snaps.SnapEditKind
import com.rustedwax.app.snaps.SnapEditor
import com.rustedwax.app.snaps.SnapPublisher
import com.rustedwax.app.snaps.SnapReply
import com.rustedwax.app.snaps.SnapReplyIntent
import com.rustedwax.app.snaps.SnapReplyKey
import com.rustedwax.app.snaps.SnapReplyTarget
import com.rustedwax.app.snaps.SnapReplyText
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
	/**
	 * Told about every chain answer this controller accepts, so Stage 6 can
	 * notice a reply somebody else wrote.
	 *
	 * Deliberately the *only* new thing Stage 6 asks of this class, and
	 * deliberately shaped so it cannot become more. It is handed the account the
	 * read belongs to, the root, and the rows exactly as they came back — it
	 * returns nothing, so no caller can steer a load, and it is invoked after
	 * the account guard below, so an answer fetched as one user is never
	 * reported under another.
	 *
	 * Defaulted to a no-op because nothing about reading a conversation depends
	 * on anybody listening: every test that existed before Stage 6 builds this
	 * controller exactly as it did.
	 */
	private val onChainRead: (String, SnapReplyTarget, List<SnapReply>) -> Unit = { _, _, _ -> },
	/**
	 * Edits this user's own Snap or reply in place. One per kind, because the
	 * two kinds keep their confirmed records in different stores. Null means
	 * editing is unavailable, and no Edit is ever offered.
	 */
	private val editor: (SnapEditKind) -> SnapEditor? = { null },
	/**
	 * Told when a root Snap's words changed on chain, with the account, the
	 * Snap's `author/permlink` and the new words — so the History card, which
	 * belongs to a different controller, draws the same text as the sheet.
	 */
	private val onRootEdited: (String, String, String) -> Unit = { _, _, _ -> },
	/**
	 * Deletes this user's own Snap or reply with Hive `delete_comment`. One
	 * per kind, as with [editor]. Null means deleting is unavailable, and no
	 * Delete is ever offered.
	 */
	private val deleter: (SnapEditKind) -> SnapDeleter? = { null },
	/**
	 * Told when a root Snap was proven deleted, with the account and its
	 * `author/permlink`, so the History card — another controller's — stops
	 * drawing it. The History row and its scrobble are untouched.
	 */
	private val onRootDeleted: (String, String) -> Unit = { _, _ -> },
	/**
	 * Told when the sheet opens on a root Snap, with the account and its
	 * `author/permlink`, so the root's own body is re-read from Hive alongside
	 * the replies (Issue 40C). The root belongs to the posting controller.
	 */
	private val onRootOpened: (String, String) -> Unit = { _, _ -> },
	private val io: CoroutineDispatcher = Dispatchers.IO,
) {

	/** Account-scoped `rootId` to what is known about that conversation. */
	private val loads = mutableStateMapOf<String, SnapThreadLoad>()

	/**
	 * The last chain answer for each conversation, before local rows are mixed in.
	 *
	 * Kept so a reply written a moment ago can be folded into the tree without
	 * asking Hive again — the alternative is a round trip between the tap and
	 * the reply appearing, which is the wait this whole change removes. Not
	 * snapshot state: nothing draws it, [loads] is what the screen reads.
	 */
	private val chainRows = mutableMapOf<String, List<SnapReply>>()

	/**
	 * Conversations with a reply attempt actually running.
	 *
	 * Separate from [statuses] for the reason the root path learned: a status
	 * describes what the user sees, and once a reply is drawn optimistically
	 * the status no longer says "busy" — so a second tap could start a second
	 * publication. Ownership is held for the whole attempt and released in a
	 * `finally`, so a restored optimistic reply is never permanently locked.
	 */
	private val running = mutableSetOf<String>()

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

	/**
	 * Threads that were asked for a read after a write (see [reloadAfterWrite])
	 * while one was already in flight. That earlier read may have left before
	 * the write landed, so its answer cannot stand in for one taken after it.
	 * One more read runs when it finishes (Issue 40C).
	 */
	private val again = mutableSetOf<String>()

	/**
	 * Account-scoped thread keys of root Snaps proven deleted (Issue 40C). A
	 * read that left before the deletion, and answers after it, must not put
	 * the conversation back on screen or in the stored preview.
	 */
	private val deletedRoots = mutableSetOf<String>()

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
	private var openEventState by mutableStateOf<String?>(null)
	private var replyingToState by mutableStateOf<String?>(null)

	/** What the bottom bar is editing instead of composing, if anything. */
	data class Editing(
		/** The conversation it belongs to, so the bar only appears in that sheet. */
		val root: SnapReplyTarget,
		/** The comment being edited: always the viewer's own. */
		val target: SnapReplyTarget,
		val kind: SnapEditKind,
		/** The words on screen when Edit was tapped. Saving these is not a write. */
		val original: String,
	)

	private var editingState by mutableStateOf<Editing?>(null)

	/** The words in the edit box. In memory only: an edit is not a draft. */
	var editText by mutableStateOf("")
		private set

	/** Why the last save did not change anything, or null. */
	var editStatus by mutableStateOf<SnapPostStatus?>(null)
		private set

	/**
	 * True while a save is running. Snapshot state, because it gates the Save
	 * control — a second tap must find it already dead.
	 */
	var isSavingEdit by mutableStateOf(false)
		private set

	/**
	 * Account-scoped `author/permlink` to the reply body a save just proved.
	 * See [withEdits]. Not snapshot state: [loads] is what the screen reads.
	 */
	private val editedBodies = mutableMapOf<String, String>()

	/**
	 * Slots whose words have been handed to an attempt and should no longer sit
	 * in the box.
	 *
	 * The draft itself is untouched — it stays on disk under the same rules it
	 * always had, because it is still what recovery reads. This only answers a
	 * narrower question: *should the composer still be showing it?* Once Send
	 * has been tapped the answer is no, and it becomes no on the tap rather
	 * than when Hive gets back to us, which is the whole point.
	 */
	private val submitted = mutableStateMapOf<String, String>()

	/** The root whose full thread is on screen — for *this* account, or null. */
	val openThread: SnapReplyTarget?
		get() = openThreadState.takeIf { uiOwner == accountId() }

	/**
	 * The History event whose sheet is open *before* it has a Snap, or null.
	 *
	 * The other half of [openThread]. A row that has not been Snapped yet has no
	 * root to hang a conversation on, so there is nothing for [open] to take —
	 * but the sheet still has somewhere to go, because writing the first Snap
	 * happens in the same place as answering one. Account-scoped by the same
	 * `uiOwner` check as everything else here, so a switch cannot leave another
	 * account's row composing.
	 */
	val openEvent: String?
		get() = openEventState.takeIf { uiOwner == accountId() }

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

	/** The edit in progress — for *this* account, or null. */
	val editing: Editing?
		get() = editingState.takeIf { uiOwner == accountId() }

	fun threadKey(root: SnapReplyTarget): String = SnapDraftKey.of(account(), root.contentId)

	/** The composer/draft key for one parent comment, under this account. */
	fun replyKey(target: SnapReplyTarget): String =
		SnapDraftKey.of(account(), SnapReplyKey.slot(target))

	/** The signed-in account as the key scheme spells it. Never null. */
	private fun accountId(): String = account()?.takeIf { it.isNotBlank() } ?: ANONYMOUS

	/**
	 * The account a chain read should look for a vote under, or null.
	 *
	 * [ANONYMOUS] is a **key**, not a Hive account — nobody is signed in — so it
	 * must never be sent to the parser as a voter name to match against.
	 */
	private fun viewerOf(who: String): String? = who.takeIf { it != ANONYMOUS }

	// ── reading ────────────────────────────────────────────────────────

	/** What is known now. Null means nothing has been asked yet. */
	fun state(root: SnapReplyTarget): SnapThreadLoad? = loads[threadKey(root)]

	fun thread(root: SnapReplyTarget): SnapThread? =
		(state(root) as? SnapThreadLoad.Ready)?.thread

	/**
	 * The conversation as the screen should see it: what Hive returned, plus
	 * what this device has written and Hive has not shown back yet.
	 *
	 * The chain list goes first so [SnapThreadBuilder]'s de-duplication keeps
	 * the chain's copy of any reply that appears in both — a local row is only
	 * ever a stand-in for the seconds before the chain catches up, and it is
	 * dropped the moment the real one arrives. Rows whose parent is not in the
	 * conversation are discarded by the builder, so a pending reply belonging
	 * to some other thread cannot leak into this one.
	 */
	private fun merged(root: SnapReplyTarget, chain: List<SnapReply>): SnapThread {
		val who = account()?.takeIf { it.isNotBlank() }
			?: return SnapThreadBuilder.build(root.contentId, chain)
		val local = runCatching { publisher()?.stagedReplyRows(who).orEmpty() }
			.getOrDefault(emptyList())
		val gone = deletedRows[threadKey(root)].orEmpty()
		return SnapThreadBuilder.build(
			root.contentId,
			(withEdits(chain) + local).filterNot { it.contentId in gone },
		)
	}

	/**
	 * The chain rows with this account's just-saved edits laid over them.
	 *
	 * An edit is proven before it gets here, but `bridge.get_discussion` is
	 * served by hivemind, which can trail the chain by a few blocks — so the
	 * re-read that follows a save may still carry the old words. Drawing those
	 * would look like the edit had been undone. Each override retires itself
	 * the first time the chain shows the same text.
	 */
	private fun withEdits(chain: List<SnapReply>): List<SnapReply> {
		if (editedBodies.isEmpty()) return chain
		val prefix = "${accountId()}|"
		return chain.map { reply ->
			val id = prefix + reply.contentId
			val edited = editedBodies[id] ?: return@map reply
			if (edited == reply.body) {
				editedBodies.remove(id)
				reply
			} else {
				reply.copy(body = edited)
			}
		}
	}

	/**
	 * Redraw one conversation from what is already known — no network.
	 *
	 * Called the instant a reply is committed locally, which is what puts it on
	 * screen without a round trip.
	 *
	 * A conversation that has never been read successfully is left alone. There
	 * is nothing to place the reply *in* — asserting a one-reply thread over a
	 * failed read would replace an honest "couldn't load this" with a picture
	 * of a conversation that has one comment in it. The reply is durable
	 * either way, and appears as soon as the thread does.
	 */
	private fun redraw(root: SnapReplyTarget) {
		val key = threadKey(root)
		val chain = chainRows[key] ?: return
		val built = merged(root, chain)
		loads[key] = SnapThreadLoad.Ready(built)
		previews[key] = SnapThreadPreview.of(built, SnapMediaText::previewLine)
	}

	/** The bounded form the History card draws. Null until a thread is loaded. */
	fun preview(root: SnapReplyTarget): SnapThreadPreview.Preview? = previews[threadKey(root)]

	/**
	 * The conversation a comment sits in, **if this process has read it**.
	 *
	 * A pure lookup over answers already in memory: no network, no store, and
	 * nothing written. It exists so the Stage 6 bell can send an unfinished
	 * reply or an unsettled Like to the thread it belongs to rather than to a
	 * comment on its own.
	 *
	 * Null is an ordinary answer and the caller is expected to cope with it —
	 * [chainRows] is not persisted, so after a process death nothing here has
	 * been read yet. It is scoped to the signed-in account for the same reason
	 * everything else in this class is: a conversation loaded under another
	 * account is not visible, so it cannot be the answer either.
	 */
	fun rootOf(comment: SnapReplyTarget): SnapReplyTarget? {
		val prefix = "${accountId()}|"
		val id = comment.contentId
		chainRows.forEach { (key, rows) ->
			if (!key.startsWith(prefix)) return@forEach
			val rootId = key.removePrefix(prefix)
			// The comment may be the root itself, which no row in the list names
			// — the builder drops the root's own echoed row from the tree.
			if (rootId == id) return comment
			if (rows.none { it.contentId == id }) return@forEach
			val slash = rootId.indexOf('/')
			if (slash <= 0) return@forEach
			SnapReplyTarget.of(rootId.substring(0, slash), rootId.substring(slash + 1))
				?.let { return it }
		}
		return null
	}

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
		if (key in deletedRoots) return
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
					// The viewer is `who` — the account this load was started
					// under — and never a second read of the live account. The
					// replies come back stamped with that account's own vote for
					// the heart, so reading the account again here would let a
					// switch mid-flight stamp Alice's conversation with Bob's
					// votes. The guard below then discards the whole answer
					// anyway, which is the point: it must be discardable as one
					// account's, not a mixture of two.
					runCatching {
						reader()?.read(root.author, root.permlink, viewerOf(who))
					}.getOrNull()
				}
				// The account can change while a read is in flight. Alice's answer
				// must not land anywhere at all once Bob is signed in — not under
				// Bob's key, and not under Alice's either, because writing it would
				// make Bob's screen recompose on a conversation he cannot see.
				if (accountId() != who) return@launch
				// The root was proven deleted while this read was out.
				if (key in deletedRoots) return@launch
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
				chainRows[key] = replies
				// Keep proven deletions suppressed for this controller's lifetime:
				// a later read can come from a hivemind node that still lags.
				// A fresh, account-checked answer is the one moment this app can
				// learn that somebody replied to its user. Reported before the
				// tree is built, from the rows themselves, so what the bell sees
				// is what Hive said rather than what the builder kept.
				onChainRead(who, root, replies)
				val built = merged(root, replies)
				val preview = SnapThreadPreview.of(built, SnapMediaText::previewLine)
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
				if (again.remove(key) && accountId() == who) load(root, force = true)
			}
		}
	}

	/**
	 * Re-read a conversation because this app just wrote to it (Issue 40C):
	 * a reply, an edit or a proven deletion. Unlike a repeated open, this may
	 * not simply join a read already in flight — that read may predate the
	 * write — so it queues exactly one more read to run after it.
	 */
	internal fun reloadAfterWrite(root: SnapReplyTarget) {
		if (threadKey(root) in inFlight) again += threadKey(root) else load(root, force = true)
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
		if (uiOwner != accountId()) clearEdit()
		uiOwner = accountId()
		openThreadState = root
		openRootSnapState = rootSnap
		openEventState = null
		replyingToState = null
		load(root, force = true)
		account()?.takeIf { it.isNotBlank() }?.let { onRootOpened(it, root.contentId) }
	}

	/**
	 * Opens the sheet on a History row that has no Snap yet.
	 *
	 * Reads nothing and writes nothing: there is no conversation to fetch until
	 * a root exists. The sheet this opens carries the composer and nothing else.
	 */
	fun openComposer(eventId: String) {
		if (uiOwner != accountId()) clearEdit()
		uiOwner = accountId()
		openEventState = eventId
		openThreadState = null
		openRootSnapState = null
		replyingToState = null
	}

	/** Leaves every draft exactly as typed — closing a thread never discards. */
	fun close() {
		if (deletingState?.phase != DeletePhase.RUNNING) deletingState = null
		uiOwner = null
		openThreadState = null
		openRootSnapState = null
		openEventState = null
		replyingToState = null
		clearEdit()
	}

	// ── editing ────────────────────────────────────────────────────────

	/**
	 * Whether Edit may be offered on a comment by [author].
	 *
	 * Only the signed-in author, compared as the signing boundary compares
	 * accounts. [SnapEditor] refuses everyone else again before anything is
	 * signed; this only decides whether a control is drawn.
	 */
	fun canEdit(author: String): Boolean {
		val who = account()?.takeIf { it.isNotBlank() } ?: return false
		return editor(SnapEditKind.REPLY) != null && author.equals(who, ignoreCase = true)
	}

	/** Put one of the viewer's own comments into the bottom bar for editing. */
	fun startEdit(root: SnapReplyTarget, target: SnapReplyTarget, kind: SnapEditKind, current: String) {
		if (uiOwner != accountId() || isSavingEdit) return
		// Never alongside a deletion: an edit landing after a delete would
		// recreate the comment under the same permlink.
		if (deletingState != null) return
		if (!canEdit(target.author)) return
		editingState = Editing(root, target, kind, current)
		editText = current
		editStatus = null
		replyingToState = null
	}

	fun editDraft(text: String) {
		if (isSavingEdit) return
		editText = text
	}

	/** Leave edit mode. The comment keeps the words it has on chain. */
	fun cancelEdit() {
		if (isSavingEdit) return
		clearEdit()
	}

	private fun clearEdit() {
		editingState = null
		editText = ""
		editStatus = null
	}

	// ── deleting ───────────────────────────────────────────────────────

	/** A deletion being considered or carried out, from its Delete tap on. */
	data class Deleting(
		val root: SnapReplyTarget,
		/** The comment to delete: always the viewer's own. */
		val target: SnapReplyTarget,
		val kind: SnapEditKind,
		/** The words on screen, so the confirmation names the exact object. */
		val body: String,
		val phase: DeletePhase,
	)

	enum class DeletePhase {
		/** Reading the chain. Nothing can be confirmed yet. */
		CHECKING,

		/** The chain said yes just now; waiting for the user. */
		CONFIRM,

		/** Confirmed: re-checking, signing, sending and proving. */
		RUNNING,
	}

	private var deletingState by mutableStateOf<Deleting?>(null)

	/** The deletion in progress — for *this* account, or null. */
	val deleting: Deleting?
		get() = deletingState.takeIf { uiOwner == accountId() }

	/** Account-scoped `author/permlink` to why its last deletion did not happen. */
	private val deleteNotices = mutableStateMapOf<String, SnapPostStatus>()

	/**
	 * Thread key to replies proven deleted that hivemind may still return.
	 * Not snapshot state: [loads] is what the screen reads.
	 */
	private val deletedRows = mutableMapOf<String, MutableSet<String>>()

	/** What the last deletion attempt on [target] had to say, or null. */
	fun deleteNotice(target: SnapReplyTarget): SnapPostStatus? =
		deleteNotices["${accountId()}|${target.contentId}"]

	/**
	 * Whether Delete may be offered on a comment by [author]. Drawing only —
	 * [SnapDeleter] refuses everyone but the author again, against the chain.
	 */
	fun canDelete(author: String): Boolean {
		val who = account()?.takeIf { it.isNotBlank() } ?: return false
		return deleter(SnapEditKind.REPLY) != null && author.equals(who, ignoreCase = true)
	}

	/**
	 * Delete tapped: read the chain, and only then offer the confirmation.
	 *
	 * A delete already sent and unproven is not offered again — tapping
	 * settles that one by reading, so a second transaction is never signed.
	 */
	fun requestDelete(root: SnapReplyTarget, target: SnapReplyTarget, kind: SnapEditKind, body: String) {
		if (uiOwner != accountId() || isSavingEdit) return
		if (deletingState?.phase == DeletePhase.RUNNING) return
		if (!canDelete(target.author)) return
		val who = accountId()
		val del = deleter(kind) ?: return
		clearEdit()
		replyingToState = null
		deleteNotices.remove("$who|${target.contentId}")
		if (del.hasUnsettled(who, target)) {
			runDelete(Deleting(root, target, kind, body, DeletePhase.RUNNING), del, who)
			return
		}
		val request = Deleting(root, target, kind, body, DeletePhase.CHECKING)
		deletingState = request
		scope.launch {
			val check = withContext(io) {
				runCatching { del.check(who, target) }
					.getOrElse { SnapDeleteCheck.Unknown(SnapDeleter.UNREADABLE) }
			}
			// Cancelled, replaced, or another account now: this answer is nobody's.
			if (deletingState != request) return@launch
			if (accountId() != who) {
				deletingState = null
				return@launch
			}
			when (check) {
				is SnapDeleteCheck.Eligible -> deletingState = request.copy(phase = DeletePhase.CONFIRM)
				is SnapDeleteCheck.Blocked -> if (check.reason == SnapDeleter.ALREADY_GONE) {
					// Already gone: let the deleter prove that twice over and
					// retire the local copy. It sends nothing for an absent object.
					runDelete(request.copy(phase = DeletePhase.RUNNING), del, who)
				} else {
					deletingState = null
					deleteNotices["$who|${target.contentId}"] = SnapPostStatus.Failed(check.reason)
				}
				is SnapDeleteCheck.Unknown -> {
					deletingState = null
					deleteNotices["$who|${target.contentId}"] = SnapPostStatus.Failed(check.message)
				}
			}
		}
	}

	/** Cancel before confirming. Nothing has been signed, and nothing is sent. */
	fun cancelDelete() {
		if (deletingState?.phase == DeletePhase.RUNNING) return
		deletingState = null
	}

	/** Confirmed. The deleter reads eligibility again before it signs. */
	fun confirmDelete() {
		val request = deleting ?: return
		if (request.phase != DeletePhase.CONFIRM) return
		val del = deleter(request.kind) ?: return
		runDelete(request.copy(phase = DeletePhase.RUNNING), del, accountId())
	}

	private fun runDelete(request: Deleting, del: SnapDeleter, who: String) {
		if (who == ANONYMOUS) return
		deletingState = request
		val id = "$who|${request.target.contentId}"
		scope.launch {
			try {
				val outcome = withContext(io) {
					runCatching { del.delete(who, request.target) }.getOrElse {
						SnapDeleter.Outcome.Uncertain(
							"RustedWax couldn't confirm the deletion yet, so this is still shown. " +
								"Checking again won't send another.",
						)
					}
				}
				// Alice's deletion settling after Bob signed in draws nothing of Bob's.
				if (accountId() != who) return@launch
				when (outcome) {
					is SnapDeleter.Outcome.Deleted -> applyDelete(who, request)
					is SnapDeleter.Outcome.Blocked ->
						deleteNotices[id] = SnapPostStatus.Failed(outcome.reason)
					is SnapDeleter.Outcome.Failed ->
						deleteNotices[id] = SnapPostStatus.Failed(outcome.message)
					is SnapDeleter.Outcome.Uncertain ->
						deleteNotices[id] = SnapPostStatus.Uncertain(outcome.message)
				}
			} finally {
				if (deletingState == request) deletingState = null
			}
		}
	}

	/** Take a proven-deleted comment off every screen this controller draws. */
	private fun applyDelete(who: String, request: Deleting) {
		// A proven deletion changes what the chain allows elsewhere — a parent
		// that "has replies" may not any more — so earlier refusals are stale.
		deleteNotices.keys.filter { it.startsWith("$who|") }.forEach { deleteNotices.remove(it) }
		when (request.kind) {
			SnapEditKind.ROOT -> {
				val key = SnapDraftKey.of(who, request.target.contentId)
				deletedRoots += key
				again -= key
				onRootDeleted(who, request.target.contentId)
				loads.remove(key)
				chainRows.remove(key)
				previews.remove(key)
				if (openThreadState == request.target) close()
			}
			SnapEditKind.REPLY -> {
				deletedRows.getOrPut(threadKey(request.root)) { mutableSetOf() }
					.add(request.target.contentId)
				editedBodies.remove("$who|${request.target.contentId}")
				redraw(request.root)
				reloadAfterWrite(request.root)
			}
		}
	}

	/**
	 * Save the edit — the same `author/permlink`, signed again with new words.
	 *
	 * The comment on screen keeps its old words until [SnapEditor] proves the
	 * new ones are on chain. On any other answer the box stays open, still
	 * holding what the user typed, with the reason beside it.
	 */
	fun saveEdit() {
		val edit = editing ?: return
		if (isSavingEdit) return
		val text = editText
		if (SnapEditor.textProblem(text) != null) return
		// Unchanged words are not an edit, and not a write.
		if (text == edit.original) {
			clearEdit()
			return
		}
		val who = accountId()
		isSavingEdit = true
		editStatus = SnapPostStatus.Posting
		scope.launch {
			try {
				val outcome = withContext(io) {
					val ed = editor(edit.kind)
					when {
						who == ANONYMOUS ->
							SnapEditor.Outcome.Failed("Sign in to your Hive account to edit.")
						ed == null -> SnapEditor.Outcome.Failed("Editing isn't available right now.")
						else -> runCatching { ed.edit(who, edit.target, edit.kind, text) }
							.getOrElse {
								SnapEditor.Outcome.Uncertain(
									"RustedWax couldn't confirm your edit yet, so the previous " +
										"text is still shown. Saving again is safe.",
								)
							}
					}
				}
				// Alice's edit settling after Bob signed in writes nothing of Bob's.
				// Her bar keeps her words; the in-flight label is all that goes.
				if (accountId() != who) {
					editStatus = null
					return@launch
				}
				when (outcome) {
					is SnapEditor.Outcome.Edited -> {
						applyEdit(who, edit, outcome.userText)
						if (editingState == edit) clearEdit()
					}
					is SnapEditor.Outcome.Failed ->
						editStatus = SnapPostStatus.Failed(outcome.message)
					is SnapEditor.Outcome.Uncertain ->
						editStatus = SnapPostStatus.Uncertain(outcome.message)
				}
			} finally {
				isSavingEdit = false
			}
		}
	}

	/** Draw the proven words everywhere this comment already appears. */
	private fun applyEdit(who: String, edit: Editing, userText: String) {
		when (edit.kind) {
			SnapEditKind.ROOT -> {
				openRootSnapState?.takeIf { it.contentId == edit.target.contentId }?.let {
					openRootSnapState = it.copy(userText = userText)
				}
				onRootEdited(who, edit.target.contentId, userText)
			}
			SnapEditKind.REPLY -> {
				// The same cleaning every chain body gets, so the override
				// compares equal to the chain's copy once hivemind catches up.
				editedBodies["$who|${edit.target.contentId}"] = SnapReplyText.sanitize(userText)
				redraw(edit.root)
			}
		}
		// Ask Hive again, as after a reply. The override above holds the new
		// words on screen if the read is still behind.
		reloadAfterWrite(edit.root)
	}

	// ── reply drafts ───────────────────────────────────────────────────

	/**
	 * What the composer draws: empty from the moment Send is tapped.
	 *
	 * Distinct from [draft], which keeps answering with the words themselves so
	 * that recovery, validation at the publication boundary and the discard
	 * dialog all keep working on the real thing.
	 */
	fun composerText(key: String): String = if (key in submitted) "" else draft(key)

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
		// An echo of the words just sent is not typing.
		//
		// A soft keyboard holds a composing region over the text it last had,
		// and finishing that session posts one more update carrying it. Arriving
		// here while the attempt still owns the slot, it would un-submit the slot
		// and write the words back — which is exactly how a sent reply appeared
		// to linger in the box until the draft was discarded on confirmation.
		//
		// Bounded to the in-flight window and to *identical* text, so anything
		// the user actually types — including retyping the same message after
		// the attempt has finished — still takes the slot back.
		if (key in running && submitted[key] == text) return
		// Typing is the user taking the slot back.
		submitted.remove(key)
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
					// An intent that was never built is deleted with the draft
					// it was written from. Nothing was sent, so nothing can be
					// orphaned — and leaving it would leave a reply drawn in
					// the thread that the user has just thrown away.
					read.draft.intentId?.let { id ->
						if (!withContext(io) { abandonIntent(who, target, id) }) {
							if (accountId() != who) return@launch
							statuses[key] = SnapPostStatus.Failed(
								"RustedWax couldn't discard this reply, so it's still here.",
							)
							return@launch
						}
					}
					if (accountId() != who) return@launch
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
		submitted.remove(key)
		// A discarded reply may have been drawn from its record. Redrawing the
		// open conversation is what actually takes it off the screen; the chain
		// is not asked again, because nothing about the chain changed.
		openThreadState?.let(::redraw)
	}

	/** Whether an attempt, if this draft has made one, is still undecided. */
	/**
	 * Delete an unfinished reply record, or refuse.
	 *
	 * True also when there is nothing to delete — an ambiguous or confirmed row
	 * never reaches here, because [discard] checks that first. See
	 * [SnapPublisher.abandonIntendedReply] for why only an intent may go.
	 */
	private fun abandonIntent(who: String, target: SnapReplyTarget, intentId: String): Boolean {
		val pub = publisher() ?: return false
		return runCatching { pub.abandonIntendedReply(who, target, intentId) }.getOrDefault(false)
	}

	private fun isUnresolved(who: String, intentId: String?, target: SnapReplyTarget): Boolean {
		if (intentId == null) return false
		// No publisher means no way to ask, and "cannot tell" resolves towards
		// keeping the draft rather than towards destroying it.
		val pub = publisher() ?: return true
		return pub.isUnresolved(who, SnapReplyKey.of(target, intentId), PendingSnapKind.REPLY)
	}

	// ── writing ────────────────────────────────────────────────────────

	fun status(key: String): SnapPostStatus = statuses[key] ?: SnapPostStatus.Idle

	/**
	 * True while an attempt is running.
	 *
	 * Answered by ownership rather than by how the row reads: once a reply is
	 * drawn optimistically its status no longer says "busy", and a
	 * status-based guard would stop guarding exactly while the slow work runs.
	 */
	fun isBusy(key: String): Boolean = key in running

	/**
	 * Publish one reply, once.
	 *
	 * The same guarantees the root Snap path makes, for the same reason — a
	 * reply is just as permanent and just as public:
	 *
	 *  - the slot is claimed **synchronously**, so a second tap arriving before
	 *    the coroutine is scheduled — or after the reply is already drawn and
	 *    the broadcast is still running behind it — finds the claim and is
	 *    turned away rather than starting a second broadcast;
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
		// Ownership, synchronously. A second tap arriving before the coroutine
		// is scheduled — or while the reply is already drawn and the broadcast
		// is still running behind it — finds the claim here and is turned away.
		if (!running.add(key)) return
		val text = draft(key)
		// Checked here so the control is honest, and checked again at the
		// publication boundary, which is what actually enforces it.
		if (!SnapText.isValid(text)) {
			running.remove(key)
			return
		}
		// The box empties on the tap, not on Hive's acknowledgement. The words
		// are not gone — `text` holds them for this attempt and the draft still
		// holds them on disk — they have simply stopped being something the
		// user is still composing. Restored below on any outcome that leaves
		// them un-published.
		// Keyed to the exact words handed over, because an IME that has not
		// finished its session yet will echo them back — see [edit].
		submitted[key] = text
		val who = accountId()

		scope.launch {
			try {
				// ── Phase one: commit the reply locally. No network at all. ──
				//
				// A reply needs no container lookup — its parent is the comment
				// that was tapped — so the permlink, the body and the parent are
				// all known without asking Hive. One local write stands between
				// the tap and the reply being on screen.
				val staged = withContext(io) { intend(key, target, text) }
				if (accountId() != who) return@launch

				when (staged.outcome) {
					is SnapPublisher.Staged.Failed -> {
						if (staged.locked) corruptDrafts[key] = staged.outcome.message
						statuses[key] = SnapPostStatus.Failed(staged.outcome.message)
						return@launch
					}
					is SnapPublisher.Staged.Uncertain -> {
						statuses[key] = SnapPostStatus.Uncertain(staged.outcome.message)
						return@launch
					}
					is SnapPublisher.Staged.Published -> {
						statuses[key] = SnapPostStatus.Posted(staged.outcome.contentId)
						settle(root, key, who, target, staged.intentId)
						return@launch
					}
					is SnapPublisher.Staged.Ready -> Unit
				}

				// ── The durable boundary. The composer closes, the reply shows. ──
				//
				// The record is signed-for, committed and read back, so the reply
				// has an identity that survives a crash and a permlink that can
				// never be minted twice. That — not Hive's acknowledgement — is
				// what makes showing it honest.
				statuses[key] = SnapPostStatus.Optimistic(staged.outcome.contentId)
				if (replyingToState == key) replyingToState = null
				// The submitted marker deliberately stays: the box must remain
				// empty for the whole attempt, not just until staging succeeds.
				redraw(root)

				// ── Phase two: the ordinary publication path, behind the reply. ──
				//
				// `publishReply` is the same call it has always been. It resumes
				// from the record phase one committed, reusing that permlink and
				// that body, signs, persists the exact prepared transaction and
				// only then broadcasts.
				val attempt = withContext(io) { publish(key, target, text) }
				if (accountId() != who) return@launch
				if (attempt.locked) corruptDrafts[key] = attempt.outcome.messageOrEmpty()
				statuses[key] = replyStatus(who, target, attempt)
				if (attempt.outcome is SnapPublisher.Outcome.Published) {
					settle(root, key, who, target, attempt.intentId)
				} else {
					// Keep the reply drawn whatever happened: the record is
					// still on disk and the words on it are still the user's.
					redraw(root)
				}
			} finally {
				// Both markers belong to the attempt, and both are released
				// here — including on the paths that leave early.
				//
				// `submitted` only ever answers "do not draw this draft", so a
				// copy of it that outlives the attempt is a draft the user
				// cannot see. The account guards above are exactly that case:
				// they abandon the attempt mid-flight when the signed-in account
				// changes, and an earlier revision returned from them leaving
				// the marker behind. The key carries the account, so the entry
				// stranded there was the *first* account's — and on coming back
				// to it the composer kept drawing an empty box over a draft that
				// was still on disk, which the next keystroke would then
				// overwrite. Releasing here cannot strand one.
				//
				// Harmless on the published path: `settle` has already retired
				// the draft by now, so the box is empty because there is nothing
				// left to draw rather than because it is being hidden. Doing it
				// here rather than before `settle` also closes a flicker, where
				// the sent words came back for the length of one disk write.
				submitted.remove(key)
				running.remove(key)
			}
		}
	}

	/**
	 * What an attempt decided, without losing an intent it left behind.
	 *
	 * A failure is only a failure if nothing is on disk any more. When the
	 * record is still an intent — the chain head could not be read, the key
	 * could not be loaded — the reply has not been lost, it has been
	 * interrupted, and it keeps its place in the thread with an explicit way
	 * to finish it.
	 */
	private suspend fun replyStatus(
		who: String,
		target: SnapReplyTarget,
		attempt: Attempt,
	): SnapPostStatus {
		val stillIntended = attempt.outcome is SnapPublisher.Outcome.Failed &&
			attempt.intentId != null &&
			withContext(io) {
				publisher()?.isReplyInterrupted(who, target, attempt.intentId) == true
			}
		return if (stillIntended) {
			SnapPostStatus.Interrupted("${target.contentId}#${attempt.intentId}")
		} else {
			attempt.outcome.toStatus()
		}
	}

	/**
	 * Mint-or-reuse the intent, prove it is stored, then freeze the reply.
	 *
	 * The same ordering [publish] has always used, stopped one step earlier.
	 * An intent that is not durably on disk before anything slow begins is an
	 * attempt a crash can detach from its draft, and a detached draft is one
	 * whose next Send mints a second permlink for words that may already be on
	 * chain.
	 *
	 * An undecodable stored draft stops here, before an intent exists — see
	 * [SnapReplyDraftRead.Corrupt] for why there is no safe way to guess what
	 * it was.
	 */
	private fun intend(key: String, target: SnapReplyTarget, text: String): Staging {
		val read = drafts.read(key)
		if (read is SnapReplyDraftRead.Corrupt) {
			return Staging(
				SnapPublisher.Staged.Failed(corruptMessage(read.reason)),
				intentId = null,
				locked = true,
			)
		}
		val stored = (read as? SnapReplyDraftRead.Present)?.draft?.intentId
		var used: String? = null
		val who = account()
		val pub = publisher()
		val outcome = when {
			who.isNullOrBlank() ->
				SnapPublisher.Staged.Failed("Sign in to your Hive account to reply.")
			pub == null -> SnapPublisher.Staged.Failed("Replying isn't available right now.")
			key != SnapDraftKey.of(who, SnapReplyKey.slot(target)) ->
				SnapPublisher.Staged.Failed(
					"You've switched Hive accounts — this draft belongs to a different one.",
				)
			else -> {
				val intentId = stored ?: SnapReplyIntent.generate()
				if (stored == null && !drafts.beginIntent(key, intentId)) {
					SnapPublisher.Staged.Failed(
						"RustedWax couldn't save this reply safely, so it didn't send. " +
							"Your draft is still here.",
					)
				} else {
					used = intentId
					pub.intendReply(who, target, intentId, text)
				}
			}
		}
		return Staging(outcome, used)
	}

	/** What staging one reply achieved, and the intent it ran under. */
	private data class Staging(
		val outcome: SnapPublisher.Staged,
		val intentId: String?,
		val locked: Boolean = false,
	)

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
		if (!running.add(key)) return
		val who = accountId()
		statuses[key] = SnapPostStatus.Posting
		scope.launch {
			try {
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
						pub.reconcile(w, SnapReplyKey.of(target, intentId), PendingSnapKind.REPLY)
							?: SnapPublisher.Outcome.Uncertain(
								"RustedWax still can't tell whether this reply posted.",
							)
					}
					Attempt(outcome, checked)
				}
				if (accountId() != who) return@launch
				if (attempt.locked) corruptDrafts[key] = attempt.outcome.messageOrEmpty()
				statuses[key] = replyStatus(who, target, attempt)
				if (attempt.outcome is SnapPublisher.Outcome.Published) {
					settle(root, key, who, target, attempt.intentId)
				}
			} finally {
				running.remove(key)
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
		reloadAfterWrite(root)
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
			val body = publisher()?.publishedBody(who, SnapReplyKey.of(target, id), PendingSnapKind.REPLY)
				?: return@withContext SnapReplySettlement.Untouched
			drafts.settle(key, SnapReplyDraft(body, id))
		}
		if (accountId() != who) return

		when (result) {
			SnapReplySettlement.Retired -> {
				draftCache[key] = ""
				corruptDrafts.remove(key)
				if (replyingToState == key) replyingToState = null
				submitted.remove(key)
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

			// ── First, from disk alone: replies this device committed to and
			// never settled. ─────────────────────────────────────────────────
			//
			// Nothing here asks the network or writes anything. It matters most
			// for an intent that was never built: that reply reached no node, so
			// it is certainly not on Hive, and reconciliation below has nothing
			// to say about it. Without this the row would come back from
			// [SnapPublisher.stagedReplyRows] looking like any other reply — the
			// exact ghost a local-first thread has to avoid — instead of saying
			// it still needs a tap.
			val staged = withContext(io) { publisher()?.restoreStagedReplies(who).orEmpty() }
			if (accountId() != who) return@launch
			staged.forEach { row ->
				val slot = SnapReplyKey.slotOf(row.eventId) ?: return@forEach
				val key = SnapDraftKey.of(who, slot)
				statuses[key] = if (row.interrupted) {
					SnapPostStatus.Interrupted(row.contentId)
				} else {
					SnapPostStatus.Optimistic(row.contentId)
				}
			}

			// ── Then the reconciliation, unchanged. ──────────────────────────
			val resolved = withContext(io) {
				publisher()?.restore(who, PendingSnapKind.REPLY).orEmpty()
			}
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

	/**
	 * Every reply this device staged and could not settle, for the account
	 * signed in now.
	 *
	 * The reply half of [SnapPostController.needsAttention], and deliberately
	 * the same shape, tagged [SnapAttention.Kind.REPLY] so a reader can tell an
	 * unfinished comment from an unfinished Snap without parsing keys. A plain
	 * query over state that already exists: no store, no channel, no retry and
	 * no notification machinery.
	 */
	fun needsAttention(): List<SnapAttention> =
		statuses.entries
			.filter { it.key.startsWith("${accountId()}|") }
			.filter {
				it.value is SnapPostStatus.Uncertain ||
					it.value is SnapPostStatus.Failed ||
					it.value is SnapPostStatus.Interrupted
			}
			.map { SnapAttention(SnapAttention.Kind.REPLY, it.key, it.value) }
			.sortedBy { it.key }

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
