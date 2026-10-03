package com.rustedwax.app.ui.snaps

import com.rustedwax.app.scrobble.InMemorySharedPreferences
import com.rustedwax.app.snaps.SnapReply
import com.rustedwax.app.snaps.SnapReplyTarget
import com.rustedwax.app.storage.MirrorRows
import com.rustedwax.app.storage.MirrorTx
import com.rustedwax.app.storage.db.LegacyImportRecord
import com.rustedwax.app.storage.db.LegacyImportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Issue #41 Slice 4: the bell's durable state — seen replies, displayed
 * notices and their read flags, seed marks — through the real legacy notice
 * file (in memory, seeded as today's store writes it) and an in-memory
 * version of the three tables with SQLite's semantics.
 */
class SnapNoticeLocalTest {

	/**
	 * `seen_reply`, `reply_notice` and `thread_seed` in memory, with the SQL
	 * adapter's rules: a seen row is inserted once and never deleted; display
	 * rows are upserted and deleted; transactions roll back on any exception.
	 */
	private class Tables : MirrorRows<NoticeEntry> {
		var seen = LinkedHashMap<String, Boolean>()
		var shown = LinkedHashMap<String, NoticeEntry.Shown>()
		var seeds = LinkedHashMap<String, Long>()
		var record: LegacyImportRecord? = null
		var failNext = false

		@Synchronized
		override fun <T> transaction(block: (MirrorTx<NoticeEntry>) -> T): T {
			val saved = listOf(LinkedHashMap(seen), LinkedHashMap(shown), LinkedHashMap(seeds))
			val savedRecord = record
			val failing = failNext.also { failNext = false }
			try {
				return block(object : MirrorTx<NoticeEntry> {
					override fun all(): Map<String, NoticeEntry> =
						seen.keys.associateWith { shown[it] ?: NoticeEntry.Seen } +
							seeds.mapValues { NoticeEntry.Seed(it.value) }

					override fun put(key: String, value: NoticeEntry) {
						if (failing) throw IllegalStateException("simulated disk failure")
						when (value) {
							is NoticeEntry.Seed -> seeds[key] = value.atEpochSec
							NoticeEntry.Seen -> { seen.putIfAbsent(key, false); shown.remove(key) }
							is NoticeEntry.Shown -> { seen.putIfAbsent(key, value.notice.read); shown[key] = value }
						}
					}

					override fun delete(key: String) {
						if (key.startsWith("s|")) seeds.remove(key) else shown.remove(key)
					}

					override fun importRecord() = record
					override fun putImportRecord(record: LegacyImportRecord) { this@Tables.record = record }
				})
			} catch (failure: Throwable) {
				@Suppress("UNCHECKED_CAST")
				seen = saved[0] as LinkedHashMap<String, Boolean>
				@Suppress("UNCHECKED_CAST")
				shown = saved[1] as LinkedHashMap<String, NoticeEntry.Shown>
				@Suppress("UNCHECKED_CAST")
				seeds = saved[2] as LinkedHashMap<String, Long>
				record = savedRecord
				throw failure
			}
		}
	}

	private class Shade : SnapNoticeNotifier {
		val posted = mutableListOf<String>()
		override fun post(account: String, notice: SnapNotice) { posted += "$account:${notice.contentId}" }
		override fun cancel(account: String, contentId: String) = Unit
	}

	private val inline = Executor { it.run() }
	private val alice = "alice"
	private val bob = "bob"
	private val root = SnapReplyTarget.of(alice, "rustedwax-snap-1000-aaaaaa")!!

	private fun notice(author: String, permlink: String, read: Boolean = false, rootAuthor: String = alice) =
		SnapNotice(author, permlink, rootAuthor, root.permlink, "text by $author", 1_500L, read)

	/** Legacy entries exactly as `SharedPreferencesSnapNoticeStore` writes them. */
	private fun legacyNotice(prefs: InMemorySharedPreferences, owner: String, n: SnapNotice, at: Long = 100L) =
		prefs.edit().putString(SnapNoticeCodec.noticeKey(owner, n.contentId), SnapNoticeCodec.encode(n, at)).commit()

	private fun legacySeen(prefs: InMemorySharedPreferences, owner: String, contentId: String) =
		prefs.edit().putString(SnapNoticeCodec.noticeKey(owner, contentId), SnapNoticeCodec.TOMBSTONE).commit()

	private fun legacySeed(prefs: InMemorySharedPreferences, owner: String, rootId: String, at: Long) =
		prefs.edit().putLong(SnapNoticeCodec.seedKey(owner, rootId), at).commit()

	/** One "process": the store as `SnapNoticeLocal` builds it, adopting [tables] — or failing to. */
	private fun boot(prefs: InMemorySharedPreferences, tables: Tables?, writer: Executor = inline) =
		MirroredSnapNoticeStore(
			SnapNoticeLocal.mirror(prefs, { tables ?: throw IllegalStateException("database unavailable") }, writer),
		)

	private fun reply(author: String, permlink: String, parent: String = alice) =
		SnapReply(author, permlink, parent, root.permlink, "hi from $author", 1_000L)

	// ---- migration ----------------------------------------------------------------------

	@Test
	fun `a full notice becomes seen and displayed, a placeholder seen only, a seed mark a seed`() {
		val prefs = InMemorySharedPreferences()
		legacyNotice(prefs, alice, notice(bob, "re-1"), at = 111L)
		legacyNotice(prefs, alice, notice(bob, "re-2", read = true), at = 112L)
		legacySeen(prefs, alice, "bob/re-0")
		legacySeed(prefs, alice, root.contentId, 99L)
		val tables = Tables()

		val store = boot(prefs, tables)

		assertEquals(setOf("n|alice|bob/re-1", "n|alice|bob/re-2", "n|alice|bob/re-0"), tables.seen.keys)
		assertEquals(setOf("n|alice|bob/re-1", "n|alice|bob/re-2"), tables.shown.keys)
		assertEquals(NoticeEntry.Shown(notice(bob, "re-1"), 111L), tables.shown["n|alice|bob/re-1"])
		assertEquals(mapOf("s|alice|${root.contentId}" to 99L), tables.seeds)
		assertEquals(setOf("bob/re-1", "bob/re-2"), store.all(alice).map { it.contentId }.toSet())
		assertEquals(listOf("bob/re-1"), store.all(alice).filterNot { it.read }.map { it.contentId })
		assertEquals(LegacyImportState.VERIFIED, tables.record!!.state)
	}

	@Test
	fun `migration is repeatable, reopens restore once, and malformed entries stay behind`() {
		val prefs = InMemorySharedPreferences()
		legacyNotice(prefs, alice, notice(bob, "re-1"))
		prefs.edit().putString("n|alice|bob/re-bad", "{not json").putString("n|ALICE|bob/re-x", "-").commit()
		val tables = Tables()

		repeat(3) { boot(prefs, tables) }

		assertEquals(setOf("n|alice|bob/re-1"), tables.seen.keys)
		assertEquals(2, tables.record!!.rejected)
		assertEquals("{not json", prefs.getString("n|alice|bob/re-bad", null))
		assertEquals(1, boot(prefs, tables).all(alice).size)
	}

	// ---- seen versus displayed -------------------------------------------------------------

	@Test
	fun `a reply already seen — displayed, read or pruned — is never new again`() {
		val prefs = InMemorySharedPreferences()
		legacySeed(prefs, alice, root.contentId, 1L)
		legacyNotice(prefs, alice, notice(bob, "re-shown"))
		legacyNotice(prefs, alice, notice(bob, "re-read", read = true))
		legacySeen(prefs, alice, "bob/re-pruned")
		val tables = Tables()
		val store = boot(prefs, tables)

		val fresh = store.record(
			alice, root.contentId,
			listOf(notice(bob, "re-shown"), notice(bob, "re-read"), notice(bob, "re-pruned"), notice(bob, "re-new")),
			2_000L,
		)

		assertEquals(listOf("bob/re-new"), fresh!!.map { it.contentId })
		// Reopened, and migrated again from the shadow: still not new.
		val again = boot(prefs, tables).record(alice, root.contentId, listOf(notice(bob, "re-pruned"), notice(bob, "re-new")), 3_000L)
		assertEquals(emptyList<SnapNotice>(), again)
	}

	@Test
	fun `pruning drops display data and keeps every seen identity`() {
		val prefs = InMemorySharedPreferences()
		val tables = Tables()
		val store = boot(prefs, tables)
		store.record(alice, root.contentId, emptyList(), 1L)
		val max = SharedPreferencesSnapNoticeStore.MAX_DISPLAYED_NOTICES
		(1..max + 5).forEach { store.record(alice, root.contentId, listOf(notice(bob, "re-$it")), 10L + it) }

		assertEquals(max + 5, tables.seen.size)
		assertEquals(max, tables.shown.size)
		assertEquals(max, store.all(alice).size)
		// The pruned ones stay old news, across a reopen too.
		val reopened = boot(prefs, tables)
		assertEquals(emptyList<SnapNotice>(), reopened.record(alice, root.contentId, listOf(notice(bob, "re-1")), 9_999L))
		assertEquals(max + 5, tables.seen.size)
	}

	@Test
	fun `read flags persist, per reply and per thread`() {
		val prefs = InMemorySharedPreferences()
		val tables = Tables()
		val store = boot(prefs, tables)
		store.record(alice, root.contentId, emptyList(), 1L)
		store.record(alice, root.contentId, listOf(notice(bob, "re-1"), notice(bob, "re-2")), 2L)
		store.record(alice, "alice/other-root", emptyList(), 3L)

		store.markRead(alice, "bob/re-1")
		val reopened = boot(prefs, tables)
		assertEquals(listOf("bob/re-2"), reopened.all(alice).filterNot { it.read }.map { it.contentId })

		reopened.markThreadRead(alice, root.contentId)
		assertTrue(boot(prefs, tables).all(alice).all { it.read })
		assertEquals(setOf(true), tables.shown.values.map { it.notice.read }.toSet())
	}

	// ---- isolation ------------------------------------------------------------------------

	@Test
	fun `accounts never see or change each other's notices, and Forget Key deletes nothing`() {
		val prefs = InMemorySharedPreferences()
		val tables = Tables()
		val store = boot(prefs, tables)
		store.record(alice, root.contentId, emptyList(), 1L)
		store.record(alice, root.contentId, listOf(notice(bob, "re-a")), 2L)
		store.record(bob, root.contentId, emptyList(), 1L)
		store.record(bob, root.contentId, listOf(notice("carol", "re-b")), 2L)

		store.markThreadRead(bob, root.contentId)

		assertEquals(listOf(false), store.all(alice).map { it.read })
		assertEquals(listOf("bob/re-a"), store.all(alice).map { it.contentId })
		assertEquals(listOf("carol/re-b"), store.all(bob).map { it.contentId })
		// Signing out and back in is just reading the same account again.
		assertEquals(listOf("bob/re-a"), boot(prefs, tables).all(alice).map { it.contentId })
	}

	// ---- authority, durability and fallback ------------------------------------------------

	@Test
	fun `a read during startup is not rolled back when the database is adopted`() {
		val prefs = InMemorySharedPreferences()
		legacySeed(prefs, alice, root.contentId, 1L)
		legacyNotice(prefs, alice, notice(bob, "re-1"))
		val tables = Tables()
		boot(prefs, tables)
		val held = ArrayDeque<Runnable>()

		val store = boot(prefs, tables, writer = { held.addLast(it) })
		store.markRead(alice, "bob/re-1")
		while (held.isNotEmpty()) held.removeFirst().run()

		assertTrue(store.all(alice).single().read)
		assertTrue(tables.shown.values.single().notice.read)
	}

	@Test
	fun `a database write that fails records nothing and announces nothing`() {
		val prefs = InMemorySharedPreferences()
		val tables = Tables()
		val store = boot(prefs, tables)
		store.record(alice, root.contentId, emptyList(), 1L)
		tables.failNext = true

		assertNull(store.record(alice, root.contentId, listOf(notice(bob, "re-1")), 2L))
		assertTrue(store.all(alice).isEmpty())
		assertTrue(tables.seen.isEmpty())
		// The next ordinary read tries again, and it is new then.
		assertEquals(listOf("bob/re-1"), store.record(alice, root.contentId, listOf(notice(bob, "re-1")), 3L)!!.map { it.contentId })
	}

	@Test
	fun `a database that cannot open leaves the legacy file in charge, and its changes win next time`() {
		val prefs = InMemorySharedPreferences()
		legacySeed(prefs, alice, root.contentId, 1L)
		legacyNotice(prefs, alice, notice(bob, "re-1"))
		val tables = Tables()
		boot(prefs, tables)

		val fallback = boot(prefs, tables = null)
		assertEquals(listOf("bob/re-2"), fallback.record(alice, root.contentId, listOf(notice(bob, "re-2")), 5L)!!.map { it.contentId })
		fallback.markRead(alice, "bob/re-1")
		assertTrue(prefs.getString("n|alice|bob/re-2", null)!!.startsWith("{"))

		val next = boot(prefs, tables)
		assertEquals(setOf("bob/re-1", "bob/re-2"), next.all(alice).map { it.contentId }.toSet())
		assertTrue(next.all(alice).single { it.contentId == "bob/re-1" }.read)
		assertEquals(setOf("n|alice|bob/re-1", "n|alice|bob/re-2"), tables.seen.keys)
	}

	// ---- delivery -------------------------------------------------------------------------

	@Test
	fun `migration and restore announce nothing, only a genuinely new reply is posted`() {
		val prefs = InMemorySharedPreferences()
		legacySeed(prefs, alice, root.contentId, 1L)
		legacyNotice(prefs, alice, notice(bob, "re-old"))
		legacySeen(prefs, alice, "bob/re-pruned")
		val shade = Shade()
		val controller = SnapNoticeController(boot(prefs, Tables()), { alice }, shade, nowEpochSec = { 5_000L })

		controller.load()
		controller.record(alice, root, listOf(reply(bob, "re-old"), reply(bob, "re-pruned")))
		assertEquals(emptyList<String>(), shade.posted)
		assertEquals(listOf("bob/re-old"), controller.unread().map { it.contentId })

		controller.record(alice, root, listOf(reply(bob, "re-new")))
		assertEquals(listOf("alice:bob/re-new"), shade.posted)
	}
	@Test
	fun `a provisional shadow cannot announce a reply already seen only in the database`() {
		val prefs = InMemorySharedPreferences()
		val tables = Tables()
		val store = boot(prefs, tables)
		store.record(alice, root.contentId, emptyList(), 1L)
		store.record(alice, root.contentId, listOf(notice(bob, "re-old")), 2L)
		// The DB committed, but this shadow entry did not survive process death.
		prefs.edit().remove("n|alice|bob/re-old").commit()
		val held = ArrayDeque<Runnable>()
		var holding = true
		val reopening = boot(prefs, tables, writer = { if (holding) held.addLast(it) else it.run() })

		assertNull(reopening.record(alice, root.contentId, listOf(notice(bob, "re-old")), 3L))
		holding = false
		while (held.isNotEmpty()) held.removeFirst().run()
		assertEquals(emptyList<SnapNotice>(), reopening.record(alice, root.contentId, listOf(notice(bob, "re-old")), 4L))
		assertEquals(listOf("bob/re-new"), reopening.record(alice, root.contentId, listOf(notice(bob, "re-new")), 5L)!!.map { it.contentId })
	}

}
