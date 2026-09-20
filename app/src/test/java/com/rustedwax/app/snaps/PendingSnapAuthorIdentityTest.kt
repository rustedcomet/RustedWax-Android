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
 * One account, three places it is written down, and they must agree.
 *
 * A record is stored under an account key, carries an `author`, and is acted on
 * by whoever is signed in now. Those are three separate strings, and until this
 * they could disagree: a record filed under Alice claiming author Bob would
 * draw a card as Bob's, then sign a comment as whoever the caller happened to
 * be. The local half and the irreversible half would describe different people,
 * and only one of them can be right.
 *
 * The invariant, in one line: **requested account == record.account ==
 * record.author, case-insensitively, and that name is shaped like a Hive
 * account.** Anything else is locked — never absent, never repaired, never
 * normalised, never rendered as a valid local post.
 *
 * Two halves, in this order, and the order is the point:
 *
 *  - **validated as stored.** Both names must be valid Hive account names
 *    exactly as they sit on disk — not lowercased first. Canonical Hive names
 *    are lowercase, so a durable "Alice" is not a spelling of a real account;
 *    it is a value this app never wrote and one the chain would refuse. The
 *    frozen author reaches Hive unchanged, so it must be acceptable *before*
 *    anything is built around it, and normalising it would mean guessing which
 *    account the record meant and then signing as that;
 *  - **then compared case-insensitively.** Which by then is a comparison
 *    between two canonical names, so the tolerance costs nothing — it exists
 *    because the caller's own spelling comes from the vault, outside this
 *    record's control, and `HiveSnapPort` binds identities the same way.
 */
class PendingSnapAuthorIdentityTest {

	private val account = "alice"
	private val eventId = "event-1"
	private val target = SnapReplyTarget.of("bob", "rustedwax-snap-1000-aaaaaa")!!
	private val intent = "a1b2c3d4e5f60718"
	private val slot = SnapReplyKey.of(target, intent)

	// ── fakes ──────────────────────────────────────────────────────────

	/**
	 * The real store's rules, without Android.
	 *
	 * Deliberately routed through [PendingSnapIntegrity] rather than
	 * re-implemented, because the integrity pass is half of what is being
	 * tested: a fake that judged records by its own rule would prove only that
	 * it agrees with itself.
	 */
	private class Store : PendingSnapStore {
		val saved = mutableMapOf<String, String>()

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
			saved["${snap.account}|${snap.eventId}"] = snap.toJson(); return true
		}
		override fun clear(account: String, eventId: String) { saved.remove("$account|$eventId") }
		override fun all(account: String) = PendingSnapIntegrity.valid(account, entries(account))
		override fun corruptEventIds(account: String) =
			PendingSnapIntegrity.lockedEventIds(account, entries(account))
	}

	private class Hive : SnapHivePort {
		var broadcasts = 0
		var containerLookups = 0
		val preparedOps = mutableListOf<TxSerializer.CommentOp>()
		/** The captured author each preparation was bound to. */
		val preparedFor = mutableListOf<String>()
		override fun resolveContainer(): SnapContainerResolver.Result {
			containerLookups++
			return SnapContainerResolver.Result.Resolved(
				SnapContainer("peak.snaps", "snap-container-1789648560", "2026-09-17T12:36:00"),
			)
		}
		override fun prepareComment(
			operation: TxSerializer.CommentOp,
			author: String,
		): HivePreparationResult {
			preparedOps += operation
			preparedFor += author
			return HivePreparationResult.Ready(
				PreparedHiveTransaction(
					"""{"op":"${operation.permlink}"}""",
					"tx-${operation.permlink}",
					2_000_000_000L,
				),
			)
		}
		override fun broadcastPrepared(
			prepared: PreparedHiveTransaction,
			author: String,
		): HiveRpc.BroadcastResult {
			broadcasts++
			return HiveRpc.BroadcastResult.Success(
				prepared.txId,
				"node",
				HiveRpc.BroadcastResult.Evidence.BLOCK,
			)
		}
		override fun observeTransaction(txId: String, expirationEpochSec: Long) =
			HiveRpc.TransactionEvidence.UNAVAILABLE
		override fun contentExists(author: String, permlink: String): Boolean? = false
	}

	private class Reader : PostedSnapReader {
		override fun read(author: String, permlink: String): PostedSnapContent? = null
	}

	/** Minted from a pool shared across this test's publishers, never reused. */
	private var minted = 0

	private fun publisher(hive: Hive, store: Store) = SnapPublisher(
		hive = hive,
		store = store,
		nowEpochSec = { 1_000L },
		newPermlink = { "rustedwax-snap-1000-m${minted++}" },
		newReplyPermlink = { "rustedwax-reply-1000-m${minted++}" },
	)

	/** A root body the display path will accept, so refusals are about identity. */
	private fun rootBody(text: String) =
		"$text\n\nhttps://youtu.be/8pSS6wdojqY\n\n#rustedwax #scrobblelife #music"

	private fun record(
		eventId: String = this.eventId,
		kind: PendingSnapKind = PendingSnapKind.ROOT,
		state: PendingSnapState = PendingSnapState.INTENT,
		account: String = this.account,
		author: String = this.account,
	) = PendingSnap(
		account = account,
		eventId = eventId,
		author = author,
		permlink = if (kind == PendingSnapKind.REPLY) {
			"rustedwax-reply-1000-frozen"
		} else {
			"rustedwax-snap-1000-frozen"
		},
		parentAuthor = if (kind == PendingSnapKind.REPLY) target.author else "",
		parentPermlink = if (kind == PendingSnapKind.REPLY) target.permlink else "",
		body = if (kind == PendingSnapKind.REPLY) "nice one" else rootBody("hello"),
		jsonMetadata = """{"app":"rustedwax/test"}""",
		signedTransactionJson = if (state == PendingSnapState.INTENT) "" else """{"op":"x"}""",
		txId = if (state == PendingSnapState.INTENT) "" else "tx-frozen",
		expirationEpochSec = if (state == PendingSnapState.INTENT) 0L else 2_000_000_000L,
		state = state,
		createdAtEpochSec = 900L,
		updatedAtEpochSec = 900L,
		kind = kind,
	)

	/** Files a record under one account while it claims another author. */
	private fun Store.plant(snap: PendingSnap, under: String = account) {
		saved["$under|${snap.eventId}"] = snap.toJson()
	}

	// ── the durable rule ───────────────────────────────────────────────

	@Test
	fun `a record whose author is somebody else is locked`() {
		val store = Store()
		store.plant(record(author = "bob"))

		assertTrue(
			"must be Corrupt, never Absent",
			store.read(account, eventId) is PendingSnapRead.Corrupt,
		)
		assertEquals("and never enumerated", emptyList<PendingSnap>(), store.all(account))
		assertEquals(setOf(eventId), store.corruptEventIds(account))
	}

	@Test
	fun `a blank or unusable stored author is locked`() {
		listOf("", "   ", "al", "Alice Smith", "alice!", ".alice", "alice.", "a..b", "-alice")
			.forEach { author ->
				val store = Store()
				store.plant(record(author = author))
				assertTrue(
					"author '$author' must be Corrupt",
					store.read(account, eventId) is PendingSnapRead.Corrupt,
				)
			}
	}

	@Test
	fun `a blank stored account is locked too`() {
		val snap = record(account = "", author = "")
		assertTrue(PendingSnapIntegrity.authorProblem(snap) != null)
	}

	/**
	 * An uppercase durable name is not a spelling — it is a broken record.
	 *
	 * Every pairing fails, including the one where the two agree with each
	 * other, because agreement is not the only rule: the name itself has to be
	 * one Hive could accept. Stored identity is what gets signed, and `Alice`
	 * is not an account.
	 */
	@Test
	fun `an uppercase stored identity is locked, however the two agree`() {
		listOf(
			"alice" to "Alice",
			"Alice" to "alice",
			"Alice" to "Alice",
			"ALICE" to "ALICE",
		).forEach { (storedAccount, storedAuthor) ->
			val snap = record(account = storedAccount, author = storedAuthor)
			assertTrue(
				"'$storedAccount'/'$storedAuthor' must be refused",
				PendingSnapIntegrity.authorProblem(snap) != null,
			)
			assertTrue(
				PendingSnapIntegrity.identityProblem(snap, "alice") != null,
			)

			val store = Store()
			store.saved["$storedAccount|$eventId"] = snap.toJson()
			assertTrue(
				"'$storedAccount'/'$storedAuthor' must read as Corrupt, never Absent",
				store.read(storedAccount, eventId) is PendingSnapRead.Corrupt,
			)
		}
	}

	@Test
	fun `a canonical lowercase identity is valid`() {
		assertNull(PendingSnapIntegrity.authorProblem(record()))
		assertNull(PendingSnapIntegrity.identityProblem(record(), "alice"))
	}

	/**
	 * The caller's own spelling is still tolerated.
	 *
	 * It comes from the vault rather than from the record, and the comparison
	 * runs only after both stored names have passed validation — so this is a
	 * tolerance about who is asking, never about what is stored.
	 */
	@Test
	fun `the caller may be spelled differently from the valid stored name`() {
		listOf("alice", "Alice", "ALICE").forEach { asking ->
			assertNull("'$asking' asking", PendingSnapIntegrity.identityProblem(record(), asking))
		}
	}

	/** A record that is fine on its own is still not another account's to use. */
	@Test
	fun `a coherent record still belongs to only one caller`() {
		val snap = record()

		assertNull(PendingSnapIntegrity.identityProblem(snap, "alice"))
		assertTrue(PendingSnapIntegrity.identityProblem(snap, "bob") != null)
		assertTrue(PendingSnapIntegrity.identityProblem(snap, "") != null)
	}

	// ── nothing prepares, signs or sends ───────────────────────────────

	@Test
	fun `a wrong-author root intent is never prepared, signed or sent`() {
		val hive = Hive()
		val store = Store()
		store.plant(record(author = "bob"))
		val before = store.saved.toMap()
		val pub = publisher(hive, store)

		val staged = pub.intendRoot(account, eventId, SnapMedia("8pSS6wdojqY"), "hello")
		val published = pub.publishRoot(account, eventId, SnapMedia("8pSS6wdojqY"), "hello")

		assertTrue("got $staged", staged is SnapPublisher.Staged.Uncertain)
		assertTrue("got $published", published is SnapPublisher.Outcome.Uncertain)
		assertEquals("no container asked for", 0, hive.containerLookups)
		assertEquals("nothing signed", 0, hive.preparedOps.size)
		assertEquals("nothing sent", 0, hive.broadcasts)
		assertEquals("and nothing written — no fresh permlink", before, store.saved)
	}

	@Test
	fun `a wrong-author reply intent is never prepared, signed or sent`() {
		val hive = Hive()
		val store = Store()
		store.plant(record(eventId = slot, kind = PendingSnapKind.REPLY, author = "bob"))
		val before = store.saved.toMap()
		val pub = publisher(hive, store)

		val staged = pub.intendReply(account, target, intent, "nice one")
		val published = pub.publishReply(account, target, intent, "nice one")

		assertTrue("got $staged", staged is SnapPublisher.Staged.Uncertain)
		assertTrue("got $published", published is SnapPublisher.Outcome.Uncertain)
		assertEquals(0, hive.preparedOps.size)
		assertEquals(0, hive.broadcasts)
		assertEquals(before, store.saved)
	}

	@Test
	fun `a wrong-author prepared transaction is never delivered`() {
		val hive = Hive()
		val store = Store()
		store.plant(record(state = PendingSnapState.PREPARED, author = "bob"))

		val outcome = publisher(hive, store).deliver(account, eventId, PendingSnapKind.ROOT)

		assertTrue("got $outcome", outcome is SnapPublisher.Outcome.Uncertain)
		assertEquals("ZERO transmission", 0, hive.broadcasts)
		assertEquals(
			"state untouched",
			PendingSnapState.PREPARED,
			PendingSnap.fromJson(store.saved.values.single())!!.state,
		)
	}

	@Test
	fun `a wrong-author record cannot be retried`() {
		listOf(PendingSnapState.INTENT, PendingSnapState.PREPARED).forEach { state ->
			val hive = Hive()
			val store = Store()
			store.plant(record(state = state, author = "bob"))

			val outcome = publisher(hive, store).retryRoot(account, eventId)

			assertTrue("$state: got $outcome", outcome is SnapPublisher.Outcome.Uncertain)
			assertEquals("$state: nothing signed", 0, hive.preparedOps.size)
			assertEquals("$state: nothing sent", 0, hive.broadcasts)
		}
	}

	@Test
	fun `a wrong-author record cannot be reconciled into an actionable state`() {
		val hive = Hive()
		val store = Store()
		store.plant(record(state = PendingSnapState.ACCEPTED_UNCONFIRMED, author = "bob"))
		val pub = publisher(hive, store)

		val outcome = pub.reconcile(account, eventId, PendingSnapKind.ROOT)

		assertTrue("got $outcome", outcome is SnapPublisher.Outcome.Uncertain)
		assertEquals(
			"the row is not advanced",
			PendingSnapState.ACCEPTED_UNCONFIRMED,
			PendingSnap.fromJson(store.saved.values.single())!!.state,
		)
		assertTrue(
			"and it is never read as decided",
			pub.isUnresolved(account, eventId, PendingSnapKind.ROOT),
		)
		assertEquals(
			"an interrupted somebody-else is not an interrupted Snap of ours",
			false,
			pub.isInterrupted(account, eventId, PendingSnapKind.ROOT),
		)
	}

	/**
	 * The caller is the third identity, and another account's call cannot
	 * reach this record.
	 *
	 * Two locks, and it is worth being precise about which does the work. The
	 * store files records by `account|eventId`, so Bob's call does not find
	 * Alice's row at all — it starts one of his own, under his own key and his
	 * own author, which is correct: Bob posting his own Snap about the same
	 * History row is an ordinary thing to do. What must never happen is Bob's
	 * call moving, consuming or re-signing *Alice's* record, and that is what
	 * this pins. The gate's account check is the second lock, for a record
	 * that is somehow reached under the wrong name — see
	 * `a coherent record still belongs to only one caller`.
	 */
	@Test
	fun `another account's call never touches this account's record`() {
		val hive = Hive()
		val store = Store()
		publisher(hive, store).intendRoot(account, eventId, SnapMedia("8pSS6wdojqY"), "hello")
		val frozen = PendingSnap.fromJson(store.saved["$account|$eventId"]!!)!!

		val pub = publisher(hive, store)
		pub.intendRoot("bob", eventId, SnapMedia("8pSS6wdojqY"), "hello")
		pub.retryRoot("bob", eventId)

		assertEquals(
			"Alice's record is byte-identical",
			frozen,
			PendingSnap.fromJson(store.saved["$account|$eventId"]!!),
		)
		assertEquals("nothing was signed as Alice", listOf("bob"), hive.preparedOps.map { it.author })
		assertTrue(
			"and never under her permlink",
			hive.preparedOps.none { it.permlink == frozen.permlink },
		)
	}

	/**
	 * A record the gate refuses is refused for *every* actionable call.
	 *
	 * Stated as one sweep rather than trusting that each entry point happens
	 * to go through the gate: these are the calls that can render, mint, sign,
	 * send or advance state, and a wrong-author record must move none of them.
	 */
	@Test
	fun `every actionable entry point refuses a wrong-author record`() {
		val hive = Hive()
		val store = Store()
		store.plant(record(author = "bob"))
		store.plant(record(eventId = slot, kind = PendingSnapKind.REPLY, author = "bob"))
		val before = store.saved.toMap()
		val pub = publisher(hive, store)

		pub.intendRoot(account, eventId, SnapMedia("8pSS6wdojqY"), "hello")
		pub.publishRoot(account, eventId, SnapMedia("8pSS6wdojqY"), "hello")
		pub.retryRoot(account, eventId)
		pub.deliver(account, eventId, PendingSnapKind.ROOT)
		pub.reconcile(account, eventId, PendingSnapKind.ROOT)
		pub.intendReply(account, target, intent, "nice one")
		pub.publishReply(account, target, intent, "nice one")
		pub.deliver(account, slot, PendingSnapKind.REPLY)
		pub.reconcile(account, slot, PendingSnapKind.REPLY)
		pub.abandonIntendedReply(account, target, intent)

		assertEquals("nothing signed", 0, hive.preparedOps.size)
		assertEquals("nothing sent", 0, hive.broadcasts)
		assertEquals("no container even resolved", 0, hive.containerLookups)
		assertEquals("nothing written, nothing deleted", before, store.saved)
	}

	// ── nothing renders ────────────────────────────────────────────────

	@Test
	fun `a wrong-author record is never drawn as a History card`() {
		listOf(PendingSnapState.CONFIRMED, PendingSnapState.INTENT).forEach { state ->
			val store = Store()
			store.plant(record(state = state, author = "bob"))
			val posted = PostedSnaps(store, Reader())

			assertNull("$state: local", posted.local(account, eventId))
			assertNull("$state: staged", posted.staged(account, eventId))
		}
	}

	@Test
	fun `a wrong-author record is never restored as this account's`() {
		val store = Store()
		store.plant(record(state = PendingSnapState.CONFIRMED, author = "bob"))
		store.plant(record(eventId = slot, kind = PendingSnapKind.REPLY, author = "bob"))
		val pub = publisher(Hive(), store)

		assertEquals(emptyList<Any>(), pub.restoreLocal(account))
		assertEquals(emptyList<Any>(), pub.restoreStaged(account))
		assertEquals(emptyList<Any>(), pub.restoreStagedReplies(account))
		assertEquals("and no optimistic thread row", emptyList<SnapReply>(), pub.stagedReplyRows(account))
	}

	/** Locked, but not hidden: it is still something the user may need told. */
	@Test
	fun `a wrong-author record is surfaced as unresolved, never as published`() {
		val hive = Hive()
		val store = Store()
		store.plant(record(state = PendingSnapState.CONFIRMED, author = "bob"))

		val restored = publisher(hive, store).restore(account)

		assertEquals(listOf(eventId), restored.map { it.first })
		assertTrue(
			"got ${restored.single().second}",
			restored.single().second is SnapPublisher.Outcome.Uncertain,
		)
		assertEquals(0, hive.broadcasts)
	}

	// ── the frozen author reaches Hive ─────────────────────────────────

	/**
	 * What the card showed is what gets signed, down to the spelling.
	 *
	 * The caller asks as "Alice"; the record — and the optimistic card — say
	 * "alice". The comment is signed as "alice", because the identity comes off
	 * the durable record rather than off the parameter.
	 */
	/**
	 * Preparation reads the author off the durable record.
	 *
	 * With the stored-identity rule in force there is no *valid* record whose
	 * author differs from the account that may act on it, so this cannot be
	 * shown by planting a mismatch — a mismatch is locked, which is the point.
	 * What it asserts instead is that the signed operation equals the record
	 * on disk, field for field, after a resume that happens long after the tap
	 * that froze it. The structural half — that the operation is built from
	 * the record rather than from the parameter — is pinned in
	 * `SnapQueueIsolationTest`.
	 */
	@Test
	fun `preparation signs the identity the durable record holds`() {
		val hive = Hive()
		val store = Store()
		val pub = publisher(hive, store)
		pub.intendRoot(account, eventId, SnapMedia("8pSS6wdojqY"), "hello")
		val frozen = PendingSnap.fromJson(store.saved.values.single())!!

		publisher(hive, store).retryRoot(account, eventId)

		val op = hive.preparedOps.single()
		assertEquals(frozen.author, op.author)
		assertEquals("and the port is bound to the same name", frozen.author, hive.preparedFor.single())
		assertEquals(frozen.permlink, op.permlink)
		assertEquals(frozen.body, op.body)
		assertEquals(frozen.jsonMetadata, op.jsonMetadata)
		assertNull(
			"the record it came from is a valid identity",
			PendingSnapIntegrity.authorProblem(frozen),
		)
	}

	/** A fresh publication still originates from the account asking. */
	@Test
	fun `a first publication signs the account that asked`() {
		val hive = Hive()
		val store = Store()

		publisher(hive, store).publishRoot(account, eventId, SnapMedia("8pSS6wdojqY"), "hello")

		assertEquals(account, hive.preparedOps.single().author)
		assertEquals(
			account,
			PendingSnap.fromJson(store.saved.values.single())!!.author,
		)
	}

	@Test
	fun `a reply signs its stored author and its stored parent together`() {
		val hive = Hive()
		val store = Store()
		val pub = publisher(hive, store)
		pub.intendReply(account, target, intent, "nice one")
		val frozen = PendingSnap.fromJson(store.saved.values.single())!!

		publisher(hive, store).publishReply(account, target, intent, "nice one")

		val op = hive.preparedOps.single()
		assertEquals(frozen.author, op.author)
		assertEquals(frozen.parentAuthor, op.parentAuthor)
		assertEquals(frozen.parentPermlink, op.parentPermlink)
		assertEquals(frozen.permlink, op.permlink)
		assertEquals(frozen.body, op.body)
		assertEquals(frozen.jsonMetadata, op.jsonMetadata)
		assertEquals(1, hive.broadcasts)
	}

	/** And a restart does not change whose comment it is. */
	@Test
	fun `a restart re-signs under the same stored author`() {
		val store = Store()
		publisher(Hive(), store).intendRoot(account, eventId, SnapMedia("8pSS6wdojqY"), "hello")
		val frozen = PendingSnap.fromJson(store.saved.values.single())!!

		val hive = Hive()
		publisher(hive, store).retryRoot(account, eventId)

		assertEquals(frozen.author, hive.preparedOps.single().author)
		assertEquals(frozen.permlink, hive.preparedOps.single().permlink)
		assertEquals(
			"and the stored record still names one account",
			null,
			PendingSnapIntegrity.authorProblem(
				PendingSnap.fromJson(store.saved.values.single())!!,
			),
		)
	}

	/**
	 * The whole local-first chain, stated once for each path.
	 *
	 * The durable intent is what the screen draws from and what the signature
	 * is built from, so the identity on the card and the identity on the chain
	 * are the same object read twice — not two derivations that happen to
	 * agree.
	 */
	@Test
	fun `the optimistic identity and the signed identity are the same record`() {
		val hive = Hive()
		val store = Store()
		val pub = publisher(hive, store)

		// ROOT: intent → what a card would draw → what is signed.
		pub.intendRoot(account, eventId, SnapMedia("8pSS6wdojqY"), "hello")
		val rootIntent = PendingSnap.fromJson(store.saved["$account|$eventId"]!!)!!
		val card = PostedSnaps(store, Reader()).staged(account, eventId)!!
		pub.retryRoot(account, eventId)
		val rootOp = hive.preparedOps.single()

		assertEquals(rootIntent.author, card.author)
		assertEquals(rootIntent.permlink, card.permlink)
		assertEquals(rootIntent.author, rootOp.author)
		assertEquals(rootIntent.permlink, rootOp.permlink)
		assertEquals(rootIntent.body, rootOp.body)
		assertEquals(rootIntent.jsonMetadata, rootOp.jsonMetadata)

		// REPLY: intent → the row a thread draws → what is signed.
		pub.intendReply(account, target, intent, "nice one")
		val replyIntent = PendingSnap.fromJson(store.saved["$account|$slot"]!!)!!
		val row = pub.stagedReplyRows(account).single()
		pub.publishReply(account, target, intent, "nice one")
		val replyOp = hive.preparedOps.last()

		assertEquals(replyIntent.author, row.author)
		assertEquals(replyIntent.permlink, row.permlink)
		assertEquals(replyIntent.parentAuthor, row.parentAuthor)
		assertEquals(replyIntent.parentPermlink, row.parentPermlink)
		assertEquals(replyIntent.author, replyOp.author)
		assertEquals(replyIntent.permlink, replyOp.permlink)
		assertEquals(replyIntent.parentAuthor, replyOp.parentAuthor)
		assertEquals(replyIntent.parentPermlink, replyOp.parentPermlink)
		assertEquals(replyIntent.body, replyOp.body)
	}
}
