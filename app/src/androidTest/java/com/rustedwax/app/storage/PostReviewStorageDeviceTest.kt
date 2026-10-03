package com.rustedwax.app.storage

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rustedwax.app.storage.db.DatabaseQuarantine
import com.rustedwax.app.storage.db.LocalDatabase
import com.rustedwax.app.storage.db.LocalDatabaseSchema
import com.rustedwax.app.ui.snaps.MirroredSnapNoticeStore
import com.rustedwax.app.ui.snaps.NoticeEntry
import com.rustedwax.app.ui.snaps.SnapLocalCodec
import com.rustedwax.app.ui.snaps.SnapLocalState
import com.rustedwax.app.ui.snaps.SnapNotice
import com.rustedwax.app.ui.snaps.SnapNoticeCodec
import com.rustedwax.app.ui.snaps.SnapNoticeLocal
import com.rustedwax.app.ui.snaps.SqliteNoticeRows
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #41 post-review: the three corrected paths against real SQLite and real
 * SharedPreferences files — every file uniquely named for this run and removed
 * afterwards, so no app data is read or written.
 *
 *  1. a background write the database refused is carried into the next one;
 *  2. a malformed entry in a newer legacy file never deletes a valid row;
 *  3. notice recording waits for the database before it can announce.
 */
@RunWith(AndroidJUnit4::class)
class PostReviewStorageDeviceTest {

	private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private lateinit var run: String
	private lateinit var database: LocalDatabase

	@Before
	fun setUp() {
		run = UUID.randomUUID().toString().take(8)
		database = LocalDatabase(context, "rustedwax-postreview-$run.db")
	}

	@After
	fun tearDown() {
		runCatching { database.close() }
		val file = context.getDatabasePath("rustedwax-postreview-$run.db")
		DatabaseQuarantine.existing(file).forEach { it.deleteRecursively() }
		(listOf("") + DatabaseQuarantine.COMPANION_SUFFIXES).forEach { File(file.path + it).delete() }
		listOf("drafts", "notices").forEach { context.deleteSharedPreferences(prefsName(it)) }
	}

	private fun prefsName(kind: String) = "rustedwax-postreview-$kind-$run"

	private fun prefs(kind: String) = context.getSharedPreferences(prefsName(kind), Context.MODE_PRIVATE)

	private val inline = Executor { it.run() }

	/** Real SQLite rows that refuse the next [failNext] transactions, as a full disk would. */
	private class Flaky<V>(private val inner: MirrorRows<V>) : MirrorRows<V> {
		var failNext = 0
		override fun <T> transaction(block: (MirrorTx<V>) -> T): T {
			if (failNext > 0) {
				failNext--
				throw android.database.sqlite.SQLiteFullException("simulated full disk")
			}
			return inner.transaction(block)
		}
	}

	private fun draftRows() = SqliteMirrorRows(database, SnapLocalState.SOURCE_DRAFTS, SnapLocalCodec.DRAFT_TABLE)

	private fun draftMirror(rows: MirrorRows<String>) =
		LocalMirror(SnapLocalState.SOURCE_DRAFTS, SnapLocalCodec.draftSource(prefs("drafts")), com.rustedwax.app.ui.snaps.SnapLocalKeys::draftStorable, writer = inline)
			.also { it.start { rows } }

	// ---- 1. failed background writes ---------------------------------------------------

	@Test
	fun aRefusedEditAndDeleteAreCarriedIntoTheNextSuccessfulWrite() {
		prefs("drafts").edit().putString("alice|e-del", "to be deleted").commit()
		val rows = Flaky(draftRows())
		val mirror = draftMirror(rows)

		rows.failNext = 2
		mirror.put("alice|e-edit", "edited while the disk was full")
		mirror.remove("alice|e-del")
		assertEquals("refused writes are not in the database yet", mapOf("alice|e-del" to "to be deleted"), draftRows().transaction { it.all() })

		mirror.put("alice|e-next", "next write")
		assertEquals(
			mapOf("alice|e-edit" to "edited while the disk was full", "alice|e-next" to "next write"),
			draftRows().transaction { it.all() },
		)

		// A fresh process over the same files: nothing lost, nothing resurrected.
		val reopened = draftMirror(draftRows())
		assertEquals(
			mapOf("alice|e-edit" to "edited while the disk was full", "alice|e-next" to "next write"),
			reopened.entries(),
		)
		assertNull(prefs("drafts").getString("alice|e-del", null))
	}

	// ---- 2. malformed entries in a newer legacy file -----------------------------------

	@Test
	fun aMalformedNewerEntryNeverDeletesAValidRow() {
		prefs("drafts").edit().putString("alice|keep", "valid").putString("alice|gone", "deleted later").commit()
		draftMirror(draftRows())
		assertEquals(setOf("alice|keep", "alice|gone"), draftRows().transaction { it.all() }.keys)

		// A run that could not open the database: it damaged one entry, removed
		// another on purpose, added a third, and advanced the generation.
		val legacy = SnapLocalCodec.draftSource(prefs("drafts"))
		prefs("drafts").edit()
			.putLong("alice|keep", 42L)
			.remove("alice|gone")
			.putString("alice|new", "added in fallback")
			.putString("#rustedwax-generation", (legacy.generation() + 5).toString())
			.commit()

		val next = draftMirror(draftRows())

		assertEquals(
			mapOf("alice|keep" to "valid", "alice|new" to "added in fallback"),
			draftRows().transaction { it.all() },
		)
		assertEquals("valid", next.get("alice|keep"))
		assertEquals("the malformed bytes stay where they were", 42L, prefs("drafts").all["alice|keep"])
	}

	// ---- 3. notices before and after the database is adopted ---------------------------

	private class Gate : Executor {
		private val held = ArrayDeque<Runnable>()
		private var open = false
		@Synchronized override fun execute(task: Runnable) { if (open) task.run() else held.addLast(task) }
		fun release() {
			synchronized(this) { open = true }
			while (true) (synchronized(this) { held.removeFirstOrNull() } ?: break).run()
		}
	}

	private fun notice(permlink: String) =
		SnapNotice("bob", permlink, "alice", "rustedwax-snap-1-aaaaaa", "hi", 1_500L, read = false)

	@Test
	fun noticesAreNeverAnnouncedBeforeTheDatabaseAnswers() {
		val root = "alice/rustedwax-snap-1-aaaaaa"
		prefs("notices").edit()
			.putLong(SnapNoticeCodec.seedKey("alice", root), 1L)
			.putString(SnapNoticeCodec.noticeKey("alice", "bob/re-old"), SnapNoticeCodec.encode(notice("re-old").copy(read = true), 10L))
			.commit()
		val rows = Flaky(SqliteNoticeRows(database))
		val gate = Gate()
		val store = MirroredSnapNoticeStore(SnapNoticeLocal.mirror(prefs("notices"), { rows }, gate))

		// Startup window: nothing is decided, nothing is announced, nothing is written.
		assertNull(store.record("alice", root, listOf(notice("re-old"), notice("re-new")), 20L))
		gate.release()

		// Authoritative: the old reply is not news; the new one is, exactly once.
		assertEquals(listOf("bob/re-new"), store.record("alice", root, listOf(notice("re-old"), notice("re-new")), 30L)!!.map { it.contentId })
		assertEquals(emptyList<SnapNotice>(), store.record("alice", root, listOf(notice("re-old"), notice("re-new")), 31L))

		// A first read of another conversation seeds it and announces nothing.
		assertEquals(emptyList<SnapNotice>(), store.record("alice", "alice/other", listOf(notice("re-x").copy(rootPermlink = "other")), 32L))

		// A durable write the database refuses records nothing and announces nothing.
		rows.failNext = 1
		assertNull(store.record("alice", root, listOf(notice("re-later")), 40L))
		assertEquals(listOf("bob/re-later"), store.record("alice", root, listOf(notice("re-later")), 41L)!!.map { it.contentId })

		// Read, then pruned to seen-only: the seen row stays.
		store.markRead("alice", "bob/re-new")
		SqliteNoticeRows(database).transaction { it.put(SnapNoticeCodec.noticeKey("alice", "bob/re-new"), NoticeEntry.Seen) }
		val count = { table: String -> database.read { db -> db.rawQuery("SELECT count(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) } } }
		assertEquals(4, count(LocalDatabaseSchema.SEEN_REPLY))
		assertEquals(3, count(LocalDatabaseSchema.REPLY_NOTICE))

		// A fresh process: no reply already on file is ever new again.
		val reopened = MirroredSnapNoticeStore(SnapNoticeLocal.mirror(prefs("notices"), { SqliteNoticeRows(database) }, inline))
		assertEquals(
			emptyList<SnapNotice>(),
			reopened.record("alice", root, listOf(notice("re-old"), notice("re-new"), notice("re-later")), 50L),
		)
		assertTrue(reopened.all("alice").none { it.contentId == "bob/re-new" && !it.read })
	}
}
