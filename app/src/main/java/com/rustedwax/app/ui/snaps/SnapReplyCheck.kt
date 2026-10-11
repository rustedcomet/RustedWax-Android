package com.rustedwax.app.ui.snaps

import androidx.annotation.WorkerThread
import com.rustedwax.app.detect.RustedWaxUiVisibility
import com.rustedwax.app.snaps.SnapReplyTarget
import com.rustedwax.app.snaps.SnapThreadReader
import com.rustedwax.app.storage.db.LocalOwner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Issue #42: look for new replies to this account's Snaps without waiting for a
 * History card to be on screen.
 *
 * Stage 6 only learned about a reply when a conversation happened to be read
 * for drawing. This asks the same question of the account's newest root Snaps
 * directly, and answers it with exactly the machinery that path already uses:
 * the same [SnapThreadReader], the same [SnapNoticeScan] rule about who is
 * notified, the same [SnapNoticeStore] that persists and de-duplicates before
 * anything is announced, and the same [SnapNoticeNotifier]. There is no second
 * copy of any of those rules here.
 *
 * Reads only. No key, publisher, port or broadcaster is reachable from this
 * class, so nothing it does can end in a Hive transaction.
 *
 * Bounded: at most [MAX_ROOTS] conversations per run, read one after another,
 * and at most one run per [MIN_INTERVAL_MILLIS] per account unless forced. The
 * [NEWEST] newest Snaps are read every run; the remaining reads walk the older
 * ones in turn from a saved [Rotation], wrapping at the end, so every Snap is
 * eventually read again without any run reading more than [MAX_ROOTS]. A run
 * stops after [MAX_FAILURES] failed reads rather than pressing on against a node
 * that is not answering.
 */
internal class SnapReplyCheck(
	/** The signed-in account, read late and re-read before every announcement. */
	private val account: () -> String?,
	/** Every candidate root Snap for one account, in any order. Disk only. */
	private val roots: (account: String) -> List<MySnapRow>,
	private val reader: () -> SnapThreadReader?,
	private val store: SnapNoticeStore,
	private val notifier: SnapNoticeNotifier,
	/** Whether this account has this conversation open on screen right now. */
	private val visible: (account: String, rootId: String) -> Boolean,
	/** The last run's time and the rotation, per account. Null means unthrottled and in memory only. */
	private val checkpoints: SyncCheckpoints?,
	private val nowMillis: () -> Long = { System.currentTimeMillis() },
	private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

	sealed interface Outcome {
		/** Nothing was read: nobody signed in, too soon, already running, or storage not ready. */
		data class Skipped(val reason: String) : Outcome

		/**
		 * A run happened. [announced] went to the shade; [quiet] are conversations
		 * open on screen that gained a reply and should be re-read in place;
		 * [complete] is false when a read or a durable write failed part way.
		 */
		data class Checked(
			val account: String,
			val read: Int,
			val announced: List<SnapNotice>,
			val quiet: List<SnapReplyTarget>,
			val complete: Boolean,
		) : Outcome
	}

	/**
	 * How far the walk through the older Snaps has got for one account.
	 *
	 * [afterAt]/[afterPermlink] name the last older Snap that was read **and**
	 * recorded — a position by value, not an index, so a new Snap at the top
	 * shifts nothing. Null means "from the start". [stuckOn] and [strikes] count
	 * runs in which the next Snap's read failed while another read in the same
	 * run succeeded: a Snap that cannot be read (deleted elsewhere, say) is passed
	 * after [MAX_STRIKES] such runs instead of blocking the walk for good, and it
	 * comes round again on the next lap.
	 */
	internal data class Rotation(
		val afterAt: Long? = null,
		val afterPermlink: String? = null,
		val stuckOn: String? = null,
		val strikes: Int = 0,
	) {
		fun encode(): String = listOf(afterAt?.toString().orEmpty(), afterPermlink.orEmpty(), stuckOn.orEmpty(), strikes.toString())
			.joinToString("|")

		/** Strictly older than the position, in the newest-first order. */
		fun isPast(row: MySnapRow): Boolean {
			val at = afterAt ?: return true
			val permlink = afterPermlink ?: return true
			return row.createdAtEpochSec < at || (row.createdAtEpochSec == at && row.permlink < permlink)
		}

		fun after(row: MySnapRow) = Rotation(row.createdAtEpochSec, row.permlink)

		companion object {
			/** Null for anything this code did not write; the walk then starts over, which skips nothing. */
			fun decode(value: String?): Rotation? {
				val parts = value?.split('|') ?: return null
				if (parts.size != 4) return null
				val at = parts[0].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }
				val permlink = parts[1].takeIf { it.isNotEmpty() }
				if ((at == null) != (permlink == null)) return null
				val strikes = parts[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
				return Rotation(at, permlink, parts[2].takeIf { it.isNotEmpty() }, strikes)
			}
		}
	}

	private val running = AtomicBoolean(false)

	/** The rotation as last advanced in this process, for when the checkpoint cannot be read. */
	private val remembered = java.util.concurrent.ConcurrentHashMap<String, Rotation>()

	@WorkerThread
	fun run(force: Boolean = false): Outcome {
		val who = account()?.takeIf { it.isNotBlank() } ?: return Outcome.Skipped("no account")
		val owner = LocalOwner.account(who) ?: return Outcome.Skipped("no account")
		if (!running.compareAndSet(false, true)) return Outcome.Skipped("already running")
		try {
			val now = nowMillis()
			if (!force) {
				val last = runCatching { checkpoints?.get(owner, SCOPE)?.value?.toLongOrNull() }.getOrNull()
				if (last != null && now - last in 0 until MIN_INTERVAL_MILLIS) return Outcome.Skipped("too soon")
			}
			// A cold process starts the notice store asynchronously. Waiting a
			// moment is cheaper than spending network reads that cannot be recorded.
			if (!awaitStore()) return Outcome.Skipped("storage not ready")
			// Stamped before the reads, so a failing node is retried on the next
			// window rather than on every resume.
			runCatching { checkpoints?.put(owner, SCOPE, now.toString(), now) }

			val ordered = ordered(runCatching { roots(who) }.getOrDefault(emptyList()))
			val newest = ordered.take(NEWEST)
			val older = ordered.drop(NEWEST)
			var rotation = loadRotation(owner)
			// Cyclic: the older Snaps past the saved position, then the lap starts over.
			val turn = (older.filter { rotation.isPast(it) } + older.filterNot { rotation.isPast(it) })
				.take(MAX_ROOTS - newest.size)

			val source = reader() ?: return Outcome.Checked(who, 0, emptyList(), emptyList(), complete = false)
			val announced = mutableListOf<SnapNotice>()
			val quiet = mutableListOf<SnapReplyTarget>()
			var read = 0
			var failures = 0

			/** One conversation. True when read and recorded; null when the run must end. */
			fun check(row: MySnapRow): Boolean? {
				val root = SnapReplyTarget.of(row.author, row.permlink) ?: return false
				if (account() != who) return null
				val replies = runCatching { source.read(root.author, root.permlink, who) }.getOrNull()
				if (replies == null) {
					failures++
					return false
				}
				read++
				// The same discard rule as every completion in this stack: an
				// answer fetched as Alice lands nowhere once Bob is signed in.
				if (account() != who) return null
				val found = SnapNoticeScan.incoming(who, root, replies)
				// A write that did not happen ends the run: nothing is announced,
				// and the rotation is not moved past this Snap.
				val fresh = store.record(who, root.contentId, found, now / 1000) ?: return null
				if (fresh.isEmpty()) return true
				if (account() != who) return null
				val (shown, toShade) = fresh.partition { visible(who, it.rootId) }
				// Already on screen: answered in place, never a redundant alert.
				shown.forEach { store.markRead(who, it.contentId) }
				if (shown.isNotEmpty()) quiet += root
				toShade.forEach { notifier.post(who, it) }
				announced += toShade
				return true
			}

			for (row in newest) {
				val ok = check(row) ?: return ended(who, read, announced, quiet)
				if (!ok && failures >= MAX_FAILURES) return Outcome.Checked(who, read, announced, quiet, complete = false)
			}
			for (row in turn) {
				val ok = check(row) ?: return ended(who, read, announced, quiet)
				if (ok) {
					rotation = rotation.after(row)
					saveRotation(owner, rotation, now)
					continue
				}
				// Not moved past a Snap that failed. Only when another read in this
				// run worked is the failure likely to be the Snap's own.
				if (read > 0) {
					val strikes = if (rotation.stuckOn == row.contentId) rotation.strikes + 1 else 1
					rotation = if (strikes >= MAX_STRIKES) {
						rotation.after(row)
					} else {
						rotation.copy(stuckOn = row.contentId, strikes = strikes)
					}
					saveRotation(owner, rotation, now)
				}
				return Outcome.Checked(who, read, announced, quiet, complete = false)
			}
			return Outcome.Checked(who, read, announced, quiet, complete = failures == 0)
		} finally {
			running.set(false)
		}
	}

	private fun ended(who: String, read: Int, announced: List<SnapNotice>, quiet: List<SnapReplyTarget>): Outcome =
		if (account() != who) Outcome.Skipped("account changed") else Outcome.Checked(who, read, announced, quiet, complete = false)

	private fun loadRotation(owner: String): Rotation =
		runCatching { checkpoints?.get(owner, ROTATION_SCOPE)?.value }.getOrNull()
			?.let { Rotation.decode(it) }
			?: remembered[owner]
			?: Rotation()

	/** Memory first, so a checkpoint that will not take the write still cannot move the walk backwards in this process. */
	private fun saveRotation(owner: String, rotation: Rotation, now: Long) {
		remembered[owner] = rotation
		runCatching { checkpoints?.put(owner, ROTATION_SCOPE, rotation.encode(), now) }
	}

	private fun awaitStore(): Boolean {
		var waited = 0L
		while (!store.ready()) {
			if (waited >= READY_WAIT_MILLIS) return false
			sleep(READY_POLL_MILLIS)
			waited += READY_POLL_MILLIS
		}
		return true
	}

	companion object {
		const val SCOPE = "snap_reply_check"
		const val ROTATION_SCOPE = "snap_reply_rotation"
		/** Hive conversation reads per run, newest and rotating together. */
		const val MAX_ROOTS = 10
		/** Read on every run. */
		const val NEWEST = 5
		const val MAX_FAILURES = 2
		const val MAX_STRIKES = 3
		/** Candidate Snaps read from the local catalog per run. Disk only. */
		const val MAX_CANDIDATES = 1000
		const val MIN_INTERVAL_MILLIS = 15 * 60 * 1000L
		private const val READY_WAIT_MILLIS = 3_000L
		private const val READY_POLL_MILLIS = 100L

		/**
		 * The candidates newest first, one per identity, keeping only Snaps a
		 * conversation can be read for. The catalog alone is not enough: it is
		 * filled when My Snaps is opened, which may never have happened, so the
		 * confirmed publication records are merged in.
		 */
		fun ordered(rows: List<MySnapRow>): List<MySnapRow> =
			rows.asSequence()
				.filter { SnapReplyTarget.of(it.author, it.permlink) != null }
				.distinctBy { it.contentId }
				.sortedWith(compareByDescending<MySnapRow> { it.createdAtEpochSec }.thenByDescending { it.permlink })
				.toList()
	}
}

/**
 * Which conversation, if any, is on screen right now, for whom.
 *
 * Process-wide rather than composition state because the reply check runs off
 * the main thread and must not post a notification about the thread the user
 * is already reading. Set and cleared by the one conversation sheet.
 */
internal object SnapThreadVisibility {

	@Volatile
	private var open: String? = null

	private fun key(account: String, rootId: String) = "$account|$rootId"

	fun opened(account: String?, rootId: String) {
		open = account?.takeIf { it.isNotBlank() }?.let { key(it, rootId) }
	}

	fun closed(account: String?, rootId: String) {
		val who = account?.takeIf { it.isNotBlank() } ?: return
		if (open == key(who, rootId)) open = null
	}

	/** Open in the sheet **and** the app in front: a backgrounded sheet is not being read. */
	fun isVisible(account: String, rootId: String): Boolean =
		RustedWaxUiVisibility.isResumed && open == key(account, rootId)
}
