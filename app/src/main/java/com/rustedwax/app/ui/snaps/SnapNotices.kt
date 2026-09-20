package com.rustedwax.app.ui.snaps

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import com.rustedwax.app.snaps.HiveAccountName
import com.rustedwax.app.snaps.SnapReplies
import com.rustedwax.app.snaps.SnapReply
import com.rustedwax.app.snaps.SnapReplyTarget
import com.rustedwax.app.snaps.SnapThreadPreview
import org.json.JSONObject

/**
 * Somebody else answered something this account wrote.
 *
 * The one genuinely new fact in Stage 6. Everything else the bell shows —
 * an unfinished Snap, an unfinished reply, an unsettled Like — is already held
 * by the controller that owns that state machine and is read from it live; this
 * is the only thing no existing object knows, because a reply written by
 * another person on another device leaves no trace anywhere in this app until a
 * thread read happens to contain it.
 *
 * Deliberately not a copy of the reply. What is kept is an identity, a place to
 * go, and a clamped line of text for the row — enough to draw a notification
 * and open the right conversation, and nothing that would make this a second,
 * staler answer to "what does this thread say". The thread itself stays
 * Hive-authoritative and is re-read when it is opened.
 */
data class SnapNotice(
	/** The comment that was written. Its id is also the de-duplication key. */
	val author: String,
	val permlink: String,
	/** The conversation it belongs to, so tapping the row can open it. */
	val rootAuthor: String,
	val rootPermlink: String,
	/** Clamped to [SnapThreadPreview.MAX_PREVIEW_CHARS]; never the whole body. */
	val text: String,
	/** When the comment was written, or null when the chain field was unreadable. */
	val createdAtEpochSec: Long?,
	/** False until the user has actually been taken to it. */
	val read: Boolean = false,
) {
	val contentId: String get() = "$author/$permlink"

	val rootId: String get() = "$rootAuthor/$rootPermlink"

	/** The conversation to open, or null when the stored root is not usable. */
	fun root(): SnapReplyTarget? = SnapReplyTarget.of(rootAuthor, rootPermlink)
}

/**
 * Which replies in a conversation are news for this account, and which are not.
 *
 * Pure, and the only place the "who gets notified" rule exists. Two conditions,
 * both structural:
 *
 *  - **the parent is this account's.** A reply notifies whoever it answers. That
 *    covers a reply to the user's root Snap and a reply to any of the user's own
 *    replies, with one rule rather than two, because `parent_author` says which
 *    it is and the app does not have to work it out;
 *  - **the author is not this account.** Your own reply is not news. This is
 *    checked here rather than at the call site so there is no path — a
 *    reconciliation, a restored optimistic row, a thread reloaded after a send —
 *    that can route around it.
 *
 * What this function can never do is notify for a Like, and that is worth
 * stating as a property of its type rather than as a rule it follows: it is
 * handed [SnapReply] rows and nothing else. A vote is not a reply, it arrives
 * on a different field of a different object, and there is no argument shape
 * here that could carry one in.
 */
object SnapNoticeScan {

	/**
	 * The incoming replies in [replies], newest last.
	 *
	 * [viewer] is the signed-in account. Blank means nobody is signed in, which
	 * is not the same as "nobody matched": with no account there is no such
	 * thing as a reply *to you*, so the answer is empty rather than everything.
	 */
	fun incoming(
		viewer: String?,
		root: SnapReplyTarget,
		replies: List<SnapReply>,
	): List<SnapNotice> {
		val who = viewer?.takeIf { it.isNotBlank() } ?: return emptyList()
		return replies
			.asSequence()
			.filter { it.parentAuthor == who }
			.filter { it.author != who }
			// The same validation the read path applies to anything that becomes
			// an identity. These strings end up as a store key, an avatar request
			// and the root of a thread load, and a row that has come this far
			// through a damaged response is not a reason to relax that.
			.filter { HiveAccountName.isValid(it.author) && SnapReplies.isPermlink(it.permlink) }
			.map {
				SnapNotice(
					author = it.author,
					permlink = it.permlink,
					rootAuthor = root.author,
					rootPermlink = root.permlink,
					text = SnapThreadPreview.clampText(it.body),
					createdAtEpochSec = it.createdAtEpochSec,
				)
			}
			// Oldest first, ties broken by id, so two devices reading the same
			// conversation put the same rows in the same order. An unreadable
			// timestamp sorts oldest — it never displaces a dated reply from the
			// top of the bell, which is the position that makes a claim.
			.sortedWith(compareBy({ it.createdAtEpochSec ?: Long.MIN_VALUE }, { it.contentId }))
			.toList()
	}
}

/**
 * What the bell remembers between one look at a conversation and the next.
 *
 * Two different facts live here, under two key namespaces, and they are both
 * needed for the bell to be honest:
 *
 *  - **a notice**, unread or read. A read notice is kept rather than deleted,
 *    because it is also the de-duplication record: the row is gone from the
 *    bell, and the reply it names can never come back as news;
 *  - **a seed mark** per conversation. The first time an account reads a given
 *    thread, whatever is already in it is not news — it is history, and very
 *    possibly history this user has already read in another Hive client. A
 *    fresh install, or an account signing in on a device that has been quiet
 *    for a month, must not open to a wall of notifications about replies from
 *    weeks ago. So the first read of each root seeds its existing replies as
 *    already seen and records that it has done so; every read after that
 *    notifies normally.
 *
 * Account-scoped throughout. Nothing secret is kept — Hive comments are public,
 * and only authors, permlinks and already-clamped visible text are written.
 */
internal interface SnapNoticeStore {
	/** Every notice for this account, read and unread. */
	fun all(account: String): List<SnapNotice>

	/**
	 * Record a finished read, and answer **what was genuinely new**.
	 *
	 * [notices] is what [SnapNoticeScan] found. On a conversation that has not
	 * been seeded, they are all written **read** and the seed mark is set; after
	 * that they are written unread — and a notice already on file is never
	 * rewritten, which is what makes the same reply surface exactly once however
	 * many times the thread is re-read.
	 *
	 * The return value is the newly-**unread** rows, and it is the only thing
	 * that decides whether the system shade is disturbed. That is deliberate: it
	 * puts persistence and de-duplication *before* the announcement rather than
	 * beside it, so a notification cannot exist for a reply the store does not
	 * already hold, and cannot be raised twice for one reply by any route. A
	 * seeding read returns an empty list, however many old replies it wrote.
	 *
	 * **Null means the durable write did not happen**, and is a different answer
	 * from an empty list. A notification is a promise that the record behind it
	 * survives, so a failed write must not produce one — and it must not produce
	 * a bell row either, because a row the disk does not have would be answered
	 * by the user, forgotten on restart, and then announced all over again. Null
	 * says "nothing was recorded, nothing is known, try again on the next read",
	 * and nothing here retries on its own.
	 */
	fun record(
		account: String,
		rootId: String,
		notices: List<SnapNotice>,
		atEpochSec: Long,
	): List<SnapNotice>?

	/** Mark one notice read. Absent ids are ignored. */
	fun markRead(account: String, contentId: String)

	/** Mark every notice under one conversation read. */
	fun markThreadRead(account: String, rootId: String)
}

/**
 * The storage format and its rules, with no Android in sight.
 *
 * Split out for the reason [SnapThreadPreviewCodec] is: the part that decides
 * what may be trusted off disk, and what may be thrown away, is worth testing
 * on its own — the preference file is only where the bytes happen to live.
 */
internal object SnapNoticeCodec {

	private const val NOTICE = "n"
	private const val SEED = "s"

	fun noticeKey(account: String, contentId: String): String = "$NOTICE|$account|$contentId"

	fun seedKey(account: String, rootId: String): String = "$SEED|$account|$rootId"

	fun isNoticeKey(key: String): Boolean = key.startsWith("$NOTICE|")

	/** The account a notice key belongs to, or null when it is not one. */
	fun accountOf(key: String): String? {
		if (!isNoticeKey(key)) return null
		val rest = key.removePrefix("$NOTICE|")
		val bar = rest.indexOf('|')
		return if (bar > 0) rest.substring(0, bar) else null
	}

	fun encode(notice: SnapNotice, at: Long): String = JSONObject()
		.put("at", at)
		.put("author", notice.author)
		.put("permlink", notice.permlink)
		.put("rootAuthor", notice.rootAuthor)
		.put("rootPermlink", notice.rootPermlink)
		.put("text", notice.text)
		.put("created", notice.createdAtEpochSec ?: JSONObject.NULL)
		.put("read", notice.read)
		.toString()

	fun storedAt(raw: String): Long =
		runCatching { JSONObject(raw).optLong("at", 0L) }.getOrDefault(0L)

	fun isRead(raw: String): Boolean =
		runCatching { JSONObject(raw).optBoolean("read", false) }.getOrDefault(false)

	/**
	 * Rebuild a notice from stored bytes, **re-validating every field**.
	 *
	 * Nothing on disk is taken on trust, even though this app wrote it. These
	 * values become an account handle, an avatar request, visible text and the
	 * root of a chain read, so the authors must still be Hive account names, the
	 * permlinks must still be permlinks, and the text is re-clamped rather than
	 * measured. A record that fails any of it is dropped: a missing row costs
	 * one notification, whereas a row drawn from bytes this code does not
	 * understand would be asserting somebody's words.
	 */
	fun decode(raw: String): SnapNotice? = runCatching {
		// A collapsed row is not damaged data and is not worth parsing: it is a
		// deliberate "seen already" marker with nothing to draw.
		if (isTombstone(raw)) return@runCatching null
		val o = JSONObject(raw)
		val author = (o.opt("author") as? String)
			?.takeIf { HiveAccountName.isValid(it) } ?: return@runCatching null
		val permlink = (o.opt("permlink") as? String)
			?.takeIf { SnapReplies.isPermlink(it) } ?: return@runCatching null
		val rootAuthor = (o.opt("rootAuthor") as? String)
			?.takeIf { HiveAccountName.isValid(it) } ?: return@runCatching null
		val rootPermlink = (o.opt("rootPermlink") as? String)
			?.takeIf { SnapReplies.isPermlink(it) } ?: return@runCatching null
		val text = (o.opt("text") as? String) ?: return@runCatching null
		SnapNotice(
			author = author,
			permlink = permlink,
			rootAuthor = rootAuthor,
			rootPermlink = rootPermlink,
			text = SnapThreadPreview.clampText(text),
			createdAtEpochSec = (o.opt("created") as? Number)?.toLong(),
			read = o.optBoolean("read", false),
		)
	}.getOrNull()

	/**
	 * What a notice key holds once its display data has been pruned away.
	 *
	 * Deliberately not JSON, and deliberately one byte: it is not a record, it
	 * is the statement *"this reply has already been seen"*. [decode] refuses
	 * it, so it never reaches a screen; [isNoticeKey] still matches it and
	 * `contains` still answers true, which is the only thing de-duplication ever
	 * asks.
	 */
	const val TOMBSTONE = "-"

	fun isTombstone(raw: String): Boolean = raw == TOMBSTONE

	/**
	 * Notice keys whose **display data** may be dropped, oldest and answered
	 * first.
	 *
	 * ## Why nothing is ever removed
	 *
	 * An earlier revision deleted these keys outright, and that was a real bug
	 * rather than a tidy-up: the stored row is not only what the bell draws, it
	 * is also the *only* record that this reply has ever been seen. Deleting one
	 * put the reply back in the same position as a reply that had never existed,
	 * so the next ordinary re-read of that conversation — which happens whenever
	 * the card is on screen — classified a months-old comment as new and
	 * announced it in the system shade. Pruning a cache is housekeeping; pruning
	 * an identity is amnesia, and the two had been the same entry.
	 *
	 * So the row is **collapsed to [TOMBSTONE], never removed**. The bytes that
	 * cost something — the body text, the root, the timestamps — go; the key
	 * stays, and with it the fact that this reply is old news. What remains is
	 * about seventy bytes per reply ever addressed to this account, which is the
	 * price of the invariant that ordinary pruning can never resurrect a reply.
	 *
	 * ## What the order still decides
	 *
	 * Collapsing is no longer dangerous, but it is not free either: a collapsed
	 * row disappears from the bell. Answered notices go first, oldest to newest,
	 * and an unread one is only collapsed when there is nothing answered left —
	 * so the rows the user has not seen are the last to lose their text.
	 *
	 * Already-collapsed keys are not counted and not returned: they hold no
	 * display data to drop, and counting them would make the cap shrink towards
	 * zero as the tombstones accumulated.
	 */
	fun collapsibleKeys(entries: Map<String, String>, max: Int): List<String> {
		val notices = entries.filterKeys(::isNoticeKey).filterValues { !isTombstone(it) }
		if (notices.size <= max) return emptyList()
		return notices.entries
			.sortedWith(
				compareBy(
					{ if (isRead(it.value)) 0 else 1 },
					{ storedAt(it.value) },
					{ it.key },
				),
			)
			.take(notices.size - max)
			.map { it.key }
	}
}

internal class SharedPreferencesSnapNoticeStore internal constructor(
	private val prefs: SharedPreferences,
	private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
) : SnapNoticeStore {

	/**
	 * The way the app builds it.
	 *
	 * The primary constructor takes the preferences themselves so the failure
	 * this class exists to survive — a `commit()` that mutates memory and then
	 * cannot reach the disk — is reachable from a JVM test. `SharedPreferences`
	 * is an interface; a `Context` is not, and a store that can only be built
	 * from one can only be tested on a device.
	 */
	constructor(
		context: Context,
		nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
	) : this(context.getSharedPreferences(NOTICES, Context.MODE_PRIVATE), nowEpochSec)

	override fun all(account: String): List<SnapNotice> =
		prefs.all.entries
			.filter { SnapNoticeCodec.accountOf(it.key) == account }
			.mapNotNull { entry ->
				(entry.value as? String)?.let { SnapNoticeCodec.decode(it) }
			}

	@Synchronized
	override fun record(
		account: String,
		rootId: String,
		notices: List<SnapNotice>,
		atEpochSec: Long,
	): List<SnapNotice>? {
		val seedKey = SnapNoticeCodec.seedKey(account, rootId)
		val seeding = !prefs.contains(seedKey)
		// Never rewritten. This is the de-duplication rule itself: a reply
		// already on file keeps whatever read state it has, so re-reading a
		// conversation — which happens every time it is opened — cannot make an
		// answered notification unread again.
		val unseen = notices.filterNot {
			prefs.contains(SnapNoticeCodec.noticeKey(account, it.contentId))
		}

		// The common case, and it must cost nothing. History reads the
		// conversation under every posted Snap on screen, and the overwhelming
		// majority of those reads find a thread this account already knows
		// about — so a conversation that is seeded and has nothing new in it
		// does not open an editor, does not touch the disk, and in particular
		// does not reach the synchronous commit below on the main thread.
		if (!seeding && unseen.isEmpty()) return emptyList()

		// Everything this write will touch, as it stands *now*.
		//
		// Captured before the editor runs, because `commit()` is memory-first:
		// it applies the whole batch to the in-process map and only then tries
		// the disk, so by the time it answers false the map already claims the
		// write happened. Reading `false` and returning is therefore not enough
		// on its own — this process would go on believing the reply had been
		// seen, `unseen` would filter it out of every later read, and a genuinely
		// new reply would stay silent until the process died. Suppressing the
		// notification without undoing the memory turns a failed write into a
		// lost one.
		//
		// `prefs.all` is a snapshot copy, so this is the state before the batch
		// and stays that way. Absent keys map to null, which is a value here and
		// not a gap: for this write every touched key *is* absent, since
		// `seeding` and `unseen` are both defined by `contains` answering false.
		// The restore still handles real prior values rather than assuming that,
		// because the assumption is a property of today's write set and not of
		// the mechanism.
		val snapshot = prefs.all
		val touched = buildList {
			if (seeding) add(seedKey)
			unseen.forEach { add(SnapNoticeCodec.noticeKey(account, it.contentId)) }
		}
		val prior: Map<String, Any?> = touched.associateWith { snapshot[it] }

		val edit = prefs.edit()
		if (seeding) edit.putLong(seedKey, atEpochSec)
		val fresh = mutableListOf<SnapNotice>()
		unseen.forEach { notice ->
			val written = notice.copy(read = seeding)
			edit.putString(
				SnapNoticeCodec.noticeKey(account, notice.contentId),
				SnapNoticeCodec.encode(written, atEpochSec),
			)
			if (!seeding) fresh += written
		}
		// `commit()`, not `apply()`, and the one place in the Snap UI stack that
		// needs it. The caller announces `fresh` in the system shade the moment
		// this returns, and a notification is a promise that the record behind it
		// survives — an announcement whose bytes were still in flight when the
		// process died would be a reply the user was told about and the bell has
		// never heard of. It is a synchronous write, which is why the guard above
		// keeps every uneventful read away from it: what reaches here is a
		// conversation's first read or a genuinely new reply, and both are rare.
		//
		// **And the answer is read.** `commit` returns false on a disk that would
		// not take the write, and an earlier revision threw that away and
		// announced `fresh` regardless — which is the same promise broken from
		// the other end: a notification, and no record anywhere behind it. Null
		// instead, so the caller shows nothing, announces nothing, and simply
		// does not know yet. Nothing is retried here; the next ordinary read of
		// the conversation finds the same replies still unseen and tries once
		// more, which is the only retry this design has and the only one it
		// needs.
		if (!edit.commit()) {
			restore(prior)
			return null
		}
		prune()
		return fresh
	}

	/**
	 * Put the in-process map back exactly as it was before a failed write.
	 *
	 * Exact values, not a blanket `remove`. Every key this write touches happens
	 * to have been absent, so removal would be correct today — and would quietly
	 * stop being correct the first time the write set grows to include a key
	 * that already had a value, destroying a read flag or a tombstone in the
	 * name of tidying up after a failure. Restoring what was there cannot make
	 * that mistake.
	 *
	 * `apply()`, not `commit()`. The disk never took the failed batch, so the
	 * file is already correct and there is nothing to repair there; what is
	 * wrong is memory, and `apply()` fixes that **synchronously** — it applies
	 * the batch to the map before it queues anything — which is the whole
	 * property this needs. Blocking the main thread a second time on a disk that
	 * has just refused a write would buy nothing. If the queued write also
	 * fails, the map is still right, and the map is what decides whether the
	 * next read retries.
	 *
	 * `internal` rather than private so the exactness can be tested directly.
	 * No write set this store builds today contains a key that already had a
	 * value — `seeding` and `unseen` are both defined by `contains` answering
	 * false — so a blanket `remove` would pass every test that goes through
	 * [record], and the requirement would be enforced by nothing but this
	 * comment. Calling it directly is the honest way to hold it.
	 */
	internal fun restore(prior: Map<String, Any?>) {
		if (prior.isEmpty()) return
		val edit = prefs.edit()
		prior.forEach { (key, value) ->
			when (value) {
				null -> edit.remove(key)
				is String -> edit.putString(key, value)
				is Long -> edit.putLong(key, value)
				// Nothing else is ever written to this file. A value of another
				// type is a file this code does not understand, and the safe
				// reading of an unknown prior value is to drop the key rather
				// than to write a guess back over it.
				else -> edit.remove(key)
			}
		}
		edit.apply()
	}

	@Synchronized
	override fun markRead(account: String, contentId: String) {
		val key = SnapNoticeCodec.noticeKey(account, contentId)
		val raw = prefs.getString(key, null) ?: return
		val notice = SnapNoticeCodec.decode(raw) ?: return
		prefs.edit()
			.putString(
				key,
				SnapNoticeCodec.encode(notice.copy(read = true), SnapNoticeCodec.storedAt(raw)),
			)
			.apply()
	}

	@Synchronized
	override fun markThreadRead(account: String, rootId: String) {
		val edit = prefs.edit()
		prefs.all.forEach { (key, value) ->
			if (SnapNoticeCodec.accountOf(key) != account) return@forEach
			val raw = value as? String ?: return@forEach
			val notice = SnapNoticeCodec.decode(raw) ?: return@forEach
			if (notice.rootId != rootId || notice.read) return@forEach
			edit.putString(
				key,
				SnapNoticeCodec.encode(notice.copy(read = true), SnapNoticeCodec.storedAt(raw)),
			)
		}
		edit.apply()
	}

	/**
	 * Drop the display data of the oldest answered rows, keeping their identity.
	 *
	 * `apply()`, not `commit()`: losing this to a crash costs nothing at all.
	 * The rows it would have collapsed are still perfectly valid records, and
	 * the next write prunes again — whereas the thing that must never be lost,
	 * the fact that a reply has been seen, is exactly what this leaves behind.
	 */
	private fun prune() {
		@Suppress("UNCHECKED_CAST")
		val strings = prefs.all.filterValues { it is String } as Map<String, String>
		val collapsible = SnapNoticeCodec.collapsibleKeys(strings, MAX_DISPLAYED_NOTICES)
		if (collapsible.isEmpty()) return
		val edit = prefs.edit()
		collapsible.forEach { edit.putString(it, SnapNoticeCodec.TOMBSTONE) }
		edit.apply()
	}

	companion object {
		const val NOTICES = "rustedwax_snap_notices"

		/**
		 * How many notices keep their **drawable** contents.
		 *
		 * Comfortably more than a bell should ever be asked to hold. Past this,
		 * rows are collapsed to a tombstone rather than deleted — see
		 * [SnapNoticeCodec.collapsibleKeys] — so this bounds what the store
		 * draws, not what it remembers having seen.
		 */
		const val MAX_DISPLAYED_NOTICES = 200
	}
}

/**
 * The bell's own state: incoming replies, and which of them have been answered
 * for.
 *
 * Deliberately narrow. It does **not** hold, mirror or re-derive the state of an
 * unfinished Snap, an unfinished reply or an unsettled Like — those live in
 * [SnapPostController], [SnapThreadController] and [SnapLikeController], which
 * already own those state machines, and are read from them live at the moment
 * the bell draws. A second copy here is exactly the thing that would eventually
 * disagree with the card the user is looking at.
 *
 * It also cannot write to Hive, and that is structural rather than a rule it
 * observes: there is no publisher, no port, no key and no broadcaster behind
 * this class. Nothing it can be asked to do has a transaction at the end of it.
 *
 * Account-scoped, and re-read rather than migrated when the account changes —
 * the same rule the rest of the Snap stack follows, for the same reason: one
 * user's conversations must never be visible under another user's session.
 */
@Stable
class SnapNoticeController internal constructor(
	private val store: SnapNoticeStore,
	private val account: () -> String?,
	/**
	 * Where a genuinely new incoming reply is announced.
	 *
	 * Only ever handed rows [SnapNoticeStore.record] has just written unread, so
	 * every rule about who is notified — not your own words, not a Like, not a
	 * conversation's first read, not a reply already on file — is enforced
	 * before this is reached rather than inside it. There is no second copy of
	 * those rules behind the shade to drift out of step with the bell.
	 */
	private val notifier: SnapNoticeNotifier,
	private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {

	/** Account-scoped `contentId` to the notice, as Compose reads it. */
	private val notices = mutableStateMapOf<String, SnapNotice>()

	/** The account [notices] was filled for, so a switch reloads rather than leaks. */
	private var loadedFor: String? = null

	private fun accountId(): String? = account()?.takeIf { it.isNotBlank() }

	private fun key(account: String, contentId: String) = "$account|$contentId"

	/**
	 * Fill the map from disk for whoever is signed in now.
	 *
	 * Called from a `LaunchedEffect` keyed on the account, so it runs on the way
	 * in and again on every switch. The map is cleared first: a switch must not
	 * leave the previous account's rows behind for even one frame.
	 */
	fun load() {
		val who = accountId()
		notices.clear()
		loadedFor = who
		if (who == null) return
		store.all(who).forEach { notices[key(who, it.contentId)] = it }
	}

	/**
	 * Fold a finished chain read into the bell.
	 *
	 * [account] is the account the read was *started* under and is checked
	 * against the one signed in now, for the reason every asynchronous
	 * completion in this stack is: an answer fetched as Alice must not land
	 * anywhere at all once Bob is signed in.
	 */
	fun record(account: String, root: SnapReplyTarget, replies: List<SnapReply>) {
		val who = accountId() ?: return
		if (who != account) return
		val found = SnapNoticeScan.incoming(who, root, replies)
		// Persisted and de-duplicated first; announced second. `fresh` is what
		// the store decided was actually new, which is a narrower thing than
		// what the scan found.
		//
		// Null is the store saying the durable write did not happen. Nothing is
		// mirrored into the bell and nothing is announced: a row on screen that
		// the disk does not have would be answered by the user, lost on restart,
		// and then announced as new all over again. Showing nothing is the
		// honest reading of "not recorded", and the next read tries again.
		val fresh = store.record(who, root.contentId, found, nowEpochSec()) ?: return
		// **Only what the store recorded as new.** An earlier revision mirrored
		// everything the scan found, filling in a read flag of its own, and that
		// was a second opinion about which replies are news — one that disagreed
		// with the store in exactly the case the store exists to get right. A
		// reply whose row had been pruned to a tombstone is still, correctly,
		// not new; the scan cannot tell, so it said unread, and the answered
		// reply reappeared in the bell. Everything that is not new arrives
		// through [load] instead, which reads the store rather than guessing.
		fresh.forEach { notices[key(who, it.contentId)] = it }
		// Re-checked after the write, for the reason every completion in this
		// stack re-checks: the store read and the announcement are two moments,
		// and a switch between them must not put Alice's reply in Bob's shade.
		if (accountId() != who) return
		fresh.forEach { notifier.post(who, it) }
	}

	/** Unread incoming replies for this account, newest first. */
	fun unread(): List<SnapNotice> {
		val who = accountId() ?: return emptyList()
		val prefix = "$who|"
		return notices.entries
			.filter { it.key.startsWith(prefix) && !it.value.read }
			.map { it.value }
			.sortedWith(
				compareByDescending<SnapNotice> { it.createdAtEpochSec ?: Long.MIN_VALUE }
					.thenBy { it.contentId },
			)
	}

	/** One row has been acted on. */
	fun markRead(notice: SnapNotice) {
		val who = accountId() ?: return
		val k = key(who, notice.contentId)
		val current = notices[k] ?: return
		if (current.read) return
		notices[k] = current.copy(read = true)
		store.markRead(who, notice.contentId)
		// The shade is a copy of the bell, not a second inbox: a reply the user
		// has reached should not still be sitting in the status bar.
		notifier.cancel(who, notice.contentId)
	}

	/**
	 * Everything under one conversation has been seen.
	 *
	 * Called when the thread is opened, whatever opened it. Reaching a
	 * conversation is what answers a notification about it, so a reply the user
	 * has now actually looked at must not still be sitting in the bell — and
	 * that has to be true whether they arrived from the bell, from the card's
	 * Thread button, or from the preview strip.
	 */
	fun markThreadRead(root: SnapReplyTarget) {
		val who = accountId() ?: return
		val prefix = "$who|"
		val rootId = root.contentId
		val answered = notices.entries
			.filter { it.key.startsWith(prefix) && !it.value.read && it.value.rootId == rootId }
			.map { it.key to it.value }
		answered.forEach { (k, v) -> notices[k] = v.copy(read = true) }
		store.markThreadRead(who, rootId)
		answered.forEach { (_, v) -> notifier.cancel(who, v.contentId) }
	}
}
