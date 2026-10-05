package com.rustedwax.app.ui.snaps

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rustedwax.app.storage.db.DatabaseQuarantine
import com.rustedwax.app.storage.db.LocalDatabase
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #47 Stage 47A: the My Snaps catalog against real SQLite — one row per
 * exact identity, known media context never erased, newest-first keyset pages,
 * and one account's rows invisible to another. Every test uses its own
 * database file.
 */
@RunWith(AndroidJUnit4::class)
class MySnapRowsDeviceTest {

	private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private lateinit var name: String
	private lateinit var database: LocalDatabase
	private lateinit var rows: SqliteMySnapRows

	@Before
	fun setUp() {
		name = "rustedwax-mysnaps-${UUID.randomUUID()}.db"
		database = LocalDatabase(context, name)
		rows = SqliteMySnapRows(database)
	}

	@After
	fun tearDown() {
		runCatching { database.close() }
		val file = context.getDatabasePath(name)
		DatabaseQuarantine.existing(file).forEach { it.deleteRecursively() }
		(listOf("") + DatabaseQuarantine.COMPANION_SUFFIXES).forEach { File(file.path + it).delete() }
	}

	private fun row(
		permlink: String,
		owner: String = "alice",
		created: Long = 1L,
		title: String? = null,
		artist: String? = null,
		text: String? = "words",
	) = MySnapRow(owner, owner, permlink, created, "e-$permlink", "dQw4w9WgXcQ", title, artist, null, text)

	@Test
	fun upsertIsOneRowAndNeverErasesKnownContext() {
		rows.upsert(listOf(row("p", title = "Song", artist = "Band")), 10L)
		rows.upsert(listOf(row("p", text = "edited")), 20L)
		val stored = rows.page("alice", 10, null).single()
		assertEquals("Song", stored.title)
		assertEquals("Band", stored.artist)
		assertEquals("edited", stored.userText)
		rows.upsert(listOf(row("p", text = null)), 30L)
		assertEquals("edited", rows.page("alice", 10, null).single().userText)
	}

	@Test
	fun pagesAreNewestFirstAndContinueWithoutGapsOrRepeats() {
		rows.upsert((1..7).map { row("p$it", created = (it / 2).toLong()) }, 1L)
		val seen = mutableListOf<MySnapRow>()
		var after: MySnapRow? = null
		do {
			val page = rows.page("alice", 3, after)
			seen += page
			after = page.lastOrNull()
		} while (page.size == 3)
		assertEquals(7, seen.size)
		assertEquals(seen.map { it.permlink }.toSet().size, 7)
		assertEquals(
			seen.sortedWith(compareByDescending<MySnapRow> { it.createdAtEpochSec }.thenByDescending { it.permlink }),
			seen,
		)
	}

	@Test
	fun accountsAreIsolatedAndTombstonesAreExact() {
		rows.upsert(listOf(row("p", owner = "alice"), row("q", owner = "alice"), row("p", owner = "bob")), 1L)
		assertEquals(listOf("q", "p"), rows.page("alice", 10, null).map { it.permlink })
		assertEquals(listOf("p"), rows.page("bob", 10, null).map { it.permlink })
		rows.tombstone("alice", "alice", "p", "tx", 5L)
		assertEquals(listOf("q"), rows.page("alice", 10, null).map { it.permlink })
		assertEquals("bob's row of the same permlink is untouched", 1, rows.page("bob", 10, null).size)
	}

	@Test
	fun aTombstonedIdentityIsNeverUpsertedOrListedAgainEvenAfterReopening() {
		rows.upsert(listOf(row("p"), row("q")), 1L)
		rows.tombstone("alice", "alice", "p", "tx", 5L)
		rows.tombstone("alice", "alice", "p", null, 6L) // idempotent
		rows.upsert(listOf(row("p", title = "back?")), 7L)
		assertEquals(listOf("q"), rows.page("alice", 10, null).map { it.permlink })
		database.close()
		database = LocalDatabase(context, name)
		rows = SqliteMySnapRows(database)
		rows.upsert(listOf(row("p")), 8L)
		assertEquals(listOf("q"), rows.page("alice", 10, null).map { it.permlink })
		database.read { db ->
			db.rawQuery("SELECT tx_id, deleted_at FROM my_snap_tombstone", null).use {
				assertTrue(it.moveToFirst())
				assertEquals("tx", it.getString(0))
				assertEquals(5L, it.getLong(1))
				assertFalse(it.moveToNext())
			}
		}
	}

	@Test
	fun aTombstoneAndItsRemovalLandTogetherOrNotAtAll() {
		rows.upsert(listOf(row("p")), 1L)
		database.write { it.execSQL("CREATE TEMP TRIGGER refuse BEFORE DELETE ON my_snap BEGIN SELECT RAISE(ABORT, 'disk'); END") }
		try {
			rows.tombstone("alice", "alice", "p", "tx", 5L)
			fail("the transaction should have failed")
		} catch (expected: SQLiteException) {
		}
		database.write { it.execSQL("DROP TRIGGER refuse") }
		assertEquals(listOf("p"), rows.page("alice", 10, null).map { it.permlink })
		database.read { db ->
			assertEquals(0, db.rawQuery("SELECT count(*) FROM my_snap_tombstone", null).use { it.moveToFirst(); it.getInt(0) })
		}
	}

	@Test
	fun discoveryKeepsLocalContextAndCreationTimeAndStoresItsCheckpointWithTheRows() {
		rows.upsert(listOf(row("p", created = 10L, title = "Song", artist = "Band", text = "local")), 1L)
		rows.tombstone("alice", "alice", "dead", null, 1L)
		val found = listOf(
			MySnapRow("alice", "alice", "p", 99L, null, "dQw4w9WgXcQ", null, null, null, "chain"),
			MySnapRow("alice", "alice", "new", 50L, null, "dQw4w9WgXcQ", null, null, null, "other device"),
			MySnapRow("alice", "alice", "dead", 60L, null, "dQw4w9WgXcQ", null, null, null, "deleted"),
		)
		rows.applyDiscovery("alice", found, "{\"v\":1}", 5L)
		val listed = rows.page("alice", 10, null)
		assertEquals(listOf("new", "p"), listed.map { it.permlink })
		val known = listed.single { it.permlink == "p" }
		assertEquals(10L, known.createdAtEpochSec)
		assertEquals("Song", known.title)
		assertEquals("Band", known.artist)
		assertEquals("e-p", known.eventId)
		assertEquals("chain", known.userText)
		assertEquals("{\"v\":1}", rows.checkpoint("alice"))
		assertNull(rows.checkpoint("bob"))
		assertEquals(listOf("new"), rows.createdBetween("alice", 40L, 60L).map { it.permlink })
	}

	@Test
	fun aFailedDiscoveryTransactionLeavesNeitherRowsNorCheckpoint() {
		database.write { it.execSQL("CREATE TEMP TRIGGER refuse BEFORE INSERT ON sync_checkpoint BEGIN SELECT RAISE(ABORT, 'disk'); END") }
		try {
			rows.applyDiscovery("alice", listOf(row("p")), "{}", 5L)
			fail("the transaction should have failed")
		} catch (expected: SQLiteException) {
		}
		database.write { it.execSQL("DROP TRIGGER refuse") }
		assertTrue(rows.page("alice", 10, null).isEmpty())
		assertNull(rows.checkpoint("alice"))
	}

	@Test
	fun rowsSurviveReopeningTheDatabase() {
		rows.upsert(listOf(row("p", title = "Song")), 1L)
		database.close()
		database = LocalDatabase(context, name)
		val reopened = SqliteMySnapRows(database).page("alice", 10, null).single()
		assertEquals("Song", reopened.title)
		assertNull(reopened.service)
	}
}
