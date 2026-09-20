package com.rustedwax.app.ui.snaps

import android.content.SharedPreferences
import com.rustedwax.app.snaps.SnapReplyTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real store, against a `SharedPreferences` that fails the way Android's
 * does.
 *
 * ## Why this file exists separately from the controller's tests
 *
 * Those use a map-backed fake *store*, which is the right shape for asking what
 * the bell does. It cannot ask this question at all, because the thing under
 * test here is inside [SharedPreferencesSnapNoticeStore] — and a fake that
 * replaces the store replaces the code that would have to be wrong.
 *
 * ## The failure being modelled
 *
 * `SharedPreferences.Editor.commit()` is **memory-first**. It applies the whole
 * batch to the in-process map and only then attempts the disk write, returning
 * whether *that* succeeded. So a `false` return does not mean nothing happened:
 * it means the map already believes the write landed and the file does not.
 *
 * That asymmetry is the entire bug this file guards. Suppressing the
 * notification on `false` — which the store already did — is not enough, because
 * the notice key is left sitting in memory looking exactly like a reply that has
 * already been seen. Every later read filters it out, nothing retries, and a
 * genuinely new reply stays silent until the process dies.
 *
 * [Prefs] reproduces that ordering exactly, so removing the rollback from the
 * store makes the tests below fail rather than pass more quietly.
 */
class SnapNoticeStoreTest {

	private val alice = "alice"
	private val bob = "bob"
	private val root = SnapReplyTarget.of(alice, "rustedwax-snap-1000-aaaaaa")!!

	/**
	 * A `SharedPreferences` with Android's ordering and a disk that can be
	 * switched off.
	 *
	 * Only the surface this store uses is implemented; everything else throws,
	 * so a future call that quietly depends on unmodelled behaviour is a loud
	 * failure rather than a silent default.
	 */
	private class Prefs(
		val map: MutableMap<String, Any?> = linkedMapOf(),
	) : SharedPreferences {

		/** False makes the disk refuse, exactly as a full or broken one would. */
		var writable = true

		/** Batches that reached the disk, for asserting what actually persisted. */
		var commits = 0
		var failedCommits = 0
		var applies = 0

		override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)

		override fun contains(key: String): Boolean = map.containsKey(key)

		override fun getString(key: String, defValue: String?): String? =
			map[key] as? String ?: defValue

		override fun getLong(key: String, defValue: Long): Long =
			map[key] as? Long ?: defValue

		override fun edit(): SharedPreferences.Editor = Edit()

		private inner class Edit : SharedPreferences.Editor {
			private val pending = linkedMapOf<String, Any?>()
			private val removed = mutableSetOf<String>()

			override fun putString(key: String, value: String?): SharedPreferences.Editor =
				apply { pending[key] = value; removed -= key }

			override fun putLong(key: String, value: Long): SharedPreferences.Editor =
				apply { pending[key] = value; removed -= key }

			override fun remove(key: String): SharedPreferences.Editor =
				apply { removed += key; pending -= key }

			/**
			 * Memory first, disk second — the ordering that makes this a hazard.
			 */
			private fun commitToMemory() {
				removed.forEach { map.remove(it) }
				pending.forEach { (k, v) -> map[k] = v }
			}

			override fun commit(): Boolean {
				commitToMemory()
				return if (writable) {
					commits++
					true
				} else {
					failedCommits++
					false
				}
			}

			override fun apply() {
				commitToMemory()
				applies++
			}

			override fun putInt(key: String, value: Int) = unsupported()
			override fun putFloat(key: String, value: Float) = unsupported()
			override fun putBoolean(key: String, value: Boolean) = unsupported()
			override fun putStringSet(key: String, values: MutableSet<String>?) = unsupported()
			override fun clear() = unsupported()
			private fun unsupported(): Nothing =
				throw UnsupportedOperationException("not used by SnapNoticeStore")
		}

		override fun getInt(key: String, defValue: Int) = unsupported()
		override fun getFloat(key: String, defValue: Float) = unsupported()
		override fun getBoolean(key: String, defValue: Boolean) = unsupported()
		override fun getStringSet(key: String, defValues: MutableSet<String>?) = unsupported()
		override fun registerOnSharedPreferenceChangeListener(
			listener: SharedPreferences.OnSharedPreferenceChangeListener,
		) = unsupported()

		override fun unregisterOnSharedPreferenceChangeListener(
			listener: SharedPreferences.OnSharedPreferenceChangeListener,
		) = unsupported()

		private fun unsupported(): Nothing =
			throw UnsupportedOperationException("not used by SnapNoticeStore")
	}

	private fun store(prefs: Prefs) =
		SharedPreferencesSnapNoticeStore(prefs, nowEpochSec = { 1_000L })

	private fun notice(author: String, permlink: String) = SnapNotice(
		author = author,
		permlink = permlink,
		rootAuthor = root.author,
		rootPermlink = root.permlink,
		text = "hello",
		createdAtEpochSec = 1_000L,
	)

	private fun noticeKey(contentId: String) = SnapNoticeCodec.noticeKey(alice, contentId)

	private val seedKey get() = SnapNoticeCodec.seedKey(alice, root.contentId)

	/** The seeding read every conversation gets before anything can be news. */
	private fun seed(store: SnapNoticeStore) =
		store.record(alice, root.contentId, emptyList(), 1_000L)

	// ── the model itself ───────────────────────────────────────────────

	@Test
	fun `the fake really does mutate memory before failing`() {
		val prefs = Prefs()
		prefs.writable = false

		val ok = prefs.edit().putString("k", "v").commit()

		assertFalse("the disk refused", ok)
		assertTrue(
			"and the in-process map kept the write anyway — if this is ever " +
				"false, every test below is checking nothing",
			prefs.contains("k"),
		)
	}

	// ── A. a failed write of a genuinely new reply ─────────────────────

	@Test
	fun `a failed write answers null and leaves no trace`() {
		val prefs = Prefs()
		val store = store(prefs)
		seed(store)
		prefs.writable = false

		val answer = store.record(alice, root.contentId, listOf(notice(bob, "r1")), 2_000L)

		assertNull("a failed durable write is not an empty result", answer)
		assertFalse(
			"the notice key must not be left in memory looking like a reply that " +
				"has already been seen",
			prefs.contains(noticeKey("bob/r1")),
		)
	}

	@Test
	fun `a failed write is retried by the next ordinary read, exactly once`() {
		val prefs = Prefs()
		val store = store(prefs)
		seed(store)
		prefs.writable = false
		assertNull(store.record(alice, root.contentId, listOf(notice(bob, "r1")), 2_000L))

		// Nothing retried it. The conversation was simply read again, which is
		// what History does whenever the card is on screen.
		prefs.writable = true
		val first = store.record(alice, root.contentId, listOf(notice(bob, "r1")), 3_000L)
		val second = store.record(alice, root.contentId, listOf(notice(bob, "r1")), 4_000L)

		assertEquals(
			"the reply the failed write dropped is announced on the retry",
			listOf("bob/r1"),
			first?.map { it.contentId },
		)
		assertEquals("and never a second time", emptyList<SnapNotice>(), second)
		assertTrue(prefs.contains(noticeKey("bob/r1")))
	}

	@Test
	fun `a failed write does not disturb the rest of the file`() {
		val prefs = Prefs()
		val store = store(prefs)
		seed(store)
		store.record(alice, root.contentId, listOf(notice(bob, "old")), 2_000L)
		val before = LinkedHashMap(prefs.map)

		prefs.writable = false
		store.record(alice, root.contentId, listOf(notice(bob, "old"), notice(bob, "new")), 3_000L)

		assertEquals(
			"a failed write rolls back what it touched and nothing else",
			before,
			prefs.map,
		)
	}

	// ── B. a failed seeding write ──────────────────────────────────────

	@Test
	fun `a failed seeding write leaves the conversation unseeded`() {
		val prefs = Prefs()
		val store = store(prefs)
		prefs.writable = false

		store.record(alice, root.contentId, listOf(notice(bob, "old")), 2_000L)

		assertFalse(
			"a seed mark left behind by a failed write would make a first read " +
				"look like a second one",
			prefs.contains(seedKey),
		)
		assertFalse(prefs.contains(noticeKey("bob/old")))
	}

	@Test
	fun `a conversation seeds correctly on the read after a failed seeding write`() {
		val prefs = Prefs()
		val store = store(prefs)
		prefs.writable = false
		store.record(alice, root.contentId, listOf(notice(bob, "old")), 2_000L)

		prefs.writable = true
		val seeded = store.record(alice, root.contentId, listOf(notice(bob, "old")), 3_000L)

		assertEquals(
			"the replies already there are still history, not notifications",
			emptyList<SnapNotice>(),
			seeded,
		)
		assertTrue(prefs.contains(seedKey))
		// And only now is a later reply news.
		val later = store.record(
			alice,
			root.contentId,
			listOf(notice(bob, "old"), notice(bob, "later")),
			4_000L,
		)
		assertEquals(listOf("bob/later"), later?.map { it.contentId })
	}

	// ── C. pre-existing state must survive the rollback ────────────────

	@Test
	fun `a rollback cannot destroy an existing read flag`() {
		val prefs = Prefs()
		val store = store(prefs)
		seed(store)
		store.record(alice, root.contentId, listOf(notice(bob, "answered")), 2_000L)
		store.markRead(alice, "bob/answered")
		val answered = prefs.map[noticeKey("bob/answered")]

		prefs.writable = false
		store.record(
			alice,
			root.contentId,
			listOf(notice(bob, "answered"), notice(bob, "new")),
			3_000L,
		)

		assertEquals(
			"the answered row is not part of the write and must come through " +
				"untouched",
			answered,
			prefs.map[noticeKey("bob/answered")],
		)
		assertTrue(
			"and it is still marked read",
			SnapNoticeCodec.decode(prefs.map[noticeKey("bob/answered")] as String)!!.read,
		)
	}

	@Test
	fun `a rollback cannot destroy a tombstone`() {
		val prefs = Prefs()
		val store = store(prefs)
		seed(store)
		// A row whose display data has already been pruned away. Its key is the
		// only record that this reply has been seen.
		prefs.map[noticeKey("bob/pruned")] = SnapNoticeCodec.TOMBSTONE

		prefs.writable = false
		store.record(
			alice,
			root.contentId,
			listOf(notice(bob, "pruned"), notice(bob, "new")),
			3_000L,
		)

		assertEquals(
			"losing a tombstone to a rollback would resurrect an old reply, which " +
				"is the very thing tombstones exist to prevent",
			SnapNoticeCodec.TOMBSTONE,
			prefs.map[noticeKey("bob/pruned")],
		)
	}

	/**
	 * The rollback is exercised directly, and deliberately.
	 *
	 * Every key [SharedPreferencesSnapNoticeStore.record] writes is one that
	 * `contains` had just answered false for, so no write it builds today can
	 * roll back onto an existing value. A blanket `remove` of the touched keys
	 * would therefore pass every test that goes through `record` — verified by
	 * making that exact change and watching all thirteen still pass. The
	 * requirement is that the *mechanism* restores what was there, so the
	 * mechanism is what this calls.
	 */
	@Test
	fun `a rollback puts back exactly what was there`() {
		val prefs = Prefs()
		val store = store(prefs)
		prefs.map["s|alice|kept"] = 7_777L
		prefs.map["n|alice|kept"] = "a stored row"
		prefs.map["n|alice|gone"] = "written by the failed batch"

		store.restore(
			mapOf(
				"s|alice|kept" to 7_777L,
				"n|alice|kept" to "a stored row",
				// Absent before the batch, so the rollback removes it.
				"n|alice|gone" to null,
			),
		)

		assertEquals("a Long prior value comes back as a Long", 7_777L, prefs.map["s|alice|kept"])
		assertEquals("and a String as that String", "a stored row", prefs.map["n|alice|kept"])
		assertFalse("only a key that was absent is removed", prefs.contains("n|alice|gone"))
	}

	@Test
	fun `a rollback restores in memory even when its own write cannot reach disk`() {
		val prefs = Prefs()
		val store = store(prefs)
		prefs.map["n|alice|gone"] = "written by the failed batch"
		prefs.writable = false

		store.restore(mapOf("n|alice|gone" to null))

		assertFalse(
			"the map is what decides whether the next read retries, and apply() " +
				"updates it before anything is queued",
			prefs.contains("n|alice|gone"),
		)
	}

	// ── D. account isolation ───────────────────────────────────────────

	@Test
	fun `a failed write for one account leaves another account untouched`() {
		val prefs = Prefs()
		val store = store(prefs)
		seed(store)
		store.record(alice, root.contentId, listOf(notice(bob, "alices")), 2_000L)
		val carolRoot = "carol/rustedwax-snap-2000-cccccc"
		store.record("carol", carolRoot, listOf(notice(bob, "carols")), 2_000L)
		val before = LinkedHashMap(prefs.map)

		prefs.writable = false
		store.record(alice, root.contentId, listOf(notice(bob, "alices"), notice(bob, "new")), 3_000L)

		assertEquals("nothing of Carol's may move", before, prefs.map)
		assertTrue(prefs.contains(SnapNoticeCodec.noticeKey("carol", "bob/carols")))
	}

	@Test
	fun `a failed write does not stop another account recording`() {
		val prefs = Prefs()
		val store = store(prefs)
		seed(store)
		prefs.writable = false
		store.record(alice, root.contentId, listOf(notice(bob, "r1")), 2_000L)

		prefs.writable = true
		val carolRoot = "carol/rustedwax-snap-2000-cccccc"
		store.record("carol", carolRoot, emptyList(), 3_000L)
		val carol = store.record("carol", carolRoot, listOf(notice(bob, "hers")), 4_000L)

		assertEquals(listOf("bob/hers"), carol?.map { it.contentId })
		assertFalse(
			"and Alice's failed row is still absent, still retryable",
			prefs.contains(noticeKey("bob/r1")),
		)
	}

	// ── the success path is unchanged ──────────────────────────────────

	@Test
	fun `a successful write persists and answers what was new`() {
		val prefs = Prefs()
		val store = store(prefs)
		assertEquals(emptyList<SnapNotice>(), seed(store))

		val fresh = store.record(alice, root.contentId, listOf(notice(bob, "r1")), 2_000L)

		assertEquals(listOf("bob/r1"), fresh?.map { it.contentId })
		assertTrue(prefs.contains(seedKey))
		assertTrue(prefs.contains(noticeKey("bob/r1")))
		assertEquals("no rollback on a good write", 0, prefs.failedCommits)
	}

	@Test
	fun `an uneventful read opens no editor at all`() {
		val prefs = Prefs()
		val store = store(prefs)
		seed(store)
		store.record(alice, root.contentId, listOf(notice(bob, "r1")), 2_000L)
		val commits = prefs.commits
		val applies = prefs.applies

		assertEquals(
			emptyList<SnapNotice>(),
			store.record(alice, root.contentId, listOf(notice(bob, "r1")), 3_000L),
		)

		assertEquals("no commit", commits, prefs.commits)
		assertEquals("and no apply either", applies, prefs.applies)
	}
}
