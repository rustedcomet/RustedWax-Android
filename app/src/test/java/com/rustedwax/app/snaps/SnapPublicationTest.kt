package com.rustedwax.app.snaps

import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.SnapContainer
import com.rustedwax.hive.SnapContainerResolver
import com.rustedwax.hive.TxSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that stop one tap becoming two permanent public comments.
 *
 * Everything is scripted through [FakeHive]; nothing touches the network, and —
 * since signing now lives behind the port — **no private key exists anywhere in
 * this file**. The properties pinned here are ordering (persist and verify
 * before send), identity (one permlink, forever) and restraint (no uncertain
 * outcome ever resends).
 */
class SnapPublicationTest {

	private val media = SnapMedia("8pSS6wdojqY")
	private val account = "rustedwaxtest"
	private val eventId = "event-1"

	// ── fakes ──────────────────────────────────────────────────────────

	private open class MemoryStore : PendingSnapStore {
		val saved = mutableMapOf<String, String>()
		val corrupt = mutableSetOf<String>()
		val states = mutableListOf<PendingSnapState>()
		/** Flip to simulate a disk that will not accept the record. */
		var writable = true

		/** Raw `eventId to json` entries for one account, as the real store sees them. */
		fun entries(account: String) = saved.entries
			.filter { it.key.startsWith("$account|") }
			.associate { it.key.removePrefix("$account|") to it.value }

		open override fun read(account: String, eventId: String): PendingSnapRead = when {
			"$account|$eventId" in corrupt -> PendingSnapRead.Corrupt("unreadable")
			// Same rule as SharedPreferencesPendingSnapStore: a key/record identity
			// disagreement locks both events, and is never absence.
			eventId in PendingSnapIntegrity.lockedEventIds(account, entries(account)) ->
				PendingSnapRead.Corrupt("stored Snap state is inconsistent")
			else -> saved["$account|$eventId"]
				?.let { PendingSnap.fromJson(it) }
				?.let(PendingSnapRead::Present)
				?: PendingSnapRead.Absent
		}

		override fun write(snap: PendingSnap): Boolean {
			if (!writable) return false
			saved["${snap.account}|${snap.eventId}"] = snap.toJson()
			states += snap.state
			return true
		}

		override fun clear(account: String, eventId: String) {
			saved.remove("$account|$eventId")
		}

		override fun all(account: String) = PendingSnapIntegrity.valid(account, entries(account))

		override fun corruptEventIds(account: String) = corrupt
			.filter { it.startsWith("$account|") }
			.map { it.removePrefix("$account|") }
			.toSet() + PendingSnapIntegrity.lockedEventIds(account, entries(account))
	}

	private class FakeHive(
		var broadcastResults: MutableList<HiveRpc.BroadcastResult> = mutableListOf(),
		var evidence: HiveRpc.TransactionEvidence = HiveRpc.TransactionEvidence.UNAVAILABLE,
		var content: Boolean? = false,
		var container: SnapContainerResolver.Result = SnapContainerResolver.Result.Resolved(
			SnapContainer("peak.snaps", "snap-container-1789648560", "2026-09-17T12:36:00"),
		),
	) : SnapHivePort {
		var broadcasts = 0
		var prepares = 0
		val preparedOps = mutableListOf<TxSerializer.CommentOp>()

		override fun resolveContainer() = container

		override fun prepareComment(operation: TxSerializer.CommentOp): HivePreparationResult {
			prepares++
			preparedOps += operation
			return HivePreparationResult.Ready(
				PreparedHiveTransaction(
					signedTransactionJson = """{"op":"${operation.permlink}"}""",
					txId = "tx-${operation.permlink}",
					expirationEpochSec = 2_000_000_000L,
				),
			)
		}

		override fun broadcastPrepared(prepared: PreparedHiveTransaction): HiveRpc.BroadcastResult {
			broadcasts++
			return broadcastResults.removeFirstOrNull()
				?: HiveRpc.BroadcastResult.NetworkFailure("no scripted result")
		}

		override fun observeTransaction(txId: String, expirationEpochSec: Long) = evidence
		override fun contentExists(author: String, permlink: String) = content
	}

	private fun publisher(
		hive: FakeHive,
		store: MemoryStore,
		permlinks: MutableList<String> = mutableListOf("rustedwax-snap-1000-aaaaaa"),
	) = SnapPublisher(
		hive = hive,
		store = store,
		nowEpochSec = { 1_000L },
		newPermlink = { permlinks.removeFirstOrNull() ?: "rustedwax-snap-exhausted" },
	)

	private fun inBlock() =
		HiveRpc.BroadcastResult.Success("tx", "node", HiveRpc.BroadcastResult.Evidence.BLOCK)

	private fun inMempool() =
		HiveRpc.BroadcastResult.Success("tx", "node", HiveRpc.BroadcastResult.Evidence.MEMPOOL)

	private fun present(store: MemoryStore) =
		(store.read(account, eventId) as PendingSnapRead.Present).snap

	// ── the v1 body contract ───────────────────────────────────────────

	@Test
	fun `signs the frozen v1 body and nothing else`() {
		val store = MemoryStore()
		val hive = FakeHive(mutableListOf(inBlock()))
		publisher(hive, store).publishRoot(account, eventId, media, "what a groove")

		assertEquals(
			"what a groove\n\nhttps://youtu.be/8pSS6wdojqY\n\n#scrobblelife #scrobble #rustedwax",
			hive.preparedOps.single().body,
		)
		assertEquals("", hive.preparedOps.single().title)
	}

	/** No artist, no title, no kind — History membership is the whole gate. */
	@Test
	fun `posts a row that has no artist title or kind`() {
		val store = MemoryStore()
		val hive = FakeHive(mutableListOf(inBlock()))
		// SnapMedia carries only a video id; there is nothing else to omit.
		val outcome = publisher(hive, store).publishRoot(account, eventId, media, "nice")
		assertTrue(outcome is SnapPublisher.Outcome.Published)
		val body = hive.preparedOps.single().body
		assertTrue("no auto header may be inserted", !body.contains("—"))
		assertTrue(!body.contains("**"))
	}

	@Test
	fun `signs metadata with no media description at all`() {
		val store = MemoryStore()
		publisher(FakeHive(mutableListOf(inBlock())), store)
			.publishRoot(account, eventId, media, "nice")
		val meta = present(store).jsonMetadata
		listOf("zingit", "artist", "title", "kind", "isrc", "timed_comment", "source_url")
			.forEach { assertTrue("metadata must not carry $it", !meta.contains(it)) }
	}

	// ── prepare → persist → verify → broadcast ─────────────────────────

	@Test
	fun `persists the exact prepared transaction before broadcasting`() {
		val store = MemoryStore()
		publisher(FakeHive(mutableListOf(inBlock())), store)
			.publishRoot(account, eventId, media, "nice")

		val saved = present(store)
		assertEquals("rustedwax-snap-1000-aaaaaa", saved.permlink)
		assertEquals("peak.snaps", saved.parentAuthor)
		assertEquals("snap-container-1789648560", saved.parentPermlink)
		assertEquals("""{"op":"rustedwax-snap-1000-aaaaaa"}""", saved.signedTransactionJson)
		assertEquals("tx-rustedwax-snap-1000-aaaaaa", saved.txId)
		assertEquals(
			listOf(
				PendingSnapState.PREPARED,
				PendingSnapState.BROADCASTING,
				PendingSnapState.CONFIRMED,
			),
			store.states,
		)
	}

	/** If the record cannot be made durable, nothing may cross the network. */
	@Test
	fun `a persistence failure prevents any broadcast`() {
		val store = MemoryStore().apply { writable = false }
		val hive = FakeHive(mutableListOf(inBlock()))
		val outcome = publisher(hive, store).publishRoot(account, eventId, media, "nice")

		assertTrue(outcome is SnapPublisher.Outcome.Failed)
		assertTrue((outcome as SnapPublisher.Outcome.Failed).message.contains("draft is still here"))
		assertEquals("nothing may be sent without durable state", 0, hive.broadcasts)
	}

	/** A record that stores but reads back wrong is also not durable. */
	@Test
	fun `a record that does not read back prevents any broadcast`() {
		val hive = FakeHive(mutableListOf(inBlock()))
		val store = object : MemoryStore() {
			override fun read(account: String, eventId: String) = PendingSnapRead.Absent
		}
		val outcome = publisher(hive, store).publishRoot(account, eventId, media, "nice")
		assertTrue(outcome is SnapPublisher.Outcome.Failed)
		assertEquals(0, hive.broadcasts)
	}

	// ── corrupt state locks the row ────────────────────────────────────

	@Test
	fun `corrupt pending state mints no permlink and sends nothing`() {
		val store = MemoryStore().apply { corrupt += "$account|$eventId" }
		val permlinks = mutableListOf("rustedwax-snap-1000-aaaaaa")
		val hive = FakeHive(mutableListOf(inBlock()))

		val outcome = publisher(hive, store, permlinks).publishRoot(account, eventId, media, "x")

		assertTrue(outcome is SnapPublisher.Outcome.Uncertain)
		assertEquals("no permlink may be minted over corrupt state", 1, permlinks.size)
		assertEquals(0, hive.prepares)
		assertEquals(0, hive.broadcasts)
	}

	@Test
	fun `corrupt state stays locked on recheck`() {
		val store = MemoryStore().apply { corrupt += "$account|$eventId" }
		val hive = FakeHive()
		val outcome = publisher(hive, store).reconcile(account, eventId)
		assertTrue(outcome is SnapPublisher.Outcome.Uncertain)
		assertEquals(0, hive.broadcasts)
	}

	// ── mempool is not publication ─────────────────────────────────────

	@Test
	fun `a mempool broadcast is pending, never posted`() {
		val store = MemoryStore()
		val hive = FakeHive(mutableListOf(inMempool()), evidence = HiveRpc.TransactionEvidence.MEMPOOL)
		val outcome = publisher(hive, store).publishRoot(account, eventId, media, "x")

		assertTrue("mempool is not publication", outcome is SnapPublisher.Outcome.Uncertain)
		assertTrue(present(store).isUncertain)
		assertTrue(present(store).state != PendingSnapState.CONFIRMED)
	}

	@Test
	fun `mempool reconciliation stays pending`() {
		val store = MemoryStore()
		val hive = FakeHive(
			mutableListOf(HiveRpc.BroadcastResult.AcceptedUnconfirmed("tx", "n", "?")),
			evidence = HiveRpc.TransactionEvidence.MEMPOOL,
		)
		assertTrue(
			publisher(hive, store).publishRoot(account, eventId, media, "x")
				is SnapPublisher.Outcome.Uncertain,
		)
	}

	// ── uncertainty never resends ──────────────────────────────────────

	private fun assertNoResendOn(result: HiveRpc.BroadcastResult, label: String) {
		val store = MemoryStore()
		val hive = FakeHive(mutableListOf(result))
		val pub = publisher(hive, store)

		assertTrue("$label must be uncertain",
			pub.publishRoot(account, eventId, media, "a") is SnapPublisher.Outcome.Uncertain)
		assertEquals("$label broadcast once", 1, hive.broadcasts)

		assertTrue("$label stays uncertain",
			pub.publishRoot(account, eventId, media, "a") is SnapPublisher.Outcome.Uncertain)
		assertEquals("$label must never rebroadcast", 1, hive.broadcasts)
		assertEquals("$label must not re-prepare", 1, hive.prepares)
	}

	@Test
	fun `a lost response never resends`() {
		assertNoResendOn(
			HiveRpc.BroadcastResult.AcceptedUnconfirmed("tx", "node", "no confirmation"),
			"AcceptedUnconfirmed",
		)
	}

	@Test
	fun `a deferred result never resends`() {
		assertNoResendOn(HiveRpc.BroadcastResult.Deferred("not in a block after 15s"), "Deferred")
	}

	@Test
	fun `a network failure never resends`() {
		assertNoResendOn(HiveRpc.BroadcastResult.NetworkFailure("all nodes unreachable"), "NetworkFailure")
	}

	/**
	 * A rejection may come from the second node after the first one's reply was
	 * lost, so it is reconciled — never treated as a clean retryable failure.
	 */
	@Test
	fun `a rejection is reconciled rather than marked failed`() {
		assertNoResendOn(HiveRpc.BroadcastResult.Rejected("duplicate"), "Rejected")
	}

	@Test
	fun `a rejection whose content is on chain is a publication`() {
		val store = MemoryStore()
		val hive = FakeHive(mutableListOf(HiveRpc.BroadcastResult.Rejected("duplicate")), content = true)
		assertTrue(
			publisher(hive, store).publishRoot(account, eventId, media, "a")
				is SnapPublisher.Outcome.Published,
		)
	}

	@Test
	fun `an exception on the way out is uncertain`() {
		val store = MemoryStore()
		val hive = object : SnapHivePort by FakeHive() {
			override fun broadcastPrepared(prepared: PreparedHiveTransaction): Nothing =
				throw RuntimeException("socket died")
		}
		val outcome = SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
			.publishRoot(account, eventId, media, "a")
		assertTrue(outcome is SnapPublisher.Outcome.Uncertain)
		assertTrue(present(store).isUncertain)
	}

	/** Only proven absence of both transaction and content clears a row. */
	@Test
	fun `only proven absence allows another attempt`() {
		val store = MemoryStore()
		val hive = FakeHive(
			mutableListOf(HiveRpc.BroadcastResult.AcceptedUnconfirmed("tx", "n", "?")),
			evidence = HiveRpc.TransactionEvidence.ABSENT,
			content = false,
		)
		val pub = publisher(hive, store)
		assertTrue(pub.publishRoot(account, eventId, media, "a") is SnapPublisher.Outcome.Failed)
		assertEquals(PendingSnapState.FAILED, present(store).state)

		hive.broadcastResults.add(inBlock())
		assertTrue(pub.publishRoot(account, eventId, media, "a") is SnapPublisher.Outcome.Published)
		assertEquals("the permlink must never change", "rustedwax-snap-1000-aaaaaa",
			present(store).permlink)
	}

	@Test
	fun `never mints a second permlink for the same History row`() {
		val store = MemoryStore()
		val permlinks = mutableListOf("rustedwax-snap-1000-first", "rustedwax-snap-1000-second")
		val hive = FakeHive(
			mutableListOf(
				HiveRpc.BroadcastResult.AcceptedUnconfirmed("tx", "n", "?"),
				inBlock(),
			),
			evidence = HiveRpc.TransactionEvidence.ABSENT,
		)
		val pub = publisher(hive, store, permlinks)
		pub.publishRoot(account, eventId, media, "a")
		pub.publishRoot(account, eventId, media, "a")

		assertEquals("rustedwax-snap-1000-first", present(store).permlink)
		assertTrue(hive.preparedOps.all { it.permlink == "rustedwax-snap-1000-first" })
		assertEquals("the second permlink must be untouched", 1, permlinks.size)
	}

	// ── startup restoration ────────────────────────────────────────────

	@Test
	fun `restores confirmed pending and corrupt rows without broadcasting`() {
		val store = MemoryStore()
		// One confirmed, one uncertain, one corrupt.
		publisher(FakeHive(mutableListOf(inBlock())), store,
			mutableListOf("rustedwax-snap-1000-done")).publishRoot(account, "done", media, "a")
		publisher(FakeHive(mutableListOf(HiveRpc.BroadcastResult.NetworkFailure("x"))), store,
			mutableListOf("rustedwax-snap-1000-flight")).publishRoot(account, "inflight", media, "b")
		store.corrupt += "$account|broken"

		val revived = FakeHive(content = true)
		val restored = publisher(revived, store).restore(account).toMap()

		assertTrue(restored["done"] is SnapPublisher.Outcome.Published)
		assertTrue(restored["inflight"] is SnapPublisher.Outcome.Published)
		assertTrue(restored["broken"] is SnapPublisher.Outcome.Uncertain)
		assertEquals("launch must never broadcast", 0, revived.broadcasts)
		assertEquals(0, revived.prepares)
	}

	// ── account isolation ──────────────────────────────────────────────

	@Test
	fun `one account never sees or resends another account's pending Snap`() {
		val store = MemoryStore()
		publisher(FakeHive(mutableListOf(HiveRpc.BroadcastResult.NetworkFailure("x"))), store)
			.publishRoot("alice", eventId, media, "alice's words")

		assertEquals(PendingSnapRead.Absent, store.read("bob", eventId))
		assertTrue(store.all("bob").isEmpty())

		val hive = FakeHive()
		assertTrue(publisher(hive, store).restore("bob").isEmpty())
		assertEquals(0, hive.broadcasts)
	}

	// ── refusals before the network ────────────────────────────────────

	@Test
	fun `refuses a malformed or missing Snap container`() {
		val store = MemoryStore()
		val hive = FakeHive(container = SnapContainerResolver.Result.Unavailable("no container"))
		val outcome = publisher(hive, store).publishRoot(account, eventId, media, "a")
		assertTrue(outcome is SnapPublisher.Outcome.Failed)
		assertEquals("nothing may be prepared without a parent", 0, hive.prepares)
		assertEquals(PendingSnapRead.Absent, store.read(account, eventId))
	}

	@Test
	fun `refuses a row with no usable video id`() {
		val store = MemoryStore()
		val hive = FakeHive()
		listOf("", "   ", "abc/../def", "watch?v=abc").forEach { bad ->
			val outcome = publisher(hive, store)
				.publishRoot(account, eventId, SnapMedia(bad), "a")
			assertTrue("must refuse '$bad'", outcome is SnapPublisher.Outcome.Failed)
		}
		assertEquals(0, hive.prepares)
		assertEquals(0, hive.broadcasts)
	}

	@Test
	fun `a confirmed Snap is never posted twice`() {
		val store = MemoryStore()
		val hive = FakeHive(mutableListOf(inBlock()))
		val pub = publisher(hive, store)
		pub.publishRoot(account, eventId, media, "a")
		repeat(3) { pub.publishRoot(account, eventId, media, "a") }
		assertEquals("one root Snap per History event", 1, hive.broadcasts)
	}

	@Test
	fun `stores no key material`() {
		val store = MemoryStore()
		publisher(FakeHive(mutableListOf(inBlock())), store)
			.publishRoot(account, eventId, media, "a")
		val raw = store.saved.values.single()
		listOf("5J", "5K", "5H", "wif", "privateKey").forEach {
			assertTrue("record must not contain $it", !raw.contains(it))
		}
		assertNull(PendingSnap.fromJson("not json"))
	}

	// ── key / record identity mismatch ─────────────────────────────────

	/**
	 * A record filed under event A that claims event B locks *both*. Neither may
	 * prepare, mint a permlink or broadcast, and a restart must not soften that.
	 */
	private fun storeWithMismatch(): MemoryStore {
		val store = MemoryStore()
		val stray = PendingSnap(
			account = account,
			eventId = "event-b",
			author = account,
			permlink = "rustedwax-snap-1000-stray",
			parentAuthor = "peak.snaps",
			parentPermlink = "snap-container-1789648560",
			body = "body",
			jsonMetadata = "{}",
			signedTransactionJson = """{"op":1}""",
			txId = "tx-stray",
			expirationEpochSec = 2_000_000_000L,
			state = PendingSnapState.ACCEPTED_UNCONFIRMED,
			createdAtEpochSec = 1_000L,
			updatedAtEpochSec = 1_000L,
		)
		// Filed under event-a, but claiming event-b.
		store.saved["$account|event-a"] = stray.toJson()
		return store
	}

	@Test
	fun `a key and record mismatch is detected as corruption, not absence`() {
		val store = storeWithMismatch()
		assertTrue(store.read(account, "event-a") is PendingSnapRead.Corrupt)
		assertTrue(
			"the claimed event must not look absent",
			store.read(account, "event-b") is PendingSnapRead.Corrupt,
		)
	}

	@Test
	fun `neither implicated event can prepare, mint or broadcast`() {
		listOf("event-a", "event-b").forEach { implicated ->
			val store = storeWithMismatch()
			val permlinks = mutableListOf("rustedwax-snap-1000-fresh")
			val hive = FakeHive(mutableListOf(inBlock()))

			val outcome = publisher(hive, store, permlinks)
				.publishRoot(account, implicated, media, "x")

			assertTrue("$implicated must be locked", outcome is SnapPublisher.Outcome.Uncertain)
			assertEquals("$implicated must mint no permlink", 1, permlinks.size)
			assertEquals("$implicated must not prepare", 0, hive.prepares)
			assertEquals("$implicated must not broadcast", 0, hive.broadcasts)
		}
	}

	/** "Check again" must not convert the inconsistency into retry eligibility. */
	@Test
	fun `recheck keeps both implicated events locked`() {
		listOf("event-a", "event-b").forEach { implicated ->
			val store = storeWithMismatch()
			val hive = FakeHive(content = false)
			val outcome = publisher(hive, store).reconcile(account, implicated)
			assertTrue("$implicated must stay locked", outcome is SnapPublisher.Outcome.Uncertain)
			assertEquals(0, hive.broadcasts)
		}
	}

	@Test
	fun `a restart preserves the lock and writes nothing to the network`() {
		val store = storeWithMismatch()
		val hive = FakeHive(mutableListOf(inBlock()))
		val restored = publisher(hive, store).restore(account).toMap()

		assertTrue("event-a" in restored.keys)
		assertTrue("event-b" in restored.keys)
		restored.values.forEach { assertTrue(it is SnapPublisher.Outcome.Uncertain) }
		assertEquals("restore must never broadcast", 0, hive.broadcasts)
		assertEquals(0, hive.prepares)
	}

	/** The inconsistent record is the evidence; nothing may tidy it away. */
	@Test
	fun `the corrupt record is preserved`() {
		val store = storeWithMismatch()
		val before = store.saved.toMap()
		publisher(FakeHive(), store).restore(account)
		publisher(FakeHive(), store).publishRoot(account, "event-a", media, "x")
		assertEquals("corrupt evidence must survive", before, store.saved)
	}
}
