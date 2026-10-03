package com.rustedwax.app.ui.snaps

import com.rustedwax.app.scrobble.InMemorySharedPreferences
import com.rustedwax.app.snaps.PostedSnapCacheEntry
import com.rustedwax.app.snaps.SnapThreadPreview
import com.rustedwax.app.storage.InMemoryMirrorRows
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Issue #41 Slice 3: root drafts, the posted-Snap cache, refresh stamps and
 * thread previews through their real legacy files (in memory) and an
 * in-memory database — seeded byte-for-byte as today's stores write them.
 */
class SnapLocalStateTest {

	private val inline = Executor { it.run() }

	private class Files {
		val drafts = InMemorySharedPreferences()
		val posted = InMemorySharedPreferences()
		val previews = InMemorySharedPreferences()
	}

	private class Tables {
		val drafts = InMemoryMirrorRows<String>()
		val posted = InMemoryMirrorRows<SnapLocalState.CachedSnap>()
		val refresh = InMemoryMirrorRows<Long>()
		val previews = InMemoryMirrorRows<SnapLocalState.StoredPreview>()
	}

	private var clock = 1_000_000L

	/** One "process" over [files], adopting [tables] — or failing to, when [tables] is null. */
	private fun boot(files: Files, tables: Tables?): SnapLocalState {
		val state = SnapLocalState(
			draftSource = SnapLocalCodec.draftSource(files.drafts),
			cacheSource = SnapLocalCodec.cacheSource(files.posted),
			refreshSource = SnapLocalCodec.refreshSource(files.posted),
			previewSource = SnapLocalCodec.previewSource(files.previews),
			nowMillis = { clock },
			writer = inline,
		)
		val fail = { throw IllegalStateException("database unavailable") }
		state.start(
			SnapLocalState.SnapLocalTables(
				drafts = { tables?.drafts ?: fail() },
				posted = { tables?.posted ?: fail() },
				refresh = { tables?.refresh ?: fail() },
				previews = { tables?.previews ?: fail() },
			),
		)
		return state
	}

	/** A cache entry exactly as `SharedPreferencesPostedSnapCache` writes it. */
	private fun legacyCache(files: Files, account: String, contentId: String, chain: String, record: String, at: Long) {
		files.posted.edit().putString("e|$account|$contentId", JSONObject().put("chain", chain).put("record", record).put("at", at).toString()).apply()
	}

	private fun preview(author: String, text: String, total: Int = 1) =
		SnapThreadPreview.Preview(listOf(SnapThreadPreview.Item(author, "re-$author-x1", text, false)), total)

	// ---- drafts -----------------------------------------------------------------------

	@Test
	fun `root drafts migrate under their exact keys, signed-out ones included`() {
		val files = Files()
		files.drafts.edit().putString("alice|event-1", "draft for alice").putString("-|event-2", "signed out").apply()
		val tables = Tables()

		val state = boot(files, tables)

		assertEquals("draft for alice", state.drafts.read("alice|event-1"))
		assertEquals("signed out", state.drafts.read("-|event-2"))
		assertEquals("", state.drafts.read("bob|event-1"))
		assertEquals(mapOf("alice|event-1" to "draft for alice", "-|event-2" to "signed out"), tables.drafts.rows)
	}

	@Test
	fun `an empty draft is still a deleted draft, in the database and the shadow`() {
		val files = Files()
		val tables = Tables()
		val state = boot(files, tables)
		state.drafts.write("alice|e1", "words")

		state.drafts.write("alice|e1", "")

		assertEquals("", state.drafts.read("alice|e1"))
		assertTrue(tables.drafts.rows.isEmpty())
		assertNull(files.drafts.getString("alice|e1", null))
		assertTrue(state.drafts.keys().isEmpty())
	}

	@Test
	fun `a draft typed while the database opens beats the stored one`() {
		val files = Files()
		val tables = Tables()
		boot(files, tables).drafts.write("alice|e1", "older")
		val held = ArrayDeque<Runnable>()
		val state = SnapLocalState(
			SnapLocalCodec.draftSource(files.drafts), SnapLocalCodec.cacheSource(files.posted),
			SnapLocalCodec.refreshSource(files.posted), SnapLocalCodec.previewSource(files.previews),
			nowMillis = { clock }, writer = { held.addLast(it) },
		)
		state.start(SnapLocalState.SnapLocalTables({ tables.drafts }, { tables.posted }, { tables.refresh }, { tables.previews }))

		state.drafts.write("alice|e1", "newer, typed during startup")
		while (held.isNotEmpty()) held.removeFirst().run()

		assertEquals("newer, typed during startup", state.drafts.read("alice|e1"))
		assertEquals("newer, typed during startup", tables.drafts.rows["alice|e1"])
	}

	@Test
	fun `drafts survive Forget Key and come back for the same account only`() {
		val files = Files()
		val tables = Tables()
		boot(files, tables).drafts.apply {
			write("alice|e1", "alice's")
			write("bob|e1", "bob's")
		}

		val reopened = boot(files, tables)
		assertEquals("alice's", reopened.drafts.read("alice|e1"))
		assertEquals("bob's", reopened.drafts.read("bob|e1"))
		assertEquals("", reopened.drafts.read("-|e1"))
	}

	// ---- posted cache and refresh ----------------------------------------------------

	@Test
	fun `the posted cache keeps owner, content id and both bodies exactly`() {
		val files = Files()
		legacyCache(files, "alice", "alice/rustedwax-snap-1-abc", "chain body", "record body", 42L)
		files.posted.edit().putLong("t|alice", 777L).putLong("t|bob", 888L).apply()
		val tables = Tables()

		val state = boot(files, tables)

		assertEquals(PostedSnapCacheEntry("chain body", "record body"), state.postedCache.read("alice", "alice/rustedwax-snap-1-abc"))
		assertNull(state.postedCache.read("bob", "alice/rustedwax-snap-1-abc"))
		assertEquals(SnapLocalState.CachedSnap("chain body", "record body", 42L), tables.posted.rows["alice|alice/rustedwax-snap-1-abc"])
		assertEquals(777L, state.postedCache.lastRefreshed("alice"))
		assertEquals(888L, state.postedCache.lastRefreshed("bob"))
		assertNull(state.postedCache.lastRefreshed("carol"))
		assertEquals(mapOf("alice" to 777L, "bob" to 888L), tables.refresh.rows)
	}

	@Test
	fun `refresh stamps are per account and shadow as longs in the old keys`() {
		val files = Files()
		val tables = Tables()
		val state = boot(files, tables)

		state.postedCache.markRefreshed("alice", 5L)
		state.postedCache.markRefreshed("bob", 9L)

		assertEquals(5L, state.postedCache.lastRefreshed("alice"))
		assertEquals(5L, files.posted.getLong("t|alice", -1))
		assertEquals(9L, files.posted.getLong("t|bob", -1))
	}

	@Test
	fun `bodies over the limit are drawn but not remembered, as before`() {
		val state = boot(Files(), Tables())
		state.postedCache.write("alice", "alice/p", PostedSnapCacheEntry("x".repeat(SnapLocalState.MAX_BODY_CHARS + 1), "r"))
		assertNull(state.postedCache.read("alice", "alice/p"))
	}

	@Test
	fun `a hundred cached Snaps per account, and one account never evicts another`() {
		val files = Files()
		val tables = Tables()
		val state = boot(files, tables)
		(1..100).forEach { clock++; state.postedCache.write("alice", "alice/p$it", PostedSnapCacheEntry("c$it", "r$it")) }
		(1..130).forEach { clock++; state.postedCache.write("bob", "bob/p$it", PostedSnapCacheEntry("c$it", "r$it")) }

		assertEquals(100, tables.posted.rows.keys.count { it.startsWith("alice|") })
		assertEquals(100, tables.posted.rows.keys.count { it.startsWith("bob|") })
		assertNull("bob's oldest went", state.postedCache.read("bob", "bob/p30"))
		assertEquals(PostedSnapCacheEntry("c31", "r31"), state.postedCache.read("bob", "bob/p31"))
		assertEquals(PostedSnapCacheEntry("c1", "r1"), state.postedCache.read("alice", "alice/p1"))
		assertEquals(200, files.posted.all.keys.count { it.startsWith("e|") })
	}

	@Test
	fun `a damaged cache entry is left behind without touching valid ones`() {
		val files = Files()
		legacyCache(files, "alice", "alice/good", "chain", "record", 1L)
		files.posted.edit().putString("e|alice|alice/bad", "{not json").apply()
		val tables = Tables()

		val state = boot(files, tables)

		assertEquals(PostedSnapCacheEntry("chain", "record"), state.postedCache.read("alice", "alice/good"))
		assertNull(state.postedCache.read("alice", "alice/bad"))
		assertEquals(1, tables.posted.rows.size)
		assertEquals(1, tables.posted.record!!.rejected)
		assertEquals("{not json", files.posted.getString("e|alice|alice/bad", null))
	}

	// ---- previews ----------------------------------------------------------------------

	@Test
	fun `previews restore exactly once and are re-validated on read`() {
		val files = Files()
		val good = SnapThreadPreviewCodec.encode(preview("bob", "nice one", total = 3), 500L)
		files.previews.edit()
			.putString("alice|alice/root-1", good)
			.putString("alice|alice/root-2", """{"at":1,"total":0,"items":[{"author":"NOT VALID","permlink":"x","text":"t","truncated":false}]}""")
			.apply()
		val tables = Tables()

		val state = boot(files, tables)
		val again = boot(files, tables)

		assertEquals(preview("bob", "nice one", total = 3), state.previews.read("alice|alice/root-1"))
		assertEquals(state.previews.read("alice|alice/root-1"), again.previews.read("alice|alice/root-1"))
		assertNull(state.previews.read("alice|alice/root-2"))
		assertEquals(1, tables.previews.rows.size)
		assertEquals("stored exactly as the old codec wrote it", good, SnapLocalCodec.previewJson(tables.previews.rows.values.single()))
	}

	@Test
	fun `sixty previews per owner, signed-out previews counted on their own`() {
		val files = Files()
		val tables = Tables()
		val state = boot(files, tables)
		(1..70).forEach { clock += 1000; state.previews.write("alice|alice/r$it", preview("bob", "a$it")) }
		(1..10).forEach { clock += 1000; state.previews.write("-|alice/r$it", preview("bob", "n$it")) }

		assertEquals(60, tables.previews.rows.keys.count { it.startsWith("alice|") })
		assertEquals(10, tables.previews.rows.keys.count { it.startsWith("-|") })
		assertNull(state.previews.read("alice|alice/r10"))
		assertEquals(preview("bob", "a11"), state.previews.read("alice|alice/r11"))
	}

	// ---- shadow and fallback -------------------------------------------------------------

	@Test
	fun `the shadow keeps today's formats, so the legacy stores still read it`() {
		val files = Files()
		val state = boot(files, Tables())
		state.drafts.write("alice|e1", "hello")
		state.postedCache.write("alice", "alice/p", PostedSnapCacheEntry("chain", "record"))
		state.previews.write("alice|alice/root", preview("bob", "hi"))

		assertEquals("hello", files.drafts.getString("alice|e1", null))
		val cached = JSONObject(files.posted.getString("e|alice|alice/p", null)!!)
		assertEquals("chain", cached.getString("chain"))
		assertEquals("record", cached.getString("record"))
		assertEquals(preview("bob", "hi"), SnapThreadPreviewCodec.decode(files.previews.getString("alice|alice/root", null)!!))
		// The generation entry is a string, which older builds' getString scans tolerate.
		assertTrue(files.drafts.all.values.all { it is String })
		assertTrue(files.previews.all.values.all { it is String })
	}

	@Test
	fun `a database that cannot open leaves every store working from its legacy file`() {
		val files = Files()
		files.drafts.edit().putString("alice|e1", "kept").apply()
		legacyCache(files, "alice", "alice/p", "chain", "record", 1L)
		files.previews.edit().putString("alice|alice/root", SnapThreadPreviewCodec.encode(preview("bob", "hi"), 1L)).apply()

		val state = boot(files, tables = null)
		state.drafts.write("alice|e2", "written in fallback")

		assertEquals("kept", state.drafts.read("alice|e1"))
		assertEquals(PostedSnapCacheEntry("chain", "record"), state.postedCache.read("alice", "alice/p"))
		assertEquals(preview("bob", "hi"), state.previews.read("alice|alice/root"))
		assertEquals("written in fallback", files.drafts.getString("alice|e2", null))

		// The next run that opens the database takes the fallback write.
		val tables = Tables()
		boot(files, tables)
		assertEquals("written in fallback", tables.drafts.rows["alice|e2"])
	}

	@Test
	fun `items are stored as the codec's own array, unchanged`() {
		val raw = SnapThreadPreviewCodec.encode(preview("bob", "line"), 9L)
		val stored = SnapLocalCodec.preview(raw)!!
		assertEquals(JSONObject(raw).getJSONArray("items").toString(), JSONArray(stored.itemsJson).toString())
		assertEquals(raw, SnapLocalCodec.previewJson(stored))
	}
	@Test
	fun `newer malformed draft keeps the database copy and raw bytes while real deletions import`() {
		val files = Files()
		val tables = Tables()
		val state = boot(files, tables)
		state.drafts.write("alice|damaged", "recoverable")
		state.drafts.write("alice|deleted", "remove me")
		val fallback = boot(files, tables = null)
		fallback.drafts.clear("alice|deleted")
		files.drafts.edit().putInt("alice|damaged", 42).commit()

		val reopened = boot(files, tables)
		assertEquals("recoverable", reopened.drafts.read("alice|damaged"))
		assertEquals("", reopened.drafts.read("alice|deleted"))
		assertEquals(mapOf("alice|damaged" to "recoverable"), tables.drafts.rows)
		assertEquals(42, files.drafts.getInt("alice|damaged", -1))
	}

}
