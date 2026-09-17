package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.PendingSnapRead
import com.rustedwax.app.snaps.PendingSnapStore
import com.rustedwax.app.snaps.PendingSnap
import com.rustedwax.app.snaps.SnapHivePort
import com.rustedwax.app.snaps.SnapMedia
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card-level safety: duplicate taps, and acting under the right account.
 *
 * Runs unconfined so `launch` completes inline; no coroutine-test dependency is
 * added for this.
 */
class SnapPostControllerTest {

	private val media = SnapMedia("8pSS6wdojqY")
	private val eventId = "event-1"

	private class Store : PendingSnapStore {
		val saved = mutableMapOf<String, String>()
		override fun read(account: String, eventId: String) =
			saved["$account|$eventId"]?.let { PendingSnap.fromJson(it) }
				?.let(PendingSnapRead::Present) ?: PendingSnapRead.Absent
		override fun write(snap: PendingSnap): Boolean {
			saved["${snap.account}|${snap.eventId}"] = snap.toJson(); return true
		}
		override fun clear(account: String, eventId: String) { saved.remove("$account|$eventId") }
		override fun all(account: String) = saved.entries
			.filter { it.key.startsWith("$account|") }.mapNotNull { PendingSnap.fromJson(it.value) }
		override fun corruptEventIds(account: String) = emptySet<String>()
	}

	private class Hive(var result: HiveRpc.BroadcastResult) : SnapHivePort {
		var broadcasts = 0
		var contentChecks = 0
		override fun resolveContainer() = SnapContainerResolver.Result.Resolved(
			SnapContainer("peak.snaps", "snap-container-1789648560", "2026-09-17T12:36:00"),
		)
		override fun prepareComment(operation: TxSerializer.CommentOp) =
			HivePreparationResult.Ready(
				PreparedHiveTransaction("""{"op":1}""", "tx-1", 2_000_000_000L),
			)
		override fun broadcastPrepared(prepared: PreparedHiveTransaction) =
			result.also { broadcasts++ }
		override fun observeTransaction(txId: String, expirationEpochSec: Long) =
			HiveRpc.TransactionEvidence.UNAVAILABLE
		override fun contentExists(author: String, permlink: String): Boolean? {
			contentChecks++
			return false
		}
	}

	private fun controller(
		hive: Hive,
		store: Store,
		account: () -> String?,
	): SnapPostController {
		val publisher = SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
		return SnapPostController(
			scope = CoroutineScope(Dispatchers.Unconfined),
			publisher = { publisher },
			account = account,
			io = Dispatchers.Unconfined,
		)
	}

	private fun inBlock() =
		HiveRpc.BroadcastResult.Success("tx", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)

	@Test
	fun `posts once and reports the published state`() {
		val hive = Hive(inBlock())
		val posts = controller(hive, Store()) { "alice" }
		val key = SnapDraftKey.of("alice", eventId)
		var published: String? = null

		posts.post(key, eventId, media, "hello") { published = it }

		assertEquals(1, hive.broadcasts)
		assertEquals("alice/rustedwax-snap-1000-aaaaaa", published)
		assertTrue(posts.status(key) is SnapPostStatus.Posted)
	}

	/** The draft is only cleared once Hive has confirmed. */
	@Test
	fun `does not report published when the outcome is uncertain`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val posts = controller(hive, Store()) { "alice" }
		val key = SnapDraftKey.of("alice", eventId)
		var published: String? = null

		posts.post(key, eventId, media, "hello") { published = it }

		assertEquals(null, published)
		assertTrue(posts.status(key) is SnapPostStatus.Uncertain)
	}

	/**
	 * Switching accounts with a composer open must not post the previous
	 * account's draft — the key is checked against whoever is signed in *now*.
	 */
	@Test
	fun `refuses to post a draft belonging to another account`() {
		val hive = Hive(inBlock())
		var signedIn = "alice"
		val posts = controller(hive, Store()) { signedIn }
		val aliceKey = SnapDraftKey.of("alice", eventId)

		signedIn = "bob"
		posts.post(aliceKey, eventId, media, "alice's words") {}

		assertEquals("nothing may be broadcast after the switch", 0, hive.broadcasts)
		val status = posts.status(aliceKey)
		assertTrue(status is SnapPostStatus.Failed)
		assertTrue((status as SnapPostStatus.Failed).message.contains("switched Hive accounts"))
	}

	/** "Check again" is a read, but it still must not run under a stale key. */
	@Test
	fun `refuses to recheck a draft belonging to another account`() {
		val hive = Hive(inBlock())
		var signedIn = "alice"
		val posts = controller(hive, Store()) { signedIn }
		val aliceKey = SnapDraftKey.of("alice", eventId)

		signedIn = "bob"
		posts.recheck(aliceKey, eventId)

		assertEquals("no chain read may run under a stale key", 0, hive.contentChecks)
		val status = posts.status(aliceKey)
		assertTrue(status is SnapPostStatus.Failed)
		assertTrue((status as SnapPostStatus.Failed).message.contains("switched Hive accounts"))
	}

	@Test
	fun `refuses to post with nobody signed in`() {
		val hive = Hive(inBlock())
		val posts = controller(hive, Store()) { null }
		posts.post(SnapDraftKey.of(null, eventId), eventId, media, "hello") {}
		assertEquals(0, hive.broadcasts)
	}

	/** Restoration reads; it never sends. */
	@Test
	fun `resuming pending state broadcasts nothing`() {
		val store = Store()
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val posts = controller(hive, store) { "alice" }
		posts.post(SnapDraftKey.of("alice", eventId), eventId, media, "hello") {}
		val after = hive.broadcasts

		posts.resumePending()

		assertEquals("launch must not rebroadcast", after, hive.broadcasts)
	}
}
