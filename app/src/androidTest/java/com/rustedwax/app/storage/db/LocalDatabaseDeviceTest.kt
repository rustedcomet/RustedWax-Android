package com.rustedwax.app.storage.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real platform SQLite behind [LocalDatabase].
 *
 * Every test opens its **own** uniquely named database file, never
 * `rustedwax.db`, and removes only the files it created — so running this
 * suite cannot touch the app's real database or any legacy store.
 */
@RunWith(AndroidJUnit4::class)
class LocalDatabaseDeviceTest {

	private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private lateinit var name: String
	private lateinit var file: File
	private val opened = mutableListOf<LocalDatabase>()

	@Before
	fun setUp() {
		name = "rustedwax-devtest-${UUID.randomUUID()}.db"
		file = context.getDatabasePath(name)
	}

	@After
	fun tearDown() {
		opened.forEach { runCatching { it.close() } }
		// Only this test's own files and quarantine directories.
		DatabaseQuarantine.existing(file).forEach { it.deleteRecursively() }
		(listOf("") + DatabaseQuarantine.COMPANION_SUFFIXES).forEach { File(file.path + it).delete() }
	}

	private fun open(): LocalDatabase = LocalDatabase(context, name).also { opened += it }

	private fun history(
		rowId: String,
		eventId: String = "event",
		owner: String = "alice",
		queueOp: String? = null,
	) = ContentValues().apply {
		put("row_id", rowId)
		put("event_id", eventId)
		put("owner", owner)
		put("title", "t")
		putNull("artist")
		put("percent", 80)
		put("at", 1L)
		put("status", "ok")
		putNull("tx_id")
		put("queued", 0)
		put("video_id", "dQw4w9WgXcQ")
		if (queueOp == null) putNull("queue_op_id") else put("queue_op_id", queueOp)
		putNull("service")
	}

	/** Schema objects of [type], minus SQLite's own and Android's locale table. */
	private fun names(db: SQLiteDatabase, type: String): Set<String> =
		db.rawQuery(
			"SELECT name FROM sqlite_master WHERE type = ? AND name NOT LIKE 'sqlite_%' " +
				"AND name <> 'android_metadata'",
			arrayOf(type),
		)
			.use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

	@Test
	fun createsTheCurrentVersionWithEveryTableAndIndex() {
		open().read { db ->
			assertEquals(LocalDatabaseSchema.VERSION, db.version)
			assertEquals(LocalDatabaseSchema.TABLES.toSet(), names(db, "table"))
			assertEquals(
				setOf(
					"history_owner_at", "history_owner_queue_op", "not_logged_owner_at",
					"posted_snap_cache_owner_at", "thread_preview_owner_at", "reply_notice_owner_read_at",
					"my_snap_owner_created",
				),
				names(db, "index"),
			)
			val journal = db.rawQuery("PRAGMA journal_mode", null).use { it.moveToFirst(); it.getString(0) }
			assertEquals("wal", journal.lowercase())
			val foreignKeys = db.rawQuery("PRAGMA foreign_keys", null).use { it.moveToFirst(); it.getInt(0) }
			assertEquals(0, foreignKeys)
		}
	}

	@Test
	fun rowIdAndEventIdAreIndependent() {
		val db = open()
		db.write {
			it.insertOrThrow("history", null, history("row-1", eventId = "listen"))
			it.insertOrThrow("history", null, history("row-2", eventId = "listen"))
		}
		db.read {
			val n = it.rawQuery("SELECT count(*) FROM history WHERE event_id = 'listen'", null)
				.use { c -> c.moveToFirst(); c.getInt(0) }
			assertEquals(2, n)
		}
		assertRejected { db.write { it.insertOrThrow("history", null, history("row-1", eventId = "other")) } }
	}

	@Test
	fun oneQueueOperationIsOneRowPerOwner() {
		val db = open()
		db.write {
			it.insertOrThrow("history", null, history("a", queueOp = "op"))
			it.insertOrThrow("history", null, history("b", owner = "bob", queueOp = "op"))
			it.insertOrThrow("history", null, history("c"))
			it.insertOrThrow("history", null, history("d"))
		}
		assertRejected { db.write { it.insertOrThrow("history", null, history("e", queueOp = "op")) } }
	}

	@Test
	fun ownersAreCheckedByTheSchema() {
		val db = open()
		assertRejected { db.write { it.insertOrThrow("history", null, history("x", owner = "Alice")) } }
		assertRejected { db.write { it.insertOrThrow("history", null, history("x", owner = "-")) } }
		assertRejected { db.write { it.insertOrThrow("history", null, history("x", owner = "")) } }
		db.write {
			it.insertOrThrow(
				"not_logged",
				null,
				ContentValues().apply {
					put("row_id", "n1"); put("owner", LocalOwner.SIGNED_OUT); put("title", "t")
					put("reason", "r"); put("at", 1L); put("played", 1L)
				},
			)
		}
	}

	@Test
	fun aFailedWriteLeavesNothingBehind() {
		val db = open()
		try {
			db.write {
				it.insertOrThrow("history", null, history("kept-out"))
				error("interrupted")
			}
			fail("the block's failure should propagate")
		} catch (expected: IllegalStateException) {
		}
		db.read {
			val n = it.rawQuery("SELECT count(*) FROM history", null).use { c -> c.moveToFirst(); c.getInt(0) }
			assertEquals(0, n)
		}
	}

	@Test
	fun rowsSurviveCloseAndReopen() {
		open().also { it.write { db -> db.insertOrThrow("history", null, history("durable")) } }.close()
		open().read {
			val id = it.rawQuery("SELECT row_id FROM history", null).use { c -> c.moveToFirst(); c.getString(0) }
			assertEquals("durable", id)
		}
	}

	@Test
	fun openingTouchesNoLegacyStore() {
		val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
		val queue = File(context.filesDir, "broadcast-queue.json")
		fun snapshot() = (prefsDir.listFiles().orEmpty().toList() + queue)
			.filter { it.exists() }
			.associate { it.name to (it.lastModified() to it.readBytes().toList()) }

		// An isolated test install has no legacy stores, which would make this
		// comparison vacuous. Seed synthetic ones under the real names — but
		// only where none exists, so an install holding real data is only ever
		// read here — and remove exactly what was seeded.
		val seededPrefs = LEGACY_PREFS.filterNot { File(prefsDir, "$it.xml").exists() }
		val seededQueue = !queue.exists()
		try {
			seededPrefs.forEach {
				assertTrue(
					context.getSharedPreferences(it, Context.MODE_PRIVATE).edit()
						.putString("issue41_device_test_seed", "synthetic").commit(),
				)
			}
			if (seededQueue) queue.writeText("[]")

			val before = snapshot()
			assertTrue("no legacy store to compare", before.keys.containsAll(LEGACY_PREFS.map { "$it.xml" }))
			open().write { it.insertOrThrow("history", null, history("r")) }
			open().read { db -> assertEquals(LocalDatabaseSchema.VERSION, db.version) }
			assertEquals(before, snapshot())
		} finally {
			seededPrefs.forEach { context.deleteSharedPreferences(it) }
			if (seededQueue) queue.delete()
		}
	}

	@Test
	fun aCorruptFileIsQuarantinedNotDeleted() {
		file.parentFile!!.mkdirs()
		val garbage = ByteArray(8192) { (it * 31 + 7).toByte() }
		file.writeBytes(garbage)
		// Companions as SQLite itself names them; contents are irrelevant, only
		// that each arrives in the quarantine byte for byte beside its main file.
		val companions = DatabaseQuarantine.COMPANION_SUFFIXES.associateWith { suffix ->
			ByteArray(512) { (it + suffix.length).toByte() }.also { File(file.path + suffix).writeBytes(it) }
		}
		val siblingsBefore = file.parentFile!!.list().orEmpty()
			.filterNot { it.startsWith(name) }.toSet()

		open().read { db ->
			assertEquals(LocalDatabaseSchema.VERSION, db.version)
			assertEquals(LocalDatabaseSchema.TABLES.toSet(), names(db, "table"))
		}

		val quarantine = DatabaseQuarantine.existing(file).single()
		assertArrayEquals(garbage, File(quarantine, name).readBytes())
		// The handler moves companions exactly as SQLite leaves them. SQLite's
		// own open-time recovery runs first and no handler can intercept it:
		// it rebuilds `-shm` (its shared-memory WAL index, not data), and may
		// roll back and remove a hot `-journal` or reset a `-wal` it judges
		// invalid — reproduced with the stock sqlite3 shell on these same
		// synthetic bytes. So the invariant is: the main file arrives byte for
		// byte, a companion that arrives with its original size is unchanged,
		// and the quarantine holds nothing but this set.
		val observed = companions.map { (suffix, bytes) ->
			val kept = File(quarantine, name + suffix)
			if (kept.isFile && kept.length() == bytes.size.toLong() && suffix != "-shm") {
				assertArrayEquals("$suffix changed in quarantine", bytes, kept.readBytes())
			}
			"$suffix=" + if (kept.isFile) "${kept.length()}B" else "absent"
		}
		Log.i("RustedWaxDbTest", "quarantine ${quarantine.name}: main=${garbage.size}B ${observed.joinToString(" ")}")
		val allowed = (listOf("") + DatabaseQuarantine.COMPANION_SUFFIXES).map { name + it }.toSet()
		assertTrue(quarantine.list().orEmpty().toSet().let { name in it && allowed.containsAll(it) })
		assertTrue("a fresh database replaced the corrupt one", file.exists())
		// Nothing else in the databases directory was touched.
		assertEquals(
			siblingsBefore,
			file.parentFile!!.list().orEmpty().filterNot { it.startsWith(name) }.toSet(),
		)
	}

	@Test
	fun aCorruptFileThatCannotBeQuarantinedFailsClosed() {
		file.parentFile!!.mkdirs()
		val garbage = ByteArray(8192) { (it * 17 + 3).toByte() }
		file.writeBytes(garbage)
		val wal = ByteArray(256) { it.toByte() }.also { File(file.path + "-wal").writeBytes(it) }
		// Occupy every quarantine name this clock can produce, as plain files,
		// so no quarantine directory can be made.
		val prefix = DatabaseQuarantine.prefixFor(file)
		val blockers = (listOf("${prefix}42") + (1..99).map { "${prefix}42-$it" })
			.map { File(file.parentFile, it).apply { writeText("blocker") } }
		try {
			assertRejected {
				LocalDatabase(context, name, nowMillis = { 42L }).also { opened += it }.read { }
			}
			assertArrayEquals("corrupt main left in place, unchanged", garbage, file.readBytes())
			assertArrayEquals("its log left beside it, unchanged", wal, File(file.path + "-wal").readBytes())
			assertTrue(DatabaseQuarantine.existing(file).isEmpty())
		} finally {
			blockers.forEach { it.delete() }
		}
	}

	@Test
	fun aNewerDatabaseIsRefusedAndLeftAsItWas() {
		file.parentFile!!.mkdirs()
		SQLiteDatabase.openOrCreateDatabase(file, null).use { it.version = LocalDatabaseSchema.VERSION + 1 }
		val bytes = file.readBytes()

		assertRejected { open().read { } }

		SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
			assertEquals(LocalDatabaseSchema.VERSION + 1, it.version)
		}
		assertTrue(DatabaseQuarantine.existing(file).isEmpty())
		assertEquals(bytes.size, file.readBytes().size)
	}

	/**
	 * Issue #47: a version 1 file written by the #41 build upgrades in place.
	 * Every row it held is still there, byte for byte, and the new catalog
	 * arrives empty beside them.
	 */
	@Test
	fun aVersionOneDatabaseUpgradesWithoutLosingARow() {
		file.parentFile!!.mkdirs()
		SQLiteDatabase.openOrCreateDatabase(file, null).use { v1 ->
			v1.beginTransaction()
			try {
				LocalDatabaseSchema.CREATE_V1.forEach(v1::execSQL)
				v1.insertOrThrow("history", null, history("h1", eventId = "e1"))
				v1.insertOrThrow("history", null, history("h2", eventId = "e2", owner = "bob"))
				v1.insertOrThrow(
					"posted_snap_cache",
					null,
					ContentValues().apply {
						put("owner", "alice"); put("content_id", "alice/p"); put("chain_body", "c")
						put("record_body", "r"); put("at", 5L)
					},
				)
				v1.insertOrThrow(
					"sync_checkpoint",
					null,
					ContentValues().apply {
						put("owner", "alice"); put("scope", "s"); put("value", "v"); put("updated_at", 1L)
					},
				)
				v1.version = 1
				v1.setTransactionSuccessful()
			} finally {
				v1.endTransaction()
			}
		}
		fun dump(db: SQLiteDatabase) = listOf("history", "posted_snap_cache", "sync_checkpoint").associateWith { table ->
			db.rawQuery("SELECT * FROM $table ORDER BY 1, 2", null).use { c ->
				buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
			}
		}
		val before = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use(::dump)

		open().read { db ->
			assertEquals(LocalDatabaseSchema.VERSION, db.version)
			assertEquals(LocalDatabaseSchema.TABLES.toSet(), names(db, "table"))
			assertEquals(before, dump(db))
			listOf("my_snap", "my_snap_tombstone").forEach { table ->
				val n = db.rawQuery("SELECT count(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
				assertEquals(table, 0, n)
			}
		}
		// And it stays upgraded: a second open runs no step again.
		open().read { db -> assertEquals(before, dump(db)) }
	}

	/**
	 * Issue #47: a version 2 file — the 47A build the A36 already runs — gains
	 * the tombstone table and keeps every catalog row it held.
	 */
	@Test
	fun aVersionTwoDatabaseUpgradesWithoutLosingACatalogRow() {
		file.parentFile!!.mkdirs()
		SQLiteDatabase.openOrCreateDatabase(file, null).use { v2 ->
			v2.beginTransaction()
			try {
				(LocalDatabaseSchema.CREATE_V1 + LocalDatabaseSchema.UPGRADE_V2).forEach(v2::execSQL)
				v2.insertOrThrow("history", null, history("h1", eventId = "e1"))
				v2.insertOrThrow(
					"my_snap",
					null,
					ContentValues().apply {
						put("owner", "alice"); put("author", "alice"); put("permlink", "p"); put("created_at", 7L)
						put("title", "Song"); put("indexed_at", 1L); put("updated_at", 1L)
					},
				)
				v2.version = 2
				v2.setTransactionSuccessful()
			} finally {
				v2.endTransaction()
			}
		}
		fun dump(db: SQLiteDatabase) = listOf("history", "my_snap").associateWith { table ->
			db.rawQuery("SELECT * FROM $table ORDER BY 1, 2", null).use { c ->
				buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
			}
		}
		val before = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use(::dump)
		open().read { db ->
			assertEquals(3, db.version)
			assertEquals(LocalDatabaseSchema.TABLES.toSet(), names(db, "table"))
			assertEquals(before, dump(db))
			val n = db.rawQuery("SELECT count(*) FROM my_snap_tombstone", null).use { it.moveToFirst(); it.getInt(0) }
			assertEquals(0, n)
		}
	}

	@Test
	fun tombstonesMustBeTheirOwnersAndUnique() {
		val db = open()
		fun stone(owner: String, author: String = owner, permlink: String = "p") = ContentValues().apply {
			put("owner", owner); put("author", author); put("permlink", permlink); put("deleted_at", 1L)
		}
		db.write { it.insertOrThrow("my_snap_tombstone", null, stone("alice")) }
		assertRejected { db.write { it.insertOrThrow("my_snap_tombstone", null, stone("alice")) } }
		assertRejected { db.write { it.insertOrThrow("my_snap_tombstone", null, stone("alice", author = "bob", permlink = "q")) } }
		assertRejected { db.write { it.insertOrThrow("my_snap_tombstone", null, stone("-", permlink = "q")) } }
		assertRejected { db.write { it.insertOrThrow("my_snap_tombstone", null, stone("alice", permlink = "")) } }
		db.write { it.insertOrThrow("my_snap_tombstone", null, stone("bob")) }
	}

	@Test
	fun myCatalogRowsMustBeTheirOwnersAndUnique() {
		val db = open()
		fun snap(owner: String, author: String = owner, permlink: String = "p") = ContentValues().apply {
			put("owner", owner); put("author", author); put("permlink", permlink); put("created_at", 1L)
			put("indexed_at", 1L); put("updated_at", 1L)
		}
		db.write { it.insertOrThrow("my_snap", null, snap("alice")) }
		assertRejected { db.write { it.insertOrThrow("my_snap", null, snap("alice")) } }
		assertRejected { db.write { it.insertOrThrow("my_snap", null, snap("alice", author = "bob", permlink = "q")) } }
		assertRejected { db.write { it.insertOrThrow("my_snap", null, snap("-", permlink = "q")) } }
		assertRejected { db.write { it.insertOrThrow("my_snap", null, snap("Alice", author = "Alice", permlink = "q")) } }
		assertRejected { db.write { it.insertOrThrow("my_snap", null, snap("alice", permlink = "")) } }
		// The same permlink under another account is another row.
		db.write { it.insertOrThrow("my_snap", null, snap("bob")) }
	}

	@Test
	fun aLogWithoutItsDatabaseIsNotOpenedOver() {
		file.parentFile!!.mkdirs()
		val wal = File(file.path + "-wal").apply { writeBytes(byteArrayOf(1, 2, 3)) }

		assertRejected { open().read { } }

		assertFalse(file.exists())
		assertArrayEquals(byteArrayOf(1, 2, 3), wal.readBytes())
	}

	private companion object {
		/**
		 * The plain SharedPreferences stores the app keeps today. The encrypted
		 * key and session vaults are deliberately left out: this suite never
		 * creates, reads or snapshots secret storage.
		 */
		val LEGACY_PREFS = listOf(
			"rustedwax_retained", "rustedwax_snap_drafts", "rustedwax_snap_reply_drafts",
			"rustedwax_pending_snaps", "rustedwax_pending_snap_replies", "rustedwax_posted_snap_cache",
			"rustedwax_snap_thread_previews", "rustedwax_snap_notices", "rustedwax_dedup",
			"rustedwax_finished_transport", "rustedwax_settings", "rustedwax_muted",
		)
	}

	private fun assertRejected(block: () -> Unit) {
		try {
			block()
			fail("expected the database to refuse this")
		} catch (expected: SQLiteConstraintException) {
		} catch (expected: SQLiteException) {
		}
	}
}
