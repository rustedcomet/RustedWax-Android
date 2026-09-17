package com.rustedwax.app.ui.snaps

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which composer is open, and what is in every draft.
 *
 * Hoisted above the tab switch on purpose. History leaves composition the moment
 * you touch another tab, so anything remembered *inside* the card is gone by the
 * time you come back — the open composer and the text in it both have to be held
 * somewhere that outlives the list.
 *
 * Two rules the spec is firm about are enforced here rather than in the card:
 *
 *  - only one composer is visibly expanded at a time;
 *  - opening a second one saves the first, it never discards it.
 *
 * The second rule is why every edit writes through to the store immediately. A
 * draft that only persists when something remembers to flush it is a draft that
 * is one crash, one rotation or one forgotten branch away from being lost, and
 * losing typed text silently is the single worst thing this screen could do.
 *
 * Deliberately knows nothing about playback, scrobbling, Hive or posting.
 */
@Stable
class SnapComposerState internal constructor(
	private val store: SnapDraftStore,
	/** Read late: the signed-in account can change while the screen is up. */
	private val account: () -> String?,
) {

	/** The key of the one open composer, or null when every card is collapsed. */
	var expanded by mutableStateOf<String?>(null)
		private set

	/**
	 * Drafts typed this session.
	 *
	 * A write-through cache, never a second source of truth. [draft] deliberately
	 * falls back to the store rather than filling this map on a miss: it is read
	 * from composition, and quietly writing snapshot state while the screen is
	 * being composed is how a list starts recomposing itself forever.
	 */
	private val cache = mutableStateMapOf<String, String>()

	fun key(eventId: String): String = SnapDraftKey.of(account(), eventId)

	/** What belongs in this card's box — typed this session, or left last time. */
	fun draft(key: String): String = cache[key] ?: store.read(key)

	/** True when a collapsed card should show the subtle Draft mark. */
	fun hasDraft(key: String): Boolean = draft(key).isNotEmpty()

	fun isExpanded(key: String): Boolean = expanded == key

	/**
	 * Open this card's composer, collapsing whatever was open.
	 *
	 * The outgoing draft needs no explicit save — it was written on every
	 * keystroke — but it is flushed anyway so that "opening another card keeps
	 * the first draft" is guaranteed by this function rather than by a habit
	 * somewhere else.
	 */
	fun open(key: String) {
		expanded?.takeIf { it != key }?.let { flush(it) }
		expanded = key
	}

	/** Collapse without touching the text: the draft stays exactly as typed. */
	fun collapse() {
		expanded?.let { flush(it) }
		expanded = null
	}

	fun edit(key: String, text: String) {
		cache[key] = text
		store.write(key, text)
	}

	/** The Discard half of the dialog: this one really is thrown away. */
	fun discard(key: String) {
		cache[key] = ""
		store.clear(key)
		if (expanded == key) expanded = null
	}

	private fun flush(key: String) {
		cache[key]?.let { store.write(key, it) }
	}
}
