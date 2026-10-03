package com.rustedwax.app.ui.snaps

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rustedwax.app.storage.InMemoryKeyedSourceForDevice
import com.rustedwax.app.storage.LocalMirror
import com.rustedwax.app.storage.SqliteMirrorRows
import com.rustedwax.app.storage.db.DatabaseQuarantine
import com.rustedwax.app.storage.db.LegacyImportState
import com.rustedwax.app.storage.db.LocalDatabase
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #41 Slice 3: the four Snap local tables against real SQLite — keys
 * split and joined exactly, values round-trip, the owner rules hold, and a
 * mirror adopts and writes through them. Every test uses its own database
 * file; no SharedPreferences file is touched.
 */
@RunWith(AndroidJUnit4::class)
class SnapLocalTablesDeviceTest {

	private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private lateinit var name: String
	private lateinit var database: LocalDatabase

	@Before
	fun setUp() {
		name = "rustedwax-slice3-${UUID.randomUUID()}.db"
		database = LocalDatabase(context, name)
	}

	@After
	fun tearDown() {
		runCatching { database.close() }
		val file = context.getDatabasePath(name)
		DatabaseQuarantine.existing(file).forEach { it.deleteRecursively() }
		(listOf("") + DatabaseQuarantine.COMPANION_SUFFIXES).forEach { File(file.path + it).delete() }
	}

	@Test
	fun draftsRoundTripUnderExactKeysIncludingSignedOut() {
		val rows = SqliteMirrorRows(database, SnapLocalState.SOURCE_DRAFTS, SnapLocalCodec.DRAFT_TABLE)
		rows.transaction {
			it.put("alice|event-1", "hello")
			it.put("-|event-2", "signed out")
			it.put("alice|event-1", "hello again")
		}
		assertEquals(mapOf("alice|event-1" to "hello again", "-|event-2" to "signed out"), rows.transaction { it.all() })
		rows.transaction { it.delete("alice|event-1") }
		assertEquals(mapOf("-|event-2" to "signed out"), rows.transaction { it.all() })
	}

	@Test
	fun postedCacheAndRefreshRefuseTheSignedOutOwner() {
		val posted = SqliteMirrorRows(database, SnapLocalState.SOURCE_POSTED, SnapLocalCodec.POSTED_TABLE)
		val refresh = SqliteMirrorRows(database, SnapLocalState.SOURCE_REFRESH, SnapLocalCodec.REFRESH_TABLE)
		val row = SnapLocalState.CachedSnap("chain", "record", 42L)
		posted.transaction { it.put("alice|alice/rustedwax-snap-1-abc", row) }
		refresh.transaction { it.put("alice", 7L) }
		assertEquals(mapOf("alice|alice/rustedwax-snap-1-abc" to row), posted.transaction { it.all() })
		assertEquals(mapOf("alice" to 7L), refresh.transaction { it.all() })
		try {
			posted.transaction { it.put("-|x/y", row) }
			fail("the schema must refuse a signed-out cache row")
		} catch (expected: android.database.sqlite.SQLiteException) {
		}
		assertTrue(SnapLocalKeys.accountKeyStorable("alice|a/b"))
		assertTrue(!SnapLocalKeys.accountKeyStorable("-|a/b"))
	}

	@Test
	fun previewsKeepTheirItemsExactly() {
		val rows = SqliteMirrorRows(database, SnapLocalState.SOURCE_PREVIEWS, SnapLocalCodec.PREVIEW_TABLE)
		val raw = """{"at":5,"total":3,"items":[{"author":"bob","permlink":"re-x","text":"hi","truncated":false}]}"""
		val stored = SnapLocalCodec.preview(raw)!!
		rows.transaction { it.put("-|alice/root", stored) }
		val back = rows.transaction { it.all() }.getValue("-|alice/root")
		assertEquals(stored, back)
		assertEquals(raw, SnapLocalCodec.previewJson(back))
	}

	@Test
	fun aMirrorMigratesOnceAndWritesThrough() {
		val legacy = InMemoryKeyedSourceForDevice(mapOf("alice|e1" to "draft", "ALICE|e2" to "owner rule"))
		val rows = SqliteMirrorRows(database, SnapLocalState.SOURCE_DRAFTS, SnapLocalCodec.DRAFT_TABLE)
		val mirror = LocalMirror(SnapLocalState.SOURCE_DRAFTS, legacy, SnapLocalKeys::draftStorable, writer = { it.run() })
		mirror.start { rows }

		assertEquals(mapOf("alice|e1" to "draft"), rows.transaction { it.all() })
		assertEquals("owner rule", mirror.get("ALICE|e2"))
		rows.transaction { tx ->
			val record = tx.importRecord()!!
			assertEquals(LegacyImportState.VERIFIED, record.state)
			assertEquals(1, record.rejected)
		}

		mirror.put("alice|e3", "new")
		mirror.remove("alice|e1")
		assertEquals(mapOf("alice|e3" to "new"), rows.transaction { it.all() })
		assertEquals(mapOf("alice|e3" to "new", "ALICE|e2" to "owner rule"), legacy.entries)
		assertNull(mirror.get("alice|e1"))

		database.close()
		database = LocalDatabase(context, name)
		val reopened = LocalMirror(SnapLocalState.SOURCE_DRAFTS, legacy, SnapLocalKeys::draftStorable, writer = { it.run() })
		reopened.start { SqliteMirrorRows(database, SnapLocalState.SOURCE_DRAFTS, SnapLocalCodec.DRAFT_TABLE) }
		assertEquals(mapOf("alice|e3" to "new", "ALICE|e2" to "owner rule"), reopened.entries())
	}
}
