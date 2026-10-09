package com.rustedwax.app.snaps

import com.rustedwax.hive.HiveCommentRead
import com.rustedwax.hive.HiveCommentState
import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.SnapContainerResolver
import com.rustedwax.hive.TxSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Issue #56, 3C: an unsettled Edit or Delete survives process death.
 *
 * A "restart" is what a new process builds: a new [SnapWriteGuards] over the
 * same [SnapAttemptStore] contents, and a new editor and deleter. Nothing in
 * memory is carried over. The fake port signs nothing real and reaches no
 * network; every assertion counts what was signed.
 */
class SnapWriteGuardsDurabilityTest {

	private val account = "rustedwaxtest"
	private val root = SnapReplyTarget.of(account, "rustedwax-snap-1000-bbbbbb")!!
	private val reply = SnapReplyTarget.of(account, "rustedwax-reply-1000-aaaaaa")!!
	private val other = SnapReplyTarget.of(account, "rustedwax-reply-1001-cccccc")!!
	private val bobs = SnapReplyTarget.of("bobtest", "rustedwax-reply-1002-dddddd")!!
	private val tail = "\n\nhttps://youtu.be/rTKpYJ80OVQ\n\n#scrobblelife #scrobble #rustedwax"

	/** Stands for the process being killed at an exact point. Not an Exception, so no runCatching hides it. */
	private class Crash : Error("process died here")

	/** What survives the process: strings on "disk". */
	private class Disk : SnapAttemptStore {
		val rows = ConcurrentHashMap<String, String>()
		@Volatile var unreadable = false
		@Volatile var saveFails = false
		@Volatile var crashOnRemove = false
		/** Removal reports failure and leaves the row, as a full disk would. */
		@Volatile var removeFails = false
		/** Removal throws an ordinary exception and leaves the row. */
		@Volatile var removeThrows = false
		override fun load(): Map<String, String>? = if (unreadable) null else HashMap(rows)
		override fun save(key: String, value: String): Boolean {
			if (saveFails) return false
			rows[key] = value
			return true
		}
		override fun remove(key: String): Boolean {
			if (crashOnRemove) {
				crashOnRemove = false
				throw Crash()
			}
			if (removeFails) return false
			if (removeThrows) throw IllegalStateException("disk said no")
			rows.remove(key)
			return true
		}
	}

	private inner class Chain : SnapHivePort {
		val comments = ConcurrentHashMap<String, SnapChainComment>()
		val evidence = ConcurrentHashMap<String, HiveRpc.TransactionEvidence>()
		val signed: MutableList<PreparedHiveTransaction> = Collections.synchronizedList(mutableListOf())
		val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())
		@Volatile var result: HiveRpc.BroadcastResult = HiveRpc.BroadcastResult.NetworkFailure("lost")
		@Volatile var finality: Long? = null
		private var n = 0

		init {
			comments[root.contentId] = SnapChainComment(account, root.permlink, "peak.snaps", "snap-container-1", "", "first words$tail", "{}", 2_000_000_100L)
			comments[reply.contentId] = SnapChainComment(account, reply.permlink, account, root.permlink, "", "reply words", "{}", 2_000_000_100L)
			comments[other.contentId] = SnapChainComment(account, other.permlink, account, root.permlink, "", "other words", "{}", 2_000_000_100L)
			comments[bobs.contentId] = SnapChainComment("bobtest", bobs.permlink, account, root.permlink, "", "bob words", "{}", 2_000_000_100L)
		}

		@Synchronized private fun next(prefix: String) =
			PreparedHiveTransaction("""{"synthetic":"$prefix-${n + 1}"}""", "$prefix-${++n}", 2_000_000_060L).also { signed += it }

		override fun resolveContainer(): SnapContainerResolver.Result = error("unused")
		override fun prepareComment(operation: TxSerializer.CommentOp, author: String) = HivePreparationResult.Ready(next("edit"))
		override fun prepareDelete(operation: TxSerializer.DeleteCommentOp, author: String) = HivePreparationResult.Ready(next("del"))
		override fun broadcastPrepared(prepared: PreparedHiveTransaction, author: String): HiveRpc.BroadcastResult {
			sent += prepared.txId
			return result
		}
		override fun observeTransaction(txId: String, expirationEpochSec: Long) =
			evidence[txId] ?: HiveRpc.TransactionEvidence.UNAVAILABLE
		override fun irreversiblyPast(expirationEpochSec: Long) = finality?.takeIf { it > expirationEpochSec }
		override fun contentExists(author: String, permlink: String): Boolean? = comments.containsKey("$author/$permlink")
		override fun readComment(author: String, permlink: String) = comments["$author/$permlink"]
		override fun readCommentState(author: String, permlink: String, limit: Int): List<HiveCommentRead> =
			(0 until minOf(limit, 2)).map { i ->
				comments["$author/$permlink"]?.let {
					HiveCommentRead.Present(
						HiveCommentState(it.author, it.permlink, it.parentAuthor, it.parentPermlink, 0, 0, 4_000_000_000L, 2_000_000_100L),
						"n$i",
					)
				} ?: HiveCommentRead.Absent("n$i", 2_000_000_100L)
			}
	}

	private class Store : PendingSnapStore {
		val saved = ConcurrentHashMap<String, PendingSnap>()
		override fun read(account: String, eventId: String): PendingSnapRead =
			saved["$account|$eventId"]?.let(PendingSnapRead::Present) ?: PendingSnapRead.Absent
		override fun write(snap: PendingSnap): Boolean { saved["${snap.account}|${snap.eventId}"] = snap; return true }
		override fun clear(account: String, eventId: String) { saved.remove("$account|$eventId") }
		override fun all(account: String) = saved.filterKeys { it.startsWith("$account|") }.values.toList()
		override fun corruptEventIds(account: String) = emptySet<String>()
	}

	private fun confirmedRoot(body: String) = PendingSnap(
		account = account, eventId = "event-1", author = account, permlink = root.permlink,
		parentAuthor = "peak.snaps", parentPermlink = "snap-container-1", body = body, jsonMetadata = "{}",
		signedTransactionJson = "{}", txId = "tx-original", expirationEpochSec = 1L,
		state = PendingSnapState.CONFIRMED, createdAtEpochSec = 1L, updatedAtEpochSec = 1L, kind = PendingSnapKind.ROOT,
	)

	/** One process: a holder restored from [disk], and the editor and deleter built over it. */
	private inner class Process(val disk: Disk, val chain: Chain, val store: Store = Store()) {
		val guards = SnapWriteGuards(disk)
		val tombstones: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
		val editor = SnapEditor(chain, store, guards = guards)
		val deleter = SnapDeleter(
			chain, store, sleep = {}, settleAttempts = 1,
			beforeRetire = { _, t, _ -> tombstones += t.contentId; true },
			guards = guards,
		)
	}

	// ── survival across a restart, both directions ─────────────────────

	@Test
	fun `an uncertain Edit survives process death, and Delete or a second Edit sign nothing`() {
		val disk = Disk()
		val chain = Chain()
		val first = Process(disk, chain)
		assertTrue(first.editor.edit(account, reply, SnapEditKind.REPLY, "first edit") is SnapEditor.Outcome.Uncertain)
		val original = chain.signed.single()

		val next = Process(disk, chain) // a new process
		val held = next.guards.current(account, reply)
		assertTrue("restored: $held", held is SnapWriteGuards.EditAttempt)
		assertEquals("exactly the original transaction", original, (held as SnapWriteGuards.EditAttempt).prepared)
		assertEquals("first edit", held.body)

		assertEquals(SnapDeleter.Outcome.Blocked(SnapDeleter.EDIT_UNSETTLED), next.deleter.delete(account, reply))
		assertTrue(next.editor.edit(account, reply, SnapEditKind.REPLY, "second edit") is SnapEditor.Outcome.Uncertain)
		assertEquals("one signature, ever", listOf(original), chain.signed)
		assertEquals(listOf(original.txId), chain.sent)
	}

	@Test
	fun `an uncertain Delete survives process death, and Edit or a second Delete sign nothing`() {
		val disk = Disk()
		val chain = Chain()
		assertTrue(Process(disk, chain).deleter.delete(account, root) is SnapDeleter.Outcome.Uncertain)
		val original = chain.signed.single()

		val next = Process(disk, chain)
		assertEquals(original, (next.guards.current(account, root) as SnapWriteGuards.DeleteAttempt).prepared)
		assertTrue(next.deleter.hasUnsettled(account, root))
		assertEquals(
			SnapEditor.Outcome.Failed(SnapEditor.DELETE_UNSETTLED),
			next.editor.edit(account, root, SnapEditKind.ROOT, "new words"),
		)
		assertTrue(next.deleter.delete(account, root) is SnapDeleter.Outcome.Uncertain)
		assertEquals(listOf(original), chain.signed)
	}

	@Test
	fun `restored locks keep root and reply apart, leave other objects and accounts free, and outlast an account switch`() {
		val disk = Disk()
		val chain = Chain()
		Process(disk, chain).deleter.delete(account, root) // del-1, uncertain

		chain.result = HiveRpc.BroadcastResult.Success("x", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)
		val asBob = Process(disk, chain) // restarted while bob is signed in
		assertTrue("bob's own reply is free", asBob.editor.edit("bobtest", bobs, SnapEditKind.REPLY, "bob edits") is SnapEditor.Outcome.Edited)

		val back = Process(disk, chain) // restarted again; alice returns
		assertTrue(back.guards.current(account, root) is SnapWriteGuards.DeleteAttempt)
		assertTrue("her reply is free", back.editor.edit(account, reply, SnapEditKind.REPLY, "reply edit") is SnapEditor.Outcome.Edited)
		assertEquals(
			SnapEditor.Outcome.Failed(SnapEditor.DELETE_UNSETTLED),
			back.editor.edit(account, root, SnapEditKind.ROOT, "root words"),
		)
		assertEquals(listOf("del-1", "edit-2", "edit-3"), chain.signed.map { it.txId })
	}

	// ── crash windows ──────────────────────────────────────────────────

	@Test
	fun `death before anything durable leaves nothing locked and nothing owed`() {
		val disk = Disk()
		val chain = Chain()
		val first = Process(disk, chain)
		assertNotNull(first.guards.reserve(account, reply, SnapWriteGuards.Operation.EDIT)) // then the process dies

		val next = Process(disk, chain)
		assertNull(next.guards.current(account, reply))
		assertTrue(disk.rows.isEmpty())
		chain.result = HiveRpc.BroadcastResult.Success("x", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)
		assertTrue(next.editor.edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Edited)
	}

	@Test
	fun `death after the attempt is saved but before it is sent keeps it locked until proof`() {
		val disk = Disk()
		val chain = Chain()
		val first = Process(disk, chain)
		val r = first.guards.reserve(account, reply, SnapWriteGuards.Operation.EDIT)!!
		val prepared = PreparedHiveTransaction("""{"synthetic":"never-sent"}""", "never-sent", 2_000_000_060L)
		assertTrue(first.guards.attach(account, reply, SnapWriteGuards.EditAttempt(r.id, SnapEditKind.REPLY, "w", "w", prepared)))
		// …and the process dies before the send.

		val next = Process(disk, chain)
		assertEquals(prepared, (next.guards.current(account, reply) as SnapWriteGuards.EditAttempt).prepared)
		assertTrue(next.editor.edit(account, reply, SnapEditKind.REPLY, "w2") is SnapEditor.Outcome.Uncertain)
		assertTrue("nothing new signed while it might be sent", chain.signed.isEmpty())

		chain.evidence["never-sent"] = HiveRpc.TransactionEvidence.ABSENT
		assertTrue(next.editor.edit(account, reply, SnapEditKind.REPLY, "w2") is SnapEditor.Outcome.Failed)
		assertTrue(disk.rows.isEmpty())
	}

	@Test
	fun `death after inclusion is proven but before local repair is finished on the next start`() {
		val disk = Disk()
		val chain = Chain()
		val store = Store().apply { write(confirmedRoot("first words$tail")) }
		assertTrue(Process(disk, chain, store).editor.edit(account, root, SnapEditKind.ROOT, "edited words") is SnapEditor.Outcome.Uncertain)
		val tx = chain.signed.single().txId
		chain.evidence[tx] = HiveRpc.TransactionEvidence.BLOCK // proven on chain; the app died before acting on it

		val next = Process(disk, chain, store)
		assertTrue(next.editor.edit(account, root, SnapEditKind.ROOT, "edited words") is SnapEditor.Outcome.Edited)
		assertEquals("edited words$tail", (store.read(account, "event-1") as PendingSnapRead.Present).snap.body)
		assertTrue(disk.rows.isEmpty())
		assertEquals(1, chain.signed.size)
	}

	@Test
	fun `death after an edit's local repair but before the lock is removed repeats only reading`() {
		val disk = Disk()
		val chain = Chain()
		val store = Store().apply { write(confirmedRoot("first words$tail")) }
		val first = Process(disk, chain, store)
		assertTrue(first.editor.edit(account, root, SnapEditKind.ROOT, "edited words") is SnapEditor.Outcome.Uncertain)
		chain.evidence[chain.signed.single().txId] = HiveRpc.TransactionEvidence.BLOCK
		disk.crashOnRemove = true
		assertCrashes { first.editor.edit(account, root, SnapEditKind.ROOT, "edited words") }
		assertEquals("repair happened before the lock went", "edited words$tail", (store.read(account, "event-1") as PendingSnapRead.Present).snap.body)
		assertTrue("the lock is still on disk", disk.rows.isNotEmpty())

		val next = Process(disk, chain, store)
		assertTrue(next.editor.edit(account, root, SnapEditKind.ROOT, "edited words") is SnapEditor.Outcome.Edited)
		assertTrue(disk.rows.isEmpty())
		assertEquals(1, chain.signed.size)
	}

	@Test
	fun `death after a delete's tombstone and retirement but before the lock is removed finishes once`() {
		val disk = Disk()
		val chain = Chain()
		val store = Store().apply { write(confirmedRoot("first words$tail")) }
		val first = Process(disk, chain, store)
		assertTrue(first.deleter.delete(account, root) is SnapDeleter.Outcome.Uncertain)
		chain.comments.remove(root.contentId) // the delete landed
		disk.crashOnRemove = true
		assertCrashes { first.deleter.delete(account, root) }
		assertEquals(setOf(root.contentId), first.tombstones)
		assertTrue("record retired before the lock went", store.saved.isEmpty())
		assertTrue(disk.rows.isNotEmpty())

		val next = Process(disk, chain, store)
		assertTrue(next.deleter.delete(account, root) is SnapDeleter.Outcome.Deleted)
		assertEquals(setOf(root.contentId), next.tombstones)
		assertTrue(disk.rows.isEmpty())
		assertEquals(1, chain.signed.size)
	}

	// ── storage that cannot be trusted ─────────────────────────────────

	@Test
	fun `an attempt that cannot be saved is never sent`() {
		val disk = Disk().apply { saveFails = true }
		val chain = Chain()
		val p = Process(disk, chain)
		assertTrue(p.editor.edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Failed)
		assertTrue(p.deleter.delete(account, root) is SnapDeleter.Outcome.Failed)
		assertTrue("nothing reached the network", chain.sent.isEmpty())
		assertNull(p.guards.current(account, reply))
		assertNull(p.guards.current(account, root))
	}

	@Test
	fun `a store that cannot be read locks every Edit and Delete`() {
		val disk = Disk().apply { unreadable = true }
		val chain = Chain()
		val p = Process(disk, chain)
		assertFalse(p.editor.edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Edited)
		assertTrue(p.deleter.delete(account, other) is SnapDeleter.Outcome.Blocked)
		assertTrue(p.deleter.check(account, root) is SnapDeleteCheck.Blocked)
		assertTrue(chain.signed.isEmpty())
	}

	@Test
	fun `a corrupt row locks its own comment and nothing else`() {
		val disk = Disk()
		disk.rows["$account|${reply.contentId}"] = "{not json"
		val chain = Chain()
		val p = Process(disk, chain)
		assertTrue(p.guards.current(account, reply) is SnapWriteGuards.Locked)
		assertFalse(p.editor.edit(account, reply, SnapEditKind.REPLY, "new") is SnapEditor.Outcome.Edited)
		assertTrue(p.deleter.delete(account, reply) is SnapDeleter.Outcome.Blocked)
		assertTrue(chain.signed.isEmpty())
		chain.result = HiveRpc.BroadcastResult.Success("x", "n", HiveRpc.BroadcastResult.Evidence.BLOCK)
		assertTrue(p.editor.edit(account, other, SnapEditKind.REPLY, "free") is SnapEditor.Outcome.Edited)
		assertTrue("the corrupt row is never cleaned up on its own", disk.rows.containsKey("$account|${reply.contentId}"))
	}

	@Test
	fun `a row filed under the wrong identity locks both identities`() {
		val disk = Disk()
		val chain = Chain()
		Process(disk, chain).deleter.delete(account, root) // a real row for root
		val rootRow = disk.rows.values.single()
		disk.rows.clear()
		disk.rows["$account|${reply.contentId}"] = rootRow // filed under reply

		val p = Process(disk, chain)
		assertTrue(p.guards.current(account, reply) is SnapWriteGuards.Locked)
		assertTrue(p.guards.current(account, root) is SnapWriteGuards.Locked)
		assertEquals(1, chain.signed.size)
		p.editor.edit(account, reply, SnapEditKind.REPLY, "x")
		p.deleter.delete(account, root)
		assertEquals("still only the first", 1, chain.signed.size)
	}

	@Test
	fun `a key that names no identity locks everything`() {
		val disk = Disk()
		disk.rows["garbage-without-identity"] = "{}"
		val chain = Chain()
		val p = Process(disk, chain)
		assertTrue(p.guards.current(account, other) is SnapWriteGuards.Locked)
		assertNull(p.guards.reserve("bobtest", bobs, SnapWriteGuards.Operation.EDIT))
		assertTrue(chain.signed.isEmpty())
	}

	// ── concurrency and stale releases ─────────────────────────────────

	@Test
	fun `racing reservations on one comment let exactly one through`() {
		val guards = SnapWriteGuards(Disk())
		val pool = Executors.newFixedThreadPool(8)
		val go = CountDownLatch(1)
		val wins = java.util.concurrent.atomic.AtomicInteger()
		repeat(16) { i ->
			pool.execute {
				go.await()
				val op = if (i % 2 == 0) SnapWriteGuards.Operation.EDIT else SnapWriteGuards.Operation.DELETE
				if (guards.reserve(account, root, op) != null) wins.incrementAndGet()
			}
		}
		go.countDown()
		pool.shutdown()
		assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
		assertEquals(1, wins.get())
	}

	@Test
	fun `a release with the wrong id frees neither memory nor disk`() {
		val disk = Disk()
		val chain = Chain()
		val p = Process(disk, chain)
		p.deleter.delete(account, root)
		val held = p.guards.current(account, root)!!
		assertFalse(p.guards.release(account, root, held.id + 1))
		assertEquals(held, p.guards.current(account, root))
		assertEquals(1, disk.rows.size)
		assertEquals(held, Process(disk, chain).guards.current(account, root))
	}

	@Test
	fun `ids keep increasing across restarts, so an old id never matches a new attempt`() {
		val disk = Disk()
		val chain = Chain()
		val first = Process(disk, chain)
		first.deleter.delete(account, root)
		val oldId = first.guards.current(account, root)!!.id
		val next = Process(disk, chain)
		val fresh = next.guards.reserve(account, other, SnapWriteGuards.Operation.EDIT)!!
		assertTrue(fresh.id > oldId)
	}

	// ── proof after a restart ──────────────────────────────────────────

	@Test
	fun `after a restart, proven absence releases the original and only then may a new Delete sign`() {
		val disk = Disk()
		val chain = Chain()
		Process(disk, chain).deleter.delete(account, root)
		val next = Process(disk, chain)
		chain.evidence["del-1"] = HiveRpc.TransactionEvidence.ABSENT
		assertTrue(next.deleter.delete(account, root) is SnapDeleter.Outcome.Failed)
		assertTrue(disk.rows.isEmpty())
		assertTrue(next.deleter.delete(account, root) is SnapDeleter.Outcome.Uncertain)
		assertEquals(listOf("del-1", "del-2"), chain.signed.map { it.txId })
	}

	@Test
	fun `after a restart, aged finality with a fenced read settles an edit whose status is gone`() {
		val disk = Disk()
		val chain = Chain()
		Process(disk, chain).editor.edit(account, reply, SnapEditKind.REPLY, "first edit")
		chain.comments[reply.contentId] = chain.comments[reply.contentId]!!.copy(body = "first edit", headEpochSec = 2_000_000_100L)
		chain.finality = 2_000_000_100L // status history aged out (UNAVAILABLE), but it can no longer land

		val next = Process(disk, chain)
		assertEquals(
			SnapEditor.Outcome.Edited(reply.contentId, "first edit", "edit-1"),
			next.editor.edit(account, reply, SnapEditKind.REPLY, "anything"),
		)
		assertTrue(disk.rows.isEmpty())
		assertEquals(1, chain.signed.size)
	}

	// ── a saved attempt whose removal does not take ────────────────────

	@Test
	fun `a proven edit whose saved row cannot be removed stays locked here and after a restart`() {
		val disk = Disk()
		val chain = Chain()
		val store = Store().apply { write(confirmedRoot("first words$tail")) }
		val p = Process(disk, chain, store)
		assertTrue(p.editor.edit(account, root, SnapEditKind.ROOT, "edited words") is SnapEditor.Outcome.Uncertain)
		val original = chain.signed.single()
		chain.evidence[original.txId] = HiveRpc.TransactionEvidence.BLOCK
		disk.removeFails = true

		assertTrue(p.editor.edit(account, root, SnapEditKind.ROOT, "edited words") is SnapEditor.Outcome.Edited)
		assertEquals("the proven repair is kept", "edited words$tail", (store.read(account, "event-1") as PendingSnapRead.Present).snap.body)
		assertEquals("the saved row is still on disk", 1, disk.rows.size)
		assertEquals("so this process stays locked too", original, (p.guards.current(account, root) as SnapWriteGuards.EditAttempt).prepared)
		assertEquals(SnapDeleter.Outcome.Blocked(SnapDeleter.EDIT_UNSETTLED), p.deleter.delete(account, root))
		p.editor.edit(account, root, SnapEditKind.ROOT, "newer words") // only settles the original again
		assertEquals("no new signature or broadcast", listOf(original), chain.signed)
		assertEquals(listOf(original.txId), chain.sent)

		val next = Process(disk, chain, store) // the row is restored by the next process
		assertEquals(original, (next.guards.current(account, root) as SnapWriteGuards.EditAttempt).prepared)

		disk.removeFails = false
		assertTrue(next.editor.edit(account, root, SnapEditKind.ROOT, "edited words") is SnapEditor.Outcome.Edited)
		assertTrue(disk.rows.isEmpty())
		assertNull(next.guards.current(account, root))
		assertTrue("only now may a Delete sign", next.deleter.delete(account, root) is SnapDeleter.Outcome.Uncertain)
		assertEquals(2, chain.signed.size)
	}

	@Test
	fun `a proven delete whose saved row cannot be removed stays locked, and its local steps repeat harmlessly`() {
		val disk = Disk()
		val chain = Chain()
		val store = Store().apply { write(confirmedRoot("first words$tail")) }
		val p = Process(disk, chain, store)
		assertTrue(p.deleter.delete(account, root) is SnapDeleter.Outcome.Uncertain)
		val original = chain.signed.single()
		chain.comments.remove(root.contentId) // the delete landed
		disk.removeFails = true

		assertTrue(p.deleter.delete(account, root) is SnapDeleter.Outcome.Deleted)
		assertEquals(setOf(root.contentId), p.tombstones)
		assertTrue("the record was retired", store.saved.isEmpty())
		assertEquals(1, disk.rows.size)
		assertTrue(p.deleter.hasUnsettled(account, root))
		assertEquals(SnapEditor.Outcome.Failed(SnapEditor.DELETE_UNSETTLED), p.editor.edit(account, root, SnapEditKind.ROOT, "x"))
		assertTrue("settling again sends nothing", p.deleter.delete(account, root) is SnapDeleter.Outcome.Deleted)
		assertEquals(setOf(root.contentId), p.tombstones)
		assertEquals(listOf(original), chain.signed)

		val next = Process(disk, chain, store)
		assertEquals(original, (next.guards.current(account, root) as SnapWriteGuards.DeleteAttempt).prepared)
		disk.removeFails = false
		assertTrue(next.deleter.delete(account, root) is SnapDeleter.Outcome.Deleted)
		assertTrue(disk.rows.isEmpty())
		assertNull(next.guards.current(account, root))
		assertEquals(listOf(original), chain.signed)
	}

	@Test
	fun `a removal that throws an ordinary exception keeps the lock just the same`() {
		val disk = Disk()
		val chain = Chain()
		val p = Process(disk, chain)
		assertTrue(p.editor.edit(account, reply, SnapEditKind.REPLY, "first edit") is SnapEditor.Outcome.Uncertain)
		val original = chain.signed.single()
		chain.evidence[original.txId] = HiveRpc.TransactionEvidence.ABSENT // proven never included
		disk.removeThrows = true

		p.editor.edit(account, reply, SnapEditKind.REPLY, "first edit") // settles; removal throws
		assertEquals(1, disk.rows.size)
		assertTrue(p.guards.current(account, reply) is SnapWriteGuards.EditAttempt)
		assertTrue(p.deleter.delete(account, reply) is SnapDeleter.Outcome.Blocked)
		assertEquals(listOf(original), chain.signed)

		disk.removeThrows = false
		assertTrue(p.editor.edit(account, reply, SnapEditKind.REPLY, "first edit") is SnapEditor.Outcome.Failed)
		assertTrue(disk.rows.isEmpty())
		assertNull(p.guards.current(account, reply))
	}

	@Test
	fun `a reservation that was never saved is freed even when removal is failing`() {
		val disk = Disk().apply { removeFails = true }
		val chain = Chain()
		val p = Process(disk, chain)
		// The chain already says these words: no write, and the reservation is let go.
		assertEquals(
			SnapEditor.Outcome.Edited(reply.contentId, "reply words", null),
			p.editor.edit(account, reply, SnapEditKind.REPLY, "reply words"),
		)
		assertNull(p.guards.current(account, reply))
		assertNotNull(p.guards.reserve(account, reply, SnapWriteGuards.Operation.DELETE))
	}

	private fun assertCrashes(block: () -> Unit) {
		try {
			block()
		} catch (expected: Crash) {
			return
		}
		throw AssertionError("expected the simulated process death")
	}
}
