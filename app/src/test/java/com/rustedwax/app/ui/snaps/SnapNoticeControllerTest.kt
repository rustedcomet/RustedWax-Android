package com.rustedwax.app.ui.snaps

import com.rustedwax.app.snaps.SnapReply
import com.rustedwax.app.snaps.SnapReplyTarget
import com.rustedwax.hive.ViewerVote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who gets told about a reply, how often, and whose bell it lands in.
 *
 * The four rules this file exists to pin are the ones a notification feature
 * gets wrong in ways nobody notices until it is on somebody's phone: telling
 * you about your own words, telling you the same thing twice, telling one
 * account about another's conversations, and forgetting it ever told you the
 * moment the process dies.
 */
class SnapNoticeControllerTest {

	private val alice = "alice"
	private val bob = "bob"
	private val root = SnapReplyTarget.of(alice, "rustedwax-snap-1000-aaaaaa")!!

	/**
	 * A notice store that survives a "restart".
	 *
	 * Backed by a map handed in from the test, so a second controller can be
	 * built over the same durable bytes — the same way the rest of this package
	 * expresses process death. The encoding is the real one: rows go through
	 * [SnapNoticeCodec] in both directions, so a field the codec would refuse
	 * off disk is refused here too.
	 */
	private class Notices(val saved: MutableMap<String, String> = mutableMapOf()) : SnapNoticeStore {
		var recorded = 0

		/**
		 * Flip to simulate a disk that will not take the write.
		 *
		 * This models the real store's **observable contract after rollback**:
		 * null to the caller, and nothing visible left behind. It deliberately
		 * does not model *how* that is achieved — Android's `commit()` mutates
		 * the in-process map before it ever tries the disk, so the real store
		 * has to undo that itself. That mechanism is a property of
		 * [SharedPreferencesSnapNoticeStore] and is tested against a
		 * memory-first `SharedPreferences` in `SnapNoticeStoreTest`; a fake
		 * standing in for the store here could only assert its own behaviour.
		 */
		var writable = true

		/** The same collapse-don't-delete pruning the real store performs. */
		fun prune(max: Int) {
			SnapNoticeCodec.collapsibleKeys(saved.toMap(), max).forEach {
				saved[it] = SnapNoticeCodec.TOMBSTONE
			}
		}

		override fun all(account: String): List<SnapNotice> = saved.entries
			.filter { SnapNoticeCodec.accountOf(it.key) == account }
			.mapNotNull { SnapNoticeCodec.decode(it.value) }

		override fun record(
			account: String,
			rootId: String,
			notices: List<SnapNotice>,
			atEpochSec: Long,
		): List<SnapNotice>? {
			recorded++
			val seedKey = SnapNoticeCodec.seedKey(account, rootId)
			val seeding = seedKey !in saved
			val unseen = notices.filterNot {
				SnapNoticeCodec.noticeKey(account, it.contentId) in saved
			}
			if (!seeding && unseen.isEmpty()) return emptyList()
			// Nothing is written at all when the disk refuses — the real editor
			// is atomic, so a half-applied batch is not a state to model.
			if (!writable) return null
			if (seeding) saved[seedKey] = "seeded"
			val fresh = mutableListOf<SnapNotice>()
			unseen.forEach {
				val written = it.copy(read = seeding)
				saved[SnapNoticeCodec.noticeKey(account, it.contentId)] =
					SnapNoticeCodec.encode(written, atEpochSec)
				if (!seeding) fresh += written
			}
			return fresh
		}

		override fun markRead(account: String, contentId: String) {
			val key = SnapNoticeCodec.noticeKey(account, contentId)
			val raw = saved[key] ?: return
			val notice = SnapNoticeCodec.decode(raw) ?: return
			saved[key] = SnapNoticeCodec.encode(notice.copy(read = true), 0L)
		}

		override fun markThreadRead(account: String, rootId: String) {
			saved.keys.toList().forEach { key ->
				if (SnapNoticeCodec.accountOf(key) != account) return@forEach
				val notice = SnapNoticeCodec.decode(saved.getValue(key)) ?: return@forEach
				if (notice.rootId != rootId) return@forEach
				saved[key] = SnapNoticeCodec.encode(notice.copy(read = true), 0L)
			}
		}
	}

	/** Every announcement the shade was asked for, and every one taken back. */
	private class Shade : SnapNoticeNotifier {
		val posted = mutableListOf<Pair<String, String>>()
		val cancelled = mutableListOf<Pair<String, String>>()
		override fun post(account: String, notice: SnapNotice) {
			posted += account to notice.contentId
		}
		override fun cancel(account: String, contentId: String) {
			cancelled += account to contentId
		}
	}

	private fun controller(
		store: Notices,
		shade: Shade = Shade(),
		account: () -> String?,
	) = SnapNoticeController(
		store = store,
		account = account,
		notifier = shade,
		nowEpochSec = { 1_000L },
	)

	private fun reply(
		author: String,
		permlink: String,
		parentAuthor: String,
		parentPermlink: String = root.permlink,
		at: Long? = 1_000L,
	) = SnapReply(
		author = author,
		permlink = permlink,
		parentAuthor = parentAuthor,
		parentPermlink = parentPermlink,
		body = "hello",
		createdAtEpochSec = at,
	)

	/**
	 * The first read of a conversation is history, not news.
	 *
	 * Used by most tests below so they can assert on what a *later* read does,
	 * which is the interesting half. The seeding rule itself is pinned on its
	 * own further down.
	 */
	private fun seed(controller: SnapNoticeController, replies: List<SnapReply> = emptyList()) {
		controller.record(alice, root, replies)
	}

	// ── who is notified ────────────────────────────────────────────────

	@Test
	fun `a reply from somebody else to your Snap notifies`() {
		val store = Notices()
		val notices = controller(store) { alice }
		notices.load()
		seed(notices)

		notices.record(alice, root, listOf(reply(bob, "r1", alice)))

		val unread = notices.unread()
		assertEquals(1, unread.size)
		assertEquals("bob/r1", unread.single().contentId)
		assertEquals(root.contentId, unread.single().rootId)
	}

	@Test
	fun `your own reply never notifies`() {
		val store = Notices()
		val notices = controller(store) { alice }
		notices.load()
		seed(notices)

		// Alice replying under her own Snap — the exact shape a thread re-read
		// produces the moment after she sends one.
		notices.record(alice, root, listOf(reply(alice, "mine", alice)))

		assertEquals(emptyList<SnapNotice>(), notices.unread())
	}

	@Test
	fun `a reply to somebody else does not notify`() {
		val store = Notices()
		val notices = controller(store) { alice }
		notices.load()
		seed(notices)

		// Two strangers talking under Alice's Snap. It is her conversation, but
		// it is not addressed to her, and a bell that rang for it would ring for
		// every comment on a popular Snap.
		notices.record(alice, root, listOf(reply(bob, "r1", "carol", "carols-comment")))

		assertEquals(emptyList<SnapNotice>(), notices.unread())
	}

	@Test
	fun `a reply to your reply notifies`() {
		val store = Notices()
		val notices = controller(store) { alice }
		notices.load()
		seed(notices)

		notices.record(alice, root, listOf(reply(bob, "r2", alice, "alices-reply")))

		assertEquals(1, notices.unread().size)
	}

	/**
	 * A Like cannot become a notification, and the type system is what says so.
	 *
	 * The scan is handed reply rows. A vote arrives on a *field* of a reply —
	 * `viewerVote`, `positiveLikeCount` — and never as a row of its own, so a
	 * conversation in which the only thing that changed is that somebody liked
	 * Alice's comment produces no notice at all. Asserted through the real
	 * entry point rather than by inspecting the scan, because the claim is about
	 * what the bell does, not about what a private function believes.
	 */
	@Test
	fun `an incoming Like never notifies`() {
		val store = Notices()
		val notices = controller(store) { alice }
		notices.load()

		val liked = reply(alice, "mine", alice).copy(
			viewerVote = ViewerVote.Positive(10_000, 1L),
			positiveLikeCount = 7,
		)
		seed(notices, listOf(liked))
		notices.record(alice, root, listOf(liked.copy(positiveLikeCount = 8)))

		assertEquals(emptyList<SnapNotice>(), notices.unread())
	}

	// ── deduplication ──────────────────────────────────────────────────

	@Test
	fun `the same reply is surfaced once however often the thread is re-read`() {
		val store = Notices()
		val notices = controller(store) { alice }
		notices.load()
		seed(notices)

		val incoming = listOf(reply(bob, "r1", alice))
		repeat(5) { notices.record(alice, root, incoming) }

		assertEquals(1, notices.unread().size)
	}

	@Test
	fun `a reply answered once does not come back on the next read`() {
		val store = Notices()
		val notices = controller(store) { alice }
		notices.load()
		seed(notices)
		val incoming = listOf(reply(bob, "r1", alice))
		notices.record(alice, root, incoming)

		notices.markRead(notices.unread().single())
		// The thread is opened again, and the chain says exactly what it said
		// before. Nothing about that is new.
		notices.record(alice, root, incoming)

		assertEquals(emptyList<SnapNotice>(), notices.unread())
	}

	@Test
	fun `opening the conversation answers every notice under it`() {
		val store = Notices()
		val notices = controller(store) { alice }
		notices.load()
		seed(notices)
		notices.record(
			alice,
			root,
			listOf(reply(bob, "r1", alice), reply("carol", "r2", alice)),
		)
		assertEquals(2, notices.unread().size)

		notices.markThreadRead(root)

		assertEquals(emptyList<SnapNotice>(), notices.unread())
	}

	@Test
	fun `the first read of a conversation is seeded rather than announced`() {
		val store = Notices()
		val notices = controller(store) { alice }
		notices.load()

		// A month of conversation that happened while this install did not exist.
		notices.record(
			alice,
			root,
			listOf(reply(bob, "old1", alice), reply(bob, "old2", alice)),
		)

		assertEquals(
			"existing replies are history, not notifications",
			emptyList<SnapNotice>(),
			notices.unread(),
		)

		// And the very next reply is news, because the conversation is now known.
		notices.record(
			alice,
			root,
			listOf(reply(bob, "old1", alice), reply(bob, "old2", alice), reply(bob, "new", alice)),
		)
		assertEquals(listOf("bob/new"), notices.unread().map { it.contentId })
	}

	// ── account isolation ──────────────────────────────────────────────

	@Test
	fun `one account never sees another account's notices`() {
		val store = Notices()
		var who: String? = alice
		val notices = controller(store) { who }
		notices.load()
		seed(notices)
		notices.record(alice, root, listOf(reply(bob, "r1", alice)))
		assertEquals(1, notices.unread().size)

		who = "carol"
		notices.load()

		assertEquals(
			"Carol's bell must not hold Alice's conversations",
			emptyList<SnapNotice>(),
			notices.unread(),
		)

		who = alice
		notices.load()
		assertEquals("and Alice's are still hers", 1, notices.unread().size)
	}

	@Test
	fun `an answer fetched under another account is discarded`() {
		val store = Notices()
		var who: String? = alice
		val notices = controller(store) { who }
		notices.load()
		seed(notices)

		// The read started as Alice; Bob signed in before it landed.
		who = bob
		notices.record(alice, root, listOf(reply("carol", "r1", alice)))

		assertEquals(emptyList<SnapNotice>(), notices.unread())
		who = alice
		notices.load()
		assertEquals(
			"and it is not quietly filed under Alice either — it was never accepted",
			emptyList<SnapNotice>(),
			notices.unread(),
		)
	}

	@Test
	fun `nobody signed in means nothing is recorded`() {
		val store = Notices()
		val notices = controller(store) { null }
		notices.load()

		notices.record(alice, root, listOf(reply(bob, "r1", alice)))

		assertEquals(emptyList<SnapNotice>(), notices.unread())
		assertEquals("the store was never touched", 0, store.recorded)
	}

	// ── persistence ────────────────────────────────────────────────────

	@Test
	fun `an unread notice survives process death`() {
		val disk = Notices()
		controller(disk) { alice }.also {
			it.load()
			seed(it)
			it.record(alice, root, listOf(reply(bob, "r1", alice)))
		}

		// A second controller over the same bytes: the process died, the map did
		// not.
		val restarted = controller(Notices(disk.saved)) { alice }
		restarted.load()

		assertEquals(listOf("bob/r1"), restarted.unread().map { it.contentId })
	}

	@Test
	fun `a notice answered before a restart stays answered`() {
		val disk = Notices()
		controller(disk) { alice }.also {
			it.load()
			seed(it)
			it.record(alice, root, listOf(reply(bob, "r1", alice)))
			it.markThreadRead(root)
		}

		val restarted = controller(Notices(disk.saved)) { alice }
		restarted.load()
		// The thread is read again after the restart, as History does for every
		// posted Snap on screen.
		restarted.record(alice, root, listOf(reply(bob, "r1", alice)))

		assertEquals(
			"a read tombstone is what stops an old reply being announced twice",
			emptyList<SnapNotice>(),
			restarted.unread(),
		)
	}

	// ── ordering and content ───────────────────────────────────────────

	@Test
	fun `unread replies read newest first`() {
		val store = Notices()
		val notices = controller(store) { alice }
		notices.load()
		seed(notices)
		notices.record(
			alice,
			root,
			listOf(
				reply(bob, "older", alice, at = 1_000L),
				reply(bob, "newer", alice, at = 2_000L),
			),
		)

		assertEquals(listOf("bob/newer", "bob/older"), notices.unread().map { it.contentId })
	}

	@Test
	fun `a notice carries the conversation to open and a clamped line of text`() {
		val store = Notices()
		val notices = controller(store) { alice }
		notices.load()
		seed(notices)
		val long = "x".repeat(SnapThreadPreviewChars.LIMIT * 2)
		notices.record(alice, root, listOf(reply(bob, "r1", alice).copy(body = long)))

		val notice = notices.unread().single()
		assertEquals(root, notice.root())
		assertTrue(
			"a stranger's reply must not arrive in the bell at full length",
			notice.text.length <= SnapThreadPreviewChars.LIMIT,
		)
	}

	@Test
	fun `a reply whose author is not a usable identity is refused`() {
		val store = Notices()
		val notices = controller(store) { alice }
		notices.load()
		seed(notices)

		// A permlink with a slash in it would split wrong in a store key and
		// wrong again in a content id. The row is dropped rather than repaired.
		notices.record(alice, root, listOf(reply(bob, "has/slash", alice)))

		assertEquals(emptyList<SnapNotice>(), notices.unread())
	}

	@Test
	fun `a damaged stored notice is dropped rather than drawn`() {
		val disk = Notices()
		disk.saved[SnapNoticeCodec.noticeKey(alice, "bob/r1")] = """{"author":"bob"}"""
		val notices = controller(disk) { alice }

		notices.load()

		assertEquals(emptyList<SnapNotice>(), notices.unread())
	}

	// ── the system shade ───────────────────────────────────────────────

	@Test
	fun `one new incoming reply asks for one Android notification`() {
		val shade = Shade()
		val notices = controller(Notices(), shade) { alice }
		notices.load()
		seed(notices)

		notices.record(alice, root, listOf(reply(bob, "r1", alice)))

		assertEquals(listOf(alice to "bob/r1"), shade.posted)
	}

	@Test
	fun `re-reading the same conversation raises no second notification`() {
		val shade = Shade()
		val notices = controller(Notices(), shade) { alice }
		notices.load()
		seed(notices)
		val incoming = listOf(reply(bob, "r1", alice))

		repeat(5) { notices.record(alice, root, incoming) }

		assertEquals(
			"the store decides what is new, once, and the shade is only told",
			listOf(alice to "bob/r1"),
			shade.posted,
		)
	}

	@Test
	fun `your own reply raises nothing in the shade`() {
		val shade = Shade()
		val notices = controller(Notices(), shade) { alice }
		notices.load()
		seed(notices)

		notices.record(alice, root, listOf(reply(alice, "mine", alice)))

		assertEquals(emptyList<Pair<String, String>>(), shade.posted)
	}

	@Test
	fun `a Like on your comment raises nothing in the shade`() {
		val shade = Shade()
		val notices = controller(Notices(), shade) { alice }
		notices.load()
		val liked = reply(alice, "mine", alice).copy(
			viewerVote = ViewerVote.Positive(10_000, 1L),
			positiveLikeCount = 3,
		)
		seed(notices, listOf(liked))

		notices.record(alice, root, listOf(liked.copy(positiveLikeCount = 9)))

		assertEquals(emptyList<Pair<String, String>>(), shade.posted)
	}

	@Test
	fun `the first read of a conversation raises nothing in the shade`() {
		val shade = Shade()
		val notices = controller(Notices(), shade) { alice }
		notices.load()

		// A conversation full of replies from before this install existed.
		notices.record(
			alice,
			root,
			listOf(reply(bob, "old1", alice), reply(bob, "old2", alice)),
		)

		assertEquals(
			"seeding must never ring: a reinstall would announce a month of replies",
			emptyList<Pair<String, String>>(),
			shade.posted,
		)
	}

	@Test
	fun `a reply already on file before a restart is not announced again`() {
		val disk = Notices()
		controller(disk) { alice }.also {
			it.load()
			seed(it)
			it.record(alice, root, listOf(reply(bob, "r1", alice)))
		}

		val shade = Shade()
		val restarted = controller(Notices(disk.saved), shade) { alice }
		restarted.load()
		restarted.record(alice, root, listOf(reply(bob, "r1", alice)))

		assertEquals(emptyList<Pair<String, String>>(), shade.posted)
	}

	@Test
	fun `an answer that lands after an account switch raises nothing`() {
		val shade = Shade()
		var who: String? = alice
		val notices = controller(Notices(), shade) { who }
		notices.load()
		seed(notices)

		who = bob
		notices.record(alice, root, listOf(reply("carol", "r1", alice)))

		assertEquals(
			"Alice's reply must not appear in Bob's shade",
			emptyList<Pair<String, String>>(),
			shade.posted,
		)
	}

	@Test
	fun `opening the conversation takes its notifications back`() {
		val shade = Shade()
		val notices = controller(Notices(), shade) { alice }
		notices.load()
		seed(notices)
		notices.record(
			alice,
			root,
			listOf(reply(bob, "r1", alice), reply("carol", "r2", alice)),
		)

		notices.markThreadRead(root)

		assertEquals(
			"a reply the user has reached should not still be in the status bar",
			listOf(alice to "bob/r1", alice to "carol/r2"),
			shade.cancelled.sortedBy { it.second },
		)
	}

	@Test
	fun `answering one notice takes only that notification back`() {
		val shade = Shade()
		val notices = controller(Notices(), shade) { alice }
		notices.load()
		seed(notices)
		notices.record(alice, root, listOf(reply(bob, "r1", alice)))

		notices.markRead(notices.unread().single())
		notices.markRead(notices.unread().firstOrNull() ?: return)

		assertEquals(listOf(alice to "bob/r1"), shade.cancelled)
	}

	@Test
	fun `the notice is durable before the shade is disturbed`() {
		val disk = Notices()
		val shade = Shade()
		val notices = controller(disk, shade) { alice }
		notices.load()
		seed(notices)

		notices.record(alice, root, listOf(reply(bob, "r1", alice)))

		assertTrue(
			"a notification whose record did not survive would be a reply the " +
				"user was told about and the bell has never heard of",
			SnapNoticeCodec.noticeKey(alice, "bob/r1") in disk.saved,
		)
		assertEquals(listOf(alice to "bob/r1"), shade.posted)
	}

	// ── pruning must never resurrect ───────────────────────────────────

	@Test
	fun `pruning drops the display data of answered notices first`() {
		val entries = buildMap {
			put(
				SnapNoticeCodec.noticeKey(alice, "bob/read"),
				SnapNoticeCodec.encode(notice("read", read = true), at = 5_000L),
			)
			put(
				SnapNoticeCodec.noticeKey(alice, "bob/unread"),
				SnapNoticeCodec.encode(notice("unread", read = false), at = 1_000L),
			)
		}

		val collapsible = SnapNoticeCodec.collapsibleKeys(entries, max = 1)

		assertEquals(
			"a row the user has not seen is the last to lose its text",
			listOf(SnapNoticeCodec.noticeKey(alice, "bob/read")),
			collapsible,
		)
	}

	@Test
	fun `a collapsed row is not counted and not collapsed again`() {
		val entries = mapOf(
			SnapNoticeCodec.noticeKey(alice, "bob/old") to SnapNoticeCodec.TOMBSTONE,
			SnapNoticeCodec.noticeKey(alice, "bob/live") to
				SnapNoticeCodec.encode(notice("live", read = true), at = 1L),
		)

		assertEquals(
			"counting tombstones would drive the display cap towards zero as they " +
				"accumulate",
			emptyList<String>(),
			SnapNoticeCodec.collapsibleKeys(entries, max = 1),
		)
	}

	@Test
	fun `a seed mark is never collapsed as if it were a notice`() {
		val entries = mapOf(
			SnapNoticeCodec.noticeKey(alice, "bob/r1") to
				SnapNoticeCodec.encode(notice("r1", read = true), at = 1L),
			SnapNoticeCodec.seedKey(alice, root.contentId) to "seeded",
		)

		assertFalse(
			SnapNoticeCodec.collapsibleKeys(entries, max = 0)
				.any { it.startsWith("s|") },
		)
	}

	@Test
	fun `a collapsed row is never drawn`() {
		assertNull(
			"a tombstone is a seen-marker, not a record with something to show",
			SnapNoticeCodec.decode(SnapNoticeCodec.TOMBSTONE),
		)
	}

	/**
	 * The bug this whole mechanism exists for.
	 *
	 * The store is driven past its display cap so that the earliest reply is
	 * pruned, and then the very same conversation is re-read — which is what
	 * History does whenever the card is on screen. Before tombstones the pruned
	 * row was *gone*, so that months-old comment came back as news and was
	 * announced in the system shade a second time.
	 */
	@Test
	fun `a pruned old reply is never announced again`() {
		val disk = Notices()
		val shade = Shade()
		val notices = controller(disk, shade) { alice }
		notices.load()

		val oldest = reply(bob, "oldest", alice)
		seed(notices, listOf(oldest))
		assertEquals("seeding never announces", emptyList<Pair<String, String>>(), shade.posted)

		// Well past the display cap, so the oldest rows lose their text.
		val many = (0 until SharedPreferencesSnapNoticeStore.MAX_DISPLAYED_NOTICES + 20).map {
			reply(bob, "later$it", alice)
		}
		notices.record(alice, root, listOf(oldest) + many)
		disk.prune(SharedPreferencesSnapNoticeStore.MAX_DISPLAYED_NOTICES)

		assertTrue(
			"the test is only meaningful if pruning actually happened",
			disk.saved.values.count { SnapNoticeCodec.isTombstone(it) } > 0,
		)
		val announcedBefore = shade.posted.size
		shade.posted.clear()

		// The ordinary re-read of a conversation already known.
		notices.record(alice, root, listOf(oldest) + many)

		assertEquals(
			"an ordinary re-read after pruning must announce nothing at all",
			emptyList<Pair<String, String>>(),
			shade.posted,
		)
		assertEquals(
			"and no row may be re-classified as new",
			emptyList<SnapNotice>(),
			notices.unread().filter { it.contentId == "bob/oldest" },
		)
		assertTrue("sanity: the first pass did announce", announcedBefore > 0)
	}

	@Test
	fun `pruning one account cannot resurrect another account's replies`() {
		val disk = Notices()
		var who: String? = alice
		val shade = Shade()
		val notices = controller(disk, shade) { who }
		notices.load()
		seed(notices)
		notices.record(alice, root, listOf(reply(bob, "shared", alice)))
		notices.markThreadRead(root)

		// Carol reads her own busy conversation and drives pruning.
		who = "carol"
		notices.load()
		val carolRoot = SnapReplyTarget.of("carol", "rustedwax-snap-2000-cccccc")!!
		notices.record("carol", carolRoot, emptyList())
		val lots = (0 until SharedPreferencesSnapNoticeStore.MAX_DISPLAYED_NOTICES + 20).map {
			reply(bob, "c$it", "carol", carolRoot.permlink)
		}
		notices.record("carol", carolRoot, lots)
		disk.prune(SharedPreferencesSnapNoticeStore.MAX_DISPLAYED_NOTICES)

		who = alice
		notices.load()
		shade.posted.clear()
		notices.record(alice, root, listOf(reply(bob, "shared", alice)))

		assertEquals(
			"Alice's answered reply must not be announced because Carol's store " +
				"grew",
			emptyList<Pair<String, String>>(),
			shade.posted,
		)
		assertEquals(emptyList<SnapNotice>(), notices.unread())
	}

	// ── a durable write that did not happen ────────────────────────────

	@Test
	fun `a failed write announces nothing`() {
		val disk = Notices()
		val shade = Shade()
		val notices = controller(disk, shade) { alice }
		notices.load()
		seed(notices)

		disk.writable = false
		notices.record(alice, root, listOf(reply(bob, "r1", alice)))

		assertEquals(
			"a notification is a promise the record behind it survives",
			emptyList<Pair<String, String>>(),
			shade.posted,
		)
	}

	@Test
	fun `a failed write claims nothing durable and shows nothing`() {
		val disk = Notices()
		val notices = controller(disk, Shade()) { alice }
		notices.load()
		seed(notices)

		disk.writable = false
		notices.record(alice, root, listOf(reply(bob, "r1", alice)))

		assertEquals(
			"a bell row the disk does not have would be answered, lost on " +
				"restart, and announced again",
			emptyList<SnapNotice>(),
			notices.unread(),
		)
		assertFalse(
			"and nothing may be on file claiming this reply was recorded",
			SnapNoticeCodec.noticeKey(alice, "bob/r1") in disk.saved,
		)
	}

	@Test
	fun `a write that fails once succeeds on the next ordinary read`() {
		val disk = Notices()
		val shade = Shade()
		val notices = controller(disk, shade) { alice }
		notices.load()
		seed(notices)
		disk.writable = false
		notices.record(alice, root, listOf(reply(bob, "r1", alice)))
		assertEquals(emptyList<Pair<String, String>>(), shade.posted)

		// Nothing retried it; the conversation was simply read again.
		disk.writable = true
		notices.record(alice, root, listOf(reply(bob, "r1", alice)))

		assertEquals(listOf(alice to "bob/r1"), shade.posted)
		assertEquals(listOf("bob/r1"), notices.unread().map { it.contentId })
	}

	@Test
	fun `a failed seeding write does not mark the conversation seeded`() {
		val disk = Notices()
		val shade = Shade()
		val notices = controller(disk, shade) { alice }
		notices.load()

		disk.writable = false
		notices.record(alice, root, listOf(reply(bob, "old", alice)))
		assertFalse(
			"a seed mark that was never written must not be believed",
			disk.saved.containsKey(SnapNoticeCodec.seedKey(alice, root.contentId)),
		)

		// The retry seeds properly, which means the old reply is still history.
		disk.writable = true
		notices.record(alice, root, listOf(reply(bob, "old", alice)))

		assertEquals(
			"a failed first read must not turn old replies into notifications",
			emptyList<Pair<String, String>>(),
			shade.posted,
		)
	}

	private fun notice(permlink: String, read: Boolean) = SnapNotice(
		author = bob,
		permlink = permlink,
		rootAuthor = root.author,
		rootPermlink = root.permlink,
		text = "hi",
		createdAtEpochSec = 1_000L,
		read = read,
	)
}

/** The clamp the notice text is held to, named once for the assertions above. */
private object SnapThreadPreviewChars {
	const val LIMIT = com.rustedwax.app.snaps.SnapThreadPreview.MAX_PREVIEW_CHARS
}
