package com.rustedwax.app.ui.snaps

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rustedwax.app.storage.db.DatabaseQuarantine
import com.rustedwax.app.storage.db.LocalDatabase
import com.rustedwax.app.storage.db.LocalDatabaseSchema
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #41 Slice 4: notice state in real SQLite — a seen reply is stored once
 * and never removed, display rows come and go, seeds and checkpoints are
 * owner-scoped. Each test uses its own database file and touches no
 * SharedPreferences.
 */
@RunWith(AndroidJUnit4::class)
class SnapNoticeTablesDeviceTest {

	private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private lateinit var name: String
	private lateinit var database: LocalDatabase

	@Before
	fun setUp() {
		name = "rustedwax-slice4-${UUID.randomUUID()}.db"
		database = LocalDatabase(context, name)
	}

	@After
	fun tearDown() {
		runCatching { database.close() }
		val file = context.getDatabasePath(name)
		DatabaseQuarantine.existing(file).forEach { it.deleteRecursively() }
		(listOf("") + DatabaseQuarantine.COMPANION_SUFFIXES).forEach { File(file.path + it).delete() }
	}

	private fun notice(permlink: String, read: Boolean = false) =
		SnapNotice("bob", permlink, "alice", "rustedwax-snap-1-aaaaaa", "hello", 1_500L, read)

	private fun count(table: String) = database.read { db ->
		db.rawQuery("SELECT count(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
	}

	@Test
	fun seenOnceDisplayedAndPrunedWithoutForgetting() {
		val rows = SqliteNoticeRows(database)
		rows.transaction {
			it.put("n|alice|bob/re-1", NoticeEntry.Shown(notice("re-1"), 100L))
			it.put("n|alice|bob/re-2", NoticeEntry.Seen)
			it.put("s|alice|alice/rustedwax-snap-1-aaaaaa", NoticeEntry.Seed(7L))
		}
		assertEquals(
			mapOf(
				"n|alice|bob/re-1" to NoticeEntry.Shown(notice("re-1"), 100L),
				"n|alice|bob/re-2" to NoticeEntry.Seen,
				"s|alice|alice/rustedwax-snap-1-aaaaaa" to NoticeEntry.Seed(7L),
			),
			rows.transaction { it.all() },
		)

		// Read, then pruned, then even deleted: the reply stays seen.
		rows.transaction { it.put("n|alice|bob/re-1", NoticeEntry.Shown(notice("re-1", read = true), 100L)) }
		rows.transaction { it.put("n|alice|bob/re-1", NoticeEntry.Seen) }
		rows.transaction { it.delete("n|alice|bob/re-2") }
		assertEquals(2, count(LocalDatabaseSchema.SEEN_REPLY))
		assertEquals(0, count(LocalDatabaseSchema.REPLY_NOTICE))
		assertEquals(NoticeEntry.Seen, rows.transaction { it.all() }["n|alice|bob/re-1"])
	}

	@Test
	fun ownersAreSeparateAndTheSignedOutOwnerIsRefused() {
		val rows = SqliteNoticeRows(database)
		rows.transaction {
			it.put("n|alice|bob/re-1", NoticeEntry.Shown(notice("re-1"), 1L))
			it.put("n|carol|bob/re-1", NoticeEntry.Seen)
		}
		assertEquals(setOf("n|alice|bob/re-1", "n|carol|bob/re-1"), rows.transaction { it.all() }.keys)
		assertEquals(false, SnapNoticeLocalCodec.storable("n|-|bob/re-1"))
		assertEquals(false, SnapNoticeLocalCodec.storable("n|Alice|bob/re-1"))
	}

	@Test
	fun checkpointsAreAStoragePrimitivePerOwnerAndScope() {
		val checkpoints = SqliteSyncCheckpoints(database)
		checkpoints.put("alice", "replies", "cursor-1", 10L)
		checkpoints.put("bob", "replies", "cursor-9", 11L)
		checkpoints.put("-", "version", "65", 12L)
		checkpoints.put("alice", "replies", "cursor-2", 13L)

		assertEquals(SyncCheckpoints.Checkpoint("cursor-2", 13L), checkpoints.get("alice", "replies"))
		assertEquals(SyncCheckpoints.Checkpoint("cursor-9", 11L), checkpoints.get("bob", "replies"))
		assertEquals(SyncCheckpoints.Checkpoint("65", 12L), checkpoints.get("-", "version"))
		assertNull(checkpoints.get("alice", "version"))

		checkpoints.delete("alice", "replies")
		assertNull(checkpoints.get("alice", "replies"))
		assertEquals(SyncCheckpoints.Checkpoint("cursor-9", 11L), checkpoints.get("bob", "replies"))
	}
}
