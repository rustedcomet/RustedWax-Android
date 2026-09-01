package com.rustedwax.app.scrobble

import com.rustedwax.core.TrackInstanceId

/**
 * Run-local order of finalized playback instances and their verified ids.
 *
 * The placeholders matter: an unresolved or policy-rejected track is a real
 * boundary and must prevent two older ids from being mistaken for consecutive
 * playlist predecessors. Entries are ordered by the frozen track start rather
 * than by coroutine completion order because network finalizations can overlap.
 *
 * ## Why the key is an instance and not a second
 *
 * Entries used to be *identified* by `(packageName, startedAtEpochSec)` as well
 * as ordered by it. Two listens beginning inside the same wall-clock second were
 * then one entry, and both jobs of this class failed together: [begin] found the
 * second already present and created no boundary, and [remember] overwrote the
 * earlier track's verified id with the later one's. A caller then received a
 * "consecutive" pair that never played consecutively — and that pair is what
 * `resolveEvidenceFromAdjacentPredecessors` recovers a public playlist from, so
 * the consequence was a listen attributed through the wrong playlist rather than
 * a listen merely lost.
 *
 * Same-second transitions are ordinary. Chromium's metadata callback finalizes
 * the outgoing listen and stamps the incoming one's start from the same
 * `System.currentTimeMillis()`; the native exact-ID-less duration replacement
 * does the same; so does a foreground Short scrolled past inside a second.
 *
 * [TrackInstanceId] carries the token allocated by the Android session binding and
 * `ForegroundShortTracker` already allocate per listen, which survives
 * MediaSession recreation with the rest of the carried progress. The start
 * second remains the **ordering** key; the instance is only what identifies a
 * row. Ties in the same second are broken by the token, which is monotonic
 * within a run, so playback order is preserved rather than left to map order.
 */
internal class VerifiedPlaybackSequence(
	private val maxEntriesPerPackage: Int = 12,
) {
	data class Predecessor(val videoId: String, val title: String?)
	private data class Entry(
		val instance: TrackInstanceId,
		val startedAtEpochSec: Long,
		val videoId: String?,
		val title: String?,
	)

	private val byPackage = mutableMapOf<String, MutableList<Entry>>()

	private companion object {
		val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
		val ORDER = compareBy<Entry>({ it.startedAtEpochSec }, { it.instance.instanceToken })
	}

	@Synchronized
	fun begin(instance: TrackInstanceId, startedAtEpochSec: Long) {
		val entries = byPackage.getOrPut(instance.packageName) { mutableListOf() }
		if (entries.none { it.instance == instance }) {
			entries += Entry(instance, startedAtEpochSec, null, null)
			trim(entries)
		}
	}

	@Synchronized
	fun remember(
		instance: TrackInstanceId,
		startedAtEpochSec: Long,
		videoId: String,
		title: String?,
	) {
		if (!VIDEO_ID.matches(videoId)) return
		val entries = byPackage.getOrPut(instance.packageName) { mutableListOf() }
		val index = entries.indexOfFirst { it.instance == instance }
		val entry = Entry(instance, startedAtEpochSec, videoId, title)
		if (index >= 0) entries[index] = entry else entries += entry
		trim(entries)
	}

	/** The ids on the immediately preceding two finalized tracks, oldest first. */
	@Synchronized
	fun predecessors(instance: TrackInstanceId): Pair<Predecessor, Predecessor>? {
		val entries = byPackage[instance.packageName].orEmpty().sortedWith(ORDER)
		val current = entries.indexOfFirst { it.instance == instance }
		if (current < 2) return null
		val firstEntry = entries[current - 2]
		val secondEntry = entries[current - 1]
		val first = firstEntry.videoId ?: return null
		val second = secondEntry.videoId ?: return null
		if (first == second) return null
		return Predecessor(first, firstEntry.title) to Predecessor(second, secondEntry.title)
	}

	@Synchronized
	fun describe(packageName: String): String = byPackage[packageName].orEmpty()
		.sortedWith(ORDER)
		.joinToString(prefix = "[", postfix = "]") {
			"${it.startedAtEpochSec}:${it.videoId ?: "?"}"
		}

	@Synchronized
	fun clear(packageName: String? = null) {
		if (packageName == null) byPackage.clear() else byPackage.remove(packageName)
	}

	/**
	 * A detached copy holding what this one holds right now.
	 *
	 * For shadow finalization. A shadow run has to *read* the real adjacency —
	 * predecessor lookup is one of the decisions under comparison, and a run
	 * starting from an empty sequence would refuse identities the live path
	 * resolves — but it must not leave an entry behind. `begin` records every
	 * finalized target including unresolved ones, so a shadow run against the
	 * live instance permanently inserts a listen that never happened, and the
	 * next real track's predecessors are wrong.
	 *
	 * Entries are immutable values, so copying the lists is a deep copy.
	 */
	@Synchronized
	fun isolatedCopy(): VerifiedPlaybackSequence {
		val copy = VerifiedPlaybackSequence(maxEntriesPerPackage)
		for ((packageName, entries) in byPackage) {
			copy.byPackage[packageName] = entries.toMutableList()
		}
		return copy
	}

	private fun trim(entries: MutableList<Entry>) {
		entries.sortWith(ORDER)
		while (entries.size > maxEntriesPerPackage) entries.removeAt(0)
	}
}
