package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.SnapReply
import com.rustedwax.app.snaps.SnapReplyTarget
import com.rustedwax.app.snaps.SnapThreadReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #42 Stage 1: finding replies without a History card on screen.
 *
 * The check owns no rule of its own about who is notified or what is new —
 * those are [SnapNoticeScan] and [SnapNoticeStore] — so these tests pin what it
 * adds around them: which conversations it reads, how often, what it does when
 * a read fails or the account changes, and that a conversation already on
 * screen is never announced.
 */
class SnapReplyCheckTest {

	private val alice = "alice"
	private val bob = "bob"
	private val carol = "carol"
	private val root = SnapReplyTarget.of(alice, "rustedwax-snap-1000-aaaaaa")!!
	private val older = SnapReplyTarget.of(alice, "rustedwax-snap-900-bbbbbb")!!

	/** The real seeding and de-duplication rule, over a map that survives "restarts". */
	private class Store(val saved: MutableMap<String, String> = mutableMapOf()) : SnapNoticeStore {
		var writable = true
		var isReady = true
		val read = mutableListOf<String>()

		override fun ready() = isReady

		override fun all(account: String) = saved.entries
			.filter { SnapNoticeCodec.accountOf(it.key) == account }
			.mapNotNull { SnapNoticeCodec.decode(it.value) }

		override fun record(account: String, rootId: String, notices: List<SnapNotice>, atEpochSec: Long): List<SnapNotice>? {
			val seedKey = SnapNoticeCodec.seedKey(account, rootId)
			val seeding = seedKey !in saved
			val unseen = notices.filterNot { SnapNoticeCodec.noticeKey(account, it.contentId) in saved }
			if (!seeding && unseen.isEmpty()) return emptyList()
			if (!writable) return null
			if (seeding) saved[seedKey] = "seeded"
			return unseen.mapNotNull {
				val written = it.copy(read = seeding)
				saved[SnapNoticeCodec.noticeKey(account, it.contentId)] = SnapNoticeCodec.encode(written, atEpochSec)
				written.takeUnless { seeding }
			}
		}

		override fun markRead(account: String, contentId: String) {
			read += "$account|$contentId"
			val key = SnapNoticeCodec.noticeKey(account, contentId)
			val notice = saved[key]?.let { SnapNoticeCodec.decode(it) } ?: return
			saved[key] = SnapNoticeCodec.encode(notice.copy(read = true), 0L)
		}

		override fun markThreadRead(account: String, rootId: String) = Unit
	}

	private class Shade : SnapNoticeNotifier {
		val posted = mutableListOf<Pair<String, String>>()
		override fun post(account: String, notice: SnapNotice) {
			posted += account to notice.contentId
		}
		override fun cancel(account: String, contentId: String) = Unit
	}

	/** Each root's current replies; a missing root answers null, as a failed read does. */
	private class Chain(val threads: MutableMap<String, List<SnapReply>> = mutableMapOf()) : SnapThreadReader {
		val reads = mutableListOf<String>()
		var onRead: () -> Unit = {}
		override fun read(rootAuthor: String, rootPermlink: String, viewer: String?): List<SnapReply>? {
			reads += "$rootAuthor/$rootPermlink"
			onRead()
			return threads["$rootAuthor/$rootPermlink"]
		}
	}

	private class Checkpoints : SyncCheckpoints {
		val values = mutableMapOf<String, SyncCheckpoints.Checkpoint>()
		override fun get(owner: String, scope: String) = values["$owner|$scope"]
		override fun put(owner: String, scope: String, value: String, atMillis: Long) {
			values["$owner|$scope"] = SyncCheckpoints.Checkpoint(value, atMillis)
		}
		override fun delete(owner: String, scope: String) {
			values.remove("$owner|$scope")
		}
	}

	private fun reply(author: String, permlink: String, parentAuthor: String, parent: SnapReplyTarget = root) = SnapReply(
		author = author,
		permlink = permlink,
		parentAuthor = parentAuthor,
		parentPermlink = parent.permlink,
		body = "hello",
		createdAtEpochSec = 1_000L,
	)

	private var signedIn: String? = alice
	private var now = 10_000_000L
	private var onScreen: Pair<String, String>? = null

	/** Catalog rows, each created at the time its permlink names — fixed, as a real Snap's is. */
	private fun rows(targets: List<SnapReplyTarget>, owner: String = alice) = targets.map { t ->
		val at = t.permlink.split('-').getOrNull(2)?.toLongOrNull() ?: error("test permlink without a time: ${t.permlink}")
		MySnapRow(owner, t.author, t.permlink, at, null, null, null, null, null, null)
	}

	private fun check(
		store: Store,
		chain: Chain,
		shade: Shade,
		roots: List<SnapReplyTarget> = listOf(root),
		checkpoints: SyncCheckpoints? = null,
		// A new check is a new process unless a test says otherwise.
		gate: SnapReplyCheck.Gate = SnapReplyCheck.Gate(),
	) = SnapReplyCheck(
		account = { signedIn },
		roots = { rows(roots) },
		reader = { chain },
		store = store,
		notifier = shade,
		visible = { who, rootId -> onScreen == (who to rootId) },
		checkpoints = checkpoints,
		nowMillis = { now },
		sleep = { now += it },
		gate = gate,
	)

	/** A first look at each conversation, which records what is there without announcing it. */
	private fun seeded(store: Store, vararg roots: SnapReplyTarget) {
		roots.forEach { store.record(alice, it.contentId, emptyList(), 0L) }
	}

	@Test
	fun `a new reply to a Snap that is not on screen is found and announced once`() {
		val store = Store().also { seeded(it, root) }
		val chain = Chain(mutableMapOf(root.contentId to listOf(reply(bob, "r1", alice))))
		val shade = Shade()
		val check = check(store, chain, shade)

		val outcome = check.run()

		assertEquals(listOf(alice to "bob/r1"), shade.posted)
		assertTrue((outcome as SnapReplyCheck.Outcome.Checked).complete)
	}

	@Test
	fun `repeated runs and a restarted store never announce the same reply again`() {
		val store = Store().also { seeded(it, root) }
		val chain = Chain(mutableMapOf(root.contentId to listOf(reply(bob, "r1", alice))))
		val shade = Shade()

		repeat(3) { check(store, chain, shade).run(force = true) }
		// Process death: a new store and check over the same durable rows.
		val restarted = Shade()
		check(Store(store.saved), chain, restarted).run(force = true)

		assertEquals(listOf(alice to "bob/r1"), shade.posted)
		assertEquals(emptyList<Pair<String, String>>(), restarted.posted)
	}

	@Test
	fun `a reply to your own reply is announced, a conversation between two others is not`() {
		val store = Store().also { seeded(it, root) }
		val mine = SnapReplyTarget.of(alice, "mine")!!
		val chain = Chain(
			mutableMapOf(
				root.contentId to listOf(
					reply(bob, "b1", alice),
					reply(alice, "mine", bob, SnapReplyTarget.of(bob, "b1")!!),
					reply(carol, "c1", alice, mine),
					reply(carol, "c2", bob, SnapReplyTarget.of(bob, "b1")!!),
					reply(bob, "b2", carol, SnapReplyTarget.of(carol, "c2")!!),
				),
			),
		)
		val shade = Shade()

		check(store, chain, shade).run()

		assertEquals(listOf(alice to "bob/b1", alice to "carol/c1"), shade.posted)
	}

	@Test
	fun `the first look at a conversation is recorded silently`() {
		val store = Store()
		val chain = Chain(mutableMapOf(root.contentId to listOf(reply(bob, "old", alice))))
		val shade = Shade()

		check(store, chain, shade).run()

		assertEquals(emptyList<Pair<String, String>>(), shade.posted)
		assertTrue(SnapNoticeCodec.noticeKey(alice, "bob/old") in store.saved)
	}

	@Test
	fun `a reply in the conversation on screen is answered in place, not announced`() {
		val store = Store().also { seeded(it, root) }
		val chain = Chain(mutableMapOf(root.contentId to listOf(reply(bob, "r1", alice))))
		val shade = Shade()
		onScreen = alice to root.contentId

		val outcome = check(store, chain, shade).run() as SnapReplyCheck.Outcome.Checked

		assertEquals(emptyList<Pair<String, String>>(), shade.posted)
		assertEquals(listOf("alice|bob/r1"), store.read)
		assertEquals(listOf(root), outcome.quiet)
	}

	@Test
	fun `the same thread open under another account does not silence this one`() {
		val store = Store().also { seeded(it, root) }
		val chain = Chain(mutableMapOf(root.contentId to listOf(reply(bob, "r1", alice))))
		val shade = Shade()
		onScreen = bob to root.contentId

		check(store, chain, shade).run()

		assertEquals(listOf(alice to "bob/r1"), shade.posted)
	}

	@Test
	fun `one failed read does not stop the others, a second ends the run`() {
		val third = SnapReplyTarget.of(alice, "rustedwax-snap-800-cccccc")!!
		val fourth = SnapReplyTarget.of(alice, "rustedwax-snap-700-dddddd")!!
		val store = Store().also { seeded(it, root, older, third, fourth) }
		val chain = Chain(mutableMapOf(older.contentId to listOf(reply(bob, "r1", alice, older))))
		val shade = Shade()

		val outcome = check(store, chain, shade, roots = listOf(root, older, third, fourth)).run() as SnapReplyCheck.Outcome.Checked

		assertFalse(outcome.complete)
		assertEquals(listOf(root.contentId, older.contentId, third.contentId), chain.reads)
		assertEquals(listOf(alice to "bob/r1"), shade.posted)
	}

	@Test
	fun `a durable write that fails announces nothing`() {
		val store = Store().also { seeded(it, root) }
		store.writable = false
		val chain = Chain(mutableMapOf(root.contentId to listOf(reply(bob, "r1", alice))))
		val shade = Shade()

		val outcome = check(store, chain, shade).run() as SnapReplyCheck.Outcome.Checked

		assertFalse(outcome.complete)
		assertEquals(emptyList<Pair<String, String>>(), shade.posted)
	}

	@Test
	fun `an account switch during the read lands nothing anywhere`() {
		val store = Store().also { seeded(it, root) }
		val chain = Chain(mutableMapOf(root.contentId to listOf(reply(bob, "r1", alice))))
		chain.onRead = { signedIn = bob }
		val shade = Shade()

		val outcome = check(store, chain, shade).run()

		assertTrue(outcome is SnapReplyCheck.Outcome.Skipped)
		assertEquals(emptyList<Pair<String, String>>(), shade.posted)
		assertFalse(SnapNoticeCodec.noticeKey(alice, "bob/r1") in store.saved)
		assertTrue(store.saved.keys.none { SnapNoticeCodec.accountOf(it) == bob })
	}

	@Test
	fun `nobody signed in reads nothing`() {
		signedIn = null
		val chain = Chain()

		val outcome = check(Store(), chain, Shade()).run()

		assertTrue(outcome is SnapReplyCheck.Outcome.Skipped)
		assertEquals(emptyList<String>(), chain.reads)
	}

	@Test
	fun `at most fifteen minutes apart per account unless forced`() {
		val checkpoints = Checkpoints()
		val chain = Chain(mutableMapOf(root.contentId to emptyList()))
		val check = check(Store(), chain, Shade(), checkpoints = checkpoints)

		check.run()
		now += SnapReplyCheck.MIN_INTERVAL_MILLIS - 1
		assertTrue(check.run() is SnapReplyCheck.Outcome.Skipped)
		now += 1
		check.run()
		check.run(force = true)

		assertEquals(3, chain.reads.size)
	}

	@Test
	fun `another account is not held back by this account's last run`() {
		val checkpoints = Checkpoints()
		val chain = Chain(mutableMapOf(root.contentId to emptyList()))
		val check = check(Store(), chain, Shade(), checkpoints = checkpoints)

		check.run()
		signedIn = bob
		check.run()

		assertEquals(2, chain.reads.size)
	}

	@Test
	fun `storage that never becomes ready spends no reads`() {
		val store = Store().apply { isReady = false }
		val chain = Chain(mutableMapOf(root.contentId to emptyList()))
		val checkpoints = Checkpoints()

		val outcome = check(store, chain, Shade(), checkpoints = checkpoints).run()

		assertTrue(outcome is SnapReplyCheck.Outcome.Skipped)
		assertEquals(emptyList<String>(), chain.reads)
		assertTrue("not stamped, so the next run tries again", checkpoints.values.isEmpty())
	}

	@Test
	fun `candidates are merged one per identity, newest first`() {
		fun row(permlink: String, at: Long) = MySnapRow(alice, alice, permlink, at, null, null, null, null, null, null)
		val rows = listOf(
			row("rustedwax-snap-1-aaaaaa", 100),
			row("rustedwax-snap-3-aaaaaa", 300),
			row("rustedwax-snap-1-aaaaaa", 100),
			row("rustedwax-snap-2-aaaaaa", 200),
		)

		assertEquals(
			listOf("rustedwax-snap-3-aaaaaa", "rustedwax-snap-2-aaaaaa", "rustedwax-snap-1-aaaaaa"),
			SnapReplyCheck.ordered(rows).map { it.permlink },
		)
	}

	// ── coverage beyond the newest ten ────────────────────────────────

	private val many = (1..25).map { SnapReplyTarget.of(alice, "rustedwax-snap-${1000 - it}-cccccc")!! }

	private fun everyThreadEmpty(targets: List<SnapReplyTarget> = many) =
		Chain(targets.associate { it.contentId to emptyList<SnapReply>() }.toMutableMap())

	/** What each run read, one list per run. */
	private fun runs(count: Int, check: SnapReplyCheck, chain: Chain): List<List<String>> = (1..count).map {
		val before = chain.reads.size
		check.run(force = true)
		chain.reads.drop(before)
	}

	@Test
	fun `every Snap is eventually read, however many there are`() {
		val chain = everyThreadEmpty()
		val check = check(Store(), chain, Shade(), roots = many, checkpoints = Checkpoints())

		// 5 newest every run, the other 20 five at a time: one lap in four runs.
		runs(4, check, chain)

		assertEquals(many.map { it.contentId }.toSet(), chain.reads.toSet())
	}

	@Test
	fun `the newest Snaps are read first on every run`() {
		val chain = everyThreadEmpty()
		val check = check(Store(), chain, Shade(), roots = many, checkpoints = Checkpoints())
		val newest = many.take(SnapReplyCheck.NEWEST).map { it.contentId }

		runs(6, check, chain).forEach { assertEquals(newest, it.take(SnapReplyCheck.NEWEST)) }
	}

	@Test
	fun `no run reads more than the budget, and the lap wraps around`() {
		val chain = everyThreadEmpty()
		val check = check(Store(), chain, Shade(), roots = many, checkpoints = Checkpoints())

		val perRun = runs(5, check, chain)

		perRun.forEach { assertTrue(it.size <= SnapReplyCheck.MAX_ROOTS) }
		assertEquals("the fifth run starts the second lap", many[5].contentId, perRun[4][SnapReplyCheck.NEWEST])
	}

	@Test
	fun `the walk continues where it stopped after a restart`() {
		val checkpoints = Checkpoints()
		val chain = everyThreadEmpty()
		check(Store(), chain, Shade(), roots = many, checkpoints = checkpoints).run(force = true)

		// Process death: a new check over the same durable checkpoint.
		val restarted = everyThreadEmpty()
		check(Store(), restarted, Shade(), roots = many, checkpoints = checkpoints).run(force = true)

		assertEquals(many.subList(10, 15).map { it.contentId }, restarted.reads.drop(SnapReplyCheck.NEWEST))
	}

	@Test
	fun `a new Snap at the top shifts nothing in the walk`() {
		val checkpoints = Checkpoints()
		val chain = everyThreadEmpty()
		check(Store(), chain, Shade(), roots = many, checkpoints = checkpoints).run(force = true)

		val newer = SnapReplyTarget.of(alice, "rustedwax-snap-5000-eeeeee")!!
		val grown = listOf(newer) + many
		val next = everyThreadEmpty(grown)
		check(Store(), next, Shade(), roots = grown, checkpoints = checkpoints).run(force = true)

		// many[4] dropped out of the newest five and joins the lap behind the
		// position; the walk itself goes on from many[10].
		assertEquals(many.subList(10, 15).map { it.contentId }, next.reads.drop(SnapReplyCheck.NEWEST))
	}

	@Test
	fun `two accounts keep separate walks and separate notices`() {
		val checkpoints = Checkpoints()
		val store = Store().also { seeded(it, *many.toTypedArray()) }
		val chain = everyThreadEmpty()
		chain.threads[many[0].contentId] = listOf(reply(carol, "c1", alice, many[0]))
		val shade = Shade()
		val check = check(store, chain, shade, roots = many, checkpoints = checkpoints)

		check.run(force = true)
		signedIn = bob
		check.run(force = true)
		signedIn = alice
		check.run(force = true)

		assertTrue(checkpoints.get("alice", SnapReplyCheck.ROTATION_SCOPE) != null)
		assertTrue(checkpoints.get("bob", SnapReplyCheck.ROTATION_SCOPE) != null)
		val bobTurn = chain.reads.subList(SnapReplyCheck.MAX_ROOTS + SnapReplyCheck.NEWEST, 2 * SnapReplyCheck.MAX_ROOTS)
		assertEquals("Bob's walk starts at the beginning, not where Alice's stopped", many.subList(5, 10).map { it.contentId }, bobTurn)
		val aliceAgain = chain.reads.drop(2 * SnapReplyCheck.MAX_ROOTS + SnapReplyCheck.NEWEST)
		assertEquals("and Alice's goes on from where it was", many.subList(10, 15).map { it.contentId }, aliceAgain)
		assertEquals("one announcement, for Alice only, never repeated on return", listOf(alice to "carol/c1"), shade.posted)
		assertTrue(store.saved.keys.none { SnapNoticeCodec.accountOf(it) == bob })
	}

	@Test
	fun `a failed read in the walk is retried first on the next run`() {
		val checkpoints = Checkpoints()
		val chain = everyThreadEmpty()
		val failing = many[7].contentId
		chain.threads.remove(failing)
		val check = check(Store(), chain, Shade(), roots = many, checkpoints = checkpoints)

		check.run(force = true)
		chain.threads[failing] = emptyList()
		val before = chain.reads.size
		check.run(force = true)

		assertEquals(failing, chain.reads.drop(before)[SnapReplyCheck.NEWEST])
	}

	@Test
	fun `a Snap that can never be read is passed after three runs and comes round again`() {
		val checkpoints = Checkpoints()
		val chain = everyThreadEmpty()
		val failing = many[5].contentId
		chain.threads.remove(failing)
		val check = check(Store(), chain, Shade(), roots = many, checkpoints = checkpoints)

		val perRun = runs(8, check, chain)

		perRun.take(3).forEach { assertEquals(failing, it.last()) }
		assertEquals("then the walk moves on", many[6].contentId, perRun[3][SnapReplyCheck.NEWEST])
		assertTrue("and every other Snap is still reached", chain.reads.toSet().containsAll(many.map { it.contentId }))
		assertTrue("it is tried again on the next lap", perRun.drop(3).any { failing in it })
	}

	@Test
	fun `with the network down the walk does not move at all`() {
		val checkpoints = Checkpoints()
		val chain = Chain()
		check(Store(), chain, Shade(), roots = many, checkpoints = checkpoints).run(force = true)

		assertEquals("two failures end the run", 2, chain.reads.size)
		assertEquals(null, checkpoints.get("alice", SnapReplyCheck.ROTATION_SCOPE))
	}

	@Test
	fun `a notice write that fails does not move the walk past that Snap`() {
		val checkpoints = Checkpoints()
		val store = Store()
		val chain = everyThreadEmpty()
		val check = check(store, chain, Shade(), roots = many, checkpoints = checkpoints)
		check.run(force = true)
		val position = checkpoints.get("alice", SnapReplyCheck.ROTATION_SCOPE)

		store.writable = false
		chain.threads[many[10].contentId] = listOf(reply(bob, "r1", alice, many[10]))
		check.run(force = true)

		assertEquals(position, checkpoints.get("alice", SnapReplyCheck.ROTATION_SCOPE))
	}

	@Test
	fun `a checkpoint that will not save re-reads rather than skips`() {
		val chain = everyThreadEmpty()
		val broken = object : SyncCheckpoints {
			override fun get(owner: String, scope: String): SyncCheckpoints.Checkpoint? = null
			override fun put(owner: String, scope: String, value: String, atMillis: Long) = error("disk full")
			override fun delete(owner: String, scope: String) = Unit
		}
		val check = check(Store(), chain, Shade(), roots = many, checkpoints = broken)

		val perRun = runs(4, check, chain)

		assertEquals("this process still walks forward", many.map { it.contentId }.toSet(), perRun.flatten().toSet())
		val restarted = everyThreadEmpty()
		check(Store(), restarted, Shade(), roots = many, checkpoints = broken).run(force = true)
		assertEquals("after a restart it starts over, skipping nothing", many.subList(5, 10).map { it.contentId }, restarted.reads.drop(SnapReplyCheck.NEWEST))
	}

	@Test
	fun `a damaged saved position starts the walk over`() {
		val checkpoints = Checkpoints().apply { put("alice", SnapReplyCheck.ROTATION_SCOPE, "nonsense", 0L) }
		val chain = everyThreadEmpty()

		check(Store(), chain, Shade(), roots = many, checkpoints = checkpoints).run(force = true)

		assertEquals(many.subList(5, 10).map { it.contentId }, chain.reads.drop(SnapReplyCheck.NEWEST))
	}

	@Test
	fun `a reply to an older Snap is announced once, however many laps read it`() {
		val store = Store().also { seeded(it, *many.toTypedArray()) }
		val chain = everyThreadEmpty()
		val deep = many[22]
		chain.threads[deep.contentId] = listOf(reply(bob, "late", alice, deep))
		val shade = Shade()
		val check = check(store, chain, shade, roots = many, checkpoints = Checkpoints())

		runs(12, check, chain)

		assertEquals(listOf(alice to "bob/late"), shade.posted)
		assertEquals("three laps read it", 3, chain.reads.count { it == deep.contentId })
	}

	@Test
	fun `rotation position round-trips and refuses what it did not write`() {
		val r = SnapReplyCheck.Rotation(123L, "rustedwax-snap-123-aaaaaa", "alice/x", 2)
		assertEquals(r, SnapReplyCheck.Rotation.decode(r.encode()))
		assertEquals(SnapReplyCheck.Rotation(), SnapReplyCheck.Rotation.decode(SnapReplyCheck.Rotation().encode()))
		listOf("", "1|", "x|p||0", "1||a|0", "|p||0", "1|p||-1", "1|p|a").forEach {
			assertEquals(it, null, SnapReplyCheck.Rotation.decode(it))
		}
	}

	// Issue #42 Stage 2: the foreground check and the scheduled background one
	// are two instances in one process, over one store and one checkpoint table.

	@Test
	fun `foreground and background never run at once in one process`() {
		val store = Store().also { seeded(it, *(many + root).toTypedArray()) }
		val chain = everyThreadEmpty()
		chain.threads[root.contentId] = listOf(reply(bob, "r1", alice))
		val shade = Shade()
		val gate = SnapReplyCheck.Gate()
		val checkpoints = Checkpoints()
		val background = check(store, chain, shade, roots = many + root, checkpoints = checkpoints, gate = gate)
		val foreground = check(store, chain, shade, roots = many + root, checkpoints = checkpoints, gate = gate)
		val overlapping = mutableListOf<SnapReplyCheck.Outcome>()
		chain.onRead = { if (overlapping.isEmpty()) overlapping += foreground.run(force = true) }

		background.run()

		assertEquals(SnapReplyCheck.Outcome.Skipped("already running"), overlapping.single())
		assertEquals("one run's budget, not two", SnapReplyCheck.MAX_ROOTS, chain.reads.size)
		assertEquals(listOf(alice to "bob/r1"), shade.posted)
	}

	@Test
	fun `a background run soon after a foreground one reads nothing`() {
		val store = Store().also { seeded(it, root) }
		val chain = Chain(mutableMapOf(root.contentId to listOf(reply(bob, "r1", alice))))
		val shade = Shade()
		val gate = SnapReplyCheck.Gate()
		val checkpoints = Checkpoints()

		check(store, chain, shade, checkpoints = checkpoints, gate = gate).run()
		now += SnapReplyCheck.MIN_INTERVAL_MILLIS / 2
		val later = check(store, chain, shade, checkpoints = checkpoints, gate = gate).run()

		assertEquals(SnapReplyCheck.Outcome.Skipped("too soon"), later)
		assertEquals(1, chain.reads.size)
		assertEquals(listOf(alice to "bob/r1"), shade.posted)
	}

	@Test
	fun `the same reply found by both sides is announced once`() {
		val store = Store().also { seeded(it, root) }
		val chain = Chain(mutableMapOf(root.contentId to listOf(reply(bob, "r1", alice))))
		val shade = Shade()

		check(store, chain, shade).run(force = true)
		// A second process's worth of state: another gate, the same durable rows.
		check(Store(store.saved), chain, shade).run(force = true)

		assertEquals(listOf(alice to "bob/r1"), shade.posted)
	}

	@Test
	fun `a sign-out during a background run announces and records nothing`() {
		val store = Store().also { seeded(it, root) }
		val chain = Chain(mutableMapOf(root.contentId to listOf(reply(bob, "r1", alice))))
		chain.onRead = { signedIn = null }
		val shade = Shade()

		val outcome = check(store, chain, shade).run()

		assertEquals(SnapReplyCheck.Outcome.Skipped("account changed"), outcome)
		assertEquals(emptyList<Pair<String, String>>(), shade.posted)
		assertFalse(SnapNoticeCodec.noticeKey(alice, "bob/r1") in store.saved)
	}

	@Test
	fun `a background run reads for whoever is signed in when it starts`() {
		val bobRoot = SnapReplyTarget.of(bob, "rustedwax-snap-1000-bbbbbb")!!
		val store = Store()
		store.record(bob, bobRoot.contentId, emptyList(), 0L)
		val chain = Chain(
			mutableMapOf(
				root.contentId to listOf(reply(carol, "for-alice", alice)),
				bobRoot.contentId to listOf(reply(carol, "for-bob", bob, bobRoot)),
			),
		)
		val shade = Shade()
		signedIn = bob
		val check = SnapReplyCheck(
			account = { signedIn },
			roots = { who -> rows(if (who == bob) listOf(bobRoot) else listOf(root), owner = who) },
			reader = { chain },
			store = store,
			notifier = shade,
			visible = { _, _ -> false },
			checkpoints = null,
			nowMillis = { now },
			sleep = { now += it },
			gate = SnapReplyCheck.Gate(),
		)

		check.run()

		assertEquals(listOf(bobRoot.contentId), chain.reads)
		assertEquals(listOf(bob to "carol/for-bob"), shade.posted)
		assertTrue(store.saved.keys.none { SnapNoticeCodec.accountOf(it) == alice })
	}

	@Test
	fun `a scheduled run may read sooner than the foreground, never on top of another run`() {
		val checkpoints = Checkpoints()
		val chain = Chain(mutableMapOf(root.contentId to emptyList()))
		val gate = SnapReplyCheck.Gate()
		val foreground = check(Store(), chain, Shade(), checkpoints = checkpoints, gate = gate)
		val job = check(Store(), chain, Shade(), checkpoints = checkpoints, gate = gate)
		val jobInterval = SnapReplySchedule.JOB_MIN_INTERVAL_MILLIS

		job.run(minIntervalMillis = jobInterval)
		now += 5_000L
		assertEquals("the platform's catch-up run", SnapReplyCheck.Outcome.Skipped("too soon"), job.run(minIntervalMillis = jobInterval))
		now += jobInterval - 5_000L
		assertEquals("the foreground still waits a full period", SnapReplyCheck.Outcome.Skipped("too soon"), foreground.run())
		assertTrue(job.run(minIntervalMillis = jobInterval) is SnapReplyCheck.Outcome.Checked)

		assertEquals(2, chain.reads.size)
	}
}
