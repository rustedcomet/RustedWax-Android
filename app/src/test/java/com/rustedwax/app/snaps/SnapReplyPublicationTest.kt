package com.rustedwax.app.snaps

import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.SnapContainer
import com.rustedwax.hive.SnapContainerResolver
import com.rustedwax.hive.TxSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reply is as permanent and as public as a Snap, so it gets the same rules.
 *
 * What is pinned here is that the reply path really does go through the shared
 * publication machinery rather than around it: persist before send, one
 * permlink per intent, and — the case that matters most — **no ambiguous
 * outcome ever becomes a second transaction**.
 *
 * Nothing here touches the network, and no private key exists in this file:
 * signing lives behind [SnapHivePort].
 */
class SnapReplyPublicationTest {

	private val account = "rustedwaxtest"
	private val target = SnapReplyTarget.of("alice", "rustedwax-snap-1000-aaaaaa")!!

	/** One reply intent — the durable identity of one thing said once. */
	private val intent = "a1b2c3d4e5f60718"
	private val secondIntent = "00112233445566ff"
	private val slot = SnapReplyKey.of(target, intent)

	// ── fakes ──────────────────────────────────────────────────────────

	private class MemoryStore : PendingSnapStore {
		val saved = mutableMapOf<String, String>()
		val states = mutableListOf<PendingSnapState>()
		var writable = true

		fun entries(account: String) = saved.entries
			.filter { it.key.startsWith("$account|") }
			.associate { it.key.removePrefix("$account|") to it.value }

		override fun read(account: String, eventId: String): PendingSnapRead = when {
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

		override fun corruptEventIds(account: String) =
			PendingSnapIntegrity.lockedEventIds(account, entries(account))
	}

	private class FakeHive(
		var broadcastResults: MutableList<HiveRpc.BroadcastResult> = mutableListOf(),
		var evidence: HiveRpc.TransactionEvidence = HiveRpc.TransactionEvidence.UNAVAILABLE,
		var content: Boolean? = false,
	) : SnapHivePort {
		var broadcasts = 0
		var containerLookups = 0
		val preparedOps = mutableListOf<TxSerializer.CommentOp>()

		override fun resolveContainer(): SnapContainerResolver.Result {
			containerLookups++
			return SnapContainerResolver.Result.Resolved(
				SnapContainer("peak.snaps", "snap-container-1789648560", "2026-09-17T12:36:00"),
			)
		}

		override fun prepareComment(operation: TxSerializer.CommentOp, author: String): HivePreparationResult {
			preparedOps += operation
			return HivePreparationResult.Ready(
				PreparedHiveTransaction(
					signedTransactionJson = """{"op":"${operation.permlink}"}""",
					txId = "tx-${operation.permlink}",
					expirationEpochSec = 2_000_000_000L,
				),
			)
		}

		override fun broadcastPrepared(prepared: PreparedHiveTransaction, author: String): HiveRpc.BroadcastResult {
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
		permlinks: MutableList<String> = mutableListOf(
			"rustedwax-reply-1000-aaaaaa",
			"rustedwax-reply-1000-bbbbbb",
		),
	) = SnapPublisher(
		hive = hive,
		store = store,
		nowEpochSec = { 1_000L },
		newReplyPermlink = { permlinks.removeFirstOrNull() ?: "rustedwax-reply-exhausted" },
	)

	private fun inBlock() =
		HiveRpc.BroadcastResult.Success("tx", "node", HiveRpc.BroadcastResult.Evidence.BLOCK)

	private fun stored(store: MemoryStore) =
		(store.read(account, slot) as PendingSnapRead.Present).snap

	// ── what a reply says and where it goes ────────────────────────────

	@Test
	fun `a reply is parented on the comment it answers, not on the container`() {
		val hive = FakeHive(mutableListOf(inBlock()))
		val store = MemoryStore()

		publisher(hive, store).publishReply(account, target, intent, "nice one")

		val op = hive.preparedOps.single()
		assertEquals("alice", op.parentAuthor)
		assertEquals("rustedwax-snap-1000-aaaaaa", op.parentPermlink)
		assertEquals(account, op.author)
		assertEquals(
			"a reply needs no Snap container, so it must not ask for one",
			0,
			hive.containerLookups,
		)
	}

	/**
	 * The frozen v1 root body appends a URL and a hashtag line. A reply appends
	 * nothing — what the composer counted is exactly what is published.
	 */
	@Test
	fun `a reply body is the user text and nothing else`() {
		val hive = FakeHive(mutableListOf(inBlock()))

		publisher(hive, MemoryStore()).publishReply(account, target, intent, "  spaced\nout  ")

		val op = hive.preparedOps.single()
		assertEquals("  spaced\nout  ", op.body)
		assertFalse(op.body.contains("youtu.be"))
		assertFalse(op.body.contains("#scrobblelife"))
	}

	@Test
	fun `the generated metadata is outside the body`() {
		val hive = FakeHive(mutableListOf(inBlock()))

		publisher(hive, MemoryStore()).publishReply(account, target, intent, "hi")

		val op = hive.preparedOps.single()
		assertEquals("hi", op.body)
		assertTrue(op.jsonMetadata.contains("\"app\""))
		assertFalse("a reply has no hashtags to mirror", op.jsonMetadata.contains("\"tags\""))
	}

	@Test
	fun `a reply permlink is distinguishable from a root Snap permlink`() {
		val hive = FakeHive(mutableListOf(inBlock()))

		publisher(hive, MemoryStore()).publishReply(account, target, intent, "hi")

		assertTrue(hive.preparedOps.single().permlink.startsWith(SnapPermlink.REPLY_PREFIX))
	}

	@Test
	fun `an empty reply never reaches the network`() {
		val hive = FakeHive(mutableListOf(inBlock()))

		val outcome = publisher(hive, MemoryStore()).publishReply(account, target, intent, "   ")

		assertTrue(outcome is SnapPublisher.Outcome.Failed)
		assertEquals(0, hive.broadcasts)
		assertTrue(hive.preparedOps.isEmpty())
	}

	@Test
	fun `the root Snap body rule is untouched by the reply rule`() {
		val hive = FakeHive(mutableListOf(inBlock()))

		// A root Snap body is the user text plus a generated tail, so it is far
		// past 200 clusters by the time it is signed — and must still publish.
		val outcome = publisher(hive, MemoryStore())
			.publishRoot(account, "event-1", SnapMedia("8pSS6wdojqY"), "a".repeat(200))

		assertTrue(outcome is SnapPublisher.Outcome.Published)
		assertTrue(hive.preparedOps.single().body.contains("#scrobblelife"))
	}

	// ── the ordering that makes reconciliation possible ────────────────

	@Test
	fun `the record is persisted and verified before anything is broadcast`() {
		val hive = FakeHive(mutableListOf(inBlock()))
		val store = MemoryStore()

		publisher(hive, store).publishReply(account, target, intent, "hi")

		assertEquals(
			listOf(
				PendingSnapState.PREPARED,
				PendingSnapState.BROADCASTING,
				PendingSnapState.CONFIRMED,
			),
			store.states,
		)
	}

	@Test
	fun `a reply that cannot be saved is never sent`() {
		val hive = FakeHive(mutableListOf(inBlock()))
		val store = MemoryStore().apply { writable = false }

		val outcome = publisher(hive, store).publishReply(account, target, intent, "hi")

		assertTrue(outcome is SnapPublisher.Outcome.Failed)
		assertEquals(0, hive.broadcasts)
	}

	/** The record is filed under the parent **and** the intent, not the parent alone. */
	@Test
	fun `the reply record is keyed by parent and intent`() {
		val hive = FakeHive(mutableListOf(inBlock()))
		val store = MemoryStore()

		publisher(hive, store).publishReply(account, target, intent, "hi")

		assertEquals("reply|alice/rustedwax-snap-1000-aaaaaa|$intent", stored(store).eventId)
		assertEquals("reply|alice/rustedwax-snap-1000-aaaaaa", SnapReplyKey.slotOf(stored(store).eventId))
		assertEquals(PendingSnapKind.REPLY, stored(store).kind)
	}

	// ── the rule that matters: ambiguity never duplicates ──────────────

	/**
	 * A lost response says nothing about what the node received. The slot locks,
	 * the outcome stays uncertain, and a second attempt reads the chain instead
	 * of writing to it.
	 */
	@Test
	fun `an ambiguous reply does not broadcast again on the next attempt`() {
		val hive = FakeHive(mutableListOf(HiveRpc.BroadcastResult.NetworkFailure("lost")))
		val store = MemoryStore()
		val pub = publisher(hive, store)

		val first = pub.publishReply(account, target, intent, "hi")
		assertTrue(first is SnapPublisher.Outcome.Uncertain)
		assertEquals(1, hive.broadcasts)

		val second = pub.publishReply(account, target, intent, "hi again")

		assertTrue(second is SnapPublisher.Outcome.Uncertain)
		assertEquals("no second transaction may leave the device", 1, hive.broadcasts)
		assertEquals(1, hive.preparedOps.size)
	}

	/** Once the chain proves the comment is there, the slot resolves to published. */
	@Test
	fun `an ambiguous reply resolves to published when the comment is found`() {
		val hive = FakeHive(mutableListOf(HiveRpc.BroadcastResult.NetworkFailure("lost")))
		val store = MemoryStore()
		val pub = publisher(hive, store)
		pub.publishReply(account, target, intent, "hi")

		hive.content = true
		val outcome = pub.reconcile(account, slot, PendingSnapKind.REPLY)

		assertTrue(outcome is SnapPublisher.Outcome.Published)
		assertEquals(1, hive.broadcasts)
		assertEquals(PendingSnapState.CONFIRMED, stored(store).state)
	}

	/**
	 * Absence needs both halves — no comment at the permlink *and* independent
	 * nodes agreeing the transaction expired without inclusion. Only then may a
	 * reply be attempted again, and then it reuses the same permlink, so a late
	 * arrival would be an edit rather than a duplicate.
	 */
	@Test
	fun `a proven absent reply may be sent again under the same permlink`() {
		val hive = FakeHive(
			mutableListOf(HiveRpc.BroadcastResult.NetworkFailure("lost"), inBlock()),
		)
		val store = MemoryStore()
		val pub = publisher(hive, store)
		pub.publishReply(account, target, intent, "hi")

		hive.evidence = HiveRpc.TransactionEvidence.ABSENT
		assertTrue(pub.reconcile(account, slot, PendingSnapKind.REPLY) is SnapPublisher.Outcome.Failed)

		val retry = pub.publishReply(account, target, intent, "hi")

		assertTrue(retry is SnapPublisher.Outcome.Published)
		assertEquals(2, hive.broadcasts)
		assertEquals(
			"the same permlink, so a late first arrival is an edit",
			hive.preparedOps[0].permlink,
			hive.preparedOps[1].permlink,
		)
	}

	/** Unreadable stored state locks the slot: no permlink, no transaction. */
	@Test
	fun `a corrupt reply record locks the slot rather than posting again`() {
		val hive = FakeHive(mutableListOf(inBlock()))
		val store = MemoryStore()
		// A record filed under this slot that claims to be a different one.
		store.saved["$account|$slot"] = PendingSnap(
			account = account,
			eventId = "reply|someone/else",
			author = account,
			permlink = "rustedwax-reply-1000-aaaaaa",
			parentAuthor = "alice",
			parentPermlink = "rustedwax-snap-1000-aaaaaa",
			body = "hi",
			jsonMetadata = "{}",
			signedTransactionJson = "{}",
			txId = "tx",
			expirationEpochSec = 2_000_000_000L,
			state = PendingSnapState.PREPARED,
			createdAtEpochSec = 1_000L,
			updatedAtEpochSec = 1_000L,
			kind = PendingSnapKind.REPLY,
		).toJson()

		val outcome = publisher(hive, store).publishReply(account, target, intent, "hi")

		assertTrue(outcome is SnapPublisher.Outcome.Uncertain)
		assertEquals(0, hive.broadcasts)
		assertTrue(hive.preparedOps.isEmpty())
	}

	// ── replying twice to the same comment ─────────────────────────────

	/**
	 * A confirmed reply is proven on chain and visible in the thread, so a
	 * *later* reply to the same comment is a fresh intent — a new permlink, not
	 * a duplicate of anything. This is the one place the reply path deliberately
	 * differs from the root path, where a confirmed Snap is the final answer.
	 */
	@Test
	fun `a later reply to the same comment, under a new intent, publishes normally`() {
		val hive = FakeHive(mutableListOf(inBlock(), inBlock()))
		val store = MemoryStore()
		val pub = publisher(hive, store)

		pub.publishReply(account, target, intent, "first")
		val second = pub.publishReply(account, target, secondIntent, "second")

		assertTrue(second is SnapPublisher.Outcome.Published)
		assertEquals(2, hive.broadcasts)
		assertNotEquals(hive.preparedOps[0].permlink, hive.preparedOps[1].permlink)
		assertEquals("second", hive.preparedOps[1].body)
	}

	// ── the crash window ───────────────────────────────────────────────

	/**
	 * The Codex failure, at the publisher boundary.
	 *
	 * A reply confirms, the record is persisted, and the process dies before the
	 * draft is cleared. On restart the draft is still sendable and still carries
	 * its intent — and because the record is filed under that intent, the second
	 * attempt finds a `CONFIRMED` row and answers with the comment that already
	 * exists. No permlink is minted, nothing is signed, nothing is sent.
	 */
	@Test
	fun `a confirmed intent attempted again publishes nothing`() {
		val hive = FakeHive(mutableListOf(inBlock()))
		val store = MemoryStore()
		val pub = publisher(hive, store)
		val first = pub.publishReply(account, target, intent, "nice one")
		assertTrue(first is SnapPublisher.Outcome.Published)

		// The restart: same intent, same draft text, a fresh publisher over the
		// same durable store.
		val afterCrash = publisher(hive, store, mutableListOf("rustedwax-reply-1000-cccccc"))
		val second = afterCrash.publishReply(account, target, intent, "nice one")

		assertTrue(second is SnapPublisher.Outcome.Published)
		assertEquals("no second transaction may leave the device", 1, hive.broadcasts)
		assertEquals("no second permlink may be minted", 1, hive.preparedOps.size)
		assertEquals(
			(first as SnapPublisher.Outcome.Published).contentId,
			(second as SnapPublisher.Outcome.Published).contentId,
		)
	}

	/** Edited text cannot resurrect a confirmed intent either. */
	@Test
	fun `a confirmed intent ignores whatever the draft now says`() {
		val hive = FakeHive(mutableListOf(inBlock()))
		val store = MemoryStore()
		val pub = publisher(hive, store)
		pub.publishReply(account, target, intent, "nice one")

		val second = pub.publishReply(account, target, intent, "completely different words")

		assertTrue(second is SnapPublisher.Outcome.Published)
		assertEquals(1, hive.broadcasts)
		assertEquals("nice one", hive.preparedOps.single().body)
	}

	/**
	 * An ambiguous intent surviving a restart may read the chain and nothing
	 * else — a fresh publisher over the same store reaches the same verdict.
	 */
	@Test
	fun `an ambiguous intent cannot be resent after a restart`() {
		val hive = FakeHive(mutableListOf(HiveRpc.BroadcastResult.NetworkFailure("lost")))
		val store = MemoryStore()
		publisher(hive, store).publishReply(account, target, intent, "hi")
		assertEquals(1, hive.broadcasts)

		val afterCrash = publisher(hive, store, mutableListOf("rustedwax-reply-1000-cccccc"))
		val outcome = afterCrash.publishReply(account, target, intent, "hi")

		assertTrue(outcome is SnapPublisher.Outcome.Uncertain)
		assertEquals(1, hive.broadcasts)
		assertEquals(1, hive.preparedOps.size)
	}

	/** `restore` settles a surviving intent by reading, never by sending. */
	@Test
	fun `restore reconciles a surviving intent without broadcasting`() {
		val hive = FakeHive(mutableListOf(HiveRpc.BroadcastResult.NetworkFailure("lost")))
		val store = MemoryStore()
		publisher(hive, store).publishReply(account, target, intent, "hi")

		hive.content = true
		val restored = publisher(hive, store).restore(account)

		assertEquals(1, hive.broadcasts)
		val (eventId, outcome) = restored.single()
		assertEquals(slot, eventId)
		assertTrue(outcome is SnapPublisher.Outcome.Published)
	}

	@Test
	fun `a minted intent can never break a store key`() {
		val ids = (1..200).map { SnapReplyIntent.generate() }

		assertEquals("intents must be unique", 200, ids.toSet().size)
		ids.forEach {
			assertTrue("'$it' must be hex", Regex("^[0-9a-f]{16}$").matches(it))
			assertFalse(it.contains('|'))
			assertEquals(it, SnapReplyKey.intentOf(SnapReplyKey.of(target, it)))
		}
	}

	@Test
	fun `an event id carries its slot and its intent back out`() {
		assertEquals("reply|alice/rustedwax-snap-1000-aaaaaa", SnapReplyKey.slotOf(slot))
		assertEquals(intent, SnapReplyKey.intentOf(slot))
		assertNull(SnapReplyKey.slotOf("event-1"))
		assertNull(SnapReplyKey.intentOf("reply|alice/only-two-parts"))
	}

	@Test
	fun `an unresolved intent is reported as such, and a decided one is not`() {
		val hive = FakeHive(mutableListOf(HiveRpc.BroadcastResult.NetworkFailure("lost")))
		val store = MemoryStore()
		val pub = publisher(hive, store)

		assertFalse("nothing sent yet", pub.isUnresolved(account, slot, PendingSnapKind.REPLY))
		pub.publishReply(account, target, intent, "hi")
		assertTrue("ambiguous", pub.isUnresolved(account, slot, PendingSnapKind.REPLY))

		hive.content = true
		pub.reconcile(account, slot, PendingSnapKind.REPLY)
		assertFalse("proven published", pub.isUnresolved(account, slot, PendingSnapKind.REPLY))
	}

	// ── the publication boundary enforces the reply rules ──────────────

	/**
	 * The composer checks the same two rules while the user types. That is not
	 * where they are enforced: a stale composition, a draft written by an older
	 * build or a hand-edited preference file all reach this function, and what
	 * is at stake is a permanent public comment.
	 */
	@Test
	fun `a reply of 201 clusters is refused at the publication boundary`() {
		val hive = FakeHive(mutableListOf(inBlock()))

		val outcome = publisher(hive, MemoryStore())
			.publishReply(account, target, intent, "a".repeat(201))

		assertTrue(outcome is SnapPublisher.Outcome.Failed)
		assertEquals(0, hive.broadcasts)
		assertTrue(hive.preparedOps.isEmpty())
	}

	@Test
	fun `a reply of exactly 200 clusters is accepted at the publication boundary`() {
		val hive = FakeHive(mutableListOf(inBlock()))

		val outcome = publisher(hive, MemoryStore())
			.publishReply(account, target, intent, "a".repeat(200))

		assertTrue(outcome is SnapPublisher.Outcome.Published)
		assertEquals(1, hive.broadcasts)
	}

	/** 200 family emoji is 200 characters, not 2200. */
	@Test
	fun `the boundary counts grapheme clusters, not UTF-16 units`() {
		val hive = FakeHive(mutableListOf(inBlock()))
		val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"

		val outcome = publisher(hive, MemoryStore())
			.publishReply(account, target, intent, family.repeat(200))

		assertTrue(outcome is SnapPublisher.Outcome.Published)
		assertTrue(hive.preparedOps.single().body.length > 2_000)
	}

	@Test
	fun `a reply that is one emoji is accepted at the publication boundary`() {
		val hive = FakeHive(mutableListOf(inBlock()))

		val outcome = publisher(hive, MemoryStore())
			.publishReply(account, target, intent, "\uD83D\uDD25")

		assertTrue(outcome is SnapPublisher.Outcome.Published)
		assertEquals("\uD83D\uDD25", hive.preparedOps.single().body)
	}

	/**
	 * Nothing renderable is not a reply, whatever the string length says.
	 *
	 * Control characters are the case that was wrong. `Character.isWhitespace`
	 * is false for NUL, BEL, ESC and the whole C1 block, so a draft made only of
	 * those looked like content to the shared visibility rule and could have
	 * been signed. A reply now has to contain something that can actually put a
	 * mark on a screen.
	 */
	@Test
	fun `a reply with nothing renderable in it is refused at the publication boundary`() {
		listOf(
			"\u0000",
			"\u0007",
			"\u001B",
			"\u0085",
			"\u0001\u0002\u0003",
			"\u009C\u009F",
			// Controls mixed with whitespace, in both orders.
			" \u0000\t\u001B\n ",
			"\u0007 \u0085\r\n\u000B",
			// And the invisibles that were already refused.
			"   ",
			"\n\n",
			"\u200D\uFE0F",
			"\u200B\u200C",
			"",
		).forEach { invisible ->
			val hive = FakeHive(mutableListOf(inBlock()))

			val outcome = publisher(hive, MemoryStore())
				.publishReply(account, target, intent, invisible)

			assertTrue(
				"${invisible.map { it.code }} must be refused",
				outcome is SnapPublisher.Outcome.Failed,
			)
			assertEquals(0, hive.broadcasts)
			assertTrue("nothing may be signed either", hive.preparedOps.isEmpty())
		}
	}

	/** Ordinary text passes, including text that merely contains a control. */
	@Test
	fun `text with one renderable character passes the publication boundary`() {
		listOf(
			"hi",
			"\u0000hi",
			"hi\u0007",
			" \u001B a \u0085 ",
			"\uD83D\uDD25",
			"\u0000\uD83D\uDD25\u0000",
		).forEach { body ->
			val hive = FakeHive(mutableListOf(inBlock()))

			val outcome = publisher(hive, MemoryStore())
				.publishReply(account, target, intent, body)

			assertTrue("'$body' must publish", outcome is SnapPublisher.Outcome.Published)
			// Byte for byte: the rule decides whether to publish, never what.
			assertEquals(body, hive.preparedOps.single().body)
		}
	}

	/** Replies to different comments are independent slots. */
	@Test
	fun `an ambiguous reply to one comment does not lock replies to another`() {
		val hive = FakeHive(
			mutableListOf(HiveRpc.BroadcastResult.NetworkFailure("lost"), inBlock()),
		)
		val store = MemoryStore()
		val pub = publisher(hive, store)
		val other = SnapReplyTarget.of("bob", "re-something")!!

		pub.publishReply(account, target, intent, "hi")
		val outcome = pub.publishReply(account, other, intent, "hi there")

		assertTrue(outcome is SnapPublisher.Outcome.Published)
		assertEquals(2, hive.broadcasts)
	}

	// ── account isolation ──────────────────────────────────────────────

	@Test
	fun `one account cannot read another account's pending reply`() {
		val hive = FakeHive(mutableListOf(HiveRpc.BroadcastResult.NetworkFailure("lost")))
		val store = MemoryStore()
		val pub = publisher(hive, store)
		pub.publishReply(account, target, intent, "hi")

		assertTrue(store.read("someoneelse", slot) is PendingSnapRead.Absent)
		assertNull(pub.reconcile("someoneelse", slot, PendingSnapKind.REPLY))
		assertTrue(pub.restore("someoneelse").isEmpty())
	}

	// ── reply targets ──────────────────────────────────────────────────

	@Test
	fun `a target that is not an account and a permlink cannot be built`() {
		assertNull(SnapReplyTarget.of("Alice", "ok-permlink"))
		assertNull(SnapReplyTarget.of("alice", "Not/A Permlink"))
		assertNull(SnapReplyTarget.of("alice", ""))
	}

	@Test
	fun `a target built from a parsed reply keeps its identity`() {
		val reply = SnapReply("bob", "re-one", "alice", "snap-1", "hi", 1_000L)

		assertEquals("bob/re-one", SnapReplyTarget.of(reply)!!.contentId)
	}

	// ── the durable intent, which is what makes a reply instant ────────
	//
	// [SnapPublisher.intendReply] is the whole of the visible path: one local
	// write, and from then on the reply is on screen. Everything these pin
	// follows from that — it may not touch the network, it may not mint a
	// second permlink, and it may not hand back a "ready" the disk did not
	// actually accept.

	@Test
	fun `intending a reply asks the network nothing and sends nothing`() {
		val hive = FakeHive(mutableListOf(inBlock()))
		val store = MemoryStore()

		val staged = publisher(hive, store).intendReply(account, target, intent, "nice one")

		assertTrue(staged is SnapPublisher.Staged.Ready)
		assertEquals("no container lookup", 0, hive.containerLookups)
		assertEquals("nothing signed", 0, hive.preparedOps.size)
		assertEquals("nothing sent", 0, hive.broadcasts)
	}

	/** And what it wrote is a reply with no transaction behind it. */
	@Test
	fun `the intent record is frozen, parented and unsendable`() {
		val store = MemoryStore()

		publisher(FakeHive(), store).intendReply(account, target, intent, "nice one")

		val record = stored(store)
		assertEquals(PendingSnapState.INTENT, record.state)
		assertEquals(PendingSnapKind.REPLY, record.kind)
		assertEquals("nice one", record.body)
		assertEquals("alice", record.parentAuthor)
		assertEquals("rustedwax-snap-1000-aaaaaa", record.parentPermlink)
		assertEquals("", record.signedTransactionJson)
		assertEquals("", record.txId)
		// An intent with no expiry is an intent with nothing to broadcast.
		assertEquals(0L, record.expirationEpochSec)
		assertEquals(
			"deliver must refuse it outright",
			SnapPublisher.Outcome.Failed("this Snap hasn't been prepared yet"),
			publisher(FakeHive(), store).deliver(account, slot, PendingSnapKind.REPLY),
		)
	}

	/** A disk that refuses is a reply that does not exist and is not shown. */
	@Test
	fun `an intent that cannot be committed is a refusal, not a ready`() {
		val store = MemoryStore().apply { writable = false }

		val staged = publisher(FakeHive(), store).intendReply(account, target, intent, "nice one")

		assertTrue(staged is SnapPublisher.Staged.Failed)
		assertTrue(store.saved.isEmpty())
	}

	/** Publishing resumes from the intent: same permlink, same words. */
	@Test
	fun `publishing after an intent reuses its identity and its body`() {
		val hive = FakeHive(mutableListOf(inBlock()))
		val store = MemoryStore()
		val pub = publisher(hive, store)
		pub.intendReply(account, target, intent, "nice one")
		val intended = stored(store)

		pub.publishReply(account, target, intent, "completely different words")

		val op = hive.preparedOps.single()
		assertEquals(intended.permlink, op.permlink)
		assertEquals("the frozen body is what gets signed", "nice one", op.body)
		assertEquals(1, hive.broadcasts)
		assertEquals("one record, one reply", 1, store.entries(account).size)
	}

	/** Intending twice is intending once: the identity is minted one time. */
	@Test
	fun `a second intent for the same slot reuses the first`() {
		val store = MemoryStore()
		val pub = publisher(FakeHive(), store)

		pub.intendReply(account, target, intent, "nice one")
		val first = stored(store).permlink
		pub.intendReply(account, target, intent, "nice one")

		assertEquals(first, stored(store).permlink)
		assertEquals(1, store.entries(account).size)
	}

	/** A reply already on chain is never re-intended. */
	@Test
	fun `a confirmed reply answers from disk instead of starting again`() {
		val hive = FakeHive(mutableListOf(inBlock()))
		val store = MemoryStore()
		val pub = publisher(hive, store)
		pub.publishReply(account, target, intent, "nice one")
		val permlink = stored(store).permlink

		val staged = pub.intendReply(account, target, intent, "nice one")

		assertTrue(staged is SnapPublisher.Staged.Published)
		assertEquals(permlink, stored(store).permlink)
		assertEquals("no second transaction", 1, hive.broadcasts)
	}

	/** An ambiguous reply is never re-intended either — it is read. */
	@Test
	fun `an ambiguous reply refuses a fresh intent`() {
		val hive = FakeHive(mutableListOf(HiveRpc.BroadcastResult.NetworkFailure("lost")))
		hive.content = null
		val store = MemoryStore()
		val pub = publisher(hive, store)
		pub.publishReply(account, target, intent, "nice one")

		val staged = pub.intendReply(account, target, intent, "nice one")

		assertTrue(staged is SnapPublisher.Staged.Uncertain)
		assertEquals(1, hive.broadcasts)
	}

	// ── the rows a thread draws before the chain has caught up ─────────

	@Test
	fun `staged reply rows carry the exact record that is being published`() {
		val store = MemoryStore()
		val pub = publisher(FakeHive(), store)
		pub.intendReply(account, target, intent, "nice one")
		val record = stored(store)

		val row = pub.stagedReplyRows(account).single()

		assertEquals(account, row.author)
		assertEquals(record.permlink, row.permlink)
		assertEquals("alice", row.parentAuthor)
		assertEquals("rustedwax-snap-1000-aaaaaa", row.parentPermlink)
		assertEquals("nice one", row.body)
	}

	/** A proven-absent reply is not a reply, and is not drawn. */
	@Test
	fun `a failed reply leaves no row behind`() {
		val hive = FakeHive(mutableListOf(HiveRpc.BroadcastResult.NetworkFailure("lost")))
		hive.evidence = HiveRpc.TransactionEvidence.ABSENT
		val store = MemoryStore()
		val pub = publisher(hive, store)

		pub.publishReply(account, target, intent, "nice one")

		assertEquals(PendingSnapState.FAILED, stored(store).state)
		assertEquals(emptyList<SnapReply>(), pub.stagedReplyRows(account))
	}

	/** Root Snaps are not replies, and never appear as thread rows. */
	@Test
	fun `a root Snap is never drawn as a reply`() {
		val store = MemoryStore()
		val pub = publisher(FakeHive(), store)
		pub.intendRoot(account, "event-1", SnapMedia("8pSS6wdojqY"), "hello")

		assertEquals(emptyList<SnapReply>(), pub.stagedReplyRows(account))
		assertEquals("and the two restores do not cross", 1, pub.restoreStaged(account).size)
		assertEquals(0, pub.restoreStagedReplies(account).size)
	}

	/** Nor the other way round. */
	@Test
	fun `an unfinished reply is not offered to the History cards`() {
		val store = MemoryStore()
		val pub = publisher(FakeHive(), store)
		pub.intendReply(account, target, intent, "nice one")

		assertEquals(emptyList<SnapPublisher.StagedSnap>(), pub.restoreStaged(account))
		val row = pub.restoreStagedReplies(account).single()
		assertEquals(slot, row.eventId)
		assertTrue("an intent reached no node, so it needs the user", row.interrupted)
	}

	/** And a kind-scoped restore reconciles only its own side. */
	@Test
	fun `restore can be scoped to one kind`() {
		val hive = FakeHive(mutableListOf(inBlock(), inBlock()))
		val store = MemoryStore()
		val pub = publisher(hive, store)
		pub.publishReply(account, target, intent, "nice one")
		pub.intendRoot(account, "event-1", SnapMedia("8pSS6wdojqY"), "hello")

		val replies = pub.restore(account, PendingSnapKind.REPLY)
		val roots = pub.restore(account, PendingSnapKind.ROOT)

		assertEquals(listOf(slot), replies.map { it.first })
		assertEquals(emptyList<String>(), roots.map { it.first })
		assertEquals("both kinds unscoped", 1, pub.restore(account).size)
	}

	// ── the parent is frozen with the intent ───────────────────────────
	//
	// A reply's parent is the one thing about it the user actually chose: they
	// tapped Reply on a specific comment, and the reply appeared under that
	// comment the instant the intent was committed. So the parent is part of
	// the frozen identity, exactly like the permlink and the body — what gets
	// signed later has to be what they were shown, and a parent recomputed at
	// signing time would let the same visible reply land somewhere else.

	/** The intent stores the parent, and stores the parent that was tapped. */
	@Test
	fun `a reply intent freezes the parent it was written to`() {
		val store = MemoryStore()

		publisher(FakeHive(), store).intendReply(account, target, intent, "nice one")

		val record = stored(store)
		assertEquals("alice", record.parentAuthor)
		assertEquals("rustedwax-snap-1000-aaaaaa", record.parentPermlink)
	}

	/** And preparation signs that stored parent, not a freshly derived one. */
	@Test
	fun `preparation signs the stored parent`() {
		val hive = FakeHive(mutableListOf(inBlock()))
		val store = MemoryStore()
		val pub = publisher(hive, store)
		pub.intendReply(account, target, intent, "nice one")
		val frozen = stored(store)

		pub.publishReply(account, target, intent, "nice one")

		val op = hive.preparedOps.single()
		assertEquals(frozen.parentAuthor, op.parentAuthor)
		assertEquals(frozen.parentPermlink, op.parentPermlink)
		assertEquals(frozen.permlink, op.permlink)
		assertEquals(frozen.body, op.body)
		assertEquals(frozen.jsonMetadata, op.jsonMetadata)
	}

	/**
	 * The blocker, stated as its own test.
	 *
	 * The stored intent says one parent; the caller asks for another. There is
	 * no reading of this where signing is safe — one of the two is not what the
	 * user saw — so nothing is signed at all, and in particular the caller's
	 * parent is never quietly used.
	 */
	@Test
	fun `a parent that disagrees with the intent signs nothing`() {
		val hive = FakeHive(mutableListOf(inBlock()))
		val store = MemoryStore()
		val pub = publisher(hive, store)
		pub.intendReply(account, target, intent, "nice one")
		// The record is moved onto a different comment, as a mis-keyed or
		// tampered entry would be.
		val moved = stored(store).copy(parentAuthor = "carol", parentPermlink = "somewhere-else")
		store.saved["$account|$slot"] = moved.toJson()

		val outcome = pub.publishReply(account, target, intent, "nice one")

		assertTrue("got $outcome", outcome is SnapPublisher.Outcome.Failed)
		assertEquals("nothing signed", 0, hive.preparedOps.size)
		assertEquals("nothing sent", 0, hive.broadcasts)
		assertEquals("carol", stored(store).parentAuthor)
		assertEquals(PendingSnapState.INTENT, stored(store).state)
	}

	/** A stored parent that is not a parent at all is refused, not guessed. */
	@Test
	fun `a blank stored parent refuses rather than inventing one`() {
		listOf("" to target.permlink, target.author to "").forEach { (author, permlink) ->
			val hive = FakeHive(mutableListOf(inBlock()))
			val store = MemoryStore()
			val pub = publisher(hive, store)
			pub.intendReply(account, target, intent, "nice one")
			store.saved["$account|$slot"] = stored(store)
				.copy(parentAuthor = author, parentPermlink = permlink).toJson()

			val outcome = pub.publishReply(account, target, intent, "nice one")

			assertTrue("'$author'/'$permlink': got $outcome", outcome is SnapPublisher.Outcome.Failed)
			assertEquals(0, hive.preparedOps.size)
			assertEquals(0, hive.broadcasts)
		}
	}

	/** A restart changes nothing: same parent, same permlink, same words. */
	@Test
	fun `a restart resumes the same parent and the same identity`() {
		val store = MemoryStore()
		publisher(FakeHive(), store).intendReply(account, target, intent, "nice one")
		val frozen = stored(store)

		// A second publisher over the same durable bytes is how process death
		// is expressed here.
		val hive = FakeHive(mutableListOf(inBlock()))
		publisher(hive, store).publishReply(account, target, intent, "completely different")

		val op = hive.preparedOps.single()
		assertEquals(frozen.parentAuthor, op.parentAuthor)
		assertEquals(frozen.parentPermlink, op.parentPermlink)
		assertEquals(frozen.permlink, op.permlink)
		assertEquals("nice one", op.body)
	}

	/** Two retries cannot produce two identities or two parents. */
	@Test
	fun `retrying twice cannot mint a second reply`() {
		// Preparation fails the first time, so the record stays an intent and
		// the retry has something to resume.
		val hive = FakeHive(mutableListOf(inBlock()))
		val store = MemoryStore()
		val pub = publisher(hive, store)
		pub.intendReply(account, target, intent, "nice one")

		pub.publishReply(account, target, intent, "nice one")
		pub.publishReply(account, target, intent, "nice one")

		assertEquals("one signature", 1, hive.preparedOps.size)
		assertEquals("one broadcast", 1, hive.broadcasts)
		assertEquals("one record", 1, store.entries(account).size)
	}

	/** The root path keeps its own rule: the container fills an empty parent. */
	@Test
	fun `a root intent still takes its parent from the container`() {
		val hive = FakeHive(mutableListOf(inBlock()))
		val store = MemoryStore()
		val pub = publisher(hive, store)
		pub.intendRoot(account, "event-1", SnapMedia("8pSS6wdojqY"), "hello")

		pub.retryRoot(account, "event-1")

		val op = hive.preparedOps.single()
		assertEquals("peak.snaps", op.parentAuthor)
		assertEquals("snap-container-1789648560", op.parentPermlink)
	}

	/** But a root intent that somehow carries a parent is refused. */
	@Test
	fun `a root intent carrying a parent is not signed`() {
		val hive = FakeHive(mutableListOf(inBlock()))
		val store = MemoryStore()
		val pub = publisher(hive, store)
		pub.intendRoot(account, "event-1", SnapMedia("8pSS6wdojqY"), "hello")
		val tampered = (store.read(account, "event-1") as PendingSnapRead.Present).snap
			.copy(parentAuthor = "carol", parentPermlink = "somewhere-else")
		store.saved["$account|event-1"] = tampered.toJson()

		val outcome = pub.retryRoot(account, "event-1")

		assertTrue("got $outcome", outcome is SnapPublisher.Outcome.Failed)
		assertEquals(0, hive.preparedOps.size)
		assertEquals(0, hive.broadcasts)
	}

	/**
	 * An expired prepared transaction is re-signed against the **record**.
	 *
	 * The window a crash opens: signed, committed, never sent, resumed more
	 * than a minute later, so the old bytes are guaranteed to be refused and
	 * have to be rebuilt. The permlink survives that — it always did — and so
	 * now do the parent and the words, which is the whole point: the caller at
	 * that moment is the thread controller, passing whatever the composer holds
	 * now, and the record is the only copy of what the user actually sent.
	 */
	@Test
	fun `re-signing an expired reply keeps the frozen parent, words and permlink`() {
		val store = MemoryStore()
		// A transaction that expired long before the clock this publisher runs
		// on — committed, never broadcast.
		publisher(FakeHive(), store).intendReply(account, target, intent, "nice one")
		val frozen = stored(store)
		store.saved["$account|$slot"] = frozen.copy(
			state = PendingSnapState.PREPARED,
			signedTransactionJson = """{"op":"stale"}""",
			txId = "tx-stale",
			expirationEpochSec = 900L,
		).toJson()

		val hive = FakeHive(mutableListOf(inBlock()))
		publisher(hive, store).publishReply(account, target, intent, "completely different")

		val op = hive.preparedOps.single()
		assertEquals(frozen.permlink, op.permlink)
		assertEquals(frozen.parentAuthor, op.parentAuthor)
		assertEquals(frozen.parentPermlink, op.parentPermlink)
		assertEquals("nice one", op.body)
		assertEquals(frozen.jsonMetadata, op.jsonMetadata)
		assertEquals("one comment", 1, hive.broadcasts)
	}
}
