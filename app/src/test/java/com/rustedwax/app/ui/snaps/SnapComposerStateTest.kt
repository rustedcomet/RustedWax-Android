package com.rustedwax.app.ui.snaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.rustedwax.app.scrobble.FinalizationRuntime
import org.junit.Test

/**
 * Draft identity and the one rule that matters most: nothing typed is ever lost
 * by opening something else.
 */
class SnapComposerStateTest {

	private class FakeStore(seed: Map<String, String> = emptyMap()) : SnapDraftStore {
		val values = seed.toMutableMap()
		var writes = 0

		override fun read(key: String): String = values[key] ?: ""

		override fun write(key: String, text: String) {
			writes += 1
			if (text.isEmpty()) values -= key else values[key] = text
		}

		override fun clear(key: String) {
			values -= key
		}

		override fun keys(): Set<String> = values.keys.toSet()
	}

	private fun state(store: SnapDraftStore, account: String? = "alice") =
		SnapComposerState(store, { account })

	@Test
	fun `a draft is keyed to the History row, not to the video`() {
		// The same video played twice is two History rows, each with its own
		// eventId and its own Snap. Nothing about the video enters the key.
		val a = SnapDraftKey.of("alice", "11111111-1111-1111-1111-111111111111")
		val b = SnapDraftKey.of("alice", "22222222-2222-2222-2222-222222222222")
		assertTrue(a != b)
	}

	/**
	 * The collision the old key could not survive.
	 *
	 * Two queued attempts on one video inside the same second produce History
	 * rows equal in every field the UI used to key on — same video, same whole
	 * second, `txId` null on both. `videoId@atEpochSec` gave them one key, so one
	 * draft and one open dialog served two rows. The row's own `eventId` is the
	 * only thing that separates them, and it is the only thing the key now uses.
	 */
	@Test
	fun `two same-video same-second rows cannot share a draft`() {
		val sameVideo = "dQw4w9WgXcQ"
		val sameSecond = 1_700_000_000L
		val first = record(sameVideo, sameSecond, eventId = "row-a")
		val second = record(sameVideo, sameSecond, eventId = "row-b")

		// Everything the old key looked at is identical…
		assertEquals(first.videoId, second.videoId)
		assertEquals(first.atEpochSec, second.atEpochSec)
		assertNull(first.txId)
		assertNull(second.txId)

		// …and the identities are still distinct.
		assertTrue(first.eventId != second.eventId)

		// Compose item keys differ — the list cannot hand one row's remembered
		// state, including an open "Discard Snap?", to the other.
		assertTrue(composeKey(first) != composeKey(second))

		// Draft keys differ, on the same account.
		val state = state(FakeStore())
		val firstDraft = state.key(first.eventId)
		val secondDraft = state.key(second.eventId)
		assertTrue(firstDraft != secondDraft)

		// And writing one leaves the other empty rather than merging them.
		state.edit(firstDraft, "belongs to the first row")
		assertEquals("belongs to the first row", state.draft(firstDraft))
		assertEquals("", state.draft(secondDraft))
		assertFalse(state.hasDraft(secondDraft))
	}

	@Test
	fun `the account prefix still isolates two identical rows`() {
		val shared = record("vid", 1_700_000_000L, eventId = "row-a")
		assertTrue(
			SnapDraftKey.of("alice", shared.eventId) !=
				SnapDraftKey.of("bob", shared.eventId),
		)
	}

	/** A History row as `note()` builds one, with the fields the old key used. */
	private fun record(videoId: String, atEpochSec: Long, eventId: String) =
		FinalizationRuntime.ScrobbleRecord(
			title = "Never Gonna Give You Up",
			artist = "Rick Astley",
			percentPlayed = 0,
			atEpochSec = atEpochSec,
			status = "queued — offline",
			txId = null,
			queued = true,
			videoId = videoId,
			eventId = eventId,
		)

	/** What `HistoryList` passes to `items(key = …)`. */
	private fun composeKey(record: FinalizationRuntime.ScrobbleRecord) = record.eventId

	@Test
	fun `drafts are scoped per Hive account`() {
		val alice = SnapDraftKey.of("alice", "vid-42")
		val bob = SnapDraftKey.of("bob", "vid-42")
		assertTrue(alice != bob)
	}

	@Test
	fun `a logged-out draft does not collide with a signed-in one`() {
		val anon = SnapDraftKey.of(null, "vid-42")
		val blank = SnapDraftKey.of("   ", "vid-42")
		val named = SnapDraftKey.of("alice", "vid-42")
		assertEquals(anon, blank)
		assertTrue(anon != named)
	}

	@Test
	fun `the account is read late, so switching users switches drafts`() {
		var who: String? = "alice"
		val state = SnapComposerState(FakeStore()) { who }
		val asAlice = state.key("event-7")
		who = "bob"
		assertTrue(asAlice != state.key("event-7"))
	}

	@Test
	fun `editing writes through immediately`() {
		val store = FakeStore()
		val state = state(store)
		val key = state.key("vid-1")

		state.edit(key, "half a thought")

		assertEquals("half a thought", store.read(key))
	}

	@Test
	fun `opening another composer keeps the first draft`() {
		val store = FakeStore()
		val state = state(store)
		val first = state.key("one-1")
		val second = state.key("two-2")

		state.open(first)
		state.edit(first, "kept")
		state.open(second)

		assertEquals(second, state.expanded)
		assertEquals("kept", store.read(first))
		assertEquals("kept", state.draft(first))
		assertTrue(state.hasDraft(first))
	}

	@Test
	fun `only one composer is expanded at a time`() {
		val state = state(FakeStore())
		val first = state.key("one-1")
		val second = state.key("two-2")

		state.open(first)
		assertTrue(state.isExpanded(first))
		state.open(second)

		assertFalse(state.isExpanded(first))
		assertTrue(state.isExpanded(second))
	}

	@Test
	fun `collapsing keeps the text exactly as typed`() {
		val store = FakeStore()
		val state = state(store)
		val key = state.key("vid-1")

		state.open(key)
		state.edit(key, "still here")
		state.collapse()

		assertNull(state.expanded)
		assertEquals("still here", state.draft(key))
		assertTrue(state.hasDraft(key))
	}

	/**
	 * The contract behind the tab-navigation fix.
	 *
	 * Leaving History collapses the open composer — History's `DisposableEffect`
	 * calls exactly this — and the draft has to come back untouched when the card
	 * is reopened. Collapsing must never be a quiet way of losing text.
	 */
	@Test
	fun `leaving History collapses the composer and reopening restores the draft`() {
		val store = FakeStore()
		val state = state(store)
		val key = state.key("vid-1")

		state.open(key)
		state.edit(key, "mid-sentence when I switched tabs")

		state.collapse() // what leaving History does

		assertNull(state.expanded)
		assertFalse(state.isExpanded(key))
		// Collapsed, but the card still advertises the draft.
		assertTrue(state.hasDraft(key))
		assertEquals("mid-sentence when I switched tabs", store.read(key))

		state.open(key) // tapping Snap again

		assertTrue(state.isExpanded(key))
		assertEquals("mid-sentence when I switched tabs", state.draft(key))
	}

	@Test
	fun `collapsing an untouched composer leaves no draft behind`() {
		// Opening Snap and leaving the tab without typing must not light the
		// Draft mark on a card the user never wrote anything on.
		val store = FakeStore()
		val state = state(store)
		val key = state.key("vid-2")

		state.open(key)
		state.collapse()

		assertNull(state.expanded)
		assertFalse(state.hasDraft(key))
		assertEquals("", store.read(key))
	}

	/**
	 * The identity History rows are keyed on survives a prepend.
	 *
	 * New scrobbles land at the top, so every existing row changes index. The
	 * card's per-item state — including an open "Discard Snap?" — must follow
	 * the event rather than the slot, which only holds if the event's identity
	 * does not depend on where it currently sits. The Compose-side reuse this
	 * protects against is verified on the device; what is checked here is the
	 * invariant the list key rests on.
	 */
	@Test
	fun `a History event keeps its identity when newer scrobbles are prepended`() {
		val state = state(FakeStore())
		val older = state.key("older-1700000100")
		val newer = state.key("newer-1700000200")

		// Whatever order they are asked for, and however many arrive later.
		assertEquals(older, state.key("older-1700000100"))
		assertTrue(older != newer)
		assertTrue(state.key("newest-1700000300") != older)
	}

	@Test
	fun `a discard aimed at one event cannot reach another`() {
		val store = FakeStore()
		val state = state(store)
		val older = state.key("older-1700000100")
		val newer = state.key("newer-1700000200")
		state.edit(older, "the draft being asked about")
		state.edit(newer, "an unrelated draft")

		// Answering the dialog discards the key it was opened for, and nothing
		// else — the newer row that took over the old list position is untouched.
		state.discard(older)

		assertEquals("", state.draft(older))
		assertFalse(state.hasDraft(older))
		assertEquals("an unrelated draft", state.draft(newer))
		assertTrue(state.hasDraft(newer))
	}

	@Test
	fun `discard is the only thing that throws a draft away`() {
		val store = FakeStore()
		val state = state(store)
		val key = state.key("vid-1")

		state.open(key)
		state.edit(key, "regrettable")
		state.discard(key)

		assertEquals("", state.draft(key))
		assertEquals("", store.read(key))
		assertFalse(state.hasDraft(key))
		assertNull(state.expanded)
	}

	@Test
	fun `a draft written last session is found again`() {
		val key = SnapDraftKey.of("alice", "vid-99")
		val store = FakeStore(mapOf(key to "from before"))
		val state = state(store)

		assertEquals("from before", state.draft(key))
		assertTrue(state.hasDraft(key))
	}

	@Test
	fun `clearing the box clears the Draft mark`() {
		val store = FakeStore()
		val state = state(store)
		val key = state.key("vid-1")

		state.edit(key, "typed")
		assertTrue(state.hasDraft(key))
		state.edit(key, "")

		assertFalse(state.hasDraft(key))
		assertEquals("", store.read(key))
	}

	@Test
	fun `an untouched card has no draft and no mark`() {
		val state = state(FakeStore())
		val key = state.key("never-opened-5")

		assertEquals("", state.draft(key))
		assertFalse(state.hasDraft(key))
		assertNull(state.expanded)
	}
}
