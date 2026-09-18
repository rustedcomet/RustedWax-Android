package com.rustedwax.app.snaps

import com.rustedwax.hive.HiveRpc

/** The canonical content Hive holds at one `author/permlink`. */
data class PostedSnapContent(
	val body: String,
	/** Chain creation time, or null when the field was missing or unreadable. */
	val createdAtEpochSec: Long?,
)

/**
 * Reads one published Snap back off the chain.
 *
 * Deliberately a separate port from [SnapHivePort] rather than another method on
 * it. [SnapHivePort] is the seam the *write* path is built on — signing,
 * broadcasting, reconciliation — and the one thing Stage 3 must be unable to do
 * is reach any of that. A reader that has no broadcaster and no key behind it
 * cannot publish a Snap however it is called.
 */
interface PostedSnapReader {
	/** Null when the chain has no such content, or could not be asked. */
	fun read(author: String, permlink: String): PostedSnapContent?
}

internal class HivePostedSnapReader(
	private val rpc: HiveRpc = HiveRpc(),
) : PostedSnapReader {

	override fun read(author: String, permlink: String): PostedSnapContent? = runCatching {
		val content = rpc.getContent(author, permlink) ?: return null
		PostedSnapContent(
			body = content.optString("body"),
			createdAtEpochSec = ChainTime.epochSec(content.optString("created")),
		)
	}.getOrNull()
}

/**
 * What the posted card shows, and where it comes from.
 *
 * Read-only by construction: it holds the pending-Snap store and a chain
 * *reader*, and nothing else. There is no publisher here, no key, no
 * broadcaster and no writer — recovering the display state of a Snap cannot
 * mint a permlink, cannot send a transaction and cannot change the state
 * machine's mind about an ambiguous write, because none of those things are
 * reachable from this object.
 *
 * The local record is the source of truth for *whether* a Snap is posted.
 * `CONFIRMED` is the only state that draws a posted card: the uncertain states
 * exist precisely because RustedWax does not know, and a card that rendered a
 * Snap for one of them would be asserting a publication the app has refused to
 * assert everywhere else.
 */
class PostedSnaps(
	private val store: PendingSnapStore,
	private val reader: PostedSnapReader,
) {

	/**
	 * The posted card built from local state alone. No network, no blocking.
	 *
	 * Null for every row that is absent, corrupt, failed or still uncertain —
	 * and also for a confirmed record whose stored body is not a v1 Snap body,
	 * because the alternative is showing the user a URL and three hashtags
	 * RustedWax wrote and promised not to show back.
	 */
	fun local(account: String, eventId: String): PostedSnap? {
		val record = (store.read(account, eventId) as? PendingSnapRead.Present)?.snap ?: return null
		if (!record.belongsTo(account, eventId)) return null
		if (record.state != PendingSnapState.CONFIRMED) return null
		val text = PostedSnapBody.userText(record.body) ?: return null
		return PostedSnap(
			author = record.author,
			permlink = record.permlink,
			userText = text,
			// The record's own creation time: the moment the transaction was
			// built, which is within seconds of the block that carried it. Good
			// enough to render `now` correctly, and replaced below by the chain's
			// own answer whenever the chain can be asked.
			createdAtEpochSec = record.createdAtEpochSec,
			fromChain = false,
		)
	}

	/**
	 * The same card, refreshed against Hive where Hive could be read.
	 *
	 * Falls back to [local] at every step, and returns it unchanged when the
	 * read fails, when the chain answers with nothing, or when what comes back
	 * is not recognisably a v1 Snap body. Nothing is written back to the store:
	 * the stored record is the write path's state machine, and a display refresh
	 * has no business editing it.
	 *
	 * The body is taken from the chain **only** when it ends in the frozen
	 * generated tail. Anything else is content this code did not produce and
	 * cannot subtract from safely, so the locally stored text — which it did
	 * produce — is what stays on screen.
	 */
	fun refreshed(account: String, eventId: String): PostedSnap? {
		val local = local(account, eventId) ?: return null
		val content = runCatching { reader.read(local.author, local.permlink) }.getOrNull()
			?: return local

		val chainText = PostedSnapBody.userText(content.body)
		return local.copy(
			userText = chainText ?: local.userText,
			createdAtEpochSec = content.createdAtEpochSec ?: local.createdAtEpochSec,
			fromChain = chainText != null || content.createdAtEpochSec != null,
		)
	}

	/**
	 * Whether this record really is the confirmed Snap for this account and row.
	 *
	 * Three identities have to agree, and the third is the one that matters:
	 *
	 *  - [PendingSnap.account] and [PendingSnap.eventId] must match what was
	 *    asked for. The store already files records by that pair, so a record
	 *    disagreeing with its own key is evidence something went wrong — the same
	 *    reading [PendingSnapIntegrity] takes on the write side;
	 *  - **[PendingSnap.author] must be the signed-in account.** A confirmed
	 *    record whose author is somebody else describes a comment this user did
	 *    not write. Rendering it would put another account's handle, avatar and
	 *    words on this user's History card as if they were theirs, and asking
	 *    Hive about it would send a read for a stranger's content on the strength
	 *    of a local record that is already known to be wrong.
	 *
	 * Refusal is total and cheap: [local] returns null, so nothing renders, and
	 * [refreshed] never reaches its chain read because it starts from [local].
	 * Nothing is repaired, cleared or rewritten — this is a display path, and the
	 * stored record stays exactly as found for the write path to reason about.
	 */
	private fun PendingSnap.belongsTo(account: String, eventId: String): Boolean =
		this.account == account && this.eventId == eventId && this.author == account
}
