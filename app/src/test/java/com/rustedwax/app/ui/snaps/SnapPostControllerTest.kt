package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.PendingSnapRead
import com.rustedwax.app.snaps.PendingSnapStore
import com.rustedwax.app.snaps.PostedSnapContent
import com.rustedwax.app.snaps.PostedSnapReader
import com.rustedwax.app.snaps.PostedSnaps
import com.rustedwax.app.snaps.PendingSnap
import com.rustedwax.app.snaps.PendingSnapKind
import com.rustedwax.app.snaps.PendingSnapState
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
		/**
		 * Mirrors the real store's three-way answer.
		 *
		 * An entry that exists and will not parse is **Corrupt**, never
		 * Absent. Collapsing the two is the precise mistake
		 * `SharedPreferencesPendingSnapStore` is built to avoid — it lets a row
		 * whose state could not be read look brand new, mint a second permlink
		 * and post a duplicate of a Snap that may already exist. A fake that
		 * collapsed them would quietly excuse the production code from ever
		 * handling it.
		 */
		override fun read(account: String, eventId: String): PendingSnapRead {
			val raw = saved["$account|$eventId"] ?: return PendingSnapRead.Absent
			return PendingSnap.fromJson(raw)?.let(PendingSnapRead::Present)
				?: PendingSnapRead.Corrupt("stored Snap state could not be read")
		}
		/** Flip to simulate a disk that will not hold the intent. */
		var writable = true
		override fun write(snap: PendingSnap): Boolean {
			if (!writable) return false
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
		/** How many transactions were signed — distinct from how many were sent. */
		var prepares = 0
		val permlinks = mutableListOf<String>()
		val bodies = mutableListOf<String>()
		/** Runs while the container lookup is in flight. */
		var duringContainer: (() -> Unit)? = null
		/** Runs while the chain head / signing is in flight. */
		var duringPrepare: (() -> Unit)? = null
		/** Runs while a broadcast is in flight, to observe what the screen shows. */
		var duringBroadcast: (() -> Unit)? = null
		var containerLookups = 0
		/** Runs while reconciliation is reading, for the same reason. */
		var duringContentCheck: (() -> Unit)? = null
		/** Overrides the resolved container, for failure cases. */
		var container: SnapContainerResolver.Result? = null
		override fun resolveContainer(): SnapContainerResolver.Result {
			containerLookups++
			duringContainer?.invoke()
			container?.let { return it }
			return SnapContainerResolver.Result.Resolved(
				SnapContainer("peak.snaps", "snap-container-1789648560", "2026-09-17T12:36:00"),
			)
		}
		/**
		 * The account the vault currently holds.
		 *
		 * Modelled because `KeyVault` stores the username and the WIF together
		 * and `HiveSnapPort` reads them together — so a switch moves this at
		 * the same moment it moves the UI's account. A fake that ignored it
		 * would let a test pass that production would refuse, and vice versa.
		 */
		var vaultAccount: String? = "alice"
		/** The captured author each call was bound to. */
		val preparedFor = mutableListOf<String>()
		val broadcastFor = mutableListOf<String>()
		/** The txids actually put on the wire. */
		val sentTxIds = mutableListOf<String>()
		/** Expiration stamped on each freshly signed transaction. */
		var expiration = 2_000_000_000L
		var txSeq = 0
		override fun prepareComment(
			operation: TxSerializer.CommentOp,
			author: String,
		): HivePreparationResult {
			duringPrepare?.invoke()
			// The same binding `HiveSnapPort` enforces: the operation's author,
			// the captured author and the vault's account must be one account.
			mismatch(operation.author, author)?.let { return it }
			prepares++
			permlinks += operation.permlink
			bodies += operation.body
			preparedFor += author
			txSeq++
			return HivePreparationResult.Ready(
				PreparedHiveTransaction("""{"op":$txSeq}""", "tx-$txSeq", expiration),
			)
		}
		override fun broadcastPrepared(
			prepared: PreparedHiveTransaction,
			author: String,
		): HiveRpc.BroadcastResult {
			duringBroadcast?.invoke()
			if (mismatch(author, author) != null) {
				return HiveRpc.BroadcastResult.Rejected("switched Hive accounts")
			}
			broadcasts++
			broadcastFor += author
			sentTxIds += prepared.txId
			return result
		}

		private fun mismatch(operationAuthor: String, captured: String): HivePreparationResult.Failed? {
			val stored = vaultAccount?.takeIf { it.isNotBlank() }
			return if (!operationAuthor.equals(captured, ignoreCase = true) ||
				stored == null || !stored.equals(captured, ignoreCase = true)
			) {
				HivePreparationResult.Failed(
					HiveRpc.BroadcastResult.Rejected("switched Hive accounts"),
				)
			} else {
				null
			}
		}
		override fun observeTransaction(txId: String, expirationEpochSec: Long) =
			HiveRpc.TransactionEvidence.UNAVAILABLE
		override fun contentExists(author: String, permlink: String): Boolean? {
			contentChecks++
			duringContentCheck?.invoke()
			return false
		}
	}

	/** A store whose durable write never sticks. */
	private class UnwritableStore : PendingSnapStore {
		var attempts = 0
		override fun read(account: String, eventId: String) = PendingSnapRead.Absent
		override fun write(snap: PendingSnap): Boolean { attempts++; return false }
		override fun clear(account: String, eventId: String) = Unit
		override fun all(account: String) = emptyList<PendingSnap>()
		override fun corruptEventIds(account: String) = emptySet<String>()
	}

	private class Reader : PostedSnapReader {
		var reads = 0
		override fun read(author: String, permlink: String): PostedSnapContent? {
			reads++
			return null
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

	/** As [controller], but with the posted-card source wired so cards render. */
	private fun drawingController(
		hive: Hive,
		store: PendingSnapStore,
		reader: PostedSnapReader = Reader(),
		account: () -> String?,
	): SnapPostController {
		val publisher = SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
		return SnapPostController(
			scope = CoroutineScope(Dispatchers.Unconfined),
			publisher = { publisher },
			account = account,
			io = Dispatchers.Unconfined,
			postedSnaps = { PostedSnaps(store, reader) },
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

	// ── optimistic posting ─────────────────────────────────────────────
	//
	// The rule these pin: the card appears at the **durable checkpoint** — the
	// signed transaction committed to disk and read back identical — and not
	// one step later. Everything after that point is background.

	/**
	 * The card is up **while the broadcast is still running**.
	 *
	 * Asserted from inside the broadcast itself, which is the only place that
	 * can prove the ordering: the network call has not returned, so whatever
	 * the screen shows it showed without waiting for Hive.
	 */
	@Test
	fun `the Snap is visible before the broadcast finishes`() {
		val hive = Hive(inBlock())
		val store = Store()
		lateinit var posts: SnapPostController
		val key = SnapDraftKey.of("alice", eventId)
		var statusDuring: SnapPostStatus? = null
		var cardDuring: String? = null
		hive.duringBroadcast = {
			statusDuring = posts.status(key)
			cardDuring = posts.posted(key)?.userText
		}
		posts = drawingController(hive, store) { "alice" }

		posts.post(key, eventId, media, "hello") {}

		assertTrue("must be optimistic, not still Posting", statusDuring is SnapPostStatus.Optimistic)
		assertEquals("and the words are already drawn", "hello", cardDuring)
	}

	/** Which means the durable record existed before the send, too. */
	@Test
	fun `the record is durably persisted before anything is broadcast`() {
		val hive = Hive(inBlock())
		val store = Store()
		var savedDuring = 0
		hive.duringBroadcast = { savedDuring = store.saved.size }
		val posts = drawingController(hive, store) { "alice" }

		posts.post(SnapDraftKey.of("alice", eventId), eventId, media, "hello") {}

		assertEquals("the checkpoint precedes the wire", 1, savedDuring)
	}

	/** A slow confirmation/reconciliation does not hold the card back either. */
	@Test
	fun `a slow reconciliation does not delay the visible Snap`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val store = Store()
		lateinit var posts: SnapPostController
		val key = SnapDraftKey.of("alice", eventId)
		var visibleDuringReconcile = false
		hive.duringContentCheck = { visibleDuringReconcile = posts.posted(key) != null }
		posts = drawingController(hive, store) { "alice" }

		posts.post(key, eventId, media, "hello") {}

		assertTrue("the card is up while reconciliation reads", visibleDuringReconcile)
	}

	/**
	 * A persist that does not stick shows **nothing**.
	 *
	 * The inverse of the whole feature: without a durable record there is no
	 * permanent identity, so there is nothing honest to draw and the composer
	 * must keep the draft.
	 */
	@Test
	fun `a failed persist never renders an optimistic Snap`() {
		val hive = Hive(inBlock())
		val store = UnwritableStore()
		val posts = drawingController(hive, store) { "alice" }
		val key = SnapDraftKey.of("alice", eventId)
		var published: String? = null

		posts.post(key, eventId, media, "hello") { published = it }

		assertEquals("nothing may be sent", 0, hive.broadcasts)
		assertNull("and nothing drawn", posts.posted(key))
		assertNull(published)
		assertTrue(posts.status(key) is SnapPostStatus.Failed)
		assertFalse(posts.status(key) is SnapPostStatus.Optimistic)
	}

	/** One tap, one signature, one permlink, one send. */
	@Test
	fun `one tap prepares and broadcasts exactly once`() {
		val hive = Hive(inBlock())
		val posts = drawingController(hive, Store()) { "alice" }

		posts.post(SnapDraftKey.of("alice", eventId), eventId, media, "hello") {}

		assertEquals(1, hive.prepares)
		assertEquals(1, hive.broadcasts)
		assertEquals(listOf("rustedwax-snap-1000-aaaaaa"), hive.permlinks)
	}

	/** A second tap on an already-posted card mints nothing and sends nothing. */
	@Test
	fun `a repeated tap cannot prepare or broadcast twice`() {
		val hive = Hive(inBlock())
		val store = Store()
		val posts = drawingController(hive, store) { "alice" }
		val key = SnapDraftKey.of("alice", eventId)

		posts.post(key, eventId, media, "hello") {}
		posts.post(key, eventId, media, "hello") {}
		posts.post(key, eventId, media, "hello") {}

		assertEquals("only one signature", 1, hive.prepares)
		assertEquals("only one broadcast", 1, hive.broadcasts)
		assertEquals(1, hive.permlinks.distinct().size)
	}

	/** Confirmation settles the same card rather than replacing it. */
	@Test
	fun `a confirmed Snap settles without changing what is drawn`() {
		val hive = Hive(inBlock())
		val store = Store()
		lateinit var posts: SnapPostController
		val key = SnapDraftKey.of("alice", eventId)
		var textDuring: String? = null
		hive.duringBroadcast = { textDuring = posts.posted(key)?.userText }
		posts = drawingController(hive, store) { "alice" }

		posts.post(key, eventId, media, "hello") {}

		assertTrue(posts.status(key) is SnapPostStatus.Posted)
		assertEquals("the same words, before and after", textDuring, posts.posted(key)?.userText)
	}

	/** An ambiguous Snap stays on screen and is never resent. */
	@Test
	fun `an ambiguous Snap stays visible and is not resent`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val store = Store()
		val posts = drawingController(hive, store) { "alice" }
		val key = SnapDraftKey.of("alice", eventId)

		posts.post(key, eventId, media, "hello") {}

		assertTrue(posts.status(key) is SnapPostStatus.Uncertain)
		assertNotNull("the Snap does not vanish", posts.posted(key))
		assertEquals("exactly one broadcast attempt", 1, hive.broadcasts)

		// Everything a screen can do to it is a read.
		posts.recheck(key, eventId)
		assertEquals("a re-check never sends", 1, hive.broadcasts)
		assertEquals("and mints nothing", 1, hive.prepares)
	}

	// ── restart / recreation ───────────────────────────────────────────

	/**
	 * A rebuilt Activity draws the staged Snap again from disk, and mints
	 * nothing doing it.
	 *
	 * The second controller is a fresh object over the same store, which is how
	 * Activity recreation is expressed here.
	 */
	@Test
	fun `recreation restores a staged Snap without re-minting or resending`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val store = Store()
		val key = SnapDraftKey.of("alice", eventId)
		drawingController(hive, store) { "alice" }
			.post(key, eventId, media, "hello") {}
		val preparesAfterFirst = hive.prepares
		val broadcastsAfterFirst = hive.broadcasts

		val rebuilt = drawingController(hive, store) { "alice" }
		rebuilt.resumePending()

		assertTrue("the card is back", rebuilt.status(key) is SnapPostStatus.Optimistic ||
			rebuilt.status(key) is SnapPostStatus.Uncertain)
		assertNotNull("with its words", rebuilt.posted(key))
		assertEquals("no second permlink", preparesAfterFirst, hive.prepares)
		assertEquals("no blind resend", broadcastsAfterFirst, hive.broadcasts)
		assertEquals(1, hive.permlinks.distinct().size)
	}

	/** And the recovery path reconciles by reading, never by sending. */
	@Test
	fun `restart never blind-resends an ambiguous Snap`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val store = Store()
		drawingController(hive, store) { "alice" }
			.post(SnapDraftKey.of("alice", eventId), eventId, media, "hello") {}
		val sent = hive.broadcasts

		repeat(3) { drawingController(hive, store) { "alice" }.resumePending() }

		assertEquals("no restart may resend", sent, hive.broadcasts)
	}

	// ── account isolation ──────────────────────────────────────────────

	@Test
	fun `another account cannot see or mutate an optimistic Snap`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val store = Store()
		var who: String? = "alice"
		val posts = drawingController(hive, store) { who }
		val aliceKey = SnapDraftKey.of("alice", eventId)
		posts.post(aliceKey, eventId, media, "alice's words") {}
		val sent = hive.broadcasts

		who = "bob"
		posts.resumePending()

		assertNull("Bob sees nothing of Alice's", posts.posted(SnapDraftKey.of("bob", eventId)))
		assertEquals("and nothing of Alice's is resent", sent, hive.broadcasts)
		assertEquals(1, hive.permlinks.distinct().size)
	}

	// ── the Stage 6 seam ───────────────────────────────────────────────

	/**
	 * Unsettled Snaps are queryable, and the query is only a query: no store,
	 * no channel, no retry, no notification machinery.
	 */
	@Test
	fun `unsettled Snaps are queryable for a later notification surface`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val store = Store()
		val posts = drawingController(hive, store) { "alice" }
		val key = SnapDraftKey.of("alice", eventId)
		posts.post(key, eventId, media, "hello") {}
		val sent = hive.broadcasts

		val attention = posts.needsAttention()

		assertEquals(1, attention.size)
		assertEquals(key, attention.single().key)
		assertTrue(attention.single().status is SnapPostStatus.Uncertain)
		assertEquals(SnapAttention.Kind.ROOT, attention.single().kind)
		assertEquals("reading it sends nothing", sent, hive.broadcasts)
	}

	@Test
	fun `a settled Snap needs no attention, and another account's is not surfaced`() {
		val hive = Hive(inBlock())
		val store = Store()
		var who: String? = "alice"
		val posts = drawingController(hive, store) { who }
		posts.post(SnapDraftKey.of("alice", eventId), eventId, media, "hello") {}
		assertEquals(emptyList<Any>(), posts.needsAttention())

		who = "bob"
		assertEquals(emptyList<Any>(), posts.needsAttention())
	}

	// ── the network is off the visible path ────────────────────────────
	//
	// The card is gated on one local write and nothing else. These assert that
	// from inside each slow stage in turn: the stage has not returned yet, so
	// whatever the screen already shows, it showed without waiting for it.

	@Test
	fun `the card is visible before the container lookup returns`() {
		val hive = Hive(inBlock())
		val store = Store()
		lateinit var posts: SnapPostController
		val key = SnapDraftKey.of("alice", eventId)
		var statusDuring: SnapPostStatus? = null
		var cardDuring: String? = null
		hive.duringContainer = {
			statusDuring = posts.status(key)
			cardDuring = posts.posted(key)?.userText
		}
		posts = drawingController(hive, store) { "alice" }

		posts.post(key, eventId, media, "hello") {}

		assertTrue("optimistic before the container RPC", statusDuring is SnapPostStatus.Optimistic)
		assertEquals("hello", cardDuring)
	}

	@Test
	fun `the card is visible before the chain head and signing return`() {
		val hive = Hive(inBlock())
		lateinit var posts: SnapPostController
		val key = SnapDraftKey.of("alice", eventId)
		var statusDuring: SnapPostStatus? = null
		hive.duringPrepare = { statusDuring = posts.status(key) }
		posts = drawingController(hive, Store()) { "alice" }

		posts.post(key, eventId, media, "hello") {}

		assertTrue("optimistic before signing", statusDuring is SnapPostStatus.Optimistic)
	}

	/** The intent is on disk before a single RPC is made. */
	@Test
	fun `the intent is persisted before any network call begins`() {
		val hive = Hive(inBlock())
		val store = Store()
		var savedAtContainer = 0
		var lookupsAtFirstSave = -1
		hive.duringContainer = { savedAtContainer = store.saved.size }
		val posts = drawingController(hive, store) { "alice" }

		posts.post(SnapDraftKey.of("alice", eventId), eventId, media, "hello") {}

		assertEquals("a record existed before the container was asked", 1, savedAtContainer)
		assertEquals("and the lookup happened once", 1, hive.containerLookups)
		assertEquals(-1, lookupsAtFirstSave)
	}

	/** No "Posting…" is ever shown for the posting path; only re-check uses it. */
	@Test
	fun `the post path never enters the Posting label state`() {
		val hive = Hive(inBlock())
		lateinit var posts: SnapPostController
		val key = SnapDraftKey.of("alice", eventId)
		val seen = mutableListOf<SnapPostStatus>()
		hive.duringContainer = { seen += posts.status(key) }
		hive.duringPrepare = { seen += posts.status(key) }
		hive.duringBroadcast = { seen += posts.status(key) }
		posts = drawingController(hive, Store()) { "alice" }

		posts.post(key, eventId, media, "hello") {}

		assertTrue(
			"Posting is the re-check label and must not appear here: $seen",
			seen.none { it is SnapPostStatus.Posting },
		)
		assertTrue(seen.isNotEmpty())
	}

	/** A local write that does not stick blocks the card — the only thing that may. */
	@Test
	fun `an intent that cannot be persisted shows no card and touches no network`() {
		val hive = Hive(inBlock())
		val posts = drawingController(hive, UnwritableStore()) { "alice" }
		val key = SnapDraftKey.of("alice", eventId)

		posts.post(key, eventId, media, "hello") {}

		assertNull("nothing drawn", posts.posted(key))
		assertFalse(posts.status(key) is SnapPostStatus.Optimistic)
		assertTrue(posts.status(key) is SnapPostStatus.Failed)
		assertEquals("and nothing was asked of the chain", 0, hive.containerLookups)
		assertEquals(0, hive.prepares)
		assertEquals(0, hive.broadcasts)
	}

	// ── intent → prepared → broadcast, in that order ───────────────────

	@Test
	fun `the prepared transaction is durable before the broadcast`() {
		val hive = Hive(inBlock())
		val store = Store()
		var stateAtBroadcast: String? = null
		hive.duringBroadcast = {
			stateAtBroadcast = PendingSnap.fromJson(store.saved.values.single())?.state?.name
		}
		val posts = drawingController(hive, store) { "alice" }

		posts.post(SnapDraftKey.of("alice", eventId), eventId, media, "hello") {}

		// `broadcast` advances the record to BROADCASTING and proves that write
		// before it sends, so the record on disk at send time is never an intent.
		assertEquals("BROADCASTING", stateAtBroadcast)
	}

	@Test
	fun `an intent alone can never be broadcast`() {
		val hive = Hive(inBlock())
		val store = Store()
		val publisher = SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })

		val intent = publisher.intendRoot("alice", eventId, media, "hello")
		assertTrue(intent is SnapPublisher.Staged.Ready)
		assertEquals("intending touches no network", 0, hive.containerLookups)
		assertEquals(0, hive.prepares)

		// Delivery refuses it outright rather than merely failing to find bytes.
		val outcome = publisher.deliver("alice", eventId, PendingSnapKind.ROOT)
		assertTrue(outcome is SnapPublisher.Outcome.Failed)
		assertEquals("nothing may be sent from an intent", 0, hive.broadcasts)
	}

	// ── one identity, whatever happens ─────────────────────────────────

	@Test
	fun `one event produces exactly one permlink however often it is attempted`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val store = Store()
		val key = SnapDraftKey.of("alice", eventId)
		val posts = drawingController(hive, store) { "alice" }

		posts.post(key, eventId, media, "hello") {}
		posts.post(key, eventId, media, "hello") {}
		drawingController(hive, store) { "alice" }.resumePending()

		assertEquals("one permlink, always", 1, hive.permlinks.distinct().size)
		assertEquals("rustedwax-snap-1000-aaaaaa", hive.permlinks.first())
	}

	/**
	 * Resuming an intent signs the **frozen** body, not a recomputed one.
	 *
	 * The card has been showing those words since the tap, so they are the
	 * words that must go on chain even if the draft changed underneath.
	 */
	@Test
	fun `a restart from an intent reuses the same permlink and the same body`() {
		val hive = Hive(inBlock())
		val store = Store()
		val publisher = SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
		publisher.intendRoot("alice", eventId, media, "the words I tapped")

		// A fresh controller over the same store — the app, restarted — and a
		// later attempt that passes different text.
		drawingController(hive, store) { "alice" }
			.post(SnapDraftKey.of("alice", eventId), eventId, media, "something else entirely") {}

		assertEquals(1, hive.permlinks.distinct().size)
		assertEquals("rustedwax-snap-1000-aaaaaa", hive.permlinks.single())
		assertTrue(
			"the frozen body is what gets signed: ${hive.bodies.single()}",
			hive.bodies.single().startsWith("the words I tapped"),
		)
	}

	/**
	 * A restart from an intent restores the card as **interrupted**, not as a
	 * Snap that is on its way.
	 *
	 * This is the ghost-post fix. An intent was never built, so it reached no
	 * node and is certainly not on Hive — drawing it like a delivered Snap
	 * would leave a card claiming publication, a Thread button pointing at a
	 * comment that does not exist, and no way to finish.
	 */
	@Test
	fun `a restart from an intent restores it as interrupted, not posted`() {
		val hive = Hive(inBlock())
		val store = Store()
		SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
			.intendRoot("alice", eventId, media, "hello")
		val key = SnapDraftKey.of("alice", eventId)

		val rebuilt = drawingController(hive, store) { "alice" }
		rebuilt.resumePending()

		assertTrue("needs the user, not a spinner", rebuilt.status(key) is SnapPostStatus.Interrupted)
		assertFalse(rebuilt.status(key) is SnapPostStatus.Optimistic)
		assertFalse(rebuilt.status(key) is SnapPostStatus.Posted)
		assertNotNull("but the words are still shown", rebuilt.posted(key))
		assertEquals("one stored row", 1, store.saved.size)
	}

	/** And the restart itself is completely silent on the network. */
	@Test
	fun `an intent-only restart performs no RPC, no signing and no broadcast`() {
		val hive = Hive(inBlock())
		val store = Store()
		SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
			.intendRoot("alice", eventId, media, "hello")

		repeat(3) { drawingController(hive, store) { "alice" }.resumePending() }

		assertEquals("no container lookup", 0, hive.containerLookups)
		assertEquals("no signing", 0, hive.prepares)
		assertEquals("no broadcast", 0, hive.broadcasts)
		assertEquals("and nothing reconciled against the chain", 0, hive.contentChecks)
	}

	// ── explicit retry ─────────────────────────────────────────────────

	@Test
	fun `retry reuses the same permlink, body and row`() {
		val hive = Hive(inBlock())
		val store = Store()
		SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
			.intendRoot("alice", eventId, media, "the words I tapped")
		val key = SnapDraftKey.of("alice", eventId)
		val posts = drawingController(hive, store) { "alice" }
		posts.resumePending()

		posts.retry(key, eventId)

		assertEquals("one permlink", 1, hive.permlinks.distinct().size)
		assertEquals("rustedwax-snap-1000-aaaaaa", hive.permlinks.single())
		assertTrue(
			"the frozen body is what gets signed: ${hive.bodies.single()}",
			hive.bodies.single().startsWith("the words I tapped"),
		)
		assertEquals("no second row", 1, store.saved.size)
		assertTrue("and it settles normally", posts.status(key) is SnapPostStatus.Posted)
	}

	@Test
	fun `retry still persists the prepared transaction before broadcasting`() {
		val hive = Hive(inBlock())
		val store = Store()
		SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
			.intendRoot("alice", eventId, media, "hello")
		var stateAtBroadcast: String? = null
		hive.duringBroadcast = {
			stateAtBroadcast = PendingSnap.fromJson(store.saved.values.single())?.state?.name
		}
		val posts = drawingController(hive, store) { "alice" }
		posts.resumePending()

		posts.retry(SnapDraftKey.of("alice", eventId), eventId)

		assertEquals("BROADCASTING", stateAtBroadcast)
	}

	/** A second Retry tap cannot start a parallel attempt. */
	@Test
	fun `a double retry tap runs only one publication`() {
		val hive = Hive(inBlock())
		val store = Store()
		SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
			.intendRoot("alice", eventId, media, "hello")
		val key = SnapDraftKey.of("alice", eventId)
		lateinit var posts: SnapPostController
		// A re-entrant tap from inside the in-flight attempt.
		hive.duringContainer = { posts.retry(key, eventId) }
		posts = drawingController(hive, store) { "alice" }
		posts.resumePending()

		posts.retry(key, eventId)
		posts.retry(key, eventId)

		assertEquals("one container lookup", 1, hive.containerLookups)
		assertEquals("one signature", 1, hive.prepares)
		assertEquals("one broadcast", 1, hive.broadcasts)
	}

	/** A retry that cannot reach the container stays retryable. */
	@Test
	fun `a failed retry remains interrupted and keeps the intent`() {
		val hive = Hive(inBlock())
		val store = Store()
		SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
			.intendRoot("alice", eventId, media, "hello")
		val key = SnapDraftKey.of("alice", eventId)
		hive.container = SnapContainerResolver.Result.Unavailable("no node answered")
		val posts = drawingController(hive, store) { "alice" }
		posts.resumePending()

		posts.retry(key, eventId)

		assertTrue("still attention-worthy", posts.status(key) is SnapPostStatus.Interrupted)
		assertNotNull("still shown", posts.posted(key))
		assertEquals("nothing sent", 0, hive.broadcasts)
		assertEquals("intent intact", 1, store.saved.size)

		// And it really is retryable: the node comes back.
		hive.container = null
		posts.retry(key, eventId)
		assertTrue(posts.status(key) is SnapPostStatus.Posted)
		assertEquals(1, hive.permlinks.distinct().size)
	}

	@Test
	fun `an ambiguous retry never auto-resends`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val store = Store()
		SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
			.intendRoot("alice", eventId, media, "hello")
		val key = SnapDraftKey.of("alice", eventId)
		val posts = drawingController(hive, store) { "alice" }
		posts.resumePending()

		posts.retry(key, eventId)
		assertTrue(posts.status(key) is SnapPostStatus.Uncertain)
		val sent = hive.broadcasts

		// Restarts, re-resumes and a further retry are all reads from here.
		repeat(2) { drawingController(hive, store) { "alice" }.resumePending() }
		posts.retry(key, eventId)

		assertEquals("an uncertain Snap is never resent", sent, hive.broadcasts)
	}

	// ── account safety ─────────────────────────────────────────────────

	@Test
	fun `another account can neither see nor retry an interrupted intent`() {
		val hive = Hive(inBlock())
		val store = Store()
		SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
			.intendRoot("alice", eventId, media, "alice's words")
		var who: String? = "alice"
		val posts = drawingController(hive, store) { who }
		posts.resumePending()
		assertTrue(posts.status(SnapDraftKey.of("alice", eventId)) is SnapPostStatus.Interrupted)

		who = "bob"
		posts.resumePending()
		assertNull("Bob sees nothing of it", posts.posted(SnapDraftKey.of("bob", eventId)))
		assertEquals(emptyList<Any>(), posts.needsAttention())

		// And Bob cannot finish Alice's Snap, under either key.
		posts.retry(SnapDraftKey.of("alice", eventId), eventId)
		posts.retry(SnapDraftKey.of("bob", eventId), eventId)
		assertEquals("nothing signed for the wrong account", 0, hive.prepares)
		assertEquals(0, hive.broadcasts)
	}

	// ── the Stage 6 seam ───────────────────────────────────────────────

	@Test
	fun `needsAttention includes an interrupted intent and stays read-only`() {
		val hive = Hive(inBlock())
		val store = Store()
		SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
			.intendRoot("alice", eventId, media, "hello")
		val key = SnapDraftKey.of("alice", eventId)
		val posts = drawingController(hive, store) { "alice" }
		posts.resumePending()

		val attention = posts.needsAttention()

		assertEquals(1, attention.size)
		assertEquals(key, attention.single().key)
		assertTrue(attention.single().status is SnapPostStatus.Interrupted)
		// The kind travels with the row: an unfinished Snap is not an
		// unfinished reply, and whoever surfaces these has to say which.
		assertEquals(SnapAttention.Kind.ROOT, attention.single().kind)
		assertEquals("reading it touches nothing", 0, hive.containerLookups)
		assertEquals(0, hive.prepares)
		assertEquals(0, hive.broadcasts)
	}

	// ── blocker 1: the account is bound at the signing boundary ────────
	//
	// A switch at any point before transmission must yield zero broadcast.
	// Counted at the port, because "no card appeared" and "nothing was
	// published" are different claims and only the second one matters.

	@Test
	fun `a switch during the container lookup broadcasts nothing`() {
		val hive = Hive(inBlock())
		var who: String? = "alice"
		// A real switch rewrites the vault and the UI account together.
		hive.duringContainer = { who = "bob"; hive.vaultAccount = "bob" }
		val posts = drawingController(hive, Store()) { who }

		posts.post(SnapDraftKey.of("alice", eventId), eventId, media, "hello") {}

		assertEquals("nothing may reach the wire", 0, hive.broadcasts)
	}

	@Test
	fun `a switch during signing broadcasts nothing`() {
		val hive = Hive(inBlock())
		var who: String? = "alice"
		hive.duringPrepare = { who = "bob"; hive.vaultAccount = "bob" }
		val posts = drawingController(hive, Store()) { who }

		posts.post(SnapDraftKey.of("alice", eventId), eventId, media, "hello") {}

		assertEquals(0, hive.broadcasts)
	}

	/** The narrowest window: signed for Alice, switched, then asked to send. */
	@Test
	fun `a switch after signing but before broadcast broadcasts nothing`() {
		val hive = Hive(inBlock())
		var who: String? = "alice"
		hive.duringBroadcast = { who = "bob"; hive.vaultAccount = "bob" }
		val posts = drawingController(hive, Store()) { who }

		posts.post(SnapDraftKey.of("alice", eventId), eventId, media, "hello") {}

		assertEquals("signed, but never sent", 0, hive.broadcasts)
		assertEquals(1, hive.prepares)
	}

	/** The captured author travels with the operation, both halves. */
	@Test
	fun `signing and broadcasting are both bound to the captured author`() {
		val hive = Hive(inBlock())
		val posts = drawingController(hive, Store()) { "alice" }

		posts.post(SnapDraftKey.of("alice", eventId), eventId, media, "hello") {}

		assertEquals(listOf("alice"), hive.preparedFor)
		assertEquals(listOf("alice"), hive.broadcastFor)
	}

	/**
	 * The case a key check alone cannot catch.
	 *
	 * One posting key may authorize several accounts, so a comment naming
	 * Alice and signed after a switch to Bob is not necessarily rejected by
	 * the chain. `HiveSnapPort` therefore compares the vault's stored username
	 * with the captured author — this asserts the port refuses that pairing
	 * rather than trusting the key.
	 */
	@Test
	fun `a shared key cannot bypass the account binding at the signing boundary`() {
		val broadcaster = com.rustedwax.hive.HiveBroadcaster()
		// The vault now holds Bob, while the operation still names Alice.
		val port = com.rustedwax.app.snaps.HiveSnapPort(
			loadKey = { error("the key must never be reached") },
			storedAccount = { "bob" },
			broadcaster = broadcaster,
		)
		val op = TxSerializer.CommentOp(
			parentAuthor = "peak.snaps",
			parentPermlink = "snap-container-1",
			author = "alice",
			permlink = "rustedwax-snap-1000-aaaaaa",
			title = "",
			body = "hello",
			jsonMetadata = "{}",
		)

		val prepared = port.prepareComment(op, author = "alice")

		assertTrue(prepared is HivePreparationResult.Failed)
		val why = ((prepared as HivePreparationResult.Failed).result
			as HiveRpc.BroadcastResult.Rejected).message
		assertTrue("got: $why", why.contains("switched Hive accounts"))
	}

	@Test
	fun `an operation naming a different author than the caller captured is refused`() {
		val port = com.rustedwax.app.snaps.HiveSnapPort(
			loadKey = { error("the key must never be reached") },
			storedAccount = { "alice" },
		)
		val op = TxSerializer.CommentOp(
			parentAuthor = "peak.snaps",
			parentPermlink = "snap-container-1",
			author = "carol",
			permlink = "rustedwax-snap-1000-aaaaaa",
			title = "",
			body = "hello",
			jsonMetadata = "{}",
		)

		assertTrue(port.prepareComment(op, author = "alice") is HivePreparationResult.Failed)
	}

	@Test
	fun `an unchanged account still publishes normally`() {
		val hive = Hive(inBlock())
		val key = SnapDraftKey.of("alice", eventId)
		val posts = drawingController(hive, Store()) { "alice" }

		posts.post(key, eventId, media, "hello") {}

		assertEquals(1, hive.broadcasts)
		assertTrue(posts.status(key) is SnapPostStatus.Posted)
	}

	// ── blocker 2: one live attempt, enforced by the controller ────────

	/**
	 * The gap this closes: the card flips to optimistic long before the
	 * broadcast, so a status-based lock stopped guarding exactly when the slow
	 * work was still running.
	 */
	@Test
	fun `a re-entrant post while optimistic starts no second attempt`() {
		val hive = Hive(inBlock())
		val key = SnapDraftKey.of("alice", eventId)
		lateinit var posts: SnapPostController
		// Fired from inside each slow stage, when the card is already optimistic.
		hive.duringContainer = { posts.post(key, eventId, media, "hello") {} }
        hive.duringPrepare = { posts.post(key, eventId, media, "hello") {} }
		hive.duringBroadcast = { posts.post(key, eventId, media, "hello") {} }
		posts = drawingController(hive, Store()) { "alice" }

		posts.post(key, eventId, media, "hello") {}

		assertEquals("one container lookup", 1, hive.containerLookups)
		assertEquals("one signature", 1, hive.prepares)
		assertEquals("one broadcast", 1, hive.broadcasts)
		assertEquals("one permlink", 1, hive.permlinks.distinct().size)
	}

	/** And the lock is released, so a finished attempt never strands the card. */
	@Test
	fun `a completed attempt leaves the card unlocked`() {
		val hive = Hive(inBlock())
		val store = Store()
		val key = SnapDraftKey.of("alice", eventId)
		val posts = drawingController(hive, store) { "alice" }

		posts.post(key, eventId, media, "hello") {}
		assertFalse("not locked after completion", posts.isBusy(key))

		// A restored interrupted card is likewise free to accept one retry.
		val rebuilt = drawingController(hive, store) { "alice" }
		rebuilt.resumePending()
		assertFalse(rebuilt.isBusy(key))
	}

	@Test
	fun `a restored interrupted card still accepts one explicit retry`() {
		val hive = Hive(inBlock())
		val store = Store()
		SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
			.intendRoot("alice", eventId, media, "hello")
		val key = SnapDraftKey.of("alice", eventId)
		val posts = drawingController(hive, store) { "alice" }
		posts.resumePending()

		assertFalse("an optimistic-looking card is not locked", posts.isBusy(key))
		posts.retry(key, eventId)

		assertEquals(1, hive.broadcasts)
		assertTrue(posts.status(key) is SnapPostStatus.Posted)
	}

	// ── blocker 3: an expired PREPARED is rebuilt, never resent ────────

	/** Still in date: the stored bytes are the right bytes. */
	@Test
	fun `an unexpired prepared retry reuses the stored transaction`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val store = Store()
		val publisher = SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
		publisher.intendRoot("alice", eventId, media, "hello")
		// Stage it, leaving a PREPARED record that has not expired.
		hive.expiration = 9_000L
		publisher.stageRoot("alice", eventId, media, "hello")
		val preparesAfterStage = hive.prepares

		publisher.retryRoot("alice", eventId)

		assertEquals("no re-signing", preparesAfterStage, hive.prepares)
		assertEquals("the stored bytes went out", listOf("tx-1"), hive.sentTxIds)
	}

	/**
	 * Expired: the old bytes are refused entry to the wire and a fresh
	 * transaction is built around the *same* identity.
	 */
	@Test
	fun `an expired prepared retry sends no old bytes and rebuilds the same Snap`() {
		val hive = Hive(inBlock())
		val store = Store()
		val publisher = SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
		publisher.intendRoot("alice", eventId, media, "the words I tapped")
		// Signed against a chain head that has since expired.
		hive.expiration = 500L
		publisher.stageRoot("alice", eventId, media, "the words I tapped")
		assertEquals(listOf("tx-1"), hive.permlinks.indices.map { "tx-${it + 1}" })

		// A *different* draft is offered; it must not influence anything.
		hive.expiration = 9_000L
		val outcome = publisher.retryRoot("alice", eventId)

        assertTrue(outcome is SnapPublisher.Outcome.Published)
		assertEquals("the expired transaction never went out", listOf("tx-2"), hive.sentTxIds)
		assertEquals("same permlink throughout", 1, hive.permlinks.distinct().size)
		assertEquals("rustedwax-snap-1000-aaaaaa", hive.permlinks.distinct().single())
		assertEquals("same frozen body throughout", 1, hive.bodies.distinct().size)
		assertTrue(hive.bodies.distinct().single().startsWith("the words I tapped"))
		assertEquals("one row", 1, store.saved.size)
		assertEquals("exactly one fresh broadcast", 1, hive.broadcasts)
	}

	/** The rebuilt transaction is persisted before it is sent. */
	@Test
	fun `an expired prepared retry persists the new transaction before broadcasting`() {
		val hive = Hive(inBlock())
		val store = Store()
		val publisher = SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
		publisher.intendRoot("alice", eventId, media, "hello")
		hive.expiration = 500L
		publisher.stageRoot("alice", eventId, media, "hello")

		var storedAtBroadcast: String? = null
		hive.duringBroadcast = {
			storedAtBroadcast = PendingSnap.fromJson(store.saved.values.single())?.txId
		}
		hive.expiration = 9_000L
		publisher.retryRoot("alice", eventId)

		assertEquals("the new bytes were committed first", "tx-2", storedAtBroadcast)
	}

	/** An expired record must not be written off as failed. */
	@Test
	fun `an expired prepared retry that cannot reach the container stays recoverable`() {
		val hive = Hive(inBlock())
		val store = Store()
		val publisher = SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
		publisher.intendRoot("alice", eventId, media, "hello")
		hive.expiration = 500L
		publisher.stageRoot("alice", eventId, media, "hello")

		hive.container = SnapContainerResolver.Result.Unavailable("no node answered")
		val outcome = publisher.retryRoot("alice", eventId)

		assertTrue(outcome is SnapPublisher.Outcome.Failed)
		assertEquals("nothing sent", 0, hive.broadcasts)
		val record = PendingSnap.fromJson(store.saved.values.single())!!
		assertEquals("the identity survives", "rustedwax-snap-1000-aaaaaa", record.permlink)
		assertTrue("and it is not written off", record.state != PendingSnapState.FAILED)
	}

	/** An uncertain record is reconciled, never rebuilt — expired or not. */
	@Test
	fun `an uncertain record is never rebuilt or resubmitted by retry`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("gone"))
		val store = Store()
		val publisher = SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
		publisher.intendRoot("alice", eventId, media, "hello")
		hive.expiration = 500L
		publisher.publishRoot("alice", eventId, media, "hello")
		val prepares = hive.prepares
        val sent = hive.broadcasts

		repeat(3) { publisher.retryRoot("alice", eventId) }

		assertEquals("no rebuild of an ambiguous Snap", prepares, hive.prepares)
		assertEquals("and no resend", sent, hive.broadcasts)
	}

	@Test
	fun `an expired prepared retry after an account switch signs and sends nothing`() {
		val hive = Hive(inBlock())
		val store = Store()
		val publisher = SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-aaaaaa" })
		publisher.intendRoot("alice", eventId, media, "hello")
		hive.expiration = 500L
		publisher.stageRoot("alice", eventId, media, "hello")
		val prepares = hive.prepares
		val sent = hive.broadcasts

		var who: String? = "bob"
		val posts = drawingController(hive, store) { who }
		posts.retry(SnapDraftKey.of("alice", eventId), eventId)

		assertEquals("nothing signed for the wrong account", prepares, hive.prepares)
		assertEquals(sent, hive.broadcasts)
	}

	// ── §3: a malformed PREPARED can never authorize anything ──────────

	/**
	 * A corrupt expiration reaches `retryRoot` as a locked row, not as a
	 * deliverable or rebuildable one.
	 *
	 * This is the end of the chain the parser fix protects: the record *looks*
	 * like a signed transaction, so without strict parsing it would either be
	 * broadcast (if its expiry read as fresh) or rebuilt (if it read as
	 * expired). Locked, it does neither.
	 */
	@Test
	fun `a prepared record with an unusable expiration authorizes nothing`() {
		val hive = Hive(inBlock())
		val store = Store()
		// A record that will not parse: PREPARED with a fractional expiry.
		store.saved["alice|$eventId"] = """
			{"account":"alice","eventId":"$eventId","author":"alice",
			 "permlink":"rustedwax-snap-1000-aaaaaa",
			 "parentAuthor":"peak.snaps","parentPermlink":"snap-container-1",
			 "body":"hello","jsonMetadata":"{}",
			 "signedTransactionJson":"{}","txId":"tx-old",
			 "expirationEpochSec":1.9,
			 "state":"PREPARED","createdAtEpochSec":1000,"updatedAtEpochSec":1000,
			 "lastError":null,"kind":"root_snap"}
		""".trimIndent()
		val publisher = SnapPublisher(hive, store, { 1_000L }, { "rustedwax-snap-1000-bbbbbb" })

		val retried = publisher.retryRoot("alice", eventId)
		val delivered = publisher.deliver("alice", eventId, PendingSnapKind.ROOT)

		assertTrue("locked, not actionable", retried is SnapPublisher.Outcome.Uncertain)
		assertTrue(delivered is SnapPublisher.Outcome.Uncertain)
		assertEquals("the old bytes never went out", 0, hive.broadcasts)
		assertEquals("and nothing was rebuilt", 0, hive.prepares)
		assertEquals("no fresh permlink was minted", 0, hive.permlinks.size)
	}

	/** Nor can the controller coax one into publishing. */
	@Test
	fun `the controller cannot post or retry a record with an unusable expiration`() {
		val hive = Hive(inBlock())
		val store = Store()
		store.saved["alice|$eventId"] = """
			{"account":"alice","eventId":"$eventId","author":"alice",
			 "permlink":"rustedwax-snap-1000-aaaaaa",
			 "parentAuthor":"peak.snaps","parentPermlink":"snap-container-1",
			 "body":"hello","jsonMetadata":"{}",
			 "signedTransactionJson":"{}","txId":"tx-old",
			 "expirationEpochSec":0,
			 "state":"PREPARED","createdAtEpochSec":1000,"updatedAtEpochSec":1000,
			 "lastError":null,"kind":"root_snap"}
		""".trimIndent()
		val key = SnapDraftKey.of("alice", eventId)
		val posts = drawingController(hive, store) { "alice" }

		posts.post(key, eventId, media, "hello") {}
		posts.retry(key, eventId)
		posts.resumePending()

		assertEquals(0, hive.broadcasts)
		assertEquals(0, hive.prepares)
		assertTrue("the row stays locked", posts.status(key) is SnapPostStatus.Uncertain)
	}

	// ── the composer closes on the durable intent, not on Hive ─────────
	//
	// The bug these exist for was visible rather than unsafe: the card
	// appeared instantly and the text box stayed open above it for the whole
	// background pipeline, so a Snap that had worked looked like one that had
	// not. Closing the box is now its own callback, fired at the durable
	// checkpoint, and destroying the draft is still the other one.

	/** The box is already shut by the time anything asks a node. */
	@Test
	fun `the composer closes at the durable intent, before any network call`() {
		val hive = Hive(inBlock())
		val posts = drawingController(hive, Store()) { "alice" }
		var staged = false
		var stagedByContainerLookup: Boolean? = null
		hive.duringContainer = { stagedByContainerLookup = staged }

		posts.post(
			SnapDraftKey.of("alice", eventId),
			eventId,
			media,
			"hello",
			onStaged = { staged = true },
			onPublished = {},
		)

		assertEquals("the box was still open while a node was asked", true, stagedByContainerLookup)
	}

	/** Once, and well before the chain has anything to say. */
	@Test
	fun `the composer closes once, and before publication`() {
		val hive = Hive(inBlock())
		val posts = drawingController(hive, Store()) { "alice" }
		val order = mutableListOf<String>()

		posts.post(
			SnapDraftKey.of("alice", eventId),
			eventId,
			media,
			"hello",
			onStaged = { order += "staged" },
			onPublished = { order += "published" },
		)

		assertEquals(listOf("staged", "published"), order)
	}

	/**
	 * No record, no closing.
	 *
	 * The box closes because the Snap is durably the user's, so a Snap that
	 * could not be committed must leave it exactly where it was — open, on the
	 * words, with a refusal beside it.
	 */
	@Test
	fun `a Snap whose intent cannot be stored never closes the composer`() {
		val hive = Hive(inBlock())
		val store = Store().apply { writable = false }
		val posts = drawingController(hive, store) { "alice" }
		val key = SnapDraftKey.of("alice", eventId)
		var staged = false

		posts.post(key, eventId, media, "hello", onStaged = { staged = true }, onPublished = {})

		assertFalse("the composer must stay open", staged)
		assertEquals("and nothing may be sent", 0, hive.broadcasts)
		assertEquals(0, hive.prepares)
		assertTrue(posts.status(key) is SnapPostStatus.Failed)
	}

	/**
	 * A Snap that never reached Hive still closed the box.
	 *
	 * The two are not the same event. The intent is on disk with a permlink
	 * nothing can mint twice, so the composer has nothing left to hold; what
	 * the chain did or did not do is said on the card, which keeps the words
	 * and offers the retry.
	 */
	@Test
	fun `an interrupted Snap has still closed the composer and kept its words`() {
		val hive = Hive(inBlock()).apply {
			container = SnapContainerResolver.Result.Unavailable("no container")
		}
		val store = Store()
		val posts = drawingController(hive, store) { "alice" }
		val key = SnapDraftKey.of("alice", eventId)
		var staged = false
		var published = 0

		posts.post(key, eventId, media, "hello", onStaged = { staged = true }) { published++ }

		assertTrue("the box closed on the durable intent", staged)
		assertEquals("but the draft is nobody's to destroy yet", 0, published)
		assertEquals(0, hive.broadcasts)
		assertTrue(posts.status(key) is SnapPostStatus.Interrupted)
		assertEquals(
			"the intent is on disk, frozen",
			"hello",
			PendingSnap.fromJson(store.saved.values.single())!!.body.lineSequence().first(),
		)
	}

	/**
	 * A stored record naming another author never becomes a card.
	 *
	 * The screen's copy of a Snap and Hive's copy have to be the same thing,
	 * so a record whose author disagrees with the account it is filed under is
	 * not drawn at all — not optimistically, not as posted, and not as
	 * something the user could finish. It is surfaced as unresolved instead,
	 * which is the honest answer: there may be a comment on chain under one of
	 * the two names and RustedWax cannot tell which.
	 */
	@Test
	fun `a record written by another author is never drawn or finished`() {
		val hive = Hive(inBlock())
		val store = Store()
		val key = SnapDraftKey.of("alice", eventId)
		store.saved["alice|$eventId"] = PendingSnap(
			account = "alice",
			eventId = eventId,
			// The blocker in one field.
			author = "bob",
			permlink = "rustedwax-snap-1000-frozen",
			parentAuthor = "",
			parentPermlink = "",
			body = "hello\n\nhttps://youtu.be/8pSS6wdojqY\n\n#rustedwax #scrobblelife #music",
			jsonMetadata = """{"app":"rustedwax/test"}""",
			signedTransactionJson = "",
			txId = "",
			expirationEpochSec = 0L,
			state = PendingSnapState.INTENT,
			createdAtEpochSec = 900L,
			updatedAtEpochSec = 900L,
			kind = PendingSnapKind.ROOT,
		).toJson()
		val before = store.saved.toMap()
		val posts = drawingController(hive, store) { "alice" }

		posts.resumePending()
		posts.retry(key, eventId)

		assertNull("no card, optimistic or otherwise", posts.posted(key))
		assertFalse(
			"and never drawn as interrupted, which would offer to finish it",
			posts.status(key) is SnapPostStatus.Interrupted,
		)
		assertTrue("got ${posts.status(key)}", posts.status(key) is SnapPostStatus.Uncertain)
		assertEquals("nothing signed", 0, hive.prepares)
		assertEquals("nothing sent", 0, hive.broadcasts)
		assertEquals("and no fresh permlink minted over it", before, store.saved)
	}

	// ── a finished Snap retires its draft, however it finished ────────
	//
	// The A12 run found the gap these close: posting offline, then tapping
	// Retry once the network was back, published exactly once — and left the
	// draft on disk forever, because Retry passed a no-op where the first
	// attempt passes the callback that retires it. The rule is not "clear the
	// draft when the user retries"; it is "a Snap proven on chain has no draft
	// any more", and both paths have to mean the same thing by it.
	//
	// Everything here is about *when* that callback fires. Nothing about what
	// is published, or about when the card appears, moves.

	/** A record left intended, exactly as a failed container lookup leaves it. */
	private fun interruptedController(hive: Hive, store: Store): SnapPostController {
		hive.container = SnapContainerResolver.Result.Unavailable("no container")
		return drawingController(hive, store) { "alice" }
	}

	@Test
	fun `a confirmed first attempt reports its publication once`() {
		val hive = Hive(inBlock())
		val posts = drawingController(hive, Store()) { "alice" }
		val published = mutableListOf<String>()

		posts.post(
			SnapDraftKey.of("alice", eventId),
			eventId,
			media,
			"hello",
			onStaged = {},
			onPublished = { published += it },
		)

		assertEquals(1, published.size)
		assertEquals("alice/rustedwax-snap-1000-aaaaaa", published.single())
	}

	/**
	 * And a Snap finished by Retry reports it identically.
	 *
	 * The interruption is real — the container could not be resolved, so
	 * nothing was ever built — and the retry that follows is the only thing
	 * that publishes. What the callback proves is that the two success paths
	 * are now one: the draft a retried Snap was written from is retired by the
	 * same mechanism a first-attempt Snap's is.
	 */
	@Test
	fun `a confirmed retry reports its publication the same way`() {
		val hive = Hive(inBlock())
		val store = Store()
		val posts = interruptedController(hive, store)
		val key = SnapDraftKey.of("alice", eventId)
		val published = mutableListOf<String>()

		posts.post(key, eventId, media, "hello", onStaged = {}) { published += it }
		assertTrue(posts.status(key) is SnapPostStatus.Interrupted)
		assertEquals("nothing is published yet", 0, published.size)

		// The network comes back, and the user asks once.
		hive.container = null
		posts.retry(key, eventId) { published += it }

		assertEquals("reported exactly once", 1, published.size)
		assertEquals("alice/rustedwax-snap-1000-aaaaaa", published.single())
		assertTrue(posts.status(key) is SnapPostStatus.Posted)

		// And the durable record is untouched by the reporting.
		val record = PendingSnap.fromJson(store.saved.values.single())!!
		assertEquals(PendingSnapState.CONFIRMED, record.state)
		assertEquals("rustedwax-snap-1000-aaaaaa", record.permlink)
		assertEquals("alice", record.author)
		assertEquals(1, hive.broadcasts)
	}

	@Test
	fun `an interrupted retry reports nothing`() {
		val hive = Hive(inBlock())
		val store = Store()
		val posts = interruptedController(hive, store)
		val key = SnapDraftKey.of("alice", eventId)
		var published = 0

		posts.post(key, eventId, media, "hello", onStaged = {}) { published++ }
		// Still no container: the retry cannot build anything either.
		posts.retry(key, eventId) { published++ }

		assertEquals("the draft must be kept", 0, published)
		assertTrue(posts.status(key) is SnapPostStatus.Interrupted)
		assertEquals(
			"and the intent is still an intent",
			PendingSnapState.INTENT,
			PendingSnap.fromJson(store.saved.values.single())!!.state,
		)
		assertEquals(0, hive.broadcasts)
	}

	@Test
	fun `an uncertain retry reports nothing`() {
		val hive = Hive(HiveRpc.BroadcastResult.NetworkFailure("lost"))
		val store = Store()
		val posts = interruptedController(hive, store)
		val key = SnapDraftKey.of("alice", eventId)
		var published = 0

		posts.post(key, eventId, media, "hello", onStaged = {}) { published++ }
		hive.container = null
		posts.retry(key, eventId) { published++ }

		assertEquals("an ambiguous Snap keeps its draft", 0, published)
		assertTrue("got ${posts.status(key)}", posts.status(key) is SnapPostStatus.Uncertain)
	}

	@Test
	fun `a failed retry reports nothing`() {
		val hive = Hive(inBlock())
		val store = Store()
		// Proven absent: nothing reached Hive, and nothing here may say it did.
		store.saved["alice|$eventId"] = PendingSnap(
			account = "alice",
			eventId = eventId,
			author = "alice",
			permlink = "rustedwax-snap-1000-aaaaaa",
			parentAuthor = "peak.snaps",
			parentPermlink = "snap-container-1789648560",
			body = "hello",
			jsonMetadata = """{"app":"rustedwax/test"}""",
			signedTransactionJson = """{"op":1}""",
			txId = "tx-1",
			expirationEpochSec = 2_000_000_000L,
			state = PendingSnapState.FAILED,
			createdAtEpochSec = 900L,
			updatedAtEpochSec = 900L,
			lastError = "this Snap never reached Hive",
			kind = PendingSnapKind.ROOT,
		).toJson()
		val posts = drawingController(hive, store) { "alice" }
		val key = SnapDraftKey.of("alice", eventId)
		var published = 0

		posts.retry(key, eventId) { published++ }

		assertEquals(0, published)
		assertTrue("got ${posts.status(key)}", posts.status(key) is SnapPostStatus.Failed)
		assertEquals(0, hive.broadcasts)
	}

	/**
	 * The draft outlives everything up to the proof.
	 *
	 * Asserted from *inside* the broadcast, which is the last moment before
	 * publication is proven: if the callback had already fired there, a draft
	 * would be destroyed for a Snap whose outcome was still unknown — and a
	 * lost response at that exact point is the case the whole pending-record
	 * design exists for.
	 */
	@Test
	fun `success is reported only after the broadcast, never before`() {
		val hive = Hive(inBlock())
		val store = Store()
		val posts = interruptedController(hive, store)
		val key = SnapDraftKey.of("alice", eventId)
		var published = 0
		var publishedDuringBroadcast: Int? = null

		posts.post(key, eventId, media, "hello", onStaged = {}) { published++ }
		hive.container = null
		hive.duringBroadcast = { publishedDuringBroadcast = published }
		posts.retry(key, eventId) { published++ }

		assertEquals("nothing was reported while the bytes were on the wire", 0, publishedDuringBroadcast)
		assertEquals(1, published)
	}

	/**
	 * Retiring a draft cannot post anything.
	 *
	 * The callback runs inside the attempt that owns this key, so the ordinary
	 * duplicate guards are what answer a caller that tries to publish from it.
	 * Worth pinning because a cleanup callback is exactly the place someone
	 * would later add "and re-check it", and re-checking is not free here.
	 */
	@Test
	fun `the success callback cannot start another publication`() {
		val hive = Hive(inBlock())
		val store = Store()
		val posts = interruptedController(hive, store)
		val key = SnapDraftKey.of("alice", eventId)

		posts.post(key, eventId, media, "hello", onStaged = {}) {}
		hive.container = null
		posts.retry(key, eventId) {
			posts.retry(key, eventId) {}
			posts.post(key, eventId, media, "hello", onStaged = {}) {}
		}

		assertEquals("one signature", 1, hive.prepares)
		assertEquals("one broadcast", 1, hive.broadcasts)
		assertEquals("one record", 1, store.saved.size)
		assertEquals(
			PendingSnapState.CONFIRMED,
			PendingSnap.fromJson(store.saved.values.single())!!.state,
		)
	}
}
/**
 * [SnapPostController.post] for the many tests that do not care when the
 * composer closes.
 *
 * The production signature takes `onStaged` explicitly and without a default,
 * so no real caller can forget to close the box — that omission is the bug
 * this whole path exists to fix. Tests that *do* care pass it themselves.
 */
private fun SnapPostController.post(
	key: String,
	eventId: String,
	media: SnapMedia,
	userText: String,
	onPublished: (String) -> Unit,
) = post(key, eventId, media, userText, onStaged = {}, onPublished = onPublished)

/**
 * [SnapPostController.retry] for tests that do not care about the draft.
 *
 * Same reasoning as the [post] helper above: the production signature takes
 * the success callback explicitly, so no real caller can forget to retire the
 * draft a finished Snap was written from — which is exactly the omission the
 * A12 run found. Tests that *do* care pass it themselves.
 */
private fun SnapPostController.retry(key: String, eventId: String) =
	retry(key, eventId, onPublished = {})
