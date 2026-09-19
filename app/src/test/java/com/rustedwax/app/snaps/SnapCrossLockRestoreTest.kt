package com.rustedwax.app.snaps

import com.rustedwax.app.ui.snaps.SnapDraftKey
import com.rustedwax.app.ui.snaps.SnapPostController
import com.rustedwax.app.ui.snaps.SnapPostStatus
import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.SnapContainer
import com.rustedwax.hive.SnapContainerResolver
import com.rustedwax.hive.TxSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A row cannot be corrupt when asked about directly and fine when listed.
 *
 * `read` already refused an event that some *other* stored row also claims: two
 * entries pointing at one event mean RustedWax cannot tell which of them
 * describes the comment that may already be on chain, and that is exactly the
 * situation a Snap must fail closed on. Enumeration did not refuse it. It
 * judged each entry against its own key alone, so the tidy-looking record
 * genuinely stored under the contested event walked straight through — and
 * `restore` would then report that event twice: once as corrupt, and again,
 * later in the same list, as published.
 *
 * The later one wins when a caller applies them in order, so the safe answer
 * was being overwritten by the unsafe one. These tests pin both halves: the
 * integrity rule itself, and what `SnapPublisher` and the card end up showing.
 */
class SnapCrossLockRestoreTest {

	private val account = "alice"

	/** The damaged entry's key. Its decoded record claims [contested] instead. */
	private val damagedKey = "event-a"

	/** Claimed by the damaged entry *and* the home of its own tidy record. */
	private val contested = "event-b"

	/** Nothing implicates this one. It must survive untouched. */
	private val healthy = "event-c"

	// ── a store that is the production store, rule for rule ────────────

	/**
	 * Raw `account|eventId to json` entries, read back through the real
	 * [PendingSnapIntegrity].
	 *
	 * Every method body here is the one `SharedPreferencesPendingSnapStore` uses;
	 * only the map underneath differs, because `SharedPreferences` needs a
	 * `Context` this source set has no way to provide. The rules under test are
	 * the production rules, not a restatement of them.
	 */
	private class Store(val saved: LinkedHashMap<String, String> = LinkedHashMap()) :
		PendingSnapStore {

		val writes = mutableListOf<PendingSnap>()

		fun entries(account: String) = saved.entries
			.filter { it.key.startsWith("$account|") }
			.associate { it.key.removePrefix("$account|") to it.value }

		override fun read(account: String, eventId: String): PendingSnapRead {
			if (eventId in PendingSnapIntegrity.lockedEventIds(account, entries(account))) {
				return PendingSnapRead.Corrupt("stored Snap state is inconsistent")
			}
			val raw = saved["$account|$eventId"] ?: return PendingSnapRead.Absent
			val parsed = PendingSnap.fromJson(raw)
				?: return PendingSnapRead.Corrupt("stored Snap state could not be read")
			return PendingSnapRead.Present(parsed)
		}

		override fun write(snap: PendingSnap): Boolean {
			writes += snap
			saved["${snap.account}|${snap.eventId}"] = snap.toJson()
			return true
		}

		override fun clear(account: String, eventId: String) {
			saved.remove("$account|$eventId")
		}

		override fun all(account: String) =
			PendingSnapIntegrity.valid(account, entries(account))

		override fun corruptEventIds(account: String) =
			PendingSnapIntegrity.lockedEventIds(account, entries(account))
	}

	/** Records every attempt to touch the chain, so restore can be proved read-only. */
	private class Hive : SnapHivePort {
		val prepared = mutableListOf<TxSerializer.CommentOp>()
		val broadcast = mutableListOf<PreparedHiveTransaction>()
		val contentQueries = mutableListOf<String>()

		override fun resolveContainer() = SnapContainerResolver.Result.Resolved(
			SnapContainer("peak.snaps", "snap-container-1789648560", "2026-09-17T12:36:00"),
		)

		override fun prepareComment(operation: TxSerializer.CommentOp, author: String): HivePreparationResult {
			prepared += operation
			return HivePreparationResult.Ready(
				PreparedHiveTransaction("{}", "tx", 2_000_000_000L),
			)
		}

		override fun broadcastPrepared(prepared: PreparedHiveTransaction, author: String): HiveRpc.BroadcastResult {
			broadcast += prepared
			return HiveRpc.BroadcastResult.NetworkFailure("no test may reach this")
		}

		override fun observeTransaction(txId: String, expirationEpochSec: Long) =
			HiveRpc.TransactionEvidence.UNAVAILABLE

		override fun contentExists(author: String, permlink: String): Boolean {
			contentQueries += permlink
			return true
		}
	}

	// ── fixtures ───────────────────────────────────────────────────────

	private fun record(
		eventId: String,
		account: String = this.account,
		state: PendingSnapState = PendingSnapState.CONFIRMED,
	) = PendingSnap(
		account = account,
		eventId = eventId,
		author = account,
		permlink = "rustedwax-snap-1000-$eventId",
		parentAuthor = "peak.snaps",
		parentPermlink = "snap-container-1789648560",
		body = "snap $eventId\n\nhttps://youtu.be/vid$eventId\n\n" +
			"#scrobblelife #scrobble #rustedwax",
		jsonMetadata = "{}",
		signedTransactionJson = """{"op":1}""",
		txId = "tx-$eventId",
		expirationEpochSec = 2_000_000_000L,
		state = state,
		createdAtEpochSec = 1_000L,
		updatedAtEpochSec = 1_000L,
		kind = PendingSnapKind.ROOT,
	)

	/**
	 * The exact shape Codex described: an entry filed under [damagedKey] whose
	 * decoded record claims [contested], and a separate, entirely well-formed
	 * confirmed record genuinely stored under [contested].
	 */
	private fun collidingStore(order: List<String> = listOf(damagedKey, contested, healthy)): Store {
		val store = Store()
		order.forEach { key ->
			when (key) {
				// Well-formed JSON, wrong home: it claims the contested event.
				damagedKey -> store.saved["$account|$damagedKey"] =
					record(eventId = contested).toJson()
				contested -> store.saved["$account|$contested"] =
					record(eventId = contested).toJson()
				healthy -> store.saved["$account|$healthy"] =
					record(eventId = healthy).toJson()
			}
		}
		return store
	}

	private fun publisher(store: Store, hive: Hive = Hive()) =
		SnapPublisher(hive, store, nowEpochSec = { 1_000L })

	// ── the integrity rule ─────────────────────────────────────────────

	@Test
	fun `a contested event is locked even though its own record is well formed`() {
		val store = collidingStore()

		val locked = store.corruptEventIds(account)

		assertTrue("the damaged entry's key is locked", damagedKey in locked)
		assertTrue("the event it claims is locked too", contested in locked)
		assertFalse("an unrelated event is not", healthy in locked)
	}

	@Test
	fun `reading the contested event is refused`() {
		val store = collidingStore()

		assertTrue(store.read(account, contested) is PendingSnapRead.Corrupt)
		assertTrue(store.read(account, damagedKey) is PendingSnapRead.Corrupt)
		assertTrue(store.read(account, healthy) is PendingSnapRead.Present)
	}

	/** The fix: enumeration must answer what `read` answers. */
	@Test
	fun `enumeration leaves out the contested event`() {
		val store = collidingStore()

		assertEquals(
			"only the untouched record may be enumerated",
			listOf(healthy),
			store.all(account).map { it.eventId },
		)
	}

	// ── what restore does with it ──────────────────────────────────────

	@Test
	fun `restoreLocal never surfaces a contested record as published`() {
		val store = collidingStore()

		val local = publisher(store).restoreLocal(account)

		assertEquals(listOf(healthy), local.map { it.first })
		assertNull(
			"the contested event must not appear at all",
			local.firstOrNull { it.first == contested },
		)
	}

	@Test
	fun `restore reports the contested event as uncertain and never as published`() {
		val store = collidingStore()

		val restored = publisher(store).restore(account)

		val outcomes = restored.filter { it.first == contested }.map { it.second }
		assertEquals("the contested event is reported exactly once", 1, outcomes.size)
		assertTrue(
			"and the one report is uncertain, not published",
			outcomes.single() is SnapPublisher.Outcome.Uncertain,
		)
		assertTrue(
			"no published outcome anywhere for it",
			restored.none {
				it.first == contested && it.second is SnapPublisher.Outcome.Published
			},
		)
	}

	/**
	 * The failure mode that made this a blocker rather than a cosmetic
	 * inconsistency: one event arriving twice, safe first and unsafe second, so
	 * that a caller applying the list in order ends on the unsafe answer.
	 */
	@Test
	fun `no event is reported twice by one restore`() {
		val restored = publisher(collidingStore()).restore(account)

		val ids = restored.map { it.first }
		assertEquals("every event appears once: $ids", ids.size, ids.distinct().size)
	}

	/** And the same thing seen from the card, which is where it would have shown. */
	@Test
	fun `the card never ends up showing a contested Snap as posted`() {
		val store = collidingStore()
		val hive = Hive()
		val posts = SnapPostController(
			scope = CoroutineScope(Dispatchers.Unconfined),
			publisher = { publisher(store, hive) },
			account = { account },
			io = Dispatchers.Unconfined,
			postedSnaps = { PostedSnaps(store, DeadReader) },
		)

		posts.resumePending()

		assertTrue(
			"the contested card must stay uncertain",
			posts.status(SnapDraftKey.of(account, contested)) is SnapPostStatus.Uncertain,
		)
		assertNull(
			"and it must have no posted body to show",
			posts.posted(SnapDraftKey.of(account, contested)),
		)
		assertEquals(
			"the untouched row is unaffected",
			SnapPostStatus.Posted("$account/rustedwax-snap-1000-$healthy"),
			posts.status(SnapDraftKey.of(account, healthy)),
		)
	}

	// ── what must NOT change ───────────────────────────────────────────

	@Test
	fun `an unaffected confirmed row still enumerates and restores`() {
		val store = collidingStore()

		assertEquals(listOf(healthy), store.all(account).map { it.eventId })
		assertEquals(
			SnapPublisher.Outcome.Published("$account/rustedwax-snap-1000-$healthy", "tx-$healthy"),
			publisher(store).restore(account).single { it.first == healthy }.second,
		)
	}

	@Test
	fun `two legitimate records both survive`() {
		val store = Store()
		store.saved["$account|$healthy"] = record(eventId = healthy).toJson()
		store.saved["$account|event-d"] = record(eventId = "event-d").toJson()

		assertEquals(emptySet<String>(), store.corruptEventIds(account))
		assertEquals(
			listOf(healthy, "event-d"),
			store.all(account).map { it.eventId }.sorted(),
		)
		assertEquals(2, publisher(store).restoreLocal(account).size)
	}

	/**
	 * `SharedPreferences.getAll()` is a hash map, so which of the two colliding
	 * entries is reached first is arbitrary. The verdict must not be.
	 */
	@Test
	fun `which colliding entry is seen first cannot change the verdict`() {
		listOf(
			listOf(damagedKey, contested, healthy),
			listOf(contested, damagedKey, healthy),
			listOf(healthy, contested, damagedKey),
			listOf(contested, healthy, damagedKey),
		).forEach { order ->
			val store = collidingStore(order)

			assertEquals("$order", listOf(healthy), store.all(account).map { it.eventId })
			assertEquals(
				"$order: the contested event stays locked",
				setOf(damagedKey, contested),
				store.corruptEventIds(account),
			)
			assertEquals(
				"$order: restoreLocal stays clean",
				listOf(healthy),
				publisher(store).restoreLocal(account).map { it.first },
			)
		}
	}

	/** A collision inside one account says nothing about anybody else's rows. */
	@Test
	fun `another account's records are untouched by this account's collision`() {
		val store = collidingStore()
		store.saved["bob|$contested"] = record(eventId = contested, account = "bob").toJson()

		assertEquals(emptySet<String>(), store.corruptEventIds("bob"))
		assertEquals(listOf(contested), store.all("bob").map { it.eventId })
		assertTrue(store.read("bob", contested) is PendingSnapRead.Present)
		// And alice's verdict is unchanged by bob existing.
		assertEquals(setOf(damagedKey, contested), store.corruptEventIds(account))
		assertEquals(listOf(healthy), store.all(account).map { it.eventId })
	}

	// ── restore stays read-only ────────────────────────────────────────

	@Test
	fun `restoring a collided account prepares, signs and broadcasts nothing`() {
		val store = collidingStore()
		val hive = Hive()

		publisher(store, hive).restoreLocal(account)
		publisher(store, hive).restore(account)

		assertEquals("no transaction prepared", emptyList<TxSerializer.CommentOp>(), hive.prepared)
		assertEquals("nothing broadcast", emptyList<PreparedHiveTransaction>(), hive.broadcast)
		assertEquals("no record rewritten", emptyList<PendingSnap>(), store.writes)
	}

	/** Local enumeration in particular must not reach the network at all. */
	@Test
	fun `restoreLocal asks the chain nothing`() {
		val store = collidingStore()
		val hive = Hive()

		publisher(store, hive).restoreLocal(account)

		assertEquals(emptyList<String>(), hive.contentQueries)
		assertEquals(emptyList<PendingSnap>(), store.writes)
	}

	private object DeadReader : PostedSnapReader {
		override fun read(author: String, permlink: String): PostedSnapContent? =
			throw IllegalStateException("no restore test may read the chain for a card")
	}
}
