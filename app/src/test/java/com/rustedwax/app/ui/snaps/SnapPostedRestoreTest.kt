package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.PendingSnap
import com.rustedwax.app.snaps.PendingSnapRead
import com.rustedwax.app.snaps.PendingSnapKind
import com.rustedwax.app.snaps.PendingSnapState
import com.rustedwax.app.snaps.PendingSnapStore
import com.rustedwax.app.snaps.PostedSnapContent
import com.rustedwax.app.snaps.PostedSnapReader
import com.rustedwax.app.snaps.PostedSnaps
import com.rustedwax.app.snaps.SnapHivePort
import com.rustedwax.app.snaps.SnapPublisher
import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.SnapContainer
import com.rustedwax.hive.SnapContainerResolver
import com.rustedwax.hive.TxSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reopening the app must not look like forgetting what was published.
 *
 * Closing RustedWax from Recents destroys the Activity — and with it the
 * composition-scoped controllers — while the process survives. Everything a
 * posted card draws is already in the pending store, proven and durable, so it
 * has no business waiting on Hive. An earlier revision interleaved the local
 * read and the chain refresh row by row, so the card a user was looking at
 * could sit blank behind several unrelated round trips, and which row drew
 * first was decided by `SharedPreferences.getAll()`'s hash order.
 */
class SnapPostedRestoreTest {

	private val account = "alice"
	private val eventIds = listOf("e1", "e2", "e3", "e4")

	// ── fakes ──────────────────────────────────────────────────────────

	/** Confirmed roots, handed back in whatever order the test asks for. */
	private class Store(
		private val order: List<String>,
		val saved: MutableMap<String, PendingSnap> = mutableMapOf(),
	) : PendingSnapStore {

		override fun read(account: String, eventId: String): PendingSnapRead =
			saved["$account|$eventId"]?.let(PendingSnapRead::Present) ?: PendingSnapRead.Absent

		override fun write(snap: PendingSnap) = true
		override fun clear(account: String, eventId: String) = Unit

		/** Deliberately the order the test chose — prefs guarantee none. */
		override fun all(account: String) = order.mapNotNull { saved["$account|$it"] }
		override fun corruptEventIds(account: String) = emptySet<String>()
	}

	private class Hive : SnapHivePort {
		override fun resolveContainer() = SnapContainerResolver.Result.Resolved(
			SnapContainer("peak.snaps", "snap-container-1", "2026-09-17T12:36:00"),
		)
		override fun prepareComment(operation: TxSerializer.CommentOp, author: String) =
			HivePreparationResult.Ready(PreparedHiveTransaction("{}", "tx", 2_000_000_000L))
		override fun broadcastPrepared(prepared: PreparedHiveTransaction, author: String) =
			HiveRpc.BroadcastResult.NetworkFailure("not used in this test")
		override fun observeTransaction(txId: String, expirationEpochSec: Long) =
			HiveRpc.TransactionEvidence.UNAVAILABLE
		override fun contentExists(author: String, permlink: String): Boolean? = true
	}

	/**
	 * Records the exact interleaving of local reads and chain reads, which is
	 * the thing under test.
	 */
	private class Observed(
		private val store: PendingSnapStore,
		private val failingPermlinks: Set<String> = emptySet(),
	) {
		val order = mutableListOf<String>()

		fun postedSnaps(): PostedSnaps = PostedSnaps(
			store = object : PendingSnapStore by store {
				override fun read(account: String, eventId: String): PendingSnapRead {
					order += "local:$eventId"
					return store.read(account, eventId)
				}
			},
			reader = object : PostedSnapReader {
				override fun read(author: String, permlink: String): PostedSnapContent? {
					order += "chain:$permlink"
					if (permlink in failingPermlinks) throw IllegalStateException("node down")
					// Nothing useful: the local card must already be correct.
					return PostedSnapContent(body = "", createdAtEpochSec = null)
				}
			},
		)
	}

	// ── helpers ────────────────────────────────────────────────────────

	private fun confirmed(eventId: String, videoId: String) = PendingSnap(
		account = "alice",
		eventId = eventId,
		author = "alice",
		permlink = "rustedwax-snap-1000-$eventId",
		parentAuthor = "peak.snaps",
		parentPermlink = "snap-container-1",
		body = "snap $eventId\n\nhttps://youtu.be/$videoId\n\n#scrobblelife #scrobble #rustedwax",
		jsonMetadata = "{}",
		signedTransactionJson = "{}",
		txId = "tx-$eventId",
		expirationEpochSec = 2_000_000_000L,
		state = PendingSnapState.CONFIRMED,
		createdAtEpochSec = 1_000L,
		updatedAtEpochSec = 1_000L,
		kind = PendingSnapKind.ROOT,
	)

	private fun storeWith(order: List<String>): Store {
		val store = Store(order)
		eventIds.forEachIndexed { i, id ->
			store.saved["$account|$id"] = confirmed(id, "vid0000${i}aaa")
		}
		return store
	}

	private fun controller(store: Store, posted: PostedSnaps) = SnapPostController(
		scope = CoroutineScope(Dispatchers.Unconfined),
		publisher = { SnapPublisher(Hive(), store, nowEpochSec = { 1_000L }) },
		account = { account },
		io = Dispatchers.Unconfined,
		postedSnaps = { posted },
	)

	// ── the fix ────────────────────────────────────────────────────────

	@Test
	fun `every confirmed root is drawn from local state before any chain read`() {
		val store = storeWith(eventIds)
		val observed = Observed(store)
		val posts = controller(store, observed.postedSnaps())

		posts.resumePending()

		// `refreshed()` legitimately re-reads the record before asking the chain,
		// so later `local:` entries are expected. What matters is the opening
		// run: one local read per row, all of them before any chain read.
		val firstChain = observed.order.indexOfFirst { it.startsWith("chain:") }
		assertTrue("there must be chain work to compare against", firstChain >= 0)
		val beforeAnyChain = observed.order.take(firstChain)
		assertTrue(
			"every row must be read locally before the first chain read: ${observed.order}",
			eventIds.all { "local:$it" in beforeAnyChain },
		)
		assertTrue(
			"nothing but local reads may precede the first chain read",
			beforeAnyChain.all { it.startsWith("local:") },
		)
		eventIds.forEach { id ->
			assertNotNull("$id must be drawn", posts.posted(SnapDraftKey.of(account, id)))
		}
	}

	/** A node that hangs up on one row must not keep another row blank. */
	@Test
	fun `a failing chain read for one root does not stop another being drawn`() {
		val store = storeWith(eventIds)
		val observed = Observed(store, failingPermlinks = setOf("rustedwax-snap-1000-e1"))
		val posts = controller(store, observed.postedSnaps())

		posts.resumePending()

		eventIds.forEach { id ->
			assertNotNull(
				"$id must be drawn despite e1's chain failure",
				posts.posted(SnapDraftKey.of(account, id)),
			)
		}
	}

	/**
	 * `SharedPreferences.getAll()` is a hash map, so the order rows come back in
	 * is arbitrary and unstable. What the user sees must not depend on it.
	 */
	@Test
	fun `what is drawn does not depend on the order the store returns rows`() {
		val forwards = storeWith(eventIds)
		val backwards = storeWith(eventIds.reversed())
		val a = controller(forwards, Observed(forwards).postedSnaps())
		val b = controller(backwards, Observed(backwards).postedSnaps())

		a.resumePending()
		b.resumePending()

		eventIds.forEach { id ->
			val key = SnapDraftKey.of(account, id)
			assertNotNull(a.posted(key))
			assertEquals(
				"row $id must render identically whichever order it arrived in",
				a.posted(key)?.contentId,
				b.posted(key)?.contentId,
			)
		}
	}

	@Test
	fun `each drawn card is the record's own Snap`() {
		val store = storeWith(eventIds)
		val posts = controller(store, Observed(store).postedSnaps())

		posts.resumePending()

		eventIds.forEach { id ->
			val card = posts.posted(SnapDraftKey.of(account, id))!!
			assertEquals(account, card.author)
			assertEquals("rustedwax-snap-1000-$id", card.permlink)
			assertEquals("snap $id", card.userText)
		}
	}
}
