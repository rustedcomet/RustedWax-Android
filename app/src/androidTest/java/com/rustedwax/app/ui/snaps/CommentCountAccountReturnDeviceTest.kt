package com.rustedwax.app.ui.snaps

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rustedwax.app.snaps.SnapReply
import com.rustedwax.app.snaps.SnapReplyTarget
import com.rustedwax.app.snaps.SnapThreadReader
import com.rustedwax.app.storage.SqliteMirrorRows
import com.rustedwax.app.storage.db.DatabaseQuarantine
import com.rustedwax.app.storage.db.LocalDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #47 Stage 47D correction, on a real device: a My Snaps row's first
 * Comments read abandoned by an account switch must be asked again when the
 * account returns. The production controller, the production preview store on
 * a throwaway SQLite database, a real catalog row, and a fake Hive reader —
 * nothing here reaches the network or the app's own data.
 */
@RunWith(AndroidJUnit4::class)
class CommentCountAccountReturnDeviceTest {

	private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private lateinit var name: String
	private lateinit var prefsName: String
	private lateinit var database: LocalDatabase

	@Before
	fun setUp() {
		val id = UUID.randomUUID()
		name = "rustedwax-47d-count-$id.db"
		prefsName = "rustedwax-47d-count-$id"
		database = LocalDatabase(context, name)
	}

	@After
	fun tearDown() {
		runCatching { database.close() }
		val file = context.getDatabasePath(name)
		DatabaseQuarantine.existing(file).forEach { it.deleteRecursively() }
		(listOf("") + DatabaseQuarantine.COMPANION_SUFFIXES).forEach { File(file.path + it).delete() }
		context.deleteSharedPreferences(prefsName)
	}

	/** The production preview store, mirrored into this test's own database. */
	private fun previewStore(): SnapThreadPreviewStore {
		val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
		val state = SnapLocalState(
			draftSource = SnapLocalCodec.draftSource(prefs),
			cacheSource = SnapLocalCodec.cacheSource(prefs),
			refreshSource = SnapLocalCodec.refreshSource(prefs),
			previewSource = SnapLocalCodec.previewSource(prefs),
			writer = { it.run() },
		)
		state.start(
			SnapLocalState.SnapLocalTables(
				drafts = { SqliteMirrorRows(database, SnapLocalState.SOURCE_DRAFTS, SnapLocalCodec.DRAFT_TABLE) },
				posted = { SqliteMirrorRows(database, SnapLocalState.SOURCE_POSTED, SnapLocalCodec.POSTED_TABLE) },
				refresh = { SqliteMirrorRows(database, SnapLocalState.SOURCE_REFRESH, SnapLocalCodec.REFRESH_TABLE) },
				previews = { SqliteMirrorRows(database, SnapLocalState.SOURCE_PREVIEWS, SnapLocalCodec.PREVIEW_TABLE) },
			),
		)
		val deadline = System.currentTimeMillis() + 5_000
		while (!state.previewMirror.isReady() && System.currentTimeMillis() < deadline) Thread.sleep(20)
		assertTrue("preview mirror never became ready", state.previewMirror.isReady())
		return state.previews
	}

	/** The row exactly as My Snaps pages it out of the real catalog. */
	private fun catalogRoot(owner: String): SnapReplyTarget {
		val rows = SqliteMySnapRows(database)
		rows.upsert(
			listOf(MySnapRow(owner, owner, "rustedwax-snap-1790000000-abcdef", 1L, "e-1", "dQw4w9WgXcQ", "Song", "Band", null, "words")),
			1L,
		)
		val row = rows.page(owner, 10, null).single()
		return SnapReplyTarget.of(row.author, row.permlink)!!
	}

	private class Reader(var replies: List<SnapReply>?) : SnapThreadReader {
		var reads = 0
		var duringRead: (() -> Unit)? = null
		override fun read(rootAuthor: String, rootPermlink: String, viewer: String?): List<SnapReply>? {
			reads++
			duringRead?.invoke()
			return replies
		}
	}

	private fun controller(reader: Reader, previews: SnapThreadPreviewStore, account: () -> String?) =
		SnapThreadController(
			scope = CoroutineScope(Dispatchers.Unconfined),
			reader = { reader },
			publisher = { null },
			account = account,
			drafts = SharedPreferencesSnapReplyDraftStore(context),
			previewStore = previews,
			io = Dispatchers.Unconfined,
		)

	private fun settledRow(threads: SnapThreadController, root: SnapReplyTarget) =
		runBlocking { threads.loadWhenSettled(root) {} }

	@Test
	fun abandonedFirstReadIsAskedAgainWhenTheAccountReturns() {
		val root = catalogRoot("alice")
		val previews = previewStore()
		var who = "alice"
		val reader = Reader(listOf(SnapReply("bob", "r1", root.author, root.permlink, "hi", 1L)))
		reader.duringRead = { who = "bob" }
		val threads = controller(reader, previews) { who }

		// 1–3: Alice's uncached read starts, Bob signs in, Alice's answer is discarded.
		settledRow(threads, root)
		assertEquals(1, reader.reads)
		assertNull(threads.preview(root))

		// 4–6: Alice again, same controller; the visible row asks once more.
		who = "alice"
		reader.duringRead = null
		repeat(3) { settledRow(threads, root) }

		assertEquals(2, reader.reads)
		assertEquals(1, threads.preview(root)!!.total)
		assertTrue(threads.state(root) is SnapThreadLoad.Ready)
		assertNull("nothing was opened", threads.openThread)
		assertEquals("persisted for Alice", 1, previews.read(SnapDraftKey.of("alice", root.contentId))!!.total)
		assertNull("and for nobody else", previews.read(SnapDraftKey.of("bob", root.contentId)))
	}

	@Test
	fun aPersistedCountSurvivesARestartAndAnAbandonedRead() {
		val root = catalogRoot("alice")
		val previews = previewStore()
		val first = controller(Reader(listOf(SnapReply("bob", "r1", root.author, root.permlink, "hi", 1L))), previews) { "alice" }
		settledRow(first, root)
		assertEquals(1, first.preview(root)!!.total)

		// A new controller over the same database: the count is drawn at once,
		// and an abandoned read neither erases it nor sticks.
		var who = "alice"
		val reader = Reader(null)
		reader.duringRead = { who = "bob" }
		val restarted = controller(reader, previews) { who }
		settledRow(restarted, root)
		who = "alice"

		assertEquals(1, restarted.preview(root)!!.total)
		assertNull(restarted.state(root))
		assertEquals(1, previews.read(SnapDraftKey.of("alice", root.contentId))!!.total)
	}
}
