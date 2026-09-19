package com.rustedwax.app.snaps

import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.SnapContainer
import com.rustedwax.hive.SnapContainerResolver
import com.rustedwax.hive.TxSerializer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The durable `kind` fails closed, and no write path consumes another's record.
 *
 * Two paths write comments through one publisher, and they build **different
 * comments**: a root Snap hangs off the `peak.snaps` container, a reply off the
 * comment the user tapped. So a record is not merely "a pending comment" — it
 * belongs to one path, and a record consumed by the other would sign a parent
 * nobody chose under a permlink minted for something else. In production the
 * two also live in separate preference files; this is the second lock, the one
 * that does not depend on the wiring being right.
 *
 * The rule, in one line: **missing, unrecognised or mismatched kind is
 * Corrupt/refused — never Absent, and never quietly reclassified as a root
 * Snap.** Absent is the dangerous reading, because absence is what invites a
 * fresh permlink and a second public comment.
 *
 * On legacy records: [PendingSnap] has written `kind` in `toJson` since the
 * commit that introduced the class, so no record this app has ever stored can
 * lack one. A record without `kind` was not written by RustedWax, and that is
 * why there is no compatibility default here — see
 * `a stored record has always carried a kind`, which pins that property of the
 * writer rather than trusting the claim.
 */
class PendingSnapKindTest {

	private val account = "rustedwaxtest"
	private val eventId = "event-1"
	private val target = SnapReplyTarget.of("alice", "rustedwax-snap-1000-aaaaaa")!!
	private val intent = "a1b2c3d4e5f60718"
	private val slot = SnapReplyKey.of(target, intent)

	// ── fakes ──────────────────────────────────────────────────────────

	/** Mirrors the real store's three-way answer, including Corrupt. */
	private class Store : PendingSnapStore {
		val saved = mutableMapOf<String, String>()
		override fun read(account: String, eventId: String): PendingSnapRead {
			val raw = saved["$account|$eventId"] ?: return PendingSnapRead.Absent
			return PendingSnap.fromJson(raw)?.let(PendingSnapRead::Present)
				?: PendingSnapRead.Corrupt("stored Snap state could not be read")
		}
		override fun write(snap: PendingSnap): Boolean {
			saved["${snap.account}|${snap.eventId}"] = snap.toJson(); return true
		}
		override fun clear(account: String, eventId: String) { saved.remove("$account|$eventId") }
		override fun all(account: String) = saved.entries
			.filter { it.key.startsWith("$account|") }
			.mapNotNull { PendingSnap.fromJson(it.value) }
		override fun corruptEventIds(account: String) = saved.entries
			.filter { it.key.startsWith("$account|") && PendingSnap.fromJson(it.value) == null }
			.map { it.key.substringAfter('|') }
			.toSet()
	}

	private class Hive : SnapHivePort {
		var prepares = 0
		var broadcasts = 0
		var containerLookups = 0
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
			prepares++
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

	private fun publisher(hive: Hive, store: Store) = SnapPublisher(
		hive = hive,
		store = store,
		nowEpochSec = { 1_000L },
		newPermlink = { "rustedwax-snap-1000-aaaaaa" },
		newReplyPermlink = { "rustedwax-reply-1000-bbbbbb" },
	)

	private fun record(
		eventId: String,
		kind: PendingSnapKind,
		state: PendingSnapState,
	) = PendingSnap(
		account = account,
		eventId = eventId,
		author = account,
		permlink = if (kind == PendingSnapKind.REPLY) {
			"rustedwax-reply-1000-frozen"
		} else {
			"rustedwax-snap-1000-frozen"
		},
		parentAuthor = if (kind == PendingSnapKind.REPLY) target.author else "",
		parentPermlink = if (kind == PendingSnapKind.REPLY) target.permlink else "",
		body = "nice one",
		jsonMetadata = """{"app":"rustedwax/test"}""",
		signedTransactionJson = if (state == PendingSnapState.INTENT) "" else """{"op":"x"}""",
		txId = if (state == PendingSnapState.INTENT) "" else "tx-frozen",
		expirationEpochSec = if (state == PendingSnapState.INTENT) 0L else 2_000_000_000L,
		state = state,
		createdAtEpochSec = 900L,
		updatedAtEpochSec = 900L,
		kind = kind,
	)

	/** The stored JSON with one field rewritten, as a foreign writer might. */
	private fun withKind(snap: PendingSnap, kind: String?): String {
		val o = JSONObject(snap.toJson())
		if (kind == null) o.remove("kind") else o.put("kind", kind)
		return o.toString()
	}

	// ── parsing fails closed ───────────────────────────────────────────

	@Test
	fun `a record with no kind at all is unreadable`() {
		val raw = withKind(record(eventId, PendingSnapKind.ROOT, PendingSnapState.INTENT), null)

		assertNull("missing kind must not parse", PendingSnap.fromJson(raw))

		val store = Store().apply { saved["$account|$eventId"] = raw }
		assertTrue(
			"and the store must call it Corrupt, never Absent",
			store.read(account, eventId) is PendingSnapRead.Corrupt,
		)
	}

	@Test
	fun `an unrecognised kind is unreadable`() {
		listOf("", "   ", "ROOT", "root", "snap", "reply_v2", "null").forEach { kind ->
			val raw = withKind(record(eventId, PendingSnapKind.ROOT, PendingSnapState.INTENT), kind)
			assertNull("kind '$kind' must not parse", PendingSnap.fromJson(raw))
		}
	}

	@Test
	fun `an explicit JSON null kind is unreadable`() {
		val o = JSONObject(record(eventId, PendingSnapKind.ROOT, PendingSnapState.INTENT).toJson())
		o.put("kind", JSONObject.NULL)

		assertNull(PendingSnap.fromJson(o.toString()))
	}

	@Test
	fun `both kinds round-trip, and their durable spelling is fixed`() {
		PendingSnapKind.entries.forEach { kind ->
			val snap = record(eventId, kind, PendingSnapState.PREPARED)
			assertEquals(snap, PendingSnap.fromJson(snap.toJson()))
		}
		// The spelling is what is already on every device. Changing it would
		// make every stored record unreadable — which fails closed, but also
		// strands Snaps nobody can finish.
		assertEquals("root_snap", PendingSnapKind.ROOT.stored)
		assertEquals("reply", PendingSnapKind.REPLY.stored)
		assertEquals(PendingSnapKind.ROOT, PendingSnapKind.of("root_snap"))
		assertEquals(PendingSnapKind.REPLY, PendingSnapKind.of("reply"))
		assertNull(PendingSnapKind.of(null))
		assertNull(PendingSnapKind.of("root"))
	}

	/**
	 * The legacy question, answered by the writer rather than by assumption.
	 *
	 * Every state, both kinds: the serialiser always emits `kind`. That is why
	 * refusing a record without one strands nothing — no build of this app has
	 * ever written such a record.
	 */
	@Test
	fun `a stored record has always carried a kind`() {
		PendingSnapState.entries.forEach { state ->
			PendingSnapKind.entries.forEach { kind ->
				val raw = JSONObject(record(eventId, kind, state).toJson())
				assertTrue("$kind/$state must serialise a kind", raw.has("kind"))
				assertEquals(kind.stored, raw.getString("kind"))
			}
		}
	}

	// ── no path consumes the other path's record ───────────────────────

	@Test
	fun `a root intent cannot be published through the reply path`() {
		val hive = Hive()
		val store = Store()
		// A root record filed, improbably, under a reply's event id.
		store.write(record(slot, PendingSnapKind.ROOT, PendingSnapState.INTENT))

		val outcome = publisher(hive, store).publishReply(account, target, intent, "nice one")

		assertTrue("got $outcome", outcome is SnapPublisher.Outcome.Uncertain)
		assertEquals("nothing signed", 0, hive.prepares)
		assertEquals("nothing sent", 0, hive.broadcasts)
		assertEquals("and the record is untouched", 1, store.saved.size)
		assertEquals(
			PendingSnapKind.ROOT,
			PendingSnap.fromJson(store.saved.values.single())!!.kind,
		)
	}

	@Test
	fun `a reply intent cannot be published through the root path`() {
		val hive = Hive()
		val store = Store()
		store.write(record(eventId, PendingSnapKind.REPLY, PendingSnapState.INTENT))

		val outcome = publisher(hive, store)
			.publishRoot(account, eventId, SnapMedia("8pSS6wdojqY"), "hello")

		assertTrue("got $outcome", outcome is SnapPublisher.Outcome.Uncertain)
		assertEquals(0, hive.prepares)
		assertEquals(0, hive.broadcasts)
		assertEquals("no container was even asked for", 0, hive.containerLookups)
	}

	@Test
	fun `a wrong-kind intent is not re-intended either`() {
		val hive = Hive()
		val store = Store()
		store.write(record(eventId, PendingSnapKind.REPLY, PendingSnapState.INTENT))
		val before = store.saved.toMap()

		val staged = publisher(hive, store)
			.intendRoot(account, eventId, SnapMedia("8pSS6wdojqY"), "hello")

		assertTrue("got $staged", staged is SnapPublisher.Staged.Uncertain)
		assertEquals("no fresh identity was minted over it", before, store.saved)
	}

	@Test
	fun `a wrong-kind prepared transaction is never delivered`() {
		val hive = Hive()
		val store = Store()
		store.write(record(eventId, PendingSnapKind.REPLY, PendingSnapState.PREPARED))

		val outcome = publisher(hive, store).deliver(account, eventId, PendingSnapKind.ROOT)

		assertTrue("got $outcome", outcome is SnapPublisher.Outcome.Uncertain)
		assertEquals("ZERO transmission", 0, hive.broadcasts)
		assertEquals(
			"and its state is unchanged",
			PendingSnapState.PREPARED,
			PendingSnap.fromJson(store.saved.values.single())!!.state,
		)
	}

	@Test
	fun `a wrong-kind record cannot be retried`() {
		listOf(PendingSnapState.INTENT, PendingSnapState.PREPARED).forEach { state ->
			val hive = Hive()
			val store = Store()
			store.write(record(eventId, PendingSnapKind.REPLY, state))

			val outcome = publisher(hive, store).retryRoot(account, eventId)

			assertTrue("$state: got $outcome", outcome is SnapPublisher.Outcome.Uncertain)
			assertEquals("$state: nothing signed", 0, hive.prepares)
			assertEquals("$state: nothing sent", 0, hive.broadcasts)
		}
	}

	@Test
	fun `a wrong-kind record is not reconciled, and answers unresolved`() {
		val hive = Hive()
		val store = Store()
		store.write(record(eventId, PendingSnapKind.REPLY, PendingSnapState.ACCEPTED_UNCONFIRMED))
		val pub = publisher(hive, store)

		val outcome = pub.reconcile(account, eventId, PendingSnapKind.ROOT)

		assertTrue("got $outcome", outcome is SnapPublisher.Outcome.Uncertain)
		assertEquals(
			"the row is not advanced by a path it does not belong to",
			PendingSnapState.ACCEPTED_UNCONFIRMED,
			PendingSnap.fromJson(store.saved.values.single())!!.state,
		)
		assertTrue(
			"and it is never mistaken for a decided row",
			pub.isUnresolved(account, eventId, PendingSnapKind.ROOT),
		)
	}

	@Test
	fun `a wrong-kind record is not an unfinished one either`() {
		val store = Store()
		store.write(record(eventId, PendingSnapKind.REPLY, PendingSnapState.INTENT))

		assertEquals(
			"an interrupted *reply* is not an interrupted Snap",
			false,
			publisher(Hive(), store).isInterrupted(account, eventId, PendingSnapKind.ROOT),
		)
	}

	/** A locked row is never treated as a free slot for a new publication. */
	@Test
	fun `an unreadable kind never becomes a fresh event`() {
		val hive = Hive()
		val store = Store()
		store.saved["$account|$eventId"] =
			withKind(record(eventId, PendingSnapKind.ROOT, PendingSnapState.INTENT), "mystery")
		val before = store.saved.toMap()
		val pub = publisher(hive, store)

		val staged = pub.intendRoot(account, eventId, SnapMedia("8pSS6wdojqY"), "hello")
		val retried = pub.retryRoot(account, eventId)
		val published = pub.publishRoot(account, eventId, SnapMedia("8pSS6wdojqY"), "hello")

		assertTrue(staged is SnapPublisher.Staged.Uncertain)
		assertTrue(retried is SnapPublisher.Outcome.Uncertain)
		assertTrue(published is SnapPublisher.Outcome.Uncertain)
		assertEquals("nothing signed", 0, hive.prepares)
		assertEquals("nothing sent", 0, hive.broadcasts)
		assertEquals("and nothing written", before, store.saved)
	}

	// ── and the ordinary paths still work ──────────────────────────────

	@Test
	fun `a valid root Snap still publishes`() {
		val hive = Hive()
		val store = Store()

		val outcome = publisher(hive, store)
			.publishRoot(account, eventId, SnapMedia("8pSS6wdojqY"), "hello")

		assertTrue("got $outcome", outcome is SnapPublisher.Outcome.Published)
		assertEquals(1, hive.prepares)
		assertEquals(1, hive.broadcasts)
		val stored = PendingSnap.fromJson(store.saved.values.single())!!
		assertEquals(PendingSnapKind.ROOT, stored.kind)
		assertEquals(PendingSnapState.CONFIRMED, stored.state)
	}

	@Test
	fun `a valid reply still publishes`() {
		val hive = Hive()
		val store = Store()

		val outcome = publisher(hive, store).publishReply(account, target, intent, "nice one")

		assertTrue("got $outcome", outcome is SnapPublisher.Outcome.Published)
		assertEquals(1, hive.prepares)
		assertEquals(1, hive.broadcasts)
		val stored = PendingSnap.fromJson(store.saved.values.single())!!
		assertEquals(PendingSnapKind.REPLY, stored.kind)
		assertEquals(PendingSnapState.CONFIRMED, stored.state)
	}

	/** An intent of each kind resumes through its own path, as before. */
	@Test
	fun `each kind resumes from its own intent`() {
		val rootHive = Hive()
		val rootStore = Store()
		val rootPub = publisher(rootHive, rootStore)
		rootPub.intendRoot(account, eventId, SnapMedia("8pSS6wdojqY"), "hello")
		assertNotNull(rootPub.restoreStaged(account).singleOrNull())
		assertTrue(rootPub.retryRoot(account, eventId) is SnapPublisher.Outcome.Published)

		val replyHive = Hive()
		val replyStore = Store()
		val replyPub = publisher(replyHive, replyStore)
		replyPub.intendReply(account, target, intent, "nice one")
		assertNotNull(replyPub.restoreStagedReplies(account).singleOrNull())
		assertTrue(
			replyPub.publishReply(account, target, intent, "nice one")
				is SnapPublisher.Outcome.Published,
		)
	}

	/**
	 * On restart it is a *locked* row, not a missing one.
	 *
	 * `PendingSnapIntegrity` locks any entry that will not parse, and a record
	 * whose kind cannot be read is now one of those — so a reopened app reports
	 * it as unresolved rather than enumerating past it, and neither restore
	 * hands it to a screen that could offer to finish it.
	 */
	@Test
	fun `an unreadable kind surfaces as a locked row on restart`() {
		val hive = Hive()
		val store = Store()
		store.saved["$account|$eventId"] =
			withKind(record(eventId, PendingSnapKind.ROOT, PendingSnapState.INTENT), null)
		val pub = publisher(hive, store)

		val restored = pub.restore(account)

		assertEquals(listOf(eventId), restored.map { it.first })
		assertTrue("got ${restored.single().second}", restored.single().second is SnapPublisher.Outcome.Uncertain)
		assertEquals("nothing is offered as staged", emptyList<Any>(), pub.restoreStaged(account))
		assertEquals(emptyList<Any>(), pub.restoreStagedReplies(account))
		assertEquals(emptyList<Any>(), pub.restoreLocal(account))
		assertEquals(0, hive.prepares)
		assertEquals(0, hive.broadcasts)
	}
}
