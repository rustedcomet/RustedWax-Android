package com.rustedwax.app.detect

import java.util.concurrent.ConcurrentHashMap

object NotificationHints {

	data class Hint(
		val host: String?,
		val subText: String?,
		val title: String?,
		val text: String?,
		val atMillis: Long = System.currentTimeMillis(),
	) {
		/** Everything observed on the notification surface. */
		fun describe(): String =
			"host=${host ?: "<none>"} subText=${quote(subText)} " +
				"title=${quote(title)} text=${quote(text)}"

		private fun quote(s: String?) = if (s == null) "<none>" else "\"$s\""
	}

	/**
	 * How many notifications back we remember per browser. Enough to cover a
	 * couple of tabs plus the one being replaced mid-track; small enough that a
	 * hint from an hour ago can never be reached.
	 */
	private const val MAX_PER_PACKAGE = 4

	/**
	 * How long an *unmatched* hint may still be used as a fallback. Title
	 * matching has no time limit — a match is a match — but guessing from the
	 * newest hint is only defensible while it's plausibly the current one.
	 */
	private const val FALLBACK_WINDOW_MS = 90_000L

	private val byPackage = ConcurrentHashMap<String, List<Hint>>()

	@Synchronized
	fun put(packageName: String, hint: Hint) {
		val existing = byPackage[packageName].orEmpty()
		// Chromium re-posts the same notification on every position update.
		// Replace the matching entry rather than filling the history with copies.
		val without = existing.filterNot {
			it.title == hint.title && it.host == hint.host
		}
		byPackage[packageName] = (listOf(hint) + without).take(MAX_PER_PACKAGE)

	}

	/** Newest hint for the package, regardless of which session it describes. */
	fun get(packageName: String): Hint? = byPackage[packageName]?.firstOrNull()

	/**
	 * The hint that most likely describes *this* session.
	 *
	 * In order of confidence:
	 *
	 *  1. Same media title — Chromium sets both from the same source, so this is
	 *     a binding, not a guess. No freshness limit.
	 *  2. Same artist/channel text, and recent.
	 *  3. The newest hint, but **only** when this is the browser's sole session
	 *     and the hint is recent — with one tab there is no other session it
	 *     could belong to.
	 *
	 * Returns null rather than guessing when several sessions are live and
	 * nothing matches. A missing hint costs a scrobble; a wrong one writes the
	 * wrong site to an immutable chain.
	 */
	fun bestFor(
		packageName: String,
		title: String?,
		artist: String?,
		soleSession: Boolean,
		now: Long = System.currentTimeMillis(),
	): Hint? {
		val hints = byPackage[packageName].orEmpty()
		if (hints.isEmpty()) return null

		if (!title.isNullOrBlank()) {
			hints.firstOrNull { it.title.equalsTrimmed(title) }?.let { return it }
		}
		if (!artist.isNullOrBlank()) {
			hints.firstOrNull {
				it.text.equalsTrimmed(artist) && now - it.atMillis <= FALLBACK_WINDOW_MS
			}?.let { return it }
		}
		val newest = hints.first()
		return newest.takeIf { soleSession && now - it.atMillis <= FALLBACK_WINDOW_MS }
	}

	/** Drops the hint a removed notification produced, leaving other tabs' alone. */
	@Synchronized
	fun remove(packageName: String, title: String?) {
		val existing = byPackage[packageName] ?: return
		val remaining = if (title == null) {
			emptyList()
		} else {
			existing.filterNot { it.title.equalsTrimmed(title) }
		}
		if (remaining.isEmpty()) byPackage.remove(packageName) else byPackage[packageName] = remaining
	}

	fun clear(packageName: String) {
		byPackage.remove(packageName)
	}

	/**
	 * Drop everything. Called when monitoring stops, so a Start minutes later
	 * can't resolve a session's identity from evidence harvested before the
	 * user asked us to stop looking.
	 */
	fun clearAll() {
		byPackage.clear()
	}

	private fun String?.equalsTrimmed(other: String?): Boolean {
		val a = this?.trim() ?: return false
		val b = other?.trim() ?: return false
		return a.isNotEmpty() && a.equals(b, ignoreCase = true)
	}
}
