package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.PendingSnap
import com.rustedwax.app.snaps.PendingSnapRead
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
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One Snap's uncertainty must not hide another Snap's certainty.
 *
 * Restoring after a restart asks two very different questions. *Was this
 * published?* is already answered on disk for a `CONFIRMED` row — the comment is
 * on chain and the record beside it is what went there. *Did this one make it?*
 * is answered only by reading Hive, and that read can hang: a node that stops
 * responding, a device with no signal, a transaction still in a mempool.
 *
 * An earlier revision asked both in a single pass, so the second question's
 * latency became the first question's latency. A user who had posted two Snaps
 * and lost the answer to one of them reopened the app and saw *neither* — the
 * settled card sat blank behind an unrelated round trip that might never
 * finish. These tests pin the ordering that fixes it, and they use a real
 * stalled reconciliation rather than a simulated one, because the bug was about
 * real waiting.
 */
class SnapRestoreOrderingTest {

	private val account = "alice"

	/** The row whose outcome is genuinely unknown, and whose read will stall. */
	private val unresolvedEvent = "a-unresolved"

	/** The rows already proven on disk. They owe the network nothing. */
	private val confirmedEvents = listOf("b-confirmed", "c-confirmed", "d-confirmed")

	private val ioThread = Executors.newSingleThreadExecutor { r ->
		Thread(r, "snap-restore-io")
	}
	private val io = ioThread.asCoroutineDispatcher()

	/** Every stalling fake this test made, so teardown can always let them go. */
	private val hives = mutableListOf<StallingHive>()

	@After
	fun tearDown() {
		// Release first, drain second. Interrupting a fake mid-stall would print
		// an exception that has nothing to do with what the test proved.
		hives.forEach { it.release.countDown() }
		ioThread.shutdown()
		check(ioThread.awaitTermination(10, TimeUnit.SECONDS)) { "the io thread never drained" }
	}

	private fun stallingHive(permlink: String = stalledPermlink()) =
		StallingHive(permlink).also { hives += it }

	// ── fakes ──────────────────────────────────────────────────────────

	private class Store(
		private val order: List<String>,
		val saved: MutableMap<String, PendingSnap> = mutableMapOf(),
	) : PendingSnapStore {

		val writes = AtomicInteger(0)

		override fun read(account: String, eventId: String): PendingSnapRead =
			saved["$account|$eventId"]?.let(PendingSnapRead::Present) ?: PendingSnapRead.Absent

		override fun write(snap: PendingSnap): Boolean {
			writes.incrementAndGet()
			saved["${snap.account}|${snap.eventId}"] = snap
			return true
		}

		override fun clear(account: String, eventId: String) {
			saved.remove("$account|$eventId")
		}

		/** Deliberately the order the test chose — preferences guarantee none. */
		override fun all(account: String) = order.mapNotNull { saved["$account|$it"] }

		override fun corruptEventIds(account: String) = emptySet<String>()
	}

	/**
	 * A chain that answers instantly for everything except one permlink, which
	 * it holds open until the test lets go.
	 */
	private class StallingHive(
		private val stalledPermlink: String,
		val reached: CountDownLatch = CountDownLatch(1),
		val release: CountDownLatch = CountDownLatch(1),
	) : SnapHivePort {

		val prepared = AtomicInteger(0)
		val broadcasts = AtomicInteger(0)

		override fun resolveContainer() = SnapContainerResolver.Result.Resolved(
			SnapContainer("peak.snaps", "snap-container-1", "2026-09-17T12:36:00"),
		)

		override fun prepareComment(operation: TxSerializer.CommentOp): HivePreparationResult {
			prepared.incrementAndGet()
			return HivePreparationResult.Ready(PreparedHiveTransaction("{}", "tx", 2_000_000_000L))
		}

		override fun broadcastPrepared(prepared: PreparedHiveTransaction): HiveRpc.BroadcastResult {
			broadcasts.incrementAndGet()
			return HiveRpc.BroadcastResult.NetworkFailure("no test may reach this")
		}

		override fun observeTransaction(txId: String, expirationEpochSec: Long) =
			HiveRpc.TransactionEvidence.UNAVAILABLE

		override fun contentExists(author: String, permlink: String): Boolean {
			if (permlink != stalledPermlink) return true
			reached.countDown()
			// The node that stops answering. Bounded only so a broken build fails
			// instead of hanging the suite.
			release.await(10, TimeUnit.SECONDS)
			// No comment at that permlink — which sends `reconcile` on to the
			// transaction evidence, where UNAVAILABLE leaves the row unresolved.
			return false
		}
	}

	/** A chain that confirms whatever it is asked about, without stalling. */
	private object AnsweringHive : SnapHivePort {
		override fun resolveContainer() = SnapContainerResolver.Result.Resolved(
			SnapContainer("peak.snaps", "snap-container-1", "2026-09-17T12:36:00"),
		)

		override fun prepareComment(operation: TxSerializer.CommentOp) =
			HivePreparationResult.Ready(PreparedHiveTransaction("{}", "tx", 2_000_000_000L))

		override fun broadcastPrepared(prepared: PreparedHiveTransaction) =
			HiveRpc.BroadcastResult.NetworkFailure("no test may reach this")

		override fun observeTransaction(txId: String, expirationEpochSec: Long) =
			HiveRpc.TransactionEvidence.UNAVAILABLE

		override fun contentExists(author: String, permlink: String): Boolean = true
	}

	/** Records every chain read of a posted card, so "before any network" is checkable. */
	private open class Reader : PostedSnapReader {
		val reads = mutableListOf<String>()

		@Synchronized
		override fun read(author: String, permlink: String): PostedSnapContent {
			reads += permlink
			return PostedSnapContent(body = "", createdAtEpochSec = null)
		}

		@Synchronized
		fun seen(): List<String> = reads.toList()
	}

	// ── helpers ────────────────────────────────────────────────────────

	private fun record(eventId: String, state: PendingSnapState) = PendingSnap(
		account = account,
		eventId = eventId,
		author = account,
		permlink = "rustedwax-snap-1000-$eventId",
		parentAuthor = "peak.snaps",
		parentPermlink = "snap-container-1",
		body = "snap $eventId\n\nhttps://youtu.be/vid$eventId\n\n" +
			"#scrobblelife #scrobble #rustedwax",
		jsonMetadata = "{}",
		signedTransactionJson = "{}",
		txId = "tx-$eventId",
		expirationEpochSec = 2_000_000_000L,
		state = state,
		createdAtEpochSec = 1_000L,
		updatedAtEpochSec = 1_000L,
	)

	private fun storeWith(order: List<String>): Store {
		val store = Store(order)
		store.saved["$account|$unresolvedEvent"] =
			record(unresolvedEvent, PendingSnapState.ACCEPTED_UNCONFIRMED)
		confirmedEvents.forEach {
			store.saved["$account|$it"] = record(it, PendingSnapState.CONFIRMED)
		}
		return store
	}

	private fun controller(
		store: Store,
		hive: SnapHivePort,
		reader: Reader,
		account: () -> String? = { this.account },
	) = SnapPostController(
		scope = CoroutineScope(Dispatchers.Unconfined),
		publisher = { SnapPublisher(hive, store, nowEpochSec = { 1_000L }) },
		account = account,
		io = io,
		postedSnaps = { PostedSnaps(store, reader) },
	)

	private fun stalledPermlink() = "rustedwax-snap-1000-$unresolvedEvent"

	// ── Codex's scenario ───────────────────────────────────────────────

	/**
	 * The exact report: one row stuck mid-reconciliation, one row long since
	 * proven, and the proven one invisible until the stuck one gives up.
	 */
	@Test
	fun `a stalled reconciliation cannot keep a proven Snap off the screen`() {
		// The unresolved row first, so the old code reached its stalling read
		// before it ever looked at the confirmed one.
		val store = storeWith(listOf(unresolvedEvent) + confirmedEvents)
		val hive = stallingHive()
		val reader = Reader()
		val posts = controller(store, hive, reader)

		posts.resumePending()

		assertTrue(
			"reconciliation for the unresolved row should have started",
			hive.reached.await(5, TimeUnit.SECONDS),
		)

		// It is still stuck in there right now. Every settled Snap must already
		// be on screen anyway.
		confirmedEvents.forEach { eventId ->
			val key = SnapDraftKey.of(account, eventId)
			assertEquals(
				"$eventId must be posted while the other row is still stalling",
				SnapPostStatus.Posted("$account/rustedwax-snap-1000-$eventId"),
				posts.status(key),
			)
			assertEquals(
				"$eventId must show its own words, not an empty card",
				"snap $eventId",
				posts.posted(key)?.userText,
			)
		}

		hive.release.countDown()
	}

	/** And nothing it did to get there involved the network. */
	@Test
	fun `every proven Snap is drawn before a single chain read`() {
		val store = storeWith(listOf(unresolvedEvent) + confirmedEvents)
		val hive = stallingHive()
		val reader = Reader()
		val posts = controller(store, hive, reader)

		posts.resumePending()
		assertTrue(hive.reached.await(5, TimeUnit.SECONDS))

		assertEquals(
			"no posted card may be refreshed from the chain before the stall",
			emptyList<String>(),
			reader.seen(),
		)
		confirmedEvents.forEach {
			assertNotNull(posts.posted(SnapDraftKey.of(account, it)))
		}

		hive.release.countDown()
	}

	/**
	 * `SharedPreferences.getAll()` is a hash map. Which row the store happens to
	 * hand back first must not decide what the user sees.
	 */
	@Test
	fun `the order the store returns rows cannot change what is drawn`() {
		listOf(
			listOf(unresolvedEvent) + confirmedEvents,
			confirmedEvents + unresolvedEvent,
			confirmedEvents.reversed() + unresolvedEvent,
			listOf(confirmedEvents[0], unresolvedEvent) + confirmedEvents.drop(1),
		).forEach { order ->
			val store = storeWith(order)
			val hive = stallingHive()
			val posts = controller(store, hive, Reader())

			posts.resumePending()
			assertTrue("$order", hive.reached.await(5, TimeUnit.SECONDS))

			confirmedEvents.forEach { eventId ->
				assertEquals(
					"$eventId must be drawn whatever order $order the store used",
					"snap $eventId",
					posts.posted(SnapDraftKey.of(account, eventId))?.userText,
				)
			}

			hive.release.countDown()
		}
	}

	/** A row nobody can decide stays undecided — it is not quietly promoted. */
	@Test
	fun `the unresolved row is never drawn as posted`() {
		val store = storeWith(listOf(unresolvedEvent) + confirmedEvents)
		val hive = stallingHive()
		val posts = controller(store, hive, Reader())
		val key = SnapDraftKey.of(account, unresolvedEvent)

		posts.resumePending()
		assertTrue(hive.reached.await(5, TimeUnit.SECONDS))

		assertNull("an unresolved Snap has no proven card to show", posts.posted(key))
		assertFalse(
			"an unresolved Snap must not be claimed as posted",
			posts.status(key) is SnapPostStatus.Posted,
		)

		hive.release.countDown()
	}

	// ── what phase two still does ──────────────────────────────────────

	/**
	 * The point of phase one is to stop reconciliation blocking the display, not
	 * to replace it. Once the chain answers, the unresolved row lands where the
	 * existing rules say it should — and the proven rows are untouched.
	 */
	@Test
	fun `reconciliation still settles the unresolved row once the chain answers`() {
		val store = storeWith(listOf(unresolvedEvent) + confirmedEvents)
		val hive = stallingHive()
		val reader = Reader()
		val posts = controller(store, hive, reader)
		val stalled = SnapDraftKey.of(account, unresolvedEvent)

		posts.resumePending()
		assertTrue(hive.reached.await(5, TimeUnit.SECONDS))
		hive.release.countDown()
		awaitChainReads(reader, confirmedEvents.size)

		assertTrue(
			"an unanswerable row stays uncertain, exactly as before",
			posts.status(stalled) is SnapPostStatus.Uncertain,
		)
		assertEquals(
			"and it is stored as unresolved, not failed",
			PendingSnapState.UNRESOLVED,
			store.saved["$account|$unresolvedEvent"]?.state,
		)
		confirmedEvents.forEach { eventId ->
			assertEquals(
				SnapPostStatus.Posted("$account/rustedwax-snap-1000-$eventId"),
				posts.status(SnapDraftKey.of(account, eventId)),
			)
		}
	}

	/** A row reconciliation proves gets its local read too, so a failing chain read costs nothing. */
	@Test
	fun `a row proved only by reconciliation is still drawn from its own record`() {
		val store = storeWith(listOf(unresolvedEvent) + confirmedEvents)
		// This time the chain says the unresolved comment is there — and then
		// every read of a posted card fails, which must not undo the local draw.
		val hive = AnsweringHive
		val reader = object : Reader() {
			override fun read(author: String, permlink: String): PostedSnapContent =
				throw IllegalStateException("every chain read fails")
		}
		val posts = controller(store, hive, reader)
		val key = SnapDraftKey.of(account, unresolvedEvent)

		posts.resumePending()
		awaitPosted(posts, key)

		assertEquals(
			"the newly proven row must show its stored words despite the chain failing",
			"snap $unresolvedEvent",
			posts.posted(key)?.userText,
		)
	}

	// ── nothing here publishes ─────────────────────────────────────────

	/**
	 * Restoring a display is not re-publishing. The narrow local read added for
	 * phase one must not have opened a second route to the network.
	 */
	@Test
	fun `restoring never prepares or broadcasts anything`() {
		val store = storeWith(listOf(unresolvedEvent) + confirmedEvents)
		val hive = stallingHive()
		val reader = Reader()
		val posts = controller(store, hive, reader)
		val writesBefore = store.writes.get()

		posts.resumePending()
		assertTrue(hive.reached.await(5, TimeUnit.SECONDS))

		// Phase one is the part under test: disk only, and it changes nothing.
		assertEquals("phase one must not write", writesBefore, store.writes.get())

		hive.release.countDown()
		awaitChainReads(reader, confirmedEvents.size)

		assertEquals("no transaction may be prepared", 0, hive.prepared.get())
		assertEquals("no transaction may be broadcast", 0, hive.broadcasts.get())
		confirmedEvents.forEach { eventId ->
			assertEquals(
				"a proven row's stored record must come back untouched",
				record(eventId, PendingSnapState.CONFIRMED),
				store.saved["$account|$eventId"],
			)
		}
	}

	// ── account isolation ──────────────────────────────────────────────

	/**
	 * Signing out between the disk read and the draw must leave nothing behind.
	 * A card keyed to the account that has gone is one user's Snap on another
	 * user's screen.
	 */
	@Test
	fun `a Snap is not drawn for an account that is no longer signed in`() {
		val store = storeWith(listOf(unresolvedEvent) + confirmedEvents)
		val hive = stallingHive()
		val switched = CountDownLatch(1)
		// Reads as `alice` once — the value phase one captures — then as somebody
		// else for every check that follows.
		val seen = AtomicInteger(0)
		val posts = controller(store, hive, Reader()) {
			if (seen.getAndIncrement() == 0) account else "bob".also { switched.countDown() }
		}

		posts.resumePending()
		switched.await(5, TimeUnit.SECONDS)

		confirmedEvents.forEach { eventId ->
			assertNull(
				"$eventId must not be drawn under alice's key after the switch",
				posts.posted(SnapDraftKey.of(account, eventId)),
			)
			assertNull(
				"$eventId must not be drawn under bob's key either",
				posts.posted(SnapDraftKey.of("bob", eventId)),
			)
		}

		hive.release.countDown()
	}

	// ── waiting helpers ────────────────────────────────────────────────

	private fun awaitChainReads(reader: Reader, atLeast: Int) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
		while (System.nanoTime() < deadline) {
			if (reader.seen().size >= atLeast) return
			Thread.sleep(10)
		}
		throw AssertionError("expected $atLeast chain reads, saw ${reader.seen()}")
	}

	private fun awaitPosted(posts: SnapPostController, key: String) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
		while (System.nanoTime() < deadline) {
			if (posts.posted(key) != null) return
			Thread.sleep(10)
		}
		throw AssertionError("nothing was ever drawn for $key")
	}
}
