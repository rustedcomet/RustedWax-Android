package com.rustedwax.app.snaps

import com.rustedwax.hive.HiveRpc
import org.json.JSONObject

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

	override fun read(author: String, permlink: String): PostedSnapContent? =
		runCatching { contentOf(author, permlink, rpc.getContent(author, permlink)) }.getOrNull()

	internal companion object {
		/**
		 * The exact object that was asked for, or nothing: an answer about any
		 * other author/permlink must never be drawn on this Snap's card.
		 */
		fun contentOf(author: String, permlink: String, content: JSONObject?): PostedSnapContent? {
			content ?: return null
			if (!content.optString("author").equals(author, ignoreCase = true) ||
				content.optString("permlink") != permlink
			) {
				return null
			}
			return PostedSnapContent(
				body = content.optString("body"),
				createdAtEpochSec = ChainTime.epochSec(content.optString("created")),
			)
		}
	}
}

/** A read and its cache candidate, persisted only after the caller accepts it. */
internal data class PostedSnapRefresh(
	val snap: PostedSnap,
	val cacheEntry: PostedSnapCacheEntry? = null,
)

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
	/**
	 * The last successfully read chain body per Snap (Issue 40C), so a reopened
	 * card starts from the newest known chain state rather than the words this
	 * device originally wrote. Null keeps nothing between reads.
	 */
	private val cache: PostedSnapCache? = null,
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
		return local(account, eventId, record)
	}

	private fun local(account: String, eventId: String, record: PendingSnap): PostedSnap? {
		if (!record.belongsTo(account, eventId)) return null
		// A History card is a root Snap's card. A reply record here would be a
		// comment drawn under a video it was never about — and a record whose
		// kind could not be read never arrives at all, because the store
		// answers Corrupt for it.
		if (record.kind != PendingSnapKind.ROOT) return null
		if (record.state != PendingSnapState.CONFIRMED) return null
		val written = PostedSnapBody.userText(record.body) ?: return null
		// The last chain body this exact Snap was read with, when it was read
		// against the record as it stands now — see [PostedSnapCacheEntry].
		val text = cache?.read(account, "${record.author}/${record.permlink}")
			?.takeIf { it.recordBody == record.body }
			?.let { reconcile(it.chainBody, record.body) }
			?: written
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
	 * The card for a Snap that is **durably staged but not yet proven**.
	 *
	 * Deliberately a second, separately-named accessor rather than a loosening
	 * of [local]. [local] means "this is on Hive", and a great deal of the Snap
	 * design rests on it meaning only that — so it keeps meaning only that, and
	 * the optimistic card asks a different question with a different name.
	 *
	 * What this will answer for is narrow: a record that has been signed and
	 * committed to disk for this account and row, and whose outcome is not yet
	 * decided. That is exactly the set for which the app has a permanent
	 * identity, an exact body and a minted permlink it will never mint again —
	 * which is what makes showing it honest rather than hopeful.
	 *
	 * It will **not** answer for [PendingSnapState.FAILED] (proven absent, so
	 * there is nothing to show), for a corrupt record, or for a record whose
	 * author is not the signed-in account. The last is the same refusal [local]
	 * makes, for the same reason.
	 *
	 * Reads only. No network, no mutation — the stored record is the write
	 * path's state machine and a display path has no business editing it.
	 */
	fun staged(account: String, eventId: String): PostedSnap? {
		val record = (store.read(account, eventId) as? PendingSnapRead.Present)?.snap ?: return null
		if (!record.belongsTo(account, eventId)) return null
		// Root Snaps only, exactly as in [local].
		if (record.kind != PendingSnapKind.ROOT) return null
		if (record.state !in STAGED_STATES) return null
		val text = PostedSnapBody.userText(record.body) ?: return null
		return PostedSnap(
			author = record.author,
			permlink = record.permlink,
			userText = text,
			// The moment the transaction was built. Within seconds of the block
			// that will carry it, and the only time this Snap has yet.
			createdAtEpochSec = record.createdAtEpochSec,
			fromChain = false,
		)
	}

	/**
	 * The same card, refreshed against Hive where Hive could be read.
	 *
	 * [PostedSnap.fromChain] is the answer to "did the read succeed": true only
	 * when Hive answered for this exact author/permlink with a body. Anything
	 * else — offline, timeout, a node with no such object, an empty body —
	 * returns [local] with `fromChain = false`, and the caller keeps whatever it
	 * already shows. A failed read is never taken as a deletion.
	 *
	 * The chain body is authoritative (Issue 40C). A body in the frozen v1 shape
	 * is subtracted exactly; any other body is the author's, edited or
	 * rearranged elsewhere, and loses only what RustedWax can prove is its own —
	 * see [PostedSnapBody.authored]. Never the stale local words instead.
	 *
	 * On success the body is remembered in [cache]. The pending record — the
	 * write path's state machine — is never written.
	 */
	fun refreshed(account: String, eventId: String): PostedSnap? {
		val read = readRefresh(account, eventId) ?: return null
		if (read.snap.fromChain) rememberRefresh(account, eventId, read)
		return read.snap
	}

	/** No cache mutation: concurrent callers must first order their answers. */
	internal fun readRefresh(account: String, eventId: String): PostedSnapRefresh? {
		val record = (store.read(account, eventId) as? PendingSnapRead.Present)?.snap ?: return null
		val local = local(account, eventId, record) ?: return null
		val fallback = PostedSnapRefresh(local)
		val content = runCatching { reader.read(local.author, local.permlink) }.getOrNull()
			?: return fallback
		val chainText = reconcile(content.body, record.body) ?: return fallback
		return PostedSnapRefresh(
			snap = local.copy(
				userText = chainText,
				createdAtEpochSec = content.createdAtEpochSec ?: local.createdAtEpochSec,
				fromChain = true,
			),
			cacheEntry = PostedSnapCacheEntry(chainBody = content.body, recordBody = record.body),
		)
	}

	/**
	 * Called without suspension alongside the controller's acceptance check.
	 * A locally edited, deleted or replaced record retires the earlier read.
	 * SharedPreferences applies the accepted cache update in memory immediately
	 * and writes it to disk asynchronously.
	 */
	internal fun rememberRefresh(account: String, eventId: String, read: PostedSnapRefresh): Boolean {
		val entry = read.cacheEntry ?: return false
		val record = (store.read(account, eventId) as? PendingSnapRead.Present)?.snap ?: return false
		if (!record.belongsTo(account, eventId) || record.kind != PendingSnapKind.ROOT ||
			record.state != PendingSnapState.CONFIRMED ||
			"${record.author}/${record.permlink}" != read.snap.contentId || record.body != entry.recordBody
		) return false
		cache?.write(account, read.snap.contentId, entry)
		return true
	}

	/** [account]'s last fully successful Snap refresh, epoch millis, or null. */
	fun lastRefreshed(account: String): Long? = cache?.lastRefreshed(account)

	fun markRefreshed(account: String, atMillis: Long) {
		cache?.markRefreshed(account, atMillis)
	}

	/**
	 * What the card shows for [chainBody], given the body RustedWax stored for
	 * the same Snap. Null for a blank body, which is not something to draw.
	 */
	private fun reconcile(chainBody: String, recordBody: String): String? {
		if (chainBody.isBlank()) return null
		PostedSnapBody.userText(chainBody)?.let { return it }
		// The association: this Snap's own generated link, from the body this
		// device wrote for it. Missing means nothing is removed at all.
		val url = PostedSnapBody.generatedUrl(recordBody) ?: return chainBody
		return PostedSnapBody.authored(chainBody, url)
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
	 *  - **[PendingSnap.author] must be the signed-in account**, compared as
	 *    [PendingSnapIntegrity] compares it and refused when the stored name is
	 *    not a usable Hive account name at all. A confirmed
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
		this.eventId == eventId &&
			// The account/author/caller agreement, from the one place that
			// defines it — the same rule the write path's gate applies, so a
			// record that cannot be published cannot be drawn either, and a
			// case-only difference is not mistaken for a different person.
			PendingSnapIntegrity.identityProblem(this, account) == null

	private companion object {
		/**
		 * Durably signed and committed, outcome not yet decided.
		 *
		 * `INTENT` is frozen but unbuilt; `PREPARED` is signed and unsent; the
		 * other three have crossed the
		 * network boundary and are awaiting reconciliation. All four describe a
		 * Snap this device has permanently committed to, which is the property
		 * the optimistic card is allowed to draw on. `CONFIRMED` is absent
		 * because [local] already serves it, and `FAILED`/`CORRUPT` because
		 * neither is something to show.
		 */
		val STAGED_STATES = setOf(
			PendingSnapState.INTENT,
			PendingSnapState.PREPARED,
			PendingSnapState.BROADCASTING,
			PendingSnapState.ACCEPTED_UNCONFIRMED,
			PendingSnapState.UNRESOLVED,
		)
	}
}
